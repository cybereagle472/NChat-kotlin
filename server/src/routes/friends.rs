//! `/friends/*` — port of `worker/src/routes/friends.ts`.

use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::HeaderMap;
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::models::UserRow;
use crate::presence::get_presence_map;
use crate::privacy::{apply_privacy, apply_privacy_to_list, get_privacy_settings};
use crate::routes::{parse_body, text_field};
use crate::util::{new_id, now_iso};
use crate::AppState;

fn blocked_either_way(state: &AppState, a: &str, b: &str) -> ApiResult<bool> {
    let found: Option<i64> = state.db.with(|conn| {
        conn.query_row(
            "SELECT 1 FROM blocked_users
             WHERE (user_id = ? AND blocked_user_id = ?) OR (user_id = ? AND blocked_user_id = ?)",
            params![a, b, b, a],
            |row| row.get(0),
        )
        .optional()
    })?;
    Ok(found.is_some())
}

pub async fn send_request(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(axum::http::StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let receiver_id = text_field(&body, "receiver_id").unwrap_or_default();

    if receiver_id.is_empty() || receiver_id == user_id {
        return Err(ApiError::bad_request("Invalid receiver_id"));
    }
    if blocked_either_way(&state, &user_id, &receiver_id)? {
        return Err(ApiError::forbidden("Cannot send a request to this user"));
    }

    let existing: Option<(String, String)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT id, status FROM friend_requests
             WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)",
            params![user_id, receiver_id, receiver_id, user_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
    })?;

    if let Some((_, status)) = &existing {
        if status == "pending" {
            return Err(ApiError::conflict("Request already pending"));
        }
        if status == "accepted" {
            return Err(ApiError::conflict("Already friends"));
        }
    }

    let id = new_id();
    let now = now_iso();
    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO friend_requests (id, sender_id, receiver_id, status, created_at, updated_at)
             VALUES (?, ?, ?, 'pending', ?, ?)
             ON CONFLICT(sender_id, receiver_id)
             DO UPDATE SET status = 'pending', updated_at = excluded.updated_at",
            params![id, user_id, receiver_id, now, now],
        )?;
        Ok(())
    })?;

    // Tell the receiver live, with the sender's profile already privacy-masked.
    let (sender, last_active, sender_settings) = state.db.with(|conn| {
        let sender = conn
            .query_row("SELECT * FROM users WHERE id = ?", params![&user_id], UserRow::from_row)
            .optional()?;
        let presence = crate::presence::get_presence(conn, &user_id)?;
        let settings = get_privacy_settings(conn, &user_id)?;
        Ok((sender, presence, settings))
    })?;

    let mut event = json!({
        "type": "friend:request",
        "request": {
            "id": id,
            "sender_id": user_id,
            "receiver_id": receiver_id,
            "status": "pending",
        }
    });
    if let Some(sender) = sender {
        let mut profile = sender.brief(last_active);
        apply_privacy(&mut profile, &receiver_id, &sender_settings, false);
        event["request"]["sender_profile"] = profile;
    }
    state.hub.publish_to_user(&receiver_id, &event.to_string());

    Ok((axum::http::StatusCode::CREATED, Json(json!({ "success": true, "id": id }))))
}

pub async fn respond(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let accept = crate::routes::bool_field(&body, "accept").unwrap_or(false);

    let request: Option<(String, String, String)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT id, sender_id, status FROM friend_requests WHERE id = ? AND receiver_id = ?",
            params![request_id, user_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
    })?;

    let Some((_id, sender_id, status)) = request else {
        return Err(ApiError::not_found("Request not found"));
    };
    if status != "pending" {
        return Err(ApiError::conflict("Request already handled"));
    }

    if accept {
        let now = now_iso();
        let friendship_id = new_id();
        state.db.with(|conn| {
            conn.execute(
                "UPDATE friend_requests SET status = 'accepted', updated_at = ? WHERE id = ?",
                params![now, request_id],
            )?;
            conn.execute(
                "INSERT OR IGNORE INTO friendships (id, user1_id, user2_id, created_at)
                 VALUES (?, ?, ?, ?)",
                params![friendship_id, sender_id, user_id, now],
            )?;
            Ok(())
        })?;

        let (accepter, last_active, settings) = state.db.with(|conn| {
            let accepter = conn
                .query_row("SELECT * FROM users WHERE id = ?", params![&user_id], UserRow::from_row)
                .optional()?;
            let presence = crate::presence::get_presence(conn, &user_id)?;
            let settings = get_privacy_settings(conn, &user_id)?;
            Ok((accepter, presence, settings))
        })?;

        let friend = match accepter {
            Some(user) => {
                let mut profile = user.brief(last_active);
                apply_privacy(&mut profile, &sender_id, &settings, true);
                profile
            }
            None => json!({ "id": user_id }),
        };
        state.hub.publish_to_user(
            &sender_id,
            &json!({ "type": "friend:accepted", "friend": friend }).to_string(),
        );
    } else {
        state.db.with(|conn| {
            conn.execute(
                "UPDATE friend_requests SET status = 'rejected', updated_at = ? WHERE id = ?",
                params![now_iso(), request_id],
            )?;
            Ok(())
        })?;
        state.hub.publish_to_user(
            &sender_id,
            &json!({ "type": "friend:declined", "userId": user_id }).to_string(),
        );
    }

    Ok(Json(json!({ "success": true })))
}

