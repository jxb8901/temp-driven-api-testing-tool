<!-- att-doc-status: current release=4.2.4 -->

# Jobs、event 及復原 API

## Server-sent events

使用 `Accept: text/event-stream` 連接 `/api/v1/jobs/{jobId}/events`。事件保存在 `dataDir/jobs/<jobId>/events.jsonl`，每項工作使用遞增數字 ID，事件名稱為 `status`、`progress`、`log`、`diagnostic` 或 `result`。

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

重新連線時傳送 `Last-Event-ID`，系統會重播 ID 較大的保留事件。較舊事件若已超出日誌保留上限，stream 會從仍保留的事件繼續。閒置時會傳送 `: keepalive` 註解，送出終端結果後會關閉。觀察端斷線不會取消工作。最多 32 個 observer 會透過 direct handoff 各自取得 stream thread；超出容量的請求會在 stream 啟動前回傳 HTTP 503。每個觀察端使用獨立的單一項目通知佇列。Worker 事件讀取器不會同步等待用戶端寫入。

## Job Results 與精簡 LOAD 終端資料

`GET /api/v1/jobs/{jobId}/result` 會回傳已授權的 Job 結果。LOAD 的 `result.summary.schemaVersion` 為 `att-load-terminal-result/v1`；其輸出契約見[設計說明](../../system-design/load-terminal-result.zh.md)及[機器可讀 DTO schema](../../../schemas/att-load-terminal-result-v1.schema.json)。RUN、DEBUG、VALIDATE 及其他 command 的結果格式維持不變。

精簡 summary 把執行結果和 performance verdict 分開，並提供有界 aggregate metrics、threshold／workload 預覽、主要診斷、細節完整度、metrics 涵蓋範圍及邏輯 archive 路徑。其 UTF-8 JSON 上限為 32 KiB。端到端測試會量度序列化後的終端 journal 與 SSE envelope，包括 Unicode 及 JSON escaping，確保各自低於 48 KiB；Journal event-data 上限仍為 64 KiB。Worker frame 上限維持 1 MiB。

ERROR 會在 `summary.diagnostics.primaryError` 保留 runtime 或 preparation 原因、有限長度診斷及失敗數量。`HTTP_RESPONSE_TOO_LARGE`、`MQ_RESPONSE_TOO_LARGE`、`MQ_POOL_TIMEOUT`、`LOAD_RESOURCE_PREPARATION_LIMIT` 及 `LOAD_PREPARATION_TIMEOUT` 仍可用於排查。Worker event truncation、Journal truncation 及 artifact 發布狀態只屬次要 metadata，不會取代主要原因、終端狀態或 exit code。較舊 Worker 送來的詳細 LOAD 結果會在新資料持久化及發布終端 event 前轉為 v1；已儲存的舊結果則會在讀取時投影為 v1。

`summary.archive.status` 和 `reportAvailability` 只表示初始狀態提示。最新 archive 狀態及核准 entries 以現有授權 artifact index route 為準。詳細 `load-summary.json`、`load-summary.yaml` 及 `report/index.html` 保留 `att-load-summary/v1.2`，只可透過已驗證的 Job artifact routes 取得。Metrics coverage 會回報 4,096 個一秒 bucket 的 rolling compatibility window，以及獨立的 `LoadWindowMetrics` bin 寬度與涵蓋範圍；不會暗示長時間執行仍保留每一秒的資料。

## 取消與復原

`DELETE /jobs/{jobId}` 會要求 Worker 優雅終止，等待最多 `gracefulStopMs`，必要時只強制終止該 Worker。工作記錄會保留。Server 重啟後不會自動重跑尚未完成的工作。

Artifact 路徑必須相對於工作輸出目錄。絕對路徑、目錄 traversal 及 symlink escape 均會遭拒。Server 不提供一般檔案系統瀏覽或 package 修改 API。
