import { JobStateIcons } from "./JobStateIcons";
import { JobsList } from "./JobsList";
import { formatRoute } from "../../router";
import { JobReplayActions } from "./JobReplayActions";
import { StatusBadge } from './StatusBadge';
import { ArchiveMetrics } from './ArchiveMetrics';
import type { ArtifactArchiveMetrics } from './types';
import { memo, Profiler, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import './load.css';
import { JobArtifacts } from './JobArtifacts';
import type { JobResultResponse, JobStatus, JobSummary, JobView, JobsFeatureProps } from './types';
import { JobConsoleView } from './JobConsoleView';
import { useJobConsole } from './JobConsoleContext';
import { useJobEventStatus } from './useJobEventStream';

function executionType(job: JobSummary): string { return job.executionType || (job.command === 'debug' ? 'run' : job.command); }

const MAX_BUCKET_ROWS = 4096;
const BUCKET_PAGE_SIZE = 100;

type LoadState = 'loading' | 'refreshing' | 'ready' | 'error';
type JobTab = 'overview' | 'live' | 'events' | 'results' | 'artifacts' | 'metrics';
const tabs: Array<{ id: JobTab; label: string }> = [
  { id: 'overview', label: 'Overview' },
  { id: 'live', label: 'Live Output' },
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

const LOCAL_TIME_ZONE = (() => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
  catch { return 'UTC'; }
})();
const DATE_TIME_FORMATTER = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
const BUCKET_TIME_FORMATTER = new Intl.DateTimeFormat(undefined, {
  year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  timeZone: LOCAL_TIME_ZONE, timeZoneName: 'short',
});
const CHART_TIME_FORMATTER = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: LOCAL_TIME_ZONE });
const METRIC_NUMBER_FORMATTER = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const CHART_NUMBER_FORMATTER = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2); }
  catch { return String(value); }
}

function dateTime(value?: string | null): string {
  if (!value) return 'Not reported';
  const time = Date.parse(value);
  return Number.isFinite(time) ? DATE_TIME_FORMATTER.format(time) : value;
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

export type MetricBucket = { key: string; values: Record<string, unknown>; epochMs: number | null };
export type MetricSeries = {
  field: string;
  label: string;
  unit: string;
  points: Array<{ epochMs: number; value: number }>;
};

/** The chart remains readable while the table retains every returned bucket. */
export const CHART_DISPLAY_POINT_THRESHOLD = 1000;

export function downsampleMetricPoints(
  points: MetricSeries['points'],
  maxPoints = CHART_DISPLAY_POINT_THRESHOLD,
): MetricSeries['points'] {
  if (points.length <= maxPoints || maxPoints < 3) return points;

  const interior = points.slice(1, -1);
  const bucketCount = Math.max(1, Math.min(Math.floor((maxPoints - 2) / 2), interior.length));
  const sampled: MetricSeries['points'] = [points[0]];
  for (let bucket = 0; bucket < bucketCount; bucket += 1) {
    const start = Math.floor(bucket * interior.length / bucketCount);
    const end = Math.max(start + 1, Math.floor((bucket + 1) * interior.length / bucketCount));
    let minIndex = start;
    let maxIndex = start;
    for (let index = start + 1; index < end; index += 1) {
      if (interior[index].value < interior[minIndex].value) minIndex = index;
      if (interior[index].value > interior[maxIndex].value) maxIndex = index;
    }
    for (const index of [minIndex, maxIndex].sort((left, right) => left - right)) {
      const point = interior[index];
      if (sampled[sampled.length - 1] !== point) sampled.push(point);
    }
  }
  sampled.push(points[points.length - 1]);
  return sampled;
}

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
  return LOCAL_TIME_ZONE;
}

function formatBucketTime(epochMs: number): string {
  return BUCKET_TIME_FORMATTER.format(epochMs);
}

function formatChartTime(epochMs: number, _timezone: string): string {
  return CHART_TIME_FORMATTER.format(epochMs);
}

function loadMetricRows(metrics: Record<string, unknown> | null): Array<[string, number]> {
  if (!metrics) return [];
  return numericRows(Object.fromEntries(Object.entries(metrics).filter(([key]) => key !== 'buckets')));
}



function unique(values: Array<string | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => Boolean(value)))].sort((a, b) => a.localeCompare(b));
}

