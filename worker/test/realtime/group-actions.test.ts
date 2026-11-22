import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
async function fixture() {
  const unique = crypto.randomUUID();
  const signup = async (name: string) => {
    const response = await SELF.fetch('https://example.test/auth/signup', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: `${name}-${unique}@example.test`, password: 'password123' }),
    });
    expect(response.status).toBe(201);
    const body = await response.json() as { user: { id: string }; access_token: string };
    return { id: body.user.id, token: body.access_token };
  };
  const owner = await signup('owner');
  const outsider = await signup('outsider');
  const group = `g-${unique}`;
  const message = `msg-${unique}`;
  await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', owner.id).run();
  await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(`m-${unique}`, group, owner.id, 'admin').run();
  await env.DB.prepare('INSERT INTO group_messages (id, group_id, sender_id, content) VALUES (?, ?, ?, ?)').bind(message, group, owner.id, 'hello').run();
  return { owner, outsider, group, message };
}
function post(path: string, token: string, body: object = {}) {
  return SELF.fetch(`https://example.test${path}`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
}

describe('group actions require current membership', () => {
  it('an outsider cannot leave or destroy a one-member group', async () => {
    const { outsider, group, message } = await fixture();
    expect((await post(`/groups/${group}/leave`, outsider.token)).status).toBe(403);
    expect(await env.DB.prepare('SELECT id FROM groups WHERE id = ?').bind(group).first()).not.toBeNull();
    expect(await env.DB.prepare('SELECT id FROM group_messages WHERE id = ?').bind(message).first()).not.toBeNull();
  });
  it('an outsider cannot hide a group message for themselves', async () => {
    const { outsider, message } = await fixture();
    expect((await post(`/groups/messages/${message}/delete-for-me`, outsider.token)).status).toBe(403);
    expect((await env.DB.prepare('SELECT deleted_for FROM group_messages WHERE id = ?').bind(message).first<{deleted_for: string | null}>())?.deleted_for).toBeNull();
  });
  it('an outsider cannot react to a group message', async () => {
    const { outsider, message } = await fixture();
    expect((await post(`/groups/messages/${message}/reaction`, outsider.token, { emoji: '👍' })).status).toBe(403);
    expect((await env.DB.prepare('SELECT reactions FROM group_messages WHERE id = ?').bind(message).first<{reactions: string | null}>())?.reactions).toBeNull();
  });
  it('a removed author cannot delete their old message for everyone', async () => {
    const { owner, group, message } = await fixture();
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(group, owner.id).run();
    expect((await post(`/groups/messages/${message}/delete-for-everyone`, owner.token)).status).toBe(403);
    expect((await env.DB.prepare('SELECT is_deleted FROM group_messages WHERE id = ?').bind(message).first<{is_deleted: number}>())?.is_deleted).toBe(0);
  });
  it('a current member can react and delete their own message', async () => {
    const { owner, message } = await fixture();
    expect((await post(`/groups/messages/${message}/reaction`, owner.token, { emoji: '👍' })).status).toBe(200);
    expect((await post(`/groups/messages/${message}/delete-for-me`, owner.token)).status).toBe(200);
    expect((await post(`/groups/messages/${message}/delete-for-everyone`, owner.token)).status).toBe(200);
    const row = await env.DB.prepare('SELECT reactions, deleted_for, is_deleted FROM group_messages WHERE id = ?').bind(message).first<{reactions: string; deleted_for: string; is_deleted: number}>();
    expect(JSON.parse(row!.reactions)['👍']).toContain(owner.id);
    expect(JSON.parse(row!.deleted_for)).toContain(owner.id);
    expect(row!.is_deleted).toBe(1);
  });
  it('the last member can leave and the empty group is removed', async () => {
    const { owner, group } = await fixture();
    expect((await post(`/groups/${group}/leave`, owner.token)).status).toBe(200);
    expect(await env.DB.prepare('SELECT id FROM groups WHERE id = ?').bind(group).first()).toBeNull();
  });

});
