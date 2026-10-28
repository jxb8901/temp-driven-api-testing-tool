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
 * <p>Quota accounting depends on the configured evidence scope:</p>
 * <ul>
 *   <li>Legacy policies keep their existing behaviour: one combined cap, or independent
 *   success/failure caps shared by the whole run.</li>
 *   <li>When a scheduler registers the planned run timeline, each cap is divided into
 *   up to ten equal-time windows. Each window retains its fixed share of the cap, so
 *   an early burst cannot consume later-window slots. Unused slots are not borrowed
 *   and retained events are never replaced; when no timeline is registered, selection
 *   falls back to first-come retention under the same total cap.</li>
 *   <li>With {@code evidence.quotaScope: perWorkload} every workload has its own hard
 *   success and failure caps. A workload reaching its cap never reduces another
 *   workload's allowance, and unused capacity stays unused. Nothing is lent, borrowed,
 *   reclaimed or revoked: previously retained samples are append-only for the run.</li>
 * </ul>
 *
 * <p>Reservations are taken before execution for success-eligible iterations and
 * released on cancellation, reclassification to failure, missing artifacts or dropped
 * arrivals, so a reservation never outlives the iteration that owns it. Success and
 * failure capacities are independent; exhaustion of one never suppresses the other.</p>
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
    private final Map<String, String> targetIdsByWorkload = new LinkedHashMap<String, String>();
    private final Map<String, String> successReservationBucket = new LinkedHashMap<String, String>();
    private final Map<String, String> failureReservationBucket = new LinkedHashMap<String, String>();
    private final Map<String, Integer> successReservedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> failureReservedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> successRetainedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> failureRetainedByWorkload = new LinkedHashMap<String, Integer>();
    private final Map<String, Long> eligibleSuccessByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> hashQualifiedSuccessByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> samplingIneligibleSuccessByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedQuotaByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedFailureQuotaByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedUnclassifiedQuotaByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Long> skippedMissingByWorkload = new LinkedHashMap<String, Long>();
    private final Map<String, Timeline> timelinesByWorkload = new LinkedHashMap<String, Timeline>();
    private final Map<String, String> reservationSlotByIteration = new LinkedHashMap<String, String>();
    private final Map<String, Integer> reservationWindowByIteration = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> reservedBySlot = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> retainedBySlot = new LinkedHashMap<String, Integer>();
    private final Map<String, String> quotaDenialTypeByIteration = new LinkedHashMap<String, String>();
    private final Map<String, String> quotaDenialBucketByIteration = new LinkedHashMap<String, String>();
    private final Map<Integer, Long> retainedByTimeWindow = new LinkedHashMap<Integer, Long>();
    private final Map<String, Long> retainedByPhase = new LinkedHashMap<String, Long>();
    private int retainedSuccessCount, retainedFailureCount;
    private long completedSuccessCount, hashQualifiedSuccessCount, samplingIneligibleSuccessCount;
    private long skippedSuccessQuotaCount, skippedFailureQuotaCount, skippedUnclassifiedQuotaCount, skippedMissingEvidenceCount;

    private static final class Timeline {
        final long startedAtEpochMs;
        final long durationMs;
        Timeline(long startedAtEpochMs, long durationMs) {
            this.startedAtEpochMs = startedAtEpochMs;
            this.durationMs = Math.max(0L, durationMs);
        }
    }
    public LoadEvidenceStore(LoadEvidencePolicy policy) { this(policy, null); }
    public LoadEvidenceStore(LoadEvidencePolicy policy, LoadEventListener observer) {
        this.policy = policy;
        this.observer = observer;
    }

    /**
     * Registers one workload before reservations begin, so per-workload caps and coverage
     * reporting can name it explicitly. An unregistered store behaves as a single
     * "default" workload so direct/unit usage keeps its current semantics.
     */
    public synchronized void registerWorkload(String workloadId) {
        registerWorkload(workloadId, null);
    }

    /**
     * Registers workload identity for cap accounting and coverage reporting. The target ID is
     * optional for direct store usage and is filled from the first completed event of a mixed
     * workload when it is not known when the workload is registered.
     */
    public synchronized void registerWorkload(String workloadId, String targetId) {
        if (workloadId == null || workloadId.isEmpty()) return;
        workloads.add(workloadId);
        if (targetId != null && !targetId.isEmpty()) targetIdsByWorkload.put(workloadId, targetId);
    }

    /** Registers the planned run window used to distribute capped evidence slots over time. */
    public synchronized void registerRunTimeline(String workloadId, long startedAtEpochMs, long durationMs) {
        timelinesByWorkload.put(bucketFor(workloadId), new Timeline(startedAtEpochMs, durationMs));
    }

    private String bucketFor(String workloadId) {
        if (workloads.isEmpty()) return DEFAULT_WORKLOAD;
        return workloadId != null && workloads.contains(workloadId) ? workloadId : DEFAULT_WORKLOAD;
    }

    private int usedSuccess(String bucket) {
        return successRetainedByWorkload.getOrDefault(bucket, 0)
                + successReservedByWorkload.getOrDefault(bucket, 0);
    }

    private int usedFailure(String bucket) {
        return failureRetainedByWorkload.getOrDefault(bucket, 0)
                + failureReservedByWorkload.getOrDefault(bucket, 0);
    }

    private void recordQuotaDenial(String iterationId, String type, String bucket) {
        String prior = quotaDenialTypeByIteration.get(iterationId);
        if (type.equals(prior)) return;
        if (prior != null) adjustQuotaDenial(prior, quotaDenialBucketByIteration.get(iterationId), -1L);
        adjustQuotaDenial(type, bucket, 1L);
        quotaDenialTypeByIteration.put(iterationId, type);
        quotaDenialBucketByIteration.put(iterationId, bucket);
    }

    private void clearQuotaDenial(String iterationId) {
        String prior = quotaDenialTypeByIteration.remove(iterationId);
        String bucket = quotaDenialBucketByIteration.remove(iterationId);
        if (prior != null) adjustQuotaDenial(prior, bucket, -1L);
    }

    private void finalizeQuotaDenial(String iterationId, String outcome, String bucket) {
        String prior = quotaDenialTypeByIteration.get(iterationId);
        if (prior == null) return;
        if ("unclassified".equals(prior)) {
            if ("success".equals(outcome) && policy.retainSuccess(iterationId)) recordQuotaDenial(iterationId, "success", bucket);
            else if ("failure".equals(outcome) && policy.retainFailure()) recordQuotaDenial(iterationId, "failure", bucket);
            else clearQuotaDenial(iterationId);
        } else if (!prior.equals(outcome)) {
            if ("failure".equals(outcome) && policy.retainFailure()) recordQuotaDenial(iterationId, "failure", bucket);
            else if ("success".equals(outcome) && policy.retainSuccess(iterationId)) recordQuotaDenial(iterationId, "success", bucket);
            else clearQuotaDenial(iterationId);
        } else {
            quotaDenialTypeByIteration.remove(iterationId);
            quotaDenialBucketByIteration.remove(iterationId);
        }
    }

    private void adjustQuotaDenial(String type, String bucket, long delta) {
        if ("success".equals(type)) {
            skippedSuccessQuotaCount += delta;
            skippedQuotaByWorkload.merge(bucket, delta, Long::sum);
        } else if ("failure".equals(type)) {
            skippedFailureQuotaCount += delta;
            skippedFailureQuotaByWorkload.merge(bucket, delta, Long::sum);
        } else {
            skippedUnclassifiedQuotaCount += delta;
            skippedUnclassifiedQuotaByWorkload.merge(bucket, delta, Long::sum);
        }
    }

    private int windowCount(String bucket, int cap) {
        if (timelinesByWorkload.containsKey(bucket) && cap > 0)
            return Math.min(LoadEvidencePolicy.MAX_RETENTION_WINDOWS, cap);
        return 1;
    }

    private int windowFor(String bucket, int cap, long plannedAtEpochMs) {
        int windows = windowCount(bucket, cap);
        Timeline timeline = timelinesByWorkload.get(bucket);
        if (windows <= 1 || timeline == null || timeline.durationMs <= 0L || plannedAtEpochMs == Long.MIN_VALUE) return 0;
        double fraction = ((double) plannedAtEpochMs - (double) timeline.startedAtEpochMs) / (double) timeline.durationMs;
        if (Double.isNaN(fraction) || fraction <= 0.0d) return 0;
        if (fraction >= 1.0d) return windows - 1;
        return Math.min(windows - 1, (int) Math.floor(fraction * windows));
    }

    private int windowQuota(int cap, String bucket, int window) {
        int windows = windowCount(bucket, cap);
        if (cap <= 0) return 0;
        // Rounded boundaries distribute any remainder through the run rather than
        // assigning every extra slot to the earliest time window.
        long left = ((long) window * cap + windows / 2L) / windows;
        long right = ((long) (window + 1) * cap + windows / 2L) / windows;
        return (int) (right - left);
    }

    private String slotKey(String scope, String bucket, int window) {
        return scope + "|" + bucket.length() + ":" + bucket + "|" + window;
    }

    private String reserveSlot(String iterationId, String bucket, String quotaScope, int cap, long plannedAtEpochMs) {
        int window = windowFor(bucket, cap, plannedAtEpochMs);
        String slot = slotKey(quotaScope, quotaScope.startsWith(LoadEvidencePolicy.QUOTA_SCOPE_PER_WORKLOAD) ? bucket : "", window);
        int used = retainedBySlot.getOrDefault(slot, 0) + reservedBySlot.getOrDefault(slot, 0);
        if (used >= windowQuota(cap, bucket, window)) return null;
        reservationSlotByIteration.put(iterationId, slot);
        reservationWindowByIteration.put(iterationId, window);
        reservedBySlot.merge(slot, 1, Integer::sum);
        return slot;
    }

    private String releaseSlot(String iterationId) {
        String slot = reservationSlotByIteration.remove(iterationId);
        reservationWindowByIteration.remove(iterationId);
        if (slot != null) reservedBySlot.merge(slot, -1, Integer::sum);
        return slot;
    }

    private void retainSlot(String slot, int window, LoadEvent event) {
        if (slot == null) return;
        retainedBySlot.merge(slot, 1, Integer::sum);
        retainedByTimeWindow.merge(window, 1L, Long::sum);
        if (event.phase() != null) retainedByPhase.merge(event.phase(), 1L, Long::sum);
    }

    private boolean slotAvailable(String bucket, String quotaScope, int cap, long plannedAtEpochMs) {
        int window = windowFor(bucket, cap, plannedAtEpochMs);
        String slot = slotKey(quotaScope, quotaScope.startsWith(LoadEvidencePolicy.QUOTA_SCOPE_PER_WORKLOAD) ? bucket : "", window);
        return retainedBySlot.getOrDefault(slot, 0) + reservedBySlot.getOrDefault(slot, 0)
                < windowQuota(cap, bucket, window);
    }

    private void releaseSuccessReservation(String iterationId) {
        if (!successReservations.remove(iterationId)) return;
        String bucket = successReservationBucket.remove(iterationId);
        if (bucket != null) successReservedByWorkload.merge(bucket, -1, Integer::sum);
        releaseSlot(iterationId);
    }

    private void releaseFailureReservation(String iterationId) {
        if (!failureReservations.remove(iterationId)) return;
        String bucket = failureReservationBucket.remove(iterationId);
        if (bucket != null) failureReservedByWorkload.merge(bucket, -1, Integer::sum);
        releaseSlot(iterationId);
    }

    /** Returns whether an unreserved slot remains for a failure, so the scheduler can avoid needless log capture. */
    public synchronized boolean retainsFailureEvidence() { return retainsFailureEvidence(null); }

    /** Workload-aware variant: per-workload caps answer for that workload only. */
    public synchronized boolean retainsFailureEvidence(String workloadId) {
        return retainsFailureEvidence(workloadId, Long.MIN_VALUE);
    }

    /** Workload- and planned-time-aware check used to avoid capturing logs outside an available time slot. */
    public synchronized boolean retainsFailureEvidence(String workloadId, long plannedAtEpochMs) {
        if (!policy.retainFailure()) return false;
        String bucket = bucketFor(workloadId);
        if (policy.perWorkloadQuotas()) {
            int cap = policy.failureCapFor(bucket);
            return usedFailure(bucket) < cap && slotAvailable(bucket, "perWorkload:failure", cap, plannedAtEpochMs);
        }
        if (policy.splitQuotas()) {
            int cap = policy.maxFailureSamples();
            return (long)retainedFailureCount + failureReservations.size() < cap
                    && slotAvailable(bucket, "failure", cap, plannedAtEpochMs);
        }
        int cap = policy.maxSamples();
        return (long) retained.size() + reservedEvidence.size() < cap
                && slotAvailable(bucket, "combined", cap, plannedAtEpochMs);
    }
    public boolean retainsSuccessEvidence(String iterationId) { return policy.retainSuccess(iterationId); }

    /** Reserves a slot only when this iteration is known to be success-eligible. */
    public synchronized boolean reserveSuccessEvidence(String iterationId) {
        return reserveSuccessEvidence(iterationId, null, Long.MIN_VALUE);
    }

    /** Workload-aware success reservation with an independent per-workload cap. */
    public synchronized boolean reserveSuccessEvidence(String iterationId, String workloadId) {
        return reserveSuccessEvidence(iterationId, workloadId, Long.MIN_VALUE);
    }

    /** Reserves a success slot in the time window of its planned scheduler arrival. */
    public synchronized boolean reserveSuccessEvidence(String iterationId, String workloadId, long plannedAtEpochMs) {
        if(policy.perWorkloadQuotas()) {
            if(iterationId==null||successReservations.contains(iterationId)||failureReservations.contains(iterationId)||!policy.retainSuccess(iterationId))return false;
            String bucket=bucketFor(workloadId);
            int cap=policy.successCapFor(bucket);
            if(usedSuccess(bucket)>=cap||reserveSlot(iterationId,bucket,"perWorkload:success",cap,plannedAtEpochMs)==null){recordQuotaDenial(iterationId,"success",bucket);return false;}
            successReservations.add(iterationId);
            successReservationBucket.put(iterationId,bucket);
            successReservedByWorkload.merge(bucket,1,Integer::sum);
            return true;
        }
        if(policy.splitQuotas()) {
            if(iterationId==null||successReservations.contains(iterationId)||failureReservations.contains(iterationId)||!policy.retainSuccess(iterationId))return false;
            String bucket=bucketFor(workloadId);
            int cap=policy.maxSuccessSamples();
            if((long)retainedSuccessCount+successReservations.size()>=cap||reserveSlot(iterationId,bucket,"success",cap,plannedAtEpochMs)==null){recordQuotaDenial(iterationId,"success",bucket);return false;}
            successReservations.add(iterationId);
            successReservationBucket.put(iterationId,bucket);
            successReservedByWorkload.merge(bucket,1,Integer::sum);
            return true;
        }
        if (iterationId == null || reservedEvidence.contains(iterationId) || !policy.retainSuccess(iterationId)) return false;
        String bucket=bucketFor(workloadId);
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()
                || reserveSlot(iterationId,bucket,"combined",policy.maxSamples(),plannedAtEpochMs)==null) { recordQuotaDenial(iterationId,"success",bucket); return false; }
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Claims a failure slot after the iteration outcome is known. */
    public synchronized boolean claimFailureEvidence(String iterationId) {
        return claimFailureEvidence(iterationId, null, Long.MIN_VALUE);
    }

    public synchronized boolean claimFailureEvidence(String iterationId, String workloadId) {
        return claimFailureEvidence(iterationId, workloadId, Long.MIN_VALUE);
    }

    /** Claims a failure slot at the planned arrival's time window after the outcome is known. */
    public synchronized boolean claimFailureEvidence(String iterationId, String workloadId, long plannedAtEpochMs) {
        if(iterationId!=null&&!"failure".equals(quotaDenialTypeByIteration.get(iterationId))) clearQuotaDenial(iterationId);
        if(policy.perWorkloadQuotas()) {
            if(iterationId==null)return false;
            releaseSuccessReservation(iterationId);
            if(!policy.retainFailure())return false;
            if(failureReservations.contains(iterationId))return true;
            String bucket=bucketFor(workloadId);
            int cap=policy.failureCapFor(bucket);
            if(usedFailure(bucket)>=cap||reserveSlot(iterationId,bucket,"perWorkload:failure",cap,plannedAtEpochMs)==null){recordQuotaDenial(iterationId,"failure",bucket);return false;}
            failureReservations.add(iterationId);
            failureReservationBucket.put(iterationId,bucket);
            failureReservedByWorkload.merge(bucket,1,Integer::sum);
            return true;
        }
        if(policy.splitQuotas()) {
            if(iterationId==null)return false;
            releaseSuccessReservation(iterationId);
            if(!policy.retainFailure())return false;
            if(failureReservations.contains(iterationId))return true;
            String bucket=bucketFor(workloadId);
            int cap=policy.maxFailureSamples();
            if((long)retainedFailureCount+failureReservations.size()>=cap
                    ||reserveSlot(iterationId,bucket,"failure",cap,plannedAtEpochMs)==null){
                recordQuotaDenial(iterationId,"failure",bucket);
                return false;
            }
            failureReservations.add(iterationId);failureReservationBucket.put(iterationId,bucket);
            failureReservedByWorkload.merge(bucket,1,Integer::sum);return true;
        }
        if (iterationId == null || !policy.retainFailure()) return false;
        if (reservedEvidence.contains(iterationId)) return true;
        String bucket=bucketFor(workloadId);
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()
                || reserveSlot(iterationId,bucket,"combined",policy.maxSamples(),plannedAtEpochMs)==null){recordQuotaDenial(iterationId,"failure",bucket);return false;}
        reservedEvidence.add(iterationId);
        return true;
    }

    /** Releases a pre-execution reservation that did not become retained evidence. */
    public synchronized void releaseEvidence(String iterationId) {
        if (iterationId != null) {
            releaseSuccessReservation(iterationId);
            releaseFailureReservation(iterationId);
            if(reservedEvidence.remove(iterationId))releaseSlot(iterationId);
        }
    }

    public synchronized boolean reserveEvidence(String iterationId) {
        return reserveEvidence(iterationId,null,Long.MIN_VALUE);
    }

    /** Compatibility reservation for callers that do not yet know the final outcome. */
    public synchronized boolean reserveEvidence(String iterationId, String workloadId, long plannedAtEpochMs) {
        if(policy.splitQuotas()||policy.perWorkloadQuotas())return policy.retainSuccess(iterationId)
                ?reserveSuccessEvidence(iterationId,workloadId,plannedAtEpochMs)
                :claimFailureEvidence(iterationId,workloadId,plannedAtEpochMs);
        if (iterationId == null || reservedEvidence.contains(iterationId)) return false;
        if (!policy.retainSuccess(iterationId) && !policy.retainFailure()) return false;
        String bucket=bucketFor(workloadId);
        if ((long) retained.size() + reservedEvidence.size() >= policy.maxSamples()
                || reserveSlot(iterationId,bucket,"combined",policy.maxSamples(),plannedAtEpochMs)==null) {recordQuotaDenial(iterationId,"unclassified",bucket);return false;}
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
        if(event.dropped()){clearQuotaDenial(event.iterationId());releaseEvidence(event.iterationId());return;}
        if(!event.completed())return;
        String bucket=bucketFor(event.workloadId());
        finalizeQuotaDenial(event.iterationId(),event.success()?"success":"failure",bucket);
        if(event.targetId()!=null&&!event.targetId().isEmpty())
            targetIdsByWorkload.putIfAbsent(bucket,event.targetId());
        if(event.success()) {
            completedSuccessCount++;
            eligibleSuccessByWorkload.merge(bucket,1L,Long::sum);
            if(policy.success()==LoadEvidencePolicy.Success.SAMPLE) {
                if(policy.sampleSuccess(event.iterationId())) {
                    hashQualifiedSuccessCount++;
                    hashQualifiedSuccessByWorkload.merge(bucket,1L,Long::sum);
                } else {
                    samplingIneligibleSuccessCount++;
                    samplingIneligibleSuccessByWorkload.merge(bucket,1L,Long::sum);
                }
            }
        }
        if(policy.perWorkloadQuotas()) {
            retainPerWorkload(event,bucket);
            return;
        }
        if(policy.splitQuotas()) {
            // Outcome reclassification also handles callers using the generic reservation API.
            if(!event.success()&&successReservations.contains(event.iterationId()))
                claimFailureEvidence(event.iterationId(),event.workloadId(),event.scheduledAtEpochMs());
            boolean reserved;
            String slot;
            int window=reservationWindowByIteration.getOrDefault(event.iterationId(),0);
            if(event.success()) {
                reserved=successReservations.remove(event.iterationId());
                if(reserved) {
                    String reservationBucket=successReservationBucket.remove(event.iterationId());
                    if(reservationBucket!=null)successReservedByWorkload.merge(reservationBucket,-1,Integer::sum);
                    slot=releaseSlot(event.iterationId());
                } else slot=null;
            } else {
                reserved=failureReservations.remove(event.iterationId());
                if(reserved) {
                    String reservationBucket=failureReservationBucket.remove(event.iterationId());
                    if(reservationBucket!=null)failureReservedByWorkload.merge(reservationBucket,-1,Integer::sum);
                    slot=releaseSlot(event.iterationId());
                } else slot=null;
            }
            int current=event.success()?retainedSuccessCount:retainedFailureCount;
            if(!reserved||!policy.retain(event,current))return;
            if(!evidenceExists(event.evidence())||event.evidence()==null||event.evidence().caseLog()==null){countMissingEvidence(bucket);return;}
            retained.add(event);
            retainSlot(slot,window,event);
            if(event.success()){retainedSuccessCount++;successRetainedByWorkload.merge(bucket,1,Integer::sum);}
            else{retainedFailureCount++;failureRetainedByWorkload.merge(bucket,1,Integer::sum);}
            return;
        }
        boolean reserved = reservedEvidence.remove(event.iterationId());
        int window=reservationWindowByIteration.getOrDefault(event.iterationId(),0);
        String slot=reserved?releaseSlot(event.iterationId()):null;
        if (!reserved || retained.size() >= policy.maxSamples()) return;
        if (!policy.retain(event, retained.size())) return;
        if (!evidenceExists(event.evidence())){countMissingEvidence(bucket);return;}
        retained.add(event);
        retainSlot(slot,window,event);
        if(event.success()){retainedSuccessCount++;successRetainedByWorkload.merge(bucket,1,Integer::sum);}
        else{retainedFailureCount++;failureRetainedByWorkload.merge(bucket,1,Integer::sum);}
    }

    /** Independent per-workload caps: one workload's cap never charges or blocks another. */
    private void retainPerWorkload(LoadEvent event, String bucket) {
        // Outcome reclassification also handles callers using the generic reservation API.
        if(!event.success()&&successReservations.contains(event.iterationId()))
            claimFailureEvidence(event.iterationId(),event.workloadId(),event.scheduledAtEpochMs());
        boolean reserved;
        String reservationBucket;
        int window=reservationWindowByIteration.getOrDefault(event.iterationId(),0);
        String slot;
        if(event.success()) {
            reserved=successReservations.remove(event.iterationId());
            reservationBucket=successReservationBucket.remove(event.iterationId());
            if(reserved) {
                if(reservationBucket!=null)successReservedByWorkload.merge(reservationBucket,-1,Integer::sum);
                slot=releaseSlot(event.iterationId());
            } else slot=null;
        } else {
            reserved=failureReservations.remove(event.iterationId());
            reservationBucket=failureReservationBucket.remove(event.iterationId());
            if(reserved) {
                if(reservationBucket!=null)failureReservedByWorkload.merge(reservationBucket,-1,Integer::sum);
                slot=releaseSlot(event.iterationId());
            } else slot=null;
        }
        if(!reserved)return;
        String owner=reservationBucket==null?bucket:reservationBucket;
        int current=event.success()?successRetainedByWorkload.getOrDefault(owner,0):failureRetainedByWorkload.getOrDefault(owner,0);
        int cap=event.success()?policy.successCapFor(owner):policy.failureCapFor(owner);
        if(current>=cap)return;
        if(!evidenceExists(event.evidence())||event.evidence()==null||event.evidence().caseLog()==null){countMissingEvidence(owner);return;}
        retained.add(event);
        retainSlot(slot,window,event);
        if(event.success()){retainedSuccessCount++;successRetainedByWorkload.merge(owner,1,Integer::sum);}
        else{retainedFailureCount++;failureRetainedByWorkload.merge(owner,1,Integer::sum);}
    }
    private void countMissingEvidence(String bucket) {
        skippedMissingEvidenceCount++;
        skippedMissingByWorkload.merge(bucket,1L,Long::sum);
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
        result.put("completedSuccessCount",completedSuccessCount);
        result.put("hashQualifiedSuccessCount",hashQualifiedSuccessCount);
        result.put("samplingIneligibleSuccessCount",samplingIneligibleSuccessCount);
        result.put("reservedSuccessCount",successReservations.size());result.put("reservedFailureCount",failureReservations.size());
        // A rejected claim is still a real loss of evidence even when in-flight
        // reservations later release capacity before the report is written.
        boolean successExhausted=policy.success()!=LoadEvidencePolicy.Success.NONE&&skippedSuccessQuotaCount>0;
        boolean failureExhausted=policy.retainFailure()&&skippedFailureQuotaCount>0;
        boolean combinedExhausted=!policy.perWorkloadQuotas()&&!policy.splitQuotas()
                &&(skippedSuccessQuotaCount+skippedFailureQuotaCount+skippedUnclassifiedQuotaCount)>0;
        result.put("successQuotaExceeded",successExhausted);
        result.put("failureQuotaExceeded",failureExhausted);
        result.put("combinedQuotaExceeded",combinedExhausted);
        result.put("skippedSuccessQuotaCount",skippedSuccessQuotaCount);result.put("skippedFailureQuotaCount",skippedFailureQuotaCount);
        result.put("skippedUnclassifiedQuotaCount",skippedUnclassifiedQuotaCount);
        result.put("skippedMissingEvidenceCount",skippedMissingEvidenceCount);
        if(combinedExhausted)result.put("warning","Legacy combined evidence cap limited retention; aggregate metrics remain complete, but some eligible evidence was not retained.");
        else if(failureExhausted)result.put("warning",policy.perWorkloadQuotas()
                ? "One or more workload failure evidence quotas were reached; additional failures remain in aggregate metrics but full iteration evidence cannot be retained for those workloads."
                : "Failure evidence quota reached; additional failures remain in aggregate metrics but full iteration evidence cannot be retained.");
        else if(successExhausted)result.put("warning","Success evidence quota reached; aggregate metrics remain complete, but some hash-qualified successes were not retained.");
        Map<String,Object> byWorkload=new LinkedHashMap<String,Object>();
        for(String workload:allWorkloadBuckets()) {
            Map<String,Object> row=new LinkedHashMap<String,Object>();
            long workloadSkippedSuccess=skippedQuotaByWorkload.getOrDefault(workload,0L);
            long workloadSkippedFailure=skippedFailureQuotaByWorkload.getOrDefault(workload,0L);
            if(targetIdsByWorkload.containsKey(workload))row.put("targetId",targetIdsByWorkload.get(workload));
            row.put("completedSuccessCount",eligibleSuccessByWorkload.getOrDefault(workload,0L));
            row.put("hashQualifiedSuccessCount",hashQualifiedSuccessByWorkload.getOrDefault(workload,0L));
            row.put("eligibleSuccessCount",eligibleSuccessByWorkload.getOrDefault(workload,0L));
            row.put("samplingIneligibleSuccessCount",samplingIneligibleSuccessByWorkload.getOrDefault(workload,0L));
            row.put("retainedSuccessCount",successRetainedByWorkload.getOrDefault(workload,0));
            row.put("retainedFailureCount",failureRetainedByWorkload.getOrDefault(workload,0));
            row.put("skippedSuccessQuotaCount",workloadSkippedSuccess);
            row.put("skippedFailureQuotaCount",workloadSkippedFailure);
            row.put("skippedUnclassifiedQuotaCount",skippedUnclassifiedQuotaByWorkload.getOrDefault(workload,0L));
            row.put("skippedMissingEvidenceCount",skippedMissingByWorkload.getOrDefault(workload,0L));
            if(policy.perWorkloadQuotas()) {
                row.put("maxSuccessSamples",policy.successCapFor(workload));
                row.put("maxFailureSamples",policy.failureCapFor(workload));
                row.put("successQuotaExceeded",workloadSkippedSuccess>0L);
                row.put("failureQuotaExceeded",workloadSkippedFailure>0L);
            }
            byWorkload.put(DEFAULT_WORKLOAD.equals(workload)?"default":workload,row);
        }
        result.put("workloads",byWorkload);
        Map<String,Object> coverage=new LinkedHashMap<String,Object>();
        coverage.put("strategy",timelinesByWorkload.isEmpty()?"firstComeFallback":LoadEvidencePolicy.RETENTION_STRATEGY);
        coverage.put("populatedTimeWindowCount",timelinesByWorkload.isEmpty()?0:retainedByTimeWindow.size());
        Map<String,Long> windows=new LinkedHashMap<String,Long>();
        if(!timelinesByWorkload.isEmpty())
            for(Map.Entry<Integer,Long> entry:retainedByTimeWindow.entrySet())windows.put(String.valueOf(entry.getKey()),entry.getValue());
        coverage.put("retainedByTimeWindow",windows);
        coverage.put("retainedByPhase",new LinkedHashMap<String,Long>(retainedByPhase));
        coverage.put("appendOnly",true);
        result.put("retentionCoverage",coverage);
        return result;
    }

    private Set<String> allWorkloadBuckets() {
        LinkedHashSet<String> all=new LinkedHashSet<String>(workloads);
        all.addAll(successReservedByWorkload.keySet());
        all.addAll(successRetainedByWorkload.keySet());
        all.addAll(failureRetainedByWorkload.keySet());
        all.addAll(eligibleSuccessByWorkload.keySet());
        all.addAll(hashQualifiedSuccessByWorkload.keySet());
        all.addAll(skippedQuotaByWorkload.keySet());
        all.addAll(skippedFailureQuotaByWorkload.keySet());
        all.addAll(skippedUnclassifiedQuotaByWorkload.keySet());
        all.addAll(failureReservedByWorkload.keySet());
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
