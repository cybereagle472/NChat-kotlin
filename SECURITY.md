# Security & privacy

What NChat protects, how, and where the honest limits are. Written for a self-hosted
deployment of `server/` and for the APK that talks to it.

---

## 1. Transport

| | |
|---|---|
| Release APK | HTTPS only. Android's default `usesCleartextTraffic=false`; nothing in the release manifest re-enables it. |
| Debug APK | `app/src/debug/AndroidManifest.xml` allows plain HTTP **only in debug builds**, so `cargo run` on `10.0.2.2:8080` works. |
| Backend | Terminate TLS in front of `server/` (Cloudflare, Caddy, nginx). The Worker deployment gets it from Cloudflare automatically. |
| WebSocket | Same host, `wss://` derived from the HTTP root — never a second, unencrypted channel. |

## 2. No backend address in code

A hardcoded URL is not a secret (it ships inside the APK anyway) — the problem is *sprawl*:
one address per screen means every future move touches dozens of files, and mixed schemes
(`http://` left behind in one place) become invisible.

```
nchat.properties / -PNCHAT_API_BASE / $NCHAT_API_BASE / local.properties
        │  Gradle, build time — exactly one value
        ▼  BuildConfig.NCHAT_API_BASE
ServerConfig.effective()   ← Settings ▸ Server (per-install override)
        ▼
ApiClient (root + socketRoot) ──► repositories ──► screens
```

- `grep -rn "workers.dev" app/src/main/java` → **0 hits**, and the address is not in the
  repository either: it lives in the Actions variable `NCHAT_API_BASE`.
- There is **no runtime server switcher**: the address is a build property, so a device
  cannot be redirected to a look-alike server by someone who briefly has the phone.
- The single committed value lives in `nchat.properties` and can be removed from git entirely
  by setting `NCHAT_API_BASE` as a repository Actions variable/secret.

## 3. Credentials on the device

| Item | Storage |
|---|---|
| Access token (15 min) | `SecurePrefs` — AES-256-GCM, key in the **Android Keystore**, not exportable |
| Refresh token (30 days, single-use rotation) | same |
| Identity (id, email, name, username, avatar URL) | same |
| Chat-list cache | plain JSON in app storage — chats and names only, wiped on sign-out |

- A copied `SharedPreferences` file is useless on another device: the key never leaves the
  Keystore.
- Values written by builds before 1.0.22 are read as plain text and re-encrypted on the next
  write, so upgrading does not sign anyone out.
- If the Keystore is unavailable (rare emulators / heavily modified ROMs) the store degrades
  to plain text instead of locking the user out; `SecurePrefs.isEncrypted` exposes which
  state is active.
- Signing out clears every token and identity field, and drops the realtime socket.

## 4. Server-side defaults

| Concern | Behaviour |
|---|---|
| Password hashing | PBKDF2-SHA256, 100 000 rounds, 16-byte salt — **byte-compatible** with the reference worker, so accounts move between the two. |
| JWT signing | HMAC-SHA256. `JWT_SECRET` (or `NCHAT_SECRET`) from the environment; if unset the server logs a warning and uses an ephemeral key (sessions do not survive a restart — fine for local development, wrong for production). |
| Credential throttling | `/auth/login` and `/auth/signup` are limited per (client, address) pair: `NCHAT_AUTH_RATE` attempts per `NCHAT_AUTH_WINDOW_SECS` (default 10 / 300 s) → `429 Too Many Attempts`. |
| Uploads | Streamed to disk, capped at `NCHAT_MAX_UPLOAD_MB` (default 100) → `413`. Keys are generated server-side; client filenames are stored as metadata only. |
| Stored media paths | Every read/delete key is validated against `..`, absolute paths and backslashes before it touches the filesystem; the resolved path must stay inside the media directory. Covered by unit tests in `server/src/routes/media.rs`. |
| Media reads | Streamed in chunks with `Range` support — a 200 MB video no longer costs 200 MB of RAM per request. |
| Admin routes | `/admin/*` accepts only the account named in `NCHAT_ADMIN_EMAIL`. **There is no default address in the source**: with the variable unset, nobody is an admin. |
| CORS | Permissive (`*`), exactly like the reference worker, because the web client is a separate origin. The APK does not use CORS. Tighten it at the edge (`NCHAT_ALLOWED_ORIGINS` is the tracked next step). |
| Logging | `TraceLayer` logs method + path + status. No tokens, no request bodies, no passwords. |

## 5. Deliberate limits (say them out loud)

1. **Media URLs are public.** `/media/file/<user>/<uuid>.<ext>` needs no token, matching the
   reference worker's public bucket. Keys are UUIDs, so they are not *guessable*, but anyone
   who receives a link (a forwarded message, a screenshot of a URL, a proxy log) can open the
   file. Making reads private needs the client to fetch with a token and signed URLs for the
   browser — tracked as the next security task, and it diverges from the worker, so it must be
   an explicit switch.
2. **The server can read messages.** There is no end-to-end encryption; the backend stores
   plaintext message content. E2EE is a protocol change (per-conversation keys, no server-side
   search), not a patch.
3. **No push-based 2FA / device list yet.** Sessions are bearer tokens; revoking one device
   means rotating the password (which invalidates every session) or clearing that install.
4. **The APK does not pin certificates.** Pinning would harden against corporate MITM proxies
   but breaks the "point this build at your own server" feature.

## 6. Deployment checklist

```bash
export JWT_SECRET="$(openssl rand -hex 32)"     # required in production
export NCHAT_ADMIN_EMAIL="you@example.com"      # required for /admin/*
export PUBLIC_API_URL="https://chat.example.com" # absolute media links
export NCHAT_MAX_UPLOAD_MB=50
export NCHAT_AUTH_RATE=10
export NCHAT_AUTH_WINDOW_SECS=300
export NCHAT_DATA_DIR=/var/lib/nchat             # keep off the web root
```

- Put TLS in front of the process; never expose plain HTTP to the internet.
- Back up `NCHAT_DATA_DIR` (SQLite database + `media/`); it is the whole server state.
- The client address used for throttling comes from `X-Forwarded-For` / `X-Real-IP`, so the
  proxy in front must set it.
