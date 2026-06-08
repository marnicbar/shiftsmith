// personnel.jsx — Personnel viewer/editor: people rail + availability calendar + config pane.
import { useState as useStateP } from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { Ic } from './icons.jsx';
import { UI } from './ui.jsx';
import { Calendar } from './calendar.jsx';
import { WorkingTimeRules } from './rules.jsx';

const AVAIL_PALETTE = [
  { type: 'pref',  cls: 'pref',  labelKey: 'avail.pref' },
  { type: 'undes', cls: 'undes', labelKey: 'avail.undes' },
  { type: 'vac',   cls: 'vac',   labelKey: 'avail.vac' },
];

export function Personnel({ employees, setEmployees, skills, settings, selId, setSelId, snap, newFlow, nameOrder = 'first' }) {
  const { t } = useTranslation();
  const palette = AVAIL_PALETTE.map((p) => ({ ...p, label: t(p.labelKey) }));
  const [q, setQ] = useStateP('');
  const [sortKey, setSortKey] = useStateP(nameOrder);
  const [view, setView] = useStateP('week');
  const [anchor, setAnchor] = useStateP(new Date());
  const [zoom, setZoom] = useStateP(46);
  const [paint, setPaint] = useStateP('pref');

  const emp = employees.find((e) => e.id === selId) || employees[0];
  const ql = q.toLowerCase();
  const list = employees
    .filter((e) => SS.fullName(e).toLowerCase().includes(ql) || e.skills.some((s) => s.toLowerCase().includes(ql)))
    .sort((a, b) => SS.compareNames(a, b, sortKey));

  function updateEmp(patch) { setEmployees(employees.map((e) => e.id === emp.id ? { ...e, ...patch } : e)); }
  function commitBlock(b) {
    const exists = emp.blocks.some((x) => x.id === b.id);
    updateEmp({ blocks: exists ? emp.blocks.map((x) => x.id === b.id ? b : x) : [...emp.blocks, b] });
  }
  function deleteBlock(id) { updateEmp({ blocks: emp.blocks.filter((x) => x.id !== id) }); }
  function splitBlock(updated, added) { updateEmp({ blocks: emp.blocks.map((x) => x.id === updated.id ? updated : x).concat(added) }); }
  const newItem = ({ date, start, end }) => ({ id: SS.uid('b'), type: paint, date, start, end, allDay: paint === 'vac', repeat: 'none' });

  const addEmployee = () => {
    const e = { id: SS.uid('e'), firstName: t('personnel.newPerson'), lastName: '', skills: [], blocks: [], rules: [] };
    setEmployees([...employees, e]); setSelId(e.id);
  };

  return (
    <div className="view">
      {/* People rail */}
      <div className="rail">
        <div className="rail-head">
          <div className="row">
            <span className="section-title">{t('personnel.people')} <span className="muted">· {employees.length}</span></span>
            <button className="iconbtn" style={{ width: 28, height: 28 }} onClick={addEmployee} title={t('personnel.addPerson')}><Ic.plus size={16}/></button>
          </div>
          <div className="search"><Ic.search/><input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('personnel.searchPlaceholder')}/></div>
          <div className="row" style={{ marginTop: 8 }}>
            <span className="muted" style={{ fontSize: 12 }}>{t('personnel.sortBy')}</span>
            <div className="seg">
              <button className={sortKey === 'first' ? 'on' : ''} onClick={() => setSortKey('first')}>{t('personnel.firstName')}</button>
              <button className={sortKey === 'last' ? 'on' : ''} onClick={() => setSortKey('last')}>{t('personnel.lastName')}</button>
            </div>
          </div>
        </div>
        <div className="rail-list">
          {list.map((e) => (
            <div key={e.id} className={`rail-item ${e.id === emp.id ? 'sel' : ''}`} onClick={() => setSelId(e.id)}>
              <UI.Avatar emp={e}/>
              <div className="ri-meta">
                <div className="ri-name">{SS.fullName(e, nameOrder)}</div>
                {e.skills.length > 0 && <div className="ri-sub">{e.skills.join(' · ')}</div>}
              </div>
            </div>
          ))}
          {!list.length && <div className="muted" style={{ padding: 14, fontSize: 13 }}>{t('personnel.noMatches')}</div>}
        </div>
      </div>

      {!emp ? (
        <div className="empty-state">
          <div className="inner">
            <Ic.users/>
            <div style={{ fontSize: 15, fontWeight: 600 }}>{t('personnel.emptyTitle')}</div>
            <div className="muted">{t('personnel.emptyBody')}</div>
            <button className="btn" onClick={addEmployee}><Ic.plus size={15}/> {t('personnel.addPerson')}</button>
          </div>
        </div>
      ) : (
      <>
      {/* Calendar */}
      <Calendar kind="availability" view={view} onView={setView} anchor={anchor} onAnchor={setAnchor}
        zoom={zoom} onZoom={setZoom} paint={paint} onPaint={setPaint} palette={palette}
        snap={snap} newFlow={newFlow}
        items={emp.blocks} newItem={newItem} onCommit={commitBlock} onDelete={deleteBlock} onSplit={splitBlock} />

      {/* Config pane */}
      <div className="config">
        <div className="pad">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <UI.Avatar emp={emp} size="lg" square/>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.02em' }}>{SS.fullName(emp, nameOrder)}</div>
              {emp.skills.length > 0 && <div className="muted" style={{ fontSize: 12.5 }}>{emp.skills.join(' · ')}</div>}
            </div>
          </div>

          <div className="row" style={{ gap: 10, alignItems: 'flex-end' }}>
            <div className="field" style={{ flex: 1 }}>
              <label>{t('personnel.firstName')}</label>
              <input className="input" value={emp.firstName || ''} onChange={(e) => updateEmp({ firstName: e.target.value })}/>
            </div>
            <div className="field" style={{ flex: 1 }}>
              <label>{t('personnel.lastName')}</label>
              <input className="input" value={emp.lastName || ''} onChange={(e) => updateEmp({ lastName: e.target.value })}/>
            </div>
          </div>
          <div className="field">
            <label title={t('personnel.skillsHint')}>{t('common.skills')}</label>
            <UI.SkillEditor value={emp.skills} all={skills} accent onChange={(s) => updateEmp({ skills: s })}/>
          </div>

          <div className="divider"></div>

          <WorkingTimeRules emp={emp} onChange={updateEmp} globalRules={settings?.globalRules || []} />

          <div className="divider"></div>
          <button className="btn danger" style={{ justifyContent: 'center' }}
            onClick={() => { if (confirm(t('personnel.confirmRemove', { name: SS.fullName(emp, nameOrder) }))) { const rest = employees.filter((x) => x.id !== emp.id); setEmployees(rest); setSelId(rest[0]?.id); } }}>
            <Ic.trash size={15}/> {t('personnel.removePerson')}
          </button>
        </div>
      </div>
      </>
      )}
    </div>
  );
}
