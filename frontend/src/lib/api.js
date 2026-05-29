const BASE = '/api/schedule';
const json = body => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

async function request(url, options = {}) {
  const res = await fetch(url, options);
  if (!res.ok) throw new Error(`${options.method ?? 'GET'} ${url} failed: ${res.status}`);
  if (res.status === 204) return null;
  return res.json();
}

export const getSchedule    = ()          => request(BASE);
export const startSolving   = ()          => request(`${BASE}/solve`, { method: 'POST' });
export const stopSolving    = ()          => request(`${BASE}/solve`, { method: 'DELETE' });
export const addEmployee    = (e)         => request(`${BASE}/employees`, { method: 'POST', ...json(e) });
export const updateEmployee = (name, e)   => request(`${BASE}/employees/${encodeURIComponent(name)}`, { method: 'PUT', ...json(e) });
export const removeEmployee = (name)      => request(`${BASE}/employees/${encodeURIComponent(name)}`, { method: 'DELETE' });
export const addShift       = (s)         => request(`${BASE}/shifts`, { method: 'POST', ...json(s) });
export const updateShift    = (id, s)     => request(`${BASE}/shifts/${id}`, { method: 'PUT', ...json(s) });
export const removeShift    = (id)        => request(`${BASE}/shifts/${id}`, { method: 'DELETE' });
