import type { Env } from '../types';
import { BACKUP_PUBLIC_KEY_PEM } from './backupPublicKey';

/**
 * Daily database backup — the owner's 2026-11-23 decisions: a Cloudflare-only
 * copy (no off-site channel), visible as files in the dashboard, keeping the
 * last 7 daily + 4 weekly dumps and deleting older ones automatically, so
 * storage can never creep up — and, same day, "fully encrypted like
 * WhatsApp": every stored file is ciphertext.
 *
 * Encryption model (WhatsApp-style key custody): each run mints a fresh
 * AES-256-GCM key for each object, wraps it with the committed PUBLIC key
 * (RSA-4096, OAEP-SHA-256) and discards it. The private half exists only
 * with the owner — Cloudflare holds data it mathematically cannot decrypt.
 * The object's own filename is the AEAD's additional data, so one day's
 * backup cannot be swapped into another day's slot undetected.
 *
 * Container `NCHB1`: magic(5) | u16BE wrappedKeyLen | wrapped AES key |
 * 12-byte IV | GCM ciphertext over the gzip bytes.
 *
 * The dump is plain SQLite DDL + INSERTs (schema from `sqlite_master`, one
 * statement object per object — never re-parsed, so trigger bodies with
 * embedded semicolons survive). Cloudflare's `d1_migrations` bookkeeping is
 * excluded: a restore target applies migrations itself.
 */

export { BACKUP_PUBLIC_KEY_PEM } from './backupPublicKey';

const DAY_MS = 86_400_000;
const DAILY_KEEPS = 7;
const WEEKLY_WINDOW_DAYS = 28;
const LATEST_KEY = 'latest.sql.gz.enc';
const STATUS_KEY = 'status.json';
const MAGIC = 'NCHB1';
const IV_LENGTH = 12;
// Not application data: a restored database re-applies migrations instead.
const EXCLUDED_TABLES = new Set(['d1_migrations']);

function quoteIdent(name: string): string {
  return `"${name.replace(/"/g, '""')}"`;
}

function blobLiteral(bytes: Uint8Array): string {
  let hex = '';
  for (const byte of bytes) hex += byte.toString(16).padStart(2, '0');
  return `X'${hex}'`;
}

