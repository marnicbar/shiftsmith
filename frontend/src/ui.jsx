// ui.jsx — small shared components.
import { useState as useStateUI } from 'react';
import { useTranslation } from 'react-i18next';
import { Theme } from './theme.js';
import { SS } from './data.js';
import { Ic } from './icons.jsx';

function Avatar({ emp, size, square }) {
  return <div className={`avatar ${size === 'lg' ? 'lg' : ''} ${square ? 'sq' : ''}`}
    style={{ background: Theme.avatarColor(SS.nameSeed(emp)) }}>{SS.empInitials(emp)}</div>;
}

function SkillEditor({ value, all, onChange, accent }) {
  const { t } = useTranslation();
  const [adding, setAdding] = useStateUI(false);
  const avail = all.filter((s) => !value.includes(s));
  return (
    <div className="chips-wrap">
      {value.map((s) => (
        <span key={s} className={`chip ${accent ? 'accent' : ''}`}>{s}
          <span className="x" onClick={() => onChange(value.filter((x) => x !== s))}><Ic.x/></span>
        </span>
      ))}
      {!adding && <span className="chip" style={{ cursor: 'pointer' }} onClick={() => setAdding(true)}><Ic.plus size={13}/> {t('common.add')}</span>}
      {adding && (
        <select className="input" autoFocus style={{ width: 'auto', height: 26, padding: '0 6px' }}
          onChange={(e) => { if (e.target.value) onChange([...value, e.target.value]); setAdding(false); }}
          onBlur={() => setAdding(false)}>
          <option value="">{t('ui.selectSkill')}</option>
          {avail.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      )}
    </div>
  );
}

function Stepper({ value, min = 1, max = 20, onChange }) {
  const { t } = useTranslation();
  return (
    <div className="seg stepper" style={{ padding: 2, alignSelf: 'flex-start' }}>
      <button onClick={() => onChange(Math.max(min, value - 1))} disabled={value <= min} aria-label={t('common.decrease')}><Ic.minus size={12}/></button>
      <span className="mono" style={{ minWidth: 28, textAlign: 'center', fontWeight: 600, alignSelf: 'center' }}>{value}</span>
      <button onClick={() => onChange(Math.min(max, value + 1))} disabled={value >= max} aria-label={t('common.increase')}><Ic.plus size={12}/></button>
    </div>
  );
}

export const UI = { Avatar, SkillEditor, Stepper };
