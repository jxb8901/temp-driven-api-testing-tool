import { lazy, Suspense, useCallback, useEffect, useReducer, useRef, useState, type ReactNode } from 'react';
import { api } from './api';
import { AppShell, useAppShellContext } from './AppShell';
import { DashboardPage } from './DashboardPage';
import { formatRoute, parseRoute, useNavigationGuard, useRouteState, type AppRoute, type ValidationRouteState, type ValidationScope } from './router';
import { appReducer, initialAppState, latestServerJobWindow, serverCapabilities } from './state';
import { RunPage } from './features/run/RunPage';
// explorer.css stays eager: TypedSidecarForm and ConfigurationExplorer use it
// outside the lazy Explorer route, and moving it would change cascade order.
import './features/explorer/explorer.css';
const ExplorerPage = lazy(() => import('./features/explorer/ExplorerPage').then(module => ({ default: module.ExplorerPage })));
import type { ExplorerPageProps, ResourceSourceReference } from './features/explorer/types';
const LoadPage = lazy(() => import('./features/load/LoadPage').then(module => ({ default: module.LoadPage })));
const JobsPage = lazy(() => import('./features/load/JobsPage').then(module => ({ default: module.JobsPage })));
const MonitoringPage = lazy(() => import('./features/monitoring/MonitoringPage').then(module => ({ default: module.MonitoringPage })));
const ValidationWorkspacePage = lazy(() => import('./features/validation/ValidationWorkspacePage').then(module => ({ default: module.ValidationWorkspacePage })));
const ValidationPanel = lazy(() => import('./features/validation/ValidationPanel').then(module => ({ default: module.ValidationPanel })));
const QuickLoadPage = lazy(() => import('./features/load/QuickLoadPage').then(module => ({ default: module.QuickLoadPage })));
const LoadCatalogPage = lazy(() => import('./features/load/LoadCatalogPage').then(module => ({ default: module.LoadCatalogPage })));
const LoadResourceDetailPage = lazy(() => import('./features/load/LoadResourceDetailPage').then(module => ({ default: module.LoadResourceDetailPage })));
const StandaloneJobArtifactPage = lazy(() => import('./features/load/StandaloneJobArtifactPage').then(module => ({ default: module.StandaloneJobArtifactPage })));
import type { LoadJobsApi, PackageOption } from './features/load/types';
const SettingsPage = lazy(() => import('./SettingsPage').then(module => ({ default: module.SettingsPage })));
import type { ValidationRecord, ValidationRequest } from './features/validation/types';
const ConfigurationWorkspace = lazy(() => import('./features/explorer/ConfigurationWorkspace').then(module => ({ default: module.ConfigurationWorkspace })));
const PackageManifestWorkspace = lazy(() => import('./features/explorer/PackageManifestWorkspace').then(module => ({ default: module.PackageManifestWorkspace })));
const PackageTestdataWorkspace = lazy(() => import('./features/explorer/PackageTestdataCatalog').then(module => ({ default: module.PackageTestdataWorkspace })));
const PackageSafeSourceFileViewer = lazy(() => import('./features/explorer/PackageSafeSourceFileViewer').then(module => ({ default: module.PackageSafeSourceFileViewer })));
const LoadTestingPage = lazy(() => import('./features/load/LoadTestingPage').then(module => ({ default: module.LoadTestingPage })));
const SavedLoadScenarios = lazy(() => import('./features/load/SavedLoadScenarios').then(module => ({ default: module.SavedLoadScenarios })));
const LoadTemplateBuilder = lazy(() => import('./features/load/LoadTemplateBuilder').then(module => ({ default: module.LoadTemplateBuilder })));
import type { ValidatedScheduleIntent } from './features/schedules/types';

const ScheduledLoadPage = lazy(() => import('./features/schedules/ScheduledLoadPage').then(module => ({ default: module.ScheduledLoadPage })));
const StandaloneJobConsolePage = lazy(() => import('./features/load/StandaloneJobConsolePage').then(module => ({ default: module.StandaloneJobConsolePage })));

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('The ATT Server request could not be completed.');
}

const loadJobsApi: LoadJobsApi = api;
const CONTEXT_VALIDATIONS_STORAGE_KEY = 'att.validation.contexts.v1';
const MAX_REMEMBERED_VALIDATION_CONTEXTS = 32;
type ContextValidation = { routeKey: string; request: ValidationRequest; title: string; submitOnOpen: boolean };

function isContextValidation(value: unknown): value is ContextValidation {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<ContextValidation>;
  if (typeof item.routeKey !== 'string' || !item.routeKey || item.routeKey.length > 4096
      || /[\u0000-\u001f\u007f]/.test(item.routeKey)
      || typeof item.title !== 'string' || !item.title || item.title.length > 1024
      || !item.request || typeof item.request !== 'object') return false;
  const request = item.request as { targetKind?: unknown; packageId?: unknown; environment?: unknown; target?: unknown };
  const scope = String(request.targetKind);
  if (!['config', 'package', 'case', 'suite', 'template', 'flow', 'tool', 'load'].includes(scope)) return false;
  if (request.packageId !== undefined && (typeof request.packageId !== 'string' || request.packageId.length > 128)) return false;
  if (request.environment !== undefined && (typeof request.environment !== 'string' || request.environment.length > 32)) return false;
  if (request.target !== undefined && (typeof request.target !== 'string' || request.target.length > 512)) return false;
  if (scope !== 'config' && (typeof request.packageId !== 'string' || !request.packageId)) return false;
  if (['case', 'suite', 'template', 'flow', 'tool', 'load'].includes(scope)
      && (typeof request.target !== 'string' || !request.target)) return false;
  if (['config', 'package'].includes(scope) && request.target !== undefined) return false;
  return true;
}

function contextValidationStorageKey(authContextKey: string): string {
  return `${CONTEXT_VALIDATIONS_STORAGE_KEY}.${encodeURIComponent(authContextKey)}`;
}

function readRememberedValidationContexts(storageKey: string): ContextValidation[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(storageKey) || '[]');
    if (!Array.isArray(parsed)) return [];
    return uniqueContextValidations(parsed.filter(isContextValidation).map(item => ({ ...item, submitOnOpen: false })));
  } catch { return []; }
}

function contextValidationKey(value: Pick<ContextValidation, 'routeKey' | 'request'>): string {
  return `${value.routeKey}\n${JSON.stringify(value.request)}`;
}

function uniqueContextValidations(values: ContextValidation[]): ContextValidation[] {
  const seen = new Set<string>();
  return [...values].reverse().filter(value => {
    const key = contextValidationKey(value);
    if (seen.has(key)) return false;
    seen.add(key);return true;
  }).reverse().slice(-MAX_REMEMBERED_VALIDATION_CONTEXTS);
}

