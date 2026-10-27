# ATT Worker protocol v1

`att-worker` 是 ATT Server 使用的內部 process adapter。一般 Worker process 會從 stdin 讀取一個 JSON request，直接呼叫 typed `AttService` operation，然後以 JSON Lines 寫入 stdout 並退出。Inspection daemon 僅供讀取，會拒絕 execution command。選用的 [one-shot prewarm handshake](execution-prewarm.zh.md) 在同一個 execution request 前執行，不允許跨 Job 重用 JVM。

## Request

Request 是 JSON object。`protocolVersion` 必須為 `att-worker/v1`；不支援的版本會先回傳 `DIAGNOSTIC`，再回傳 `INVALID` `RESULT`。空白、格式錯誤、截斷或多份 JSON 文件都使用相同的終端 event 順序拒絕。`jobId` 是 Worker orchestration event 的識別碼，與 operation result 中 ATT 的 `executionId` 分開。`command` 可為 `run`、`debug`、`load`、`validate` 或 `snapshot`。`packageRoot` 必填，並會獨立於 process working directory 傳入 Engine。

常用選填欄位包括 `config`、`environment`、`outputDirectory` 和 `runId`。相對 config、suite、scenario 及 output 路徑均以指定的 package root 為基準。`target` 為 Debug 或不含 scenario 的 Load 提供 `type` 和 `id`。`suites`、`suiteDirectory`、`caseIds`、`tags`、`excludeTags` 及 `all` 用於選取 Run、Validate 或 Snapshot 的輸入。`load` 可包含 `users`、`arrivalRate`、`duration` 和 `maxConcurrent` 等 policy override；`overrides` 使用 ATT 接受的安全 value syntax 傳遞 typed input、argument 及 variable 指派。

## Events

每個 event 是一個 JSON object，後接 newline。Event 一定有 `type`，已知時亦包含 `jobId`。

| Type | 意義 |
| --- | --- |
| `STATUS` | Worker lifecycle，初始狀態為 `RUNNING`。 |
| `LOG` | 安全、結構化的執行日誌。 |
| `PROGRESS` | 機器可讀的進度觀察。 |
| `DIAGNOSTIC` | Request、配置、validation 或 runtime 問題。 |
| `RESULT` | Canonical operation result、狀態及 process exit code。 |

一般順序為 `STATUS`、零項或多項 `LOG`／`PROGRESS`，最後是 `RESULT`；無效或失敗的 request 可先發出 `DIAGNOSTIC`。非 LOAD result 保留 `executionId`、`status`、`exitCode`、duration、typed diagnostics、paths 及 summary。Server 代理的 LOAD result 則在 `result.summary` 使用版本化 `att-load-terminal-result/v1` projection；Engine 會從有界 metrics view 建立 DTO，不會先序列化 CLI 完整結果。主要 runtime/preparation 原因及實際觀察到的失敗數量優先於傳輸問題。完整 LOAD metrics、bucket series 及 retained evidence 仍在報告 artifact。直接 Engine／CLI 結果維持完整的舊版 summary contract，其 schema 定義保留於 `schemas/history/`；封存的 `LoadReportWriter` 輸出則使用新增的 `att-load-summary/v1.3` schema 及 `details/v1/` sidecar。

精簡 LOAD summary 的 UTF-8 JSON 上限為 32 KiB。量度 terminal Journal 和 SSE envelope 後，各自低於 48 KiB；Worker frame 1 MiB 上限和 Journal data 64 KiB 上限均未更改。Compatibility `metrics.buckets` 會回報 4,096 筆的一秒 rolling window；`LoadWindowMetrics` 另行回報 bin 寬度和涵蓋範圍。DTO 把 execution outcome、performance verdict、detail completeness 及 archive availability 分開。完整契約和相容性說明見[精簡 LOAD 終端結果](load-terminal-result.zh.md)。

### Execution frame budget

每個 execution event 的上限為 **1,048,576 UTF-8 bytes**，不含 LF 或 CRLF delimiter。容量依編碼 bytes 計算，包含 JSON escaping，而不是 Java UTF-16 字元數。Worker 會透過有界 capture 序列化，超出 frame 限制時不會寫出部分 JSON。普通超限 event 會保留有界識別／狀態／訊息欄位，並加入 `truncated`／`protocolLimit` metadata，內容只列出已知 event type、原始量度 bytes 和上限。

超限的終端 `RESULT` 會保留實際 status、exit code、execution ID、duration、最多四項有界原始診斷及最後的 Worker metrics，並明確標示 truncation。這個 legacy fallback 維持既有非 LOAD result shape，並支援較舊 Worker。超限的 legacy LOAD result 則由 WorkerMain 先從 bounded failure diagnostics、metrics、preparation data 或 outer diagnostics 擷取經 sanitization 的主要診斷，保留實際 status 和 exit code；`ATT-WORKER-EVENT-TRUNCATED` 只作次要訊息。新的 Server 代理 LOAD 會在 frame 序列化前先投影，正常詳細資料不會走這個 fallback。Sanitized 的完整 legacy result 會嘗試存入指定 output directory 的 `worker-protocol-result.json`，再依一般 archive publication policy 處理；policy 可能省略超限或不符合資格的項目。若檔案寫入失敗，會回報 `detailsUnavailable`，但不會取代執行結果。Typed preparation exception 提供分類代碼；分類不解析 exception message 內的字串，也不會虛構失敗數量。

Server 會以有界記憶體獨立讀取超限 frame，並以 UTF-8 `sizeBytes`、`limitBytes` 及 allowlisted `eventType`（或 `UNKNOWN`）記錄 `ATT-SERVER-WORKER-EVENT-TOO-LARGE`，不會在診斷內保留 payload。之後到達的有效 result 仍可完成 Job。若 Worker 未送出 result 就退出，該 protocol 診斷會優先於巢狀的 `ATT-SERVER-WORKER-EXITED`，並與終端 Job 一起保存。Inspection daemon response 使用獨立的 aggregate response cap；超限時會 fail，而不會回傳被截斷的 discovery data。

## Process 與輸出邊界

使用 `java -cp 'lib/*' att.worker.WorkerMain` 執行 packaged Worker。終止該 process 即取消該 Job 邊界。Engine resource 依正常 cleanup 路徑釋放；command-backed Tool process 則盡力清理。Java 8 Windows 上停止 Worker 不保證一併停止外部 Tool 的所有 descendants。Worker contract 不會只為保證 descendants cleanup 而加入強制 native dependency；Server 可另行採用自己的 process supervision 和 escalation policy。Package-authored file 由呼叫者負責，僅可位於 `packageRoot` 以下或明確指定的 output location。

Worker artifact 是為未來 Server integration 提供；目前支援的終端入口仍為 `att.sh` 和 `att.bat`。
