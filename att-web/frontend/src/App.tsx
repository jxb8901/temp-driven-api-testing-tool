import { useCallback, useEffect, useReducer, useState } from 'react';
import { api, request } from './api';
import { AppShell } from './AppShell';
import { DashboardPage, LoadLanding, PackageLanding } from './DashboardPage';
import { useRouteState } from './router';
import { appReducer, initialAppState, serverCapabilities } from './state';
import type { AsyncState, JobSummary } from './types';

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('The ATT Server request could not be completed.');
}

function JobDetailPage({ jobId }: { jobId: string }) {
  const [job, setJob] = useState<AsyncState<JobSummary>>({ status: 'loading' });
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setJob({ status: 'loading' });
    request<JobSummary>('jobs/' + encodeURIComponent(jobId), { signal: controller.signal })
      .then(data => setJob({ status: 'ready', data }))
      .catch(error => {
        if (!controller.signal.aborted) setJob({ status: 'error', error: asError(error) });
      });
    return () => controller.abort();
  }, [jobId, refresh]);

  return (
    <div className="page-content">
      <div className="page-heading"><div><p className="eyebrow">Jobs and results</p><h1 id="home-title">Job {jobId}</h1><p className="page-description">Job detail from the ATT Server API.</p></div></div>
      <section className="panel" aria-labelledby="job-detail-heading">
        <div className="section-heading"><div><h2 id="job-detail-heading">Job status</h2><p className="section-description">The detail route remains addressable by job ID.</p></div>
          {job.status === 'error' && <button className="button button-quiet" type="button" onClick={() => setRefresh(value => value + 1)}>Retry</button>}
        </div>
        {job.status === 'loading' && <p className="section-state" role="status" aria-busy="true">Loading job {jobId}…</p>}
        {job.status === 'error' && <p className="section-state state-error" role="status">Could not load job: {job.error.message}</p>}
        {job.status === 'ready' && <dl className="detail-grid">
          <div><dt>Job ID</dt><dd>{job.data.jobId}</dd></div>
          <div><dt>Status</dt><dd><span className={'status-badge status-' + String(job.data.status).toLowerCase().replace(/_/g, '-')}>{job.data.status}</span></dd></div>
          <div><dt>Command</dt><dd>{job.data.command}</dd></div>
          <div><dt>Package</dt><dd>{job.data.packageId}</dd></div>
          <div><dt>Created</dt><dd>{job.data.createdAt || 'Not available'}</dd></div>
        </dl>}
      </section>
    </div>
  );
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
    content = <JobDetailPage jobId={navigation.route.jobId} />;
  } else if (navigation.route.kind === 'jobs') {
    content = <DashboardPage view="jobs" state={state} onPackageChange={onPackageChange} onJobChange={onJobChange} onRetryPackages={() => void loadPackages()} onRetryJobs={() => void loadJobs()} />;
  } else if (navigation.route.kind === 'package') {
    content = <PackageLanding packageId={navigation.route.packageId} resourceId={navigation.route.resource?.resourceId} enabled={capabilities.inlineLoadEnabled} />;
  } else {
    content = <LoadLanding state={state.packages} enabled={capabilities.inlineLoadEnabled} packageId={navigation.route.packageId} onSelectPackage={onLoadPackageChange} />;
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
