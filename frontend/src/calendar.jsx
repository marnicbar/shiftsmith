// calendar.jsx — reusable calendar: day/week/month, drag-to-create, click-to-edit popover, recurrence.
import { useState, useRef, useEffect, useLayoutEffect, useCallback } from 'react';
import React from 'react';
import { useTranslation } from 'react-i18next';
import { SS } from './data.js';
import { dateLocale, is24h } from './i18n/index.js';
import { Ic } from './icons.jsx';

// Vertical zoom = pixels per hour. ZOOM_BASE is "100%"; ZOOM_MAX caps zoom-in.
// The zoom-out floor is computed per-render from the viewport so the day grid
// always at least fills the scroll area (see `minZoom` in Calendar) — zooming
// out further would leave the body shorter than the viewport and make the
// sticky day-header stretch to fill the gap.
const ZOOM_BASE = 46;
const ZOOM_MAX = 160;
const ZOOM_FLOOR = 18;

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

// --- overlap detection -----------------------------------------------------
// Two calendar entries may not occupy the same minute. Vacations (and any other
// all-day entry) are exempt: they span the whole day on purpose and are a
// special case, so they never count as an overlap.
function isExempt(it) { return it.allDay || it.type === 'vac'; }

const dayNum = (iso) => Math.round(SS.parseISO(iso).getTime() / SS.DAY);

// Concrete occupied time spans for an item across `dayList`, as absolute
// minutes from a fixed epoch so overnight entries (and their next-day tails)
// compare correctly. Returns [] for exempt/all-day items.
function occIntervals(item, dayList) {
  if (isExempt(item)) return [];
  const out = [];
  for (const d of dayList) {
    if (!occursOn(item, d)) continue;
    const base = dayNum(d) * 1440;
    const e = item.end > item.start ? item.end : item.end + 1440; // wrap overnight
    out.push([base + item.start, base + e]);
  }
  return out;
}

// True when two entries share any minute on any day they both occur. Recurrence
// repeats with a period of at most a week, so a 9-day window from the later
// anchor (one day of slack on each side for overnight spillover) is enough to
// decide it for any none/daily/weekly combination.
export function entriesOverlap(a, b) {
  if (isExempt(a) || isExempt(b)) return false;
  const start = SS.addDays(SS.parseISO(a.date > b.date ? a.date : b.date), -1);
  const days = Array.from({ length: 9 }, (_, i) => SS.isoOf(SS.addDays(start, i)));
  const ia = occIntervals(a, days), ib = occIntervals(b, days);
  return ia.some(([s1, e1]) => ib.some(([s2, e2]) => s1 < e2 && s2 < e1));
}

// True when applying {scope} to {item} would change or remove an occurrence before today.
function touchesPast(scope, item, occDate) {
  const today = SS.isoOf(new Date());
  if (scope === 'this' || scope === 'future') return (occDate || item.date) < today;
  return item.date < today; // 'all' — the series starts at its anchor date
}

// --- drag/resize helpers ---------------------------------------------------
const addDaysISO = (iso, n) => SS.isoOf(SS.addDays(SS.parseISO(iso), n));
const uniqDates = (arr) => arr.filter((v, i, a) => a.indexOf(v) === i);
// Shift a weekly day-of-week set (Mon=0…Sun=6) when a whole series moves by {dDay} days.
function shiftDays(days, dDay) {
  if (!days || !days.length || !dDay) return days;
  return days.map((d) => (((d + dDay) % 7) + 7) % 7).sort((a, b) => a - b);
}

// Translate a dropped occurrence (its new {geom}) into the concrete edit for the
// chosen scope, plus the resulting item list so overlaps can be re-checked.
// 'all'/non-recurring → a single replacement commit; 'this'/'future' → a split
// that truncates the original series and adds the moved single/series. Mirrors
// the editor's done() so a dragged edit and a typed edit behave identically.
export function buildMove(items, orig, geom, occDate, scope, idPfx) {
  const dDay = dayNum(geom.date) - dayNum(occDate);
  const recurring = orig.repeat && orig.repeat !== 'none';
  if (!recurring || scope === 'all') {
    const updated = { ...orig, start: geom.start, end: geom.end,
      date: addDaysISO(orig.date, dDay),
      until: orig.until ? addDaysISO(orig.until, dDay) : orig.until,
      endDate: orig.endDate ? addDaysISO(orig.endDate, dDay) : orig.endDate,
      days: shiftDays(orig.days, dDay) };
    return { kind: 'commit', changed: [updated], list: items.map((x) => x.id === orig.id ? updated : x) };
  }
  if (scope === 'this') {
    const restored = { ...orig, except: uniqDates([...(orig.except || []), occDate]) };
    const single = { ...orig, id: SS.uid(idPfx), repeat: 'none', date: geom.date, start: geom.start, end: geom.end };
    delete single.until; delete single.except; delete single.days; delete single.endDate;
    return { kind: 'split', restored, added: [single], list: items.map((x) => x.id === orig.id ? restored : x).concat(single) };
  }
  const restored = { ...orig, until: addDaysISO(occDate, -1) };
  const series = { ...orig, id: SS.uid(idPfx), date: geom.date, start: geom.start, end: geom.end,
    except: (orig.except || []).filter((x) => x >= occDate), days: shiftDays(orig.days, dDay) };
  delete series.until;
  return { kind: 'split', restored, added: [series], list: items.map((x) => x.id === orig.id ? restored : x).concat(series) };
}

