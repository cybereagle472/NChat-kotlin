import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';
import app from '../src/index';
import type { Env } from '../src/types';
import {
  BACKUP_PUBLIC_KEY_PEM,
  assembleDump,
  buildDumpStatements,
  decryptBackup,
  decodePem,
  encryptBackup,
  gzip,
  keysToDelete,
  runBackup,
} from '../src/lib/backup';

/**
 * Daily database backup, end-to-end encrypted like the owner asked for on
 * 2026-11-23 ("fully encrypted like WhatsApp"): the nightly job encrypts with
 * a committed PUBLIC key only; the private half exists solely with the owner
 * and is never in this repo or on Cloudflare — the platform stores ciphertext
 * it mathematically cannot read.
 *
 * Container format NCHB1: magic(5) | u16BE wrappedKeyLen | RSA-OAEP(SHA-256)
 * wrapped per-file AES-256-GCM key | 12-byte IV | GCM ciphertext (gzip bytes).
 * AAD = the object's own filename, so a backup cannot be swapped into another
 * day's slot undetected.
 */

beforeEach(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

/** Test-only keypair (2048 for speed) — production uses the committed 4096 key. */
const testKeyPair = await crypto.subtle.generateKey(
  { name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
  true, ['wrapKey', 'unwrapKey', 'encrypt', 'decrypt'],
);
function pemFrom(buf: ArrayBuffer, label: string): string {
  const b64 = btoa(String.fromCharCode(...new Uint8Array(buf)));
  return `-----BEGIN ${label}-----\n${(b64.match(/.{1,64}/g) ?? []).join('\n')}\n-----END ${label}-----`;
}
const testPublicPem = pemFrom(await crypto.subtle.exportKey('spki', testKeyPair.publicKey), 'PUBLIC KEY');
const testPrivatePem = pemFrom(await crypto.subtle.exportKey('pkcs8', testKeyPair.privateKey), 'PRIVATE KEY');

async function gunzip(bytes: Uint8Array): Promise<string> {
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Response(stream).text();
}

async function statusJson(): Promise<Record<string, unknown>> {
  const object = await env.BACKUPS.get('status.json');
  expect(object).not.toBeNull();
  return JSON.parse(await object!.text()) as Record<string, unknown>;
}

async function getBytes(key: string): Promise<Uint8Array> {
  const object = await env.BACKUPS.get(key);
  expect(object).not.toBeNull();
  return new Uint8Array(await new Response(object!.body).arrayBuffer());
}

describe('backup retention: 7 daily + 4 weekly, older deleted', () => {
  const dated = (iso: string) => `nchat-${iso}.sql.gz.enc`;

  it('keeps the last 7 days, one file per ISO week up to 4 weeks, deletes the rest', () => {
    const now = Date.parse('2026-06-15T12:00:00Z'); // a Monday
    const keys = [
      // Daily window: ages 0–6 all kept. (2026-06-15 is a Monday; ISO weeks
      // run Monday–Sunday, so these dates group as noted.)
      ...['09', '10', '11', '12', '13', '14', '15'].map((d) => dated(`2026-06-${d}`)),
      // Beyond the daily window: only each ISO week's latest file survives.
      dated('2026-06-08'), // age 7, but its week already has newer files — deleted
      dated('2026-06-07'), // age 8, latest of its week (Jun 1–7) — kept
      dated('2026-06-01'), // same week as 06-07, older — deleted
      dated('2026-05-30'), // age 16, latest of its week — kept
      dated('2026-05-18'), // age 28 (boundary), latest of its week — kept
      dated('2026-05-17'), // age 29 — deleted
      dated('2026-05-11'), // age 35 — deleted
      // Never candidates for deletion.
      'latest.sql.gz.enc',
      'status.json',
    ];
    expect(keysToDelete(keys, now).sort()).toEqual([
      dated('2026-05-11'),
      dated('2026-05-17'),
      dated('2026-06-01'),
      dated('2026-06-08'),
    ].sort());
  });

  it('groups the New Year days into the right ISO weeks', () => {
    const now = Date.parse('2026-01-10T12:00:00Z');
    const keys = [
      ...['04', '05', '06', '07', '08', '09', '10'].map((d) => dated(`2026-01-${d}`)),
      // ISO week W01 spans the year boundary: Jan 4, Dec 31 and Dec 29 are
      // all one week, so the year-end files yield to the newer Jan 4.
      dated('2025-12-31'), // same week as the Jan dailies — deleted
      dated('2025-12-29'), // same week — deleted
      dated('2025-12-28'), // the week before (age 13), alone — kept
    ];
    expect(keysToDelete(keys, now).sort()).toEqual([dated('2025-12-31'), dated('2025-12-29')].sort());
  });
});

describe('the committed production public key', () => {
  it('imports and wraps a fresh AES key at 4096-bit RSA (owner holds the private half)', async () => {
    const publicKey = await crypto.subtle.importKey(
      'spki', decodePem(BACKUP_PUBLIC_KEY_PEM, 'PUBLIC KEY'),
      { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['wrapKey'],
    );
    const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['wrapKey']);
    const wrapped = new Uint8Array(await crypto.subtle.wrapKey('raw', aes, publicKey, { name: 'RSA-OAEP' }));
    expect(wrapped.byteLength).toBe(512);
  });
});

describe('encrypted dump round-trip', () => {
  it('encrypts, decrypts byte-exactly, and the restored file runs on a fresh database', async () => {
    // Wipe the restore target — the file alone must recreate everything.
    const tables = await env.DB_RESTORE.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid DESC"
    ).all<{ name: string }>();
    for (const row of tables.results ?? []) {
      await env.DB_RESTORE.prepare(`DROP TABLE IF EXISTS "${row.name.replace(/"/g, '""')}"`).run();
    }
    // Deliberately NOT migrated: no applyD1Migrations — the dump provides schema.

    // Seed with rows that stress quoting: apostrophes, semicolons, double
    // quotes, newlines, unicode, and a NULL.
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

    // The exact file a backup run produces: assembled, gzipped, encrypted.
    const name = 'nchat-2026-06-15.sql.gz.enc';
    const sql = assembleDump(statements, '2026-06-15T03:17:00.000Z');
    const gz = await gzip(sql);
    const blob = await encryptBackup(gz, name, testPublicPem);

    // Pipe integrity: decrypt returns the identical gzip, gunzip the exact text.
    const decryptedGz = await decryptBackup(blob, name, testPrivatePem);
    expect(decryptedGz).toEqual(gz);
    expect(await gunzip(decryptedGz)).toBe(sql);

    // Restore: the file is exactly `assembleDump(statements)` byte-for-byte
    // (asserted above), so executing its statement list IS executing the
    // file's content. Note: D1's exec() is line-naive and cannot run any
    // multi-line script — a real SQLite engine parses the file directly.
    // Nothing ever splits the file by hand at restore time; the statements
    // are the dump's own units, each `;`-terminated by assembly.
    for (const statement of statements) {
      expect(sql).toContain(statement.replace(/;\s*$/, '') + ';');
    }
    await env.DB_RESTORE.batch(statements.map((statement) => env.DB_RESTORE.prepare(statement)));
    const after = await countRows(env.DB_RESTORE);
    expect(after).toEqual(before);
    const restored = await env.DB_RESTORE.prepare('SELECT content FROM messages WHERE content IS NOT NULL AND content != ?')
      .bind('').first<{ content: string }>();
    expect(restored?.content).toBe(nasty);
  }, 60000);

  it('rejects tampered ciphertext and filename swaps (AAD binding)', async () => {
    const name = 'nchat-2026-01-01.sql.gz.enc';
    const gz = await gzip('secret dump');
    const blob = await encryptBackup(gz, name, testPublicPem);

    const tampered = blob.slice();
    tampered[tampered.length - 1] ^= 0xff;
    await expect(decryptBackup(tampered, name, testPrivatePem)).rejects.toThrow();

    // Decrypting under a different object name must fail: the filename is bound.
    await expect(decryptBackup(blob, 'latest.sql.gz.enc', testPrivatePem)).rejects.toThrow();

    expect(await gunzip(await decryptBackup(blob, name, testPrivatePem))).toBe('secret dump');
  }, 30000);
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
  it('uploads encrypted dated + latest copies, prunes old files, records status', async () => {
    const now = new Date('2026-06-15T03:17:00.000Z');
    // An expired file that must be pruned by this run.
    await env.BACKUPS.put('nchat-2026-05-11.sql.gz.enc', new Uint8Array([1, 2, 3]));

    const result = await runBackup(env as Env, now, testPublicPem);
    expect(result).toEqual({ ok: true, key: 'nchat-2026-06-15.sql.gz.enc', bytes: expect.any(Number) });

    expect(await env.BACKUPS.head('nchat-2026-06-15.sql.gz.enc')).not.toBeNull();
    expect(await env.BACKUPS.head('latest.sql.gz.enc')).not.toBeNull();
    expect(await env.BACKUPS.head('nchat-2026-05-11.sql.gz.enc')).toBeNull();

    const status = await statusJson();
    expect(status.last_success_utc).toBe('2026-06-15T03:17:00.000Z');
    expect(status.key).toBe('nchat-2026-06-15.sql.gz.enc');
    expect(status.encrypted).toBe(true);

    // Both stored objects are ciphertext containers that decrypt (with their
    // own filename as AAD) to the identical dump text.
    const datedName = 'nchat-2026-06-15.sql.gz.enc';
    const sqlFromDated = await gunzip(await decryptBackup(await getBytes(datedName), datedName, testPrivatePem));
    const sqlFromLatest = await gunzip(await decryptBackup(await getBytes('latest.sql.gz.enc'), 'latest.sql.gz.enc', testPrivatePem));
    expect(sqlFromDated).toBe(sqlFromLatest);
    expect(sqlFromDated).toContain('-- NChat D1 backup');
    expect(sqlFromDated).toContain('CREATE TABLE');
    expect(sqlFromDated).toContain('INSERT INTO "users"');
    // Containers start with the format magic.
    expect(Buffer.from((await getBytes(datedName)).subarray(0, 5)).toString('latin1')).toBe('NCHB1');
  }, 60000);

  it('records the failure in status and does not throw', async () => {
    const now = new Date('2026-06-16T03:17:00.000Z');
    const broken = {
      ...env,
      DB: { prepare: () => { throw new Error('db down'); } },
    } as unknown as Env;
    const result = await runBackup(broken, now, testPublicPem);
    expect(result.ok).toBe(false);
    const status = await statusJson();
    expect(status.last_error_utc).toBe('2026-06-16T03:17:00.000Z');
    expect(String(status.error)).toContain('db down');
    // A failed run must not have touched the dated dump for its day.
    expect(await env.BACKUPS.head('nchat-2026-06-16.sql.gz.enc')).toBeNull();
  }, 30000);
});

describe('cron wiring', () => {
  it('the default export carries a scheduled handler for the daily trigger', () => {
    expect(typeof (app as unknown as { scheduled?: unknown }).scheduled).toBe('function');
  });
});
