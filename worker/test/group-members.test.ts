import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

async function signup(name: string) {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
    body: JSON.stringify({ email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}
async function fixture() {
  const owner = await signup('owner');
  const member = await signup('member');
  const outsider = await signup('outsider');
  const group = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', owner.id).run();
  await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, owner.id, 'admin').run();
  await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, member.id, 'member').run();
  return { owner, member, outsider, group };
}
function get(path: string, token: string) {
  return SELF.fetch(`https://example.test${path}`, { headers: { Authorization: `Bearer ${token}` } });
}

describe('group member privacy', () => {
  it('refuses member lists to outsiders and removed members', async () => {
    const { member, outsider, group } = await fixture();
    expect((await get(`/groups/${group}/members`, outsider.token)).status).toBe(403);
    expect((await get(`/groups/${group}/members`, member.token)).status).toBe(200);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(group, member.id).run();
    expect((await get(`/groups/${group}/members`, member.token)).status).toBe(403);
  });
  it('does not expose member email addresses in group projections', async () => {
    const { owner, member, group } = await fixture();
    for (const [path, root] of [[`/groups/${group}`, 'group'], ['/groups', 'groups'], [`/groups/${group}/members`, 'members']] as const) {
      const response = await get(path, owner.token);
      expect(response.status).toBe(200);
      const body = await response.json() as Record<string, unknown>;
      const text = JSON.stringify(body[root]);
      expect(text).toContain(member.id);
      expect(text).not.toContain('@example.test');
      expect(text).not.toContain('"email"');
    }
  });
});
