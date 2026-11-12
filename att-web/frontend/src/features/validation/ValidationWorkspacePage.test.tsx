// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import type { AppRoute, ValidationRouteState } from '../../router';
import type { ServerVersion } from '../../types';
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
  version = serverVersion, onRouteChange = vi.fn() }: {
  api: { validateResource(input: ValidationRequest, signal?: AbortSignal): Promise<ValidationReportData> };
  route?: ValidationRouteState; context?: AppShellContextValue; version?: ServerVersion;
  onRouteChange?: (route: AppRoute) => void;
}) {
  return root.render(<AppShellContext.Provider value={context}>
    <ValidationWorkspacePage api={api as never} packages={{ status: 'ready', data: [] }} serverVersion={{ status: 'ready', data: version }}
      route={route} onRouteChange={onRouteChange} />
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
      diagnostics: [{ code: 'ATT-SECRET', severity: 'ERROR', message: 'password=violet-123 from /Users/jeffrey/private/config.yaml' }],
    });
    expect(safe).toContain('[REDACTED]');
    expect(safe).toContain('[REDACTED_PATH]');
    expect(safe).not.toContain('violet-123');
    expect(safe).not.toContain('serverPath');
  });
});
