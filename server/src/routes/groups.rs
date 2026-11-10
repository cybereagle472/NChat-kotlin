//! `/groups/*` — port of `worker/src/routes/groups.ts`.
//!
//! Group events fan out to every member's personal room (there is no
//! per-group room), exactly like the reference.

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::util::{clamp_limit, new_id, now_iso, parse_iso_millis};
use crate::AppState;

const DELETE_FOR_EVERYONE_WINDOW_MS: i64 = 60 * 60 * 1_000;

#[derive(Debug, Deserialize)]
pub struct MessagesQuery {
    before: Option<String>,
    limit: Option<String>,
}

struct GroupRow {
    id: String,
    name: String,
    description: Option<String>,
    avatar_url: Option<String>,
    created_by: String,
    created_at: String,
    updated_at: Option<String>,
}

impl GroupRow {
    fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            name: row.get("name")?,
            description: row.get("description")?,
            avatar_url: row.get("avatar_url")?,
            created_by: row.get("created_by")?,
            created_at: row.get("created_at")?,
            updated_at: row.get("updated_at")?,
        })
    }

    fn to_json(&self) -> Value {
        let mut value = json!({
            "id": self.id,
            "name": self.name,
            "created_by": self.created_by,
            "created_at": self.created_at,
        });
        let object = value.as_object_mut().expect("object");
        if let Some(description) = &self.description {
            object.insert("description".into(), json!(description));
        }
        if let Some(avatar) = &self.avatar_url {
            object.insert("avatar_url".into(), json!(avatar));
        }
        if let Some(updated) = &self.updated_at {
            object.insert("updated_at".into(), json!(updated));
        }
        value
    }
}

/// A `group_messages` row plus its JSON projection (`groupMessageJson`).
struct GroupMessageRow {
    id: String,
    group_id: String,
    sender_id: String,
    content: String,
    kind: String,
    media_url: Option<String>,
    thumbnail_url: Option<String>,
    duration: Option<String>,
    client_message_id: Option<String>,
    reply_to: Option<String>,
    is_edited: bool,
    edited_at: Option<String>,
    is_deleted: bool,
    deleted_for: Option<String>,
    reactions: Option<String>,
    created_at: String,
}

impl GroupMessageRow {
    fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            group_id: row.get("group_id")?,
            sender_id: row.get("sender_id")?,
            content: row.get("content")?,
            kind: row.get("type")?,
            media_url: row.get("media_url")?,
            thumbnail_url: row.get("thumbnail_url")?,
            duration: row.get("duration")?,
            client_message_id: row.get("client_message_id")?,
            reply_to: row.get("reply_to")?,
            is_edited: row.get::<_, i64>("is_edited")? != 0,
            edited_at: row.get("edited_at")?,
            is_deleted: row.get::<_, i64>("is_deleted")? != 0,
            deleted_for: row.get("deleted_for")?,
            reactions: row.get("reactions")?,
            created_at: row.get("created_at")?,
        })
    }

    fn to_json(&self) -> Value {
        let mut value = json!({
            "id": self.id,
            "group_id": self.group_id,
            "sender_id": self.sender_id,
            "content": self.content,
            "type": self.kind,
            "is_edited": self.is_edited,
            "is_deleted": self.is_deleted,
            "created_at": self.created_at,
        });
        let object = value.as_object_mut().expect("object");
        if let Some(url) = &self.media_url {
            object.insert("media_url".into(), json!(url));
        }
        if let Some(url) = &self.thumbnail_url {
            object.insert("thumbnail_url".into(), json!(url));
        }
        if let Some(duration) = &self.duration {
            object.insert("duration".into(), json!(duration));
        }
        if let Some(client_id) = &self.client_message_id {
            object.insert("client_message_id".into(), json!(client_id));
        }
        if let Some(reply) = &self.reply_to {
            object.insert("reply_to".into(), json!(reply));
        }
        if let Some(edited) = &self.edited_at {
            object.insert("edited_at".into(), json!(edited));
        }
        if let Some(raw) = &self.deleted_for {
            object.insert(
                "deleted_for".into(),
                serde_json::from_str(raw).unwrap_or(Value::Null),
            );
        }
        if let Some(raw) = &self.reactions {
            object.insert(
                "reactions".into(),
                serde_json::from_str(raw).unwrap_or(Value::Null),
            );
        }
        value
    }

    #[allow(dead_code)] // the client filters `deleted_for`, the API returns rows as-is
    fn deleted_for_ids(&self) -> Vec<String> {
        self.deleted_for
            .as_deref()
            .and_then(|raw| serde_json::from_str::<Vec<String>>(raw).ok())
            .unwrap_or_default()
    }
}

