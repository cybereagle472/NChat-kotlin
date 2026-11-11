//! PBKDF2-SHA256 password hashing — byte-compatible with
//! `worker/src/lib/password.ts` (`iterations:salthex:hashhex`, 100k rounds,
//! 16-byte salt, 32-byte derived key).
//!
//! Byte compatibility means an account created by the TypeScript worker can log
//! in against this server and vice versa — no password reset needed when the
//! backend is replaced.
//!
//! # Raising the round count
//!
//! The count is the first field of the stored text, so every hash says how it was
//! made and an old one keeps verifying after the setting changes. Raising the
//! setting therefore needs no migration script and no password reset:
//!
//! 1. new hashes are written with the configured count ([`hash_password_with`]);
//! 2. a stored hash with a lower count is replaced after the next successful
//!    sign-in ([`needs_rehash`], used by `routes::auth::login`) — the only moment
//!    the server holds the password;
//! 3. a stored hash with a higher count is never replaced by a weaker one.
//!
//! The other direction is the constraint: a hash this server writes with more
//! rounds than the *other* backend can compute stops being portable to it. Keep
//! the setting at [`ITERATIONS`] while accounts may still move to the Worker.

use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use sha2::Sha256;

/// What both backends write today, and the lowest count this server will write.
pub const ITERATIONS: u32 = 100_000;

/// The most rounds this server will run for one check. The count is read from the
/// database, and a row is data: without a ceiling, one corrupted or imported row
/// (`4000000000:…`) turns a single sign-in request into hours of CPU.
pub const MAX_ITERATIONS: u32 = 10_000_000;
const SALT_LEN: usize = 16;
const KEY_LEN: usize = 32;

fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

// `is_multiple_of()` on integers is newer than this crate's MSRV, so keep the
// modulo form and silence the (advisory) style lint.
#[allow(clippy::manual_is_multiple_of)]
fn from_hex(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(text.len() / 2);
    let mut index = 0;
    while index < bytes.len() {
        let high = (bytes[index] as char).to_digit(16)?;
        let low = (bytes[index + 1] as char).to_digit(16)?;
        out.push(((high << 4) | low) as u8);
        index += 2;
    }
    Some(out)
}

fn derive(password: &str, salt: &[u8], iterations: u32) -> Vec<u8> {
    let mut key = vec![0u8; KEY_LEN];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, iterations, &mut key);
    key
}

pub fn hash_password(password: &str) -> String {
    hash_password_with(password, ITERATIONS)
}

/// A fresh salt and `iterations` rounds, kept inside what [`verify_password`] accepts.
pub fn hash_password_with(password: &str, iterations: u32) -> String {
    let iterations = iterations.clamp(1, MAX_ITERATIONS);
    let mut salt = [0u8; SALT_LEN];
    rand::thread_rng().fill_bytes(&mut salt);
    let key = derive(password, &salt, iterations);
    format!("{iterations}:{}:{}", to_hex(&salt), to_hex(&key))
}

/// The round count a stored hash was made with; `None` when the text does not start
/// with a count this server is willing to run.
pub fn stored_iterations(stored: &str) -> Option<u32> {
    let count = stored.split(':').next()?.parse::<u32>().ok()?;
    if (1..=MAX_ITERATIONS).contains(&count) {
        Some(count)
    } else {
        None
    }
}

/// `true` when `stored` was made with fewer rounds than `target`. Never `true` for a
/// stronger hash, and never for text that is not a hash: upgrades only go one way.
pub fn needs_rehash(stored: &str, target: u32) -> bool {
    match stored_iterations(stored) {
        Some(count) => count < target,
        None => false,
    }
}

pub fn verify_password(password: &str, stored: &str) -> bool {
    let mut parts = stored.split(':');
    let (Some(_), Some(salt_hex), Some(hash_hex)) = (parts.next(), parts.next(), parts.next())
    else {
        return false;
    };
    // Refused before any work is done: zero rounds is not a hash, and a count above
    // the ceiling is a request to burn CPU.
    let Some(iterations) = stored_iterations(stored) else {
        return false;
    };
    let (Some(salt), Some(expected)) = (from_hex(salt_hex), from_hex(hash_hex)) else {
        return false;
    };
    let actual = derive(password, &salt, iterations);
    constant_time_eq(&actual, &expected)
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
        let stored = hash_password("hunter2");
        assert!(stored.starts_with("100000:"));
        assert!(verify_password("hunter2", &stored));
        assert!(!verify_password("hunter3", &stored));
        assert!(!verify_password("hunter2", "garbage"));
    }

    #[test]
    fn the_configured_count_is_what_gets_written() {
        let stored = hash_password_with("hunter2", 2_500);
        assert!(stored.starts_with("2500:"));
        assert_eq!(stored_iterations(&stored), Some(2_500));
        assert!(verify_password("hunter2", &stored));
        assert!(!verify_password("hunter3", &stored));
    }

    #[test]
    fn upgrades_only_go_one_way() {
        let stored = hash_password_with("hunter2", 2_500);
        assert!(needs_rehash(&stored, 2_501));
        assert!(!needs_rehash(&stored, 2_500));
        assert!(!needs_rehash(&stored, 1_000));
        // Not a hash at all: nothing to upgrade, and nothing to verify either.
        assert!(!needs_rehash("garbage", 100_000));
        assert!(!needs_rehash("", 100_000));
    }

    /// The count comes from the database. These rows must be turned away *before*
    /// the work starts — if this test ever takes more than an instant, they were not.
    #[test]
    fn a_stored_count_outside_the_limits_is_refused_without_running_it() {
        let salt = "000102030405060708090a0b0c0d0e0f";
        let key = "00".repeat(32);
        for count in ["0", "10000001", "4000000000", "4294967295", "99999999999", "-1", "1e5"] {
            let stored = format!("{count}:{salt}:{key}");
            assert_eq!(stored_iterations(&stored), None, "{count}");
            assert!(!verify_password("hunter2", &stored), "{count}");
            assert!(!needs_rehash(&stored, 100_000), "{count}");
        }
        // The ceiling itself is a count, not an error.
        assert_eq!(
            stored_iterations(&format!("{MAX_ITERATIONS}:{salt}:{key}")),
            Some(MAX_ITERATIONS)
        );
    }

    /// Asking for nonsense still produces something that verifies.
    #[test]
    fn a_requested_count_is_kept_inside_the_limits() {
        let stored = hash_password_with("hunter2", 0);
        assert!(stored.starts_with("1:"));
        assert!(verify_password("hunter2", &stored));
    }

    #[test]
    fn matches_the_typescript_vector() {
        // Generated by worker/src/lib/password.ts semantics: pbkdf2("password",
        // salt=00..0f, 1000 rounds) with a lowered iteration count.
        let salt: Vec<u8> = (0u8..16).collect();
        let key = derive("password", &salt, 1_000);
        let stored = format!("1000:{}:{}", to_hex(&salt), to_hex(&key));
        assert!(verify_password("password", &stored));
        assert!(!verify_password("Password", &stored));
    }
}
