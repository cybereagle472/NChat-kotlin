import { Hono } from 'hono';
import type { Env, Variables } from '../types';
import { requireAuth } from '../middleware/auth';
import { ONLINE_THRESHOLD_MS } from '../lib/presence';

const admin = new Hono<{ Bindings: Env; Variables: Variables }>();
admin.use('*', requireAuth);

// A role must be explicitly granted to an existing account by the operator.
// An unverified email address alone is never proof of administration.
admin.get('/stats', async (c) => {
  const userId = c.get('userId');
  const me = await c.env.DB.prepare('SELECT is_admin FROM users WHERE id = ?').bind(userId).first<{ is_admin: number }>();
  if (!me || me.is_admin !== 1) return c.json({ error: 'Forbidden' }, 403);

  const onlineSince = Date.now() - ONLINE_THRESHOLD_MS;
  const [users, online, messages, groups] = await Promise.all([
    c.env.DB.prepare('SELECT COUNT(*) AS n FROM users').first<{ n: number }>(),
    c.env.DB.prepare('SELECT COUNT(*) AS n FROM presence WHERE last_active_at > ?').bind(onlineSince).first<{ n: number }>(),
    c.env.DB.prepare('SELECT COUNT(*) AS n FROM messages').first<{ n: number }>(),
    c.env.DB.prepare('SELECT COUNT(*) AS n FROM groups').first<{ n: number }>(),
  ]);

  return c.json({
    totalUsers: users?.n ?? 0,
    onlineUsers: online?.n ?? 0,
    totalMessages: messages?.n ?? 0,
    totalGroups: groups?.n ?? 0,
  });
});

export default admin;
