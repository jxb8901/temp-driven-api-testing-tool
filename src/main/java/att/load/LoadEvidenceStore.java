package att.load;

import att.validation.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded failure/sample evidence sink, separate from functional run artifacts. */
public final class LoadEvidenceStore implements LoadEventListener {
    private final LoadEvidencePolicy policy;
    private final LoadEventListener observer;
    private final List<LoadEvent> retained = new ArrayList<LoadEvent>();
    private final Set<String> reservedEvidence = new LinkedHashSet<String>();
    public LoadEvidenceStore(LoadEvidencePolicy policy) { this(policy, null); }
    public LoadEvidenceStore(LoadEvidencePolicy policy, LoadEventListener observer) {
        this.policy = policy;
        this.observer = observer;
    }
    public boolean retainsFailureEvidence() { return policy.failure() == LoadEvidencePolicy.Failure.FULL; }
    public boolean retainsSuccessEvidence(String iterationId) { return policy.retainSuccess(iterationId); }

    /** Reserves a slot only when this iteration is known to be success-eligible. */
    public synchronized boolean reserveSuccessEvidence(String iterationId) {
        if (iterationId == null || reservedEvidence.contains(iterationId) || !policy.retainSuccess(iterationId)) return false;
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()) return false;
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Claims a failure slot after the iteration outcome is known. */
    public synchronized boolean claimFailureEvidence(String iterationId) {
        if (iterationId == null || !policy.retainFailure()) return false;
        if (reservedEvidence.contains(iterationId)) return true;
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()) return false;
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Releases a pre-execution reservation that did not become retained evidence. */
    public synchronized void releaseEvidence(String iterationId) {
        if (iterationId != null) reservedEvidence.remove(iterationId);
    }

    public synchronized boolean reserveEvidence(String iterationId) {
        if (iterationId == null || reservedEvidence.contains(iterationId)) return false;
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()) return false;
        if (!policy.retainSuccess(iterationId) && !policy.retainFailure()) return false;
        reservedEvidence.add(iterationId);
        return true;
    }
    /** @deprecated use {@link #reserveSuccessEvidence(String)} for success or {@link #claimFailureEvidence(String)} for failure. */
    @Deprecated public boolean reserveSuccess(String iterationId) { return reserveSuccessEvidence(iterationId); }
    @Override public void onEvent(LoadEvent event) {
        if (observer != null) try { observer.onEvent(event); }
        catch (RuntimeException ignored) { /* Presentation must not alter load execution semantics. */ }
        retain(event);
    }
    private synchronized void retain(LoadEvent event) {
        if (event == null || event.dropped() || !event.completed()) return;
        boolean reserved = reservedEvidence.remove(event.iterationId());
        if (!reserved || retained.size() >= policy.maxSamples()) return;
        if (!policy.retain(event, retained.size()) || !evidenceExists(event.evidence())) return;
        retained.add(event);
    }
    private boolean evidenceExists(EvidenceRef evidence) {
        if (evidence == null || evidence.workspace() == null || !Files.isDirectory(evidence.workspace())) return false;
        return evidence.caseLog() == null || Files.isRegularFile(evidence.caseLog());
    }
    public synchronized List<LoadEvent> events() { return Collections.unmodifiableList(new ArrayList<LoadEvent>(retained)); }
    public synchronized Map<String, Object> write(Path runDirectory) throws IOException {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (LoadEvent event : retained) {
            boolean failure = !event.success();
            String workload = event.workloadId() == null ? null : safe(event.workloadId());
            Path directory = runDirectory.resolve(failure ? "failures" : "samples");
            if (workload != null) directory = directory.resolve(workload);
            Files.createDirectories(directory);
            String fileName = String.format("%05d-%s.json", ++index, LoadIsolation.shortHash(event.iterationId()));
            Path file = directory.resolve(fileName);
            Files.write(file, JsonSupport.write(event.toMap(runDirectory)).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> link = new LinkedHashMap<String, Object>();
            if (event.workloadId() != null) link.put("workloadId", event.workloadId());
            if (event.targetType() != null) link.put("targetType", event.targetType());
            if (event.targetId() != null) link.put("targetId", event.targetId());
            link.put("iterationId", event.iterationId());
            link.put("status", failure ? "FAILURE" : "SAMPLE");
            link.put("path", runDirectory.relativize(file).toString().replace('\\', '/'));
            links.add(link);
        }
        result.put("count", retained.size()); result.put("items", links); return result;
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
