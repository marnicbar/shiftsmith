<script>
  import { createEventDispatcher } from 'svelte';
  import * as api from '../lib/api.js';

  export let employees = [];

  const dispatch = createEventDispatcher();

  let editingEmployee = null; // null = add mode, object = edit mode
  let form = emptyForm();

  function emptyForm() {
    return { name: '', skills: '', unavailableDates: '', undesiredDates: '', desiredDates: '' };
  }

  function startAdd() {
    editingEmployee = null;
    form = emptyForm();
  }

  function startEdit(emp) {
    editingEmployee = emp;
    form = {
      name: emp.name,
      skills: [...emp.skills].join(', '),
      unavailableDates: [...(emp.unavailableDates ?? [])].join(', '),
      undesiredDates:   [...(emp.undesiredDates ?? [])].join(', '),
      desiredDates:     [...(emp.desiredDates ?? [])].join(', '),
    };
  }

  function buildPayload() {
    const splitDates = s => s.split(',').map(d => d.trim()).filter(Boolean);
    return {
      name: form.name.trim(),
      skills: form.skills.split(',').map(s => s.trim()).filter(Boolean),
      unavailableDates: splitDates(form.unavailableDates),
      undesiredDates:   splitDates(form.undesiredDates),
      desiredDates:     splitDates(form.desiredDates),
    };
  }

  async function save() {
    const payload = buildPayload();
    if (!payload.name) return;
    try {
      if (editingEmployee) {
        await api.updateEmployee(editingEmployee.name, payload);
      } else {
        await api.addEmployee(payload);
      }
      editingEmployee = null;
      form = emptyForm();
      dispatch('reload');
    } catch (e) { alert(e.message); }
  }

  async function remove(name) {
    if (!confirm(`Delete employee "${name}"?`)) return;
    try {
      await api.removeEmployee(name);
      dispatch('reload');
    } catch (e) { alert(e.message); }
  }
</script>

<div class="panel">
  <h2>Employees</h2>

  <ul class="item-list">
    {#each employees as emp (emp.name)}
      <li>
        <span class="item-name">{emp.name}</span>
        <span class="skills">
          {#each emp.skills as skill}
            <span class="badge">{skill}</span>
          {/each}
        </span>
        <span class="actions">
          <button class="btn-icon" on:click={() => startEdit(emp)} title="Edit">✏️</button>
          <button class="btn-icon danger" on:click={() => remove(emp.name)} title="Delete">🗑️</button>
        </span>
      </li>
    {/each}
  </ul>

  <div class="editor">
    <h3>{editingEmployee ? 'Edit Employee' : 'Add Employee'}</h3>
    <label>
      Name
      <input type="text" bind:value={form.name} placeholder="e.g. Alice" readonly={!!editingEmployee} />
    </label>
    <label>
      Skills <small>(comma-separated)</small>
      <input type="text" bind:value={form.skills} placeholder="e.g. Bartender, Waiter" />
    </label>
    <label>
      Unavailable dates <small>(YYYY-MM-DD, comma-separated)</small>
      <input type="text" bind:value={form.unavailableDates} placeholder="e.g. 2025-01-04" />
    </label>
    <label>
      Undesired dates <small>(YYYY-MM-DD, comma-separated)</small>
      <input type="text" bind:value={form.undesiredDates} placeholder="e.g. 2025-01-05" />
    </label>
    <label>
      Desired dates <small>(YYYY-MM-DD, comma-separated)</small>
      <input type="text" bind:value={form.desiredDates} placeholder="e.g. 2025-01-06" />
    </label>
    <div class="editor-actions">
      <button class="btn primary" on:click={save}>
        {editingEmployee ? 'Save Changes' : 'Add Employee'}
      </button>
      {#if editingEmployee}
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
    display: flex; align-items: center; gap: 0.5rem;
    padding: 0.4rem 0.5rem; background: #f8f9fa; border-radius: 6px;
    font-size: 0.875rem;
  }
  .item-name { font-weight: 500; min-width: 70px; }
  .skills { display: flex; flex-wrap: wrap; gap: 0.25rem; flex: 1; }
  .badge {
    background: #dbeafe; color: #1e40af;
    padding: 1px 6px; border-radius: 10px; font-size: 0.75rem;
  }
  .actions { display: flex; gap: 0.25rem; margin-left: auto; }
  .btn-icon {
    background: none; border: none; cursor: pointer;
    padding: 2px 4px; font-size: 0.9rem; opacity: 0.7;
    border-radius: 4px;
  }
  .btn-icon:hover { opacity: 1; background: #e5e7eb; }
  .btn-icon.danger:hover { background: #fee2e2; }

  .editor {
    background: #f8f9fa; border: 1px solid #e5e7eb;
    border-radius: 8px; padding: 1rem;
  }
  label {
    display: flex; flex-direction: column; gap: 0.2rem;
    font-size: 0.8rem; font-weight: 500; color: #374151;
    margin-bottom: 0.5rem;
  }
  label small { font-weight: 400; color: #9ca3af; }
  input[type="text"] {
    padding: 0.35rem 0.5rem; border: 1px solid #d1d5db;
    border-radius: 5px; font-size: 0.875rem;
    background: white;
  }
  input[readonly] { background: #e5e7eb; color: #6b7280; }
  .editor-actions { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
  .btn {
    padding: 0.4rem 1rem; border: 1px solid #d1d5db;
    border-radius: 6px; cursor: pointer; font-size: 0.875rem;
    background: white;
  }
  .btn.primary { background: #2563eb; color: white; border-color: #2563eb; }
  .btn.primary:hover { background: #1d4ed8; }
  .btn:hover { background: #f3f4f6; }
</style>
