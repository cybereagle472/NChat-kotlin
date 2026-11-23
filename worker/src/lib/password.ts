/**
 * Password hashing using PBKDF2 via Web Crypto (available natively in the
 * Workers runtime — no native bindings / npm bcrypt needed).
 */
const ITERATIONS = 100_000;
// Bound work to the current issuance count; revisit after verifying runtime limits.
const MAX_ITERATIONS = 100_000;

function toHex(buf: ArrayBuffer | Uint8Array): string {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function fromHex(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bytes;
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<ArrayBuffer> {
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  return crypto.subtle.deriveBits({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, keyMaterial, 256);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(password, salt, ITERATIONS);
  return `${ITERATIONS}:${toHex(salt)}:${toHex(hash)}`;
}

export function passwordNeedsUpgrade(stored: string): boolean {
  const iterations = Number(stored.split(':')[0]);
  return Number.isInteger(iterations) && iterations > 0 && iterations < ITERATIONS;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  if (typeof stored !== 'string' || typeof password !== 'string') return false;
  const match = /^(\d{1,6}):([0-9a-f]{32}):([0-9a-f]{64})$/i.exec(stored);
  if (!match) return false;
  const iterations = Number(match[1]);
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) return false;
  const actual = new Uint8Array(await pbkdf2(password, fromHex(match[2]), iterations));
  const expected = fromHex(match[3]);
  let difference = 0;
  for (let i = 0; i < actual.length; i++) difference |= actual[i] ^ expected[i];
  return difference === 0;
}
