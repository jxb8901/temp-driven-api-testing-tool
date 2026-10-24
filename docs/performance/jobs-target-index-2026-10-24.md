# Jobs target indexing and substring search benchmark

Measured for PR #289 on 24 October 2026. Baseline: `cc3fdee09dc8dc25e355261479b8a06e9dda8e18`.

## Reproduce

The opt-in `JobTargetBenchmarkTest` uses the actual disk-backed `JobStore`, H2 connection settings (`WRITE_DELAY=0`), snapshot parser, target writer and complete `queryJobs` response path. It is skipped in normal CI.

```sh
mvn -pl att-server -am -Dskip.installnodenpm -Dskip.npm \
  -Datt.skipFrontendUnitTests=true -Dtest=JobTargetBenchmarkTest \
  -Datt.jobs.benchmark=true -Dsurefire.failIfNoSpecifiedTests=false test
```

Sizes default to `10000,50000`; override with `-Datt.jobs.benchmark.sizes=10000`. Add `-Datt.jobs.benchmark.startupOnly=true` to isolate upgrade readiness, including creation of the previously absent migration cursor index, and verify browsing succeeds while search explicitly remains pending.

Each history contains 98% single Flow RUN jobs, 1% RUN jobs with 2,000 selected Cases, and 1% LOAD jobs with ten workloads and twenty Flow/Template bindings each. This creates 229,800 / 1,149,000 target rows. Snapshots contain 7,718,500 / 38,592,500 UTF-8 bytes (ASCII fixture IDs). Three repeated target groups model retained executions of the same definitions. Seeding is excluded from measurements. Jobs are terminal, in one package/environment, with deterministic IDs; this is a controlled history fixture, not production traffic.

Environment: macOS ARM64, eight logical CPUs, Java 26.0.1, the repository's H2 version. Measurements use the developer host and filesystem cache; no cold-device, production hardware or concurrency SLA is claimed. `wallMs` uses a monotonic clock; CPU is process CPU time, including runtime background work. Allocation is cumulative calling-thread allocation, **not peak live heap**. H2 `info.FILE_READ*` / `info.FILE_WRITE*` counters are cumulative since database reopen and describe database file operations, not physical-device IO. File sizes are allocation high-water marks before store close, not compacted live data.

## Upgrade and migration

The baseline constructor blocks readiness until every historical snapshot is indexed:

| History | Target rows | Blocking constructor | Process CPU | Thread allocation | Database before → after | H2 read / write bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10k Jobs | 229,800 | 40,655 ms | 44,439 ms | 11.05 GB | 18.37 → 634.88 MB | 282.96 / 616.51 MB |
| 50k Jobs | 1,149,000 | 215,839 ms | 200,461 ms | 73.03 GB | 132.92 → 4,573.50 MB | 4,168.22 / 4,537.52 MB |

These costs justify removing backfill from startup. The revised constructor installs schema/index metadata without reading private snapshots. A runtime-owned executor processes one transaction of at most 100 historical Jobs, waits 100 ms, then continues. The `(target_search_version, job_id)` index supports pending checks and batch discovery. Each transaction commits both target rows and its completion markers. Restart resumes unfinished rows; new submissions remain atomically indexed. The executor stops when caught up and is interrupted/joined before the database closes.

While any historical row remains pending, any nonempty **Target or Job ID** search returns HTTP 503 with `ATT-SERVER-TARGET-SEARCH-PENDING` and `Retry-After: 2`. No partial items/counts/groups are returned. Browsing without a search, including grouping and pagination, remains available. Studio clears stale results, shows the retry message and allows **Refresh jobs**. This deliberately gates the combined search field, including Job-ID substrings, until the entire historical index is ready.

With the migration cursor index initially absent, revised `JobStore` readiness measured:

| History | Constructor including index creation | Process CPU | Thread allocation |
| --- | ---: | ---: | ---: |
| 10k Jobs | 50.44 ms | 91.97 ms | 36.77 MB |
| 50k Jobs | 1,412.16 ms | 420.35 ms | 184.01 MB |

