<!-- att-doc-status: current release=4.2.5 -->

# 25-hour LOAD soak acceptance

Run the opt-in CLI Local benchmark to measure ATT's arrival generator and Engine against a deterministic local no-op Template. The run does not call an external system under test.

## Prepare the runtime

Build the Local distribution from the commit you want to measure, then extract it. Set `RUNTIME_ROOT` to the extracted directory that contains `att.sh` (or `att.bat`) and `lib/`.

The acceptance defaults to seed `177`, the `LOAD_SOAK_NOOP` Template, 150 configured arrivals per second, a 25-hour measured duration, maximum concurrency 512, no warm-up or ramps, and metrics-only evidence. The scenario requires overall completed and successful throughput of at least 100/s and zero dropped arrivals.

## Run the acceptance

From the repository root, run:

```sh
python3 tools/run_load_soak.py \
  --runtime-root "$RUNTIME_ROOT" \
  --output target/load-soak-evidence \
  --commit-sha "$(git rev-parse HEAD)"
```

Keep the host awake until the process exits. The script rejects durations below 25 hours and configured arrival rates at or below 100/s. Do not reuse an output directory that already contains the selected run ID.

## Verify the result

Treat the gate as passed only when the command exits successfully and `acceptance.json` records `result: PASS`. The gate checks exact scheduled arrivals, completed throughput across the run and qualified five-minute intervals, zero drops and failures, verified accounting and checkpoints, a complete report manifest, a `MET` report performance verdict, and dense resource samples with bounded RSS, heap, thread, and file-descriptor growth.

Resource samples must cover at least 90% of the qualified duration, with at least 95% sample coverage for each required metric. The gate compares median values from the first and last 30-minute endpoint windows. It allows at most two additional open file descriptors; thread growth is limited to the greater of two threads or 5% of the baseline. RSS growth is limited to the greater of 64 MiB or the smaller of 256 MiB and 10% of the baseline. Heap-used growth is limited to 16–64 MiB, based on 1% of the configured maximum heap.

The evidence directory contains `run-metadata.json`, `acceptance.json`, `five-minute-trend.json`, `jvm-resource-trend.json`, `process-resource-samples.json`, logs, and the captured report and checkpoints under `load/<RUN_ID>/`. The metadata records the commit, seed, target, workload controls, operating system, processor, memory, and Java runtime.

An interrupted run records `PARTIAL` when a verified checkpoint is available, or `REPORT_UNAVAILABLE` when none is available. Neither state is a pass. Preserve the evidence directory for diagnosis.

This long measurement uses CLI Local and does not create a Server Job ZIP. Server Job publication, archive integrity, and retrieval are covered by the Server LOAD integration tests in the regular CI suite.