/** A value as a SQLite expression — text quoting is the whole point. */
function sqlLiteral(value: unknown): string {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : 'NULL';
  if (typeof value === 'bigint') return String(value);
  if (typeof value === 'string') return `'${value.replace(/'/g, "''")}'`;
  if (value instanceof ArrayBuffer) return blobLiteral(new Uint8Array(value));
  if (ArrayBuffer.isView(value)) {
    return blobLiteral(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
  }
  if (Array.isArray(value)) return blobLiteral(Uint8Array.from(value as number[]));
  return `'${String(value).replace(/'/g, "''")}'`;
}

/**
 * The full restore script as discrete statements: every table's DDL followed
 * by its rows (creation order satisfies foreign keys), then indexes,
 * triggers and views. One statement per object — no text splitting anywhere.
 */
export async function buildDumpStatements(db: D1Database): Promise<string[]> {
  const { results: objects } = await db.prepare(
    `SELECT type, name, sql FROM sqlite_master
      WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
      ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'trigger' THEN 2 ELSE 3 END, rowid`
  ).all<{ type: string; name: string; sql: string }>();
  const statements: string[] = [];
  for (const object of objects ?? []) {
    if (object.type !== 'table') {
      statements.push(object.sql);
      continue;
    }
    if (EXCLUDED_TABLES.has(object.name)) continue;
    statements.push(object.sql);
    // The source database is small (text-scale); one pass per table is fine.
    const { results: rows } = await db.prepare(`SELECT * FROM ${quoteIdent(object.name)}`).all<Record<string, unknown>>();
    for (const row of rows ?? []) {
      const columns = Object.keys(row);
      statements.push(
        `INSERT INTO ${quoteIdent(object.name)} (${columns.map(quoteIdent).join(', ')}) VALUES ` +
        `(${columns.map((column) => sqlLiteral(row[column])).join(', ')});`
      );
    }
  }
  return statements;
}

export async function gzip(text: string): Promise<Uint8Array> {
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Strip PEM armour down to raw key bytes. */
export function decodePem(pem: string, label: string): Uint8Array {
  const body = pem
    .replace(new RegExp(`-----BEGIN ${label}-----`), '')
    .replace(new RegExp(`-----END ${label}-----`), '')
    .replace(/\s+/g, '');
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * The exact restore script: one statement per line, each terminated with `;`
 * (sqlite_master DDL arrives without trailing semicolons — never concatenate
 * bare). The file is meant to be executed by a real SQL engine as-is; nothing
 * ever splits it back apart by hand.
 */
export function assembleDump(statements: string[], timestampIso: string): string {
  const body = statements.map((statement) => statement.replace(/;\s*$/, '') + ';').join('\n');
  return `-- NChat D1 backup ${timestampIso}\n${body}\n`;
}

/** Encrypt gzip bytes into an NCHB1 container for the given object name. */
export async function encryptBackup(
  gzipBytes: Uint8Array,
  keyName: string,
  publicKeyPem: string,
): Promise<Uint8Array> {
  const publicKey = await crypto.subtle.importKey(
    'spki', decodePem(publicKeyPem, 'PUBLIC KEY'),
    { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['wrapKey'],
  );
  const aesKey = (await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt'])) as CryptoKey;
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(keyName) },
    aesKey, gzipBytes,
  ));
  const wrapped = new Uint8Array(await crypto.subtle.wrapKey('raw', aesKey, publicKey, { name: 'RSA-OAEP' }));

  const out = new Uint8Array(MAGIC.length + 2 + wrapped.length + IV_LENGTH + ciphertext.length);
  out.set(new TextEncoder().encode(MAGIC), 0);
  out[5] = wrapped.length >> 8;
  out[6] = wrapped.length & 0xff;
  out.set(wrapped, 7);
  out.set(iv, 7 + wrapped.length);
  out.set(ciphertext, 7 + wrapped.length + IV_LENGTH);
  return out;
}

/**
 * Inverse of `encryptBackup` — the restore entry point. `keyName` must be the
 * object's own filename (it is authenticated additional data). Returns the
 * gzip bytes; the caller decompresses. Needs the owner's private key.
 */
export async function decryptBackup(
  container: Uint8Array,
  keyName: string,
  privateKeyPem: string,
): Promise<Uint8Array> {
  if (container.length < MAGIC.length + 2 + IV_LENGTH + 16 ||
      new TextDecoder().decode(container.subarray(0, MAGIC.length)) !== MAGIC) {
    throw new Error('not an NCHB1 backup container');
  }
  const view = new DataView(container.buffer, container.byteOffset, container.byteLength);
  const wrappedLength = view.getUint16(MAGIC.length);
  const wrappedStart = MAGIC.length + 2;
  const ivStart = wrappedStart + wrappedLength;
  const dataStart = ivStart + IV_LENGTH;
  if (container.length < dataStart + 16) throw new Error('truncated NCHB1 backup container');

  const privateKey = await crypto.subtle.importKey(
    'pkcs8', decodePem(privateKeyPem, 'PRIVATE KEY'),
    { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt'],
  );
  // RSA-OAEP "decrypt" of the wrapped key == unwrap, then import the raw AES key.
  const rawAesKey = await crypto.subtle.decrypt(
    { name: 'RSA-OAEP' },
    privateKey, container.subarray(wrappedStart, ivStart),
  );
  const aesKey = await crypto.subtle.importKey('raw', rawAesKey, { name: 'AES-GCM' }, false, ['decrypt']);
  const iv = container.subarray(ivStart, dataStart);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(keyName) },
    aesKey, container.subarray(dataStart),
  );
  return new Uint8Array(plaintext);
}

/** ISO-8601 week identity ("2026-W24") so weekly retention groups correctly. */
function isoWeekKey(timestampMs: number): string {
  const date = new Date(timestampMs);
  const weekday = (date.getUTCDay() + 6) % 7; // Monday = 0
  date.setUTCDate(date.getUTCDate() - weekday + 3); // Thursday of that week
  const isoYear = date.getUTCFullYear();
  const firstThursday = new Date(Date.UTC(isoYear, 0, 4));
  const firstThursdayWeekday = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstThursdayWeekday + 3);
  const week = 1 + Math.round((date.getTime() - firstThursday.getTime()) / (7 * DAY_MS));
  return `${isoYear}-W${String(week).padStart(2, '0')}`;
}

