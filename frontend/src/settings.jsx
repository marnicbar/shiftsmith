// settings.jsx — full-page settings: Appearance, Calendar, Shift plan and Solver.
import React from 'react';
import { Theme } from './theme.js';
import { SS } from './data.js';

function Seg({ value, options, onChange }) {
  return (
    <div className="seg set-seg">
      {options.map((o) => {
        const v = typeof o === 'object' ? o.value : o;
        const l = typeof o === 'object' ? o.label : o;
        return <button key={v} className={value === v ? 'on' : ''} onClick={() => onChange(v)}>{l}</button>;
      })}
    </div>
  );
}

function Toggle({ value, onChange }) {
  return (
    <button type="button" className="set-toggle" data-on={value ? '1' : '0'}
      role="switch" aria-checked={!!value} onClick={() => onChange(!value)}><i/></button>
  );
}

function Row({ label, hint, children }) {
  return (
    <div className="set-row">
      <div className="set-row-l">
        <div className="set-row-label">{label}</div>
        {hint && <div className="hint">{hint}</div>}
      </div>
      <div className="set-row-c">{children}</div>
    </div>
  );
}

const UNIT_LABEL = { day: 'day', week: 'week', month: 'month' };

function horizonSummary(sched, settings) {
  const n = Math.max(1, settings.horizonCount || 1);
  const unit = settings.horizonUnit || 'week';
  const noun = UNIT_LABEL[unit] + (n === 1 ? '' : 's');
  let range = '';
  if (sched.horizonStart && sched.horizonEnd) {
    const start = SS.parseISO(sched.horizonStart);
    const last = SS.addDays(SS.parseISO(sched.horizonEnd), -1); // end is exclusive
    const days = Math.round((SS.parseISO(sched.horizonEnd) - SS.parseISO(sched.horizonStart)) / SS.DAY);
    const fmt = (d) => d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    range = `${fmt(start)} – ${fmt(last)} · ${days} days`;
  }
  return { noun, n, range };
}

export function SettingsView({ prefs, setPref, fonts, settings, setSettings, sched }) {
  const accents = Object.entries(Theme.ACCENTS);
  const active = sched.solverStatus === 'SOLVING_ACTIVE' || sched.solverStatus === 'SOLVING_SCHEDULED';
  const { noun, n, range } = horizonSummary(sched, settings);
  const setSetting = (patch) => setSettings({ ...settings, ...patch });

  return (
    <div className="settings">
      <div className="settings-inner">
        <div className="dash-head"><div><h1>Settings</h1><p>Appearance, calendar behaviour and the solver window.</p></div></div>

        <div className="card set-card">
          <h3>Appearance</h3>
          <Row label="Dark mode" hint="Switch between light and dark themes.">
            <Toggle value={prefs.dark} onChange={(v) => setPref('dark', v)} />
          </Row>
          <Row label="Palette" hint="Neutral tone of the interface.">
            <Seg value={prefs.palette} options={['slate', 'stone', 'mono']} onChange={(v) => setPref('palette', v)} />
          </Row>
          <Row label="Accent" hint="Highlight colour for active elements.">
            <div className="accent-row">
              {accents.map(([key, a]) => (
                <button key={key} title={a.label} onClick={() => setPref('accent', key)}
                  className={`accent-swatch ${prefs.accent === key ? 'on' : ''}`}
                  style={{ background: `oklch(0.6 ${a.c} ${a.hue})` }} />
              ))}
            </div>
          </Row>
          <Row label="UI font">
            <select className="input set-select" value={prefs.font} onChange={(e) => setPref('font', e.target.value)}>
              {Object.keys(fonts).map((f) => <option key={f} value={f}>{f}</option>)}
            </select>
          </Row>
        </div>

        <div className="card set-card">
          <h3>Calendar</h3>
          <Row label="Time snap" hint="Granularity when drawing or dragging blocks.">
            <Seg value={prefs.snapLabel} options={['15 min', '30 min', '60 min']} onChange={(v) => setPref('snapLabel', v)} />
          </Row>
          <Row label="New block" hint="What happens when you create a calendar block.">
            <Seg value={prefs.newFlowLabel} options={['Paint, then tweak', 'Open a form']} onChange={(v) => setPref('newFlowLabel', v)} />
          </Row>
          <Row label="Shift plan default view">
            <Seg value={prefs.tlDefaultLabel} options={['Day', 'Week', 'Continuous']} onChange={(v) => setPref('tlDefaultLabel', v)} />
          </Row>
        </div>

        <div className="card set-card">
          <h3>Solver</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            The solver runs continuously over this window and pauses automatically once the
            schedule is steady. Changing any setting re-solves from the new state.
          </div>

          <Row label="Time range" hint="Counted from the beginning of the next full day, week or month. One week means this week and the next.">
            <div className="horizon-ctl">
              <input className="input mono horizon-num" type="number" min="1" max="52"
                value={settings.horizonCount}
                onChange={(e) => setSetting({ horizonCount: Math.max(1, Number(e.target.value) || 1) })} />
              <Seg value={settings.horizonUnit}
                options={[{ value: 'day', label: 'Days' }, { value: 'week', label: 'Weeks' }, { value: 'month', label: 'Months' }]}
                onChange={(v) => setSetting({ horizonUnit: v })} />
            </div>
          </Row>

          <div className="solver-status">
            <div className="ss-line">
              <span className={`solver-badge ${active ? 'on' : ''}`}><span className="dot"></span>{active ? 'Solving…' : 'Steady'}</span>
              <span className="muted">Solving {n} {noun} ahead{range ? ` · ${range}` : ''}</span>
            </div>
            <div className="hint" style={{ marginTop: 8 }}>
              Live coverage and the Solve / Pause controls are on the Shift Plan view.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
