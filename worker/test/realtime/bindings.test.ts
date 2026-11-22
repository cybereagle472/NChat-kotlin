import { env } from 'cloudflare:test';
import { expect, it } from 'vitest';

it('runs R2 and a realtime Durable Object locally', async () => {
  const key = crypto.randomUUID();
  await env.MEDIA.put(key, 'hello');
  expect(await (await env.MEDIA.get(key))?.text()).toBe('hello');
  await env.MEDIA.delete(key);
  expect(await env.MEDIA.get(key)).toBeNull();

  const stub = env.REALTIME.get(env.REALTIME.idFromName(key));
  const upgrade = await stub.fetch('https://internal/connect', { headers: { Upgrade: 'websocket' } });
  expect(upgrade.status).toBe(101);
  const socket = upgrade.webSocket!;
  socket.accept();
  const received = new Promise<string>((resolve) => socket.addEventListener('message', (event) => resolve(String(event.data)), { once: true }));
  const response = await stub.fetch('https://internal/broadcast', { method: 'POST', body: '{"type":"smoke"}' });
  expect(response.status).toBe(200);
  expect(await received).toBe('{"type":"smoke"}');
  socket.close(1000, 'finished');
});
