import { useEffect, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react';
import './load.css';
import type { JobArtifact, JobEvent, JobResultResponse, JobStatus, JobSummary, JobView, JobsFeatureProps } from './types';

function executionType(job: JobSummary): string { return job.executionType || (job.command === 'debug' ? 'run' : job.command); }

const PAGE_SIZE = 25;
const MAX_EVENTS = 500;
const MAX_LINE_LENGTH = 4000;
const MAX_BUCKET_ROWS = 4096;
const EVENT_TYPES = ['status', 'progress', 'log', 'diagnostic', 'result'] as const;

type LoadState = 'loading' | 'refreshing' | 'ready' | 'error';
type JobTab = 'overview' | 'live' | 'events' | 'results' | 'artifacts' | 'metrics';
const tabs: Array<{ id: JobTab; label: string }> = [
  { id: 'overview', label: 'Overview' },
  { id: 'live', label: 'Live events' },
  { id: 'events', label: 'Events' },
  { id: 'results', label: 'Results' },
  { id: 'artifacts', label: 'Artifacts' },
  { id: 'metrics', label: 'Metrics' },
];

function toError(value: unknown, fallback: string): Error {
  return value instanceof Error ? value : new Error(fallback);
}

function isTerminalStatus(status: JobStatus): boolean {
  return !['QUEUED', 'PREPARING', 'RUNNING', 'CANCEL_REQUESTED'].includes(status);
}

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2); }
  catch { return String(value); }
}

function eventText(event: JobEvent): string {
  const data = event.data;
  if (typeof data === 'string') return data;
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const record = data as Record<string, unknown>;
    for (const key of ['message', 'summary', 'status', 'text']) {
      if (typeof record[key] === 'string') return record[key] as string;
    }
  }
  return displayValue(data);
}

function eventTimestamp(event: JobEvent): string {
  const data = event.data && typeof event.data === 'object' && !Array.isArray(event.data)
    ? event.data as Record<string, unknown> : {};
  const candidate = [data.timestamp, data.createdAt, data.time, data.at, event.receivedAt]
    .find(value => typeof value === 'string' && Number.isFinite(Date.parse(value)));
  return typeof candidate === 'string' ? candidate : '';
}

function chronologicalEvents(events: JobEvent[]): JobEvent[] {
  return events.map((event, index) => ({ event, index })).sort((left, right) => {
    const a = Number(left.event.id); const b = Number(right.event.id);
    return Number.isFinite(a) && Number.isFinite(b) ? a - b : left.index - right.index;
  }).map(item => item.event);
}

function dateTime(value?: string | null): string {
  if (!value) return 'Not reported';
  const time = Date.parse(value);
  return Number.isFinite(time) ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(time) : value;
}

function numericRows(value: unknown, prefix = '', depth = 0, rows: Array<[string, number]> = []): Array<[string, number]> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth > 4 || rows.length >= 100) return rows;
  Object.entries(value as Record<string, unknown>).forEach(([key, child]) => {
    if (rows.length >= 100) return;
    const label = prefix ? `${prefix}.${key}` : key;
    if (typeof child === 'number' && Number.isFinite(child)) rows.push([label, child]);
    else if (child && typeof child === 'object' && !Array.isArray(child)) numericRows(child, label, depth + 1, rows);
  });
  return rows;
}

