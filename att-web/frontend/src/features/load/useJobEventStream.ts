import { useMemo, useSyncExternalStore } from 'react';
import type { JobEvent, JobEventHandlers, LoadJobsApi } from './types';

export const MAX_EVENTS = 500;

export type JobStreamConnection = 'connecting' | 'connected' | 'reconnecting' | 'completed';
export type JobStreamEvent = JobEvent & { seq: number };

export interface JobEventStreamSnapshot {
  events: JobStreamEvent[];
  connection: JobStreamConnection;
  journalTruncated: boolean;
}

interface Entry extends JobEventStreamSnapshot {
  snapshot: JobEventStreamSnapshot;
  listeners: Set<() => void>;
  unsubscribe?: () => void;
  nextSequence: number;
  consumerCount: number;
}

const stores = new WeakMap<object, Map<string, Entry>>();

function eventJournalTruncated(event: JobEvent): boolean {
  const data = event.data;
  return Boolean(data && typeof data === 'object' && !Array.isArray(data)
    && (data as Record<string, unknown>).historyTruncated === true);
}

function notify(entry: Entry): void {
  entry.snapshot = { events: entry.events, connection: entry.connection, journalTruncated: entry.journalTruncated };
  for (const listener of entry.listeners) listener();
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
      events: [], connection: 'connecting', journalTruncated: false,
      listeners: new Set(), nextSequence: 0, consumerCount: 0,
      snapshot: { events: [], connection: 'connecting', journalTruncated: false },
    };
    byJob.set(jobId, entry);
  }
  return entry;
}

function start(api: LoadJobsApi, jobId: string, entry: Entry): void {
  if (entry.unsubscribe) return;
  entry.connection = 'connecting';
  const handlers: JobEventHandlers = {
    onOpen: () => {
      if (entry.connection !== 'completed') {
        entry.connection = 'connected';
        notify(entry);
      }
    },
    onError: () => {
      if (entry.connection !== 'completed') {
        entry.connection = 'reconnecting';
        notify(entry);
      }
    },
    onEvent: event => {
      if (eventJournalTruncated(event)) entry.journalTruncated = true;
      // A terminal status can precede the canonical result event. Keep the
      // transport state connected until result closes the EventSource, while
      // the Job Details status projection still updates immediately.
      const terminal = event.type === 'result';
      if (terminal) entry.connection = 'completed';
      if (!event.id || !entry.events.some(existing => existing.id === event.id)) {
        entry.events = [...entry.events, {
          ...event,
          seq: ++entry.nextSequence,
          receivedAt: event.receivedAt || new Date().toISOString(),
        }].slice(-MAX_EVENTS);
      }
      notify(entry);
    },
  };
  entry.unsubscribe = api.subscribeJob(jobId, handlers);
}

function release(api: LoadJobsApi, jobId: string, entry: Entry): void {
  entry.consumerCount = Math.max(0, entry.consumerCount - 1);
  if (entry.consumerCount !== 0) return;
  entry.unsubscribe?.();
  entry.unsubscribe = undefined;
  stores.get(api as object)?.delete(jobId);
}

/**
 * A bounded, per-browser-tab Job stream. Consumers of the same API object and
 * Job share one EventSource; the source is released after the last consumer.
 * EventSource itself retains Last-Event-ID for reconnect/replay.
 */
export function useJobEventStream(api: LoadJobsApi, jobId: string): JobEventStreamSnapshot {
  const entry = useMemo(() => entryFor(api, jobId), [api, jobId]);
  const subscribe = useMemo(() => (listener: () => void) => {
    entry.listeners.add(listener);
    entry.consumerCount += 1;
    start(api, jobId, entry);
    return () => {
      entry.listeners.delete(listener);
      release(api, jobId, entry);
    };
  }, [api, entry, jobId]);
  const getSnapshot = () => entry.snapshot;
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
