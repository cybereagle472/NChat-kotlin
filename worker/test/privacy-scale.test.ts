import { describe, expect, it } from 'vitest';
import { getFriendIdSet, getPrivacySettingsMap, isVisible, type PrivacyOption } from '../src/lib/privacy';
import type { Env } from '../src/types';

function fakeDatabase(): Env {
  return {DB: {prepare: (sql:string) => ({bind: (...ids:string[]) => ({all: async () => {
    if (ids.length > 100) throw new Error('too many SQL parameters');
    return {results: sql.includes('privacy_settings')
      ? ids.map(id => ({user_id:id,profile_photo_visibility:'nobody'}))
      : ids.slice(1, (ids.length - 2) / 2 + 1).map(id => ({user1_id:'viewer',user2_id:id}))};
  }})})}} as unknown as Env;
}

describe('privacy at scale and invalid values', () => {
  it('handles lists larger than the D1 parameter limit', async () => {
    const ids = Array.from({length:121}, (_, i) => `user-${i}`);
    const env = fakeDatabase();
    const settings = await getPrivacySettingsMap(env, ids);
    const friends = await getFriendIdSet(env, 'viewer', ids);
    expect(settings.size).toBe(ids.length);
    expect(friends.size).toBe(ids.length);
  });
  it('fails closed for unrecognized visibility settings', () => {
    expect(isVisible('unknown' as PrivacyOption, false, true)).toBe(false);
    expect(isVisible('contacts', false, true)).toBe(true);
    expect(isVisible('unknown' as PrivacyOption, true, false)).toBe(true);
  });
});
