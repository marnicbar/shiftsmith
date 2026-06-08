// Component smoke test for the Dashboard (an example of the React Testing Library
// pattern: render with props, assert on what the user sees, simulate interaction).
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Dashboard } from './dashboard.jsx';
import { SS } from './data.js';

// A single shift needing 2 people on today, so the week/month always covers it.
const today = SS.isoOf(new Date());
const positions = [
  { id: 'p1', name: 'Bar', color: '20', shifts: [
    { id: 's1', name: 'Evening', start: 1020, end: 1320, headcount: 2, skills: [], repeat: 'none', date: today },
  ] },
];
const employees = [
  { id: 'e1', firstName: 'Ann', lastName: '', skills: [], blocks: [] },
  { id: 'e2', firstName: 'Bo', lastName: '', skills: [], blocks: [{ type: 'vac', repeat: 'none', date: today, allDay: true }] },
];

describe('Dashboard', () => {
  it('derives KPIs from the real plan for the current week', () => {
    // One of the two slots on the daily shift is filled by the solver.
    const assign = { [`s1@${today}`]: [employees[0]] };
    render(<Dashboard employees={employees} positions={positions} assign={assign} onOpenShift={vi.fn()} />);

    // Real headline metrics are present.
    expect(screen.getByText('Shifts')).toBeInTheDocument();
    // "Unassigned shifts" appears as both the KPI label and the list heading.
    expect(screen.getAllByText('Unassigned shifts').length).toBeGreaterThan(0);
    expect(screen.getByText('Coverage')).toBeInTheDocument();
    // One person is on vacation today.
    expect(screen.getByText('On vacation')).toBeInTheDocument();
  });

  it('lists unassigned shifts and jumps to one when clicked', async () => {
    const assign = {}; // nothing assigned → today's occurrence is under-staffed
    const onOpenShift = vi.fn();
    render(<Dashboard employees={employees} positions={positions} assign={assign} onOpenShift={onOpenShift} />);

    const row = await screen.findByRole('button', { name: /Evening/ });
    await userEvent.click(row);
    expect(onOpenShift).toHaveBeenCalledWith('s1', today);
  });

  it('switches between week and month views', async () => {
    render(<Dashboard employees={employees} positions={positions} assign={{}} onOpenShift={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Month' }));
    // Month label includes the year.
    expect(screen.getByText(new RegExp(String(new Date().getFullYear())))).toBeInTheDocument();
  });
});
