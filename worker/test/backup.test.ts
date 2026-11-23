import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';
import app from '../src/index';
import type { Env } from '../src/types';
import { buildDumpStatements, keysToDelete, runBackup } from '../src/lib/backup';

/**
 * Daily database backup (the owner's 2026-11-23 decision: Cloudflare-only,
 * dashboard-visible, 7 daily + 4 weekly copies, auto-deleted by age).
 * Red first: these tests describe the behaviour before the module exists.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

async function gunzip(bytes: Uint8Array): Promise<string> {
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Response(stream).text();
}

async function statusJson(): Promise<Record<string, unknown>> {
  const object = await env.BACKUPS.get('status.json');
  expect(object).not.toBeNull();
  return JSON.parse(await object!.text()) as Record<string, unknown>;
}

describe('backup retention: 7 daily + 4 weekly, older deleted', () => {
  const dated = (iso: string) => `nchat-${iso}.sql.gz`;

  it('keeps the last 7 days, one file per ISO week up to 4 weeks, deletes the rest', () => {
    const now = Date.parse('2026-06-15T12:00:00Z'); // a Monday
    const keys = [
      // Daily window: ages 0–6 all kept.
      ...['09', '10', '11', '12', '13', '14', '15'].map((d) => dated(`2026-06-${d}`)),
      // Age 7–28: only each ISO week's latest file survives.
      dated('2026-06-08'), // W24 latest (age 7) — kept
      dated('2026-06-07'), // same week, older — deleted
      dated('2026-06-01'), // W23 latest (age 14) — kept
      dated('2026-05-30'), // W22 latest (age 16) — kept
      dated('2026-05-18'), // W21 latest (age 28, boundary) — kept
      // Beyond 4 weeks — deleted whatever week they sit in.
      dated('2026-05-17'), // age 29 — deleted
      dated('2026-05-11'), // age 35 — deleted
      // Never candidates for deletion.
      'latest.sql.gz',
      'status.json',
    ];
    expect(keysToDelete(keys, now).sort()).toEqual([
      dated('2026-05-11'),
      dated('2026-05-17'),
      dated('2026-06-07'),
    ].sort());
  });

  it('groups the New Year days into the right ISO weeks', () => {
    const now = Date.parse('2026-01-10T12:00:00Z');
    const keys = [
      ...['04', '05', '06', '07', '08', '09', '10'].map((d) => dated(`2026-01-${d}`)),
      dated('2025-12-31'), // 2026-W01 latest (age 10) — kept
      dated('2025-12-29'), // same ISO week as 12-31 despite the year flip — deleted
      dated('2025-12-28'), // 2025-W53 latest (age 13) — kept
    ];
    expect(keysToDelete(keys, now)).toEqual([dated('2025-12-29')]);
  });
});

describe('database dump round-trip', () => {
  it('restores every row into a fresh database, nasty text included', async () => {
    // Wipe + migrate the restore target.
    const tables = await env.DB_RESTORE.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid DESC"
    ).all<{ name: string }>();
    for (const row of tables.results ?? []) {
      await env.DB_RESTORE.prepare(`DROP TABLE IF EXISTS "${row.name.replace(/"/g, '""')}"`).run();
    }
    await applyD1Migrations(env.DB_RESTORE, env.TEST_MIGRATIONS);

    // Seed the source with rows that stress quoting: apostrophes, semicolons,
    // double quotes, newlines, unicode, and a NULL.
    const nasty = `it's; a "test"\nline2 — emoji 👍 and <tag> & %`;
    const userId = crypto.randomUUID(), peerId = crypto.randomUUID();
    await env.DB.prepare('INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)')
      .bind(userId, 'roundtrip-a@example.test', 'hash-a', 'Round Trip A').run();
    await env.DB.prepare('INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)')
      .bind(peerId, 'roundtrip-b@example.test', 'hash-b', "O'Brien; DROP").run();
    await env.DB.prepare('INSERT INTO friendships (id, user1_id, user2_id) VALUES (?, ?, ?)')
      .bind(crypto.randomUUID(), userId, peerId).run();
    await env.DB.prepare('INSERT INTO messages (id, sender_id, receiver_id, content, deleted_for) VALUES (?, ?, ?, ?, ?)')
      .bind(crypto.randomUUID(), userId, peerId, nasty, null).run();

    const before = await countRows(env.DB);
    const statements = await buildDumpStatements(env.DB);
    expect(statements.length).toBeGreaterThan(before.total);

    for (const statement of statements) await env.DB_RESTORE.prepare(statement).run();

    const after = await countRows(env.DB_RESTORE);
    expect(after).toEqual(before);
    const restored = await env.DB_RESTORE.prepare('SELECT content FROM messages WHERE content IS NOT NULL AND content != ?')
      .bind('').first<{ content: string }>();
    expect(restored?.content).toBe(nasty);
  }, 60000);
});

async function countRows(db: D1Database): Promise<Record<string, number> & { total: number }> {
  const tables = await db.prepare(
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'd1_migrations' ORDER BY name"
  ).all<{ name: string }>();
  const counts: Record<string, number> & { total: number } = { total: 0 };
  for (const row of tables.results ?? []) {
    const result = await db.prepare(`SELECT COUNT(*) AS n FROM "${row.name.replace(/"/g, '""')}"`).first<{ n: number }>();
    counts[row.name] = result?.n ?? 0;
    counts.total += result?.n ?? 0;
  }
  return counts;
}

describe('runBackup', () => {
  it('uploads a dated dump and latest copy, prunes old files, records status', async () => {
    const now = new Date('2026-06-15T03:17:00.000Z');
    // An expired file that must be pruned by this run.
    await env.BACKUPS.put('nchat-2026-05-11.sql.gz', new Uint8Array([1, 2, 3]));

    const result = await runBackup(env as Env, now);
    expect(result).toEqual({ ok: true, key: 'nchat-2026-06-15.sql.gz', bytes: expect.any(Number) });

    expect(await env.BACKUPS.head('nchat-2026-06-15.sql.gz')).not.toBeNull();
    expect(await env.BACKUPS.head('latest.sql.gz')).not.toBeNull();
    expect(await env.BACKUPS.head('nchat-2026-05-11.sql.gz')).toBeNull();

    const status = await statusJson();
    expect(status.last_success_utc).toBe('2026-06-15T03:17:00.000Z');
    expect(status.key).toBe('nchat-2026-06-15.sql.gz');

    const bytes = new Uint8Array(await (await env.BACKUPS.get('nchat-2026-06-15.sql.gz')!.arrayBuffer()));
    const sql = await gunzip(bytes);
    expect(sql).toContain('-- NChat D1 backup');
    expect(sql).toContain('CREATE TABLE');
    expect(sql).toContain('INSERT INTO');
  }, 60000);

  it('records the failure in status and does not throw', async () => {
    const now = new Date('2026-06-16T03:17:00.000Z');
    const broken = {
      ...env,
      DB: { prepare: () => { throw new Error('db down'); } },
    } as unknown as Env;
    const result = await runBackup(broken, now);
    expect(result.ok).toBe(false);
    const status = await statusJson();
    expect(status.last_error_utc).toBe('2026-06-16T03:17:00.000Z');
    expect(String(status.error)).toContain('db down');
    // A failed run must not have touched the dated dump for its day.
    expect(await env.BACKUPS.head('nchat-2026-06-16.sql.gz')).toBeNull();
  }, 30000);
});

describe('cron wiring', () => {
  it('the default export carries a scheduled handler for the daily trigger', () => {
    expect(typeof (app as unknown as { scheduled?: unknown }).scheduled).toBe('function');
  });
});
