// Smoke test: the Shift Plan tab's scope selector switches between the timeline
// overview and the two read-only assignment calendars.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlanView } from './planview.jsx';

const employees = [{ id: 'e1', firstName: 'Alice', lastName: 'Ng', skills: [], blocks: [], rules: [] }];
const positions = [{
  id: 'p1', name: 'Barback', color: 200, skills: [], shifts: [
    { id: 's1', name: 'Open', date: '2026-06-01', start: 540, end: 1020, headcount: 2, repeat: 'daily', skills: [], preferred: [] },
  ],
}];
const assign = { ['s1@' + '2026-06-01']: [employees[0]] };

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
});
