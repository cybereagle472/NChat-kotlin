//! `/media/*` — port of `worker/src/routes/media.ts` (R2 → local files).
//!
//! Uploads stream a multipart part to `NCHAT_DATA_DIR/media/<user>/<id>.<ext>`,
//! record a `media_attachments` row and return the public URL — the same shape
//! the frontend expects. Reads support `Range` so audio/video scrubbing works.

use axum::body::Body;
use axum::extract::{Multipart, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use rusqlite::{params, OptionalExtension};
use serde_json::{json, Value};
use sha2::Sha256;
use std::collections::HashMap;
use tokio::io::AsyncReadExt;

use crate::error::{ApiError, ApiResult};
use crate::util::{new_id, now_secs};
use crate::AppState;

fn media_type_from_mime(mime: &str) -> &'static str {
    if mime.starts_with("image/") {
        "image"
    } else if mime.starts_with("video/") {
        "video"
    } else if mime.starts_with("audio/") {
        "audio"
    } else {
        "document"
    }
}

/// Port of `media.post('/upload')`.
///
/// The multipart part is streamed straight to disk chunk by chunk (R2's `put`
/// also never holds the object in memory), so a 200 MB video costs one 64 KB
/// buffer instead of 200 MB of RSS.
pub async fn upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;

    struct Stored {
        key: String,
        file_name: String,
        mime: String,
        size: i64,
    }

    let mut stored: Option<Stored> = None;
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|error| ApiError::bad_request(format!("invalid multipart body: {error}")))?
    {
        if field.name() != Some("file") {
            continue;
        }
        let file_name = field.file_name().unwrap_or("upload").to_string();
        let mime = field
            .content_type()
            .unwrap_or("application/octet-stream")
            .to_string();

        // `${userId}/${newId()}${ext ? '.' + ext : ''}` — same key shape as R2.
        let extension = file_name.rsplit_once('.').map(|(_, ext)| ext.to_string());
        let key = match extension.filter(|ext| !ext.is_empty() && is_plain_extension(ext)) {
            Some(ext) => format!("{user_id}/{}.{ext}", new_id()),
            None => format!("{user_id}/{}", new_id()),
        };

        let path = state.media_dir.join(&key);
        if let Some(parent) = path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        let mut sink = tokio::fs::File::create(&path).await?;
        let mut size: i64 = 0;
        loop {
            match field.chunk().await {
                Ok(Some(chunk)) => {
                    use tokio::io::AsyncWriteExt;
                    if size + chunk.len() as i64 > state.max_upload_bytes as i64 {
                        drop(sink);
                        let _ = tokio::fs::remove_file(&path).await;
                        return Err(ApiError::new(
                            StatusCode::PAYLOAD_TOO_LARGE,
                            format!(
                                "file is too large (limit is {} MB)",
                                state.max_upload_bytes / (1024 * 1024)
                            ),
                        ));
                    }
                    if let Err(error) = sink.write_all(&chunk).await {
                        drop(sink);
                        let _ = tokio::fs::remove_file(&path).await;
                        return Err(ApiError::internal(format!(
                            "could not store the upload: {error}"
                        )));
                    }
                    size += chunk.len() as i64;
                }
                Ok(None) => break,
                Err(error) => {
                    drop(sink);
                    let _ = tokio::fs::remove_file(&path).await;
                    return Err(ApiError::bad_request(format!(
                        "could not read the file: {error}"
                    )));
                }
            }
        }
        {
            use tokio::io::AsyncWriteExt;
            sink.flush().await?;
        }

        stored = Some(Stored {
            key,
            file_name,
            mime,
            size,
        });
        break;
    }

    let Some(stored) = stored else {
        return Err(ApiError::bad_request(
            "file is required (multipart/form-data)",
        ));
    };

    let id = new_id();
    let media_type = media_type_from_mime(&stored.mime);

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO media_attachments
               (id, uploader_id, file_path, file_name, file_size, mime_type, media_type, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                id,
                user_id,
                stored.key,
                stored.file_name,
                stored.size,
                stored.mime,
                media_type,
                crate::util::now_iso()
            ],
        )?;
        Ok(())
    })?;

    let url = format!("{}/media/file/{}", state.public_api_url, stored.key);
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "id": id,
            "url": url,
            "key": stored.key,
            "media_type": media_type,
            "file_name": stored.file_name,
            "file_size": stored.size,
        })),
    ))
}

/// R2 accepts any extension, but a key is also a path on disk here, so refuse
/// anything that could escape the media directory.
fn is_plain_extension(extension: &str) -> bool {
    extension.len() <= 16
        && !extension.contains('/')
        && !extension.contains('\\')
        && !extension.contains("..")
}

