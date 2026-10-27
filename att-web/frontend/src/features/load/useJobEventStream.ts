import { useMemo, useSyncExternalStore } from 'react';
import type { JobEvent, JobEventHandlers, LoadJobsApi, LoadLiveProgressSnapshot, LoadCheckpointInterval } from './types';

export const MAX_EVENTS = 500;
export const MAX_CONSOLE_LINES = 2000;
const MAX_CONSOLE_LINE_LENGTH = 4000;
export const EVENT_FLUSH_INTERVAL_MS = 100;
export const COUNTER_FLUSH_INTERVAL_MS = 1000;
export const MAX_TRACKED_EVENT_IDS = 2000;

export type JobStreamConnection = 'connecting' | 'connected' | 'reconnecting' | 'completed';
export type JobStreamEvent = Readonly<JobEvent & { seq: number; consoleText: string }>;
export type JobConsoleLine = Readonly<{ seq: number; id?: string; receivedAt: string; text: string }>;

export interface JobEventStreamSnapshot {
  readonly latestLoadProgress?: LoadLiveProgressSnapshot;
  readonly checkpointHistory?: readonly LoadCheckpointInterval[];
  readonly events: readonly JobStreamEvent[];
  readonly consoleLines: readonly JobConsoleLine[];
  readonly consoleTruncated: boolean;
  readonly connection: JobStreamConnection;
  readonly journalTruncated: boolean;
  readonly duplicatesIgnored: number;
  readonly staleIgnored: number;
  readonly gaps: number;
}

export interface JobEventStatusSnapshot {
  readonly status?: string;
  readonly text?: string;
  readonly eventRevision: number;
  readonly resultEvent: boolean;
}

class EventRing {
  private readonly slots: Array<JobStreamEvent | undefined> = new Array(MAX_EVENTS);
  private start = 0;
  private size = 0;

  append(event: JobStreamEvent): void {
    if (this.size < MAX_EVENTS) {
      this.slots[(this.start + this.size) % MAX_EVENTS] = event;
      this.size += 1;
      return;
    }
    this.slots[this.start] = event;
    this.start = (this.start + 1) % MAX_EVENTS;
  }

  snapshot(): readonly JobStreamEvent[] {
    const events = Array.from({ length: this.size }, (_, index) => this.slots[(this.start + index) % MAX_EVENTS]!);
    return Object.freeze(events);
  }
}

class ConsoleRing {
  private readonly slots: Array<JobConsoleLine | undefined> = new Array(MAX_CONSOLE_LINES);
  private start = 0;
  private size = 0;
  private truncated = false;

  append(line: JobConsoleLine): void {
    if (this.size < MAX_CONSOLE_LINES) {
      this.slots[(this.start + this.size) % MAX_CONSOLE_LINES] = line;
      this.size += 1;
      return;
    }
    this.slots[this.start] = line;
    this.start = (this.start + 1) % MAX_CONSOLE_LINES;
    this.truncated = true;
  }

  snapshot(): readonly JobConsoleLine[] {
    return Object.freeze(Array.from({ length: this.size }, (_, index) => this.slots[(this.start + index) % MAX_CONSOLE_LINES]!));
  }

  wasTruncated(): boolean { return this.truncated; }
}

class BoundedIdIndex {
  private readonly ids = new Set<string>();
  private readonly order: Array<string | undefined> = new Array(MAX_TRACKED_EVENT_IDS);
  private start = 0;
  private size = 0;

  has(id: string): boolean { return this.ids.has(id); }

  add(id: string): void {
    if (this.ids.has(id)) return;
    if (this.size === MAX_TRACKED_EVENT_IDS) {
      const evicted = this.order[this.start];
      if (evicted !== undefined) this.ids.delete(evicted);
      this.order[this.start] = id;
      this.start = (this.start + 1) % MAX_TRACKED_EVENT_IDS;
    } else {
      this.order[(this.start + this.size) % MAX_TRACKED_EVENT_IDS] = id;
      this.size += 1;
    }
    this.ids.add(id);
  }

  get count(): number { return this.ids.size; }
}

interface Entry {
  latestLoadProgress?: LoadLiveProgressSnapshot;
  checkpointHistory: LoadCheckpointInterval[];
  buffer: EventRing;
  consoleBuffer: ConsoleRing;
  idIndex: BoundedIdIndex;
  connection: JobStreamConnection;
  journalTruncated: boolean;
  duplicatesIgnored: number;
  staleIgnored: number;
  gaps: number;
  status?: string;
  statusSnapshot: JobEventStatusSnapshot;
  snapshot: JobEventStreamSnapshot;
  listeners: Set<() => void>;
  unsubscribe?: () => void;
  nextSequence: number;
  lastNumericId?: bigint;
  consumerCount: number;
  dirty: boolean;
  pendingCounters: boolean;
  flushTimer?: ReturnType<typeof setTimeout>;
  counterFlushTimer?: ReturnType<typeof setTimeout>;
  visibilityListener?: () => void;
}

