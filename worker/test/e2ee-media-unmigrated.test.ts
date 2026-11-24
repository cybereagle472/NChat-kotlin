import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

/**
 * Deploying the code does not apply a migration: the owner runs it against
 * the live database by hand. Between the two, the server must go on doing
 * everything it did before, and say plainly what it cannot do yet.
 *
 * This file holds one test and nothing else, because it takes the audience
 * columns of migration 0009 away again and no other test should meet a
 * database in that state.
 */

const address = () => `2001:db8:${crypto.randomUUID().slice(0, 4)}::1`;

async function signup(name: string) {
  const response = await SELF.fetch('https://example.test/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': address() },
    body: JSON.stringify({ email: `${name}-${crypto.randomUUID()}@example.test`, password: 'password123', full_name: name }),
  });
  expect(response.status).toBe(201);
  const body = await response.json() as { user: { id: string }; access_token: string };
  return { id: body.user.id, token: body.access_token };
}

function upload(token: string, file: File, fields: Record<string, string> = {}) {
  const form = new FormData();
  form.set('file', file);
  for (const [name, value] of Object.entries(fields)) form.set(name, value);
  return SELF.fetch('https://example.test/media/upload', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'CF-Connecting-IP': address() }, body: form,
  });
}

describe('a server whose database has not been given migration 0009', () => {
  it('stores and serves ordinary files as before, and refuses sealed ones without keeping them', async () => {
    await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
    await env.DB.batch([
      env.DB.prepare('ALTER TABLE media_attachments DROP COLUMN sealed_for_user'),
      env.DB.prepare('ALTER TABLE media_attachments DROP COLUMN sealed_for_group'),
    ]);
    const [alice, bob] = await Promise.all([signup('alice'), signup('bob')]);
    await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)').bind(crypto.randomUUID(), alice.id, bob.id).run();

    // Ordinary files: upload, attach to a message, read on the other side.
    const stored = await upload(alice.token, new File(['a photo'], 'photo.jpg', { type: 'image/jpeg' }));
    expect(stored.status).toBe(201);
    const file = await stored.json() as { key: string; url: string };
    const sent = await SELF.fetch('https://example.test/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${alice.token}`, 'CF-Connecting-IP': address() },
      body: JSON.stringify({ receiver_id: bob.id, content: file.url, type: 'image', media_url: file.url }),
    });
    expect(sent.status).toBe(201);
    for (const reader of [alice, bob]) {
      const served = await SELF.fetch(file.url, { headers: { Authorization: `Bearer ${reader.token}` } });
      expect(served.status).toBe(200);
      expect(await served.text()).toBe('a photo');
    }

    // A sealed file: said plainly, and not left lying in storage with nobody allowed to read it.
    const before = (await env.MEDIA.list()).objects.length;
    const sealed = await upload(alice.token, new File([new Uint8Array(64)], 'sealed', { type: 'application/octet-stream' }), { sealed_for_user: bob.id });
    expect(sealed.status).toBe(501);
    expect(((await sealed.json()) as { code: string }).code).toBe('e2ee_unavailable');
    expect((await env.MEDIA.list()).objects.length).toBe(before);
    const { results } = await env.DB.prepare("SELECT id FROM media_attachments WHERE media_type = 'sealed'").all();
    expect(results).toHaveLength(0);
  });
});
