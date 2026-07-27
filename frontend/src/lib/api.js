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

// --- PDF export -------------------------------------------------------------
// The backend builds the export document from the canonical problem and renders it
// with Typst; the client only says *what* to export. `scope` repeats — one per page
// of the PDF — so the same call serves a single calendar and a batch.
//
//   { scopes: ['person:e1'], view: 'week', anchor: '2026-07-27',
//     from: 360, to: 1320, paper: 'a4', orientation: 'landscape' }

function exportQuery({ scopes = [], view, anchor, from, to, paper, orientation, lang, nameOrder }) {
  const p = new URLSearchParams();
  for (const s of scopes) p.append('scope', s);
  if (view) p.set('view', view);
  if (anchor) p.set('anchor', anchor);
  if (from != null) p.set('from', String(from));
  if (to != null) p.set('to', String(to));
  if (paper) p.set('paper', paper);
  if (orientation) p.set('orientation', orientation);
  if (lang) p.set('lang', lang);
  if (nameOrder) p.set('nameOrder', nameOrder);
  return p.toString();
}

// What the export *would* contain — above all the shifts the chosen printed hours
// would leave off the page — so the dialog can warn before anything is downloaded.
export const getExportPlan = (params) =>
  request(`${BASE}/export/calendar/plan?${exportQuery(params)}`);

// Render the PDF. Returns `{ blob, filename }`: the server names the file (it knows
// whether this is one calendar or a batch), and we honour that in the download.
export async function exportCalendarPdf(params) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const url = `${BASE}/export/calendar.pdf?${exportQuery(params)}`;
  const res = await fetch(url, { headers });
  if (res.status === 401) {
    clearToken();
    if (onUnauthorized) onUnauthorized();
    const e = new Error(`GET ${url} failed: 401`); e.status = 401; throw e;
  }
  if (!res.ok) {
    let serverMessage = null;
    try { const b = await res.json(); if (b && typeof b.error === 'string') serverMessage = b.error; } catch { /* no body */ }
    const e = new Error(serverMessage || `PDF export failed: ${res.status}`);
    e.status = res.status; e.serverMessage = serverMessage; throw e;
  }
  return { blob: await res.blob(), filename: filenameFrom(res.headers.get('Content-Disposition')) };
}

function filenameFrom(disposition) {
  const m = /filename="([^"]+)"/.exec(disposition || '');
  return m ? m[1] : 'schedule.pdf';
}

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

// Reads that also return the resource's ETag, so a delta can refetch one slice and
// keep its version in step.
export const getEmployee = (id) => send('GET', `${BASE}/employees/${enc(id)}`);
export const getPosition = (id) => send('GET', `${BASE}/positions/${enc(id)}`);
export const getSettings = () => send('GET', `${BASE}/settings`);

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

// Live updates over Server-Sent Events: the backend pushes a small typed change event
// (issue #47, Phase 5) whenever a resource is edited, a pin changes, or the solver
// advances — the client refetches only the affected slice. The browser's EventSource
// auto-reconnects on drop; `onOpen` fires on (re)connect so the client can catch up,
// `onError` on a drop so it can show a "reconnecting" state. EventSource can't send
// headers, so the token rides along as a query parameter. Returns an unsubscribe fn.
export function subscribeSchedule(onEvent, onError, onOpen) {
  const token = getToken();
  const url = token ? `${BASE}/stream?token=${encodeURIComponent(token)}` : `${BASE}/stream`;
  const es = new EventSource(url);
  es.onmessage = (e) => {
    try { onEvent(JSON.parse(e.data)); } catch { /* ignore malformed frame */ }
  };
  if (onOpen) es.onopen = onOpen;
  if (onError) es.onerror = onError; // EventSource reconnects automatically
  return () => es.close();
}
