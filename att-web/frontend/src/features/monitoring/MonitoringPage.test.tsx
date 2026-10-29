// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { api } from '../../api';
import type { MonitoringSnapshot } from '../../types';
import { MonitoringPage } from './MonitoringPage';

function snapshot(capturedAt: string, heapUsedBytes: number): MonitoringSnapshot {
  return {
    schemaVersion: 'att-monitoring/v1', capturedAt, origin: 'server-runtime', requestId: 'request-1',
    health: { status: 'UP', version: '4.2.7', uptimeMs: 120_000 },
    process: { heapUsedBytes, heapCommittedBytes: 4_194_304, heapMaxBytes: 8_388_608,
      nonHeapUsedBytes: 2_097_152, nonHeapCommittedBytes: 4_194_304, threadCount: 20, daemonThreadCount: 15, peakThreadCount: 24,
      gcCount: null, gcTimeMs: null, availableProcessors: 8, processCpuLoad: null, processCpuTimeMs: null, processRssBytes: null },
    workers: { active: 1, maxConcurrent: 8, queued: 0, queueCapacity: 10, admissionCapacity: 18, completedSinceStart: 4,
      admissionSlotsUsed: 1, loadSlotsUsed: 1, activeLoadJobs: 1, maxConcurrentLoad: 2, loadCapacityRejections: 0,
      queueCapacityRejections: 0, workerAbnormalExits: null, workerTimeouts: null, activeSseConnections: 1, sseCapacity: 64,
      sseErrors: null, sseReconnects: null },
    http: { requests: 20, successfulRequests: 18, redirectRequests: 0, clientErrors: 1, serverErrors: 0, recentP50Ms: null, recentP95Ms: null },
    journal: { activeJobs: 1, degradedActiveJobs: 0, activeJournalBytes: 4096, activeRetainedEvents: 12,
      activeRetainedMemoryBytes: 2048, activeMemoryOverBudgetBytes: 0, activeCompactionAttempts: 2, activeCompactionFailures: 0,
      activeListenerDispatchFailures: 0, appendBytesWritten: 4096, compactionBytesWritten: 0, writeAmplification: 1,
      oldestActiveCompactionAgeMs: null, maxJournalBytesPerJob: 65536, memoryBudgetBytesPerJob: 8192,
      storageUsableBytes: null, storageHeadroomLow: false, cachedTerminalJobs: 2, cachedTerminalWeightBytes: 1024 },
    storage: { state: 'unknown', fileStoreUsableBytes: null, fileStoreTotalBytes: null, jobQuotaEnabled: false,
      jobQuotaBytes: null, jobStorageBytes: null, jobStorageMeasurementComplete: null, eligibleJobs: null,
      protectedJobs: null, deletedJobs: null, reclaimedBytes: null, quotaUnmet: null, cleanupTargetBytes: null, lastSweepAt: null,
      maxSingleJobBytes: null, archiveWritersActive: 0, archiveQueueDepth: 0, archiveQueueCapacity: 18, pendingArchiveJobs: 0,
      archiveFailuresSinceStart: 0, archiveRetriesSinceStart: 0, artifactBytes: null },
    inspection: { enabled: false, workerCount: 0, busyWorkers: 0, liveProcesses: 0, requests: null, failures: null,
      totalDurationMs: null, workerStarts: null, indexCacheHits: null, indexCacheMisses: null, configCacheHits: null,
      configCacheMisses: null, inspectorCacheHits: null, inspectorCacheMisses: null, serverStages: {}, workers: [] },
    executionPool: { enabled: false, owned: null, ready: null, maxIdle: null, starts: null, leases: null,
      coldFallbacks: null, startupFailures: null, recoveryUncertain: null, idleHeapCapBytes: null,
      idleCpuMetricsAvailable: null, idleCpuMs: null },
    scheduledLoad: { enabled: false, scheduleCount: null, activeLoads: null, maxConcurrentRuns: null, nextDueAt: null, occurrences: null },
    jobs: { QUEUED: 0, PREPARING: 0, RUNNING: 1, PASS: 3, FAIL: 0, ERROR: 0, INVALID: 0, CANCELLED: 0 },
  };
}

let host: HTMLDivElement;
let root: Root;
const context = { uiLocale: 'en' } as unknown as AppShellContextValue;

beforeEach(() => {
  vi.useFakeTimers();
  vi.restoreAllMocks();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove(); vi.restoreAllMocks(); vi.useRealTimers();
  Object.defineProperty(document, 'hidden', { configurable: true, value: false });
});

