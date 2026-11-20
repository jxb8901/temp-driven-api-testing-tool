// @vitest-environment jsdom
import { act, useCallback, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageTestdataWorkspace } from './PackageTestdataCatalog';
import type { ConfigurationResponse, ExplorerApi, TestdataRecordsResponse } from './types';
import { inspectionCache } from '../../inspectionCache';
import { api as sharedApi } from '../../api';
import { formatRoute, NavigationGuardContext, useRouteState } from '../../router';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, readOnly = true, onChange, maxCharacters = 200_000 }: {
    value: string; label: string; readOnly?: boolean; onChange?: (value: string) => void; maxCharacters?: number;
  }) => {
    const displayed = value.length > maxCharacters ? value.slice(0, maxCharacters) : value;
    return readOnly ? <pre aria-label={label}>{displayed}</pre>
      : <textarea aria-label={label} value={displayed} onChange={event => onChange?.(event.currentTarget.value)} />;
  },
  detectCodeLanguage: () => 'yaml',
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
  window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`);
  vi.restoreAllMocks(); vi.unstubAllGlobals();
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

function SelectedDescriptorHarness({ api, environment = '' }: { api: ExplorerApi; environment?: string }) {
  const [descriptorId, setDescriptorId] = useState<string>();
  return <PackageTestdataWorkspace api={api} packageId="payments" packageLabel="payments" registrationState="ready" registered
    environment={environment} descriptorId={descriptorId} onEnvironmentChange={vi.fn()} onDescriptorChange={setDescriptorId}
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
function setInputValue(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  act(() => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
function clickButton(label: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label);
  if (!button) throw new Error(`Button ${label} is not available`);
  act(() => button.click());
}
function clickDescriptor(id: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('nav[aria-label="Testdata descriptors"] button')]
    .find(item => item.querySelector('span')?.textContent === id);
  if (!button) throw new Error(`Testdata descriptor ${id} is not available`);
  act(() => button.click());
}
async function confirmGuardNavigation() {
  const button = host.querySelector<HTMLButtonElement>('[role="dialog"] button:last-child');
  if (!button) throw new Error('Route discard confirmation is not available');
  await act(async () => button.click());
  await flushRoute();
}
async function confirmAction() {
  await flush();
  const button = host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child');
  if (!button) throw new Error('Confirmation action is not available');
  await act(async () => button.click());
  await flush();
}

function Harness({ api, initialEnvironment = '', descriptorId, registered = true, onOpenPackageConfiguration, canManagePackageSource = true }: {
  api: ExplorerApi; initialEnvironment?: string; descriptorId?: string; registered?: boolean; onOpenPackageConfiguration?: () => void; canManagePackageSource?: boolean;
}) {
  const [environment, setEnvironment] = useState(initialEnvironment);
  const [selectedDescriptor, setSelectedDescriptor] = useState(descriptorId);
  return <PackageTestdataWorkspace api={api} packageId="payments" packageLabel="Payments" registrationState="ready" registered={registered}
    environment={environment} descriptorId={selectedDescriptor} onEnvironmentChange={setEnvironment}
    onDescriptorChange={setSelectedDescriptor} onBackToResources={vi.fn()} onOpenResource={vi.fn()} onOpenPackageConfiguration={onOpenPackageConfiguration || vi.fn()}
    canManagePackageSource={canManagePackageSource} />;
}

function GuardedHarness({ api }: { api: ExplorerApi }) {
  const navigation = useRouteState();
  const route = navigation.route.kind === 'package' && navigation.route.view === 'testdata' ? navigation.route
    : { kind: 'package' as const, packageId: 'payments', view: 'testdata' as const, environment: 'UAT' };
  const onDescriptorChange = useCallback((testdataDescriptorId?: string) =>
    navigation.navigate({ ...route, testdataDescriptorId }), [navigation.navigate, route]);
  return <NavigationGuardContext.Provider value={navigation}>
    <PackageTestdataWorkspace api={api} packageId="payments" packageLabel="Payments" registrationState="ready" registered
      environment={route.environment || ''} descriptorId={route.testdataDescriptorId} onEnvironmentChange={environment => navigation.navigate({ ...route, environment })}
      onDescriptorChange={onDescriptorChange} onBackToResources={() => navigation.navigate({ kind: 'package', packageId: 'payments' })}
      onOpenResource={vi.fn()} onOpenPackageConfiguration={() => navigation.navigate({ kind: 'configuration', packageId: 'payments' })}
      canManagePackageSource registerUnsavedChanges={navigation.registerUnsavedChanges} />
    {navigation.pendingNavigation && <div role="dialog" aria-label="Unsaved work confirmation">
      {navigation.pendingNavigationError && <p role="alert">{navigation.pendingNavigationError}</p>}
      <button type="button" onClick={navigation.cancelNavigation}>Cancel</button>
      <button type="button" onClick={navigation.confirmNavigation} disabled={navigation.pendingNavigationBusy}>Confirm discard</button>
    </div>}
    <output aria-label="Current route">{formatRoute(navigation.route)}</output>
  </NavigationGuardContext.Provider>;
}

function setInitialGuardedRoute(descriptor?: string) {
  window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${formatRoute({
    kind: 'package', packageId: 'payments', view: 'testdata', environment: 'UAT', ...(descriptor ? { testdataDescriptorId: descriptor } : {}),
  })}`);
}

