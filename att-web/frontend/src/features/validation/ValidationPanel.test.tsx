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
let scrollIntoViewDescriptor: PropertyDescriptor | undefined;
beforeEach(() => {
  vi.restoreAllMocks();
  scrollIntoViewDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks();
  if (scrollIntoViewDescriptor) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', scrollIntoViewDescriptor);
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});
async function settle() { await act(async () => { for (let index = 0; index < 5; index++) await Promise.resolve(); }); }

describe('ValidationPanel', () => {
  it('focuses and scrolls a newly opened contextual result into view', async () => {
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView });
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async () => queued),
      listValidationRecords: vi.fn(async () => ({ items: [] })),
      cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
    };
    await act(async () => root.render(<AppShellContext.Provider value={context}>
      <ValidationPanel request={{ targetKind: 'config' }} api={api as never} onClose={vi.fn()} />
    </AppShellContext.Provider>));
    await settle();
    const panel = host.querySelector<HTMLElement>('.att-validation-context-panel')!;
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(document.activeElement).toBe(panel.querySelector('h2'));
    expect(panel.querySelector('[role="status"]')?.textContent).toContain('queued');
  });

  it('refreshes a durable record and does not cancel it when the view unmounts', async () => {
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async () => completed),
      listValidationRecords: vi.fn(async () => ({ items: [] })),
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
      listValidationRecords: vi.fn(async () => ({ items: [] })),
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

  it('restores the latest exact-context record on reopen without submitting another request', async () => {
    const existing = { ...completed, packageId: 'payments', target: 'template-a', environment: 'SIT', scope: 'template' };
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async () => existing),
      listValidationRecords: vi.fn(async () => ({ items: [{ ...existing, report: undefined }] })),
      cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
    };
    const request: ValidationRequest = { targetKind: 'template', packageId: 'payments', target: 'template-a', environment: 'SIT' };
    const renderPanel = async () => act(async () => root.render(<AppShellContext.Provider value={context}>
      <ValidationPanel request={request} api={api as never} submitOnOpen={false} />
    </AppShellContext.Provider>));

    await renderPanel(); await settle();
    expect(api.listValidationRecords).toHaveBeenCalledWith({
      packageId: 'payments', scope: 'template', target: 'template-a', environment: 'SIT', matchContext: true, limit: 1,
    }, expect.any(AbortSignal));
    expect(api.getValidationRecord).toHaveBeenCalledWith(existing.validationId, expect.any(AbortSignal));
    expect(api.submitValidation).not.toHaveBeenCalled();
    expect(host.textContent).toContain(existing.validationId);

    await act(async () => root.unmount());
    root = createRoot(host);
    await renderPanel(); await settle();
    expect(api.listValidationRecords).toHaveBeenCalledTimes(2);
    expect(api.submitValidation).not.toHaveBeenCalled();
  });

  it.each([queued, { ...queued, status: 'RUNNING' as const }, completed])(
    'does not re-submit or lose a %s record when the locale changes',
    async initial => {
      const api = {
        submitValidation: vi.fn(async (_request: ValidationRequest) => initial),
        getValidationRecord: vi.fn(async () => initial),
        listValidationRecords: vi.fn(async () => ({ items: [] })),
        cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
        listResources: vi.fn(async () => ({ items: [], total: 0 })),
      };
      const renderLocale = async (locale: 'en' | 'zh-Hant') => {
        const localized = { ...context, uiLocale: locale } as AppShellContextValue;
        await act(async () => root.render(<AppShellContext.Provider value={localized}>
          <ValidationPanel request={{ targetKind: 'config' }} api={api as never} />
        </AppShellContext.Provider>));
      };
      await renderLocale('en'); await settle();
      expect(api.submitValidation).toHaveBeenCalledTimes(1);
      await renderLocale('zh-Hant'); await settle();
      expect(api.submitValidation).toHaveBeenCalledTimes(1);
      expect(host.textContent).toContain(initial.validationId);
    },
  );

  it('uses separate lookups when the validation context changes', async () => {
    const sit = { ...completed, validationId: 'V11111111111111111111111111111111', packageId: 'payments', target: 'template-a', environment: 'SIT', scope: 'template' };
    const uat = { ...completed, validationId: 'V22222222222222222222222222222222', packageId: 'payments', target: 'template-b', environment: 'UAT', scope: 'template' };
    const api = {
      submitValidation: vi.fn(async (_request: ValidationRequest) => queued),
      getValidationRecord: vi.fn(async (id: string) => id === sit.validationId ? sit : uat),
      listValidationRecords: vi.fn(async (query: { environment?: string }) => ({ items: [query.environment === 'SIT' ? sit : uat] })),
      cancelValidation: vi.fn(async () => ({ ...queued, status: 'CANCELLED' as const })),
      listResources: vi.fn(async () => ({ items: [], total: 0 })),
    };
    const renderRequest = async (request: ValidationRequest) => act(async () => root.render(<AppShellContext.Provider value={context}>
      <ValidationPanel request={request} api={api as never} submitOnOpen={false} />
    </AppShellContext.Provider>));
    await renderRequest({ targetKind: 'template', packageId: 'payments', target: 'template-a', environment: 'SIT' }); await settle();
    expect(host.textContent).toContain(sit.validationId);
    await renderRequest({ targetKind: 'template', packageId: 'payments', target: 'template-b', environment: 'UAT' }); await settle();
    expect(host.textContent).toContain(uat.validationId);
    expect(api.submitValidation).not.toHaveBeenCalled();
  });
});
