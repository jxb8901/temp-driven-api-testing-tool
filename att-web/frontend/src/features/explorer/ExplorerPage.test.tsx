// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ExplorerPage } from './ExplorerPage';
import { SourceViewer, sourceViewerLimits } from './SourceViewer';
import { ResourceTree } from './ResourceTree';
import { ConfigurationExplorer } from './ConfigurationExplorer';
import { TypedSidecarForm } from './TypedSidecarForm';
import { inspectionCache } from '../../inspectionCache';
import { ApiError } from '../../types';
import { parseDocument } from 'yaml';
import type { ConfigurationResponse, ExplorerApi, JsonObject, ResourceDetail, ResourceIdentity, ResourceSummary } from './types';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, readOnly = true, onChange, sourcePath, fileReferences, onFileReference }: {
    value: string; label: string; readOnly?: boolean; onChange?: (value: string) => void; sourcePath?: string;
    fileReferences?: Array<{ referenceId: string; logicalPath: string }>;
    onFileReference?: (path: string, origin?: string, referenceId?: string) => void;
  }) => <section className="att-code-editor" aria-label={label}>
    <div className="att-code-host">{readOnly ? <pre>{value}</pre>
      : <textarea aria-label={label} value={value} onChange={event => onChange?.(event.currentTarget.value)} />}</div>
    {fileReferences?.[0] && <button type="button" onClick={() => onFileReference?.('./payloads/request.xml', sourcePath, fileReferences[0].referenceId)}>Open safe referenced file</button>}
    {readOnly && <button type="button" onClick={() => void navigator.clipboard?.writeText(value)}>Copy displayed source</button>}
  </section>,
  detectCodeLanguage: () => 'yaml',
}));

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

async function render(node: ReactNode) {
  await act(async () => root.render(node));
  await settle();
  return container;
}

async function settle() {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); });
    const confirm = container.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child');
    if (!confirm) break;
    await act(async () => { confirm.click(); await Promise.resolve(); });
  }
}

function button(name: string): HTMLButtonElement {
  const found = [...container.querySelectorAll('button')].find(item => item.textContent?.trim() === name);
  if (!found) throw new Error(`Button not found: ${name}`);
  return found;
}

function setValue(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  const prototype = Object.getPrototypeOf(element) as object;
  const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
  act(() => {
    setter?.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}

function click(element: HTMLElement) { act(() => element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))); }

function apiWith(overrides: Partial<ExplorerApi>): ExplorerApi {
  const fallback = {
    listResources: vi.fn(async () => ({ items: [], total: 0, nextCursor: null })),
    getResource: vi.fn(async () => ({ resource: { type: 'template', resourceId: 'template-1' } as ResourceSummary, definition: {} })),
    getSafeSource: vi.fn(async () => ({ available: true, text: 'safe: true\n', format: 'yaml' })),
    getDeclaredConfiguration: vi.fn(async () => ({ state: 'ready', environments: [], globals: {}, sections: [] })),
    getEffectiveConfiguration: vi.fn(async () => ({ state: 'ready', globals: {}, sections: [] })),
    compareConfiguration: vi.fn(async () => ({ state: 'ready', fields: [] })),
    getDebugForm: vi.fn(async () => ({ target: { type: 'template' as const, id: 'template-1' }, input: {} })),
    previewDebug: vi.fn(async () => ({ draftId: 'draft-1', previewYaml: 'accepted: true' })),
    submitDebug: vi.fn(async () => ({ jobId: 'job-debug-1' })),
    runCase: vi.fn(async () => ({ jobId: 'job-case-1' })),
  } satisfies ExplorerApi;
  return { ...fallback, ...overrides };
}

async function prepareNewMetricSource(api: ExplorerApi): Promise<HTMLDetailsElement> {
  await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
    registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
  const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
  click(panel.querySelector('summary')!);
  setValue(panel.querySelector<HTMLInputElement>('input[aria-label="Resource ID"]')!, 'new-health');
  click(button('Prepare new resource'));
  await settle();
  return panel;
}

function resource(type: ResourceSummary['type'], resourceId: string, extra: Partial<ResourceSummary> = {}): ResourceSummary {
  return { type, resourceId, logicalId: resourceId, name: resourceId, state: 'ready', sourceAvailable: true, ...extra };
}

