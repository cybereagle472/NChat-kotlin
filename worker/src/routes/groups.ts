import { Hono } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { limitContact, limitSend } from '../middleware/rate-limit';
import { mayAttachMedia, guardedGroupAvatarReferences, writeWithMediaReferences } from '../lib/media-access';
import { exceedsTextLimits } from '../lib/input';
import { newId, nowIso } from '../lib/id';
import { broadcastToUser } from '../lib/realtime';
import { applyPrivacyToList, getPrivacySettings } from '../lib/privacy';

const groups = new Hono<{ Bindings: Env; Variables: Variables }>();
groups.use('*', requireAuth);

// Exported for worker/src/routes/groupCalls.ts, which needs the same
// "is this user actually in this group" / "who's in this group" checks a
// group call has to run before letting someone ring, join or signal it.
export async function getMembership(env: Env, groupId: string, userId: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return env.DB.prepare('SELECT * FROM group_members WHERE group_id = ? AND user_id = ?')
    .bind(groupId, userId)
    .first<any>();
}

export async function memberIdsOf(env: Env, groupId: string): Promise<string[]> {
  const { results } = await env.DB.prepare('SELECT user_id FROM group_members WHERE group_id = ?').bind(groupId).all<{ user_id: string }>();
  return (results ?? []).map((r) => r.user_id);
}

/** Fan a group-scoped event out to every member's personal realtime room
 * (the same `user:<id>` room every other feature already uses) — a group
 * doesn't have its own room clients subscribe to, since every client holds
 * a single shared WebSocket for their own room only. */
