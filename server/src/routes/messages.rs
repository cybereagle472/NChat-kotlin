//! `/messages/*` — port of `worker/src/routes/messages.ts`.
//!
//! Fan-out goes to both participants' personal rooms (`user:<id>`), exactly like
//! the reference: one socket per user receives every conversation's events.

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, params_from_iter, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::models::{MessageRow, UserRow};
use crate::presence::get_presence_map;
use crate::privacy::{are_friends, apply_privacy_to_list, get_privacy_settings};
use crate::routes::media::ensure_attachable;
use crate::routes::{ensure_lengths, limits, parse_body, text_field};
use crate::util::{clamp_limit, new_id, now_iso, parse_iso_millis};
use crate::AppState;

const EDIT_WINDOW_MS: i64 = 15 * 60 * 1_000;

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    limit: Option<String>,
    before: Option<String>,
}

fn broadcast_to_participants(state: &AppState, a: &str, b: &str, payload: &Value) {
    let text = payload.to_string();
    state.hub.publish_to_user(a, &text);
    if b != a {
        state.hub.publish_to_user(b, &text);
    }
}

/// Direct conversations are only available to accepted friends and neither side may have
/// blocked the other. Keep this check server-side: UI gating is not an authorization boundary.
fn ensure_direct_access(state: &AppState, a: &str, b: &str) -> ApiResult<()> {
    if a == b {
        return Err(ApiError::bad_request("Cannot message yourself"));
    }
    let allowed = state.db.with(|conn| {
        let friends = are_friends(conn, a, b)?;
        let blocked: Option<i64> = conn
            .query_row(
                "SELECT 1 FROM blocked_users
                 WHERE (user_id = ? AND blocked_user_id = ?)
                    OR (user_id = ? AND blocked_user_id = ?) LIMIT 1",
                params![a, b, b, a],
                |row| row.get(0),
            )
            .optional()?;
        Ok(friends && blocked.is_none())
    })?;
    if !allowed {
        return Err(ApiError::forbidden("Direct messaging is unavailable for this user"));
    }
    Ok(())
}

