### 6.3 Load 模式

ATT 接受 att-load/v1.5 scenario。Scenario 有一個或多個 workload；每個 workload 固定一個 Template、Flow 或 Tool target，並配置自己的 inputs、testdata policy、bootstrap vars 與 pacing。Root defaults 可供多個 workload 共用，workload-local 欄位會覆蓋它們。Scheduler 啟動前會驗證 scenario 與所有 target。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

#### Scenario 結構

~~~yaml
schemaVersion: att-load/v1.5
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

Target 支援 template、flow 或 tool；Tool target 可有 named arguments，但不能宣告 bootstrap vars。Workload inputs 會成為每個 iteration 的 EXEC.INPUT；Template/Flow 的 workload vars 則在每個開始的 iteration 建立全新的初始 EXEC.VARS tree。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

| Workload 欄位 | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input；不作為 bootstrap variables |
| `vars` | initial `EXEC.VARS` | Template/Flow typed expression tree；每個 execution 獨立評估 |
| `target.arguments` | Tool arguments | 僅供 Tool call；與 `EXEC.INPUT`、`EXEC.VARS` 分開 |

ATT 會在每個 iteration boundary 將 inputs map snapshot 一次，形成深層 immutable tree。複製 request metadata 時會重用這個 snapshot；Load adapter 會將巢狀 value 直接傳入該 iteration 的 `EXEC.INPUT` map，不再複製巢狀內容。開始 iteration 後，caller 修改來源 map 不會影響該 iteration；不同 iteration 也不會共用 input snapshot。

#### Testdata Imports 與 Workload Scope

Environment profile 提供共享的 `testdata` descriptor list。Scenario 可選擇在頂層宣告 package-relative YAML `testdata` imports，形成僅供該次 Load 使用的 overlay。同 ID 的 Load-local descriptor 會完整取代 environment descriptor，不會合併 records 或 selection 設定。任一 layer 內的重複 ID 都會使 validation 失敗。

使用 `inputs` 將 `@{id}`、`@{id.path}` 或 scalar interpolation 映射到 `EXEC.INPUT`。同一 mapping 內每個 ID 只選一次 record；`scope` 決定何時重新選擇：`workload`、`user` 或 `iteration`。Load 預設為 `iteration`。`user` 只適用 closed-VU workload，在 `arrivalRate` 無效。Workload `testdata` map 只存 policy，不負責匯入 descriptor。其 `selection` 若有設定，會整份取代 descriptor selection policy。Policy 支援 `sequential`、`roundRobin`、有 seed 的 `random`，以及 exhaustion `error`（預設）、`recycle` 或 `stop`。耗盡後 `stop` 會平順停止該 workload。單筆 descriptor 不需要 selection policy。

Selection evidence 只記錄 testdata ID、來源 layer、record index、適用時的 generated sequence、scope、strategy 與 random seed，不包含 record 內容。Run/Debug 只載入 mapping 有引用的 ID；Load 會在 scheduler 啟動前驗證被引用的 ID 及 workload policy。

#### 每次執行的 bootstrap vars

Scheduler identity 及唯一 EXEC.ID/EXEC.OUTPUT_DIR 初始化完成後，ATT 會在 Template 或 Flow 開始前評估 workload 的 vars tree。完整 `${...}` reference 保留原生型別，混合文字會轉成字串，`#{...}` 使用一般 typed expression parser，巢狀 map/list 會遞迴評估。Vars 之間的依賴不受宣告順序影響；缺少變數或循環會在 target 開始前失敗。每個 iteration 都有獨立 map/list，因此併發 user/workload 不會共用可變值。第一次一般 `assign` 可取代 bootstrap variable。

Bootstrap expression 可使用已初始化的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定的 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata，以及 Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 不可用。只允許安全的純 built-in。Tool arguments 與 vars 是不同 contract。

#### Workload 模型

Closed workload 使用正整數 load.users。每個穩定 virtual user 重複執行固定 target，並在下一次 iteration 前遵守 execution.thinkTime。thinkTime 可設 duration 或 {min, max} range。

Arrival-rate workload 使用 load.arrivalRate、正整數 load.maxConcurrent 與 overloadPolicy: drop。Scheduler 依絕對 due time 排程。超過 maxConcurrent 的 arrival 記為 generator drop；不排隊，也不算 SUT error。Arrival-rate 沒有持續 USER_ID，也不能配置 thinkTime。

duration 必填。warmup、rampUp、rampDown 預設為零。Warm-up 送出真實 traffic，但不計入 measured threshold aggregates。可選 seed 使 closed-VU think-time randomization 可重複。

#### Load identity 與輸出路徑

每個開始的 iteration 在 Load run 內有唯一 EXEC.ID，並共用 EXEC.RUN_ID。省略 execution.execIdFormat 時 ATT 使用預設 run-scoped ID；有設定時，在 initialization 使用一般 ${...} / #{...} engine 求值一次。Bootstrap vars 在 ID 發布後才評估，因此可讀 EXEC.ID 與 EXEC.OUTPUT_DIR。Closed workload 可讀 EXEC.LOAD.USER_ID；arrival-rate 沒有此欄位。欄位可用時機及 function 限制見[Runtime and Context Model](../03_runtime_context.md)。

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

