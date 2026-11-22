import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';
import { consumeLimit } from '../../src/lib/rate-limit';
import app from '../../src/index';
import type { Env } from '../../src/types';

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
    // A newly authorized avatar write carries provenance. Old group avatars
    // have no reliable author and therefore fail closed.
    const created = await post('/groups', owner.token, {name:'Test', avatar_url:groupAvatar.url, memberIds:[peer.id]});
    expect(created.status).toBe(201);
    const group = (await created.json() as {group:{id:string}}).group.id;
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

describe('group removal safeguards', () => {
  it('does not allow the last admin to bypass leave rules by removing themselves', async () => {
    const { owner, outsider, group } = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, outsider.id, 'member').run();
    const response = await SELF.fetch(`https://example.test/groups/${group}/members/${owner.id}`, {method:'DELETE',headers:{Authorization:`Bearer ${owner.token}`}});
    expect(response.status).toBe(400);
    expect(await env.DB.prepare('SELECT id FROM group_members WHERE group_id = ? AND user_id = ?').bind(group, owner.id).first()).not.toBeNull();
  });
  it('does not announce removal to someone who was not a member', async () => {
    const { owner, outsider, group } = await fixture();
    const listener = await inbox(outsider);
    const response = await SELF.fetch(`https://example.test/groups/${group}/members/${outsider.id}`, {method:'DELETE',headers:{Authorization:`Bearer ${owner.token}`}});
    expect(response.status).toBe(404);
    await listener.flush(); expect(listener.events).toHaveLength(0);
    listener.socket.close(1000, 'finished');
  });
});

describe('profile-photo privacy in group payloads', () => {
  it('redacts a hidden avatar in group responses and member events per viewer', async () => {
    const { owner, outsider, group } = await fixture();
    const avatar = `https://images.example/${crypto.randomUUID()}`;
    await env.DB.prepare('UPDATE users SET avatar_url = ? WHERE id = ?').bind(avatar, owner.id).run();
    await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility = 'nobody' WHERE user_id = ?").bind(owner.id).run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, outsider.id, 'member').run();
    for (const path of [`/groups/${group}`, `/groups/${group}/members`, '/groups']) {
      const viewer = await authGet(path, outsider.token);
      expect(viewer.status).toBe(200);
      expect(await viewer.text(), path).not.toContain(avatar);
    }
    const own = await authGet(`/groups/${group}`, owner.token);
    expect(await own.text()).toContain(avatar);
    const target = await inbox(outsider);
    const update = await SELF.fetch(`https://example.test/groups/${group}`, {method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({name:'Renamed'})});
    expect(update.status).toBe(200);
    await target.flush();
    expect(JSON.stringify(target.events)).not.toContain(avatar);
    target.socket.close(1000, 'finished');
  });
});

describe('profile-photo privacy in status payloads', () => {
  it('redacts hidden photos in feeds, status events and viewer lists', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount();
    await friend(owner.id, viewer.id);
    const ownerAvatar = `https://images.example/owner-${crypto.randomUUID()}`;
    const viewerAvatar = `https://images.example/viewer-${crypto.randomUUID()}`;
    for (const [account, avatar] of [[owner, ownerAvatar], [viewer, viewerAvatar]] as const) {
      await env.DB.prepare('UPDATE users SET avatar_url = ? WHERE id = ?').bind(avatar, account.id).run();
      await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility = 'nobody' WHERE user_id = ?").bind(account.id).run();
    }
    const ownerEvents = await inbox(owner), viewerEvents = await inbox(viewer);
    const created = await post('/status', owner.token, {content_type:'text', text_content:'hello', privacy:'contacts'});
    expect(created.status).toBe(201);
    const id = (await created.json() as {status:{id:string}}).status.id;
    await viewerEvents.flush(); expect(JSON.stringify(viewerEvents.events)).not.toContain(ownerAvatar);
    const feed = await authGet('/status/feed', viewer.token);
    expect(feed.status).toBe(200); expect(await feed.text()).not.toContain(ownerAvatar);
    expect((await post(`/status/${id}/view`, viewer.token)).status).toBe(200);
    await ownerEvents.flush(); expect(JSON.stringify(ownerEvents.events)).not.toContain(viewerAvatar);
    const views = await authGet(`/status/${id}/views`, owner.token);
    expect(views.status).toBe(200); expect(await views.text()).not.toContain(viewerAvatar);
    ownerEvents.socket.close(1000, 'finished'); viewerEvents.socket.close(1000, 'finished');
  });
});

describe('input bounds', () => {
  it('rejects oversized content, reactions, status captions, group names and profile text', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount();
    await friend(owner.id, peer.id);
    const message = await post('/messages', owner.token, {receiver_id:peer.id, content:'hello'});
    const id = (await message.json() as {message:{id:string}}).message.id;
    const cases: Array<[string, string, object]> = [
      ['POST', '/messages', {receiver_id:peer.id, content:'x'.repeat(65537)}],
      ['PATCH', `/messages/${id}`, {content:'x'.repeat(65537)}],
      ['POST', `/messages/${id}/reaction`, {emoji:'x'.repeat(33)}],
      ['POST', '/status', {content_type:'text', text_content:'x'.repeat(4097)}],
      ['POST', '/groups', {name:'x'.repeat(101)}],
      ['PATCH', '/users/me', {bio:'x'.repeat(501)}],
    ];
    for (const [method,path,body] of cases) {
      const res = await SELF.fetch(`https://example.test${path}`, {method,headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify(body)});
      expect(res.status, path).toBe(400);
    }
    expect((await env.DB.prepare('SELECT content FROM messages WHERE id = ?').bind(id).first<{content:string}>())?.content).toBe('hello');
  });
});

