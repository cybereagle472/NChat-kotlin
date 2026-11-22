import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
async function fixture() {
  const unique = crypto.randomUUID();
  const signup = async (name: string) => {
    const response = await SELF.fetch('https://example.test/auth/signup', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
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
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` }, body: JSON.stringify(body),
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
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
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
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
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
    await friend(stranger.id, other.id);
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
    const update = await SELF.fetch(`https://example.test/groups/${group}`, { method: 'PATCH', headers: { Authorization: `Bearer ${stranger.token}`, 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` }, body: JSON.stringify({ avatar_url: file.url }) });
    expect(update.status).toBe(403);
    const profile = await SELF.fetch('https://example.test/users/me', { method: 'PATCH', headers: { Authorization: `Bearer ${stranger.token}`, 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` }, body: JSON.stringify({ avatar_url: file.url }) });
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

function authGet(path: string, token: string) {
  return SELF.fetch(`https://example.test${path}`, { headers: { Authorization: `Bearer ${token}` } });
}
async function seedStatus(owner: string, privacy = 'contacts', expiry = new Date(Date.now() + 3600000).toISOString()) {
  const id = crypto.randomUUID();
  await env.DB.prepare("INSERT INTO statuses (id, user_id, content_type, privacy, expires_at) VALUES (?, ?, 'text', ?, ?)").bind(id, owner, privacy, expiry).run();
  return id;
}
async function friend(a: string, b: string) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), a, b).run();
}
async function statusFeed(token: string) {
  const res = await authGet('/status/feed', token);
  expect(res.status).toBe(200);
  return (await res.json() as { statuses: {id: string}[] }).statuses.map((v) => v.id);
}

describe('status privacy', () => {
  it('shows a friend only visible, unexpired statuses and respects account visibility', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount();
    await friend(owner.id, viewer.id);
    const visible = await seedStatus(owner.id);
    const hidden = await seedStatus(owner.id, 'nobody');
    const unknown = await seedStatus(owner.id, 'contacts_except');
    const expired = await seedStatus(owner.id, 'contacts', new Date(Date.now() - 3600000).toISOString());
    let feed = await statusFeed(viewer.token);
    expect(feed).toContain(visible);
    expect(feed).not.toContain(hidden);
    expect(feed).not.toContain(unknown);
    expect(feed).not.toContain(expired);
    await env.DB.prepare("UPDATE privacy_settings SET status_visibility = 'nobody' WHERE user_id = ?").bind(owner.id).run();
    feed = await statusFeed(viewer.token);
    expect(feed).not.toContain(visible);
  });
  it('blocks status viewing in both directions and for strangers or expired statuses', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount(), stranger = await mediaAccount();
    await friend(owner.id, viewer.id);
    const id = await seedStatus(owner.id);
    expect((await post(`/status/${id}/view`, stranger.token)).status).toBe(404);
    expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(200);
    expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(200);
    expect((await env.DB.prepare('SELECT COUNT(*) AS n FROM status_views WHERE status_id = ?').bind(id).first<{n:number}>())?.n).toBe(1);
    for (const [a, b] of [[owner.id, viewer.id], [viewer.id, owner.id]]) {
      await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), a, b).run();
      expect(await statusFeed(viewer.token)).not.toContain(id);
      expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(404);
      await env.DB.prepare('DELETE FROM blocked_users WHERE user_id = ? AND blocked_user_id = ?').bind(a, b).run();
    }
    await env.DB.prepare('UPDATE statuses SET expires_at = ? WHERE id = ?').bind(new Date(Date.now() - 3600000).toISOString(), id).run();
    expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(404);
  });
  it('does not push private or blocked statuses over realtime', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount();
    await friend(owner.id, viewer.id);
    const response = await SELF.fetch(`https://example.test/realtime/connect?${new URLSearchParams({room: `user:${viewer.id}`, token: viewer.token})}`, { headers: { Upgrade: 'websocket' } });
    expect(response.status).toBe(101);
    const socket = response.webSocket!;
    socket.accept();
    const received: string[] = [];
    let finish!: () => void;
    const marker = crypto.randomUUID();
    const done = new Promise<void>((resolve) => { finish = resolve; });
    socket.addEventListener('message', (event) => { received.push(String(event.data)); if (String(event.data) === marker) finish(); });
    expect((await post('/status', owner.token, { privacy: 'nobody', text_content: 'private' })).status).toBe(201);
    await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), owner.id, viewer.id).run();
    expect((await post('/status', owner.token, { privacy: 'contacts', text_content: 'blocked' })).status).toBe(201);
    const stub = env.REALTIME.get(env.REALTIME.idFromName(`user:${viewer.id}`));
    await stub.fetch('https://internal/broadcast', { method: 'POST', body: marker });
    await done;
    expect(received).toEqual([marker]);
    socket.close(1000, 'finished');
  });
  it('only gives the author the viewer list', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount();
    await friend(owner.id, viewer.id);
    const id = await seedStatus(owner.id);
    expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(200);
    expect((await authGet(`/status/${id}/views`, viewer.token)).status).toBe(404);
    const views = await authGet(`/status/${id}/views`, owner.token);
    expect(views.status).toBe(200);
    expect((await views.json() as {views: {viewer_id:string}[]}).views.map((v) => v.viewer_id)).toContain(viewer.id);
  });
});

