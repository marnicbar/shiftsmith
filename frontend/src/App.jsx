// App.jsx — shell: top tab bar, settings, view routing, backend sync + solver polling.
import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import React from 'react';
import { Theme } from './theme.js';
import { Ic } from './icons.jsx';
import { SS } from './data.js';
import { Personnel } from './personnel.jsx';
import { Positions } from './positions.jsx';
import { ShiftPlan } from './shiftplan.jsx';
import { Dashboard } from './dashboard.jsx';
import { SettingsView } from './settings.jsx';
import * as api from './lib/api.js';

const TABS = [
  { id: 'dashboard', label: 'Dashboard', icon: 'grid' },
  { id: 'personnel', label: 'Personnel', icon: 'users' },
  { id: 'positions', label: 'Positions', icon: 'briefcase' },
  { id: 'shiftplan', label: 'Shift Plan', icon: 'timeline' },
];

export const FONTS = {
  'Geist':          "'Geist', system-ui, sans-serif",
  'Helvetica Neue': "'Helvetica Neue', Helvetica, Arial, sans-serif",
  'Figtree':        "'Figtree', system-ui, sans-serif",
};

const PREF_DEFAULTS = {
  dark: false, palette: 'slate', accent: 'indigo', font: 'Geist',
  snapLabel: '15 min', newFlowLabel: 'Paint, then tweak', tlDefaultLabel: 'Week',
};

const SNAP_MAP = { '15 min': 15, '30 min': 30, '60 min': 60 };
const FLOW_MAP = { 'Paint, then tweak': 'quick', 'Open a form': 'menu' };
const TL_MAP = { 'Day': 'day', 'Week': 'week', 'Continuous': 'free' };
const PREF_KEY = 'shiftsmith.prefs';

const initialPrefs = (() => {
  try { return { ...PREF_DEFAULTS, ...JSON.parse(localStorage.getItem(PREF_KEY) || '{}') }; }
  catch { return PREF_DEFAULTS; }
})();

// Apply theme immediately on module load to avoid a flash of unstyled content.
Theme.applyTheme({ palette: initialPrefs.palette, accent: initialPrefs.accent, dark: initialPrefs.dark });
document.documentElement.style.setProperty('--ui-font', FONTS[initialPrefs.font] || FONTS.Geist);

function usePrefs() {
  const [prefs, setPrefs] = useState(initialPrefs);
  const setPref = useCallback((k, v) => setPrefs((p) => {
    const next = { ...p, [k]: v };
    try { localStorage.setItem(PREF_KEY, JSON.stringify(next)); } catch {}
    return next;
  }), []);
  return [prefs, setPref];
}

