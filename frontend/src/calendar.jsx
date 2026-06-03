// calendar.jsx — reusable calendar: day/week/month, drag-to-create, click-to-edit popover, recurrence.
import { useState, useRef, useEffect, useCallback } from 'react';
import React from 'react';
import { SS } from './data.js';
import { Ic } from './icons.jsx';

const WD = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];

function weekdayOf(iso) { return (SS.parseISO(iso).getDay() + 6) % 7; }
function occursOn(it, d) {
  if (it.except && it.except.includes(d)) return false;
  // Multi-day span (e.g. a vacation range): start .. endDate, inclusive.
  if (it.endDate && (!it.repeat || it.repeat === 'none')) return d >= it.date && d <= it.endDate;
  if (it.until && d > it.until) return false;
  if (!it.repeat || it.repeat === 'none') return d === it.date;
  if (it.repeat === 'daily') return d >= it.date;
  if (it.repeat === 'weekly') {
    if (it.days && it.days.length) return d >= it.date && it.days.includes(weekdayOf(d));
    return weekdayOf(d) === weekdayOf(it.date) && d >= it.date;
  }
  return false;
}
function isOvernight(it) { return !it.allDay && it.end < it.start; }

// True when applying {scope} to {item} would change or remove an occurrence before today.
function touchesPast(scope, item, occDate) {
  const today = SS.isoOf(new Date());
  if (scope === 'this' || scope === 'future') return (occDate || item.date) < today;
  return item.date < today; // 'all' — the series starts at its anchor date
}

function expand(items, dayList) {
  const out = [];
  for (const it of items) {
    for (const d of dayList) {
      if (it.allDay) { if (occursOn(it, d)) out.push({ item: it, date: d, key: `${it.id}@${d}`, seg: 'full', s: 0, e: 0 }); continue; }
      if (occursOn(it, d)) {
        if (isOvernight(it)) out.push({ item: it, date: d, key: `${it.id}@${d}`, seg: 'head', s: it.start, e: 1440 });
        else out.push({ item: it, date: d, key: `${it.id}@${d}`, seg: 'full', s: it.start, e: it.end });
      }
      if (isOvernight(it)) {
        const prev = SS.isoOf(SS.addDays(SS.parseISO(d), -1));
        if (occursOn(it, prev)) out.push({ item: it, date: d, key: `${it.id}@${d}@t`, seg: 'tail', s: 0, e: it.end });
      }
    }
  }
  return out;
}

function packLanes(evs) {
  const sorted = [...evs].sort((a, b) => a.s - b.s || b.e - a.e);
  const lanes = [];
  for (const e of sorted) {
    let placed = false;
    for (let i = 0; i < lanes.length; i++) {
      if (lanes[i] <= e.s) { e._lane = i; lanes[i] = e.e; placed = true; break; }
    }
    if (!placed) { e._lane = lanes.length; lanes.push(e.e); }
  }
  for (const e of sorted) {
    let max = 1;
    for (const o of sorted) {
      if (o === e) continue;
      if (o.s < e.e && o.e > e.s) max = Math.max(max, (o._lane ?? 0) + 1, (e._lane ?? 0) + 1);
    }
    e._lanes = Math.max(max, (e._lane ?? 0) + 1);
  }
  return sorted;
}

