import { env, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { signJWT } from '../../src/lib/jwt';

const secret = 'local-tests-only-not-a-production-secret';
async function connect(room: string, token?: string) {
  const query = new URLSearchParams({ room });
  if (token !== undefined) query.set('token', token);
  return SELF.fetch(`https://example.test/realtime/connect?${query}`, { headers: { Upgrade: 'websocket' } });
}

describe('realtime security', () => {
  it('accepts only the signed-in user’s personal room', async () => {
    const id = crypto.randomUUID();
    const token = await signJWT({ sub: id }, secret, 900);
    expect((await connect(`user:${id}`)).status).toBe(400);
    expect((await connect(`user:${id}`, 'bad-token')).status).toBe(401);
    for (const room of ['user:someone-else', `dm:${id}:other`, 'group:other', 'call:other', 'anything']) {
      const response = await connect(room, token);
      if (response.webSocket) { response.webSocket.accept(); response.webSocket.close(1000, 'finished'); }
      expect(response.status, room).toBe(403);
    }
    const allowed = await connect(`user:${id}`, token);
    expect(allowed.status).toBe(101);
    allowed.webSocket!.accept();
    allowed.webSocket!.close(1000, 'finished');
  });

  it('refuses a refresh token at the socket entry point', async () => {
    const id = crypto.randomUUID();
    const token = await signJWT({ sub: id, sid: crypto.randomUUID() }, secret, 30 * 86400);
    const response = await connect(`user:${id}`, token);
    if (response.webSocket) { response.webSocket.accept(); response.webSocket.close(1000, 'finished'); }
    expect(response.status).toBe(401);
  });

  it('does not relay client messages but still delivers server broadcasts', async () => {
    const id = crypto.randomUUID();
    const token = await signJWT({ sub: id }, secret, 900);
    const room = `user:${id}`;
    const first = await connect(room, token);
    const second = await connect(room, token);
    expect(first.status).toBe(101);
    expect(second.status).toBe(101);
    const sender = first.webSocket!;
    const receiver = second.webSocket!;
    sender.accept(); receiver.accept();
    const received: string[] = [];
    const marker = crypto.randomUUID();
    const broadcastReceived = new Promise<void>((resolve) => receiver.addEventListener('message', (event) => {
      received.push(String(event.data));
      if (String(event.data) === marker) resolve();
    }));
    sender.send('forged-event');
    // Let the incoming WebSocket event be handled before the server broadcast.
    await new Promise((resolve) => setTimeout(resolve, 50));
    const stub = env.REALTIME.get(env.REALTIME.idFromName(room));
    expect((await stub.fetch('https://internal/broadcast', { method: 'POST', body: marker })).status).toBe(200);
    await broadcastReceived;
    expect(received).toEqual([marker]);
    sender.close(1000, 'finished'); receiver.close(1000, 'finished');
  });
});
