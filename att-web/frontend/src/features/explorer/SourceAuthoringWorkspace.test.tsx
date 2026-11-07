// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SourceAuthoringWorkspace } from './SourceAuthoringWorkspace';
import type { ExplorerApi } from './types';
import type { SourceImpactReport } from './sourceManagement';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, readOnly = true, onChange }: { value: string; label: string; readOnly?: boolean; onChange?: (value: string) => void }) =>
    <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.currentTarget.value)} />,
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

describe('SourceAuthoringWorkspace', () => {
  it('stages the complete owner source against its revisions and gates publication through validate and review', async () => {
    const actions = vi.fn(async (_domain: unknown, _scope: unknown, _id: unknown, action: string) => {
      if (action === 'validate') return draft('validated');
      if (action === 'review') return draft('reviewed');
      return { published: true, domain: 'PACKAGE_SOURCE', scopeId: 'payments', changeSetId: 'cs-1', beforeRevision: 'base-1', revision: 'published-2' };
    });
    const api = {
      getManagedPackageAuthoringSource: vi.fn(async () => source()),
      stageSourceChangeSet: vi.fn(async () => draft()),
      sourceChangeSetAction: actions,
    } as unknown as ExplorerApi;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId: 'payments',
      query: { kind: 'resource', resourceType: 'template', resourceId: 'checkout' }, label: 'Template source' }} />));
    await settle();
    click(button('Edit'));
    const editor = host.querySelector<HTMLTextAreaElement>('textarea[aria-label^="Edit authorized source"]');
    if (!editor) throw new Error('Owner source editor was not rendered.');
    setInputValue(editor, 'enabled: true\n');
    click(button('Save Draft'));
    await settle();
    expect(api.stageSourceChangeSet).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-1', 'registry-1', [
      { operation: 'replace', path: 'templates/example/template.yaml', content: 'enabled: true\n' },
    ], expect.any(AbortSignal));
    expect(host.textContent).toContain('Published source is unchanged');
    click(button('Validate')); await settle();
    click(button('Review changed files')); await settle();
    const publish = button('Publish');
    expect(publish.disabled).toBe(false);
    click(publish); await settle();
    expect(actions.mock.calls.map(call => call[3])).toEqual(['validate', 'review', 'publish']);
    expect(host.textContent).toContain('Published revision published-2');
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
