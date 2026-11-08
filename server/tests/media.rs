//! `/media/*` end-to-end: upload → range read → ownership-gated delete.
//!
//! These mirror `worker/src/routes/media.ts` exactly: the R2 key shape
//! (`<userId>/<newId>.<ext>`), the 201 payload, `accept-ranges` + 206 on a
//! `Range` request (audio/video scrubbing depends on it) and uploader-only
//! deletion.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::db::Db;
use nchat_server::{build_app, AppState};
use serde_json::{json, Value};
use tower::ServiceExt;

struct TestApp {
    router: Router,
    dir: tempfile::TempDir,
}

/// Private media (the default): a read needs the caller's token or a signed link.
fn app() -> TestApp {
    build(false)
}

/// `NCHAT_MEDIA_PUBLIC=1`: the old "anyone with the URL" bucket behaviour.
fn public_app() -> TestApp {
    build(true)
}

fn build(public: bool) -> TestApp {
    let dir = tempfile::tempdir().expect("temp dir");
    let db = Db::open(dir.path().join("nchat.sqlite"), 2).expect("db");
    let state = AppState::new(db, "test-secret")
        .with_public_url("http://test.local")
        .with_media_dir(dir.path().join("media"))
        .with_public_media(public);
    TestApp {
        router: build_app(state),
        dir,
    }
}

impl TestApp {
    async fn raw(&self, request: Request<Body>) -> (StatusCode, Vec<String>, Vec<u8>) {
        let response = self
            .router
            .clone()
            .oneshot(request)
            .await
            .expect("response");
        let status = response.status();
        let headers = response
            .headers()
            .iter()
            .map(|(name, value)| {
                format!(
                    "{}: {}",
                    name.as_str().to_ascii_lowercase(),
                    value.to_str().unwrap_or_default()
                )
            })
            .collect();
        let bytes = response
            .into_body()
            .collect()
            .await
            .expect("body")
            .to_bytes()
            .to_vec();
        (status, headers, bytes)
    }

    /// `GET <uri>` with an optional bearer token, no body.
    async fn read(
        &self,
        uri: &str,
        token: Option<&str>,
    ) -> (StatusCode, Vec<String>, Vec<u8>) {
        let mut builder = Request::builder().uri(uri);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        self.raw(builder.body(Body::empty()).expect("request")).await
    }

    async fn call(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        let request = match body {
            Some(value) => builder
                .header("content-type", "application/json")
                .body(Body::from(value.to_string()))
                .expect("request"),
            None => builder.body(Body::empty()).expect("request"),
        };
        let (status, _, bytes) = self.raw(request).await;
        let value = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        (status, value)
    }

