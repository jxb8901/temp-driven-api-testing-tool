// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { NativeTestCaseAuthoringWorkspace } from './NativeTestCaseAuthoringWorkspace';
import type { ExplorerApi } from './types';

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
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

async function settle() { for (let i = 0; i < 6; i++) await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); }); }
function button(label: string): HTMLButtonElement {
  const match = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === label);
  if (!match) throw new Error(`Button not found: ${label}`);
  return match;
}
function context(): AppShellContextValue {
  return { selectedPackage: 'payments', selectedEnvironment: 'SIT', themePreference: 'system', uiLocale: 'en', uiLocalePreference: 'en',
    currentUser: null, accessCapabilities: { operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false },
    preferenceStatus: 'idle', canRevealConfigurationFields: false, onPackageChange: vi.fn(), onEnvironmentChange: vi.fn(),
    onThemePreferenceChange: vi.fn(), onUiLocaleChange: vi.fn(), onJobAccepted: vi.fn() };
}

describe('NativeTestCaseAuthoringWorkspace', () => {
  it('previews safe inline cases, selects one, and submits through the v2 RUN draft API', async () => {
    const definition = `schemaVersion: att-testcase/v1
id: payments.refund
name: Refund
flow: checkout.v1
caseIdExpression: "\${DATA.id}"
dataSource:
  type: inline
  records:
    - recordId: first
      data: {id: R-001}
    - recordId: second
      data: {id: R-002}
`;
    const getSource = vi.fn(async (_packageId: string, query: { kind: string }, _signal?: AbortSignal) => query.kind === 'manifest'
      ? { available: true, present: true, logicalPath: 'package.yaml', format: 'yaml', text: 'schemaVersion: att-package/v2\nresources:\n  testcaseRoot: testcase\n', sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }
      : { available: true, present: true, logicalPath: 'testcase/refund/testcase.yaml', format: 'yaml', text: definition, sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' });
    const previewNative = vi.fn(async () => ({ kind: 'NativeTestCasePreview', valid: true, sourceType: 'inline',
      sourceReference: 'testcase/refund/testcase.yaml', definitionRevision: 'def-revision', packageSourceRevision: 'base-1',
      registryRevision: 'registry-1', resolvedCount: 2, selectedCount: 2, previewCount: 2, truncated: false,
      diagnostics: [], cases: [
        { qualifiedCaseId: 'payments.refund.R-001', caseId: 'R-001', definitionId: 'payments.refund', flowId: 'checkout.v1',
          tags: [], inputs: { id: 'R-001', password: '[REDACTED]' }, redacted: true,
          provenance: { sourceType: 'inline', sourceReference: 'testcase/refund/testcase.yaml', recordId: 'first', recordIndex: 0 } },
        { qualifiedCaseId: 'payments.refund.R-002', caseId: 'R-002', definitionId: 'payments.refund', flowId: 'checkout.v1',
          tags: [], inputs: { id: 'R-002', password: '[REDACTED]' }, redacted: true,
          provenance: { sourceType: 'inline', sourceReference: 'testcase/refund/testcase.yaml', recordId: 'second', recordIndex: 1 } },
      ] }));
    const previewRun = vi.fn(async () => ({ draftId: 'R-native', expiresAt: '2099-01-01T00:00:00Z' }));
    const submitRun = vi.fn(async () => ({ jobId: 'J-native' }));
    const onJobAccepted = vi.fn();
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1', name: 'Checkout' }], total: 1 })),
      getManagedPackageAuthoringSource: getSource, previewNativeTestCase: previewNative,
      supportsNativeCaseRun: vi.fn(() => true), previewRun, submitRun,
    } as unknown as ExplorerApi;

    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/refund/testcase.yaml" onJobAccepted={onJobAccepted} />
    </AppShellContext.Provider>));
    await settle();
    await act(async () => button('Preview / Resolve').click());
    expect(host.textContent).toContain('2 resolved Case(s)');
    expect(host.textContent).toContain('payments.refund.R-001');
    expect(previewNative).toHaveBeenCalledWith('payments', { path: 'testcase/refund/testcase.yaml', source: definition }, expect.any(AbortSignal));
    const preview = host.querySelector<HTMLElement>('[aria-label="Resolved Case design-time preview"]');
    expect(preview?.textContent).toContain('redacted by Server policy');
    expect(preview?.textContent).not.toContain('secret-one');
    expect(preview?.textContent).not.toContain('secret-two');

    const checkboxes = [...host.querySelectorAll<HTMLInputElement>('.att-native-case-preview-list input[type="checkbox"]')];
    expect(checkboxes).toHaveLength(2);
    act(() => checkboxes[1].click());
    await act(async () => button('Validate selected Cases for RUN').click());
    expect(previewRun).toHaveBeenCalledWith(expect.objectContaining({
      schemaVersion: 'att-execution/v2',
      target: { type: 'nativeCase', definitionId: 'payments.refund', selection: { resolvedCaseIds: ['R-001'] } },
    }), expect.any(AbortSignal));
    expect(host.textContent).toContain('Server validation passed');

    await act(async () => button('Run selected Cases').click());
    expect(host.querySelector('[role="alertdialog"]')).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child')!.click());
    await settle();
    expect(submitRun).toHaveBeenCalledWith({ packageId: 'payments', draftId: 'R-native' });
    expect(onJobAccepted).toHaveBeenCalledWith('J-native');
  });

  it('keeps RUN disabled until the Server advertises v2 support', async () => {
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1' }], total: 1 })),
      getManagedPackageAuthoringSource: vi.fn(async (_packageId: string, query: { kind: string }) => ({
        available: true, present: true, logicalPath: query.kind === 'manifest' ? 'package.yaml' : 'testcase/a/testcase.yaml', format: 'yaml',
        text: query.kind === 'manifest' ? 'resources:\n  testcaseRoot: testcase\n'
          : 'schemaVersion: att-testcase/v1\nid: a\nflow: checkout.v1\ninputs: {}\n', sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1',
      })), previewNativeTestCase: vi.fn(async () => ({ kind: 'NativeTestCasePreview', valid: true, sourceType: 'direct',
        sourceReference: 'testcase/a/testcase.yaml', definitionRevision: 'd', packageSourceRevision: 'base-1', registryRevision: 'registry-1',
        resolvedCount: 1, selectedCount: 1, previewCount: 1, truncated: false, diagnostics: [], cases: [{
          qualifiedCaseId: 'a.default', caseId: 'default', definitionId: 'a', flowId: 'checkout.v1', tags: [], inputs: {},
          provenance: { sourceType: 'inline', sourceReference: 'testcase/a/testcase.yaml' },
        }] })), supportsNativeCaseRun: vi.fn(() => false), previewRun: vi.fn(), submitRun: vi.fn(),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/a/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle(); await act(async () => button('Preview / Resolve').click());
    const run = button('Validate selected Cases for RUN');
    expect(run.disabled).toBe(true);
    expect(host.textContent).toContain('att-execution/v2');
  });

  it('shows read-only Excel provenance and maps one selected row to Native RUN', async () => {
    const definition = `schemaVersion: att-testcase/v1\nid: excel-cases\nflow: checkout.v1\ncaseIdExpression: "\${DATA.id}"\ndataSource:\n  type: excel\n  path: testcase/cases.xlsx\n  sheet: Cases\n  headerRows: 1\n  caseId: ID\n  dataColumns: id=ID, amount=Amount\n`;
    const previewNative = vi.fn(async () => ({ kind: 'NativeTestCasePreview', valid: true, sourceType: 'excel',
      sourceReference: 'testcase/excel-cases/testcase.yaml', definitionRevision: 'def-e', packageSourceRevision: 'package-e',
      registryRevision: 'registry-e', workbookRevision: 'workbook-e', resolvedCount: 2, selectedCount: 2, previewCount: 2,
      truncated: false, diagnostics: [], cases: [
        { qualifiedCaseId: 'excel-cases.case-1', caseId: 'case-1', definitionId: 'excel-cases', flowId: 'checkout.v1',
          tags: [], inputs: { amount: 10 }, provenance: { sourceType: 'excel', sourceReference: 'testcase/cases.xlsx', sheet: 'Cases', row: 2, recordId: 'case-1', recordIndex: 0 } },
        { qualifiedCaseId: 'excel-cases.case-2', caseId: 'case-2', definitionId: 'excel-cases', flowId: 'checkout.v1',
          tags: [], inputs: { amount: 20 }, provenance: { sourceType: 'excel', sourceReference: 'testcase/cases.xlsx', sheet: 'Cases', row: 3, recordId: 'case-2', recordIndex: 1 } },
      ] }));
    const previewRun = vi.fn(async () => ({ draftId: 'R-excel', expiresAt: '2099-01-01T00:00:00Z' }));
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1' }], total: 1 })),
      getManagedPackageAuthoringSource: vi.fn(async (_packageId: string, query: { kind: string }) => ({
        available: true, present: true, logicalPath: query.kind === 'manifest' ? 'package.yaml' : 'testcase/excel-cases/testcase.yaml',
        format: 'yaml', text: query.kind === 'manifest' ? 'resources:\n  testcaseRoot: testcase\n' : definition,
        sourceEtag: 'e', revision: 'r', baseSourceRevision: 'package-e', registryRevision: 'registry-e',
      })),
      previewNativeTestCase: previewNative, supportsNativeCaseRun: vi.fn(() => true), previewRun,
      submitRun: vi.fn(),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/excel-cases/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle(); await act(async () => button('Preview / Resolve').click());
    expect(host.textContent).toContain('Workbook revision:');
    expect(host.textContent).toContain('Cases row 2');
    const checkboxes = [...host.querySelectorAll<HTMLInputElement>('.att-native-case-preview-list input[type="checkbox"]')];
    expect(checkboxes).toHaveLength(2);
    act(() => checkboxes[0].click());
    await act(async () => button('Validate selected Cases for RUN').click());
    expect(previewRun).toHaveBeenCalledWith(expect.objectContaining({
      target: { type: 'nativeCase', definitionId: 'excel-cases', selection: { resolvedCaseIds: ['case-2'] } },
    }), expect.any(AbortSignal));
  });

  it('retains invalid JSON drafts and refuses to stage stale Definition YAML', async () => {
    const definition = `schemaVersion: att-testcase/v1
id: payments.refund
name: Refund
flow: checkout.v1
inputs:
  amount: 10
`;
    const getSource = vi.fn(async (_packageId: string, query: { kind: string }) => query.kind === 'manifest'
      ? { available: true, present: true, logicalPath: 'package.yaml', format: 'yaml', text: 'resources:\n  testcaseRoot: testcase\n', sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }
      : { available: true, present: true, logicalPath: 'testcase/refund/testcase.yaml', format: 'yaml', text: definition, sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' });
    const previewNative = vi.fn();
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1', name: 'Checkout' }], total: 1 })),
      getManagedPackageAuthoringSource: getSource, previewNativeTestCase: previewNative,
    } as unknown as ExplorerApi;

    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/refund/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle();
    const loadCallsBeforeEdit = getSource.mock.calls.length;
    const inputs = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Typed inputs"]');
    expect(inputs).not.toBeNull();
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(inputs!, '{"amount":');
      inputs!.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await settle();
    expect(inputs!.value).toBe('{"amount":');
    expect(host.textContent).toContain('Typed inputs must be a valid JSON object.');
    expect(host.textContent).toContain('JSON draft is not synchronized');
    expect(button('Preview / Resolve').disabled).toBe(true);
    expect(button('Add to Change Set').disabled).toBe(true);
    await act(async () => button('Add to Change Set').click());
    expect(getSource.mock.calls.length).toBe(loadCallsBeforeEdit);
    expect(host.querySelector('[aria-label="Package Change Set workflow"]')).toBeNull();
    expect(previewNative).not.toHaveBeenCalled();
  });

  it('confirms before discarding unsynchronized JSON drafts on Data Source switch', async () => {
    const definition = `schemaVersion: att-testcase/v1
id: payments.refund
name: Refund
flow: checkout.v1
caseIdExpression: "\${DATA.id}"
dataSource:
  type: inline
  records:
    - recordId: first
      data: {id: R-001}
`;
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1', name: 'Checkout' }], total: 1 })),
      getManagedPackageAuthoringSource: vi.fn(async (_packageId: string, query: { kind: string }) => query.kind === 'manifest'
        ? { available: true, present: true, logicalPath: 'package.yaml', format: 'yaml', text: 'resources:\n  testcaseRoot: testcase\n', sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }
        : { available: true, present: true, logicalPath: 'testcase/refund/testcase.yaml', format: 'yaml', text: definition, sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }),
    } as unknown as ExplorerApi;

    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/refund/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle();

    const records = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Inline multi-record data"]');
    expect(records).not.toBeNull();
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(records!, '[{"recordId":');
      records!.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await settle();
    expect(records!.value).toBe('[{"recordId":');
    expect(host.textContent).toContain('JSON draft is not synchronized');

    const dataSource = host.querySelector<HTMLSelectElement>('select[aria-label="Native Case Data Source"]');
    expect(dataSource).not.toBeNull();
    expect(dataSource!.value).toBe('inline');
    await act(async () => {
      dataSource!.value = 'excel';
      dataSource!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await settle();
    expect(host.querySelector('[role="alertdialog"]')).not.toBeNull();
    expect(host.textContent).toContain('Discard unfinished JSON draft?');

    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:first-child')!.click());
    await settle();
    expect(host.querySelector('[role="alertdialog"]')).toBeNull();
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="Native Case Data Source"]')!.value).toBe('inline');
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Inline multi-record data"]')!.value).toBe('[{"recordId":');
    expect(host.textContent).toContain('JSON draft is not synchronized');

    await act(async () => {
      dataSource!.value = 'excel';
      dataSource!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await settle();
    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child')!.click());
    await settle();
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="Native Case Data Source"]')!.value).toBe('excel');
    expect(host.querySelector('textarea[aria-label="Inline multi-record data"]')).toBeNull();
    expect(host.textContent).not.toContain('JSON draft is not synchronized');
  });

  it('surfaces structured Excel row and column diagnostics without previewing a Run draft', async () => {
    const definition = `schemaVersion: att-testcase/v1\nid: excel-errors\nflow: checkout.v1\ndataSource:\n  type: excel\n  path: testcase/cases.xlsx\n  sheet: Cases\n  caseId: ID\n  dataColumns: amount=Amount\n`;
    const previewNative = vi.fn(async () => ({ kind: 'NativeTestCasePreview', valid: false, sourceType: 'invalid',
      sourceReference: 'testcase/excel-errors/testcase.yaml', packageSourceRevision: 'package-e', registryRevision: 'registry-e',
      resolvedCount: 0, selectedCount: 0, previewCount: 0, truncated: false, cases: [],
      diagnostics: [{ code: 'ATT-TESTCASE-INVALID', summary: 'Excel cell is invalid', field: 'dataSource.dataColumns.amount',
        sheet: 'Cases', row: 9, column: 3, suggestion: 'Correct the indicated cell.' }] }));
    const previewRun = vi.fn();
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1' }], total: 1 })),
      getManagedPackageAuthoringSource: vi.fn(async (_packageId: string, query: { kind: string }) => ({
        available: true, present: true, logicalPath: query.kind === 'manifest' ? 'package.yaml' : 'testcase/excel-errors/testcase.yaml',
        format: 'yaml', text: query.kind === 'manifest' ? 'resources:\n  testcaseRoot: testcase\n' : definition,
        sourceEtag: 'e', revision: 'r', baseSourceRevision: 'package-e', registryRevision: 'registry-e',
      })), previewNativeTestCase: previewNative, previewRun,
    } as unknown as ExplorerApi;
    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/excel-errors/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle(); await act(async () => button('Preview / Resolve').click());
    expect(host.textContent).toContain('row 9'); expect(host.textContent).toContain('column 3');
    expect(host.textContent).toContain('dataSource.dataColumns.amount');
    expect(previewRun).not.toHaveBeenCalled();
  });
});
