import { useCallback, useContext, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { AppShellContext } from '../../AppShell';
import type { ResourceRoute, ValidationRouteState, ValidationScope } from '../../router';
import type { api } from '../../api';
import type { AsyncState, ApiError, PackageSummary, ServerVersion } from '../../types';
import { ValidationReport } from './ValidationReport';
import type { ValidationRequest, ValidationReportData } from './types';
import './validation.css';

type ValidationApi = Pick<typeof api, 'validateResource' | 'getDeclaredConfiguration' | 'getPackageManifest' | 'listResources' | 'listLoadCatalog'>;
type TargetOption = { value: string; label: string; resource?: ResourceRoute };
type CatalogState = 'idle' | 'loading' | 'ready' | 'error';
const VALIDATION_API_VERSION = 'att-validation/v1';
const SCOPES: Array<{ value: ValidationScope; label: string }> = [
  { value: 'config', label: 'Shared / Effective Config' }, { value: 'package', label: 'Package' },
  { value: 'case', label: 'Case' }, { value: 'suite', label: 'Suite' }, { value: 'template', label: 'Template' },
  { value: 'flow', label: 'Flow' }, { value: 'tool', label: 'Tool' }, { value: 'load', label: 'LOAD Scenario' },
];

function text(en: string, zh: string, locale: 'en' | 'zh-Hant'): string { return locale === 'zh-Hant' ? zh : en; }
function isSelectedScope(scope: ValidationScope): boolean { return ['case', 'suite', 'template', 'flow', 'tool', 'load'].includes(scope); }
function safePackagePath(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value || value.length > 2048 || value.startsWith('/') || value.includes('\\')
    || /^[a-z][a-z\d+.-]*:/i.test(value) || /[\u0000-\u001f\u007f]/.test(value)) return undefined;
  const parts = value.split('/');
  return parts.some(part => !part || part === '.' || part === '..') ? undefined : value;
}

function resourceTarget(scope: ValidationScope, item: { type: string; resourceId: string; logicalId?: string; name?: string; provenance?: { suite?: string } }): TargetOption | null {
  if (scope === 'case') {
    if (item.type !== 'case' || !item.logicalId || !item.resourceId) return null;
    return { value: item.resourceId, label: item.name || item.logicalId, resource: { type: 'case', resourceId: item.resourceId, logicalId: item.logicalId } };
  }
  if (scope === 'suite') {
    if (item.type !== 'case') return null;
    const suite = safePackagePath(item.provenance?.suite);
    return suite ? { value: suite, label: suite } : null;
  }
  if (item.type !== scope || !item.resourceId) return null;
  return { value: item.resourceId, label: item.name || item.logicalId || item.resourceId,
    resource: { type: scope, resourceId: item.resourceId, ...(item.logicalId ? { logicalId: item.logicalId } : {}) } };
}

function validationFailure(error: unknown, locale: 'en' | 'zh-Hant'): { message: string; requestId?: string } {
  const apiError = error as ApiError;
  const status = typeof apiError?.status === 'number' ? apiError.status : 0;
  const requestId = typeof apiError?.requestId === 'string' ? apiError.requestId : undefined;
  const message = status === 400 ? text('The Server rejected this validation request. Check the selected scope and target.', 'Server 拒絕此驗證請求。請檢查所選範圍及目標。', locale)
    : status === 401 ? text('Your session is no longer authenticated. Sign in again before validating.', '目前工作階段已失效。請重新登入後再驗證。', locale)
      : status === 403 ? text('Validation access or the selected Environment is not authorized.', '您沒有驗證權限，或所選環境未獲授權。', locale)
        : status === 404 ? text('The registered Package or selected target no longer exists. Refresh the catalog.', '已註冊套件或所選目標已不存在。請重新整理目錄。', locale)
          : status === 409 ? text('The source changed or the request is stale. Refresh the target and validate again.', '來源已變更或請求已過期。請重新整理目標並再次驗證。', locale)
            : status === 413 ? text('The request or safe report exceeded the Server size limit.', '請求或安全報告超出 Server 大小限制。', locale)
              : status === 415 ? text('The Server requires a JSON validation request. Update ATT Studio and retry.', 'Server 要求 JSON 驗證請求。請更新 ATT Studio 後重試。', locale)
                : status === 503 ? text('Validation Worker capacity is full. Wait briefly, then retry.', '驗證 Worker 容量已滿。請稍候再重試。', locale)
                  : status === 504 ? text('Read-only validation exceeded the Server time limit. Narrow the scope and retry.', '唯讀驗證超過 Server 時限。請縮小範圍後重試。', locale)
                    : status === 0 ? text('ATT Server does not return a supported validation report. Check its advertised capability and version.', 'ATT Server 未回傳支援的驗證報告。請檢查其 capability 與版本資訊。', locale)
                      : text('The Server could not complete read-only validation. Retry when it is available.', 'Server 無法完成唯讀驗證。請在服務恢復後重試。', locale);
  return { message, requestId };
}

