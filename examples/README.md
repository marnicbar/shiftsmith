# Deploying ShiftSmith

Ready-to-run examples for running a **released** ShiftSmith image in production.
The compose files in the repository root are for development — they build from
source; these pull a published image and only need configuration.

| File | What it runs |
| --- | --- |
| [`docker-compose.yml`](docker-compose.yml) | The app plus a PostgreSQL container. The usual starting point. |
| [`docker-compose.external-db.yml`](docker-compose.external-db.yml) | The app only, against a PostgreSQL you already operate. |
| [`.env.example`](.env.example) | Every variable both files read, with comments. |

## Quick start

```bash
curl -O https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/.env.example
$EDITOR .env            # set the two passwords and your timezone
docker compose up -d
```

The app is up when `docker compose ps` reports it healthy (the image's own
healthcheck polls the UI root, which only answers 200 once the database
connection is live). Open <http://localhost:8080> and sign in with
`SHIFTSMITH_ADMIN_USERNAME` / `SHIFTSMITH_ADMIN_PASSWORD`.

## What you are deploying

One image serves the React UI and the `/api` backend on **port 8080** — same
origin, no reverse proxy needed between them, one process. The database is
deliberately *not* part of the image: bring **PostgreSQL 14 or newer** (the floor
supported by the Hibernate ORM version Quarkus ships).

Published on every `v*` git tag to `ghcr.io/marnicbar/shiftsmith` as a multi-arch
manifest, so the same tag runs on **linux/amd64** and **linux/arm64**:

| Tag | Moves? |
| --- | --- |
| `0.1.0` | No — an exact release. **Use this.** |
| `0.1` | Yes, on every patch release of that minor line. |
| `latest` | Yes, on every release. Convenient for a trial, risky for a server. |
| `@sha256:…` | No — the strictest pin; `docker inspect` reports the digest you are running. |

## Configuration

Everything is environment variables; there is no config file to mount.

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://db:5432/shiftsmith` | Where PostgreSQL is. Append `?sslmode=require` for a remote server. |
| `POSTGRES_USER` | `shiftsmith` | Database user. |
| `POSTGRES_PASSWORD` | _(none)_ | Database password. **Required** — the app fails fast at startup without it rather than falling back to a well-known default. |
| `SHIFTSMITH_ADMIN_USERNAME` | `admin` | Username of the account seeded on a fresh database. |
| `SHIFTSMITH_ADMIN_PASSWORD` | _(none)_ | Its initial password. **Set this before the first boot** (see below). |
| `TZ` | `UTC` | The app's business timezone (see below). |

Anything else is a standard Quarkus property in environment form. Two that
occasionally matter in production:

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUARKUS_TIMEFOLD_SOLVER_TERMINATION_UNIMPROVED_SPENT_LIMIT` | `8s` | How long the solver keeps trying after it last improved the schedule. Lower it to spend less CPU, raise it for large rosters. |
| `SHIFTSMITH_TYPST_BIN` | `typst` | Path to the Typst binary used for PDF export; it is installed in the image, so you normally leave this alone. |

Don't change the container's own port (`QUARKUS_HTTP_PORT`) — the image's
healthcheck targets 8080. Publish a different **host** port instead:
`SHIFTSMITH_PORT=9000` in `.env`.

### The first admin account

On a **fresh database** ShiftSmith seeds exactly one admin account:

- With `SHIFTSMITH_ADMIN_PASSWORD` set, that password is used and the account
  works immediately.
- Without it, the account falls back to the well-known default
  (`admin` / `shiftsmith`) but is flagged for rotation: every protected endpoint
  answers `403` and the UI forces a password change at first sign-in. The
  instance is never actually usable on the default credential.

Both variables only take effect when the account is created. Rotate the password
later in the app under **Account → Sign-in** (or `POST /api/auth/change-password`);
changing the environment variable afterwards does nothing.

### Timezone

The schedule model is zone-less: "today", the solve-horizon roll-over and shift
wall-clock times all use the app's default timezone. Set `TZ` to your business
timezone (e.g. `Europe/Berlin`) so the horizon rolls at local midnight. It
defaults to UTC rather than inheriting the host's zone, so this is worth setting
explicitly even if the host is already correct.

## Reverse proxy and TLS

The app speaks plain HTTP and hands the browser a bearer token that the SPA keeps
in `localStorage`, so put it behind a TLS-terminating proxy on anything but a
trusted LAN. The compose files bind the published port to `127.0.0.1` for exactly
this reason.

Caddy, with automatic certificates, is about as small as this gets:

```caddyfile
shifts.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Two things to check in any other proxy:

