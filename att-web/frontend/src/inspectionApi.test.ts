import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { inspectionCache } from './inspectionCache';
const json = (value: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(value), { status, headers });

describe('inspection transport caching', () => {
  beforeEach(() => inspectionCache.resetSession());
  afterEach(() => { inspectionCache.resetSession(); vi.unstubAllGlobals(); });

  it('reuses pages, detail, safe source and ordinary configuration after a live context check', async () => {
    const reads = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (url: URL, init: RequestInit) => {
      expect(init.cache).toBe('no-store');
      if (url.pathname.endsWith('/auth/capabilities')) return json({ inspectionCacheContext: 'alice:USER:policy-1' });
      reads(url.toString()); return json({ items: [], total: 0, resource: { resourceId: 'x' }, available: true });
    }));
    const identity = { packageId: 'p', type: 'template' as const, resourceId: 'x' };
    const queries = [() => api.listResources('p', { type: 'all', limit: 50 }), () => api.getResource(identity),
      () => api.getSafeSource(identity), () => api.getDeclaredConfiguration('p'),
      () => api.getEffectiveConfiguration('p', { environment: 'SIT', section: 'tools', offset: 0, limit: 50 })];
    for (const query of queries) { await query(); await query(); }
    expect(reads).toHaveBeenCalledTimes(5);
  });

  it('separates changed identity, role, session and policy contexts and excludes privileged projections', async () => {
    let context = 'alice:ADMIN:session-1:policy-1'; const reads = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (url: URL) => {
      if (url.pathname.endsWith('/auth/capabilities')) return json({ inspectionCacheContext: context });
      reads(); return json({ state: 'ready', items: [] });
    }));
    for (const changed of ['alice:ADMIN:session-1:policy-1', 'alice:USER:session-1:policy-1', 'bob:USER:session-2:policy-1', 'bob:USER:session-2:policy-2']) {
      context = changed; await api.listResources('p', { type: 'all' }); await api.listResources('p', { type: 'all' });
    }
    expect(reads).toHaveBeenCalledTimes(4);
    const admin = { environment: 'SIT', includeAllFields: true };
    await api.getEffectiveConfiguration('p', admin); await api.getEffectiveConfiguration('p', admin);
    expect(reads).toHaveBeenCalledTimes(6);
  });

  it('uses separate environment, query, sort, cursor and source-reference keys', async () => {
    const reads = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (url: URL) => {
      if (url.pathname.endsWith('/auth/capabilities')) return json({ inspectionCacheContext: 'context' });
      reads(); return json({ state: 'ready', items: [] });
    }));
    for (const environment of ['SIT', 'UAT']) await api.getEffectiveConfiguration('p', { environment });
    for (const query of [{ type: 'all' as const }, { type: 'all' as const, query: 'x' }, { type: 'all' as const, sort: 'state' as const }, { type: 'all' as const, cursor: 'next' }]) await api.listResources('p', query);
    const identity = { packageId: 'p', type: 'template' as const, resourceId: 'x' };
    await api.getResourceSourceFile(identity, 'ref-1'); await api.getResourceSourceFile(identity, 'ref-2');
    expect(reads).toHaveBeenCalledTimes(8);
  });

  it('falls back to uncached transport with older Servers and keeps auth/session responses uncached', async () => {
    const fetcher = vi.fn(async () => json({ items: [], configurationAdmin: false })); vi.stubGlobal('fetch', fetcher);
    await api.listResources('p', { type: 'all' }); await api.listResources('p', { type: 'all' });
    expect(fetcher).toHaveBeenCalledTimes(4);
    await api.getCurrentUser(); await api.getCurrentUser(); expect(fetcher).toHaveBeenCalledTimes(6);
  });
});