    async fn signup(&self, email: &str, name: &str) -> (String, String) {
        let (status, body) = self
            .call(
                "POST",
                "/auth/signup",
                None,
                Some(json!({ "email": email, "password": "secret1", "full_name": name })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "signup {email}: {body}");
        (
            body["access_token"].as_str().expect("token").to_string(),
            body["user"]["id"].as_str().expect("id").to_string(),
        )
    }

    /// `POST /media/upload` as `multipart/form-data` with a single `file` part.
    async fn upload(
        &self,
        token: &str,
        file_name: &str,
        mime: &str,
        bytes: &[u8],
    ) -> (StatusCode, Value) {
        let boundary = "----nchat-test-boundary";
        let mut body = Vec::new();
        body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        body.extend_from_slice(
            format!(
                "Content-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(format!("Content-Type: {mime}\r\n\r\n").as_bytes());
        body.extend_from_slice(bytes);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let request = Request::builder()
            .method("POST")
            .uri("/media/upload")
            .header("authorization", format!("Bearer {token}"))
            .header(
                "content-type",
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .expect("request");
        let (status, _, raw) = self.raw(request).await;
        let value = serde_json::from_slice(&raw).unwrap_or(Value::Null);
        (status, value)
    }

    fn path(&self, key: &str) -> std::path::PathBuf {
        self.dir.path().join("media").join(key)
    }
}

#[tokio::test]
async fn upload_store_range_read_and_owner_only_delete() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, _grace_id) = app.signup("grace@example.com", "Grace").await;

    // ---- upload ----------------------------------------------------------
    let payload = b"0123456789".to_vec();
    let (status, body) = app.upload(&ada_token, "voice-note.webm", "audio/webm", &payload).await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();
    let id = body["id"].as_str().expect("id").to_string();
    assert!(
        key.starts_with(&format!("{ada_id}/")) && key.ends_with(".webm"),
        "key shape {key}"
    );
    // The R2 key and the row id are two independent ids, like the reference.
    assert_ne!(key.rsplit('/').next().unwrap(), id);
    assert_eq!(body["media_type"], "audio");
    assert_eq!(body["file_name"], "voice-note.webm");
    assert_eq!(body["file_size"], payload.len());
    assert_eq!(
        body["url"],
        format!("http://test.local/media/file/{key}")
    );
    assert_eq!(std::fs::read(app.path(&key)).expect("stored bytes"), payload);

    // ---- missing file part ----------------------------------------------
    let request = Request::builder()
        .method("POST")
        .uri("/media/upload")
        .header("authorization", format!("Bearer {ada_token}"))
        .header("content-type", "multipart/form-data; boundary=x")
        .body(Body::from("--x--\r\n"))
        .expect("request");
    let (status, _, raw) = app.raw(request).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(
        serde_json::from_slice::<Value>(&raw).unwrap()["error"],
        "file is required (multipart/form-data)"
    );

    // ---- the uploader can read it back ----------------------------------
    let (status, headers, bytes) = app
        .read(&format!("/media/file/{key}"), Some(&ada_token))
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(bytes, payload);
    assert!(headers.iter().any(|h| h == "accept-ranges: bytes"));
    assert!(
        headers
            .iter()
            .any(|h| h == "cache-control: private, max-age=3600"),
        "private media must not be cacheable by a shared proxy: {headers:?}"
    );
    assert!(headers.iter().any(|h| h == "content-type: audio/webm"));

    // ---- no token at all → 401 ------------------------------------------
    let (status, _, _) = app.read(&format!("/media/file/{key}"), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    // ---- someone else → 404, so the response never confirms the key -----
    let (status, _, _) = app
        .read(&format!("/media/file/{key}"), Some(&grace_token))
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // ---- byte range → 206 + content-range -------------------------------
    let (status, headers, bytes) = app
        .raw(
            Request::builder()
                .uri(format!("/media/file/{key}"))
                .header("authorization", format!("Bearer {ada_token}"))
                .header("range", "bytes=2-5")
                .body(Body::empty())
                .expect("request"),
        )
        .await;
    assert_eq!(status, StatusCode::PARTIAL_CONTENT);
    assert_eq!(bytes, b"2345".to_vec());
    assert!(
        headers
            .iter()
            .any(|h| h == "content-range: bytes 2-5/10"),
        "headers: {headers:?}"
    );

    // ---- delete: not the uploader → 404 ---------------------------------
    let (status, body) = app
        .call("DELETE", &format!("/media/file/{key}"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND, "grace delete: {body}");
    assert!(app.path(&key).exists(), "file must survive a foreign delete");

    // ---- delete: uploader → removes object and row ----------------------
    let (status, body) = app
        .call("DELETE", &format!("/media/file/{key}"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK, "owner delete: {body}");
    assert_eq!(body["success"], true);
    assert!(!app.path(&key).exists(), "object must be gone");

    let (status, _, _) = app.read(&format!("/media/file/{key}"), Some(&ada_token)).await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // ---- unknown key and unsupported extension --------------------------
    let (status, body) = app.upload(&ada_token, "no-extension", "application/pdf", b"pdf").await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    assert_eq!(body["media_type"], "document");
    let key = body["key"].as_str().expect("key");
    assert!(key.starts_with(&format!("{ada_id}/")), "key shape {key}");
    assert!(!key.contains('.'), "extension-less name keeps no suffix: {key}");
}

/// Private media follows the conversation: the two people in a 1:1 chat can
/// load the photo, a third account cannot even confirm that it exists.
#[tokio::test]
async fn conversation_members_can_read_but_strangers_cannot() {
    let app = app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    let (bob_token, _bob_id) = app.signup("bob@example.com", "Bob").await;

    let payload = b"jpeg-bytes".to_vec();
    let (status, body) = app.upload(&ada_token, "photo.jpg", "image/jpeg", &payload).await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();
    let url = body["url"].as_str().expect("url").to_string();

    // Sending the message is what makes the file readable by the recipient.
    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&ada_token),
            Some(json!({
                "receiver_id": grace_id,
                "content": "",
                "type": "image",
                "media_url": url,
            })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "send: {body}");

    let (status, _, bytes) = app
        .read(&format!("/media/file/{key}"), Some(&grace_token))
        .await;
    assert_eq!(status, StatusCode::OK, "the recipient must be able to open it");
    assert_eq!(bytes, payload);

    let (status, _, _) = app
        .read(&format!("/media/file/{key}"), Some(&bob_token))
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND, "an outsider must learn nothing");
}

/// `/media/link` mints a URL that works without a token, expires, and cannot be
/// redirected at another file.
#[tokio::test]
async fn signed_links_are_key_bound_and_expire() {
    let app = app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;
    let (status, body) = app.upload(&ada_token, "clip.mp4", "video/mp4", b"video").await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();

    // A stranger cannot mint a link for a file they cannot read.
    let (status, _) = app
        .call("GET", &format!("/media/link?key={key}"), None, None)
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let (status, body) = app
        .call("GET", &format!("/media/link?key={key}"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK, "link: {body}");
    let url = body["url"].as_str().expect("url").to_string();
    let expires_in = body["expires_in"].as_i64().expect("expires_in");
    assert!(expires_in > 0);
    assert!(url.contains("exp=") && url.contains("sig="), "url: {url}");

    // The signed URL needs no bearer token.
    let path = url.trim_start_matches("http://test.local").to_string();
    let (status, _, bytes) = app.read(&path, None).await;
    assert_eq!(status, StatusCode::OK, "signed read: {url}");
    assert_eq!(bytes, b"video".to_vec());

    // Pushing the expiry forward invalidates the signature.
    let exp = body["expires_at"].as_i64().expect("expires_at");
    let tampered = path.replace(&format!("exp={exp}"), &format!("exp={}", exp + 3600));
    let (status, _, _) = app.read(&tampered, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "tampered link: {tampered}");
}

/// `NCHAT_MEDIA_PUBLIC=1` restores the reference's public bucket behaviour.
#[tokio::test]
async fn public_mode_still_serves_anonymous_reads() {
    let app = public_app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;

    let (status, body) = app.upload(&ada_token, "voice.webm", "audio/webm", b"abc").await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();

    let (status, headers, bytes) = app.read(&format!("/media/file/{key}"), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(bytes, b"abc".to_vec());
    assert!(headers
        .iter()
        .any(|h| h == "cache-control: public, max-age=31536000, immutable"));
}

#[tokio::test]
async fn upload_larger_than_axums_default_body_limit() {
    let app = app();
    let (token, _) = app.signup("large@example.com", "Large").await;
    let payload = vec![42u8; 3 * 1024 * 1024];
    let (status, body) = app.upload(&token, "large.bin", "application/octet-stream", &payload).await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
}
