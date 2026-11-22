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

type Pair = { media_url: string | null; thumbnail_url: string | null };

/** A file must be registered under its exact key. Access follows its shares. */
export async function canReadMedia(env: Env, userId: string, key: string): Promise<boolean> {
  const attachment = await env.DB.prepare('SELECT uploader_id FROM media_attachments WHERE file_path = ?')
    .bind(key).first<{ uploader_id: string }>();
  if (!attachment) return false;
  if (attachment.uploader_id === userId) return true;

  // instr is only a coarse candidate filter; it is NOT an authorization check.
  // Also find encoded historical URLs, then compare their decoded paths exactly.
  const [chat, group, avatars, groupAvatars, statuses] = await Promise.all([
    env.DB.prepare(`SELECT media_url, thumbnail_url FROM messages WHERE (sender_id = ?2 OR receiver_id = ?2)
      AND (instr(coalesce(media_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(media_url, ''), '%') > 0 OR instr(coalesce(thumbnail_url, ''), '%') > 0)`)
      .bind(key, userId).all<Pair>(),
    env.DB.prepare(`SELECT m.media_url, m.thumbnail_url FROM group_messages m JOIN group_members g ON g.group_id = m.group_id AND g.user_id = ?2
      WHERE instr(coalesce(m.media_url, ''), ?1) > 0 OR instr(coalesce(m.thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(m.media_url, ''), '%') > 0 OR instr(coalesce(m.thumbnail_url, ''), '%') > 0`)
      .bind(key, userId).all<Pair>(),
    env.DB.prepare(`SELECT id, avatar_url FROM users WHERE instr(coalesce(avatar_url, ''), ?1) > 0 OR instr(coalesce(avatar_url, ''), '%') > 0`)
      .bind(key).all<{ id: string; avatar_url: string }>(),
    env.DB.prepare(`SELECT groups.avatar_url FROM groups JOIN group_members gm ON gm.group_id = groups.id AND gm.user_id = ?2
      WHERE instr(coalesce(groups.avatar_url, ''), ?1) > 0 OR instr(coalesce(groups.avatar_url, ''), '%') > 0`)
      .bind(key, userId).all<{ avatar_url: string }>(),
    env.DB.prepare(`SELECT user_id, privacy, content_url, thumbnail_url FROM statuses WHERE datetime(expires_at) > datetime('now')
      AND (instr(coalesce(content_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0
        OR instr(coalesce(content_url, ''), '%') > 0 OR instr(coalesce(thumbnail_url, ''), '%') > 0)`)
      .bind(key).all<{ user_id: string; privacy: string; content_url: string | null; thumbnail_url: string | null }>(),
  ]);
  if (chat.results.some(row => mentions(env, key, row.media_url, row.thumbnail_url)) ||
      group.results.some(row => mentions(env, key, row.media_url, row.thumbnail_url)) ||
      groupAvatars.results.some(row => mentions(env, key, row.avatar_url))) return true;
  for (const avatar of avatars.results) {
    if (!mentions(env, key, avatar.avatar_url)) continue;
    const settings = await getPrivacySettings(env, avatar.id);
    if (settings.profile_photo_visibility === 'everyone' ||
        (settings.profile_photo_visibility === 'contacts' && await areFriends(env, avatar.id, userId))) return true;
  }
  for (const status of statuses.results) {
    if (mentions(env, key, status.content_url, status.thumbnail_url) &&
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
