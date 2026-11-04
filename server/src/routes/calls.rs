//! `/calls/*` — port of `worker/src/routes/calls.ts` (1:1 WebRTC signalling).

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::privacy::{apply_privacy, are_friends, get_privacy_settings};
use crate::util::{clamp_limit, new_id, now_iso};
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    limit: Option<String>,
}

struct CallRow {
    id: String,
    caller_id: String,
    receiver_id: String,
    call_type: String,
    status: String,
    duration: Option<i64>,
    created_at: String,
    ended_at: Option<String>,
}

impl CallRow {
    fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            caller_id: row.get("caller_id")?,
            receiver_id: row.get("receiver_id")?,
            call_type: row.get("call_type")?,
            status: row.get("status")?,
            duration: row.get("duration")?,
            created_at: row.get("created_at")?,
            ended_at: row.get("ended_at")?,
        })
    }

    fn is_participant(&self, user_id: &str) -> bool {
        self.caller_id == user_id || self.receiver_id == user_id
    }
}

fn get_call(state: &AppState, id: &str) -> ApiResult<Option<CallRow>> {
    Ok(state
        .db
        .with(|conn| {
            conn.query_row("SELECT * FROM calls WHERE id = ?", [id], CallRow::from_row)
                .optional()
        })?)
}

/// TURN credentials are minted by Cloudflare Realtime in the reference. The
/// endpoint keeps its contract (`{"iceServers": null}` when unconfigured) so the
/// client falls back to its public STUN/TURN list; wiring `CF_TURN_*` through
/// here is the next step (see PORTING.md).
pub async fn turn_credentials(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    state.current_user(&headers)?;
    Ok(Json(json!({ "iceServers": Value::Null })))
}

pub async fn create(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let receiver_id = crate::routes::text_field(&body, "receiver_id").unwrap_or_default();
    let call_type = crate::routes::text_field(&body, "call_type").unwrap_or_default();

    if receiver_id.is_empty() || call_type.is_empty() {
        return Err(ApiError::bad_request(
            "receiver_id and call_type are required",
        ));
    }
    if call_type != "audio" && call_type != "video" {
        return Err(ApiError::bad_request("call_type must be audio or video"));
    }
    if receiver_id == user_id {
        return Err(ApiError::bad_request("Cannot call yourself"));
    }

    let blocked: Option<i64> = state.db.with(|conn| {
        conn.query_row(
            "SELECT 1 FROM blocked_users
             WHERE (user_id = ? AND blocked_user_id = ?) OR (user_id = ? AND blocked_user_id = ?)",
            params![user_id, receiver_id, receiver_id, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    if blocked.is_some() {
        return Err(ApiError::forbidden("Cannot call this user"));
    }

    let id = new_id();
    let created_at = now_iso();
    let offer = body.get("offer_sdp").cloned();
    let offer_sql = offer
        .as_ref()
        .filter(|value| !value.is_null())
        .map(|value| value.to_string());

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO calls (id, caller_id, receiver_id, call_type, status, offer_sdp, created_at)
             VALUES (?, ?, ?, ?, 'ringing', ?, ?)",
            params![id, user_id, receiver_id, call_type, offer_sql, created_at],
        )?;
        Ok(())
    })?;

    // The callee's ringing screen shows the caller masked by the caller's own
    // privacy settings, exactly like every other profile projection.
    let (caller, settings, is_friend) = state.db.with(|conn| {
        let caller = conn
            .query_row(
                "SELECT full_name, avatar_url FROM users WHERE id = ?",
                [&user_id],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
            )
            .optional()?;
        let settings = get_privacy_settings(conn, &user_id)?;
        let is_friend = are_friends(conn, &user_id, &receiver_id)?;
        Ok((caller, settings, is_friend))
    })?;

    let mut payload = json!({
        "type": "call:incoming",
        "call": {
            "id": id,
            "caller_id": user_id,
            "receiver_id": receiver_id,
            "call_type": call_type,
            "status": "ringing",
            "created_at": created_at,
            "offer_sdp": offer,
        }
    });
    if let Some((full_name, avatar_url)) = caller {
        let mut profile = json!({ "id": user_id, "full_name": full_name });
        if let Some(avatar) = avatar_url {
            profile["avatar_url"] = json!(avatar);
        }
        apply_privacy(&mut profile, &receiver_id, &settings, is_friend);
        payload["call"]["caller_profile"] = profile;
    }
    state
        .hub
        .publish_to_user(&receiver_id, &payload.to_string());

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "call": {
                "id": id,
                "caller_id": user_id,
                "receiver_id": receiver_id,
                "call_type": call_type,
                "status": "ringing",
                "created_at": created_at,
            }
        })),
    ))
}

