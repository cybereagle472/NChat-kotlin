//! Small in-memory rate limiter for the credential endpoints.
//!
//! Not a replacement for an edge (Cloudflare/nginx already absorbs floods in front of a
//! deployment), but it stops the cheap attack that matters for privacy: password guessing
//! against `/auth/login` and account enumeration through `/auth/check-email`.
//!
//! Deliberately dependency-free and process-local — a self-hosted NChat is usually a single
//! instance, and a limiter that cannot be bypassed by restarting the app is worth more than
//! one that needs Redis.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Sliding-window limiter: at most `max` hits per `window` for each key.
pub struct RateLimiter {
    window: Duration,
    max: usize,
    hits: Mutex<HashMap<String, Vec<Instant>>>,
}

impl RateLimiter {
    pub fn new(max: usize, window: Duration) -> Self {
        Self {
            window,
            max,
            hits: Mutex::new(HashMap::new()),
        }
    }

    /// A limiter that never blocks, for callers that opt out.
    pub fn disabled() -> Self {
        Self::new(usize::MAX, Duration::from_secs(1))
    }

    /// Records a hit for `key` and returns false when the key is over its budget.
    ///
    /// Failing closed on a poisoned mutex keeps the limiter honest: if it cannot count
    /// requests it must not silently allow them.
    pub fn allow(&self, key: &str) -> bool {
        let now = Instant::now();
        let Ok(mut map) = self.hits.lock() else {
            return false;
        };

        // Opportunistic sweep so a long-running server does not accumulate keys forever.
        if map.len() > 10_000 {
            let window = self.window;
            map.retain(|_, stamps| {
                stamps.retain(|at| now.duration_since(*at) < window);
                !stamps.is_empty()
            });
        }

        let stamps = map.entry(key.to_string()).or_default();
        stamps.retain(|at| now.duration_since(*at) < self.window);
        if stamps.len() >= self.max {
            return false;
        }
        stamps.push(now);
        true
    }
}

/// Client address for rate-limit keys: the first hop in `X-Forwarded-For` (a Worker or a
/// reverse proxy sets it), else `X-Real-IP`, else a fixed bucket for direct connections.
pub fn client_key(headers: &axum::http::HeaderMap, email: &str) -> String {
    let forwarded = headers
        .get("x-forwarded-for")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.split(',').next())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());

    let ip = forwarded
        .or_else(|| {
            headers
                .get("x-real-ip")
                .and_then(|value| value.to_str().ok())
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_else(|| "direct".to_string());

    if email.is_empty() {
        ip
    } else {
        format!("{ip}|{email}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_after_the_budget() {
        let limiter = RateLimiter::new(2, Duration::from_secs(60));
        assert!(limiter.allow("a"));
        assert!(limiter.allow("a"));
        assert!(!limiter.allow("a"));
        // A different key has its own budget.
        assert!(limiter.allow("b"));
    }

    #[test]
    fn window_expires() {
        let limiter = RateLimiter::new(1, Duration::from_millis(20));
        assert!(limiter.allow("k"));
        assert!(!limiter.allow("k"));
        std::thread::sleep(Duration::from_millis(30));
        assert!(limiter.allow("k"));
    }
}
