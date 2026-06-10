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
  it('emits one combined event per occurrence listing every assigned person', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 2 })] });
    const assign = { [`s1@${MON}`]: [emp('a'), emp('b')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    expect(evs).toHaveLength(1);
    expect(evs[0]).toMatchObject({ _tone: 'assign', date: MON, start: 540, end: 1020, open: 0, _openLabel: null });
    expect(evs[0]._lines).toEqual(['a', 'b']);
    expect(evs[0]._timeLabel).toBe('09:00–17:00'); // from–to, not just the start
    expect(evs[0]._segments).toHaveLength(2); // one colour per assigned person
  });

  it('keeps a single event when understaffed, with an open line and muted bar segments', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 3 })] });
    const assign = { [`s1@${MON}`]: [emp('a')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    expect(evs).toHaveLength(1);
    expect(evs[0]).toMatchObject({ _tone: 'assign', open: 2 });
    expect(evs[0]._lines).toEqual(['a']);
    expect(evs[0]._openLabel).toBeTruthy();
    expect(evs[0]._segments).toHaveLength(3); // 1 person + 2 open slots
  });

  it('omits the open line when fully staffed', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 1 })] });
    const assign = { [`s1@${MON}`]: [emp('a')] };
    const evs = buildPositionEvents(pos, [MON], assign);
    expect(evs[0]._openLabel).toBeNull();
    expect(evs[0]._segments).toHaveLength(1);
  });

  it('shows an all-open occurrence (no split bar) when nobody is assigned', () => {
    const pos = position({ shifts: [shift('s1', { headcount: 2 })] });
    const evs = buildPositionEvents(pos, [MON], {});
    expect(evs).toHaveLength(1);
    expect(evs[0]).toMatchObject({ _tone: 'open', open: 2, _segments: null });
    expect(evs[0]._lines).toEqual([]);
  });

  it('expands recurrence across the day list and keys assignments per date', () => {
    const pos = position({ shifts: [shift('s1', { repeat: 'daily', headcount: 1 })] });
    const assign = { [`s1@${MON}`]: [emp('a')], [`s1@${TUE}`]: [emp('b')] };
    const evs = buildPositionEvents(pos, [MON, TUE, WED], assign);
    const byDate = Object.fromEntries(evs.map((e) => [e.date, e]));
    expect(byDate[MON]._lines).toEqual(['a']);
    expect(byDate[TUE]._lines).toEqual(['b']);
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
