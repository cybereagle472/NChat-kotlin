import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * E2EE key infrastructure (Batch 1 of "fully encrypt like competitors"):
 * the server stores and serves PUBLIC key material only — identity keys,
 * signed prekeys with signatures verified at upload, and one-time prekeys
 * popped atomically on bundle fetch. Private keys never appear anywhere
 * in this contract. Wire encodings are locked: SPKI DER = exactly 91 bytes
 * (P-256), signature = exactly 64 bytes (ECDSA P1363, WebCrypto native).
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

async function signup(name: string) {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1` },
    body: JSON.stringify({ email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

/** Bundles are served only between users who may message each other. */
async function becomeFriends(a: { id: string }, b: { id: string }) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

async function makeKeys() {
  const identity = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const signedPrekey = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const identityPub = b64(new Uint8Array(await crypto.subtle.exportKey('spki', identity.publicKey)));
  const prekeyPubBytes = new Uint8Array(await crypto.subtle.exportKey('spki', signedPrekey.publicKey));
  const prekeyPub = b64(prekeyPubBytes);
  const signature = b64(new Uint8Array(await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekeyPubBytes,
  )));
  const impostor = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const impostorSignature = b64(new Uint8Array(await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' }, impostor.privateKey, prekeyPubBytes,
  )));
  return { identityPub, prekeyPub, signature, impostorSignature };
}

function post(path: string, token: string | null, body: unknown) {
  return SELF.fetch(`https://example.test${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1`,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

function get(path: string, token: string) {
  return SELF.fetch(`https://example.test${path}`, {
    headers: { Authorization: `Bearer ${token}`, 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1` },
  });
}

describe('register + bundle round-trip', () => {
  it('stores public keys and serves a bundle that pops one-time prekeys', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const keys = await makeKeys();

    const registered = await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });
    expect(registered.status).toBe(200);
    expect(await registered.json()).toEqual({ ok: true });

    const otk1 = await makeKeys();
    const otk2 = await makeKeys();
    const refill = await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [otk1.identityPub, otk2.identityPub] });
    expect(refill.status).toBe(201);
    expect(await refill.json()).toEqual({ ok: true, stored: 2, ids: [expect.any(Number), expect.any(Number)] });

    const first = await get(`/keys/bundle/${alice.id}`, bob.token);
    expect(first.status).toBe(200);
    const firstBody = await first.json() as {
      user_id: string; identity_public_key: string; signed_prekey_public: string;
      signed_prekey_signature: string; one_time_prekey: { id: number; public_key: string } | null;
    };
    expect(firstBody.user_id).toBe(alice.id);
    expect(firstBody.identity_public_key).toBe(keys.identityPub);
    expect(firstBody.signed_prekey_public).toBe(keys.prekeyPub);
    expect(firstBody.signed_prekey_signature).toBe(keys.signature);
    expect(firstBody.one_time_prekey).not.toBeNull();
    expect(firstBody.one_time_prekey!.public_key).toBe(otk1.identityPub);

    const second = await get(`/keys/bundle/${alice.id}`, bob.token);
    const secondBody = await second.json() as { one_time_prekey: { id: number; public_key: string } | null };
    expect(secondBody.one_time_prekey).not.toBeNull();
    expect(secondBody.one_time_prekey!.id).not.toBe(firstBody.one_time_prekey!.id);
    expect(secondBody.one_time_prekey!.public_key).toBe(otk2.identityPub);

    const third = await get(`/keys/bundle/${alice.id}`, bob.token);
    const thirdBody = await third.json() as { one_time_prekey: unknown };
    expect(thirdBody.one_time_prekey).toBeNull();
  }, 30000);

  it('re-registering rotates the signed prekey', async () => {
    const alice = await signup('alice');
    const first = await makeKeys();
    const second = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: first.identityPub,
      signed_prekey_public: first.prekeyPub,
      signed_prekey_signature: first.signature,
    });
    await post('/keys/register', alice.token, {
      identity_public_key: second.identityPub,
      signed_prekey_public: second.prekeyPub,
      signed_prekey_signature: second.signature,
    });
    const bundle = await get(`/keys/bundle/${alice.id}`, alice.token);
    const body = await bundle.json() as { identity_public_key: string; signed_prekey_public: string };
    expect(body.identity_public_key).toBe(second.identityPub);
    expect(body.signed_prekey_public).toBe(second.prekeyPub);
  }, 30000);
});

describe('register validation', () => {
  it('rejects unauthenticated, malformed, wrong-length, and forged requests', async () => {
    const alice = await signup('alice');
    const keys = await makeKeys();
    const valid = {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    };

    expect((await post('/keys/register', null, valid)).status).toBe(401);
    expect((await post('/keys/register', alice.token, { ...valid, identity_public_key: 'not base64!!' })).status).toBe(400);
    expect((await post('/keys/register', alice.token, { ...valid, identity_public_key: b64(new Uint8Array(10)) })).status).toBe(400);
    expect((await post('/keys/register', alice.token, { ...valid, signed_prekey_signature: b64(new Uint8Array(64)) })).status).toBe(400);
    // Signature that verifies against a different identity key must not pass.
    expect((await post('/keys/register', alice.token, { ...valid, signed_prekey_signature: keys.impostorSignature })).status).toBe(400);
    expect((await post('/keys/register', alice.token, { signed_prekey_public: keys.prekeyPub, signed_prekey_signature: keys.signature })).status).toBe(400);
    expect((await post('/keys/register', alice.token, { ...valid, signed_prekey_public: 'AAAA' })).status).toBe(400);
  }, 30000);
});

describe('one-time prekey refill', () => {
  it('enforces array shape, per-key validity, and the100-unconsumed cap', async () => {
    const alice = await signup('alice');
    const keys = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });

    expect((await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [] })).status).toBe(400);
    expect((await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: 'nope' })).status).toBe(400);
    expect((await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [b64(new Uint8Array(5))] })).status).toBe(400);
    const tooMany = Array.from({ length: 101 }, () => keys.identityPub);
    expect((await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: tooMany })).status).toBe(400);
    expect((await post('/keys/prekeys', null, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub] })).status).toBe(401);

    // Seed the cap directly:100 stored → one more must be refused...
    for (let i = 0; i < 100; i++) {
      await env.DB.prepare('INSERT INTO one_time_prekeys (user_id, public_key) VALUES (?, ?)')
        .bind(alice.id, keys.identityPub).run();
    }
    expect((await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub] })).status).toBe(400);

    // ...and after one is consumed, room exists again.
    await get(`/keys/bundle/${alice.id}`, alice.token);
    const after = await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub] });
    expect(after.status).toBe(201);
    expect(await after.json()).toEqual({ ok: true, stored: 1, ids: [expect.any(Number)] });
  }, 60000);
});

describe('bundle edge cases', () => {
  it('404s unknown or keyless users, 401s unauthenticated fetches', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    expect((await get(`/keys/bundle/${crypto.randomUUID()}`, bob.token)).status).toBe(404);
    expect((await get(`/keys/bundle/${alice.id}`, bob.token)).status).toBe(404); // alice has no keys yet
    expect((await get(`/keys/bundle/${alice.id}`, null as unknown as string)).status).toBe(401);
  }, 30000);

  it('parallel refills can never push the pool past the cap', async () => {
    const alice = await signup('alice');
    const keys = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });
    for (let i = 0; i < 99; i++) {
      await env.DB.prepare('INSERT INTO one_time_prekeys (user_id, public_key) VALUES (?, ?)')
        .bind(alice.id, keys.identityPub).run();
    }

    const [a, b] = await Promise.all([
      post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub] }),
      post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub] }),
    ]);
    expect([a.status, b.status].sort()).toEqual([201, 400]);
    const count = (await env.DB.prepare('SELECT COUNT(*) AS n FROM one_time_prekeys WHERE user_id = ?')
      .bind(alice.id).first<{ n: number }>())?.n;
    expect(count).toBe(100);
  }, 30000);

  it('concurrent fetches never hand out the same one-time key twice', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const keys = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });
    const expected = new Set<string>();
    for (let i = 0; i < 5; i++) {
      const k = await makeKeys();
      await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [k.identityPub] });
      expected.add(k.identityPub);
    }

    const fetches = await Promise.all([1, 2, 3, 4].map(() => get(`/keys/bundle/${alice.id}`, bob.token)));
    const bodies = await Promise.all(fetches.map((r) => r.json() as Promise<{ one_time_prekey: { id: number; public_key: string } | null }>));
    const ids = bodies.map((b) => b.one_time_prekey!.id);
    expect(new Set(ids).size).toBe(4);
    for (const b of bodies) expect(expected.has(b.one_time_prekey!.public_key)).toBe(true);

    const fifth = await get(`/keys/bundle/${alice.id}`, bob.token);
    const fifthBody = await fifth.json() as { one_time_prekey: { id: number } | null };
    expect(fifthBody.one_time_prekey).not.toBeNull();
    expect(ids).not.toContain(fifthBody.one_time_prekey!.id);

    const sixth = await get(`/keys/bundle/${alice.id}`, bob.token);
    expect((await sixth.json() as { one_time_prekey: unknown }).one_time_prekey).toBeNull();
  }, 60000);
});

describe('prekey status and refill ids', () => {
  it('reports the caller remaining count and returns row ids on refill', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const keys = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });

    const empty = await get('/keys/prekeys', alice.token);
    expect(empty.status).toBe(200);
    expect(await empty.json()).toEqual({ remaining: 0, identity_public_key: keys.identityPub });

    const refill = await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub, keys.prekeyPub] });
    expect(refill.status).toBe(201);
    const refillBody = await refill.json() as { ok: boolean; stored: number; ids: number[] };
    expect(refillBody.ok).toBe(true);
    expect(refillBody.stored).toBe(2);
    expect(refillBody.ids).toHaveLength(2);
    expect(refillBody.ids[1]).toBe(refillBody.ids[0] + 1);

    expect(await (await get('/keys/prekeys', alice.token)).json()).toEqual({ remaining: 2, identity_public_key: keys.identityPub });

    // The bundle pop consumes exactly the oldest id.
    const bundle = await get(`/keys/bundle/${alice.id}`, bob.token);
    const bundleBody = await bundle.json() as { one_time_prekey: { id: number } | null };
    expect(bundleBody.one_time_prekey!.id).toBe(refillBody.ids[0]);
    expect(await (await get('/keys/prekeys', alice.token)).json()).toEqual({ remaining: 1, identity_public_key: keys.identityPub });

    expect((await get('/keys/prekeys', null as unknown as string)).status).toBe(401);
  }, 30000);

  it('ids stay aligned with accepted keys when the cap truncates a batch', async () => {
    const alice = await signup('alice');
    const keys = await makeKeys();
    await post('/keys/register', alice.token, {
      identity_public_key: keys.identityPub,
      signed_prekey_public: keys.prekeyPub,
      signed_prekey_signature: keys.signature,
    });
    for (let i = 0; i < 99; i++) {
      await env.DB.prepare('INSERT INTO one_time_prekeys (user_id, public_key) VALUES (?, ?)')
        .bind(alice.id, keys.identityPub).run();
    }
    const refill = await post('/keys/prekeys', alice.token, { identity_public_key: keys.identityPub, public_keys: [keys.identityPub, keys.prekeyPub] });
    expect(refill.status).toBe(201);
    const body = await refill.json() as { stored: number; ids: number[] };
    expect(body.stored).toBe(1);
    expect(body.ids).toHaveLength(1);
    expect(typeof body.ids[0]).toBe('number');
  }, 30000);
});
