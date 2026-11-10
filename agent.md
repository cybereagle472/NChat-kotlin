# agent.md — NChat permanent engineering memory

> **Document status:** Phase 0 baseline, plus the Rust-backend half of Phase 1 (critical security, §33), added 2026-11-10 on a session branch that is not merged. This is the single source of truth for project state. A second Phase 1 turn the same day, on the same branch, repaired the in-app updater (Android 1.0.75) and hardened sign-in on the Rust backend (§19.4, Appendix E).
> **Last verified:** 2026-11-10 (UTC; Phase 0 baseline merged to main). Phase 1 Rust work: verified by CI the same day on its session branch (§36). Second Phase 1 turn: CI green for both workflows on `2b30f1d` (§36); nothing was run on a device.
> **Verified by:** Arena.ai Agent Mode.
> **Repository:** `cybereagle472/NChat-kotlin`.
> **Branch:** `arena/d8051bf9-nchat-kotlin` (active; Phase 0 was pushed here and merged to `main` through PR #87; the branch remains active and is not deleted). Phase 1 (Rust): `arena/56647832-nchat-kotlin` — active and pushed; not merged, and no pull request had been opened when this was written.
> **Start commit:** `d87b70302fbacff185e8f5c829fce03ee3ef7595`. Phase 1 (Rust): `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (`main` after PR #88).
> **End commit:** `f45480678630810eff1a525469d83d1feca0021f` (substantive Phase 0 commit; the handover commit that records this hash is a metadata-only follow-up). Phase 1 (Rust): `a1d23985852abfe66e47f0004cb01c387625560f` — the last commit that touches `server/`; the commits after it on that branch change only `agent.md`. Second Phase 1 turn: `2b30f1d9f0da08c54fa055efc1ce7c37d062fe0f` — the last commit of that turn that touches code; later commits change only `agent.md`.
> **Main merge commit:** `a867fdd881740889ee153013323072c1ebb8ea79` (PR #87, merged 2026-11-10 08:05:44 UTC; `agent.md` is now the only project-owned Markdown file on `main`). `main` has since moved to `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (PR #88, 2026-11-10 08:09:29 UTC, `agent.md` only). The Phase 1 branch has no merge commit.
> **Scope of this turn:** Phase 0 only — repository discovery, architecture/security audit, documentation consolidation, competitor comparison, implementation roadmap, and verification baseline. No application code, database, API, configuration, secret, or deployment was changed. **Scope of the Phase 1 turn (2026-11-10):** the Rust backend only — `server/src` and `server/tests`. No Android, database-schema, route-table, configuration, workflow, secret, or deployment change. The Cloudflare Worker repository `nchat-friends` was read, not changed: the gaps fixed here are still open there, except where §19.3 says the Worker differs. **Scope of the second Phase 1 turn (2026-11-10):** the Android updater (`app/…/core/update`, `feature/update/UpdateGate.kt`, one call in `MainActivity.onResume`), a version bump to 1.0.75 / 83 with `version.json`, and sign-in on the Rust backend (`server/src/{password,lib,main}.rs`, `routes/auth.rs`, `tests/auth_hardening.rs`). No database schema, route, workflow, secret or deployment change; nothing merged, nothing released.
> **Consolidation:** This file supersedes and consolidates the nine project-owned Markdown files previously in this repository. Their still-relevant content is preserved below; stale claims are explicitly labelled. Full verbatim history remains recoverable from Git history. No dependency/vendor/legal files were removed; the MIT `LICENSE` in the separate backend repository is untouched.

**Status vocabulary (exactly one primary status per feature):** `NOT_STARTED`, `DISCOVERED`, `IN_PROGRESS`, `PARTIALLY_IMPLEMENTED`, `IMPLEMENTED_UNVERIFIED`, `TESTING`, `BLOCKED`, `COMPLETED`, `DEPRECATED`. `COMPLETED` is used only where this audit has explicit test or CI evidence, never merely because a screen, route, or table exists.

---

## 1. Project identity and objectives

NChat is a modern messaging product with three visible codebases and two backend runtimes:

1. A native Android client in Kotlin/Jetpack Compose (`com.nchat.app`, currently version `1.0.74`, `versionCode 82`). *Update 2026-11-10:* `1.0.75` / `versionCode 83` on the Phase 1 branch; `main` and the published release are still `1.0.74` / `82`.
2. A React/TypeScript web client and a Cloudflare Workers/Hono backend in `cybereagle472/nchat-friends`.
3. A wire-compatible self-hosted Rust/Axum/SQLite backend in this repository under `server/`.

The primary objective is to evolve NChat into a secure, reliable, coherent messaging platform with functionality comparable to leading communication applications, without replacing the existing project, discarding working functionality, or losing user data. The Android app is the principal product surface; the Cloudflare Worker is the deployed reference backend; the Rust backend is an alternative runtime that preserves the same HTTP/JSON and real-time contracts. The web client in the backend repository is a separate client, not a third backend.

**Non-negotiable rules:** preserve package identity, architecture, API contracts, and history; use evidence before implementation; never introduce fake functionality; never remove auth checks; never perform destructive database operations, production deployments, secret rotation, or force pushes without explicit authorization; never call HTTPS/TLS alone “end-to-end encryption”; never claim completion from a visible screen, HTTP 200, or compilation alone.

---

## 2. Repository inventory

| Repository | Role | Default branch | Verified head | Branches | License / identity |
|---|---|---|---|---|---|
| `cybereagle472/NChat-kotlin` | Native Android client + Rust self-hosted backend, CI, release manifest, this memory file | `main` | `d87b70302fbacff185e8f5c829fce03ee3ef7595` | 79 branches at verification; `main` is authoritative; historical `arena/*` work branches merge into `main`; current fixed session branch is `arena/d8051bf9-nchat-kotlin` | Package `com.nchat.app`; no root LICENSE file in this repository |
| `cybereagle472/nchat-friends` | React/TypeScript web client, Capacitor wrapper, Cloudflare Worker, D1 migrations, documentation | `main` | `616a9e69407b072de29f640f35ea05c6f48b9768` | 11 branches; `main` is authoritative; historical `arena/*` branches are session work branches | MIT; web app `appId` in Capacitor config is `com.nchat.friends` |

A GitHub repository search for `NChat user:cybereagle472` returned only the two repositories above. No separate React/TypeScript frontend repository was found. Other repositories under the account (`React-webRTC-Calling-`, `Nstar-Ludo`, etc.) are unrelated to NChat and were not modified.

**Authorized modification scope for this session:** only `cybereagle472/NChat-kotlin`, branch `arena/d8051bf9-nchat-kotlin`. The backend repository was cloned read-only into `/tmp/nchat-friends-audit` for inspection; its files were not changed or deleted.

---

## 3. Latest verified commit for every repository

| Repository | Commit | Author date (UTC) | Subject | Verification |
|---|---|---|---|---|
| `NChat-kotlin` `main` | `a867fdd881740889ee153013323072c1ebb8ea79` | 2026-11-10 08:05:44 | `Merge pull request #87 ... Add Phase 0 agent.md to main` | `gh pr view 87`, `git ls-remote`, `git show origin/main:agent.md` |
| `NChat-kotlin` working branch | `e2ca5c2685ae29d8e86a6d3011a0b7918262f768` | 2026-11-09 15:14:49 / 15:16 UTC | Phase 0 baseline pushed to the active session branch, then merged to `main` via PR #87; branch retained | `git log`, `git ls-remote --heads origin arena/d8051bf9-nchat-kotlin`, `gh pr view 87` |
| `nchat-friends` `main` | `616a9e69407b072de29f640f35ea05c6f48b9768` | 2026-11-03 18:35:35 | `Merge pull request #11 ... Record verification that the username-search feature is fully merged` | Fresh shallow clone, `git log`, GitHub API |
| `NChat-kotlin` `main`, re-checked 2026-11-10 | `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` | 2026-11-10 08:09:29 | `Merge pull request #88 from cybereagle472/arena/d8051bf9-nchat-kotlin` (`agent.md` only) | `git ls-remote origin main`, `gh pr view 88` |
| `NChat-kotlin` Phase 1 branch `arena/56647832-nchat-kotlin` (not merged) | `a1d23985852abfe66e47f0004cb01c387625560f` (last commit touching `server/`) | 2026-11-10 09:54:54 | `ci: drop the backend failure log now that the signal fix is in`; 12 commits on top of `ec0f86c` up to here, 2 of them failure-log commits made by the workflow | `git log ec0f86c..`, `git push`, Rust backend run `42794684951` and APK run `42794684870`, both green |

**Deployment/CI heads:** the Android APK workflow succeeded on `d87b703` (run `42584666249`). Both Cloudflare Workers Builds check-runs failed on backend `616a9e6` (build IDs in the Cloudflare dashboard are referenced by GitHub check-run output; detailed logs are outside this sandbox’s allowed network). The Rust backend workflow last succeeded on 2026-11-08 at `b6e5721f43e6a8945ab63197bfc03a2f1a3faaac` (run `42491305703`); it did not run on `d87b703` because that commit did not touch `server/**`. PR #87’s Android `build` and Rust `Build and test` checks both passed before the `main` merge; the merged `main` head is `a867fdd881740889ee153013323072c1ebb8ea79`. **Update 2026-11-10 (Phase 1, Rust):** the Rust backend workflow also ran on `main` at `a867fdd` (run `42779970114`, green, the 18-test suite). On the Phase 1 branch it was red by design on the two tests-only commits (`22371ff`, run `42790409920`; `17c4c7f`, run `42794405563`) and green on `ccd83fe` (run `42791596474`, twice), `a5cd751` (run `42792659202`) and `a1d2398` (run `42794684951`). The Android APK workflow was green on every push of that branch (last: run `42794684870` on `a1d2398`). `nchat-friends` `main` was cloned afresh on 2026-11-10 and is still `616a9e69407b072de29f640f35ea05c6f48b9768`. None of the Phase 1 work is on `main`.

---

## 4. Current architecture

```
Android UI (Compose feature/*)
    -> ViewModels (StateFlow) -> repository interfaces -> Remote* implementations
    -> ApiClient (OkHttp + kotlinx.serialization) / NChatSocket (OkHttp WebSocket)
    -> BuildConfig.NCHAT_API_BASE (one build-time backend address)
    -> either:
       A) Cloudflare Worker (Hono) -> D1 SQLite / R2 objects / Durable Object RealtimeRoom
       B) Rust Axum server -> bundled SQLite / local media files / in-process Hub
    -> REST response + WebSocket event in personal room user:<id>
    -> other clients and other sessions of the same account
```

The two backends are **wire-compatible but operationally different**. They share snake_case JSON, wrappers such as `{messages:[]}` / `{chats:[]}` / `{groups:[]}`, JWT/PBKDF2 formats, route shapes, and real-time event names. They do **not** share the same security posture: the Rust backend already implements private media, strict personal-room WebSocket authorization, server-side friend/block checks for direct messages, upload limits, and auth throttling; the Cloudflare Worker does not implement all of those. Improving one backend does not automatically improve the other.

The web client in `nchat-friends/src/` uses service modules (`authService`, `chatService`, `friendService`, `groupService`, `callService`, `groupCallService`, `statusService`, `mediaService`, `privacyService`, `typingService`, `profileService`), `apiClient.ts`, `realtimeClient.ts`, `webrtcMedia.ts`, `offlineStorage.ts`, and React hooks. Its `package.json` uses React `18.3.1`, not React 19; `CLAUDE.md` and some README claims in that repository are stale. The historical `worker/README.md` claim that only auth/profile services are migrated is contradicted by current imports in `src/App.tsx`, hooks, and components.

The Rust backend is a deliberate line-for-line port of the Cloudflare Worker’s route contracts, not a redesign. It substitutes axum/tokio/rusqlite/local filesystem/in-process broadcast for Hono/Workers/D1/R2/Durable Objects. The Android client can be built against either backend through `NCHAT_API_BASE`; there is no runtime server switcher.

---

## 5. Technology stack

| Layer | Current technology | Evidence |
|---|---|---|
| Android | Kotlin 1.9.22, Jetpack Compose BOM 2024.02.00, Material 3, AGP 8.2.0, JDK 17, minSdk 24, targetSdk/compileSdk 34, single `:app` module | `build.gradle`, `app/build.gradle` |
| Android async/state | Coroutines, `StateFlow`, `SharedFlow`, `ViewModel`, hand-written `ServiceLocator` DI | `data/ServiceLocator.kt`, `feature/*ViewModel.kt` |
| Android network | OkHttp 4.12.0, kotlinx.serialization 1.6.2, Coil 2.5.0, `io.github.webrtc-sdk:android:150.7871.01`, AndroidX ExifInterface | `app/build.gradle` |
| Android local storage | JSON files in `filesDir`/`cacheDir`, `SharedPreferences` wrapped by `SecurePrefs`; no Room database | `data/local/*.kt`, `network/SecurePrefs.kt` |
| Web frontend | React 18.3.1, TypeScript ~5.8, Vite 6, Tailwind 4, Radix-derived custom CSS removed, Capacitor 8 web wrapper | `nchat-friends/package.json`, `capacitor.config.ts` |
| Cloudflare backend | Hono 4.6, Cloudflare Workers, D1 SQLite, R2 `nchat-media`, `RealtimeRoom` Durable Object, Web Crypto JWT/PBKDF2, Wrangler 3 | `worker/src/index.ts`, `worker/wrangler.toml`, `worker/package.json` |
| Rust backend | Axum 0.7, Tokio 1, rusqlite 0.31 bundled SQLite, serde_json, HMAC-SHA256, PBKDF2-SHA256, reqwest, tower-http, tracing | `server/Cargo.toml`, `server/src/lib.rs` |
| Calls | WebRTC, offer/answer/ICE over REST + WebSocket signaling, optional Cloudflare Realtime TURN, public STUN fallback; group calls are mesh | `data/calls/WebRtcEngine.kt`, `GroupCallSession.kt`, `worker/src/routes/groupCalls.ts` |
| CI | GitHub Actions: Android unit tests + signed APK release; Rust fmt/build/clippy/test; Cloudflare Workers Builds | `.github/workflows/*.yml`, backend repo `.github/workflows/apk.yml` |

### 5.1 Dependency map (verified from manifests)

| Boundary | Exact dependencies | Build/runtime constraints |
|---|---|---|
| Android build toolchain | AGP `8.2.0`, Kotlin plugin `1.9.22`, Kotlin serialization plugin `1.9.22`, Compose compiler `1.5.8`, JDK 17 | `compileSdk/targetSdk 34`, `minSdk 24`, `resourceConfigurations en`, `abiFilters arm64-v8a/armeabi-v7a`, single `:app` module |
| Android UI/runtime | Compose BOM `2024.02.00` (`ui`, `ui-graphics`, `ui-tooling-preview`, `foundation`, `animation`, `material3`, `material-icons-extended`), `androidx.core:core-ktx:1.12.0`, `activity-compose:1.8.2`, lifecycle `2.7.0`, `kotlinx-coroutines-android:1.7.3` | no Navigation-Compose, no Room, no Firebase, no dependency-injection framework |
| Android network/media/calls | `kotlinx-serialization-json:1.6.2`, `okhttp:4.12.0`, `coil-compose:2.5.0`, `exifinterface:1.3.7`, `io.github.webrtc-sdk:android:150.7871.01` | native WebRTC binaries included only for arm64/armv7; test runtime `junit:4.13.2` |
| Web frontend | React/React DOM `^18.3.1`, `lucide-react:^0.487.0`, `sonner:^2.0.3` | manual components/Tailwind; no Radix/shadcn runtime dependency |
| Web build tooling | TypeScript `~5.8.2`, Vite `^6.3.5`, `@vitejs/plugin-react-swc:^3.10.2`, Tailwind `^4.1.17`, `@tailwindcss/vite:^4.3.3`, PostCSS `^8.5.6`, Autoprefixer `^10.4.22`, `tw-animate-css:^1.4.0`, `web-push:^3.6.7` | `npm run build` = `tsc && vite build`; `npm run lint` is broken because ESLint is not installed |
| Capacitor wrapper | `@capacitor/core/cli/android ^8.6.0` | `appId com.nchat.friends`, `webDir dist`, no `server.url` override; bundles a static `dist/` snapshot |
| Cloudflare Worker | `hono:^4.6.14`, `@cloudflare/workers-types:^4.20241127.0`, TypeScript `^5.7.2`, Wrangler `^3.91.0` | D1/R2/Durable Object bindings declared in `worker/wrangler.toml`; scripts cover migrations 0001/0002 only |
| Rust backend | `axum 0.7` (`ws`, `macros`, `multipart`), `tokio 1`, `rusqlite 0.31` bundled, `serde/serde_json`, `uuid 1`, `chrono 0.4`, `sha2 0.10`, `hmac 0.12`, `pbkdf2 0.12` (`hmac`), `base64 0.22`, `rand 0.8`, `futures-util 0.3`, `tokio-util 0.7`, `reqwest 0.12` (`rustls-tls`), `tower-http 0.5`, `tracing`/`tracing-subscriber` | dev: `tower 0.5`, `http-body-util 0.1`, `tempfile 3`; release profile LTO thin |

Dependency ownership rule: Android and Rust manifests in `NChat-kotlin` are authoritative for this session; web/Worker manifests in `nchat-friends` are read-only reference. Changing a wire type requires coordinated Android DTO, Worker TypeScript, and Rust model changes.

---

## 6. Directory structure

### `NChat-kotlin`

```
agent.md                         # this permanent memory file
app/build.gradle                 # Android module, version 1.0.74 / code 82
build.gradle, settings.gradle, gradle.properties
nchat.properties                 # build-time apiBase; intentionally empty in Git
version.json                     # in-app update manifest, release tag/cert fingerprint
.github/workflows/build-apk.yaml # unit tests, signed release, APK verification, release asset
.github/workflows/backend.yml    # Rust fmt/build/clippy/test with zero-test guard
.github/scripts/*.py             # APK package/version/signing verification + unit tests
.github/nchat-demo-keystore.b64  # PUBLIC demo signing key; not a production credential
.ci/last-failure.log             # stale CI failure tail; do not trust as current status
app/src/main/AndroidManifest.xml
app/src/debug/AndroidManifest.xml
app/src/main/java/com/nchat/app/
  MainActivity.kt, NChatApplication.kt, ui/, core/, data/, feature/, network/
  core/designsystem/             # theme, colors, typography, components, motion, toasts
  core/navigation/               # NcNavigator and screen stack
  core/network/                  # NetworkMonitor
  core/update/                   # AutoUpdater, ApkDownloader, signer policy, system fallback
  core/audio/, core/util/        # recorder, waveform, text formatting, media prep, locator
  data/local/                    # caches, outbox, drafts, stars, pins, archive, wallpaper, voice clips
  data/repository/               # remote repositories and send/cache/delivery policies
  data/notifications/, data/push/# local notifications and FCM-less foreground sync
  data/calls/                    # WebRtcEngine and GroupCallSession
  feature/auth|chats|discover|groups|history|profile|settings|status|update/
  network/                       # ApiClient, NChatApi, Dtos, NChatSocket, TokenStore, SecurePrefs,
                                 # MediaImages, MediaFiles, ApiLog
app/src/test/java/...            # 21 JVM unit-test files
app/src/main/res/                # launcher/icon/theme/strings/FileProvider paths
server/Cargo.toml, server/src/, server/tests/, server/migrations/, server/schema.sql
```

### `nchat-friends`

```
src/                    # React client: components, hooks, lib, services, styles, types, utils
worker/src/index.ts     # Hono app, CORS, route mounts, WebSocket upgrade
worker/src/routes/      # auth, users, friends, groups, messages, calls, groupCalls,
                        # status, media, privacy, admin
worker/src/lib/         # jwt, password, presence, privacy, realtime, id
worker/src/middleware/  # requireAuth
worker/src/durable-objects/RealtimeRoom.ts
worker/migrations/      # 0001..0005 D1 SQL
worker/wrangler.toml    # D1/R2/Durable Object bindings, PUBLIC_API_URL
wrangler.toml           # separate static frontend Worker config
android/                # Capacitor-generated wrapper
docs/assets/            # banner image
README.md, CLAUDE.md, APP_DESCRIPTION.md, CONTRIBUTING.md, TESTING_CHECKLIST.md,
LICENSE, worker/README.md, worker/SETUP_GUIDE.md, worker/DASHBOARD_SETUP_GUIDE.md
```

---

## 7. Database schema and relationships

Both backends use the same 22-table SQLite schema. `server/schema.sql` is the idempotent fresh-database bootstrap; `server/migrations/` and `worker/migrations/` contain the same five versioned SQL files. Key relationships and indexes:

| Table | Purpose / important columns | Relationships and indexes |
|---|---|---|
| `users` | `id`, unique `email`, `password_hash`, `full_name`, unique `username`, `avatar_url`, `bio`, `is_admin`, `created_at` | parent of sessions, presence, friend requests, friendships, blocks, messages, groups, calls, statuses, settings, media |
| `sessions` | `id` (`sid`), `user_id`, `expires_at`, `revoked_at`, `user_agent` | FK to users; `idx_sessions_user`; refresh rotation deletes consumed row |
| `presence` | `user_id`, `last_active_at` | FK to users; online threshold 120 s |
| `friend_requests` | sender, receiver, pending/accepted/rejected, timestamps | FKs to users; unique `(sender_id, receiver_id)`; receiver/status and sender/status indexes |
| `friendships` | `user1_id`, `user2_id` | FKs to users; unique pair; indexes on both sides |
| `blocked_users` | `user_id`, `blocked_user_id` | FKs to users; unique pair |
| `messages` | DM content/type/media/thumbnail/file/duration/`client_message_id`/`reply_to`/read/edited/deleted/`deleted_for`/reactions/location/GIF columns | FKs sender/receiver to users; pair+created_at indexes; unique `(sender_id, client_message_id)` for idempotency |
| `groups` | `id`, name, description, avatar, `created_by` | FK creator to users |
| `group_members` | group, user, `admin|member`, `is_muted`, `joined_at` | FKs; unique `(group_id, user_id)`; group/user indexes |
| `group_messages` | group, sender, content/type/media/thumbnail/`client_message_id`/reply/read-edit-delete flags, reactions, `duration` added in 0003 | FKs to groups/users; `(group_id, created_at)` index |
| `calls` | caller, receiver, `audio|video`, status, offer/answer SDP, duration, timestamps | FKs to users; caller/receiver/created_at and receiver/created_at indexes |
| `statuses` | author, type, content/text/style/thumbnail/duration/caption, `privacy`, `view_count`, `created_at`, `expires_at` | FK to users; user/created_at and expiry indexes; 24 h TTL |
| `status_views` | `status_id`, `viewer_id`, `viewed_at` | FKs; unique `(status_id, viewer_id)` |
| `muted_statuses` | viewer, muted author | composite PK |
| `privacy_settings` | user, last-seen/photo/about/status visibility, read receipts, online status | FK to users; 0002 adds about/online columns |
| `notification_settings` | per-user push/message/call/preview/muted-until flags | FK to users; no route currently uses it |
| `notifications` | user, actor, type, JSON payload, read flag | FK to users; no route currently uses it |
| `push_subscriptions` | Web Push endpoint and keys | FK to users; no route currently uses it |
| `media_attachments` | uploader, `file_path`, name, size, MIME, media type, thumbnail, dimensions, duration | FK uploader to users; no FK to messages |
| `group_read_state` | group, member, `last_read_at` high-water mark | added in 0004; FKs; group/user indexes |
| `group_calls` | group, caller, type, active/ended, timestamps | added in 0005; FK to groups/users |
| `group_call_participants` | call, user, `ringing|joined|declined|left|missed`, join/leave times | added in 0005; composite PK; call/user indexes |

**Referential integrity:** migrations enable `PRAGMA foreign_keys=ON`; Rust `db.rs` sets WAL, foreign keys, busy timeout. JSON arrays such as `deleted_for` and `reactions` are stored in TEXT columns. There is no disappearing-message TTL column, invite-link table, device/key table, OTP table, report table, audit-log table, or migration-version table.

---

## 8. API endpoint inventory

### Cloudflare Worker: 81 method+path endpoints

- Root/health/realtime: `GET /`, `GET /health`, `GET /realtime/connect?room=&token=`.
- Auth: `GET /auth/check-email`, `POST /auth/signup`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`.
- Users: `GET/PATCH /users/me`, `GET /users/username-available`, `GET /users/search`, `GET /users/:id`, `GET /users`, `POST /users/presence`.
- Friends: `POST /friends/request`, `POST /friends/:id/respond`, `DELETE /friends/request/:id`, `GET /friends/requests`, `GET /friends/sent`, `GET /friends`, `DELETE /friends/:friendId`, `POST /friends/block`, `POST /friends/unblock`, `GET /friends/blocked`.
- Direct messages: `GET /messages/with/:otherId`, `GET /messages/chats`, `POST /messages`, `PATCH /messages/:id`, `DELETE /messages/:id`, `POST /messages/:id/delete-for-me`, `POST /messages/:id/reaction`, `POST /messages/mark-read`, `POST /messages/typing`, `POST /messages/recording`.
- Groups: `POST/GET /groups`, `GET/PATCH/DELETE /groups/:id`, `GET/POST /groups/:id/members`, `DELETE/PATCH /groups/:id/members/:userId`, `POST /groups/:id/leave`, `PATCH /groups/:id/mute`, `GET/POST /groups/:id/messages`, `POST /groups/messages/:messageId/delete-for-me`, `POST /groups/messages/:messageId/delete-for-everyone`, `POST /groups/messages/:messageId/reaction`, `POST /groups/:id/typing`, `POST /groups/:id/recording`, `POST /groups/:id/read`.
- Status: `POST /status`, `GET /status/mine`, `GET /status/feed`, `POST /status/:id/view`, `GET /status/:id/views`, `DELETE /status/:id`, `POST /status/mute`, `DELETE /status/mute/:mutedUserId`, `GET /status/muted`.
- Privacy/admin: `GET/PATCH /privacy`, `GET /admin/stats`.
- Media: `POST /media/upload`, `GET /media/file/*`, `DELETE /media/file/*`.
- 1:1 calls: `GET /calls/turn-credentials`, `POST /calls`, `POST /calls/:id/signal`, `POST /calls/:id/answer`, `POST /calls/:id/end`, `GET /calls/history`.
- Group calls: `POST /group-calls`, `POST /group-calls/:id/join`, `POST /group-calls/:id/signal`, `POST /group-calls/:id/decline`, `POST /group-calls/:id/leave`.

### Rust backend: 72 route patterns

The Rust route table in `server/src/routes/mod.rs` mirrors the above with these differences:

- No `GET /auth/check-email` (Android uses `GET /users/username-available`).
- Adds `GET /media/link` for HMAC-signed expiring media URLs and `POST /messages/with/:other_id/delete-for-me` for clearing a whole DM thread.
- Path parameter names differ (`:other_id`, `:message_id`, `:user_id`, `:muted_user_id`, `*key`) but URL shapes are equivalent.
- `/admin/stats` is gated by `NCHAT_ADMIN_EMAIL`, not a hardcoded address.
- `/media/file/*key` enforces conversation/status/avatar authorization by default; `NCHAT_MEDIA_PUBLIC=1` restores public behavior.
- `/realtime/connect` accepts only `user:<authenticated id>`.
- **Phase 1 branch, 2026-11-10 — behaviour only; no route was added, removed or renamed.** The Rust backend now refuses requests that the Worker still accepts: `GET /groups/:id/members` answers 403 to non-members and no longer returns `email` (also gone from `members[]` inside group objects); `POST /groups/:id/leave` answers 403 to non-members; group message reaction, delete-for-me and delete-for-everyone answer 404 to non-members; `POST /status/:id/view` answers 404 when the caller is outside the status audience or the status has expired; `GET /status/:id/views` answers 404 to anyone but the author; `POST /group-calls/:id/decline` and `/leave` answer 403 unless the caller belongs to the call; `POST /group-calls/:id/signal` answers 403 when `to` is not a current member of the call's group; both signal relays drop the body keys listed in `routes::is_signal_envelope_key` and answer 200 without relaying once the call has ended. Reasons and tests: §19.3.

### Android call coverage

`network/NChatApi.kt` declares 73 suspend methods. A textual caller scan found only `declineGroupCall` with no caller outside `NChatApi`. Known deliberate no-app-caller routes from historical wiring docs remain: `GET /groups/:id/members` (the app uses `GET /groups/:id` for the roster), `DELETE /media/file/*`, and `/admin/stats`. The Android app does not call `/media/link`; it sends a bearer token through Coil/`MediaFiles` instead. The historical `docs/BACKEND-WIRING.md` section claiming five uncalled group-call methods is stale: current `NChatApi` implements and mostly calls them.

---

## 9. Authentication and authorization flow

1. **Sign-up / sign-in:** email and password, minimum password length 6, email regex. Cloudflare exposes a public `GET /auth/check-email`; the Android client uses `GET /users/username-available` after sign-in. There is no phone number, OTP, email verification, password reset, two-step verification, account recovery, or account deletion.
2. **Token issue:** `issueTokens()` creates a session row with a random `sid`, HS256 access JWT (`sub`, 15 min) and refresh JWT (`sub`, `sid`, 30 days). Expired sessions are swept on token issuance. Password hash format is `100000:16-byte-salt-hex:32-byte-hash-hex` (PBKDF2-HMAC-SHA256).
3. **Refresh:** single-use rotation guarded by an atomic `DELETE FROM sessions WHERE id=? AND user_id=?`; a replay returns 401. Android `ApiClient` serializes 401 refresh calls through a mutex and retries once. A failed refresh clears the local session.
4. **Logout:** Android clears tokens and account-local state immediately, then best-effort calls `POST /auth/logout` to delete the session row; `NChatApp` disconnects realtime when session becomes null. Offline logout cannot revoke the server row until connectivity returns, so the refresh token remains valid until it is used or expires.
5. **REST authorization:** Cloudflare `requireAuth` verifies `Authorization: Bearer` and sets `userId`; Rust `AppState::current_user` does the same and rejects refresh tokens as API credentials. Both protect most routes, but route-level object authorization is inconsistent (see §19).
6. **WebSocket authorization:** token is in the query string because browser WebSocket upgrades cannot set an Authorization header. Cloudflare verifies JWT and rejects a mismatched `user:<id>` room, but allows arbitrary other room names. Rust fails closed and allows only the caller’s own personal room. Reverse proxies must not log query strings.
7. **Privacy/profile masking:** `applyPrivacy` / `apply_privacy_to_list` redact avatar/bio/last-seen/online according to the target user’s `everyone|contacts|nobody` settings and friendship. Read receipts are suppressed when the reader’s `read_receipts` is off; caller identity is masked in `call:incoming`.
8. **Admin:** Cloudflare hardcodes an owner email in `admin.ts`; Rust requires `NCHAT_ADMIN_EMAIL` and denies all admins when unset. Neither has a device/session admin UI.
9. **CORS:** both backends are permissive (`*`), matching the web client’s separate origin. This is tracked as a production hardening item.
10. **Known missing controls:** no server-side device list, no per-device revocation beyond refresh-token deletion, no brute-force/OTP rate limits in the Worker, no account enumeration protection on `/auth/check-email`, no CSRF/XSS audit for web, no audit log, no report flow, no E2EE.
11. **Status audience (Rust only; Phase 1 branch, 2026-11-10):** `privacy::status_visible_to(owner, viewer, per-status privacy)` is the one rule behind every way a status can be seen — the feed (the same rule written in SQL), the `status:new` push, `POST /status/:id/view`, and the status media file. The author always sees their own status. For anyone else: a block in either direction hides it; a per-status value the server cannot evaluate (`contacts_except`, `only_share_with` — no recipient list is stored) hides it; otherwise both the per-status `privacy` and the author's account-wide `status_visibility` must allow the viewer (`everyone` = anyone, `contacts` = accepted friends, `nobody` = no one), so the stricter of the two wins. The feed additionally lists friends' statuses only and honours the viewer's mute list. `GET /status/:id/views` answers the author only. A refused or expired view answers `404 Status not found`, the same as an unknown id, so ids cannot be probed. The Cloudflare Worker stores both settings and enforces neither (§19.1 #5–#6, §19.3 #21).

---

## 10. Real-time architecture

**Cloudflare:** `GET /realtime/connect?room=<room>&token=<jwt>` verifies the token. It checks only that a room beginning with `user:` equals `user:<token.sub>`; it does not check membership for `dm:`, `group:`, `call:`, or arbitrary rooms. `RealtimeRoom` is a Durable Object with an in-memory `Set<WebSocket>`. It accepts client text frames and relays them to every other socket in the same room; it has no WebSocket Hibernation API, no explicit heartbeat/ping/pong, no authorization on client-sent frames, no durable event sequence, and no backpressure policy beyond a catch/delete on send failure. Server routes mostly call `broadcastToUser`, i.e. they publish to each participant’s personal `user:<id>` room. `roomIdFor`, `groupRoomId`, and `callRoomId` exist in `lib/realtime.ts` but are not used for membership-checked room subscriptions by the Android client.

**Rust:** `realtime::Hub` is an in-process `HashMap<room, HashMap<Uuid, bounded mpsc::Sender>>`. Each socket queue holds 256 messages; slow consumers are removed. `/realtime/connect` rejects every room except `user:<authenticated user>`, ignores inbound text frames, and answers WebSocket ping with pong. A multi-instance deployment needs a shared event bus because the Hub is process-local.

**Android:** `NChatSocket` opens one socket to `user:<id>` with a short-lived access token. It renews the token before a 401 handshake, uses exponential backoff (immediate first retry, capped at 8 s per 1.0.39), wakes on `ensureConnected()`/`onResume`, calls `reconnectNow()` when `NetworkMonitor` sees validated connectivity, and `ServiceLocator` performs `resyncAfterReconnect()` plus outbox flush on reconnect. Typing indicators expire after 6 s; an open conversation polls every 3 s only while the socket is down. A visible Connecting/Reconnecting/Waiting-for-network pill exists. There is no server event ID or cursor; missed-event recovery is a REST resync of chat lists and opened conversations.

**Event coverage observed in source/docs:** `message:new|updated|edit|deleted|delete|reaction|read`, `typing`, `recording`, `group-typing`, `group-recording`, `group-message:*`, `group:created|updated|deleted`, `group-member:*`, `friend:request|accepted|declined|removed`, `status:new|view`, `call:incoming|answered|ended|signal`, and `group-call:incoming|signal|participant-joined|participant-left|participant-declined|ended`. Event names are not authoritative state; REST and database rows are.

---

## 11. Message lifecycle

1. User types or picks media; `ConversationViewModel` calls `ChatRepository`.
2. The repository writes an optimistic message with `DeliveryState.Sending` and a `c-<UUID>` `clientMessageId`, and writes an `OutboxStore` entry before the network request. Voice clips are written to `filesDir/voice-out` first.
3. `ApiClient` sends `POST /messages` or `POST /groups/:id/messages` with snake_case fields. `client_message_id` is the server idempotency key.
4. Cloudflare builds the response payload in memory, then runs the D1 `INSERT` and real-time broadcast concurrently with `Promise.all`. Rust inserts first, then publishes. Neither provides a durable event sequence or transactional outbox.
5. The server response replaces the optimistic row with a real ID/timestamp; the sender’s own socket echo can also reconcile it. `message:read` flips the tick; unread counts are server-owned and refreshed on REST.
6. On failure, `SendRetryPolicy` distinguishes dead radio, transient server error, and permanent refusal. The outbox retries up to five attempts; a failed bubble shows the server’s reason and can be retried. A deleted queued message removes its outbox entry and voice clip.
7. Local `MessageStore` caches the newest 300 messages per account/chat in JSON, painted before the network answer. `ChatListCache`, `GroupListCache`, and `FriendGraphCache` are mirrored from state flows after a complete server answer (`chatsTrusted` / `groupsTrusted` guards). All account-scoped caches are cleared on sign-out.
8. Edits, deletes, tombstones, reactions, replies, forwarding, and read receipts have REST routes and real-time broadcasts. Direct/group deletion semantics differ; group delete-for-everyone has a one-hour window, DM delete-for-everyone is sender-only with no time limit.
9. No disappearing messages, view-once media, server-side message search, chat export, or per-recipient read table exists. Starred messages, drafts, pinned messages, archive, and wallpaper are deliberately device-local.

---

## 12. Media lifecycle

1. Android picks/captures a file, strips EXIF/GPS, downscales photos to 1600 px JPEG 85, produces a 320 px thumbnail, and streams `multipart/form-data` to `POST /media/upload` with a determinate progress ring.
2. Cloudflare stores the object in R2 at `<userId>/<newId>.<ext>` and records `media_attachments`; it returns an absolute `/media/file/<key>` URL built from `PUBLIC_API_URL`. Rust stores under `NCHAT_DATA_DIR/media/<key>` and applies `NCHAT_MAX_UPLOAD_MB` (default 100).
3. Messages carry `media_url`, `thumbnail_url`, `type`, `fileName`, `fileSize`, `duration`; web-originated rows may put the URL in `content`, so the Kotlin mapper falls back to `content`.
4. Cloudflare `GET /media/file/*` is **public** and has `cache-control: public, max-age=31536000, immutable`, `accept-ranges: bytes`, and 206 `content-range` support. This is a confirmed high-risk finding.
5. Rust `GET /media/file/*key` is private by default: it accepts a signed `?exp=&sig=` link from `/media/link` or checks the bearer token against uploader, DM participants, group membership, unexpired status audience/privacy, or avatar usage; strangers get 404. It streams bytes from disk and validates path traversal (`..`, absolute paths, backslashes). `DELETE /media/file/*` is uploader-only in Cloudflare and owner-only in Rust tests.
6. Android `MediaImages` adds `Authorization: Bearer` only for the configured origin, renews once on 401, and caps the Coil disk cache at 128 MB. `MediaFiles` downloads audio/video through the authenticated client to a local file because `MediaPlayer`/`VideoView` cannot attach the header. It checks audio magic numbers and caches played clips.
7. No storage quota, duplicate-upload deduplication, malware scanning, MIME allowlist/signature verification, upload cancellation API, media cleanup job, expiring Cloudflare signed URL, or public/private cache separation exists. Thumbnail/waveform JSON in `thumbnail_url` is a compatibility hack, not a typed media schema.

---

## 13. Push notification architecture

**Implemented:** Android local system notifications via `NcNotifications`: per-chat channels, mute-aware importance, `MessagingStyle` with the last six lines, sender photos through authenticated Coil, inline `RemoteInput` reply, group summary and launcher badge, tap deep links to a specific message/call, friend-request/group/call/missed-call alerts, notification actions, and `POST_NOTIFICATIONS` runtime request. `BackgroundSyncService` is a `dataSync` foreground service with `START_STICKY` and a 60 s watchdog that keeps the existing realtime socket alive when the app is not in the foreground; a visible low-importance “connected” notification and optional battery-exemption request disclose that cost. `NotificationReplyReceiver` and `CallActionReceiver` are not exported.

**Not implemented:** FCM, native high-priority push when the process is dead, server-side push token registration/refresh/invalid-token cleanup, Web Push routes, notification deduplication across devices, delivery receipts for push, or use of `notification_settings` / `notifications` / `push_subscriptions` tables. `VAPID_*` are declared in Cloudflare `Env` and docs but no worker route uses them. Do not describe this architecture as real push parity; it is a foreground-socket/local-notification workaround.

---

## 14. Audio/video calling architecture

**1:1:** `CallRepository` + `WebRtcEngine` use `org.webrtc`. The caller creates an offer and posts it with `POST /calls`; the callee receives `call:incoming`, answers with `POST /calls/:id/answer`, and the caller receives `call:answered`. ICE candidates trickle through `POST /calls/:id/signal` and are queued if they arrive before a peer connection exists. `GET /calls/turn-credentials` mints short-lived Cloudflare Realtime TURN credentials server-side; public STUN is fallback. State is Ringing → Connecting → Live → Ended; the timer starts only on ICE/DTLS connect. Guard rails: 45 s ring, 20 s connecting, 30 s disconnect grace with visible Reconnecting state. Microphone/camera permissions are requested before dialing/answering. Mute, speaker, camera flip, foreground call UI, and call history exist.

**Group calls:** `/group-calls` routes and `group_calls` / `group_call_participants` tables support start/join/signal/decline/leave and participant events. `GroupCallSession` holds one audio source/track and one `PeerConnection` per participant, with perfect-negotiation glare handling. Media topology is a **mesh with no SFU**; group **video** calls are explicitly out of scope. Group call invites render a card with Join / Not now and expire after 45 s. `declineGroupCall` is currently an uncalled API method.

**Verification limit:** source and signaling tests exist, but no controlled two-device test in this session proves actual audio/video media flow, TURN relay across NATs, Bluetooth routing, background call continuity, or group-call scaling. A successful signaling exchange is not evidence that media flowed.

---

## 15. Encryption and key management

- **Transport:** release APK is HTTPS-only by default; debug manifest permits cleartext for local Rust development. WebSocket derives `wss://` from the same root. TLS should be terminated by Cloudflare or a trusted reverse proxy for the Rust server.
- **Credentials:** access/refresh tokens and identity are stored in `SharedPreferences` wrapped by `SecurePrefs` AES-256-GCM with an Android Keystore key; legacy plaintext values are read defensively and re-encrypted. If Keystore is unavailable the store degrades to plaintext rather than locking the user out; `isEncrypted` reports the state.
- **Server secrets:** `JWT_SECRET` / `NCHAT_SECRET` sign JWTs and Rust media links. Rust warns and uses an ephemeral key if unset. Cloudflare secrets are supplied by Wrangler/dashboard. CORS is permissive. Neither backend has a KMS/HSM, secret rotation procedure, or certificate pinning.
- **Password hashes:** PBKDF2-HMAC-SHA256, 100,000 iterations, 16-byte salt, 32-byte derived key, byte-compatible between Worker and Rust. Current OWASP guidance recommends 600,000 iterations for PBKDF2-HMAC-SHA256 [1](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Migration must preserve the stored iteration prefix and be tested against a database copy.
- **Signing:** `.github/nchat-demo-keystore.b64` and the workflow’s public demo key/password are intentionally committed for update compatibility. The pinned certificate SHA-256 is `429c0bb9664496faaa275638852d4e6b366bfb203c4e5c6d6b60101106e67912`. Any differently signed APK cannot update existing installs. This key is not a secure production credential.
- **Secret hygiene:** a historical Cloudflare dashboard setup guide in the backend repository contains a ready-made static JWT secret example. If that value was ever used, treat it as exposed and rotate it with explicit authorization. This document deliberately does not repeat secret values.
- **E2EE:** none. Both backends store plaintext message content and media. There are no identity keys, device keys, prekeys, sessions, safety numbers, encrypted attachments, encrypted backups, or key verification. TLS/JWT/Keystore storage must not be described as E2EE.

---

## 16. Android application architecture

- Single Activity (`MainActivity`), `NChatApplication` installs crash logging and the authenticated Coil loader. `NChatApp` gates MissingServer → Auth → ProfileSetup → Main, opens realtime on session, refreshes profile, and sends a presence heartbeat every 60 s.
- Navigation is a custom `NcNavigator` with a `HomeTab` and a `SnapshotStateList` back stack, not Navigation-Compose. Tabs: Chats, Status, Groups, Discover, History. Conversation, Settings, profile, cropper, and group screens are pushed onto the stack.
- `ServiceLocator` is the composition root: `BuildConfig.NCHAT_API_BASE` → `ApiClient`/`NChatSocket` → `NChatApi` → remote repositories (auth, profile, chat, friend, status, call, preferences). There are no in-memory demo repositories or seeded fake data in current source.
- ViewModels expose `StateFlow`; repositories merge REST, socket events, local caches, and outbox state. `RemoteChatRepository.kt` is 2,634 lines and is a known decomposition debt.
- Local JSON stores: `ChatListCache`, `GroupListCache`, `FriendGraphCache`, `MessageStore`, `OutboxStore`, `BlockedContactsStore`, `DraftStore`, `StarredStore`, `PinnedMessageStore`, `ArchivedStore`, `WallpaperStore`, `VoiceClipStore`, `StorageReport`. These are account-scoped and cleared on sign-out; no Room/SQLCipher local database.
- Permissions declared: INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO, CAMERA, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT, REQUEST_INSTALL_PACKAGES, POST_NOTIFICATIONS, FOREGROUND_SERVICE(_DATA_SYNC), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, coarse/fine location. Runtime requests exist for notifications, call mic/camera, location, gallery/camera pickers, and battery exemption. There is no privacy-policy/terms onboarding, contacts permission/sync, or comprehensive permission-denial recovery UI.
- Startup/update: `StartupUpdateGate` reads `version.json`, shows a full-screen update page with release notes and live progress, falls back to Android DownloadManager for direct-transfer timeouts, verifies package/version/signing certificate, and installs via non-exported `FileProvider`. *Update 2026-11-10 (1.0.75, Phase 1 branch):* the page also looks for a release when the app returns to the foreground — `MainActivity.onResume` → `UpdateForeground` → `updateCheckDue` (six hours after an answer, one minute after a failed look) — and a look that finds a release makes the offer again; the “Install unknown apps” step is shown as a step, and the install continues by itself on return; the signer of a file is asked for with both PackageManager flags on Android 9+ (§19.4 #24). The gate is still inside `HomeRoot`, so only signed-in users see it.
- Accessibility/dark mode/tablet/landscape: no dedicated implementation or tests found. `configChanges` handles orientation/size/uiMode in the Activity; resource configurations are English-only.
- Crash handling: `CrashLog` writes `last-crash.txt` and an external copy; `CrashReportScreen` displays it on next launch. `ApiLog` records request path/status/duration/error and Settings ▸ Sync check probes endpoints.

---

## 17. UI/UX design system

Preserved exact design decisions from `eagle-x.md`:

| Token | Value |
|---|---|
| Brand gradient | `#8B5CF6 → #A855F7 → #D946EF` |
| Body gradient | `#F5F3FF → #FAF5FF → #FDF4FF` |
| Own bubble | violet → purple, white text |
| Composer | `#A855F7 → #8B5CF6 → #7C3AED`, radius 28 dp |
| Incoming bubble | white, slate text, 1 dp hairline |
| Typing indicator | violet-500; read ticks sky-500; online dot `#22C55E` |
| Glass | `rgba(255,255,255,.22)` + 18 px blur + `saturate(150%)` + border opacity `.55` |
| Error / success banners | `#FEF2F2`/`#FECACA`/`#DC2626`; `#F0FDF4`/`#BBF7D0`/`#16A34A` |
| Group call background | `#0F172A → #2E1065 → #0F172A` |

Shape/spacing: chat avatar 56 dp/radius 16 dp, header avatar 40 dp circle, group sender avatar 30 dp, bubbles radius 16 dp with 6 dp tail and max width 300 dp, meta 10 sp, screen padding 16 dp, list rows separated by 2 dp. Signature motion curve `cubic-bezier(0.16, 1, 0.3, 1)`; tab chip tints 120 ms, scale 140 ms, press 90 ms; overlays 200–260 ms; typing dots 1400 ms cycle with 150 ms stagger. Tab switching is a plain composition swap; backgrounds are static; no per-row entry animations, no per-row shadows, no snapshot-backed layout maps. Lists must use stable keys, hoisted `LazyListState`, one flexible weighted child, single-line tokens with ellipsis, and fixed leading/trailing columns.

---

## 18. Feature implementation matrix

### 18.1 Primary status table

All dates are 2026-11-09 unless a row says otherwise: rows touched by the Phase 1 Rust work are dated 2026-11-10 and mark what was added. (Corrected on 2026-11-10; this sentence used to read “All dates are 2026-11-09.”) “Evidence” means source inspection, local test output, or CI result; it does not mean unverified end-to-end product behavior.

| Feature | Status | Implementation location | Evidence | Remaining work | Last updated |
|---|---|---|---|---|---|
| Phase 0 audit + `agent.md` | COMPLETED | `agent.md` | This document; verification log §36 | Append future handover entries | 2026-11-09 |
| Markdown consolidation | COMPLETED | root/docs/server Markdown removal | Appendix D ledger; Git diff after commit | None | 2026-11-09 |
| Android signed APK CI at main head | COMPLETED | `.github/workflows/build-apk.yaml` | check-run `build` success on `d87b703`; run `42584666249` | Keep green on session branch | 2026-11-09 |
| Worker TypeScript typecheck | COMPLETED | `worker/src/**` | `npm run typecheck --prefix worker` exit 0 in fresh clone | Add authorization tests | 2026-11-09 |
| Web frontend typecheck/build | COMPLETED | `nchat-friends/src/**` | `npm run type-check`, `npm run build` exit 0; 1649 modules | Visual/live-device QA | 2026-11-09 |
| APK signing verification unit tests | COMPLETED | `.github/scripts/test_verify_release_apk.py` | `python3 -m unittest ...`: 7 tests OK | None for current scope | 2026-11-09 |
| Repository/branch inventory | COMPLETED | Git/GitHub API | §2, §3, §36 | Refresh each session | 2026-11-09 |
| Competitor comparison + roadmap | COMPLETED | §18.2, §18.3, §33 | This document; public sources in Appendix F | Update as features land | 2026-11-09 |
| Rust backend integration tests at HEAD | COMPLETED | `server/tests/*.rs`, `.github/workflows/backend.yml` | 18 tokio tests defined; last green run `42491305703` at prior SHA; no run on `d87b703` **2026-11-10:** run `42779970114` green on `main` `a867fdd` (the 18 tokio tests). Phase 1 branch: 63 tokio integration tests (`api` 9, `authz` 45, `groups_status_calls` 4, `media` 5) + 16 unit tests, `cargo test` green on `a1d2398`, run `42794684951` | Run on HEAD; add authorization cases **2026-11-10:** both done on the Phase 1 branch (not merged). Add a refusal test with every new route. Green logs cannot be read from the sandbox: the counts come from the source and from the two red logs | 2026-11-10 |
| Cloudflare Workers deployment | BLOCKED | dashboard Workers Builds | two check-runs failed on `616a9e6`; no dashboard log access | Dashboard/build-log access, fix and redeploy with authorization | 2026-11-09 |
| Email/password registration | IMPLEMENTED_UNVERIFIED | `feature/auth`, `RemoteAuthRepository`, `/auth/signup` | Source + Rust `signup_login_refresh_logout` test | Manual two-device journey; error/duplicate/expired-session tests | 2026-11-09 |
| Phone/OTP registration | NOT_STARTED | N/A | no OTP/phone columns or routes | Full design, delivery, rate limits, expiry, brute-force tests | 2026-11-09 |
| Login + session restore | IMPLEMENTED_UNVERIFIED | `TokenStore`, `NChatApp`, `/auth/login` | Source + Rust auth test | Offline/suspended/revoked-token/manual scenarios | 2026-11-09 |
| Refresh-token rotation / logout | IMPLEMENTED_UNVERIFIED | `ApiClient`, `/auth/refresh`, `/auth/logout` | Source + Rust auth test | Offline logout, old-token rejection, second-device tests | 2026-11-09 |
| Device/session management | NOT_STARTED | N/A | `sessions.user_agent` exists but no UI/API | Dedicated session model, list/revoke, login alerts | 2026-11-09 |
| Account deletion / data export | NOT_STARTED | N/A | no route/table | Design with retention, media cleanup, authorization tests | 2026-11-09 |
| Password reset / 2FA / recovery | NOT_STARTED | N/A | no route/table | Full secure flow | 2026-11-09 |
| Profile setup and management | PARTIALLY_IMPLEMENTED | `ProfileSetupScreen`, `ProfileScreen`, `RemoteProfileRepository` | Source + `ProfileSetup*` unit tests | Phone/email visibility, server sync of all settings, cross-device tests | 2026-11-09 |
| Onboarding privacy/terms/permissions | PARTIALLY_IMPLEMENTED | `MainActivity`, `CallPermissions`, `ServiceLocator` | Manifest + runtime requests | Privacy policy, ToS, first-launch explanation, denial recovery, accessibility | 2026-11-09 |
| Chat list, unread, previews, pin, mute, archive | IMPLEMENTED_UNVERIFIED | `ChatsScreen`, `RemoteChatRepository`, local stores | Source + `ChatPreviewTest` etc. | Server-backed pin/archive/mute where required; UI tests | 2026-11-09 |
| One-to-one messaging | IMPLEMENTED_UNVERIFIED | `NChatApi`, `RemoteChatRepository`, `/messages/*` | Source + Rust `messaging_flow` test **2026-11-10:** (Rust, Phase 1 branch) `authz.rs`: a stranger gets 403 on send, typing, recording and mark-read; a third party gets 404 on edit, delete, react and delete-for-me of someone else's message and reads nothing of the conversation | Two-device E2E, authorization tests, failure injection **2026-11-10:** Rust authorization tests exist; the Worker still accepts a DM without friendship or block checks (§19.2) | 2026-11-10 |
| Delivery/read status + typing/recording | IMPLEMENTED_UNVERIFIED | `DeliveryGate`, `NChatSocket`, `/messages/*` | Source + `DeliveryGateTest` | Multi-device receipt/typing expiry tests | 2026-11-09 |
| Offline outbox / local caches / resync | IMPLEMENTED_UNVERIFIED | `OutboxStore`, `MessageStore`, `NetworkMonitor`, `NChatSocket` | Source + `OutboxStoreTest`, `MessageStoreTest`, `NetworkMonitorTest` | Process-death, duplicate-prevention, conflict tests | 2026-11-09 |
| Multi-device synchronization | PARTIALLY_IMPLEMENTED | personal-room fan-out, account-scoped caches | Source; no device registry | Linked-device identity, read-state sync, pending-message safety | 2026-11-09 |
| Reply/forward/edit/delete/reactions | IMPLEMENTED_UNVERIFIED | `RemoteChatRepository`, `MessageActionsMenu`, `/messages/*`, `/groups/messages/*` | Source + policy unit tests **2026-11-10:** (Rust, Phase 1 branch) group reaction, delete-for-me and delete-for-everyone require current membership (`0ec858c`); DM message actions were already participant-checked; both are covered by `authz.rs` | Server authorization tests, tombstones, edit-history policy **2026-11-10:** server authorization tests done for Rust; Worker unchanged (§19.1 #7, §19.3 #19). Forwarded media can become unreadable on Rust (§19.3 #23) | 2026-11-10 |
| Star/pin/search/multi-select/drafts/wallpaper | PARTIALLY_IMPLEMENTED | device-local stores and `ChatSearchOverlay` | Source + historical docs | Decide which should be server-synced; no wire format today | 2026-11-09 |
| Media upload/download/playback | IMPLEMENTED_UNVERIFIED | `MediaPreparer`, `MediaImages`, `MediaFiles`, `/media/*` | Source + Rust media tests | Cloudflare private media, MIME/quota/cleanup, progress/failure E2E | 2026-11-09 |
| Voice messages | IMPLEMENTED_UNVERIFIED | `VoiceRecorder`, `VoiceWaveform`, `VoiceClipStore`, `MessageBubble` | Source + `VoiceClipStoreTest` | Playback interruption, seek, speed, background, upload recovery tests | 2026-11-09 |
| Friends/requests/blocking | PARTIALLY_IMPLEMENTED | `DiscoverScreen`, `RemoteFriendRepository`, `/friends/*` | Source + Rust friend test **2026-11-10:** (Rust, Phase 1 branch) `authz.rs`: only the receiver can answer a friend request; unfriending someone who is not a friend sends them nothing (`d247d39`) | Reconcile destructive Worker block vs device-local/Rust block; auth tests **2026-11-10:** Rust auth tests added; block-policy reconciliation and the Worker untouched | 2026-11-10 |
| Contact synchronization | NOT_STARTED | N/A | no contacts permission/sync | Permission, hashing, privacy, server matching | 2026-11-09 |
| Group creation, roster, roles, messages | IMPLEMENTED_UNVERIFIED | `Group*Screen`, `RemoteChatRepository`, `/groups/*` | Source + Rust group lifecycle test **2026-11-10:** (Rust, Phase 1 branch) member list and every message action are members-only, leaving requires membership, member e-mails are no longer returned (`0ec858c`); 11 group tests in `authz.rs` | Fix member-list/reaction authorization; invite links; role tests **2026-11-10:** member-list and reaction authorization fixed in Rust only; all of it is still open in the Worker (§19.1 #4, #7; §19.3 #11, #17, #19) | 2026-11-10 |
| Invite links / join requests / ownership transfer | NOT_STARTED | N/A | no table/route | Schema migration + authorization + UI | 2026-11-09 |
| Status/stories creation/viewing/views/mute | PARTIALLY_IMPLEMENTED | `StatusScreen`, `StatusViewer`, `StatusRepository`, `/status/*` | Source + Rust status test **2026-11-10:** (Rust, Phase 1 branch) one audience rule for feed, `status:new`, view, viewer list and media (`b647f5a`, §9 item 11); 14 status tests in `authz.rs` and a truth-table unit test | Privacy enforcement on feed/direct/media; viewer ownership; E2E **2026-11-10:** privacy enforcement and viewer ownership done in Rust; Worker unchanged; selected/excluded-contact audiences have no schema (Rust shows such a status to its author only); E2E still missing | 2026-11-10 |
| Global user / in-chat / media search | PARTIALLY_IMPLEMENTED | `/users/search`, `ChatSearchOverlay`, `ChatMediaSheet` | Source + web username-search commit | Server message search, filters, pagination, privacy tests | 2026-11-09 |
| 1:1 WebRTC calls | IMPLEMENTED_UNVERIFIED | `WebRtcEngine`, `CallRepository`, `/calls/*` | Source + Rust call signaling test **2026-11-10:** (Rust, Phase 1 branch) a signal body cannot rewrite the event around it, and an ended call relays nothing (`84ceec5`, `feb3600`); 4 call tests in `authz.rs` | Actual media-flow E2E, TURN across NAT, background call **2026-11-10:** the Worker relay still spreads the request body over the event (§19.3 #12) | 2026-11-10 |
| Group voice calls | PARTIALLY_IMPLEMENTED | `GroupCallSession`, `/group-calls/*` | Source + Rust group-call test **2026-11-10:** (Rust, Phase 1 branch) decline and leave require belonging to the call; signals reach only current members of the call's group, cannot rename their call or sender, and stop when the call ends (`84ceec5`, `feb3600`); 9 group-call tests in `authz.rs` | Group video, decline caller, SFU evaluation, scale tests **2026-11-10:** Worker unchanged (§19.3 #12–#16). Android matches group-call events by name fragments and never removes a leaver (§24 item 18). Removing a member from a group does not end their part in a running call | 2026-11-10 |
| Group video calls / SFU | NOT_STARTED | N/A | mesh/audio-only comments | SFU or bounded mesh with measured capacity | 2026-11-09 |
| Local + background notifications | IMPLEMENTED_UNVERIFIED | `NcNotifications`, `BackgroundSyncService`, manifest | Source; historical release notes | Doze/OEM/notification-channel/permission-denial tests | 2026-11-09 |
| FCM / real server push | NOT_STARTED | N/A | no Firebase dependency/route | Token lifecycle, retry, dedupe, background delivery tests | 2026-11-09 |
| Privacy settings backend enforcement | PARTIALLY_IMPLEMENTED | `/privacy`, `lib/privacy.ts`, `server/src/privacy.rs` | Source + Rust privacy redaction test **2026-11-10:** (Rust, Phase 1 branch) the account-wide `status_visibility` is now applied to statuses (`b647f5a`, test `account_level_status_visibility_is_enforced`) | Status privacy, read receipts across devices, group/profile edge cases **2026-11-10:** status privacy done in Rust; the Worker stores `status_visibility` and never reads it in `status.ts` | 2026-11-10 |
| Blocking enforcement | PARTIALLY_IMPLEMENTED | `/friends/block`, `BlockedContactsStore`, Rust `ensure_direct_access` | Source + Rust tests **2026-11-10:** (Rust, Phase 1 branch) a block in either direction now also hides statuses, their media and the `status:new` push (`b647f5a`, four tests). A block on DMs was already enforced and tested (`api.rs`); a block on ringing was already enforced and is now guarded by `blocked_user_cannot_ring` | Make both backends non-destructive and consistent **2026-11-10:** unchanged — the two backends still block differently | 2026-11-10 |
| WebSocket room authorization | PARTIALLY_IMPLEMENTED | `worker/src/index.ts`, `server/src/realtime.rs` | Rust fails closed; Cloudflare partial | Cloudflare room membership/fail-closed, DO hibernation, frame validation | 2026-11-09 |
| Private media authorization | PARTIALLY_IMPLEMENTED | `server/src/routes/media.rs`, `worker/src/routes/media.ts` | Rust tests pass in last CI; Cloudflare public read **2026-11-10:** (Rust, Phase 1 branch) status media follows the status audience, including blocks and audiences the server cannot evaluate (`b647f5a`, three tests in `authz.rs`) | Cloudflare private R2/Worker proxy + signed links + tests **2026-11-10:** Cloudflare work unchanged. Rust: media that is referenced from more than one kind of conversation is over-restricted (§19.3 #23, discovered, not fixed) | 2026-11-10 |
| Rate limiting / brute-force protection | PARTIALLY_IMPLEMENTED | `server/src/ratelimit.rs`, web client local limiter | Rust auth limiter source; no Worker limiter | Durable/edge rate limits, IP/device/account limits, tests | 2026-11-09 |
| Input validation / abuse prevention | PARTIALLY_IMPLEMENTED | route bodies, `MediaPreparer`, `validation.ts` | Source | Central schemas, MIME/size, SSRF/path traversal, fuzz tests | 2026-11-09 |
| Database migrations | PARTIALLY_IMPLEMENTED | five SQL files, `schema.sql` | §25 and package scripts | Version table/runner, 0003–0005 scripts, copy-based migration tests | 2026-11-09 |
| Backup/restore | NOT_STARTED | N/A | docs only recommend backup | SQLite-aware backup, media backup, restore drill | 2026-11-09 |
| Audit logs / monitoring / alerting | PARTIALLY_IMPLEMENTED | `ApiLog`, `CrashLog`, tracing | Source | Server audit events, structured metrics, alerting, retention | 2026-11-09 |
| E2EE | NOT_STARTED | N/A | no keys/sessions/ciphertext | Signal Protocol evaluation, device keys, group encryption, tests | 2026-11-09 |
| Disappearing / view-once media | NOT_STARTED | N/A | no TTL/view-once columns | Schema, enforcement, client expiry, media deletion | 2026-11-09 |
| Report user/message | NOT_STARTED | N/A | no route/table | Report flow, moderation, privacy-preserving evidence | 2026-11-09 |
| Admin/stats | IMPLEMENTED_UNVERIFIED | `/admin/stats` | Source; no Android surface **2026-11-10:** (Rust, Phase 1 branch) test `admin_stats_are_denied_to_ordinary_accounts` | Role-based auth instead of hardcoded email; tests **2026-11-10:** a Rust denial test exists; role-based authorization still open in both backends | 2026-11-10 |
| In-app updater | IMPLEMENTED_UNVERIFIED | `core/update/*`, `feature/update/UpdateGate.kt`, `version.json`, workflow | Source + 7 Python signing tests + green Android CI. *2026-11-10:* repaired on the Phase 1 branch as 1.0.75 (§19.4 #24–#27); 22 JVM tests on the pure rules, one of them seen failing before the signer fix | Install/upgrade on a real device (never done in any recorded session), mirror/failure tests, the page behaviour added in 1.0.75 | 2026-11-10 |
| UI/UX design system | IMPLEMENTED_UNVERIFIED | `core/designsystem/*`, feature screens | Source + historical design rules | Compose UI tests, accessibility, dark mode, tablet, low-end profiling | 2026-11-09 |
| Accessibility / dark mode / tablets | NOT_STARTED | N/A | no dedicated implementation/tests | Full audit and implementation | 2026-11-09 |
| Performance/load measurement | NOT_STARTED | N/A | no measured results in repo | Instrumented API/DB/WS/media/call/Android benchmarks | 2026-11-09 |
| End-to-end/manual scenario tests | NOT_STARTED | N/A | backend checklist exists but no execution evidence | Scenarios in §29 on real devices/staging | 2026-11-09 |
| Phase 1 critical security, both backends (§33) | IN_PROGRESS | `server/src/routes/*`, `server/src/privacy.rs`; Worker `worker/src/routes/*` | Rust half done and green in CI on the Phase 1 branch (run `42794684951`); Worker half not started | Worker items 1–5 of §33 in `nchat-friends`; items 6–8 in both; residual risks in §19.3; merge the branch | 2026-11-10 |
| Rust authorization regression suite | COMPLETED | `server/tests/authz.rs` (45 tests) | Shown failing first (run `42790409920`: 27 of 42 failed; run `42794405563`: 4 of 45 failed), then green with the fixes (runs `42791596474`, `42792659202`, `42794684951`) | Not on `main` until the branch is merged; the Worker has no equivalent | 2026-11-10 |

### 18.2 Competitor comparison matrix

Publicly documented patterns only; no proprietary source, private protocol, or protected asset is copied. WhatsApp documents E2EE, privacy controls, two-step verification, disappearing messages, group privacy, and linked-device E2EE [1](https://faq.whatsapp.com/898107234497196) [1](https://faq.whatsapp.com/3307102709559968/?cms_platform=web) [4](https://engineering.fb.com/2021/07/14/security/whatsapp-multi-device/). Signal documents safety numbers, disappearing messages, delivery/read states, and encrypted backups [2](https://support.signal.org/hc/en-us/articles/360007060632-What-is-a-safety-number-and-why-do-I-see-that-it-changed) [1](https://signal.org/blog/disappearing-messages/) [4](https://support.signal.org/hc/en-us/articles/360007320751-How-do-I-know-if-my-message-was-delivered-or-read). Telegram distinguishes cloud chats (multi-device, server-stored) from device-specific secret chats (E2EE, self-destruct, no forwarding) and documents 2-step verification [2](https://telegram.org/faq) [1](https://core.telegram.org/tsi/e2ee-simple). Messenger documents default E2EE rollout for personal messages/calls and secure storage/PIN recovery [1](https://about.fb.com/news/2024/03/end-to-end-encryption-on-messenger-explained/). Discord documents role/channel permissions, SFU-based voice/video, and DAVE/MLS E2EE for audio/video while text remains moderated [1](https://discord.com/community/permissions-on-discord-discord) [1](https://discord.com/blog/meet-dave-e2ee-for-audio-video). WeChat documents ID/phone friend discovery, Moments, and group voice/video calls [1](https://cs.help.wechat.com/hc/en-us/articles/11901073289999-Welcome-to-WeChat).

| Product | Message/group model | Calls | Temporary media | Security model | Sync/offline | NChat gap |
|---|---|---|---|---|---|---|
| WhatsApp | E2EE 1:1/groups, privacy controls, disappearing messages, linked devices | E2EE voice/video | Status, view-once, disappearing media | Signal Protocol, key verification, optional encrypted backups | Multi-device client fan-out, local history | No E2EE, device model, disappearing/view-once, verified key UX |
| Telegram | Cloud chats + opt-in device-specific secret chats, huge groups/channels | voice/video/group calls | Stories/self-destruct secret media | Cloud chats server-stored; secret chats MTProto E2EE | Cloud multi-device; secret chats single-device | No secret-chat mode, no cloud/secret split, weaker status privacy |
| Signal | E2EE 1:1/groups, read/delivery states, disappearing messages | E2EE calls | disappearing messages, encrypted backups | Signal Protocol, safety numbers, sealed-sender patterns | linked devices, local-first | No protocol, safety numbers, encrypted backup, E2EE search tradeoff |
| Messenger | personal E2EE chats/calls, secure storage | E2EE calls | view-once media | default E2EE rollout, PIN/secure storage | multi-device history via secure storage | No E2EE or secure-storage recovery |
| WeChat | friend requests, Moments, broad social/payment ecosystem | group voice/video | Moments | no public default-E2EE claim in reviewed docs | phone-centric + desktop | No contacts/payments/mini-app analogue; NChat has friend graph/status |
| Discord | servers/channels, roles/permissions, threads | SFU voice/video, DAVE E2EE A/V | no story equivalent | text moderated/non-E2EE; A/V E2EE | cloud history, channel permissions | No role hierarchy, channel model, SFU, A/V E2EE |
| NChat | email accounts, accepted friends, DM + groups, local JSON cache/outbox | WebRTC mesh/P2P + TURN, group voice only | 24 h status text/photo/video | TLS, JWT, PBKDF2, Keystore tokens; plaintext server content | one socket per account, REST resync, device-local state | Critical security fixes, E2EE, push, device model, privacy enforcement |

### 18.3 Twelve-point parity analysis for major feature groups

| Major feature | 1 Competitor behavior | 2 User sees | 3 Behind the scenes | 4 Data stored | 5 Real-time sync | 6 Security controls | 7 Network failure | 8 Multi-device | 9 NChat current | 10 Missing | 11 Recommended implementation | 12 Required tests |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1:1 messaging | WhatsApp/Signal/Messenger encrypt and sync; Telegram cloud syncs | bubbles, ticks, typing, read state | encrypted session or cloud queue + fan-out | ciphertext or server rows + local cache | per-device events, resync | E2EE/participant auth/block/rate limits | local outbox, retry, dedupe | pairwise device sessions or cloud history | REST D1/SQLite rows, optimistic bubble, outbox, personal-room events | E2EE, durable event cursor, complete auth on Worker | keep DB authoritative; add sequence/cursor, auth matrix, then protocol work | unit, API auth, offline/process-death, two-device, duplicate, ordering |
| Groups | roles, add/remove, invite controls, admin restrictions; Discord channel permissions | roster, role badges, member actions | membership table + server authorization | group/member/message/read-state rows | events to every member | membership + role checks on every route | queued sends, resync | shared cloud state or encrypted sender keys | create/rename/roles/messages/read-state exist | invite links, ownership transfer, granular permissions, member-list/reaction auth | enforce membership server-side for all routes, add role matrix and migrations | membership/IDOR tests, role transitions, leave/delete concurrency |
| Media | compressed image/video/voice/docs, thumbnails, view-once, quota | progress, preview, playback | object storage + signed/authorized fetch | object + metadata + local cache | message event with media URL | private object, MIME/size, expiry, membership | resumable/retry upload, local clip | shared URL or encrypted attachment | upload/R2/files, Coil/MediaFiles, range reads | Cloudflare private reads, MIME validation, quota, cleanup, encrypted attachments | Worker proxy or private R2 + expiring links; typed attachment table | unauthorized URL, signed-link expiry, Range, MIME confusion, large-file, retry |
| Calls | P2P/SFU, E2EE A/V, call history, background ringing | ring/accept, mute/video, reconnect | signaling + ICE/TURN + media transport | call rows, ephemeral keys | signaling events | participant auth, TURN secrecy, media encryption | ICE reconnect, missed/busy cleanup | per-device call state or SFU | WebRTC 1:1, mesh group voice, TURN, history | group video, SFU, A/V E2EE, full-screen lockscreen, proven media flow | measure mesh limit, evaluate SFU, add call-state sequence and media tests | two-device audio/video, NAT/TURN, mute/camera, interruption, group join/leave |
| Status | 24 h stories/Moments, privacy audience, viewer list, reply | ring, segmented viewer, viewers sheet | expiring rows + view events | status/view/mute rows | `status:new/view` | feed + direct media + viewer ownership | cached feed, upload retry | cloud feed or encrypted sync | text/photo/video, 24 h, view count/list, mute | privacy setting enforcement, selected/excluded contacts, owner-only viewers | enforce `privacy` in feed, view, views, media; add audience table | unauthorized feed/view/viewers/media, expiry, muted-author, cross-device |
| Notifications | FCM/APNs/Web Push, channels, grouping, deep links, dedupe | shade, inline reply, call alert | push gateway + token registry | tokens, prefs, delivery IDs | push + socket reconciliation | token validation, mute, payload minimization | retry, invalid-token cleanup | per-device token | local notifications + foreground socket | real push, token lifecycle, dedupe, delivery receipt | FCM/Web Push service, token table, idempotent notification IDs | killed-app delivery, token rotation, mute, deep link, duplicate |
| Privacy/security | E2EE, 2FA, device list, blocking, report, disappearing | privacy settings, safety numbers, active sessions | server policy + client keys | keys, sessions, settings, audit log | sync settings and device changes | server enforcement, not UI gating | offline policy cache | per-device keys/sessions | profile/read-receipt/online masking, block, privacy route | 2FA, device list, account deletion, report, E2EE, consistent blocking | fix current auth gaps first, then device/session and account controls | authorization matrix, privacy redaction, revoked-token, 2FA, report |
| Search/offline | local + server search, filters, pagination; offline-first cache | instant results, empty/error states, pending sends | local index + server query | cached messages, outbox, cursors | event + cursor sync | conversation participant filters | cache-first, outbox, resync | shared state/read status | user search, in-chat local search, media index, JSON caches/outbox | server message search, global filters, cursor pagination, encrypted-search strategy | add cursor/event IDs, typed local DB, server search with participant checks | privacy leak, pagination, debounce, stale result, offline process death |
| Multi-device | linked devices, history transfer, read-state sync, security codes | device list, same chats everywhere | pairwise encrypted fan-out or cloud sync | device identities, keys, app-state | events to all devices | device verification/revocation | independent reconnect + resync | core model | one `user:<id>` room broadcasts to all same-account sockets; caches per account | device identity, key/session registry, app-state sync, safety numbers | design device model before E2EE; add session/device APIs | two-device send/read/logout/revoke, ordering, pending outbox |
| Groups/calls scale | Discord SFU/MLS; WhatsApp group-call key rotation | stable large call, role gates | SFU, selective forwarding, epoch keys | call epochs/participants | gateway events | member/auth checks, E2EE epochs | rejoin/recovery | SFU participants | mesh one PeerConnection per participant | measured capacity, SFU, A/V E2EE, role gating | benchmark 2/4/8 peers, then choose SFU | load, join/leave churn, bandwidth, CPU, glare |
| Registration/lifecycle | phone/email verification, OTP/2FA, account recovery, device list | onboarding, permission explanations, session restore | verification service + rate limits | account, OTP/session/device | session events | anti-enumeration, brute-force, revocation | retry, offline logout | per-device sessions | email/password + profile setup + refresh rotation | OTP, phone, 2FA, recovery, device list, account deletion | add verification/session domain with migrations and rate limits | signup/login/logout/relogin, expired/revoked, offline, second device |
| UI/UX | polished native patterns, accessibility, dark mode, responsive layouts | smooth lists, empty/loading/error states, haptics | stable keys, cached images, lifecycle handling | local UI state/caches | incremental state updates | permission explanation, no sensitive previews | cached content, visible connection state | consistent cross-device | Compose design system, static backgrounds, local caches, update sheet | Compose UI tests, dark mode, tablet/foldable, accessibility audit | instrument first, then targeted optimizations | UI automation, font scale, rotation, screen sizes, frame profiling |

---

## 19. Known bugs and security vulnerabilities

### 19.1 Historical findings re-verified against current source

| # | Historical finding | Current verified result | Severity | Affected | Recommendation |
|---|---|---|---|---|---|
| 1 | Public media access | **CONFIRMED in Cloudflare**: `/media/file/*` has no `requireAuth` and sets public immutable cache. **FIXED in Rust**: conversation/status/avatar/signed-link authorization before first byte, tested. | Critical | Worker/R2/Android users | Private R2 behind Worker proxy or signed URLs; membership check; no public cache; parity tests |
| 2 | WebSocket room authorization | **PARTIALLY CONFIRMED**: Worker only rejects mismatched `user:` rooms; arbitrary `dm:`/`group:`/`call:` rooms are accepted and Durable Object relays client text frames. Rust fails closed to personal rooms only. | Critical | Worker | Allow only personal rooms or verify membership for every room; validate/ignore client frames; consider DO Hibernation |
| 3 | Message persistence + broadcast | **CONFIRMED**: Worker `POST /messages` and group send run DB insert and broadcast in `Promise.all`; a failed insert can still broadcast. Rust writes first then publishes, but neither has durable event sequence/cursor. | High | both | Transactional outbox / event sequence, persist before publish, cursor-based missed-event recovery |
| 4 | Group member information | **CONFIRMED in both**: `GET /groups/:id/members` only checks authentication, returns `full_name`, `username`, `avatar_url`, `email`; Rust comment says “deliberately no membership check.” **Update 2026-11-10 — FIXED in Rust** (Phase 1 branch, `0ec858c`): members only (403), and `email` is no longer returned by the member list or inside group objects; tests `group_member_list_is_for_members_only`, `group_projections_do_not_expose_member_emails`. **Still CONFIRMED in the Worker** (`groups.ts` L206–215 at `616a9e6`). | High | both; from 2026-11-10 Worker only, once the Phase 1 branch is merged | Require group membership; redact email; authorization tests |
| 5 | Status privacy | **CONFIRMED in both**: `privacy` column is stored, but `/status/feed` joins only friendships and muted list; it does not evaluate `s.privacy`. `status:new` goes to all friends. Rust media access does evaluate status privacy, but feed still leaks content URLs. **Update 2026-11-10 — FIXED in Rust** (Phase 1 branch, `b647f5a`): feed, `status:new`, view and media share one audience rule (§9 item 11), which also covers blocks and the account-wide `status_visibility`. Selected/excluded-contact audiences are still not implemented (no schema); Rust shows such a status to its author only. **Still CONFIRMED in the Worker** (`status.ts` L21–69 and L92–107). | High | both; from 2026-11-10 Worker only, once the Phase 1 branch is merged | Enforce audience on feed, direct status/media, and broadcast; add selected/excluded contacts |
| 6 | Status views | **CONFIRMED in both**: `/status/:id/view` only checks status exists and not self; no friendship/privacy/expiry check. `/status/:id/views` only checks authentication, not owner or authorized viewer. **Update 2026-11-10 — FIXED in Rust** (Phase 1 branch, `b647f5a`): a view is recorded only for someone in the audience of an unexpired status (404 otherwise), and the viewer list answers the author only (404 otherwise); tests `stranger_cannot_record_a_status_view`, `expired_status_cannot_be_viewed`, `only_the_author_can_list_who_viewed_a_status`. **Still CONFIRMED in the Worker** (`status.ts` L109–159). | High | both; from 2026-11-10 Worker only, once the Phase 1 branch is merged | Owner-only viewer list; audience/expiry check on view; tests |
| 7 | Message reactions | **CONFIRMED in Worker**: `/messages/:id/reaction` selects any message with no participant check; group reaction has no membership check. **PARTIALLY FIXED in Rust**: DM reaction checks participant + friend/block; group reaction still lacks membership check. **Update 2026-11-10 — FIXED in Rust** (Phase 1 branch, `0ec858c`): group reaction requires current membership (404 otherwise); test `outsider_cannot_react_to_a_group_message`. **Still CONFIRMED in the Worker** for group reactions (`groups.ts` L534–554); `messages.ts` was not re-read on 2026-11-10. | High | Worker + Rust group; from 2026-11-10 Worker only, once the Phase 1 branch is merged | Participant/membership checks everywhere; authorization tests |
| 8 | Password hashing | **CONFIRMED in both**: PBKDF2-HMAC-SHA256 100,000 iterations. OWASP currently recommends 600,000 for HMAC-SHA256 [1](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). | Medium | both | Versioned rehash-on-login using stored iteration prefix; benchmark; test on DB copy |
| 9 | Group calls | **CONFIRMED**: mesh topology, one PeerConnection per participant, no SFU; group video explicitly out of scope. | Medium | both/Android | Measure capacity; evaluate SFU for larger calls; do not claim scale without evidence |
| 10 | Migrations/runtime dependencies | **CONFIRMED**: Worker `package.json` has scripts only for 0001 and 0002; 0003–0005 require manual Wrangler commands. No migration-version table. Rust `schema.sql` uses `CREATE IF NOT EXISTS` only and does not migrate older schemas. Workers Builds failed at backend main head. | High | both | Versioned runner, staging migration tests, documented rollback limits, dashboard build-log access |

### 19.2 Additional current findings

- **Direct-message authorization divergence:** Cloudflare `POST /messages` requires only authentication; it does not check accepted friendship or bilateral block. Rust `ensure_direct_access` enforces both. This can permit unsolicited or blocked-peer messages on the deployed Worker.
- **Blocking inconsistency:** Cloudflare `/friends/block` deletes the friendship row and `/friends/unblock` does not restore it; Android keeps a device-local non-destructive mirror; Rust enforces block without deleting friendship. Behavior differs by client/backend.
- **Public account enumeration:** Cloudflare `GET /auth/check-email` deliberately discloses whether an email is registered; signup also returns 409. Needs anti-enumeration and rate limiting if product requirements demand it.
- **Hardcoded admin identity:** Cloudflare `admin.ts` contains a personal email; Rust requires `NCHAT_ADMIN_EMAIL`. Replace with a role/claim and test.
- **CORS:** permissive `*` in both runtimes; tighten at the edge with an allowed-origin list.
- **WebSocket token exposure:** access token in query string; application tracing omits query, but reverse proxies must be configured to do the same.
- **No server-side brute-force/OTP/device/IP rate limiting in Worker:** Rust has process-local auth throttling only; web client has a local message-rate limiter that is not a security boundary.
- **Secret/signing hygiene:** public demo signing key/password committed; backend dashboard guide contains a static JWT secret example; both must be treated as exposed and migrated/rotated with explicit authorization.
- **No E2EE:** server operators can read plaintext messages/media; no encrypted backups or key verification.
- **No certificate pinning:** deliberate historical decision because the backend address is build-time and may rotate.
- **Stale CI artifacts:** `.ci/last-failure.log` contains an old Android compile failure from run `42490765804` (fixed by later commit `37ecd28b`); it is not current status. Do not delete databases or force-push to clean it.
- **Stale documentation:** `PARITY.md` describes version 1.0.14 mock-only app; `ARCHITECTURE.md` claims camelCase/in-memory repositories; `eagle-x.md` claims no tests/push/offline DB and repeats a public demo signing password; `docs/AUDIT-1.0.39.md` says calls unwired; `worker/README.md` says most web services are unmigrated; `CLAUDE.md`/backend `README.md` say React 19; `APP_DESCRIPTION.md`/backend `README.md` claim disappearing messages and real push. Current source has 21 Android unit-test files, 11 web service modules, React 18.3.1, no disappearing-message/E2EE route, and no server push route. All stale claims are consolidated as historical context, and no secret value is reproduced in `agent.md`.

### 19.3 Findings of the Phase 1 Rust work (2026-11-10)

Found while writing the authorization suite (`server/tests/authz.rs`). **Rust** is the state on branch `arena/56647832-nchat-kotlin`. Every FIXED entry names tests that failed in CI before the fix (runs `42790409920`, `42794405563`) and pass after it (run `42794684951`); `removed_member_can_still_hang_up` is a guard that passed throughout. **Worker** is what the source of `nchat-friends` at `616a9e6` says: it was read on 2026-11-10, not executed, and nothing there was changed. Numbering continues from §19.1.

| # | Finding | Severity | Rust | Worker |
|---|---|---|---|---|
| 11 | `POST /groups/:id/leave` did not check membership. A non-member could delete a group that had one member, with all its messages, or have `group-member:left` announced to a group they were never in. | High | FIXED `0ec858c` — 403. Tests `outsider_cannot_destroy_a_group_by_leaving_it`, `outsider_leaving_a_group_is_refused_and_not_announced` | CONFIRMED — `groups.ts` L267–293 |
| 12 | The call signal relays copied the request body over the event they deliver, so a call participant could choose the delivered event's `type`, call id and sender: a forged realtime event of any kind (`message:new`, `group:deleted`, …) pushed to the other side. | High | FIXED `84ceec5`, completed by `feb3600` (see 13). Tests `call_signal_cannot_impersonate_another_event`, `group_call_signal_cannot_impersonate_another_event` | CONFIRMED — `calls.ts` L124–135, `groupCalls.ts` L214–230 |
| 13 | The first fix of 12 protected only the exact keys the server writes. The Android client reads the call id of a group-call event from `callId` before `call_id` (`CallRepository.handleGroupEvent`), so a body could still label a signal as belonging to another call. Someone who knew that call's id without being in it — a member removed mid-call, for example — could reach a participant through any group they share and have the participant's phone start connecting to them. Found in self-review after the first green run; the web client reads only `call_id` and was not affected. | Medium | FIXED `feb3600` — one shared list of envelope keys for both relays (`routes::is_signal_envelope_key`). Test `signal_from_one_group_call_cannot_pose_as_another` | CONFIRMED — same spread as 12 |
| 14 | Signals were relayed after the call had ended. | Low | FIXED `84ceec5` — answers 200 and relays nothing. Tests `finished_call_relays_no_more_signals`, `ended_group_call_relays_no_more_signals` | CONFIRMED — neither handler looks at `ended_at` or `status` |
| 15 | `POST /group-calls/:id/signal` delivered to any `to`: a group member could push `group-call:signal` events to any user id. | Medium | FIXED `84ceec5`, tightened by `feb3600`: `to` must be a current member of the call's group. A member removed mid-call keeps a participant row, which lets them hang up but no longer brings them signalling. Tests `group_call_signal_cannot_be_aimed_outside_the_call`, `member_removed_from_the_group_gets_no_more_call_signals` | CONFIRMED — `groupCalls.ts` L214–230 |
| 16 | `POST /group-calls/:id/decline` and `/leave` had no check: anyone who knew a call id could have `participant-declined` or `participant-left` announced for themselves. | Low | FIXED `84ceec5` — 403 unless a member of the group or a participant of the call. Tests `outsider_cannot_decline_a_group_call`, `outsider_cannot_leave_a_group_call`, guard `removed_member_can_still_hang_up` | CONFIRMED — `groupCalls.ts` L232–249, L254–295 |
| 17 | `DELETE /groups/:id/members/:userId` announced `group-member:left` even when the named user was not a member, so an admin could push that event to any user id. | Low | FIXED `0ec858c` — announced only when a row was removed. Test `removing_someone_who_is_not_a_member_announces_nothing` | CONFIRMED — `groups.ts` L250–265 |
| 18 | `DELETE /friends/:id` sent `friend:removed` to any user id, friend or not. | Low | FIXED `d247d39` — sent only when a friendship or request was removed. Test `unfriending_a_stranger_sends_them_nothing` | CONFIRMED — `friends.ts` L196–217 |
| 19 | Group message delete-for-me and delete-for-everyone did not check current membership; a removed member could still delete their old message for everyone. | Low | FIXED `0ec858c` — 404. Tests `outsider_cannot_mark_a_group_message_deleted_for_themselves`, `removed_member_cannot_delete_their_old_message_for_everyone` | CONFIRMED — `groups.ts` L496–532 |
| 20 | Statuses ignored blocks. A Rust block keeps the friendship row, so a blocked friend still got the feed, the push and the media. | High | FIXED `b647f5a`. Tests `status_feed_respects_blocks_in_both_directions`, `new_status_is_not_pushed_to_blocked_friends`, `blocked_friend_cannot_record_a_status_view`, `blocked_friend_cannot_open_status_media` | Different shape — the Worker's block deletes the friendship (`friends.ts` L219–238), which takes the blocked user out of the feed join; viewing by id is open to anyone (#6) |
| 21 | The account-wide privacy setting `status_visibility` was stored and never applied to statuses. | Medium | FIXED `b647f5a`. Test `account_level_status_visibility_is_enforced` | CONFIRMED — `status_visibility` appears only in `lib/privacy.ts` and `routes/privacy.ts` |
| 22 | Rust media access treated a per-status `privacy` value it could not parse as `contacts`. | Low | FIXED `b647f5a` — such a status is visible to its author only. Tests `status_with_an_audience_the_server_cannot_evaluate_fails_closed`, `status_media_follows_the_audience_of_its_status` | Not applicable — Worker media is public (#1) |
| 23 | Rust media access (`routes/media.rs`, `access::can_read`) lets the first kind of conversation that mentions a file decide alone: if any 1:1 message references the key, only the parties of those 1:1 messages may read it, and group messages or statuses that reference the same key are not consulted (the group branch does the same to statuses). Forwarding re-sends the same `media_url` (Android `forwardMessage`), so a photo forwarded from a 1:1 chat into a group should be unreadable for the other group members. It refuses too much rather than too little. | Medium (availability) | **DISCOVERED, not fixed** — source reading only, no test yet; predates this work. Proposed: let any branch grant access instead of returning early, with a forward-into-group test first | Not applicable — Worker media is public (#1) |

**Not fixed — residual risks seen in the Rust source during this work.** Source reading, no test; each needs a decision or belongs to a later item.

- **Other users' e-mail addresses.** Any signed-in user can read every other user's `email` (and `is_admin`) through `GET /users`, `/users/search`, `/users/:id` and the friends lists: the projections `user_public`, `profile` and `brief` in `server/src/models.rs` mirror the Worker's. Left alone on purpose — it is a product decision, and the Android Sync check (`core/diagnostics/SyncCheck.kt`) compares e-mails from `/users`.
- **No friendship needed to ring or to add.** `POST /calls` and `POST /groups` (and adding members) accept any user id; only a block stops a call.
- **Rate limiting.** `ratelimit::client_key` trusts the first hop of `X-Forwarded-For`, which a client can set unless a trusted proxy overwrites it, and the limiter is process-local.
- **Token lifetime.** `AppState::current_user` validates the JWT only, so an access token keeps working until it expires (15 min) after logout, and after its account is gone.
- **Media by reference.** Access to a file follows any message that mentions its key, so someone who once saw a media URL can keep access by quoting it in a conversation of their own.
- **Calls after removal.** Removing a member from a group does not end their part in a running group call. The server stops relaying signalling to and from them, but peer-to-peer media that is already flowing is not the server's to cut.
- **Already tracked elsewhere:** `CorsLayer::permissive()`, no event sequence or cursor, PBKDF2 at 100,000 (§33 items 6–8).
- **Smaller:** a member removing themselves through `DELETE /groups/:id/members/:self` skips the last-admin rule that `leave` applies; `DELETE /status/:id` answers 200 to non-authors, though nothing is deleted (guarded by `only_the_author_can_delete_a_status`).

### 19.4 Findings of the second Phase 1 turn: in-app updater and sign-in (2026-11-10)

Numbering continues from §19.3. State on branch `arena/56647832-nchat-kotlin`, not merged. “Red” and “green” name the CI runs in which the test failed before the change and passed after it.

| # | Finding | Severity | State | Evidence and limits |
|---|---|---|---|---|
| 24 | **The updater refused every update on Android 9, Android 10 and early Android 13.** `AutoUpdater.validateArchive` asked `PackageManager.getPackageArchiveInfo` for a file's signer with `GET_SIGNING_CERTIFICATES` only. In AOSP those releases load a file's certificates only for `GET_SIGNATURES` (`android-9.0.0_r1`, `android-10.0.0_r1`, `android-13.0.0_r1`, `_r2` and `_r3`; the corrected check is present at `android-13.0.0_r16` and at every later Android 13 tag that was read). Android 11, 12 and 14+ accept either flag (tags in §36); on Android 7–8 the app already used the old flag. No signer came back, and the download was refused as “not signed by NChat's verified release key … Received: unavailable”. | High for the phones concerned: no update can install | FIXED `c5eed26` — `signerLookupFlags` asks with both flags on API 28+ | Red: `6bc65dc`, APK run `42802241927` (58 tests, 1 failed: `asksForAnApkFilesCertificatesTheWayEveryAndroidReleaseLoadsThem`). Green: `5091dbb`, run `42802654826`. The platform behaviour was **read in AOSP source, not seen on a device**; vendor builds may differ, and which Android 13 phones kept the early code is unknown. The owner's report (“downloaded, then an error”) fits; the exact message and the Android version were not available. |
| 25 | The update page looked for a release once, when the screen was created. `BackgroundSyncService` keeps the process alive, so that can be days ago, and a look that failed at start-up was never repeated. | Medium | FIXED `4b3f2a1` — `UpdateForeground`, `updateCheckDue` | `UpdateCheckPolicyTest` (4 tests) covers the rule. The wiring in `UpdateGate.kt` has no test and has not run on a device. |
| 26 | The first update on a phone needs Android's “Install unknown apps” switch. The page printed that step in the red used for errors and waited for a second tap on Update now. | Medium — it reads as a failure, and it also fits the owner's report | FIXED `4b3f2a1` — shown as a step; the install continues on return once the switch is on; the downloaded file is reused | No test; not run on a device. |
| 27 | Messages: “no certificate could be read” was reported as “wrong signer”; a file refused by the checks got “try another network”; so did HTTP 404 in the minutes between the manifest and its APK being published. | Low | FIXED `4b3f2a1` — `judgeUpdateSigners`, `downloadFailureMessage`; messages name the Android version | `UpdateSignerPolicyTest`, `ApkDownloaderTest`. Every refusal still refuses: no check was loosened. |
| 28 | **Rust `/auth/login` could be guessed without limit.** The limiter's key was “address + e-mail”, and the address is the first hop of `X-Forwarded-For`, which the client writes. A new value per request was a new budget per request. | High | FIXED `dd4b497` — a second budget per account, with no part the client chooses (`NCHAT_AUTH_ACCOUNT_RATE`, default 3 × `NCHAT_AUTH_RATE`) | Red: `6eee8c6`, Rust run `42805222571` (“60 of 60 guesses were checked”). Green: `2b30f1d`, run `42805578387`. Accepted cost, written in the code: enough wrong guesses keep the owner from signing in on a new device until the window moves on; existing sessions are not affected. **Worker:** no rate limiting of any kind in `worker/src` or `wrangler.toml` at `616a9e6`; what Cloudflare's edge does is not visible in the source. |
| 29 | Rust password hashes: a stored hash was never upgraded, so raising the round count would have protected new accounts only; and the stored count was run as found — `0`, or four billion. | Medium | FIXED `dd4b497` — configurable count (`NCHAT_PBKDF2_ITERATIONS`, never below 100 000), upgrade after the next successful sign-in, never a downgrade, a stored count of 0 or above 10 000 000 refused before any work | Red and green as #28 for the upgrade (“the 1 000-round hash is still stored”). The ceiling has unit tests but **no red run**: showing it fail would mean running billions of rounds in CI. The default count is unchanged, so nothing is rewritten until an operator raises it. Plan: §27. **Worker:** `lib/password.ts` reads the count from the stored text; it has no upgrade, no ceiling, and compares with `===`. |

**Not fixed — seen during this turn.** Source reading unless stated.

*Updater*

- **The update to 1.0.75 is carried out by 1.0.74.** On the phones of #24 it will be refused once more. The release notes in `version.json` say to install 1.0.75 from the release page once; from then on the repaired code does the updating.
- **No update offer before sign-in.** `StartupUpdateGate` is inside `HomeRoot()`. A build whose sign-in is broken cannot offer its own fix.
- **The installer's verdict is not seen.** The APK is handed over with `startActivity` (`ACTION_VIEW`, then `ACTION_INSTALL_PACKAGE`), which returns nothing; “App not installed” cannot be reported or explained by the app. `PackageInstaller` sessions return a result.
- **Settings ▸ Check for updates** still wants a second tap after the permission step (`SettingsViewModel`); only the start-up page continues by itself.
- **Process death on the permission screen.** Some Android versions are reported to restart an app when “Install unknown apps” is granted (not verified here). The page then comes back at the restart and one more tap is needed; nothing is downloaded twice.
- **Manifest before APK.** `version.json` is read from raw `main`, so a release is announced a few minutes before CI has published its APK. 1.0.75 explains the 404; it does not remove the window.
- **The signing key is public** (ADR 012). The pin proves “signed with the demo key”, which anybody can do. Owner's decision; migration notes in §27.

*Sign-in, Rust*

- `ratelimit::client_key` still trusts the first `X-Forwarded-For` hop, so the per-address limiter is only as good as the proxy in front. The repair is to use the connection's peer address and count trusted proxies from the right (`ConnectInfo`, plus a setting for how many proxies there are).
- **Sign-up is not limited per address:** its key contains the e-mail, so a new e-mail is a new budget. Each sign-up also costs one PBKDF2 run.
- **Timing shows whether an e-mail has an account:** `login` answers at once for an unknown e-mail and after a PBKDF2 run for a known one. Hashing a dummy value would close that and would let anyone make the server hash at will, so it needs the address work first.
- PBKDF2 runs on the async worker threads. The limiter maps grow with every distinct key inside a window: the sweep in `RateLimiter::allow` starts above 10 000 keys, removes only expired entries, and then runs on every call. Both sign-in keys contain text the client chooses (the claimed address, the e-mail).
- **Admin by unverified e-mail:** `/admin/stats` trusts `NCHAT_ADMIN_EMAIL`, and nothing verifies e-mail addresses. If that account does not exist yet, whoever registers the address is the admin. The route returns four counts.

*Reviewed, nothing to change (Rust, source reading)*

- **§33 item 6, first half:** `messages::send` writes the row, returns on error, and only then publishes. `db.rs` sets WAL and leaves `synchronous` at SQLite's default. Other event types were not checked one by one.
- **SSRF:** the only outbound request is the TURN credential call in `routes/calls.rs`, to a fixed Cloudflare host with a key id from the environment. No URL comes from a request.
- **CORS:** `CorsLayer::permissive()` with bearer tokens in a header and no cookies is not by itself a cross-site request forgery path. An allowlist is still part of item 7; it needs the list of web origins from the owner.

---

## 20. Completed work

**Completed with objective evidence in this session:** Phase 0 repository discovery, latest-commit verification, source-backed architecture/security map, competitor comparison, roadmap, `agent.md`, Markdown consolidation, baseline checks listed in §36.

**Historically shipped implementation work (source exists; product-level completion is not claimed without E2E evidence):** remote repositories and JWT transport (1.0.18/1.0.19); media rendering/upload and status composer (1.0.19/1.0.20); build-time backend address (1.0.21); Keystore token storage, upload limits, path traversal fix (1.0.22); private Rust media, photo compression, chat cache, no server switcher (1.0.23); crash reporting (1.0.24); defensive preference reads (1.0.25); API log/Sync check (1.0.26); full user search and profile avatar (1.0.27); status/group-info routes (1.0.28); real voice audio (1.0.30); non-destructive device blocking (1.0.31); session-loss handling (1.0.32); history recovery (1.0.33); account isolation (1.0.34); optimistic send/retry (1.0.35); persistent outbox (1.0.36); UUID idempotency IDs (1.0.37); friend graph error states (1.0.38); realtime resync/typing expiry (1.0.39); real call signaling and status view recording (1.0.40); WebRTC media layer and TURN (1.0.41); durable friend graph (1.0.42); durable message cache and connectivity watch (1.0.43); continuously mirrored chat/group lists (1.0.44); real voice waveform and offline clip queue (1.0.45); previews/replies/deletes/reactions/forwarding (1.0.46); authenticated media playback (1.0.47); gesture ownership/upload progress (1.0.48); swipe reply/double-tap reaction/voice speed (1.0.49); in-chat search/multi-select/starred/drafts/photo pager (1.0.50); system notifications (1.0.51); text formatting/swipe archive/pinned message/message info (1.0.52); camera/location (1.0.53); mentions/private reply (1.0.54); storage manager/media index (1.0.55); group voice calls mesh (1.0.56/1.0.57); FCM-less background socket (1.0.58); call bug fixes (1.0.59); notification photos and event alerts (1.0.60); chat-list grid (1.0.61); notification deep links (1.0.62); background call ring/decline (1.0.63); full-screen update page/mention notifications (1.0.64); wallpapers/group sender runs (1.0.65); Groups/Status empty states (1.0.66); emoji picker (1.0.67); complete status composer/viewer (1.0.68); 1.0.69–1.0.74 release/onboarding/update-signing/system-downloader hardening (see §32).

**Completed with objective evidence on 2026-11-10 (Phase 1, Rust backend only; branch `arena/56647832-nchat-kotlin`, not merged):** an authorization regression suite for the Rust API (`server/tests/authz.rs`, 45 tests) and the fixes it called for — group membership checks and e-mail redaction (§33 item 3), the status audience (§33 item 4), call-signal integrity, and two false announcements (§19.3). Evidence: the suite failed in CI before the fixes (runs `42790409920`, `42794405563`) and `cargo test` is green after them (run `42794684951` on `a1d2398`); see §36. Not completed: everything on the Worker side of Phase 1, and items 6–8.

**Completed with objective evidence on 2026-11-10 (second Phase 1 turn; same branch, not merged):** on the Rust backend, a per-account sign-in budget and an upgrade path for password hashes, each with a test that failed in CI before the change and passes after it (§19.4 #28–#29). On Android, the signer lookup of the in-app updater, with a test that failed before the fix (§19.4 #24). The rest of the updater repair (§19.4 #25–#27) is written and builds in CI but is **not** claimed as completed: its page behaviour has no test, and nothing has run on a device.

---

## 21. Partially completed work

- Two wire-compatible backends with divergent security controls; parity is incomplete. *Update 2026-11-10:* the gap is wider on the Phase 1 branch — Rust now refuses the requests listed in §19.1 #4–#7 and §19.3, which the Worker still accepts. That is deliberate (fix first where tests can run); the Worker has to follow.
- Account lifecycle: email/password, profile setup, refresh/logout exist; no phone/OTP, 2FA, recovery, device list, account deletion, or data export.
- Blocking: server block in Worker is destructive, Android block is device-local, Rust block is non-destructive; no single consistent policy.
- Status privacy: stored `privacy` field and Rust media audience check, but feed and viewer routes do not enforce it. *Update 2026-11-10:* enforced in Rust on the Phase 1 branch (feed, push, view, viewer list, media); still unenforced in the Worker; selected/excluded-contact audiences have no schema.
- Multi-device: same account’s sockets receive personal-room events, but no device identity, linked-device verification, app-state sync, or per-device revocation.
- Push: local notifications and a foreground socket work around lack of FCM; no real push infrastructure.
- Group calls: audio-only mesh; no group video, SFU, or capacity measurement.
- Search: user search and local in-chat/media search; no server-side message search, filters, or privacy-tested global search.
- Local state: many WhatsApp-like features are deliberately device-only (drafts, stars, pins, archive, wallpaper), so they do not follow the account across devices.
- UI/accessibility: strong phone-portrait design system, but no dark mode, tablet/foldable layout, or accessibility/UI automation evidence.
- Tests: unit and Rust integration tests exist; no Android UI tests, E2E journeys, migration tests against a copy, load tests, or security regression suite. *Update 2026-11-10:* a security (authorization) regression suite now exists for the Rust backend on the Phase 1 branch; there is none for the Worker or for Android.
- Deployment: Android and local web/worker builds are green; Cloudflare Workers Builds failed at backend main head.
- Phase 1 critical security (added 2026-11-10): the Rust half is done on a branch that is not merged; the Worker half is not started; items 6–8 of §33 are open in both. *Update (second turn):* item 8 and part of item 7 are now done in Rust; the rest stands. By the owner's decision the branch is merged only when all of Phase 1 is finished (§33).
- In-app updater (added 2026-11-10): exists since 1.0.8; repaired as 1.0.75 on the Phase 1 branch; never proven on a device; not offered before sign-in; the system installer's result is not observed (§19.4).

---

## 22. Remaining work

Phase 1 critical security; Phase 2 account lifecycle; Phase 3 reliable messaging; Phase 4 contacts/groups; Phase 5 media/voice messages; Phase 6 push; Phase 7 calls; Phase 8 status/advanced messaging; Phase 9 E2EE; Phase 10 UI/UX/performance; Phase 11 production readiness. Detailed roadmap is §33. Cross-cutting remaining work: authorization tests, durable event recovery, migration runner, device model, real push, E2EE design, backup/restore, monitoring/alerting, load tests, accessibility, dark/tablet layouts, and end-to-end scenario evidence. *Update 2026-11-10:* of Phase 1, the Rust-side authorization work is done on branch `arena/56647832-nchat-kotlin` (not merged). Still remaining in Phase 1: every Worker-side item (1–5), durable events (6), rate limits, CORS and admin role (7), the PBKDF2 plan (8), and the residual risks and finding #23 in §19.3.

---

## 23. Blocked work

| Work | Exact limitation |
|---|---|
| Inspect/fix Cloudflare Workers Build failure details | Sandbox outbound network allows GitHub/npm/PyPI only; Cloudflare dashboard build logs are not accessible. GitHub check-runs expose only build IDs and failure conclusion. |
| Local Android build/unit tests | No `java`, `gradle`, Android SDK, or local Gradle daemon in this sandbox. CI is the only compile/test evidence. |
| Local Rust `cargo test` | No `cargo`/`rustc` installed. Last CI success is on a prior commit, not HEAD. *Update 2026-11-10:* still no local toolchain, and none can be installed — the official Rust hosts are outside the sandbox's network allowlist, and mirrors must not be used (Appendix C). CI has since run on `main` at `a867fdd` (run `42779970114`) and on every `server/` push of the Phase 1 branch. |
| Real two-device call/media/push/notification tests | No physical devices, emulator, FCM credentials, TURN credentials, or live staging accounts in this session. |
| Production D1/SQLite migration or restore drill | No production database access; directive forbids destructive operations without explicit authorization. |
| Secret rotation / signing-key migration | Requires explicit authorization; key rotation breaks in-place updates for existing demo-signed installs. |
| Production deployment | Requires explicit authorization and Cloudflare dashboard access. |
| E2EE protocol work | Requires a separate security design and interoperability plan; cannot be inferred from current plaintext schema. |
| Worker half of Phase 1 (added 2026-11-10) | The Worker lives in `cybereagle472/nchat-friends`; a session is tied to one repository and branch, and the Phase 1 session was tied to `NChat-kotlin`. It needs a session on that repository. The Worker has no test suite and no test script in `worker/package.json`, so the first step there is a way to run tests. |
| Reading green Rust CI output, `cargo fmt` and clippy results (added 2026-11-10) | Actions logs are served from a host outside the sandbox allowlist. Readable: run and step conclusions, check-run annotations, and the failure log the workflow commits on a red run. The format and clippy steps are `continue-on-error`, so their conclusions say nothing. |

---

## 24. Technical debt

1. `RemoteChatRepository.kt` is 2,634 lines; split transport, mapping, event reducer, and state.
2. Cloudflare Worker and Rust backend have drifted in authorization behavior despite sharing wire contracts. *Update 2026-11-10:* the Phase 1 branch widens this on purpose (Rust hardened first; see the note on ADR 002 in §31). The differences are listed in §8, §19.1 #4–#7 and §19.3.
3. No event sequence/cursor/transactional outbox; reconnect resync refetches lists/open chats.
4. No migration runner/version table; only 0001/0002 scripts in Worker `package.json`; Rust `CREATE IF NOT EXISTS` does not alter old schemas.
5. `RealtimeRoom` uses in-memory sockets with no Hibernation API, heartbeat, or room membership policy; Rust Hub is process-local.
6. JSON-in-TEXT columns for reactions/deleted_for/waveforms; no typed attachment or read-receipt tables.
7. Public demo signing key and historical secret examples committed; production secret lifecycle missing.
8. Stale `.ci/last-failure.log` and several stale docs (now consolidated/removed from this repository).
9. `declineGroupCall` has no textual caller; group call decline flow needs verification.
10. No Room/SQLCipher local DB; JSON caches can grow, though MessageStore bounds 300 messages/chat and Coil caps 128 MB.
11. No Compose UI tests, E2E tests, load tests, or security regression suite. *Update 2026-11-10:* a Rust authorization regression suite exists on the Phase 1 branch (`server/tests/authz.rs`).
12. No central input schema/MIME allowlist, quota, cleanup, or audit logging.
13. Web client `npm run lint` is defined but ESLint is not installed (historical backend `CLAUDE.md` finding); use typecheck/build.
14. All 11 backend Markdown files are retained but several are stale (`CLAUDE.md`, `README.md`, `APP_DESCRIPTION.md`, `worker/README.md`, setup guides). They contain React 19/Radix, disappearing-message, push, production-grade, branch-specific `arena/940244a7-nchat-friends`, “21 tables”, and “only 0001/0002” claims; the dashboard guide also contains an exposed example JWT secret. See Appendix A.10.
15. No dark mode, tablet/foldable layout, accessibility audit, or localization beyond English resources.
16. (2026-11-10) `cargo fmt --all -- --check` fails on `main` and on the Phase 1 branch. The step is advisory (`continue-on-error`) and its diff cannot be read without a Rust toolchain or log access, so nobody has fixed it, and the Phase 1 code very likely adds to it (an inference: the annotation's log line moved from 1895 on `main` to 2225 on the branch). Run `cargo fmt` once where a toolchain exists, then consider making the step blocking.
17. (2026-11-10) A green Rust CI run leaves nothing an agent can read: only step conclusions. The test summary is visible only in the failure log that a red run commits. A workflow step that emits the `test result:` lines as a `::notice` would make green runs auditable through the annotations API. Not changed, because workflow edits were out of scope.
18. (2026-11-10) Android `CallRepository.handleGroupEvent` matches group-call events by fragments of their name. It drops a participant only for an event whose type contains `leave`, but both backends send `group-call:participant-left`, so that branch never runs for the event the servers actually send, and a leaver is not taken out of the roster by it (source reading; not tested on a device). It also reads the call id from `callId` before `call_id`, which is what made §19.3 #13 possible.
19. (2026-11-10) The user projections return every user's `email` to any signed-in caller in both backends (§19.3, residual risks). Needs a product decision before code.
20. (2026-11-10) Rust media access lets the first kind of conversation that references a file decide alone (§19.3 #23).
21. (2026-11-10) The updater hands the APK to the system with `startActivity` (`ACTION_VIEW`, then `ACTION_INSTALL_PACKAGE`) and never learns whether the install succeeded; `PackageInstaller` sessions would report it. The update page exists only for signed-in users, keeps its state in the composable (lost on process death) and has no test; `SettingsViewModel` repeats the download-and-install flow with the older behaviour.
22. (2026-11-10) `version.json` is read from raw `main`, so a version is announced before its APK exists. Publishing the manifest with the release, or reading the release API, would remove the window.
23. (2026-11-10) Rust sign-in: `ratelimit::client_key` trusts a client-written header, sign-up is limited per address-and-e-mail only, and PBKDF2 runs on async worker threads (§19.4).

---

## 25. Database migration history

| Version | File | Change | Current automation |
|---|---|---|---|
| 0001 | `0001_init.sql` | 20 core tables: users, sessions, presence, friend requests/friendships/blocks, messages, groups/members/messages, calls, statuses/views/mutes, privacy/notification/notifications/push subscriptions, media attachments | Worker `db:migrate:local/remote`; Rust `schema.sql` bootstrap |
| 0002 | `0002_privacy_extra.sql` | `ALTER privacy_settings` add `about_visibility`, `online_status_enabled` | Worker scripts for local/remote; folded into Rust `schema.sql` |
| 0003 | `0003_group_voice_duration.sql` | `ALTER group_messages ADD COLUMN duration TEXT` | Manual Wrangler only; no npm script |
| 0004 | `0004_group_read_state.sql` | `group_read_state` table + indexes | Manual Wrangler only; no npm script |
| 0005 | `0005_group_calls.sql` | `group_calls`, `group_call_participants`, indexes; documents mesh/no-SFU | Manual Wrangler only; no npm script |

`server/migrations/` and `worker/migrations/` contain identical table sets (verified by parsing SQL). `server/schema.sql` contains the same 22 tables with `CREATE TABLE IF NOT EXISTS` and indexes; it is safe to rerun on a fresh/current database but is **not** a migration system and will not add missing columns to an old database. There is no `schema_migrations` table, checksum, rollback script, or staging-copy test. Never delete/recreate production databases. Back up `NCHAT_DATA_DIR` (SQLite + `media/`) and use a SQLite-aware backup for a live WAL database; document rollback limitations before any migration.

---

## 26. Configuration and environment variable inventory

| Scope | Name | Default / current value | Purpose |
|---|---|---|---|
| Android build | `NCHAT_API_BASE` / `-PNCHAT_API_BASE` / `local.properties:nchat.apiBase` / `nchat.properties:apiBase` | committed `apiBase=` empty; CI repository variable injects value | single build-time backend root; empty shows `MissingServerScreen`; bare host normalized to HTTPS |
| Android release | `KEY_STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | workflow supplies public demo values | signs `app/release.keystore` restored from `.github/nchat-demo-keystore.b64` |
| Android manifest | `versionCode` 82, `versionName` 1.0.74 on `main`; *2026-11-10:* 83 / 1.0.75 on the Phase 1 branch | `version.json` must match | in-app updater and CI APK verification |
| Rust server | `PORT` | 8080 | binds `0.0.0.0:8080` |
| Rust server | `NCHAT_DATA_DIR` | `data` | `nchat.sqlite` + `media/` |
| Rust server | `JWT_SECRET` / `NCHAT_SECRET` | random ephemeral if unset (warning) | JWT and media-link signing; set persistent secret in production |
| Rust server | `PUBLIC_API_URL` | `http://localhost:<PORT>` | absolute media URL base |
| Rust server | `NCHAT_POOL` | 8 | SQLite connections |
| Rust server | `NCHAT_MAX_UPLOAD_MB` | 100 | per-upload cap, 413 on exceed |
| Rust server | `NCHAT_AUTH_RATE` / `NCHAT_AUTH_WINDOW_SECS` | 10 / 300 | process-local signup/login throttle |
| Rust server | `NCHAT_AUTH_ACCOUNT_RATE` | 3 × `NCHAT_AUTH_RATE` (30) | sign-in attempts per account in the same window, whatever address they claim to come from (added 2026-11-10, Phase 1 branch) |
| Rust server | `NCHAT_PBKDF2_ITERATIONS` | 100000; a lower value counts as 100000, a higher one as 10000000 | rounds for new and upgraded password hashes — read §27 before raising it (added 2026-11-10, Phase 1 branch) |
| Rust server | `NCHAT_MEDIA_PUBLIC` | 0 | `1` disables private media enforcement |
| Rust server | `NCHAT_MEDIA_TTL` | 600 s | signed media link lifetime |
| Rust server | `NCHAT_ADMIN_EMAIL` | unset = no admin | `/admin/stats` gate |
| Rust server | `CF_TURN_KEY_ID` / `CF_TURN_KEY_API_TOKEN` | unset → `{iceServers:null}` | Cloudflare Realtime TURN credential minting |
| Rust server | `NCHAT_LOG` | `info` | tracing filter |
| Cloudflare Worker | `JWT_SECRET` | secret | JWT signing; never commit |
| Cloudflare Worker | `PUBLIC_API_URL` | `https://nchat-friends.usa66486.workers.dev` in current `wrangler.toml` | media URL base |
| Cloudflare Worker | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | optional; no route uses them | planned Web Push |
| Cloudflare Worker | `CF_TURN_KEY_ID`, `CF_TURN_KEY_API_TOKEN` | optional | TURN credentials |
| Cloudflare bindings | `DB`, `MEDIA`, `REALTIME` | D1 `nchat-db`, R2 `nchat-media`, Durable Object `RealtimeRoom` | runtime resources; D1 database ID is committed in `wrangler.toml` |
| Web frontend | `VITE_API_URL` | `.env.local` | Worker URL |
| Web frontend | `VITE_VAPID_PUBLIC_KEY`, `VITE_GIPHY_API_KEY` | optional | historical Web Push/GIF configuration |

**Secret rules:** never expose or commit JWT secrets, TURN API tokens, keystore passwords, or refresh tokens. Application request spans omit query strings because the WebSocket URL contains a bearer token; configure reverse proxies likewise. The committed demo keystore and static guide secret are known exposures, not acceptable production credentials.

---

## 27. Build and deployment procedures

### Android

1. CI workflow `.github/workflows/build-apk.yaml` runs on `main` and `arena/**` pushes and manual dispatch.
2. JDK 17, Gradle 8.2, Android SDK 34/build-tools 34.0.0; restore demo keystore from base64.
3. Check `NCHAT_API_BASE`; on `main`, an empty value fails the release gate; on arena branches it warns and builds a MissingServer APK.
4. `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`.
5. `gradle --no-daemon --stacktrace :app:testDebugUnitTest`.
6. `gradle --no-daemon --stacktrace :app:assembleRelease` with signing and API-base environment.
7. `apksigner verify --print-certs`, `aapt dump badging`, `verify_release_apk.py` validates package, version, release tag/URL, and pinned certificate fingerprint.
8. On `main`, upload artifact and publish GitHub Release using `version.json` `releaseTag` and notes.
9. Local build command requires a JDK/Android SDK/Gradle install not present in this sandbox: `gradle :app:assembleRelease`; set `NCHAT_API_BASE` to an HTTPS backend. A missing address is not an error for local builds but shows MissingServerScreen.
10. Version bumps must update `app/build.gradle` and `version.json` in the same commit; keep `versionCode` monotonically increasing and the signing key unchanged.
11. *(2026-11-10)* A release reaches phones in two steps that are minutes apart: `version.json` on `main` is visible as soon as the merge lands (the raw file host can serve an older copy for a few minutes — general knowledge, not measured from this sandbox; the app asks with `Cache-Control: no-cache`); the APK only when the `main` run has built and published it. In between the download answers 404; from 1.0.75 the app says so.
12. *(2026-11-10)* An update is carried out by the version that is installed, not by the one being installed. A repair to the updater therefore helps from the following update on, and the notes of the repairing release must say what to do if the old code fails.
13. *(2026-11-10)* When a branch that carries a version bump waits — as the Phase 1 branch does — check before merging that `main` has not used that version, and that `releaseNotes` describe everything in the APK since the last release.

### Rust backend

```sh
cd server
JWT_SECRET='<long-random-secret>' PUBLIC_API_URL='https://chat.example.com' \
NCHAT_DATA_DIR=data cargo run --release
cargo build --all-targets
cargo fmt --all -- --check
cargo clippy --all-targets
cargo test
```

Terminate TLS in a trusted reverse proxy; set `NCHAT_API_BASE` in the Android build to the public HTTPS URL. `GET /health` returns `{"ok":true}`. Back up the SQLite database and media directory; use a SQLite-aware backup for live WAL.

**Password hash work factor — the plan for §33 item 8 (2026-11-10).** Both backends store `rounds:salt:key` (PBKDF2-HMAC-SHA256, 16-byte salt, 32-byte key) and both read `rounds` from the stored text, so a hash made with another count keeps verifying. On the Rust backend (Phase 1 branch) raising the count needs no script and no password reset:

1. Set `NCHAT_PBKDF2_ITERATIONS` (a value below 100 000 counts as 100 000, one above 10 000 000 as 10 000 000). New accounts get the new count at once.
2. Each existing account is upgraded after its next successful sign-in, the only time the server holds the password. A stronger hash is never replaced by a weaker one, so turning the setting back down rewrites nothing.
3. Watch progress with `SELECT substr(password_hash, 1, instr(password_hash, ':') - 1) AS rounds, COUNT(*) FROM users GROUP BY 1;`. Accounts that never sign in keep their old hash; forcing them is a product decision (expiry or reset), not a migration step.
4. Try it on a copy first, never on the live file: `sqlite3 data/nchat.sqlite ".backup /tmp/nchat-copy/nchat.sqlite"` (the directory must exist; the server looks for a file of exactly that name in `NCHAT_DATA_DIR`), start a second server with `NCHAT_DATA_DIR=/tmp/nchat-copy`, another `PORT` and the new setting; sign in with a test account; run the query on the copy. These manual steps are written from the source and have not been carried out — there is no toolchain and no real database in the agent's sandbox. `server/tests/auth_hardening.rs` is the automated form: its rows were computed by another implementation (Python `hashlib`) and copied into the test database.

**The constraint is the other backend.** A hash written with more rounds than the Worker's runtime will compute cannot be verified there, so such accounts stop being portable from Rust to the Worker. Cloudflare's Web Crypto is reported to cap PBKDF2 at 100 000 iterations — **not verified in this project**; verify it in the Worker session before deciding anything. If it holds, the Worker cannot go higher with PBKDF2 at all, and a stronger work factor there means another algorithm and a new stored prefix that both backends recognise. Until that is decided, leave the Rust setting at its default. The Worker also lacks the two guards Rust now has: an upper bound on the stored count, and an upgrade path.

### Cloudflare Worker / web frontend

```sh
# backend
cd worker
npm install
npx wrangler login
npx wrangler d1 create nchat-db           # put database_id into wrangler.toml
npx wrangler r2 bucket create nchat-media
npm run db:migrate:remote                 # 0001 only; 0002..0005 currently manual
npx wrangler secret put JWT_SECRET
npm run deploy

# frontend
cd ..
npm install
npm run dev                              # http://localhost:3000
npm run type-check && npm run build       # produces dist/
```

Dashboard-only path: create D1/R2, set the D1 ID in `worker/wrangler.toml`, connect two Workers Builds projects (frontend root `/` with build `npm run build`; backend root `worker` with empty build command), set `JWT_SECRET`, set `PUBLIC_API_URL`, apply 0001/0002 SQL in the D1 console, verify `DB`/`MEDIA`/`REALTIME` bindings, then smoke-test `/health`, signup, and `/users/me`. Both projects’ production branch should be `main`; `npx wrangler preview` is not a deploy command. The frontend bakes `__BUILD_COMMIT__` into Settings ▸ About for deployment freshness.

**This session performed no deployment.** Cloudflare deploy remains blocked pending dashboard log access and explicit authorization.

### Update signing migration (historical `UPDATE_SETUP.md`)

Create a private permanent keystore with `keytool`, back it up, add `SIGNING_KEY_BASE64`, `KEY_STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as GitHub Actions secrets, and update the workflow to consume them. Android cannot update the existing demo-signed installation with a differently signed APK; plan migration and warn users that uninstalling may remove local-only data. Keep `versionCode` and `version.json` aligned, pin the new certificate fingerprint, use exact versioned release asset URLs, and only add independent HTTPS mirrors in `apkUrls` after provision/verification.

---

## 28. Automated testing procedures

| Suite | Command / workflow | Current evidence |
|---|---|---|
| APK verification Python tests | `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v` | 7 tests, all OK in this session |
| Android JVM unit tests | `gradle :app:testDebugUnitTest` in CI | Green on `d87b703` via run `42584666249`; 21 test files tracked, cannot run locally (no Java/Gradle) *Update 2026-11-10 (second Phase 1 turn):* 67 `@Test` in 22 files on the branch; red by design on `6bc65dc` (run `42802241927`: 58 tests, 1 failed), green on `5091dbb`, `8a506c8`, `6eee8c6`, `2b30f1d` (runs `42802654826`, `42803934030`, `42805221994`, `42805578589`). |
| Android release build/signature | `gradle :app:assembleRelease` + apksigner/aapt/Python verification in CI | Green on `d87b703`; pinned fingerprint checked |
| Rust build/clippy/test | `cargo build --all-targets`, `cargo clippy --all-targets`, `cargo test` with zero-test guard | 18 tokio integration tests + unit tests defined; last green run `42491305703` on prior SHA; no local cargo *Update 2026-11-10:* Phase 1 branch — 63 tokio integration tests (`api` 9, `authz` 45, `groups_status_calls` 4, `media` 5) + 16 unit tests, green on `a1d2398` (run `42794684951`). `main` at `a867fdd` is green with the earlier 18 (run `42779970114`). *Update 2026-11-10 (second turn):* 69 tokio integration tests (adds `auth_hardening.rs`, 6) and 20 unit tests by source count; red by design on `6eee8c6` (run `42805222571`: `auth_hardening` 4 passed, 2 failed), green on `2b30f1d` (run `42805578387`). |
| Worker typecheck | `cd worker && npm ci && npm run typecheck` | Exit 0 in fresh backend clone, this session |
| Web frontend | `npm ci && npm run type-check && npm run build` | Exit 0; 1649 modules; Vite built in 3.14 s, this session |
| Web lint | `npm run lint` | Known broken because ESLint is not installed; do not treat as a green gate |
| Cloudflare deployment | Workers Builds check-runs | Both frontend/backend builds failed on `616a9e6`; needs dashboard logs |

Missing suites: API authorization matrix, migration against a DB copy, WebSocket room authorization, Android instrumented/Compose UI tests, E2E journeys, network interruption, load/performance, security regression, backup restore, and multi-device tests. *Update 2026-11-10:* for the Rust backend an API authorization suite now exists (`server/tests/authz.rs`, Phase 1 branch), and the WebSocket room rule is covered by `api.rs::websocket_restricts_rooms_and_rejects_refresh_credentials`. The Worker has neither.

---

## 29. Manual testing procedures

The eight required end-to-end scenarios are **not executed** and must be run on staging/two devices before any feature can be `COMPLETED`:

1. **New user:** register → verify (currently no OTP) → create profile → find user → send/accept friend request → start conversation → send/receive message.
2. **Existing user:** open app → restore session → load cached and server conversations → receive notification → open conversation → read → reply.
3. **Offline user:** open conversation → disable internet → send → kill app → reopen → restore internet → verify one delivery, no duplicates.
4. **Group user:** create group → invite → accept → send messages → upload media → promote admin → remove member → leave.
5. **Calling:** start call → receive → accept → exchange actual audio/video → mute/camera → end → verify history.
6. **Logout/re-login:** logout → revoke session → reject old token → login → restore state/counters/contacts/groups/pending messages → register notification device → verify old-session policy.
7. **Security:** attempt unauthorized media access, room subscription, group access, member list, reaction, status viewing, and viewer-list access; every unauthorized operation must be rejected.
8. **Multi-device:** login on two devices → send from each → synchronize → read on one → verify status on the other.

Backend `TESTING_CHECKLIST.md` additionally requires login/session sanity, group create/mute/add/promote/remove/rename/leave/delete, privacy last-seen/photo/about/read-receipts/online/blocked-user checks, voice/video call ring/decline/miss/mute/camera/speaker/history/background behavior, and profile preview from chat/media viewer/Discover/requests/group/history with correct relationship actions. Report checklist item, expected vs actual, device/account count, and privacy settings on failure.

---

## 30. Performance and scalability considerations

**No measured load-test evidence exists.** Do not claim capacity figures. Historical latency budget in `ARCHITECTURE.md` (30 µs handler, 90 µs history, 25 µs fan-out, 15 ms cold start) was an early Rust design estimate, not a current benchmark.

Known architectural risks to measure:

- Cloudflare `Promise.all` insert+broadcast trades consistency for latency.
- Durable Object `RealtimeRoom` holds sockets in memory, relays arbitrary client text, and does not use WebSocket Hibernation; no heartbeat/backpressure policy.
- Rust `Hub` is process-local with a 256-message queue; multiple instances need Redis/NATS or equivalent.
- Group call mesh is O(n²) peer connections; no SFU; Android `GroupCallSession` explicitly avoids group video.
- D1/Rust queries use dynamic `IN (...)` placeholders for privacy/presence; bound result sets and add indexes for actual query patterns.
- Web production bundle main chunk is ~435.5 kB JS (116.98 kB gzip) in this session’s build; CSS ~103.49 kB.
- Android uses JSON file caches and a 128 MB Coil disk cache; `MessageStore` bounds 300 messages/chat; no database indexes or Flow queries.
- Absent metrics: API/DB p50/p95, WS stability under concurrency, message throughput, media upload/download throughput, Android frame time/startup/memory/battery, notification latency, call quality.

Measure first in a safe staging environment; increase concurrency gradually; document actual results and bottlenecks.

---

## 31. Architecture decision records

| ADR | Decision | Rationale / status |
|---|---|---|
| 001 | Preserve the existing Kotlin/Compose project and package `com.nchat.app`; no template replacement | Primary directive; identity/history preserved |
| 002 | Keep two backends wire-compatible but separate: Cloudflare Worker is reference, Rust is port; TypeScript contract wins on drift | Avoids assuming one runtime fixes the other; current divergence documented. **Note 2026-11-10 (security exception):** where Rust refuses a request the Worker accepts (§8, §19.1, §19.3), the Rust behaviour is the target and the Worker is the one to change — the roadmap already says “mirror Rust” (§33 item 2). Never loosen Rust to restore parity |
| 003 | Backend address is build-time `BuildConfig.NCHAT_API_BASE`; no runtime server switcher | Prevents device redirection and URL sprawl; empty shows MissingServerScreen |
| 004 | snake_case JSON, existing wrappers/status codes, and `client_message_id` idempotency are contract | Historical `ARCHITECTURE.md` camelCase claim is stale; current source uses snake_case with legacy camelCase DTO fields |
| 005 | Server database is source of truth; local JSON caches/outbox are fast copies | Offline startup without fabricated data; server owns ordering/unread |
| 006 | No FCM; `BackgroundSyncService` holds the existing socket | Chosen to avoid Google dependency; cost is visible foreground notification and battery sensitivity; not push parity |
| 007 | Rust media private by default; Cloudflare migration pending | Self-hosted server must check conversation membership; R2 public read remains high-risk |
| 008 | WebRTC 1:1 P2P + TURN; group calls mesh, no SFU yet | Suitable for small calls only; SFU evaluation required before scale claims |
| 009 | No E2EE until a protocol/key-lifecycle project | TLS/JWT/Keystore are not E2EE; server stores plaintext |
| 010 | PBKDF2 100k retained for Worker/Rust byte compatibility | OWASP now recommends 600k for PBKDF2-HMAC-SHA256; migration debt recorded **Note 2026-11-10:** Rust can now write a higher count and upgrade to it (`NCHAT_PBKDF2_ITERATIONS`); the default stays 100k for compatibility — see §27. |
| 011 | Static Compose backgrounds, no tab cross-fade, no per-row entry animations/shadows | Root cause of historical tab-switch jank; preserve for low-end devices |
| 012 | Public demo signing key retained for update compatibility | Any rotation breaks existing installs; requires explicit migration plan |
| 013 | CI is source of truth when local Gradle/cargo unavailable | Never claim local compile success; stale `.ci/last-failure.log` is not status |
| 014 | One central `agent.md`; no conflicting status docs | Enables handover across agents/models; this file supersedes nine docs |
| 015 | Device-local drafts/stars/pins/archive/wallpaper are intentional | Worker has no wire fields; must not be presented as account-synced |
| 016 | Updates stay optional: the update page keeps “Not now”; nothing is forced (owner, 2026-11-10) | The page asks again at the next start and, from 1.0.75, at most every six hours when the app returns to the foreground. A forced update would need a new manifest field and the owner's decision |
| 017 | The Phase 1 branch is merged only when all of Phase 1 is finished (owner, 2026-11-10) | Keeps `main` and the released app as they are until then. Cost: the updater repair (1.0.75) and the Rust fixes wait with it, and the branch must be checked against `main` before merging (§27, Android rule 13) |
| 018 | Sign-in attempts are limited per account as well as per address (Rust, 2026-11-10) | The address is client-written unless a trusted proxy sets it; a per-account budget cannot be reset by the client. Accepted cost: a targeted, temporary sign-in lockout for new devices |

---

## 32. Recent changes and commits

### `NChat-kotlin` `main` (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `ec0f86cd2899` | 2026-11-10 08:09:29 | Merge PR #88 — `agent.md` update only (row added 2026-11-10) |
| `a867fdd88174` | 2026-11-10 08:05:44 | Merge PR #87 — Add Phase 0 agent.md to main |
| `d87b70302fba` | 2026-11-09 08:15:46 | Merge PR #86 — Add system downloader fallback for APK updates |
| `bc36307321ae` | 2026-11-09 08:11:38 | Add system downloader fallback for APK updates |
| `429164eead3f` | 2026-11-09 07:57:15 | Merge PR #85 |
| `fb1e5211f509` | 2026-11-09 07:53:09 | Prepare 1.0.73 in-app update test |
| `868e10cddc5c` | 2026-11-08 15:08:42 | Merge PR #84 |
| `9c27d63a5436` | 2026-11-08 15:03:43 | Harden APK update signing and downloads |
| `b6e5721f43e6` | 2026-11-08 14:44:44 | Merge PR #83 |
| `37ecd28beb0d` | 2026-11-08 14:40:23 | Fix Android compile errors in account-scoped state |
| `82f72cdf45fa` | 2026-11-08 14:39:26 | ci: capture build failure log [skip ci] |
| `bd3530094af6` | 2026-11-08 14:37:59 | Fix onboarding and chat relationship flows |
| `c66c4a864f6c` | 2026-11-08 10:46:24 | Merge PR #82 |
| `82526f293392` | 2026-11-08 10:39:53 | Test default and selected profile avatar handling |
| `9c263c2fc651` | 2026-11-08 10:35:25 | Improve profile setup and conversation previews |
| `1668cfa74f8e` | 2026-11-08 10:25:22 | Verify release signing certificate compatibility (#81) |
| `b70e4174b35a` | 2026-11-08 09:50:25 | Merge PR #80 |

Additional version-marker commit: `d1be4f5d16` “Release NChat 1.0.69” (2026-11-08 07:58:01). Current `version.json` is 1.0.74; release notes describe the Android system-download fallback for direct-transfer update timeouts.

### `NChat-kotlin` branch `arena/56647832-nchat-kotlin` — Phase 1, Rust; not merged (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `a1d23985852a` | 2026-11-10 09:54:54 | ci: drop the backend failure log now that the signal fix is in |
| `feb3600b1ad0` | 2026-11-10 09:53:01 | fix(server): call signals cannot relabel themselves under any spelling |
| `88c84137271f` | 2026-11-10 09:54:32 | ci: backend failure log [skip ci] |
| `17c4c7fe79fc` | 2026-11-10 09:52:52 | test(server): signal envelope under every spelling a client reads |
| `a5cd7512419d` | 2026-11-10 09:40:17 | test(server): only the author can delete a status |
| `ccd83fe601f0` | 2026-11-10 09:32:39 | chore(ci): remove the backend failure log of the tests-only commit |
| `d247d398e004` | 2026-11-10 09:32:22 | fix(server): unfriending announces only a removal that happened |
| `b647f5a240b4` | 2026-11-10 09:32:22 | fix(server): enforce the status audience everywhere a status can be seen |
| `84ceec587a7a` | 2026-11-10 09:32:22 | fix(server): call signalling cannot forge events or leave the call |
| `0ec858c5fa03` | 2026-11-10 09:32:22 | fix(server): group routes act only for members, and stop exposing member e-mails |
| `2d809769e52f` | 2026-11-10 09:26:00 | ci: backend failure log [skip ci] |
| `22371ff4c147` | 2026-11-10 09:24:06 | test(server): authorization regression suite (agent.md §33, Phase 1) |

Dates are author dates. The two `ci: backend failure log [skip ci]` commits were made by the workflow on the two red-by-design runs; each log was removed again by the commit that follows the fix. Commits after `a1d23985852a` on this branch change only `agent.md`.

### `NChat-kotlin` branch `arena/56647832-nchat-kotlin` — second Phase 1 turn: updater repair, sign-in; not merged (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `2b30f1d9f0da` | 2026-11-10 11:12:33 | ci: remove the backend failure log of the expected red run |
| `dd4b49758093` | 2026-11-10 11:12:33 | fix(server): a per-account sign-in budget, and weaker password hashes are upgraded |
| `5f0b2d95b8b4` | 2026-11-10 11:10:43 | ci: backend failure log [skip ci] |
| `6eee8c692c54` | 2026-11-10 11:10:00 | test(server): sign-in guesses and weak stored password hashes |
| `8a506c863d8b` | 2026-11-10 11:00:51 | release: NChat 1.0.75 (versionCode 83), in-app update repair |
| `4b3f2a167ad8` | 2026-11-10 11:00:51 | fix(update): look again on return, continue after the install permission, say what failed |
| `5091dbb83c3e` | 2026-11-10 10:51:25 | ci: put the stale Android failure log back to main's copy |
| `c5eed2642216` | 2026-11-10 10:51:25 | fix(update): read the signer of a downloaded APK on Android 9, 10 and early 13 |
| `be9759610f0b` | 2026-11-10 10:50:23 | ci: capture build failure log [skip ci] |
| `6bc65dc113ea` | 2026-11-10 10:48:25 | test(update): the updater must read an APK file's signer on every Android release |

Below these is `e2709bd` (“docs(agent.md): record the Rust half of Phase 1”). The two `[skip ci]` commits were made by the workflows on the two red-by-design runs. `.ci/last-failure.log` exists on `main` as a stale copy, so it was put back to `main`'s content rather than deleted; `server/.ci/last-failure.log` does not exist on `main` and was deleted. “release:” in `8a506c863d8b` is a version bump on a branch — nothing was published. Commits after `2b30f1d9f0da` change only `agent.md`.

### `nchat-friends` `main` (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `616a9e69407b` | 2026-11-03 18:35:35 | Merge PR #11 — username-search verification |
| `05588eac7d9b` | 2026-11-03 18:35:13 | Record verification that the username-search work is fully merged |
| `dff24dcc49eb` | 2026-11-03 16:19:57 | Merge PR #10 |
| `901122263a56` | 2026-11-03 16:19:39 | Add real-time, full-database username search across the app |
| `20ce2db34569` | 2026-11-03 10:46:49 | Merge PR #9 |
| `af548a7def9e` | 2026-11-03 10:46:27 | Fix header dropdown rendering behind messages + responsive long-press menu |
| `02f0b727476f` | 2026-11-03 10:35:00 | Merge PR #8 |
| `63890311648e` | 2026-11-03 10:34:44 | Document glass-card + transform/opacity animation gotcha |
| `92e44a33acff` | 2026-11-03 10:33:59 | Fix real code bug: separate entrance animation from backdrop-filter |
| `381da1c8544f` | 2026-11-03 10:23:00 | Merge PR #7 |
| `41da34aa9cb2` | 2026-11-03 10:22:43 | Strengthen glassmorphism recipe + comprehensive project history |
| `4ff2bdacedaa` | 2026-11-03 09:56:30 | Merge PR #6 |

Appendix B preserves the condensed 1.0.1–1.0.68 release history and important historical engineering decisions.

---

## 33. Next recommended tasks

**Implementation roadmap and Phase 0 status:**

**Phase 0 — COMPLETED:** this audit, `agent.md`, baseline verification, competitor comparison, roadmap. No application code changed.

**Phase 1 — Critical security (next precise task; requires go-ahead):** start with an authorization regression test suite in a safe local/staging Worker + Rust environment. Then, in small commits:

1. Make Cloudflare `/media/file/*` private: bearer-token membership check or expiring signed URL, remove public immutable cache, validate MIME/size, and cover unauthorized/range/expiry cases.
2. Harden `/realtime/connect`: fail closed to personal rooms (mirror Rust) or implement membership checks for every room type; stop relaying arbitrary client frames; evaluate Durable Object Hibernation.
3. Add membership checks to `/groups/:id/members`, group reactions, and all group message action routes; redact emails.
4. Enforce status privacy on feed, `status:new`, `/status/:id/view`, `/status/:id/views`, and direct media; owner-only viewer list.
5. Add participant/friend/block checks to Worker DM send/edit/delete/reaction/read/typing; reconcile blocking policy.
6. Make message persistence durable before broadcast; add sequence/cursor and missed-event recovery.
7. Add server-side rate limits, CORS allowlist, admin role, and CSRF/XSS/SSRF/input-validation review.
8. Plan PBKDF2 iteration migration with stored-prefix compatibility and a copy-based test. Deliverable: security remediation report + passing tests.

**Phase 1 status — 2026-11-10.** The Rust half is done on branch `arena/56647832-nchat-kotlin` (not merged). “Done” means: a test failed in CI before the fix and passes after it. *Clarification added after the second turn:* “the Rust half” in that sentence means items 1–5 and the added call-signal row. Items 6 and 7 are not finished in Rust either; the table says how far each one is.

| Item | Rust backend | Cloudflare Worker |
|---|---|---|
| 1 Private media | Was already private; status media now follows the status audience (`b647f5a`). One over-restriction discovered, not fixed (§19.3 #23) | NOT_STARTED |
| 2 Realtime rooms | Was already fail-closed; covered by an `api.rs` test | NOT_STARTED |
| 3 Group membership checks, e-mail redaction | Done — `0ec858c` | NOT_STARTED |
| 4 Status privacy | Done — `b647f5a` (§9 item 11) | NOT_STARTED |
| 5 DM participant/friend/block checks | Was already enforced; now covered by `authz.rs` | NOT_STARTED; blocking policy not reconciled |
| 6 Durable persistence, sequence, cursor | NOT_STARTED as work. Reviewed 2026-11-10: `messages::send` already persists before it publishes (§19.4). Sequence, cursor and missed-event recovery are a protocol change for both servers and the app; they overlap Phase 3 and need a design decision first | NOT_STARTED |
| 7 Rate limits, CORS allowlist, admin role, input review | IN_PROGRESS — per-account sign-in budget done (`dd4b497`, §19.4 #28); SSRF, CORS and the admin gate reviewed (§19.4). Open: the real client address behind a proxy, a per-address sign-up limit, limits beyond sign-in, the CORS allowlist, sign-in timing | NOT_STARTED — no rate limiting at all in `worker/src` (§19.4 #28) |
| 8 PBKDF2 migration plan | Done as far as it can be without real data — `dd4b497`: the upgrade mechanism, the plan in §27, and tests whose stored hashes were computed by another implementation and copied into the test database. **Not done:** a run against a copy of a real database (none is available to the agent). The default count is unchanged on purpose | NOT_STARTED — the runtime's PBKDF2 limit has to be verified first (§27) |
| Added: call-signal integrity and false announcements (§19.3 #11–#19) | Done — `0ec858c`, `84ceec5`, `d247d39`, `feb3600` | NOT_STARTED |
| Added: in-app updater repair, Android 1.0.75 (§19.4 #24–#27) | Not a server item. Android: done on the branch (`c5eed26`, `4b3f2a1`, `8a506c8`); not run on a device; not released | Not applicable |

**Owner decisions, 2026-11-10 (second turn).** Given as answers to two structured questions; the owner's free-text wording was not kept, so none is quoted. (1) *Merge:* the Phase 1 branch goes to `main` only when **all** of Phase 1 is finished (answer `all_phase1_then_merge`). No pull request and no merge before that; this also keeps 1.0.75 from phones until then. (2) *Updates stay optional:* “Not now” is kept; nothing is forced. (3) *What had happened with earlier updates:* the answer was “Downloaded, then an error”. The exact message and the Android version were not given; ask for both if it happens again. Still unanswered from the first turn: other users' e-mail addresses in `/users` and `/friends` responses, and §19.3 #23.

**What “all of Phase 1” still needs (2026-11-10, after the second turn).** *Worker:* everything — items 1–5, the call-signal findings, rate limiting, the password guards. It needs a session on `nchat-friends`, and first a way to run Worker tests. *Rust:* the rest of item 7 (listed in the table) and item 6's sequence and cursor, which is a design decision before it is code. *Android:* nothing is required by Phase 1 itself; `handleGroupEvent` not matching `group-call:participant-left` (§24 #18) is a known client bug outside it.

**Next precise task (as of 2026-11-10):** port the same checks to the Worker in `nchat-friends`, test-first: `worker/src/routes/groups.ts`, `status.ts`, `calls.ts`, `groupCalls.ts`, `friends.ts`, then `media.ts` and the realtime room rule. `server/tests/authz.rs` is the specification — each test is one request and the answer it must get. This needs a session on that repository and, first, a way to run Worker tests (there is none). Decisions to get from the owner before or alongside: whether other users' e-mail addresses stay in `/users` and `/friends` responses, whether to fix §19.3 #23, and merging the Phase 1 branch. *Update after the second turn:* unchanged — the Worker is the next task, and it is now also what unblocks the merge; `server/tests/auth_hardening.rs` joins `authz.rs` as specification. In this repository the next contained step is the client address behind a proxy (`ratelimit::client_key`, `ConnectInfo`, a trusted-proxy count), test-first.

**Phase 2 — Core account lifecycle:** phone/OTP or documented email verification, country/format validation, duplicate detection, expiry/resend/attempt limits, device/IP rate limits, default privacy/notification/security settings, session/device registry, logout/re-login, account deletion/export. Deliverable: tested complete lifecycle.

**Phase 3 — Core messaging:** durable event sequence, delivery/read receipts, pagination, offline queue, reconnect sync, multi-device ordering, deduplication, and network interruption tests. Deliverable: reliable 1:1 messaging.

**Phase 4 — Contacts/groups:** contact sync with permission, friend-request authorization, group roles/permissions, invite links, join approval, ownership transfer, server-enforced group authorization. Deliverable: complete social/group system.

**Phase 5 — Media/voice:** storage quota, MIME/extension validation, thumbnails, cancellation/retry, cleanup, encrypted attachment strategy, playback/seek/interruption tests. Deliverable: complete media workflow.

**Phase 6 — Notifications:** choose FCM/Web Push architecture, token registration/refresh/cleanup, notification preferences, grouping, deep links, dedupe, killed-app delivery tests. Deliverable: real push.

**Phase 7 — Calls:** prove actual media flow, TURN across NATs, background/full-screen incoming calls, Bluetooth routing, group video/SFU evaluation, call cleanup. Deliverable: verified calls.

**Phase 8 — Status/advanced messaging:** privacy audiences, viewer authorization, reactions/forward/edit/search/archive/pin/star with server sync where required, disappearing/view-once with TTL schema. Deliverable: advanced messaging set.

**Phase 9 — E2EE:** evaluate Signal Protocol/MLS; identity/device/prekeys, sessions, group sender keys, safety numbers, encrypted attachments/backups, multi-device key lifecycle, interoperability tests. Deliverable: security-reviewed E2EE, not a UI label.

**Phase 10 — UI/UX/performance:** Compose UI tests, accessibility, dark mode, tablet/foldable, low-end profiling, list/image/media optimizations, measured startup/frame/memory/battery. Deliverable: polished app.

**Phase 11 — Production readiness:** migration runner, backups/restore drill, monitoring/alerting, rate limits, deployment/rollback runbook, load tests, security review, release verification. Deliverable: production-readiness report with objective evidence.

Do not proceed to destructive DB operations, production deployment, secret rotation, key migration, or force pushes without explicit authorization.

---

## 34. Rollback procedures

1. **This Phase 0 change:** documentation-only. To roll back, `git revert <phase0-commit>` or restore the nine deleted `.md` files from the parent commit; no database, API, app code, or deployment state depends on them. Keep `agent.md` unless deliberately replacing it with a corrected version.
2. **Application code changes:** revert the specific commit on `arena/d8051bf9-nchat-kotlin`, push only to that branch, and run Android/Rust/worker checks. Never force-push or rewrite shared history.
3. **Database migrations:** take a SQLite-aware backup plus a media-directory snapshot before any migration; test on a copy; document forward-only limitations. `ALTER TABLE ADD COLUMN` has no automatic down-migration; rollback may require restoring the backup, never deleting/recreating production data.
4. **Cloudflare deployment:** use a known-good Workers Build deployment or redeploy the previous Git commit through the dashboard after authorization; verify `/health`, bindings, and `__BUILD_COMMIT__`. Do not retry an old deployment expecting a new branch/commit.
5. **APK release:** keep the same signing certificate for in-place updates; revert `version.json`/`app/build.gradle` together and republish a versioned release asset. A signing-key migration requires explicit user plan because existing installs cannot update across keys.
6. **Secret/session incidents:** rotate `JWT_SECRET` only with explicit authorization; understand that all existing access/refresh tokens become invalid. Preserve the demo signing key until a migration is coordinated.
7. **Local caches:** sign-out clears account-scoped JSON caches; if rolling back a cache format change, retain compatibility readers or restore from user backup. Never delete pending outbox data to resolve a migration error.
8. **Phase 1 Rust authorization changes (2026-11-10):** code only — no schema, data, configuration or client change. `git revert` the specific fix commit on `arena/56647832-nchat-kotlin` and push to that branch: `0ec858c` (groups), `84ceec5` then `feb3600` (call signalling; revert the later one first), `b647f5a` (status audience), `d247d39` (friends). The matching tests in `server/tests/authz.rs` will then fail, which is what they are for; change them in the same commit and say why. Nothing has to be restored in a database.

---

## 35. Agent handover instructions

**Before starting any future task:**

1. Read this entire `agent.md`.
2. Check `git log -1`, current branch, and the latest progress entries in Appendix E.
3. Verify the recorded implementation still exists by targeted source inspection; do not trust stale documentation.
4. Search this file and the source tree for an existing implementation before building anything.
5. Record task ownership: assigned task, relevant files, expected changes, dependencies, API contracts, database changes, testing requirements, and conflicts.
6. Use one primary status per feature from the allowed vocabulary. Update status only with evidence.
7. Coordinate edits to auth middleware, migrations, API types, message models, WebSocket handlers, navigation, and global state; one owner for integration.

**After finishing:**

1. Update the feature matrix, completed/partial/remaining/blocked sections, technical debt, and next task.
2. Record modified files, database/API changes, tests executed and actual results, unresolved problems, commit hash, and last verified date.
3. Append a chronological handover entry in the format: Task / Agent / Start commit / End commit / Files modified / Database changes / API changes / Tests executed / Test results / Known limitations / Remaining work / Next recommended task.
4. Never erase history. If documentation is wrong, correct it and explain why.
5. Maintain one central memory file; do not create a competing status document.
6. Never mark `COMPLETED` from a screen, button, HTTP 200, table, mock, local demo, or compile alone.

---

## 36. Verification and evidence log

| Date UTC | Check | Command / source | Actual result |
|---|---|---|---|
| 2026-11-09 | Repository/branch state | `git status --short --branch`, `git log`, `git ls-remote` | Working branch active at `d87b703`; branch initially absent from origin; clean except transient `__pycache__` later removed |
| 2026-11-09 | Repository discovery | `gh api user/repos`, `gh api search/repositories?q=NChat user:cybereagle472` | Exactly two NChat repositories; no separate frontend repo |
| 2026-11-09 | Backend latest commit | fresh `git clone --depth 1` of `nchat-friends`; `git log -1`; `gh api branches` | `616a9e69407b072de29f640f35ea05c6f48b9768`, 11 branches |
| 2026-11-09 | Android latest CI | `gh api commits/d87b703.../check-runs`, `gh run list --workflow=build-apk.yaml` | `build` conclusion success at 08:20:56; run `42584666249` success |
| 2026-11-09 | Rust backend CI | `gh run list --workflow=backend.yml` | Last success `42491305703` on 2026-11-08 at `b6e5721...`; no run on current HEAD due path filter |
| 2026-11-09 | Cloudflare deployment CI | `gh api commits/616a9e6.../check-runs` | Both `Workers Builds: nchat-friends-deploy` and `nchat-friends` failed at 18:38; detailed dashboard logs unavailable |
| 2026-11-09 | Local Python tests | `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v` | Ran 7 tests in 0.001 s, OK |
| 2026-11-09 | Worker typecheck | `npm ci --prefix worker && npm run typecheck --prefix worker` | Exit 0; 64 packages installed |
| 2026-11-09 | Web frontend typecheck/build | `npm ci && npm run type-check && npm run build` | Exit 0; 1649 modules transformed; Vite build in 3.14 s; main JS 435.51 kB / 116.98 kB gzip |
| 2026-11-09 | Local toolchain | `command -v java gradle cargo rustc` | `java`, `gradle`, `cargo`, `rustc` not found; Python 3.11.2, Node 22.22.3, npm 10.9.8 available |
| 2026-11-09 | Source inspection | `find`, `wc -l`, `sed`, `grep`, Python route/table parsers | 136 Kotlin, 27 Rust, 9 project-owned Markdown files initially; Cloudflare 81 endpoints, Rust 72 route patterns; 22 schema tables; 73 Android API methods with `declineGroupCall` as only no-caller method |
| 2026-11-09 | Security re-verification | targeted reads of `worker/src/index.ts`, `media.ts`, `messages.ts`, `groups.ts`, `status.ts`, `groupCalls.ts`, `lib/*`, Rust corresponding files | Findings 1–10 and additional issues in §19 verified against current source |
| 2026-11-09 | Full Markdown end-to-end reading | all 9 `NChat-kotlin` project-owned `.md` files and all 11 `nchat-friends` project-owned `.md` files; SHA-256 of each concatenation | Main digest `b41168d674e049fc2d546df89913975131d91b5bc8162d6bb237de9c5385a742`; backend digest `dcb1b44a3ea0c2e6a8bce2945f5e0764a373f45ef30dc5a5dcc3f1f9c2fef3a6`; all 20 files read, none truncated |
| 2026-11-09 | Documentation consolidation | source-file verification after full reading; Appendix D ledger | Still-relevant technical, architecture, setup, API, plan, prior-agent, known-bug, development, deployment, legal/security content preserved; stale claims labelled; no dependency/vendor/legal files removed; backend files not deleted |
| 2026-11-09 | Backend stale-claim scan | `grep -RniE 'disappearing\|e2ee\|end.to.end\|two.step\|otp\|account deletion\|invite\|report' worker/src src`, service-file inventory, `package.json` | No disappearing/E2EE/two-step/OTP/account-deletion/report route or service; `src/services/` has 11 files, no `disappearingService`, `encryptionService`, or `pushNotificationService`; React 18.3.1; no Radix/ESLint dependencies |
| 2026-11-09 | Application code changes | `git status`/diff after this change | None: only `agent.md` added and nine project-owned `.md` files deleted; no schema/API/config/deployment/secret changes |
| 2026-11-09 | Phase 0 substantive commit | `git commit` on `arena/d8051bf9-nchat-kotlin` | `f45480678630810eff1a525469d83d1feca0021f`; 10 files changed, 1082 insertions, 2104 deletions; working tree clean after commit |
| 2026-11-10 | PR #87 checks | `gh pr checks 87 --watch` | Android `build` passed in 58 s; Rust `Build and test` passed in 1 m 3 s; PR mergeable |
| 2026-11-10 | Phase 0 on main | `gh pr merge 87 --merge`, `gh pr view 87`, `git ls-remote`, `git show origin/main:agent.md` | PR #87 merged at 2026-11-10 08:05:44 UTC as `a867fdd881740889ee153013323072c1ebb8ea79`; `agent.md` present on `main` (1083 lines); `agent.md` is the only project-owned Markdown file there; no direct push to `main` |
| 2026-11-10 | `main` head re-check | `git ls-remote origin main`, `gh pr view 88`, `gh run list --workflow backend.yml --branch main` | `main` is `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (PR #88, `agent.md` only). Rust backend run `42779970114` was green on `a867fdd` at 08:05:48Z, so the “not re-run on HEAD” statements elsewhere in this file were already out of date for the 18-test suite |
| 2026-11-10 | Phase 1: authorization suite, tests only (red by design) | push `22371ff`; Rust backend run `42790409920` | Failure. `authz` 15 passed / 27 failed; unit tests 15/15; `api` 9/9. `cargo test` stops at the first failing target, so `groups_status_calls` and `media` did not run. Log: `git show 2d80976:server/.ci/last-failure.log` |
| 2026-11-10 | Phase 1: fixes | push `ccd83fe`; Rust backend run `42791596474`, then one rerun of it | Success twice. Tests, `Cargo.toml` and workflow identical to the red commit |
| 2026-11-10 | Phase 1: guard test for status deletion | push `a5cd751`; Rust backend run `42792659202` | Success |
| 2026-11-10 | Phase 1: signal-envelope follow-up, tests only (red by design) | push `17c4c7f`; Rust backend run `42794405563` | Failure. `authz` 41 passed / 4 failed — exactly the two extended and two new signal tests; unit tests 16/16; `api` 9/9. Log: `git show 88c8413:server/.ci/last-failure.log` |
| 2026-11-10 | Phase 1: signal-envelope fix | push `a1d2398` (the fix is `feb3600`); Rust backend run `42794684951` | Success: Build success, Test success, failure-log step skipped. This is the evidence for the Phase 1 end commit |
| 2026-11-10 | Android APK workflow on the Phase 1 branch | `gh run list --branch arena/56647832-nchat-kotlin` | Success on every push: runs `42790409849`, `42791596578`, `42792659515`, `42794405898`, `42794684870`. No Android file was changed |
| 2026-11-10 | Rust format check | check-run annotations, `gh api repos/cybereagle472/NChat-kotlin/check-runs/<job id>/annotations` | “Process completed with exit code 1.” on `main` runs `42491305703` and `42779970114` and on every Phase 1 run checked: `cargo fmt --all -- --check` fails. The step is advisory; its diff cannot be read from the sandbox. Clippy's result cannot be observed at all |
| 2026-11-10 | Local Rust compile or test | `command -v cargo rustc` | No toolchain. The repository's Rust code was never compiled or tested locally. Before each push a tree-sitter syntax check was run on the changed files; it is not compile evidence |
| 2026-11-10 | Worker source re-read | fresh `git clone --depth 1` of `nchat-friends` (`616a9e69407b072de29f640f35ea05c6f48b9768`); reads of `worker/src/routes/{groups,status,calls,groupCalls,friends}.ts`, `worker/package.json` | The gaps fixed in Rust this turn are still open in the Worker, except where §19.3 says the Worker differs (#20, #22, #23). No test files and no test script exist there. Nothing was executed or changed |
| 2026-11-10 | Phase 1 application code changes | `git diff --stat ec0f86c..a1d2398` | 9 files changed, 2400 insertions, 55 deletions: `server/src/privacy.rs`, `server/src/routes/{calls,friends,group_calls,groups,media,mod,status}.rs`, `server/tests/authz.rs` (new, 2029 lines). Route table unchanged (72 `route(` lines); `server/schema.sql`, `app/` and `.github/` untouched |
| 2026-11-10 | Updater: which flag loads a file's certificates | AOSP source read through the GitHub contents API (`aosp-mirror/platform_frameworks_base`): `PackageManager.getPackageArchiveInfo`, `ApplicationPackageManager`, `PackageParser.generatePackageInfo` | Only `GET_SIGNATURES` collects a file's certificates at `android-7.0.0_r1`, `8.0.0_r1`, `9.0.0_r1`, `10.0.0_r1`, `13.0.0_r1`, `_r2` and `_r3`. Either flag does at `11.0.0_r1`, at `12.0.0_r1` and `12.1.0_r1` (there in `ApplicationPackageManager`), at `13.0.0_r16`, `_r30`, `_r41`, `_r52`, `_r67` and `_r84`, and at `14.0.0_r1`, `14.0.0_r75`, `15.0.0_r1` and `16.0.0_r3`. `PackageParser` at `9.0.0_r1`, `10.0.0_r1` and `13.0.0_r1` leaves `signingInfo` null when no certificates were collected. Tags between `13.0.0_r3` and `_r16` were not read. Source reading; no device; vendor builds not covered |
| 2026-11-10 | Updater: signer test alone (red by design) | push `6bc65dc`; APK run `42802241927` | Failure at “Run Android unit tests”: 58 tests, 1 failed — `UpdateSignerPolicyTest.asksForAnApkFilesCertificatesTheWayEveryAndroidReleaseLoadsThem` |
| 2026-11-10 | Updater: signer fix | push `5091dbb` (the fix is `c5eed26`); APK run `42802654826` | Success: unit tests, signed release build, release verification |
| 2026-11-10 | Updater: page behaviour, messages, version 1.0.75 | push `8a506c8`; APK run `42803934030` | Success; `verify_release_apk.py` accepted 1.0.75 / 83 with the pinned signer; the publish step was skipped (not `main`) |
| 2026-11-10 | Release-script tests | `python3 -m unittest` in `.github/scripts` | 7 tests OK, locally |
| 2026-11-10 | Sign-in: tests alone (red by design) | push `6eee8c6`; Rust run `42805222571` | Failure: `auth_hardening` 4 passed, 2 failed — “60 of 60 guesses were checked…” and “the 1 000-round hash is still stored”. The passing four include hashes computed by Python verifying in Rust. Unit tests 16/16, `api` 9/9; later targets did not run |
| 2026-11-10 | Sign-in: fix | push `2b30f1d` (the fix is `dd4b497`); Rust run `42805578387`; APK run `42805578589` | Both success. Rust: Build and Test steps success, failure-log step skipped |
| 2026-11-10 | Worker sign-in, read only | clone at `616a9e6`: case-insensitive search of `worker/src` and `worker/wrangler.toml` for rate-limit, throttle, “too many” and 429; read of `worker/src/lib/password.ts` | No match: no application-level limit. The password check reads the stored count and has no bound and no upgrade |
| 2026-11-10 | Device or emulator test of the updater | — | **Not done; not possible here.** Nothing about install behaviour is verified |
| 2026-11-10 | Code changes of the second turn | `git diff --stat e2709bd..2b30f1d` | 16 files, 955 insertions, 84 deletions: 7 Android source files, 3 Android test files, `app/build.gradle`, `version.json`, 4 Rust source files, `server/tests/auth_hardening.rs` |

**Current project health:** Android main build/CI green and PR #87 checks passed on the merged Phase 0 change; web/worker local typecheck/build green; Rust integration suite green on a recent prior commit but not re-run on HEAD; Cloudflare deployment blocked/failing; security status has multiple confirmed high-risk authorization gaps; no feature claims of full product completion; no known active Android compile regression; no production deployment performed; no destructive operation performed; `agent.md` is now available to future agents from `main`.

**Current project health — update 2026-11-10 (Phase 1 branch, not merged):** Rust backend — build and all tests green in CI on `a1d2398`, now including an authorization suite; the Rust authorization gaps of §19.1 #4–#7 and §19.3 #11–#22 are closed there, #23 and the residual risks are open. Cloudflare Worker (the reference backend, ADR 002) — unchanged: those gaps are still present there (§19.3 lists the three that take a different shape), as are public media and open realtime rooms, and its deployment still fails. Android — no change; APK workflow green on the branch. Rust format check red (advisory) on `main` and on the branch; clippy not observable. No deployment, schema change, secret change or destructive operation was performed.

**Current project health — update 2026-11-10, second Phase 1 turn (same branch, not merged):** Android — the in-app updater is repaired in source as 1.0.75 and CI is green (unit tests, signed release build, release verification), but **no update has been installed on a device in any recorded session**, so the feature stays `IMPLEMENTED_UNVERIFIED`; the published app is still 1.0.74, with the signer defect of §19.4 #24. Rust backend — build and tests green on `2b30f1d`; sign-in is limited per account and password hashes can be upgraded. Cloudflare Worker — unchanged, and now known to have no sign-in limit at all. Phase 1 is **not** complete, and by the owner's decision nothing is merged until it is. No deployment, release, schema change, secret change or destructive operation was performed.

---

# Appendix A — Consolidated historical source-document digest

This appendix records the provenance of information removed from separate Markdown files. Full verbatim source text remains in Git history at the parent commit. Stale statements are kept as historical context, not current truth.

### A.1 `ARCHITECTURE.md` → §§4, 5, 16, 30, 31

Historical rationale: Kotlin/Compose for frame-perfect UI, Rust/tokio for low-latency state, JSON boundary with one wire model, UI never talks HTTP directly, one backend source of truth, server owns ordering/unread, client owns presentation, no offline mode at that time. Preserved latency budget: `POST /messages` ~30 µs, `GET /messages?limit=50` ~90 µs, WS fan-out ~25 µs, cold start ~15 ms — explicitly an unmeasured early estimate. Historical claim of camelCase and in-memory repositories is **stale**: current source uses snake_case and remote repositories. Historical migration plan steps 1–4 are now implemented; offline queue, push, and WebRTC evolved separately.

### A.2 `PARITY.md` → §§18, 20, 21, 22, 32

A 1.0.14 audit against `nchat-friends` v2.0.0: 19/19 screens cloned, 120 feature points reviewed → 67 ✅, 14 🟡, 39 ❌; visual layer complete, behavior partially local, platform layer absent at that time. Preserve its per-screen findings (chat list, conversation, group chat, message actions, calls, status, Discover, groups, profile/settings, auth, app shell) as a historical baseline. Its 14–29 item four-phase UI/platform plan is superseded by current source and §33. Caveats: “1:1” meant layout/copy/palette/motion, not real data; several inert reference rows were intentionally mirrored. Do not use its ❌ labels as current status.

### A.3 `SECURITY.md` → §§9, 12, 15, 19, 26, 27, 34

Preserved security content: release HTTPS-only, debug HTTP only, TLS termination in front of Rust, `wss://` derived from HTTP root; build-time backend address and no runtime switcher; `SecurePrefs` AES-256-GCM with Android Keystore, legacy plaintext/type coercion, graceful degradation, sign-out clears tokens/session/socket; PBKDF2 100k byte-compatible; JWT HS256 with `JWT_SECRET`/`NCHAT_SECRET` and ephemeral development fallback; Rust auth throttling 10/300 s; upload cap 100 MB; path-traversal validation; private Rust media with conversation membership or signed expiring links; 404 for strangers; admin env gate; permissive CORS; request spans omit query strings; public demo APK signing key; no E2EE, no device list/2FA, historically device-local blocking, no certificate pinning. Deployment checklist: set persistent `JWT_SECRET`, `NCHAT_ADMIN_EMAIL`, `PUBLIC_API_URL`, upload/rate variables, `NCHAT_DATA_DIR` off web root; terminate TLS; back up database + media; configure `X-Forwarded-For`/`X-Real-IP`. Historical statement that blocking is device-local in Android remains partly true but Worker/Rust behavior differs (§19).

### A.4 `UPDATE_SETUP.md` → §§15, 27, 34

Preserved update-signing migration procedure, pinned fingerprint, versioned release tag/asset URL, direct download vs Android DownloadManager fallback, browser release-page fallback, `apkUrls` mirror constraints (up to three, same exact signed APK, verified package/version/signing), release publication delay warning, and “never uninstall to work around a signing error without protecting local-only data.” The current workflow uses a public demo key; migration to private signing requires explicit authorization.

### A.5 `docs/AUDIT-1.0.39.md` → §§10, 11, 20, 24, 28, 29

Preserved realtime inventory and 1.0.39 fixes: socket never exits, immediate/capped 8 s backoff, token-aware 401 handshake, foreground wake, reconnect catch-up, typing expiry 6 s, 3 s fallback poll only while socket down, visible connection pill, incremental event application, and open to-dos. Historical P3 claim that incoming calls never ring is stale; current `CallRepository`/`NChatApp` handle `call:incoming`. Historical P4 asks for `handleEvent` unit tests and API latency summary — still technical debt.

### A.6 `docs/BACKEND-WIRING.md` → §§8–14, 20, 24

Preserved rule: a route is wired only when a real user action or handled socket event reaches it; a screen is real only when server-backed. Preserved route-to-app map: auth/users/friends/messages/groups/status/privacy/media/calls and realtime events, including the deliberate `GET /groups/:id/members` no-caller decision and operator-only `/admin`. Preserved 1.0.40/1.0.41 call signaling/WebRTC notes, 1.0.43 local store/connectivity rules, 1.0.45 voice-note rules (`type=voice`, disk before network, never post `file://`, waveform in `thumbnail_url`), 1.0.46 message preview/delete/reply/reaction/forward rules, 1.0.47 authenticated media playback rules, 1.0.48 gesture ownership/upload progress, and 1.0.50 deliberately local drafts/starred/in-chat search. Some “fixed/uncalled” labels are stale after 1.0.74; current source and §8 govern.

### A.7 `docs/WHATSAPP-PARITY.md` → §§17, 18, 20, 21, 32

Preserved 1.0.48+ parity table and wave history: text/ticks/edited, reply, reactions, forward, delete, in-chat search, multi-select, starred, drafts, photo pager, voice speed, real waveform/scrub/lock/cancel, typing/recording, text formatting, pinned message, message info, camera, location, media index, disappearing/view-once marked missing/server-blocked; groups create/rename/roles/voice calls/mentions/private reply; chat list filters/archive/swipe/notifications/background call; wallpaper/storage; E2EE/multi-device/backup missing. Waves 1.0.49–1.0.68 preserved in §20. The “Next 1.0.57 group video” item is still not implemented.

### A.8 `eagle-x.md` → most sections and Appendices B/C

The former “whole story” document described intent, design system, version history, feature cost, build/sign/release contract, hard-won lessons, performance rules, reference component status, roadmap, and working agreement. Its header claimed version 1.0.68 and no tests/push/offline DB; those claims are stale. Still-binding content is preserved in §§4–17, §20, §24, §27, §30–32 and Appendices B/C. Historical deviations: Rust timestamps strictly increasing; realtime rooms process-local; media objects are files. Hard-won Compose/Kotlin/icon/verification lessons and performance rules are in Appendix C; exact palette and row anatomy in §17; working agreement in Appendix C. It also printed the public demo keystore password and told future agents to `git rm .ci/last-failure.log`; neither is carried forward as a secret or instruction. The password is already public but is deliberately omitted here, and the stale CI log is retained as a historical artifact rather than deleted; current GitHub Actions results are authoritative.

### A.9 `server/README.md` → §§4, 5, 7, 25–28, 34

Preserved run instructions, `0.0.0.0:8080`, `GET /health`, TLS proxy requirement, backup guidance, environment table, schema bootstrap warning (“`CREATE IF NOT EXISTS` is not a migration system”), WebSocket personal-room rule, process-local Hub limitation, private media default, and “no E2EE” statement.

### A.10 Backend repository documentation (all 11 files read; none deleted)

All 11 project-owned Markdown files in `cybereagle472/nchat-friends` were read end-to-end on 2026-11-09 and checked against the source. Their combined content digest for this audit was `dcb1b44a3ea0c2e6a8bce2945f5e0764a373f45ef30dc5a5dcc3f1f9c2fef3a6`; the nine main-repository source files’ combined digest was `b41168d674e049fc2d546df89913975131d91b5bc8162d6bb237de9c5385a742`. These files are outside the authorized `NChat-kotlin` deletion scope and remain intact. The separate MIT `LICENSE` is not Markdown and was also left untouched.

| Source | Still-relevant content preserved here | Current verification / caveat |
|---|---|---|
| `.github/ISSUE_TEMPLATE/bug_report.md` | bug report fields: steps, expected behavior, screenshots, device/OS/browser or APK version, context | Preserved in §29 reporting guidance |
| `.github/ISSUE_TEMPLATE/feature_request.md` | feature request fields: problem, proposed solution, alternatives, context | Preserved as reporting/planning guidance |
| `.github/PULL_REQUEST_TEMPLATE.md` | summary/type/test checklist, screenshots, typecheck/build/lint/backend-typecheck/manual gates | §28; **`npm run lint` gate is currently broken** because ESLint is not installed |
| `APP_DESCRIPTION.md` | user-facing product intent: 1:1/groups, text/photo/video/voice/docs, reactions/reply/forward/edit/delete, calls, 24 h status, friends, notifications, privacy, clean UI; project developed by Muhammad Nasir | Marketing claims of “disappearing messages”, “secure”, and always-available notifications are **not current source evidence**; see §§13, 15, 19, 21 |
| `CONTRIBUTING.md` | owner/author attribution; fork from `main`; setup with `.env.example`, `npm install`, `npm run dev`; service-layer rule; strict TypeScript; report device/browser; respectful conduct | Frontend service layer is verified; README badge claiming React 19 is stale |
| `README.md` | MIT license and owner attribution; high-level architecture; Cloudflare Workers/D1/R2/DO; setup guides; Capacitor Android wrapper; feature categories; development commands | React 19/Radix/shadcn, disappearing messages, real push, “production-grade”, and “fully self-hosted” claims are stale or unverified. Current package uses React 18.3.1 and no Radix runtime; source has no disappearing-message or E2EE implementation |
| `CLAUDE.md` | comprehensive project history: generated prototype origins, Supabase→Cloudflare migration, auth/session cache-first rebuild, messaging/voice/status/group/call/privacy/profile/performance work, 48 unused shadcn/Radix primitives removed, packages 306→173; offline-first IndexedDB queue, one WS per active room, service singletons, `types.ts`, Tailwind without component library; `VITE_API_URL`/VAPID/GIPHY and Worker secrets; Workers Builds settings; build-commit stamp; `-webkit-backdrop-filter` before standard; glass entrance animations on wrappers; Retry reruns historical commit; median.co wrapper is not this repo’s APK; `npm run lint` broken; `apk.yml` never run; live visual verification limits | React 19 and “all features live end-to-end” claims are stale; `gh run list --workflow=apk.yml` returned `[]`; Workers Builds currently fail at `616a9e6`. Preserved in §§2, 4, 5, 19, 24, 27, 28, 32 |
| `TESTING_CHECKLIST.md` | login/session sanity, groups, privacy, two-device voice/video calls, profile-preview journeys; expected-vs-actual and account/privacy-setting failure report | Fully preserved in §29; checklist execution evidence is absent |
| `worker/README.md` | Supabase→D1/Auth/Realtime/R2/Worker mapping; CLI setup, secrets, deploy, local dev, health/signup/`/users/me` smoke test; API surface; optional TURN/VAPID secrets | Its “only auth/profile services migrated” list is contradicted by all 11 current `src/services/*.ts` modules and App imports; referenced `disappearingService`, `encryptionService`, and `pushNotificationService` do not exist in current source. Preserved in §§4, 27 |
| `worker/SETUP_GUIDE.md` | prerequisites; install/login; D1/R2 creation; migration 0001/0002 scripts; secret generation; first deploy; `PUBLIC_API_URL` and `VITE_API_URL`; curl smoke tests; local `wrangler dev`; custom domain; dashboard data locations; troubleshooting; command cheat sheet | Branch-specific `arena/940244a7-nchat-friends` instruction is historical; migration scripts omit 0003–0005; `wrangler.dev` port 8787. Preserved in §§25–27 |
| `worker/DASHBOARD_SETUP_GUIDE.md` | dashboard-only D1/R2 creation, Workers Builds Git integration, `JWT_SECRET`, `PUBLIC_API_URL`, SQL console, bindings verification, `/health`, frontend wiring, deployment notes | Branch-specific `arena/940244a7-nchat-friends` instruction, “21 tables total” (actual union 22), and “only 0001/0002” are stale. It contains a ready-made static JWT secret example; the value is intentionally **not reproduced** here and must be treated as exposed. Preserved in §§15, 25, 27 |

**Preserved hard-won backend rules:** two Workers Builds projects (frontend root `/`, build `npm run build`; backend root `worker`, empty build command; both deploy `npx wrangler deploy`; production branch `main`); `npx wrangler preview` is not a deploy command; a Retry rebuilds the historical commit; `__BUILD_COMMIT__` in Settings → About verifies deployment freshness; production Lightning CSS drops the standard `backdrop-filter` if it is written before `-webkit-backdrop-filter`; entrance animations belong on a wrapper, never on the element with `backdrop-filter`; do not reintroduce shadcn/Radix primitives; third-party median.co wrapper screenshots are not evidence about this repository’s APK.

---

# Appendix B — Condensed version history

Version codes are from historical `eagle-x.md`; current manifest is 1.0.74 / code 82.

| Version | Code | Principal change |
|---|---|---|
| 1.0.1 | 62 | glass surfaces, empty states, avatar gradients, day separators, message state icons |
| 1.0.2 | 66 | Discover, status ring/viewer, History; tab-switch regression introduced |
| 1.0.3 | 70 | groups list/create/info/chat, group call overlay |
| 1.0.4 | 72 | voice recorder gesture, waveform, playback |
| 1.0.5 | 75 | message actions, reply/forward/copy/delete/react |
| 1.0.6 | 78 | audio/video call overlay, PiP-style minimize, controls |
| 1.0.7 | 82 | profile screens, image cropper, settings sections |
| 1.0.8 | 84 | update checker, version manifest, FileProvider install |
| 1.0.9 | 88 | forward dialog, status viewers, header actions |
| 1.0.10 | 90 | group call wiring, profile repository, cropper polish |
| 1.0.11 | 94 | settings profile pane |
| 1.0.12 | 20 | auth screen, profile setup, local session, logout |
| 1.0.13 | 21 | named group typing, sender avatars, tab-switch performance fix |
| 1.0.14 | 22 | layout sweep, call-history rows, shared gutters |
| 1.0.15 | 23 | resume marker, jump-to-latest, media viewer, edit, toasts |
| 1.0.16 | 24 | privacy/notifications/blocked settings panes |
| 1.0.17 | 25 | composer state hoisted, chat long-press menu |
| 1.0.18 | 26 | OkHttp transport, refresh rotation, realtime socket, remote repositories |
| 1.0.19 | 27 | deployed backend, demo data removed, attachments/status/privacy/avatar/call records |
| 1.0.20 | 28 | Coil renders media/avatars |
| 1.0.21 | 29 | backend address moved to Gradle config |
| 1.0.22 | 30 | security/privacy pass: Keystore, throttling, upload cap, path traversal, streaming |
| 1.0.23 | 31 | photo compression/thumbnails, chat cache, private Rust media, server switcher removed |
| 1.0.24 | 32 | crash report screen and diagnostic logging |
| 1.0.25 | 33 | defensive SharedPreferences type reads fix launch crash |
| 1.0.26 | 34 | ApiLog, Sync check, visible reload errors, profile photos wired |
| 1.0.27 | 35 | full Discover data, server user search, sent requests, account audit |
| 1.0.28 | 36 | status photo/viewers/mute and real group roster wired |
| 1.0.30 | 38 | real AAC voice messages and playback |
| 1.0.31 | 39 | non-destructive device-local blocking |
| 1.0.32 | 40 | synchronous token save fix, session-lost UI, settled-empty diagnosis |
| 1.0.33 | 41 | recover conversations from message history |
| 1.0.34 | 42 | account isolation for chat state |
| 1.0.35 | 43 | local/server history merge, optimistic reconciliation, retry/failure states |
| 1.0.36 | 44 | persistent outbox and group read events |
| 1.0.37 | 45 | UUID `client_message_id` fixes duplicate-send bug |
| 1.0.38 | 46 | friend graph retries/error UI, web media URL fallback, real video playback |
| 1.0.39 | 47 | resilient socket, reconnect resync, typing expiry, fallback poll, connection pill |
| 1.0.40 | 48 | real call signaling, status view recording, recording events, correct group typing sender |
| 1.0.41 | 49 | WebRTC media, offer/answer/ICE, TURN, real video surfaces, timeouts |
| 1.0.42 | 50 | friend graph cache and chat-list reconciliation |
| 1.0.43 | 51 | durable per-account message cache, NetworkMonitor, offline behavior |
| 1.0.44 | 52 | continuously mirrored chat/group lists and trust guards |
| 1.0.45 | 53 | real voice waveform, offline clip queue, correct `voice` type |
| 1.0.46 | 54 | kind-aware previews, replies, tombstone deletes, reactions/forwarding fixes |
| 1.0.47 | 55 | authenticated media playback, audio magic-number checks, duplicate icon fix |
| 1.0.48 | 56 | upload progress, one-owner gesture/seek behavior |
| 1.0.49 | 57 | swipe reply, double-tap ❤️, voice playback speed |
| 1.0.50 | 58 | in-chat search, multi-select, starred, drafts, photo pager |
| 1.0.51 | 59 | system notification channels, inline reply, deep link, POST_NOTIFICATIONS |
| 1.0.52 | 60 | text formatting, swipe archive/mark-read, archive sheet, filters, pinned message, message info |
| 1.0.53 | 61 | camera capture and location sharing |
| 1.0.54 | 62 | @mentions and private reply |
| 1.0.55 | 63 | storage manager and per-chat media/links/docs |
| 1.0.56/57 | 64/65 | group voice mesh, perfect negotiation, worker-aligned group-call events |
| 1.0.58 | 66 | FCM-less background foreground service |
| 1.0.59 | 67 | self-view black fix, screen-on call fix, disconnect timer fix |
| 1.0.60 | 68 | notification photos, event alerts, filter row, header truncation, update sheet, battery prompt |
| 1.0.61 | 69 | chat list two-line grid, group sender preview |
| 1.0.62 | 70 | notification deep links to exact message/call, unread badge race fix |
| 1.0.63 | 71 | background call ring, Decline receiver, missed-call ownership |
| 1.0.64 | 72 | full-screen update page, mention-aware notifications |
| 1.0.65 | 73 | chat wallpapers, group sender name once per run |
| 1.0.66 | 74 | Groups/Status list grid and empty states |
| 1.0.67 | 75 | emoji picker (~300 emoji, six groups) |
| 1.0.68 | 76 | complete status text/photo/video composer and viewer |
| 1.0.69 | — | release marker commit `d1be4f5d16`; subsequent QA/profile/onboarding work |
| 1.0.70–1.0.73 | — | release/QA iterations around profile setup, onboarding/chat relationships, APK signing, update flow; see §32 commits |
| 1.0.74 | 82 | system DownloadManager fallback for in-app APK update timeouts; current `version.json` |
| 1.0.75 | 83 | in-app update repair: signer lookup on Android 9/10/13, a look for a release when the app returns to the foreground, the install-permission step continues by itself, clearer failures. **On the Phase 1 branch only — not merged, not released** (2026-11-10) |

---

# Appendix C — Hard-won rules and working agreement

**Compose / Material 3:** `LinearProgressIndicator` requires the lambda `progress = { … }` overload; no `DropdownMenu(containerColor=…)`; no color parameters on `Slider`; avoid `animateDpAsState` on nullable `Dp`; `withTransform` is `DrawScope`-only; split chained `clip`/translucent `background`/`border`/`shadow` modifiers to avoid white stripes; never unmount the node that received a touch before the gesture completes.

**Icon names:** use `AutoAwesome` not `Sparkles`, `Email` not `AtSign`, `HowToReg` not `UserCheck`, `PersonRemove` not `UserMinus`, `Groups` not `Users`, `History` not `Schedule`. Known-good icons include `OpenWith`, `RotateRight`, `ZoomIn`, `ZoomOut`, `Close`, `Check`, `ChatBubble`, `Visibility`, `VisibilityOff`, `Info`, `CameraAlt`, `Logout`, `Mic`, `Call`, `Videocam`, `MoreVert`, `ArrowBack`.

**Kotlin:** do not name a private enum/state holder the same as a composable; after changing a composable signature grep every call site; unused imports are not errors but cleanup scripts must preserve `getValue`/`setValue` and `as` aliases; regex patch scripts must be checked for duplicated blocks and brace balance.

**Verification:** no reliable local Gradle build in this sandbox; a green GitHub Actions run is the source of truth. Tree-sitter can catch syntax/braces but not unresolved imports. `.ci/last-failure.log` is stale on green runs; CI bot `[skip ci]` pushes can require `git pull --rebase`.

**Rust CI (added 2026-11-10):** `backend.yml` runs only when `server/**` or the workflow changes. Its format and clippy steps are `continue-on-error`, so their step conclusion is always `success` — read the check-run annotations instead. On a red run the workflow commits `server/.ci/last-failure.log` to the branch with `[skip ci]`: `git pull --rebase` before the next push, read the log there, and delete it in the change that fixes the build. `cargo test` stops at the first failing test target, so a red run says nothing about the targets after it. A green run leaves no log that can be read from this sandbox.

**Test-first for authorization (added 2026-11-10):** push the test alone and let CI show it failing, then push the fix. A test that has never been seen to fail proves little, and here it is also the only way to learn that the code compiles. Read the client before trusting a fix: the first signal-relay fix protected the keys the server writes, while the Android client reads the same fact under other spellings (§19.3 #13).

**Toolchains and supply chain (added 2026-11-10, after an incident):** never download or run a compiler, formatter or package from an unofficial mirror to make up for a missing local toolchain — not even inside a home-made sandbox. On 2026-11-10 an agent that could not reach the official Rust hosts fetched a Rust toolchain from a third-party GitHub mirror whose origin could not be verified, and ran it on a throwaway project. The isolation it had built was not sound: the restricted account it used could have become `root`, and the environment held a GitHub token. An audit found no sign of misuse; everything was removed; nothing from that toolchain reached the repository; the owner was told and advised to reconnect GitHub so that the token is replaced. The rule: if the official source is unreachable, the tool is unavailable. Write the tests, check syntax with tree-sitter, and let CI compile (ADR 013).

**Updater (added 2026-11-10):** ask `getPackageArchiveInfo` with `GET_SIGNATURES | GET_SIGNING_CERTIFICATES` on Android 9+ — the new flag alone returns no signer on Android 9, 10 and early 13 (`signerLookupFlags`; do not “simplify” it). An update is carried out by the installed version, so a repair to the updater only helps from the following release. A notice on the update page is either a step or an error: never print a step in red. The update manifest goes live minutes before its APK.

**Platform behaviour without a device (added 2026-11-10):** read the platform's own source at the exact release tags (`aosp-mirror/platform_frameworks_base` through the GitHub contents API with `Accept: application/vnd.github.raw`), cite the tags, and say plainly that it is source reading. Vendor builds can differ.

**Tests that prove a limit (added 2026-11-10):** a test for “this expensive thing is refused” must assert the cheap decision first, so that a broken guard fails the test instead of running the expensive thing in CI. Do not make such a test fail on purpose.

**Rate-limit keys (added 2026-11-10):** nothing a client can write belongs in the only key of a limit. `X-Forwarded-For` is client-written unless a trusted proxy replaces it.

**Performance/layout:** static backgrounds; tab switching is a composition swap; no per-row entrance animations or shadows; nav affordances ≤150 ms; stable keys and hoisted `LazyListState`; derived lists in `remember(data,key)`; one weighted flexible child per row; single-line tokens/ellipsis; fixed leading/trailing columns; ask what happens at 1.3× font scale on a 360 dp screen.

**Row anatomy:** three lines max; fixed 44–56 dp avatar and 28–32 dp trailing actions; `heightIn(min=…)`; shared 12/16 dp gutters and 8 dp spacing; exactly one shrinking child; never let time/duration/pill wrap.

**Working agreement:** fidelity to the reference where intentional; one version bump per batch; bump `app/build.gradle` and `version.json` together; push, wait for green CI, verify release asset; keep `agent.md` current in the same change that discovers a hard rule; never claim compilation without green CI.

---

# Appendix D — Source-document removal ledger

After verifying that still-relevant content is preserved above, these nine project-owned Markdown files in `NChat-kotlin` are removed by this Phase 0 change. They are recoverable from Git history. No dependency/vendor documentation, generated artifacts, third-party package docs, legal files, source code, configuration, database files, or images are deleted. Files outside this repository, including all `nchat-friends` Markdown files, are untouched.

| Removed file | Consolidated into |
|---|---|
| `ARCHITECTURE.md` | §§4, 5, 16, 30, 31; Appendix A.1 |
| `PARITY.md` | §§18, 20–22, 32; Appendix A.2 |
| `SECURITY.md` | §§9, 12, 15, 19, 26, 27, 34; Appendix A.3 |
| `UPDATE_SETUP.md` | §§15, 27, 34; Appendix A.4 |
| `docs/AUDIT-1.0.39.md` | §§10, 11, 20, 24, 28, 29; Appendix A.5 |
| `docs/BACKEND-WIRING.md` | §§8–14, 20, 24; Appendix A.6 |
| `docs/WHATSAPP-PARITY.md` | §§17, 18, 20–22, 32; Appendix A.7 |
| `eagle-x.md` | most sections; Appendices A.8, B, C |
| `server/README.md` | §§4, 5, 7, 25–28, 34; Appendix A.9 |

Legal/security/compliance-relevant content from `SECURITY.md`, `UPDATE_SETUP.md`, and backend setup guides is preserved above; the MIT `LICENSE` in `nchat-friends` is outside the authorized repository and remains intact.

---

# Appendix E — Chronological change and handover log

### 2026-11-09 — Phase 0 discovery and permanent memory

- **Task:** Inspect latest NChat repositories, map architecture/features/security, create `agent.md`, consolidate Markdown, produce competitor comparison and roadmap, establish verification baseline. No application code changes.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `d87b70302fbacff185e8f5c829fce03ee3ef7595`.
- **End commit:** `f45480678630810eff1a525469d83d1feca0021f` (substantive Phase 0 commit; the following commit is metadata-only and records this hash in `agent.md`).
- **Files modified:** `agent.md` added; nine project-owned `.md` files removed (Appendix D); transient `.github/scripts/__pycache__` removed. No source/config/database/API/deployment changes.
- **Database changes:** none.
- **API changes:** none.
- **Tests executed:** Python APK-verification unit tests; Worker TypeScript typecheck; web frontend typecheck/build; GitHub check-run/workflow queries; source/route/schema parsers.
- **Test results:** Python 7/7 OK; Worker typecheck exit 0; frontend typecheck/build exit 0; Android main CI success; Rust CI success on prior commit, not HEAD; Cloudflare Workers Builds failed at backend main head.
- **Known limitations:** no local Java/Gradle/cargo; no Cloudflare dashboard logs; no real devices/emulator/FCM/TURN/staging DB; no production deployment or destructive operations; no E2E/manual/load/security regression tests executed.
- **Remaining work:** Phase 1–11 roadmap in §33.
- **Next recommended task:** With explicit go-ahead, create a local/staging authorization regression suite and begin Phase 1 with Cloudflare private media + WebSocket room authorization. Do not proceed with production changes, secret rotation, or deployment without authorization.

### 2026-11-10 — Phase 0 baseline merged to main

- **Task:** Make the permanent `agent.md` handover document available on `main` as requested, without switching to or directly pushing to `main`.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `e2ca5c2685ae29d8e86a6d3011a0b7918262f768` (active session branch head after Phase 0).
- **End commit:** `a867fdd881740889ee153013323072c1ebb8ea79` (`main` merge commit for PR #87).
- **Files modified:** documentation-only `agent.md` metadata/evidence update on the session branch; the earlier nine Markdown removals are included in the merge. No source/config/database/API/deployment changes.
- **Database changes:** none.
- **API changes:** none.
- **Tests executed:** PR #87 Android APK workflow and Rust backend workflow checks; `git show origin/main:agent.md`; exact 36-section/one-status-per-feature verification retained from Phase 0.
- **Test results:** PR #87 Android `build` success (58 s); Rust `Build and test` success (1 m 3 s); PR `MERGED`; `agent.md` present on `main`; session branch still active and clean at its Phase 0 head.
- **Known limitations:** no local Java/Gradle/cargo; no Cloudflare dashboard logs; no real devices/emulator/FCM/TURN/staging DB; no production deployment or destructive operations; no E2E/manual/load/security regression tests executed.
- **Remaining work:** Phase 1–11 roadmap in §33.
- **Next recommended task:** Future agents should read `agent.md` from `main` (or the active session branch) completely, verify the latest commit, then proceed only with explicit authorization for Phase 1 security work.

### 2026-11-10 — Phase 1 (critical security), Rust backend half

- **Task:** Phase 1 of §33 on the Rust backend, after the owner's go-ahead: authorization regression tests first, shown failing in CI; then the fixes; then green CI; then this handover. Worker-side fixes, Android changes and workflow changes were out of scope.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (`main` after PR #88).
- **End commit:** `a1d23985852abfe66e47f0004cb01c387625560f` on `arena/56647832-nchat-kotlin` — the last commit that touches `server/`; the commits after it change only `agent.md`. Not merged; no pull request opened.
- **Files modified:** `server/tests/authz.rs` (new, 45 tests); `server/src/privacy.rs`; `server/src/routes/{calls,friends,group_calls,groups,media,mod,status}.rs`; `agent.md`. The workflow committed `server/.ci/last-failure.log` twice (red runs); both copies were removed again.
- **Database changes:** none. `server/schema.sql` untouched; no migration.
- **API changes:** no route added, removed or renamed. Behaviour: the refusals listed in §8 (Phase 1 bullet) and §19.3, and `email` no longer appears in group member lists or group objects. Decisions taken: a status is visible under the stricter of its own audience and the author's account setting, never across a block, and to its author only when the audience cannot be evaluated (§9 item 11); a refused status view answers 404, not 403; a signal for a call that has ended answers 200 and is not relayed; a member removed from a group may still decline or leave a call they are in, but neither sends nor receives its signalling. All of this is deliberate drift from the Worker (ADR 002 note).
- **Tests executed:** GitHub Actions only — the Rust backend workflow (`cargo build --all-targets`, `cargo test`) and the Android APK workflow, on every push. Locally only a tree-sitter syntax check, which is not compile evidence.
- **Test results:** red by design on the two tests-only commits (`22371ff`: 27 of 42 `authz` tests failed; `17c4c7f`: 4 of 45), green after the fixes (`ccd83fe` run `42791596474`, `a5cd751` run `42792659202`, `a1d2398` run `42794684951`). At the end commit 63 tokio integration tests and 16 unit tests are defined and `cargo test` exits 0. APK workflow green on every push.
- **Known limitations:** the repository's Rust code was never compiled or tested locally; green CI logs cannot be read from the sandbox, so the pass counts of green runs come from the source, not from a log; the format check fails (advisory) on `main` and here, and clippy's result cannot be observed; the Worker was read, not executed; no device or end-to-end test. One process failure of this turn is recorded in Appendix C (toolchains and supply chain).
- **Remaining work:** the Worker half of Phase 1 (status table in §33); items 6–8; finding #23 and the residual risks in §19.3; merging the branch.
- **Next recommended task:** port the checks to the Worker in `nchat-friends`, test-first, with `server/tests/authz.rs` as the specification; get the owner's decisions on other users' e-mail addresses in API responses and on §19.3 #23.

### 2026-11-10 — Phase 1, second turn: in-app updater repair (Android 1.0.75) and sign-in hardening (Rust)

- **Task:** the owner's message concerned an in-app update system and a merge to `main` (its wording was not kept). Answered first: the work is in `NChat-kotlin` only; Phase 1 is not complete; an updater has existed since 1.0.8 and was found defective, so it is repaired rather than built a second time. Then the owner's decisions: merge only when all of Phase 1 is finished; updates stay optional (§33; ADR 016, 017). Work: repair the updater, then continue Phase 1 on the Rust backend.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `e2709bd` on `arena/56647832-nchat-kotlin` (13 commits above `main`, `ec0f86c`).
- **End commit:** `2b30f1d9f0da08c54fa055efc1ce7c37d062fe0f` — the last commit that touches code; later commits change only `agent.md`. Not merged; no pull request — the owner tied both to the completion of Phase 1.
- **Files modified:** Android — `core/update/{AutoUpdater,ApkDownloader,UpdateSignerPolicy}.kt`, new `core/update/UpdateCheckPolicy.kt`, `feature/update/UpdateGate.kt`, `MainActivity.kt` (one call in `onResume`); tests `core/update/{ApkDownloaderTest,UpdateSignerPolicyTest}.kt` and new `UpdateCheckPolicyTest.kt`; `app/build.gradle`; `version.json`. Rust — `server/src/{password,lib,main}.rs`, `server/src/routes/auth.rs`, new `server/tests/auth_hardening.rs`. `agent.md`.
- **Database changes:** none. Password hashes are rewritten only if an operator raises `NCHAT_PBKDF2_ITERATIONS`, one account at a time, at sign-in.
- **API changes:** no route added, removed or renamed. `/auth/login` already answered 429 per claimed address; it now also answers 429 per account, whatever address the attempts claim to come from. New settings `NCHAT_AUTH_ACCOUNT_RATE` and `NCHAT_PBKDF2_ITERATIONS` (§26). Android: `versionCode` 83, `versionName` 1.0.75; nothing changed in how the app talks to the server.
- **Tests executed:** GitHub Actions only, both workflows on every push; `python3 -m unittest` for the release scripts, locally. Before pushing Rust, a tree-sitter syntax check (not compile evidence); nothing comparable for Kotlin.
- **Test results:** red by design on `6bc65dc` (Android, 1 of 58) and `6eee8c6` (Rust, 2 of 6 in `auth_hardening`); green on `5091dbb`, `8a506c8`, `6eee8c6` (Android) and on `2b30f1d` (both workflows). By source count: 67 Android JVM tests, 69 Rust integration tests, 20 Rust unit tests.
- **Known limitations:** nothing was installed or run on a phone or an emulator — the updater's defect was found by reading Android's source, and its repair is untested where it matters. The Compose changes in `UpdateGate.kt` have no test. 1.0.75 exists only on the branch: phones keep 1.0.74 until the merge, and the update to 1.0.75 is itself carried out by 1.0.74. The per-account sign-in limit can be used to keep someone from signing in on a new device for a few minutes. Green CI logs cannot be read from the sandbox; format and clippy results are not observable.
- **Remaining work:** §33, “What ‘all of Phase 1’ still needs”; the unfixed lists in §19.3 and §19.4.
- **Next recommended task:** the Worker half, in a session on `nchat-friends`, with `server/tests/authz.rs` and `server/tests/auth_hardening.rs` as the specification. If the owner reports another failed update, get the exact message and the Android version first.

---

# Appendix F — External public references

- OWASP Password Storage Cheat Sheet — PBKDF2-HMAC-SHA256 600,000 iterations: [1](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
- WhatsApp Safety and Security — E2EE, privacy controls, two-step verification: [1](https://faq.whatsapp.com/898107234497196).
- WhatsApp privacy settings, disappearing messages, groups/status controls: [1](https://faq.whatsapp.com/3307102709559968/?cms_platform=web), [2](https://faq.whatsapp.com/673193694148537), [5](https://faq.whatsapp.com/2779769622225319).
- WhatsApp multi-device E2EE engineering overview: [4](https://engineering.fb.com/2021/07/14/security/whatsapp-multi-device/).
- Signal safety numbers and disappearing messages: [2](https://support.signal.org/hc/en-us/articles/360007060632-What-is-a-safety-number-and-why-do-I-see-that-it-changed), [1](https://signal.org/blog/disappearing-messages/), [4](https://support.signal.org/hc/en-us/articles/360007320751-How-do-I-know-if-my-message-was-delivered-or-read).
- Telegram cloud vs secret chats, multi-device, 2-step verification: [2](https://telegram.org/faq), [1](https://core.telegram.org/tsi/e2ee-simple).
- Messenger default E2EE and secure storage: [1](https://about.fb.com/news/2024/03/end-to-end-encryption-on-messenger-explained/), [2](https://about.fb.com/news/2024/01/investing-in-privacy/).
- Discord DAVE E2EE A/V and role/channel permissions: [1](https://discord.com/blog/meet-dave-e2ee-for-audio-video), [3](https://docs.discord.com/developers/topics/voice-connections), [1](https://discord.com/community/permissions-on-discord-discord).
- WeChat friend discovery, Moments, group calls: [1](https://cs.help.wechat.com/hc/en-us/articles/11901073289999-Welcome-to-WeChat).

> End of `agent.md`. Future agents: append, do not erase. Correct stale entries with an explanation. Keep evidence honest.