/// `GET /messages/with/:other_id` — history, oldest-first.
pub async fn with(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(other_id): Path<String>,
    Query(query): Query<HistoryQuery>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let limit = clamp_limit(query.limit.as_deref(), 200, 200);

    let rows = state.db.with(|conn| {
        let mut sql = String::from(
            "SELECT * FROM messages
             WHERE ((sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?))",
        );
        if query.before.is_some() {
            sql.push_str(" AND created_at < ?");
        }
        sql.push_str(" ORDER BY created_at DESC LIMIT ?");

        let mut bound: Vec<Box<dyn rusqlite::ToSql>> = vec![
            Box::new(user_id.clone()),
            Box::new(other_id.clone()),
            Box::new(other_id.clone()),
            Box::new(user_id.clone()),
        ];
        if let Some(before) = &query.before {
            bound.push(Box::new(before.clone()));
        }
        bound.push(Box::new(limit as i64));

        let mut statement = conn.prepare(&sql)?;
        let refs: Vec<&dyn rusqlite::ToSql> = bound.iter().map(|value| value.as_ref() as &dyn rusqlite::ToSql).collect();
        let mapped = statement.query_map(params_from_iter(refs), MessageRow::from_row)?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    // If the other side disabled read receipts, never reveal that they read.
    let other_settings = state
        .db
        .with(|conn| get_privacy_settings(conn, &other_id))?;

    let mut messages: Vec<Value> = rows
        .iter()
        .filter(|row| !row.deleted_for_ids().contains(&user_id))
        .map(|row| {
            let mut value = row.to_json();
            if row.sender_id == user_id && !other_settings.read_receipts {
                value["is_read"] = json!(false);
            }
            value
        })
        .collect();
    messages.reverse();

    Ok(Json(json!({ "messages": messages })))
}

/// `GET /messages/chats` — friends with a conversation, last message, unread.
pub async fn chats(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let (friends, presence, settings_map) = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT u.* FROM friendships f
             JOIN users u ON u.id = (CASE WHEN f.user1_id = ? THEN f.user2_id ELSE f.user1_id END)
             WHERE f.user1_id = ? OR f.user2_id = ?",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id], UserRow::from_row)?;
        let mut rows = Vec::new();
        for row in mapped {
            rows.push(row?);
        }
        let ids: Vec<String> = rows.iter().map(|user| user.id.clone()).collect();
        let presence = get_presence_map(conn, &ids)?;
        let settings = crate::privacy::get_privacy_settings_map(conn, &ids)?;
        Ok((rows, presence, settings))
    })?;

    let mut profiles: Vec<Value> = friends
        .iter()
        .map(|user| user.brief(presence.get(&user.id).copied()))
        .collect();
    state
        .db
        .with(|conn| apply_privacy_to_list(conn, &user_id, &mut profiles))?;

    let mut chats: Vec<Value> = Vec::with_capacity(friends.len());
    let mut last_times: Vec<String> = Vec::with_capacity(friends.len());

    for (index, friend) in friends.iter().enumerate() {
        let (last, unread) = state.db.with(|conn| {
            let mut statement = conn.prepare(
                "SELECT * FROM messages
                 WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)
                 ORDER BY created_at DESC",
            )?;
            let rows = statement.query_map(
                params![user_id, friend.id, friend.id, user_id],
                MessageRow::from_row,
            )?;
            let mut last = None;
            let mut unread = 0i64;
            for row in rows {
                let message = row?;
                if message.deleted_for_ids().contains(&user_id) {
                    continue;
                }
                if message.sender_id == friend.id && !message.is_read { unread += 1; }
                if last.is_none() { last = Some(message); }
            }
            Ok((last, unread))
        })?;
        // A cleared conversation disappears from this device's chat list. A later incoming or
        // outgoing message naturally makes it visible again. Friend discovery remains separate.
        if last.is_none() { continue; }

        let mut last_json = last.as_ref().map(MessageRow::to_json);
        if let (Some(value), Some(row)) = (last_json.as_mut(), last.as_ref()) {
            if row.sender_id == user_id && row.is_read {
                let their_receipts = settings_map
                    .get(&friend.id)
                    .map(|settings| settings.read_receipts)
                    .unwrap_or(true);
                if !their_receipts {
                    value["is_read"] = json!(false);
                }
            }
        }

        last_times.push(
            last_json
                .as_ref()
                .and_then(|value| value.get("created_at"))
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
        );

        chats.push(json!({
            "user_id": friend.id,
            "status": "accepted",
            "profile": profiles[index],
            "lastMessage": last_json,
            "unreadCount": unread,
        }));
    }

    // Most recent conversation first.
    let mut order: Vec<usize> = (0..chats.len()).collect();
    order.sort_by(|a, b| last_times[*b].cmp(&last_times[*a]));
    let sorted: Vec<Value> = order.into_iter().map(|index| chats[index].clone()).collect();

    Ok(Json(json!({ "chats": sorted })))
}

