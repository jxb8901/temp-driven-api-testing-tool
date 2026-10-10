# Run and Load benchmark

`tools/benchmark_run_load_workloads.py` measures the 4.0.1 CLI's fresh-process Run path with a generated 20-case workbook and the Load generator against a loopback HTTP fixture. Each Load condition exercises a fixed arrival rate and one evidence policy (`metrics`, `failures`, or `samples`). The harness records process startup/first-output time, Run case counts and output size, Load throughput/latency/scheduler metrics, generator CPU/heap/GC/thread metrics, resource-pool observations, and raw repetitions as JSON.

The fixture returns a fixed JSON response and is not an external system under test. Its HTTP helper is configured with `maxConnections: 256` and `maxConnectionsPerRoute: 256`; those are benchmark overrides, not product defaults. The listening backlog is set on a server subclass before bind/listen and the report records the configured backlog plus an OS-based effective estimate. See [HTTPHelper pool defaults and sizing](../reference/resources/httphelper.md), [DBHelper pool defaults](../reference/resources/dbhelper.md), [MQHelper pool defaults](../reference/resources/mqhelper.md), and [Load generator telemetry and PayloadCache bounds](load-telemetry.md).

## Reproduce

Build the 4.0.1 package, extract it, and run the benchmark from the repository root:

```sh
env MAVEN_ARGS=-o ./build.sh
mkdir -p /tmp/att-4.0.1-runtime
tar -xzf dist/releases/att-4.0.1-local.tar.gz -C /tmp/att-4.0.1-runtime
python3 tools/benchmark_run_load_workloads.py \
  --runtime-root /tmp/att-4.0.1-runtime/att-4.0.1-local \
  --runtime-revision <source-commit> \
  --rates 100,500,1000 \
  --evidence-modes metrics,failures,samples \
  --duration 5s --load-warmup 1s --max-concurrent 256 --max-samples 10 \
  --runs 3 --warmups 1 --timeout-seconds 300 \
  --output target/run-load-benchmark-4.0.1.json
```

The runner uses Python's standard library, makes a temporary minimal ATT package, generates and snapshots the workbook, and starts a loopback-only HTTP server. It does not make external network requests. Every launcher invocation has a configurable deadline (`--timeout-seconds`, default 300); expiry terminates the process group/tree and retains a timeout record and output tail in the report. Timed-out samples are excluded from latency summaries. A Load run with runtime errors remains in the report with its exit status and metrics; failure-evidence runs also include up to three retained case-log examples. Do not discard errored runs when interpreting the results.

The checked-in [4.0.1 report](baselines/issue-177-run-load-4.0.1.json) records the package tree hash, source revision, operating system, hardware, logical CPU count, Python/JVM versions, benchmark settings, raw samples, per-helper HTTP pool snapshots and across-run summaries. Whole-process p95 uses nearest-rank over three measured samples, so it is the maximum of those three observations. Each Load run's p95/p99 comes from ATT's bounded latency reservoir. Treat these results as a local baseline, not a portable performance target.

The [2026-10-10 backlog follow-up](baselines/issue-177-run-load-high-rate-followup-2026-10-10.json) reran 500/s and 1,000/s with one warmup, two 2-second measured runs, and a configured backlog of 256 (OS-reported `somaxconn` 128; estimated effective backlog 128). All four measured runs passed with zero runtime errors. The local extracted package did not provide a source revision, so the report records its binary tree hash and `sourceRevision: unknown`; treat this short rerun as exploratory evidence rather than a replacement for the checked-in baseline.

## Recorded results for 4.0.1

The capture used source revision `b35bc653561b45d441cafda4537a8d66e09eb039` on a Mac14,2 MacBook Air with Apple M2, 8 GB RAM, macOS 26.1, eight logical CPUs, OpenJDK 26.0.1 and Python 3.13.1. It recorded the binary tree SHA-256 in the JSON report. No local Java 8 or 17 installation was available for this run.

The 20-case Run completed all cases in each of three measurements. Process-to-first-output was 299.838 ms p50 / 300.525 ms p95; total process wall time was 765.419 / 770.085 ms. These include launcher and JVM startup.

| Arrival rate | Evidence | PASS runs | Median achieved TPS | Median per-run p95 | Warmup runtime errors in the three measured runs |
| ---: | --- | ---: | ---: | ---: | --- |
| 100/s | metrics | 3/3 | 99.780 | 3 ms | 0, 0, 0 |
| 100/s | failures | 3/3 | 99.860 | 3 ms | 0, 0, 0 |
| 100/s | samples | 3/3 | 99.840 | 3 ms | 0, 0, 0 |
| 500/s | metrics | 3/3 | 499.301 | 1 ms | 0, 0, 0 |
| 500/s | failures | 2/3 | 499.700 | 2 ms | 0, 0, 6 |
| 500/s | samples | 1/3 | 499.700 | 2 ms | 4, 0, 1 |
| 1,000/s | metrics | 0/3 | 998.403 | 1 ms | 57, 14, 41 |
| 1,000/s | failures | 1/3 | 998.801 | 1 ms | 33, 31, 0 |
| 1,000/s | samples | 0/3 | 999.001 | 1 ms | 70, 49, 63 |

All measured phases reported zero runtime errors, SUT failures and dropped arrivals. Failure evidence from the errored runs identifies `HTTP_TIMEOUT` / `HTTP connect timed out` against the loopback fixture during warmup; ATT correctly returns overall `ERROR` for those runs. The HTTP pool was configured for 256 connections, observed peak active connections reached at most 179, and pool waiting remained zero. The data therefore shows the generator scheduling the requested rates during measurement, while the initial connection burst in this local setup was intermittent at 500/s and common at 1,000/s. It does not identify whether the connect timeouts came from the loopback fixture, host socket behavior, or HTTP client startup; do not use these samples as a clean high-rate pass or as a general ATT capacity claim.

The measured target is loopback only. These numbers do not measure remote HTTP latency, Server Workers, Tomcat, SSE delivery, or end-to-end job throughput; those require separate Server benchmarks.
