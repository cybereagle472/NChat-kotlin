import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * Sealed group messages (E2EE Batch 4, server half).
 *
 * A member seals what they write to a group once, with a chain of their own,
 * and hands that chain to every other member inside a sealed one-to-one
 * envelope. The server stores both — the message and, per member, the
 * envelope that carries the chain — and can open neither.
 *
 * What it can do, and these tests hold it to, is keep the two in step:
 *  - a sealed message is accepted only once every other member who has keys
 *    has been handed the chain it was sealed with;
 *  - it is refused when somebody who should no longer read still holds that
 *    chain: a member who left, or one who has registered other keys since —
 *    the sender then starts a new chain;
 *  - an envelope that carries a chain is handed to the member it was sealed
 *    for and to nobody else.
 * The roster tells a sender who is in the group, with which keys, and who
 * holds the chain already.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

const address = () => `2001:db8:${crypto.randomUUID().slice(0, 4)}::1`;

type Account = { id: string; token: string };

async function signup(name: string): Promise<Account> {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': address() },
    body: JSON.stringify({ email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

function call(method: string, path: string, token: string, body?: unknown) {
  return SELF.fetch(`https://example.test${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'CF-Connecting-IP': address() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

/** Registers key material for `user` and returns the identity key the server now holds for them. */
async function registerKeys(user: Account): Promise<string> {
  const identity = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const prekey = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const identityPub = b64(new Uint8Array(await crypto.subtle.exportKey('spki', identity.publicKey)));
  const prekeyBytes = new Uint8Array(await crypto.subtle.exportKey('spki', prekey.publicKey));
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, prekeyBytes));
  const response = await call('POST', '/keys/register', user.token, {
    identity_public_key: identityPub, signed_prekey_public: b64(prekeyBytes), signed_prekey_signature: b64(signature),
  });
  expect(response.status).toBe(200);
  return identityPub;
}

async function createGroup(owner: Account, members: Account[]): Promise<string> {
  const response = await call('POST', '/groups', owner.token, { name: 'Group', memberIds: members.map((m) => m.id) });
  expect(response.status).toBe(201);
  return ((await response.json()) as { group: { id: string } }).group.id;
}

const chainId = () => b64(crypto.getRandomValues(new Uint8Array(16)));

// Opaque to the server: only their bytes matter here.
const sealedMessage = (n: number) => JSON.stringify({ v: 1, c: 'AAAAAAAAAAAAAAAAAAAAAA==', n, ct: 'QWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=', sig: 'c2lnbmF0dXJl' });
const keyEnvelope = (tag: string) => JSON.stringify({ v: 1, dh: 'AOCsVJxqKzN2mH8vYtQwErTyUiOpAsDfGhJkLzXcVbNm', n: 0, pn: 0, ct: `a2V5LWZvci0${tag}` });

type RosterEntry = { user_id: string; identity_key: string | null; has_key: boolean };

async function roster(member: Account, group: string, chain?: string) {
  const response = await call('GET', `/groups/${group}/roster${chain ? `?chain=${encodeURIComponent(chain)}` : ''}`, member.token);
  expect(response.status).toBe(200);
  return response.json() as Promise<{ members: RosterEntry[]; stale: boolean }>;
}

function handOver(sender: Account, group: string, chain: string, keys: { to: Account; identity: string; envelope?: string }[]) {
  return call('POST', `/groups/${group}/keys`, sender.token, {
    chain_id: chain,
    keys: keys.map((k) => ({ recipient_id: k.to.id, recipient_identity_key: k.identity, envelope: k.envelope ?? keyEnvelope(k.to.id.slice(0, 6)) })),
  });
}

function post(sender: Account, group: string, chain: string, extra: Record<string, unknown> = {}) {
  return call('POST', `/groups/${group}/messages`, sender.token, {
    type: 'e2ee', content: sealedMessage(0), client_message_id: crypto.randomUUID(), chain_id: chain, ...extra,
  });
}

async function fetchKeys(member: Account, group: string, wanted: { sender: Account; chain: string }[]) {
  const response = await call('POST', `/groups/${group}/keys/fetch`, member.token, {
    wanted: wanted.map((w) => ({ sender_id: w.sender.id, chain_id: w.chain })),
  });
  expect(response.status).toBe(200);
  return ((await response.json()) as { keys: { sender_id: string; chain_id: string; envelope: string }[] }).keys;
}

/** Four people in a group; the first three have keys, the fourth uses an app without. */
async function scene() {
  const [alice, bob, carol, dave, outsider] = await Promise.all(['alice', 'bob', 'carol', 'dave', 'outsider'].map(signup));
  const [aliceKey, bobKey, carolKey] = [await registerKeys(alice), await registerKeys(bob), await registerKeys(carol)];
  const group = await createGroup(alice, [bob, carol, dave]);
  return { alice, bob, carol, dave, outsider, aliceKey, bobKey, carolKey, group };
}

describe('the roster of a group', () => {
  it('names every other member and the keys they have registered', async () => {
    const s = await scene();
    const seen = await roster(s.alice, s.group);
    expect(seen.stale).toBe(false);
    expect(seen.members.map((m) => m.user_id).sort()).toEqual([s.bob.id, s.carol.id, s.dave.id].sort());
    const byId = Object.fromEntries(seen.members.map((m) => [m.user_id, m]));
    expect(byId[s.bob.id]).toEqual({ user_id: s.bob.id, identity_key: s.bobKey, has_key: false });
    expect(byId[s.carol.id].identity_key).toBe(s.carolKey);
    // An app without keys: named, so the sender knows somebody cannot read sealed messages.
    expect(byId[s.dave.id]).toEqual({ user_id: s.dave.id, identity_key: null, has_key: false });

    expect((await call('GET', `/groups/${s.group}/roster`, s.outsider.token)).status).toBe(403);
    expect((await call('GET', `/groups/${crypto.randomUUID()}/roster`, s.alice.token)).status).toBe(403);
    expect((await call('GET', `/groups/${s.group}/roster?chain=not-a-chain`, s.alice.token)).status).toBe(400);
  });

  it('says who holds a chain, and when somebody holds it who should not', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }])).status).toBe(200);

    let seen = await roster(s.alice, s.group, chain);
    let byId = Object.fromEntries(seen.members.map((m) => [m.user_id, m]));
    expect(byId[s.bob.id].has_key).toBe(true);
    expect(byId[s.carol.id].has_key).toBe(false);
    expect(seen.stale).toBe(false);
    // Another chain, or another member's view, is another matter.
    expect((await roster(s.alice, s.group, chainId())).members.every((m) => !m.has_key)).toBe(true);
    expect((await roster(s.carol, s.group, chain)).members.every((m) => !m.has_key)).toBe(true);

    // Bob signs in on a new phone: the chain he was handed was sealed for keys he no longer has.
    const bobsNewKey = await registerKeys(s.bob);
    seen = await roster(s.alice, s.group, chain);
    byId = Object.fromEntries(seen.members.map((m) => [m.user_id, m]));
    expect(byId[s.bob.id]).toEqual({ user_id: s.bob.id, identity_key: bobsNewKey, has_key: false });
    expect(seen.stale).toBe(true);

    // Carol holds another chain and then leaves: that chain is held by somebody outside the group.
    const second = chainId();
    expect((await handOver(s.alice, s.group, second, [{ to: s.carol, identity: s.carolKey }])).status).toBe(200);
    expect((await roster(s.alice, s.group, second)).stale).toBe(false);
    expect((await call('DELETE', `/groups/${s.group}/members/${s.carol.id}`, s.alice.token)).status).toBe(200);
    expect((await roster(s.alice, s.group, second)).stale).toBe(true);
    // Back in the group with the same keys, she may as well go on holding it.
    expect((await call('POST', `/groups/${s.group}/members`, s.alice.token, { memberIds: [s.carol.id] })).status).toBe(200);
    seen = await roster(s.alice, s.group, second);
    expect(seen.stale).toBe(false);
    expect(seen.members.find((m) => m.user_id === s.carol.id)?.has_key).toBe(true);
  });
});

describe('handing a chain to the members of a group', () => {
  it('keeps each envelope for the member it was sealed for, and for nobody else', async () => {
    const s = await scene();
    const chain = chainId();
    const forBob = keyEnvelope('bob');
    const forCarol = keyEnvelope('carol');
    const stored = await handOver(s.alice, s.group, chain, [
      { to: s.bob, identity: s.bobKey, envelope: forBob },
      { to: s.carol, identity: s.carolKey, envelope: forCarol },
    ]);
    expect(stored.status).toBe(200);

    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toEqual([{ sender_id: s.alice.id, chain_id: chain, envelope: forBob }]);
    expect(await fetchKeys(s.carol, s.group, [{ sender: s.alice, chain }])).toEqual([{ sender_id: s.alice.id, chain_id: chain, envelope: forCarol }]);
    // Nothing for a member it was not sealed for, for another chain, or for another sender's chain.
    expect(await fetchKeys(s.dave, s.group, [{ sender: s.alice, chain }])).toEqual([]);
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain: chainId() }])).toEqual([]);
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.carol, chain }])).toEqual([]);
    expect(await fetchKeys(s.bob, s.group, [])).toEqual([]);
    // Several at once.
    const second = chainId();
    expect((await handOver(s.carol, s.group, second, [{ to: s.bob, identity: s.bobKey, envelope: keyEnvelope('from-carol') }])).status).toBe(200);
    const both = await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }, { sender: s.carol, chain: second }]);
    expect(both.map((k) => k.sender_id).sort()).toEqual([s.alice.id, s.carol.id].sort());

    expect((await call('POST', `/groups/${s.group}/keys/fetch`, s.outsider.token, { wanted: [{ sender_id: s.alice.id, chain_id: chain }] })).status).toBe(403);

    // An envelope is kept for a person, not for a key: an account that registers the very
    // key material another member published (it is public) is not handed that member's envelope.
    await env.DB.prepare('UPDATE user_crypto_keys SET identity_public_key = ?1 WHERE user_id = ?2').bind(s.bobKey, s.dave.id).run();
    await env.DB.prepare(`INSERT INTO user_crypto_keys (user_id, identity_public_key, signed_prekey_public, signed_prekey_signature)
      SELECT ?1, identity_public_key, signed_prekey_public, signed_prekey_signature FROM user_crypto_keys WHERE user_id = ?2
      ON CONFLICT (user_id) DO NOTHING`).bind(s.dave.id, s.bob.id).run();
    expect(await fetchKeys(s.dave, s.group, [{ sender: s.alice, chain }])).toEqual([]);
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toHaveLength(1);
  });

  it('keeps the first envelope when the same chain is handed to the same member again', async () => {
    const s = await scene();
    const chain = chainId();
    const first = keyEnvelope('first');
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey, envelope: first }])).status).toBe(200);
    // A retry after a lost answer, or a phone that forgot it had done this: accepted, and changes nothing.
    // The first one may have been opened already; a later one starts further on and would leave a hole.
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey, envelope: keyEnvelope('second') }])).status).toBe(200);
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toEqual([{ sender_id: s.alice.id, chain_id: chain, envelope: first }]);
  });

  it('refuses a chain for somebody who is not in the group or has other keys, and stores none of it', async () => {
    const s = await scene();
    const chain = chainId();
    const outsiderKey = await registerKeys(s.outsider);

    const refused = [
      await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.outsider, identity: outsiderKey }]),
      await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.bobKey }]),
      await handOver(s.alice, s.group, chain, [{ to: s.dave, identity: s.bobKey }]),
    ];
    for (const response of refused) {
      expect(response.status).toBe(409);
      expect(((await response.json()) as { code: string }).code).toBe('roster_changed');
    }
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toEqual([]);
    expect((await handOver(s.outsider, s.group, chain, [{ to: s.bob, identity: s.bobKey }])).status).toBe(403);

    const body = (keys: unknown, chain_id: unknown = chain) => call('POST', `/groups/${s.group}/keys`, s.alice.token, { chain_id, keys });
    const key = (overrides: Record<string, unknown> = {}) => ({ recipient_id: s.bob.id, recipient_identity_key: s.bobKey, envelope: keyEnvelope('x'), ...overrides });
    const malformed = [
      await body([key()], 'short'),
      await body([key()], b64(new Uint8Array(15))),
      await body([key()], null),
      await body([key()], 7),
      await body('not a list'),
      await body([]),
      await body([key({ envelope: '' })]),
      await body([key({ envelope: 'x'.repeat(8193) })]),
      await body([key({ envelope: 7 })]),
      await body([key({ recipient_identity_key: 'not a key' })]),
      await body([key({ recipient_id: '' })]),
      await body([key({ recipient_id: s.alice.id, recipient_identity_key: s.aliceKey })]),
      await body([key(), key()]),
      // More than one request may carry — each for somebody else, so that it is the number that is refused.
      await body(Array.from({ length: 101 }, () => key({ recipient_id: crypto.randomUUID() }))),
      await body([key(), 'not a key entry']),
    ];
    expect(malformed.map((r) => r.status)).toEqual(malformed.map(() => 400));
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toEqual([]);
  });

  it('does not hand out an envelope sealed for keys the member no longer has, or to a member who left', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.carolKey }])).status).toBe(200);
    await registerKeys(s.bob);
    expect(await fetchKeys(s.bob, s.group, [{ sender: s.alice, chain }])).toEqual([]);
    expect((await call('POST', `/groups/${s.group}/leave`, s.carol.token)).status).toBe(200);
    expect((await call('POST', `/groups/${s.group}/keys/fetch`, s.carol.token, { wanted: [{ sender_id: s.alice.id, chain_id: chain }] })).status).toBe(403);
  });
});

describe('a sealed message to a group', () => {
  it('is stored and served as it was sent, with nothing readable beside it', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.carolKey }])).status).toBe(200);
    const content = sealedMessage(0);
    const clientId = crypto.randomUUID();
    const sent = await call('POST', `/groups/${s.group}/messages`, s.alice.token, {
      type: 'e2ee', content, client_message_id: clientId, chain_id: chain,
      // What an app might send along by mistake. A sealed message carries these inside.
      reply_to: 'some-message', media_url: 'https://example.test/media/file/x', thumbnail_url: 'waveform', duration: '12',
    });
    expect(sent.status).toBe(201);
    const stored = ((await sent.json()) as { message: Record<string, unknown> }).message;
    expect(stored).toMatchObject({ group_id: s.group, sender_id: s.alice.id, content, type: 'e2ee', client_message_id: clientId });
    for (const field of ['reply_to', 'media_url', 'thumbnail_url', 'duration']) expect(stored[field]).toBeUndefined();

    for (const member of [s.alice, s.bob, s.carol, s.dave]) {
      const history = await call('GET', `/groups/${s.group}/messages`, member.token);
      const { messages } = await history.json() as { messages: Record<string, unknown>[] };
      expect(messages).toHaveLength(1);
      expect(messages[0]).toMatchObject({ id: stored.id, content, type: 'e2ee', client_message_id: clientId });
      for (const field of ['reply_to', 'media_url', 'thumbnail_url', 'duration']) expect(messages[0][field]).toBeUndefined();
    }
    const row = await env.DB.prepare('SELECT reply_to, media_url, thumbnail_url, duration FROM group_messages WHERE id = ?').bind(stored.id).first();
    expect(row).toEqual({ reply_to: null, media_url: null, thumbnail_url: null, duration: null });
  });

  it('must say which message and which chain it is, and may be as long as a sealed text gets', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.carolKey }])).status).toBe(200);
    const body = (overrides: Record<string, unknown>) => call('POST', `/groups/${s.group}/messages`, s.alice.token, {
      type: 'e2ee', content: sealedMessage(0), client_message_id: crypto.randomUUID(), chain_id: chain, ...overrides,
    });
    expect((await body({ client_message_id: undefined })).status).toBe(400);
    expect((await body({ client_message_id: '' })).status).toBe(400);
    expect((await body({ chain_id: undefined })).status).toBe(400);
    expect((await body({ chain_id: 'not-a-chain' })).status).toBe(400);
    expect((await body({ chain_id: b64(new Uint8Array(17)) })).status).toBe(400);
    expect((await body({ content: '' })).status).toBe(400);
    // Longer than a readable message may be, because sealing makes a text longer.
    expect((await body({ content: 'x'.repeat(100_000) })).status).toBe(201);
    expect((await body({ content: 'x'.repeat(131_073) })).status).toBe(400);
    // A readable message keeps its own limit.
    expect((await call('POST', `/groups/${s.group}/messages`, s.alice.token, { type: 'text', content: 'x'.repeat(65_537) })).status).toBe(400);
    expect((await call('POST', `/groups/${s.group}/messages`, s.alice.token, { type: 'text', content: 'still works' })).status).toBe(201);
    expect((await call('POST', `/groups/${s.group}/messages`, s.outsider.token, { type: 'e2ee', content: sealedMessage(0), client_message_id: 'x', chain_id: chain })).status).toBe(403);
  });

  it('is accepted only once every other member with keys has been handed its chain', async () => {
    const s = await scene();
    const chain = chainId();
    const refusedAll = await post(s.alice, s.group, chain);
    expect(refusedAll.status).toBe(409);
    expect(((await refusedAll.json()) as { code: string }).code).toBe('roster_changed');

    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, chain)).status).toBe(409);
    expect((await handOver(s.alice, s.group, chain, [{ to: s.carol, identity: s.carolKey }])).status).toBe(200);
    // Dave has no keys: nothing can be sealed for him, and he does not hold the others up.
    expect((await post(s.alice, s.group, chain)).status).toBe(201);
    // Nothing was stored for the refused attempts.
    const history = await call('GET', `/groups/${s.group}/messages`, s.bob.token);
    expect(((await history.json()) as { messages: unknown[] }).messages).toHaveLength(1);

    // Dave updates his app: from now on he has to be handed the chain too.
    const daveKey = await registerKeys(s.dave);
    expect((await post(s.alice, s.group, chain)).status).toBe(409);
    expect((await handOver(s.alice, s.group, chain, [{ to: s.dave, identity: daveKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, chain)).status).toBe(201);
    // Somebody new joins with keys: the same.
    const eve = await signup('eve');
    const eveKey = await registerKeys(eve);
    expect((await call('POST', `/groups/${s.group}/members`, s.alice.token, { memberIds: [eve.id] })).status).toBe(200);
    expect((await post(s.alice, s.group, chain)).status).toBe(409);
    expect((await handOver(s.alice, s.group, chain, [{ to: eve, identity: eveKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, chain)).status).toBe(201);
    // Each member's chain is their own: bob's needs handing over as well.
    expect((await post(s.bob, s.group, chain)).status).toBe(409);
  });

  it('is refused while somebody who left, or who has other keys now, still holds its chain', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.carolKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, chain)).status).toBe(201);

    // Carol is removed. Every remaining member is covered, and yet: she holds the chain.
    expect((await call('DELETE', `/groups/${s.group}/members/${s.carol.id}`, s.alice.token)).status).toBe(200);
    const stale = await post(s.alice, s.group, chain);
    expect(stale.status).toBe(409);
    expect(((await stale.json()) as { code: string }).code).toBe('roster_changed');
    // A new chain for who is left.
    const second = chainId();
    expect((await handOver(s.alice, s.group, second, [{ to: s.bob, identity: s.bobKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, second)).status).toBe(201);

    // Bob registers other keys (a new phone). The phone he had still holds the chain.
    const bobsNewKey = await registerKeys(s.bob);
    expect((await post(s.alice, s.group, second)).status).toBe(409);
    // Handing the same chain to his new keys does not make the old phone forget it.
    expect((await handOver(s.alice, s.group, second, [{ to: s.bob, identity: bobsNewKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, second)).status).toBe(409);
    const third = chainId();
    expect((await handOver(s.alice, s.group, third, [{ to: s.bob, identity: bobsNewKey }])).status).toBe(200);
    expect((await post(s.alice, s.group, third)).status).toBe(201);
  });

  it('acknowledges a message it already has, whatever has changed since', async () => {
    const s = await scene();
    const chain = chainId();
    expect((await handOver(s.alice, s.group, chain, [{ to: s.bob, identity: s.bobKey }, { to: s.carol, identity: s.carolKey }])).status).toBe(200);
    const clientId = crypto.randomUUID();
    const first = await post(s.alice, s.group, chain, { client_message_id: clientId });
    expect(first.status).toBe(201);
    const stored = ((await first.json()) as { message: { id: string } }).message;

    expect((await call('DELETE', `/groups/${s.group}/members/${s.carol.id}`, s.alice.token)).status).toBe(200);
    // The answer was lost and the phone asks again: the message is there, and says so.
    const again = await post(s.alice, s.group, chain, { client_message_id: clientId });
    expect(again.status).toBe(200);
    expect(((await again.json()) as { message: { id: string } }).message.id).toBe(stored.id);
    const history = await call('GET', `/groups/${s.group}/messages`, s.bob.token);
    expect(((await history.json()) as { messages: unknown[] }).messages).toHaveLength(1);
  });
});

describe('the keys of somebody in the same group', () => {
  it('are served to the other members, contacts or not, and to nobody else', async () => {
    const s = await scene();
    // Alice and bob are not contacts; the group is all they share.
    const bundle = await call('GET', `/keys/bundle/${s.bob.id}`, s.alice.token);
    expect(bundle.status).toBe(200);
    expect(((await bundle.json()) as { identity_public_key: string }).identity_public_key).toBe(s.bobKey);
    // A stranger gets the answer that says nothing, as before.
    expect((await call('GET', `/keys/bundle/${s.bob.id}`, s.outsider.token)).status).toBe(404);
    // Once bob has left, the group is no longer a reason.
    expect((await call('POST', `/groups/${s.group}/leave`, s.bob.token)).status).toBe(200);
    expect((await call('GET', `/keys/bundle/${s.bob.id}`, s.alice.token)).status).toBe(404);
    // Sharing a group does not make them contacts: no direct message.
    expect((await call('POST', '/messages', s.alice.token, { receiver_id: s.carol.id, content: 'hi' })).status).toBe(403);
  });
});
