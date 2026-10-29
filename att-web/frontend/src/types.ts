export interface ApiEnvelope<T> {
  items: T[];
  requestId?: string;
}

export interface ApiErrorBody {
  code?: string;
  summary?: string;
  message?: string;
  detail?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly details?: unknown;
  readonly diagnostics?: unknown;

  constructor(message: string, options: { status: number; code?: string; requestId?: string; details?: unknown; diagnostics?: unknown }) {
    super(message);
    this.name = 'ApiError';
    this.status = options.status;
    this.code = options.code;
    this.requestId = options.requestId;
    this.details = options.details;
    this.diagnostics = options.diagnostics;
  }
}

export interface ServerVersion {
  unifiedRunVersion?: string;
  version?: string;
  apiVersion: string;
  buildTime?: string;
  gitCommit?: string;
  javaMinimum?: number;
  inlineLoadEnabled: boolean;
  loadSchedulingEnabled?: boolean;
  authMode?: 'container' | 'local';
  authenticationRequired?: boolean;
}

export interface LocalAuthUser {
  loginId: string;
  displayName: string;
  role: 'ADMIN' | 'USER';
  active: boolean;
  updatedAt?: string;
}

export interface PackageSummary {
  packageId: string;
  name?: string;
  canExecute?: boolean;
}

export type JobStatus =
  | 'QUEUED'
  | 'PREPARING'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'PASS'
  | 'FAIL'
  | 'INVALID'
  | 'ERROR'
  | 'CANCELLED'
  | string;

export interface JobSummary {
  executionType?: string;
  jobId: string;
  packageId: string;
  command: string;
  principal?: string;
  status: JobStatus;
  createdAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  exitCode?: number | null;
}

export type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; error: Error };

export interface ServerCapabilities {
  apiVersion: string | null;
  inlineLoadEnabled: boolean;
  loadSchedulingEnabled: boolean;
}

export interface MonitoringSnapshot {
  schemaVersion: 'att-monitoring/v1';
  capturedAt: string;
  origin: string;
  requestId?: string;
  health: { status: string; version: string; uptimeMs: number };
  process: { heapUsedBytes: number | null; heapCommittedBytes: number | null; heapMaxBytes: number | null; nonHeapUsedBytes: number | null; nonHeapCommittedBytes: number | null; threadCount: number; daemonThreadCount: number; peakThreadCount: number; gcCount: number | null; gcTimeMs: number | null; availableProcessors: number | null; processCpuLoad: number | null; processCpuTimeMs: number | null; processRssBytes: number | null };
  workers: { active: number; maxConcurrent: number; queued: number; queueCapacity: number; admissionCapacity: number; completedSinceStart: number; admissionSlotsUsed: number; loadSlotsUsed: number; activeLoadJobs: number; maxConcurrentLoad: number; loadCapacityRejections: number; queueCapacityRejections: number; workerAbnormalExits: number | null; workerTimeouts: number | null; activeSseConnections: number; sseCapacity: number; sseErrors: number | null; sseReconnects: number | null };
  http: { requests: number; successfulRequests: number; redirectRequests: number; clientErrors: number; serverErrors: number; recentP50Ms: number | null; recentP95Ms: number | null };
  journal: { activeJobs: number; degradedActiveJobs: number; activeJournalBytes: number; activeRetainedEvents: number; activeRetainedMemoryBytes: number; activeMemoryOverBudgetBytes: number; activeCompactionAttempts: number; activeCompactionFailures: number; activeListenerDispatchFailures: number; appendBytesWritten: number; compactionBytesWritten: number; writeAmplification: number | null; oldestActiveCompactionAgeMs: number | null; maxJournalBytesPerJob: number; memoryBudgetBytesPerJob: number; storageUsableBytes: number | null; storageHeadroomLow: boolean; cachedTerminalJobs: number; cachedTerminalWeightBytes: number };
  storage: { state: string; fileStoreUsableBytes: number | null; fileStoreTotalBytes: number | null; jobQuotaEnabled: boolean; jobQuotaBytes: number | null; jobStorageBytes: number | null; jobStorageMeasurementComplete: boolean | null; eligibleJobs: number | null; protectedJobs: number | null; deletedJobs: number | null; reclaimedBytes: number | null; quotaUnmet: boolean | null; cleanupTargetBytes: number | null; lastSweepAt: string | null; maxSingleJobBytes: number | null; archiveWritersActive: number; archiveQueueDepth: number; archiveQueueCapacity: number; pendingArchiveJobs: number; archiveFailuresSinceStart: number; archiveRetriesSinceStart: number; artifactBytes: number | null };
  inspection: { enabled: boolean; workerCount: number; busyWorkers: number; liveProcesses: number; requests: number | null; failures: number | null; totalDurationMs: number | null; workerStarts: number | null; indexCacheHits: number | null; indexCacheMisses: number | null; configCacheHits: number | null; configCacheMisses: number | null; inspectorCacheHits: number | null; inspectorCacheMisses: number | null; serverStages: Record<string, { count: number; totalDurationMs: number }>; workers: Array<{ slot: number; busy: boolean; processAlive: boolean; heapUsedBytes: number | null; processCpuTimeMs: number | null; indexCacheHits: number | null; indexCacheMisses: number | null; configCacheHits: number | null; configCacheMisses: number | null; inspectorCacheHits: number | null; inspectorCacheMisses: number | null }> };
  executionPool: { enabled: boolean; owned: number | null; ready: number | null; maxIdle: number | null; starts: number | null; leases: number | null; coldFallbacks: number | null; startupFailures: number | null; recoveryUncertain: boolean | null; idleHeapCapBytes: number | null; idleCpuMetricsAvailable: boolean | null; idleCpuMs: number | null };
  scheduledLoad: { enabled: boolean; scheduleCount: number | null; activeLoads: number | null; maxConcurrentRuns: number | null; nextDueAt: string | null; occurrences: Record<string, number> | null };
  jobs: Record<string, number>;
}

export interface MonitoringJobJournal {
  jobId: string;
  metricsAvailable: boolean;
  persistence: string | null;
  journalBytes: number | null;
  maxJournalBytes: number | null;
  retainedEvents: number | null;
  retainedBytes: number | null;
  retainedMemoryBytes: number | null;
  compactionBacklogBytes: number | null;
  historyTruncated: boolean | null;
  sequenceGapDetected: boolean | null;
  compactionAttempts: number | null;
  compactionFailures: number | null;
  listenerDispatchFailures: number | null;
  writeAmplification: number | null;
  lastCompactionAt: string | null;
  lastCompactionDurationMs: number | null;
  lastCompactionAgeMs: number | null;
}
