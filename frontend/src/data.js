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

function reflowPositions(positions, order) {
  const rank = (p) => p.group ? (order.indexOf(p.group) + 1 || 9998) : 9999;
  return positions.map((p, i) => [p, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map((x) => x[0]);
}

export const SS = {
  DAY, pad, isoOf, startOfWeek, addDays, parseISO, minLabel, min12, uid,
  shiftSkills, reflowPositions,
};