Both cases verified all Jobs remained browseable and target search returned the explicit pending exception before any backfill. This isolates constructor readiness and does not claim whole-Server startup in 50 ms / 1.4 seconds.

The final incremental pass (without row locking) measured:

| History | Batches | Processing wall time | Slowest batch | Process CPU | Thread allocation | Database before → after | H2 read / write bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10k Jobs | 100 | 11,520 ms | 991 ms | 25,154 ms | 6.01 GB | 18.79 → 663.68 MB | 59.72 / 644.90 MB |
| 50k Jobs | 500 | 94,791 ms | 1,772 ms | 110,848 ms | 96.41 GB | 135.29 → 5,661.32 MB | 6,261.20 / 5,767.49 MB |

The version index and resumable transactions have additional allocation/IO costs: the 50k final pass allocates and writes more than the synchronous baseline. Moving work off startup does not eliminate it. An intermediate locking variant wrote 21.75 GB at 50k Jobs; unnecessary `FOR UPDATE` was removed. Foreign keys and atomic batch rollback handle a concurrent deletion, with retry in the single migration executor. Batches are bounded by records/retained snapshot size, not a hard wall-time deadline.

The final pass also measured constructor times of 68 / 160 ms, illustrating warm-host variability relative to the separate readiness pass above; neither is a cold-start guarantee.

The benchmark drains batches without the runtime's 100 ms pauses, so its migration duration is processing time, not the elapsed time until a deployed runtime completes indexing. It measures `JobStore` construction, not other Server startup/recovery work. The raw evidence records every timing, allocation, row count and IO counter.

## Substring queries and plans

Four terms cover a late selected Case, a late mixed LOAD target, a broad single-target match and a miss: `case-1999`, `load-target-9-19`, `ordinary`, `absent-identifier`. Each query requests a grouped 100-Job page and verifies complete totals and bounded response size. Four samples include the first full response and three repeats. Before timing, `EXPLAIN ANALYZE` exercises count, sorted page and grouped count forms; the plans are retained in raw evidence. These plan executions warm the database; the first full-response sample is not a cold-disk sample.

| Baseline history | Case substring | LOAD substring | Broad match | Miss |
| --- | ---: | ---: | ---: | ---: |
| 10k Jobs | 86–166 ms | 71–95 ms | 80–92 ms | 59–64 ms |
| 50k Jobs | 1,252–1,641 ms | 917–1,090 ms | 1,041–1,158 ms | 887–938 ms |

Final implementation samples (same substring contract, with the new readiness gate):

| Final history | Case substring | LOAD substring | Broad match | Miss |
| --- | ---: | ---: | ---: | ---: |
| 10k Jobs | 144–399 ms | 101–136 ms | 114–121 ms | 69–73 ms |
| 50k Jobs | 1509–2505 ms | 1077–1722 ms | 1072–1234 ms | 862–1134 ms |

The plans confirm that `LOCATE` cannot narrow arbitrary substrings using `job_targets_identifier_idx(target_id,job_id)`. H2 uses the target relation's primary key with `job_id=jobs.job_id` for the correlated lookup. The outer history still scans 10,001 / 50,001 rows in representative count/page plans; a capped result page does not cap search work. The full production path also computes the current page's matching group totals and suggestions. Per-subquery `scanCount` annotations do not describe the sum of all correlated invocations.

Substring matching is preserved. The 50k measurements establish a material scaling cost rather than proving constant-time search. An exact/prefix-only mode would change the user contract and would not accelerate existing substring searches. This change therefore ships the measured baseline and nonblocking migration without introducing an unmeasured replacement query or removing constituent-target search. Larger histories, colder storage and concurrent operators can be slower; these results are not a throughput guarantee.

## Evidence

- [Synchronous baseline timings and query plans](baselines/jobs-target-index-2026-10-24-baseline.txt)
- [Upgrade readiness with the cursor index initially absent](baselines/jobs-target-index-2026-10-24-startup.txt)
- [Final incremental migration, timings and query plans](baselines/jobs-target-index-2026-10-24-incremental.txt)
