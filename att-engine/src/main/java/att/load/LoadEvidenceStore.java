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

/**
 * Bounded failure/sample evidence sink, separate from functional run artifacts.
 *
 * <p>In split-quota mode the success budget is shared across registered workloads:
 * each workload receives a deterministic minimum share (the global cap divided
 * evenly, with the remainder assigned to the earliest-registered workloads), then
 * once every workload has reached its minimum the remaining budget is allocated
 * opportunistically on a first-come basis. The configured global cap is always
 * enforced, including when the cap is smaller than the workload count. Failure
 * evidence remains independently bounded and is never starved by success quotas.</p>
 */
public final class LoadEvidenceStore implements LoadEventListener {
    private static final String DEFAULT_WORKLOAD = "\u0000default";
    private final LoadEvidencePolicy policy;
    private final LoadEventListener observer;
    private final List<LoadEvent> retained = new ArrayList<LoadEvent>();
    private final Set<String> reservedEvidence = new LinkedHashSet<String>();
    private final Set<String> successReservations = new LinkedHashSet<String>();
    private final Set<String> failureReservations = new LinkedHashSet<String>();
    private final Set<String> workloads = new LinkedHashSet<String>();
    private final Map<String, String> successReservationBucket = new LinkedHashMap<String, String>();
    private final Map<String, Integer> successReservedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> successRetainedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> failureRetainedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Long> eligibleSuccessByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedQuotaByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedFailureQuotaByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedMissingByWorkload = new LinkedHashMap<String, Long>();
    private int retainedSuccessCount, retainedFailureCount;
    private long skippedSuccessQuotaCount, skippedFailureQuotaCount, skippedMissingEvidenceCount;
    public LoadEvidenceStore(LoadEvidencePolicy policy) { this(policy, null); }
    public LoadEvidenceStore(LoadEvidencePolicy policy, LoadEventListener observer) {
        this.policy = policy;
        this.observer = observer;
    }

    /**
     * Registers one workload before reservations begin. Fair success quotas are
     * computed across registered workloads; an unregistered store behaves as a
     * single workload so direct/unit usage keeps its current semantics.
     */
    public synchronized void registerWorkload(String workloadId) {
        if (workloadId != null && !workloadId.isEmpty()) workloads.add(workloadId);
    }

    private String bucketFor(String workloadId) {
        if (workloads.isEmpty()) return DEFAULT_WORKLOAD;
        return workloadId != null && workloads.contains(workloadId) ? workloadId : DEFAULT_WORKLOAD;
    }

    private int workloadCount() { return workloads.isEmpty() ? 1 : workloads.size(); }

    private int workloadIndex(String bucket) {
        if (workloads.isEmpty()) return 0;
        if (!workloads.contains(bucket)) return workloads.size();
        int index = 0;
        for (String workload : workloads) {
            if (workload.equals(bucket)) return index;
            index++;
        }
        return workloads.size();
    }

    private int successShareFor(String bucket) {
        int count = workloadCount();
        int base = policy.maxSuccessSamples() / count;
        int remainder = policy.maxSuccessSamples() % count;
        return base + (workloadIndex(bucket) < remainder ? 1 : 0);
    }

    private int usedSuccess(String bucket) {
        return successRetainedByWorkload.getOrDefault(bucket, 0)
                + successReservedByWorkload.getOrDefault(bucket, 0);
    }

    private boolean successOverflowAllowed() {
        for (String workload : workloads.isEmpty() ? java.util.Collections.singleton(DEFAULT_WORKLOAD) : workloads)
            if (usedSuccess(workload) < successShareFor(workload)) return false;
        return true;
    }

    private void skipSuccessQuota(String bucket) {
        skippedSuccessQuotaCount++;
        skippedQuotaByWorkload.merge(bucket, 1L, Long::sum);
    }

    private void releaseSuccessReservation(String iterationId) {
        if (!successReservations.remove(iterationId)) return;
        String bucket = successReservationBucket.remove(iterationId);
        if (bucket != null) successReservedByWorkload.merge(bucket, -1, Integer::sum);
    }

    /** Returns whether an unreserved slot remains for a failure, so the scheduler can avoid needless log capture. */
    public synchronized boolean retainsFailureEvidence() {
        return policy.retainFailure() && (policy.splitQuotas()
                ? (long)retainedFailureCount + failureReservations.size() < policy.maxFailureSamples()
                : (long) retained.size() + reservedEvidence.size() < policy.maxSamples());
    }
    public boolean retainsSuccessEvidence(String iterationId) { return policy.retainSuccess(iterationId); }

