import { Hono } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import type { Env, Variables } from './types';
import { verifyAccessJWT } from './lib/jwt';
import { runBackup } from './lib/backup';

import authRoutes from './routes/auth';
import userRoutes from './routes/users';
import friendRoutes from './routes/friends';
import groupRoutes from './routes/groups';
import messageRoutes from './routes/messages';
import callRoutes from './routes/calls';
import groupCallRoutes from './routes/groupCalls';
import statusRoutes from './routes/status';
import mediaRoutes from './routes/media';
import privacyRoutes from './routes/privacy';
import adminRoutes from './routes/admin';

export { RealtimeRoom } from './durable-objects/RealtimeRoom';

const app = new Hono<{ Bindings: Env; Variables: Variables }>();

// Bound input before JSON/multipart parsing. The supported Android app limits
// status media to 48 MiB and chat attachments to 24 MiB; allow multipart
// overhead while keeping Worker memory use bounded.
const jsonBodyLimit = bodyLimit({ maxSize: 256 * 1024 });
const uploadBodyLimit = bodyLimit({ maxSize: 50 * 1024 * 1024 });
app.use('*', (c, next) => (c.req.path === '/media/upload' ? uploadBodyLimit : jsonBodyLimit)(c, next));

// The supported Android client is not subject to browser CORS. Do not grant
// cross-origin browser access by default; same-origin HTTP remains available.

app.get('/', (c) => c.json({ ok: true, service: 'nchat-api' }));
app.get('/health', (c) => c.json({ ok: true }));

app.route('/auth', authRoutes);
app.route('/users', userRoutes);
app.route('/friends', friendRoutes);
app.route('/groups', groupRoutes);
app.route('/messages', messageRoutes);
app.route('/calls', callRoutes);
app.route('/group-calls', groupCallRoutes);
app.route('/status', statusRoutes);
app.route('/media', mediaRoutes);
app.route('/privacy', privacyRoutes);
app.route('/admin', adminRoutes);

// WebSocket entry point: GET /realtime/connect?room=<roomId>&token=<jwt>
// Browsers can't send an Authorization header on a WS upgrade, so the access
// token travels as a query param here and is verified before we ever hand
// the connection off to the Durable Object.
app.get('/realtime/connect', async (c) => {
  const room = c.req.query('room');
  const token = c.req.query('token');
  if (!room || !token) return c.json({ error: 'room and token query params are required' }, 400);

  const payload = await verifyAccessJWT(token, c.env.JWT_SECRET);
  if (!payload?.sub) return c.json({ error: 'Invalid or expired token' }, 401);

  // Clients may listen only to their own personal event stream. Other
  // rooms can still be used internally but have no client authorization.
  if (room !== `user:${payload.sub}`) {
    return c.json({ error: 'Forbidden' }, 403);
  }

  const id = c.env.REALTIME.idFromName(room);

  const stub = c.env.REALTIME.get(id);
  return stub.fetch(c.req.raw);
});

// Daily database backup cron (wrangler.toml [triggers]): dump → gzip → R2 →
// prune by age. Attached to the default export so the runtime finds
// `scheduled` beside `fetch`; tests call runBackup directly.
(app as unknown as {
  scheduled: (event: ScheduledController, env: Env) => Promise<unknown>;
}).scheduled = (_event, cronEnv) => runBackup(cronEnv);

export default app;
