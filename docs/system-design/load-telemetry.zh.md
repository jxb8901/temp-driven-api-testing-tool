# Load generator telemetry

ATT 會將 generator observation 與 SUT outcome metrics 分開。Bounded `metrics.generator` snapshot 包含 heap used/committed/max、觀察到的 heap/thread peak、GC deltas、JVM 支援時的 process CPU，以及 warm-up heap checkpoint。Sampling 由 event 觸發，每 100 ms 至多一次；較短的 peak 可能落在兩次 sample 之間。CPU、heap、GC、scheduler 與 worker-queue 數值描述 ATT process，不代表 target 已飽和。

`schedulerWakeups`、submit-lag 次數/平均/最大值，以及 worker-queue current/peak depth 描述 scheduler pressure。Arrival drops 是容量結果，會與 SUT error 分開。`resources.http` 按 helper 提供 active/idle/waiting 及觀察到的 pool connection peak；DB、MQ、Render diagnostics 也會並列。使用相關功能時，Render-plan compilation/cache counters 及 testdata mapping/selection counters 會一同輸出。Custom `EXEC.ID` collision reservation 使用 disk marker，並顯示保留 marker 數量；default monotonic ID 不會建立每個 ID 的 registry entry。

Load latency percentile 使用有界 primitive `long` reservoir。Run-level report 提供 `latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate`；精確 count、mean、minimum、maximum 不受抽樣影響。Run-level 和每個 configured-window cohort reservoir 各最多保留 4,096 個 sample；legacy phase 與 `metrics.buckets` reservoir 每個 phase 或一秒 bucket 最多保留 256 個 latency sample。

`metrics.windowMetrics.timeSeries.points` 全執行 chart series 最多保留 2,048 個有資料的 bin，初始寬度為 1,000 ms。資料 bin 超過容量時，ATT 會合併相鄰 bin，並使用 power-of-two coarsening 將寬度加倍。它不會淘汰歷史 bin；長時間 run 會以較低的時間解析度保留資料。Measured 和 steady series `metrics.windowMetrics.timeSeries.windowSeries.measured.points`、`metrics.windowMetrics.timeSeries.windowSeries.steady.points` 各自最多 2,048 個 bin，按 configured window 起點對齊，並在 window boundary 截短最後一個 partial bucket；初始寬度為 1,000 ms 與 window duration 兩者中較小者，且沿用相同的 coarsening 和不淘汰歷史資料政策。每個 chart bin 最多保留 128 個 latency sample，與 run-level、configured-window 和 phase reservoir 分開計算。Compatibility `metrics.buckets` ring 保留最新 4,096 個一秒 bucket，並淘汰較舊項目。

Service 有 private output directory 時，每個 LOAD metrics collector 每五分鐘及 scheduler 正常 drain 後寫入 versioned checkpoint。v2 checkpoint 的 SHA-256 會涵蓋移除 digest 欄位後的完整序列化 envelope，包括 schema、state、run/workload identity、sequence、capture time、finalization flag 及 metrics payload；Java 與 soak runner 會驗證相同 bytes。每個 workload checkpoint directory 由 sanitized workload ID 及原始 ID 的完整 lowercase SHA-256 組成，因此只在大小寫上不同的 ID，在大小寫不敏感的 filesystem 上也會使用不同目錄。執行期間，`metrics.accounting.pendingAdmissionByScope` 會記錄尚未計入 started 或 dropped 的 scheduled arrivals；finalization 仍要求 arrival conservation 完全相等。資料先寫入 temporary file 再 atomic rename；每個 aggregate 或 workload collector 僅保留最新兩代，每個檔案上限 16 MiB。寫入錯誤會獨立顯示在 `metrics.checkpoints`，不會改寫執行狀態。Checkpoint 是 recovery evidence，不會聲稱中斷或 partial LOAD 已成功完成。完成的 report bundle 以最後寫入的 `load-report-manifest.json` 作 commit marker；Server 發布 canonical LOAD report 前會驗證所有列出的 path、長度及 checksum。

九百萬事件 accelerated metrics stress 預設不執行：

~~~sh
mvn -pl att-engine -Datt.load.soak.accelerated=true -Dtest=LoadMetricsHighIterationSoakTest test
~~~

此 synthetic collector stress 會檢查精確事件總數及有界 bucket/time-series retention；它不能代替 25 小時 Engine/Worker acceptance run，也不能單獨證明 completed throughput。

請在專用主機上手動執行 measured gate，預留至少 28 小時不間斷執行時間及足夠儲存空間。請從本次測量的相同 checkout commit 建置並解壓 ATT Local，再執行：

~~~sh
python3 tools/run_load_soak.py --runtime-root /path/to/att-local --output target/load-soak-evidence --commit-sha "$(git rev-parse HEAD)"
~~~

Gate 使用 150 arrivals/s、512 個最大 in-flight iterations 和 ATT-local no-op Template，驗證每個五分鐘 checkpoint interval、arrival/completion conservation，以及已 commit 的 JSON/YAML/HTML report bundle。它會輸出 `acceptance.json`、`five-minute-trend.json` 和完整 run directory。只有這個 real 25 小時 gate 通過並記錄硬體資料後，結果才算 VERIFIED。

`PayloadCache` 為 process scope，並會在 file 的 canonical path、size 或 modification time 改變時使 entry 失效。它採 least-recently-used eviction，最多保留 512 個 entries、共 16,777,216 個 UTF-16 code units；超過 2,097,152 code units 的單一 file 不會被 cache。這將 cached character data 限制在約 32 MiB；此上限不限制呼叫端載入的 String 或 compiled plan 自己的 snapshot。

如需比較 physical、interactive-mirror、deferred 及 bounded mode 的本機 evidence-log append 數據，請看 [CaseExecutionLog 基準測試](case-execution-log-benchmark.zh.md)。

Iteration scope 的 testdata choice 只保留在目前 input mapping 的 memo table。Run-level resolver map 只含 user/workload choices，因此 iteration 數量增加時，其 iteration cache size 應保持為零。一般 regression suite 會檢查 20,000 次 synthetic iterations。若要執行較長時間的 retained-heap 檢查：

~~~sh
mvn -Datt.load.soak=true -Datt.load.soak.durationMinutes=30 -Dtest=LoadTelemetrySoakTest test
~~~

Opt-in profile 會讓 synthetic Template 經 production `ClosedVuScheduler` 及 `IterationExecutor` 執行 30–60 分鐘。在 warm-up 與最後 checkpoint 強制 GC，並要求 final retained heap 維持於 warm-up checkpoint 的 `max(16 MiB, 25%)` 範圍內。它亦會檢查 iteration selection state 保持空集合、mapping evaluations 持續增加，以及 generator 和 resource sampling 維持 rate-limited。`resources.resourceMetricSamples` 計算 resource observations，每 100 ms 至多一次；最終 resource snapshot maps 會在產生 report 時建立。請以 summary 的 cache 及 sample 精確數值作為主要 state-growth 指標；retained heap 是 process-level 粗略交叉檢查。
