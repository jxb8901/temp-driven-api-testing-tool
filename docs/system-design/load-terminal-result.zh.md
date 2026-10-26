# 精簡 LOAD 終端結果

## 目的

Worker 的終端結果屬於控制平面資料。它必須保持精簡，才能符合 Worker frame、Server journal、持久化 Job 結果及 Studio 初始請求的容量要求。完整 metrics、workload 細節、thresholds 及 evidence 仍由 LOAD 報告 artifact 保存。

## 版本化契約

Server 代理的 LOAD 結果會在現有 `result.summary` 欄位使用
`att-load-terminal-result/v1`。外層 OperationResult 欄位（`executionId`、
`status`、`exitCode`、`durationMs` 及 `diagnostics`）維持原名稱。summary
包含以下互相獨立的部分：

| 欄位 | 內容 |
| --- | --- |
| `execution` | 執行結果、狀態、exit code、開始／結束時間及耗時。 |
| `performance` | Threshold performance verdict，與執行結果分開。 |
| `metrics` | 有界 run counters、完成及成功 throughput、可用的 latency percentiles。 |
| `thresholds` | 整體 verdict、總數／通過／失敗／未評估數量及有界預覽。 |
| `workloads` | 有界 workload 結果／計數摘要，以及總數和省略數量。 |
| `diagnostics` | 主要 runtime／preparation 原因、有界可採取行動的診斷及分類計數。 |
| `metricCoverage` | 保留的 bucket 數量／容量、bucket 寬度、實際涵蓋時間及 window-series 涵蓋時間／bin 寬度。 |
| `detailCompleteness` | 詳細報告資料是否未放入 inline 結果，以及有界省略計數。 |
| `archive` | 邏輯報告路徑及初始 archive availability 提示。當前狀態仍以 archive API 為準。 |

此 projection 不會包含主機絕對路徑、secret、request／response payload、
逐次 iteration 記錄、完整 time series 或無界 evidence。Artifact 路徑相對於
Job archive，例如 `load/<runId>/load-summary.json` 及
`load/<runId>/report/index.html`。

## 大小與優先規則

精簡 summary 的 UTF-8 JSON 確定性上限為 32 KiB。完整結果經過 journal／SSE
envelope 後，測試量度的 event bytes 目標低於 48 KiB，並包括 Unicode 及 JSON
escaping，為 `JobEvents.MAX_EVENT_DATA_BYTES`（64 KiB）預留空間。Worker frame
維持 1 MiB；兩項傳輸上限都不會更改。

Projection 為 workload 預覽、diagnostics、classifications、threshold
預覽及文字長度設定固定上限。預覽縮短時會回報總數及省略數量。ERROR 和
INVALID 的 root-cause diagnostic 會優先於次要傳輸或 archive 診斷，並且必定保留；
只有實際量度到失敗數量時才會輸出 failed count。
`HTTP_RESPONSE_TOO_LARGE`、`MQ_RESPONSE_TOO_LARGE`、`MQ_POOL_TIMEOUT` 等
runtime 分類，以及 `LOAD_RESOURCE_PREPARATION_LIMIT`、
`LOAD_PREPARATION_TIMEOUT` 等 preparation 原因都會保留為可採取行動的訊息。
Worker／Journal truncation、archive omission 或 archive failure 不可取代此原因、
改變執行結果或改變 exit code。

若舊版 LOAD `RESULT` 過大而無法放入 Worker frame，WorkerMain 會在丟棄細節前，
先從舊 metrics、failure diagnostics、preparation summary 或外層 diagnostics
抽取 allowlist 內的主要原因和有界診斷，再將
`ATT-WORKER-EVENT-TRUNCATED` 作為次要警告。Server 持久化 `resultJson` 及發布
終端 Journal／SSE 前會再次投影，保留 status 和 exit code。Preparation limit
及 deadline 使用 typed exception 分類；若沒有觀察到 helper failure，便不會虛構失敗數量。

