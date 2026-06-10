// Tests for the pure assignment-event builders that back the Shift Plan tab's
// per-position and per-person calendars. They turn the solver's assignment map
// (shiftId@date → [employee]) into concrete, read-only calendar events.
import { describe, it, expect } from 'vitest';
import { buildPositionEvents, buildPersonEvents } from './planview.jsx';

const MON = '2026-06-01'; // Monday
const TUE = '2026-06-02';
const WED = '2026-06-03';

const emp = (id) => ({ id, firstName: id, lastName: '', skills: [] });
const shift = (id, over = {}) => ({ id, name: id, date: MON, start: 540, end: 1020, headcount: 1, repeat: 'none', ...over });
const position = (over = {}) => ({ id: 'p1', name: 'Bar', color: 200, shifts: [], ...over });

describe('buildPositionEvents', () => {
  it('emits one event per assigned person on a shift occurrence', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 2 })] });
    const assign = { [`s1@${MON}`]: [emp('a'), emp('b')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    expect(evs).toHaveLength(2);
    expect(evs.map((e) => e._label).sort()).toEqual(['a', 'b']);
    expect(evs.every((e) => e._tone === 'assign' && e.date === MON && e.start === 540 && e.end === 1020)).toBe(true);
  });

  it('adds a single "open" event when the occurrence is understaffed', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 3 })] });
    const assign = { [`s1@${MON}`]: [emp('a')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    const open = evs.filter((e) => e._tone === 'open');
    expect(open).toHaveLength(1);
    expect(open[0].open).toBe(2);
    expect(evs.filter((e) => e._tone === 'assign')).toHaveLength(1);
  });

  it('emits no open event when fully staffed', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 1 })] });
    const assign = { [`s1@${MON}`]: [emp('a')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    expect(evs.filter((e) => e._tone === 'open')).toHaveLength(0);
  });

  it('shows an all-open occurrence when nobody is assigned', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 2 })] });
    const evs = buildPositionEvents(pos, [MON], {});
    expect(evs).toHaveLength(1);
    expect(evs[0]).toMatchObject({ _tone: 'open', open: 2 });
  });

  it('expands recurrence across the day list and keys assignments per date', () => {
    const pos = position({ shifts: [shift('s1', { repeat: 'daily', headcount: 1 })] });
    const assign = { [`s1@${MON}`]: [emp('a')], [`s1@${TUE}`]: [emp('b')] };
    const evs = buildPositionEvents(pos, [MON, TUE, WED], assign);
    const byDate = Object.fromEntries(evs.map((e) => [e.date, e]));
    expect(byDate[MON]._label).toBe('a');
    expect(byDate[TUE]._label).toBe('b');
    expect(byDate[WED]._tone).toBe('open'); // recurs but nobody assigned that day
  });

  it('returns nothing for a missing position', () => {
    expect(buildPositionEvents(null, [MON], {})).toEqual([]);
  });
});

describe('buildPersonEvents', () => {
  const positions = [
    position({ id: 'p1', name: 'Bar', color: 200, shifts: [shift('s1')] }),
    position({ id: 'p2', name: 'Floor', color: 40, shifts: [shift('s2', { start: 600, end: 720 })] }),
  ];

  it('collects the shifts a person is assigned, across positions, coloured by position', () => {
    const assign = { [`s1@${MON}`]: [emp('me')], [`s2@${MON}`]: [emp('other')] };
    const evs = buildPersonEvents(emp('me'), positions, [MON], assign);
    expect(evs).toHaveLength(1);
    expect(evs[0]).toMatchObject({ _label: 'Bar', positionId: 'p1', date: MON, _tone: 'assign' });
    expect(evs[0]._color).toContain('200'); // owning position's hue
  });

  it('includes every position the person works that day', () => {
    const me = emp('me');
    const assign = { [`s1@${MON}`]: [me], [`s2@${MON}`]: [me] };
    const evs = buildPersonEvents(me, positions, [MON], assign);
    expect(evs.map((e) => e._label).sort()).toEqual(['Bar', 'Floor']);
  });

  it('ignores occurrences the person is not assigned to', () => {
    const assign = { [`s1@${MON}`]: [emp('someone-else')] };
    expect(buildPersonEvents(emp('me'), positions, [MON], assign)).toEqual([]);
  });

  it('returns nothing for a missing employee', () => {
    expect(buildPersonEvents(null, positions, [MON], {})).toEqual([]);
  });
});
