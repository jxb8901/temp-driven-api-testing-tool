# Load 模式

ATT 接受 att-load/v1.6 scenario。Scenario 有一個或多個 workload；每個 workload 使用單一固定 Template、Flow 或 Tool target，或使用 closed-user weighted target mix。Workload 配置 inputs、testdata policy、bootstrap vars 與 pacing。Root defaults 可供多個 workload 共用，workload-local 欄位會覆蓋它們。Scheduler 啟動前會驗證並預先 resolve 所有 target。

使用上一版 workload schema 的 descriptor 仍相容，載入時會 normalize 至現行 schema。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

## 定義 Load scenario

~~~yaml
schemaVersion: att-load/v1.6
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK, account: "@{generatedAccounts}", accountId: "@{generatedAccounts.id}"}
    testdata:
      generatedAccounts:
        scope: iteration
        selection: {strategy: sequential, exhaustion: recycle}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
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

## 將 closed-user work 分配到多個 target

Workload 可用 `mix` 代替 `target`，讓每位 closed user 的下一次 iteration 在預先 resolve 的 targets 之間選擇。每個 entry 包含唯一 `id`、正整數 `weight`，以及 Template、Flow 或 Tool `target`。Selector 由 run seed、workload ID、穩定 VU ID 和該 VU 的 iteration number 決定；切換 target 不會重設 VU 或 think-time random stream。Weight 表示選擇機率，短時間 run 不保證精確符合比例。

~~~yaml
schemaVersion: att-load/v1.6
seed: 73
workloads:
  - id: checkout
    inputs: {region: HK}
    mix:
      - id: browse
        weight: 60
        target: {type: template, id: BROWSE}
        inputs: {operation: browse}
      - id: purchase
        weight: 30
        target: {type: flow, id: PURCHASE}
        inputs: {operation: purchase}
      - id: report
        weight: 10
        target: {type: tool, id: REPORT, arguments: {format: csv}}
    load: {users: 20, duration: 1m}
~~~

Workload `inputs` 和 `vars` 提供預設值；entry 中相同名稱的 top-level key 會取代預設值。Tool arguments 必須放在 entry 的 `target.arguments`，Tool entry 不可宣告 `vars`。Mix 僅支援 closed model。每次 iteration 完成並經正常 think time 後才選下一個 target。`EXEC.LOAD.MIX_ID`、`TARGET_TYPE` 和 `TARGET_ID` 會在 execution ID expressions、testdata 及 bootstrap vars 評估前發布。切換 target 時，testdata selection state 仍按 workload/VU scope 共用。

Run summary 和 HTML report 會列出設定 weight、實際選擇次數及有界 per-entry metrics。Overall 和 workload percentiles 各自使用 aggregate latency collector 計算；ATT 不會平均各 entry percentile。Event 和 retained evidence 會包含所選的 `mixId` 及 target identity。

Target 支援 template、flow 或 tool；Tool target 可有 named arguments，但不能宣告 bootstrap vars。Workload inputs 會成為每個 iteration 的 EXEC.INPUT；Template/Flow 的 workload vars 則在每個開始的 iteration 建立全新的初始 EXEC.VARS tree。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

| Workload 欄位 | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input；不作為 bootstrap variables |
| `vars` | initial `EXEC.VARS` | Template/Flow typed expression tree；每個 execution 獨立評估 |
| `target.arguments` | Tool arguments | 僅供 Tool call；與 `EXEC.INPUT`、`EXEC.VARS` 分開 |

ATT 會在每個 iteration boundary 將 inputs map snapshot 一次，形成深層 immutable tree。複製 request metadata 時會重用這個 snapshot；Load adapter 會將巢狀 value 直接傳入該 iteration 的 `EXEC.INPUT` map，不再複製巢狀內容。開始 iteration 後，caller 修改來源 map 不會影響該 iteration；不同 iteration 也不會共用 input snapshot。

## 映射 inputs 並選擇 testdata

