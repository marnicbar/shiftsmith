# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Commands

### Backend (Quarkus + Timefold)
```bash
cd backend
mvn quarkus:dev          # dev mode, live reload on :8080
mvn package -DskipTests  # build (also validates the Timefold model)
java -jar target/quarkus-app/quarkus-run.jar
```

### Frontend (React + Vite)
```bash
cd frontend
npm install
npm run dev    # dev server on :5173, proxies /api/* → :8080
npm run build  # production build to dist/
```

### Full stack
```bash
docker compose up --build   # backend :8080, frontend :5173
```

## Architecture

React SPA → Vite/nginx proxy → Quarkus REST → Timefold Solver. **State is
in-memory** in `ScheduleService` (no database); demo data is seeded on startup.

### Backend is the source of truth
The frontend owns the editor UI but the backend holds the canonical problem
(employees, positions, settings, manual overrides) and the solver. The frontend:
1. loads everything from `GET /api/schedule` on startup,
2. debounce-syncs the whole problem to `PUT /api/problem` on every edit,
3. polls `GET /api/schedule` while the solver is running to refresh assignments.

### Domain model
- **Employee** — `skills`, calendar `blocks` (`pref`/`undes`/`vac`), and working-time
  `rules` (`dayHours`/`weekHours`/`monthHours`/`consecDays`/`restHours`, with
  `preferred` = soft and `min`/`max` = hard). Rules carry date-scheduled `changes`
  resolved per-date by `Rule.effectiveAt`.
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
`solveAndListen` streams best solutions; `unimproved-spent-limit` (application.properties)
pauses the solver once the solution is steady. Any `PUT /api/problem` restarts it.

### Constraints (`ScheduleConstraintProvider`)
Hard: required skills, vacation, overlaps, min rest, daily/weekly/monthly hour limits
(time-varying), max consecutive days. Medium: coverage. Soft: preferred employees,
preferred/undesired time blocks, preferred weekly hours, workload balance.
Constraint names must be alphanumeric — no `/`.

### Frontend notes
- `lib/api.js` is the only integration point.
- Appearance/interaction prefs persist in `localStorage`; solver settings live in the backend.
- `ShiftPlan` renders the solver's assignment map; the `AssignEditor` writes manual
  overrides which sync back as pins.
