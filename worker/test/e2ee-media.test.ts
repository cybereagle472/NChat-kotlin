import { applyD1Migrations, env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';

/**
 * Sealed attachments (E2EE Batch 5, server half).
 *
 * A photo, video, voice message or file of an encrypted conversation is
 * sealed on the phone; what is uploaded is bytes the server cannot read, and
 * the key travels inside the sealed message. The server therefore cannot
 * tell from a message who may fetch a file, the way it does for readable
 * messages. The uploader says so at upload: one person, or one group.
 *
 * What these tests hold the server to:
 *  - the bytes come back exactly, to the uploader and to the audience named,
 *    and to nobody else who is signed in;
 *  - an audience can only be somebody the uploader may write to;
 *  - nothing about the file is kept but its size: no name, no type;
 *  - a sealed file is not handed on by putting its address in a message.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

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

type Account = { id: string; token: string };

async function becomeFriends(a: Account, b: Account) {
  await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

async function block(a: Account, b: Account) {
  await env.DB.prepare('INSERT INTO blocked_users (id, user_id, blocked_user_id) VALUES (?, ?, ?)')
    .bind(crypto.randomUUID(), a.id, b.id).run();
}

function json(method: string, path: string, token: string, body?: unknown) {
  return SELF.fetch(`https://example.test${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'CF-Connecting-IP': address() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function createGroup(owner: Account, members: Account[]) {
  const response = await json('POST', '/groups', owner.token, { name: 'Group', memberIds: members.map((m) => m.id) });
  expect(response.status).toBe(201);
  return ((await response.json()) as { group: { id: string } }).group.id;
}

// What a phone uploads: bytes with no meaning, under a name and a type that say nothing.
const sealedBytes = new Uint8Array(3000).map((_, index) => (index * 31 + 7) & 0xff);

function upload(token: string, fields: Record<string, string>, file = new File([sealedBytes], 'sealed', { type: 'application/octet-stream' })) {
  const form = new FormData();
  form.set('file', file);
  for (const [name, value] of Object.entries(fields)) form.set(name, value);
  return SELF.fetch('https://example.test/media/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'CF-Connecting-IP': address() },
    body: form,
  });
}

async function uploaded(token: string, fields: Record<string, string>) {
  const response = await upload(token, fields);
  expect(response.status).toBe(201);
  return response.json() as Promise<{ key: string; url: string; media_type: string; file_name: string; file_size: number }>;
}

function read(key: string, token?: string) {
  return SELF.fetch(`https://example.test/media/file/${key}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
}

describe('a sealed file for one person', () => {
  it('comes back exactly, to the two of them and to nobody else', async () => {
    const [alice, bob, carol] = await Promise.all([signup('alice'), signup('bob'), signup('carol')]);
    await becomeFriends(alice, bob);
    await becomeFriends(alice, carol);

    const file = await uploaded(alice.token, { sealed_for_user: bob.id });
    expect(file.url).toBe(`https://example.test/media/file/${file.key}`);
    expect(file.file_size).toBe(sealedBytes.length);

    for (const reader of [alice, bob]) {
      const response = await read(file.key, reader.token);
      expect(response.status).toBe(200);
      expect(new Uint8Array(await response.arrayBuffer())).toEqual(sealedBytes);
      // Never something a browser would show or run from this address.
      expect(response.headers.get('content-type')).toBe('application/octet-stream');
      expect(response.headers.get('content-disposition')).toBe('attachment');
      expect(response.headers.get('cache-control')).toBe('private, no-store');
    }
    // A friend of the uploader, signed in, knowing the address: not theirs.
    expect((await read(file.key, carol.token)).status).toBe(404);
    expect((await read(file.key)).status).toBe(401);
  });

  it('keeps nothing about the file but its size', async () => {
    const [alice, bob] = await Promise.all([signup('alice'), signup('bob')]);
    await becomeFriends(alice, bob);
    // An app that named the file after its content would be giving the content away here.
    const telling = new File([sealedBytes], 'passport-scan.pdf', { type: 'application/pdf' });
    const response = await upload(alice.token, { sealed_for_user: bob.id }, telling);
    expect(response.status).toBe(201);
    const file = await response.json() as { key: string; media_type: string; file_name: string };

    expect(file.media_type).toBe('sealed');
    expect(file.file_name).toBe('sealed');
    expect(file.key).toMatch(new RegExp(`^${alice.id}/[^/.]+$`));
    const row = await env.DB.prepare('SELECT file_name, mime_type, media_type, file_size, sealed_for_user, sealed_for_group FROM media_attachments WHERE file_path = ?')
      .bind(file.key).first<Record<string, unknown>>();
    expect(row).toEqual({
      file_name: 'sealed', mime_type: 'application/octet-stream', media_type: 'sealed',
      file_size: sealedBytes.length, sealed_for_user: bob.id, sealed_for_group: null,
    });
    const served = await read(file.key, bob.token);
    expect(served.headers.get('content-type')).toBe('application/octet-stream');
    await served.arrayBuffer();
  });

  it('can only be for somebody the uploader may write to', async () => {
    const [alice, bob, stranger, blocker] = await Promise.all([signup('alice'), signup('bob'), signup('stranger'), signup('blocker')]);
    await becomeFriends(alice, bob);
    await becomeFriends(alice, blocker);
    await block(blocker, alice);

    expect((await upload(alice.token, { sealed_for_user: stranger.id })).status).toBe(403);
    expect((await upload(alice.token, { sealed_for_user: blocker.id })).status).toBe(403);
    expect((await upload(alice.token, { sealed_for_user: crypto.randomUUID() })).status).toBe(403);
    expect((await upload(alice.token, { sealed_for_user: alice.id })).status).toBe(400);
    expect((await upload(alice.token, { sealed_for_user: '' })).status).toBe(400);
    expect((await upload(alice.token, { sealed_for_user: 'x'.repeat(513) })).status).toBe(400);
    // One audience, not two.
    const group = await createGroup(alice, [bob]);
    expect((await upload(alice.token, { sealed_for_user: bob.id, sealed_for_group: group })).status).toBe(400);
    // Nothing was stored for any of them.
    const { results } = await env.DB.prepare('SELECT id FROM media_attachments WHERE uploader_id = ?').bind(alice.id).all();
    expect(results).toHaveLength(0);
    expect((await upload(alice.token, { sealed_for_user: bob.id })).status).toBe(201);
  });

  it('is not handed on by putting its address in a message', async () => {
    const [alice, bob, carol] = await Promise.all([signup('alice'), signup('bob'), signup('carol')]);
    await becomeFriends(alice, bob);
    await becomeFriends(alice, carol);
    await becomeFriends(bob, carol);
    const file = await uploaded(alice.token, { sealed_for_user: bob.id });

    // The one it was sealed for cannot pass it on as an attachment of their own…
    const passedOn = await json('POST', '/messages', bob.token, { receiver_id: carol.id, content: 'look', type: 'image', media_url: file.url });
    expect(passedOn.status).toBe(403);
    // …and the uploader cannot name it in a readable message either: there is nothing in it to read.
    const named = await json('POST', '/messages', alice.token, { receiver_id: carol.id, content: 'look', type: 'image', media_url: file.url });
    expect(named.status).toBe(403);
    const asThumbnail = await json('POST', '/messages', alice.token, { receiver_id: carol.id, content: 'look', type: 'image', thumbnail_url: file.url });
    expect(asThumbnail.status).toBe(403);
    expect((await read(file.key, carol.token)).status).toBe(404);
    expect((await read(file.key, bob.token)).status).toBe(200);
  });

  it('is deleted by its uploader only', async () => {
    const [alice, bob] = await Promise.all([signup('alice'), signup('bob')]);
    await becomeFriends(alice, bob);
    const file = await uploaded(alice.token, { sealed_for_user: bob.id });
    expect((await json('DELETE', `/media/file/${file.key}`, bob.token)).status).toBe(404);
    expect((await read(file.key, bob.token)).status).toBe(200);
    expect((await json('DELETE', `/media/file/${file.key}`, alice.token)).status).toBe(200);
    expect((await read(file.key, bob.token)).status).toBe(404);
    expect((await read(file.key, alice.token)).status).toBe(404);
  });
});

describe('a sealed file for a group', () => {
  it('is for whoever is in the group when they ask', async () => {
    const [alice, bob, carol, dave] = await Promise.all([signup('alice'), signup('bob'), signup('carol'), signup('dave')]);
    const group = await createGroup(alice, [bob, carol]);
    const file = await uploaded(bob.token, { sealed_for_group: group });

    for (const member of [alice, bob, carol]) {
      const response = await read(file.key, member.token);
      expect(response.status).toBe(200);
      expect(new Uint8Array(await response.arrayBuffer())).toEqual(sealedBytes);
    }
    expect((await read(file.key, dave.token)).status).toBe(404);

    // Somebody who joins can fetch it (whether they can open it is the key's business)…
    expect((await json('POST', `/groups/${group}/members`, alice.token, { memberIds: [dave.id] })).status).toBe(200);
    expect((await read(file.key, dave.token)).status).toBe(200);
    // …and somebody who was removed cannot any more.
    expect((await json('DELETE', `/groups/${group}/members/${carol.id}`, alice.token)).status).toBe(200);
    expect((await read(file.key, carol.token)).status).toBe(404);
    // The uploader keeps theirs even after leaving.
    expect((await json('POST', `/groups/${group}/leave`, bob.token)).status).toBe(200);
    expect((await read(file.key, bob.token)).status).toBe(200);
  });

  it('can only be for a group the uploader is in', async () => {
    const [alice, bob, outsider] = await Promise.all([signup('alice'), signup('bob'), signup('outsider')]);
    const group = await createGroup(alice, [bob]);
    expect((await upload(outsider.token, { sealed_for_group: group })).status).toBe(403);
    expect((await upload(alice.token, { sealed_for_group: crypto.randomUUID() })).status).toBe(403);
    expect((await upload(alice.token, { sealed_for_group: '' })).status).toBe(400);
    expect((await upload(bob.token, { sealed_for_group: group })).status).toBe(201);
  });
});

describe('files that are not sealed', () => {
  it('are stored and shared as before', async () => {
    const [alice, bob] = await Promise.all([signup('alice'), signup('bob')]);
    await becomeFriends(alice, bob);
    const photo = new File(['a photo'], 'photo.jpg', { type: 'image/jpeg' });
    const response = await upload(alice.token, {}, photo);
    expect(response.status).toBe(201);
    const file = await response.json() as { key: string; url: string; media_type: string; file_name: string };
    expect(file.media_type).toBe('image');
    expect(file.file_name).toBe('photo.jpg');
    expect(file.key.endsWith('.jpg')).toBe(true);
    // Readable by the uploader at once, by the other person once a message names it.
    expect((await read(file.key, bob.token)).status).toBe(404);
    expect((await json('POST', '/messages', alice.token, { receiver_id: bob.id, content: file.url, type: 'image', media_url: file.url })).status).toBe(201);
    const served = await read(file.key, bob.token);
    expect(served.status).toBe(200);
    expect(served.headers.get('content-type')).toBe('image/jpeg');
    await served.arrayBuffer();
  });
});
