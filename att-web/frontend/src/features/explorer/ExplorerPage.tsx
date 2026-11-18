import { useCallback, useContext, useEffect, useMemo, useRef, useState, type KeyboardEvent, type UIEvent } from 'react';
import { parseDocument } from 'yaml';
import { AppShellContext } from '../../AppShell';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import { ResourceTree, resourceKey } from './ResourceTree';
import { SourceViewer } from './SourceViewer';
import { ActionInspector } from './ActionInspector';
import { CodeEditor } from '../code/CodeEditor';
import { TypedSidecarForm } from './TypedSidecarForm';
import type { DebugDraftRequest, Diagnostic, DraftResponse, ExplorerApi, ExplorerPageProps, JsonObject, PackageSutMetricsCatalog,
  PackageSutMetricsDraft, PackageSutMetricsDraftAction, PackageSutMetricsResource, PackageSutMetricsResourceKind,
  PackageSutMetricsResourceDraftInput, ResourceIdentity, ResourceReference, ResourceSortField, ResourceSummary } from './types';

const MAX_DEFINITION_CHARACTERS = 100_000;
const RESOURCE_PAGE_SIZE = 50;
const EMPTY_PACKAGE_RESOURCE_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

function createdPackageResourceIdentityMatches(draft: PackageSutMetricsDraft): boolean {
  const candidateDigest = draft.candidateResourceDigest;
  if (typeof draft.recoveredExistingResource !== 'boolean'
    || typeof candidateDigest !== 'string' || !/^[a-f0-9]{64}$/i.test(candidateDigest)) return false;
  return draft.recoveredExistingResource
    ? draft.expectedResourceDigest !== EMPTY_PACKAGE_RESOURCE_SHA256 && draft.expectedResourceDigest === candidateDigest
    : draft.expectedResourceDigest === EMPTY_PACKAGE_RESOURCE_SHA256;
}

interface ExplorerListSnapshot {
  query: string;
  typeFilter: ResourceSummary['type'] | 'all';
  sortKey: ResourceSortField;
  sortDirection: 'asc' | 'desc';
  pagesLoaded: number;
  scrollTop: number;
}

const explorerListSnapshots = {
  get: (key: string) => inspectionCache.getView<ExplorerListSnapshot>('explorer:' + key),
  set: (key: string, value: ExplorerListSnapshot) => inspectionCache.setView('explorer:' + key, value),
};

function savedExplorerList(packageId: string): ExplorerListSnapshot {
  return explorerListSnapshots.get(packageId) || {
    query: '', typeFilter: 'all', sortKey: 'name', sortDirection: 'asc', pagesLoaded: 1, scrollTop: 0,
  };
}

type DetailTab = 'definition' | 'source' | 'relations';

function statusOf(error: unknown): number {
  return typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
}
function userError(error: unknown, action: string): string {
  const status = statusOf(error);
  if (status === 401) return `Your session has expired. Sign in again, then retry ${action}.`;
  if (status === 403) return `You do not have access to ${action}. Check your package permissions or Server configuration.`;
  if (status === 404) return `The selected resource or ${action} draft no longer exists. Refresh the resource list.`;
  if (status === 409) return `The package changed while ${action} was in progress. Refresh the resource and validate again.`;
  if (status === 413) return `The Server declined ${action} because the safe projection exceeded its configured limit.`;
  if (status === 429 || status === 503 || status === 504) return `${action} is temporarily unavailable. Wait briefly and retry.`;
  if (status >= 500) return `The Server could not complete ${action}. Retry or refresh the resource list.`;
  if (status) return `${action} failed (HTTP ${status}). Check the fields and access, then retry.`;
  return `${action} could not be completed. Check the connection and retry.`;
}
function isRunnable(resource?: ResourceSummary | null): boolean {
  return Boolean(resource && resource.state !== 'invalid' && ['template', 'flow', 'tool'].includes(resource.type));
}
function isResourceType(type: string): type is ResourceSummary['type'] {
  return ['case', 'template', 'flow', 'tool'].includes(type);
}
function safeSuiteTarget(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value || value.length > 2048 || value.startsWith('/') || value.includes('\\')
    || /^[a-z][a-z\d+.-]*:/i.test(value) || /[\u0000-\u001f\u007f]/.test(value)) return undefined;
  const parts = value.split('/');
  return parts.some(part => !part || part === '.' || part === '..') ? undefined : value;
}
function debugGroups(type?: string) {
  return type === 'tool'
    ? [{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }]
    : [{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }];
}
function safeProjection(value: unknown): string {
  let result = '';
  try { result = JSON.stringify(value ?? {}, null, 2); } catch { result = '{}'; }
  return result.length > MAX_DEFINITION_CHARACTERS
    ? `${result.slice(0, MAX_DEFINITION_CHARACTERS)}\n\n[Definition truncated at ${MAX_DEFINITION_CHARACTERS.toLocaleString()} characters.]`
    : result;
}
function resourceRouteKey(route: ExplorerPageProps['resourceRoute']): string {
  return route ? `${route.type}:${route.resourceId}` : '';
}

function ExplorerReference({ reference, onOpen, onOpenFile, sourceIdentity }: {
  reference: ResourceReference;
  onOpen: ExplorerPageProps['onResourceRouteChange'];
  onOpenFile?: ExplorerPageProps['onOpenConfigurationFile'];
  sourceIdentity?: ResourceIdentity | null;
}) {
  const label = reference.logicalId || reference.name || reference.resourceId || 'Unresolved reference';
  if (reference.type === 'file') {
    const calls = reference.callSites || (reference.callSite ? [reference.callSite] : []);
    const actionIds = calls.map(site => typeof site.actionId === 'string' ? site.actionId : '').filter(Boolean);
    const path = typeof reference.logicalId === 'string' ? reference.logicalId : '';
    const originPath = typeof reference.originPath === 'string' ? reference.originPath : '';
    const referenceId = typeof reference.referenceId === 'string' ? reference.referenceId : '';
    const canOpen = reference.available === true && Boolean(path && originPath && /^[0-9a-f]{64}$/.test(referenceId)
      && sourceIdentity && (sourceIdentity.type === 'template' || sourceIdentity.type === 'flow') && onOpenFile);
    const message = reference.reason === 'missing' ? 'Missing file.'
      : reference.reason === 'invalid' || reference.reason === 'invalid-reference' ? 'Invalid or out-of-Package reference.'
        : reference.available === false ? `Blocked or unavailable (${reference.reason || reference.resolution || 'Server policy'}).` : '';
    return <li>
      {canOpen
        ? <button type="button" onClick={() => onOpenFile!(path, originPath, {
          type: sourceIdentity!.type as 'template' | 'flow', resourceId: sourceIdentity!.resourceId, referenceId, returnTab: 'relations',
        })}>{path}</button>
        : <span>{path || 'Package file reference'}</span>}
      <span>{reference.resolution || 'unresolved'}</span>
      {actionIds.length > 0 && <span>Actions: {actionIds.join(', ')}</span>}
      {message && <span role="status">{message}</span>}
    </li>;
  }
  if (reference.targetKind === 'helper' && reference.resolution === 'resolved') {
    const callSite = reference.callSite && typeof reference.callSite.actionId === 'string' ? reference.callSite.actionId : '';
    return <li><details className="att-helper-reference"><summary>{label}<span>Configured Helper method</span></summary>
      <p>This call resolves through the package Helper configuration. It is not a separate Tool resource and cannot be opened as a Tool.</p>
      {callSite && <p>Called from action {callSite}.</p>}
    </details></li>;
  }
  const referenceType = reference.type;
  const valid = Boolean(referenceType && ['case', 'template', 'flow', 'tool'].includes(referenceType) && reference.resourceId && reference.resolution !== 'unresolved');
  return <li>{valid ? <button type="button" onClick={() => onOpen({ type: referenceType!, resourceId: reference.resourceId!, tab: 'definition' })}>{label}</button> : <span>{label}</span>}
    <span>{reference.resolution === 'unresolved' ? 'Unresolved reference' : reference.type || reference.resolution || 'unresolved'}</span></li>;
}

function catalogRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function catalogText(value: unknown, fallback = ''): string {
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, 256) : fallback;
}

function packageRelativeCatalogPath(value: unknown): string {
  if (typeof value !== 'string' || !value || value.length > 1024 || value.startsWith('/') || value.includes('\\')
    || /^[a-z][a-z\d+.-]*:/i.test(value) || /[\u0000-\u001f\u007f]/.test(value)) return '—';
  const parts = value.split('/');
  return parts.some(part => !part || part === '.' || part === '..') ? '—' : value;
}

function packageCatalogResourceProvenance(value: unknown, index: number, zh: boolean) {
  const item = catalogRecord(value);
  const kindLabels: Record<string, string> = zh
    ? { metricSource: '指標來源', group: '指標群組', profile: '指標設定檔', 'package-catalog': 'Package Manifest' }
    : { metricSource: 'Metric Source', group: 'Metric Group', profile: 'Metrics Profile', 'package-catalog': 'Package Manifest' };
  const kind = typeof item.kind === 'string' ? kindLabels[item.kind] || (zh ? `資源 ${index + 1}` : `Resource ${index + 1}`)
    : (zh ? `資源 ${index + 1}` : `Resource ${index + 1}`);
  const rawId = catalogText(item.id);
  const id = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/.test(rawId) ? rawId : '—';
  const path = packageRelativeCatalogPath(item.path);
  const digest = typeof item.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(item.sha256) ? item.sha256 : '—';
  return { kind, id, path, digest };
}

function packageCatalogLabel(value: unknown, index: number): { id: string; name: string; kind: string; connection: string } {
  const item = catalogRecord(value);
  const id = catalogText(item.id || item.logicalId, `Definition ${index + 1}`);
  const name = catalogText(item.name, id);
  const kind = catalogText(item.kind || item.type);
  const connection = catalogRecord(item.connection);
  const connectionKind = catalogText(connection.kind);
  const connectionId = catalogText(connection.id);
  return { id, name, kind, connection: connectionKind && connectionId ? `${connectionKind} · ${connectionId}` : '' };
}

function packageSutMetricsResourceLabel(kind: PackageSutMetricsResourceKind, zh: boolean): string {
  if (zh) return kind === 'source' ? '指標來源' : kind === 'group' ? '指標群組' : '指標設定檔';
  return kind === 'source' ? 'Metric Source' : kind === 'group' ? 'Metric Group' : 'Metrics Profile';
}

