// Smoke test: PlanView renders the right sub-view for each scope (the scope
// selector itself lives in the top nav's morphing Shift Plan tab).
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PlanView } from './planview.jsx';

// The read-only calendars fetch the visible range from the durable store; stub it as
// a never-settling call so these synchronous smoke assertions render off the live
// `assign` prop (the overlay) without a real network request.
vi.mock('./lib/api.js', () => ({ getScheduleRange: vi.fn(() => new Promise(() => {})) }));

// Anchor the fixture shift on the real "today" so it falls inside the calendars'
// default week view (which anchors on new Date()), making rendered-event
// assertions deterministic regardless of when the suite runs.
const pad = (n) => String(n).padStart(2, '0');
const td = new Date();
const TODAY = `${td.getFullYear()}-${pad(td.getMonth() + 1)}-${pad(td.getDate())}`;

const employees = [{ id: 'e1', firstName: 'Alice', lastName: 'Ng', skills: [], blocks: [], rules: [] }];
const positions = [{
  id: 'p1', name: 'Barback', color: 200, skills: [], shifts: [
    { id: 's1', name: 'Open', date: TODAY, start: 540, end: 1020, headcount: 2, repeat: 'none', skills: [], preferred: [] },
  ],
}];
const assign = { [`s1@${TODAY}`]: [employees[0]] };

function renderPlan(scope) {
  return render(
    <PlanView scope={scope} employees={employees} positions={positions} assign={assign}
      selEmp="e1" setSelEmp={vi.fn()} selPos="p1" setSelPos={vi.fn()}
      groupOrder={[]} initialMode="week" overrides={{}} setOverrides={vi.fn()}
      sched={{ total: 2, staffed: 1, unassigned: 1, solverStatus: 'NOT_SOLVING' }}
      onSolve={vi.fn()} onPause={vi.fn()} nameOrder="first" />,
  );
}

describe('PlanView scopes', () => {
  it('overview renders the timeline with solver controls', () => {
    renderPlan('overview');
    expect(screen.getByRole('button', { name: /Solve now/ })).toBeInTheDocument();
  });

  it('personnel renders the read-only per-person calendar', () => {
    renderPlan('personnel');
    expect(screen.getAllByText('Alice Ng').length).toBeGreaterThan(0); // rail + summary
    expect(screen.getByText('Assigned shifts')).toBeInTheDocument();
    // read-only: no overview solver controls, no calendar "Add" button
    expect(screen.queryByRole('button', { name: /Solve now/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add' })).not.toBeInTheDocument();
  });

  it('positions renders one combined entry with the assignee, a from–to time and an open line', () => {
    renderPlan('positions');
    expect(screen.getByText('Filled slots')).toBeInTheDocument();
    expect(screen.getByText('Open slots')).toBeInTheDocument();
    // headcount 2, one assignee → a single event listing the name, the full time
    // range (not just the start) and the remaining open slot.
    expect(screen.getAllByText('Alice Ng').length).toBeGreaterThan(0);
    expect(screen.getByText('09:00–17:00')).toBeInTheDocument();
    expect(screen.getByText('1 open')).toBeInTheDocument();
  });
});
