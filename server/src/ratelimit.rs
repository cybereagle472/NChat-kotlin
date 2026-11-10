//! Small in-memory rate limiter.
//!
//! Not a replacement for an edge (Cloudflare/nginx already absorbs floods in front of a
//! deployment), but it slows password guessing against `/auth/login` and automated account
//! creation through `/auth/signup`, and it bounds what one signed-in account can send and
//! how many people it can reach (see `AppState::count_send` and `count_contact`).
//!
//! Deliberately dependency-free and process-local — a self-hosted NChat is usually a single
//! instance, and a limiter that cannot be bypassed by restarting the app is worth more than
//! one that needs Redis.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Mutex, Once};
use std::time::{Duration, Instant};

use axum::http::HeaderMap;

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

/// The address a request is counted against, as text.
///
/// `peer` is the other end of the TCP connection: the one thing about a request that its
/// sender does not write. `X-Forwarded-For` is a header, so anybody can send any value,
/// and a limit keyed by it is a limit the sender can step around by inventing a new
/// address for every request.
///
/// - `trusted_proxies == 0` (the default): the peer is the client. The header is ignored.
/// - `trusted_proxies == n`: the server is reached only through `n` reverse proxies that
///   each append the address they saw. The client is then the `n`-th entry from the right;
///   everything to its left was written by the client itself. A request with fewer entries
///   than that, or an entry that is not an address, is counted against the peer.
///
/// IPv6 clients are counted per /64, the smallest block a provider hands to one customer:
/// counting single addresses would give every such customer 2^64 budgets.
///
/// Without a peer (a caller that did not go through `main`, such as a test that drives the
/// router directly) every request shares the bucket `"unknown"`.
pub fn client_address(
    peer: Option<SocketAddr>,
    headers: &HeaderMap,
    trusted_proxies: usize,
) -> String {
    let forwarded = if trusted_proxies == 0 {
        if headers.contains_key("x-forwarded-for") {
            warn_about_ignored_header();
        }
        None
    } else {
        forwarded_address(headers, trusted_proxies)
    };
    match forwarded.or_else(|| peer.map(|address| address.ip())) {
        Some(ip) => bucket(ip),
        None => "unknown".to_string(),
    }
}

/// The `hops`-th entry from the right over every `X-Forwarded-For` line, if it is an address.
fn forwarded_address(headers: &HeaderMap, hops: usize) -> Option<IpAddr> {
    let mut entries: Vec<&str> = Vec::new();
    for value in headers.get_all("x-forwarded-for") {
        // A line that is not text says nothing that can be trusted about the others.
        let line = value.to_str().ok()?;
        for entry in line.split(',') {
            entries.push(entry.trim());
        }
    }
    let index = entries.len().checked_sub(hops)?;
    parse_address(entries.get(index)?)
}

/// `203.0.113.5`, `2001:db8::1`, and the forms some proxies write with a port:
/// `203.0.113.5:4711`, `[2001:db8::1]:4711`, `[2001:db8::1]`.
fn parse_address(entry: &str) -> Option<IpAddr> {
    if let Ok(ip) = entry.parse::<IpAddr>() {
        return Some(ip);
    }
    if let Ok(address) = entry.parse::<SocketAddr>() {
        return Some(address.ip());
    }
    entry
        .strip_prefix('[')
        .and_then(|rest| rest.strip_suffix(']'))
        .and_then(|inner| inner.parse::<IpAddr>().ok())
}

fn bucket(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            // `::ffff:203.0.113.5` is an IPv4 client on a dual-stack socket.
            Some(v4) => v4.to_string(),
            None => {
                let part = v6.segments();
                format!(
                    "{:x}:{:x}:{:x}:{:x}::/64",
                    part[0], part[1], part[2], part[3]
                )
            }
        },
    }
}

/// Says once that a forwarding header arrived and was not used. Behind a reverse proxy
/// that is the sign of a missing setting: every client then shares the proxy's address,
/// and with it one budget.
fn warn_about_ignored_header() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        tracing::warn!(
            "a request carried X-Forwarded-For and it was ignored: no proxy is trusted. \
             If this server is behind a reverse proxy, set NCHAT_TRUSTED_PROXIES to the \
             number of proxies in front of it; otherwise all clients share one address \
             for rate limiting"
        );
    });
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

    fn peer(text: &str) -> Option<SocketAddr> {
        Some(text.parse().expect("socket address"))
    }

    fn forwarded(lines: &[&str]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for line in lines {
            headers.append("x-forwarded-for", line.parse().expect("header value"));
        }
        headers
    }

    #[test]
    fn without_a_trusted_proxy_the_peer_is_the_client() {
        let headers = forwarded(&["198.18.0.1"]);
        assert_eq!(
            client_address(peer("203.0.113.9:40000"), &headers, 0),
            "203.0.113.9"
        );
        // Another port on the same machine is the same client.
        assert_eq!(
            client_address(peer("203.0.113.9:40001"), &HeaderMap::new(), 0),
            "203.0.113.9"
        );
        assert_eq!(client_address(None, &headers, 0), "unknown");
    }

    #[test]
    fn a_trusted_proxy_is_believed_about_the_last_hop_only() {
        let proxy = peer("10.0.0.1:55000");
        let headers = forwarded(&["1.2.3.4, 203.0.113.5"]);
        assert_eq!(client_address(proxy, &headers, 1), "203.0.113.5");
        // Two proxies: the second entry from the right, also across header lines.
        let headers = forwarded(&["1.2.3.4, 203.0.113.5", "10.0.0.2"]);
        assert_eq!(client_address(proxy, &headers, 2), "203.0.113.5");
        // Forms with a port.
        let headers = forwarded(&["203.0.113.5:4711"]);
        assert_eq!(client_address(proxy, &headers, 1), "203.0.113.5");
        let headers = forwarded(&["[2001:db8:1:2::7]:4711"]);
        assert_eq!(client_address(proxy, &headers, 1), "2001:db8:1:2::/64");
    }

    #[test]
    fn a_header_that_cannot_be_used_falls_back_to_the_peer() {
        let proxy = peer("10.0.0.1:55000");
        // No header at all, fewer entries than proxies, and text that is not an address.
        assert_eq!(client_address(proxy, &HeaderMap::new(), 1), "10.0.0.1");
        let headers = forwarded(&["203.0.113.5"]);
        assert_eq!(client_address(proxy, &headers, 2), "10.0.0.1");
        let headers = forwarded(&["203.0.113.5, not-an-address"]);
        assert_eq!(client_address(proxy, &headers, 1), "10.0.0.1");
    }

    #[test]
    fn ipv6_clients_are_counted_per_block() {
        let headers = HeaderMap::new();
        let one = client_address(peer("[2001:db8:1:2::7]:443"), &headers, 0);
        let same_block = client_address(peer("[2001:db8:1:2:ffff::9]:443"), &headers, 0);
        let other_block = client_address(peer("[2001:db8:1:3::7]:443"), &headers, 0);
        assert_eq!(one, "2001:db8:1:2::/64");
        assert_eq!(one, same_block);
        assert_ne!(one, other_block);
        // An IPv4 client seen through a dual-stack socket is still that IPv4 client.
        assert_eq!(
            client_address(peer("[::ffff:203.0.113.9]:443"), &headers, 0),
            "203.0.113.9"
        );
    }
}