function packageSutMetricsError(error: unknown, zh: boolean): string {
  const status = error && typeof error === 'object' && 'status' in error ? Number(error.status) : 0;
  if (status === 401) return zh ? '登入工作階段已過期，請重新登入後再試。' : 'Your session expired. Sign in again before continuing.';
  if (status === 403) return zh ? '目前帳戶沒有此 Package 資源的檢視或編輯權限。' : 'Your account cannot view or edit this Package resource.';
  if (status === 404) return zh ? 'Package 資源或草稿已不存在。請重新載入目錄。' : 'The Package resource or draft is unavailable. Reload the catalog.';
  if (status === 409) return zh ? 'Package 資源已變更。請捨棄草稿並重新載入資源。' : 'The Package resource changed. Discard the draft and reload the resource.';
  if (status === 400 || status === 422)
    return zh ? '伺服器拒絕此草稿操作。請檢查下方驗證診斷，捨棄草稿後再修正來源。' : 'The Server rejected this draft action. Review the validation details below, discard the draft, and edit the source.';
  if (status === 429 || status === 503 || status === 504)
    return zh ? 'Package SUT Metrics 暫時無法使用，請稍後重試。' : 'Package SUT Metrics is temporarily unavailable. Retry shortly.';
  return zh ? 'Package SUT Metrics 請求失敗。請重新載入後再試。' : 'The Package SUT Metrics request failed. Reload and retry.';
}

function safePackageDiagnosticText(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 2048) return '';
  return value.trim().replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\b(password|passwd|secret|token|api[_-]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, '$1=[REDACTED]')
    .replace(/\b([a-z][a-z\d+.-]*:\/\/)[^/@\s:]+:[^/@\s]+@/gi, '$1[REDACTED]@')
    .replace(/(?:\/Users\/[^\s,;]+|\/home\/[^\s,;]+|[A-Za-z]:\\[^\s,;]+)/g, '[REDACTED_PATH]')
    .slice(0, 512);
}

function packageSutMetricsDiagnostic(value: unknown, index: number): { key: string; text: string } {
  const item = catalogRecord(value);
  const code = safePackageDiagnosticText(item.code);
  const text = safePackageDiagnosticText(item.summary || item.detail || item.message) || `Validation finding ${index + 1}`;
  const rawField = safePackageDiagnosticText(item.field);
  const rawPath = item.path === undefined ? '' : packageRelativeCatalogPath(item.path);
  const field = rawField || (rawPath && rawPath !== '—' ? rawPath : '');
  return { key: `${code}:${field}:${index}`, text: `${code ? `${code}: ` : ''}${field ? `${field} · ` : ''}${text}` };
}

function packageSutMetricsFailureDiagnostics(error: unknown): unknown[] {
  const item = catalogRecord(error);
  const diagnostics = Array.isArray(item.diagnostics) ? item.diagnostics.slice(0, 64) : [];
  const detail = safePackageDiagnosticText(item.details) || safePackageDiagnosticText(item.message);
  if (detail && !diagnostics.some(value => {
    const diagnostic = catalogRecord(value);
    return safePackageDiagnosticText(diagnostic.summary || diagnostic.detail || diagnostic.message) === detail;
  })) diagnostics.unshift({ code: item.code, summary: detail });
  return diagnostics;
}

function packageResourceInventoryKey(kind: PackageSutMetricsResourceKind): 'metricSources' | 'groups' | 'profiles' {
  return kind === 'source' ? 'metricSources' : kind === 'group' ? 'groups' : 'profiles';
}

function packageSutMetricsStarter(kind: PackageSutMetricsResourceKind, id: string): string {
  const lines = kind === 'source' ? [
    'schemaVersion: att-sut-metrics-metric-source/v1', `id: ${id}`, 'name: New Metric Source',
    'connection:', '  kind: DATA_SOURCE', '  id: replace_data_source', 'fetch:', '  id: default-fetch',
    '  mode: prometheus-range', '  query: replace_metric_query', '  parameters: {}', '  sampling: range', '  step: 30s',
    'mapping:', '  outputs:', '    - id: replace_metric', '      title: Replace metric', '      unit: count', '      kind: gauge',
    'limits:', '  maxRows: 1024', '  maxSeries: 128', '  maxSamples: 250000', '  maxArtifactBytes: 8388608',
  ] : kind === 'group' ? [
    'schemaVersion: att-sut-metrics-group/v1', `id: ${id}`, 'title: New Metric Group', 'order: 10', 'match:',
    '  metricIds:', '    - replace_metric', 'groupBy: []', 'display: auto',
  ] : [
    'schemaVersion: att-sut-metrics-profile/v2', `id: ${id}`, 'name: New Metrics Profile', 'metricSourceIds:',
    '  - replace_metric_source', 'groupIds: []', 'allowedBindings:', '  environments: []', '  targets: []', '  tenants: []', '  parameters: {}',
  ];
  return lines.join('\n') + '\n';
}

function normalizedManifestValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizedManifestValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => [key, normalizedManifestValue(item)]));
  }
  return value;
}

function candidatePackageManifest(source: string, kind: PackageSutMetricsResourceKind, id: string, zh: boolean): { path: string; source: string } {
  const fail = (english: string, chinese: string): never => { throw new Error(zh ? chinese : english); };
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(id))
    fail('Use a resource ID with letters, numbers, dots, underscores, or hyphens (up to 80 characters).', '資源 ID 只能包含英文字母、數字、句點、底線或連字號，長度最多 80 個字元。');
  if (new TextEncoder().encode(source).length > 262_144) fail('The Package Manifest exceeds the source size limit.', 'Package Manifest 超出來源大小限制。');
  let document;
  try { document = parseDocument(source, { uniqueKeys: true, strict: true }); }
  catch { return fail('The Package Manifest source could not be parsed safely.', '無法安全解析 Package Manifest 來源。'); }
  if (document.errors.length > 0) fail('The Package Manifest source is invalid YAML.', 'Package Manifest 來源不是有效的 YAML。');
  let parsedManifest: unknown;
  try { parsedManifest = document.toJS({ maxAliasCount: 32 }); }
  catch { return fail('The Package Manifest source could not be parsed safely.', '無法安全解析 Package Manifest 來源。'); }
  const root = catalogRecord(parsedManifest);
  if (root.schemaVersion !== 'att-package/v2')
    fail('Creating Package SUT Metrics resources requires migrating the Package inventory to att-package/v2 first.', '建立 Package SUT Metrics 資源前，必須先把 Package inventory 遷移至 att-package/v2。');
  const pathKind = kind === 'source' ? 'sources' : kind === 'group' ? 'groups' : 'profiles';
  const path = `load/sut-metrics/${pathKind}/${id}.yaml`;
  let observability = root.observability;
  if (observability === undefined) {
    document.setIn(['observability'], {});
    observability = {};
  }
  if (!observability || typeof observability !== 'object' || Array.isArray(observability))
    fail('The Package SUT Metrics inventory has an invalid structure.', 'Package SUT Metrics inventory 結構無效。');
  const inventory = observability as Record<string, unknown>;
  const keys = ['metricSources', 'groups', 'profiles'] as const;
  for (const key of keys) {
    const entries = inventory[key];
    if (entries !== undefined && (!Array.isArray(entries) || entries.some(item => typeof item !== 'string')))
      fail('The Package SUT Metrics inventory has an invalid structure.', 'Package SUT Metrics inventory 結構無效。');
    if (Array.isArray(entries) && entries.includes(path))
      fail('This resource path is already listed in the Package inventory.', '此資源路徑已列在 Package inventory 中。');
  }
  const targetKey = packageResourceInventoryKey(kind);
  const targetEntries = Array.isArray(inventory[targetKey]) ? inventory[targetKey] as string[] : [];
  document.setIn(['observability', targetKey], [...targetEntries, path]);
  const candidate = document.toString({ lineWidth: 100 });
  let candidateDocument;
  try { candidateDocument = parseDocument(candidate, { uniqueKeys: true, strict: true }); }
  catch { return fail('The candidate Package Manifest could not be verified.', '無法驗證候選 Package Manifest。'); }
  if (candidateDocument.errors.length > 0) fail('The candidate Package Manifest could not be verified.', '無法驗證候選 Package Manifest。');
  let expected: Record<string, unknown>;
  try { expected = JSON.parse(JSON.stringify(root)) as Record<string, unknown>; }
  catch { return fail('The Package Manifest contains unsupported values.', 'Package Manifest 含有不支援的值。'); }
  const expectedObservability = catalogRecord(expected.observability);
  expected.observability = expectedObservability;
  expectedObservability[targetKey] = [...targetEntries, path];
  let parsedCandidate: unknown;
  try { parsedCandidate = candidateDocument.toJS({ maxAliasCount: 32 }); }
  catch { return fail('The candidate Package Manifest could not be verified.', '無法驗證候選 Package Manifest。'); }
  if (JSON.stringify(normalizedManifestValue(parsedCandidate)) !== JSON.stringify(normalizedManifestValue(expected)))
    fail('The candidate Package Manifest changes more than one inventory path.', '候選 Package Manifest 除新增一個 inventory 路徑外，還包含其他變更。');
  if (new TextEncoder().encode(candidate).length > 262_144)
    fail('The candidate Package Manifest exceeds the source size limit.', '候選 Package Manifest 超出來源大小限制。');
  return { path, source: candidate };
}

interface NewPackageSutMetricsResource extends PackageSutMetricsResource {
  candidateManifestSource: string;
  expectedManifestDigest: string;
}

