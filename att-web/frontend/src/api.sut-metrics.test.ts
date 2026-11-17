// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('api.getJobSutMetricSeries', () => {
  it('uses the query-aware archived series route and forwards the selected query identity', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ state: 'READY', items: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.getJobSutMetricSeries('JOB-204', {
      offset: 0, limit: 1, queryId: 'query-cpu', targetId: 'sut-prod', profilePath: 'profile-platform/cpu',
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    expect(url.pathname.endsWith('/jobs/JOB-204/sut-metrics/series')).toBe(true);
    expect(Object.fromEntries(url.searchParams.entries())).toEqual({
      offset: '0', limit: '1', queryId: 'query-cpu', targetId: 'sut-prod', profilePath: 'profile-platform/cpu',
    });
  });

  it('uses the versioned Metric Source and Group catalog routes', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ items: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.listSutMetricAcquisitions();
    await api.listSutMetricGroups();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const urls = fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname);
    expect(urls[0]?.endsWith('/sut-metrics/metric-sources')).toBe(true);
    expect(urls[1]?.endsWith('/sut-metrics/groups')).toBe(true);
  });

  it('saves Metric Source and Group definitions through admin-only routes', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ id: 'saved' }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.saveSutMetricAcquisition({ id: 'host-metrics' });
    await api.saveSutMetricGroup({ id: 'host-servers' });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const routes = fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname);
    expect(routes[0]?.endsWith('/admin/sut-metrics/metric-sources')).toBe(true);
    expect(routes[1]?.endsWith('/admin/sut-metrics/groups')).toBe(true);
    expect(fetchMock.mock.calls.every(([, init]) => init?.method === 'POST')).toBe(true);
  });
});
