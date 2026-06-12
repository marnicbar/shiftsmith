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
  if (!res.ok) {
    // Surface the server's error envelope ({ error: "..." }) when present — e.g. a
    // 400 validation message — so the UI can explain *why* the request was rejected
    // instead of falling back to a bare status / "is the backend running?" notice.
    let serverMessage = null;
    try {
      const body = await res.json();
      if (body && typeof body.error === 'string') serverMessage = body.error;
    } catch { /* empty or non-JSON body */ }
    const err = new Error(serverMessage || `${options.method ?? 'GET'} ${url} failed: ${res.status}`);
    err.status = res.status;
    err.serverMessage = serverMessage;
    throw err;
  }
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

// Windowed read of the durable assignment slots in [from, to) (ISO dates),
// optionally narrowed to one person/position (scope = 'person:<id>' | 'position:<id>').
// Unlike getSchedule this spans history and any persisted future, not just the live
// solve window — it powers the read-only Personnel/Positions calendars per range.
export const getScheduleRange = (from, to, scope) => {
  const params = new URLSearchParams({ from, to });
  if (scope) params.set('scope', scope);
  return request(`${BASE}/schedule/range?${params.toString()}`);
};

// Replace the problem (employees / positions / settings / overrides) and re-solve.
// Any omitted field is left unchanged server-side. Deprecated: kept as a fallback while
// the granular writes below (issue #47, Phase 4) take over the per-edit sync.
export const putProblem = (problem) => request(`${BASE}/problem`, { method: 'PUT', ...json(problem) });

// --- Granular, concurrency-safe writes (issue #47, Phase 4) -----------------
// Each returns { data, etag }; mutations carry the resource's expected version as an
// If-Match header so a stale write is rejected (409) instead of silently overwriting.
async function send(method, url, body, ifMatch) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (ifMatch != null) headers['If-Match'] = String(ifMatch);
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  if (res.status === 401) {
    clearToken();
    if (onUnauthorized) onUnauthorized();
    const e = new Error(`${method} ${url} failed: 401`); e.status = 401; throw e;
  }
  if (!res.ok) {
    let serverMessage = null;
    try { const b = await res.json(); if (b && typeof b.error === 'string') serverMessage = b.error; } catch { /* no body */ }
    const e = new Error(serverMessage || `${method} ${url} failed: ${res.status}`);
    e.status = res.status; e.serverMessage = serverMessage; throw e;
  }
  const etag = res.headers.get('ETag');
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { data, etag };
}

const enc = encodeURIComponent;

export const createEmployee = (employee) => send('POST', `${BASE}/employees`, employee);
export const updateEmployee = (employee, ifMatch) => send('PUT', `${BASE}/employees/${enc(employee.id)}`, employee, ifMatch);
export const deleteEmployee = (id, ifMatch) => send('DELETE', `${BASE}/employees/${enc(id)}`, undefined, ifMatch);

export const createPosition = (position) => send('POST', `${BASE}/positions`, position);
export const updatePosition = (position, ifMatch) => send('PUT', `${BASE}/positions/${enc(position.id)}`, position, ifMatch);
export const deletePosition = (id, ifMatch) => send('DELETE', `${BASE}/positions/${enc(id)}`, undefined, ifMatch);

export const updateSettings = (settings, ifMatch) => send('PUT', `${BASE}/settings`, settings, ifMatch);

// Pin an occurrence to an ordered employee-id list (null/short = pinned-empty); unpin drops it.
export const pinOccurrence = (templateId, date, employeeIds) =>
  send('PUT', `${BASE}/assignments/${enc(templateId)}/${date}`, employeeIds ?? []);
export const unpinOccurrence = (templateId, date) =>
  send('DELETE', `${BASE}/assignments/${enc(templateId)}/${date}`);

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
