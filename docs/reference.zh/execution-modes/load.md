# Load 模式

ATT 接受 att-load/v1.9 scenario。Scenario 有一個或多個 workload；每個 workload 使用單一固定 Template、Flow 或 Tool target，或使用 closed-user weighted target mix。Workload 配置 inputs、testdata policy、bootstrap vars 與 pacing。Root defaults 可供多個 workload 共用，workload-local 欄位會覆蓋它們。Scheduler 啟動前會驗證並預先 resolve 所有 target。

使用上一版 workload schema 的 descriptor 仍相容，載入時會 normalize 至現行 schema。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

## LOAD 啟動前準備被引用的 resource

Target 驗證及 Load plan preparation 完成後，ATT 只會發現所選 Template、Flow、主要及巢狀 Tool call，以及已凍結 Render payload expression 可到達的 DB、MQ、HTTP helper，並在 scheduler start gate 開啟前準備它們。Global configuration 中未使用的 helper 不會被啟動。無法靜態 resolve 的 reference 會標記為 `SKIPPED_DYNAMIC`，並在首次使用時維持 lazy preparation。

每個被引用 DB helper 會建立足夠的不同實體 connection，至少同時有 `max(1, minIdle)` 個可用 session。原有 `minIdle`、`maxSize` 設定不變，pool 後續正常擴充仍採 lazy 模式。MQ readiness 會為配置的實體 instance 建立 idle connection，但不會開啟 queue、傳送 message 或執行 business request。HTTP readiness 只建立 client 及 TLS 設定，不會連線或發出 request。

Preparation 每個 run 的 DB/MQ initial session 上限為 64 個，每個 Worker JVM 上限為 256 個，並受 deadline 及 cancellation 控制。ATT Server 每個 Server process 最多同時執行四個 LOAD Worker，因此由該 Server 提交的 LOAD 工作共用 256 個 initial session 的 aggregate 上限。Resource owner 完成 close 前，cleanup 會繼續計算其 reservation。此 scheduler 上限只涵蓋該 Server process 提交的 LOAD；RUN/Debug、CLI 或直接 Engine process，以及其他 Server instance 不在 admission budget 內，仍可能增加共用 backend 的 session。失敗會在任何 iteration admitted 前停止 run。Live output 和 structured event 會顯示各 helper readiness；run summary 會記錄準備結果。此 barrier 只適用 LOAD，也不會取代已配置的 warm-up traffic。

## 獨立 evidence 配額

`att-load/v1.9` 支援兩種配額模型。Legacy scenario 保留既有配額範圍和硬上限：`maxSamples` 是整個 run 的合併上限；`maxFailureSamples` 會啟用整次 run 共用的 success/failure 上限。兩者皆未設定時，亦保留既有 mode defaults，包括預設不設上限的 `all`。有上限的 run 會改用固定 run-time window 分配保留名額，因此保留哪些 iteration 可能與舊版本不同；配置上限和 success/failure 共用規則維持不變。

可選的 `evidence.quotaScope: perWorkload` 會啟用獨立模型。此為明確 opt-in，既有 scenario 不會被靜默重新解釋：

~~~yaml
evidence:
  quotaScope: perWorkload
  success: sample
  failure: full
  sampleRate: 0.1
  defaults:
    maxSuccessSamples: 10
    maxFailureSamples: 20
workloads:
  - id: payment-api
    target: {type: template, id: PAYMENT_INVOKE}
    load: {users: 10, duration: 2m}
  - id: mq-processing
    target: {type: tool, id: sample.getAcDate}
    load: {users: 5, duration: 2m}
    evidence:
      maxSuccessSamples: 5
      maxFailureSamples: 30
~~~

在此模式下，root `success`、`failure`、`sampleRate` 與 `resources` 仍是共用的 capture policy。`evidence.defaults` 提供每個 workload 預設的 `maxSuccessSamples` 與 `maxFailureSamples`；未設定的 default 為 **1,000**。`workloads[].evidence` 可覆寫任一 cap，且只接受這兩個 cap 欄位；只覆寫一項時，另一項繼承 default。Cap 為 0–1,000,000 的整數；0 表示該 workload 的該類別不保留 evidence。上例中，`payment-api` 為 10 success／20 failure，`mq-processing` 為 5 success／30 failure。

