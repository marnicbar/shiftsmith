// rules.jsx — working-time rules editor, shared by Personnel (personal rules) and
// Settings (global rules). Rules carry scheduled future `changes`; each metric+op
// may be defined only once; edits are staged in a draft and saved on Apply. In
// personal mode the global rules are surfaced read-only and a person can only add
// a stricter override on top of them.
import React, { useState as useStateR } from 'react';
import { SS } from './data.js';
import { Ic } from './icons.jsx';

export const METRICS = {
  dayHours:   { label: 'Daily hours',         short: 'daily hours',      unit: 'h', icon: 'clock' },
  weekHours:  { label: 'Weekly hours',        short: 'weekly hours',     unit: 'h', icon: 'calendar' },
  monthHours: { label: 'Monthly hours',       short: 'monthly hours',    unit: 'h', icon: 'grid' },
  consecDays: { label: 'Consecutive days',    short: 'consecutive days', unit: 'd', icon: 'repeat' },
  restHours:  { label: 'Rest between shifts', short: 'rest',             unit: 'h', icon: 'moon' },
};
export const OPS = { preferred: 'Preferred', min: 'At least', max: 'At most' };
export const isHard = (op) => op !== 'preferred';
export const ruleKey = (r) => `${r.metric}:${r.op}`;

/**
 * A personal rule may only be *stricter* than the global one for the same
 * metric+op: a lower ceiling ("at most") or a higher floor ("at least").
 * "Preferred" is just a preference, so anything goes. Returns the value the
 * personal rule would have to be clamped to, or null if it's already valid.
 */
export function tooLooseAgainst(op, value, globalValue) {
  if (op === 'max' && value > globalValue) return globalValue;
  if (op === 'min' && value < globalValue) return globalValue;
  return null;
}

/** Resolve a rule's metric/op/value on an ISO date, applying scheduled changes. */
export function ruleEffectiveAt(rule, iso) {
  let metric = rule.metric, op = rule.op, value = rule.value, active = true;
  const applicable = (rule.changes || []).filter((c) => c.date && c.date <= iso).slice()
    .sort((a, b) => a.date.localeCompare(b.date));
  for (const c of applicable) {
    if (c.kind === 'remove') active = false;
    else { active = true; if (c.metric) metric = c.metric; if (c.op) op = c.op; value = c.value; }
  }
  return { active, metric, op, value };
}

const dLabel = (iso) => SS.parseISO(iso).toLocaleDateString([], { month: 'short', day: 'numeric', year: SS.parseISO(iso).getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined });

function periodOf(metric) {
  if (metric === 'weekHours') return 'week';
  if (metric === 'monthHours') return 'month';
  return 'day';
}
const CP_WD = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];

