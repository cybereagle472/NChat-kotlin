import type { Env } from '../types';
import { statusVisibleTo } from './privacy';

/** A file must be registered under its exact key. Access follows every place
 * it is shared, so forwarding into a group does not revoke the original chat.
 * As in the existing API, avatar files may be read by any signed-in user. */
export async function canReadMedia(env: Env, userId: string, key: string): Promise<boolean> {
  const attachment = await env.DB.prepare('SELECT uploader_id FROM media_attachments WHERE file_path = ?')
    .bind(key).first<{ uploader_id: string }>();
  if (!attachment) return false;
  if (attachment.uploader_id === userId) return true;

  const [chat, group, avatar, statuses] = await Promise.all([
    env.DB.prepare(`SELECT 1 FROM messages WHERE (sender_id = ?2 OR receiver_id = ?2)
      AND (instr(coalesce(media_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0) LIMIT 1`)
      .bind(key, userId).first(),
    env.DB.prepare(`SELECT 1 FROM group_messages m JOIN group_members g ON g.group_id = m.group_id AND g.user_id = ?2
      WHERE instr(coalesce(m.media_url, ''), ?1) > 0 OR instr(coalesce(m.thumbnail_url, ''), ?1) > 0 LIMIT 1`)
      .bind(key, userId).first(),
    env.DB.prepare(`SELECT 1 FROM users WHERE instr(coalesce(avatar_url, ''), ?1) > 0
      UNION ALL SELECT 1 FROM groups WHERE instr(coalesce(avatar_url, ''), ?1) > 0 LIMIT 1`)
      .bind(key).first(),
    env.DB.prepare(`SELECT user_id, privacy FROM statuses WHERE datetime(expires_at) > datetime('now')
      AND (instr(coalesce(content_url, ''), ?1) > 0 OR instr(coalesce(thumbnail_url, ''), ?1) > 0)`)
      .bind(key).all<{ user_id: string; privacy: string }>(),
  ]);
  if (chat || group || avatar) return true;
  for (const status of statuses.results ?? []) {
    if (await statusVisibleTo(env, status.user_id, userId, status.privacy)) return true;
  }
  return false;
}

/** An address naming a local attachment must be readable before it is stored;
 * otherwise an attacker could manufacture their own authorization by referring
 * to someone else's file. External URLs remain supported. */
export async function mayAttachMedia(env: Env, userId: string, addresses: unknown[]): Promise<boolean> {
  for (const address of addresses) {
    if (typeof address !== 'string' || !address) continue;
    const keys = await env.DB.prepare("SELECT file_path FROM media_attachments WHERE file_path != '' AND instr(?1, file_path) > 0")
      .bind(address).all<{ file_path: string }>();
    for (const row of keys.results ?? []) {
      if (!(await canReadMedia(env, userId, row.file_path))) return false;
    }
  }
  return true;
}