pub async fn cancel_request(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM friend_requests WHERE id = ? AND sender_id = ?",
            params![request_id, user_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn requests(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT fr.id AS request_id, fr.sender_id, fr.receiver_id, fr.status,
                    fr.created_at AS request_created_at, fr.updated_at AS request_updated_at,
                    u.*
             FROM friend_requests fr JOIN users u ON u.id = fr.sender_id
             WHERE fr.receiver_id = ? AND fr.status = 'pending'
             ORDER BY fr.created_at DESC",
        )?;
        let mapped = statement.query_map(params![user_id], |row| {
            Ok((
                row.get::<_, String>("request_id")?,
                row.get::<_, String>("sender_id")?,
                row.get::<_, String>("receiver_id")?,
                row.get::<_, String>("status")?,
                row.get::<_, String>("request_created_at")?,
                row.get::<_, String>("request_updated_at")?,
                UserRow::from_row(row)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let sender_ids: Vec<String> = rows.iter().map(|row| row.1.clone()).collect();
    let presence = state.db.with(|conn| get_presence_map(conn, &sender_ids))?;
    let mut profiles: Vec<Value> = rows
        .iter()
        .map(|row| row.6.brief(presence.get(&row.6.id).copied()))
        .collect();
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;

    let requests: Vec<Value> = rows
        .iter()
        .enumerate()
        .map(|(index, row)| {
            json!({
                "id": row.0,
                "sender_id": row.1,
                "receiver_id": row.2,
                "status": row.3,
                "created_at": row.4,
                "updated_at": row.5,
                "sender_profile": profiles[index],
            })
        })
        .collect();

    Ok(Json(json!({ "requests": requests })))
}

pub async fn sent(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT id, receiver_id, created_at, updated_at FROM friend_requests
             WHERE sender_id = ? AND status = 'pending'",
        )?;
        let mapped = statement.query_map(params![user_id], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let receiver_ids: Vec<Value> = rows.iter().map(|row| json!(row.1)).collect();
    let requests: Vec<Value> = rows
        .iter()
        .map(|row| {
            json!({
                "id": row.0,
                "receiver_id": row.1,
                "created_at": row.2,
                "updated_at": row.3,
            })
        })
        .collect();

    Ok(Json(json!({ "receiverIds": receiver_ids, "requests": requests })))
}

pub async fn list(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT u.* FROM friendships f
             JOIN users u ON u.id = (CASE WHEN f.user1_id = ? THEN f.user2_id ELSE f.user1_id END)
             WHERE f.user1_id = ? OR f.user2_id = ?
             ORDER BY u.full_name",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id], UserRow::from_row)?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let ids: Vec<String> = rows.iter().map(|user| user.id.clone()).collect();
    let presence = state.db.with(|conn| get_presence_map(conn, &ids))?;
    let mut profiles: Vec<Value> = rows
        .iter()
        .map(|user| user.brief(presence.get(&user.id).copied()))
        .collect();
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;

    let friends: Vec<Value> = rows
        .iter()
        .enumerate()
        .map(|(index, user)| json!({ "id": user.id, "profile": profiles[index] }))
        .collect();

    Ok(Json(json!({ "friends": friends })))
}

pub async fn remove(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(friend_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM friendships
             WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)",
            params![user_id, friend_id, friend_id, user_id],
        )?;
        conn.execute(
            "DELETE FROM friend_requests
             WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)",
            params![user_id, friend_id, friend_id, user_id],
        )?;
        Ok(())
    })?;

    state.hub.publish_to_user(
        &friend_id,
        &json!({ "type": "friend:removed", "userId": user_id }).to_string(),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn block(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let target = text_field(&body, "user_id").ok_or_else(|| ApiError::bad_request("user_id is required"))?;

    state.db.with(|conn| {
        conn.execute(
            "INSERT OR IGNORE INTO blocked_users (id, user_id, blocked_user_id, blocked_at)
             VALUES (?, ?, ?, ?)",
            params![new_id(), user_id, target, now_iso()],
        )?;
        conn.execute(
            "DELETE FROM friendships
             WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)",
            params![user_id, target, target, user_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn unblock(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let target = text_field(&body, "user_id").ok_or_else(|| ApiError::bad_request("user_id is required"))?;
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM blocked_users WHERE user_id = ? AND blocked_user_id = ?",
            params![user_id, target],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn blocked(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let rows = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT u.* FROM blocked_users b JOIN users u ON u.id = b.blocked_user_id
             WHERE b.user_id = ?",
        )?;
        let mapped = statement.query_map(params![user_id], UserRow::from_row)?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let mut profiles: Vec<Value> = rows.iter().map(|user| user.brief(None)).collect();
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;
    Ok(Json(json!({ "blocked": profiles })))
}
