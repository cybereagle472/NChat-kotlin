//! Small helpers shared by every route — the Rust equivalents of `lib/id.ts`.

use chrono::{DateTime, Duration, SecondsFormat, Utc};

/// `crypto.randomUUID()`.
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// `new Date().toISOString()` → `2026-11-04T12:34:56.789Z`.
pub fn now_iso() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

/// ISO timestamp `secs` in the future (or past, for negatives).
pub fn iso_in_secs(secs: i64) -> String {
    (Utc::now() + Duration::seconds(secs)).to_rfc3339_opts(SecondsFormat::Millis, true)
}

/// Parse an ISO string the way `new Date(x).getTime()` does. Returns millis.
pub fn parse_iso_millis(value: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|dt| dt.timestamp_millis())
}

/// Whole seconds since the epoch (`Math.floor(Date.now() / 1000)`).
pub fn now_secs() -> i64 {
    Utc::now().timestamp()
}

pub fn now_millis() -> i64 {
    Utc::now().timestamp_millis()
}

/// `Number(x) || fallback`, clamped — the pattern the reference uses for
/// every `?limit=` query parameter.
pub fn clamp_limit(raw: Option<&str>, fallback: usize, max: usize) -> usize {
    let parsed = raw
        .and_then(|text| text.trim().parse::<i64>().ok())
        .filter(|value| *value > 0);
    match parsed {
        Some(value) => (value as usize).min(max),
        None => fallback.min(max),
    }
}
