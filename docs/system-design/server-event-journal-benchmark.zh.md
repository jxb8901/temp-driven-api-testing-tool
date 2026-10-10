# Server 事件日誌效能基準

`JobEventsPerformanceBenchmark` 是可選的本機基準測試，用於量度 Server 日誌路徑。測試使用 1、4 或 8 個並行工作日誌，量度合成事件的追加延遲與吞吐量、首個事件時間、游標重播、保留檔案大小、程序 CPU 時間、Worker 執行緒已配置的位元組（JVM 支援時）及抽樣 Heap 使用量，並將機器、JVM 資訊與每次執行的原始數據寫入 JSON。

基準測試不會啟動 Worker、Tomcat、SSE socket 或被測系統。數據只反映日誌路徑，不代表端到端工作延遲或 Worker 阻塞情況。`writerAllocatedBytes` 只包括追加執行緒配置，不包括壓縮執行緒；`processCpuMs` 包括追加及壓縮工作。`heapUsedBeforeBytes` 和 `heapUsedAfterBytes` 是兩個時間點的抽樣，不是 Heap 峰值。在建立穩定的機器專屬基線之前，不設定固定回歸門檻。

## 擷取及比較基線

請在 Repository 根目錄執行。Maven 只會執行明確指定的基準測試類別；一般測試不會執行此基準。

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

在同一部機器及同一 JDK 重複測試，改用另一個輸出檔，並指定第一份報告：

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

報告會列出多次執行的追加延遲 p50/p95、變異係數、首個事件時間、重播時間及每次原始樣本。基線比較會列出相同測試情境的追加延遲 p50/p95 及吞吐量比例。這些比例只供觀察，不是 CI 門檻；分析前請控制機器負載，並重複測試波動較大的樣本。

獨立的確定性 `JobEventsTest` 測試會覆蓋保留事件 ID、游標順序及批次上限、並行工作日誌、慢速訂閱者的合併喚醒，以及非同步壓縮期間的 Server 重啟與事件重播。
