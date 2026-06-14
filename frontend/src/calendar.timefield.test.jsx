// Component test for TimeField's click-away commit (#34): typing a new time and
// then clicking outside must commit the *typed* value, not the value seeded when
// the field opened. The bug was a stale closure in useClickAway capturing the
// open-time callback.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TimeField } from './calendar.jsx';

describe('TimeField — click-away commit', () => {
  it('commits the typed time when the user clicks outside', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <div>
        <TimeField minutes={600} onChange={onChange} isEnd={false} />
        <button type="button">outside</button>
      </div>,
    );

    const input = screen.getByRole('textbox');
    await user.click(input);        // focus seeds the field with 10:00 and opens it
    await user.clear(input);
    await user.type(input, '14:30');

    // Click away (mousedown on an element outside the field's wrapper).
    fireEvent.mouseDown(screen.getByText('outside'));

    expect(onChange).toHaveBeenLastCalledWith(870); // 14:30 — not the seeded 600
  });
});
