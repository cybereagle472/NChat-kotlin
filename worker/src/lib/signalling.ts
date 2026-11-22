// These names form the server-controlled event envelope. Android recognises
// multiple aliases for call and sender IDs; do not forward any client alias.
const ENVELOPE_KEYS = new Set([
  'type', 'to', 'call_id', 'callId', 'group_call_id', 'groupCallId',
  'from', 'from_id', 'sender_id', 'senderId', 'user_id',
]);

export function signalPayload(body: unknown): Record<string, unknown> {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return {};
  return Object.fromEntries(Object.entries(body).filter(([key]) => !ENVELOPE_KEYS.has(key)));
}