/// `POST /messages` — send a 1:1 message (idempotent on `client_message_id`).
pub async fn send(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    state.count_send(&user_id)?;
    let body = parse_body(&body);
    ensure_lengths(
        &body,
        &[
            ("receiver_id", limits::SHORT),
            ("content", limits::MESSAGE),
            ("type", limits::SHORT),
            ("client_message_id", limits::SHORT),
            ("media_url", limits::URL),
            ("thumbnail_url", limits::PREVIEW),
            ("fileName", limits::SHORT),
            ("fileSize", limits::SHORT),
            ("duration", limits::SHORT),
            ("reply_to", limits::SHORT),
        ],
    )?;

    let receiver_id = text_field(&body, "receiver_id").unwrap_or_default();
    if receiver_id.is_empty() {
        return Err(ApiError::bad_request("receiver_id is required"));
    }
    ensure_direct_access(&state, &user_id, &receiver_id)?;
    let content = text_field(&body, "content").unwrap_or_default();
    let kind = text_field(&body, "type").unwrap_or_else(|| "text".to_string());
    let client_message_id = text_field(&body, "client_message_id");

    if let Some(client_id) = &client_message_id {
        let existing = state.db.with(|conn| {
            conn.query_row(
                "SELECT * FROM messages WHERE sender_id = ? AND client_message_id = ?",
                params![user_id, client_id],
                MessageRow::from_row,
            )
            .optional()
        })?;
        if let Some(row) = existing {
            return Ok((StatusCode::CREATED, Json(json!({ "message": row.to_json() }))));
        }
    }

    // Whoever is in a message can open the files it names, so naming a file needs the
    // right to open it. Forwarding passes: the forwarder can open what they were sent.
    let media_url = text_field(&body, "media_url");
    let thumbnail_url = text_field(&body, "thumbnail_url");
    ensure_attachable(
        &state,
        &user_id,
        &[media_url.as_deref(), thumbnail_url.as_deref()],
    )?;

    let row = MessageRow {
        id: new_id(),
        sender_id: user_id.clone(),
        receiver_id: receiver_id.clone(),
        content,
        kind,
        media_url,
        thumbnail_url,
        file_name: text_field(&body, "fileName"),
        file_size: text_field(&body, "fileSize"),
        duration: text_field(&body, "duration"),
        client_message_id,
        reply_to: text_field(&body, "reply_to"),
        is_read: false,
        is_edited: false,
        edited_at: None,
        is_deleted: false,
        deleted_for: None,
        reactions: None,
        created_at: now_iso(),
    };

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO messages (id, sender_id, receiver_id, content, type, media_url,
                                   thumbnail_url, file_name, file_size, duration,
                                   client_message_id, reply_to, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                row.id, row.sender_id, row.receiver_id, row.content, row.kind, row.media_url,
                row.thumbnail_url, row.file_name, row.file_size, row.duration,
                row.client_message_id, row.reply_to, row.created_at,
            ],
        )?;
        Ok(())
    })?;

    let payload = json!({ "type": "message:new", "message": row.to_json() });
    // Include the sender so their other devices stay in sync, like the reference.
    broadcast_to_participants(&state, &user_id, &receiver_id, &payload);

    Ok((StatusCode::CREATED, Json(json!({ "message": row.to_json() }))))
}

/// `PATCH /messages/:id` — author only, text only, 15-minute window.
pub async fn edit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    ensure_lengths(&body, &[("content", limits::MESSAGE)])?;
    let content = text_field(&body, "content").unwrap_or_default();

    let row = state.db.with(|conn| {
        conn.query_row(
            "SELECT * FROM messages WHERE id = ? AND sender_id = ?",
            params![id, user_id],
            MessageRow::from_row,
        )
        .optional()
    })?;
    let Some(mut row) = row else {
        return Err(ApiError::not_found("Message not found"));
    };
    ensure_direct_access(&state, &user_id, &row.receiver_id)?;
    if row.kind != "text" {
        return Err(ApiError::bad_request("Only text messages can be edited"));
    }
    let created = parse_iso_millis(&row.created_at).unwrap_or(0);
    if crate::util::now_millis() - created > EDIT_WINDOW_MS {
        return Err(ApiError::bad_request(
            "Messages can only be edited within 15 minutes",
        ));
    }

    let edited_at = now_iso();
    state.db.with(|conn| {
        conn.execute(
            "UPDATE messages SET content = ?, is_edited = 1, edited_at = ? WHERE id = ?",
            params![content, edited_at, id],
        )?;
        Ok(())
    })?;

    row.content = content;
    row.is_edited = true;
    row.edited_at = Some(edited_at);
    let receiver = row.receiver_id.clone();

    let payload = json!({ "type": "message:edit", "message": row.to_json() });
    broadcast_to_participants(&state, &user_id, &receiver, &payload);

    Ok(Json(json!({ "message": row.to_json() })))
}