describe('group call membership and privacy', () => {
  it('does not let a removed participant keep a call alive or receive call events', async () => {
    const f = await groupCallFixture();
    const removed = await inbox(f.member);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id = ? AND user_id = ?').bind(f.group, f.member.id).run();
    expect((await post(`/group-calls/${f.call}/leave`, f.owner.token)).status).toBe(200);
    expect((await env.DB.prepare('SELECT status FROM group_calls WHERE id = ?').bind(f.call).first<{status:string}>())?.status).toBe('ended');
    await removed.flush(); expect(removed.events).toHaveLength(0);
    removed.socket.close(1000, 'finished');
  });
  it('hides a private caller photo from other group members during ringing and joining', async () => {
    const f = await fixture();
    const avatar = `https://images.example/caller-${crypto.randomUUID()}`;
    await env.DB.prepare('UPDATE users SET avatar_url = ? WHERE id = ?').bind(avatar, f.owner.id).run();
    await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility = 'nobody' WHERE user_id = ?").bind(f.owner.id).run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), f.group, f.outsider.id, 'member').run();
    const listener = await inbox(f.outsider);
    const started = await post('/group-calls', f.owner.token, {group_id:f.group,call_type:'audio'});
    expect(started.status).toBe(201);
    const id = (await started.json() as {call:{id:string}}).call.id;
    await listener.flush(); expect(JSON.stringify(listener.events)).not.toContain(avatar);
    const joined = await post(`/group-calls/${id}/join`, f.outsider.token);
    expect(joined.status).toBe(200); expect(await joined.text()).not.toContain(avatar);
    listener.socket.close(1000, 'finished');
  });
});

describe('trusted media provenance', () => {
  it('does not turn historical forged direct-message references into file access', async () => {
    const owner = await mediaAccount(), attacker = await mediaAccount(), peer = await mediaAccount();
    const file = await mediaUpload(owner.token);
    await friend(attacker.id, peer.id);
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, media_url) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), attacker.id, peer.id, file.url).run();
    expect((await mediaRead(file.key, attacker.token)).status).toBe(404);
    expect((await mediaRead(file.key, peer.token)).status).toBe(404);
    expect((await post('/messages', attacker.token, {receiver_id:peer.id, media_url:file.url})).status).toBe(403);
  });
  it('does not trust historical forged group, status, or avatar references', async () => {
    const owner = await mediaAccount(), attacker = await mediaAccount(), viewer = await mediaAccount();
    await friend(attacker.id, viewer.id);
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id, name, created_by) VALUES (?, ?, ?)').bind(group, 'Test', attacker.id).run();
    for (const user of [attacker, viewer]) await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, user.id, 'member').run();
    const groupFile = await mediaUpload(owner.token);
    await env.DB.prepare('INSERT INTO group_messages (id, group_id, sender_id, media_url) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), group, attacker.id, groupFile.url).run();
    expect((await mediaRead(groupFile.key, viewer.token)).status).toBe(404);
    const statusFile = await mediaUpload(owner.token);
    await env.DB.prepare("INSERT INTO statuses (id,user_id,content_type,content_url,privacy,expires_at) VALUES (?,?,'image',?,'contacts',?)").bind(crypto.randomUUID(), attacker.id, statusFile.url, new Date(Date.now() + 3600000).toISOString()).run();
    expect((await mediaRead(statusFile.key, viewer.token)).status).toBe(404);
    const avatarFile = await mediaUpload(owner.token);
    await env.DB.prepare('UPDATE users SET avatar_url = ? WHERE id = ?').bind(avatarFile.url, attacker.id).run();
    expect((await mediaRead(avatarFile.key, viewer.token)).status).toBe(404);
    const groupAvatarFile = await mediaUpload(owner.token);
    await env.DB.prepare('UPDATE groups SET avatar_url = ? WHERE id = ?').bind(groupAvatarFile.url, group).run();
    expect((await mediaRead(groupAvatarFile.key, viewer.token)).status).toBe(404);
  });
  it('continues to allow uploader-authored legacy shares and authorized new forwarding', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(), recipient = await mediaAccount();
    await friend(peer.id, recipient.id);
    const file = await mediaUpload(owner.token);
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, media_url) VALUES (?, ?, ?, ?)').bind(crypto.randomUUID(), owner.id, peer.id, file.url).run();
    expect((await mediaRead(file.key, peer.token)).status).toBe(200);
    expect((await post('/messages', peer.token, {receiver_id:recipient.id, type:'image', media_url:file.url})).status).toBe(201);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
  });
});

