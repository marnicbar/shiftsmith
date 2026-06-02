// rules.test.jsx — the pure helpers behind global/system working-time rules.
import { describe, it, expect } from 'vitest';
import { tooLooseAgainst, ruleKey, ruleEffectiveAt } from './rules.jsx';

describe('tooLooseAgainst', () => {
  it('flags a personal ceiling above the global one and returns the clamp value', () => {
    expect(tooLooseAgainst('max', 12, 10)).toBe(10); // 12h/day > system 10h → must drop to 10
    expect(tooLooseAgainst('max', 8, 10)).toBeNull(); // already stricter
    expect(tooLooseAgainst('max', 10, 10)).toBeNull(); // equal is allowed
  });

  it('flags a personal floor below the global one', () => {
    expect(tooLooseAgainst('min', 6, 8)).toBe(8);  // 6h < system 8h → must raise to 8
    expect(tooLooseAgainst('min', 10, 8)).toBeNull();
  });

  it('never constrains a preference', () => {
    expect(tooLooseAgainst('preferred', 60, 30)).toBeNull();
    expect(tooLooseAgainst('preferred', 5, 30)).toBeNull();
  });
});

describe('ruleKey', () => {
  it('identifies a rule by metric+op so each can be defined once', () => {
    expect(ruleKey({ metric: 'weekHours', op: 'max' })).toBe('weekHours:max');
    expect(ruleKey({ metric: 'weekHours', op: 'max' })).toBe(ruleKey({ metric: 'weekHours', op: 'max', value: 99 }));
  });
});

// Mirrors the backend Rule.effectiveAt — keep the two in lock-step.
describe('ruleEffectiveAt', () => {
  const base = { metric: 'weekHours', op: 'max', value: 40, changes: [] };

  it('returns the base value when there are no scheduled changes', () => {
    expect(ruleEffectiveAt(base, '2026-06-01')).toEqual({ active: true, metric: 'weekHours', op: 'max', value: 40 });
  });

  it('applies a set change only on or after its date', () => {
    const r = { ...base, changes: [{ id: 'c1', date: '2026-06-10', kind: 'set', metric: 'weekHours', op: 'max', value: 30 }] };
    expect(ruleEffectiveAt(r, '2026-06-09').value).toBe(40);
    expect(ruleEffectiveAt(r, '2026-06-10').value).toBe(30);
  });

  it('takes the latest applicable change by date, not list order', () => {
    const r = { ...base, changes: [
      { id: 'c2', date: '2026-06-15', kind: 'set', value: 20 },
      { id: 'c1', date: '2026-06-05', kind: 'set', value: 30 },
    ] };
    expect(ruleEffectiveAt(r, '2026-06-10').value).toBe(30);
    expect(ruleEffectiveAt(r, '2026-06-20').value).toBe(20);
  });

  it('deactivates the rule from a remove change', () => {
    const r = { ...base, changes: [{ id: 'c1', date: '2026-06-07', kind: 'remove' }] };
    expect(ruleEffectiveAt(r, '2026-06-06').active).toBe(true);
    expect(ruleEffectiveAt(r, '2026-06-07').active).toBe(false);
  });
});
