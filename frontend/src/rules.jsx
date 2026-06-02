// rules.jsx — WorkingTimeRules: soft/hard working-time requirements with scheduled future changes.
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

export function WorkingTimeRules({ emp, onChange, globalRules = [] }) {
  const [selId, setSelId] = useStateR(null);
  const [draft, setDraft] = useStateR(null);
  const [menu, setMenu] = useStateR(null);
  const [warn, setWarn] = useStateR(null);
  const rules = emp.rules || [];

  const globalByKey = Object.fromEntries((globalRules || []).map((g) => [ruleKey(g), g]));
  const personalKeys = new Set(rules.map(ruleKey));
  // Global rules the person hasn't overridden show as read-only "System" cards.
  const systemOnly = (globalRules || []).filter((g) => !personalKeys.has(ruleKey(g)));

  function setRules(next) { onChange({ rules: next }); }
  function updateRule(id, patch) { setRules(rules.map((r) => r.id === id ? { ...r, ...patch } : r)); }

  function select(r) { setSelId(r.id); setDraft({ metric: r.metric, op: r.op, value: r.value }); setMenu(null); setWarn(null); }
  function cancel() { setSelId(null); setDraft(null); setMenu(null); setWarn(null); }

  /** Reject duplicate (same metric+op as another personal rule) or too-loose-vs-global edits. */
  function validate(metric, op, value) {
    if (rules.some((r) => r.id !== selId && r.metric === metric && r.op === op)) {
      setWarn(`A “${METRICS[metric].label} · ${OPS[op]}” rule already exists — each rule can be set only once.`);
      return false;
    }
    const g = globalByKey[`${metric}:${op}`];
    if (g) {
      const bound = tooLooseAgainst(op, value, g.value);
      if (bound != null) {
        setWarn(`The system limit is ${OPS[op].toLowerCase()} ${g.value}${METRICS[metric].unit}. A personal rule can only be stricter.`);
        return false;
      }
    }
    return true;
  }

  function applyNow() {
    if (!validate(draft.metric, draft.op, +draft.value)) return;
    updateRule(selId, { metric: draft.metric, op: draft.op, value: +draft.value }); cancel();
  }
  function applyOn(date) {
    if (!validate(draft.metric, draft.op, +draft.value)) return;
    const r = rules.find((x) => x.id === selId);
    const ch = { id: SS.uid('c'), date, kind: 'set', metric: draft.metric, op: draft.op, value: +draft.value };
    updateRule(selId, { changes: [...(r.changes || []).filter((c) => c.date !== date || c.kind !== 'set'), ch].sort((a, b) => a.date.localeCompare(b.date)) });
    cancel();
  }
  function deleteNow() { setRules(rules.filter((r) => r.id !== selId)); cancel(); }
  function deleteOn(date) {
    const r = rules.find((x) => x.id === selId);
    const ch = { id: SS.uid('c'), date, kind: 'remove' };
    updateRule(selId, { changes: [...(r.changes || []).filter((c) => c.kind !== 'remove'), ch].sort((a, b) => a.date.localeCompare(b.date)) });
    cancel();
  }
  function removeChange(ruleId, changeId) {
    const r = rules.find((x) => x.id === ruleId);
    updateRule(ruleId, { changes: (r.changes || []).filter((c) => c.id !== changeId) });
  }
  function addRule() {
    // Pick the first metric+op not already used by a personal or global rule, so
    // each rule is defined at most once. Falls back to any personal-free combo.
    const used = new Set([...personalKeys, ...Object.keys(globalByKey)]);
    let combo = null;
    for (const m of Object.keys(METRICS)) for (const op of Object.keys(OPS)) {
      const k = `${m}:${op}`;
      if (combo == null && !used.has(k)) combo = [m, op];
    }
    if (!combo) for (const m of Object.keys(METRICS)) for (const op of Object.keys(OPS)) {
      if (combo == null && !personalKeys.has(`${m}:${op}`)) combo = [m, op];
    }
    if (!combo) { setWarn('Every rule is already defined.'); return; }
    const r = { id: SS.uid('r'), metric: combo[0], op: combo[1], value: combo[1] === 'preferred' ? 8 : 10, changes: [] };
    setRules([...rules, r]); select(r);
  }
  /** Turn a read-only system card into a personal (stricter) rule the user can edit. */
  function customize(g) {
    const r = { id: SS.uid('r'), metric: g.metric, op: g.op, value: g.value, changes: [] };
    setRules([...rules, r]); select(r);
  }

  return (
    <div className="field">
      <div className="row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <label style={{ margin: 0, whiteSpace: 'nowrap' }}>Working time rules</label>
        <button className="iconbtn" style={{ width: 26, height: 26, flex: '0 0 26px' }} onClick={addRule} title="Add rule"><Ic.plus size={15}/></button>
      </div>
      <div className="hint" style={{ marginTop: 2 }}>Preferred rules are soft goals; "at least" / "at most" are hard limits. System rules apply to everyone and can only be tightened.</div>
      {warn && <div className="rule-warn">{warn}</div>}

      <div className="rules">
        {rules.map((r) => {
          const sel = selId === r.id;
          const d = sel ? draft : r;
          const m = METRICS[d.metric];
          const hard = isHard(d.op);
          const changes = (r.changes || []);
          const overG = globalByKey[ruleKey(d)];
          return (
            <div key={r.id} className={`rule ${hard ? 'hard' : 'soft'} ${sel ? 'sel' : ''}`} onClick={() => { if (!sel) select(r); }}>
              <div className="rule-top">
                <span className="rule-ic">{React.createElement(Ic[m.icon] || Ic.clock, { size: 14 })}</span>
                {sel
                  ? <select className="bare-select rule-name" value={draft.metric} onClick={(e) => e.stopPropagation()} onChange={(e) => setDraft({ ...draft, metric: e.target.value })}>
                      {Object.entries(METRICS).map(([k, mm]) => <option key={k} value={k}>{mm.label}</option>)}
                    </select>
                  : <span className="rule-name">{m.label}</span>}
                {overG && <span className="str-tag system" title={`Tightens the system rule (${OPS[overG.op].toLowerCase()} ${overG.value}${m.unit})`}>System</span>}
                <span className={`str-tag ${hard ? 'hard' : 'soft'}`}>{hard ? 'Hard' : 'Soft'}</span>
              </div>

              <div className="rule-bot">
                {sel
                  ? <select className="bare-select op" value={draft.op} onClick={(e) => e.stopPropagation()} onChange={(e) => setDraft({ ...draft, op: e.target.value })}>
                      {Object.entries(OPS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                    </select>
                  : <span className="op-static">{OPS[d.op]}</span>}
                <div className="num">
                  {sel
                    ? <input className="input mono" type="number" min="0" step="1" value={draft.value}
                        onClick={(e) => e.stopPropagation()} onChange={(e) => setDraft({ ...draft, value: e.target.value })} autoFocus/>
                    : <span className="num-static mono">{d.value}</span>}
                  <span className="unit">{m.unit}</span>
                </div>
              </div>

              {changes.length > 0 && (
                <div className="rule-changes">
                  {changes.map((c) => (
                    <div key={c.id} className="change">
                      <Ic.clock size={11}/>
                      <span className="ch-txt">
                        {c.kind === 'remove'
                          ? <>Remove rule</>
                          : <>→ {OPS[c.op]} {c.value}{METRICS[c.metric].unit}</>}
                        <span className="ch-date"> · from {dLabel(c.date)}</span>
                      </span>
                      <button className="rule-x" onClick={(e) => { e.stopPropagation(); removeChange(r.id, c.id); }} title="Cancel scheduled change"><Ic.trash size={12}/></button>
                    </div>
                  ))}
                </div>
              )}

              {sel && (
                <div className="rule-actions" onClick={(e) => e.stopPropagation()}>
                  <button className="btn ghost sm" onClick={cancel}>Cancel</button>
                  <div className="ra-right">
                    <div className="splitbtn danger">
                      <button className="sb-main" onClick={deleteNow}><Ic.trash size={13}/></button>
                      <button className="sb-caret" onClick={() => setMenu(menu === 'delete' ? null : 'delete')}><Ic.chevD size={12}/></button>
                      {menu === 'delete' && <CalPicker kind="delete" period={periodOf(draft.metric)} onPick={deleteOn} onClose={() => setMenu(null)} />}
                    </div>
                    <div className="splitbtn primary">
                      <button className="sb-main" onClick={applyNow}>Apply</button>
                      <button className="sb-caret" onClick={() => setMenu(menu === 'apply' ? null : 'apply')}><Ic.chevD size={12}/></button>
                      {menu === 'apply' && <CalPicker kind="apply" period={periodOf(draft.metric)} onPick={applyOn} onClose={() => setMenu(null)} />}
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}

        {systemOnly.map((g) => {
          const m = METRICS[g.metric];
          const hard = isHard(g.op);
          return (
            <div key={`sys-${ruleKey(g)}`} className={`rule system ${hard ? 'hard' : 'soft'}`}>
              <div className="rule-top">
                <span className="rule-ic">{React.createElement(Ic[m.icon] || Ic.clock, { size: 14 })}</span>
                <span className="rule-name">{m.label}</span>
                <span className="str-tag system" title="Set in Settings · applies to everyone">System</span>
                <span className={`str-tag ${hard ? 'hard' : 'soft'}`}>{hard ? 'Hard' : 'Soft'}</span>
              </div>
              <div className="rule-bot">
                <span className="op-static">{OPS[g.op]}</span>
                <div className="num"><span className="num-static mono">{g.value}</span><span className="unit">{m.unit}</span></div>
              </div>
              <div className="rule-actions" onClick={(e) => e.stopPropagation()}>
                <span className="hint" style={{ margin: 0 }}>Applies to everyone</span>
                <div className="ra-right">
                  <button className="btn ghost sm" onClick={() => customize(g)}>{g.op === 'preferred' ? 'Customize' : 'Make stricter'}</button>
                </div>
              </div>
            </div>
          );
        })}

        {!rules.length && !systemOnly.length && <div className="hint">No rules yet — add one to constrain this person's hours, or set system-wide rules in Settings.</div>}
      </div>
    </div>
  );
}

export function prefWeekHours(emp) {
  const r = (emp.rules || []).find((x) => x.metric === 'weekHours' && x.op === 'preferred');
  return r ? r.value : (emp.contract ?? null);
}
