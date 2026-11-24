import { Hono } from 'hono';
import type { Env, Variables } from '../types';
import { hashPassword, verifyPassword, passwordNeedsUpgrade } from '../lib/password';
import { signJWT, verifyJWT } from '../lib/jwt';
import { newId } from '../lib/id';
import { requireAuth } from '../middleware/auth';
import { clientAddressBucket, consumeLimit } from '../lib/rate-limit';
import { decodeExact, SPKI_BYTES } from '../lib/input';
import { withdrawIdentityKey } from '../lib/keys';

const auth = new Hono<{ Bindings: Env; Variables: Variables }>();

const ACCESS_TTL_SECONDS = 15 * 60; // 15 minutes
const REFRESH_TTL_DAYS = 30;
// Fixed decoy: perform the normal PBKDF2 work even when no account exists.
const DECOY_HASH = '100000:00000000000000000000000000000000:0000000000000000000000000000000000000000000000000000000000000000';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function userPublic(row: any) {
  return {
    id: row.id,
    email: row.email,
    full_name: row.full_name,
    username: row.username ?? undefined,
    avatar_url: row.avatar_url ?? undefined,
    bio: row.bio ?? undefined,
    is_admin: !!row.is_admin,
    created_at: row.created_at,
  };
}

// Refresh tokens rotate on every use (see /refresh below), and the access
// token they back is refreshed proactively every ~14 minutes while the app
// is open (see lib/apiClient.ts's scheduleProactiveRefresh) — so a single
// long-lived session can otherwise generate dozens of session rows a day,
// forever, with nothing ever deleting them. There's no Cron Trigger wired
// up for a scheduled sweep, so instead this piggybacks a cheap, bounded
// cleanup onto every token issuance (signup/login/refresh all call this):
// delete any session row that's actually expired. Combined with /refresh
// and /logout now deleting the row they're replacing/ending outright
// (instead of just flagging it revoked_at forever), the table naturally
// stays sized to roughly "one row per currently-active session" rather
// than accumulating every session anyone has ever had.
async function sweepExpiredSessions(env: Env): Promise<void> {
  try {
    await env.DB.prepare("DELETE FROM sessions WHERE expires_at < datetime('now')").run();
  } catch {
    /* best-effort housekeeping — never let this block an actual auth call */
  }
}

async function issueTokens(env: Env, userId: string) {
  const sid = newId();
  const expiresAt = new Date(Date.now() + REFRESH_TTL_DAYS * 86400 * 1000).toISOString();
  await env.DB.prepare('INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)')
    .bind(sid, userId, expiresAt)
    .run();
  // Awaited (not fire-and-forget): Workers can tear down the execution
  // context as soon as the response is sent, which would silently cancel
  // a detached promise here.
  await sweepExpiredSessions(env);
  const access_token = await signJWT({ sub: userId }, env.JWT_SECRET, ACCESS_TTL_SECONDS);
  const refresh_token = await signJWT({ sub: userId, sid }, env.JWT_SECRET, REFRESH_TTL_DAYS * 86400);
  return { access_token, refresh_token, expires_in: ACCESS_TTL_SECONDS };
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

auth.post('/signup', async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  // full_name/username/avatar are collected later by the Profile Setup screen —
  // signup itself only needs an email + password, matching the existing UI.
  const full_name = typeof body.full_name === 'string' ? body.full_name.trim() : '';

  if (!email || !password || email.length > 254 || password.length > 1024 || full_name.length > 200) {
    return c.json({ error: 'Invalid email, password, or name' }, 400);
  }
  if (!EMAIL_RE.test(email)) {
    return c.json({ error: 'Please enter a valid email address' }, 400);
  }
  if (password.length < 6) {
    return c.json({ error: 'Password must be at least 6 characters' }, 400);
  }

  const ip = clientAddressBucket(c.req.header('CF-Connecting-IP'));
  if (!ip) return c.json({ error: 'Client address unavailable' }, 503);
  if (!(await consumeLimit(c.env, 'signup-ip', ip, 10, 60 * 60 * 1000))) return c.json({ error: 'Too many attempts' }, 429);

  const existing = await c.env.DB.prepare('SELECT id FROM users WHERE email = ?').bind(email).first();
  if (existing) return c.json({ error: 'Email is already registered' }, 409);

  const id = newId(), sid = newId();
  const password_hash = await hashPassword(password);
  const expiresAt = new Date(Date.now() + REFRESH_TTL_DAYS * 86400 * 1000).toISOString();
  // Prepare the tokens before committing so signing errors cannot leave a
  // newly registered account without a usable signup response.
  const tokens = {
    access_token: await signJWT({ sub: id }, c.env.JWT_SECRET, ACCESS_TTL_SECONDS),
    refresh_token: await signJWT({ sub: id, sid }, c.env.JWT_SECRET, REFRESH_TTL_DAYS * 86400),
    expires_in: ACCESS_TTL_SECONDS,
  };
  // The email may have been taken after the lookup. The dependent rows must
  // only be inserted for this newly generated user id, in the same D1 batch.
  // An error in settings or session creation rolls the account back as well.
  const [created] = await c.env.DB.batch([
    c.env.DB.prepare(`INSERT INTO users (id, email, password_hash, full_name) VALUES (?1, ?2, ?3, ?4)
      ON CONFLICT(email) DO NOTHING RETURNING *`).bind(id, email, password_hash, full_name),
    c.env.DB.prepare('INSERT INTO privacy_settings (user_id) SELECT id FROM users WHERE id = ?').bind(id),
    c.env.DB.prepare('INSERT INTO notification_settings (user_id) SELECT id FROM users WHERE id = ?').bind(id),
    c.env.DB.prepare('INSERT INTO sessions (id, user_id, expires_at) SELECT ?1, id, ?3 FROM users WHERE id = ?2').bind(sid, id, expiresAt),
  ]);
  if (!created.meta.changes) return c.json({ error: 'Email is already registered' }, 409);
  await sweepExpiredSessions(c.env);
  return c.json({ user: userPublic(created.results[0]), ...tokens }, 201);
});

