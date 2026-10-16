# 統一 RUN orchestration

公開契約與升級範例見[統一 RUN 與排程](../reference.zh/execution-modes/unified-run.md)。本頁說明維護者使用的 adapter 分工。

| 入口 | 驗證／綁定 | 執行 adapter | 結果格式 |
|---|---|---|---|
| `/drafts/run`，Case／批次 | `RunDefinition` → Worker `run-input` → `PackageValidator`；固定已選 Case IDs 與 revision | `DefaultAttService.run` | 現有 Run manifest、Case logs 與功能報表 |
| `/drafts/run`，Template/Flow/Tool | `RunDefinition` → 現有 Debug draft inspection；保留 typed sidecar 值與 keep markers | RUN Worker command 轉接 `DefaultAttService.debug` | 現有 Resource 執行報表與產物 |
| 舊 `/jobs/run`、CLI run/validate/report | 現有 Case 選取及 Excel/sidecar 契約 | 現有 functional runner | 不變 |
| 舊 `/drafts/debug`、`/jobs/debug`、CLI debug | 現有 Debug input schema 與安全 inspection | 現有 Debug adapter | 不變 |
| LOAD draft／舊 scenario | 現有 Load scenario normalization 與政策限制 | Load engine／workload coordinator | 現有 Load summary、telemetry 與 evidence |
| `/schedules` 與 `/load-schedules` 別名 | 分類 snapshot 與 trigger | 共用 occurrence claim → 分類 submit adapter | 共用 jobs 與專屬結果 |

`WorkerRequest.diagnosticMode` 可省略：省略保留舊事件行為；canonical RUN 一律設定。False 在 Worker 邊界抑制 DEBUG events，兩種值都保留一般 progress、diagnostics 與 result events。此選項不會改變 runner 呼叫次數或 Server disclosure policy。

`ServerRuntime` 管理有期限且綁定身分的 RUN drafts。公開請求不能提供 Worker inline payload 或 revision assertion，這些欄位由驗證產生。提交會再次驗證 revision，Worker 亦在執行前重查。Resource 排程儲存安全 keep markers 並拒絕提交的敏感覆寫。Case 排程固定已驗證 IDs，不會重新評估 selector 或歷史失敗。

`LoadScheduleScheduler` 仍是唯一排程器。現有 H2 occurrence transaction 先領取 cron 或 once occurrence，再提交 job。Once 領取後將 `nextRunAt` 設為 null，重新啟動不會重播。兩種類型共用 process、queue、overlap、retention、audit 與 cancellation。現有 JSON 排程預設為 LOAD；新增分類欄位採相容擴充，未知類型拒絕執行。沒有破壞式資料庫遷移。升級前須備份；降版須恢復舊資料庫，或先移除新 RUN／once 排程。

Server 在每次排程提交前重查本機帳戶是否啟用及是否仍為管理員。Container roles 由外部管理；排程管理請求檢查設定的 container role，管理員須在外部帳戶失去權限時暫停其委派排程。背景工作無法透過 Servlet API 查詢外部 container realm。

驗證涵蓋嚴格契約、真實 Worker SIT/UAT 目標及選取、排程領取／重新啟動、有驗證的非根目錄 WAR API，以及 Studio component／browser acceptance。現有 Engine 與 Server 測試保留專屬報表、取消／SSE、DST、misfire 與 overlap gate。