Per-workload 模式不可與 root `maxSamples`、`maxSuccessSamples` 或 `maxFailureSamples` 混用；`evidence.defaults` 或任何 workload cap override 都必須配合 `quotaScope: perWorkload`。執行前，ATT 會驗證所有 effective success caps 的總和及所有 effective failure caps 的總和；任一總和不得超過 aggregate safety budget 1,000,000。這是 preflight allocation 上限，不是 runtime global allocator。

每個 workload 各自擁有 cap。任一 workload 達到 success 或 failure cap，都不會預留其他 workload 的容量；未使用容量就是維持未使用。沒有 lending、borrowing、repayment、reclaiming、revocation 或由配額驅動的 artifact 刪除。只要 workload 自己的 cap 仍有空間，其第一個合格 success 或 failure 就會被接受，即使其他 workload 已全部額滿。保留的 evidence 在整個 run 內只增不減。取消、拒絕、PASS-to-FAIL 重新分類、dropped／未開始 arrival 或 evidence 缺失時，會釋放預留。Success 與 failure 容量互相獨立，一類用盡不會抑制另一類。

`sampleRate` 只用於抽樣 PASS；FAIL/ERROR（包括操作層 TIMEOUT）直接使用 failure cap，不經抽樣。`failure: full` 受 effective failure cap 限制，不保證每個失敗都會保留。Failure log 維持 65,536 字元 tail、強制 secret protection 及延後 resource formatting。

抽樣 PASS 的資格由 MurmurHash3 x86 32-bit（seed 0）計算，輸入是以 UTF-8 編碼的 iteration ID。ATT 將結果視為 unsigned 32-bit 整數，低於 `floor(sampleRate × 2^32)` 才合格；resolved policy 會記錄 `murmur3-32-v1`。Hash 合格不代表一定保留：仍須有可用配額名額及 evidence artifact。

有上限的 evidence 名額會按 planned run duration 分配到最多十個等長 window；各 outcome cap 和 workload cap 分別分配。名額少於十個時，每個名額使用一個 window。名額只會新增，不會替換、撤回、借用空 window 容量或刪除已保留 artifact。每個 window 內仍採先到先得，因此 cap 較小、某個 window 沒有合格候選，或候選速率不均時，代表性仍可能受限。Legacy `maxSamples` 維持單一合併上限，success 和 failure 共用各 window 名額。無上限保留及沒有 scheduler timeline 的直接 store 呼叫維持原有行為。

`load-summary.json`／YAML、HTML report 及 Studio job result 會顯示 resolved sampling hash 和 retention strategy、完成的 success、hash 合格數、sampling-ineligible success、已保留 evidence、success/failure 配額拒絕、無法判定 outcome 的相容性 reservation，以及缺失 artifact。抽樣模式下，completed success = hash 合格 + sampling-ineligible。若 hash 合格的 success 之後變成 failure，quota denial 會按最後 outcome 只計一次；missing artifact 只計已預留但完成後缺少 artifact 的 outcome。這些欄位位於不同階段，不能當成可相加的 retention funnel。Workload row 亦會顯示 target ID 及有效 per-workload cap。Report 會顯示各 time window 和 phase 的保留情況；legacy combined cap 或類別配額拒絕候選時會發出警告，aggregate metrics、thresholds 和 failure events 繼續完整統計。保留連結仍使用 `samples/`、`failures/`，並保留 EXEC.ID、case.log、case.yaml。Studio 會分開顯示 legacy／shared caps 與明確 per-workload scope、effective defaults 及 workload overrides，並與 advanced JSON 同步。見[per-workload 範例](../../../examples/load/independent-evidence.yaml)。


## 定義 Load scenario

~~~yaml
schemaVersion: att-load/v1.9
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
      rampUp: 5s
      warmup: 10s
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
~~~

## 將 closed-user work 分配到多個 target