Environment profile 提供共享的 `testdata` descriptor list。Scenario 可選擇在頂層宣告 package-relative YAML `testdata` imports，形成僅供該次 Load 使用的 overlay。同 ID 的 Load-local descriptor 會完整取代 environment descriptor，不會合併 records 或 selection 設定。任一 layer 內的重複 ID 都會使 validation 失敗。

使用 `inputs` 將 `@{id}`、`@{id.path}` 或 scalar interpolation 映射到 `EXEC.INPUT`。同一 mapping 內每個 ID 只選一次 record；`scope` 決定何時重新選擇：`workload`、`user` 或 `iteration`。Load 預設為 `iteration`。`user` 只適用 closed-VU workload，在 `arrivalRate` 無效。Workload `testdata` map 只存 policy，不負責匯入 descriptor。其 `selection` 若有設定，會整份取代 descriptor selection policy。Policy 支援 `sequential`、`roundRobin`、有 seed 的 `random`，以及 exhaustion `error`（預設）、`recycle` 或 `stop`。耗盡後 `stop` 會平順停止該 workload。單筆 descriptor 不需要 selection policy。

Selection evidence 只記錄 testdata ID、來源 layer、record index、適用時的 generated sequence、scope、strategy 與 random seed，不包含 record 內容。Run/Debug 只載入 mapping 有引用的 ID；Load 會在 scheduler 啟動前驗證被引用的 ID 及 workload policy。

## 初始化 Template 和 Flow variables

Scheduler identity 及唯一 EXEC.ID/EXEC.OUTPUT_DIR 初始化完成後，ATT 會在 Template 或 Flow 開始前評估 workload 的 vars tree。完整 `${...}` reference 保留原生型別，混合文字會轉成字串，`#{...}` 使用一般 typed expression parser，巢狀 map/list 會遞迴評估。Vars 之間的依賴不受宣告順序影響；缺少變數或循環會在 target 開始前失敗。每個 iteration 都有獨立 map/list，因此併發 user/workload 不會共用可變值。第一次一般 `assign` 可取代 bootstrap variable。

Bootstrap expression 可使用已初始化的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定的 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata，以及 Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 不可用。只允許安全的純 built-in。Tool arguments 與 vars 是不同 contract。

## 選擇 closed-user 或 arrival-rate workload

Closed workload 使用正整數 load.users。每個穩定 virtual user 重複執行固定 target，並在下一次 iteration 前遵守 execution.thinkTime。thinkTime 可設 duration 或 {min, max} range。

Arrival-rate workload 使用 `load.arrivalRate`、正整數 `load.maxConcurrent` 與 `overloadPolicy: drop`。Scheduler 依絕對 due time 排程。超過 maxConcurrent 的 arrival 記為 generator drop；不排隊，也不算 SUT error。Arrival-rate 沒有持續 USER_ID，也不能配置 `thinkTime`。

Closed workload 會在 iteration 同步執行時保留配置的 virtual user 數量。Scheduler 和 worker-pool implementation 請看 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

## 設定 pacing 並估算 Resource pool 容量

穩定 arrival rate 下，可使用 Little's law 估算平均同時處理中的 request 數：

```text
平均 concurrency ≈ arrival rate（requests/second）× 平均 response time（seconds）
```

例如 HTTP 每秒 20 個 request、平均 response time 為 1.5 秒，約需 30 條 concurrent connection，才不會先受 client pool 限制。HTTP 預設 `pool.maxConnections: 50`、`pool.maxConnectionsPerRoute: 20`；請按 workload 需要調整兩者，並確保 per-route 值不大於總數。另為 latency 變化及其他 route 留出 headroom，再查看 `resources.http` 的 active/idle/waiting/peak observations。Pool capacity 是 generator-side 上限，不代表應向未確認承載能力的 service 發送該流量。