export const JobOverview = memo(function JobOverview({ job, status, statusText, jobState, hidden, onProfilerCommit }: { job: JobView | null; status: JobStatus; statusText?: string; jobState: LoadState; hidden: boolean; onProfilerCommit?: () => void }) {
  return <section className="att-job-panel" role="tabpanel" id="job-panel-overview" aria-labelledby="job-tab-overview" hidden={hidden} tabIndex={0}>
    <Profiler id="job-overview-content" onRender={() => onProfilerCommit?.()}>{job ? <dl className="att-job-overview">
      <div><dt>Job ID</dt><dd>{job.jobId}</dd></div><div><dt>Status</dt><dd><StatusBadge status={status} /></dd></div>
      <div><dt>Execution type</dt><dd>{executionType(job)}</dd></div><div><dt>Command</dt><dd>{job.command || 'Not reported'}</dd></div><div><dt>Package</dt><dd>{job.packageId || 'Not reported'}</dd></div>
      {job.rerunOfJobId && <div><dt>Rerun of</dt><dd><a href={formatRoute({ kind: 'jobs', jobId: job.rerunOfJobId })}>{job.rerunOfJobId}</a></dd></div>}
      <div><dt>Principal</dt><dd>{job.principal || 'Not reported'}</dd></div><div><dt>Created</dt><dd><time dateTime={job.createdAt || undefined}>{dateTime(job.createdAt)}</time></dd></div>
      <div><dt>Started</dt><dd><time dateTime={job.startedAt || undefined}>{dateTime(job.startedAt)}</time></dd></div><div><dt>Finished</dt><dd><time dateTime={job.finishedAt || undefined}>{dateTime(job.finishedAt)}</time></dd></div>
      <div><dt>Exit code</dt><dd>{job.exitCode ?? 'Not reported'}</dd></div>
      {job.pinned && <div><dt>Pin reason</dt><dd>{job.pinReason || 'Not provided'}</dd></div>}
    </dl> : jobState !== 'loading' && <p>Job details are unavailable.</p>}</Profiler>
    {statusText && <p className="att-live-status" role="status">Latest live status event: {statusText}</p>}
  </section>;
});

const LiveOutputPanel = memo(function LiveOutputPanel({ api, jobId, job, hidden }: { api: JobsFeatureProps['api']; jobId: string; job: JobView | null; hidden: boolean }) {
  return <section className="att-job-panel" role="tabpanel" id="job-panel-live" aria-labelledby="job-tab-live" hidden={hidden} tabIndex={0}>
    <JobConsoleView api={api} jobId={jobId} job={job} view="live" showSwitcher={false} />
  </section>;
});

const StructuredEventsPanel = memo(function StructuredEventsPanel({ api, jobId, job, hidden }: { api: JobsFeatureProps['api']; jobId: string; job: JobView | null; hidden: boolean }) {
  return <section className="att-job-panel" role="tabpanel" id="job-panel-events" aria-labelledby="job-tab-events" hidden={hidden} tabIndex={0}>
    <JobConsoleView api={api} jobId={jobId} job={job} view="events" showSwitcher={false} />
  </section>;
});

export const ArtifactsPanel = memo(function ArtifactsPanel({ api, jobId, refresh, onMetrics, hidden, onProfilerCommit }: { api: JobsFeatureProps['api']; jobId: string; refresh: number; onMetrics: (metrics: ArtifactArchiveMetrics | null) => void; hidden: boolean; onProfilerCommit?: () => void }) {
  return <section className="att-job-panel" role="tabpanel" id="job-panel-artifacts" aria-labelledby="job-tab-artifacts" hidden={hidden} tabIndex={0}>
    <Profiler id="job-artifacts-content" onRender={() => onProfilerCommit?.()}><h2>Artifacts</h2>
      <JobArtifacts key={jobId} api={api} jobId={jobId} refresh={refresh} onMetrics={onMetrics} /></Profiler>
  </section>;
});

