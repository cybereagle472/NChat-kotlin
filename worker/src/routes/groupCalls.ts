import { Hono } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { limitContact } from '../middleware/rate-limit';
import { newId, nowIso } from '../lib/id';
import { signalPayload } from '../lib/signalling';
import { broadcastToUser } from '../lib/realtime';
import { getMembership, memberIdsOf } from './groups';
import { applyPrivacyToList } from '../lib/privacy';

/**
 * Group voice/video calling.
 *
 * Mirrors routes/calls.ts's shape and conventions (same requireAuth setup,
 * same newId/nowIso/broadcastToUser helpers, same "signal is a dumb
 * passthrough" design) but a group call isn't a single caller/receiver
 * pair — any member can join or leave independently while it's running,
 * so it needs its own two tables (group_calls, group_call_participants —
 * see migrations/0005_group_calls.sql) instead of reusing `calls`.
 *
 * Media topology is a mesh: every joined participant holds a direct
 * WebRTC connection to every other joined participant (there is no SFU
 * media server in this app's infrastructure, same as the rest of its
 * calling stack). To avoid two participants racing to both send each
 * other an offer at the same time (glare), direction is fixed by a
 * simple rule enforced entirely on the client side: only already-joined
 * participants create an offer toward a newcomer; a newcomer only ever
 * answers, never offers. This route only needs to tell clients *when*
 * someone joined — which side of each pair offers is a client concern
 * (see src/services/groupCallService.ts).
 */
const groupCalls = new Hono<{ Bindings: Env; Variables: Variables }>();
groupCalls.use('*', requireAuth);

interface GroupCallRow {
  id: string;
  group_id: string;
  caller_id: string;
  call_type: string;
  status: string;
  created_at: string;
  ended_at: string | null;
}

async function getCallOr404(env: Env, id: string) {
  return env.DB.prepare('SELECT * FROM group_calls WHERE id = ?').bind(id).first<GroupCallRow>();
}

// Removed members retain a participant row so they can still hang up.
async function belongsToCall(env: Env, call: GroupCallRow, userId: string): Promise<boolean> {
  if (await getMembership(env, call.group_id, userId)) return true;
  return !!(await env.DB.prepare('SELECT 1 FROM group_call_participants WHERE call_id = ? AND user_id = ?')
    .bind(call.id, userId).first());
}

interface ParticipantBrief {
  user_id: string;
  full_name: string;
  avatar_url?: string;
}

async function joinedParticipantsOf(env: Env, callId: string, excludingUserId?: string): Promise<ParticipantBrief[]> {
  const { results } = await env.DB.prepare(
    `SELECT gcp.user_id, u.full_name, u.avatar_url
     FROM group_call_participants gcp
     JOIN users u ON u.id = gcp.user_id
     JOIN group_calls gc ON gc.id = gcp.call_id
     JOIN group_members gm ON gm.group_id = gc.group_id AND gm.user_id = gcp.user_id
     WHERE gcp.call_id = ? AND gcp.status = 'joined' AND gcp.user_id != ?`
  )
    .bind(callId, excludingUserId ?? '')
    .all<{ user_id: string; full_name: string; avatar_url: string | null }>();
  const rows = results ?? [];
  const profiles = await applyPrivacyToList(env, excludingUserId ?? '', rows.map(r => ({id:r.user_id, avatar_url:r.avatar_url})));
  return rows.map((r, i) => ({ user_id: r.user_id, full_name: r.full_name, avatar_url: profiles[i].avatar_url ?? undefined }));
}

function callToWire(call: GroupCallRow) {
  return {
    id: call.id,
    group_id: call.group_id,
    caller_id: call.caller_id,
    call_type: call.call_type,
    status: call.status,
    created_at: call.created_at,
  };
}

/**
 * Add/flip `userId` to 'joined' on `call` and tell everyone who was
 * already in the call so their client starts an offer toward them (see
 * the module doc for why only the already-joined side offers). Shared by
 * both "start a call" (for anyone besides the very first caller, when
 * the group already has an active call) and the explicit /join route.
 */