describe('Explorer source viewer', () => {
  it('groups Package support sources separately from executable resources with direct navigation', async () => {
    const onOpenTestdata = vi.fn();
    const onOpenPackageConfiguration = vi.fn();
    await render(<ExplorerPage api={apiWith({})} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}}
      onOpenTestdata={onOpenTestdata} onOpenPackageConfiguration={onOpenPackageConfiguration} />);

    const support = container.querySelector<HTMLElement>('.att-explorer-supporting-sources')!;
    expect(support.hidden).toBe(true);
    click(button('Supporting sources'));
    expect(support.hidden).toBe(false);
    expect(support.textContent).toContain('File catalog');
    expect(support.querySelector('.att-package-sut-metrics')).not.toBeNull();
    click(button('Testdata'));
    click(button('Package manifest'));
    expect(onOpenTestdata).toHaveBeenCalledOnce();
    expect(onOpenPackageConfiguration).toHaveBeenCalledOnce();

    click(button('Executable resources'));
    expect(support.hidden).toBe(true);
    expect(container.querySelector<HTMLElement>('.att-explorer-executable-resources')?.hidden).toBe(false);
  });

  it('distinguishes browseable metadata from unresolved execution dependencies', async () => {
    const target = resource('template', 'dependent', {
      metadataState: 'available',
      executionReadiness: { state: 'dependency-unresolved', environment: 'SIT', diagnostics: [{
        code: 'ATT-RESOURCE-ENV-REQUIRED', summary: 'Execution requires environment variable ORDERS_DB_PASSWORD',
        remediation: 'Configure the Worker environment, then validate the selected target.',
      }] },
    });
    const api = apiWith({ getResource: vi.fn(async () => ({ resource: target, definition: { name: 'Dependent' } })) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'dependent' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const readiness = container.querySelector('[aria-label="Execution readiness"]');
    expect(readiness?.textContent).toContain('Metadata available');
    expect(readiness?.textContent).toContain('Execution dependencies unresolved (SIT)');
    expect(readiness?.textContent).toContain('ORDERS_DB_PASSWORD');
    expect(container.querySelector('[data-tab="definition"]')?.textContent).toBe('Visual');
    expect(container.querySelector('[aria-label="Visual action inspector"]')).not.toBeNull();
  });

  it('maps the legacy definition tab to Template Visual and retains Tool Definition', async () => {
    const api = apiWith({ getResource: vi.fn(async (identity: ResourceIdentity) => (identity.type === 'template'
      ? { resource: resource('template', identity.resourceId), definition: { actions: { first: { type: 'log', message: 'safe' } } } }
      : { resource: resource('tool', identity.resourceId), definition: { toolId: 'lookup', executionKind: 'call' } }) as ResourceDetail) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'checkout', tab: 'definition' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    expect(container.querySelector('[data-tab="definition"]')?.textContent).toBe('Visual');
    expect(container.querySelector('.att-action-list .att-action-name')?.textContent).toBe('first');

    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'tool', resourceId: 'lookup', tab: 'definition' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    expect(container.querySelector('[data-tab="definition"]')?.textContent).toBe('Definition');
    expect(container.querySelector('[aria-label="Safe resource definition"]')).not.toBeNull();
  });

  it('shows a loading state before an action projection arrives and distinguishes malformed from missing projections', async () => {
    let resolveDetail!: (detail: ResourceDetail) => void;
    const pendingDetail = new Promise<ResourceDetail>(resolve => { resolveDetail = resolve; });
    const delayedApi = apiWith({ getResource: vi.fn(() => pendingDetail) });
    await render(<ExplorerPage api={delayedApi} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'slow' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    expect(container.textContent).toContain('Loading action definition…');
    expect(container.querySelector('[aria-label="Visual action inspector"]')).toBeNull();
    expect(container.textContent).not.toContain('Actions are unavailable');

    await act(async () => resolveDetail({ resource: resource('template', 'slow'), definition: {} }));
    await settle();
    expect(container.textContent).toContain('Actions are unavailable because the safe definition does not contain an action map');

    const missingApi = apiWith({ getResource: vi.fn(async () => ({ resource: resource('template', 'missing') })) });
    await render(<ExplorerPage api={missingApi} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'missing' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    expect(container.textContent).toContain('No definition projection is available.');
    expect(container.textContent).not.toContain('Actions are unavailable');
  });

  it('routes a selected resolved Flow action to its detail and leaves unresolved targets inert', async () => {
    const flow = resource('template', 'flow-caller', { references: [{ type: 'flow', logicalId: 'common.compose.v1', resourceId: 'flow-target',
      resolution: 'resolved', callSite: { actionId: 'compose' } }] });
    const api = apiWith({ getResource: vi.fn(async () => ({ resource: flow,
      definition: { actions: { compose: { type: 'flow', use: 'common.compose.v1' } } } })) });
    const onResourceRouteChange = vi.fn();
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'flow-caller' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={onResourceRouteChange} onJobAccepted={() => {}} />);
    click(button('Open referenced Flow · common.compose.v1'));
    expect(onResourceRouteChange).toHaveBeenLastCalledWith({ type: 'flow', resourceId: 'flow-target', tab: 'definition' });

    const unresolvedApi = apiWith({ getResource: vi.fn(async () => ({ resource: resource('template', 'flow-caller', {
      references: [{ type: 'flow', logicalId: 'common.compose.v1', resolution: 'unresolved', callSite: { actionId: 'compose' } }],
    }), definition: { actions: { compose: { type: 'flow', use: 'common.compose.v1' } } } })) });
    await render(<ExplorerPage api={unresolvedApi} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'flow-caller' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={onResourceRouteChange} onJobAccepted={() => {}} />);
    expect(container.textContent).toContain('Referenced Flow is unresolved or invalid');
    expect([...container.querySelectorAll('button')].some(item => item.textContent?.includes('Open referenced Flow'))).toBe(false);
  });

  it('uses the shared query panels and exposes Clear filters and Refresh resources', async () => {
    const api = apiWith({ listResources: vi.fn(async () => ({ items: [resource('flow', 'payments.flow')], total: 1, nextCursor: null })) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);

    expect(container.querySelector('.att-query-filter-panel[aria-label="Resource filters"]')).not.toBeNull();
    expect(container.querySelector('.att-query-results-panel[aria-label="Resource results"]')).not.toBeNull();
    expect(container.querySelector('.att-explorer-table.att-data-table')).not.toBeNull();
    click(button('Templates'));
    await new Promise(resolve => window.setTimeout(resolve, 10));
    await settle();
    expect(vi.mocked(api.listResources).mock.calls.at(-1)?.[1].type).toBe('template');
    click(button('Clear filters'));
    await new Promise(resolve => window.setTimeout(resolve, 10));
    await settle();
    expect(container.querySelector('[role="radio"][aria-checked="true"]')?.textContent).toBe('All');
    click(button('Refresh resources'));
    await settle();
    expect(api.listResources).toHaveBeenCalledTimes(4);
  });

  it('exposes a discoverable Batch RUN entry from the package resource catalog', async () => {
    const onRunBatch = vi.fn();
    const api = apiWith({ listResources: vi.fn(async () => ({ items: [resource('case', 'payments.C1')], total: 1, nextCursor: null })) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} onRunBatch={onRunBatch} />);

    click(button('Run Test Cases…'));
    expect(onRunBatch).toHaveBeenCalledTimes(1);
  });

  it('requires accessible confirmation before running a Test Case', async () => {
    const selected = resource('case', 'payments.C1', { provenance: { suite: 'suites/payments.yaml' } });
    const runCase = vi.fn(async () => ({ jobId: 'job-case-1' }));
    const api = apiWith({
      listResources: vi.fn(async () => ({ items: [selected], total: 1, nextCursor: null })),
      getResource: vi.fn(async () => ({ resource: selected, definition: {} })),
      runCase,
    });
    const onJobAccepted = vi.fn();
    const nativeConfirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await render(<ExplorerPage api={api} packageId="payments"
      resourceRoute={{ type: 'case', resourceId: 'payments.C1' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={onJobAccepted} />);

    click(button('Run selected case'));
    const dialog = container.querySelector<HTMLElement>('[role="alertdialog"][aria-modal="true"]');
    expect(dialog?.getAttribute('aria-labelledby')).toBeTruthy();
    expect(dialog?.getAttribute('aria-describedby')).toBeTruthy();
    expect(dialog?.textContent).toContain('Run Test Case payments.C1 now?');
    click(button('Cancel'));
    await settle();
    expect(runCase).not.toHaveBeenCalled();
    expect(onJobAccepted).not.toHaveBeenCalled();

    click(button('Run selected case'));
    const confirm = container.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child');
    expect(confirm?.textContent).toBe('Run now');
    click(confirm!);
    await settle();
    expect(runCase).toHaveBeenCalledWith(expect.objectContaining({
      packageId: 'payments', suite: 'suites/payments.yaml', caseId: 'payments.C1', environment: 'SIT',
    }));
    expect(nativeConfirm).not.toHaveBeenCalled();
    expect(onJobAccepted).toHaveBeenCalledWith('job-case-1');
  });

  it('virtualizes a multi-thousand-line safe projection, refreshes repeatedly, and copies only displayed text', async () => {
    let version = 0;
    const source = Array.from({ length: 5000 }, (_, index) => `line-${index + 1}: ${index === 0 ? '<img src=x onerror=alert(1)>' : 'value'}`).join('\n');
    const getSafeSource = vi.fn(async () => ({ available: true, text: version++ === 0 ? source : `${source}\nrefreshed`, format: 'yaml' }));
    const api = apiWith({ getSafeSource });
    const identity: ResourceIdentity = { packageId: 'payments', type: 'template', resourceId: 'checkout' };
    const clipboardWrite = vi.fn<(text: string) => Promise<void>>(async () => {});
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } });

    await render(<SourceViewer api={api} identity={identity} available active />);
    expect(container.textContent).toMatch(/5[,.]?000 lines/);
    expect(container.querySelector('.att-code-editor pre')?.textContent).toContain('line-1: <img src=x onerror=alert(1)>');
    expect(container.querySelector('img,script,svg')).toBeNull();
    expect(container.querySelector('.att-code-editor pre')?.textContent).toContain('line-5000: value');

    click(button('Refresh source'));
    await settle();
    expect(getSafeSource).toHaveBeenCalledTimes(2);
    click(button('Refresh source'));
    await settle();
    expect(getSafeSource).toHaveBeenCalledTimes(3);

    click(button('Copy displayed source'));
    await settle();
    expect(clipboardWrite).toHaveBeenCalledTimes(1);
    expect(clipboardWrite.mock.calls[0][0]).toBe(`${source}\nrefreshed`);
  });

  it('labels original versus sanitized source and preserves CRLF in both display and Copy', async () => {
    const source = '# authored comment\r\n\r\ncall: >-\r\n  #{upper(value="x")}\r\nliteral: \'\\\\n\'\r\n';
    const clipboardWrite = vi.fn<(text: string) => Promise<void>>(async () => {});
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } });
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text: source, format: 'yaml', logicalPath: 'templates/checkout/template.yaml', projectionKind: 'original' })) });
    const identity: ResourceIdentity = { packageId: 'payments', type: 'template', resourceId: 'checkout' };
    await render(<SourceViewer api={api} identity={identity} available active />);

    const toolbar = container.querySelector('.att-source-toolbar')!;
    expect(toolbar.textContent).toContain('templates/checkout/template.yaml');
    expect(toolbar.textContent).toContain('YAML');
    expect(toolbar.textContent).toContain('Original');
    expect(toolbar.textContent).not.toContain('Original source');
    expect(container.querySelector('.att-code-editor pre')?.textContent).toBe(source);
    click(button('Copy path'));
    await settle();
    expect(clipboardWrite).toHaveBeenLastCalledWith('templates/checkout/template.yaml');
    click(button('Copy displayed source'));
    expect(clipboardWrite).toHaveBeenLastCalledWith(source);

    const sanitizedApi = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text: 'name: [REDACTED]\n', format: 'yaml', projectionKind: 'sanitized', redacted: true })) });
    await render(<SourceViewer api={sanitizedApi} identity={identity} available active />);
    expect(container.querySelector('.att-source-toolbar')?.textContent).toContain('Sanitized projection');
    expect(container.textContent).toContain('Sanitized projection');
  });

  it('localizes the compact source toolbar and accessibility labels to Traditional Chinese', async () => {
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, logicalPath: 'templates/demo.yaml',
      format: 'yaml', projectionKind: 'original', text: 'name: demo\n' })) });
    const identity: ResourceIdentity = { packageId: 'payments', type: 'template', resourceId: 'demo' };
    await render(<SourceViewer api={api} identity={identity} available active locale="zh-Hant" />);
    const toolbar = container.querySelector('.att-source-toolbar');
    expect(toolbar?.textContent).toContain('templates/demo.yaml');
    expect(toolbar?.textContent).toContain('YAML · 2 行 · 原始來源');
    expect(container.querySelector('.att-explorer-source')?.getAttribute('aria-label')).toBe('資源來源');
    expect([...container.querySelectorAll<HTMLButtonElement>('button')].some(button => button.textContent === '複製路徑')).toBe(true);
  });

  it('localizes the compact source toolbar and accessibility labels to Traditional Chinese', async () => {
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, logicalPath: 'templates/demo.yaml',
      format: 'yaml', projectionKind: 'original', text: 'name: demo\n' })) });
    const identity: ResourceIdentity = { packageId: 'payments', type: 'template', resourceId: 'demo' };
    await render(<SourceViewer api={api} identity={identity} available active locale="zh-Hant" />);
    const toolbar = container.querySelector('.att-source-toolbar');
    expect(toolbar?.textContent).toContain('templates/demo.yaml');
    expect(toolbar?.textContent).toContain('YAML · 2 行 · 原始來源');
    expect(container.querySelector('.att-explorer-source')?.getAttribute('aria-label')).toBe('資源來源');
    expect([...container.querySelectorAll<HTMLButtonElement>('button')].some(button => button.textContent === '複製路徑')).toBe(true);
  });

  it('explains Tool source provenance and distinguishes no standalone source from policy denial', async () => {
    const identity: ResourceIdentity = { packageId: 'payments', type: 'tool', resourceId: 'lookup' };
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: false, reason: 'no-standalone-source' })) });
    await render(<SourceViewer api={api} identity={identity} available={false} active resource={{
      ...resource('tool', 'lookup', { sourceAvailable: false, sourceReason: 'no-standalone-source', sourceKind: 'call-backed',
        provenance: { toolGroupId: 'billing', toolName: 'Lookup' } }),
    }} />);
    expect(container.textContent).toContain('No standalone source file');
    expect(container.querySelector<HTMLDetailsElement>('.att-source-details')?.open).toBe(false);
    expect(container.textContent).not.toContain('Name: Lookup');
    expect(container.textContent).not.toContain('Tool Group:');
    expect(container.textContent).not.toContain('Source is not available for this resource');

    await render(<SourceViewer api={api} identity={identity} available={false} active resource={{
      ...resource('tool', 'lookup', { sourceAvailable: false, sourceReason: 'not-allowlisted', sourceKind: 'script-backed' }),
    }} />);
    expect(container.textContent).toContain('Source exists but access is denied');
  });

  it('opens the Group manifest through the Tool resource safe-source reference', async () => {
    const referenceId = 'e'.repeat(64);
    const openReference = vi.fn();
    const manifestPath = 'tools/billing/tool-group.yaml';
    const api = apiWith({ getSafeSource: vi.fn(async () => ({
      available: true, text: 'schemaVersion: att-tool/v1\nid: lookup\n', format: 'yaml', logicalPath: 'tools/billing/lookup.yaml',
      fileReferences: [{ referenceId, logicalPath: manifestPath, available: true }],
    })) });
    await render(<SourceViewer api={api} identity={{ packageId: 'payments', type: 'tool', resourceId: 'billing.lookup' }} available active
      resource={resource('tool', 'billing.lookup', { logicalId: 'billing.lookup', provenance: { toolGroupId: 'billing', toolName: 'Lookup', toolGroupManifest: manifestPath } })}
      onFileReference={openReference} />);

    const groupLink = container.querySelector<HTMLButtonElement>('[aria-label="Open Group manifest source tools/billing/tool-group.yaml"]');
    expect(groupLink?.textContent).toContain('Group manifest');
    expect(container.querySelector('.att-source-toolbar')?.textContent).toContain('tools/billing/lookup.yaml');
    expect(container.querySelector('.att-code-editor')!.compareDocumentPosition(container.querySelector('.att-source-details')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await act(async () => groupLink?.click());
    expect(openReference).toHaveBeenCalledWith(manifestPath, 'tools/billing/lookup.yaml', {
      type: 'tool', resourceId: 'billing.lookup', referenceId, returnTab: 'source',
    });
  });

  it('opens Action file dependencies through the safe Package viewer from Visual and Dependencies', async () => {
    const referenceId = 'd'.repeat(64);
    const fileReference = { type: 'file' as const, targetKind: 'package-file', logicalId: 'templates/checkout/CT001_Confirm.xml',
      originPath: 'templates/checkout/template.yaml', referenceId, resolution: 'resolved', available: true,
      callSites: [{ kind: 'action', actionId: 'sendCT001Confirm' }] };
    const selected = resource('template', 'checkout', { references: [fileReference] });
    const api = apiWith({ getResource: vi.fn(async () => ({ resource: selected,
      definition: { actions: { sendCT001Confirm: { type: 'tool', call: "#{send(payload='&{./CT001_Confirm.xml}')}" } } } })) });
    const onOpenConfigurationFile = vi.fn();
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'template', resourceId: 'checkout', tab: 'definition' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onOpenConfigurationFile={onOpenConfigurationFile} onJobAccepted={() => {}} />);
    await settle();

    click(button('View file'));
    expect(onOpenConfigurationFile).toHaveBeenLastCalledWith('templates/checkout/CT001_Confirm.xml', 'templates/checkout/template.yaml', {
      type: 'template', resourceId: 'checkout', referenceId, returnTab: 'definition', actionId: 'sendCT001Confirm',
    });
    click(button('Dependencies'));
    click(button('templates/checkout/CT001_Confirm.xml'));
    expect(onOpenConfigurationFile).toHaveBeenLastCalledWith('templates/checkout/CT001_Confirm.xml', 'templates/checkout/template.yaml', {
      type: 'template', resourceId: 'checkout', referenceId, returnTab: 'relations',
    });
  });

  it('shows an explicit empty state and reports expired authentication without leaking API detail', async () => {
    const identity: ResourceIdentity = { packageId: 'payments', type: 'flow', resourceId: 'empty' };
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text: '', format: 'yaml' })) });
    await render(<SourceViewer api={api} identity={identity} available active />);
    expect(container.textContent).toContain('Safe projection is empty.');
    expect(container.querySelector('.att-code-editor')).toBeNull();

    const authApi = apiWith({ getSafeSource: vi.fn(async () => { throw Object.assign(new Error('token=must-not-display'), { status: 401 }); }) });
    await render(<SourceViewer api={authApi} identity={identity} available active />);
    expect(container.textContent).toContain('Your session has expired.');
    expect(container.textContent).not.toContain('token=must-not-display');
  });

  it('opens only a server-projected package file reference using its opaque reference id', async () => {
    const referenceId = 'a'.repeat(64);
    const identity: ResourceIdentity = { packageId: 'payments', type: 'flow', resourceId: 'checkout-flow' };
    const openReference = vi.fn();
    const api = apiWith({ getSafeSource: vi.fn(async () => ({
      available: true, text: 'request: &{./payloads/request.xml}', format: 'yaml', logicalPath: 'templates/checkout/flow.yaml',
      fileReferences: [{ referenceId, logicalPath: 'templates/checkout/payloads/request.xml', available: true }],
    })) });
    await render(<SourceViewer api={api} identity={identity} available active onFileReference={openReference} />);

    click(button('Open safe referenced file'));
    expect(openReference).toHaveBeenCalledWith('templates/checkout/payloads/request.xml', 'templates/checkout/flow.yaml', {
      type: 'flow', resourceId: 'checkout-flow', referenceId, returnTab: 'source',
    });
  });

  it('offers an accessible reference-list action for nested Action expressions and reports unavailable references', async () => {
    const referenceId = 'c'.repeat(64);
    const openReference = vi.fn();
    const api = apiWith({ getSafeSource: vi.fn(async () => ({
      available: true, format: 'yaml', logicalPath: 'flows/checkout/flow.yaml',
      text: 'argument: #{request(&{./request.xml})}',
      fileReferences: [
        { referenceId, logicalPath: 'flows/checkout/request.xml', available: true },
        { referenceId: 'd'.repeat(64), logicalPath: 'flows/private/secret.xml', available: false },
      ],
    })) });
    await render(<SourceViewer api={api} identity={{ packageId: 'payments', type: 'flow', resourceId: 'checkout' }} available active onFileReference={openReference} />);
    expect(container.querySelector('.att-code-editor pre')?.textContent).toContain('#{request(&{./request.xml})}');
    expect(container.textContent).toContain('Unavailable under the Server safe-source policy');
    click(button('View referenced file'));
    expect(openReference).toHaveBeenCalledWith('flows/checkout/request.xml', 'flows/checkout/flow.yaml', {
      type: 'flow', resourceId: 'checkout', referenceId, returnTab: 'source',
    });
  });

  it('caps the safe source projection before rendering', async () => {
    const text = 'x'.repeat(sourceViewerLimits.maxCharacters + 100);
    const api = apiWith({ getSafeSource: vi.fn(async () => ({ available: true, text })) });
    await render(<SourceViewer api={api} identity={{ packageId: 'p', type: 'template', resourceId: 'r' }} available active />);
    expect(container.textContent).toContain('Source exceeds the viewer limit.');
    expect(container.querySelector('.att-code-editor pre')?.textContent?.length).toBeLessThanOrEqual(sourceViewerLimits.maxCharacters);
  });
});