Workload 可用 `mix` 代替 `target`，讓每位 closed user 的下一次 iteration 在預先 resolve 的 targets 之間選擇。每個 entry 包含唯一 `id`、正整數 `weight`，以及 Template、Flow 或 Tool `target`。Selector 由 run seed、workload ID、穩定 VU ID 和該 VU 的 iteration number 決定；切換 target 不會重設 VU 或 think-time random stream。Weight 表示選擇機率，短時間 run 不保證精確符合比例。

~~~yaml
schemaVersion: att-load/v1.9
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

DB workload 可用相同估算，將 DB operations/second 乘以 connection lease 平均時間（connection 借出後仍被使用的時間）。例如每秒 40 次 operation、平均 lease 100 ms，約需四條 concurrent connection。每個 DB helper 的 `pool.maxSize` 可按此 concurrency 加上 headroom 設定，再查看其 `resources.db` active/idle/waiting、borrow timeout 及 total borrow wait metrics。每個 helper 預設 maximum 為 20；這是 client-side 上限，並非建議的目標 database 容量。

MQ request/reply 若每秒 10 個 request、平均 reply time 為 3 秒，整個 workload 約需 30 條 leased connection。`pool.maxSize` 應按每個**實體 MQ instance** 的預期 in-flight request 數 sizing，而非按 logical helper。單一 instance（或 request 固定送往一個 instance）約需 30 條再加 headroom。兩個平均分配的 instance 平均各需約 15 條；亦要考慮 selection skew 並確認實際分佈。`resources.mq` 按實體 instance 顯示 pool metrics，請逐一查看 waiting、timeout 及 response latency。`minIdle` 會在該實體 pool 首次被使用並建立時套用，不會在 Load 開始前建立或預熱 pool。若要避免 connection creation 影響 measured steady state，請配合 Load warm-up / first-use warm-up phase 使用 `minIdle`。負載變化時，請依觀察到的平均 latency 重新估算；tail latency 可用於 headroom 規劃，但不是公式中的平均值。

duration 必填。rampUp、warmup、rampDown 預設為零。執行順序為 Ramp-up → Warm-up → Steady → Ramp-down，然後進入 DRAIN，等待已 admitted work 完成而不再排程新 arrival：Ramp-up 由零增加至設定目標，Warm-up 維持完整目標，Steady 結束後 Ramp-down 逐步降低流量。Ramp-up 與 Warm-up 都會送出真實 traffic，但不納入 measured metrics 及 threshold denominator；measured load 為 Steady + Ramp-down。例如 100/s 目標、Ramp-up 60s、Warm-up 60s、Steady 180s、Ramp-down 60s，分別排定 3,000 + 6,000 + 18,000 + 3,000 = 30,000 次 arrival。既有儲存描述會保留原值與歷史；重新執行時採用新順序，不會重現舊 phase timing。可選 seed 使 closed-VU think-time randomization 可重複。

## 識別 iterations 並查看其 artifacts

