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

function matchingAddress(env: Env, key: string, ...addresses: unknown[]): string | null {
  return addresses.find((address): address is string => typeof address === 'string' && localMediaKey(env, address).key === key) ?? null;
}

type Pair = { media_url: string | null; thumbnail_url: string | null; sender_id: string; trusted: number };

type MediaProof = { key: string; source: string | null };

/** Find one currently readable, precisely decoded reference for a file. The
 * source URL is a candidate, not an authorization that can outlive a write.
 *
 * A sealed file (an attachment of an encrypted conversation) is read by the
 * audience its uploader named and by nobody else, whatever message names its
 * address. It is never a valid attachment of a readable message
 * (`purpose === 'attach'`), not even for its uploader: there is nothing in
 * it for the recipient of such a message to read. */
async function readableMediaProof(env: Env, userId: string, key: string, purpose: 'read' | 'attach' = 'read'): Promise<MediaProof | null> {
  // `*`, not a column list: the two audience columns exist only once
  // migration 0009 has been applied, and reads must work before that too.
  const attachment = await env.DB.prepare('SELECT * FROM media_attachments WHERE file_path = ?')
    .bind(key).first<{ uploader_id: string; sealed_for_user?: string | null; sealed_for_group?: string | null }>();
  if (!attachment) return null;
  if (attachment.sealed_for_user || attachment.sealed_for_group) {
    if (purpose !== 'read') return null;
    if (attachment.uploader_id === userId || attachment.sealed_for_user === userId) return { key, source: null };
    if (!attachment.sealed_for_group) return null;
    const member = await env.DB.prepare('SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?')
      .bind(attachment.sealed_for_group, userId).first();
    return member ? { key, source: null } : null;
  }
  if (attachment.uploader_id === userId) return { key, source: null };

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
  for (const row of [...chat.results, ...group.results]) {
    const source = matchingAddress(env, key, row.media_url, row.thumbnail_url);
    if (source && (row.sender_id === attachment.uploader_id || row.trusted)) return { key, source };
  }
  for (const row of groupAvatars.results) {
    const source = matchingAddress(env, key, row.avatar_url);
    if (source && row.trusted) return { key, source };
  }
  for (const avatar of avatars.results) {
    const source = matchingAddress(env, key, avatar.avatar_url);
    if (!(avatar.id === attachment.uploader_id || avatar.trusted) || !source) continue;
    const settings = await getPrivacySettings(env, avatar.id);
    if (avatar.id === userId || settings.profile_photo_visibility === 'everyone' ||
        (settings.profile_photo_visibility === 'contacts' && await areFriends(env, avatar.id, userId))) return { key, source };
  }
  for (const status of statuses.results) {
    const source = matchingAddress(env, key, status.content_url, status.thumbnail_url);
    if ((status.user_id === attachment.uploader_id || status.trusted) && source &&
        await statusVisibleTo(env, status.user_id, userId, status.privacy)) return { key, source };
  }
  return null;
}

/** A file must be registered under its exact key. Access follows its shares. */
export async function canReadMedia(env: Env, userId: string, key: string): Promise<boolean> {
  return !!(await readableMediaProof(env, userId, key));
}

/** Collect one exact, decoded source candidate per local attachment. SQL must
 * revalidate each candidate together with any write which grants new access. */
export async function attachmentProofs(env: Env, userId: string, addresses: unknown[]): Promise<MediaProof[] | null> {
  const proofs: MediaProof[] = [];
  const seen = new Set<string>();
  for (const address of addresses) {
    const parsed = localMediaKey(env, address);
    if (!parsed.local) continue;
    if (!parsed.key) return null;
    if (seen.has(parsed.key)) continue;
    const proof = await readableMediaProof(env, userId, parsed.key, 'attach');
    if (!proof) return null;
    proofs.push(proof);
    seen.add(parsed.key);
  }
  return proofs;
}

