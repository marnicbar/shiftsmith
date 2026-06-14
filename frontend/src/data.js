// data.js — date/time helpers and id/skill utilities shared across the app.

const DAY = 86400000;
const pad = (n) => String(n).padStart(2, '0');
const isoOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
function startOfWeek(d) { const x = new Date(d); const wd = (x.getDay() + 6) % 7; x.setHours(0,0,0,0); x.setDate(x.getDate() - wd); return x; }
function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
function parseISO(s) { const [y,m,dd] = s.split('-').map(Number); return new Date(y, m-1, dd); }
function startOfDay(d) { const x = new Date(d); x.setHours(0,0,0,0); return x; }

// Length of the solve window in days. MUST mirror the backend's
// Settings.rawHorizonEnd / horizonStart (dev.shiftsmith.domain.Settings): the
// window runs from the start of today to the start of the next full unit plus
// `horizonCount` more units. We check it client-side so an over-long horizon is
// caught with a localized message before the PUT is rejected with a 400.
const MAX_HORIZON_DAYS = 732; // ~2 years — keep in lock-step with Settings.MAX_HORIZON_DAYS
function horizonDays(settings, today = new Date()) {
  const count = Math.max(1, (settings && settings.horizonCount) || 1);
  const unit = (settings && settings.horizonUnit) || 'week';
  const start = startOfDay(today);
  let end;
  if (unit === 'day') {
    end = addDays(start, 1 + count);
  } else if (unit === 'month') {
    end = new Date(start.getFullYear(), start.getMonth() + 1 + count, 1);
  } else { // week (Monday-based, matching the backend)
    end = addDays(startOfWeek(start), 7 * (1 + count));
  }
  return Math.round((end - start) / DAY);
}
function minLabel(m) { return `${pad(Math.floor(m/60))}:${pad(m%60)}`; }
function min12(m) { let h = Math.floor(m/60); const mm = m%60; const ap = h < 12 ? 'AM':'PM'; h = h%12 || 12; return mm ? `${h}:${pad(mm)} ${ap}` : `${h} ${ap}`; }

// Entity IDs must be collision-free across page reloads and across concurrently
// open tabs (the counter-based scheme reset to 1 on every load, so a reload would
// re-mint `e1` over the `e1` already loaded from the backend and corrupt records).
// We mint a random UUID per id instead. `crypto.randomUUID` needs a secure context,
// which a LAN deployment served over plain HTTP does not have, so we fall back to
// `crypto.getRandomValues` (available in insecure contexts and jsdom) and finally
// to `Math.random` for ancient environments. The prefix is kept for readability.
function randomUUID() {
  const c = typeof crypto !== 'undefined' ? crypto : null;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  const b = new Uint8Array(16);
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(b);
  else for (let i = 0; i < 16; i++) b[i] = Math.floor(Math.random() * 256);
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // variant 10
  const h = [...b].map((x) => x.toString(16).padStart(2, '0'));
  return `${h[0]}${h[1]}${h[2]}${h[3]}-${h[4]}${h[5]}-${h[6]}${h[7]}-${h[8]}${h[9]}-${h[10]}${h[11]}${h[12]}${h[13]}${h[14]}${h[15]}`;
}
const uid = (p) => `${p}${randomUUID()}`;

const shiftSkills = (s) => s.skills ? s.skills : (s.skill ? [s.skill] : []);

// --- Recurrence / multi-day spans ------------------------------------------
// Whether a recurring or multi-day calendar item (a shift template or an
// availability/vacation Block) covers the given ISO date. This is the single
// source of truth used by the timeline (matchesDay), the reusable Calendar, and
// the dashboard. It MUST stay in lock-step with the backend's
// dev.shiftsmith.domain.Block.occursOn / Recurrence (see CLAUDE.md) — note the
// multi-day `endDate` span and the `null`/missing `repeat` coercion to 'none',
// which several callers used to miss.
const weekdayOf = (iso) => (parseISO(iso).getDay() + 6) % 7; // Mon=0 … Sun=6
function occursOn(item, date) {
  if (!item) return false;
  if (item.except && item.except.includes(date)) return false;
  // Multi-day span (e.g. a vacation range): start .. endDate, inclusive.
  if (item.endDate && (!item.repeat || item.repeat === 'none')) return date >= item.date && date <= item.endDate;
  if (item.until && date > item.until) return false;
  if (!item.repeat || item.repeat === 'none') return date === item.date;
  if (item.repeat === 'daily') return date >= item.date;
  if (item.repeat === 'weekly') {
    if (item.days && item.days.length) return date >= item.date && item.days.includes(weekdayOf(date));
    return weekdayOf(date) === weekdayOf(item.date) && date >= item.date;
  }
  return false;
}

// --- Employee name helpers --------------------------------------------------
// A person has a firstName + lastName. `order` ('first' | 'last') is a UI
// preference deciding which one leads in lists/labels; 'last' renders "Last, First".
function fullName(e, order = 'first') {
  if (!e) return '';
  const f = (e.firstName || '').trim();
  const l = (e.lastName || '').trim();
  if (!f && !l) return '';
  if (!f) return l;
  if (!l) return f;
  return order === 'last' ? `${l}, ${f}` : `${f} ${l}`;
}
// Avatar initials: first letter of each name (stable regardless of display order).
function empInitials(e) {
  const f = (e && e.firstName || '').trim();
  const l = (e && e.lastName || '').trim();
  return ((f[0] || '') + (l[0] || '')).toUpperCase() || '?';
}
// Stable seed for the avatar colour — must not change when the display order flips.
function nameSeed(e) { return `${(e && e.firstName) || ''} ${(e && e.lastName) || ''}`.trim(); }
// Compare two people by the chosen sort key, with the other name as a tiebreaker.
function compareNames(a, b, key = 'first') {
  const first = (a.firstName || '').localeCompare(b.firstName || '');
  const last = (a.lastName || '').localeCompare(b.lastName || '');
  return key === 'last' ? (last || first) : (first || last);
}

function reflowPositions(positions, order) {
  const rank = (p) => p.group ? (order.indexOf(p.group) + 1 || 9998) : 9999;
  return positions.map((p, i) => [p, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map((x) => x[0]);
}

export const SS = {
  DAY, pad, isoOf, startOfWeek, addDays, parseISO, minLabel, min12, uid,
  shiftSkills, reflowPositions, MAX_HORIZON_DAYS, horizonDays, occursOn,
  fullName, empInitials, nameSeed, compareNames,
};
