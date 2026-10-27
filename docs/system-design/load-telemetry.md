# Load generator telemetry

ATT reports generator observations separately from SUT outcome metrics. The bounded `metrics.generator` snapshot contains heap used/committed/max values, observed peak heap/thread counts, GC deltas, process CPU when supported, and a warm-up heap checkpoint. Samples are event-triggered and limited to one per 100 ms; brief peaks between samples can be missed. CPU, heap, GC, scheduler, and worker-queue values describe the ATT process and must not be interpreted as target saturation.

`schedulerWakeups`, submit-lag counts/mean/maximum, and worker-queue current/peak depth describe scheduler pressure. Arrival drops are capacity outcomes and remain separate from SUT errors. `resources.http` adds active/idle/waiting and observed peak pool connections by helper; DB, MQ and Render diagnostics remain alongside it. Render-plan compilation/cache counters and testdata mapping/selection counters are exposed when those features are used. Custom `EXEC.ID` collision reservations are disk-backed and report their retained marker count; default monotonic IDs allocate no per-ID registry entry.

Load latency percentiles use bounded primitive `long` reservoirs. The run-level report exposes `latencySampleCapacity`, `latencySampleCount`, `latencyObservationCount`, and `latencySampleRate`; exact count, mean, minimum, and maximum remain independent of sampling. Run-level and configured-window cohort reservoirs retain up to 4,096 samples each. Legacy phase and `metrics.buckets` reservoirs retain up to 256 latency samples per phase or one-second bucket.

The full-execution chart series at `metrics.windowMetrics.timeSeries.points` is bounded to 2,048 populated bins, initially 1,000 ms wide. When populated bins exceed capacity, ATT merges adjacent bins and doubles the width using power-of-two coarsening. It never evicts historical bins; long runs retain their span at lower time resolution. The measured and steady series at `metrics.windowMetrics.timeSeries.windowSeries.measured.points` and `metrics.windowMetrics.timeSeries.windowSeries.steady.points` are independently bounded to 2,048 bins each, align to configured window starts, and clip the final partial bucket at the window boundary. Their initial width is the smaller of 1,000 ms or the configured window duration, and they use the same coarsening and no-eviction policy. Each chart bin retains up to 128 latency samples, separately from the run-level, configured-window, and phase reservoirs. The compatibility `metrics.buckets` ring keeps the newest 4,096 one-second buckets and evicts older entries.

When the service has a private output directory, each LOAD metrics collector writes a versioned checkpoint every five minutes and after clean scheduler drain. A v2 checkpoint's SHA-256 covers the exact serialized envelope with the digest field omitted, including its schema, state, run/workload identity, sequence, capture time, finalization flag, and metrics payload; Java and the soak runner verify the same bytes. Per-workload checkpoint directories combine a sanitized workload ID with the full lowercase SHA-256 of the original ID, so IDs that differ only by case remain distinct on case-insensitive filesystems. During execution, `metrics.accounting.pendingAdmissionByScope` reports scheduled arrivals not yet accounted as started or dropped; finalization still requires exact arrival conservation. Temporary files are atomically renamed; only the newest two generations per aggregate or workload collector are retained, and each file is capped at 16 MiB. Checkpoint write failures are reported separately in `metrics.checkpoints`; they do not rewrite execution status. A checkpoint is recovery evidence and never claims that a partial or interrupted LOAD completed successfully. The completed report bundle uses `load-report-manifest.json` as its commit marker; the Server verifies every listed path, length, and checksum before accepting a canonical LOAD report for publication.

The accelerated nine-million-event metrics stress is opt-in:

~~~sh
mvn -pl att-engine -Datt.load.soak.accelerated=true -Dtest=LoadMetricsHighIterationSoakTest test
~~~

This synthetic collector stress checks exact event totals and bounded bucket/time-series retention. It does not replace the real 25-hour Engine/Worker acceptance run or verify completed throughput on its own.

Run the measured gate manually on a dedicated host with enough storage and at
least 28 hours of uninterrupted runtime. Build and extract ATT Local from the
exact checkout commit being measured, then run:

~~~sh
python3 tools/run_load_soak.py --runtime-root /path/to/att-local --output target/load-soak-evidence --commit-sha "$(git rev-parse HEAD)"
~~~

The gate configures 150 arrivals/s with 512 maximum in-flight iterations, uses
an ATT-local no-op Template, verifies every five-minute checkpoint interval,
checks arrival/completion conservation, and validates the committed JSON/YAML/
HTML report bundle. It writes acceptance.json, five-minute-trend.json, and the
complete run directory. A result is NOT YET VERIFIED until this real 25-hour
gate passes on recorded hardware.

`PayloadCache` is process-scoped and invalidates entries when a file's canonical path, size or modification time changes. It uses least-recently-used eviction, retains at most 512 entries and 16,777,216 UTF-16 code units in total, and skips a file larger than 2,097,152 code units. This bounds cached content to about 32 MiB of character data; the cap does not limit the caller's loaded string or a compiled plan's own snapshot.

For local evidence-log append measurements across physical, interactive-mirror, deferred and bounded modes, see [CaseExecutionLog benchmark](case-execution-log-benchmark.md).

Iteration-scoped testdata choices live only in the active input mapping's memo table. The run-scoped resolver map contains only user/workload choices, so its reported iteration cache size should remain zero as iteration count rises. The regular regression suite checks 20,000 synthetic iterations. For a slower retained-heap check, run:

~~~sh
mvn -Datt.load.soak=true -Datt.load.soak.durationMinutes=30 -Dtest=LoadTelemetrySoakTest test
~~~

The opt-in profile runs a synthetic Template through the production `ClosedVuScheduler` and `IterationExecutor` for 30–60 minutes. It forces GC at the warm-up and final checkpoints and requires final retained heap to remain within `max(16 MiB, 25%)` of the warm-up checkpoint. It also checks that iteration selection state remains empty, mapping evaluations continue, and generator and resource sampling stay rate-limited as iterations run. `resources.resourceMetricSamples` counts resource observations, limited to at most one per 100 ms; final resource snapshot maps are built for the report. Use the summary's exact cache and sample counts as the primary state-growth signals; retained heap is a coarse process-level cross-check.
