import { describe, expect, it, vi } from 'vitest';
import { broadcastToRoom } from '../src/lib/realtime';

/**
 * Post-write notification delivery — the Phase 1 residual documented on
 * 2026-11-23 ("post-write notification authorization/order/loss races").
 *
 * The database write has already committed when a realtime event is sent.
 * A delivery failure must never surface as a failed request: that would make
 * clients believe the write failed, retry committed work, and apply events
 * out of order. The miss itself is logged; client resync covers it.
 */
describe('post-write notification delivery', () => {
  it('a realtime-object failure resolves instead of failing the caller', async () => {
    const env = {
      REALTIME: {
        idFromName: () => 'room-stub',
        get: () => ({
          fetch: async () => {
            throw new Error('realtime object unavailable');
          },
        }),
      },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any;
    await expect(broadcastToRoom(env, 'dm:a:b', { type: 'message:new' })).resolves.toBeUndefined();
  });

  it('a rejected delivery is logged, not raised to the caller', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const env = {
        REALTIME: {
          idFromName: () => 'room-stub',
          get: () => ({ fetch: async () => new Response('object error', { status: 500 }) }),
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any;
      await expect(broadcastToRoom(env, 'dm:a:b', { type: 'message:new' })).resolves.toBeUndefined();
      expect(spy).toHaveBeenCalled();
    } finally {
      spy.mockRestore();
    }
  });
});
