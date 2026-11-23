import type { Env } from '../types';

/**
 * Daily database backup — the owner's 2026-11-23 decision: a Cloudflare-only
 * copy (no off-site channel), visible as files in the dashboard, keeping the
 * last 7 daily + 4 weekly dumps and deleting older ones automatically, so
 * storage can never creep up.
 *
 * The dump is plain SQLite DDL + INSERTs (schema from `sqlite_master`, one
 * statement object per object — never re-parsed, so trigger bodies with
 * embedded semicolons survive). Cloudflare's `d1_migrations` bookkeeping is
 * excluded: a restore target applies migrations itself.
 */

const DAY_MS = 86_400_000;
const DAILY_KEEPS = 7;
const WEEKLY_WINDOW_DAYS = 28;
const LATEST_KEY = 'latest.sql.gz';
const STATUS_KEY = 'status.json';
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
 * `latest.sql.gz` and `status.json` are not dated and never listed.
 */
export function keysToDelete(keys: string[], nowMs: number): string[] {
  const dated: { key: string; time: number }[] = [];
  for (const key of keys) {
    const match = /^nchat-(\d{4}-\d{2}-\d{2})\.sql\.gz$/.exec(key);
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
 * One backup run: dump → gzip → dated file + `latest.sql.gz` → prune by age
 * → record `status.json` (last success or the error). Never throws: a failed
 * run is a status entry, not a crashed cron.
 */
export async function runBackup(
  env: Env,
  now: Date = new Date(),
): Promise<{ ok: true; key: string; bytes: number } | { ok: false; error: string }> {
  try {
    const statements = await buildDumpStatements(env.DB);
    const sql = `-- NChat D1 backup ${now.toISOString()}\n${statements.join('\n')}\n`;
    const compressed = await gzip(sql);
    const key = `nchat-${now.toISOString().slice(0, 10)}.sql.gz`;
    const options = { httpMetadata: { contentType: 'application/gzip' } };
    await env.BACKUPS.put(key, compressed, options);
    await env.BACKUPS.put(LATEST_KEY, compressed, options);

    const listed: string[] = [];
    let cursor: string | undefined;
    do {
      const page = await env.BACKUPS.list(cursor ? { cursor } : {});
      listed.push(...page.objects.map((object) => object.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
    for (const stale of keysToDelete(listed, now.getTime())) await env.BACKUPS.delete(stale);

    await env.BACKUPS.put(STATUS_KEY, JSON.stringify({
      last_success_utc: now.toISOString(),
      key,
      bytes: compressed.byteLength,
      statements: statements.length,
    }));
    return { ok: true, key, bytes: compressed.byteLength };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await env.BACKUPS.put(STATUS_KEY, JSON.stringify({
      last_error_utc: now.toISOString(),
      error: message,
    })).catch(() => { /* status is best-effort on the failure path */ });
    return { ok: false, error: message };
  }
}
