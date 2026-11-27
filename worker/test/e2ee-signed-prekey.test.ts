import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * A phone replaces its signed prekey every week. It asks for that with
 * `only_if_registered: true` on POST /keys/register, and the server then
 * writes nothing unless the identity key in the request is the one the
 * account has registered at that moment.
 *
 * Why the condition. The plain registration makes the sender's keys the
 * account's, whichever phone held it before — that is what a sign-in needs.
 * A phone that only replaces a prekey must never do that: it asked "am I
 * still this account's phone?" some time ago — a moment ago, or before it
 * went to sleep for the night — and the user may have signed in on another
 * phone since. Without the condition its request would take the account
 * back and sign the new phone out.
 *
 * The server still handles PUBLIC key material only.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

type User = { id: string; token: string };
type Keys = { identity_public_key: string; signed_prekey_public: string; signed_prekey_signature: string };
type Bundle = Keys & { one_time_prekey: { public_key: string } | null };

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

async function signup(name: string): Promise<User> {
  const response = await post('/auth/signup', null, {
    email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name,
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const bytes = (text: string) => Uint8Array.from(atob(text), (character) => character.charCodeAt(0));

async function newKeyPair() {
  return await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
}

async function publicKey(pair: CryptoKeyPair) {
  return b64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey)));
}

/** One installation of the app: its identity key, and as many signed prekeys as it is asked for. */
async function newPhone() {
  const pair = await newKeyPair();
  const identity = await publicKey(pair);
  return {
    identity,
    /** A new signed prekey, signed by this phone's identity key — a registration body. */
    async keys(): Promise<Keys> {
      const prekey = new Uint8Array(await crypto.subtle.exportKey('spki', (await newKeyPair()).publicKey));
      const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, pair.privateKey, prekey));
      return { identity_public_key: identity, signed_prekey_public: b64(prekey), signed_prekey_signature: b64(signature) };
    },
  };
}

type Phone = Awaited<ReturnType<typeof newPhone>>;

/** The plain registration: what a sign-in on [phone] sends. Returns what was registered. */
async function signIn(user: User, phone: Phone): Promise<Keys> {
  const keys = await phone.keys();
  expect((await post('/keys/register', user.token, keys)).status).toBe(200);
  return keys;
}

/** What a phone sends to replace its signed prekey. */
function replace(user: User, keys: Keys) {
  return post('/keys/register', user.token, { ...keys, only_if_registered: true });
}

async function uploadPrekeys(user: User, phone: Phone, count: number) {
  const keys: string[] = [];
  for (let i = 0; i < count; i++) keys.push(await publicKey(await newKeyPair()));
  const response = await post('/keys/prekeys', user.token, { identity_public_key: phone.identity, public_keys: keys });
  expect(response.status).toBe(201);
  return keys;
}

async function status(user: User) {
  return await (await get('/keys/prekeys', user.token)).json() as { remaining: number; identity_public_key: string | null };
}

/** What somebody who wants to write to [user] is handed. An account may ask for its own. */
async function bundle(user: User): Promise<Bundle> {
  const response = await get(`/keys/bundle/${user.id}`, user.token);
  expect(response.status).toBe(200);
  return await response.json() as Bundle;
}

/** Whether a sender would accept [served]: its signed prekey must carry the signature of its identity key. */
async function verifies(served: Bundle) {
  const identity = await crypto.subtle.importKey(
    'spki', bytes(served.identity_public_key), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'],
  );
  return crypto.subtle.verify(
    { name: 'ECDSA', hash: 'SHA-256' }, identity, bytes(served.signed_prekey_signature), bytes(served.signed_prekey_public),
  );
}

