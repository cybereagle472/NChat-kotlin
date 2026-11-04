//! `/media/*` — port of `worker/src/routes/media.ts` (R2 → local files).
//!
//! Uploads stream a multipart part to `NCHAT_DATA_DIR/media/<user>/<id>.<ext>`,
//! record a `media_attachments` row and return the public URL — the same shape
//! the frontend expects. Reads support `Range` so audio/video scrubbing works.

use axum::body::Body;
use axum::extract::{Multipart, Path, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use rusqlite::{params, OptionalExtension};
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

pub async fn upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let user_id = state.current_user(&headers)?;

    let mut upload: Option<(String, String, Vec<u8>)> = None; // name, mime, bytes
    while let Some(field) = multipart
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
        let bytes = field
            .bytes()
            .await
            .map_err(|error| ApiError::bad_request(format!("could not read the file: {error}")))?;
        upload = Some((file_name, mime, bytes.to_vec()));
        break;
    }

    let Some((file_name, mime, bytes)) = upload else {
        return Err(ApiError::bad_request(
            "file is required (multipart/form-data)",
        ));
    };

    let extension = file_name
        .rsplit_once('.')
        .map(|(_, ext)| ext.to_string())
        .filter(|ext| !ext.is_empty() && ext.len() <= 8);
    let key = match &extension {
        Some(ext) => format!("{user_id}/{}.{ext}", new_id()),
        None => format!("{user_id}/{}", new_id()),
    };

    let path = state.media_dir.join(&key);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, &bytes)?;

    let id = new_id();
    let media_type = media_type_from_mime(&mime);
    let size = bytes.len() as i64;

    state.db.with(|conn| {
        conn.execute(
            "INSERT INTO media_attachments
               (id, uploader_id, file_path, file_name, file_size, mime_type, media_type, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            params![
                id,
                user_id,
                key,
                file_name,
                size,
                mime,
                media_type,
                crate::util::now_iso()
            ],
        )?;
        Ok(())
    })?;

    let url = format!("{}/media/file/{}", state.public_api_url, key);
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "id": id,
            "url": url,
            "key": key,
            "media_type": media_type,
            "file_name": file_name,
            "file_size": size,
        })),
    ))
}

/// `GET /media/file/*key` — public read with byte-range support (HTTP 206).
pub async fn file(
    State(state): State<AppState>,
    Path(key): Path<String>,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let row: Option<String> = state.db.with(|conn| {
        conn.query_row(
            "SELECT mime_type FROM media_attachments WHERE file_path = ?",
            [&key],
            |row| row.get(0),
        )
        .optional()
    })?;

    let path = state.media_dir.join(&key);
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(_) => return Err(ApiError::not_found("Not found")),
    };

    let mime = row.unwrap_or_else(|| "application/octet-stream".to_string());
    let total = bytes.len();

    // `Range: bytes=start-end` → 206 with Content-Range, like R2 does.
    let range = headers
        .get(header::RANGE)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("bytes=").map(str::to_string))
        .and_then(|spec| {
            let (start, end) = spec.split_once('-')?;
            let start: usize = start.trim().parse().ok()?;
            let end: usize = match end.trim() {
                "" => total.saturating_sub(1),
                value => value.parse::<usize>().ok()?,
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
        );
    builder = builder.header(header::ETAG, HeaderValue::from_str(&format!("\"{total}\"")).unwrap_or(HeaderValue::from_static("\"0\"")));

    match range {
        Some((start, end)) => {
            let slice = bytes[start..=end].to_vec();
            let length = slice.len();
            builder
                .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"))
                .status(StatusCode::PARTIAL_CONTENT)
                .body(Body::from(slice))
                .map(|response| response.set_content_length(length))
                .map_err(|error| ApiError::internal(format!("could not build response: {error}")))
        }
        None => builder
            .status(StatusCode::OK)
            .body(Body::from(bytes))
            .map_err(|error| ApiError::internal(format!("could not build response: {error}"))),
    }
}

/// `DELETE /media/file/*key` — uploader only.
pub async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(key): Path<String>,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;

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

    let _ = std::fs::remove_file(state.media_dir.join(&key));
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