async function flushRoute() {
  await flush();
  await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); });
  await flush();
}

function guardedCatalogApi() {
  let ids = ['descriptor-a', 'descriptor-b'];
  const sourceText = (id: string) => `schemaVersion: att-testdata/v1.0\nid: ${id}\nrecords: [{id: BASE}]\n`;
  const stageSourceChangeSet = vi.fn(async (_domain: unknown, _scope: unknown, baseSourceRevision: string, registryRevision: string,
    operations: Array<{ operation: string; path: string; content?: string }>) => ({
      changeSetId: 'cs-testdata-a', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state: 'reviewed',
      baseSourceRevision, registryRevision, changedFiles: operations.map(operation => ({
        operation: operation.operation, path: operation.path, contentDiff: ['@@ -1 +1 @@', `-${sourceText('descriptor-a').trim()}`, `+${operation.content?.trim() || ''}`],
      })),
    }));
  const sourceChangeSetAction = vi.fn(async (_domain: unknown, _scope: unknown, changeSetId: string, action: string) => ({
    discarded: action === 'discard', changeSetId,
  }));
  const api = {
    getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'UAT', state: 'active' as const }] })),
    getEffectiveConfiguration: vi.fn(async () => ({ total: ids.length, environment: 'UAT', sections: [{ id: 'testdata', entries: ids.map(id => ({
      id, source: `testdata/${id}.yaml`, fields: { 'selection.strategy': { state: 'visible' as const, value: 'sequential' } },
    })) }] })),
    getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    getManagedPackageAuthoringSource: vi.fn(async (_packageId: string, query: { resourceId?: string }) => {
      const id = query.resourceId || 'descriptor-a';
      return { available: true, present: true, logicalPath: `testdata/${id}.yaml`, format: 'yaml', text: sourceText(id),
        sourceEtag: `etag-${id}`, baseSourceRevision: `base-${id}`, registryRevision: 'registry-1' };
    }),
    stageSourceChangeSet,
    sourceChangeSetAction,
  } as unknown as ExplorerApi;
  return { api, stageSourceChangeSet, sourceChangeSetAction, removeDescriptorA: () => { ids = ['descriptor-b']; }, sourceText };
}

