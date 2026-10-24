# Load scheduler design

本頁面向 ATT maintainer，說明 Load implementation。使用者可見的 scenario fields、pacing semantics、cancellation behavior、result schemas 和 metric interpretation 請看 [Load Reference](../reference.zh/execution-modes/load.md)。

## Workload scheduling

`ClosedVuScheduler` 將每個 virtual user 表示為 scheduler state，不會為閒置 user 永久佔用一條 thread。同步 iteration 遇到阻塞時，`LoadWorkerPool` 才按需建立 platform worker，最多達到所有 workload 配置 concurrency slots 的總和。這樣既保留 closed-user 的配置 concurrency，也避免預先建立大量閒置 thread。

Coordinated scheduler 會等待 arrival/VU deadline、phase boundary、completion event 或 cancellation，不會固定每毫秒 polling。Arrival-rate scheduling 直接按 phase start 與 rate 計算下一個到期時間；逾期 admission 不需搜尋之前所有 arrivals。超出容量而拒絕的 arrival 會記作 generator drop，不會排隊成為 SUT work。Arrival notification 會 coalesce，避免慢速 scheduler 累積無界的 wakeup queue。

每個已 admission 的 workload task 都有可取消 handle。Cancellation 先停止 admission，再 interrupt 該 workload 已 admission 的 tasks。Workload result snapshot 在 scheduler 收完 completion/finally event 後才凍結，因此 cancellation 期間完成的 iteration outcome 和 metrics 也會被計入。

## LOAD resource readiness barrier

Coordinator 會先為每個 target 建立 immutable execution plan，並凍結可到達的 Render source，然後才呼叫 `LoadRunResources.prepareResources()`。Resource inventory 會沿 primary 和巢狀 Tool plan 追蹤靜態 helper call 及 frozen payload expression，不會開啟 global configuration 中所有 helper。Dynamic helper reference 會記錄為 skipped，並在首次使用時維持 lazy。

Preparation 在 scheduler start event 前依序執行。DB pool 會保留不同且已驗證的 idle lease，數量至少為 `max(1, minIdle)`，不會執行 business SQL；MQ factory 建立 idle physical connection，但不開 queue 或傳送 message；HTTP preparation 建立 client/TLS state，不發 request。Run/process session budget 及單一 deadline 限制 startup。失敗會關閉本 run 已建立的 resource，並回傳 preparation error，不會 admission iteration。RUN/Debug 不呼叫此 LOAD barrier。

## Execution identity 與 artifact storage

ATT 為每個 started iteration 配置唯一的 engine-managed `EXEC.ID`：`<runId>-execution-<sequence>`。同一 Run 的所有 Workload、Target、Mix entry 及 phase 共用一個 thread-safe、單調遞增的 allocator。ID 在 bootstrap vars 與 Target action 前發布；`EXEC.RUN_ID` 保持為外層 Run identity。Collision tracking 不會建立 per-iteration 檔案或持續增長的 ID registry。

目前及歷史 Load Scenario、policy-only YAML、inline draft，以及已儲存的 Schedule／Worker snapshot，都拒絕 `execution.execIdFormat`，包括空值。請移除此欄位並遷移至 `att-load/v1.9`，檢查依賴 artifact path 的整合，改用獨立 input／variable 保存 business correlation。歷史 artifact 保留原路徑；歷史 schema 檔案仍供檢視。不含自訂 ID 的歷史資源會正規化至目前 contract。受影響的 immutable schedule 須透過一般 reviewed-draft 流程建立替代版本。

生成 ID 沿用安全 path segment grammar 及 240 字元上限；Run ID 必須預留 `-execution-` 與 signed-long sequence 空間。Sequence 耗盡後持續失敗，不會 wrap。每個 Run output namespace 只透過 `.execution-owner` 原子 claim 一次；第二個 owner 或 restart 必須選用新的外層 Run ID。Claim 由安全的 completed-Job artifact retention 隨 Run 目錄清理。舊 reservation 目錄只可由 completed-Job retention 清理，不能在舊 process 仍可能使用時刪除。Metrics-only evidence 保持 lazy；單一 Run metadata 檔不會建立 iteration workspace。

外層 `execution.runIdFormat`、`execution.debugIdFormat`、literal `--run-id` 與 `--debug-id` 行為不變。Diagnostic iteration／VU／Workload／Mix ID 與 scheduler event sequence 仍獨立於 `EXEC.ID`。分散式 Worker 必須使用互不重疊的 namespace 或共用 allocator；獨立 process-local counter 不能安全共用同一 Run namespace。

Iteration 的 `EXEC.OUTPUT_DIR` 是預先規劃的 logical path。Metrics-only work 本身不會建立持久 per-iteration directory。Resource-output formatting 及延遲寫入的 case-log evidence 會在 retention 選中 iteration 後才 materialize。資料被丟棄或複製到 retained evidence 後，temporary workspace 會被清理。

Failure capture 依有效 evidence policy 和剩餘 retention capacity 決定。符合 bounded failure capture 條件時，action 會把 redacted rolling tail 寫入 memory；只有 iteration 被保留時才寫入檔案。被抽中的 success 和 full-success policy 使用延遲寫入的完整日誌。Implementation 會在 iteration 開始前預留 evidence capacity，因此並行 reservation 可能在容量耗盡時保守地略過 capture。

## Telemetry storage

Load latency percentile 使用有界 primitive `long` reservoir。精確 count、mean、minimum 和 maximum 會另行追蹤。Run-level、phase 和每秒 time-series observation 使用各自的容量；time-series store 是 circular ring，保留最新 4,096 個一秒 bucket。

Generator 和 resource telemetry sampling 由 event 觸發，每 100 ms 至多記錄一次。Heap soak test 會在 warm-up 與最後 checkpoint 強制 GC，再檢查 retained heap growth，並確認 iteration-scope testdata state 不會隨 iteration 數量增加。報告字段和 maintainer verification procedure 見 [Load Generator Telemetry](load-telemetry.zh.md)。

## Plan 與 resolver reuse

Load 會在 scheduling 前 compile 可達的 Template/Flow actions、主要 Tool calls、argument expressions、guards、assertions、retry conditions、Render plans 和 testdata input mappings。Compiled structure 和 package source 會在 iterations 間共用；每個 iteration 仍會使用自己的 Context 評估。Load Render payload files 每個 run 只 resolve 和 freeze 一次；時間、random、sequence、Context 和 external values 仍會逐 iteration 評估。
