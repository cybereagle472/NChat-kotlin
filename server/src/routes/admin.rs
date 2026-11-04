//! `/admin/*` — port of `worker/src/routes/admin.ts`.
//!
//! The reference gates the panel on a hardcoded admin email rather than the
//! `is_admin` column. The e-mail address that may reach these routes is deployment
//! configuration (`NCHAT_ADMIN_EMAIL`) — it is deliberately not baked into the source.

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

    let email: Option<String> = state.db.with(|conn| {
        use rusqlite::OptionalExtension;
        conn.query_row("SELECT email FROM users WHERE id = ?", [&user_id], |row| {
            row.get(0)
        })
        .optional()
    })?;

    // No default: a self-hosted server must name its own admin, otherwise nobody is one.
    let admin_email = std::env::var("NCHAT_ADMIN_EMAIL").unwrap_or_default().trim().to_lowercase();
    if admin_email.is_empty() {
        return Err(ApiError::forbidden(
            "No administrator is configured (set NCHAT_ADMIN_EMAIL)",
        ));
    }
    match email {
        Some(email) if email.eq_ignore_ascii_case(&admin_email) => {}
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
