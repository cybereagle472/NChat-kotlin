//! The rest of Phase 1 on this server (agent.md §33): what was left of item 7,
//! other people's e-mail addresses, and media that is shared twice (§19.3 #23).
//!
//! Each test asks one question and reads the answer back through the API. The
//! cast is the one `authz.rs` uses:
//!
//! - **Ada** owns the thing under test;
//! - **Grace** is legitimately involved (a friend, a member);
//! - **Heidi**, **Ivan** and **Trent** are further honest accounts, used where a
//!   third and a fourth party are needed;
//! - **Mallory** is signed in and has no business with any of it.
//!
//! Two things a request says about where it comes from are kept apart
//! throughout: the *peer* is the address the server's own socket sees, and
//! `X-Forwarded-For` is a header, which is text that the sender writes.

use std::net::SocketAddr;
use std::sync::Once;
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{HeaderMap, Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::db::Db;
use nchat_server::ratelimit::RateLimiter;
use nchat_server::{build_app, AppState};
use serde_json::{json, Value};
use tokio::sync::mpsc;
use tower::ServiceExt;

const SECRET: &str = "test-secret";
const PASSWORD: &str = "secret1";
/// The address `NCHAT_ADMIN_EMAIL` names in this test process.
const ADMIN_ADDRESS: &str = "boss@test.local";

struct TestApp {
    router: Router,
    /// Shares the hub, the database and the limiters with `router`.
    state: AppState,
    _dir: tempfile::TempDir,
}

struct User {
    token: String,
    id: String,
}

struct Reply {
    status: StatusCode,
    headers: HeaderMap,
    body: Value,
}

impl Reply {
    fn header(&self, name: &str) -> Option<String> {
        self.headers
            .get(name)
            .and_then(|value| value.to_str().ok())
            .map(str::to_string)
    }
}

/// What a request says about itself besides its method, path, token and body.
#[derive(Clone, Copy, Default)]
struct Extra<'a> {
    /// Where the connection comes from: what the server's socket sees.
    peer: Option<&'a str>,
    /// `X-Forwarded-For`: what the request, or a proxy in front of the server, claims.
    forwarded: Option<&'a str>,
    /// `Origin`: the website a browser is calling from.
    origin: Option<&'a str>,
    /// `Access-Control-Request-Method`: turns an `OPTIONS` request into a CORS preflight.
    preflight: Option<&'a str>,
}

/// One signed-in device: everything the server pushes to `user:<id>`.
struct Inbox {
    rx: mpsc::Receiver<String>,
}

impl Inbox {
    /// Every event queued so far. Handlers publish before they answer, so once a
    /// request has been awaited nothing is still in flight.
    fn drain(&mut self) -> Vec<Value> {
        let mut events = Vec::new();
        while let Ok(text) = self.rx.try_recv() {
            events.push(serde_json::from_str(&text).unwrap_or(Value::Null));
        }
        events
    }

    /// Just the `type` of each queued event, in arrival order.
    fn kinds(&mut self) -> Vec<String> {
        let events = self.drain();
        let mut kinds = Vec::new();
        for event in &events {
            kinds.push(event["type"].as_str().unwrap_or_default().to_string());
        }
        kinds
    }
}

fn app() -> TestApp {
    app_with(|state| state)
}

/// Every server in this file runs in a process whose environment names an
/// administrator address, the way a deployment's does. The variable is written
/// once, before the first server is built. Every test begins by building a
/// server, so no thread is reading the environment while it is written.
fn name_an_admin_address() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| std::env::set_var("NCHAT_ADMIN_EMAIL", ADMIN_ADDRESS));
}

/// A server with its settings changed by `configure`. Passwords are hashed with few
/// rounds: these tests count requests, and only one of them measures hashing.
fn app_with(configure: impl FnOnce(AppState) -> AppState) -> TestApp {
    name_an_admin_address();
    let dir = tempfile::tempdir().expect("temp dir");
    let db = Db::open(dir.path().join("nchat.sqlite"), 2).expect("db");
    let state = configure(
        AppState::new(db, SECRET)
            .with_public_url("http://test.local")
            .with_media_dir(dir.path().join("media"))
            .with_password_iterations(1_000),
    );
    TestApp {
        router: build_app(state.clone()),
        state,
        _dir: dir,
    }
}

/// `true` when `key` appears anywhere inside `value`.
fn mentions_key(value: &Value, key: &str) -> bool {
    match value {
        Value::Object(map) => map
            .iter()
            .any(|(name, inner)| name.as_str() == key || mentions_key(inner, key)),
        Value::Array(items) => items.iter().any(|inner| mentions_key(inner, key)),
        _ => false,
    }
}

/// A text of `count` characters.
fn long(count: usize) -> String {
    "x".repeat(count)
}

impl TestApp {
    async fn request(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
        extra: Extra<'_>,
    ) -> Reply {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        if let Some(address) = extra.peer {
            let address: SocketAddr = address.parse().expect("peer address");
            builder = builder.extension(ConnectInfo(address));
        }
        if let Some(forwarded) = extra.forwarded {
            builder = builder.header("x-forwarded-for", forwarded);
        }
        if let Some(origin) = extra.origin {
            builder = builder.header("origin", origin);
        }
        if let Some(wanted) = extra.preflight {
            builder = builder
                .header("access-control-request-method", wanted)
                .header("access-control-request-headers", "authorization,content-type");
        }
        let request = match body {
            Some(value) => builder
                .header("content-type", "application/json")
                .body(Body::from(value.to_string()))
                .expect("request"),
            None => builder.body(Body::empty()).expect("request"),
        };
        self.finish(request).await
    }

    async fn finish(&self, request: Request<Body>) -> Reply {
        let response = self.router.clone().oneshot(request).await.expect("response");
        let status = response.status();
        let headers = response.headers().clone();
        let bytes = response
            .into_body()
            .collect()
            .await
            .expect("body")
            .to_bytes();
        let body = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        Reply {
            status,
            headers,
            body,
        }
    }

    async fn call(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let reply = self
            .request(method, uri, token, body, Extra::default())
            .await;
        (reply.status, reply.body)
    }

