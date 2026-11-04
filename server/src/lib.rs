//! NChat backend: a small, fast realtime service for the Kotlin client.
//!
//! Design rules that keep it instant:
//! * state lives in memory, guarded by an `RwLock` — reads never touch the disk;
//! * writes append to the in-memory tree and snapshot atomically (`db.json`);
//! * realtime fan-out is one `tokio::sync::broadcast` channel for all sessions;
//! * no per-request allocation beyond the JSON body.

pub mod api;
pub mod auth;
pub mod error;
pub mod model;
pub mod store;
pub mod ws;

use axum::http::{header, HeaderMap};
use axum::Router;
use std::sync::Arc;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::error::ApiError;
use crate::model::{now_ms, Id};
use crate::store::Store;
use crate::ws::Hub;

/// Default lifetime of a bearer token (30 days).
pub const DEFAULT_TOKEN_TTL_SECS: i64 = 30 * 24 * 60 * 60;

#[derive(Clone)]
pub struct AppState {
    pub store: Arc<Store>,
    pub hub: Hub,
    pub secret: Arc<Vec<u8>>,
    pub token_ttl_secs: i64,
}

impl AppState {
    pub fn new(store: Store, secret: Vec<u8>, token_ttl_secs: i64) -> Self {
        Self {
            store: Arc::new(store),
            hub: Hub::new(),
            secret: Arc::new(secret),
            token_ttl_secs,
        }
    }

    pub fn issue_token(&self, user_id: &str) -> Option<String> {
        auth::issue_token(&self.secret, user_id, now_ms(), self.token_ttl_secs)
    }

    /// `Authorization: Bearer <token>` → the current user id.
    pub fn require_user(&self, headers: &HeaderMap) -> Result<Id, ApiError> {
        let raw = headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        let token = raw
            .strip_prefix("Bearer ")
            .or_else(|| raw.strip_prefix("bearer "))
            .unwrap_or_default();
        if token.is_empty() {
            return Err(ApiError::Unauthorized);
        }
        let user_id = auth::verify_token(&self.secret, token, now_ms()).ok_or(ApiError::Unauthorized)?;
        if self.store.user(&user_id).is_none() {
            return Err(ApiError::Unauthorized);
        }
        Ok(user_id)
    }
}

pub fn build_app(state: AppState) -> Router {
    api::router(state)
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
}