function PackageSutMetricsPanel({ api, packageId, environment, refreshKey, locale, registerUnsavedChanges }: {
  api: ExplorerApi;
  packageId: string;
  environment: string;
  refreshKey: number;
  locale: string;
  registerUnsavedChanges: ExplorerPageProps['registerUnsavedChanges'];
}) {
  const zh = locale === 'zh-Hant';
  const [catalog, setCatalog] = useState<PackageSutMetricsCatalog | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [requestVersion, setRequestVersion] = useState(0);
  const [selection, setSelection] = useState<{ kind: PackageSutMetricsResourceKind; id: string } | null>(null);
  const [resource, setResource] = useState<PackageSutMetricsResource | null>(null);
  const [resourceSource, setResourceSource] = useState('');
  const [baselineSource, setBaselineSource] = useState('');
  const [newResource, setNewResource] = useState<NewPackageSutMetricsResource | null>(null);
  const [newResourceSource, setNewResourceSource] = useState('');
  const [newResourceKind, setNewResourceKind] = useState<PackageSutMetricsResourceKind>('source');
  const [newResourceId, setNewResourceId] = useState('');
  const [newResourceBusy, setNewResourceBusy] = useState(false);
  const [newResourceError, setNewResourceError] = useState('');
  const [resourceLoading, setResourceLoading] = useState(false);
  const [resourceError, setResourceError] = useState('');
  const [resourceVersion, setResourceVersion] = useState(0);
  const [draft, setDraft] = useState<PackageSutMetricsDraft | null>(null);
  const [draftBusy, setDraftBusy] = useState(false);
  const [draftError, setDraftError] = useState('');
  const [draftFailureDiagnostics, setDraftFailureDiagnostics] = useState<unknown[]>([]);
  const [draftMessage, setDraftMessage] = useState('');
  const [stale, setStale] = useState(false);
  const activeResource = newResource || resource;
  const activeSelection = newResource ? { kind: newResource.kind, id: newResource.id } : selection;
  const activeSource = newResource ? newResourceSource : resourceSource;
  const sourceDirty = !!newResource || (!!resource && resourceSource !== baselineSource);
  const hasUnpublishedChanges = sourceDirty || !!draft;

  useEffect(() => registerUnsavedChanges(hasUnpublishedChanges, 'unpublished Package SUT Metrics changes'),
    [hasUnpublishedChanges, registerUnsavedChanges]);

  useEffect(() => {
    const load = api.getPackageSutMetricsCatalog;
    if (!load) {
      setCatalog(null);
      setError(zh ? '此 Server client 不支援 Package SUT Metrics 目錄。' : 'This Server client does not support the Package SUT Metrics catalog.');
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError('');
    load(packageId, environment || undefined, controller.signal).then(value => {
      if (!controller.signal.aborted) setCatalog(value);
    }).catch(() => {
      if (!controller.signal.aborted) {
        setCatalog(null);
        setError(zh ? '無法載入 Package SUT Metrics 目錄。請重試。' : 'The Package SUT Metrics catalog could not be loaded. Retry.');
      }
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [api, packageId, environment, refreshKey, requestVersion, zh]);

  useEffect(() => {
    setSelection(null); setResource(null); setResourceSource(''); setBaselineSource(''); setNewResource(null);
    setNewResourceSource(''); setNewResourceError(''); setDraft(null);
    setResourceError(''); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage(''); setStale(false);
  }, [packageId, environment]);

  useEffect(() => {
    if (!selection) {
      setResource(null); setResourceSource(''); setBaselineSource(''); setResourceLoading(false);
      return;
    }
    const load = api.getPackageSutMetricsResource;
    if (!load) {
      setResource(null); setResourceError(zh ? '此 Server client 不支援載入 Package 資源來源。' : 'This Server client does not support loading Package resource source.');
      setResourceLoading(false);
      return;
    }
    const controller = new AbortController();
    setResource(null); setResourceSource(''); setBaselineSource(''); setResourceLoading(true); setResourceError('');
    setDraft(null); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage(''); setStale(false);
    load(packageId, selection.kind, selection.id, controller.signal).then(value => {
      if (controller.signal.aborted) return;
      const digestValid = typeof value.resourceDigest === 'string' && /^[a-f0-9]{64}$/i.test(value.resourceDigest);
      const path = packageRelativeCatalogPath(value.path);
      if (value.packageId !== packageId || value.kind !== selection.kind || value.id !== selection.id
        || path === '—' || !digestValid || typeof value.source !== 'string' || typeof value.canEdit !== 'boolean') {
        setResourceError(zh ? '伺服器傳回的 Package 資源來源資料不完整。請重新載入目錄。' : 'The Server returned incomplete Package resource source metadata. Reload the catalog.');
        return;
      }
      const normalized = { ...value, path };
      setResource(normalized); setResourceSource(value.source); setBaselineSource(value.source);
    }).catch(failure => {
      if (!controller.signal.aborted) {
        setResource(null);
        setResourceError(packageSutMetricsError(failure, zh));
      }
    }).finally(() => { if (!controller.signal.aborted) setResourceLoading(false); });
    return () => controller.abort();
  }, [api, packageId, selection?.kind, selection?.id, resourceVersion, zh]);

  const sources = Array.isArray(catalog?.metricSources) ? catalog.metricSources.slice(0, 128) : [];
  const groups = Array.isArray(catalog?.groups) ? catalog.groups.slice(0, 128) : [];
  const profiles = Array.isArray(catalog?.profiles) ? catalog.profiles.slice(0, 128) : [];
  const resourceProvenance = Array.isArray(catalog?.resourceProvenance) ? catalog.resourceProvenance.slice(0, 128) : [];
  const total = sources.length + groups.length + profiles.length;
  const returnedEnvironment = catalogText(catalog?.environment, environment || (zh ? '預設環境' : 'Default environment'));
  const revisionLabel = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value) ? value : '—';
  const catalogCanEdit = !loading && catalog?.canEdit === true;
  const resourceEditable = catalogCanEdit && activeResource?.canEdit === true;
  const resourceCanEdit = resourceEditable && !stale;
  const editorWritable = resourceCanEdit && !draft && !draftBusy && !resourceLoading;
  const selectionLocked = sourceDirty || !!draft || draftBusy || newResourceBusy;
  const selectedEnvironment = catalogText(catalog?.environment, environment);
  const resourceLabel = activeSelection ? packageSutMetricsResourceLabel(activeSelection.kind, zh) : '';
  const validationDiagnostics = Array.isArray(draft?.validation?.diagnostics) ? draft.validation.diagnostics.slice(0, 64) : [];

  const selectResource = (kind: PackageSutMetricsResourceKind, id: string) => {
    if (selectionLocked) return;
    setNewResource(null); setNewResourceSource(''); setNewResourceError('');
    setSelection({ kind, id }); setResource(null); setResourceSource(''); setBaselineSource(''); setDraft(null);
    setResourceError(''); setDraftError(''); setDraftMessage(''); setStale(false);
  };

  const prepareNewResource = async () => {
    const id = newResourceId.trim();
    if (!catalogCanEdit || !api.getPackageManifest || newResourceBusy || selectionLocked
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(id)) return;
    const inventory = newResourceKind === 'source' ? sources : newResourceKind === 'group' ? groups : profiles;
    if (inventory.some((item, index) => packageCatalogLabel(item, index).id === id)) {
      setNewResourceError(zh ? '此 ID 已存在於 Package inventory。' : 'This ID already exists in the Package inventory.');
      return;
    }
    const controller = new AbortController();
    setNewResourceBusy(true); setNewResourceError(''); setDraftError(''); setDraftFailureDiagnostics([]);
    try {
      const manifest = await api.getPackageManifest(packageId, 'source', undefined, controller.signal);
      if (manifest.packageId !== packageId || manifest.canEdit !== true) {
        setNewResourceError(zh ? '建立資源需要此已註冊 Package 的來源編輯權限。' : 'Creating a resource requires source editing access for this registered Package.');
        return;
      }
      if (!manifest.present || manifest.redacted || manifest.available !== true || typeof manifest.text !== 'string') {
        setNewResourceError(zh
          ? '建立資源前，請先提供可編輯的 att-package/v2 Manifest；缺少或受遮罩的來源需要先完成 Package inventory 遷移。'
          : 'Provide an editable att-package/v2 Manifest before creating resources. A missing or redacted source requires Package inventory migration first.');
        return;
      }
      if (!/^[a-f0-9]{64}$/i.test(manifest.manifestDigest)) {
        setNewResourceError(zh ? 'Package Manifest revision 無效；請重新載入後再試。' : 'The Package Manifest revision is invalid. Reload and retry.');
        return;
      }
      if (typeof catalog?.manifestDigest !== 'string' || !/^[a-f0-9]{64}$/i.test(catalog.manifestDigest)
        || manifest.manifestDigest !== catalog.manifestDigest) {
        setNewResourceError(zh ? 'Package Manifest 已在目錄載入後變更。請重新載入目錄再試。' : 'The Package Manifest changed after the catalog loaded. Reload the catalog and retry.');
        return;
      }
      const candidate = candidatePackageManifest(manifest.text, newResourceKind, id, zh);
      const starter = packageSutMetricsStarter(newResourceKind, id);
      setSelection(null); setResource(null); setResourceSource(''); setBaselineSource('');
      setDraft(null); setStale(false); setResourceError(''); setDraftError(''); setDraftMessage('');
      setNewResource({
        packageId, kind: newResourceKind, id, path: candidate.path, resourceDigest: EMPTY_PACKAGE_RESOURCE_SHA256,
        source: starter, canEdit: true, candidateManifestSource: candidate.source, expectedManifestDigest: manifest.manifestDigest,
      });
      setNewResourceSource(starter);
    } catch (failure) {
      const message = failure instanceof Error ? failure.message : packageSutMetricsError(failure, zh);
      setNewResourceError(message.slice(0, 512));
    } finally { setNewResourceBusy(false); }
  };

  const cancelNewResource = () => {
    if (draftBusy) return;
    setNewResource(null); setNewResourceSource(''); setNewResourceError('');
    setDraft(null); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage(''); setStale(false);
  };

  const updateActiveSource = (value: string) => {
    if (newResource) setNewResourceSource(value);
    else setResourceSource(value);
  };

  const createDraft = async () => {
    if (!activeResource || !editorWritable || !sourceDirty || !selectedEnvironment
      || !/^[a-f0-9]{64}$/i.test(activeResource.resourceDigest) || !api.createPackageSutMetricsDraft) return;
    if (new TextEncoder().encode(activeSource).length > 262_144) {
      setDraftError(zh ? 'SUT Metrics 資源超出來源大小限制。' : 'The SUT Metrics resource exceeds the source size limit.');
      return;
    }
    const controller = new AbortController();
    setDraftBusy(true); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage('');
    try {
      const input: PackageSutMetricsResourceDraftInput = newResource ? {
        resourcePath: activeResource.path, environment: selectedEnvironment, source: activeSource,
        expectedResourceDigest: activeResource.resourceDigest,
        candidateManifestSource: newResource.candidateManifestSource, expectedManifestDigest: newResource.expectedManifestDigest,
      } : {
        resourcePath: activeResource.path, environment: selectedEnvironment, source: activeSource,
        expectedResourceDigest: activeResource.resourceDigest,
      };
      const next = await api.createPackageSutMetricsDraft(packageId, input, controller.signal);
      const newManifestMatches = !newResource || (next.expectedManifestDigest === newResource.expectedManifestDigest
        && typeof next.candidateManifestDigest === 'string' && /^[a-f0-9]{64}$/i.test(next.candidateManifestDigest)
        && next.activationMode === 'resource-then-manifest');
      const resourceRevisionMatches = newResource
        ? createdPackageResourceIdentityMatches(next)
        : next.expectedResourceDigest === activeResource.resourceDigest;
      if (next.packageId !== packageId || next.resourcePath !== activeResource.path
        || !resourceRevisionMatches || !next.draftId || next.state !== 'draft' || !newManifestMatches) {
        setStale(true);
        if (next.packageId === packageId && next.draftId) setDraft(next);
        setDraftError(zh ? '伺服器建立的草稿與目前資源不符，操作已停止。' : 'The Server draft does not match the selected resource. The action was stopped.');
        return;
      }
      setDraft(next);
      setDraftMessage(zh ? '草稿已建立；下一步請先驗證。' : 'Draft created. Validate it before review.');
    } catch (failure) {
      if (failure && typeof failure === 'object' && 'status' in failure && Number(failure.status) === 409) setStale(true);
      setDraftError(packageSutMetricsError(failure, zh));
      setDraftFailureDiagnostics(packageSutMetricsFailureDiagnostics(failure));
    } finally { setDraftBusy(false); }
  };

  const actOnDraft = async (action: PackageSutMetricsDraftAction) => {
    if (!draft || !api.packageSutMetricsDraftAction || draftBusy || (stale && action !== 'discard')) return;
    const state = draft.state.toLowerCase();
    if (action === 'validate' && state !== 'draft') return;
    if (action === 'review' && (state !== 'validated' || draft.validation?.valid !== true)) return;
    if (action === 'publish' && (state !== 'reviewed' || !resourceCanEdit || catalog?.canEdit !== true)) return;
    const controller = new AbortController();
    setDraftBusy(true); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage('');
    try {
      const next = await api.packageSutMetricsDraftAction(packageId, draft.draftId, action, controller.signal);
      if (controller.signal.aborted) return;
      if (action === 'publish') {
        const publishedResource = activeResource;
        const manifestIdentityMatches = !newResource || (draft.expectedManifestDigest === newResource.expectedManifestDigest
          && draft.activationMode === 'resource-then-manifest' && typeof draft.candidateManifestDigest === 'string'
          && /^[a-f0-9]{64}$/i.test(draft.candidateManifestDigest)
          && next.activationMode === 'resource-then-manifest'
          && next.expectedManifestDigest === newResource.expectedManifestDigest
          && next.manifestDigest === draft.candidateManifestDigest
          && next.candidateManifestDigest === draft.candidateManifestDigest
          && next.beforeResourceDigest === draft.expectedResourceDigest
          && createdPackageResourceIdentityMatches(draft)
          && next.recoveredExistingResource === draft.recoveredExistingResource
          && next.candidateResourceDigest === draft.candidateResourceDigest
          && next.resourceDigest === draft.candidateResourceDigest
          && draft.validation?.resourceDigest === draft.candidateResourceDigest);
        const validatedResourceMatches = typeof draft.validation?.resourceDigest === 'string'
          && /^[a-f0-9]{64}$/i.test(draft.validation.resourceDigest)
          && draft.validation.resourceDigest === next.resourceDigest;
        const configurationIdentity = safePackageDiagnosticText(next.configurationIdentity);
        if (next.published !== true || !publishedResource || next.packageId !== packageId
          || next.resourcePath !== publishedResource.path || next.refreshRequired !== true
          || typeof next.resourceDigest !== 'string' || !/^[a-f0-9]{64}$/i.test(next.resourceDigest)
          || next.environment !== selectedEnvironment || !configurationIdentity
          || !manifestIdentityMatches || !validatedResourceMatches) {
          setStale(true);
          setDraftError(zh ? '伺服器尚未確認發布；草稿仍保留供檢查。' : 'The Server did not confirm publication. The draft remains available for review.');
          return;
        }
        setDraft(null); setStale(false);
        if (newResource) {
          setSelection({ kind: newResource.kind, id: newResource.id });
          setNewResource(null); setNewResourceSource('');
        }
        setDraftMessage(zh
          ? `Package SUT Metrics 資源已發布（${next.environment}；設定身分 ${configurationIdentity}）；正在重新載入資源與目錄。`
          : `Package SUT Metrics resource published (${next.environment}; configuration identity ${configurationIdentity}). Reloading the resource and catalog.`);
        setRequestVersion(value => value + 1); setResourceVersion(value => value + 1);
        return;
      }
      if (action === 'discard') {
        setDraft(null); setDraftError(''); setDraftFailureDiagnostics([]); setDraftMessage(zh ? '草稿已捨棄；來源變更已保留，可繼續編輯。' : 'Draft discarded. Source edits were preserved and can be continued.');
        if (stale) { setStale(false); setRequestVersion(value => value + 1); setResourceVersion(value => value + 1); }
        return;
      }
      const expectedState = action === 'validate' ? 'validated' : 'reviewed';
      const manifestActionMatches = !newResource || (next.expectedManifestDigest === newResource.expectedManifestDigest
        && next.candidateManifestDigest === draft.candidateManifestDigest && next.activationMode === 'resource-then-manifest'
        && createdPackageResourceIdentityMatches(next)
        && next.recoveredExistingResource === draft.recoveredExistingResource
        && next.candidateResourceDigest === draft.candidateResourceDigest
        && (action !== 'review' || draft.validation?.resourceDigest === draft.candidateResourceDigest));
      if (next.draftId !== draft.draftId || next.packageId !== packageId || next.resourcePath !== activeResource?.path
        || next.expectedResourceDigest !== draft.expectedResourceDigest || next.state.toLowerCase() !== expectedState
        || !manifestActionMatches) {
        setStale(true);
        setDraftError(zh ? '伺服器草稿狀態與預期不符，操作已停止。' : 'The Server draft state did not match the expected transition. The action was stopped.');
        return;
      }
      if (action === 'validate' && next.validation?.valid === false) {
        setDraft({ ...draft, ...next });
        setDraftMessage(zh ? '驗證未通過；檢查診斷並捨棄草稿以修正來源。' : 'Validation failed. Review the diagnostics and discard the draft to edit the source.');
        return;
      }
      if (action === 'validate' && (next.validation?.valid !== true
        || typeof next.validation.resourceDigest !== 'string' || !/^[a-f0-9]{64}$/i.test(next.validation.resourceDigest)
        || typeof next.validation.validationRevision !== 'string' || !/^[a-f0-9]{64}$/i.test(next.validation.validationRevision)
        || (newResource && next.validation.resourceDigest !== draft.candidateResourceDigest)
        || (newResource && next.validation.candidateManifestDigest !== draft.candidateManifestDigest))) {
        setStale(true);
        setDraft({ ...draft, ...next });
        setDraftError(zh ? '伺服器未提供完整的草稿驗證 revision，操作已停止。' : 'The Server did not return complete draft validation revisions. The action was stopped.');
        return;
      }
      setDraft({ ...draft, ...next });
      setDraftMessage(action === 'validate'
        ? (next.validation?.valid === true ? (zh ? '草稿驗證通過。' : 'Draft validation passed.') : (zh ? '驗證完成；請檢查診斷並修正來源。' : 'Validation completed. Review the diagnostics and update the source.'))
        : (zh ? '草稿已完成 Review，可發布。' : 'Draft review is complete and it can be published.'));
    } catch (failure) {
      if (failure && typeof failure === 'object' && 'status' in failure && Number(failure.status) === 409) setStale(true);
      setDraftError(packageSutMetricsError(failure, zh));
      setDraftFailureDiagnostics(packageSutMetricsFailureDiagnostics(failure));
    } finally { setDraftBusy(false); }
  };

  return <details className="att-query-filter-panel att-package-sut-metrics" aria-label={zh ? 'Package SUT Metrics 目錄' : 'Package SUT Metrics catalog'}>
    <summary>{zh ? 'Package SUT Metrics' : 'Package SUT Metrics'}{loading ? (zh ? ' · 載入中…' : ' · Loading…') : total ? ` · ${total}` : ''}</summary>
    <section aria-label={zh ? 'Package SUT Metrics 目錄內容' : 'Package SUT Metrics catalog contents'}>
      <p>{zh
        ? `環境：${returnedEnvironment}。此處只顯示 Package inventory 明確列出的定義；本頁不會查詢 Metrics 來源。`
        : `Environment: ${returnedEnvironment}. Only definitions explicitly listed in this Package inventory appear here. This view does not query metric sources.`}</p>
      {loading && <p role="status" aria-busy="true">{zh ? '正在載入 Package inventory…' : 'Loading Package inventory…'}</p>}
      {error && <p role="alert">{error} <button type="button" onClick={() => setRequestVersion(value => value + 1)}>{zh ? '重試' : 'Retry'}</button></p>}
      {!loading && !error && catalog && total === 0 && <p role="status">{zh
        ? '此 Package 沒有明確列出 SUT Metrics 定義。'
        : 'This Package has no explicitly listed SUT Metrics definitions.'}</p>}
      {!loading && !error && catalog && total > 0 && <div className="att-relation-columns att-package-sut-metrics-groups">
        {([[zh ? '指標來源' : 'Metric Sources', 'source' as const, sources], [zh ? '邏輯群組' : 'Groups', 'group' as const, groups], [zh ? '設定檔' : 'Profiles', 'profile' as const, profiles]] as Array<[string, PackageSutMetricsResourceKind, unknown[]]>).map(([title, kind, values]) => (
          <section className="att-source-file-references" key={title} aria-label={title}>
            <h3>{title} <span>({values.length})</span></h3>
            {values.length === 0 ? <p>{zh ? '無' : 'None'}</p> : <ul>{values.map((value, index) => {
              const item = packageCatalogLabel(value, index);
              return <li key={`${item.id}:${index}`}>
                {catalogCanEdit ? <button type="button" aria-label={`${zh ? '檢視' : 'View'} ${packageSutMetricsResourceLabel(kind, zh)} ${item.id}`}
                  aria-pressed={selection?.kind === kind && selection.id === item.id}
                  disabled={selectionLocked || resourceLoading || draftBusy}
                  onClick={() => selectResource(kind, item.id)}>
                  <strong>{item.name}</strong> <code>{item.id}</code>
                  {item.kind && <span> · {item.kind}</span>}
                  {item.connection && <small>{zh ? '連線' : 'Connection'}: {item.connection}</small>}
                </button> : <div className="att-package-sut-metrics-catalog-item">
                  <strong>{item.name}</strong> <code>{item.id}</code>
                  {item.kind && <span> · {item.kind}</span>}
                  {item.connection && <small>{zh ? '連線' : 'Connection'}: {item.connection}</small>}
                </div>}
              </li>;
            })}</ul>}
          </section>
        ))}
      </div>}
      {!loading && !error && catalog && <dl className="att-resource-meta att-package-sut-metrics-provenance">
        <div><dt>{zh ? 'Manifest 摘要' : 'Manifest digest'}</dt><dd><code>{revisionLabel(catalog.manifestDigest)}</code></dd></div>
        <div><dt>{zh ? 'Package revision' : 'Package revision'}</dt><dd><code>{revisionLabel(catalog.packageRevision)}</code></dd></div>
        <div><dt>{zh ? 'Runtime Config revision' : 'Runtime Config revision'}</dt><dd><code>{revisionLabel(catalog.runtimeConfigRevision)}</code></dd></div>
      </dl>}
      {!loading && !error && catalog && resourceProvenance.length > 0 && <section className="att-source-file-references" aria-label={zh ? 'Package 資源來源資訊' : 'Package resource provenance'}>
        <h3>{zh ? 'Package 資源來源資訊' : 'Package resource provenance'}</h3>
        <ul>{resourceProvenance.map((value, index) => {
          const item = packageCatalogResourceProvenance(value, index, zh);
          return <li key={`${item.kind}:${item.id}:${index}`}>
            <strong>{zh ? '類型' : 'Kind'}: {item.kind}</strong>
            <span>{zh ? 'ID' : 'ID'}: <code>{item.id}</code></span>
            <span>{zh ? 'Package 相對路徑' : 'Package-relative path'}: <code>{item.path}</code></span>
            <span>SHA-256: <code>{item.digest}</code></span>
          </li>;
        })}</ul>
      </section>}
      {catalog && catalogCanEdit && <section className="att-source-file-references" aria-label={zh ? '建立 Package SUT Metrics 資源' : 'Create Package SUT Metrics resource'}>
        <h3>{zh ? '新增資源' : 'New resource'}</h3>
        <label>{zh ? '資源類型' : 'Resource kind'}
          <select aria-label={zh ? '資源類型' : 'Resource kind'} value={newResourceKind} disabled={selectionLocked || newResourceBusy}
            onChange={event => { setNewResourceKind(event.currentTarget.value as PackageSutMetricsResourceKind); setNewResourceError(''); }}>
            <option value="source">{zh ? '指標來源' : 'Metric Source'}</option>
            <option value="group">{zh ? '指標群組' : 'Metric Group'}</option>
            <option value="profile">{zh ? '指標設定檔' : 'Metrics Profile'}</option>
          </select>
        </label>
        <label>{zh ? '資源 ID' : 'Resource ID'}
          <input aria-label={zh ? '資源 ID' : 'Resource ID'} value={newResourceId} maxLength={80}
            disabled={selectionLocked || newResourceBusy}
            onChange={event => { setNewResourceId(event.currentTarget.value); setNewResourceError(''); }} />
        </label>
        <button type="button" disabled={!api.getPackageManifest || !api.createPackageSutMetricsDraft || selectionLocked || newResourceBusy
          || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(newResourceId.trim())}
          onClick={() => void prepareNewResource()}>
          {newResourceBusy ? (zh ? '載入 Package Manifest…' : 'Loading Package Manifest…') : (zh ? '準備新資源' : 'Prepare new resource')}
        </button>
        {newResourceError && <p role="alert">{newResourceError}</p>}
        {!api.getPackageManifest && <p role="note">{zh ? '此 Server client 不支援讀取 Package Manifest。' : 'This Server client does not support reading the Package Manifest.'}</p>}
      </section>}
      {catalog && <section className="att-source-file-references" aria-label={zh ? 'Package SUT Metrics 資源編輯器' : 'Package SUT Metrics resource editor'}>
        <h3>{zh ? 'SUT Metrics 資源' : 'SUT Metrics resource'}</h3>
        {!activeSelection && <p>{zh ? '選取明確列出的 Metric Source、Group 或 Profile 以載入其來源。' : 'Select an explicitly listed Metric Source, Group, or Profile to load its source.'}</p>}
        {resourceLoading && !newResource && <p role="status" aria-busy="true">{zh ? '正在載入選取的 Package 資源…' : 'Loading selected Package resource…'}</p>}
        {resourceError && <p role="alert">{resourceError}
          {selection && !resourceLoading && !draft && <button type="button" onClick={() => setResourceVersion(value => value + 1)}>{zh ? '重試載入來源' : 'Retry source load'}</button>}
        </p>}
        {activeResource && activeSelection && <>
          <h4>{newResource ? (zh ? '新增' : 'New') : ''} {resourceLabel} · {activeResource.id}</h4>
          <dl className="att-resource-meta">
            <div><dt>{zh ? 'Package 路徑' : 'Package path'}</dt><dd><code>{activeResource.path}</code></dd></div>
            <div><dt>{zh ? '資源摘要' : 'Resource digest'}</dt><dd><code>{activeResource.resourceDigest}</code></dd></div>
            {newResource && <>
              <div><dt>{zh ? '預期 Manifest 摘要' : 'Expected Manifest digest'}</dt><dd><code>{newResource.expectedManifestDigest}</code></dd></div>
              <div><dt>{zh ? '候選 Manifest 路徑' : 'Candidate Manifest entry'}</dt><dd><code>{newResource.path}</code></dd></div>
            </>}
          </dl>
          {catalogCanEdit && activeResource.canEdit !== true && <p role="note">{zh ? '此資源目前不可編輯。' : 'This resource is currently read only.'}</p>}
          {stale && <p role="alert">{zh ? '資源 revision 已過期。請捨棄草稿並重新載入來源。' : 'The resource revision is stale. Discard the draft and reload its source.'}</p>}
          <label>{zh ? `${resourceLabel} YAML` : `${resourceLabel} YAML`}
            <textarea aria-label={`${resourceLabel} YAML`} value={activeSource} readOnly={!editorWritable}
              onChange={event => updateActiveSource(event.currentTarget.value)} rows={16} maxLength={262144} spellCheck={false} />
          </label>
          {sourceDirty && !draft && <p role="status">{newResource
            ? (zh ? '新資源尚未列入 Package inventory。建立草稿以驗證資源及候選 Manifest。' : 'This resource is not in the Package inventory yet. Create a draft to validate both the resource and candidate Manifest.')
            : (zh ? '來源有尚未建立草稿的變更。建立草稿以進行驗證與發布。' : 'Source changes have not been staged. Create a draft to validate and publish them.')}</p>}
          {sourceDirty && !draft && resourceEditable && <button type="button" disabled={draftBusy} onClick={() => {
            if (newResource) cancelNewResource();
            else {
              setResourceSource(baselineSource); setDraftError(''); setDraftFailureDiagnostics([]);
              if (stale) { setStale(false); setRequestVersion(value => value + 1); setResourceVersion(value => value + 1); }
            }
          }}>
            {newResource ? (zh ? '取消新資源' : 'Cancel new resource') : (zh ? '捨棄來源變更' : 'Discard source edits')}
          </button>}
          {!draft && resourceEditable && <button type="button" disabled={!editorWritable || !sourceDirty || !selectedEnvironment
            || !api.createPackageSutMetricsDraft} onClick={() => void createDraft()}>
            {draftBusy ? (zh ? '建立草稿中…' : 'Creating draft…') : (zh ? '建立 SUT Metrics 草稿' : 'Create SUT Metrics draft')}
          </button>}
          {draftError && <p role="alert">{draftError}</p>}
          {draftFailureDiagnostics.length > 0 && <ul aria-label={zh ? '草稿操作診斷' : 'Draft action diagnostics'}>
            {draftFailureDiagnostics.map((value, index) => { const diagnostic = packageSutMetricsDiagnostic(value, index); return <li key={`failure:${diagnostic.key}`}>{diagnostic.text}</li>; })}
          </ul>}
          {draftMessage && <p role="status" aria-live="polite">{draftMessage}</p>}
          {draft && <section aria-label={zh ? 'Package SUT Metrics 草稿發布 Review' : 'Package SUT Metrics draft publication review'}>
            <h4>{zh ? '草稿狀態' : 'Draft state'}: {draft.state}</h4>
            {draft.expiresAt && <p>{zh ? '到期時間' : 'Expires'}: {draft.expiresAt}</p>}
            {draft.expectedManifestDigest && <dl className="att-resource-meta">
              <div><dt>{zh ? '預期 Manifest 摘要' : 'Expected Manifest digest'}</dt><dd><code>{draft.expectedManifestDigest}</code></dd></div>
              <div><dt>{zh ? '候選 Manifest 摘要' : 'Candidate Manifest digest'}</dt><dd><code>{revisionLabel(draft.candidateManifestDigest)}</code></dd></div>
              <div><dt>{zh ? '啟用順序' : 'Activation mode'}</dt><dd><code>{draft.activationMode === 'resource-then-manifest' ? draft.activationMode : '—'}</code></dd></div>
            </dl>}
            {draft.validation && <p role="status">{draft.validation.valid === true
              ? (zh ? '驗證通過。' : 'Validation passed.')
              : (zh ? '驗證未通過。' : 'Validation did not pass.')}</p>}
            {draft.validation?.resourceDigest && <p>{zh ? '候選資源摘要' : 'Validated resource digest'}: <code>{revisionLabel(draft.validation.resourceDigest)}</code></p>}
            {draft.validation?.candidateManifestDigest && <p>{zh ? '驗證的候選 Manifest 摘要' : 'Validated candidate Manifest digest'}: <code>{revisionLabel(draft.validation.candidateManifestDigest)}</code></p>}
            {draft.validation?.validationRevision && <p>{zh ? '驗證 revision' : 'Validation revision'}: <code>{revisionLabel(draft.validation.validationRevision)}</code></p>}
            {validationDiagnostics.length > 0 && <ul aria-label={zh ? '驗證診斷' : 'Validation diagnostics'}>
              {validationDiagnostics.map((value, index) => { const diagnostic = packageSutMetricsDiagnostic(value, index); return <li key={diagnostic.key}>{diagnostic.text}</li>; })}
            </ul>}
            {resourceEditable && <div className="att-explorer-detail-actions">
              <button type="button" disabled={draftBusy || stale || draft.state.toLowerCase() !== 'draft'} onClick={() => void actOnDraft('validate')}>
                {draftBusy ? (zh ? '處理中…' : 'Working…') : (zh ? '驗證草稿' : 'Validate draft')}
              </button>
              <button type="button" disabled={draftBusy || stale || draft.state.toLowerCase() !== 'validated' || draft.validation?.valid !== true}
                onClick={() => void actOnDraft('review')}>{zh ? 'Review 草稿' : 'Review draft'}</button>
              <button type="button" disabled={draftBusy || stale || catalog.canEdit !== true || draft.state.toLowerCase() !== 'reviewed'}
                onClick={() => void actOnDraft('publish')}>{zh ? '發布草稿' : 'Publish draft'}</button>
              <button type="button" disabled={draftBusy} onClick={() => void actOnDraft('discard')}>
                {draft.validation?.valid === false || draftFailureDiagnostics.length > 0
                  ? (zh ? '捨棄草稿並編輯來源' : 'Discard draft and edit source')
                  : (zh ? '捨棄草稿' : 'Discard draft')}
              </button>
            </div>}
            {!resourceEditable && <p role="note">{zh ? '繼續草稿需要此 Package 資源的來源編輯權限。' : 'Source editing access for this Package resource is required to continue this draft.'}</p>}
          </section>}
        </>}
      </section>}
    </section>
  </details>;
}

export function ExplorerPage({ api, packageId, resourceRoute, environment,
  principalLabel = '', onResourceRouteChange, onJobAccepted, onPrincipalLabelChange, onOpenQuickLoad,
  onRunTarget, onValidateTarget, onValidatePackage, onRunBatch, onScheduleDebug, onOpenConfigurationFile, onOpenTestdata, registerUnsavedChanges }: ExplorerPageProps) {
  const workspace = useContext(AppShellContext);
  const locale = workspace?.uiLocale || 'en';
  const validationText = workspace?.uiLocale === 'zh-Hant'
    ? { case: '驗證案例', resource: '驗證', suite: '驗證套件集' }
    : { case: 'Validate Case', resource: 'Validate', suite: 'Validate Suite' };
  const validatePackageText = locale === 'zh-Hant' ? '驗證套件' : 'Validate Package';
  const validationHelp = locale === 'zh-Hant'
    ? '唯讀檢查，不會執行測試或建立 Job。' : 'Read-only inspection; this does not run tests or create a Job.';
  const initialListState = useRef(savedExplorerList(packageId)).current;
  const [query, setQuery] = useState(initialListState.query);
  const [typeFilter, setTypeFilter] = useState<ResourceSummary['type'] | 'all'>(initialListState.typeFilter);
  const [sortKey, setSortKey] = useState<ResourceSortField>(initialListState.sortKey);
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>(initialListState.sortDirection);
  const [resources, setResources] = useState<ResourceSummary[]>([]);
  const [totalResources, setTotalResources] = useState(0);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingResources, setLoadingResources] = useState(false);
  const [resourceError, setResourceError] = useState('');
  const [resourceStatus, setResourceStatus] = useState('');
  const [detail, setDetail] = useState<ResourceSummary | null>(null);
  const [actionOrder, setActionOrder] = useState<string[] | undefined>();
  const [definition, setDefinition] = useState<unknown>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [tab, setTab] = useState<DetailTab>('definition');
  const [debugOpen, setDebugOpen] = useState(false);
  const [diagnosticMode, setDiagnosticMode] = useState(false);
  const [debugForm, setDebugForm] = useState<Awaited<ReturnType<ExplorerApi['getDebugForm']>> | null>(null);
  const [debugInput, setDebugInput] = useState<JsonObject>({});
  const [debugStatus, setDebugStatus] = useState('');
  const [debugError, setDebugError] = useState('');
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [formValid, setFormValid] = useState(true);
  const [resetKey, setResetKey] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [principal, setPrincipal] = useState(principalLabel);
  const [resourceRefresh, setResourceRefresh] = useState(0);
  const inspectionRefresh = useInspectionRefresh();
  const resourceListRef = useRef<HTMLDivElement | null>(null);
  const pagesToRestore = useRef(Math.max(1, initialListState.pagesLoaded));
  const savedScrollTop = useRef(Math.max(0, initialListState.scrollTop));
  const didRestoreScroll = useRef(false);
  const scrollSaveTimer = useRef<number | null>(null);
  const listStateRef = useRef({ packageId, query, typeFilter, sortKey, sortDirection });
  listStateRef.current = { packageId, query, typeFilter, sortKey, sortDirection };
  const dirtyRef = useRef(false);
  const debugValidationGeneration = useRef(0);
  const unregisterDirtyRef = useRef<(() => void) | null>(null);
  const detailIdentityKey = useRef('');
  const selectedRouteKey = resourceRouteKey(resourceRoute);
  const identity = useMemo<ResourceIdentity | null>(() => resourceRoute && isResourceType(resourceRoute.type)
    ? { packageId, type: resourceRoute.type, resourceId: resourceRoute.resourceId } : null,
  [packageId, resourceRoute?.type, resourceRoute?.resourceId]);
  const selectedKey = resourceRoute ? `${resourceRoute.type}:${resourceRoute.resourceId}` : '';

  const updateDirty = useCallback((next: boolean) => {
    dirtyRef.current = next;
    if (!next) {
      unregisterDirtyRef.current?.();
      unregisterDirtyRef.current = null;
    }
    setDirty(next);
  }, []);

  useEffect(() => {
    const unregister = registerUnsavedChanges(dirty, 'Explorer Debug and Test Runner edits');
    unregisterDirtyRef.current = unregister;
    return () => {
      unregister();
      if (unregisterDirtyRef.current === unregister) unregisterDirtyRef.current = null;
    };
  }, [dirty, registerUnsavedChanges]);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault(); event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  useEffect(() => {
    setPrincipal(principalLabel);
  }, [principalLabel]);

  useEffect(() => {
    // Preserve authored Debug inputs across suspension/resume, but drop validated
    // previews that may reference a stale projection or session.
    const invalidateDraft = () => { debugValidationGeneration.current++; setDraft(null); };
    window.addEventListener('pagehide', invalidateDraft);
    window.addEventListener('pageshow', invalidateDraft);
    document.addEventListener('visibilitychange', invalidateDraft);
    return () => {
      window.removeEventListener('pagehide', invalidateDraft);
      window.removeEventListener('pageshow', invalidateDraft);
      document.removeEventListener('visibilitychange', invalidateDraft);
    };
  }, []);

  useEffect(() => {
    setResources([]); setCursor(null); setResourceError(''); setResourceStatus('Loading package resources…');
    const controller = new AbortController();
    setLoadingResources(true);
    const requestedPages = Math.min(pagesToRestore.current, 1000);
    const timer = window.setTimeout(() => {
      void (async () => {
        const accumulated: ResourceSummary[] = [];
        let nextCursor: string | null = null;
        let total = 0;
        let diagnostics: Diagnostic[] = [];
        let pagesFetched = 0;
        try {
          for (let index = 0; index < requestedPages; index++) {
            const page = await api.listResources(packageId, {
              query: query.trim() || undefined, type: typeFilter, sort: sortKey, direction: sortDirection,
              ...(nextCursor ? { cursor: nextCursor } : {}), limit: RESOURCE_PAGE_SIZE,
            }, controller.signal);
            if (controller.signal.aborted) return;
            accumulated.push(...(page.items || []));
            total = Number(page.total || 0);
            nextCursor = page.nextCursor || null;
            diagnostics = page.diagnostics || [];
            pagesFetched++;
            // Paint each restored page as soon as its session check/cache lookup completes.
            setResources([...accumulated]); setTotalResources(total); setCursor(nextCursor);
            if (!nextCursor) break;
          }
          if (controller.signal.aborted) return;
          pagesToRestore.current = Math.max(1, pagesFetched);
          setResources(accumulated); setTotalResources(total); setCursor(nextCursor);
          const warning = diagnostics.map(item => item.summary || item.code).filter(Boolean).join(' ');
          setResourceStatus(`Showing ${accumulated.length} of ${total} resources.${warning ? ` ${warning}` : ''}`);
          setResourceError('');
          explorerListSnapshots.set(packageId, {
            ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
          });
        } catch (error) {
          if (!controller.signal.aborted) { setResourceError(userError(error, 'resource discovery')); setResourceStatus('Resource discovery failed.'); }
        } finally { if (!controller.signal.aborted) setLoadingResources(false); }
      })();
    }, query ? 220 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [api, packageId, query, typeFilter, sortKey, sortDirection, resourceRefresh, inspectionRefresh]);

  useEffect(() => {
    explorerListSnapshots.set(packageId, {
      ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
    });
  }, [packageId, query, typeFilter, sortKey, sortDirection]);

  useEffect(() => {
    if (loadingResources || didRestoreScroll.current) return;
    const container = resourceListRef.current;
    if (!container) return;
    container.scrollTop = savedScrollTop.current;
    didRestoreScroll.current = true;
  }, [loadingResources, resources.length]);

  useEffect(() => () => {
    if (scrollSaveTimer.current !== null) window.clearTimeout(scrollSaveTimer.current);
    explorerListSnapshots.set(packageId, {
      ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
    });
  }, [packageId]);

  const resetListPosition = () => {
    pagesToRestore.current = 1;
    savedScrollTop.current = 0;
    didRestoreScroll.current = true;
    if (resourceListRef.current) resourceListRef.current.scrollTop = 0;
  };

  const handleListScroll = (event: UIEvent<HTMLDivElement>) => {
    savedScrollTop.current = event.currentTarget.scrollTop;
    if (scrollSaveTimer.current !== null) window.clearTimeout(scrollSaveTimer.current);
    scrollSaveTimer.current = window.setTimeout(() => {
      explorerListSnapshots.set(packageId, {
        ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
      });
      scrollSaveTimer.current = null;
    }, 100);
  };

  const changeSort = (field: ResourceSortField) => {
    resetListPosition();
    if (field === sortKey) setSortDirection(value => value === 'asc' ? 'desc' : 'asc');
    else { setSortKey(field); setSortDirection('asc'); }
  };

  const loadMore = async () => {
    if (!cursor || loadingResources) return;
    setLoadingResources(true); setResourceError('');
    try {
      const page = await api.listResources(packageId, { query: query.trim() || undefined, type: typeFilter, sort: sortKey, direction: sortDirection, cursor, limit: 50 });
      setResources(current => [...current, ...(page.items || [])]); setCursor(page.nextCursor || null);
      setResourceStatus(`Showing ${resources.length + (page.items || []).length} of ${totalResources} resources.`);
      pagesToRestore.current++;
      explorerListSnapshots.set(packageId, {
        ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
      });
    } catch (error) { setResourceError(userError(error, 'loading more resources')); }
    finally { setLoadingResources(false); }
  };

  useEffect(() => {
    if (!identity) { detailIdentityKey.current = ''; setDetail(null); setDefinition(null); setActionOrder(undefined); setDetailError(''); return; }
    const controller = new AbortController();
    // Revalidation refreshes a projection in place, but clearing detail on every
    // cache invalidation would drop the open Debug form and its authored inputs.
    const identityKey = `${identity.packageId}:${identity.type}:${identity.resourceId}`;
    const identityChanged = detailIdentityKey.current !== identityKey;
    detailIdentityKey.current = identityKey;
    setDetailLoading(true); setDetailError('');
    if (identityChanged) { setDetail(null); setDefinition(null); setActionOrder(undefined); }
    api.getResource(identity, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      setDetail(response.resource); setDefinition(response.definition); setActionOrder(response.actionOrder); setDetailError('');
    }).catch(error => {
      if (!controller.signal.aborted) setDetailError(userError(error, 'resource detail'));
    }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false); });
    return () => controller.abort();
  }, [api, identity?.packageId, identity?.type, identity?.resourceId, inspectionRefresh]);

  useEffect(() => {
    const requestedTab = resourceRoute?.tab;
    // `definition` remains the route identifier for compatibility. Template and
    // Flow details now present that panel as the Visual action inspector.
    setTab(requestedTab === 'source' || requestedTab === 'relations' || requestedTab === 'definition' ? requestedTab : 'definition');
  }, [selectedRouteKey, resourceRoute?.tab]);

  useEffect(() => {
    debugValidationGeneration.current++;
    setDebugOpen(false); setDebugForm(null); setDebugInput({}); setDraft(null); setDebugError(''); setDebugStatus('');
    setFormValid(true); setResetKey(value => value + 1); updateDirty(false);
  }, [packageId, selectedRouteKey, updateDirty]);

  useEffect(() => {
    if (!debugOpen || !identity || !isRunnable(detail)) return;
    debugValidationGeneration.current++;
    const controller = new AbortController();
    setDebugForm(null); setDraft(null); setDebugError(''); setDebugStatus('Loading safe Debug defaults…');
    api.getDebugForm(identity, environment || undefined, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      setDebugForm(response); setDebugInput(JSON.parse(JSON.stringify(response.input || {})) as JsonObject);
      setResetKey(value => value + 1); setFormValid(true); updateDirty(false);
      setDebugStatus(response.redacted
        ? 'Defaults loaded. Hidden package-authored values are preserved until you choose a typed override.'
        : 'Defaults loaded from the selected resource sidecar.');
    }).catch(error => {
      if (!controller.signal.aborted) { setDebugError(userError(error, 'Debug defaults')); setDebugStatus('Debug defaults are unavailable.'); }
    });
    return () => controller.abort();
  }, [api, debugOpen, identity?.packageId, identity?.type, identity?.resourceId, environment, detail?.resourceId]);

  const setBusinessInput = (value: JsonObject) => {
    debugValidationGeneration.current++;
    setDebugInput(value); setDraft(null); updateDirty(true);
    setDebugStatus('Debug input changed. Validate it before starting.'); setDebugError('');
  };

  const previewDebug = async (): Promise<DraftResponse | null> => {
    if (!debugForm || !identity) { setDebugError('Open Debug for a Template, Flow, or Tool first.'); return null; }
    if (!formValid) { setDebugError('Correct the typed fields or Advanced JSON before validating.'); return null; }
    const generation = ++debugValidationGeneration.current;
    const body: DebugDraftRequest = { packageId, target: debugForm.target, input: debugInput };
    if (environment) body.environment = environment;
    setPreviewLoading(true); setDebugError(''); setDebugStatus('Validating the effective Debug input…');
    try {
      const nextDraft = api.previewRun ? await api.previewRun({ ...body, schemaVersion: 'att-execution/v1', executionType: 'run', options: { debug: diagnosticMode } }) : await api.previewDebug(body);
      if (generation !== debugValidationGeneration.current) return null;
      setDraft(nextDraft); setDebugStatus(nextDraft.redacted ? 'Validation passed. Sensitive preview values remain hidden.' : 'Validation passed.');
      return nextDraft;
    } catch (error) { setDebugError(userError(error, 'Debug preview')); setDebugStatus('Debug preview failed.'); return null; }
    finally { setPreviewLoading(false); }
  };

  const scheduleDebug = async () => {
    if (!debugForm || !identity || !onScheduleDebug || !formValid) return;
    let currentDraft = draft;
    const expiresAt = currentDraft?.expiresAt ? Date.parse(currentDraft.expiresAt) : Number.NaN;
    if (!currentDraft || !Number.isFinite(expiresAt) || expiresAt <= Date.now() + 15_000) currentDraft = await previewDebug();
    if (!currentDraft) return;
    const wasDirty = dirtyRef.current;
    updateDirty(false);
    const accepted = onScheduleDebug({ packageId, environment: environment || undefined, draftId: currentDraft.draftId, executionType: 'run', legacyDebug: !currentDraft.draftId.startsWith('R'),
      previewYaml: currentDraft.previewYaml, target: { type: debugForm.target.type, id: debugForm.target.id } });
    if (accepted === false && wasDirty) updateDirty(true);
  };

  const startDebug = async () => {
    if (!debugForm || !identity || !formValid) { setDebugError('Correct the typed fields or Advanced JSON before starting.'); return; }
    let currentDraft = draft;
    const expiresAt = currentDraft?.expiresAt ? Date.parse(currentDraft.expiresAt) : Number.NaN;
    if (!currentDraft || !Number.isFinite(expiresAt) || expiresAt <= Date.now() + 15_000) currentDraft = await previewDebug();
    if (!currentDraft) return;
    if (!window.confirm(`Run ${detail?.logicalId || detail?.name || identity.resourceId}?`)) return;
    setSubmitLoading(true); setDebugError(''); setDebugStatus('Submitting Run…');
    try {
      const accepted = await (currentDraft.draftId.startsWith('R') && api.submitRun ? api.submitRun : api.submitDebug)({ packageId, draftId: currentDraft.draftId, principal: principal.trim() || undefined });
      setDraft(null); updateDirty(false); setDebugStatus(`Run job ${accepted.jobId} was accepted. Opening job status…`);
      onJobAccepted(accepted.jobId);
    } catch (error) {
      const status = statusOf(error);
      if (status === 404 || status === 409) setDraft(null);
      setDebugError(userError(error, 'Debug submission'));
      setDebugStatus(status === 404 || status === 409 ? 'This Debug draft expired or became stale. Validate the current input again.' : 'Debug was not submitted.');
    } finally { setSubmitLoading(false); }
  };

  const resetDebug = () => {
    if (!debugForm) return;
    debugValidationGeneration.current++;
    setDebugInput(JSON.parse(JSON.stringify(debugForm.input || {})) as JsonObject);
    setDraft(null); setDebugError(''); setDebugStatus('Defaults restored. Validate before starting Debug.');
    setFormValid(true); setResetKey(value => value + 1); updateDirty(false);
  };

  const runCase = async () => {
    if (!detail || detail.type !== 'case' || !detail.logicalId || !detail.provenance?.suite) return;
    if (!window.confirm(`Run case ${detail.logicalId}?`)) return;
    setDetailError(''); setDetailLoading(true);
    try {
      const accepted = await api.runCase({ packageId, suite: String(detail.provenance.suite), caseId: detail.logicalId, environment: environment || undefined, principal: principal.trim() || undefined });
      updateDirty(false); onJobAccepted(accepted.jobId);
    } catch (error) { setDetailError(userError(error, 'case run')); }
    finally { setDetailLoading(false); }
  };

  const changePrincipal = (next: string) => {
    setPrincipal(next); onPrincipalLabelChange?.(next);
  };

  const selectedResource = detail || resources.find(item => resourceKey(item) === selectedKey) || null;
  const debugEnabled = isRunnable(selectedResource);
  const selectedType = resourceRoute?.type;
  const groups = debugGroups(debugForm?.target.type || selectedType);
  const previewText = draft?.previewYaml || (draft?.preview ? JSON.stringify(draft.preview, null, 2) : '');
  const detailText = safeProjection(definition);
  const hasVisualActions = identity?.type === 'template' || identity?.type === 'flow';
  const requestedDetailIdentityKey = identity ? `${identity.packageId}:${identity.type}:${identity.resourceId}` : '';
  const detailRequestPending = Boolean(identity && (detailLoading || detailIdentityKey.current !== requestedDetailIdentityKey));
  const sourceIdentity = identity;
  const onTabsKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const tabs: DetailTab[] = ['definition', 'source', 'relations'];
    const currentIndex = tabs.indexOf(tab);
    const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (currentIndex + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    setTab(tabs[nextIndex]);
    if (identity) onResourceRouteChange({ type: identity.type, resourceId: identity.resourceId, logicalId: detail?.logicalId, tab: tabs[nextIndex], ...(resourceRoute?.actionId ? { actionId: resourceRoute.actionId } : {}) });
    event.currentTarget.querySelector<HTMLButtonElement>(`[data-tab="${tabs[nextIndex]}"]`)?.focus();
  };
  const selectDetailTab = (next: DetailTab) => {
    setTab(next);
    if (identity) onResourceRouteChange({ type: identity.type, resourceId: identity.resourceId, logicalId: detail?.logicalId, tab: next, ...(resourceRoute?.actionId ? { actionId: resourceRoute.actionId } : {}) });
  };

  return <main className="page-content att-explorer-page">
    <header className="att-explorer-page-heading">
      <div><p className="att-explorer-breadcrumb">Packages / {packageId} / Explorer</p><h1>{identity ? 'Resource details' : 'Package Explorer'}</h1>
        <p>{identity ? 'View the safe definition, source, and dependencies for this resource.' : 'Search, sort, and open resources. Choose an execution environment from the RUN or Quick LOAD editor.'}</p></div>
    </header>

    <PackageSutMetricsPanel api={api} packageId={packageId} environment={environment} refreshKey={resourceRefresh + inspectionRefresh}
      locale={locale} registerUnsavedChanges={registerUnsavedChanges} />

    <div className={`att-explorer-workspace${identity ? ' has-selected-resource' : ' is-list-first'}${identity ? '' : ' att-query-workspace'}`}>
      <aside className="att-explorer-navigation" aria-label="Resource navigation" hidden={Boolean(identity)}>
        <section className="att-query-filter-panel" aria-label="Resource filters">
          <h2>Resources</h2>
          <div className="att-query-filter-grid">
            <label>Search resources <input type="search" value={query} maxLength={200} onChange={event => { resetListPosition(); setQuery(event.currentTarget.value); }} /></label>
            <div className="att-explorer-type-filter">
              <span>{locale === 'zh-Hant' ? '資源類型' : 'Resource Type'}</span>
              <div className="att-explorer-categories" role="radiogroup" aria-label={locale === 'zh-Hant' ? '資源類型篩選' : 'Resource Type filters'}
              title={locale === 'zh-Hant' ? '選取類型以篩選目前清單。' : 'Select a type to filter the current resource list.'}
              onKeyDown={event => {
                const values = ['all', 'case', 'template', 'flow', 'tool'] as const;
                const current = values.indexOf(typeFilter as typeof values[number]);
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? values.length - 1
                  : event.key === 'ArrowRight' ? (current + 1) % values.length
                    : event.key === 'ArrowLeft' ? (current + values.length - 1) % values.length : -1;
                if (next < 0) return;
                event.preventDefault();
                resetListPosition(); setTypeFilter(values[next]);
                event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus();
              }}>
              {([
                ['all', 'All'], ['case', 'Test Cases'], ['template', 'Templates'], ['flow', 'Flows'], ['tool', 'Tools'],
              ] as Array<[ResourceSummary['type'] | 'all', string]>).map(([value, englishLabel]) => {
                const label = locale === 'zh-Hant'
                  ? ({ All: '全部', 'Test Cases': '測試案例', Templates: '範本', Flows: '流程', Tools: '工具' } as Record<string, string>)[englishLabel]
                  : englishLabel;
                return <button key={value} type="button" role="radio" aria-checked={typeFilter === value}
                  tabIndex={typeFilter === value ? 0 : -1}
                  onClick={() => { resetListPosition(); setTypeFilter(value); }}>{label}</button>;
              })}
              </div>
            </div>
          </div>
          <div className="att-query-filter-actions att-explorer-resource-actions" role="group" aria-label={locale === 'zh-Hant' ? '資源操作' : 'Resource actions'}>
            <button className="button button-quiet" type="button" onClick={() => { resetListPosition(); setQuery(''); setTypeFilter('all'); }}>{locale === 'zh-Hant' ? '清除篩選條件' : 'Clear filters'}</button>
            <button className="button button-quiet" type="button" disabled={loadingResources} onClick={() => (inspectionCache.clear(), setResourceRefresh(value => value + 1))}>{locale === 'zh-Hant' ? '重新整理資源' : 'Refresh resources'}</button>
            {onValidatePackage && <button className="button button-quiet" type="button" title={validationHelp} onClick={onValidatePackage}>{validatePackageText}</button>}
            {onRunBatch && <button className="button button-primary" type="button" onClick={onRunBatch}>{locale === 'zh-Hant' ? '執行測試案例…' : 'Run Test Cases…'}</button>}
            {onOpenTestdata && <button className="button button-quiet" type="button" onClick={onOpenTestdata}>Testdata</button>}
          </div>
        </section>
        <section className="att-query-results-panel" aria-label="Resource results">
          <p className="att-query-result-status" role="status" aria-live="polite">{resourceStatus}</p>
          {resourceError && <p className="att-error" role="alert">{resourceError}<button type="button" onClick={() => (inspectionCache.clear(), setResourceRefresh(value => value + 1))}>Retry</button></p>}
          <ResourceTree items={resources} selectedKey={selectedKey} loading={loadingResources} nextCursor={cursor} sortKey={sortKey} direction={sortDirection} onSortChange={changeSort}
            onSelect={resource => onResourceRouteChange({ type: resource.type, resourceId: resource.resourceId, logicalId: resource.logicalId })}
            onRun={onRunTarget ? resource => onRunTarget({ type: resource.type, resourceId: resource.resourceId, logicalId: resource.logicalId }) : undefined}
            onQuickLoad={onOpenQuickLoad ? resource => onOpenQuickLoad({ packageId, type: resource.type, resourceId: resource.resourceId, logicalId: resource.logicalId }) : undefined}
            onLoadMore={() => void loadMore()} scrollContainerRef={resourceListRef} onListScroll={handleListScroll} />
          <p className="att-explorer-total">{totalResources.toLocaleString()} {typeFilter === 'all' ? 'package resources' : typeFilter + ' resources'}</p>
        </section>
      </aside>

      <section className="att-explorer-detail" aria-label="Resource details" hidden={!identity}>
        {!identity && <div className="att-explorer-empty-state"><h2>Select a resource</h2><p>Choose a Case, Template, Flow, or Tool from the resource list.</p></div>}
        {identity && <>
          <button className="button button-quiet" type="button" onClick={() => onResourceRouteChange(null)}>Back to resources</button>
          <header className="att-resource-title">
            <div><p>{identity.type} · {packageId}</p><h2>{detail?.name || detail?.logicalId || identity.resourceId}</h2>
              <p>{detail?.description || detail?.logicalId || ''}</p></div>
            {detail?.state === 'invalid' && <span className="att-status-badge" data-state="error">Invalid resource</span>}
          </header>
          {detail?.executionReadiness && <section aria-label="Execution readiness" className="att-diagnostic">
            <p>Metadata {detail.metadataState === 'available' ? 'available' : 'invalid'} · Execution {detail.executionReadiness.state === 'dependency-unresolved'
              ? 'dependencies unresolved' : detail.executionReadiness.state === 'invalid' ? 'invalid' : 'not validated'}
              {detail.executionReadiness.environment ? ` (${detail.executionReadiness.environment})` : ''}.</p>
            {detail.executionReadiness.diagnostics?.map((item, index) => <p key={`${item.code}:${index}`}>
              {item.code ? `${item.code}: ` : ''}{item.summary}{item.remediation ? ` ${item.remediation}` : ''}
            </p>)}
            {detail.executionReadiness.diagnosticsTruncated && <p>Additional requirements are omitted. Validate the selected target for complete execution checks.</p>}
          </section>}
          <div className="att-explorer-detail-actions">
            {onRunTarget && detail?.state !== 'invalid' && <button className="button button-primary" type="button" onClick={() => onRunTarget({ type: identity.type, resourceId: identity.resourceId, logicalId: detail?.logicalId })}>Run</button>}
            {onValidateTarget && detail && detail.type === 'case' && detail.logicalId && <button className="button button-quiet" type="button"
              title={validationHelp} onClick={() => onValidateTarget('case', detail.resourceId, { type: 'case', resourceId: detail.resourceId, logicalId: detail.logicalId })}>{validationText.case}</button>}
            {onValidateTarget && detail && detail.type !== 'case' && <button className="button button-quiet" type="button"
              title={validationHelp} onClick={() => onValidateTarget(detail.type, detail.resourceId, { type: detail.type, resourceId: detail.resourceId, logicalId: detail.logicalId })}>{validationText.resource}</button>}
            {onValidateTarget && detail?.type === 'case' && safeSuiteTarget(detail.provenance?.suite) && <button className="button button-quiet" type="button"
              title={validationHelp} onClick={() => onValidateTarget('suite', safeSuiteTarget(detail.provenance?.suite)!, { type: 'case', resourceId: detail.resourceId, logicalId: detail.logicalId })}>{validationText.suite}</button>}
            {onOpenQuickLoad && detail?.state !== 'invalid' && ['template','flow','tool'].includes(identity.type) && <button className="button button-quiet" type="button" onClick={() => onOpenQuickLoad({ packageId, type: identity.type, resourceId: identity.resourceId, logicalId: detail?.logicalId })}>Quick LOAD</button>}
          </div>
          {detailLoading && <p role="status">Loading resource detail…</p>}
          {detailError && <p className="att-error" role="alert">{detailError}</p>}
          <div className="att-explorer-tabs" role="tablist" aria-label="Resource content" onKeyDown={onTabsKeyDown}>
            {(['definition', 'source', 'relations'] as const).map(name => <button key={name} type="button" role="tab" data-tab={name}
              id={`att-explorer-tab-${name}`} aria-selected={tab === name} aria-controls={`att-explorer-panel-${name}`}
              tabIndex={tab === name ? 0 : -1} onClick={() => selectDetailTab(name)}>{name === 'definition' ? hasVisualActions ? 'Visual' : 'Definition' : name === 'source' ? 'Source' : 'Dependencies'}</button>)}
          </div>
          <section id="att-explorer-panel-definition" role="tabpanel" aria-labelledby="att-explorer-tab-definition" hidden={tab !== 'definition'}>
            {hasVisualActions
              ? definition == null && detailRequestPending ? <p role="status">Loading action definition…</p>
                : definition == null && detailError ? <p role="status">The action definition could not be loaded.</p>
                  : definition == null ? <p>No definition projection is available.</p>
                    : <ActionInspector definition={definition} actionOrder={actionOrder} resourceType={identity.type === 'flow' ? 'flow' : 'template'}
                        resourceId={identity.resourceId} references={detail?.references}
                        selectedActionId={resourceRoute?.actionId}
                        onSelectAction={actionId => onResourceRouteChange({ type: identity.type, resourceId: identity.resourceId,
                          logicalId: detail?.logicalId, tab: 'definition', actionId })}
                        onOpenFlow={targetId => onResourceRouteChange({ type: 'flow', resourceId: targetId, tab: 'definition' })}
                        onOpenFile={(path, origin, reference) => onOpenConfigurationFile?.(path, origin, reference)} />
              : definition == null && !detailRequestPending ? <p>No definition projection is available.</p>
                : <CodeEditor value={detailText} language="json" label="Safe resource definition" readOnly sourcePath={detail?.sourcePath} fileReferences={[]} />}
          </section>
          {sourceIdentity && <section id="att-explorer-panel-source" role="tabpanel" aria-labelledby="att-explorer-tab-source" hidden={tab !== 'source'}>
            <SourceViewer api={api} identity={sourceIdentity} available={detail?.sourceAvailable === true} active={tab === 'source'} resource={detail} onFileReference={onOpenConfigurationFile} locale={workspace?.uiLocale} />
          </section>}
          <section id="att-explorer-panel-relations" role="tabpanel" aria-labelledby="att-explorer-tab-relations" hidden={tab !== 'relations'}>
            <div className="att-relation-columns">
              <div><h3>References</h3><ul>{(detail?.references || []).map((reference, index) => <ExplorerReference key={`${reference.type}:${reference.logicalId || reference.resourceId}:${index}`} reference={reference} onOpen={onResourceRouteChange} onOpenFile={onOpenConfigurationFile} sourceIdentity={identity} />)}</ul>{!detail?.references?.length && <p>None</p>}
                {detail?.fileDependenciesTruncated && <p role="status">Additional Package file dependencies were omitted by the Server safety limit.</p>}</div>
              <div><h3>Referenced by</h3><ul>{(detail?.referencedBy || []).map((reference, index) => <ExplorerReference key={`${reference.type}:${reference.resourceId}:${index}`} reference={reference} onOpen={onResourceRouteChange} />)}</ul>{!detail?.referencedBy?.length && <p>None</p>}</div>
            </div>
            {detail?.diagnostics?.map((item, index) => <p className="att-diagnostic" key={`${item.code}:${index}`}>{item.code ? `${item.code}: ` : ''}{item.summary}</p>)}
          </section>
        </>}
      </section>

      <aside className="att-explorer-inspector" aria-label="Contextual actions" hidden>
        <h2>Inspector</h2>
        {!selectedResource && <p>Select a resource to view its metadata and actions.</p>}
        {selectedResource && <>
          <dl className="att-resource-meta"><div><dt>Type</dt><dd>{selectedResource.type}</dd></div>
            <div><dt>Logical ID</dt><dd>{selectedResource.logicalId || '—'}</dd></div>
            <div><dt>State</dt><dd>{selectedResource.state || 'ready'}</dd></div>
            <div><dt>Tags</dt><dd>{selectedResource.tags?.join(', ') || '—'}</dd></div></dl>
          <label>Anonymous job label (optional)<input value={principal} maxLength={120} onChange={event => changePrincipal(event.currentTarget.value)} /></label>
          {selectedResource.type === 'case' && <button type="button" disabled={detailLoading || !detail?.provenance?.suite || !detail.logicalId} onClick={() => void runCase()}>Run selected case</button>}
          {debugEnabled && <button type="button" aria-expanded={debugOpen} onClick={() => setDebugOpen(value => !value)}>{debugOpen ? 'Close Run' : 'Run this target'}</button>}
          {onOpenQuickLoad && debugEnabled && identity && <button type="button" onClick={() => onOpenQuickLoad({ ...identity, logicalId: selectedResource.logicalId })}>Quick Load</button>}
          {debugOpen && <section className="att-debug-panel" aria-labelledby="att-debug-heading">
            <h3 id="att-debug-heading">Run</h3>
            <p className="att-debug-status" role="status" aria-live="polite">{debugStatus}</p>
            {debugError && <p className="att-error" role="alert">{debugError}</p>}
            {debugForm && <>
              <TypedSidecarForm key={`${selectedRouteKey}:${resetKey}`} value={debugInput} groups={groups} resetKey={resetKey}
                onChange={setBusinessInput} onValidityChange={valid => {
                  setFormValid(valid);
                  if (!valid) { debugValidationGeneration.current++; setDraft(null); }
                }} />
              <label><input type="checkbox" checked={diagnosticMode} onChange={event => { setDiagnosticMode(event.target.checked); setDraft(null); debugValidationGeneration.current++; updateDirty(true); }} />Debug diagnostics</label>
              <div className="att-debug-actions">
                <button type="button" onClick={resetDebug} disabled={previewLoading || submitLoading}>Reset defaults</button>
                <button type="button" onClick={() => void previewDebug()} disabled={previewLoading || submitLoading || !formValid}>{previewLoading ? 'Validating…' : 'Validate and preview'}</button>
                <button type="button" onClick={() => void startDebug()} disabled={submitLoading || previewLoading || !formValid}>{submitLoading ? 'Starting…' : 'Run Now'}</button>
                {onScheduleDebug && <button type="button" onClick={() => void scheduleDebug()} disabled={submitLoading || previewLoading || !formValid}>Schedule…</button>}
              </div>
              <p className="att-help" role="note">Scheduling keeps this validated Debug target, environment and input snapshot. Protected package defaults remain server-managed and are not saved as schedule values.</p>
              {previewText && <section className="att-debug-preview"><h4>Safe effective Debug preview</h4><CodeEditor value={previewText} language={draft?.previewYaml ? 'yaml' : 'json'} label="Safe effective Debug preview" readOnly /></section>}
            </>}
          </section>}
        </>}
      </aside>
    </div>
  </main>;
}
