//! NChat backend — a Rust port of `worker/` from `nchat-friends`.
//!
//! The reference is a Cloudflare Worker (Hono) on D1 + R2 + Durable Objects.
//! This crate keeps the same database schema, the same routes, the same JSON
//! (snake_case keys, same wrappers), the same JWT/PBKDF2 formats and the same
//! realtime room contract, so the Kotlin client can talk to either backend
//! unchanged. What changes is only the runtime: axum + tokio + SQLite.

pub mod db;
pub mod error;
pub mod jwt;
pub mod models;
pub mod password;
pub mod presence;
pub mod privacy;
pub mod ratelimit;
pub mod realtime;
pub mod routes;
pub mod util;

use axum::http::{header, HeaderMap, HeaderValue, Method};
use axum::Router;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::trace::TraceLayer;

use crate::db::Db;
use crate::error::{ApiError, ApiResult};
use crate::realtime::Hub;

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Db>,
    pub hub: Hub,
    pub secret: Arc<String>,
    /// `PUBLIC_API_URL` in the reference — used to build absolute media URLs.
    pub public_api_url: Arc<String>,
    /// Stands in for the R2 bucket binding.
    pub media_dir: Arc<PathBuf>,
    /// Largest accepted upload; the worker relied on Cloudflare's request cap, a
    /// self-hosted server must enforce its own (`NCHAT_MAX_UPLOAD_MB`, default 100).
    pub max_upload_bytes: u64,
    /// Throttles `/auth/login` and `/auth/signup` (`NCHAT_AUTH_RATE`, default 10 / 5 min).
    pub auth_limiter: Arc<ratelimit::RateLimiter>,
    /// Sign-in attempts per account, whatever address they claim to come from
    /// (`NCHAT_AUTH_ACCOUNT_RATE`, default three times `NCHAT_AUTH_RATE`, same window).
    /// `auth_limiter`'s key includes an address the client can write; this one has no
    /// such part.
    pub account_limiter: Arc<ratelimit::RateLimiter>,
    /// PBKDF2 rounds for new and upgraded password hashes (`NCHAT_PBKDF2_ITERATIONS`,
    /// default and minimum [`password::ITERATIONS`]). See `password.rs` before raising it.
    pub password_iterations: u32,
    /// When `true` (the default), `/media/file/*` only answers a caller who is part of
    /// the conversation that owns the object, or a short-lived signed link. The
    /// reference relied on R2 bucket privacy; a self-hosted server has to check.
    /// `NCHAT_MEDIA_PUBLIC=1` restores the old public-bucket behaviour.
    pub media_requires_auth: bool,
    /// Lifetime of a signed media link, in seconds (`NCHAT_MEDIA_TTL`, default 600).
    pub media_link_ttl: i64,
    /// How many reverse proxies stand between clients and this server
    /// (`NCHAT_TRUSTED_PROXIES`, default 0). It decides which address a request is counted
    /// against; see [`ratelimit::client_address`].
    pub trusted_proxies: usize,
    /// Password hashes one client address may ask for, sign-in and sign-up together
    /// (`NCHAT_AUTH_ADDRESS_RATE`, default three times `NCHAT_AUTH_RATE`, same window).
    /// The other two credential limits are per account; this one is what stops a single
    /// address from working through a list of accounts, or from creating them.
    pub address_limiter: Arc<ratelimit::RateLimiter>,
    /// Messages, statuses and uploads one account may send (`NCHAT_SEND_RATE`, default
    /// 300 a minute).
    pub send_limiter: Arc<ratelimit::RateLimiter>,
    /// Friend requests, new groups, added members and calls one account may start
    /// (`NCHAT_CONTACT_RATE`, default 30 in five minutes).
    pub contact_limiter: Arc<ratelimit::RateLimiter>,
    /// Web origins a browser may call this API from (`NCHAT_CORS_ORIGINS`). Empty, the
    /// default, means none: the Android client is not a browser and needs no permission.
    /// `*` means any.
    pub cors_origins: Arc<Vec<String>>,
    /// See [`AppState::decoy_password_hash`].
    decoy_hash: Arc<OnceLock<String>>,
}

