import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * The parts of end-to-end encryption that follow a message and a device
 * through their lives (E2EE Batch 3, server half, continued):
 *
 *  - a sealed message is edited by replacing its envelope with another
 *    envelope — never with readable text — under the same rules as sending;
 *  - one-time prekeys are accepted only from the installation whose identity
 *    key is the registered one, so a phone that has been replaced cannot mix
 *    keys nobody can use into the account's pool;
 *  - signing out withdraws the installation's keys, and only its own:
 *    afterwards senders are told instead of sealing for keys that no longer
 *    exist;
 *  - whatever text may be sent in the clear also fits once it is sealed.
 *
 * The server still handles PUBLIC key material and opaque envelopes only.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

type User = { id: string; token: string; refresh: string; email: string };

function headers(token: string | null) {
  return {
    'Content-Type': 'application/json',
    // Never the ::/64 that the signup-limit test in the realtime suite exhausts.
    'CF-Connecting-IP': `2001:db8:1:${crypto.randomUUID().slice(0, 4)}::1`,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function send(method: string, path: string, token: string | null, body?: unknown) {
  return SELF.fetch(`https://example.test${path}`, {
    method,
    headers: headers(token),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function signup(name: string): Promise<User> {
  const email = `${name}-${crypto.randomUUID()}@example.test`;
  const response = await send('POST', '/auth/signup', null, { email, password: 'password123', full_name: name });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string; refresh_token: string };
  return { id: body.user.id, token: body.access_token, refresh: body.refresh_token, email };
}

/** The same account signing in on another phone: a second, independent session. */
async function signInAgain(user: User): Promise<User> {
  const response = await send('POST', '/auth/login', null, { email: user.email, password: 'password123' });
  expect(response.status).toBe(200);
  const body = await response.json() as { access_token: string; refresh_token: string };
  return { ...user, token: body.access_token, refresh: body.refresh_token };
}

/** Signing out, as the app does it: the session's refresh token and the identity key the phone is erasing. */
async function signOut(user: User, identityKey?: unknown) {
  const response = await send('POST', '/auth/logout', null, {
    refresh_token: user.refresh, ...(identityKey === undefined ? {} : { identity_public_key: identityKey }),
  });
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ success: true });
}

async function sessionIsAlive(user: User) {
  return (await send('POST', '/auth/refresh', null, { refresh_token: user.refresh })).status === 200;
}

async function becomeFriends(a: User, b: User) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

async function newKeyPair() {
  return await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
}

async function publicKey(pair?: CryptoKeyPair) {
  return b64(new Uint8Array(await crypto.subtle.exportKey('spki', (pair ?? await newKeyPair()).publicKey)));
}

/** Registers a new installation for [user]; returns its identity key (base64 SPKI). */
async function register(user: User): Promise<string> {
  const identity = await newKeyPair();
  const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekey));
  const identityPub = await publicKey(identity);
  const response = await send('POST', '/keys/register', user.token, {
    identity_public_key: identityPub, signed_prekey_public: b64(prekey), signed_prekey_signature: b64(signature),
  });
  expect(response.status).toBe(200);
  return identityPub;
}

async function status(user: User) {
  const response = await send('GET', '/keys/prekeys', user.token);
  expect(response.status).toBe(200);
  return await response.json() as { remaining: number; identity_public_key: string | null };
}

const envelope = (label: string) => JSON.stringify({ v: 1, dh: 'ZGg=', n: 0, pn: 0, ct: btoa(label) });

type Row = { id: string; type: string; content: string; is_edited: boolean };

/** Alice sends Bob one sealed message; both are friends and Bob has keys. */
async function sealedConversation() {
  const alice = await signup('alice');
  const bob = await signup('bob');
  await becomeFriends(alice, bob);
  const bobKey = await register(bob);
  const sent = await send('POST', '/messages', alice.token, {
    receiver_id: bob.id, content: envelope('original'), type: 'e2ee',
    client_message_id: crypto.randomUUID(), recipient_identity_key: bobKey,
  });
  expect(sent.status).toBe(201);
  const message = (await sent.json() as { message: Row }).message;
  return { alice, bob, bobKey, message };
}

