// deltas.js — route a typed SSE change event (issue #47, Phase 5) to the right
// refetch handler, and small list helpers for applying a remote change. Pure, so the
// routing is unit-testable; the handlers themselves live in the component (stateful).

/**
 * Dispatch one change event to `handlers`
 * { employee, position, settings, schedule, assignment, reload }.
 * A pin/unpin (`assignment`) is routed separately from a solver tick (`solver`) so the
 * client can also reconcile the overrides map for a pin, while keeping solver ticks cheap.
 * `assignment` falls back to `schedule` when no dedicated handler is supplied.
 */
export function dispatchChange(ev, handlers) {
  if (!ev || !ev.type) return;
  switch (ev.type) {
    case 'employee': handlers.employee(ev.id, ev.rev); break;
    case 'position': handlers.position(ev.id, ev.rev); break;
    case 'settings': handlers.settings(ev.rev); break;
    case 'assignment': (handlers.assignment || handlers.schedule)(); break;
    case 'solver': handlers.schedule(); break;
    case 'reload': handlers.reload(); break;
    default: break; // connected / heartbeat — liveness only
  }
}

/** Parse an ETag ("3" / "\"3\"") into a number. */
export const etagNum = (etag) => {
  const n = parseInt(String(etag ?? '').replace(/\D/g, ''), 10);
  return Number.isNaN(n) ? 0 : n;
};

export const upsertById = (list, item) => {
  const i = list.findIndex((x) => x.id === item.id);
  if (i < 0) return [...list, item];
  const copy = list.slice();
  copy[i] = item;
  return copy;
};

export const removeById = (list, id) => list.filter((x) => x.id !== id);

/**
 * Should a change event for `id` carrying version `rev` be ignored as an echo of a
 * change we already hold? Per-row versions are monotonic on the server, so any `rev`
 * at or below the one we hold is our own write (or a now-superseded one) — refetching
 * it gains nothing and risks reverting an unsynced local edit. Only a strictly newer
 * `rev` is a genuine remote change worth refetching. A null `rev` (e.g. a delete) or an
 * unknown id is never skipped.
 */
export const isStaleEcho = (rev, known) => rev != null && known != null && rev <= known;

/**
 * Fold a fresh per-resource version snapshot into the one we hold, taking the per-row
 * max so a row's version is never rolled backwards. A partial schedule refetch can
 * carry a snapshot read just before our latest write committed; adopting it wholesale
 * would regress that row's version and break self-echo suppression (`isStaleEcho`),
 * making a later echo of our own write look like a remote change. The max keeps our own
 * pending writes while still adopting any genuinely newer remote version.
 */
export function mergeVersionsMax(current = {}, incoming = {}) {
  const out = {
    employees: { ...(current.employees || {}) },
    positions: { ...(current.positions || {}) },
    settings: current.settings || 0,
  };
  for (const [id, v] of Object.entries(incoming.employees || {})) out.employees[id] = Math.max(out.employees[id] || 0, v || 0);
  for (const [id, v] of Object.entries(incoming.positions || {})) out.positions[id] = Math.max(out.positions[id] || 0, v || 0);
  out.settings = Math.max(out.settings, incoming.settings || 0);
  return out;
}

/**
 * Fold a refetched remote entity (`data`, or null for a delete) into the live `list`,
 * without clobbering an unsynced local edit. If our local copy of `id` has diverged from
 * the last-synced `baseline` (an edit in flight — e.g. characters typed since the last
 * sync), keep the local copy so a stale echo of our own earlier write can't revert
 * in-progress typing; the pending debounced sync will push the local edit. Otherwise
 * adopt the remote copy. A remote delete always applies.
 */
export function foldRemoteEntity(list, baseline, id, data) {
  if (!data) return removeById(list, id);
  const local = list.find((x) => x.id === id);
  const baseItem = (baseline || []).find((x) => x.id === id);
  if (local && JSON.stringify(local) !== JSON.stringify(baseItem)) return list; // unsynced local edit — keep it
  return upsertById(list, data);
}
