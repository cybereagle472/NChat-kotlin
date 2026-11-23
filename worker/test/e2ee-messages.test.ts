import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * Sealed DM messages (E2EE Batch 3, server half): the server is a blind
 * relay for `type: 'e2ee'` — it stores and returns the opaque envelope
 * unchanged, enforces the same social/size/idempotency rules as plaintext,
 * and rejects unknown message types outright (fail closed).
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

async function becomeFriends(a: { id: string }, b: { id: string }) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

function send(token: string, body: Record<string, unknown>) {
  return SELF.fetch('https://example.test/messages', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0, 4)}::1` },
    body: JSON.stringify(body),
  });
}

function history(token: string, otherId: string) {
  return SELF.fetch(`https://example.test/messages/with/${otherId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

const envelope = JSON.stringify({
  v: 1,
  dh: 'AOCsVJxqKzN2mH8vYtQwErTyUiOpAsDfGhJkLzXcVbNm',
  n: 0,
  pn: 0,
  otk: 17,
  ct: 'QWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkw',
});

describe('sealed (e2ee) direct messages', () => {
  it('stores, returns, and re-serves the opaque envelope byte-for-byte', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);

    const sent = await send(alice.token, {
      receiver_id: bob.id,
      content: envelope,
      type: 'e2ee',
      client_message_id: crypto.randomUUID(),
    });
    expect(sent.status).toBe(201);
    const message = (await sent.json() as { message: { type: string; content: string; id: string } }).message;
    expect(message.type).toBe('e2ee');
    expect(message.content).toBe(envelope);

    const read = await history(bob.token, alice.id);
    expect(read.status).toBe(200);
    const rows = (await read.json() as { messages: { type: string; content: string; id: string }[] }).messages;
    expect(rows).toHaveLength(1);
    expect(rows[0].type).toBe('e2ee');
    expect(rows[0].content).toBe(envelope);
    expect(rows[0].id).toBe(message.id);
  }, 30000);

  it('keeps idempotent retry semantics for sealed sends', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const clientId = crypto.randomUUID();

    const first = await send(alice.token, { receiver_id: bob.id, content: envelope, type: 'e2ee', client_message_id: clientId });
    const second = await send(alice.token, { receiver_id: bob.id, content: envelope, type: 'e2ee', client_message_id: clientId });
    expect(first.status).toBe(201);
    expect(second.status).toBe(200); // idempotent replay of the stored row
    const id1 = (await first.json() as { message: { id: string } }).message.id;
    const id2 = (await second.json() as { message: { id: string } }).message.id;
    expect(id2).toBe(id1);

    const rows = (await (await history(bob.token, alice.id)).json() as { messages: unknown[] }).messages;
    expect(rows).toHaveLength(1);
  }, 30000);

  it('refuses editing a sealed message (only plaintext text is editable)', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);
    const sent = await send(alice.token, { receiver_id: bob.id, content: envelope, type: 'e2ee', client_message_id: crypto.randomUUID() });
    const message = (await sent.json() as { message: { id: string } }).message;

    const edited = await SELF.fetch(`https://example.test/messages/${message.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${alice.token}` },
      body: JSON.stringify({ content: 'tampered' }),
    });
    expect(edited.status).toBe(400);
    const rows = (await (await history(bob.token, alice.id)).json() as { messages: { content: string }[] }).messages;
    expect(rows[0].content).toBe(envelope);
  }, 30000);
});

describe('message type hardening (fail closed)', () => {
  it('rejects unknown, malformed, or non-string message types on DMs', async () => {
    const alice = await signup('alice');
    const bob = await signup('bob');
    await becomeFriends(alice, bob);

    expect((await send(alice.token, { receiver_id: bob.id, content: 'x', type: 'E2EE' })).status).toBe(400);
    expect((await send(alice.token, { receiver_id: bob.id, content: 'x', type: 'weird type!' })).status).toBe(400);
    expect((await send(alice.token, { receiver_id: bob.id, content: 'x', type: 123 })).status).toBe(400);
    expect((await send(alice.token, { receiver_id: bob.id, content: 'x', type: 'text; DROP TABLE' })).status).toBe(400);
    // The known set still passes.
    expect((await send(alice.token, { receiver_id: bob.id, content: 'hi', type: 'text' })).status).toBe(201);
    expect((await send(alice.token, { receiver_id: bob.id, content: envelope, type: 'e2ee' })).status).toBe(201);
  }, 30000);

  it('rejects sealed group messages until group E2EE ships', async () => {
    const owner = await signup('owner');
    const member = await signup('member');
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'G', owner.id).run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, owner.id, 'admin').run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, member.id, 'member').run();

    const refused = await SELF.fetch(`https://example.test/groups/${group}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ content: envelope, type: 'e2ee' }),
    });
    expect(refused.status).toBe(400);
    expect((await SELF.fetch(`https://example.test/groups/${group}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ content: 'hello', type: 'text' }),
    })).status).toBe(201);
  }, 30000);
});
