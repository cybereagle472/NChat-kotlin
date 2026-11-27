import { Hono } from 'hono';
import type { Context } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { limitSend } from '../middleware/rate-limit';
import { decodeExact, SPKI_BYTES } from '../lib/input';
import { mayMessage, shareGroup } from '../lib/contacts';
import { keyTablesExist, registeredIdentityKey } from '../lib/keys';
import { broadcastToUser } from '../lib/realtime';

/**
 * E2EE key infrastructure — public keys only.
 *
 * POST /keys/register  upsert identity + signed prekey (signature verified);
 *                      a NEW identity discards the old one-time prekeys.
 *                      With `only_if_registered: true` it replaces the signed
 *                      prekey of the identity that is registered, and does
 *                      nothing else: 409 when another identity, or none, is
 * POST /keys/prekeys   refill one-time prekeys (1..100,100-unconsumed cap),
 *                      only under the identity key that is registered
 * GET  /keys/prekeys   the caller's own unconsumed count and registered
 *                      identity key
 *
 * Keys are withdrawn by POST /auth/logout — signing out is what ends them.
 * GET  /keys/bundle/:userId  identity + signed prekey + one popped OTK,
 *                      only for callers who may message that user
 *
 * Wire format (locked by tests): SPKI DER of a P-256 key = exactly 91 bytes;
 * ECDSA signature = exactly 64 bytes (P1363 r||s, WebCrypto's native format —
 * Android converts DER→P1363 on upload, batch 2).
 */

const keys = new Hono<{ Bindings: Env; Variables: Variables }>();

// The key tables come with migration 0008. A server that was deployed before
// that migration ran cannot serve these routes — and must not look like one
// that is merely busy: an app that reads "try again later" holds messages
// back waiting for keys, while one that reads "not set up" carries on exactly
// as it did before end-to-end encryption existed. So a failure here is
// answered with 501 when, and only when, the tables are in fact missing.
keys.use('*', async (c, next) => {
  await next();
  if (c.error && !(await keyTablesExist(c.env))) {
    c.res = c.json({ error: 'End-to-end encryption is not set up on this server', code: 'e2ee_unavailable' }, 501);
  }
});

const SIGNATURE_BYTES = 64;
const MAX_PREKEYS_PER_CALL = 100;
const MAX_UNCONSUMED_PREKEYS = 100;
const IDENTITY_KEY_ERROR = 'identity_public_key must be P-256 SPKI (91 bytes)';
/** Another installation has registered since: this one's keys are not the account's any more. */
const REPLACED = { error: 'A different installation is registered for this account', code: 'identity_changed' };

/** Verify the signed-prekey signature with the uploader's identity key. */
async function signatureMatches(identitySpki: Uint8Array, prekeySpki: Uint8Array, signature: Uint8Array): Promise<boolean> {
  try {
    const identity = await crypto.subtle.importKey(
      'spki', identitySpki as BufferSource, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'],
    );
    return await crypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' }, identity, signature as BufferSource, prekeySpki as BufferSource,
    );
  } catch {
    return false;
  }
}

async function readJson(c: Context<{ Bindings: Env; Variables: Variables }>): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await c.req.json();
    return body !== null && typeof body === 'object' && !Array.isArray(body) ? body as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