/** A local attachment must be readable before it may be shared. */
export async function mayAttachMedia(env: Env, userId: string, addresses: unknown[]): Promise<boolean> {
  return (await attachmentProofs(env, userId, addresses)) !== null;
}

/** SQL expression for use in the same D1 statement as an attachment write.
 * Placeholders are trusted SQL fragments supplied by the caller. The source
 * URL was decoded/verified by attachmentProofs; compare that exact stored URL
 * here, and re-check current audience, provenance, blocks and expiry. */
export function attachmentProofGuard(actor: string, proofs: string): string {
  const key = "json_extract(proof.value, '$.key')";
  const source = "json_extract(proof.value, '$.source')";
  const friend = (owner: string) => `EXISTS (SELECT 1 FROM friendships f WHERE
    (f.user1_id = ${owner} AND f.user2_id = ${actor}) OR (f.user1_id = ${actor} AND f.user2_id = ${owner}))`;
  const trusted = (kind: string, id: string) => `EXISTS (SELECT 1 FROM trusted_media_references r
    WHERE r.file_path = ${key} AND r.kind = '${kind}' AND r.reference_id = ${id})`;
  return `NOT EXISTS (SELECT 1 FROM json_each(${proofs}) proof WHERE NOT EXISTS (
    SELECT 1 FROM media_attachments a WHERE a.file_path = ${key} AND (
      a.uploader_id = ${actor}
      OR EXISTS (SELECT 1 FROM messages m WHERE (m.sender_id = ${actor} OR m.receiver_id = ${actor}) AND m.is_deleted = 0
        AND (m.media_url = ${source} OR m.thumbnail_url = ${source})
        AND (m.sender_id = a.uploader_id OR ${trusted('message', 'm.id')}))
      OR EXISTS (SELECT 1 FROM group_messages m JOIN group_members gm ON gm.group_id = m.group_id AND gm.user_id = ${actor}
        WHERE m.is_deleted = 0 AND (m.media_url = ${source} OR m.thumbnail_url = ${source})
        AND (m.sender_id = a.uploader_id OR ${trusted('group_message', 'm.id')}))
      OR EXISTS (SELECT 1 FROM groups g JOIN group_members gm ON gm.group_id = g.id AND gm.user_id = ${actor}
        WHERE g.avatar_url = ${source} AND ${trusted('group_avatar', 'g.id')})
      OR EXISTS (SELECT 1 FROM users u LEFT JOIN privacy_settings ps ON ps.user_id = u.id
        WHERE u.avatar_url = ${source} AND (u.id = a.uploader_id OR ${trusted('user_avatar', 'u.id')})
        AND (u.id = ${actor} OR coalesce(ps.profile_photo_visibility, 'everyone') = 'everyone' OR
          (coalesce(ps.profile_photo_visibility, 'everyone') = 'contacts' AND ${friend('u.id')})))
      OR EXISTS (SELECT 1 FROM statuses s LEFT JOIN privacy_settings ps ON ps.user_id = s.user_id
        WHERE datetime(s.expires_at) > datetime('now') AND (s.content_url = ${source} OR s.thumbnail_url = ${source})
        AND (s.user_id = a.uploader_id OR ${trusted('status', 's.id')})
        AND (s.user_id = ${actor} OR (
          NOT EXISTS (SELECT 1 FROM blocked_users b WHERE (b.user_id = s.user_id AND b.blocked_user_id = ${actor})
            OR (b.user_id = ${actor} AND b.blocked_user_id = s.user_id))
          AND (s.privacy = 'everyone' OR (s.privacy = 'contacts' AND ${friend('s.user_id')}))
          AND (coalesce(ps.status_visibility, 'contacts') = 'everyone' OR
            (coalesce(ps.status_visibility, 'contacts') = 'contacts' AND ${friend('s.user_id')})))))
    )))`;
}

export type MediaReferenceKind = 'message' | 'group_message' | 'status' | 'user_avatar' | 'group_avatar';

/** Prepare evidence for a just-authorized write; execute in the same D1 batch
 * as the referencing row. Never backfill untrusted historical references. */
