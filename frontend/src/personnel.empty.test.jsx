// Regression: a fresh/empty database has no employees or positions. The editor
// panes must render an empty state instead of crashing on an undefined selection
// (previously `emp.blocks` / `pos.shifts` threw on first paint).
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Personnel } from './personnel.jsx';
import { Positions } from './positions.jsx';

describe('empty database', () => {
  it('Personnel renders an empty state with no employees', () => {
    render(
      <Personnel
        employees={[]} setEmployees={vi.fn()} skills={[]} settings={{}}
        selId={null} setSelId={vi.fn()} snap={5} newFlow={false}
      />,
    );
    expect(screen.getByText(/no people yet/i)).toBeInTheDocument();
    expect(screen.getByText(/add your first person/i)).toBeInTheDocument();
  });

  it('Positions renders an empty state with no positions', () => {
    render(
      <Positions
        employees={[]} positions={[]} setPositions={vi.fn()} groupOrder={[]}
        setGroupOrder={vi.fn()} skills={[]} selId={null} setSelId={vi.fn()}
        snap={5} newFlow={false}
      />,
    );
    expect(screen.getByText(/no positions yet/i)).toBeInTheDocument();
    expect(screen.getByText(/add your first position/i)).toBeInTheDocument();
  });
});
