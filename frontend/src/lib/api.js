// api.js — single integration point with the Quarkus + Timefold backend.
const BASE = '/api';
const json = (body) => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

// --- Session token ----------------------------------------------------------
// The token authenticates every request. "Remember me" decides where it lives:
// localStorage survives a browser restart, sessionStorage is cleared when the
// tab closes. We read from whichever holds it.
const TOKEN_KEY = 'shiftsmith.token';

export function getToken() {
  try { return sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY) || null; }
  catch { return null; }
}

export function setToken(token, remember) {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(TOKEN_KEY);
    (remember ? localStorage : sessionStorage).setItem(TOKEN_KEY, token);
  } catch { /* storage unavailable — token stays in memory only for this load */ }
}

export function clearToken() {
  try { sessionStorage.removeItem(TOKEN_KEY); localStorage.removeItem(TOKEN_KEY); } catch {}
}

// Called when a request comes back 401, so the app can drop to the login screen.
let onUnauthorized = null;
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn; };

async function request(url, options = {}) {
  const token = getToken();
  const headers = { ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { ...options, headers });
  if (res.status === 401) {
    clearToken();
    if (onUnauthorized) onUnauthorized();
    throw new Error(`${options.method ?? 'GET'} ${url} failed: 401`);
  }
  if (!res.ok) throw new Error(`${options.method ?? 'GET'} ${url} failed: ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

// --- Auth -------------------------------------------------------------------
// Log in, store the token, and return the response. Does not throw on bad
// credentials — returns { ok: false } so the caller can show an inline error.
export async function login(username, password, remember) {
  const res = await fetch(`${BASE}/auth/login`, { method: 'POST', ...json({ username, password, remember }) });
  if (res.status === 401) return { ok: false };
  if (!res.ok) throw new Error(`login failed: ${res.status}`);
  const data = await res.json();
  setToken(data.token, remember);
  return { ok: true, username: data.username, mustChangePassword: !!data.mustChangePassword };
}

// Validate a stored token on startup; returns { username, mustChangePassword } or null.
export async function me() {
  if (!getToken()) return null;
  try {
    const d = await request(`${BASE}/auth/me`);
    return d?.username ? { username: d.username, mustChangePassword: !!d.mustChangePassword } : null;
  } catch { return null; }
}

export const changePassword = (currentPassword, newPassword) =>
  request(`${BASE}/auth/change-password`, { method: 'POST', ...json({ currentPassword, newPassword }) });

export function logout() { clearToken(); }

// --- Schedule ---------------------------------------------------------------
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
// EventSource can't send headers, so the token rides along as a query parameter.
// Returns an unsubscribe function that closes the stream.
export function subscribeSchedule(onUpdate, onError) {
  const token = getToken();
  const url = token ? `${BASE}/stream?token=${encodeURIComponent(token)}` : `${BASE}/stream`;
  const es = new EventSource(url);
  es.onmessage = (e) => {
    try { onUpdate(JSON.parse(e.data)); } catch { /* ignore malformed frame */ }
  };
  if (onError) es.onerror = onError; // EventSource reconnects automatically
  return () => es.close();
}
