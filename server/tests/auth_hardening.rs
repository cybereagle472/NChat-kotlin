//! Sign-in hardening (agent.md §33, Phase 1 items 7 and 8).
//!
//! Two questions about `/auth/login`, each asked through the API and answered
//! by reading the database back:
//!
//! 1. **How many guesses does somebody get at one account?** The limiter's key
//!    used to be "the address the request claims to come from, plus the e-mail",
//!    and the address is a header the client writes. A new claimed address for
//!    every request was a new budget for every request.
//! 2. **What happens to a password hash that is weaker than today's setting?**
//!    The stored form is `rounds:salt:key`, so an old hash still verifies. The
//!    server holds the password only while a sign-in is being checked, and that
//!    is when a weaker hash has to be replaced.
//!
//! The stored hashes below were computed with Python's
//! `hashlib.pbkdf2_hmac("sha256", b"secret1", salt, rounds, 32)`, not with this
//! crate: they stand for rows copied in from another deployment or from the
//! Cloudflare Worker, which writes the same `rounds:salt:key` text.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::db::Db;
use nchat_server::{build_app, AppState};
use rusqlite::params;
use serde_json::{json, Value};
use tower::ServiceExt;

const SECRET: &str = "test-secret";
const PASSWORD: &str = "secret1";

/// "secret1" at 1 000 rounds: one hundredth of what this server writes today.
const WEAK_HASH: &str = "1000:000102030405060708090a0b0c0d0e0f:\
6f8926ce4ca5c25a52786dd2e12bc46e17bd289c4e073a8f6d047e7efc748c88";

/// "secret1" at 150 000 rounds: more than this server writes by default.
const STRONG_HASH: &str = "150000:f0e1d2c3b4a5968778695a4b3c2d1e0f:\
c3c61d43be9bfaeb9eac3c1b86d33a675058ed9bce787720469369d5754c9593";

struct TestApp {
    router: Router,
    /// Shares the database with `router`.
    state: AppState,
    _dir: tempfile::TempDir,
}

fn app() -> TestApp {
    let dir = tempfile::tempdir().expect("temp dir");
    let db = Db::open(dir.path().join("nchat.sqlite"), 2).expect("db");
    let state = AppState::new(db, SECRET)
        .with_public_url("http://test.local")
        .with_media_dir(dir.path().join("media"));
    TestApp {
        router: build_app(state.clone()),
        state,
        _dir: dir,
    }
}

/// The number in front of the first colon of a stored hash.
fn rounds_of(stored: &str) -> u32 {
    stored
        .split(':')
        .next()
        .unwrap_or_default()
        .parse()
        .unwrap_or(0)
}

/// The salt of a stored hash, as written.
fn salt_of(stored: &str) -> String {
    stored.split(':').nth(1).unwrap_or_default().to_string()
}

impl TestApp {
    /// `claimed_address` is sent as `X-Forwarded-For`: what the request says about
    /// where it comes from. Nothing in these tests makes it true.
    async fn post(
        &self,
        uri: &str,
        claimed_address: Option<&str>,
        body: Value,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder()
            .method("POST")
            .uri(uri)
            .header("content-type", "application/json");
        if let Some(address) = claimed_address {
            builder = builder.header("x-forwarded-for", address);
        }
        let request = builder
            .body(Body::from(body.to_string()))
            .expect("request");
        let response = self.router.clone().oneshot(request).await.expect("response");
        let status = response.status();
        let bytes = response
            .into_body()
            .collect()
            .await
            .expect("body")
            .to_bytes();
        let value = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        (status, value)
    }

    async fn signup(&self, email: &str) {
        let (status, body) = self
            .post(
                "/auth/signup",
                None,
                json!({ "email": email, "password": PASSWORD, "full_name": "Test" }),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "signup {email}: {body}");
    }

    async fn login(
        &self,
        email: &str,
        password: &str,
        claimed_address: Option<&str>,
    ) -> StatusCode {
        let (status, _) = self
            .post(
                "/auth/login",
                claimed_address,
                json!({ "email": email, "password": password }),
            )
            .await;
        status
    }

    /// What is in `users.password_hash` right now.
    fn stored_hash(&self, email: &str) -> String {
        self.state
            .db
            .with(|conn| {
                conn.query_row(
                    "SELECT password_hash FROM users WHERE email = ?",
                    params![email],
                    |row| row.get::<_, String>(0),
                )
            })
            .expect("stored hash")
    }

    /// Replaces the stored hash, the way a row copied from another database would arrive.
    fn copy_in_hash(&self, email: &str, hash: &str) {
        let changed = self
            .state
            .db
            .with(|conn| {
                conn.execute(
                    "UPDATE users SET password_hash = ? WHERE email = ?",
                    params![hash, email],
                )
            })
            .expect("update");
        assert_eq!(changed, 1, "no account for {email}");
    }
}

// ---------------------------------------------------------------- stored hashes