    /** Reserves a slot only when this iteration is known to be success-eligible. */
    public synchronized boolean reserveSuccessEvidence(String iterationId) {
        return reserveSuccessEvidence(iterationId, null);
    }

    /** Workload-aware success reservation with a fair per-workload minimum share. */
    public synchronized boolean reserveSuccessEvidence(String iterationId, String workloadId) {
        if(policy.splitQuotas()) {
            if(iterationId==null||successReservations.contains(iterationId)||failureReservations.contains(iterationId)||!policy.retainSuccess(iterationId))return false;
            String bucket=bucketFor(workloadId);
            if((long)retainedSuccessCount+successReservations.size()>=policy.maxSuccessSamples()){skipSuccessQuota(bucket);return false;}
            if(usedSuccess(bucket)>=successShareFor(bucket)&&!successOverflowAllowed()){skipSuccessQuota(bucket);return false;}
            successReservations.add(iterationId);
            successReservationBucket.put(iterationId,bucket);
            successReservedByWorkload.merge(bucket,1,Integer::sum);
            return true;
        }
        if (iterationId == null || reservedEvidence.contains(iterationId) || !policy.retainSuccess(iterationId)) return false;
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()) return false;
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Claims a failure slot after the iteration outcome is known. */
    public synchronized boolean claimFailureEvidence(String iterationId) {
        return claimFailureEvidence(iterationId, null);
    }

    public synchronized boolean claimFailureEvidence(String iterationId, String workloadId) {
        if(policy.splitQuotas()) {
            if(iterationId==null)return false;
            releaseSuccessReservation(iterationId);
            if(!policy.retainFailure())return false;
            if(failureReservations.contains(iterationId))return true;
            if((long)retainedFailureCount+failureReservations.size()>=policy.maxFailureSamples()){
                skippedFailureQuotaCount++;
                String bucket=bucketFor(workloadId);
                skippedFailureQuotaByWorkload.merge(bucket,1L,Long::sum);
                return false;
            }
            failureReservations.add(iterationId);return true;
        }
        if (iterationId == null || !policy.retainFailure()) return false;
        if (reservedEvidence.contains(iterationId)) return true;
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()) return false;
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Releases a pre-execution reservation that did not become retained evidence. */
    public synchronized void releaseEvidence(String iterationId) {
        if (iterationId != null) {
            releaseSuccessReservation(iterationId);
            reservedEvidence.remove(iterationId);failureReservations.remove(iterationId);
        }
    }

