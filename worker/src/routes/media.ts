import { Hono } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { limitSend } from '../middleware/rate-limit';
import { newId } from '../lib/id';
import { canReadMedia } from '../lib/media-access';
import { mayMessage } from '../lib/contacts';

const media = new Hono<{ Bindings: Env; Variables: Variables }>();

function mediaTypeFromMime(mime: string): 'image' | 'video' | 'audio' | 'document' {
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  return 'document';
}

// Do not serve executable user-provided documents under the API origin.
// Preserve ordinary image/audio/video types for Android playback and seeking.
function safeResponseType(stored: string | null): { type: string; inline: boolean } {
  const type = (stored ?? '').split(';', 1)[0].trim().toLowerCase();
  const safeImage = new Set(['image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/webp', 'image/bmp', 'image/avif', 'image/heic', 'image/heif']);
  const safeAudioVideo = /^(audio|video)\/[a-z0-9][a-z0-9!#$&^_.+\-]*$/.test(type);
  return safeImage.has(type) || safeAudioVideo ? { type, inline: true } : { type: 'application/octet-stream', inline: false };
}

// POST /media/upload — multipart/form-data with a "file" field.
// Replaces supabase.storage.from('chat-media').upload(...)
media.post('/upload', requireAuth, limitSend, async (c) => {
  const userId = c.get('userId');
  const form = await c.req.formData();
  const file = form.get('file');
  if (!(file instanceof File)) return c.json({ error: 'file is required (multipart/form-data)' }, 400);
  if (file.size > 49 * 1024 * 1024) return c.json({ error: 'File too large' }, 413);

  // A sealed file — an attachment of an encrypted conversation. The server
  // cannot read the message that carries its key, so the uploader names the
  // audience here: one person, or one group.
  const forUser = form.get('sealed_for_user');
  const forGroup = form.get('sealed_for_group');
  if (forUser !== null || forGroup !== null) {
    const audience = forUser !== null ? forUser : forGroup;
    if (forUser !== null && forGroup !== null) return c.json({ error: 'A sealed file has one audience' }, 400);
    if (typeof audience !== 'string' || !audience || audience.length > 512) return c.json({ error: 'Invalid audience' }, 400);
    if (forUser !== null && audience === userId) return c.json({ error: 'Cannot seal a file for yourself' }, 400);
    const allowed = forUser !== null
      ? await mayMessage(c.env, userId, audience)
      : !!(await c.env.DB.prepare('SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?').bind(audience, userId).first());
    if (!allowed) return c.json({ error: 'Cannot send files to this audience' }, 403);

    // Nothing the phone says about the file is kept: not its name, not its
    // type. They would describe content the server is not meant to know.
    const sealedKey = `${userId}/${newId()}`;
    await c.env.MEDIA.put(sealedKey, file, { httpMetadata: { contentType: 'application/octet-stream' } });
    const sealedId = newId();
    try {
      await c.env.DB.prepare(
        `INSERT INTO media_attachments (id, uploader_id, file_path, file_name, file_size, mime_type, media_type, sealed_for_user, sealed_for_group)
         VALUES (?, ?, ?, 'sealed', ?, 'application/octet-stream', 'sealed', ?, ?)`
      )
        .bind(sealedId, userId, sealedKey, file.size, forUser !== null ? audience : null, forGroup !== null ? audience : null)
        .run();
    } catch (err) {
      // A file nobody is recorded as allowed to read must not stay behind.
      await c.env.MEDIA.delete(sealedKey);
      // The database has not been given the audience columns (migration 0009).
      if (/sealed_for_(user|group)/.test(String(err))) return c.json({ error: 'Sealed files are not available on this server yet', code: 'e2ee_unavailable' }, 501);
      throw err;
    }
    const sealedUrl = `${c.env.PUBLIC_API_URL}/media/file/${sealedKey}`;
    return c.json({ id: sealedId, url: sealedUrl, key: sealedKey, media_type: 'sealed', file_name: 'sealed', file_size: file.size }, 201);
  }

  const suffix = file.name.includes('.') ? file.name.split('.').pop() ?? '' : '';
  const ext = /^[a-zA-Z0-9]{1,16}$/.test(suffix) ? suffix : '';
  const key = `${userId}/${newId()}${ext ? `.${ext}` : ''}`;

  await c.env.MEDIA.put(key, file, {
    httpMetadata: { contentType: file.type || 'application/octet-stream' },
  });

  const id = newId();
  const media_type = mediaTypeFromMime(file.type || '');
  await c.env.DB.prepare(
    `INSERT INTO media_attachments (id, uploader_id, file_path, file_name, file_size, mime_type, media_type)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  )
    .bind(id, userId, key, file.name, file.size, file.type, media_type)
    .run();

  const url = `${c.env.PUBLIC_API_URL}/media/file/${key}`;
  return c.json({ id, url, key, media_type, file_name: file.name, file_size: file.size }, 201);
});

// GET /media/file/:key — signed-in users must be in the file's audience.
//
// Serves byte-range requests (HTTP 206 Partial Content), which this used to
// skip entirely — every request got the whole object back with a plain 200,
// no `Accept-Ranges` header at all. That silently breaks exactly the kind
// of seeking a voice-message/video scrubber depends on: a browser can only
// jump to an unbuffered position in an <audio>/<video> element by asking the
// server for that specific byte range. Without Accept-Ranges advertising
// support for that, the browser has no choice but to treat the file as
// unseekable until the *entire* thing has downloaded, which is exactly why
// this looked fine on whichever side already had the file fully buffered
// (e.g. the sender, moments after uploading it) and broken/unresponsive on
// whichever side was still fetching it fresh — nothing to do with being the
// sender or receiver specifically, just whichever side's copy wasn't fully
// downloaded yet. R2 natively understands a `Range` header passed straight
// through, so this is mostly just relaying it and shaping the response per
// the HTTP spec (206 + Content-Range on a successful range request).
media.get('/file/*', requireAuth, async (c) => {
  const key = c.req.path.replace(/^\/media\/file\//, '');
  if (!(await canReadMedia(c.env, c.get('userId'), key))) return c.json({ error: 'Not found' }, 404);
  const object = await c.env.MEDIA.get(key, { range: c.req.raw.headers });
  if (!object) return c.json({ error: 'Not found' }, 404);

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  const served = safeResponseType(headers.get('content-type'));
  headers.set('content-type', served.type);
  if (served.inline) headers.delete('content-disposition');
  else headers.set('content-disposition', 'attachment');
  headers.set('x-content-type-options', 'nosniff');
  headers.set('content-security-policy', "sandbox; default-src 'none'");
  headers.set('etag', object.httpEtag);
  headers.set('cache-control', 'private, no-store');
  headers.set('accept-ranges', 'bytes');

  const range = object.range;
  let status = 200;
  if (c.req.header('Range') && range && 'offset' in range) {
    const offset = range.offset ?? 0;
    const length = range.length ?? object.size - offset;
    headers.set('content-range', `bytes ${offset}-${offset + length - 1}/${object.size}`);
    status = 206;
  }

  return new Response(object.body, { headers, status });
});

media.delete('/file/*', requireAuth, async (c) => {
  const userId = c.get('userId');
  const key = c.req.path.replace(/^\/media\/file\//, '');
  const owned = await c.env.DB.prepare('SELECT id FROM media_attachments WHERE file_path = ? AND uploader_id = ?')
    .bind(key, userId)
    .first();
  if (!owned) return c.json({ error: 'Not found' }, 404);
  await c.env.MEDIA.delete(key);
  await c.env.DB.prepare('DELETE FROM media_attachments WHERE file_path = ?').bind(key).run();
  return c.json({ success: true });
});

export default media;
