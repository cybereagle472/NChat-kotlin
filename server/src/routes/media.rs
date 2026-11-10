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
use crate::routes::limits;
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
    state.count_send(&user_id)?;

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
        // Both are stored and sent back; neither is content.
        if file_name.chars().count() > limits::SHORT || mime.chars().count() > limits::SHORT {
            return Err(ApiError::bad_request("file name or type is too long"));
        }

        // `${userId}/${newId()}${ext ? '.' + ext : ''}` — same key shape as R2.
        let extension = file_name.rsplit_once('.').map(|(_, ext)| ext.to_string());
        let key = match extension.filter(|ext| is_plain_extension(ext)) {
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

/// R2 accepts any extension, but here a key is a path on disk and the end of a URL.
/// The uploader's extension is kept only when it is plain letters and digits; with
/// anything else the file is stored without one.
///
/// It used to be enough that the extension could not leave the media directory. That
/// let through `?`, `#` and `%`, which mean something in a URL: a file called
/// `holiday.j?pg` was stored under a key that no request could ever name again.
fn is_plain_extension(extension: &str) -> bool {
    !extension.is_empty()
        && extension.len() <= 16
        && extension.chars().all(|c| c.is_ascii_alphanumeric())
}

/// The `Content-Type` a stored file is sent back with, and whether a browser may show
/// it in place.
///
/// The type was chosen by whoever uploaded the file. Sent back as it is, an upload
/// labelled `text/html` (or an SVG, which can carry script) would run as a page of this
/// server's own origin for anyone who opens a share link. So only the kinds a chat shows
/// in place keep their type — sound, video and plain pictures, none of which a browser
/// executes. Everything else is sent as a download.
fn served_type(stored: Option<&str>) -> (HeaderValue, bool) {
    const DOWNLOAD: &str = "application/octet-stream";
    let stored = stored.unwrap_or(DOWNLOAD).trim();
    let essence = stored
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    let shown_in_place = essence.starts_with("audio/")
        || essence.starts_with("video/")
        || matches!(
            essence.as_str(),
            "image/jpeg"
                | "image/jpg"
                | "image/png"
                | "image/gif"
                | "image/webp"
                | "image/bmp"
                | "image/avif"
                | "image/heic"
                | "image/heif"
        );
    if shown_in_place {
        if let Ok(value) = HeaderValue::from_str(stored) {
            return (value, true);
        }
    }
    (HeaderValue::from_static(DOWNLOAD), false)
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
/// A file is open to its uploader and to everybody who can see something it is attached
/// to: both ends of a 1:1 message, every member of a group it was sent to, the audience
/// of a status. Avatars are the documented exception — they are shown in search results
/// and member lists, so any signed-in user may load them.
///
/// A file can be attached in several places: a photo is forwarded from a chat into a
/// group, or posted as a status and also sent to one friend. Each place is asked in turn
/// and one "yes" is enough. (Until this was written each kind of place answered for all
/// of them: once a file appeared in any 1:1 message, only the two ends of such messages
/// could open it, and a group it was forwarded to — or had been posted to first — got
/// "not found".)
mod access {
    use super::*;
    use crate::privacy::status_visible_to;

    pub fn can_read(state: &AppState, user_id: &str, key: &str) -> ApiResult<bool> {
        // `Db::with` hands back a `rusqlite::Result`; `?` turns a failed query into a 500.
        Ok(state.db.with(|conn| {
            // Only a file this server has a record of, and only under the name it gave
            // it. The disk reads `a//b` as `a/b`, but every comparison below is on text:
            // if a second spelling were served, somebody could write that spelling into a
            // message of their own and be let in as that message's sender, past
            // `ensure_attachable`, which looks for the name as it was given.
            let uploader: Option<String> = conn
                .query_row(
                    "SELECT uploader_id FROM media_attachments WHERE file_path = ?",
                    [key],
                    |row| row.get(0),
                )
                .optional()?;
            let Some(uploader) = uploader else {
                return Ok(false);
            };
            if uploader == user_id {
                return Ok(true);
            }

            // ---- 1:1 messages ------------------------------------------------
            let in_a_chat: Option<i64> = conn
                .query_row(
                    "SELECT 1 FROM messages
                     WHERE (sender_id = ?2 OR receiver_id = ?2)
                       AND (instr(coalesce(media_url, ''), ?1) > 0
                            OR instr(coalesce(thumbnail_url, ''), ?1) > 0)
                     LIMIT 1",
                    params![key, user_id],
                    |row| row.get(0),
                )
                .optional()?;
            if in_a_chat.is_some() {
                return Ok(true);
            }

            // ---- group messages ----------------------------------------------
            let in_a_group: Option<i64> = conn
                .query_row(
                    "SELECT 1 FROM group_messages m
                     JOIN group_members g ON g.group_id = m.group_id AND g.user_id = ?2
                     WHERE instr(coalesce(m.media_url, ''), ?1) > 0
                        OR instr(coalesce(m.thumbnail_url, ''), ?1) > 0
                     LIMIT 1",
                    params![key, user_id],
                    |row| row.get(0),
                )
                .optional()?;
            if in_a_group.is_some() {
                return Ok(true);
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

/// Refuses to store a media address that names a file the caller cannot open.
///
/// Access to a file follows the messages, statuses and profiles that mention it (see
/// `access::can_read`). Without this check, mentioning a file was a way to get it:
/// anybody who knew a file's address — from a share link that has since expired, say —
/// could put it into a message of their own and be let in as that message's sender, or
/// make it their profile picture, which every signed-in account may load.
///
/// `can_read` looks for a file's key anywhere inside a stored address, so this looks for
/// every known key anywhere inside the new address: the same test from the other side.
/// An address that names no file of this server is none of its business.
///
/// Call it before the row is written, for every address the request wants stored.
pub fn ensure_attachable(
    state: &AppState,
    user_id: &str,
    addresses: &[Option<&str>],
) -> ApiResult<()> {
    // With public media there is nothing to protect: every file is open to everybody.
    if !state.media_requires_auth {
        return Ok(());
    }
    for address in addresses.iter().flatten() {
        if address.is_empty() {
            continue;
        }
        let keys: Vec<String> = state.db.with(|conn| {
            let mut statement = conn.prepare(
                "SELECT file_path FROM media_attachments
                 WHERE file_path != '' AND instr(?1, file_path) > 0",
            )?;
            let keys = statement
                .query_map([*address], |row| row.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(keys)
        })?;
        // One connection at a time: `can_read` takes its own.
        for key in keys {
            if !access::can_read(state, user_id, &key)? {
                return Err(ApiError::forbidden(
                    "You cannot attach a file you do not have access to",
                ));
            }
        }
    }
    Ok(())
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
    let (content_type, shown_in_place) = served_type(row.as_deref());

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
        .header(header::CONTENT_TYPE, content_type)
        // The type above is the whole answer: a browser must not guess a better one
        // from the bytes.
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
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
    if !shown_in_place {
        // Saved, not opened. The policy is for a browser that opens it anyway: no script,
        // no requests, no access to this origin.
        builder = builder
            .header(header::CONTENT_DISPOSITION, "attachment")
            .header(
                header::CONTENT_SECURITY_POLICY,
                "default-src 'none'; sandbox",
            );
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

    #[test]
    fn an_extension_is_kept_only_when_it_is_plain() {
        for kept in ["jpg", "JPG", "mp4", "webm", "m4a", "7z"] {
            assert!(is_plain_extension(kept), "{kept}");
        }
        for dropped in ["", "j?pg", "p#df", "%2e", "a/b", "a\\b", "..", "é", "tar gz"] {
            assert!(!is_plain_extension(dropped), "{dropped:?}");
        }
        assert!(is_plain_extension(&"x".repeat(16)));
        assert!(!is_plain_extension(&"x".repeat(17)));
    }

    #[test]
    fn only_kinds_a_browser_does_not_run_keep_their_type() {
        for stored in [
            "image/jpeg",
            "IMAGE/PNG",
            "image/webp",
            "audio/webm;codecs=opus",
            "audio/mp4",
            "video/mp4",
        ] {
            let (value, shown_in_place) = served_type(Some(stored));
            assert!(shown_in_place, "{stored}");
            assert_eq!(value.to_str().ok(), Some(stored), "{stored}");
        }
        for stored in [
            "text/html",
            "text/html; charset=utf-8",
            "application/xhtml+xml",
            "image/svg+xml",
            "IMAGE/SVG+XML; charset=utf-8",
            "application/pdf",
            "text/plain",
            "",
            // Looks like sound, cannot be a header.
            "audio/a\nb",
        ] {
            let (value, shown_in_place) = served_type(Some(stored));
            assert!(!shown_in_place, "{stored:?}");
            assert_eq!(
                value.to_str().ok(),
                Some("application/octet-stream"),
                "{stored:?}"
            );
        }
        let (value, shown_in_place) = served_type(None);
        assert!(!shown_in_place);
        assert_eq!(value.to_str().ok(), Some("application/octet-stream"));
    }
}
