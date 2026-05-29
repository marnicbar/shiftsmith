<script>
  import { createEventDispatcher } from 'svelte';
  import * as api from '../lib/api.js';

  export let shifts = [];

  const dispatch = createEventDispatcher();

  let editingShift = null;
  let form = emptyForm();

  function emptyForm() {
    const today = new Date().toISOString().slice(0, 10);
    return { date: today, startTime: '08:00', endTime: '16:00', location: 'Main Floor', requiredSkill: '' };
  }

  function startAdd() {
    editingShift = null;
    form = emptyForm();
  }

  function startEdit(shift) {
    editingShift = shift;
    const start = new Date(shift.start);
    const end   = new Date(shift.end);
    form = {
      date:          start.toISOString().slice(0, 10),
      startTime:     start.toTimeString().slice(0, 5),
      endTime:       end.toTimeString().slice(0, 5),
      location:      shift.location,
      requiredSkill: shift.requiredSkill,
    };
  }

  function buildPayload() {
    return {
      start:         `${form.date}T${form.startTime}:00`,
      end:           `${form.date}T${form.endTime}:00`,
      location:      form.location.trim(),
      requiredSkill: form.requiredSkill.trim(),
    };
  }

  async function save() {
    const payload = buildPayload();
    if (!payload.requiredSkill) return;
    try {
      if (editingShift) {
        await api.updateShift(editingShift.id, { id: editingShift.id, ...payload });
      } else {
        await api.addShift(payload);
      }
      editingShift = null;
      form = emptyForm();
      dispatch('reload');
    } catch (e) { alert(e.message); }
  }

  async function remove(id) {
    if (!confirm('Delete this shift?')) return;
    try {
      await api.removeShift(id);
      dispatch('reload');
    } catch (e) { alert(e.message); }
  }

  function fmtTime(isoStr) {
    return new Date(isoStr).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  function fmtDate(isoStr) {
    return new Date(isoStr).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  }
</script>

<div class="panel">
  <h2>Shifts</h2>

  <ul class="item-list">
    {#each shifts as shift (shift.id)}
      <li>
        <span class="shift-date">{fmtDate(shift.start)}</span>
        <span class="shift-time">{fmtTime(shift.start)}–{fmtTime(shift.end)}</span>
        <span class="badge skill">{shift.requiredSkill}</span>
        {#if shift.employee}
          <span class="employee assigned">{shift.employee.name}</span>
        {:else}
          <span class="employee unassigned">unassigned</span>
        {/if}
        <span class="actions">
          <button class="btn-icon" on:click={() => startEdit(shift)} title="Edit">✏️</button>
          <button class="btn-icon danger" on:click={() => remove(shift.id)} title="Delete">🗑️</button>
        </span>
      </li>
    {/each}
  </ul>

  <div class="editor">
    <h3>{editingShift ? 'Edit Shift' : 'Add Shift'}</h3>
    <div class="form-row">
      <label>
        Date
        <input type="date" bind:value={form.date} />
      </label>
      <label>
        Start
        <input type="time" bind:value={form.startTime} />
      </label>
      <label>
        End
        <input type="time" bind:value={form.endTime} />
      </label>
    </div>
    <label>
      Location
      <input type="text" bind:value={form.location} placeholder="e.g. Main Floor" />
    </label>
    <label>
      Required skill
      <input type="text" bind:value={form.requiredSkill} placeholder="e.g. Waiter" />
    </label>
    <div class="editor-actions">
      <button class="btn primary" on:click={save}>
        {editingShift ? 'Save Changes' : 'Add Shift'}
      </button>
      {#if editingShift}
        <button class="btn" on:click={startAdd}>Cancel</button>
      {/if}
    </div>
  </div>
</div>

<style>
  .panel { display: flex; flex-direction: column; gap: 1rem; }
  h2 { margin: 0 0 0.5rem; font-size: 1.1rem; font-weight: 600; }
  h3 { margin: 0 0 0.75rem; font-size: 0.95rem; font-weight: 600; color: #555; }

  .item-list {
    list-style: none; margin: 0; padding: 0;
    display: flex; flex-direction: column; gap: 0.4rem;
    max-height: 260px; overflow-y: auto;
  }
  li {
    display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap;
    padding: 0.4rem 0.5rem; background: #f8f9fa; border-radius: 6px;
    font-size: 0.8rem;
  }
  .shift-date { font-weight: 500; min-width: 90px; }
  .shift-time { color: #374151; min-width: 90px; }
  .badge {
    padding: 1px 6px; border-radius: 10px; font-size: 0.72rem;
  }
  .badge.skill { background: #dbeafe; color: #1e40af; }
  .employee { margin-left: auto; font-size: 0.8rem; }
  .assigned { color: #15803d; font-weight: 500; }
  .unassigned { color: #9ca3af; font-style: italic; }
  .actions { display: flex; gap: 0.25rem; }
  .btn-icon {
    background: none; border: none; cursor: pointer;
    padding: 2px 4px; font-size: 0.9rem; opacity: 0.7; border-radius: 4px;
  }
  .btn-icon:hover { opacity: 1; background: #e5e7eb; }
  .btn-icon.danger:hover { background: #fee2e2; }

  .editor {
    background: #f8f9fa; border: 1px solid #e5e7eb;
    border-radius: 8px; padding: 1rem;
  }
  .form-row { display: flex; gap: 0.5rem; }
  .form-row label { flex: 1; }
  label {
    display: flex; flex-direction: column; gap: 0.2rem;
    font-size: 0.8rem; font-weight: 500; color: #374151;
    margin-bottom: 0.5rem;
  }
  input {
    padding: 0.35rem 0.5rem; border: 1px solid #d1d5db;
    border-radius: 5px; font-size: 0.875rem; background: white;
  }
  .editor-actions { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
  .btn {
    padding: 0.4rem 1rem; border: 1px solid #d1d5db;
    border-radius: 6px; cursor: pointer; font-size: 0.875rem; background: white;
  }
  .btn.primary { background: #2563eb; color: white; border-color: #2563eb; }
  .btn.primary:hover { background: #1d4ed8; }
  .btn:hover { background: #f3f4f6; }
</style>
