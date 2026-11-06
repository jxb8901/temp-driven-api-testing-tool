// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageTestdataWorkspace } from './PackageTestdataCatalog';
import type { ExplorerApi } from './types';
import { inspectionCache } from '../../inspectionCache';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label }: { value: string; label: string }) => <pre aria-label={label}>{value}</pre>,
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }

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
    });
    expect(host.textContent).toContain('account-50');
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