describe('Tool Group navigation', () => {
  it('shows grouped members inside a collapsible Package-local folder', async () => {
    const onSelect = vi.fn();
    const items = [
      resource('tool', 'billing.lookup', { logicalId: 'billing.lookup', provenance: { toolGroupId: 'billing', toolGroupManifest: 'tools/folder/tool-group.yaml' } }),
      resource('tool', 'billing.count', { logicalId: 'billing.count', provenance: { toolGroupId: 'billing', toolGroupManifest: 'tools/folder/tool-group.yaml' } }),
      resource('tool', 'Default.lookup', { logicalId: 'Default.lookup', provenance: { toolGroupId: 'Default', toolGroupManifest: 'tools/compat/tool-group.yaml' } }),
      resource('tool', 'standalone', { provenance: { toolGroupId: 'Default' } }),
    ];
    await render(<ResourceTree items={items} selectedKey="" loading={false} sortKey="name" direction="asc"
      onSortChange={() => {}} onSelect={onSelect} onLoadMore={() => {}} />);
    expect(container.querySelector('button[aria-expanded="true"]')?.textContent).toContain('billing');
    expect(container.textContent).toContain('billing.lookup');
    expect(container.textContent).toContain('billing.count');
    expect(container.textContent).toContain('standalone');

    click(container.querySelector<HTMLButtonElement>('button[aria-expanded="true"]')!);
    expect(container.querySelector('button[aria-expanded="false"]')?.textContent).toContain('billing');
    expect(container.textContent).not.toContain('billing.lookup');
    expect(container.textContent).not.toContain('billing.count');
    expect(container.textContent).toContain('Column sorting applies to matching resources before pagination');
  });
});

