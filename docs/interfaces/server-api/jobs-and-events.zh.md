<!-- att-doc-status: current release=4.2.6 -->

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

ERROR 和 INVALID 結果會在 `summary.diagnostics.primaryError` 保留有界 runtime、preparation 或 validation 原因。只有來源回報真實失敗數量時才輸出 failed count。`HTTP_RESPONSE_TOO_LARGE`、`MQ_RESPONSE_TOO_LARGE`、`MQ_POOL_TIMEOUT`、`LOAD_RESOURCE_PREPARATION_LIMIT` 及 `LOAD_PREPARATION_TIMEOUT` 仍可用於排查。Worker event truncation、Journal truncation 及 artifact 發布狀態只屬次要 metadata，不會取代主要原因、終端狀態或 exit code。過大的舊版 LOAD 結果會在 Worker 發出前投影，並在 Server 持久化／發布前再次投影；舊 stored result 則會在讀取時投影。

`summary.archive.status` 和 `reportAvailability` 只表示初始狀態提示。最新 archive 狀態及核准 entries 以現有授權 artifact index route 為準。`LoadReportWriter` 輸出的 archived `load-summary.json` 和 YAML 使用 `att-load-summary/v1.3`；大型 metric collection 使用相對路徑及版本化 `details/v1/manifest.json` sidecar，只可透過已驗證的 Job artifact routes 取得。直接 Engine／CLI `OperationResult` output 維持 legacy summary contract (schema under `schemas/history/`)。Metrics coverage 會回報 4,096 個一秒 bucket 的 rolling compatibility window，以及獨立的 `LoadWindowMetrics` bin 寬度與涵蓋範圍；不會暗示長時間執行仍保留每一秒的資料。

## 取消與復原

`DELETE /jobs/{jobId}` 會要求 Worker 優雅終止，等待最多 `gracefulStopMs`，必要時只強制終止該 Worker。工作記錄會保留。Server 重啟後不會自動重跑尚未完成的工作。

Artifact 路徑必須相對於工作輸出目錄。絕對路徑、目錄 traversal 及 symlink escape 均會遭拒。Server 不提供一般檔案系統瀏覽或 package 修改 API。

## Job replay 與可編輯 draft

只有來源 Job 對目前呼叫者授權，且安全保留 `att-job-execution/v1` 或 `/v2` 定義時才可 Replay。系統不會從 log／artifact 重建秘密、Worker transport data、host path 或不安全 source path。

v2 retained-spec 合約見 [att-job-execution/v2](../../../schemas/api/att-job-execution-v2.schema.json)；短期 editor draft response envelope 見 [att-job-replay-draft/v1](../../../schemas/api/att-job-replay-draft-v1.schema.json)。

| Method 與 path | 合約 |
| --- | --- |
| `GET /jobs/{jobId}/replay` | 對已授權呼叫者回傳安全可用性 metadata 與定義。 |
| `POST /jobs/{jobId}/replay-draft` | 接受 `{"sourcePolicy":"current"|"strict","overrides":{...}}`；回傳 `att-job-replay-draft/v1`、`draftRef`、`intentId`、原始／目前 revision 及已驗證 preview。省略 `sourcePolicy` 時沿用舊版 strict 行為。 |
| `GET /jobs/{jobId}/replay-draft/{draftRef}` | 回傳綁定 principal 的 editor sanitized prefill 定義及重新解析的 configuration；response 使用 `private, no-store`。 |
| `POST /jobs/{jobId}/rerun` | 接受 `{"intentId":"...","confirm":true,"sourcePolicy":"current"|"strict"}`，建立含 `rerunOfJobId` 的獨立 Job。相同 intent 重試時回傳已接受的 Job。 |

Overrides 上限為 32 KiB，並使用 typed allowlist。LOAD workload edits 綁定保留的 workload ID，且保留原有 `virtualUsers` 或 `arrivalRate` model；Server 拒絕隱式 model 轉換。RUN edits 可指定保留的 target 或 exact Case selection，並修改支援的 options／inputs。一般 Engine validator 仍是最終依據。Package-file-backed LOAD replay 使用已驗證的安全 package-relative source path，不受僅 inline draft 適用的 `server.inlineLoad` 上限影響。

`ATT-SERVER-REPLAY-REVISION-CONFLICT` 表示 strict mode 無法使用記錄的原始 revision；它不會還原缺失的歷史 source files。`ATT-SERVER-REPLAY-DRAFT-STALE` 表示 draft 驗證後 Package 已改變。`ATT-SERVER-REPLAY-DRAFT-EXPIRED` 表示短期 draft／intent 過期或 Server restart 後遺失。缺少安全輸入回傳 `ATT-SERVER-REPLAY-INPUT-MISSING`；已移除／無法解析的 Resource 回傳 `ATT-SERVER-REPLAY-RESOURCE-MISSING`；schema／field 錯誤回傳附安全 diagnostics 的 `ATT-SERVER-REPLAY-VALIDATION-FAILED`；容量回傳 HTTP 429。Draft reference 及 confirmation intent 綁定 principal，存在 Server 記憶體十分鐘。到期或重啟後，請重新開啟來源 Job 建立 draft。
