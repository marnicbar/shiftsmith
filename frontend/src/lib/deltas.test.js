// Tests for the SSE delta routing + list helpers (lib/deltas.js).
import { describe, it, expect, vi } from 'vitest';
import { dispatchChange, etagNum, upsertById, removeById } from './deltas.js';

const handlers = () => ({
  employee: vi.fn(), position: vi.fn(), settings: vi.fn(), schedule: vi.fn(),
  assignment: vi.fn(), reload: vi.fn(),
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

  it('routes reload events and ignores liveness frames', () => {
    const h = handlers();
    dispatchChange({ type: 'reload' }, h);
    dispatchChange({ type: 'connected' }, h);
    dispatchChange({ type: 'heartbeat' }, h);
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
