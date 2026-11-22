import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

type Account = { id: string; token: string; email: string };
async function account(name: string): Promise<Account> {
  const email = `${name}@example.test`;
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token, email };
}
async function request(account: Account, path: string, method = 'GET', body?: object) {
  return SELF.fetch(`https://example.test${path}`, {
    method, headers: { Authorization: `Bearer ${account.token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
async function expectPublicProfile(response: Response, key: string, expectedId: string) {
  expect(response.status).toBe(200);
  const body = await response.json() as Record<string, any>;
  const profile = key.split('.').reduce((v, k) => v[k], body);
  expect(profile.id).toBe(expectedId);
  expect(profile).not.toHaveProperty('email');
  expect(profile).not.toHaveProperty('is_admin');
}

describe('profile privacy', () => {
  it('preserves own email in the self endpoints', async () => {
    const a = await account('ada');
    for (const [path, key, method, body] of [
      ['/auth/me', 'user', 'GET', undefined],
      ['/users/me', 'profile', 'GET', undefined],
      ['/users/me', 'profile', 'PATCH', { bio: 'hello' }],
      [`/users/${a.id}`, 'profile', 'GET', undefined],
    ] as const) {
      const response = await request(a, path, method, body);
      expect(response.status).toBe(200);
      expect((await response.json() as Record<string, any>)[key].email).toBe(a.email);
    }
  });

  it('hides email and admin status in discovery, search and another user profile', async () => {
    const a = await account('ada');
    const b = await account('grace');
    await expectPublicProfile(await request(a, '/users'), 'profiles.0', b.id);
    await expectPublicProfile(await request(a, '/users/search?q=grace'), 'profiles.0', b.id);
    await expectPublicProfile(await request(a, `/users/${b.id}`), 'profile', b.id);
  });

  it('hides email in friend requests, friend lists, blocked lists and chats', async () => {
    const a = await account('ada');
    const b = await account('grace');
    const c = await account('heidi');
    await env.DB.prepare("INSERT INTO friend_requests (id, sender_id, receiver_id, status) VALUES (?, ?, ?, 'pending')").bind('r', c.id, a.id).run();
    await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)').bind('f', a.id, b.id).run();
    await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)').bind('b', a.id, c.id).run();
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, content) VALUES (?, ?, ?, ?)').bind('m', a.id, b.id, 'hello').run();
    await expectPublicProfile(await request(a, '/friends/requests'), 'requests.0.sender_profile', c.id);
    await expectPublicProfile(await request(a, '/friends'), 'friends.0.profile', b.id);
    await expectPublicProfile(await request(a, '/friends/blocked'), 'blocked.0', c.id);
    await expectPublicProfile(await request(a, '/messages/chats'), 'chats.0.profile', b.id);
  });
});