describe('trusted media reference lifecycle', () => {
  it('records authorized forwarding across messages, avatars and statuses and removes stale grants', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(), recipient = await mediaAccount();
    await friend(owner.id, peer.id); await friend(peer.id, recipient.id);
    const file = await mediaUpload(owner.token);
    expect((await post('/messages', owner.token, {receiver_id:peer.id, media_url:file.url})).status).toBe(201);
    const sent = await post('/messages', peer.token, {receiver_id:recipient.id, media_url:file.url});
    expect(sent.status).toBe(201);
    const msg = (await sent.json() as {message:{id:string}}).message.id;
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
    expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE file_path = ? AND kind = 'message' AND reference_id = ?").bind(file.key, msg).first()).not.toBeNull();
    await env.DB.prepare('DELETE FROM messages WHERE id = ?').bind(msg).run();
    expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE file_path = ? AND kind = 'message' AND reference_id = ?").bind(file.key, msg).first()).toBeNull();
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
    const profile = await SELF.fetch('https://example.test/users/me', {method:'PATCH',headers:{Authorization:`Bearer ${peer.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:file.url})});
    expect(profile.status).toBe(200);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
    const reset = await SELF.fetch('https://example.test/users/me', {method:'PATCH',headers:{Authorization:`Bearer ${peer.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:''})});
    expect(reset.status).toBe(200);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
    const status = await post('/status', peer.token, {content_type:'image',content_url:file.url,privacy:'contacts'});
    expect(status.status).toBe(201);
    const statusId = (await status.json() as {status:{id:string}}).status.id;
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
    expect((await SELF.fetch(`https://example.test/status/${statusId}`, {method:'DELETE',headers:{Authorization:`Bearer ${peer.token}`}})).status).toBe(200);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
    const groupRes = await post('/groups', peer.token, {name:'Shared',memberIds:[recipient.id],avatar_url:file.url});
    expect(groupRes.status).toBe(201);
    const groupId = (await groupRes.json() as {group:{id:string}}).group.id;
    expect((await mediaRead(file.key, recipient.token)).status).toBe(200);
    const update = await SELF.fetch(`https://example.test/groups/${groupId}`, {method:'PATCH',headers:{Authorization:`Bearer ${peer.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:''})});
    expect(update.status).toBe(200);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
  });
  it('fails closed for unverifiable old forwards and old group avatars', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(), recipient = await mediaAccount();
    const file = await mediaUpload(owner.token);
    await env.DB.prepare('INSERT INTO messages (id,sender_id,receiver_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),owner.id,peer.id,file.url).run();
    await env.DB.prepare('INSERT INTO messages (id,sender_id,receiver_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),peer.id,recipient.id,file.url).run();
    expect((await mediaRead(file.key, peer.token)).status).toBe(200);
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id,name,created_by,avatar_url) VALUES (?,?,?,?)').bind(group,'Old group',owner.id,file.url).run();
    await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,recipient.id,'member').run();
    expect((await mediaRead(file.key, recipient.token)).status).toBe(404);
  });
});

describe('request body limits', () => {
  it('rejects oversized JSON even when the oversized field is ignored', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(); await friend(owner.id, peer.id);
    const attempts: Array<[string, object]> = [
      ['/messages', {receiver_id:peer.id, content:'hello', ignored:'x'.repeat(262145)}],
      ['/status', {content_type:'text', text_content:'hello', ignored:'x'.repeat(262145)}],
      ['/friends/request', {receiver_id:peer.id, ignored:'x'.repeat(262145)}],
    ];
    for (const [path, body] of attempts) expect((await post(path, owner.token, body)).status, path).toBe(413);
  });
  it('rejects streamed JSON without relying on a content length', async () => {
    const owner = await mediaAccount(), peer = await mediaAccount(); await friend(owner.id, peer.id);
    const data = new TextEncoder().encode(JSON.stringify({receiver_id:peer.id, content:'hello', ignored:'x'.repeat(262145)}));
    const body = new ReadableStream({start(controller) {controller.enqueue(data); controller.close();}});
    const response = await SELF.fetch('https://example.test/messages', {method:'POST',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body,duplex:'half'} as RequestInit);
    expect(response.status).toBe(413);
  });
  it('rejects an upload whose declared body exceeds the Android-compatible limit', async () => {
    const owner = await mediaAccount();
    const form = new FormData(); form.set('file', new File(['small'], 'a.txt'));
    const response = await SELF.fetch('https://example.test/media/upload', {method:'POST',headers:{Authorization:`Bearer ${owner.token}`, 'Content-Length':String(52 * 1024 * 1024)},body:form});
    expect(response.status).toBe(413);
  });
});

describe('IPv6 credential limit buckets', () => {
  it('does not grant a new signup budget for every address in the same IPv6 /64', async () => {
    const prefix = `2001:db8:${crypto.randomUUID().slice(0,4)}:42`;
    const statuses: number[] = [];
    for (let i=1; i<=12; i++) {
      const response = await SELF.fetch('https://example.test/auth/signup', {
        method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP':`${prefix}::${i.toString(16)}`},
        body:JSON.stringify({email:`${crypto.randomUUID()}@example.test`,password:'password123'}),
      });
      statuses.push(response.status);
    }
    expect(statuses.slice(0,10).every(status => status === 201)).toBe(true);
    expect(statuses.slice(10)).toEqual([429,429]);
  });
});

describe('authenticated abuse limits', () => {
  it('shares a contact-action budget across requests and group creation without affecting other users', async () => {
    const sender = await mediaAccount(), target = await mediaAccount(), other = await mediaAccount();
    const statuses:number[] = [];
    for (let i=0; i<31; i++) statuses.push((await post('/friends/request', sender.token, {receiver_id:target.id})).status);
    expect(statuses[0]).toBe(201);
    expect(statuses.slice(1,30).every(status => status === 409)).toBe(true);
    expect(statuses[30]).toBe(429);
    for (const path of ['/groups', '/groups/example/members', '/calls', '/group-calls']) {
      expect((await post(path, sender.token, {})).status, path).toBe(429);
    }
    expect((await post('/friends/request', other.token, {receiver_id:target.id})).status).toBe(201);
  }, 30000);
  it('limits repeated message sends per account', async () => {
    const sender = await mediaAccount(), peer = await mediaAccount(); await friend(sender.id, peer.id);
    const body = {receiver_id:peer.id, content:'hello', client_message_id:crypto.randomUUID()};
    const seeded = await Promise.all(Array.from({length:299}, () => consumeLimit(env, 'send', sender.id, 300, 60000)));
    expect(seeded.every(Boolean)).toBe(true);
    expect((await post('/messages', sender.token, body)).status).toBe(201);
    expect((await post('/messages', sender.token, body)).status).toBe(429);
    for (const path of ['/status', '/media/upload', '/groups/example/messages']) {
      expect((await post(path, sender.token, {})).status, path).toBe(429);
    }
  }, 30000);
});

describe('group departure concurrency', () => {
  it('never allows two administrators to leave a remaining member without an administrator', async () => {
    const { owner, outsider, group } = await fixture();
    const member = await mediaAccount();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, outsider.id, 'admin').run();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, member.id, 'member').run();
    const replies = await Promise.all([post(`/groups/${group}/leave`, owner.token), post(`/groups/${group}/leave`, outsider.token)]);
    expect(replies.map(r => r.status).sort()).toEqual([200, 400]);
    const admins = await env.DB.prepare("SELECT user_id FROM group_members WHERE group_id = ? AND role = 'admin'").bind(group).all();
    expect(admins.results).toHaveLength(1);
  });
  it('removing oneself as the last member removes the empty group', async () => {
    const { owner, group, message } = await fixture();
    const response = await SELF.fetch(`https://example.test/groups/${group}/members/${owner.id}`, {method:'DELETE', headers:{Authorization:`Bearer ${owner.token}`}});
    expect(response.status).toBe(200);
    expect(await env.DB.prepare('SELECT id FROM groups WHERE id = ?').bind(group).first()).toBeNull();
    expect(await env.DB.prepare('SELECT id FROM group_messages WHERE id = ?').bind(message).first()).toBeNull();
  });
});

describe('group call decline integrity', () => {
  it('delivers a valid ringing decline once', async () => {
    const f = await groupCallFixture();
    await env.DB.prepare("UPDATE group_call_participants SET status='ringing' WHERE call_id=? AND user_id=?").bind(f.call, f.member.id).run();
    const caller = await inbox(f.owner);
    expect((await post(`/group-calls/${f.call}/decline`, f.member.token)).status).toBe(200);
    expect((await post(`/group-calls/${f.call}/decline`, f.member.token)).status).toBe(409);
    await caller.flush();
    expect(caller.events).toHaveLength(1);
    expect(JSON.parse(caller.events[0])).toMatchObject({type:'group-call:participant-declined', user_id:f.member.id, call_id:f.call});
    caller.socket.close(1000, 'finished');
  });
  it('does not send decline events to a caller removed from the group', async () => {
    const f = await groupCallFixture();
    await env.DB.prepare("UPDATE group_call_participants SET status='ringing' WHERE call_id=? AND user_id=?").bind(f.call, f.member.id).run();
    const caller = await inbox(f.owner);
    await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(f.group, f.owner.id).run();
    expect((await post(`/group-calls/${f.call}/decline`, f.member.token)).status).toBe(200);
    await caller.flush(); expect(caller.events).toHaveLength(0); caller.socket.close(1000, 'finished');
  });
  it('does not let removed or non-ringing members forge decline events', async () => {
    const f = await groupCallFixture();
    const caller = await inbox(f.owner);
    expect((await post(`/group-calls/${f.call}/decline`, f.member.token)).status).toBe(409);
    await env.DB.prepare("UPDATE group_call_participants SET status='ringing' WHERE call_id=? AND user_id=?").bind(f.call, f.member.id).run();
    await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(f.group, f.member.id).run();
    expect((await post(`/group-calls/${f.call}/decline`, f.member.token)).status).toBe(403);
    await caller.flush(); expect(caller.events).toHaveLength(0); caller.socket.close(1000, 'finished');
  });
});

describe('concurrent group call creation', () => {
  it('converges simultaneous starts on a single active call', async () => {
    const { owner, outsider, group } = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, outsider.id, 'member').run();
    // Force both handlers to see the same pre-creation snapshot, regardless
    // of runtime scheduling; production D1 still executes all other SQL.
    let readers = 0;
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const db = {
      prepare(sql: string) {
        const statement = env.DB.prepare(sql);
        if (!sql.includes('SELECT * FROM group_calls WHERE group_id = ?')) return statement;
        return { bind(...args: unknown[]) {
          const bound = statement.bind(...args);
          return { async first() {
            const result = await bound.first();
            if (++readers === 2) release();
            await gate;
            return result;
          } };
        } };
      },
      batch: env.DB.batch.bind(env.DB),
    };
    const bindings = { ...env, DB: db } as unknown as Env;
    const start = (token: string) => app.request('https://example.test/group-calls', {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ group_id: group, call_type: 'audio' }),
    }, bindings);
    const responses = await Promise.all([start(owner.token), start(outsider.token)]);
    expect(responses.map(r => r.status).sort()).toEqual([200, 201]);
    const bodies = await Promise.all(responses.map(r => r.json() as Promise<{call:{id:string}}>));
    expect(bodies[0].call.id).toBe(bodies[1].call.id);
    const calls = await env.DB.prepare("SELECT id FROM group_calls WHERE group_id = ? AND status = 'active'").bind(group).all();
    expect(calls.results).toHaveLength(1);
    const joined = await env.DB.prepare("SELECT user_id FROM group_call_participants WHERE call_id = ? AND status = 'joined'").bind(bodies[0].call.id).all();
    expect(joined.results).toHaveLength(2);
  });
});

