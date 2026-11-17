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
  vi.spyOn(api, 'listSutMetricAcquisitions').mockResolvedValue({ items: [] });
  vi.spyOn(api, 'listSutMetricGroups').mockResolvedValue({ items: [] });
  vi.spyOn(api, 'listSutMetricProfiles').mockResolvedValue({ items: [] });
  vi.spyOn(api, 'testSutMetricSource').mockResolvedValue({});
  vi.spyOn(api, 'previewSutMetricBinding').mockResolvedValue({ valid: true, queries: [] });
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove(); vi.restoreAllMocks();
});

describe('SettingsPage global SUT Metrics Data Sources', () => {
  it('loads only global Data Sources and shows safe connection, permission, and limit metadata', async () => {
    const configuration = { origin: 'server.runtimeConfigPath', state: 'LAST_KNOWN_GOOD', configured: true,
      revision: 'a'.repeat(64), message: 'The latest Runtime Config revision was rejected at config.sutMetrics.' };
    vi.spyOn(api, 'listSutMetricSources').mockResolvedValue({ configuration, items: [{
      id: 'prom-main', name: 'Main Prometheus', revision: 'b'.repeat(64), provider: 'PROMETHEUS',
      url: 'http://prometheus.sit.example:9090', apiPath: '/api/v1',
      auth: { mode: 'bearer', secretRef: 'prometheus-secret-reference', token: 'never-render-this-secret' },
      readers: ['alice'], readRoles: ['SUT_VIEWER'], allowedTenants: ['42'], defaultTenant: '42',
      limits: { maxRangeSeconds: 3600, maxResponseBytes: 1048576, maxQueriesPerJob: 8, maxSeriesPerJob: 32 },
    }] });
    const testSource = vi.spyOn(api, 'testSutMetricSource').mockResolvedValue({});

    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    expect(host.querySelector('[aria-label="SUT Metrics configuration is read-only"]')).not.toBeNull();
    expect(host.textContent).toContain('Update the Runtime Config (config.yaml) through the approved deployment process.');
    expect(host.textContent).toContain('The active catalog is the last known good revision.');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('The latest Runtime Config revision was rejected');
    const buttonTexts = Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim());
    for (const packageEditor of ['Save Metric Source', 'Save Metric Group', 'Save Metrics Profile',
      'Save Business Metrics Profile', 'Save Flat Profile', 'New Metric Source draft', 'New Metric Group draft',
      'New Flat Profile draft', 'Preview fetch and outputs', 'Preview group rules', 'Preview sources and fetch count', 'Validate binding']) {
      expect(buttonTexts).not.toContain(packageEditor);
    }
    expect(buttonTexts).not.toContain('Save Data Source');
    expect(buttonTexts).not.toContain('Delete');
    expect(buttonTexts).toContain('View JSON');
    expect(buttonTexts).toContain('Test');
    expect(api.listSutMetricSources).toHaveBeenCalledTimes(1);
    expect(api.listSutMetricAcquisitions).not.toHaveBeenCalled();
    expect(api.listSutMetricGroups).not.toHaveBeenCalled();
    expect(api.listSutMetricProfiles).not.toHaveBeenCalled();
    expect(api.previewSutMetricBinding).not.toHaveBeenCalled();

    const sourceRow = Array.from(host.querySelectorAll('li')).find(item => item.textContent?.includes('prom-main'))!;
    const viewButton = Array.from(sourceRow.querySelectorAll('button')).find(button => button.textContent === 'View JSON')!;
    await act(async () => { viewButton.click(); });
    const dataSourceEditor = Array.from(host.querySelectorAll<HTMLTextAreaElement>('textarea')).find(editor =>
      editor.parentElement?.textContent?.includes('Data Source JSON'))!;
    expect(dataSourceEditor.readOnly).toBe(true);
    expect(JSON.parse(dataSourceEditor.value)).toMatchObject({
      provider: 'PROMETHEUS', url: 'http://prometheus.sit.example:9090', apiPath: '/api/v1',
      readers: ['alice'], readRoles: ['SUT_VIEWER'], allowedTenants: ['42'], defaultTenant: '42',
      limits: { maxRangeSeconds: 3600, maxResponseBytes: 1048576, maxQueriesPerJob: 8, maxSeriesPerJob: 32 },
    });
    const editorValues = Array.from(host.querySelectorAll<HTMLTextAreaElement>('textarea')).map(editor => editor.value);
    expect(editorValues.some(value => /att-sut-metrics-(?:metric-source|group|profile)\//.test(value))).toBe(false);
    expect(editorValues.some(value => value.includes('att-load-observability/v1'))).toBe(false);
    expect(dataSourceEditor.value).not.toContain('never-render-this-secret');

    const testButton = Array.from(sourceRow.querySelectorAll('button')).find(button => button.textContent === 'Test')!;
    await act(async () => { testButton.click(); await Promise.resolve(); });
    expect(testSource).toHaveBeenCalledWith('prom-main');
    expect(api.listSutMetricAcquisitions).not.toHaveBeenCalled();
    expect(api.listSutMetricGroups).not.toHaveBeenCalled();
    expect(api.listSutMetricProfiles).not.toHaveBeenCalled();
  });

  it('keeps legacy catalog editing available when responses omit configuration metadata', async () => {
    vi.spyOn(api, 'listSutMetricSources').mockResolvedValue({ items: [{ id: 'legacy-source', name: 'Legacy source' }] });
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    expect(host.querySelector('[aria-label="SUT Metrics configuration is read-only"]')).toBeNull();
    const buttonTexts = Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim());
    expect(buttonTexts).toContain('Edit JSON');
    expect(buttonTexts).toContain('Delete');
    expect(buttonTexts).toContain('Save Data Source');
    expect(Array.from(host.querySelectorAll<HTMLTextAreaElement>('textarea')).some(editor =>
      editor.value.includes('att-sut-metrics-source/v1') && !editor.readOnly)).toBe(true);
    expect(api.listSutMetricAcquisitions).not.toHaveBeenCalled();
    expect(api.listSutMetricGroups).not.toHaveBeenCalled();
    expect(api.listSutMetricProfiles).not.toHaveBeenCalled();
  });

  it('keeps catalog editing unavailable until delayed metadata confirms a legacy writable catalog', async () => {
    let resolveSources!: (value: { items: Array<Record<string, unknown>> }) => void;
    vi.spyOn(api, 'listSutMetricSources').mockReturnValue(new Promise(resolve => { resolveSources = resolve; }) as never);
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Editing stays unavailable until the server confirms whether this catalog is writable.');
    let buttonTexts = Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim());
    expect(buttonTexts).not.toContain('Save Data Source');
    expect(buttonTexts).not.toContain('Edit JSON');
    expect(Array.from(host.querySelectorAll<HTMLTextAreaElement>('.att-sut-metrics-admin textarea'))
      .filter(editor => editor.value.includes('att-sut-metrics-')).every(editor => editor.readOnly)).toBe(true);

    await act(async () => {
      resolveSources({ items: [{ id: 'legacy-source', name: 'Legacy source' }] });
      await Promise.resolve();
    });

    buttonTexts = Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim());
    expect(buttonTexts).toContain('Save Data Source');
    expect(buttonTexts).toContain('Edit JSON');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(api.listSutMetricAcquisitions).not.toHaveBeenCalled();
    expect(api.listSutMetricGroups).not.toHaveBeenCalled();
    expect(api.listSutMetricProfiles).not.toHaveBeenCalled();
  });

  it('keeps catalog editing unavailable when catalog metadata cannot be loaded', async () => {
    vi.spyOn(api, 'listSutMetricSources').mockRejectedValue(new Error('SUT Metrics catalog is unavailable.'));
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await new Promise(resolve => window.setTimeout(resolve, 0));
    });

    expect(host.textContent).toContain('The SUT Metrics catalog status could not be confirmed. Editing is disabled');
    const buttonTexts = Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim());
    expect(buttonTexts).not.toContain('Save Data Source');
    expect(buttonTexts).not.toContain('Delete');
    expect(buttonTexts).not.toContain('Edit JSON');
    expect(Array.from(host.querySelectorAll<HTMLTextAreaElement>('.att-sut-metrics-admin textarea'))
      .filter(editor => editor.value.includes('att-sut-metrics-')).every(editor => editor.readOnly)).toBe(true);
    expect(api.listSutMetricAcquisitions).not.toHaveBeenCalled();
    expect(api.listSutMetricGroups).not.toHaveBeenCalled();
    expect(api.listSutMetricProfiles).not.toHaveBeenCalled();
  });
});