export default function App() {
  const [prefs, setPref] = usePrefs();
  const [tab, setTab] = useState('personnel');

  // Problem state (client-authoritative, synced to the backend).
  const [employees, setEmployees] = useState([]);
  const [positions, setPositions] = useState([]);
  const [settings, setSettings] = useState({ horizonUnit: 'week', horizonCount: 1 });
  const [overrides, setOverrides] = useState({});
  const [groupOrder, setGroupOrder] = useState([]);
  const [selEmp, setSelEmp] = useState(null);
  const [selPos, setSelPos] = useState(null);

  // Solver result + status (read-only, refreshed by polling).
  const [sched, setSched] = useState({ assignments: [], solverStatus: 'NOT_SOLVING', score: null, total: 0, staffed: 0, unassigned: 0 });
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);

  const lastSyncRef = useRef(null);
  const pollRef = useRef(null);

  useEffect(() => { Theme.applyTheme({ palette: prefs.palette, accent: prefs.accent, dark: prefs.dark }); }, [prefs.palette, prefs.accent, prefs.dark]);
  useEffect(() => { document.documentElement.style.setProperty('--ui-font', FONTS[prefs.font] || FONTS.Geist); }, [prefs.font]);

  const setMeta = useCallback((d) => setSched({
    assignments: d.assignments || [], solverStatus: d.solverStatus, score: d.score,
    total: d.total, staffed: d.staffed, unassigned: d.unassigned,
    horizonStart: d.horizonStart, horizonEnd: d.horizonEnd,
  }), []);

  const startPolling = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(async () => {
      try {
        const d = await api.getSchedule();
        setMeta(d);
        if (d.solverStatus === 'NOT_SOLVING') { clearInterval(pollRef.current); pollRef.current = null; }
      } catch { /* transient — keep polling */ }
    }, 1500);
  }, [setMeta]);

  // Initial load from the backend (seeds demo data on a fresh container).
  useEffect(() => {
    (async () => {
      try {
        const d = await api.getSchedule();
        setEmployees(d.employees); setPositions(d.positions);
        setSettings(d.settings || { horizonUnit: 'week', horizonCount: 1 });
        setOverrides(d.overrides || {});
        setSelEmp(d.employees[0]?.id ?? null);
        setSelPos(d.positions[0]?.id ?? null);
        const g = []; (d.positions || []).forEach((p) => { if (p.group && !g.includes(p.group)) g.push(p.group); });
        setGroupOrder(g);
        setMeta(d);
        lastSyncRef.current = JSON.stringify({ employees: d.employees, positions: d.positions, settings: d.settings, overrides: d.overrides || {} });
        setLoaded(true);
        if (d.solverStatus && d.solverStatus !== 'NOT_SOLVING') startPolling();
      } catch (e) { setError(e.message); }
    })();
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [setMeta, startPolling]);

  // Debounced sync: push the problem to the backend whenever the user edits it.
  useEffect(() => {
    if (!loaded) return;
    const problem = { employees, positions, settings, overrides };
    const ser = JSON.stringify(problem);
    if (ser === lastSyncRef.current) return;
    const t = setTimeout(async () => {
      try { await api.putProblem(problem); lastSyncRef.current = ser; startPolling(); }
      catch (e) { setError(e.message); }
    }, 600);
    return () => clearTimeout(t);
  }, [loaded, employees, positions, settings, overrides, startPolling]);

  const snap = SNAP_MAP[prefs.snapLabel] ?? 15;
  const newFlow = FLOW_MAP[prefs.newFlowLabel] ?? 'quick';
  const tlDefault = TL_MAP[prefs.tlDefaultLabel] ?? 'week';

  // Map the solver's slots into the per-occurrence shape the timeline expects.
  const empById = useMemo(() => Object.fromEntries(employees.map((e) => [e.id, e])), [employees]);
  const assignMap = useMemo(() => {
    const m = {};
    (sched.assignments || []).forEach((s) => {
      const k = `${s.shiftTemplateId}@${s.date}`;
      const arr = (m[k] = m[k] || []);
      if (s.employeeId && empById[s.employeeId]) arr[s.slotIndex] = empById[s.employeeId];
    });
    Object.keys(m).forEach((k) => { m[k] = m[k].filter(Boolean); });
    return m;
  }, [sched.assignments, empById]);

  async function solveNow() { try { await api.startSolving(); startPolling(); } catch (e) { setError(e.message); } }
  async function pauseSolver() { try { await api.stopSolving(); } catch (e) { setError(e.message); } }

  if (!loaded && !error) {
    return <div className="app"><div className="loading">Loading schedule…</div></div>;
  }

  return (
    <div className="app">
      <div className="topbar">
        <div className="brand"><span className="logo">S</span><span className="brand-name"><b>Shift</b>Smith</span></div>
        <nav className="tabs">
          {TABS.map((x) => (
            <button key={x.id} className={`tab ${tab === x.id ? 'active' : ''}`} onClick={() => setTab(x.id)}>
              {React.createElement(Ic[x.icon], { size: 16 })}{x.label}
              {x.id === 'shiftplan' && sched.unassigned > 0 && <span className="pill">{sched.unassigned}</span>}
            </button>
          ))}
        </nav>
        <div className="spacer"></div>
        <SolverBadge status={sched.solverStatus} />
        <button className={`iconbtn ${tab === 'settings' ? 'active' : ''}`} title="Settings" onClick={() => setTab('settings')}>
          <Ic.settings/>
        </button>
      </div>

      {error && <div className="api-error">Backend error: {error}. Is the backend running on :8080?</div>}

      {tab === 'dashboard' && <Dashboard employees={employees} positions={positions} sched={sched} onGo={setTab} />}
      {tab === 'personnel' && <Personnel employees={employees} setEmployees={setEmployees} skills={SS.SKILLS} selId={selEmp} setSelId={setSelEmp} snap={snap} newFlow={newFlow} />}
      {tab === 'positions' && <Positions employees={employees} positions={positions} setPositions={setPositions} groupOrder={groupOrder} setGroupOrder={setGroupOrder} skills={SS.SKILLS} selId={selPos} setSelId={setSelPos} snap={snap} newFlow={newFlow} />}
      {tab === 'shiftplan' && <ShiftPlan key={tlDefault} employees={employees} positions={positions} groupOrder={groupOrder} initialMode={tlDefault} assign={assignMap} overrides={overrides} setOverrides={setOverrides} sched={sched} onSolve={solveNow} onPause={pauseSolver} />}
      {tab === 'settings' && <SettingsView prefs={prefs} setPref={setPref} fonts={FONTS} settings={settings} setSettings={setSettings} sched={sched} />}
    </div>
  );
}

function SolverBadge({ status }) {
  const active = status === 'SOLVING_ACTIVE' || status === 'SOLVING_SCHEDULED';
  return (
    <span className={`solver-badge ${active ? 'on' : ''}`} title={active ? 'Solver running' : 'Solver idle (steady state)'}>
      <span className="dot"></span>{active ? 'Solving…' : 'Steady'}
    </span>
  );
}