pub async fn signal(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    if !call.is_participant(&user_id) {
        return Err(ApiError::forbidden("Not authorized"));
    }

    let payload_body = crate::routes::parse_body(&body);
    let other_id = if call.caller_id == user_id {
        call.receiver_id.clone()
    } else {
        call.caller_id.clone()
    };

    let mut payload = json!({ "type": "call:signal", "callId": id, "from": user_id });
    for (key, value) in payload_body {
        payload[key] = value;
    }
    state.hub.publish_to_user(&other_id, &payload.to_string());
    Ok(Json(json!({ "success": true })))
}

pub async fn answer(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    if call.receiver_id != user_id {
        return Err(ApiError::forbidden("Only the callee can answer"));
    }
    if call.status != "ringing" {
        return Err(ApiError::conflict("Call is no longer ringing"));
    }

    let body = crate::routes::parse_body(&body);
    let answer = body.get("answer_sdp").cloned().unwrap_or(Value::Null);
    let encoded = answer.to_string();

    state.db.with(|conn| {
        conn.execute(
            "UPDATE calls SET status = 'answered', answer_sdp = ? WHERE id = ?",
            params![encoded, id],
        )?;
        Ok(())
    })?;

    state.hub.publish_to_user(
        &call.caller_id,
        &json!({ "type": "call:answered", "callId": id, "answer_sdp": answer }).to_string(),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn end(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    if !call.is_participant(&user_id) {
        return Err(ApiError::forbidden("Not authorized"));
    }
    // A stale duplicate "end" must not overwrite a finished call's outcome.
    if call.ended_at.is_some() {
        return Ok(Json(json!({ "success": true })));
    }

    let body = crate::routes::parse_body(&body);
    let status = crate::routes::text_field(&body, "status").unwrap_or_else(|| "ended".to_string());
    let duration = body
        .get("duration")
        .and_then(Value::as_i64)
        .map(Some)
        .unwrap_or(call.duration);

    state.db.with(|conn| {
        conn.execute(
            "UPDATE calls SET status = ?, duration = ?, ended_at = ? WHERE id = ?",
            params![status, duration, now_iso(), id],
        )?;
        Ok(())
    })?;

    let other_id = if call.caller_id == user_id {
        call.receiver_id
    } else {
        call.caller_id
    };
    state.hub.publish_to_user(
        &other_id,
        &json!({ "type": "call:ended", "callId": id, "status": status }).to_string(),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn history(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HistoryQuery>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let limit = clamp_limit(query.limit.as_deref(), 50, 200);

    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT cl.*, u.id AS other_id, u.full_name, u.avatar_url FROM calls cl
             JOIN users u ON u.id = (CASE WHEN cl.caller_id = ? THEN cl.receiver_id ELSE cl.caller_id END)
             WHERE cl.caller_id = ? OR cl.receiver_id = ?
             ORDER BY cl.created_at DESC LIMIT ?",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id, limit as i64], |row| {
            Ok((
                CallRow::from_row(row)?,
                row.get::<_, String>("other_id")?,
                row.get::<_, String>("full_name")?,
                row.get::<_, Option<String>>("avatar_url")?,
            ))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let mut history: Vec<Value> = Vec::with_capacity(rows.len());
    for (call, other_id, full_name, avatar_url) in rows {
        let (settings, is_friend) = state.db.with(|conn| {
            Ok((
                get_privacy_settings(conn, &other_id)?,
                are_friends(conn, &user_id, &other_id)?,
            ))
        })?;
        let mut profile = json!({ "id": other_id, "full_name": full_name });
        if let Some(avatar) = avatar_url {
            profile["avatar_url"] = json!(avatar);
        }
        apply_privacy(&mut profile, &user_id, &settings, is_friend);

        history.push(json!({
            "id": call.id,
            "caller_id": call.caller_id,
            "receiver_id": call.receiver_id,
            "call_type": call.call_type,
            "status": call.status,
            "duration": call.duration,
            "created_at": call.created_at,
            "ended_at": call.ended_at,
            "other_profile": profile,
        }));
    }

    Ok(Json(json!({ "history": history })))
}
