//! `/users/*` — port of `worker/src/routes/users.ts`.

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::HeaderMap;
use axum::Json;
use rusqlite::{params, params_from_iter, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::models::UserRow;
use crate::presence::{get_presence, get_presence_map, heartbeat};
use crate::privacy::{
    apply_privacy, apply_privacy_to_list, are_friends, get_privacy_settings,
};
use crate::routes::{parse_body, text_field};
use crate::util::{clamp_limit, now_iso};
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct SearchQuery {
    q: Option<String>,
    limit: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UsernameQuery {
    username: Option<String>,
}

pub async fn me(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let (row, last_active) = state.db.with(|conn| {
        let row = conn
            .query_row("SELECT * FROM users WHERE id = ?", params![&user_id], UserRow::from_row)
            .optional()?;
        let presence = get_presence(conn, &user_id)?;
        Ok((row, presence))
    })?;
    let user = row.ok_or_else(|| ApiError::not_found("Not found"))?;
    Ok(Json(json!({ "profile": user.profile(last_active) })))
}

pub async fn update_me(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);

    let mut fields: Vec<&str> = Vec::new();
    let mut values: Vec<String> = Vec::new();

    if let Some(name) = text_field(&body, "full_name") {
        fields.push("full_name = ?");
        values.push(name.trim().to_string());
    }
    if let Some(bio) = text_field(&body, "bio") {
        fields.push("bio = ?");
        values.push(bio.trim().to_string());
    }
    if let Some(avatar) = text_field(&body, "avatar_url") {
        fields.push("avatar_url = ?");
        values.push(avatar);
    }
    if let Some(raw) = text_field(&body, "username") {
        let username = raw.trim().to_lowercase();
        let taken: Option<String> = state.db.with(|conn| {
            conn.query_row(
                "SELECT id FROM users WHERE username = ? AND id != ?",
                params![username, user_id],
                |row| row.get(0),
            )
            .optional()
        })?;
        if taken.is_some() {
            return Err(ApiError::conflict("Username already taken"));
        }
        fields.push("username = ?");
        values.push(username);
        fields.push("username_last_changed = ?");
        values.push(now_iso());
    }

    if fields.is_empty() {
        return Err(ApiError::bad_request("No fields to update"));
    }

    let sql = format!("UPDATE users SET {} WHERE id = ?", fields.join(", "));
    let updated = state.db.with(|conn| {
        let mut bound: Vec<&dyn rusqlite::ToSql> = values.iter().map(|v| v as &dyn rusqlite::ToSql).collect();
        bound.push(&user_id);
        conn.execute(&sql, params_from_iter(bound))?;
        let row = conn
            .query_row("SELECT * FROM users WHERE id = ?", params![&user_id], UserRow::from_row)
            .optional()?;
        let presence = get_presence(conn, &user_id)?;
        Ok((row, presence))
    })?;

    let (row, last_active) = updated;
    let user = row.ok_or_else(|| ApiError::not_found("Not found"))?;
    Ok(Json(json!({ "profile": user.profile(last_active) })))
}

pub async fn username_available(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<UsernameQuery>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let username = query.username.unwrap_or_default().trim().to_lowercase();
    if username.is_empty() {
        return Ok(Json(json!({ "available": false })));
    }
    let taken: Option<String> = state.db.with(|conn| {
        conn.query_row(
            "SELECT id FROM users WHERE username = ? AND id != ?",
            params![username, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    Ok(Json(json!({ "available": taken.is_none() })))
}

/// Escape `%`, `_` and `\` so a literal search behaves literally (the
/// reference does the same with `ESCAPE '\'`).
fn escape_like(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        if ch == '\\' || ch == '%' || ch == '_' {
            out.push('\\');
        }
        out.push(ch);
    }
    out
}

pub async fn search(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<SearchQuery>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let raw = query.q.unwrap_or_default().trim().to_string();
    let term = raw.strip_prefix('@').unwrap_or(&raw).trim().to_string();
    if term.is_empty() {
        return Ok(Json(json!({ "profiles": [] })));
    }

    let lower = term.to_lowercase();
    let contains = format!("%{}%", escape_like(&lower));
    let prefix = format!("{}%", escape_like(&lower));
    let limit = clamp_limit(query.limit.as_deref(), 30, 50);

    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT u.* FROM users u
             WHERE u.id != ?
               AND u.id NOT IN (SELECT blocked_user_id FROM blocked_users WHERE user_id = ?)
               AND u.id NOT IN (SELECT user_id FROM blocked_users WHERE blocked_user_id = ?)
               AND (LOWER(u.username) LIKE ? ESCAPE '\\' OR LOWER(u.full_name) LIKE ? ESCAPE '\\')
             ORDER BY
               CASE
                 WHEN LOWER(u.username) = ? THEN 0
                 WHEN LOWER(u.username) LIKE ? ESCAPE '\\' THEN 1
                 WHEN LOWER(u.full_name) LIKE ? ESCAPE '\\' THEN 2
                 ELSE 3
               END,
               u.username ASC,
               u.full_name ASC
             LIMIT ?",
        )?;
        let mapped = statement.query_map(
            params![user_id, user_id, user_id, contains, contains, lower, prefix, prefix, limit as i64],
            UserRow::from_row,
        )?;
        let mut users = Vec::new();
        for row in mapped {
            users.push(row?);
        }
        Ok(users)
    })?;

    let ids: Vec<String> = rows.iter().map(|user| user.id.clone()).collect();
    let mut profiles: Vec<Value> = {
        let presence = state.db.with(|conn| get_presence_map(conn, &ids))?;
        rows.iter()
            .map(|user| user.profile(presence.get(&user.id).copied()))
            .collect()
    };
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;

    Ok(Json(json!({ "profiles": profiles })))
}

pub async fn one(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let viewer_id = state.current_user(&headers)?;
    let (row, last_active) = state.db.with(|conn| {
        let row = conn
            .query_row("SELECT * FROM users WHERE id = ?", params![&id], UserRow::from_row)
            .optional()?;
        let presence = get_presence(conn, &id)?;
        Ok((row, presence))
    })?;
    let user = row.ok_or_else(|| ApiError::not_found("Not found"))?;
    let mut profile = user.profile(last_active);

    if id != viewer_id {
        let (settings, is_friend) = state.db.with(|conn| {
            Ok((get_privacy_settings(conn, &id)?, are_friends(conn, &viewer_id, &id)?))
        })?;
        apply_privacy(&mut profile, &viewer_id, &settings, is_friend);
    }

    Ok(Json(json!({ "profile": profile })))
}

pub async fn list(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT u.* FROM users u
             WHERE u.id != ?
               AND u.id NOT IN (SELECT blocked_user_id FROM blocked_users WHERE user_id = ?)
               AND u.id NOT IN (SELECT user_id FROM blocked_users WHERE blocked_user_id = ?)
             ORDER BY u.created_at DESC
             LIMIT 100",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id], UserRow::from_row)?;
        let mut users = Vec::new();
        for row in mapped {
            users.push(row?);
        }
        Ok(users)
    })?;

    let ids: Vec<String> = rows.iter().map(|user| user.id.clone()).collect();
    let mut profiles: Vec<Value> = {
        let presence = state.db.with(|conn| get_presence_map(conn, &ids))?;
        rows.iter()
            .map(|user| user.profile(presence.get(&user.id).copied()))
            .collect()
    };
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;

    Ok(Json(json!({ "profiles": profiles })))
}

pub async fn presence(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    state.db.with(|conn| heartbeat(conn, &user_id))?;
    Ok(Json(json!({ "success": true })))
}
