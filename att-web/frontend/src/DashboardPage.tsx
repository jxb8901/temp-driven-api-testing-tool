import type { AppState } from './state';
import type { AsyncState, JobSummary, PackageSummary } from './types';

export type DashboardView = 'dashboard' | 'packages' | 'jobs';

interface DashboardPageProps {
  view: DashboardView;
  state: AppState;
  onPackageChange(packageId: string): void;
  onJobChange(jobId: string): void;
  onRetryPackages(): void;
  onRetryJobs(): void;
}

function ErrorText({ error }: { error: Error }) {
  return <span>{error.message}</span>;
}

function PackagePanel({
  state, onPackageChange, onRetry, heading = 'Configured packages', description = 'Open a package to inspect resources and run tests.',
}: {
  state: AsyncState<PackageSummary[]>;
  onPackageChange(packageId: string): void;
  onRetry(): void;
  heading?: string;
  description?: string;
}) {
  return (
    <section id="home-packages-section" className="panel" aria-labelledby="packages-heading">
      <div className="section-heading">
        <div><h2 id="packages-heading">{heading}</h2><p className="section-description">{description}</p></div>
        {state.status === 'error' && <button id="retry-packages" className="button button-quiet" type="button" onClick={onRetry}>Retry</button>}
      </div>
      <PackageState state={state} onPackageChange={onPackageChange} />
    </section>
  );
}

