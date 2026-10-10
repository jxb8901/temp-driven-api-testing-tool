# CaseExecutionLog benchmark

`CaseExecutionLogPerformanceBenchmark` is an opt-in local benchmark for append cost under physical and deferred evidence modes. It covers per-append-flushed and bounded-batched physical full evidence, physical writes with a mirror callback, deferred full evidence followed by materialization, and bounded failure evidence. The mirror scenario uses a counting callback rather than terminal I/O, so terminal rendering must be measured separately when it matters.

Run without an observer uses the bounded-batched writer with a 64 Ki character buffer. Observed Run and Debug keep per-append flushes so the live log mirror remains prompt.

Run it with:

~~~sh
mvn -pl att-engine -am \
  -Dtest=CaseExecutionLogPerformanceBenchmark \
  -Datt.engine.benchmark.caseLog=true \
  -Datt.engine.benchmark.caseLog.iterations=5000 \
  -Datt.engine.benchmark.caseLog.warmups=1 \
  -Datt.engine.benchmark.caseLog.runs=3 \
  -Datt.engine.benchmark.caseLog.output=target/case-log-benchmark.json \
  test
~~~

The JSON report records JVM, operating system, architecture, processor count, heap limit, file-store type, run settings, per-append p50/p95, total append time, records per second, materialization/close time, output bytes, process CPU when available, and before/after heap snapshots. A short deterministic smoke run always exercises the harness in the normal test suite. Benchmark numbers are descriptive and have no fixed CI threshold; compare on the same host, JDK, filesystem, and payload settings before changing flush or buffering behavior.