async function joinCall(env: Env, call: GroupCallRow, userId: string) {
  const now = nowIso();
  const joined = await env.DB.prepare(
    `INSERT INTO group_call_participants (call_id, user_id, status, joined_at)
     SELECT gc.id, ?2, 'joined', ?3 FROM group_calls gc
     JOIN group_members gm ON gm.group_id = gc.group_id AND gm.user_id = ?2
     WHERE gc.id = ?1 AND gc.status = 'active'
     ON CONFLICT(call_id, user_id) DO UPDATE SET status = 'joined', joined_at = excluded.joined_at, left_at = NULL`
  )
    .bind(call.id, userId, now)
    .run();
  if (!joined.meta.changes) return null;

  const alreadyJoined = await joinedParticipantsOf(env, call.id, userId);

  await Promise.all(
    alreadyJoined.map((p) =>
      broadcastToUser(env, p.user_id, {
        type: 'group-call:participant-joined',
        call_id: call.id,
        group_id: call.group_id,
        user_id: userId,
      })
    )
  );

  return { call: callToWire(call), participants: alreadyJoined };
}

// Start a call for a group, or — if that group already has one running —
// join it instead. Collapsing both into the same endpoint means the
// "Call" button in the group chat never has to know or care whether
// someone else already started one; it just always gets you into the
// group's one active call.
groupCalls.post('/', limitContact, async (c) => {
  const userId = c.get('userId');
  const { group_id, call_type } = await c.req.json().catch(() => ({}));
  if (!group_id || !call_type) return c.json({ error: 'group_id and call_type are required' }, 400);
  if (call_type !== 'audio' && call_type !== 'video') return c.json({ error: 'call_type must be audio or video' }, 400);

  const membership = await getMembership(c.env, group_id, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const existing = await c.env.DB.prepare(
    `SELECT * FROM group_calls WHERE group_id = ? AND status = 'active' ORDER BY created_at DESC LIMIT 1`
  )
    .bind(group_id)
    .first<GroupCallRow>();

  if (existing) {
    const result = await joinCall(c.env, existing, userId);
    if (!result) {
      if (!(await getMembership(c.env, existing.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);
      return c.json({ error: 'Call is no longer active' }, 409);
    }
    return c.json(result);
  }

  const id = newId();
  const created_at = nowIso();
  const memberIds = await memberIdsOf(c.env, group_id);

  // The conditional insert and participant setup are one D1 transaction.
  // A concurrent starter that lost the insert joins the winner instead.
  const [created] = await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT INTO group_calls (id, group_id, caller_id, call_type, status, created_at)
       SELECT ?1, ?2, ?3, ?4, 'active', ?5
       WHERE EXISTS (SELECT 1 FROM group_members WHERE group_id = ?2 AND user_id = ?3)
         AND NOT EXISTS (SELECT 1 FROM group_calls WHERE group_id = ?2 AND status = 'active')`
    ).bind(id, group_id, userId, call_type, created_at),
    c.env.DB.prepare(
      `INSERT INTO group_call_participants (call_id, user_id, status, joined_at)
       SELECT ?1, ?2, 'joined', ?3 WHERE EXISTS (SELECT 1 FROM group_calls WHERE id = ?1)`
    ).bind(id, userId, created_at),
    ...memberIds
      .filter((m) => m !== userId)
      .map((m) =>
        c.env.DB.prepare(`INSERT INTO group_call_participants (call_id, user_id, status)
          SELECT ?1, ?2, 'ringing' WHERE EXISTS (SELECT 1 FROM group_calls WHERE id = ?1)
            AND EXISTS (SELECT 1 FROM group_members WHERE group_id = ?3 AND user_id = ?2)`).bind(id, m, group_id)
      ),
  ]);
  if (!created.meta.changes) {
    if (!(await getMembership(c.env, group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);
    const winner = await c.env.DB.prepare(
      `SELECT * FROM group_calls WHERE group_id = ? AND status = 'active' ORDER BY created_at DESC LIMIT 1`
    ).bind(group_id).first<GroupCallRow>();
    if (!winner) return c.json({ error: 'Call is no longer active' }, 409);
    const result = await joinCall(c.env, winner, userId);
    if (!result) {
      if (!(await getMembership(c.env, winner.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);
      return c.json({ error: 'Call is no longer active' }, 409);
    }
    return c.json(result);
  }

  // Caller photos are shaped separately for each recipient's privacy view.
  const caller = await c.env.DB.prepare('SELECT full_name, avatar_url FROM users WHERE id = ?').bind(userId).first<{
    full_name: string;
    avatar_url: string | null;
  }>();

  const group = await c.env.DB.prepare('SELECT name, avatar_url FROM groups WHERE id = ?').bind(group_id).first<{
    name: string;
    avatar_url: string | null;
  }>();

  await Promise.all(
    memberIds
      .filter((m) => m !== userId)
      .map(async (m) => {
        if (!(await getMembership(c.env, group_id, m))) return;
        const [callerProfile] = await applyPrivacyToList(c.env, m, [{ id: userId, full_name: caller?.full_name, avatar_url: caller?.avatar_url }]);
        return broadcastToUser(c.env, m, {
          type: 'group-call:incoming',
          call: {
            id,
            group_id,
            group_name: group?.name,
            group_avatar_url: group?.avatar_url ?? undefined,
            call_type,
            caller_id: userId,
            caller_profile: caller ? callerProfile : undefined,
            created_at,
          },
        });
      })
  );

  return c.json({ call: { id, group_id, caller_id: userId, call_type, status: 'active', created_at }, participants: [] }, 201);
});

// Explicit accept, for someone who got the ringing group-call:incoming
// event and tapped Accept (as opposed to opening the group and tapping
// Call themselves, which goes through POST / above). Also doubles as
// "join an in-progress call" for a member who wasn't on the original
// ringing list at all (joined the group, or just opening the chat and
// seeing the call already happening) — same ON CONFLICT upsert either way.
groupCalls.post('/:id/join', async (c) => {
  const userId = c.get('userId');
  const id = c.req.param('id');
  const call = await getCallOr404(c.env, id);
  if (!call) return c.json({ error: 'Call not found' }, 404);
  if (call.status !== 'active') return c.json({ error: 'This call has ended' }, 409);

  const membership = await getMembership(c.env, call.group_id, userId);
  if (!membership) return c.json({ error: 'Not a member of this group' }, 403);

  const result = await joinCall(c.env, call, userId);
  if (!result) {
    if (!(await getMembership(c.env, call.group_id, userId))) return c.json({ error: 'Not a member of this group' }, 403);
    return c.json({ error: 'Call is no longer active' }, 409);
  }
  return c.json(result);
});

// Directed signaling passthrough — unlike 1:1's /:id/signal (which can
// infer "the other participant" because there are only ever two), a
// group call can have many participants, so the sender names exactly who
// a given offer/answer/ICE candidate is for.
groupCalls.post('/:id/signal', async (c) => {
  const userId = c.get('userId');
  const id = c.req.param('id');
  const body = await c.req.json().catch(() => ({}));
  const to = body?.to as string | undefined;
  if (!to) return c.json({ error: 'to is required' }, 400);

  const call = await getCallOr404(c.env, id);
  if (!call) return c.json({ error: 'Call not found' }, 404);

  const membership = await getMembership(c.env, call.group_id, userId);
  if (!membership) return c.json({ error: 'Not authorized' }, 403);

  if (call.status !== 'active') return c.json({ success: true });
  if (!(await getMembership(c.env, call.group_id, to))) return c.json({ error: 'Not authorized' }, 403);
  await broadcastToUser(c.env, to, { ...signalPayload(body), type: 'group-call:signal', call_id: id, from: userId });
  return c.json({ success: true });
});

groupCalls.post('/:id/decline', async (c) => {
  const userId = c.get('userId');
  const id = c.req.param('id');
  const call = await getCallOr404(c.env, id);
  if (!call) return c.json({ error: 'Call not found' }, 404);
  if (!(await getMembership(c.env, call.group_id, userId))) return c.json({ error: 'Not authorized' }, 403);

  // Only an outstanding invitation to an active call can be declined.
  // Check membership and call state in the update, not only in a prior read.
  const updated = await c.env.DB.prepare(`UPDATE group_call_participants SET status = 'declined'
    WHERE call_id = ?1 AND user_id = ?2 AND status = 'ringing'
      AND EXISTS (SELECT 1 FROM group_calls gc JOIN group_members gm ON gm.group_id = gc.group_id
        WHERE gc.id = ?1 AND gc.status = 'active' AND gm.user_id = ?2)`)
    .bind(id, userId).run();
  if (!updated.meta.changes) {
    if (!(await getMembership(c.env, call.group_id, userId))) return c.json({ error: 'Not authorized' }, 403);
    return c.json({ error: 'Call is no longer ringing' }, 409);
  }

  if (await getMembership(c.env, call.group_id, call.caller_id)) {
    await broadcastToUser(c.env, call.caller_id, {
      type: 'group-call:participant-declined',
      call_id: id,
      group_id: call.group_id,
      user_id: userId,
    });
  }
  return c.json({ success: true });
});

// Leave an active call. If that was the last joined participant, the
// call itself ends — and anyone still ringing (never answered either
// way) is told to stop, since there's nothing left to join.
groupCalls.post('/:id/leave', async (c) => {
  const userId = c.get('userId');
  const id = c.req.param('id');
  const call = await getCallOr404(c.env, id);
  if (!call) return c.json({ error: 'Call not found' }, 404);
  if (!(await belongsToCall(c.env, call, userId))) return c.json({ error: 'Not authorized' }, 403);

  const now = nowIso();
  await c.env.DB.prepare(`UPDATE group_call_participants SET status = 'left', left_at = ? WHERE call_id = ? AND user_id = ?`)
    .bind(now, id, userId)
    .run();

  const stillJoined = await joinedParticipantsOf(c.env, id, userId);

  await Promise.all(
    stillJoined.map((p) =>
      broadcastToUser(c.env, p.user_id, {
        type: 'group-call:participant-left',
        call_id: id,
        group_id: call.group_id,
        user_id: userId,
      })
    )
  );

  if (stillJoined.length === 0 && call.status === 'active') {
    // A join can occur after stillJoined was read. Only end while there
    // are no joined current members, checked atomically with the update.
    const ended = await c.env.DB.prepare(`UPDATE group_calls SET status = 'ended', ended_at = ?1
      WHERE id = ?2 AND status = 'active' AND NOT EXISTS (
        SELECT 1 FROM group_call_participants p
        JOIN group_calls gc ON gc.id = p.call_id
        JOIN group_members gm ON gm.group_id = gc.group_id AND gm.user_id = p.user_id
        WHERE p.call_id = ?2 AND p.status = 'joined'
      )`).bind(now, id).run();
    if (!ended.meta.changes) return c.json({ success: true });

    const { results: stillRinging } = await c.env.DB.prepare(
      `SELECT gcp.user_id FROM group_call_participants gcp JOIN group_calls gc ON gc.id = gcp.call_id JOIN group_members gm ON gm.group_id = gc.group_id AND gm.user_id = gcp.user_id WHERE gcp.call_id = ? AND gcp.status = 'ringing'`
    )
      .bind(id)
      .all<{ user_id: string }>();

    await Promise.all(
      (stillRinging ?? []).map((r) =>
        broadcastToUser(c.env, r.user_id, { type: 'group-call:ended', call_id: id, group_id: call.group_id })
      )
    );
  }

  return c.json({ success: true });
});

export default groupCalls;
