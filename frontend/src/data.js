// data.js — seed data + date/time helpers. Anchored to the current week.

const DAY = 86400000;
const pad = (n) => String(n).padStart(2, '0');
const isoOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
function startOfWeek(d) { const x = new Date(d); const wd = (x.getDay() + 6) % 7; x.setHours(0,0,0,0); x.setDate(x.getDate() - wd); return x; }
function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
function parseISO(s) { const [y,m,dd] = s.split('-').map(Number); return new Date(y, m-1, dd); }
function minLabel(m) { return `${pad(Math.floor(m/60))}:${pad(m%60)}`; }
function min12(m) { let h = Math.floor(m/60); const mm = m%60; const ap = h < 12 ? 'AM':'PM'; h = h%12 || 12; return mm ? `${h}:${pad(mm)} ${ap}` : `${h} ${ap}`; }

const MON = startOfWeek(new Date());
const iso = (offset) => isoOf(addDays(MON, offset)); // 0 = Monday of current week

let _uid = 1; const uid = (p) => `${p}${_uid++}`;

const SKILLS = ['Reception', 'Floor', 'Bar', 'Kitchen', 'Cleaning', 'Supervisor', 'First Aid', 'Logistics'];

function blk(type, dayOffset, start, end, repeat = 'none', allDay = false) {
  return { id: uid('b'), type, date: iso(dayOffset), start, end, allDay, repeat };
}

const EMPLOYEES = [
  { id: uid('e'), name: 'Anna Schmidt', role: 'Senior Staff', contract: 38, skills: ['Floor','Bar','Supervisor'], blocks: [
    blk('pref', 0, 9*60, 17*60, 'weekly'), blk('pref', 1, 9*60, 17*60, 'weekly'),
    blk('undes', 4, 18*60, 23*60, 'weekly'), blk('vac', 2, 0, 0, 'none', true),
  ]},
  { id: uid('e'), name: 'Liam Carter', role: 'Cook', contract: 40, skills: ['Kitchen','Logistics'], blocks: [
    blk('pref', 0, 7*60, 15*60, 'weekly'), blk('pref', 2, 7*60, 15*60, 'weekly'), blk('pref', 4, 7*60, 15*60, 'weekly'),
    blk('undes', 5, 0, 0, 'weekly', true),
  ]},
  { id: uid('e'), name: 'Mei Tanaka', role: 'Receptionist', contract: 32, skills: ['Reception','First Aid'], blocks: [
    blk('pref', 1, 8*60, 14*60, 'weekly'), blk('pref', 3, 8*60, 14*60, 'weekly'),
    blk('vac', 5, 0, 0, 'none', true), blk('vac', 6, 0, 0, 'none', true),
  ]},
  { id: uid('e'), name: 'Omar Haddad', role: 'Bartender', contract: 30, skills: ['Bar','Floor'], blocks: [
    blk('pref', 3, 16*60, 24*60, 'weekly'), blk('pref', 4, 16*60, 24*60, 'weekly'), blk('pref', 5, 16*60, 24*60, 'weekly'),
    blk('undes', 0, 7*60, 12*60, 'weekly'),
  ]},
  { id: uid('e'), name: 'Sofia Rossi', role: 'Facilities', contract: 25, skills: ['Cleaning','Logistics'], blocks: [
    blk('pref', 0, 6*60, 11*60, 'daily'),
  ]},
  { id: uid('e'), name: 'Noah Becker', role: 'Shift Lead', contract: 40, skills: ['Floor','Supervisor','First Aid'], blocks: [
    blk('pref', 1, 12*60, 20*60, 'weekly'), blk('pref', 2, 12*60, 20*60, 'weekly'), blk('pref', 3, 12*60, 20*60, 'weekly'),
    blk('undes', 6, 12*60, 20*60, 'weekly'),
  ]},
  { id: uid('e'), name: 'Priya Nair', role: 'Staff', contract: 28, skills: ['Reception','Floor'], blocks: [
    blk('pref', 2, 10*60, 18*60, 'weekly'), blk('pref', 4, 10*60, 18*60, 'weekly'),
  ]},
];

function defaultRules(contract) {
  return [
    { id: uid('r'), metric: 'weekHours', op: 'preferred', value: contract, changes: [] },
    { id: uid('r'), metric: 'weekHours', op: 'max',       value: 48, changes: [] },
    { id: uid('r'), metric: 'dayHours',  op: 'max',       value: 10, changes: [] },
  ];
}

function sh(name, dayOffset, start, end, skill, headcount, repeat = 'weekly') {
  return { id: uid('s'), name, date: iso(dayOffset), start, end, skills: Array.isArray(skill) ? skill : [skill], headcount, repeat, preferred: [] };
}
const shiftSkills = (s) => s.skills ? s.skills : (s.skill ? [s.skill] : []);

