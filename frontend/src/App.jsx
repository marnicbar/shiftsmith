// App.jsx — shell: top tab bar, theme toggle, view routing, Tweaks.
import { useState as useStateApp, useEffect as useEffectApp, useMemo } from 'react';
import React from 'react';
import { Theme } from './theme.js';
import { Ic } from './icons.jsx';
import { SS } from './data.js';
import { UI } from './ui.jsx';
import {
  useTweaks, TweaksPanel, TweakSection, TweakRow,
  TweakToggle, TweakRadio, TweakSelect,
} from './tweaks-panel.jsx';
import { Personnel } from './personnel.jsx';
import { Positions } from './positions.jsx';
import { ShiftPlan, buildPlan, matchesDay } from './shiftplan.jsx';
import { Dashboard } from './dashboard.jsx';

const TABS = [
  { id: 'dashboard', label: 'Dashboard', icon: 'grid' },
  { id: 'personnel', label: 'Personnel', icon: 'users' },
  { id: 'positions', label: 'Positions', icon: 'briefcase' },
  { id: 'shiftplan', label: 'Shift Plan', icon: 'timeline' },
];

const FONTS = {
  'Geist':          "'Geist', system-ui, sans-serif",
  'Helvetica Neue': "'Helvetica Neue', Helvetica, Arial, sans-serif",
  'Figtree':        "'Figtree', system-ui, sans-serif",
};

const TWEAK_DEFAULTS = {
  "dark": false,
  "palette": "slate",
  "accent": "indigo",
  "font": "Geist",
  "snapLabel": "15 min",
  "newFlowLabel": "Paint, then tweak",
  "tlDefaultLabel": "Week"
};

// Apply theme immediately on module load to avoid flash of unstyled content
Theme.applyTheme({ palette: TWEAK_DEFAULTS.palette, accent: TWEAK_DEFAULTS.accent, dark: TWEAK_DEFAULTS.dark });
document.documentElement.style.setProperty('--ui-font', FONTS[TWEAK_DEFAULTS.font] || FONTS.Geist);

const SNAP_MAP = { '15 min': 15, '30 min': 30, '60 min': 60 };
const FLOW_MAP = { 'Paint, then tweak': 'quick', 'Open a form': 'menu' };
const TL_MAP = { 'Day': 'day', 'Week': 'week', 'Continuous': 'free' };

export default function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [tab, setTab] = useStateApp('personnel');
  const init = useMemo(() => SS.seed(), []);
  const [employees, setEmployees] = useStateApp(init.employees);
  const [positions, setPositions] = useStateApp(init.positions);
  const [selEmp, setSelEmp] = useStateApp(init.employees[0].id);
  const [selPos, setSelPos] = useStateApp(init.positions[0].id);
  const [groupOrder, setGroupOrder] = useStateApp(() => {
    const g = []; init.positions.forEach((p) => { if (p.group && !g.includes(p.group)) g.push(p.group); }); return g;
  });
  const [overrides, setOverrides] = useStateApp({});

  useEffectApp(() => { Theme.applyTheme({ palette: t.palette, accent: t.accent, dark: t.dark }); }, [t.palette, t.accent, t.dark]);
  useEffectApp(() => { document.documentElement.style.setProperty('--ui-font', FONTS[t.font] || FONTS.Geist); }, [t.font]);

  const snap = SNAP_MAP[t.snapLabel] ?? 15;
  const newFlow = FLOW_MAP[t.newFlowLabel] ?? 'quick';
  const tlDefault = TL_MAP[t.tlDefaultLabel] ?? 'week';

  const unassigned = useMemo(() => {
    const dayList = Array.from({ length: 7 }, (_, i) => SS.iso(i));
    const assign = buildPlan(employees, positions, dayList, overrides);
    let need = 0, got = 0;
    positions.forEach((p) => p.shifts.forEach((sh) => dayList.forEach((d) => {
      if (matchesDay(sh, d)) { need += sh.headcount; got += (assign[`${sh.id}@${d}`] || []).length; }
    })));
    return need - got;
  }, [employees, positions, overrides]);

  const accents = Object.entries(Theme.ACCENTS);

  return (
    <div className="app">
      <div className="topbar">
        <div className="brand"><span className="logo">S</span><span className="brand-name"><b>Shift</b>Smith</span></div>
        <nav className="tabs">
          {TABS.map((x) => (
            <button key={x.id} className={`tab ${tab === x.id ? 'active' : ''}`} onClick={() => setTab(x.id)}>
              {React.createElement(Ic[x.icon], { size: 16 })}{x.label}
              {x.id === 'shiftplan' && unassigned > 0 && <span className="pill">{unassigned}</span>}
            </button>
          ))}
        </nav>
        <div className="spacer"></div>
        <button className="iconbtn" title="Toggle theme" onClick={() => setTweak('dark', !t.dark)}>
          {t.dark ? <Ic.sun/> : <Ic.moon/>}
        </button>
      </div>

      {tab === 'dashboard' && <Dashboard employees={employees} positions={positions} onGo={setTab} />}
      {tab === 'personnel' && <Personnel employees={employees} setEmployees={setEmployees} skills={SS.SKILLS} selId={selEmp} setSelId={setSelEmp} snap={snap} newFlow={newFlow} />}
      {tab === 'positions' && <Positions employees={employees} positions={positions} setPositions={setPositions} groupOrder={groupOrder} setGroupOrder={setGroupOrder} skills={SS.SKILLS} selId={selPos} setSelId={setSelPos} snap={snap} newFlow={newFlow} />}
      {tab === 'shiftplan' && <ShiftPlan key={tlDefault} employees={employees} positions={positions} groupOrder={groupOrder} initialMode={tlDefault} overrides={overrides} setOverrides={setOverrides} />}

      <TweaksPanel>
        <TweakSection label="Appearance" />
        <TweakToggle label="Dark mode" value={t.dark} onChange={(v) => setTweak('dark', v)} />
        <TweakRadio label="Palette" value={t.palette} options={['slate','stone','mono']} onChange={(v) => setTweak('palette', v)} />
        <TweakRow label="Accent">
          <div style={{ display: 'flex', gap: 6 }}>
            {accents.map(([key, a]) => (
              <button key={key} title={a.label} onClick={() => setTweak('accent', key)}
                style={{ width: 24, height: 24, borderRadius: 7, cursor: 'pointer',
                  background: `oklch(0.6 ${a.c} ${a.hue})`,
                  border: t.accent === key ? '2px solid var(--text)' : '2px solid transparent',
                  boxShadow: '0 0 0 1px var(--border)' }} />
            ))}
          </div>
        </TweakRow>
        <TweakSelect label="UI font" value={t.font} options={Object.keys(FONTS)} onChange={(v) => setTweak('font', v)} />

        <TweakSection label="Calendar interaction" />
        <TweakRadio label="Time snap" value={t.snapLabel} options={['15 min','30 min','60 min']} onChange={(v) => setTweak('snapLabel', v)} />
        <TweakSelect label="New block" value={t.newFlowLabel} options={['Paint, then tweak','Open a form']} onChange={(v) => setTweak('newFlowLabel', v)} />

        <TweakSection label="Shift plan" />
        <TweakRadio label="Default view" value={t.tlDefaultLabel} options={['Day','Week','Continuous']} onChange={(v) => setTweak('tlDefaultLabel', v)} />
      </TweaksPanel>
    </div>
  );
}