function CalPicker({ period, kind, onPick, onClose }) {
  const [vm, setVm] = useStateR(() => { const t = new Date(); return new Date(t.getFullYear(), t.getMonth(), 1); });
  const todayISO = SS.isoOf(new Date());
  const head = kind === 'apply' ? 'Apply change starting' : 'Remove rule starting';

  if (period === 'month') {
    const yr = vm.getFullYear();
    const thisM = new Date().getFullYear() === yr ? new Date().getMonth() : -1;
    return (
      <>
        <div className="menu-backdrop" onClick={onClose}></div>
        <div className="date-menu cal-pick" onClick={(e) => e.stopPropagation()}>
          <div className="dm-head">{head}</div>
          <div className="cp-nav">
            <button className="cp-arrow" onClick={() => setVm(new Date(yr - 1, 0, 1))}><Ic.chevL size={14}/></button>
            <span className="cp-month">{yr}</span>
            <button className="cp-arrow" onClick={() => setVm(new Date(yr + 1, 0, 1))}><Ic.chevR size={14}/></button>
          </div>
          <div className="cp-months">
            {Array.from({ length: 12 }, (_, i) => (
              <button key={i} className={`cp-mo ${i === thisM ? 'today' : ''}`} onClick={() => onPick(SS.isoOf(new Date(yr, i, 1)))}>
                {new Date(yr, i, 1).toLocaleDateString([], { month: 'short' })}
              </button>
            ))}
          </div>
          <div className="cp-hint">Starts on the 1st</div>
        </div>
      </>
    );
  }

  const first = new Date(vm.getFullYear(), vm.getMonth(), 1);
  const gridStart = SS.startOfWeek(first);
  const weeks = Array.from({ length: 6 }, (_, w) => Array.from({ length: 7 }, (_, i) => SS.addDays(gridStart, w * 7 + i)));
  const mon = vm.getMonth();
  const step = (n) => setVm(new Date(vm.getFullYear(), vm.getMonth() + n, 1));
  return (
    <>
      <div className="menu-backdrop" onClick={onClose}></div>
      <div className="date-menu cal-pick" onClick={(e) => e.stopPropagation()}>
        <div className="dm-head">{head}</div>
        <div className="cp-nav">
          <button className="cp-arrow" onClick={() => step(-1)}><Ic.chevL size={14}/></button>
          <span className="cp-month">{vm.toLocaleDateString([], { month: 'long', year: 'numeric' })}</span>
          <button className="cp-arrow" onClick={() => step(1)}><Ic.chevR size={14}/></button>
        </div>
        <div className="cp-dow">{CP_WD.map((w) => <span key={w}>{w}</span>)}</div>
        <div className={`cp-grid ${period}`}>
          {weeks.map((wk, wi) => (
            <div key={wi} className="cp-week" onClick={period === 'week' ? () => onPick(SS.isoOf(wk[0])) : undefined}>
              {wk.map((d) => {
                const di = SS.isoOf(d), out = d.getMonth() !== mon, today = di === todayISO;
                const cls = `cp-day ${out ? 'out' : ''} ${today ? 'today' : ''}`;
                return period === 'week'
                  ? <span key={di} className={cls}>{d.getDate()}</span>
                  : <button key={di} className={cls} onClick={() => onPick(di)}>{d.getDate()}</button>;
              })}
            </div>
          ))}
        </div>
        <div className="cp-hint">{period === 'week' ? 'Pick a week — starts Monday' : 'Pick any date'}</div>
      </div>
    </>
  );
}

const stop = (e) => e.stopPropagation();

/**
 * Shared rules editor.
 * @param mode 'personal' (rules sit on top of read-only `globalRules`) or 'global'.
 */
