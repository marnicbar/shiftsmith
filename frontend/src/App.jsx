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
import { applyProblemChanges } from './lib/sync.js';
import { dispatchChange, etagNum, upsertById, removeById, isStaleEcho, mergeVersionsMax, foldRemoteEntity } from './lib/deltas.js';

const TABS = [
  { id: 'dashboard', labelKey: 'nav.dashboard', icon: 'grid' },
  { id: 'personnel', labelKey: 'nav.personnel', icon: 'users' },
  { id: 'positions', labelKey: 'nav.positions', icon: 'briefcase' },
  { id: 'shiftplan', labelKey: 'nav.shiftPlan', icon: 'timeline' },
];

// Scopes the Shift Plan tab morphs into while active (see the nav below).
const PLAN_SCOPES = ['overview', 'personnel', 'positions'];

const UI_FONT = "'Geist', system-ui, sans-serif";

const PREF_DEFAULTS = {
  dark: false, lang: 'en',
  snapLabel: '15 min', tlDefaultLabel: 'Week',
  nameOrder: 'first',
};

const SNAP_MAP = { '15 min': 15, '30 min': 30, '60 min': 60 };
const TL_MAP = { 'Day': 'day', 'Week': 'week', 'Continuous': 'free' };
const PREF_KEY = 'shiftsmith.prefs';

const initialPrefs = (() => {
  try { return { ...PREF_DEFAULTS, ...JSON.parse(localStorage.getItem(PREF_KEY) || '{}') }; }
  catch { return PREF_DEFAULTS; }
})();