export function validationOriginRoute(record: ValidationRecord, contexts: ContextValidation[] = []): AppRoute | undefined {
  const savedContext = [...contexts].reverse().find(({ request }) => {
    if ('all' in request) return Boolean(record.all);
    return !record.all && request.targetKind === record.scope
      && (request.packageId || undefined) === (record.packageId || undefined)
      && (('target' in request ? request.target : undefined) || undefined) === (record.target || undefined)
      && (!('environment' in request) || !request.environment || request.environment === record.environment);
  });
  if (savedContext) {
    const savedRoute = parseRoute(savedContext.routeKey);
    const environment = record.environment;
    if (!environment) return savedRoute;
    switch (savedRoute.kind) {
      case 'package':
      case 'configuration':
      case 'quick-load':
      case 'run':
      case 'load-resource':
      case 'validation':
        return { ...savedRoute, environment };
      case 'load':
        return savedRoute.packageId
          ? { ...savedRoute, environment }
          : { ...savedRoute, catalog: { ...savedRoute.catalog, environment } };
      default:
        return savedRoute;
    }
  }
  const environment = record.environment || '';
  if (record.scope === 'config') return { kind: 'configuration', ...(record.packageId ? { packageId: record.packageId } : {}), environment, tab: 'effective' };
  if (!record.packageId) return undefined;
  if (record.scope === 'package') return { kind: 'package', packageId: record.packageId, environment };
  if (['case', 'template', 'flow', 'tool'].includes(record.scope) && record.target) {
    return { kind: 'package', packageId: record.packageId, environment,
      resource: { type: record.scope, resourceId: record.target } };
  }
  if (record.scope === 'load') return { kind: 'load', tab: 'scenarios', catalog: {
    query: record.target || '', packageFilter: record.packageId, environment,
  } };
  if (record.scope === 'suite' && record.target) return { kind: 'validation', scope: 'suite', packageId: record.packageId, target: record.target, environment };
  return { kind: 'package', packageId: record.packageId, environment };
}

type ValidationContextState = {
  contextValidation?: ContextValidation;
  contextValidations: ContextValidation[];
  onValidateContext(request: ValidationRequest, title: string): void;
  rememberContextValidation(record: ValidationRecord): void;
  closeContextValidation(): void;
};

function ValidationContextCache({ routeKey, supportsValidationRecords, navigate, children }: {
  routeKey: string;
  supportsValidationRecords: boolean;
  navigate(route: AppRoute): void;
  children(state: ValidationContextState): ReactNode;
}) {
  const shell = useAppShellContext();
  const authContextKey = shell.authContextKey || '';
  const identityReady = Boolean(authContextKey && !authContextKey.startsWith('auth:') && shell.accessCapabilities.operate);
  const storageKey = identityReady ? contextValidationStorageKey(authContextKey) : '';
  const [saved, setSaved] = useState<{ owner: string | null; values: ContextValidation[] }>(() => identityReady
    ? { owner: authContextKey, values: readRememberedValidationContexts(storageKey) }
    : { owner: null, values: [] });
  const cacheReady = identityReady && saved.owner === authContextKey;
  const contextValidations = cacheReady ? saved.values : [];
  const contextValidation = [...contextValidations].reverse().find(value => value.routeKey === routeKey);
  const previousRouteKey = useRef(routeKey);

  useEffect(() => {
    try { localStorage.removeItem(CONTEXT_VALIDATIONS_STORAGE_KEY); } catch { /* Legacy unscoped records are never restored. */ }
  }, []);
  useEffect(() => {
    if (!identityReady) { setSaved({ owner: null, values: [] }); return; }
    setSaved({ owner: authContextKey, values: readRememberedValidationContexts(storageKey) });
  }, [authContextKey, identityReady, storageKey]);
  useEffect(() => {
    if (!cacheReady) return;
    try {
      localStorage.setItem(storageKey, JSON.stringify(saved.values.map(value => ({ ...value, submitOnOpen: false }))));
    } catch { /* Server-owned validation records remain recoverable from their contextual action. */ }
  }, [cacheReady, saved.owner, saved.values, storageKey]);
  useEffect(() => {
    if (previousRouteKey.current !== routeKey) {
      previousRouteKey.current = routeKey;
      setSaved(current => current.owner === authContextKey
        ? { ...current, values: current.values.map(value => value.submitOnOpen ? { ...value, submitOnOpen: false } : value) }
        : current);
    }
  }, [authContextKey, routeKey]);

  const onValidateContext = useCallback((request: ValidationRequest, title: string) => {
    if (supportsValidationRecords) {
      if (!cacheReady) return;
      const value: ContextValidation = { routeKey, request, title, submitOnOpen: true };
      const key = contextValidationKey(value);
      setSaved(current => current.owner === authContextKey
        ? { ...current, values: uniqueContextValidations([...current.values.filter(item => contextValidationKey(item) !== key), value]) }
        : current);
      return;
    }
    if ('all' in request) return;
    navigate({ kind: 'validation', scope: request.targetKind,
      ...(request.packageId ? { packageId: request.packageId } : {}),
      ...(request.environment ? { environment: request.environment } : {}),
      ...('target' in request ? { target: request.target } : {}) });
  }, [authContextKey, cacheReady, navigate, routeKey, supportsValidationRecords]);
  const rememberContextValidation = useCallback((record: ValidationRecord) => {
    if (!cacheReady || !record.environment) return;
    setSaved(current => current.owner === authContextKey ? { ...current, values: uniqueContextValidations(current.values.map(value => {
      const request = value.request;
      if ('all' in request || request.environment || request.targetKind !== record.scope
          || (request.packageId || undefined) !== record.packageId
          || (('target' in request ? request.target : undefined) || undefined) !== record.target) return value;
      return { ...value, request: { ...request, environment: record.environment }, submitOnOpen: false };
    })) } : current);
  }, [authContextKey, cacheReady]);
  const closeContextValidation = useCallback(() => {
    if (!cacheReady || !contextValidation) return;
    const key = contextValidationKey(contextValidation);
    setSaved(current => current.owner === authContextKey
      ? { ...current, values: current.values.filter(value => contextValidationKey(value) !== key) }
      : current);
  }, [authContextKey, cacheReady, contextValidation]);

  return <>{children({ contextValidation, contextValidations, onValidateContext, rememberContextValidation, closeContextValidation })}</>;
}