function resultMetrics(payload: JobResultResponse | null): Record<string, unknown> | null {
  const value = payload?.result?.metrics;
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function EvidenceQuotaSummary({ payload }: { payload: JobResultResponse | null }) {
  const value = payload?.result?.evidence;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const evidence = value as Record<string, unknown>;
  const rawPolicy = evidence.policy;
  if (!rawPolicy || typeof rawPolicy !== 'object' || Array.isArray(rawPolicy)) return null;
  const policy = rawPolicy as Record<string, unknown>;
  const workloadStats = evidence.workloads && typeof evidence.workloads === 'object' && !Array.isArray(evidence.workloads)
    ? evidence.workloads as Record<string, Record<string, unknown>> : {};
  const defaults = policy.defaults && typeof policy.defaults === 'object' && !Array.isArray(policy.defaults)
    ? policy.defaults as Record<string, unknown> : {};
  const perWorkload = policy.quotaMode === 'perWorkload';
  const labels: Record<string, string> = {
    maxSamples: 'Combined limit', maxSuccessSamples: 'Success limit', maxFailureSamples: 'Failure limit',
    retainedSuccessCount: 'Success samples retained', retainedFailureCount: 'Failure samples retained',
    successQuotaExceeded: 'Success limit reached', failureQuotaExceeded: 'Failure limit reached',
    skippedSuccessQuotaCount: 'Success samples skipped at limit', skippedFailureQuotaCount: 'Failure samples skipped at limit',
    skippedMissingEvidenceCount: 'Unavailable evidence skipped',
  };
  return <section aria-label="Retained evidence quotas">
    <h3>Retained evidence quotas</h3>
    <p>{perWorkload ? 'Independent per-workload success and failure caps; unused capacity is never transferred or reclaimed.'
      : policy.quotaMode === 'independent' ? 'Independent success and failure quotas shared across the run'
        : 'Legacy combined cap'}</p>
    <dl>
      {perWorkload && defaults.maxSuccessSamples != null && <div><dt>Default success cap</dt><dd>{displayValue(defaults.maxSuccessSamples)}</dd></div>}
      {perWorkload && defaults.maxFailureSamples != null && <div><dt>Default failure cap</dt><dd>{displayValue(defaults.maxFailureSamples)}</dd></div>}
      {['maxSamples', 'maxSuccessSamples', 'maxFailureSamples'].filter(key => policy[key] != null)
        .map(key => <div key={key}><dt>{labels[key]}</dt><dd>{displayValue(policy[key])}</dd></div>)}
      {['retainedSuccessCount', 'retainedFailureCount', 'successQuotaExceeded', 'failureQuotaExceeded', 'skippedSuccessQuotaCount', 'skippedFailureQuotaCount', 'skippedMissingEvidenceCount']
        .filter(key => evidence[key] != null).map(key => <div key={key}><dt>{labels[key]}</dt><dd>{displayValue(evidence[key])}</dd></div>)}
    </dl>
    {typeof evidence.warning === 'string' && <p role="alert">{evidence.warning}</p>}
    {Object.keys(workloadStats).length > 0 && <>
      <h4>Evidence coverage by workload</h4>
      <div className="att-table-scroll"><table><thead><tr><th>Workload</th><th>Target</th>{perWorkload && <><th>Success cap</th><th>Failure cap</th></>}<th>Eligible successes</th><th>Sampling ineligible</th><th>Success retained</th><th>Failure retained</th><th>Quota skips</th><th>Missing evidence</th></tr></thead>
        <tbody>{Object.entries(workloadStats).map(([workload, stats]) => <tr key={workload}>
          <th scope="row">{workload}</th>
          <td>{displayValue(stats.targetId)}</td>
          {perWorkload && <><td>{displayValue(stats.maxSuccessSamples)}</td><td>{displayValue(stats.maxFailureSamples)}</td></>}
          <td>{displayValue(stats.eligibleSuccessCount)}</td>
          <td>{displayValue(stats.samplingIneligibleSuccessCount)}</td>
          <td>{displayValue(stats.retainedSuccessCount)}</td>
          <td>{displayValue(stats.retainedFailureCount)}</td>
          <td>Success: {displayValue(stats.skippedSuccessQuotaCount)} · Failure: {displayValue(stats.skippedFailureQuotaCount)}</td>
          <td>{displayValue(stats.skippedMissingEvidenceCount)}</td>
        </tr>)}</tbody>
      </table></div>
    </>}
  </section>;
}

type MetricBucket = { key: string; values: Record<string, unknown>; epochMs: number | null };
type MetricSeries = {
  field: string;
  label: string;
  unit: string;
  points: Array<{ epochMs: number; value: number }>;
};

function metricBuckets(value: unknown): MetricBucket[] {
  const entries: Array<[string, unknown]> = Array.isArray(value)
    ? value.map((bucket, index) => [String(index), bucket])
    : value && typeof value === 'object'
      ? Object.entries(value as Record<string, unknown>)
      : [];

  return entries.flatMap(([key, item]) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return [];
    const values = item as Record<string, unknown>;
    const explicitTime = values.bucketStartEpochMs;
    let epochMs: number | null = null;

    if (typeof explicitTime === 'number' && Number.isFinite(explicitTime)) epochMs = explicitTime;
    else if (typeof explicitTime === 'string' && explicitTime.trim() && Number.isFinite(Number(explicitTime))) epochMs = Number(explicitTime);
    else if (typeof values.bucketStart === 'string' && Number.isFinite(Date.parse(values.bucketStart))) epochMs = Date.parse(values.bucketStart);
    else if (/^\d{12,}$/.test(key) && Number.isFinite(Number(key))) epochMs = Number(key);
    else if (Number.isFinite(Date.parse(key))) epochMs = Date.parse(key);

    return [{ key, values, epochMs }];
  }).sort((left, right) => {
    if (left.epochMs === null) return right.epochMs === null ? left.key.localeCompare(right.key) : 1;
    if (right.epochMs === null) return -1;
    return left.epochMs - right.epochMs;
  });
}

function metricSeries(buckets: MetricBucket[]): MetricSeries[] {
  if (!buckets.length) return [];
  const definitions = [
    { field: 'completedTps', label: 'Completed throughput', unit: 'completed requests per 1-second bucket' },
    { field: 'p95Ms', label: 'p95 latency', unit: 'milliseconds' },
    { field: 'p99Ms', label: 'p99 latency', unit: 'milliseconds' },
  ];

  return definitions.flatMap(definition => {
    const points: MetricSeries['points'] = [];
    for (const bucket of buckets) {
      const value = bucket.values[definition.field];
      if (bucket.epochMs === null || typeof value !== 'number' || !Number.isFinite(value)) return [];
      points.push({ epochMs: bucket.epochMs, value });
    }
    return [{ ...definition, points }];
  });
}

function browserTimeZone(): string {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
  catch { return 'UTC'; }
}

function formatBucketTime(epochMs: number): string {
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    timeZone: browserTimeZone(), timeZoneName: 'short',
  }).format(epochMs);
}

