# ShiftSmith

An open-source employee scheduling app powered by constraint programming. Define
your people and positions, set their calendars and working-time rules, and let the
[Timefold Solver](https://timefold.ai) assign everyone automatically — respecting
skills, availability, hour limits and fair distribution.

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

### Docker Compose
```bash
docker compose up --build
```
Open **http://localhost:5173**. The backend API runs on :8080 (Swagger UI at
`/q/swagger-ui`); the Vite dev server proxies `/api/*` to it.

### Local development
Requires Java 21 + Maven and Node 20+.
```bash
cd backend && mvn quarkus:dev      # :8080
cd frontend && npm install && npm run dev   # :5173 (proxies /api → :8080)
```

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | React + Vite |
| Backend | Quarkus 3.35 (Java 21) |
| Solver | Timefold Solver 2.1 |

## License

Apache 2.0
