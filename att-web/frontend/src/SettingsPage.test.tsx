// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from './AppShell';
import { api } from './api';
import { SettingsPage } from './SettingsPage';
import { ApiError } from './types';

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

describe('SettingsPage SUT Metrics profiles', () => {
  it('shows config-backed catalogs as read-only while keeping view, test, and safe previews available', async () => {
    const configuration = { origin: 'server.runtimeConfigPath', state: 'LAST_KNOWN_GOOD', configured: true,
      revision: 'a'.repeat(64), message: 'The latest Runtime Config revision was rejected at config.sutMetrics.' };
    vi.spyOn(api, 'listSutMetricSources').mockResolvedValue({ configuration, items: [{
      id: 'prom-main', name: 'Main Prometheus', revision: 'b'.repeat(64), provider: 'PROMETHEUS',
      url: 'http://localhost:9090', apiPath: '/api/v1', readers: ['alice'], readRoles: [], limits: {},
    }] });
    vi.spyOn(api, 'listSutMetricAcquisitions').mockResolvedValue({ configuration, items: [{
      schemaVersion: 'att-sut-metrics-metric-source/v1', id: 'host-metrics', name: 'Host metrics',
      connection: { kind: 'DATA_SOURCE', id: 'prom-main', revision: 'b'.repeat(64) },
      fetch: { mode: 'prometheus-range', query: 'up' },
      mapping: { labels: { retain: ['instance'] }, outputs: [{ id: 'up', title: 'Up', unit: 'count', kind: 'gauge', metricName: 'up' }] },
      limits: { maxRows: 10, maxSeries: 10, maxSamples: 100, maxArtifactBytes: 1024 },
    }] });
    vi.spyOn(api, 'listSutMetricGroups').mockResolvedValue({ configuration, items: [{
      schemaVersion: 'att-sut-metrics-group/v1', id: 'runtime', title: 'Runtime', order: 1,
      match: { metricIds: ['up'] }, groupBy: ['instance'], display: 'chart',
    }] });
    vi.spyOn(api, 'listSutMetricProfiles').mockResolvedValue({ configuration, items: [{
      schemaVersion: 'att-sut-metrics-profile/v2', id: 'host-profile', name: 'Host profile',
      metricSourceIds: ['host-metrics'], groupIds: ['runtime'], allowedBindings: { environments: [], targets: [], tenants: [], parameters: {} },
      budgets: { maxFetches: 1, maxObservations: 10, maxArtifactBytes: 1024 },
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
    for (const hiddenMutation of ['Save Data Source', 'Save Metric Source', 'Save Metric Group', 'Save Metrics Profile',
      'Save Business Metrics Profile', 'Save Flat Profile', 'Delete', 'New Metric Group draft', 'New Flat Profile draft']) {
      expect(buttonTexts).not.toContain(hiddenMutation);
    }
    expect(buttonTexts).not.toContain('Edit JSON');
    expect(buttonTexts).toContain('View JSON');
    expect(buttonTexts).toContain('Test');
    expect(buttonTexts).toContain('Preview fetch and outputs');
    expect(buttonTexts).toContain('Preview group rules');
    expect(buttonTexts).toContain('Preview sources and fetch count');
    expect(buttonTexts).toContain('Validate binding');
    expect(Array.from(host.querySelectorAll<HTMLInputElement>('.att-sut-profile-selection input[type="checkbox"]'))
      .every(input => input.disabled)).toBe(true);
    expect(Array.from(host.querySelectorAll<HTMLTextAreaElement>('textarea')).filter(editor =>
      editor.value.includes('att-sut-metrics-')).every(editor => editor.readOnly)).toBe(true);

    const sourceRow = Array.from(host.querySelectorAll('li')).find(item => item.textContent?.includes('prom-main'))!;
    const testButton = Array.from(sourceRow.querySelectorAll('button')).find(button => button.textContent === 'Test')!;
    await act(async () => { testButton.click(); await Promise.resolve(); });
    expect(testSource).toHaveBeenCalledWith('prom-main');
    const metricSourceRow = Array.from(host.querySelectorAll('li')).find(item => item.textContent?.includes('host-metrics'))!;
    const viewMetricSource = Array.from(metricSourceRow.querySelectorAll('button')).find(button => button.textContent === 'View JSON')!;
    await act(async () => { viewMetricSource.click(); });
    const dataSourceSelect = Array.from(host.querySelectorAll('select')).find(select =>
      select.parentElement?.textContent?.includes('Registered Prometheus Data Source'));
    expect(dataSourceSelect?.disabled).toBe(true);
    const preview = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Preview fetch and outputs')!;
    await act(async () => { preview.click(); });
    expect(host.textContent).toContain('Preview inspected the mapping only; no SQL, Tool, or Prometheus query ran.');
    expect(testSource).toHaveBeenCalledTimes(1);
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
    expect(buttonTexts).toContain('Save Metric Source');
    expect(buttonTexts).toContain('Save Metric Group');
    expect(Array.from(host.querySelectorAll<HTMLTextAreaElement>('textarea')).some(editor =>
      editor.value.includes('att-sut-metrics-source/v1') && !editor.readOnly)).toBe(true);
  });

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

  it('previews and saves Metric Sources and Groups without running source calls', async () => {
    const saveSource = vi.spyOn(api, 'saveSutMetricAcquisition').mockResolvedValue({});
    const saveGroup = vi.spyOn(api, 'saveSutMetricGroup').mockResolvedValue({});
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    const previewSource = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Preview fetch and outputs');
    await act(async () => { previewSource!.click(); });
    expect(host.textContent).toContain('no SQL, Tool, or Prometheus query ran');
    expect(host.textContent).toContain('estimatedLogicalFetches');

    const saveSourceButton = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Metric Source');
    await act(async () => { saveSourceButton!.click(); await Promise.resolve(); });
    expect(saveSource).toHaveBeenCalledWith(expect.objectContaining({
      schemaVersion: 'att-sut-metrics-metric-source/v1', id: 'host-metrics',
    }));

    const previewGroup = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Preview group rules');
    await act(async () => { previewGroup!.click(); });
    expect(host.textContent).toContain('match');
    expect(host.textContent).toContain('groupBy');
    const saveGroupButton = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Metric Group');
    await act(async () => { saveGroupButton!.click(); await Promise.resolve(); });
    expect(saveGroup).toHaveBeenCalledWith(expect.objectContaining({
      schemaVersion: 'att-sut-metrics-group/v1', id: 'host-servers',
    }));
    expect(api.testSutMetricSource).not.toHaveBeenCalled();
  });

  it('binds a Metric Source to a registered Data Source revision without inspecting it', async () => {
    vi.spyOn(api, 'listSutMetricSources').mockResolvedValue({ items: [{ id: 'prom-main', name: 'Main Prometheus', revision: 'a'.repeat(64) }] });
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });
    const selector = Array.from(host.querySelectorAll('select')).find(select => select.getAttribute('aria-label') === null
      && select.parentElement?.textContent?.includes('Registered Prometheus Data Source'))!;
    await act(async () => {
      selector.value = 'prom-main'; selector.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const metricSourceEditor = Array.from(host.querySelectorAll('textarea')).find(editor => editor.value.includes('att-sut-metrics-metric-source/v1'))!;
    expect(JSON.parse(metricSourceEditor.value)).toMatchObject({
      connection: { kind: 'DATA_SOURCE', id: 'prom-main', revision: 'a'.repeat(64) },
    });
    expect(api.testSutMetricSource).not.toHaveBeenCalled();
  });

  it('shows server schema diagnostics beside the affected JSON editor', async () => {
    vi.spyOn(api, 'listSutMetricGroups').mockResolvedValue({ items: [{ id: 'host-servers', title: 'Host servers' }] });
    vi.spyOn(api, 'saveSutMetricGroup').mockRejectedValue(new ApiError('Metric Group definition has field validation errors', {
      status: 400, code: 'ATT-SUT-METRICS-INVALID-DEFINITION', diagnostics: [
        { code: 'ATT-SUT-METRICS-FIELD-INVALID', path: '/match/metricIds', message: 'must contain at least one item', keyword: 'minItems' },
      ],
    }));
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    const save = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Metric Group');
    await act(async () => { save!.click(); await Promise.resolve(); await Promise.resolve(); });

    const groupEditor = Array.from(host.querySelectorAll('textarea')).find(editor => editor.value.includes('att-sut-metrics-group/v1'))!;
    expect(groupEditor.getAttribute('aria-invalid')).toBe('true');
    expect(groupEditor.getAttribute('aria-describedby')).toBe('sut-metric-group-errors');
    expect(host.querySelector('#sut-metric-group-errors')?.textContent).toContain('/match/metricIds');
    expect(host.querySelector('#sut-metric-group-errors')?.textContent).toContain('must contain at least one item');
  });

  it('selects only catalogued Metric Sources and Groups in a Flat Profile', async () => {
    const saveProfile = vi.spyOn(api, 'saveSutMetricProfile').mockResolvedValue({});
    vi.spyOn(api, 'listSutMetricAcquisitions').mockResolvedValue({ items: [{ id: 'host-metrics', name: 'Host metrics' }] });
    vi.spyOn(api, 'listSutMetricGroups').mockResolvedValue({ items: [{ id: 'host-servers', title: 'Host servers' }, { id: 'db-servers', title: 'Database servers' }] });
    vi.spyOn(api, 'listSutMetricProfiles').mockResolvedValue({ items: [{
      schemaVersion: 'att-sut-metrics-profile/v2', id: 'flat-host', name: 'Flat host profile',
      metricSourceIds: ['host-metrics'], groupIds: ['host-servers'],
      allowedBindings: { environments: ['SIT'], targets: [{ type: 'target', id: 'sut-1' }], tenants: [], parameters: {} },
      budgets: { maxFetches: 1, maxObservations: 64, maxArtifactBytes: 1048576 }, revision: 'c'.repeat(64),
    }] });
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });
    const profileRow = Array.from(host.querySelectorAll('li')).find(item => item.textContent?.includes('flat-host'))!;
    const editProfile = Array.from(profileRow.querySelectorAll('button')).find(button => button.textContent === 'Edit JSON');
    await act(async () => { editProfile!.click(); });
    const groupCheckbox = Array.from(host.querySelectorAll<HTMLInputElement>('.att-sut-profile-selection input[type="checkbox"]'))
      .find(input => input.parentElement?.textContent?.includes('db-servers'))!;
    await act(async () => { groupCheckbox.click(); });
    const profileEditor = Array.from(host.querySelectorAll('textarea')).find(editor => editor.value.includes('att-sut-metrics-profile/v2'))!;
    const definition = JSON.parse(profileEditor.value) as { metricSourceIds: string[]; groupIds: string[] };
    expect(definition.metricSourceIds).toEqual(['host-metrics']);
    expect(definition.groupIds).toEqual(['host-servers', 'db-servers']);
    expect(definition).not.toHaveProperty('revision');

    const saveFlatProfile = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Flat Profile');
    await act(async () => { saveFlatProfile!.click(); await Promise.resolve(); });
    expect(saveProfile).toHaveBeenCalled();
    expect(saveProfile.mock.calls[0]?.[0]).not.toHaveProperty('revision');
  });

  it('recomputes an existing Metric Source revision when saving edits', async () => {
    const saveSource = vi.spyOn(api, 'saveSutMetricAcquisition').mockResolvedValue({});
    vi.spyOn(api, 'listSutMetricAcquisitions').mockResolvedValue({ items: [{
      schemaVersion: 'att-sut-metrics-metric-source/v1', id: 'host-metrics', name: 'Host metrics',
      connection: { kind: 'DATA_SOURCE', id: 'prom-main', revision: 'b'.repeat(64) },
      fetch: { id: 'host-series', mode: 'prometheus-range', query: 'up', parameters: {}, sampling: 'range', step: '15s' },
      mapping: { labels: { retain: ['instance'] }, outputs: [{ id: 'cpu', title: 'CPU', unit: 'percent', kind: 'gauge', metricName: 'up' }] },
      limits: { maxRows: 64, maxSeries: 16, maxSamples: 1000, maxArtifactBytes: 1048576 }, revision: 'a'.repeat(64),
    }] });
    await act(async () => {
      root.render(<AppShellContext.Provider value={context}><SettingsPage /></AppShellContext.Provider>);
      await Promise.resolve();
    });

    const sourceRow = Array.from(host.querySelectorAll('li')).find(item => item.textContent?.includes('host-metrics'))!;
    const editSource = Array.from(sourceRow.querySelectorAll('button')).find(button => button.textContent === 'Edit JSON');
    await act(async () => { editSource!.click(); });
    const saveMetricSource = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Save Metric Source');
    await act(async () => { saveMetricSource!.click(); await Promise.resolve(); });

    expect(saveSource).toHaveBeenCalled();
    expect(saveSource.mock.calls[0]?.[0]).not.toHaveProperty('revision');
    expect(saveSource.mock.calls[0]?.[0].connection).toEqual({ kind: 'DATA_SOURCE', id: 'prom-main', revision: 'b'.repeat(64) });
  });
});
