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

async function upload() {
  const signup = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `uploader-${crypto.randomUUID()}@example.test`, password: 'password123' }),
  });
  expect(signup.status).toBe(201);
  const session = await signup.json() as { access_token: string };
  const form = new FormData();
  form.set('file', new File(['0123456789'], 'sample.txt', { type: 'text/plain' }));
  const response = await SELF.fetch('https://example.test/media/upload', {
    method: 'POST', headers: { Authorization: `Bearer ${session.access_token}` }, body: form,
  });
  expect(response.status).toBe(201);
  const file = await response.json() as { key: string };
  return { token: session.access_token, path: `/media/file/${file.key}` };
}

describe('media requires sign-in', () => {
  it('refuses unauthenticated and invalid-token reads even for real files', async () => {
    const file = await upload();
    const read = (token?: string) => SELF.fetch(`https://example.test${file.path}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    expect((await read()).status).toBe(401);
    expect((await read('invalid')).status).toBe(401);
  });
  it('allows the uploader to read and seek without shared caching', async () => {
    const file = await upload();
    const headers = { Authorization: `Bearer ${file.token}` };
    const response = await SELF.fetch(`https://example.test${file.path}`, { headers });
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('0123456789');
    expect(response.headers.get('cache-control')).toContain('no-store');
    const range = await SELF.fetch(`https://example.test${file.path}`, { headers: { ...headers, Range: 'bytes=2-4' } });
    expect(range.status).toBe(206);
    expect(range.headers.get('content-range')).toBe('bytes 2-4/10');
    expect(range.headers.get('cache-control')).toContain('no-store');
    expect(await range.text()).toBe('234');
  });
});

async function mediaAccount() {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `${crypto.randomUUID()}@example.test`, password: 'password123' }),
  });
  expect(response.status).toBe(201);
  const result = await response.json() as { user: { id: string }; access_token: string };
  return { id: result.user.id, token: result.access_token };
}
async function mediaUpload(token: string) {
  const form = new FormData();
  form.set('file', new File(['private image'], 'private.jpg', { type: 'image/jpeg' }));
  const response = await SELF.fetch('https://example.test/media/upload', { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  expect(response.status).toBe(201);
  return response.json() as Promise<{key: string; url: string}>;
}
function mediaRead(key: string, token: string) {
  return SELF.fetch(`https://example.test/media/file/${key}`, { headers: { Authorization: `Bearer ${token}` } });
}

describe('media access follows its audience', () => {
  it('allows uploader and chat recipient, but not an unrelated signed-in user', async () => {
    const owner = await mediaAccount(), recipient = await mediaAccount(), stranger = await mediaAccount();
    const file = await mediaUpload(owner.token);
    expect((await mediaRead(file.key, owner.token)).status).toBe(200);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, media_url) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), owner.id, recipient.id, file.url).run();
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
  });
  it('supports forwarding to a group without granting access to outsiders or removed members', async () => {
    const owner = await mediaAccount(), receiver = await mediaAccount(), member = await mediaAccount(), stranger = await mediaAccount();
    const file = await mediaUpload(owner.token);
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, media_url) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), owner.id, receiver.id, file.url).run();
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', receiver.id).run();
    for (const user of [receiver, member]) {
      await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
        .bind(crypto.randomUUID(), group, user.id, user.id === receiver.id ? 'admin' : 'member').run();
    }
    expect((await post(`/groups/${group}/messages`, receiver.token, { type: 'image', media_url: file.url })).status).toBe(201);
    expect((await mediaRead(file.key, member.token)).status).toBe(200);
    expect((await mediaRead(file.key, receiver.token)).status).toBe(200);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(group, member.id).run();
    expect((await mediaRead(file.key, member.token)).status).toBe(404);
  });
  it('does not let a stranger attach someone else’s private file to gain access', async () => {
    const owner = await mediaAccount(), stranger = await mediaAccount(), other = await mediaAccount();
    const file = await mediaUpload(owner.token);
    expect((await post('/messages', stranger.token, { receiver_id: other.id, type: 'image', media_url: file.url })).status).toBe(403);
    expect((await post('/messages', stranger.token, { receiver_id: other.id, thumbnail_url: file.url })).status).toBe(403);
    expect((await post('/status', stranger.token, { content_type: 'image', content_url: file.url })).status).toBe(403);
    expect((await post('/status', stranger.token, { content_type: 'image', thumbnail_url: file.url })).status).toBe(403);
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', stranger.id).run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, stranger.id, 'admin').run();
    expect((await post(`/groups/${group}/messages`, stranger.token, { media_url: file.url })).status).toBe(403);
    expect((await post(`/groups/${group}/messages`, stranger.token, { thumbnail_url: file.url })).status).toBe(403);
    expect((await post('/groups', stranger.token, { name: 'Test', avatar_url: file.url })).status).toBe(403);
    const update = await SELF.fetch(`https://example.test/groups/${group}`, { method: 'PATCH', headers: { Authorization: `Bearer ${stranger.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ avatar_url: file.url }) });
    expect(update.status).toBe(403);
    const profile = await SELF.fetch('https://example.test/users/me', { method: 'PATCH', headers: { Authorization: `Bearer ${stranger.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ avatar_url: file.url }) });
    expect(profile.status).toBe(403);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
  });
  it('honors status privacy, expiry and blocks for status media', async () => {
    const owner = await mediaAccount(), friend = await mediaAccount(), stranger = await mediaAccount();
    const file = await mediaUpload(owner.token);
    await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), owner.id, friend.id).run();
    const status = crypto.randomUUID();
    await env.DB.prepare("INSERT INTO statuses (id, user_id, content_type, content_url, privacy, expires_at) VALUES (?, ?, 'image', ?, 'contacts', ?)")
      .bind(status, owner.id, file.url, new Date(Date.now() + 3600000).toISOString()).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(200);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
    await env.DB.prepare("UPDATE statuses SET privacy = 'nobody' WHERE id = ?").bind(status).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(404);
    await env.DB.prepare("UPDATE statuses SET privacy = 'mystery' WHERE id = ?").bind(status).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(404);
    await env.DB.prepare("UPDATE statuses SET privacy = 'contacts' WHERE id = ?").bind(status).run();
    await env.DB.prepare("UPDATE privacy_settings SET status_visibility = 'nobody' WHERE user_id = ?").bind(owner.id).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(404);
    await env.DB.prepare("UPDATE privacy_settings SET status_visibility = 'contacts' WHERE user_id = ?").bind(owner.id).run();
    await env.DB.prepare("UPDATE statuses SET expires_at = ? WHERE id = ?").bind(new Date(Date.now() - 3600000).toISOString(), status).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(404);
    await env.DB.prepare("UPDATE statuses SET expires_at = ? WHERE id = ?").bind(new Date(Date.now() + 3600000).toISOString(), status).run();
    await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), owner.id, friend.id).run();
    expect((await mediaRead(file.key, friend.token)).status).toBe(404);
  });
});
