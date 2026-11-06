// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageTestdataWorkspace } from './PackageTestdataCatalog';
import type { ConfigurationResponse, ExplorerApi, TestdataRecordsResponse } from './types';
import { inspectionCache } from '../../inspectionCache';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label }: { value: string; label: string }) => <pre aria-label={label}>{value}</pre>,
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockResolvedValue(undefined) } });
});
afterEach(async () => {
  await act(async () => root.unmount()); host.remove();
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
});
async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function RoutedHarness({ api, packageId = 'payments', environment = '', descriptorId }: {
  api: ExplorerApi; packageId?: string; environment?: string; descriptorId?: string;
}) {
  return <PackageTestdataWorkspace api={api} packageId={packageId} packageLabel={packageId} registrationState="ready" registered
    environment={environment} descriptorId={descriptorId} onEnvironmentChange={vi.fn()} onDescriptorChange={vi.fn()}
    onBackToResources={vi.fn()} onOpenResource={vi.fn()} onOpenPackageConfiguration={vi.fn()} />;
}

function generatedDescriptors() {
  return ['generated-a', 'generated-b'].map(id => ({ id, source: `testdata/${id}.yaml`, generated: true,
    fields: { generated: { state: 'visible' as const, value: true }, recordCount: { state: 'visible' as const, value: 100 } },
  }));
}

function configurationPage(id: string, nextOffset: number | null): ConfigurationResponse {
  return { total: nextOffset == null ? 1 : 2, nextOffset, sections: [{ id: 'testdata', entries: [{ id, source: `testdata/${id}.yaml` }] }] };
}

