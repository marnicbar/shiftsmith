# Testing

This document is the plan and reference for the ShiftSmith test suite. The goal is
**robustness**: catch regressions automatically, keep the solver honest, and make the
test layer cheap enough to run on every change.

## Layers at a glance

| Layer | Where | Tooling | Needs Docker? | What it guards |
|-------|-------|---------|---------------|----------------|
| Domain unit tests | `backend/src/test/.../domain` | JUnit 5 + AssertJ | no | Pure model maths: recurrence, rules-over-time, availability windows, horizon, time arithmetic |
| Expansion unit tests | `backend/src/test/.../service` | JUnit 5 | no | Templates → concrete slots (headcount, recurrence in-window, overrides/pins) |
| Constraint unit tests | `backend/src/test/.../solver/ScheduleConstraintProviderTest` | Timefold `ConstraintVerifier` | no | Each scoring rule **in isolation** (penalty / reward / no-impact) |
| Solver scenario tests | `backend/src/test/.../solver/ScheduleSolverTest` | Real solver, tiny problems | no | End-to-end: known input → expected assignment; impossible inputs → unfilled |
| REST/persistence IT | `backend/src/test/.../rest/ScheduleResourceIT` | `@QuarkusTest` + RestAssured + Dev Services PostgreSQL | **yes** | The HTTP contract and the persist → solve → read-back round-trip |
| Export document tests | `backend/src/test/.../export/CalendarDocumentBuilderTest` | JUnit 5 + AssertJ | no | What lands on the page: day lists, overnight splitting, band clipping, lane packing, month chips, localisation |
| PDF render tests | `backend/src/test/.../service/PdfExportServiceTest` | JUnit 5 + the real `typst` binary | no (needs `typst` on PATH, else skipped) | The template compiles each view — and a batch — to a PDF; a missing binary degrades cleanly |
| Frontend logic tests | `frontend/src/**/*.test.js(x)` | Vitest | no | Date/time helpers, recurrence parity, local assignment preview, the `api.js` client |
| Frontend component tests | `frontend/src/*.test.jsx` | Vitest + React Testing Library + jsdom | no | Components render the right thing and react to interaction |

## Backend

### Running

```bash
cd backend
mvn test            # unit + constraint + solver tests (no Docker needed)
mvn verify          # additionally runs *IT integration tests *if Docker is present*
```

`mvn test` runs everything except `*IT`. The `@QuarkusTest` integration test boots the
real app with a throwaway PostgreSQL (Quarkus Dev Services), which needs Docker. To keep
the default build runnable anywhere, that test is:

1. named `*IT`, so Surefire (the `test` phase) never loads it — only Failsafe does; and
2. guarded twice — the Failsafe execution lives in a Maven profile that auto-activates
   only when `/var/run/docker.sock` exists, and the class itself carries
   `@EnabledIfDockerAvailable`.

So `mvn test` is green with or without Docker, and `mvn verify` transparently adds the
integration test when Docker is available.

### How constraints are tested

Each constraint in `ScheduleConstraintProvider` is unit-tested with Timefold's
`ConstraintVerifier` (bundled in `timefold-solver-core`, no enterprise licence needed). A
test feeds a handful of facts to a single constraint and asserts its exact impact, e.g.:

```java
verifier.verifyThat(ScheduleConstraintProvider::maxHoursPerDay)
        .given(employee, shiftA, shiftB)
        .penalizesBy(120);   // 2 hours over an 8h cap, in minutes
```

> Note: `ConstraintVerifier` in the community edition reads the weight from the
> constraint's **match-weigher** lambda. Constant-weight constraints (`reward(ofSoft(4))`)
> report only the match *count*. `preferredEmployee` therefore uses an explicit weigher
> (`reward(ONE_SOFT, a -> 4)`) — identical score, but the magnitude stays assertable.

### How the solver is tested

`ScheduleSolverTest` builds small problems through the **real** expansion + constraint
stack (`SolverHarness` wires the production `ScheduleConstraintProvider` into a plain
`SolverFactory` — no Quarkus, no DB) and solves them. Every scenario is designed to have a
**unique** optimum so the assertion is deterministic:

- **Positive** — exactly one employee can satisfy each slot (by skill, availability or
  preference), so the expected assignment is forced and asserted.
- **Negative / impossible** — a slot that nobody can fill without breaking a hard rule
  (no matching skill, on vacation, would exceed an hour cap, or would double-book an
  overlapping slot). Because coverage is only a *medium* reward, the optimum leaves such a
  slot **empty** while keeping `hardScore == 0`. The tests assert exactly that.

The solver is deterministic in `REPRODUCIBLE` mode; tiny problems converge well inside the
short termination used by the harness.

## Frontend

### Recommendation

**Vitest + React Testing Library (RTL) + jsdom.** Rationale:

- **Vitest** reuses the existing Vite config and transforms, starts in milliseconds, is
  ESM/JSX-native, and offers a Jest-compatible API (`describe/it/expect/vi`) — so there's
  no second build pipeline to maintain.
- **React Testing Library** tests components the way a user sees them (query by text/role,
  simulate clicks) rather than by implementation detail, which makes tests survive
  refactors.
- **jsdom** provides a lightweight DOM so component tests run headless in CI.

### Running

```bash
cd frontend
npm install            # one-time, pulls the test devDependencies
npm test               # run once
npm run test:watch     # watch mode while developing
npm run test:coverage   # coverage report (v8)
```

### What's covered / how to extend

- `data.test.js` — the pure date/time helpers every view depends on.
- `shiftplan.test.jsx` — `matchesDay` (recurrence, kept in lock-step with the backend's
  `Recurrence`) and `buildPlan` (the local greedy assignment preview).
- `lib/api.test.js` — the API client, with `fetch` and `EventSource` stubbed.
- `dashboard.test.jsx` — a worked RTL example (render with props, assert visible KPIs,
  click a button and assert the callback). Use it as the template for new component tests.
- `export.test.jsx` — the PDF export control: the parameters it sends, the drop-out
  warning it shows, the download it triggers. What the PDF *contains* is asserted
  backend-side (`CalendarDocumentBuilderTest`), since that is where it is built.

When adding tests, prefer pulling pure logic out of components (as `matchesDay`/`buildPlan`
already are) and unit-testing it directly; reach for RTL for genuinely UI-level behaviour.

## Conventions

- Tests are deterministic: backend fixtures anchor to a fixed Monday (`Fixtures.MON`)
  rather than "today"; the one place that must use the live date (the REST IT) anchors its
  template to `LocalDate.now()` so the slot lands inside the live solve window.
- Keep solver scenario problems tiny and uniquely-solvable — that is what makes
  "expected == actual" assertions safe.
