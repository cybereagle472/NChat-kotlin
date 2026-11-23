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
