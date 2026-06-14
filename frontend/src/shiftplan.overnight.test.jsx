// Regression: the Shift Plan overview timeline must split overnight shifts in the
// fit (day/week) views — a head on the start day (clipped at midnight) plus a tail
// at the start of the next day — while the continuous view keeps one spanning bar.
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
  head: container.querySelectorAll('.bar.seg-head').length,
  tail: container.querySelectorAll('.bar.seg-tail').length,
  full: container.querySelectorAll('.bar:not(.seg-head):not(.seg-tail)').length,
});

describe('Shift Plan timeline overnight', () => {
  it('day view splits the overnight shift into a head and an incoming tail', () => {
    const { container } = renderTL('day');
    const { head, tail, full } = c(container);
    expect(head).toBe(1);  // Night, 22:00→24:00 clipped at the day edge
    expect(tail).toBe(1);  // Night, 00:00→02:00 carried over from the previous day
    expect(full).toBe(1);  // the daytime control shift, unsplit
  });

  it('week view draws an incoming tail on every day for a daily overnight shift', () => {
    const { container } = renderTL('week');
    const { head, tail } = c(container);
    expect(head).toBe(7); // one head per day
    expect(tail).toBe(7); // each day also shows the prior day's overnight tail
  });

  it('continuous view keeps a single spanning bar (no tail segments)', () => {
    const { container } = renderTL('free');
    expect(container.querySelectorAll('.bar.seg-tail').length).toBe(0);
  });
});
