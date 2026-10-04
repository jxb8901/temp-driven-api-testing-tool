# Load scheduler design

本頁面向 ATT maintainer，說明 Load implementation。使用者可見的 scenario fields、pacing semantics、cancellation behavior、result schemas 和 metric interpretation 請看 [Load Reference](../reference.zh/execution-modes/load.md)。

## Workload scheduling

`ClosedVuScheduler` 將每個 virtual user 表示為 scheduler state，不會為閒置 user 永久佔用一條 thread。同步 iteration 遇到阻塞時，`LoadWorkerPool` 才按需建立 platform worker，最多達到所有 workload 配置 concurrency slots 的總和。這樣既保留 closed-user 的配置 concurrency，也避免預先建立大量閒置 thread。

Coordinated scheduler 會等待 arrival/VU deadline、phase boundary、completion event 或 cancellation，不會固定每毫秒 polling。Arrival-rate scheduling 直接按 phase start 與 rate 計算下一個到期時間；逾期 admission 不需搜尋之前所有 arrivals。超出容量而拒絕的 arrival 會記作 generator drop，不會排隊成為 SUT work。Arrival notification 會 coalesce，避免慢速 scheduler 累積無界的 wakeup queue。

每個已 admission 的 workload task 都有可取消 handle。Cancellation 先停止 admission，再 interrupt 該 workload 已 admission 的 tasks。Workload result snapshot 在 scheduler 收完 completion/finally event 後才凍結，因此 cancellation 期間完成的 iteration outcome 和 metrics 也會被計入。

## Execution identity 與 artifact storage

每個 started iteration 都會在 bootstrap vars 評估前取得唯一 `EXEC.ID`。Default monotonic ID 不需要 collision registry。設定 `execution.execIdFormat` 時，程式會使用 disk-backed reservation marker，避免多個 process 佔用相同 custom ID；marker 限定於該 Load run。Public behavior 是 duplicate 或不符合 path safety 的 ID 會在 target 啟動前使 execution 失敗。

Iteration 的 `EXEC.OUTPUT_DIR` 是預先規劃的 logical path。Metrics-only work 本身不會建立持久 per-iteration directory。Resource-output formatting 及延遲寫入的 case-log evidence 會在 retention 選中 iteration 後才 materialize。資料被丟棄或複製到 retained evidence 後，temporary workspace 會被清理。

Failure capture 依有效 evidence policy 和剩餘 retention capacity 決定。符合 bounded failure capture 條件時，action 會把 redacted rolling tail 寫入 memory；只有 iteration 被保留時才寫入檔案。被抽中的 success 和 full-success policy 使用延遲寫入的完整日誌。Implementation 會在 iteration 開始前預留 evidence capacity，因此並行 reservation 可能在容量耗盡時保守地略過 capture。

## Telemetry storage

Load latency percentile 使用有界 primitive `long` reservoir。精確 count、mean、minimum 和 maximum 會另行追蹤。Run-level、phase 和每秒 time-series observation 使用各自的容量；time-series store 是 circular ring，保留最新 4,096 個一秒 bucket。

Generator 和 resource telemetry sampling 由 event 觸發，每 100 ms 至多記錄一次。Heap soak test 會在 warm-up 與最後 checkpoint 強制 GC，再檢查 retained heap growth，並確認 iteration-scope testdata state 不會隨 iteration 數量增加。報告字段和 maintainer verification procedure 見 [Load Generator Telemetry](load-telemetry.zh.md)。

## Plan 與 resolver reuse

Load 會在 scheduling 前 compile 可達的 Template/Flow actions、主要 Tool calls、argument expressions、guards、assertions、retry conditions、Render plans 和 testdata input mappings。Compiled structure 和 package source 會在 iterations 間共用；每個 iteration 仍會使用自己的 Context 評估。Load Render payload files 每個 run 只 resolve 和 freeze 一次；時間、random、sequence、Context 和 external values 仍會逐 iteration 評估。
