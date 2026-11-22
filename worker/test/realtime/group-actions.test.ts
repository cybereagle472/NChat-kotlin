import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';
import { consumeLimit } from '../../src/lib/rate-limit';
import app from '../../src/index';
import type { Env } from '../../src/types';

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });
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
        return {
          async run() { notify(); await gate; return bound.run(); },
          async first() { notify(); await gate; return bound.first(); },
          async all() { notify(); await gate; return bound.all(); },
        };
      } };
    },
    batch: env.DB.batch.bind(env.DB),
  };
  return { reached, resume, bindings: { ...env, DB: db } as unknown as Env };
}
function pauseBatch() {
  let notify!: () => void, resume!: () => void;
  const reached = new Promise<void>(resolve => { notify = resolve; });
  const gate = new Promise<void>(resolve => { resume = resolve; });
  const db = { prepare: env.DB.prepare.bind(env.DB), async batch(statements: D1PreparedStatement[]) {
    notify(); await gate; return env.DB.batch(statements);
  }};
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
    const paused = pauseWrite('UPDATE groups SET name = CASE');
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
    {path:'delete-for-me', sql:'UPDATE group_messages SET deleted_for = CASE', body:{}},
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
    const target = await inbox(recipient);
    const retry = await post('/friends/request', actor.token, {receiver_id:recipient.id});
    expect(retry.status).toBe(201);
    const retryBody = await retry.json() as {id:string};
    const row = await env.DB.prepare('SELECT id, status FROM friend_requests WHERE sender_id = ? AND receiver_id = ?').bind(actor.id,recipient.id).first<{id:string;status:string}>();
    expect(row).toEqual({id,status:'pending'});
    await target.flush();
    const event = target.events.map(e=>JSON.parse(e)).find(e=>e.type === 'friend:request');
    target.socket.close(1000,'finished');
    expect(retryBody.id).toBe(id);
    expect(event?.request.id).toBe(id);
    expect((await post(`/friends/${retryBody.id}/respond`, recipient.token, {accept:true})).status).toBe(200);
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

describe('friend request response authorization under concurrency', () => {
  async function requestFixture() {
    const sender = await mediaAccount(), receiver = await mediaAccount();
    const response = await post('/friends/request',sender.token,{receiver_id:receiver.id});
    expect(response.status).toBe(201);
    const {id} = await response.json() as {id:string};
    return {sender,receiver,id};
  }
  it('cannot accept a request after either party blocks the other', async () => {
    for (const blockingParty of ['sender','receiver'] as const) {
      const f = await requestFixture(), target = await inbox(f.sender);
      const blocker = f[blockingParty], blocked = blockingParty === 'sender' ? f.receiver : f.sender;
      expect((await post('/friends/block',blocker.token,{user_id:blocked.id})).status).toBe(200);
      expect((await post(`/friends/${f.id}/respond`,f.receiver.token,{accept:true})).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM friendships WHERE (user1_id=? AND user2_id=?) OR (user1_id=? AND user2_id=?)').bind(f.sender.id,f.receiver.id,f.receiver.id,f.sender.id).first()).toBeNull();
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
    }
  },30000);
  it('cannot accept if a block is applied after the request was read', async () => {
    const f = await requestFixture(), target = await inbox(f.sender);
    let reached!:()=>void, resume!:()=>void;
    const paused = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
    const db = {prepare:env.DB.prepare.bind(env.DB), async batch(statements:D1PreparedStatement[]) {reached(); await gate; return env.DB.batch(statements);}};
    const request = app.request(`https://example.test/friends/${f.id}/respond`,{
      method:'POST',headers:{Authorization:`Bearer ${f.receiver.token}`,'Content-Type':'application/json'},body:JSON.stringify({accept:true}),
    }, {...env,DB:db} as unknown as Env);
    await paused;
    try { expect((await post('/friends/block',f.sender.token,{user_id:f.receiver.id})).status).toBe(200); }
    finally {resume();}
    expect((await request).status).toBe(403);
    expect(await env.DB.prepare('SELECT 1 FROM friendships WHERE user1_id=? AND user2_id=?').bind(f.sender.id,f.receiver.id).first()).toBeNull();
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
  it('cannot create a friendship from a cancelled request', async () => {
    const f = await requestFixture(), target = await inbox(f.sender);
    let reached!:()=>void, resume!:()=>void;
    const paused = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
    const db = {prepare:env.DB.prepare.bind(env.DB), async batch(statements:D1PreparedStatement[]) {reached(); await gate; return env.DB.batch(statements);}};
    const request = app.request(`https://example.test/friends/${f.id}/respond`,{
      method:'POST',headers:{Authorization:`Bearer ${f.receiver.token}`,'Content-Type':'application/json'},body:JSON.stringify({accept:true}),
    }, {...env,DB:db} as unknown as Env);
    await paused;
    try {
      const cancelled = await app.request(`https://example.test/friends/request/${f.id}`,{method:'DELETE',headers:{Authorization:`Bearer ${f.sender.token}`}},env);
      expect(cancelled.status).toBe(200);
    } finally {resume();}
    expect((await request).status).toBe(409);
    expect(await env.DB.prepare('SELECT 1 FROM friendships WHERE user1_id=? AND user2_id=?').bind(f.sender.id,f.receiver.id).first()).toBeNull();
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
  it('does not let a stale rejection overwrite a concurrent acceptance', async () => {
    const f = await requestFixture(), target = await inbox(f.sender);
    const paused = pauseWrite("UPDATE friend_requests SET status = 'rejected'");
    const request = requestWithBindings(`/friends/${f.id}/respond`,f.receiver.token,paused.bindings);
    await paused.reached;
    try { expect((await post(`/friends/${f.id}/respond`,f.receiver.token,{accept:true})).status).toBe(200); }
    finally {paused.resume();}
    expect((await request).status).toBe(409);
    expect((await env.DB.prepare('SELECT status FROM friend_requests WHERE id=?').bind(f.id).first<{status:string}>())?.status).toBe('accepted');
    await target.flush(); expect(target.events.map(e=>JSON.parse(e).type)).toEqual(['friend:accepted']); target.socket.close(1000,'finished');
  });
});

describe('friend request creation concurrency', () => {
  it('does not duplicate or re-announce concurrent same-direction requests', async () => {
    const sender = await mediaAccount(), receiver = await mediaAccount(), target = await inbox(receiver);
    const paused = pauseWrite('INSERT INTO friend_requests (id, sender_id');
    const stale = app.request('https://example.test/friends/request',{
      method:'POST',headers:{Authorization:`Bearer ${sender.token}`,'Content-Type':'application/json'},body:JSON.stringify({receiver_id:receiver.id}),
    }, paused.bindings);
    await paused.reached;
    try { expect((await post('/friends/request',sender.token,{receiver_id:receiver.id})).status).toBe(201); }
    finally {paused.resume();}
    expect((await stale).status).toBe(409);
    const rows = await env.DB.prepare('SELECT id FROM friend_requests WHERE sender_id=? AND receiver_id=?').bind(sender.id,receiver.id).all();
    expect(rows.results).toHaveLength(1);
    await target.flush(); expect(target.events.filter(e=>JSON.parse(e).type === 'friend:request')).toHaveLength(1); target.socket.close(1000,'finished');
  });
  it('does not reopen a request that was accepted after the initial check', async () => {
    const sender = await mediaAccount(), receiver = await mediaAccount(), target = await inbox(receiver);
    const paused = pauseWrite('INSERT INTO friend_requests (id, sender_id');
    const stale = app.request('https://example.test/friends/request',{
      method:'POST',headers:{Authorization:`Bearer ${sender.token}`,'Content-Type':'application/json'},body:JSON.stringify({receiver_id:receiver.id}),
    }, paused.bindings);
    await paused.reached;
    try {
      const first = await post('/friends/request',sender.token,{receiver_id:receiver.id});
      expect(first.status).toBe(201);
      const {id} = await first.json() as {id:string};
      expect((await post(`/friends/${id}/respond`,receiver.token,{accept:true})).status).toBe(200);
    } finally {paused.resume();}
    expect((await stale).status).toBe(409);
    expect((await env.DB.prepare('SELECT status FROM friend_requests WHERE sender_id=? AND receiver_id=?').bind(sender.id,receiver.id).first<{status:string}>())?.status).toBe('accepted');
    await target.flush(); expect(target.events.filter(e=>JSON.parse(e).type === 'friend:request')).toHaveLength(1); target.socket.close(1000,'finished');
  });
  it('does not create opposite pending requests when both parties initiate concurrently', async () => {
    const a = await mediaAccount(), b = await mediaAccount(), target = await inbox(b);
    const paused = pauseWrite('INSERT INTO friend_requests (id, sender_id');
    const stale = app.request('https://example.test/friends/request',{
      method:'POST',headers:{Authorization:`Bearer ${a.token}`,'Content-Type':'application/json'},body:JSON.stringify({receiver_id:b.id}),
    }, paused.bindings);
    await paused.reached;
    try { expect((await post('/friends/request',b.token,{receiver_id:a.id})).status).toBe(201); }
    finally {paused.resume();}
    expect((await stale).status).toBe(409);
    const rows = await env.DB.prepare("SELECT sender_id,receiver_id FROM friend_requests WHERE status='pending' AND ((sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?))").bind(a.id,b.id,b.id,a.id).all();
    expect(rows.results).toEqual([{sender_id:b.id,receiver_id:a.id}]);
    await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
  });
});

describe('direct message authorization under concurrency', () => {
  for (const withMedia of [false, true]) for (const action of ['block','unfriend'] as const) {
    it(`does not write or broadcast a ${withMedia ? 'media' : 'text'} message after ${action}`, async () => {
      const sender = await mediaAccount(), receiver = await mediaAccount();
      await friend(sender.id,receiver.id);
      const file = withMedia ? await mediaUpload(sender.token) : undefined;
      const target = await inbox(receiver);
      let reached!:()=>void, resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {
        prepare(sql:string) {
          const statement = env.DB.prepare(sql);
          if (withMedia || !sql.includes('INSERT INTO messages (id, sender_id')) return statement;
          return {bind(...args:unknown[]) {const bound = statement.bind(...args); return {async run(){reached(); await gate; return bound.run();}};}};
        },
        async batch(statements:D1PreparedStatement[]) { if (withMedia) {reached(); await gate;} return env.DB.batch(statements); },
      };
      const request = app.request('https://example.test/messages',{
        method:'POST',headers:{Authorization:`Bearer ${sender.token}`,'Content-Type':'application/json'},
        body:JSON.stringify({receiver_id:receiver.id,content:'late message',...(file ? {media_url:file.url} : {})}),
      },{...env,DB:db} as unknown as Env);
      await ready;
      try {
        if (action === 'block') expect((await post('/friends/block',receiver.token,{user_id:sender.id})).status).toBe(200);
        else expect((await app.request(`https://example.test/friends/${sender.id}`,{method:'DELETE',headers:{Authorization:`Bearer ${receiver.token}`}},env)).status).toBe(200);
      } finally {resume();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM messages WHERE sender_id=? AND receiver_id=?').bind(sender.id,receiver.id).first()).toBeNull();
      if (file) expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE kind='message' AND file_path=?").bind(file.key).first()).toBeNull();
      await target.flush(); expect(target.events.filter(e=>JSON.parse(e).type === 'message:new')).toHaveLength(0); target.socket.close(1000,'finished');
    });
  }
});

describe('direct message actions after blocking or unfriend', () => {
  const actions = [
    {name:'edit',method:'PATCH',suffix:'',body:{content:'changed'},sql:'UPDATE messages SET content ='},
    {name:'delete-for-everyone',method:'DELETE',suffix:'',body:{},sql:'UPDATE messages SET is_deleted ='},
    {name:'reaction',method:'POST',suffix:'/reaction',body:{emoji:'👍'},sql:'UPDATE messages SET reactions ='},
  ];
  for (const action of actions) for (const race of [false,true]) {
    it(`refuses ${action.name} ${race ? 'when unfriended before the write' : 'after a block'}`, async () => {
      const sender = await mediaAccount(), receiver = await mediaAccount();
      await friend(sender.id,receiver.id);
      const sent = await post('/messages',sender.token,{receiver_id:receiver.id,content:'original'});
      expect(sent.status).toBe(201);
      const id = (await sent.json() as {message:{id:string}}).message.id;
      const actor = action.name === 'reaction' ? receiver : sender;
      const other = action.name === 'reaction' ? sender : receiver;
      const target = await inbox(other);
      const makeRequest = (bindings:Env) => app.request(`https://example.test/messages/${id}${action.suffix}`,{
        method:action.method,headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify(action.body),
      },bindings);
      let response:Response;
      if (race) {
        const paused = pauseWrite(action.sql), request = makeRequest(paused.bindings);
        await paused.reached;
        try { expect((await app.request(`https://example.test/friends/${actor.id}`,{method:'DELETE',headers:{Authorization:`Bearer ${other.token}`}},env)).status).toBe(200); }
        finally {paused.resume();}
        response = await request;
      } else {
        expect((await post('/friends/block',other.token,{user_id:actor.id})).status).toBe(200);
        response = await makeRequest(env);
      }
      expect(response.status).toBe(403);
      const row = await env.DB.prepare('SELECT content,is_deleted,reactions FROM messages WHERE id=?').bind(id).first<{content:string;is_deleted:number;reactions:string|null}>();
      expect(row).toEqual({content:'original',is_deleted:0,reactions:null});
      await target.flush();
      expect(target.events.filter(e=>['message:edit','message:delete','message:reaction'].includes(JSON.parse(e).type))).toHaveLength(0);
      target.socket.close(1000,'finished');
    });
  }
  it('allows friends to edit, react and delete their own message', async () => {
    const sender = await mediaAccount(), receiver = await mediaAccount(); await friend(sender.id,receiver.id);
    const sent = await post('/messages',sender.token,{receiver_id:receiver.id,content:'original'});
    const id = (await sent.json() as {message:{id:string}}).message.id;
    const target = await inbox(receiver);
    const edited = await app.request(`https://example.test/messages/${id}`, {method:'PATCH',headers:{Authorization:`Bearer ${sender.token}`,'Content-Type':'application/json'},body:JSON.stringify({content:'edited'})},env);
    expect(edited.status).toBe(200);
    expect((await post(`/messages/${id}/reaction`,receiver.token,{emoji:'👍'})).status).toBe(200);
    const deleted = await app.request(`https://example.test/messages/${id}`,{method:'DELETE',headers:{Authorization:`Bearer ${sender.token}`}},env);
    expect(deleted.status).toBe(200);
    await target.flush(); expect(target.events.map(e=>JSON.parse(e).type)).toEqual(['message:edit','message:reaction','message:delete']);
    target.socket.close(1000,'finished');
  });
  it('still allows a participant to hide a message locally after a block', async () => {
    const sender = await mediaAccount(), receiver = await mediaAccount(); await friend(sender.id,receiver.id);
    const sent = await post('/messages',sender.token,{receiver_id:receiver.id,content:'original'});
    const id = (await sent.json() as {message:{id:string}}).message.id;
    await post('/friends/block',sender.token,{user_id:receiver.id});
    expect((await post(`/messages/${id}/delete-for-me`,receiver.token)).status).toBe(200);
    const row = await env.DB.prepare('SELECT deleted_for FROM messages WHERE id=?').bind(id).first<{deleted_for:string}>();
    expect(JSON.parse(row!.deleted_for)).toContain(receiver.id);
  });
});

describe('safe media response types and filenames', () => {
  async function uploadNamed(token:string, name:string, mime:string) {
    const form = new FormData();
    form.set('file',new File(['0123456789'],name,{type:mime}));
    const response = await SELF.fetch('https://example.test/media/upload',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:form});
    expect(response.status).toBe(201);
    return response.json() as Promise<{key:string;url:string}>;
  }
  it('forces active and unknown content to download without browser sniffing', async () => {
    const owner = await mediaAccount();
    for (const type of ['text/html','image/svg+xml','application/pdf','application/octet-stream']) {
      const file = await uploadNamed(owner.token,'file.bin',type);
      const response = await mediaRead(file.key,owner.token);
      expect(response.status,type).toBe(200);
      expect(response.headers.get('content-type'),type).toBe('application/octet-stream');
      expect(response.headers.get('content-disposition'),type).toBe('attachment');
      expect(response.headers.get('x-content-type-options'),type).toBe('nosniff');
      expect(await response.text()).toBe('0123456789');
    }
  });
  it('keeps safe media playable and preserves byte ranges', async () => {
    const owner = await mediaAccount();
    for (const type of ['image/jpeg','image/png','audio/mp4','audio/ogg','video/mp4']) {
      const file = await uploadNamed(owner.token,'file.ext',type);
      const response = await SELF.fetch(`https://example.test/media/file/${file.key}`,{headers:{Authorization:`Bearer ${owner.token}`,Range:'bytes=2-5'}});
      expect(response.status,type).toBe(206);
      expect(response.headers.get('content-type'),type).toBe(type);
      expect(response.headers.get('content-range'),type).toBe('bytes 2-5/10');
      expect(response.headers.get('x-content-type-options'),type).toBe('nosniff');
      expect(await response.text()).toBe('2345');
    }
  });
  it('generates usable keys for URL-special and excessively long extensions', async () => {
    const owner = await mediaAccount();
    for (const name of ['photo.j?pg','photo.a#b','photo.bad%xx','photo.abcdefghijklmnopq']) {
      const file = await uploadNamed(owner.token,name,'image/jpeg');
      expect(file.key.split('/').at(-1)).toMatch(/^[0-9a-f-]+$/);
      const response = await SELF.fetch(file.url.replace('https://nchat-friends.usa66486.workers.dev','https://example.test'),{headers:{Authorization:`Bearer ${owner.token}`}});
      expect(response.status,name).toBe(200);
    }
  });
});

describe('status view authorization under concurrency', () => {
  for (const change of ['block','unfriend','privacy','expired','deleted'] as const) {
    it(`does not record or announce a view after the status becomes ${change}`, async () => {
      const owner = await mediaAccount(), viewer = await mediaAccount(); await friend(owner.id,viewer.id);
      const created = await post('/status',owner.token,{content_type:'text',text_content:'private update',privacy:'contacts'});
      expect(created.status).toBe(201);
      const id = (await created.json() as {status:{id:string}}).status.id;
      const target = await inbox(owner), paused = pauseBatch();
      const request = requestWithBindings(`/status/${id}/view`,viewer.token,paused.bindings);
      await paused.reached;
      try {
        if (change === 'block') await env.DB.prepare('INSERT INTO blocked_users (id,user_id,blocked_user_id) VALUES (?,?,?)').bind(crypto.randomUUID(),owner.id,viewer.id).run();
        if (change === 'unfriend') await env.DB.prepare('DELETE FROM friendships WHERE user1_id=? AND user2_id=?').bind(owner.id,viewer.id).run();
        if (change === 'privacy') await env.DB.prepare("INSERT INTO privacy_settings (user_id,status_visibility) VALUES (?,'nobody') ON CONFLICT(user_id) DO UPDATE SET status_visibility='nobody'").bind(owner.id).run();
        if (change === 'expired') await env.DB.prepare("UPDATE statuses SET expires_at='2000-01-01' WHERE id=?").bind(id).run();
        if (change === 'deleted') await env.DB.prepare('DELETE FROM statuses WHERE id=?').bind(id).run();
      } finally {paused.resume();}
      expect((await request).status).toBe(404);
      expect(await env.DB.prepare('SELECT 1 FROM status_views WHERE status_id=? AND viewer_id=?').bind(id,viewer.id).first()).toBeNull();
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
    });
  }
  it('permits a nonfriend only when both status audiences are everyone', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount();
    await env.DB.prepare("UPDATE privacy_settings SET status_visibility='everyone' WHERE user_id=?").bind(owner.id).run();
    const open = await seedStatus(owner.id,'everyone');
    const contacts = await seedStatus(owner.id,'contacts');
    expect((await post(`/status/${open}/view`,viewer.token)).status).toBe(200);
    expect((await post(`/status/${contacts}/view`,viewer.token)).status).toBe(404);
    await env.DB.prepare("UPDATE privacy_settings SET status_visibility='contacts' WHERE user_id=?").bind(owner.id).run();
    expect((await post(`/status/${open}/view`,viewer.token)).status).toBe(404);
  });
  it('records and announces an authorized view only once', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount(); await friend(owner.id,viewer.id);
    const created = await post('/status',owner.token,{content_type:'text',text_content:'hello',privacy:'contacts'});
    const id = (await created.json() as {status:{id:string}}).status.id;
    const target = await inbox(owner);
    expect((await post(`/status/${id}/view`,viewer.token)).status).toBe(200);
    expect((await post(`/status/${id}/view`,viewer.token)).status).toBe(200);
    const views = await env.DB.prepare('SELECT id FROM status_views WHERE status_id=? AND viewer_id=?').bind(id,viewer.id).all();
    expect(views.results).toHaveLength(1);
    await target.flush(); expect(target.events.map(e=>JSON.parse(e).type)).toEqual(['status:view']); target.socket.close(1000,'finished');
  });
});

describe('concurrent privacy settings updates', () => {
  for (const existingRow of [true,false]) {
    it(`preserves independent privacy changes when a settings row ${existingRow ? 'exists' : 'is absent'}`, async () => {
      const owner = await mediaAccount(), viewer = await mediaAccount(); await friend(owner.id,viewer.id);
      if (!existingRow) await env.DB.prepare('DELETE FROM privacy_settings WHERE user_id=?').bind(owner.id).run();
      const status = await seedStatus(owner.id);
      const paused = pauseWrite('INSERT INTO privacy_settings');
      const request = app.request('https://example.test/privacy',{
        method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({about_visibility:'nobody'}),
      },paused.bindings);
      await paused.reached;
      try {
        const change = await SELF.fetch('https://example.test/privacy',{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({status_visibility:'nobody'})});
        expect(change.status).toBe(200);
      } finally {paused.resume();}
      expect((await request).status).toBe(200);
      const current = await env.DB.prepare('SELECT about_visibility,status_visibility FROM privacy_settings WHERE user_id=?').bind(owner.id).first<{about_visibility:string;status_visibility:string}>();
      expect(current).toEqual({about_visibility:'nobody',status_visibility:'nobody'});
      expect(await statusFeed(viewer.token)).not.toContain(status);
    });
  }
});

describe('direct read receipt authorization under concurrency', () => {
  for (const change of ['block','unfriend'] as const) {
    it(`does not record or announce read receipts after ${change}`, async () => {
      const sender = await mediaAccount(), receiver = await mediaAccount(); await friend(sender.id,receiver.id);
      const sent = await post('/messages',sender.token,{receiver_id:receiver.id,content:'unread'});
      const id = (await sent.json() as {message:{id:string}}).message.id;
      const target = await inbox(sender), paused = pauseWrite('UPDATE messages SET is_read = 1');
      const request = app.request('https://example.test/messages/mark-read',{
        method:'POST',headers:{Authorization:`Bearer ${receiver.token}`,'Content-Type':'application/json'},body:JSON.stringify({sender_id:sender.id}),
      },paused.bindings);
      await paused.reached;
      try {
        if (change === 'block') await post('/friends/block',sender.token,{user_id:receiver.id});
        else await app.request(`https://example.test/friends/${receiver.id}`,{method:'DELETE',headers:{Authorization:`Bearer ${sender.token}`}},env);
      } finally {paused.resume();}
      expect((await request).status).toBe(403);
      expect((await env.DB.prepare('SELECT is_read FROM messages WHERE id=?').bind(id).first<{is_read:number}>())?.is_read).toBe(0);
      await target.flush(); expect(target.events.filter(e=>JSON.parse(e).type === 'message:read')).toHaveLength(0); target.socket.close(1000,'finished');
    });
  }
  it('marks and announces an authorized unread message once', async () => {
    const sender = await mediaAccount(), receiver = await mediaAccount(); await friend(sender.id,receiver.id);
    const sent = await post('/messages',sender.token,{receiver_id:receiver.id,content:'hello'});
    const id = (await sent.json() as {message:{id:string}}).message.id;
    const target = await inbox(sender);
    expect((await post('/messages/mark-read',receiver.token,{sender_id:sender.id})).status).toBe(200);
    expect((await post('/messages/mark-read',receiver.token,{sender_id:sender.id})).status).toBe(200);
    expect((await env.DB.prepare('SELECT is_read FROM messages WHERE id=?').bind(id).first<{is_read:number}>())?.is_read).toBe(1);
    await target.flush(); const events = target.events.map(e=>JSON.parse(e));
    expect(events).toHaveLength(1); expect(events[0]).toMatchObject({type:'message:read',reader_id:receiver.id,message_ids:[id]}); target.socket.close(1000,'finished');
  },30000);
});

describe('nonexistent target users are refused cleanly', () => {
  it('does not create a friend request for an account that does not exist', async () => {
    const actor = await mediaAccount(), missing = crypto.randomUUID();
    expect((await post('/friends/request', actor.token, {receiver_id:missing})).status).toBe(404);
    expect(await env.DB.prepare('SELECT 1 FROM friend_requests WHERE sender_id=? OR receiver_id=?').bind(actor.id, missing).first()).toBeNull();
  });
  it('does not create a block for an account that does not exist', async () => {
    const actor = await mediaAccount(), missing = crypto.randomUUID();
    expect((await post('/friends/block', actor.token, {user_id:missing})).status).toBe(404);
    expect(await env.DB.prepare('SELECT 1 FROM blocked_users WHERE user_id=? AND blocked_user_id=?').bind(actor.id, missing).first()).toBeNull();
  });
  it('does not create a call for an account that does not exist', async () => {
    const actor = await mediaAccount(), missing = crypto.randomUUID();
    expect((await post('/calls', actor.token, {receiver_id:missing,call_type:'audio'})).status).toBe(404);
    expect(await env.DB.prepare('SELECT 1 FROM calls WHERE caller_id=? AND receiver_id=?').bind(actor.id, missing).first()).toBeNull();
  });
  it('does not add a group member whose account does not exist', async () => {
    const {owner,group} = await fixture(), missing = crypto.randomUUID();
    expect((await post(`/groups/${group}/members`, owner.token, {memberIds:[missing]})).status).toBe(404);
    expect(await env.DB.prepare('SELECT 1 FROM group_members WHERE group_id=? AND user_id=?').bind(group, missing).first()).toBeNull();
  });
});

describe('group creation atomicity', () => {
  it('does not leave an orphan group when a member does not exist', async () => {
    const owner = await mediaAccount(); const missing = crypto.randomUUID();
    const result = await post('/groups',owner.token,{name:'invalid membership',memberIds:[missing]});
    expect(result.status).toBe(404);
    expect(await env.DB.prepare('SELECT 1 FROM groups WHERE created_by=?').bind(owner.id).first()).toBeNull();
  });
  it('rolls back the group if a member disappears before the batch', async () => {
    const owner = await mediaAccount(), member = await mediaAccount();
    let reached!:()=>void, resume!:()=>void;
    const paused = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
    const db = {prepare:env.DB.prepare.bind(env.DB), async batch(statements:D1PreparedStatement[]) {reached(); await gate; return env.DB.batch(statements);}};
    const request = app.request('https://example.test/groups',{
      method:'POST',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({name:'disappearing member',memberIds:[member.id]}),
    },{...env,DB:db} as unknown as Env);
    await paused;
    try { await env.DB.prepare('DELETE FROM users WHERE id=?').bind(member.id).run(); }
    finally {resume();}
    expect((await request).status).toBeGreaterThanOrEqual(400);
    expect(await env.DB.prepare('SELECT 1 FROM groups WHERE created_by=?').bind(owner.id).first()).toBeNull();
  });
});

describe('group member invitation budgets', () => {
  async function nearlyExhausted(ownerId:string) {
    const reservations = await Promise.all(Array.from({length:255},()=>consumeLimit(env,'group-members',ownerId,256,300000)));
    expect(reservations.every(Boolean)).toBe(true);
  }
  it('charges distinct invited accounts on group creation without partially charging a rejected batch', async () => {
    const owner = await mediaAccount(), a = await mediaAccount(), b = await mediaAccount();
    await nearlyExhausted(owner.id);
    expect((await post('/groups',owner.token,{name:'too many',memberIds:[a.id,b.id]})).status).toBe(429);
    expect(await env.DB.prepare('SELECT 1 FROM groups WHERE created_by=?').bind(owner.id).first()).toBeNull();
    const allowed = await post('/groups',owner.token,{name:'last invite',memberIds:[a.id,a.id,owner.id]});
    expect(allowed.status).toBe(201);
    const {group} = await allowed.json() as {group:{id:string}};
    expect((await post(`/groups/${group.id}/members`,owner.token,{memberIds:[b.id]})).status).toBe(429);
    expect(await env.DB.prepare('SELECT 1 FROM group_members WHERE group_id=? AND user_id=?').bind(group.id,b.id).first()).toBeNull();
  },30000);
  it('reserves weighted budgets atomically across concurrent calls', async () => {
    const account = await mediaAccount();
    const prior = await Promise.all(Array.from({length:253},()=>consumeLimit(env,'group-members',account.id,256,300000)));
    expect(prior.every(Boolean)).toBe(true);
    const competing = await Promise.all([
      consumeLimit(env,'group-members',account.id,256,300000,2),
      consumeLimit(env,'group-members',account.id,256,300000,2),
    ]);
    expect(competing.filter(Boolean)).toHaveLength(1);
    expect(await consumeLimit(env,'group-members',account.id,256,300000,1)).toBe(true);
    expect(await consumeLimit(env,'group-members',account.id,256,300000,1)).toBe(false);
  },30000);
  it('shares the member budget across additions and creation', async () => {
    const {owner,group} = await fixture(); const a = await mediaAccount(), b = await mediaAccount();
    await nearlyExhausted(owner.id);
    expect((await post(`/groups/${group}/members`,owner.token,{memberIds:[a.id,b.id]})).status).toBe(429);
    expect(await env.DB.prepare('SELECT 1 FROM group_members WHERE group_id=? AND user_id=?').bind(group,a.id).first()).toBeNull();
    expect((await post(`/groups/${group}/members`,owner.token,{memberIds:[a.id]})).status).toBe(200);
    expect((await post('/groups',owner.token,{name:'exhausted',memberIds:[b.id]})).status).toBe(429);
    expect((await post('/groups',owner.token,{name:'empty',memberIds:[]})).status).toBe(201);
  },30000);
});

describe('direct media forwarding authorization at write time', () => {
  for (const source of ['group','message','status'] as const) {
    it(`does not persist or grant a forward after ${source} access is revoked`, async () => {
      const owner = await mediaAccount(), actor = await mediaAccount(), receiver = await mediaAccount();
      await friend(actor.id,receiver.id);
      const file = await mediaUpload(owner.token);
      let sourceId = '';
      if (source === 'group') {
        sourceId = crypto.randomUUID();
        await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(sourceId,'source',owner.id).run();
        for (const user of [owner,actor]) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),sourceId,user.id,user.id===owner.id?'admin':'member').run();
        await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),sourceId,owner.id,file.url).run();
      } else if (source === 'message') {
        await friend(owner.id,actor.id);
        const sent = await post('/messages',owner.token,{receiver_id:actor.id,media_url:file.url,type:'image'});
        expect(sent.status).toBe(201); sourceId = (await sent.json() as {message:{id:string}}).message.id;
      } else {
        await friend(owner.id,actor.id);
        const created = await post('/status',owner.token,{content_type:'image',content_url:file.url,privacy:'contacts'});
        expect(created.status).toBe(201); sourceId = (await created.json() as {status:{id:string}}).status.id;
      }
      expect((await mediaRead(file.key,actor.token)).status).toBe(200);
      const target = await inbox(receiver);
      let reached!:()=>void, resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {prepare:env.DB.prepare.bind(env.DB), async batch(statements:D1PreparedStatement[]) {reached(); await gate; return env.DB.batch(statements);}};
      const request = app.request('https://example.test/messages',{
        method:'POST',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify({receiver_id:receiver.id,type:'image',media_url:file.url}),
      },{...env,DB:db} as unknown as Env);
      await ready;
      try {
        if (source === 'group') await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(sourceId,actor.id).run();
        if (source === 'message') await env.DB.prepare('UPDATE messages SET is_deleted=1 WHERE id=?').bind(sourceId).run();
        if (source === 'status') await env.DB.prepare("UPDATE privacy_settings SET status_visibility='nobody' WHERE user_id=?").bind(owner.id).run();
      } finally {resume();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM messages WHERE sender_id=? AND receiver_id=?').bind(actor.id,receiver.id).first()).toBeNull();
      expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE kind='message' AND file_path=? AND actor_id=?").bind(file.key,actor.id).first()).toBeNull();
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
      expect((await mediaRead(file.key,receiver.token)).status).toBe(404);
    }, 30000);
  }
});

describe('direct media forwarding proof coverage', () => {
  it('accepts a different exact encoded URL spelling when the source is still authorized', async () => {
    const owner = await mediaAccount(), actor = await mediaAccount(), receiver = await mediaAccount();
    await friend(actor.id,receiver.id);
    const file = await mediaUpload(owner.token);
    const encoded = `https://example.test/media/file/${encodeURIComponent(file.key)}`;
    await env.DB.prepare('INSERT INTO messages (id,sender_id,receiver_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),owner.id,actor.id,encoded).run();
    const sent = await post('/messages',actor.token,{receiver_id:receiver.id,type:'image',media_url:file.url});
    expect(sent.status).toBe(201);
    expect((await mediaRead(file.key,receiver.token)).status).toBe(200);
  }, 30000);
  for (const source of ['group-message','status','user-avatar','group-avatar'] as const) {
    it(`allows an authorized trusted ${source} to be forwarded`, async () => {
      const uploader = await mediaAccount(), owner = await mediaAccount(), actor = await mediaAccount(), receiver = await mediaAccount();
      await friend(actor.id,receiver.id);
      const file = await mediaUpload(uploader.token), reference = crypto.randomUUID();
      if (source === 'group-message' || source === 'group-avatar') {
        const group = source === 'group-avatar' ? reference : crypto.randomUUID();
        await env.DB.prepare('INSERT INTO groups (id,name,created_by,avatar_url) VALUES (?,?,?,?)').bind(group,'source',owner.id,source === 'group-avatar' ? file.url : null).run();
        await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,actor.id,'member').run();
        if (source === 'group-message') await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,media_url) VALUES (?,?,?,?)').bind(reference,group,owner.id,file.url).run();
      } else if (source === 'status') {
        await friend(owner.id,actor.id);
        await env.DB.prepare("INSERT INTO statuses (id,user_id,content_type,content_url,privacy,expires_at) VALUES (?,?,'image',?,'contacts',?)").bind(reference,owner.id,file.url,new Date(Date.now()+3600000).toISOString()).run();
      } else {
        await env.DB.prepare('UPDATE users SET avatar_url=? WHERE id=?').bind(file.url,owner.id).run();
      }
      const kind = source === 'group-message' ? 'group_message' : source === 'group-avatar' ? 'group_avatar' : source === 'user-avatar' ? 'user_avatar' : 'status';
      await env.DB.prepare('INSERT INTO trusted_media_references (file_path,kind,reference_id,actor_id) VALUES (?,?,?,?)').bind(file.key,kind,source === 'user-avatar' ? owner.id : reference,owner.id).run();
      expect((await post('/messages',actor.token,{receiver_id:receiver.id,media_url:file.url})).status).toBe(201);
      expect((await mediaRead(file.key,receiver.token)).status).toBe(200);
    }, 30000);
  }
  for (const source of ['user-avatar','group-avatar'] as const) {
    it(`does not grant a forward when ${source} access is revoked`, async () => {
      const owner = await mediaAccount(), actor = await mediaAccount(), receiver = await mediaAccount();
      await friend(actor.id,receiver.id); const file = await mediaUpload(owner.token);
      let group = '';
      if (source === 'user-avatar') {
        const res = await SELF.fetch('https://example.test/users/me',{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:file.url})});
        expect(res.status).toBe(200);
      } else {
        const res = await post('/groups',owner.token,{name:'source',avatar_url:file.url,memberIds:[actor.id]});
        expect(res.status).toBe(201); group = (await res.json() as {group:{id:string}}).group.id;
      }
      expect((await mediaRead(file.key,actor.token)).status).toBe(200);
      const target = await inbox(receiver);
      let reached!:()=>void,resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {prepare:env.DB.prepare.bind(env.DB),async batch(statements:D1PreparedStatement[]){reached(); await gate; return env.DB.batch(statements);}};
      const request = app.request('https://example.test/messages',{method:'POST',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify({receiver_id:receiver.id,media_url:file.url})},{...env,DB:db} as unknown as Env);
      await ready;
      try {
        if (source === 'user-avatar') await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility='nobody' WHERE user_id=?").bind(owner.id).run();
        else await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(group,actor.id).run();
      } finally {resume();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM messages WHERE sender_id=? AND receiver_id=?').bind(actor.id,receiver.id).first()).toBeNull();
      expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE kind='message' AND file_path=? AND actor_id=?").bind(file.key,actor.id).first()).toBeNull();
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
      expect((await mediaRead(file.key,receiver.token)).status).toBe(404);
    }, 30000);
  }
});

