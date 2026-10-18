// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigurationWorkspace } from './ConfigurationWorkspace';
import type { ExplorerApi, ResourceSourceReference } from './types';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, fileReferences, sourcePath, onFileReference }: {
    value: string; label: string; fileReferences?: Array<{ referenceId: string; logicalPath: string }>;
    sourcePath?: string; onFileReference?: (path: string, origin?: string, referenceId?: string) => void;
  }) => <>
    <pre aria-label={label}>{value}</pre>
    {fileReferences?.[0] && <button type="button" onClick={() => onFileReference?.('./refs/child.json', sourcePath, fileReferences[0].referenceId)}>Open linked file</button>}
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

function Harness({ api, sourceReference, canRevealAdminFields = false }: { api: ExplorerApi; sourceReference?: ResourceSourceReference; canRevealAdminFields?: boolean }) {
  const [filePath, setFilePath] = useState<string | undefined>(sourceReference ? 'templates/payment/payload/request.xml' : undefined);
  const [fileOrigin, setFileOrigin] = useState<string | undefined>(sourceReference ? 'templates/payment/flow.yaml' : undefined);
  const [activeSourceReference, setActiveSourceReference] = useState(sourceReference);
  const [configurationReferenceId, setConfigurationReferenceId] = useState<string | undefined>();
  const [tab, setTab] = useState<'source' | 'declared' | 'effective' | 'compare' | 'testdata'>('source');
  return <ConfigurationWorkspace api={api} packages={[{ packageId: 'payments' }]} packageId="payments" environment=""
    filePath={filePath} fileOrigin={fileOrigin} sourceReference={activeSourceReference} configurationReferenceId={configurationReferenceId} tab={tab} onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()}
    onFileChange={(path, origin, reference, configurationReference) => { setFilePath(path); setFileOrigin(origin); setActiveSourceReference(reference); setConfigurationReferenceId(configurationReference); }} onTabChange={setTab} onOpenResource={vi.fn()} canRevealAdminFields={canRevealAdminFields} />;
}

