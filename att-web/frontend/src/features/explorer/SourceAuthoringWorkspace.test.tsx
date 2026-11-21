// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { ApiError } from '../../types';
import { ChangedFiles, SourceAuthoringWorkspace } from './SourceAuthoringWorkspace';
import type { ExplorerApi } from './types';
import type { SourceImpactReport, SourceOperation } from './sourceManagement';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, readOnly = true, onChange, maxCharacters = 200_000 }: { value: string; label: string; readOnly?: boolean; onChange?: (value: string) => void; maxCharacters?: number }) => {
    const displayed = value.length > maxCharacters ? value.slice(0, maxCharacters) : value;
    return <textarea aria-label={label} value={displayed} readOnly={readOnly} onChange={event => onChange?.(event.currentTarget.value)} />;
  },
  detectCodeLanguage: () => 'yaml',
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals();
});

async function settle() {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); });
    const confirm = host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child');
    if (!confirm) break;
    await act(async () => { confirm.click(); await Promise.resolve(); });
  }
}
function button(name: string): HTMLButtonElement {
  const found = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === name);
  if (!found) throw new Error(`Button not found: ${name}`);
  return found;
}
function click(element: HTMLElement) { act(() => element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))); }
function setInputValue(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  act(() => {
    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set;
    setter?.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
function acknowledgeCentralImpact(actor = 'admin') {
  const identity = host.querySelector<HTMLInputElement>('input[aria-label="Authenticated administrator ID"]');
  if (identity) setInputValue(identity, actor);
  const checkbox = host.querySelector<HTMLInputElement>('input[aria-label="Acknowledge current Config impact"]');
  if (!checkbox) throw new Error('Impact acknowledgement checkbox not found');
  act(() => checkbox.click());
}

function source(path = 'templates/example/template.yaml') {
  return { available: true, present: true, logicalPath: path, format: 'yaml', text: 'enabled: false\n',
    sourceEtag: 'etag-1', revision: 'revision-1', baseSourceRevision: 'base-1', registryRevision: 'registry-1' };
}
function workspaceContext(setWorkspaceEditorLock: (locked: boolean) => void): AppShellContextValue {
  return {
    selectedPackage: 'payments', selectedEnvironment: 'SIT', themePreference: 'system', uiLocale: 'en', uiLocalePreference: 'en',
    currentUser: null, accessCapabilities: { operate: true, accountManage: true, systemSettingsWrite: true, configurationAdmin: true },
    preferenceStatus: 'idle', canRevealConfigurationFields: false, setWorkspaceEditorLock,
    onPackageChange: vi.fn(), onEnvironmentChange: vi.fn(), onThemePreferenceChange: vi.fn(), onUiLocaleChange: vi.fn(), onJobAccepted: vi.fn(),
  };
}
function draft(state: 'draft' | 'validated' | 'reviewed' = 'draft') {
  return { changeSetId: 'cs-1', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state,
    baseSourceRevision: 'base-1', registryRevision: 'registry-1', changedFiles: [] };
}
function centralImpact(decision: 'COMPATIBLE' | 'REQUIRES_REVIEW' | 'BLOCKED' = 'COMPATIBLE', acknowledgementRequired = false): SourceImpactReport {
  return {
    schemaVersion: 'att-config-impact-report/v1', providerState: 'AVAILABLE', coverage: 'COMPLETE', decision, acknowledgementRequired,
    freshness: {
      schemaVersion: 'att-config-impact-freshness/v1', draftId: 'cs-1', draftFingerprint: 'a'.repeat(64),
      baseConfig: { kind: 'CONFIG_SOURCE', scopeId: 'central-runtime-config', revision: 'config-1' },
      candidateConfig: { kind: 'CONFIG_SOURCE', scopeId: 'central-runtime-config', revision: 'config-candidate-1' },
      packageRegistry: { kind: 'PACKAGE_REGISTRATION', scopeId: 'packages', revision: 'registry-1' },
      packageSources: [], manifests: [], engineSchema: { kind: 'ENGINE_SCHEMA', scopeId: 'runtime', revision: 'schema-1' }, scheduleSnapshots: [],
    },
    affectedPackages: [], schedules: { items: [], totalEstimate: 0, truncated: false },
  };
}

describe('SourceAuthoringWorkspace', () => {
  it('reviews authorized source history and stages Restore as a draft against current revisions', async () => {
    const beforeSnapshotId = 'a'.repeat(64);
    const afterSnapshotId = 'b'.repeat(64);
    const path = 'templates/payment/template.yaml';
    const getHistory = vi.fn(async () => ({ schemaVersion: 'att-source-history/v1', domain: 'PACKAGE_SOURCE' as const,
      scopeId: 'payments', items: [{ transactionId: 'tx-1', domain: 'PACKAGE_SOURCE' as const,
        createdAt: '2026-11-18T00:00:00Z', action: 'SOURCE_CHANGE_SET_PUBLISH', outcome: 'COMMITTED', author: 'owner',
        beforeSnapshotId, afterSnapshotId, revisionsAvailable: true, changedResources: [path] }],
      offset: 0, limit: 20, total: 1, hasMore: false, currentSourceRevision: 'base-1', registryRevision: 'registry-1' }));
    const getDiff = vi.fn(async (_domain: unknown, _scope: unknown, _before: string, _after: string, selectedPath?: string) => ({
      schemaVersion: 'att-source-history-diff/v1', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments',
      beforeSnapshotId, afterSnapshotId, offset: 0, limit: 20, total: 1, hasMore: false,
      items: selectedPath ? [{ path, change: 'modified', beforeBytes: 10, afterBytes: 11,
        contentAvailable: true, beforeContent: 'enabled: false\n', afterContent: 'enabled: true\n',
        contentDiff: ['-enabled: false', '+enabled: true'] }] : [{ path, change: 'modified', beforeBytes: 10, afterBytes: 11 }],
    }));
    const stageRestore = vi.fn(async () => draft());
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()),
      getSourceHistory: getHistory,
      getSourceHistoryDiff: getDiff,
      stageSourceRestore: stageRestore,
      sourceChangeSetAction: vi.fn(async () => draft('validated')),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle();
    click(button('History / Restore')); await settle();
    expect(getHistory).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 0, 20, expect.any(AbortSignal));
    click(button('View changes')); await settle();
    expect(getDiff).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', beforeSnapshotId, afterSnapshotId,
      undefined, expect.any(AbortSignal), 0, 20);
    const pathButton = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes(path));
    if (!pathButton) throw new Error('History diff path was not rendered.');
    click(pathButton); await settle();
    expect(getDiff).toHaveBeenLastCalledWith('PACKAGE_SOURCE', 'payments', beforeSnapshotId, afterSnapshotId,
      path, expect.any(AbortSignal), 0, 20);
    expect(host.textContent).toContain('enabled: false');
    expect(host.textContent).toContain('enabled: true');
    click(button('Stage restore as draft')); await settle();
    expect(stageRestore).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', afterSnapshotId, 'base-1', 'registry-1', expect.any(AbortSignal));
    expect(host.textContent).toContain('Historical source restore is staged as draft cs-1');
    expect(button('Validate')).toBeDefined();
    expect(button('Publish').disabled).toBe(true);
  });

  it('loads every page of a large historical diff', async () => {
    const beforeSnapshotId = 'e'.repeat(64);
    const afterSnapshotId = 'f'.repeat(64);
    const paths = Array.from({ length: 31 }, (_, index) => `templates/page-${String(index + 1).padStart(2, '0')}.yaml`);
    const getHistory = vi.fn(async () => ({ schemaVersion: 'att-source-history/v1', domain: 'PACKAGE_SOURCE' as const,
      scopeId: 'payments', items: [{ transactionId: 'tx-many', domain: 'PACKAGE_SOURCE' as const,
        createdAt: '2026-11-18T00:00:00Z', action: 'SOURCE_CHANGE_SET_PUBLISH', outcome: 'COMMITTED', author: 'owner',
        beforeSnapshotId, afterSnapshotId, revisionsAvailable: true, changedResources: paths }],
      offset: 0, limit: 20, total: 1, hasMore: false, currentSourceRevision: 'base-1', registryRevision: 'registry-1' }));
    const getDiff = vi.fn(async (_domain: unknown, _scope: unknown, _before: string, _after: string,
      _selectedPath?: string, _signal?: AbortSignal, offset = 0, limit = 20) => ({
      schemaVersion: 'att-source-history-diff/v1', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments',
      beforeSnapshotId, afterSnapshotId, offset, limit, total: paths.length, hasMore: offset + limit < paths.length,
      items: paths.slice(offset, offset + limit).map(path => ({ path, change: 'modified' as const,
        beforeBytes: 1, afterBytes: 2, contentAvailable: false })),
    }));
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()), getSourceHistory: getHistory,
      getSourceHistoryDiff: getDiff,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle();
    click(button('History / Restore')); await settle();
    click(button('View changes')); await settle();
    expect(host.textContent).toContain('Showing 20 of 31 changed files.');
    expect(button('Load more changed files')).toBeDefined();
    click(button('Load more changed files')); await settle();
    expect(getDiff).toHaveBeenNthCalledWith(1, 'PACKAGE_SOURCE', 'payments', beforeSnapshotId, afterSnapshotId,
      undefined, expect.any(AbortSignal), 0, 20);
    expect(getDiff).toHaveBeenNthCalledWith(2, 'PACKAGE_SOURCE', 'payments', beforeSnapshotId, afterSnapshotId,
      undefined, expect.any(AbortSignal), 20, 20);
    expect(host.textContent).toContain('Showing 31 of 31 changed files.');
    expect([...host.querySelectorAll('button')].some(item => item.textContent?.includes('Load more changed files'))).toBe(false);
    for (const path of paths) expect(host.textContent).toContain(path);
  });

  it('requires fresh Config Impact after staging a Central history restore', async () => {
    const beforeSnapshotId = 'c'.repeat(64);
    const afterSnapshotId = 'd'.repeat(64);
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'),
      domain: 'CENTRAL_RUNTIME_CONFIG' as const, scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string,
      _signal?: AbortSignal, _acknowledgement?: unknown) =>
      actionName === 'publish' ? { published: true, revision: 'config-published', activeRevision: 'config-active', auditConfirmed: true }
        : centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    const stageRestore = vi.fn(async () => centralDraft('draft'));
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      getSourceHistory: vi.fn(async () => ({ schemaVersion: 'att-source-history/v1', domain: 'CENTRAL_RUNTIME_CONFIG' as const,
        scopeId: 'central-runtime-config', items: [{ transactionId: 'central-tx', domain: 'CENTRAL_RUNTIME_CONFIG' as const,
          createdAt: '2026-11-18T00:00:00Z', action: 'CENTRAL_CONFIG_PUBLISH', outcome: 'COMMITTED',
          beforeSnapshotId, afterSnapshotId, revisionsAvailable: true, changedResources: ['config/config.yaml'] }],
        offset: 0, limit: 20, total: 1, hasMore: false, currentSourceRevision: 'config-base-1' })),
      stageSourceRestore: stageRestore,
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn(async () => centralImpact()),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle();
    click(button('History / Restore')); await settle();
    click(button('Stage restore as draft')); await settle();
    expect(stageRestore).toHaveBeenCalledWith('CENTRAL_RUNTIME_CONFIG', 'central-runtime-config', afterSnapshotId,
      'config-base-1', undefined, expect.any(AbortSignal));
    expect(action).not.toHaveBeenCalled();
    click(button('Validate')); await settle();
    click(button('Review changed files / Impact')); await settle();
    expect(api.getCentralConfigImpact).toHaveBeenCalledWith('cs-1', undefined, expect.any(AbortSignal));
    expect(action.mock.calls.map(call => call[3])).toEqual(['validate', 'review']);
    expect(button('Publish').disabled).toBe(false);
    click(button('Publish')); await settle();
    const publishCall = action.mock.calls.find(call => call[3] === 'publish');
    expect(publishCall?.[5]).toEqual(expect.objectContaining({ freshnessFingerprint: expect.any(String) }));
    expect(publishCall?.[5]).not.toHaveProperty('actor');
    expect(publishCall?.[5]).not.toHaveProperty('acknowledgementDigest');
  });

  it('shows actionable guidance when a history restore exceeds the operation limit', async () => {
    const beforeSnapshotId = '1'.repeat(64);
    const afterSnapshotId = '2'.repeat(64);
    const getHistory = vi.fn(async () => ({ schemaVersion: 'att-source-history/v1', domain: 'PACKAGE_SOURCE' as const,
      scopeId: 'payments', items: [{ transactionId: 'tx-large', domain: 'PACKAGE_SOURCE' as const,
        createdAt: '2026-11-18T00:00:00Z', action: 'SOURCE_CHANGE_SET_PUBLISH', outcome: 'COMMITTED', author: 'owner',
        beforeSnapshotId, afterSnapshotId, revisionsAvailable: true, changedResources: ['templates/a.yaml'] }],
      offset: 0, limit: 20, total: 1, hasMore: false, currentSourceRevision: 'base-1', registryRevision: 'registry-1' }));
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()), getSourceHistory: getHistory,
      stageSourceRestore: vi.fn(async () => { throw Object.assign(new Error('restore limit'),
        { status: 422, code: 'ATT-CHANGESET-RESTORE-LIMIT' }); }),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle(); click(button('History / Restore')); await settle();
    click(button('Stage restore as draft')); await settle();
    expect(host.textContent).toContain('exceeds the Server limit of 32 file operations');
    expect(host.textContent).toContain('did not publish or partially apply any files');
  });

  it('locks workspace navigation only while the source editor has unsaved work', async () => {
    const setWorkspaceEditorLock = vi.fn();
    const api = { getManagedPackageAuthoringSource: vi.fn(async () => source()) } as unknown as ExplorerApi;
    await act(async () => root.render(<AppShellContext.Provider value={workspaceContext(setWorkspaceEditorLock)}>
      <SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
        query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />
    </AppShellContext.Provider>));
    await settle();
    expect(setWorkspaceEditorLock).toHaveBeenLastCalledWith(false);
    click(button('Edit'));
    expect(setWorkspaceEditorLock).toHaveBeenLastCalledWith(false);
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n');
    expect(setWorkspaceEditorLock).toHaveBeenLastCalledWith(true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    click(button('Discard local changes'));
    await settle();
    expect(setWorkspaceEditorLock).toHaveBeenLastCalledWith(false);
  });

  it('stages a Package ZIP as a draft and keeps publication behind Validate, Review, and Publish', async () => {
    const stagedArchive = vi.fn(async () => draft());
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()),
      stagePackageSourceArchive: stagedArchive,
      sourceChangeSetAction: vi.fn(async () => draft('validated')),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' }, label: 'Template source' }} />));
    await settle();
    expect(button('Import Package ZIP').disabled).toBe(false);
    const input = host.querySelector<HTMLInputElement>('input[aria-label="Import Package ZIP"]');
    if (!input) throw new Error('Package ZIP input was not rendered.');
    const archive = new File([new Uint8Array([0x50, 0x4b, 0x03, 0x04])], 'package.zip', { type: 'application/zip' });
    Object.defineProperty(input, 'files', { configurable: true, value: [archive] });
    await act(async () => { input.dispatchEvent(new Event('change', { bubbles: true })); await Promise.resolve(); });
    await settle();
    expect(stagedArchive).toHaveBeenCalledWith('payments', archive, expect.any(AbortSignal));
    expect(host.textContent).toContain('Imported package.zip as draft cs-1');
    expect(host.textContent).toContain('published source remains unchanged');
    expect(button('Validate')).toBeDefined();
    expect(button('Publish').disabled).toBe(true);
  });

  it('clears authorized source and mutation controls when ZIP import is revoked', async () => {
    const secretSource = { ...source(), text: 'zip-revocation-secret: value\n' };
    const stageArchive = vi.fn(async () => { throw Object.assign(new Error('forbidden'), { status: 403 }); });
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => secretSource),
      stagePackageSourceArchive: stageArchive,
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle();
    const sourceView = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="View authorized source"]');
    expect(sourceView?.value).toContain('zip-revocation-secret');
    const input = host.querySelector<HTMLInputElement>('input[aria-label="Import Package ZIP"]');
    if (!input) throw new Error('Package ZIP input was not rendered.');
    const archive = new File([new Uint8Array([0x50, 0x4b, 0x03, 0x04])], 'revoked.zip', { type: 'application/zip' });
    Object.defineProperty(input, 'files', { configurable: true, value: [archive] });
    await act(async () => { input.dispatchEvent(new Event('change', { bubbles: true })); await Promise.resolve(); });
    await settle();

    expect(stageArchive).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('Package source editing is unavailable for this identity');
    expect(host.textContent).not.toContain('zip-revocation-secret');
    expect(host.querySelector('textarea[aria-label^="View authorized source"]')).toBeNull();
    expect(host.querySelector('textarea[aria-label^="Edit authorized source"]')).toBeNull();
    expect([...host.querySelectorAll('button')].some(item => ['Edit', 'Import Package ZIP', 'Save Draft'].includes(item.textContent?.trim() || ''))).toBe(false);
  });

  it('clears a staged Package draft when guarded navigation discard receives a revocation 403', async () => {
    const secretSource = { ...source(), text: 'discard-revocation-secret: value\n' };
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, operation: string) => {
      if (operation === 'validate') throw new ApiError('validation failed', { status: 422, diagnostics: [
        { code: 'PRIVATE_VALIDATION_DETAIL', severity: 'ERROR', logicalPath: 'templates/checkout/template.yaml', message: 'private validation detail' },
      ] });
      throw new ApiError('forbidden', { status: 403 });
    });
    let registeredDiscard: (() => boolean | Promise<boolean>) | undefined;
    const registerUnsavedChanges = vi.fn((_dirty: boolean, _description?: string,
      onConfirmDiscard?: () => boolean | Promise<boolean>) => {
      registeredDiscard = onConfirmDiscard;
      return () => {};
    });
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => secretSource),
      stageSourceChangeSet: vi.fn(async () => draft()),
      sourceChangeSetAction: action,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} registerUnsavedChanges={registerUnsavedChanges} />));
    await settle();
    const sourceView = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="View authorized source"]');
    expect(sourceView?.value).toContain('discard-revocation-secret');
    click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'discard-revocation-secret: candidate\n');
    click(button('Save Draft'));
    await settle();
    expect(host.textContent).toContain('Draft cs-1');
    click(button('Validate'));
    await settle();
    expect(host.textContent).toContain('private validation detail');

    const confirmDiscard = registeredDiscard;
    if (!confirmDiscard) throw new Error('Guarded draft-discard callback was not registered.');
    let discardResult: boolean | undefined;
    await act(async () => { discardResult = await confirmDiscard(); });
    await settle();

    expect(discardResult).toBe(false);
    expect(action).toHaveBeenCalledTimes(2);
    expect(action.mock.calls.map(call => call.slice(0, 4))).toEqual([
      ['PACKAGE_SOURCE', 'payments', 'cs-1', 'validate'],
      ['PACKAGE_SOURCE', 'payments', 'cs-1', 'discard'],
    ]);
    expect(host.textContent).toContain('Package source editing is unavailable for this identity');
    expect(host.querySelector('[aria-label="Source validation diagnostics"]')).toBeNull();
    expect(host.textContent).not.toContain('private validation detail');
    expect(host.textContent).not.toContain('Draft cs-1');
    expect([...host.querySelectorAll<HTMLTextAreaElement>('textarea')].map(item => item.value).join('\n')).not.toContain('discard-revocation-secret');
    expect(host.querySelector('textarea[aria-label^="View authorized source"]')).toBeNull();
    expect(host.querySelector('textarea[aria-label^="Edit authorized source"]')).toBeNull();
    expect([...host.querySelectorAll('button')].some(item => ['Edit', 'Discard Draft', 'Save Draft', 'Publish'].includes(item.textContent?.trim() || ''))).toBe(false);
  });

  it('shows shared-contract dependency paths, Action call sites, relationships and uncertainty', async () => {
    await act(async () => root.render(<ChangedFiles files={[{ operation: 'delete', path: 'templates/checkout/template.yaml', contentDiff: [
      '@@ -1 +1 @@', '-name: old', '+name: new'], inboundReferences: [
      { schemaVersion: 'att-source-dependency/v1', kind: 'PACKAGE_RESOURCE', packageId: 'payments', environment: 'UAT',
        logicalId: 'checkout', canonicalPath: 'flows/purchase.yaml', relationship: 'invokes', callSite: 'actions[2].template', certainty: 'KNOWN' },
      { schemaVersion: 'att-source-dependency/v1', kind: 'LOAD_OVERLAY', canonicalPath: null, callSite: 'LOAD: invoice-profile', certainty: 'UNKNOWN' },
    ] }, { operation: 'create', path: 'files/another.yaml' }]} />));
    expect(host.textContent).toContain('flows/purchase.yaml');
    expect(host.textContent).toContain('Action: actions[2].template');
    expect(host.textContent).toContain('invokes');
    expect(host.textContent).toContain('location uncertain');
    expect(host.textContent).not.toContain('Unknown descriptor');
    const diffSummary = [...host.querySelectorAll('summary')].find(item => item.textContent?.includes('unified diff'));
    if (!diffSummary) throw new Error('Unified diff disclosure is missing.');
    click(diffSummary); expect(host.querySelector('.att-source-diff-line.is-added code')?.textContent).toBe('+name: new');
    setInputValue(host.querySelector<HTMLInputElement>('input[aria-label="Filter changed files"]')!, 'another.yaml');
    expect(host.textContent).toContain('files/another.yaml');
    expect(host.textContent).not.toContain('templates/checkout/template.yaml');
  });

  it('stages a Template and related payload together and gates publication through validate and review', async () => {
    const packageSource = { schemaVersion: 'att-source-revision/v1', kind: 'PACKAGE_SOURCE', scopeId: 'payments', revision: 'package-source-1' };
    const executionRevision = { schemaVersion: 'att-source-revision/v1', kind: 'EXECUTION_REVISION', scopeId: 'payments@SIT', revision: 'execution-1' };
    const stagedDraft = { ...draft(), revisions: {
      packageSourceRevision: packageSource,
      businessSource: { ...packageSource, kind: 'BUSINESS_SOURCE' },
      executionRevisionDigest: executionRevision,
    } };
    const actions = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, action: string) => {
      if (action === 'validate') return draft('validated');
      if (action === 'review') return draft('reviewed');
      return { published: true, domain: 'PACKAGE_SOURCE', scopeId: 'payments', changeSetId: 'cs-1', beforeRevision: 'base-1', revision: 'published-2' };
    });
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()),
      stageSourceChangeSet: vi.fn(async () => stagedDraft),
      sourceChangeSetAction: actions,
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' }, label: 'Template source', allowFileOperations: true }} />));
    await settle();
    click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n');
    click(button('Add current file to candidate'));
    await settle();
    setInputValue(host.querySelector<HTMLInputElement>('input[aria-label="Related file path"]')!, 'templates/example/payload.json');
    setInputValue(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Related file content"]')!, '{"id":"{{id}}"}\n');
    click(button('Add related file'));
    click(button('Stage candidate (2 files)'));
    await settle();
    expect(api.stageSourceChangeSet).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-1', 'registry-1', [
      { operation: 'replace', path: 'templates/example/template.yaml', content: 'enabled: true\n' },
      { operation: 'create', path: 'templates/example/payload.json', content: '{"id":"{{id}}"}\n' },
    ], expect.any(AbortSignal));
    expect(host.textContent).toContain('Published source is unchanged');
    const revisionDetails = host.querySelector<HTMLDetailsElement>('details[aria-label="Advanced source revision provenance"]');
    expect(revisionDetails?.open).toBe(false);
    if (!revisionDetails) throw new Error('Advanced revision provenance disclosure was not rendered.');
    click(revisionDetails.querySelector('summary')!);
    expect(revisionDetails.textContent).toContain('package-source-1');
    expect(revisionDetails.textContent).toContain('execution-1');
    click(button('Validate')); await settle();
    click(button('Review changed files')); await settle();
    const publish = button('Publish');
    expect(publish.disabled).toBe(false);
    click(publish); await settle();
    expect(actions.mock.calls.map(call => call[3])).toEqual(['validate', 'review', 'publish']);
    expect(host.textContent).toContain('Published successfully');
    const publicationDetails = host.querySelector<HTMLDetailsElement>('details[aria-label="Advanced publication provenance"]');
    expect(publicationDetails?.open).toBe(false);
    if (!publicationDetails) throw new Error('Advanced publication provenance disclosure was not rendered.');
    click(publicationDetails.querySelector('summary')!);
    expect(publicationDetails.textContent).toContain('published-2');
  });

  it('keeps the full tail of a source larger than 200,000 characters in the staged edit', async () => {
    const largeText = `first: false\n${'x'.repeat(200_010)}\nTAIL_SENTINEL: intact\n`;
    const largeSource = { ...source('templates/large.yaml'), text: largeText };
    const stage = vi.fn(async (_domain: unknown, _scope: unknown, _baseRevision: unknown, _registryRevision: unknown, _operations: SourceOperation[]) => draft());
    const api = { getManagedPackageAuthoringSource: vi.fn(async () => largeSource), stageSourceChangeSet: stage } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'large' }, allowFileOperations: true }} />));
    await settle(); click(button('Edit'));
    expect(host.querySelector('[aria-label="Source publication workflow"]')).not.toBeNull();
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    expect(editor.value.endsWith('TAIL_SENTINEL: intact\n')).toBe(true);
    setInputValue(editor, `${editor.value.replace('first: false', 'first: true')}!`);
    click(button('Add current file to candidate')); await settle(); click(button('Stage candidate (1 files)')); await settle();
    expect(stage.mock.calls[0][4]).toEqual([{ operation: 'replace', path: 'templates/large.yaml', content: `${largeText.replace('first: false', 'first: true')}!` }]);
    expect(stage.mock.calls[0][4][0].content).toContain('TAIL_SENTINEL: intact');
  });

  it('hides and then reauthorizes local edits before offering to resume them after backgrounding', async () => {
    const api = { getManagedPackageAuthoringSource: vi.fn(async () => source()) } as unknown as ExplorerApi;
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'local-only: retained\n');
    act(() => window.dispatchEvent(new Event('pagehide')));
    expect(host.querySelector('textarea[aria-label^="Edit authorized source"]')).toBeNull();
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle();
    expect(host.textContent).toContain('Resume work');
    click(button('Resume work'));
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]')?.value).toBe('local-only: retained\n');
  });

  it('preserves the server draft identity across backgrounding and offers explicit resume after reauthorization', async () => {
    const staged = draft();
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()),
      stageSourceChangeSet: vi.fn(async () => staged),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'local-only: retained\n'); click(button('Save Draft')); await settle();
    expect(host.textContent).toContain('Draft cs-1');
    act(() => window.dispatchEvent(new Event('pagehide')));
    expect(host.textContent).not.toContain('Draft cs-1');
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle();
    expect(host.textContent).toContain('Resume work');
    click(button('Resume work'));
    expect(host.textContent).toContain('Draft cs-1');
  });

  it('retains but blocks a candidate whose Package source revision changed while the editor was hidden', async () => {
    const current = { ...source(), sourceEtag: 'etag-2', revision: 'revision-2', baseSourceRevision: 'base-2', registryRevision: 'registry-2' };
    const getSource = vi.fn().mockResolvedValueOnce(source()).mockResolvedValueOnce(current);
    const stage = vi.fn(async () => draft());
    const api = { getManagedPackageAuthoringSource: getSource, stageSourceChangeSet: stage } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' }, allowFileOperations: true }} />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Add current file to candidate')); await settle();
    act(() => window.dispatchEvent(new Event('pagehide')));
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle(); click(button('Resume work'));
    expect(host.textContent).toContain('older Package source or registration revision');
    expect(button('Stage candidate (1 files)').disabled).toBe(true);
    expect(stage).not.toHaveBeenCalled();
  });

  it('preserves stale local editor text without offering a base-only rebase', async () => {
    const current = { ...source(), text: 'enabled: true\n', sourceEtag: 'etag-2', revision: 'revision-2',
      baseSourceRevision: 'base-2', registryRevision: 'registry-2' };
    const getSource = vi.fn().mockResolvedValueOnce(source()).mockResolvedValueOnce(current);
    const stage = vi.fn(async () => draft());
    const api = { getManagedPackageAuthoringSource: getSource, stageSourceChangeSet: stage } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\nlocal-only: preserve\n');

    act(() => window.dispatchEvent(new Event('pagehide')));
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle();
    click(button('Resume work'));

    const retained = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!retained) throw new Error('Recovered owner source editor was not rendered.');
    expect(retained.value).toBe('enabled: true\nlocal-only: preserve\n');
    expect(host.textContent).toContain('cannot be rebased safely');
    expect(button('Save Draft').disabled).toBe(true);
    expect(host.textContent).not.toContain('Rebase local changes on current revision');
    expect(stage).not.toHaveBeenCalled();

    click(button('Discard local changes'));
    await settle();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="View authorized source"]')?.value)
      .toBe('enabled: true\n');
    click(button('Edit'));
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]')?.value)
      .toBe('enabled: true\n');
  });

  it('clears recovered editor work when reauthorization returns 403', async () => {
    const getSource = vi.fn()
      .mockResolvedValueOnce(source())
      .mockRejectedValueOnce(Object.assign(new Error('forbidden'), { status: 403 }));
    const api = { getManagedPackageAuthoringSource: getSource } as unknown as ExplorerApi;
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' } }} />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'revoked-secret: value\n');
    act(() => window.dispatchEvent(new Event('pagehide')));
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle();
    expect(host.textContent).toContain('Package source editing is unavailable');
    expect(host.textContent).not.toContain('Resume work');
    expect(host.querySelector('textarea[aria-label^="Edit authorized source"]')).toBeNull();
  });

  it('keeps central publication disabled when the #335 impact provider is unavailable', async () => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, _action: string) => centralDraft('validated'));
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle();
    click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle();
    click(button('Review changed files / Impact')); await settle();
    expect(host.textContent).toContain('Config Impact Report API is not available');
    expect(button('Publish').disabled).toBe(true);
    expect(action.mock.calls.map(call => call[3])).toEqual(['validate']);
  });

  it.each([
    ['provider unavailable', { ...centralImpact(), providerState: 'UNAVAILABLE', decision: null }],
    ['impact route unavailable with HTTP 404', null],
    ['provider stale', { ...centralImpact(), providerState: 'STALE', decision: null }],
    ['blocked by impact', { ...centralImpact('BLOCKED') }],
    ['draft freshness mismatch', { ...centralImpact(), freshness: { ...centralImpact().freshness!, draftId: 'another-draft' } }],
    ['partial coverage', { ...centralImpact(), coverage: 'PARTIAL' }],
  ])('fails closed for #335 impact state: %s', async (_name, report) => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string) =>
      centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn(async () => {
        if (!report) throw Object.assign(new Error('impact route unavailable'), { status: 404 });
        return report as SourceImpactReport;
      }),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle(); click(button('Review changed files / Impact')); await settle();
    expect(button('Publish').disabled).toBe(true);
    expect(action.mock.calls.map(call => call[3])).not.toContain('review');
  });

  it('allows a complete current #335 report to publish without an acknowledgement when the Server does not require one', async () => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string,
      _signal: AbortSignal, _acknowledgement?: unknown) =>
      actionName === 'publish' ? { published: true, revision: 'published-1', publishedRevision: 'published-1',
        activeRevision: 'published-1', activationState: 'ACTIVE', activationFailureCode: null, auditConfirmed: true }
        : centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn(async () => centralImpact()),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle(); click(button('Review changed files / Impact')); await settle();
    expect(action.mock.calls.map(call => call[3])).toEqual(['validate', 'review']);
    expect(button('Publish').disabled).toBe(false);
    expect(host.querySelector('input[aria-label="Acknowledge current Config impact"]')).toBeNull();
    click(button('Publish')); await settle();
    const publishCall = action.mock.calls.find(call => call[3] === 'publish');
    expect(publishCall?.[5]).toEqual(expect.objectContaining({ freshnessFingerprint: expect.any(String) }));
    expect(publishCall?.[5]).not.toHaveProperty('actor');
    expect(publishCall?.[5]).not.toHaveProperty('acknowledgementDigest');
    expect(host.textContent).toContain('Runtime Config published and active.');
  });

  it('requires actor and freshness-bound acknowledgement when the Server requires review approval', async () => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string,
      _signal: AbortSignal, _acknowledgement?: unknown) =>
      actionName === 'publish' ? { published: true, revision: 'published-2', auditConfirmed: true }
        : centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn(async () => centralImpact('REQUIRES_REVIEW', true)),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle(); click(button('Review changed files / Impact')); await settle();
    expect(button('Publish').disabled).toBe(true);
    expect(host.querySelector('input[aria-label="Acknowledge current Config impact"]')).not.toBeNull();
    acknowledgeCentralImpact('admin');
    expect(button('Publish').disabled).toBe(false);
    click(button('Publish')); await settle();
    await vi.waitFor(() => expect(action.mock.calls.some(call => call[3] === 'publish')).toBe(true));
    const publishCall = action.mock.calls.find(call => call[3] === 'publish');
    expect(publishCall?.[5]).toMatchObject({ actor: 'admin' });
    expect((publishCall?.[5] as { freshnessFingerprint?: string } | undefined)?.freshnessFingerprint).toMatch(/^[a-f0-9]{64}$/);
    expect((publishCall?.[5] as { acknowledgementDigest?: string } | undefined)?.acknowledgementDigest).toMatch(/^[a-f0-9]{64}$/);
  });

  it.each([
    ['typed Package source identity', { schemaVersion: 'att-source-revision/v1', kind: 'PACKAGE_SOURCE', scopeId: 'payments', revision: 'package-1' }, true],
    ['legacy Business source alias', { schemaVersion: 'att-source-revision/v1', kind: 'BUSINESS_SOURCE', scopeId: 'payments', revision: 'package-1' }, true],
    ['execution identity in Package freshness', { schemaVersion: 'att-source-revision/v1', kind: 'EXECUTION_REVISION', scopeId: 'payments@SIT', revision: 'execution-1' }, false],
  ])('uses Package identities for #335 freshness: %s', async (_name, packageFreshness, accepted) => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string) =>
      centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    const report = centralImpact();
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn(async () => ({
        ...report,
        freshness: { ...report.freshness!, packageSources: [packageFreshness] },
      } as SourceImpactReport)),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle(); click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle(); click(button('Review changed files / Impact')); await settle();
    expect(button('Publish').disabled).toBe(!accepted);
    expect(action.mock.calls.map(call => call[3])).toEqual(accepted ? ['validate', 'review'] : ['validate']);
  });

  it('aborts a pending central impact request and does not restore its report after the page is hidden', async () => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string) =>
      centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
    let resolveImpact: ((report: SourceImpactReport) => void) | undefined;
    let impactSignal: AbortSignal | undefined;
    const api = {
      getCentralAuthoringSource: vi.fn(async () => centralSource),
      stageSourceChangeSet: vi.fn(async () => centralDraft('draft')),
      sourceChangeSetAction: action,
      getCentralConfigImpact: vi.fn((_id: string, _cursor: string | undefined, signal: AbortSignal) => {
        impactSignal = signal;
        return new Promise<SourceImpactReport>(resolve => { resolveImpact = resolve; });
      }),
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'CENTRAL_RUNTIME_CONFIG', path: 'config/config.yaml' }} canManageCentral />));
    await settle();
    click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Central owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n'); click(button('Save Draft')); await settle();
    click(button('Validate')); await settle();
    click(button('Review changed files / Impact')); await settle();
    expect(api.getCentralConfigImpact).toHaveBeenCalled();
    act(() => window.dispatchEvent(new Event('pagehide')));
    expect(impactSignal?.aborted).toBe(true);
    resolveImpact?.({ providerState: 'AVAILABLE', decision: 'COMPATIBLE', affectedPackages: [
      { packageId: 'report-cleared-on-pagehide', environment: 'QA', certainty: 'POTENTIALLY_AFFECTED' },
    ], schedules: { items: [{ scheduleId: 'report-cleared-on-pagehide', action: 'REVALIDATE' }] } });
    await settle();
    expect(host.textContent).not.toContain('report-cleared-on-pagehide');
  });
});
