// Tests for the single backend integration point (lib/api.js). fetch and
// EventSource are stubbed so we can assert request shape, error handling and the
// SSE subscription contract without a running server.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as api from './api.js';

function mockFetch(response) {
  const fn = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fn);
  return fn;
}

const ok = (body, status = 200) => ({ ok: true, status, json: async () => body });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

describe('getSchedule', () => {
  it('GETs /api/schedule and returns the parsed body', async () => {
    const fetchFn = mockFetch(ok({ total: 3 }));
    const result = await api.getSchedule();
    expect(fetchFn).toHaveBeenCalledWith('/api/schedule', { headers: {} });
    expect(result).toEqual({ total: 3 });
  });

  it('attaches the bearer token when one is stored', async () => {
    api.setToken('tok123', true);
    const fetchFn = mockFetch(ok({ total: 1 }));
    await api.getSchedule();
    const [, options] = fetchFn.mock.calls[0];
    expect(options.headers.Authorization).toBe('Bearer tok123');
  });
});

describe('auth', () => {
  it('login stores the token and reports success', async () => {
    const fetchFn = mockFetch(ok({ token: 'abc', username: 'admin' }));
    const res = await api.login('admin', 'shiftsmith', true);
    expect(res).toEqual({ ok: true, username: 'admin' });
    expect(api.getToken()).toBe('abc');
    const [url, options] = fetchFn.mock.calls[0];
    expect(url).toBe('/api/auth/login');
    expect(JSON.parse(options.body)).toEqual({ username: 'admin', password: 'shiftsmith', remember: true });
  });

  it('login returns { ok: false } on 401 without storing a token', async () => {
    mockFetch({ ok: false, status: 401, json: async () => ({}) });
    const res = await api.login('admin', 'wrong', false);
    expect(res).toEqual({ ok: false });
    expect(api.getToken()).toBeNull();
  });

  it('remember=false stores the token in sessionStorage only', () => {
    api.setToken('s-tok', false);
    expect(sessionStorage.getItem('shiftsmith.token')).toBe('s-tok');
    expect(localStorage.getItem('shiftsmith.token')).toBeNull();
  });

  it('a 401 clears the token and fires the unauthorized handler', async () => {
    api.setToken('stale', true);
    const onUnauth = vi.fn();
    api.setUnauthorizedHandler(onUnauth);
    mockFetch({ ok: false, status: 401, json: async () => ({}) });
    await expect(api.getSchedule()).rejects.toThrow(/401/);
    expect(onUnauth).toHaveBeenCalled();
    expect(api.getToken()).toBeNull();
    api.setUnauthorizedHandler(null);
  });
});

describe('putProblem', () => {
  it('PUTs JSON to /api/problem and returns null on 204', async () => {
    const fetchFn = mockFetch({ ok: true, status: 204, json: async () => { throw new Error('no body'); } });
    const problem = { employees: [], positions: [] };
    const result = await api.putProblem(problem);

    expect(result).toBeNull();
    const [url, options] = fetchFn.mock.calls[0];
    expect(url).toBe('/api/problem');
    expect(options.method).toBe('PUT');
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(options.body)).toEqual(problem);
  });
});

describe('solver lifecycle', () => {
  it('startSolving POSTs and stopSolving DELETEs /api/solve', async () => {
    const fetchFn = mockFetch({ ok: true, status: 204, json: async () => null });
    await api.startSolving();
    await api.stopSolving();
    expect(fetchFn.mock.calls[0][1].method).toBe('POST');
    expect(fetchFn.mock.calls[1][1].method).toBe('DELETE');
  });
});

describe('error handling', () => {
  it('throws when the response is not ok', async () => {
    mockFetch({ ok: false, status: 500, json: async () => ({}) });
    await expect(api.getSchedule()).rejects.toThrow(/500/);
  });
});

describe('subscribeSchedule (SSE)', () => {
  let instances;

  beforeEach(() => {
    instances = [];
    class FakeEventSource {
      constructor(url) {
        this.url = url;
        this.onmessage = null;
        this.onerror = null;
        this.close = vi.fn();
        instances.push(this);
      }
    }
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  it('parses incoming frames and forwards them to onUpdate', () => {
    const onUpdate = vi.fn();
    api.subscribeSchedule(onUpdate);
    const es = instances[0];
    expect(es.url).toBe('/api/stream');

    es.onmessage({ data: JSON.stringify({ total: 5 }) });
    expect(onUpdate).toHaveBeenCalledWith({ total: 5 });
  });

  it('ignores malformed frames without throwing', () => {
    const onUpdate = vi.fn();
    api.subscribeSchedule(onUpdate);
    expect(() => instances[0].onmessage({ data: 'not json' })).not.toThrow();
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('wires the error handler and returns an unsubscribe that closes the stream', () => {
    const onError = vi.fn();
    const unsubscribe = api.subscribeSchedule(vi.fn(), onError);
    const es = instances[0];
    expect(es.onerror).toBe(onError);

    unsubscribe();
    expect(es.close).toHaveBeenCalled();
  });
});