/// The count in front of the hash is what it was made with, so a hash made with
/// another count is still the same password. This held before; it must keep holding.
#[tokio::test]
async fn a_hash_copied_from_another_backend_is_still_the_same_password() {
    let app = app();
    app.signup("ada@test.local").await;

    for copied in [WEAK_HASH, STRONG_HASH] {
        app.copy_in_hash("ada@test.local", copied);
        assert_eq!(
            app.login("ada@test.local", PASSWORD, None).await,
            StatusCode::OK,
            "the right password was refused for {copied}"
        );
        // Put the copy back: the sign-in above is allowed to have upgraded it.
        app.copy_in_hash("ada@test.local", copied);
        assert_eq!(
            app.login("ada@test.local", "secret2", None).await,
            StatusCode::UNAUTHORIZED,
            "a wrong password was accepted for {copied}"
        );
    }
}

/// A hash weaker than today's setting is replaced at the next successful sign-in:
/// that is the only time the server has the password. Without this, raising the
/// setting protects new accounts only and every existing one stays where it was.
#[tokio::test]
async fn a_weaker_stored_hash_is_upgraded_by_the_next_successful_sign_in() {
    let app = app();
    app.signup("ada@test.local").await;
    let written_today = rounds_of(&app.stored_hash("ada@test.local"));
    assert!(written_today > 1_000, "signup wrote {written_today} rounds");

    app.copy_in_hash("ada@test.local", WEAK_HASH);
    assert_eq!(
        app.login("ada@test.local", PASSWORD, None).await,
        StatusCode::OK
    );

    let stored = app.stored_hash("ada@test.local");
    assert_ne!(stored, WEAK_HASH, "the 1 000-round hash is still stored");
    assert_eq!(
        rounds_of(&stored),
        written_today,
        "upgraded to something other than today's setting: {stored}"
    );
    assert_ne!(
        salt_of(&stored),
        salt_of(WEAK_HASH),
        "the upgrade kept the old salt"
    );

    // The new hash is the same password, and only that password.
    assert_eq!(
        app.login("ada@test.local", PASSWORD, None).await,
        StatusCode::OK
    );
    assert_eq!(
        app.login("ada@test.local", "secret2", None).await,
        StatusCode::UNAUTHORIZED
    );
    // A second sign-in has nothing left to upgrade.
    assert_eq!(app.stored_hash("ada@test.local"), stored);
}

#[tokio::test]
async fn a_wrong_password_never_rewrites_the_stored_hash() {
    let app = app();
    app.signup("ada@test.local").await;
    app.copy_in_hash("ada@test.local", WEAK_HASH);

    assert_eq!(
        app.login("ada@test.local", "secret2", None).await,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(app.stored_hash("ada@test.local"), WEAK_HASH);
}

/// Upgrades go one way. A hash made with more rounds than this server is set to
/// (a stricter deployment, a later setting that was turned back) is left as it is.
#[tokio::test]
async fn a_stronger_stored_hash_is_left_alone() {
    let app = app();
    app.signup("ada@test.local").await;
    app.copy_in_hash("ada@test.local", STRONG_HASH);

    assert_eq!(
        app.login("ada@test.local", PASSWORD, None).await,
        StatusCode::OK
    );
    assert_eq!(app.stored_hash("ada@test.local"), STRONG_HASH);
}

// ---------------------------------------------------------------- guessing

/// Sixty guesses at one account, each claiming to come from a different address.
/// However many the server is willing to check, it is fewer than fifty, and once
/// it stops checking it stops for everybody: the next request with the right
/// password is refused as well, because checking it would be checking a guess.
#[tokio::test]
async fn a_new_claimed_address_for_every_guess_does_not_buy_more_guesses() {
    let app = app();
    app.signup("ada@test.local").await;
    // Cheap to check: this test is about counting, not about hashing.
    app.copy_in_hash("ada@test.local", WEAK_HASH);

    let mut answers = Vec::new();
    for guess in 0..60 {
        let claimed = format!("203.0.113.{guess}");
        let wrong = format!("guess-{guess}");
        answers.push(app.login("ada@test.local", &wrong, Some(&claimed)).await);
    }

    let checked = answers
        .iter()
        .filter(|status| **status == StatusCode::UNAUTHORIZED)
        .count();
    assert!(
        answers[50..]
            .iter()
            .all(|status| *status == StatusCode::TOO_MANY_REQUESTS),
        "{checked} of 60 guesses were checked; the last ten answers were {:?}",
        &answers[50..]
    );
    assert_eq!(
        app.login("ada@test.local", PASSWORD, Some("198.51.100.7")).await,
        StatusCode::TOO_MANY_REQUESTS,
        "a request from yet another claimed address was checked"
    );
    // Nothing was learned and nothing was changed along the way.
    assert_eq!(app.stored_hash("ada@test.local"), WEAK_HASH);

    // The limit belongs to the account that was attacked, not to the server.
    app.signup("grace@test.local").await;
    assert_eq!(
        app.login("grace@test.local", PASSWORD, None).await,
        StatusCode::OK
    );
}

/// The other side of that limit: a handful of wrong guesses by somebody else must
/// not shut the owner out of their own account.
#[tokio::test]
async fn a_few_bad_guesses_from_elsewhere_do_not_lock_the_owner_out() {
    let app = app();
    app.signup("ada@test.local").await;
    app.copy_in_hash("ada@test.local", WEAK_HASH);

    for guess in 0..5 {
        let claimed = format!("203.0.113.{guess}");
        assert_eq!(
            app.login("ada@test.local", "not-it", Some(&claimed)).await,
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        app.login("ada@test.local", PASSWORD, None).await,
        StatusCode::OK
    );
}
