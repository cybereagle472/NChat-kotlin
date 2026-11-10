use std::net::SocketAddr;
use std::path::PathBuf;

use nchat_server::db::Db;
use nchat_server::{build_app, AppState};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_env("NCHAT_LOG").unwrap_or_else(|_| EnvFilter::new("info")))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(8080);

    let data_dir = std::env::var("NCHAT_DATA_DIR").unwrap_or_else(|_| "data".to_string());
    let db_path = PathBuf::from(&data_dir).join("nchat.sqlite");
    let media_dir = PathBuf::from(&data_dir).join("media");

    // The reference reads JWT_SECRET; accept both names so a deployment can be
    // migrated without renaming secrets.
    let secret = std::env::var("JWT_SECRET")
        .or_else(|_| std::env::var("NCHAT_SECRET"))
        .unwrap_or_else(|_| {
            tracing::warn!("JWT_SECRET is not set — using an ephemeral development key");
            uuid::Uuid::new_v4().to_string()
        });

    let public_api_url = std::env::var("PUBLIC_API_URL").unwrap_or_else(|_| {
        format!("http://localhost:{port}")
    });

    // Upload cap and credential throttling. Cloudflare's edge applied both for the worker;
    // a self-hosted server must enforce them itself.
    let max_upload_mb: u64 = std::env::var("NCHAT_MAX_UPLOAD_MB")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(100);
    let auth_rate: usize = std::env::var("NCHAT_AUTH_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(10);
    let auth_window_secs: u64 = std::env::var("NCHAT_AUTH_WINDOW_SECS")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(300);

    // Sign-in attempts per account, whatever address they claim to come from. Three times
    // the per-address budget unless set: room for a phone, a laptop and a mistake.
    let auth_account_rate: usize = std::env::var("NCHAT_AUTH_ACCOUNT_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(auth_rate.saturating_mul(3));

    // Which address a request is counted against. 0: the address of the connection, which
    // is right when clients reach this port directly. n: the n-th entry from the right of
    // X-Forwarded-For, which is right when exactly n reverse proxies stand in front and
    // nothing else can reach this port. See `ratelimit::client_address`.
    let trusted_proxies: usize = std::env::var("NCHAT_TRUSTED_PROXIES")
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0);
    // Password hashes one client address may ask for, across accounts, sign-in and sign-up
    // together. Three times the per-(client, account) budget unless set.
    let auth_address_rate: usize = std::env::var("NCHAT_AUTH_ADDRESS_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(auth_rate.saturating_mul(3));
    // What one signed-in account may do: messages, statuses and uploads a minute, and
    // friend requests, new groups, added members and calls in five minutes.
    let send_rate: usize = std::env::var("NCHAT_SEND_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(300);
    let contact_rate: usize = std::env::var("NCHAT_CONTACT_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(30);
    // Websites a browser may call this API from, comma-separated. Empty: none.
    let cors_origins: Vec<String> = std::env::var("NCHAT_CORS_ORIGINS")
        .unwrap_or_default()
        .split(',')
        .map(|origin| origin.trim().to_string())
        .filter(|origin| !origin.is_empty())
        .collect();

    // PBKDF2 rounds for new and upgraded password hashes. Never below what both backends
    // write today; see `password.rs` for what raising it means for the other backend.
    let password_iterations: u32 = std::env::var("NCHAT_PBKDF2_ITERATIONS")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .unwrap_or(nchat_server::password::ITERATIONS)
        .clamp(
            nchat_server::password::ITERATIONS,
            nchat_server::password::MAX_ITERATIONS,
        );

    // Media privacy. The reference stored files in a private R2 bucket and handed out
    // URLs; here a read is authorised per caller (see `routes::media`). `NCHAT_MEDIA_PUBLIC=1`
    // is an escape hatch for a deployment that still expects public links.
    let media_public = matches!(
        std::env::var("NCHAT_MEDIA_PUBLIC")
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase()
            .as_str(),
        "1" | "true" | "yes"
    );
    let media_ttl: i64 = std::env::var("NCHAT_MEDIA_TTL")
        .ok()
        .and_then(|value| value.parse().ok())
        .filter(|value| *value > 0)
        .unwrap_or(600);

    let pool_size: usize = std::env::var("NCHAT_POOL")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(8);

    let db = match Db::open(&db_path, pool_size) {
        Ok(db) => db,
        Err(error) => {
            tracing::error!(%error, path = %db_path.display(), "could not open the database");
            std::process::exit(1);
        }
    };
    tracing::info!(path = %db_path.display(), pool = pool_size, "database ready");

    let state = AppState::new(db, secret)
        .with_public_url(public_api_url.clone())
        .with_media_dir(media_dir.clone())
        .with_max_upload(max_upload_mb * 1024 * 1024)
        .with_auth_limiter(nchat_server::ratelimit::RateLimiter::new(
            auth_rate,
            std::time::Duration::from_secs(auth_window_secs),
        ))
        .with_account_limiter(nchat_server::ratelimit::RateLimiter::new(
            auth_account_rate,
            std::time::Duration::from_secs(auth_window_secs),
        ))
        .with_address_limiter(nchat_server::ratelimit::RateLimiter::new(
            auth_address_rate,
            std::time::Duration::from_secs(auth_window_secs),
        ))
        .with_send_limiter(nchat_server::ratelimit::RateLimiter::new(
            send_rate,
            std::time::Duration::from_secs(60),
        ))
        .with_contact_limiter(nchat_server::ratelimit::RateLimiter::new(
            contact_rate,
            std::time::Duration::from_secs(300),
        ))
        .with_trusted_proxies(trusted_proxies)
        .with_cors_origins(cors_origins.clone())
        .with_password_iterations(password_iterations)
        .with_public_media(media_public)
        .with_media_link_ttl(media_ttl);
    tracing::info!(
        max_upload_mb,
        auth_rate,
        auth_account_rate,
        auth_address_rate,
        auth_window_secs,
        send_rate,
        contact_rate,
        trusted_proxies,
        password_iterations,
        media_public,
        media_ttl,
        "limits ready"
    );
    if cors_origins.is_empty() {
        tracing::info!("no web origin may call this API (NCHAT_CORS_ORIGINS is empty)");
    } else {
        tracing::info!(origins = ?cors_origins, "web origins that may call this API");
    }

    // The administrator is an account that has the role. Naming an address here gives the
    // role to the account that has that address now, and takes it from everybody else. It
    // is read once: an account made later does not get the role until the next start,
    // and the log says whose account it was given to.
    let admin_email = std::env::var("NCHAT_ADMIN_EMAIL").unwrap_or_default();
    if !admin_email.trim().is_empty() {
        match state.apply_admin_email(&admin_email) {
            Ok(true) => tracing::info!(
                "the account with the NCHAT_ADMIN_EMAIL address is the administrator"
            ),
            Ok(false) => tracing::warn!(
                "NCHAT_ADMIN_EMAIL names an address that has no account, so nobody is the \
                 administrator. Sign up with that address, check that the account is yours, \
                 then restart the server"
            ),
            Err(error) => tracing::error!(%error, "could not apply NCHAT_ADMIN_EMAIL"),
        }
    }

    // Start-up is also when the decoy for unknown accounts is made, so that the first
    // such sign-in costs what every later one costs.
    let _ = state.decoy_password_hash();

    let app = build_app(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => listener,
        Err(error) => {
            tracing::error!(%addr, %error, "could not bind");
            std::process::exit(1);
        }
    };
    tracing::info!(%addr, public_api_url = %public_api_url, "NChat backend listening");

    // `with_connect_info` is what tells a handler where a connection comes from; the
    // credential limits count against that address.
    if let Err(error) = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    {
        tracing::error!(%error, "server stopped with an error");
    }
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut signal) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            signal.recv().await;
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("shutting down");
}