/** Pause a particular SQL write after its preceding authorization reads. */
function pauseWrite(match: string) {
  let notify!: () => void, resume!: () => void;
  const reached = new Promise<void>(resolve => { notify = resolve; });
  const gate = new Promise<void>(resolve => { resume = resolve; });
  const db = {
    prepare(sql: string) {
      const statement = env.DB.prepare(sql);
      if (!sql.includes(match)) return statement;
      return { bind(...args: unknown[]) {
        const bound = statement.bind(...args);
        return { async run() { notify(); await gate; return bound.run(); } };
      } };
    },
    batch: env.DB.batch.bind(env.DB),
  };
  return { reached, resume, bindings: { ...env, DB: db } as unknown as Env };
}
function requestWithBindings(path: string, token: string, bindings: Env) {
  return app.request(`https://example.test${path}`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}',
  }, bindings);
}

describe('group call join and leave races', () => {
  it('cannot join after the last participant has ended the call', async () => {
    const f = await groupCallFixture();
    await env.DB.prepare("UPDATE group_call_participants SET status='ringing' WHERE call_id=? AND user_id=?").bind(f.call, f.member.id).run();
    const paused = pauseWrite('INSERT INTO group_call_participants (call_id, user_id, status, joined_at)');
    const joining = requestWithBindings(`/group-calls/${f.call}/join`, f.member.token, paused.bindings);
    await paused.reached;
    try {
      expect((await post(`/group-calls/${f.call}/leave`, f.owner.token)).status).toBe(200);
    } finally { paused.resume(); }
    expect((await joining).status).toBe(409);
    const participant = await env.DB.prepare('SELECT status FROM group_call_participants WHERE call_id=? AND user_id=?').bind(f.call, f.member.id).first<{status:string}>();
    expect(participant?.status).not.toBe('joined');
  });
  it('does not end a call when someone joins after the leave check', async () => {
    const f = await groupCallFixture();
    await env.DB.prepare("UPDATE group_call_participants SET status='ringing' WHERE call_id=? AND user_id=?").bind(f.call, f.member.id).run();
    const paused = pauseWrite("UPDATE group_calls SET status = 'ended'");
    const leaving = requestWithBindings(`/group-calls/${f.call}/leave`, f.owner.token, paused.bindings);
    await paused.reached;
    try {
      expect((await post(`/group-calls/${f.call}/join`, f.member.token)).status).toBe(200);
    } finally { paused.resume(); }
    expect((await leaving).status).toBe(200);
    const call = await env.DB.prepare('SELECT status FROM group_calls WHERE id=?').bind(f.call).first<{status:string}>();
    expect(call?.status).toBe('active');
  });
  it('cannot join after removal from the group', async () => {
    const f = await groupCallFixture();
    const paused = pauseWrite('INSERT INTO group_call_participants (call_id, user_id, status, joined_at)');
    const joining = requestWithBindings(`/group-calls/${f.call}/join`, f.member.token, paused.bindings);
    await paused.reached;
    try {
      await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(f.group, f.member.id).run();
    } finally { paused.resume(); }
    expect((await joining).status).toBe(403);
  });
});

