// App.jsx — shell: top tab bar, settings, view routing, backend sync + solver polling.
import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { Theme } from './theme.js';
import { Ic } from './icons.jsx';
import { SS } from './data.js';
import i18n from './i18n/index.js';
import { Personnel } from './personnel.jsx';
import { Positions } from './positions.jsx';
import { PlanView } from './planview.jsx';
import { Dashboard } from './dashboard.jsx';
import { SettingsView, AccountView } from './settings.jsx';
import { Login, ForcePasswordChange } from './login.jsx';
import { tooLooseAgainst } from './rules.jsx';
import * as api from './lib/api.js';

const TABS = [
  { id: 'dashboard', labelKey: 'nav.dashboard', icon: 'grid' },
  { id: 'personnel', labelKey: 'nav.personnel', icon: 'users' },
  { id: 'positions', labelKey: 'nav.positions', icon: 'briefcase' },
  { id: 'shiftplan', labelKey: 'nav.shiftPlan', icon: 'timeline' },
];

// Scopes the Shift Plan tab morphs into while active (see the nav below).
const PLAN_SCOPES = ['overview', 'personnel', 'positions'];

export const FONTS = {
  'Geist':          "'Geist', system-ui, sans-serif",
  'Helvetica Neue': "'Helvetica Neue', Helvetica, Arial, sans-serif",
  'Figtree':        "'Figtree', system-ui, sans-serif",
};

