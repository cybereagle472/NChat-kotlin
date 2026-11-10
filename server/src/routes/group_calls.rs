//! `/group-calls/*` — port of `worker/src/routes/groupCalls.ts`.
//!
//! Mesh group calling: any member can join or leave while the call is running,
//! and glare is avoided by a client-side rule (already-joined peers offer, the
//! newcomer answers). This module only tracks states and relays signals.

use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::util::{new_id, now_iso};
use crate::AppState;

struct GroupCallRow {
    id: String,
    group_id: String,
    caller_id: String,
    call_type: String,
    status: String,
    created_at: String,
}

impl GroupCallRow {
    fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            group_id: row.get("group_id")?,
            caller_id: row.get("caller_id")?,
            call_type: row.get("call_type")?,
            status: row.get("status")?,
            created_at: row.get("created_at")?,
        })
    }

    fn to_wire(&self) -> Value {
        json!({
            "id": self.id,
            "group_id": self.group_id,
            "caller_id": self.caller_id,
            "call_type": self.call_type,
            "status": self.status,
            "created_at": self.created_at,
        })
    }
}

fn get_call(state: &AppState, id: &str) -> ApiResult<Option<GroupCallRow>> {
    Ok(state.db.with(|conn| {
        conn.query_row(
            "SELECT * FROM group_calls WHERE id = ?",
            [id],
            GroupCallRow::from_row,
        )
        .optional()
    })?)
}

