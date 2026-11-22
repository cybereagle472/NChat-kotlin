import type { Env } from '../types';

/** Hash identities before naming globally shared counters; never trust X-Forwarded-For. */
export async function consumeLimit(env: Env, scope: string, identity: string, limit: number, window: number): Promise<boolean> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(identity)));
  const hash = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const id = env.REALTIME.idFromName(`rate:${scope}:${hash}`);
  const response = await env.REALTIME.get(id).fetch('https://rate.internal/limit', {
    method: 'POST', body: JSON.stringify({ limit, window }), headers: { 'Content-Type': 'application/json' },
  });
  if (response.status === 429) return false;
  if (!response.ok) throw new Error('Rate limiter unavailable');
  return true;
}