async function groupCallFixture() {
  const owner = await mediaAccount(), member = await mediaAccount(), outsider = await mediaAccount();
  const group = crypto.randomUUID(), call = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', owner.id).run();
  for (const user of [owner, member]) {
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, user.id, user.id === owner.id ? 'admin' : 'member').run();
  }
  await env.DB.prepare("INSERT INTO group_calls (id, group_id, caller_id, call_type, status, created_at) VALUES (?, ?, ?, 'audio', 'active', ?)")
    .bind(call, group, owner.id, new Date().toISOString()).run();
  for (const user of [owner, member]) {
    await env.DB.prepare("INSERT INTO group_call_participants (call_id, user_id, status) VALUES (?, ?, 'joined')").bind(call, user.id).run();
  }
  return { owner, member, outsider, group, call };
}
async function inbox(user: {id:string;token:string}) {
  const response = await SELF.fetch(`https://example.test/realtime/connect?${new URLSearchParams({room: `user:${user.id}`, token: user.token})}`, { headers: { Upgrade: 'websocket' } });
  expect(response.status).toBe(101);
  const socket = response.webSocket!; socket.accept();
  const events: string[] = [];
  const waiters = new Map<string, () => void>();
  socket.addEventListener('message', (event) => { const text = String(event.data); events.push(text); waiters.get(text)?.(); });
  return {
    events, socket,
    async flush() {
      const marker = crypto.randomUUID();
      const done = new Promise<void>((resolve) => waiters.set(marker, resolve));
      await env.REALTIME.get(env.REALTIME.idFromName(`user:${user.id}`)).fetch('https://internal/broadcast', { method: 'POST', body: marker });
      await done; waiters.delete(marker); events.pop();
    },
  };
}

