import { StatusBadge } from './StatusBadge';
import { ArchiveMetrics } from './ArchiveMetrics';
import type { ArtifactArchiveMetrics } from './types';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent, type PointerEvent } from 'react';
import './load.css';
import { JobArtifacts } from './JobArtifacts';
import type { JobEvent, JobResultResponse, JobStatus, JobSummary, JobView, JobsFeatureProps } from './types';

function executionType(job: JobSummary): string { return job.executionType || (job.command === 'debug' ? 'run' : job.command); }

const PAGE_SIZE = 25;
const MAX_EVENTS = 500;
const MAX_LINE_LENGTH = 4000;
const MAX_BUCKET_ROWS = 4096;
const CONSOLE_MIN_HEIGHT = 192;
const CONSOLE_DEFAULT_HEIGHT = 384;
const CONSOLE_MAX_HEIGHT = 960;
const TERMINAL_BACKGROUND = '#0d1117';
const TERMINAL_FOREGROUND = '#f5f7fa';
const EVENT_TYPES = ['status', 'progress', 'log', 'diagnostic', 'result'] as const;

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

const DELETE_OUTCOME_LABELS: Record<string, string> = {
  DELETED: 'Deleted',
  ALREADY_UNAVAILABLE: 'Already removed',
  DELETION_PENDING_RECOVERY: 'Pending recovery (will finish automatically)',
  PINNED_UNPIN_FIRST: 'Protected: unpin first',
  NOT_TERMINAL: 'Not terminal',
  JOB_BUSY: 'Busy, retry later',
  ARCHIVE_BUSY: 'Busy, retry later',
  UNAVAILABLE: 'Not found',
};

const PIN_OUTCOME_LABELS: Record<string, string> = {
  PINNED: 'Pinned',
  UNPINNED: 'Unpinned',
  NOT_ELIGIBLE: 'Not eligible for pinning',
  UNAVAILABLE: 'Not found',
};

function outcomeLabel(outcome: string, labels: Record<string, string>): string {
  if (labels[outcome]) return labels[outcome];
  const readable = outcome.replace(/[_-]+/g, ' ').toLocaleLowerCase().replace(/^./, value => value.toLocaleUpperCase());
  return readable ? `Unknown outcome: ${readable}` : 'Unknown outcome';
}

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

function eventJournalTruncated(event: JobEvent): boolean {
  const data = event.data;
  return Boolean(data && typeof data === 'object' && !Array.isArray(data) && (data as Record<string, unknown>).historyTruncated === true);
}

function consoleText(event: JobEvent): string {
  const data = event.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return '';
  const value = (data as Record<string, unknown>).console;
  if (typeof value !== 'string') return '';
  return value
    .replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001B\][^\u0007]*(?:\u0007|\u001B\\)/g, '');
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

function consoleMaxHeight(): number {
  if (typeof window === 'undefined') return CONSOLE_MAX_HEIGHT;
  return Math.max(CONSOLE_MIN_HEIGHT, Math.min(CONSOLE_MAX_HEIGHT, Math.floor(window.innerHeight * 0.7)));
}

function clampConsoleHeight(height: number): number {
  return Math.min(consoleMaxHeight(), Math.max(CONSOLE_MIN_HEIGHT, Math.round(height)));
}

function eventPreview(event: JobEvent): string {
  const data = event.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return eventText(event);
  const record = data as Record<string, unknown>;
  const parts: string[] = [];
  for (const key of ['status', 'phase', 'sequence', 'message', 'error', 'summary']) {
    const value = record[key];
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') parts.push(`${key}=${value}`);
  }
  const metrics = record.metrics;
  if (metrics && typeof metrics === 'object' && !Array.isArray(metrics)) {
    const compact = Object.entries(metrics as Record<string, unknown>)
      .filter((entry): entry is [string, string | number | boolean] => ['string', 'number', 'boolean'].includes(typeof entry[1]))
      .slice(0, 6)
      .map(([key, value]) => `${key}=${value}`)
      .join(', ');
    if (compact) parts.push(`metrics=${compact}`);
  }
  return parts.join(' · ');
}

function eventTimestamp(event: JobEvent): string {
  const data = event.data && typeof event.data === 'object' && !Array.isArray(event.data)
    ? event.data as Record<string, unknown> : {};
  const candidate = [data.timestamp, data.createdAt, data.time, data.at, event.receivedAt]
    .find(value => typeof value === 'string' && Number.isFinite(Date.parse(value)));
  return typeof candidate === 'string' ? candidate : '';
}

function chronologicalEvents<T extends JobEvent>(events: T[]): T[] {
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
  return props.jobId ? <JobDetail key={props.jobId} {...props} jobId={props.jobId} /> : <JobsList {...props} />;
}

type JobActionOutcome = { jobId: string; outcome: string };