describe('other media writes after source access is revoked', () => {
  for (const route of ['group-message','status','profile-avatar','group-avatar','group-create'] as const) {
    it(`rejects ${route} without changing rows, grants or events`, async () => {
      const owner = await mediaAccount(), actor = await mediaAccount(), recipient = await mediaAccount();
      await friend(actor.id,recipient.id);
      const source = crypto.randomUUID(), destination = crypto.randomUUID();
      const file = await mediaUpload(owner.token);
      await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(source,'source',owner.id).run();
      await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),source,actor.id,'member').run();
      await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),source,owner.id,file.url).run();
      await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(destination,'destination',actor.id).run();
      for (const user of [actor,recipient]) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),destination,user.id,user.id === actor.id ? 'admin' : 'member').run();
      if (route === 'profile-avatar' || route === 'group-avatar') {
        const old = await mediaUpload(actor.token);
        const url = route === 'profile-avatar' ? '/users/me' : `/groups/${destination}`;
        const response = await SELF.fetch(`https://example.test${url}`,{method:'PATCH',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:old.url})});
        expect(response.status).toBe(200);
        (actor as typeof actor & {oldKey:string;oldUrl:string}).oldKey = old.key;
        (actor as typeof actor & {oldKey:string;oldUrl:string}).oldUrl = old.url;
      }
      expect((await mediaRead(file.key,actor.token)).status).toBe(200);
      const target = await inbox(recipient);
      let reached!:()=>void,resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {prepare:env.DB.prepare.bind(env.DB),async batch(statements:D1PreparedStatement[]){reached(); await gate; return env.DB.batch(statements);}};
      const config = route === 'group-message' ? {path:`/groups/${destination}/messages`,method:'POST',body:{media_url:file.url}} :
        route === 'status' ? {path:'/status',method:'POST',body:{content_type:'image',content_url:file.url,privacy:'contacts'}} :
        route === 'profile-avatar' ? {path:'/users/me',method:'PATCH',body:{avatar_url:file.url}} :
        route === 'group-avatar' ? {path:`/groups/${destination}`,method:'PATCH',body:{avatar_url:file.url}} :
        {path:'/groups',method:'POST',body:{name:'blocked creation',avatar_url:file.url,memberIds:[recipient.id]}};
      const request = app.request(`https://example.test${config.path}`,{method:config.method,headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify(config.body)},{...env,DB:db} as unknown as Env);
      await ready;
      try { await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(source,actor.id).run(); }
      finally {resume();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM group_messages WHERE group_id=? AND sender_id=?').bind(destination,actor.id).first()).toBeNull();
      expect(await env.DB.prepare('SELECT 1 FROM statuses WHERE user_id=?').bind(actor.id).first()).toBeNull();
      expect(await env.DB.prepare("SELECT 1 FROM groups WHERE created_by=? AND name='blocked creation'").bind(actor.id).first()).toBeNull();
      expect(await env.DB.prepare('SELECT 1 FROM trusted_media_references WHERE file_path=? AND actor_id=?').bind(file.key,actor.id).first()).toBeNull();
      if (route === 'profile-avatar' || route === 'group-avatar') {
        const old = actor as typeof actor & {oldKey:string;oldUrl:string};
        const row = route === 'profile-avatar' ? await env.DB.prepare('SELECT avatar_url FROM users WHERE id=?').bind(actor.id).first<{avatar_url:string}>() : await env.DB.prepare('SELECT avatar_url FROM groups WHERE id=?').bind(destination).first<{avatar_url:string}>();
        expect(row?.avatar_url).toBe(old.oldUrl);
        const kind = route === 'profile-avatar' ? 'user_avatar' : 'group_avatar';
        expect(await env.DB.prepare('SELECT 1 FROM trusted_media_references WHERE file_path=? AND kind=? AND reference_id=?').bind(old.oldKey,kind,route === 'profile-avatar' ? actor.id : destination).first()).not.toBeNull();
      }
      await target.flush(); expect(target.events).toHaveLength(0); target.socket.close(1000,'finished');
      expect((await mediaRead(file.key,recipient.token)).status).toBe(404);
    }, 30000);
  }
});

