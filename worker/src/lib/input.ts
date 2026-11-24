/** Bound UTF-8 text fields before storing or broadcasting them. */
export function exceedsTextLimits(body: Record<string, unknown>, limits: Record<string, number>): boolean {
  const encoder = new TextEncoder();
  return Object.entries(limits).some(([field, max]) => typeof body[field] === 'string' && encoder.encode(body[field] as string).byteLength > max);
}

/**
 * Message types the app actually sends (RemoteChatRepository.wireType plus
 * 'e2ee' sealed envelopes). Unknown types fail closed at the route — a
 * garbage type must never reach storage or a client's renderer.
 */
export const DIRECT_MESSAGE_TYPES = new Set(['text', 'e2ee', 'image', 'video', 'voice', 'document', 'location']);

/**
 * Group message types — identical minus 'e2ee': sealed group messages are
 * rejected until group E2EE ships and its renderer exists.
 */
export const GROUP_MESSAGE_TYPES = new Set(['text', 'image', 'video', 'voice', 'document', 'location']);

/** The longest message text, in bytes. */
export const TEXT_CONTENT_BYTES = 65536;

/**
 * The longest sealed envelope. A text of TEXT_CONTENT_BYTES grows by about a
 * third when it is sealed and base64-encoded (roughly 88,000 bytes with its
 * header), so anything that may be sent in the clear also fits sealed.
 */
export const SEALED_CONTENT_BYTES = 131072;

/** A P-256 public key as SubjectPublicKeyInfo DER — the only key encoding the E2EE routes accept. */
export const SPKI_BYTES = 91;

/**
 * Strict base64 decode: canonical encoding and an exact byte length, or null.
 * "Canonical" matters because key material is compared as text elsewhere —
 * two spellings of the same bytes must never both be accepted.
 */
export function decodeExact(value: unknown, bytes: number): Uint8Array | null {
  if (typeof value !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  let binary: string;
  try {
    binary = atob(value);
  } catch {
    return null;
  }
  if (binary.length !== bytes) return null;
  if (btoa(binary) !== value) return null; // non-canonical padding/bits
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = binary.charCodeAt(i);
  return out;
}
