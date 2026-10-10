# Load generator telemetry

ATT reports generator observations separately from SUT outcome metrics. The bounded `metrics.generator` snapshot contains heap used/committed/max values, observed peak heap/thread counts, GC deltas, process CPU when supported, and a warm-up heap checkpoint. Samples are event-triggered and limited to one per 100 ms; brief peaks between samples can be missed. CPU, heap, GC, scheduler, and worker-queue values describe the ATT process and must not be interpreted as target saturation.

`schedulerWakeups`, submit-lag counts/mean/maximum, and worker-queue current/peak depth describe scheduler pressure. Arrival drops are capacity outcomes and remain separate from SUT errors. `resources.http` adds active/idle/waiting and observed peak pool connections by helper; DB, MQ and Render diagnostics remain alongside it. Render-plan compilation/cache counters and testdata mapping/selection counters are exposed when those features are used. Custom `EXEC.ID` collision reservations are disk-backed and report their retained marker count; default monotonic IDs allocate no per-ID registry entry.

Load latency percentiles use a bounded primitive `long` reservoir. The run-level report exposes `latencySampleCapacity`, `latencySampleCount`, `latencyObservationCount`, and `latencySampleRate`; exact count, mean, minimum, and maximum remain independent of sampling. Phase and one-second time-bucket latency samples have their own stated capacity. Time series retain the newest 4,096 one-second buckets in a circular ring; older buckets are evicted as new seconds arrive.

`PayloadCache` is process-scoped and invalidates entries when a file's canonical path, size or modification time changes. It uses least-recently-used eviction, retains at most 512 entries and 16,777,216 UTF-16 code units in total, and skips a file larger than 2,097,152 code units. This bounds cached content to about 32 MiB of character data; the cap does not limit the caller's loaded string or a compiled plan's own snapshot.

For local evidence-log append measurements across physical, interactive-mirror, deferred and bounded modes, see [CaseExecutionLog benchmark](case-execution-log-benchmark.md).

Iteration-scoped testdata choices live only in the active input mapping's memo table. The run-scoped resolver map contains only user/workload choices, so its reported iteration cache size should remain zero as iteration count rises. The regular regression suite checks 20,000 synthetic iterations. For a slower retained-heap check, run:

~~~sh
mvn -Datt.load.soak=true -Datt.load.soak.durationMinutes=30 -Dtest=LoadTelemetrySoakTest test
~~~

The opt-in profile runs a synthetic Template through the production `ClosedVuScheduler` and `IterationExecutor` for 30–60 minutes. It forces GC at the warm-up and final checkpoints and requires final retained heap to remain within `max(16 MiB, 25%)` of the warm-up checkpoint. It also checks that iteration selection state remains empty, mapping evaluations continue, and generator and resource sampling stay rate-limited as iterations run. `resources.resourceMetricSamples` counts resource observations, limited to at most one per 100 ms; final resource snapshot maps are built for the report. Use the summary's exact cache and sample counts as the primary state-growth signals; retained heap is a coarse process-level cross-check.