describe('a phone replacing its signed prekey', () => {
  it('is served the new one from then on, under the same identity, with its one-time prekeys untouched', async () => {
    const alice = await signup('alice');
    const phone = await newPhone();
    await signIn(alice, phone);
    const prekeys = await uploadPrekeys(alice, phone, 3);

    const next = await phone.keys();
    const replaced = await replace(alice, next);
    expect(replaced.status).toBe(200);
    expect(await replaced.json()).toEqual({ ok: true });

    expect(await status(alice)).toEqual({ remaining: 3, identity_public_key: phone.identity });
    const served = await bundle(alice);
    expect(served).toMatchObject({ ...next, one_time_prekey: { public_key: prekeys[0] } });
    expect(await verifies(served)).toBe(true);

    // A phone that never saw the answer asks again with the same key: nothing changes.
    expect((await replace(alice, next)).status).toBe(200);
    expect(await bundle(alice)).toMatchObject({ ...next, one_time_prekey: { public_key: prekeys[1] } });
    expect(await status(alice)).toEqual({ remaining: 1, identity_public_key: phone.identity });
  }, 60000);

  it('is refused once another phone has the account, and leaves that phone\'s keys as they are', async () => {
    const alice = await signup('alice');
    const oldPhone = await newPhone();
    await signIn(alice, oldPhone);

    // The user signs in on another phone. The old one has not found out yet.
    const newerPhone = await newPhone();
    const theirs = await signIn(alice, newerPhone);
    const prekeys = await uploadPrekeys(alice, newerPhone, 2);

    const refused = await replace(alice, await oldPhone.keys());
    expect(refused.status).toBe(409);
    expect(await refused.json()).toMatchObject({ code: 'identity_changed' });

    expect(await status(alice)).toEqual({ remaining: 2, identity_public_key: newerPhone.identity });
    expect(await bundle(alice)).toMatchObject({ ...theirs, one_time_prekey: { public_key: prekeys[0] } });
  }, 60000);

  it('is refused for an account that has no keys registered, and registers none', async () => {
    const alice = await signup('alice');
    const phone = await newPhone();

    const refused = await replace(alice, await phone.keys());
    expect(refused.status).toBe(409);
    expect(await refused.json()).toMatchObject({ code: 'identity_changed' });

    expect(await status(alice)).toEqual({ remaining: 0, identity_public_key: null });
    expect((await get(`/keys/bundle/${alice.id}`, alice.token)).status).toBe(404);
  }, 60000);

  it('never touches another account, not even one that registered the same identity key', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    const phone = await newPhone();
    const alices = await signIn(alice, phone);
    const bobs = await signIn(bob, phone); // the same key pair under a second account
    const other = await signup('carol');
    const others = await signIn(other, await newPhone());

    const next = await phone.keys();
    expect((await replace(alice, next)).status).toBe(200);

    expect(await bundle(alice)).toMatchObject(next);
    expect(await bundle(bob)).toMatchObject(bobs);
    expect(await bundle(other)).toMatchObject(others);
    expect(alices.signed_prekey_public).not.toBe(next.signed_prekey_public);
  }, 60000);

  it('is checked like every registration: signed in, well-formed keys, the signature of that identity key', async () => {
    const alice = await signup('alice');
    const phone = await newPhone();
    const first = await signIn(alice, phone);
    const next = await phone.keys();
    const stranger = await (await newPhone()).keys();

    expect((await post('/keys/register', null, { ...next, only_if_registered: true })).status).toBe(401);
    for (const body of [
      { ...next, signed_prekey_signature: stranger.signed_prekey_signature },
      { ...next, signed_prekey_public: stranger.signed_prekey_public },
      { ...next, signed_prekey_public: 'AAAA' },
      { ...next, signed_prekey_signature: b64(new Uint8Array(63)) },
      { ...next, identity_public_key: 'not a key' },
      { signed_prekey_public: next.signed_prekey_public, signed_prekey_signature: next.signed_prekey_signature },
    ]) {
      expect((await replace(alice, body as Keys)).status).toBe(400);
    }
    expect(await bundle(alice)).toMatchObject(first);
  }, 60000);

  it('must say true or false: anything else is refused, never taken for a plain registration', async () => {
    // The direction that matters: a request that meant "only if I am still the one"
    // must not be read as "make me the one".
    const alice = await signup('alice');
    const oldPhone = await newPhone();
    await signIn(alice, oldPhone);
    const newerPhone = await newPhone();
    const theirs = await signIn(alice, newerPhone);

    for (const flag of ['true', 'yes', 1, 0, null, {}, [true]]) {
      const response = await post('/keys/register', alice.token, { ...(await oldPhone.keys()), only_if_registered: flag });
      expect(response.status).toBe(400);
      expect(await bundle(alice)).toMatchObject(theirs);
    }

    // `false` says the same as saying nothing: the plain registration of a sign-in.
    const mine = await oldPhone.keys();
    expect((await post('/keys/register', alice.token, { ...mine, only_if_registered: false })).status).toBe(200);
    expect(await bundle(alice)).toMatchObject(mine);
  }, 60000);

  it('never undoes a sign-in on another phone that arrives at the same moment', async () => {
    // Whichever of the two requests the database serves first, the phone that
    // signed in holds the account afterwards, with the signed prekey it sent:
    // the replacement is applied and then overwritten, or it is refused.
    const alice = await signup('alice');
    for (let round = 0; round < 8; round++) {
      const oldPhone = await newPhone();
      await signIn(alice, oldPhone);
      const newerPhone = await newPhone();
      const theirs = await newerPhone.keys();
      const replacement = async () => replace(alice, await oldPhone.keys());
      const takeover = () => post('/keys/register', alice.token, theirs);

      // Sent in both orders: one of them lands second in every environment.
      const [first, second] = round % 2 === 0 ? [replacement, takeover] : [takeover, replacement];
      const answers = await Promise.all([first(), second()]);
      const [replaced, signedIn] = round % 2 === 0 ? answers : [answers[1], answers[0]];

      expect(signedIn.status).toBe(200);
      expect([200, 409]).toContain(replaced.status);
      const served = await bundle(alice);
      expect(served).toMatchObject(theirs);
      expect(await verifies(served)).toBe(true);
    }
  }, 60000);
});
