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
}

impl AppState {
    pub fn new(db: Db, secret: impl Into<String>) -> Self {
        Self {
            db: Arc::new(db),
            hub: Hub::new(),
            secret: Arc::new(secret.into()),
            public_api_url: Arc::new(String::new()),
            media_dir: Arc::new(PathBuf::from("data/media")),
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
        .layer(TraceLayer::new_for_http())
}