function dayIndex(timestampMs: number): number {
  return Math.floor(Date.UTC(
    new Date(timestampMs).getUTCFullYear(),
    new Date(timestampMs).getUTCMonth(),
    new Date(timestampMs).getUTCDate(),
  ) / DAY_MS);
}

/**
 * Retention: keep every dated dump from the last 7 days; beyond that keep
 * only each ISO week's latest file while inside 28 days; delete the rest.
 * `latest.sql.gz.enc` and `status.json` are not dated and never listed.
 */
export function keysToDelete(keys: string[], nowMs: number): string[] {
  const dated: { key: string; time: number }[] = [];
  for (const key of keys) {
    const match = /^nchat-(\d{4}-\d{2}-\d{2})\.sql\.gz(\.enc)?$/.exec(key);
    if (!match) continue;
    const time = Date.parse(`${match[1]}T00:00:00Z`);
    if (Number.isNaN(time)) continue;
    dated.push({ key, time });
  }

  const today = dayIndex(nowMs);
  const newestOfweek = new Map<string, number>();
  for (const entry of dated) {
    const week = isoWeekKey(entry.time);
    const current = newestOfweek.get(week);
    if (current === undefined || entry.time > current) newestOfweek.set(week, entry.time);
  }

  const keep = new Set<string>();
  for (const entry of dated) {
    const age = today - dayIndex(entry.time);
    if (age < DAILY_KEEPS) {
      keep.add(entry.key);
    } else if (age <= WEEKLY_WINDOW_DAYS && newestOfweek.get(isoWeekKey(entry.time)) === entry.time) {
      keep.add(entry.key);
    }
  }
  return dated.map((entry) => entry.key).filter((key) => !keep.has(key));
}

/**
 * One backup run: dump → gzip → encrypt (per-object AES key, wrapped with
 * the owner's public key) → dated file + `latest.sql.gz.enc` → prune by age
 * → record `status.json` (last success or the error). Never throws: a failed
 * run is a status entry, not a crashed cron.
 *
 * `publicKeyPem` exists for tests (their own keypair, so they can prove the
 * decrypt path); production calls pass nothing and get the committed key.
 */
export async function runBackup(
  env: Env,
  now: Date = new Date(),
  publicKeyPem: string = BACKUP_PUBLIC_KEY_PEM,
): Promise<{ ok: true; key: string; bytes: number } | { ok: false; error: string }> {
  try {
    const statements = await buildDumpStatements(env.DB);
    const sql = assembleDump(statements, now.toISOString());
    const compressed = await gzip(sql);
    const key = `nchat-${now.toISOString().slice(0, 10)}.sql.gz.enc`;
    const options = { httpMetadata: { contentType: 'application/octet-stream' } };
    // Each object name is its own AEAD context — encrypt once per name so
    // both the dated file and `latest` verify as themselves.
    await env.BACKUPS.put(key, await encryptBackup(compressed, key, publicKeyPem), options);
    await env.BACKUPS.put(LATEST_KEY, await encryptBackup(compressed, LATEST_KEY, publicKeyPem), options);

    const listed: string[] = [];
    let cursor: string | undefined;
    do {
      const page = await env.BACKUPS.list(cursor ? { cursor } : {});
      listed.push(...page.objects.map((object) => object.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
    for (const stale of keysToDelete(listed, now.getTime())) await env.BACKUPS.delete(stale);

    const datedBytes = (await env.BACKUPS.head(key))?.size ?? compressed.byteLength;
    await env.BACKUPS.put(STATUS_KEY, JSON.stringify({
      last_success_utc: now.toISOString(),
      key,
      bytes: datedBytes,
      statements: statements.length,
      encrypted: true,
    }));
    return { ok: true, key, bytes: datedBytes };
  } catch (error) {
    const message = (error instanceof Error ? error.message : String(error)).slice(0, 300);
    await env.BACKUPS.put(STATUS_KEY, JSON.stringify({
      last_error_utc: now.toISOString(),
      error: message,
    })).catch(() => { /* status is best-effort on the failure path */ });
    return { ok: false, error: message };
  }
}