function ExplorerRouteView({ route, inlineLoadEnabled, schedulingEnabled, onSchedule, packageRegistrationState, registered, packageLabel,
  onValidateContext, canManagePackageSource }: {
  route: Extract<AppRoute, { kind: 'package' }>;
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  onSchedule(input: ValidatedScheduleIntent): boolean;
  packageRegistrationState: 'loading' | 'ready' | 'error';
  registered: boolean;
  packageLabel: string;
  onValidateContext(request: ValidationRequest, title: string): void;
  canManagePackageSource: boolean;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const packageId = route.packageId;
  const resourceRoute = route.resource || null;
  const environment = route.environment ?? (packageId === workspace.selectedPackage ? workspace.selectedEnvironment : '');
  const onResourceRouteChange = useCallback((resource: ExplorerPageProps['resourceRoute']) => {
    navigation.navigate(resource
      ? { kind: 'package', packageId, resource, environment }
      : { kind: 'package', packageId, environment });
  }, [navigation.navigate, packageId, environment]);
  const onTestdataEnvironmentChange = useCallback((nextEnvironment: string) => {
    navigation.navigate({ ...route, environment: nextEnvironment });
  }, [navigation.navigate, route]);
  const onTestdataDescriptorChange = useCallback((descriptorId?: string) => {
    navigation.navigate({ ...route, testdataDescriptorId: descriptorId });
  }, [navigation.navigate, route]);
  const onOpenTestdata = useCallback(() => {
    navigation.navigate({ kind: 'package', packageId, view: 'testdata', environment });
  }, [navigation.navigate, packageId, environment]);
  const onOpenPackageConfiguration = useCallback(() => {
    navigation.navigate({ kind: 'configuration', packageId, environment, view: 'package-manifest' });
  }, [navigation.navigate, packageId, environment]);
  const onOpenTestdataResource = useCallback((resource: { type: string; resourceId: string }) => {
    navigation.navigate({ kind: 'package', packageId, resource, environment });
  }, [navigation.navigate, packageId, environment]);
  const onJobAccepted = useCallback((jobId: string) => {
    workspace.onJobAccepted({ jobId, packageId });
  }, [packageId, workspace.onJobAccepted]);
  const onOpenQuickLoad = useCallback((identity: { packageId: string; type: string; resourceId: string; logicalId?: string }) => {
    navigation.navigate({ kind: 'quick-load', packageId: identity.packageId,
      target: { type: identity.type, resourceId: identity.resourceId, logicalId: identity.logicalId }, environment });
  }, [navigation.navigate, environment]);
  const onRunTarget = useCallback((identity: { packageId: string; type: string; resourceId: string; logicalId?: string }) => {
    navigation.navigate({ kind: 'run', packageId: identity.packageId,
      target: { type: identity.type, resourceId: identity.resourceId, logicalId: identity.logicalId }, environment });
  }, [navigation.navigate, environment]);
  const onRunBatch = useCallback(() => {
    navigation.navigate({ kind: 'run', packageId, environment, batch: true });
  }, [navigation.navigate, packageId, environment]);
  const onValidateTarget = useCallback((scope: ValidationScope, target: string) => {
    onValidateContext({ targetKind: scope as 'case' | 'suite' | 'template' | 'flow' | 'tool', packageId,
      ...(environment ? { environment } : {}), target }, `${scope === 'suite' ? 'Suite' : scope} · ${target}`);
  }, [onValidateContext, packageId, environment]);
  const onValidatePackage = useCallback(() => {
    onValidateContext({ targetKind: 'package', packageId, ...(environment ? { environment } : {}) }, `Package · ${packageLabel}`);
  }, [onValidateContext, packageId, packageLabel, environment]);
  const onScheduleDebug = useCallback<NonNullable<ExplorerPageProps['onScheduleDebug']>>((input) => {
    return onSchedule(input);
  }, [onSchedule]);

  const contextNotice = packageId !== workspace.selectedPackage && <div className="page-content"><p>Browsing Package {packageId}; Workspace is {workspace.selectedPackage || 'unselected'}.</p><button type="button" onClick={() => workspace.onPackageChange(packageId)}>Use this Package as Workspace</button></div>;
  if (route.view === 'testdata') return <>
    {contextNotice}
    <PackageTestdataWorkspace api={api} packageId={packageId} packageLabel={packageLabel} registrationState={packageRegistrationState} registered={registered}
      environment={environment} descriptorId={route.testdataDescriptorId} onEnvironmentChange={onTestdataEnvironmentChange}
      onDescriptorChange={onTestdataDescriptorChange} onBackToResources={() => navigation.navigate({ kind: 'package', packageId, environment })}
      onOpenResource={onOpenTestdataResource} onOpenPackageConfiguration={onOpenPackageConfiguration}
      canManagePackageSource={canManagePackageSource} registerUnsavedChanges={navigation.registerUnsavedChanges} />
  </>;
  if (route.view === 'source-file') return <>
    {contextNotice}
    <PackageSafeSourceFileViewer api={api} packageId={packageId} filePath={route.filePath} fileOrigin={route.fileOrigin}
      sourceReference={route.sourceReference} locale={workspace.uiLocale} canManagePackageSource={canManagePackageSource}
      onBack={() => navigation.navigate(route.returnTo || formatRoute({ kind: 'package', packageId,
        ...(route.sourceReference ? { resource: { type: route.sourceReference.type, resourceId: route.sourceReference.resourceId, tab: route.sourceReference.returnTab || 'source' as const, ...(route.sourceReference.actionId ? { actionId: route.sourceReference.actionId } : {}) } } : {}) }))}
      registerUnsavedChanges={navigation.registerUnsavedChanges}
      onOpenFile={(path, origin, sourceReference) => navigation.navigate({ kind: 'package', packageId, view: 'source-file', environment,
        filePath: path, fileOrigin: origin, sourceReference: { ...sourceReference, returnTab: route.sourceReference?.returnTab || 'source', ...(route.sourceReference?.actionId ? { actionId: route.sourceReference.actionId } : {}) }, returnTo: formatRoute(route) })} />
  </>;
  return <>
    {contextNotice}
    <ExplorerPage key={packageId}
    api={api}
    packageId={packageId}
    resourceRoute={resourceRoute}
    environment={environment}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
    onResourceRouteChange={onResourceRouteChange}
    onJobAccepted={onJobAccepted}
    onRunTarget={target => onRunTarget({ packageId, ...target })}
    onValidateTarget={(scope, target) => onValidateTarget(scope, target)}
    onValidatePackage={onValidatePackage}
    onRunBatch={onRunBatch}
    onOpenQuickLoad={inlineLoadEnabled ? onOpenQuickLoad : undefined}
    onScheduleDebug={schedulingEnabled ? onScheduleDebug : undefined}
    onOpenConfigurationFile={(path, origin, sourceReference) => {
      if (sourceReference) navigation.navigate({ kind: 'package', packageId, view: 'source-file', environment, filePath: path, fileOrigin: origin,
        sourceReference, returnTo: formatRoute({ kind: 'package', packageId, environment,
          ...(resourceRoute ? { resource: { ...resourceRoute, tab: sourceReference.returnTab || 'source', ...(sourceReference.actionId ? { actionId: sourceReference.actionId } : {}) } } : {}) }) });
      else navigation.navigate({ kind: 'configuration', packageId, environment, filePath: path, fileOrigin: origin, tab: 'source' });
    }}
    onOpenTestdata={onOpenTestdata}
    onOpenPackageConfiguration={onOpenPackageConfiguration}
    canManagePackageSource={canManagePackageSource}
  /></>;
}

function LoadRouteView({ packageId, templateId, environment: initialEnvironment, replayDraftId, replaySourceJobId, inlineLoadEnabled, schedulingEnabled, packages, onSchedule }: {
  packageId: string;
  environment?: string;
  replayDraftId?: string;
  replaySourceJobId?: string;
  templateId?: string;
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  packages: PackageOption[];
  onSchedule(input: ValidatedScheduleIntent): void;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const [routeEnvironment,setRouteEnvironment] = useState(initialEnvironment ?? (replaySourceJobId || packageId !== workspace.selectedPackage ? '' : workspace.selectedEnvironment));
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const onPackageChange = useCallback((nextPackageId: string) => {
    navigation.navigate({ kind: 'load', packageId: nextPackageId });
  }, [navigation.navigate]);
  const onEnvironmentChange = useCallback((environment:string) => {
    setRouteEnvironment(environment);
  },[]);
  useEffect(()=>{if(initialEnvironment!==undefined)setRouteEnvironment(initialEnvironment);},[initialEnvironment]);
  const onJobAccepted = useCallback((jobId: string) => {
    workspace.onJobAccepted({ jobId, packageId });
  }, [packageId, workspace.onJobAccepted]);

  useEffect(() => {
    const controller = new AbortController();
    setEnvironmentState('loading');
    api.getDeclaredConfiguration(packageId, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      const names = (configuration.environments || []).filter(profile => profile.name && profile.state === 'active').map(profile => profile.name);
      setEnvironments(names);
      setEnvironmentState('ready');
      if (routeEnvironment && !names.includes(routeEnvironment)) {
        setRouteEnvironment('');
      }
    }).catch(() => {
      if (!controller.signal.aborted) {
        setEnvironments([]);
        setEnvironmentState('error');
      }
    });
    return () => controller.abort();
  }, [packageId, environmentReload, routeEnvironment]);

  return <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Load…</div>}>
    {templateId ? <LoadTemplateBuilder templateId={templateId} api={loadJobsApi} packages={packages} selectedPackage={packageId} selectedEnvironment={routeEnvironment} environments={environments} environmentState={environmentState} inlineLoadEnabled={inlineLoadEnabled} schedulingEnabled={schedulingEnabled} onPackageChange={onPackageChange} onEnvironmentChange={onEnvironmentChange} onJobAccepted={onJobAccepted} onSchedule={onSchedule} /> : <LoadPage
      api={loadJobsApi}
      packages={packages}
      selectedPackage={packageId}
      selectedEnvironment={routeEnvironment}
      environments={environments}
      environmentState={environmentState}
      inlineLoadEnabled={inlineLoadEnabled}
      schedulingEnabled={schedulingEnabled}
      replayDraftId={replayDraftId}
      replaySourceJobId={replaySourceJobId}
      onRetryEnvironments={() => setEnvironmentReload(value => value + 1)}
      onPackageChange={onPackageChange}
      onEnvironmentChange={onEnvironmentChange}
      onJobAccepted={onJobAccepted}
      onSchedule={onSchedule}
    />}
  </Suspense>;
}