describe('avatar provenance when reusing the current avatar', () => {
  for (const kind of ['profile','group'] as const) for (const encoded of [false,true]) {
    it(`preserves the trusted ${kind} avatar when its previous source is gone (encoded: ${encoded})`, async () => {
      const uploader = await mediaAccount(), owner = await mediaAccount(), viewer = await mediaAccount();
      await friend(uploader.id,owner.id);
      const file = await mediaUpload(uploader.token);
      const initial = await post('/messages',uploader.token,{receiver_id:owner.id,media_url:file.url});
      const id = (await initial.json() as {message:{id:string}}).message.id;
      let path = '/users/me', reference = owner.id;
      if (kind === 'group') {
        const created = await post('/groups',owner.token,{name:'group',avatar_url:file.url,memberIds:[viewer.id]});
        expect(created.status).toBe(201); reference = (await created.json() as {group:{id:string}}).group.id;
        path = `/groups/${reference}`;
      } else {
        const set = await SELF.fetch(`https://example.test${path}`,{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:file.url})});
        expect(set.status).toBe(200);
      }
      await env.DB.prepare('UPDATE messages SET is_deleted=1 WHERE id=?').bind(id).run();
      expect((await mediaRead(file.key,owner.token)).status).toBe(200);
      const nextUrl = encoded ? `https://example.test/media/file/${encodeURIComponent(file.key)}` : file.url;
      const repeat = await SELF.fetch(`https://example.test${path}`,{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:nextUrl})});
      expect(repeat.status).toBe(200);
      const updated = kind === 'profile' ? await env.DB.prepare('SELECT avatar_url FROM users WHERE id=?').bind(reference).first<{avatar_url:string}>() : await env.DB.prepare('SELECT avatar_url FROM groups WHERE id=?').bind(reference).first<{avatar_url:string}>();
      expect(updated?.avatar_url).toBe(nextUrl);
      expect(await env.DB.prepare('SELECT 1 FROM trusted_media_references WHERE file_path=? AND kind=? AND reference_id=?').bind(file.key,kind === 'profile' ? 'user_avatar' : 'group_avatar',reference).first()).not.toBeNull();
      expect((await mediaRead(file.key,viewer.token)).status).toBe(200);
    }, 30000);
  }
});

