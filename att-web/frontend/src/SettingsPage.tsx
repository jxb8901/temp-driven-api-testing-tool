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
const EMPTY_SUT_BINDING = {
  schemaVersion: 'att-load-observability/v1', collections: [{ profile: 'profile-os', source: 'prom-main',
    targets: [{ id: 'sut-1', type: 'application', parameters: { instance: 'app-1:8080' } }] }],
  collection: { mode: 'post-run', before: '5m', after: '2m', step: '30s' },
  livePreview: { enabled: true, refreshInterval: '30s', defaultRange: '15m' },
};

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
  const [profiles, setProfiles] = useState<Array<Record<string, unknown>>>([]);
  const [sourceJson, setSourceJson] = useState(JSON.stringify(EMPTY_SUT_SOURCE, null, 2));
  const [profileJson, setProfileJson] = useState(JSON.stringify(EMPTY_SUT_PROFILE, null, 2));
  const [businessProfileJson, setBusinessProfileJson] = useState(JSON.stringify(EMPTY_BUSINESS_PROFILE, null, 2));
  const [mappedGroupId, setMappedGroupId] = useState('transaction-query');
  const [mappedCall, setMappedCall] = useState("#{db.orders.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}");
  const [mappedRowsPath, setMappedRowsPath] = useState('rows');
  const [mappedWorkloadColumn, setMappedWorkloadColumn] = useState('WORKLOAD_ID');
  const [mappedMixColumn, setMappedMixColumn] = useState('MIX_ID');
  const [mappedSampling, setMappedSampling] = useState<'afterDrain' | 'windowQuery'>('afterDrain');
  const [mappedToolAcknowledged, setMappedToolAcknowledged] = useState(false);
  const [mappedOutputs, setMappedOutputs] = useState([
    { id: 'success_transactions', title: 'Successful transactions', column: 'SUCCESS', unit: 'transactions', kind: 'counter' },
    { id: 'failed_transactions', title: 'Failed transactions', column: 'FAILURE', unit: 'transactions', kind: 'counter' },
  ]);
  const [bindingJson, setBindingJson] = useState(JSON.stringify(EMPTY_SUT_BINDING, null, 2));
  const [preview, setPreview] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function reload(signal?: AbortSignal) {
    const [sourceResponse, profileResponse] = await Promise.all([
      api.listSutMetricSources(signal), api.listSutMetricProfiles(signal),
    ]);
    setSources(sourceResponse.items || []); setProfiles(profileResponse.items || []);
  }
  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal).catch(error => {
      if (!controller.signal.aborted) setMessage(error instanceof Error ? error.message : 'SUT Metrics catalog is unavailable.');
    });
    return () => controller.abort();
  }, []);

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true); setMessage('');
    try { await action(); await reload(); setMessage(success); }
    catch (error) { setMessage(error instanceof Error ? error.message : 'SUT Metrics administration failed.'); }
    finally { setBusy(false); }
  }
  function parseEditor(text: string, label: string): Record<string, unknown> {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be a JSON object.`);
    return value as Record<string, unknown>;
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

  return <section className="panel att-settings-section att-sut-metrics-admin" aria-labelledby="sut-metrics-admin-title">
    <div className="att-settings-section-heading"><div><h2 id="sut-metrics-admin-title">SUT Metrics administration</h2>
      <p className="att-settings-help">Register approved Prometheus or Grafana sources and versioned metrics profiles. Profiles may define one DBHelper or Package Tool call group with multiple numeric outputs; Package Tools require sideEffectsAcknowledged: true. Validate and live preview never execute calls. Grafana uses one pinned Prometheus datasource UID through its fixed query endpoint and a service-account secret. HTTPS hosts must exactly match ATT_SUT_METRICS_ALLOWED_HOSTS; HTTP is limited to loopback. Secret values are read from ATT_SECRET_* environment variables and are never returned by the API.</p></div></div>
    {message && <p role="status" aria-live="polite">{message}</p>}
    <section aria-labelledby="sut-sources-title"><h3 id="sut-sources-title">SUT Metrics Data Sources</h3>
      <ul className="att-sut-catalog-list">{sources.map(source => <li key={String(source.id)}><strong>{String(source.name || source.id)}</strong> <code>{String(source.id)}</code>
        <button type="button" disabled={busy} onClick={() => setSourceJson(JSON.stringify(sourceForEditor(source), null, 2))}>Edit JSON</button>
        <button type="button" disabled={busy} onClick={() => void run(() => api.testSutMetricSource(String(source.id)), `Data Source ${String(source.id)} responded successfully.`)}>Test</button>
        <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricSource(String(source.id)), `Data Source ${String(source.id)} deleted.`)}>Delete</button>
      </li>)}</ul>
      <label>Data Source JSON<textarea value={sourceJson} onChange={event => setSourceJson(event.target.value)} rows={12} spellCheck={false} /></label>
      <p className="att-settings-help">Set <code>provider</code> to <code>GRAFANA</code> to pin <code>grafanaDatasourceUid</code>. Grafana organization IDs in <code>allowedTenants</code> and <code>defaultTenant</code> must be numeric. The adapter accepts only server-owned profiles and never accepts a browser query or proxy URL.</p>
      <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricSource(parseEditor(sourceJson, 'Data Source')), 'Data Source saved.')}>Save Data Source</button>
    </section>
    <section aria-labelledby="sut-profiles-title"><h3 id="sut-profiles-title">Metrics Profiles</h3>
      <ul className="att-sut-catalog-list">{profiles.map(profile => <li key={String(profile.id)}><strong>{String(profile.name || profile.id)}</strong> <code>{String(profile.id)}</code> · {String(profile.kind)} / {String(profile.category)}
        <button type="button" disabled={busy} onClick={() => {
          const metrics = Array.isArray(profile.metrics) ? profile.metrics as Array<Record<string, unknown>> : [];
          const businessOnly = metrics.length > 0 && metrics.every(metric => typeof metric.call === 'string' && typeof metric.query !== 'string');
          if (businessOnly) setBusinessProfileJson(JSON.stringify(profile, null, 2));
          else setProfileJson(JSON.stringify(profile, null, 2));
        }}>Edit JSON</button>
        <button type="button" disabled={busy} onClick={() => void run(() => api.deleteSutMetricProfile(String(profile.id)), `Metrics Profile ${String(profile.id)} deleted.`)}>Delete</button>
      </li>)}</ul>
      <fieldset className="att-sut-mapped-call-editor">
        <legend>{zh ? '多指標 business call 對照' : 'Multi-output business call mapping'}</legend>
        <p className="att-settings-help">{zh
          ? '一次執行 DBHelper query 或 Package Tool，將明確資料欄位對應至多個數值 metric。Workload 和 Mix 欄位必須使用 LOAD 中不可變的 workloadId／mixId；未知或重複的列會保留為診斷資料。'
          : 'Run one DBHelper query or Package Tool and map explicit result columns to numeric metrics. Workload and Mix columns must use immutable LOAD workloadId and mixId values; unknown or duplicate rows remain diagnostic. These mappings are drafted in a separate Business Metrics Profile so PromQL profiles stay query-only.'}</p>
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
      </fieldset>
      <label>Prometheus Metrics Profile JSON<textarea value={profileJson} onChange={event => setProfileJson(event.target.value)} rows={16} spellCheck={false} /></label>
      <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricProfile(parseEditor(profileJson, 'Metrics Profile')), 'Metrics Profile saved and dependency graph validated.')}>Save Metrics Profile</button>
      <label>Business Metrics Profile JSON<textarea value={businessProfileJson} onChange={event => setBusinessProfileJson(event.target.value)} rows={16} spellCheck={false} /></label>
      <button type="button" disabled={busy} onClick={() => void run(() => api.saveSutMetricProfile(parseEditor(businessProfileJson, 'Business Metrics Profile')), 'Business Metrics Profile saved and dependency graph validated.')}>Save Business Metrics Profile</button>
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