export const MetricsPanel = memo(function MetricsPanel({ job, metrics, resultState, archiveMetrics, buckets, charts, hidden, onProfilerCommit }: {
  job: JobView | null;
  metrics: Record<string, unknown> | null;
  resultState: LoadState;
  archiveMetrics: ArtifactArchiveMetrics | null;
  buckets: MetricBucket[];
  charts: MetricSeries[];
  hidden: boolean;
  onProfilerCommit?: () => void;
}) {
  return <section className="att-job-panel" role="tabpanel" id="job-panel-metrics" aria-labelledby="job-tab-metrics" hidden={hidden} tabIndex={0}>
    <Profiler id="job-metrics-content" onRender={() => onProfilerCommit?.()}><h2>Metrics</h2>
    <p className="att-help">Only values returned by the job API are shown. Server timings and Worker JVM metrics are separate from Load result metrics.</p>
    {job && <MetricTable title="Server timings" rows={numericRows(job.performance?.timings)} unit="ms" />}
    {job && <MetricTable title="Worker metrics" rows={numericRows(job.performance?.worker)} />}
    <ArchiveMetrics value={archiveMetrics || job?.archiveMetrics} />
    {metrics && <MetricTable title="Load result metrics" rows={loadMetricRows(metrics)} />}
    {!metrics && <p role="status">{resultState === 'loading' || resultState === 'refreshing' ? 'Load result metrics are loading.' : job?.command === 'load' ? 'No Load metrics summary was returned. No chart or derived metric is shown.' : 'This command has no Load metric summary.'}</p>}
    {buckets.length > 0 && charts.map(series => <TimeSeriesChart key={series.field} series={series} />)}
    {buckets.length > 0 && !charts.length && <p role="status">The Server returned buckets, but this view cannot chart them without timestamps and complete numeric samples. Raw returned values remain available in the table.</p>}
    {buckets.length > 0 && <BucketTable buckets={buckets} />}
    {metrics && !buckets.length && <p role="status">No time-series buckets were returned. Summary metrics are shown in the table above.</p>}
    {!job?.performance?.timings && !job?.performance?.worker && !archiveMetrics && !job?.archiveMetrics && !metrics && resultState !== 'loading' && <p role="status">The API did not return metrics for this job.</p>}</Profiler>
  </section>;
});

export function JobsPage(props: JobsFeatureProps) {
  return props.jobId ? <JobDetail key={props.jobId} {...props} jobId={props.jobId} /> : <JobsList {...props} />;
}