describe('group role change authorization under concurrency', () => {
  it('does not allow two admins to demote each other and leave a group without an admin', async () => {
    const { owner, outsider, group } = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, outsider.id, 'admin').run();
    let count = 0;
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const db = {
      prepare(sql: string) {
        const statement = env.DB.prepare(sql);
        if (!sql.includes('UPDATE group_members SET role = ?')) return statement;
        return { bind(...args: unknown[]) {
          const bound = statement.bind(...args);
          return { async run() { if (++count === 2) release(); await gate; return bound.run(); } };
        } };
      },
      batch: env.DB.batch.bind(env.DB),
    };
    const bindings = { ...env, DB: db } as unknown as Env;
    const change = (actor: {token:string}, target: string) => app.request(`https://example.test/groups/${group}/members/${target}`, {
      method: 'PATCH', headers: {Authorization:`Bearer ${actor.token}`, 'Content-Type':'application/json'}, body:JSON.stringify({role:'member'}),
    }, bindings);
    const results = await Promise.all([change(owner, outsider.id), change(outsider, owner.id)]);
    expect(results.map(r => r.status).sort()).toEqual([200, 403]);
    const admins = await env.DB.prepare("SELECT user_id FROM group_members WHERE group_id = ? AND role = 'admin'").bind(group).all();
    expect(admins.results).toHaveLength(1);
  });
});

describe('group member addition authorization under concurrency', () => {
  it('lets an admin add members and safely repeat an addition', async () => {
    const { owner, outsider, group } = await fixture();
    const path = `/groups/${group}/members`;
    expect((await post(path, owner.token, {memberIds:[outsider.id]})).status).toBe(200);
    expect((await post(path, owner.token, {memberIds:[outsider.id]})).status).toBe(200);
    const rows = await env.DB.prepare('SELECT user_id FROM group_members WHERE group_id=? AND user_id=?').bind(group, outsider.id).all();
    expect(rows.results).toHaveLength(1);
    expect((await post(path, outsider.token, {memberIds:[owner.id]})).status).toBe(403);
  });
  it('does not add a member when the administrator is demoted before the write', async () => {
    const { owner, outsider, group } = await fixture();
    const invitee = await mediaAccount();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, outsider.id, 'admin').run();
    let reached!: () => void, release!: () => void;
    const atBatch = new Promise<void>(resolve => { reached = resolve; });
    const gate = new Promise<void>(resolve => { release = resolve; });
    const db = {
      prepare: env.DB.prepare.bind(env.DB),
      async batch(statements: D1PreparedStatement[]) { reached(); await gate; return env.DB.batch(statements); },
    };
    const bindings = { ...env, DB: db } as unknown as Env;
    const request = app.request(`https://example.test/groups/${group}/members`, {
      method:'POST', headers:{Authorization:`Bearer ${owner.token}`, 'Content-Type':'application/json'},
      body:JSON.stringify({memberIds:[invitee.id]}),
    }, bindings);
    await atBatch;
    try {
      await env.DB.prepare("UPDATE group_members SET role='member' WHERE group_id=? AND user_id=?").bind(group, owner.id).run();
    } finally { release(); }
    expect((await request).status).toBe(403);
    expect(await env.DB.prepare('SELECT 1 FROM group_members WHERE group_id=? AND user_id=?').bind(group, invitee.id).first()).toBeNull();
  });
});

