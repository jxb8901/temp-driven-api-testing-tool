// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import type { AppRoute, ValidationRouteState } from '../../router';
import type { AsyncState, PackageSummary, ServerVersion } from '../../types';
import { ValidationWorkspacePage } from './ValidationWorkspacePage';
import { safeValidationReportCopy } from './ValidationReport';
import type { ValidationReportData, ValidationRequest } from './types';

const report: ValidationReportData = {
  schemaVersion: 'att-validation/v1', scope: 'config', backend: 'SERVER', sections: [], diagnostics: [],
  counts: { pass: 1, fail: 0, warn: 0, skip: 0, errors: 0, warnings: 0 },
  overallStatus: 'PASS', complete: true, exitCode: 0, requestId: 'request-355',
  context: { packageId: 'payments', environment: 'SIT', revision: 4, serverPath: '/Users/jeffrey/hidden.yaml' },
};
const serverVersion: ServerVersion = { apiVersion: '1', validationApiVersion: 'att-validation/v1', inlineLoadEnabled: false, loadSchedulingEnabled: false };
const baseContext = {
  selectedPackage: '', selectedEnvironment: '', themePreference: 'light', uiLocale: 'en', uiLocalePreference: 'en',
  currentUser: null, accessCapabilities: { operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false },
  preferenceStatus: 'ready', canRevealConfigurationFields: false, authContextKey: 'session-a', inspectionSessionEpoch: 1,
  onPackageChange: vi.fn(), onEnvironmentChange: vi.fn(), onThemePreferenceChange: vi.fn(), onUiLocaleChange: vi.fn(), onJobAccepted: vi.fn(),
} as unknown as AppShellContextValue;

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  vi.restoreAllMocks();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });
async function settle() { await act(async () => { for (let index = 0; index < 5; index++) await Promise.resolve(); }); }

function renderWorkspace({ api, route = { kind: 'validation', scope: 'config' }, context = baseContext,
  version = serverVersion, onRouteChange = vi.fn(), onOpenResource = vi.fn(), packages = { status: 'ready', data: [] } }: {
  api: { validateResource(input: ValidationRequest, signal?: AbortSignal): Promise<ValidationReportData> };
  route?: ValidationRouteState; context?: AppShellContextValue; version?: ServerVersion;
  onRouteChange?: (route: AppRoute) => void;
  onOpenResource?: (packageId: string, resource: { type: string; resourceId: string; logicalId?: string }, environment?: string) => void;
  packages?: AsyncState<PackageSummary[]>;
}) {
  return root.render(<AppShellContext.Provider value={context}>
    <ValidationWorkspacePage api={api as never} packages={packages} serverVersion={{ status: 'ready', data: version }}
      route={route} onRouteChange={onRouteChange} onOpenResource={onOpenResource} />
  </AppShellContext.Provider>);
}