fn is_member(state: &AppState, group_id: &str, user_id: &str) -> ApiResult<bool> {
    let found: Option<i64> = state.db.with(|conn| {
        conn.query_row(
            "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
            params![group_id, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    Ok(found.is_some())
}

fn group_member_ids(state: &AppState, group_id: &str) -> ApiResult<Vec<String>> {
    Ok(state.db.with(|conn| {
        let mut statement = conn.prepare("SELECT user_id FROM group_members WHERE group_id = ?")?;
        let mapped = statement.query_map([group_id], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?)
}

/// `true` when the user was rung for this call or has joined it.
fn is_participant(state: &AppState, call_id: &str, user_id: &str) -> ApiResult<bool> {
    let found: Option<i64> = state.db.with(|conn| {
        conn.query_row(
            "SELECT 1 FROM group_call_participants WHERE call_id = ? AND user_id = ?",
            params![call_id, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    Ok(found.is_some())
}

/// Someone who belongs to this call: a current member of its group, or a user
/// with a participant row. The second half matters for a member who is removed
/// from the group while they are on the call — they must still be able to hang up.
fn belongs_to_call(state: &AppState, call: &GroupCallRow, user_id: &str) -> ApiResult<bool> {
    Ok(is_member(state, &call.group_id, user_id)? || is_participant(state, &call.id, user_id)?)
}

/// The envelope of a relayed `group-call:signal` event; a signal body cannot
/// override these (see `calls::signal` for why). `to` is routing, not payload.
const SIGNAL_RESERVED_KEYS: [&str; 4] = ["type", "call_id", "from", "to"];

/// `joinedParticipantsOf()` — everyone already in the call, minus `excluding`.
fn joined_participants(
    state: &AppState,
    call_id: &str,
    excluding: Option<&str>,
) -> ApiResult<Vec<Value>> {
    let exclude = excluding.unwrap_or("");
    Ok(state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT gcp.user_id, u.full_name, u.avatar_url
             FROM group_call_participants gcp
             JOIN users u ON u.id = gcp.user_id
             WHERE gcp.call_id = ? AND gcp.status = 'joined' AND gcp.user_id != ?",
        )?;
        let mapped = statement.query_map(params![call_id, exclude], |row| {
            let mut value = json!({
                "user_id": row.get::<_, String>("user_id")?,
                "full_name": row.get::<_, String>("full_name")?,
            });
            if let Some(avatar) = row.get::<_, Option<String>>("avatar_url")? {
                value["avatar_url"] = json!(avatar);
            }
            Ok(value)
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?)
}

/// Mark a participant joined and tell everyone who was already in.
fn join_call(state: &AppState, call: &GroupCallRow, user_id: &str) -> ApiResult<Value> {
    let now = now_iso();
    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO group_call_participants (call_id, user_id, status, joined_at)
             VALUES (?, ?, 'joined', ?)
             ON CONFLICT(call_id, user_id) DO UPDATE SET
               status = 'joined', joined_at = excluded.joined_at, left_at = NULL",
            params![call.id, user_id, now],
        )?;
        Ok(())
    })?;

    let already_joined = joined_participants(state, &call.id, Some(user_id))?;
    for participant in &already_joined {
        if let Some(target) = participant.get("user_id").and_then(Value::as_str) {
            state.hub.publish_to_user(
                target,
                &json!({
                    "type": "group-call:participant-joined",
                    "call_id": call.id,
                    "group_id": call.group_id,
                    "user_id": user_id,
                })
                .to_string(),
            );
        }
    }

    Ok(json!({
        "call": call.to_wire(),
        "participants": already_joined,
    }))
}

/// `POST /group-calls` — start a call, or join the group's active one.
pub async fn start(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let group_id = crate::routes::text_field(&body, "group_id").unwrap_or_default();
    let call_type = crate::routes::text_field(&body, "call_type").unwrap_or_default();

    if group_id.is_empty() || call_type.is_empty() {
        return Err(ApiError::bad_request("group_id and call_type are required"));
    }
    if call_type != "audio" && call_type != "video" {
        return Err(ApiError::bad_request("call_type must be audio or video"));
    }
    if !is_member(&state, &group_id, &user_id)? {
        return Err(ApiError::forbidden("Not a member of this group"));
    }

    let existing = state.db.with(|conn| {
        conn.query_row(
            "SELECT * FROM group_calls WHERE group_id = ? AND status = 'active'
             ORDER BY created_at DESC LIMIT 1",
            [&group_id],
            GroupCallRow::from_row,
        )
        .optional()
    })?;

    if let Some(call) = existing {
        let result = join_call(&state, &call, &user_id)?;
        return Ok((StatusCode::OK, Json(result)));
    }

    let id = new_id();
    let created_at = now_iso();
    let members = group_member_ids(&state, &group_id)?;

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO group_calls (id, group_id, caller_id, call_type, status, created_at)
             VALUES (?, ?, ?, ?, 'active', ?)",
            params![id, group_id, user_id, call_type, created_at],
        )?;
        conn.execute(
            "INSERT INTO group_call_participants (call_id, user_id, status, joined_at)
             VALUES (?, ?, 'joined', ?)",
            params![id, user_id, created_at],
        )?;
        for member in &members {
            if member != &user_id {
                conn.execute(
                    "INSERT INTO group_call_participants (call_id, user_id, status)
                     VALUES (?, ?, 'ringing')",
                    params![id, member],
                )?;
            }
        }
        Ok(())
    })?;

    let caller: Option<(String, Option<String>)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT full_name, avatar_url FROM users WHERE id = ?",
            [&user_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
        )
        .optional()
    })?;
    let mut caller_profile = json!({ "id": user_id });
    if let Some((full_name, avatar_url)) = caller {
        caller_profile["full_name"] = json!(full_name);
        if let Some(avatar) = avatar_url {
            caller_profile["avatar_url"] = json!(avatar);
        }
    }

    let group: Option<(String, Option<String>)> = state.db.with(|conn| {
        conn.query_row("SELECT name, avatar_url FROM groups WHERE id = ?", [&group_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?))
        })
        .optional()
    })?;

    for member in members.iter().filter(|member| *member != &user_id) {
        let mut incoming = json!({
            "type": "group-call:incoming",
            "call": {
                "id": id,
                "group_id": group_id,
                "call_type": call_type,
                "caller_id": user_id,
                "caller_profile": caller_profile,
                "created_at": created_at,
            }
        });
        if let Some((name, avatar)) = &group {
            incoming["call"]["group_name"] = json!(name);
            if let Some(avatar) = avatar {
                incoming["call"]["group_avatar_url"] = json!(avatar);
            }
        }
        state
            .hub
            .publish_to_user(member.as_str(), &incoming.to_string());
    }

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "call": {
                "id": id,
                "group_id": group_id,
                "caller_id": user_id,
                "call_type": call_type,
                "status": "active",
                "created_at": created_at,
            },
            "participants": [],
        })),
    ))
}