/// `SELECT * FROM group_members WHERE group_id = ? AND user_id = ?`
fn membership(state: &AppState, group_id: &str, user_id: &str) -> ApiResult<Option<(String, bool)>> {
    let row = state.db.with(|conn| {
        conn.query_row(
            "SELECT role, is_muted FROM group_members WHERE group_id = ? AND user_id = ?",
            params![group_id, user_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)? != 0)),
        )
        .optional()
    })?;
    Ok(row)
}

fn member_ids(state: &AppState, group_id: &str) -> ApiResult<Vec<String>> {
    let ids = state.db.with(|conn| {
        let mut statement = conn.prepare("SELECT user_id FROM group_members WHERE group_id = ?")?;
        let mapped = statement.query_map([group_id], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;
    Ok(ids)
}

fn broadcast_to_members(state: &AppState, ids: &[String], payload: &Value) {
    let text = payload.to_string();
    for id in ids {
        state.hub.publish_to_user(id, &text);
    }
}

/// `groupWithMembers()` — group row plus every member with a privacy-masked
/// `last_read_at` and their profile.
///
/// The reference also put each member's e-mail address into `profile`. Group
/// payloads go to every member (and to people at the moment they are added), so
/// that handed a login identifier to anyone who shares a group with you. It is
/// left out here; both clients only ever used it as a last-resort display name.
fn group_with_members(state: &AppState, group_id: &str) -> ApiResult<Option<Value>> {
    let group = state.db.with(|conn| {
        conn.query_row("SELECT * FROM groups WHERE id = ?", [group_id], GroupRow::from_row)
            .optional()
    })?;
    let Some(group) = group else {
        return Ok(None);
    };

    let members = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT gm.group_id, gm.user_id, gm.role, gm.is_muted, gm.joined_at,
                    grs.last_read_at AS last_read_at,
                    COALESCE(ps.read_receipts, 1) AS read_receipts,
                    u.full_name, u.username, u.avatar_url
             FROM group_members gm
             JOIN users u ON u.id = gm.user_id
             LEFT JOIN group_read_state grs
                    ON grs.group_id = gm.group_id AND grs.user_id = gm.user_id
             LEFT JOIN privacy_settings ps ON ps.user_id = gm.user_id
             WHERE gm.group_id = ?",
        )?;
        let mapped = statement.query_map([group_id], |row| {
            let read_receipts = row.get::<_, i64>("read_receipts")? != 0;
            let last_read_at: Option<String> = row.get("last_read_at")?;
            Ok(json!({
                "group_id": row.get::<_, String>("group_id")?,
                "user_id": row.get::<_, String>("user_id")?,
                "role": row.get::<_, String>("role")?,
                "is_muted": row.get::<_, i64>("is_muted")? != 0,
                "joined_at": row.get::<_, String>("joined_at")?,
                "last_read_at": if read_receipts { last_read_at } else { None },
                "profile": {
                    "id": row.get::<_, String>("user_id")?,
                    "full_name": row.get::<_, String>("full_name")?,
                    "username": row.get::<_, Option<String>>("username")?,
                    "avatar_url": row.get::<_, Option<String>>("avatar_url")?,
                },
            }))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let mut value = group.to_json();
    value["members"] = Value::Array(members);
    Ok(Some(value))
}

/// `isReadByAllOthers()` — double ticks for a group message once every other
/// member has read up to it.
fn read_by_all_others(members: &[Value], sender_id: &str, created_at: &str) -> bool {
    let mut others = 0usize;
    for member in members {
        let user_id = member.get("user_id").and_then(Value::as_str).unwrap_or_default();
        if user_id == sender_id {
            continue;
        }
        others += 1;
        let read_at = member.get("last_read_at").and_then(Value::as_str);
        match read_at {
            Some(value) if value >= created_at => {}
            _ => return false,
        }
    }
    others > 0
}

/// `POST /groups` — create a group; the creator becomes admin.
pub async fn create(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);

    let name = crate::routes::text_field(&body, "name")
        .unwrap_or_default()
        .trim()
        .to_string();
    if name.is_empty() {
        return Err(ApiError::bad_request("name is required"));
    }
    let description = crate::routes::text_field(&body, "description");
    let avatar_url = crate::routes::text_field(&body, "avatar_url");
    let invited = crate::routes::string_list(&body, "memberIds");

    let group_id = new_id();
    let created_at = now_iso();

    let mut members: Vec<String> = vec![user_id.clone()];
    for id in invited {
        if !members.contains(&id) {
            members.push(id);
        }
    }

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO groups (id, name, description, avatar_url, created_by, created_at)
             VALUES (?, ?, ?, ?, ?, ?)",
            params![
                group_id,
                name,
                description.as_deref().map(str::trim).filter(|value| !value.is_empty()),
                avatar_url,
                user_id,
                created_at
            ],
        )?;
        for member in &members {
            conn.execute(
                "INSERT INTO group_members (id, group_id, user_id, role, joined_at)
                 VALUES (?, ?, ?, ?, ?)",
                params![
                    new_id(),
                    group_id,
                    member,
                    if member == &user_id { "admin" } else { "member" },
                    created_at
                ],
            )?;
        }
        Ok(())
    })?;

    let group = group_with_members(&state, &group_id)?
        .ok_or_else(|| ApiError::internal("group vanished after create"))?;
    broadcast_to_members(&state, &members, &json!({ "type": "group:created", "group": group }));

    Ok((StatusCode::CREATED, Json(json!({ "group": group }))))
}