async function stored(reader: User, other: User, id: string): Promise<Row> {
  const response = await send('GET', `/messages/with/${other.id}`, reader.token);
  expect(response.status).toBe(200);
  const row = (await response.json() as { messages: Row[] }).messages.find((message) => message.id === id);
  expect(row).toBeDefined();
  return row!;
}

describe('editing a sealed message', () => {
  it('replaces the envelope with another envelope and keeps the message sealed', async () => {
    const { alice, bob, bobKey, message } = await sealedConversation();

    const edited = await send('PATCH', `/messages/${message.id}`, alice.token, {
      content: envelope('edited'), recipient_identity_key: bobKey,
    });
    expect(edited.status).toBe(200);
    const body = (await edited.json() as { message: Row & Record<string, unknown> }).message;
    expect(body).toMatchObject({ id: message.id, type: 'e2ee', content: envelope('edited'), is_edited: true });
    // The key is compared, never stored and never echoed.
    expect(JSON.stringify(body)).not.toContain(bobKey);

    expect(await stored(bob, alice, message.id)).toMatchObject({ type: 'e2ee', content: envelope('edited'), is_edited: true });
  }, 60000);

  it('never lets readable text, or a request without the recipient key, replace an envelope', async () => {
    const { alice, bob, message } = await sealedConversation();

    for (const body of [
      { content: 'now in the clear' },
      { content: envelope('edited'), recipient_identity_key: 'not-a-key' },
      { content: envelope('edited'), recipient_identity_key: b64(new Uint8Array(90)) },
      { content: envelope('edited'), recipient_identity_key: 7 },
    ]) {
      expect((await send('PATCH', `/messages/${message.id}`, alice.token, body)).status).toBe(400);
    }
    expect(await stored(bob, alice, message.id)).toMatchObject({ type: 'e2ee', content: envelope('original'), is_edited: false });
  }, 60000);

  it('refuses an edit sealed for keys the recipient no longer has, and leaves the message as it was', async () => {
    const { alice, bob, bobKey, message } = await sealedConversation();
    const newKey = await register(bob); // bob reinstalls

    const stale = await send('PATCH', `/messages/${message.id}`, alice.token, {
      content: envelope('edited'), recipient_identity_key: bobKey,
    });
    expect(stale.status).toBe(409);
    expect(await stale.json()).toMatchObject({ code: 'identity_changed' });
    expect(await stored(bob, alice, message.id)).toMatchObject({ content: envelope('original'), is_edited: false });

    const fresh = await send('PATCH', `/messages/${message.id}`, alice.token, {
      content: envelope('edited for the new phone'), recipient_identity_key: newKey,
    });
    expect(fresh.status).toBe(200);
  }, 60000);

  it('answers a sender who may no longer message the recipient without revealing key state', async () => {
    const { alice, bob, bobKey, message } = await sealedConversation();
    await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)')
      .bind(crypto.randomUUID(), bob.id, alice.id).run();
    await register(bob); // a key change the blocked sender must not be able to observe

    for (const key of [bobKey, await publicKey()]) {
      const response = await send('PATCH', `/messages/${message.id}`, alice.token, {
        content: envelope('edited'), recipient_identity_key: key,
      });
      expect(response.status).toBe(403);
    }
    expect(await stored(bob, alice, message.id)).toMatchObject({ content: envelope('original') });
  }, 60000);

  it('keeps the rules of every edit: the sender only, and only for fifteen minutes', async () => {
    const { alice, bob, bobKey, message } = await sealedConversation();
    const edit = { content: envelope('edited'), recipient_identity_key: bobKey };

    expect((await send('PATCH', `/messages/${message.id}`, bob.token, edit)).status).toBe(404);

    await env.DB.prepare('UPDATE messages SET created_at = ? WHERE id = ?')
      .bind(new Date(Date.now() - 16 * 60 * 1000).toISOString(), message.id).run();
    expect((await send('PATCH', `/messages/${message.id}`, alice.token, edit)).status).toBe(400);
    expect(await stored(bob, alice, message.id)).toMatchObject({ content: envelope('original'), is_edited: false });
  }, 60000);

  it('does not let an envelope replace the text of a message that was never sealed', async () => {
    const { alice, bob, bobKey } = await sealedConversation();
    const sent = await send('POST', '/messages', alice.token, { receiver_id: bob.id, content: 'plain', type: 'text' });
    const plain = (await sent.json() as { message: Row }).message;

    const confused = await send('PATCH', `/messages/${plain.id}`, alice.token, {
      content: envelope('edited'), recipient_identity_key: bobKey,
    });
    expect(confused.status).toBe(400);
    expect(await stored(bob, alice, plain.id)).toMatchObject({ type: 'text', content: 'plain' });

    // An ordinary text edit is unchanged.
    const ordinary = await send('PATCH', `/messages/${plain.id}`, alice.token, { content: 'plain, corrected' });
    expect(ordinary.status).toBe(200);
    expect(await stored(bob, alice, plain.id)).toMatchObject({ type: 'text', content: 'plain, corrected', is_edited: true });
  }, 60000);
});

