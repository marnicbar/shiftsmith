// Tests for the pure scheduling helpers in shiftplan.jsx:
//  - matchesDay: recurrence logic that must mirror the backend's Recurrence/occursOn
//  - buildPlan:  the frontend's local greedy assignment preview (skills, vacation,
//                headcount, preferred ordering, overrides, no double-booking)
import { describe, it, expect } from 'vitest';
import { matchesDay, buildPlan, availableFor, hourTickStep } from './shiftplan.jsx';

const MON = '2026-06-01'; // Monday
const TUE = '2026-06-02';
const NEXT_MON = '2026-06-08';

describe('matchesDay', () => {
  it('none occurs only on its own date', () => {
    expect(matchesDay({ repeat: 'none', date: MON }, MON)).toBe(true);
    expect(matchesDay({ repeat: 'none', date: MON }, TUE)).toBe(false);
  });

  it('daily occurs on and after the anchor', () => {
    expect(matchesDay({ repeat: 'daily', date: MON }, MON)).toBe(true);
    expect(matchesDay({ repeat: 'daily', date: MON }, NEXT_MON)).toBe(true);
    expect(matchesDay({ repeat: 'daily', date: TUE }, MON)).toBe(false);
  });

  it('weekly occurs on the same weekday on/after the anchor', () => {
    expect(matchesDay({ repeat: 'weekly', date: MON }, NEXT_MON)).toBe(true);
    expect(matchesDay({ repeat: 'weekly', date: MON }, TUE)).toBe(false);
  });

  it('weekly with selected days occurs on each selected weekday (Mon=0 … Sun=6)', () => {
    // anchored on Monday, repeating Mon + Wed (days 0 and 2)
    const WED = '2026-06-03';
    const item = { repeat: 'weekly', date: MON, days: [0, 2] };
    expect(matchesDay(item, MON)).toBe(true);
    expect(matchesDay(item, WED)).toBe(true);
    expect(matchesDay(item, TUE)).toBe(false);
    // does not fire before the anchor date
    expect(matchesDay({ repeat: 'weekly', date: NEXT_MON, days: [0, 2] }, MON)).toBe(false);
  });

  it('respects until (inclusive) and except', () => {
    expect(matchesDay({ repeat: 'daily', date: MON, until: TUE }, '2026-06-03')).toBe(false);
    expect(matchesDay({ repeat: 'daily', date: MON, except: [TUE] }, TUE)).toBe(false);
  });

  it('covers a multi-day vacation on every day in its range (#33)', () => {
    const WED = '2026-06-03';
    const THU = '2026-06-04';
    const vac = { type: 'vac', date: MON, endDate: '2026-06-04' }; // Mon–Thu, no repeat field
    expect(matchesDay(vac, MON)).toBe(true);
    expect(matchesDay(vac, WED)).toBe(true);  // mid-range — used to be missed
    expect(matchesDay(vac, THU)).toBe(true);  // last day
    expect(matchesDay(vac, '2026-06-05')).toBe(false);
  });

  it('treats a missing/null repeat as a single day (matches the backend)', () => {
    expect(matchesDay({ date: MON }, MON)).toBe(true);
    expect(matchesDay({ date: MON }, TUE)).toBe(false);
  });
});

// --- buildPlan -------------------------------------------------------------

const emp = (id, skills, blocks = []) => ({ id, firstName: id, lastName: '', skills, blocks });
const shift = (id, over = {}) => ({
  id, date: MON, start: 1020, end: 1440, skills: ['Bar'], headcount: 1,
  repeat: 'none', preferred: [], ...over,
});
const position = (shifts) => ({ id: 'p', name: 'P', shifts });

// --- availableFor (mirrors backend Employee.isAvailableFor) -----------------