impl AppState {
    pub fn new(db: Db, secret: impl Into<String>) -> Self {
        Self {
            db: Arc::new(db),
            hub: Hub::new(),
            secret: Arc::new(secret.into()),
            public_api_url: Arc::new(String::new()),
            media_dir: Arc::new(PathBuf::from("data/media")),
            max_upload_bytes: 100 * 1024 * 1024,
            auth_limiter: Arc::new(ratelimit::RateLimiter::new(
                10,
                std::time::Duration::from_secs(300),
            )),
            account_limiter: Arc::new(ratelimit::RateLimiter::new(
                30,
                std::time::Duration::from_secs(300),
            )),
            password_iterations: password::ITERATIONS,
            media_requires_auth: true,
            media_link_ttl: 600,
            trusted_proxies: 0,
            address_limiter: Arc::new(ratelimit::RateLimiter::new(
                30,
                std::time::Duration::from_secs(300),
            )),
            send_limiter: Arc::new(ratelimit::RateLimiter::new(
                300,
                std::time::Duration::from_secs(60),
            )),
            contact_limiter: Arc::new(ratelimit::RateLimiter::new(
                30,
                std::time::Duration::from_secs(300),
            )),
            cors_origins: Arc::new(Vec::new()),
            decoy_hash: Arc::new(OnceLock::new()),
        }
    }

    pub fn with_public_url(mut self, base_url: impl Into<String>) -> Self {
        self.public_api_url = Arc::new(base_url.into());
        self
    }

    pub fn with_media_dir(mut self, dir: impl Into<PathBuf>) -> Self {
        self.media_dir = Arc::new(dir.into());
        self
    }

    pub fn with_max_upload(mut self, bytes: u64) -> Self {
        self.max_upload_bytes = bytes;
        self
    }

    pub fn with_auth_limiter(mut self, limiter: ratelimit::RateLimiter) -> Self {
        self.auth_limiter = Arc::new(limiter);
        self
    }

    pub fn with_account_limiter(mut self, limiter: ratelimit::RateLimiter) -> Self {
        self.account_limiter = Arc::new(limiter);
        self
    }

    /// Rounds for new and upgraded hashes. Tests use small values; `main` never goes
    /// below [`password::ITERATIONS`].
    pub fn with_password_iterations(mut self, iterations: u32) -> Self {
        self.password_iterations = iterations.clamp(1, password::MAX_ITERATIONS);
        // A decoy made for another round count would cost a different amount of time.
        self.decoy_hash = Arc::new(OnceLock::new());
        self
    }

    /// `true` = the old "anyone with the URL can read it" bucket behaviour.
    pub fn with_public_media(mut self, public: bool) -> Self {
        self.media_requires_auth = !public;
        self
    }

    pub fn with_media_link_ttl(mut self, seconds: i64) -> Self {
        self.media_link_ttl = seconds.max(1);
        self
    }

    pub fn with_trusted_proxies(mut self, count: usize) -> Self {
        self.trusted_proxies = count;
        self
    }

    pub fn with_address_limiter(mut self, limiter: ratelimit::RateLimiter) -> Self {
        self.address_limiter = Arc::new(limiter);
        self
    }

    pub fn with_send_limiter(mut self, limiter: ratelimit::RateLimiter) -> Self {
        self.send_limiter = Arc::new(limiter);
        self
    }

    pub fn with_contact_limiter(mut self, limiter: ratelimit::RateLimiter) -> Self {
        self.contact_limiter = Arc::new(limiter);
        self
    }

    pub fn with_cors_origins(mut self, origins: Vec<String>) -> Self {
        self.cors_origins = Arc::new(origins);
        self
    }

    /// Makes the account with this e-mail address the administrator, and nobody else.
    /// Returns whether such an account exists.
    ///
    /// `main` calls this once at start-up when `NCHAT_ADMIN_EMAIL` is set. It is never
    /// called while handling a request: nothing proves that an address belongs to whoever
    /// registers it, so naming an address must not turn the next person to sign up with
    /// it into the administrator. The role lives in `users.is_admin`, and
    /// `routes::admin` reads only that.
    pub fn apply_admin_email(&self, email: &str) -> rusqlite::Result<bool> {
        let email = email.trim().to_lowercase();
        self.db.with(|conn| {
            let tx = conn.transaction()?;
            tx.execute(
                "UPDATE users SET is_admin = 0 WHERE is_admin != 0 AND lower(email) != ?1",
                [&email],
            )?;
            let granted = if email.is_empty() {
                0
            } else {
                tx.execute(
                    "UPDATE users SET is_admin = 1 WHERE lower(email) = ?1",
                    [&email],
                )?
            };
            tx.commit()?;
            Ok(granted > 0)
        })
    }

