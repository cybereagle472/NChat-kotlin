import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

beforeEach(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe('account smoke test', () => {
  it('signs up, logs in, and reads the current profile', async () => {
    const credentials = { email: 'alice@example.test', password: 'password123' };
    const post = (path: string) => SELF.fetch(`https://example.test${path}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    });
    const signup = await post('/auth/signup');
    expect(signup.status).toBe(201);
    const created = await signup.json() as { user: { id: string } };
    const login = await post('/auth/login');
    expect(login.status).toBe(200);
    const session = await login.json() as { access_token: string; user: { id: string } };
    expect(session.user.id).toBe(created.user.id);
    const me = await SELF.fetch('https://example.test/users/me', {
      headers: { Authorization: `Bearer ${session.access_token}` },
    });
    expect(me.status).toBe(200);
    expect(await me.json()).toMatchObject({ profile: { id: created.user.id, email: credentials.email } });
  });
});

it('does not accept refresh tokens as API access tokens', async () => {
  const signup = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'bob@example.test', password: 'password123' }),
  });
  expect(signup.status).toBe(201);
  const tokens = await signup.json() as { access_token: string; refresh_token: string };
  const read = (token: string) => SELF.fetch('https://example.test/users/me', { headers: { Authorization: `Bearer ${token}` } });
  expect((await read(tokens.access_token)).status).toBe(200);
  expect((await read(tokens.refresh_token)).status).toBe(401);
  const refresh = await SELF.fetch('https://example.test/auth/refresh', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: tokens.refresh_token }),
  });
  expect(refresh.status).toBe(200);
});
