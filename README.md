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
ShiftSmith ships as **one image** serving the UI and the API on port 8080; bring
your own PostgreSQL **14+**. Every `v*` tag publishes to
`ghcr.io/marnicbar/shiftsmith` for `linux/amd64` and `linux/arm64`, so deploying
is a pull, not a build:

```bash
curl -O https://raw.githubusercontent.com/marnicbar/shiftsmith/main/examples/docker-compose.yml
$EDITOR docker-compose.yml    # fill in the <placeholders>
docker compose up -d
```

Open **http://localhost:8080** and sign in with the admin credentials you just
set. **[`examples/`](examples/)** has both compose files and the deployment
guide: configuration, TLS, backups, upgrades, troubleshooting.

### Development
Hot-reload stack (separate Vite dev server + backend + db) in Docker:
```bash
docker compose -f docker-compose.dev.yml up --build
```
Open **http://localhost:5173**; the Vite dev server proxies `/api/*` to the
backend on :8080. Swagger UI is served at `/q/swagger-ui` in dev mode only — it
is not packaged into the release image.

Or run the toolchains directly (requires Java 21 + Maven and Node 22.13+; CI and
the Docker images use Node 24):
```bash
cd backend && mvn quarkus:dev      # :8080
cd frontend && npm install && npm run dev   # :5173 (proxies /api → :8080)
```

To check a change the way it will ship, the root `docker-compose.yml` builds the
all-in-one image from your working tree:
```bash
docker compose up -d --build       # :8080, tagged shiftsmith:local
```
Both root compose files are development stacks — deploy from
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
