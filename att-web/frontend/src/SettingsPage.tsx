import { useEffect, useState } from 'react';
import { useAppShellContext, type ThemePreference } from './AppShell';
import { api, type ServerJobStorageMetrics } from './api';
import { ApiError } from './types';
import { authUiText } from './authUiText';
import { UserManagementPanel } from './UserManagement';
import './SettingsPage.css';

const EMPTY_SUT_SOURCE = {
  schemaVersion: 'att-sut-metrics-source/v1', id: 'prom-main', name: 'Main Prometheus',
  provider: 'PROMETHEUS', url: 'https://prometheus.example.internal', apiPath: '/api/v1/query_range',
  tls: { verify: true }, auth: { mode: 'NONE' }, readers: [], readRoles: ['USER'],
  allowedTenants: [], limits: { connectTimeoutMs: 5000, requestTimeoutMs: 10000, maxRangeSeconds: 93600, maxQueriesPerJob: 256 },
};
const EMPTY_SUT_PROFILE = {
  schemaVersion: 'att-sut-metrics-profile/v1', id: 'profile-os', name: 'Operating system', kind: 'base', category: 'os',
  params: { instance: { type: 'promLabelValue', required: true } }, overridableParams: ['instance'],
  metrics: [{ id: 'cpu', title: 'CPU usage', unit: 'percent', kind: 'gauge', query: '100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle",instance="{{instance}}"}[5m])))' }],
};
const EMPTY_BUSINESS_PROFILE = {
  schemaVersion: 'att-sut-metrics-profile/v1', id: 'business-metrics', name: 'Business Metrics', kind: 'base', category: 'db', metrics: [],
};
const EMPTY_METRIC_SOURCE = {
  schemaVersion: 'att-sut-metrics-metric-source/v1', id: 'host-metrics', name: 'Host metrics',
  connection: { kind: 'DATA_SOURCE', id: 'prom-main', revision: '0'.repeat(64) },
  fetch: { id: 'host-series', mode: 'prometheus-range', query: 'up{instance="{{instance}}"}', parameters: {}, sampling: 'range', step: '30s' },
  mapping: { labels: { retain: ['instance'] }, outputs: [
    { id: 'host_up', title: 'Host availability', unit: 'ratio', kind: 'gauge', metricName: 'up' },
  ] },
  limits: { maxRows: 64, maxSeries: 16, maxSamples: 10000, maxArtifactBytes: 1048576 },
};
const EMPTY_METRIC_GROUP = {
  schemaVersion: 'att-sut-metrics-group/v1', id: 'host-servers', title: 'Host servers', order: 10,
  match: { metricIds: ['host_up'] }, groupBy: ['instance'], display: 'table',
};
const EMPTY_FLAT_PROFILE = {
  schemaVersion: 'att-sut-metrics-profile/v2', id: 'host-profile', name: 'Host profile',
  metricSourceIds: ['host-metrics'], groupIds: ['host-servers'],
  allowedBindings: { environments: ['TEST'], targets: [{ type: 'target', id: 'sut-1' }], tenants: [], parameters: { instance: { type: 'promLabelValue', required: true } } },
  budgets: { maxFetches: 4, maxObservations: 256, maxArtifactBytes: 4194304 },
};
const EMPTY_SUT_BINDING = {
  schemaVersion: 'att-load-observability/v1', collections: [{ profile: 'profile-os', source: 'prom-main',
    targets: [{ id: 'sut-1', type: 'application', parameters: { instance: 'app-1:8080' } }] }],
  collection: { mode: 'post-run', before: '5m', after: '2m', step: '30s' },
  livePreview: { enabled: true, refreshInterval: '30s', defaultRange: '15m' },
};

type SutFieldDiagnostic = { path: string; message: string; code?: string; keyword?: string };

function sutFieldDiagnostics(value: unknown): SutFieldDiagnostic[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap(item => {
    if (!item || typeof item !== 'object') return [];
    const diagnostic = item as Record<string, unknown>;
    if (typeof diagnostic.path !== 'string' || typeof diagnostic.message !== 'string') return [];
    return [{ path: diagnostic.path, message: diagnostic.message,
      ...(typeof diagnostic.code === 'string' ? { code: diagnostic.code } : {}),
      ...(typeof diagnostic.keyword === 'string' ? { keyword: diagnostic.keyword } : {}) }];
  }).slice(0, 64);
}

function editorStringList(text: string, field: string): string[] {
  try {
    const parsed: unknown = JSON.parse(text);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [];
    const values = (parsed as Record<string, unknown>)[field];
    return Array.isArray(values) ? values.filter((value): value is string => typeof value === 'string') : [];
  } catch { return []; }
}

const themeOptions: Array<{ value: ThemePreference; label: string; description: string }> = [
  { value: 'system', label: 'System', description: 'Follow the appearance preference set by your operating system.' },
  { value: 'light', label: 'Light', description: 'Use the light color theme on this browser.' },
  { value: 'dark', label: 'Dark', description: 'Use the dark color theme on this browser.' },
];