describe('call signalling integrity', () => {
  it('never lets clients override call event identity', async () => {
    const owner = await mediaAccount(), receiver = await mediaAccount();
    const id = crypto.randomUUID();
    await env.DB.prepare("INSERT INTO calls (id, caller_id, receiver_id, call_type, status) VALUES (?, ?, ?, 'audio', 'ringing')").bind(id, owner.id, receiver.id).run();
    const target = await inbox(receiver);
    const answer = await post(`/calls/${id}/signal`, owner.token, { type:'message:new', callId:'fake', call_id:'fake', groupCallId:'fake', group_call_id:'fake', from:receiver.id, from_id:receiver.id, senderId:receiver.id, sender_id:receiver.id, user_id:receiver.id, to:receiver.id, candidate:{candidate:'example'} });
    expect(answer.status).toBe(200);
    await target.flush();
    expect(target.events).toHaveLength(1);
    const event = JSON.parse(target.events[0]);
    expect(event).toMatchObject({ type:'call:signal', callId:id, from:owner.id, candidate:{candidate:'example'} });
    for (const key of ['call_id','groupCallId','group_call_id','from_id','senderId','sender_id','user_id','to']) expect(event).not.toHaveProperty(key);
    target.socket.close(1000, 'finished');
  });
  it('does not relay late signals after a call ends', async () => {
    const owner = await mediaAccount(), receiver = await mediaAccount();
    const id = crypto.randomUUID();
    await env.DB.prepare("INSERT INTO calls (id, caller_id, receiver_id, call_type, status, ended_at) VALUES (?, ?, ?, 'audio', 'ended', datetime('now'))").bind(id, owner.id, receiver.id).run();
    const target = await inbox(receiver);
    expect((await post(`/calls/${id}/signal`, owner.token, {candidate:'late'})).status).toBe(200);
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000, 'finished');
  });
  it('forbids outsiders from declining or leaving group calls', async () => {
    const f = await groupCallFixture();
    for (const action of ['decline', 'leave']) expect((await post(`/group-calls/${f.call}/${action}`, f.outsider.token)).status).toBe(403);
    expect((await env.DB.prepare('SELECT status FROM group_calls WHERE id = ?').bind(f.call).first<{status:string}>())?.status).toBe('active');
  });
  it('requires both parties to be current group members for group signals', async () => {
    const f = await groupCallFixture();
    expect((await post(`/group-calls/${f.call}/signal`, f.owner.token, {to:f.outsider.id, candidate:'x'})).status).toBe(403);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(f.group, f.member.id).run();
    expect((await post(`/group-calls/${f.call}/signal`, f.owner.token, {to:f.member.id, candidate:'x'})).status).toBe(403);
    expect((await post(`/group-calls/${f.call}/signal`, f.member.token, {to:f.owner.id, candidate:'x'})).status).toBe(403);
    expect((await post(`/group-calls/${f.call}/leave`, f.member.token)).status).toBe(200);
  });
  it('does not allow forged group call signal envelopes', async () => {
    const f = await groupCallFixture(), target = await inbox(f.member);
    expect((await post(`/group-calls/${f.call}/signal`, f.owner.token, {to:f.member.id, type:'group:deleted', call_id:'fake', callId:'fake', groupCallId:'fake', group_call_id:'fake', from:f.member.id, from_id:f.member.id, senderId:f.member.id, sender_id:f.member.id, user_id:f.member.id, offer_sdp:{type:'offer',sdp:'v=0'} })).status).toBe(200);
    await target.flush(); expect(target.events).toHaveLength(1);
    const event = JSON.parse(target.events[0]);
    expect(event).toMatchObject({type:'group-call:signal', call_id:f.call, from:f.owner.id, offer_sdp:{type:'offer',sdp:'v=0'}});
    for (const key of ['to','callId','groupCallId','group_call_id','from_id','senderId','sender_id','user_id']) expect(event).not.toHaveProperty(key);
    target.socket.close(1000, 'finished');
  });
});