/// `DELETE /messages/:id` — delete for everyone (sender only).
pub async fn delete_for_everyone(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let receiver: Option<String> = state.db.with(|conn| {
        conn.query_row(
            "SELECT receiver_id FROM messages WHERE id = ? AND sender_id = ?",
            params![id, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    let Some(receiver) = receiver else {
        return Err(ApiError::not_found("Message not found"));
    };
    ensure_direct_access(&state, &user_id, &receiver)?;

    state.db.with(|conn| {
        conn.execute(
            "UPDATE messages SET is_deleted = 1, content = '' WHERE id = ?",
            params![&id],
        )?;
        Ok(())
    })?;

    let payload = json!({
        "type": "message:delete",
        "messageId": id,
        "sender_id": user_id,
        "receiver_id": receiver,
    });
    broadcast_to_participants(&state, &user_id, &receiver, &payload);

    Ok(Json(json!({ "success": true })))
}

/// Hide an entire direct conversation from this account without deleting the other side's copy.
pub async fn clear_conversation(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(other_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if other_id.is_empty() || other_id == user_id {
        return Err(ApiError::bad_request("Invalid conversation"));
    }
    state.db.with(|conn| {
        let tx = conn.transaction()?;
        let mut statement = tx.prepare(
            "SELECT id, deleted_for FROM messages
             WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)",
        )?;
        let rows = statement.query_map(params![user_id, other_id, other_id, user_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?))
        })?;
        let mut updates = Vec::new();
        for row in rows {
            updates.push(row?);
        }
        drop(statement);
        for (message_id, raw) in updates {
            let mut deleted: Vec<String> = raw
                .as_deref()
                .and_then(|value| serde_json::from_str(value).ok())
                .unwrap_or_default();
            if !deleted.contains(&user_id) {
                deleted.push(user_id.clone());
                let encoded = serde_json::to_string(&deleted).unwrap_or_else(|_| "[]".to_string());
                tx.execute(
                    "UPDATE messages SET deleted_for = ? WHERE id = ?",
                    params![encoded, message_id],
                )?;
            }
        }
        tx.commit()
    })?;
    Ok(Json(json!({ "success": true })))
}

/// `POST /messages/:id/delete-for-me`.
pub async fn delete_for_me(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let current: Option<Option<String>> = state.db.with(|conn| {
        conn.query_row(
            "SELECT deleted_for FROM messages WHERE id = ? AND (sender_id = ? OR receiver_id = ?)",
            params![id, user_id, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    let Some(current) = current else {
        return Err(ApiError::not_found("Message not found"));
    };

    let mut list: Vec<String> = current
        .as_deref()
        .and_then(|raw| serde_json::from_str(raw).ok())
        .unwrap_or_default();
    if !list.contains(&user_id) {
        list.push(user_id);
    }
    let encoded = serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string());

    state.db.with(|conn| {
        conn.execute("UPDATE messages SET deleted_for = ? WHERE id = ?", params![encoded, id])?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

/// `POST /messages/:id/reaction` — toggle one emoji for the caller.
pub async fn reaction(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    ensure_lengths(&body, &[("emoji", limits::EMOJI)])?;
    let emoji = text_field(&body, "emoji").unwrap_or_default();

    let row: Option<(String, String, Option<String>)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT sender_id, receiver_id, reactions FROM messages
             WHERE id = ? AND (sender_id = ? OR receiver_id = ?)",
            params![&id, user_id, user_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
    })?;
    let Some((sender_id, receiver_id, reactions)) = row else {
        return Err(ApiError::not_found("Message not found"));
    };
    let other_id = if sender_id == user_id { &receiver_id } else { &sender_id };
    ensure_direct_access(&state, &user_id, other_id)?;

    let mut map: std::collections::BTreeMap<String, Vec<String>> = reactions
        .as_deref()
        .and_then(|raw| serde_json::from_str(raw).ok())
        .unwrap_or_default();

    let mut list = map.get(&emoji).cloned().unwrap_or_default();
    if list.contains(&user_id) {
        list.retain(|value| value != &user_id);
    } else {
        // The whole map is stored with the message and pushed on every change, and each
        // different reaction is one more entry in it.
        if !map.contains_key(&emoji) && map.len() >= limits::REACTIONS {
            return Err(ApiError::bad_request(
                "This message has too many different reactions",
            ));
        }
        list.push(user_id.clone());
    }
    if list.is_empty() {
        map.remove(&emoji);
    } else {
        map.insert(emoji, list);
    }

    let encoded = serde_json::to_string(&map).unwrap_or_else(|_| "{}".to_string());
    state.db.with(|conn| {
        conn.execute("UPDATE messages SET reactions = ? WHERE id = ?", params![encoded, id])?;
        Ok(())
    })?;

    let reactions_json = serde_json::to_value(&map).unwrap_or(Value::Null);
    let payload = json!({
        "type": "message:reaction",
        "messageId": id,
        "reactions": reactions_json,
        "sender_id": sender_id,
        "receiver_id": receiver_id,
    });
    broadcast_to_participants(&state, &sender_id, &receiver_id, &payload);

    Ok(Json(json!({ "reactions": reactions_json })))
}

/// `POST /messages/mark-read` — flips `is_read` and tells the sender, unless
/// the reader has switched read receipts off.
pub async fn mark_read(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let sender_id = text_field(&body, "sender_id").unwrap_or_default();
    if sender_id.is_empty() {
        return Err(ApiError::bad_request("sender_id is required"));
    }
    ensure_direct_access(&state, &user_id, &sender_id)?;

    let ids: Vec<String> = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT id FROM messages WHERE sender_id = ? AND receiver_id = ? AND is_read = 0",
        )?;
        let mapped = statement.query_map(params![sender_id, user_id], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    if !ids.is_empty() {
        state.db.with(|conn| {
            conn.execute(
                "UPDATE messages SET is_read = 1 WHERE sender_id = ? AND receiver_id = ?",
                params![sender_id, user_id],
            )?;
            Ok(())
        })?;

        let reader_settings = state
            .db
            .with(|conn| get_privacy_settings(conn, &user_id))?;
        if reader_settings.read_receipts {
            let payload = json!({
                "type": "message:read",
                "reader_id": user_id,
                "message_ids": ids,
            });
            state.hub.publish_to_user(&sender_id, &payload.to_string());
        }
    }

    Ok(Json(json!({ "success": true })))
}

/// `POST /messages/typing` — ephemeral, no database write.
pub async fn typing(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let receiver_id = text_field(&body, "receiver_id").unwrap_or_default();
    if receiver_id.is_empty() {
        return Err(ApiError::bad_request("receiver_id is required"));
    }
    ensure_direct_access(&state, &user_id, &receiver_id)?;
    let is_typing = crate::routes::bool_field(&body, "is_typing").unwrap_or(false);

    let payload = json!({
        "type": "typing",
        "sender_id": user_id,
        "is_typing": is_typing,
    });
    state
        .hub
        .publish_to_user(&receiver_id, &payload.to_string());
    Ok(Json(json!({ "success": true })))
}

/// `POST /messages/recording` — ephemeral "recording audio" state.
pub async fn recording(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);
    let receiver_id = text_field(&body, "receiver_id").unwrap_or_default();
    if receiver_id.is_empty() {
        return Err(ApiError::bad_request("receiver_id is required"));
    }
    ensure_direct_access(&state, &user_id, &receiver_id)?;
    let is_recording = crate::routes::bool_field(&body, "is_recording").unwrap_or(false);

    let payload = json!({
        "type": "recording",
        "sender_id": user_id,
        "is_recording": is_recording,
    });
    state
        .hub
        .publish_to_user(&receiver_id, &payload.to_string());
    Ok(Json(json!({ "success": true })))
}
