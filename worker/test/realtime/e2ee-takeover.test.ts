import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * One phone per account: when a phone signs in and registers its keys in
 * place of another phone's, the account's connected phones are told at once
 * over realtime. The phone that was replaced then checks with the server and
 * signs out, instead of going on for hours showing messages it cannot open.
 *
 * The notice says only that something changed. It carries no key material,
 * and it goes to that account alone.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

type User = { id: string; token: string };

function headers(token: string | null) {
  return {
    'Content-Type': 'application/json',
    // Never the ::/64 that the signup-limit test of this suite exhausts.
    'CF-Connecting-IP': `2001:db8:1:${crypto.randomUUID().slice(0, 4)}::1`,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function post(path: string, token: string | null, body: unknown) {
  return SELF.fetch(`https://example.test${path}`, { method: 'POST', headers: headers(token), body: JSON.stringify(body) });
}

async function signup(name: string): Promise<User> {
  const response = await post('/auth/signup', null, { email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123' });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

async function newKeyPair() {
  return await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
}

async function publicKey(pair: CryptoKeyPair) {
  return b64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey)));
}

/** Registers keys for [user] under [identity] (a new phone when omitted); returns the identity. */
async function register(user: User, identity?: CryptoKeyPair): Promise<CryptoKeyPair> {
  const id = identity ?? await newKeyPair();
  const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, id.privateKey, prekey));
  const response = await post('/keys/register', user.token, {
    identity_public_key: await publicKey(id), signed_prekey_public: b64(prekey), signed_prekey_signature: b64(signature),
  });
  expect(response.status).toBe(200);
  return id;
}

/** A phone of [user] connected to realtime: everything it is sent, in order. */
async function inbox(user: User) {
  const response = await SELF.fetch(
    `https://example.test/realtime/connect?${new URLSearchParams({ room: `user:${user.id}`, token: user.token })}`,
    { headers: { Upgrade: 'websocket' } },
  );
  expect(response.status).toBe(101);
  const socket = response.webSocket!;
  socket.accept();
  const events: string[] = [];
  const waiters = new Map<string, () => void>();
  socket.addEventListener('message', (event) => {
    const text = String(event.data);
    events.push(text);
    waiters.get(text)?.();
  });
  return {
    events,
    /** Returns once everything sent before this call has arrived. */
    async flush() {
      const marker = crypto.randomUUID();
      const done = new Promise<void>((resolve) => waiters.set(marker, resolve));
      await env.REALTIME.get(env.REALTIME.idFromName(`user:${user.id}`)).fetch('https://internal/broadcast', { method: 'POST', body: marker });
      await done;
      waiters.delete(marker);
      events.pop();
    },
  };
}

describe('an account moving to another phone', () => {
  it('is announced to the account\'s connected phones, and to nobody else', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await register(alice);
    await register(bob);
    const oldPhone = await inbox(alice);
    const bobsPhone = await inbox(bob);

    await register(alice); // a different identity key: another phone signed in

    await oldPhone.flush();
    await bobsPhone.flush();
    // Only that something changed: the phone asks the server what, and no key travels in the notice.
    expect(oldPhone.events.map((event) => JSON.parse(event))).toEqual([{ type: 'keys:changed' }]);
    expect(bobsPhone.events).toEqual([]);
  }, 60000);

  it('is not announced when nothing moved', async () => {
    const alice = await signup('alice');
    const phone = await inbox(alice);

    // The first keys an account ever registers replace nobody's.
    const identity = await register(alice);
    // The same phone again: a new signed prekey, the same identity, more one-time prekeys.
    await register(alice, identity);
    const upload = await post('/keys/prekeys', alice.token, {
      identity_public_key: await publicKey(identity), public_keys: [await publicKey(await newKeyPair())],
    });
    expect(upload.status).toBe(201);

    await phone.flush();
    expect(phone.events).toEqual([]);
  }, 60000);

  it('is not announced when a phone replaces its signed prekey', async () => {
    const alice = await signup('alice');
    const identity = await register(alice);
    const phone = await inbox(alice);

    // What the app sends once a week: a new signed prekey, to be stored only while this
    // identity key is the account's.
    const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
    const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekey));
    const replaced = await post('/keys/register', alice.token, {
      identity_public_key: await publicKey(identity),
      signed_prekey_public: b64(prekey),
      signed_prekey_signature: b64(signature),
      only_if_registered: true,
    });
    expect(replaced.status).toBe(200);

    // And the same from a phone that has been replaced since: refused, and nobody is told anything.
    await register(alice); // the user signs in on another phone — that is announced, and is not what is looked at here
    await phone.flush();
    phone.events.length = 0;
    const refused = await post('/keys/register', alice.token, {
      identity_public_key: await publicKey(identity),
      signed_prekey_public: b64(prekey),
      signed_prekey_signature: b64(signature),
      only_if_registered: true,
    });
    expect(refused.status).toBe(409);

    await phone.flush();
    expect(phone.events).toEqual([]);
  }, 60000);

  it('is announced again when the account moves back', async () => {
    const alice = await signup('alice');
    const first = await register(alice);
    await register(alice);
    const phone = await inbox(alice);

    await register(alice, first); // signing in on the first phone again
    await phone.flush();
    expect(phone.events.map((event) => JSON.parse(event))).toEqual([{ type: 'keys:changed' }]);
  }, 60000);
});
