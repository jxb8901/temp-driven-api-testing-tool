import { describe, expect, it, vi } from 'vitest';
import { InspectionQueryCache } from './inspectionCache';
const deferred = <T,>() => { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

describe('inspection session memory', () => {
  it('deduplicates requests, isolates returned objects, and cancels consumers independently', async () => {
    const cache = new InspectionQueryCache();
    const pending = deferred<{ items: number[] }>();
    let transportSignal: AbortSignal | undefined;
    const load = vi.fn((signal: AbortSignal) => { transportSignal = signal; return pending.promise; });
    const first = new AbortController(), second = new AbortController();
    const a = cache.query('page', load, first.signal);
    const b = cache.query('page', load, second.signal);
    await Promise.resolve(); first.abort();
    await expect(a).rejects.toMatchObject({ name: 'AbortError' });
    expect(transportSignal?.aborted).toBe(false);
    pending.resolve({ items: [1] });
    const value = await b; value.items.push(2);
    expect(await cache.query('page', load)).toEqual({ items: [1] });
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('aborts transport when the final consumer leaves and never revives its result', async () => {
    const cache = new InspectionQueryCache();
    const pending = deferred<number>();
    let signal: AbortSignal | undefined;
    const controller = new AbortController();
    const request = cache.query('page', current => { signal = current; return pending.promise; }, controller.signal);
    await Promise.resolve(); controller.abort();
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(signal?.aborted).toBe(true); pending.resolve(1);
    expect(await cache.query('page', async () => 2)).toBe(2);
  });

  it('discards prior identity and selection responses, including transports that ignore abort', async () => {
    const cache = new InspectionQueryCache(); cache.setContext('alice');
    const pending = deferred<string>();
    const old = cache.query('page', () => pending.promise);
    await Promise.resolve(); cache.setContext('bob'); pending.resolve('alice secret');
    await expect(old).rejects.toMatchObject({ name: 'AbortError' });
    expect(await cache.query('page', async () => 'bob')).toBe('bob');
    cache.select('another-package', 'UAT');
    expect(await cache.query('page', async () => 'UAT')).toBe('UAT');
    cache.resetSession();
    expect(await cache.query('page', async () => 'new login')).toBe('new login');
  });

  it('shows stale data while revalidating and notifies readers of updates and failures', async () => {
    let now = 0; const cache = new InspectionQueryCache(10, () => now);
    const changed = vi.fn(); cache.subscribe(changed);
    expect(await cache.query('page', async () => 'before')).toBe('before');
    now = 11; const pending = deferred<string>();
    expect(await cache.query('page', () => pending.promise)).toBe('before');
    pending.resolve('after');
    await vi.waitFor(() => expect(changed).toHaveBeenCalled());
    expect(await cache.query('page', async () => 'unexpected')).toBe('after');
    now = 22; changed.mockClear();
    expect(await cache.query('page', async () => { throw { status: 403 }; })).toBe('after');
    await vi.waitFor(() => expect(changed).toHaveBeenCalled());
    await expect(cache.query('page', async () => { throw { status: 403 }; })).rejects.toEqual({ status: 403 });
  });

  for (const invalidate of ['clear', 'path', 'package', 'session'] as const) {
    for (const outcome of ['resolve', 'reject'] as const) {
      it(`preserves a newer response when obsolete revalidation settles: ${invalidate}/${outcome}`, async () => {
        let now = 0; const cache = new InspectionQueryCache(10, () => now);
        const path = 'packages/demo/resources';
        await cache.query(path, async () => 'stale');
        now = 11;
        const pending = deferred<string>();
        expect(await cache.query(path, () => pending.promise)).toBe('stale');
        if (invalidate === 'clear') cache.clear();
        if (invalidate === 'path') cache.invalidatePath(path);
        if (invalidate === 'package') cache.invalidatePackage('demo');
        if (invalidate === 'session') cache.resetSession();
        expect(await cache.query(path, async () => 'current')).toBe('current');
        const changed = vi.fn(); cache.subscribe(changed);
        if (outcome === 'resolve') pending.resolve('obsolete');
        else pending.reject(new Error('obsolete failure'));
        // Drain the old transport, fetch, consumer, and revalidation handlers.
        await new Promise(resolve => setTimeout(resolve, 0));
        const load = vi.fn(async () => 'unexpected');
        expect(await cache.query(path, load)).toBe('current');
        expect(load).not.toHaveBeenCalled();
        expect(changed).not.toHaveBeenCalled();
      });
    }
  }

  it('refreshes only the requested path without discarding other views or metadata', async () => {
    const cache = new InspectionQueryCache();
    await cache.query('source', async () => 'old source');
    await cache.query('list', async () => 'retained list');
    cache.setView('filters', { query: 'checkout' });
    cache.invalidatePath('source');
    expect(await cache.query('source', async () => 'new source')).toBe('new source');
    const load = vi.fn(async () => 'unexpected');
    expect(await cache.query('list', load)).toBe('retained list');
    expect(load).not.toHaveBeenCalled();
    expect(cache.getView('filters')).toEqual({ query: 'checkout' });
  });

  it('invalidates old pages and outstanding reads on a package revision change', async () => {
    const cache = new InspectionQueryCache();
    cache.revision('demo', 'v1');
    await cache.query('packages/demo/resources', async () => 'v1');
    const pending = deferred<string>();
    const source = cache.query('packages/demo/resources/tool/a/source', () => pending.promise);
    await Promise.resolve(); cache.revision('demo', 'v2', 'packages/demo/resources'); pending.resolve('old source');
    await expect(source).rejects.toMatchObject({ name: 'AbortError' });
    expect(await cache.query('packages/demo/resources', async () => 'v2')).toBe('v2');
  });

  it('bounds entry count and payload bytes, evicts least recently used entries, and limits view state', async () => {
    const cache = new InspectionQueryCache(); const load = vi.fn(async () => 'small');
    await cache.query('first', load);
    for (let i = 0; i < 128; i++) await cache.query(String(i), async () => i);
    await cache.query('first', load); expect(load).toHaveBeenCalledTimes(2);
    const large = vi.fn(async () => 'x'.repeat(1024 * 1024 + 1));
    await cache.query('large', large); await cache.query('large', large); expect(large).toHaveBeenCalledTimes(2);
    for (let i = 0; i < 40; i++) cache.setView(String(i), i);
    expect(cache.getView('0')).toBeUndefined(); expect(cache.getView('39')).toBe(39);
  });
});
