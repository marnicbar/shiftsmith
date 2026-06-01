// api.js — single integration point with the Quarkus + Timefold backend.
const BASE = '/api';
const json = (body) => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

async function request(url, options = {}) {
  const res = await fetch(url, options);
  if (!res.ok) throw new Error(`${options.method ?? 'GET'} ${url} failed: ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

// Full state: problem data + the solver's current best assignment + status.
export const getSchedule = () => request(`${BASE}/schedule`);

// Replace the problem (employees / positions / settings / overrides) and re-solve.
// Any omitted field is left unchanged server-side.
export const putProblem = (problem) => request(`${BASE}/problem`, { method: 'PUT', ...json(problem) });

// Solver lifecycle (auto-runs on every problem change; these are manual controls).
export const startSolving = () => request(`${BASE}/solve`, { method: 'POST' });
export const stopSolving = () => request(`${BASE}/solve`, { method: 'DELETE' });

// Live updates over Server-Sent Events: the backend pushes a fresh schedule
// snapshot whenever the solver improves the solution, the problem changes, or
// the solver starts/stops. The browser's EventSource auto-reconnects on drop.
// Returns an unsubscribe function that closes the stream.
export function subscribeSchedule(onUpdate, onError) {
  const es = new EventSource(`${BASE}/stream`);
  es.onmessage = (e) => {
    try { onUpdate(JSON.parse(e.data)); } catch { /* ignore malformed frame */ }
  };
  if (onError) es.onerror = onError; // EventSource reconnects automatically
  return () => es.close();
}
