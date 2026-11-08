//! HS256 JWT sign/verify — a straight port of `worker/src/lib/jwt.ts`.
//!
//! The reference issues `{ sub, iat, exp }` access tokens and
//! `{ sub, sid, iat, exp }` refresh tokens; both round-trip here unchanged, so
//! tokens minted by either implementation verify in the other.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use serde_json::{json, Value};
use sha2::Sha256;

use crate::util::now_secs;

type HmacSha256 = Hmac<Sha256>;

fn mac(secret: &str) -> Option<HmacSha256> {
    HmacSha256::new_from_slice(secret.as_bytes()).ok()
}

fn encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn sign_data(secret: &str, data: &str) -> Option<String> {
    let mut hasher = mac(secret)?;
    hasher.update(data.as_bytes());
    Some(encode(&hasher.finalize().into_bytes()))
}

/// `signJWT(payload, secret, expiresInSeconds)`.
pub fn sign(payload: &Value, secret: &str, ttl_secs: i64) -> Option<String> {
    let header = json!({ "alg": "HS256", "typ": "JWT" });
    let now = now_secs();

    let mut body = payload.as_object()?.clone();
    body.insert("iat".into(), json!(now));
    body.insert("exp".into(), json!(now + ttl_secs));

    let header_b64 = encode(serde_json::to_string(&header).ok()?.as_bytes());
    let body_b64 = encode(serde_json::to_string(&Value::Object(body)).ok()?.as_bytes());
    let data = format!("{header_b64}.{body_b64}");
    let signature = sign_data(secret, &data)?;
    Some(format!("{data}.{signature}"))
}

/// `verifyJWT(token, secret)` — signature first, then expiry.
pub fn verify(token: &str, secret: &str) -> Option<Value> {
    let mut parts = token.split('.');
    let header_b64 = parts.next()?;
    let body_b64 = parts.next()?;
    let signature_b64 = parts.next()?;
    if parts.next().is_some() {
        return None;
    }

    let data = format!("{header_b64}.{body_b64}");
    let expected = sign_data(secret, &data)?;
    if !constant_time_eq(expected.as_bytes(), signature_b64.as_bytes()) {
        return None;
    }

    let raw = URL_SAFE_NO_PAD.decode(body_b64).ok()?;
    let payload: Value = serde_json::from_slice(&raw).ok()?;

    // Every token we issue has an expiry. Reject missing, malformed, and expired
    // claims rather than accidentally accepting a perpetual bearer token.
    let exp = payload.get("exp").and_then(Value::as_i64)?;
    if now_secs() >= exp {
        return None;
    }
    Some(payload)
}

/// `sub` claim of a valid token.
pub fn subject(token: &str, secret: &str) -> Option<String> {
    let payload = verify(token, secret)?;
    // The worker-compatible refresh token contains `sid`; access tokens do
    // not. Never treat a refresh credential as an API or WebSocket credential.
    if payload.get("sid").is_some() {
        return None;
    }
    payload.get("sub").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string)
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let token = sign(&json!({ "sub": "user-1" }), "secret", 60).expect("token");
        let payload = verify(&token, "secret").expect("payload");
        assert_eq!(payload["sub"], "user-1");
        assert!(payload["exp"].as_i64().unwrap_or(0) > now_secs());
        assert!(verify(&token, "other").is_none());
        assert!(verify("not.a.token", "secret").is_none());
    }

    #[test]
    fn refresh_token_is_not_an_access_token() {
        let access = sign(&json!({ "sub": "user-1" }), "secret", 60).unwrap();
        let refresh = sign(&json!({ "sub": "user-1", "sid": "session-1" }), "secret", 60).unwrap();
        assert_eq!(subject(&access, "secret").as_deref(), Some("user-1"));
        assert_eq!(subject(&refresh, "secret"), None);
        let expired = sign(&json!({ "sub": "user-1" }), "secret", 0).unwrap();
        assert!(verify(&expired, "secret").is_none());
    }

    #[test]
    fn rejects_tampering() {
        let token = sign(&json!({ "sub": "user-1" }), "secret", 60).expect("token");
        let mut parts: Vec<&str> = token.split('.').collect();
        parts[1] = "eyJzdWIiOiJhZG1pbiJ9";
        assert!(verify(&parts.join("."), "secret").is_none());
    }
}
