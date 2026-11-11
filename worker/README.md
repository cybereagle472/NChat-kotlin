# NChat backend (Cloudflare)

This folder is the backend the NChat Android app talks to. It runs on the owner's Cloudflare
account as the Worker `nchat-friends`, with the D1 database `nchat-db`, the R2 bucket
`nchat-media` and the Durable Object `RealtimeRoom` (live events).

`agent.md` in the repository root is the project's memory. Its §0 and §27 hold the rules and
the history for this folder. This file is the short version.

## How a change goes live

1. A change to a file in this folder is merged to `main`.
2. Cloudflare (Workers Builds, connected to this repository since 2026-11-11) installs the
   packages and runs `npx wrangler deploy` in this folder.
3. The result shows on the commit on GitHub as the check `Workers Builds: nchat-friends`.
   - Success: the new version is live for every user at once. No app update is needed.
   - Failure: nothing changed, and the version before keeps running.

So a merge to `main` that touches this folder is a deployment.

The build settings, on Cloudflare under the Worker `nchat-friends` → Settings → Builds, as they
have to be:

| Setting | Value |
|---|---|
| Git repository | `cybereagle472/NChat-kotlin` |
| Git branch | `main` |
| Root directory | `worker` |
| Build command | empty |
| Deploy command | `npx wrangler deploy` |
| Build watch paths, include | `worker/*` |
| Builds for other branches (preview builds) | off |

The owner set them on 2026-11-11. No agent can read the dashboard, so if a build behaves in a
way these values do not explain, ask the owner to report them again.

## Never change

- **The Worker's name, `nchat-friends`**, in `wrangler.toml` or in the Cloudflare dashboard.
  The name is the first part of the address, `https://nchat-friends.usa66486.workers.dev`.
  That address is built into every installed app, and every photo, voice note and profile
  picture is stored with it. When the Worker was renamed in the dashboard on 2026-11-11, that
  address stopped answering until the name was set back. The name says nothing about which
  repository the code is in.
- **`PUBLIC_API_URL`** in `wrangler.toml`. The backend puts it in front of the link of every
  uploaded file, so it has to be the address above.
- The database and bucket names and ids, and the Durable Object block, in `wrangler.toml`.

## Secrets

They are kept on Cloudflare only, in the Worker's settings, and never in this repository, in a
chat or in a guide: `JWT_SECRET` (required; it signs every
sign-in), `CF_TURN_KEY_ID` and `CF_TURN_KEY_API_TOKEN` (optional; they make calls connect
more reliably). A deploy keeps them.

## Database

A deploy does not touch the database. The schema is the SQL files in `migrations/`. A new one
is applied separately, by the owner (`agent.md` §27).

## Is it up?

`https://nchat-friends.usa66486.workers.dev/health` answers `{"ok":true}`.

## Going back

On Cloudflare: the Worker → Deployments → roll back to the version before. Then revert the
change on `main` as well, or the next build puts it back.

## What an agent can run without a Cloudflare account

From this folder: `npm ci`, `npm run typecheck`, and
`npx wrangler deploy --dry-run --outdir <dir>`, which bundles and stops. There are no tests yet.
