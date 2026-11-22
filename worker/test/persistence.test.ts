import { describe, expect, it } from 'vitest';
import app from '../src/index';
import { signJWT } from '../src/lib/jwt';
import type { Env } from '../src/types';

describe('message events follow durable writes', () => {
  async function attempt(method: 'POST' | 'PATCH') {
    const events: string[] = [];
    const secret = 'local-test-secret';
    const token = await signJWT({sub:'sender'}, secret, 300);
    const database = {
      prepare: (query: string) => ({
        bind: (..._args: unknown[]) => ({
          first: async () => query.includes('friendships') ? { ok: 1 } : {
            id:'msg', sender_id:'sender', receiver_id:'recipient', type:'text', content:'original', created_at:new Date().toISOString(),
          },
          // Let the realtime operation complete before an injected write failure.
          run: async () => { await new Promise(resolve => setTimeout(resolve, 20)); throw new Error('injected write failure'); },
        }),
      }),
    };
    const realtime = { idFromName: (name: string) => name, get: (_id: string) => ({ fetch: async (_url: string, init: RequestInit) => {events.push(String(init.body)); return new Response('ok');} }) };
    const bindings = { DB: database, REALTIME: realtime, JWT_SECRET: secret, PUBLIC_API_URL: 'https://example.test' } as unknown as Env;
    const path = method === 'POST' ? '/messages' : '/messages/msg';
    const response = await app.request(`https://example.test${path}`, {
      method, headers: {Authorization:`Bearer ${token}`, 'Content-Type':'application/json'},
      body: JSON.stringify(method === 'POST' ? {receiver_id:'recipient',content:'new message'} : {content:'edited'}),
    }, bindings);
    expect(response.status).toBeGreaterThanOrEqual(500);
    expect(events).toEqual([]);
  }
  it('does not publish a failed insertion', async () => { await attempt('POST'); });
  it('does not publish a failed edit', async () => { await attempt('PATCH'); });
});