const stores = new WeakMap<object, Map<string, Entry>>();
const EMPTY_EVENTS: readonly JobStreamEvent[] = Object.freeze([]);
const EMPTY_SNAPSHOT: JobEventStreamSnapshot = Object.freeze({
  events: EMPTY_EVENTS, consoleLines: Object.freeze([]), consoleTruncated: false, connection: 'connecting', journalTruncated: false,
  duplicatesIgnored: 0, staleIgnored: 0, gaps: 0,
});
const EMPTY_STATUS_SNAPSHOT: JobEventStatusSnapshot = Object.freeze({ status: undefined, text: undefined, eventRevision: 0, resultEvent: false });
const TERMINAL_STATUSES = new Set(['PASS', 'FAIL', 'ERROR', 'INVALID', 'CANCELLED']);

function eventJournalTruncated(event: JobEvent): boolean {
  const data = event.data;
  return Boolean(data && typeof data === 'object' && !Array.isArray(data)
    && (data as Record<string, unknown>).historyTruncated === true);
}

function strippedConsoleText(event: JobEvent): string {
  const data = event.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return '';
  const value = (data as Record<string, unknown>).console;
  return typeof value === 'string' ? value
    .replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001B\][^\u0007]*(?:\u0007|\u001B\\)/g, '') : '';
}

function numericEventId(id: string | undefined): bigint | undefined {
  if (!id || !/^\d+$/.test(id)) return undefined;
  try { return BigInt(id); } catch { return undefined; }
}

function eventStatus(event: JobEvent): string | undefined {
  if (event.type !== 'status' && event.type !== 'result') return undefined;
  const data = event.data;
  const status = data && typeof data === 'object' && !Array.isArray(data)
    ? (data as Record<string, unknown>).status : data;
  return typeof status === 'string' ? status : undefined;
}

function eventStatusText(event: JobEvent): string | undefined {
  const data = event.data;
  if (typeof data === 'string') return data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined;
  const record = data as Record<string, unknown>;
  for (const key of ['message', 'summary', 'status', 'text']) {
    if (typeof record[key] === 'string') return record[key] as string;
  }
  return undefined;
}

function hasTerminalStatus(event: JobEvent): boolean {
  return event.type === 'result' || TERMINAL_STATUSES.has(eventStatus(event) || '');
}

function requiresImmediatePublish(event: JobEvent): boolean {
  return hasTerminalStatus(event) || eventStatus(event) === 'CANCEL_REQUESTED';
}

function isHidden(): boolean {
  return typeof document !== 'undefined' && document.hidden;
}

function clearFlushTimer(entry: Entry): void {
  if (entry.flushTimer === undefined) return;
  clearTimeout(entry.flushTimer);
  entry.flushTimer = undefined;
}

function clearCounterFlushTimer(entry: Entry): void {
  if (entry.counterFlushTimer === undefined) return;
  clearTimeout(entry.counterFlushTimer);
  entry.counterFlushTimer = undefined;
}

function publish(entry: Entry): void {
  if (!entry.dirty && !entry.pendingCounters) return;
  clearFlushTimer(entry);
  clearCounterFlushTimer(entry);
  entry.snapshot = Object.freeze({
    latestLoadProgress: entry.latestLoadProgress,
    checkpointHistory: Object.freeze([...entry.checkpointHistory]),
    events: entry.buffer.snapshot(),
    consoleLines: entry.consoleBuffer.snapshot(),
    consoleTruncated: entry.consoleBuffer.wasTruncated(),
    connection: entry.connection,
    journalTruncated: entry.journalTruncated,
    duplicatesIgnored: entry.duplicatesIgnored,
    staleIgnored: entry.staleIgnored,
    gaps: entry.gaps,
  });
  entry.dirty = false;
  entry.pendingCounters = false;
  for (const listener of entry.listeners) listener();
}

/** Ordinary events publish on this fixed cadence; there is deliberately no rAF mode. */
function scheduleFlush(entry: Entry): void {
  if (!entry.dirty || entry.consumerCount === 0 || entry.flushTimer !== undefined || isHidden()) return;
  entry.flushTimer = setTimeout(() => {
    entry.flushTimer = undefined;
    if (!isHidden()) publish(entry);
  }, EVENT_FLUSH_INTERVAL_MS);
}