若 LOAD Worker 在送出 `RESULT` 前退出，Server 仍會使用有界的 Worker-exit 或
protocol 診斷發布精簡終端結果。Performance verdict 會是 `NOT_EVALUATED`；若未
產生報告 artifact，report availability 會標示為省略。

執行結果、performance verdict、detail completeness 及 archive availability
使用分開欄位。例如 ERROR 可以同時有省略的詳細資料及 READY archive；執行
結果可以是 PASS，而 archive 仍在 PENDING 或已 FAILED。

## 詳細 artifact 與 metrics 涵蓋範圍

`LoadReportWriter` 會以新增欄位的 `att-load-summary/v1.3` schema 輸出 archived
`load-summary.json` 及 YAML。Aggregate counters 和 thresholds 仍然精確；大型
aggregate／per-workload metric collection 會移至 `details/v1/` sidecar，並由
`details/v1/manifest.json` 及 `att-load-detail-manifest/v1`、
`att-load-detail-reference/v1`、`att-load-detail-series/v1`、
`att-load-workload-detail/v1` 描述。每個 inline
preview 最多 32 項；sidecar chunk 最多 4 MiB，sidecar 集合上限為 100 MiB／9,000
個檔案，完整 report output 上限為 120 MiB。三個主要 report 檔案各自最多 16 MiB。
獨立 HTML 保留精確報告表格及 inline chart preview，可離線使用，並連結至完整資料的
sidecar manifest。若超出上限會明確令 report 建立失敗並清理部分檔案；compact archive
狀態會是 `FAILED`，執行 status 和 exit code 維持不變，不會靜默省略 series。Server
只會透過已驗證的 #236 ZIP archive 及現有授權 preview／download routes 發布所有檔案。
Archive 狀態分別回報為
`PENDING`、`ARCHIVING`、`READY`、`FAILED`，或受 export policy 省略。Artifact
不存在或被省略時，inline 執行結果仍然保留。

Inline 結果會說明 `LoadMetrics` 最多只保留 4,096 個一秒 bucket 的 rolling
window。`LoadWindowMetrics` 會回報實際涵蓋範圍、bin 寬度及有界 series 容量。
長時間執行不可被描述為保留了完整期間的每秒趨勢，因較早 bucket 可能已被覆寫。

Studio 載入 Job Details 時只讀取精簡 Job 結果，初次載入不會請求 artifact。操作人員
開啟 Results 後，才會透過授權 route 載入 summary preview，顯示有界 retained-evidence
記錄、workload counters，以及可開啟的 workload detail sidecar。開啟 Metrics 後，才會
載入授權 summary 和其引用的 metric-series sidecar。畫面會顯示 archive、loading、
省略及失敗狀態。只有開啟 Artifacts 分頁時才會載入完整 artifact 清單。

## 相容性

Worker protocol version 及 1 MiB frame 上限不變。Legacy `truncated` fallback
仍用於非 LOAD 結果及較舊 Worker。直接 Engine／CLI `OperationResult` 輸出仍保留舊版完整 summary contract；其 schema
定義仍存於 `schemas/history/`。`LoadReportWriter` 產生的 archived report 獨立使用
`att-load-summary/v1.3`。Server clients 應檢查
`result.summary.schemaVersion`：LOAD 終端資料改用
`att-load-terminal-result/v1`；詳細報告 consumer 應跟隨 archive 的
`detailManifestPath` 及相對 series references。RUN、DEBUG、VALIDATE 及其他非 LOAD
結果格式不變。

## 驗證 benchmark

使用以下指令執行確定性 compact-result benchmark/test：

```sh
mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest \
  -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test
```

測試會建構短時間及等同 30–60 分鐘的多 workload metrics snapshot，不會真的
長時間執行 LOAD，並比較 Worker 終端 bytes、已儲存結果 bytes、終端
journal／SSE bytes 及初始 Job Details transfer bytes。
測試亦會輸出 compact projection 周邊 JVM heap-used snapshot 的 best-effort 差值；
這不是 forced-GC allocation 或 Worker peak-heap 量度。Frontend 測試會以 DOM node 數量
限制初始 render 結構，並驗證開啟 Results 或 Metrics 前不會請求 archive。
