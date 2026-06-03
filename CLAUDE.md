# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Commands

### Backend (Quarkus + Timefold)
```bash
cd backend
mvn quarkus:dev          # dev mode, live reload on :8080
mvn package -DskipTests  # build (also validates the Timefold model)
mvn test                 # unit + constraint + solver tests (no Docker needed)
mvn verify               # also runs *IT integration tests if Docker is present
java -jar target/quarkus-app/quarkus-run.jar
```

### Frontend (React + Vite)
```bash
cd frontend
npm install
npm run dev    # dev server on :5173, proxies /api/* → :8080
npm run build  # production build to dist/
npm test       # Vitest unit + component tests
```

### Full stack
```bash
docker compose up --build   # postgres :5432, backend :8080, frontend :5173
```

### Testing
See `TESTING.md` for the full strategy. Backend: JUnit 5 + AssertJ for domain/expansion
units, Timefold `ConstraintVerifier` for per-constraint tests, the real solver for
end-to-end scenario tests, and a Docker-gated `@QuarkusTest` (`*IT`) for the REST round-trip.
Frontend: Vitest + React Testing Library (jsdom) for pure logic, the `api.js` client, and
component smoke tests. When you change a constraint, add/adjust a `ConstraintVerifier` case;
when you change recurrence logic, keep `Recurrence` (backend) and `matchesDay` (frontend) —
and their tests — in lock-step.

## Architecture

React SPA → Vite/nginx proxy → Quarkus REST → Timefold Solver, with PostgreSQL
for persistence. `ScheduleService` keeps the working problem in memory (the
solver needs it there) but **persists it to Postgres as a single JSONB
document** so it survives restarts. On boot it rehydrates from the DB; an empty
database starts with an empty problem (no demo data).

### Persistence (`persistence` package)
The whole editable problem (employees, positions, settings, overrides) is stored
as one JSONB row via `ProblemEntity` (`@JdbcTypeCode(SqlTypes.JSON)`) behind
`ProblemStore`. It's a document, not normalised tables: the model is deeply
nested, synced atomically, and several domain classes carry Timefold annotations
that don't mix with JPA. `ProblemDocument` is the serialized shape.
- Dev (`mvn quarkus:dev`): Quarkus Dev Services auto-starts a throwaway Postgres
  (needs Docker). Prod/compose: connects to the `db` service via
  `QUARKUS_DATASOURCE_*` env (see `application.properties` `%prod` keys).

### Backend is the source of truth
The frontend owns the editor UI but the backend holds the canonical problem
(employees, positions, settings, manual overrides) and the solver. The frontend:
1. loads everything from `GET /api/schedule` on startup,
2. debounce-syncs the whole problem to `PUT /api/problem` on every edit (which
   persists to the DB and re-solves),
3. subscribes to `GET /api/stream` (SSE) for live updates while the solver runs.

### Live updates (SSE)
`GET /api/stream` is a Server-Sent Events endpoint. `ScheduleBroadcaster` fans
out a lightweight "changed" tick whenever the solver finds a better solution, a
problem edit lands, or the solver starts/stops; each subscriber rebuilds a fresh
`ScheduleDTO` snapshot **off the solver thread** (`emitOn`) so the solver is
never blocked. The browser's `EventSource` (`api.subscribeSchedule`) auto-
reconnects, and a 25s heartbeat keeps the connection alive through proxies. This
replaces the old `GET /api/schedule` polling loop and also propagates one
client's edits to others live.

### Domain model
- **Employee** — `skills`, calendar `blocks`, and working-time `rules`
  (`dayHours`/`weekHours`/`monthHours`/`consecDays`/`restHours`, with `preferred`
  = soft and `min`/`max` = hard). Rules carry date-scheduled `changes` resolved
  per-date by `Rule.effectiveAt`. New personnel start with **no** rules.
  - **Global rules.** `Settings.globalRules` are working-time rules that apply to
    everyone (empty on a fresh DB). The solver injects them into each `Employee`
    before solving (`Employee.globalRules`, `@JsonIgnore`); `Employee.limit` uses a
    global rule only where the person has no personal rule for that metric+op. A
    personal rule may only be **stricter** than the matching global one (lower
    `max`, higher `min`; `preferred` is free), enforced in the UI; tightening a
    global rule auto-tightens any looser personal rule (with a warning). Each
    metric+op may be defined at most once, per person and globally.
  - **Availability is the calendar.** `pref` and `undes` blocks both define when
    an employee is *available* (an empty calendar = unavailable); a shift may only
    be assigned if it fits entirely within one window. Adjacent/overlapping blocks
    merge into one window internally (see `Employee.availableWindows`). On top of
    that hard rule, hours inside `pref` windows score soft-positive and hours
    inside `undes` windows soft-negative. `vac` is hard time-off.
- **Position → ShiftTemplate** — recurring shift definitions (`repeat` none/daily/weekly,
  `headcount`, `preferred` employees).
- **ShiftAssignment** (`@PlanningEntity`) — one slot per template occurrence per headcount.
  `@PlanningVariable(allowsUnassigned=true) employee`; `@PlanningPin pinned` for manual overrides.
- **Schedule** (`@PlanningSolution`) — `HardMediumSoftScore`: hard = rules, medium =
  coverage (fill slots), soft = preferences/balance.

`ScheduleExpander` turns templates + overrides into the concrete slots for the horizon.

### Solve window (Settings page)
`Settings.horizonEnd` computes the window: start of today → start of the next full
unit + `horizonCount` units. So `week × 1` covers "this week and the next".

### Continuous solving
`solverManager.solveBuilder()...run()` streams best solutions via
`withBestSolutionEventConsumer` (each also fires an SSE tick);
`unimproved-spent-limit` (application.properties) pauses the solver once the
solution is steady. Any `PUT /api/problem` restarts it.

### Constraints (`ScheduleConstraintProvider`)
Hard: required skills, vacation, availability (shift must fit an available window),
overlaps, min rest, daily/weekly/monthly hour limits (time-varying), max consecutive
days. Medium: coverage. Soft: preferred employees, preferred/undesired hours worked,
preferred weekly hours, workload balance. Constraint names must be alphanumeric — no `/`.

### Frontend notes
- `lib/api.js` is the only integration point.
- Appearance/interaction prefs persist in `localStorage`; solver settings live in the backend.
- `ShiftPlan` renders the solver's assignment map; the `AssignEditor` writes manual
  overrides which sync back as pins.
