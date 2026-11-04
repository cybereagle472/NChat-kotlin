//! `/status/*` — port of `worker/src/routes/status.ts` (24-hour stories).

use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::util::{iso_in_secs, new_id, now_iso};
use crate::AppState;

const STATUS_TTL_SECS: i64 = 24 * 60 * 60;

fn friend_ids(state: &AppState, user_id: &str) -> ApiResult<Vec<String>> {
    Ok(state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT CASE WHEN user1_id = ? THEN user2_id ELSE user1_id END AS friend_id
             FROM friendships WHERE user1_id = ? OR user2_id = ?",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id], |row| {
            row.get::<_, String>(0)
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?)
}

pub async fn create(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);

    let id = new_id();
    let created_at = now_iso();
    let expires_at = iso_in_secs(STATUS_TTL_SECS);
    let content_type = crate::routes::text_field(&body, "content_type")
        .unwrap_or_else(|| "text".to_string());
    let privacy = crate::routes::text_field(&body, "privacy")
        .unwrap_or_else(|| "contacts".to_string());

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO statuses (id, user_id, content_type, content_url, text_content,
                                   background_color, font_style, thumbnail_url, duration,
                                   caption, privacy, created_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                id,
                user_id,
                content_type,
                crate::routes::text_field(&body, "content_url"),
                crate::routes::text_field(&body, "text_content"),
                crate::routes::text_field(&body, "background_color"),
                crate::routes::text_field(&body, "font_style"),
                crate::routes::text_field(&body, "thumbnail_url"),
                crate::routes::text_field(&body, "duration"),
                crate::routes::text_field(&body, "caption"),
                privacy,
                created_at,
                expires_at,
            ],
        )?;
        Ok(())
    })?;

    let row = state.db.with(|conn| {
        conn.query_row(
            "SELECT * FROM statuses WHERE id = ?",
            [&id],
            Row::from_row,
        )
        .optional()
    })?;
    let row = row.ok_or_else(|| ApiError::internal("status vanished after insert"))?;

    // Friends get it live, with the poster's name/avatar attached.
    let poster = state.db.with(|conn| {
        conn.query_row(
            "SELECT full_name, username, avatar_url FROM users WHERE id = ?",
            [&user_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, Option<String>>(1)?,
                    row.get::<_, Option<String>>(2)?,
                ))
            },
        )
        .optional()
    })?;

    let mut broadcast = row.to_json();
    if let Some((full_name, username, avatar)) = poster {
        broadcast["full_name"] = json!(full_name);
        broadcast["username"] = json!(username);
        broadcast["avatar_url"] = json!(avatar);
    }

    for friend in friend_ids(&state, &user_id)? {
        state.hub.publish_to_user(
            &friend,
            &json!({ "type": "status:new", "status": broadcast }).to_string(),
        );
    }

    Ok((StatusCode::CREATED, Json(json!({ "status": row.to_json() }))))
}

/// Story row plus its JSON shape (`SELECT *` column order is irrelevant: every
/// field is read by name).
pub struct Row {
    id: String,
    user_id: String,
    content_type: String,
    content_url: Option<String>,
    text_content: Option<String>,
    background_color: Option<String>,
    font_style: Option<String>,
    thumbnail_url: Option<String>,
    duration: Option<String>,
    caption: Option<String>,
    privacy: String,
    created_at: String,
    expires_at: String,
}

impl Row {
    pub fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            user_id: row.get("user_id")?,
            content_type: row.get("content_type")?,
            content_url: row.get("content_url")?,
            text_content: row.get("text_content")?,
            background_color: row.get("background_color")?,
            font_style: row.get("font_style")?,
            thumbnail_url: row.get("thumbnail_url")?,
            duration: row.get("duration")?,
            caption: row.get("caption")?,
            privacy: row.get("privacy")?,
            created_at: row.get("created_at")?,
            expires_at: row.get("expires_at")?,
        })
    }

    pub fn to_json(&self) -> Value {
        let mut value = json!({
            "id": self.id,
            "user_id": self.user_id,
            "content_type": self.content_type,
            "privacy": self.privacy,
            "created_at": self.created_at,
            "expires_at": self.expires_at,
        });
        let object = value.as_object_mut().expect("object");
        for (key, slot) in [
            ("content_url", &self.content_url),
            ("text_content", &self.text_content),
            ("background_color", &self.background_color),
            ("font_style", &self.font_style),
            ("thumbnail_url", &self.thumbnail_url),
            ("duration", &self.duration),
            ("caption", &self.caption),
        ] {
            if let Some(text) = slot {
                object.insert(key.to_string(), json!(text));
            }
        }
        value
    }
}