describe('a sealed message carries the id it was sealed for', () => {
  it('is refused without a client_message_id', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const bobKey = await register(bob);
    for (const clientId of [undefined, '', 7]) {
      const response = await send('POST', '/messages', alice.token, {
        receiver_id: bob.id, content: envelope('x'), type: 'e2ee', recipient_identity_key: bobKey,
        ...(clientId === undefined ? {} : { client_message_id: clientId }),
      });
      expect(response.status).toBe(400);
    }
  }, 60000);
});

describe('one-time prekeys belong to the registered installation', () => {
  it('reports which identity key the account has registered', async () => {
    const alice = await signup('alice');
    expect(await status(alice)).toEqual({ remaining: 0, identity_public_key: null });
    const key = await register(alice);
    expect(await status(alice)).toEqual({ remaining: 0, identity_public_key: key });
  }, 60000);

  it('accepts prekeys only from the installation whose identity key is registered', async () => {
    const alice = await signup('alice');
    const prekeys = [await publicKey(), await publicKey()];

    // Nothing registered yet: there is no installation these could belong to.
    const early = await send('POST', '/keys/prekeys', alice.token, { identity_public_key: await publicKey(), public_keys: prekeys });
    expect(early.status).toBe(409);

    const oldPhone = await register(alice);
    expect((await send('POST', '/keys/prekeys', alice.token, { identity_public_key: oldPhone, public_keys: prekeys })).status).toBe(201);
    expect((await status(alice)).remaining).toBe(2);

    // The account moves to a new phone; the old one is still running.
    const newPhone = await register(alice);
    expect((await status(alice)).remaining).toBe(0);
    const late = await send('POST', '/keys/prekeys', alice.token, { identity_public_key: oldPhone, public_keys: prekeys });
    expect(late.status).toBe(409);
    expect(await late.json()).toMatchObject({ code: 'identity_changed' });
    expect((await status(alice)).remaining).toBe(0);

    expect((await send('POST', '/keys/prekeys', alice.token, { identity_public_key: newPhone, public_keys: prekeys })).status).toBe(201);
    expect(await status(alice)).toEqual({ remaining: 2, identity_public_key: newPhone });
  }, 60000);

  it('never keeps prekeys of an installation that is replaced while it uploads', async () => {
    // Whichever request the database serves first, the outcome is the same:
    // the upload is refused, or it is stored and then discarded together
    // with the identity it belonged to.
    const alice = await signup('alice');
    for (let round = 0; round < 6; round++) {
      const oldPhone = await register(alice);
      const prekeys = [await publicKey(), await publicKey(), await publicKey()];
      const identity = await newKeyPair();
      const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
      const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekey));
      const [upload, takeover] = await Promise.all([
        send('POST', '/keys/prekeys', alice.token, { identity_public_key: oldPhone, public_keys: prekeys }),
        send('POST', '/keys/register', alice.token, {
          identity_public_key: await publicKey(identity), signed_prekey_public: b64(prekey), signed_prekey_signature: b64(signature),
        }),
      ]);
      expect(takeover.status).toBe(200);
      expect([201, 409]).toContain(upload.status);
      expect((await status(alice)).remaining).toBe(0);
    }
  }, 60000);

  it('requires a well-formed identity key with every upload', async () => {
    const alice = await signup('alice');
    await register(alice);
    const prekeys = [await publicKey()];
    for (const identity of [undefined, 'not-a-key', b64(new Uint8Array(90)), 7]) {
      const response = await send('POST', '/keys/prekeys', alice.token, {
        public_keys: prekeys, ...(identity === undefined ? {} : { identity_public_key: identity }),
      });
      expect(response.status).toBe(400);
    }
    expect((await status(alice)).remaining).toBe(0);
  }, 60000);
});