function reflowPositions(positions, order) {
  const rank = (p) => p.group ? (order.indexOf(p.group) + 1 || 9998) : 9999;
  return positions.map((p, i) => [p, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map((x) => x[0]);
}

const POSITIONS = [
  { id: uid('p'), name: 'Front Desk', color: 192, group: 'Front of House', skills: ['Reception','First Aid'], shifts: [
    sh('Morning Desk', 0, 8*60, 14*60, 'Reception', 1), sh('Afternoon Desk', 0, 14*60, 20*60, 'Reception', 1),
    sh('Morning Desk', 1, 8*60, 14*60, 'Reception', 1), sh('Afternoon Desk', 2, 14*60, 20*60, 'Reception', 2),
    sh('Morning Desk', 3, 8*60, 14*60, 'Reception', 1), sh('Afternoon Desk', 4, 14*60, 20*60, 'Reception', 1),
  ]},
  { id: uid('p'), name: 'Main Floor', color: 274, group: 'Front of House', skills: ['Floor','Supervisor'], shifts: [
    sh('Open Floor', 0, 9*60, 15*60, 'Floor', 2), sh('Peak Floor', 0, 15*60, 22*60, ['Floor','Supervisor'], 3),
    sh('Peak Floor', 1, 15*60, 22*60, 'Floor', 3), sh('Open Floor', 2, 9*60, 15*60, 'Floor', 2),
    sh('Peak Floor', 3, 15*60, 22*60, 'Floor', 2), sh('Peak Floor', 4, 15*60, 22*60, 'Floor', 3),
    sh('Weekend Floor', 5, 11*60, 22*60, 'Floor', 4),
  ]},
  { id: uid('p'), name: 'Kitchen Line', color: 35, group: 'Kitchen', skills: ['Kitchen'], shifts: [
    sh('Prep', 0, 7*60, 12*60, 'Kitchen', 2), sh('Service', 0, 12*60, 20*60, 'Kitchen', 2),
    sh('Prep', 2, 7*60, 12*60, 'Kitchen', 1), sh('Service', 2, 12*60, 20*60, 'Kitchen', 2),
    sh('Service', 4, 12*60, 20*60, 'Kitchen', 2), sh('Weekend Service', 5, 11*60, 21*60, 'Kitchen', 3),
  ]},
  { id: uid('p'), name: 'Bar', color: 330, group: 'Kitchen', skills: ['Bar'], shifts: [
    sh('Evening Bar', 3, 17*60, 24*60, 'Bar', 1), sh('Evening Bar', 4, 17*60, 24*60, 'Bar', 2),
    sh('Weekend Bar', 5, 16*60, 24*60, 'Bar', 2),
  ]},
  { id: uid('p'), name: 'Cleaning Crew', color: 150, group: 'Operations', skills: ['Cleaning'], shifts: [
    sh('Early Clean', 0, 6*60, 10*60, 'Cleaning', 1, 'daily'), sh('Close Clean', 0, 22*60, 24*60, 'Cleaning', 1, 'daily'),
  ]},
  { id: uid('p'), name: 'Night Supervisor', color: 256, group: 'Operations', skills: ['Supervisor'], shifts: [
    sh('Night Lead', 4, 18*60, 24*60, ['Supervisor','First Aid'], 1), sh('Night Lead', 5, 18*60, 24*60, 'Supervisor', 1),
  ]},
];

export const SS = {
  DAY, pad, isoOf, startOfWeek, addDays, parseISO, minLabel, min12, iso, uid,
  SKILLS, MON, defaultRules, shiftSkills, reflowPositions,
  seed: () => {
    const employees = JSON.parse(JSON.stringify(EMPLOYEES)).map((e) => ({
      ...e,
      rules: e.rules || defaultRules(e.contract),
    }));
    // Demo: Anna prefers an 8h day, and her preferred week drops to 30h from the start of next month
    const a = employees[0];
    a.rules.push({ id: uid('r'), metric: 'dayHours', op: 'preferred', value: 8, changes: [] });
    const nm = new Date(); const nextMonth = isoOf(new Date(nm.getFullYear(), nm.getMonth() + 1, 1));
    a.rules[0].changes = [{ id: uid('c'), date: nextMonth, kind: 'set', metric: 'weekHours', op: 'preferred', value: 30 }];
    const positions = JSON.parse(JSON.stringify(POSITIONS));
    const byName = (n) => (employees.find((e) => e.name === n) || {}).id;
    const pin = (posName, shiftName, names) => {
      const p = positions.find((x) => x.name === posName); if (!p) return;
      p.shifts.forEach((s) => { if (s.name === shiftName) s.preferred = names.map(byName).filter(Boolean).slice(0, s.headcount); });
    };
    pin('Bar', 'Evening Bar', ['Omar Haddad']);
    pin('Front Desk', 'Afternoon Desk', ['Mei Tanaka']);
    pin('Kitchen Line', 'Prep', ['Liam Carter']);
    return { employees, positions };
  },
};