/** Counter-only replay diagnostics use one slower coalesced publication. */
function scheduleCounterFlush(entry: Entry): void {
  if (!entry.pendingCounters || entry.consumerCount === 0 || entry.counterFlushTimer !== undefined || isHidden()) return;
  entry.counterFlushTimer = setTimeout(() => {
    entry.counterFlushTimer = undefined;
    if (!isHidden()) publish(entry);
  }, COUNTER_FLUSH_INTERVAL_MS);
}

function markChanged(entry: Entry, immediate: boolean): void {
  entry.dirty = true;
  if (immediate) publish(entry); else scheduleFlush(entry);
}

function markCountersChanged(entry: Entry): void {
  entry.pendingCounters = true;
  scheduleCounterFlush(entry);
}

function entryFor(api: LoadJobsApi, jobId: string): Entry {
  let byJob = stores.get(api as object);
  if (!byJob) {
    byJob = new Map();
    stores.set(api as object, byJob);
  }
  let entry = byJob.get(jobId);
  if (!entry) {
    entry = {
      buffer: new EventRing(), consoleBuffer: new ConsoleRing(), idIndex: new BoundedIdIndex(),
      connection: 'connecting', journalTruncated: false, checkpointHistory: [],
      duplicatesIgnored: 0, staleIgnored: 0, gaps: 0,
      statusSnapshot: EMPTY_STATUS_SNAPSHOT,
      listeners: new Set(), nextSequence: 0, consumerCount: 0, dirty: false, pendingCounters: false,
      snapshot: EMPTY_SNAPSHOT,
    };
    byJob.set(jobId, entry);
  }
  return entry;
}

/** Exposed for focused lifecycle and memory-bound unit tests. */
export function jobEventStreamStateForTesting(api: LoadJobsApi, jobId: string): {
  snapshot: JobEventStreamSnapshot;
  idIndexSize: number;
  timerPending: boolean;
} | undefined {
  const entry = stores.get(api as object)?.get(jobId);
  return entry && { snapshot: entry.snapshot, idIndexSize: entry.idIndex.count, timerPending: entry.flushTimer !== undefined || entry.counterFlushTimer !== undefined };
}

/** Exposed for the unit test that guards the read-only render path. */
export function hasJobEventStreamEntryForTesting(api: LoadJobsApi, jobId: string): boolean {
  return stores.get(api as object)?.has(jobId) ?? false;
}

function ingest(entry: Entry, event: JobEvent): boolean {
  if (eventJournalTruncated(event) && !entry.journalTruncated) {
    entry.journalTruncated = true;
    entry.dirty = true;
  }

  const id = event.id;
  const numericId = numericEventId(id);
  if (numericId !== undefined && entry.lastNumericId !== undefined && numericId <= entry.lastNumericId) {
    if (id && entry.idIndex.has(id)) entry.duplicatesIgnored += 1;
    else entry.staleIgnored += 1;
    markCountersChanged(entry);
    return false;
  }
  if (id && numericId === undefined && entry.idIndex.has(id)) {
    entry.duplicatesIgnored += 1;
    markCountersChanged(entry);
    return false;
  }

  // Server append order is authoritative, and the Server never sends an ID at
  // or below Last-Event-ID. Gaps are diagnostic; stale/replayed IDs are never
  // inserted or re-sorted by the browser.
  if (numericId !== undefined) {
    if (entry.lastNumericId !== undefined && numericId > entry.lastNumericId + 1n) entry.gaps += 1;
    entry.lastNumericId = numericId;
  }
  if (id) entry.idIndex.add(id);
  const stored = Object.freeze({
    ...event,
    seq: ++entry.nextSequence,
    receivedAt: event.receivedAt || new Date().toISOString(),
    consoleText: strippedConsoleText(event),
  });
  if (stored.consoleText) entry.consoleBuffer.append(Object.freeze({
    seq: stored.seq, id: stored.id, receivedAt: stored.receivedAt,
    text: stored.consoleText.length <= MAX_CONSOLE_LINE_LENGTH ? stored.consoleText : `${stored.consoleText.slice(0, MAX_CONSOLE_LINE_LENGTH)} [console line truncated]`,
  }));
  if (event.type === 'status' || event.type === 'result') {
    const status = eventStatus(event);
    if (status) entry.status = status;
    entry.statusSnapshot = Object.freeze({
      status: entry.status,
      text: eventStatusText(event),
      eventRevision: entry.statusSnapshot.eventRevision + 1,
      resultEvent: event.type === 'result',
    });
  }
  if (event.type === 'progress' && event.data && typeof event.data === 'object' && !Array.isArray(event.data)) {
    const candidate = (event.data as Record<string, unknown>).loadProgress as LoadLiveProgressSnapshot | undefined;
    if (candidate && candidate.schemaVersion === 'att-load-live-progress/v1' && Number.isFinite(candidate.elapsedMs)
        && (!entry.latestLoadProgress || candidate.elapsedMs >= entry.latestLoadProgress.elapsedMs)) {
      entry.latestLoadProgress = candidate;
      const interval = candidate.checkpointInterval;
      if (interval?.state === 'VERIFIED' && typeof interval.endSequence === 'number' &&
          !entry.checkpointHistory.some(item => item.endSequence === interval.endSequence)) {
        entry.checkpointHistory.push(interval);
        if (entry.checkpointHistory.length > 64) entry.checkpointHistory.shift();
      }
    }
  }
  entry.buffer.append(stored);
  entry.dirty = true;
  return true;
}