describe('concurrent group metadata and avatar writes', () => {
  it('a name-only update does not revert a concurrently replaced avatar or its grants', async () => {
    const {owner, outsider, group} = await fixture();
    await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,outsider.id,'member').run();
    const old = await mediaUpload(owner.token), replacement = await mediaUpload(owner.token);
    const patch = (body: object) => SELF.fetch(`https://example.test/groups/${group}`, {method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify(body)});
    expect((await patch({avatar_url:old.url})).status).toBe(200);
    const paused = pauseWrite('UPDATE groups SET name = CASE');
    const request = app.request(`https://example.test/groups/${group}`,{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({name:'Renamed'})},paused.bindings);
    await paused.reached;
    try { expect((await patch({avatar_url:replacement.url})).status).toBe(200); }
    finally { paused.resume(); }
    expect((await request).status).toBe(200);
    const row = await env.DB.prepare('SELECT name,avatar_url FROM groups WHERE id=?').bind(group).first<{name:string;avatar_url:string}>();
    expect(row).toEqual({name:'Renamed',avatar_url:replacement.url});
    expect((await mediaRead(replacement.key,outsider.token)).status).toBe(200);
    expect(await env.DB.prepare("SELECT file_path FROM trusted_media_references WHERE kind='group_avatar' AND reference_id=?").bind(group).all<{file_path:string}>()).toMatchObject({results:[{file_path:replacement.key}]});
  },30000);
});

