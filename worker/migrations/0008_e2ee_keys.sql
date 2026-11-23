-- E2EE key infrastructure (Batch 1): PUBLIC key material only. The server
-- never holds private keys, never sees message content — it verifies the
-- signed-prekey signature at upload and serves bundles on request.
-- Encodings are locked by tests: identity/signed-prekey = SPKI DER of a
-- P-256 key (exactly 91 bytes), signature = ECDSA P1363 (exactly 64 bytes).

CREATE TABLE user_crypto_keys (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  identity_public_key TEXT NOT NULL,
  signed_prekey_public TEXT NOT NULL,
  signed_prekey_signature TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- One-time prekeys: each is handed out at most once (atomic DELETE ... RETURNING
-- in the bundle endpoint — concurrent fetches can never receive the same row).
-- The id is a strictly increasing integer: insertion order is the pop order,
-- with no timestamp ties (datetime('now') only has second precision).
CREATE TABLE one_time_prekeys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_one_time_prekeys_by_user ON one_time_prekeys(user_id, id);
