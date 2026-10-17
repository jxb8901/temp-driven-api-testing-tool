import { lazy, Suspense, useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { api } from './api';
import { AppShell, useAppShellContext } from './AppShell';
import { DashboardPage } from './DashboardPage';
import { useNavigationGuard, useRouteState, type AppRoute } from './router';
import { appReducer, initialAppState, latestServerJobWindow, serverCapabilities } from './state';
import { RunPage } from './features/run/RunPage';
import { ExplorerPage } from './features/explorer/ExplorerPage';
import './features/explorer/explorer.css';
import type { ExplorerPageProps, ResourceSourceReference } from './features/explorer/types';
const LoadPage = lazy(() => import('./features/load/LoadPage').then(module => ({ default: module.LoadPage })));
const JobsPage = lazy(() => import('./features/load/JobsPage').then(module => ({ default: module.JobsPage })));
import type { LoadJobsApi, PackageOption } from './features/load/types';
import { SettingsPage } from './SettingsPage';
import { ConfigurationWorkspace } from './features/explorer/ConfigurationWorkspace';
import { QuickLoadPage } from './features/load/QuickLoadPage';
import { LoadCatalogPage } from './features/load/LoadCatalogPage';
import type { ValidatedScheduleIntent } from './features/schedules/types';

const ScheduledLoadPage = lazy(() => import('./features/schedules/ScheduledLoadPage').then(module => ({ default: module.ScheduledLoadPage })));

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('The ATT Server request could not be completed.');
}

const loadJobsApi: LoadJobsApi = api;

function ExplorerRouteView({ packageId, resourceRoute, inlineLoadEnabled, schedulingEnabled, onSchedule }: {
  packageId: string;
  resourceRoute: ExplorerPageProps['resourceRoute'];
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  onSchedule(input: ValidatedScheduleIntent): void;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const onResourceRouteChange = useCallback((resource: ExplorerPageProps['resourceRoute']) => {
    navigation.navigate(resource
      ? { kind: 'package', packageId, resource }
      : { kind: 'package', packageId });
  }, [navigation.navigate, packageId]);
  const onJobAccepted = useCallback((jobId: string) => {
    workspace.onJobAccepted({ jobId, packageId });
  }, [packageId, workspace.onJobAccepted]);
  const onOpenQuickLoad = useCallback((identity: { packageId: string; type: string; resourceId: string }) => {
    navigation.navigate({ kind: 'quick-load', packageId: identity.packageId, target: { type: identity.type, resourceId: identity.resourceId }, environment: workspace.selectedEnvironment });
  }, [navigation.navigate, workspace.selectedEnvironment]);
  const onRunTarget = useCallback((identity: { packageId: string; type: string; resourceId: string; logicalId?: string }) => {
    navigation.navigate({ kind: 'run', packageId: identity.packageId,
      target: { type: identity.type, resourceId: identity.resourceId, logicalId: identity.logicalId }, environment: workspace.selectedEnvironment });
  }, [navigation.navigate, workspace.selectedEnvironment]);
  const onScheduleDebug = useCallback<NonNullable<ExplorerPageProps['onScheduleDebug']>>((input) => {
    onSchedule(input);
  }, [onSchedule]);

  return <ExplorerPage key={packageId}
    api={api}
    packageId={packageId}
    resourceRoute={resourceRoute}
    environment={workspace.selectedEnvironment}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
    onResourceRouteChange={onResourceRouteChange}
    onJobAccepted={onJobAccepted}
    onRunTarget={target => onRunTarget({ packageId, ...target })}
    onOpenQuickLoad={inlineLoadEnabled ? onOpenQuickLoad : undefined}
    onScheduleDebug={schedulingEnabled ? onScheduleDebug : undefined}
    onOpenConfigurationFile={(path, origin, sourceReference) => navigation.navigate({ kind: 'configuration', packageId, environment: workspace.selectedEnvironment, filePath: path, fileOrigin: origin, sourceReference, tab: 'source' })}
  />;
}

function LoadRouteView({ packageId, inlineLoadEnabled, schedulingEnabled, packages, onSchedule }: {
  packageId: string;
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  packages: PackageOption[];
  onSchedule(input: ValidatedScheduleIntent): void;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = useRef(workspace.selectedEnvironment);
  selectedEnvironment.current = workspace.selectedEnvironment;
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const onPackageChange = useCallback((nextPackageId: string) => {
    navigation.navigate({ kind: 'load', packageId: nextPackageId });
  }, [navigation.navigate]);
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
      if (selectedEnvironment.current && !names.includes(selectedEnvironment.current)) {
        workspace.onEnvironmentChange('');
      }
    }).catch(() => {
      if (!controller.signal.aborted) {
        setEnvironments([]);
        setEnvironmentState('error');
      }
    });
    return () => controller.abort();
  }, [packageId, environmentReload, workspace.onEnvironmentChange]);

  return <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Load…</div>}>
    <LoadPage
      api={loadJobsApi}
      packages={packages}
      selectedPackage={packageId}
      selectedEnvironment={workspace.selectedEnvironment}
      environments={environments}
      environmentState={environmentState}
      inlineLoadEnabled={inlineLoadEnabled}
      schedulingEnabled={schedulingEnabled}
      onRetryEnvironments={() => setEnvironmentReload(value => value + 1)}
      onPackageChange={onPackageChange}
      onEnvironmentChange={workspace.onEnvironmentChange}
      onJobAccepted={onJobAccepted}
      onSchedule={onSchedule}
    />
  </Suspense>;
}

