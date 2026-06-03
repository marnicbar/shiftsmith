// settings.jsx — full-page settings: Appearance, Calendar, Skills, Shift plan and Solver.
import React, { useState } from 'react';
import { Theme } from './theme.js';
import { SS } from './data.js';
import { Ic } from './icons.jsx';
import { RulesEditor } from './rules.jsx';

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

function SkillsManager({ skills, onAdd, onRename, onRemove }) {
  const [adding, setAdding] = useState('');
  const [editing, setEditing] = useState(null); // { name, value }
  const sorted = [...skills].sort((a, b) => a.localeCompare(b));
  const commitAdd = () => { const v = adding.trim(); if (v) onAdd(v); setAdding(''); };
  const commitEdit = () => { if (editing) onRename(editing.name, editing.value); setEditing(null); };
  return (
    <div className="skills-mgr">
      {sorted.length === 0 && <div className="hint">No skills yet — add the first one below.</div>}
      <div className="skills-list">
        {sorted.map((s) => (
          <div key={s} className="skill-row">
            {editing && editing.name === s ? (
              <input className="input skill-edit" autoFocus value={editing.value}
                onChange={(e) => setEditing({ name: s, value: e.target.value })}
                onKeyDown={(e) => { if (e.key === 'Enter') commitEdit(); else if (e.key === 'Escape') setEditing(null); }}
                onBlur={commitEdit} />
            ) : (
              <span className="skill-name" onClick={() => setEditing({ name: s, value: s })}>{s}</span>
            )}
            <div className="skill-actions">
              <button className="iconbtn sm-ic danger" title="Remove"
                onClick={() => { if (confirm(`Remove the skill “${s}”? It will be removed from every person and shift that requires it.`)) onRemove(s); }}><Ic.trash size={14}/></button>
            </div>
          </div>
        ))}
      </div>
      <div className="skill-add">
        <input className="input" placeholder="Add a skill…" value={adding}
          onChange={(e) => setAdding(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') commitAdd(); }} />
        <button className="btn sm" disabled={!adding.trim()} onClick={commitAdd}><Ic.plus size={14}/> Add</button>
      </div>
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

export function SettingsView({ prefs, setPref, fonts, settings, setSettings, sched, skills = [], onAddSkill, onRenameSkill, onRemoveSkill, globalRules = [], setGlobalRules }) {
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
          <h3>Skills</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            The skills people can have and shifts can require. Renaming or removing a skill
            updates everyone and every shift that uses it.
          </div>
          <SkillsManager skills={skills} onAdd={onAddSkill} onRename={onRenameSkill} onRemove={onRemoveSkill} />
        </div>

        <div className="card set-card">
          <h3>Working time rules</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            Global limits that apply to everyone. People inherit these unless they set
            their own rule for the same metric, and a personal rule can only be stricter.
            Tightening a rule here updates anyone whose personal rule was looser.
          </div>
          <RulesEditor rules={globalRules} onChange={setGlobalRules} mode="global" label={null} />
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