pub async fn join(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    if call.status != "active" {
        return Err(ApiError::conflict("This call has ended"));
    }
    if !is_member(&state, &call.group_id, &user_id)? {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    Ok(Json(join_call(&state, &call, &user_id)?))
}

/// `POST /group-calls/:id/signal` — relay an SDP offer/answer or ICE candidate
/// to one other participant (`to`).
///
/// Three things the reference does not check: the body cannot replace the
/// event envelope, the recipient has to belong to the call (otherwise any
/// member could push events to any user id), and an ended call relays nothing.
pub async fn signal(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let to = crate::routes::text_field(&body, "to").unwrap_or_default();
    if to.is_empty() {
        return Err(ApiError::bad_request("to is required"));
    }

    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    if !is_member(&state, &call.group_id, &user_id)? {
        return Err(ApiError::forbidden("Not authorized"));
    }
    // Late candidates after everyone has left are normal; they just go nowhere.
    if call.status != "active" {
        return Ok(Json(json!({ "success": true })));
    }
    if !belongs_to_call(&state, &call, &to)? {
        return Err(ApiError::forbidden("Not authorized"));
    }

    let mut payload = json!({ "type": "group-call:signal", "call_id": id, "from": user_id });
    for (key, value) in body {
        if SIGNAL_RESERVED_KEYS.contains(&key.as_str()) {
            continue;
        }
        payload[key] = value;
    }
    state.hub.publish_to_user(&to, &payload.to_string());
    Ok(Json(json!({ "success": true })))
}

pub async fn decline(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    // The reference lets any signed-in caller "decline" any call, which sends a
    // `participant-declined` event to the person who started it.
    if !belongs_to_call(&state, &call, &user_id)? {
        return Err(ApiError::forbidden("Not authorized"));
    }

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_call_participants SET status = 'declined'
             WHERE call_id = ? AND user_id = ?",
            params![id, user_id],
        )?;
        Ok(())
    })?;

    state.hub.publish_to_user(
        &call.caller_id,
        &json!({
            "type": "group-call:participant-declined",
            "call_id": id,
            "group_id": call.group_id,
            "user_id": user_id,
        })
        .to_string(),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn leave(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let call = get_call(&state, &id)?.ok_or_else(|| ApiError::not_found("Call not found"))?;
    // Without this, anyone could announce `participant-left` to everybody on a
    // call they were never part of.
    if !belongs_to_call(&state, &call, &user_id)? {
        return Err(ApiError::forbidden("Not authorized"));
    }
    let now = now_iso();

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_call_participants SET status = 'left', left_at = ?
             WHERE call_id = ? AND user_id = ?",
            params![now, id, user_id],
        )?;
        Ok(())
    })?;

    let still_joined = joined_participants(&state, &id, Some(&user_id))?;
    for participant in &still_joined {
        if let Some(target) = participant.get("user_id").and_then(Value::as_str) {
            state.hub.publish_to_user(
                target,
                &json!({
                    "type": "group-call:participant-left",
                    "call_id": id,
                    "group_id": call.group_id,
                    "user_id": user_id,
                })
                .to_string(),
            );
        }
    }

    if still_joined.is_empty() && call.status == "active" {
        state.db.with(|conn| {
            conn.execute(
                "UPDATE group_calls SET status = 'ended', ended_at = ? WHERE id = ?",
                params![now, id],
            )?;
            Ok(())
        })?;

        let ringing = state.db.with(|conn| {
            let mut statement = conn.prepare(
                "SELECT user_id FROM group_call_participants WHERE call_id = ? AND status = 'ringing'",
            )?;
            let mapped = statement.query_map([&id], |row| row.get::<_, String>(0))?;
            let mut out = Vec::new();
            for row in mapped {
                out.push(row?);
            }
            Ok(out)
        })?;
        for member in ringing {
            state.hub.publish_to_user(
                &member,
                &json!({ "type": "group-call:ended", "call_id": id, "group_id": call.group_id })
                    .to_string(),
            );
        }
    }

    Ok(Json(json!({ "success": true })))
}