pub async fn mine(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let now = now_iso();

    let statuses = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT s.id, s.user_id, s.content_type, s.content_url, s.text_content,
                    s.background_color, s.font_style, s.thumbnail_url, s.duration,
                    s.caption, s.privacy, s.created_at, s.expires_at,
                    (SELECT COUNT(*) FROM status_views sv
                      WHERE sv.status_id = s.id AND sv.viewer_id != s.user_id) AS view_count
             FROM statuses s
             WHERE s.user_id = ? AND s.expires_at > ?
             ORDER BY s.created_at ASC",
        )?;
        let mapped = statement.query_map(params![user_id, now], |row| {
            let mut value = Row::from_row(row)?.to_json();
            value["view_count"] = json!(row.get::<_, i64>("view_count")?);
            Ok(value)
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    Ok(Json(json!({ "statuses": statuses })))
}

pub async fn feed(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let now = now_iso();

    let statuses = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT s.*, u.full_name, u.username, u.avatar_url,
                    EXISTS(SELECT 1 FROM status_views sv
                           WHERE sv.status_id = s.id AND sv.viewer_id = ?) AS viewed_by_me
             FROM statuses s
             JOIN users u ON u.id = s.user_id
             JOIN friendships f
               ON (f.user1_id = ? AND f.user2_id = s.user_id)
               OR (f.user2_id = ? AND f.user1_id = s.user_id)
             WHERE s.expires_at > ?
               AND s.user_id NOT IN (SELECT muted_user_id FROM muted_statuses WHERE user_id = ?)
             ORDER BY s.created_at ASC",
        )?;
        let mapped = statement.query_map(params![user_id, user_id, user_id, now, user_id], |row| {
            let mut value = Row::from_row(row)?.to_json();
            value["full_name"] = json!(row.get::<_, String>("full_name")?);
            value["username"] = json!(row.get::<_, Option<String>>("username")?);
            value["avatar_url"] = json!(row.get::<_, Option<String>>("avatar_url")?);
            value["viewed_by_me"] = json!(row.get::<_, i64>("viewed_by_me")? != 0);
            Ok(value)
        })?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;

    Ok(Json(json!({ "statuses": statuses })))
}

pub async fn view(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let owner: Option<String> = state.db.with(|conn| {
        conn.query_row("SELECT user_id FROM statuses WHERE id = ?", [&id], |row| {
            row.get(0)
        })
        .optional()
    })?;
    let owner = owner.ok_or_else(|| ApiError::not_found("Status not found"))?;

    // Viewing your own status is not a view.
    if owner == user_id {
        return Ok(Json(json!({ "success": true })));
    }

    let inserted = state.db.with(|conn| {
        conn.execute(
            "INSERT OR IGNORE INTO status_views (id, status_id, viewer_id) VALUES (?, ?, ?)",
            params![new_id(), id, user_id],
        )
    })?;

    if inserted > 0 {
        state.db.with(|conn| {
            conn.execute("UPDATE statuses SET view_count = view_count + 1 WHERE id = ?", [&id])?;
            Ok(())
        })?;

        let viewer = state.db.with(|conn| {
            conn.query_row(
                "SELECT full_name, username, avatar_url FROM users WHERE id = ?",
                [&user_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, Option<String>>(2)?,
                    ))
                },
            )
            .optional()
        })?;

        let mut payload = json!({
            "type": "status:view",
            "status_id": id,
            "viewer_id": user_id,
        });
        if let Some((full_name, username, avatar)) = viewer {
            payload["viewer_profile"] = json!({
                "id": user_id,
                "full_name": full_name,
                "username": username,
                "avatar_url": avatar,
            });
        }
        state.hub.publish_to_user(&owner, &payload.to_string());
    }

    Ok(Json(json!({ "success": true })))
}

pub async fn views(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    state.current_user(&headers)?;
    let views = state.db.with(|conn| {
        let mut statement = conn.prepare(
            "SELECT sv.id, sv.status_id, sv.viewer_id, sv.viewed_at,
                    u.full_name, u.username, u.avatar_url
             FROM status_views sv
             JOIN users u ON u.id = sv.viewer_id
             JOIN statuses s ON s.id = sv.status_id
             WHERE sv.status_id = ? AND sv.viewer_id != s.user_id
             ORDER BY sv.viewed_at DESC",
        )?;
        let mapped = statement.query_map([&id], |row| {
            Ok(json!({
                "id": row.get::<_, String>("id")?,
                "status_id": row.get::<_, String>("status_id")?,
                "viewer_id": row.get::<_, String>("viewer_id")?,
                "viewed_at": row.get::<_, String>("viewed_at")?,
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
    Ok(Json(json!({ "views": views })))
}

pub async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM statuses WHERE id = ? AND user_id = ?",
            params![id, user_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn mute(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = crate::routes::parse_body(&body);
    let muted = crate::routes::text_field(&body, "muted_user_id")
        .ok_or_else(|| ApiError::bad_request("muted_user_id is required"))?;

    state.db.with(|conn| {
        conn.execute(
            "INSERT OR IGNORE INTO muted_statuses (user_id, muted_user_id) VALUES (?, ?)",
            params![user_id, muted],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn unmute(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(muted_user_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM muted_statuses WHERE user_id = ? AND muted_user_id = ?",
            params![user_id, muted_user_id],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

pub async fn muted(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let muted = state.db.with(|conn| {
        let mut statement =
            conn.prepare("SELECT muted_user_id FROM muted_statuses WHERE user_id = ?")?;
        let mapped = statement.query_map([&user_id], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for row in mapped {
            out.push(row?);
        }
        Ok(out)
    })?;
    Ok(Json(json!({ "muted": muted })))
}