/// `GET /groups` — every group the caller belongs to, newest first, with last
/// message and unread badge.
pub async fn list(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let group_ids = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT g.id FROM groups g JOIN group_members gm ON gm.group_id = g.id
             WHERE gm.user_id = ? ORDER BY g.created_at DESC",
        )?;
        let mapped = statement.query_map(params![user_id], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    let mut groups: Vec<Value> = Vec::with_capacity(group_ids.len());
    for group_id in group_ids {
        let Some(mut group) = group_with_members(&state, &group_id)? else {
            continue;
        };

        let (last, my_joined_at) = state.db.with(|conn| {
            let last = conn
                .query_row(
                    "SELECT * FROM group_messages WHERE group_id = ?
                     ORDER BY created_at DESC LIMIT 1",
                    [&group_id],
                    GroupMessageRow::from_row,
                )
                .optional()?;
            let joined: Option<String> = conn
                .query_row(
                    "SELECT joined_at FROM group_members WHERE group_id = ? AND user_id = ?",
                    params![group_id, user_id],
                    |row| row.get(0),
                )
                .optional()?;
            Ok((last, joined))
        })?;

        let members = group
            .get("members")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();

        let mut last_json = last.as_ref().map(GroupMessageRow::to_json);
        if let (Some(value), Some(row)) = (last_json.as_mut(), last.as_ref()) {
            if row.sender_id == user_id {
                value["is_read"] = json!(read_by_all_others(&members, &user_id, &row.created_at));
            }
        }

        let (my_read_at, unread): (Option<String>, i64) = state.db.with(|conn| {
            let read_at: Option<String> = conn
                .query_row(
                    "SELECT last_read_at FROM group_read_state WHERE group_id = ? AND user_id = ?",
                    params![group_id, user_id],
                    |row| row.get(0),
                )
                .optional()?;
            let since = read_at
                .clone()
                .or_else(|| my_joined_at.clone())
                .unwrap_or_else(|| "1970-01-01T00:00:00.000Z".to_string());
            let unread: i64 = conn.query_row(
                "SELECT COUNT(*) FROM group_messages
                 WHERE group_id = ? AND sender_id != ? AND created_at > ?",
                params![group_id, user_id, since],
                |row| row.get(0),
            )?;
            Ok((read_at, unread))
        })?;
        let _ = my_read_at;

        group["lastMessage"] = last_json.unwrap_or(Value::Null);
        group["unreadCount"] = json!(unread);
        groups.push(group);
    }

    Ok(Json(json!({ "groups": groups })))
}

