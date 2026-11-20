// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('source history API', () => {
  it('uses no-store Package and central history and diff routes', async () => {
    const response = { schemaVersion: 'att-source-history/v1', items: [], offset: 0, limit: 20, total: 0, hasMore: false,
      currentSourceRevision: 'c'.repeat(64) };
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify(response), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.getSourceHistory('PACKAGE_SOURCE', 'payments', 20, 20);
    await api.getSourceHistory('CENTRAL_RUNTIME_CONFIG', 'central-runtime-config', 0, 20);
    await api.getSourceHistoryDiff('PACKAGE_SOURCE', 'payments', 'a'.repeat(64), 'b'.repeat(64), 'templates/payments/a.yaml');
    await api.getSourceHistoryDiff('CENTRAL_RUNTIME_CONFIG', 'central-runtime-config', 'c'.repeat(64), 'd'.repeat(64), undefined, undefined, 20, 20);

    const urls = fetchMock.mock.calls.map(([input]) => new URL(String(input)));
    expect(urls[0]?.pathname.endsWith('/packages/payments/source-history')).toBe(true);
    expect(Object.fromEntries(urls[0]!.searchParams.entries())).toEqual({ offset: '20', limit: '20' });
    expect(urls[1]?.pathname.endsWith('/configuration/source-history')).toBe(true);
    expect(urls[2]?.pathname.endsWith('/packages/payments/source-history/diff')).toBe(true);
    expect(Object.fromEntries(urls[2]!.searchParams.entries())).toEqual({
      beforeSnapshotId: 'a'.repeat(64), afterSnapshotId: 'b'.repeat(64), path: 'templates/payments/a.yaml', offset: '0', limit: '20',
    });
    expect(urls[3]?.pathname.endsWith('/configuration/source-history/diff')).toBe(true);
    expect(Object.fromEntries(urls[3]!.searchParams.entries())).toEqual({
      beforeSnapshotId: 'c'.repeat(64), afterSnapshotId: 'd'.repeat(64), offset: '20', limit: '20',
    });
    expect(fetchMock.mock.calls.every(([, init]) => init?.cache === 'no-store')).toBe(true);
  });

  it('stages restore with scope-specific revision preconditions and never publishes directly', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ changeSetId: 'C123', state: 'draft' }), {
      status: 201, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.stageSourceRestore('PACKAGE_SOURCE', 'payments', 'a'.repeat(64), 'c'.repeat(64), 'd'.repeat(64));
    await api.stageSourceRestore('CENTRAL_RUNTIME_CONFIG', 'central-runtime-config', 'b'.repeat(64), 'e'.repeat(64));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const packageCall = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    expect(new URL(String(packageCall[0])).pathname.endsWith('/packages/payments/source-history/restore')).toBe(true);
    expect(packageCall[1].method).toBe('POST');
    expect(JSON.parse(String(packageCall[1].body))).toEqual({ snapshotId: 'a'.repeat(64),
      expectedSourceRevision: 'c'.repeat(64), registryRevision: 'd'.repeat(64) });
    const centralCall = fetchMock.mock.calls[1] as unknown as [URL, RequestInit];
    expect(new URL(String(centralCall[0])).pathname.endsWith('/configuration/source-history/restore')).toBe(true);
    expect(JSON.parse(String(centralCall[1].body))).toEqual({ snapshotId: 'b'.repeat(64), expectedSourceRevision: 'e'.repeat(64) });
  });
});
