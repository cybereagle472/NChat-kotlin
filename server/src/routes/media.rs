//! `/media/*` — port of `worker/src/routes/media.ts` (R2 → local files).
//!
//! Uploads stream a multipart part to `NCHAT_DATA_DIR/media/<user>/<id>.<ext>`,
//! record a `media_attachments` row and return the public URL — the same shape
//! the frontend expects. Reads support `Range` so audio/video scrubbing works.

use axum::body::Body;
use axum::extract::{Multipart, Path, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::Response;
use axum::Json;
use rusqlite::{params, OptionalExtension};
use tokio::io::AsyncReadExt;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::util::new_id;
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

/// Rejects keys that could escape the media directory.
///
/// `R2.get(key)` treats the key as an opaque name, but here it is a path on
/// disk — so `../`, absolute paths and backslashes must never reach `join`.
fn safe_media_path(media_dir: &std::path::Path, key: &str) -> Option<std::path::PathBuf> {
    if key.is_empty() || key.contains('\0') || key.contains('\\') {
        return None;
    }
    let candidate = std::path::Path::new(key);
    if candidate.is_absolute() {
        return None;
    }
    for component in candidate.components() {
        match component {
            std::path::Component::Normal(_) => {}
            // `.`, `..`, a root or a Windows prefix: refuse the whole request.
            _ => return None,
        }
    }
    let path = media_dir.join(candidate);
    path.starts_with(media_dir).then_some(path)
}

/// `GET /media/file/*key` — public read with byte-range support (HTTP 206).
///
/// The body is streamed from disk: a 200 MB video used to be read into memory
/// for every request, which is a one-line denial of service on a small VPS.
pub async fn file(
    State(state): State<AppState>,
    Path(key): Path<String>,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let Some(path) = safe_media_path(&state.media_dir, &key) else {
        return Err(ApiError::not_found("Not found"));
    };

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

    let mut builder = Response::builder()
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(
            header::CACHE_CONTROL,
            "public, max-age=31536000, immutable",
        )
        .header(header::ETAG, format!("\"{total}\"));

    let (status, offset, length) = match range {
        Some((start, end)) => {
            builder = builder.header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"));
            (StatusCode::PARTIAL_CONTENT, start, end - start + 1)
        }
        None => (StatusCode::OK, 0, total),
    };

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
        .status(status)
        .header(header::CONTENT_LENGTH, length)
        .body(Body::from_stream(stream))
        .map_err(|error| ApiError::internal(format!("could not build response: {error}")))
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    #[test]
    fn media_paths_cannot_escape_the_media_dir() {
        let dir = Path::new("/srv/nchat/media");

        // Nested keys are normal (`<user>/<id>.png`).
        assert!(safe_media_path(dir, "user-1/abc.png").is_some());
        assert!(safe_media_path(dir, "user-1/abc").is_some());

        // Everything that could walk out of the directory is refused.
        assert!(safe_media_path(dir, "../secret.txt").is_none());
        assert!(safe_media_path(dir, "user-1/../../etc/passwd").is_none());
        assert!(safe_media_path(dir, "/etc/passwd").is_none());
        assert!(safe_media_path(dir, "user-1\\..\\win").is_none());
        assert!(safe_media_path(dir, "").is_none());
        assert!(safe_media_path(dir, ".").is_none());
    }

    #[test]
    fn resolved_path_stays_under_the_root() {
        let dir = Path::new("/srv/nchat/media");
        let path = safe_media_path(dir, "user-1/abc.png").expect("in-root key");
        assert!(path.starts_with(dir));
    }
}