describe('availableFor', () => {
  const block = (type, over = {}) => ({ type, date: MON, repeat: 'none', allDay: false, ...over });
  const sh = (start, end) => ({ start, end });

  it('treats an empty calendar as unavailable', () => {
    expect(availableFor(emp('e1', [], []), sh(600, 720), MON)).toBe(false);
  });

  it('is available when the shift fits inside a pref window', () => {
    const e = emp('e1', [], [block('pref', { start: 480, end: 1080 })]);
    expect(availableFor(e, sh(600, 960), MON)).toBe(true);
  });

  it('is unavailable when the shift spills past the window', () => {
    const e = emp('e1', [], [block('pref', { start: 480, end: 900 })]);
    expect(availableFor(e, sh(600, 960), MON)).toBe(false);
  });

  it('undesired blocks also define availability and merge with adjacent pref', () => {
    const e = emp('e1', [], [block('pref', { start: 480, end: 720 }), block('undes', { start: 720, end: 1080 })]);
    expect(availableFor(e, sh(600, 960), MON)).toBe(true); // spans the seam
  });

  it('an allDay pref block covers the whole day', () => {
    const e = emp('e1', [], [block('pref', { allDay: true })]);
    expect(availableFor(e, sh(0, 1440), MON)).toBe(true);
  });

  it('vacation blocks do not grant availability', () => {
    const e = emp('e1', [], [block('vac', { allDay: true })]);
    expect(availableFor(e, sh(600, 720), MON)).toBe(false);
  });

  it('an overnight window wraps past midnight and covers an overnight shift', () => {
    // 22:00 → 02:00 next day (start > end) — mirrors backend Employee.isAvailableFor.
    const e = emp('e1', [], [block('pref', { start: 1320, end: 120 })]);
    expect(availableFor(e, sh(1320, 120), MON)).toBe(true);  // 22:00–02:00 fits exactly
    expect(availableFor(e, sh(1380, 60), MON)).toBe(true);   // 23:00–01:00 fits inside
    expect(availableFor(e, sh(1260, 120), MON)).toBe(false); // starts 21:00, before window
  });

  it('a daytime window does not cover an overnight shift that spills past midnight', () => {
    const e = emp('e1', [], [block('pref', { start: 1080, end: 1440 })]); // 18:00–24:00
    expect(availableFor(e, sh(1080, 120), MON)).toBe(false); // 18:00–02:00 spills past 24:00
  });

  it('two adjacent day windows across midnight cover an overnight shift', () => {
    // Overnight availability is entered as two adjacent day blocks (one reaching
    // midnight, one starting at midnight); they merge across the seam.
    const e = emp('e1', [], [
      block('pref', { start: 1080, end: 1440 }),        // Mon 18:00–24:00
      block('pref', { date: TUE, start: 0, end: 360 }), // Tue 00:00–06:00
    ]);
    expect(availableFor(e, sh(1080, 360), MON)).toBe(true);  // 18:00–06:00 spans the seam
    expect(availableFor(e, sh(1020, 360), MON)).toBe(false); // starts 17:00, before Mon window
  });
});

describe('buildPlan', () => {
  it('assigns an employee who has every required skill', () => {
    const assign = buildPlan([emp('e1', ['Bar'])], [position([shift('s1')])], [MON]);
    expect(assign['s1@' + MON].map((e) => e.id)).toEqual(['e1']);
  });

  it('leaves a slot empty when no one has the required skills', () => {
    const assign = buildPlan([emp('e1', ['Floor'])], [position([shift('s1', { skills: ['Bar'] })])], [MON]);
    expect(assign['s1@' + MON]).toEqual([]);
  });

  it('excludes employees on vacation that day', () => {
    const onVac = emp('e1', ['Bar'], [{ type: 'vac', date: MON, repeat: 'none', allDay: true }]);
    const assign = buildPlan([onVac], [position([shift('s1')])], [MON]);
    expect(assign['s1@' + MON]).toEqual([]);
  });

  it('fills up to headcount and no further', () => {
    const assign = buildPlan(
      [emp('e1', ['Bar']), emp('e2', ['Bar']), emp('e3', ['Bar'])],
      [position([shift('s1', { headcount: 2 })])],
      [MON],
    );
    expect(assign['s1@' + MON]).toHaveLength(2);
  });

  it('prefers the shift\'s preferred employee', () => {
    const assign = buildPlan(
      [emp('e1', ['Bar']), emp('e2', ['Bar'])],
      [position([shift('s1', { preferred: ['e2'] })])],
      [MON],
    );
    expect(assign['s1@' + MON][0].id).toBe('e2');
  });

  it('does not double-book the same person on the same day', () => {
    // two non-overlapping Bar shifts on MON, only one candidate
    const shifts = [shift('s1', { start: 600, end: 720 }), shift('s2', { start: 800, end: 900 })];
    const assign = buildPlan([emp('e1', ['Bar'])], [position(shifts)], [MON]);
    const e1Slots = Object.values(assign).filter((crew) => crew.some((e) => e.id === 'e1'));
    expect(e1Slots).toHaveLength(1);
  });

  it('honours manual overrides verbatim, ignoring skills and preferences', () => {
    const overrides = { ['s1@' + MON]: ['e1'] };
    const assign = buildPlan(
      [emp('e1', []), emp('e2', ['Bar'])],
      [position([shift('s1', { preferred: ['e2'] })])],
      [MON],
      overrides,
    );
    expect(assign['s1@' + MON].map((e) => e.id)).toEqual(['e1']);
  });
});

describe('hourTickStep', () => {
  it('always returns a divisor of 24 so ticks align to every day boundary', () => {
    // Sweep a realistic range of pixels-per-hour, including the zoom levels that
    // previously produced a step of 5 (a non-divisor that drifts day-to-day).
    for (let pph = 2; pph <= 180; pph += 0.5) {
      expect(24 % hourTickStep(pph)).toBe(0);
    }
  });

  it('keeps adjacent ticks at least ~46px apart', () => {
    for (let pph = 2; pph <= 180; pph += 0.5) {
      const step = hourTickStep(pph);
      // Either the spacing clears the 46px threshold, or we are already at the
      // coarsest divisor (a full day) and cannot space them out further.
      expect(step * pph >= 46 || step === 24).toBe(true);
    }
  });

  it('regression: the ~9-11px zoom band snaps to 6, not 5', () => {
    // ceil(46/effPph) used to give 5 here, which does not divide 24.
    expect(hourTickStep(10)).toBe(6);
    expect(hourTickStep(9.5)).toBe(6);
  });
});
