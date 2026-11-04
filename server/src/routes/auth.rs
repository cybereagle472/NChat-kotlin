//! `/auth/*` — port of `worker/src/routes/auth.ts`.

use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::models::UserRow;
use crate::password;
use crate::ratelimit::client_key;
use crate::routes::{parse_body, text_field};
use crate::util::{iso_in_secs, new_id, now_iso};
use crate::{jwt, AppState};

const ACCESS_TTL_SECS: i64 = 15 * 60;
const REFRESH_TTL_SECS: i64 = 30 * 24 * 60 * 60;

#[derive(Debug, Deserialize)]
pub struct EmailQuery {
    email: Option<String>,
}

/// `\w+@\w+\.\w+` with the same constraints as the reference regex
/// (`^[^\s@]+@[^\s@]+\.[^\s@]+$`).
fn email_valid(email: &str) -> bool {
    let mut parts = email.split('@');
    let (Some(local), Some(domain), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    if local.is_empty() || domain.is_empty() {
        return false;
    }
    if local.contains(char::is_whitespace) || domain.contains(char::is_whitespace) {
        return false;
    }
    match domain.rsplit_once('.') {
        Some((head, tail)) => !head.is_empty() && !tail.is_empty(),
        None => false,
    }
}

fn sweep_expired_sessions(state: &AppState) -> ApiResult<()> {
    let now = now_iso();
    state.db.with(|conn| {
        conn.execute("DELETE FROM sessions WHERE expires_at < ?", [now])?;
        Ok(())
    })?;
    Ok(())
}

/// Mints a session row plus the access/refresh pair.
fn issue_tokens(state: &AppState, user_id: &str) -> ApiResult<Value> {
    let sid = new_id();
    let expires_at = iso_in_secs(REFRESH_TTL_SECS);
    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)",
            params![sid, user_id, expires_at],
        )?;
        Ok(())
    })?;
    sweep_expired_sessions(state)?;

    let access = jwt::sign(&json!({ "sub": user_id }), &state.secret, ACCESS_TTL_SECS)
        .ok_or_else(|| ApiError::internal("could not sign access token"))?;
    let refresh = jwt::sign(
        &json!({ "sub": user_id, "sid": sid }),
        &state.secret,
        REFRESH_TTL_SECS,
    )
    .ok_or_else(|| ApiError::internal("could not sign refresh token"))?;

    Ok(json!({
        "access_token": access,
        "refresh_token": refresh,
        "expires_in": ACCESS_TTL_SECS,
    }))
}

fn find_by_email(state: &AppState, email: &str) -> ApiResult<Option<UserRow>> {
    let email = email.to_string();
    let row = state.db.with(|conn| {
        conn.query_row("SELECT * FROM users WHERE email = ?", params![email], UserRow::from_row)
            .optional()
    })?;
    Ok(row)
}

pub async fn check_email(
    State(state): State<AppState>,
    Query(query): Query<EmailQuery>,
) -> ApiResult<Json<Value>> {
    let email = query
        .email
        .unwrap_or_default()
        .trim()
        .to_lowercase();
    if email.is_empty() || !email_valid(&email) {
        return Ok(Json(json!({ "valid": false, "exists": false })));
    }
    let exists = find_by_email(&state, &email)?.is_some();
    Ok(Json(json!({ "valid": true, "exists": exists })))
}

pub async fn signup(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let body = parse_body(&body);
    let email = text_field(&body, "email")
        .unwrap_or_default()
        .trim()
        .to_lowercase();

    if !state.auth_limiter.allow(&client_key(&headers, &email)) {
        return Err(ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many attempts — try again in a few minutes",
        ));
    }
    let password = text_field(&body, "password").unwrap_or_default();
    let full_name = text_field(&body, "full_name").unwrap_or_default().trim().to_string();

    if email.is_empty() || password.is_empty() {
        return Err(ApiError::bad_request("email and password are required"));
    }
    if !email_valid(&email) {
        return Err(ApiError::bad_request("Please enter a valid email address"));
    }
    if password.len() < 6 {
        return Err(ApiError::bad_request("Password must be at least 6 characters"));
    }
    if find_by_email(&state, &email)?.is_some() {
        return Err(ApiError::conflict("Email is already registered"));
    }

    let id = new_id();
    let password_hash = password::hash_password(&password);
    let created_at = now_iso();

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO users (id, email, password_hash, full_name, created_at)
             VALUES (?, ?, ?, ?, ?)",
            params![id, email, password_hash, full_name, created_at],
        )?;
        conn.execute(
            "INSERT INTO privacy_settings (user_id) VALUES (?)",
            params![&id],
        )?;
        conn.execute(
            "INSERT INTO notification_settings (user_id) VALUES (?)",
            params![&id],
        )?;
        Ok(())
    })?;

    let tokens = issue_tokens(&state, &id)?;
    let user = state
        .db
        .with(|conn| {
            conn.query_row("SELECT * FROM users WHERE id = ?", params![&id], UserRow::from_row)
                .optional()
        })?
        .ok_or_else(|| ApiError::internal("user vanished after signup"))?;

    let mut response = tokens;
    response["user"] = user.user_public();
    Ok((StatusCode::CREATED, Json(response)))
}

