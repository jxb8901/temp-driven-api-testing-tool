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
      data: {id: R-001, password: secret-one}
    - recordId: second
      data: {id: R-002, password: secret-two}
`;
    const getSource = vi.fn(async (_packageId: string, query: { kind: string }, _signal?: AbortSignal) => query.kind === 'manifest'
      ? { available: true, present: true, logicalPath: 'package.yaml', format: 'yaml', text: 'schemaVersion: att-package/v2\nresources:\n  testcaseRoot: testcase\n', sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }
      : { available: true, present: true, logicalPath: 'testcase/refund/testcase.yaml', format: 'yaml', text: definition, sourceEtag: 'e', revision: 'r', baseSourceRevision: 'base-1', registryRevision: 'registry-1' });
    const previewRun = vi.fn(async () => ({ draftId: 'R-native', expiresAt: '2099-01-01T00:00:00Z' }));
    const submitRun = vi.fn(async () => ({ jobId: 'J-native' }));
    const onJobAccepted = vi.fn();
    const api = {
      listResources: vi.fn(async () => ({ items: [{ type: 'flow', resourceId: 'f1', logicalId: 'checkout.v1', name: 'Checkout' }], total: 1 })),
      getManagedPackageAuthoringSource: getSource,
      supportsNativeCaseRun: vi.fn(() => true), previewRun, submitRun,
    } as unknown as ExplorerApi;

    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/refund/testcase.yaml" onJobAccepted={onJobAccepted} />
    </AppShellContext.Provider>));
    await settle();
    act(() => button('Preview / Resolve').click());
    expect(host.textContent).toContain('2 resolved Case(s)');
    expect(host.textContent).toContain('payments.refund.R-001');
    const preview = host.querySelector<HTMLElement>('[aria-label="Resolved Case design-time preview"]');
    expect(preview?.textContent).toContain('sensitive keys are redacted');
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
      })), supportsNativeCaseRun: vi.fn(() => false), previewRun: vi.fn(), submitRun: vi.fn(),
    } as unknown as ExplorerApi;
    await act(async () => root.render(<AppShellContext.Provider value={context()}>
      <NativeTestCaseAuthoringWorkspace api={api} packageId="payments" sourcePath="testcase/a/testcase.yaml" />
    </AppShellContext.Provider>));
    await settle(); act(() => button('Preview / Resolve').click());
    const run = button('Validate selected Cases for RUN');
    expect(run.disabled).toBe(true);
    expect(host.textContent).toContain('att-execution/v2');
  });
});