export function RulesEditor({ rules, onChange, globalRules = [], mode = 'personal', label = 'Working time rules', hint }) {
  // editing: a staged draft. { ruleId, metric, op, value, locked } — ruleId null = new.
  const [editing, setEditing] = useStateR(null);
  const [menu, setMenu] = useStateR(null); // 'apply' | 'delete'
  const isPersonal = mode === 'personal';
  const todayISO = SS.isoOf(new Date());

  const gByKey = Object.fromEntries((globalRules || []).map((g) => [ruleKey(g), g]));
  const personalByKey = Object.fromEntries(rules.map((r) => [ruleKey(r), r]));
  // A metric+op is "taken" if any rule already uses it. In personal mode, global
  // rules count too (they're overridden via their own card, not re-created here).
  const usedKeys = new Set([...rules.map(ruleKey), ...(isPersonal ? Object.keys(gByKey) : [])]);

  const comboFree = (metric, op, selfKey) => `${metric}:${op}` === selfKey || !usedKeys.has(`${metric}:${op}`);
  const availOps = (metric, selfKey) => Object.keys(OPS).filter((op) => comboFree(metric, op, selfKey));
  const availMetrics = (selfKey) => Object.keys(METRICS).filter((m) => availOps(m, selfKey).length > 0);

  const globalMatch = (metric, op) => (isPersonal ? gByKey[`${metric}:${op}`] : null);

  /** Hold a value within the band a stricter override is allowed to use. */
  function clampValueAt(metric, op, val, iso) {
    const n = Math.max(0, Number.isFinite(val) ? val : 0);
    const g = globalMatch(metric, op);
    if (!g) return n;
    const ge = ruleEffectiveAt(g, iso);
    if (!ge.active) return n;
    if (op === 'max') return Math.min(n, ge.value);
    if (op === 'min') return Math.max(n, ge.value);
    return n; // preferred — unconstrained
  }

  const editKey = editing ? `${editing.metric}:${editing.op}` : null;
  const isNewDraft = editing && editing.ruleId == null && !editing.locked;
  const isOverrideDraft = editing && editing.ruleId == null && editing.locked;
  const selfKeyOf = () => {
    if (!editing || editing.ruleId == null) return null;
    const r = rules.find((x) => x.id === editing.ruleId);
    return r ? ruleKey(r) : null;
  };

  // --- staging -------------------------------------------------------------
  function startNew() {
    const metrics = availMetrics(null);
    if (!metrics.length) return;
    const metric = metrics[0];
    const op = availOps(metric, null)[0];
    setEditing({ ruleId: null, metric, op, value: op === 'preferred' ? 8 : 10, locked: false });
    setMenu(null);
  }
  function startEdit(rule) {
    setEditing({ ruleId: rule.id, metric: rule.metric, op: rule.op, value: rule.value, locked: !!globalMatch(rule.metric, rule.op) });
    setMenu(null);
  }
  function startOverride(g) {
    setEditing({ ruleId: null, metric: g.metric, op: g.op, value: clampValueAt(g.metric, g.op, ruleEffectiveAt(g, todayISO).value, todayISO), locked: true });
    setMenu(null);
  }
  function cancel() { setEditing(null); setMenu(null); }

  function setMetric(metric) {
    const sk = selfKeyOf();
    const ops = availOps(metric, sk);
    const op = ops.includes(editing.op) ? editing.op : ops[0];
    setEditing({ ...editing, metric, op });
  }
  const setOp = (op) => setEditing({ ...editing, op });
  const setVal = (value) => setEditing({ ...editing, value });
  const commitClamp = () => setEditing((e) => e ? { ...e, value: clampValueAt(e.metric, e.op, +e.value, todayISO) } : e);

  // --- saving (only on Apply) ---------------------------------------------
  function applyNow() {
    const value = clampValueAt(editing.metric, editing.op, +editing.value, todayISO);
    if (editing.ruleId == null) {
      onChange([...rules, { id: SS.uid('r'), metric: editing.metric, op: editing.op, value, changes: [] }]);
    } else {
      onChange(rules.map((r) => r.id === editing.ruleId ? { ...r, metric: editing.metric, op: editing.op, value } : r));
    }
    cancel();
  }
  function applyOn(date) {
    const value = clampValueAt(editing.metric, editing.op, +editing.value, date);
    const ch = { id: SS.uid('c'), date, kind: 'set', metric: editing.metric, op: editing.op, value };
    if (editing.ruleId == null) {
      // A not-yet-saved rule scheduled to take effect on `date`: start from the
      // inherited global value (a no-op before the date) so the change is what bites.
      const g = globalMatch(editing.metric, editing.op);
      const baseVal = g ? ruleEffectiveAt(g, todayISO).value : value;
      onChange([...rules, { id: SS.uid('r'), metric: editing.metric, op: editing.op, value: baseVal, changes: [ch] }]);
      cancel();
      return;
    }
    const r = rules.find((x) => x.id === editing.ruleId);
    if (!r) return;
    onChange(rules.map((x) => x.id === r.id
      ? { ...x, changes: [...(x.changes || []).filter((c) => c.date !== date || c.kind !== 'set'), ch].sort((a, b) => a.date.localeCompare(b.date)) }
      : x));
    cancel();
  }
  function deleteNow() { onChange(rules.filter((r) => r.id !== editing.ruleId)); cancel(); }
  function deleteOn(date) {
    const r = rules.find((x) => x.id === editing.ruleId);
    if (!r) return;
    const ch = { id: SS.uid('c'), date, kind: 'remove' };
    onChange(rules.map((x) => x.id === r.id
      ? { ...x, changes: [...(x.changes || []).filter((c) => c.kind !== 'remove'), ch].sort((a, b) => a.date.localeCompare(b.date)) }
      : x));
    cancel();
  }
  function removeChange(ruleId, changeId) {
    onChange(rules.map((x) => x.id === ruleId ? { ...x, changes: (x.changes || []).filter((c) => c.id !== changeId) } : x));
  }

  // --- shared pieces (called as plain functions, not <Components/>, so the value
  //     input keeps focus across re-renders instead of remounting each keystroke) -
  function valueInput() {
    const g = globalMatch(editing.metric, editing.op);
    const ge = g ? ruleEffectiveAt(g, todayISO) : null;
    const hi = editing.op === 'max' && ge && ge.active ? ge.value : undefined;
    const lo = editing.op === 'min' && ge && ge.active ? ge.value : 0;
    return (
      <div className="num">
        <input className="input mono" type="number" min={lo} max={hi} step="1" value={editing.value} autoFocus
          onClick={stop} onChange={(e) => setVal(e.target.value)} onBlur={commitClamp}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applyNow(); } }} />
        <span className="unit">{METRICS[editing.metric].unit}</span>
      </div>
    );
  }
  function changesEl(rule) {
    const changes = rule.changes || [];
    if (!changes.length) return null;
    return (
      <div className="rule-changes">
        {changes.map((c) => (
          <div key={c.id} className="change">
            <Ic.clock size={11}/>
            <span className="ch-txt">
              {c.kind === 'remove' ? <>Remove rule</> : <>→ {OPS[c.op]} {c.value}{METRICS[c.metric].unit}</>}
              <span className="ch-date"> · from {dLabel(c.date)}</span>
            </span>
            <button className="rule-x" onClick={(e) => { e.stopPropagation(); removeChange(rule.id, c.id); }} title="Cancel scheduled change"><Ic.trash size={12}/></button>
          </div>
        ))}
      </div>
    );
  }
  function actionsEl(isNew) {
    return (
      <div className="rule-actions" onClick={stop}>
        <button className="btn ghost sm" onClick={cancel}>Cancel</button>
        <div className="ra-right">
          {!isNew && (
            <div className="splitbtn danger">
              <button className="sb-main" onClick={deleteNow}><Ic.trash size={13}/></button>
              <button className="sb-caret" onClick={() => setMenu(menu === 'delete' ? null : 'delete')}><Ic.chevD size={12}/></button>
              {menu === 'delete' && <CalPicker kind="delete" period={periodOf(editing.metric)} onPick={deleteOn} onClose={() => setMenu(null)} />}
            </div>
          )}
          <div className="splitbtn primary">
            <button className="sb-main" onClick={applyNow}>Apply</button>
            <button className="sb-caret" onClick={() => setMenu(menu === 'apply' ? null : 'apply')}><Ic.chevD size={12}/></button>
            {menu === 'apply' && <CalPicker kind="apply" period={periodOf(editing.metric)} onPick={applyOn} onClose={() => setMenu(null)} />}
          </div>
        </div>
      </div>
    );
  }

  // --- cards ---------------------------------------------------------------
  // A free-standing rule (no global backing); pass null for the new-rule draft.
  function normalCard(rule) {
    const isNew = !rule;
    const sel = isNew ? isNewDraft : (editing && editing.ruleId === rule.id);
    const view = sel ? editing : rule;
    const m = METRICS[view.metric];
    const hard = isHard(view.op);
    const selfKey = isNew ? null : ruleKey(rule);
    return (
      <div key={isNew ? '__new__' : rule.id} className={`rule ${hard ? 'hard' : 'soft'} ${sel ? 'sel' : ''}`} onClick={() => { if (!sel && !isNew) startEdit(rule); }}>
        <div className="rule-top">
          <span className="rule-ic">{React.createElement(Ic[m.icon] || Ic.clock, { size: 14 })}</span>
          {sel
            ? <select className="bare-select rule-name" value={editing.metric} onClick={stop} onChange={(e) => setMetric(e.target.value)}>
                {availMetrics(selfKey).map((k) => <option key={k} value={k}>{METRICS[k].label}</option>)}
              </select>
            : <span className="rule-name">{m.label}</span>}
          <span className={`str-tag ${hard ? 'hard' : 'soft'}`}>{hard ? 'Hard' : 'Soft'}</span>
        </div>
        <div className="rule-bot">
          {sel
            ? <select className="bare-select op" value={editing.op} onClick={stop} onChange={(e) => setOp(e.target.value)}>
                {availOps(editing.metric, selfKey).map((op) => <option key={op} value={op}>{OPS[op]}</option>)}
              </select>
            : <span className="op-static">{OPS[view.op]}</span>}
          {sel ? valueInput() : <div className="num"><span className="num-static mono">{view.value}</span><span className="unit">{m.unit}</span></div>}
        </div>
        {!isNew && changesEl(rule)}
        {sel && actionsEl(isNew)}
      </div>
    );
  }

  // A global rule, with its (optional) stricter personal override stacked below it.
  function globalCard(g) {
    const key = ruleKey(g);
    const personal = personalByKey[key];
    const editingThis = editing && ((personal && editing.ruleId === personal.id) || (isOverrideDraft && editKey === key));
    const hasPersonalRow = !!personal || editingThis;
    const m = METRICS[g.metric];
    const hard = isHard(g.op);
    const gEff = ruleEffectiveAt(g, todayISO);
    return (
      <div key={`g-${key}`} className={`rule rule-stacked ${hard ? 'hard' : 'soft'} ${editingThis ? 'sel' : ''}`}>
        <div className="rule-top">
          <span className="rule-ic">{React.createElement(Ic[m.icon] || Ic.clock, { size: 14 })}</span>
          <span className="rule-name">{m.label}</span>
          <span className={`str-tag ${hard ? 'hard' : 'soft'}`}>{hard ? 'Hard' : 'Soft'}</span>
        </div>
        <div className={`rule-bot rule-row-global ${hasPersonalRow ? 'dim' : ''}`}>
          <span className="op-static">{OPS[g.op]}</span>
          <div className="num"><span className="num-static mono">{gEff.active ? gEff.value : g.value}</span><span className="unit">{m.unit}</span></div>
          <span className={`mini-tag ${hasPersonalRow ? '' : 'accent'}`}>Global</span>
        </div>
        {hasPersonalRow ? (
          <>
            <div className={`rule-bot rule-row-personal ${editingThis ? '' : 'clickable'}`}
              onClick={() => { if (!editingThis && personal) startEdit(personal); }}>
              <span className="op-static">{OPS[g.op]}</span>
              {editingThis ? valueInput() : <div className="num"><span className="num-static mono">{personal.value}</span><span className="unit">{m.unit}</span></div>}
              <span className="mini-tag accent">Personal</span>
            </div>
            {personal && changesEl(personal)}
            {editingThis && actionsEl(isOverrideDraft)}
          </>
        ) : (
          <div className="rule-actions" onClick={stop}>
            <span className="hint" style={{ margin: 0 }}>Applies to everyone</span>
            <div className="ra-right">
              <button className="btn ghost sm" onClick={() => startOverride(g)}>Customize</button>
            </div>
          </div>
        )}
      </div>
    );
  }

  const customRules = isPersonal ? rules.filter((r) => !gByKey[ruleKey(r)]) : rules;
  const canAdd = availMetrics(null).length > 0;
  const empty = (isPersonal ? (globalRules || []).length === 0 : true) && customRules.length === 0 && !isNewDraft;

  return (
    <div className="field">
      <div className="row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        {label ? <label style={{ margin: 0, whiteSpace: 'nowrap' }}>{label}</label> : <span/>}
        {canAdd && <button className="iconbtn" style={{ width: 26, height: 26, flex: '0 0 26px' }} onClick={startNew} title="Add rule"><Ic.plus size={15}/></button>}
      </div>
      {hint && <div className="hint" style={{ marginTop: 2 }}>{hint}</div>}

      <div className="rules">
        {isPersonal && (globalRules || []).map((g) => globalCard(g))}
        {customRules.map((r) => normalCard(r))}
        {isNewDraft && normalCard(null)}
        {empty && <div className="hint">{isPersonal ? "No rules yet — add one to constrain this person's hours, or set system-wide rules in Settings." : 'No global rules yet — add one to apply it to everyone.'}</div>}
      </div>
    </div>
  );
}

export function WorkingTimeRules({ emp, onChange, globalRules = [] }) {
  return (
    <RulesEditor
      rules={emp.rules || []}
      onChange={(next) => onChange({ rules: next })}
      globalRules={globalRules}
      mode="personal"
      hint='Preferred rules are soft goals; "at least" / "at most" are hard limits. Global rules apply to everyone and can only be tightened.'
    />
  );
}

export function prefWeekHours(emp) {
  const r = (emp.rules || []).find((x) => x.metric === 'weekHours' && x.op === 'preferred');
  return r ? r.value : (emp.contract ?? null);
}