describe('MonitoringPage', () => {
  it('defaults to 15 seconds, renders unavailable readings honestly, and draws bounded session history', async () => {
    const baseRows = [
      snapshot('2026-10-29T12:00:00Z', 1024),
      snapshot('2026-10-29T12:00:05Z', 2048),
      snapshot('2026-10-29T12:00:10Z', 3072),
    ];
    const rows = [
      baseRows[0],
      { ...baseRows[1], process: { ...baseRows[1].process, gcCount: 2 }, http: { ...baseRows[1].http, serverErrors: 1 }, journal: { ...baseRows[1].journal, activeCompactionFailures: 1 } },
      { ...baseRows[2], process: { ...baseRows[2].process, gcCount: 3 }, http: { ...baseRows[2].http, serverErrors: 2 }, journal: { ...baseRows[2].journal, activeCompactionFailures: 3 } },
    ];
    const read = vi.spyOn(api, 'getServerMonitoring').mockImplementation(async () => rows.shift()!);
    await act(async () => root.render(<AppShellContext.Provider value={context}><MonitoringPage /></AppShellContext.Provider>));
    await act(async () => { await Promise.resolve(); });
    const select = host.querySelector<HTMLSelectElement>('select')!;
    expect(select.value).toBe('15');
    expect(host.textContent).toContain('Not available');
    expect(host.textContent).toContain('Active LOAD jobs');
    expect(host.textContent).toContain('Process RSS');
    expect(host.textContent).toContain('2xx / 3xx / 4xx / 5xx totals');
    expect(host.textContent).not.toContain('/Users/');
    await act(async () => { select.value = '5'; select.dispatchEvent(new Event('change', { bubbles: true })); });
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
    expect(read).toHaveBeenCalledTimes(3);
    expect(host.textContent).toContain('Journal compaction failures increased since the previous snapshot.');
    const points = host.querySelector('.att-monitor-chart polyline')?.getAttribute('points') || '';
    expect(points).toMatch(/^\d+(\.\d+)?,\d+(\.\d+)? \d+(\.\d+)?,\d+(\.\d+)?/);
    expect(host.textContent).toContain('Session trends');
    expect(host.querySelectorAll('.att-monitor-chart polyline').length).toBeGreaterThanOrEqual(4);
  });

  it('aborts an in-flight request while hidden and refreshes immediately when visible again', async () => {
    let resolveFirst!: (value: MonitoringSnapshot) => void;
    const pending = new Promise<MonitoringSnapshot>(resolve => { resolveFirst = resolve; });
    const read = vi.spyOn(api, 'getServerMonitoring')
      .mockReturnValueOnce(pending)
      .mockResolvedValue(snapshot('2026-10-29T12:01:00Z', 1024));
    await act(async () => root.render(<AppShellContext.Provider value={context}><MonitoringPage /></AppShellContext.Provider>));
    const firstSignal = read.mock.calls[0]?.[0];
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    expect(firstSignal?.aborted).toBe(true);
    await act(async () => { resolveFirst(snapshot('2026-10-29T12:00:00Z', 1024)); await Promise.resolve(); });
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await act(async () => { await Promise.resolve(); });
    expect(read).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('Health');
  });

  it('labels uncached per-Job metrics and links to the authorized Events view', async () => {
    const read = vi.spyOn(api, 'getMonitoringJobJournal').mockResolvedValue({
      jobId: 'J0123456789ABCDEF', metricsAvailable: false, persistence: null, journalBytes: null, maxJournalBytes: null,
      retainedEvents: null, retainedBytes: null, retainedMemoryBytes: null, compactionBacklogBytes: null,
      historyTruncated: null, sequenceGapDetected: null, compactionAttempts: null, compactionFailures: null,
      listenerDispatchFailures: null, writeAmplification: null, lastCompactionAt: null, lastCompactionDurationMs: null,
      lastCompactionAgeMs: null,
    });
    await act(async () => root.render(<AppShellContext.Provider value={context}><MonitoringPage /></AppShellContext.Provider>));
    const input = host.querySelector<HTMLInputElement>('#monitoring-job-id')!;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      setter.call(input, 'J0123456789ABCDEF'); input.dispatchEvent(new Event('input', { bubbles: true }));
      host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await Promise.resolve();
    });
    expect(read).toHaveBeenCalledWith('J0123456789ABCDEF', expect.any(AbortSignal));
    expect(host.textContent).toContain('Journal metrics are not currently cached.');
    expect(host.querySelector('a[href="#/jobs/J0123456789ABCDEF?tab=events"]')).not.toBeNull();
  });
});