use std::path::{Component, Path as FsPath, PathBuf};

/// Rejects keys that could escape the media directory.
///
/// `R2.get(key)` treats the key as an opaque name, but here it is a path on
/// disk — so `..`, absolute paths and backslashes must never reach `join`.
fn safe_media_path(media_dir: &FsPath, key: &str) -> Option<PathBuf> {
    if key.is_empty() || key.contains('\0') || key.contains('\\') {
        return None;
    }
    let candidate = FsPath::new(key);
    if candidate.is_absolute() {
        return None;
    }
    for component in candidate.components() {
        match component {
            Component::Normal(_) => {}
            // `.`, `..`, a root or a Windows prefix: refuse the whole request.
            _ => return None,
        }
    }
    let path = media_dir.join(candidate);
    path.starts_with(media_dir).then_some(path)
}

// ------------------------------------------------------------ signed links

type HmacSha256 = Hmac<Sha256>;

/// `sig = base64url(HMAC-SHA256(secret, "media:<key>:<unix exp>"))`.
///
/// The signature covers the key *and* the expiry, so a link can neither be
/// pointed at a different file nor kept alive by editing the query string.
fn sign_link(secret: &str, key: &str, exp: i64) -> String {
    let mut mac =
        HmacSha256::new_from_slice(secret.as_bytes()).expect("HMAC accepts any key length");
    mac.update(format!("media:{key}:{exp}").as_bytes());
    URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
}

/// Signature check (constant time) plus expiry check.
fn verify_link(secret: &str, key: &str, exp: i64, signature: &str) -> bool {
    if exp <= now_secs() {
        return false;
    }
    let expected = sign_link(secret, key, exp);
    constant_time_eq(expected.as_bytes(), signature.as_bytes())
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in left.iter().zip(right) {
        diff |= a ^ b;
    }
    diff == 0
}

/// Who may read a stored object.
///
/// A file is private to the conversation it belongs to: the uploader, both ends
/// of a 1:1 message, every member of the group it was sent to, or the audience
/// of a status. Avatars are the documented exception — they are shown in search
/// results and member lists, so any signed-in user may load them.
mod access {
    use super::*;
    use crate::privacy::status_visible_to;

