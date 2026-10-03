# Load Generator Telemetry

ATT 會將 generator observation 與 SUT outcome metrics 分開。Bounded `metrics.generator` snapshot 包含 heap used/committed/max、觀察到的 heap/thread peak、GC deltas、JVM 支援時的 process CPU，以及 warm-up heap checkpoint。Sampling 由 event 觸發，每 100 ms 至多一次；較短的 peak 可能落在兩次 sample 之間。CPU、heap、GC、scheduler 與 worker-queue 數值描述 ATT process，不代表 target 已飽和。

`schedulerWakeups`、submit-lag 次數/平均/最大值，以及 worker-queue current/peak depth 描述 scheduler pressure。Arrival drops 是容量結果，會與 SUT error 分開。`resources.http` 按 helper 提供 active/idle/waiting 及觀察到的 pool connection peak；DB、MQ、Render diagnostics 也會並列。使用相關功能時，Render-plan compilation/cache counters 及 testdata mapping/selection counters 會一同輸出。Custom `EXEC.ID` collision reservation 使用 disk marker，並顯示保留 marker 數量；default monotonic ID 不會建立每個 ID 的 registry entry。

Load latency percentile 使用有界 primitive `long` reservoir。Run-level report 提供 `latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate`；精確 count、mean、minimum、maximum 不受抽樣影響。Phase 及每秒 time bucket latency sample 各有明確容量。Time series 以 circular ring 保留最新 4,096 個一秒 bucket；新秒數到達時會淘汰較舊 bucket。

Iteration scope 的 testdata choice 只保留在目前 input mapping 的 memo table。Run-level resolver map 只含 user/workload choices，因此 iteration 數量增加時，其 iteration cache size 應保持為零。一般 regression suite 會檢查 20,000 次 synthetic iterations。若要執行較長時間的 retained-heap 檢查：

~~~sh
mvn -Datt.load.soak=true -Datt.load.soak.durationMinutes=30 -Dtest=LoadTelemetrySoakTest test
~~~

Opt-in profile 會讓 synthetic Template 經 production `ClosedVuScheduler` 及 `IterationExecutor` 執行 30–60 分鐘。在 warm-up 與最後 checkpoint 強制 GC，並要求 final retained heap 維持於 warm-up checkpoint 的 `max(16 MiB, 25%)` 範圍內。它亦會檢查 iteration selection state 保持空集合、mapping evaluations 持續增加，以及 generator 和 resource sampling 維持 rate-limited。`resources.resourceMetricSamples` 計算 resource observations，每 100 ms 至多一次；最終 resource snapshot maps 會在產生 report 時建立。請以 summary 的 cache 及 sample 精確數值作為主要 state-growth 指標；retained heap 是 process-level 粗略交叉檢查。