describe('group deletion authorization under concurrency', () => {
  it('deletes related group records and notifies members when an admin deletes the group', async () => {
    const f = await groupCallFixture();
    const listener = await inbox(f.member);
    await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,content) VALUES (?,?,?,?)').bind(crypto.randomUUID(), f.group, f.owner.id, 'hello').run();
    await env.DB.prepare('INSERT INTO group_read_state (group_id,user_id,last_read_at) VALUES (?,?,?)').bind(f.group, f.member.id, new Date().toISOString()).run();
    const response = await SELF.fetch(`https://example.test/groups/${f.group}`, {method:'DELETE',headers:{Authorization:`Bearer ${f.owner.token}`}});
    expect(response.status).toBe(200);
    for (const table of ['groups', 'group_members', 'group_messages', 'group_read_state', 'group_calls']) {
      const col = table === 'groups' ? 'id' : 'group_id';
      expect(await env.DB.prepare(`SELECT 1 FROM ${table} WHERE ${col}=?`).bind(f.group).first(), table).toBeNull();
    }
    expect(await env.DB.prepare('SELECT 1 FROM group_call_participants WHERE call_id=?').bind(f.call).first()).toBeNull();
    await listener.flush();
    expect(listener.events.map(event => JSON.parse(event))).toContainEqual({type:'group:deleted',group_id:f.group});
    listener.socket.close(1000, 'finished');
  });
  it('does not delete a group after the actor is demoted', async () => {
    const { owner, outsider, group, message } = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role) VALUES (?, ?, ?, ?)')
      .bind(crypto.randomUUID(), group, outsider.id, 'admin').run();
    let reached!: () => void, release!: () => void;
    const ready = new Promise<void>(resolve => { reached = resolve; });
    const gate = new Promise<void>(resolve => { release = resolve; });
    const db = {
      prepare(sql:string) {
        const statement = env.DB.prepare(sql);
        if (sql !== 'SELECT user_id FROM group_members WHERE group_id = ?') return statement;
        return {bind(...args:unknown[]) {
          const bound = statement.bind(...args);
          return {async all() { const result = await bound.all(); reached(); await gate; return result; }};
        }};
      },
      batch: env.DB.batch.bind(env.DB),
    };
    const request = app.request(`https://example.test/groups/${group}`, {
      method:'DELETE', headers:{Authorization:`Bearer ${owner.token}`},
    }, {...env, DB:db} as unknown as Env);
    await ready;
    try {
      await env.DB.prepare("UPDATE group_members SET role='member' WHERE group_id=? AND user_id=?").bind(group, owner.id).run();
    } finally { release(); }
    expect((await request).status).toBe(403);
    expect(await env.DB.prepare('SELECT id FROM groups WHERE id=?').bind(group).first()).not.toBeNull();
    expect(await env.DB.prepare('SELECT id FROM group_messages WHERE id=?').bind(message).first()).not.toBeNull();
  });
});

describe('group update authorization under concurrency', () => {
  it('does not change group text when the admin is demoted before the write', async () => {
    const {owner, outsider, group} = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,outsider.id,'admin').run();
    const paused = pauseWrite('UPDATE groups SET name = ?');
    const request = app.request(`https://example.test/groups/${group}`, {method:'PATCH', headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({name:'Not authorized'})}, paused.bindings);
    await paused.reached;
    try { await env.DB.prepare("UPDATE group_members SET role='member' WHERE group_id=? AND user_id=?").bind(group,owner.id).run(); }
    finally { paused.resume(); }
    expect((await request).status).toBe(403);
    expect((await env.DB.prepare('SELECT name FROM groups WHERE id=?').bind(group).first<{name:string}>())?.name).toBe('Test');
  });
  it('does not replace avatar or media grants when the admin is demoted before the batch', async () => {
    const {owner, outsider, group} = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,outsider.id,'admin').run();
    const oldFile = await mediaUpload(owner.token), newFile = await mediaUpload(owner.token);
    const initial = await SELF.fetch(`https://example.test/groups/${group}`, {method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:oldFile.url})});
    expect(initial.status).toBe(200);
    let reached!:()=>void, release!:()=>void;
    const atBatch = new Promise<void>(resolve=>{reached=resolve;});
    const gate = new Promise<void>(resolve=>{release=resolve;});
    const db = {prepare:env.DB.prepare.bind(env.DB), async batch(statements:D1PreparedStatement[]) {reached(); await gate; return env.DB.batch(statements);}};
    const request = app.request(`https://example.test/groups/${group}`, {method:'PATCH', headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:newFile.url})}, {...env, DB:db} as unknown as Env);
    await atBatch;
    try { await env.DB.prepare("UPDATE group_members SET role='member' WHERE group_id=? AND user_id=?").bind(group,owner.id).run(); }
    finally {release();}
    expect((await request).status).toBe(403);
    expect((await env.DB.prepare('SELECT avatar_url FROM groups WHERE id=?').bind(group).first<{avatar_url:string}>())?.avatar_url).toBe(oldFile.url);
    const refs = await env.DB.prepare("SELECT file_path FROM trusted_media_references WHERE kind='group_avatar' AND reference_id=?").bind(group).all<{file_path:string}>();
    expect(refs.results.map(r=>r.file_path)).toEqual([oldFile.key]);
    expect((await mediaRead(oldFile.key, outsider.token)).status).toBe(200);
    expect((await mediaRead(newFile.key, outsider.token)).status).toBe(404);
  });
});

