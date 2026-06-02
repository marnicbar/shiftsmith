// Component tests for the shared RulesEditor: apply-to-save, "define once"
// availability, global rules surfaced per person, and stricter-only overrides.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RulesEditor } from './rules.jsx';

describe('RulesEditor — personal mode', () => {
  it('does not save a new rule until Apply is pressed', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RulesEditor rules={[]} onChange={onChange} globalRules={[]} mode="personal" />);

    await user.click(screen.getByTitle('Add rule'));
    expect(onChange).not.toHaveBeenCalled();      // a draft, not a saved rule

    await user.click(screen.getByText('Apply'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toHaveLength(1);
  });

  it('shows a global rule with a "Customize" affordance and no inline error', () => {
    render(
      <RulesEditor rules={[]} onChange={vi.fn()} mode="personal"
        globalRules={[{ id: 'g1', metric: 'dayHours', op: 'max', value: 10, changes: [] }]} />,
    );
    expect(screen.getByText('Daily hours')).toBeInTheDocument();
    expect(screen.getByText('Global')).toBeInTheDocument();
    expect(screen.getByText('Customize')).toBeInTheDocument();
  });

  it('caps an override input at the global ceiling instead of erroring', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RulesEditor rules={[]} onChange={onChange} mode="personal"
        globalRules={[{ id: 'g1', metric: 'dayHours', op: 'max', value: 10, changes: [] }]} />,
    );

    await user.click(screen.getByText('Customize'));
    const input = screen.getByRole('spinbutton');
    expect(input).toHaveAttribute('max', '10');   // browser-level guard

    await user.clear(input);
    await user.type(input, '14');                 // above the global ceiling
    await user.click(screen.getByText('Apply'));

    expect(onChange).toHaveBeenCalledTimes(1);
    const saved = onChange.mock.calls[0][0];
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({ metric: 'dayHours', op: 'max', value: 10 }); // clamped down
  });

  it('renders both the global and the personal row when overridden', () => {
    render(
      <RulesEditor mode="personal" onChange={vi.fn()}
        rules={[{ id: 'r1', metric: 'dayHours', op: 'max', value: 8, changes: [] }]}
        globalRules={[{ id: 'g1', metric: 'dayHours', op: 'max', value: 10, changes: [] }]} />,
    );
    expect(screen.getByText('Global')).toBeInTheDocument();
    expect(screen.getByText('Personal')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument(); // global value
    expect(screen.getByText('8')).toBeInTheDocument();  // personal override
  });
});

describe('RulesEditor — define once', () => {
  it('omits already-used metric/op combinations from a new rule', async () => {
    const user = userEvent.setup();
    render(
      <RulesEditor mode="global" onChange={vi.fn()} globalRules={[]}
        rules={[{ id: 'r1', metric: 'dayHours', op: 'max', value: 10, changes: [] }]} />,
    );

    await user.click(screen.getByTitle('Add rule'));
    // The metric select offers metrics; pick Daily hours, then its op list must
    // exclude "At most" since dayHours:max already exists.
    const selects = screen.getAllByRole('combobox');
    const metricSelect = selects[0];
    await user.selectOptions(metricSelect, 'dayHours');
    const opSelect = screen.getAllByRole('combobox')[1];
    const opLabels = within(opSelect).getAllByRole('option').map((o) => o.textContent);
    expect(opLabels).not.toContain('At most');
    expect(opLabels).toContain('At least');
  });
});