describe('ValidationWorkspacePage', () => {
  it('does not validate on page load or scope changes and posts only after the explicit read-only action', async () => {
    const validateResource = vi.fn(async () => report);
    const onRouteChange = vi.fn();
    await act(async () => renderWorkspace({ api: { validateResource }, onRouteChange }));
    await settle();
    expect(validateResource).not.toHaveBeenCalled();
    const scope = host.querySelector<HTMLSelectElement>('[aria-label="Validation scope"]')!;
    await act(async () => { scope.value = 'package'; scope.dispatchEvent(new Event('change', { bubbles: true })); });
    expect(onRouteChange).toHaveBeenCalledWith(expect.objectContaining({ kind: 'validation', scope: 'package' }));
    expect(validateResource).not.toHaveBeenCalled();
    // Return to Config; with no Package this means global Server Runtime Config.
    await act(async () => { scope.value = 'config'; scope.dispatchEvent(new Event('change', { bubbles: true })); });
    await act(async () => { host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click(); await Promise.resolve(); });
    await settle();
    expect(validateResource).toHaveBeenCalledTimes(1);
    expect(validateResource).toHaveBeenCalledWith({ targetKind: 'config' }, expect.any(AbortSignal));
    expect(host.textContent).toContain('Validation report');
    expect(host.textContent).toContain('PASS');
    expect(host.textContent).not.toContain('/Users/jeffrey/hidden.yaml');
  });

  it('keeps global Config validation available when the Package catalog is unavailable', async () => {
    const validateResource = vi.fn(async () => report);
    await act(async () => renderWorkspace({ api: { validateResource }, packages: { status: 'error', error: new Error('catalog unavailable') } }));
    await settle();
    const submit = host.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.disabled).toBe(false);
    await act(async () => { submit.click(); await Promise.resolve(); });
    await settle();
    expect(validateResource).toHaveBeenCalledWith({ targetKind: 'config' }, expect.any(AbortSignal));
    expect(host.textContent).toContain('Validation report');
  });

  it('gates unsupported Server versions and aborts an in-flight result when the auth context changes', async () => {
    const validateResource = vi.fn(async () => report);
    await act(async () => renderWorkspace({ api: { validateResource }, version: { ...serverVersion, validationApiVersion: undefined } }));
    await settle();
    expect(host.textContent).toContain('Update the Server');
    expect(host.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
    expect(validateResource).not.toHaveBeenCalled();

    let finish!: (value: ValidationReportData) => void;
    const pending = new Promise<ValidationReportData>(resolve => { finish = resolve; });
    const validation = vi.fn((_input: ValidationRequest, _signal?: AbortSignal) => pending);
    await act(async () => renderWorkspace({ api: { validateResource: validation } }));
    await act(async () => { host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click(); await Promise.resolve(); });
    const signal = validation.mock.calls[0]?.[1];
    const changedContext = { ...baseContext, authContextKey: 'session-b' } as AppShellContextValue;
    await act(async () => renderWorkspace({ api: { validateResource: validation }, context: changedContext }));
    expect(signal?.aborted).toBe(true);
    await act(async () => { finish(report); await Promise.resolve(); });
    expect(host.textContent).not.toContain('Validation report');
  });

  it('copies only contract fields and redacts credentials and absolute paths', () => {
    const safe = safeValidationReportCopy({ ...report,
      diagnostics: [{ code: 'ATT-SECRET', severity: 'ERROR', message: 'password=violet-123; from /Users/jeffrey/private/config.yaml' }],
    });
    expect(safe).toContain('[REDACTED]');
    expect(safe).toContain('[REDACTED_PATH]');
    expect(safe).not.toContain('violet-123');
    expect(safe).not.toContain('serverPath');
  });

  it('resolves Package diagnostics through the authorized catalog before offering resource navigation', async () => {
    const packageReport: ValidationReportData = { ...report, scope: 'PACKAGE', diagnostics: [{
      code: 'ATT-TEMPLATE-INVALID', severity: 'ERROR', message: 'Template could not be validated',
      resourceType: 'template', resourceId: 'template-42', source: 'templates/checkout.yaml',
    }] };
    const validateResource = vi.fn(async () => packageReport);
    const listResources = vi.fn(async () => ({
      items: [{ type: 'template' as const, resourceId: 'template-42', logicalId: 'checkout' }], total: 1,
    }));
    const onOpenResource = vi.fn();
    const api = {
      validateResource,
      listResources,
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getPackageManifest: vi.fn(async () => ({ environment: 'SIT' })),
    };
    await act(async () => renderWorkspace({ api, route: { kind: 'validation', scope: 'package', packageId: 'payments' },
      packages: { status: 'ready', data: [{ packageId: 'payments', name: 'Payments' } as PackageSummary] }, onOpenResource }));
    await settle();
    await act(async () => { host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click(); await Promise.resolve(); });
    await settle();

    expect(listResources).toHaveBeenCalledWith('payments', expect.objectContaining({ type: 'template', query: 'template-42', limit: 100 }), expect.any(AbortSignal));
    const openButton = [...host.querySelectorAll<HTMLButtonElement>('.att-validation-diagnostic-list button')]
      .find(button => button.textContent?.includes('Open authorized resource'));
    expect(openButton).toBeDefined();
    await act(async () => openButton!.click());
    expect(onOpenResource).toHaveBeenCalledWith('payments', { type: 'template', resourceId: 'template-42', logicalId: 'checkout' }, 'SIT');
  });

  it('keeps path-only and catalog-denied Package diagnostics as plain text and redacts displayed and copied secrets', async () => {
    const secret = 'eyJabc123';
    const packageReport: ValidationReportData = { ...report, scope: 'PACKAGE', diagnostics: [
      { code: 'ATT-FLOW-DENIED', severity: 'ERROR', message: `Authorization: Bearer ${secret}; password="two word hidden secret"`,
        resourceType: 'flow', resourceId: 'flow-denied', source: 'flows/denied.yaml' },
      { code: 'ATT-SOURCE-ONLY', severity: 'WARN', message: 'Source-only diagnostic', source: 'templates/only-path.yaml' },
    ] };
    const validateResource = vi.fn(async () => packageReport);
    const listResources = vi.fn(async () => { throw { status: 403 }; });
    const copiedValues: string[] = [];
    const writeText = vi.fn(async (value: string) => { copiedValues.push(value); });
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const api = {
      validateResource, listResources,
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [] })),
      getPackageManifest: vi.fn(async () => ({ environment: 'SIT' })),
    };
    await act(async () => renderWorkspace({ api, route: { kind: 'validation', scope: 'package', packageId: 'payments' },
      packages: { status: 'ready', data: [{ packageId: 'payments', name: 'Payments' } as PackageSummary] } }));
    await settle();
    await act(async () => { host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click(); await Promise.resolve(); });
    await settle();

    expect(listResources).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('flows/denied.yaml');
    expect(host.textContent).toContain('templates/only-path.yaml');
    expect(host.textContent).toContain('Authorization: [REDACTED]');
    expect(host.textContent).toContain('password=[REDACTED]');
    expect(host.textContent).not.toContain(secret);
    expect(host.textContent).not.toContain('two word hidden secret');
    expect(host.querySelectorAll('.att-validation-diagnostic-list button')).toHaveLength(0);
    expect(host.querySelectorAll('.att-validation-diagnostic-list a')).toHaveLength(0);

    await act(async () => {
      [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Copy safe report'))!.click();
      await Promise.resolve();
    });
    expect(writeText).toHaveBeenCalledTimes(1);
    const copied = copiedValues[0] || '';
    expect(copied).toContain('Authorization: [REDACTED]');
    expect(copied).not.toContain(secret);
    expect(copied).not.toContain('two word hidden secret');
  });
});
