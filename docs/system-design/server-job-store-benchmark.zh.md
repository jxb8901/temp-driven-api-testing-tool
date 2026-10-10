# Server JobStore 基準測試

`JobStorePerformanceBenchmark` 量度 H2 connection open/close 成本，以及 status read/update/count path。並行情境使用 1、4、8 個不同 job row；每次 iteration 會執行 status read、job update 和 status count。`get` 和 `count` read 可與其他 read 及 write 並行；`update` 仍維持 synchronized。Latency 包含 H2 connection open/close 及 SQL 執行時間，update 亦包括 writer monitor 等候時間。基準測試不會啟動 Worker 或呼叫 HTTP API。

執行方式：

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

此命令使用較短的初始樣本；收集較穩定的本機基線時，可增加 iterations、warmups 及 runs。JSON 會記錄 JVM、作業系統、architecture、processor 數、heap 上限、file-store type、執行設定、單獨 connection open/close latency、各 operation 的 p50/p95、整體 operations/second、JVM 支援時的 process CPU、heap snapshot 和 H2 file bytes。一般 test suite 會以短小且 deterministic 的 smoke run 驗證 harness。更改 synchronization 或 connection ownership 前，請在相同 host、JDK、filesystem 和 H2 設定下比較；此基準沒有固定 regression threshold。