describe('private profile avatar owner access', () => {
  it('allows the owner to read and reuse a trusted avatar even when the profile photo is hidden from everyone else', async () => {
    const uploader = await mediaAccount(), owner = await mediaAccount(), viewer = await mediaAccount();
    await friend(uploader.id,owner.id);
    const file = await mediaUpload(uploader.token);
    const initial = await post('/messages',uploader.token,{receiver_id:owner.id,media_url:file.url});
    expect(initial.status).toBe(201);
    const id = (await initial.json() as {message:{id:string}}).message.id;
    const patch = () => SELF.fetch('https://example.test/users/me',{method:'PATCH',headers:{Authorization:`Bearer ${owner.token}`,'Content-Type':'application/json'},body:JSON.stringify({avatar_url:file.url})});
    expect((await patch()).status).toBe(200);
    await env.DB.prepare('UPDATE messages SET is_deleted=1 WHERE id=?').bind(id).run();
    await env.DB.prepare("UPDATE privacy_settings SET profile_photo_visibility='nobody' WHERE user_id=?").bind(owner.id).run();
    expect((await mediaRead(file.key,viewer.token)).status).toBe(404);
    expect((await mediaRead(file.key,owner.token)).status).toBe(200);
    expect((await patch()).status).toBe(200);
  },30000);
});