    public synchronized boolean reserveEvidence(String iterationId) {
        if(policy.splitQuotas())return policy.retainSuccess(iterationId)?reserveSuccessEvidence(iterationId):claimFailureEvidence(iterationId);
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
        if(event==null)return;
        if(event.dropped()){releaseEvidence(event.iterationId());return;}
        if(!event.completed())return;
        String bucket=bucketFor(event.workloadId());
        if(event.success()&&policy.retainSuccess(event.iterationId()))
            eligibleSuccessByWorkload.merge(bucket,1L,Long::sum);
        if(policy.splitQuotas()) {
            // Outcome reclassification also handles callers using the generic reservation API.
            if(!event.success()&&successReservations.contains(event.iterationId()))claimFailureEvidence(event.iterationId(),event.workloadId());
            boolean reserved;
            if(event.success()) {
                reserved=successReservations.remove(event.iterationId());
                if(reserved) {
                    String reservationBucket=successReservationBucket.remove(event.iterationId());
                    if(reservationBucket!=null)successReservedByWorkload.merge(reservationBucket,-1,Integer::sum);
                }
            } else {
                reserved=failureReservations.remove(event.iterationId());
            }
            int current=event.success()?retainedSuccessCount:retainedFailureCount;
            if(!reserved||!policy.retain(event,current))return;
            if(!evidenceExists(event.evidence()) || event.evidence().caseLog()==null){
                skippedMissingEvidenceCount++;skippedMissingByWorkload.merge(bucket,1L,Long::sum);return;
            }
            retained.add(event);
            if(event.success()){retainedSuccessCount++;successRetainedByWorkload.merge(bucket,1,Integer::sum);}
            else{retainedFailureCount++;failureRetainedByWorkload.merge(bucket,1,Integer::sum);}
            return;
        }
        boolean reserved = reservedEvidence.remove(event.iterationId());
        if (!reserved || retained.size() >= policy.maxSamples()) return;
        if (!policy.retain(event, retained.size()) || !evidenceExists(event.evidence())) return;
        retained.add(event);
        if(event.success()){retainedSuccessCount++;successRetainedByWorkload.merge(bucket,1,Integer::sum);}
        else{retainedFailureCount++;failureRetainedByWorkload.merge(bucket,1,Integer::sum);}
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
            if (event.evidence() != null && event.evidence().executionId() != null) link.put("execId", event.evidence().executionId());
            if (event.evidence() != null && event.evidence().workspace() != null)
                link.put("workspace", relative(runDirectory, event.evidence().workspace()));
            if (event.evidence() != null && event.evidence().caseLog() != null)
                link.put("caseLog", relative(runDirectory, event.evidence().caseLog()));
            link.put("status", failure ? "FAILURE" : "SAMPLE");
            link.put("path", runDirectory.relativize(file).toString().replace('\\', '/'));
            links.add(link);
        }
        result.put("count", retained.size()); result.put("items", links);
        result.put("policy",policy.toMap());result.putAll(statistics());return result;
    }
    public synchronized Map<String,Object> statistics() {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("retainedSuccessCount",retainedSuccessCount);result.put("retainedFailureCount",retainedFailureCount);
        result.put("reservedSuccessCount",successReservations.size());result.put("reservedFailureCount",failureReservations.size());
        // A rejected claim is still a real loss of evidence even when in-flight
        // reservations later release capacity before the report is written.
        result.put("successQuotaExceeded",policy.splitQuotas()&&policy.success()!=LoadEvidencePolicy.Success.NONE
                &&(retainedSuccessCount>=policy.maxSuccessSamples()||skippedSuccessQuotaCount>0));
        boolean failureExhausted=policy.splitQuotas()&&policy.retainFailure()
                &&(retainedFailureCount>=policy.maxFailureSamples()||skippedFailureQuotaCount>0);
        result.put("failureQuotaExceeded",failureExhausted);
        result.put("skippedSuccessQuotaCount",skippedSuccessQuotaCount);result.put("skippedFailureQuotaCount",skippedFailureQuotaCount);
        result.put("skippedMissingEvidenceCount",skippedMissingEvidenceCount);
        if(failureExhausted)result.put("warning","Failure evidence quota reached; additional failures remain in aggregate metrics but full iteration evidence cannot be retained.");
        Map<String,Object> byWorkload=new LinkedHashMap<String,Object>();
        for(String workload:allWorkloadBuckets()) {
            Map<String,Object> row=new LinkedHashMap<String,Object>();
            row.put("eligibleSuccessCount",eligibleSuccessByWorkload.getOrDefault(workload,0L));
            row.put("retainedSuccessCount",successRetainedByWorkload.getOrDefault(workload,0));
            row.put("retainedFailureCount",failureRetainedByWorkload.getOrDefault(workload,0));
            row.put("skippedSuccessQuotaCount",skippedQuotaByWorkload.getOrDefault(workload,0L));
            row.put("skippedFailureQuotaCount",skippedFailureQuotaByWorkload.getOrDefault(workload,0L));
            row.put("skippedMissingEvidenceCount",skippedMissingByWorkload.getOrDefault(workload,0L));
            byWorkload.put(DEFAULT_WORKLOAD.equals(workload)?"default":workload,row);
        }
        result.put("workloads",byWorkload);
        return result;
    }

    private Set<String> allWorkloadBuckets() {
        LinkedHashSet<String> all=new LinkedHashSet<String>(workloads);
        all.addAll(successReservedByWorkload.keySet());
        all.addAll(successRetainedByWorkload.keySet());
        all.addAll(failureRetainedByWorkload.keySet());
        all.addAll(eligibleSuccessByWorkload.keySet());
        all.addAll(skippedQuotaByWorkload.keySet());
        all.addAll(skippedFailureQuotaByWorkload.keySet());
        return all;
    }

    private String relative(Path base, Path path) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedBase)
                ? normalizedBase.relativize(normalizedPath).toString().replace('\\', '/')
                : normalizedPath.toString();
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
