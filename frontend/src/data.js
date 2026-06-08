// data.js — date/time helpers and id/skill utilities shared across the app.

const DAY = 86400000;
const pad = (n) => String(n).padStart(2, '0');
const isoOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
function startOfWeek(d) { const x = new Date(d); const wd = (x.getDay() + 6) % 7; x.setHours(0,0,0,0); x.setDate(x.getDate() - wd); return x; }
function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
function parseISO(s) { const [y,m,dd] = s.split('-').map(Number); return new Date(y, m-1, dd); }
function minLabel(m) { return `${pad(Math.floor(m/60))}:${pad(m%60)}`; }
function min12(m) { let h = Math.floor(m/60); const mm = m%60; const ap = h < 12 ? 'AM':'PM'; h = h%12 || 12; return mm ? `${h}:${pad(mm)} ${ap}` : `${h} ${ap}`; }

let _uid = 1; const uid = (p) => `${p}${_uid++}`;

const shiftSkills = (s) => s.skills ? s.skills : (s.skill ? [s.skill] : []);

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
  shiftSkills, reflowPositions,
  fullName, empInitials, nameSeed, compareNames,
};
