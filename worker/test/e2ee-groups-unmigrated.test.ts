import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

/**
 * The same rule as for sealed files: between a deployment and the migration
 * that goes with it, the server goes on doing what it did, and says plainly
 * what it cannot do yet. One test, in a file of its own, because it takes
 * the table of migration 0009 away again.
 */

const address = () => `2001:db8:${crypto.randomUUID().slice(0, 4)}::1`;

async function signup(name: string) {
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

describe('a server whose database has no table for group chains', () => {
  it('keeps readable group messages working and answers 501 for everything sealed', async () => {
    await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
    await env.DB.prepare('DROP TABLE group_sender_keys').run();
    const [alice, bob] = await Promise.all([signup('alice'), signup('bob')]);
    const created = await call('POST', '/groups', alice.token, { name: 'Group', memberIds: [bob.id] });
    expect(created.status).toBe(201);
    const group = ((await created.json()) as { group: { id: string } }).group.id;
    const chain = btoa(String.fromCharCode(...new Uint8Array(16)));

    // As before.
    expect((await call('POST', `/groups/${group}/messages`, alice.token, { type: 'text', content: 'hello' })).status).toBe(201);
    const history = await call('GET', `/groups/${group}/messages`, bob.token);
    expect(((await history.json()) as { messages: { content: string }[] }).messages.map((m) => m.content)).toEqual(['hello']);

    // Said plainly, so that an app does not keep trying — and never stored half.
    const sealed = [
      await call('GET', `/groups/${group}/roster`, alice.token),
      await call('GET', `/groups/${group}/roster?chain=${encodeURIComponent(chain)}`, alice.token),
      await call('POST', `/groups/${group}/keys`, alice.token, { chain_id: chain, keys: [{ recipient_id: bob.id, recipient_identity_key: btoa(String.fromCharCode(...new Uint8Array(91))), envelope: 'x' }] }),
      await call('POST', `/groups/${group}/keys/fetch`, bob.token, { wanted: [{ sender_id: alice.id, chain_id: chain }] }),
      await call('POST', `/groups/${group}/messages`, alice.token, { type: 'e2ee', content: 'sealed', client_message_id: crypto.randomUUID(), chain_id: chain }),
    ];
    for (const response of sealed) {
      expect(response.status).toBe(501);
      expect(((await response.json()) as { code: string }).code).toBe('e2ee_unavailable');
    }
    const after = await call('GET', `/groups/${group}/messages`, bob.token);
    expect(((await after.json()) as { messages: unknown[] }).messages).toHaveLength(1);
  });
});