function ConfigurationRouteView({ route, onValidateContext }: {
  route: Extract<ReturnType<typeof useRouteState>['route'], { kind: 'configuration' }>;
  onValidateContext(request: ValidationRequest, title: string): void;
}) {
  const workspace = useAppShellContext();
  const [showManifest, setShowManifest] = useState(route.view === 'package-manifest');
  const [manifestDirty, setManifestDirty] = useState(false);
  useEffect(() => { setShowManifest(route.view === 'package-manifest'); }, [route.filePath, route.packageId, route.view]);
  const navigation = useNavigationGuard();
  const selectedEnvironment = route.environment ?? (route.packageId === workspace.selectedPackage ? workspace.selectedEnvironment : '');
  const onEnvironmentChange = useCallback((environment: string) => {
    navigation.navigate({ ...route, environment });
  }, [navigation.navigate, route]);
  const onFileChange = useCallback((filePath?: string, fileOrigin?: string, sourceReference?: ResourceSourceReference, configurationReferenceId?: string) => {
    navigation.navigate({ ...route, filePath, fileOrigin, sourceReference, configurationReferenceId, tab: filePath ? 'source' : route.tab });
  }, [navigation.navigate, route]);
  const onTabChange = useCallback((tab: 'source' | 'declared' | 'effective' | 'compare') => {
    navigation.navigate({ ...route, tab });
  }, [navigation.navigate, route]);
  const onOpenResource = useCallback((resource: { type: string; resourceId: string }) => {
    navigation.navigate({ kind: 'package', packageId: route.packageId || '', resource });
  }, [navigation.navigate, route.packageId]);
  const onValidateConfiguration = useCallback(() => {
    onValidateContext({ targetKind: 'config' }, 'Shared Runtime Config');
  }, [onValidateContext]);
  const onValidatePackageConfiguration = useCallback(() => {
    if (!route.packageId) return;
    onValidateContext({ targetKind: 'config', packageId: route.packageId,
      ...(selectedEnvironment ? { environment: selectedEnvironment } : {}) }, `${route.packageId} effective Config`);
  }, [onValidateContext, route.packageId, selectedEnvironment]);
  return <>
    <nav className="configuration-scope" aria-label="Configuration ownership">
      <button type="button" aria-pressed={showManifest} disabled={!route.packageId} onClick={() => navigation.navigate({ ...route, view: 'package-manifest' })}>Package Configuration</button>
      <button type="button" aria-pressed={!showManifest} disabled={manifestDirty} onClick={() => navigation.navigate({ ...route, view: undefined })}>Shared Runtime Configuration</button>
    </nav>
    {showManifest && route.packageId ? <PackageManifestWorkspace key={route.packageId} api={api} packageId={route.packageId} registerUnsavedChanges={navigation.registerUnsavedChanges} onDirtyChange={setManifestDirty} /> : <ConfigurationWorkspace
    api={api}
    packageId={route.packageId || ''}
    environment={selectedEnvironment}
    filePath={route.filePath}
    fileOrigin={route.fileOrigin}
    sourceReference={route.sourceReference}
    configurationReferenceId={route.configurationReferenceId}
    canRevealAdminFields={workspace.canRevealConfigurationFields}
    canManageCentralConfiguration={workspace.accessCapabilities.systemSettingsWrite}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
    tab={route.tab || (route.filePath ? 'source' : 'declared')}
    onEnvironmentChange={onEnvironmentChange}
    onFileChange={onFileChange}
    onTabChange={onTabChange}
    onOpenResource={onOpenResource}
    onValidate={onValidateConfiguration}
    validationLabel={workspace.uiLocale === 'zh-Hant' ? '驗證共用設定' : 'Validate Shared Config'}
    validationHelp={workspace.uiLocale === 'zh-Hant'
      ? '唯讀檢查設定，不會執行測試或建立 Job。' : 'Read-only configuration inspection; it does not run tests or create a Job.'}
    onValidatePackage={route.packageId ? onValidatePackageConfiguration : undefined}
    packageValidationLabel={route.packageId ? (workspace.uiLocale === 'zh-Hant'
      ? '驗證生效設定' : 'Validate Effective Config') : undefined}
  />}</>;
}

