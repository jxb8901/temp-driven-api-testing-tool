# Server Worker and SSE benchmark

`ServerWorkerSsePerformanceBenchmark` is an opt-in, end-to-end local benchmark for the ATT Server. It starts the packaged WAR in embedded Tomcat, submits real Run, Debug, and Load jobs through the HTTP API, and records Worker throughput with no SSE observer, a fast observer, and a deliberately slow observer. It also appends synthetic progress events to completed jobs and measures SSE replay, `Last-Event-ID` resume, slow-consumer delivery, and replay after a Server restart. An optional repeated-job soak can be enabled.

The JSON report includes the WAR SHA-256, JVM and host details, each measured job and Server metric sample, event counts, journal bytes on disk, event append time and rate, SSE response bytes, first-event time, replay rate, and command wall time. The fixture and appended events are synthetic; the target is the local ATT Server, not an external service. The benchmark does not set regression thresholds. Compare captures from the same machine and JDK, and retain the raw JSON with any conclusions.

## Capture a baseline

Run from the repository root. Build the WAR first; ordinary Maven test runs do not execute this opt-in benchmark.

```sh
mvn -B -ntp -pl att-server -am -DskipTests package

mvn -B -ntp -pl att-server -am \
  -Dtest=ServerWorkerSsePerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.benchmark.workerSse=true \
  -Datt.server.benchmark.workerSse.output=target/server-worker-sse.json \
  -Datt.server.war=att-server/target/att-server-4.0.1.war test
```

By default, each Run job has 300 cases; the matrix uses 1, 4, and 8 concurrent Workers, one warmup and two measured runs, and 10,000 synthetic events per replay leader. A slow SSE reader waits 1 ms per event. Tune these settings with `att.server.benchmark.workerSse.cases`, `.workers`, `.warmups`, `.runs`, `.events`, and `.slowReaderDelayMs`. Set `.soakSeconds` to a positive number to enable the optional soak; it is disabled by default. The report records the selected settings.

The benchmark binds a loopback HTTP port and starts local Worker processes from the supplied WAR. Use a packaged WAR for the same ATT version and source under comparison. Results depend on JVM, hardware, filesystem, and host load; Server metric peaks are sampled, and very short peaks may be missed. Load uses the generated local template workload and does not measure an external system under test.

## Recorded 4.0.1 capture

The [full issue 177 capture](baselines/issue-177-server-worker-sse-4.0.1.json) used 100 cases per Run job, one warmup, two measured runs, 1/4/8 concurrent Workers, and 10,000 synthetic replay events. On an 8-core Apple Silicon Mac running macOS 26.1 and OpenJDK 26.0.1, batch wall time ranged from 2.85–3.29 s at one Worker, 7.64–9.31 s at four, and 14.54–17.84 s at eight, depending on observer mode. The median observed batch throughput ranged from 0.316–0.327, 0.449–0.521, and 0.469–0.534 jobs/s respectively. Each setting has two measured runs, so these figures are descriptive only.

Ten-thousand-event SSE replay took 439–474 ms across the three replay concurrency settings. A 1,000-event `Last-Event-ID` resume took 436–649 ms; restart replay took 531 ms. The 10,000-event slow-reader case took about 15.6 s with an intentional 1 ms delay per event. Appending 10,000 synthetic events took 321–327 ms and grew the journal to about 3.01 MB. Debug and Load completed successfully in 3.05 s and 5.85 s.

A focused one-run comparison with the same 5-case, one-worker, 10,000-event settings is also saved: [before the buffered-drain change](baselines/issue-177-server-worker-sse-before-terminal-drain-4.0.1.json) measured 21.75 s for replay; [after the change](baselines/issue-177-server-worker-sse-4.0.1.json) measured 516.8 ms. The first-event time stayed near 0.46 s. This is a same-host single-sample result, not a release threshold.

Both captures ran on OpenJDK 26.0.1; this host did not have Java 17 installed. The measured WAR was assembled from the 4.0.1 base WAR with the compiled Server and Worker classes under test overlaid; its exact SHA-256 and class hashes are recorded in each JSON. The capture is a reproducibility example, not a universal performance target or a Java 17 deployment validation.