export function SettingsPage() {
  const { themePreference, onThemePreferenceChange, uiLocale, uiLocalePreference, onUiLocaleChange, authMode, accessCapabilities, preferenceStatus } = useAppShellContext();
  const locale = uiLocale;
  const zh=locale==='zh-Hant';
  const preferenceMessage=preferenceStatus==='saving'?(zh?'正在將偏好儲存至您的帳戶…':'Saving preferences to your account…'):preferenceStatus==='saved'?(zh?'偏好已儲存至您的帳戶。':'Preferences saved to your account.'):preferenceStatus==='failed'?(zh?'無法將偏好儲存至帳戶；所選值仍在本機生效。請檢查 Server 連線，然後再次變更以重試。':'Could not save preferences to your account. The current value remains active locally; check the Server connection and change it again to retry.'):preferenceStatus==='browser-only'?(zh?'帳戶偏好儲存區不可用；偏好只會儲存在此瀏覽器。':'Account preference storage is unavailable. Preferences use this browser only.'):preferenceStatus==='idle'?(zh?'尚未儲存帳戶偏好；目前使用瀏覽器及系統預設值。':'No account preference is saved yet; browser and system defaults are active.'):(zh?'正在載入帳戶偏好…':'Loading account preferences…');

  return (
    <section className="page-content att-settings-page" aria-labelledby="settings-title">
      <header className="page-heading">
        <div>
          <p className="eyebrow">ATT Studio</p>
          <h1 id="settings-title">{authUiText('settingsTitle',locale)}</h1>
          <p className="page-description">{authUiText('settingsDescription',locale)}</p>
        </div>
      </header>

      {(accessCapabilities.accountManage || authMode === 'container') && <UserManagementPanel />}
      {accessCapabilities.systemSettingsWrite && <ServerStoragePanel />}
      {accessCapabilities.systemSettingsWrite && <SutMetricsAdminPanel />}
      <p className="att-settings-persistence" role="status" aria-live="polite">{preferenceMessage}</p>

      <section className="panel att-settings-section" aria-labelledby="appearance-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="appearance-title">Appearance</h2>
            <p className="att-settings-help">Choose how ATT Studio displays colors and contrast.</p>
          </div>
          <span className="att-settings-current" role="status" aria-live="polite">Theme preference: {themePreference === 'system' ? 'System' : themePreference === 'dark' ? 'Dark' : 'Light'}.</span>
        </div>
        <fieldset className="att-settings-options">
          <legend className="sr-only">Color theme preference</legend>
          {themeOptions.map(option => (
            <label className="att-settings-option" key={option.value}>
              <input
                type="radio"
                name="theme-preference"
                value={option.value}
                checked={themePreference === option.value}
                onChange={() => onThemePreferenceChange(option.value)}
              />
              <span className="att-settings-option-copy">
                <strong>{option.label}</strong>
                <small>{option.description}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <p className="att-settings-storage-note">{zh?'「系統」會跟隨作業系統；伺服器支援時，會儲存為帳戶偏好。':'System follows the operating system and is saved as an account preference when the Server supports it.'}</p>
      </section>

      <section className="panel att-settings-section" aria-labelledby="language-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="language-title">Language</h2>
            <p className="att-settings-help">Choose the language used throughout ATT Studio.</p>
          </div>
          <span className="att-settings-current" role="status" aria-live="polite">
            {locale === 'zh-Hant' ? '目前語言：繁體中文。' : 'Current language: English.'}
          </span>
        </div>
        <fieldset className="att-settings-options">
          <legend className="sr-only">Studio language preference</legend>
          {([
            { value: 'browser' as const, label: 'Browser default', description: 'Follow this browser’s language unless you choose a language below.' },
            { value: 'en' as const, label: 'English', description: 'Use English throughout ATT Studio.' },
            { value: 'zh-Hant' as const, label: 'Traditional Chinese', description: 'Use Traditional Chinese throughout ATT Studio.' },
          ]).map(option => (
            <label className="att-settings-option" key={option.value}>
              <input
                type="radio"
                name="ui-language"
                value={option.value}
                checked={uiLocalePreference === option.value}
                onChange={() => onUiLocaleChange(option.value)}
              />
              <span className="att-settings-option-copy">
                <strong>{option.label}</strong>
                <small>{option.description}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <p className="att-settings-storage-note">{zh?'「瀏覽器預設」會跟隨此瀏覽器；伺服器支援時，會儲存為帳戶偏好。':'Browser default follows this browser and is saved as an account preference when the Server supports it.'}</p>
      </section>

      <section className="panel att-settings-section" aria-labelledby="keyboard-settings-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="keyboard-settings-title">Keyboard shortcuts</h2>
            <p className="att-settings-help">Use these shortcuts from any page. Text-entry fields keep their normal typing behavior.</p>
          </div>
        </div>
        <dl className="att-settings-shortcuts">
          <div><dt>Search and open a page</dt><dd><kbd>Ctrl K</kbd> or <kbd>⌘ K</kbd>, or <kbd>/</kbd></dd></div>
          <div><dt>Show shortcut help</dt><dd><kbd>?</kbd></dd></div>
          <div><dt>Close a dialog or mobile navigation</dt><dd><kbd>Escape</kbd></dd></div>
          <div><dt>Start a validated Load draft</dt><dd><kbd>Ctrl Enter</kbd> or <kbd>⌘ Enter</kbd>; confirmation is required</dd></div>
        </dl>
      </section>
    </section>
  );
}

function SutMetricsAdminPanel() {
  const { uiLocale } = useAppShellContext();
  const zh = uiLocale === 'zh-Hant';
  const [sources, setSources] = useState<Array<Record<string, unknown>>>([]);
  const [metricSources, setMetricSources] = useState<Array<Record<string, unknown>>>([]);
  const [groups, setGroups] = useState<Array<Record<string, unknown>>>([]);
  const [profiles, setProfiles] = useState<Array<Record<string, unknown>>>([]);
  const [catalogConfiguration, setCatalogConfiguration] = useState<Record<string, unknown> | null>(null);
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'unavailable'>('loading');
  const [sourceJson, setSourceJson] = useState(JSON.stringify(EMPTY_SUT_SOURCE, null, 2));
  const [metricSourceJson, setMetricSourceJson] = useState(JSON.stringify(EMPTY_METRIC_SOURCE, null, 2));
  const [groupJson, setGroupJson] = useState(JSON.stringify(EMPTY_METRIC_GROUP, null, 2));
  const [flatProfileJson, setFlatProfileJson] = useState(JSON.stringify(EMPTY_FLAT_PROFILE, null, 2));
  const [profileJson, setProfileJson] = useState(JSON.stringify(EMPTY_SUT_PROFILE, null, 2));
  const [businessProfileJson, setBusinessProfileJson] = useState(JSON.stringify(EMPTY_BUSINESS_PROFILE, null, 2));
  const [mappedGroupId, setMappedGroupId] = useState('transaction-query');
  const [mappedCall, setMappedCall] = useState("#{db.orders.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}");
  const [mappedRowsPath, setMappedRowsPath] = useState('rows');
  const [mappedWorkloadColumn, setMappedWorkloadColumn] = useState('workload_id');
  const [mappedMixColumn, setMappedMixColumn] = useState('mix_id');
  const [mappedSampling, setMappedSampling] = useState<'afterDrain' | 'windowQuery'>('afterDrain');
  const [mappedToolAcknowledged, setMappedToolAcknowledged] = useState(false);
  const [mappedOutputs, setMappedOutputs] = useState([
    { id: 'success_transactions', title: 'Successful transactions', column: 'success', unit: 'transactions', kind: 'counter' },
    { id: 'failed_transactions', title: 'Failed transactions', column: 'failure', unit: 'transactions', kind: 'counter' },
  ]);
  const [bindingJson, setBindingJson] = useState(JSON.stringify(EMPTY_SUT_BINDING, null, 2));
  const [preview, setPreview] = useState<unknown>(null);
  const [definitionPreview, setDefinitionPreview] = useState<unknown>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, SutFieldDiagnostic[]>>({});
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function reload(signal?: AbortSignal) {
    try {
      const sourceResponse = await api.listSutMetricSources(signal);
      setSources(sourceResponse.items || []);
      setMetricSources([]); setGroups([]); setProfiles([]);
      setCatalogConfiguration(sourceResponse.configuration || null);
      setCatalogStatus('ready');
    } catch (error) {
      if (!signal?.aborted) setCatalogStatus('unavailable');
      throw error;
    }
  }
  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal).catch(error => {
      if (!controller.signal.aborted) {
        setCatalogStatus('unavailable');
        setMessage(error instanceof Error ? error.message : 'SUT Metrics catalog is unavailable.');
      }
    });
    return () => controller.abort();
  }, []);

  async function run(action: () => Promise<unknown>, success: string, editorKey?: string) {
    setBusy(true); setMessage('');
    try { await action(); await reload(); setMessage(success); }
    catch (error) {
      const diagnostics = error instanceof ApiError ? sutFieldDiagnostics(error.diagnostics) : [];
      if (editorKey) setFieldErrors(current => ({ ...current, [editorKey]: diagnostics }));
      setMessage(error instanceof Error ? error.message : 'SUT Metrics administration failed.');
    }
    finally { setBusy(false); }
  }
  function clearFieldErrors(editorKey: string) {
    setFieldErrors(current => current[editorKey]?.length ? { ...current, [editorKey]: [] } : current);
  }
  function renderFieldErrors(editorKey: string) {
    const diagnostics = fieldErrors[editorKey] || [];
    if (!diagnostics.length) return null;
    return <ul id={`sut-${editorKey}-errors`} className="att-sut-field-errors" role="alert" aria-live="polite">
      {diagnostics.map((diagnostic, index) => <li key={`${diagnostic.path}-${index}`}>
        <code>{diagnostic.path}</code> {diagnostic.message}
      </li>)}
    </ul>;
  }
  function updateFlatProfileSelection(field: 'metricSourceIds' | 'groupIds', id: string, selected: boolean) {
    try {
      const definition = parseEditor(flatProfileJson, 'Flat Metrics Profile');
      delete definition.revision;
      const current = Array.isArray(definition[field]) ? (definition[field] as unknown[]).filter((item): item is string => typeof item === 'string') : [];
      const next = selected ? [...new Set([...current, id])] : current.filter(item => item !== id);
      setFlatProfileJson(JSON.stringify({ ...definition, [field]: next }, null, 2));
      clearFieldErrors('flat-profile');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Flat Profile JSON is invalid.');
    }
  }
  function parseEditor(text: string, label: string): Record<string, unknown> {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be a JSON object.`);
    return value as Record<string, unknown>;
  }
  function parseEditableDefinition(text: string, label: string): Record<string, unknown> {
    const definition = parseEditor(text, label);
    delete definition.revision;
    return definition;
  }
  function addMappedBusinessCall() {
    try {
      const profile = parseEditor(businessProfileJson, 'Business Metrics Profile');
      const metrics = Array.isArray(profile.metrics) ? profile.metrics : [];
      const mapping: Record<string, string> = { rows: mappedRowsPath.trim() || 'rows' };
      if (mappedWorkloadColumn.trim()) mapping.workloadColumn = mappedWorkloadColumn.trim();
      if (mappedMixColumn.trim()) mapping.mixColumn = mappedMixColumn.trim();
      const group = {
        id: mappedGroupId.trim(), call: mappedCall.trim(), sampling: mappedSampling,
        ...(mappedSampling === 'windowQuery' ? { window: 'steady' } : {}),
        sideEffectsAcknowledged: mappedToolAcknowledged,
        mapping,
        outputs: mappedOutputs.map(output => ({ ...output })),
      };
      setBusinessProfileJson(JSON.stringify({ ...profile, kind: 'base', metrics: [...metrics, group] }, null, 2));
      setMessage(zh ? '多指標 business call 已加入獨立的 Business Metrics Profile 草稿。' : 'Multi-output business call added to the separate Business Metrics Profile draft.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : (zh ? '無法建立 business call JSON。' : 'Could not build the business call JSON.'));
    }
  }
  function updateMappedOutput(index: number, key: keyof typeof mappedOutputs[number], value: string) {
    setMappedOutputs(current => current.map((output, row) => row === index ? { ...output, [key]: value } : output));
  }
  function previewMetricSource() {
    try {
      const definition = parseEditor(metricSourceJson, 'Metric Source');
      const fetch = definition.fetch && typeof definition.fetch === 'object' ? definition.fetch as Record<string, unknown> : {};
      const mapping = definition.mapping && typeof definition.mapping === 'object' ? definition.mapping as Record<string, unknown> : {};
      const outputs = Array.isArray(mapping.outputs) ? mapping.outputs : [];
      const limits = definition.limits && typeof definition.limits === 'object' ? definition.limits as Record<string, unknown> : {};
      setDefinitionPreview({ kind: 'metricSource', mode: fetch.mode, estimatedLogicalFetches: 1,
        declaredOutputs: outputs.length, maxRows: limits.maxRows ?? null, maxSeries: limits.maxSeries ?? null,
        maxSamples: limits.maxSamples ?? null, maxArtifactBytes: limits.maxArtifactBytes ?? null, outputs,
        note: 'One acquisition is shared by the declared outputs; collection limits are enforced against the pinned definition.' });
      setMessage(zh ? '預覽只檢視對照定義，沒有執行 SQL、Tool 或 Prometheus 查詢。' : 'Preview inspected the mapping only; no SQL, Tool, or Prometheus query ran.');
    } catch (error) {
      setDefinitionPreview({ kind: 'error', message: error instanceof Error ? error.message : 'Metric Source JSON is invalid.' });
    }
  }
  function previewMetricGroup() {
    try {
      const definition = parseEditor(groupJson, 'Metric Group');
      setDefinitionPreview({ kind: 'group', match: definition.match, groupBy: definition.groupBy, display: definition.display });
      setMessage(zh ? '分組預覽只檢視精確比對條件，沒有讀取或改寫 Job 觀察值。' : 'Group preview inspected exact match rules only; no Job observation was read or changed.');
    } catch (error) {
      setDefinitionPreview({ kind: 'error', message: error instanceof Error ? error.message : 'Metric Group JSON is invalid.' });
    }
  }
  function previewFlatProfile() {
    try {
      const definition = parseEditor(flatProfileJson, 'Flat Metrics Profile');
      const sourceIds = Array.isArray(definition.metricSourceIds) ? definition.metricSourceIds : [];
      const groupIds = Array.isArray(definition.groupIds) ? definition.groupIds : [];
      const allowedBindings = definition.allowedBindings && typeof definition.allowedBindings === 'object'
        ? definition.allowedBindings as Record<string, unknown> : {};
      const targets = Array.isArray(allowedBindings.targets) ? allowedBindings.targets : [];
      const sourcePlans = sourceIds.map(rawId => {
        const id = String(rawId);
        const source = metricSources.find(item => item.id === id);
        const fetch = source?.fetch && typeof source.fetch === 'object' ? source.fetch as Record<string, unknown> : {};
        const mapping = source?.mapping && typeof source.mapping === 'object' ? source.mapping as Record<string, unknown> : {};
        const outputs = Array.isArray(mapping.outputs) ? mapping.outputs.length : 0;
        const limits = source?.limits && typeof source.limits === 'object' ? source.limits as Record<string, unknown> : {};
        const prometheus = fetch.mode === 'prometheus-range';
        const bindings = prometheus ? targets.length : 1;
        return {
          sourceId: id, mode: fetch.mode || 'unresolved',
          estimatedLogicalFetches: bindings,
          declaredOutputsPerFetch: outputs,
          estimatedOutputMappings: bindings * outputs,
          maxRowsPerFetch: limits.maxRows ?? null, maxSeriesPerFetch: limits.maxSeries ?? null,
          maxSamplesPerFetch: limits.maxSamples ?? null, maxArtifactBytes: limits.maxArtifactBytes ?? null,
          estimateNote: source ? (prometheus ? 'One shared Prometheus fetch per allowed target; declared outputs route from that response.' : 'One shared DBHelper or Package Tool call for this source; workload-specific execution can vary by binding scope.') : 'Metric Source is not in the loaded catalog.',
        };
      });
      const budgets = definition.budgets && typeof definition.budgets === 'object' ? definition.budgets as Record<string, unknown> : {};
      setDefinitionPreview({ kind: 'profile', estimate: true, sourcePlans,
        estimatedLogicalFetches: sourcePlans.reduce((sum, source) => sum + source.estimatedLogicalFetches, 0),
        estimatedOutputMappings: sourcePlans.reduce((sum, source) => sum + source.estimatedOutputMappings, 0),
        targetBindings: targets.length, sourceIds, groupIds,
        profileBudgets: { maxFetches: budgets.maxFetches ?? null, maxObservations: budgets.maxObservations ?? null, maxArtifactBytes: budgets.maxArtifactBytes ?? null },
        note: 'Definition-level estimate only. Final fetches depend on the validated Job binding; previews never execute source calls.' });
      setMessage(zh ? 'Profile 預覽顯示來源級 fetch／輸出上限估算；實際數量仍由已驗證的 Job binding 決定，沒有執行來源呼叫。' : 'Profile preview shows source-level fetch and output estimates. The validated Job binding determines final counts; no source call ran.');
    } catch (error) {
      setDefinitionPreview({ kind: 'error', message: error instanceof Error ? error.message : 'Flat Profile JSON is invalid.' });
    }
  }

  const selectedMetricSourceIds = editorStringList(flatProfileJson, 'metricSourceIds');
  const selectedGroupIds = editorStringList(flatProfileJson, 'groupIds');
  const catalogReadOnly = catalogConfiguration?.origin === 'server.runtimeConfigPath';
  const catalogCanEdit = catalogStatus === 'ready' && !catalogReadOnly;
  let metricSourceConnectionId = '';
  let metricSourceMode = '';
  try {
    const definition = parseEditor(metricSourceJson, 'Metric Source');
    const connection = definition.connection && typeof definition.connection === 'object' ? definition.connection as Record<string, unknown> : {};
    const fetch = definition.fetch && typeof definition.fetch === 'object' ? definition.fetch as Record<string, unknown> : {};
    metricSourceConnectionId = typeof connection.id === 'string' ? connection.id : '';
    metricSourceMode = typeof fetch.mode === 'string' ? fetch.mode : '';
  } catch { /* The JSON editor remains the source of truth while it is incomplete. */ }
  function selectMetricSourceDataSource(id: string) {
    const selected = sources.find(source => source.id === id);
    if (!selected) return;
    try {
      const definition = parseEditor(metricSourceJson, 'Metric Source');
      delete definition.revision;
      setMetricSourceJson(JSON.stringify({ ...definition, connection: {
        kind: 'DATA_SOURCE', id, revision: typeof selected.revision === 'string' ? selected.revision : '0'.repeat(64),
      } }, null, 2));
      clearFieldErrors('metric-source');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Metric Source JSON is invalid.');
    }
  }

  return <section className="panel att-settings-section att-sut-metrics-admin" aria-labelledby="sut-metrics-admin-title">
    <div className="att-settings-section-heading"><div><h2 id="sut-metrics-admin-title">SUT Metrics connections</h2>
      <p className="att-settings-help">{catalogReadOnly
        ? (zh ? '全域 Data Source 連線和安全政策由核准的中央 Runtime Config 管理。此處可檢視 Data Source 連線、讀取權限與資源上限。' : 'Global Data Source connections and security policy come from the approved central Runtime Config. Review connection metadata, reader permissions, and resource limits here.')
        : (zh ? '管理全域 Prometheus/Grafana Data Source 連線、讀取權限與資源上限。Metric Sources、Groups 和 Profiles 由各 Package 的 LOAD 資源擁有。' : 'Manage global Prometheus/Grafana Data Source connections, reader permissions, and resource limits. Packages own their Metric Sources, Groups, and Profiles under LOAD resources.')}</p></div></div>
    {message && <p role="status" aria-live="polite">{message}</p>}
    {catalogReadOnly && <aside className="att-sut-read-only-banner" aria-label={zh ? 'SUT Metrics 設定唯讀' : 'SUT Metrics configuration is read-only'}>
      <strong>{zh ? '由中央 Runtime Config 管理 · 唯讀' : 'Managed by central Runtime Config · read-only'}</strong>
      <p>{zh
        ? '此部署的全域 Data Source 連線由伺服器核准的中央設定提供。請透過核准的部署流程更新 Runtime Config（config.yaml）。'
        : 'This deployment loads global Data Source connections from the server-approved central configuration. Update the Runtime Config (config.yaml) through the approved deployment process.'}</p>
      <p>{zh
        ? catalogConfiguration?.state === 'LAST_KNOWN_GOOD' ? '目前啟用最後一個有效版本。' : '目前使用已啟用的 Runtime Config。'
        : catalogConfiguration?.state === 'LAST_KNOWN_GOOD' ? 'The active catalog is the last known good revision.' : 'The selected Runtime Config is active.'}</p>
      {catalogConfiguration?.state === 'LAST_KNOWN_GOOD' && typeof catalogConfiguration.message === 'string'
        && <p role="alert">{catalogConfiguration.message}</p>}
    </aside>}
    {catalogStatus === 'loading' && <p role="status" className="att-settings-help">{zh
      ? '正在載入 SUT Metrics 目錄；伺服器確認目錄可寫入前，編輯與變更操作會保持停用。'
      : 'Loading the SUT Metrics catalog. Editing stays unavailable until the server confirms whether this catalog is writable.'}</p>}
    {catalogStatus === 'unavailable' && <p role="alert" className="att-settings-help">{zh
      ? '無法確認 SUT Metrics 目錄狀態；為避免提交不支援的變更，編輯操作已停用。'
      : 'The SUT Metrics catalog status could not be confirmed. Editing is disabled to avoid submitting unsupported changes.'}</p>}
    <section aria-labelledby="sut-sources-title"><h3 id="sut-sources-title">SUT Metrics Data Sources</h3>
      <ul className="att-sut-catalog-list">{sources.map(source => <li key={String(source.id)}><strong>{String(source.name || source.id)}</strong> <code>{String(source.id)}</code>
        <button type="button" disabled={busy} onClick={() => setSourceJson(JSON.stringify(sourceForEditor(source), null, 2))}>{catalogCanEdit ? (zh ? '編輯 JSON' : 'Edit JSON') : (zh ? '檢視 JSON' : 'View JSON')}</button>
        <button type="button" disabled={busy} onClick={() => void run(() => api.testSutMetricSource(String(source.id)), `Data Source ${String(source.id)} responded successfully.`)}>Test</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricSource(String(source.id)), `Data Source ${String(source.id)} deleted.`)}>Delete</button>}
      </li>)}</ul>
      <label>Data Source JSON<textarea value={sourceJson} readOnly={!catalogCanEdit} aria-invalid={Boolean(fieldErrors['data-source']?.length)} aria-describedby={fieldErrors['data-source']?.length ? 'sut-data-source-errors' : undefined} onChange={event => { setSourceJson(event.target.value); clearFieldErrors('data-source'); }} rows={12} spellCheck={false} /></label>
      {renderFieldErrors('data-source')}
      <p className="att-settings-help">Set <code>provider</code> to <code>GRAFANA</code> to pin <code>grafanaDatasourceUid</code>. Grafana organization IDs in <code>allowedTenants</code> and <code>defaultTenant</code> must be numeric. The adapter accepts only server-owned profiles and never accepts a browser query or proxy URL.</p>
      {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricSource(parseEditor(sourceJson, 'Data Source')), 'Data Source saved.', 'data-source')}>Save Data Source</button>}
    </section>
    <p className="att-settings-help">{zh
      ? 'Package 的 Metric Sources、Metric Groups 和 Metrics Profiles 位於所選 Package 的 LOAD → SUT Metrics。此處不會登錄或合併 Package 定義。'
      : 'Package Metric Sources, Metric Groups, and Metrics Profiles live under the selected Package’s LOAD → SUT Metrics resources. This page does not register or merge Package definitions.'}</p>
    {false && <div>
    <section aria-labelledby="sut-metric-sources-title">
      <h3 id="sut-metric-sources-title">Metric Sources</h3>
      <p className="att-settings-help">{zh
        ? 'Metric Source 只描述一次 Prometheus、DBHelper 或既有 Package Tool 擷取與明確輸出欄位；不會建立第二份連線註冊。Prometheus 使用 mapping.labels.retain；SQL 類來源使用 mapping.labels 將輸出 label 對應至明確結果欄位。未映射欄位不會輸出。預覽只解析設定，不會執行查詢或 Tool。'
        : 'A Metric Source describes one Prometheus, DBHelper, or existing Package Tool acquisition with explicit outputs. Use mapping.labels.retain for Prometheus, or map output labels to explicit SQL result columns with mapping.labels. Unmapped columns are not published. Preview parses the definition and never runs a query or Tool.'}</p>
      <ul className="att-sut-catalog-list">{metricSources.map(source => {
        const mapping = source.mapping && typeof source.mapping === 'object' ? source.mapping as Record<string, unknown> : {};
        const outputCount = Array.isArray(mapping.outputs) ? mapping.outputs.length : 0;
        return <li key={String(source.id)}><strong>{String(source.name || source.id)}</strong> <code>{String(source.id)}</code>
          <span>{zh ? `1 次有效擷取 · ${outputCount} 個輸出` : `1 effective fetch · ${outputCount} outputs`}</span>
          <button type="button" disabled={busy} onClick={() => setMetricSourceJson(JSON.stringify(source, null, 2))}>{catalogCanEdit ? (zh ? '編輯 JSON' : 'Edit JSON') : (zh ? '檢視 JSON' : 'View JSON')}</button>
          {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricAcquisition(String(source.id)), `Metric Source ${String(source.id)} deleted.`)}>{zh ? '刪除' : 'Delete'}</button>}
        </li>;
      })}</ul>
      {metricSourceMode === 'prometheus-range' && <label>{zh ? '已註冊的 Prometheus Data Source' : 'Registered Prometheus Data Source'}
        <select value={sources.some(source => source.id === metricSourceConnectionId) ? metricSourceConnectionId : ''}
          onChange={event => selectMetricSourceDataSource(event.target.value)} disabled={busy || !catalogCanEdit || sources.length === 0}>
          <option value="">{zh ? '選擇 Data Source' : 'Choose a Data Source'}</option>
          {sources.map(source => <option key={String(source.id)} value={String(source.id)}>{String(source.name || source.id)} · {String(source.id)}</option>)}
        </select>
      </label>}
      {catalogCanEdit && <div className="att-sut-editor-actions">
        <button type="button" disabled={busy} onClick={() => {
          const firstSource = sources[0];
          const draft = { ...EMPTY_METRIC_SOURCE, connection: { kind: 'DATA_SOURCE', id: String(firstSource?.id || 'prom-main'), revision: String(firstSource?.revision || '0'.repeat(64)) } };
          setMetricSourceJson(JSON.stringify(draft, null, 2)); setDefinitionPreview(null);
        }}>{zh ? '新增 Metric Source 草稿' : 'New Metric Source draft'}</button>
      </div>}
      <label>{zh ? 'Metric Source JSON' : 'Metric Source JSON'}<textarea value={metricSourceJson} readOnly={!catalogCanEdit} aria-invalid={Boolean(fieldErrors['metric-source']?.length)} aria-describedby={fieldErrors['metric-source']?.length ? 'sut-metric-source-errors' : undefined} onChange={event => { setMetricSourceJson(event.target.value); clearFieldErrors('metric-source'); }} rows={16} spellCheck={false} /></label>
      {renderFieldErrors('metric-source')}
      <div className="att-sut-editor-actions">
        <button type="button" disabled={busy} onClick={previewMetricSource}>{zh ? '預覽擷取與輸出' : 'Preview fetch and outputs'}</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricAcquisition(parseEditableDefinition(metricSourceJson, 'Metric Source')), zh ? 'Metric Source 已儲存並通過 schema 驗證。' : 'Metric Source saved and schema-validated.', 'metric-source')}>{zh ? '儲存 Metric Source' : 'Save Metric Source'}</button>}
      </div>
    </section>
    <section aria-labelledby="sut-metric-groups-title">
      <h3 id="sut-metric-groups-title">Metric Groups</h3>
      <p className="att-settings-help">{zh
        ? 'Group 以 metric ID、source ID 與精確 label 值組織既有觀察值。groupBy 只控制呈現，無 reducer、跨來源 join 或額外 fetch。'
        : 'Groups organize existing observations by metric ID, source ID, and exact label values. groupBy controls presentation only; it adds no reducer, cross-source join, or fetch.'}</p>
      <ul className="att-sut-catalog-list">{groups.map(group => <li key={String(group.id)}><strong>{String(group.title || group.id)}</strong> <code>{String(group.id)}</code> · {String(group.display)}
        <button type="button" disabled={busy} onClick={() => setGroupJson(JSON.stringify(group, null, 2))}>{catalogCanEdit ? (zh ? '編輯 JSON' : 'Edit JSON') : (zh ? '檢視 JSON' : 'View JSON')}</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricGroup(String(group.id)), `Metric Group ${String(group.id)} deleted.`)}>{zh ? '刪除' : 'Delete'}</button>}
      </li>)}</ul>
      {catalogCanEdit && <button type="button" disabled={busy} onClick={() => { setGroupJson(JSON.stringify(EMPTY_METRIC_GROUP, null, 2)); setDefinitionPreview(null); }}>{zh ? '新增 Metric Group 草稿' : 'New Metric Group draft'}</button>}
      <label>Metric Group JSON<textarea value={groupJson} readOnly={!catalogCanEdit} aria-invalid={Boolean(fieldErrors['metric-group']?.length)} aria-describedby={fieldErrors['metric-group']?.length ? 'sut-metric-group-errors' : undefined} onChange={event => { setGroupJson(event.target.value); clearFieldErrors('metric-group'); }} rows={10} spellCheck={false} /></label>
      {renderFieldErrors('metric-group')}
      <div className="att-sut-editor-actions">
        <button type="button" disabled={busy} onClick={previewMetricGroup}>{zh ? '預覽分組條件' : 'Preview group rules'}</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricGroup(parseEditor(groupJson, 'Metric Group')), zh ? 'Metric Group 已儲存並通過 schema 驗證。' : 'Metric Group saved and schema-validated.', 'metric-group')}>{zh ? '儲存 Metric Group' : 'Save Metric Group'}</button>}
      </div>
    </section>
    {definitionPreview !== null && <section className="att-sut-definition-preview" aria-live="polite" aria-label={zh ? '安全預覽' : 'Safe preview'}>
      <h3>{zh ? '安全預覽' : 'Safe preview'}</h3>
      {((definitionPreview as Record<string, unknown>).kind === 'error')
        ? <p role="alert">{String((definitionPreview as Record<string, unknown>).message)}</p>
        : <pre>{JSON.stringify(definitionPreview, null, 2)}</pre>}
    </section>}
    <section aria-labelledby="sut-profiles-title"><h3 id="sut-profiles-title">Metrics Profiles</h3>
      <ul className="att-sut-catalog-list">{profiles.map(profile => <li key={String(profile.id)}><strong>{String(profile.name || profile.id)}</strong> <code>{String(profile.id)}</code> · {profile.schemaVersion === 'att-sut-metrics-profile/v2' ? 'flat v2' : `${String(profile.kind)} / ${String(profile.category)}`}
        <button type="button" disabled={busy} onClick={() => {
          if (profile.schemaVersion === 'att-sut-metrics-profile/v2') { setFlatProfileJson(JSON.stringify(profile, null, 2)); return; }
          const metrics = Array.isArray(profile.metrics) ? profile.metrics as Array<Record<string, unknown>> : [];
          const businessOnly = metrics.length > 0 && metrics.every(metric => typeof metric.call === 'string' && typeof metric.query !== 'string');
          if (businessOnly) setBusinessProfileJson(JSON.stringify(profile, null, 2));
          else setProfileJson(JSON.stringify(profile, null, 2));
        }}>{catalogCanEdit ? 'Edit JSON' : (zh ? '檢視 JSON' : 'View JSON')}</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricProfile(String(profile.id)), `Metrics Profile ${String(profile.id)} deleted.`)}>Delete</button>}
      </li>)}</ul>
      {catalogCanEdit && <fieldset className="att-sut-mapped-call-editor">
        <legend>{zh ? '多指標 business call 對照' : 'Multi-output business call mapping'}</legend>
        <p className="att-settings-help">{zh
          ? '一次執行 DBHelper query 或 Package Tool，將明確資料欄位對應至多個數值 metric。欄位名稱會區分大小寫，必須與查詢結果的欄名完全一致。Workload 和 Mix 欄位必須使用 LOAD 中不可變的 workloadId／mixId；未知或重複的列會保留為診斷資料。'
          : 'Run one DBHelper query or Package Tool and map explicit result columns to numeric metrics. Column names are matched exactly and are case-sensitive, so keep them aligned with the query result labels. Workload and Mix columns must use immutable LOAD workloadId and mixId values; unknown or duplicate rows remain diagnostic. These mappings are drafted in a separate Business Metrics Profile so PromQL profiles stay query-only.'}</p>
        <label>{zh ? 'Call group ID' : 'Call group ID'}<input value={mappedGroupId} onChange={event => setMappedGroupId(event.target.value)} /></label>
        <label>{zh ? 'DBHelper／Package Tool call' : 'DBHelper / Package Tool call'}<textarea value={mappedCall} onChange={event => setMappedCall(event.target.value)} rows={3} spellCheck={false} /></label>
        <label>{zh ? '結果列路徑' : 'Result rows path'}<input value={mappedRowsPath} onChange={event => setMappedRowsPath(event.target.value)} /></label>
        <label>{zh ? 'Workload ID 欄位（選填）' : 'Workload ID column (optional)'}<input value={mappedWorkloadColumn} onChange={event => setMappedWorkloadColumn(event.target.value)} /></label>
        <label>{zh ? 'Mix ID 欄位（選填）' : 'Mix ID column (optional)'}<input value={mappedMixColumn} onChange={event => setMappedMixColumn(event.target.value)} /></label>
        <label>{zh ? '取樣方式' : 'Sampling'}<select value={mappedSampling} onChange={event => setMappedSampling(event.target.value as 'afterDrain' | 'windowQuery')}>
          <option value="afterDrain">afterDrain</option><option value="windowQuery">windowQuery · steady</option>
        </select></label>
        <label className="att-sut-mapped-call-check"><input type="checkbox" checked={mappedToolAcknowledged} onChange={event => setMappedToolAcknowledged(event.target.checked)} />
          {zh ? '已確認 Package Tool 可能有副作用' : 'Acknowledge possible Package Tool side effects'}</label>
        <div className="att-sut-mapped-call-outputs"><strong>{zh ? 'Metric 輸出' : 'Metric outputs'}</strong>
          {mappedOutputs.map((output, index) => <fieldset key={index}>
            <legend>{zh ? `輸出 ${index + 1}` : `Output ${index + 1}`}</legend>
            <label>ID<input value={output.id} onChange={event => updateMappedOutput(index, 'id', event.target.value)} /></label>
            <label>{zh ? '標題' : 'Title'}<input value={output.title} onChange={event => updateMappedOutput(index, 'title', event.target.value)} /></label>
            <label>{zh ? '結果欄位' : 'Result column'}<input value={output.column} onChange={event => updateMappedOutput(index, 'column', event.target.value)} /></label>
            <label>{zh ? '單位' : 'Unit'}<input value={output.unit} onChange={event => updateMappedOutput(index, 'unit', event.target.value)} /></label>
            <label>{zh ? '種類' : 'Kind'}<select value={output.kind} onChange={event => updateMappedOutput(index, 'kind', event.target.value)}>
              <option value="gauge">gauge</option><option value="rate">rate</option><option value="counter">counter</option>
            </select></label>
            {mappedOutputs.length > 1 && <button type="button" disabled={busy} onClick={() => setMappedOutputs(current => current.filter((_, row) => row !== index))}>{zh ? '移除輸出' : 'Remove output'}</button>}
          </fieldset>)}
          <button type="button" disabled={busy || mappedOutputs.length >= 64} onClick={() => setMappedOutputs(current => [...current, { id: '', title: '', column: '', unit: 'count', kind: 'gauge' }])}>{zh ? '新增輸出' : 'Add output'}</button>
        </div>
        <button type="button" disabled={busy} onClick={addMappedBusinessCall}>{zh ? '加入 Business Profile 草稿' : 'Add mapping to Business Profile draft'}</button>
      </fieldset>}
      <label>Prometheus Metrics Profile JSON<textarea value={profileJson} readOnly={!catalogCanEdit} onChange={event => setProfileJson(event.target.value)} rows={16} spellCheck={false} /></label>
      {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricProfile(parseEditor(profileJson, 'Metrics Profile')), 'Metrics Profile saved and dependency graph validated.')}>Save Metrics Profile</button>}
      <label>Business Metrics Profile JSON<textarea value={businessProfileJson} readOnly={!catalogCanEdit} onChange={event => setBusinessProfileJson(event.target.value)} rows={16} spellCheck={false} /></label>
      {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricProfile(parseEditor(businessProfileJson, 'Business Metrics Profile')), 'Business Metrics Profile saved and dependency graph validated.')}>Save Business Metrics Profile</button>}
      <h3>{zh ? 'Flat Profile v2' : 'Flat Profile v2'}</h3>
      <p className="att-settings-help">{zh
        ? '選擇 Metric Sources 與 Groups，並固定環境、target、tenant、參數與預算。此 Profile 不支援繼承；驗證只展開伺服器註冊的呼叫定義，不執行 DBHelper 或 Package Tool。'
        : 'Select Metric Sources and Groups, and pin environment, target, tenant, parameter, and budget allowlists. This Profile has no inheritance. Validation resolves server-registered calls without running DBHelper or Package Tool.'}</p>
      <fieldset className="att-sut-profile-selection">
        <legend>{zh ? 'Metric Sources' : 'Metric Sources'}</legend>
        {metricSources.length ? metricSources.map(source => {
          const id = String(source.id);
          return <label key={`profile-source-${id}`}><input type="checkbox" checked={selectedMetricSourceIds.includes(id)} disabled={!catalogCanEdit} onChange={event => updateFlatProfileSelection('metricSourceIds', id, event.target.checked)} />
            <span><code>{id}</code> · {String(source.name || id)}</span></label>;
        }) : <p className="att-settings-help">{zh ? '尚未註冊 Metric Source。' : 'No Metric Sources are registered.'}</p>}
      </fieldset>
      <fieldset className="att-sut-profile-selection">
        <legend>{zh ? 'Metric Groups' : 'Metric Groups'}</legend>
        {groups.length ? groups.map(group => {
          const id = String(group.id);
          return <label key={`profile-group-${id}`}><input type="checkbox" checked={selectedGroupIds.includes(id)} disabled={!catalogCanEdit} onChange={event => updateFlatProfileSelection('groupIds', id, event.target.checked)} />
            <span><code>{id}</code> · {String(group.title || id)}</span></label>;
        }) : <p className="att-settings-help">{zh ? '尚未註冊 Metric Group。' : 'No Metric Groups are registered.'}</p>}
      </fieldset>
      <label>{zh ? 'Flat Profile JSON' : 'Flat Profile JSON'}<textarea value={flatProfileJson} readOnly={!catalogCanEdit} aria-invalid={Boolean(fieldErrors['flat-profile']?.length)} aria-describedby={fieldErrors['flat-profile']?.length ? 'sut-flat-profile-errors' : undefined} onChange={event => { setFlatProfileJson(event.target.value); clearFieldErrors('flat-profile'); }} rows={14} spellCheck={false} /></label>
      {renderFieldErrors('flat-profile')}
      <div className="att-sut-editor-actions">
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => { setFlatProfileJson(JSON.stringify(EMPTY_FLAT_PROFILE, null, 2)); setDefinitionPreview(null); }}>{zh ? '新增 Flat Profile 草稿' : 'New Flat Profile draft'}</button>}
        <button type="button" disabled={busy} onClick={previewFlatProfile}>{zh ? '預覽來源與 fetch 數' : 'Preview sources and fetch count'}</button>
        {catalogCanEdit && <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricProfile(parseEditableDefinition(flatProfileJson, 'Flat Metrics Profile')), zh ? 'Flat Profile 已儲存，來源、Groups 與重疊規則均已驗證。' : 'Flat Profile saved; sources, Groups, and overlap rules were validated.', 'flat-profile')}>{zh ? '儲存 Flat Profile' : 'Save Flat Profile'}</button>}
      </div>
    </section>
    <section aria-labelledby="sut-binding-title"><h3 id="sut-binding-title">Validate a LOAD observability binding</h3>
      <p>Validation resolves registered profiles, checks permissions and budgets, and returns a redacted query preview.</p>
      <label>Observability JSON<textarea value={bindingJson} onChange={event => setBindingJson(event.target.value)} rows={10} spellCheck={false} /></label>
      <button type="button" disabled={busy} onClick={() => {
        try {
          const binding = parseEditor(bindingJson, 'Observability binding');
          setBusy(true); setMessage(''); setPreview(null);
          void api.previewSutMetricBinding(binding).then(value => {
            setPreview(value); setMessage('Observability binding is valid.');
          }).catch(error => setMessage(error instanceof Error ? error.message : 'Binding validation failed.')).finally(() => setBusy(false));
        } catch (error) { setMessage(error instanceof Error ? error.message : 'Observability binding is invalid.'); }
      }}>Validate binding</button>
      {preview !== null && <pre className="att-sut-preview" aria-label="Redacted SUT Metrics preview">{JSON.stringify(preview, null, 2)}</pre>}
    </section>
    </div>}
  </section>;
}

function sourceForEditor(source: Record<string, unknown>): Record<string, unknown> {
  const tls = source.tls && typeof source.tls === 'object' ? source.tls as Record<string, unknown> : {};
  const auth = source.auth && typeof source.auth === 'object' ? source.auth as Record<string, unknown> : {};
  return {
    schemaVersion: 'att-sut-metrics-source/v1', id: source.id, name: source.name,
    provider: source.provider || 'PROMETHEUS', url: source.url, apiPath: source.apiPath,
    ...(source.grafanaDatasourceUid ? { grafanaDatasourceUid: source.grafanaDatasourceUid } : {}),
    tls: { verify: tls.verify, ...(tls.caSecretRef ? { caSecretRef: tls.caSecretRef } : {}) },
    auth: { mode: auth.mode, ...(auth.secretRef ? { secretRef: auth.secretRef } : {}) },
    readers: source.readers || [], readRoles: source.readRoles || [], allowedTenants: source.allowedTenants || [],
    ...(source.defaultTenant ? { defaultTenant: source.defaultTenant } : {}), limits: source.limits || {},
  };
}

function ServerStoragePanel() {
  const [report, setReport] = useState<ServerJobStorageMetrics | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    api.getServerMetrics(controller.signal).then(value => {
      if (!controller.signal.aborted) setReport(value.jobStorage || null);
    }).catch(error => {
      if (controller.signal.aborted) return;
      setMessage(error instanceof ApiError && (error.status === 401 || error.status === 403)
        ? 'Server storage metrics require system administration.'
        : 'Server storage metrics are unavailable.');
    });
    return () => controller.abort();
  }, []);
  const measured = typeof report?.totalBytes === 'number';
  return <section className="panel att-settings-section" aria-labelledby="server-storage-title">
    <div className="att-settings-section-heading">
      <div>
        <h2 id="server-storage-title">Server storage</h2>
        <p className="att-settings-help">Job directory usage under server.dataDir/jobs. This is not the H2 database file, and retention does not compact H2.</p>
      </div>
    </div>
    {message && <p role="status">{message}</p>}
    {report && <dl className="att-job-overview">
      <div><dt>Size limits</dt><dd>{report.enabled ? 'Enabled' : 'Disabled'}</dd></div>
      <div><dt>State</dt><dd>{report.state || 'Not reported'}</dd></div>
      <div><dt>Job directories</dt><dd>{report.enabled ? (measured ? formatBytes(report.totalBytes) : 'Not measured') : 'Not measured while size limits are disabled'}</dd></div>
      <div><dt>High watermark</dt><dd>{report.enabled ? formatBytes(report.maxTotalBytes) : 'Not configured'}</dd></div>
      <div><dt>Cleanup target</dt><dd>{report.enabled ? formatBytes(report.cleanupTargetBytes) : 'Not configured'}</dd></div>
      <div><dt>Minimum retention</dt><dd>{report.minRetentionHours ?? 'Not reported'} hours</dd></div>
      <div><dt>Last sweep</dt><dd>{report.lastSweepAt || 'No sweep recorded'}</dd></div>
      <div><dt>Last sweep deletions</dt><dd>{report.deletedJobs ?? 'Not reported'}</dd></div>
      <div><dt>Last sweep reclaimed</dt><dd>{typeof report.reclaimedBytes === 'number' ? formatBytes(report.reclaimedBytes) : 'Not reported'}</dd></div>
    </dl>}
  </section>;
}

function formatBytes(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return 'Not measured';
  if (value < 1024) return `${value} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let amount = value / 1024;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit += 1; }
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(amount)} ${units[unit]}`;
}
