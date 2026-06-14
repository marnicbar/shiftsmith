// settings.jsx — two pages:
//   AccountView  (account button → Account settings): sign-in, appearance, calendar.
//   SettingsView (gear → System settings): skills, working-time rules, solver.
import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { dateLocale, LANGUAGES } from './i18n/index.js';
import { Ic } from './icons.jsx';
import { RulesEditor } from './rules.jsx';
import * as api from './lib/api.js';

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
  const { t } = useTranslation();
  const [adding, setAdding] = useState('');
  const [editing, setEditing] = useState(null); // { name, value }
  const sorted = [...skills].sort((a, b) => a.localeCompare(b));
  const commitAdd = () => { const v = adding.trim(); if (v) onAdd(v); setAdding(''); };
  const commitEdit = () => { if (editing) onRename(editing.name, editing.value); setEditing(null); };
  return (
    <div className="skills-mgr">
      {sorted.length === 0 && <div className="hint">{t('settings.skills.empty')}</div>}
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
              <button className="iconbtn sm-ic danger" title={t('common.remove')}
                onClick={() => { if (confirm(t('settings.skills.confirmRemove', { name: s }))) onRemove(s); }}><Ic.trash size={14}/></button>
            </div>
          </div>
        ))}
      </div>
      <div className="skill-add">
        <input className="input" placeholder={t('settings.skills.addPlaceholder')} value={adding}
          onChange={(e) => setAdding(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') commitAdd(); }} />
        <button className="btn sm" disabled={!adding.trim()} onClick={commitAdd}><Ic.plus size={14}/> {t('common.add')}</button>
      </div>
    </div>
  );
}

