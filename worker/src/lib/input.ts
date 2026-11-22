/** Bound UTF-8 text fields before storing or broadcasting them. */
export function exceedsTextLimits(body: Record<string, unknown>, limits: Record<string, number>): boolean {
  const encoder = new TextEncoder();
  return Object.entries(limits).some(([field, max]) => typeof body[field] === 'string' && encoder.encode(body[field] as string).byteLength > max);
}
