// shiftplan.jsx — timeline: positions (rows) × time (columns). Day / week / continuous zoom.
import { useState as useStateSP, useRef as useRefSP, useEffect as useEffectSP, useLayoutEffect as useLayoutEffectSP } from 'react';
import React from 'react';
import { SS } from './data.js';
import { Ic } from './icons.jsx';
import { Theme } from './theme.js';

export function matchesDay(item, date) {
  if (item.until && date > item.until) return false;
  if (item.except && item.except.includes(date)) return false;
  if (item.repeat === 'none') return date === item.date;
  if (item.repeat === 'daily') return date >= item.date;
  if (item.repeat === 'weekly') {
    if (date < item.date) return false;
    if (item.days && item.days.length) return item.days.includes((SS.parseISO(date).getDay()+6)%7);
    return ((SS.parseISO(date).getDay()+6)%7) === ((SS.parseISO(item.date).getDay()+6)%7);
  }
  return false;
}
function onVacation(emp, date) {
  return emp.blocks.some((b) => b.type === 'vac' && matchesDay(b, date));
}
function prefScore(emp, shift, date) {
  let sc = 0;
  for (const b of emp.blocks) {
    if (!matchesDay(b, date) || b.allDay) continue;
    const overlap = b.start < shift.end && b.end > shift.start;
    if (!overlap) continue;
    if (b.type === 'pref') sc += 2; else if (b.type === 'undes') sc -= 2;
  }
  return sc;
}
// Mirrors backend Employee.isAvailableFor: pref/undes blocks define availability
// (an empty calendar = unavailable); a shift may only be filled by someone if it
// fits entirely within one window, with adjacent/overlapping windows merged.
export function availableFor(emp, shift, date) {
  const raw = [];
  for (const b of emp.blocks) {
    if (b.type !== 'pref' && b.type !== 'undes' || !matchesDay(b, date)) continue;
    if (b.allDay) raw.push([0, 1440]);
    else if (b.start < b.end) raw.push([b.start, b.end]);
  }
  raw.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const r of raw) {
    const last = merged[merged.length - 1];
    if (last && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
    else merged.push([r[0], r[1]]);
  }
  return merged.some((w) => w[0] <= shift.start && shift.end <= w[1]);
}
export function buildPlan(employees, positions, dayList, overrides = {}) {
  const empById = {}; employees.forEach((e) => { empById[e.id] = e; });
  const slots = [];
  positions.forEach((p) => p.shifts.forEach((sh) => dayList.forEach((d) => {
    if (matchesDay(sh, d)) slots.push({ pos: p, shift: sh, date: d, key: `${sh.id}@${d}` });
  })));
  slots.sort((a, b) => a.date.localeCompare(b.date) || a.shift.start - b.shift.start);
  const used = {}; const assign = {};
  for (const s of slots) {
    const ov = overrides[s.key];
    if (!ov) continue;
    const crew = ov.map((id) => empById[id]).filter(Boolean).slice(0, s.shift.headcount);
    assign[s.key] = crew;
    crew.forEach((e) => { used[`${e.id}:${s.date}`] = true; });
  }
  for (const s of slots) {
    if (overrides[s.key]) continue;
    const pref = s.shift.preferred || [];
    const cands = employees
      .filter((e) => SS.shiftSkills(s.shift).every((sk) => e.skills.includes(sk)) && !used[`${e.id}:${s.date}`] && !onVacation(e, s.date))
      .map((e) => ({ e, sc: prefScore(e, s.shift, s.date), pin: pref.includes(e.id) ? 1 : 0 }))
      .sort((a, b) => b.pin - a.pin || b.sc - a.sc);
    const got = [];
    for (const c of cands) { if (got.length >= s.shift.headcount) break; got.push(c.e); used[`${c.e.id}:${s.date}`] = true; }
    assign[s.key] = got;
  }
  return assign;
}

const LW_TL = 168;
const FREE_BASE = 18;

