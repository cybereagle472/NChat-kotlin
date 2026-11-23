import type { Env } from '../types';

/**
 * May `userId` exchange direct messages with `otherId`? True only for
 * accepted contacts with no block in either direction — the one rule every
 * direct-message route and the E2EE key bundle share.
 */
export async function mayMessage(env: Env, userId: string, otherId: string): Promise<boolean> {
  if (userId === otherId) return false;
  const row = await env.DB.prepare(`SELECT 1 FROM friendships f
    WHERE ((f.user1_id = ?1 AND f.user2_id = ?2) OR (f.user1_id = ?2 AND f.user2_id = ?1))
    AND NOT EXISTS (SELECT 1 FROM blocked_users b WHERE (b.user_id = ?1 AND b.blocked_user_id = ?2)
      OR (b.user_id = ?2 AND b.blocked_user_id = ?1)) LIMIT 1`).bind(userId, otherId).first();
  return !!row;
}