`evidence.mode` 支援 `metrics`、`failures`、`samples`、`all`；預設為 `failures`。這些 mode 分別將 effective success/failure policy 預設為 `none/none`、`none/full`、`sample/full`、`full/full`。`evidence.success` 與 `evidence.failure` 可各自覆寫預設。`sampleRate` 與 `maxSamples` 限制保留 evidence。Dropped arrival 不建立 iteration evidence。

Case log capture 會在每個 iteration 開始前依 effective success/failure policy 及剩餘 retention capacity 決定。Effective failure policy 為 `full` 且仍有可用的 `maxSamples` slot 時，failure（包括 `samples` 中未抽中的 success）會在記憶體保留經 redaction 的 rolling tail，最多 65,536 字元；只有 failure claim 到 retention slot 後才會物化。`maxSamples` 為零或已用盡、因此不能再保留 failure 時，會略過 per-action serialization 與 buffering。In-flight iteration 已預留的 slot 可能令 scheduler 保守地略過其他 iteration 的 capture。保留的 log 會在 truncation marker 後保留最新 action 與 runtime failure detail。已抽中的 success 與保留的 full-success evidence 使用完整 deferred case log；若已預留 success slot 的 iteration 最後失敗，系統仍會保留其完整 deferred log，並使用該 slot 保存 failure evidence。例如 `mode: metrics, failure: full` 會在仍有容量時啟用 bounded failure capture；`mode: failures, failure: none` 則會略過 failure capture。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、exact project-file String 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

#### Report、metrics 與 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

Root thresholds 只套用於 aggregate run；workload thresholds 只套用於個別 workload，不會從 root 繼承。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

#### Render plan 與 payload snapshot

Load workload scheduler 啟動前，ATT 會為每個可達的 Render payload glob 解析一次，並凍結該 run 匹配到的 UTF-8 source content 與已編譯的 reference/expression 結構。Run 進行中對 payload 的編輯、替換或新增 glob match 不影響該 run 的 iterations；下一次 Load run 會重新解析 package。一般 Run 與 Debug 每次 execution 使用新 plan，因此下一次 execution 會讀取編輯後的內容。

每個 iteration 都會以自己的 Context 評估 Context reference、built-in call 及 external call。ATT 重用解析後的結構與 source text，不重用 dynamic rendered result；`seq.next()`、clock/random function 及 external call 等 stateful call 仍會在每次 iteration 執行。Render 在記憶體回傳 String，因此將 `ACTIONS.<id>.output.result` 傳給下游 action 不會建立中間 Render file。只有 operation 明確需要檔案時才使用 `EXEC.OUTPUT_DIR`。

使用 `--profile` 時，`performance.json` 會記錄 `renderPlansCompiled`、`renderPlanCacheHits`、`renderPayloadResolutions`、`renderPayloadResolutionCacheHits`、`renderEvaluations`、`renderArtifactWrites` 與 `renderSourceBytes`。這些有界 run totals 分別顯示 source-plan 重用與每次 iteration 的 evaluation；`renderArtifactWrites` 為零，因為 Render 只回傳 String，不會自行寫入 artifact。

Load 啟動時也會編譯所選 Template/Flow action sequence、主要 Tool call 與 argument expression、`runWhen`、assertion 及 `retry.when`。每個 iteration 會使用自己的 Context 評估此 immutable plan。Testdata input mapping 同樣會在 target validation 時編譯；descriptor 與有效 workload policy 只準備一次，record selection 與 Context value 仍會依 iteration 評估。Run summary 的 `resources.execution` 包含 `executionPlansCompiled`、`actionPlansCompiled` 與 `actionEvaluations`。

#### CLI 與範例

單一 workload 可用 --users、--arrival-rate、--warmup、--ramp-up、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Template/Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 `att-load/v1.5` policy-only descriptor，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence`、`seed` 及 Load-local `testdata` imports。明確 CLI pacing 會覆蓋 policy。`load --debug template|flow|tool <id>` 會將 sidecar 的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 policy 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy descriptor 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load/v1.5
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

可複製範例與欄位說明見 [examples/load/README.md](../../../examples/load/README.md)；schema migration 見 [Appendix C](../appendices/migrations.md)。

### Load execution ID initialization

Load 使用 att-load/v1.5。設定 execution.execIdFormat 時，ATT 在每個 iteration initialization 使用一般 ${...} / #{...} engine 求值一次；省略時維持預設 run-scoped ID。Bootstrap vars 會在生成 ID 及 output path 發布後評估。

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


execIdFormat 只允許 deterministic、side-effect-free built-ins；external calls、seq.next()、random、clock 與 filesystem functions 都會被拒絕。Schema migration 見 [Appendix C](../appendices/migrations.md)。
