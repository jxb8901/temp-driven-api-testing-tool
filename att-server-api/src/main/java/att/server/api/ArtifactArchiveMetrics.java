package att.server.api;

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
    public Double scanClassificationRedactionMs;
    public Double zipWriteMs;
    public Double verificationFsyncMs;
    public Double cleanupMs;
    public Double writerCpuMs;
    public Long writerAllocatedBytes;
    public Long sourceFiles;
    public Long sourceBytes;
    public Long includedEntries;
    public Long omittedEntries;
    public Long includedBytes;
    public Long zipBytes;
    public Double compressionRatio;
    public Long temporaryExportFiles;
    public Long temporaryExportBytes;
    public Long memoryExportPeakBytes;
}
