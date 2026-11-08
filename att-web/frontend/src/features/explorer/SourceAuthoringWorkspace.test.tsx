// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

async function settle() { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); }); }
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
function source(path = 'templates/example/template.yaml') {
  return { available: true, present: true, logicalPath: path, format: 'yaml', text: 'enabled: false\n',
    sourceEtag: 'etag-1', revision: 'revision-1', baseSourceRevision: 'base-1', registryRevision: 'registry-1' };
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
  it('shows shared-contract dependency paths, Action call sites, relationships and uncertainty', async () => {
    await act(async () => root.render(<ChangedFiles files={[{ operation: 'delete', path: 'templates/checkout/template.yaml', inboundReferences: [
      { schemaVersion: 'att-source-dependency/v1', kind: 'PACKAGE_RESOURCE', packageId: 'payments', environment: 'UAT',
        logicalId: 'checkout', canonicalPath: 'flows/purchase.yaml', relationship: 'invokes', callSite: 'actions[2].template', certainty: 'KNOWN' },
      { schemaVersion: 'att-source-dependency/v1', kind: 'LOAD_OVERLAY', canonicalPath: null, callSite: 'LOAD: invoice-profile', certainty: 'UNKNOWN' },
    ] }]} />));
    expect(host.textContent).toContain('flows/purchase.yaml');
    expect(host.textContent).toContain('Action: actions[2].template');
    expect(host.textContent).toContain('invokes');
    expect(host.textContent).toContain('location uncertain');
    expect(host.textContent).not.toContain('Unknown descriptor');
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
    expect(host.textContent).toContain('Package source revision package-source-1');
    expect(host.textContent).toContain('Execution revision execution-1');
    click(button('Validate')); await settle();
    click(button('Review changed files')); await settle();
    const publish = button('Publish');
    expect(publish.disabled).toBe(false);
    click(publish); await settle();
    expect(actions.mock.calls.map(call => call[3])).toEqual(['validate', 'review', 'publish']);
    expect(host.textContent).toContain('Published revision published-2');
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
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    expect(editor.value.endsWith('TAIL_SENTINEL: intact\n')).toBe(true);
    setInputValue(editor, `${editor.value.replace('first: false', 'first: true')}!`);
    click(button('Add current file to candidate')); click(button('Stage candidate (1 files)')); await settle();
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
    setInputValue(editor, 'enabled: true\n'); click(button('Add current file to candidate'));
    act(() => window.dispatchEvent(new Event('pagehide')));
    act(() => window.dispatchEvent(new Event('pageshow'))); await settle(); click(button('Resume work'));
    expect(host.textContent).toContain('older Package source or registration revision');
    expect(button('Stage candidate (1 files)').disabled).toBe(true);
    expect(stage).not.toHaveBeenCalled();
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
    expect(host.textContent).toContain('Owner editing is unavailable');
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
    ['explicit acknowledgement missing', { ...centralImpact('REQUIRES_REVIEW', true) }],
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

  it('accepts a complete, current #335 report before enabling the central review and publish steps', async () => {
    const centralSource = { ...source('config/config.yaml'), revision: 'config-1', configRevision: 'config-1', baseSourceRevision: 'config-base-1' };
    const centralDraft = (state: string) => ({ ...draft(state as 'draft' | 'validated' | 'reviewed'), domain: 'CENTRAL_RUNTIME_CONFIG' as const,
      scopeId: 'central-runtime-config', baseSourceRevision: 'config-base-1' });
    const action = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, actionName: string) =>
      centralDraft(actionName === 'validate' ? 'validated' : 'reviewed'));
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
