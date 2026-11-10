//! `/auth/*` — port of `worker/src/routes/auth.ts`.

use std::net::SocketAddr;

use axum::body::Bytes;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::error::{ApiError, ApiResult};
use crate::models::UserRow;
use crate::password;
use crate::routes::{ensure_lengths, limits, parse_body, text_field};
use crate::util::{iso_in_secs, new_id, now_iso};
use crate::{jwt, AppState};

const ACCESS_TTL_SECS: i64 = 15 * 60;
const REFRESH_TTL_SECS: i64 = 30 * 24 * 60 * 60;

fn too_many_attempts() -> ApiError {
    ApiError::too_many_requests("Too many attempts — try again in a few minutes")
}

/// The e-mail address as it goes into a rate-limit key.
///
/// The limiters keep their keys in memory for a whole window, and a sign-in request may
/// name an "address" as long as the body limit allows. Anything longer than a real
/// address is replaced by its digest: the same text still lands in the same bucket, and
/// a key is never more than a few hundred bytes.
fn account_key(email: &str) -> String {
    if email.len() <= 320 {
        email.to_string()
    } else {
        format!(
            "sha256:{}",
            URL_SAFE_NO_PAD.encode(Sha256::digest(email.as_bytes()))
        )
    }
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

/// Replaces a hash made with fewer rounds than the current setting. Called right after
/// the password has been verified, which is the only time the server holds it.
///
/// Best effort: the sign-in has already succeeded, so a failed write is logged and the
/// old hash stays until the next sign-in. The `AND password_hash = ?` makes the write a
/// compare-and-swap, so it can never undo a hash that changed in the meantime.
fn upgrade_password_hash(state: &AppState, user: &UserRow, password: &str) {
    if !password::needs_rehash(&user.password_hash, state.password_iterations) {
        return;
    }
    let upgraded = password::hash_password_with(password, state.password_iterations);
    let outcome = state.db.with(|conn| {
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ? AND password_hash = ?",
            params![upgraded, user.id, user.password_hash],
        )
    });
    if let Err(error) = outcome {
        tracing::warn!(%error, "could not upgrade a stored password hash");
    }
}

pub async fn signup(
    State(state): State<AppState>,
    peer: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let body = parse_body(&body);
    ensure_lengths(
        &body,
        &[
            ("email", limits::EMAIL),
            ("password", limits::PASSWORD),
            ("full_name", limits::NAME),
        ],
    )?;
    let email = text_field(&body, "email")
        .unwrap_or_default()
        .trim()
        .to_lowercase();
    let address = state.client_address(peer.map(|ConnectInfo(address)| address), &headers);

    if !state.auth_limiter.allow(&format!("{address}|{email}")) {
        return Err(too_many_attempts());
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
    // Every sign-up that gets this far costs a password hash and, if the address is new,
    // an account. The limit above has the e-mail address in its key, so it never notices
    // one client that uses a new address every time; this one counts the client alone.
    if !state.address_limiter.allow(&address) {
        return Err(too_many_attempts());
    }
    // Hash before checking for duplicates: otherwise the fast duplicate path becomes a timing
    // oracle even though the response text and status are intentionally indistinguishable.
    let password_hash = password::hash_password_with(&password, state.password_iterations);
    if find_by_email(&state, &email)?.is_some() {
        return Err(ApiError::bad_request("Unable to create an account with these details"));
    }

    let id = new_id();
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
    peer: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let body = parse_body(&body);
    let email = text_field(&body, "email")
        .unwrap_or_default()
        .trim()
        .to_lowercase();
    let password = text_field(&body, "password").unwrap_or_default();
    let address = state.client_address(peer.map(|ConnectInfo(address)| address), &headers);
    let account = account_key(&email);

    // Three budgets, each for a different way of guessing.
    //
    // Per (client, account): the everyday one. A shared address — an office, a mobile
    // carrier — does not lock out everyone behind it because one person keeps mistyping.
    if !state.auth_limiter.allow(&format!("{address}|{account}")) {
        return Err(too_many_attempts());
    }
    // Per account, from anywhere: many clients guessing at one account. The price is that
    // enough wrong guesses can keep the owner from signing in on a new device for the rest
    // of the window; sessions that already exist are not affected.
    if !email.is_empty() && !state.account_limiter.allow(&format!("account|{account}")) {
        return Err(too_many_attempts());
    }
    // Per client, across accounts: one client working through a list of accounts. Neither
    // limit above ever sees that, because every attempt names somebody else.
    if !state.address_limiter.allow(&address) {
        return Err(too_many_attempts());
    }

    let user = find_by_email(&state, &email)?;
    let Some(user) = user else {
        // The answer is the same as for a wrong password, and so is the work: without
        // this, "no such account" came back in a millisecond and "wrong password" after
        // a hash, and the clock said which addresses have accounts.
        let _ = password::verify_password(&password, state.decoy_password_hash());
        return Err(ApiError::unauthorized("Invalid email or password"));
    };
    if !password::verify_password(&password, &user.password_hash) {
        return Err(ApiError::unauthorized("Invalid email or password"));
    }
    upgrade_password_hash(&state, &user, &password);

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
