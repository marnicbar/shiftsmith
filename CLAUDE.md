# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Commands

### Backend (Quarkus)
```bash
cd backend
mvn quarkus:dev          # dev mode with live reload on :8080
mvn package -DskipTests  # compile and package
mvn test                 # run tests
```

### Frontend (Svelte + Vite)
```bash
cd frontend
npm install
npm run dev    # dev server on :5173, proxies /api/* → localhost:8080
npm run build  # production build to dist/
```

### Full stack with Docker
```bash
docker compose up --build   # frontend on :80, backend on :8080
```

### Useful endpoints (when running locally)
- App: http://localhost:5173
- Swagger UI: http://localhost:8080/q/swagger-ui
- REST API root: http://localhost:8080/api/schedule

## Architecture

### Overview
Three-layer architecture: Svelte SPA → nginx proxy → Quarkus REST → Timefold Solver.

In dev the Vite dev server proxies `/api/*` to `:8080`; in production nginx does the same. There is no CORS configuration — the proxy makes it unnecessary.

### Backend (`backend/`)

**State is in-memory only.** `ScheduleService` holds the canonical `List<Employee>` and `List<Shift>` in RAM. There is no database. On restart the demo seed data is reloaded.

**Solver flow:**
1. `POST /api/schedule/solve` → `ScheduleService.startSolving()` snapshots the current employees/shifts into a new `EmployeeSchedule` and hands it to `SolverManager.solveAndListen`.
2. The solver runs asynchronously; each new best solution is written to `volatile EmployeeSchedule bestSolution`.
3. `GET /api/schedule` returns `bestSolution` (if one exists) or a fresh unsolved snapshot, always annotated with the current `SolverStatus`.
4. Solver terminates after 30 s (`quarkus.timefold.solver.termination.spent-limit`) or when `DELETE /api/schedule/solve` is called.

**Timefold domain annotations:**
- `Employee` — `@ProblemFactCollectionProperty` + `@ValueRangeProvider` (the pool Timefold picks from when assigning shifts)
- `Shift` — `@PlanningEntity`; its `employee` field is the sole `@PlanningVariable`
- `EmployeeSchedule` — `@PlanningSolution`; score type is `HardSoftScore`

**Constraints** (`ScheduleConstraintProvider`):
- Hard (must not violate): missing required skill, overlapping shifts per employee, < 10 h between shifts, > 1 shift per day, unavailable dates
- Soft (optimised): undesired dates (penalise), desired dates (reward), shift count balance (penalise count² per employee)

**Editing shifts while solving:** CRUD mutations go directly to the `employees`/`shifts` lists; the currently running solve job uses a snapshot taken at solve-start and is unaffected. The user must press Solve again to incorporate changes.

**Employee name is the `@PlanningId`** — renaming is not supported (the PUT endpoint replaces the record but the old name is gone).

### Frontend (`frontend/src/`)

`App.svelte` owns the schedule state and polling loop (every 2 s while `solverStatus` is `SOLVING_ACTIVE` or `SOLVING_SCHEDULED`). It passes data down and listens for `reload` events that trigger a fresh `GET /api/schedule`.

`api.js` is the single integration point — all fetch calls live there. Dates/times are sent as ISO-8601 strings (`LocalDateTime` serialised by Jackson's JavaTimeModule).

Component responsibilities:
- `EmployeePanel` — CRUD for employees; name is readonly in edit mode because it is the planning ID
- `ShiftPanel` — CRUD for shifts; editing a shift clears its current employee assignment (re-solve to reassign)
- `ScheduleGrid` — read-only view; groups shifts by date, colour-codes assigned (green) vs unassigned (amber)