describe('group message send authorization under concurrency', () => {
  for (const withMedia of [false, true]) {
    it(`rejects a removed sender before a ${withMedia ? 'media' : 'text'} write`, async () => {
      const {owner, outsider, group} = await fixture();
      await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,outsider.id,'member').run();
      const file = withMedia ? await mediaUpload(outsider.token) : undefined;
      const listener = await inbox(owner);
      let reached!:()=>void, release!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;});
      const gate = new Promise<void>(resolve=>{release=resolve;});
      const db = {
        prepare(sql:string) {
          const statement = env.DB.prepare(sql);
          if (withMedia || !sql.includes('INSERT INTO group_messages')) return statement;
          return {bind(...args:unknown[]) {const bound = statement.bind(...args); return {async run(){reached(); await gate; return bound.run();}};}};
        },
        async batch(statements:D1PreparedStatement[]) { if (withMedia) {reached(); await gate;} return env.DB.batch(statements); },
      };
      const request = app.request(`https://example.test/groups/${group}/messages`, {
        method:'POST',headers:{Authorization:`Bearer ${outsider.token}`,'Content-Type':'application/json'},
        body:JSON.stringify({content:'sent after removal', ...(file ? {media_url:file.url} : {})}),
      }, {...env, DB:db} as unknown as Env);
      await ready;
      try { await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(group,outsider.id).run(); }
      finally {release();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT id FROM group_messages WHERE group_id=? AND sender_id=?').bind(group,outsider.id).first()).toBeNull();
      if (file) expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE kind='group_message' AND file_path=?").bind(file.key).first()).toBeNull();
      await listener.flush(); expect(listener.events).toHaveLength(0); listener.socket.close(1000, 'finished');
    });
  }
});

describe('group message actions under concurrent removal', () => {
  const actions = [
    {path:'delete-for-me', sql:'UPDATE group_messages SET deleted_for = ?', body:{}},
    {path:'delete-for-everyone', sql:'UPDATE group_messages SET is_deleted = 1', body:{}},
    {path:'reaction', sql:'UPDATE group_messages SET reactions = ?', body:{emoji:'👍'}},
  ];
  for (const action of actions) {
    it(`rejects ${action.path} if the actor is removed before the write`, async () => {
      const {owner, outsider, group, message} = await fixture();
      await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,outsider.id,'admin').run();
      const listener = await inbox(outsider);
      const paused = pauseWrite(action.sql);
      const request = app.request(`https://example.test/groups/messages/${message}/${action.path}`, {
        method:'POST',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify(action.body),
      }, paused.bindings);
      await paused.reached;
      try { await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(group,owner.id).run(); }
      finally {paused.resume();}
      expect((await request).status).toBe(403);
      const row = await env.DB.prepare('SELECT is_deleted, deleted_for, reactions FROM group_messages WHERE id=?').bind(message).first<{is_deleted:number;deleted_for:string|null;reactions:string|null}>();
      expect(row).toMatchObject({is_deleted:0,deleted_for:null,reactions:null});
      await listener.flush(); expect(listener.events).toHaveLength(0); listener.socket.close(1000,'finished');
    });
  }
});

describe('call signalling abuse limits', () => {
  it('shares a per-account signal budget across direct and group calls without limiting other accounts', async () => {
    const f = await groupCallFixture();
    const direct = crypto.randomUUID();
    await env.DB.prepare("INSERT INTO calls (id,caller_id,receiver_id,call_type,status) VALUES (?,?,?,'audio','ringing')").bind(direct,f.owner.id,f.member.id).run();
    const target = await inbox(f.member);
    const seeds = await Promise.all(Array.from({length:599}, () => consumeLimit(env, 'signal', f.owner.id, 600, 60000)));
    expect(seeds.every(Boolean)).toBe(true);
    const payload = {to:f.member.id,candidate:{candidate:'candidate:1 1 udp 1 192.0.2.1 1234 typ host',sdpMid:'0',sdpMLineIndex:0}};
    expect((await post(`/group-calls/${f.call}/signal`,f.owner.token,payload)).status).toBe(200);
    expect((await post(`/calls/${direct}/signal`,f.owner.token,{candidate:payload.candidate})).status).toBe(429);
    await target.flush();
    expect(target.events).toHaveLength(1);
    expect((await post(`/calls/${direct}/signal`,f.member.token,{candidate:payload.candidate})).status).toBe(200);
    target.socket.close(1000,'finished');
  }, 30000);
});

