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

### Production image (UI + API in one container)
```bash
docker compose up -d --build   # app (UI+API) :8080 + postgres
```
The root `Dockerfile` is a multi-stage build: it builds the React SPA, bundles
`dist/` into the Quarkus app's `META-INF/resources/` (served at `/` on the same
origin as `/api`), and packages the backend. One process, one port (8080), no
nginx — bring your own PostgreSQL (`docker-compose.yml` wires up a `db` service;
the database is deliberately not part of the image). Tagged builds (`v*`) are
published to `ghcr.io/marnicbar/shiftsmith` by `.github/workflows/release.yml`.
Because the frontend has no client-side router, the SPA is only ever served at
`/`, so no deep-link fallback is needed.

### Dev full stack (hot reload)
```bash
docker compose -f docker-compose.dev.yml up --build   # postgres :5432, backend :8080, frontend :5173
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

React SPA → Quarkus REST → Timefold Solver, with PostgreSQL for persistence. In
dev the Vite server proxies `/api/*` to Quarkus; in the production image Quarkus
serves the built SPA itself (same origin, no proxy). `ScheduleService` keeps the working problem in memory (the
solver needs it there) but **persists it to Postgres as normalized, time-indexed
rows** so it survives restarts. On boot it rehydrates from the DB; an empty
database starts with an empty problem (no demo data).

### Persistence (`persistence` package)
The problem is stored in **normalized, time-indexed tables** (issue #47):
`settings`, `skill`, `employee`(+`employee_skill`), `availability_block`
(+`availability_block_exception`), `work_rule`(+`work_rule_change`, with a NULL
`employee_id` for a global rule), `position`(+`position_skill`),
`shift_template`(+`_skill`/`_exception`/`_preferred`), and the core `assignment`
table (one row per concrete slot: manual pins are `source=manual`, the solver's
durable output is `source=solver`). Schema is owned by **Flyway** (`db/migration`,
`migrate-at-start`, `baseline-on-migrate`); Hibernate only `validate`s it (no
auto-DDL). The two models stay separate: the Timefold-annotated domain classes
(`domain/`) and the JPA entities (`persistence/entity/`), bridged by the pure,
unit-tested `ProblemMapper`. There is **no document blob and no whole-document
write**: on boot `ProblemStore.load()` rehydrates the whole problem from the rows
into a `LoadedProblem` (empty DB seeds an empty `settings` row); every write goes
through the granular per-resource stores (`EmployeeStore`/`PositionStore`/
`SettingsStore`/`AssignmentStore`), each with a row `version` for optimistic
concurrency (`If-Match`/ETag → 409). Per-row reads/writes, windowed reads, SSE
deltas, bounded solver scope and per-employee authorization are the rest of #47.
- Dev (`mvn quarkus:dev`): Quarkus Dev Services auto-starts a throwaway Postgres
  (needs Docker). Prod/compose: connects to the `db` service via
  `QUARKUS_DATASOURCE_*` env (see `application.properties` `%prod` keys).

### Backend is the source of truth
The frontend owns the editor UI but the backend holds the canonical problem
(employees, positions, settings, manual overrides) and the solver. The frontend:
1. loads everything from `GET /api/schedule` on startup (incl. a per-resource
   `versions`/ETag map),
2. debounce-diffs each edit into **granular, concurrency-safe calls** (`lib/sync.js`:
   `diffProblem` → `POST/PUT/DELETE /api/{employees,positions}/{id}`, `PUT /api/settings`,
   `PUT/DELETE /api/assignments/{templateId}/{date}`), threading each resource's
   version with `If-Match`; a `409` reloads (there is no bulk `PUT /api/problem`),
3. subscribes to `GET /api/stream` (SSE) for live delta updates while the solver runs.

### Live updates (SSE deltas, issue #47 Phase 5)
`GET /api/stream` is a Server-Sent Events endpoint emitting small **typed change
events** (`ChangeEvent`: `{type, id?, rev?, from?, to?}`), not full snapshots.
`ScheduleBroadcaster` fans out an event whenever a resource is edited
(`employee`/`position`/`settings` with id + new version), a pin changes
(`assignment`), or the solver advances (`solver`); plus a `connected` frame and a
25s `heartbeat`. The stream never rebuilds a `ScheduleDTO`, so the solver's frequent
ticks no longer fan a full rebuild out to every subscriber (the #38 contention).
Clients (`api.subscribeSchedule`, `lib/deltas.js`) refetch only the affected slice —
the granular `GET /api/{employees,positions}/{id}` / `/settings` for problem edits
(skipping their own edits via the `rev` hint), and a debounced `GET /api/schedule`
for `solver`/`assignment` events. `EventSource` auto-reconnects; a drop flips a
visible "reconnecting" state and a reconnect refetches to catch up.

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

**Timezone.** The model is zone-less: `LocalDate.now()` ("today"), the horizon
roll-over and shift wall-clock times all use the **app's default timezone**. Pin it
explicitly via the `TZ` env (set on the `app`/`backend` services in the compose files,
defaulting to UTC) to your business timezone so the horizon rolls at local midnight;
don't rely on the host zone. Making the business TZ a per-tenant setting is future work.

### Continuous solving
`solverManager.solveBuilder()...run()` streams best solutions via
`withBestSolutionEventConsumer` (each also fires an SSE tick);
`unimproved-spent-limit` (application.properties) pauses the solver once the
solution is steady. Any granular write (or a pin change) restarts it.

### Durable schedule & bounded lookback (issue #47, Phase 2)
The solver's final best solution is persisted as `assignment` rows (`AssignmentStore`,
`source = solver`) and reloaded on boot, so a restart shows the last roster
immediately (`ScheduleService.persistSolved`/`reloadPersistedAssignments` overlay
`currentAssignments`). Past `assignment` rows are the history: `SolverScope.lookbackStart`
bounds the working set to `[lookbackStart, windowEnd)` — the window plus just the
lead-in each boundary constraint needs (ISO-week/month bucket starts, `maxConsecDays`,
`maxRestHours`) — and `ScheduleService` loads the worked shifts in
`[lookbackStart, windowStart)` as fixed **history facts** (`ShiftAssignment.history`,
pinned). History counts towards rest/consec/week/month at the boundary but is ignored
by per-shift rules, coverage and preferences, and only charges a breach where a window
slot shares it. Because the granular per-resource stores upsert the FK targets
(employee, position, shift_template) by id, this history survives edits to those
resources.

### Constraints (`ScheduleConstraintProvider`)
Hard: required skills, vacation, availability (shift must fit an available window),
overlaps, min rest, daily/weekly/monthly hour limits (time-varying), max consecutive
days. Medium: coverage. Soft: preferred employees, preferred/undesired hours worked,
preferred weekly hours, workload balance. Constraint names must be alphanumeric — no `/`.

### Frontend notes
- `lib/api.js` is the only integration point.
- **Brand mark.** `frontend/public/logo.svg` is the single drawing of the ShiftSmith
  logo: `brand.jsx` renders it (topbar + login screen), `index.html` points the
  favicon at it, and the PDF export prints it in the page footer. The export runs in
  a separate build context that never sees `frontend/`, so the backend ships a copy at
  `backend/src/main/resources/typst/logo.svg` — `PdfExportServiceTest` fails if the two
  drift apart, so **edit the logo in both places**.
- Appearance/interaction prefs persist in `localStorage`; solver settings live in the backend.
- `ShiftPlan` renders the solver's assignment map; the `AssignEditor` writes manual
  overrides which sync back as pins.
- The **Shift Plan tab** is `PlanView` (`planview.jsx`), a scope selector (Overview /
  Personnel / Positions) owned by the top nav — the Shift Plan tab button *morphs*
  into the selector while active (`planScope` in `App.jsx`). *Overview* is the
  `ShiftPlan` timeline; *Personnel* and *Positions* are read-only day/week/month
  calendars of the **actual** assignments (`buildPersonEvents` / `buildPositionEvents`
  turn the solver's `assignMap` into concrete events). They reuse the shared `Calendar`
  in `readOnly` mode (no create/drag/edit; events carry `_tone`/`_label`/`_title`/`_color`).
  `calendar.jsx` exports `calendarDays(view, anchor)` so the builders expand exactly the
  visible range.

### PDF export (`export` package + `typst/calendar.typ`)
The read-only Plan calendars can be printed to PDF for the day/week/month in view.
**Everything is server-side** — the client only says *what* to export, so a future
scheduled/emailed export uses the same path:

- `ExportRequest` — validated query params. `scope` **repeats**, one per page
  (`person:<id>` / `position:<id>`), so a single export and a batch ("everyone, a page
  each") are the same code path; `MAX_SCOPES` bounds it.
- `CalendarDocumentBuilder` — pure, no CDI/DB. The server-side counterpart of
  `planview.jsx`'s `buildPersonEvents`/`buildPositionEvents` and `calendar.jsx`'s
  `calendarDays`/`packLanes`: **keep those in lock-step**. Splits overnight shifts at
  midnight, clips to the printed band, packs overlaps into lanes, and builds month chips.
- `ExportLabels` / `Palette` — every string, date format and colour the page needs.
  `Palette` mirrors `theme.js` `colorAt` (OKLCH components, not a CSS string).
- `PdfExportService` — drops the document's JSON, plus `typst/logo.svg` (the brand mark
  the footer prints — Typst may only read inside the sandbox root), beside
  `typst/calendar.typ` in a scratch directory and shells out to
  `typst compile --root <dir>` (sandboxed, timed out, cleaned up). The template composes
  **no text of its own**; a section is a page.
- `ScheduleService.assignMap(from, to)` — durable rows overlaid with the live in-memory
  solution, exactly as the UI overlays them.

Endpoints (both build the same document, so they cannot disagree):
`GET /api/export/calendar.pdf` renders it; `GET /api/export/calendar/plan` returns just
the preflight — above all **which shifts the printed hours would leave off the page**,
which the dialog warns about and the PDF lists in a footnote. Nothing is dropped silently.

The `typst` binary is installed in both Dockerfiles (pinned `TYPST_VERSION`, plus
`fonts-dejavu-core` — Typst embeds no sans-serif face). Without it the app runs normally
and only the export endpoint answers 503 with a clear message
(`shiftsmith.typst.bin` / `shiftsmith.typst.timeout-seconds`).

### Internationalization (`i18n/`)
- UI strings live in `i18n/locales/{en,de}.json` (one `translation` namespace, nested by
  feature); English is the fallback. Components read them via `useTranslation()`'s `t()`.
  Add a key to **both** locale files — `i18n.test.jsx` fails if the EN/DE key sets diverge.
- The selected language is a UI pref (`prefs.lang` in the `localStorage` bag, set on the
  Settings → Appearance page). `i18n/index.js` seeds i18next from it; `App.jsx` calls
  `i18n.changeLanguage` when it changes.
- Date/number formatting uses `dateLocale()` (→ `en-US`/`de-DE`) and `is24h()` from
  `i18n/index.js`, not a hardcoded locale. Pass these to `Intl`/`toLocaleDateString`.
- Tests init i18next via `test/setup.js` (defaults to English), so `t()` resolves without
  a provider. Keep test-asserted English strings byte-identical to the EN resource values.
