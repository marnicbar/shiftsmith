// Tests for the SSE delta routing + list helpers (lib/deltas.js).
import { describe, it, expect, vi } from 'vitest';
import { dispatchChange, etagNum, upsertById, removeById, isStaleEcho, mergeVersionsMax, foldRemoteEntity } from './deltas.js';

const handlers = () => ({
  employee: vi.fn(), position: vi.fn(), settings: vi.fn(), schedule: vi.fn(),
  assignment: vi.fn(), heartbeat: vi.fn(), reload: vi.fn(),
});

describe('dispatchChange', () => {
  it('routes problem-edit events to their resource handler with id + rev', () => {
    const h = handlers();
    dispatchChange({ type: 'employee', id: 'e1', rev: 3 }, h);
    dispatchChange({ type: 'position', id: 'p1', rev: 1 }, h);
    dispatchChange({ type: 'settings', rev: 5 }, h);
    expect(h.employee).toHaveBeenCalledWith('e1', 3);
    expect(h.position).toHaveBeenCalledWith('p1', 1);
    expect(h.settings).toHaveBeenCalledWith(5);
  });

  it('routes a solver tick to the schedule handler', () => {
    const h = handlers();
    dispatchChange({ type: 'solver' }, h);
    expect(h.schedule).toHaveBeenCalledTimes(1);
    expect(h.assignment).not.toHaveBeenCalled();
  });

  it('routes a pin change to the dedicated assignment handler', () => {
    const h = handlers();
    dispatchChange({ type: 'assignment', from: '2026-06-01', to: '2026-06-01' }, h);
    expect(h.assignment).toHaveBeenCalledTimes(1);
    expect(h.schedule).not.toHaveBeenCalled();
  });

  it('falls back to the schedule handler for assignment when none is dedicated', () => {
    const h = handlers();
    delete h.assignment;
    dispatchChange({ type: 'assignment' }, h);
    expect(h.schedule).toHaveBeenCalledTimes(1);
  });

  it('routes a heartbeat to its handler so a missed solver-idle edge can self-heal', () => {
    const h = handlers();
    dispatchChange({ type: 'heartbeat' }, h);
    expect(h.heartbeat).toHaveBeenCalledTimes(1);
    expect(h.schedule).not.toHaveBeenCalled();
  });

  it('tolerates a heartbeat when no handler is supplied (liveness only)', () => {
    const h = handlers();
    delete h.heartbeat;
    expect(() => dispatchChange({ type: 'heartbeat' }, h)).not.toThrow();
  });

  it('routes reload events and ignores connected frames', () => {
    const h = handlers();
    dispatchChange({ type: 'reload' }, h);
    dispatchChange({ type: 'connected' }, h);
    dispatchChange(null, h);
    expect(h.reload).toHaveBeenCalledTimes(1);
    expect(h.schedule).not.toHaveBeenCalled();
    expect(h.employee).not.toHaveBeenCalled();
  });
});

describe('list + etag helpers', () => {
  it('upsertById replaces by id or appends', () => {
    const list = [{ id: 'a', v: 1 }, { id: 'b', v: 1 }];
    expect(upsertById(list, { id: 'b', v: 2 })).toEqual([{ id: 'a', v: 1 }, { id: 'b', v: 2 }]);
    expect(upsertById(list, { id: 'c', v: 1 })).toEqual([...list, { id: 'c', v: 1 }]);
  });

  it('removeById drops the matching entry', () => {
    expect(removeById([{ id: 'a' }, { id: 'b' }], 'a')).toEqual([{ id: 'b' }]);
  });

  it('etagNum parses quoted/bare/garbage ETags', () => {
    expect(etagNum('"4"')).toBe(4);
    expect(etagNum('7')).toBe(7);
    expect(etagNum(null)).toBe(0);
    expect(etagNum('')).toBe(0);
  });
});

describe('self-echo suppression (isStaleEcho)', () => {
  it('skips an echo at or below the version we hold (our own / superseded write)', () => {
    expect(isStaleEcho(3, 3)).toBe(true);  // our own write echoing back
    expect(isStaleEcho(2, 3)).toBe(true);  // delayed echo of a now-superseded write
  });
  it('refetches a strictly newer rev (a genuine remote change)', () => {
    expect(isStaleEcho(4, 3)).toBe(false);
  });
  it('never skips a null rev (delete) or an unknown id', () => {
    expect(isStaleEcho(null, 3)).toBe(false);
    expect(isStaleEcho(3, undefined)).toBe(false);
  });
});

describe('mergeVersionsMax', () => {
  it('takes the per-row max so a row is never rolled back', () => {
    const current = { employees: { e1: 3 }, positions: { p1: 2 }, settings: 5 };
    const snapshot = { employees: { e1: 2, e2: 1 }, positions: {}, settings: 4 };
    expect(mergeVersionsMax(current, snapshot)).toEqual({
      employees: { e1: 3, e2: 1 }, positions: { p1: 2 }, settings: 5,
    });
  });
  it('adopts a genuinely newer remote version', () => {
    const merged = mergeVersionsMax({ employees: { e1: 3 }, positions: {}, settings: 0 }, { employees: { e1: 5 } });
    expect(merged.employees.e1).toBe(5);
  });
  it('tolerates empty/missing maps', () => {
    expect(mergeVersionsMax()).toEqual({ employees: {}, positions: {}, settings: 0 });
  });
});

describe('foldRemoteEntity', () => {
  const base = [{ id: 'e1', firstName: 'Anna' }];
  it('adopts the remote copy when the local copy matches the synced baseline', () => {
    const local = [{ id: 'e1', firstName: 'Anna' }];
    const remote = { id: 'e1', firstName: 'Annabel' };
    expect(foldRemoteEntity(local, base, 'e1', remote)).toEqual([remote]);
  });
  it('keeps an unsynced local edit instead of reverting in-progress typing', () => {
    const local = [{ id: 'e1', firstName: 'Annab' }];        // typed 'b' since last sync
    const staleRemote = { id: 'e1', firstName: 'Anna' };     // echo of the prior write
    expect(foldRemoteEntity(local, base, 'e1', staleRemote)).toBe(local); // unchanged ref
  });
  it('adds a remotely-created entity we do not have locally', () => {
    const remote = { id: 'e2', firstName: 'Bob' };
    expect(foldRemoteEntity(base, base, 'e2', remote)).toEqual([base[0], remote]);
  });
  it('applies a remote delete', () => {
    expect(foldRemoteEntity(base, base, 'e1', null)).toEqual([]);
  });
});