// Does any entry changed/added by {drop} overlap another entry in the result?
export function moveClashes(drop) {
  const changed = drop.kind === 'commit' ? drop.changed : drop.added;
  return changed.some((c) => drop.list.some((o) => o.id !== c.id && entriesOverlap(c, o)));
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
  const { t } = useTranslation();
  const { view, anchor, items, kind, zoom = 46, onZoom, paint, palette,
          newItem, onCommit, onDelete, onSplit, extraFields, dayStart = 6,
          snap = 15, readOnly = false } = props;
  const scrollRef = useRef(null);
  const gridRef = useRef(null);
  const [editor, setEditor] = useState(null);
  const [draft, setDraft] = useState(null);   // local working copy; only persisted on confirm
  const [overlapErr, setOverlapErr] = useState(null);
  const [drag, setDrag] = useState(null);
  const dragRef = useRef(null);
  const idPfx = kind === 'availability' ? 'b' : 's';

  function openEditor(it, occDate) {
    setDraft(JSON.parse(JSON.stringify(it)));
    setEditor({ id: it.id, isNew: false, occDate: occDate || it.date });
  }

  const dayList = calendarDays(view, anchor);

  const todayISO = SS.isoOf(new Date());

  // --- vertical zoom (px/hour) ----------------------------------------------
  // `box` tracks the scroll viewport height and the header's height (the offset
  // of the first day column inside the grid). From those we derive `minZoom`,
  // the smallest px/hour at which 24h still fills the area below the header, so
  // the grid never shrinks past the viewport and stretches the header.
  const [box, setBox] = useState({ h: 0, head: 0 });
  const zoomScrollRef = useRef(null);   // desired scrollTop to apply after a zoom relayout

  const measure = useCallback(() => {
    const sc = scrollRef.current;
    if (!sc) return;
    const col = sc.querySelector('[data-daycol]');
    const h = sc.clientHeight, head = col ? col.offsetTop : 0;
    setBox((b) => (b.h === h && b.head === head ? b : { h, head }));
  }, []);

  // Re-measure on every render (catches the all-day row appearing/disappearing,
  // which changes the header height) and on container resize.
  useLayoutEffect(() => { if (view !== 'month') measure(); });
  useEffect(() => {
    const sc = scrollRef.current;
    if (!sc || view === 'month' || !window.ResizeObserver) return;
    const ro = new ResizeObserver(measure);
    ro.observe(sc);
    return () => ro.disconnect();
  }, [view, measure]);

  const minZoom = box.h && box.head
    ? Math.max(ZOOM_FLOOR, (box.h - box.head) / 24)
    : ZOOM_FLOOR;

  // Apply a new zoom while keeping the time under `anchorY` (px from the top of
  // the scroll viewport) pinned on screen. Stashes the matching scrollTop for
  // the [zoom] layout effect, since the grid height only updates after re-render.
  function applyZoom(rawTarget, anchorY) {
    const sc = scrollRef.current;
    if (!sc) return;
    const nz = Math.max(minZoom, Math.min(ZOOM_MAX, rawTarget));
    if (Math.abs(nz - zoom) < 0.01) return;
    const hour = Math.max(0, (sc.scrollTop + anchorY - box.head) / zoom);
    zoomScrollRef.current = Math.max(0, box.head + hour * nz - anchorY);
    onZoom(nz);
  }
  const viewCenterY = () => box.h / 2;

  // Frame business hours when the view changes (not on every zoom).
  useEffect(() => {
    if (scrollRef.current && view !== 'month') scrollRef.current.scrollTop = dayStart * zoom - 8;
  }, [view]);
  // Re-place scrollLeft/Top after a zoom so the anchored time stays put.
  useLayoutEffect(() => {
    if (zoomScrollRef.current == null) return;
    if (scrollRef.current) scrollRef.current.scrollTop = zoomScrollRef.current;
    zoomScrollRef.current = null;
  }, [zoom]);
  // Window/container grew: nudge zoom back up so the grid keeps filling the area.
  useEffect(() => {
    if (view !== 'month' && zoom < minZoom - 0.5) applyZoom(minZoom, viewCenterY());
  }, [minZoom, zoom, view]);

  // Ctrl/Cmd + wheel zooms the time axis, anchored on the cursor (native, non-
  // passive so we can preventDefault the browser's page zoom).
  useEffect(() => {
    const sc = scrollRef.current;
    if (!sc || view === 'month') return;
    function onWheel(e) {
      if (!(e.ctrlKey || e.metaKey)) return;
      e.preventDefault();
      const anchorY = e.clientY - sc.getBoundingClientRect().top;
      applyZoom(zoom - Math.sign(e.deltaY) * Math.max(1, zoom * 0.12), anchorY);
    }
    sc.addEventListener('wheel', onWheel, { passive: false });
    return () => sc.removeEventListener('wheel', onWheel);
  }, [view, zoom, minZoom, box.h, box.head]);

  const zoomPct = Math.round(zoom / ZOOM_BASE * 100);
  const zoomControls = {
    pct: zoomPct,
    canOut: zoom > minZoom + 0.5,
    canIn: zoom < ZOOM_MAX - 0.5,
    onOut: () => applyZoom(zoom * 0.8, viewCenterY()),
    onIn: () => applyZoom(zoom * 1.25, viewCenterY()),
    onReset: () => applyZoom(ZOOM_BASE, viewCenterY()),
  };

  const yToMin = (y) => Math.max(0, Math.min(1440, Math.round((y / zoom * 60) / snap) * snap));

  function startCreate(d) {
    setDraft(d);
    setEditor({ id: d.id, isNew: true, occDate: d.date });
  }
  function addNew() {
    const date = view === 'day' ? SS.isoOf(anchor) : SS.isoOf(SS.startOfWeek(anchor));
    startCreate(newItem({ date, start: 9 * 60, end: 17 * 60 }));
  }

  // Would a new entry spanning [lo,hi) on {dayISO} overlap an existing one?
  function createClash(tmpl, dayISO, lo, hi) {
    const cand = { ...tmpl, date: dayISO, start: lo, end: hi };
    return items.some((o) => o.id !== cand.id && entriesOverlap(cand, o));
  }

  function onColMouseDown(e, dayISO) {
    if (e.button !== 0) return;
    const colRect = e.currentTarget.getBoundingClientRect();
    const a = yToMin(e.clientY - colRect.top);
    const tmpl = newItem({ date: dayISO, start: 0, end: 0 }); // one representative for collision checks
    const st = { dayISO, a, b: a, colRect, invalid: false, tmpl };
    dragRef.current = st; setDrag(st);
    const move = (ev) => {
      const b = yToMin(ev.clientY - colRect.top);
      const lo = Math.min(dragRef.current.a, b), hi = Math.max(dragRef.current.a, b);
      const s = { ...dragRef.current, b, invalid: hi > lo && createClash(tmpl, dayISO, lo, hi) };
      dragRef.current = s; setDrag(s);
    };
    const up = (ev) => {
      window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up);
      const s = dragRef.current; dragRef.current = null; setDrag(null);
      let lo = Math.min(s.a, s.b), hi = Math.max(s.a, s.b);
      const wasDrag = hi - lo >= snap;
      if (hi - lo < snap) { hi = Math.min(1440, lo + 120); }
      // A real drag that lands on another entry is rejected, like a move/resize;
      // a click falls through to the editor, which validates on save.
      if (wasDrag && createClash(tmpl, dayISO, lo, hi)) return;
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

  function patch(p) { setOverlapErr(null); setDraft((d) => ({ ...d, ...p })); }
  function discard() { setDraft(null); setEditor(null); setOverlapErr(null); }

  // The entry as it will land for a given scope, so the overlap check sees the
  // real dates: "this" becomes a single occurrence, "future" a series from the
  // edited occurrence, "all" the draft as-is.
  function candidateFor(d, scope) {
    if (editor && !editor.isNew && d.repeat && d.repeat !== 'none' && (scope === 'this' || scope === 'future')) {
      const occDate = (editor && editor.occDate) || d.date;
      return scope === 'this' ? { ...d, repeat: 'none', date: occDate } : { ...d, date: occDate };
    }
    return d;
  }

  // Returns an error message if saving the draft (at `scope`) would overlap an
  // existing entry, else null. The entry being edited is excluded by id.
  function validate(scope) {
    if (!draft) return null;
    const cand = candidateFor(draft, scope);
    const clash = items.some((o) => o.id !== draft.id && entriesOverlap(cand, o));
    if (!clash) return null;
    return kind === 'availability' ? t('calendar.overlapAvail') : t('calendar.overlapShift');
  }

  function done(scope = 'all') {
    const d = draft;
    if (!d) { discard(); return; }
    const err = validate(scope);
    if (err) { setOverlapErr(err); return; }
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

  // --- drag to move / resize entries (Gmail-style) --------------------
  // A live gesture previews the dragged occurrence as a single ghost; on release
  // a non-recurring entry commits straight away, a recurring one opens the same
  // this/future/all scope chooser used on editor save. Either way the move is
  // rejected if it would overlap another entry.
  const [preview, setPreview] = useState(null); // { id, occDate, geom:{date,start,end}, invalid, asking, error }
  const previewRef = useRef(null);
  const setPv = (p) => { previewRef.current = p; setPreview(p); };

  function colAtX(clientX) {
    const els = gridRef.current ? gridRef.current.querySelectorAll('[data-daycol]') : [];
    for (const el of els) { const r = el.getBoundingClientRect(); if (clientX >= r.left && clientX < r.right) return { day: el.getAttribute('data-daycol'), rect: r }; }
    return null;
  }
  function colRectFor(dayISO) {
    const el = gridRef.current && gridRef.current.querySelector(`[data-daycol="${dayISO}"]`);
    return el ? el.getBoundingClientRect() : null;
  }
  function cellAtPoint(x, y) {
    const els = gridRef.current ? gridRef.current.querySelectorAll('[data-daycell]') : [];
    for (const el of els) { const r = el.getBoundingClientRect(); if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return el.getAttribute('data-daycell'); }
    return null;
  }
  const minAt = (clientY, rect) => Math.max(0, Math.min(1440, Math.round((clientY - rect.top) / zoom * 60 / snap) * snap));

  // The dragged occurrence as a standalone single entry (for collision tests + the ghost).
  function ghostSingle(orig, geom) {
    const dDay = dayNum(geom.date) - dayNum(orig.date);
    return { ...orig, repeat: 'none', date: geom.date, start: geom.start, end: geom.end,
             except: undefined, until: undefined, days: undefined,
             endDate: orig.endDate ? addDaysISO(orig.endDate, dDay) : undefined };
  }
  function clashGhost(orig, geom) {
    const g = ghostSingle(orig, geom);
    return items.some((o) => o.id !== orig.id && entriesOverlap(g, o));
  }

  function computeGeom(ev, mode, orig, occDate, baseRect, startMin, dur) {
    if (view === 'month' || orig.allDay) {
      const day = view === 'month' ? (cellAtPoint(ev.clientX, ev.clientY) || occDate)
                : (colAtX(ev.clientX)?.day || occDate);
      return { date: day, start: orig.start, end: orig.end };
    }
    const cur = baseRect ? minAt(ev.clientY, baseRect) : startMin;
    const delta = cur - startMin;
    if (mode === 'move') {
      const day = view === 'week' ? (colAtX(ev.clientX)?.day || occDate) : occDate;
      let s = orig.start + delta, e = s + dur;
      if (s < 0) { s = 0; e = dur; }
      if (e > 1440) { e = 1440; s = 1440 - dur; }
      return { date: day, start: s, end: e };
    }
    if (mode === 'n') return { date: occDate, start: Math.min(Math.max(0, orig.start + delta), orig.end - snap), end: orig.end };
    return { date: occDate, start: orig.start, end: Math.max(Math.min(1440, orig.end + delta), orig.start + snap) };
  }

  function onEvtDown(e, occ, mode) {
    if (e.button !== 0 || occ.item._preview) return;
    e.stopPropagation(); e.preventDefault();
    const orig = items.find((x) => x.id === occ.item.id);
    if (!orig) return;
    const occDate = occ.seg === 'tail' ? addDaysISO(occ.date, -1) : occ.date;
    const startX = e.clientX, startY = e.clientY;
    const baseRect = view === 'month' ? null : (colRectFor(occDate) || colAtX(startX)?.rect || null);
    const startMin = baseRect ? minAt(startY, baseRect) : 0;
    const dur = (orig.end <= orig.start ? orig.end + 1440 : orig.end) - orig.start;
    let active = false;
    const move = (ev) => {
      if (!active) {
        if (Math.abs(ev.clientX - startX) < 4 && Math.abs(ev.clientY - startY) < 4) return;
        active = true;
        document.body.classList.add(mode === 'move' ? 'cal-moving' : 'cal-resizing');
      }
      const geom = computeGeom(ev, mode, orig, occDate, baseRect, startMin, dur);
      setPv({ id: orig.id, occDate, geom, invalid: clashGhost(orig, geom) });
    };
    const up = () => {
      window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up);
      document.body.classList.remove('cal-moving', 'cal-resizing');
      if (!active) { setPv(null); openEditor(orig, occDate); return; } // a click, not a drag
      finalizeDrop();
    };
    window.addEventListener('mousemove', move); window.addEventListener('mouseup', up);
  }

  function finalizeDrop() {
    const p = previewRef.current;
    if (!p) return;
    const orig = items.find((x) => x.id === p.id);
    if (!orig || p.invalid) { setPv(null); return; } // unchanged target or a collision → revert
    if (orig.repeat && orig.repeat !== 'none') setPv({ ...p, asking: true });
    else { performDrop(buildMove(items, orig, p.geom, p.occDate, 'all', idPfx)); setPv(null); }
  }

  function performDrop(drop) {
    if (drop.kind === 'commit') drop.changed.forEach(onCommit);
    else onSplit(drop.restored, drop.added);
  }
  function chooseScope(scope) {
    const p = previewRef.current;
    if (!p) return;
    const orig = items.find((x) => x.id === p.id);
    if (!orig) { setPv(null); return; }
    const drop = buildMove(items, orig, p.geom, p.occDate, scope, idPfx);
    if (moveClashes(drop)) {
      setPv({ ...p, error: kind === 'availability'
        ? t('calendar.overlapAvailOtherDay')
        : t('calendar.overlapShiftOtherDay') });
      return;
    }
    performDrop(drop);
    setPv(null);
  }

  // While dragging (or choosing a scope), preview the move: hide the original
  // occurrence and show a single ghost at the new spot.
  function previewItems(p) {
    const orig = items.find((x) => x.id === p.id);
    if (!orig) return renderItems;
    const ghost = { ...ghostSingle(orig, p.geom), id: '__ghost', _preview: true, _invalid: p.invalid };
    const hidden = { ...orig, except: uniqDates([...(orig.except || []), p.occDate]) };
    return items.map((x) => x.id === orig.id ? hidden : x).concat(ghost);
  }
  // The in-progress create selection, as a preview entry so it lane-packs beside
  // existing entries (rather than drawing on top of them) — same as a move ghost.
  function createGhost(dr) {
    const lo = Math.min(dr.a, dr.b), hi = Math.max(dr.a, dr.b);
    return { ...dr.tmpl, id: '__ghost', date: dr.dayISO, start: lo, end: hi,
             repeat: 'none', allDay: false, _preview: true, _invalid: dr.invalid };
  }
  const liveItems = preview ? previewItems(preview)
                  : drag ? [...renderItems, createGhost(drag)]
                  : renderItems;

  return (
    <div className="cal" ref={gridRef}>
      <Toolbar {...props} onAdd={addNew} zoomControls={zoomControls} readOnly={readOnly} />
      {view === 'month'
        ? <MonthGrid dayList={dayList} anchor={anchor} items={liveItems} kind={kind} todayISO={todayISO}
            onDayClick={(d, el) => startCreate(newItem({ date: d, start: 9*60, end: 17*60 }))}
            onEvtDown={onEvtDown} readOnly={readOnly} />
        : <TimeGrid scrollRef={scrollRef} dayList={dayList} view={view} zoom={zoom} items={liveItems}
            kind={kind} todayISO={todayISO} onColMouseDown={onColMouseDown}
            onEvtDown={onEvtDown} readOnly={readOnly} />}
      {editing && !readOnly && (
        <Editor item={editing} kind={kind} palette={palette} isNew={editor.isNew}
          occDate={editor.occDate} scopable={!!onSplit && !editor.isNew}
          onPatch={patch} onRemove={remove} onClose={discard} onDone={done}
          onValidate={validate} error={overlapErr} extraFields={extraFields} />
      )}
      {preview && preview.asking && (
        <DropScope kind={kind} error={preview.error} onPick={chooseScope} onCancel={() => setPv(null)} />
      )}
    </div>
  );
}

// The exact day list a given view+anchor renders. Exported so read-only callers
// (e.g. the assignment schedules) can pre-expand concrete events for precisely the
// visible range and stay in lock-step with what the grid draws.
export function calendarDays(view, anchor) {
  if (view === 'week') return Array.from({ length: 7 }, (_, i) => SS.isoOf(SS.addDays(SS.startOfWeek(anchor), i)));
  if (view === 'day') return [SS.isoOf(anchor)];
  return monthDays(anchor);
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
  const { t } = useTranslation();
  const { view, onView, anchor, onAnchor, kind, paint, onPaint, onAdd, zoomControls, readOnly } = props;
  const monthLabel = anchor.toLocaleDateString(dateLocale(), { month: 'long', year: 'numeric' });
  let title = monthLabel, sub = '';
  if (view === 'week') {
    const ws = SS.startOfWeek(anchor), we = SS.addDays(ws, 6);
    title = `${ws.toLocaleDateString(dateLocale(), { month: 'short', day: 'numeric' })} – ${we.toLocaleDateString(dateLocale(), { month: 'short', day: 'numeric' })}`;
    sub = String(ws.getFullYear());
  } else if (view === 'day') {
    title = anchor.toLocaleDateString(dateLocale(), { weekday: 'long', month: 'short', day: 'numeric' });
  }
  const step = (dir) => {
    if (view === 'month') onAnchor(new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1));
    else onAnchor(SS.addDays(anchor, dir * (view === 'week' ? 7 : 1)));
  };
  return (
    <div className="cal-toolbar">
      <div className="nav">
        <button className="iconbtn" onClick={() => step(-1)}><Ic.chevL/></button>
        <button className="btn sm" onClick={() => onAnchor(new Date())}>{t('common.today')}</button>
        <button className="iconbtn" onClick={() => step(1)}><Ic.chevR/></button>
      </div>
      <div className="cal-title">{title} {sub && <span className="sub">{sub}</span>}</div>
      {view !== 'month' && zoomControls && (
        <div className="seg" style={{ marginRight: 6, flexShrink: 0 }}>
          <button onClick={zoomControls.onOut} disabled={!zoomControls.canOut} title={t('calendar.zoomOut')}><Ic.zoomOut size={14}/></button>
          <button className="mono zoom-pct" style={{ minWidth: 48 }} title={t('calendar.zoomReset')} onClick={zoomControls.onReset}>{zoomControls.pct}%</button>
          <button onClick={zoomControls.onIn} disabled={!zoomControls.canIn} title={t('calendar.zoomIn')}><Ic.zoomIn size={14}/></button>
        </div>
      )}
      <div className="seg" style={{ marginRight: 8, flexShrink: 0 }}>
        {['day','week','month'].map((v) => (
          <button key={v} className={view === v ? 'on' : ''} onClick={() => onView(v)}>{t(`calendar.view.${v}`)}</button>
        ))}
      </div>
      {!readOnly && (
        <button className="btn primary sm" style={{ flexShrink: 0 }} onClick={onAdd} title={kind === 'availability' ? t('calendar.addAvailability') : t('calendar.addShift')}>
          <Ic.plus size={14}/> {t('common.add')}
        </button>
      )}
    </div>
  );
}

