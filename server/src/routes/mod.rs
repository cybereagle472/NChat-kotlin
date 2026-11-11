//! Route table — mirrors `worker/src/index.ts` one-for-one.

pub mod admin;
pub mod auth;
pub mod calls;
pub mod friends;
pub mod group_calls;
pub mod groups;
pub mod media;
pub mod messages;
pub mod privacy;
pub mod status;
pub mod users;

use axum::body::Bytes;
use axum::extract::DefaultBodyLimit;
use axum::routing::{get, patch, post};
use axum::Router;
use serde_json::{Map, Value};

use crate::error::{ApiError, ApiResult};
use crate::realtime;
use crate::AppState;

pub fn router(state: AppState) -> Router {
    // Multipart framing needs a little headroom beyond the per-file limit.
    // Keep the default 2 MiB request cap on all other routes.
    let upload_body_limit = usize::try_from(state.max_upload_bytes)
        .unwrap_or(usize::MAX)
        .saturating_add(1024 * 1024);
    Router::new()
        .route("/", get(root))
        .route("/health", get(health))
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
        .route("/messages/with/:other_id/delete-for-me", post(messages::clear_conversation))
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
        .route("/admin/stats", get(admin::stats))
        // ---- groups ----
        .route("/groups/messages/:message_id/delete-for-me", post(groups::delete_message_for_me))
        .route("/groups/messages/:message_id/delete-for-everyone", post(groups::delete_message_for_everyone))
        .route("/groups/messages/:message_id/reaction", post(groups::react_message))
        .route("/groups", get(groups::list).post(groups::create))
        .route(
            "/groups/:id",
            get(groups::one).patch(groups::update).delete(groups::delete),
        )
        .route("/groups/:id/members", get(groups::members).post(groups::add_members))
        .route(
            "/groups/:id/members/:user_id",
            axum::routing::delete(groups::remove_member).patch(groups::set_role),
        )
        .route("/groups/:id/leave", post(groups::leave))
        .route("/groups/:id/mute", patch(groups::mute))
        .route("/groups/:id/messages", get(groups::messages).post(groups::send_message))
        .route("/groups/:id/typing", post(groups::typing))
        .route("/groups/:id/recording", post(groups::recording))
        .route("/groups/:id/read", post(groups::mark_read))
        // ---- status / stories ----
        .route("/status/mine", get(status::mine))
        .route("/status/feed", get(status::feed))
        .route("/status/muted", get(status::muted))
        .route("/status/mute", post(status::mute))
        .route("/status/mute/:muted_user_id", axum::routing::delete(status::unmute))
        .route("/status/:id/view", post(status::view))
        .route("/status/:id/views", get(status::views))
        .route("/status/:id", axum::routing::delete(status::delete))
        .route("/status", post(status::create))
        // ---- 1:1 calls ----
        .route("/calls/turn-credentials", get(calls::turn_credentials))
        .route("/calls/history", get(calls::history))
        .route("/calls/:id/signal", post(calls::signal))
        .route("/calls/:id/answer", post(calls::answer))
        .route("/calls/:id/end", post(calls::end))
        .route("/calls", post(calls::create))
        // ---- group calls ----
        .route("/group-calls", post(group_calls::start))
        .route("/group-calls/:id/join", post(group_calls::join))
        .route("/group-calls/:id/signal", post(group_calls::signal))
        .route("/group-calls/:id/decline", post(group_calls::decline))
        .route("/group-calls/:id/leave", post(group_calls::leave))
        // ---- media ----
        .route("/media/upload", post(media::upload).layer(DefaultBodyLimit::max(upload_body_limit)))
        .route("/media/link", get(media::link))
        .route(
            "/media/file/*key",
            get(media::file).delete(media::delete),
        )
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

/// The keys of a relayed call-signalling event that belong to the server.
///
/// `POST /calls/:id/signal` and `POST /group-calls/:id/signal` copy the request
/// body — opaque WebRTC data — into the event they deliver. Around it sits an
/// envelope that says what the event is, which call it belongs to, who sent it
/// and who it is for. A sender must not be able to write any of that, under any
/// spelling a client reads.
///
/// The spellings matter. The Android client takes the call id of a group-call
/// event from `callId` before `call_id` (then `groupCallId`, `group_call_id`)
/// and the sender from `from`, `from_id`, `senderId`, `sender_id` or `user_id`,
/// and it starts an audio connection to whoever a signal for its current group
/// call is from. If a body could relabel a signal as belonging to a different
/// call, someone who knows that call's id but is not in it — a member who was
/// removed from the group, say — could get a participant's phone to start
/// connecting to them.
const SIGNAL_ENVELOPE_KEYS: [&str; 11] = [
    "type",
    "to",
    "call_id",
    "callId",
    "group_call_id",
    "groupCallId",
    "from",
    "from_id",
    "sender_id",
    "senderId",
    "user_id",
];

/// `true` for a body key that a signal relay must not copy into the event.
pub fn is_signal_envelope_key(key: &str) -> bool {
    SIGNAL_ENVELOPE_KEYS.contains(&key)
}

/// The longest text this server stores in each kind of field, in characters.
///
/// The request body is capped at 2 MiB, which bounds a request but not what is done with
/// it: a display name is copied into every list it appears in, a message is pushed to
/// every member of a group. These are far above anything the clients write (the Android
/// client stops a bio at 150 characters) and far below what hurts.
pub mod limits {
    /// Display names and group names.
    pub const NAME: usize = 100;
    pub const USERNAME: usize = 50;
    pub const BIO: usize = 500;
    /// A group's description.
    pub const DESCRIPTION: usize = 2_048;
    /// The text of a status, and a caption.
    pub const CAPTION: usize = 4_096;
    /// The text of a message.
    pub const MESSAGE: usize = 65_536;
    pub const URL: usize = 2_048;
    /// `thumbnail_url`. Not always an address: a voice note keeps its waveform there (a
    /// short JSON array), and a client may keep a small inline preview there. It is
    /// bounded like text, because this server cannot tell which it is.
    pub const PREVIEW: usize = 65_536;
    /// Ids, type names, colours, durations, file names: short by nature.
    pub const SHORT: usize = 512;
    /// One reaction. An emoji is a handful of code points, even with modifiers.
    pub const EMOJI: usize = 32;
    /// Different reactions on one message.
    pub const REACTIONS: usize = 64;
    pub const EMAIL: usize = 254;
    pub const PASSWORD: usize = 1_024;
    /// A search text; a longer one matches nobody.
    pub const SEARCH: usize = 100;
    /// Ids in one `memberIds` list.
    pub const MEMBERS: usize = 256;
}

/// Refuses a body in which a named text field is longer than its limit.
///
/// Only fields that are present and are text are looked at, so the lenient parsing
/// around this (`text_field`) keeps its meaning.
pub fn ensure_lengths(body: &Map<String, Value>, rules: &[(&str, usize)]) -> ApiResult<()> {
    for (key, max) in rules {
        if let Some(Value::String(text)) = body.get(*key) {
            // A text has at most as many characters as bytes, so short ones are not counted.
            if text.len() > *max && text.chars().count() > *max {
                return Err(ApiError::bad_request(format!(
                    "{key} is too long (at most {max} characters)"
                )));
            }
        }
    }
    Ok(())
}

/// `true` when every one of `ids` names an account.
///
/// The tables that hold other people's ids refer to `users`, so an id that names nobody
/// used to surface as a failed insert: a server error for what is the caller's mistake,
/// after whatever the handler had already written.
pub fn all_users_exist(state: &AppState, ids: &[String]) -> ApiResult<bool> {
    if ids.is_empty() {
        return Ok(true);
    }
    let found = state.db.with(|conn| {
        let mut statement = conn.prepare("SELECT 1 FROM users WHERE id = ?")?;
        for id in ids {
            if !statement.exists([id])? {
                return Ok(false);
            }
        }
        Ok(true)
    })?;
    Ok(found)
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
