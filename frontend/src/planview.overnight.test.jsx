// Regression: overnight assignments must render their post-midnight tail in the
// read-only day/week calendars, including on the day *after* the shift's start day.
// The per-person / per-position views feed the Calendar events built over the
// visible range plus a one-day lead-in, so the Calendar can split an overnight
// occurrence anchored just before the range into head (start day) + tail (next day).
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { Calendar } from './calendar.jsx';
import { buildPositionEvents } from './planview.jsx';

const MON = '2026-06-01';
const TUE = '2026-06-02';
const noop = () => {};
const overnight = { id: 's1', name: 'Night', date: MON, start: 1320, end: 120, headcount: 1, repeat: 'none', skills: [], preferred: [] };
const pos = { id: 'p1', name: 'Bar', color: 200, shifts: [overnight] };
const assign = { [`s1@${MON}`]: [{ id: 'e1', firstName: 'A', lastName: 'B', skills: [] }] };

function eventsByDay(container) {
  const out = {};
  container.querySelectorAll('[data-daycol]').forEach((c) => {
    out[c.getAttribute('data-daycol')] = c.querySelectorAll('.evt').length;
  });
  return out;
}

describe('overnight read-only calendar rendering', () => {
  it('day view of the day after an overnight start shows the tail', () => {
    // The view shows only Tuesday; the builder is fed [MON, TUE] (the lead-in day)
    // so the Monday-anchored overnight occurrence can spill its tail into Tuesday.
    const events = buildPositionEvents(pos, [MON, TUE], assign, { nameOrder: 'first', t: (k) => k });
    const { container } = render(
      <Calendar kind="assign-position" readOnly view="day" anchor={new Date(Date.UTC(2026, 5, 2, 12))}
        items={events} zoom={46} onZoom={noop} palette={[]} newItem={() => ({})}
        onCommit={noop} onDelete={noop} onSplit={noop} />,
    );
    expect(eventsByDay(container)[TUE]).toBe(1); // the post-midnight tail
  });

  it('week view splits an overnight occurrence into head + tail across the seam', () => {
    const events = buildPositionEvents(pos, [MON, TUE], assign, { nameOrder: 'first', t: (k) => k });
    const { container } = render(
      <Calendar kind="assign-position" readOnly view="week" anchor={new Date(Date.UTC(2026, 5, 1, 12))}
        items={events} zoom={46} onZoom={noop} palette={[]} newItem={() => ({})}
        onCommit={noop} onDelete={noop} onSplit={noop} />,
    );
    const byDay = eventsByDay(container);
    expect(byDay[MON]).toBe(1); // head, 22:00–24:00
    expect(byDay[TUE]).toBe(1); // tail, 00:00–02:00
  });
});
