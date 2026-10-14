// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigurationWorkspace } from './ConfigurationWorkspace';
import type { ExplorerApi } from './types';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, sourcePath, onFileReference }: {
    value: string; label: string; sourcePath?: string; onFileReference?: (path: string, origin?: string) => void;
  }) => <>
    <pre aria-label={label}>{value}</pre>
    {sourcePath && onFileReference && <button type="button" onClick={() => onFileReference('./refs/child.json', sourcePath)}>Open linked file</button>}
  </>,
  detectCodeLanguage: () => 'yaml',
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});
async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

function Harness({ api }: { api: ExplorerApi }) {
  const [filePath, setFilePath] = useState<string>();
  const [fileOrigin, setFileOrigin] = useState<string>();
  const [tab, setTab] = useState<'source' | 'declared' | 'effective' | 'compare' | 'testdata'>('source');
  return <ConfigurationWorkspace api={api} packages={[{ packageId: 'payments' }]} packageId="payments" environment=""
    filePath={filePath} fileOrigin={fileOrigin} tab={tab} onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()}
    onFileChange={(path, origin) => { setFilePath(path); setFileOrigin(origin); }} onTabChange={setTab} onOpenResource={vi.fn()} />;
}

describe('ConfigurationWorkspace', () => {
  it('opens safe source from the file tree and preserves descriptor origin for linked files', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'x' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [] })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async () => ({
        path: 'config', total: 2, items: [
          { name: 'main.yaml', path: 'config/main.yaml', kind: 'file' as const, sourceAvailable: true },
          { name: 'refs', path: 'config/refs', kind: 'directory' as const },
        ],
      })),
      getConfigurationFileSource: vi.fn(async (_packageId: string, path: string) => ({
        available: true, format: path.endsWith('.json') ? 'json' : 'yaml',
        logicalPath: path.startsWith('./') ? 'config/refs/child.json' : path,
        text: path.startsWith('./') ? '{"ok":true}' : 'name: base',
      })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'x', offset: 0, limit: 50, total: 0, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} />));
    await flush();
    await act(async () => host.querySelector<HTMLButtonElement>('[role="tree"] button[role="treeitem"]')?.click());
    await flush();
    expect(api.getConfigurationFileSource).toHaveBeenCalledWith('payments', 'config/main.yaml', undefined, expect.any(AbortSignal));
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('name: base');

    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Open linked file')?.click());
    await flush();
    expect(api.getConfigurationFileSource).toHaveBeenLastCalledWith('payments', './refs/child.json', 'config/main.yaml', expect.any(AbortSignal));
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('{"ok":true}');
  });

  it('keeps safe descriptor metadata visible when a Server does not expose the records endpoint', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'checkout' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({
        environments: [{ name: 'dev', state: 'active', default: true }],
        sections: [{ id: 'testdata', title: 'Testdata' }],
      })),
      getEffectiveConfiguration: vi.fn(async () => ({
        state: 'ready',
        environment: 'dev',
        sections: [{
          id: 'testdata',
          entries: [{
            id: 'accounts', source: 'config/testdata/accounts.yaml',
            fields: {
              recordCount: { state: 'visible' as const, value: 3 },
              'selection.strategy': { state: 'visible' as const, value: 'roundRobin' },
            },
          }],
        }],
      })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async () => ({ items: [], total: 0 })),
      getConfigurationFileSource: vi.fn(async () => ({ available: false })),
      getTestdataRecords: vi.fn(async () => { throw Object.assign(new Error('not found'), { status: 404 }); }),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} />));
    await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find(button => button.textContent === 'Testdata')?.click());
    await flush();
    expect(host.textContent).toContain('accounts');
    expect(host.textContent).toContain('3');
    expect(host.textContent).toContain('Testdata record preview is not available from this Server version');
  });

  it('requires an explicit request before showing a bounded generated-record preview', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'checkout' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'dev', state: 'active', default: true }] })),
      getEffectiveConfiguration: vi.fn(async () => ({
        state: 'ready', environment: 'dev',
        sections: [{ id: 'testdata', entries: [{ id: 'generatedAccounts', source: 'config/testdata/generated.yaml', fields: {
          generated: { state: 'visible' as const, value: true },
          recordCount: { state: 'visible' as const, value: 120 },
          'selection.strategy': { state: 'visible' as const, value: 'sequential' },
        } }] }],
      })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async () => ({ items: [], total: 0 })),
      getConfigurationFileSource: vi.fn(async () => ({ available: false })),
      getTestdataRecords: vi.fn(async (_packageId: string, _descriptorId: string, query: { previewGenerated?: boolean }) => query.previewGenerated
        ? ({ descriptorId: 'generatedAccounts', generated: true, previewGenerated: true, previewRequired: false, offset: 0, limit: 50, total: 120, recordCount: 120,
            generation: { generate: { seq: { from: 1000, to: 1119, format: '%06d' } }, record: { id: 'A-%{seq}' } },
            records: [{ index: 0, sequence: 1000, value: { id: 'A-001000' } }] })
        : ({ descriptorId: 'generatedAccounts', generated: true, previewGenerated: false, previewRequired: true, offset: 0, limit: 50, total: 120, recordCount: 120,
            generation: { generate: { seq: { from: 1000, to: 1119, format: '%06d' } }, record: { id: 'A-%{seq}' } }, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} />));
    await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find(button => button.textContent === 'Testdata')?.click());
    await flush();
    expect(host.textContent).toContain('120');
    expect(host.textContent).toContain('Choose');
    expect(host.textContent).not.toContain('A-001000');
    expect(api.getTestdataRecords).toHaveBeenCalledWith('payments', 'generatedAccounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));

    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Preview first 50 generated records')?.click());
    await flush();
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'generatedAccounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: true }, expect.any(AbortSignal));
    expect(host.textContent).toContain('A-001000');
    expect(host.textContent).toContain('seq 1000');
  });
});
