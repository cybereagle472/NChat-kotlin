# NChat Rust backend

The self-hosted backend uses Axum and Tokio, SQLite for application data, a local
filesystem directory for media, and an in-process WebSocket hub. Its HTTP routes
are defined in `src/routes/mod.rs`; they are not prefixed with `/api/v1`.

## Run

```sh
cd server
JWT_SECRET='replace-with-a-long-random-secret' \
PUBLIC_API_URL='https://chat.example.com' \
NCHAT_DATA_DIR=data cargo run --release
```

The server binds `0.0.0.0:8080` by default. Terminate TLS in a trusted reverse
proxy, and configure the Android build's `NCHAT_API_BASE` to the public HTTPS URL.
`GET /health` returns `{"ok":true}`. Back up the database **and** media directory;
use a SQLite-aware backup for a live WAL database.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Listening port |
| `NCHAT_DATA_DIR` | `data` | Directory containing `nchat.sqlite` and `media/` |
| `JWT_SECRET` or `NCHAT_SECRET` | Random per startup | Token and media-link signing; set a persistent secret in production |
| `PUBLIC_API_URL` | `http://localhost:<PORT>` | Public URL used in generated media links |
| `NCHAT_POOL` | `8` | SQLite connections |
| `NCHAT_MAX_UPLOAD_MB` | `100` | Per-file upload limit |
| `NCHAT_AUTH_RATE` / `NCHAT_AUTH_WINDOW_SECS` | `10` / `300` | Process-local credential throttling |
| `NCHAT_MEDIA_PUBLIC` | `0` | Set to `1` to allow anonymous media reads |
| `NCHAT_MEDIA_TTL` | `600` | Signed media link lifetime in seconds |
| `NCHAT_ADMIN_EMAIL` | Unset | Account allowed to use admin endpoints |
| `CF_TURN_KEY_ID` / `CF_TURN_KEY_API_TOKEN` | Unset | Cloudflare TURN credentials for calls |
| `NCHAT_LOG` | `info` | Logging filter |

The merged `schema.sql` bootstraps fresh databases and is safe to run again on
restart. `CREATE IF NOT EXISTS` is not a migration system: it does not add
missing columns to an older schema. Back up and explicitly migrate existing older
databases before deployment. The SQL files in `migrations/` describe the original
worker migrations and are not automatically applied by this server.

WebSocket clients use `/realtime/connect?room=user:<id>&token=<access token>`.
Only the authenticated user's personal room is accepted; client-sent text frames
are not relayed. Configure reverse proxies not to log the query string, because
it contains a bearer token. The hub is process-local: running multiple instances
requires shared event delivery. Private media reads require authorization by
default; `NCHAT_MEDIA_PUBLIC=1` disables that protection.

Run backend checks with `cargo test`. This service does **not** provide end-to-end
encryption; server operators can access message content.
