import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeAll, describe, expect, it } from 'vitest';

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

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