// POST /keys/register — upsert the caller's public key material.
keys.post('/register', requireAuth, limitSend, async (c) => {
  const body = await readJson(c);
  if (!body) return c.json({ error: 'Invalid JSON body' }, 400);

  const identity = decodeExact(body.identity_public_key, SPKI_BYTES);
  const prekey = decodeExact(body.signed_prekey_public, SPKI_BYTES);
  const signature = decodeExact(body.signed_prekey_signature, SIGNATURE_BYTES);
  if (!identity || !prekey || !signature) {
    return c.json({ error: 'Key material must be P-256 SPKI (91 bytes) with a 64-byte signature' }, 400);
  }
  // Said or not said — never guessed. A request that meant "only while I am
  // still this account's phone" and were read as a plain registration would
  // do the very thing the condition is there to prevent.
  const onlyIfRegistered = body.only_if_registered;
  if (onlyIfRegistered !== undefined && typeof onlyIfRegistered !== 'boolean') {
    return c.json({ error: 'only_if_registered must be true or false' }, 400);
  }
  if (!(await signatureMatches(identity, prekey, signature))) {
    return c.json({ error: 'Signed prekey does not verify against the identity key' }, 400);
  }

  const userId = c.get('userId');
  // A phone replacing its signed prekey, which it does every week. It asked
  // whether it is still the account's phone some time ago — a moment, or the
  // night it then slept through — and the user may have signed in on another
  // phone since. So the question is asked again by the statement that
  // writes: nothing changes unless this identity key is the registered one,
  // and a sign-in that lands first always wins. Nobody is told anything:
  // the account has not moved, and its one-time prekeys stay good.
  if (onlyIfRegistered === true) {
    const replaced = await c.env.DB.prepare(
      `UPDATE user_crypto_keys
          SET signed_prekey_public = ?1, signed_prekey_signature = ?2, updated_at = datetime('now')
        WHERE user_id = ?3 AND identity_public_key = ?4
        RETURNING user_id`,
    ).bind(body.signed_prekey_public, body.signed_prekey_signature, userId, body.identity_public_key).first();
    return replaced ? c.json({ ok: true }) : c.json(REPLACED, 409);
  }
  // A different identity key means a different installation: the private
  // halves of the one-time prekeys uploaded before it no longer exist, and a
  // session started against one could never be opened. They go in the same
  // transaction as the new identity (the DELETE still sees the old row).
  // Registering the same identity again — with the same signed prekey or a
  // new one — leaves them alone.
  const discardStalePrekeys = c.env.DB.prepare(
    `DELETE FROM one_time_prekeys WHERE user_id = ?1
       AND EXISTS (SELECT 1 FROM user_crypto_keys WHERE user_id = ?1 AND identity_public_key <> ?2)`,
  ).bind(userId, body.identity_public_key);
  const upsert = c.env.DB.prepare(
    `INSERT INTO user_crypto_keys (user_id, identity_public_key, signed_prekey_public, signed_prekey_signature, updated_at)
     VALUES (?, ?, ?, ?, datetime('now'))
     ON CONFLICT(user_id) DO UPDATE SET
       identity_public_key = excluded.identity_public_key,
       signed_prekey_public = excluded.signed_prekey_public,
       signed_prekey_signature = excluded.signed_prekey_signature,
       updated_at = datetime('now')`,
  ).bind(userId, body.identity_public_key, body.signed_prekey_public, body.signed_prekey_signature);
  const previous = await registeredIdentityKey(c.env, userId);
  await c.env.DB.batch([discardStalePrekeys, upsert]);
  // Another phone held this account until now. Its encrypted chats can be
  // read on one phone only, so the account's connected phones are told at
  // once: the one that was replaced checks with the server and signs out,
  // instead of showing messages it cannot open until its next routine check.
  // The notice says only that something changed — no key travels in it.
  if (previous !== null && previous !== body.identity_public_key) {
    await broadcastToUser(c.env, userId, { type: 'keys:changed' });
  }
  return c.json({ ok: true });
});