export function Calendar(props) {
  const { view, anchor, items, kind, zoom = 46, paint, palette,
          newItem, onCommit, onDelete, onSplit, extraFields, dayStart = 6,
          snap = 15 } = props;
  const scrollRef = useRef(null);
  const [editor, setEditor] = useState(null);
  const [draft, setDraft] = useState(null);   // local working copy; only persisted on confirm
  const [drag, setDrag] = useState(null);
  const dragRef = useRef(null);
  const idPfx = kind === 'availability' ? 'b' : 's';

  function openEditor(it, occDate) {
    setDraft(JSON.parse(JSON.stringify(it)));
    setEditor({ id: it.id, isNew: false, occDate: occDate || it.date });
  }

  const dayList = view === 'week'
    ? Array.from({ length: 7 }, (_, i) => SS.isoOf(SS.addDays(SS.startOfWeek(anchor), i)))
    : view === 'day' ? [SS.isoOf(anchor)] : monthDays(anchor);

  const todayISO = SS.isoOf(new Date());

  useEffect(() => {
    if (scrollRef.current && view !== 'month') scrollRef.current.scrollTop = dayStart * zoom - 8;
  }, [view, zoom]);

  const yToMin = (y) => Math.max(0, Math.min(1440, Math.round((y / zoom * 60) / snap) * snap));

  function startCreate(d) {
    setDraft(d);
    setEditor({ id: d.id, isNew: true, occDate: d.date });
  }
  function addNew() {
    const date = view === 'day' ? SS.isoOf(anchor) : SS.isoOf(SS.startOfWeek(anchor));
    startCreate(newItem({ date, start: 9 * 60, end: 17 * 60 }));
  }

  function onColMouseDown(e, dayISO) {
    if (e.button !== 0) return;
    const colRect = e.currentTarget.getBoundingClientRect();
    const a = yToMin(e.clientY - colRect.top);
    const st = { dayISO, a, b: a, colRect };
    dragRef.current = st; setDrag(st);
    const move = (ev) => { const b = yToMin(ev.clientY - colRect.top); const s = { ...dragRef.current, b }; dragRef.current = s; setDrag(s); };
    const up = (ev) => {
      window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up);
      const s = dragRef.current; dragRef.current = null; setDrag(null);
      let lo = Math.min(s.a, s.b), hi = Math.max(s.a, s.b);
      if (hi - lo < snap) { hi = Math.min(1440, lo + 120); }
      startCreate(newItem({ date: dayISO, start: lo, end: hi }));
    };
    window.addEventListener('mousemove', move); window.addEventListener('mouseup', up);
  }

  // Edits live only in `draft` until the user confirms (Save / Done); the grid
  // previews them, and closing/Escape/backdrop simply throws the draft away.
  const editing = draft;
  const renderItems = draft
    ? (editor.isNew ? [...items, draft] : items.map((x) => (x.id === draft.id ? draft : x)))
    : items;

  function patch(p) { setDraft((d) => ({ ...d, ...p })); }
  function discard() { setDraft(null); setEditor(null); }

  function done(scope = 'all') {
    const d = draft;
    if (!d) { discard(); return; }
    if (editor.isNew) { onCommit(d); discard(); return; }
    const orig = items.find((x) => x.id === d.id);
    const occDate = (editor && editor.occDate) || d.date;
    if (onSplit && orig && d.repeat && d.repeat !== 'none' && scope === 'this') {
      const restored = { ...orig, except: [...(orig.except || []), occDate].filter((v, i, a) => a.indexOf(v) === i) };
      const single = { ...d, id: SS.uid(idPfx), repeat: 'none', date: occDate };
      delete single.until; delete single.except; delete single.days;
      onSplit(restored, [single]);
    } else if (onSplit && orig && d.repeat && d.repeat !== 'none' && scope === 'future' && occDate > orig.date) {
      const prevDay = SS.isoOf(SS.addDays(SS.parseISO(occDate), -1));
      const restored = { ...orig, until: prevDay };
      const series = { ...d, id: SS.uid(idPfx), date: occDate, except: (orig.except || []).filter((e) => e >= occDate) };
      delete series.until;
      onSplit(restored, [series]);
    } else {
      onCommit(d); // whole series / single item
    }
    discard();
  }
  function remove(scope = 'all') {
    if (editor.isNew) { discard(); return; } // unsaved new item — nothing to delete
    const cur = items.find((x) => x.id === editor.id);
    if (!cur) { discard(); return; }
    const occDate = (editor && editor.occDate) || cur.date;
    if (cur.repeat && cur.repeat !== 'none' && scope === 'this') {
      onCommit({ ...cur, except: [...(cur.except || []), occDate].filter((v, i, a) => a.indexOf(v) === i) });
    } else if (cur.repeat && cur.repeat !== 'none' && scope === 'future') {
      const prevDay = SS.isoOf(SS.addDays(SS.parseISO(occDate), -1));
      onCommit({ ...cur, until: prevDay });
    } else {
      onDelete(cur.id);
    }
    discard();
  }

  return (
    <div className="cal">
      <Toolbar {...props} onAdd={addNew} />
      {view === 'month'
        ? <MonthGrid dayList={dayList} anchor={anchor} items={renderItems} kind={kind} todayISO={todayISO}
            onDayClick={(d, el) => startCreate(newItem({ date: d, start: 9*60, end: 17*60 }))}
            onEvtClick={(it, occDate) => openEditor(it, occDate)} />
        : <TimeGrid scrollRef={scrollRef} dayList={dayList} view={view} zoom={zoom} items={renderItems}
            kind={kind} todayISO={todayISO} drag={drag} onColMouseDown={onColMouseDown}
            onEvtClick={(it, occDate) => openEditor(it, occDate)} />}
      {editing && (
        <Editor item={editing} kind={kind} palette={palette} isNew={editor.isNew}
          occDate={editor.occDate} scopable={!!onSplit && !editor.isNew}
          onPatch={patch} onRemove={remove} onClose={discard} onDone={done} extraFields={extraFields} />
      )}
    </div>
  );
}

