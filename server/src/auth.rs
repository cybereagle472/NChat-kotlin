//! Password hashing and stateless bearer tokens.
//!
//! This is deliberately dependency-light: salted SHA-256 with a work factor,
//! and an HMAC-SHA256 signed token. Both are strong enough for a private beta
//! and fast (single-digit microseconds per verify). When accounts become
//! public, swap `hash_password` for Argon2id — the storage format (`salt`,
//! `hash`) already allows a per-user algorithm prefix.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

const WORK_FACTOR: u32 = 20_000;

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0f) as usize] as char);
    }
    out
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// 32 hex chars of randomness, unique per account.
pub fn new_salt() -> String {
    hex(&rand::random::<[u8; 16]>())
}

pub fn hash_password(password: &str, salt: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(salt.as_bytes());
    hasher.update(password.as_bytes());
    let mut digest = hasher.finalize();

    for _ in 0..WORK_FACTOR {
        let mut round = Sha256::new();
        round.update(digest);
        round.update(salt.as_bytes());
        digest = round.finalize();
    }
    hex(&digest)
}

pub fn verify_password(password: &str, salt: &str, expected_hash: &str) -> bool {
    constant_time_eq(
        hash_password(password, salt).as_bytes(),
        expected_hash.as_bytes(),
    )
}

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String,
    exp: i64,
}

fn sign(secret: &[u8], data: &[u8]) -> Option<String> {
    let mut mac = HmacSha256::new_from_slice(secret).ok()?;
    mac.update(data);
    Some(hex(&mac.finalize().into_bytes()))
}

/// `base64url(payload_json) "." hex(hmac)` — compact, URL-safe, no padding.
pub fn issue_token(secret: &[u8], user_id: &str, now: i64, ttl_secs: i64) -> Option<String> {
    let claims = Claims {
        sub: user_id.to_string(),
        exp: now + ttl_secs,
    };
    let payload = serde_json::to_vec(&claims).ok()?;
    let encoded = URL_SAFE_NO_PAD.encode(payload);
    let signature = sign(secret, encoded.as_bytes())?;
    Some(format!("{encoded}.{signature}"))
}

/// Returns the user id when the signature and expiry both check out.
pub fn verify_token(secret: &[u8], token: &str, now: i64) -> Option<String> {
    let (encoded, signature) = token.split_once('.')?;
    let expected = sign(secret, encoded.as_bytes())?;
    if !constant_time_eq(expected.as_bytes(), signature.as_bytes()) {
        return None;
    }
    let raw = URL_SAFE_NO_PAD.decode(encoded).ok()?;
    let claims: Claims = serde_json::from_slice(&raw).ok()?;
    if claims.exp <= now {
        return None;
    }
    Some(claims.sub)
}

/// URL slug used as the public handle: `"Ada Lovelace"` → `"ada-lovelace"`.
pub fn slugify(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    let mut last_dash = true;
    for ch in name.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            last_dash = false;
        } else if !last_dash {
            out.push('-');
            last_dash = true;
        }
    }
    let trimmed = out.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "user".to_string()
    } else {
        trimmed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn password_round_trip() {
        let salt = new_salt();
        let hash = hash_password("correct horse", &salt);
        assert!(verify_password("correct horse", &salt, &hash));
        assert!(!verify_password("wrong horse", &salt, &hash));
    }

    #[test]
    fn token_round_trip_and_expiry() {
        let secret = b"test-secret";
        let token = issue_token(secret, "user-1", 1_000, 60).expect("token");
        assert_eq!(verify_token(secret, &token, 1_010).as_deref(), Some("user-1"));
        assert!(verify_token(secret, &token, 2_000).is_none());
        assert!(verify_token(b"other-secret", &token, 1_010).is_none());
    }

    #[test]
    fn slugs_are_stable() {
        assert_eq!(slugify("Ada Lovelace"), "ada-lovelace");
        assert_eq!(slugify("  Zayn  "), "zayn");
        assert_eq!(slugify("???"), "user");
    }
}
