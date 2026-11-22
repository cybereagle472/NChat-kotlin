import { describe, expect, it } from 'vitest';
import { clientAddressBucket } from '../src/lib/rate-limit';

describe('Cloudflare client address buckets', () => {
  it('normalizes IPv6 spelling and hosts within a /64', () => {
    expect(clientAddressBucket('2001:db8:1234:5678::1')).toBe('2001:db8:1234:5678::/64');
    expect(clientAddressBucket('2001:0DB8:1234:5678:abcd:0:0:2')).toBe('2001:db8:1234:5678::/64');
    expect(clientAddressBucket('::1')).toBe('0:0:0:0::/64');
  });
  it('uses the same bucket for IPv4 and IPv4-mapped IPv6', () => {
    expect(clientAddressBucket('192.0.2.1')).toBe('192.0.2.1');
    expect(clientAddressBucket('::ffff:192.0.2.1')).toBe('192.0.2.1');
    expect(clientAddressBucket('::FFFF:c000:201')).toBe('192.0.2.1');
  });
  it('rejects malformed or missing addresses', () => {
    for (const value of [undefined, '', '999.0.0.1', '192.000.2.1', '2001:::1', '2001:db8::1%eth0', 'garbage']) expect(clientAddressBucket(value)).toBeNull();
  });
});
