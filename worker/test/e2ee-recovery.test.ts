import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * What keeps end-to-end encrypted chats working when a device is replaced
 * (E2EE Batch 3, server half):
 *
 *  - a new identity key discards the one-time prekeys uploaded under the old
 *    one — their private halves died with the old installation;
 *  - a sealed message names the identity key it was sealed for, and is
 *    refused (409) when the recipient has since registered another, so the
 *    sender learns it must start a new session instead of writing into the
 *    void;
 *  - key bundles are served only to users who may message the target, so a
 *    stranger can neither read key state nor drain one-time prekeys.
 *
 * The server still handles PUBLIC key material only.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

type User = { id: string; token: string };

async function signup(name: string): Promise<User> {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1` },
    body: JSON.stringify({ email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

async function becomeFriends(a: User, b: User) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

async function block(blocker: User, blocked: User) {
  await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), blocker.id, blocked.id).run();
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

async function newKeyPair() {
  return await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
}

async function publicKey(pair: CryptoKeyPair) {
  return b64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey)));
}

/** A registration body: a (possibly reused) identity and a fresh prekey signed by it. */
async function registration(identity?: CryptoKeyPair) {
  const id = identity ?? await newKeyPair();
  const prekeyBytes = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, id.privateKey, prekeyBytes));
  return {
    identity: id,
    identityPub: await publicKey(id),
    body: {
      identity_public_key: await publicKey(id),
      signed_prekey_public: b64(prekeyBytes),
      signed_prekey_signature: b64(signature),
    },
  };
}

function headers(token: string | null) {
  return {
    'Content-Type': 'application/json',
    'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1`,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function post(path: string, token: string | null, body: unknown) {
  return SELF.fetch(`https://example.test${path}`, { method: 'POST', headers: headers(token), body: JSON.stringify(body) });
}

function get(path: string, token: string) {
  return SELF.fetch(`https://example.test${path}`, { headers: headers(token) });
}

async function register(user: User, identity?: CryptoKeyPair) {
  const keys = await registration(identity);
  const response = await post('/keys/register', user.token, keys.body);
  expect(response.status).toBe(200);
  return keys;
}

async function uploadPrekeys(user: User, count: number) {
  const keys: string[] = [];
  for (let i = 0; i < count; i++) keys.push(await publicKey(await newKeyPair()));
  const response = await post('/keys/prekeys', user.token, { public_keys: keys });
  expect(response.status).toBe(201);
  return keys;
}

async function remaining(user: User) {
  return (await (await get('/keys/prekeys', user.token)).json() as { remaining: number }).remaining;
}

async function history(reader: User, other: User) {
  const response = await get(`/messages/with/${other.id}`, reader.token);
  expect(response.status).toBe(200);
  return (await response.json() as { messages: { id: string; type: string; content: string }[] }).messages;
}

const envelope = JSON.stringify({
  v: 1,
  dh: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexampleexampleexampleexampleexampleexampleexampleexampleexampleexampleexampleexampleexampleexampleexample',
  n: 0,
  pn: 0,
  ct: 'QWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkw',
});

function sealed(receiver: User, recipientIdentity: unknown, clientId: string = crypto.randomUUID()) {
  return {
    receiver_id: receiver.id,
    content: envelope,
    type: 'e2ee',
    client_message_id: clientId,
    ...(recipientIdentity === undefined ? {} : { recipient_identity_key: recipientIdentity }),
  };
}

describe('a new identity key discards stale one-time prekeys', () => {
  it('removes the prekeys of the previous identity and serves none of them', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    await register(alice);
    await uploadPrekeys(alice, 3);
    expect(await remaining(alice)).toBe(3);

    // Alice reinstalls: a different identity key arrives.
    const fresh = await register(alice);
    expect(await remaining(alice)).toBe(0);

    const bundle = await (await get(`/keys/bundle/${alice.id}`, bob.token)).json() as {
      identity_public_key: string; one_time_prekey: unknown;
    };
    expect(bundle.identity_public_key).toBe(fresh.identityPub);
    expect(bundle.one_time_prekey).toBeNull();

    // Prekeys uploaded under the new identity are served normally.
    const uploaded = await uploadPrekeys(alice, 2);
    const next = await (await get(`/keys/bundle/${alice.id}`, bob.token)).json() as { one_time_prekey: { public_key: string } };
    expect(next.one_time_prekey.public_key).toBe(uploaded[0]);
  }, 60000);

  it('keeps them when only the signed prekey rotates, or the same material is registered again', async () => {
    const alice = await signup('alice');
    const first = await register(alice);
    await uploadPrekeys(alice, 3);

    await register(alice, first.identity); // same identity, new signed prekey
    expect(await remaining(alice)).toBe(3);

    const again = await post('/keys/register', alice.token, first.body); // byte-identical re-registration
    expect(again.status).toBe(200);
    expect(await remaining(alice)).toBe(3);
  }, 60000);

  it('keeps prekeys uploaded before the very first registration', async () => {
    const alice = await signup('alice');
    await uploadPrekeys(alice, 2);
    await register(alice);
    expect(await remaining(alice)).toBe(2);
  }, 60000);

  it('never touches another account', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await register(alice);
    await register(bob);
    await uploadPrekeys(alice, 2);
    await uploadPrekeys(bob, 4);

    await register(alice); // only alice changes identity
    expect(await remaining(alice)).toBe(0);
    expect(await remaining(bob)).toBe(4);
  }, 60000);

  it('a rejected registration changes nothing', async () => {
    const alice = await signup('alice');
    const first = await register(alice);
    await uploadPrekeys(alice, 2);

    const forged = await registration();
    const stranger = await registration();
    const refused = await post('/keys/register', alice.token, {
      ...forged.body, signed_prekey_signature: stranger.body.signed_prekey_signature,
    });
    expect(refused.status).toBe(400);
    expect(await remaining(alice)).toBe(2);
    const bundle = await (await get(`/keys/bundle/${alice.id}`, alice.token)).json() as { identity_public_key: string };
    expect(bundle.identity_public_key).toBe(first.identityPub);
  }, 60000);
});