async function broadcastToGroup(env: Env, groupId: string, payload: unknown, memberIds?: string[]): Promise<void> {
  const ids = memberIds ?? (await memberIdsOf(env, groupId));
  await Promise.all(ids.map((uid) => broadcastToUser(env, uid, payload)));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function groupWithMembers(env: Env, groupId: string, viewerId: string): Promise<any | null> {
  const group = await env.DB.prepare('SELECT * FROM groups WHERE id = ?').bind(groupId).first<any>();
  if (!group) return null;
  // last_read_at/read_receipts are LEFT JOINed so a member who has never
  // opened the chat (no group_read_state row) or has no privacy_settings
  // row yet (defaults to read_receipts = 1, same default as lib/privacy.ts)
  // still comes back as a normal member rather than being dropped.
  const { results: memberRows } = await env.DB.prepare(
    `SELECT gm.*, u.full_name, u.username, u.avatar_url,
            grs.last_read_at AS last_read_at,
            COALESCE(ps.read_receipts, 1) AS read_receipts
     FROM group_members gm
     JOIN users u ON u.id = gm.user_id
     LEFT JOIN group_read_state grs ON grs.group_id = gm.group_id AND grs.user_id = gm.user_id
     LEFT JOIN privacy_settings ps ON ps.user_id = gm.user_id
     WHERE gm.group_id = ?`
  )
    .bind(groupId)
    .all<any>();
  const profiles = await applyPrivacyToList(env, viewerId, (memberRows ?? []).map((m: any) => ({
    id: m.user_id, full_name: m.full_name, username: m.username, avatar_url: m.avatar_url,
  })));
  return {
    ...group,
    members: (memberRows ?? []).map((m: any, index: number) => ({
      group_id: m.group_id,
      user_id: m.user_id,
      role: m.role,
      is_muted: !!m.is_muted,
      joined_at: m.joined_at,
      // A member who has disabled read receipts never exposes their read
      // status to anyone else — same opt-out rule 1:1 chat already
      // enforces for its own read receipts (see POST /messages/mark-read).
      last_read_at: m.read_receipts ? (m.last_read_at ?? undefined) : undefined,
      profile: profiles[index],
    })),
  };
}

/** Group payloads contain member profiles and must be shaped per recipient. */
async function broadcastGroupProjection(env: Env, groupId: string, type: string, memberIds?: string[]): Promise<void> {
  const current = await memberIdsOf(env, groupId);
  const recipients = memberIds ? current.filter(id => memberIds.includes(id)) : current;
  await Promise.all(recipients.map(async viewerId => {
    const group = await groupWithMembers(env, groupId, viewerId);
    if (!group || !(await getMembership(env, groupId, viewerId))) return;
    const payload = type === 'group:created' || type === 'group:updated'
      ? { type, group }
      : { type, group_id: groupId, members: group.members };
    await broadcastToUser(env, viewerId, payload);
  }));
}

/**
 * Has every *other* current member of this group read a message sent by
 * `senderId` at `createdAt`? Mirrors the double-check tick semantics 1:1
 * chat already has, generalized to "read by everyone" for a group.
 * `members` must already carry the privacy-masked `last_read_at` from
 * groupWithMembers() above.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function isReadByAllOthers(members: any[], senderId: string, createdAt: string): boolean {
  const others = members.filter((m) => m.user_id !== senderId);
  if (others.length === 0) return false;
  return others.every((m) => m.last_read_at && m.last_read_at >= createdAt);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function groupMessageJson(row: any) {
  return {
    id: row.id,
    group_id: row.group_id,
    sender_id: row.sender_id,
    content: row.content,
    type: row.type,
    media_url: row.media_url ?? undefined,
    thumbnail_url: row.thumbnail_url ?? undefined,
    duration: row.duration ?? undefined,
    client_message_id: row.client_message_id ?? undefined,
    reply_to: row.reply_to ?? undefined,
    is_edited: !!row.is_edited,
    edited_at: row.edited_at ?? undefined,
    is_deleted: !!row.is_deleted,
    deleted_for: row.deleted_for ? JSON.parse(row.deleted_for) : undefined,
    reactions: row.reactions ? JSON.parse(row.reactions) : undefined,
    created_at: row.created_at,
    is_read: undefined as boolean | undefined,
  };
}

groups.post('/', limitContact, async (c) => {
  const userId = c.get('userId');
  const { name, description, avatar_url, memberIds = [] } = await c.req.json().catch(() => ({}));
  if (exceedsTextLimits({name,description,avatar_url}, {name:100,description:2048,avatar_url:2048}) || !Array.isArray(memberIds) || memberIds.length > 256 || memberIds.some((id: unknown) => typeof id !== 'string' || id.length > 512)) return c.json({ error: 'Invalid group fields' }, 400);
  if (!name || !String(name).trim()) return c.json({ error: 'name is required' }, 400);
  if (!(await mayAttachMedia(c.env, userId, [avatar_url]))) return c.json({ error: 'Cannot attach this file' }, 403);

  const groupId = newId();
  const created_at = nowIso();
  const write = c.env.DB.prepare('INSERT INTO groups (id, name, description, avatar_url, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(groupId, String(name).trim(), description?.trim() || null, avatar_url || null, userId, created_at);
  await writeWithMediaReferences(c.env, write, 'group_avatar', groupId, userId, [avatar_url]);

  const allMembers: string[] = Array.from(new Set([userId, ...(memberIds as string[])]));
  const joinedAt = created_at;
  await c.env.DB.batch(
    allMembers.map((uid) =>
      c.env.DB.prepare('INSERT INTO group_members (id, group_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)').bind(
        newId(),
        groupId,
        uid,
        uid === userId ? 'admin' : 'member',
        joinedAt
      )
    )
  );

  const group = await groupWithMembers(c.env, groupId, userId);
  // Let every invited member's device pick up the new group immediately
  // (their Groups tab) without waiting for a manual refresh.
  await broadcastGroupProjection(c.env, groupId, 'group:created', allMembers);
  return c.json({ group }, 201);
});

groups.get('/', async (c) => {
  const userId = c.get('userId');
  const { results: groupRows } = await c.env.DB.prepare(
    `SELECT g.* FROM groups g JOIN group_members gm ON gm.group_id = g.id WHERE gm.user_id = ? ORDER BY g.created_at DESC`
  )
    .bind(userId)
    .all();

  const groupsWithMembers = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  for (const g of (groupRows ?? []) as any[]) {
    const full = await groupWithMembers(c.env, g.id, userId);
    const lastMessageRow = await c.env.DB.prepare(
      'SELECT * FROM group_messages WHERE group_id = ? ORDER BY created_at DESC LIMIT 1'
    )
      .bind(g.id)
      .first();

    let lastMessage = lastMessageRow ? groupMessageJson(lastMessageRow) : undefined;
    // The double-check tick on the list preview only ever applies to a
    // message *I* sent — same rule as the in-chat bubble.
    if (lastMessage && lastMessage.sender_id === userId) {
      lastMessage = { ...lastMessage, is_read: isReadByAllOthers(full.members, userId, lastMessage.created_at) };
    }

    // Unread badge: every message not sent by me since I last read this
    // group (or since I joined, if I've never read it — so a brand new
    // member doesn't get hit with years of pre-existing history as
    // "unread"). Read my *own* last_read_at straight from the table rather
    // than off `full.members` — that list privacy-masks last_read_at for
    // members who've disabled read receipts, and that must never apply to
    // my own unread count, only to what *other* people can see of mine.
    const myMembership = full.members.find((m: { user_id: string }) => m.user_id === userId);
    const myReadState = await c.env.DB.prepare(
      'SELECT last_read_at FROM group_read_state WHERE group_id = ? AND user_id = ?'
    )
      .bind(g.id, userId)
      .first<{ last_read_at: string }>();
    const since = myReadState?.last_read_at || myMembership?.joined_at || '1970-01-01T00:00:00.000Z';
    const unreadRow = await c.env.DB.prepare(
      'SELECT COUNT(*) AS cnt FROM group_messages WHERE group_id = ? AND sender_id != ? AND created_at > ?'
    )
      .bind(g.id, userId, since)
      .first<{ cnt: number }>();

    groupsWithMembers.push({
      ...full,
      lastMessage,
      unreadCount: unreadRow?.cnt ?? 0,
    });
  }
  return c.json({ groups: groupsWithMembers });
});

groups.get('/:id', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const group = await groupWithMembers(c.env, groupId, userId);
  if (!group) return c.json({ error: 'Group not found' }, 404);
  return c.json({ group });
});

groups.get('/:id/members', async (c) => {
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, c.get('userId'));
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);
  const { results } = await c.env.DB.prepare(
    `SELECT gm.*, u.full_name, u.username, u.avatar_url FROM group_members gm
     JOIN users u ON u.id = gm.user_id WHERE gm.group_id = ?`
  )
    .bind(groupId)
    .all<{ user_id: string; avatar_url: string | null; [key: string]: unknown }>();
  const rows = results ?? [];
  const profiles = await applyPrivacyToList(c.env, c.get('userId'), rows.map(row => ({ id: row.user_id, avatar_url: row.avatar_url })));
  return c.json({ members: rows.map((row, i) => ({ ...row, avatar_url: profiles[i].avatar_url })) });
});

groups.post('/:id/members', limitContact, async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id')!;
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);
  if (membership.role !== 'admin') return c.json({ error: 'Only admins can add members' }, 403);

  const { memberIds = [] } = await c.req.json().catch(() => ({}));
  if (!Array.isArray(memberIds) || memberIds.length > 256 || memberIds.some((id: unknown) => typeof id !== 'string' || id.length > 512)) return c.json({ error: 'Invalid member list' }, 400);
  const newIds = (memberIds as string[]).filter((uid) => uid);
  if (newIds.length === 0) return c.json({ success: true });

  const joinedAt = nowIso();
  // D1 runs the authorization read and the guarded inserts in one batch
  // transaction, so a concurrent demotion cannot authorize stale additions.
  const [authorization] = await c.env.DB.batch([
    c.env.DB.prepare("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ? AND role = 'admin'").bind(groupId, userId),
    ...newIds.map((uid) =>
      c.env.DB.prepare(`INSERT OR IGNORE INTO group_members (id, group_id, user_id, role, joined_at)
        SELECT ?1, ?2, ?3, 'member', ?4
        WHERE EXISTS (SELECT 1 FROM group_members WHERE group_id = ?2 AND user_id = ?5 AND role = 'admin')`)
        .bind(newId(), groupId, uid, joinedAt, userId)
    ),
  ]);
  if (!authorization.results.length) return c.json({ error: 'Only admins can add members' }, 403);

  const group = await groupWithMembers(c.env, groupId, userId);
  await broadcastGroupProjection(c.env, groupId, 'group-member:joined');
  // Make sure the newly-added members themselves hear about it even if
  // broadcastToGroup's membership lookup (post-insert) already includes
  // them — kept explicit so this still works if that lookup ever changes.
  await broadcastGroupProjection(c.env, groupId, 'group:created', newIds);
  return c.json({ success: true, members: group?.members ?? [] });
});

// D1 batch is transactional: neither messages nor the group are deleted if
// another member joins before the empty-group check executes.
async function cleanupEmptyGroup(env: Env, groupId: string): Promise<void> {
  await env.DB.batch([
    env.DB.prepare(`DELETE FROM group_messages WHERE group_id = ?1
      AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = ?1)`).bind(groupId),
    env.DB.prepare(`DELETE FROM groups WHERE id = ?1
      AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = ?1)`).bind(groupId),
  ]);
}

groups.delete('/:id/members/:userId', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const targetId = c.req.param('userId');

  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);
  if (membership.role !== 'admin' && userId !== targetId) {
    return c.json({ error: 'Only admins can remove other members' }, 403);
  }

  // Keep the authorization and last-admin guard in the same atomic DELETE,
  // so concurrent removals cannot both eliminate the final administrator.
  const deleted = await c.env.DB.prepare(`DELETE FROM group_members
    WHERE group_id = ?1 AND user_id = ?2
      AND EXISTS (SELECT 1 FROM group_members actor WHERE actor.group_id = ?1 AND actor.user_id = ?3
        AND (actor.role = 'admin' OR ?2 = ?3))
      AND (role != 'admin'
        OR NOT EXISTS (SELECT 1 FROM group_members other WHERE other.group_id = ?1 AND other.user_id != ?2)
        OR EXISTS (SELECT 1 FROM group_members other WHERE other.group_id = ?1 AND other.user_id != ?2 AND other.role = 'admin'))`)
    .bind(groupId, targetId, userId).run();
  if (!deleted.meta.changes) {
    const [actor, target] = await Promise.all([getMembership(c.env, groupId, userId), getMembership(c.env, groupId, targetId)]);
    if (!actor || (actor.role !== 'admin' && userId !== targetId)) return c.json({ error: 'Not authorized' }, 403);
    if (!target) return c.json({ error: 'Member not found' }, 404);
    return c.json({ error: 'Make someone else an admin before removing the last admin' }, 400);
  }
  await cleanupEmptyGroup(c.env, groupId);
  await broadcastToGroup(c.env, groupId, { type: 'group-member:left', group_id: groupId, user_id: targetId });
  await broadcastToUser(c.env, targetId, { type: 'group-member:left', group_id: groupId, user_id: targetId });
  return c.json({ success: true });
});

groups.post('/:id/leave', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');

  // The last-admin invariant must be checked by the same statement that
  // removes the member; two administrators can leave concurrently.
  const deleted = await c.env.DB.prepare(`DELETE FROM group_members
    WHERE group_id = ?1 AND user_id = ?2
      AND (role != 'admin'
        OR NOT EXISTS (SELECT 1 FROM group_members other WHERE other.group_id = ?1 AND other.user_id != ?2)
        OR EXISTS (SELECT 1 FROM group_members other WHERE other.group_id = ?1 AND other.user_id != ?2 AND other.role = 'admin'))`)
    .bind(groupId, userId).run();
  if (!deleted.meta.changes) {
    if (!(await getMembership(c.env, groupId, userId))) return c.json({ error: 'Not a member of this group' }, 403);
    return c.json({ error: 'Make someone else an admin before you leave' }, 400);
  }
  await cleanupEmptyGroup(c.env, groupId);
  await broadcastToGroup(c.env, groupId, { type: 'group-member:left', group_id: groupId, user_id: userId });
  await broadcastToUser(c.env, userId, { type: 'group-member:left', group_id: groupId, user_id: userId });
  return c.json({ success: true });
});

groups.patch('/:id/members/:userId', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const targetId = c.req.param('userId');
  const { role } = await c.req.json().catch(() => ({}));
  if (role !== 'admin' && role !== 'member') return c.json({ error: 'role must be admin or member' }, 400);
  if (userId === targetId) return c.json({ error: 'You cannot change your own role' }, 400);

  const membership = await getMembership(c.env, groupId, userId);
  if (membership?.role !== 'admin') return c.json({ error: 'Only admins can change member roles' }, 403);

  const changed = await c.env.DB.prepare(`UPDATE group_members SET role = ?1 WHERE group_id = ?2 AND user_id = ?3
    AND EXISTS (SELECT 1 FROM group_members actor WHERE actor.group_id = ?2 AND actor.user_id = ?4 AND actor.role = 'admin')`)
    .bind(role, groupId, targetId, userId).run();
  if (!changed.meta.changes) {
    if ((await getMembership(c.env, groupId, userId))?.role !== 'admin') return c.json({ error: 'Only admins can change member roles' }, 403);
    return c.json({ error: 'Member not found' }, 404);
  }
  await broadcastGroupProjection(c.env, groupId, 'group-member:updated');
  return c.json({ success: true });
});

groups.patch('/:id/mute', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const { muted } = await c.req.json().catch(() => ({}));

  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  await c.env.DB.prepare('UPDATE group_members SET is_muted = ? WHERE group_id = ? AND user_id = ?')
    .bind(muted ? 1 : 0, groupId, userId)
    .run();
  return c.json({ success: true });
});

groups.patch('/:id', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (membership?.role !== 'admin') return c.json({ error: 'Only admins can update group info' }, 403);

  const { name, description, avatar_url } = await c.req.json().catch(() => ({}));
  if (exceedsTextLimits({name,description,avatar_url}, {name:100,description:2048,avatar_url:2048})) return c.json({ error: 'Group field too long' }, 400);
  if (!(await mayAttachMedia(c.env, userId, [avatar_url]))) return c.json({ error: 'Cannot attach this file' }, 403);
  const current = await c.env.DB.prepare('SELECT * FROM groups WHERE id = ?').bind(groupId).first<any>();
  if (!current) return c.json({ error: 'Group not found' }, 404);

  const nextName = name !== undefined ? String(name).trim() : current.name;
  const nextDescription = description !== undefined ? (String(description).trim() || null) : current.description;
  const nextAvatar = avatar_url !== undefined ? avatar_url : current.avatar_url;

  const write = c.env.DB.prepare(
    `UPDATE groups SET name = ?1, description = ?2, avatar_url = ?3, updated_at = datetime('now') WHERE id = ?4
     AND EXISTS (SELECT 1 FROM group_members WHERE group_id = ?4 AND user_id = ?5 AND role = 'admin')`
  ).bind(nextName, nextDescription, nextAvatar, groupId, userId);
  const updated = avatar_url !== undefined
    ? (await c.env.DB.batch([write, ...guardedGroupAvatarReferences(c.env, groupId, userId, avatar_url)]))[0]
    : await write.run();
  if (!updated.meta.changes) return c.json({ error: 'Only admins can update group info' }, 403);

  const group = await groupWithMembers(c.env, groupId, userId);
  await broadcastGroupProjection(c.env, groupId, 'group:updated');
  return c.json({ success: true, group });
});

groups.delete('/:id', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (membership?.role !== 'admin') return c.json({ error: 'Only admins can delete the group' }, 403);

  const memberIds = await memberIdsOf(c.env, groupId);
  // D1 enforces foreign keys; the schema's cascades remove group messages,
  // members, read state and calls atomically with this guarded deletion.
  const deleted = await c.env.DB.prepare(`DELETE FROM groups WHERE id = ?1
    AND EXISTS (SELECT 1 FROM group_members WHERE group_id = ?1 AND user_id = ?2 AND role = 'admin')`)
    .bind(groupId, userId).run();
  if (!deleted.meta.changes) return c.json({ error: 'Only admins can delete the group' }, 403);

  await broadcastToGroup(c.env, groupId, { type: 'group:deleted', group_id: groupId }, memberIds);
  return c.json({ success: true });
});

groups.get('/:id/messages', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const before = c.req.query('before');
  const limit = Math.min(Number(c.req.query('limit')) || 50, 100);

  const { results } = before
    ? await c.env.DB.prepare(
        'SELECT * FROM group_messages WHERE group_id = ? AND created_at < ? ORDER BY created_at DESC LIMIT ?'
      )
        .bind(groupId, before, limit)
        .all()
    : await c.env.DB.prepare('SELECT * FROM group_messages WHERE group_id = ? ORDER BY created_at DESC LIMIT ?')
        .bind(groupId, limit)
        .all();

  const messages = (results ?? []).map(groupMessageJson).reverse();
  return c.json({ messages });
});

groups.post('/:id/messages', limitSend, async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id')!;
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const body = await c.req.json().catch(() => ({}));
  if ((body.content != null && typeof body.content !== 'string') || exceedsTextLimits(body, {content:65536, type:512, media_url:2048, thumbnail_url:65536, client_message_id:512, reply_to:512})) return c.json({ error: 'Invalid group message' }, 400);
  if (!(await mayAttachMedia(c.env, userId, [body.media_url, body.thumbnail_url]))) return c.json({ error: 'Cannot attach this file' }, 403);

  // Idempotent retry support via client_message_id, same pattern as 1:1
  // messages — a retried send (e.g. after a flaky connection) must not
  // create a second row.
  if (body.client_message_id) {
    const existing = await c.env.DB.prepare('SELECT * FROM group_messages WHERE group_id = ? AND sender_id = ? AND client_message_id = ?')
      .bind(groupId, userId, body.client_message_id)
      .first();
    if (existing) return c.json({ message: groupMessageJson(existing) });
  }

  const id = newId();
  const created_at = nowIso();

  // Same latency fix as the 1:1 send route: every field is already known,
  // so skip the write-then-read-back round trip and run the insert and
  // the broadcast concurrently instead of sequentially.
  const msg = groupMessageJson({
    id,
    group_id: groupId,
    sender_id: userId,
    content: body.content ?? '',
    type: body.type ?? 'text',
    media_url: body.media_url ?? null,
    thumbnail_url: body.thumbnail_url ?? null,
    duration: body.duration ?? null,
    client_message_id: body.client_message_id ?? null,
    reply_to: body.reply_to ?? null,
    is_edited: 0,
    edited_at: null,
    is_deleted: 0,
    deleted_for: null,
    reactions: null,
    created_at,
  });

  // The `duration` column (needed for group voice messages) was added by a
  // later migration (0003_group_voice_duration.sql) that has to be applied
  // by hand against each D1 database — it is NOT picked up automatically
  // by a plain worker code deploy. If it hasn't been run yet on whichever
  // database this request hits, an INSERT that references `duration`
  // fails outright with a "no such column" error — and since this insert
  // used to be unconditional, that took down *every* group message send,
  // not just voice ones, the instant this code shipped ahead of the
  // migration. Try the full insert first (so duration is saved once the
  // migration has run), and silently fall back to the pre-migration
  // column list if it hasn't, so sending never breaks either way.
  try {
    const write = c.env.DB.prepare(
      `INSERT INTO group_messages (id, group_id, sender_id, content, type, media_url, thumbnail_url, duration, client_message_id, reply_to, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        id,
        groupId,
        userId,
        body.content ?? '',
        body.type ?? 'text',
        body.media_url ?? null,
        body.thumbnail_url ?? null,
        body.duration ?? null,
        body.client_message_id ?? null,
        body.reply_to ?? null,
        created_at
      );
    await writeWithMediaReferences(c.env, write, 'group_message', id, userId, [body.media_url, body.thumbnail_url]);
  } catch (err) {
    if (!String(err).includes('no such column: duration') && !String(err).includes('no column named duration')) throw err;
    console.error('[groups] insert with duration column failed, retrying without it (migration 0003 likely not applied yet):', err);
    const write = c.env.DB.prepare(
      `INSERT INTO group_messages (id, group_id, sender_id, content, type, media_url, thumbnail_url, client_message_id, reply_to, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        id,
        groupId,
        userId,
        body.content ?? '',
        body.type ?? 'text',
        body.media_url ?? null,
        body.thumbnail_url ?? null,
        body.client_message_id ?? null,
        body.reply_to ?? null,
        created_at
      );
    await writeWithMediaReferences(c.env, write, 'group_message', id, userId, [body.media_url, body.thumbnail_url]);
  }

  await broadcastToGroup(c.env, groupId, { type: 'group-message:new', message: msg });

  return c.json({ message: msg }, 201);
});

// These three are deliberately NOT nested under /:id/messages/:messageId —
// the frontend only ever has a bare messageId handy at the call sites that
// need them (message action sheets), not the group it belongs to, so the
// group_id is looked up from the message row itself instead of the URL.
groups.post('/messages/:messageId/delete-for-me', async (c) => {
  const userId = c.get('userId');
  const messageId = c.req.param('messageId');
  const row = await c.env.DB.prepare('SELECT group_id, deleted_for FROM group_messages WHERE id = ?').bind(messageId).first<{ group_id: string; deleted_for: string | null }>();
  if (!row) return c.json({ error: 'Message not found' }, 404);
  if (!(await getMembership(c.env, row.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);

  const deletedFor: string[] = row.deleted_for ? JSON.parse(row.deleted_for) : [];
  if (!deletedFor.includes(userId)) deletedFor.push(userId);

  await c.env.DB.prepare('UPDATE group_messages SET deleted_for = ? WHERE id = ?').bind(JSON.stringify(deletedFor), messageId).run();
  return c.json({ success: true });
});

groups.post('/messages/:messageId/delete-for-everyone', async (c) => {
  const userId = c.get('userId');
  const messageId = c.req.param('messageId');

  const row = await c.env.DB.prepare('SELECT group_id, sender_id, created_at FROM group_messages WHERE id = ?').bind(messageId).first<{
    group_id: string;
    sender_id: string;
    created_at: string;
  }>();
  if (!row) return c.json({ error: 'Message not found' }, 404);
  if (!(await getMembership(c.env, row.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);
  if (row.sender_id !== userId) return c.json({ error: 'You can only delete your own messages' }, 403);

  const oneHourAgo = Date.now() - 60 * 60 * 1000;
  if (new Date(row.created_at).getTime() < oneHourAgo) {
    return c.json({ error: 'Cannot delete messages older than 1 hour' }, 400);
  }

  await c.env.DB.prepare("UPDATE group_messages SET is_deleted = 1, content = 'This message was deleted' WHERE id = ?")
    .bind(messageId)
    .run();

  await broadcastToGroup(c.env, row.group_id, { type: 'group-message:delete', group_id: row.group_id, message_id: messageId });
  return c.json({ success: true });
});

groups.post('/messages/:messageId/reaction', async (c) => {
  const userId = c.get('userId');
  const messageId = c.req.param('messageId');
  const { emoji } = await c.req.json().catch(() => ({}));
  if (typeof emoji !== 'string' || !emoji || exceedsTextLimits({emoji}, {emoji:32})) return c.json({ error: 'Invalid reaction' }, 400);
  if (!emoji) return c.json({ error: 'emoji is required' }, 400);

  const row = await c.env.DB.prepare('SELECT group_id, reactions FROM group_messages WHERE id = ?').bind(messageId).first<{
    group_id: string;
    reactions: string | null;
  }>();
  if (!row) return c.json({ error: 'Message not found' }, 404);
  if (!(await getMembership(c.env, row.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);

  const reactions: Record<string, string[]> = row.reactions ? JSON.parse(row.reactions) : {};
  const list = reactions[emoji] ?? [];
  reactions[emoji] = list.includes(userId) ? list.filter((u) => u !== userId) : [...list, userId];
  if (reactions[emoji].length === 0) delete reactions[emoji];

  await c.env.DB.prepare('UPDATE group_messages SET reactions = ? WHERE id = ?').bind(JSON.stringify(reactions), messageId).run();
  await broadcastToGroup(c.env, row.group_id, { type: 'group-message:reaction', group_id: row.group_id, message_id: messageId, reactions });
  return c.json({ reactions });
});

// POST /groups/:id/typing — ephemeral group typing indicator, no DB write.
// Mirrors /messages/typing exactly but fans out to every *other* member of
// the group (never the sender — there's no reason for a client to ever see
// its own "is typing" echo), so every open group chat window and the
// groups list both update instantly.
groups.post('/:id/typing', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const { is_typing } = await c.req.json().catch(() => ({}));
  const memberIds = (await memberIdsOf(c.env, groupId)).filter((id) => id !== userId);

  await broadcastToGroup(
    c.env,
    groupId,
    { type: 'group-typing', group_id: groupId, sender_id: userId, is_typing: !!is_typing },
    memberIds
  );

  return c.json({ success: true });
});

// POST /groups/:id/recording — ephemeral "recording a voice message"
// indicator for groups. Mirrors /messages/recording exactly, as its own
// event type so clients can show the distinct mic/"recording audio" state.
groups.post('/:id/recording', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const { is_recording } = await c.req.json().catch(() => ({}));
  const memberIds = (await memberIdsOf(c.env, groupId)).filter((id) => id !== userId);

  await broadcastToGroup(
    c.env,
    groupId,
    { type: 'group-recording', group_id: groupId, sender_id: userId, is_recording: !!is_recording },
    memberIds
  );

  return c.json({ success: true });
});

// POST /groups/:id/read — mark every message in this group as read by me,
// up to now. Two effects, same as 1:1's POST /messages/mark-read:
//   1. Always persists my own last_read_at (group_read_state), which is
//      what drives *my own* unread badge in the Groups list — purely
//      private bookkeeping, unaffected by anyone's read-receipts setting.
//   2. Only broadcasts `group-message:read` (so other open chats/lists can
//      flip a double-check tick on live) when *my* read_receipts privacy
//      setting is enabled — if it's off, other members never learn I read
//      anything, exactly like the 1:1 opt-out.
groups.post('/:id/read', async (c) => {
  const userId = c.get('userId');
  const groupId = c.req.param('id');
  const membership = await getMembership(c.env, groupId, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const readAt = nowIso();
  await c.env.DB.prepare(
    `INSERT INTO group_read_state (group_id, user_id, last_read_at) VALUES (?, ?, ?)
     ON CONFLICT(group_id, user_id) DO UPDATE SET last_read_at = excluded.last_read_at`
  )
    .bind(groupId, userId, readAt)
    .run();

  const readerSettings = await getPrivacySettings(c.env, userId);
  if (readerSettings.read_receipts) {
    const memberIds = (await memberIdsOf(c.env, groupId)).filter((id) => id !== userId);
    await broadcastToGroup(
      c.env,
      groupId,
      { type: 'group-message:read', group_id: groupId, reader_id: userId, read_at: readAt },
      memberIds
    );
  }

  return c.json({ success: true });
});

export default groups;
