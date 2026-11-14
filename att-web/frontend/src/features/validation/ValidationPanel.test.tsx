// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { ValidationPanel } from './ValidationPanel';
import type { ValidationRecord, ValidationReportData, ValidationRequest } from './types';

const report: ValidationReportData = {
  schemaVersion: 'att-validation/v1', scope: 'config', backend: 'SERVER', sections: [], diagnostics: [],
  counts: { pass: 1, fail: 0, warn: 0, skip: 0, errors: 0, warnings: 0 },
  overallStatus: 'PASS', complete: true, exitCode: 0,
};
const queued: ValidationRecord = {
  validationId: 'V0123456789ABCDEF0123456789ABCDEF', scope: 'config', status: 'QUEUED',
  requestId: 'request-364', submittedAt: '2026-11-14T10:00:00Z', applicability: 'UNVERIFIED',
};
const completed: ValidationRecord = {
  ...queued, status: 'COMPLETED', finishedAt: '2026-11-14T10:00:01Z', report, overallStatus: 'PASS',
};
const context = {
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

describe('ValidationPanel', () => {
  it('refreshes a durable record and does not cancel it when the view unmounts', async () => {
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async () => completed),
      cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
    };
    const onOpenRecord = vi.fn();
    await act(async () => root.render(<AppShellContext.Provider value={context}>
      <ValidationPanel request={{ targetKind: 'config' }} api={api as never} onOpenRecord={onOpenRecord} />
    </AppShellContext.Provider>));
    await settle();
    expect(api.submitValidation).toHaveBeenCalledWith({ targetKind: 'config' });
    expect(host.textContent).toContain('queued');
    expect(api.cancelValidation).not.toHaveBeenCalled();

    const refresh = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Refresh')!;
    await act(async () => { refresh.click(); await Promise.resolve(); });
    await settle();
    expect(api.getValidationRecord).toHaveBeenCalledWith(queued.validationId, undefined);
    expect(host.textContent).toContain('PASS');
    expect(host.textContent).toContain('UNVERIFIED');
    const open = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Open full record')!;
    await act(async () => open.click());
    expect(onOpenRecord).toHaveBeenCalledWith(queued.validationId);

    await act(async () => root.unmount());
    expect(api.cancelValidation).not.toHaveBeenCalled();
  });

  it('sends cancellation only after the owner selects Cancel validation', async () => {
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async () => queued),
      cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
    };
    await act(async () => root.render(<AppShellContext.Provider value={context}>
      <ValidationPanel request={{ targetKind: 'config' }} api={api as never} />
    </AppShellContext.Provider>));
    await settle();
    const cancel = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Cancel validation')!;
    await act(async () => { cancel.click(); await Promise.resolve(); });
    await settle();
    expect(api.cancelValidation).toHaveBeenCalledWith(queued.validationId);
    expect(host.textContent).toContain('cancelled');
  });
});