describe('group call signalling participation', () => {
  it('does not deliver signals from group members who never joined the call', async () => {
    const f = await groupCallFixture();
    await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),f.group,f.outsider.id,'member').run();
    const target = await inbox(f.owner);
    expect((await post(`/group-calls/${f.call}/signal`,f.outsider.token,{to:f.owner.id,candidate:{candidate:'forged'}})).status).toBe(403);
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
  it('does not deliver signals to participants who are ringing, declined or left', async () => {
    const f = await groupCallFixture();
    const target = await inbox(f.member);
    for (const status of ['ringing','declined','left']) {
      await env.DB.prepare('UPDATE group_call_participants SET status=? WHERE call_id=? AND user_id=?').bind(status,f.call,f.member.id).run();
      expect((await post(`/group-calls/${f.call}/signal`,f.owner.token,{to:f.member.id,candidate:{candidate:'late'}})).status, status).toBe(403);
    }
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
});

describe('direct call state concurrency', () => {
  async function directFixture() {
    const caller = await mediaAccount(), callee = await mediaAccount(), call = crypto.randomUUID();
    await env.DB.prepare("INSERT INTO calls (id,caller_id,receiver_id,call_type,status) VALUES (?,?,?,'audio','ringing')").bind(call,caller.id,callee.id).run();
    return {caller,callee,call};
  }
  it('cannot answer a call after the caller ended it', async () => {
    const f = await directFixture(), target = await inbox(f.caller);
    const paused = pauseWrite("UPDATE calls SET status = 'answered'");
    const request = requestWithBindings(`/calls/${f.call}/answer`,f.callee.token,paused.bindings);
    await paused.reached;
    try { expect((await post(`/calls/${f.call}/end`,f.caller.token,{status:'ended'})).status).toBe(200); }
    finally {paused.resume();}
    expect((await request).status).toBe(409);
    const row = await env.DB.prepare('SELECT status,ended_at FROM calls WHERE id=?').bind(f.call).first<{status:string;ended_at:string|null}>();
    expect(row?.status).toBe('ended'); expect(row?.ended_at).not.toBeNull();
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
  it('does not overwrite or re-announce a concurrent call end', async () => {
    const f = await directFixture(), target = await inbox(f.callee);
    const paused = pauseWrite('UPDATE calls SET status = ?, duration = ?');
    const request = requestWithBindings(`/calls/${f.call}/end`,f.caller.token,paused.bindings);
    await paused.reached;
    try { expect((await post(`/calls/${f.call}/end`,f.callee.token,{status:'declined',duration:0})).status).toBe(200); }
    finally {paused.resume();}
    expect((await request).status).toBe(200);
    const row = await env.DB.prepare('SELECT status,duration FROM calls WHERE id=?').bind(f.call).first<{status:string;duration:number|null}>();
    expect(row).toMatchObject({status:'declined',duration:0});
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
  it('only accepts and announces one concurrent answer', async () => {
    const f = await directFixture(), target = await inbox(f.caller);
    const paused = pauseWrite("UPDATE calls SET status = 'answered'");
    const request = requestWithBindings(`/calls/${f.call}/answer`,f.callee.token,paused.bindings);
    await paused.reached;
    try { expect((await post(`/calls/${f.call}/answer`,f.callee.token,{answer_sdp:{type:'answer',sdp:'v=0'}})).status).toBe(200); }
    finally {paused.resume();}
    expect((await request).status).toBe(409);
    await target.flush(); expect(target.events).toHaveLength(1); target.socket.close(1000,'finished');
  });
});

describe('blocking during contact initiation', () => {
  it('allows a valid direct call and a valid friend request', async () => {
    const actor = await mediaAccount(), recipient = await mediaAccount(), target = await inbox(recipient);
    expect((await post('/calls',actor.token,{receiver_id:recipient.id,call_type:'audio',offer_sdp:{type:'offer',sdp:'v=0'}})).status).toBe(201);
    expect((await post('/friends/request',actor.token,{receiver_id:recipient.id})).status).toBe(201);
    await target.flush();
    expect(target.events.map(e=>JSON.parse(e).type).sort()).toEqual(['call:incoming','friend:request']);
    target.socket.close(1000,'finished');
  });
  it('reopens a rejected request from the same sender', async () => {
    const actor = await mediaAccount(), recipient = await mediaAccount();
    const first = await post('/friends/request', actor.token, {receiver_id:recipient.id});
    expect(first.status).toBe(201);
    const {id} = await first.json() as {id:string};
    expect((await post(`/friends/${id}/respond`, recipient.token, {accept:false})).status).toBe(200);
    expect((await post('/friends/request', actor.token, {receiver_id:recipient.id})).status).toBe(201);
    const row = await env.DB.prepare('SELECT id, status FROM friend_requests WHERE sender_id = ? AND receiver_id = ?').bind(actor.id,recipient.id).first<{id:string;status:string}>();
    expect(row).toEqual({id,status:'pending'});
  });
  const routes = [
    {path:'/calls',sql:'INSERT INTO calls (id, caller_id',table:'calls',body:(id:string)=>({receiver_id:id,call_type:'audio'})},
    {path:'/friends/request',sql:'INSERT INTO friend_requests (id, sender_id',table:'friend_requests',body:(id:string)=>({receiver_id:id})},
  ];
  for (const route of routes) {
    it(`does not persist or announce ${route.path} after the recipient blocks the actor`, async () => {
      const actor = await mediaAccount(), recipient = await mediaAccount(), target = await inbox(recipient);
      const paused = pauseWrite(route.sql);
      const request = app.request(`https://example.test${route.path}`, {
        method:'POST',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify(route.body(recipient.id)),
      }, paused.bindings);
      await paused.reached;
      try { await env.DB.prepare('INSERT INTO blocked_users (id,user_id,blocked_user_id) VALUES (?,?,?)').bind(crypto.randomUUID(),recipient.id,actor.id).run(); }
      finally { paused.resume(); }
      expect((await request).status).toBe(403);
      const column = route.table === 'calls' ? 'caller_id' : 'sender_id';
      expect(await env.DB.prepare(`SELECT 1 FROM ${route.table} WHERE ${column}=? AND receiver_id=?`).bind(actor.id,recipient.id).first()).toBeNull();
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
    });
  }
});
