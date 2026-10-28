package att.server.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded Server archive telemetry, independent of Worker execution metrics. */
public final class ArtifactArchiveMetrics {
    public int version = 1;
    public long attempts;
    public long retries;
    public String outcome;
    public String failurePhase;
    public String failureReason;
    public Double queueWaitMs;
    public Double publicationWallMs;
    /** Legacy metric retained when reading persisted v1 snapshots. New publications use scanValidationPreservationMs. */
    @Deprecated public Double scanClassificationRedactionMs;
    public Double scanValidationPreservationMs;
    public Double zipWriteMs;
    public Double verificationFsyncMs;
    public Double cleanupMs;
    public Double writerCpuMs;
    public Long writerAllocatedBytes;
    public Long sourceFiles;
    /** Filesystem candidates counted by the bounded archive inventory; null when the scan did not complete. */
    public Long candidateCount;
    public Long sourceBytes;
    public Long includedEntries;
    public Long omittedEntries;
    public Long includedBytes;
    public Long zipBytes;
    public Double compressionRatio;
    public String configuredCompressionMode;
    public String effectiveCompressionMode;
    public Integer compressionLevel;
    public Double compressionDurationMs;
    public Long storedEntries;
    public Long deflatedEntries;
    public Long compressedPayloadBytes;
    public Long archiveOverheadBytes;
    public Double compressionSavingsPercent;
    public Long compressionFallbackCount;
    public Map<String, Long> compressionFallbackReasons;
    public Long intentionallyExcludedRecoveryCheckpointFiles;
    public Long intentionallyExcludedRecoveryCheckpointBytes;
    public Long temporaryExportFiles;
    public Long temporaryExportBytes;
    /** Bytes reread from validated producer-byte spill files; null on metrics written before this counter existed. */
    public Long temporaryExportReadBytes;
    /** Uncompressed approved payload bytes passed to ZIP entries, including the manifest. */
    public Long zipWriteInputBytes;
    /** Uncompressed manifest and entry bytes consumed by digest verification. */
    public Long verificationBytesRead;
    public Long memoryExportPeakBytes;
    /** Bounded usage/limit/threshold snapshots; absent means the measurement was unavailable. */
    public Map<String, Map<String, Object>> limitUsage = new LinkedHashMap<String, Map<String, Object>>();
}