function monthDays(anchor) {
  const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
  const last = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0); // last day of month
  const start = SS.startOfWeek(first);
  // Only render whole weeks that actually touch this month — no trailing week
  // that lies entirely in the next month.
  const days = Math.round((SS.startOfWeek(last) - start) / SS.DAY) + 7;
  return Array.from({ length: days }, (_, i) => SS.isoOf(SS.addDays(start, i)));
}

function Toolbar(props) {
  const { view, onView, anchor, onAnchor, kind, paint, onPaint, zoom, onZoom, onAdd } = props;
  const monthLabel = anchor.toLocaleDateString([], { month: 'long', year: 'numeric' });
  let title = monthLabel, sub = '';
  if (view === 'week') {
    const ws = SS.startOfWeek(anchor), we = SS.addDays(ws, 6);
    title = `${ws.toLocaleDateString([], { month: 'short', day: 'numeric' })} – ${we.toLocaleDateString([], { month: 'short', day: 'numeric' })}`;
    sub = String(ws.getFullYear());
  } else if (view === 'day') {
    title = anchor.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
  }
  const step = (dir) => {
    if (view === 'month') onAnchor(new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1));
    else onAnchor(SS.addDays(anchor, dir * (view === 'week' ? 7 : 1)));
  };
  return (
    <div className="cal-toolbar">
      <div className="nav">
        <button className="iconbtn" onClick={() => step(-1)}><Ic.chevL/></button>
        <button className="btn sm" onClick={() => onAnchor(new Date())}>Today</button>
        <button className="iconbtn" onClick={() => step(1)}><Ic.chevR/></button>
      </div>
      <div className="cal-title">{title} {sub && <span className="sub">{sub}</span>}</div>
      {view !== 'month' && (
        <div className="seg" style={{ marginRight: 6, flexShrink: 0 }}>
          <button onClick={() => onZoom(Math.max(28, zoom - 10))}><Ic.zoomOut size={14}/></button>
          <button onClick={() => onZoom(Math.min(96, zoom + 10))}><Ic.zoomIn size={14}/></button>
        </div>
      )}
      <div className="seg" style={{ marginRight: 8, flexShrink: 0 }}>
        {['day','week','month'].map((v) => (
          <button key={v} className={view === v ? 'on' : ''} onClick={() => onView(v)}>{v[0].toUpperCase()+v.slice(1)}</button>
        ))}
      </div>
      <button className="btn primary sm" style={{ flexShrink: 0 }} onClick={onAdd} title={kind === 'availability' ? 'Add availability' : 'Add shift'}>
        <Ic.plus size={14}/> Add
      </button>
    </div>
  );
}

