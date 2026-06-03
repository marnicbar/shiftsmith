// Tests for entriesOverlap — the pure overlap check that stops users creating
// calendar entries that occupy the same minute. Vacations / all-day entries are
// exempt (they span the whole day on purpose).
import { describe, it, expect } from 'vitest';
import { entriesOverlap } from './calendar.jsx';

const MON = '2026-06-01'; // Monday
const TUE = '2026-06-02';
const WED = '2026-06-03';
const NEXT_MON = '2026-06-08';

const item = (over = {}) => ({ id: 's', type: 'pref', date: MON, start: 600, end: 720, repeat: 'none', allDay: false, ...over });

describe('entriesOverlap', () => {
  it('detects two timed entries that share minutes on the same day', () => {
    expect(entriesOverlap(item({ start: 600, end: 720 }), item({ id: 'b', start: 660, end: 780 }))).toBe(true);
  });

  it('allows adjacent entries that merely touch at the seam', () => {
    expect(entriesOverlap(item({ start: 600, end: 720 }), item({ id: 'b', start: 720, end: 840 }))).toBe(false);
  });

  it('allows entries that are clear of each other', () => {
    expect(entriesOverlap(item({ start: 600, end: 660 }), item({ id: 'b', start: 800, end: 900 }))).toBe(false);
  });

  it('does not flag entries on different days', () => {
    expect(entriesOverlap(item({ date: MON }), item({ id: 'b', date: TUE }))).toBe(false);
  });

  it('exempts vacation entries even when the times collide', () => {
    expect(entriesOverlap(item({ type: 'vac', allDay: true }), item({ id: 'b' }))).toBe(false);
    expect(entriesOverlap(item(), item({ id: 'b', type: 'vac', allDay: true }))).toBe(false);
  });

  it('exempts any all-day entry', () => {
    expect(entriesOverlap(item({ allDay: true }), item({ id: 'b' }))).toBe(false);
  });

  it('catches a recurring entry that lands on a one-off entry', () => {
    const weekly = item({ id: 'w', repeat: 'weekly', date: MON, start: 600, end: 720 });
    expect(entriesOverlap(weekly, item({ id: 'b', date: NEXT_MON, start: 660, end: 780 }))).toBe(true);
    expect(entriesOverlap(weekly, item({ id: 'b', date: TUE, start: 660, end: 780 }))).toBe(false);
  });

  it('catches a daily entry overlapping a weekly entry on a shared weekday', () => {
    const daily = item({ id: 'd', repeat: 'daily', date: MON, start: 600, end: 720 });
    const weekly = item({ id: 'w', repeat: 'weekly', date: WED, start: 660, end: 780 });
    expect(entriesOverlap(daily, weekly)).toBe(true);
  });

  it('respects until on a recurring entry', () => {
    const weekly = item({ id: 'w', repeat: 'weekly', date: MON, until: MON, start: 600, end: 720 });
    expect(entriesOverlap(weekly, item({ id: 'b', date: NEXT_MON, start: 660, end: 780 }))).toBe(false);
  });

  it('detects overnight spillover into the next morning', () => {
    // Mon 23:00 → 02:00 overlaps a Tue 01:00 → 03:00 entry
    const overnight = item({ id: 'n', date: MON, start: 1380, end: 120 });
    expect(entriesOverlap(overnight, item({ id: 'b', date: TUE, start: 60, end: 180 }))).toBe(true);
  });
});