function formatChartTime(epochMs: number, timezone: string): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: timezone,
  }).format(epochMs);
}

function loadMetricRows(metrics: Record<string, unknown> | null): Array<[string, number]> {
  if (!metrics) return [];
  return numericRows(Object.fromEntries(Object.entries(metrics).filter(([key]) => key !== 'buckets')));
}

function dateKey(value: string): number {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function unique(values: Array<string | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => Boolean(value)))].sort((a, b) => a.localeCompare(b));
}

export function JobsPage(props: JobsFeatureProps) {
  return props.jobId ? <JobDetail {...props} jobId={props.jobId} /> : <JobsList {...props} />;
}

function JobsList({ api, principal = '', onOpenJob }: JobsFeatureProps) {
  const [rows, setRows] = useState<JobSummary[]>([]);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState('');
  const [serverPrincipal, setServerPrincipal] = useState(principal);
  const [status, setStatus] = useState('');
  const [packageId, setPackageId] = useState('');
  const [command, setCommand] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [sort, setSort] = useState<'newest' | 'oldest' | 'id'>('newest');
  const [page, setPage] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const sequence = useRef(0);

  useEffect(() => {
    let debounce: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();
    const current = ++sequence.current;
    setState(previous => previous === 'ready' || previous === 'refreshing' ? 'refreshing' : 'loading');
    setError('');
    debounce = setTimeout(() => {
      api.listJobs({ principal: serverPrincipal.trim() || undefined, signal: controller.signal })
        .then(items => {
          if (controller.signal.aborted || current !== sequence.current) return;
          setRows(items.slice(0, 100));
          setState('ready');
        })
        .catch(reason => {
          if (controller.signal.aborted || current !== sequence.current) return;
          setError(toError(reason, 'Jobs could not be loaded.').message);
          setState(previous => previous === 'ready' ? 'ready' : 'error');
        });
    }, 180);
    return () => {
      if (debounce) clearTimeout(debounce);
      controller.abort();
    };
  }, [api, serverPrincipal, refreshVersion]);

  const filtered = useMemo(() => {
    const start = fromDate ? new Date(`${fromDate}T00:00:00`).getTime() : Number.NEGATIVE_INFINITY;
    const end = toDate ? new Date(`${toDate}T23:59:59.999`).getTime() : Number.POSITIVE_INFINITY;
    return rows.filter(job => {
      const created = dateKey(job.createdAt || '');
      return (!status || job.status === status)
        && (!packageId || job.packageId === packageId)
        && (!command || executionType(job) === command)
        && created >= start && created <= end;
    }).sort((a, b) => {
      if (sort === 'id') return a.jobId.localeCompare(b.jobId);
      const difference = dateKey(a.createdAt || '') - dateKey(b.createdAt || '');
      return sort === 'oldest' ? difference : -difference;
    });
  }, [rows, status, packageId, command, fromDate, toDate, sort]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount - 1);
  const pageRows = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);
  const resetPage = (setter: (value: string) => void) => (event: ChangeEvent<HTMLSelectElement | HTMLInputElement>) => {
    setter(event.currentTarget.value);
    setPage(0);
  };
  const start = filtered.length ? currentPage * PAGE_SIZE + 1 : 0;
  const end = Math.min((currentPage + 1) * PAGE_SIZE, filtered.length);
  const clearFilters = () => {
    setServerPrincipal(''); setStatus(''); setPackageId(''); setCommand('');
    setFromDate(''); setToDate(''); setSort('newest'); setPage(0);
  };

  return <div className="page-content att-jobs-page att-query-workspace" aria-labelledby="jobs-title">
    <header className="att-feature-header">
      <div><p className="att-eyebrow">Jobs and results</p><h1 id="jobs-title">Jobs</h1>
        <p>Search and review jobs returned by the ATT Server.</p></div>
    </header>

    <section className="att-query-filter-panel att-jobs-filter-panel" aria-label="Job filters">
      <div className="att-query-filter-grid">
      <label>Principal search (Server filter)
        <input value={serverPrincipal} onChange={event => { setServerPrincipal(event.currentTarget.value); setPage(0); }} />
      </label>
      <label>Status
        <select value={status} onChange={resetPage(setStatus)}><option value="">All loaded statuses</option>{status && !rows.some(job => job.status === status) && <option value={status}>{status} (not in latest loaded rows)</option>}{unique(rows.map(job => job.status)).map(value => <option key={value}>{value}</option>)}</select>
      </label>
      <label>Package
        <select value={packageId} onChange={resetPage(setPackageId)}><option value="">All loaded packages</option>{packageId && !rows.some(job => job.packageId === packageId) && <option value={packageId}>{packageId} (not in latest loaded rows)</option>}{unique(rows.map(job => job.packageId)).map(value => <option key={value}>{value}</option>)}</select>
      </label>
      <label>Type
        <select value={command} onChange={resetPage(setCommand)}><option value="">All loaded types</option>{command && !rows.some(job => executionType(job) === command) && <option value={command}>{command} (not in latest loaded rows)</option>}{unique(rows.map(executionType)).map(value => <option key={value}>{value}</option>)}</select>
      </label>
      <label>Created from
        <input type="date" value={fromDate} onChange={resetPage(setFromDate)} />
      </label>
      <label>Created to
        <input type="date" value={toDate} onChange={resetPage(setToDate)} />
      </label>
      <label>Sort
        <select value={sort} onChange={event => { setSort(event.currentTarget.value as typeof sort); setPage(0); }}>
          <option value="newest">Newest first</option><option value="oldest">Oldest first</option><option value="id">Job ID A–Z</option>
        </select>
      </label>
      </div>
      <div className="att-query-filter-actions">
        <button className="button button-quiet" type="button" onClick={clearFilters}>Clear filters</button>
        <button className="button button-quiet" type="button" onClick={() => { setPage(0); setRefreshVersion(value => value + 1); }} disabled={state === 'loading' || state === 'refreshing'}>
          {state === 'refreshing' ? 'Refreshing…' : 'Refresh jobs'}
        </button>
      </div>
    </section>
    <section className="att-query-results-panel" aria-label="Job results">
    <p className="att-list-coverage" role="note">Filters and sorting apply to the API-loaded rows. The API returns at most 100 jobs; principal search is performed by the Server. No environment field is returned for this list.</p>
    <p className="att-list-count att-query-result-status" role="status" aria-live="polite" aria-busy={state === 'loading' || state === 'refreshing'}>
      {state === 'loading' ? 'Loading jobs…' : state === 'refreshing' ? `Refreshing ${rows.length} loaded jobs…` : `Showing ${start}–${end} of ${filtered.length} matching loaded jobs (page ${currentPage + 1} of ${pageCount}).`}
    </p>
    {error && <div className="att-inline-error" role="alert">{error}{rows.length > 0 && ' Showing the last successful result.'}</div>}
    {state === 'error' && rows.length === 0 ? <section className="att-empty-state att-query-empty-state"><h2>Jobs unavailable</h2><p>{error}</p><button type="button" onClick={() => setRefreshVersion(value => value + 1)}>Try again</button></section>
      : filtered.length === 0 && state === 'ready' ? <section className="att-empty-state att-query-empty-state"><h2>No matching jobs</h2><p>{rows.length ? 'No loaded jobs match these filters.' : 'The Server returned no jobs for this principal search.'}</p></section>
        : <div className="att-table-scroll"><table className="att-jobs-table att-data-table">
          <caption>Jobs returned by the Server; most recently created first by default</caption>
          <thead><tr><th scope="col">Job ID</th><th scope="col">Package</th><th scope="col">Type</th><th scope="col">Principal</th><th scope="col">Status</th><th scope="col">Created</th><th scope="col">Open</th></tr></thead>
          <tbody>{pageRows.map(job => <tr key={job.jobId}>
            <th scope="row"><button type="button" className="att-link-button" onClick={() => onOpenJob(job.jobId)}>{job.jobId}</button></th>
            <td>{job.packageId}</td><td>{executionType(job)}</td><td>{job.principal || 'Not reported'}</td>
            <td><StatusBadge status={job.status} /></td><td><time dateTime={job.createdAt || undefined}>{dateTime(job.createdAt)}</time></td>
            <td><button type="button" onClick={() => onOpenJob(job.jobId)}>Open details</button></td>
          </tr>)}</tbody>
        </table></div>}
    {filtered.length > PAGE_SIZE && <nav className="att-pagination att-query-pagination" aria-label="Loaded jobs pages">
      <button type="button" onClick={() => setPage(value => Math.max(0, value - 1))} disabled={currentPage === 0}>Previous</button>
      <span>Page {currentPage + 1} of {pageCount}</span>
      <button type="button" onClick={() => setPage(value => Math.min(pageCount - 1, value + 1))} disabled={currentPage >= pageCount - 1}>Next</button>
    </nav>}
    </section>
  </div>;
}

