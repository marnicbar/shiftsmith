// positions.jsx — Position/job planner: positions rail + shift calendar + config pane.
import { useState as useStatePos } from 'react';
import { SS } from './data.js';
import { Ic } from './icons.jsx';
import { UI } from './ui.jsx';
import { Theme } from './theme.js';
import { Calendar } from './calendar.jsx';

export function Positions({ employees = [], positions, setPositions, groupOrder, setGroupOrder, skills, selId, setSelId, snap, newFlow }) {
  const [q, setQ] = useStatePos('');
  const [view, setView] = useStatePos('week');
  const [anchor, setAnchor] = useStatePos(new Date());
  const [zoom, setZoom] = useStatePos(46);
  const [collapsed, setCollapsed] = useStatePos({});
  const [menuOpen, setMenuOpen] = useStatePos(false);
  const [drag, setDrag] = useStatePos(null);
  const [mark, setMark] = useStatePos(null);

  const pos = positions.find((p) => p.id === selId) || positions[0];
  const list = positions.filter((p) => p.name.toLowerCase().includes(q.toLowerCase()));
  const dnd = !q;

  const UNGROUPED = '__ungrouped';
  const allGroups = [...groupOrder];
  positions.forEach((p) => { if (p.group && !allGroups.includes(p.group)) allGroups.push(p.group); });
  const order = [...allGroups, UNGROUPED];
  const byGroup = {};
  list.forEach((p) => { const g = p.group && allGroups.includes(p.group) ? p.group : UNGROUPED; (byGroup[g] = byGroup[g] || []).push(p); });
  const toggleGroup = (g) => setCollapsed({ ...collapsed, [g]: !collapsed[g] });
  const reflow = (arr, ord = allGroups) => setPositions(SS.reflowPositions(arr, ord));

  function createGroup() {
    const name = (prompt('New group name') || '').trim();
    if (!name || allGroups.includes(name)) return;
    setGroupOrder([...groupOrder, name]);
    setCollapsed({ ...collapsed, [name]: false });
  }
  function setPosGroup(id, g) {
    if (g === '__new') { const name = (prompt('New group name')||'').trim(); if (!name) return; if (!allGroups.includes(name)) setGroupOrder([...groupOrder, name]); g = name; }
    reflow(positions.map((p) => p.id === id ? { ...p, group: g || undefined } : p));
  }
  function clearDnd() { setDrag(null); setMark(null); }

  function applyDrop() {
    if (!drag || !mark) { clearDnd(); return; }
    if (drag.kind === 'pos') {
      const moved = positions.find((p) => p.id === drag.id);
      const rest = positions.filter((p) => p.id !== drag.id);
      if (mark.type === 'pos' && mark.id !== drag.id) {
        const target = positions.find((p) => p.id === mark.id);
        const ti = rest.findIndex((p) => p.id === mark.id);
        rest.splice(mark.edge === 'bottom' ? ti + 1 : ti, 0, { ...moved, group: target.group });
        reflow(rest);
      } else if (mark.type === 'into') {
        rest.push({ ...moved, group: mark.g === UNGROUPED ? undefined : mark.g });
        reflow(rest);
      }
    } else if (drag.kind === 'group' && mark.type === 'group' && mark.g !== drag.id && mark.g !== UNGROUPED) {
      const ord = groupOrder.filter((g) => g !== drag.id);
      const ti = ord.indexOf(mark.g);
      ord.splice(mark.edge === 'bottom' ? ti + 1 : ti, 0, drag.id);
      setGroupOrder(ord);
      setPositions(SS.reflowPositions(positions, ord));
    }
    clearDnd();
  }

  function updatePos(patch) { setPositions(positions.map((p) => p.id === pos.id ? { ...p, ...patch } : p)); }
  function commitShift(s) {
    const exists = pos.shifts.some((x) => x.id === s.id);
    updatePos({ shifts: exists ? pos.shifts.map((x) => x.id === s.id ? s : x) : [...pos.shifts, s] });
  }
  function deleteShift(id) { updatePos({ shifts: pos.shifts.filter((x) => x.id !== id) }); }
  function splitShift(updated, added) { updatePos({ shifts: pos.shifts.map((x) => x.id === updated.id ? updated : x).concat(added) }); }
  const newItem = ({ date, start, end }) => ({ id: SS.uid('s'), name: 'New Shift', date, start, end, skills: pos.skills.length ? pos.skills.slice() : [skills[0]], headcount: 1, repeat: 'none', preferred: [] });

  const weeklySlots = pos ? pos.shifts.reduce((a, s) => a + s.headcount * (s.repeat === 'daily' ? 7 : 1), 0) : 0;

  const extraFields = (item, patch) => {
    const prefIds = item.preferred || [];
    const reqSkills = SS.shiftSkills(item);
    const eligibleFor = (e) => reqSkills.every((sk) => e.skills.includes(sk));
    const chosen = prefIds.map((id) => employees.find((e) => e.id === id)).filter(Boolean);
    const avail = employees.filter((e) => eligibleFor(e) && !prefIds.includes(e.id));
    const atMax = prefIds.length >= item.headcount;
    return (
    <>
      <div className="field">
        <label>Shift name</label>
        <input className="input" value={item.name} onChange={(e) => patch({ name: e.target.value })} autoFocus={item.name === 'New Shift'}/>
      </div>
      <div className="field">
        <label>Required skills</label>
        <div className="hint">Only people with every selected skill can fill this shift.</div>
        <UI.SkillEditor value={reqSkills} all={skills}
          onChange={(s) => patch({ skills: s, preferred: prefIds.filter((id) => { const e = employees.find((x) => x.id === id); return e && s.every((sk) => e.skills.includes(sk)); }) })}/>
      </div>
      <div className="field">
        <label>Staff</label>
        <UI.Stepper value={item.headcount} min={1} onChange={(v) => patch({ headcount: v, preferred: prefIds.slice(0, v) })}/>
      </div>
      <div className="field">
        <label>Preferred employees</label>
        <div className="hint">Assigned first when available — at most {item.headcount} (the staff count).</div>
        <div className="pref-list">
          {chosen.map((e) => (
            <div key={e.id} className="pref-emp">
              <span className="avatar sq" style={{ width: 22, height: 22, flexBasis: 22, fontSize: 9.5, background: Theme.avatarColor(e.name) }}>{e.name.split(' ').map((x) => x[0]).slice(0,2).join('')}</span>
              <span className="pe-name">{e.name}</span>
              <button className="pe-x" title="Remove" onClick={() => patch({ preferred: prefIds.filter((x) => x !== e.id) })}><Ic.x/></button>
            </div>
          ))}
          {!chosen.length && <div className="pref-empty">No preference set — anyone eligible can fill this.</div>}
        </div>
        {atMax ? (
          <div className="hint" style={{ marginTop: 7 }}>Limit reached. Raise the staff count to prefer more people.</div>
        ) : avail.length ? (
          <select className="input" style={{ marginTop: 7 }} value=""
            onChange={(e) => { if (e.target.value) patch({ preferred: [...prefIds, e.target.value] }); }}>
            <option value="">+ Add preferred employee…</option>
            {avail.map((e) => <option key={e.id} value={e.id}>{e.name}{e.skills.length ? ` · ${e.skills.join(', ')}` : ''}</option>)}
          </select>
        ) : (
          <div className="hint" style={{ marginTop: 7 }}>No {chosen.length ? 'other ' : ''}employees match the required skills.</div>
        )}
      </div>
    </>
    );
  };

  const addPosition = () => {
    const p = { id: SS.uid('p'), name: 'New Position', color: Math.floor(Math.random()*360), skills: [], shifts: [] };
    setPositions([...positions, p]); setSelId(p.id);
  };

  return (
    <div className="view">
      <div className="rail">
        <div className="rail-head">
          <div className="row">
            <span className="section-title">Positions <span className="muted">· {positions.length}</span></span>
            <div className="splitbtn rail-split">
              <button className="sb-main" onClick={addPosition} title="Add position"><Ic.plus size={16}/></button>
              <button className={`sb-caret ${menuOpen ? 'on' : ''}`} onClick={() => setMenuOpen((o) => !o)} title="More"><Ic.chevD size={13}/></button>
              {menuOpen && (
                <>
                  <div className="menu-backdrop" onClick={() => setMenuOpen(false)}></div>
                  <div className="mini-menu">
                    <button onClick={() => { setMenuOpen(false); addPosition(); }}><Ic.plus size={15}/> New position</button>
                    <button onClick={() => { setMenuOpen(false); createGroup(); }}><Ic.folderPlus size={15}/> New group</button>
                  </div>
                </>
              )}
            </div>
          </div>
          <div className="search"><Ic.search/><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search positions"/></div>
        </div>
        <div className="rail-list" onDragEnd={clearDnd} onDrop={applyDrop} onDragOver={(e) => { if (drag) e.preventDefault(); }}>
          {order.map((g) => {
            const items = byGroup[g] || [];
            if (g === UNGROUPED && !items.length) return null;
            if (q && !items.length) return null;
            const isReal = g !== UNGROUPED;
            const isCollapsed = !q && collapsed[g];
            const slotsWk = items.reduce((a, p) => a + p.shifts.reduce((b, s) => b + s.headcount * (s.repeat === 'daily' ? 7 : 1), 0), 0);
            const greorder = drag && drag.kind === 'group' && mark && mark.type === 'group' && mark.g === g ? mark.edge : null;
            const groupOver = (e) => {
              if (!drag) return; e.preventDefault();
              if (drag.kind === 'pos') { setMark({ type: 'into', g }); }
            };
            return (
              <div key={g}
                className={`rail-group ${drag && drag.kind === 'pos' && mark && mark.type === 'into' && mark.g === g ? 'drop' : ''} ${greorder ? 'greorder-' + greorder : ''}`}
                onDragOver={groupOver} onDrop={(e) => { e.preventDefault(); e.stopPropagation(); applyDrop(); }}>
                {isReal ? (
                  <div className={`rail-group-head ${dnd ? 'draggable' : ''}`}
                    draggable={dnd}
                    onClick={() => toggleGroup(g)}
                    onDragStart={(e) => { e.stopPropagation(); setDrag({ kind: 'group', id: g }); e.dataTransfer.effectAllowed = 'move'; }}
                    onDragOver={(e) => {
                      if (!drag) return; e.preventDefault(); e.stopPropagation();
                      if (drag.kind === 'group') { const r = e.currentTarget.getBoundingClientRect(); setMark({ type: 'group', g, edge: (e.clientY - r.top) < r.height / 2 ? 'top' : 'bottom' }); }
                      else { setMark({ type: 'into', g }); }
                    }}>
                    {dnd && <span className="rgh-grip"><Ic.move size={12}/></span>}
                    <Ic.chevD size={13} style={{ transform: isCollapsed ? 'rotate(-90deg)' : 'none', transition: 'transform .12s' }}/>
                    <span className="rgh-name">{g}</span>
                    <span className="muted" style={{ fontSize: 11 }}>{items.length} · {slotsWk}/wk</span>
                  </div>
                ) : (allGroups.length > 0 && <div className="rail-group-head plain"><span className="rgh-name">Ungrouped</span></div>)}
                {!isCollapsed && items.map((p) => {
                  const slots = p.shifts.reduce((a, s) => a + s.headcount * (s.repeat === 'daily' ? 7 : 1), 0);
                  const m = drag && drag.kind === 'pos' && mark && mark.type === 'pos' && mark.id === p.id ? mark.edge : null;
                  return (
                    <div key={p.id} draggable={dnd}
                      className={`rail-item ${p.id === pos.id ? 'sel' : ''} ${drag && drag.kind === 'pos' && drag.id === p.id ? 'dragging' : ''} ${m ? 'mark-' + m : ''}`}
                      onClick={() => setSelId(p.id)}
                      onDragStart={(e) => { e.stopPropagation(); setDrag({ kind: 'pos', id: p.id }); e.dataTransfer.effectAllowed = 'move'; }}
                      onDragOver={(e) => {
                        if (!drag || drag.kind !== 'pos') return;
                        e.preventDefault(); e.stopPropagation();
                        const r = e.currentTarget.getBoundingClientRect();
                        setMark({ type: 'pos', id: p.id, edge: (e.clientY - r.top) < r.height / 2 ? 'top' : 'bottom' });
                      }}
                      onDrop={(e) => { e.preventDefault(); e.stopPropagation(); applyDrop(); }}>
                      {dnd && <div className="ri-grip" title="Drag to reorder"><Ic.move size={13}/></div>}
                      <div className="avatar sq" style={{ background: `oklch(0.62 0.13 ${p.color})` }}><Ic.briefcase size={16}/></div>
                      <div className="ri-meta">
                        <div className="ri-name">{p.name}</div>
                        <div className="ri-sub">{p.shifts.length} shifts · {slots} slots/wk</div>
                      </div>
                    </div>
                  );
                })}
                {isReal && !isCollapsed && !items.length && (
                  <div className="rail-group-empty">Drag positions here</div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {!pos ? (
        <div className="empty-state">
          <div className="inner">
            <Ic.briefcase/>
            <div style={{ fontSize: 15, fontWeight: 600 }}>No positions yet</div>
            <div className="muted">Add your first position to start defining shifts.</div>
            <button className="btn" onClick={addPosition}><Ic.plus size={15}/> Add position</button>
          </div>
        </div>
      ) : (
      <>
      <Calendar kind="shift" view={view} onView={setView} anchor={anchor} onAnchor={setAnchor}
        zoom={zoom} onZoom={setZoom} palette={[]} items={pos.shifts} snap={snap} newFlow={newFlow}
        newItem={newItem} onCommit={commitShift} onDelete={deleteShift} onSplit={splitShift} extraFields={extraFields} />

      <div className="config">
        <div className="pad">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div className="avatar lg sq" style={{ background: `oklch(0.62 0.13 ${pos.color})` }}><Ic.briefcase size={20}/></div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.02em' }}>{pos.name}</div>
              <div className="muted" style={{ fontSize: 12.5 }}>{pos.shifts.length} shift types · {weeklySlots} slots/wk</div>
            </div>
          </div>

          <div className="field">
            <label>Position name</label>
            <input className="input" value={pos.name} onChange={(e) => updatePos({ name: e.target.value })}/>
          </div>

          <div className="field">
            <label>Group</label>
            <div className="hint">Cluster related positions in the sidebar — e.g. all kitchen roles.</div>
            <select className="input" value={pos.group || ''}
              onChange={(e) => setPosGroup(pos.id, e.target.value)}>
              <option value="">No group</option>
              {allGroups.map((g) => <option key={g} value={g}>{g}</option>)}
              <option value="__new">+ New group…</option>
            </select>
          </div>

          <div className="field">
            <label>Default required skills</label>
            <div className="hint">Suggested when you create a new shift here.</div>
            <UI.SkillEditor value={pos.skills} all={skills} onChange={(s) => updatePos({ skills: s })}/>
          </div>

          <div className="divider"></div>
          <button className="btn danger" style={{ justifyContent: 'center' }}
            onClick={() => { if (confirm(`Delete ${pos.name}?`)) { const rest = positions.filter((x) => x.id !== pos.id); setPositions(rest); setSelId(rest[0]?.id); } }}>
            <Ic.trash size={15}/> Delete position
          </button>
        </div>
      </div>
      </>
      )}
    </div>
  );
}
