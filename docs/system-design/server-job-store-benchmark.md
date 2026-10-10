# Server JobStore benchmark

`JobStorePerformanceBenchmark` measures H2 connection open/close cost and the status read/update/count path. The concurrent workload uses 1, 4 and 8 independent job rows; each iteration performs a status read, a job update, and a status count. `get` and `count` reads can overlap with each other and writes; `update` remains synchronized. Latency includes H2 connection open/close and SQL execution, plus writer-monitor wait for updates. It does not launch Workers or call the HTTP API.

Run it with:

~~~sh
mvn -pl att-server -am \
  -Dtest=JobStorePerformanceBenchmark \
  -Datt.server.benchmark.jobStore=true \
  -Datt.server.benchmark.jobStore.iterations=2 \
  -Datt.server.benchmark.jobStore.warmups=0 \
  -Datt.server.benchmark.jobStore.runs=2 \
  -Datt.server.benchmark.jobStore.connectionOps=20 \
  -Datt.server.benchmark.jobStore.output=target/job-store-benchmark.json \
  test
~~~

The command uses a short initial sample. Increase iterations, warmups and runs when collecting a steadier local baseline. The JSON records JVM, operating system, architecture, processor count, heap limit, file-store type, run settings, open/close-only latency, per-operation p50/p95, aggregate operations per second, process CPU when available, heap snapshots and H2 file bytes. A short deterministic smoke always exercises the harness in the normal test suite. Compare runs on the same host, JDK, filesystem and H2 settings before changing synchronization or connection ownership; this benchmark sets no fixed regression threshold.