    async fn signup(&self, email: &str, name: &str) -> User {
        let (status, body) = self
            .call(
                "POST",
                "/auth/signup",
                None,
                Some(json!({ "email": email, "password": PASSWORD, "full_name": name })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "signup {email}: {body}");
        User {
            token: body["access_token"].as_str().expect("token").to_string(),
            id: body["user"]["id"].as_str().expect("id").to_string(),
        }
    }

    /// The answer to one sign-up attempt.
    async fn try_signup(&self, email: &str, extra: Extra<'_>) -> StatusCode {
        self.request(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": email, "password": PASSWORD, "full_name": "Somebody" })),
            extra,
        )
        .await
        .status
    }

    /// The answer to one sign-in attempt.
    async fn try_login(&self, email: &str, password: &str, extra: Extra<'_>) -> StatusCode {
        self.request(
            "POST",
            "/auth/login",
            None,
            Some(json!({ "email": email, "password": password })),
            extra,
        )
        .await
        .status
    }

    async fn befriend(&self, sender: &User, receiver: &User) {
        let (status, body) = self
            .call(
                "POST",
                "/friends/request",
                Some(&sender.token),
                Some(json!({ "receiver_id": receiver.id })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "friend request: {body}");
        let request_id = body["id"].as_str().expect("request id").to_string();
        let (status, body) = self
            .call(
                "POST",
                &format!("/friends/{request_id}/respond"),
                Some(&receiver.token),
                Some(json!({ "accept": true })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "accept: {body}");
    }

    async fn block(&self, blocker: &User, target: &User) {
        let (status, body) = self
            .call(
                "POST",
                "/friends/block",
                Some(&blocker.token),
                Some(json!({ "user_id": target.id })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "block: {body}");
    }

    /// Subscribes to a user's personal realtime room, exactly like their device.
    fn listen(&self, user: &User) -> Inbox {
        let (_socket, rx) = self.state.hub.join(&format!("user:{}", user.id));
        Inbox { rx }
    }

    async fn create_group(&self, admin: &User, members: &[&User]) -> String {
        let mut ids: Vec<&str> = Vec::new();
        for member in members {
            ids.push(member.id.as_str());
        }
        let (status, body) = self
            .call(
                "POST",
                "/groups",
                Some(&admin.token),
                Some(json!({ "name": "Squad", "memberIds": ids })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "create group: {body}");
        body["group"]["id"].as_str().expect("group id").to_string()
    }

    /// `POST /media/upload` with a single `file` part: the status and the JSON answer.
    async fn try_upload(&self, owner: &User, file_name: &str, mime: &str) -> (StatusCode, Value) {
        let boundary = "----nchat-phase1-boundary";
        let mut body = Vec::new();
        body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        body.extend_from_slice(
            format!(
                "Content-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(format!("Content-Type: {mime}\r\n\r\n").as_bytes());
        body.extend_from_slice(b"file-bytes");
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let request = Request::builder()
            .method("POST")
            .uri("/media/upload")
            .header("authorization", format!("Bearer {}", owner.token))
            .header(
                "content-type",
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .expect("request");
        let reply = self.finish(request).await;
        (reply.status, reply.body)
    }

    /// Uploads a picture and returns `(key, url)`.
    async fn upload(&self, owner: &User, file_name: &str) -> (String, String) {
        let (status, value) = self.try_upload(owner, file_name, "image/jpeg").await;
        assert_eq!(status, StatusCode::CREATED, "upload: {value}");
        (
            value["key"].as_str().expect("key").to_string(),
            value["url"].as_str().expect("url").to_string(),
        )
    }

    /// Status code of `GET /media/file/<key>` for this caller.
    async fn open_media(&self, key: &str, viewer: &User) -> StatusCode {
        self.request(
            "GET",
            &format!("/media/file/{key}"),
            Some(&viewer.token),
            None,
            Extra::default(),
        )
        .await
        .status
    }

    /// Sends a picture in a 1:1 chat; the answer's status.
    async fn send_picture(&self, sender: &User, receiver: &User, url: &str) -> StatusCode {
        let (status, _) = self
            .call(
                "POST",
                "/messages",
                Some(&sender.token),
                Some(json!({
                    "receiver_id": receiver.id,
                    "content": "",
                    "type": "image",
                    "media_url": url,
                })),
            )
            .await;
        status
    }

    /// Sends a picture to a group; the answer's status.
    async fn send_group_picture(&self, sender: &User, group_id: &str, url: &str) -> StatusCode {
        let (status, _) = self
            .call(
                "POST",
                &format!("/groups/{group_id}/messages"),
                Some(&sender.token),
                Some(json!({ "content": "", "type": "image", "media_url": url })),
            )
            .await;
        status
    }

    /// Status code of `GET /admin/stats` for this caller.
    async fn admin_stats(&self, user: &User) -> StatusCode {
        let (status, _) = self
            .call("GET", "/admin/stats", Some(&user.token), None)
            .await;
        status
    }
}

// -------------------------------------------------- the address a request comes from

/// Forty sign-ups over one connection address, each with a new e-mail address and
/// each claiming, in a header, to come from somewhere else. Every sign-up costs the
/// server a password hash and a row. The header is the sender's own text, so the
/// count has to follow the address the socket sees.
#[tokio::test]
async fn a_claimed_address_does_not_buy_more_sign_ups() {
    let app = app();

    let mut answers = Vec::new();
    for attempt in 0..40 {
        let email = format!("bot-{attempt}@test.local");
        let claimed = format!("198.18.0.{attempt}");
        let extra = Extra {
            peer: Some("203.0.113.9:40000"),
            forwarded: Some(&claimed),
            ..Extra::default()
        };
        answers.push(app.try_signup(&email, extra).await);
    }

    let created = answers
        .iter()
        .filter(|status| **status == StatusCode::CREATED)
        .count();
    assert!(
        answers[30..]
            .iter()
            .all(|status| *status == StatusCode::TOO_MANY_REQUESTS),
        "{created} of 40 sign-ups from one address were accepted; the last ten answers were {:?}",
        &answers[30..]
    );

    // The limit belongs to that address. Somebody elsewhere can still sign up.
    let elsewhere = Extra {
        peer: Some("198.51.100.4:40000"),
        ..Extra::default()
    };
    assert_eq!(
        app.try_signup("ada@test.local", elsewhere).await,
        StatusCode::CREATED
    );
}

/// The same for sign-in, with a different account named in every attempt. The
/// existing limits are per account, so they never notice one address working its
/// way through a list of e-mail addresses.
#[tokio::test]
async fn sign_in_attempts_from_one_address_run_out_across_accounts() {
    let app = app();
    let here = Extra {
        peer: Some("203.0.113.9:40000"),
        ..Extra::default()
    };

    let mut answers = Vec::new();
    for attempt in 0..40 {
        let email = format!("person-{attempt}@test.local");
        answers.push(app.try_login(&email, "a-guess", here).await);
    }

    let checked = answers
        .iter()
        .filter(|status| **status == StatusCode::UNAUTHORIZED)
        .count();
    assert!(
        answers[30..]
            .iter()
            .all(|status| *status == StatusCode::TOO_MANY_REQUESTS),
        "{checked} of 40 sign-in attempts from one address were checked; the last ten answers were {:?}",
        &answers[30..]
    );

    let elsewhere = Extra {
        peer: Some("198.51.100.4:40000"),
        ..Extra::default()
    };
    assert_eq!(
        app.try_login("person-0@test.local", "a-guess", elsewhere).await,
        StatusCode::UNAUTHORIZED,
        "another address must still be answered"
    );
}

/// Behind a reverse proxy every connection comes from the proxy, and the proxy
/// appends the address it saw to `X-Forwarded-For`. With one trusted proxy the
/// last entry is the client; anything in front of it is the client's own text.
#[tokio::test]
async fn behind_a_trusted_proxy_the_address_the_proxy_saw_is_what_counts() {
    let app = app_with(|state| state.with_trusted_proxies(1));
    let proxy = "10.0.0.1:55000";

    let first_client = Extra {
        peer: Some(proxy),
        forwarded: Some("1.2.3.4, 203.0.113.5"),
        ..Extra::default()
    };
    let mut answers = Vec::new();
    for attempt in 0..31 {
        let email = format!("person-{attempt}@test.local");
        answers.push(app.try_login(&email, "a-guess", first_client).await);
    }
    assert_eq!(
        answers[30],
        StatusCode::TOO_MANY_REQUESTS,
        "the 31st attempt of one client behind the proxy"
    );

    // Writing a different story in front of the proxy's entry changes nothing.
    let disguised = Extra {
        peer: Some(proxy),
        forwarded: Some("9.9.9.9, 203.0.113.5"),
        ..Extra::default()
    };
    assert_eq!(
        app.try_login("somebody-else@test.local", "a-guess", disguised)
            .await,
        StatusCode::TOO_MANY_REQUESTS,
        "the same client with another invented first hop"
    );

    // Another client behind the same proxy is another client.
    let second_client = Extra {
        peer: Some(proxy),
        forwarded: Some("203.0.113.6"),
        ..Extra::default()
    };
    assert_eq!(
        app.try_login("person-0@test.local", "a-guess", second_client)
            .await,
        StatusCode::UNAUTHORIZED,
        "a second client behind the same proxy"
    );
}

/// "Invalid email or password" is one message for two cases, but only one of them
/// used to cost a password hash, so the clock told them apart. This server is
/// built with full-strength hashing so that the work is long enough to measure;
/// the smallest of three samples is compared, with a wide margin.
#[tokio::test]
async fn an_unknown_account_is_refused_no_faster_than_a_wrong_password() {
    let app = app_with(|state| state.with_password_iterations(100_000));
    app.signup("ada@test.local", "Ada").await;

    let mut known = Duration::MAX;
    let mut unknown = Duration::MAX;
    for _ in 0..3 {
        let started = Instant::now();
        let status = app
            .try_login("ada@test.local", "not-the-password", Extra::default())
            .await;
        known = known.min(started.elapsed());
        assert_eq!(status, StatusCode::UNAUTHORIZED);

        let started = Instant::now();
        let status = app
            .try_login("nobody@test.local", "not-the-password", Extra::default())
            .await;
        unknown = unknown.min(started.elapsed());
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    assert!(
        unknown * 4 >= known,
        "a wrong password took {known:?}, an unknown account took {unknown:?}"
    );
}

// ------------------------------------------------------------ limits beyond sign-in

/// One account, a budget of three sends. The fourth is refused, stored nowhere and
/// pushed to nobody; the other ways of sending draw on the same budget; and the
/// limit belongs to the account, not to the server.
#[tokio::test]
async fn sending_runs_out_per_account() {
    let app = app_with(|state| {
        state.with_send_limiter(RateLimiter::new(3, Duration::from_secs(60)))
    });
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    app.befriend(&ada, &grace).await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let mut inbox = app.listen(&grace);

    for number in 0..3 {
        let (status, body) = app
            .call(
                "POST",
                "/messages",
                Some(&ada.token),
                Some(json!({ "receiver_id": grace.id, "content": format!("message {number}") })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "message {number}: {body}");
    }
    let (status, _) = app
        .call(
            "POST",
            "/messages",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "content": "one too many" })),
        )
        .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "the fourth message");

    let (status, body) = app
        .call(
            "GET",
            &format!("/messages/with/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "history: {body}");
    assert_eq!(
        body["messages"].as_array().map(Vec::len),
        Some(3),
        "the refused message was stored: {body}"
    );
    let pushed = inbox
        .kinds()
        .iter()
        .filter(|kind| kind.as_str() == "message:new")
        .count();
    assert_eq!(pushed, 3, "the refused message was pushed");

    let (status, _) = app
        .call(
            "POST",
            &format!("/groups/{group_id}/messages"),
            Some(&ada.token),
            Some(json!({ "content": "to the group" })),
        )
        .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "a group message");
    let (status, _) = app
        .call(
            "POST",
            "/status",
            Some(&ada.token),
            Some(json!({ "content_type": "text", "text_content": "hello" })),
        )
        .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "a status");
    let (status, _) = app.try_upload(&ada, "photo.jpg", "image/jpeg").await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "an upload");

    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&grace.token),
            Some(json!({ "receiver_id": ada.id, "content": "still here" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "another account: {body}");
}

/// The requests that reach people who did not ask for them: friend requests, new
/// groups, added members and calls. A budget of two.
#[tokio::test]
async fn contacting_people_runs_out_per_account() {
    let app = app_with(|state| {
        state.with_contact_limiter(RateLimiter::new(2, Duration::from_secs(300)))
    });
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let heidi = app.signup("heidi@test.local", "Heidi").await;

    let group_id = app.create_group(&mallory, &[]).await;
    let (status, body) = app
        .call(
            "POST",
            "/friends/request",
            Some(&mallory.token),
            Some(json!({ "receiver_id": ada.id })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "the second action: {body}");

    let mut inbox = app.listen(&grace);
    let attempts: Vec<(&str, String, Value)> = vec![
        (
            "a friend request",
            "/friends/request".to_string(),
            json!({ "receiver_id": grace.id }),
        ),
        (
            "a new group",
            "/groups".to_string(),
            json!({ "name": "Everybody", "memberIds": [grace.id.as_str()] }),
        ),
        (
            "a new member",
            format!("/groups/{group_id}/members"),
            json!({ "memberIds": [grace.id.as_str()] }),
        ),
        (
            "a call",
            "/calls".to_string(),
            json!({ "receiver_id": grace.id, "call_type": "audio" }),
        ),
        (
            "a group call",
            "/group-calls".to_string(),
            json!({ "group_id": group_id, "call_type": "audio" }),
        ),
    ];
    for (what, uri, body) in attempts {
        let (status, _) = app
            .call("POST", &uri, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{what} over the budget");
    }
    let kinds = inbox.kinds();
    assert!(kinds.is_empty(), "the refused requests still reached Grace: {kinds:?}");

    let (status, body) = app
        .call(
            "POST",
            "/friends/request",
            Some(&grace.token),
            Some(json!({ "receiver_id": heidi.id })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "another account: {body}");
}

// ----------------------------------------------------------------------------- CORS

const ALLOW_ORIGIN: &str = "access-control-allow-origin";

/// A web page on some other site, in the browser of somebody who can reach this
/// server. Without the header a browser does not hand the answer to the page, and
/// does not send a request that needs a preflight at all. The Android app is not
/// a browser and sends no `Origin`.
#[tokio::test]
async fn pages_on_other_sites_get_no_permission_by_default() {
    let app = app();
    let page = Extra {
        origin: Some("https://elsewhere.example"),
        ..Extra::default()
    };

    let reply = app.request("GET", "/health", None, None, page).await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.header(ALLOW_ORIGIN), None, "a plain read");

    let preflight = Extra {
        origin: Some("https://elsewhere.example"),
        preflight: Some("POST"),
        ..Extra::default()
    };
    let reply = app
        .request("OPTIONS", "/auth/login", None, None, preflight)
        .await;
    assert_eq!(reply.header(ALLOW_ORIGIN), None, "a preflight");
}

/// The operator names the websites that may call the API. Those get permission,
/// named exactly; a look-alike does not.
#[tokio::test]
async fn listed_web_origins_are_allowed_and_only_those() {
    let app =
        app_with(|state| state.with_cors_origins(vec!["https://chat.example".to_string()]));

    let ours = Extra {
        origin: Some("https://chat.example"),
        ..Extra::default()
    };
    let reply = app.request("GET", "/health", None, None, ours).await;
    assert_eq!(
        reply.header(ALLOW_ORIGIN).as_deref(),
        Some("https://chat.example"),
        "the listed origin"
    );

    let ours_preflight = Extra {
        origin: Some("https://chat.example"),
        preflight: Some("POST"),
        ..Extra::default()
    };
    let reply = app
        .request("OPTIONS", "/messages", None, None, ours_preflight)
        .await;
    assert_eq!(
        reply.header(ALLOW_ORIGIN).as_deref(),
        Some("https://chat.example"),
        "the listed origin's preflight"
    );
    // `*` does not cover `Authorization` in every browser; it has to be named.
    let allowed_headers = reply
        .header("access-control-allow-headers")
        .unwrap_or_default()
        .to_lowercase();
    assert!(
        allowed_headers.contains("authorization"),
        "the preflight allows these request headers: {allowed_headers:?}"
    );

    for origin in [
        "https://elsewhere.example",
        "https://chat.example.elsewhere.example",
        "http://chat.example",
    ] {
        let theirs = Extra {
            origin: Some(origin),
            ..Extra::default()
        };
        let reply = app.request("GET", "/health", None, None, theirs).await;
        assert_eq!(reply.header(ALLOW_ORIGIN), None, "{origin}");
    }
}

/// `*` is the way back to "any website", for an operator who wants that.
#[tokio::test]
async fn a_star_in_the_list_allows_every_origin() {
    let app = app_with(|state| state.with_cors_origins(vec!["*".to_string()]));
    let page = Extra {
        origin: Some("https://elsewhere.example"),
        ..Extra::default()
    };
    let reply = app.request("GET", "/health", None, None, page).await;
    assert_eq!(reply.header(ALLOW_ORIGIN).as_deref(), Some("*"));
}

// ---------------------------------------------------------------------------- admin

/// An administrator is an account that has the role. Nothing else opens the route.
#[tokio::test]
async fn the_admin_role_is_what_opens_admin_stats() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    app.state
        .db
        .with(|conn| conn.execute("UPDATE users SET is_admin = 1 WHERE id = ?", [&ada.id]))
        .expect("grant the role");

    let (status, body) = app
        .call("GET", "/admin/stats", Some(&ada.token), None)
        .await;
    assert_eq!(status, StatusCode::OK, "the account with the role: {body}");
    assert_eq!(body["totalUsers"], 2, "{body}");

    let (status, body) = app
        .call("GET", "/admin/stats", Some(&mallory.token), None)
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "an ordinary account: {body}");
    assert!(body.get("totalUsers").is_none(), "{body}");

    // An account is told about its own role.
    let (status, body) = app.call("GET", "/auth/me", Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["user"]["is_admin"], true, "{body}");
}

/// `NCHAT_ADMIN_EMAIL` names an address. Nothing proves that an address belongs to
/// whoever registers it, so an account made *after* the setting was read must not
/// become the administrator by being made.
#[tokio::test]
async fn registering_the_configured_admin_address_does_not_make_an_admin() {
    let app = app();
    // What start-up did with the setting: nobody had the address yet.
    let granted = app
        .state
        .apply_admin_email(ADMIN_ADDRESS)
        .expect("apply the setting");
    assert!(!granted, "no account has that address yet");

    let mallory = app.signup(ADMIN_ADDRESS, "Mallory").await;
    assert_eq!(
        app.admin_stats(&mallory).await,
        StatusCode::FORBIDDEN,
        "whoever registered the configured address became the administrator"
    );
}

/// What start-up does with the setting: the account that has the address gets the
/// role, and it is the only one that has it.
#[tokio::test]
async fn the_configured_admin_address_grants_the_role_when_it_is_applied() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;

    let granted = app
        .state
        .apply_admin_email("  Ada@Test.Local ")
        .expect("apply the setting");
    assert!(granted, "an account with that address exists");
    assert_eq!(app.admin_stats(&ada).await, StatusCode::OK);
    assert_eq!(app.admin_stats(&grace).await, StatusCode::FORBIDDEN);

    // Naming somebody else moves the role instead of adding a second holder.
    let granted = app
        .state
        .apply_admin_email("grace@test.local")
        .expect("apply the setting");
    assert!(granted);
    assert_eq!(app.admin_stats(&ada).await, StatusCode::FORBIDDEN);
    assert_eq!(app.admin_stats(&grace).await, StatusCode::OK);

    // An address without an account leaves nobody in charge.
    let granted = app
        .state
        .apply_admin_email("nobody@test.local")
        .expect("apply the setting");
    assert!(!granted);
    assert_eq!(app.admin_stats(&grace).await, StatusCode::FORBIDDEN);
}

// ------------------------------------------------------------------ e-mail addresses

/// Every answer and every pushed event in which one account sees another. People
/// are found by name and username; the address they signed up with is theirs.
#[tokio::test]
async fn other_peoples_email_addresses_are_not_served() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let heidi = app.signup("heidi@test.local", "Heidi").await;
    let trent = app.signup("trent@test.local", "Trent").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    app.befriend(&ada, &grace).await;
    app.block(&grace, &trent).await;
    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "content": "hello" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "message: {body}");

    let mut seen: Vec<(String, Value)> = Vec::new();

    // Heidi asks Grace, and the request stays open.
    let mut inbox = app.listen(&grace);
    let (status, body) = app
        .call(
            "POST",
            "/friends/request",
            Some(&heidi.token),
            Some(json!({ "receiver_id": grace.id })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "friend request: {body}");
    seen.push(("the answer to a friend request".to_string(), body));
    let events = inbox.drain();
    assert!(!events.is_empty(), "Grace was told about the request");
    for event in events {
        seen.push(("an event pushed to Grace".to_string(), event));
    }

    let reads: Vec<(String, &User)> = vec![
        ("/users".to_string(), &mallory),
        ("/users/search?q=a".to_string(), &mallory),
        (format!("/users/{}", ada.id), &mallory),
        (format!("/users/{}", ada.id), &grace),
        ("/friends".to_string(), &grace),
        ("/friends/requests".to_string(), &grace),
        ("/friends/sent".to_string(), &heidi),
        ("/friends/blocked".to_string(), &grace),
        ("/messages/chats".to_string(), &grace),
    ];
    for (uri, reader) in &reads {
        let (status, body) = app.call("GET", uri, Some(&reader.token), None).await;
        assert_eq!(status, StatusCode::OK, "GET {uri}: {body}");
        seen.push((format!("GET {uri}"), body));
    }

    for (place, body) in &seen {
        let text = body.to_string();
        assert!(
            !mentions_key(body, "email") && !text.contains("@test.local"),
            "{place} shows an e-mail address: {text}"
        );
        assert!(
            !mentions_key(body, "is_admin"),
            "{place} says whether somebody is an administrator: {text}"
        );
    }
}

/// The other half: every way an account reads *itself* still has the address. The
/// Android client stores it from these answers.
#[tokio::test]
async fn everyone_still_gets_their_own_email_address() {
    let app = app();
    let (status, body) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": "ada@test.local", "password": PASSWORD, "full_name": "Ada" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "signup: {body}");
    assert_eq!(body["user"]["email"], "ada@test.local", "signup: {body}");
    let token = body["access_token"].as_str().expect("token").to_string();
    let id = body["user"]["id"].as_str().expect("id").to_string();

    let (status, body) = app
        .call(
            "POST",
            "/auth/login",
            None,
            Some(json!({ "email": "ada@test.local", "password": PASSWORD })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "login: {body}");
    assert_eq!(body["user"]["email"], "ada@test.local", "login: {body}");

    let (_, body) = app.call("GET", "/auth/me", Some(&token), None).await;
    assert_eq!(body["user"]["email"], "ada@test.local", "/auth/me: {body}");

    let (_, body) = app.call("GET", "/users/me", Some(&token), None).await;
    assert_eq!(body["profile"]["email"], "ada@test.local", "/users/me: {body}");

    let (status, body) = app
        .call(
            "PATCH",
            "/users/me",
            Some(&token),
            Some(json!({ "bio": "hello" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "PATCH /users/me: {body}");
    assert_eq!(body["profile"]["email"], "ada@test.local", "PATCH /users/me: {body}");

    let (_, body) = app
        .call("GET", &format!("/users/{id}"), Some(&token), None)
        .await;
    assert_eq!(body["profile"]["email"], "ada@test.local", "/users/:own id: {body}");
}

// ---------------------------------------------------------------- media shared twice

/// §19.3 #23. Ada sends Grace a photo. Grace forwards it into a group that Ada is
/// not in. Forwarding sends the same address again, and the group has to be able
/// to open what was posted to it. Somebody outside still cannot.
#[tokio::test]
async fn a_photo_forwarded_from_a_private_chat_opens_for_the_group() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let heidi = app.signup("heidi@test.local", "Heidi").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    app.befriend(&ada, &grace).await;
    let group_id = app.create_group(&grace, &[&heidi]).await;

    let (key, url) = app.upload(&ada, "photo.jpg").await;
    assert_eq!(app.send_picture(&ada, &grace, &url).await, StatusCode::CREATED);
    assert_eq!(
        app.send_group_picture(&grace, &group_id, &url).await,
        StatusCode::CREATED,
        "Grace forwards what she was sent"
    );

    assert_eq!(
        app.open_media(&key, &heidi).await,
        StatusCode::OK,
        "a member of the group it was forwarded to"
    );
    let (status, body) = app
        .call(
            "GET",
            &format!("/media/link?key={key}"),
            Some(&heidi.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "a share link for that member: {body}");

    assert_eq!(app.open_media(&key, &grace).await, StatusCode::OK);
    assert_eq!(app.open_media(&key, &ada).await, StatusCode::OK);
    assert_eq!(
        app.open_media(&key, &mallory).await,
        StatusCode::NOT_FOUND,
        "somebody outside the chat and outside the group"
    );
}

/// The same rule from the other side, and worse to live with: a photo in a group,
/// forwarded by one member into a private chat, used to disappear for everybody
/// else in the group.
#[tokio::test]
async fn forwarding_a_group_photo_does_not_take_it_away_from_the_group() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let heidi = app.signup("heidi@test.local", "Heidi").await;
    let ivan = app.signup("ivan@test.local", "Ivan").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    app.befriend(&grace, &ivan).await;
    let group_id = app.create_group(&ada, &[&grace, &heidi]).await;

    let (key, url) = app.upload(&ada, "photo.jpg").await;
    assert_eq!(
        app.send_group_picture(&ada, &group_id, &url).await,
        StatusCode::CREATED
    );
    assert_eq!(app.open_media(&key, &heidi).await, StatusCode::OK, "before");

    assert_eq!(
        app.send_picture(&grace, &ivan, &url).await,
        StatusCode::CREATED,
        "Grace forwards it to a friend"
    );

    assert_eq!(
        app.open_media(&key, &heidi).await,
        StatusCode::OK,
        "a group member, after somebody else forwarded the photo"
    );
    assert_eq!(app.open_media(&key, &ivan).await, StatusCode::OK);
    assert_eq!(app.open_media(&key, &mallory).await, StatusCode::NOT_FOUND);
}

/// And for a status: the same photo posted as a status and sent to one friend.
/// The other friends are the status's audience and keep seeing it.
#[tokio::test]
async fn a_photo_used_in_a_status_and_in_a_chat_stays_open_to_both() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let heidi = app.signup("heidi@test.local", "Heidi").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    app.befriend(&ada, &grace).await;
    app.befriend(&ada, &heidi).await;

    let (key, url) = app.upload(&ada, "story.jpg").await;
    let (status, body) = app
        .call(
            "POST",
            "/status",
            Some(&ada.token),
            Some(json!({ "content_type": "image", "content_url": url })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "status: {body}");
    assert_eq!(app.open_media(&key, &heidi).await, StatusCode::OK, "before");

    assert_eq!(app.send_picture(&ada, &grace, &url).await, StatusCode::CREATED);

    assert_eq!(
        app.open_media(&key, &heidi).await,
        StatusCode::OK,
        "a friend who sees the status, after the photo was also sent in a chat"
    );
    assert_eq!(app.open_media(&key, &grace).await, StatusCode::OK);
    assert_eq!(app.open_media(&key, &mallory).await, StatusCode::NOT_FOUND);
}

/// Access follows the messages, statuses and profiles that mention a file. So
/// mentioning it must need access: otherwise knowing a file's address — from an
/// expired share link, say — is enough to hand the file to oneself.
///
/// Mallory knows the address of Ada's photo to Grace and has a friend of her own.
/// Every place that stores a media address is tried.
#[tokio::test]
async fn knowing_a_media_address_is_not_enough_to_attach_it() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    let trent = app.signup("trent@test.local", "Trent").await;
    app.befriend(&ada, &grace).await;
    app.befriend(&mallory, &trent).await;
    let group_id = app.create_group(&mallory, &[&trent]).await;

    let (key, url) = app.upload(&ada, "private.jpg").await;
    assert_eq!(app.send_picture(&ada, &grace, &url).await, StatusCode::CREATED);
    assert_eq!(app.open_media(&key, &mallory).await, StatusCode::NOT_FOUND);

    let elsewhere = "https://pictures.example/cat.jpg";
    let attempts: Vec<(&str, &str, String, Value)> = vec![
        (
            "a message",
            "POST",
            "/messages".to_string(),
            json!({ "receiver_id": trent.id, "content": "", "type": "image", "media_url": url }),
        ),
        (
            "a message's thumbnail",
            "POST",
            "/messages".to_string(),
            json!({
                "receiver_id": trent.id,
                "content": "",
                "type": "image",
                "media_url": elsewhere,
                "thumbnail_url": url,
            }),
        ),
        (
            "a group message",
            "POST",
            format!("/groups/{group_id}/messages"),
            json!({ "content": "", "type": "image", "media_url": url }),
        ),
        (
            "a group message's thumbnail",
            "POST",
            format!("/groups/{group_id}/messages"),
            json!({ "content": "", "type": "image", "media_url": elsewhere, "thumbnail_url": url }),
        ),
        (
            "a status",
            "POST",
            "/status".to_string(),
            json!({ "content_type": "image", "content_url": url }),
        ),
        (
            "a status's thumbnail",
            "POST",
            "/status".to_string(),
            json!({ "content_type": "image", "content_url": elsewhere, "thumbnail_url": url }),
        ),
        (
            "a profile picture",
            "PATCH",
            "/users/me".to_string(),
            json!({ "avatar_url": url }),
        ),
        (
            "a new group's picture",
            "POST",
            "/groups".to_string(),
            json!({ "name": "Gallery", "memberIds": [], "avatar_url": url }),
        ),
        (
            "a group's picture",
            "PATCH",
            format!("/groups/{group_id}"),
            json!({ "avatar_url": url }),
        ),
        (
            "an address that only contains the file's name",
            "POST",
            "/messages".to_string(),
            json!({
                "receiver_id": trent.id,
                "content": "",
                "type": "image",
                "media_url": format!("https://pictures.example/?file={key}"),
            }),
        ),
    ];
    for (place, method, uri, body) in attempts {
        let (status, answer) = app
            .call(method, &uri, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(
            status,
            StatusCode::FORBIDDEN,
            "{place} took the address of a file Mallory cannot open: {answer}"
        );
    }

    assert_eq!(
        app.open_media(&key, &mallory).await,
        StatusCode::NOT_FOUND,
        "Mallory, after trying"
    );
    assert_eq!(app.open_media(&key, &trent).await, StatusCode::NOT_FOUND);
    assert_eq!(app.open_media(&key, &ada).await, StatusCode::OK);
    assert_eq!(app.open_media(&key, &grace).await, StatusCode::OK);

    // An address that is not a file on this server is none of its business.
    assert_eq!(
        app.send_picture(&mallory, &trent, elsewhere).await,
        StatusCode::CREATED,
        "a picture hosted somewhere else"
    );
}

/// The disk reads `a//b` and `a/b` as the same file. Every check that compares
/// addresses reads them as different text. So a file is served under the one
/// name the server gave it, and a second spelling of that name opens nothing —
/// not even for somebody who wrote that spelling into a message of their own.
#[tokio::test]
async fn a_second_spelling_of_a_media_address_opens_nothing() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let mallory = app.signup("mallory@test.local", "Mallory").await;
    let trent = app.signup("trent@test.local", "Trent").await;
    app.befriend(&ada, &grace).await;
    app.befriend(&mallory, &trent).await;

    let (key, url) = app.upload(&ada, "private.jpg").await;
    assert_eq!(app.send_picture(&ada, &grace, &url).await, StatusCode::CREATED);

    let twisted_key = key.replacen('/', "//", 1);
    let twisted_url = url.replacen(&key, &twisted_key, 1);
    assert_ne!(twisted_url, url, "the address contains the key");
    // Whether this message is accepted does not matter; what it unlocks does.
    let _ = app.send_picture(&mallory, &trent, &twisted_url).await;

    assert_eq!(
        app.open_media(&twisted_key, &mallory).await,
        StatusCode::NOT_FOUND,
        "Mallory, under a second spelling of the address"
    );
    assert_eq!(app.open_media(&twisted_key, &trent).await, StatusCode::NOT_FOUND);
    let (status, _) = app
        .call(
            "GET",
            &format!("/media/link?key={twisted_key}"),
            Some(&mallory.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND, "a share link for that spelling");

    assert_eq!(app.open_media(&key, &mallory).await, StatusCode::NOT_FOUND);
    assert_eq!(app.open_media(&key, &grace).await, StatusCode::OK);
    assert_eq!(app.open_media(&key, &ada).await, StatusCode::OK);
}

// --------------------------------------------------------------------- input checks

/// A stored file is sent back with the type its uploader named. A page or a
/// drawing with script in it would then run in the server's own web origin for
/// whoever opens a share link. Only plain pictures, sound and video keep their
/// type; everything else is a download.
#[tokio::test]
async fn an_uploaded_file_cannot_run_as_a_web_page() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;

    for (file_name, mime) in [("page.html", "text/html"), ("drawing.svg", "image/svg+xml")] {
        let (status, body) = app.try_upload(&ada, file_name, mime).await;
        assert_eq!(status, StatusCode::CREATED, "upload {file_name}: {body}");
        let key = body["key"].as_str().expect("key").to_string();
        let reply = app
            .request(
                "GET",
                &format!("/media/file/{key}"),
                Some(&ada.token),
                None,
                Extra::default(),
            )
            .await;
        assert_eq!(reply.status, StatusCode::OK, "{file_name}");
        assert_eq!(
            reply.header("content-type").as_deref(),
            Some("application/octet-stream"),
            "{file_name} is served with the type its uploader chose"
        );
        let disposition = reply.header("content-disposition").unwrap_or_default();
        assert!(
            disposition.starts_with("attachment"),
            "{file_name}: content-disposition is {disposition:?}"
        );
        assert_eq!(
            reply.header("x-content-type-options").as_deref(),
            Some("nosniff"),
            "{file_name}"
        );
    }

    // A photo is still a photo.
    let (status, body) = app.try_upload(&ada, "photo.jpg", "image/jpeg").await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();
    let reply = app
        .request(
            "GET",
            &format!("/media/file/{key}"),
            Some(&ada.token),
            None,
            Extra::default(),
        )
        .await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.header("content-type").as_deref(), Some("image/jpeg"));
    assert_eq!(
        reply.header("x-content-type-options").as_deref(),
        Some("nosniff")
    );
    assert_eq!(reply.header("content-disposition"), None);
}

/// A file's address is built from the end of its name. A name ending in something
/// that means something in an address made a file nobody could fetch.
#[tokio::test]
async fn an_upload_can_be_read_back_whatever_its_name() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;

    let (status, body) = app.try_upload(&ada, "holiday.j?pg", "image/jpeg").await;
    assert_eq!(status, StatusCode::CREATED, "upload: {body}");
    let key = body["key"].as_str().expect("key").to_string();
    assert!(
        key.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '/' || c == '-' || c == '.'),
        "the file's key is {key:?}"
    );
    assert_eq!(app.open_media(&key, &ada).await, StatusCode::OK);
}

/// When the server itself fails, the caller learns that it failed. The table
/// names and the SQL belong in the server's log.
#[tokio::test]
async fn a_server_error_does_not_describe_the_database() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    app.state
        .db
        .with(|conn| conn.execute("DROP TABLE presence", []))
        .expect("break the database");

    let (status, body) = app.call("GET", "/users/me", Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{body}");
    let text = body.to_string().to_lowercase();
    for word in ["presence", "table", "database", "sql"] {
        assert!(
            !text.contains(word),
            "a failed request says {word:?}: {body}"
        );
    }
}

/// An id in a request body that names no account. That is the caller's mistake,
/// so the answer is a 4xx, and a request that is refused leaves nothing behind.
#[tokio::test]
async fn an_id_that_names_nobody_is_refused_cleanly() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let nobody = "00000000-0000-4000-8000-000000000000";

    let attempts: Vec<(&str, String, Value)> = vec![
        (
            "a friend request",
            "/friends/request".to_string(),
            json!({ "receiver_id": nobody }),
        ),
        (
            "a block",
            "/friends/block".to_string(),
            json!({ "user_id": nobody }),
        ),
        (
            "a call",
            "/calls".to_string(),
            json!({ "receiver_id": nobody, "call_type": "audio" }),
        ),
        (
            "a new group",
            "/groups".to_string(),
            json!({ "name": "Ghosts", "memberIds": [grace.id.as_str(), nobody] }),
        ),
        (
            "a new member",
            format!("/groups/{group_id}/members"),
            json!({ "memberIds": [nobody] }),
        ),
    ];
    for (what, uri, body) in attempts {
        let (status, answer) = app
            .call("POST", &uri, Some(&ada.token), Some(body))
            .await;
        assert!(
            status.is_client_error(),
            "{what} for an id that names nobody was answered {status}: {answer}"
        );
    }

    let (status, body) = app.call("GET", "/groups", Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(
        body["groups"].as_array().map(Vec::len),
        Some(1),
        "the refused group was half made: {body}"
    );
    let (status, body) = app.call("GET", "/groups", Some(&grace.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(
        body["groups"].as_array().map(Vec::len),
        Some(1),
        "Grace was put into the refused group: {body}"
    );
}

/// Text that is stored and then sent to other people's phones has a size. A
/// display name is in every list; without a limit one account can make every
/// such list as large as it likes.
#[tokio::test]
async fn text_far_beyond_any_sensible_length_is_refused() {
    let app = app();
    let ada = app.signup("ada@test.local", "Ada").await;
    let grace = app.signup("grace@test.local", "Grace").await;
    app.befriend(&ada, &grace).await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "content": "first" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "message: {body}");
    let message_id = body["message"]["id"].as_str().expect("id").to_string();

    let attempts: Vec<(&str, &str, String, Value)> = vec![
        (
            "a display name",
            "PATCH",
            "/users/me".to_string(),
            json!({ "full_name": long(200_000) }),
        ),
        (
            "a username",
            "PATCH",
            "/users/me".to_string(),
            json!({ "username": long(200) }),
        ),
        (
            "an about text",
            "PATCH",
            "/users/me".to_string(),
            json!({ "bio": long(5_000) }),
        ),
        (
            "a profile picture address",
            "PATCH",
            "/users/me".to_string(),
            json!({ "avatar_url": format!("https://pictures.example/{}", long(5_000)) }),
        ),
        (
            "a message",
            "POST",
            "/messages".to_string(),
            json!({ "receiver_id": grace.id, "content": long(70_000) }),
        ),
        (
            "an edited message",
            "PATCH",
            format!("/messages/{message_id}"),
            json!({ "content": long(70_000) }),
        ),
        (
            "a media address",
            "POST",
            "/messages".to_string(),
            json!({
                "receiver_id": grace.id,
                "content": "",
                "type": "image",
                "media_url": format!("https://pictures.example/{}", long(5_000)),
            }),
        ),
        (
            "a group name",
            "POST",
            "/groups".to_string(),
            json!({ "name": long(5_000), "memberIds": [] }),
        ),
        (
            "a group description",
            "PATCH",
            format!("/groups/{group_id}"),
            json!({ "description": long(20_000) }),
        ),
        (
            "a group message",
            "POST",
            format!("/groups/{group_id}/messages"),
            json!({ "content": long(70_000) }),
        ),
        (
            "a status",
            "POST",
            "/status".to_string(),
            json!({ "content_type": "text", "text_content": long(20_000) }),
        ),
    ];
    for (what, method, uri, body) in attempts {
        let (status, _) = app.call(method, &uri, Some(&ada.token), Some(body)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{what} of absurd length");
    }

    // Nothing of it was kept.
    let (status, body) = app.call("GET", "/users/me", Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profile"]["full_name"], "Ada");
    assert!(body["profile"]["username"].is_null());
    assert!(body["profile"]["bio"].is_null());
    assert!(body["profile"]["avatar_url"].is_null());
    let (status, body) = app
        .call(
            "GET",
            &format!("/messages/with/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["messages"].as_array().map(Vec::len), Some(1));
    assert_eq!(body["messages"][0]["content"], "first");

    // Signing up.
    let (status, _) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": "long@test.local", "password": PASSWORD, "full_name": long(5_000) })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "signing up with an absurd name");
    let (status, _) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({
                "email": format!("{}@test.local", long(400)),
                "password": PASSWORD,
                "full_name": "Long",
            })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "signing up with an absurd address");
    assert_eq!(
        app.try_login("long@test.local", PASSWORD, Extra::default())
            .await,
        StatusCode::UNAUTHORIZED,
        "the refused sign-up made an account"
    );

    // Searching: a pattern longer than the database accepts used to be a server error.
    let (status, _) = app
        .call(
            "GET",
            &format!("/users/search?q={}", long(60_000)),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "a search text of absurd length");

    // Ordinary sizes, up to the limits, are still taken.
    let (status, _) = app
        .call(
            "PATCH",
            "/users/me",
            Some(&ada.token),
            Some(json!({ "full_name": long(100) })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "a display name of 100 characters");
    let (status, _) = app
        .call(
            "POST",
            "/messages",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "content": long(65_536) })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "a message of 65,536 characters");
}
