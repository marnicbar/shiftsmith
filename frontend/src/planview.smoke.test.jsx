// Smoke test: the Shift Plan tab's scope selector switches between the timeline
// overview and the two read-only assignment calendars.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlanView } from './planview.jsx';

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

function renderPlan() {
  return render(
    <PlanView employees={employees} positions={positions} assign={assign}
      selEmp="e1" setSelEmp={vi.fn()} selPos="p1" setSelPos={vi.fn()}
      groupOrder={[]} initialMode="week" overrides={{}} setOverrides={vi.fn()}
      sched={{ total: 2, staffed: 1, unassigned: 1, solverStatus: 'NOT_SOLVING' }}
      onSolve={vi.fn()} onPause={vi.fn()} nameOrder="first" />,
  );
}

describe('PlanView scope selector', () => {
  it('starts on the timeline overview', () => {
    renderPlan();
    // The overview carries the solver controls; the calendars do not.
    expect(screen.getByRole('button', { name: /Solve now/ })).toBeInTheDocument();
  });

  it('switches to the per-person calendar', () => {
    renderPlan();
    fireEvent.click(screen.getByRole('button', { name: 'Personnel' }));
    expect(screen.getAllByText('Alice Ng').length).toBeGreaterThan(0); // rail + summary
    expect(screen.getByText('Assigned shifts')).toBeInTheDocument();
    // read-only: no overview solver controls, no calendar "Add" button
    expect(screen.queryByRole('button', { name: /Solve now/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add' })).not.toBeInTheDocument();
  });

  it('switches to the per-position calendar and surfaces open slots', () => {
    renderPlan();
    fireEvent.click(screen.getByRole('button', { name: 'Positions' }));
    expect(screen.getByText('Filled slots')).toBeInTheDocument();
    expect(screen.getByText('Open slots')).toBeInTheDocument();
  });

  it('renders one combined entry with the assignee, a from–to time and an open line', () => {
    renderPlan();
    fireEvent.click(screen.getByRole('button', { name: 'Positions' }));
    // headcount 2, one assignee → a single event listing the name, the full time
    // range (not just the start) and the remaining open slot.
    expect(screen.getAllByText('Alice Ng').length).toBeGreaterThan(0);
    expect(screen.getByText('09:00–17:00')).toBeInTheDocument();
    expect(screen.getByText('1 open')).toBeInTheDocument();
  });
});
