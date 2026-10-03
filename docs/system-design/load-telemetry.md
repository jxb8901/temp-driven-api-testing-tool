# Load Generator Telemetry

ATT reports generator observations separately from SUT outcome metrics. The bounded `metrics.generator` snapshot contains heap used/committed/max values, observed peak heap/thread counts, GC deltas, process CPU when supported, and a warm-up heap checkpoint. Samples are event-triggered and limited to one per 100 ms; brief peaks between samples can be missed. CPU, heap, GC, scheduler, and worker-queue values describe the ATT process and must not be interpreted as target saturation.

`schedulerWakeups`, submit-lag counts/mean/maximum, and worker-queue current/peak depth describe scheduler pressure. Arrival drops are capacity outcomes and remain separate from SUT errors. `resources.http` adds active/idle/waiting and observed peak pool connections by helper; DB, MQ and Render diagnostics remain alongside it. Render-plan compilation/cache counters and testdata mapping/selection counters are exposed when those features are used. Custom `EXEC.ID` collision reservations are disk-backed and report their retained marker count; default monotonic IDs allocate no per-ID registry entry.

Iteration-scoped testdata choices live only in the active input mapping's memo table. The run-scoped resolver map contains only user/workload choices, so its reported iteration cache size should remain zero as iteration count rises. The regular regression suite checks 20,000 synthetic iterations. For a slower retained-heap check, run:

~~~sh
mvn -Datt.load.soak=true -Datt.load.soak.durationMinutes=30 -Dtest=LoadTelemetrySoakTest test
~~~

The opt-in profile runs a synthetic no-target selection loop for 30–60 minutes. It forces GC at the warm-up and final checkpoints and requires final retained heap to remain within `max(16 MiB, 25%)` of the warm-up checkpoint, while asserting that iteration selection state remains empty. Use the summary's exact cache counts as the primary state-growth signal; retained heap is a coarse process-level cross-check.
