// dashboard.jsx — general overview (placeholder data per spec).
import React from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { dateLocale } from './i18n/index.js';
import { Ic } from './icons.jsx';

export function Dashboard({ employees, positions, sched = {}, onGo }) {
  const { t } = useTranslation();
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
    { ic: 'alert', cls: 'undes', val: unassigned, lbl: t('dashboard.kpi.unassigned'), delta: t('dashboard.kpi.thisWeek'), deltaCls: 'muted' },
    { ic: 'check', cls: 'pref', val: coverage + '%', lbl: t('dashboard.kpi.coverage'), delta: t('dashboard.kpi.vsLastWeek'), deltaCls: 'tone-pref' },
    { ic: 'users', cls: 'shift', val: employees.length, lbl: t('dashboard.kpi.activePeople'), delta: t('dashboard.kpi.positionsCount', { count: positions.length }), deltaCls: 'muted' },
    { ic: 'ban', cls: 'vac', val: 3, lbl: t('dashboard.kpi.onVacation'), delta: t('dashboard.kpi.hardConstraint'), deltaCls: 'muted' },
  ];

  const attnCls = ['undes', 'undes', 'vac', 'undes'];
  const attention = t('dashboard.attention', { returnObjects: true }).map((r, i) => ({ ...r, cls: attnCls[i] }));

  return (
    <div className="dash">
      <div className="dash-head">
        <div>
          <h1>{t('dashboard.title')}</h1>
          <p>{t('dashboard.weekOf', { date: SS.startOfWeek(new Date()).toLocaleDateString(dateLocale(), { month: 'long', day: 'numeric' }) })}</p>
        </div>
        <button className="btn primary" onClick={() => onGo('shiftplan')}><Ic.sparkles size={15}/> {t('dashboard.solveSchedule')}</button>
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
          <h3>{t('dashboard.coverageByPosition')}</h3>
          <div className="ph" style={{ height: 240 }}>{t('dashboard.chartPlaceholder')}</div>
        </div>
        <div className="card">
          <h3>{t('dashboard.needsAttention')}</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {attention.map((r, i) => (
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
