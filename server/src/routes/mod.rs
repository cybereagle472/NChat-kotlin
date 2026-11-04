//! Route table — mirrors `worker/src/index.ts` one-for-one.

pub mod auth;
pub mod friends;
pub mod messages;
pub mod privacy;
pub mod users;

use axum::body::Bytes;
use axum::routing::{get, patch, post};
use axum::Router;
use serde_json::{Map, Value};

use crate::realtime;
use crate::AppState;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/", get(root))
        .route("/health", get(health))
        .route("/auth/check-email", get(auth::check_email))
        .route("/auth/signup", post(auth::signup))
        .route("/auth/login", post(auth::login))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/logout", post(auth::logout))
        .route("/auth/me", get(auth::me))
        .route("/users/me", get(users::me).patch(users::update_me))
        .route("/users/username-available", get(users::username_available))
        .route("/users/search", get(users::search))
        .route("/users/presence", post(users::presence))
        .route("/users/:id", get(users::one))
        .route("/users", get(users::list))
        .route("/friends/request", post(friends::send_request))
        .route("/friends/requests", get(friends::requests))
        .route("/friends/sent", get(friends::sent))
        .route("/friends/request/:id", axum::routing::delete(friends::cancel_request))
        .route("/friends/:id/respond", post(friends::respond))
        .route("/friends/block", post(friends::block))
        .route("/friends/unblock", post(friends::unblock))
        .route("/friends/blocked", get(friends::blocked))
        .route("/friends/:friend_id", axum::routing::delete(friends::remove))
        .route("/friends", get(friends::list))
        .route("/messages/with/:other_id", get(messages::with))
        .route("/messages/chats", get(messages::chats))
        .route("/messages/mark-read", post(messages::mark_read))
        .route("/messages/typing", post(messages::typing))
        .route("/messages/recording", post(messages::recording))
        .route("/messages/:id/delete-for-me", post(messages::delete_for_me))
        .route("/messages/:id/reaction", post(messages::reaction))
        .route(
            "/messages/:id",
            patch(messages::edit).delete(messages::delete_for_everyone),
        )
        .route("/messages", post(messages::send))
        .route("/privacy", get(privacy::get_settings).patch(privacy::patch_settings))
        .route("/realtime/connect", get(realtime::connect))
        .with_state(state)
}

async fn root() -> axum::Json<Value> {
    axum::Json(serde_json::json!({ "ok": true, "service": "nchat-api" }))
}

async fn health() -> axum::Json<Value> {
    axum::Json(serde_json::json!({ "ok": true }))
}

/// `await c.req.json().catch(() => ({}))` — lenient body parsing.
pub fn parse_body(bytes: &Bytes) -> Map<String, Value> {
    if bytes.is_empty() {
        return Map::new();
    }
    match serde_json::from_slice::<Value>(bytes) {
        Ok(Value::Object(map)) => map,
        _ => Map::new(),
    }
}

/// `typeof body.x === 'string' ? body.x : default`.
pub fn text_field(body: &Map<String, Value>, key: &str) -> Option<String> {
    body.get(key).and_then(Value::as_str).map(str::to_string)
}

pub fn bool_field(body: &Map<String, Value>, key: &str) -> Option<bool> {
    body.get(key).and_then(Value::as_bool)
}

/// A repeated string field (`memberIds`, `deleted_for`, …).
pub fn string_list(body: &Map<String, Value>, key: &str) -> Vec<String> {
    match body.get(key) {
        Some(Value::Array(items)) => items
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_string)
            .collect(),
        _ => Vec::new(),
    }
}