function DeleteJobs({ jobs, onDelete, onDone, onCancel }: { jobs: JobSummary[]; onDelete: (jobIds: string[]) => Promise<JobActionOutcome[]>; onDone: (outcomes: JobActionOutcome[]) => void; onCancel: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const dialogRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const restoreFocusRef = useRef<Element | null>(null);
  const eligible = jobs.filter(job => isTerminalStatus(job.status) && !job.pinned);
  useEffect(() => {
    restoreFocusRef.current = document.activeElement instanceof Element ? document.activeElement : null;
    headingRef.current?.focus();
  }, []);
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onCancel(); return; }
      if (event.key !== 'Tab' || !dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])');
      if (!focusable.length) return;
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, []);
  useEffect(() => () => {
    const opener = restoreFocusRef.current;
    if (opener instanceof HTMLElement) opener.focus();
  }, []);
  async function remove() {
    setBusy(true); setError('');
    try { onDone(await onDelete(eligible.map(job => job.jobId))); }
    catch (reason) { setError(toError(reason, 'Deletion failed; refresh and retry.').message); setBusy(false); }
  }
  return <div ref={dialogRef}><section role="dialog" aria-modal="true" aria-label="Confirm irreversible Job deletion" className="att-job-panel">
    <h2 tabIndex={-1} ref={headingRef}>Delete {eligible.length} of {jobs.length} selected Jobs permanently?</h2>
    <p>This irreversible action removes each eligible Job record, results, events/logs, archive and stored artifacts. Package sources and schedules are preserved.</p>
    <ul>{jobs.map(job => <li key={job.jobId}><code>{job.jobId}</code> — {job.command}, {job.packageId}, {job.status}, {job.principal || 'Unknown principal'}{job.pinned ? ' — protected: unpin first' : !isTerminalStatus(job.status) ? ' — skipped: active Job' : ' — delete'}</li>)}</ul>
    {error && <p role="alert">{error}</p>}
    <button type="button" disabled={busy} onClick={onCancel}>Keep Jobs</button>
    <button type="button" disabled={busy || !eligible.length} onClick={() => void remove()}>{busy ? 'Deleting…' : `Permanently delete ${eligible.length} Jobs`}</button>
  </section></div>;
}

