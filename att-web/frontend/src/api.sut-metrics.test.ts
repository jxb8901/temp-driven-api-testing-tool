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
});