describe('signing out withdraws the keys of the phone that signs out', () => {
  it('removes its keys and prekeys, after which sealed messages for them are refused', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const bobKey = await register(bob);
    await send('POST', '/keys/prekeys', bob.token, { identity_public_key: bobKey, public_keys: [await publicKey()] });

    await signOut(bob, bobKey);

    // The access token is still good for a few minutes; the keys are gone at once.
    expect(await status(bob)).toEqual({ remaining: 0, identity_public_key: null });
    expect((await send('GET', `/keys/bundle/${bob.id}`, alice.token)).status).toBe(404);
    const sealed = await send('POST', '/messages', alice.token, {
      receiver_id: bob.id, content: envelope('x'), type: 'e2ee',
      client_message_id: crypto.randomUUID(), recipient_identity_key: bobKey,
    });
    expect(sealed.status).toBe(409);
    expect(await sessionIsAlive(bob)).toBe(false);
  }, 60000);

  it('leaves the keys of a newer phone, and of every other account, alone', async () => {
    const oldPhone = await signup('alice');
    const bob = await signup('bob');
    const oldKey = await register(oldPhone);
    const newPhone = await signInAgain(oldPhone);
    const newKey = await register(newPhone);
    const bobKey = await register(bob);
    await send('POST', '/keys/prekeys', newPhone.token, { identity_public_key: newKey, public_keys: [await publicKey()] });

    // The replaced phone signs out, naming the keys it held.
    await signOut(oldPhone, oldKey);
    expect(await status(newPhone)).toEqual({ remaining: 1, identity_public_key: newKey });

    // A session of one account cannot withdraw the keys of another, whichever key it names.
    const third = await signInAgain(newPhone);
    await signOut(third, bobKey);
    expect(await status(newPhone)).toEqual({ remaining: 1, identity_public_key: newKey });
    expect(await status(bob)).toEqual({ remaining: 0, identity_public_key: bobKey });
  }, 60000);

  it('withdraws nothing without a live session of the account', async () => {
    const alice = await signup('alice');
    const key = await register(alice);
    const registered = { remaining: 0, identity_public_key: key };

    // No token, a token that is not a refresh token, a forged one.
    for (const refresh of [undefined, '', 7, alice.token, 'not.a.token', `${alice.refresh}x`]) {
      const response = await send('POST', '/auth/logout', null, {
        identity_public_key: key, ...(refresh === undefined ? {} : { refresh_token: refresh }),
      });
      expect(response.status).toBe(200);
      expect(await status(alice)).toEqual(registered);
    }
    for (const body of ['null', '[]', '"text"', 'not json']) {
      const response = await SELF.fetch('https://example.test/auth/logout', { method: 'POST', headers: headers(null), body });
      expect(response.status).toBe(200);
    }
    expect(await status(alice)).toEqual(registered);

    // A session that has already ended: its token can no longer withdraw anything.
    const second = await signInAgain(alice);
    await signOut(second);
    await signOut(second, key);
    expect(await status(alice)).toEqual(registered);
  }, 60000);

  it('ends the session and keeps the keys when no usable identity key is named', async () => {
    // What an app from before end-to-end encryption sends, and what a damaged request looks like.
    const alice = await signup('alice');
    const key = await register(alice);
    for (const identity of [undefined, 'not-a-key', b64(new Uint8Array(90)), 7, null]) {
      const session = await signInAgain(alice);
      await signOut(session, identity);
      expect(await sessionIsAlive(session)).toBe(false);
      expect((await status(alice)).identity_public_key).toBe(key);
    }
  }, 60000);
});

