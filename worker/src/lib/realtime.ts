import type { Env } from '../types';

/** Canonical room id for a 1:1 conversation between two users (order-independent). */
export function roomIdFor(userA: string, userB: string): string {
  return `dm:${[userA, userB].sort().join(':')}`;
}

export function groupRoomId(groupId: string): string {
  return `group:${groupId}`;
}

export function callRoomId(callId: string): string {
  return `call:${callId}`;
}

export function userRoomId(userId: string): string {
  return `user:${userId}`;
}

/**
 * Send an event into a realtime room's Durable Object so any connected
 * WebSocket clients (open chat windows, typing indicators, etc.) get it
 * immediately. This is how "Supabase Realtime" style fan-out works here.
 *
 * This runs *after* the database write has committed, so a delivery failure
 * must never fail the request: that would tell clients the write failed,
 * make them retry committed work, and deliver events out of order (the
 * post-write notification "loss/order" races of the 2026-11-23 Phase 1
 * sign-off). The miss is logged instead; the client's resync path — and the
 * durable event sequence Phase 3 owns — covers it. Rate limiting and
 * connection setup deliberately do NOT use this helper: those failures must
 * still propagate.
 */
export async function broadcastToRoom(env: Env, roomId: string, payload: unknown): Promise<void> {
  try {
    const id = env.REALTIME.idFromName(roomId);
    const stub = env.REALTIME.get(id);
    const response = await stub.fetch('https://do/broadcast', {
      method: 'POST',
      body: JSON.stringify(payload),
      headers: { 'Content-Type': 'application/json' },
    });
    if (!response.ok) {
      console.error(`realtime broadcast rejected (${response.status}) for room ${roomId}`);
    }
  } catch (error) {
    console.error(`realtime broadcast failed for room ${roomId}`, error);
  }
}

/** Convenience wrapper: send an event straight to a user's personal room. */
export async function broadcastToUser(env: Env, userId: string, payload: unknown): Promise<void> {
  await broadcastToRoom(env, userRoomId(userId), payload);
}