- **Don't buffer `/api/stream`.** It is a Server-Sent Events endpoint that pushes
  live solver updates; a buffering proxy makes the UI look frozen. In nginx:
  `proxy_buffering off;` and `proxy_read_timeout 1h;` for that path.
- **Serve the whole origin**, not a sub-path. The SPA is built for the root path,
  and there is no client-side router to rewrite.

## Backups

Everything that matters lives in PostgreSQL — the roster, the solved schedule,
user accounts and the server's session-signing secret. The app container itself
is disposable.

```bash
# Bundled database (docker-compose.yml)
docker compose exec -T db pg_dump -U shiftsmith shiftsmith | gzip > shiftsmith-$(date +%F).sql.gz

# Restore into an empty database
gunzip -c shiftsmith-2026-07-30.sql.gz | docker compose exec -T db psql -U shiftsmith shiftsmith
```

With the bundled database the files also sit in `POSTGRES_DATA_DIR`
(`./data/postgres` by default) — fine to snapshot at the filesystem level, but
only with the container stopped. A logical `pg_dump` is the safer routine.

Restoring a dump restores the signing secret too, so sessions issued before the
backup stay valid.

## Upgrading

```bash
$EDITOR .env                 # SHIFTSMITH_VERSION=0.2.0
docker compose pull
docker compose up -d
```

The app runs its Flyway schema migrations at startup, so a new version brings the
database along with it. Migrations are **forward-only**: rolling the image tag
back does not roll the schema back, and an older app is not guaranteed to run
against a newer schema. Take a dump before upgrading — restoring it is the
rollback path.

Restarts are otherwise cheap: the last solved roster is persisted, so the app
comes back with the schedule already on screen and re-solves from there.

## Resources and sizing

The solver deliberately uses CPU between edits: it keeps improving the schedule
until it stops finding better solutions, then pauses on its own. `mem_limit` and
`cpus` in the compose file keep it from starving PostgreSQL or the rest of the
host; the JVM is container-aware and sizes its heap from `mem_limit`. The 1 GB /
2 CPU defaults comfortably fit a few dozen people over a two-week horizon — raise
them for larger rosters or a longer horizon, and lower
`QUARKUS_TIMEFOLD_SOLVER_TERMINATION_UNIMPROVED_SPENT_LIMIT` if you would rather
the solver settle sooner than search harder.

## Health and troubleshooting

The image ships a `HEALTHCHECK` that polls the UI root; because the app only
finishes booting once PostgreSQL is reachable, a healthy container means the
whole stack is up. Use it for monitoring: `GET /` returning 200 is the readiness
signal. `docker compose logs -f app` shows the Flyway migrations, the Timefold
banner and the "Loaded problem from database" line on each boot.

| Symptom | Cause |
| --- | --- |
| App exits at startup with `no password was provided` | `POSTGRES_PASSWORD` is unset. There is no fallback by design, so it can never silently connect with a well-known default. |
| Every API call answers `403` and the UI insists on a password change | `SHIFTSMITH_ADMIN_PASSWORD` was not set on the first boot. Change the password in the UI; the instance unlocks. |
| Schedule rolls over at the wrong hour | `TZ` is not your business timezone. |
| PDF export answers `503` | Only happens if the Typst binary is missing; it is installed in the published image, so check `SHIFTSMITH_TYPST_BIN` has not been overridden. |
| `/q/swagger-ui` and `/q/openapi` answer `404` | Intentional: the API docs are dev-mode aids and are not packaged into the release image. |
| The UI freezes with a "reconnecting" banner | A proxy is buffering or timing out `/api/stream`. |