describe('typed sidecar form', () => {
  it('keeps redacted package defaults until a scalar override is explicitly selected', async () => {
    const keepDefault = { $attDebugKeepDefault: '/inputs/credential' };
    const onChange = vi.fn((_value: JsonObject) => {});
    const onValidityChange = vi.fn((_valid: boolean) => {});
    const initial: JsonObject = { inputs: { credential: keepDefault, retries: 3 }, vars: { dryRun: true } };
    await render(<TypedSidecarForm value={initial} groups={[{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }]}
      resetKey={0} onChange={onChange} onValidityChange={onValidityChange} />);

    const checkbox = container.querySelector<HTMLInputElement>('.att-sidecar-keep-default input');
    expect(checkbox?.checked).toBe(true);
    expect(container.textContent).not.toContain('real-password');
    expect(onChange).not.toHaveBeenCalled();

    click(checkbox!);
    const type = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    expect(type).toBeDefined();
    setValue(type!, 'string');
    const override = container.querySelector<HTMLInputElement>('input[aria-label="credential value"]');
    expect(override).not.toBeNull();
    setValue(override!, 'safe-override');
    expect(onChange).toHaveBeenLastCalledWith({ inputs: { credential: 'safe-override', retries: 3 }, vars: { dryRun: true } });
  });

  it('validates advanced JSON and preserves multiple typed groups through reset', async () => {
    let current: JsonObject = { inputs: { count: 2 }, arguments: { enabled: false } };
    const onChange = vi.fn((value: JsonObject) => { current = value; });
    const onValidityChange = vi.fn();
    await render(<TypedSidecarForm value={current} groups={[{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }]}
      resetKey={0} onChange={onChange} onValidityChange={onValidityChange} />);

    const advanced = container.querySelector<HTMLTextAreaElement>('textarea');
    expect(advanced).not.toBeNull();
    setValue(advanced!, '{');
    await settle();
    expect(container.textContent).toContain('Expected property name or');
    expect(container.querySelector('.att-sidecar-advanced [aria-invalid="true"]')).not.toBeNull();

    setValue(advanced!, '{"inputs":{"count":4},"arguments":{"enabled":true,"nested":{"ok":true}}}');
    await settle();
    expect(current).toEqual({ inputs: { count: 4 }, arguments: { enabled: true, nested: { ok: true } } });
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });

  it('blocks invalid numeric values and non-object JSON', async () => {
    let valid = true;
    await render(<TypedSidecarForm value={{ inputs: { count: 2 } }} groups={[{ key: 'inputs', label: 'Inputs' }]}
      resetKey={0} onChange={() => {}} onValidityChange={next => { valid = next; }} />);
    const numberInput = container.querySelector<HTMLInputElement>('input[aria-label="count value"]');
    expect(numberInput?.type).toBe('number');
    setValue(numberInput!, '');
    await settle();
    expect(valid).toBe(false);

    const advanced = container.querySelector<HTMLTextAreaElement>('textarea');
    setValue(advanced!, '[]');
    await settle();
    expect(container.textContent).toContain('Enter a JSON object.');
  });
});