export function ShiftPlan({ employees, positions, groupOrder = [], initialMode = 'week', assign = {}, overrides = {}, setOverrides, sched = {}, onSolve, onPause }) {
  const [mode, setMode] = useStateSP(initialMode);
  const [anchor, setAnchor] = useStateSP(new Date());
  const [pph, setPph] = useStateSP(initialMode === 'free' ? FREE_BASE : 58);
  const [collapsed, setCollapsed] = useStateSP({});
  const [containerW, setContainerW] = useStateSP(0);
  const [editing, setEditing] = useStateSP(null);
  const [freeWin, setFreeWin] = useStateSP(() => ({ start: SS.isoOf(SS.addDays(SS.startOfWeek(new Date()), -7)), days: 35 }));
  const [navSeq, setNavSeq] = useStateSP(0);
  const [scrollX, setScrollX] = useStateSP(0);
  const scrollRef = useRefSP(null);
  const adjustRef = useRefSP(0);
  const wantRef = useRefSP(SS.isoOf(new Date()));
  const busyRef = useRefSP(false);
  const zoomScrollRef = useRefSP(null);
  const alignRef = useRefSP(initialMode === 'free' ? 'left' : null);
  const rafRef = useRefSP(0);

  useEffectSP(() => {
    const el = scrollRef.current; if (!el || !window.ResizeObserver) return;
    const ro = new ResizeObserver(() => setContainerW(el.clientWidth));
    ro.observe(el); setContainerW(el.clientWidth);
    return () => ro.disconnect();
  }, []);

  let dayList;
  if (mode === 'day') dayList = [SS.isoOf(anchor)];
  else if (mode === 'week') { const s = SS.startOfWeek(anchor); dayList = Array.from({ length: 7 }, (_, i) => SS.isoOf(SS.addDays(s, i))); }
  else dayList = Array.from({ length: freeWin.days }, (_, i) => SS.isoOf(SS.addDays(SS.parseISO(freeWin.start), i)));

  const weekPph = containerW ? Math.max(2, (containerW - LW_TL) / (7 * 24)) : 6;
  const effPph = mode === 'week' ? weekPph : pph;
  const boxOnly = mode === 'week';
  const trackW = mode === 'week' ? Math.max(0, containerW - LW_TL) : dayList.length * 24 * effPph;
  const totalHours = dayList.length * 24;
  // `assign` is the solver's best assignment, keyed `${shiftId}@${date}` → [employees].

  useLayoutEffectSP(() => {
    const el = scrollRef.current; if (!el) return;
    busyRef.current = true;
    if (mode === 'week') el.scrollLeft = 0;
    else if (wantRef.current) {
      const idx = (i => i >= 0 ? i : 0)(dayList.indexOf(wantRef.current));
      // alignRef === 'left': pin that day's midnight to the left edge of the track (Today button).
      // otherwise: land on ~6am with a small inset (default navigation framing).
      el.scrollLeft = alignRef.current === 'left' ? idx * 24 * effPph : (idx * 24 + 6) * effPph - 8;
      wantRef.current = null; alignRef.current = null;
    }
    requestAnimationFrame(() => { busyRef.current = false; });
  }, [navSeq, mode, containerW]);

  useLayoutEffectSP(() => {
    const el = scrollRef.current;
    if (el && adjustRef.current) { el.scrollLeft += adjustRef.current; adjustRef.current = 0; }
    requestAnimationFrame(() => { busyRef.current = false; });
  }, [freeWin]);

  // After a cursor-anchored zoom changes the canvas width, re-place scrollLeft so the
  // time under the pointer stays under the pointer. Only runs for wheel zoom (the ref is
  // null for zoom buttons / mode switches), and guards day-loading via busyRef.
  useLayoutEffectSP(() => {
    if (zoomScrollRef.current == null) return;
    const el = scrollRef.current;
    if (el) { busyRef.current = true; el.scrollLeft = zoomScrollRef.current; }
    zoomScrollRef.current = null;
    requestAnimationFrame(() => { busyRef.current = false; });
  }, [pph]);

  function onScroll() {
    if (mode !== 'free') return;
    const el = scrollRef.current; if (!el) return;
    // Keep the toolbar's visible-range label in sync, throttled to one update per frame.
    if (!rafRef.current) rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0; if (scrollRef.current) setScrollX(scrollRef.current.scrollLeft);
    });
    if (busyRef.current) return;
    const pad = 3 * 24 * effPph;
    if (el.scrollLeft < pad) {
      busyRef.current = true; const add = 21; adjustRef.current = add * 24 * effPph;
      setFreeWin((w) => ({ start: SS.isoOf(SS.addDays(SS.parseISO(w.start), -add)), days: Math.min(w.days + add, 420) }));
    } else if (el.scrollWidth - el.scrollLeft - el.clientWidth < pad) {
      busyRef.current = true;
      setFreeWin((w) => ({ ...w, days: Math.min(w.days + 21, 420) }));
    }
  }

  // Zoom the time axis while keeping the content `anchorOffset` px from the scroll
  // viewport's left edge pinned on screen. computeNz maps the old pph → new (clamped
  // to 6..180). The matching scrollLeft is stashed for the [pph] layout effect above.
  function zoomAround(anchorOffset, computeNz) {
    const el = scrollRef.current; if (!el) return;
    const contentX = el.scrollLeft + anchorOffset;
    setPph((z) => {
      const nz = Math.max(6, Math.min(180, computeNz(z)));
      const hour = (contentX - LW_TL) / z;            // time at the anchor (LW_TL = sticky label column)
      zoomScrollRef.current = LW_TL + hour * nz - anchorOffset;
      return nz;
    });
  }
  // Horizontal center of the visible track, for button/reset zooms.
  const viewCenter = () => { const el = scrollRef.current; return el ? (LW_TL + el.clientWidth) / 2 : 0; };

  // Timeline wheel behavior (industry-standard, only while the pointer is over the
  // timeline — elsewhere on the page the browser keeps its defaults, e.g. Ctrl+scroll
  // page zoom over the logo). Attached natively with { passive: false } so we can
  // preventDefault; React's synthetic onWheel is passive and can't stop page zoom.
  //   • plain wheel        → horizontal scroll (time)
  //   • Shift + wheel      → vertical scroll (tracks/rows)
  //   • Ctrl/Cmd + wheel   → zoom the time axis (no browser page zoom)
  useEffectSP(() => {
    const el = scrollRef.current; if (!el) return;
    function onWheel(e) {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault(); // stop the browser from zooming the whole page
        if (mode === 'free') {
          // Anchor the zoom on the cursor: keep the time under the pointer fixed on screen.
          const mouseOffset = e.clientX - el.getBoundingClientRect().left; // px from track viewport's left
          zoomAround(mouseOffset, (z) => z - Math.sign(e.deltaY) * Math.max(1, z * 0.12));
        }
        return;
      }
      if (mode === 'week') return; // week mode fits the viewport; nothing to scroll
      if (e.shiftKey) {
        if (e.deltaY !== 0) { e.preventDefault(); el.scrollTop += e.deltaY; }
        return;
      }
      // Plain wheel: a vertical-only wheel (mouse) drives the time axis horizontally;
      // a trackpad's native horizontal delta is left untouched.
      if (e.deltaY !== 0 && e.deltaX === 0) {
        e.preventDefault();
        el.scrollLeft += e.deltaY;
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [mode]);

  function goAnchor(next) {
    setAnchor(next); wantRef.current = SS.isoOf(next); setNavSeq((n) => n + 1);
    if (mode === 'free') setFreeWin({ start: SS.isoOf(SS.addDays(SS.startOfWeek(next), -7)), days: 35 });
  }
  function pickMode(m) {
    setMode(m); wantRef.current = SS.isoOf(anchor); setNavSeq((n) => n + 1);
    if (m === 'day') setPph(58);
    else if (m === 'free') {
      // Initialize the continuous view on today, pinned to the left edge of the track.
      const today = new Date();
      setPph(FREE_BASE); setAnchor(today); wantRef.current = SS.isoOf(today); alignRef.current = 'left';
      setFreeWin({ start: SS.isoOf(SS.addDays(SS.startOfWeek(today), -7)), days: 35 });
    }
  }
  function step(dir) {
    if (mode === 'free') {                       // continuous: nudge the view by one day
      const el = scrollRef.current; if (el) el.scrollLeft += dir * 24 * effPph;
      return;
    }
    goAnchor(SS.addDays(anchor, dir * (mode === 'day' ? 1 : 7)));
  }
  function goToday() {
    if (mode !== 'free') { goAnchor(new Date()); return; }
    const today = new Date();                     // continuous: pin today's column to the left edge
    setAnchor(today); wantRef.current = SS.isoOf(today); alignRef.current = 'left';
    setFreeWin({ start: SS.isoOf(SS.addDays(SS.startOfWeek(today), -7)), days: 35 });
    setNavSeq((n) => n + 1);
  }

  // Continuous mode: derive the label from the days actually visible in the track viewport
  // (left edge sits at content x = scrollX + LW_TL; right edge at scrollX + containerW).
  function freeLabel() {
    const n = dayList.length; if (!n) return '';
    const last = n - 1;
    const i0 = Math.max(0, Math.min(last, Math.floor(scrollX / effPph / 24)));
    const i1 = Math.max(0, Math.min(last, Math.floor((scrollX + Math.max(0, containerW - LW_TL) - 1) / effPph / 24)));
    const d0 = SS.parseISO(dayList[i0]);
    if (i1 <= i0) return d0.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
    const d1 = SS.parseISO(dayList[i1]);
    const left = d0.getFullYear() === d1.getFullYear()
      ? d0.toLocaleDateString([], { month: 'short', day: 'numeric' })
      : d0.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
    return `${left} – ${d1.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' })}`;
  }
  const stepLabel = mode === 'day'
    ? anchor.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' })
    : mode === 'week'
    ? `${SS.parseISO(dayList[0]).toLocaleDateString([], { month: 'short', day: 'numeric' })} – ${SS.parseISO(dayList[6]).toLocaleDateString([], { month: 'short', day: 'numeric' })}`
    : freeLabel();

  const tickStep = Math.max(1, Math.ceil(46 / effPph));
  const showHourLabels = !boxOnly && effPph >= 10;
  const pct = Math.round(pph / FREE_BASE * 100);

  const now = new Date();
  const nowIdx = dayList.indexOf(SS.isoOf(now));
  const nowX = nowIdx >= 0 ? (nowIdx * 24 + now.getHours() + now.getMinutes()/60) * effPph : -1;

  const hasGroups = positions.some((p) => p.group);
  const gKey = (g) => g || '__ungrouped';
  const gCount = {};
  positions.forEach((p) => { const k = gKey(p.group); gCount[k] = (gCount[k] || 0) + 1; });

  const seq = [];
  let cur = '';
  positions.forEach((p) => { const g = p.group || null; if (g !== cur) { seq.push({ type: 'g', g }); cur = g; } seq.push({ type: 'p', p }); });

  function openEditor(ev, sh, pos, date, key) {
    ev.stopPropagation();
    const r = ev.currentTarget.getBoundingClientRect();
    setEditing({ key, sh, pos, date, x: r.left, y: r.bottom + 6 });
  }

  // Boxes have a fixed height in every view; only their width varies, and the
  // content is chosen purely from that width (see `bar`).
  const BOX_H = 62, BOX_TOP = 7;

  function bar(p, d, di) {
    return p.shifts.filter((sh) => matchesDay(sh, d)).map((sh) => {
      const key = `${sh.id}@${d}`;
      const crew = assign[key] || [];
      const edited = !!overrides[key];
      const x = (di*24 + sh.start/60) * effPph;
      const w = Math.max(4, (sh.end - sh.start)/60 * effPph);
      const full = crew.length >= sh.headcount;
      const title = `${sh.name} · ${SS.minLabel(sh.start)}–${SS.minLabel(sh.end)} · ${SS.shiftSkills(sh).join(' · ') || '—'} · ${crew.length}/${sh.headcount}${edited ? ' · manually set' : ''}`;
      const cls = `bar ${full?'full':'under'} ${edited?'edited':''}`;
      const style = { left: x+1, width: Math.max(3, w-2), top: BOX_TOP, height: BOX_H };

      // How many 18px circles (3px gap) fit across the box's inner width.
      const fit = Math.floor((w - 16 + 3) / 21);
      if (fit < 1) {
        // Too small for even one circle — just a plain coloured box.
        return <div key={key} className={cls + ' tiny'} title={title} onClick={(e) => openEditor(e, sh, p, d, key)} style={style}></div>;
      }

      // One circle per headcount slot: filled avatars first, then empty slots.
      const circles = Array.from({ length: sh.headcount }, (_, i) => {
        const e = crew[i];
        return e
          ? <span key={i} className="av" style={{ background: Theme.avatarColor(e.name) }} title={e.name}>{e.name.split(' ').map(x=>x[0]).slice(0,2).join('')}</span>
          : <span key={i} className="slot-empty"></span>;
      });
      // Collapse whatever doesn't fit into a single "+N" circle.
      let shown = circles;
      if (circles.length > fit) {
        shown = circles.slice(0, fit - 1);
        shown.push(<span key="more" className="av more" title={`${crew.length}/${sh.headcount} staffed`}>+{circles.length - (fit - 1)}</span>);
      }

      return (
        <div key={key} className={cls} title={title} onClick={(e) => openEditor(e, sh, p, d, key)} style={style}>
          <div className="bhead">
            <span className="bt">{sh.name}</span>
            {edited && <span className="bedit" title="Manually set"><Ic.user size={9}/></span>}
          </div>
          <span className="btime mono">{SS.minLabel(sh.start)}–{SS.minLabel(sh.end)}</span>
          <div className="crew">{shown}</div>
        </div>
      );
    });
  }

  const rowH = BOX_H + 2 * BOX_TOP;

  return (
    <div className="tl" style={{ '--lw': LW_TL + 'px' }}>
      <div className="tl-toolbar">
        <div className="nav">
          <button className="iconbtn" onClick={() => step(-1)}><Ic.chevL/></button>
          <button className="btn sm" onClick={goToday}>Today</button>
          <button className="iconbtn" onClick={() => step(1)}><Ic.chevR/></button>
        </div>
        <div className="cal-title" style={{ minWidth: 180 }}>{stepLabel}</div>
        <div style={{ flex: 1 }}></div>
        <div className="legend" style={{ display: 'flex', gap: 12, marginRight: 4 }}>
          {[['full','Fully staffed','green'],['under','Understaffed','amber']].map(([c,l,t]) => (
            <span key={c} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-3)' }}>
              <span style={{ width: 10, height: 10, borderRadius: 3, background: `var(--${t}-solid)` }}></span>{l}
            </span>
          ))}
        </div>
        {(mode === 'free') && (
          <div className="seg">
            <button onClick={() => zoomAround(viewCenter(), (z) => z * 0.8)}><Ic.zoomOut size={14}/></button>
            <button className="mono zoom-pct" style={{ minWidth: 48 }} title="Reset zoom to 100%" onClick={() => zoomAround(viewCenter(), () => FREE_BASE)}>{pct}%</button>
            <button onClick={() => zoomAround(viewCenter(), (z) => z * 1.25)}><Ic.zoomIn size={14}/></button>
          </div>
        )}
        <div className="seg">
          {[['day','Day'],['week','Week'],['free','Continuous']].map(([v,l]) => (
            <button key={v} className={mode === v ? 'on' : ''} onClick={() => pickMode(v)}>{l}</button>
          ))}
        </div>
      </div>

      <SolverBar sched={sched} onSolve={onSolve} onPause={onPause} />

      <div className={`tl-scroll ${boxOnly ? 'no-xscroll' : ''}`} ref={scrollRef} onScroll={onScroll}>
        <div className="tl-canvas" style={{ width: LW_TL + trackW }}>
          <div className="tl-head" style={{ height: 44 }}>
            <div className="tl-corner">{mode === 'free' && <span style={{ display:'flex', alignItems:'center', gap:6 }}><Ic.move size={13}/> </span>}Position</div>
            <div className="tl-times" style={{ width: trackW, height: 44 }}>
              {dayList.map((d, di) => {
                const dt = SS.parseISO(d); const we = dt.getDay()===0||dt.getDay()===6;
                return <div key={d} className={`tl-dayband ${we?'we':''}`} style={{ left: di*24*effPph, width: 24*effPph }}>
                  {effPph*24 > 60 ? dt.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: dayList.length>7?'short':undefined }) : dt.getDate()}
                </div>;
              })}
              {Array.from({ length: totalHours+1 }, (_, h) => {
                const isDay = h % 24 === 0;
                if (!isDay && (boxOnly || h % tickStep !== 0)) return null;
                return <div key={h} className={`tl-tick ${isDay?'day':''}`} style={{ left: h*effPph }}>
                  {showHourLabels && !isDay && <span className="mono">{SS.pad(h%24)}</span>}
                </div>;
              })}
            </div>
          </div>

          {seq.map((row) => {
            if (row.type === 'g') {
              if (row.g === null && !hasGroups) return null;
              const k = gKey(row.g);
              const col = collapsed[k];
              return (
                <div key={'g:'+k} className="tl-grouprow">
                  <button className="tl-glabel" onClick={() => setCollapsed({ ...collapsed, [k]: !col })}>
                    <Ic.chevD size={13} style={{ transform: col ? 'rotate(-90deg)' : 'none', transition: 'transform .12s' }}/>
                    <span className="gname">{row.g || 'Ungrouped'}</span>
                    <span className="gcount">{gCount[k]}</span>
                  </button>
                  <div className="tl-gfill" style={{ width: trackW }}></div>
                </div>
              );
            }
            const p = row.p;
            if (collapsed[gKey(p.group)]) return null;
            return (
              <div key={p.id} className="tl-row" style={{ height: rowH }}>
                <div className="tl-label">
                  <div className="avatar sq" style={{ background: `oklch(0.62 0.13 ${p.color})`, width: boxOnly?24:30, height: boxOnly?24:30, flexBasis: boxOnly?24:30 }}><Ic.briefcase size={boxOnly?13:15}/></div>
                  <div style={{ minWidth: 0 }}>
                    <div className="nm">{p.name}</div>
                    {!boxOnly && <div className="sub">{p.skills.join(' · ') || '—'}</div>}
                  </div>
                </div>
                <div className="tl-track" style={{ width: trackW }}>
                  {dayList.map((d, di) => {
                    const dt = SS.parseISO(d); const we = dt.getDay()===0||dt.getDay()===6;
                    return <React.Fragment key={d}>
                      {we && <div className="tl-gband we" style={{ left: di*24*effPph, width: 24*effPph }}></div>}
                      <div className="tl-gline day" style={{ left: di*24*effPph }}></div>
                    </React.Fragment>;
                  })}
                  {nowX >= 0 && <div className="tl-now" style={{ left: nowX }}></div>}
                  {dayList.map((d, di) => bar(p, d, di))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
      {mode === 'free' && <div style={{ padding: '6px 18px', fontSize: 11.5, color: 'var(--text-3)', borderTop: '1px solid var(--border)', background: 'var(--surface)' }}>
        Scroll sideways to load more days · <span className="kbd">⌘</span>/<span className="kbd">Ctrl</span> + scroll to zoom
      </div>}
      {editing && <AssignEditor ctx={editing} employees={employees} assign={assign}
        overrides={overrides} setOverrides={setOverrides} onClose={() => setEditing(null)} />}
    </div>
  );
}

function SolverBar({ sched = {}, onSolve, onPause }) {
  const active = sched.solverStatus === 'SOLVING_ACTIVE' || sched.solverStatus === 'SOLVING_SCHEDULED';
  const total = sched.total ?? 0;
  return (
    <div className="tl-substat">
      <span className={`solver-badge ${active ? 'on' : ''}`} title={active ? 'Solver running' : 'Solver idle (steady state)'}>
        <span className="dot"></span>{active ? 'Solving…' : 'Steady'}
      </span>
      <span className="sb-stat"><b>{sched.staffed ?? 0}/{total}</b> staffed</span>
      <span className="sb-stat"><b>{sched.unassigned ?? 0}</b> unassigned</span>
      {sched.score && <span className="sb-stat mono" title="hard / medium / soft">{sched.score.hard}/{sched.score.medium}/{sched.score.soft}</span>}
      <div style={{ flex: 1 }}></div>
      <button className="btn sm primary" onClick={onSolve} disabled={active}><Ic.play size={13}/> Solve now</button>
      <button className="btn sm" onClick={onPause} disabled={!active}><Ic.pause size={13}/> Pause</button>
    </div>
  );
}

function AssignEditor({ ctx, employees, assign, overrides, setOverrides, onClose }) {
  const { key, sh, pos, date } = ctx;
  const popRef = useRefSP(null);
  const [place, setPlace] = useStateSP({ left: ctx.x, top: ctx.y, ready: false });

  useLayoutEffectSP(() => {
    const el = popRef.current; if (!el) return;
    const r = el.getBoundingClientRect();
    const vw = window.innerWidth, vh = window.innerHeight;
    let left = Math.min(ctx.x, vw - r.width - 12); left = Math.max(12, left);
    let top = ctx.y;
    if (top + r.height > vh - 12) top = Math.max(12, ctx.y - r.height - 12);
    setPlace({ left, top, ready: true });
  }, [ctx.key]);

  useEffectSP(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const reqSkills = SS.shiftSkills(sh);
  const eff = (overrides[key] || (assign[key] || []).map((e) => e.id)).slice();
  const chosen = new Set(eff);
  const atMax = eff.length >= sh.headcount;

  const busyMap = {};
  Object.entries(assign).forEach(([k, crew]) => {
    if (k === key || !k.endsWith('@' + date)) return;
    crew.forEach((e) => { busyMap[e.id] = true; });
  });

  function toggle(id) {
    const cur = eff.slice();
    const i = cur.indexOf(id);
    if (i >= 0) cur.splice(i, 1);
    else { if (cur.length >= sh.headcount) return; cur.push(id); }
    setOverrides({ ...overrides, [key]: cur });
  }
  function resetAuto() { const next = { ...overrides }; delete next[key]; setOverrides(next); }

  const ranked = employees.map((e) => ({
    e,
    skill: reqSkills.every((sk) => e.skills.includes(sk)),
    avail: availableFor(e, sh, date),
    leave: onVacation(e, date),
    pref: (sh.preferred || []).includes(e.id),
    elsewhere: !!busyMap[e.id] && !chosen.has(e.id),
  })).sort((a, b) =>
    (chosen.has(b.e.id) - chosen.has(a.e.id)) || (b.pref - a.pref) || (b.skill - a.skill) || (b.avail - a.avail) || (a.leave - b.leave) || a.e.name.localeCompare(b.e.name)
  );

  const dateLabel = SS.parseISO(date).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  const isOverride = !!overrides[key];

  return (
    <>
      <div className="pop-backdrop" onClick={onClose}></div>
      <div className="pop assign-pop" ref={popRef} style={{ left: place.left, top: place.top, visibility: place.ready ? 'visible' : 'hidden' }}>
        <div className="ae-head">
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="ae-title">{sh.name}</div>
            <div className="ae-sub">{pos.name} · {SS.minLabel(sh.start)}–{SS.minLabel(sh.end)} · {dateLabel}</div>
          </div>
          <button className="iconbtn" onClick={onClose} style={{ width: 26, height: 26, flex: '0 0 26px' }}><Ic.x size={15}/></button>
        </div>
        <div className="ae-meta">
          <span className={`ae-count ${eff.length >= sh.headcount ? 'ok' : 'low'}`}>{eff.length}/{sh.headcount} staffed</span>
          {isOverride && <span className="ae-badge"><Ic.user size={11}/> Manual</span>}
          {reqSkills.length > 0 && <span className="ae-req">Needs {reqSkills.join(', ')}</span>}
        </div>
        <div className="ae-list">
          {ranked.map(({ e, skill, avail, leave, pref, elsewhere }) => {
            const on = chosen.has(e.id);
            const blocked = !on && atMax;
            return (
              <button key={e.id} type="button" className={`ae-row ${on ? 'on' : ''} ${blocked ? 'blocked' : ''}`}
                disabled={blocked} onClick={() => toggle(e.id)}>
                <span className="ae-check">{on && <Ic.check size={13}/>}</span>
                <span className="avatar sq" style={{ width: 26, height: 26, flexBasis: 26, fontSize: 10, background: Theme.avatarColor(e.name) }}>{e.name.split(' ').map((x) => x[0]).slice(0,2).join('')}</span>
                <span className="ae-who">
                  <span className="ae-name">{e.name}{pref && <Ic.star size={11} className="ae-star"/>}</span>
                  <span className="ae-tags">
                    {!skill && <span className="ae-tag warn"><Ic.alert size={10}/> Missing skill</span>}
                    {!avail && <span className="ae-tag warn"><Ic.clock size={10}/> Unavailable</span>}
                    {leave && <span className="ae-tag warn"><Ic.palm size={10}/> On leave</span>}
                    {elsewhere && <span className="ae-tag">Booked elsewhere</span>}
                    {skill && avail && !leave && !elsewhere && e.skills.length > 0 && <span className="ae-tag muted">{e.skills.join(', ')}</span>}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="ae-foot">
          <button className="btn sm" onClick={resetAuto} disabled={!isOverride}><Ic.sparkles size={13}/> Reset to auto</button>
          <button className="btn sm primary" onClick={onClose}>Done</button>
        </div>
      </div>
    </>
  );
}
