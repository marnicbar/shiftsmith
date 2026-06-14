// Regression: the Shift Plan overview timeline only splits an overnight shift at the
// edge of the visible range — the right edge where it runs off-screen, and the left
// edge where a shift carries in from before the range. Interior day borders are spanned
// by one continuous bar, and the continuous view never splits (nor flattens) at all.
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { ShiftPlan } from './shiftplan.jsx';

const MON = '2026-06-01';
const emp = { id: 'e1', firstName: 'A', lastName: 'B', skills: [] };
// A daily overnight shift (22:00→02:00) and a daily daytime control (09:00→17:00).
const positions = [{ id: 'p1', name: 'Bar', color: 200, group: null, shifts: [
  { id: 's1', name: 'Night', date: MON, start: 1320, end: 120, headcount: 1, repeat: 'daily', skills: [], preferred: [] },
  { id: 's2', name: 'Day', date: MON, start: 540, end: 1020, headcount: 1, repeat: 'daily', skills: [], preferred: [] },
]}];

const renderTL = (mode) => render(
  <ShiftPlan employees={[emp]} positions={positions} assign={{}} overrides={{}}
    setOverrides={() => {}} initialMode={mode} sched={{}} />,
);
const c = (container) => ({
  head: container.querySelectorAll('.bar.seg-head').length, // clipped at the right edge
  tail: container.querySelectorAll('.bar.seg-tail').length, // carried in at the left edge
  full: container.querySelectorAll('.bar:not(.seg-head):not(.seg-tail)').length,
});

describe('Shift Plan timeline overnight', () => {
  it('day view splits the overnight shift at the single day edge (run-off + carry-in)', () => {
    const { container } = renderTL('day');
    const { head, tail, full } = c(container);
    expect(head).toBe(1);  // Night, 22:00→24:00 running off the right edge
    expect(tail).toBe(1);  // Night, 00:00→02:00 carried in from the previous day
    expect(full).toBe(1);  // the daytime control shift, unsplit
  });

  it('week view splits the daily overnight shift only at the two week edges', () => {
    const { container } = renderTL('week');
    const { head, tail } = c(container);
    expect(head).toBe(1); // only the last day runs off the right edge
    expect(tail).toBe(1); // only the first day carries in from the prior day
    // The interior nights span their day border as one continuous (unsplit) bar:
    // 6 spanning Night bars (Mon–Sat) + 7 daytime bars = 13 unsplit.
    expect(container.querySelectorAll('.bar:not(.seg-head):not(.seg-tail)').length).toBe(13);
  });

  it('continuous view never splits or flattens an overnight bar', () => {
    const { container } = renderTL('free');
    expect(container.querySelectorAll('.bar.seg-tail').length).toBe(0);
    expect(container.querySelectorAll('.bar.seg-head').length).toBe(0);
  });
});
