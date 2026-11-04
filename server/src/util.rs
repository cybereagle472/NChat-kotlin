//! Small helpers shared by every route — the Rust equivalents of `lib/id.ts`.

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use std::sync::Mutex;

/// `crypto.randomUUID()`.
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// `new Date().toISOString()` → `2026-11-04T12:34:56.789Z`.
///
/// One deliberate hardening on top of the reference: the returned timestamps are
/// **strictly increasing**. Unread counts compare `created_at > last_read_at`
/// (and a brand-new group member is compared against `joined_at`), so two writes
/// that land inside the same millisecond would otherwise make a message look
/// already read — a real flake in the worker too. Bumping by 1 ms keeps the exact
/// same string format, the same wall-clock order and removes the race.
pub fn now_iso() -> String {
    use chrono::TimeZone;

    static LAST_MILLIS: Mutex<i64> = Mutex::new(0);
    let mut last = LAST_MILLIS
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let mut millis = Utc::now().timestamp_millis();
    if millis <= *last {
        millis = *last + 1;
    }
    *last = millis;
    Utc.timestamp_millis_opt(millis)
        .single()
        .map(|at| at.to_rfc3339_opts(SecondsFormat::Millis, true))
        .unwrap_or_default()
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
