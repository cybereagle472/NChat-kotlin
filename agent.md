# agent.md — NChat permanent engineering memory

## 0. Direction — read this first (set by the owner on 2026-11-11)

This block outranks everything below it. Older text below may say that the Cloudflare backend is in `nchat-friends`, that the Worker is a “read-only reference”, that `nchat-friends` is “outside this work”, or that Phase 1 is “finished”. Where it does, this block is right and that text is history.

| Part | Where its code is | Do the app's users depend on it? |
|---|---|---|
| Android app | this repository, `app/` | Yes. Released as 1.0.75 |
| Cloudflare backend — Worker, D1 (database), R2 (files), Durable Object (live events) | this repository, `worker/`, since 2026-11-11 | Yes. It is the only backend the app talks to, and it runs on the owner's Cloudflare account |
| Rust backend | this repository, `server/` | **No.** It runs nowhere. It is a second implementation of the same API |

**Rules for every agent**

1. **Work only in this repository**, `cybereagle472/NChat-kotlin`. The owner has said so more than once. Do not propose work in `cybereagle472/nchat-friends`, do not send the owner there, and do not make anything depend on it. It holds the old web client and the old copy of the backend; the owner does not need it.
2. **“The backend” means `worker/`.** A change reaches the app's users only when it is in `worker/` *and* Cloudflare has deployed it. A change in `server/` reaches nobody.
3. **Put no effort into `server/` (Rust)** unless the owner asks for exactly that. On 2026-11-10 three turns of Phase 1 went into it and protected no user. Its tests are still worth reading: they describe, case by case, how `worker/` should answer.
4. **Say which part a piece of work touches, in plain words, before starting and when reporting.** The owner owns the product and is not a backend engineer. Say “the Cloudflare backend, the one your app uses”. Say first whether the work reaches users. Never call a phase finished unless it is finished in `worker/` and deployed.
5. **Next task: Phase 1 (critical security) in `worker/`.** Nothing of it exists there yet. The task is written out in §33, “Next precise task (2026-11-11)”. Two things come first, in this order: read the result of the first Cloudflare build from this repository and write it down (rule 6), and ask the owner whether `JWT_SECRET` has been replaced (rule 9). Both are in §33, “First steps for the next session (2026-11-11, evening)”.
6. **A merge to `main` that changes a file under `worker/` is a deployment to every user.** Since the evening of 2026-11-11 Cloudflare builds the backend from this repository: the owner connected the Worker's build to `cybereagle472/NChat-kotlin` (branch `main`, root directory `worker`, deploy command `npx wrangler deploy`, watch path `worker/*`; §27, “Cloudflare connected to this repository”). Tell the owner so before merging anything that touches `worker/`. The result of a build is the check `Workers Builds: nchat-friends` on the commit. No agent has access to the Cloudflare account: an agent sees that check, never the build log. **Whether builds from this repository succeed was not known when this was written** — the first one starts with the merge of pull request #91. Find out before relying on it (§33, “First steps for the next session”), and write the answer here. Until a green check has been seen, “merged” does not mean “live”. *Until that evening this rule said: deploying does not work; Cloudflare is connected to `nchat-friends`, and every build since 2026-11-03 05:09 UTC has failed.*
7. **Do not change these in `worker/wrangler.toml`** without the owner's explicit instruction: `name = "nchat-friends"` (the live Worker's name and with it its address — another name creates a second, empty backend), the D1 `database_name` and `database_id`, the R2 `bucket_name`, the Durable Object binding and its `[[migrations]]` tag, and `PUBLIC_API_URL`. **The same holds in the Cloudflare dashboard, and the owner has to be told in so many words:** on 2026-11-11 the owner renamed the Worker there to `nchat-kotlin`. The address changed with the name and the old address stopped answering, so the installed app had nothing to reach, until the name was set back (§27, “The rename of 2026-11-11”). The name is part of the address that is built into every installed app and stored in front of every media file. It says nothing about which repository the code is in.
8. **Run nothing against the live account** — no `wrangler deploy`, `wrangler d1 execute --remote`, `wrangler secret put`, `wrangler r2 …` — and never ask the owner for Cloudflare passwords or tokens. A change to the live database is applied by the owner: give the exact command and say what it does.
9. **Treat the sign-in secret (`JWT_SECRET`) as public until the owner says it has been replaced.** The old public setup guide printed a ready-made value. Asked on 2026-11-11, the owner was not sure where the live value came from (“I think it was copied from claude code or maybe from cloudflare … I'm not sure”). If it is the printed one, anyone can act as any user. The owner has the steps to replace it and had not said that it was done when this was written. **Ask first thing, and record the answer here.** Replacing it signs every user out, and the backend has no way to change or reset a password, so someone who has forgotten theirs is locked out; §27, “`JWT_SECRET`, state on 2026-11-11”, says how to let such a user back in and which shortcut not to build. The other open questions are in §33. *Until that evening this rule said: ask the owner the open questions in §33 before writing code; the first, about this secret, cannot wait.*
10. **Keep this block true.** When Cloudflare is connected to this repository, when a build succeeds, when Phase 1 lands in `worker/`, change the rules above in the same commit.

> **Document status:** Phase 0 baseline, plus the Rust-backend half of Phase 1 (critical security, §33), added 2026-11-10 on a session branch that is not merged. This is the single source of truth for project state. A second Phase 1 turn the same day, on the same branch, repaired the in-app updater (Android 1.0.75) and hardened sign-in on the Rust backend (§19.4, Appendix E). A third Phase 1 turn the same day, on the same branch, finished the Rust part of §33 item 7, hid other users' e-mail addresses and repaired finding 23 (§19.5, Appendix E), and opened pull request #89 to bring the whole branch to `main`. **2026-11-11:** the owner set the direction in §0 — one repository — and the code of the Cloudflare backend was moved into it (`worker/`). Pull request #89 was merged that day and Android 1.0.75 is released. **2026-11-11, evening:** the owner connected Cloudflare's build to this repository, and `worker/README.md` was added as the first change under `worker/` since then, so that Cloudflare has something to build (pull request #91). Whether that build succeeded is not recorded in this file (§0 rule 6).
> **Last verified:** 2026-11-10 (UTC; Phase 0 baseline merged to main). Phase 1 Rust work: verified by CI the same day on its session branch (§36). Second Phase 1 turn: CI green for both workflows on `2b30f1d` (§36); nothing was run on a device. Third Phase 1 turn: both workflows green on `bef2644`, and both checks of pull request #89 green (§36); nothing was run on a device, and the Rust server was not run outside CI. 2026-11-11: pull request #89 merged, release `nchat-v1.0.75` published, both `main` runs green (§36). The moved `worker/` type-checks and bundles in its new place — run locally (§36); it has no CI yet. 2026-11-11, evening: the live backend answered at its address just before pull request #91 (§36), and `worker/` type-checked and bundled again; the result of the first Cloudflare build from this repository was not known when this was written.
> **Verified by:** Arena.ai Agent Mode.
> **Repository:** `cybereagle472/NChat-kotlin`.
> **Branch:** `arena/d8051bf9-nchat-kotlin` (active; Phase 0 was pushed here and merged to `main` through PR #87; the branch remains active and is not deleted). Phase 1 (Rust): `arena/56647832-nchat-kotlin` — active and pushed; not merged, and no pull request had been opened when this was written. *Third turn:* pull request #89 (`arena/56647832-nchat-kotlin` → `main`) was open with green checks when this was written; merging it is the step that follows this file's commit. *2026-11-11:* pull request #89 was merged; that branch is finished. The move of the Cloudflare backend was made on `arena/6151c100-nchat-kotlin`. By the owner's instruction it goes to `main` as well, through pull request #90, which was open when this was written and is merged after this file's commit (`gh pr view 90 --json state,mergedAt,mergeCommit`). *2026-11-11, evening:* `arena/7cfa350c-nchat-kotlin`, from `main` at `420fa1b`; pull request #91, open when this was written and merged after this file's commit (`gh pr view 91 --json state,mergedAt,mergeCommit`).
> **Start commit:** `d87b70302fbacff185e8f5c829fce03ee3ef7595`. Phase 1 (Rust): `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (`main` after PR #88). Move of the Cloudflare backend (2026-11-11): `45d793a8219f17f7a2e14bbb9f8a1d7bcc749632` (`main` after PR #89). First deployment from this repository (2026-11-11, evening): `420fa1bb69ed4b6401a75daae0c1b4e5e55777c5` (`main` after PR #90).
> **End commit:** `f45480678630810eff1a525469d83d1feca0021f` (substantive Phase 0 commit; the handover commit that records this hash is a metadata-only follow-up). Phase 1 (Rust): `a1d23985852abfe66e47f0004cb01c387625560f` — the last commit that touches `server/`; the commits after it on that branch change only `agent.md`. Second Phase 1 turn: `2b30f1d9f0da08c54fa055efc1ce7c37d062fe0f` — the last commit of that turn that touches code; later commits change only `agent.md`. Third Phase 1 turn: `bef2644eaaabf6cfe337bae8e8481c9289689e80` — the last commit of that turn before its `agent.md` commit; it only removes a CI log, and the last commit that changes code is `e0f3b5afbcb6a657c2c094848d586d813693ce57`. Move of the Cloudflare backend (2026-11-11): `89f689da6ddc883bb15332d50f328cdb615091dd` — the commit that adds `worker/`; the commit after it changes only `agent.md`. First deployment from this repository: `cc2bb619a748c38f6fa9b98623629c5cedec19c9` — `worker/README.md`, in two commits (`7bee103d33fe` adds it, `cc2bb619a748` corrects four sentences in it); the commit after them changes only `agent.md`.
> **Main merge commit:** `a867fdd881740889ee153013323072c1ebb8ea79` (PR #87, merged 2026-11-10 08:05:44 UTC; `agent.md` is now the only project-owned Markdown file on `main`). `main` has since moved to `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` (PR #88, 2026-11-10 08:09:29 UTC, `agent.md` only). The Phase 1 branch has no merge commit. *Third turn:* the Phase 1 branch goes to `main` through pull request #89. This file was committed before that merge, so the merge commit cannot be named here: `gh pr view 89 --json state,mergedAt,mergeCommit` gives it, and if the state is not `MERGED` the merge did not happen. **Recorded 2026-11-11:** pull request #89 was merged on 2026-11-11 at 13:29:08 UTC as `45d793a8219f17f7a2e14bbb9f8a1d7bcc749632`, and the `main` run that followed published release `nchat-v1.0.75` at 13:33:51 UTC. Pull request #90, which carries the move of the Cloudflare backend, is merged after this file is committed, so its merge commit is not named here: `gh pr view 90 --json state,mergedAt,mergeCommit` gives it, and if the state is not `MERGED` the merge did not happen. **Recorded 2026-11-11, evening:** pull request #90 was merged on 2026-11-11 at 14:20:06 UTC as `420fa1bb69ed4b6401a75daae0c1b4e5e55777c5`. Pull request #91 is merged after this file is committed, so its merge commit is not named here either: `gh pr view 91 --json state,mergedAt,mergeCommit`.
> **Scope of this turn:** Phase 0 only — repository discovery, architecture/security audit, documentation consolidation, competitor comparison, implementation roadmap, and verification baseline. No application code, database, API, configuration, secret, or deployment was changed. **Scope of the Phase 1 turn (2026-11-10):** the Rust backend only — `server/src` and `server/tests`. No Android, database-schema, route-table, configuration, workflow, secret, or deployment change. The Cloudflare Worker repository `nchat-friends` was read, not changed: the gaps fixed here are still open there, except where §19.3 says the Worker differs. **Scope of the second Phase 1 turn (2026-11-10):** the Android updater (`app/…/core/update`, `feature/update/UpdateGate.kt`, one call in `MainActivity.onResume`), a version bump to 1.0.75 / 83 with `version.json`, and sign-in on the Rust backend (`server/src/{password,lib,main}.rs`, `routes/auth.rs`, `tests/auth_hardening.rs`). No database schema, route, workflow, secret or deployment change; nothing merged, nothing released. **Scope of the third Phase 1 turn (2026-11-10):** the Rust backend only — 16 files under `server/src` and the new `server/tests/phase1_remainder.rs`. No Android, database-schema, route-table, workflow or secret change; five new settings and a changed meaning for `NCHAT_ADMIN_EMAIL` (§26). No deployment. Merging pull request #89 publishes Android 1.0.75 as a release — the APK workflow does that on every push to `main` — and that version is the second turn's updater repair, nothing from this turn. The Cloudflare Worker was not changed and nothing in this turn was checked against it: by the owner's decision `nchat-friends` is outside this work (§33). **Scope of the turn of 2026-11-11:** the code of the Cloudflare backend was copied into this repository as `worker/` (31 files, not one byte changed), `.gitignore` got three entries, and this file was updated. No code was changed, nothing was deployed, no Cloudflare setting was touched, and `nchat-friends` was only read. **Scope of the evening turn of 2026-11-11:** one new file, `worker/README.md`, and this file. An agent changed no code, schema, workflow or secret. On Cloudflare the owner changed the build connection and, for a short time, the Worker's name (§27). Merging pull request #91 makes Cloudflare build and deploy `worker/` as it already was on `main`.
> **Consolidation:** This file supersedes and consolidates the nine project-owned Markdown files previously in this repository. Their still-relevant content is preserved below; stale claims are explicitly labelled. Full verbatim history remains recoverable from Git history. No dependency/vendor/legal files were removed; the MIT `LICENSE` in the separate backend repository is untouched. *2026-11-11:* a copy of that licence now sits in `worker/LICENSE`, next to the code it covers.

**Status vocabulary (exactly one primary status per feature):** `NOT_STARTED`, `DISCOVERED`, `IN_PROGRESS`, `PARTIALLY_IMPLEMENTED`, `IMPLEMENTED_UNVERIFIED`, `TESTING`, `BLOCKED`, `COMPLETED`, `DEPRECATED`. `COMPLETED` is used only where this audit has explicit test or CI evidence, never merely because a screen, route, or table exists.

---

## 1. Project identity and objectives

NChat is a modern messaging product with three visible codebases and two backend runtimes: *(2026-11-11: read §0 first. The code of the Cloudflare backend is now in this repository, and the owner works only here.)*

1. A native Android client in Kotlin/Jetpack Compose (`com.nchat.app`, currently version `1.0.74`, `versionCode 82`). *Update 2026-11-10:* `1.0.75` / `versionCode 83` on the Phase 1 branch; `main` and the published release are still `1.0.74` / `82`.
2. A React/TypeScript web client and a Cloudflare Workers/Hono backend in `cybereagle472/nchat-friends`. *Update 2026-11-11:* the backend's code was moved into this repository (`worker/`). `nchat-friends` still holds the web client and the old copy of the backend; the owner does not use that repository.
3. A wire-compatible self-hosted Rust/Axum/SQLite backend in this repository under `server/`. *Update 2026-11-11:* it is deployed nowhere and the app does not use it; no further work on it unless the owner asks (§0).

The primary objective is to evolve NChat into a secure, reliable, coherent messaging platform with functionality comparable to leading communication applications, without replacing the existing project, discarding working functionality, or losing user data. The Android app is the principal product surface; the Cloudflare Worker is the deployed reference backend; the Rust backend is an alternative runtime that preserves the same HTTP/JSON and real-time contracts. The web client in the backend repository is a separate client, not a third backend.

**Non-negotiable rules:** preserve package identity, architecture, API contracts, and history; use evidence before implementation; never introduce fake functionality; never remove auth checks; never perform destructive database operations, production deployments, secret rotation, or force pushes without explicit authorization; never call HTTPS/TLS alone “end-to-end encryption”; never claim completion from a visible screen, HTTP 200, or compilation alone.

---

## 2. Repository inventory

| Repository | Role | Default branch | Verified head | Branches | License / identity |
|---|---|---|---|---|---|
| `cybereagle472/NChat-kotlin` | Native Android client + Rust self-hosted backend, CI, release manifest, this memory file | `main` | `d87b70302fbacff185e8f5c829fce03ee3ef7595` | 79 branches at verification; `main` is authoritative; historical `arena/*` work branches merge into `main`; current fixed session branch is `arena/d8051bf9-nchat-kotlin` | Package `com.nchat.app`; no root LICENSE file in this repository. *2026-11-11:* this repository now also holds the Cloudflare backend, in `worker/`, with its MIT licence in `worker/LICENSE` |
| `cybereagle472/nchat-friends` | React/TypeScript web client, Capacitor wrapper, Cloudflare Worker, D1 migrations, documentation | `main` | `616a9e69407b072de29f640f35ea05c6f48b9768` | 11 branches; `main` is authoritative; historical `arena/*` branches are session work branches | MIT; web app `appId` in Capacitor config is `com.nchat.friends`. *2026-11-11:* no longer the home of the backend and not to be worked in (§0) |

A GitHub repository search for `NChat user:cybereagle472` returned only the two repositories above. No separate React/TypeScript frontend repository was found. Other repositories under the account (`React-webRTC-Calling-`, `Nstar-Ludo`, etc.) are unrelated to NChat and were not modified.

**Authorized modification scope for this session:** only `cybereagle472/NChat-kotlin`, branch `arena/d8051bf9-nchat-kotlin`. The backend repository was cloned read-only into `/tmp/nchat-friends-audit` for inspection; its files were not changed or deleted. *2026-11-11:* by the owner's instruction this now holds for every session: only `NChat-kotlin` is worked in (§0).

---

## 3. Latest verified commit for every repository

| Repository | Commit | Author date (UTC) | Subject | Verification |
|---|---|---|---|---|
| `NChat-kotlin` `main` | `a867fdd881740889ee153013323072c1ebb8ea79` | 2026-11-10 08:05:44 | `Merge pull request #87 ... Add Phase 0 agent.md to main` | `gh pr view 87`, `git ls-remote`, `git show origin/main:agent.md` |
| `NChat-kotlin` working branch | `e2ca5c2685ae29d8e86a6d3011a0b7918262f768` | 2026-11-09 15:14:49 / 15:16 UTC | Phase 0 baseline pushed to the active session branch, then merged to `main` via PR #87; branch retained | `git log`, `git ls-remote --heads origin arena/d8051bf9-nchat-kotlin`, `gh pr view 87` |
| `nchat-friends` `main` | `616a9e69407b072de29f640f35ea05c6f48b9768` | 2026-11-03 18:35:35 | `Merge pull request #11 ... Record verification that the username-search feature is fully merged` | Fresh shallow clone, `git log`, GitHub API |
| `NChat-kotlin` `main`, re-checked 2026-11-10 | `ec0f86cd2899eac3fa3ad5885f3b8442a6e946cc` | 2026-11-10 08:09:29 | `Merge pull request #88 from cybereagle472/arena/d8051bf9-nchat-kotlin` (`agent.md` only) | `git ls-remote origin main`, `gh pr view 88` |
| `NChat-kotlin` Phase 1 branch `arena/56647832-nchat-kotlin` (not merged) | `a1d23985852abfe66e47f0004cb01c387625560f` (last commit touching `server/`) | 2026-11-10 09:54:54 | `ci: drop the backend failure log now that the signal fix is in`; 12 commits on top of `ec0f86c` up to here, 2 of them failure-log commits made by the workflow | `git log ec0f86c..`, `git push`, Rust backend run `42794684951` and APK run `42794684870`, both green |
| `NChat-kotlin` `main`, checked 2026-11-11 | `45d793a8219f17f7a2e14bbb9f8a1d7bcc749632` | 2026-11-11 13:29:07 | `Merge pull request #89 from cybereagle472/arena/56647832-nchat-kotlin` — all of the Phase 1 branch, Android 1.0.75 | `gh pr view 89`, `git ls-remote origin main` |
| `nchat-friends` `main`, checked 2026-11-11 | `616a9e69407b072de29f640f35ea05c6f48b9768` | 2026-11-03 18:35:35 | unchanged since the audit; `worker/` was copied from this commit | `git ls-remote`, full clone |
| `NChat-kotlin` branch `arena/6151c100-nchat-kotlin` | `89f689da6ddc883bb15332d50f328cdb615091dd` | 2026-11-11 14:08:37 | `worker: move the Cloudflare backend into this repository` | `git log`, `git push` |
| `NChat-kotlin` `main`, checked 2026-11-11 (evening) | `420fa1bb69ed4b6401a75daae0c1b4e5e55777c5` | 2026-11-11 14:20:05 | `Merge pull request #90 from cybereagle472/arena/6151c100-nchat-kotlin` — `worker/` and the direction of §0 | `git log`; GitHub check-runs at 16:54 UTC: Actions `build` success, no Cloudflare check |
| `NChat-kotlin` branch `arena/7cfa350c-nchat-kotlin` | `7bee103d33fe4e7ef796731f45bdf0e748455c43` | 2026-11-11 17:29:59 | `worker: describe how the Cloudflare backend is deployed from this repository` | `git log`, `git push`, pull request #91; Actions `build` success |
| the same branch, one commit later | `cc2bb619a748c38f6fa9b98623629c5cedec19c9` | 2026-11-11 17:43:59 | `worker: README says only what was seen` | `git log`; pushed together with the commit that changes this file |

**Deployment/CI heads:** the Android APK workflow succeeded on `d87b703` (run `42584666249`). Both Cloudflare Workers Builds check-runs failed on backend `616a9e6` (build IDs in the Cloudflare dashboard are referenced by GitHub check-run output; detailed logs are outside this sandbox’s allowed network). The Rust backend workflow last succeeded on 2026-11-08 at `b6e5721f43e6a8945ab63197bfc03a2f1a3faaac` (run `42491305703`); it did not run on `d87b703` because that commit did not touch `server/**`. PR #87’s Android `build` and Rust `Build and test` checks both passed before the `main` merge; the merged `main` head is `a867fdd881740889ee153013323072c1ebb8ea79`. **Update 2026-11-10 (Phase 1, Rust):** the Rust backend workflow also ran on `main` at `a867fdd` (run `42779970114`, green, the 18-test suite). On the Phase 1 branch it was red by design on the two tests-only commits (`22371ff`, run `42790409920`; `17c4c7f`, run `42794405563`) and green on `ccd83fe` (run `42791596474`, twice), `a5cd751` (run `42792659202`) and `a1d2398` (run `42794684951`). The Android APK workflow was green on every push of that branch (last: run `42794684870` on `a1d2398`). `nchat-friends` `main` was cloned afresh on 2026-11-10 and is still `616a9e69407b072de29f640f35ea05c6f48b9768`. None of the Phase 1 work is on `main`. *2026-11-11:* all of it is on `main` now (PR #89, `45d793a`). Cloudflare: GitHub shows both builds as failed on every `nchat-friends` commit from `78af299` (2026-11-03 05:09 UTC) to the head; the last commit with green builds is `7916475` (§27, §36).

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

Dependency ownership rule: Android and Rust manifests in `NChat-kotlin` are authoritative for this session; web/Worker manifests in `nchat-friends` are read-only reference. Changing a wire type requires coordinated Android DTO, Worker TypeScript, and Rust model changes. *Update 2026-11-11:* the Worker's manifest is now `worker/package.json` in this repository and is as authoritative as the Android one. The web client's manifests stay in `nchat-friends` and are no concern of this repository. A wire type now changes in the Android DTO and the Worker; the Rust model follows only if the owner wants `server/` kept in step (§0).

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
                                 # Rust backend: not deployed, not used by the app (see section 0)
# added 2026-11-11 - the Cloudflare backend, the one the app uses:
worker/wrangler.toml             # live Worker name, D1/R2/Durable Object bindings, PUBLIC_API_URL
worker/package.json, package-lock.json, tsconfig.json
worker/src/index.ts              # Hono app, CORS, route mounts, WebSocket upgrade
worker/src/routes/               # auth, users, friends, groups, messages, calls, groupCalls,
                                 # status, media, privacy, admin
worker/src/lib/                  # jwt, password, presence, privacy, realtime, id
worker/src/middleware/auth.ts    # requireAuth
worker/src/durable-objects/RealtimeRoom.ts
worker/src/types.ts
worker/migrations/               # 0001..0005, D1 SQL
worker/LICENSE                   # MIT, from the repository the code came from
worker/README.md                 # added 2026-11-11 (evening), written here: how the backend is
                                 # deployed from this repository, and what must never change
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

*2026-11-11:* the `worker/` lines of this listing are the files that were copied into `NChat-kotlin`. The three guides in its last line were not copied (§27 says why and where their content is). *2026-11-11, evening:* this repository has a `worker/README.md` again. It is a new, short file written here, not one of those guides.

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
- **Third Phase 1 turn, 2026-11-10 — behaviour only; still 72 route patterns.** New answers on the Rust backend. **429** when a budget is used up: sign-up and sign-in per address (§9 item 12); `POST /messages`, `POST /groups/:id/messages`, `POST /status` and `POST /media/upload` per account (send budget); `POST /friends/request`, `POST /groups`, `POST /groups/:id/members`, `POST /calls` and a `POST /group-calls` that starts a call per account (contact budget). **400** for text over the limits of `routes::limits` (table in §19.5), for more than 256 `memberIds`, and for a reaction that would be the 65th different one on a message. **404 “User not found”** instead of 500 when `receiver_id`, `user_id` or an entry of `memberIds` names nobody (`/friends/request`, `/friends/block`, `/calls`, `/groups`, `/groups/:id/members`). **403** when a message, status, profile or group is given a media address whose file the caller cannot open. **404** for a media key that is not exactly a key an upload returned. Every **500** has the body `{"error":"Internal server error"}`. `email` and `is_admin` appear only in what an account reads about itself: `/auth/signup`, `/auth/login`, `/auth/me`, `GET` and `PATCH /users/me`, and `GET /users/:id` for the caller's own id. Media answers carry `X-Content-Type-Options: nosniff`, and a stored file that is not a picture, sound or video is answered as `application/octet-stream` with `Content-Disposition: attachment`. No CORS headers unless `NCHAT_CORS_ORIGINS` is set. `/admin/stats` follows `users.is_admin`. Reasons and tests: §19.5.

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
12. **Limits on credentials (Rust only; third Phase 1 turn, 2026-11-10):** three budgets stand in front of every password hash, asked in this order; a refusal (429) does not count against the later ones. (a) Per address *and* account, sign-up and sign-in: `NCHAT_AUTH_RATE` in `NCHAT_AUTH_WINDOW_SECS` (10 in 300 s). (b) Per account from any address, sign-in only: `NCHAT_AUTH_ACCOUNT_RATE` (30). (c) Per address across all accounts, sign-up and sign-in together: `NCHAT_AUTH_ADDRESS_RATE` (30). “Address” is the peer of the TCP connection (`ratelimit::client_address`). `X-Forwarded-For` is read only when `NCHAT_TRUSTED_PROXIES` is n > 0, and then only its n-th entry from the right — the one the outermost trusted proxy wrote; with fewer entries, or an entry that is not an address, the peer counts. IPv6 clients are counted per /64, IPv4-mapped addresses as IPv4. Sign-in for an address that has no account checks the password against a decoy hash made with the server's round count and answers the same 401. Sign-up refuses an e-mail over 254 characters, a password over 1,024 and a name over 100 before anything is counted or hashed. Sign-in does not refuse long input (an account made before the caps must still get in); an e-mail over 320 bytes goes into the limiter keys as its SHA-256.
13. **Budgets for signed-in accounts (Rust only; same turn):** `AppState::count_send` — `NCHAT_SEND_RATE`, 300 in 60 s per account, over `POST /messages`, `POST /groups/:id/messages`, `POST /status` and `POST /media/upload`. `AppState::count_contact` — `NCHAT_CONTACT_RATE`, 30 in 300 s per account, over `POST /friends/request`, `POST /groups`, `POST /groups/:id/members`, `POST /calls` and a `POST /group-calls` that starts a call; joining a call that is running is not counted. Over budget: 429. All budgets live in memory, per process.
14. **Admin (Rust only; same turn) — replaces the Rust half of item 8:** `/admin/stats` is open to an account whose `users.is_admin` is set and to nobody else. `NCHAT_ADMIN_EMAIL` is read once, at start-up (`AppState::apply_admin_email`): the account that has the address gets the role and every other account loses it; if no account has the address, nobody has the role and the log says so. Unset: the roles in the database are left as they are. Nothing grants the role while requests are being served.
15. **CORS (Rust only; same turn) — replaces the Rust half of item 9:** no CORS headers at all unless `NCHAT_CORS_ORIGINS` lists origins. A listed origin gets GET, POST, PATCH and DELETE with the request headers `Authorization`, `Content-Type` and `Range`; a `*` in the list allows every origin, as before.
16. **What one account learns about another (Rust only; same turn):** `email` and `is_admin` are in `UserRow::user_public` and `UserRow::own_profile` only, which serve the caller's own account (`/auth/signup`, `/auth/login`, `/auth/me`, `GET` and `PATCH /users/me`, `GET /users/:id` with the caller's own id). Every other profile is built by `profile` or `brief`, which have neither field.

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
8. **Rust media rules after the third Phase 1 turn (2026-11-10)** — this replaces the access part of item 5. *Reading* (`access::can_read`): the key must be exactly the `file_path` of a `media_attachments` row; the disk reads `a//b` and `a/./b` as `a/b`, the server does not. The uploader may always read. Anyone else may read if at least one of these is true: a 1:1 message they sent or received mentions the key; a message in a group they belong to mentions it; a status that has not expired and that they may see (§9 item 11) mentions it; it is somebody's profile picture or a group's picture. A signed link from `/media/link`, which is given only to someone who may read, works without a token until it expires. *Storing an address* (`media::ensure_attachable`): `media_url` and `thumbnail_url` of a message or group message, `content_url` and `thumbnail_url` of a status, and `avatar_url` of a profile or a group are accepted only if the caller may read every file of this server that the address names; otherwise 403. *Answering:* always `X-Content-Type-Options: nosniff`; `audio/*`, `video/*` and the picture types jpeg, png, gif, webp, bmp, avif, heic and heif keep their stored type; anything else — HTML and SVG included — is sent as `application/octet-stream` with `Content-Disposition: attachment` and `Content-Security-Policy: default-src 'none'; sandbox`. *Uploading:* counted against the send budget; the key keeps the file's extension only if it is 1–16 ASCII letters or digits; a file name or declared type over 512 characters is refused. With `NCHAT_MEDIA_PUBLIC=1` none of the read or store checks apply. Still true from item 7: no quota, no check of the bytes against the declared type, no cleanup.

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
| Worker TypeScript typecheck | COMPLETED | `worker/src/**` | `npm run typecheck --prefix worker` exit 0 in fresh clone **2026-11-11:** `worker/` is now in this repository; there `npm ci` and `npm run typecheck` exit 0 and `wrangler deploy --dry-run` bundles (169.88 KiB) — run locally | Add authorization tests **2026-11-11:** and a CI workflow for `worker/`; both are the first step of the next task (§33) | 2026-11-11 |
| Web frontend typecheck/build | COMPLETED | `nchat-friends/src/**` | `npm run type-check`, `npm run build` exit 0; 1649 modules | Visual/live-device QA | 2026-11-09 |
| APK signing verification unit tests | COMPLETED | `.github/scripts/test_verify_release_apk.py` | `python3 -m unittest ...`: 7 tests OK | None for current scope | 2026-11-09 |
| Repository/branch inventory | COMPLETED | Git/GitHub API | §2, §3, §36 | Refresh each session | 2026-11-09 |
| Competitor comparison + roadmap | COMPLETED | §18.2, §18.3, §33 | This document; public sources in Appendix F | Update as features land | 2026-11-09 |
| Rust backend integration tests at HEAD | COMPLETED | `server/tests/*.rs`, `.github/workflows/backend.yml` | 18 tokio tests defined; last green run `42491305703` at prior SHA; no run on `d87b703` **2026-11-10:** run `42779970114` green on `main` `a867fdd` (the 18 tokio tests). Phase 1 branch: 63 tokio integration tests (`api` 9, `authz` 45, `groups_status_calls` 4, `media` 5) + 16 unit tests, `cargo test` green on `a1d2398`, run `42794684951` | Run on HEAD; add authorization cases **2026-11-10:** both done on the Phase 1 branch (not merged). Add a refusal test with every new route. Green logs cannot be read from the sandbox: the counts come from the source and from the two red logs | 2026-11-10 |
| Cloudflare Workers deployment | IMPLEMENTED_UNVERIFIED | dashboard Workers Builds | two check-runs failed on `616a9e6`; no dashboard log access **2026-11-11:** failed on all 23 commits from `78af299` (2026-11-03 05:09 UTC) to `616a9e6`; last green `7916475`; Cloudflare is still connected to `nchat-friends` **2026-11-11, evening:** the status was `BLOCKED` until now. The owner connected the Worker's build to `NChat-kotlin` (settings in §27, as the owner reported them). Connecting started no build, and none from this repository had been seen when this was written | Dashboard/build-log access, fix and redeploy with authorization **2026-11-11:** the owner connects the Worker's build to `NChat-kotlin` (root `worker`) and, if it fails, copies the build log into the chat — steps in §27 **2026-11-11, evening:** read the check `Workers Builds: nchat-friends` on the merge commit of pull request #91 (§33, “First steps for the next session”); if it failed, the owner copies the log into the chat | 2026-11-11 |
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
| Private media authorization | PARTIALLY_IMPLEMENTED | `server/src/routes/media.rs`, `worker/src/routes/media.ts` | Rust tests pass in last CI; Cloudflare public read **2026-11-10:** (Rust, Phase 1 branch) status media follows the status audience, including blocks and audiences the server cannot evaluate (`b647f5a`, three tests in `authz.rs`) | Cloudflare private R2/Worker proxy + signed links + tests **2026-11-10:** Cloudflare work unchanged. Rust: media that is referenced from more than one kind of conversation is over-restricted (§19.3 #23, discovered, not fixed) **Third turn:** Rust — #23 fixed; a media address is stored only if its sender can open the file; exact keys only; types a browser could run are sent as downloads (`e0f3b5a`; §19.5 #23, #30–#33). Open in Rust: the file of a message deleted for everyone stays reachable (§19.5, “Not fixed”) | 2026-11-10 |
| Rate limiting / brute-force protection | PARTIALLY_IMPLEMENTED | `server/src/ratelimit.rs`, web client local limiter | Rust auth limiter source; no Worker limiter | Durable/edge rate limits, IP/device/account limits, tests **2026-11-10:** Rust — a sign-in budget per account (`dd4b497`, §19.4 #28); then, third turn, the real connection address, a credential budget per address, and send and contact budgets per account, all with tests (`b15a73c`, `e0f3b5a`; §19.5 #37–#39). Still in memory and per process. The Worker had no rate limiting when it was last read (§19.4 #28) | 2026-11-10 |
| Input validation / abuse prevention | PARTIALLY_IMPLEMENTED | route bodies, `MediaPreparer`, `validation.ts` | Source | Central schemas, MIME/size, SSRF/path traversal, fuzz tests **2026-11-10 (third turn):** Rust — text sizes, reaction counts, upload names, unknown ids and 500 answers without internal text, with tests (`b15a73c`, `e0f3b5a`; §19.5 #33–#36). Still open: a central schema, checking a file's bytes against its declared type, and the fields listed under §19.5 “Not fixed” | 2026-11-10 |
| Database migrations | PARTIALLY_IMPLEMENTED | five SQL files, `schema.sql` | §25 and package scripts | Version table/runner, 0003–0005 scripts, copy-based migration tests | 2026-11-09 |
| Backup/restore | NOT_STARTED | N/A | docs only recommend backup | SQLite-aware backup, media backup, restore drill | 2026-11-09 |
| Audit logs / monitoring / alerting | PARTIALLY_IMPLEMENTED | `ApiLog`, `CrashLog`, tracing | Source | Server audit events, structured metrics, alerting, retention | 2026-11-09 |
| E2EE | NOT_STARTED | N/A | no keys/sessions/ciphertext | Signal Protocol evaluation, device keys, group encryption, tests | 2026-11-09 |
| Disappearing / view-once media | NOT_STARTED | N/A | no TTL/view-once columns | Schema, enforcement, client expiry, media deletion | 2026-11-09 |
| Report user/message | NOT_STARTED | N/A | no route/table | Report flow, moderation, privacy-preserving evidence | 2026-11-09 |
| Admin/stats | IMPLEMENTED_UNVERIFIED | `/admin/stats` | Source; no Android surface **2026-11-10:** (Rust, Phase 1 branch) test `admin_stats_are_denied_to_ordinary_accounts` | Role-based auth instead of hardcoded email; tests **2026-11-10:** a Rust denial test exists; role-based authorization still open in both backends **Third turn:** Rust — the gate is the stored role `users.is_admin`, with three tests (`b15a73c`, §19.5 #43); the Worker was not changed | 2026-11-10 |
| In-app updater | IMPLEMENTED_UNVERIFIED | `core/update/*`, `feature/update/UpdateGate.kt`, `version.json`, workflow | Source + 7 Python signing tests + green Android CI. *2026-11-10:* repaired on the Phase 1 branch as 1.0.75 (§19.4 #24–#27); 22 JVM tests on the pure rules, one of them seen failing before the signer fix | Install/upgrade on a real device (never done in any recorded session), mirror/failure tests, the page behaviour added in 1.0.75 **Third turn:** 1.0.75 is what the merge of pull request #89 releases; first thing to check afterwards on a phone: 1.0.74 offers it and it installs | 2026-11-10 |
| UI/UX design system | IMPLEMENTED_UNVERIFIED | `core/designsystem/*`, feature screens | Source + historical design rules | Compose UI tests, accessibility, dark mode, tablet, low-end profiling | 2026-11-09 |
| Accessibility / dark mode / tablets | NOT_STARTED | N/A | no dedicated implementation/tests | Full audit and implementation | 2026-11-09 |
| Performance/load measurement | NOT_STARTED | N/A | no measured results in repo | Instrumented API/DB/WS/media/call/Android benchmarks | 2026-11-09 |
| End-to-end/manual scenario tests | NOT_STARTED | N/A | backend checklist exists but no execution evidence | Scenarios in §29 on real devices/staging | 2026-11-09 |
| Phase 1 critical security, both backends (§33) | IN_PROGRESS | `server/src/routes/*`, `server/src/privacy.rs`; Worker `worker/src/routes/*` | Rust half done and green in CI on the Phase 1 branch (run `42794684951`); Worker half not started | Worker items 1–5 of §33 in `nchat-friends`; items 6–8 in both; residual risks in §19.3; merge the branch **Third turn:** the Rust side is finished as the owner now defines Phase 1 (this repository only; §33) and goes to `main` with pull request #89. The Worker half is not started and is outside this work by the owner's decision, so this row stays IN_PROGRESS for “both backends” **2026-11-11:** the direction changed for good (§0): the Worker's code is in this repository now, and Phase 1 in `worker/` is the next task. Nothing of it exists there yet | 2026-11-11 |
| Rust authorization regression suite | COMPLETED | `server/tests/authz.rs` (45 tests) | Shown failing first (run `42790409920`: 27 of 42 failed; run `42794405563`: 4 of 45 failed), then green with the fixes (runs `42791596474`, `42792659202`, `42794684951`) | Not on `main` until the branch is merged; the Worker has no equivalent **Third turn:** pull request #89 carries it to `main` | 2026-11-10 |
| Rust tests for limits, CORS, the admin role, input sizes and media reuse | COMPLETED | `server/tests/phase1_remainder.rs` (25 tests) | Shown failing first (run `42854637982`: 22 of 24 failed; run `42902301878`: 23 of 25 failed), then green with the code (runs `42903011159`, `42903567155`) | The Worker has no equivalent; the server was never run outside CI | 2026-11-10 |
| Cloudflare backend code in this repository | COMPLETED | `worker/` (31 files) | 2026-11-11: the 30 code and configuration files have the same git blob ids as in `nchat-friends@616a9e6`; type-check and dry-run bundle pass locally (§36) **2026-11-11, evening:** both pass again before the first deployment from here (§36) | CI and tests for `worker/`; the Cloudflare build connection (owner, §27). No fix has been made in it **2026-11-11, evening:** the build connection is made (owner's report, §27); `worker/README.md` was added, so the folder has 32 files; CI and tests are still missing | 2026-11-11 |

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
| 23 | Rust media access (`routes/media.rs`, `access::can_read`) lets the first kind of conversation that mentions a file decide alone: if any 1:1 message references the key, only the parties of those 1:1 messages may read it, and group messages or statuses that reference the same key are not consulted (the group branch does the same to statuses). Forwarding re-sends the same `media_url` (Android `forwardMessage`), so a photo forwarded from a 1:1 chat into a group should be unreadable for the other group members. It refuses too much rather than too little. | Medium (availability) | **DISCOVERED, not fixed** — source reading only, no test yet; predates this work. Proposed: let any branch grant access instead of returning early, with a forward-into-group test first. *Update, third turn:* **FIXED** `e0f3b5a` — the forward-into-group test failed in CI first (the group member got 404); see §19.5 | Not applicable — Worker media is public (#1) |

**Not fixed — residual risks seen in the Rust source during this work.** Source reading, no test; each needs a decision or belongs to a later item.

- **Other users' e-mail addresses.** Any signed-in user can read every other user's `email` (and `is_admin`) through `GET /users`, `/users/search`, `/users/:id` and the friends lists: the projections `user_public`, `profile` and `brief` in `server/src/models.rs` mirror the Worker's. Left alone on purpose — it is a product decision, and the Android Sync check (`core/diagnostics/SyncCheck.kt`) compares e-mails from `/users`. *Update, third turn:* the owner decided to hide them; done in Rust (§19.5 #44). Against such a server the Sync check's e-mail comparison finds nothing; its username comparison still works.
- **No friendship needed to ring or to add.** `POST /calls` and `POST /groups` (and adding members) accept any user id; only a block stops a call. *Update, third turn:* still so, but an id that names nobody is refused (404) and each of these requests counts against the contact budget (§19.5 #34, #37).
- **Rate limiting.** `ratelimit::client_key` trusts the first hop of `X-Forwarded-For`, which a client can set unless a trusted proxy overwrites it, and the limiter is process-local. *Update, third turn:* the address is now the connection's (§19.5 #38); the limiters are still process-local.
- **Token lifetime.** `AppState::current_user` validates the JWT only, so an access token keeps working until it expires (15 min) after logout, and after its account is gone.
- **Media by reference.** Access to a file follows any message that mentions its key, so someone who once saw a media URL can keep access by quoting it in a conversation of their own. *Update, third turn:* closed for everything stored from now on (§19.5 #30); references stored earlier are still honoured.
- **Calls after removal.** Removing a member from a group does not end their part in a running group call. The server stops relaying signalling to and from them, but peer-to-peer media that is already flowing is not the server's to cut.
- **Already tracked elsewhere:** `CorsLayer::permissive()`, no event sequence or cursor, PBKDF2 at 100,000 (§33 items 6–8). *Update, third turn:* CORS is closed by default (§19.5 #42).
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

- `ratelimit::client_key` still trusts the first `X-Forwarded-For` hop, so the per-address limiter is only as good as the proxy in front. The repair is to use the connection's peer address and count trusted proxies from the right (`ConnectInfo`, plus a setting for how many proxies there are). *Update, third turn:* done that way (§19.5 #38).
- **Sign-up is not limited per address:** its key contains the e-mail, so a new e-mail is a new budget. Each sign-up also costs one PBKDF2 run. *Update, third turn:* limited per address now (§19.5 #39).
- **Timing shows whether an e-mail has an account:** `login` answers at once for an unknown e-mail and after a PBKDF2 run for a known one. Hashing a dummy value would close that and would let anyone make the server hash at will, so it needs the address work first. *Update, third turn:* closed with a decoy hash, behind the per-address budget (§19.5 #40).
- PBKDF2 runs on the async worker threads. The limiter maps grow with every distinct key inside a window: the sweep in `RateLimiter::allow` starts above 10 000 keys, removes only expired entries, and then runs on every call. Both sign-in keys contain text the client chooses (the claimed address, the e-mail). *Update, third turn:* the address is no longer the client's choice and the e-mail part of a key is bounded (§19.5 #38, #41); the worker threads and the sweep are unchanged (§19.5, “Not fixed”).
- **Admin by unverified e-mail:** `/admin/stats` trusts `NCHAT_ADMIN_EMAIL`, and nothing verifies e-mail addresses. If that account does not exist yet, whoever registers the address is the admin. The route returns four counts. *Update, third turn:* closed — a stored role, assigned at start-up only (§19.5 #43).

*Reviewed, nothing to change (Rust, source reading)*

- **§33 item 6, first half:** `messages::send` writes the row, returns on error, and only then publishes. `db.rs` sets WAL and leaves `synchronous` at SQLite's default. Other event types were not checked one by one.
- **SSRF:** the only outbound request is the TURN credential call in `routes/calls.rs`, to a fixed Cloudflare host with a key id from the environment. No URL comes from a request.
- **CORS:** `CorsLayer::permissive()` with bearer tokens in a header and no cookies is not by itself a cross-site request forgery path. An allowlist is still part of item 7; it needs the list of web origins from the owner. *Update, third turn:* done without the list — closed by default, and the operator lists origins in `NCHAT_CORS_ORIGINS` (§19.5 #42).

### 19.5 Findings of the third Phase 1 turn: the rest of item 7, e-mail addresses, shared media (2026-11-10)

Numbering continues from §19.4. Everything here is the **Rust backend**, on branch `arena/56647832-nchat-kotlin` (pull request #89). The Worker was not looked at in this turn — by the owner's decision `nchat-friends` is outside this work (§33) — so the table has no Worker column; what §19.1–§19.4 say about the Worker still stands.

**Evidence.** The tests are in `server/tests/phase1_remainder.rs` (25). They were pushed before the code and failed in CI: run `42854637982` on `6335e5d` — 22 of 24 failed (log: `git show b349c0b:server/.ci/last-failure.log`); run `42902301878` on `899879f` — the same 22 and the reactions test that commit added (log: `git show 839542f:server/.ci/last-failure.log`). They pass in run `42903011159` on `e0f3b5a` and run `42903567155` on `bef2644`. Two of the 25 passed from the start; they are guards, not proof of a fix: `a_star_in_the_list_allows_every_origin` and `everyone_still_gets_their_own_email_address`. Six unit tests came with the code and were never seen failing: four in `ratelimit.rs`, two in `routes/media.rs`. “Seen” quotes or summarises a failure message of the second red run.

| # | Finding | Severity | State | Tests; what the red run showed | Limits |
|---|---|---|---|---|---|
| 23 | From §19.3: a file used in two kinds of place could be opened through the first kind only. | Medium (availability) | FIXED `e0f3b5a` — `access::can_read` asks every kind of place in turn (1:1 messages, groups, statuses, profile and group pictures); one yes is enough | `a_photo_forwarded_from_a_private_chat_opens_for_the_group`, `forwarding_a_group_photo_does_not_take_it_away_from_the_group`, `a_photo_used_in_a_status_and_in_a_chat_stays_open_to_both` — seen: 404 for a member of the group a photo had been forwarded to | A status's media stops opening for its viewers when the status expires, as before |
| 30 | **Mentioning a file was a way to open it** (a residual risk in §19.3). Access follows the messages, statuses and profiles that mention a file's key. Whoever knew a key — from a share link that has run out, or from a group they have left — could put it into a message of their own and be let in as that message's sender, or make it their profile picture, which every signed-in account may load. | Medium — the key is random and has to be known; a share link shows it | FIXED `e0f3b5a` — `media::ensure_attachable`: a media address is stored only if the caller can already open every file of this server that it names; otherwise 403. Applied wherever such an address is stored: messages and group messages (`media_url`, `thumbnail_url`), statuses (`content_url`, `thumbnail_url`), profiles and groups (`avatar_url`) | `knowing_a_media_address_is_not_enough_to_attach_it` — seen: 201 for a message carrying the address of a file its sender could not open | References stored before this are still honoured. Off when `NCHAT_MEDIA_PUBLIC=1`. Each stored address costs one `instr` search over all of `media_attachments` |
| 31 | **A second spelling of a key opened the file.** The disk reads `<user>//<file>` (and `<user>/./<file>`) as `<user>/<file>`; the access rule compares text. A message that mentioned the second spelling let its sender in — and the guard of #30 would not have noticed, because it looks for the key as the upload returned it. | Medium — same precondition as #30 | FIXED `e0f3b5a` — `can_read` first requires a `media_attachments` row whose `file_path` is exactly the requested key: no row, no file. `/media/link` goes through the same rule | `a_second_spelling_of_a_media_address_opens_nothing` — seen: 200 under the second spelling, for an account that had written that spelling into a message of its own | With `NCHAT_MEDIA_PUBLIC=1` any spelling the path check accepts is served, as everything is |
| 32 | **A stored file was sent back with the type its uploader declared.** `page.html` came back as `text/html` from the API's own origin, where a browser runs it; a share link opens without a token, so such a page could be handed to anyone. | Medium — the server serves no pages of its own, and tokens are not cookies | FIXED `e0f3b5a` — `served_type`: `audio/*`, `video/*` and the picture types jpeg, png, gif, webp, bmp, avif, heic, heif keep their type; everything else, SVG included, is sent as `application/octet-stream` with `Content-Disposition: attachment` and `Content-Security-Policy: default-src 'none'; sandbox`. Every media answer has `X-Content-Type-Options: nosniff` | `an_uploaded_file_cannot_run_as_a_web_page` — seen: `text/html`. Unit test `only_kinds_a_browser_does_not_run_keep_their_type` (never seen failing) | The declared type is still not checked against the bytes; `nosniff` is what keeps a browser from guessing. A document opened from a share link now downloads instead of showing |
| 33 | **An upload's name went into its key and its row unchecked.** The extension was whatever followed the last dot: `holiday.j?pg` was stored under a key ending in `.j?pg`, which no request can name again. The file name and the declared type were stored at any length. | Low | FIXED `e0f3b5a` — an extension is kept only if it is 1–16 ASCII letters or digits, otherwise the key has none; a name or type over 512 characters is refused (400) before anything is written | `an_upload_can_be_read_back_whatever_its_name` — seen: a key ending in `.j?pg`. Unit test `an_extension_is_kept_only_when_it_is_plain` (never seen failing) | — |
| 34 | **An id that names nobody was answered 500, in the database's words.** `POST /friends/request`, `/friends/block`, `/calls`, `/groups` and `/groups/:id/members` passed the id to an insert and reported its failure. Every other internal failure was reported the same way, table names included. | Low — it shows an outsider the schema, and a 500 invites a retry of something that cannot succeed | FIXED. `e0f3b5a`: those five answer 404 “User not found” (`routes::all_users_exist`); a new group and its members are written in one transaction; more than 256 `memberIds` is 400. `b15a73c`: every 500 is logged with its cause and answered `{"error":"Internal server error"}` (`error.rs`) | `an_id_that_names_nobody_is_refused_cleanly`, `a_server_error_does_not_describe_the_database` — seen: `database error: FOREIGN KEY constraint failed` and `database error: no such table: presence` in answers | Only status 500 is rewritten. Two requests that race for one e-mail (`signup`) or one username (`update_me`) still end in a 500 for the loser |
| 35 | **No text had a size limit below the 2 MiB body limit.** Names, bios, messages, captions, addresses — whatever was sent was stored, listed and pushed to every recipient. | Medium — one account could load every client that lists or receives it | FIXED — the limits and the check in `b15a73c` (`routes::limits`, `routes::ensure_lengths`; sign-up uses them there), their use in the other handlers in `e0f3b5a`. Values in the table below; 400 naming the field | `text_far_beyond_any_sensible_length_is_refused` — seen: 200 for “a display name of absurd length” | Fields that are still unbounded are in the list below. Sign-in does not refuse long input |
| 36 | **Reactions were unbounded.** A reaction is a key in a JSON map that is stored with the message and pushed whole on every change; the key could be any text and the map any size. | Medium | FIXED `e0f3b5a` — a reaction over 32 characters is 400; a 65th different reaction on one message is 400; joining a reaction that exists and taking one's own back stay allowed (`messages::reaction`, `groups::react_message`) | `reactions_on_one_message_are_bounded` — seen: 200 for a reaction of 5,000 characters | A reaction over 32 characters that was stored before the limit can no longer be taken back through the API |
| 37 | **A signed-in account could send, and reach other people, without limit.** | Medium | FIXED — the budgets in `b15a73c`, their use in `e0f3b5a`. *Send:* 300 in 60 s per account over messages, group messages, statuses and uploads (`NCHAT_SEND_RATE`). *Contact:* 30 in 300 s per account over friend requests, new groups, added members, calls and starting a group call (`NCHAT_CONTACT_RATE`). Then 429 | `sending_runs_out_per_account`, `contacting_people_runs_out_per_account` | In memory and per process: a restart empties them. An idempotent retry of a message counts again. They count requests, not bytes, so there is still no storage quota. The Android outbox retries a 429 (`SendRetryPolicy.kt`); what the app shows for a 429 on a friend request or a call was not looked at |
| 38 | **The address a limit counted against was written by the client** (§19.3, §19.4): the first entry of `X-Forwarded-For`. | High for sign-in | FIXED `b15a73c` — `ratelimit::client_address`: the peer of the connection; with `NCHAT_TRUSTED_PROXIES` = n, the n-th entry from the right of `X-Forwarded-For`, and the peer again if there are fewer entries or the entry is not an address. IPv6 per /64 | `a_claimed_address_does_not_buy_more_sign_ups`, `behind_a_trusted_proxy_the_address_the_proxy_saw_is_what_counts`. Four unit tests in `ratelimit.rs` (never seen failing) | Behind a proxy with the setting left at 0, all clients are counted as the proxy and share one budget; the server says so once in its log. Clients that really share an address (an office, a mobile carrier) share its budget. Tested in-process only, never behind a real proxy |
| 39 | **A new e-mail address was a new budget**, for sign-up and for sign-in across accounts: the only per-address key had the e-mail in it. | High | FIXED `b15a73c` — a third budget, per address alone, counted where a password hash is about to be made (`NCHAT_AUTH_ADDRESS_RATE`, 30 in the window by default) | `a_claimed_address_does_not_buy_more_sign_ups`, `sign_in_attempts_from_one_address_run_out_across_accounts` — seen: “40 of 40 sign-ups from one address were accepted”, “40 of 40 sign-in attempts from one address were checked” | — |
| 40 | **Sign-in answered at once for an unknown address and after a hash for a known one**, which lists who has an account (§19.4). | Medium | FIXED `b15a73c` — an unknown address is checked against a decoy hash made with the server's round count (`AppState::decoy_password_hash`) | `an_unknown_account_is_refused_no_faster_than_a_wrong_password` — seen in CI: “a wrong password took 785.788608ms, an unknown account took 310.655µs” (debug build, 100,000 rounds) | An account whose stored hash has another round count still takes a different time. Sign-up still tells whether an address is taken — its refusal can mean nothing else; only verified e-mail changes that (Phase 2) |
| 41 | **A limiter key held the whole e-mail text of a sign-in request** — megabytes, if the client wished — for a whole window. | Low | FIXED `b15a73c` — an e-mail over 320 bytes goes into the keys as its SHA-256 (`auth::account_key`); sign-up refuses an e-mail over 254 characters before any limiter is asked | The sign-up refusal is part of `text_far_beyond_any_sensible_length_is_refused`; the sign-in key has no test of its own | How many keys exist is still up to the client (list below) |
| 42 | **Every website was allowed to call the API** (`CorsLayer::permissive()`). | Low — tokens travel in a header, not a cookie, so this was not request forgery by itself | FIXED `b15a73c` — no CORS headers unless `NCHAT_CORS_ORIGINS` lists origins; a listed origin gets GET, POST, PATCH, DELETE with `Authorization`, `Content-Type`, `Range`; `*` gives the old behaviour | `pages_on_other_sites_get_no_permission_by_default`, `listed_web_origins_are_allowed_and_only_those` — seen: `Access-Control-Allow-Origin: *`. Guard: `a_star_in_the_list_allows_every_origin` | A web client on another origin stops working against this server until its origin is listed. Tested in-process only, never with a browser |
| 43 | **Signing up with the configured admin address made one the admin** (§19.4): `/admin/stats` compared the caller's e-mail with `NCHAT_ADMIN_EMAIL`, and nothing verifies e-mail addresses. | Medium — the route returns four counts | FIXED `b15a73c` — the gate is `users.is_admin`. `NCHAT_ADMIN_EMAIL` is applied once, at start-up: the account that has the address then gets the role and every other account loses it. Nothing is granted while requests are served | `the_admin_role_is_what_opens_admin_stats`, `registering_the_configured_admin_address_does_not_make_an_admin`, `the_configured_admin_address_grants_the_role_when_it_is_applied` — seen: 200 for “whoever registered the configured address” | The address is as unverified at a restart as it was before: whoever holds that account when the server starts is the admin. No route or screen manages the role |
| 44 | **Every signed-in account could read every other account's e-mail address and admin flag** (§19.3; the owner decided to hide them): `/users`, `/users/search`, `/users/:id`, the friends lists, the chat list, and the event pushed for a friend request. | Medium | FIXED `e0f3b5a` — `UserRow::profile` and `brief` have neither field; `user_public` and `own_profile` have both and serve only the caller's own account | `other_peoples_email_addresses_are_not_served` — seen: the pushed friend-request event carried `"email":"heidi@test.local"`. Guard: `everyone_still_gets_their_own_email_address` | The Android client needs no change (`UserDto.email` is nullable). Against this server its “Account duplication” check (`SyncCheck.kt`) can compare usernames only, and a group member with neither name nor username shows as “Member” |

**Text limits (#35, #36)** — `routes::limits`; over the limit is 400. They are far above anything the clients write (the Android client stops a bio at 150 characters) and far below what hurts.

| Text | Limit, in characters |
|---|---|
| Display name, group name | 100 |
| Username | 50 |
| Bio | 500 |
| Group description | 2,048 |
| Status text, caption | 4,096 |
| Message text (also when edited) | 65,536 |
| `media_url`, `content_url`, `avatar_url` | 2,048 |
| `thumbnail_url` | 65,536 — it is not always an address: a voice note keeps its waveform there and a client may keep a small inline preview there |
| Ids, type names, colours, durations, file names, an upload's name and declared type | 512 |
| One reaction | 32; at most 64 different reactions on one message |
| E-mail and password at sign-up | 254 and 1,024 |
| Search text | 100 — a longer one finds nobody |
| Ids in one `memberIds` list | 256 |

**Not fixed — seen during this turn.** Rust, source reading, no test unless stated.

*Media*

- **Deleting a message for everyone leaves its file reachable.** `messages::delete_for_everyone` and `groups::delete_message_for_everyone` replace `content` (with nothing; with a notice in a group) and set `is_deleted`; `media_url` and `thumbnail_url` stay in the row and in the message's JSON, and `can_read` does not look at `is_deleted`. The other side can still load the picture.
- **Profile and group pictures are open to every signed-in account that knows the key.** `profile_photo_visibility` hides the address in a profile, not the file; a group's picture can be loaded by accounts outside the group.
- **No index on `media_attachments.file_path`**, and the rules search stored addresses with `instr`, which no index helps: every media read scans `media_attachments` for the exact key, every stored media address scans it once more (§24 #24).
- Unchanged from §12 item 7: no storage quota, and a file's bytes are not checked against its declared type.

*Limits and budgets*

- **How many limiter keys exist is still up to the client.** A sign-in request with a new e-mail adds a key to two limiter maps before the per-address budget refuses it. A key stays in its map until the map holds more than 10,000 keys; from then on every call of `RateLimiter::allow` sweeps out the keys whose window has passed. A key is now a few hundred bytes at most (#41). Asking the per-address limiter first, without counting, would bound what one address can add.
- **PBKDF2 still runs on the async worker threads** (§19.4).
- **Still bounded only by the 2 MiB body limit:** `offer_sdp` of `POST /calls` (stored, pushed to the callee) and `answer_sdp` of `POST /calls/:id/answer` (stored, pushed to the caller); the bodies of `POST /calls/:id/signal` and `POST /group-calls/:id/signal` (relayed, not stored); `status` of `POST /calls/:id/end` — any text, stored and pushed to the other side; `muted_user_id` of `POST /status/mute` — any text and any number of rows (the column has no foreign key), against no budget.

*Accounts*

- **`PATCH /users/me` takes any text as a username** — trimmed, lower-cased, at most 50 characters. There is no character rule, and a username of spaces is stored as the empty string.
- **Uniqueness races end in a 500:** two sign-ups for one e-mail, or two accounts taking one username, at the same moment. The check and the write are separate statements.
- Unchanged from §19.3: an access token works until it expires (15 minutes) after logout; no friendship is needed to call somebody or to put them in a group.

*Reviewed, nothing to change (Rust, source reading)*

- Every handler that takes a request body was checked field by field, by listing what each one reads against the limits it applies. Apart from the fields in the list above, a field without a size rule is one of these: a value from a fixed set — `privacy::patch_settings` (three visibility values), `groups::set_role` (`admin`, `member`), `calls::create` and `group_calls::start` (`audio`, `video`); a boolean or a number; an id that is looked up and never stored (`messages::mark_read`, `typing`, `recording`, `friends::unblock`, `group_calls::start`); a refresh token, which is verified and not stored; or the e-mail and password of a sign-in, which are unbounded on purpose (#41). `media::upload` reads one multipart field, the file.
- **Cross-site requests:** the API has no cookies; a token travels in a header that a page has to set itself. With CORS closed, a page on another site can neither read an answer nor send a request that carries a token. It can still make a visitor's browser send a request without one — a sign-in or sign-up attempt, since a body is parsed whatever type it declares — without seeing the answer; that attempt counts against the visitor's own address.

---

## 20. Completed work

**Completed with objective evidence in this session:** Phase 0 repository discovery, latest-commit verification, source-backed architecture/security map, competitor comparison, roadmap, `agent.md`, Markdown consolidation, baseline checks listed in §36.

**Historically shipped implementation work (source exists; product-level completion is not claimed without E2E evidence):** remote repositories and JWT transport (1.0.18/1.0.19); media rendering/upload and status composer (1.0.19/1.0.20); build-time backend address (1.0.21); Keystore token storage, upload limits, path traversal fix (1.0.22); private Rust media, photo compression, chat cache, no server switcher (1.0.23); crash reporting (1.0.24); defensive preference reads (1.0.25); API log/Sync check (1.0.26); full user search and profile avatar (1.0.27); status/group-info routes (1.0.28); real voice audio (1.0.30); non-destructive device blocking (1.0.31); session-loss handling (1.0.32); history recovery (1.0.33); account isolation (1.0.34); optimistic send/retry (1.0.35); persistent outbox (1.0.36); UUID idempotency IDs (1.0.37); friend graph error states (1.0.38); realtime resync/typing expiry (1.0.39); real call signaling and status view recording (1.0.40); WebRTC media layer and TURN (1.0.41); durable friend graph (1.0.42); durable message cache and connectivity watch (1.0.43); continuously mirrored chat/group lists (1.0.44); real voice waveform and offline clip queue (1.0.45); previews/replies/deletes/reactions/forwarding (1.0.46); authenticated media playback (1.0.47); gesture ownership/upload progress (1.0.48); swipe reply/double-tap reaction/voice speed (1.0.49); in-chat search/multi-select/starred/drafts/photo pager (1.0.50); system notifications (1.0.51); text formatting/swipe archive/pinned message/message info (1.0.52); camera/location (1.0.53); mentions/private reply (1.0.54); storage manager/media index (1.0.55); group voice calls mesh (1.0.56/1.0.57); FCM-less background socket (1.0.58); call bug fixes (1.0.59); notification photos and event alerts (1.0.60); chat-list grid (1.0.61); notification deep links (1.0.62); background call ring/decline (1.0.63); full-screen update page/mention notifications (1.0.64); wallpapers/group sender runs (1.0.65); Groups/Status empty states (1.0.66); emoji picker (1.0.67); complete status composer/viewer (1.0.68); 1.0.69–1.0.74 release/onboarding/update-signing/system-downloader hardening (see §32).

**Completed with objective evidence on 2026-11-10 (Phase 1, Rust backend only; branch `arena/56647832-nchat-kotlin`, not merged):** an authorization regression suite for the Rust API (`server/tests/authz.rs`, 45 tests) and the fixes it called for — group membership checks and e-mail redaction (§33 item 3), the status audience (§33 item 4), call-signal integrity, and two false announcements (§19.3). Evidence: the suite failed in CI before the fixes (runs `42790409920`, `42794405563`) and `cargo test` is green after them (run `42794684951` on `a1d2398`); see §36. Not completed: everything on the Worker side of Phase 1, and items 6–8.

**Completed with objective evidence on 2026-11-10 (second Phase 1 turn; same branch, not merged):** on the Rust backend, a per-account sign-in budget and an upgrade path for password hashes, each with a test that failed in CI before the change and passes after it (§19.4 #28–#29). On Android, the signer lookup of the in-app updater, with a test that failed before the fix (§19.4 #24). The rest of the updater repair (§19.4 #25–#27) is written and builds in CI but is **not** claimed as completed: its page behaviour has no test, and nothing has run on a device.

**Completed with objective evidence on 2026-11-10 (third Phase 1 turn; same branch, pull request #89):** on the Rust backend, the rest of §33 item 7 — the connection's address for limits, a credential budget per address, send and contact budgets per account, equal sign-in timing, the CORS allowlist, the stored admin role, sizes for text and reactions, clean answers for unknown ids and for internal errors — and with it the hiding of other users' e-mail addresses and the media rules (§19.3 #23, attaching by reference, exact keys, served types). Each has tests in `server/tests/phase1_remainder.rs` that failed in CI before the code (runs `42854637982`, `42902301878`) and pass after it (run `42903011159`); §19.5 names the tests and the limits of each fix. Not completed and not claimed: anything on the Worker; the “Not fixed” list of §19.5; any run of the Rust server outside CI.

**Completed with objective evidence on 2026-11-11:** pull request #89 is merged (`45d793a`) and release `nchat-v1.0.75` is published with its APK (§36). The code of the Cloudflare backend is in this repository as `worker/`: the same bytes as in `nchat-friends@616a9e6` (equal git blob ids), and it type-checks and bundles from its new place. Not completed and not claimed: any fix in `worker/`; CI or tests for `worker/`; any deployment; the connection between Cloudflare and this repository.

**2026-11-11, evening — nothing new is claimed as completed.** Observed, and not more: the live backend answers at its address (§36); `worker/` type-checks and bundles as before; a new `worker/README.md` describes how the backend is deployed. Reported by the owner and not visible to an agent: Cloudflare's build is connected to this repository. Not known when this was written: whether a build from this repository succeeds — the first one starts with the merge of pull request #91 (§27).

---

## 21. Partially completed work

- Two wire-compatible backends with divergent security controls; parity is incomplete. *Update 2026-11-10:* the gap is wider on the Phase 1 branch — Rust now refuses the requests listed in §19.1 #4–#7 and §19.3, which the Worker still accepts. That is deliberate (fix first where tests can run); the Worker has to follow. *Update (third turn):* wider again — the third-turn bullet in §8 lists what Rust now answers differently.
- Account lifecycle: email/password, profile setup, refresh/logout exist; no phone/OTP, 2FA, recovery, device list, account deletion, or data export.
- Blocking: server block in Worker is destructive, Android block is device-local, Rust block is non-destructive; no single consistent policy.
- Status privacy: stored `privacy` field and Rust media audience check, but feed and viewer routes do not enforce it. *Update 2026-11-10:* enforced in Rust on the Phase 1 branch (feed, push, view, viewer list, media); still unenforced in the Worker; selected/excluded-contact audiences have no schema.
- Multi-device: same account’s sockets receive personal-room events, but no device identity, linked-device verification, app-state sync, or per-device revocation.
- Push: local notifications and a foreground socket work around lack of FCM; no real push infrastructure.
- Group calls: audio-only mesh; no group video, SFU, or capacity measurement.
- Search: user search and local in-chat/media search; no server-side message search, filters, or privacy-tested global search.
- Local state: many WhatsApp-like features are deliberately device-only (drafts, stars, pins, archive, wallpaper), so they do not follow the account across devices.
- UI/accessibility: strong phone-portrait design system, but no dark mode, tablet/foldable layout, or accessibility/UI automation evidence.
- Tests: unit and Rust integration tests exist; no Android UI tests, E2E journeys, migration tests against a copy, load tests, or security regression suite. *Update 2026-11-10:* a security (authorization) regression suite now exists for the Rust backend on the Phase 1 branch; there is none for the Worker or for Android. *Update (third turn):* the Rust suite also covers limits, CORS, the admin role, input sizes and media reuse (`phase1_remainder.rs`) — 94 integration tests in all.
- Deployment: Android and local web/worker builds are green; Cloudflare Workers Builds failed at backend main head.
- Phase 1 critical security (added 2026-11-10): the Rust half is done on a branch that is not merged; the Worker half is not started; items 6–8 of §33 are open in both. *Update (second turn):* item 8 and part of item 7 are now done in Rust; the rest stands. By the owner's decision the branch is merged only when all of Phase 1 is finished (§33). *Update (third turn):* the owner narrowed Phase 1 to this repository (§33). In that sense it is finished in Rust and goes to `main` with pull request #89; the Worker half is not started and is outside this work.
- In-app updater (added 2026-11-10): exists since 1.0.8; repaired as 1.0.75 on the Phase 1 branch; never proven on a device; not offered before sign-in; the system installer's result is not observed (§19.4). *Update (third turn):* 1.0.75 is what the merge of pull request #89 releases; still never proven on a device. *Update 2026-11-11:* released; still never proven on a device.
- Cloudflare backend in this repository (added 2026-11-11): the code is here (`worker/`). Cloudflare is not connected to this repository yet, its builds have failed since 2026-11-03 for a reason nobody has seen, `worker/` has no CI and no tests, and none of Phase 1 exists in it (§0, §27, §33). *Update 2026-11-11, evening:* connected by the owner (§27). The first build from here starts with the merge of pull request #91; its result is not recorded in this file. Still no CI, no tests and nothing of Phase 1 — and from now on every merge under `worker/` deploys.

---

## 22. Remaining work

Phase 1 critical security; Phase 2 account lifecycle; Phase 3 reliable messaging; Phase 4 contacts/groups; Phase 5 media/voice messages; Phase 6 push; Phase 7 calls; Phase 8 status/advanced messaging; Phase 9 E2EE; Phase 10 UI/UX/performance; Phase 11 production readiness. Detailed roadmap is §33. Cross-cutting remaining work: authorization tests, durable event recovery, migration runner, device model, real push, E2EE design, backup/restore, monitoring/alerting, load tests, accessibility, dark/tablet layouts, and end-to-end scenario evidence. *Update 2026-11-10:* of Phase 1, the Rust-side authorization work is done on branch `arena/56647832-nchat-kotlin` (not merged). Still remaining in Phase 1: every Worker-side item (1–5), durable events (6), rate limits, CORS and admin role (7), the PBKDF2 plan (8), and the residual risks and finding #23 in §19.3. *Update 2026-11-10 (third turn):* in this repository the Rust side of Phase 1 is done as the owner defined it (§33). What remains of Phase 1 is the Worker, and the “Not fixed” lists of §19.3, §19.4 and §19.5. *Update 2026-11-11:* Phase 1 on the Cloudflare backend is what remains of Phase 1, it is done in `worker/` in this repository, and it is the next task (§33). Sentences above that place it in another repository or outside this work are superseded (§0).

---

## 23. Blocked work

| Work | Exact limitation |
|---|---|
| Inspect/fix Cloudflare Workers Build failure details | Sandbox outbound network allows GitHub/npm/PyPI only; Cloudflare dashboard build logs are not accessible. GitHub check-runs expose only build IDs and failure conclusion. *2026-11-11:* still so. The owner can open a failed build in the Cloudflare dashboard and copy its log into the chat; that is the only way an agent gets to read it (§27) *2026-11-11, evening:* asked for the log of the last failed build, the owner saw no failed entry (“there is no failed deployment i see”): the Deployments list shows what went live, and by Cloudflare's documentation failed builds are in another list (Deployments → “View build history”). With the build connected to this repository, a failed build is a red check on the commit here, and its “Details” link opens the build for the owner |
| Local Android build/unit tests | No `java`, `gradle`, Android SDK, or local Gradle daemon in this sandbox. CI is the only compile/test evidence. |
| Local Rust `cargo test` | No `cargo`/`rustc` installed. Last CI success is on a prior commit, not HEAD. *Update 2026-11-10:* still no local toolchain, and none can be installed — the official Rust hosts are outside the sandbox's network allowlist, and mirrors must not be used (Appendix C). CI has since run on `main` at `a867fdd` (run `42779970114`) and on every `server/` push of the Phase 1 branch. |
| Real two-device call/media/push/notification tests | No physical devices, emulator, FCM credentials, TURN credentials, or live staging accounts in this session. |
| Production D1/SQLite migration or restore drill | No production database access; directive forbids destructive operations without explicit authorization. |
| Secret rotation / signing-key migration | Requires explicit authorization; key rotation breaks in-place updates for existing demo-signed installs. *2026-11-11, evening:* for `JWT_SECRET` the owner has been advised to replace the value and has the steps (§27). It is the owner's action in the dashboard; whether it was done is not known |
| Production deployment | Requires explicit authorization and Cloudflare dashboard access. *2026-11-11:* and first the owner's step of connecting the Worker's build to this repository (§27) *2026-11-11, evening:* the connection is made (owner's report). A merge to `main` that changes `worker/**` is now a deployment (§0 rule 6). The owner's “Go” covered pull request #91 and nothing after it |
| E2EE protocol work | Requires a separate security design and interoperability plan; cannot be inferred from current plaintext schema. |
| Worker half of Phase 1 (added 2026-11-10) | The Worker lives in `cybereagle472/nchat-friends`; a session is tied to one repository and branch, and the Phase 1 session was tied to `NChat-kotlin`. It needs a session on that repository. The Worker has no test suite and no test script in `worker/package.json`, so the first step there is a way to run tests. *Update, third turn:* by the owner's decision it is also outside the Phase 1 work of this repository (§33). *Update 2026-11-11:* **not blocked any more** — the Worker's code is in `worker/` in this repository and the owner wants the work done here (§0). What it still needs first is a way to run tests |
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
14. All 11 backend Markdown files are retained but several are stale (`CLAUDE.md`, `README.md`, `APP_DESCRIPTION.md`, `worker/README.md`, setup guides). They contain React 19/Radix, disappearing-message, push, production-grade, branch-specific `arena/940244a7-nchat-friends`, “21 tables”, and “only 0001/0002” claims; the dashboard guide also contains an exposed example JWT secret. See Appendix A.10. *2026-11-11:* for both reasons the three `worker/` guides were not copied into this repository with the code (§27).
15. No dark mode, tablet/foldable layout, accessibility audit, or localization beyond English resources.
16. (2026-11-10) `cargo fmt --all -- --check` fails on `main` and on the Phase 1 branch. The step is advisory (`continue-on-error`) and its diff cannot be read without a Rust toolchain or log access, so nobody has fixed it, and the Phase 1 code very likely adds to it (an inference: the annotation's log line moved from 1895 on `main` to 2225 on the branch). Run `cargo fmt` once where a toolchain exists, then consider making the step blocking.
17. (2026-11-10) A green Rust CI run leaves nothing an agent can read: only step conclusions. The test summary is visible only in the failure log that a red run commits. A workflow step that emits the `test result:` lines as a `::notice` would make green runs auditable through the annotations API. Not changed, because workflow edits were out of scope.
18. (2026-11-10) Android `CallRepository.handleGroupEvent` matches group-call events by fragments of their name. It drops a participant only for an event whose type contains `leave`, but both backends send `group-call:participant-left`, so that branch never runs for the event the servers actually send, and a leaver is not taken out of the roster by it (source reading; not tested on a device). It also reads the call id from `callId` before `call_id`, which is what made §19.3 #13 possible.
19. (2026-11-10) The user projections return every user's `email` to any signed-in caller in both backends (§19.3, residual risks). Needs a product decision before code. *Update (third turn):* decided and done in Rust — hidden (§19.5 #44). The Worker was not changed.
20. (2026-11-10) Rust media access lets the first kind of conversation that references a file decide alone (§19.3 #23). *Update (third turn):* fixed (§19.5 #23).
21. (2026-11-10) The updater hands the APK to the system with `startActivity` (`ACTION_VIEW`, then `ACTION_INSTALL_PACKAGE`) and never learns whether the install succeeded; `PackageInstaller` sessions would report it. The update page exists only for signed-in users, keeps its state in the composable (lost on process death) and has no test; `SettingsViewModel` repeats the download-and-install flow with the older behaviour.
22. (2026-11-10) `version.json` is read from raw `main`, so a version is announced before its APK exists. Publishing the manifest with the release, or reading the release API, would remove the window.
23. (2026-11-10) Rust sign-in: `ratelimit::client_key` trusts a client-written header, sign-up is limited per address-and-e-mail only, and PBKDF2 runs on async worker threads (§19.4). *Update (third turn):* the address and the sign-up limit are fixed (§19.5 #38, #39); PBKDF2 on the async worker threads is not.
24. (2026-11-10, third turn) `media_attachments.file_path` has no index and is not unique, and the Rust media rules search stored addresses with `instr`. Every media read scans the table for the exact key; every stored media address scans it once more. `CREATE INDEX IF NOT EXISTS idx_media_attachments_file_path ON media_attachments(file_path)` would cover the exact lookups. Left out because that turn changed no schema.
25. (2026-11-10, third turn) The Rust text limits are constants (`routes::limits`) applied handler by handler with `ensure_lengths`: a new handler has no limit until its author adds one. There is still no central input schema (#12).
26. (2026-11-10, third turn) The Rust budgets live in memory, per process (`ratelimit.rs`): a restart empties them, two server processes would each keep their own, and old keys are swept only above 10,000 keys, then on every call (§19.5, “Not fixed”).
27. (2026-11-10, third turn) The Rust admin role can be given only through `NCHAT_ADMIN_EMAIL` and a restart, or by editing the database. No route or screen manages it.
28. (2026-11-10, third turn) A Rust upload writes its file before its database row. If the insert fails, the file stays on disk: nobody can read it (no row, no access) and nothing removes it.
29. (2026-11-11) `worker/` has no CI and no tests. `.github/workflows/backend.yml` covers `server/**` only. First step of the next task (§33).
30. (2026-11-11) The APK workflow has no path filter: every push to `main` — a backend-only or `agent.md`-only one too — builds the app again and replaces the APK file of the current release. With the backend in this repository that will happen often. A `paths:` filter (`app/**`, the Gradle files, `version.json`, `nchat.properties`, `.github/scripts/**`, the keystore file, the workflow itself) would stop it; prove that a version bump still publishes before relying on it.
31. (2026-11-11) The Wrangler locked in `worker/package-lock.json` is 3.114.17 and reports itself out of date (4.x exists). Not known to break anything. Moving to 4.x is a major-version change: do it on purpose and by itself, never inside a security fix.
32. (2026-11-11) Nothing records which of `worker/migrations/0001`–`0005` the live D1 database has, and `worker/package.json` has scripts for the first two only (§25, §27).
33. (2026-11-11) With `server/` out of use (§0) the Rust code will fall behind `worker/` with every backend change. That is accepted. If the owner later wants `server/` deleted or revived, record it in §0.
34. (2026-11-11) `nchat.properties` says the backend's address is kept out of git. It is in git: in `worker/wrangler.toml` (`PUBLIC_API_URL`) and, since Phase 0, in §26 of this file. It was public in `nchat-friends` before and is inside every APK, so nothing new is disclosed; the comment is out of date.
35. (2026-11-11, evening) Every stored media address contains the backend's host name. `POST /media/upload` answers with `PUBLIC_API_URL` + `/media/file/<key>`; the app stores that full address and sends it back; the backend saves what it is given — `messages.media_url` and `thumbnail_url`, the same two in `group_messages`, `users.avatar_url`, `groups.avatar_url`, `statuses.content_url` and `thumbnail_url`. So the host can never change — not by renaming the Worker, not by moving to a custom domain — without rewriting those columns, and the phones' own caches hold the same addresses. Storing the key and building the address when answering would remove the tie. Found when a rename took the app offline (§27).
36. (2026-11-11, evening) Nobody can change or reset a password. `worker/src/routes/auth.ts` has `check-email`, `signup`, `login`, `refresh`, `logout` and `me`; the app has no screen for it. A forgotten password is a lost account, and it makes replacing `JWT_SECRET` costly, because that signs everyone out (§27). Phase 2 names recovery; “change password, old password required” may have to come sooner. Do not build a change that needs only a valid token while the secret may be public (§27).
37. (2026-11-11, evening) The running backend does not say which version it is: `/` and `/health` answer constants. An agent can see that it is up, and can see Cloudflare's check on a commit, but cannot tie the two together. A version in `/health` — the commit, put in at build time — would let an agent confirm a deployment by itself.
38. (2026-11-11, evening) Nothing stands between a merge and every user. Cloudflare deploys each merge to `main` that changes `worker/**`, and `worker/` has no tests and no CI (#29). Until the test workflow of §33 exists, the only gate is the agent's own `npm run typecheck` and dry-run bundle before merging, and the owner's test in the app after.

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

*2026-11-11:* `worker/migrations/0001`–`0005` are now in this repository. Nothing records which of them the live D1 database has. The backend that Cloudflare last built (`7916475`) already contains the code that needs 0003–0005, so they are probably applied; only the owner can look (Cloudflare dashboard → D1 → `nchat-db` → the tables `group_read_state`, `group_calls`, `group_call_participants` and the column `group_messages.duration`). A new migration is applied by the owner (§27).

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
| Rust server | `NCHAT_AUTH_RATE` / `NCHAT_AUTH_WINDOW_SECS` | 10 / 300 | process-local signup/login throttle *2026-11-10, third turn:* counted per connection address and account (see `NCHAT_TRUSTED_PROXIES`); the window is shared by the other two credential budgets |
| Rust server | `NCHAT_AUTH_ACCOUNT_RATE` | 3 × `NCHAT_AUTH_RATE` (30) | sign-in attempts per account in the same window, whatever address they claim to come from (added 2026-11-10, Phase 1 branch) |
| Rust server | `NCHAT_AUTH_ADDRESS_RATE` | 3 × `NCHAT_AUTH_RATE` (30) | password hashes one address may ask for in the window, sign-up and sign-in together, whatever accounts they name (added 2026-11-10, third Phase 1 turn) |
| Rust server | `NCHAT_TRUSTED_PROXIES` | 0 | how many reverse proxies stand in front. 0: limits count against the connection's own address and `X-Forwarded-For` is ignored. n: the n-th entry from the right of `X-Forwarded-For` is the client. It must be the real number — too low and every client is counted as the proxy, too high and a client can name its own address (added 2026-11-10, third Phase 1 turn; §27) |
| Rust server | `NCHAT_SEND_RATE` | 300 | messages, group messages, statuses and uploads one account may send in 60 s; then 429 (added 2026-11-10, third Phase 1 turn) |
| Rust server | `NCHAT_CONTACT_RATE` | 30 | friend requests, new groups, member additions, calls and group-call starts one account may make in 300 s; then 429 (added 2026-11-10, third Phase 1 turn) |
| Rust server | `NCHAT_CORS_ORIGINS` | empty = no CORS headers | comma-separated web origins, written as a browser sends them (`https://chat.example`); `*` allows every origin, which was the behaviour before (added 2026-11-10, third Phase 1 turn) |
| Rust server | `NCHAT_PBKDF2_ITERATIONS` | 100000; a lower value counts as 100000, a higher one as 10000000 | rounds for new and upgraded password hashes — read §27 before raising it (added 2026-11-10, Phase 1 branch) |
| Rust server | `NCHAT_MEDIA_PUBLIC` | 0 | `1` disables private media enforcement |
| Rust server | `NCHAT_MEDIA_TTL` | 600 s | signed media link lifetime |
| Rust server | `NCHAT_ADMIN_EMAIL` | unset = no admin | `/admin/stats` gate *2026-11-10, third turn:* no longer compared with the caller. Read once at start-up: the account that has this address gets the role `users.is_admin` and every other account loses it; if no account has it, nobody is admin and the log says so. Unset: roles already in the database stay as they are |
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

*2026-11-11, evening:* what the live Worker has, as the owner reported it from the dashboard: the secrets `JWT_SECRET`, `CF_TURN_KEY_ID` and `CF_TURN_KEY_API_TOKEN`, and the plain variable `PUBLIC_API_URL`. That list has no `VAPID_*` entry. A deploy from this repository sets `PUBLIC_API_URL` to the value in `worker/wrangler.toml` each time and leaves the secrets alone. The build settings are configuration too, and they exist only in the dashboard: §27 and `worker/README.md` list them as the owner reported them.

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

**Before running a build that contains Phase 1 (2026-11-10, third turn).** Written from the source and the tests; the server was not run outside CI.

1. **Behind a reverse proxy, set `NCHAT_TRUSTED_PROXIES`** to the number of proxies in front. Left at 0, every client is counted as the proxy: all of them share one credential budget (30 sign-ups and sign-ins in five minutes by default), and the log says once “a request carried X-Forwarded-For and it was ignored”. The proxy must add the address it saw to `X-Forwarded-For`, and nothing but the proxy may reach the server's port. Without a proxy leave it at 0.
2. **A web client on another origin needs its origin in `NCHAT_CORS_ORIGINS`.** Without it browsers refuse its requests. The Android app is not affected.
3. **`NCHAT_ADMIN_EMAIL` takes effect at start-up only.** Create the admin's account, make sure it is yours, then restart. The log says whether an account was found.
4. The send and contact budgets and the text limits need no setting (values: §9 item 13, §19.5). Nothing in the database changes when such a build first starts, except `users.is_admin` when `NCHAT_ADMIN_EMAIL` is set.

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

**This session performed no deployment.** Cloudflare deploy remains blocked pending dashboard log access and explicit authorization. *2026-11-11:* the backend commands above now run from `worker/` in this repository. What changed with the move, and what the owner has to do, is in the next subsection.

### Cloudflare backend in this repository (2026-11-11)

*Update, evening of 2026-11-11:* Cloudflare is connected to this repository now. The next subsection, “Cloudflare connected to this repository”, has the present state. The paragraphs below describe the day of the move, and their step list was written before anyone had looked at the dashboard.

**What is where.** Code: `worker/`. Running: on the owner's Cloudflare account — the Worker named `nchat-friends` (its address is `PUBLIC_API_URL` in `worker/wrangler.toml`; the Android build gets its address from the Actions variable `NCHAT_API_BASE`, which an agent cannot read — that it is this Worker's address is the owner's word, given on 2026-11-10 and again on 2026-11-11), the D1 database `nchat-db`, the R2 bucket `nchat-media`, and the Durable Object class `RealtimeRoom`. Secrets are on Cloudflare only: `JWT_SECRET` (required), `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `CF_TURN_KEY_ID`, `CF_TURN_KEY_API_TOKEN` (optional).

**What was moved, and what was not.** From `cybereagle472/nchat-friends` at `616a9e69407b072de29f640f35ea05c6f48b9768`: the 30 code and configuration files of `worker/`, byte for byte (their git blob ids are the same in both repositories; source tree `50f17653cc5af42769fa7c332c12de7aad451e04`), and the MIT `LICENSE`, as `worker/LICENSE`. Not moved: `worker/README.md`, `worker/SETUP_GUIDE.md` and `worker/DASHBOARD_SETUP_GUIDE.md` — they describe the other repository, are stale in places, and one of them prints a ready-made `JWT_SECRET` (§24 #14); what still holds is in this section and in Appendix A.10. Also not moved: the web client, the Capacitor wrapper and the root `wrangler.toml`, which belongs to the web client.

**The move changed nothing that runs.** Cloudflare builds from the Git repository it is connected to, and that is still `nchat-friends`. Two build projects are connected there (names from the GitHub checks): `nchat-friends`, the backend, and `nchat-friends-deploy`, the web client.

**No Cloudflare build has succeeded since 2026-11-03.** GitHub records the result of every Cloudflare build as a check on its commit. On `nchat-friends` `main` both projects were green up to `7916475` (2026-11-03 05:03 UTC) and failed on each of the 23 commits from `78af299` (05:09 UTC) to the head, `616a9e6`. Nobody who has worked on this file has seen why: the build logs are in the Cloudflare dashboard only. Two things are known and neither is known to be the cause. The old repository's notes say that the dashboard once had wrong commands — for the backend a build command `npm run build`, which `worker/package.json` does not have — and that this was corrected; the checks after that note still fail. And the locked Wrangler, 3.114.17, reports itself out of date.

**So the live backend is most likely the build of `7916475`,** unless the owner has deployed by hand since. Between `7916475` and `616a9e6`, `worker/` changed in one commit: `9011222` adds `GET /users/search` (60 lines in `routes/users.ts`). The Android app uses that route for the Find tab (`network/NChatApi.kt`). On a backend built from `7916475` the request falls through to `GET /users/:id` and is answered 404 “Not found” — read from the source at that commit, not observed on the live backend. The owner can see which it is: in the app, Settings ▸ Sync check, row “Search (Find)”.

**The owner's steps to make Cloudflare build the backend from this repository.** Written from the old repository's notes and from general knowledge of the dashboard, not from a live session; a label may read a little differently.

1. GitHub → Settings → Applications → Installed GitHub Apps → “Cloudflare Workers and Pages” → Configure → give it access to `NChat-kotlin`.
2. Cloudflare dashboard → Workers & Pages → the Worker `nchat-friends` → Settings → Build. Disconnect `nchat-friends`; connect `cybereagle472/NChat-kotlin`.
3. Set: production branch `main`; root directory `worker`; build command empty; deploy command `npx wrangler deploy`.
4. If “Build watch paths” is offered, include only `worker/*`. Without it every commit to `main`, an app-only one too, deploys the backend again.
5. A build starts with the next push to `main` that it watches. “Retry deployment” on an old entry builds that old commit from the old repository again; it does not test the new connection.
6. If the build fails, open it in the dashboard and copy the whole log into the chat. That log is the one thing no agent has seen.
7. After a green build: `<address>/health` answers `{"ok":true}`; then, in the app, sign in, send a message and a photo, and run Settings ▸ Sync check.

Leave the other project, `nchat-friends-deploy`, as it is: it builds the web client, which is not in this repository.

**What the first green build changes for users.** If the live backend is the build of `7916475`: one thing, username search in the Find tab starts to work. No database change comes with it.

**Going back.** A Worker keeps its earlier versions: Cloudflare dashboard → the Worker → Deployments → roll back to the one before. Connecting the build to `nchat-friends` again is steps 2–3 in reverse. Removing `worker/` from this repository changes nothing that runs, unless Cloudflare has been connected to it (§34 item 10).

**The secret that signs every sign-in (`JWT_SECRET`) — a question only the owner can answer.** The dashboard guide in `nchat-friends` offered a ready-made value for it (“use this ready-made random secret (or generate your own)”), and that guide is public. `worker/src/middleware/auth.ts` accepts any token signed with the secret and takes the user's id from the token; it looks nothing up. If the live secret is that published value, anyone can act as any user. The fix needs no code: Cloudflare dashboard → the Worker → Settings → Variables and Secrets → `JWT_SECRET` → replace it with a new, long random value that is written down nowhere public → save. Every user is then signed out once and signs in again; nothing else changes. Put to the owner on 2026-11-11; record the answer here. This file never repeats a secret value.

**Database.** The schema is five plain SQL files, `worker/migrations/0001`–`0005`. There is no migration runner and no table that says what has been applied; `package.json` has scripts for the first two (§25). A new migration is applied by the owner — from `worker/`: `npx wrangler d1 execute nchat-db --remote --file=./migrations/<file>.sql`, or the same SQL pasted into the D1 console — and the code has to work both before and after it.

**What an agent may run** (no Cloudflare account is needed and nothing is deployed), from `worker/`: `npm ci`; `npm run typecheck`; `npx wrangler deploy --dry-run --outdir <dir>`, which bundles and stops. All three were run on 2026-11-11 (§36). `npx wrangler dev` with a local database was not tried.

### Cloudflare connected to this repository (2026-11-11, evening)

**State.** On the evening of 2026-11-11 the owner connected the Worker's build to this repository, working in the Cloudflare dashboard on a phone, step by step in the chat. No agent has seen the dashboard: every setting below is the owner's report. What an agent saw is under “Seen from outside”.

| Setting (Worker `nchat-friends` → Settings → Builds) | As the owner reported it on 2026-11-11 |
|---|---|
| Git repository | `cybereagle472/NChat-kotlin` |
| Git branch (production) | `main` |
| Root directory | `worker` |
| Build command | empty |
| Deploy command | `npx wrangler deploy` |
| Build watch paths | Include `worker/*`; Exclude empty |
| Builds for other branches (“Enable Preview Builds”) | asked to be off; the owner reported the steps as done, and this one was not confirmed by itself. A push to the work branch that changed `worker/README.md` got no Cloudflare check (§36) |
| API token | not asked. A build token was selected while the build was connected to `nchat-friends`; whether the same one is selected now is not known |

Before that evening the build was connected to `nchat-friends`, and the dashboard showed `main` as its production branch. The other earlier values were not written down.

The Worker's secrets and variables, as the owner reported them: `JWT_SECRET`, `CF_TURN_KEY_ID`, `CF_TURN_KEY_API_TOKEN` (secrets) and `PUBLIC_API_URL` (a plain variable). The GitHub app “Cloudflare Workers and Pages” has access to `nchat-friends` and to `NChat-kotlin` (owner's report). The second build project, `nchat-friends-deploy`, is the old web client; it was left alone and stays with the old repository. `worker/README.md` lists the same build settings.

**What the steps in the subsection above did not have.** Those steps were written without a look at the dashboard. The steps of that evening were written from Cloudflare's documentation, read the same day; the dashboard itself no agent has seen. Added by them: the watch paths have two fields — include `worker/*`, leave Exclude empty; there is a switch for building other branches (“Enable Preview Builds”, under “Branch control”), which has to be off, or every push to a work branch that changes `worker/` starts a build; and to change the repository, builds are disabled first and then connected again. The place of the secrets is written “Variables and Secrets” in the subsection above and was written “Runtime variables and secrets” in that evening's steps; which of the two the dashboard shows, nobody wrote down.

**A guess that was wrong.** Before the owner had reported what the dashboard showed, the agent said in the chat that builds had most likely stopped on 2026-11-03 because the production branch was a work branch (the old guides name `arena/940244a7-nchat-friends`). The dashboard showed `main`. Why every build from `nchat-friends` failed after 2026-11-03 is still unknown.

**The old failures were never read.** Asked for the log of the last failed build, the owner answered “there is no failed deployment i see”. The Deployments list shows what went live; by Cloudflare's documentation, failed builds are in another list, at the bottom of the Deployments tab under “View build history”. The owner was not sent there a second time. The newest entry in Deployments now comes from that day's changes in the dashboard, so its date no longer tells which code is running. From now on a failed build is a red check on the commit in this repository, and its “Details” link opens the build for the owner.

**The first build from this repository.** Connecting started none: at 16:54 UTC the head of `main`, `420fa1b`, carried the GitHub Actions check and no Cloudflare check. Cloudflare builds when a watched file changes on `main`, so `worker/README.md` was added for that and for nothing else — pull request #91, no code. **Its result was not known when this was written.** To read it:

```
gh pr view 91 --json state,mergedAt,mergeCommit
gh api repos/cybereagle472/NChat-kotlin/commits/<merge commit>/check-runs \
  --jq '.check_runs[] | [.name, .app.slug, .status, .conclusion] | @tsv'
```

Cloudflare's checks come from the app `cloudflare-workers-and-pages`. In the old repository the backend's check was named `Workers Builds: nchat-friends`; a second one, `Workers Builds: nchat-friends-deploy`, belongs to the old web client and is not expected here.

- A check `Workers Builds: nchat-friends` with `success`: the backend was deployed from this repository. If the live backend was the build of `7916475` (the subsection above), one thing changes for users — username search in the Find tab starts to work. The database is not touched.
- The same check with `failure`: nothing changed for users. Ask the owner to open the check's “Details” link and copy the log into the chat. Fixing the build comes before any other work.
- No Cloudflare check at all: Cloudflare did not build. Ask the owner to report the build settings again.

Whichever it is, write it here, in §36 and in §0 rule 6.

**Seen from outside.** `curl` in the sandbox cannot reach the backend (the network allowlist). The page-fetch tool can: `GET /` answers `{"ok":true,"service":"nchat-api"}` and `GET /health` answers `{"ok":true}`. Two traps. Fetching the same address a second time, seven minutes later, gave the earlier answer although the state had changed in between; a fetch with a new query string, minutes after that, showed the change. Whether the tool had kept its first answer or Cloudflare needed those minutes was not settled — put a new query string on every probe (`/?check=<something new>`), and the doubt does not arise. And both answers are constants: they show that the backend is up, not which version it is (§24 #37).

**The rename of 2026-11-11.** During the setup the owner renamed the Worker in the dashboard from `nchat-friends` to `nchat-kotlin`. No step had asked for it. The agent had written that the name stays; it had not written what a rename does. This is what was seen:

- The address changed with the name. At about 16:30 UTC `https://nchat-kotlin.usa66486.workers.dev/health` answered as the backend, and `https://nchat-friends.usa66486.workers.dev/health` was not found. When the rename was made was not written down.
- The installed app had nothing to reach. Its address is fixed when the app is built (`BuildConfig.NCHAT_API_BASE`; `data/ServiceLocator.kt`), and no setting changes it. That the built-in address is the old one is the owner's word (the subsection above); what the app showed in those minutes was not reported.
- The owner set the name back. A few minutes after 16:37 UTC `https://nchat-friends.usa66486.workers.dev/` answered `{"ok":true,"service":"nchat-api"}` again, and the `nchat-kotlin` address showed Cloudflare's page “There is nothing here yet”. At 16:54 UTC the old address answered the same again.
- The owner checked `PUBLIC_API_URL` afterwards and reported the right address. It matters: a file uploaded while that variable pointed at another name would be stored with an address that stops existing when the name goes back.
- The owner then sent a message and a photo from the app and reported that it worked.
- Nothing is known to be lost. The database and the bucket do not hang on the Worker's name, and the secrets came through: the app worked afterwards, which it cannot without `JWT_SECRET`. The app also keeps its session through such an outage: only a 401 or 403 from `/auth/refresh` clears it (`network/ApiClient.kt`), and an address with no Worker behind it gives neither.

Why the name cannot simply change:

1. *The app.* Every installed copy has the address built in. Another name needs a new release, and each phone is cut off until it has installed it. The updater itself would still work: it reads `version.json` from GitHub, not from the backend (`core/update/AutoUpdater.kt`).
2. *Stored media.* An upload is answered with a full address, `PUBLIC_API_URL` + `/media/file/<key>` (`worker/src/routes/media.ts`). The app stores that address and sends it back (`data/repository/RemoteChatRepository.kt`, `RemoteProfileRepository.kt`, `StatusRepository.kt`), and the backend saves what it is given (§24 #35 lists the columns). Every photo, voice note, video, document and profile picture sent so far is stored with `https://nchat-friends.usa66486.workers.dev` in front.
3. *`worker/wrangler.toml`.* Its `name` has to be the Worker's name — Cloudflare's documentation says the build fails otherwise; one public report says the build replaces the name instead; neither was tried — and `PUBLIC_API_URL` has to be the address that works.

If the owner asks for another name again, say all of this first. A way that cuts nobody off exists on paper and was not tried: keep a small second Worker under the old name that passes every request on to the renamed one, release an app with the new address, and rewrite the stored addresses or stop storing the host. A custom domain is the better long-term answer and is the same work. The owner was told that a slower way exists and did not ask for it.

**`JWT_SECRET`, state on 2026-11-11.** The question of the subsection above went unanswered several times that day and was then misunderstood twice (“Jwt is already set upped what's wrong with it ?”). What worked is in Appendix C. The owner's answer: “I think it was copied from claude code or maybe from cloudflare … I'm not sure”. Either way the value was copied from somewhere, and the one place known to have offered a value is the public guide. **It is treated as public.**

The owner was advised to replace it and has the steps, as they were written in the chat: the Worker → Settings → “Runtime variables and secrets” → edit `JWT_SECRET` → a new value of 40 or more random characters → type Secret → save; tell nobody the value, the agent included. **Whether it has been done is not known. Ask.**

What replacing it costs — looked up in the code only after the advice had been given, which was the wrong order:

- Every access token and every refresh token stops verifying (both are signed with it; `worker/src/routes/auth.ts`), so every app is sent to its sign-in screen. No message, account or file is touched.
- Nobody can change or reset a password (§24 #36). A user who has forgotten the password cannot come back alone.
- The agent first offered to add “change password” to the app before the replacement. **Do not build it that way.** A route that sets a new password for whoever holds a valid token, without the old password, lets anyone who can forge a token take an account over for good — and while the secret is public, that is anyone. A route that asks for the old password does not help the user who forgot it.
- The safe order: replace the secret first. If a user is then locked out, the owner sets that one account's password by hand. `users.password_hash` is `100000:<salt, hex>:<hash, hex>` — PBKDF2 with SHA-256, 16 bytes of salt, 32 bytes out (`worker/src/lib/password.ts`). An agent can compute such a line for a temporary password with Node's Web Crypto, and the owner runs `UPDATE users SET password_hash = '<line>' WHERE email = '<address, lower case>';` in the D1 console. Not tried. The temporary password passes through the chat, and the user cannot replace it until “change password, old password required” exists — which is why that belongs right after.

### Update signing migration (historical `UPDATE_SETUP.md`)

Create a private permanent keystore with `keytool`, back it up, add `SIGNING_KEY_BASE64`, `KEY_STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as GitHub Actions secrets, and update the workflow to consume them. Android cannot update the existing demo-signed installation with a differently signed APK; plan migration and warn users that uninstalling may remove local-only data. Keep `versionCode` and `version.json` aligned, pin the new certificate fingerprint, use exact versioned release asset URLs, and only add independent HTTPS mirrors in `apkUrls` after provision/verification.

---

## 28. Automated testing procedures

| Suite | Command / workflow | Current evidence |
|---|---|---|
| APK verification Python tests | `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v` | 7 tests, all OK in this session |
| Android JVM unit tests | `gradle :app:testDebugUnitTest` in CI | Green on `d87b703` via run `42584666249`; 21 test files tracked, cannot run locally (no Java/Gradle) *Update 2026-11-10 (second Phase 1 turn):* 67 `@Test` in 22 files on the branch; red by design on `6bc65dc` (run `42802241927`: 58 tests, 1 failed), green on `5091dbb`, `8a506c8`, `6eee8c6`, `2b30f1d` (runs `42802654826`, `42803934030`, `42805221994`, `42805578589`). |
| Android release build/signature | `gradle :app:assembleRelease` + apksigner/aapt/Python verification in CI | Green on `d87b703`; pinned fingerprint checked |
| Rust build/clippy/test | `cargo build --all-targets`, `cargo clippy --all-targets`, `cargo test` with zero-test guard | 18 tokio integration tests + unit tests defined; last green run `42491305703` on prior SHA; no local cargo *Update 2026-11-10:* Phase 1 branch — 63 tokio integration tests (`api` 9, `authz` 45, `groups_status_calls` 4, `media` 5) + 16 unit tests, green on `a1d2398` (run `42794684951`). `main` at `a867fdd` is green with the earlier 18 (run `42779970114`). *Update 2026-11-10 (second turn):* 69 tokio integration tests (adds `auth_hardening.rs`, 6) and 20 unit tests by source count; red by design on `6eee8c6` (run `42805222571`: `auth_hardening` 4 passed, 2 failed), green on `2b30f1d` (run `42805578387`). *Update 2026-11-10 (third turn):* 94 tokio integration tests (adds `phase1_remainder.rs`, 25) and 26 unit tests by source count; red by design on `6335e5d` (run `42854637982`: `phase1_remainder` 2 passed, 22 failed) and on `899879f` (run `42902301878`: 2 passed, 23 failed), with every earlier test file passing in both; green on `e0f3b5a` (run `42903011159`), on `bef2644` (run `42903567155`) and for pull request #89 (run `42904397920`). |
| Worker typecheck | `cd worker && npm ci && npm run typecheck` | Exit 0 in fresh backend clone, this session. *2026-11-11:* exit 0 in `worker/` of this repository (TypeScript 5.9.3, Node 22.22.3); `npx wrangler deploy --dry-run --outdir <dir>` exit 0, 169.88 KiB, bindings `DB`, `MEDIA`, `REALTIME` (Wrangler 3.114.17). Both run locally; `worker/` has no CI *2026-11-11, evening:* run again before the first deployment from here — the same results |
| Web frontend | `npm ci && npm run type-check && npm run build` | Exit 0; 1649 modules; Vite built in 3.14 s, this session |
| Web lint | `npm run lint` | Known broken because ESLint is not installed; do not treat as a green gate |
| Cloudflare deployment | Workers Builds check-runs | Both frontend/backend builds failed on `616a9e6`; needs dashboard logs. *2026-11-11:* failed on all 23 commits from `78af299` to `616a9e6`, green on the 17 before that which were looked at, the newest of them `7916475` *2026-11-11, evening:* the build is connected to this repository. The check to read is `Workers Builds: nchat-friends` on a commit of `main` that changes `worker/**`; none had been seen when this was written (§27) |

Missing suites: API authorization matrix, migration against a DB copy, WebSocket room authorization, Android instrumented/Compose UI tests, E2E journeys, network interruption, load/performance, security regression, backup restore, and multi-device tests. *Update 2026-11-10:* for the Rust backend an API authorization suite now exists (`server/tests/authz.rs`, Phase 1 branch), and the WebSocket room rule is covered by `api.rs::websocket_restricts_rooms_and_rejects_refresh_credentials`. The Worker has neither. *Update 2026-11-10 (third turn):* the Rust backend also has tests for its limits, CORS, the admin role, input sizes and media reuse (`server/tests/phase1_remainder.rs`). Everything else in this list is still missing. *Update 2026-11-11:* the suites the app's users need are the ones for `worker/`, and none exists: no test of any kind runs against the Cloudflare backend. That is step 1 of the next task (§33).

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
| 002 | Keep two backends wire-compatible but separate: Cloudflare Worker is reference, Rust is port; TypeScript contract wins on drift | Avoids assuming one runtime fixes the other; current divergence documented. **Note 2026-11-10 (security exception):** where Rust refuses a request the Worker accepts (§8, §19.1, §19.3), the Rust behaviour is the target and the Worker is the one to change — the roadmap already says “mirror Rust” (§33 item 2). Never loosen Rust to restore parity. **Note 2026-11-11:** ADR 027 — the Rust backend is out of use; its tests describe the target, and the target is built in `worker/` |
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
| 017 | The Phase 1 branch is merged only when all of Phase 1 is finished (owner, 2026-11-10) | Keeps `main` and the released app as they are until then. Cost: the updater repair (1.0.75) and the Rust fixes wait with it, and the branch must be checked against `main` before merging (§27, Android rule 13). **Superseded 2026-11-10 (third turn)** by ADR 019 |
| 018 | Sign-in attempts are limited per account as well as per address (Rust, 2026-11-10) | The address is client-written unless a trusted proxy sets it; a per-account budget cannot be reset by the client. Accepted cost: a targeted, temporary sign-in lockout for new devices |
| 019 | Phase 1 is finished in this repository alone and then merged; the Worker repository `nchat-friends` is outside this work (owner, 2026-11-10, third turn; replaces ADR 017) | The published app talks to the Worker, so the Rust fixes do not reach its users; the merge releases Android 1.0.75 and deploys no server. The sequence and cursor of §33 item 6 move to Phase 3. Cost: the Worker's Phase 1 gaps (§19.1, §19.3, §19.4) stay open until the owner asks for them. **Changed 2026-11-11** by ADR 026: the Worker's code is in this repository now, the owner has asked, and Phase 1 on it is done here |
| 020 | Other users' e-mail addresses and admin flags are not sent (owner, 2026-11-10; Rust) | People find each other by name and username; an account still reads its own address. Deliberate drift from the Worker (ADR 002 note) |
| 021 | The address a limit counts against is the connection's peer; a forwarding header is believed only for a stated number of proxies (`NCHAT_TRUSTED_PROXIES`, default 0) (Rust, 2026-11-10) | A header is written by the sender. Believing it whenever the peer looks private was rejected: in a rootless container every client looks private, and the limit could be stepped around without anyone noticing. With 0 behind a proxy the failure is loud instead — everyone shares one budget and the log says why |
| 022 | The administrator is a stored role (`users.is_admin`); `NCHAT_ADMIN_EMAIL` only assigns it, once, at start-up (Rust, 2026-11-10) | E-mail addresses are not verified, so an address must not grant anything to whoever registers it later. Cost: the admin's account has to exist before the restart that names it |
| 023 | Media access is the union over every place that mentions a file, under its exact key; and a media address is stored only if its sender can already open the file (Rust, 2026-11-10) | The two belong together: the union alone would have made mentioning a file a way to open it. Cost: an `instr` search on every store and read; references stored earlier are trusted |
| 024 | CORS is closed unless origins are listed in `NCHAT_CORS_ORIGINS` (Rust, 2026-11-10) | The Android client is not a browser and needs no permission. A web client on another origin has to be listed; `*` gives the former behaviour |
| 025 | A stored file keeps its declared type only if it is a picture, sound or video; everything else is sent as a download (Rust, 2026-11-10) | A file's type is its uploader's claim. Putting a sandboxing policy on every media answer was rejected: it could stop a picture or video opened directly from a share link |
| 026 | All work happens in `NChat-kotlin`, and the code of the Cloudflare backend lives here, in `worker/` (owner, 2026-11-11) | The owner works in one repository, and a fix has to land in the backend the app uses. While that code was elsewhere, three turns of security work went into the Rust backend, which nobody uses. Cost: the owner has to connect Cloudflare to this repository before anything deploys from here (§27); the web client stays behind in `nchat-friends` |
| 027 | The Cloudflare Worker is the backend. The Rust backend in `server/` gets no further work unless the owner asks for it (2026-11-11; proposed by the agent in the message that proposed the move, which the owner then ordered — the owner has not spoken about `server/` by itself) | One backend in use, one place for a fix. Cost: `server/` falls behind `worker/`. Its tests stay, as a description of the behaviour wanted |
| 028 | The move is a plain copy with the source commit recorded, not an import of the other repository's history (2026-11-11) | Easy to check (equal blob ids) and to undo (one revert). The history of those files can be read in `nchat-friends` up to `616a9e6` |
| 029 | The three guides in `worker/` are not carried over (2026-11-11) | This file is the one document of this repository. The guides describe the other repository, are stale in places, and one prints a ready-made secret |
| 030 | The Worker keeps the name `nchat-friends` (owner, 2026-11-11 — by setting it back after a rename) | The name is the address: it is built into every installed app and stored in front of every media file (§27). While the Worker carried the other name, the old address did not answer. Cost: the name of a repository that is no longer used stays in the dashboard and in the address. Another name needs a new app release, something that still answers under the old address, and a rewrite of stored addresses |
| 031 | Cloudflare's own build deploys the backend on every merge to `main` that changes `worker/**`; other branches are not to be built (the owner's dashboard settings, 2026-11-11, as reported) | One setup step for the owner, none per change, and no Cloudflare token in GitHub. Cost: until `worker/` has tests in CI, nothing stands between a merge and every user (§24 #38), and an agent sees the result of a build but never its log |
| 032 | `worker/` has one short `README.md`, written here (2026-11-11) | The rules that must never be broken — the name, `PUBLIC_API_URL`, where secrets live — belong next to `wrangler.toml`, where a person opening the folder reads them. It is a summary and points here; this file stays the memory (ADR 029 was about the old guides, not about having a README) |

---

## 32. Recent changes and commits

### `NChat-kotlin` `main` (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `45d793a8219f` | 2026-11-11 13:29:07 | Merge PR #89 — the Phase 1 branch: Rust backend security work, updater repair, Android 1.0.75 (row added 2026-11-11) |
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

Additional version-marker commit: `d1be4f5d16` “Release NChat 1.0.69” (2026-11-08 07:58:01). Current `version.json` is 1.0.74; release notes describe the Android system-download fallback for direct-transfer update timeouts. *2026-11-10, third turn:* pull request #89 brings the Phase 1 branch — the three tables below — to `main`. Its merge commit is missing from the table above because this file was committed before the merge: `gh pr view 89 --json mergeCommit`.

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

### `NChat-kotlin` branch `arena/56647832-nchat-kotlin` — third Phase 1 turn: the rest of item 7, e-mail addresses, shared media (most recent first)

| SHA | Date UTC | Subject |
|---|---|---|
| `bef2644eaaab` | 2026-11-10 21:17:20 | ci: remove the backend failure log of the expected red runs |
| `e0f3b5afbcb6` | 2026-11-10 21:13:57 | server: budgets, sizes and media checks in the handlers; hide e-mail addresses |
| `b15a73c795e4` | 2026-11-10 21:13:57 | server: connection addresses, budgets, CORS and the admin role |
| `839542f6d046` | 2026-11-10 21:11:32 | ci: backend failure log [skip ci] |
| `899879f65f6a` | 2026-11-10 21:09:42 | server: test that reactions on one message are bounded (expected to fail) |
| `b349c0b766dc` | 2026-11-10 16:29:10 | ci: backend failure log [skip ci] |
| `6335e5d64cd3` | 2026-11-10 16:26:58 | server: tests for the rest of Phase 1 (expected to fail) |
| `13b2be40d002` | 2026-11-10 16:26:58 | server: settings for the rest of Phase 1 (no behaviour change) |

Below these is `76c9e40`, the `agent.md` commit of the second turn. `13b2be4` adds the fields and builder methods that the tests name, with the old behaviour as their default, so that the tests-only commit `6335e5d` compiles and fails for the right reason. The two `[skip ci]` commits were made by the workflow on the two red-by-design runs; `bef2644` removes the log again. `b15a73c` and `e0f3b5a` were pushed together and CI built only `e0f3b5a`: the tree of `b15a73c` alone was never compiled (§34 item 9). The commit after `bef2644eaaab` changes only `agent.md`; `e0f3b5afbcb6` is the last one that changes code.

*2026-11-11:* the three tables above are on `main` since pull request #89 (`45d793a8219f`); `bfeef11d94e2` is the `agent.md` commit that followed `bef2644eaaab`.

### `NChat-kotlin` branch `arena/6151c100-nchat-kotlin` — the Cloudflare backend moves here (2026-11-11)

| SHA | Date UTC | Subject |
|---|---|---|
| `89f689da6ddc` | 2026-11-11 14:08:37 | worker: move the Cloudflare backend into this repository |

Below it is `45d793a8219f`, `main` after pull request #89. The commit after `89f689da6ddc` changes only `agent.md`. Pull request #90 carries both to `main`.

### `NChat-kotlin` branch `arena/7cfa350c-nchat-kotlin` — first deployment from this repository (2026-11-11, evening)

| SHA | Date UTC | Subject |
|---|---|---|
| `cc2bb619a748` | 2026-11-11 17:43:59 | worker: README says only what was seen |
| `7bee103d33fe` | 2026-11-11 17:29:59 | worker: describe how the Cloudflare backend is deployed from this repository |

Most recent first. Below them is `420fa1bb69ed`, `main` after pull request #90 (merged 2026-11-11 14:20:06 UTC). The commit after `cc2bb619a748` changes only `agent.md`. Pull request #91 carries both to `main`, and merging it starts the first Cloudflare build from this repository.

### `nchat-friends` `main` (most recent first)

*2026-11-11:* unchanged since `616a9e6`; `worker/` was copied from that commit, and this repository is not worked in any more (§0). What Cloudflare made of each commit is in §27.

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
| 1 Private media | Was already private; status media now follows the status audience (`b647f5a`). One over-restriction discovered, not fixed (§19.3 #23). *Third turn:* fixed, together with attaching by reference, second spellings of a key and served types (`e0f3b5a`; §19.5 #23, #30–#33) | NOT_STARTED |
| 2 Realtime rooms | Was already fail-closed; covered by an `api.rs` test | NOT_STARTED |
| 3 Group membership checks, e-mail redaction | Done — `0ec858c` | NOT_STARTED |
| 4 Status privacy | Done — `b647f5a` (§9 item 11) | NOT_STARTED |
| 5 DM participant/friend/block checks | Was already enforced; now covered by `authz.rs` | NOT_STARTED; blocking policy not reconciled |
| 6 Durable persistence, sequence, cursor | NOT_STARTED as work. Reviewed 2026-11-10: `messages::send` already persists before it publishes (§19.4). Sequence, cursor and missed-event recovery are a protocol change for both servers and the app; they overlap Phase 3 and need a design decision first. *Third turn:* moved to Phase 3 by the owner; nothing more is planned for it under Phase 1 | NOT_STARTED |
| 7 Rate limits, CORS allowlist, admin role, input review | IN_PROGRESS — per-account sign-in budget done (`dd4b497`, §19.4 #28); SSRF, CORS and the admin gate reviewed (§19.4). Open: the real client address behind a proxy, a per-address sign-up limit, limits beyond sign-in, the CORS allowlist, sign-in timing. *Third turn:* done in Rust — the connection's address (`NCHAT_TRUSTED_PROXIES`), a credential budget per address, send and contact budgets, the CORS allowlist, the stored admin role, sign-in timing, text sizes and clean answers to bad input (`b15a73c`, `e0f3b5a`; §19.5 #34–#43). What the review left open is listed under §19.5, “Not fixed” | NOT_STARTED — no rate limiting at all in `worker/src` (§19.4 #28) |
| 8 PBKDF2 migration plan | Done as far as it can be without real data — `dd4b497`: the upgrade mechanism, the plan in §27, and tests whose stored hashes were computed by another implementation and copied into the test database. **Not done:** a run against a copy of a real database (none is available to the agent). The default count is unchanged on purpose | NOT_STARTED — the runtime's PBKDF2 limit has to be verified first (§27) |
| Added: call-signal integrity and false announcements (§19.3 #11–#19) | Done — `0ec858c`, `84ceec5`, `d247d39`, `feb3600` | NOT_STARTED |
| Added: in-app updater repair, Android 1.0.75 (§19.4 #24–#27) | Not a server item. Android: done on the branch (`c5eed26`, `4b3f2a1`, `8a506c8`); not run on a device; not released | Not applicable |
| Added (third turn): other users' e-mail addresses hidden (§19.5 #44) | Done — `e0f3b5a` | Not changed; outside this work |

**Owner decisions, 2026-11-10 (second turn).** Given as answers to two structured questions; the owner's free-text wording was not kept, so none is quoted. (1) *Merge:* the Phase 1 branch goes to `main` only when **all** of Phase 1 is finished (answer `all_phase1_then_merge`). No pull request and no merge before that; this also keeps 1.0.75 from phones until then. (2) *Updates stay optional:* “Not now” is kept; nothing is forced. (3) *What had happened with earlier updates:* the answer was “Downloaded, then an error”. The exact message and the Android version were not given; ask for both if it happens again. Still unanswered from the first turn: other users' e-mail addresses in `/users` and `/friends` responses, and §19.3 #23.

**What “all of Phase 1” still needs (2026-11-10, after the second turn).** *Worker:* everything — items 1–5, the call-signal findings, rate limiting, the password guards. It needs a session on `nchat-friends`, and first a way to run Worker tests. *Rust:* the rest of item 7 (listed in the table) and item 6's sequence and cursor, which is a design decision before it is code. *Android:* nothing is required by Phase 1 itself; `handleGroupEvent` not matching `group-call:participant-left` (§24 #18) is a known client bug outside it.

**Next precise task (as of 2026-11-10):** port the same checks to the Worker in `nchat-friends`, test-first: `worker/src/routes/groups.ts`, `status.ts`, `calls.ts`, `groupCalls.ts`, `friends.ts`, then `media.ts` and the realtime room rule. `server/tests/authz.rs` is the specification — each test is one request and the answer it must get. This needs a session on that repository and, first, a way to run Worker tests (there is none). Decisions to get from the owner before or alongside: whether other users' e-mail addresses stay in `/users` and `/friends` responses, whether to fix §19.3 #23, and merging the Phase 1 branch. *Update after the second turn:* unchanged — the Worker is the next task, and it is now also what unblocks the merge; `server/tests/auth_hardening.rs` joins `authz.rs` as specification. In this repository the next contained step is the client address behind a proxy (`ratelimit::client_key`, `ConnectInfo`, a trusted-proxy count), test-first.

**Owner decisions, 2026-11-10 (third turn).** Given as answers to four structured questions; the chosen options were kept, the owner's own wording was not, so none is quoted. (1) *Which server the app uses:* the Cloudflare Worker (option `workers_dev`). The Rust server in this repository is therefore not what the published app talks to. (2) *What happens next:* finish the small remaining parts of Phase 1 in this repository, then merge (option `small_then_merge`). This replaces decision (1) of the second turn and ADR 017: “Phase 1” now means this repository only, `nchat-friends` is outside this work until the owner asks for it, and the sequence, cursor and missed-event recovery of item 6 move to Phase 3. (3) *Other users' e-mail addresses:* hide them (option `hide`). (4) *§19.3 #23:* fix it (option `fix`).

**Phase 1 after the third turn (2026-11-10).** In this repository Phase 1 is finished as the owner defined it. Rust: items 1–5, 7 and 8 and the added rows are done, each with the limits its section states; item 6's first half was reviewed (§19.4) and its second half belongs to Phase 3. Android: the updater repair, 1.0.75. Pull request #89 carries all of it to `main`; merging it publishes 1.0.75 and deploys no server. **Not part of it and still open:** the whole Worker, which is the server the app's users are on — as it was last read, in the first two turns and not since: public media, open realtime rooms, the authorization gaps of §19.1 and §19.3, no rate limiting, e-mail addresses in profiles; and the “Not fixed” lists of §19.3, §19.4 and §19.5.

**Next precise task (as of 2026-11-10, after the third turn):** the owner decides. The largest remaining risk for the app's users is the Worker: a session on `nchat-friends`, first a way to run Worker tests, then the same checks, with `server/tests/authz.rs`, `auth_hardening.rs` and `phase1_remainder.rs` as the specification. In this repository the next contained steps are small and listed under §19.5, “Not fixed”: the file of a message deleted for everyone, the unbounded fields of the call routes and of `POST /status/mute`, an index on `media_attachments.file_path`. Otherwise Phase 2. Right after the merge: confirm on a phone that 1.0.74 offers 1.0.75 and that it installs — nothing about the updater has ever been tried on a device.

**Owner decisions, 2026-11-11.** After pull request #89 was merged the owner asked whether Phase 1 was completed, and it came out that the two sides had meant different things all along. The owner's product is the Android app with its Cloudflare backend — Worker, D1, R2 — and “the backend in this repository” had meant that to the owner; the agent had been fixing `server/`, the Rust backend, because it was the only backend code in this repository. The owner also said that `nchat-friends` is not the repository to work in, and that this had been said before. The decision, in the owner's words: “Ok then let's move the cloudflare here. Mention this into agent.md so that new agent perform this thing. And merge to main branch.” What follows from it is §0, ADR 026–029, and the task below. It replaces the third-turn reading in which “Phase 1” meant the Rust backend of this repository and could be called finished.

**Phase 1 as it stands on 2026-11-11.** On the backend the app uses — the Cloudflare backend, now `worker/` — Phase 1 has not been started: every row of the table above reads NOT_STARTED in its Cloudflare column, and that column is the one that counts. What exists is the same work on `server/`, which protects nobody (§19.3–§19.5, tests in `server/tests/`), and, in the app, the updater repair released as 1.0.75.

**Next precise task (2026-11-11) — Phase 1, critical security, in `worker/`.** Small commits, tests first: a test is pushed, seen failing in CI, then made to pass. Nothing here reaches a user until Cloudflare deploys it (§0 rule 6); say that to the owner before starting.

1. **A way to test the Cloudflare backend.** There is none. Add a test runner that runs the Worker against a local D1 database, R2 bucket and Durable Object — Vitest with `@cloudflare/vitest-pool-workers` is the usual choice; check that it works with the Wrangler in `worker/package.json` before settling on it, nothing was tried — with `migrations/0001`–`0005` applied to the test database. Add `.github/workflows/worker.yml`: on pushes and pull requests that touch `worker/**`, run `npm ci`, `npm run typecheck` and the tests. The first test signs up, signs in and reads `/users/me`.
2. **The two findings rated critical** (§19.1 #1, #2).
   - *Media is public.* `GET /media/file/*` has no sign-in check (`worker/src/routes/media.ts`, the one route there without `requireAuth`). What the answers should be: `server/tests/media.rs`, `authz.rs`, and the media tests of `phase1_remainder.rs`. The Android app already sends its token when it loads media (`network/MediaImages.kt`, `MediaFiles.kt`), so the app keeps working. A picture opened in a browser sends no token: ask the owner first whether anything but the Android app still has to open media (question 3 below).
   - *Any signed-in account can join any `dm:`, `group:` or `call:` room* (`worker/src/index.ts`, `/realtime/connect`: only a foreign `user:` room is refused), and the Durable Object passes on whatever a client sends (`durable-objects/RealtimeRoom.ts`). The Android app joins one room, `user:<its own id>`, and never sends anything over the socket (`network/NChatSocket.kt`). Allow that room only, and stop passing on what clients send.
3. **Who may do what:** group members (§19.1 #4), status privacy and views (#5, #6), reactions (#7), and every row of §19.3 whose Worker column says CONFIRMED. `server/tests/authz.rs` (45 tests) says what each answer should be.
4. **Sign-in and limits:** `worker/src` has no rate limit at all (§19.4 #28), and `GET /auth/check-email` tells anyone whether an address has an account. A Worker keeps nothing in memory between requests, so the counters of the Rust design do not carry over: choose between Cloudflare's rate-limiting binding and counters in D1 or a Durable Object, and write down why. Then CORS (`origin: '*'` in `index.ts`), the admin check (`routes/admin.ts` compares with a fixed e-mail address), text sizes, and other users' e-mail addresses in profiles, which the owner decided to hide (third turn, above).
5. **Record** each fix in §19 with its tests, the way §19.3–§19.5 do, and keep §0 true.

Not part of this task: any change in `server/`; the web client; deploying.

**Questions for the owner (2026-11-11), in this order.** Ask them in plain words; record the answers here.

1. *The sign-in secret.* When Cloudflare was first set up, was the ready-made `JWT_SECRET` from the setup guide pasted in, or a value of the owner's own? If the ready-made one, or if unsure: replace it now (§27). Put to the owner in the report of 2026-11-11; no answer yet. *2026-11-11, evening:* answered — the owner is not sure where the value came from. It is treated as public; the owner was advised to replace it and has not confirmed that it is done (below, and §27).
2. *Which backend version is live.* In the app, Settings ▸ Sync check: does the row “Search (Find)” pass? If it fails, the live backend is the build of 3 November and no build since has gone live (§27).
3. *Who else uses the backend.* Does anything other than the Android app — the old web client, for one — still have to work against it? If yes, closing media and realtime rooms needs a plan for it first.
4. *When to connect Cloudflare to this repository.* Recommended: now, before the first fix. The first green build then changes one known thing (username search), and it shows whether builds can succeed at all before security fixes depend on it. *2026-11-11, evening:* done by the owner that day (below).

**Owner actions and answers, 2026-11-11 (evening).** In the order they happened, all of it in the chat, the owner working in the Cloudflare dashboard on a phone.

1. Asked for the difference between the Rust backend and the Cloudflare one to be made clear, and for every setup step with its reason, and the reason for every step left out. Answered with the table of §0 in plain words.
2. Reported what the dashboard showed: the Worker's three secrets and one variable, the production branch `main`, and the second build project (§27).
3. Renamed the Worker to `nchat-kotlin`, and set the name back once the agent had said what the rename had done (§27, “The rename of 2026-11-11”).
4. Checked `PUBLIC_API_URL`, and that the app sends a message and a photo.
5. Connected the build to this repository with the settings of §27. Question 4 above is answered by that.
6. `JWT_SECRET`, question 1: not sure where the value came from. Treated as public; replacement advised, not confirmed (§27).
7. Wrote “Go” for the first change under `worker/` to be merged — pull request #91.

Questions 2 and 3 above were not answered.

**First steps for the next session (2026-11-11, evening).** Before the Phase 1 task above, in this order:

1. *Read the result of the first Cloudflare build.* The commands are in §27, “The first build from this repository”. Record the result in §27, in §36 and in §0 rule 6. If the build failed, getting the log from the owner and fixing the build comes before everything else: no fix reaches a user until a build is green.
2. *Ask whether `JWT_SECRET` has been replaced.* If not, say again, in two sentences, why and how (§27). If a user is locked out afterwards, prepare the reset by hand that §27 describes.
3. *Ask questions 2 and 3 above:* does “Search (Find)” pass in Sync check, and does anything besides the Android app use the backend.
4. *Then Phase 1, step 1:* a way to test `worker/`, and a workflow that runs it. Deploys are automatic now, so this is also what stands between a mistake and every user (§24 #38). Until it exists: run `npm run typecheck` and the dry-run bundle before every merge that touches `worker/`, and tell the owner that the merge is a deployment.

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
9. **Third Phase 1 turn (2026-11-10):** code and settings only — no schema or client change. The two code commits were pushed together and CI built only the later one, so take both back, later first: `git revert e0f3b5a`, then `git revert b15a73c`. The tests in `server/tests/phase1_remainder.rs` describe the behaviour being removed and will fail; remove the file in the same change and say why. Milder than a revert, by setting: `NCHAT_CORS_ORIGINS=*` gives open CORS again; very large values for `NCHAT_SEND_RATE`, `NCHAT_CONTACT_RATE` and `NCHAT_AUTH_ADDRESS_RATE` take the new budgets out of the way. `NCHAT_MEDIA_PUBLIC=1` switches every media check off by making all media public — that is not a rollback. The only data that turn writes is `users.is_admin`, at start-up, when `NCHAT_ADMIN_EMAIL` is set; `UPDATE users SET is_admin = 0` undoes it. For Android 1.0.75 see item 5: a release cannot be taken back from phones that installed it, only followed by a higher version.
10. **Move of the Cloudflare backend (2026-11-11):** `git revert 89f689d` removes `worker/` and the three `.gitignore` entries. Nothing that runs is affected, because Cloudflare does not build from this repository until the owner connects it (§27). If it has been connected by then, connect it to `nchat-friends` again first: a build from this repository without `worker/` fails. The live backend itself is taken back in the Cloudflare dashboard (Deployments → roll back), not in git.
11. **Deployments from this repository (since 2026-11-11, evening):** to take the *backend* back to what ran before a build: Cloudflare dashboard → the Worker `nchat-friends` → Deployments → roll back to the version before. Then revert, on `main`, the commit the build came from, or the next merge under `worker/` puts it back. Pull request #91 itself adds only `worker/README.md`; reverting it changes no code, and the revert is again a change under `worker/`, so Cloudflare builds once more. To stop Cloudflare building from this repository: the Worker → Settings → Builds → Disconnect; the running version keeps serving. Never undo anything by renaming, deleting or re-creating the Worker (§27).

---

## 35. Agent handover instructions

**First of all (added 2026-11-11):** read §0 at the top of this file and do what it says. It names the backend the app's users are on, the one repository to work in, and the next task. If anything further down disagrees with it, §0 is right.

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
| 2026-11-10 | Third turn: tests alone (red by design) | push `6335e5d` on top of `13b2be4` (settings the tests name; no behaviour change); Rust run `42854637982`; APK run `42854637927` | Rust: failure — `phase1_remainder` 2 passed, 22 failed; every earlier test file passed first (unit 20, `api` 9, `auth_hardening` 6, `authz` 45, `groups_status_calls` 4, `media` 5). “a wrong password took 867.266347ms, an unknown account took 335.818µs”. APK: success. Log: `git show b349c0b:server/.ci/last-failure.log` |
| 2026-11-10 | Third turn: reactions test alone (red by design) | push `899879f`; Rust run `42902301878`; APK run `42902301971` | Rust: failure — `phase1_remainder` 2 passed, 23 failed; the new test fails at `tests/phase1_remainder.rs:1737` (a reaction of 5,000 characters was answered 200). APK: success. Log: `git show 839542f:server/.ci/last-failure.log` |
| 2026-11-10 | Third turn: the code | push `e0f3b5a` (with `b15a73c`); Rust run `42903011159`; APK run `42903011165` | Both success on the first build of the code. Rust steps: Build success, Test success, failure-log step skipped. The tests are those of the red runs, unchanged |
| 2026-11-10 | Third turn: failure log removed | push `bef2644`; Rust run `42903567155`; APK run `42903567079` | Both success |
| 2026-11-10 | Pull request #89 | `gh pr create`; `gh pr checks 89`; `gh pr view 89 --json mergeable,mergeStateStatus` | Rust `Build and test` passed in 1 m 55 s (run `42904397920`, on the merge ref); Android `build` passed in 4 m 34 s (run `42903567079`); `MERGEABLE`, `CLEAN`, on `bef2644` |
| 2026-11-10 | Release check before the merge (§27, Android rule 13) | `git show origin/main:version.json`; `gh release list`; `gh release view nchat-v1.0.75`; `git diff --stat origin/main...HEAD -- app` | `main` is at 1.0.74 / 82 (`ec0f86c`); no release `nchat-v1.0.75` exists; the branch changes 10 files under `app/`, all of them the updater and the version, which is what `releaseNotes` describe |
| 2026-11-10 | Rust format and clippy on the third turn | check-run annotations of run `42903567155` | One “Process completed with exit code 1.”, as on `main`: the format step, by the inference of §24 #16. No second one, so clippy raised no error-level lint. Warnings cannot be read |
| 2026-11-10 | Local checks of the third turn | tree-sitter on every changed Rust file; reading | Syntax only. The changed files were read through as a compiler would read them, and tests were traced by hand against the code before the push; how many was not recorded. Nothing was compiled or run locally (no toolchain) |
| 2026-11-10 | Rust server outside CI | — | **Not done.** No real proxy, browser or client was pointed at the server; the address rule, CORS and the media headers are verified by tests that drive the router in-process |
| 2026-11-10 | Code changes of the third turn | `git diff --stat 76c9e40..bef2644` | 17 files, 2,880 insertions, 168 deletions: 16 files under `server/src` and the new `server/tests/phase1_remainder.rs` (1,794 lines). Route table unchanged (72 `.route(` lines); `server/schema.sql`, `app/` and `.github/` untouched |
| 2026-11-11 | Pull request #89 merged | `gh pr checks 89`; `gh pr merge 89 --merge`; `gh pr view 89 --json state,mergedAt,mergeCommit` | On its last head `bfeef11`: Rust `Build and test` passed (run `42907827548`, 2 m 2 s), Android `build` passed (run `42907816660`, 3 m 6 s). `MERGED` at 2026-11-11T13:29:08Z, merge commit `45d793a8219f17f7a2e14bbb9f8a1d7bcc749632` |
| 2026-11-11 | `main` after that merge; the release | `gh run list --branch main`; `gh run view`; `gh release view nchat-v1.0.75`; `curl -sI` on `apkUrl` | Rust run `43029539597` success (Build, Test). APK run `43029539594` success, with “Verify release APK version and signature” and “Publish latest release asset”. Release `nchat-v1.0.75` published 2026-11-11T13:33:51Z, marked latest, asset `nchat-ui-lab.apk` 31,131,359 bytes. The asset address answers 302 at `github.com`; the file itself cannot be fetched from the sandbox. Not installed on any device |
| 2026-11-11 | Both repositories are public | `gh repo view <repo> --json isPrivate` | `false` for `nchat-friends` and for `NChat-kotlin`; copying between them publishes nothing new |
| 2026-11-11 | The move | `git archive 616a9e6 worker \| tar -x`; per file `git hash-object` against `git ls-tree -r 616a9e6 worker` | 30 files identical, 0 different; `LICENSE` identical; every mode 100644; three guides left out on purpose. Commit `89f689d`: 32 files, 5,109 insertions |
| 2026-11-11 | No secret came along | `grep -n -i -E` for secret, token, key and password patterns over the moved files | Only code that reads `c.env.*`; no secret value. `wrangler.toml` holds identifiers (the D1 database id, the Worker's address), which were public before. `routes/admin.ts` holds one e-mail address, also public before |
| 2026-11-11 | The moved backend builds in its new place | in `worker/`: `npm ci`; `npm run typecheck`; `npx wrangler deploy --dry-run --outdir /tmp/worker-dry` | 64 packages installed; type-check exit 0 (TypeScript 5.9.3); dry run exit 0, 169.88 KiB (gzip 36.51 KiB), bindings `REALTIME`, `DB`, `MEDIA`, var `PUBLIC_API_URL` (Wrangler 3.114.17, which reports itself out of date). Local runs on Node 22.22.3; no CI covers `worker/` |
| 2026-11-11 | Cloudflare build results per commit | `gh api repos/cybereagle472/nchat-friends/commits/<sha>/check-runs` for the newest 40 commits of `main` | Both projects (`nchat-friends`, `nchat-friends-deploy`): failure on the 23 commits from `78af299` (2026-11-03 05:09 UTC) to `616a9e6`; success on the 17 before, newest `7916475` (05:03 UTC). The checks carry a build id and no log |
| 2026-11-11 | What the backend gained after the last green build | `git log 7916475..616a9e6 -- worker`; `git diff --stat` | One commit, `9011222`: `worker/src/routes/users.ts` +60 lines, the route `GET /users/search`. At `7916475` the path `/users/search` matches `GET /users/:id`, which answers 404 “Not found” for an id that names nobody (source reading) |
| 2026-11-11 | What the Android app needs from the backend here | `grep` in `app/src/main` | `GET /users/search` in `network/NChatApi.kt` (Find tab) and as the probe “Search (Find)” in `core/diagnostics/SyncCheck.kt`; the socket joins `room=user:$userId` and no other room (`network/NChatSocket.kt`) |
| 2026-11-11 | How the Cloudflare backend checks a token | reading `worker/src/middleware/auth.ts`, `lib/jwt.ts` | Signature and expiry only; the user id is taken from the token; nothing is looked up. Hence the question about `JWT_SECRET` in §27 |
| 2026-11-11 | **Not done / not possible** | — | No deployment and no Cloudflare access. The Actions variable `NCHAT_API_BASE` cannot be read (`gh api …/actions/variables` → 403), so that the app's address is the Worker's rests on the owner's word. The live backend was not called. Nothing ran on a device. No fix was made in `worker/` |
| 2026-11-11, evening | What the Cloudflare dashboard showed | the owner's messages in the chat; no agent saw the dashboard | Secrets `CF_TURN_KEY_API_TOKEN`, `CF_TURN_KEY_ID`, `JWT_SECRET`; variable `PUBLIC_API_URL`. The build was connected to `nchat-friends` with production branch `main`; a second build project, `nchat-friends-deploy`, is the old web client. After the owner's changes: the values in §27 |
| 2026-11-11, evening | The rename and the way back | the page-fetch tool on both addresses | About 16:30 UTC: `nchat-kotlin.usa66486.workers.dev/health` answered as the backend; `nchat-friends.usa66486.workers.dev/health` was not found. 16:37 UTC, the same two addresses once more: the same answers. Minutes later, with a new query string: `nchat-friends…/?check=…` → `{"ok":true,"service":"nchat-api"}`; `nchat-kotlin…/?check=…` → Cloudflare's page “There is nothing here yet”. 16:54 UTC: `nchat-friends…/?check=…` → the same answer again |
| 2026-11-11, evening | What a rename touches | reading `worker/src/routes/media.ts`, `messages.ts`, `groups.ts`; `app/…/data/ServiceLocator.kt`, `network/ApiClient.kt`, `data/repository/RemoteChatRepository.kt`, `RemoteProfileRepository.kt`, `StatusRepository.kt`, `core/update/AutoUpdater.kt` | An upload is answered with `PUBLIC_API_URL` + `/media/file/<key>`; the app stores and sends that address; the backend saves it as given. The app's address is fixed at build time. Only 401 and 403 from `/auth/refresh` clear the session. The updater reads `version.json` from GitHub, not from the backend |
| 2026-11-11, evening | No password change or reset exists | `grep` over `worker/src` and `app/src/main/java` | `routes/auth.ts`: `check-email`, `signup`, `login`, `refresh`, `logout`, `me`. No reset or change route in the backend, no such screen or call in the app |
| 2026-11-11, evening | Connecting started no build | GitHub check-runs of `main` at `420fa1b`, 16:54 UTC | One check, GitHub Actions `build`, success. No Cloudflare check |
| 2026-11-11, evening | `worker/` before its first deployment from here | in `worker/`, 17:29 UTC: `npm ci`; `npm run typecheck`; `npx wrangler deploy --dry-run --outdir /tmp/worker-dry` | exit 0; exit 0; exit 0 with 169.88 KiB (gzip 36.51 KiB), bindings `REALTIME`, `DB`, `MEDIA`, var `PUBLIC_API_URL` — the same as on the day of the move |
| 2026-11-11, evening | The live backend just before pull request #91 | the page-fetch tool, about 17:29 UTC | `/?check=…` → `{"ok":true,"service":"nchat-api"}`. `/users/search?q=a` without a token → `{"error":"Unauthorized"}`; the old and the new code both ask for sign-in first, so this does not show the version |
| 2026-11-11, evening | No Cloudflare check on the work branch | check-runs of `7bee103` on the work branch — it changes `worker/README.md` — at 17:30 and 17:32 UTC | Only GitHub Actions `build`, success. No Cloudflare check. That fits builds for other branches being off; it says nothing about whether builds work |
| 2026-11-11, evening | **Not known when this was written** | — | The result of the first Cloudflare build from this repository; it starts with the merge of pull request #91. Whether `JWT_SECRET` has been replaced. Which backend version was live before. Nothing ran on a device in an agent's sight: that the app worked after the rename is the owner's report |

**Current project health:** Android main build/CI green and PR #87 checks passed on the merged Phase 0 change; web/worker local typecheck/build green; Rust integration suite green on a recent prior commit but not re-run on HEAD; Cloudflare deployment blocked/failing; security status has multiple confirmed high-risk authorization gaps; no feature claims of full product completion; no known active Android compile regression; no production deployment performed; no destructive operation performed; `agent.md` is now available to future agents from `main`.

**Current project health — update 2026-11-10 (Phase 1 branch, not merged):** Rust backend — build and all tests green in CI on `a1d2398`, now including an authorization suite; the Rust authorization gaps of §19.1 #4–#7 and §19.3 #11–#22 are closed there, #23 and the residual risks are open. Cloudflare Worker (the reference backend, ADR 002) — unchanged: those gaps are still present there (§19.3 lists the three that take a different shape), as are public media and open realtime rooms, and its deployment still fails. Android — no change; APK workflow green on the branch. Rust format check red (advisory) on `main` and on the branch; clippy not observable. No deployment, schema change, secret change or destructive operation was performed.

**Current project health — update 2026-11-10, second Phase 1 turn (same branch, not merged):** Android — the in-app updater is repaired in source as 1.0.75 and CI is green (unit tests, signed release build, release verification), but **no update has been installed on a device in any recorded session**, so the feature stays `IMPLEMENTED_UNVERIFIED`; the published app is still 1.0.74, with the signer defect of §19.4 #24. Rust backend — build and tests green on `2b30f1d`; sign-in is limited per account and password hashes can be upgraded. Cloudflare Worker — unchanged, and now known to have no sign-in limit at all. Phase 1 is **not** complete, and by the owner's decision nothing is merged until it is. No deployment, release, schema change, secret change or destructive operation was performed.

**Current project health — update 2026-11-10, third Phase 1 turn (pull request #89):** Rust backend — build and all tests green on `bef2644` (94 integration and 26 unit tests by source count). Phase 1 is finished there as the owner defined it (§33): limits count against the real connection, CORS is closed by default, the admin is a stored role, other users' e-mail addresses are hidden, media opens by exact key through any place that mentions it. That server is deployed nowhere by this work, and the published app does not use it. Cloudflare Worker — not changed by this work, not read again in this turn, and outside it by the owner's decision: public media, open realtime rooms, the authorization gaps of §19.1 and §19.3 and no rate limiting were all there when it was last read, and that is the server the app's users are on. Android — 1.0.75, the updater repair, is what the merge of pull request #89 releases; it has still never been installed on a device in any recorded session. Rust format check still red (advisory). No deployment, schema change, secret change or destructive operation was performed.

**Current project health — 2026-11-11:** Android — 1.0.75 released from `main` (`45d793a`); never installed on a device in any recorded session. Cloudflare backend, the one the app uses — its code is now in this repository (`worker/`), unchanged; it type-checks and bundles. **None of Phase 1 exists in it:** media is public, realtime rooms are open, the authorization gaps of §19.1 and §19.3 stand, there is no rate limit, and whether its sign-in secret is a published value is an open question (§27). Cloudflare has taken no new build since 2026-11-03 and is not connected to this repository; both are the owner's to fix, and nobody has seen the build log. Rust backend — green, fixed, and used by nobody. No deployment, schema change, secret change or destructive operation was performed.

**Current project health — 2026-11-11, evening:** Cloudflare backend — up at its address. Its build is connected to this repository (owner's report), and the first build from here starts with the merge of pull request #91: **its result is not recorded here** (§27 says how to read it). Still nothing of Phase 1 in it, and its sign-in secret is treated as public until the owner confirms the replacement. Its address did not answer for some minutes that evening, while the Worker carried another name (§27). Android — 1.0.75, unchanged; the merge of pull request #91 publishes the same version once more (§24 #30). `server/` — untouched and unused.

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

**Media keys (added 2026-11-10, third turn):** a media key is text, exactly as an upload returned it (`media_attachments.file_path`). The file system reads `a//b` and `a/./b` as `a/b`; never serve a file under a spelling the database does not hold. A check on what may be *stored* has to find a key the same way the read rule finds it (`instr` on both sides), or one of the two can be stepped around.

**Limits and what is already stored (added 2026-11-10, third turn):** a new limit on input also refuses the request that undoes older data — a reaction longer than 32 characters can no longer be taken back, because taking back sends the same text. When adding a limit, look at the undo path. And do not put a limit on sign-in input that sign-up did not have: accounts made before it would be locked out.

**Rust server internals (added 2026-11-10, third turn):** `Db::with` locks one pooled connection for the length of its closure. Never call `state.db.with`, or anything that does (`access::can_read`, `all_users_exist`), inside another `with`: it can wait on itself. Collect what is needed, leave the closure, then make the next call. Integration tests of one file run as threads of one process: do not call `std::env::set_var` in a test body — another thread may be reading the environment. `tests/phase1_remainder.rs` configures each server through `app_with(|state| …)`, and writes the one variable it needs once, before the first server is built (`name_an_admin_address`).

**Tests first when the tests need new settings (added 2026-11-10, third turn):** a tests-only commit has to compile, or CI shows a build error instead of failing tests. First a commit that adds the fields and builder methods the tests name and changes no behaviour (`13b2be4`), then the tests (`6335e5d`), then the code. `cargo test` stops at the first failing test file, so new tests belong in the file that sorts last, or the files after it are not run in the red run.

**When the sandbox is re-created in the middle of a task (added 2026-11-10, third turn):** it happened twice. Afterwards the files are as they were left, but the local branch points at the commit the session branched from and `/tmp` is empty, so `git status` shows every earlier commit of the session as uncommitted change. Recover with `git fetch origin <branch>` and `git reset FETCH_HEAD` — a mixed reset; never `--hard` and never `checkout`, both destroy the uncommitted work — then compare `git status` with what was uncommitted before. Settings made in `.git/config` during the session, such as the branch's upstream, are gone as well. Keep nothing that matters only in `/tmp`.

**Which backend, and where its code is (added 2026-11-11):** before any backend work, establish two things and say them to the owner in one plain sentence each: which backend the app's users are on, and whether its code is in the repository being worked in. If it is not, stop there. On 2026-11-10 this file already said that the Worker was the deployed backend and that its code was elsewhere; the work went into the Rust backend anyway, because it could be tested here, and the question was put to the owner late and as a multiple choice whose consequence the owner did not see. Three turns of fixes reached no user.

**Words (added 2026-11-11):** “the Rust server”, “the Worker”, “Phase 1 in this repository” told the owner nothing — the owner asked whether Rust has a built-in server. Say what the app talks to, where that thing's code is, and whether a change gets to users. A report that has to be explained afterwards was the wrong report.

**A guide never prints a usable secret (added 2026-11-11):** the old dashboard guide offered a ready-made `JWT_SECRET` to paste, in a public repository. Whoever followed it has a public secret. Tell the owner how to make a value; never supply one — not in a file, not in a chat.

**Green in GitHub is not live on Cloudflare (added 2026-11-11):** code on `main` and code that runs are two things. For 23 commits the old repository's `main` moved while every Cloudflare build failed. Before saying that something reached users, look at the Cloudflare check on the commit, and ask the owner to confirm in the app.

**Say what an action breaks, not only what to keep (added 2026-11-11, evening):** the setup instructions said twice that the Worker keeps its name. They did not say “do not rename it — the app stops working”. The owner renamed it, and the address the app uses did not answer until the name was back. When the owner is about to work in a console, name the few actions that do damage — rename, delete, re-create — and say what each one breaks.

**A question the owner cannot answer is the agent's wording problem (added 2026-11-11, evening):** “was the ready-made `JWT_SECRET` used?” went unanswered several times and was then misunderstood twice. What worked: first “nothing is broken”, then one picture — a lock that works, whose key a public guide had printed — then three plain cases to choose from. Every longer explanation before that made it worse.

**Count the cost of advice before giving it (added 2026-11-11, evening):** “replace the secret, everyone signs in once more” was said several times before anyone looked whether a forgotten password can be recovered. It cannot. Look up what an action costs the users first, and say it in the same breath as the advice.

**A probe needs a new address each time (added 2026-11-11, evening):** the page-fetch tool reaches the live backend, which `curl` in the sandbox cannot. A second fetch of the same address gave the earlier answer after the state had changed, and for a moment that looked like a rename that had not been undone; a fetch with a new query string showed the change. Whether the tool or Cloudflare was behind was not settled. A new query string on every probe, and the doubt does not arise.

**Ask for the settings before explaining the failure (added 2026-11-11, evening):** the agent named a likely cause for the failed builds — a work branch as production branch — before the owner had read the settings out. The branch was `main`. What the owner reports is the only view of the dashboard there is: ask for it first, and write it down as reported, with the date. That evening only part of it was written down, and the rest could not be put into this file afterwards.

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

### 2026-11-10 — Phase 1, third turn: the rest of item 7, e-mail addresses, shared media (Rust); pull request #89

- **Task:** after the owner's answers (§33, third turn): finish the small remaining parts of Phase 1 in this repository — the rest of item 7, hide other users' e-mail addresses, fix §19.3 #23 — then merge the branch. `nchat-friends` is outside this work.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `76c9e40` on `arena/56647832-nchat-kotlin` (the `agent.md` commit of the second turn).
- **End commit:** `bef2644eaaabf6cfe337bae8e8481c9289689e80` — the last commit before this `agent.md` commit; it only removes a CI log. The last commit that changes code is `e0f3b5afbcb6a657c2c094848d586d813693ce57`. Pull request #89 to `main` was open with green checks when this entry was written; the merge follows this commit (`gh pr view 89`).
- **Files modified:** `server/src/{error,lib,main,models,ratelimit}.rs`; `server/src/routes/{mod,auth,admin,users,media,messages,groups,status,friends,calls,group_calls}.rs`; new `server/tests/phase1_remainder.rs` (25 tests); `agent.md`. The workflow committed `server/.ci/last-failure.log` twice (red runs); `bef2644` removed it. No Android file.
- **Database changes:** no schema change, no migration. `users.is_admin`, an existing column, is written at start-up when `NCHAT_ADMIN_EMAIL` is set.
- **API changes:** no route added, removed or renamed (72). New answers and removed fields: §8, third-turn bullet. New settings: `NCHAT_TRUSTED_PROXIES`, `NCHAT_AUTH_ADDRESS_RATE`, `NCHAT_SEND_RATE`, `NCHAT_CONTACT_RATE`, `NCHAT_CORS_ORIGINS`; `NCHAT_ADMIN_EMAIL` changed its meaning (§26, §27). All of it is deliberate drift from the Worker.
- **Tests executed:** GitHub Actions only — the Rust and the APK workflow on every push up to `bef2644`, and the Rust workflow once more for the pull request. Locally a tree-sitter syntax check of each changed Rust file, and reading (§36).
- **Test results:** red by design twice (`phase1_remainder`: 22 of 24 failed on `6335e5d`, 23 of 25 on `899879f`), green on the first build of the code: Rust runs `42903011159` (`e0f3b5a`), `42903567155` (`bef2644`), `42904397920` (pull request #89). APK workflow green on every push (`42854637927`, `42902301971`, `42903011165`, `42903567079`). By source count: 94 Rust integration tests, 26 Rust unit tests.
- **Known limitations:** the Rust server was never run outside CI, so the address rule, CORS and the media headers were not tried with a real proxy, browser or client. The published app uses the Worker, so none of this reaches its users. Six unit tests were never seen failing. The limits of each fix are in the last column of §19.5. The updater repair that the merge releases has never run on a device. Green CI logs cannot be read; the format step still fails (advisory).
- **Remaining work:** §19.5, “Not fixed”; the Worker, outside this work by the owner's decision; Phase 2 onward.
- **Next recommended task:** §33, “Next precise task (as of 2026-11-10, after the third turn)”.

### 2026-11-11 — Pull request #89 merged; the Cloudflare backend moves into this repository (pull request #90)

- **Task:** (1) merge the Phase 1 branch — done first thing, pull request #89. (2) After the owner's questions made clear that the app's backend is the Cloudflare one and that all work is to happen in this repository: “Ok then let's move the cloudflare here. Mention this into agent.md so that new agent perform this thing. And merge to main branch.”
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `45d793a8219f17f7a2e14bbb9f8a1d7bcc749632` (`main` after pull request #89), branch `arena/6151c100-nchat-kotlin`.
- **End commit:** `89f689da6ddc883bb15332d50f328cdb615091dd` — the move; the commit after it changes only `agent.md`. Pull request #90 was open when this entry was written and is merged after this commit (`gh pr view 90`).
- **Files modified:** new `worker/` (31 files: 30 copied unchanged from `nchat-friends@616a9e6`, and `worker/LICENSE`); `.gitignore` (three entries); `agent.md`. Nothing in `app/`, `server/` or `.github/`.
- **Database changes:** none. `worker/migrations/` came along as files; nothing was run against any database.
- **API changes:** none. No line of backend code was changed.
- **Tests executed:** in `worker/`, locally: `npm ci`, `npm run typecheck`, `npx wrangler deploy --dry-run`. A blob-id comparison of every moved file with its source. For pull request #90 the APK workflow, which is the only workflow that runs for it.
- **Test results:** type-check exit 0; dry run exit 0, 169.88 KiB; 30 of 30 files identical (§36).
- **Known limitations:** the move changes nothing for users. Cloudflare is still connected to `nchat-friends` and has failed every build since 2026-11-03; the reason is unknown. `worker/` has no CI and no tests. None of Phase 1 exists in `worker/`. Whether the live sign-in secret is the published one is unanswered. The merge of pull request #90 builds Android 1.0.75 once more and replaces the release's APK file with it (§24 #30).
- **Remaining work:** everything in §33, “Next precise task (2026-11-11)”; the owner's steps in §27; the four questions for the owner in §33.
- **Next recommended task:** §0 rule 5 — Phase 1 in `worker/`, starting with a way to test it.

### 2026-11-11, evening — Cloudflare connected to this repository; the rename; first deployment from here (pull request #91)

- **Task:** the owner's requests, in order: explain `server/` against `worker/` and what changes for the app; give the Cloudflare setup step by step with a reason for each step; then, with the steps done, “Go” for the first change.
- **Agent:** Arena.ai Agent Mode.
- **Start commit:** `420fa1bb69ed4b6401a75daae0c1b4e5e55777c5` (`main` after pull request #90), branch `arena/7cfa350c-nchat-kotlin`. The explanations and the owner's work in the dashboard happened before this branch existed, while the previous branch was merged and could only be read.
- **End commit:** `cc2bb619a748c38f6fa9b98623629c5cedec19c9` — `worker/README.md` (added in `7bee103d33fe`, four sentences corrected in this one); the commit after it changes only `agent.md`. Pull request #91 was open when this entry was written and is merged after this commit (`gh pr view 91`).
- **Files modified:** new `worker/README.md`; `agent.md`. No code.
- **Database changes:** none.
- **API changes:** none in the source. If the Cloudflare build that the merge starts succeeds, the live backend becomes what `main` has held since pull request #90 — as far as is known, the running build plus `GET /users/search`.
- **Changes on Cloudflare, made by the owner:** the Worker's build connected to this repository; watch path `worker/*`; builds for other branches asked to be off; the Worker renamed and renamed back (§27).
- **Tests executed:** in `worker/`: `npm ci`, `npm run typecheck`, `npx wrangler deploy --dry-run`. Probes of the live address with the page-fetch tool. The APK workflow on the branch.
- **Test results:** exit 0 three times, 169.88 KiB; the live backend answered before the merge; APK `build` green on `7bee103` (§36).
- **Known limitations:** the result of the first Cloudflare build is not in this file. No agent saw the dashboard or a build log. `JWT_SECRET` is treated as public and its replacement is not confirmed. `worker/` has no tests and no CI, while every merge under it now deploys. Why the old repository's builds failed is still unknown.
- **Remaining work:** §33, “First steps for the next session (2026-11-11, evening)”, then the Phase 1 task.
- **Next recommended task:** read the build result; ask about `JWT_SECRET`; then a way to test `worker/`.

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
