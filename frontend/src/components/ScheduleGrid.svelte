<script>
  export let shifts = [];

  // Group shifts by date (YYYY-MM-DD), sorted chronologically.
  $: shiftsByDay = (() => {
    const map = {};
    for (const s of shifts) {
      const day = s.start.slice(0, 10);
      if (!map[day]) map[day] = [];
      map[day].push(s);
    }
    for (const day of Object.keys(map)) {
      map[day].sort((a, b) => a.start.localeCompare(b.start));
    }
    return map;
  })();

  $: days = Object.keys(shiftsByDay).sort();

  function fmtDayHeader(dateStr) {
    return new Date(dateStr + 'T12:00:00').toLocaleDateString([], {
      weekday: 'short', month: 'short', day: 'numeric'
    });
  }

  function fmtTime(isoStr) {
    return new Date(isoStr).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  }
</script>

<div class="grid-wrapper">
  <h2>Schedule</h2>
  {#if days.length === 0}
    <p class="empty">No shifts defined yet.</p>
  {:else}
    <div class="grid">
      {#each days as day}
        <div class="day-col">
          <div class="day-header">{fmtDayHeader(day)}</div>
          {#each shiftsByDay[day] as shift (shift.id)}
            <div class="shift-card {shift.employee ? 'assigned' : 'unassigned'}">
              <div class="shift-time">{fmtTime(shift.start)} – {fmtTime(shift.end)}</div>
              <div class="shift-meta">
                <span class="skill-badge">{shift.requiredSkill}</span>
                <span class="location">{shift.location}</span>
              </div>
              {#if shift.employee}
                <div class="employee-chip">{shift.employee.name}</div>
              {:else}
                <div class="employee-chip empty-chip">unassigned</div>
              {/if}
            </div>
          {/each}
        </div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .grid-wrapper { min-width: 0; }
  h2 { margin: 0 0 0.75rem; font-size: 1.1rem; font-weight: 600; }
  .empty { color: #9ca3af; font-style: italic; font-size: 0.875rem; }

  .grid {
    display: flex; gap: 0.75rem;
    overflow-x: auto; padding-bottom: 0.5rem;
  }

  .day-col { min-width: 150px; display: flex; flex-direction: column; gap: 0.5rem; }

  .day-header {
    font-size: 0.78rem; font-weight: 600; text-transform: uppercase;
    color: #6b7280; padding: 0.3rem 0; border-bottom: 2px solid #e5e7eb;
    text-align: center;
  }

  .shift-card {
    border-radius: 8px; padding: 0.6rem 0.7rem;
    display: flex; flex-direction: column; gap: 0.3rem;
    border: 1px solid transparent;
  }
  .shift-card.assigned   { background: #f0fdf4; border-color: #bbf7d0; }
  .shift-card.unassigned { background: #fff7ed; border-color: #fed7aa; }

  .shift-time { font-size: 0.8rem; font-weight: 600; color: #111827; }

  .shift-meta { display: flex; align-items: center; gap: 0.3rem; flex-wrap: wrap; }

  .skill-badge {
    background: #dbeafe; color: #1e40af;
    padding: 1px 5px; border-radius: 8px; font-size: 0.7rem;
  }

  .location { font-size: 0.7rem; color: #9ca3af; }

  .employee-chip {
    font-size: 0.78rem; font-weight: 500;
    padding: 2px 8px; border-radius: 10px; width: fit-content;
    background: #dcfce7; color: #166534;
  }
  .employee-chip.empty-chip { background: #fef3c7; color: #92400e; font-style: italic; }
</style>