function setSearchValue(value: string) {
  const input = host.querySelector<HTMLInputElement>('input[aria-label="Search Testdata descriptors"]');
  if (!input) throw new Error('Testdata descriptor search is not available');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function Harness({ api, initialEnvironment = '', descriptorId, registered = true, onOpenPackageConfiguration }: {
  api: ExplorerApi; initialEnvironment?: string; descriptorId?: string; registered?: boolean; onOpenPackageConfiguration?: () => void;
}) {
  const [environment, setEnvironment] = useState(initialEnvironment);
  const [selectedDescriptor, setSelectedDescriptor] = useState(descriptorId);
  return <PackageTestdataWorkspace api={api} packageId="payments" packageLabel="Payments" registrationState="ready" registered={registered}
    environment={environment} descriptorId={selectedDescriptor} onEnvironmentChange={setEnvironment}
    onDescriptorChange={setSelectedDescriptor} onBackToResources={vi.fn()} onOpenResource={vi.fn()} onOpenPackageConfiguration={onOpenPackageConfiguration || vi.fn()} />;
}

describe('PackageTestdataWorkspace', () => {
  it('loads the linked Package descriptor and shows safe metadata when record preview is unavailable', async () => {
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'dev', state: 'active', default: true }] })),
      getEffectiveConfiguration: vi.fn(async () => ({ environment: 'dev', total: 1, sections: [{ id: 'testdata', entries: [{ id: 'accounts', source: 'testdata/accounts.yaml', fields: {
        recordCount: { state: 'visible' as const, value: 3 }, 'selection.strategy': { state: 'visible' as const, value: 'roundRobin', origin: 'Base Package Manifest' },
      } }] }] })),
      getTestdataRecords: vi.fn(async () => { throw Object.assign(new Error('not found'), { status: 404 }); }),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} descriptorId="accounts" />)); await flush();
    expect(host.textContent).toContain('Package: Payments');
    expect(host.textContent).toContain('Effective environment: dev');
    expect(host.textContent).toContain('testdata/accounts.yaml');
    expect(host.textContent).toContain('Base Package Manifest');
    expect(host.textContent).toContain('3');
    expect(host.textContent).toContain('Testdata record preview is not available from this Server version');
    expect(api.getEffectiveConfiguration).toHaveBeenCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'accounts', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
  });

  it('keeps generated records withheld until the user requests the bounded preview', async () => {
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'dev', state: 'active', default: true }] })),
      getEffectiveConfiguration: vi.fn(async () => ({ state: 'ready', environment: 'dev', total: 1,
        sections: [{ id: 'testdata', entries: [{ id: 'generatedAccounts', source: 'testdata/generated.yaml', fields: {
          generated: { state: 'visible' as const, value: true }, recordCount: { state: 'visible' as const, value: 120 },
          'selection.strategy': { state: 'visible' as const, value: 'sequential' },
        } }] }],
      })),
      getTestdataRecords: vi.fn(async (_packageId: string, _descriptorId: string, query: { previewGenerated?: boolean }) => query.previewGenerated
        ? ({ descriptorId: 'generatedAccounts', generated: true, previewGenerated: true, previewRequired: false, offset: 0, limit: 50, total: 120, recordCount: 120,
            generation: { generate: { seq: { from: 1000, to: 1119, format: '%06d' } }, record: { id: 'A-%{seq}' } },
            records: [{ index: 0, sequence: 1000, value: { id: 'A-001000' } }] })
        : ({ descriptorId: 'generatedAccounts', generated: true, previewGenerated: false, previewRequired: true, offset: 0, limit: 50, total: 120, recordCount: 120,
            generation: { generate: { seq: { from: 1000, to: 1119, format: '%06d' } }, record: { id: 'A-%{seq}' } }, records: [] })),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} descriptorId="generatedAccounts" />)); await flush();
    expect(host.textContent).toContain('120');
    expect(host.textContent).not.toContain('A-001000');
    expect(api.getTestdataRecords).toHaveBeenCalledWith('payments', 'generatedAccounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'generatedAccounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: true }, expect.any(AbortSignal));
    expect(host.textContent).toContain('A-001000');
    expect(host.textContent).toContain('seq 1000');
  });

  it('uses server-side descriptor paging for the Package route', async () => {
    const entries = Array.from({ length: 50 }, (_, index) => ({ id: `account-${index}`, source: `testdata/${index}.yaml` }));
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { offset?: number }) => query.offset
        ? ({ total: 51, sections: [{ id: 'testdata', entries: [{ id: 'account-50', source: 'testdata/50.yaml' }] }] })
        : ({ total: 51, nextOffset: 50, sections: [{ id: 'testdata', entries }] })),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} />)); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load more descriptors')?.click()); await flush();
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: undefined, offset: 50, limit: 50,
    }, expect.any(AbortSignal));
    expect(host.textContent).toContain('account-50');
  });

  it('resets generated preview, record paging, and copied-row state on descriptor and scope route changes', async () => {
    const requests: Array<{ packageId: string; descriptorId: string; query: { environment?: string; offset?: number; previewGenerated?: boolean } }> = [];
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [
        { name: 'UAT', state: 'active' as const, default: true }, { name: 'SIT', state: 'active' as const },
      ] })),
      getEffectiveConfiguration: vi.fn(async () => ({ total: 2, sections: [{ id: 'testdata', entries: generatedDescriptors() }] })),
      getTestdataRecords: vi.fn(async (packageId: string, descriptorId: string, query: { environment?: string; offset?: number; previewGenerated?: boolean }) => {
        requests.push({ packageId, descriptorId, query });
        const offset = query.offset || 0;
        const preview = query.previewGenerated === true;
        return {
          descriptorId, generated: true, previewGenerated: preview, previewRequired: !preview,
          offset, limit: 50, total: 100, nextOffset: preview && offset === 0 ? 50 : null,
          records: preview ? [{ index: 0, value: { id: `${packageId}-${descriptorId}-page-${offset}` } }] : [],
        } satisfies TestdataRecordsResponse;
      }),
    } as unknown as ExplorerApi;
    const renderRoute = async (descriptorId: string, packageId = 'payments', environment = 'UAT') => {
      await act(async () => root.render(<RoutedHarness api={api} packageId={packageId} environment={environment} descriptorId={descriptorId} />));
      await flush();
    };

    await renderRoute('generated-a');
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    expect(requests.at(-1)).toMatchObject({ descriptorId: 'generated-a', query: { environment: 'UAT', offset: 0, previewGenerated: true } });
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load next 50 records')?.click()); await flush();
    expect(requests.at(-1)).toMatchObject({ descriptorId: 'generated-a', query: { environment: 'UAT', offset: 50, previewGenerated: true } });
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Copy row')?.click()); await flush();
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Copied')).toBe(true);

    await renderRoute('generated-b');
    expect(requests.at(-1)).toMatchObject({ descriptorId: 'generated-b', query: { environment: 'UAT', offset: 0, previewGenerated: false } });
    expect(host.textContent).not.toContain('payments-generated-a-page-50');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Preview first 50 generated records')).toBe(true);
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Copy row')).toBe(true);
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Copied')).toBe(false);

    await renderRoute('generated-a');
    expect(requests.at(-1)).toMatchObject({ descriptorId: 'generated-a', query: { environment: 'UAT', offset: 0, previewGenerated: false } });
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    await renderRoute('generated-a', 'payments', 'SIT');
    expect(requests.at(-1)).toMatchObject({ packageId: 'payments', descriptorId: 'generated-a', query: { environment: 'SIT', offset: 0, previewGenerated: false } });
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    await renderRoute('generated-a', 'ledger', 'SIT');
    expect(requests.at(-1)).toMatchObject({ packageId: 'ledger', descriptorId: 'generated-a', query: { environment: 'SIT', offset: 0, previewGenerated: false } });
  });

  it('hides the previous descriptor records and copy action while the next records request is pending', async () => {
    const pendingB = deferred<TestdataRecordsResponse>();
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'UAT', state: 'active' as const, default: true }] })),
      getEffectiveConfiguration: vi.fn(async () => ({ total: 2, sections: [{ id: 'testdata', entries: generatedDescriptors() }] })),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string, query: { previewGenerated?: boolean }) => {
        if (descriptorId === 'generated-b') return pendingB.promise;
        return {
          descriptorId, generated: true, previewGenerated: query.previewGenerated === true,
          previewRequired: query.previewGenerated !== true, offset: 0, limit: 50, total: 1,
          records: query.previewGenerated ? [{ index: 0, value: { id: 'previous-descriptor-secret' } }] : [],
        } satisfies TestdataRecordsResponse;
      }),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<RoutedHarness api={api} environment="UAT" descriptorId="generated-a" />)); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click()); await flush();
    expect(host.textContent).toContain('previous-descriptor-secret');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Copy row')).toBe(true);

    await act(async () => root.render(<RoutedHarness api={api} environment="UAT" descriptorId="generated-b" />)); await flush();
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'generated-b',
      { environment: 'UAT', offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));
    expect(host.textContent).not.toContain('previous-descriptor-secret');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Copy row')).toBe(false);
    pendingB.resolve({ descriptorId: 'generated-b', generated: true, previewRequired: true, offset: 0, limit: 50, total: 1, records: [] });
    await flush();
    expect(host.textContent).not.toContain('previous-descriptor-secret');
  });

  it('discards late first-page and load-more responses after the catalog scope changes', async () => {
    const pendingUatPage = deferred<ConfigurationResponse>();
    const pendingSitLoadMore = deferred<ConfigurationResponse>();
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [
        { name: 'UAT', state: 'active' as const, default: true }, { name: 'SIT', state: 'active' as const },
      ] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { environment?: string; query?: string; offset?: number }) => {
        if (query.environment === 'UAT' && !query.query && query.offset === 0) return pendingUatPage.promise;
        if (query.environment === 'SIT' && !query.query && query.offset === 0) return configurationPage('sit-first-page', 50);
        if (query.environment === 'SIT' && !query.query && query.offset === 50) return pendingSitLoadMore.promise;
        if (query.environment === 'SIT' && query.query === 'fresh' && query.offset === 0) return configurationPage('fresh-first-page', 50);
        if (query.environment === 'SIT' && query.query === 'fresh' && query.offset === 50) return configurationPage('fresh-second-page', null);
        throw new Error(`Unexpected catalog query ${JSON.stringify(query)}`);
      }),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<RoutedHarness api={api} environment="UAT" />)); await flush();
    expect(vi.mocked(api.getEffectiveConfiguration)).toHaveBeenCalledWith('payments', {
      environment: 'UAT', section: 'testdata', query: undefined, offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    const staleFirstPageSignal = vi.mocked(api.getEffectiveConfiguration).mock.calls[0][2];

    await act(async () => root.render(<RoutedHarness api={api} environment="SIT" />)); await flush();
    expect(host.textContent).toContain('sit-first-page');
    expect(staleFirstPageSignal?.aborted).toBe(true);
    pendingUatPage.resolve(configurationPage('uat-stale-first-page', null));
    await flush();
    expect(host.textContent).not.toContain('uat-stale-first-page');

    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load more descriptors')?.click()); await flush();
    const staleLoadMoreCall = vi.mocked(api.getEffectiveConfiguration).mock.calls.find(call => call[1].environment === 'SIT' && call[1].offset === 50 && !call[1].query);
    expect(staleLoadMoreCall).toBeTruthy();

    await act(async () => setSearchValue('fresh')); await flush();
    expect(host.textContent).toContain('fresh-first-page');
    expect(staleLoadMoreCall?.[2]?.aborted).toBe(true);
    pendingSitLoadMore.resolve(configurationPage('sit-stale-second-page', null));
    await flush();
    expect(host.textContent).not.toContain('sit-stale-second-page');
    expect(host.textContent).toContain('fresh-first-page');
    const currentLoadMore = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load more descriptors');
    expect(currentLoadMore?.disabled).toBe(false);
    await act(async () => currentLoadMore?.click()); await flush();
    expect(host.textContent).toContain('fresh-second-page');
    expect(host.textContent).not.toContain('sit-stale-second-page');
  });

  it('reconciles deep-linked descriptors across same-mounted Back/Forward routes without replacing manual search', async () => {
    const descriptors = [
      { id: 'accounts', source: 'testdata/accounts.yaml' },
      { id: 'payments', source: 'testdata/payments.yaml' },
    ];
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { query?: string }) => {
        const normalizedQuery = query.query?.toLocaleLowerCase();
        const matches = descriptors.filter(item => !normalizedQuery
          || item.id.toLocaleLowerCase().includes(normalizedQuery)
          || item.source.toLocaleLowerCase().includes(normalizedQuery));
        return { total: matches.length, sections: [{ id: 'testdata', entries: matches }] };
      }),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;
    const renderRoute = async (descriptorId?: string) => {
      await act(async () => root.render(<RoutedHarness api={api} descriptorId={descriptorId} />));
      await flush();
    };

    await renderRoute('accounts');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'accounts', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'accounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));

    await renderRoute('payments');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'payments', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'payments',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));

    await renderRoute('accounts');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'accounts', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'accounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));

    await act(async () => setSearchValue('acc'));
    await renderRoute(undefined);
    expect(host.querySelector<HTMLInputElement>('input[aria-label="Search Testdata descriptors"]')?.value).toBe('acc');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'acc', offset: 0, limit: 50,
    }, expect.any(AbortSignal));

    await renderRoute('payments');
    expect(host.querySelector<HTMLInputElement>('input[aria-label="Search Testdata descriptors"]')?.value).toBe('acc');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'payments', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'payments',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));
    expect(host.textContent).not.toContain('descriptor payments is unavailable');
  });

  it('does not fall back to shared or another Package when the route Package is unregistered', async () => {
    const api = { getDeclaredConfiguration: vi.fn(), getEffectiveConfiguration: vi.fn(), getTestdataRecords: vi.fn() } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} registered={false} />)); await flush();
    expect(host.textContent).toContain('Package payments is not registered or is no longer available');
    expect(host.textContent).toContain('Choose a registered Package');
    expect(api.getDeclaredConfiguration).not.toHaveBeenCalled();
    expect(api.getEffectiveConfiguration).not.toHaveBeenCalled();
    expect(api.getTestdataRecords).not.toHaveBeenCalled();
  });

  it('invalidates Package inspection projections when refreshing the catalog', async () => {
    const invalidatePackage = vi.spyOn(inspectionCache, 'invalidatePackage');
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [{ id: 'testdata', entries: [] }] })),
      getTestdataRecords: vi.fn(),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} />)); await flush();
    const readsBeforeRefresh = vi.mocked(api.getEffectiveConfiguration).mock.calls.length;
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Refresh Testdata')?.click());
    await flush();
    expect(invalidatePackage).toHaveBeenCalledWith('payments');
    expect(vi.mocked(api.getEffectiveConfiguration).mock.calls.length).toBeGreaterThan(readsBeforeRefresh);
  });

  it('links to the Package Configuration owner for the Testdata inventory', async () => {
    const onOpenPackageConfiguration = vi.fn();
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [{ id: 'testdata', entries: [] }] })),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} onOpenPackageConfiguration={onOpenPackageConfiguration} />)); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Package Configuration')?.click());
    expect(onOpenPackageConfiguration).toHaveBeenCalledTimes(1);
  });
});