function QuickLoadRouteView({ route, inlineLoadEnabled, schedulingEnabled, onSchedule }: {
  route: Extract<ReturnType<typeof useRouteState>['route'], { kind: 'quick-load' }>;
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  onSchedule(input: ValidatedScheduleIntent): void;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = route.environment ?? (route.packageId === workspace.selectedPackage ? workspace.selectedEnvironment : '');
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const [environmentNotice, setEnvironmentNotice] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setEnvironmentState('loading'); setEnvironmentNotice('');
    api.getDeclaredConfiguration(route.packageId, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      const names = (configuration.environments || []).filter(item => item.name && item.state === 'active').map(item => item.name);
      setEnvironments(names);
      setEnvironmentState('ready');
      if (selectedEnvironment && !names.includes(selectedEnvironment)) {
        setEnvironmentNotice(`Environment ${selectedEnvironment} is not an active profile in ${route.packageId}; Package default is selected.`);
        navigation.navigate({ ...route, environment: '' });
      }
    }).catch(() => { if (!controller.signal.aborted) { setEnvironments([]); setEnvironmentState('error'); } });
    return () => controller.abort();
  }, [route.packageId, environmentReload]);
  const onEnvironmentChange = useCallback((environment: string) => {
    const accepted = navigation.navigate({ ...route, environment });
    if (accepted) setEnvironmentNotice('');
  }, [navigation.navigate, route]);
  const onCancel = useCallback(() => navigation.navigate(route.target
    ? { kind: 'package', packageId: route.packageId, resource: route.target }
    : { kind: 'load' }), [navigation.navigate, route.packageId, route.target]);
  const onJobAccepted = useCallback((jobId: string) => workspace.onJobAccepted({ jobId, packageId: route.packageId }), [route.packageId, workspace.onJobAccepted]);
  return <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Quick Load…</div>}><QuickLoadPage
    api={loadJobsApi}
    packageId={route.packageId}
    target={route.target}
    initialTemplateId={route.templateId}
    initialModel={route.model}
    backLabel={route.target ? 'Back to Explorer' : 'Back to LOAD Catalog'}
    environment={selectedEnvironment}
    environments={environments}
    environmentState={environmentState}
    inlineLoadEnabled={inlineLoadEnabled}
    schedulingEnabled={schedulingEnabled}
    onRetryEnvironments={() => setEnvironmentReload(value => value + 1)}
    onEnvironmentChange={onEnvironmentChange}
    environmentNotice={environmentNotice}
    onJobAccepted={onJobAccepted}
    onSchedule={onSchedule}
    onCancel={onCancel}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
  /></Suspense>;
}

function LoadResourceRouteView({ route, inlineLoadEnabled }: {
  route: Extract<ReturnType<typeof useRouteState>['route'], { kind: 'load-resource' }>;
  inlineLoadEnabled: boolean;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = route.environment ?? (route.packageId === workspace.selectedPackage ? workspace.selectedEnvironment : '');
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const [environmentNotice, setEnvironmentNotice] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setEnvironmentState('loading'); setEnvironmentNotice('');
    api.getDeclaredConfiguration(route.packageId, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      const names = (configuration.environments || []).filter(item => item.name && item.state === 'active').map(item => item.name);
      setEnvironments(names); setEnvironmentState('ready');
      if (selectedEnvironment && !names.includes(selectedEnvironment)) {
        setEnvironmentNotice(`Environment ${selectedEnvironment} is not an active profile in ${route.packageId}; Package default is selected.`);
        navigation.navigate({ ...route, environment: '' });
      }
    }).catch(() => { if (!controller.signal.aborted) { setEnvironments([]); setEnvironmentState('error'); } });
    return () => controller.abort();
  }, [route.packageId, environmentReload]);
  const onEnvironmentChange = useCallback((environment: string) => {
    setEnvironmentNotice('');
    navigation.navigate({ ...route, environment });
  }, [navigation.navigate, route]);
  return <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading LOAD resource…</div>}><LoadResourceDetailPage api={api} packageId={route.packageId} resourceId={route.resourceId}
    environment={selectedEnvironment} environments={environments} environmentState={environmentState}
    environmentNotice={environmentNotice} onRetryEnvironments={() => setEnvironmentReload(value => value + 1)} inlineLoadEnabled={inlineLoadEnabled}
    onEnvironmentChange={onEnvironmentChange}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
    onBack={() => navigation.navigate(route.returnTo || '#/advanced-load')}
    onQuickLoad={(packageId, detail) => navigation.navigate({ kind: 'quick-load', packageId,
      templateId: detail.resourceId, model: detail.model || 'virtualUsers', environment: selectedEnvironment })} /></Suspense>;
}