describe('direct messages and friendship authorization', () => {
  it('refuses strangers and blocked accounts on messaging, typing, recording and read receipts', async () => {
    const owner = await mediaAccount(), visitor = await mediaAccount();
    const target = await inbox(owner);
    const attempts: [string, object][] = [
      ['/messages', {receiver_id:owner.id, content:'hello'}],
      ['/messages/typing', {receiver_id:owner.id, is_typing:true}],
      ['/messages/recording', {receiver_id:owner.id, is_recording:true}],
      ['/messages/mark-read', {sender_id:owner.id}],
    ];
    for (const [path, body] of attempts) expect((await post(path, visitor.token, body)).status, path).toBe(403);
    await friend(owner.id, visitor.id);
    await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), owner.id, visitor.id).run();
    for (const [path, body] of attempts) expect((await post(path, visitor.token, body)).status, path).toBe(403);
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000, 'finished');
  });
  it('allows friends to message while preventing outsiders from reacting', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(), outsider = await mediaAccount();
    await friend(owner.id, peer.id);
    const sent = await post('/messages', owner.token, {receiver_id:peer.id, content:'hello'});
    expect(sent.status).toBe(201);
    const id = (await sent.json() as {message:{id:string}}).message.id;
    expect((await post(`/messages/${id}/reaction`, outsider.token, {emoji:'👍'})).status).toBe(404);
    expect((await env.DB.prepare('SELECT reactions FROM messages WHERE id = ?').bind(id).first<{reactions:string|null}>())?.reactions).toBeNull();
    expect((await post(`/messages/${id}/reaction`, peer.token, {emoji:'👍'})).status).toBe(200);
  });
  it('does not send unfriend events to strangers', async () => {
    const owner = await mediaAccount(), stranger = await mediaAccount(), target = await inbox(owner);
    const result = await SELF.fetch(`https://example.test/friends/${owner.id}`, {method:'DELETE', headers:{Authorization:`Bearer ${stranger.token}`}});
    expect(result.status).toBe(200);
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000, 'finished');
  });
});

describe('media references are precise and respect avatar audiences', () => {
  it('does not grant access through lookalike historical references', async () => {
    const owner = await mediaAccount(), stranger = await mediaAccount(), partner = await mediaAccount();
    const file = await mediaUpload(owner.token);
    for (const address of [
      `${file.url}-other`,
      `https://evil.example/media/file/${file.key}`,
      `https://example.test/not-media/${file.key}`,
      `https://example.test/media/file/other?next=${encodeURIComponent(file.url)}`,
    ]) {
      await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, media_url) VALUES (?, ?, ?, ?)')
        .bind(crypto.randomUUID(), stranger.id, partner.id, address).run();
      expect((await mediaRead(file.key, stranger.token)).status, address).toBe(404);
    }
  });
  it('prevents encoded local URLs from bypassing attachment authorization', async () => {
    const owner = await mediaAccount(), stranger = await mediaAccount(), partner = await mediaAccount();
    await friend(stranger.id, partner.id);
    const file = await mediaUpload(owner.token);
    const encoded = `https://example.test/media/file/${file.key.replace(/./g, c => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))}`;
    expect((await post('/messages', stranger.token, {receiver_id:partner.id, media_url:encoded})).status).toBe(403);
    expect((await post('/status', stranger.token, {content_type:'image', content_url:encoded})).status).toBe(403);
    expect((await mediaRead(file.key, stranger.token)).status).toBe(404);
  });
  it('checks profile photo settings and group membership for avatar files', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(), stranger = await mediaAccount();
    await friend(owner.id, peer.id);
    const avatar = await mediaUpload(owner.token);
    await env.DB.prepare('UPDATE users SET avatar_url = ? WHERE id = ?').bind(avatar.url, owner.id).run();
    await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility = 'contacts' WHERE user_id = ?").bind(owner.id).run();
    expect((await mediaRead(avatar.key, peer.token)).status).toBe(200);
    expect((await mediaRead(avatar.key, stranger.token)).status).toBe(404);
    await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility = 'nobody' WHERE user_id = ?").bind(owner.id).run();
    expect((await mediaRead(avatar.key, peer.token)).status).toBe(404);
    const groupAvatar = await mediaUpload(owner.token);
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id, name, created_by, avatar_url) VALUES (?, ?, ?, ?)').bind(group, 'Test', owner.id, groupAvatar.url).run();
    for (const user of [owner, peer]) await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, user.id, 'member').run();
    expect((await mediaRead(groupAvatar.key, peer.token)).status).toBe(200);
    expect((await mediaRead(groupAvatar.key, stranger.token)).status).toBe(404);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(group, peer.id).run();
    expect((await mediaRead(groupAvatar.key, peer.token)).status).toBe(404);
  });
});