describe('Explorer page integration', () => {
  it('shows only the explicit Package SUT Metrics inventory with environment and revision provenance', async () => {
    const getPackageSutMetricCatalog = vi.fn(async (_packageId: string, _environment?: string, _signal?: AbortSignal) => ({
      schemaVersion: 'att-package-sut-metrics-catalog/v1',
      packageId: 'payments', environment: 'UAT', manifestDigest: 'a'.repeat(64),
      packageRevision: 'b'.repeat(64), runtimeConfigRevision: 'c'.repeat(64),
      canEdit: false,
      metricSources: [{ id: 'db-health', name: 'Database health', kind: 'SQL', connection: { kind: 'DATA_SOURCE', id: 'payments-db', secretRef: 'must-not-render' } }],
      groups: [{ id: 'checkout', name: 'Checkout' }],
      profiles: [{ id: 'checkout-load', name: 'Checkout Load', kind: 'flat' }],
      resourceProvenance: [{ kind: 'metricSource', id: 'db-health', path: 'load/sut-metrics/sources/db-health.yaml', sha256: 'd'.repeat(64) }],
    }));
    const getPackageSutMetricsResource = vi.fn(async () => { throw new ApiError('Forbidden', { status: 403 }); });
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog: getPackageSutMetricCatalog, getPackageSutMetricsResource });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);

    expect(getPackageSutMetricCatalog).toHaveBeenCalledWith('payments', 'UAT', expect.any(AbortSignal));
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    expect(panel.textContent).toContain('Environment: UAT');
    expect(panel.textContent).toContain('Database health');
    expect(panel.textContent).toContain('DATA_SOURCE · payments-db');
    expect(panel.textContent).toContain('checkout-load');
    expect(panel.textContent).toContain('a'.repeat(64));
    expect(panel.textContent).toContain('b'.repeat(64));
    expect(panel.textContent).toContain('c'.repeat(64));
    expect(panel.textContent).toContain('Package resource provenance');
    expect(panel.textContent).toContain('Package-relative path: load/sut-metrics/sources/db-health.yaml');
    expect(panel.textContent).toContain('SHA-256:');
    expect(panel.textContent).toContain('d'.repeat(64));
    expect(panel.textContent).toContain('This view does not query metric sources.');
    expect(panel.textContent).not.toContain('must-not-render');
    expect(panel.querySelector('.att-package-sut-metrics-catalog-item')?.textContent).toContain('Database health');
    expect(panel.querySelector('.att-package-sut-metrics-catalog-item')?.textContent).toContain('db-health');
    expect(panel.querySelector<HTMLButtonElement>('button[aria-label="View Metric Source db-health"]')).toBeNull();
    expect(getPackageSutMetricsResource).not.toHaveBeenCalled();
    const mutationButtons = [...panel.querySelectorAll('button')].filter(item =>
      /create .*draft|validate draft|review draft|publish draft|discard draft/i.test(item.textContent || ''));
    expect(mutationButtons).toHaveLength(0);
    expect(panel.querySelector('textarea')).toBeNull();
    expect(panel.textContent).not.toContain('must-not-render');
  });

  it('states when no explicit Package SUT Metrics definitions are listed', async () => {
    const getPackageSutMetricCatalog = vi.fn(async () => ({
      schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'SIT',
      manifestDigest: 'a'.repeat(64), packageRevision: 'b'.repeat(64), runtimeConfigRevision: 'c'.repeat(64),
      metricSources: [], groups: [], profiles: [],
    }));
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog: getPackageSutMetricCatalog });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);

    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    expect(panel.textContent).toContain('This Package has no explicitly listed SUT Metrics definitions.');
    expect(panel.textContent).toContain('Manifest digest');
    expect(panel.textContent).not.toContain('global profile');
    const advanced = [...panel.querySelectorAll('details')].find(item => item.querySelector('summary')?.textContent?.includes('Advanced revision provenance'));
    expect(advanced?.open).toBe(false);
  });

  it('registers Package SUT Metrics source edits with the navigation guard and clears them on discard', async () => {
    const registerUnsavedChanges = vi.fn((_dirty: boolean, _description?: string) => () => {});
    const getPackageSutMetricsCatalog = vi.fn(async () => ({
      schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
      canEdit: true, metricSources: [{ id: 'db-health', name: 'Database health' }], groups: [], profiles: [],
    }));
    const getPackageSutMetricsResource = vi.fn(async () => ({
      packageId: 'payments', kind: 'source' as const, id: 'db-health', path: 'load/sut-metrics/sources/db-health.yaml',
      resourceDigest: 'a'.repeat(64), source: 'enabled: true\n', canEdit: true,
    }));
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageSutMetricsResource });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={registerUnsavedChanges} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);

    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    click(panel.querySelector<HTMLButtonElement>('button[aria-label="View Metric Source db-health"]')!);
    await settle();
    expect(panel.querySelector('[aria-label="SUT Metrics source publication workflow"]')).not.toBeNull();
    expect(panel.querySelector('.att-code-editor')).not.toBeNull();
    const editor = panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!;
    setValue(editor, 'enabled: false\n');
    expect(registerUnsavedChanges).toHaveBeenCalledWith(true, 'unpublished Package SUT Metrics changes');

    click(button('Discard source edits'));
    await settle();
    expect((panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]'))?.value).toBe('enabled: true\n');
    expect(registerUnsavedChanges).toHaveBeenCalledWith(false, 'unpublished Package SUT Metrics changes');
  });

  it('validates, reviews, publishes, and refreshes an edited Package resource', async () => {
    const resourceDigest = 'a'.repeat(64);
    const candidateResourceDigest = 'b'.repeat(64);
    const getPackageSutMetricsCatalog = vi.fn(async () => ({
      schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
      manifestDigest: 'c'.repeat(64), packageRevision: 'd'.repeat(64), runtimeConfigRevision: 'e'.repeat(64), canEdit: true,
      metricSources: [{ id: 'db-health', name: 'Database health' }], groups: [], profiles: [],
    }));
    const getPackageSutMetricsResource = vi.fn()
      .mockResolvedValueOnce({ packageId: 'payments', kind: 'source', id: 'db-health',
        path: 'load/sut-metrics/sources/db-health.yaml', resourceDigest, source: 'enabled: true\n', canEdit: true })
      .mockResolvedValueOnce({ packageId: 'payments', kind: 'source', id: 'db-health',
        path: 'load/sut-metrics/sources/db-health.yaml', resourceDigest: candidateResourceDigest, source: 'enabled: false\n', canEdit: true });
    const createPackageSutMetricsDraft = vi.fn(async () => ({ draftId: 'D-EDIT', packageId: 'payments',
      resourcePath: 'load/sut-metrics/sources/db-health.yaml', expectedResourceDigest: resourceDigest, state: 'draft' as const }));
    const packageSutMetricsDraftAction = vi.fn(async (_packageId: string, _draftId: string, action: 'validate' | 'review' | 'publish' | 'discard') => {
      if (action === 'validate') return { draftId: 'D-EDIT', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
        expectedResourceDigest: resourceDigest, state: 'validated', validation: { valid: true, resourceDigest: candidateResourceDigest, validationRevision: 'f'.repeat(64) } };
      if (action === 'review') return { draftId: 'D-EDIT', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
        expectedResourceDigest: resourceDigest, state: 'reviewed' };
      if (action === 'publish') return { draftId: 'D-EDIT', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
        expectedResourceDigest: resourceDigest, state: 'reviewed', published: true, refreshRequired: true,
        resourceDigest: candidateResourceDigest, environment: 'UAT', configurationIdentity: 'config-revision-UAT' };
      return { draftId: 'D-EDIT', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
        expectedResourceDigest: resourceDigest, state: 'discarded' };
    });
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageSutMetricsResource,
      createPackageSutMetricsDraft, packageSutMetricsDraftAction });
    const registerUnsavedChanges = vi.fn((_dirty: boolean, _description?: string) => () => {});
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={registerUnsavedChanges} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    click(panel.querySelector<HTMLButtonElement>('button[aria-label="View Metric Source db-health"]')!);
    await settle();
    setValue(panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!, 'enabled: false\n');
    expect(registerUnsavedChanges).toHaveBeenCalledWith(true, 'unpublished Package SUT Metrics changes');
    click(button('Create SUT Metrics draft'));
    await settle();
    expect(createPackageSutMetricsDraft).toHaveBeenCalledWith('payments', {
      resourcePath: 'load/sut-metrics/sources/db-health.yaml', environment: 'UAT', source: 'enabled: false\n', expectedResourceDigest: resourceDigest,
    }, expect.any(AbortSignal));
    click(button('Validate draft'));
    await settle();
    expect(panel.textContent).toContain('Validation passed.');
    click(button('Review draft'));
    await settle();
    click(button('Publish draft'));
    await settle(); await settle();
    expect(packageSutMetricsDraftAction.mock.calls.map(call => call[2])).toEqual(['validate', 'review', 'publish']);
    expect(getPackageSutMetricsCatalog).toHaveBeenCalledTimes(2);
    expect(getPackageSutMetricsResource).toHaveBeenCalledTimes(2);
    expect(panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')?.value).toBe('enabled: false\n');
  });

  it('shows bounded field diagnostics for a rejected validation and preserves source after draft discard', async () => {
    const resourceDigest = 'a'.repeat(64);
    const getPackageSutMetricsCatalog = vi.fn(async () => ({
      schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
      manifestDigest: 'c'.repeat(64), canEdit: true,
      metricSources: [{ id: 'db-health', name: 'Database health' }], groups: [], profiles: [],
    }));
    const getPackageSutMetricsResource = vi.fn(async () => ({ packageId: 'payments', kind: 'source' as const, id: 'db-health',
      path: 'load/sut-metrics/sources/db-health.yaml', resourceDigest, source: 'enabled: true\n', canEdit: true }));
    const createPackageSutMetricsDraft = vi.fn(async () => ({ draftId: 'D-INVALID', packageId: 'payments',
      resourcePath: 'load/sut-metrics/sources/db-health.yaml', expectedResourceDigest: resourceDigest, state: 'draft' as const }));
    const packageSutMetricsDraftAction = vi.fn(async (_packageId: string, _draftId: string, action: 'validate' | 'review' | 'publish' | 'discard') => {
      if (action === 'validate') throw new ApiError('Draft rejected at /Users/worker/private.yaml token=top-secret', { status: 400,
        diagnostics: [{ code: 'ATT-SUT-METRICS-FIELD', field: 'profiles[0].metricSourceIds[0]',
          summary: 'Metric Source was not found; password=private-value', path: '/Users/worker/package.yaml' }] });
      return { draftId: 'D-INVALID', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/db-health.yaml',
        expectedResourceDigest: resourceDigest, state: 'discarded' };
    });
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageSutMetricsResource,
      createPackageSutMetricsDraft, packageSutMetricsDraftAction });
    const registerUnsavedChanges = vi.fn((_dirty: boolean, _description?: string) => () => {});
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={registerUnsavedChanges} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    click(panel.querySelector<HTMLButtonElement>('button[aria-label="View Metric Source db-health"]')!);
    await settle();
    setValue(panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!, 'enabled: false\n');
    click(button('Create SUT Metrics draft')); await settle();
    click(button('Validate draft')); await settle();

    expect(panel.textContent).toContain('profiles[0].metricSourceIds[0]');
    expect(panel.textContent).toContain('Metric Source was not found');
    expect(panel.textContent).not.toContain('top-secret');
    expect(panel.textContent).not.toContain('private-value');
    expect(panel.textContent).not.toContain('/Users/worker');
    expect(panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')).toBeNull();
    expect(panel.querySelector('.att-code-editor pre')?.textContent).toBe('enabled: false\n');
    expect(panel.textContent).toContain('Discard draft and edit source');

    click(button('Discard draft and edit source')); await settle();
    const editor = panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!;
    expect(editor.readOnly).toBe(false);
    expect(editor.value).toBe('enabled: false\n');
    expect(registerUnsavedChanges).toHaveBeenCalledWith(true, 'unpublished Package SUT Metrics changes');
    click(button('Discard source edits'));
    await settle();
    expect(registerUnsavedChanges).toHaveBeenCalledWith(false, 'unpublished Package SUT Metrics changes');
  });

  it('adds a new Metric Source to an existing v2 Package inventory and publishes resource before Manifest refresh', async () => {
    const emptyResourceDigest = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
    const manifestDigest = 'a'.repeat(64);
    const candidateManifestDigest = 'b'.repeat(64);
    const resourceDigest = 'c'.repeat(64);
    const manifestSource = 'schemaVersion: att-package/v2\nmetadata:\n  name: Payments\nobservability:\n  metricSources: []\n  groups: []\n  profiles: []\n';
    const getPackageSutMetricsCatalog = vi.fn()
      .mockResolvedValueOnce({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
        manifestDigest, packageRevision: 'd'.repeat(64), runtimeConfigRevision: 'e'.repeat(64), canEdit: true, metricSources: [], groups: [], profiles: [] })
      .mockResolvedValue({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
        manifestDigest: candidateManifestDigest, packageRevision: 'f'.repeat(64), runtimeConfigRevision: 'e'.repeat(64), canEdit: true,
        metricSources: [{ id: 'new-health', name: 'New Metric Source' }], groups: [], profiles: [] });
    const getPackageManifest = vi.fn(async () => ({ packageId: 'payments', manifestDigest, present: true, canEdit: true,
      available: true, text: manifestSource, redacted: false }));
    const getPackageSutMetricsResource = vi.fn(async () => ({ packageId: 'payments', kind: 'source' as const, id: 'new-health',
      path: 'load/sut-metrics/sources/new-health.yaml', resourceDigest, source: 'schemaVersion: att-sut-metrics-metric-source/v1\nid: new-health\n', canEdit: true }));
    const createPackageSutMetricsDraft = vi.fn(async () => ({ draftId: 'D-NEW', packageId: 'payments',
      resourcePath: 'load/sut-metrics/sources/new-health.yaml', expectedResourceDigest: emptyResourceDigest,
      recoveredExistingResource: false, candidateResourceDigest: resourceDigest,
      expectedManifestDigest: manifestDigest, candidateManifestDigest, activationMode: 'resource-then-manifest' as const, state: 'draft' as const }));
    const packageSutMetricsDraftAction = vi.fn(async (_packageId: string, _draftId: string, action: 'validate' | 'review' | 'publish' | 'discard') => {
      if (action === 'validate') return { draftId: 'D-NEW', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
        expectedResourceDigest: emptyResourceDigest, recoveredExistingResource: false, candidateResourceDigest: resourceDigest,
        expectedManifestDigest: manifestDigest, candidateManifestDigest,
        activationMode: 'resource-then-manifest' as const, state: 'validated',
        validation: { valid: true, resourceDigest, candidateManifestDigest, validationRevision: '1'.repeat(64) } };
      if (action === 'review') return { draftId: 'D-NEW', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
        expectedResourceDigest: emptyResourceDigest, recoveredExistingResource: false, candidateResourceDigest: resourceDigest,
        expectedManifestDigest: manifestDigest, candidateManifestDigest,
        activationMode: 'resource-then-manifest' as const, state: 'reviewed' };
      if (action === 'publish') return { draftId: 'D-NEW', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
        expectedResourceDigest: emptyResourceDigest, recoveredExistingResource: false, candidateResourceDigest: resourceDigest,
        expectedManifestDigest: manifestDigest, candidateManifestDigest,
        activationMode: 'resource-then-manifest' as const, beforeResourceDigest: emptyResourceDigest,
        manifestDigest: candidateManifestDigest, state: 'reviewed', published: true, refreshRequired: true,
        resourceDigest, environment: 'UAT', configurationIdentity: 'config-revision-UAT' };
      return { draftId: 'D-NEW', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
        expectedResourceDigest: emptyResourceDigest, state: 'discarded' };
    });
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageManifest, getPackageSutMetricsResource,
      createPackageSutMetricsDraft, packageSutMetricsDraftAction });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    expect(getPackageManifest).not.toHaveBeenCalled();
    setValue(panel.querySelector<HTMLInputElement>('input[aria-label="Resource ID"]')!, 'new-health');
    click(button('Prepare new resource')); await settle();
    expect(getPackageManifest).toHaveBeenCalledWith('payments', 'source', undefined, expect.any(AbortSignal));
    expect(panel.textContent).toContain('load/sut-metrics/sources/new-health.yaml');
    const editor = panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!;
    expect(editor.value).toContain('id: new-health');

    click(button('Create SUT Metrics draft')); await settle();
    const [, draftInput] = createPackageSutMetricsDraft.mock.calls[0] as unknown as [string, {
      resourcePath: string; expectedResourceDigest: string; candidateManifestSource: string; expectedManifestDigest: string;
    }];
    expect(draftInput).toMatchObject({ resourcePath: 'load/sut-metrics/sources/new-health.yaml',
      expectedResourceDigest: emptyResourceDigest, expectedManifestDigest: manifestDigest });
    const candidateManifest = parseDocument(draftInput.candidateManifestSource).toJS() as { observability: { metricSources: string[]; groups: string[]; profiles: string[] }; metadata: { name: string } };
    expect(candidateManifest.observability.metricSources).toEqual(['load/sut-metrics/sources/new-health.yaml']);
    expect(candidateManifest.observability.groups).toEqual([]);
    expect(candidateManifest.observability.profiles).toEqual([]);
    expect(candidateManifest.metadata.name).toBe('Payments');

    click(button('Validate draft')); await settle();
    click(button('Review draft')); await settle();
    click(button('Publish draft')); await settle(); await settle();
    expect(packageSutMetricsDraftAction.mock.calls.map(call => call[2])).toEqual(['validate', 'review', 'publish']);
    expect(getPackageSutMetricsCatalog).toHaveBeenCalledTimes(2);
    expect(getPackageSutMetricsResource).toHaveBeenCalledTimes(1);
  });

  it.each([
    { label: 'a missing recovery flag', recoveredExistingResource: undefined },
    { label: 'a recovery flag that contradicts the empty before digest', recoveredExistingResource: true },
  ])('rejects a new resource draft with $label', async ({ recoveredExistingResource }) => {
    const emptyResourceDigest = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
    const manifestDigest = 'a'.repeat(64);
    const candidateManifestDigest = 'b'.repeat(64);
    const candidateResourceDigest = 'c'.repeat(64);
    const manifestSource = 'schemaVersion: att-package/v2\nmetadata:\n  name: Payments\nobservability:\n  metricSources: []\n  groups: []\n  profiles: []\n';
    const api = apiWith({
      getPackageSutMetricsCatalog: vi.fn(async () => ({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments',
        environment: 'UAT', manifestDigest, canEdit: true, metricSources: [], groups: [], profiles: [] })),
      getPackageManifest: vi.fn(async () => ({ packageId: 'payments', manifestDigest, present: true, canEdit: true,
        available: true, text: manifestSource, redacted: false })),
      createPackageSutMetricsDraft: vi.fn(async () => ({ draftId: 'D-BAD-IDENTITY', packageId: 'payments',
        resourcePath: 'load/sut-metrics/sources/new-health.yaml', expectedResourceDigest: emptyResourceDigest,
        recoveredExistingResource, candidateResourceDigest, expectedManifestDigest: manifestDigest,
        candidateManifestDigest, activationMode: 'resource-then-manifest' as const, state: 'draft' as const })),
    });
    const panel = await prepareNewMetricSource(api);

    click(button('Create SUT Metrics draft')); await settle();

    expect(panel.textContent).toContain('The Server draft does not match the selected resource. The action was stopped.');
    expect(button('Validate draft').disabled).toBe(true);
  });

  it('rejects new-resource validation when the validated digest differs from the staged candidate', async () => {
    const emptyResourceDigest = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
    const manifestDigest = 'a'.repeat(64);
    const candidateManifestDigest = 'b'.repeat(64);
    const candidateResourceDigest = 'c'.repeat(64);
    const differentResourceDigest = 'd'.repeat(64);
    const manifestSource = 'schemaVersion: att-package/v2\nmetadata:\n  name: Payments\nobservability:\n  metricSources: []\n  groups: []\n  profiles: []\n';
    const identity = { draftId: 'D-BAD-DIGEST', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/new-health.yaml',
      expectedResourceDigest: emptyResourceDigest, recoveredExistingResource: false, candidateResourceDigest,
      expectedManifestDigest: manifestDigest, candidateManifestDigest,
      activationMode: 'resource-then-manifest' as const };
    const api = apiWith({
      getPackageSutMetricsCatalog: vi.fn(async () => ({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments',
        environment: 'UAT', manifestDigest, canEdit: true, metricSources: [], groups: [], profiles: [] })),
      getPackageManifest: vi.fn(async () => ({ packageId: 'payments', manifestDigest, present: true, canEdit: true,
        available: true, text: manifestSource, redacted: false })),
      createPackageSutMetricsDraft: vi.fn(async () => ({ ...identity, state: 'draft' as const })),
      packageSutMetricsDraftAction: vi.fn(async (_packageId, _draftId, action) => action === 'validate'
        ? { ...identity, state: 'validated', validation: { valid: true, resourceDigest: differentResourceDigest,
          candidateManifestDigest, validationRevision: '1'.repeat(64) } }
        : { ...identity, state: 'reviewed' }),
    });
    const panel = await prepareNewMetricSource(api);
    click(button('Create SUT Metrics draft')); await settle();

    click(button('Validate draft')); await settle();

    expect(panel.textContent).toContain('The Server did not return complete draft validation revisions. The action was stopped.');
    expect(button('Review draft').disabled).toBe(true);
  });

  it('recovers an already-written unlisted resource in Explorer after a publisher restart', async () => {
    const manifestDigest = 'a'.repeat(64);
    const candidateManifestDigest = 'b'.repeat(64);
    const recoveredDigest = 'c'.repeat(64);
    const manifestSource = 'schemaVersion: att-package/v2\nmetadata:\n  name: Payments\nobservability:\n  metricSources: []\n  groups: []\n  profiles: []\n';
    const packageSource = 'schemaVersion: att-sut-metrics-metric-source/v1\nid: recovered-health\n';
    const getPackageSutMetricsCatalog = vi.fn()
      .mockResolvedValueOnce({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
        manifestDigest, canEdit: true, metricSources: [], groups: [], profiles: [] })
      .mockResolvedValue({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments', environment: 'UAT',
        manifestDigest: candidateManifestDigest, canEdit: true,
        metricSources: [{ id: 'recovered-health', name: 'Recovered health' }], groups: [], profiles: [] });
    const getPackageManifest = vi.fn(async () => ({ packageId: 'payments', manifestDigest, present: true, canEdit: true,
      available: true, text: manifestSource, redacted: false }));
    const getPackageSutMetricsResource = vi.fn(async () => ({ packageId: 'payments', kind: 'source' as const, id: 'recovered-health',
      path: 'load/sut-metrics/sources/recovered-health.yaml', resourceDigest: recoveredDigest, source: packageSource, canEdit: true }));
    const createPackageSutMetricsDraft = vi.fn(async () => ({ draftId: 'D-RECOVERED', packageId: 'payments',
      resourcePath: 'load/sut-metrics/sources/recovered-health.yaml', expectedResourceDigest: recoveredDigest,
      recoveredExistingResource: true, candidateResourceDigest: recoveredDigest,
      expectedManifestDigest: manifestDigest, candidateManifestDigest, activationMode: 'resource-then-manifest' as const, state: 'draft' as const }));
    const packageSutMetricsDraftAction = vi.fn(async (_packageId: string, _draftId: string, action: 'validate' | 'review' | 'publish' | 'discard') => {
      const identity = { draftId: 'D-RECOVERED', packageId: 'payments', resourcePath: 'load/sut-metrics/sources/recovered-health.yaml',
        expectedResourceDigest: recoveredDigest, recoveredExistingResource: true, candidateResourceDigest: recoveredDigest,
        expectedManifestDigest: manifestDigest, candidateManifestDigest, activationMode: 'resource-then-manifest' as const };
      if (action === 'validate') return { ...identity, state: 'validated',
        validation: { valid: true, resourceDigest: recoveredDigest, candidateManifestDigest, validationRevision: '1'.repeat(64) } };
      if (action === 'review') return { ...identity, state: 'reviewed' };
      if (action === 'publish') return { ...identity, beforeResourceDigest: recoveredDigest, resourceDigest: recoveredDigest,
        manifestDigest: candidateManifestDigest, state: 'reviewed', published: true, refreshRequired: true,
        environment: 'UAT', configurationIdentity: 'config-revision-UAT' };
      return { ...identity, state: 'discarded' };
    });
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageManifest, getPackageSutMetricsResource,
      createPackageSutMetricsDraft, packageSutMetricsDraftAction });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);

    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    setValue(panel.querySelector<HTMLInputElement>('input[aria-label="Resource ID"]')!, 'recovered-health');
    click(button('Prepare new resource')); await settle();
    expect(panel.textContent).toContain('load/sut-metrics/sources/recovered-health.yaml');
    setValue(panel.querySelector<HTMLTextAreaElement>('textarea[aria-label="Metric Source YAML"]')!, packageSource);

    click(button('Create SUT Metrics draft')); await settle();
    expect(panel.textContent).toContain('Draft created. Validate it before review.');
    expect(panel.textContent).not.toContain('Server draft does not match');
    click(button('Validate draft')); await settle();
    click(button('Review draft')); await settle();
    click(button('Publish draft')); await settle(); await settle();

    expect(packageSutMetricsDraftAction.mock.calls.map(call => call[2])).toEqual(['validate', 'review', 'publish']);
    expect(getPackageSutMetricsCatalog).toHaveBeenCalledTimes(2);
    expect(getPackageSutMetricsResource).toHaveBeenCalledTimes(1);
  });

  it.each([
    { kind: 'group', path: 'load/sut-metrics/groups/new-thing.yaml', schema: 'att-sut-metrics-group/v1', label: 'Metric Group YAML' },
    { kind: 'profile', path: 'load/sut-metrics/profiles/new-thing.yaml', schema: 'att-sut-metrics-profile/v2', label: 'Metrics Profile YAML' },
  ] as const)('prepares a $kind starter and matching Package inventory path', async ({ kind, path, schema, label }) => {
    const manifestDigest = 'a'.repeat(64);
    const api = Object.assign(apiWith({}), {
      getPackageSutMetricsCatalog: vi.fn(async () => ({ schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments',
        environment: 'UAT', manifestDigest, canEdit: true, metricSources: [], groups: [], profiles: [] })),
      getPackageManifest: vi.fn(async () => ({ packageId: 'payments', manifestDigest, present: true, canEdit: true,
        available: true, redacted: false, text: 'schemaVersion: att-package/v2\nobservability:\n  metricSources: []\n  groups: []\n  profiles: []\n' })),
      createPackageSutMetricsDraft: vi.fn(),
    });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    setValue(panel.querySelector<HTMLSelectElement>('select[aria-label="Resource kind"]')!, kind);
    setValue(panel.querySelector<HTMLInputElement>('input[aria-label="Resource ID"]')!, 'new-thing');
    click(button('Prepare new resource')); await settle();
    expect(panel.textContent).toContain(path);
    expect(panel.querySelector<HTMLTextAreaElement>(`textarea[aria-label="${label}"]`)?.value).toContain(schema);
    expect(api.createPackageSutMetricsDraft).not.toHaveBeenCalled();
  });

  it('requires an existing att-package/v2 Manifest before preparing a new resource', async () => {
    const getPackageSutMetricsCatalog = vi.fn(async () => ({ schemaVersion: 'att-package-sut-metrics-catalog/v1',
      packageId: 'payments', environment: 'UAT', manifestDigest: 'a'.repeat(64), canEdit: true,
      metricSources: [], groups: [], profiles: [] }));
    const getPackageManifest = vi.fn(async () => ({ packageId: 'payments', manifestDigest: 'a'.repeat(64), present: true,
      canEdit: true, available: true, redacted: false, text: 'schemaVersion: att-package/v1\n' }));
    const createPackageSutMetricsDraft = vi.fn();
    const api = Object.assign(apiWith({}), { getPackageSutMetricsCatalog, getPackageManifest, createPackageSutMetricsDraft });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="UAT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    const panel = container.querySelector<HTMLDetailsElement>('.att-package-sut-metrics')!;
    click(panel.querySelector('summary')!);
    setValue(panel.querySelector<HTMLInputElement>('input[aria-label="Resource ID"]')!, 'new-health');
    click(button('Prepare new resource')); await settle();
    expect(panel.textContent).toContain('migrating the Package inventory to att-package/v2 first');
    expect(createPackageSutMetricsDraft).not.toHaveBeenCalled();
    expect(panel.querySelector('textarea[aria-label="Metric Source YAML"]')).toBeNull();
  });

  it('shows an environment-neutral full-width resource list until a package resource is selected', async () => {
    const flow = resource('flow', 'checkout');
    const api = apiWith({ listResources: vi.fn(async () => ({ items: [flow], total: 1, nextCursor: null })) });
    const onResourceRouteChange = vi.fn();
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={onResourceRouteChange}
      onJobAccepted={() => {}} />);
    await settle();

    expect(container.querySelector('.att-explorer-workspace')?.classList.contains('is-list-first')).toBe(true);
    expect(container.querySelector('[aria-label="Resource details"]')?.hasAttribute('hidden')).toBe(true);
    expect(container.querySelector('[aria-label="Contextual actions"]')?.hasAttribute('hidden')).toBe(true);
    expect([...container.querySelectorAll('label')].some(label => label.textContent?.includes('Run, Debug, and Load environment'))).toBe(false);
    expect(container.textContent).toContain('Choose an execution environment from the RUN or Quick LOAD editor.');
    expect(container.querySelector('.att-explorer-table')?.closest('.att-explorer-navigation')).not.toBeNull();
  });

  it('restores list search, category, sorting, loaded pages, and scroll after returning from a resource route', async () => {
    const flow = resource('flow', 'flow-1');
    const listResources = vi.fn(async (_packageId: string, query: { cursor?: string }) => query.cursor === 'flows-page-2'
      ? { items: [resource('flow', 'flow-51')], total: 51, nextCursor: null }
      : { items: [flow], total: 51, nextCursor: 'flows-page-2' });
    const api = apiWith({ listResources });
    const props = {
      api, packageId: 'payments-list-state', resourceRoute: null, environment: '',
      registerUnsavedChanges: () => () => {}, onResourceRouteChange: () => {},
      onEnvironmentChange: () => {}, onJobAccepted: () => {},
    };
    await render(<ExplorerPage {...props} />);
    const search = container.querySelector<HTMLInputElement>('.att-query-filter-panel input[type="search"]')!;
    setValue(search, 'payment flow');
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 250)); });
    click([...container.querySelectorAll<HTMLElement>('[role="radio"]')].find(item => item.textContent === 'Flows')!);
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 250)); });
    click(container.querySelector('.att-explorer-sort')!);
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 250)); });
    click(button('Load more'));
    await settle();
    const scrollContainer = container.querySelector<HTMLDivElement>('.att-explorer-table-scroll')!;
    scrollContainer.scrollTop = 37;
    act(() => scrollContainer.dispatchEvent(new Event('scroll', { bubbles: true })));
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 120)); });

    const callsBeforeReturn = listResources.mock.calls.length;
    await act(async () => root.render(null));
    await render(<ExplorerPage {...props} resourceRoute={{ type: 'flow', resourceId: 'flow-1' }} />);
    await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 250)); });

    expect(container.querySelector<HTMLInputElement>('.att-query-filter-panel input[type="search"]')?.value).toBe('payment flow');
    expect([...container.querySelectorAll<HTMLElement>('[role="radio"]')].find(item => item.textContent === 'Flows')?.getAttribute('aria-checked')).toBe('true');
    expect(container.querySelector('.att-explorer-sort')?.textContent).toContain('↓');
    expect(listResources.mock.calls.slice(callsBeforeReturn).map(call => call[1])).toEqual([
      expect.objectContaining({ query: 'payment flow', type: 'flow', sort: 'name', direction: 'desc', limit: 50 }),
      expect.objectContaining({ query: 'payment flow', type: 'flow', sort: 'name', direction: 'desc', cursor: 'flows-page-2', limit: 50 }),
    ]);
    expect(container.querySelector<HTMLDivElement>('.att-explorer-table-scroll')?.scrollTop).toBe(37);
  });

  it('restores the Dependencies deep link and routes resolved references to the target definition', async () => {
    const origin = resource('flow', 'payment-flow', {
      references: [{ type: 'template', resourceId: 'payment-template', logicalId: 'payment-template', resolution: 'resolved' }],
      referencedBy: [],
    });
    const api = apiWith({ getResource: vi.fn(async () => ({ resource: origin, definition: {} })) });
    const onResourceRouteChange = vi.fn();
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={{ type: 'flow', resourceId: 'payment-flow', tab: 'relations' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={onResourceRouteChange}
      onJobAccepted={() => {}} />);
    await settle();

    expect(container.querySelector('[data-tab="relations"]')?.getAttribute('aria-selected')).toBe('true');
    click(button('payment-template'));
    expect(onResourceRouteChange).toHaveBeenLastCalledWith({ type: 'template', resourceId: 'payment-template', tab: 'definition' });

    const helperApi = apiWith({ getResource: vi.fn(async () => ({ resource: resource('flow', 'helper-caller', {
      references: [{ type: 'tool', logicalId: 'MQHelper.publish', targetKind: 'helper', resolution: 'resolved' }],
    }), definition: {} })) });
    await render(<ExplorerPage api={helperApi} packageId="payments" resourceRoute={{ type: 'flow', resourceId: 'helper-caller', tab: 'relations' }} environment="SIT"
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}}
      onJobAccepted={() => {}} />);
    await settle();
    expect(container.textContent).toContain('This call resolves through the package Helper configuration.');
    expect(container.textContent).toContain('It is not a separate Tool resource');
    expect([...container.querySelectorAll('button')].some(item => item.textContent === 'MQHelper.publish')).toBe(false);
  });

  it('supports keyboard source tabs, registers dirty sidecar edits, and carries anonymous labels into accepted jobs', async () => {
    const template = resource('template', 'checkout');
    const debugInput: JsonObject = { inputs: { credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { retries: 2 } };
    const getSafeSource = vi.fn(async () => ({ available: true, text: 'template: checkout\n', format: 'yaml' }));
    const getDebugForm = vi.fn(async () => ({ target: { type: 'template' as const, id: 'checkout' }, input: debugInput, redacted: true }));
    const submitDebug = vi.fn(async () => ({ jobId: 'job-99' }));
    const api = apiWith({
      listResources: vi.fn(async () => ({ items: [template], total: 1, nextCursor: null })),
      getResource: vi.fn(async () => ({ resource: template, definition: { template: 'checkout' } })),
      getSafeSource, getDebugForm, submitDebug,
    });
    const onResourceRouteChange = vi.fn();
    const onJobAccepted = vi.fn();
    const onScheduleDebug = vi.fn();
    const dirtyTransitions: boolean[] = [];
    const registerUnsavedChanges = vi.fn((dirty: boolean) => {
      dirtyTransitions.push(dirty);
      return () => {};
    });
    const props = {
      api, packageId: 'payments', resourceRoute: { type: 'template' as const, resourceId: 'checkout' }, environment: '',
      registerUnsavedChanges, onResourceRouteChange, onEnvironmentChange: vi.fn(), onJobAccepted, onScheduleDebug,
    };
    await render(<ExplorerPage {...props} />);
    await settle();

    const tabs = container.querySelector<HTMLElement>('[aria-label="Resource content"]');
    expect(tabs).not.toBeNull();
    act(() => tabs!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    await settle();
    expect(container.querySelector('[data-tab="source"]')?.getAttribute('aria-selected')).toBe('true');
    expect(getSafeSource).toHaveBeenCalled();

    click(button('Run this target'));
    await settle();
    expect(container.textContent).toContain('Hidden package-authored values are preserved');
    expect(button('Schedule…').disabled).toBe(false);
    expect(container.textContent).toContain('Scheduling keeps this validated Debug target, environment and input snapshot.');
    click(button('Schedule…'));
    await settle();
    expect(api.previewDebug).toHaveBeenCalledWith(expect.objectContaining({
      packageId: 'payments', target: { type: 'template', id: 'checkout' }, input: debugInput,
    }));
    expect(onScheduleDebug).toHaveBeenCalledWith(expect.objectContaining({
      packageId: 'payments', draftId: 'draft-1', executionType: 'run', legacyDebug: true,
      target: { type: 'template', id: 'checkout' },
    }));
    click(container.querySelector('.att-sidecar-keep-default input')!);
    const type = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    setValue(type!, 'string');
    setValue(container.querySelector<HTMLInputElement>('input[aria-label="credential value"]')!, 'temporary-override');
    await settle();
    expect(dirtyTransitions).toContain(true);

    click(button('Reset defaults'));
    await settle();
    expect(dirtyTransitions.at(-1)).toBe(false);
    expect(container.querySelector<HTMLInputElement>('.att-sidecar-keep-default input')?.checked).toBe(true);
    click(container.querySelector('.att-sidecar-keep-default input')!);
    const resetType = [...container.querySelectorAll('select')].find(item => item.getAttribute('aria-label') === 'credential value type');
    setValue(resetType!, 'string');
    setValue(container.querySelector<HTMLInputElement>('input[aria-label="credential value"]')!, 'temporary-override');
    await settle();

    setValue(container.querySelector<HTMLInputElement>('input[maxlength="120"]')!, 'anonymous-run-42');
    click(button('Run Now'));
    await settle();
    expect(submitDebug).toHaveBeenCalledWith(expect.objectContaining({ packageId: 'payments', principal: 'anonymous-run-42' }));
    expect(onJobAccepted).toHaveBeenCalledWith('job-99');
    expect(dirtyTransitions.at(-1)).toBe(false);
  });

  it('keeps an open Debug form and authored edits across tab visibility restores', async () => {
    const template = resource('template', 'checkout');
    const getDebugForm = vi.fn(async () => ({
      target: { type: 'template' as const, id: 'checkout' },
      input: { inputs: { credential: 'package-default' } },
    }));
    const api = apiWith({
      listResources: vi.fn(async () => ({ items: [template], total: 1, nextCursor: null })),
      getResource: vi.fn(async () => ({ resource: template, definition: { template: 'checkout' } })),
      getDebugForm,
    });
    await render(<ExplorerPage api={api} packageId="payments"
      resourceRoute={{ type: 'template' as const, resourceId: 'checkout' }} environment=""
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}}
      onJobAccepted={() => {}} />);
    await settle();

    click(button('Run this target'));
    await settle();
    const input = container.querySelector<HTMLInputElement>('input[aria-label="credential value"]');
    expect(input).not.toBeNull();
    setValue(input!, 'edited-value');
    click(button('Validate and preview'));
    await settle();
    expect(container.textContent).toContain('accepted: true');

    // Mirror AppShell's suspension handler: clear cached projections, then let the
    // Explorer drop only the validated draft while retaining the authored form.
    act(() => { inspectionCache.clear(true); document.dispatchEvent(new Event('visibilitychange')); });
    await settle();

    const after = container.querySelector<HTMLInputElement>('input[aria-label="credential value"]');
    expect(after).not.toBeNull();
    expect(after!.value).toBe('edited-value');
    expect(container.textContent).toContain('Run');
    expect(container.textContent).not.toContain('accepted: true');
  });

  it('shows anonymous unauthorized resource errors in the page without exposing the response body', async () => {
    const api = apiWith({ listResources: vi.fn(async () => { throw Object.assign(new Error('authorization header rejected'), { status: 401 }); }) });
    await render(<ExplorerPage api={api} packageId="payments" resourceRoute={null} environment=""
      registerUnsavedChanges={() => () => {}} onResourceRouteChange={() => {}} onJobAccepted={() => {}} />);
    await settle();
    expect(container.textContent).toContain('Your session has expired.');
    expect(container.textContent).not.toContain('authorization header rejected');
  });

  it('renders hidden and missing configuration sides distinctly and omits hidden values', async () => {
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }],
        globals: {}, sections: [],
      })),
      compareConfiguration: vi.fn(async () => ({
        state: 'ready', leftEnvironment: 'left', rightEnvironment: 'right',
        fields: [{ path: 'database.password', left: { state: 'hidden', value: 'must-not-display' }, right: { state: 'missing' }, change: 'changed' }],
      })),
    });
    await render(<ConfigurationExplorer api={api} packageId="payments" environment=""
      onEnvironmentChange={() => {}} initialMode="declared" />);
    await settle();
    click(button('Compare'));
    await settle();
    expect(container.textContent).toContain('hidden');
    expect(container.textContent).toContain('not declared');
    expect(container.textContent).not.toContain('must-not-display');
  });

  it('identifies the package file shared by both sides of a comparison', async () => {
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }],
        globals: {}, sections: [],
      })),
      compareConfiguration: vi.fn(async () => ({
        state: 'ready', leftEnvironment: 'left', rightEnvironment: 'right',
        fields: [{ path: 'dbhelpers.shared.readOnly', source: 'config/dbhelpers/shared.yaml',
          left: { state: 'visible', value: false, origin: 'profile' },
          right: { state: 'visible', value: false, origin: 'profile' }, change: 'same' }],
      })),
    });
    await render(<ConfigurationExplorer api={api} packageId="payments" environment=""
      onEnvironmentChange={() => {}} initialMode="declared" />);
    click(button('Compare'));
    await settle();
    expect(container.textContent).toContain('Shared source: config/dbhelpers/shared.yaml');
    expect(container.textContent).toContain('environment');
  });

  it('scopes Compare pages to the selected section and preserves that section while loading more', async () => {
    const compareConfiguration = vi.fn(async (_packageId: string, query: { section: string; offset?: number }) => ({
      state: 'ready', section: query.section, nextOffset: query.offset === 0 ? 50 : null,
      total: 60, fields: [{ path: `${query.section}.field${query.offset || 0}`, left: { state: 'visible', value: 1 }, right: { state: 'visible', value: 2 }, change: 'changed' }],
    }));
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', globals: {},
        environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }],
        sections: [{ id: 'globals', title: 'Global fields' }, { id: 'dbhelpers', title: 'DB helpers' }],
      })),
      compareConfiguration,
    });
    await render(<ConfigurationExplorer api={api} packageId="payments" environment=""
      onEnvironmentChange={() => {}} initialMode="compare" />);
    await settle();
    expect(compareConfiguration.mock.calls[0][1]).toMatchObject({ left: 'left', right: 'right', section: 'dbhelpers', offset: 0, limit: 50 });

    const section = [...container.querySelectorAll('label')].find(label => label.textContent?.startsWith('Compare section'))?.querySelector('select');
    expect(section).toBeTruthy();
    setValue(section!, 'globals');
    await settle();
    expect(compareConfiguration.mock.calls[1][1]).toMatchObject({ section: 'globals', offset: 0, limit: 50 });
    setValue(section!, 'dbhelpers');
    await settle();
    expect(compareConfiguration.mock.calls[2][1]).toMatchObject({ section: 'dbhelpers', offset: 0, limit: 50 });

    click(button('Load more configuration'));
    await settle();
    expect(compareConfiguration.mock.calls[3][1]).toMatchObject({ section: 'dbhelpers', offset: 50, limit: 50 });
    expect(container.textContent).toContain('dbhelpers.field0');
    expect(container.textContent).toContain('dbhelpers.field50');
  });

  it('ignores stale Compare responses after a section change and refreshes after profile changes', async () => {
    const pending: Array<(response: ConfigurationResponse) => void> = [];
    const compareConfiguration = vi.fn<ExplorerApi['compareConfiguration']>((_packageId, _query, _signal) =>
      new Promise<ConfigurationResponse>(resolve => pending.push(resolve)));
    const api = apiWith({
      getDeclaredConfiguration: vi.fn(async () => ({
        state: 'ready', globals: {},
        environments: [{ name: 'left', state: 'active', default: true }, { name: 'right', state: 'active' }, { name: 'stage', state: 'active' }],
        sections: [{ id: 'globals', title: 'Global fields' }, { id: 'dbhelpers', title: 'DB helpers' }],
      })),
      compareConfiguration,
    });
    await render(<ConfigurationExplorer api={api} packageId="payments" environment=""
      onEnvironmentChange={() => {}} initialMode="compare" />);
    await settle();
    const section = [...container.querySelectorAll('label')].find(label => label.textContent?.startsWith('Compare section'))?.querySelector('select');
    setValue(section!, 'globals');
    await settle();
    expect(compareConfiguration).toHaveBeenCalledTimes(2);
    expect(compareConfiguration.mock.calls[1][1]).toMatchObject({ section: 'globals' });

    await act(async () => { pending[0]({ state: 'ready', fields: [{ path: 'stale-db', left: { state: 'visible' }, right: { state: 'visible' }, change: 'changed' }] }); await Promise.resolve(); });
    await settle();
    expect(container.textContent).not.toContain('stale-db');
    await act(async () => { pending[1]({ state: 'ready', fields: [{ path: 'fresh-global', left: { state: 'visible' }, right: { state: 'visible' }, change: 'changed' }] }); await Promise.resolve(); });
    await settle();
    expect(container.textContent).toContain('fresh-global');

    setValue(section!, 'dbhelpers');
    await settle();
    await act(async () => { pending[2]({ state: 'ready', fields: [{ path: 'fresh-db', left: { state: 'visible' }, right: { state: 'visible' }, change: 'changed' }] }); await Promise.resolve(); });
    await settle();
    expect(container.textContent).toContain('fresh-db');

    const leftProfile = container.querySelector<HTMLSelectElement>('select[aria-label="Left environment"]');
    setValue(leftProfile!, 'stage');
    await settle();
    expect(compareConfiguration.mock.calls[3][1]).toMatchObject({ left: 'stage', right: 'right', section: 'dbhelpers', offset: 0 });
  });
});
