// Tests for the granular-sync layer (lib/sync.js): the pure problem diff and the
// version-threading executor that replaces the bulk PUT /api/problem.
import { describe, it, expect, vi } from 'vitest';
import { diffProblem, applyProblemChanges } from './sync.js';

const empty = { employees: [], positions: [], settings: {}, overrides: {} };

describe('diffProblem', () => {
  it('emits create / update / delete for employees and positions', () => {
    const prev = {
      employees: [{ id: 'a', firstName: 'A' }, { id: 'b' }],
      positions: [{ id: 'p', name: 'Bar' }],
      settings: { horizonUnit: 'week' }, overrides: {},
    };
    const next = {
      employees: [{ id: 'a', firstName: 'A2' }, { id: 'c' }], // b removed, a changed, c new
      positions: [{ id: 'p', name: 'Bar' }],                  // unchanged
      settings: { horizonUnit: 'week' }, overrides: {},
    };
    const ops = diffProblem(prev, next);
    expect(ops).toContainEqual({ kind: 'employee', action: 'update', id: 'a', body: { id: 'a', firstName: 'A2' } });
    expect(ops).toContainEqual({ kind: 'employee', action: 'create', id: 'c', body: { id: 'c' } });
    expect(ops).toContainEqual({ kind: 'employee', action: 'delete', id: 'b' });
    expect(ops.some((o) => o.kind === 'position')).toBe(false); // position unchanged
  });

  it('emits a settings update only when it changes', () => {
    expect(diffProblem(empty, empty)).toEqual([]);
    const ops = diffProblem(empty, { ...empty, settings: { horizonUnit: 'day' } });
    expect(ops).toEqual([{ kind: 'settings', action: 'update', body: { horizonUnit: 'day' } }]);
  });

  it('emits pin / unpin for changed overrides', () => {
    const prev = { ...empty, overrides: { 't1@2026-06-01': ['a'], 't2@2026-06-02': ['b'] } };
    const next = { ...empty, overrides: { 't1@2026-06-01': ['a', 'c'] } }; // t1 changed, t2 removed
    const ops = diffProblem(prev, next);
    expect(ops).toContainEqual({ kind: 'pin', action: 'pin', templateId: 't1', date: '2026-06-01', employeeIds: ['a', 'c'] });
    expect(ops).toContainEqual({ kind: 'pin', action: 'unpin', templateId: 't2', date: '2026-06-02' });
  });
});

describe('applyProblemChanges', () => {
  const client = () => ({
    createEmployee: vi.fn().mockResolvedValue({ etag: '"0"' }),
    updateEmployee: vi.fn().mockResolvedValue({ etag: '"3"' }),
    deleteEmployee: vi.fn().mockResolvedValue({ etag: null }),
    createPosition: vi.fn().mockResolvedValue({ etag: '"0"' }),
    updatePosition: vi.fn().mockResolvedValue({ etag: '"1"' }),
    deletePosition: vi.fn().mockResolvedValue({ etag: null }),
    updateSettings: vi.fn().mockResolvedValue({ etag: '"5"' }),
    pinOccurrence: vi.fn().mockResolvedValue({ etag: null }),
    unpinOccurrence: vi.fn().mockResolvedValue({ etag: null }),
  });

  it('threads the expected version into each write and records the new one', async () => {
    const c = client();
    const versions = { employees: { a: 2 }, positions: {}, settings: 4 };
    const prev = { employees: [{ id: 'a', firstName: 'A' }], positions: [], settings: { h: 1 }, overrides: {} };
    const next = {
      employees: [{ id: 'a', firstName: 'A2' }, { id: 'c' }],
      positions: [{ id: 'p' }],
      settings: { h: 2 },
      overrides: { 't1@2026-06-01': ['a'] },
    };
    await applyProblemChanges(prev, next, versions, c);

    expect(c.updateEmployee).toHaveBeenCalledWith({ id: 'a', firstName: 'A2' }, 2); // used current version
    expect(c.createEmployee).toHaveBeenCalledWith({ id: 'c' });
    expect(c.createPosition).toHaveBeenCalledWith({ id: 'p' });
    expect(c.updateSettings).toHaveBeenCalledWith({ h: 2 }, 4);
    expect(c.pinOccurrence).toHaveBeenCalledWith('t1', '2026-06-01', ['a']);

    // Versions advanced from the write responses.
    expect(versions.employees.a).toBe(3);
    expect(versions.employees.c).toBe(0);
    expect(versions.positions.p).toBe(0);
    expect(versions.settings).toBe(5);
  });

  it('sends If-Match for a delete and drops the version afterwards', async () => {
    const c = client();
    const versions = { employees: { a: 7 }, positions: {}, settings: 0 };
    await applyProblemChanges(
      { employees: [{ id: 'a' }], positions: [], settings: {}, overrides: {} }, empty, versions, c);
    expect(c.deleteEmployee).toHaveBeenCalledWith('a', 7);
    expect(versions.employees.a).toBeUndefined();
  });

  it('propagates a 409 so the caller can reload', async () => {
    const c = client();
    c.updateEmployee.mockRejectedValue(Object.assign(new Error('conflict'), { status: 409 }));
    const versions = { employees: { a: 1 }, positions: {}, settings: 0 };
    await expect(applyProblemChanges(
      { employees: [{ id: 'a', firstName: 'A' }], positions: [], settings: {}, overrides: {} },
      { employees: [{ id: 'a', firstName: 'B' }], positions: [], settings: {}, overrides: {} },
      versions, c)).rejects.toMatchObject({ status: 409 });
  });
});