auth.post('/login', async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';

  if (!email || email.length > 254 || !password || password.length > 1024) return c.json({ error: 'Invalid email or password' }, 400);
  const ip = clientAddressBucket(c.req.header('CF-Connecting-IP'));
  if (!ip) return c.json({ error: 'Client address unavailable' }, 503);
  if (!(await consumeLimit(c.env, 'login-ip', ip, 60, 15 * 60 * 1000)) ||
      !(await consumeLimit(c.env, 'login-account', email, 10, 15 * 60 * 1000))) return c.json({ error: 'Too many attempts' }, 429);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const row = await c.env.DB.prepare('SELECT * FROM users WHERE email = ?').bind(email).first<any>();
  const valid = await verifyPassword(password, row?.password_hash ?? DECOY_HASH);
  if (!row || !valid) {
    return c.json({ error: 'Invalid email or password' }, 401);
  }

  if (passwordNeedsUpgrade(row.password_hash)) {
    const upgraded = await hashPassword(password);
    // Do not overwrite a concurrent password change.
    await c.env.DB.prepare('UPDATE users SET password_hash = ? WHERE id = ? AND password_hash = ?')
      .bind(upgraded, row.id, row.password_hash).run();
  }
  const tokens = await issueTokens(c.env, row.id);
  return c.json({ user: userPublic(row), ...tokens });
});

auth.post('/refresh', async (c) => {
  const { refresh_token } = await c.req.json().catch(() => ({ refresh_token: null }));
  if (!refresh_token) return c.json({ error: 'refresh_token is required' }, 400);

  const payload = await verifyJWT<{ sub: string; sid: string }>(refresh_token, c.env.JWT_SECRET);
  if (!payload) return c.json({ error: 'Invalid or expired refresh token' }, 401);

  // Rotate: the used refresh token is single-use, so there's nothing worth
  // keeping its row around for — delete it outright rather than just
  // flagging it revoked_at forever, which otherwise means the sessions
  // table grows by one permanent row on every single refresh (happening
  // every ~14 minutes for as long as anyone keeps the app open). Folding
  // the old "look it up, then separately delete it" into one atomic
  // DELETE also closes a small race: if this same (single-use) refresh
  // token were ever replayed twice at nearly the same instant, checking
  // `changes` here guarantees at most one of the two calls can ever
  // succeed, instead of both reading "still valid" before either delete
  // lands and both going on to mint their own session.
  const result = await c.env.DB.prepare('DELETE FROM sessions WHERE id = ? AND user_id = ?')
    .bind(payload.sid, payload.sub)
    .run();
  if (!result.meta.changes) return c.json({ error: 'Session has been revoked' }, 401);

  const tokens = await issueTokens(c.env, payload.sub);
  return c.json(tokens);
});

auth.post('/logout', async (c) => {
  const body = await c.req.json().catch(() => null);
  const refresh_token = body?.refresh_token;
  if (typeof refresh_token === 'string' && refresh_token) {
    const payload = await verifyJWT<{ sid: string }>(refresh_token, c.env.JWT_SECRET);
    if (payload?.sid) {
      // An explicit sign-out ends this session for good — delete its row
      // rather than leaving a revoked one behind forever.
      const ended = await c.env.DB.prepare('DELETE FROM sessions WHERE id = ? RETURNING user_id')
        .bind(payload.sid).first<{ user_id: string }>();
      // Signing out erases the phone's private keys. Naming its identity key
      // here withdraws the public half too, so senders are told instead of
      // sealing messages nobody can open. Only a session that was still alive
      // may do this, and only for the keys it names (see withdrawIdentityKey).
      const identity = body.identity_public_key;
      if (ended && typeof identity === 'string' && decodeExact(identity, SPKI_BYTES)) {
        // Signing out has happened either way; it must not fail over this.
        await withdrawIdentityKey(c.env, ended.user_id, identity).catch(() => false);
      }
    }
  }
  return c.json({ success: true });
});

auth.get('/me', requireAuth, async (c) => {
  const row = await c.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(c.get('userId')).first();
  if (!row) return c.json({ error: 'Not found' }, 404);
  return c.json({ user: userPublic(row) });
});

export default auth;