export function ValidationWorkspacePage({ api, packages, serverVersion, route, onRouteChange, onOpenResource }: {
  api: ValidationApi;
  packages: AsyncState<PackageSummary[]>;
  serverVersion: AsyncState<ServerVersion>;
  route: ValidationRouteState;
  onRouteChange(route: ValidationRouteState): void;
  onOpenResource?(packageId: string, resource: ResourceRoute, environment?: string): void;
}) {
  const workspace = useContext(AppShellContext);
  if (!workspace) throw new Error('ValidationWorkspacePage requires ATT Studio context.');
  const locale = workspace.uiLocale;
  const scope = route.scope || 'config';
  const packageId = route.packageId || '';
  const environment = route.environment ?? '';
  const target = route.target || '';
  const [environmentProfiles, setEnvironmentProfiles] = useState<Array<{ name: string; state?: string; default?: boolean }>>([]);
  const [environmentStatus, setEnvironmentStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [effectiveEnvironment, setEffectiveEnvironment] = useState('');
  const [targetOptions, setTargetOptions] = useState<TargetOption[]>([]);
  const [targetCursor, setTargetCursor] = useState<string | undefined>();
  const [targetCatalogState, setTargetCatalogState] = useState<CatalogState>('idle');
  const [targetCatalogError, setTargetCatalogError] = useState('');
  const [targetSearch, setTargetSearch] = useState('');
  const [catalogRefresh, setCatalogRefresh] = useState(0);
  const [reportResult, setReportResult] = useState<{ key: string; report: ValidationReportData } | null>(null);
  const [requestError, setRequestError] = useState<{ key: string; message: string; requestId?: string } | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [notice, setNotice] = useState('');
  const requestController = useRef<AbortController | null>(null);
  const requestSequence = useRef(0);
  const currentRequestKey = useRef('');
  const catalogController = useRef<AbortController | null>(null);
  const catalogSequence = useRef(0);
  const targetCursorRef = useRef<string | undefined>(undefined);
  targetCursorRef.current = targetCursor;

  const routeUpdate = useCallback((changes: Partial<Omit<ValidationRouteState, 'kind'>>) => {
    onRouteChange({ kind: 'validation', scope, packageId: packageId || undefined, environment,
      target: target || undefined, resource: route.resource, ...changes });
  }, [onRouteChange, scope, packageId, environment, target, route.resource]);

  useEffect(() => {
    const controller = new AbortController();
    setEnvironmentProfiles([]); setEffectiveEnvironment('');
    if (!packageId) { setEnvironmentStatus('idle'); return () => controller.abort(); }
    setEnvironmentStatus('loading');
    api.getDeclaredConfiguration(packageId, controller.signal).then(configuration => {
      if (!controller.signal.aborted) {
        setEnvironmentProfiles((configuration.environments || []).filter(item => item.name && (item.state === 'active' || !item.state)));
        setEnvironmentStatus('ready');
      }
    }).catch(() => { if (!controller.signal.aborted) setEnvironmentStatus('error'); });
    api.getPackageManifest(packageId, 'effective', environment || undefined, controller.signal).then(manifest => {
      if (!controller.signal.aborted && typeof manifest.environment === 'string') setEffectiveEnvironment(manifest.environment);
    }).catch(() => { /* The Server resolves the effective Environment during validation as well. */ });
    return () => controller.abort();
  }, [api, packageId, environment]);

  const loadTargets = useCallback(async (append: boolean, query: string) => {
    if (!packageId || !isSelectedScope(scope)) return;
    catalogController.current?.abort();
    const controller = new AbortController(); catalogController.current = controller;
    const sequence = ++catalogSequence.current;
    const cursor = append ? targetCursorRef.current : undefined;
    setTargetCatalogState('loading'); setTargetCatalogError('');
    try {
      let options: TargetOption[] = [];
      let next: string | undefined;
      if (scope === 'load') {
        const page = await api.listLoadCatalog(packageId, { query: query.trim() || undefined, cursor, limit: 100 }, controller.signal);
        options = (page.items || []).filter(item => item.kind === 'scenario')
          .map(item => safePackagePath(item.path)).filter((path): path is string => Boolean(path))
          .map(path => ({ value: path, label: path }));
        next = page.nextCursor || undefined;
      } else {
        const type = scope === 'suite' ? 'case' : scope;
        const page = await api.listResources(packageId, { type: type as 'case' | 'template' | 'flow' | 'tool',
          query: query.trim() || undefined, cursor, limit: 100 }, controller.signal);
        options = (page.items || []).map(item => resourceTarget(scope, item)).filter((item): item is TargetOption => Boolean(item));
        next = page.nextCursor || undefined;
      }
      if (controller.signal.aborted || sequence !== catalogSequence.current) return;
      setTargetOptions(current => append
        ? [...current, ...options.filter(option => !current.some(item => item.value === option.value))]
        : options);
      targetCursorRef.current = next; setTargetCursor(next); setTargetCatalogState('ready');
    } catch (error) {
      if (!controller.signal.aborted && sequence === catalogSequence.current) {
        setTargetCatalogState('error');
        const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
        setTargetCatalogError(status === 403 ? text('You cannot list targets in this Package.', '您無法列出此套件中的目標。', locale)
          : status === 404 ? text('The registered Package is unavailable.', '已註冊套件無法使用。', locale)
            : text('The authorized target catalog could not be loaded.', '無法載入已授權的目標目錄。', locale));
      }
    }
  }, [api, locale, packageId, scope]);

  useEffect(() => {
    catalogController.current?.abort();
    targetCursorRef.current = undefined;
    setTargetOptions([]); setTargetCursor(undefined); setTargetSearch(''); setTargetCatalogError('');
    if (packageId && isSelectedScope(scope)) void loadTargets(false, '');
    else setTargetCatalogState('idle');
    return () => catalogController.current?.abort();
  }, [packageId, scope, catalogRefresh, loadTargets]);

  const requestKey = JSON.stringify([scope, packageId, environment, target, route.resource?.type, route.resource?.resourceId,
    route.resource?.logicalId, workspace.authContextKey, workspace.inspectionSessionEpoch, workspace.accessCapabilities.operate]);
  currentRequestKey.current = requestKey;
  useEffect(() => {
    requestController.current?.abort();
    requestSequence.current++;
    setRequesting(false); setReportResult(null); setRequestError(null); setNotice('');
    return () => requestController.current?.abort();
  }, [requestKey]);

  const knownPackage = packages.status === 'ready' && packages.data.some(item => item.packageId === packageId);
  const capabilitySupported = serverVersion.status === 'ready' && serverVersion.data.validationApiVersion === VALIDATION_API_VERSION;
  const environmentKnown = !environment || environmentProfiles.some(profile => profile.name === environment);
  const needsPackage = scope !== 'config' || Boolean(packageId);
  const targetOption = targetOptions.find(item => item.value === target);
  const resourceForReport = targetOption?.resource || route.resource;
  const targetRequired = isSelectedScope(scope);
  const canRun = capabilitySupported && workspace.accessCapabilities.operate && packages.status === 'ready'
    && (!needsPackage || knownPackage) && (!needsPackage || environmentStatus === 'ready')
    && environmentKnown && (!targetRequired || (Boolean(target) && (targetCatalogState === 'ready' || Boolean(route.target)))) && !requesting;

  function selectScope(value: ValidationScope) {
    onRouteChange({ kind: 'validation', scope: value, packageId: packageId || undefined,
      environment: packageId ? environment : undefined });
  }
  function selectPackage(value: string) {
    onRouteChange({ kind: 'validation', scope, packageId: value || undefined, environment: '', target: undefined, resource: undefined });
  }
  function selectEnvironment(value: string) {
    routeUpdate({ environment: value, target: target || undefined, resource: route.resource });
  }
  function selectTarget(option: TargetOption | undefined) {
    routeUpdate({ target: option?.value, resource: option?.resource });
  }

  function requestInput(): ValidationRequest {
    const environmentInput = environment ? { environment } : {};
    if (scope === 'config') return { targetKind: 'config', ...(packageId ? { packageId } : {}), ...(packageId ? environmentInput : {}) };
    if (scope === 'package') return { targetKind: 'package', packageId, ...environmentInput };
    return { targetKind: scope, packageId, target, ...environmentInput };
  }

  async function runValidation(event?: FormEvent) {
    event?.preventDefault();
    if (!canRun) return;
    requestController.current?.abort();
    const controller = new AbortController(); requestController.current = controller;
    const sequence = ++requestSequence.current;
    const submittedKey = requestKey;
    setReportResult(null); setRequestError(null); setNotice(''); setRequesting(true);
    try {
      const report = await api.validateResource(requestInput(), controller.signal);
      if (!controller.signal.aborted && sequence === requestSequence.current && currentRequestKey.current === submittedKey) {
        setReportResult({ key: submittedKey, report });
      }
    } catch (error) {
      if (!controller.signal.aborted && sequence === requestSequence.current && currentRequestKey.current === submittedKey) {
        const failure = validationFailure(error, locale);
        setRequestError({ key: submittedKey, ...failure });
      }
    } finally {
      if (sequence === requestSequence.current && currentRequestKey.current === submittedKey) setRequesting(false);
    }
  }

  function cancelValidation() {
    requestSequence.current++;
    requestController.current?.abort(); requestController.current = null;
    setRequesting(false); setReportResult(null); setRequestError(null);
    setNotice(text('Validation was cancelled. Run it again to get a current result.', '已取消驗證。請再次執行以取得目前結果。', locale));
  }

  function onTargetSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') { event.preventDefault(); void loadTargets(false, targetSearch); }
  }

  const report = reportResult?.key === requestKey ? reportResult.report : null;
  const error = requestError?.key === requestKey ? requestError : null;
  const profileUnavailable = Boolean(packageId && environment && environmentStatus === 'ready' && !environmentKnown);
  const selectedPackageName = packages.status === 'ready' ? packages.data.find(item => item.packageId === packageId)?.name : undefined;
  const optionsForSelect = useMemo(() => {
    const options = targetOptions.slice();
    if (target && !options.some(item => item.value === target)) options.unshift({ value: target, label: `${target} · ${text('from route', '來自路由', locale)}`, resource: route.resource });
    return options;
  }, [targetOptions, target, route.resource, locale]);

  return <main className="page-content att-validation-page">
    <header className="att-feature-header">
      <div><p className="att-eyebrow">{text('Read-only source validation', '唯讀來源驗證', locale)}</p>
        <h1>{text('Validate', '驗證', locale)}</h1>
        <p>{text('Validate the current Server Runtime Config, a registered Package, or one selected resource. This workspace does not create a RUN, LOAD, Debug draft, Job, Schedule, or snapshot.', '驗證目前 Server Runtime Config、已註冊套件或所選資源。此工作區不會建立 RUN、LOAD、Debug draft、Job、Schedule 或 snapshot。', locale)}</p></div>
    </header>

    {serverVersion.status === 'loading' && <p className="att-validation-help" role="status">{text('Checking Server validation capability…', '正在檢查 Server 驗證 capability…', locale)}</p>}
    {serverVersion.status === 'error' && <div className="att-validation-gate" role="alert">
      <strong>{text('Server capability could not be checked.', '無法檢查 Server capability。', locale)}</strong>
      <span>{text('Reconnect to ATT Server and refresh this page before validating.', '請重新連線 ATT Server 並重新整理此頁面後再驗證。', locale)}</span>
    </div>}
    {serverVersion.status === 'ready' && !capabilitySupported && <div className="att-validation-gate" role="alert">
      <strong>{text('Read-only validation is unavailable on this Server.', '此 Server 尚未支援唯讀驗證。', locale)}</strong>
      <span>{text(`This workspace requires ${VALIDATION_API_VERSION}. Update the Server to a build that advertises validationApiVersion in GET /api/v1/version.`, `此工作區需要 ${VALIDATION_API_VERSION}。請更新至 GET /api/v1/version 會宣告 validationApiVersion 的 Server build。`, locale)}</span>
    </div>}
    {!workspace.accessCapabilities.operate && <div className="att-validation-gate" role="alert">{text('Your account does not have operational access to validate resources.', '您的帳戶沒有驗證資源的操作權限。', locale)}</div>}

    <form className="att-validation-form" onSubmit={event => void runValidation(event)}>
      <div className="att-validation-fields">
        <label>{text('Scope', '範圍', locale)}<select aria-label={text('Validation scope', '驗證範圍', locale)} value={scope} onChange={event => selectScope(event.currentTarget.value as ValidationScope)}>
          {SCOPES.map(option => <option key={option.value} value={option.value}>{text(option.label, ({ 'Shared / Effective Config': '共用／生效設定', Package: '套件', Case: 'Case', Suite: 'Suite', Template: 'Template', Flow: 'Flow', Tool: 'Tool', 'LOAD Scenario': 'LOAD 情境' } as Record<string, string>)[option.label], locale)}</option>)}
        </select></label>
        {(scope === 'config' || scope === 'package' || targetRequired) && <label>{text('Registered Package', '已註冊套件', locale)}<select aria-label={text('Validation Package', '驗證套件', locale)} value={packageId} onChange={event => selectPackage(event.currentTarget.value)}>
          {scope === 'config' && <option value="">{text('Global Server Runtime Config', '全域 Server 執行階段設定', locale)}</option>}
          {scope !== 'config' && !packageId && <option value="">{text('Choose a registered Package', '選擇已註冊套件', locale)}</option>}
          {packages.status === 'ready' && packages.data.map(item => <option key={item.packageId} value={item.packageId}>{item.name ? `${item.name} (${item.packageId})` : item.packageId}</option>)}
        </select></label>}
        {needsPackage && packageId && <label>{text('Environment', '環境', locale)}<select aria-label={text('Validation Environment', '驗證環境', locale)} value={environment} disabled={environmentStatus === 'loading' || environmentStatus === 'error'} onChange={event => selectEnvironment(event.currentTarget.value)}>
          <option value="">{text('Package default', '套件預設值', locale)}{!environment && effectiveEnvironment ? ` (${effectiveEnvironment})` : ''}</option>
          {environmentProfiles.map(profile => <option key={profile.name} value={profile.name}>{profile.name}{profile.default ? ` · ${text('default', '預設', locale)}` : ''}</option>)}
          {environment && !environmentProfiles.some(profile => profile.name === environment) && <option value={environment}>{environment} · {text('not active', '未啟用', locale)}</option>}
        </select></label>}
        {targetRequired && <div className="att-validation-target-picker">
          <label>{text('Target catalog search', '目標目錄搜尋', locale)}<div className="att-validation-search-row">
            <input type="search" aria-label={text('Search authorized targets', '搜尋已授權目標', locale)} value={targetSearch} onChange={event => setTargetSearch(event.currentTarget.value)} onKeyDown={onTargetSearchKeyDown} disabled={!packageId} />
            <button className="button button-quiet" type="button" disabled={!packageId || targetCatalogState === 'loading'} onClick={() => void loadTargets(false, targetSearch)}>{text('Search catalog', '搜尋目錄', locale)}</button>
          </div></label>
          <label>{text('Selected target', '所選目標', locale)}<select aria-label={text('Validation target', '驗證目標', locale)} value={target} onChange={event => selectTarget(optionsForSelect.find(option => option.value === event.currentTarget.value))} disabled={!packageId || targetCatalogState === 'loading' && !optionsForSelect.length}>
            <option value="">{text('Choose a target', '選擇目標', locale)}</option>
            {optionsForSelect.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select></label>
          {targetCatalogState === 'loading' && <p role="status">{text('Loading the authorized target catalog…', '正在載入已授權目標目錄…', locale)}</p>}
          {targetCatalogState === 'error' && <p className="att-error" role="alert">{targetCatalogError}</p>}
          {targetCatalogState === 'ready' && !targetOptions.length && <p>{scope === 'load'
            ? text('No executable LOAD Scenarios were returned. Policy-only templates are not valid targets.', '沒有可執行的 LOAD 情境。僅含政策的範本不是有效目標。', locale)
            : text('No targets were returned from the authorized catalog.', '已授權目錄沒有回傳目標。', locale)}</p>}
          {targetCursor && <button className="button button-quiet" type="button" disabled={targetCatalogState === 'loading'} onClick={() => void loadTargets(true, targetSearch)}>{text('Load more targets', '載入更多目標', locale)}</button>}
          <button className="button button-quiet" type="button" disabled={!packageId || targetCatalogState === 'loading'} onClick={() => { setReportResult(null); setRequestError(null); setCatalogRefresh(value => value + 1); }}>{text('Refresh target catalog', '重新整理目標目錄', locale)}</button>
        </div>}
      </div>
      {scope === 'config' && !packageId && <p className="att-validation-help">{text('No Package is selected, so this validates the global Server Runtime Config.', '未選擇套件，因此會驗證全域 Server Runtime Config。', locale)}</p>}
      {scope === 'config' && packageId && <p className="att-validation-help">{text('This validates the selected Package-effective Config. Protected central source values remain subject to Server policy.', '此操作會驗證所選套件的生效設定。受保護的中央來源值仍受 Server policy 控制。', locale)}</p>}
      {scope === 'package' && <p className="att-validation-help">{text('Package validation checks the registered Package and its static resource/dependency closure for the selected Environment.', '套件驗證會針對所選環境檢查已註冊套件及其靜態資源／相依性閉包。', locale)}</p>}
      {environmentStatus === 'error' && packageId && <p className="att-error" role="alert">{text('Package Environments could not be loaded. Refresh the page or check Package access.', '無法載入套件環境。請重新整理頁面或檢查套件權限。', locale)}</p>}
      {profileUnavailable && <p className="att-error" role="alert">{text('The selected Environment is not an active Package profile. Choose an active Environment or Package default.', '所選環境不是作用中的套件 profile。請選擇作用中的環境或套件預設值。', locale)}</p>}
      {packages.status === 'loading' && needsPackage && <p role="status">{text('Loading registered Packages…', '正在載入已註冊套件…', locale)}</p>}
      {packages.status === 'error' && needsPackage && <p className="att-error" role="alert">{text('Registered Packages could not be loaded.', '無法載入已註冊套件。', locale)}</p>}
      {packageId && packages.status === 'ready' && !knownPackage && <p className="att-error" role="alert">{text('The selected Package is not registered or available to this account.', '所選套件未註冊或此帳戶無法使用。', locale)}</p>}
      {selectedPackageName && <p className="att-validation-help">{text('Validating registered Package', '正在驗證已註冊套件', locale)}: {selectedPackageName} ({packageId})</p>}
      <div className="att-validation-submit-row">
        <button className="button button-primary" type="submit" disabled={!canRun}>{requesting ? text('Validating…', '正在驗證…', locale) : text('Run Validation', '執行驗證', locale)}</button>
        {requesting && <button className="button button-quiet" type="button" onClick={cancelValidation}>{text('Cancel validation', '取消驗證', locale)}</button>}
      </div>
    </form>

    {notice && <p className="att-validation-notice" role="status">{notice}</p>}
    {error && <div className="att-validation-error" role="alert"><strong>{error.message}</strong>{error.requestId && <small>{text('Server request ID', 'Server 請求 ID', locale)}: {error.requestId}</small>}</div>}
    {requesting && <p role="status" aria-live="polite">{text('The Server is running one bounded, read-only validation request…', 'Server 正在執行單一有界唯讀驗證請求…', locale)}</p>}
    {report && <ValidationReport report={report} scope={scope} packageId={packageId || undefined} environment={environment || undefined}
      effectiveEnvironment={typeof report.context?.environment === 'string' ? report.context.environment : effectiveEnvironment}
      target={target || undefined} resourceRoute={resourceForReport} locale={locale} onRevalidate={() => void runValidation()}
      onOpenResource={resourceForReport && packageId ? () => onOpenResource?.(packageId, resourceForReport, environment || effectiveEnvironment || undefined) : undefined} />}
  </main>;
}
