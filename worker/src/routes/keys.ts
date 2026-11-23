import { Hono } from 'hono';
import type { Context } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { limitSend } from '../middleware/rate-limit';

/**
 * E2EE key infrastructure — public keys only.
 *
 * POST /keys/register  upsert identity + signed prekey (signature verified)
 * POST /keys/prekeys   refill one-time prekeys (1..100,100-unconsumed cap)
 * GET  /keys/bundle/:userId  identity + signed prekey + one popped OTK
 *
 * Wire format (locked by tests): SPKI DER of a P-256 key = exactly 91 bytes;
 * ECDSA signature = exactly 64 bytes (P1363 r||s, WebCrypto's native format —
 * Android converts DER→P1363 on upload, batch 2).
 */

const keys = new Hono<{ Bindings: Env; Variables: Variables }>();

const SPKI_BYTES = 91;
const SIGNATURE_BYTES = 64;
const MAX_PREKEYS_PER_CALL = 100;
const MAX_UNCONSUMED_PREKEYS = 100;

/** Strict decode: canonical base64, exact byte length. null = reject. */
function decodeExact(value: unknown, bytes: number): Uint8Array | null {
  if (typeof value !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  let binary: string;
  try {
    binary = atob(value);
  } catch {
    return null;
  }
  if (binary.length !== bytes) return null;
  if (btoa(binary) !== value) return null; // non-canonical padding/bits
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = binary.charCodeAt(i);
  return out;
}

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
  if (!(await signatureMatches(identity, prekey, signature))) {
    return c.json({ error: 'Signed prekey does not verify against the identity key' }, 400);
  }

  const userId = c.get('userId');
  await c.env.DB.prepare(
    `INSERT INTO user_crypto_keys (user_id, identity_public_key, signed_prekey_public, signed_prekey_signature, updated_at)
     VALUES (?, ?, ?, ?, datetime('now'))
     ON CONFLICT(user_id) DO UPDATE SET
       identity_public_key = excluded.identity_public_key,
       signed_prekey_public = excluded.signed_prekey_public,
       signed_prekey_signature = excluded.signed_prekey_signature,
       updated_at = datetime('now')`,
  ).bind(userId, body.identity_public_key, body.signed_prekey_public, body.signed_prekey_signature).run();
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

  const userId = c.get('userId');
  const stored = (await c.env.DB.prepare('SELECT COUNT(*) AS n FROM one_time_prekeys WHERE user_id = ?')
    .bind(userId).first<{ n: number }>())?.n ?? 0;
  if (stored + publicKeys.length > MAX_UNCONSUMED_PREKEYS) {
    return c.json({ error: `One-time prekey pool full (${stored} of ${MAX_UNCONSUMED_PREKEYS} unconsumed)` }, 400);
  }

  // Conditional insert: each row checks the live count inside its own
  // statement, so parallel refills can never push the pool past the cap
  // (a plain check-then-insert would race).
  const insert = c.env.DB.prepare(
    `INSERT INTO one_time_prekeys (user_id, public_key)
      SELECT ?, ? WHERE (SELECT COUNT(*) FROM one_time_prekeys WHERE user_id = ?) < ${MAX_UNCONSUMED_PREKEYS}`,
  );
  const results = await c.env.DB.batch(publicKeys.map((value) => insert.bind(userId, value, userId)));
  const accepted = results.reduce((sum, result) => sum + (result.meta.changes ?? 0), 0);
  if (accepted === 0) {
    return c.json({ error: `One-time prekey pool full (${MAX_UNCONSUMED_PREKEYS} unconsumed)` }, 400);
  }
  return c.json({ ok: true, stored: accepted }, 201);
});

// GET /keys/bundle/:userId — serve public material; pop exactly one one-time
// prekey per fetch (single-statement DELETE ... RETURNING = atomic under
// concurrency, so two fetchers can never receive the same key).
keys.get('/bundle/:userId', requireAuth, limitSend, async (c) => {
  const targetId = c.req.param('userId');
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
