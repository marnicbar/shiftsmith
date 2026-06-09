// Unit tests for the pure date/time helpers in data.js. These underpin every view
// (calendar, shift plan, settings) and must agree with the backend's date maths.
import { describe, it, expect } from 'vitest';
import { SS } from './data.js';

describe('SS.pad', () => {
  it('zero-pads single digits to width 2', () => {
    expect(SS.pad(0)).toBe('00');
    expect(SS.pad(7)).toBe('07');
    expect(SS.pad(12)).toBe('12');
  });
});

describe('SS.isoOf / SS.parseISO', () => {
  it('formats a Date as YYYY-MM-DD', () => {
    expect(SS.isoOf(new Date(2026, 5, 3))).toBe('2026-06-03'); // month is 0-based
  });

  it('round-trips through parseISO at local midnight', () => {
    const d = SS.parseISO('2026-06-03');
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(5);
    expect(d.getDate()).toBe(3);
    expect(SS.isoOf(d)).toBe('2026-06-03');
  });
});

describe('SS.startOfWeek', () => {
  it('returns the Monday of the given week at midnight', () => {
    const wed = new Date(2026, 5, 3); // Wednesday 2026-06-03
    const mon = SS.startOfWeek(wed);
    expect(SS.isoOf(mon)).toBe('2026-06-01');
    expect(mon.getHours()).toBe(0);
  });

  it('keeps a Monday as the same day', () => {
    expect(SS.isoOf(SS.startOfWeek(new Date(2026, 5, 1)))).toBe('2026-06-01');
  });

  it('maps Sunday back to the preceding Monday', () => {
    expect(SS.isoOf(SS.startOfWeek(new Date(2026, 5, 7)))).toBe('2026-06-01');
  });
});

describe('SS.addDays', () => {
  it('shifts a date forwards and backwards across month boundaries', () => {
    expect(SS.isoOf(SS.addDays(new Date(2026, 5, 30), 1))).toBe('2026-07-01');
    expect(SS.isoOf(SS.addDays(new Date(2026, 5, 1), -1))).toBe('2026-05-31');
  });
});

describe('SS.minLabel (24h)', () => {
  it('formats minutes-from-midnight as HH:MM', () => {
    expect(SS.minLabel(0)).toBe('00:00');
    expect(SS.minLabel(540)).toBe('09:00');
    expect(SS.minLabel(1020)).toBe('17:00');
    expect(SS.minLabel(570)).toBe('09:30');
  });
});

describe('SS.min12 (12h)', () => {
  it('formats midnight and noon as 12 AM / 12 PM', () => {
    expect(SS.min12(0)).toBe('12 AM');
    expect(SS.min12(720)).toBe('12 PM');
  });

  it('formats morning and evening hours', () => {
    expect(SS.min12(540)).toBe('9 AM');
    expect(SS.min12(1380)).toBe('11 PM');
  });

  it('includes minutes when not on the hour', () => {
    expect(SS.min12(570)).toBe('9:30 AM');
  });
});

describe('SS.shiftSkills', () => {
  it('reads the skills array, falling back to a single skill', () => {
    expect(SS.shiftSkills({ skills: ['Bar', 'Floor'] })).toEqual(['Bar', 'Floor']);
    expect(SS.shiftSkills({ skill: 'Kitchen' })).toEqual(['Kitchen']);
    expect(SS.shiftSkills({})).toEqual([]);
  });
});

describe('SS.fullName', () => {
  const e = { firstName: 'Ada', lastName: 'Lovelace' };
  it('joins first then last by default', () => {
    expect(SS.fullName(e)).toBe('Ada Lovelace');
    expect(SS.fullName(e, 'first')).toBe('Ada Lovelace');
  });
  it('renders "Last, First" when ordered by last name', () => {
    expect(SS.fullName(e, 'last')).toBe('Lovelace, Ada');
  });
  it('handles a missing name part without stray separators', () => {
    expect(SS.fullName({ firstName: 'Ada', lastName: '' })).toBe('Ada');
    expect(SS.fullName({ firstName: '', lastName: 'Lovelace' }, 'last')).toBe('Lovelace');
    expect(SS.fullName({ firstName: '', lastName: '' })).toBe('');
    expect(SS.fullName(null)).toBe('');
  });
});

describe('SS.empInitials', () => {
  it('takes the first letter of each name, uppercased', () => {
    expect(SS.empInitials({ firstName: 'ada', lastName: 'lovelace' })).toBe('AL');
    expect(SS.empInitials({ firstName: 'Bo', lastName: '' })).toBe('B');
    expect(SS.empInitials({ firstName: '', lastName: '' })).toBe('?');
  });
});

describe('SS.nameSeed', () => {
  it('is stable regardless of display order', () => {
    const e = { firstName: 'Ada', lastName: 'Lovelace' };
    expect(SS.nameSeed(e)).toBe('Ada Lovelace');
  });
});

describe('SS.compareNames', () => {
  const a = { firstName: 'Ada', lastName: 'Zane' };
  const b = { firstName: 'Bo', lastName: 'Apex' };
  it('orders by first name by default', () => {
    expect(SS.compareNames(a, b, 'first')).toBeLessThan(0);
  });
  it('orders by last name when requested', () => {
    expect(SS.compareNames(a, b, 'last')).toBeGreaterThan(0); // Zane after Apex
  });
});