describe('ConfigurationWorkspace', () => {
  it('limits the additional DB fields option to Effective and Compare and explains protected views', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'x' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [], sections: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [] })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async () => ({ items: [], total: 0 })),
      getConfigurationFileSource: vi.fn(async () => ({ available: false })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'x', offset: 0, limit: 50, total: 0, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;
    const showTab = async (name: string) => {
      await act(async () => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
        .find(button => button.textContent === name)?.click());
      await flush();
    };

    await act(async () => root.render(<Harness api={api} canRevealAdminFields />));
    await flush();
    expect(host.textContent).not.toContain('Show more DB fields (sensitive values stay hidden)');
    await showTab('Declared');
    expect(host.textContent).not.toContain('Show more DB fields (sensitive values stay hidden)');
    await showTab('Effective');
    expect(host.textContent).toContain('Show more DB fields (sensitive values stay hidden)');
    expect(api.getDeclaredConfiguration).toHaveBeenCalledWith('payments', expect.any(AbortSignal));
    await showTab('Compare');
    expect(host.textContent).toContain('Show more DB fields (sensitive values stay hidden)');
    await showTab('Source');
    expect(host.textContent).not.toContain('Show more DB fields (sensitive values stay hidden)');
  });

  it('shows a generic unavailable state when the Server withholds config-source references', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'x' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [] })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async (_packageId: string, path?: string) => ({
        path: path || '', total: path ? 0 : 2, items: path ? [] : [
          { name: 'main.yaml', path: 'config/main.yaml', kind: 'file' as const, sourceAvailable: true },
          { name: 'refs', path: 'config/refs', kind: 'directory' as const },
        ],
      })),
      getConfigurationFileSource: vi.fn(async (_packageId: string, path: string) => ({
        available: true, format: 'yaml',
        logicalPath: path,
        text: 'name: base\npayload: &{./refs/child.json}',
      })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'x', offset: 0, limit: 50, total: 0, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} />));
    await flush();
    await act(async () => host.querySelector<HTMLButtonElement>('[role="tree"] button[role="treeitem"]')?.click());
    await flush();
    expect(api.getConfigurationFileSource).toHaveBeenCalledWith('payments', 'config/main.yaml', undefined, expect.any(AbortSignal), undefined);
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('name: base\npayload: &{./refs/child.json}');
    expect(host.textContent).toContain('The Server did not authorize any referenced files for navigation from this source.');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Open linked file')).toBe(false);
    expect(api.getConfigurationFileSource).toHaveBeenCalledTimes(1);
  });

  it('opens a config-source reference with the Server-issued reference token', async () => {
    const referenceId='eyJmaWxlUmVmZXJlbmNlIjoiYXV0aG9yaXplZCJ9';
    const getConfigurationFileSource=vi.fn(async (_packageId:string,path:string) => ({
      available:true,format:path.endsWith('.json')?'json':'yaml',logicalPath:path,
      text:path==='config/main.yaml'?'payload: &{./refs/child.json}':'{"status":"ok"}',
      fileReferences:path==='config/main.yaml'?[{referenceId,logicalPath:'config/refs/child.json',available:true}]:[],
    }));
    const api={
      listResources:vi.fn(async()=>({items:[],total:0})),getResource:vi.fn(async()=>({resource:{type:'template',resourceId:'x'}})),
      getSafeSource:vi.fn(async()=>({available:false})),getDeclaredConfiguration:vi.fn(async()=>({environments:[]})),
      getEffectiveConfiguration:vi.fn(async()=>({sections:[]})),compareConfiguration:vi.fn(async()=>({fields:[]})),
      listConfigurationFiles:vi.fn(async(_packageId:string,path?:string)=>({path:path||'',total:path?0:1,items:path?[]:[{name:'main.yaml',path:'config/main.yaml',kind:'file' as const,sourceAvailable:true}]})),
      getConfigurationFileSource,getTestdataRecords:vi.fn(async()=>({descriptorId:'x',offset:0,limit:50,total:0,records:[]})),
      getDebugForm:vi.fn(),previewDebug:vi.fn(),submitDebug:vi.fn(),runCase:vi.fn(),
    } as unknown as ExplorerApi;
    await act(async()=>root.render(<Harness api={api}/>));await flush();
    await act(async()=>host.querySelector<HTMLButtonElement>('[role="tree"] button[role="treeitem"]')?.click());await flush();
    await act(async()=>[...host.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent==='Open linked file')?.click());await flush();
    expect(getConfigurationFileSource).toHaveBeenLastCalledWith('payments','config/refs/child.json','config/main.yaml',expect.any(AbortSignal),referenceId);
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('{"status":"ok"}');
  });

  it('uses tree arrow keys to expand, enter children, return to parents, and collapse', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'x' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [] })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async (_packageId: string, path = '') => ({
        path, total: 1, items: path === 'config/helpers'
          ? [{ name: 'one.yaml', path: 'config/helpers/one.yaml', kind: 'file' as const, sourceAvailable: true }]
          : [{ name: 'helpers', path: 'config/helpers', kind: 'directory' as const }],
      })),
      getConfigurationFileSource: vi.fn(async () => ({ available: false })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'x', offset: 0, limit: 50, total: 0, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} />));
    await flush();
    const tree = host.querySelector('[role="tree"]')!;
    const press = async (item: HTMLElement, key: string) => act(async () => {
      item.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    });
    let parent = [...tree.querySelectorAll<HTMLElement>('[role="treeitem"]')].find(item => item.textContent?.includes('helpers'))!;
    parent.focus();
    await press(parent, 'ArrowRight');
    await flush();
    parent = [...tree.querySelectorAll<HTMLElement>('[role="treeitem"]')].find(item => item.textContent?.includes('helpers'))!;
    expect(parent.getAttribute('aria-expanded')).toBe('true');

    await press(parent, 'ArrowRight');
    const child = [...tree.querySelectorAll<HTMLElement>('[role="treeitem"]')].find(item => item.textContent?.includes('one.yaml'))!;
    expect(document.activeElement).toBe(child);

    await press(child, 'ArrowLeft');
    expect(document.activeElement).toBe(parent);
    await press(parent, 'ArrowLeft');
    expect(parent.getAttribute('aria-expanded')).toBe('false');
    expect(tree.textContent).not.toContain('one.yaml');
  });

  it('opens a projected template payload by its resource reference id', async () => {
    const referenceId = 'b'.repeat(64);
    const nestedReferenceId = 'd'.repeat(64);
    const getResourceSourceFile = vi.fn(async (_identity: unknown, selectedId: string, _signal?: AbortSignal, _origin?: string) => selectedId === referenceId
      ? ({ available: true, logicalPath: 'templates/payment/payload/request.xml', format: 'xml', text: '<request>&{./refs/child.json}</request>', fileReferences: [{ referenceId: nestedReferenceId, logicalPath: 'templates/payment/payload/refs/child.json', available: true }] })
      : ({ available: true, logicalPath: 'templates/payment/payload/refs/child.json', format: 'json', text: '{"ok":true}', fileReferences: [] }));
    const api = {
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
      getResource: vi.fn(async () => ({ resource: { type: 'flow', resourceId: 'checkout-flow' } })),
      getSafeSource: vi.fn(async () => ({ available: false })),
      getResourceSourceFile,
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [] })),
      compareConfiguration: vi.fn(async () => ({ fields: [] })),
      listConfigurationFiles: vi.fn(async () => ({ items: [], total: 0 })),
      getConfigurationFileSource: vi.fn(async () => ({ available: false })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'x', offset: 0, limit: 50, total: 0, records: [] })),
      getDebugForm: vi.fn(), previewDebug: vi.fn(), submitDebug: vi.fn(), runCase: vi.fn(),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<Harness api={api} sourceReference={{ type: 'flow', resourceId: 'checkout-flow', referenceId }} />));
    await flush();

    expect(getResourceSourceFile).toHaveBeenCalledWith({ packageId: 'payments', type: 'flow', resourceId: 'checkout-flow' }, referenceId, expect.any(AbortSignal), 'templates/payment/flow.yaml');
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('<request>&{./refs/child.json}</request>');
    expect(host.textContent).not.toContain('This projection includes no Server-verified file-reference token');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Open linked file')).toBe(true);
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Open linked file')?.click());
    await flush();
    expect(getResourceSourceFile).toHaveBeenLastCalledWith({ packageId: 'payments', type: 'flow', resourceId: 'checkout-flow' }, nestedReferenceId, expect.any(AbortSignal), 'templates/payment/payload/request.xml');
    expect(host.querySelector('pre[aria-label^="Safe source"]')?.textContent).toBe('{"ok":true}');
    expect(api.getConfigurationFileSource).not.toHaveBeenCalled();
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
            id: 'accounts', source: 'testdata/accounts.yaml',
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
        sections: [{ id: 'testdata', entries: [{ id: 'generatedAccounts', source: 'testdata/generated.yaml', fields: {
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
