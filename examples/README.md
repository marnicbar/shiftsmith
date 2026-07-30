# Deploying ShiftSmith

Running a released ShiftSmith image in production. The compose files in the
repository root build from source and are for development; these pull a
published image and only need configuration.

| File | What it runs |
| --- | --- |
| [`docker-compose.yml`](docker-compose.yml) | The app plus a PostgreSQL container. Start here. |
| [`docker-compose.external-db.yml`](docker-compose.external-db.yml) | The app only, against a PostgreSQL you already operate. |
| [`.env.example`](.env.example) | Every variable both files read. |

## Quick start

```bash
curl -O https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/.env.example
$EDITOR .env            # set the two passwords and your timezone
docker compose up -d
```

The app is up once `docker compose ps` reports it healthy. Open
<http://localhost:8080> and sign in with the admin credentials from your `.env`.

One image serves the React UI and the `/api` backend on **port 8080**. The
database is not part of it: bring **PostgreSQL 14 or newer**. Every `v*` tag
publishes to `ghcr.io/marnicbar/shiftsmith` for `linux/amd64` and `linux/arm64`.

| Tag | Moves? |
| --- | --- |
| `0.1.0` | No — an exact release. Use this. |
| `0.1` | On every patch release of that line. |
| `latest` | On every release. Fine for a trial, risky for a server. |
| `@sha256:…` | No — the strictest pin. |

## Configuration

All environment variables; there is no config file to mount.

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://db:5432/shiftsmith` | Where PostgreSQL is. Append `?sslmode=require` for a remote server. |
| `POSTGRES_USER` | `shiftsmith` | Database user. |
| `POSTGRES_PASSWORD` | _(none)_ | **Required.** The app refuses to start without it rather than falling back to a default. |
| `SHIFTSMITH_ADMIN_USERNAME` | `admin` | Username of the account seeded on a fresh database. |
| `SHIFTSMITH_ADMIN_PASSWORD` | _(none)_ | Its initial password. **Set this before the first boot.** |
| `TZ` | `UTC` | Your business timezone. |

Anything else is a standard Quarkus property in environment form. Two that
occasionally matter:

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUARKUS_TIMEFOLD_SOLVER_TERMINATION_UNIMPROVED_SPENT_LIMIT` | `8s` | How long the solver keeps going after it last improved the schedule. |
| `SHIFTSMITH_TYPST_BIN` | `typst` | Path to the Typst binary used for PDF export. Installed in the image. |

Leave the container's port alone (`QUARKUS_HTTP_PORT`) — the healthcheck targets
8080. Publish a different host port with `SHIFTSMITH_PORT` instead.

**The first admin account.** On a fresh database ShiftSmith seeds exactly one.
Without `SHIFTSMITH_ADMIN_PASSWORD` it falls back to `admin` / `shiftsmith` but
is flagged for rotation — every protected endpoint answers `403` and the UI
forces a password change at first sign-in, so the default can't be used to
operate the app. Both variables only apply when the account is created; rotate
the password later under **Account → Sign-in**.

**Timezone.** The schedule model is zone-less, so `TZ` decides when "today" and
the solve horizon roll over. It defaults to UTC rather than the host's zone, so
set it even if the host is already right.

## Reverse proxy and TLS

The app speaks plain HTTP and the SPA keeps its session token in `localStorage`,
so put a TLS-terminating proxy in front of anything but a trusted LAN — hence the
`127.0.0.1` binding in the compose files. With Caddy that is:

```caddyfile
shifts.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Two things to check in any other proxy:

- **Don't buffer `/api/stream`.** It is Server-Sent Events; buffering makes the
  UI look frozen. In nginx, `proxy_buffering off;` and a long
  `proxy_read_timeout` for that path.
- **Serve the whole origin**, not a sub-path. The SPA is built for the root and
  has no client-side router.

## Backups

Everything lives in PostgreSQL — roster, solved schedule, accounts and the
session-signing secret. The app container is disposable.

```bash
docker compose exec -T db pg_dump -U shiftsmith shiftsmith | gzip > shiftsmith-$(date +%F).sql.gz
gunzip -c shiftsmith-2026-07-30.sql.gz | docker compose exec -T db psql -U shiftsmith shiftsmith
```

The files also sit in `POSTGRES_DATA_DIR`, which you can snapshot — but only with
the container stopped. `pg_dump` is the safer routine. Restoring a dump brings
the signing secret back too, so sessions issued before it stay valid.

## Upgrading

```bash
$EDITOR .env                 # SHIFTSMITH_VERSION=0.2.0
docker compose pull
docker compose up -d
```

Flyway migrates the schema at startup, so a new version brings the database along
with it. Migrations are forward-only: rolling the tag back does not roll the
schema back, and an older app is not guaranteed to run against a newer schema.
Take a dump first — restoring it is the rollback path.

Restarts are otherwise cheap: the last solved roster is persisted, so the app
comes back with the schedule on screen and re-solves from there.

## Sizing

The solver keeps improving the schedule until it stops finding anything better,
then pauses. `mem_limit` and `cpus` stop it starving PostgreSQL or the host, and
the JVM sizes its heap from `mem_limit`. The 1 GB / 2 CPU defaults fit a few
dozen people over a two-week horizon; raise them for more, or lower
`QUARKUS_TIMEFOLD_SOLVER_TERMINATION_UNIMPROVED_SPENT_LIMIT` if you would rather
the solver settle sooner than search harder.

## Health and troubleshooting

The image's `HEALTHCHECK` polls the UI root, and the app only finishes booting
once PostgreSQL is reachable — so a healthy container means the whole stack is
up, and `GET /` returning 200 is your readiness probe. `docker compose logs -f app`
shows the migrations and the "Loaded problem from database" line on each boot.

| Symptom | Cause |
| --- | --- |
| Startup fails with `no password was provided` | `POSTGRES_PASSWORD` is unset. |
| Every API call answers `403` and the UI insists on a password change | `SHIFTSMITH_ADMIN_PASSWORD` was not set on the first boot. Change the password in the UI and it unlocks. |
| Schedule rolls over at the wrong hour | `TZ` is not your business timezone. |
| PDF export answers `503` | The Typst binary is missing — check `SHIFTSMITH_TYPST_BIN`. |
| `/q/swagger-ui` and `/q/openapi` answer `404` | Intentional: dev-mode aids, not packaged into the release. |
| The UI shows a "reconnecting" banner | A proxy is buffering or timing out `/api/stream`. |
