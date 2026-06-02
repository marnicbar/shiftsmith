// Tests for the pure scheduling helpers in shiftplan.jsx:
//  - matchesDay: recurrence logic that must mirror the backend's Recurrence/occursOn
//  - buildPlan:  the frontend's local greedy assignment preview (skills, vacation,
//                headcount, preferred ordering, overrides, no double-booking)
import { describe, it, expect } from 'vitest';
import { matchesDay, buildPlan } from './shiftplan.jsx';

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

  it('respects until (inclusive) and except', () => {
    expect(matchesDay({ repeat: 'daily', date: MON, until: TUE }, '2026-06-03')).toBe(false);
    expect(matchesDay({ repeat: 'daily', date: MON, except: [TUE] }, TUE)).toBe(false);
  });
});

// --- buildPlan -------------------------------------------------------------

const emp = (id, skills, blocks = []) => ({ id, name: id, skills, blocks });
const shift = (id, over = {}) => ({
  id, date: MON, start: 1020, end: 1440, skills: ['Bar'], headcount: 1,
  repeat: 'none', preferred: [], ...over,
});
const position = (shifts) => ({ id: 'p', name: 'P', shifts });

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
