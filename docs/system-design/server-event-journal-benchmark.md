# Server event-journal benchmark

`JobEventsPerformanceBenchmark` is an opt-in local benchmark for the Server journal path. It measures synthetic event append latency and throughput, first-event time, cursor replay, retained disk bytes, process CPU time, writer-thread allocated bytes (when the JVM exposes them), and sampled heap use with 1, 4, or 8 concurrent job journals. It writes machine and JVM details plus raw run samples to JSON.

For H2 metadata connection and status-poll measurements, see [Server JobStore benchmark](server-job-store-benchmark.md).

The benchmark does not start Workers, Tomcat, SSE sockets, or a system under test. Its measurements isolate journal behavior; they do not represent end-to-end job latency or Worker blocking. `writerAllocatedBytes` covers append-thread allocations and excludes compactor threads. `processCpuMs` includes append and compaction work. `heapUsedBeforeBytes` and `heapUsedAfterBytes` are point samples, not peak heap. No fixed regression threshold is enabled until a stable machine-specific baseline exists.

## Capture and compare a baseline

Run from the repository root. Maven runs only this explicitly selected benchmark class; ordinary test runs do not execute it.

```sh
mvn -B -ntp -pl att-server -am \
  -Dtest=JobEventsPerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.benchmark.output=target/server-events-before.json \
  -Datt.server.benchmark.events=1000,10000 \
  -Datt.server.benchmark.jobs=1,4,8 \
  -Datt.server.benchmark.warmups=2 \
  -Datt.server.benchmark.runs=5 test
```

Repeat on the same machine and JDK, changing the output file and pointing at the first report:

```sh
mvn -B -ntp -pl att-server -am \
  -Dtest=JobEventsPerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.benchmark.output=target/server-events-after.json \
  -Datt.server.benchmark.baseline=target/server-events-before.json \
  -Datt.server.benchmark.events=1000,10000 \
  -Datt.server.benchmark.jobs=1,4,8 \
  -Datt.server.benchmark.warmups=2 \
  -Datt.server.benchmark.runs=5 test
```

The report includes across-run p50/p95, coefficient of variation, first-event time, replay time, and each raw sample. The baseline comparison reports descriptive p50/p95 append-latency and throughput ratios for matching scenarios. Treat ratios as observations, not a CI gate; control machine load and repeat noisy samples before drawing conclusions.

The deterministic `JobEventsTest` suite separately covers retained IDs, cursor order and limits, concurrent job journals, slow-subscriber wake-up coalescing, and restart/replay while asynchronous compaction is pending.