pub async fn login(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let body = parse_body(&body);
    let email = text_field(&body, "email")
        .unwrap_or_default()
        .trim()
        .to_lowercase();
    let password = text_field(&body, "password").unwrap_or_default();

    // Password guessing is the cheapest attack on a private chat server: throttle per
    // (client, address) pair instead of only per client, so a shared NAT does not lock
    // out everyone behind it.
    if !state.auth_limiter.allow(&client_key(&headers, &email)) {
        return Err(ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many attempts — try again in a few minutes",
        ));
    }

    let user = find_by_email(&state, &email)?;
    let Some(user) = user else {
        return Err(ApiError::unauthorized("Invalid email or password"));
    };
    if !password::verify_password(&password, &user.password_hash) {
        return Err(ApiError::unauthorized("Invalid email or password"));
    }

    let mut response = issue_tokens(&state, &user.id)?;
    response["user"] = user.user_public();
    Ok(Json(response))
}

pub async fn refresh(State(state): State<AppState>, body: Bytes) -> ApiResult<Json<Value>> {
    let body = parse_body(&body);
    let token = text_field(&body, "refresh_token")
        .ok_or_else(|| ApiError::bad_request("refresh_token is required"))?;

    let payload = jwt::verify(&token, &state.secret)
        .ok_or_else(|| ApiError::unauthorized("Invalid or expired refresh token"))?;
    let sub = payload
        .get("sub")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::unauthorized("Invalid or expired refresh token"))?
        .to_string();
    let sid = payload
        .get("sid")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::unauthorized("Invalid or expired refresh token"))?
        .to_string();

    // Single-use rotation: deleting the row is the atomic "claim" of this token.
    let deleted = state.db.with(|conn| {
        conn.execute(
            "DELETE FROM sessions WHERE id = ? AND user_id = ?",
            params![sid, sub],
        )
    })?;
    if deleted == 0 {
        return Err(ApiError::unauthorized("Session has been revoked"));
    }

    Ok(Json(issue_tokens(&state, &sub)?))
}

pub async fn logout(State(state): State<AppState>, body: Bytes) -> ApiResult<Json<Value>> {
    let body = parse_body(&body);
    if let Some(token) = text_field(&body, "refresh_token") {
        if let Some(payload) = jwt::verify(&token, &state.secret) {
            if let Some(sid) = payload.get("sid").and_then(Value::as_str) {
                let sid = sid.to_string();
                state.db.with(|conn| {
                    conn.execute("DELETE FROM sessions WHERE id = ?", [sid])?;
                    Ok(())
                })?;
            }
        }
    }
    Ok(Json(json!({ "success": true })))
}

pub async fn me(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let user = state
        .db
        .with(|conn| {
            conn.query_row("SELECT * FROM users WHERE id = ?", params![&user_id], UserRow::from_row)
                .optional()
        })?
        .ok_or_else(|| ApiError::not_found("Not found"))?;
    Ok(Json(json!({ "user": user.user_public() })))
}

#[cfg(test)]
mod tests {
    use super::email_valid;

    #[test]
    fn email_rules_match_the_reference() {
        assert!(email_valid("a@b.co"));
        assert!(email_valid("first.last@example.com"));
        assert!(!email_valid("nope"));
        assert!(!email_valid("a@b"));
        assert!(!email_valid("a b@c.de"));
        assert!(!email_valid("a@@b.co"));
    }
}
