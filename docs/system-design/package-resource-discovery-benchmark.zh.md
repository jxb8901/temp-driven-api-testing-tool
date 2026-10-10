# Issue 176 資源探索及首個事件效能基線

以下可選本機擷取結果為 Issue 176 資源瀏覽器及 Server 事件串流提供可重現的效能參考。這些是描述性數據，不是 CI 門檻或發佈保證。比較結果時請保留原始 JSON，並只比較硬件、JDK、套件及設定相近的擷取結果。

## 套件資源探索

[`PackageResourceInspectionPerformanceBenchmark`](../../att-server/src/test/java/att/server/PackageResourceInspectionPerformanceBenchmark.java) 透過一次性 Worker 量度 `ServerRuntime.inspectResource`，不包括 HTTP 和 Tomcat 開銷。測試資料是 Repository 的測試套件，共有 60 項資源（26 個 Case、10 個 Template、3 個 Flow 及 21 個 Tool）。資源檢視路徑不會連接外部資料庫或訊息代理。

[4.1.0 擷取結果](baselines/issue-176-resource-discovery-4.1.0.json)每個情境先預熱一次，再量度三次；分頁大小為 100。測試機為 8 核心 Apple Silicon、macOS 26.1 及 OpenJDK 26.0.1。由於每個情境只量度三次，p95 是最高的觀察樣本，不是穩定的分佈估計。

| 篩選條件 | 資源數量 | p50（毫秒） | p95（毫秒） |
| --- | ---: | ---: | ---: |
| 全部 | 60 | 848.4 | 854.5 |
| Case | 26 | 836.4 | 862.3 |
| Template | 10 | 863.3 | 995.3 |
| Flow | 3 | 824.2 | 896.4 |
| Tool | 21 | 818.0 | 873.8 |

請在 Repository 根目錄執行以下指令。Maven 會編譯所需模組並只執行此基準測試類別：

```sh
mvn -B -ntp -pl att-server -am \
  -Dtest=PackageResourceInspectionPerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.inspection.benchmark.warmups=1 \
  -Datt.server.inspection.benchmark.runs=3 \
  -Datt.server.inspection.benchmark.pageSize=100 \
  -Datt.server.inspection.benchmark.revision="$(git rev-parse HEAD)" \
  -Datt.server.inspection.benchmark.output=target/issue-176-resource-discovery.json \
  test
```

## Server Worker 與 SSE 首個事件時間

[4.1.0 Server Worker 及 SSE 擷取結果](baselines/issue-176-server-worker-sse-4.1.0.json)使用已封裝的 4.1.0 WAR（SHA-256 為 `f379876e8a99649808a8d3cd857e810779057d7657f6412d46a82ff35839f38c`）。測試執行一個包含五個案例的工作、使用一個 Worker、不預熱，並正式量度一次。快速 SSE 訂閱者在 443.9 毫秒收到首個事件。基準測試再向已完成的工作追加 1,000 個合成 progress 事件，並量度重播：

| 重播情境 | 事件數量 | 首個事件時間（毫秒） | 重播總時間（毫秒） | 速率（事件/秒） |
| --- | ---: | ---: | ---: | ---: |
| 並行重播 | 1,000 | 431.9 | 447.4 | 2,235 |
| `Last-Event-ID` 恢復 | 1,000 | 439.2 | 450.0 | 2,222 |
| Server 重啟後重播 | 1,000 | 507.2 | 526.9 | 1,898 |

以上是單次測量，只供說明參考。擷取設定的慢速用戶端延遲為零，因此不代表刻意限速的用戶端。原始報告亦包含 Worker、事件量較大的命令、重播及重啟測量結果。

要重現擷取結果，請先建立 WAR，再執行可選基準測試：

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

基準測試會啟動本機 Worker 程序並綁定 loopback HTTP port。結果會受 JVM、硬件、檔案系統及主機負載影響；兩份擷取均未設定可通用的通過或失敗門檻。
