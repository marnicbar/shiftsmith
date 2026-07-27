// planview.jsx — the Shift Plan tab. A scope selector (Overview / Personnel /
// Positions) switches between the timeline overview (ShiftPlan) and two read-only
// assignment calendars: one per position (who works each shift) and one per person
// (the shifts a single employee is assigned). The per-position / per-person views
// reuse the shared Calendar in read-only mode and draw the solver's *actual*
// assignments rather than the editable availability/shift templates.
import { useState, useMemo, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { Ic } from './icons.jsx';
import { UI } from './ui.jsx';
import { Theme } from './theme.js';
import { Calendar, calendarDays } from './calendar.jsx';
import { ShiftPlan, matchesDay } from './shiftplan.jsx';
import { getScheduleRange } from './lib/api.js';
import { ExportButton } from './export.jsx';

const NOOP = () => {};
const NEW_ITEM = () => ({});

// Prepend the day before the visible range. An overnight shift anchored on that
// lead-in day spills its tail into the first visible day; building events over it
// lets the read-only Calendar render that tail. The Calendar only draws its own
// columns, so the lead-in day's head (and any non-overnight lead-in event) is
// harmlessly dropped.
function withLeadIn(dayList) {
  if (!dayList.length) return dayList;
  return [SS.isoOf(SS.addDays(SS.parseISO(dayList[0]), -1)), ...dayList];
}

// Turn the backend's range slots into the `shiftTemplateId@date → [employee]` map the
// event builders consume (mirrors App's live assignMap, so the two are interchangeable).
export function slotsToAssign(slots = [], empById = {}) {
  const m = {};
  for (const s of slots) {
    const k = `${s.shiftTemplateId}@${s.date}`;
    const arr = (m[k] = m[k] || []);
    if (s.employeeId && empById[s.employeeId]) arr[s.slotIndex] = empById[s.employeeId];
  }
  for (const k of Object.keys(m)) m[k] = m[k].filter(Boolean);
  return m;
}

// Load the durable assignment slots for the visible calendar range and convert them to
// an assign map, so a read-only view can show history beyond the live solve window. The
// live `assign` (fresher, SSE-driven) is overlaid on top for the current window.
function useRangeAssign(dayList, scope, empById) {
  const [rangeAssign, setRangeAssign] = useState({});
  const from = dayList[0];
  const to = dayList.length ? SS.isoOf(SS.addDays(SS.parseISO(dayList[dayList.length - 1]), 1)) : null;
  useEffect(() => {
    if (!from || !to || !scope) { setRangeAssign({}); return undefined; }
    let alive = true;
    getScheduleRange(from, to, scope)
      .then((slots) => { if (alive) setRangeAssign(slotsToAssign(slots || [], empById)); })
      .catch(() => { if (alive) setRangeAssign({}); });
    return () => { alive = false; };
  }, [from, to, scope, empById]);
  return rangeAssign;
}

// Hours a concrete (non-recurring) event spans, accounting for overnight (end < start).
function evHours(ev) {
  const end = ev.end > ev.start ? ev.end : ev.end + 1440;
  return (end - ev.start) / 60;
}

// --- pure event builders (exported for tests) -------------------------------

// Read-only calendar events for one position over `dayList`: ONE event per shift
// occurrence, with a generic accent border and each assignee shown as an avatar
// (initials, person-coloured) + name, plus an "open" line when understaffed.
// `assign` is the solver's map `shiftId@date → [emp]`.
export function buildPositionEvents(position, dayList, assign = {}, { nameOrder = 'first', t } = {}) {
  const out = [];
  if (!position) return out;
  const openLabel = (n) => (t ? t('plan.openSlots', { count: n }) : `${n} open`);
  for (const sh of position.shifts || []) {
    for (const d of dayList) {
      if (!matchesDay(sh, d)) continue;
      const assigned = assign[`${sh.id}@${d}`] || [];
      const headcount = Math.max(sh.headcount || 1, assigned.length);
      const open = Math.max(0, headcount - assigned.length);
      const time = `${SS.minLabel(sh.start)}–${SS.minLabel(sh.end)}`;
      const crew = assigned.map((e) => ({
        name: SS.fullName(e, nameOrder), initials: SS.empInitials(e), color: Theme.colorAt(e.color),
      }));
      const title = [position.name, time, crew.map((c) => c.name).join(', '), open ? openLabel(open) : '']
        .filter(Boolean).join(' · ');
      out.push({
        id: `${sh.id}@${d}`, date: d, start: sh.start, end: sh.end, repeat: 'none', allDay: false,
        _tone: crew.length ? 'assign' : 'open',
        _timeLabel: time,
        _crew: crew,
        _openLabel: open > 0 ? openLabel(open) : null,
        _title: title,
        crew, open, shiftId: sh.id,
      });
    }
  }
  return out;
}

// Read-only calendar events for one employee over `dayList`: every shift, across
// all positions, the person is assigned to. Coloured by the owning position.
export function buildPersonEvents(employee, positions = [], dayList, assign = {}) {
  const out = [];
  if (!employee) return out;
  for (const p of positions) {
    for (const sh of p.shifts || []) {
      for (const d of dayList) {
        if (!matchesDay(sh, d)) continue;
        const crew = assign[`${sh.id}@${d}`] || [];
        if (!crew.some((e) => e.id === employee.id)) continue;
        const time = `${SS.minLabel(sh.start)}–${SS.minLabel(sh.end)}`;
        out.push({
          id: `${sh.id}@${d}#${p.id}`, date: d, start: sh.start, end: sh.end, repeat: 'none', allDay: false,
          _tone: 'assign', _color: Theme.colorAt(p.color),
          _label: p.name, _title: `${p.name} · ${time}`,
          positionId: p.id, shiftId: sh.id,
        });
      }
    }
  }
  return out;
}

// --- per-person view --------------------------------------------------------

function PersonSchedule({ employees = [], positions = [], assign, selId, setSelId, nameOrder }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [view, setView] = useState('week');
  const [anchor, setAnchor] = useState(new Date());
  const [zoom, setZoom] = useState(46);

  const emp = employees.find((e) => e.id === selId) || employees[0];
  const ql = q.toLowerCase();
  const list = employees
    .filter((e) => SS.fullName(e).toLowerCase().includes(ql) || e.skills.some((s) => s.toLowerCase().includes(ql)))
    .sort((a, b) => SS.compareNames(a, b, nameOrder));

  const dayList = useMemo(() => calendarDays(view, anchor), [view, anchor]);
  const buildDays = useMemo(() => withLeadIn(dayList), [dayList]);
  const empById = useMemo(() => Object.fromEntries(employees.map((e) => [e.id, e])), [employees]);
  // Fetch the visible range (plus the overnight lead-in day) from the durable store
  // (so past months show history), then overlay the live window assignments on top.
  const rangeAssign = useRangeAssign(buildDays, emp ? `person:${emp.id}` : null, empById);
  const effectiveAssign = useMemo(() => ({ ...rangeAssign, ...assign }), [rangeAssign, assign]);
  const events = useMemo(
    () => (emp ? buildPersonEvents(emp, positions, buildDays, effectiveAssign) : []),
    [emp, positions, buildDays, effectiveAssign],
  );
  // Stats reflect the visible range only — exclude the lead-in day's spill-over anchor.
  const visibleEvents = useMemo(() => events.filter((ev) => ev.date >= dayList[0]), [events, dayList]);
  const totalHours = visibleEvents.reduce((a, ev) => a + evHours(ev), 0);

  return (
    <div className="view">
      <div className="rail">
        <div className="rail-head">
          <div className="row">
            <span className="section-title">{t('personnel.people')} <span className="muted">· {employees.length}</span></span>
          </div>
          <div className="search"><Ic.search/><input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('personnel.searchPlaceholder')}/></div>
        </div>
        <div className="rail-list">
          {list.map((e) => (
            <div key={e.id} className={`rail-item ${emp && e.id === emp.id ? 'sel' : ''}`} onClick={() => setSelId(e.id)}>
              <UI.Avatar emp={e}/>
              <div className="ri-meta">
                <div className="ri-name">{SS.fullName(e, nameOrder)}</div>
                {e.skills.length > 0 && <div className="ri-sub">{e.skills.join(' · ')}</div>}
              </div>
            </div>
          ))}
          {!list.length && <div className="muted" style={{ padding: 14, fontSize: 13 }}>{t('personnel.noMatches')}</div>}
        </div>
      </div>

      {!emp ? (
        <ScopeEmpty icon={<Ic.users/>} title={t('plan.pickPerson')} body={t('plan.pickPersonBody')} />
      ) : (
        <>
          <Calendar kind="assign-person" readOnly view={view} onView={setView} anchor={anchor} onAnchor={setAnchor}
            zoom={zoom} onZoom={setZoom} palette={[]} items={events}
            toolbarExtra={<ExportButton scopes={[`person:${emp.id}`]} view={view} anchor={anchor} nameOrder={nameOrder} />}
            newItem={NEW_ITEM} onCommit={NOOP} onDelete={NOOP} onSplit={NOOP} />
          <div className="config">
            <div className="pad">
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <UI.Avatar emp={emp} size="lg" square/>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.02em' }}>{SS.fullName(emp, nameOrder)}</div>
                  {emp.skills.length > 0 && <div className="muted" style={{ fontSize: 12.5 }}>{emp.skills.join(' · ')}</div>}
                </div>
              </div>
              <div className="divider"></div>
              <div className="section-title">{t('plan.inView')}</div>
              <div>
                <div className="stat-line"><span className="k">{t('plan.assignedShifts')}</span><span className="v">{visibleEvents.length}</span></div>
                <div className="stat-line"><span className="k">{t('plan.assignedHours')}</span><span className="v">{t('plan.hours', { hours: Math.round(totalHours * 10) / 10 })}</span></div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// --- per-position view ------------------------------------------------------

function PositionSchedule({ positions = [], employees = [], assign, selId, setSelId, nameOrder }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [view, setView] = useState('week');
  const [anchor, setAnchor] = useState(new Date());
  const [zoom, setZoom] = useState(46);

  const pos = positions.find((p) => p.id === selId) || positions[0];
  const list = positions.filter((p) => p.name.toLowerCase().includes(q.toLowerCase()));

  const dayList = useMemo(() => calendarDays(view, anchor), [view, anchor]);
  const buildDays = useMemo(() => withLeadIn(dayList), [dayList]);
  const empById = useMemo(() => Object.fromEntries(employees.map((e) => [e.id, e])), [employees]);
  // History-aware range load for this position (plus the overnight lead-in day),
  // with the live window overlaid on top.
  const rangeAssign = useRangeAssign(buildDays, pos ? `position:${pos.id}` : null, empById);
  const effectiveAssign = useMemo(() => ({ ...rangeAssign, ...assign }), [rangeAssign, assign]);
  const events = useMemo(
    () => (pos ? buildPositionEvents(pos, buildDays, effectiveAssign, { nameOrder, t }) : []),
    [pos, buildDays, effectiveAssign, nameOrder, t],
  );
  // Stats reflect the visible range only — exclude the lead-in day's spill-over anchor.
  const visibleEvents = useMemo(() => events.filter((e) => e.date >= dayList[0]), [events, dayList]);
  const filled = visibleEvents.reduce((a, e) => a + (e.crew ? e.crew.length : 0), 0);
  const open = visibleEvents.reduce((a, e) => a + (e.open || 0), 0);

  return (
    <div className="view">
      <div className="rail">
        <div className="rail-head">
          <div className="row">
            <span className="section-title">{t('positions.sectionTitle')} <span className="muted">· {positions.length}</span></span>
          </div>
          <div className="search"><Ic.search/><input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('positions.searchPlaceholder')}/></div>
        </div>
        <div className="rail-list">
          {list.map((p) => (
            <div key={p.id} className={`rail-item ${pos && p.id === pos.id ? 'sel' : ''}`} onClick={() => setSelId(p.id)}>
              <div className="avatar sq" style={{ background: Theme.colorAt(p.color) }}><Ic.briefcase size={16}/></div>
              <div className="ri-meta">
                <div className="ri-name">{p.name}</div>
                <div className="ri-sub">{t('positions.shiftCount', { count: p.shifts.length })}</div>
              </div>
            </div>
          ))}
          {!list.length && <div className="muted" style={{ padding: 14, fontSize: 13 }}>{t('personnel.noMatches')}</div>}
        </div>
      </div>

      {!pos ? (
        <ScopeEmpty icon={<Ic.briefcase/>} title={t('plan.pickPosition')} body={t('plan.pickPositionBody')} />
      ) : (
        <>
          <Calendar kind="assign-position" readOnly view={view} onView={setView} anchor={anchor} onAnchor={setAnchor}
            zoom={zoom} onZoom={setZoom} palette={[]} items={events}
            toolbarExtra={<ExportButton scopes={[`position:${pos.id}`]} view={view} anchor={anchor} nameOrder={nameOrder} />}
            newItem={NEW_ITEM} onCommit={NOOP} onDelete={NOOP} onSplit={NOOP} />
          <div className="config">
            <div className="pad">
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div className="avatar lg sq" style={{ background: Theme.colorAt(pos.color) }}><Ic.briefcase size={20}/></div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.02em' }}>{pos.name}</div>
                  <div className="muted" style={{ fontSize: 12.5 }}>{t('positions.shiftTypeCount', { count: pos.shifts.length })}</div>
                </div>
              </div>
              <div className="divider"></div>
              <div className="section-title">{t('plan.inView')}</div>
              <div>
                <div className="stat-line"><span className="k">{t('plan.filledSlots')}</span><span className="v">{filled}</span></div>
                <div className="stat-line"><span className="k">{t('plan.openSlotsLabel')}</span><span className="v" style={open ? { color: 'var(--amber-strong)' } : null}>{open}</span></div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// Empty-state body for a scope with nothing selected. The scope selector itself
// lives in the top nav (the morphing Shift Plan tab), so it's always reachable.
function ScopeEmpty({ icon, title, body }) {
  return (
    <div className="empty-state">
      <div className="inner">
        {icon}
        <div style={{ fontSize: 15, fontWeight: 600 }}>{title}</div>
        <div className="muted">{body}</div>
      </div>
    </div>
  );
}

// --- the tab shell ----------------------------------------------------------

// `scope` ('overview' | 'personnel' | 'positions') is owned by the top nav, where
// the Shift Plan tab morphs into the scope selector while active.
export function PlanView(props) {
  const { employees, positions, assign, selEmp, setSelEmp, selPos, setSelPos, nameOrder, scope = 'overview' } = props;

  if (scope === 'personnel') {
    return <PersonSchedule employees={employees} positions={positions} assign={assign}
      selId={selEmp} setSelId={setSelEmp} nameOrder={nameOrder} />;
  }
  if (scope === 'positions') {
    return <PositionSchedule positions={positions} employees={employees} assign={assign}
      selId={selPos} setSelId={setSelPos} nameOrder={nameOrder} />;
  }
  return <ShiftPlan employees={employees} positions={positions} groupOrder={props.groupOrder}
    initialMode={props.initialMode} assign={assign} overrides={props.overrides} setOverrides={props.setOverrides}
    sched={props.sched} onSolve={props.onSolve} onPause={props.onPause} focus={props.focus}
    onFocusConsumed={props.onFocusConsumed} nameOrder={nameOrder} />;
}