pub async fn one(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let group = group_with_members(&state, &group_id)?
        .ok_or_else(|| ApiError::not_found("Group not found"))?;
    Ok(Json(json!({ "group": group })))
}

/// `GET /groups/:id/members` — the member list, for members.
///
/// The reference answers any signed-in caller here, which lets a stranger who
/// learns a group id read who is in the group. Membership is required, exactly
/// as on `GET /groups/:id`, and e-mail addresses are not part of the answer.
pub async fn members(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let members = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT gm.*, u.full_name, u.username, u.avatar_url
             FROM group_members gm JOIN users u ON u.id = gm.user_id
             WHERE gm.group_id = ?",
        )?;
        let mapped = statement.query_map([&group_id], |row| {
            Ok(json!({
                "id": row.get::<_, String>("id")?,
                "group_id": row.get::<_, String>("group_id")?,
                "user_id": row.get::<_, String>("user_id")?,
                "role": row.get::<_, String>("role")?,
                "is_muted": row.get::<_, i64>("is_muted")? != 0,
                "joined_at": row.get::<_, String>("joined_at")?,
                "full_name": row.get::<_, String>("full_name")?,
                "username": row.get::<_, Option<String>>("username")?,
                "avatar_url": row.get::<_, Option<String>>("avatar_url")?,
            }))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;
    Ok(Json(json!({ "members": members })))
}

pub async fn add_members(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let Some((role, _)) = membership(&state, &group_id, &user_id)? else {
        return Err(ApiError::forbidden("Not a member of this group"));
    };
    if role != "admin" {
        return Err(ApiError::forbidden("Only admins can add members"));
    }

    let body = crate::routes::parse_body(&body);
    let new_ids: Vec<String> = crate::routes::string_list(&body, "memberIds")
        .into_iter()
        .filter(|id| !id.is_empty())
        .collect();
    if new_ids.is_empty() {
        return Ok(Json(json!({ "success": true })));
    }

    let joined_at = now_iso();
    state.db.with(|conn| {
        for id in &new_ids {
            conn.execute(
                "INSERT OR IGNORE INTO group_members (id, group_id, user_id, role, joined_at)
                 VALUES (?, ?, ?, 'member', ?)",
                params![new_id(), group_id, id, joined_at],
            )?;
        }
        Ok(())
    })?;

    let group = group_with_members(&state, &group_id)?
        .ok_or_else(|| ApiError::not_found("Group not found"))?;
    let members = group
        .get("members")
        .cloned()
        .unwrap_or_else(|| Value::Array(Vec::new()));

    let all_ids = member_ids(&state, &group_id)?;
    broadcast_to_members(
        &state,
        &all_ids,
        &json!({
            "type": "group-member:joined",
            "group_id": group_id,
            "members": members,
        }),
    );
    broadcast_to_members(
        &state,
        &new_ids,
        &json!({ "type": "group:created", "group": group }),
    );

    Ok(Json(json!({ "success": true, "members": members })))
}

pub async fn remove_member(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((group_id, target_id)): Path<(String, String)>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let Some((role, _)) = membership(&state, &group_id, &user_id)? else {
        return Err(ApiError::forbidden("Not a member of this group"));
    };
    if role != "admin" && user_id != target_id {
        return Err(ApiError::forbidden("Only admins can remove other members"));
    }

    let removed = state.db.with(|conn| {
        conn.execute(
            "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
            params![group_id, target_id],
        )
    })?;

    // Only a removal that happened is announced. Without this an admin could
    // push `group-member:left` for their group to any user id at all.
    if removed > 0 {
        let ids = member_ids(&state, &group_id)?;
        let payload = json!({
            "type": "group-member:left",
            "group_id": group_id,
            "user_id": target_id,
        });
        broadcast_to_members(&state, &ids, &payload);
        state.hub.publish_to_user(&target_id, &payload.to_string());
    }

    Ok(Json(json!({ "success": true })))
}

pub async fn leave(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let members = state.db.with(|conn| {
        let mut statement =
            conn.prepare("SELECT user_id, role FROM group_members WHERE group_id = ?")?;
        let mapped = statement.query_map([&group_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    // Only a member can leave. The reference skips this check, and because the
    // "last member left" branch below deletes the group and its messages, anyone
    // who knew the id of a one-member group could delete it. An unknown group
    // gets the same answer, so the response does not reveal which ids exist.
    if !members.iter().any(|(id, _)| id == &user_id) {
        return Err(ApiError::forbidden("Not a member of this group"));
    }

    let admins: Vec<&String> = members
        .iter()
        .filter(|(_, role)| role == "admin")
        .map(|(id, _)| id)
        .collect();
    let is_only_admin = admins.len() == 1 && admins[0] == &user_id;
    if is_only_admin && members.len() > 1 {
        return Err(ApiError::bad_request(
            "Make someone else an admin before you leave",
        ));
    }

    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
            params![group_id, user_id],
        )?;
        if members.len() <= 1 {
            conn.execute("DELETE FROM group_messages WHERE group_id = ?", [&group_id])?;
            conn.execute("DELETE FROM groups WHERE id = ?", [&group_id])?;
        }
        Ok(())
    })?;

    if members.len() > 1 {
        let ids = member_ids(&state, &group_id)?;
        broadcast_to_members(
            &state,
            &ids,
            &json!({
                "type": "group-member:left",
                "group_id": group_id,
                "user_id": user_id,
            }),
        );
    }
    state.hub.publish_to_user(
        &user_id,
        &json!({
            "type": "group-member:left",
            "group_id": group_id,
            "user_id": user_id,
        })
        .to_string(),
    );

    Ok(Json(json!({ "success": true })))
}

pub async fn set_role(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((group_id, target_id)): Path<(String, String)>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let role = crate::routes::text_field(&body, "role").unwrap_or_default();
    if role != "admin" && role != "member" {
        return Err(ApiError::bad_request("role must be admin or member"));
    }
    if user_id == target_id {
        return Err(ApiError::bad_request("You cannot change your own role"));
    }
    match membership(&state, &group_id, &user_id)? {
        Some((my_role, _)) if my_role == "admin" => {}
        _ => return Err(ApiError::forbidden("Only admins can change member roles")),
    }

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_members SET role = ? WHERE group_id = ? AND user_id = ?",
            params![role, group_id, target_id],
        )?;
        Ok(())
    })?;

    let group = group_with_members(&state, &group_id)?;
    let members = group
        .and_then(|value| value.get("members").cloned())
        .unwrap_or_else(|| Value::Array(Vec::new()));
    let ids = member_ids(&state, &group_id)?;
    broadcast_to_members(
        &state,
        &ids,
        &json!({
            "type": "group-member:updated",
            "group_id": group_id,
            "members": members,
        }),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn mute(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let body = crate::routes::parse_body(&body);
    let muted = crate::routes::bool_field(&body, "muted").unwrap_or(false);

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_members SET is_muted = ? WHERE group_id = ? AND user_id = ?",
            params![if muted { 1 } else { 0 }, group_id, user_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn update(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    match membership(&state, &group_id, &user_id)? {
        Some((role, _)) if role == "admin" => {}
        _ => return Err(ApiError::forbidden("Only admins can update group info")),
    }

    let body = crate::routes::parse_body(&body);
    let current = state.db.with(|conn| {
        conn.query_row("SELECT * FROM groups WHERE id = ?", [&group_id], GroupRow::from_row)
            .optional()
    })?;
    let Some(current) = current else {
        return Err(ApiError::not_found("Group not found"));
    };

    let name = crate::routes::text_field(&body, "name")
        .map(|value| value.trim().to_string())
        .unwrap_or(current.name);
    let description = match crate::routes::text_field(&body, "description") {
        Some(value) => {
            let trimmed = value.trim().to_string();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed)
            }
        }
        None => current.description,
    };
    let avatar_url = crate::routes::text_field(&body, "avatar_url").or(current.avatar_url);

    state.db.with(|conn| {
        conn.execute(
            "UPDATE groups SET name = ?, description = ?, avatar_url = ?, updated_at = ?
             WHERE id = ?",
            params![name, description, avatar_url, now_iso(), group_id],
        )?;
        Ok(())
    })?;

    let group = group_with_members(&state, &group_id)?
        .ok_or_else(|| ApiError::not_found("Group not found"))?;
    let ids = member_ids(&state, &group_id)?;
    broadcast_to_members(&state, &ids, &json!({ "type": "group:updated", "group": group }));

    Ok(Json(json!({ "success": true, "group": group })))
}

pub async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    match membership(&state, &group_id, &user_id)? {
        Some((role, _)) if role == "admin" => {}
        _ => return Err(ApiError::forbidden("Only admins can delete the group")),
    }

    let ids = member_ids(&state, &group_id)?;
    state.db.with(|conn| {
        conn.execute("DELETE FROM group_messages WHERE group_id = ?", [&group_id])?;
        conn.execute("DELETE FROM group_members WHERE group_id = ?", [&group_id])?;
        conn.execute("DELETE FROM groups WHERE id = ?", [&group_id])?;
        Ok(())
    })?;

    broadcast_to_members(
        &state,
        &ids,
        &json!({ "type": "group:deleted", "group_id": group_id }),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn messages(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    Query(query): Query<MessagesQuery>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }

    let limit = clamp_limit(query.limit.as_deref(), 50, 100);
    let rows = state.db.with(|conn| {
        let mapped = match &query.before {
            Some(before) => {
                let mut statement = conn.prepare(
                    "SELECT * FROM group_messages WHERE group_id = ? AND created_at < ?
                     ORDER BY created_at DESC LIMIT ?",
                )?;
                let collected = statement
                    .query_map(params![group_id, before, limit as i64], GroupMessageRow::from_row)?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                collected
            }
            None => {
                let mut statement = conn.prepare(
                    "SELECT * FROM group_messages WHERE group_id = ?
                     ORDER BY created_at DESC LIMIT ?",
                )?;
                let collected = statement
                    .query_map(params![group_id, limit as i64], GroupMessageRow::from_row)?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                collected
            }
        };
        Ok(mapped)
    })?;

    let mut messages: Vec<Value> = rows.iter().map(GroupMessageRow::to_json).collect();
    messages.reverse();
    Ok(Json(json!({ "messages": messages })))
}

pub async fn send_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let body = crate::routes::parse_body(&body);
    let client_message_id = crate::routes::text_field(&body, "client_message_id");

    // Idempotent retry, same as 1:1.
    if let Some(client_id) = &client_message_id {
        let existing = state.db.with(|conn| {
            conn.query_row(
                "SELECT * FROM group_messages
                 WHERE group_id = ? AND sender_id = ? AND client_message_id = ?",
                params![group_id, user_id, client_id],
                GroupMessageRow::from_row,
            )
            .optional()
        })?;
        if let Some(row) = existing {
            return Ok((StatusCode::CREATED, Json(json!({ "message": row.to_json() }))));
        }
    }

    let row = GroupMessageRow {
        id: new_id(),
        group_id: group_id.clone(),
        sender_id: user_id.clone(),
        content: crate::routes::text_field(&body, "content").unwrap_or_default(),
        kind: crate::routes::text_field(&body, "type").unwrap_or_else(|| "text".to_string()),
        media_url: crate::routes::text_field(&body, "media_url"),
        thumbnail_url: crate::routes::text_field(&body, "thumbnail_url"),
        duration: crate::routes::text_field(&body, "duration"),
        client_message_id,
        reply_to: crate::routes::text_field(&body, "reply_to"),
        is_edited: false,
        edited_at: None,
        is_deleted: false,
        deleted_for: None,
        reactions: None,
        created_at: now_iso(),
    };

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO group_messages (id, group_id, sender_id, content, type, media_url,
                                         thumbnail_url, duration, client_message_id, reply_to,
                                         created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                row.id,
                row.group_id,
                row.sender_id,
                row.content,
                row.kind,
                row.media_url,
                row.thumbnail_url,
                row.duration,
                row.client_message_id,
                row.reply_to,
                row.created_at,
            ],
        )?;
        Ok(())
    })?;

    let ids = member_ids(&state, &group_id)?;
    broadcast_to_members(
        &state,
        &ids,
        &json!({ "type": "group-message:new", "message": row.to_json() }),
    );

    Ok((StatusCode::CREATED, Json(json!({ "message": row.to_json() }))))
}

