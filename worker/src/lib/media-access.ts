import type { Env } from '../types';
import { areFriends, getPrivacySettings, statusVisibleTo } from './privacy';

// Only the configured origin and an exact file path name a local file. Decode
// once (including encoded slashes) to avoid alternate spellings bypassing
// attachment checks. A foreign URL or query parameter containing a key is not
// a share of that key. Malformed local file paths fail closed on writes.
function localMediaKey(env: Env, address: unknown): { local: boolean; key: string | null } {
  if (typeof address !== 'string' || !address) return { local: false, key: null };
  try {
    const base = new URL(env.PUBLIC_API_URL);
    const url = new URL(address, base);
    if (url.origin !== base.origin) return { local: false, key: null };
    const path = decodeURIComponent(url.pathname);
    const prefix = '/media/file/';
    if (!path.startsWith(prefix)) return { local: false, key: null };
    const key = path.slice(prefix.length);
    return { local: true, key: key || null };
  } catch {
    // An invalid URL should not be stored as a way around local checks.
    return { local: true, key: null };
  }
}

function mentions(env: Env, key: string, ...addresses: unknown[]): boolean {
  return addresses.some(address => localMediaKey(env, address).key === key);
}

type Pair = { media_url: string | null; thumbnail_url: string | null; sender_id: string; trusted: number };

/** A file must be registered under its exact key. Access follows its shares. */
export async function canReadMedia(env: Env, userId: string, key: string): Promise<boolean> {
  const attachment = await env.DB.prepare('SELECT uploader_id FROM media_attachments WHERE file_path = ?')
    .bind(key).first<{ uploader_id: string }>();
  if (!attachment) return false;
  if (attachment.uploader_id === userId) return true;

  // instr is only a coarse candidate filter; it is NOT an authorization check.
  // Also find encoded historical URLs, then compare their decoded paths exactly.
  const [chat, group, avatars, groupAvatars, statuses] = await Promise.all([
    env.DB.prepare(`SELECT m.media_url, m.thumbnail_url, m.sender_id, EXISTS(SELECT 1 FROM trusted_media_references r WHERE r.file_path = ?1 AND r.kind = 'message' AND r.reference_id = m.id) AS trusted FROM messages m WHERE (m.sender_id = ?2 OR m.receiver_id = ?2) AND m.is_deleted = 0
      AND (instr(coalesce(media_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(media_url, ''), '%') > 0 OR instr(coalesce(thumbnail_url, ''), '%') > 0)`)
      .bind(key, userId).all<Pair>(),
    env.DB.prepare(`SELECT m.media_url, m.thumbnail_url, m.sender_id, EXISTS(SELECT 1 FROM trusted_media_references r WHERE r.file_path = ?1 AND r.kind = 'group_message' AND r.reference_id = m.id) AS trusted FROM group_messages m JOIN group_members g ON g.group_id = m.group_id AND g.user_id = ?2
      WHERE m.is_deleted = 0 AND (instr(coalesce(m.media_url, ''), ?1) > 0 OR instr(coalesce(m.thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(m.media_url, ''), '%') > 0 OR instr(coalesce(m.thumbnail_url, ''), '%') > 0)`)
      .bind(key, userId).all<Pair>(),
    env.DB.prepare(`SELECT id, avatar_url, EXISTS(SELECT 1 FROM trusted_media_references r WHERE r.file_path = ?1 AND r.kind = 'user_avatar' AND r.reference_id = users.id) AS trusted FROM users WHERE instr(coalesce(avatar_url, ''), ?1) > 0 OR instr(coalesce(avatar_url, ''), '%') > 0`)
      .bind(key).all<{ id: string; avatar_url: string; trusted: number }>(),
    env.DB.prepare(`SELECT groups.avatar_url, EXISTS(SELECT 1 FROM trusted_media_references r WHERE r.file_path = ?1 AND r.kind = 'group_avatar' AND r.reference_id = groups.id) AS trusted FROM groups JOIN group_members gm ON gm.group_id = groups.id AND gm.user_id = ?2
      WHERE instr(coalesce(groups.avatar_url, ''), ?1) > 0 OR instr(coalesce(groups.avatar_url, ''), '%') > 0`)
      .bind(key, userId).all<{ avatar_url: string; trusted: number }>(),
    env.DB.prepare(`SELECT user_id, privacy, content_url, thumbnail_url, EXISTS(SELECT 1 FROM trusted_media_references r WHERE r.file_path = ?1 AND r.kind = 'status' AND r.reference_id = statuses.id) AS trusted FROM statuses WHERE datetime(expires_at) > datetime('now')
      AND (instr(coalesce(content_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(content_url, ''), '%') > 0 OR instr(coalesce(thumbnail_url, ''), '%') > 0)`)
      .bind(key).all<{ user_id: string; privacy: string; content_url: string | null; thumbnail_url: string | null; trusted: number }>(),
  ]);
  if (chat.results.some(row => (row.sender_id === attachment.uploader_id || row.trusted) && mentions(env, key, row.media_url, row.thumbnail_url)) ||
      group.results.some(row => (row.sender_id === attachment.uploader_id || row.trusted) && mentions(env, key, row.media_url, row.thumbnail_url)) ||
      groupAvatars.results.some(row => row.trusted && mentions(env, key, row.avatar_url))) return true;
  for (const avatar of avatars.results) {
    if (!(avatar.id === attachment.uploader_id || avatar.trusted) || !mentions(env, key, avatar.avatar_url)) continue;
    const settings = await getPrivacySettings(env, avatar.id);
    if (settings.profile_photo_visibility === 'everyone' ||
        (settings.profile_photo_visibility === 'contacts' && await areFriends(env, avatar.id, userId))) return true;
  }
  for (const status of statuses.results) {
    if ((status.user_id === attachment.uploader_id || status.trusted) && mentions(env, key, status.content_url, status.thumbnail_url) &&
        await statusVisibleTo(env, status.user_id, userId, status.privacy)) return true;
  }
  return false;
}

