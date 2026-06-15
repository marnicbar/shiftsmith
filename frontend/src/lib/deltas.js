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