describe('multi-attachment proof guards', () => {
  for (const route of ['message','group-message','status'] as const) {
    it(`rejects ${route} when one of two different sources is revoked`, async () => {
      const uploader = await mediaAccount(), actor = await mediaAccount(), recipient = await mediaAccount();
      await friend(actor.id,recipient.id);
      const first = await mediaUpload(actor.token), second = await mediaUpload(uploader.token);
      const source = crypto.randomUUID(), destination = crypto.randomUUID();
      await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(source,'source',uploader.id).run();
      await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),source,actor.id,'member').run();
      await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),source,uploader.id,second.url).run();
      await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(destination,'destination',actor.id).run();
      for (const u of [actor,recipient]) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),destination,u.id,'member').run();
      let reached!:()=>void,resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {prepare:env.DB.prepare.bind(env.DB),async batch(statements:D1PreparedStatement[]){reached(); await gate; return env.DB.batch(statements);}};
      const path = route === 'message' ? '/messages' : route === 'status' ? '/status' : `/groups/${destination}/messages`;
      const body = route === 'message' ? {receiver_id:recipient.id,media_url:first.url,thumbnail_url:second.url} : route === 'status' ? {content_type:'image',content_url:first.url,thumbnail_url:second.url,privacy:'contacts'} : {media_url:first.url,thumbnail_url:second.url};
      const request = app.request(`https://example.test${path}`,{method:'POST',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify(body)},{...env,DB:db} as unknown as Env);
      await ready;
      try {await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(source,actor.id).run();} finally {resume();}
      expect((await request).status).toBe(403);
      expect(await env.DB.prepare('SELECT 1 FROM trusted_media_references WHERE actor_id=? AND file_path IN (?,?)').bind(actor.id,first.key,second.key).first()).toBeNull();
      expect((await mediaRead(first.key,recipient.token)).status).toBe(404);
      expect((await mediaRead(second.key,recipient.token)).status).toBe(404);
    },30000);
  }
});