function JobDetail({ api, jobId, onShowList, confirmCancel, onOpenJob, onSchedule, onEditSchedule, initialTab, onTabChange, standalone }: JobsFeatureProps & { jobId: string }) {
  const [job, setJob] = useState<JobView | null>(null);
  const [jobActionBusy, setJobActionBusy] = useState(false);
  const [jobActionStatus, setJobActionStatus] = useState('');
  const [jobState, setJobState] = useState<LoadState>('loading');
  const [detailError, setDetailError] = useState('');
  const [result, setResult] = useState<JobResultResponse | null>(null);
  const [resultState, setResultState] = useState<LoadState>('loading');
  const [resultError, setResultError] = useState('');
  const [activeTab, setActiveTabState] = useState<JobTab>(initialTab || 'overview');
  useEffect(() => setActiveTabState(initialTab || 'overview'), [initialTab]);
  const setActiveTab = (tab: JobTab) => { setActiveTabState(tab); onTabChange?.(tab); };
  const [cancelState, setCancelState] = useState<'idle' | 'pending' | 'error'>('idle');
  const [cancelError, setCancelError] = useState('');
  const [detailRefresh, setDetailRefresh] = useState(0);
  const [terminalRefresh, setTerminalRefresh] = useState(0);
  const [archiveMetrics, setArchiveMetrics] = useState<ArtifactArchiveMetrics | null>(null);
  useEffect(() => setArchiveMetrics(null), [jobId]);
  const cancellationLock = useRef(false);
  const terminalStatus = useRef<JobStatus | null>(null);
  const terminalReconciled = useRef(false);
  const requestSequence = useRef(0);
  const consoleContext = useJobConsole();
  const statusSnapshot = useJobEventStatus(api, jobId, job?.status);
  const selectedStatus = (statusSnapshot.status || job?.status || 'UNKNOWN') as JobStatus;
  const displayedJob = useMemo(() => job && selectedStatus !== job.status ? { ...job, status: selectedStatus } : job, [job, selectedStatus]);

  useEffect(() => {
    const controller = new AbortController();
    const sequence = ++requestSequence.current;
    // SSE status always wins over the REST snapshot when the page chooses the displayed status,
    // so a newer status event must not discard this response (that would leave the detail empty).
    const isCurrent = () => !controller.signal.aborted && sequence === requestSequence.current;
    setJobState(job ? 'refreshing' : 'loading');
    setResultState(result ? 'refreshing' : 'loading');
    setDetailError(''); setResultError('');
    api.getJob(jobId, controller.signal).then(value => {
      if (isCurrent()) {
        setJob(terminalStatus.current ? { ...value, status: terminalStatus.current } : value);
        setJobState('ready');
      }
    }).catch(error => {
      if (isCurrent()) { setDetailError(toError(error, 'Job detail could not be loaded.').message); setJobState('error'); }
    });
    api.getJobResult(jobId, controller.signal).then(value => {
      if (isCurrent()) { setResult(value); setResultState('ready'); }
    }).catch(error => {
      if (isCurrent()) { setResultError(toError(error, 'Job result is not available.').message); setResultState('error'); }
    });
    return () => controller.abort();
  }, [api, jobId, detailRefresh, terminalRefresh]);

  useEffect(() => {
    if (statusSnapshot.eventRevision === 0) return;
    const terminal = Boolean(statusSnapshot.status && isTerminalStatus(statusSnapshot.status as JobStatus));
    if (terminal) terminalStatus.current = statusSnapshot.status as JobStatus;
    if (!terminalReconciled.current && (terminal || statusSnapshot.resultEvent)) {
      terminalReconciled.current = true;
      setTerminalRefresh(value => value + 1);
    }
  }, [statusSnapshot]);

  async function changeJobPin(pinned: boolean, reason = '') {
    setJobActionBusy(true);
    try { setJob(await api.pinJob!(jobId, pinned, reason)); }
    catch (error) { setJobActionStatus(toError(error, 'Pin change failed.').message); }
    finally { setJobActionBusy(false); }
  }

  async function changeDeletion() {
    if (!job || !api.setDeletionRequested || jobActionBusy) return;
    setJobActionBusy(true);
    try {
      const outcomes = await api.setDeletionRequested([jobId], job.deletionState !== 'DELETE_REQUESTED');
      setJobActionStatus(outcomes.map(item => item.outcome.replaceAll('_', ' ')).join('; '));
      setDetailRefresh(value => value + 1);
    } catch (error) { setJobActionStatus(toError(error, 'Deletion mark failed.').message); }
    finally { setJobActionBusy(false); }
  }

  async function cancel() {
    if (!job || cancellationLock.current || !['QUEUED', 'PREPARING', 'RUNNING'].includes(jobStatus)) return;
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
  const jobStatus = selectedStatus;
  const canCancel = Boolean(job && ['QUEUED', 'PREPARING', 'RUNNING'].includes(jobStatus));
  const metrics = useMemo(() => resultMetrics(result), [result]);
  const buckets = useMemo(() => metricBuckets(metrics?.buckets).slice(0, MAX_BUCKET_ROWS), [metrics]);
  const charts = useMemo(() => metricSeries(buckets), [buckets]);

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

  return <div className={`page-content att-job-detail${standalone ? " att-standalone-job-details" : ""}`} aria-labelledby="job-title">
    <header className="att-feature-header">
      <div><p className="att-eyebrow"><button className="att-link-button" type="button" onClick={onShowList}>Jobs and results</button></p>
        <h1 id="job-title">Job {jobId}</h1><p>Server status, live output, results, artifacts and reported metrics.</p></div>
      <div className="att-job-actions">
        <StatusBadge status={jobStatus} />
        <JobReplayActions api={api} configuration={job ? { executionType: executionType(job), packageId: job.packageId } : undefined} jobId={jobId} onOpenJob={onOpenJob} onSchedule={onSchedule} onEditSchedule={onEditSchedule} />
        {!standalone && <button type="button" onClick={() => consoleContext.openConsole({ jobId, view: activeTab === 'events' ? 'events' : 'live', job })}>Open floating console</button>}
        {job?.pinned && <span title={`Pinned by ${job.pinnedBy || 'operator'} at ${job.pinnedAt || 'unknown time'}${job.pinReason ? ` · ${job.pinReason}` : ''}`}>📌 Pinned</span>}
        {job && <JobStateIcons job={job} busy={jobActionBusy} onPin={api.pinJob ? () => void changeJobPin(!job.pinned) : undefined} onDelete={api.setDeletionRequested ? () => void changeDeletion() : undefined} />}
        {canCancel && <button className="att-button-danger" type="button" onClick={() => void cancel()} disabled={cancelState === 'pending'}>{cancelState === 'pending' ? 'Cancel requested…' : 'Cancel job'}</button>}
        <button type="button" onClick={retry} disabled={jobState === 'loading' || jobState === 'refreshing'}>{jobState === 'refreshing' ? 'Refreshing…' : 'Refresh details'}</button>
      </div>
    </header>
    {jobActionStatus && <p role="status">{jobActionStatus}</p>}
    {jobState === 'loading' && <p role="status" aria-busy="true">Loading job {jobId}…</p>}
    {detailError && <div role="alert" className="att-inline-error">{detailError}<button type="button" onClick={retry}>Retry</button></div>}
    {cancelError && <p role="alert" className="att-inline-error">{cancelError}</p>}
    {cancelState === 'pending' && <p role="status" aria-live="polite">Cancellation request sent; waiting for the Server status update.</p>}

    <div className="att-job-tabs" role="tablist" aria-label={`Job ${jobId} sections`} onKeyDown={onTabKeyDown}>
      {tabs.map(tab => <button key={tab.id} type="button" role="tab" id={`job-tab-${tab.id}`} aria-controls={`job-panel-${tab.id}`} aria-selected={activeTab === tab.id} tabIndex={activeTab === tab.id ? 0 : -1} onClick={() => setActiveTab(tab.id)}>{tab.label}</button>)}
    </div>
    <JobOverview job={job} status={jobStatus} statusText={statusSnapshot.text} jobState={jobState} hidden={activeTab !== 'overview'} />
    <LiveOutputPanel api={api} jobId={jobId} job={displayedJob} hidden={activeTab !== 'live'} />
    <StructuredEventsPanel api={api} jobId={jobId} job={displayedJob} hidden={activeTab !== 'events'} />
    <section className="att-job-panel" role="tabpanel" id="job-panel-results" aria-labelledby="job-tab-results" hidden={activeTab !== 'results'} tabIndex={0}>
      <h2>Results</h2>
      {Boolean(result?.result?.metrics) && <section aria-label="Failure diagnosis"><h3>Failure diagnosis</h3>
        <p>Runtime errors (all phases): {displayValue((result!.result!.metrics as Record<string, unknown>).runtimeError ?? 0)}; measured runtime errors: {displayValue((result!.result!.metrics as Record<string, unknown>).measuredRuntimeError ?? 0)}.</p>
        <p>Thresholds and execution outcome are independent. Completed TPS includes unsuccessful completions; successfulThroughput counts PASS only.</p>
        <pre>{displayValue((result!.result!.metrics as Record<string, unknown>).failureDiagnostics ?? [])}</pre>
      </section>}
      {resultState === 'loading' && <p role="status" aria-busy="true">Loading result…</p>}
      {resultState === 'refreshing' && <p role="status" aria-busy="true">Refreshing result…</p>}
      {resultError && <p role="alert" className="att-inline-error">{resultError}</p>}
      {result && <><p>{result.result ? 'Server result returned.' : 'The Server has not returned a result payload.'}</p>
        {result.diagnostic && <details><summary>Server diagnostic</summary><pre>{displayValue(result.diagnostic)}</pre></details>}
        <EvidenceQuotaSummary payload={result} />
        {result.result && <details open><summary>Raw result payload</summary><pre className="att-raw-result">{displayValue(result.result)}</pre></details>}
      </>}
    </section>
    <ArtifactsPanel api={api} jobId={jobId} refresh={detailRefresh} onMetrics={setArchiveMetrics} hidden={activeTab !== 'artifacts'} />
    <MetricsPanel job={job} metrics={metrics} resultState={resultState} archiveMetrics={archiveMetrics} buckets={buckets} charts={charts} hidden={activeTab !== 'metrics'} />
  </div>;
}

const MetricTable = memo(function MetricTable({ title, rows, unit = '' }: { title: string; rows: Array<[string, number]>; unit?: string }) {
  return <section className="att-metric-section" aria-label={title}><h3>{title}</h3>
    {rows.length ? <div className="att-table-scroll"><table><caption>{title} returned by the ATT API</caption><thead><tr><th scope="col">Field</th><th scope="col">Returned value</th></tr></thead><tbody>
      {rows.map(([name, value]) => <tr key={name}><th scope="row">{name}</th><td>{METRIC_NUMBER_FORMATTER.format(value)}{unit && <span> {unit}</span>}</td></tr>)}
    </tbody></table></div> : <p role="status">No {title.toLowerCase()} were returned.</p>}
  </section>;
});

export const TimeSeriesChart = memo(function TimeSeriesChart({ series }: { series: MetricSeries }) {
  const width = 820;
  const height = 250;
  const left = 62;
  const right = 16;
  const top = 16;
  const bottom = 42;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const displayPoints = downsampleMetricPoints(series.points);
  const firstTime = displayPoints[0].epochMs;
  const lastTime = displayPoints[displayPoints.length - 1].epochMs;
  const maxValue = Math.max(1, ...displayPoints.map(point => point.value));
  const timeSpan = Math.max(1, lastTime - firstTime);
  const titleId = 'att-chart-' + series.field + '-title';
  const descId = 'att-chart-' + series.field + '-description';
  const coordinates = displayPoints.map(point => ({
    x: left + ((point.epochMs - firstTime) / timeSpan) * plotWidth,
    y: top + (1 - point.value / maxValue) * plotHeight,
  }));
  const path = coordinates.map((point, index) => (index ? 'L ' : 'M ') + point.x.toFixed(2) + ' ' + point.y.toFixed(2)).join(' ');
  const timezone = browserTimeZone();

  return <figure className="att-metric-chart" aria-labelledby={titleId}>
    <figcaption>
      <h3 id={titleId}>{series.label}</h3>
      <p>Y axis: {series.unit}. X axis: bucket start time in {timezone}. Each displayed point is an exact value returned by the Server; no points are interpolated.</p>
      {displayPoints.length < series.points.length && <p role="status">display sample: {displayPoints.length} of {series.points.length} points. The table retains all returned buckets.</p>}
    </figcaption>
    <svg className="att-metric-chart-svg" viewBox={'0 0 ' + width + ' ' + height} role="img" aria-label={series.label + ' by bucket start time'} aria-describedby={descId}>
      <title>{series.label} by bucket start time</title>
      <desc id={descId}>{displayPoints.length < series.points.length ? `display sample: ${displayPoints.length} of ${series.points.length} points; ` : ''}{series.points.length} ordered server samples from {formatBucketTime(firstTime)} to {formatBucketTime(lastTime)}. Displayed coordinates and values are returned server points.</desc>
      {[0, 0.25, 0.5, 0.75, 1].map(fraction => {
        const y = top + (1 - fraction) * plotHeight;
        return <g className="att-metric-chart-grid" key={fraction}>
          <line x1={left} x2={width - right} y1={y} y2={y} />
          <text x={left - 8} y={y + 4} textAnchor="end">{CHART_NUMBER_FORMATTER.format(maxValue * fraction)}</text>
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
});

export const BucketTable = memo(function BucketTable({ buckets }: { buckets: MetricBucket[] }) {
  const [page, setPage] = useState(0);
  const columns = useMemo(() => unique(buckets.flatMap(bucket => Object.keys(bucket.values))), [buckets]);
  const pageCount = Math.max(1, Math.ceil(buckets.length / BUCKET_PAGE_SIZE));
  const currentPage = Math.min(page, pageCount - 1);
  const start = currentPage * BUCKET_PAGE_SIZE;
  const pageRows = buckets.slice(start, start + BUCKET_PAGE_SIZE);
  const end = start + pageRows.length;
  useEffect(() => setPage(0), [buckets]);
  return <section className="att-metric-section" aria-label="Returned Load time-series buckets">
    <h3>Load time-series buckets</h3><p>Table alternative using the exact bucket fields returned by the Server; timestamps are shown in the local timezone, and no points are interpolated.</p>
    <p role="status">Showing rows {buckets.length ? start + 1 : 0}–{end} of {buckets.length}.</p>
    <div className="att-table-scroll"><table aria-rowcount={buckets.length + 1}><caption>{buckets.length} returned buckets; this view shows {BUCKET_PAGE_SIZE} rows per page.</caption><thead><tr><th scope="col">Bucket start ({browserTimeZone()})</th>{columns.map(key => <th scope="col" key={key}>{key}</th>)}</tr></thead>
      <tbody>{pageRows.map((bucket, index) => <tr key={bucket.key} aria-rowindex={start + index + 2}>
        <td>{bucket.epochMs === null ? bucket.key : <time dateTime={new Date(bucket.epochMs).toISOString()}>{formatBucketTime(bucket.epochMs)}</time>}</td>
        {columns.map(key => <td key={key}>{displayValue(bucket.values[key] ?? '')}</td>)}
      </tr>)}</tbody>
    </table></div>
    {pageCount > 1 && <nav className="att-pagination" aria-label="Load time-series bucket pages">
      <button type="button" aria-label="Previous bucket page" onClick={() => setPage(value => Math.max(0, value - 1))} disabled={currentPage === 0}>Previous</button>
      <span>Page {currentPage + 1} of {pageCount}</span>
      <button type="button" aria-label="Next bucket page" onClick={() => setPage(value => Math.min(pageCount - 1, value + 1))} disabled={currentPage >= pageCount - 1}>Next</button>
    </nav>}
  </section>;
});