function ConfigurationRouteView({ route, packages }: {
  route: Extract<ReturnType<typeof useRouteState>['route'], { kind: 'configuration' }>;
  packages: PackageOption[];
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = route.environment ?? workspace.selectedEnvironment;
  const onPackageChange = useCallback((packageId: string) => {
    navigation.navigate({ kind: 'configuration', packageId, environment: selectedEnvironment, tab: route.tab });
  }, [navigation.navigate, route.tab, selectedEnvironment]);
  const onEnvironmentChange = useCallback((environment: string) => {
    workspace.onEnvironmentChange(environment);
    navigation.navigate({ ...route, environment });
  }, [navigation.navigate, route, workspace.onEnvironmentChange]);
  const onFileChange = useCallback((filePath?: string, fileOrigin?: string, sourceReference?: ResourceSourceReference, configurationReferenceId?: string) => {
    navigation.navigate({ ...route, filePath, fileOrigin, sourceReference, configurationReferenceId, tab: filePath ? 'source' : route.tab });
  }, [navigation.navigate, route]);
  const onTabChange = useCallback((tab: 'source' | 'declared' | 'effective' | 'compare' | 'testdata') => {
    navigation.navigate({ ...route, tab });
  }, [navigation.navigate, route]);
  const onOpenResource = useCallback((resource: { type: string; resourceId: string }) => {
    navigation.navigate({ kind: 'package', packageId: route.packageId || '', resource });
  }, [navigation.navigate, route.packageId]);
  return <ConfigurationWorkspace
    api={api}
    packages={packages}
    packageId={route.packageId || ''}
    environment={selectedEnvironment}
    filePath={route.filePath}
    fileOrigin={route.fileOrigin}
    sourceReference={route.sourceReference}
    configurationReferenceId={route.configurationReferenceId}
    canRevealAdminFields={workspace.canRevealConfigurationFields}
    tab={route.tab || (route.filePath ? 'source' : 'declared')}
    onPackageChange={onPackageChange}
    onEnvironmentChange={onEnvironmentChange}
    onFileChange={onFileChange}
    onTabChange={onTabChange}
    onOpenResource={onOpenResource}
  />;
}

function QuickLoadRouteView({ route, inlineLoadEnabled, schedulingEnabled, onSchedule }: {
  route: Extract<ReturnType<typeof useRouteState>['route'], { kind: 'quick-load' }>;
  inlineLoadEnabled: boolean;
  schedulingEnabled: boolean;
  onSchedule(input: ValidatedScheduleIntent): void;
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = route.environment ?? workspace.selectedEnvironment;
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    api.getDeclaredConfiguration(route.packageId, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      setEnvironments((configuration.environments || []).filter(item => item.name && item.state === 'active').map(item => item.name));
      setEnvironmentState('ready');
    }).catch(() => { if (!controller.signal.aborted) { setEnvironments([]); setEnvironmentState('error'); } });
    return () => controller.abort();
  }, [route.packageId, environmentReload]);
  const onEnvironmentChange = useCallback((environment: string) => {
    workspace.onEnvironmentChange(environment);
    navigation.navigate({ ...route, environment });
  }, [navigation.navigate, route, workspace.onEnvironmentChange]);
  const onCancel = useCallback(() => navigation.navigate(route.target
    ? { kind: 'package', packageId: route.packageId, resource: route.target }
    : { kind: 'load' }), [navigation.navigate, route.packageId, route.target]);
  const onJobAccepted = useCallback((jobId: string) => workspace.onJobAccepted({ jobId, packageId: route.packageId }), [route.packageId, workspace.onJobAccepted]);
  return <QuickLoadPage
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
    onJobAccepted={onJobAccepted}
    onSchedule={onSchedule}
    onCancel={onCancel}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
  />;
}