export default function App() {
  const navigation = useRouteState();
  const [state, dispatch] = useReducer(appReducer, initialAppState);
  const [jobsRefreshing, setJobsRefreshing] = useState(false);
  const [jobsUpdatedAt, setJobsUpdatedAt] = useState<string | null>(null);
  const jobsRefreshCount = useRef(0);
  const jobsRequestSequence = useRef(0);
  // Workspace belongs to this tab; route and editor contexts never write it implicitly.
  const [workspaceContext, setWorkspaceContext] = useState<{packageId: string; environment: string}>(() => {
    try { const saved = JSON.parse(sessionStorage.getItem('att.workspace.context') || 'null');
      if (saved && typeof saved.packageId === 'string' && typeof saved.environment === 'string') return saved;
    } catch { /* Storage may be unavailable. */ }
    return { packageId: '', environment: '' };
  });
  const selectedPackage = workspaceContext.packageId;
  const selectedEnvironment = workspaceContext.environment;
  const [workspaceEnvironments, setWorkspaceEnvironments] = useState<string[]>([]);
  const [workspaceProfilesStatus, setWorkspaceProfilesStatus] = useState<'loading' | 'ready' | 'error'>('ready');
  const [workspaceResolution, setWorkspaceResolution] = useState<{key: string; environment?: string; error?: boolean}>({key: ''});
  const workspaceResolutionError = Boolean(selectedPackage && workspaceResolution.key === `${selectedPackage}\n${selectedEnvironment}` && workspaceResolution.error);
  const [catalogEnvironment, setCatalogEnvironment] = useState('');
  const currentRouteKey = formatRoute(navigation.route);
  const supportsValidationRecords = state.serverVersion.status === 'ready'
    && state.serverVersion.data.validationRecordsApiVersion === 'att-validation-records/v1';
  useEffect(() => { try { sessionStorage.setItem('att.workspace.context', JSON.stringify(workspaceContext)); } catch { /* In-memory context remains usable. */ } }, [workspaceContext]);
  useEffect(() => {
    const controller = new AbortController();
    setWorkspaceEnvironments([]);
    setWorkspaceProfilesStatus(selectedPackage ? 'loading' : 'ready');
    if (selectedPackage) api.getDeclaredConfiguration(selectedPackage, controller.signal).then(configuration => {
      if (!controller.signal.aborted) { setWorkspaceEnvironments((configuration.environments || []).filter(item => item.name && item.state === 'active').map(item => item.name)); setWorkspaceProfilesStatus('ready'); }
    }).catch(() => { if (!controller.signal.aborted) { setWorkspaceEnvironments([]); setWorkspaceProfilesStatus('error'); } });
    return () => controller.abort();
  }, [selectedPackage]);
  useEffect(() => {
    const controller = new AbortController();
    const key = `${selectedPackage}\n${selectedEnvironment}`;
    if (selectedPackage) api.getPackageManifest(selectedPackage, 'effective', selectedEnvironment || undefined, controller.signal).then(value => {
      if (!controller.signal.aborted) setWorkspaceResolution({key, environment: value.environment, error: !value.environment});
    }).catch(() => { if (!controller.signal.aborted) setWorkspaceResolution({key, error: true}); });
    return () => controller.abort();
  }, [selectedPackage, selectedEnvironment]);
  const changeWorkspacePackage = useCallback((packageId: string) => setWorkspaceContext(current => current.packageId === packageId ? current : {packageId, environment: ''}), []);
  const changeWorkspaceEnvironment = useCallback((environment: string) => setWorkspaceContext(current => ({...current, environment})), []);
  const [scheduleIntent, setScheduleIntent] = useState<ValidatedScheduleIntent | null>(null);
  const scheduleReturnRoute = useRef<AppRoute | null>(null);
  const onSchedule = useCallback((intent: ValidatedScheduleIntent): boolean => {
    scheduleReturnRoute.current = navigation.route;
    const accepted = navigation.navigate({ kind: 'schedules', view: 'new', intent: 'validated', returnTo: formatRoute(navigation.route) });
    if (!accepted) { scheduleReturnRoute.current = null; return false; }
    setScheduleIntent(intent);
    return true;
  }, [navigation.navigate, navigation.route]);
  const consumeScheduleIntent = useCallback(() => setScheduleIntent(null), []);
  const returnFromSchedule = useCallback(() => {
    setScheduleIntent(null);
    const route = scheduleReturnRoute.current;
    scheduleReturnRoute.current = null;
    navigation.navigate(route || (navigation.route.kind === 'schedules' && navigation.route.returnTo
      ? navigation.route.returnTo : { kind: 'schedules' }));
  }, [navigation.navigate, navigation.route]);
  const onScheduleSaved = useCallback(() => {
    setScheduleIntent(null);
    scheduleReturnRoute.current = null;
    navigation.navigate({ kind: 'schedules' });
  }, [navigation.navigate]);


  const loadPackages = useCallback(async (signal?: AbortSignal) => {
    dispatch({ type: 'packages/loading' });
    try {
      const response = await api.getPackages(signal);
      dispatch({ type: 'packages/ready', data: Array.isArray(response.items) ? response.items : [] });
    } catch (error) {
      if (!signal?.aborted) dispatch({ type: 'packages/error', error: asError(error) });
    }
  }, []);

  const loadJobs = useCallback(async (signal?: AbortSignal) => {
    const requestSequence = ++jobsRequestSequence.current;
    jobsRefreshCount.current++;
    setJobsRefreshing(true);
    dispatch({ type: 'jobs/loading' });
    try {
      const response = await api.getJobs(signal);
      if (!signal?.aborted && requestSequence === jobsRequestSequence.current) {
        dispatch({ type: 'jobs/ready', data: latestServerJobWindow(Array.isArray(response.items) ? response.items : []) });
        setJobsUpdatedAt(new Date().toISOString());
      }
    } catch (error) {
      if (!signal?.aborted && requestSequence === jobsRequestSequence.current) dispatch({ type: 'jobs/error', error: asError(error) });
    } finally {
      jobsRefreshCount.current = Math.max(0, jobsRefreshCount.current - 1);
      if (jobsRefreshCount.current === 0) setJobsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    dispatch({ type: 'version/loading' });
    api.getVersion(controller.signal).then(version => {
      if (version.apiVersion !== '1') throw new Error('Incompatible ATT Server API version: expected v1.');
      if (controller.signal.aborted) return;
      dispatch({ type: 'version/ready', data: version });
      if (navigation.route.kind !== 'job-console' && navigation.route.kind !== 'job-artifact' && !(navigation.route.kind === 'jobs' && navigation.route.standalone)) {
        void loadPackages(controller.signal);
        if (navigation.route.kind === 'dashboard') void loadJobs(controller.signal);
      }
    }).catch(error => {
      if (controller.signal.aborted) return;
      const reason = asError(error);
      dispatch({ type: 'version/error', error: reason });
      dispatch({ type: 'packages/error', error: reason });
      dispatch({ type: 'jobs/error', error: reason });
    });
    return () => controller.abort();
  }, [loadJobs, loadPackages, navigation.route.kind === 'job-console' || navigation.route.kind === 'job-artifact' || (navigation.route.kind === 'jobs' && !!navigation.route.standalone)]);

  const previousRouteKind = useRef(navigation.route.kind);
  useEffect(() => {
    const previous = previousRouteKind.current;
    previousRouteKind.current = navigation.route.kind;
    if (navigation.route.kind === 'dashboard' && previous !== 'dashboard') void loadJobs();
  }, [navigation.route.kind, loadJobs]);
  const hasActiveDashboardJobs = state.jobs.status === 'ready' && state.jobs.data.some(job =>
    ['QUEUED', 'PREPARING', 'RUNNING', 'CANCEL_REQUESTED'].includes(String(job.status).toUpperCase()));
  useEffect(() => {
    if (navigation.route.kind !== 'dashboard' || !hasActiveDashboardJobs) return;
    const timer = window.setInterval(() => void loadJobs(), 5000);
    return () => window.clearInterval(timer);
  }, [navigation.route.kind, hasActiveDashboardJobs, loadJobs]);

  const onPackageChange = useCallback((packageId: string) => {
    navigation.navigate({ kind: 'package', packageId });
  }, [navigation.navigate]);
  const onJobChange = useCallback((jobId: string) => {
    navigation.navigate({ kind: 'jobs', jobId, ...(navigation.route.kind === 'jobs' && navigation.route.executionType ? { executionType: navigation.route.executionType } : {}) });
  }, [navigation.navigate, navigation.route]);
  const onJobAccepted = useCallback((job: { jobId: string; packageId?: string }) => {
    navigation.navigate({ kind: 'jobs', jobId: job.jobId });
    void loadJobs();
  }, [navigation.navigate, loadJobs]);
  const capabilities = serverCapabilities(state);


  const runRoute = navigation.route.kind === 'run' ? navigation.route : null;
  const runUsesWorkspaceDefaults = Boolean(runRoute && !runRoute.replayDraftId && !runRoute.replaySourceJobId);
  const runPackageId = runRoute?.packageId ?? (runUsesWorkspaceDefaults ? selectedPackage : undefined);
  const runCanUseWorkspaceEnvironment = Boolean(runRoute && (!runRoute.packageId || runRoute.packageId === selectedPackage));
  const runEnvironment = runRoute?.environment ?? (
    runUsesWorkspaceDefaults && runCanUseWorkspaceEnvironment
      ? selectedEnvironment : ''
  );

  const renderContent = (onValidateContext: (request: ValidationRequest, title: string) => void,
    contextValidations: ContextValidation[]): ReactNode => {
  let content: ReactNode;
  if (navigation.route.kind === 'dashboard') {
    content = <DashboardPage view="dashboard" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} jobsRefreshing={jobsRefreshing} jobsUpdatedAt={jobsUpdatedAt} />;
  } else if (navigation.route.kind === 'monitoring') {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Monitoring…</div>}><MonitoringPage /></Suspense>;
  } else if (navigation.route.kind === 'validation') {
    content = navigation.route.validationId
      ? <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading validation record…</div>}>
          <ValidationPanel key={navigation.route.validationId} validationId={navigation.route.validationId}
            onRecordSubmitted={record => navigation.navigate({ kind: 'validation', validationId: record.validationId })}
            onOpenOrigin={record => { const origin = validationOriginRoute(record, contextValidations); if (origin) navigation.navigate(origin); }}
            onOpenResource={(packageId, resource, environment) => navigation.navigate({ kind: 'package', packageId, resource, environment })} />
        </Suspense>
      : <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading validation workspace…</div>}>
          <ValidationWorkspacePage api={api} packages={state.packages}
            serverVersion={state.serverVersion}
            route={navigation.route as ValidationRouteState} onRouteChange={route => navigation.navigate(route)}
            onOpenResource={(packageId, resource, environment) => navigation.navigate({ kind: 'package', packageId, resource, environment })} />
        </Suspense>;
  } else if (navigation.route.kind === 'run') {
    content = <RunPage key={`${navigation.route.packageId || ''}:${navigation.route.target?.type || ''}:${navigation.route.target?.resourceId || ''}:${navigation.route.environment || ''}:${navigation.route.batch ? 'batch' : ''}:${navigation.route.replaySourceJobId || ''}:${navigation.route.replayDraftId || ''}`}
      packageId={runPackageId} initialTarget={navigation.route.target} initialBatchMode={navigation.route.batch} initialEnvironment={runEnvironment}
      replayDraftId={navigation.route.replayDraftId} replaySourceJobId={navigation.route.replaySourceJobId}
      onAccepted={onJobAccepted} onSchedule={onSchedule} schedulingEnabled={capabilities.loadSchedulingEnabled} />;
  } else if (navigation.route.kind === 'packages') {
    content = <DashboardPage view="packages" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} jobsRefreshing={jobsRefreshing} jobsUpdatedAt={jobsUpdatedAt} />;
  } else if (navigation.route.kind === 'job-console' || (navigation.route.kind === 'jobs' && navigation.route.standalone && navigation.route.jobId)) {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Job console…</div>}><StandaloneJobConsolePage jobId={navigation.route.jobId!} view={navigation.route.kind === 'job-console' ? navigation.route.view : navigation.route.tab === 'validations' ? undefined : navigation.route.tab} /></Suspense>;
  } else if (navigation.route.kind === 'job-artifact') {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading artifact…</div>}><StandaloneJobArtifactPage api={loadJobsApi} jobId={navigation.route.jobId} path={navigation.route.path} /></Suspense>;
  } else if (navigation.route.kind === 'jobs' && navigation.route.jobId) {
    content = <Suspense fallback={<div role="status">Loading jobs…</div>}><JobsPage executionType={navigation.route.executionType} schedulingEnabled={capabilities.loadSchedulingEnabled} onExecutionTypeChange={executionType => navigation.navigate({ kind: 'jobs', executionType })} api={loadJobsApi} onSchedule={capabilities.loadSchedulingEnabled ? onSchedule : undefined} onEditSchedule={id => navigation.navigate({ kind: 'schedules', view: 'edit', scheduleId: id })} jobId={navigation.route.jobId} onOpenJob={onJobChange} onShowList={() => navigation.navigate({ kind: 'jobs', ...(navigation.route.kind === 'jobs' && navigation.route.executionType ? { executionType: navigation.route.executionType } : {}) })} /></Suspense>;
  } else if (navigation.route.kind === 'jobs') {
    content = <Suspense fallback={<div role="status">Loading jobs…</div>}><JobsPage executionType={navigation.route.executionType}
      jobsTab={navigation.route.tab === 'validations' ? 'validations' : 'jobs'}
      onJobsTabChange={tab => navigation.navigate(tab === 'validations' ? { kind: 'jobs', tab: 'validations' } : { kind: 'jobs' })}
      validationRecordsSupported={supportsValidationRecords}
      onOpenValidationRecord={validationId => navigation.navigate({ kind: 'validation', validationId })}
      schedulingEnabled={capabilities.loadSchedulingEnabled}
      onExecutionTypeChange={executionType => navigation.navigate({ kind: 'jobs', executionType })} api={loadJobsApi}
      onSchedule={capabilities.loadSchedulingEnabled ? onSchedule : undefined}
      onEditSchedule={id => navigation.navigate({ kind: 'schedules', view: 'edit', scheduleId: id })}
      onOpenJob={onJobChange}
      onShowList={() => navigation.navigate({ kind: 'jobs', ...(navigation.route.kind === 'jobs' && navigation.route.executionType ? { executionType: navigation.route.executionType } : {}) })} /></Suspense>;
  } else if (navigation.route.kind === 'schedules') {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Schedules…</div>}>
      <ScheduledLoadPage
        route={navigation.route}
        api={api}
        packages={state.packages}
        enabled={capabilities.loadSchedulingEnabled}
        onOpenJob={onJobChange}
        initialDraft={scheduleIntent}
        onInitialDraftConsumed={consumeScheduleIntent}
        onSaved={onScheduleSaved}
        onCancel={returnFromSchedule}
      />
    </Suspense>;
  } else if (navigation.route.kind === 'settings') {
    content = <Suspense fallback={<div role="status">Loading settings...</div>}><SettingsPage /></Suspense>;
  } else if (navigation.route.kind === 'configuration') {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Configuration…</div>}>
      <ConfigurationRouteView route={navigation.route} onValidateContext={onValidateContext} />
    </Suspense>;
  } else if (navigation.route.kind === 'quick-load') {
    content = <QuickLoadRouteView key={`${navigation.route.packageId}:${navigation.route.target?.type || ''}:${navigation.route.target?.resourceId || ''}:${navigation.route.templateId || ''}:${navigation.route.model || ''}`}
      route={navigation.route} inlineLoadEnabled={capabilities.inlineLoadEnabled}
      schedulingEnabled={capabilities.loadSchedulingEnabled} onSchedule={onSchedule} />;
  } else if (navigation.route.kind === 'load-resource') {
    content = <LoadResourceRouteView key={`${navigation.route.packageId}:${navigation.route.resourceId}`} route={navigation.route}
      inlineLoadEnabled={capabilities.inlineLoadEnabled} />;
  } else if (navigation.route.kind === 'package') {
    const packageRoute = navigation.route;
    const registeredPackage = state.packages.status === 'ready'
      ? state.packages.data.find(item => item.packageId === packageRoute.packageId)
      : undefined;
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Explorer…</div>}>
      <ExplorerRouteView key={packageRoute.packageId}
        route={packageRoute}
        inlineLoadEnabled={capabilities.inlineLoadEnabled}
        schedulingEnabled={capabilities.loadSchedulingEnabled}
        onSchedule={onSchedule}
        packageRegistrationState={state.packages.status}
        registered={Boolean(registeredPackage)}
        packageLabel={registeredPackage?.name || packageRoute.packageId}
        onValidateContext={onValidateContext}
        canManagePackageSource={registeredPackage?.canEditPackageContent === true}
      />
    </Suspense>;
  } else {
    const packageOptions = state.packages.status === 'ready'
      ? state.packages.data.map(item => ({ packageId: item.packageId, displayName: item.name }))
      : [];
    content = navigation.route.packageId
      ? <LoadRouteView key={`${navigation.route.packageId}:${navigation.route.templateId || ''}:${navigation.route.replaySourceJobId || ''}:${navigation.route.replayDraftId || ''}`} templateId={navigation.route.templateId} packageId={navigation.route.packageId} environment={navigation.route.environment} replayDraftId={navigation.route.replayDraftId} replaySourceJobId={navigation.route.replaySourceJobId} inlineLoadEnabled={capabilities.inlineLoadEnabled}
          schedulingEnabled={capabilities.loadSchedulingEnabled} packages={packageOptions} onSchedule={onSchedule} />
      : <Suspense fallback={<div role="status">Loading templates…</div>}><LoadTestingPage api={api} packages={packageOptions} enabled={capabilities.inlineLoadEnabled} tab={navigation.route.tab || (navigation.route.catalog ? 'scenarios' : 'templates')}
          onTab={tab => navigation.navigate({ kind: 'load', tab })}
          onBlank={packageId => navigation.navigate({ kind: 'load', packageId })}
          onJobs={() => navigation.navigate({ kind: 'jobs', executionType: 'load' })}
          onCreate={(packageId, item) => navigation.navigate({ kind: 'load', packageId, templateId: item.id })}>
        <SavedLoadScenarios editorApi={loadJobsApi} packages={packageOptions} enabled={capabilities.inlineLoadEnabled} onJobAccepted={jobId => onJobAccepted({ jobId, packageId: undefined })} onSchedule={capabilities.loadSchedulingEnabled ? onSchedule : undefined} />
        <LoadCatalogPage api={api}
          packages={state.packages.status === 'ready'
            ? { status: 'ready', data: packageOptions }
            : state.packages}
          inlineLoadEnabled={capabilities.inlineLoadEnabled}
          schedulingEnabled={capabilities.loadSchedulingEnabled}
          environment={catalogEnvironment}
          catalogState={navigation.route.kind === 'load' ? navigation.route.catalog : undefined}
          onEnvironmentChange={setCatalogEnvironment}
          onNewLoad={packageId => navigation.navigate(packageId ? { kind: 'load', packageId } : { kind: 'load' })}
          onQuickLoad={(packageId, template, environment) => navigation.navigate({ kind: 'quick-load', packageId, templateId: template.resourceId, model: template.model || 'virtualUsers', environment })}
          onJobAccepted={jobId => onJobAccepted({ jobId, packageId: undefined })}
          onOpenJob={onJobChange}
          onSchedule={onSchedule}
          onOpenDetail={(item, environment, returnTo) => navigation.navigate({ kind: 'load-resource', packageId: item.packageId,
            resourceId: item.resourceId, environment, returnTo })}
          onOpenValidation={(item, environment) => onValidateContext(
            { targetKind: 'load', packageId: item.packageId, ...(environment ? { environment } : {}), target: item.path },
            `LOAD Scenario · ${item.path}`)} />
      </LoadTestingPage></Suspense>;
  }
  return content;
  };

  return (
    <AppShell
      route={navigation.route}
      navigation={navigation}
      serverVersion={state.serverVersion}
      showScheduledLoad={capabilities.loadSchedulingEnabled}
      selectedPackage={selectedPackage}
      selectedEnvironment={selectedEnvironment}
      onPackageChange={changeWorkspacePackage}
      onEnvironmentChange={changeWorkspaceEnvironment}
      workspacePackages={state.packages.status === 'ready' ? state.packages.data.map(item => item.packageId) : []}
      workspaceEnvironments={workspaceEnvironments}
      workspaceResolvedEnvironment={selectedPackage && workspaceResolution.key === `${selectedPackage}\n${selectedEnvironment}` ? workspaceResolution.environment || null : null}
      workspaceResolutionError={workspaceResolutionError}
      workspaceNotice={selectedPackage && state.packages.status === 'ready' && !state.packages.data.some(item => item.packageId === selectedPackage) ? 'The selected Workspace Package is no longer available. Choose another Package.' : workspaceProfilesStatus === 'error' ? 'Workspace profiles could not be loaded. Check the selected Package.' : workspaceProfilesStatus === 'ready' && selectedEnvironment && !workspaceEnvironments.includes(selectedEnvironment) ? 'The selected Workspace Environment is no longer active for this Package. Choose another environment.' : workspaceResolutionError ? 'Workspace Environment could not be resolved. Validate the execution configuration before use.' : ''}
      onJobAccepted={onJobAccepted}
    >
      <ValidationContextCache routeKey={currentRouteKey} supportsValidationRecords={supportsValidationRecords} navigate={navigation.navigate}>
        {({ contextValidation, contextValidations, onValidateContext, rememberContextValidation, closeContextValidation }) => <>
          {renderContent(onValidateContext, contextValidations)}
          {supportsValidationRecords && contextValidation && <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading validation…</div>}>
            <ValidationPanel key={`${contextValidation.routeKey}:${contextValidation.title}`}
              request={contextValidation.request} title={contextValidation.title}
              onClose={closeContextValidation}
              submitOnOpen={contextValidation.submitOnOpen} onRecordLoaded={rememberContextValidation}
              onOpenRecord={validationId => navigation.navigate({ kind: 'validation', validationId })}
              onOpenOrigin={record => { const origin = validationOriginRoute(record, contextValidations); if (origin) navigation.navigate(origin); }}
              onOpenResource={(packageId, resource, environment) => navigation.navigate({ kind: 'package', packageId, resource, environment })} />
          </Suspense>}
        </>}
      </ValidationContextCache>
    </AppShell>
  );
}
