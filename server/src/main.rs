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
        ));
    tracing::info!(
        max_upload_mb,
        auth_rate,
        auth_window_secs,
        "limits ready"
    );
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

    if let Err(error) = axum::serve(listener, app)
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
