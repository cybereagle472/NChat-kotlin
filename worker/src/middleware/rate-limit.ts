import type { Context, Next } from 'hono';
import type { Env, Variables } from '../types';
import { consumeLimit } from '../lib/rate-limit';

/** Authenticated budgets are shared across all routes in each category. */
function userBudget(scope: 'send' | 'contact' | 'signal', limit: number, window: number) {
  return async (c: Context<{Bindings: Env; Variables: Variables}>, next: Next) => {
    const userId = c.get('userId');
    if (!userId) return c.json({ error: 'Unauthorized' }, 401);
    if (!(await consumeLimit(c.env, scope, userId, limit, window))) return c.json({ error: 'Too many requests' }, 429);
    await next();
  };
}

export const limitSend = userBudget('send', 300, 60_000);
export const limitContact = userBudget('contact', 30, 5 * 60_000);

// WebRTC trickles ICE candidates; use a separate, higher budget so signalling
// does not consume message or contact-action allowance.
export const limitSignal = userBudget('signal', 600, 60_000);
