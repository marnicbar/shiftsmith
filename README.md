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

### Production (published image + your database)
ShiftSmith ships as **one image** that serves the UI and the API together on
port 8080. Bring your own PostgreSQL **14 or newer** (the minimum supported by
the Hibernate ORM version Quarkus ships) — the database is not part of the image.

Every `v*` git tag publishes a multi-arch image (`linux/amd64` + `linux/arm64`)
to `ghcr.io/marnicbar/shiftsmith`, so a deployment is a pull, not a build:

```bash
curl -O https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/.env.example
$EDITOR .env            # set the two passwords and your timezone
docker compose up -d
```

Then open **http://localhost:8080** and sign in with the admin credentials from
your `.env`.

**[`examples/`](examples/) is the deployment guide**: a compose file for the app
plus a PostgreSQL container, another for a database you already operate, and a
README covering configuration, the reverse proxy and TLS, backups, upgrades,
sizing and troubleshooting.

Two things worth knowing before the first boot:

- **Set `SHIFTSMITH_ADMIN_PASSWORD`.** On a fresh database ShiftSmith seeds one
  admin account. Without this variable it falls back to a well-known default but
  is flagged for rotation — every protected endpoint answers `403` and the UI
  forces a password change at first sign-in, so the default can never be used to
  operate the app. Rotate it later under **Account → Sign-in**.
- **Set `TZ` to your business timezone.** The schedule model is zone-less, so
  `TZ` decides when "today" and the solve horizon roll over. It defaults to UTC
  rather than the host's zone.

(The OpenAPI document and Swagger UI are developer aids and are not packaged into
the release image; `mvn quarkus:dev` serves Swagger UI at `/q/swagger-ui`.)

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

The root `docker-compose.yml` builds the all-in-one image from your working tree
and runs it against a PostgreSQL container — the packaged app, but from source:
```bash
docker compose up -d --build       # :8080, image tagged shiftsmith:local
```
Both root compose files are development stacks. To deploy a release, use
[`examples/`](examples/).

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | React + Vite |
| Backend | Quarkus 3.38 (Java 21) |
| Solver | Timefold Solver 2.3 |
| Database | PostgreSQL 14+ |

## License

MIT — see [LICENSE](LICENSE).