function start(api: LoadJobsApi, jobId: string, entry: Entry): void {
  if (entry.unsubscribe) return;
  entry.connection = 'connecting';
  if (typeof document !== 'undefined') {
    entry.visibilityListener = () => {
      if (document.hidden) {
        clearFlushTimer(entry);
        clearCounterFlushTimer(entry);
      }
      else publish(entry);
    };
    document.addEventListener('visibilitychange', entry.visibilityListener);
  }
  const handlers: JobEventHandlers = {
    onOpen: () => {
      if (entry.connection !== 'completed' && entry.connection !== 'connected') {
        entry.connection = 'connected';
        markChanged(entry, !isHidden());
      }
    },
    onError: () => {
      if (entry.connection !== 'completed' && entry.connection !== 'reconnecting') {
        entry.connection = 'reconnecting';
        markChanged(entry, true);
      }
    },
    onEvent: event => {
      const immediate = requiresImmediatePublish(event);
      const accepted = ingest(entry, event);
      if (event.type === 'result' && entry.connection !== 'completed') {
        entry.connection = 'completed';
        entry.dirty = true;
      }
      if (accepted || entry.dirty) markChanged(entry, immediate);
    },
  };
  entry.unsubscribe = api.subscribeJob(jobId, handlers);
}

function release(api: LoadJobsApi, jobId: string, entry: Entry): void {
  entry.consumerCount = Math.max(0, entry.consumerCount - 1);
  if (entry.consumerCount !== 0) return;
  clearFlushTimer(entry);
  clearCounterFlushTimer(entry);
  if (entry.visibilityListener && typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', entry.visibilityListener);
    entry.visibilityListener = undefined;
  }
  entry.unsubscribe?.();
  entry.unsubscribe = undefined;
  const byJob = stores.get(api as object);
  if (byJob?.get(jobId) === entry) byJob.delete(jobId);
}

/**
 * A bounded, per-browser-tab Job stream. Consumers of the same API object and
 * Job share one EventSource; the source is released after the last consumer.
 * EventSource itself retains Last-Event-ID for reconnect/replay. Ingest is
 * immediate, while ordinary immutable presentation snapshots publish every
 * 100 ms and counter-only snapshots publish every 1,000 ms. Publications pause
 * while the document is hidden. Terminal/error state bypasses that delay.
 * Numeric Event IDs follow the monotonic Server journal cursor; gaps are
 * allowed and stale replay never enters the append-ordered buffer.
 */
export function useJobEventStream(api: LoadJobsApi, jobId: string): JobEventStreamSnapshot {
  const subscribe = useMemo(() => (listener: () => void) => {
    const entry = entryFor(api, jobId);
    entry.listeners.add(listener);
    entry.consumerCount += 1;
    start(api, jobId, entry);
    return () => {
      entry.listeners.delete(listener);
      release(api, jobId, entry);
    };
  }, [api, jobId]);
  const getSnapshot = useMemo(() => () => stores.get(api as object)?.get(jobId)?.snapshot ?? EMPTY_SNAPSHOT, [api, jobId]);
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/**
 * A narrow subscription for status reconciliation. The returned snapshot only
 * changes for status/result events, so ordinary log/progress bursts do not
 * rerender Job Overview or its parent.
 */
export function useJobEventStatus(api: LoadJobsApi, jobId: string, fallbackStatus?: string): JobEventStatusSnapshot {
  const fallback = useMemo<JobEventStatusSnapshot>(() => Object.freeze({
    status: fallbackStatus,
    text: undefined,
    eventRevision: 0,
    resultEvent: false,
  }), [fallbackStatus]);
  const subscribe = useMemo(() => (listener: () => void) => {
    const entry = entryFor(api, jobId);
    entry.listeners.add(listener);
    entry.consumerCount += 1;
    start(api, jobId, entry);
    return () => {
      entry.listeners.delete(listener);
      release(api, jobId, entry);
    };
  }, [api, jobId]);
  const getSnapshot = useMemo(() => () => stores.get(api as object)?.get(jobId)?.statusSnapshot ?? fallback, [api, jobId, fallback]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return snapshot.status === undefined && fallbackStatus !== undefined && snapshot.eventRevision === 0
    ? fallback : snapshot;
}