pub async fn delete_message_for_me(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(message_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let current: Option<(String, Option<String>)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT group_id, deleted_for FROM group_messages WHERE id = ?",
            [&message_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
    })?;
    let Some((group_id, current)) = current else {
        return Err(ApiError::not_found("Message not found"));
    };
    // This writes to the message row, so it is for current members only. To
    // anyone else the message does not exist (the 1:1 routes answer the same way).
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::not_found("Message not found"));
    }

    let mut list: Vec<String> = current
        .as_deref()
        .and_then(|raw| serde_json::from_str(raw).ok())
        .unwrap_or_default();
    if !list.contains(&user_id) {
        list.push(user_id);
    }
    let encoded = serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string());

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_messages SET deleted_for = ? WHERE id = ?",
            params![encoded, message_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn delete_message_for_everyone(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(message_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let row: Option<(String, String, String)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT group_id, sender_id, created_at FROM group_messages WHERE id = ?",
            [&message_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
    })?;
    let Some((group_id, sender_id, created_at)) = row else {
        return Err(ApiError::not_found("Message not found"));
    };
    // Someone who has left or been removed no longer edits the group's history,
    // not even their own messages in it.
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::not_found("Message not found"));
    }
    if sender_id != user_id {
        return Err(ApiError::forbidden("You can only delete your own messages"));
    }
    if crate::util::now_millis() - parse_iso_millis(&created_at).unwrap_or(0)
        > DELETE_FOR_EVERYONE_WINDOW_MS
    {
        return Err(ApiError::bad_request(
            "Cannot delete messages older than 1 hour",
        ));
    }

    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_messages SET is_deleted = 1, content = 'This message was deleted'
             WHERE id = ?",
            [&message_id],
        )?;
        Ok(())
    })?;

    let ids = member_ids(&state, &group_id)?;
    broadcast_to_members(
        &state,
        &ids,
        &json!({
            "type": "group-message:delete",
            "group_id": group_id,
            "message_id": message_id,
        }),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn react_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(message_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let emoji = crate::routes::text_field(&body, "emoji").unwrap_or_default();
    if emoji.is_empty() {
        return Err(ApiError::bad_request("emoji is required"));
    }

    let row: Option<(String, Option<String>)> = state.db.with(|conn| {
        conn.query_row(
            "SELECT group_id, reactions FROM group_messages WHERE id = ?",
            [&message_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
    })?;
    let Some((group_id, reactions)) = row else {
        return Err(ApiError::not_found("Message not found"));
    };
    // The reference lets any signed-in caller react to any group message whose
    // id they know, and then broadcasts the result to the whole group.
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::not_found("Message not found"));
    }

    let mut map: std::collections::BTreeMap<String, Vec<String>> = reactions
        .as_deref()
        .and_then(|raw| serde_json::from_str(raw).ok())
        .unwrap_or_default();
    let mut list = map.get(&emoji).cloned().unwrap_or_default();
    if list.contains(&user_id) {
        list.retain(|value| value != &user_id);
    } else {
        list.push(user_id.clone());
    }
    if list.is_empty() {
        map.remove(&emoji);
    } else {
        map.insert(emoji, list);
    }

    let encoded = serde_json::to_string(&map).unwrap_or_else(|_| "{}".to_string());
    state.db.with(|conn| {
        conn.execute(
            "UPDATE group_messages SET reactions = ? WHERE id = ?",
            params![encoded, message_id],
        )?;
        Ok(())
    })?;

    let reactions_json = serde_json::to_value(&map).unwrap_or(Value::Null);
    let ids = member_ids(&state, &group_id)?;
    broadcast_to_members(
        &state,
        &ids,
        &json!({
            "type": "group-message:reaction",
            "group_id": group_id,
            "message_id": message_id,
            "reactions": reactions_json,
        }),
    );
    Ok(Json(json!({ "reactions": reactions_json })))
}

pub async fn typing(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let body = crate::routes::parse_body(&body);
    let is_typing = crate::routes::bool_field(&body, "is_typing").unwrap_or(false);

    let others: Vec<String> = member_ids(&state, &group_id)?
        .into_iter()
        .filter(|id| id != &user_id)
        .collect();
    broadcast_to_members(
        &state,
        &others,
        &json!({
            "type": "group-typing",
            "group_id": group_id,
            "sender_id": user_id,
            "is_typing": is_typing,
        }),
    );
    Ok(Json(json!({ "success": true })))
}

pub async fn recording(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }
    let body = crate::routes::parse_body(&body);
    let is_recording = crate::routes::bool_field(&body, "is_recording").unwrap_or(false);

    let others: Vec<String> = member_ids(&state, &group_id)?
        .into_iter()
        .filter(|id| id != &user_id)
        .collect();
    broadcast_to_members(
        &state,
        &others,
        &json!({
            "type": "group-recording",
            "group_id": group_id,
            "sender_id": user_id,
            "is_recording": is_recording,
        }),
    );
    Ok(Json(json!({ "success": true })))
}

/// `POST /groups/:id/read` — persist my high-water mark, broadcast only if my
/// read receipts are on.
pub async fn mark_read(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(group_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    if membership(&state, &group_id, &user_id)?.is_none() {
        return Err(ApiError::forbidden("Not a member of this group"));
    }

    let read_at = now_iso();
    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO group_read_state (group_id, user_id, last_read_at) VALUES (?, ?, ?)
             ON CONFLICT(group_id, user_id) DO UPDATE SET last_read_at = excluded.last_read_at",
            params![group_id, user_id, read_at],
        )?;
        Ok(())
    })?;

    let settings = state
        .db
        .with(|conn| crate::privacy::get_privacy_settings(conn, &user_id))?;
    if settings.read_receipts {
        let others: Vec<String> = member_ids(&state, &group_id)?
            .into_iter()
            .filter(|id| id != &user_id)
            .collect();
        broadcast_to_members(
            &state,
            &others,
            &json!({
                "type": "group-message:read",
                "group_id": group_id,
                "reader_id": user_id,
                "read_at": read_at,
            }),
        );
    }

    Ok(Json(json!({ "success": true })))
}