    /// The address this request is counted against; see [`ratelimit::client_address`].
    pub fn client_address(&self, peer: Option<SocketAddr>, headers: &HeaderMap) -> String {
        ratelimit::client_address(peer, headers, self.trusted_proxies)
    }

    /// A stored hash that no password matches, made with this server's round count.
    ///
    /// Sign-in checks the password against it when the address names no account, so that
    /// "no such account" costs the same work as "wrong password" and the clock does not
    /// tell them apart. Built on first use, because the round count is set after `new`.
    pub fn decoy_password_hash(&self) -> &str {
        self.decoy_hash.get_or_init(|| {
            password::hash_password_with(&util::new_id(), self.password_iterations)
        })
    }

    /// Counts one message, status or upload against the caller's budget.
    pub fn count_send(&self, user_id: &str) -> ApiResult<()> {
        if self.send_limiter.allow(user_id) {
            Ok(())
        } else {
            Err(ApiError::too_many_requests(
                "You are sending too fast — wait a moment and try again",
            ))
        }
    }

    /// Counts one request that reaches somebody who did not ask for it: a friend request,
    /// a new group, added members, a call.
    pub fn count_contact(&self, user_id: &str) -> ApiResult<()> {
        if self.contact_limiter.allow(user_id) {
            Ok(())
        } else {
            Err(ApiError::too_many_requests(
                "Too many requests to other people — try again in a few minutes",
            ))
        }
    }

    /// `requireAuth` middleware — `Authorization: Bearer <jwt>` → user id.
    pub fn current_user(&self, headers: &HeaderMap) -> ApiResult<String> {
        let raw = headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        let token = raw
            .strip_prefix("Bearer ")
            .ok_or_else(|| ApiError::unauthorized("Unauthorized"))?;
        jwt::subject(token, &self.secret)
            .ok_or_else(|| ApiError::unauthorized("Invalid or expired token"))
    }
}

pub fn build_app(state: AppState) -> Router {
    let cors = cors_layer(&state.cors_origins);
    let app = routes::router(state);
    let app = match cors {
        Some(layer) => app.layer(layer),
        None => app,
    };
    app.layer(TraceLayer::new_for_http().make_span_with(
        |request: &axum::http::Request<axum::body::Body>| {
            // WebSocket clients may put a bearer token in the query string.
            // Never put the full URI into application logs.
            tracing::info_span!("http_request", method = %request.method(), path = %request.uri().path())
        },
    ))
}

/// Which websites a browser may call this API from.
///
/// CORS is a permission a server gives to pages on *other* sites. The Android client is
/// not a browser, sends no `Origin` and needs none, so the default is to give none: a
/// page somewhere else can then neither read an answer nor send a request that carries a
/// token. An operator who serves a web client lists its origin, exactly as the browser
/// sends it (`https://chat.example`). A `*` in the list gives the permission to every
/// site, which is what this server did before the setting existed.
fn cors_layer(origins: &[String]) -> Option<CorsLayer> {
    let mut listed: Vec<HeaderValue> = Vec::new();
    for origin in origins {
        let origin = origin.trim().trim_end_matches('/');
        if origin == "*" {
            return Some(CorsLayer::permissive());
        }
        if origin.is_empty() {
            continue;
        }
        match HeaderValue::from_str(origin) {
            Ok(value) => listed.push(value),
            Err(_) => tracing::warn!(origin, "ignoring a web origin that cannot be a header"),
        }
    }
    if listed.is_empty() {
        return None;
    }
    Some(
        CorsLayer::new()
            .allow_origin(AllowOrigin::list(listed))
            .allow_methods([Method::GET, Method::POST, Method::PATCH, Method::DELETE])
            // Named one by one: a wildcard does not cover `Authorization`.
            .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE, header::RANGE])
            .expose_headers([
                header::ACCEPT_RANGES,
                header::CONTENT_LENGTH,
                header::CONTENT_RANGE,
            ]),
    )
}