describe('PackageTestdataWorkspace', () => {
  it('keeps unsaved Testdata text when a guarded descriptor switch is cancelled and resets only after confirmation', async () => {
    const { api } = guardedCatalogApi();
    const edited = 'schemaVersion: att-testdata/v1.0\nid: descriptor-a\nrecords: [{id: EXACT-UNSAVED-BYTES}]\n';
    setInitialGuardedRoute();
    await act(async () => root.render(<GuardedHarness api={api} />)); await flushRoute(); await flushRoute();
    clickButton('Edit');
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('The default descriptor source editor was not rendered');
    setInputValue(editor, edited);

    clickDescriptor('descriptor-b');
    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[role="dialog"] button:first-child')?.click());
    await flushRoute();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]')?.value).toBe(edited);
    expect(host.querySelector('output[aria-label="Current route"]')?.textContent).not.toContain('descriptor=descriptor-b');

    clickDescriptor('descriptor-b');
    await confirmGuardNavigation();
    expect(host.querySelector('output[aria-label="Current route"]')?.textContent).toContain('descriptor=descriptor-b');
    expect(host.textContent).toContain('testdata/descriptor-b.yaml');
    expect(host.textContent).not.toContain('EXACT-UNSAVED-BYTES');
    expect(api.getManagedPackageAuthoringSource).toHaveBeenCalledWith('payments', { kind: 'testdata', resourceId: 'descriptor-b', environment: 'UAT' }, expect.any(AbortSignal));
  });

  it('treats Testdata search as a list filter and preserves the active dirty descriptor', async () => {
    const { api } = guardedCatalogApi();
    const edited = 'schemaVersion: att-testdata/v1.0\nid: descriptor-a\nrecords: [{id: SEARCH-CANCEL-PRESERVES}]\n';
    setInitialGuardedRoute();
    await act(async () => root.render(<GuardedHarness api={api} />)); await flushRoute(); await flushRoute();
    clickButton('Edit');
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('The default descriptor source editor was not rendered');
    setInputValue(editor, edited);

    await act(async () => setSearchValue('descriptor-b'));
    await flush(); await flush();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(host.querySelector('output[aria-label="Current route"]')?.textContent).not.toContain('descriptor=descriptor-b');
    expect(host.querySelector<HTMLInputElement>('input[aria-label="Search Testdata descriptors"]')?.value).toBe('descriptor-b');
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]')?.value).toBe(edited);
    expect(host.textContent).toContain('descriptor-a');
    expect(host.textContent).toContain('descriptor-b');

    clickDescriptor('descriptor-b');
    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[role="dialog"] button:first-child')?.click());
    await flushRoute();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]')?.value).toBe(edited);
  });

  it('retains a removed dirty descriptor and discards its staged Change Set once after confirmed navigation', async () => {
    const { api, sourceChangeSetAction, stageSourceChangeSet, removeDescriptorA } = guardedCatalogApi();
    const edited = 'schemaVersion: att-testdata/v1.0\nid: descriptor-a\nrecords: [{id: EXACT-STAGED-BYTES}]\n';
    setInitialGuardedRoute();
    await act(async () => root.render(<GuardedHarness api={api} />)); await flushRoute(); await flushRoute();
    clickButton('Edit');
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('The default descriptor source editor was not rendered');
    setInputValue(editor, edited);
    clickButton('Add current file to candidate'); await confirmAction();
    clickButton('Stage candidate (1 files)'); await flush(); await flush();
    expect(stageSourceChangeSet).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-descriptor-a', 'registry-1', [
      { operation: 'replace', path: 'testdata/descriptor-a.yaml', content: edited },
    ], expect.any(AbortSignal));
    expect(host.textContent).toContain('Draft cs-testdata-a');
    expect(host.textContent).toContain('EXACT-STAGED-BYTES');

    removeDescriptorA();
    clickButton('Refresh Testdata');
    await flush(); await flush(); await flush(); await flushRoute();
    expect(host.textContent).toContain('Descriptor descriptor-a is no longer present in the refreshed catalog.');
    expect(host.textContent).toContain('Draft cs-testdata-a');
    expect(host.textContent).toContain('EXACT-STAGED-BYTES');
    const publish = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === 'Publish');
    expect(publish?.disabled).toBe(true);

    clickDescriptor('descriptor-b');
    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[role="dialog"] button:first-child')?.click());
    await flushRoute();
    expect(host.textContent).toContain('Draft cs-testdata-a');
    expect(host.textContent).toContain('EXACT-STAGED-BYTES');
    expect(sourceChangeSetAction).not.toHaveBeenCalled();

    clickDescriptor('descriptor-b');
    await confirmGuardNavigation();
    expect(sourceChangeSetAction).toHaveBeenCalledTimes(1);
    expect(sourceChangeSetAction.mock.calls[0][2]).toBe('cs-testdata-a');
    expect(sourceChangeSetAction.mock.calls[0][3]).toBe('discard');
    expect(host.querySelector('output[aria-label="Current route"]')?.textContent).toContain('descriptor=descriptor-b');
    expect(host.textContent).not.toContain('Draft cs-testdata-a');
    expect(host.textContent).not.toContain('EXACT-STAGED-BYTES');
  });

  it('keeps Testdata browsing available while withholding owner-only editing from non-owners', async () => {
    const managedSource = vi.fn(async () => ({
      available: true, present: true, logicalPath: 'testdata/accounts.yaml', format: 'yaml', text: 'records: []',
      sourceEtag: 'owner-etag', baseSourceRevision: 'owner-revision',
    }));
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({
        sections: [{ id: 'testdata', entries: [{ id: 'accounts', source: 'testdata/accounts.yaml' }] }],
      })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'accounts', offset: 0, limit: 50, total: 0, records: [] })),
      getManagedPackageAuthoringSource: managedSource,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} descriptorId="accounts" canManagePackageSource={false} />));
    await flush();
    expect(host.textContent).toContain('accounts');
    expect(host.textContent).not.toContain('Create Testdata descriptor');
    expect(host.querySelector('.att-source-authoring')).toBeNull();
    expect(managedSource).not.toHaveBeenCalled();
  });

  it('creates and publishes a Testdata descriptor with its Manifest registration and overlay in one candidate', async () => {
    let published = false;
    const draft = { changeSetId: 'cs-testdata', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state: 'draft',
      baseSourceRevision: 'base-1', registryRevision: 'registry-1', changedFiles: [] };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(typeof input === 'string' ? input : input instanceof Request ? input.url : String(input));
      const action = url.pathname.split('/').at(-1);
      let result: unknown;
      if (action === 'validate') result = { ...draft, state: 'validated' };
      else if (action === 'review') result = { ...draft, state: 'reviewed' };
      else {
        published = true;
        result = { published: true, domain: 'PACKAGE_SOURCE', scopeId: 'payments', changeSetId: draft.changeSetId,
          beforeRevision: 'base-1', revision: 'published-testdata-2' };
      }
      return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);
    const actions = vi.spyOn(sharedApi, 'sourceChangeSetAction');
    const stage = vi.fn(async (_domain: unknown, _scope: unknown, _base: unknown, _registry: unknown, _operations: unknown[]) => draft);
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async () => ({ sections: [{ id: 'testdata', entries: published
        ? [{ id: 'new-shipments', source: 'testdata/new-shipments.yaml' }] : [] }] })),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
      getManagedPackageAuthoringSource: vi.fn(async () => ({ available: true, present: true, logicalPath: 'package.yaml', format: 'yaml',
        text: 'schemaVersion: att-package/v2\ntestdata:\n  descriptors: []\n', sourceEtag: 'etag-1', revision: 'revision-1', baseSourceRevision: 'base-1', registryRevision: 'registry-1' })),
      stageSourceChangeSet: stage,
      sourceChangeSetAction: sharedApi.sourceChangeSetAction,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} />)); await flush();
    expect(host.textContent).toContain('Create Testdata descriptor');
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Create Testdata descriptor')?.click()); await flush();
    setInputValue(host.querySelector<HTMLInputElement>('input[aria-label="New Testdata ID"]')!, 'new-shipments');
    setInputValue(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Testdata JSON records"]')!, '[{ "shipmentId": "S-001" }]');
    setInputValue(host.querySelector<HTMLSelectElement>('select[aria-label="Testdata selection strategy"]')!, 'roundRobin');
    setInputValue(host.querySelector<HTMLSelectElement>('select[aria-label="Testdata exhaustion policy"]')!, 'recycle');
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Prepare atomic candidate')?.click()); await flush(); await flush();
    expect(host.textContent).toContain('testdata/new-shipments.yaml');
    expect(host.textContent).toContain('package.yaml');
    await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Stage candidate (2 files)')?.click()); await flush();
    expect(stage).toHaveBeenCalledTimes(1);
    const operations = stage.mock.calls[0][4] as Array<{ operation: string; path: string; content?: string }>;
    expect(operations).toHaveLength(2);
    expect(operations.map(operation => [operation.operation, operation.path])).toEqual([
      ['create', 'testdata/new-shipments.yaml'], ['replace', 'package.yaml'],
    ]);
    expect(operations[0].content).toContain('schemaVersion: att-testdata/v1.0');
    expect(operations[0].content).toContain('strategy: roundRobin');
    expect(operations[0].content).toContain('exhaustion: recycle');
    expect(operations[1].content).toContain('testdata/new-shipments.yaml');
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Validate')?.click()); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Review changed files')?.click()); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Publish')?.click()); await flush();
    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child')?.click()); await flush(); await flush();
    expect(actions.mock.calls.map(call => call[3])).toEqual(['validate', 'review', 'publish']);
    expect(vi.mocked(api.getEffectiveConfiguration).mock.calls.length).toBeGreaterThan(1);
    expect(host.textContent).toContain('new-shipments');
  });

  it('edits the descriptor selected from the UAT Testdata overlay through Package source authoring', async () => {
    const stage = vi.fn(async () => ({ changeSetId: 'cs-uat-edit', domain: 'PACKAGE_SOURCE' as const,
      scopeId: 'payments', state: 'draft', baseSourceRevision: 'base-uat-1', registryRevision: 'registry-1', changedFiles: [] }));
    const managedSource = vi.fn(async () => ({ available: true, present: true, logicalPath: 'testdata/uat-accounts.yaml',
      format: 'yaml', text: 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-1}]\n',
      sourceEtag: 'etag-uat', baseSourceRevision: 'base-uat-1', registryRevision: 'registry-1' }));
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'UAT', state: 'active' as const }] })),
      getEffectiveConfiguration: vi.fn(async () => ({ environment: 'UAT', sections: [{ id: 'testdata', entries: [
        { id: 'accounts', source: 'testdata/uat-accounts.yaml' },
      ] }] })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'accounts', offset: 0, limit: 50, total: 0, records: [] })),
      getManagedPackageAuthoringSource: managedSource,
      stageSourceChangeSet: stage,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} initialEnvironment="UAT" descriptorId="accounts" />));
    await flush(); await flush();

    expect(host.textContent).toContain('Effective environment: UAT');
    expect(host.textContent).toContain('manage authorized Package sources');
    expect(managedSource).toHaveBeenCalledWith('payments', { kind: 'testdata', resourceId: 'accounts', environment: 'UAT' }, expect.any(AbortSignal));
    clickButton('Edit');
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('The selected UAT Testdata source editor was not rendered');
    setInputValue(editor, 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-2}]\n');
    clickButton('Add current file to candidate');
    await confirmAction();
    clickButton('Stage candidate (1 files)');
    await flush();

    expect(stage).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-uat-1', 'registry-1', [
      { operation: 'replace', path: 'testdata/uat-accounts.yaml',
        content: 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-2}]\n' },
    ], expect.any(AbortSignal));
    expect(host.textContent).toContain('Draft cs-uat-edit saved');
  });

  it('deletes a UAT Testdata descriptor with Manifest and LOAD references in the same candidate', async () => {
    const draft = { changeSetId: 'cs-uat-delete', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state: 'draft',
      baseSourceRevision: 'base-uat-2', registryRevision: 'registry-2', changedFiles: [] };
    const stage = vi.fn(async () => draft);
    const managedSource = vi.fn(async () => ({ available: true, present: true, logicalPath: 'testdata/uat-accounts.yaml',
      format: 'yaml', text: 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-1}]\n',
      sourceEtag: 'etag-uat', baseSourceRevision: 'base-uat-2', registryRevision: 'registry-2' }));
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [{ name: 'UAT', state: 'active' as const }] })),
      getEffectiveConfiguration: vi.fn(async () => ({ environment: 'UAT', sections: [{ id: 'testdata', entries: [
        { id: 'accounts', source: 'testdata/uat-accounts.yaml' },
      ] }] })),
      getTestdataRecords: vi.fn(async () => ({ descriptorId: 'accounts', offset: 0, limit: 50, total: 0, records: [] })),
      getManagedPackageAuthoringSource: managedSource,
      stageSourceChangeSet: stage,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<Harness api={api} initialEnvironment="UAT" descriptorId="accounts" />));
    await flush(); await flush();

    clickButton('Delete');
    await confirmAction();
    setInputValue(host.querySelector<HTMLSelectElement>('select[aria-label="Related file operation"]')!, 'replace');
    setInputValue(host.querySelector<HTMLInputElement>('input[aria-label="Related file path"]')!, 'package.yaml');
    setInputValue(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Related file content"]')!,
      'schemaVersion: att-package/v2\ntestdata:\n  descriptors: [testdata/accounts.yaml]\n  environments:\n    UAT: [testdata/uat-orders.yaml]\n');
    clickButton('Add related file');
    setInputValue(host.querySelector<HTMLSelectElement>('select[aria-label="Related file operation"]')!, 'replace');
    setInputValue(host.querySelector<HTMLInputElement>('input[aria-label="Related file path"]')!, 'load/scenario.yaml');
    setInputValue(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Related file content"]')!,
      'schemaVersion: att-load/v1.6\ntestdata: []\nworkloads: []\n');
    clickButton('Add related file');
    clickButton('Stage candidate (3 files)');
    await flush();

    expect(stage).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-uat-2', 'registry-2', [
      { operation: 'delete', path: 'testdata/uat-accounts.yaml' },
      { operation: 'replace', path: 'package.yaml', content:
        'schemaVersion: att-package/v2\ntestdata:\n  descriptors: [testdata/accounts.yaml]\n  environments:\n    UAT: [testdata/uat-orders.yaml]\n' },
      { operation: 'replace', path: 'load/scenario.yaml', content:
        'schemaVersion: att-load/v1.6\ntestdata: []\nworkloads: []\n' },
    ], expect.any(AbortSignal));
    expect(host.textContent).toContain('Draft cs-uat-delete saved');
  });

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
    expect(vi.mocked(api.getEffectiveConfiguration).mock.calls
      .filter(call => call[1].query === 'fresh').map(call => call[1].offset)).toEqual([0]);
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

  it('resolves successive deep links from the new route query, not prior fuzzy results', async () => {
    const descriptors = [
      { id: 'accounts', source: 'testdata/accounts.yaml' },
      { id: 'pay', source: 'testdata/pay.yaml' },
      { id: 'pay-v2', source: 'testdata/pay-v2.yaml' },
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

    await renderRoute();
    await act(async () => setSearchValue('acc'));
    await flush();
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'acc', offset: 0, limit: 50,
    }, expect.any(AbortSignal));

    await renderRoute('pay');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'pay', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(host.textContent).toContain('2 Testdata descriptors match this search');

    await renderRoute('pay-v2');
    expect(host.querySelector<HTMLInputElement>('input[aria-label="Search Testdata descriptors"]')?.value).toBe('acc');
    expect(api.getEffectiveConfiguration).toHaveBeenLastCalledWith('payments', {
      environment: undefined, section: 'testdata', query: 'pay-v2', offset: 0, limit: 50,
    }, expect.any(AbortSignal));
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'pay-v2',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));
    expect(host.textContent).toContain('testdata/pay-v2.yaml');
    expect(host.textContent).not.toContain('Testdata descriptor pay-v2 is unavailable');
  });

  it('pages a deep-link fuzzy lookup until its exact descriptor appears', async () => {
    const descriptors = [
      ...Array.from({ length: 60 }, (_, index) => ({
        id: `a-variant-${String(index).padStart(2, '0')}`,
        source: `testdata/accounts-${String(index).padStart(2, '0')}.yaml`,
      })),
      { id: 'accounts', source: 'testdata/accounts.yaml' },
    ].sort((left, right) => left.id.localeCompare(right.id, undefined, { sensitivity: 'base' }));
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { query?: string; offset?: number; limit?: number }) => {
        const normalizedQuery = query.query?.toLocaleLowerCase();
        const matches = descriptors.filter(item => !normalizedQuery
          || item.id.toLocaleLowerCase().includes(normalizedQuery)
          || item.source.toLocaleLowerCase().includes(normalizedQuery));
        const offset = query.offset ?? 0;
        const page = matches.slice(offset, offset + (query.limit ?? 50));
        const nextOffset = offset + page.length < matches.length ? offset + page.length : null;
        return { total: matches.length, nextOffset, sections: [{ id: 'testdata', entries: page }] };
      }),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<RoutedHarness api={api} descriptorId="accounts" />));
    await flush(); await flush();

    expect(vi.mocked(api.getEffectiveConfiguration).mock.calls.map(call => call[1].offset)).toEqual([0, 50]);
    expect(api.getTestdataRecords).toHaveBeenLastCalledWith('payments', 'accounts',
      { environment: undefined, offset: 0, limit: 50, previewGenerated: false }, expect.any(AbortSignal));
    expect(host.textContent).toContain('testdata/accounts.yaml');
    expect(host.textContent).not.toContain('Testdata descriptor accounts is unavailable');
  });

  it('re-resolves a selected later-page descriptor after refreshing the catalog', async () => {
    const descriptors = Array.from({ length: 100 }, (_, index) => ({
      id: `data-${String(index).padStart(3, '0')}`, source: `testdata/data-${String(index).padStart(3, '0')}.yaml`,
    }));
    const selected = descriptors[75];
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { query?: string; offset?: number; limit?: number }) => {
        if (query.query === selected.id) return { total: 1, sections: [{ id: 'testdata', entries: [selected] }] };
        const offset = query.offset ?? 0;
        const page = descriptors.slice(offset, offset + (query.limit ?? 50));
        const nextOffset = offset + page.length < descriptors.length ? offset + page.length : null;
        return { total: descriptors.length, nextOffset, sections: [{ id: 'testdata', entries: page }] };
      }),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<SelectedDescriptorHarness api={api} />)); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load more descriptors')?.click()); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('nav.att-testdata-catalog button')].find(button => button.textContent?.includes(selected.id))?.click()); await flush();
    expect(host.textContent).toContain(selected.source);

    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Refresh Testdata')?.click());
    await flush(); await flush();

    expect(vi.mocked(api.getEffectiveConfiguration).mock.calls.some(call => call[1].query === selected.id && call[1].offset === 0)).toBe(true);
    expect(host.textContent).toContain(selected.source);
    expect(host.textContent).not.toContain(`Testdata descriptor ${selected.id} is unavailable`);
  });

  it('re-resolves the selected descriptor in a new environment when it is on a later result page', async () => {
    const uatDescriptors = Array.from({ length: 100 }, (_, index) => ({
      id: `data-${String(index).padStart(3, '0')}`, source: `testdata/data-${String(index).padStart(3, '0')}.yaml`,
    }));
    const selected = uatDescriptors[75];
    const sitMatches = [
      ...Array.from({ length: 60 }, (_, index) => ({
        id: `a-variant-${String(index).padStart(2, '0')}`, source: `testdata/${selected.id}-${String(index).padStart(2, '0')}.yaml`,
      })),
      selected,
    ].sort((left, right) => left.id.localeCompare(right.id, undefined, { sensitivity: 'base' }));
    const page = (items: typeof sitMatches, offset: number, limit: number) => {
      const entries = items.slice(offset, offset + limit);
      return { total: items.length, nextOffset: offset + entries.length < items.length ? offset + entries.length : null,
        sections: [{ id: 'testdata', entries }] };
    };
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [
        { name: 'UAT', state: 'active' as const, default: true }, { name: 'SIT', state: 'active' as const },
      ] })),
      getEffectiveConfiguration: vi.fn(async (_packageId: string, query: { environment?: string; query?: string; offset?: number; limit?: number }) => {
        const offset = query.offset ?? 0;
        const limit = query.limit ?? 50;
        if (query.environment === 'SIT' && query.query === selected.id) {
          const matches = sitMatches.filter(item => item.id.toLocaleLowerCase().includes(selected.id)
            || item.source.toLocaleLowerCase().includes(selected.id));
          return page(matches, offset, limit);
        }
        return page(uatDescriptors, offset, limit);
      }),
      getTestdataRecords: vi.fn(async (_packageId: string, descriptorId: string) => ({ descriptorId, offset: 0, limit: 50, total: 0, records: [] })),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<SelectedDescriptorHarness api={api} environment="UAT" />)); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Load more descriptors')?.click()); await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('nav.att-testdata-catalog button')].find(button => button.textContent?.includes(selected.id))?.click()); await flush();
    expect(host.textContent).toContain(selected.source);

    await act(async () => root.render(<SelectedDescriptorHarness api={api} environment="SIT" />));
    await flush(); await flush();

    const sitLookupOffsets = vi.mocked(api.getEffectiveConfiguration).mock.calls
      .filter(call => call[1].environment === 'SIT' && call[1].query === selected.id).map(call => call[1].offset);
    expect(sitLookupOffsets).toEqual([0, 50]);
    expect(host.textContent).toContain(selected.source);
    expect(host.textContent).not.toContain(`Testdata descriptor ${selected.id} is unavailable`);
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
