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

use axum::http::{header, HeaderMap};
use axum::Router;
use std::path::PathBuf;
use std::sync::Arc;
use tower_http::cors::CorsLayer;
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
    routes::router(state)
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http().make_span_with(
            |request: &axum::http::Request<axum::body::Body>| {
                // WebSocket clients may put a bearer token in the query string.
                // Never put the full URI into application logs.
                tracing::info_span!("http_request", method = %request.method(), path = %request.uri().path())
            },
        ))
}
