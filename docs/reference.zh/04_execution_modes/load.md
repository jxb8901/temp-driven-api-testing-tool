### 4.3 Load 模式

ATT 3.6.0 接受 att-load/v1.2 scenario。Scenario 有一個或多個 workload；每個 workload 固定一個 Template、Flow 或 Tool target，並配置自己的 pacing。Scheduler 啟動前會驗證 scenario 與所有 target。

#### Scenario 結構

~~~yaml
schemaVersion: att-load/v1.2
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK}
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Target 支援 template、flow 或 tool；Tool target 可有 named arguments。Workload inputs 會成為每個 iteration 的 EXEC.INPUT。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

#### Workload 模型

Closed workload 使用正整數 load.users。每個穩定 virtual user 重複執行固定 target，並在下一次 iteration 前遵守 execution.thinkTime。thinkTime 可設 duration 或 {min, max} range。

Arrival-rate workload 使用 load.arrivalRate、正整數 load.maxConcurrent 與 overloadPolicy: drop。Scheduler 依絕對 due time 排程。超過 maxConcurrent 的 arrival 記為 generator drop；不排隊，也不算 SUT error。Arrival-rate 沒有持續 USER_ID，也不能配置 thinkTime。

duration 必填。warmup、rampUp、rampDown 預設為零。Warm-up 送出真實 traffic，但不計入 measured threshold aggregates。可選 seed 使 closed-VU think-time randomization 可重複。

#### Load identity 與輸出路徑

每個開始的 iteration 在 Load run 內有唯一 EXEC.ID，並共用 EXEC.RUN_ID。省略 execution.execIdFormat 時 ATT 使用預設 run-scoped ID；有設定時，在 initialization 使用一般 ${...} / #{...} engine 求值一次。Closed workload 可讀 EXEC.LOAD.USER_ID；arrival-rate 沒有此欄位。欄位可用時機及 function 限制見[Runtime and Context Model](../03_runtime_context.md)。

產生的 ID 必須非空且是安全的 path segment。重複 ID 會在 target 啟動前失敗；ATT 不會靜默附加 suffix。

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── 寫入 EXEC.OUTPUT_DIR 的 action outputs
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

Metrics-only iteration 雖有 EXEC.ID，但除非 operation 寫入 artifact 或 retention decision 要求 materialize evidence，否則不會建立 per-iteration execution directory。iteration 執行期間 EXEC.OUTPUT_DIR 維持 executions/<EXEC.ID> 的 logical planned path。保留的 failure 或 sampled success 會將 evidence 複製到 failures/<EXEC.ID>/ 或 samples/<EXEC.ID>/。Report/evidence summary 顯示 EXEC.ID；有保留 case.log 時提供連結。Helper resource-output formatting 會延遲至 retention；明確要求的 Tool evidence collector 仍會執行，因為它是 author-requested diagnostic operation。

#### Evidence 與 resource output

evidence.mode 支援 metrics、failures、samples、all；預設 failures。sampleRate 與 maxSamples 限制保留 evidence。Dropped arrival 不建立 iteration evidence。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、Render DocumentValue 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

#### Report、metrics 與 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

Root thresholds 套用於 aggregate run；workload thresholds 套用於個別 workload。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

#### CLI 與範例

單一 workload 可用 --users、--arrival-rate、--warmup、--ramp-up、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
~~~

可複製範例與欄位說明維護於 [examples/load/README.md](../../../examples/load/README.md)。歷史 v1.0/v1.1 schema 已封存，不接受為 active version。請遷移至 v1.2 workloads syntax；見[Migrations](../appendices/migrations.md)。
