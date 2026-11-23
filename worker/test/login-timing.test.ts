import { describe, expect, it, vi } from 'vitest';
import type { Env } from '../src/types';

vi.mock('../src/lib/password', async (original) => {
  const actual = await original<typeof import('../src/lib/password')>();
  return { ...actual, verifyPassword: vi.fn(async () => false) };
});

import app from '../src/index';
import { verifyPassword } from '../src/lib/password';

describe('credential verification', () => {
  it('does password work for unknown as well as existing accounts', async () => {
    vi.mocked(verifyPassword).mockClear();
    const passwordHash = '100000:000102030405060708090a0b0c0d0e0f:8f8b3645a5523e2521f58dcdb91b0d009e1b1a80c4d22d627d5ed7173c9c6607';
    const database = { prepare: (_query:string) => ({ bind: (email:string) => ({ first: async () => email === 'known@example.test' ? {id:'known',password_hash:passwordHash} : null }) }) };
    const realtime = { idFromName: (name:string) => name, get: (_id:string) => ({ fetch: async () => new Response(null, {status:204}) }) };
    const bindings = { DB: database, REALTIME: realtime, JWT_SECRET:'test-secret', PUBLIC_API_URL:'https://example.test' } as unknown as Env;
    for (const email of ['known@example.test', 'unknown@example.test']) {
      const response = await app.request('https://example.test/auth/login', {method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP':'192.0.2.1'},body:JSON.stringify({email,password:'wrongpass'})}, bindings);
      expect(response.status).toBe(401);
    }
    expect(vi.mocked(verifyPassword)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(verifyPassword).mock.calls[1][1]).toMatch(/^100000:[0-9a-f]{32}:[0-9a-f]{64}$/);
  });
});
