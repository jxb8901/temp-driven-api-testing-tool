import { useCallback, useEffect, useMemo, useState } from 'react';
import type { AsyncState, JobSummary } from '../../types';
import type { LoadCatalogItem, PackageOption } from './types';
import type { api } from '../../api';
import { CodeEditor } from '../code/CodeEditor';
import './load.css';

type ScheduleIntent = { packageId: string; environment?: string; scenario: string; executionType: 'load' };
type CatalogApi = Pick<typeof api, 'listLoadCatalog' | 'getLoadSource' | 'submitLoadScenario' | 'listLoadJobs'>;
type CatalogJob = JobSummary & { scenario?: string; environment?: string; model?: string };

function jobDuration(job: CatalogJob): string | null {
  if (!job.startedAt || !job.finishedAt) return null;
  const milliseconds = Date.parse(job.finishedAt) - Date.parse(job.startedAt);
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return null;
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`;
}

export function LoadCatalogPage({ api, packages, inlineLoadEnabled, schedulingEnabled, environment, onEnvironmentChange,
  onNewLoad, onQuickLoad, onJobAccepted, onOpenJob, onSchedule }: {
  api: CatalogApi; packages: AsyncState<PackageOption[]>; inlineLoadEnabled: boolean; schedulingEnabled: boolean;
  environment: string; onEnvironmentChange(value: string): void; onNewLoad(packageId?: string): void;
  onQuickLoad(packageId: string, template: LoadCatalogItem): void; onJobAccepted(jobId: string): void;
  onOpenJob(jobId: string): void; onSchedule(intent: ScheduleIntent): void;
}) {
  const [catalog, setCatalog] = useState<LoadCatalogItem[]>([]);
  const [catalogState, setCatalogState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [catalogError, setCatalogError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const [query, setQuery] = useState('');
  const [packageFilter, setPackageFilter] = useState('all');
  const [kindFilter, setKindFilter] = useState('all');
  const [modelFilter, setModelFilter] = useState('all');
  const [validationFilter, setValidationFilter] = useState('all');
  const [environmentDraft, setEnvironmentDraft] = useState(environment);
  const [actionKey, setActionKey] = useState('');
  const [notice, setNotice] = useState('');
  const [source, setSource] = useState<{ item: LoadCatalogItem; text: string; reason?: string } | null>(null);
  const [jobs, setJobs] = useState<CatalogJob[]>([]);
  const [jobsState, setJobsState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [jobsError, setJobsError] = useState('');
  const [jobsMore, setJobsMore] = useState<number | null>(null);

  const loadCatalog = useCallback(async (signal: AbortSignal) => {
    if (packages.status !== 'ready') return;
    setCatalogState('loading'); setCatalogError('');
    try {
      const gathered: LoadCatalogItem[] = [];
      for (const pkg of packages.data) {
        let cursor: string | undefined;
        do {
          const page = await api.listLoadCatalog(pkg.packageId, { query: query.trim() || undefined, cursor, limit: 100 }, signal);
          if (signal.aborted) return;
          gathered.push(...page.items);
          cursor = page.nextCursor || undefined;
        } while (cursor);
      }
      if (!signal.aborted) { setCatalog(gathered); setCatalogState('ready'); }
    } catch (error) {
      if (!signal.aborted) { setCatalogError(error instanceof Error ? error.message : 'LOAD catalog could not be loaded.'); setCatalogState('error'); }
    }
  }, [api, packages, query]);

  useEffect(() => {
    if (packages.status === 'loading') { setCatalogState('loading'); return; }
    if (packages.status === 'error') { setCatalogState('error'); setCatalogError('Configured packages could not be loaded.'); return; }
    const controller = new AbortController(); void loadCatalog(controller.signal);
    return () => controller.abort();
  }, [packages.status, packages.status === 'ready' ? packages.data : null, loadCatalog, refreshKey]);

  const loadJobs = useCallback(async (offset = 0, append = false, signal?: AbortSignal) => {
    setJobsState(offset === 0 ? 'loading' : 'ready'); setJobsError('');
    try {
      const page = await api.listLoadJobs({ offset, limit: 50, signal });
      if (signal?.aborted) return;
      setJobs(current => append ? [...current, ...page.items] : page.items);
      setJobsMore(page.nextOffset ?? null); setJobsState('ready');
    } catch (error) {
      if (!signal?.aborted) { setJobsError(error instanceof Error ? error.message : 'LOAD job history could not be loaded.'); setJobsState('error'); }
    }
  }, [api]);
  useEffect(() => { const controller = new AbortController(); void loadJobs(0, false, controller.signal); return () => controller.abort(); }, [loadJobs]);

  const visible = useMemo(() => catalog.filter(item =>
    (packageFilter === 'all' || item.packageId === packageFilter)
    && (kindFilter === 'all' || item.kind === kindFilter)
    && (modelFilter === 'all' || item.model === modelFilter)
    && (validationFilter === 'all' || item.validation === validationFilter)), [catalog, packageFilter, kindFilter, modelFilter, validationFilter]);

  async function runNow(item: LoadCatalogItem) {
    if (!item.runNow || actionKey) return;
    const confirmation = `Run this package LOAD scenario now?\n\nPackage: ${item.packageId}\nScenario: ${item.path}\nEnvironment: ${environmentDraft || 'Package default'}\nModel: ${item.model || 'Validated by Server'}\nWorkloads: ${item.workloadCount}`;
    if (!window.confirm(confirmation)) return;
    setActionKey(item.resourceId); setNotice('');
    try {
      const job = await api.submitLoadScenario({ packageId: item.packageId, scenario: item.path, ...(environmentDraft ? { environment: environmentDraft } : {}) });
      onJobAccepted(job.jobId);
    } catch (error) { setNotice(error instanceof Error ? error.message : 'The LOAD scenario could not be started.'); }
    finally { setActionKey(''); }
  }

  async function showSource(item: LoadCatalogItem) {
    setActionKey(`source:${item.resourceId}`); setNotice('');
    try {
      const result = await api.getLoadSource(item.packageId, item.resourceId);
      setSource({ item, text: result.text || '', reason: result.reason || (result.available ? undefined : 'Source is unavailable under the package visibility policy.') });
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Safe YAML preview is unavailable.'); }
    finally { setActionKey(''); }
  }

  async function validateItem(item: LoadCatalogItem) {
    if (actionKey) return;
    const key = `validate:${item.resourceId}`; setActionKey(key); setNotice('');
    try {
      const page = await api.listLoadCatalog(item.packageId, { query: item.path, limit: 100 });
      const latest = page.items.find(candidate => candidate.path === item.path);
      if (!latest) throw new Error('The YAML resource is no longer available in this package.');
      setCatalog(current => current.map(candidate => candidate.packageId === item.packageId && candidate.resourceId === item.resourceId ? latest : candidate));
      setNotice(`${item.path} validation refreshed: ${latest.validation}.`);
    } catch (error) { setNotice(error instanceof Error ? error.message : 'The YAML resource could not be validated.'); }
    finally { setActionKey(''); }
  }

  const selectedPackage = packageFilter === 'all' ? packages.status === 'ready' ? packages.data[0]?.packageId : undefined : packageFilter;
  return <div className="page-content att-load-catalog">
    <header className="att-feature-header"><div><p className="att-eyebrow">Load resources and execution history</p><h1>LOAD Catalog</h1>
      <p>Browse validated package scenarios and Quick LOAD policies, then run or schedule from the selected resource.</p></div>
      <div className="att-quick-load-actions"><button className="button button-primary" type="button" onClick={() => onNewLoad(selectedPackage)}>New LOAD</button></div>
    </header>
    {!inlineLoadEnabled && <p className="callout callout-warning" role="status">Quick LOAD composition is disabled by Server policy. Valid package scenarios may still be run directly when Server execution policy allows.</p>}
    <section className="att-load-section" aria-labelledby="load-catalog-heading">
      <div className="att-section-heading"><div><h2 id="load-catalog-heading">Available Load YAML resources</h2><p>Scenario and policy/template definitions are validated on the Server. Execution actions remain permission and policy gated.</p></div>
        <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={catalogState === 'loading'}>{catalogState === 'loading' ? 'Refreshing…' : 'Refresh'}</button></div>
      <div className="att-load-catalog-filters">
        <label>Search resources<input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder="Package-relative YAML path" /></label>
        <label>Package<select value={packageFilter} onChange={event => setPackageFilter(event.currentTarget.value)}><option value="all">All packages</option>{packages.status === 'ready' && packages.data.map(pkg => <option key={pkg.packageId} value={pkg.packageId}>{pkg.displayName || pkg.packageId}</option>)}</select></label>
        <label>Kind<select value={kindFilter} onChange={event => setKindFilter(event.currentTarget.value)}><option value="all">All kinds</option><option value="scenario">Scenario</option><option value="template">Template</option><option value="invalid">Invalid</option></select></label>
        <label>Model<select value={modelFilter} onChange={event => setModelFilter(event.currentTarget.value)}><option value="all">All models</option><option value="virtualUsers">Virtual Users</option><option value="arrivalRate">Arrival Rate</option></select></label>
        <label>Validation<select value={validationFilter} onChange={event => setValidationFilter(event.currentTarget.value)}><option value="all">All validation states</option><option value="valid">Valid</option><option value="invalid">Invalid</option></select></label>
        <label>Environment<input value={environmentDraft} onChange={event => { setEnvironmentDraft(event.currentTarget.value); onEnvironmentChange(event.currentTarget.value); }} placeholder="Package default" /></label>
      </div>
      {catalogState === 'loading' && <p role="status" aria-busy="true">Discovering package Load YAML resources…</p>}
      {catalogState === 'error' && <p className="att-inline-error" role="alert">{catalogError}</p>}
      {notice && <p role="status" className="att-inline-error">{notice}</p>}
      {catalogState === 'ready' && visible.length === 0 && <p role="status">No Load YAML resources match these filters.</p>}
      {catalogState === 'ready' && visible.length > 0 && <div className="att-load-catalog-list" aria-label="Available Load YAML resources">
        {visible.map(item => <article className="att-load-catalog-card" key={`${item.packageId}:${item.resourceId}`}>
          <div className="att-load-catalog-card-heading"><div><span className={`att-load-kind kind-${item.kind}`}>{item.kind === 'template' ? 'Load Template' : item.kind === 'scenario' ? 'Executable Scenario' : 'Invalid'}</span>
            <h3>{item.path}</h3><p>{item.packageId} · {item.model === 'virtualUsers' ? 'Virtual Users' : item.model === 'arrivalRate' ? 'Arrival Rate' : 'Model unavailable'} · {item.workloadCount} workload{item.workloadCount === 1 ? '' : 's'}</p></div>
            <span className={`att-load-validation validation-${item.validation}`}>{item.validation}</span></div>
          {item.diagnostic && <p className="att-help">{item.diagnostic}</p>}
          <div className="att-quick-load-actions">
            <button type="button" disabled={Boolean(actionKey)} onClick={() => void validateItem(item)}>{actionKey === `validate:${item.resourceId}` ? 'Validating…' : 'Validate'}</button>
            {item.kind === 'scenario' && <><button type="button" disabled={!item.runNow || Boolean(actionKey)} onClick={() => void runNow(item)}>{actionKey === item.resourceId ? 'Starting…' : 'Run Now'}</button>
              <button type="button" disabled={!item.schedule || !schedulingEnabled} title={!schedulingEnabled ? 'Schedules are disabled by Server policy.' : undefined}
                onClick={() => onSchedule({ packageId: item.packageId, environment: environmentDraft || undefined, scenario: item.path, executionType: 'load' })}>Schedule</button></>}
            {item.kind === 'template' && <button type="button" disabled={!item.createQuickLoad || !inlineLoadEnabled}
              onClick={() => onQuickLoad(item.packageId, item)}>Create Quick LOAD</button>}
            <button type="button" disabled={!item.sourceAvailable || Boolean(actionKey)} onClick={() => void showSource(item)}>{actionKey === `source:${item.resourceId}` ? 'Loading…' : 'View YAML'}</button>
          </div>
        </article>)}
      </div>}
    </section>
    <section className="att-load-section" aria-labelledby="recent-load-jobs-heading">
      <div className="att-section-heading"><div><h2 id="recent-load-jobs-heading">Recent LOAD Jobs</h2><p>Execution history returned by the Server's authorized LOAD-only query.</p></div>
        <button type="button" onClick={() => void loadJobs() } disabled={jobsState === 'loading'}>{jobsState === 'loading' ? 'Refreshing…' : 'Refresh'}</button></div>
      {jobsState === 'loading' && <p role="status" aria-busy="true">Loading recent LOAD jobs…</p>}
      {jobsState === 'error' && <p className="att-inline-error" role="alert">{jobsError}</p>}
      {jobsState === 'ready' && jobs.length === 0 && <p role="status">No LOAD jobs are available for this account.</p>}
      {jobs.length > 0 && <div className="att-load-job-list">{jobs.map(job => <div className="att-load-job-row" key={job.jobId}>
        <button className="text-link" type="button" onClick={() => onOpenJob(job.jobId)}>{job.jobId}</button>
        <span>{job.packageId}{job.scenario ? ` · ${job.scenario}` : ''}</span>
        <span>{job.model || 'Model unavailable'}{job.environment ? ` · ${job.environment}` : ''}</span>
        <span className={'status-badge status-' + String(job.status).toLowerCase()}>{job.status}</span>
        <span className="att-load-job-times"><time title="Created">Created {job.createdAt ? new Date(job.createdAt).toLocaleString() : 'unavailable'}</time>
          {job.startedAt && <time title="Started">Started {new Date(job.startedAt).toLocaleString()}</time>}
          {job.finishedAt && <time title="Finished">Finished {new Date(job.finishedAt).toLocaleString()}</time>}
          {jobDuration(job) && <span>Duration {jobDuration(job)}</span>}</span>
        <button className="text-link" type="button" onClick={() => onOpenJob(job.jobId)}>View Results</button>
      </div>)}</div>}
      {jobsMore !== null && <button type="button" onClick={() => void loadJobs(jobsMore, true)} disabled={jobsState === 'loading'}>Load more LOAD jobs</button>}
    </section>
    {source && <section className="att-load-section att-load-source-panel" aria-label={`Safe YAML source for ${source.item.path}`}>
      <div className="att-section-heading"><div><h2>{source.item.path}</h2><p>{source.reason || 'Redacted read-only YAML preview'}</p></div><button type="button" onClick={() => setSource(null)}>Close preview</button></div>
      {source.text ? <CodeEditor value={source.text} language="yaml" label={`Safe YAML preview for ${source.item.path}`} readOnly /> : <p role="status">{source.reason || 'Source is unavailable.'}</p>}
    </section>}
  </div>;
}