describe('group message pre-duration fallback', () => {
  for (const revoke of [false,true]) {
    it(`rechecks media proof on fallback (source revoked: ${revoke})`, async () => {
      const uploader = await mediaAccount(), actor = await mediaAccount(), viewer = await mediaAccount();
      const file = await mediaUpload(uploader.token), source = crypto.randomUUID(), destination = crypto.randomUUID();
      for (const [id,owner] of [[source,uploader],[destination,actor]] as const) await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(id,'group',owner.id).run();
      for (const [id,user] of [[source,actor],[destination,actor],[destination,viewer]] as const) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),id,user.id,'member').run();
      await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,media_url) VALUES (?,?,?,?)').bind(crypto.randomUUID(),source,uploader.id,file.url).run();
      let attempts = 0;
      const db = {prepare:env.DB.prepare.bind(env.DB),async batch(statements:D1PreparedStatement[]) {
        if (++attempts === 1) {
          if (revoke) await env.DB.prepare('DELETE FROM group_members WHERE group_id=? AND user_id=?').bind(source,actor.id).run();
          throw new Error('no column named duration');
        }
        return env.DB.batch(statements);
      }};
      const response = await app.request(`https://example.test/groups/${destination}/messages`,{method:'POST',headers:{Authorization:`Bearer ${actor.token}`,'Content-Type':'application/json'},body:JSON.stringify({media_url:file.url})},{...env,DB:db} as unknown as Env);
      expect(attempts).toBe(2);
      expect(response.status).toBe(revoke ? 403 : 201);
      const row = await env.DB.prepare('SELECT id FROM group_messages WHERE group_id=? AND sender_id=?').bind(destination,actor.id).first<{id:string}>();
      if (revoke) {
        expect(row).toBeNull();
        expect(await env.DB.prepare('SELECT 1 FROM trusted_media_references WHERE actor_id=? AND file_path=?').bind(actor.id,file.key).first()).toBeNull();
        expect((await mediaRead(file.key,viewer.token)).status).toBe(404);
      } else {
        expect(row).not.toBeNull();
        expect(await env.DB.prepare("SELECT 1 FROM trusted_media_references WHERE kind='group_message' AND reference_id=? AND file_path=?").bind(row!.id,file.key).first()).not.toBeNull();
        expect((await mediaRead(file.key,viewer.token)).status).toBe(200);
      }
    },30000);
  }
});

describe('status view persistence atomicity', () => {
  it('rolls back the view when its counter update fails, so a retry can count it', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount(); await friend(owner.id,viewer.id);
    const created = await post('/status',owner.token,{content_type:'text',text_content:'hello',privacy:'contacts'});
    expect(created.status).toBe(201);
    const id = (await created.json() as {status:{id:string}}).status.id;
    const trigger = `fail_count_${crypto.randomUUID().replaceAll('-','')}`;
    const target = await inbox(owner);
    await env.DB.prepare(`CREATE TRIGGER ${trigger} BEFORE UPDATE OF view_count ON statuses WHEN OLD.id = '${id}' BEGIN SELECT RAISE(ABORT,'injected count failure'); END`).run();
    try { expect((await post(`/status/${id}/view`,viewer.token)).status).toBe(500); }
    finally { await env.DB.prepare(`DROP TRIGGER ${trigger}`).run(); }
    expect(await env.DB.prepare('SELECT 1 FROM status_views WHERE status_id=? AND viewer_id=?').bind(id,viewer.id).first()).toBeNull();
    expect((await env.DB.prepare('SELECT view_count FROM statuses WHERE id=?').bind(id).first<{view_count:number}>())?.view_count).toBe(0);
    await target.flush(); expect(target.events).toHaveLength(0);
    expect((await post(`/status/${id}/view`,viewer.token)).status).toBe(200);
    expect((await post(`/status/${id}/view`,viewer.token)).status).toBe(200);
    expect((await env.DB.prepare('SELECT view_count FROM statuses WHERE id=?').bind(id).first<{view_count:number}>())?.view_count).toBe(1);
    await target.flush(); expect(target.events.map(e=>JSON.parse(e).type)).toEqual(['status:view']); target.socket.close(1000,'finished');
  },30000);
});

describe('concurrent status view persistence', () => {
  it('counts and announces simultaneous views by the same viewer once', async () => {
    const owner = await mediaAccount(), viewer = await mediaAccount(); await friend(owner.id,viewer.id);
    const created = await post('/status',owner.token,{content_type:'text',text_content:'hello',privacy:'contacts'});
    expect(created.status).toBe(201);
    const id = (await created.json() as {status:{id:string}}).status.id;
    const target = await inbox(owner);
    const results = await Promise.all(Array.from({length:5},()=>post(`/status/${id}/view`,viewer.token)));
    expect(results.map(r=>r.status)).toEqual([200,200,200,200,200]);
    expect((await env.DB.prepare('SELECT count(*) AS count FROM status_views WHERE status_id=?').bind(id).first<{count:number}>())?.count).toBe(1);
    expect((await env.DB.prepare('SELECT view_count FROM statuses WHERE id=?').bind(id).first<{view_count:number}>())?.view_count).toBe(1);
    await target.flush(); expect(target.events.map(e=>JSON.parse(e).type)).toEqual(['status:view']); target.socket.close(1000,'finished');
  },30000);
});

describe('concurrent message JSON actions', () => {
  for (const kind of ['direct','group'] as const) for (const action of ['reaction','delete-for-me'] as const) {
    it(`preserves both users' ${action} on a ${kind} message`, async () => {
      const a = await mediaAccount(), b = await mediaAccount();
      let id: string, path: string;
      if (kind === 'direct') {
        await friend(a.id,b.id);
        const sent = await post('/messages',a.token,{receiver_id:b.id,content:'hello'});
        expect(sent.status).toBe(201);
        id = (await sent.json() as {message:{id:string}}).message.id;
        path = `/messages/${id}/${action}`;
      } else {
        const group = crypto.randomUUID(); id = crypto.randomUUID();
        await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(group,'group',a.id).run();
        for (const user of [a,b]) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,user.id,'member').run();
        await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,content) VALUES (?,?,?,?)').bind(id,group,a.id,'hello').run();
        path = `/groups/messages/${id}/${action}`;
      }
      const table = kind === 'direct' ? 'messages' : 'group_messages';
      const column = action === 'reaction' ? 'reactions' : 'deleted_for';
      const paused = pauseWrite(`UPDATE ${table} SET ${column} = `);
      const stale = app.request(`https://example.test${path}`,{method:'POST',headers:{Authorization:`Bearer ${a.token}`,'Content-Type':'application/json'},body:JSON.stringify(action === 'reaction' ? {emoji:'👍'} : {})},paused.bindings);
      await paused.reached;
      try { expect((await post(path,b.token,action === 'reaction' ? {emoji:'👍'} : {})).status).toBe(200); }
      finally {paused.resume();}
      expect((await stale).status).toBe(200);
      const row = await env.DB.prepare(`SELECT ${column} AS value FROM ${table} WHERE id=?`).bind(id).first<{value:string}>();
      const result = JSON.parse(row!.value);
      expect((action === 'reaction' ? result['👍'] : result).sort()).toEqual([a.id,b.id].sort());
    },30000);
  }
});

