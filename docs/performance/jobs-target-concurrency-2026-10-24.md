# Historical target indexing during active execution

Measured for PR #289 / ATT 4.2.5 on 24 October 2026, on macOS ARM64, eight logical CPUs and Java 26.0.1. Raw samples and complete LOAD metrics are in [the JSONL evidence](baselines/jobs-target-concurrency-2026-10-24.jsonl).

## Reproduce

```sh
mvn -pl att-server -am -Dskip.installnodenpm -Dskip.npm \
  -Datt.skipFrontendUnitTests=true -Dtest=JobTargetConcurrencyBenchmarkTest \
  -Datt.jobs.concurrentBenchmark=true -Dsurefire.failIfNoSpecifiedTests=false test
```

The opt-in test is skipped in normal CI. Defaults: 10,000 historical Jobs and two rounds; override `att.jobs.concurrentBenchmark.size` and `att.jobs.concurrentBenchmark.rounds`. Each fresh disk-backed H2 fixture uses `WRITE_DELAY=0`, 98% single-target identities, 1% retained 2,000-Case RUN definitions and 1% retained 200-binding LOAD definitions (229,800 target bindings after indexing). Fixture creation is excluded from timings. Historical rows are terminal; no retained Worker processes or artifacts are fabricated.

Both modes execute real child Workers: a 20-second, 100 arrivals/second LOAD against a local log-only Template, plus six RUN target executions, each followed by a 500 ms pause. The same package, configuration, three Worker slots and Jobs API reader are used. The reader calls the actual `/jobs` servlet handler with grouped totals and a 100-row page, checks status/body, then waits 100 ms. API timings include filtering, counts, groups, JSON serialization and response parsing, but exclude network transport and authentication-filter cost. First meaningful event is the production persisted `firstMeaningfulEventMs` timing, not the admission/status event.

The production index executor is stopped before fixture seeding, then the measured indexer uses the same `backfillSearchTargetsBatch()` and 100 ms initial/fixed delay. Disabled mode performs no historical migration. This aligns migration start with admission instead of consuming history during setup; it does not change production scheduling. The benchmark continues empty pending checks after catching up until measurement ends; production stops its executor when caught up. Round order is disabled→indexing, then indexing→disabled. The harness joins its executor before closing ServerRuntime, whose normal shutdown fencing is also covered by migration/redeploy tests.

## Results

| Round / indexing | LOAD admission ms | RUN admission p95 ms | LOAD first event ms | RUN first event p95 ms | Jobs handler p95 ms (samples) | Successful TPS | LOAD p95 ms | Scheduler lag mean / max ms | Historical Jobs indexed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 / disabled | 611.88 | 10.59 | 1244.95 | 588.12 | 32.32 (162) | 99.94 | 1 | 2.96 / 28 | 0 |
| 1 / indexing | 617.51 | 6.40 | 1298.81 | 637.01 | 35.38 (165) | 99.94 | 1 | 3.02 / 43 | 10,000 |
| 2 / indexing | 495.52 | 10.34 | 1043.97 | 473.11 | 33.17 (166) | 99.92 | 1 | 3.01 / 19 | 10,000 |
| 2 / disabled | 482.43 | 3.16 | 1080.54 | 532.17 | 31.77 (160) | 99.96 | 1 | 2.97 / 24 | 0 |

All four LOAD runs scheduled, started and completed all 2,000 arrivals without drops, failures or runtime errors. Both indexing runs reached all 10,000 historical Jobs while execution was active; disabled runs made zero migration progress. Admission/first-event p95 over six RUN samples is simply the maximum and is not a reliable tail-latency SLA. The first LOAD admission includes cold inspection/source capture and differs from subsequent RUN admissions.

In this controlled workload, indexing did not materially reduce achieved TPS or change LOAD p95 (1 ms throughout). Jobs handler p95 was about 32–35 ms with indexing versus 31.77–32.32 ms with indexing disabled; scheduler lag remained around 3 ms mean, with variable isolated maxima. These observations do not justify adding an unmeasured pause or changing the existing scheduler in this PR. The fixed 100-Job transaction / 100 ms delay and crash-resume semantics remain in place.

This is a single-host, cache-warm, low-latency local target workload, not proof of isolation on a saturated generator, remote SUT, slower disk, 50k concurrent history, or continuous LOAD. Larger histories and high-utilization workloads can still require a measured work budget/backoff or pause policy; the benchmark exposes history size for that investigation. The earlier [10k/50k allocation/IO and substring-query costs](jobs-target-index-2026-10-24.md) remain applicable.
