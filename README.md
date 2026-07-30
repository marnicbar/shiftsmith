# ShiftSmith

An open-source constraints-based employee scheduling app powered by the [Timefold Solver](https://timefold.ai).
Define people and positions, populate their calendars and working-time rules, and let the solver
assign shifts automatically — respecting skills, availability, hour limits and fair distribution.

> ℹ️ Note:
>
> This project is written entirely by AI coding agents (e.g. Claude Code) as an experiment in agentic development.
> Despite that, it's actively maintained and used in production, so if you find a bug or have a feature request, please open an issue.

## Highlights

- **Rich personnel model** — per-person skills, preferred/undesired/vacation calendar
  blocks (with recurrence), and working-time rules (daily/weekly/monthly hours,
  consecutive days, rest between shifts) that can change on a future date.
- **Positions & recurring shifts** — group positions, define recurring shift templates
  with headcount and preferred employees.
- **Continuous solving** — the solver runs over a configurable horizon and pauses
  automatically once the schedule is steady; any edit re-solves from the new state.
- **Manual overrides** — pin specific people to specific slots; the solver works around them.
- **Configurable horizon** — solve N days, weeks or months ahead, counted from the start
  of the next full period (one week = this week and the next).
- **Settings page** — appearance (theme/palette/accent/font), calendar behaviour and the
  solver window.

## Constraints

| Type | Rule |
|------|------|
| Hard | Employee must have every required skill for the shift |
| Hard | No overlapping shifts for one person |
| Hard | Minimum rest between shifts (per `restHours` rule) |
| Hard | Daily / weekly / monthly hour limits (`min` / `max`, time-varying) |
| Hard | Maximum consecutive working days |
| Hard | No work on vacation days |
| Medium | Staff as many shift slots as possible (coverage) |
| Soft | Prefer the shift's preferred employees |
| Soft | Honour preferred / undesired time blocks |
| Soft | Hit preferred weekly hours |
| Soft | Balance workload across the team |

## Running

### Production (single image + your database)
ShiftSmith ships as **one image** that serves the UI and the API together on
port 8080. Bring your own PostgreSQL **14 or newer** (the minimum supported by
the Hibernate ORM version Quarkus ships) — the database is not part of the image.

```bash
docker compose up -d --build
```
Open **http://localhost:8080** (Swagger UI at `/q/swagger-ui`). The bundled
`docker-compose.yml` runs the app plus a PostgreSQL container; override
`POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` (and the image tag) via a
`.env` file before going to production.

To run a published image instead of building locally, drop the `build:` block in
`docker-compose.yml` and keep the `image:` line (tagged builds are pushed to
`ghcr.io/marnicbar/shiftsmith` on every `v*` git tag). Point the app at any
PostgreSQL 14+ with the standard `QUARKUS_DATASOURCE_JDBC_URL`, `POSTGRES_USER`
and `POSTGRES_PASSWORD` environment variables.

#### Admin credentials
On a **fresh database** ShiftSmith seeds a single admin account. Set the initial
password at deploy time so the instance is never reachable on a known credential:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SHIFTSMITH_ADMIN_USERNAME` | `admin` | Username of the seeded account. |
| `SHIFTSMITH_ADMIN_PASSWORD` | _(none)_ | Initial password. **Set this in production.** |

- If `SHIFTSMITH_ADMIN_PASSWORD` is set, that password is used and the account is
  ready immediately.
- If it is **not** set, the account falls back to the well-known default
  (`admin` / `shiftsmith`) but is flagged for rotation: every protected endpoint
  returns `403` and the UI forces a password change on first sign-in, so the
  default password can never be used to operate the app.

These only take effect when the account is first created. To rotate the password
later, use **Account → Sign-in** in the app (or `POST /api/auth/change-password`).

### Development
Hot-reload stack (separate Vite dev server + backend + db) in Docker:
```bash
docker compose -f docker-compose.dev.yml up --build
```
Open **http://localhost:5173**; the Vite dev server proxies `/api/*` to the
backend on :8080.

Or run the toolchains directly (requires Java 21 + Maven and Node 22.13+; CI and
the Docker images use Node 24):
```bash
cd backend && mvn quarkus:dev      # :8080
cd frontend && npm install && npm run dev   # :5173 (proxies /api → :8080)
```

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | React + Vite |
| Backend | Quarkus 3.38 (Java 21) |
| Solver | Timefold Solver 2.3 |
| Database | PostgreSQL 14+ |

## License

Apache 2.0
