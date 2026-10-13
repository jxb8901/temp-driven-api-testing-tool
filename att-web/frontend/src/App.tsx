import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { api } from './api';
import { AppShell, useAppShellContext } from './AppShell';
import { DashboardPage, LoadLanding } from './DashboardPage';
import { useNavigationGuard, useRouteState } from './router';
import { appReducer, initialAppState, serverCapabilities } from './state';
import { ExplorerPage } from './features/explorer/ExplorerPage';
import './features/explorer/explorer.css';
import type { ExplorerPageProps } from './features/explorer/types';
import { LoadPage } from './features/load/LoadPage';
import { JobsPage } from './features/load/JobsPage';
import type { LoadJobsApi, PackageOption } from './features/load/types';

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('The ATT Server request could not be completed.');
}

const loadJobsApi: LoadJobsApi = api;

function ExplorerRouteView({ packageId, resourceRoute, inlineLoadEnabled }: {
  packageId: string;
  resourceRoute: ExplorerPageProps['resourceRoute'];
  inlineLoadEnabled: boolean;
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
  const onOpenQuickLoad = useCallback((identity: { packageId: string }) => {
    navigation.navigate({ kind: 'load', packageId: identity.packageId });
  }, [navigation.navigate]);

  return <ExplorerPage
    api={api}
    packageId={packageId}
    resourceRoute={resourceRoute}
    environment={workspace.selectedEnvironment}
    registerUnsavedChanges={navigation.registerUnsavedChanges}
    onResourceRouteChange={onResourceRouteChange}
    onEnvironmentChange={workspace.onEnvironmentChange}
    onJobAccepted={onJobAccepted}
    onOpenQuickLoad={inlineLoadEnabled ? onOpenQuickLoad : undefined}
  />;
}

function LoadRouteView({ packageId, inlineLoadEnabled, packages }: {
  packageId: string;
  inlineLoadEnabled: boolean;
  packages: PackageOption[];
}) {
  const workspace = useAppShellContext();
  const navigation = useNavigationGuard();
  const selectedEnvironment = useRef(workspace.selectedEnvironment);
  selectedEnvironment.current = workspace.selectedEnvironment;
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
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
      const names = (configuration.environments || []).map(profile => profile.name).filter(Boolean);
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
  }, [packageId, workspace.onEnvironmentChange]);

  return <LoadPage
    api={loadJobsApi}
    packages={packages}
    selectedPackage={packageId}
    selectedEnvironment={workspace.selectedEnvironment}
    environments={environments}
    environmentState={environmentState}
    inlineLoadEnabled={inlineLoadEnabled}
    onPackageChange={onPackageChange}
    onEnvironmentChange={workspace.onEnvironmentChange}
    onJobAccepted={onJobAccepted}
  />;
}

export default function App() {
  const navigation = useRouteState();
  const [state, dispatch] = useReducer(appReducer, initialAppState);
  const [selectedEnvironment, setSelectedEnvironment] = useState('');

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
    dispatch({ type: 'jobs/loading' });
    try {
      const response = await api.getJobs(signal);
      dispatch({ type: 'jobs/ready', data: Array.isArray(response.items) ? response.items : [] });
    } catch (error) {
      if (!signal?.aborted) dispatch({ type: 'jobs/error', error: asError(error) });
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

  const onPackageChange = useCallback((packageId: string) => {
    navigation.navigate({ kind: 'package', packageId });
  }, [navigation.navigate]);
  const onLoadPackageChange = useCallback((packageId: string) => {
    navigation.navigate({ kind: 'load', packageId });
  }, [navigation.navigate]);
  const onJobChange = useCallback((jobId: string) => {
    navigation.navigate({ kind: 'jobs', jobId });
  }, [navigation.navigate]);
  const onJobAccepted = useCallback((job: { jobId: string; packageId?: string }) => {
    navigation.navigate({ kind: 'jobs', jobId: job.jobId });
  }, [navigation.navigate]);
  const capabilities = serverCapabilities(state);
  const currentRoute = navigation.route;
  const selectedPackage = currentRoute.kind === 'package' || currentRoute.kind === 'load'
    ? currentRoute.packageId || ''
    : currentRoute.kind === 'jobs' && currentRoute.jobId && state.jobs.status === 'ready'
      ? state.jobs.data.find(job => job.jobId === currentRoute.jobId)?.packageId || ''
      : '';

  let content;
  if (navigation.route.kind === 'dashboard') {
    content = <DashboardPage view="dashboard" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} />;
  } else if (navigation.route.kind === 'packages') {
    content = <DashboardPage view="packages" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} />;
  } else if (navigation.route.kind === 'jobs' && navigation.route.jobId) {
    content = <JobsPage api={loadJobsApi} jobId={navigation.route.jobId} onOpenJob={onJobChange} onShowList={() => navigation.navigate({ kind: 'jobs' })} />;
  } else if (navigation.route.kind === 'jobs') {
    content = <JobsPage api={loadJobsApi} onOpenJob={onJobChange} onShowList={() => navigation.navigate({ kind: 'jobs' })} />;
  } else if (navigation.route.kind === 'package') {
    content = <ExplorerRouteView
      packageId={navigation.route.packageId}
      resourceRoute={navigation.route.resource || null}
      inlineLoadEnabled={capabilities.inlineLoadEnabled}
    />;
  } else {
    const packageOptions = state.packages.status === 'ready'
      ? state.packages.data.map(item => ({ packageId: item.packageId, displayName: item.name }))
      : [];
    content = navigation.route.packageId
      ? <LoadRouteView packageId={navigation.route.packageId} inlineLoadEnabled={capabilities.inlineLoadEnabled} packages={packageOptions} />
      : <LoadLanding state={state.packages} enabled={capabilities.inlineLoadEnabled} onSelectPackage={onLoadPackageChange} />;
  }

  return (
    <AppShell
      route={navigation.route}
      navigation={navigation}
      serverVersion={state.serverVersion}
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
