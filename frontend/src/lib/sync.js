// sync.js — turn a whole-problem edit into granular, concurrency-safe API calls
// (issue #47, Phase 4), replacing the bulk PUT /api/problem. `diffProblem` is a pure
// function (unit-tested); `applyProblemChanges` runs the diff against the backend,
// threading each resource's version (ETag) so a stale write is a 409, not a silent
// overwrite.
import * as api from './api.js';

const keyById = (list = []) => Object.fromEntries(list.map((x) => [x.id, x]));
const num = (etag) => {
  const n = parseInt(String(etag ?? '').replace(/\D/g, ''), 10);
  return Number.isNaN(n) ? 0 : n;
};

/** The ordered operations that turn problem snapshot `prev` into `next`. Pure — no I/O. */
export function diffProblem(prev, next) {
  const ops = [];
  diffList('employee', prev.employees, next.employees, ops);
  diffList('position', prev.positions, next.positions, ops);
  if (JSON.stringify(prev.settings) !== JSON.stringify(next.settings)) {
    ops.push({ kind: 'settings', action: 'update', body: next.settings });
  }
  diffOverrides(prev.overrides || {}, next.overrides || {}, ops);
  return ops;
}

function diffList(kind, prevList, nextList, ops) {
  const a = keyById(prevList);
  const b = keyById(nextList);
  for (const id of Object.keys(b)) {
    if (!(id in a)) ops.push({ kind, action: 'create', id, body: b[id] });
    else if (JSON.stringify(a[id]) !== JSON.stringify(b[id])) ops.push({ kind, action: 'update', id, body: b[id] });
  }
  for (const id of Object.keys(a)) if (!(id in b)) ops.push({ kind, action: 'delete', id });
}

function diffOverrides(prev, next, ops) {
  for (const key of Object.keys(next)) {
    if (JSON.stringify(prev[key]) !== JSON.stringify(next[key])) {
      const { templateId, date } = splitKey(key);
      if (templateId) ops.push({ kind: 'pin', action: 'pin', templateId, date, employeeIds: next[key] });
    }
  }
  for (const key of Object.keys(prev)) {
    if (!(key in next)) {
      const { templateId, date } = splitKey(key);
      if (templateId) ops.push({ kind: 'pin', action: 'unpin', templateId, date });
    }
  }
}

function splitKey(key) {
  const at = key.lastIndexOf('@');
  if (at < 0) return {};
  return { templateId: key.slice(0, at), date: key.slice(at + 1) };
}

/**
 * Apply the diff against the backend, mutating `versions` in place as each write lands
 * (so a mid-sequence failure leaves an accurate version map for the retry). Throws on
 * the first failed call — the caller recovers (a 409 means reload). Returns `versions`.
 */
export async function applyProblemChanges(prev, next, versions, client = api) {
  versions.employees = versions.employees || {};
  versions.positions = versions.positions || {};
  for (const op of diffProblem(prev, next)) {
    if (op.kind === 'employee') {
      if (op.action === 'create') versions.employees[op.id] = num((await client.createEmployee(op.body)).etag);
      else if (op.action === 'update') versions.employees[op.id] = num((await client.updateEmployee(op.body, versions.employees[op.id])).etag);
      else { await client.deleteEmployee(op.id, versions.employees[op.id]); delete versions.employees[op.id]; }
    } else if (op.kind === 'position') {
      if (op.action === 'create') versions.positions[op.id] = num((await client.createPosition(op.body)).etag);
      else if (op.action === 'update') versions.positions[op.id] = num((await client.updatePosition(op.body, versions.positions[op.id])).etag);
      else { await client.deletePosition(op.id, versions.positions[op.id]); delete versions.positions[op.id]; }
    } else if (op.kind === 'settings') {
      versions.settings = num((await client.updateSettings(op.body, versions.settings || 0)).etag);
    } else if (op.kind === 'pin') {
      if (op.action === 'pin') await client.pinOccurrence(op.templateId, op.date, op.employeeIds);
      else await client.unpinOccurrence(op.templateId, op.date);
    }
  }
  return versions;
}