function JobDetail({ api, jobId, onShowList, confirmCancel }: JobsFeatureProps & { jobId: string }) {
  const [job, setJob] = useState<JobView | null>(null);
  const [jobState, setJobState] = useState<LoadState>('loading');
  const [detailError, setDetailError] = useState('');
  const [result, setResult] = useState<JobResultResponse | null>(null);
  const [resultState, setResultState] = useState<LoadState>('loading');
  const [resultError, setResultError] = useState('');
  const [artifacts, setArtifacts] = useState<JobArtifact[]>([]);
  const [artifactState, setArtifactState] = useState<LoadState>('loading');
  const [artifactError, setArtifactError] = useState('');
  const [activeTab, setActiveTab] = useState<JobTab>('overview');
  const [connection, setConnection] = useState<'connecting' | 'connected' | 'reconnecting'>('connecting');
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [eventTypeFilter, setEventTypeFilter] = useState<string[]>([...EVENT_TYPES]);
  const [eventSearch, setEventSearch] = useState('');
  const [eventAutoScroll, setEventAutoScroll] = useState(true);
  const [eventScrollPaused, setEventScrollPaused] = useState(false);
  const [eventCopyStatus, setEventCopyStatus] = useState('');
  const eventPanelRef = useRef<HTMLElement>(null);
  const [cancelState, setCancelState] = useState<'idle' | 'pending' | 'error'>('idle');
  const [cancelError, setCancelError] = useState('');
  const [detailRefresh, setDetailRefresh] = useState(0);
  const cancellationLock = useRef(false);
  const liveStatus = useMemo(() => [...events].reverse().find(event => event.type === 'status'), [events]);

  useEffect(() => {
    const controller = new AbortController();
    setJobState(job ? 'refreshing' : 'loading');
    setResultState(result ? 'refreshing' : 'loading');
    setArtifactState(artifacts.length ? 'refreshing' : 'loading');
    setDetailError(''); setResultError(''); setArtifactError('');
    api.getJob(jobId, controller.signal).then(value => {
      if (!controller.signal.aborted) { setJob(value); setJobState('ready'); }
    }).catch(error => {
      if (!controller.signal.aborted) { setDetailError(toError(error, 'Job detail could not be loaded.').message); setJobState('error'); }
    });
    api.getJobResult(jobId, controller.signal).then(value => {
      if (!controller.signal.aborted) { setResult(value); setResultState('ready'); }
    }).catch(error => {
      if (!controller.signal.aborted) { setResultError(toError(error, 'Job result is not available.').message); setResultState('error'); }
    });
    api.getJobArtifacts(jobId, controller.signal).then(value => {
      if (!controller.signal.aborted) { setArtifacts(value); setArtifactState('ready'); }
    }).catch(error => {
      if (!controller.signal.aborted) { setArtifactError(toError(error, 'Job artifacts are not available.').message); setArtifactState('error'); }
    });
    return () => controller.abort();
  }, [api, jobId, detailRefresh]);

  useEffect(() => {
    setConnection('connecting');
    setEvents([]);
    return api.subscribeJob(jobId, {
      onOpen: () => setConnection('connected'),
      onError: () => setConnection('reconnecting'),
      onEvent: event => setEvents(current => {
        if (event.id && current.some(existing => existing.id === event.id)) return current;
        if (event.type === 'status') {
          const payload = event.data && typeof event.data === 'object' && !Array.isArray(event.data)
            ? (event.data as Record<string, unknown>).status
            : event.data;
          if (typeof payload === 'string' && payload) setJob(currentJob => currentJob ? { ...currentJob, status: payload } : currentJob);
        }
        return [...current, { ...event, receivedAt: event.receivedAt || new Date().toISOString() }].slice(-MAX_EVENTS);
      }),
    });
  }, [api, jobId]);

  async function cancel() {
    if (!job || cancellationLock.current || !['QUEUED', 'PREPARING', 'RUNNING'].includes(job.status)) return;
    cancellationLock.current = true;
    const allowed = await (confirmCancel || (message => window.confirm(message)))(`Cancel job ${job.jobId}? The Server will stop it when cancellation is safe.`);
    if (!allowed) { cancellationLock.current = false; return; }
    setCancelState('pending'); setCancelError('');
    try {
      const response = await api.cancelJob(jobId);
      setJob(current => {
        if (!current) return current;
        if (isTerminalStatus(current.status) && !isTerminalStatus(response.status)) return current;
        return { ...current, status: response.status };
      });
      setCancelState('idle');
    } catch (error) {
      setCancelError(toError(error, 'The Server could not cancel this job.').message);
      setCancelState('error');
    } finally {
      cancellationLock.current = false;
    }
  }

  const retry = () => setDetailRefresh(value => value + 1);
  const jobStatus = job?.status || 'UNKNOWN';
  const canCancel = Boolean(job && ['QUEUED', 'PREPARING', 'RUNNING'].includes(job.status));
  const metrics = resultMetrics(result);
  const orderedEvents = useMemo(() => chronologicalEvents(events), [events]);
  const visibleEvents = useMemo(() => {
    const query = eventSearch.trim().toLocaleLowerCase();
    const allEventTypesSelected = eventTypeFilter.length === EVENT_TYPES.length;
    return orderedEvents.filter(event => (allEventTypesSelected || eventTypeFilter.includes(event.type))
      && (!query || `${event.type} ${event.id || ''} ${eventText(event)}`.toLocaleLowerCase().includes(query)));
  }, [orderedEvents, eventTypeFilter, eventSearch]);
  const buckets = metricBuckets(metrics?.buckets).slice(0, MAX_BUCKET_ROWS);
  const charts = metricSeries(buckets);

  useEffect(() => {
    if (activeTab !== 'live' || !eventAutoScroll || eventScrollPaused) return;
    const list = eventPanelRef.current?.querySelector<HTMLOListElement>('.att-job-events');
    if (list) list.scrollTop = list.scrollHeight;
  }, [activeTab, eventAutoScroll, eventScrollPaused, visibleEvents.length]);

  async function copyEvents() {
    const value = visibleEvents.map(event => {
      const timestamp = eventTimestamp(event);
      return [timestamp, event.type, event.id ? `#${event.id}` : '', eventText(event)].filter(Boolean).join(' · ');
    }).join('\n');
    try {
      await navigator.clipboard.writeText(value);
      setEventCopyStatus(`${visibleEvents.length} events copied.`);
    } catch {
      setEventCopyStatus('Clipboard access is unavailable.');
    }
  }

  function toggleEventType(type: string, checked: boolean) {
    setEventTypeFilter(current => checked
      ? current.includes(type) ? current : [...current, type]
      : current.filter(item => item !== type));
  }

  function toggleAllEventTypes(checked: boolean) {
    setEventTypeFilter(checked ? [...EVENT_TYPES] : []);
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const index = tabs.findIndex(tab => tab.id === activeTab);
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    setActiveTab(tabs[next].id);
    document.getElementById(`job-tab-${tabs[next].id}`)?.focus();
  }

  return <div className="page-content att-job-detail" aria-labelledby="job-title">
    <header className="att-feature-header">
      <div><p className="att-eyebrow"><button className="att-link-button" type="button" onClick={onShowList}>Jobs and results</button></p>
        <h1 id="job-title">Job {jobId}</h1><p>Server status, live events, results, artifacts and reported metrics.</p></div>
      <div className="att-job-actions">
        <StatusBadge status={jobStatus} />
        {canCancel && <button className="att-button-danger" type="button" onClick={() => void cancel()} disabled={cancelState === 'pending'}>{cancelState === 'pending' ? 'Cancel requested…' : 'Cancel job'}</button>}
        <button type="button" onClick={retry} disabled={jobState === 'loading' || jobState === 'refreshing'}>{jobState === 'refreshing' ? 'Refreshing…' : 'Refresh details'}</button>
      </div>
    </header>
    {jobState === 'loading' && <p role="status" aria-busy="true">Loading job {jobId}…</p>}
    {detailError && <div role="alert" className="att-inline-error">{detailError}<button type="button" onClick={retry}>Retry</button></div>}
    {cancelError && <p role="alert" className="att-inline-error">{cancelError}</p>}
    {cancelState === 'pending' && <p role="status" aria-live="polite">Cancellation request sent; waiting for the Server status update.</p>}

    <div className="att-job-tabs" role="tablist" aria-label={`Job ${jobId} sections`} onKeyDown={onTabKeyDown}>
      {tabs.map(tab => <button key={tab.id} type="button" role="tab" id={`job-tab-${tab.id}`} aria-controls={`job-panel-${tab.id}`} aria-selected={activeTab === tab.id} tabIndex={activeTab === tab.id ? 0 : -1} onClick={() => setActiveTab(tab.id)}>{tab.label}</button>)}
    </div>
    <section className="att-job-panel" role="tabpanel" id="job-panel-overview" aria-labelledby="job-tab-overview" hidden={activeTab !== 'overview'} tabIndex={0}>
      {job ? <dl className="att-job-overview">
        <div><dt>Job ID</dt><dd>{job.jobId}</dd></div><div><dt>Status</dt><dd><StatusBadge status={job.status} /></dd></div>
        <div><dt>Execution type</dt><dd>{executionType(job)}</dd></div><div><dt>Command</dt><dd>{job.command || 'Not reported'}</dd></div><div><dt>Package</dt><dd>{job.packageId || 'Not reported'}</dd></div>
        <div><dt>Principal</dt><dd>{job.principal || 'Not reported'}</dd></div><div><dt>Created</dt><dd><time dateTime={job.createdAt || undefined}>{dateTime(job.createdAt)}</time></dd></div>
        <div><dt>Started</dt><dd><time dateTime={job.startedAt || undefined}>{dateTime(job.startedAt)}</time></dd></div><div><dt>Finished</dt><dd><time dateTime={job.finishedAt || undefined}>{dateTime(job.finishedAt)}</time></dd></div>
        <div><dt>Exit code</dt><dd>{job.exitCode ?? 'Not reported'}</dd></div>
      </dl> : jobState !== 'loading' && <p>Job details are unavailable.</p>}
      {liveStatus && <p className="att-live-status" role="status">Latest live status event: {eventText(liveStatus)}</p>}
    </section>
    <section ref={eventPanelRef} className="att-job-panel" role="tabpanel" id="job-panel-live" aria-labelledby="job-tab-live" hidden={activeTab !== 'live'} tabIndex={0}>
      <div className="att-live-heading"><h2>Live events</h2><span role="status" data-state={connection}>SSE {connection}</span></div>
      <p className="att-help">The browser EventSource reconnects automatically; the Server resumes from the last delivered event ID. Job state appears separately above.</p>
      <div className="att-event-controls" aria-label="Event controls">
        <fieldset className="att-event-type-filters"><legend>Filter event type</legend>
          <label><input type="checkbox" aria-label="All Events" checked={eventTypeFilter.length === EVENT_TYPES.length}
            onChange={event => toggleAllEventTypes(event.currentTarget.checked)} /> All Events</label>
          {EVENT_TYPES.map(type => <label key={type}><input type="checkbox" aria-label={type}
            checked={eventTypeFilter.includes(type)} onChange={event => toggleEventType(type, event.currentTarget.checked)} /> {type}</label>)}
        </fieldset>
        <label>Search events<input aria-label="Search events" type="search" value={eventSearch} onChange={event => setEventSearch(event.currentTarget.value)} placeholder="Search event text" /></label>
        <label><input type="checkbox" checked={eventAutoScroll} onChange={event => setEventAutoScroll(event.currentTarget.checked)} /> Auto-scroll</label>
        <button type="button" onClick={() => setEventScrollPaused(value => !value)} aria-pressed={eventScrollPaused}>{eventScrollPaused ? 'Resume auto-scroll' : 'Pause auto-scroll'}</button>
        <button type="button" onClick={() => void copyEvents()} disabled={!visibleEvents.length}>Copy visible events</button>
      </div>
      {eventCopyStatus && <p role="status">{eventCopyStatus}</p>}
      {visibleEvents.length ? <ol className="att-job-events" aria-label="Chronological job events">{visibleEvents.map((event, index) => {
        const timestamp = eventTimestamp(event);
        return <li key={event.id || `${index}-${event.type}`}><strong>{event.type}</strong>{event.id && <code>#{event.id}</code>}{timestamp && <time dateTime={timestamp}>{dateTime(timestamp)}</time>}<pre>{eventText(event).slice(0, MAX_LINE_LENGTH)}</pre></li>;
      })}</ol>
        : <p role="status">{events.length ? 'No events match the current filters.'
          : connection === 'connected' ? 'Connected; no events have been emitted yet.'
            : connection === 'reconnecting' ? 'Reconnecting to live events; no recent events are available.'
              : 'Connecting to live events…'}</p>}
      {events.length >= MAX_EVENTS && <p role="note">The oldest events are discarded after {MAX_EVENTS} entries to keep the view bounded.</p>}
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-events" aria-labelledby="job-tab-events" hidden={activeTab !== 'events'} tabIndex={0}>
      <h2>Events</h2><p role="status">SSE {connection}; {events.length} recent events retained in this view.</p>
      {orderedEvents.length ? <ol className="att-job-events" aria-label="Structured job events">{orderedEvents.map((event, index) => {
        const timestamp = eventTimestamp(event);
        return <li key={event.id || `${index}-${event.type}`}><strong>{event.type}</strong>{event.id && <code>#{event.id}</code>}{timestamp && <time dateTime={timestamp}>{dateTime(timestamp)}</time>}<pre>{eventText(event).slice(0, MAX_LINE_LENGTH)}</pre></li>;
      })}</ol> : <p>No SSE events have been returned.</p>}
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-results" aria-labelledby="job-tab-results" hidden={activeTab !== 'results'} tabIndex={0}>
      <h2>Results</h2>
      {resultState === 'loading' && <p role="status" aria-busy="true">Loading result…</p>}
      {resultState === 'refreshing' && <p role="status" aria-busy="true">Refreshing result…</p>}
      {resultError && <p role="alert" className="att-inline-error">{resultError}</p>}
      {result && <><p>{result.result ? 'Server result returned.' : 'The Server has not returned a result payload.'}</p>
        {result.diagnostic && <details><summary>Server diagnostic</summary><pre>{displayValue(result.diagnostic)}</pre></details>}
        <EvidenceQuotaSummary payload={result} />
        {result.result && <details open><summary>Raw result payload</summary><pre className="att-raw-result">{displayValue(result.result)}</pre></details>}
      </>}
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-artifacts" aria-labelledby="job-tab-artifacts" hidden={activeTab !== 'artifacts'} tabIndex={0}>
      <h2>Artifacts</h2>
      {artifactState === 'loading' && <p role="status" aria-busy="true">Loading artifacts…</p>}
      {artifactError && <p role="alert" className="att-inline-error">{artifactError}</p>}
      {artifactState === 'ready' && artifacts.length === 0 && <p>No artifacts were returned for this job.</p>}
      {artifacts.length > 0 && <ul>{artifacts.map(item => <li key={item.path}><a href={api.artifactHref(jobId, item.path)}>{item.path}</a>{typeof item.size === 'number' && <span> · {item.size.toLocaleString()} bytes</span>}</li>)}</ul>}
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-metrics" aria-labelledby="job-tab-metrics" hidden={activeTab !== 'metrics'} tabIndex={0}>
      <h2>Metrics</h2>
      <p className="att-help">Only values returned by the job API are shown. Server timings and Worker JVM metrics are separate from Load result metrics.</p>
      {job && <MetricTable title="Server timings" rows={numericRows(job.performance?.timings)} unit="ms" />}
      {job && <MetricTable title="Worker metrics" rows={numericRows(job.performance?.worker)} />}
      {metrics && <MetricTable title="Load result metrics" rows={loadMetricRows(metrics)} />}
      {!metrics && <p role="status">{resultState === 'loading' || resultState === 'refreshing' ? 'Load result metrics are loading.' : job?.command === 'load' ? 'No Load metrics summary was returned. No chart or derived metric is shown.' : 'This command has no Load metric summary.'}</p>}
      {buckets.length > 0 && charts.map(series => <TimeSeriesChart key={series.field} series={series} />)}
      {buckets.length > 0 && !charts.length && <p role="status">The Server returned buckets, but this view cannot chart them without timestamps and complete numeric samples. Raw returned values remain available in the table.</p>}
      {buckets.length > 0 && <BucketTable buckets={buckets} />}
      {metrics && !buckets.length && <p role="status">No time-series buckets were returned. Summary metrics are shown in the table above.</p>}
      {!job?.performance?.timings && !job?.performance?.worker && !metrics && resultState !== 'loading' && <p role="status">The API did not return metrics for this job.</p>}
    </section>
  </div>;
}

function StatusBadge({ status }: { status: JobStatus }) {
  return <span className={`att-status-badge att-status-${String(status).toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>{status}</span>;
}

function MetricTable({ title, rows, unit = '' }: { title: string; rows: Array<[string, number]>; unit?: string }) {
  return <section className="att-metric-section" aria-label={title}><h3>{title}</h3>
    {rows.length ? <div className="att-table-scroll"><table><caption>{title} returned by the ATT API</caption><thead><tr><th scope="col">Field</th><th scope="col">Returned value</th></tr></thead><tbody>
      {rows.map(([name, value]) => <tr key={name}><th scope="row">{name}</th><td>{new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 }).format(value)}{unit && <span> {unit}</span>}</td></tr>)}
    </tbody></table></div> : <p role="status">No {title.toLowerCase()} were returned.</p>}
  </section>;
}

function TimeSeriesChart({ series }: { series: MetricSeries }) {
  const width = 820;
  const height = 250;
  const left = 62;
  const right = 16;
  const top = 16;
  const bottom = 42;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const firstTime = series.points[0].epochMs;
  const lastTime = series.points[series.points.length - 1].epochMs;
  const maxValue = Math.max(1, ...series.points.map(point => point.value));
  const timeSpan = Math.max(1, lastTime - firstTime);
  const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
  const titleId = 'att-chart-' + series.field + '-title';
  const descId = 'att-chart-' + series.field + '-description';
  const coordinates = series.points.map(point => ({
    x: left + ((point.epochMs - firstTime) / timeSpan) * plotWidth,
    y: top + (1 - point.value / maxValue) * plotHeight,
  }));
  const path = coordinates.map((point, index) => (index ? 'L ' : 'M ') + point.x.toFixed(2) + ' ' + point.y.toFixed(2)).join(' ');
  const timezone = browserTimeZone();

  return <figure className="att-metric-chart" aria-labelledby={titleId}>
    <figcaption>
      <h3 id={titleId}>{series.label}</h3>
      <p>Y axis: {series.unit}. X axis: bucket start time in {timezone}. Each point is a value returned by the Server; no points are interpolated.</p>
    </figcaption>
    <svg className="att-metric-chart-svg" viewBox={'0 0 ' + width + ' ' + height} role="img" aria-label={series.label + ' by bucket start time'} aria-describedby={descId}>
      <title>{series.label} by bucket start time</title>
      <desc id={descId}>{series.points.length} ordered server samples from {formatBucketTime(firstTime)} to {formatBucketTime(lastTime)}.</desc>
      {[0, 0.25, 0.5, 0.75, 1].map(fraction => {
        const y = top + (1 - fraction) * plotHeight;
        return <g className="att-metric-chart-grid" key={fraction}>
          <line x1={left} x2={width - right} y1={y} y2={y} />
          <text x={left - 8} y={y + 4} textAnchor="end">{numberFormat.format(maxValue * fraction)}</text>
        </g>;
      })}
      <line className="att-metric-chart-axis" x1={left} x2={left} y1={top} y2={height - bottom} />
      <line className="att-metric-chart-axis" x1={left} x2={width - right} y1={height - bottom} y2={height - bottom} />
      {coordinates.length > 1
        ? <path className="att-metric-chart-line" d={path} />
        : <circle className="att-metric-chart-line" cx={coordinates[0].x} cy={coordinates[0].y} r="4" />}
      <text className="att-metric-chart-time" x={left} y={height - 10} textAnchor="start">{formatChartTime(firstTime, timezone)}</text>
      <text className="att-metric-chart-time" x={width - right} y={height - 10} textAnchor="end">{formatChartTime(lastTime, timezone)}</text>
    </svg>
  </figure>;
}

function BucketTable({ buckets }: { buckets: MetricBucket[] }) {
  const columns = unique(buckets.flatMap(bucket => Object.keys(bucket.values)));
  return <section className="att-metric-section" aria-label="Returned Load time-series buckets">
    <h3>Load time-series buckets</h3><p>Table alternative using the exact bucket fields returned by the Server; timestamps are shown in the local timezone, and no points are interpolated.</p>
    <div className="att-table-scroll"><table><caption>{buckets.length} returned buckets, capped at {MAX_BUCKET_ROWS} rows by this view</caption><thead><tr><th scope="col">Bucket start ({browserTimeZone()})</th>{columns.map(key => <th scope="col" key={key}>{key}</th>)}</tr></thead>
      <tbody>{buckets.map(bucket => <tr key={bucket.key}>
        <td>{bucket.epochMs === null ? bucket.key : <time dateTime={new Date(bucket.epochMs).toISOString()}>{formatBucketTime(bucket.epochMs)}</time>}</td>
        {columns.map(key => <td key={key}>{displayValue(bucket.values[key] ?? '')}</td>)}
      </tr>)}</tbody>
    </table></div>
  </section>;
}
