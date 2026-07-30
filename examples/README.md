# Deploying ShiftSmith

One image serves the UI and the `/api` backend on port 8080. Bring **PostgreSQL
14+**; every `v*` tag publishes to `ghcr.io/marnicbar/shiftsmith` for
`linux/amd64` and `linux/arm64`.

- [`docker-compose.yml`](docker-compose.yml) — the app plus a PostgreSQL container. Start here.
- [`docker-compose.external-db.yml`](docker-compose.external-db.yml) — the app only, against a PostgreSQL you already run.

```bash
curl -O https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/docker-compose.yml
$EDITOR docker-compose.yml    # fill in the <placeholders>
docker compose up -d
```

Open <http://localhost:8080> once `docker compose ps` reports it healthy.

Pin an exact tag (`0.1.0`, or a `@sha256:…` digest). `0.1` and `latest` move
under you on the next release.

## Configuration

All environment variables; there is no config file to mount.

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://db:5432/shiftsmith` | Where PostgreSQL is. Append `?sslmode=require` for a remote server. |
| `POSTGRES_USER` | `shiftsmith` | Database user. |
| `POSTGRES_PASSWORD` | _(none)_ | **Required** — no fallback, the app refuses to start without it. |
| `SHIFTSMITH_ADMIN_USERNAME` | `admin` | Account seeded on a fresh database. |
| `SHIFTSMITH_ADMIN_PASSWORD` | _(none)_ | Its initial password. Without it the account is locked to a forced password change, so set it before the first boot. Both apply only at creation; rotate later under **Account → Sign-in**. |
| `TZ` | `UTC` | Your business timezone. The schedule model is zone-less, so this decides when "today" and the solve horizon roll over. |

Anything else is a standard Quarkus property in environment form —
`QUARKUS_TIMEFOLD_SOLVER_TERMINATION_UNIMPROVED_SPENT_LIMIT` (`8s`, how long the
solver keeps going after its last improvement) and `SHIFTSMITH_TYPST_BIN`
(`typst`, the PDF export binary) are the ones that occasionally matter. Leave
`QUARKUS_HTTP_PORT` alone — the healthcheck targets 8080; change the host side of
the `ports:` mapping instead.

## Reverse proxy

The app speaks plain HTTP and the SPA keeps its session token in `localStorage`,
so terminate TLS in front of it — hence the `127.0.0.1` binding. With Caddy:

```caddyfile
shifts.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Serve it at the origin root (the SPA has no client-side router), and don't buffer
`/api/stream` — it's Server-Sent Events, and buffering makes the UI look frozen
(in nginx: `proxy_buffering off;` plus a long `proxy_read_timeout`).

## Backups

Everything is in PostgreSQL — roster, schedule, accounts, the session-signing
secret. The app container is disposable.

```bash
docker compose exec -T db pg_dump -U shiftsmith shiftsmith | gzip > shiftsmith-$(date +%F).sql.gz
gunzip -c shiftsmith-2026-07-30.sql.gz | docker compose exec -T db psql -U shiftsmith shiftsmith
```

`./data/postgres` can be snapshotted instead, but only with the container
stopped.

## Upgrading

```bash
$EDITOR docker-compose.yml   # image: ghcr.io/marnicbar/shiftsmith:0.2.0
docker compose pull
docker compose up -d
```

Flyway migrates the schema at startup. Migrations are forward-only, so rolling
the tag back doesn't roll the schema back — dump first, that's the rollback path.
Restarts are otherwise cheap: the last solved roster is persisted and comes back
on screen.

## Troubleshooting

A healthy container means the whole stack is up: the healthcheck polls `GET /`,
which only answers 200 once PostgreSQL is connected. Use it as your readiness
probe.

| Symptom | Cause |
| --- | --- |
| Startup fails with `no password was provided` | `POSTGRES_PASSWORD` is unset. |
| Every API call answers `403`, the UI insists on a password change | `SHIFTSMITH_ADMIN_PASSWORD` was unset on the first boot. Change the password in the UI and it unlocks. |
| Schedule rolls over at the wrong hour | `TZ` is not your business timezone. |
| PDF export answers `503` | The Typst binary is missing — check `SHIFTSMITH_TYPST_BIN`. |
| `/q/swagger-ui` and `/q/openapi` answer `404` | Intentional: dev-mode aids, not packaged into the release. |
| The UI shows a "reconnecting" banner | A proxy is buffering or timing out `/api/stream`. |

The solver burns CPU between edits until it stops finding improvements, so
`mem_limit` / `cpus` keep it from starving PostgreSQL; the JVM sizes its heap
from `mem_limit`. 1 GB / 2 CPUs fits a few dozen people over a two-week horizon.