describe('sealed sends are pinned to the recipient identity key', () => {
  it('stores a sealed message that names the current identity key, and never echoes the key', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const bobKeys = await register(bob);

    const sent = await post('/messages', alice.token, sealed(bob, bobKeys.identityPub));
    expect(sent.status).toBe(201);
    const message = (await sent.json() as { message: Record<string, unknown> }).message;
    expect(message.type).toBe('e2ee');
    expect(message.content).toBe(envelope);
    expect(message).not.toHaveProperty('recipient_identity_key');

    const rows = await history(bob, alice);
    expect(rows).toHaveLength(1);
    expect(rows[0].content).toBe(envelope);
    expect(rows[0]).not.toHaveProperty('recipient_identity_key');
  }, 60000);

  it('refuses a sealed send that does not name a well-formed identity key', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const bobKeys = await register(bob);

    const malformed: unknown[] = [
      undefined,                              // missing
      null,
      7,
      '',
      'not base64 !!',
      bobKeys.identityPub.slice(0, -4),       // too short
      bobKeys.identityPub + 'AAAA',           // too long
      bobKeys.identityPub.replace(/=*$/, ''), // padding stripped: not canonical
      [bobKeys.identityPub],
    ];
    for (const value of malformed) {
      const response = await post('/messages', alice.token, sealed(bob, value));
      expect(response.status, `recipient_identity_key = ${JSON.stringify(value)}`).toBe(400);
    }
    expect(await history(bob, alice)).toHaveLength(0);
  }, 60000);

  it('answers 409 and stores nothing when the recipient has registered another identity', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const oldKeys = await register(bob);
    const first = await post('/messages', alice.token, sealed(bob, oldKeys.identityPub));
    expect(first.status).toBe(201);

    const newKeys = await register(bob); // bob reinstalls
    const clientId = crypto.randomUUID();
    const refused = await post('/messages', alice.token, sealed(bob, oldKeys.identityPub, clientId));
    expect(refused.status).toBe(409);
    expect(await refused.json()).toEqual({ error: expect.any(String), code: 'identity_changed' });
    expect(await history(bob, alice)).toHaveLength(1);

    // The sender starts a new session and retries the SAME message: accepted exactly once.
    const retried = await post('/messages', alice.token, sealed(bob, newKeys.identityPub, clientId));
    expect(retried.status).toBe(201);
    expect(await history(bob, alice)).toHaveLength(2);
  }, 60000);

  it('answers 409 when the recipient has no keys at all', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const unrelated = await registration();

    const refused = await post('/messages', alice.token, sealed(bob, unrelated.identityPub));
    expect(refused.status).toBe(409);
    expect(await history(bob, alice)).toHaveLength(0);
  }, 60000);

  it('still replays an already stored message after the identity changed', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const oldKeys = await register(bob);
    const clientId = crypto.randomUUID();
    const first = await post('/messages', alice.token, sealed(bob, oldKeys.identityPub, clientId));
    expect(first.status).toBe(201);
    const id = (await first.json() as { message: { id: string } }).message.id;

    await register(bob);
    const replay = await post('/messages', alice.token, sealed(bob, oldKeys.identityPub, clientId));
    expect(replay.status).toBe(200);
    expect((await replay.json() as { message: { id: string } }).message.id).toBe(id);
    expect(await history(bob, alice)).toHaveLength(1);
  }, 60000);

  it('tells a stranger nothing about key state', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    const bobKeys = await register(bob);
    const wrong = await registration();

    // Not friends: the same refusal whether the named key is right, wrong or malformed-free.
    const right = await post('/messages', alice.token, sealed(bob, bobKeys.identityPub));
    const other = await post('/messages', alice.token, sealed(bob, wrong.identityPub));
    expect(right.status).toBe(403);
    expect(other.status).toBe(403);
    expect(await right.json()).toEqual(await other.json());
  }, 60000);

  it('ignores the field on plaintext messages', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);

    const sent = await post('/messages', alice.token, {
      receiver_id: bob.id, content: 'hello', type: 'text', client_message_id: crypto.randomUUID(),
      recipient_identity_key: 'whatever',
    });
    expect(sent.status).toBe(201);
    const message = (await sent.json() as { message: Record<string, unknown> }).message;
    expect(message.content).toBe('hello');
    expect(message).not.toHaveProperty('recipient_identity_key');
  }, 60000);
});

