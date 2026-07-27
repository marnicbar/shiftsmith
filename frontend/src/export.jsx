// export.jsx — the "Export PDF" control for the read-only Plan calendars.
//
// A toolbar button that opens a small popover: printed time range (day/week only —
// a month page has no time axis), paper size and orientation. The backend builds and
// renders the document (see dev.shiftsmith.export.CalendarDocumentBuilder); this only
// says *what* to export, then hands the resulting Blob to the browser as a download.
//
// While the popover is open it asks the backend what the export would contain, so a
// narrow time range warns about the shifts it would leave off the page instead of
// dropping them silently.
import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Ic } from './icons.jsx';
import { TimeField } from './calendar.jsx';
import { SS } from './data.js';
import { exportCalendarPdf, getExportPlan } from './lib/api.js';

export const PAPERS = ['a4', 'a3', 'us-letter'];
export const ORIENTATIONS = ['landscape', 'portrait'];

// A week or month page needs the width for its seven columns; a single day has one,
// which on a landscape sheet is absurdly wide. Mirrors ExportRequest.defaultOrientation.
export const defaultOrientation = (view) => (view === 'day' ? 'portrait' : 'landscape');

// Chosen to fit an ordinary working day on one page; the user can widen them, and
// anything left outside is called out rather than dropped quietly.
const DEFAULT_BAND = { start: 6 * 60, end: 22 * 60 };

function download(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Give the browser a tick to start the download before revoking the handle.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * @param {string[]} scopes  one `person:<id>` / `position:<id>` per page of the PDF
 * @param {string} view      'day' | 'week' | 'month'
 * @param {Date} anchor      the date the calendar is showing
 */
export function ExportButton({ scopes = [], view, anchor, nameOrder, disabled }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const [range, setRange] = useState(DEFAULT_BAND);
  const [paper, setPaper] = useState('a4');
  // Null until the user picks one, so the per-view default (see `defaultOrientation`)
  // keeps following the view instead of freezing at whatever the first view wanted.
  const [orientation, setOrientation] = useState(null);
  const [plan, setPlan] = useState(null);
  const btnRef = useRef(null);
  const [at, setAt] = useState(null);

  const timed = view !== 'month';
  const params = {
    scopes,
    view,
    anchor: anchor ? SS.isoOf(anchor) : undefined,
    from: timed ? range.start : 0,
    to: timed ? range.end : 1440,
    lang: i18n.language,
    nameOrder,
  };

  // Anchor the popover under the button (it is `position: fixed`, like the editor's).
  useEffect(() => {
    if (!open || !btnRef.current) return;
    const r = btnRef.current.getBoundingClientRect();
    setAt({ top: r.bottom + 6, left: Math.max(8, Math.min(r.right - 290, window.innerWidth - 298)) });
  }, [open]);

  // Ask what this export would contain whenever the shape of it changes. Serialising
  // the params keeps the effect from re-firing on every render over an equal object.
  const key = open ? JSON.stringify(params) : null;
  useEffect(() => {
    if (!key) { setPlan(null); return undefined; }
    let alive = true;
    getExportPlan(JSON.parse(key))
      .then((p) => { if (alive) setPlan(p); })
      .catch(() => { if (alive) setPlan(null); }); // a failed preflight only costs the warning
    return () => { alive = false; };
  }, [key]);

  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      const { blob, filename } = await exportCalendarPdf({ ...params, paper, orientation: effOrientation });
      download(blob, filename);
      setOpen(false);
    } catch (e) {
      setErr(e.serverMessage || e.message || t('plan.export.failed'));
    } finally {
      setBusy(false);
    }
  };

  const dropped = plan ? plan.droppedTotal : 0;
  const effOrientation = orientation ?? defaultOrientation(view);

  return (
    <>
      <button ref={btnRef} className="btn sm" style={{ flexShrink: 0 }} disabled={disabled || !scopes.length}
        title={t('plan.export.title')} onClick={() => setOpen((v) => !v)}>
        <Ic.download size={14}/> {t('plan.export.button')}
      </button>
      {open && (
        <>
          <div className="pop-backdrop" onClick={() => setOpen(false)}></div>
          <div className="pop" style={at || { top: -9999, left: -9999 }}>
            <h4>{t('plan.export.title')}
              <button className="iconbtn" onClick={() => setOpen(false)}><Ic.x size={14}/></button>
            </h4>

            {/* A month page has no time axis, so there is nothing to set — and saying
                so would only be noise. The field is simply absent. */}
            {timed && (
              <div className="field">
                <label>{t('plan.export.timeRange')}</label>
                <div className="timepair">
                  <TimeField minutes={range.start}
                    onChange={(m) => setRange((r) => ({ ...r, start: Math.min(m, r.end - 60) }))} />
                  <span className="muted">→</span>
                  <TimeField minutes={range.end} isEnd align="right"
                    onChange={(m) => setRange((r) => ({ ...r, end: Math.max(m === 0 ? 1440 : m, r.start + 60) }))} />
                </div>
                {dropped > 0 ? (
                  <div className="hint" style={{ color: 'var(--amber-strong)', display: 'flex', gap: 5 }}>
                    <Ic.warning2 size={12}/>
                    <span>{t('plan.export.droppedWarning', { count: dropped })}</span>
                  </div>
                ) : (
                  <div className="hint">{t('plan.export.timeRangeHint')}</div>
                )}
              </div>
            )}

            <div className="field">
              <label>{t('plan.export.paper')}</label>
              <div className="seg full">
                {PAPERS.map((p) => (
                  <button key={p} className={paper === p ? 'on' : ''} onClick={() => setPaper(p)}>
                    {t(`plan.export.paperName.${p}`)}
                  </button>
                ))}
              </div>
            </div>

            <div className="field">
              <label>{t('plan.export.orientation')}</label>
              <div className="seg full">
                {ORIENTATIONS.map((o) => (
                  <button key={o} className={effOrientation === o ? 'on' : ''} onClick={() => setOrientation(o)}>
                    {t(`plan.export.orientationName.${o}`)}
                  </button>
                ))}
              </div>
            </div>

            {err && <div className="hint" style={{ color: 'var(--rose-solid)' }}>{err}</div>}

            <div className="pop-actions">
              <span className="muted" style={{ fontSize: 11.5 }}>
                {plan ? t('plan.export.pages', { count: plan.sections.length }) : ''}
              </span>
              <button className="btn primary sm" disabled={busy} onClick={run}>
                {busy ? t('plan.export.rendering') : t('plan.export.download')}
              </button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