/** A local attachment must be readable before it may be shared. */
export async function mayAttachMedia(env: Env, userId: string, addresses: unknown[]): Promise<boolean> {
  for (const address of addresses) {
    const parsed = localMediaKey(env, address);
    if (parsed.local && (!parsed.key || !(await canReadMedia(env, userId, parsed.key)))) return false;
  }
  return true;
}

export type MediaReferenceKind = 'message' | 'group_message' | 'status' | 'user_avatar' | 'group_avatar';

/** Prepare evidence for a just-authorized write; execute in the same D1 batch
 * as the referencing row. Never backfill untrusted historical references. */
export function mediaReferenceStatements(env: Env, kind: MediaReferenceKind, referenceId: string, actorId: string, addresses: unknown[]): D1PreparedStatement[] {
  const keys = new Set(addresses.map(a => localMediaKey(env, a)).filter(a => a.local && a.key).map(a => a.key!));
  return [...keys].map(key => env.DB.prepare(`INSERT OR REPLACE INTO trusted_media_references (file_path, kind, reference_id, actor_id)
    SELECT file_path, ?2, ?3, ?4 FROM media_attachments WHERE file_path = ?1 LIMIT 1`)
    .bind(key, kind, referenceId, actorId));
}

export function clearMediaReferences(env: Env, kind: MediaReferenceKind, referenceId: string): D1PreparedStatement {
  return env.DB.prepare('DELETE FROM trusted_media_references WHERE kind = ? AND reference_id = ?').bind(kind, referenceId);
}

export async function writeWithMediaReferences(env: Env, write: D1PreparedStatement, kind: MediaReferenceKind, referenceId: string, actorId: string, addresses: unknown[]): Promise<void> {
  const references = mediaReferenceStatements(env, kind, referenceId, actorId, addresses);
  if (references.length) await env.DB.batch([write, ...references]);
  else await write.run();
}
