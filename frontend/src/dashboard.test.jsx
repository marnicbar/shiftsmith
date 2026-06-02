// Component smoke test for the Dashboard (an example of the React Testing Library
// pattern: render with props, assert on what the user sees, simulate interaction).
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Dashboard } from './dashboard.jsx';

const employees = [{ id: 'e1' }, { id: 'e2' }, { id: 'e3' }, { id: 'e4' }, { id: 'e5' }];
const positions = [{ id: 'p1', shifts: [] }, { id: 'p2', shifts: [] }];

describe('Dashboard', () => {
  it('renders solver-derived KPIs when a schedule is provided', () => {
    render(
      <Dashboard
        employees={employees}
        positions={positions}
        sched={{ total: 10, staffed: 8, unassigned: 2 }}
        onGo={vi.fn()}
      />,
    );

    // coverage = staffed/total = 80%
    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.getByText('Coverage')).toBeInTheDocument();
    // active people = employees.length
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('Active people')).toBeInTheDocument();
    // unassigned slots from the solver snapshot
    expect(screen.getByText('Unassigned shifts')).toBeInTheDocument();
  });

  it('navigates to the shift plan when "Solve schedule" is clicked', async () => {
    const onGo = vi.fn();
    render(<Dashboard employees={employees} positions={positions} sched={{}} onGo={onGo} />);

    await userEvent.click(screen.getByRole('button', { name: /solve schedule/i }));
    expect(onGo).toHaveBeenCalledWith('shiftplan');
  });
});