每個 started iteration 會在 bootstrap vars 與 Target 執行前取得 engine-managed `EXEC.ID`；`EXEC.RUN_ID` 維持外層 Run。詳見 [ID 初始化](#檢查-execid-可用欄位)。

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

`evidence.mode` 支援 `metrics`、`failures`、`samples`、`all`；預設為 `failures`。這些 mode 分別將 effective success/failure policy 預設為 `none/none`、`none/full`、`sample/full`、`full/full`。`evidence.success` 與 `evidence.failure` 可各自覆寫預設。`sampleRate` 決定 hash 資格；配額 window 另行決定實際保留項目。Dropped arrival 不建立 iteration evidence。有上限的選取使用最多十個 append-only time window，並在每個 window 內先到先得；抽樣和代表性限制見[獨立 evidence 配額](#獨立-evidence-配額)。

Case-log capture 依 effective success/failure policy 及剩餘 retention capacity 決定。Failure policy 為 `full` 且仍有 `maxSamples` slot 時，符合條件的 failure（包括 `samples` 中未被抽中的 success）會保留經 redaction 的 rolling log tail，上限為 65,536 個字符。ATT 只會在 failure 取得 retention slot 後 materialize failure log。Tail 被截斷時會加上 marker 標示較早事件已省略，並在末尾保留最新的 action 和 runtime failure details。容量耗盡後不再保留 failure log。Sampled success 和 full-success policy 會保留完整的 deferred log。例如 `mode: metrics, failure: full` 會在容量允許時進行有界 failure capture；`mode: failures, failure: none` 則會停用此功能。Implementation 和 storage 細節見 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、exact file-content String 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

## 查看 Load 結果並設定 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

直接 Engine／CLI `OperationResult` 輸出仍保留舊版完整 summary 詳細格式；其 schema 定義仍存於 `schemas/history/`。`LoadReportWriter` 輸出的 archived report 使用新增欄位的 `att-load-summary/v1.3`，並將大型 aggregate／per-workload metric collection 移至相對路徑 `details/v1/` sidecar，由 `details/v1/manifest.json` 引用。Inline collection 最多顯示 32 項 preview；每個 sidecar chunk 最多 4 MiB，sidecar 集合上限為 100 MiB／9,000 個檔案，完整 report output 上限為 120 MiB，每個主要 report 檔案最多 16 MiB。Aggregate counters 和 thresholds 維持精確。Server 代理 Job Results 則在 `result.summary` 使用 `att-load-terminal-result/v1`：執行結果、performance verdict、有界 aggregate counters、workload／threshold 預覽、主要診斷及 detail/archive 狀態。精簡 summary 上限為 32 KiB；量度終端 journal 和 SSE envelope 後均低於 48 KiB，並低於不變的 64 KiB journal event-data 上限。Worker frame 的 1 MiB 上限也維持不變。LOAD scheduling、virtual-user 和 arrival-rate 語義不變。

長時間 scenario 會由 Engine 執行 read-only preflight 並在 summary 記錄 `resources.preflight`；此提示不會只因 duration 過長而拒絕 run。Browser inline draft 及 package-backed scenario 均受 Server 設定的完整 envelope 上限 (`server.inlineLoad.maxDurationSeconds`，最多 10 日) 限制。選擇性 CLI Local 手動 benchmark 見 [25 小時 LOAD soak acceptance 指南](../../operations/load-soak-acceptance.zh.md)。

完整 buckets、failure samples、workload／threshold 細節及 evidence 仍保存在 `load-summary.json`、`load-summary.yaml`、`report/index.html` 及版本化 sidecar，Server 只透過現有授權 artifact archive routes 提供。Studio 先顯示精簡結果；開啟 Results 才載入 evidence/workload preview，開啟 Metrics 才載入 metric-series sidecar。初始 Job Details 不會請求 artifact。Archive availability 與執行結果獨立；`PENDING`、`ARCHIVING`、`READY`、`FAILED` 或詳細資料 omitted 均不會改變終端狀態或 exit code。ERROR 和 INVALID 會優先保留真實 runtime/preparation/validation 原因，而非 Worker／Journal truncation 或 archive 診斷。

Load v1.9 新增 `all`、`measured`、`steady` 三個 event-time window。`measured` 從 ramp-up 及 warm-up 結束後開始，只包含 steady 及 ramp-down；`steady` 只涵蓋配置的穩態 duration。HTML report 預設顯示 `steady`，並可切換其他 window。Scheduled、started、completed rate 依各 event timestamp 及配置 window duration 計算，因此 `startedRate` 仍按 event time 計算。`admissionRate` 則使用相同的 scheduled cohort：分子是該 window 內排程且在 run 結束前已開始的 arrival，分母是該 window 內全部排程 arrival；run 結束仍未開始的 arrival 視為未准入。Latency percentile 和 outcome count 依 scheduled cohort 計算，因此 drain 完成的 iteration 仍屬於其 scheduled phase。既有 top-level metric 名稱及 legacy 語義仍保留。

巢狀 `thresholds.steady` 和 `thresholds.overall` 分別對 steady 與 all-execution window 評估。Top-level measured threshold 使用修正後的 measured interval，即 `STEADY + RAMP_DOWN`；ramp-up 和 warm-up 不計入。Window 不完整或無法評估時回報 `NOT_EVALUATED`，不視為 performance pass。Report 會把 execution status 與 performance verdict 分開，並顯示 percentile sample／observation count，讓稀疏估算可被辨識。直接 Engine／CLI summary 使用 legacy summary contract (schema under `schemas/history/`)；archived report 使用新增 `detailManifestPath` 和 workload sidecar references 的 `att-load-summary/v1.3`。

Summary 的 `thresholds` object 中，`status` 和 `performanceVerdict` 是 authoritative tri-state 欄位，值為 `MET`、`NOT_MET` 或 `NOT_EVALUATED`。Optional `legacyPassed` boolean 維持舊有 `passed()` 語義：已配置但無法評估的 threshold 為 false，未配置 threshold 時為 true。Consumer 應使用 tri-state 欄位，區分 evidence 不完整與 threshold 未達標。

Summary 會將 generator observation 與 SUT outcome 分開。`metrics.generator` 包含 sampled heap used/committed/maximum、觀察到的 peak live threads、GC count/time，以及 JVM 支援時的 process CPU。Sampling 限制為每 100 ms 至多一次，較短暫的 peak 可能錯過。`schedulerWakeups`、`submitLag*` 與 `workerQueueDepth*` 描述 scheduler pressure；arrival drops 與 SUT error 分開。`resources.http` 會按 HTTP helper 報告 active/idle/waiting 與觀察到的 peak connections，並與 DB、MQ、Render pool/plan diagnostics 並列；`resources.resourceMetricSamples` 顯示 rate-limited resource observations 的數量。`resources.executionIds` 顯示 custom execution-ID reservation 數量。Testdata mapping/selection counts 位於 `resources.generator.testdata`；解讀方式和 implementation limits 見 [Load Generator Telemetry](../../system-design/load-telemetry.zh.md)。

`latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate` 描述 run-level percentile estimate；精確 latency aggregates 仍保持精確。Run-level reservoir 最多保留 4,096 個 sample。Legacy `metrics.phases.*` 每個 phase 最多保留 256 個 percentile sample；每個 configured window 的 `metrics.windowMetrics.windows.*.cohort.latency` 則使用獨立的 4,096-sample reservoir。`metrics.windowMetrics.timeSeries.points` 的每個 chart bin 最多保留 128 個 latency sample。

`metrics.windowMetrics.timeSeries.points` 全執行序列最多保留 2,048 個有資料的 time bin，初始寬度為 1,000 ms。資料 bin 超過容量時，ATT 會合併相鄰 bin，並以 power-of-two coarsening 將 bucket 寬度加倍。系統不會淘汰歷史 bin，因此較長的 run 會以較低的時間解析度保留歷史資料。`metrics.windowMetrics.timeSeries.windowSeries.measured.points` 和 `metrics.windowMetrics.timeSeries.windowSeries.steady.points` 是各自獨立的序列，各自最多 2,048 個 bin。它們按 configured window 起點對齊，初始寬度為 1,000 ms 與 window duration 兩者中較小者，並會在 window boundary 截短最後一個 partial bucket；它們使用相同的 coarsening 和不淘汰歷史資料政策。Compatibility `metrics.buckets` 是獨立的 legacy series：使用 circular buffer 保留最新 4,096 個一秒 bucket，並淘汰較舊項目。ATT 不會平均 workload percentile 來計算整體 percentile。

Load telemetry 欄位在舊版直接 summary contract（schema under `schemas/history/`）下仍屬 optional；archived report 使用 `att-load-summary/v1.3` 及 sidecar references。Server Job Results 使用另一個版本化契約 `att-load-terminal-result/v1`；需要完整 metrics 的 consumer 應在 archive 顯示 READY 後跟隨 `detailManifestPath` 及相對 chunk references。

Sampling limits、metrics 解讀方式和 maintainer verification 請看 [Load Generator Telemetry](../../system-design/load-telemetry.zh.md) 及[精簡 LOAD 終端結果](../../system-design/load-terminal-result.zh.md)。執行確定性的短時間對照與模擬長時間 transfer benchmark：`mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest#benchmarkShortAndSyntheticLongMultiWorkloadTransferSizes -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test`。該測試會建構等同 30–60 分鐘的多 workload 結果，不會真的執行長時間 LOAD。

Root thresholds 只套用於 aggregate run；workload thresholds 只套用於個別 workload，不會從 root 繼承。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

## 取消 Load run

取消時，ATT 會停止新的 admission，並 interrupt 已 admission 的 iterations。已 admission 工作的 completion events 會先 drain 並計入 outcomes，之後才 finalize workload results 和 metrics。

## 了解 Load 執行期間的 payload 變化

Load workload 啟動前，ATT 會為每個可達的 Render payload glob 解析一次，並凍結該 run 匹配到的 UTF-8 source content。Run 進行中對 payload 的編輯、替換或新增 glob match 不影響該 run 的 iterations；下一次 Load run 會重新解析 package。一般 Run 與 Debug 每次 execution 使用新 plan，因此下一次 execution 會讀取編輯後的內容。

每個 iteration 都會以自己的 Context 評估 Context reference、built-in call 及 external call。`seq.next()`、clock/random function 及 external call 等 stateful call 會在每次 iteration 執行。Render 在記憶體回傳 String，因此將 `ACTIONS.<id>.output.result` 傳給下游 action 不會建立中間 Render file。只有 operation 明確需要檔案時才使用 `EXEC.OUTPUT_DIR`。

使用 `--profile` 時，`performance.json` 會記錄 `renderPlansCompiled`、`renderPlanCacheHits`、`renderPayloadResolutions`、`renderPayloadResolutionCacheHits`、`renderEvaluations`、`renderArtifactWrites` 與 `renderSourceBytes`。Load 如何準備 plans 並在 iterations 間重用 immutable source data，請看 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

使用 `--profile` 時，`performance.json` 的 `resources.execution` 會包含 `executionPlansCompiled`、`actionPlansCompiled` 與 `actionEvaluations`。Plan 和 resolver reuse 的 implementation 見 [Load scheduler design](../../system-design/load-scheduler.zh.md)。

## 從 CLI 覆蓋 workload 設定

單一 workload 可用 --users、--arrival-rate、--ramp-up、--warmup、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Template/Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 `att-load/v1.9` policy-only descriptor，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence`、`seed` 及 Load-local `testdata` imports。明確 CLI pacing 會覆蓋 policy。`load --debug template|flow|tool <id>` 會將 sidecar 的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 policy 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy descriptor 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load/v1.9
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml
./att.sh load ../examples/load/arrival-smoke.yaml --format json
./att.sh load ../examples/load/multi-closed.yaml
./att.sh load ../examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

可複製範例與欄位說明見 [examples/load/README.md](../../../examples/load/README.md)；schema migration 見 [Migration Notes](../appendices/migrations.md)。

## 檢查 EXEC.ID 可用欄位

ATT 為每個 started iteration 配置唯一的 engine-managed `EXEC.ID`：`<runId>-execution-<sequence>`。同一 Run 的所有 Workload、Target、Mix entry 及 phase 共用一個 thread-safe、單調遞增的 allocator。ID 在 bootstrap vars 與 Target action 前發布；`EXEC.RUN_ID` 保持為外層 Run identity。Collision tracking 不會建立 per-iteration 檔案或持續增長的 ID registry。

目前及歷史 Load Scenario、policy-only YAML、inline draft，以及已儲存的 Schedule／Worker snapshot，都拒絕 `execution.execIdFormat`，包括空值。請移除此欄位並遷移至 `att-load/v1.9`，檢查依賴 artifact path 的整合，改用獨立 input／variable 保存 business correlation。歷史 artifact 保留原路徑；歷史 schema 檔案仍供檢視。不含自訂 ID 的歷史resource會正規化至目前 contract。受影響的 immutable schedule 須透過一般 reviewed-draft 流程建立替代版本。

生成 ID 沿用安全 path segment grammar 及 240 字元上限；Load execution prefix 保留符合 ASCII grammar 的 Run ID；其他合法 Run ID（包括 Unicode、內含空格）使用 `run-<Run ID UTF-8 的 SHA-256>`，每個 Run 只計算一次。外層 Run 目錄及 `EXEC.RUN_ID` 保留原值。 所有 prefix 均預留 `-execution-` 與 signed-long sequence 空間。Sequence 耗盡後持續失敗，不會 wrap。每個 Run output namespace 只透過 `.execution-owner` 原子 claim 一次；第二個 owner 或 restart 必須選用新的外層 Run ID。Claim 由安全的 completed-Job artifact retention 隨 Run 目錄清理。舊 reservation 目錄只可由 completed-Job retention 清理，不能在舊 process 仍可能使用時刪除。Metrics-only evidence 保持 lazy；單一 Run metadata 檔不會建立 iteration workspace。

外層 `execution.runIdFormat`、`execution.debugIdFormat`、literal `--run-id` 與 `--debug-id` 行為不變。Diagnostic iteration／VU／Workload／Mix ID 與 scheduler event sequence 仍獨立於 `EXEC.ID`。分散式 Worker 必須使用互不重疊的 namespace 或共用 allocator；獨立 process-local counter 不能安全共用同一 Run namespace。

## 錯誤率與失敗診斷（4.2.4）

測量階段完成項目分為 PASS、SUT 失敗及執行期 ERROR/INVALID。Ramp-up 與 Warm-up 都不計入 measured rates，但任一階段仍可使執行結果成為 ERROR。產生器丟棄獨立計算。

| 指標 | 分子 / 分母 |
| --- | --- |
| `sutErrorRate`／原有 `errorRate` 門檻 | `measuredFailure / measuredCompleted` |
| `runtimeErrorRate` | `measuredRuntimeError / measuredCompleted` |
| `totalErrorRate` | `(measuredFailure + measuredRuntimeError) / measuredCompleted` |
| `completedThroughput` | 全部測量完成數 / 測量秒數 |
| `successfulThroughput` | 測量 PASS 數 / 測量秒數 |

可選的 `runtimeErrorRate`、`totalErrorRate` 門檻採用百分比比較，例如 `{errorRate: '< 1%', totalErrorRate: '< 1%'}`。`errorRate` 保留僅計 SUT FAIL 的含義，不包含執行期錯誤或丟棄。Thresholds PASS 可與 Execution ERROR 同時出現；門檻通過或完成 TPS 均不代表執行成功。沒有測量完成項目時，JSON/YAML 的錯誤率維持數值零以相容舊消費者，HTML 顯示 N/A。報告摘要沒有門檻時顯示 `Thresholds: none configured`；有門檻時顯示 `Thresholds: N passed / M failed`。

Runtime failures / Failure diagnosis 顯示全部／測量執行期錯誤數，並於每個彙總、workload 或 mix 收集器保留最多 100 個已遮罩的輕量失敗樣本。樣本包含 iteration、序號、階段、時間、workload/mix、結果、分類、診斷來源／代碼／摘要及證據狀態；達到樣本上限後仍完整計數。沒有樣本時，報告顯示 `No lightweight failure samples were recorded.`。排程器例外與執行器回傳失敗分開顯示；公開資料不含 stack trace、已分類秘密或主機路徑。保留的 Case Log 可從 Evidence 區段連結開啟。`NO_CASE_LOG`、`WORKSPACE_MISSING`、`EVIDENCE_IO_ERROR`、`EVIDENCE_MATERIALIZATION_ERROR`、`NOT_RETAINED_BY_POLICY` 分別說明證據缺失原因，不改變 iteration 結果，也不額外計為一次失敗。

## 不綁定 Target 的 Load Templates

Load testing 預設開啟 **Templates**。六種模式共同使用 `att-load-template/v1`：`single-vu`、`single-tps`、`single-workload-mix`、`multi-vu`、`multi-tps`、`multi-workload-mix`。Single VU/TPS 沿用 Quick Load；mix weights 是每個 workload 內的 iteration 選擇機率，不會分配固定 VU。各 workload 獨立持有 users 或 requested arrivals；所有 workload 共用 phase timing。實際 throughput 和 generator drops 另行報告。

**Scenarios** 保留 executable YAML 與 **Legacy Quick Load Policy**，排除 `load/templates/`、`load/bindings/`。**View LOAD Jobs** 開啟 Jobs and results，使用 Server 授權後的 LOAD 篩選及分頁，detail/back 繼續保留 URL filter。

內建 ID 為 `builtin:<mode>`。Package template 放於 `load/templates/`，binding 放於 `load/bindings/`；只允許 package-relative、安全且通過 schema 的檔案，每檔最多 256 KiB，catalog 掃描有上限。Template 只保存結構與 policy，不保存 Target ID 或 business values。完整 YAML 範例見英文同名章節；template 欄位為 `schemaVersion`、`name`、`mode`、`policy`、`workloads`。Workload 宣告穩定 `id`、可選 `load` override、mix slot 的 `id/weight`。Binding descriptor 使用 `att-load-bindings/v1`，`bindings.<workloadId>.target` 或 `bindings.<workloadId>.mix.<slotId>` 指向 `{type,id}`。

Catalog inspection 每頁最多 100 項，亦受序列化 response 大小限制（預設 256 KiB）。須持續跟隨 `nextOffset`，直到其為 null；每頁保留完整 definition，相同 query 與 offset 可取得對應 definition，毋須額外 detail request。若單一 definition 已超出大小限制，catalog 仍列出 metadata，但標示 `response-too-large` 並省略 definition；可縮小檔案供 Studio 使用，或在 CLI 直接以檔案 reference compose。CLI `load --list-templates` 自動讀取所有分頁，並保留掃描上限的 `truncated` 標示。

例如 workload-a 使用 10 VU、A/B 70/30，workload-b 使用 20 VU、B/C 40/60，即 2 workloads、4 bindings、3 unique targets、30 VU；重複 Target 不合併 workload。額外 binding 必須有 `prototype`，明確指向同 scope 的原有 slot，複製 policy 而不複製 Target；不可省略必要 slot。Descriptor 最多 128 workloads/entries，Server 可設定更低 aggregate limits。

Builder 以精簡表格顯示 users/rates、bindings、weights。Workload、mix、Target、common settings 使用 Save/Cancel modal；Cancel/Escape 捨棄暫存修改。每個 Target 的 typed sidecar inputs/vars/Tool arguments 獨立載入；優先次序為 template policy、safe sidecar defaults、explicit binding values、適用的 CLI overrides。Debug-local testdata imports 不會自動提升，須明確宣告 Load testdata。Binding 亦可指定 workload inputs/vars/testdata selection。Business inputs/vars 按 key 合併，優先順序為 safe sidecar defaults、Target binding、workload binding；後者覆蓋同名 key，其餘 key 保留。Studio validation 及編輯會保留各 workload 的 timing、ramps、execution settings 與 ranged think time。

Validate 經由共享 Engine composer 建立完整 `att-load/v1.9` immutable draft；Review 顯示 Server-normalized redacted YAML。儲存修改或更改 Environment 會使驗證失效；提交時再次檢查 package/source revision。Run 前確認 traffic；Schedule 沿用相同 draft、權限、limits、expiry 和 revision policy。不使用 browser storage 或寫入 package source。

LOAD Job 的 **Configure & Rerun** 使用此標準 editor 及 Server 發出的 `draftRef`。它會預填安全保留的 scenario、environment、workload IDs/mix、model、pacing、timing phases、thresholds、evidence 及可安全保留的 inputs。修改共用 timing envelope 與模型專屬欄位後，需依目前 Package 驗證並檢查差異。Current-package rerun 不會無提示重載最新 YAML defaults，也不會把 Arrival Rate 轉成 Virtual Users。File-backed LOAD replay 會沿用 package-relative source route，不套用只供 inline draft 使用的 duration 上限；詳見 [Job replay](unified-run.md)。

```bash
./att.sh load --list-templates --format json
./att.sh load --debug template BROWSE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview --format json
```

Windows `att.bat` 使用相同參數。List/preview 不啟動 traffic；確認 composed scenario 後才移除 `--preview`。Multi-target 必須提供完整 bindings，拒絕不明確的 `--input`、`--set`、`--users`、`--arrival-rate`、`--think-time`、`--max-concurrent`、`--overload-policy`；duration/warmup/ramp timing 可共同覆蓋。既有不帶 template 的 single-target grammar 和無參數 scenario discovery 不變；validation error 回傳非零 exit code 與欄位位置。
