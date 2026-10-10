# Issue 176 Resource Discovery and First-Event Baselines

These opt-in local captures provide reproducible performance context for the Issue 176 resource browser and Server event stream. They are descriptive measurements, not CI thresholds or release guarantees. Keep the raw JSON when comparing results, and compare only captures from similar machines, JDKs, packages, and settings.

## Package resource discovery

[`PackageResourceInspectionPerformanceBenchmark`](../../att-server/src/test/java/att/server/PackageResourceInspectionPerformanceBenchmark.java) measures `ServerRuntime.inspectResource` through the persistent bounded inspection Worker pool. It excludes HTTP and Tomcat overhead and does not connect to external databases or message brokers. The current generated fixture contains five Excel workbooks with 300 Cases each, 200 Templates, and one Tool.

The [4.1.0 large-package capture](baselines/issue-176-resource-discovery-large-4.1.0.json) used one warmup and five measured calls at page size 50. It ran on an 8-core Apple Silicon Mac, macOS 26.1, and OpenJDK 26.0.1. Cold index construction took 1,142.5 ms; the report includes warm first-page, page-N, detail, and Tool-source samples. With five measured calls, p95 is the highest observed sample and is not a stable distribution estimate. A [60-resource capture](baselines/issue-176-resource-discovery-4.1.0.json) from before the persistent cache remains as a historical reference only; it is not a representative large-package measurement.

| Scenario | p50 (ms) | p95 (ms) |
| --- | ---: | ---: | ---: |
| Case first page | 14.9 | 17.3 |
| Case page N | 9.3 | 10.7 |
| Case detail | 0.9 | 1.1 |
| Template first page | 7.4 | 10.2 |
| Template page N | 8.6 | 10.8 |
| Template detail | 1.3 | 1.5 |
| Tool source | 2.0 | 2.0 |

Run from the repository root. Maven compiles the required modules and runs this benchmark class:

```sh
mvn -B -ntp -pl att-server -am \
  -Dtest=PackageResourceInspectionPerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.inspection.benchmark.warmups=1 \
  -Datt.server.inspection.benchmark.runs=5 \
  -Datt.server.inspection.benchmark.pageSize=50 \
  -Datt.server.inspection.benchmark.revision="$(git rev-parse HEAD)" \
  -Datt.server.inspection.benchmark.output=target/issue-176-resource-discovery-large.json \
  test
```

The older 60-resource baseline can no longer be reproduced by this benchmark version because the benchmark now generates the larger package locally.

## Server Worker and SSE first-event timing

The [4.1.0 Server Worker and SSE capture](baselines/issue-176-server-worker-sse-4.1.0.json) uses the packaged 4.1.0 WAR (SHA-256 `f379876e8a99649808a8d3cd857e810779057d7657f6412d46a82ff35839f38c`). It ran one five-case job with one Worker, no warmup, and one measured run. A fast SSE observer received its first event in 443.9 ms. The benchmark then appended 1,000 synthetic progress events to the completed job and measured replay:

| Replay scenario | Events | First-event time (ms) | Total replay time (ms) | Rate (events/s) |
| --- | ---: | ---: | ---: | ---: |
| Parallel replay | 1,000 | 431.9 | 447.4 | 2,235 |
| `Last-Event-ID` resume | 1,000 | 439.2 | 450.0 | 2,222 |
| Replay after Server restart | 1,000 | 507.2 | 526.9 | 1,898 |

These single-run figures are illustrative only. The capture sets the slow-reader delay to zero, so it does not represent an intentionally throttled client. The raw report includes the worker, event-heavy command, replay, and restart measurements.

To reproduce the capture, build the WAR and then run the opt-in benchmark:

```sh
mvn -B -ntp -pl att-server -am -DskipTests package

mvn -B -ntp -pl att-server -am \
  -Dtest=ServerWorkerSsePerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.benchmark.workerSse=true \
  -Datt.server.benchmark.workerSse.cases=5 \
  -Datt.server.benchmark.workerSse.workers=1 \
  -Datt.server.benchmark.workerSse.warmups=0 \
  -Datt.server.benchmark.workerSse.runs=1 \
  -Datt.server.benchmark.workerSse.events=1000 \
  -Datt.server.benchmark.workerSse.slowDelayMs=0 \
  -Datt.server.benchmark.workerSse.soakSeconds=0 \
  -Datt.server.benchmark.workerSse.output=target/issue-176-server-worker-sse.json \
  -Datt.server.war=att-server/target/att-server-4.1.0.war \
  test
```

The benchmark starts local Worker processes and binds a loopback HTTP port. Results vary with JVM, hardware, filesystem, and host load; neither capture defines a portable pass/fail threshold.
