/**
 * RealtimeRoom — one Durable Object instance per "room" (a 1:1 conversation,
 * a group, a call, or a user's personal channel). This replaces Supabase
 * Realtime channels: clients open a WebSocket to a room, and the Worker
 * broadcasts events into that room (new message, typing, presence, call
 * signaling) whenever something happens via the REST routes.
 */
const OPEN = 1;

export class RealtimeRoom {
  private sockets: Set<WebSocket> = new Set();
  constructor(private state: DurableObjectState) {}

  // Rate-limit objects use this same deployed class under separate names.
  // Storage transactions serialize concurrent requests even across isolates.
  async alarm(): Promise<void> {
    await this.state.storage.transaction(async tx => {
      const bucket = await tx.get<{ expires: number }>('rate');
      if (bucket && bucket.expires > Date.now()) await tx.setAlarm(bucket.expires);
      else await tx.delete('rate');
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/limit' && request.method === 'POST') {
      const data = await request.json() as { limit: number; window: number; cost?: number };
      const cost = data.cost ?? 1;
      if (!Number.isInteger(data.limit) || data.limit < 1 || data.limit > 1000 ||
          !Number.isInteger(data.window) || data.window < 1000 || data.window > 86400000 ||
          !Number.isInteger(cost) || cost < 1 || cost > 1000) return new Response('bad limit', { status: 400 });
      const allowed = await this.state.storage.transaction(async tx => {
        const now = Date.now();
        let bucket = await tx.get<{ count: number; expires: number }>('rate');
        if (!bucket || bucket.expires <= now) bucket = { count: 0, expires: now + data.window };
        if (bucket.count + cost > data.limit) return false;
        bucket.count += cost;
        await tx.put('rate', bucket);
        await tx.setAlarm(bucket.expires);
        return true;
      });
      return new Response(null, { status: allowed ? 204 : 429 });
    }

    // Internal call from a Worker route: broadcast a JSON payload to everyone
    // currently connected to this room.
    if (url.pathname === '/broadcast' && request.method === 'POST') {
      const payload = await request.text();
      this.broadcast(payload);
      return new Response('ok');
    }

    // Client WebSocket upgrade.
    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('Expected websocket', { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    this.sockets.add(server);

    // Clients receive server-generated events only; never relay client input.

    const cleanup = () => this.sockets.delete(server);
    server.addEventListener('close', cleanup);
    server.addEventListener('error', cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }

  private broadcast(payload: string) {
    for (const ws of this.sockets) {
      if (ws.readyState === OPEN) {
        try {
          ws.send(payload);
        } catch {
          this.sockets.delete(ws);
        }
      }
    }
  }
}