// Apply theme immediately on module load to avoid a flash of unstyled content.
Theme.applyTheme({ dark: initialPrefs.dark });
document.documentElement.style.setProperty('--ui-font', UI_FONT);

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
  // SSE liveness: false while the stream is dropped/reconnecting (shown to the user).
  const [streamLive, setStreamLive] = useState(true);
  const schedTimerRef = useRef(null);
  // Sticky flag: a pending schedule refetch should also reconcile the overrides map
  // (set by a remote pin/unpin event or a reconnect catch-up, not by plain solver ticks).
  const wantOverridesRef = useRef(false);
  // Mirrors whether we currently believe the solver is running, read from the SSE handler
  // (a ref, so a status change doesn't re-create the handler and resubscribe the stream).
  const solvingRef = useRef(false);

  // Auth gate: 'checking' until we know, then 'in' (show the app) or 'out' (show login).
  const [authState, setAuthState] = useState('checking');
  const [authUser, setAuthUser] = useState(null);
  // While set, the account is on a seeded password that must be rotated before
  // the app loads — the backend blocks every other endpoint until it is.
  const [mustChangePassword, setMustChangePassword] = useState(false);

  const lastSyncRef = useRef(null);
  // Per-resource versions (ETags) from the snapshot, threaded into granular writes so a
  // stale edit is a 409 instead of a silent overwrite (issue #47, Phase 4).
  const versionsRef = useRef({ employees: {}, positions: {}, settings: 0 });

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

  useEffect(() => {
    solvingRef.current = sched.solverStatus === 'SOLVING_ACTIVE' || sched.solverStatus === 'SOLVING_SCHEDULED';
  }, [sched.solverStatus]);

  useEffect(() => { Theme.applyTheme({ dark: prefs.dark }); }, [prefs.dark]);
  useEffect(() => { if (prefs.lang && i18n.language !== prefs.lang) i18n.changeLanguage(prefs.lang); }, [prefs.lang]);

  const setMeta = useCallback((d) => {
    // Fold the snapshot's versions into ours by per-row max (incl. other clients' edits
    // over SSE), never regressing a row below a version we've already written — a
    // wholesale replace from a snapshot read just before our latest write committed
    // would roll it back and make our own next echo look like a remote change (#47).
    if (d.versions) versionsRef.current = mergeVersionsMax(versionsRef.current, d.versions);
    setSched({
      assignments: d.assignments || [], solverStatus: d.solverStatus, score: d.score,
      total: d.total, staffed: d.staffed, unassigned: d.unassigned,
      horizonStart: d.horizonStart, horizonEnd: d.horizonEnd,
    });
  }, []);

  // Load the whole problem from the backend (initial boot, and to recover from a
  // concurrency conflict). Resets the sync baseline and version map.
  const loadProblem = useCallback(async () => {
    const d = await api.getSchedule();
    setEmployees(d.employees); setPositions(d.positions);
    setSettings(d.settings || { horizonUnit: 'week', horizonCount: 1 });
    setOverrides(d.overrides || {});
    setSelEmp(d.employees[0]?.id ?? null);
    setSelPos(d.positions[0]?.id ?? null);
    const g = []; (d.positions || []).forEach((p) => { if (p.group && !g.includes(p.group)) g.push(p.group); });
    setGroupOrder(g);
    setMeta(d);
    versionsRef.current = d.versions || { employees: {}, positions: {}, settings: 0 };
    lastSyncRef.current = JSON.stringify({ employees: d.employees, positions: d.positions, settings: d.settings, overrides: d.overrides || {} });
  }, [setMeta]);

  // Initial load from the backend, once the session is established (and the
  // account isn't gated behind a forced password change).
  useEffect(() => {
    if (authState !== 'in' || mustChangePassword) return;
    (async () => {
      try {
        await loadProblem();
        setError(null);
        setLoaded(true);
      } catch (e) { reportError(e); }
    })();
  }, [authState, mustChangePassword, loadProblem, reportError]);

  // Apply a remote change to one list resource, keeping both the live state and the
  // sync baseline in step (so our own debounced sync doesn't echo it back). Only the
  // affected entity is touched, preserving any unsynced local edits to others.
  const applyRemoteList = useCallback((setList, kind, id, data) => {
    const prev = JSON.parse(lastSyncRef.current || '{"employees":[],"positions":[],"settings":{},"overrides":{}}');
    // Fold against the last-synced baseline so a stale echo of our own write can't revert
    // an unsynced local edit (e.g. characters typed since the last sync); the pending
    // debounced sync then pushes that local edit on top of what the server now holds.
    setList((list) => foldRemoteEntity(list, prev[kind] || [], id, data));
    prev[kind] = data ? upsertById(prev[kind] || [], data) : removeById(prev[kind] || [], id);
    lastSyncRef.current = JSON.stringify(prev);
  }, []);

  // Adopt the backend's overrides (manual pins) from a fresh snapshot, keeping any
  // edit we haven't synced yet. Pins have no per-row ETag, so we can't skip our own
  // change by version (like refetchEntity does); instead we diff against the sync
  // baseline and preserve only the keys we've changed locally but not yet pushed.
  // Without this, a remote pin/unpin updates the rendered roster but leaves this
  // client's `overrides` (the "manually set" badge / AssignEditor state) stale.
  const reconcileOverrides = useCallback((remote) => {
    const base = JSON.parse(lastSyncRef.current || '{"employees":[],"positions":[],"settings":{},"overrides":{}}');
    const baseOv = base.overrides || {};
    setOverrides((local) => {
      const merged = { ...remote };
      let changed = Object.keys(merged).length !== Object.keys(local).length;
      const keys = new Set([...Object.keys(local), ...Object.keys(baseOv)]);
      for (const k of keys) {
        // A key whose local value diverges from the last-synced baseline is an unsynced
        // local edit — keep it (a local deletion means drop the key the server still has).
        if (JSON.stringify(local[k]) !== JSON.stringify(baseOv[k])) {
          if (k in local) merged[k] = local[k];
          else delete merged[k];
        }
      }
      if (!changed) {
        for (const k of new Set([...Object.keys(merged), ...Object.keys(local)])) {
          if (JSON.stringify(merged[k]) !== JSON.stringify(local[k])) { changed = true; break; }
        }
      }
      return changed ? merged : local; // keep the same ref when nothing moved (no needless re-render)
    });
    // The baseline now reflects what the server holds, so the next debounced diff only
    // carries the still-unsynced local pin edits we preserved above.
    base.overrides = remote;
    lastSyncRef.current = JSON.stringify(base);
  }, []);

  // Refetch one resource on its change event — unless the event is an echo of a change we
  // already hold (our own edit, or a now-superseded one): versions are monotonic, so any
  // rev at or below ours is stale and refetching it would risk reverting an unsynced local
  // edit. Only a strictly newer rev is a genuine remote change. A 404 means it was deleted.
  const refetchEntity = useCallback(async (kind, setList, id, rev) => {
    const get = kind === 'employees' ? api.getEmployee : api.getPosition;
    const bag = versionsRef.current[kind] || (versionsRef.current[kind] = {});
    if (isStaleEcho(rev, bag[id])) return;
    try {
      const { data, etag } = await get(id);
      bag[id] = etagNum(etag);
      applyRemoteList(setList, kind, id, data);
    } catch (e) {
      if (e && e.status === 404) { delete bag[id]; applyRemoteList(setList, kind, id, null); }
      else reportError(e);
    }
  }, [applyRemoteList, reportError]);

  const refetchSettings = useCallback(async (rev) => {
    if (isStaleEcho(rev, versionsRef.current.settings)) return;
    try {
      const { data, etag } = await api.getSettings();
      versionsRef.current.settings = etagNum(etag);
      setSettings(data);
      const prev = JSON.parse(lastSyncRef.current || '{"settings":{}}');
      prev.settings = data; lastSyncRef.current = JSON.stringify(prev);
    } catch (e) { reportError(e); }
  }, [reportError]);

  // The solver advanced or pins changed: refetch the live schedule (assignments + score
  // + versions), debounced so the solver's rapid ticks coalesce into one fetch. When the
  // refetch was prompted by a pin change (or a reconnect catch-up) it also reconciles the
  // overrides map from the same snapshot, so plain solver ticks stay cheap.
  const refetchSchedule = useCallback((withOverrides = false) => {
    if (withOverrides) wantOverridesRef.current = true;
    clearTimeout(schedTimerRef.current);
    schedTimerRef.current = setTimeout(async () => {
      const wantOv = wantOverridesRef.current;
      wantOverridesRef.current = false;
      try {
        const d = await api.getSchedule();
        setMeta(d);
        if (wantOv) reconcileOverrides(d.overrides || {});
      } catch { /* transient — the next event retries */ }
    }, 400);
  }, [setMeta, reconcileOverrides]);

  const handleEvent = useCallback((ev) => dispatchChange(ev, {
    employee: (id, rev) => refetchEntity('employees', setEmployees, id, rev),
    position: (id, rev) => refetchEntity('positions', setPositions, id, rev),
    settings: refetchSettings,
    schedule: refetchSchedule,
    // A pin/unpin from another client: refresh the roster *and* the overrides map.
    assignment: () => refetchSchedule(true),
    // While we believe the solver is running, reconcile on each heartbeat: the
    // solver-went-idle event is one-shot and never re-asserted, so a client that
    // missed it (a drop during the reconnect gap) would otherwise show "solving"
    // forever. Cheap — only fires every 25s, and only while we think we're solving.
    heartbeat: () => { if (solvingRef.current) refetchSchedule(); },
    reload: () => loadProblem().catch(reportError),
  }), [refetchEntity, refetchSettings, refetchSchedule, loadProblem, reportError]);

  // Live updates: subscribe to the backend's typed SSE change events once loaded. Each
  // event refetches only the affected slice; a drop flips the visible "reconnecting" state.
  // onOpen refetches the schedule on every (re)connect — including the first — which also
  // closes the window between the initial GET and the EventSource handshake: a solver
  // improvement emitted in that gap (and otherwise lost, since the stream is delta-only)
  // is caught by this catch-up rather than left stale until the next edit (#40).
  useEffect(() => {
    if (!loaded) return;
    return api.subscribeSchedule(
      handleEvent,
      () => setStreamLive(false),
      () => { setStreamLive(true); refetchSchedule(true); },
    );
  }, [loaded, handleEvent, refetchSchedule]);

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
          // Translate the edit into granular, concurrency-safe calls (replacing the bulk
          // PUT). Versions thread through versionsRef, mutated in place as writes land.
          const prev = lastSyncRef.current
            ? JSON.parse(lastSyncRef.current)
            : { employees: [], positions: [], settings: {}, overrides: {} };
          await applyProblemChanges(prev, problem, versionsRef.current);
          if (cancelled) return;
          lastSyncRef.current = ser;
          setError(null);
        } catch (e) {
          if (cancelled) return;
          // A 409 means someone else changed this resource: reload and let the user redo
          // their edit, rather than clobbering the other change or retrying a stale write.
          if (e && e.status === 409) {
            setNotice(t('app.reloadedAfterConflict'));
            try { await loadProblem(); } catch (re) { reportError(re); }
            return;
          }
          reportError(e);
          const retriable = !e || !e.status || e.status >= 500;
          if (retriable) attempt(Math.min(delay * 2, 16000));
        }
      }, delay);
    };
    attempt(600);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [loaded, employees, positions, settings, overrides, reportError, t, loadProblem]);

  const snap = SNAP_MAP[prefs.snapLabel] ?? 15;
  const newFlow = 'quick';
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
      {!streamLive && <div className="api-notice" role="status">{t('app.reconnecting')}</div>}

      {tab === 'dashboard' && <Dashboard employees={employees} positions={positions} assign={assignMap} onOpenShift={openShift} />}
      {tab === 'personnel' && <Personnel employees={employees} setEmployees={setEmployees} skills={skills} settings={settings} selId={selEmp} setSelId={setSelEmp} snap={snap} newFlow={newFlow} nameOrder={prefs.nameOrder} />}
      {tab === 'positions' && <Positions employees={employees} positions={positions} setPositions={setPositions} groupOrder={groupOrder} setGroupOrder={setGroupOrder} skills={skills} selId={selPos} setSelId={setSelPos} snap={snap} newFlow={newFlow} nameOrder={prefs.nameOrder} />}
      {tab === 'shiftplan' && <PlanView key={tlDefault} scope={planScope} employees={employees} positions={positions} groupOrder={groupOrder} initialMode={tlDefault} assign={assignMap} overrides={overrides} setOverrides={setOverrides} sched={sched} onSolve={solveNow} onPause={pauseSolver} focus={focusShift} onFocusConsumed={() => setFocusShift(null)} nameOrder={prefs.nameOrder} selEmp={selEmp} setSelEmp={setSelEmp} selPos={selPos} setSelPos={setSelPos} />}
      {tab === 'settings' && <SettingsView settings={settings} setSettings={setSettings} sched={sched} skills={skills} onAddSkill={addSkill} onRenameSkill={renameSkill} onRemoveSkill={removeSkill} globalRules={settings.globalRules || []} setGlobalRules={setGlobalRules} />}
      {tab === 'account' && <AccountView prefs={prefs} setPref={setPref} authUser={authUser} />}
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
