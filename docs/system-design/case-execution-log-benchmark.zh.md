# CaseExecutionLog 基準測試

`CaseExecutionLogPerformanceBenchmark` 是可選的本機基準測試，用來量度實體及延遲 evidence mode 的 append 成本。它涵蓋每次 append 均 flush 和有界 batch 的實體完整 evidence、帶 mirror callback 的實體寫入、完整 deferred evidence 後再 materialize，以及有界 failure evidence。Mirror scenario 使用計數 callback，而非寫入 terminal；如需評估 terminal rendering，應另外量度。

Run 沒有 observer 時會使用 64 Ki character 的有界 batch writer。Observed Run 和 Debug 仍會逐次 append flush，讓 live log mirror 保持即時。

執行方式：

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

JSON report 會記錄 JVM、作業系統、architecture、processor 數、heap 上限、file-store type、執行設定、每次 append 的 p50/p95、append 總時間、records/second、materialize/close 時間、輸出 bytes、JVM 支援時的 process CPU，以及前後 heap snapshot。一般 test suite 亦會以短小且 deterministic 的 smoke run 驗證 harness。基準數值只供描述，沒有固定 CI threshold；更改 flush 或 buffering behavior 前，請在相同 host、JDK、filesystem 及 payload 設定下比較。
