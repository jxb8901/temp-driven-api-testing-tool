package att.server;

import java.util.Map;

/** Stable, explicitly allowlisted read model for the operator Monitoring page. */
public record MonitoringSnapshot(
        String schemaVersion,
        String capturedAt,
        String origin,
        String requestId,
        Health health,
        Process process,
        Workers workers,
        Http http,
        Journal journal,
        Storage storage,
        Inspection inspection,
        ExecutionPool executionPool,
        ScheduledLoad scheduledLoad,
        Map<String, Long> jobs) {
    public record Health(String status, String version, long uptimeMs) { }
    public record Process(Long heapUsedBytes, Long heapCommittedBytes, Long heapMaxBytes,
                          Long nonHeapUsedBytes, Long nonHeapCommittedBytes,
                          int threadCount, int daemonThreadCount, int peakThreadCount,
                          Long gcCount, Long gcTimeMs,
                          Integer availableProcessors, Double processCpuLoad,
                          Long processCpuTimeMs, Long processRssBytes) { }
    public record Workers(int active, int maxConcurrent, int queued, int queueCapacity,
                          int admissionCapacity, long completedSinceStart, long admissionSlotsUsed,
                          long loadSlotsUsed, long activeLoadJobs, int maxConcurrentLoad,
                          long loadCapacityRejections, long queueCapacityRejections,
                          Long workerAbnormalExits, Long workerTimeouts,
                          int activeSseConnections, int sseCapacity,
                          Long sseErrors, Long sseReconnects) { }
    public record Http(long requests, long successfulRequests, long redirectRequests,
                       long clientErrors, long serverErrors,
                       Long recentP50Ms, Long recentP95Ms) { }
    public record Journal(int activeJobs, int degradedActiveJobs, long activeJournalBytes,
                          long activeRetainedEvents, long activeRetainedMemoryBytes,
                          long activeMemoryOverBudgetBytes,
                          long activeCompactionAttempts, long activeCompactionFailures,
                          long compactionFailuresSinceStart,
                          long activeListenerDispatchFailures, long appendBytesWritten,
                          long compactionBytesWritten, Double writeAmplification,
                          Long oldestActiveCompactionAgeMs, long maxJournalBytesPerJob,
                          long memoryBudgetBytesPerJob, Long storageUsableBytes,
                          boolean storageHeadroomLow, int cachedTerminalJobs,
                          long cachedTerminalWeightBytes) { }
    public record Storage(String state, Long fileStoreUsableBytes, Long fileStoreTotalBytes,
                          boolean jobQuotaEnabled, Long jobQuotaBytes, Long jobStorageBytes,
                          Boolean jobStorageMeasurementComplete, Integer eligibleJobs,
                          Integer protectedJobs, Integer deletedJobs, Long reclaimedBytes,
                          Boolean quotaUnmet, Long cleanupTargetBytes, String lastSweepAt,
                          Long maxSingleJobBytes, int archiveWritersActive, int archiveQueueDepth,
                          int archiveQueueCapacity, int pendingArchiveJobs,
                          long archiveFailuresSinceStart, long archiveRetriesSinceStart,
                          Long artifactBytes) { }
    public record Inspection(boolean enabled, int workerCount, int busyWorkers,
                             int liveProcesses, Long requests, Long failures,
                             Long totalDurationMs, Long workerStarts,
                             Long indexCacheHits, Long indexCacheMisses,
                             Long configCacheHits, Long configCacheMisses,
                             Long inspectorCacheHits, Long inspectorCacheMisses,
                             Map<String, StageMetric> serverStages,
                             java.util.List<InspectionWorkerSnapshot> workers) { }
    public record StageMetric(long count, long totalDurationMs) { }
    public record InspectionWorkerSnapshot(int slot, boolean busy, boolean processAlive,
                                           Long heapUsedBytes, Long processCpuTimeMs,
                                           Long indexCacheHits, Long indexCacheMisses,
                                           Long configCacheHits, Long configCacheMisses,
                                           Long inspectorCacheHits, Long inspectorCacheMisses) { }
    public record ExecutionPool(boolean enabled, Integer owned, Integer ready, Integer maxIdle,
                                Long starts, Long leases, Long coldFallbacks, Long startupFailures,
                                Boolean recoveryUncertain, Long idleHeapCapBytes,
                                Boolean idleCpuMetricsAvailable, Double idleCpuMs) { }
    public record ScheduledLoad(boolean enabled, Integer scheduleCount, Integer activeLoads,
                                Integer maxConcurrentRuns, String nextDueAt,
                                Map<String, Long> occurrences) { }
    public record JobJournal(String jobId, boolean metricsAvailable, String persistence,
                             Long journalBytes, Long maxJournalBytes, Long retainedEvents,
                             Long retainedBytes, Long retainedMemoryBytes,
                             Long compactionBacklogBytes, Boolean historyTruncated,
                             Boolean sequenceGapDetected, Long compactionAttempts,
                             Long compactionFailures, Long listenerDispatchFailures,
                             Double writeAmplification, String lastCompactionAt,
                             Long lastCompactionDurationMs, Long lastCompactionAgeMs) { }
}
