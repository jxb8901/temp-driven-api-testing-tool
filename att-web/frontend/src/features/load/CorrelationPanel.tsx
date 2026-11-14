import { memo, useEffect, useMemo, useRef, useState } from 'react';
import type { LoadJobsApi, SutMetricItem, SutMetricPoint, SutMetricSeries, SutMetricsJobResponse } from './types';
import './CorrelationPanel.css';

const SERIES_TARGET = 'metrics/windowMetrics/timeSeries/points';
const MAX_DETAIL_SERIES_FILES = 9000;
const MAX_CHART_POINTS = 1200;
const NUMBER = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const UTC_TIME = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const UTC_TICK = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const UTC_DATE = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: '2-digit', month: 'short', year: 'numeric' });

type PhaseBoundary = { phase: string; windowStartEpochMs: number; windowEndEpochMs: number };
type Point = { x: number; y: number; durationMs?: number; visibleStart?: number; visibleEnd?: number; partialPhase?: boolean; partialRange?: boolean; cohortSamples?: number };
type LoadMetric = { id: string; field: string; label: string; unit: string; percentile?: boolean; rate?: boolean };

const LOAD_METRICS: LoadMetric[] = [
  { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s', rate: true },
  { id: 'successfulRate', field: 'successfulRate', label: 'Successful TPS', unit: 'requests/s', rate: true },
  { id: 'scheduledRate', field: 'scheduledRate', label: 'Scheduled rate', unit: 'requests/s', rate: true },
  { id: 'startedRate', field: 'startedRate', label: 'Started rate', unit: 'requests/s', rate: true },
  { id: 'sutFailure', field: 'sutFailure', label: 'SUT failures', unit: 'failures/bin' },
  { id: 'runtimeError', field: 'runtimeError', label: 'Runtime errors', unit: 'errors/bin' },
  { id: 'dropped', field: 'dropped', label: 'Generator drops', unit: 'drops/bin' },
  { id: 'latencyP95Ms', field: 'latencyP95Ms', label: 'Latency p95 (scheduled cohort)', unit: 'ms', percentile: true },
];

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function numeric(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function utcDateTime(value?: string | null): string {
  if (!value) return 'Not reported';
  const time = Date.parse(value);
  return Number.isFinite(time) ? `${UTC_TIME.format(time)} UTC` : value;
}

export function parseUtcInstant(value: string): number | null {
  const normalized = value.trim();
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?Z$/.exec(normalized);
  if (!match) return null;
  const epoch = Date.parse(normalized);
  if (!Number.isFinite(epoch)) return null;
  const parsed = new Date(epoch);
  const expectedMillis = Number((match[2] || '').padEnd(3, '0') || 0);
  return parsed.toISOString().slice(0, 19) === match[1] && parsed.getUTCMilliseconds() === expectedMillis ? epoch : null;
}

function timeOf(sample: SutMetricPoint): number {
  return Number.isFinite(sample.timestampEpochMs) ? sample.timestampEpochMs : Date.parse(sample.timestamp);
}

export function exclusiveObservedEnd(loadPoints: unknown[], sutSamples: SutMetricPoint[], sutResolutionMs: number | null, collectionWindowEndMs: number | null): number {
  const latestLoadEnd = loadPoints.reduce<number>((latest, value) => {
    const item = record(value);
    const start = numeric(item?.windowStartEpochMs);
    const duration = numeric(item?.durationMs);
    const end = start === null || duration === null || duration <= 0 ? Number.NEGATIVE_INFINITY : start + duration;
    return Number.isFinite(end) ? Math.max(latest, end) : latest;
  }, Number.NEGATIVE_INFINITY);
  const sutStep = Number.isFinite(sutResolutionMs) && sutResolutionMs! > 0 ? sutResolutionMs! : 1;
  const latestSutEnd = sutSamples.reduce<number>((latest, sample) => {
    const end = timeOf(sample) + sutStep;
    return Number.isFinite(end) ? Math.max(latest, end) : latest;
  }, Number.NEGATIVE_INFINITY);
  return Math.max(latestLoadEnd, latestSutEnd,
    Number.isFinite(collectionWindowEndMs) ? collectionWindowEndMs! : Number.NEGATIVE_INFINITY);
}

function labelsKey(series: SutMetricSeries, index: number): string {
  const labels = Object.entries(series.labels || {}).sort(([left], [right]) => left.localeCompare(right));
  return labels.length ? JSON.stringify(labels) : `series-${index}`;
}

function instanceLabel(series: SutMetricSeries, index: number): string {
  const labels = Object.entries(series.labels || {}).sort(([left], [right]) => left.localeCompare(right));
  return labels.length ? labels.map(([name, value]) => `${name}=${value}`).join(', ') : `Series ${index + 1} (no labels)`;
}

function metricSeriesPoints(metrics: Record<string, unknown> | null): unknown[] {
  const windowMetrics = record(metrics?.windowMetrics);
  const timeSeries = record(windowMetrics?.timeSeries);
  return Array.isArray(timeSeries?.points) ? timeSeries.points : [];
}

function phaseBoundaries(metrics: Record<string, unknown> | null): PhaseBoundary[] {
  const windowMetrics = record(metrics?.windowMetrics);
  const rawSeries = record(windowMetrics?.timeSeries);
  const directBoundaries = windowMetrics?.phaseBoundaries;
  const nestedBoundaries = rawSeries?.phaseBoundaries;
  const raw = Array.isArray(directBoundaries) ? directBoundaries
    : Array.isArray(nestedBoundaries) ? nestedBoundaries : [];
  return raw.flatMap(value => {
    const item = record(value);
    const start = numeric(item?.windowStartEpochMs);
    const end = numeric(item?.windowEndEpochMs);
    return item && typeof item.phase === 'string' && start !== null && end !== null && end >= start
      ? [{ phase: item.phase, windowStartEpochMs: start, windowEndEpochMs: end }] : [];
  }).sort((left, right) => left.windowStartEpochMs - right.windowStartEpochMs);
}

function phaseOverlap(start: number, end: number, phase: PhaseBoundary): number {
  return Math.max(0, Math.min(end, phase.windowEndEpochMs) - Math.max(start, phase.windowStartEpochMs));
}

export function selectLoadPoints(points: unknown[], metric: LoadMetric, phase: string, boundaries: PhaseBoundary[], start = Number.NEGATIVE_INFINITY, end = Number.POSITIVE_INFINITY): Point[] {
  const selectedBoundary = phase === 'all' ? null : boundaries.find(candidate => candidate.phase === phase) || null;
  if (phase !== 'all' && !selectedBoundary) return [];
  return points.flatMap(value => {
    const item = record(value);
    const x = numeric(item?.windowStartEpochMs);
    const durationMs = numeric(item?.durationMs);
    const y = numeric(item?.[metric.field]);
    if (!item || x === null || durationMs === null || durationMs <= 0 || y === null) return [];
    const binEnd = x + durationMs;
    const rangeOverlap = Math.max(0, Math.min(binEnd, end) - Math.max(x, start));
    if (rangeOverlap <= 0) return [];
    const phaseOverlapMs = selectedBoundary ? phaseOverlap(x, binEnd, selectedBoundary) : durationMs;
    if (phaseOverlapMs <= 0) return [];
    const visibleStart = Math.max(x, start, selectedBoundary?.windowStartEpochMs ?? Number.NEGATIVE_INFINITY);
    const visibleEnd = Math.min(binEnd, end, selectedBoundary?.windowEndEpochMs ?? Number.POSITIVE_INFINITY);
    if (visibleStart >= visibleEnd) return [];
    return [{ x, y, durationMs,
      visibleStart, visibleEnd,
      partialPhase: Boolean(selectedBoundary && phaseOverlapMs < durationMs),
      partialRange: rangeOverlap < durationMs,
      cohortSamples: numeric(item.latencySampleCount) ?? 0 }];
  }).sort((left, right) => left.x - right.x);
}

export function selectSutPoints(samples: SutMetricPoint[], phase: string, boundaries: PhaseBoundary[], start = Number.NEGATIVE_INFINITY, end = Number.POSITIVE_INFINITY): Point[] {
  const selectedPhase = phase === 'all' ? null : boundaries.find(candidate => candidate.phase === phase) || null;
  if (phase !== 'all' && !selectedPhase) return [];
  return samples.flatMap(sample => {
    const x = timeOf(sample);
    return Number.isFinite(x) && numeric(sample.value) !== null && x >= start && x < end
      && (!selectedPhase || (x >= selectedPhase.windowStartEpochMs && x < selectedPhase.windowEndEpochMs))
      ? [{ x, y: sample.value }] : [];
  }).sort((left, right) => left.x - right.x);
}

export function missingIntervals(points: Point[], resolutionMs: number): number {
  if (!Number.isFinite(resolutionMs) || resolutionMs <= 0) return 0;
  let missing = 0;
  for (let index = 1; index < points.length; index++) {
    const slots = Math.round((points[index]!.x - points[index - 1]!.x) / resolutionMs);
    if (slots > 1) missing += slots - 1;
  }
  return missing;
}

export function meanForLoadMetric(points: Point[], isPercentile: boolean): number | null {
  if (isPercentile || !points.length) return null;
  return points.reduce((sum, point) => sum + point.y, 0) / points.length;
}

export function meanForSutMetric(points: Point[], kind?: string): number | null {
  if (!['gauge', 'rate'].includes(kind || '') || !points.length) return null;
  return points.reduce((sum, point) => sum + point.y, 0) / points.length;
}

export function splitSeriesAtGaps(points: Point[], resolutionMs: number): Point[][] {
  if (!points.length) return [];
  if (!Number.isFinite(resolutionMs) || resolutionMs <= 0) return points.map(point => [point]);
  const segments: Point[][] = [[points[0]!]];
  points.slice(1).forEach(point => {
    const current = segments[segments.length - 1]!;
    if (resolutionMs > 0 && point.x - current[current.length - 1]!.x > resolutionMs * 1.5) segments.push([point]);
    else current.push(point);
  });
  return segments;
}

export function downsampleSegments(points: Point[], resolutionMs: number): { segments: Point[][]; total: number } {
  const sourceSegments = splitSeriesAtGaps(points, resolutionMs);
  if (points.length <= MAX_CHART_POINTS) return { segments: sourceSegments, total: points.length };
  const minimums = sourceSegments.map(segment => Math.min(segment.length, segment.length === 1 ? 1 : 2));
  const minimumTotal = minimums.reduce((sum, count) => sum + count, 0);
  if (minimumTotal > MAX_CHART_POINTS) {
    // Pathological fragmentation can make preserving every pair of endpoints exceed the display cap.
    const selected = sourceSegments.length <= MAX_CHART_POINTS
      ? sourceSegments
      : Array.from({ length: MAX_CHART_POINTS }, (_, index) => sourceSegments[Math.floor(index * (sourceSegments.length - 1) / (MAX_CHART_POINTS - 1))]!);
    const segments = selected.map(segment => segment.length === 1 ? segment : [segment[Math.floor((segment.length - 1) / 2)]!]);
    return { segments, total: segments.reduce((sum, segment) => sum + segment.length, 0) };
  }

  const budgets = [...minimums];
  const remaining = MAX_CHART_POINTS - minimumTotal;
  const capacities = sourceSegments.map((segment, index) => segment.length - budgets[index]!);
  const capacityTotal = capacities.reduce((sum, count) => sum + count, 0);
  if (remaining > 0 && capacityTotal > 0) {
    const shares = capacities.map(capacity => remaining * capacity / capacityTotal);
    let allocated = 0;
    shares.forEach((share, index) => {
      const whole = Math.min(capacities[index]!, Math.floor(share));
      budgets[index] += whole;
      allocated += whole;
    });
    const remainders = shares.map((share, index) => ({ index, fraction: share - Math.floor(share) }))
      .sort((left, right) => right.fraction - left.fraction);
    for (const { index } of remainders) {
      if (allocated >= remaining) break;
      if (budgets[index]! < sourceSegments[index]!.length) { budgets[index]++; allocated++; }
    }
  }

  const segments = sourceSegments.map((segment, index) => downsampleSegment(segment, budgets[index]!));
  return { segments, total: segments.reduce((sum, segment) => sum + segment.length, 0) };
}

function downsampleSegment(segment: Point[], budget: number): Point[] {
  if (segment.length <= budget) return segment;
  if (budget <= 1) return [segment[0]!];
  if (budget === 2) return [segment[0]!, segment.at(-1)!];

  const selected = new Set<number>([0, segment.length - 1]);
  if (budget === 3) {
    const first = segment[0]!.y;
    const last = segment.at(-1)!.y;
    let extreme = 1;
    for (let index = 2; index < segment.length - 1; index++) {
      const currentDistance = Math.abs(segment[index]!.y - (first + (last - first) * index / (segment.length - 1)));
      const extremeDistance = Math.abs(segment[extreme]!.y - (first + (last - first) * extreme / (segment.length - 1)));
      if (currentDistance > extremeDistance) extreme = index;
    }
    selected.add(extreme);
  } else {
    const bucketCount = Math.floor((budget - 2) / 2);
    const interiorCount = segment.length - 2;
    for (let bucket = 0; bucket < bucketCount; bucket++) {
      const start = 1 + Math.floor(bucket * interiorCount / bucketCount);
      const end = 1 + Math.floor((bucket + 1) * interiorCount / bucketCount);
      let minIndex = start;
      let maxIndex = start;
      for (let index = start + 1; index < end; index++) {
        if (segment[index]!.y < segment[minIndex]!.y) minIndex = index;
        if (segment[index]!.y > segment[maxIndex]!.y) maxIndex = index;
      }
      selected.add(minIndex);
      selected.add(maxIndex);
    }
  }
  return [...selected].sort((left, right) => left - right).map(index => segment[index]!);
}

function observedRange(points: Point[]) {
  if (!points.length) return null;
  const bounds = points.reduce((current, point) => ({ min: Math.min(current.min, point.y), max: Math.max(current.max, point.y) }),
    { min: Number.POSITIVE_INFINITY, max: Number.NEGATIVE_INFINITY });
  return { count: points.length, min: bounds.min, max: bounds.max };
}

function safeArtifactPath(root: string, value: unknown): string | null {
  if (typeof value !== 'string' || !value || value.startsWith('/') || value.includes('\\')) return null;
  const parts = value.split('/');
  if (parts.some(part => !part || part === '.' || part === '..')) return null;
  return root + value;
}

async function restoreWorkloadSeries(metrics: Record<string, unknown>, api: LoadJobsApi, jobId: string, root: string, workloadId: string,
                                      signal: AbortSignal, cache: Map<string, string>): Promise<Record<string, unknown>> {
  const reference = record(metrics.detailSeries);
  if (!reference) return metrics;
  if (reference.schemaVersion !== 'att-load-detail-reference/v1' || reference.complete !== true || !Array.isArray(reference.series))
    throw new Error('The archived workload metric-series reference is invalid.');
  if (!api.getJobArtifactText) throw new Error('Authorized workload metric-series artifacts are unavailable.');
  const records = reference.series.filter(value => {
    const item = record(value);
    return item?.target === SERIES_TARGET;
  });
  const fileCount = records.reduce((count, value) => count + (Array.isArray(record(value)?.files) ? (record(value)!.files as unknown[]).length : 0), 0);
  if (fileCount > MAX_DETAIL_SERIES_FILES) throw new Error('The archived workload metric-series file count exceeds the UI bound.');
  const restored = JSON.parse(JSON.stringify(metrics)) as Record<string, unknown>;
  for (const value of records) {
    const item = record(value);
    if (!item || item.scope !== 'workload' || item.workloadId !== workloadId || item.complete !== true
        || item.target !== SERIES_TARGET || item.kind !== 'list' || !Number.isSafeInteger(item.count)
        || !Array.isArray(item.files) || item.files.length === 0)
      throw new Error('The archived workload metric-series entry is incomplete.');
    const paths = item.files.map(path => safeArtifactPath(root, path));
    if (paths.some(path => !path)) throw new Error('The archived workload metric-series path is invalid.');
    const chunks: Array<{ part: number; parts: number; items: unknown[] }> = new Array(paths.length);
    let next = 0;
    await Promise.all(Array.from({ length: Math.min(8, paths.length) }, async () => {
      while (next < paths.length) {
        if (signal.aborted) throw new Error('Workload metric-series retrieval was cancelled.');
        const index = next++;
        const maybePath = paths[index];
        if (!maybePath) throw new Error('The archived workload metric-series path is invalid.');
        const path = maybePath;
        const key = `${jobId}:${path}`;
        let text = cache.get(key);
        if (text === undefined) {
          text = await api.getJobArtifactText!(jobId, path, signal);
          if (signal.aborted) throw new Error('Workload metric-series retrieval was cancelled.');
          if (text.length <= 1024 * 1024) {
            if (cache.size >= 32) cache.delete(cache.keys().next().value!);
            cache.set(key, text);
          }
        }
        const parsed = record(JSON.parse(text));
        const shared = item.sharedContent === true;
        if (!parsed || (shared
          ? parsed.schemaVersion !== 'att-load-detail-series/v2' || !/^[a-f0-9]{64}$/.test(String(parsed.contentSha256 || ''))
          : parsed.schemaVersion !== 'att-load-detail-series/v1' || parsed.scope !== 'workload' || parsed.workloadId !== workloadId || parsed.target !== SERIES_TARGET)
          || parsed.kind !== 'list' || !Number.isSafeInteger(parsed.part) || !Number.isSafeInteger(parsed.parts)
          || parsed.total !== item.count || !Array.isArray(parsed.items))
          throw new Error('An archived workload metric-series chunk does not match its reference.');
        chunks[index] = { part: parsed.part as number, parts: parsed.parts as number, items: parsed.items };
      }
    }));
    chunks.sort((left, right) => left.part - right.part);
    if (chunks.some((chunk, index) => chunk.part !== index + 1 || chunk.parts !== chunks.length))
      throw new Error('The archived workload metric-series chunks are incomplete.');
    const points = chunks.flatMap(chunk => chunk.items);
    if (points.length !== item.count) throw new Error('The archived workload metric-series item count is incomplete.');
    const tokens = String(item.target).split('/').map(token => token.replace(/~1/g, '/').replace(/~0/g, '~'));
    if (tokens.shift() !== 'metrics') throw new Error('The archived workload metric-series target is invalid.');
    let parent: unknown = restored;
    for (const token of tokens.slice(0, -1)) {
      if (!parent || typeof parent !== 'object') throw new Error('The archived workload metric-series target is unavailable.');
      parent = (parent as Record<string, unknown>)[token];
    }
    if (!parent || typeof parent !== 'object') throw new Error('The archived workload metric-series target is unavailable.');
    (parent as Record<string, unknown>)[tokens.at(-1)!] = points;
  }
  return restored;
}

export const CorrelationPanel = memo(function CorrelationPanel({ api, jobId, activeLoad, hidden, availability, archiveError, metrics, workloads,
  archiveRoot, loadTarget }: {
  api: LoadJobsApi; jobId: string; activeLoad: boolean; hidden: boolean;
  availability: string; archiveError: string; metrics: Record<string, unknown> | null; workloads: Record<string, unknown> | null;
  archiveRoot: string; loadTarget: string;
}) {
  const [workloadId, setWorkloadId] = useState('');
  const [workloadMetrics, setWorkloadMetrics] = useState<Record<string, unknown> | null>(null);
  const [loadedWorkloadId, setLoadedWorkloadId] = useState('');
  const [workloadLoading, setWorkloadLoading] = useState(false);
  const [workloadError, setWorkloadError] = useState('');
  const workloadCache = useRef(new Map<string, string>());
  const [collection, setCollection] = useState<SutMetricsJobResponse | null>(null);
  const [collectionError, setCollectionError] = useState('');
  const [collectionLoading, setCollectionLoading] = useState(false);
  const [queryResponse, setQueryResponse] = useState<SutMetricsJobResponse | null>(null);
  const [queryResponseId, setQueryResponseId] = useState('');
  const [queryError, setQueryError] = useState('');
  const [queryLoading, setQueryLoading] = useState(false);
  const [profileFilter, setProfileFilter] = useState('');
  const [targetFilter, setTargetFilter] = useState('');
  const [metricFilter, setMetricFilter] = useState('');
  const [queryFilter, setQueryFilter] = useState('');
  const [instanceFilter, setInstanceFilter] = useState('');
  const [loadMetricId, setLoadMetricId] = useState('completedRate');
  const [phaseFilter, setPhaseFilter] = useState('all');
  const [rangeFilter, setRangeFilter] = useState('all');
  const [customStartText, setCustomStartText] = useState('');
  const [customEndText, setCustomEndText] = useState('');

  const workloadEntries = useMemo(() => Object.entries(workloads || {}).filter(([, value]) => Boolean(record(value))), [workloads]);
  const selectedWorkload = record(workloads?.[workloadId]);
  const workloadTarget = record(selectedWorkload?.target);
  const loadWorkloadTarget = !workloadId ? 'Aggregate LOAD' : workloadTarget
    ? `${workloadTarget.type || 'target'}:${workloadTarget.id || 'Not reported'}`
    : selectedWorkload?.mix ? 'Workload target mix' : 'Not reported';
  useEffect(() => {
    if (!workloadId) { setWorkloadMetrics(null); setLoadedWorkloadId(''); setWorkloadError(''); setWorkloadLoading(false); return; }
    const rawMetrics = record(selectedWorkload?.metrics);
    if (!rawMetrics) { setWorkloadMetrics(null); setLoadedWorkloadId(''); setWorkloadError('This workload has no archived metrics summary.'); setWorkloadLoading(false); return; }
    const hasReference = Boolean(record(rawMetrics.detailSeries));
    if (!hasReference) { setWorkloadMetrics(rawMetrics); setLoadedWorkloadId(workloadId); setWorkloadError(''); setWorkloadLoading(false); return; }
    if (hidden) { setWorkloadLoading(false); return; }
    const controller = new AbortController();
    setWorkloadLoading(true); setWorkloadError(''); setWorkloadMetrics(null); setLoadedWorkloadId('');
    restoreWorkloadSeries(rawMetrics, api, jobId, archiveRoot, workloadId, controller.signal, workloadCache.current)
      .then(value => { if (!controller.signal.aborted) { setWorkloadMetrics(value); setLoadedWorkloadId(workloadId); } })
      .catch(error => { if (!controller.signal.aborted) setWorkloadError(error instanceof Error ? error.message : 'Workload series could not be loaded.'); })
      .finally(() => { if (!controller.signal.aborted) setWorkloadLoading(false); });
    return () => controller.abort();
  }, [api, jobId, workloadId, selectedWorkload, archiveRoot, hidden]);

  useEffect(() => {
    if (hidden || activeLoad) return;
    let disposed = false;
    let visible = !document.hidden;
    let timer: number | undefined;
    let request: AbortController | null = null;
    const schedule = (delay: number) => { if (!disposed && visible) timer = window.setTimeout(() => void poll(), delay); };
    const poll = async () => {
      if (disposed || !visible || request) return;
      if (!api.getJobSutMetrics) { setCollectionError('This Server client does not support archived SUT Metrics.'); setCollectionLoading(false); return; }
      const controller = new AbortController(); request = controller; setCollectionLoading(true); setCollectionError('');
      try {
        const value = await api.getJobSutMetrics(jobId, { offset: 0, limit: 1, signal: controller.signal });
        if (disposed || controller.signal.aborted) return;
        setCollection(value);
        const state = String(value.collection?.state || value.state || 'UNKNOWN');
        if (state === 'PENDING' || state === 'COLLECTING') schedule(5000);
      } catch (error) {
        if (!disposed && !controller.signal.aborted) {
          setCollectionError(error instanceof Error ? error.message : 'SUT collection metadata could not be loaded.');
          schedule(10_000);
        }
      } finally { if (request === controller) request = null; if (!disposed && !controller.signal.aborted) setCollectionLoading(false); }
    };
    const visibility = () => {
      visible = !document.hidden;
      if (!visible) { if (timer !== undefined) window.clearTimeout(timer); request?.abort(); request = null; }
      else void poll();
    };
    setCollection(null); setCollectionError(''); setCollectionLoading(true);
    document.addEventListener('visibilitychange', visibility);
    if (visible) void poll();
    return () => { disposed = true; if (timer !== undefined) window.clearTimeout(timer); request?.abort(); document.removeEventListener('visibilitychange', visibility); };
  }, [api, jobId, hidden, activeLoad]);

  const collectionState = String(collection?.collection?.state || collection?.state || (collectionLoading ? 'LOADING' : 'UNKNOWN'));
  const collectionRevision = collection?.collection?.updatedAt || collection?.collection?.createdAt || '';
  const queries = collection?.collection?.queries || [];
  const profileOptions = [...new Set(queries.map(query => query.profilePath || query.profile || '').filter(Boolean))].sort();
  const targetOptions = [...new Set(queries.filter(query => !profileFilter || (query.profilePath || query.profile) === profileFilter).map(query => query.targetId).filter(Boolean))].sort();
  const metricOptions = [...new Map<string, string>(queries.filter(query => (!profileFilter || (query.profilePath || query.profile) === profileFilter)
    && (!targetFilter || query.targetId === targetFilter)).map(query => [query.metricId, query.title || query.metricId] as const)).entries()];
  const filteredQueries = queries.filter(query => (!profileFilter || (query.profilePath || query.profile) === profileFilter)
    && (!targetFilter || query.targetId === targetFilter) && (!metricFilter || query.metricId === metricFilter));
  const selectedQuery = filteredQueries.find(query => query.id === queryFilter) || filteredQueries[0] || null;
  useEffect(() => { if (selectedQuery?.id !== queryFilter) setQueryFilter(selectedQuery?.id || ''); }, [selectedQuery?.id, queryFilter]);

  useEffect(() => {
    if (hidden || activeLoad || !selectedQuery) { setQueryResponse(null); setQueryResponseId(''); setQueryError(''); setQueryLoading(false); return; }
    if (!['READY', 'PARTIAL'].includes(collectionState)) { setQueryResponse(null); setQueryResponseId(''); setQueryError(''); setQueryLoading(false); return; }
    if (!api.getJobSutMetricSeries) { setQueryResponse(null); setQueryResponseId(''); setQueryError('This Server client does not support query-aware archived SUT series.'); setQueryLoading(false); return; }
    const controller = new AbortController();
    setQueryResponse(null); setQueryResponseId(''); setQueryError(''); setQueryLoading(true); setInstanceFilter('');
    api.getJobSutMetricSeries(jobId, { offset: 0, limit: 1, queryId: selectedQuery.id, targetId: selectedQuery.targetId,
      profilePath: selectedQuery.profilePath || selectedQuery.profile || undefined, signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { setQueryResponse(value); setQueryResponseId(selectedQuery.id); } })
      .catch(error => { if (!controller.signal.aborted) setQueryError(error instanceof Error ? error.message : 'The selected SUT series could not be loaded.'); })
      .finally(() => { if (!controller.signal.aborted) setQueryLoading(false); });
    return () => controller.abort();
  }, [api, jobId, hidden, activeLoad, collectionState, collectionRevision, selectedQuery?.id, selectedQuery?.targetId, selectedQuery?.profilePath, selectedQuery?.profile]);

  const selectedItem: SutMetricItem | null = useMemo(() => {
    if (queryResponseId !== selectedQuery?.id) return null;
    const items = queryResponse?.items || [];
    return items.find(item => item.queryId === selectedQuery?.id || item.id === selectedQuery?.id) || null;
  }, [queryResponse, queryResponseId, selectedQuery?.id]);
  const instances = (selectedItem?.series || []).map((series, index) => ({ series, index, key: labelsKey(series, index), label: instanceLabel(series, index) }));
  useEffect(() => { if (!instances.some(instance => instance.key === instanceFilter)) setInstanceFilter(instances[0]?.key || ''); }, [selectedItem, instanceFilter]);
  const selectedInstance = instances.find(instance => instance.key === instanceFilter) || null;
  const selectedCoverage = record(selectedInstance?.series.coverage) || record(selectedItem?.coverage) || {};
  const selectedObservation = record(selectedItem?.observation);
  const selectedAvailability = record(selectedObservation?.availability);
  const activeMetrics = workloadId ? loadedWorkloadId === workloadId ? workloadMetrics : null : metrics;
  const rawLoadPoints = metricSeriesPoints(activeMetrics);
  const boundaries = phaseBoundaries(activeMetrics);
  const timeSeries = record(record(activeMetrics?.windowMetrics)?.timeSeries);
  const loadResolution = numeric(timeSeries?.bucketDurationMs);
  const loadMetric = LOAD_METRICS.find(candidate => candidate.id === loadMetricId) || LOAD_METRICS[0]!;
  const sutSamples = selectedInstance?.series.samples || [];
  const sutResolution = metricStepMilliseconds(collection?.collection?.step);
  const latestCollectedSutTime = sutSamples.reduce<number>((latest, sample) => {
    const x = timeOf(sample);
    return Number.isFinite(x) ? Math.max(latest, x) : latest;
  }, Number.NEGATIVE_INFINITY);
  const collectionWindowEnd = collection?.collection?.windowEnd;
  const collectionWindowEndMs = collectionWindowEnd ? Date.parse(collectionWindowEnd) : Number.NaN;
  const loadObservedBounds = rawLoadPoints.reduce<{ first: number; last: number } | null>((bounds, value) => {
    const item = record(value);
    const start = numeric(item?.windowStartEpochMs);
    const duration = numeric(item?.durationMs);
    if (start === null || duration === null || duration <= 0) return bounds;
    return bounds
      ? { first: Math.min(bounds.first, start), last: Math.max(bounds.last, start + duration) }
      : { first: start, last: start + duration };
  }, null);
  const maxTime = exclusiveObservedEnd(rawLoadPoints, sutSamples, sutResolution, collectionWindowEndMs);
  const rangeSpan: Record<string, number> = { '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 };
  const customStart = parseUtcInstant(customStartText);
  const customEnd = parseUtcInstant(customEndText);
  const customRangeError = rangeFilter !== 'custom' ? ''
    : customStart === null || customEnd === null ? 'Enter both times as valid UTC timestamps, for example 2026-11-14T02:10:00Z.'
      : customStart >= customEnd ? 'The UTC start time must be earlier than the end time.' : '';
  const rangeStart = rangeFilter === 'custom' ? customStart ?? Number.POSITIVE_INFINITY
    : rangeFilter === 'all' || !Number.isFinite(maxTime) ? Number.NEGATIVE_INFINITY : maxTime - (rangeSpan[rangeFilter] || 0);
  const rangeEnd = rangeFilter === 'custom' ? customEnd ?? Number.NEGATIVE_INFINITY
    : rangeFilter === 'all' || !Number.isFinite(maxTime) ? Number.POSITIVE_INFINITY : maxTime;
  const loadPoints = customRangeError ? [] : selectLoadPoints(rawLoadPoints, loadMetric, phaseFilter, boundaries, rangeStart, rangeEnd);
  const sutPoints = customRangeError ? [] : selectSutPoints(sutSamples, phaseFilter, boundaries, rangeStart, rangeEnd);
  const latestSelectedSutTime = sutPoints.reduce((latest, point) => Math.max(latest, point.x), Number.NEGATIVE_INFINITY);
  const earliestSelectedSutTime = sutPoints.reduce((earliest, point) => Math.min(earliest, point.x), Number.POSITIVE_INFINITY);
  const chartDomain = Number.isFinite(rangeStart) && Number.isFinite(rangeEnd) && rangeStart < rangeEnd
    ? [rangeStart, rangeEnd] as [number, number] : undefined;
  const partialPhaseBins = loadPoints.filter(point => point.partialPhase).length;
  const partialRangeBins = loadPoints.filter(point => point.partialRange).length;
  const phaseOptions = [...new Set(boundaries.map(boundary => boundary.phase))];
  const collectionManifest = collection?.collection;
  const collectionWindowStart = collectionManifest?.windowStart;
  const collectionCoverage = collectionWindowStart && collectionWindowEnd
    ? `${utcDateTime(collectionWindowStart)} to ${utcDateTime(collectionWindowEnd)}` : 'Not reported';
  const sampleTailOffset = Number.isFinite(latestCollectedSutTime) && Number.isFinite(collectionWindowEndMs)
    ? collectionWindowEndMs - latestCollectedSutTime : null;
  const sampleTailDescription = sampleTailOffset === null ? 'Not reported'
    : `${NUMBER.format(Math.abs(sampleTailOffset))} ms ${sampleTailOffset >= 0 ? 'before' : 'after'} requested collection-window end`;
  const loadCoverage = typeof timeSeries?.coverage === 'string' ? timeSeries.coverage : 'Not reported';
  const loadCapacity = numeric(timeSeries?.capacity);
  const actualSutCoverage = Number.isFinite(earliestSelectedSutTime) && Number.isFinite(latestSelectedSutTime)
    ? `${utcDateTime(new Date(earliestSelectedSutTime).toISOString())} to ${utcDateTime(new Date(latestSelectedSutTime).toISOString())}` : 'Not reported';
  const sutMetricKind = selectedItem?.kind || selectedQuery?.kind || '';
  const sutFreshness = typeof selectedAvailability?.freshness === 'string' ? selectedAvailability.freshness : 'Not reported';

  return <section className="att-job-panel att-correlation-panel" role="tabpanel" id="job-panel-correlation" aria-labelledby="job-tab-correlation" hidden={hidden} tabIndex={0}>
    <h2>Correlation</h2>
    <p className="att-help">Compare one native ATT LOAD series with one selected SUT metric series on the same UTC time axis. This read-only view does not change either Metrics tab or the LOAD result.</p>
    {activeLoad && <p role="status">Correlation is available after LOAD finishes and its authorized report archive is ready.</p>}
    {!activeLoad && availability === 'loading' && <p role="status" aria-busy="true">Loading authorized LOAD phase series…</p>}
    {!activeLoad && ['PENDING', 'ARCHIVING'].includes(availability) && <p role="status">The correlation series will be available when the LOAD report archive is ready.</p>}
    {!activeLoad && ['FAILED', 'EXPIRED', 'OMITTED', 'UNAVAILABLE', 'ERROR'].includes(availability) && <p role={availability === 'ERROR' ? 'alert' : 'status'}>{archiveError || `The detailed LOAD report is ${availability.toLowerCase()}; correlation data is unavailable.`}</p>}
    {!activeLoad && availability === 'READY' && !metrics && <p role="status" aria-busy="true">Loading the authorized LOAD summary…</p>}
    {!activeLoad && availability === 'READY' && metrics && !timeSeries && <p role="status">This archived report does not include native phase-aligned LOAD window metrics. No timing origin or series is inferred.</p>}
    {!activeLoad && availability === 'READY' && timeSeries && <>
      <div className="att-correlation-filters" aria-label="Correlation filters">
        <label>LOAD workload<select value={workloadId} onChange={event => setWorkloadId(event.target.value)}>
          <option value="">Aggregate LOAD</option>{workloadEntries.map(([id]) => <option key={id} value={id}>{id}</option>)}
        </select></label>
        <label>LOAD metric<select value={loadMetricId} onChange={event => setLoadMetricId(event.target.value)}>
          {LOAD_METRICS.map(metric => <option key={metric.id} value={metric.id}>{metric.label}</option>)}
        </select></label>
        <label>Phase<select value={phaseFilter} onChange={event => setPhaseFilter(event.target.value)}>
          <option value="all">All phases</option>{phaseOptions.map(phase => <option key={phase} value={phase}>{phase}</option>)}
        </select></label>
        <label>Time range<select value={rangeFilter} onChange={event => setRangeFilter(event.target.value)}>
          <option value="all">All available overlap</option><option value="15m">Last 15 minutes</option><option value="1h">Last hour</option><option value="6h">Last 6 hours</option><option value="24h">Last 24 hours</option><option value="custom">Custom UTC range</option>
        </select></label>
        {rangeFilter === 'custom' && <>
          <label>UTC start<input type="text" autoComplete="off" placeholder="2026-11-14T02:10:00Z" value={customStartText} onChange={event => setCustomStartText(event.target.value)} /></label>
          <label>UTC end<input type="text" autoComplete="off" placeholder="2026-11-14T03:10:00Z" value={customEndText} onChange={event => setCustomEndText(event.target.value)} /></label>
        </>}
        <label>SUT profile<select value={profileFilter} onChange={event => { setProfileFilter(event.target.value); setTargetFilter(''); setMetricFilter(''); setQueryFilter(''); }}>
          <option value="">All profiles</option>{profileOptions.map(profile => <option key={profile} value={profile}>{profile}</option>)}
        </select></label>
        <label>SUT target<select value={targetFilter} onChange={event => { setTargetFilter(event.target.value); setMetricFilter(''); setQueryFilter(''); }}>
          <option value="">All SUT targets</option>{targetOptions.map(target => <option key={target} value={target}>{target}</option>)}
        </select></label>
        <label>SUT metric<select value={metricFilter} onChange={event => { setMetricFilter(event.target.value); setQueryFilter(''); }}>
          <option value="">All SUT metrics</option>{metricOptions.map(([id, title]) => <option key={id} value={id}>{title} ({id})</option>)}
        </select></label>
        <label>SUT query<select value={selectedQuery?.id || ''} onChange={event => setQueryFilter(event.target.value)}>
          {filteredQueries.map(query => <option key={query.id} value={query.id}>{query.title || query.metricId} · {query.sourceName || query.sourceId || 'source not reported'}</option>)}
          {!filteredQueries.length && <option value="">No matching query</option>}
        </select></label>
        <label>SUT instance<select value={instanceFilter} onChange={event => setInstanceFilter(event.target.value)} disabled={!instances.length}>
          {instances.map(instance => <option key={instance.key} value={instance.key}>{instance.label}</option>)}
          {!instances.length && <option value="">No series</option>}
        </select></label>
      </div>
      {workloadLoading && <p role="status" aria-busy="true">Loading the selected workload series from its authorized sidecar…</p>}
      {workloadError && <p role="alert">{workloadError}</p>}
      {customRangeError && <p role="alert">{customRangeError}</p>}
      {collectionError && <p role="alert">{collectionError}</p>}
      {queryError && <p role="alert">{queryError}</p>}
      {!queries.length && <p role="status" aria-busy={collectionLoading}>SUT collection metadata is {collectionState}; no query definitions are available yet.</p>}
      {collectionState === 'FAILED' && <p role="alert">SUT collection failed. Correlation does not change the LOAD verdict.</p>}
      {collectionState === 'DISABLED' && <p role="status">SUT Metrics are not configured for this LOAD Job.</p>}
      {collectionState === 'PARTIAL' && <p role="status">The SUT collection is partial; the selected query coverage and gaps remain visible below.</p>}
      {['UNAVAILABLE', 'NO_DATA'].includes(collectionState) && <p role="status">The SUT collection state is {collectionState}; no SUT values are synthesized.</p>}
      {collectionState === 'PENDING' || collectionState === 'COLLECTING'
        ? <p role="status" aria-busy="true">SUT post-run collection is {collectionState.toLowerCase()}; query metadata will refresh while this tab is visible.</p> : null}
      {selectedQuery && <>
        <dl className="att-correlation-provenance">
          <div><dt>LOAD job target</dt><dd>{loadTarget}</dd></div>
          <div><dt>LOAD workload</dt><dd>{workloadId || 'Aggregate LOAD'}</dd></div>
          {workloadId && <div><dt>LOAD workload target</dt><dd>{loadWorkloadTarget}</dd></div>}
          <div><dt>LOAD native window series</dt><dd>{loadResolution === null ? 'Native bin width not reported' : `${NUMBER.format(loadResolution)} ms native bin width`} · {rawLoadPoints.length} archived bins · capacity {loadCapacity === null ? 'not reported' : `${NUMBER.format(loadCapacity)} bins`} · {loadCoverage}</dd></div>
          <div><dt>LOAD observed bin coverage</dt><dd>{loadObservedBounds
            ? `${utcDateTime(new Date(loadObservedBounds.first).toISOString())} to ${utcDateTime(new Date(loadObservedBounds.last).toISOString())}`
            : 'Not reported'}</dd></div>
          <div><dt>Legacy Metrics ring</dt><dd>Separate 4,096 one-second buckets; not used as the native window-series resolution.</dd></div>
          <div><dt>SUT collection state</dt><dd>{collectionState}</dd></div>
          <div><dt>SUT source</dt><dd>{selectedItem?.sourceName || selectedQuery.sourceName || selectedQuery.sourceId || 'Not reported'}</dd></div>
          <div><dt>SUT target</dt><dd>{selectedItem?.targetId || selectedQuery.targetId}{(selectedItem?.targetType || selectedQuery.targetType) ? ` (${selectedItem?.targetType || selectedQuery.targetType})` : ''}</dd></div>
          <div><dt>Profile</dt><dd>{selectedItem?.profilePath || selectedItem?.profile || selectedQuery.profilePath || selectedQuery.profile || 'Not reported'}</dd></div>
          <div><dt>SUT metric and unit</dt><dd>{selectedItem?.title || selectedQuery.title || selectedQuery.metricId} · {selectedItem?.unit || selectedQuery.unit || 'unit not reported'}</dd></div>
          <div><dt>SUT metric kind</dt><dd>{sutMetricKind || 'Not reported'}</dd></div>
          <div><dt>SUT query freshness (entire collected series)</dt><dd>{sutFreshness}</dd></div>
          <div><dt>Collection resolution</dt><dd>{collection?.collection?.step || 'Not reported'}</dd></div>
          <div><dt>Collection manifest updated</dt><dd>{utcDateTime(collection?.collection?.updatedAt || collection?.collection?.createdAt)}</dd></div>
          <div><dt>Collection coverage</dt><dd>{collectionCoverage}</dd></div>
          <div><dt>Selected SUT instance</dt><dd>{selectedInstance?.label || 'Not reported'}</dd></div>
          <div><dt>Selected SUT series count</dt><dd>{instances.length}</dd></div>
          <div><dt>Selected SUT samples in range and phase</dt><dd>{sutPoints.length}</dd></div>
          <div><dt>Latest selected SUT sample</dt><dd>{Number.isFinite(latestSelectedSutTime) ? utcDateTime(new Date(latestSelectedSutTime).toISOString()) : 'Not reported'}</dd></div>
          <div><dt>Latest SUT sample (entire collected series)</dt><dd>{Number.isFinite(latestCollectedSutTime) ? utcDateTime(new Date(latestCollectedSutTime).toISOString()) : 'Not reported'}</dd></div>
          <div><dt>Entire collected series latest sample vs collection window end</dt><dd>{sampleTailDescription}</dd></div>
          <div><dt>Selected SUT observed sample coverage</dt><dd>{actualSutCoverage}</dd></div>
          <div><dt>Selected SUT series coverage</dt><dd>{selectedCoverage.firstSampleAt || selectedCoverage.lastSampleAt
            ? `${selectedCoverage.firstSampleAt || 'first sample not reported'} to ${selectedCoverage.lastSampleAt || 'last sample not reported'} (UTC); ${selectedCoverage.gapCount ?? 'gap count not reported'} reported gaps`
            : 'Not reported'}</dd></div>
          {selectedItem?.queryStatus && <div><dt>Query status</dt><dd>{selectedItem.queryStatus}</dd></div>}
        </dl>
        {queryLoading && <p role="status" aria-busy="true">Loading the selected SUT query series…</p>}
      </>}
      {selectedQuery && ['READY', 'PARTIAL'].includes(collectionState) && !queryLoading && !selectedItem && <p role="status">The selected query returned no SUT series for this collection.</p>}
      {phaseFilter !== 'all' && !phaseOptions.includes(phaseFilter) && <p role="status">The archived report has no actual phase boundary for {phaseFilter}.</p>}
      {workloadId && !workloadLoading && workloadMetrics && !metricSeriesPoints(workloadMetrics).length && <p role="status">The selected workload archive has no native phase-aligned window points.</p>}
      {loadPoints.length > 0 && sutPoints.length > 0 && selectedQuery && !queryLoading && !customRangeError && <>
        <CorrelationSummary metric={loadMetric} loadPoints={loadPoints} sutPoints={sutPoints} loadResolution={loadResolution} sutResolution={sutResolution}
          partialPhaseBins={partialPhaseBins} partialRangeBins={partialRangeBins} loadCoverage={loadCoverage} loadCapacity={loadCapacity} sutMetricKind={sutMetricKind} />
        <CorrelationChart loadPoints={loadPoints} sutPoints={sutPoints} loadMetric={loadMetric} sutUnit={selectedItem?.unit || selectedQuery.unit || 'unit not reported'}
          loadResolution={loadResolution} sutResolution={sutResolution} boundaries={boundaries} domain={chartDomain} />
      </>}
      {!customRangeError && !queryLoading && !loadPoints.length && <p role="status">No archived LOAD samples match the selected metric, phase, and time range.</p>}
      {!customRangeError && !queryLoading && selectedQuery && selectedInstance && !sutPoints.length && <p role="status">No SUT samples match the selected query, instance, phase, and time range.</p>}
      <p className="att-correlation-caveat">The chart preserves source timestamps and native resolutions. It does not interpolate or fill gaps with zero. Temporal overlap is descriptive only: it does not establish causation or attribute shared host CPU, JVM, database, or MQ metrics to a LOAD workload.</p>
      {boundaries.length > 0 && <details className="att-correlation-boundaries"><summary>Actual LOAD phase boundaries (UTC)</summary><ul>
        {boundaries.map(boundary => <li key={`${boundary.phase}-${boundary.windowStartEpochMs}`}><strong>{boundary.phase}</strong>: {new Date(boundary.windowStartEpochMs).toISOString()} – {new Date(boundary.windowEndEpochMs).toISOString()}</li>)}
      </ul></details>}
    </>}
  </section>;
});

function metricStepMilliseconds(step?: string): number | null {
  const match = /^([1-9][0-9]*)(ms|s|m|h|d|w|y)$/.exec(step || '');
  if (!match) return null;
  const multiplier: Record<string, number> = { ms: 1, s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000, w: 604_800_000, y: 31_536_000_000 };
  return Number(match[1]) * multiplier[match[2]!]!;
}

function CorrelationSummary({ metric, loadPoints, sutPoints, loadResolution, sutResolution, partialPhaseBins, partialRangeBins, loadCoverage, loadCapacity, sutMetricKind }: {
  metric: LoadMetric; loadPoints: Point[]; sutPoints: Point[]; loadResolution: number | null; sutResolution: number | null; partialPhaseBins: number; partialRangeBins: number;
  loadCoverage: string; loadCapacity: number | null; sutMetricKind?: string;
}) {
  const load = observedRange(loadPoints); const sut = observedRange(sutPoints);
  const loadMean = meanForLoadMetric(loadPoints, Boolean(metric.percentile));
  const sutMean = meanForSutMetric(sutPoints, sutMetricKind);
  const loadMissing = loadResolution === null ? null : missingIntervals(loadPoints, loadResolution);
  const sutMissing = sutResolution === null ? null : missingIntervals(sutPoints, sutResolution);
  const cohortSamples = loadPoints.reduce((sum, point) => sum + (point.cohortSamples || 0), 0);
  return <dl className="att-correlation-summary">
    <div><dt>LOAD series</dt><dd>{metric.label} · {metric.unit}</dd></div>
    <div><dt>LOAD native resolution and coverage</dt><dd>{loadResolution === null ? 'Not reported' : `${NUMBER.format(loadResolution)} ms`} · {loadPoints.length} selected observed bins · capacity {loadCapacity === null ? 'not reported' : `${NUMBER.format(loadCapacity)} bins`} · {loadCoverage} · {loadMissing === null ? 'missing intervals not estimated' : `${loadMissing} estimated interior missing intervals`}</dd></div>
    <div><dt>LOAD observed range</dt><dd>Min {NUMBER.format(load!.min)} · {metric.percentile ? `Max ${NUMBER.format(load!.max)} · ${load!.count} scheduled-cohort p95 bins · ${NUMBER.format(cohortSamples)} cohort samples` : `Mean ${NUMBER.format(loadMean!)} · Max ${NUMBER.format(load!.max)}`}</dd></div>
    <div><dt>SUT selected samples</dt><dd>{sutPoints.length} · {sutResolution === null ? 'effective step not reported' : `${NUMBER.format(sutResolution)} ms effective step`} · {sutMissing === null ? 'missing intervals not estimated' : `${sutMissing} estimated interior missing intervals`}</dd></div>
    <div><dt>SUT observed range</dt><dd>Min {NUMBER.format(sut!.min)} · {sutMean === null ? `mean not calculated for ${sutMetricKind || 'unreported metric kind'}` : `observed-sample arithmetic mean ${NUMBER.format(sutMean)}`} · Max {NUMBER.format(sut!.max)}</dd></div>
    <div><dt>Phase boundary bins</dt><dd>{partialPhaseBins} partially overlapping LOAD bins</dd></div>
    <div><dt>Time-range boundary bins</dt><dd>{partialRangeBins} partially overlapping LOAD bins{partialPhaseBins || partialRangeBins ? ' (reported values and rates retain the full native-bin denominator)' : ''}</dd></div>
    <div><dt>Mean method</dt><dd>{metric.percentile ? 'No mean is calculated across p95 bins.' : 'LOAD uses an arithmetic mean of observed points; missing intervals are excluded.'} {metric.rate ? 'Each LOAD rate uses its full native-bin denominator.' : ''} SUT uses the arithmetic mean of observed gauge or rate samples; counters and histograms are not averaged.</dd></div>
  </dl>;
}

export function CorrelationChart({ loadPoints, sutPoints, loadMetric, sutUnit, loadResolution, sutResolution, boundaries, domain }: {
  loadPoints: Point[]; sutPoints: Point[]; loadMetric: LoadMetric; sutUnit: string; loadResolution: number | null; sutResolution: number | null;
  boundaries: PhaseBoundary[]; domain?: [number, number];
}) {
  const width = 960; const height = 360; const left = 82; const right = 82; const top = 34; const bottom = 55;
  const plotWidth = width - left - right; const plotHeight = height - top - bottom;
  const minOf = (points: Point[]) => points.reduce((min, point) => Math.min(min, point.x), Number.POSITIVE_INFINITY);
  const maxOf = (points: Point[]) => points.reduce((max, point) => Math.max(max, point.x), Number.NEGATIVE_INFINITY);
  const loadStart = loadPoints.reduce((min, point) => Math.min(min, point.visibleStart ?? point.x), Number.POSITIVE_INFINITY);
  const loadEnd = loadPoints.reduce((max, point) => Math.max(max, point.visibleEnd ?? point.x + (point.durationMs || 0)), Number.NEGATIVE_INFINITY);
  let minX = domain?.[0] ?? Math.min(loadStart, minOf(sutPoints));
  let maxX = domain?.[1] ?? Math.max(loadEnd, maxOf(sutPoints));
  if (minX === maxX) { minX -= Math.max(loadResolution || 0, sutResolution || 0, 1000) / 2; maxX += Math.max(loadResolution || 0, sutResolution || 0, 1000) / 2; }
  let minLoad = loadPoints.reduce((min, point) => Math.min(min, point.y), Number.POSITIVE_INFINITY); let maxLoad = loadPoints.reduce((max, point) => Math.max(max, point.y), Number.NEGATIVE_INFINITY);
  let minSut = sutPoints.reduce((min, point) => Math.min(min, point.y), Number.POSITIVE_INFINITY); let maxSut = sutPoints.reduce((max, point) => Math.max(max, point.y), Number.NEGATIVE_INFINITY);
  if (minLoad === maxLoad) { minLoad -= Math.abs(minLoad) * .05 || 1; maxLoad += Math.abs(maxLoad) * .05 || 1; }
  if (minSut === maxSut) { minSut -= Math.abs(minSut) * .05 || 1; maxSut += Math.abs(maxSut) * .05 || 1; }
  const x = (value: number) => left + (value - minX) / (maxX - minX) * plotWidth;
  const yLoad = (value: number) => top + (maxLoad - value) / (maxLoad - minLoad) * plotHeight;
  const ySut = (value: number) => top + (maxSut - value) / (maxSut - minSut) * plotHeight;
  const loadDisplay = downsampleSegments(loadPoints, loadResolution || 0);
  const sutDisplay = downsampleSegments(sutPoints, sutResolution || 0);
  const visibleBoundaries = boundaries.filter(boundary => boundary.windowEndEpochMs >= minX && boundary.windowStartEpochMs <= maxX);
  return <figure className="att-correlation-chart">
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`Synchronized UTC time series. Left axis ${loadMetric.label} in ${loadMetric.unit}; right axis selected SUT metric in ${sutUnit}.`}>
      <defs><clipPath id="att-correlation-plot"><rect x={left} y={top} width={plotWidth} height={plotHeight} /></clipPath></defs>
      {visibleBoundaries.map((boundary, index) => <g key={`${boundary.phase}-${boundary.windowStartEpochMs}`}>
        <rect x={Math.max(left, x(boundary.windowStartEpochMs))} y={top} width={Math.max(0, Math.min(width - right, x(boundary.windowEndEpochMs)) - Math.max(left, x(boundary.windowStartEpochMs)))} height={plotHeight}
          className={`att-correlation-phase att-correlation-phase-${index % 4}`}><title>{boundary.phase}: {new Date(boundary.windowStartEpochMs).toISOString()} to {new Date(boundary.windowEndEpochMs).toISOString()}</title></rect>
        <text x={(Math.max(left, x(boundary.windowStartEpochMs)) + Math.min(width - right, x(boundary.windowEndEpochMs))) / 2} y={top + 13} textAnchor="middle">{boundary.phase}</text>
      </g>)}
      {Array.from({ length: 5 }, (_, index) => {
        const ratio = index / 4; const gy = top + ratio * plotHeight;
        const loadValue = maxLoad - ratio * (maxLoad - minLoad); const sutValue = maxSut - ratio * (maxSut - minSut);
        return <g key={index}><line x1={left} x2={width - right} y1={gy} y2={gy} className="att-correlation-grid" />
          <text x={left - 8} y={gy + 4} textAnchor="end">{NUMBER.format(loadValue)}</text><text x={width - right + 8} y={gy + 4}>{NUMBER.format(sutValue)}</text></g>;
      })}
      <g clipPath="url(#att-correlation-plot)">
        {loadDisplay.segments.map((segment, index) => {
          const visible = segment.flatMap(point => {
            const start = Math.max(point.visibleStart ?? point.x, minX);
            const end = Math.min(point.visibleEnd ?? point.x + (point.durationMs || 0), maxX);
            return end > start ? [{ point, start, end }] : [];
          });
          if (!visible.length) return null;
          const path = visible.map((entry, pointIndex) => pointIndex === 0
            ? `M${x(entry.start)},${yLoad(entry.point.y)}H${x(entry.end)}`
            : `L${x(entry.start)},${yLoad(visible[pointIndex - 1]!.point.y)}V${yLoad(entry.point.y)}H${x(entry.end)}`).join(' ');
          return <path key={`load-${index}`} d={path} className="att-correlation-load-line">
            <title>ATT LOAD native-bin value shown over its visible interval; reported value and rate denominator remain for the full bin.</title>
          </path>;
        })}
        {sutDisplay.segments.map((segment, index) => segment.length > 1
          ? <path key={`sut-${index}`} d={segment.map((point, pointIndex) => `${pointIndex ? 'L' : 'M'}${x(point.x)},${ySut(point.y)}`).join(' ')} className="att-correlation-sut-line" />
          : <circle key={`sut-${index}`} cx={x(segment[0]!.x)} cy={ySut(segment[0]!.y)} r="2.5" className="att-correlation-sut-point" />)}
      </g>
      {Array.from({ length: 5 }, (_, index) => minX + index / 4 * (maxX - minX)).map((tick, index, ticks) => {
        const day = new Date(tick).toISOString().slice(0, 10);
        const previousDay = index ? new Date(ticks[index - 1]!).toISOString().slice(0, 10) : null;
        const label = index === 0 || day !== previousDay ? `${UTC_DATE.format(tick)} ${UTC_TICK.format(tick)}` : UTC_TICK.format(tick);
        return <g key={index}><line x1={x(tick)} x2={x(tick)} y1={top + plotHeight} y2={top + plotHeight + 5} className="att-correlation-grid" />
          <text className="att-correlation-time-tick" x={x(tick)} y={height - 23} textAnchor="middle">{label}</text></g>;
      })}
      <text transform={`translate(18 ${top + plotHeight / 2}) rotate(-90)`} textAnchor="middle">{loadMetric.label} ({loadMetric.unit})</text>
      <text transform={`translate(${width - 18} ${top + plotHeight / 2}) rotate(90)`} textAnchor="middle">Selected SUT metric ({sutUnit})</text>
      <line x1={left} x2={width - right} y1={top + plotHeight} y2={top + plotHeight} className="att-correlation-axis" />
      <line x1={left} x2={left} y1={top} y2={top + plotHeight} className="att-correlation-axis" />
      <line x1={width - right} x2={width - right} y1={top} y2={top + plotHeight} className="att-correlation-axis" />
      <text x={width / 2} y={height - 4} textAnchor="middle">Sample time (UTC)</text>
      <line x1={left + 20} x2={left + 48} y1={19} y2={19} className="att-correlation-load-line" /><text x={left + 54} y={23}>ATT LOAD</text>
      <line x1={width - right - 145} x2={width - right - 117} y1={19} y2={19} className="att-correlation-sut-line" /><text x={width - right - 111} y={23}>SUT</text>
    </svg>
    <figcaption>ATT LOAD native-bin values span each visible part of their interval and retain full-bin denominators; SUT values use actual sample timestamps (UTC). Chart displays {loadDisplay.total}/{loadPoints.length} LOAD points and {sutDisplay.total}/{sutPoints.length} SUT points. SUT lines break at estimated missing intervals; values are not interpolated.</figcaption>
  </figure>;
}