function SigninSection({ username }) {
  const { t } = useTranslation();
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [msg, setMsg] = useState(null); // { ok: boolean, text }
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setMsg(null);
    if (next.length < 6) { setMsg({ ok: false, text: t('auth.tooShort') }); return; }
    if (next !== confirm) { setMsg({ ok: false, text: t('auth.mismatch') }); return; }
    setBusy(true);
    try {
      await api.changePassword(cur, next);
      setMsg({ ok: true, text: t('auth.changed') });
      setCur(''); setNext(''); setConfirm('');
    } catch (err) {
      const wrongCurrent = String(err.message || '').includes('403');
      setMsg({ ok: false, text: wrongCurrent ? t('auth.currentIncorrect') : t('auth.changeFailed') });
    } finally { setBusy(false); }
  };

  return (
    <div className="card set-card">
      <h3>{t('account.signinTitle')}</h3>
      <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>{t('account.signinDesc')}</div>
      <Row label={t('account.signedInAs')}>
        <span className="acct-name">{username || '—'}</span>
      </Row>
      <form className="acct-pw" onSubmit={submit}>
        <div className="field">
          <label htmlFor="pw-cur">{t('account.currentPassword')}</label>
          <input id="pw-cur" className="input" type="password" autoComplete="current-password"
            value={cur} onChange={(e) => setCur(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="pw-new">{t('account.newPassword')}</label>
          <input id="pw-new" className="input" type="password" autoComplete="new-password"
            value={next} onChange={(e) => setNext(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="pw-confirm">{t('account.confirmPassword')}</label>
          <input id="pw-confirm" className="input" type="password" autoComplete="new-password"
            value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </div>
        {msg && <div className={msg.ok ? 'acct-msg ok' : 'acct-msg err'}>{msg.text}</div>}
        <button type="submit" className="btn primary acct-submit" disabled={busy || !cur || !next || !confirm}>
          {t('account.updatePassword')}
        </button>
      </form>
    </div>
  );
}

function AppearanceCard({ prefs, setPref }) {
  const { t } = useTranslation();
  return (
    <div className="card set-card">
      <h3>{t('settings.appearance')}</h3>
      <Row label={t('settings.darkMode')} hint={t('settings.darkModeHint')}>
        <Toggle value={prefs.dark} onChange={(v) => setPref('dark', v)} />
      </Row>
      <Row label={t('settings.nameOrder')} hint={t('settings.nameOrderHint')}>
        <Seg value={prefs.nameOrder} options={[{ value: 'first', label: t('settings.order.first') }, { value: 'last', label: t('settings.order.last') }]} onChange={(v) => setPref('nameOrder', v)} />
      </Row>
      <Row label={t('settings.language')} hint={t('settings.languageHint')}>
        <select className="input set-select" value={prefs.lang} onChange={(e) => setPref('lang', e.target.value)}>
          {LANGUAGES.map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}
        </select>
      </Row>
    </div>
  );
}

function CalendarCard({ prefs, setPref }) {
  const { t } = useTranslation();
  return (
    <div className="card set-card">
      <h3>{t('settings.calendar')}</h3>
      <Row label={t('settings.timeSnap')} hint={t('settings.timeSnapHint')}>
        <Seg value={prefs.snapLabel} options={[{ value: '15 min', label: t('settings.snap.15') }, { value: '30 min', label: t('settings.snap.30') }, { value: '60 min', label: t('settings.snap.60') }]} onChange={(v) => setPref('snapLabel', v)} />
      </Row>
      <Row label={t('settings.defaultView')}>
        <Seg value={prefs.tlDefaultLabel} options={[{ value: 'Day', label: t('settings.view.day') }, { value: 'Week', label: t('settings.view.week') }, { value: 'Continuous', label: t('settings.view.continuous') }]} onChange={(v) => setPref('tlDefaultLabel', v)} />
      </Row>
    </div>
  );
}

function horizonSummary(t, sched, settings) {
  const n = Math.max(1, settings.horizonCount || 1);
  const unit = settings.horizonUnit || 'week';
  const noun = t(`settings.unit_${unit}`, { count: n });
  let range = '';
  if (sched.horizonStart && sched.horizonEnd) {
    const start = SS.parseISO(sched.horizonStart);
    const last = SS.addDays(SS.parseISO(sched.horizonEnd), -1); // end is exclusive
    const days = Math.round((SS.parseISO(sched.horizonEnd) - SS.parseISO(sched.horizonStart)) / SS.DAY);
    const fmt = (d) => d.toLocaleDateString(dateLocale(), { month: 'short', day: 'numeric' });
    range = `${fmt(start)} – ${fmt(last)} · ${t('settings.daysCount', { count: days })}`;
  }
  return { noun, n, range };
}

/** Account settings: sign-in, appearance and calendar — personal to the user. */
export function AccountView({ prefs, setPref, authUser }) {
  const { t } = useTranslation();
  return (
    <div className="settings">
      <div className="settings-inner">
        <div className="dash-head"><div><h1>{t('account.title')}</h1><p>{t('account.subtitle')}</p></div></div>
        <SigninSection username={authUser} />
        <AppearanceCard prefs={prefs} setPref={setPref} />
        <CalendarCard prefs={prefs} setPref={setPref} />
      </div>
    </div>
  );
}

/** System settings: skills, working-time rules and the solver — shared, server-side config. */
export function SettingsView({ settings, setSettings, sched, skills = [], onAddSkill, onRenameSkill, onRemoveSkill, globalRules = [], setGlobalRules }) {
  const { t } = useTranslation();
  const active = sched.solverStatus === 'SOLVING_ACTIVE' || sched.solverStatus === 'SOLVING_SCHEDULED';
  const { noun, n, range } = horizonSummary(t, sched, settings);
  const setSetting = (patch) => setSettings({ ...settings, ...patch });

  return (
    <div className="settings">
      <div className="settings-inner">
        <div className="dash-head"><div><h1>{t('settings.title')}</h1><p>{t('settings.subtitle')}</p></div></div>

        <div className="card set-card">
          <h3>{t('settings.skillsTitle')}</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            {t('settings.skillsDesc')}
          </div>
          <SkillsManager skills={skills} onAdd={onAddSkill} onRename={onRenameSkill} onRemove={onRemoveSkill} />
        </div>

        <div className="card set-card">
          <h3>{t('settings.rulesTitle')}</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            {t('settings.rulesDesc')}
          </div>
          <RulesEditor rules={globalRules} onChange={setGlobalRules} mode="global" label={null} />
        </div>

        <div className="card set-card">
          <h3>{t('settings.solver')}</h3>
          <div className="hint" style={{ marginTop: -2, marginBottom: 12 }}>
            {t('settings.solverDesc')}
          </div>

          <Row label={t('settings.timeRange')} hint={t('settings.timeRangeHint')}>
            <div className="horizon-ctl">
              <input className="input mono horizon-num" type="number" min="1" max="52"
                value={settings.horizonCount}
                onChange={(e) => setSetting({ horizonCount: Math.max(1, Number(e.target.value) || 1) })} />
              <Seg value={settings.horizonUnit}
                options={[{ value: 'day', label: t('settings.unitPlural.day') }, { value: 'week', label: t('settings.unitPlural.week') }, { value: 'month', label: t('settings.unitPlural.month') }]}
                onChange={(v) => setSetting({ horizonUnit: v })} />
            </div>
          </Row>

          <div className="solver-status">
            <div className="ss-line">
              <span className={`solver-badge ${active ? 'on' : ''}`}><span className="dot"></span>{active ? t('solver.solving') : t('solver.steady')}</span>
              <span className="muted">{t('settings.solvingAhead', { count: n, noun })}{range ? ` · ${range}` : ''}</span>
            </div>
            <div className="hint" style={{ marginTop: 8 }}>
              {t('settings.coverageHint')}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