function PinReasonForm({ title, busy, onSubmit, onCancel }: { title: string; busy: boolean; onSubmit: (reason: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  return <form className="att-inline-form" onSubmit={event => { event.preventDefault(); onSubmit(reason.trim()); }}>
    <h3>{title}</h3>
    <label>Pin reason (optional)
      <input maxLength={200} value={reason} onChange={event => setReason(event.currentTarget.value)} placeholder="Why should this evidence be retained?" />
    </label>
    <button type="submit" disabled={busy}>Pin Job</button>
    <button type="button" disabled={busy} onClick={onCancel}>Cancel pin</button>
  </form>;
}

function PayloadDetails({ payload }: { payload: string }) {
  const [open, setOpen] = useState(false);
  return <details open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary onClick={event => { event.preventDefault(); setOpen(value => !value); }}>Events preview clipped — expand retained payload</summary>
    {open && <pre>{payload.slice(0, 65536)}</pre>}
    {open && payload.length > 65536 && <p>Retained payload display limited to 65,536 characters.</p>}
  </details>;
}

function EventPayload({ open, getPayload }: { open: boolean; getPayload: () => string }) {
  if (!open) return null;
  const payload = getPayload();
  return <>
    <pre className="att-event-payload">{payload.slice(0, MAX_LINE_LENGTH)}</pre>
    {payload.length > MAX_LINE_LENGTH && <PayloadDetails payload={payload} />}
  </>;
}

function JobsList({ api, principal = '', onOpenJob }: JobsFeatureProps) {
  const [rows, setRows] = useState<JobSummary[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [pinTarget, setPinTarget] = useState<JobSummary | null>(null);
  const [bulkPin, setBulkPin] = useState<null | 'pin' | 'unpin'>(null);
  const [bulkPinReason, setBulkPinReason] = useState('');
  const [actionStatus, setActionStatus] = useState('');
  const [actionBusy, setActionBusy] = useState(false);
  async function pinRow(job: JobSummary, reason = '') {
    if (!api.pinJob) return;
    setActionBusy(true);
    try { const updated = await api.pinJob(job.jobId, !job.pinned, reason); setRows(current => current.map(row => row.jobId === job.jobId ? updated : row)); }
    catch (reason) { setActionStatus(toError(reason, 'Pin change failed.').message); }
    finally { setActionBusy(false); }
  }
  async function pinSelected(pinned: boolean, reason: string) {
    if (!api.pinJobs) return;
    const targets = rows.filter(job => selected.includes(job.jobId));
    setActionBusy(true); setBulkPin(null);
    try {
      const outcomes = await api.pinJobs(targets.map(job => job.jobId), pinned, pinned && reason ? reason : undefined);
      setActionStatus(outcomes.slice(0, 100).map(item => `${item.jobId}: ${outcomeLabel(item.outcome, PIN_OUTCOME_LABELS)}`).join('; '));
      setRefreshVersion(value => value + 1);
    }
    catch (reason) { setActionStatus(toError(reason, 'Bulk pin change failed.').message); }
    finally { setActionBusy(false); }
  }
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
      api.listJobs({ principal: serverPrincipal.trim() || undefined, signal: controller.signal, ...(pinnedOnly ? {pinnedOnly: true} : {}) })
        .then(items => {
          if (controller.signal.aborted || current !== sequence.current) return;
          setRows(items.slice(0, 100));
          setSelected(current => current.filter(id => items.some(job => job.jobId === id)));
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
  }, [api, serverPrincipal, refreshVersion, pinnedOnly]);

  const filtered = useMemo(() => {
    const start = fromDate ? new Date(`${fromDate}T00:00:00`).getTime() : Number.NEGATIVE_INFINITY;
    const end = toDate ? new Date(`${toDate}T23:59:59.999`).getTime() : Number.POSITIVE_INFINITY;
    return rows.filter(job => {
      const created = dateKey(job.createdAt || '');
      return (!pinnedOnly || job.pinned) && (!status || job.status === status)
        && (!packageId || job.packageId === packageId)
        && (!command || executionType(job) === command)
        && created >= start && created <= end;
    }).sort((a, b) => {
      if (sort === 'id') return a.jobId.localeCompare(b.jobId);
      const difference = dateKey(a.createdAt || '') - dateKey(b.createdAt || '');
      return sort === 'oldest' ? difference : -difference;
    });
  }, [rows, status, packageId, command, fromDate, toDate, sort, pinnedOnly]);

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
    setPinnedOnly(false); setServerPrincipal(''); setStatus(''); setPackageId(''); setCommand('');
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
      <label><input type="checkbox" checked={pinnedOnly} onChange={event => {setPinnedOnly(event.currentTarget.checked);setPage(0);}} /> Pinned only</label>
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
    <p className="att-list-coverage" role="note">Filters and sorting apply to the API-loaded rows. The API returns at most 100 jobs; principal search and Pinned only are performed by the Server. No environment field is returned for this list.</p>
    <p className="att-list-count att-query-result-status" role="status" aria-live="polite" aria-busy={state === 'loading' || state === 'refreshing'}>
      {state === 'loading' ? 'Loading jobs…' : state === 'refreshing' ? `Refreshing ${rows.length} loaded jobs…` : `Showing ${start}–${end} of ${filtered.length} matching loaded jobs (page ${currentPage + 1} of ${pageCount}).`}
    </p>
    {(api.deleteJobs || api.pinJobs) && <div className="att-query-filter-actions"><span>{selected.length} selected across loaded pages</span>
      <button type="button" disabled={!selected.length} onClick={() => setSelected([])}>Clear selection</button>
      {api.pinJobs && <button type="button" disabled={!selected.length || actionBusy} onClick={() => setBulkPin('pin')}>Pin selected</button>}
      {api.pinJobs && <button type="button" disabled={!selected.length || actionBusy} onClick={() => void pinSelected(false, '')}>Unpin selected</button>}
      {api.deleteJobs && <button type="button" disabled={!selected.length || actionBusy} onClick={() => setConfirmDelete(true)}>Delete selected</button>}
    </div>}
    {actionStatus && <p role="status">{actionStatus}</p>}
    {pinTarget && <PinReasonForm title={`Pin ${pinTarget.jobId}`} busy={actionBusy} onSubmit={reason => { void pinRow(pinTarget, reason); setPinTarget(null); }} onCancel={() => setPinTarget(null)} />}
    {bulkPin === 'pin' && <form className="att-inline-form" onSubmit={event => { event.preventDefault(); void pinSelected(true, bulkPinReason.trim()); }}>
      <h3>Pin {rows.filter(job => selected.includes(job.jobId) && isTerminalStatus(job.status)).length} selected Jobs</h3>
      <label>Pin reason (optional)<input maxLength={200} value={bulkPinReason} onChange={event => setBulkPinReason(event.currentTarget.value)} /></label>
      <button type="submit" disabled={actionBusy}>Pin selected Jobs</button>
      <button type="button" disabled={actionBusy} onClick={() => setBulkPin(null)}>Cancel</button>
    </form>}
    {confirmDelete && api.deleteJobs && <DeleteJobs jobs={rows.filter(job => selected.includes(job.jobId))} onDelete={api.deleteJobs} onCancel={() => setConfirmDelete(false)} onDone={outcomes => {
      setConfirmDelete(false);setActionStatus(outcomes.map(item => `${item.jobId}: ${outcomeLabel(item.outcome, DELETE_OUTCOME_LABELS)}`).join('; '));
      const removed=outcomes.filter(item => item.outcome === 'DELETED' || item.outcome === 'ALREADY_UNAVAILABLE').map(item => item.jobId);
      setRows(current => current.filter(job => !removed.includes(job.jobId)));setSelected(current => current.filter(id => !removed.includes(id)));
      setRefreshVersion(value => value + 1);
    }} />}
    {error && <div className="att-inline-error" role="alert">{error}{rows.length > 0 && ' Showing the last successful result.'}</div>}
    {state === 'error' && rows.length === 0 ? <section className="att-empty-state att-query-empty-state"><h2>Jobs unavailable</h2><p>{error}</p><button type="button" onClick={() => setRefreshVersion(value => value + 1)}>Try again</button></section>
      : filtered.length === 0 && state === 'ready' ? <section className="att-empty-state att-query-empty-state"><h2>No matching jobs</h2><p>{rows.length ? 'No loaded jobs match these filters.' : 'The Server returned no jobs for this principal search.'}</p></section>
        : <div className="att-table-scroll"><table className="att-jobs-table att-data-table">
          <caption>Jobs returned by the Server; most recently created first by default</caption>
          <thead><tr>{(api.deleteJobs || api.pinJobs) && <th scope="col"><input type="checkbox" aria-label="Select all on this page" checked={pageRows.length > 0 && pageRows.every(job => selected.includes(job.jobId))} onChange={event => {const checked=event.currentTarget.checked;setSelected(current => checked ? [...new Set([...current, ...pageRows.map(job => job.jobId)])] : current.filter(id => !pageRows.some(job => job.jobId === id)));}} /></th>}<th scope="col">Job ID</th><th scope="col">Package</th><th scope="col">Type</th><th scope="col">Principal</th><th scope="col">Status</th><th scope="col">Created</th><th scope="col">Open</th></tr></thead>
          <tbody>{pageRows.map(job => <tr key={job.jobId}>
            {(api.deleteJobs || api.pinJobs) && <td><input type="checkbox" aria-label={`Select ${job.jobId}`} checked={selected.includes(job.jobId)} onChange={event => {const checked=event.currentTarget.checked;setSelected(current => checked ? [...current, job.jobId] : current.filter(id => id !== job.jobId));}} /></td>}
            <th scope="row"><button type="button" className="att-link-button" onClick={() => onOpenJob(job.jobId)}>{job.jobId}</button></th>
            <td>{job.packageId}</td><td>{executionType(job)}</td><td>{job.principal || 'Not reported'}</td>
            <td><StatusBadge status={job.status} /></td><td><time dateTime={job.createdAt || undefined}>{dateTime(job.createdAt)}</time></td>
            <td>{job.pinned && <span title={`Pinned${job.pinReason ? `: ${job.pinReason}` : ''}`}>📌 Pinned</span>}{api.pinJob && isTerminalStatus(job.status) && <button type="button" disabled={actionBusy} onClick={() => job.pinned ? void pinRow(job, '') : setPinTarget(job)}>{job.pinned ? "Unpin" : "Pin"}</button>}<button type="button" onClick={() => onOpenJob(job.jobId)}>Open details</button></td>
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
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [jobActionBusy, setJobActionBusy] = useState(false);
  const [jobActionStatus, setJobActionStatus] = useState('');
  const [pinReasonOpen, setPinReasonOpen] = useState(false);
  const [jobState, setJobState] = useState<LoadState>('loading');
  const [detailError, setDetailError] = useState('');
  const [result, setResult] = useState<JobResultResponse | null>(null);
  const [resultState, setResultState] = useState<LoadState>('loading');
  const [resultError, setResultError] = useState('');
  const [activeTab, setActiveTab] = useState<JobTab>('overview');
  const [connection, setConnection] = useState<'connecting' | 'connected' | 'reconnecting' | 'completed'>('connecting');
  const [events, setEvents] = useState<Array<JobEvent & { seq: number }>>([]);
  const [eventTypeFilter, setEventTypeFilter] = useState<string[]>([...EVENT_TYPES]);
  const [eventSearch, setEventSearch] = useState('');
  const [liveSearch, setLiveSearch] = useState('');
  const [consoleFloor, setConsoleFloor] = useState(0);
  const [liveAutoScroll, setLiveAutoScroll] = useState(true);
  const [liveScrollPaused, setLiveScrollPaused] = useState(false);
  const [consoleHeight, setConsoleHeight] = useState(CONSOLE_DEFAULT_HEIGHT);
  const [consoleMaximized, setConsoleMaximized] = useState(false);
  const [maximizeTop, setMaximizeTop] = useState(128);
  const [eventsAutoScroll, setEventsAutoScroll] = useState(true);
  const [eventsScrollPaused, setEventsScrollPaused] = useState(false);
  const [eventsExpanded, setEventsExpanded] = useState(true);
  const [eventOpenOverride, setEventOpenOverride] = useState<Record<string, boolean>>({});
  const [eventCopyStatus, setEventCopyStatus] = useState('');
  const eventPanelRef = useRef<HTMLElement>(null);
  const consoleRef = useRef<HTMLPreElement>(null);
  const consoleShellRef = useRef<HTMLDivElement>(null);
  const eventsListRef = useRef<HTMLOListElement>(null);
  const eventsProgrammaticScroll = useRef(false);
  const streamCompleted = useRef(false);
  const eventSeq = useRef(0);
  const [journalTruncated, setJournalTruncated] = useState(false);
  const [cancelState, setCancelState] = useState<'idle' | 'pending' | 'error'>('idle');
  const [cancelError, setCancelError] = useState('');
  const [detailRefresh, setDetailRefresh] = useState(0);
  const [terminalRefresh, setTerminalRefresh] = useState(0);
  const [archiveMetrics, setArchiveMetrics] = useState<ArtifactArchiveMetrics | null>(null);
  useEffect(() => setArchiveMetrics(null), [jobId]);
  const cancellationLock = useRef(false);
  const terminalStatus = useRef<JobStatus | null>(null);
  const terminalReconciled = useRef(false);
  const liveStatus = useMemo(() => [...events].reverse().find(event => event.type === 'status'), [events]);
  const eventPayloadCache = useRef(new Map<string, { data: unknown; value: string }>());

  useEffect(() => {
    const controller = new AbortController();
    setJobState(job ? 'refreshing' : 'loading');
    setResultState(result ? 'refreshing' : 'loading');
    setDetailError(''); setResultError('');
    api.getJob(jobId, controller.signal).then(value => {
      if (!controller.signal.aborted) {
        setJob(terminalStatus.current ? { ...value, status: terminalStatus.current } : value);
        setJobState('ready');
      }
    }).catch(error => {
      if (!controller.signal.aborted) { setDetailError(toError(error, 'Job detail could not be loaded.').message); setJobState('error'); }
    });
    api.getJobResult(jobId, controller.signal).then(value => {
      if (!controller.signal.aborted) { setResult(value); setResultState('ready'); }
    }).catch(error => {
      if (!controller.signal.aborted) { setResultError(toError(error, 'Job result is not available.').message); setResultState('error'); }
    });
    return () => controller.abort();
  }, [api, jobId, detailRefresh, terminalRefresh]);

  useEffect(() => {
    let disposed = false;
    setConnection('connecting');
    setEvents([]);
    setJournalTruncated(false);
    setConsoleFloor(0);
    streamCompleted.current = false;
    const unsubscribe = api.subscribeJob(jobId, {
      onOpen: () => { if (!disposed && !streamCompleted.current) setConnection('connected'); },
      onError: () => { if (!disposed && !streamCompleted.current) setConnection('reconnecting'); },
      onEvent: event => {
        if (disposed) return;
        if (eventJournalTruncated(event)) setJournalTruncated(true);
        if (event.type === 'result') { streamCompleted.current = true; setConnection('completed'); }
        if (event.type === 'status' || event.type === 'result') {
          const payload = event.data && typeof event.data === 'object' && !Array.isArray(event.data)
            ? (event.data as Record<string, unknown>).status
            : event.data;
          const terminal = typeof payload === 'string' && ['PASS', 'FAIL', 'ERROR', 'INVALID', 'CANCELLED'].includes(payload);
          if (terminal) terminalStatus.current = payload;
          if (typeof payload === 'string' && payload) setJob(currentJob => currentJob
            ? { ...currentJob, status: terminalStatus.current || payload } : currentJob);
          // Status and result can arrive separately or be replayed on reconnect.
          // Reconcile both REST snapshots once, outside the state updater.
          if (!terminalReconciled.current && (terminal || event.type === 'result')) {
            terminalReconciled.current = true;
            setTerminalRefresh(value => value + 1);
          }
        }
        setEvents(current => event.id && current.some(existing => existing.id === event.id) ? current
          : [...current, { ...event, seq: ++eventSeq.current, receivedAt: event.receivedAt || new Date().toISOString() }].slice(-MAX_EVENTS));
      },
    });
    return () => { disposed = true; unsubscribe(); };
  }, [api, jobId]);

  async function changeJobPin(pinned: boolean, reason = '') {
    setJobActionBusy(true); setPinReasonOpen(false);
    try { setJob(await api.pinJob!(jobId, pinned, reason)); }
    catch (error) { setJobActionStatus(toError(error, 'Pin change failed.').message); }
    finally { setJobActionBusy(false); }
  }

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
      && (!query || `${event.type} ${event.id || ''} ${JSON.stringify(event.data)}`.toLocaleLowerCase().includes(query)));
  }, [orderedEvents, eventTypeFilter, eventSearch]);
  const visibleConsole = useMemo(() => {
    const query = liveSearch.trim().toLocaleLowerCase();
    return orderedEvents.filter(event => event.seq > consoleFloor && consoleText(event))
      .map(event => consoleText(event))
      .filter(line => !query || line.toLocaleLowerCase().includes(query));
  }, [orderedEvents, consoleFloor, liveSearch]);
  const historyTruncated = journalTruncated || orderedEvents.some(eventJournalTruncated);
  const browserTruncated = events.length >= MAX_EVENTS;
  const buckets = metricBuckets(metrics?.buckets).slice(0, MAX_BUCKET_ROWS);
  const charts = metricSeries(buckets);
  function eventPayload(event: JobEvent & { seq: number }, key: string): string {
    const cached = eventPayloadCache.current.get(key);
    if (cached && cached.data === event.data) return cached.value;
    const value = displayValue(event.data);
    eventPayloadCache.current.set(key, { data: event.data, value });
    return value;
  }

  const latestVisibleEventId = visibleEvents.at(-1)?.id;
  useEffect(() => {
    if (activeTab !== 'live' || !liveAutoScroll || liveScrollPaused) return;
    const view = consoleRef.current;
    if (view) view.scrollTop = view.scrollHeight;
  }, [activeTab, liveAutoScroll, liveScrollPaused, visibleConsole.length, consoleMaximized, consoleHeight]);
  useEffect(() => {
    if (activeTab !== 'events' || !eventsAutoScroll || eventsScrollPaused) return;
    const list = eventsListRef.current;
    if (!list) return;
    eventsProgrammaticScroll.current = true;
    list.scrollTop = list.scrollHeight;
    eventsProgrammaticScroll.current = false;
  }, [activeTab, eventsAutoScroll, eventsScrollPaused, visibleEvents.length, latestVisibleEventId]);

  async function copyEvents() {
    const value = visibleConsole.join('\n');
    try {
      await navigator.clipboard.writeText(value);
      setEventCopyStatus(`${visibleConsole.length} lines copied.`);
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

  function toggleConsoleMaximize() {
    if (!consoleMaximized) {
      const top = consoleShellRef.current?.getBoundingClientRect().top ?? 128;
      const limit = Math.max(CONSOLE_MIN_HEIGHT, window.innerHeight - CONSOLE_MIN_HEIGHT - 16);
      setMaximizeTop(Math.max(8, Math.min(Math.round(top), limit)));
    }
    setConsoleMaximized(value => !value);
  }

  function onConsoleResizePointerDown(event: PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const origin = { y: event.clientY, height: consoleHeight };
    const target = event.currentTarget;
    try { target.setPointerCapture(event.pointerId); } catch { /* Pointer capture is unavailable in some test environments. */ }
    const move = (ev: globalThis.PointerEvent) => setConsoleHeight(clampConsoleHeight(origin.height + ev.clientY - origin.y));
    const stop = () => {
      target.removeEventListener('pointermove', move);
      target.removeEventListener('pointerup', stop);
      target.removeEventListener('pointercancel', stop);
    };
    target.addEventListener('pointermove', move);
    target.addEventListener('pointerup', stop);
    target.addEventListener('pointercancel', stop);
  }

  function onConsoleResizeKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 48 : 24;
    if (event.key === 'ArrowDown') setConsoleHeight(height => clampConsoleHeight(height + step));
    else if (event.key === 'ArrowUp') setConsoleHeight(height => clampConsoleHeight(height - step));
    else if (event.key === 'Home') setConsoleHeight(CONSOLE_MIN_HEIGHT);
    else if (event.key === 'End') setConsoleHeight(consoleMaxHeight());
    else return;
    event.preventDefault();
  }

  function onEventsScroll() {
    if (eventsProgrammaticScroll.current || !eventsAutoScroll) return;
    const list = eventsListRef.current;
    if (!list) return;
    const gap = list.scrollHeight - list.scrollTop - list.clientHeight;
    if (gap > 32) setEventsScrollPaused(true);
  }

  function setEventOpen(key: string, open: boolean, last: boolean) {
    setEventOpenOverride(current => ({ ...current, [key]: open }));
    if (!last) setEventsScrollPaused(true);
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
        <h1 id="job-title">Job {jobId}</h1><p>Server status, live output, results, artifacts and reported metrics.</p></div>
      <div className="att-job-actions">
        <StatusBadge status={jobStatus} />
        {job?.pinned && <span title={`Pinned by ${job.pinnedBy || 'operator'} at ${job.pinnedAt || 'unknown time'}${job.pinReason ? ` · ${job.pinReason}` : ''}`}>📌 Pinned</span>}
        {job && isTerminalStatus(job.status) && api.pinJob && <button type="button" disabled={jobActionBusy} onClick={() => job.pinned ? void changeJobPin(false) : setPinReasonOpen(true)}>{job.pinned ? 'Unpin' : 'Pin'}</button>}
        {job && isTerminalStatus(job.status) && (api.deleteJob || api.deleteJobs) && <button type="button" disabled={jobActionBusy || job.pinned} title={job.pinned ? 'Unpin before deletion' : undefined} onClick={() => setConfirmDelete(true)}>Delete Job</button>}
        {canCancel && <button className="att-button-danger" type="button" onClick={() => void cancel()} disabled={cancelState === 'pending'}>{cancelState === 'pending' ? 'Cancel requested…' : 'Cancel job'}</button>}
        <button type="button" onClick={retry} disabled={jobState === 'loading' || jobState === 'refreshing'}>{jobState === 'refreshing' ? 'Refreshing…' : 'Refresh details'}</button>
      </div>
    </header>
    {jobActionStatus && <p role="status">{jobActionStatus}</p>}
    {pinReasonOpen && job && <PinReasonForm title={`Pin ${job.jobId}`} busy={jobActionBusy} onSubmit={reason => void changeJobPin(true, reason)} onCancel={() => setPinReasonOpen(false)} />}
    {confirmDelete && job && (api.deleteJob || api.deleteJobs) && <DeleteJobs jobs={[job]} onDelete={jobIds => api.deleteJob
      ? api.deleteJob(jobIds[0]).then(outcome => [outcome])
      : api.deleteJobs!(jobIds)} onCancel={() => setConfirmDelete(false)} onDone={outcomes => {
        setConfirmDelete(false);
        if (outcomes.some(item => item.outcome === 'DELETED' || item.outcome === 'ALREADY_UNAVAILABLE')) onShowList();
        else setJobActionStatus(outcomes.map(item => `${item.jobId}: ${outcomeLabel(item.outcome, DELETE_OUTCOME_LABELS)}`).join('; '));
      }} />}
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
        {job.pinned && <div><dt>Pin reason</dt><dd>{job.pinReason || 'Not provided'}</dd></div>}
      </dl> : jobState !== 'loading' && <p>Job details are unavailable.</p>}
      {liveStatus && <p className="att-live-status" role="status">Latest live status event: {eventText(liveStatus)}</p>}
    </section>
    <section ref={eventPanelRef} className="att-job-panel" role="tabpanel" id="job-panel-live" aria-labelledby="job-tab-live" hidden={activeTab !== 'live'} tabIndex={0}>
      <div className="att-live-heading"><h2>Live Output</h2><span role="status" data-state={connection}>SSE {connection}</span></div>
      <p className="att-help">Live Output shows the Server console lines for this Job. It does not format events in the browser. EventSource reconnects from the last delivered event ID. [truncated] marks an entry shortened upstream; [truncated by journal] marks the journal byte limit. Event payload truncation is reported separately from dropped history.</p>
      <div className="att-event-controls" aria-label="Live output controls">
        <label>Search output<input aria-label="Search output" type="search" value={liveSearch} onChange={event => setLiveSearch(event.currentTarget.value)} placeholder="Search console lines" /></label>
        <label><input type="checkbox" aria-label="Live output auto-scroll" checked={liveAutoScroll} onChange={event => setLiveAutoScroll(event.currentTarget.checked)} /> Auto-scroll</label>
        <button type="button" onClick={() => setLiveScrollPaused(value => !value)} aria-pressed={liveScrollPaused}>{liveScrollPaused ? 'Resume auto-scroll' : 'Pause auto-scroll'}</button>
        <button type="button" onClick={() => void copyEvents()} disabled={!visibleConsole.length}>Copy Output</button>
        <button type="button" onClick={() => setConsoleFloor(events.reduce((floor, event) => Math.max(floor, event.seq), consoleFloor))}>Clear view</button>
        <button type="button" aria-pressed={consoleMaximized} onClick={toggleConsoleMaximize}>{consoleMaximized ? 'Restore' : 'Maximize'}</button>
      </div>
      {eventCopyStatus && <p role="status">{eventCopyStatus}</p>}
      {events.some(event => event.data && typeof event.data === 'object' && 'journalLimit' in event.data) && <p role="note">Some event payload fields were truncated by the journal byte budget. A preserved console preview can still be complete; [truncated by journal] marks a shortened console entry.</p>}
      {events.some(event => event.data && typeof event.data === 'object' && 'protocolLimit' in event.data) && <p role="note">Some event payload fields were truncated by the Worker protocol limit. Inspect artifacts for retained full details.</p>}
      {(historyTruncated || browserTruncated) && <p role="note">{historyTruncated && <span>Earlier journal events are no longer retained.</span>}{historyTruncated && browserTruncated && ' '}{browserTruncated && <span>This browser keeps the latest {MAX_EVENTS} events.</span>}</p>}
      {visibleConsole.length ? <div ref={consoleShellRef} className="att-console-shell" data-maximized={consoleMaximized ? 'true' : 'false'} style={consoleMaximized
        ? { position: 'fixed', top: maximizeTop, left: 12, right: 12, bottom: 12, height: 'auto', maxHeight: 'none', zIndex: 30 }
        : { height: consoleHeight }}>
        {consoleMaximized && <div className="att-console-maximized-bar"><button type="button" onClick={toggleConsoleMaximize}>Restore</button></div>}
        <pre ref={consoleRef} className="att-console" aria-label="Live output" data-appearance="terminal" style={{ background: TERMINAL_BACKGROUND, color: TERMINAL_FOREGROUND, whiteSpace: 'pre', overflow: 'auto' }}>{visibleConsole.join('\n')}</pre>
        {!consoleMaximized && <div role="separator" aria-orientation="horizontal" aria-label="Resize live output" aria-valuemin={CONSOLE_MIN_HEIGHT} aria-valuemax={consoleMaxHeight()} aria-valuenow={consoleHeight} tabIndex={0} className="att-console-resize" onPointerDown={onConsoleResizePointerDown} onKeyDown={onConsoleResizeKeyDown} />}
      </div>
        : <p role="status">{events.length ? (liveSearch.trim() ? 'No console lines match the current search.' : 'This Job has no console lines. Structured events remain on the Events tab.')
          : connection === 'completed' ? 'The Job has finished and no console lines were recorded.'
            : connection === 'connected' ? 'Connected; no console lines have been emitted yet.'
            : connection === 'reconnecting' ? 'Reconnecting to live output; no recent console lines are available.'
              : 'Connecting to live output…'}</p>}
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-events" aria-labelledby="job-tab-events" hidden={activeTab !== 'events'} tabIndex={0}>
      <h2>Events</h2><p role="status">SSE {connection}; {events.length} recent events retained in this view.</p>
      <div className="att-event-controls" aria-label="Event controls">
        <fieldset className="att-event-type-filters"><legend>Filter event type</legend>
          <label><input type="checkbox" aria-label="All Events" checked={eventTypeFilter.length === EVENT_TYPES.length}
            onChange={event => toggleAllEventTypes(event.currentTarget.checked)} /> All Events</label>
          {EVENT_TYPES.map(type => <label key={type}><input type="checkbox" aria-label={type}
            checked={eventTypeFilter.includes(type)} onChange={event => toggleEventType(type, event.currentTarget.checked)} /> {type}</label>)}
        </fieldset>
        <label>Search events<input aria-label="Search events" type="search" value={eventSearch} onChange={event => setEventSearch(event.currentTarget.value)} placeholder="Search event JSON" /></label>
        <label><input type="checkbox" aria-label="Events auto-scroll" checked={eventsAutoScroll} onChange={event => setEventsAutoScroll(event.currentTarget.checked)} /> Auto-scroll</label>
        <button type="button" aria-pressed={eventsScrollPaused} onClick={() => setEventsScrollPaused(value => !value)}>{eventsScrollPaused ? 'Resume auto-scroll' : 'Pause auto-scroll'}</button>
        <button type="button" onClick={() => { setEventsExpanded(true); setEventOpenOverride({}); }}>Expand all</button>
        <button type="button" onClick={() => { setEventsExpanded(false); setEventOpenOverride({}); setEventsScrollPaused(true); }}>Collapse all</button>
      </div>
      {visibleEvents.length ? <ol ref={eventsListRef} className="att-job-events" aria-label="Structured job events" onScroll={onEventsScroll}>{visibleEvents.map((event, index) => {
        const timestamp = eventTimestamp(event);
        const key = event.id || `${event.seq}-${event.type}`;
        const open = eventOpenOverride[key] ?? eventsExpanded;
        const preview = eventPreview(event);
        const last = index === visibleEvents.length - 1;
        return <li key={key}><details open={open}><summary onClick={click => { click.preventDefault(); setEventOpen(key, !open, last); }}><strong>{event.type}</strong>{event.id && <code>#{event.id}</code>}{timestamp && <time dateTime={timestamp}>{dateTime(timestamp)}</time>}{preview && <span className="att-event-preview">{preview}</span>}</summary><EventPayload open={open} getPayload={() => eventPayload(event, key)} /></details></li>;
      })}</ol> : <p>{events.length ? 'No events match the current filters.' : 'No SSE events have been returned.'}</p>}
    </section>
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
    <section className="att-job-panel" role="tabpanel" id="job-panel-artifacts" aria-labelledby="job-tab-artifacts" hidden={activeTab !== 'artifacts'} tabIndex={0}>
      <h2>Artifacts</h2>
      <JobArtifacts key={jobId} api={api} jobId={jobId} refresh={detailRefresh} onMetrics={setArchiveMetrics} />
    </section>
    <section className="att-job-panel" role="tabpanel" id="job-panel-metrics" aria-labelledby="job-tab-metrics" hidden={activeTab !== 'metrics'} tabIndex={0}>
      <h2>Metrics</h2>
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
      {!job?.performance?.timings && !job?.performance?.worker && !archiveMetrics && !job?.archiveMetrics && !metrics && resultState !== 'loading' && <p role="status">The API did not return metrics for this job.</p>}
    </section>
  </div>;
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
