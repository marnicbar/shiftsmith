# ShiftSmith

An open-source employee scheduling app powered by constraint programming. Define your employees and shifts, hit **Solve**, and let the optimizer assign everyone automatically while respecting skill requirements, availability, and fair workload distribution.

## Features

- **Automated scheduling** — Timefold Solver assigns employees to shifts using constraint programming, producing optimal or near-optimal schedules within 30 seconds
- **Skill matching** — each shift requires a skill (e.g. Bartender, Waiter); only employees with that skill are assigned
- **Availability & preferences** — mark employees as unavailable, undesired, or desired on specific dates; the solver respects hard unavailability and optimises soft preferences
- **Fair distribution** — shifts are balanced across employees automatically
- **Full CRUD** — add, edit, and delete employees and shifts at any time via the UI
- **Live schedule grid** — week view updates in real time while the solver is running; assigned shifts show in green, unassigned in amber
- **Swagger UI** — full REST API documentation available at `/q/swagger-ui`

## Constraints

| Type | Rule |
|------|------|
| Hard | Employee must have the required skill |
| Hard | No two overlapping shifts for the same employee |
| Hard | At least 10 hours between consecutive shifts |
| Hard | Maximum one shift per day per employee |
| Hard | Employee cannot work on unavailable dates |
| Soft | Avoid scheduling on undesired dates |
| Soft | Prefer scheduling on desired dates |
| Soft | Balance total shift count across all employees |

## Running the app

### With Docker Compose (recommended)

Requires [Docker](https://docs.docker.com/get-docker/) with the Compose plugin.

```bash
git clone https://github.com/marnicbar/shiftsmith.git
cd shiftsmith
docker compose up --build
```

Open **http://localhost** in your browser.

The first build downloads Maven and npm dependencies and may take a few minutes. Subsequent builds are faster.

### Without Docker (local development)

Requires Java 21, Maven, and Node.js 20+.

**Backend:**
```bash
cd backend
mvn quarkus:dev
```

The backend starts on http://localhost:8080 with live reload enabled.

**Frontend** (in a separate terminal):
```bash
cd frontend
npm install
npm run dev
```

The frontend starts on http://localhost:5173 and proxies `/api/*` to the backend automatically.

## Usage

1. **Review the demo data** — the app starts with 5 employees and 14 shifts seeded across the next 7 days
2. **Add or edit employees** — use the Employees panel on the left; set skills and optionally mark unavailable/undesired/desired dates as comma-separated `YYYY-MM-DD` values
3. **Add or edit shifts** — use the Shifts panel in the middle; each shift needs a date, time range, location, and required skill
4. **Solve** — click the **Solve** button in the header; the badge animates while the solver runs (up to 30 seconds)
5. **Stop early** — click **Stop** at any time to keep the best solution found so far
6. **Re-solve** — after editing employees or shifts, click **Solve** again to get a fresh schedule

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | [Svelte](https://svelte.dev) + [Vite](https://vitejs.dev) |
| Backend | [Quarkus](https://quarkus.io) 3.15 (Java 21) |
| Solver | [Timefold Solver](https://timefold.ai) 1.27 |
| Proxy | nginx (production) / Vite dev server (development) |

## License

Apache 2.0 — see [LICENSE](LICENSE).