export default function App() {
  const navigation = useRouteState();
  const [state, dispatch] = useReducer(appReducer, initialAppState);
  const [jobsRefreshing, setJobsRefreshing] = useState(false);
  const [jobsUpdatedAt, setJobsUpdatedAt] = useState<string | null>(null);
  const jobsRefreshCount = useRef(0);
  const jobsRequestSequence = useRef(0);
  const [selectedEnvironment, setSelectedEnvironment] = useState('');
  const [scheduleIntent, setScheduleIntent] = useState<ValidatedScheduleIntent | null>(null);
  const scheduleReturnRoute = useRef<AppRoute | null>(null);
  const onSchedule = useCallback((intent: ValidatedScheduleIntent) => {
    scheduleReturnRoute.current = navigation.route;
    setScheduleIntent(intent);
    navigation.navigate({ kind: 'schedules', view: 'new' });
  }, [navigation.navigate, navigation.route]);
  const consumeScheduleIntent = useCallback(() => setScheduleIntent(null), []);
  const returnFromSchedule = useCallback(() => {
    setScheduleIntent(null);
    const route = scheduleReturnRoute.current;
    scheduleReturnRoute.current = null;
    navigation.navigate(route || { kind: 'schedules' });
  }, [navigation.navigate]);
  useEffect(() => {
    const routeEnvironment = navigation.route.kind === 'configuration' || navigation.route.kind === 'quick-load' || navigation.route.kind === 'run' ? navigation.route.environment : undefined;
    if (routeEnvironment !== undefined) setSelectedEnvironment(routeEnvironment);
  }, [navigation.route]);

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
      void loadPackages(controller.signal);
      void loadJobs(controller.signal);
    }).catch(error => {
      if (controller.signal.aborted) return;
      const reason = asError(error);
      dispatch({ type: 'version/error', error: reason });
      dispatch({ type: 'packages/error', error: reason });
      dispatch({ type: 'jobs/error', error: reason });
    });
    return () => controller.abort();
  }, [loadJobs, loadPackages]);

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
    navigation.navigate({ kind: 'jobs', jobId });
  }, [navigation.navigate]);
  const onJobAccepted = useCallback((job: { jobId: string; packageId?: string }) => {
    navigation.navigate({ kind: 'jobs', jobId: job.jobId });
    void loadJobs();
  }, [navigation.navigate, loadJobs]);
  const capabilities = serverCapabilities(state);
  const currentRoute = navigation.route;
  const selectedPackage = currentRoute.kind === 'run' || currentRoute.kind === 'package' || currentRoute.kind === 'load' || currentRoute.kind === 'configuration' || currentRoute.kind === 'quick-load'
    ? currentRoute.packageId || ''
    : currentRoute.kind === 'jobs' && currentRoute.jobId && state.jobs.status === 'ready'
      ? state.jobs.data.find(job => job.jobId === currentRoute.jobId)?.packageId || ''
      : '';

  let content;
  if (navigation.route.kind === 'dashboard') {
    content = <DashboardPage view="dashboard" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} jobsRefreshing={jobsRefreshing} jobsUpdatedAt={jobsUpdatedAt} />;
  } else if (navigation.route.kind === 'run') {
    content = <RunPage key={`${navigation.route.packageId || ''}:${navigation.route.target?.type || ''}:${navigation.route.target?.resourceId || ''}:${navigation.route.environment || ''}`}
      packageId={navigation.route.packageId} initialTarget={navigation.route.target} initialEnvironment={navigation.route.environment ?? selectedEnvironment}
      onAccepted={onJobAccepted} onSchedule={onSchedule} schedulingEnabled={capabilities.loadSchedulingEnabled} />;
  } else if (navigation.route.kind === 'packages') {
    content = <DashboardPage view="packages" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} jobsRefreshing={jobsRefreshing} jobsUpdatedAt={jobsUpdatedAt} />;
  } else if (navigation.route.kind === 'jobs' && navigation.route.jobId) {
    content = <Suspense fallback={<div role="status">Loading jobs…</div>}><JobsPage api={loadJobsApi} jobId={navigation.route.jobId} onOpenJob={onJobChange} onShowList={() => navigation.navigate({ kind: 'jobs' })} /></Suspense>;
  } else if (navigation.route.kind === 'jobs') {
    content = <Suspense fallback={<div role="status">Loading jobs…</div>}><JobsPage api={loadJobsApi} onOpenJob={onJobChange} onShowList={() => navigation.navigate({ kind: 'jobs' })} /></Suspense>;
  } else if (navigation.route.kind === 'schedules') {
    content = <Suspense fallback={<div className="page-content" role="status" aria-busy="true">Loading Schedules…</div>}>
      <ScheduledLoadPage
        key={`${navigation.route.view || 'list'}:${navigation.route.scheduleId || ''}`}
        route={navigation.route}
        api={api}
        packages={state.packages}
        enabled={capabilities.loadSchedulingEnabled}
        onOpenJob={onJobChange}
        initialDraft={scheduleIntent}
        onInitialDraftConsumed={consumeScheduleIntent}
        onSaved={returnFromSchedule}
        onCancel={returnFromSchedule}
      />
    </Suspense>;
  } else if (navigation.route.kind === 'settings') {
    content = <SettingsPage />;
  } else if (navigation.route.kind === 'configuration') {
    const packageOptions = state.packages.status === 'ready'
      ? state.packages.data.map(item => ({ packageId: item.packageId, displayName: item.name }))
      : [];
    content = <ConfigurationRouteView route={navigation.route} packages={packageOptions} />;
  } else if (navigation.route.kind === 'quick-load') {
    content = <QuickLoadRouteView key={`${navigation.route.packageId}:${navigation.route.target?.type || ''}:${navigation.route.target?.resourceId || ''}:${navigation.route.templateId || ''}:${navigation.route.model || ''}`}
      route={navigation.route} inlineLoadEnabled={capabilities.inlineLoadEnabled}
      schedulingEnabled={capabilities.loadSchedulingEnabled} onSchedule={onSchedule} />;
  } else if (navigation.route.kind === 'package') {
    content = <ExplorerRouteView
      packageId={navigation.route.packageId}
      resourceRoute={navigation.route.resource || null}
      inlineLoadEnabled={capabilities.inlineLoadEnabled}
      schedulingEnabled={capabilities.loadSchedulingEnabled}
      onSchedule={onSchedule}
    />;
  } else {
    const packageOptions = state.packages.status === 'ready'
      ? state.packages.data.map(item => ({ packageId: item.packageId, displayName: item.name }))
      : [];
    content = navigation.route.packageId
      ? <LoadRouteView packageId={navigation.route.packageId} inlineLoadEnabled={capabilities.inlineLoadEnabled}
          schedulingEnabled={capabilities.loadSchedulingEnabled} packages={packageOptions} onSchedule={onSchedule} />
      : <LoadCatalogPage api={api}
          packages={state.packages.status === 'ready'
            ? { status: 'ready', data: packageOptions }
            : state.packages}
          inlineLoadEnabled={capabilities.inlineLoadEnabled}
          schedulingEnabled={capabilities.loadSchedulingEnabled}
          environment={selectedEnvironment}
          onEnvironmentChange={setSelectedEnvironment}
          onNewLoad={packageId => navigation.navigate(packageId ? { kind: 'load', packageId } : { kind: 'load' })}
          onQuickLoad={(packageId, template) => navigation.navigate({ kind: 'quick-load', packageId, templateId: template.resourceId, model: template.model || 'virtualUsers', environment: selectedEnvironment })}
          onJobAccepted={jobId => onJobAccepted({ jobId, packageId: undefined })}
          onOpenJob={onJobChange}
          onSchedule={onSchedule} />;
  }

  return (
    <AppShell
      route={navigation.route}
      navigation={navigation}
      serverVersion={state.serverVersion}
      showScheduledLoad={capabilities.loadSchedulingEnabled}
      selectedPackage={selectedPackage}
      selectedEnvironment={selectedEnvironment}
      onPackageChange={onPackageChange}
      onEnvironmentChange={setSelectedEnvironment}
      onJobAccepted={onJobAccepted}
    >
      {content}
    </AppShell>
  );
}