const PREF_DEFAULTS = {
  dark: false, palette: 'slate', accent: 'indigo', font: 'Geist', lang: 'en',
  snapLabel: '15 min', newFlowLabel: 'Paint, then tweak', tlDefaultLabel: 'Week',
  nameOrder: 'first',
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
  const { t } = useTranslation();
  const [prefs, setPref] = usePrefs();
  const [tab, setTab] = useState('personnel');
  // Sub-view of the Shift Plan tab; the tab button itself hosts the selector.
  const [planScope, setPlanScope] = useState('overview');
  const [acctMenu, setAcctMenu] = useState(false);
  // Remember the last primary view so a settings/account panel can toggle back to it.
  const prevTabRef = useRef('personnel');
  const PANELS = ['settings', 'account'];
  const openPanel = (id) => {
    if (tab === id) { setTab(prevTabRef.current); }
    else { if (!PANELS.includes(tab)) prevTabRef.current = tab; setTab(id); }
  };

  // Problem state (client-authoritative, synced to the backend).
  const [employees, setEmployees] = useState([]);
  const [positions, setPositions] = useState([]);
  const [settings, setSettings] = useState({ horizonUnit: 'week', horizonCount: 1 });
  const [overrides, setOverrides] = useState({});
  const [groupOrder, setGroupOrder] = useState([]);
  const [selEmp, setSelEmp] = useState(null);
  const [selPos, setSelPos] = useState(null);
  // When set, the shift plan opens this shift's assignment editor on mount.
  const [focusShift, setFocusShift] = useState(null);

  // Solver result + status (read-only, refreshed by polling).
  const [sched, setSched] = useState({ assignments: [], solverStatus: 'NOT_SOLVING', score: null, total: 0, staffed: 0, unassigned: 0 });
  const [loaded, setLoaded] = useState(false);
  // Banner state: { text, validation }. A validation error carries the backend's
  // explanation (e.g. "the solve window is too long") and shows it directly; any
  // other failure is treated as a connectivity problem.
  const [error, setError] = useState(null);
  const reportError = useCallback((e) => {
    // A 400 is a validation rejection we can explain verbatim; anything else (a
    // network drop, or a 5xx such as a failed persist) is a transient connectivity
    // problem the sync effect retries in the background.
    if (e && e.status === 400 && e.serverMessage) setError({ text: e.serverMessage, validation: true });
    else setError({ text: e?.message ?? String(e), validation: false });
  }, []);
  const [notice, setNotice] = useState(null);

  // Auth gate: 'checking' until we know, then 'in' (show the app) or 'out' (show login).
  const [authState, setAuthState] = useState('checking');
  const [authUser, setAuthUser] = useState(null);
  // While set, the account is on a seeded password that must be rotated before
  // the app loads — the backend blocks every other endpoint until it is.
  const [mustChangePassword, setMustChangePassword] = useState(false);

  const lastSyncRef = useRef(null);

  // On startup, validate any stored token. A 401 on any later request (e.g. an
  // expired token) drops us back to the login screen via the shared handler.
  useEffect(() => {
    api.setUnauthorizedHandler(() => { setAuthState('out'); setAuthUser(null); setLoaded(false); });
    (async () => {
      const u = await api.me();
      if (u) { setAuthUser(u.username); setMustChangePassword(u.mustChangePassword); setAuthState('in'); }
      else { setAuthState('out'); }
    })();
    return () => api.setUnauthorizedHandler(null);
  }, []);

  const onLogin = useCallback((u, mustChange) => { setAuthUser(u); setMustChangePassword(!!mustChange); setAuthState('in'); }, []);
  const onLogout = useCallback(() => {
    api.logout();
    setAuthState('out'); setAuthUser(null); setMustChangePassword(false); setLoaded(false);
    setEmployees([]); setPositions([]); setOverrides({}); setSelEmp(null); setSelPos(null);
    lastSyncRef.current = null;
  }, []);

  useEffect(() => { Theme.applyTheme({ palette: prefs.palette, accent: prefs.accent, dark: prefs.dark }); }, [prefs.palette, prefs.accent, prefs.dark]);
  useEffect(() => { document.documentElement.style.setProperty('--ui-font', FONTS[prefs.font] || FONTS.Geist); }, [prefs.font]);
  useEffect(() => { if (prefs.lang && i18n.language !== prefs.lang) i18n.changeLanguage(prefs.lang); }, [prefs.lang]);

  const setMeta = useCallback((d) => setSched({
    assignments: d.assignments || [], solverStatus: d.solverStatus, score: d.score,
    total: d.total, staffed: d.staffed, unassigned: d.unassigned,
    horizonStart: d.horizonStart, horizonEnd: d.horizonEnd,
  }), []);

  // Initial load from the backend, once the session is established (and the
  // account isn't gated behind a forced password change).
  useEffect(() => {
    if (authState !== 'in' || mustChangePassword) return;
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
        setError(null);
        setLoaded(true);
      } catch (e) { reportError(e); }
    })();
  }, [authState, mustChangePassword, setMeta, reportError]);

  // Live updates: subscribe to the backend's SSE stream once loaded. The solver's
  // progress, our own edits and other clients' edits all arrive here, so the
  // timeline, score and status stay current without polling.
  useEffect(() => {
    if (!loaded) return;
    return api.subscribeSchedule(setMeta);
  }, [loaded, setMeta]);

  // Debounced sync: push the problem to the backend whenever the user edits it.
  useEffect(() => {
    if (!loaded) return;
    const problem = { employees, positions, settings, overrides };
    const ser = JSON.stringify(problem);
    if (ser === lastSyncRef.current) return;
    // Catch an over-long solve window here, with a localized message: the backend
    // also rejects it (400), but its message isn't translated, so we'd rather
    // explain it in the user's language and skip the doomed request.
    const days = SS.horizonDays(settings);
    if (days > SS.MAX_HORIZON_DAYS) {
      setError({ text: t('app.horizonTooLong', { days, max: SS.MAX_HORIZON_DAYS }), validation: true });
      return;
    }
    // Debounce the first attempt by 600ms; on a transient failure (network drop or
    // 5xx such as a failed persist) keep retrying with exponential backoff so the
    // edit isn't silently lost the moment the user stops typing. A 400 won't fix
    // itself, so we surface it and stop. A fresh edit re-runs the effect, which
    // cancels this loop (clearing the banner on the next success).
    let cancelled = false;
    let timer;
    const attempt = (delay) => {
      timer = setTimeout(async () => {
        try {
          await api.putProblem(problem);
          if (cancelled) return;
          lastSyncRef.current = ser;
          setError(null);
        } catch (e) {
          if (cancelled) return;
          reportError(e);
          const retriable = !e || !e.status || e.status >= 500;
          if (retriable) attempt(Math.min(delay * 2, 16000));
        }
      }, delay);
    };
    attempt(600);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [loaded, employees, positions, settings, overrides, reportError, t]);

  const snap = SNAP_MAP[prefs.snapLabel] ?? 15;
  const newFlow = FLOW_MAP[prefs.newFlowLabel] ?? 'quick';
  const tlDefault = TL_MAP[prefs.tlDefaultLabel] ?? 'week';

  // Skill catalogue lives in settings (managed on the Settings page). Renames and
  // removals cascade into every employee/position/shift that references the skill,
  // so the catalogue and the data never drift apart.
  const skills = settings.skills ?? [];
  const uniq = (arr) => arr.filter((v, i) => arr.indexOf(v) === i);
  const addSkill = useCallback((raw) => {
    const name = (raw || '').trim();
    setSettings((s) => {
      const list = s.skills ?? [];
      if (!name || list.includes(name)) return s;
      return { ...s, skills: [...list, name] };
    });
  }, []);
  const renameSkill = useCallback((oldName, raw) => {
    const name = (raw || '').trim();
    if (!name || name === oldName) return;
    const map = (arr = []) => uniq(arr.map((x) => (x === oldName ? name : x)));
    setSettings((s) => { const list = s.skills ?? []; return { ...s, skills: map(list) }; });
    setEmployees((es) => es.map((e) => ({ ...e, skills: map(e.skills) })));
    setPositions((ps) => ps.map((p) => ({ ...p, skills: map(p.skills), shifts: p.shifts.map((sh) => ({ ...sh, skills: map(sh.skills) })) })));
  }, []);
  // Global working-time rules live in settings and apply to everyone. When they
  // change, any personal rule that is now *looser* than the new system limit is
  // tightened to it (a personal rule can only be stricter), and we warn about it.
  const setGlobalRules = useCallback((nextRules) => {
    setSettings((s) => ({ ...s, globalRules: nextRules }));
    const touched = [];
    const next = employees.map((e) => {
      let changed = false;
      const rules = (e.rules || []).map((r) => {
        const g = nextRules.find((x) => x.metric === r.metric && x.op === r.op);
        if (!g) return r;
        const bound = tooLooseAgainst(r.op, r.value, g.value);
        if (bound != null) { changed = true; return { ...r, value: bound }; }
        return r;
      });
      if (changed) touched.push(SS.fullName(e, prefs.nameOrder));
      return changed ? { ...e, rules } : e;
    });
    if (touched.length) {
      setEmployees(next);
      const names = touched.length > 3
        ? t('app.namesMore', { names: touched.slice(0, 3).join(', '), count: touched.length - 3 })
        : touched.join(', ');
      setNotice(t('app.tightenedRules', { names }));
    }
  }, [employees, t, prefs.nameOrder]);

  const removeSkill = useCallback((name) => {
    const drop = (arr = []) => arr.filter((x) => x !== name);
    setSettings((s) => { const list = s.skills ?? []; return { ...s, skills: drop(list) }; });
    setEmployees((es) => es.map((e) => ({ ...e, skills: drop(e.skills) })));
    setPositions((ps) => ps.map((p) => ({ ...p, skills: drop(p.skills), shifts: p.shifts.map((sh) => ({ ...sh, skills: drop(sh.skills) })) })));
  }, []);

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

  // Jump from the dashboard's "needs attention" list straight to a shift in the plan.
  const openShift = useCallback((shiftId, date) => { setFocusShift({ shiftId, date }); setPlanScope('overview'); setTab('shiftplan'); }, []);

  async function solveNow() { try { await api.startSolving(); } catch (e) { reportError(e); } }
  async function pauseSolver() { try { await api.stopSolving(); } catch (e) { reportError(e); } }

  if (authState === 'checking') {
    return <div className="app"><div className="loading">{t('app.loading')}</div></div>;
  }
  if (authState === 'out') {
    return <Login onSuccess={onLogin} />;
  }
  if (mustChangePassword) {
    return <ForcePasswordChange username={authUser} onDone={() => setMustChangePassword(false)} />;
  }

  if (!loaded && !error) {
    return <div className="app"><div className="loading">{t('app.loading')}</div></div>;
  }

  return (
    <div className="app">
      <div className="topbar">
        <div className="brand"><span className="logo">S</span><span className="brand-name"><b>Shift</b>Smith</span></div>
        <nav className="tabs">
          {TABS.map((x) => {
            const pill = x.id === 'shiftplan' && sched.unassigned > 0 ? <span className="pill">{sched.unassigned}</span> : null;
            // While active, the Shift Plan tab morphs into its scope selector,
            // expanding to the right at the same height.
            if (x.id === 'shiftplan' && tab === 'shiftplan') {
              return (
                <div key={x.id} className="tab active tab-morph">
                  <span className="tab-lead">{React.createElement(Ic[x.icon], { size: 16 })}{t(x.labelKey)}{pill}</span>
                  <span className="tab-scope">
                    {PLAN_SCOPES.map((s) => (
                      <button key={s} className={planScope === s ? 'on' : ''} onClick={() => setPlanScope(s)}>{t(`plan.scope.${s}`)}</button>
                    ))}
                  </span>
                </div>
              );
            }
            return (
              <button key={x.id} className={`tab ${tab === x.id ? 'active' : ''}`} onClick={() => setTab(x.id)}>
                {React.createElement(Ic[x.icon], { size: 16 })}{t(x.labelKey)}{pill}
              </button>
            );
          })}
        </nav>
        <div className="spacer"></div>
        <SolverBadge status={sched.solverStatus} />
        <button className={`iconbtn ${tab === 'settings' ? 'active' : ''}`} title={t('nav.settings')} onClick={() => openPanel('settings')}>
          <Ic.settings/>
        </button>
        <div className="acct-btn-wrap">
          <button className={`iconbtn ${tab === 'account' || acctMenu ? 'active' : ''}`} title={t('account.title')}
            aria-haspopup="menu" aria-expanded={acctMenu} onClick={() => setAcctMenu((o) => !o)}>
            <Ic.user/>
          </button>
          {acctMenu && (
            <>
              <div className="menu-backdrop" onClick={() => setAcctMenu(false)}></div>
              <div className="mini-menu" role="menu">
                <div className="acct-menu-head">{authUser}</div>
                <button role="menuitem" onClick={() => { setAcctMenu(false); openPanel('account'); }}><Ic.sliders size={15}/> {t('account.menuSettings')}</button>
                <button role="menuitem" onClick={() => { setAcctMenu(false); onLogout(); }}><Ic.logout size={15}/> {t('account.signOut')}</button>
              </div>
            </>
          )}
        </div>
      </div>

      {error && (
        <div className="api-error">
          {error.validation
            ? t('app.invalidInput', { error: error.text })
            : t('app.backendError', { error: error.text })}
        </div>
      )}
      {notice && <div className="api-notice">{notice}<button className="notice-x" onClick={() => setNotice(null)} title={t('common.dismiss')}><Ic.x size={14}/></button></div>}

      {tab === 'dashboard' && <Dashboard employees={employees} positions={positions} assign={assignMap} onOpenShift={openShift} />}
      {tab === 'personnel' && <Personnel employees={employees} setEmployees={setEmployees} skills={skills} settings={settings} selId={selEmp} setSelId={setSelEmp} snap={snap} newFlow={newFlow} nameOrder={prefs.nameOrder} />}
      {tab === 'positions' && <Positions employees={employees} positions={positions} setPositions={setPositions} groupOrder={groupOrder} setGroupOrder={setGroupOrder} skills={skills} selId={selPos} setSelId={setSelPos} snap={snap} newFlow={newFlow} nameOrder={prefs.nameOrder} />}
      {tab === 'shiftplan' && <PlanView key={tlDefault} scope={planScope} employees={employees} positions={positions} groupOrder={groupOrder} initialMode={tlDefault} assign={assignMap} overrides={overrides} setOverrides={setOverrides} sched={sched} onSolve={solveNow} onPause={pauseSolver} focus={focusShift} onFocusConsumed={() => setFocusShift(null)} nameOrder={prefs.nameOrder} selEmp={selEmp} setSelEmp={setSelEmp} selPos={selPos} setSelPos={setSelPos} />}
      {tab === 'settings' && <SettingsView settings={settings} setSettings={setSettings} sched={sched} skills={skills} onAddSkill={addSkill} onRenameSkill={renameSkill} onRemoveSkill={removeSkill} globalRules={settings.globalRules || []} setGlobalRules={setGlobalRules} />}
      {tab === 'account' && <AccountView prefs={prefs} setPref={setPref} fonts={FONTS} authUser={authUser} />}
    </div>
  );
}

function SolverBadge({ status }) {
  const { t } = useTranslation();
  const active = status === 'SOLVING_ACTIVE' || status === 'SOLVING_SCHEDULED';
  return (
    <span className={`solver-badge ${active ? 'on' : ''}`} title={active ? t('solver.running') : t('solver.idle')}>
      <span className="dot"></span>{active ? t('solver.solving') : t('solver.steady')}
    </span>
  );
}
