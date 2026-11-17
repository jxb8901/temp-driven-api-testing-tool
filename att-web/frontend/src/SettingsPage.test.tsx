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