function TimeGrid({ scrollRef, dayList, view, zoom, items, kind, todayISO, drag, onColMouseDown, onEvtClick }) {
  const H = 24 * zoom;
  const occ = expand(items, dayList);
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const allDayByDay = {};
  dayList.forEach((d) => allDayByDay[d] = []);
  const timedByDay = {};
  dayList.forEach((d) => timedByDay[d] = []);
  occ.forEach((o) => { (o.item.allDay ? allDayByDay : timedByDay)[o.date].push(o); });
  const hasAllDay = Object.values(allDayByDay).some((a) => a.length);

  // Measure the day-header row so the all-day row can stick right below it.
  const headRef = useRef(null);
  const [headH, setHeadH] = useState(0);
  useEffect(() => {
    const el = headRef.current;
    if (!el) return;
    const measure = () => setHeadH(el.offsetHeight);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [dayList.length]);

  const gridCols = `56px repeat(${dayList.length}, minmax(0, 1fr))`;
  return (
    <div className="cal-scroll" ref={scrollRef}>
      <div className="weekgrid" style={{ gridTemplateColumns: gridCols, gridTemplateRows: `auto ${hasAllDay ? 'auto' : ''} ${H}px` }}>
        <div className="wg-corner" ref={headRef} style={{ gridRow: 1, gridColumn: 1 }}></div>
        {dayList.map((d, i) => {
          const dt = SS.parseISO(d); const isToday = d === todayISO;
          return (
            <div key={d} className={`wg-dayhead ${isToday ? 'today' : ''}`} style={{ gridRow: 1, gridColumn: i + 2 }}>
              <span className="dow">{WD[(dt.getDay()+6)%7]}</span>
              <span className="dnum">{dt.getDate()}</span>
            </div>
          );
        })}
        {hasAllDay && <>
          <div className="wg-timecol" style={{ gridRow: 2, gridColumn: 1, display:'grid', placeItems:'center', position:'sticky', top: headH, zIndex: 6, borderBottom: '1px solid var(--border)' }}>
            <span className="wg-timelabel" style={{ transform:'none', paddingRight: 0 }}>all-day</span>
          </div>
          {dayList.map((d, i) => (
            <div key={d} className="wg-col" style={{ gridRow: 2, gridColumn: i+2, position:'sticky', top: headH, zIndex: 5, background:'var(--surface)', borderBottom: '1px solid var(--border)', padding: 4, display:'flex', flexDirection:'column', gap: 3, minHeight: 30 }}>
              {allDayByDay[d].map((o) => (
                <div key={o.key} className={`mg-evt allday tone-${toneCls(o.item, kind)}`} onClick={(e) => onEvtClick(o.item, o.date)} style={{ cursor:'pointer' }}>
                  {kind==='availability' ? <Ic.palm size={11}/> : null}{labelOf(o.item, kind)}
                </div>
              ))}
            </div>
          ))}
        </>}
        <div className="wg-timecol" style={{ gridRow: hasAllDay ? 3 : 2, gridColumn: 1, position: 'relative' }}>
          {Array.from({ length: 24 }, (_, h) => (
            <div key={h} className="wg-timelabel" style={{ position: 'absolute', top: h * zoom, right: 0, width: '100%' }}>{h === 0 ? '' : SS.pad(h)+':00'}</div>
          ))}
        </div>
        {dayList.map((d, i) => {
          const dt = SS.parseISO(d); const weekend = (dt.getDay()===0||dt.getDay()===6);
          const evs = packLanes(timedByDay[d]);
          const isToday = d === todayISO;
          return (
            <div key={d} className={`wg-col ${weekend?'weekend':''}`} style={{ gridRow: hasAllDay ? 3 : 2, gridColumn: i+2, position:'relative' }}
                 onMouseDown={(e) => onColMouseDown(e, d)}>
              {Array.from({ length: 24 }, (_, h) => (<React.Fragment key={h}>
                <div className="wg-hourline" style={{ top: h * zoom }}></div>
                <div className="wg-halfline" style={{ top: h * zoom + zoom/2 }}></div>
              </React.Fragment>))}
              {evs.map((o) => {
                const top = o.s/60*zoom, h = Math.max(16, (o.e - o.s)/60*zoom);
                const w = 100 / o._lanes, left = o._lane * w;
                return (
                  <div key={o.key} className={`evt tone-${toneCls(o.item, kind)} ${o.seg !== 'full' ? 'seg-'+o.seg : ''}`} onMouseDown={(e)=>e.stopPropagation()}
                       onClick={(e) => onEvtClick(o.item, o.seg === 'tail' ? SS.isoOf(SS.addDays(SS.parseISO(o.date), -1)) : o.date)}
                       style={{ top, height: h, left: `calc(${left}% + 3px)`, width: `calc(${w}% - 6px)` }}>
                    {o.item.repeat !== 'none' && <span className="rep"><Ic.repeat/></span>}
                    {o.seg === 'tail' && <span className="ovn" title="Continues from previous day"><Ic.chevD size={11} style={{ transform: 'rotate(180deg)' }}/></span>}
                    {o.seg === 'head' && <span className="ovn" title="Continues next day"><Ic.chevD size={11}/></span>}
                    <span className="et mono">{SS.minLabel(o.item.start)}–{SS.minLabel(o.item.end)}</span>
                    <span className="el">{labelOf(o.item, kind)}</span>
                  </div>
                );
              })}
              {drag && drag.dayISO === d && (() => {
                const lo = Math.min(drag.a, drag.b), hi = Math.max(drag.a, drag.b);
                return <div className="evt ghost tone-shift" style={{ top: lo/60*zoom, height: Math.max(16,(hi-lo)/60*zoom), left: 3, right: 3 }}>
                  <span className="et mono">{SS.minLabel(lo)}–{SS.minLabel(hi)}</span></div>;
              })()}
              {isToday && nowMin>0 && <div className="nowline" style={{ top: nowMin/60*zoom }}></div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function MonthGrid({ dayList, anchor, items, kind, todayISO, onDayClick, onEvtClick }) {
  const occ = expand(items, dayList).filter((o) => o.seg !== 'tail');
  const byDay = {}; dayList.forEach((d) => byDay[d] = []);
  occ.forEach((o) => byDay[o.date].push(o));
  const mon = anchor.getMonth();
  return (
    <div className="cal-scroll">
      <div className="monthgrid" style={{ gridTemplateRows: `auto repeat(${dayList.length / 7}, 1fr)` }}>
        {WD.map((w) => <div key={w} className="mg-dow">{w}</div>)}
        {dayList.map((d) => {
          const dt = SS.parseISO(d); const out = dt.getMonth() !== mon; const isToday = d === todayISO;
          const evs = byDay[d].sort((a,b)=> (b.item.allDay?1:0)-(a.item.allDay?1:0) || a.item.start-b.item.start);
          const shown = evs.slice(0, 3);
          return (
            <div key={d} className={`mg-cell ${out?'out':''} ${isToday?'today':''}`} onClick={(e) => onDayClick(d, e.currentTarget)}>
              <span className="mg-num">{dt.getDate()}</span>
              {shown.map((o) => (
                <div key={o.key} className={`mg-evt ${o.item.allDay?'allday':''} tone-${toneCls(o.item, kind)}`}
                     onClick={(e) => { e.stopPropagation(); onEvtClick(o.item, o.date); }}>
                  {!o.item.allDay && <span className="mono" style={{ fontSize: 10, opacity:.85 }}>{SS.minLabel(o.item.start)}</span>}
                  {labelOf(o.item, kind)}
                </div>
              ))}
              {evs.length > 3 && <span className="mg-more">+{evs.length - 3} more</span>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function toneCls(item, kind) {
  if (kind === 'availability') return item.type === 'pref' ? 'pref' : item.type === 'undes' ? 'undes' : 'vac';
  return 'shift';
}
function labelOf(item, kind) {
  if (kind === 'availability') return item.type === 'pref' ? 'Preferred' : item.type === 'undes' ? 'Undesired' : 'Vacation';
  return item.name || 'Shift';
}

function useClickAway(ref, onAway, active) {
  useEffect(() => {
    if (!active) return;
    function h(e) { if (ref.current && !ref.current.contains(e.target)) onAway(); }
    document.addEventListener('mousedown', h, true);
    return () => document.removeEventListener('mousedown', h, true);
  }, [active]);
}

const CAL_WD = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
function DateField({ value, onChange }) {
  const [open, setOpen] = useState(false);
  const wrap = useRef(null);
  useClickAway(wrap, () => setOpen(false), open);
  const base = value ? SS.parseISO(value) : new Date();
  const [vm, setVm] = useState(() => new Date(base.getFullYear(), base.getMonth(), 1));
  useEffect(() => { if (open && value) { const d = SS.parseISO(value); setVm(new Date(d.getFullYear(), d.getMonth(), 1)); } }, [open]);
  const todayISO = SS.isoOf(new Date());
  const gridStart = SS.startOfWeek(new Date(vm.getFullYear(), vm.getMonth(), 1));
  const weeks = Array.from({ length: 6 }, (_, w) => Array.from({ length: 7 }, (_, i) => SS.addDays(gridStart, w * 7 + i)));
  const mon = vm.getMonth();
  const step = (n) => setVm(new Date(vm.getFullYear(), vm.getMonth() + n, 1));
  const label = value ? SS.parseISO(value).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' }) : 'Pick a date';
  return (
    <div className="picker-wrap" ref={wrap}>
      <button type="button" className={`input picker-trigger ${open ? 'open' : ''}`} onClick={() => setOpen((o) => !o)}>
        <Ic.calendar size={14}/>
        <span className="pt-val">{label}</span>
        <Ic.chevD size={14}/>
      </button>
      {open && (
        <div className="picker-pop cal-pop">
          <div className="cp-nav">
            <button type="button" className="cp-arrow" onClick={() => step(-1)}><Ic.chevL size={14}/></button>
            <span className="cp-month">{vm.toLocaleDateString([], { month: 'long', year: 'numeric' })}</span>
            <button type="button" className="cp-arrow" onClick={() => step(1)}><Ic.chevR size={14}/></button>
          </div>
          <div className="cp-dow">{CAL_WD.map((w) => <span key={w}>{w}</span>)}</div>
          <div className="cp-grid day">
            {weeks.map((wk, wi) => (
              <div key={wi} className="cp-week">
                {wk.map((d) => {
                  const di = SS.isoOf(d), out = d.getMonth() !== mon;
                  const cls = `cp-day ${out ? 'out' : ''} ${di === todayISO ? 'today' : ''} ${di === value ? 'sel' : ''}`;
                  return <button type="button" key={di} className={cls} onClick={() => { onChange(di); setOpen(false); }}>{d.getDate()}</button>;
                })}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function parseTimeText(raw, isEnd) {
  let s = String(raw).trim().toLowerCase();
  if (!s) return null;
  if (s === 'midnight') return isEnd ? 1440 : 0;
  if (s === 'noon') return 720;
  let ap = null;
  if (/[ap]m?$/.test(s)) { ap = s.includes('p') ? 'p' : 'a'; s = s.replace(/\s*[ap]m?$/, ''); }
  s = s.replace(/[.\s]/g, ':');
  let h, mm;
  if (s.includes(':')) {
    const [hp, mp = '0'] = s.split(':');
    h = parseInt(hp, 10); mm = parseInt(mp, 10);
  } else if (/^\d{3,4}$/.test(s)) {
    h = parseInt(s.slice(0, s.length - 2), 10); mm = parseInt(s.slice(-2), 10);
  } else if (/^\d{1,2}$/.test(s)) {
    h = parseInt(s, 10); mm = 0;
  } else return null;
  if (isNaN(h) || isNaN(mm) || mm > 59) return null;
  if (ap === 'p' && h < 12) h += 12;
  if (ap === 'a' && h === 12) h = 0;
  let total = h * 60 + mm;
  if (total === 1440) return isEnd ? 1440 : 0;
  if (total < 0 || total > 1440) return null;
  return total;
}

function TimeField({ minutes, onChange, isEnd, align }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const wrap = useRef(null);
  const listRef = useRef(null);
  const inputRef = useRef(null);
  const opts = Array.from({ length: isEnd ? 97 : 96 }, (_, i) => i * 15);
  const optLabel = (m) => m >= 1440 ? 'Midnight' : SS.min12(m);
  const label = minutes >= 1440 ? 'Midnight' : SS.min12(minutes);
  const commit = (m) => { onChange(m); setOpen(false); if (inputRef.current) inputRef.current.blur(); };
  const tryCommitText = () => {
    const m = text.trim() ? parseTimeText(text, isEnd) : null;
    if (m != null) onChange(m);
    setOpen(false);
  };
  useClickAway(wrap, tryCommitText, open);
  const onFocus = () => { setText(SS.minLabel(minutes >= 1440 ? 1440 % 1440 : minutes)); setOpen(true); requestAnimationFrame(() => inputRef.current && inputRef.current.select()); };
  const onKey = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); tryCommitText(); inputRef.current && inputRef.current.blur(); }
    else if (e.key === 'Escape') { e.preventDefault(); setOpen(false); inputRef.current && inputRef.current.blur(); }
  };
  useEffect(() => {
    if (open && listRef.current) {
      const sel = listRef.current.querySelector('.tm-opt.sel');
      if (sel) listRef.current.scrollTop = sel.offsetTop - 64;
    }
  }, [open]);
  return (
    <div className="picker-wrap" ref={wrap} style={{ flex: '1 1 0', minWidth: 0 }}>
      <div className={`input picker-trigger compact ${open ? 'open' : ''}`} onMouseDown={(e) => { if (e.target !== inputRef.current) { e.preventDefault(); inputRef.current && inputRef.current.focus(); } }}>
        <Ic.clock size={13}/>
        <input
          ref={inputRef} className="tm-inline" type="text"
          value={open ? text : label}
          onFocus={onFocus}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKey}
        />
      </div>
      {open && (
        <div className={`picker-pop time-pop ${align === 'right' ? 'to-right' : ''}`} ref={listRef}>
          {opts.map((m) => (
            <button type="button" key={m} className={`tm-opt ${m === minutes ? 'sel' : ''}`} onMouseDown={(e) => { e.preventDefault(); commit(m); }}>{optLabel(m)}</button>
          ))}
        </div>
      )}
    </div>
  );
}

function Editor({ item, kind, palette, isNew, occDate, scopable, onPatch, onRemove, onClose, onDone, extraFields }) {
  const ref = useRef(null);
  const [scopeMenu, setScopeMenu] = useState(false);
  const [delMenu, setDelMenu] = useState(false);
  const [confirm, setConfirm] = useState(null); // { kind: 'save'|'delete', scope }
  useEffect(() => {
    function onKey(e) { if (e.key === 'Escape') { if (confirm) setConfirm(null); else onClose(); } }
    window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey);
  }, [confirm]);
  const overnight = !item.allDay && item.end < item.start;
  const nextDate = item.date ? SS.parseISO(item.date) : null;
  const nextLabel = nextDate ? SS.addDays(nextDate, 1).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' }) : '';
  const isVac = kind === 'availability' && item.type === 'vac';
  // Vacation spans a date range instead of recurring; everything else can repeat.
  const recurs = !isVac && item.repeat && item.repeat !== 'none';

  // Run an action, first asking for confirmation when it would alter the past.
  const run = (kind2, scope) => {
    setScopeMenu(false); setDelMenu(false);
    const fn = kind2 === 'save' ? onDone : onRemove;
    if (!isNew && touchesPast(scope, item, occDate)) setConfirm({ kind: kind2, scope });
    else fn(scope);
  };
  const toggleDay = (d) => {
    const cur = item.days && item.days.length ? item.days : [weekdayOf(item.date)];
    const next = cur.includes(d) ? cur.filter((x) => x !== d) : [...cur, d].sort((a, b) => a - b);
    onPatch({ days: next.length ? next : cur }); // keep at least one day selected
  };
  const selDays = recurs && item.repeat === 'weekly' ? (item.days && item.days.length ? item.days : [weekdayOf(item.date)]) : [];

  return (
    <>
      <div className="pop-backdrop" onClick={onClose}></div>
      <div className="pop" ref={ref} style={{ left: '50%', top: 96, transform: 'translateX(-50%)' }}>
        <h4>
          {isNew ? 'New ' : 'Edit '}{kind === 'availability' ? 'availability' : 'shift'}
          <span className="iconbtn" style={{ width: 26, height: 26, border: 0, background: 'transparent' }} onClick={onClose}><Ic.x size={15}/></span>
        </h4>

        {kind === 'availability' && (
          <div className="seg full">
            {palette.map((p) => (
              <button key={p.type} className={item.type === p.type ? 'on' : ''}
                onClick={() => onPatch({ type: p.type, allDay: p.type === 'vac', ...(p.type !== 'vac' ? { endDate: undefined } : {}) })}>{p.label}</button>
            ))}
          </div>
        )}

        {extraFields && extraFields(item, onPatch)}

        <div className="field">
          <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Ic.calendar size={13}/> {isVac ? 'Start date' : 'Date'}</label>
          <DateField value={item.date} onChange={(iso) => onPatch({ date: iso, ...(item.endDate && item.endDate < iso ? { endDate: undefined } : {}) })} />
        </div>

        {isVac && (
          <div className="field">
            <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Ic.calendar size={13}/> End date <span className="muted" style={{ fontWeight: 400 }}>· optional</span></label>
            {item.endDate ? (
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <div style={{ flex: 1, minWidth: 0 }}><DateField value={item.endDate} onChange={(iso) => onPatch({ endDate: iso >= item.date ? iso : item.date })} /></div>
                <button className="iconbtn" style={{ width: 30, height: 30, flex: '0 0 30px' }} title="Single day" onClick={() => onPatch({ endDate: undefined })}><Ic.x size={14}/></button>
              </div>
            ) : (
              <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => onPatch({ endDate: SS.isoOf(SS.addDays(SS.parseISO(item.date), 1)) })}><Ic.plus size={13}/> Add end date</button>
            )}
            <div className="hint">Leave empty for a single day, or set an end date to cover several days.</div>
          </div>
        )}

        {!item.allDay && !isVac && (
          <div className="field">
            <label>Time</label>
            <div className="timepair">
              <TimeField minutes={item.start} onChange={(m) => onPatch({ start: m })} />
              <span className="muted">→</span>
              <TimeField minutes={item.end} isEnd align="right" onChange={(m) => onPatch({ end: m === 0 ? 1440 : m })} />
              {overnight && <span className="chip accent ovn-pill" title={`Ends ${nextLabel}`}>+1d</span>}
            </div>
            {overnight && <div className="hint" style={{ color: 'var(--accent-strong)', display: 'flex', alignItems: 'center', gap: 5 }}><Ic.moon size={12}/> Overnight — ends {nextLabel}</div>}
          </div>
        )}

        {!isVac && (
          <div className="field">
            <label style={{ display:'flex', alignItems:'center', gap:6 }}><Ic.repeat size={13}/> Repeat</label>
            <div className="seg full">
              {[['none','Once'],['daily','Daily'],['weekly','Weekly']].map(([v,l]) => (
                <button key={v} className={(item.repeat || 'none') === v ? 'on' : ''}
                  onClick={() => onPatch({ repeat: v, days: v === 'weekly' ? (item.days && item.days.length ? item.days : [weekdayOf(item.date)]) : undefined })}>{l}</button>
              ))}
            </div>
            {item.repeat === 'weekly' && (
              <div className="daypick">
                {WD.map((w, d) => (
                  <button key={w} type="button" className={selDays.includes(d) ? 'on' : ''} onClick={() => toggleDay(d)}>{w[0]}</button>
                ))}
              </div>
            )}
            {item.repeat === 'weekly' && <div className="hint">Repeats every week on the selected days.</div>}
          </div>
        )}

        <div className="pop-actions">
          {scopable && recurs ? (
            <div className="splitbtn danger">
              <button className="sb-main" onClick={() => run('delete', 'all')}><Ic.trash size={13}/> Delete all</button>
              <button className="sb-caret" onClick={() => setDelMenu((o) => !o)}><Ic.chevD size={12}/></button>
              {delMenu && (
                <>
                  <div className="menu-backdrop" onClick={() => setDelMenu(false)}></div>
                  <div className="scope-menu" onClick={(e) => e.stopPropagation()}>
                    <div className="dm-head">Delete</div>
                    <button onClick={() => run('delete', 'this')}><span className="sm-t">This occurrence</span><span className="sm-s">Only {SS.parseISO(occDate || item.date).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })}</span></button>
                    <button onClick={() => run('delete', 'future')}><span className="sm-t">This &amp; following</span><span className="sm-s">{SS.parseISO(occDate || item.date).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })} onward</span></button>
                    <button onClick={() => run('delete', 'all')}><span className="sm-t">All occurrences</span></button>
                  </div>
                </>
              )}
            </div>
          ) : (
            <button className="btn danger sm" onClick={() => run('delete', 'all')}><Ic.trash size={14}/> Delete</button>
          )}
          {scopable && recurs ? (() => {
            const occLabel = SS.parseISO(occDate || item.date).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
            const everyWord = item.repeat === 'daily' ? 'day' : 'week';
            return (
              <div className="splitbtn primary">
                <button className="sb-main" onClick={() => run('save', 'all')}><Ic.check size={13}/> Save all</button>
                <button className="sb-caret" onClick={() => setScopeMenu((o) => !o)}><Ic.chevD size={12}/></button>
                {scopeMenu && (
                  <>
                    <div className="menu-backdrop" onClick={() => setScopeMenu(false)}></div>
                    <div className="scope-menu" onClick={(e) => e.stopPropagation()}>
                      <div className="dm-head">Apply changes to</div>
                      <button onClick={() => run('save', 'this')}><span className="sm-t">This occurrence</span><span className="sm-s">Only {occLabel}</span></button>
                      <button onClick={() => run('save', 'future')}><span className="sm-t">This &amp; following</span><span className="sm-s">{occLabel} onward</span></button>
                      <button onClick={() => run('save', 'all')}><span className="sm-t">All occurrences</span><span className="sm-s">Every {everyWord}</span></button>
                    </div>
                  </>
                )}
              </div>
            );
          })() : (
            <button className="btn primary sm" onClick={() => run('save', 'all')}><Ic.check size={14}/> Done</button>
          )}
        </div>
      </div>

      {confirm && (
        <>
          <div className="pop-backdrop" style={{ zIndex: 70 }} onClick={() => setConfirm(null)}></div>
          <div className="pop confirm-pop" style={{ left: '50%', top: 140, transform: 'translateX(-50%)', zIndex: 71 }}>
            <h4 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Ic.alert size={16}/> Affects the past</h4>
            <p className="confirm-msg">
              This {confirm.kind === 'delete' ? 'deletion' : 'change'} affects {confirm.scope === 'this' ? 'a date' : 'dates'} before today. Are you sure?
            </p>
            <div className="pop-actions">
              <button className="btn sm" onClick={() => setConfirm(null)}>Cancel</button>
              <button className={`btn sm ${confirm.kind === 'delete' ? 'danger' : 'primary'}`}
                onClick={() => { (confirm.kind === 'save' ? onDone : onRemove)(confirm.scope); setConfirm(null); }}>
                {confirm.kind === 'delete' ? 'Delete anyway' : 'Save anyway'}
              </button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
