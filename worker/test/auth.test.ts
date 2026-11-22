import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

beforeEach(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe('account smoke test', () => {
  it('signs up, logs in, and reads the current profile', async () => {
    const credentials = { email: 'alice@example.test', password: 'password123' };
    const post = (path: string) => SELF.fetch(`https://example.test${path}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
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
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` },
    body: JSON.stringify({ email: 'bob@example.test', password: 'password123' }),
  });
  expect(signup.status).toBe(201);
  const tokens = await signup.json() as { access_token: string; refresh_token: string };
  const read = (token: string) => SELF.fetch('https://example.test/users/me', { headers: { Authorization: `Bearer ${token}` } });
  expect((await read(tokens.access_token)).status).toBe(200);
  expect((await read(tokens.refresh_token)).status).toBe(401);
  const refresh = await SELF.fetch('https://example.test/auth/refresh', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` }, body: JSON.stringify({ refresh_token: tokens.refresh_token }),
  });
  expect(refresh.status).toBe(200);
});

it('upgrades weak password hashes after a successful login', async () => {
  const email = 'legacy@example.test';
  const signup = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1` }, body: JSON.stringify({email, password:'secret1'}),
  });
  expect(signup.status).toBe(201);
  const weak = '1000:000102030405060708090a0b0c0d0e0f:6f8926ce4ca5c25a52786dd2e12bc46e17bd289c4e073a8f6d047e7efc748c88';
  await env.DB.prepare('UPDATE users SET password_hash = ? WHERE email = ?').bind(weak, email).run();
  const login = (password:string) => SELF.fetch('https://example.test/auth/login', {method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP': `2001:db8:${crypto.randomUUID().slice(0,4)}::1`},body:JSON.stringify({email,password})});
  expect((await login('wrong')).status).toBe(401);
  expect((await env.DB.prepare('SELECT password_hash FROM users WHERE email = ?').bind(email).first<{password_hash:string}>())?.password_hash).toBe(weak);
  expect((await login('secret1')).status).toBe(200);
  expect((await env.DB.prepare('SELECT password_hash FROM users WHERE email = ?').bind(email).first<{password_hash:string}>())?.password_hash.startsWith('100000:')).toBe(true);
});

it('rejects malformed and unsupported password hashes without throwing', async () => {
  const { verifyPassword } = await import('../src/lib/password');
  for (const value of ['0:00:ff', '999999999:00:ff', 'abc:00:ff', '1000:nothex:garbage']) {
    expect(await verifyPassword('secret1', value), value).toBe(false);
  }
});

it('rejects a token at its expiration time', async () => {
  const { signJWT } = await import('../src/lib/jwt');
  const token = await signJWT({sub:'some-user'}, 'local-tests-only-not-a-production-secret', 0);
  const response = await SELF.fetch('https://example.test/users/me', {headers:{Authorization:`Bearer ${token}`}});
  expect(response.status).toBe(401);
});
