// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from './AppShell';
import { api } from './api';
import { SettingsPage } from './SettingsPage';

let host: HTMLDivElement;
let root: Root;
const context = {
  selectedPackage: 'payments', selectedEnvironment: 'SIT', themePreference: 'system', uiLocale: 'en',
  uiLocalePreference: 'browser', currentUser: null,
  accessCapabilities: { operate: false, accountManage: false, systemSettingsWrite: true, configurationAdmin: false },
  preferenceStatus: 'saved', canRevealConfigurationFields: false,
  onPackageChange: vi.fn(), onEnvironmentChange: vi.fn(), onThemePreferenceChange: vi.fn(),
  onUiLocaleChange: vi.fn(), onJobAccepted: vi.fn(),
} as unknown as AppShellContextValue;

beforeEach(() => {
  vi.restoreAllMocks();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.spyOn(api, 'getServerMetrics').mockResolvedValue({ jobStorage: null } as never);
  vi.spyOn(api, 'listSutMetricSources').mockResolvedValue({ items: [] });
  vi.spyOn(api, 'listSutMetricProfiles').mockResolvedValue({ items: [] });
  vi.spyOn(api, 'previewSutMetricBinding').mockResolvedValue({ valid: true, queries: [] });
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove(); vi.restoreAllMocks();
});

describe('SettingsPage SUT Metrics profiles', () => {
  it('adds and saves a business mapping in its own profile while preserving the default PromQL profile', async () => {
    const save = vi.spyOn(api, 'saveSutMetricProfile').mockResolvedValue({});
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    const addMapping = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Add mapping to Business Profile draft');
    expect(addMapping).toBeDefined();
    await act(async () => { addMapping!.click(); });

    const editorFor = (labelText: string) => {
      const label = Array.from(host.querySelectorAll('label')).find(item => item.textContent?.includes(labelText));
      return label?.querySelector('textarea')!;
    };
    const prometheusProfile = JSON.parse(editorFor('Prometheus Metrics Profile JSON').value) as Record<string, unknown>;
    const businessProfile = JSON.parse(editorFor('Business Metrics Profile JSON').value) as Record<string, unknown>;
    expect(prometheusProfile.id).toBe('profile-os');
    expect(prometheusProfile.metrics).toEqual(expect.arrayContaining([expect.objectContaining({ id: 'cpu', query: expect.any(String) })]));
    expect(businessProfile).toMatchObject({ id: 'business-metrics', kind: 'base' });
    expect(businessProfile.metrics).toEqual(expect.arrayContaining([expect.objectContaining({
      id: 'transaction-query', call: expect.stringContaining('select workload_id, mix_id, success, failure'),
    })]));
    const businessMetric = (businessProfile.metrics as Array<Record<string, unknown>>)[0]!;
    expect(businessMetric.mapping).toMatchObject({ workloadColumn: 'workload_id', mixColumn: 'mix_id' });
    expect(businessMetric.outputs).toEqual(expect.arrayContaining([
      expect.objectContaining({ column: 'success' }), expect.objectContaining({ column: 'failure' }),
    ]));
    expect(host.textContent).toContain('Column names are matched exactly and are case-sensitive');

    const saveBusiness = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Business Metrics Profile');
    await act(async () => { saveBusiness!.click(); await Promise.resolve(); });
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ id: 'business-metrics', kind: 'base' }));
    const saved = save.mock.calls[0]?.[0];
    expect(saved?.metrics).toEqual(expect.arrayContaining([expect.objectContaining({ call: expect.any(String), outputs: expect.any(Array) })]));
    expect((saved?.metrics as Array<Record<string, unknown>>).some(metric => typeof metric.query === 'string')).toBe(false);
    expect(host.textContent).toContain('Business Metrics Profile saved and dependency graph validated.');

    const observability = {
      schemaVersion: 'att-load-observability/v1', collections: [{ profile: 'business-metrics', scope: 'job' }],
      collection: { mode: 'post-run', before: '5m', after: '2m', step: '30s' }, livePreview: { enabled: false },
    };
    const bindingEditor = editorFor('Observability JSON');
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(bindingEditor, JSON.stringify(observability, null, 2));
      bindingEditor.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const validate = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Validate binding');
    await act(async () => { validate!.click(); await Promise.resolve(); });
    expect(api.previewSutMetricBinding).toHaveBeenCalledWith(expect.objectContaining({
      collections: [expect.objectContaining({ profile: 'business-metrics', scope: 'job' })],
    }));
    expect(host.textContent).toContain('Observability binding is valid.');
  });
});
