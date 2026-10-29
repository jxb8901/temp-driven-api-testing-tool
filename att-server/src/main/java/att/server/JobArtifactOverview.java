package att.server;

import java.util.List;

/** Bounded archive state for Job Overview; it never lists archive entries. */
public record JobArtifactOverview(
        String archiveStatus,
        boolean summaryVerified,
        String reportAvailability,
        String reportPath,
        Integer approvedArtifactCount,
        Integer approvedReportCount,
        Long omittedCandidateCount,
        Boolean complete,
        List<OmissionCategory> omissions,
        boolean omissionReasonsAvailable) {
    public record OmissionCategory(long count, String category) { }
}
