import type { Env } from '../types';

/**
 * The identity key (base64 SPKI) `userId` has registered for end-to-end
 * encryption, or null. One installation per account holds the private half;
 * everything sealed for, or uploaded under, any other key is refused.
 */
export async function registeredIdentityKey(env: Env, userId: string): Promise<string | null> {
  const row = await env.DB.prepare('SELECT identity_public_key FROM user_crypto_keys WHERE user_id = ?')
    .bind(userId).first<{ identity_public_key: string }>();
  return row?.identity_public_key ?? null;
}

/** False on a server whose database has not been given the key tables (migration 0008). */
export async function keyTablesExist(env: Env): Promise<boolean> {
  try {
    await env.DB.batch([
      env.DB.prepare('SELECT 1 FROM user_crypto_keys LIMIT 1'),
      env.DB.prepare('SELECT 1 FROM one_time_prekeys LIMIT 1'),
    ]);
    return true;
  } catch {
    return false;
  }
}

/**
 * Removes the keys of `userId` if `identityKey` is still the registered one,
 * together with the one-time prekeys uploaded under it. True when keys were
 * removed. Naming the identity is what protects a newer installation: the
 * phone it replaced cannot take the new phone's keys with it when it signs
 * out. One batch, so the prekeys go exactly when the identity does.
 */
export async function withdrawIdentityKey(env: Env, userId: string, identityKey: string): Promise<boolean> {
  const [, withdrawn] = await env.DB.batch([
    env.DB.prepare(
      `DELETE FROM one_time_prekeys WHERE user_id = ?1
         AND EXISTS (SELECT 1 FROM user_crypto_keys WHERE user_id = ?1 AND identity_public_key = ?2)`,
    ).bind(userId, identityKey),
    env.DB.prepare('DELETE FROM user_crypto_keys WHERE user_id = ?1 AND identity_public_key = ?2').bind(userId, identityKey),
  ]);
  return withdrawn.meta.changes > 0;
}
