// The export control: it opens, adapts to the view, warns about shifts the printed
// hours would leave out, and asks the backend for the right document. What that
// document *contains* is the backend's job (CalendarDocumentBuilderTest).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExportButton } from './export.jsx';
import { exportCalendarPdf, getExportPlan } from './lib/api.js';

vi.mock('./lib/api.js', () => ({ exportCalendarPdf: vi.fn(), getExportPlan: vi.fn() }));

const ANCHOR = new Date(2026, 6, 27); // Mon 27 Jul 2026

const plan = (droppedTotal = 0, pages = 1) => ({
  sections: Array.from({ length: pages }, (_, i) => ({ title: `p${i}`, range: '', shifts: 3, dropped: {} })),
  droppedTotal,
  generated: 'Generated Jul 27, 2026, 14:03',
});

function renderBtn(props = {}) {
  render(<ExportButton scopes={['position:p1']} view="week" anchor={ANCHOR} nameOrder="first" {...props} />);
}

const openPopover = () => userEvent.click(screen.getByRole('button', { name: /PDF/ }));

describe('ExportButton', () => {
  let clicked;

  beforeEach(() => {
    getExportPlan.mockReset().mockResolvedValue(plan());
    exportCalendarPdf.mockReset().mockResolvedValue({
      blob: new Blob(['%PDF-'], { type: 'application/pdf' }), filename: 'kitchen-2026-07-27-week.pdf',
    });
    // jsdom implements neither of these; capture the download instead of performing it.
    clicked = [];
    URL.createObjectURL = vi.fn(() => 'blob:fake');
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function spy() { clicked.push(this.download); });
  });
  afterEach(() => vi.restoreAllMocks());

  it('asks the backend what a week export would contain', async () => {
    renderBtn();
    await openPopover();
    expect(screen.getByText('Printed hours')).toBeInTheDocument();
    await waitFor(() => expect(getExportPlan).toHaveBeenCalled());
    expect(getExportPlan).toHaveBeenLastCalledWith(expect.objectContaining({
      scopes: ['position:p1'], view: 'week', anchor: '2026-07-27', from: 360, to: 1320, nameOrder: 'first',
    }));
  });

  it('drops the time range for a month view, since it prints whole days', async () => {
    renderBtn({ view: 'month' });
    await openPopover();
    expect(screen.queryByText('Printed hours')).not.toBeInTheDocument();
    await waitFor(() => expect(getExportPlan).toHaveBeenCalled());
    expect(getExportPlan).toHaveBeenLastCalledWith(expect.objectContaining({ from: 0, to: 1440 }));
  });

  it('warns when the printed hours would leave shifts off the page', async () => {
    getExportPlan.mockResolvedValue(plan(3));
    renderBtn();
    await openPopover();
    expect(await screen.findByText(/3 shifts fall outside these hours/)).toBeInTheDocument();
    expect(screen.queryByText('Only this part of the day is printed.')).not.toBeInTheDocument();
  });

  it('reports how many pages the export will be', async () => {
    getExportPlan.mockResolvedValue(plan(0, 4));
    renderBtn({ scopes: ['person:e1', 'person:e2', 'person:e3', 'person:e4'] });
    await openPopover();
    expect(await screen.findByText('4 pages')).toBeInTheDocument();
  });

  it('downloads with the chosen options under the name the server gave', async () => {
    renderBtn();
    await openPopover();
    await userEvent.click(screen.getByRole('button', { name: 'Portrait' }));
    await userEvent.click(screen.getByRole('button', { name: 'A3' }));
    await userEvent.click(screen.getByRole('button', { name: 'Download' }));

    await waitFor(() => expect(exportCalendarPdf).toHaveBeenCalledTimes(1));
    expect(exportCalendarPdf).toHaveBeenCalledWith(expect.objectContaining({
      scopes: ['position:p1'], paper: 'a3', orientation: 'portrait', from: 360, to: 1320,
    }));
    expect(clicked).toEqual(['kitchen-2026-07-27-week.pdf']);
  });

  it('shows the failure reason and stays open when the render fails', async () => {
    const err = new Error('boom');
    err.serverMessage = "PDF export is unavailable: the 'typst' binary was not found";
    exportCalendarPdf.mockRejectedValue(err);
    renderBtn();
    await openPopover();
    await userEvent.click(screen.getByRole('button', { name: 'Download' }));

    expect(await screen.findByText(/typst' binary was not found/)).toBeInTheDocument();
    expect(clicked).toEqual([]);
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument();
  });

  it('still lets you export when the preflight fails — it only costs the warning', async () => {
    getExportPlan.mockRejectedValue(new Error('offline'));
    renderBtn();
    await openPopover();
    await userEvent.click(screen.getByRole('button', { name: 'Download' }));
    await waitFor(() => expect(clicked).toEqual(['kitchen-2026-07-27-week.pdf']));
  });

  it('stands a day page up and lays a week page down, unless told otherwise', async () => {
    const { unmount } = render(
      <ExportButton scopes={['position:p1']} view="day" anchor={ANCHOR} nameOrder="first" />);
    await openPopover();
    expect(screen.getByRole('button', { name: 'Portrait' })).toHaveClass('on');
    await userEvent.click(screen.getByRole('button', { name: 'Landscape' })); // an explicit pick wins
    await userEvent.click(screen.getByRole('button', { name: 'Download' }));
    await waitFor(() => expect(exportCalendarPdf).toHaveBeenCalledWith(
      expect.objectContaining({ orientation: 'landscape' })));
    unmount();

    renderBtn(); // week
    await openPopover();
    expect(screen.getByRole('button', { name: 'Landscape' })).toHaveClass('on');
  });

  it('is disabled with nothing selected to export', async () => {
    renderBtn({ scopes: [] });
    expect(screen.getByRole('button', { name: /PDF/ })).toBeDisabled();
  });
});