MQ request/reply 若每秒 10 個 request、平均 reply time 為 3 秒，整個 workload 約需 30 條 leased connection。`pool.maxSize` 應按每個**實體 MQ instance** 的預期 in-flight request 數 sizing，而非按 logical helper。單一 instance（或 request 固定送往一個 instance）約需 30 條再加 headroom。兩個平均分配的 instance 平均各需約 15 條；亦要考慮 selection skew 並確認實際分佈。`resources.mq` 按實體 instance 顯示 pool metrics，請逐一查看 waiting、timeout 及 response latency。`minIdle` 會在該實體 pool 首次被使用並建立時套用，不會在 Load 開始前建立或預熱 pool。若要避免 connection creation 影響 measured steady state，請配合 Load warm-up / first-use warm-up phase 使用 `minIdle`。負載變化時，請依觀察到的平均 latency 重新估算；tail latency 可用於 headroom 規劃，但不是公式中的平均值。

duration 必填。warmup、rampUp、rampDown 預設為零。Warm-up 送出真實 traffic，但不計入 measured threshold aggregates。可選 seed 使 closed-VU think-time randomization 可重複。

## 識別 iterations 並查看其 artifacts

每個開始的 iteration 在 Load run 內有唯一 `EXEC.ID`，並共用 `EXEC.RUN_ID`。省略 `execution.execIdFormat` 時 ATT 使用預設 run-scoped ID；有設定時，使用一般 `${...}` / `#{...}` engine 求值一次。Bootstrap vars 在 ID 發布後才評估，因此可讀取 `EXEC.ID` 與 `EXEC.OUTPUT_DIR`。Closed workload 可讀取 `EXEC.LOAD.USER_ID`；arrival-rate 沒有此欄位。欄位初始化時機與 function 限制見 [Check execution ID fields before use](#檢查-execution-id-可用欄位)。

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

Metrics-only iteration 雖有 `EXEC.ID`，但除非 operation 寫入 artifact 或 retention materialize evidence，否則不會建立 per-iteration execution directory。Iteration 執行期間 `EXEC.OUTPUT_DIR` 維持 `executions/<EXEC.ID>` 的 logical planned path。保留的 failure 或 sampled success 會將 evidence 複製到 `failures/<EXEC.ID>/` 或 `samples/<EXEC.ID>/`。Report/evidence summary 顯示 `EXEC.ID`；有保留 `case.log` 時提供連結。Optional Resource output formatting 會延遲至 iteration 被保留後；明確要求的 Tool evidence collector 仍會執行。

## 選擇要保留的 iteration evidence

`evidence.mode` 支援 `metrics`、`failures`、`samples`、`all`；預設為 `failures`。這些 mode 分別將 effective success/failure policy 預設為 `none/none`、`none/full`、`sample/full`、`full/full`。`evidence.success` 與 `evidence.failure` 可各自覆寫預設。`sampleRate` 與 `maxSamples` 限制保留 evidence。Dropped arrival 不建立 iteration evidence。

Case-log evidence 依 effective success/failure policy 及剩餘 retention capacity 保留。Failure policy 為 `full` 時，保留的 failure 會包含經 redaction 的 execution log；sampled success 和 full-success policy 會按 selection 保留 success log。超過 evidence capacity 的 iterations 不會保留 log。例如 `mode: metrics, failure: full` 會在仍有容量時保留 failure log；`mode: failures, failure: none` 會停用 failure log。Deferred capture 和 storage 的實作細節見 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、exact project-file String 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

## 查看 Load 結果並設定 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

Summary 會將 generator observation 與 SUT outcome 分開。`metrics.generator` 包含 sampled heap used/committed/maximum、觀察到的 peak live threads、GC count/time，以及 JVM 支援時的 process CPU。Sampling 限制為每 100 ms 至多一次，較短暫的 peak 可能錯過。`schedulerWakeups`、`submitLag*` 與 `workerQueueDepth*` 描述 scheduler pressure；arrival drops 與 SUT error 分開。`resources.http` 會按 HTTP helper 報告 active/idle/waiting 與觀察到的 peak connections，並與 DB、MQ、Render pool/plan diagnostics 並列；`resources.resourceMetricSamples` 顯示 rate-limited resource observations 的數量。`resources.executionIds` 顯示 custom execution-ID reservation 數量。Testdata mapping/selection counts 位於 `resources.generator.testdata`；解讀方式和 implementation limits 見 [Load Generator Telemetry](../../system-design/load-telemetry.zh.md)。

`latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate` 描述 run-level percentile estimate；精確 latency aggregates 仍保持精確。Time-series output 保留最新 4,096 個一秒 bucket。ATT 不會平均 workload percentile 來計算整體 percentile。

新增的 Load summary telemetry 欄位在 `att-load-summary/v1.1` 下屬 optional；目前 writer 會輸出這些欄位，加入 telemetry 前產生的 summary 仍然有效。

Sampling limits、metrics 解讀方式和 maintainer verification 請看 [Load Generator Telemetry](../../system-design/load-telemetry.zh.md)。

Root thresholds 只套用於 aggregate run；workload thresholds 只套用於個別 workload，不會從 root 繼承。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

## 了解 Load 執行期間的 payload 變化

Load workload 啟動前，ATT 會為每個可達的 Render payload glob 解析一次，並凍結該 run 匹配到的 UTF-8 source content。Run 進行中對 payload 的編輯、替換或新增 glob match 不影響該 run 的 iterations；下一次 Load run 會重新解析 package。一般 Run 與 Debug 每次 execution 使用新 plan，因此下一次 execution 會讀取編輯後的內容。

每個 iteration 都會以自己的 Context 評估 Context reference、built-in call 及 external call。`seq.next()`、clock/random function 及 external call 等 stateful call 會在每次 iteration 執行。Render 在記憶體回傳 String，因此將 `ACTIONS.<id>.output.result` 傳給下游 action 不會建立中間 Render file。只有 operation 明確需要檔案時才使用 `EXEC.OUTPUT_DIR`。

使用 `--profile` 時，`performance.json` 會記錄 `renderPlansCompiled`、`renderPlanCacheHits`、`renderPayloadResolutions`、`renderPayloadResolutionCacheHits`、`renderEvaluations`、`renderArtifactWrites` 與 `renderSourceBytes`。Load 如何準備 plans 並在 iterations 間重用 immutable source data，請看 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

使用 `--profile` 時，`performance.json` 的 `resources.execution` 會包含 `executionPlansCompiled`、`actionPlansCompiled` 與 `actionEvaluations`。Plan 和 resolver reuse 的 implementation 見 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

## 從 CLI 覆蓋 workload 設定

單一 workload 可用 --users、--arrival-rate、--warmup、--ramp-up、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Template/Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 `att-load/v1.6` policy-only descriptor，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence`、`seed` 及 Load-local `testdata` imports。明確 CLI pacing 會覆蓋 policy。`load --debug template|flow|tool <id>` 會將 sidecar 的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 policy 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy descriptor 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load/v1.6
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

可複製範例與欄位說明見 [examples/load/README.md](../../../examples/load/README.md)；schema migration 見 [Migration Notes](../appendices/migrations.md)。

## 檢查 execution ID 可用欄位

Load 使用 att-load/v1.6。設定 execution.execIdFormat 時，ATT 在每個 iteration initialization 使用一般 ${...} / #{...} engine 求值一次；省略時維持預設 run-scoped ID。Bootstrap vars 會在生成 ID 及 output path 發布後評估。

可用值有 EXEC.RUN_ID、timestamps、EXEC.INPUT、EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE、closed-only EXEC.LOAD.USER_ID，以及已建立的 META.PROJECT/SOURCE/TARGET/TEMPLATE。EXEC.ID 和 EXEC.OUTPUT_DIR 尚未可用，因為生成的 ID 決定 workspace。還沒有 Action 執行，所以 EXEC.ACTIONS 與 Flow/Tool/helper invocation META 缺席。

只允許 deterministic、side-effect-free built-ins。External Tool/DB/MQ/HTTP/SSH calls 及 stateful、random、clock、filesystem functions 會被拒絕。seq.next() 不允許也不需要。請使用穩定 identity：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 沒有 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

ID 必須非空、安全且為單一路徑 segment，並在 Load run 內唯一。重複或不安全值會在 target 開始前失敗；ATT 不會附加隱藏 suffix。


execIdFormat 只允許 deterministic、side-effect-free built-ins；external calls、seq.next()、random、clock 與 filesystem functions 都會被拒絕。Schema migration 見 [Migration Notes](../appendices/migrations.md)。
