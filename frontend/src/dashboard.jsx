// dashboard.jsx — live overview of the real shift plan for a selectable week or
// month. Metrics (shifts, unassigned, coverage, people on vacation) and the
// "needs attention" list are all derived from the actual problem + the solver's
// assignment map, for the period the user is looking at.
import React, { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { matchesDay } from './shiftplan.jsx';
import { dateLocale } from './i18n/index.js';
import { Ic } from './icons.jsx';

// The list of ISO days covered by the selected period, anchored on `anchor`.
function periodDays(period, anchor) {
  if (period === 'month') {
    const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const n = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0).getDate();
    return Array.from({ length: n }, (_, i) => SS.isoOf(SS.addDays(first, i)));
  }
  const s = SS.startOfWeek(anchor);
  return Array.from({ length: 7 }, (_, i) => SS.isoOf(SS.addDays(s, i)));
}

export function Dashboard({ employees, positions, assign = {}, onOpenShift }) {
  const { t } = useTranslation();
  const [period, setPeriod] = useState('week');
  const [anchor, setAnchor] = useState(new Date());

  const days = useMemo(() => periodDays(period, anchor), [period, anchor]);

  // Walk every shift occurrence in the period once, tallying coverage globally and
  // per position, and collecting the under-staffed occurrences for "needs attention".
  const stats = useMemo(() => {
    let shifts = 0, slotTotal = 0, slotFilled = 0, unassignedShifts = 0, openSlots = 0;
    const attention = [];
    const byPosition = [];
    positions.forEach((p) => {
      let pt = 0, pf = 0;
      p.shifts.forEach((sh) => days.forEach((d) => {
        if (!matchesDay(sh, d)) return;
        shifts++;
        const crew = assign[`${sh.id}@${d}`] || [];
        const filled = Math.min(crew.length, sh.headcount);
        slotTotal += sh.headcount; slotFilled += filled;
        pt += sh.headcount; pf += filled;
        if (filled < sh.headcount) {
          unassignedShifts++; openSlots += sh.headcount - filled;
          attention.push({ shiftId: sh.id, pos: p, sh, date: d, open: sh.headcount - filled });
        }
      }));
      if (pt > 0) byPosition.push({ p, total: pt, filled: pf, pct: Math.round((pf / pt) * 100) });
    });
    attention.sort((a, b) => a.date.localeCompare(b.date) || a.sh.start - b.sh.start);
    const onVac = employees.filter((e) => (e.blocks || []).some((b) => b.type === 'vac' && days.some((d) => matchesDay(b, d))));
    const coverage = slotTotal ? Math.round((slotFilled / slotTotal) * 100) : 0;
    return { shifts, slotTotal, slotFilled, unassignedShifts, openSlots, coverage, attention, onVac, byPosition };
  }, [positions, employees, assign, days]);

  function step(dir) {
    if (period === 'month') setAnchor(new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1));
    else setAnchor(SS.addDays(anchor, dir * 7));
  }

  const periodLabel = period === 'month'
    ? anchor.toLocaleDateString(dateLocale(), { month: 'long', year: 'numeric' })
    : `${SS.parseISO(days[0]).toLocaleDateString(dateLocale(), { month: 'short', day: 'numeric' })} – ${SS.parseISO(days[days.length - 1]).toLocaleDateString(dateLocale(), { month: 'short', day: 'numeric', year: 'numeric' })}`;

  const kpis = [
    { ic: 'calendar', cls: 'shift', val: stats.shifts, lbl: t('dashboard.kpi.shifts'),
      delta: t('dashboard.kpi.slots', { count: stats.slotTotal }) },
    { ic: 'alert', cls: 'undes', val: stats.unassignedShifts, lbl: t('dashboard.kpi.unassigned'),
      delta: t('dashboard.kpi.slotsOpen', { count: stats.openSlots }) },
    { ic: 'check', cls: 'pref', val: stats.coverage + '%', lbl: t('dashboard.kpi.coverage'),
      delta: t('dashboard.kpi.slotsFilled', { filled: stats.slotFilled, total: stats.slotTotal }) },
    { ic: 'palm', cls: 'vac', val: stats.onVac.length, lbl: t('dashboard.kpi.onVacation'),
      delta: t('dashboard.kpi.ofPeople', { count: employees.length }) },
  ];

  return (
    <div className="dash">
      <div className="dash-head">
        <div>
          <h1>{t('dashboard.title')}</h1>
          <p>{periodLabel} · {t('dashboard.overview')}</p>
        </div>
        <div className="nav">
          <button className="iconbtn" title={t('common.previous')} onClick={() => step(-1)}><Ic.chevL/></button>
          <button className="btn sm" onClick={() => setAnchor(new Date())}>{t('common.today')}</button>
          <button className="iconbtn" title={t('common.next')} onClick={() => step(1)}><Ic.chevR/></button>
          <div className="seg" style={{ marginLeft: 6 }}>
            {[['week', t('dashboard.period.week')], ['month', t('dashboard.period.month')]].map(([v, l]) => (
              <button key={v} className={period === v ? 'on' : ''} onClick={() => setPeriod(v)}>{l}</button>
            ))}
          </div>
        </div>
      </div>

      <div className="kpi-grid">
        {kpis.map((k, i) => (
          <div key={i} className={`card kpi tone-${k.cls}`}>
            <div className="kpi-top">
              <div className="kpi-ic">{React.createElement(Ic[k.ic])}</div>
              <span className="kpi-delta muted">{k.delta}</span>
            </div>
            <div className="kpi-val">{k.val}</div>
            <div className="kpi-lbl">{k.lbl}</div>
          </div>
        ))}
      </div>

      <div className="dash-cols">
        <div className="card">
          <h3>{t('dashboard.coverageByPosition')}</h3>
          {stats.byPosition.length === 0 ? (
            <div className="ph" style={{ height: 240 }}>{t('dashboard.noShifts')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, paddingTop: 4 }}>
              {stats.byPosition.map(({ p, filled, total, pct }) => (
                <div key={p.id}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginBottom: 5 }}>
                    <span style={{ fontWeight: 600, color: 'var(--text-2)', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.name}</span>
                    <span className="mono" style={{ color: 'var(--text-3)', flex: '0 0 auto', marginLeft: 8 }}>{filled}/{total} · {pct}%</span>
                  </div>
                  <div style={{ height: 8, borderRadius: 99, background: 'var(--surface-2)', overflow: 'hidden' }}>
                    <div style={{ width: pct + '%', height: '100%', borderRadius: 99, background: `oklch(0.62 0.13 ${p.color})` }}></div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="card">
          <h3>{t('dashboard.unassignedTitle')}</h3>
          {stats.attention.length === 0 ? (
            <div className="ph" style={{ height: 120 }}>{t('dashboard.allStaffed')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 360, overflowY: 'auto' }}>
              {stats.attention.map((r) => {
                const dlabel = SS.parseISO(r.date).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric' });
                return (
                  <button key={`${r.shiftId}@${r.date}`} type="button" className="tone-undes attn-row"
                    onClick={() => onOpenShift?.(r.shiftId, r.date)}
                    style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 11px', borderRadius: 10, background: 'var(--tone-soft)', border: 'none', textAlign: 'left', cursor: 'pointer', width: '100%' }}>
                    <span style={{ width: 8, height: 8, borderRadius: 99, background: 'var(--tone)', flex: '0 0 8px' }}></span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--tone-strong)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.pos.name} · {r.sh.name}</div>
                      <div style={{ fontSize: 12, color: 'var(--text-3)' }}>{dlabel} · {SS.minLabel(r.sh.start)}–{SS.minLabel(r.sh.end)} · {t('dashboard.kpi.slotsOpen', { count: r.open })}</div>
                    </div>
                    <Ic.chevR size={15} style={{ color: 'var(--text-3)', flex: '0 0 auto' }}/>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
