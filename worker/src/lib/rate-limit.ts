import type { Env } from '../types';

/** Hash identities before naming globally shared counters; never trust X-Forwarded-For. */
export async function consumeLimit(env: Env, scope: string, identity: string, limit: number, window: number, cost = 1): Promise<boolean> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(identity)));
  const hash = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const id = env.REALTIME.idFromName(`rate:${scope}:${hash}`);
  const response = await env.REALTIME.get(id).fetch('https://rate.internal/limit', {
    method: 'POST', body: JSON.stringify({ limit, window, cost }), headers: { 'Content-Type': 'application/json' },
  });
  if (response.status === 429) return false;
  if (!response.ok) throw new Error('Rate limiter unavailable');
  return true;
}

/** Trust only Cloudflare's edge-provided address. Normalize IPv6 to /64 so
 * changing individual addresses inside one allocation does not reset limits. */
export function clientAddressBucket(address: string | undefined): string | null {
  if (!address || address.length > 45 || !/^[0-9a-f:.]+$/i.test(address)) return null;
  if (!address.includes(':')) {
    const parts = address.split('.');
    if (parts.length !== 4 || parts.some(p => !/^\d{1,3}$/.test(p) || (p.length > 1 && p[0] === '0') || Number(p) > 255)) return null;
    return parts.join('.');
  }
  try {
    const host = new URL(`http://[${address}]/`).hostname.slice(1, -1);
    const [left, right] = host.split('::');
    const before = left ? left.split(':') : [];
    const after = right ? right.split(':') : [];
    const zeros = right === undefined ? 0 : 8 - before.length - after.length;
    const groups = [...before, ...Array(zeros).fill('0'), ...after].map(group => parseInt(group, 16));
    if (groups.length !== 8 || groups.some(group => !Number.isInteger(group) || group < 0 || group > 65535)) return null;
    if (groups.slice(0, 5).every(group => group === 0) && groups[5] === 0xffff) {
      return [groups[6] >> 8, groups[6] & 255, groups[7] >> 8, groups[7] & 255].join('.');
    }
    return groups.slice(0, 4).map(group => group.toString(16)).join(':') + '::/64';
  } catch {
    return null;
  }
}