// POST /keys/prekeys — refill one-time prekeys; the unconsumed pool is capped
// so a client bug can never balloon storage (backpressure as an explicit 400).
keys.post('/prekeys', requireAuth, limitSend, async (c) => {
  const body = await readJson(c);
  const publicKeys = body?.public_keys;
  if (!Array.isArray(publicKeys) || publicKeys.length === 0 || publicKeys.length > MAX_PREKEYS_PER_CALL) {
    return c.json({ error: `public_keys must be an array of 1..${MAX_PREKEYS_PER_CALL} keys` }, 400);
  }
  if (publicKeys.some((value) => decodeExact(value, SPKI_BYTES) === null)) {
    return c.json({ error: 'Each prekey must be P-256 SPKI (91 bytes)' }, 400);
  }
  // A one-time prekey is only usable by the installation that holds its
  // private half. Mixed into the pool of another installation it is handed
  // out with that one's identity key, and the first message sealed against
  // it can never be opened — so every upload names the identity it is for.
  const identity = body?.identity_public_key;
  if (!decodeExact(identity, SPKI_BYTES)) return c.json({ error: IDENTITY_KEY_ERROR }, 400);

  const userId = c.get('userId');
  if ((await registeredIdentityKey(c.env, userId)) !== identity) return c.json(REPLACED, 409);
  // Fast path only when the pool is already full — partial fills are the
  // conditional insert's job (it fits as many as the cap allows).
  const stored = (await c.env.DB.prepare('SELECT COUNT(*) AS n FROM one_time_prekeys WHERE user_id = ?')
    .bind(userId).first<{ n: number }>())?.n ?? 0;
  if (stored >= MAX_UNCONSUMED_PREKEYS) {
    return c.json({ error: `One-time prekey pool full (${stored} of ${MAX_UNCONSUMED_PREKEYS} unconsumed)` }, 400);
  }

  // Conditional insert: each row checks the live count inside its own
  // statement, so parallel refills can never push the pool past the cap
  // (a plain check-then-insert would race). RETURNING yields the row ids
  // so the client can pair each public key with the private half it keeps.
  // The identity is checked again in the same statement: a registration that
  // lands between the check above and this write must win.
  const insert = c.env.DB.prepare(
    `INSERT INTO one_time_prekeys (user_id, public_key)
      SELECT ?1, ?2 WHERE (SELECT COUNT(*) FROM one_time_prekeys WHERE user_id = ?1) < ${MAX_UNCONSUMED_PREKEYS}
        AND EXISTS (SELECT 1 FROM user_crypto_keys WHERE user_id = ?1 AND identity_public_key = ?3)
      RETURNING id`,
  );
  const results = await c.env.DB.batch(publicKeys.map((value) => insert.bind(userId, value, identity)));
  const ids = (results.flatMap((result) => result.results ?? []) as { id: number }[]).map((row) => row.id);
  if (ids.length === 0) {
    if ((await registeredIdentityKey(c.env, userId)) !== identity) return c.json(REPLACED, 409);
    return c.json({ error: `One-time prekey pool full (${MAX_UNCONSUMED_PREKEYS} unconsumed)` }, 400);
  }
  return c.json({ ok: true, stored: ids.length, ids }, 201);
});

// GET /keys/prekeys — the caller's own unconsumed count, for refill planning,
// and the identity key the account has registered: an installation sees from
// it whether it is still the one in use or was replaced by a sign-in elsewhere.
keys.get('/prekeys', requireAuth, limitSend, async (c) => {
  const userId = c.get('userId');
  const stored = (await c.env.DB.prepare('SELECT COUNT(*) AS n FROM one_time_prekeys WHERE user_id = ?')
    .bind(userId).first<{ n: number }>())?.n ?? 0;
  return c.json({ remaining: stored, identity_public_key: await registeredIdentityKey(c.env, userId) });
});

// GET /keys/bundle/:userId — serve public material; pop exactly one one-time
// prekey per fetch (single-statement DELETE ... RETURNING = atomic under
// concurrency, so two fetchers can never receive the same key).
//
// Only for callers who may message the target, who share a group with them,
// or the target itself: a bundle is the first step of a sealed message, and
// each fetch spends a one-time prekey — a stranger must be able to do
// neither. A refusal is the same 404 as "no keys", so it says nothing about
// the target's key state.
//
// Sharing a group counts because a member hands their group chain to every
// other member in a sealed one-to-one envelope, contact or not, and that
// takes a session. It does not make the two contacts: direct messages
// between them are refused as before.
keys.get('/bundle/:userId', requireAuth, limitSend, async (c) => {
  const targetId = c.req.param('userId');
  const userId = c.get('userId');
  if (!targetId || (targetId !== userId && !(await mayMessage(c.env, userId, targetId)) && !(await shareGroup(c.env, userId, targetId)))) {
    return c.json({ error: 'No keys registered for this user' }, 404);
  }
  const stored = await c.env.DB.prepare(
    'SELECT identity_public_key, signed_prekey_public, signed_prekey_signature FROM user_crypto_keys WHERE user_id = ?',
  ).bind(targetId).first<{ identity_public_key: string; signed_prekey_public: string; signed_prekey_signature: string }>();
  if (!stored) return c.json({ error: 'No keys registered for this user' }, 404);

  const popped = await c.env.DB.prepare(
    `DELETE FROM one_time_prekeys
      WHERE id = (SELECT id FROM one_time_prekeys WHERE user_id = ? ORDER BY id LIMIT 1)
      RETURNING id, public_key`,
  ).bind(targetId).first<{ id: number; public_key: string }>();

  return c.json({
    user_id: targetId,
    identity_public_key: stored.identity_public_key,
    signed_prekey_public: stored.signed_prekey_public,
    signed_prekey_signature: stored.signed_prekey_signature,
    one_time_prekey: popped ? { id: popped.id, public_key: popped.public_key } : null,
  });
});

export default keys;
