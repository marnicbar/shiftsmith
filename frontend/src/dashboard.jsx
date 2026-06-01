// dashboard.jsx — general overview (placeholder data per spec).
import React from 'react';
import { SS } from './data.js';
import { Ic } from './icons.jsx';

export function Dashboard({ employees, positions, sched = {}, onGo }) {
  // Real coverage from the solver over the configured horizon; fall back to a
  // rough per-week estimate before the first solve completes.
  let shiftSlots = sched.total || 0;
  if (!shiftSlots) {
    positions.forEach((p) => p.shifts.forEach((s) => {
      const occ = s.repeat === 'daily' ? 7 : 1;
      shiftSlots += s.headcount * occ;
    }));
  }
  const assigned = sched.total ? sched.staffed : Math.round(shiftSlots * 0.78);
  const unassigned = sched.total ? sched.unassigned : shiftSlots - assigned;
  const coverage = shiftSlots ? Math.round((assigned / shiftSlots) * 100) : 0;

  const kpis = [
    { ic: 'alert', cls: 'undes', val: unassigned, lbl: 'Unassigned shifts', delta: 'this week', deltaCls: 'muted' },
    { ic: 'check', cls: 'pref', val: coverage + '%', lbl: 'Coverage', delta: '+4% vs last week', deltaCls: 'tone-pref' },
    { ic: 'users', cls: 'shift', val: employees.length, lbl: 'Active people', delta: positions.length + ' positions', deltaCls: 'muted' },
    { ic: 'ban', cls: 'vac', val: 3, lbl: 'On vacation', delta: 'hard constraint', deltaCls: 'muted' },
  ];

  return (
    <div className="dash">
      <div className="dash-head">
        <div>
          <h1>Dashboard</h1>
          <p>Week of {SS.startOfWeek(new Date()).toLocaleDateString([], { month: 'long', day: 'numeric' })} · live overview</p>
        </div>
        <button className="btn primary" onClick={() => onGo('shiftplan')}><Ic.sparkles size={15}/> Solve schedule</button>
      </div>

      <div className="kpi-grid">
        {kpis.map((k, i) => (
          <div key={i} className={`card kpi tone-${k.cls}`}>
            <div className="kpi-top">
              <div className="kpi-ic">{React.createElement(Ic[k.ic])}</div>
              <span className={`kpi-delta ${k.deltaCls}`} style={k.deltaCls.startsWith('tone') ? { color: 'var(--tone-strong)' } : {}}>{k.delta}</span>
            </div>
            <div className="kpi-val">{k.val}</div>
            <div className="kpi-lbl">{k.lbl}</div>
          </div>
        ))}
      </div>

      <div className="dash-cols">
        <div className="card">
          <h3>Coverage by position</h3>
          <div className="ph" style={{ height: 240 }}>chart placeholder — coverage per position over the week</div>
        </div>
        <div className="card">
          <h3>Needs attention</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {[
              { t: 'Bar · Fri evening', s: '2 slots unfilled', cls: 'undes' },
              { t: 'Kitchen Line · Sat', s: '1 slot unfilled', cls: 'undes' },
              { t: 'Mei Tanaka', s: 'on vacation Sat–Sun', cls: 'vac' },
              { t: 'Night Supervisor', s: 'no eligible staff free', cls: 'undes' },
            ].map((r, i) => (
              <div key={i} className={`tone-${r.cls}`} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 11px', borderRadius: 10, background: 'var(--tone-soft)' }}>
                <span style={{ width: 8, height: 8, borderRadius: 99, background: 'var(--tone)', flex: '0 0 8px' }}></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--tone-strong)' }}>{r.t}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-3)' }}>{r.s}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