describe('key bundles are served only to users who may message the target', () => {
  it('refuses strangers and blocked contacts exactly like "no keys", and spends no prekey on them', async () => {
    const alice = await signup('alice');
    const friend = await signup('friend');
    const stranger = await signup('stranger');
    const blocked = await signup('blocked');
    const keyless = await signup('keyless');
    await becomeFriends(alice, friend);
    await becomeFriends(alice, blocked);
    await block(alice, blocked);
    await becomeFriends(stranger, keyless);
    await register(alice);
    await uploadPrekeys(alice, 3);

    // What "this user has no keys" looks like to someone who may ask.
    const noKeys = await get(`/keys/bundle/${keyless.id}`, stranger.token);
    expect(noKeys.status).toBe(404);
    const noKeysBody = await noKeys.json();

    for (const asker of [stranger, blocked]) {
      const refused = await get(`/keys/bundle/${alice.id}`, asker.token);
      expect(refused.status).toBe(404);
      expect(await refused.json()).toEqual(noKeysBody);
    }
    expect(await remaining(alice)).toBe(3);

    const allowed = await get(`/keys/bundle/${alice.id}`, friend.token);
    expect(allowed.status).toBe(200);
    expect((await allowed.json() as { one_time_prekey: unknown }).one_time_prekey).not.toBeNull();
    expect(await remaining(alice)).toBe(2);

    // A block in the other direction closes it too.
    await block(friend, alice);
    expect((await get(`/keys/bundle/${alice.id}`, friend.token)).status).toBe(404);
    expect(await remaining(alice)).toBe(2);
  }, 60000);

  it('lets an account read its own bundle', async () => {
    const alice = await signup('alice');
    const keys = await register(alice);
    const own = await get(`/keys/bundle/${alice.id}`, alice.token);
    expect(own.status).toBe(200);
    expect((await own.json() as { identity_public_key: string }).identity_public_key).toBe(keys.identityPub);
  }, 60000);
});