    pub fn can_read(state: &AppState, user_id: &str, key: &str) -> ApiResult<bool> {
        // `Db::with` hands back a `rusqlite::Result`; `?` turns a failed query into a 500.
        Ok(state.db.with(|conn| {
            let owned: Option<i64> = conn
                .query_row(
                    "SELECT 1 FROM media_attachments WHERE file_path = ? AND uploader_id = ?",
                    params![key, user_id],
                    |row| row.get(0),
                )
                .optional()?;
            if owned.is_some() {
                return Ok(true);
            }

            // ---- 1:1 messages ------------------------------------------------
            let mut statement = conn.prepare(
                "SELECT sender_id, receiver_id FROM messages
                 WHERE instr(coalesce(media_url, ''), ?1) > 0
                    OR instr(coalesce(thumbnail_url, ''), ?1) > 0",
            )?;
            let pairs = statement
                .query_map([key], |row| {
                    Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            if !pairs.is_empty() {
                return Ok(pairs
                    .iter()
                    .any(|(sender, receiver)| sender == user_id || receiver == user_id));
            }

            // ---- group messages ----------------------------------------------
            let mut statement = conn.prepare(
                "SELECT group_id FROM group_messages
                 WHERE instr(coalesce(media_url, ''), ?1) > 0
                    OR instr(coalesce(thumbnail_url, ''), ?1) > 0",
            )?;
            let groups = statement
                .query_map([key], |row| row.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            if !groups.is_empty() {
                for group_id in groups {
                    let member: Option<i64> = conn
                        .query_row(
                            "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
                            params![group_id, user_id],
                            |row| row.get(0),
                        )
                        .optional()?;
                    if member.is_some() {
                        return Ok(true);
                    }
                }
                return Ok(false);
            }

            // ---- statuses (unexpired, audience-checked) -----------------------
            let mut statement = conn.prepare(
                "SELECT user_id, privacy FROM statuses
                 WHERE expires_at > ?2
                   AND (instr(coalesce(content_url, ''), ?1) > 0
                        OR instr(coalesce(thumbnail_url, ''), ?1) > 0)",
            )?;
            let statuses = statement
                .query_map(params![key, crate::util::now_iso()], |row| {
                    Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            // Same rule as the feed: the stricter of the status's own audience and
            // the author's account setting, never across a block, and an audience
            // this server does not know counts as "nobody".
            for (author, privacy) in statuses {
                if status_visible_to(conn, &author, user_id, &privacy)? {
                    return Ok(true);
                }
            }

            // ---- avatars ------------------------------------------------------
            let avatar: Option<i64> = conn
                .query_row(
                    "SELECT 1 FROM users WHERE instr(coalesce(avatar_url, ''), ?1) > 0
                     UNION ALL
                     SELECT 1 FROM groups WHERE instr(coalesce(avatar_url, ''), ?1) > 0
                     LIMIT 1",
                    [key],
                    |row| row.get(0),
                )
                .optional()?;
            Ok(avatar.is_some())
        })?)
    }
}

/// `GET /media/file/*key` — read with byte-range support (HTTP 206).
///
/// Authorisation is checked before a single byte is read:
/// - a signed link (`?exp=&sig=`), minted by `/media/link`, needs no token —
///   that is the "share outside the app" path;
/// - otherwise the caller's bearer token must belong to the conversation that
///   owns the file. A stranger gets `404`, so the response never confirms that
///   a key exists.
///
/// The body is streamed from disk: a 200 MB video used to be read into memory
/// for every request, which is a one-line denial of service on a small VPS.
pub async fn file(
    State(state): State<AppState>,
    Path(key): Path<String>,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let Some(path) = safe_media_path(&state.media_dir, &key) else {
        return Err(ApiError::not_found("Not found"));
    };

    if state.media_requires_auth {
        let signed = match (
            query
                .get("exp")
                .and_then(|value| value.parse::<i64>().ok()),
            query.get("sig"),
        ) {
            (Some(exp), Some(signature)) => verify_link(state.secret.as_str(), &key, exp, signature),
            _ => false,
        };
        if !signed {
            let user_id = state
                .current_user(&headers)
                .map_err(|_| ApiError::unauthorized("This file needs a signed-in request"))?;
            if !access::can_read(&state, &user_id, &key)? {
                return Err(ApiError::not_found("Not found"));
            }
        }
    }

    let mut handle = match tokio::fs::File::open(&path).await {
        Ok(handle) => handle,
        Err(_) => return Err(ApiError::not_found("Not found")),
    };

    let total = match handle.metadata().await {
        Ok(metadata) => metadata.len(),
        Err(_) => return Err(ApiError::not_found("Not found")),
    };

    let row: Option<String> = state.db.with(|conn| {
        conn.query_row(
            "SELECT mime_type FROM media_attachments WHERE file_path = ?",
            [&key],
            |row| row.get(0),
        )
        .optional()
    })?;
    let mime = row.unwrap_or_else(|| "application/octet-stream".to_string());

    // `Range: bytes=start-end` → 206 with Content-Range, like R2 does.
    let range = headers
        .get(header::RANGE)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("bytes=").map(str::to_string))
        .and_then(|spec| {
            let (start, end) = spec.split_once('-')?;
            let start: u64 = start.trim().parse().ok()?;
            let end: u64 = match end.trim() {
                "" => total.saturating_sub(1),
                value => value.parse::<u64>().ok()?,
            };
            if start > end || start >= total {
                return None;
            }
            Some((start, end.min(total.saturating_sub(1))))
        });

    let (status, offset, length) = match range {
        Some((start, end)) => (StatusCode::PARTIAL_CONTENT, start, end - start + 1),
        None => (StatusCode::OK, 0, total),
    };

    let mut builder = Response::builder()
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, length)
        .header(
            header::CACHE_CONTROL,
            if state.media_requires_auth {
                "private, max-age=3600"
            } else {
                "public, max-age=31536000, immutable"
            },
        )
        .header(header::ETAG, format!("{:?}", total.to_string()))
        .status(status);
    if let Some((start, end)) = range {
        builder = builder.header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"));
    }

    if offset > 0 {
        use tokio::io::AsyncSeekExt;
        if handle
            .seek(std::io::SeekFrom::Start(offset))
            .await
            .is_err()
        {
            return Err(ApiError::internal("could not read the stored file"));
        }
    }

    let stream = tokio_util::io::ReaderStream::new(handle.take(length));
    builder
        .body(Body::from_stream(stream))
        .map_err(|error| ApiError::internal(format!("could not build response: {error}")))
}

/// `GET /media/link?key=<key>` — mint a short-lived signed URL for a file the
/// caller is already allowed to read.
///
/// Signing is what makes a link safe to hand to something that cannot send a
/// bearer token (an external player, a sharing sheet). It expires after
/// `NCHAT_MEDIA_TTL` seconds and only ever unlocks that one key.
pub async fn link(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let key = query.get("key").cloned().unwrap_or_default();
    if key.is_empty() {
        return Err(ApiError::bad_request("key is required"));
    }
    if safe_media_path(&state.media_dir, &key).is_none() {
        return Err(ApiError::not_found("Not found"));
    }
    if !access::can_read(&state, &user_id, &key)? {
        return Err(ApiError::not_found("Not found"));
    }

    let exp = now_secs() + state.media_link_ttl;
    let signature = sign_link(state.secret.as_str(), &key, exp);
    Ok(Json(json!({
        "key": key,
        "url": format!(
            "{}/media/file/{}?exp={}&sig={}",
            state.public_api_url, key, exp, signature
        ),
        "expires_at": exp,
        "expires_in": state.media_link_ttl,
    })))
}

/// `DELETE /media/file/*key` — uploader only.
pub async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(key): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

    let Some(path) = safe_media_path(&state.media_dir, &key) else {
        return Err(ApiError::not_found("Not found"));
    };

    let owned: Option<String> = state.db.with(|conn| {
        conn.query_row(
            "SELECT id FROM media_attachments WHERE file_path = ? AND uploader_id = ?",
            params![key, user_id],
            |row| row.get(0),
        )
        .optional()
    })?;
    if owned.is_none() {
        return Err(ApiError::not_found("Not found"));
    }

    let _ = std::fs::remove_file(path);
    state.db.with(|conn| {
        conn.execute(
            "DELETE FROM media_attachments WHERE file_path = ?",
            [&key],
        )?;
        Ok(())
    })?;
    Ok(Json(json!({ "success": true })))
}

/// Small helper so the range branch can set `Content-Length` without a second
/// header builder.
trait SetContentLength {
    fn set_content_length(self, length: usize) -> Self;
}

impl SetContentLength for Response {
    fn set_content_length(mut self, length: usize) -> Self {
        self.headers_mut().insert(
            header::CONTENT_LENGTH,
            HeaderValue::from_str(&length.to_string())
                .unwrap_or(HeaderValue::from_static("0")),
        );
        self
    }
}

/// Keeps `IntoResponse` in scope for the response type in tests.
#[allow(dead_code)]
fn _assert_into_response(response: Response) -> Response {
    response.into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_paths_cannot_escape_the_media_dir() {
        let dir = FsPath::new("/srv/nchat/media");

        // Nested keys are normal: `<user id>/<uuid>.<ext>`.
        assert!(safe_media_path(dir, "user-1/abc.png").is_some());
        assert!(safe_media_path(dir, "user-1/abc").is_some());

        // Everything that could walk out of the directory is refused.
        assert!(safe_media_path(dir, "../secret.txt").is_none());
        assert!(safe_media_path(dir, "user-1/../../etc/passwd").is_none());
        assert!(safe_media_path(dir, "/etc/passwd").is_none());
        assert!(safe_media_path(dir, "user-1/..").is_none());
        assert!(safe_media_path(dir, "").is_none());
        assert!(safe_media_path(dir, ".").is_none());
    }

    #[test]
    fn signatures_bind_the_key_and_the_expiry() {
        let secret = "unit-test-secret";
        let exp = now_secs() + 60;
        let signature = sign_link(secret, "user-1/abc.png", exp);

        assert!(verify_link(secret, "user-1/abc.png", exp, &signature));
        // Another key with the same signature must fail.
        assert!(!verify_link(secret, "user-1/other.png", exp, &signature));
        // Extending the expiry must fail.
        assert!(!verify_link(secret, "user-1/abc.png", exp + 60, &signature));
        // A different secret must fail.
        assert!(!verify_link("other", "user-1/abc.png", exp, &signature));
        // Truncated or empty signatures must fail without panicking.
        assert!(!verify_link(secret, "user-1/abc.png", exp, ""));
        assert!(!verify_link(secret, "user-1/abc.png", exp, &signature[..8]));
    }

    #[test]
    fn expired_links_are_rejected() {
        let secret = "unit-test-secret";
        let past = now_secs() - 1;
        let signature = sign_link(secret, "user-1/abc.png", past);
        assert!(!verify_link(secret, "user-1/abc.png", past, &signature));
    }

    #[test]
    fn resolved_path_stays_under_the_root() {
        let dir = FsPath::new("/srv/nchat/media");
        let path = safe_media_path(dir, "user-1/abc.png").expect("in-root key");
        assert!(path.starts_with(dir));
    }
}