function TimeGrid({ scrollRef, dayList, view, zoom, items, kind, todayISO, onColMouseDown, onEvtDown, readOnly }) {
  const { t } = useTranslation();
  const WD = t('common.weekdays3', { returnObjects: true });
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
        <div className="wg-corner" ref={headRef} style={{ gridRow: 1, gridColumn: 1, ...(hasAllDay ? { borderBottom: 'none' } : null) }}></div>
        {dayList.map((d, i) => {
          const dt = SS.parseISO(d); const isToday = d === todayISO;
          return (
            <div key={d} className={`wg-dayhead ${isToday ? 'today' : ''}`} style={{ gridRow: 1, gridColumn: i + 2, ...(hasAllDay ? { borderBottom: 'none' } : null) }}>
              <span className="dow">{WD[(dt.getDay()+6)%7]}</span>
              <span className="dnum">{dt.getDate()}</span>
            </div>
          );
        })}
        {hasAllDay && <>
          <div className="wg-timecol" style={{ gridRow: 2, gridColumn: 1, display:'grid', placeItems:'center', position:'sticky', top: Math.max(0, headH - 1), zIndex: 19, borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)' }}>
            <span className="wg-timelabel" style={{ transform:'none', paddingRight: 0 }}>{t('calendar.allDay')}</span>
          </div>
          {dayList.map((d, i) => (
            <div key={d} className="wg-col" style={{ gridRow: 2, gridColumn: i+2, position:'sticky', top: Math.max(0, headH - 1), zIndex: 18, background:'var(--surface)', borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)', padding: 4, display:'flex', flexDirection:'column', gap: 3, minHeight: 30 }}>
              {allDayByDay[d].map((o) => (
                <div key={o.key} className={`mg-evt allday tone-${toneCls(o.item, kind)} ${o.item._preview ? 'dragging' : ''} ${o.item._invalid ? 'invalid' : ''}`}
                     title={o.item._title || undefined}
                     onMouseDown={readOnly ? undefined : (e) => onEvtDown(e, o, 'move')} onClick={(e) => e.stopPropagation()} style={{ cursor: readOnly || o.item._preview ? 'default' : 'grab' }}>
                  {kind==='availability' ? <Ic.palm size={11}/> : null}{labelOf(o.item, kind, t)}
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
            <div key={d} data-daycol={d} className={`wg-col ${weekend?'weekend':''}`} style={{ gridRow: hasAllDay ? 3 : 2, gridColumn: i+2, position:'relative' }}
                 onMouseDown={readOnly ? undefined : (e) => onColMouseDown(e, d)}>
              {Array.from({ length: 24 }, (_, h) => (<React.Fragment key={h}>
                <div className="wg-hourline" style={{ top: h * zoom }}></div>
                <div className="wg-halfline" style={{ top: h * zoom + zoom/2 }}></div>
              </React.Fragment>))}
              {evs.map((o) => {
                const top = o.s/60*zoom, h = Math.max(16, (o.e - o.s)/60*zoom);
                const w = 100 / o._lanes, left = o._lane * w;
                const ghost = o.item._preview;
                const resizable = !readOnly && !ghost && o.seg === 'full' && !o.item.allDay;
                const segs = o.item._segments;
                return (
                  <div key={o.key} className={`evt tone-${toneCls(o.item, kind)} ${o.seg !== 'full' ? 'seg-'+o.seg : ''} ${ghost ? 'dragging' : ''} ${o.item._invalid ? 'invalid' : ''} ${segs ? 'has-strip' : ''}`}
                       title={o.item._title || undefined}
                       onMouseDown={readOnly || ghost ? undefined : (e) => onEvtDown(e, o, 'move')}
                       style={{ top, height: h, left: `calc(${left}% + 3px)`, width: `calc(${w}% - 6px)`, cursor: readOnly || ghost ? 'default' : 'grab', ...(!segs && o.item._color ? { borderLeftColor: o.item._color } : null) }}>
                    {segs && <div className="evt-strip">{segs.map((c, si) => <span key={si} style={{ background: c }} />)}</div>}
                    {resizable && <div className="evt-handle n" onMouseDown={(e) => onEvtDown(e, o, 'n')}></div>}
                    {o.item.repeat !== 'none' && <span className="rep"><Ic.repeat/></span>}
                    {o.seg === 'tail' && <span className="ovn" title={t('calendar.continuesPrev')}><Ic.chevD size={11} style={{ transform: 'rotate(180deg)' }}/></span>}
                    {o.seg === 'head' && <span className="ovn" title={t('calendar.continuesNext')}><Ic.chevD size={11}/></span>}
                    <span className="et mono">{o.item._timeLabel || `${SS.minLabel(o.item.start)}–${SS.minLabel(o.item.end)}`}</span>
                    {o.item._lines
                      ? <span className="evt-names">
                          {o.item._lines.map((n, ni) => <span key={ni} className="evt-name">{n}</span>)}
                          {o.item._openLabel && <span className="evt-open">{o.item._openLabel}</span>}
                        </span>
                      : <span className="el">{labelOf(o.item, kind, t)}</span>}
                    {resizable && <div className="evt-handle s" onMouseDown={(e) => onEvtDown(e, o, 's')}></div>}
                  </div>
                );
              })}
              {isToday && nowMin>0 && <div className="nowline" style={{ top: nowMin/60*zoom }}></div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function MonthGrid({ dayList, anchor, items, kind, todayISO, onDayClick, onEvtDown, readOnly }) {
  const { t } = useTranslation();
  const WD = t('common.weekdays3', { returnObjects: true });
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
            <div key={d} data-daycell={d} className={`mg-cell ${out?'out':''} ${isToday?'today':''}`}
                 onClick={readOnly ? undefined : (e) => onDayClick(d, e.currentTarget)} style={readOnly ? { cursor: 'default' } : null}>
              <span className="mg-num">{dt.getDate()}</span>
              {shown.map((o) => {
                const segs = o.item._segments;
                const multi = !!o.item._lines;
                return (
                <div key={o.key} className={`mg-evt ${o.item.allDay?'allday':''} tone-${toneCls(o.item, kind)} ${o.item._preview ? 'dragging' : ''} ${o.item._invalid ? 'invalid' : ''} ${multi ? 'multi' : ''} ${segs ? 'has-strip' : ''}`}
                     title={o.item._title || undefined}
                     onMouseDown={readOnly || o.item._preview ? undefined : (e) => onEvtDown(e, o, 'move')}
                     onClick={(e) => e.stopPropagation()} style={{ cursor: readOnly || o.item._preview ? 'default' : 'grab', ...(!segs && o.item._color ? { borderLeftColor: o.item._color } : null) }}>
                  {segs && <div className="evt-strip">{segs.map((c, si) => <span key={si} style={{ background: c }} />)}</div>}
                  {multi ? (
                    <>
                      <span className="mono mg-time">{o.item._timeLabel || `${SS.minLabel(o.item.start)}–${SS.minLabel(o.item.end)}`}</span>
                      {o.item._lines.map((n, ni) => <span key={ni} className="mg-name">{n}</span>)}
                      {o.item._openLabel && <span className="mg-open">{o.item._openLabel}</span>}
                    </>
                  ) : (
                    <>
                      {!o.item.allDay && <span className="mono" style={{ fontSize: 10, opacity:.85 }}>{SS.minLabel(o.item.start)}</span>}
                      {labelOf(o.item, kind, t)}
                    </>
                  )}
                </div>
              ); })}
              {evs.length > 3 && <span className="mg-more">{t('calendar.moreCount', { count: evs.length - 3 })}</span>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function toneCls(item, kind) {
  if (item._tone) return item._tone; // read-only events carry their own tone
  if (kind === 'availability') return item.type === 'pref' ? 'pref' : item.type === 'undes' ? 'undes' : 'vac';
  return 'shift';
}
function labelOf(item, kind, t) {
  if (item._label != null) return item._label; // read-only events carry their own label
  if (kind === 'availability') return item.type === 'pref' ? t('avail.pref') : item.type === 'undes' ? t('avail.undes') : t('avail.vac');
  return item.name || t('common.shift');
}

function useClickAway(ref, onAway, active) {
  useEffect(() => {
    if (!active) return;
    function h(e) { if (ref.current && !ref.current.contains(e.target)) onAway(); }
    document.addEventListener('mousedown', h, true);
    return () => document.removeEventListener('mousedown', h, true);
  }, [active]);
}

function DateField({ value, onChange }) {
  const { t } = useTranslation();
  const CAL_WD = t('common.weekdays2', { returnObjects: true });
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
  const label = value ? SS.parseISO(value).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' }) : t('calendar.pickDate');
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
            <span className="cp-month">{vm.toLocaleDateString(dateLocale(), { month: 'long', year: 'numeric' })}</span>
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
  const { t } = useTranslation();
  const h24 = is24h();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const wrap = useRef(null);
  const listRef = useRef(null);
  const inputRef = useRef(null);
  const opts = Array.from({ length: isEnd ? 97 : 96 }, (_, i) => i * 15);
  const fmtTime = (m) => (h24 ? SS.minLabel(m) : SS.min12(m));
  const optLabel = (m) => m >= 1440 ? t('calendar.midnight') : fmtTime(m);
  const label = minutes >= 1440 ? t('calendar.midnight') : fmtTime(minutes);
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

// Scope chooser shown after dropping a recurring entry — mirrors the editor's
// "apply to this / this & following / all" menu.
function DropScope({ kind, error, onPick, onCancel }) {
  const { t } = useTranslation();
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onCancel(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const noun = kind === 'availability' ? t('calendar.entryNounAvail') : t('calendar.entryNounShift');
  return (
    <>
      <div className="pop-backdrop" style={{ zIndex: 70 }} onClick={onCancel}></div>
      <div className="pop confirm-pop" style={{ left: '50%', top: 140, transform: 'translateX(-50%)', zIndex: 71 }}>
        <h4 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Ic.repeat size={15}/> {t('calendar.move', { noun })}</h4>
        {error
          ? <p className="confirm-msg" style={{ color: 'var(--rose-strong)', display: 'flex', alignItems: 'center', gap: 6 }}><Ic.alert size={14}/> {error}</p>
          : <p className="confirm-msg">{t('calendar.repeatingApply')}</p>}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <button className="btn sm" style={{ justifyContent: 'flex-start' }} onClick={() => onPick('this')}>{t('calendar.scope.this')}</button>
          <button className="btn sm" style={{ justifyContent: 'flex-start' }} onClick={() => onPick('future')}>{t('calendar.scope.future')}</button>
          <button className="btn sm" style={{ justifyContent: 'flex-start' }} onClick={() => onPick('all')}>{t('calendar.scope.all')}</button>
        </div>
        <div className="pop-actions">
          <button className="btn sm" onClick={onCancel}>{t('common.cancel')}</button>
        </div>
      </div>
    </>
  );
}

function Editor({ item, kind, palette, isNew, occDate, scopable, onPatch, onRemove, onClose, onDone, onValidate, error, extraFields }) {
  const { t } = useTranslation();
  const WD = t('common.weekdays3', { returnObjects: true });
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
  const nextLabel = nextDate ? SS.addDays(nextDate, 1).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric' }) : '';
  const isVac = kind === 'availability' && item.type === 'vac';
  // Vacation spans a date range instead of recurring; everything else can repeat.
  const recurs = !isVac && item.repeat && item.repeat !== 'none';

  // Run an action, first blocking overlapping saves, then asking for
  // confirmation when it would alter the past.
  const run = (kind2, scope) => {
    setScopeMenu(false); setDelMenu(false);
    if (kind2 === 'save' && onValidate && onValidate(scope)) { onDone(scope); return; } // surfaces the error, no commit
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
          {isNew
            ? (kind === 'availability' ? t('calendar.newAvailability') : t('calendar.newShift'))
            : (kind === 'availability' ? t('calendar.editAvailability') : t('calendar.editShift'))}
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
          <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Ic.calendar size={13}/> {isVac ? t('calendar.startDate') : t('calendar.date')}</label>
          <DateField value={item.date} onChange={(iso) => onPatch({ date: iso, ...(item.endDate && item.endDate < iso ? { endDate: undefined } : {}) })} />
        </div>

        {isVac && (
          <div className="field">
            <label title={t('calendar.endDateHint')} style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Ic.calendar size={13}/> {t('calendar.endDate')} <span className="muted" style={{ fontWeight: 400 }}>· {t('common.optional')}</span></label>
            {item.endDate ? (
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <div style={{ flex: 1, minWidth: 0 }}><DateField value={item.endDate} onChange={(iso) => onPatch({ endDate: iso >= item.date ? iso : item.date })} /></div>
                <button className="iconbtn" style={{ width: 30, height: 30, flex: '0 0 30px' }} title={t('calendar.singleDay')} onClick={() => onPatch({ endDate: undefined })}><Ic.x size={14}/></button>
              </div>
            ) : (
              <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => onPatch({ endDate: SS.isoOf(SS.addDays(SS.parseISO(item.date), 1)) })}><Ic.plus size={13}/> {t('calendar.addEndDate')}</button>
            )}
          </div>
        )}

        {!item.allDay && !isVac && (
          <div className="field">
            <label>{t('calendar.time')}</label>
            <div className="timepair">
              <TimeField minutes={item.start} onChange={(m) => onPatch({ start: m })} />
              <span className="muted">→</span>
              <TimeField minutes={item.end} isEnd align="right" onChange={(m) => onPatch({ end: m === 0 ? 1440 : m })} />
              {overnight && <span className="chip accent ovn-pill" title={t('calendar.endsOn', { date: nextLabel })}>{t('calendar.plusDay')}</span>}
            </div>
            {overnight && <div className="hint" style={{ color: 'var(--accent-strong)', display: 'flex', alignItems: 'center', gap: 5 }}><Ic.moon size={12}/> {t('calendar.overnight', { date: nextLabel })}</div>}
          </div>
        )}

        {!isVac && (
          <div className="field">
            <label title={t('calendar.weeklyHint')} style={{ display:'flex', alignItems:'center', gap:6 }}><Ic.repeat size={13}/> {t('calendar.repeat')}</label>
            <div className="seg full">
              {[['none', t('calendar.repeatOnce')], ['daily', t('calendar.repeatDaily')], ['weekly', t('calendar.repeatWeekly')]].map(([v,l]) => (
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
          </div>
        )}

        {error && (
          <div className="hint" role="alert" style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--rose-strong)' }}>
            <Ic.alert size={13}/> {error}
          </div>
        )}

        <div className="pop-actions">
          {scopable && recurs ? (
            <div className="splitbtn danger">
              <button className="sb-main" onClick={() => run('delete', 'all')}><Ic.trash size={13}/> {t('calendar.deleteAll')}</button>
              <button className="sb-caret" onClick={() => setDelMenu((o) => !o)}><Ic.chevD size={12}/></button>
              {delMenu && (
                <>
                  <div className="menu-backdrop" onClick={() => setDelMenu(false)}></div>
                  <div className="scope-menu" onClick={(e) => e.stopPropagation()}>
                    <div className="dm-head">{t('calendar.deleteHead')}</div>
                    <button onClick={() => run('delete', 'this')}><span className="sm-t">{t('calendar.scope.this')}</span><span className="sm-s">{t('calendar.onlyDate', { date: SS.parseISO(occDate || item.date).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric' }) })}</span></button>
                    <button onClick={() => run('delete', 'future')}><span className="sm-t">{t('calendar.scope.future')}</span><span className="sm-s">{t('calendar.dateOnward', { date: SS.parseISO(occDate || item.date).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric' }) })}</span></button>
                    <button onClick={() => run('delete', 'all')}><span className="sm-t">{t('calendar.scope.all')}</span></button>
                  </div>
                </>
              )}
            </div>
          ) : (
            <button className="btn danger sm" onClick={() => run('delete', 'all')}><Ic.trash size={14}/> {t('common.delete')}</button>
          )}
          {scopable && recurs ? (() => {
            const occLabel = SS.parseISO(occDate || item.date).toLocaleDateString(dateLocale(), { weekday: 'short', month: 'short', day: 'numeric' });
            const everyLabel = item.repeat === 'daily' ? t('calendar.everyDay') : t('calendar.everyWeek');
            return (
              <div className="splitbtn primary">
                <button className="sb-main" onClick={() => run('save', 'all')}><Ic.check size={13}/> {t('calendar.saveAll')}</button>
                <button className="sb-caret" onClick={() => setScopeMenu((o) => !o)}><Ic.chevD size={12}/></button>
                {scopeMenu && (
                  <>
                    <div className="menu-backdrop" onClick={() => setScopeMenu(false)}></div>
                    <div className="scope-menu" onClick={(e) => e.stopPropagation()}>
                      <div className="dm-head">{t('calendar.applyChangesTo')}</div>
                      <button onClick={() => run('save', 'this')}><span className="sm-t">{t('calendar.scope.this')}</span><span className="sm-s">{t('calendar.onlyDate', { date: occLabel })}</span></button>
                      <button onClick={() => run('save', 'future')}><span className="sm-t">{t('calendar.scope.future')}</span><span className="sm-s">{t('calendar.dateOnward', { date: occLabel })}</span></button>
                      <button onClick={() => run('save', 'all')}><span className="sm-t">{t('calendar.scope.all')}</span><span className="sm-s">{everyLabel}</span></button>
                    </div>
                  </>
                )}
              </div>
            );
          })() : (
            <button className="btn primary sm" onClick={() => run('save', 'all')}><Ic.check size={14}/> {t('common.done')}</button>
          )}
        </div>
      </div>

      {confirm && (
        <>
          <div className="pop-backdrop" style={{ zIndex: 70 }} onClick={() => setConfirm(null)}></div>
          <div className="pop confirm-pop" style={{ left: '50%', top: 140, transform: 'translateX(-50%)', zIndex: 71 }}>
            <h4 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Ic.alert size={16}/> {t('calendar.affectsPastTitle')}</h4>
            <p className="confirm-msg">
              {t('calendar.affectsPastBody', {
                action: confirm.kind === 'delete' ? t('calendar.actionDeletion') : t('calendar.actionChange'),
                when: confirm.scope === 'this' ? t('calendar.whenDate') : t('calendar.whenDates'),
              })}
            </p>
            <div className="pop-actions">
              <button className="btn sm" onClick={() => setConfirm(null)}>{t('common.cancel')}</button>
              <button className={`btn sm ${confirm.kind === 'delete' ? 'danger' : 'primary'}`}
                onClick={() => { (confirm.kind === 'save' ? onDone : onRemove)(confirm.scope); setConfirm(null); }}>
                {confirm.kind === 'delete' ? t('calendar.deleteAnyway') : t('calendar.saveAnyway')}
              </button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
