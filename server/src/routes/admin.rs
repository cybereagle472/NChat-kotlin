//! `/admin/*` — port of `worker/src/routes/admin.ts`.
//!
//! The reference gates the panel on a hardcoded admin e-mail address. Here the
//! administrator is the account whose `users.is_admin` is set, and nothing else is
//! looked at while a request is handled.
//!
//! An address is not an identity: this server does not verify that an address belongs
//! to whoever signs up with it. If a request were allowed in because the caller's
//! address equals a configured one, then registering that address would be all it takes.
//! So `NCHAT_ADMIN_EMAIL` is read once, at start-up, and turned into the role for the
//! account that has the address at that moment (`AppState::apply_admin_email`).

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::presence::ONLINE_THRESHOLD_MS;
use crate::util::now_millis;
use crate::AppState;

pub async fn stats(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let is_admin: Option<i64> = state.db.with(|conn| {
        use rusqlite::OptionalExtension;
        conn.query_row(
            "SELECT is_admin FROM users WHERE id = ?",
            [&user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    // No default: until a deployment gives somebody the role, nobody has it.
    match is_admin {
        Some(flag) if flag != 0 => {}
        _ => return Err(ApiError::forbidden("Forbidden")),
    }

    let online_since = now_millis() - ONLINE_THRESHOLD_MS;
    let (users, online, messages, groups) = state.db.with(|conn| {
        let users: i64 = conn.query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))?;
        let online: i64 = conn.query_row(
            "SELECT COUNT(*) FROM presence WHERE last_active_at > ?",
            [online_since],
            |row| row.get(0),
        )?;
        let messages: i64 = conn.query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))?;
        let groups: i64 = conn.query_row("SELECT COUNT(*) FROM groups", [], |row| row.get(0))?;
        Ok((users, online, messages, groups))
    })?;

    Ok(Json(json!({
        "totalUsers": users,
        "onlineUsers": online,
        "totalMessages": messages,
        "totalGroups": groups,
    })))
}
