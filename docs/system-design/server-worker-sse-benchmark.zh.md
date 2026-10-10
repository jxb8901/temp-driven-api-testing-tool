# Server Worker 與 SSE 效能基準

`ServerWorkerSsePerformanceBenchmark` 是可選的 ATT Server 端到端本機基準測試。它會在嵌入式 Tomcat 啟動已封裝的 WAR，透過 HTTP API 提交實際 Run、Debug 及 Load 工作，並記錄沒有 SSE 訂閱者、快速訂閱者及刻意放慢的訂閱者對 Worker 吞吐量的影響。測試亦會向已完成的工作追加合成 progress 事件，量度 SSE 重播、`Last-Event-ID` 恢復、慢速用戶端傳送，以及 Server 重啟後的重播。可選的持續工作 soak 測試預設關閉。

JSON 報告包括 WAR SHA-256、JVM 和主機資料、每個工作及 Server 指標樣本、事件數量、磁碟日誌大小、事件追加時間和速率、SSE 回應位元組數、首個事件時間、重播速率及命令總時間。測試套件和追加事件均為合成資料；被測目標是本機 ATT Server，不是外部服務。測試不設定回歸門檻。比較時請使用同一部機器及同一 JDK，並保留原始 JSON 報告。

## 擷取基線

請在 Repository 根目錄執行。先建立 WAR；一般 Maven 測試不會執行此可選基準測試。

```sh
mvn -B -ntp -pl att-server -am -DskipTests package

mvn -B -ntp -pl att-server -am \
  -Dtest=ServerWorkerSsePerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.benchmark.workerSse=true \
  -Datt.server.benchmark.workerSse.output=target/server-worker-sse.json \
  -Datt.server.war=att-server/target/att-server-4.0.1.war test
```

預設每個 Run 工作執行 300 個測試案例；測試矩陣使用 1、4、8 個並行 Worker、一次預熱、兩次正式測量，並為主要重播工作追加 10,000 個合成事件。慢速 SSE 用戶端每個事件等待 1 毫秒。可使用 `att.server.benchmark.workerSse.cases`、`.workers`、`.warmups`、`.runs`、`.events` 及 `.slowReaderDelayMs` 調整設定。將 `.soakSeconds` 設為正數即可啟用可選 soak 測試；預設關閉。報告會記錄所有設定。

基準測試會綁定本機 loopback HTTP port，並使用指定 WAR 啟動本機 Worker。比較時請使用相同 ATT 版本及來源的已封裝 WAR。結果會受 JVM、硬件、檔案系統及主機負載影響；Server 指標峰值是抽樣值，可能錯過非常短暫的高峰。Load 使用產生的本機 Template 工作負載，並不量度外部被測系統。

## 已記錄的 4.0.1 效能數據

[Issue 177 完整擷取結果](baselines/issue-177-server-worker-sse-4.0.1.json)使用每個 Run 工作 100 個案例、一次預熱、兩次正式測量、1/4/8 個並行 Worker，以及 10,000 個合成重播事件。測試機為 8 核心 Apple Silicon、macOS 26.1、OpenJDK 26.0.1。視訂閱者模式而定，批次總時間在 1 個 Worker 時為 2.85–3.29 秒、4 個 Worker 時為 7.64–9.31 秒、8 個 Worker 時為 14.54–17.84 秒。觀察到的批次吞吐量中位數分別為 0.316–0.327、0.449–0.521 及 0.469–0.534 工作/秒。每項設定只有兩次正式測量，數據只供描述參考。

三種重播並行設定的 10,000 事件 SSE 重播時間為 439–474 毫秒。使用 `Last-Event-ID` 恢復 1,000 個事件需時 436–649 毫秒；重啟後重播需時 531 毫秒。刻意每個事件延遲 1 毫秒的 10,000 事件慢速用戶端測試約需 15.6 秒。追加 10,000 個合成事件需時 321–327 毫秒，日誌大小約增至 3.01 MB。Debug 和 Load 均成功，分別用時 3.05 秒和 5.85 秒。

另有一組相同的 5 案例、單一 Worker、10,000 事件單次測量作前後比較：[緩衝重播改動前](baselines/issue-177-server-worker-sse-before-terminal-drain-4.0.1.json)的重播時間為 21.75 秒；[改動後](baselines/issue-177-server-worker-sse-4.0.1.json)為 516.8 毫秒。首個事件時間仍約為 0.46 秒。這是同一主機上的單次測量，不是發佈門檻。

兩次擷取均使用 OpenJDK 26.0.1；此主機沒有安裝 Java 17。測量 WAR 由 4.0.1 基礎 WAR 加上正在測試的已編譯 Server 和 Worker 類別組成；兩份 JSON 均記錄 WAR SHA-256 和類別雜湊。這些數據只供重現參考，並非通用效能目標或 Java 17 部署驗證。