describe('message JSON action semantics', () => {
  for (const kind of ['direct','group'] as const) {
    it(`keeps ${kind} toggles literal and hiding idempotent`, async () => {
      const a = await mediaAccount(), b = await mediaAccount(); let id:string, base:string, table:string;
      if (kind === 'direct') {
        await friend(a.id,b.id);
        const sent = await post('/messages',a.token,{receiver_id:b.id,content:'hello'});
        expect(sent.status).toBe(201); id = (await sent.json() as {message:{id:string}}).message.id;
        base = `/messages/${id}`; table = 'messages';
      } else {
        const group = crypto.randomUUID(); id = crypto.randomUUID();
        await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(group,'group',a.id).run();
        await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,a.id,'admin').run();
        await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,content) VALUES (?,?,?,?)').bind(id,group,a.id,'hello').run();
        base = `/groups/messages/${id}`; table = 'group_messages';
      }
      for (const emoji of ['__proto__','constructor','"\\👍']) {
        const added = await post(`${base}/reaction`,a.token,{emoji});
        expect(added.status).toBe(200);
        expect((await added.json() as {reactions:Record<string,string[]>}).reactions[emoji]).toEqual([a.id]);
        const removed = await post(`${base}/reaction`,a.token,{emoji});
        expect(removed.status).toBe(200);
        expect(Object.hasOwn((await removed.json() as {reactions:Record<string,string[]>}).reactions,emoji)).toBe(false);
      }
      expect((await post(`${base}/delete-for-me`,a.token)).status).toBe(200);
      expect((await post(`${base}/delete-for-me`,a.token)).status).toBe(200);
      const row = await env.DB.prepare(`SELECT deleted_for,reactions FROM ${table} WHERE id=?`).bind(id).first<{deleted_for:string;reactions:string}>();
      expect(JSON.parse(row!.deleted_for)).toEqual([a.id]);
      expect(JSON.parse(row!.reactions)).toEqual({});
    },30000);
  }
});

describe('reaction contention limits', () => {
  for (const kind of ['direct','group'] as const) {
    it(`returns a retryable conflict rather than overwriting after repeated ${kind} changes`, async () => {
      const a = await mediaAccount(), b = await mediaAccount(); let id:string, path:string, table:string;
      if (kind === 'direct') {
        await friend(a.id,b.id);
        const sent = await post('/messages',a.token,{receiver_id:b.id,content:'hello'});
        id = (await sent.json() as {message:{id:string}}).message.id;
        path = `/messages/${id}/reaction`; table = 'messages';
      } else {
        const group = crypto.randomUUID(); id = crypto.randomUUID();
        await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(group,'group',a.id).run();
        await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,a.id,'admin').run();
        await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,content) VALUES (?,?,?,?)').bind(id,group,a.id,'hello').run();
        path = `/groups/messages/${id}/reaction`; table = 'group_messages';
      }
      let attempts = 0;
      const db = {prepare(sql:string) {
        const stmt = env.DB.prepare(sql);
        if (!sql.includes(`UPDATE ${table} SET reactions =`)) return stmt;
        return {bind(...args:unknown[]) {const bound = stmt.bind(...args); return {async run() {
          attempts++;
          await env.DB.prepare(`UPDATE ${table} SET reactions=? WHERE id=?`).bind(JSON.stringify({other:[String(attempts)]}),id).run();
          return bound.run();
        }};}};
      },batch:env.DB.batch.bind(env.DB)};
      const response = await app.request(`https://example.test${path}`,{method:'POST',headers:{Authorization:`Bearer ${a.token}`,'Content-Type':'application/json'},body:JSON.stringify({emoji:'👍'})},{...env,DB:db} as unknown as Env);
      expect(response.status).toBe(409); expect(attempts).toBe(8);
      const row = await env.DB.prepare(`SELECT reactions FROM ${table} WHERE id=?`).bind(id).first<{reactions:string}>();
      expect(JSON.parse(row!.reactions)).toEqual({other:['8']});
    },30000);
  }
});

async function setup(kind:'direct'|'group', count:number) {
  const a = await mediaAccount(), b = await mediaAccount(), id = crypto.randomUUID();
  const reactions = Object.fromEntries(Array.from({length:count},(_,i)=>[`reaction${i}`,[b.id]]));
  let table:string, path:string;
  if (kind === 'direct') {
    await env.DB.prepare('INSERT INTO friendships (id,user1_id,user2_id) VALUES (?,?,?)').bind(crypto.randomUUID(),a.id,b.id).run();
    await env.DB.prepare('INSERT INTO messages (id,sender_id,receiver_id,reactions) VALUES (?,?,?,?)').bind(id,a.id,b.id,JSON.stringify(reactions)).run();
    table='messages'; path=`/messages/${id}/reaction`;
  } else {
    const group = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO groups (id,name,created_by) VALUES (?,?,?)').bind(group,'Group',a.id).run();
    for (const u of [a,b]) await env.DB.prepare('INSERT INTO group_members (id,group_id,user_id,role) VALUES (?,?,?,?)').bind(crypto.randomUUID(),group,u.id,'member').run();
    await env.DB.prepare('INSERT INTO group_messages (id,group_id,sender_id,reactions) VALUES (?,?,?,?)').bind(id,group,a.id,JSON.stringify(reactions)).run();
    table='group_messages'; path=`/groups/messages/${id}/reaction`;
  }
  return {a,b,id,table,path};
}
function react(path:string,token:string,emoji:string) {
  return SELF.fetch(`https://example.test${path}`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({emoji})});
}

describe('bounded distinct message reactions', () => {
  for (const kind of ['direct','group'] as const) {
    it(`limits ${kind} messages to 64 distinct reactions while allowing joins and removal`, async () => {
      const f = await setup(kind,64);
      expect((await react(f.path,f.a.token,'new-reaction')).status).toBe(400);
      expect((await react(f.path,f.a.token,'reaction0')).status).toBe(200);
      expect((await react(f.path,f.a.token,'reaction0')).status).toBe(200);
      expect((await react(f.path,f.b.token,'reaction0')).status).toBe(200);
      expect((await react(f.path,f.a.token,'new-reaction')).status).toBe(200);
      const row = await env.DB.prepare(`SELECT reactions FROM ${f.table} WHERE id=?`).bind(f.id).first<{reactions:string}>();
      expect(Object.keys(JSON.parse(row!.reactions))).toHaveLength(64);
    },30000);
    it(`rechecks the ${kind} reaction limit after a conflicting write`, async () => {
      const f = await setup(kind,63);
      let reached!:()=>void,resume!:()=>void;
      const ready = new Promise<void>(resolve=>{reached=resolve;}), gate = new Promise<void>(resolve=>{resume=resolve;});
      const db = {prepare(sql:string) {
        const stmt = env.DB.prepare(sql);
        if (!sql.includes(`UPDATE ${f.table} SET reactions =`)) return stmt;
        return {bind(...args:unknown[]) {const bound=stmt.bind(...args);return {async run(){reached();await gate;return bound.run();}};}};
      },batch:env.DB.batch.bind(env.DB)};
      const stale = app.request(`https://example.test${f.path}`,{method:'POST',headers:{Authorization:`Bearer ${f.a.token}`,'Content-Type':'application/json'},body:JSON.stringify({emoji:'stale'})},{...env,DB:db} as unknown as Env);
      await ready;
      try { expect((await react(f.path,f.b.token,'fresh')).status).toBe(200); } finally {resume();}
      expect((await stale).status).toBe(400);
      const row = await env.DB.prepare(`SELECT reactions FROM ${f.table} WHERE id=?`).bind(f.id).first<{reactions:string}>();
      const reactions = JSON.parse(row!.reactions);
      expect(Object.keys(reactions)).toHaveLength(64); expect(reactions.fresh).toEqual([f.b.id]); expect(reactions.stale).toBeUndefined();
    },30000);
  }
});
