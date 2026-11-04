use std::net::SocketAddr;
use std::path::PathBuf;

use nchat_server::store::Store;
use nchat_server::{build_app, AppState, DEFAULT_TOKEN_TTL_SECS};
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
    let db_path = PathBuf::from(data_dir).join("db.json");

    let secret = std::env::var("NCHAT_SECRET")
        .map(|value| value.into_bytes())
        .unwrap_or_else(|_| {
            tracing::warn!("NCHAT_SECRET is not set — using an ephemeral development key");
            let generated = uuid::Uuid::new_v4().to_string();
            generated.into_bytes()
        });

    let ttl = std::env::var("NCHAT_TOKEN_TTL")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(DEFAULT_TOKEN_TTL_SECS);

    let store = Store::open(Some(db_path.clone()));
    let (users, chats) = store.counts();
    tracing::info!(users, chats, path = %db_path.display(), "state loaded");

    let state = AppState::new(store, secret, ttl);
    let app = build_app(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => listener,
        Err(error) => {
            tracing::error!(%addr, %error, "could not bind");
            std::process::exit(1);
        }
    };
    tracing::info!(%addr, "NChat backend listening");

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
