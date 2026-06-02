// rules.test.jsx — the pure helpers behind global/system working-time rules.
import { describe, it, expect } from 'vitest';
import { tooLooseAgainst, ruleKey } from './rules.jsx';

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
