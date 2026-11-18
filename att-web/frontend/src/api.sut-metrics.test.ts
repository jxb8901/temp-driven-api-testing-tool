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

  it('loads SUT Metrics from the selected Package and Environment', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
      metricSources: [], groups: [], profiles: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    await api.getPackageSutMetricsCatalog('payments', 'UAT');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [input] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    const url = new URL(String(input));
    expect(url.pathname.endsWith('/packages/payments/sut-metrics/catalog')).toBe(true);
    expect(url.searchParams.get('environment')).toBe('UAT');
  });

  it('loads one Package SUT Metrics resource by inventory kind and id', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      packageId: 'payments', kind: 'source', id: 'db-health', path: 'load/sut-metrics/sources/db-health.yaml',
      resourceDigest: 'a'.repeat(64), source: 'schemaVersion: att-sut-metrics-metric-source/v1\n', canEdit: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    await api.getPackageSutMetricsResource('payments', 'source', 'db-health');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [input] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    const url = new URL(String(input));
    expect(url.pathname.endsWith('/packages/payments/sut-metrics/resource')).toBe(true);
    expect(Object.fromEntries(url.searchParams.entries())).toEqual({ kind: 'source', id: 'db-health' });
  });

  it('creates a Package SUT Metrics draft with the pinned resource digest and selected Environment', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      draftId: 'D123', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
      expectedResourceDigest: 'a'.repeat(64), state: 'draft',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const input = { resourcePath: 'load/sut-metrics/sources/db-health.yaml', environment: 'UAT',
      source: 'schemaVersion: att-sut-metrics-metric-source/v1\n', expectedResourceDigest: 'a'.repeat(64) };

    await api.createPackageSutMetricsDraft('payments', input);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [requestUrl, init] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    expect(new URL(String(requestUrl)).pathname.endsWith('/packages/payments/sut-metrics/drafts')).toBe(true);
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual(input);
  });

  it('includes the candidate Package manifest and its expected digest when staging a new resource', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      draftId: 'D124', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
      expectedResourceDigest: '0'.repeat(64), expectedManifestDigest: 'a'.repeat(64),
      candidateManifestDigest: 'b'.repeat(64), activationMode: 'resource-then-manifest', state: 'draft',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const input = { resourcePath: 'load/sut-metrics/sources/new-health.yaml', environment: 'UAT',
      source: 'schemaVersion: att-sut-metrics-metric-source/v1\n', expectedResourceDigest: '0'.repeat(64),
      candidateManifestSource: 'schemaVersion: att-package/v2\n', expectedManifestDigest: 'a'.repeat(64) };

    await api.createPackageSutMetricsDraft('payments', input);

    const [, init] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual(input);
  });

  it('posts Package SUT Metrics draft lifecycle actions with empty action bodies', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      draftId: 'D123', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
      expectedResourceDigest: 'a'.repeat(64), state: 'reviewed', published: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    for (const action of ['validate', 'review', 'publish', 'discard'] as const)
      await api.packageSutMetricsDraftAction('payments', 'D123', action);

    expect(fetchMock).toHaveBeenCalledTimes(4);
    for (const [index, action] of (['validate', 'review', 'publish', 'discard'] as const).entries()) {
      const [requestUrl, init] = fetchMock.mock.calls[index] as unknown as [URL, RequestInit];
      expect(new URL(String(requestUrl)).pathname.endsWith(`/packages/payments/sut-metrics/drafts/D123/${action}`)).toBe(true);
      expect(init.method).toBe('POST');
      expect(init.body).toBe('{}');
    }
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
