<script>
  import { onMount, onDestroy } from 'svelte';
  import * as api from './lib/api.js';
  import EmployeePanel from './components/EmployeePanel.svelte';
  import ShiftPanel from './components/ShiftPanel.svelte';
  import ScheduleGrid from './components/ScheduleGrid.svelte';

  let schedule = { employees: [], shifts: [], score: null, solverStatus: 'NOT_SOLVING' };
  let error = null;
  let pollInterval = null;

  async function loadSchedule() {
    try {
      schedule = await api.getSchedule();
      error = null;
    } catch (e) {
      error = e.message;
    }
  }

  async function handleSolve() {
    try {
      await api.startSolving();
      await loadSchedule();
    } catch (e) { alert(e.message); }
  }

  async function handleStop() {
    try {
      await api.stopSolving();
      await loadSchedule();
    } catch (e) { alert(e.message); }
  }

  $: isSolving = schedule.solverStatus === 'SOLVING_ACTIVE' || schedule.solverStatus === 'SOLVING_SCHEDULED';

  $: {
    clearInterval(pollInterval);
    if (isSolving) {
      pollInterval = setInterval(loadSchedule, 2000);
    }
  }

  onMount(loadSchedule);
  onDestroy(() => clearInterval(pollInterval));

  function scoreLabel(score) {
    if (!score) return null;
    const hard = score.hardScore ?? 0;
    const soft = score.softScore ?? 0;
    return `${hard} hard / ${soft} soft`;
  }
</script>

<div class="app">
  <header>
    <span class="logo">ShiftSmith</span>
    <span class="status-group">
      {#if isSolving}
        <span class="badge solving">Solving…</span>
      {:else if schedule.score}
        <span class="badge score" title="Hard / Soft score">{scoreLabel(schedule.score)}</span>
      {/if}
    </span>
    <span class="actions">
      {#if isSolving}
        <button class="btn danger" on:click={handleStop}>Stop</button>
      {:else}
        <button class="btn primary" on:click={handleSolve}>Solve</button>
      {/if}
    </span>
  </header>

  {#if error}
    <div class="error-bar">Could not reach backend: {error}</div>
  {/if}

  <main>
    <section class="side-panel">
      <EmployeePanel employees={schedule.employees} on:reload={loadSchedule} />
    </section>
    <section class="side-panel">
      <ShiftPanel shifts={schedule.shifts} on:reload={loadSchedule} />
    </section>
    <section class="grid-panel">
      <ScheduleGrid shifts={schedule.shifts} />
    </section>
  </main>
</div>

<style>
  :global(*, *::before, *::after) { box-sizing: border-box; }
  :global(body) { margin: 0; font-family: system-ui, sans-serif; background: #f1f5f9; color: #111827; }

  .app { min-height: 100vh; display: flex; flex-direction: column; }

  header {
    display: flex; align-items: center; gap: 1rem; padding: 0.75rem 1.5rem;
    background: #1e293b; color: white; position: sticky; top: 0; z-index: 10;
  }
  .logo { font-size: 1.2rem; font-weight: 700; letter-spacing: -0.5px; }
  .status-group { flex: 1; display: flex; align-items: center; gap: 0.5rem; }

  .badge {
    padding: 3px 10px; border-radius: 12px; font-size: 0.78rem; font-weight: 600;
  }
  .badge.solving { background: #fbbf24; color: #78350f; animation: pulse 1.5s ease-in-out infinite; }
  .badge.score   { background: #22c55e; color: #14532d; }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50%       { opacity: 0.6; }
  }

  .actions { display: flex; gap: 0.5rem; }
  .btn {
    padding: 0.4rem 1.1rem; border: none; border-radius: 6px;
    cursor: pointer; font-size: 0.875rem; font-weight: 500;
  }
  .btn.primary { background: #3b82f6; color: white; }
  .btn.primary:hover { background: #2563eb; }
  .btn.danger  { background: #ef4444; color: white; }
  .btn.danger:hover  { background: #dc2626; }

  .error-bar {
    background: #fee2e2; color: #991b1b; padding: 0.5rem 1.5rem;
    font-size: 0.875rem; text-align: center;
  }

  main {
    display: grid;
    grid-template-columns: 280px 320px 1fr;
    grid-template-areas: 'employees shifts grid';
    gap: 1rem; padding: 1rem 1.5rem; flex: 1; min-height: 0;
  }
  .side-panel { background: white; border-radius: 10px; padding: 1rem; overflow: hidden; }
  .grid-panel { background: white; border-radius: 10px; padding: 1rem; overflow: hidden; }

  section:nth-child(1) { grid-area: employees; }
  section:nth-child(2) { grid-area: shifts; }
  section:nth-child(3) { grid-area: grid; }

  @media (max-width: 900px) {
    main { grid-template-columns: 1fr; grid-template-areas: 'employees' 'shifts' 'grid'; }
  }
</style>