export function mediaReferenceStatements(env: Env, kind: MediaReferenceKind, referenceId: string, actorId: string, addresses: unknown[]): D1PreparedStatement[] {
  const keys = new Set(addresses.map(a => localMediaKey(env, a)).filter(a => a.local && a.key).map(a => a.key!));
  return [...keys].map(key => env.DB.prepare(`INSERT OR REPLACE INTO trusted_media_references (file_path, kind, reference_id, actor_id)
    SELECT file_path, ?2, ?3, ?4 FROM media_attachments WHERE file_path = ?1
    ${kind === 'group_message' ? 'AND EXISTS (SELECT 1 FROM group_messages WHERE id = ?3 AND sender_id = ?4)' : kind === 'message' ? 'AND EXISTS (SELECT 1 FROM messages WHERE id = ?3 AND sender_id = ?4)' : kind === 'status' ? 'AND EXISTS (SELECT 1 FROM statuses WHERE id = ?3 AND user_id = ?4)' : kind === 'group_avatar' ? 'AND EXISTS (SELECT 1 FROM groups WHERE id = ?3 AND created_by = ?4)' : ''} LIMIT 1`)
    .bind(key, kind, referenceId, actorId));
}

/** Recheck the same source proof for every avatar provenance mutation in the
 * batch, so a skipped avatar write cannot clear or create a grant. */
export function guardedUserAvatarReferences(env: Env, userId: string, address: unknown, proofs: string): D1PreparedStatement[] {
  const guard = attachmentProofGuard('?1', '?2');
  const key = localMediaKey(env, address).key;
  const statements: D1PreparedStatement[] = [];
  // Add the new grant before deleting older ones: the current avatar may be
  // the only remaining proof that this same file is readable.
  if (key) statements.push(env.DB.prepare(`INSERT OR REPLACE INTO trusted_media_references (file_path, kind, reference_id, actor_id)
    SELECT file_path, 'user_avatar', ?1, ?1 FROM media_attachments WHERE file_path = ?3 AND ${guard}`)
    .bind(userId, proofs, key));
  statements.push(env.DB.prepare(`DELETE FROM trusted_media_references
    WHERE kind = 'user_avatar' AND reference_id = ?1 AND (?3 IS NULL OR file_path != ?3) AND ${guard}`).bind(userId, proofs, key));
  return statements;
}

/** Avatar provenance changes follow the group update's admin and attachment
 * guards. Execute these statements in the same D1 batch as the update. */
export function guardedGroupAvatarReferences(env: Env, groupId: string, actorId: string, address: unknown, proofs: string): D1PreparedStatement[] {
  const guard = `EXISTS (SELECT 1 FROM group_members WHERE group_id = ?1 AND user_id = ?2 AND role = 'admin') AND ${attachmentProofGuard('?2', '?3')}`;
  const key = localMediaKey(env, address).key;
  const statements: D1PreparedStatement[] = [];
  if (key) statements.push(env.DB.prepare(`INSERT OR REPLACE INTO trusted_media_references (file_path, kind, reference_id, actor_id)
    SELECT file_path, 'group_avatar', ?1, ?2 FROM media_attachments WHERE file_path = ?4 AND ${guard}`)
    .bind(groupId, actorId, proofs, key));
  statements.push(env.DB.prepare(`DELETE FROM trusted_media_references
    WHERE kind = 'group_avatar' AND reference_id = ?1 AND (?4 IS NULL OR file_path != ?4) AND ${guard}`).bind(groupId, actorId, proofs, key));
  return statements;
}

export async function writeWithMediaReferences(env: Env, write: D1PreparedStatement, kind: MediaReferenceKind, referenceId: string, actorId: string, addresses: unknown[]): Promise<D1Result> {
  const references = mediaReferenceStatements(env, kind, referenceId, actorId, addresses);
  if (references.length) return (await env.DB.batch([write, ...references]))[0];
  return write.run();
}