describe('a sealed message may be as long as the longest text', () => {
  // 65,536 bytes of text become about 88,000 once sealed and encoded.
  const sealedLongest = JSON.stringify({ v: 1, dh: 'ZGg=', n: 0, pn: 0, ct: 'A'.repeat(90000) });
  const tooLong = JSON.stringify({ v: 1, dh: 'ZGg=', n: 0, pn: 0, ct: 'A'.repeat(131072) });

  it('accepts it when sending and when editing, and still bounds it', async () => {
    const { alice, bob, bobKey, message } = await sealedConversation();
    const sealed = (content: string) => ({
      receiver_id: bob.id, content, type: 'e2ee', client_message_id: crypto.randomUUID(), recipient_identity_key: bobKey,
    });

    const sent = await send('POST', '/messages', alice.token, sealed(sealedLongest));
    expect(sent.status).toBe(201);
    const id = (await sent.json() as { message: Row }).message.id;
    expect((await stored(bob, alice, id)).content).toBe(sealedLongest);
    expect((await send('POST', '/messages', alice.token, sealed(tooLong))).status).toBe(400);

    const edit = (content: string) => send('PATCH', `/messages/${message.id}`, alice.token, { content, recipient_identity_key: bobKey });
    expect((await edit(sealedLongest)).status).toBe(200);
    expect((await edit(tooLong)).status).toBe(400);
    expect((await stored(bob, alice, message.id)).content).toBe(sealedLongest);
  }, 60000);

  it('leaves the limit for readable text where it was', async () => {
    const { alice, bob } = await sealedConversation();
    const longest = 'x'.repeat(65536);
    const sent = await send('POST', '/messages', alice.token, { receiver_id: bob.id, content: longest, type: 'text' });
    expect(sent.status).toBe(201);
    const id = (await sent.json() as { message: Row }).message.id;
    expect((await send('POST', '/messages', alice.token, { receiver_id: bob.id, content: `${longest}x`, type: 'text' })).status).toBe(400);
    expect((await send('POST', '/messages', alice.token, { receiver_id: bob.id, content: `${longest}x`, type: 'location' })).status).toBe(400);

    expect((await send('PATCH', `/messages/${id}`, alice.token, { content: `${longest}x` })).status).toBe(400);
    expect((await send('PATCH', `/messages/${id}`, alice.token, { content: 'short' })).status).toBe(200);
    expect((await stored(bob, alice, id)).content).toBe('short');
  }, 60000);
});

describe('a server whose database has not been given the key tables', () => {
  // Must stay the last test of this file: it takes the tables away and puts them back.
  it('says that encryption is not set up, and everything else carries on', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const key = await register(alice);
    const migration = (env.TEST_MIGRATIONS as { name: string; queries: string[] }[]).find((m) => m.name.startsWith('0008'))!;

    await env.DB.batch([env.DB.prepare('DROP TABLE one_time_prekeys'), env.DB.prepare('DROP TABLE user_crypto_keys')]);
    try {
      const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
      const identity = await newKeyPair();
      const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekey));
      const calls = [
        send('GET', '/keys/prekeys', alice.token),
        send('POST', '/keys/prekeys', alice.token, { identity_public_key: key, public_keys: [await publicKey()] }),
        send('POST', '/keys/register', alice.token, {
          identity_public_key: await publicKey(identity), signed_prekey_public: b64(prekey), signed_prekey_signature: b64(signature),
        }),
        send('GET', `/keys/bundle/${bob.id}`, alice.token),
      ];
      for (const response of await Promise.all(calls)) {
        // Not a 500: the app must be able to tell "not set up" from "try again in a moment".
        expect(response.status).toBe(501);
        expect(await response.json()).toMatchObject({ code: 'e2ee_unavailable' });
      }
      // What needs no keys is untouched: refusals, ordinary messages, signing out.
      expect((await send('GET', '/keys/prekeys', null)).status).toBe(401);
      expect((await send('POST', '/keys/prekeys', alice.token, { public_keys: [] })).status).toBe(400);
      expect((await send('POST', '/messages', alice.token, { receiver_id: bob.id, content: 'still works', type: 'text' })).status).toBe(201);
      await signOut(alice, key);
      expect(await sessionIsAlive(alice)).toBe(false);
    } finally {
      for (const query of migration.queries) await env.DB.prepare(query).run();
    }
    // With the tables back, the same routes answer as usual.
    expect(await status(bob)).toEqual({ remaining: 0, identity_public_key: null });
  }, 60000);
});