function PackageState({ state, onPackageChange }: { state: AsyncState<PackageSummary[]>; onPackageChange(packageId: string): void }) {
  if (state.status === 'loading') return <p id="packages-state" className="section-state" role="status" aria-live="polite" aria-busy="true">Loading configured packages…</p>;
  if (state.status === 'error') return <p id="packages-state" className="section-state state-error" role="status" aria-live="polite">Could not load packages: <ErrorText error={state.error} /></p>;
  if (state.data.length === 0) return <p id="packages-state" className="section-state" role="status" aria-live="polite">No packages are configured yet.</p>;
  return (
    <div>
      <p id="packages-state" className="section-state" role="status" aria-live="polite">{state.data.length} configured packages are available.</p>
      <ul className="package-list" aria-label="Configured packages">
        {state.data.map(item => (
          <li className="package-row" key={item.packageId}>
            <div className="package-mark" aria-hidden="true">P</div>
            <div className="package-copy"><strong>{item.name || item.packageId}</strong><span>{item.name ? item.packageId : 'Package workspace'}</span></div>
            <button className="button button-quiet" type="button" onClick={() => onPackageChange(item.packageId)}>Open package</button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function JobPanel({
  state, onJobChange, onRetry, heading = 'Recent activity', description = 'Latest job rows returned by this Server.',
}: {
  state: AsyncState<JobSummary[]>;
  onJobChange(jobId: string): void;
  onRetry(): void;
  heading?: string;
  description?: string;
}) {
  return (
    <section id="home-jobs-section" className="panel" aria-labelledby="jobs-heading">
      <div className="section-heading">
        <div><h2 id="jobs-heading">{heading}</h2><p className="section-description">{description}</p></div>
        {state.status === 'error' && <button id="retry-jobs" className="button button-quiet" type="button" onClick={onRetry}>Retry</button>}
      </div>
      <JobState state={state} onJobChange={onJobChange} />
    </section>
  );
}

function JobState({ state, onJobChange }: { state: AsyncState<JobSummary[]>; onJobChange(jobId: string): void }) {
  if (state.status === 'loading') return <p id="jobs-state" className="section-state" role="status" aria-live="polite" aria-busy="true">Loading recent jobs…</p>;
  if (state.status === 'error') return <p id="jobs-state" className="section-state state-error" role="status" aria-live="polite">Could not load recent jobs: <ErrorText error={state.error} /></p>;
  if (state.data.length === 0) return <p id="jobs-state" className="section-state" role="status" aria-live="polite">No jobs yet.</p>;
  return (
    <div>
      <p id="jobs-state" className="section-state" role="status" aria-live="polite">{state.data.length} recent job rows returned.</p>
      <ul className="job-list" aria-label="Recent jobs">
        {state.data.slice(0, 8).map(job => (
          <li className="job-row" key={job.jobId}>
            <div className="job-mark" aria-hidden="true">↗</div>
            <div className="job-copy">
              <button className="text-link job-id" type="button" onClick={() => onJobChange(job.jobId)}>{job.jobId}</button>
              <span>{job.command} · {job.packageId}</span>
            </div>
            <span className={'status-badge status-' + String(job.status || 'UNKNOWN').toLowerCase().replace(/_/g, '-')}>{job.status || 'Unknown'}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function SummaryCards({ state }: { state: AppState }) {
  const packages = state.packages.status === 'ready' ? state.packages.data : null;
  const jobs = state.jobs.status === 'ready' ? state.jobs.data : null;
  const activeCount = jobs?.filter(job => ['QUEUED', 'PREPARING', 'RUNNING', 'CANCEL_REQUESTED'].includes(String(job.status).toUpperCase())).length;
  const latest = jobs?.[0];
  const serverStatus = state.serverVersion.status === 'ready' ? 'Connected'
    : state.serverVersion.status === 'error' ? 'Unavailable' : 'Connecting…';
  return (
    <section className="summary-grid" aria-label="Dashboard summary">
      <article className="summary-card"><span className="summary-label">Configured packages</span><strong className="summary-value">{packages === null ? '—' : packages.length}</strong><span className="summary-detail">Available to this Server</span></article>
      <article className="summary-card"><span className="summary-label">Active jobs in results</span><strong className="summary-value">{activeCount === undefined ? '—' : activeCount}</strong><span className="summary-detail">{jobs === null ? 'Waiting for recent jobs' : 'From ' + jobs.length + ' returned job rows'}</span></article>
      <article className="summary-card"><span className="summary-label">Latest job</span><strong className="summary-value summary-value-text">{latest ? latest.status : jobs === null ? '—' : 'No jobs yet'}</strong><span className="summary-detail">{latest ? 'Job ' + latest.jobId : 'From the latest job rows'}</span></article>
      <article className="summary-card"><span className="summary-label">Server status</span><strong className="summary-value summary-value-text">{serverStatus}</strong><span className="summary-detail">ATT API v1</span></article>
    </section>
  );
}

export function DashboardPage({
  view, state, onPackageChange, onJobChange, onRetryPackages, onRetryJobs,
}: DashboardPageProps) {
  const title = view === 'dashboard' ? 'Dashboard' : view === 'packages' ? 'Packages' : 'Jobs and results';
  const description = view === 'dashboard' ? 'Monitor recent ATT activity and open a package workspace.'
    : view === 'packages' ? 'Open a configured package workspace.'
      : 'Review jobs returned by the ATT Server API.';
  return (
    <div className="page-content">
      <div className="page-heading">
        <div><p className="eyebrow">Workspace</p><h1 id="home-title">{title}</h1><p className="page-description">{description}</p></div>
      </div>
      {view === 'dashboard' && <SummaryCards state={state} />}
      {view === 'dashboard'
        ? <div className="dashboard-columns">
            <PackagePanel state={state.packages} onPackageChange={onPackageChange} onRetry={onRetryPackages} />
            <JobPanel state={state.jobs} onJobChange={onJobChange} onRetry={onRetryJobs} />
          </div>
        : view === 'packages'
          ? <PackagePanel state={state.packages} onPackageChange={onPackageChange} onRetry={onRetryPackages} />
          : <JobPanel state={state.jobs} onJobChange={onJobChange} onRetry={onRetryJobs} />}
    </div>
  );
}

export function LoadLanding({
  state, enabled, packageId, onSelectPackage,
}: {
  state: AsyncState<PackageSummary[]>;
  enabled: boolean;
  packageId?: string;
  onSelectPackage(packageId: string): void;
}) {
  return (
    <div className="page-content">
      <div className="page-heading">
        <div><p className="eyebrow">Workspace</p><h1 id="home-title">{packageId ? 'Advanced Load' : 'Load testing'}</h1><p className="page-description">Choose a configured package to build a Load scenario.</p></div>
      </div>
      <section className="panel" aria-labelledby="load-selection-heading">
        <div className="section-heading"><div><h2 id="load-selection-heading">Select a package</h2><p className="section-description">Load availability follows the Server capability response.</p></div></div>
        {!enabled && <p className="callout callout-warning" role="status">Browser-submitted Load is disabled by Server configuration.</p>}
        {packageId && <p className="callout">Selected package: <strong>{packageId}</strong>. The Load builder will open here.</p>}
        <PackageState state={state} onPackageChange={onSelectPackage} />
      </section>
    </div>
  );
}

export function PackageLanding({ packageId, resourceId, enabled }: { packageId: string; resourceId?: string; enabled: boolean }) {
  return (
    <div className="page-content">
      <div className="page-heading">
        <div><p className="eyebrow">Package workspace</p><h1 id="home-title">{resourceId || packageId}</h1><p className="page-description">{resourceId ? 'Selected package resource' : 'Package resources and Run actions are available from this route.'}</p></div>
      </div>
      <section className="panel feature-placeholder">
        <span className="section-icon" aria-hidden="true">▦</span>
        <div><h2>{resourceId ? 'Resource route ready' : 'Explorer route ready'}</h2><p className="section-description">Package: <strong>{packageId}</strong></p>
          <p className="section-description">The typed route supports resource deep links and later feature pages.</p>
          {!enabled && <p className="callout callout-warning">Browser-submitted Load is disabled by Server configuration.</p>}
        </div>
      </section>
    </div>
  );
}