describe('credential abuse limits', () => {
  const authRequest = (path: string, body: object, ip: string, forwarded = '1.1.1.1') => SELF.fetch(`https://example.test${path}`, {
    method: 'POST', headers: {'Content-Type':'application/json', 'CF-Connecting-IP':ip, 'X-Forwarded-For':forwarded}, body:JSON.stringify(body),
  });
  it('limits guesses per account across client addresses and spoofed forwarding headers', async () => {
    const email = `${crypto.randomUUID()}@example.test`;
    const replies = await Promise.all(Array.from({length: 15}, (_, i) => authRequest('/auth/login', {email, password:'wrongpass'}, `2001:db8::${i+1}`, `198.51.100.${i+1}`)));
    expect(replies.filter(r => r.status === 429).length).toBeGreaterThanOrEqual(5);
    expect(replies.every(r => r.status === 401 || r.status === 429)).toBe(true);
  });
  it('limits signups per connecting address even when emails and forwarded headers vary', async () => {
    const ip = `2001:db8:${Math.floor(Math.random() * 65535).toString(16)}::1`;
    const statuses: number[] = [];
    for (let i = 0; i < 12; i++) statuses.push((await authRequest('/auth/signup', {email:`${crypto.randomUUID()}@example.test`,password:'password123'}, ip, `203.0.113.${i+1}`)).status);
    expect(statuses.slice(0, 10).every(s => s === 201)).toBe(true);
    expect(statuses.slice(10)).toEqual([429, 429]);
  });
  it('fails closed if the Cloudflare client address is absent', async () => {
    const result = await SELF.fetch('https://example.test/auth/login', {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'nobody@example.test',password:'bad'})});
    expect(result.status).toBe(503);
  });
});

describe('backend entry-point hardening', () => {
  it('uses the stored admin role, not possession of an unverified email', async () => {
    const ip = `2001:db8:${crypto.randomUUID().slice(0,4)}::1`;
    const signup = await SELF.fetch('https://example.test/auth/signup', {method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP':ip},body:JSON.stringify({email:'kazmiinasir6@gmail.com',password:'password123'})});
    expect(signup.status).toBe(201);
    const person = await signup.json() as {user:{id:string},access_token:string};
    expect((await authGet('/admin/stats', person.access_token)).status).toBe(403);
    await env.DB.prepare('UPDATE users SET is_admin = 1 WHERE id = ?').bind(person.user.id).run();
    expect((await authGet('/admin/stats', person.access_token)).status).toBe(200);
    const other = await mediaAccount();
    expect((await authGet('/admin/stats', other.token)).status).toBe(403);
  });
  it('does not grant arbitrary web origins cross-origin access', async () => {
    const response = await SELF.fetch('https://example.test/health', {headers:{Origin:'https://attacker.example'}});
    expect(response.headers.get('access-control-allow-origin')).toBeNull();
    const preflight = await SELF.fetch('https://example.test/auth/login', {method:'OPTIONS',headers:{Origin:'https://attacker.example','Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'authorization,content-type'}});
    expect(preflight.headers.get('access-control-allow-origin')).toBeNull();
  });
  it('does not offer an unauthenticated account existence lookup', async () => {
    const existing = await mediaAccount();
    const email = (await env.DB.prepare('SELECT email FROM users WHERE id = ?').bind(existing.id).first<{email:string}>())!.email;
    const first = await SELF.fetch(`https://example.test/auth/check-email?email=${encodeURIComponent(email)}`);
    const second = await SELF.fetch(`https://example.test/auth/check-email?email=unknown-${crypto.randomUUID()}@example.test`);
    expect(first.status).toBe(404);
    expect(second.status).toBe(404);
  });
});
