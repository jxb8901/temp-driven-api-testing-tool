### 4.3 Load 模式

ATT Load 透過有界的 load-run lifecycle 執行 Template、Flow 或 Tool target。`att-load/v1.0` 繼續作為單 target 相容契約；`att-load/v1.1` 在同一個 run 中新增多個獨立 pacing 的 workload。

#### 單 target 相容模式（`att-load/v1.0`）

```yaml
schemaVersion: att-load/v1.0
target: {type: template, id: V3_FLOW_EXAMPLE}
inputs: {region: HK}
load:
  users: 20
  duration: 5m
execution:
  thinkTime: 500ms
thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"
```

`target.type` 只可以是 `template`、`flow`、`tool`；只有 Tool target 可使用 `target.arguments`。Scenario `inputs` 會成為每個 iteration 的 `EXEC.INPUT`。既有 v1.0 scenario 繼續沿用原本的 single-workload execution path。

#### 即時主控台進度

`load` 會即時輸出 start/configuration，之後定期顯示有界計數、執行中工作、throughput 和平均 latency。Failure、error、timeout 及 dropped arrival 會即時報告並限流；成功 iteration 不會逐筆列印。`--quiet` 只保留最終摘要和錯誤。使用 `--format json` 時，JSON 保留在 stdout，即時進度寫到 stderr。最終 `load-summary` 和 report 仍是權威結果。

#### Multi-workload 契約（`att-load/v1.1`）

v1.1 scenario 包含一個或多個具名 `workloads`。每個 workload 有穩定 `id`、一個固定 target、可選 inputs/Tool arguments、自己的 load 設定，以及可選 workload thresholds。

獨立 arrival-rate 例子：

```yaml
schemaVersion: att-load/v1.1
workloads:
  - id: payment
    target: {type: template, id: PAYMENT}
    load:
      arrivalRate: 80/s
      duration: 10m
      maxConcurrent: 200
      overloadPolicy: drop
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"

  - id: balance
    target: {type: template, id: BALANCE_INQUIRY}
    load:
      arrivalRate: 20/s
      duration: 10m
      maxConcurrent: 100
      overloadPolicy: drop

  - id: customer
    target: {type: flow, id: CUSTOMER_LOOKUP}
    load:
      arrivalRate: 5/s
      duration: 10m
      maxConcurrent: 30
      overloadPolicy: drop

thresholds:
  errorRate: "< 0.5%"
  minThroughput: ">= 100/s"
```

這是三個彼此獨立的 arrival generator，不是一個 105/s scheduler 再隨機選 target。PAYMENT 始終按 80/s pacing、BALANCE 按 20/s、CUSTOMER 按 5/s；每個 workload 都有自己的 `maxConcurrent` 與 drop 行為。

Closed-VU pool 例子：

```yaml
schemaVersion: att-load/v1.1
seed: 12345
workloads:
  - id: payment
    target: {type: template, id: PAYMENT}
    load:
      users: 60
      duration: 10m
    execution:
      thinkTime:
        min: 300ms
        max: 1s

  - id: balance
    target: {type: template, id: BALANCE_INQUIRY}
    load:
      users: 30
      duration: 10m
    execution:
      thinkTime: 500ms

  - id: enquiry
    target: {type: flow, id: CUSTOMER_ENQUIRY}
    load:
      users: 10
      duration: 10m
```

此時 60 個 VU 永遠執行 PAYMENT、30 個永遠執行 BALANCE、10 個永遠執行 ENQUIRY。這**不是** transaction mix：同一個 VU 不會在 run 中切換 target。Transaction-mix selection 屬另一個獨立 feature。

#### v1.1 lifecycle 與 validation

同一個 v1.1 run 的所有 workload 必須使用相同 scheduler model：全部 `arrivalRate`，或全部 `users`。同一 run 混合 open/closed workload 會被拒絕。所有 workload 亦必須使用完全相同的 `warmup`、`rampUp`、`duration`、`rampDown` envelope，令所有 child scheduler 共用同一個 monotonic T0 與對齊 phase window。

任何 scheduler 開始之前，ATT 會先 resolve 並 validate **全部** workload target 及 dependency；只要其中一個 workload 無效，所有 workload 都不會開始執行。Workload 共用同一個 run-scoped DB/MQ resource layer，因此會真實競爭 configured pool；但 mutable iteration Context 與 output 仍彼此隔離。

v1.1 在 retained `DIAG.load` evidence 中增加 `workloadId`、`targetType` 和 `targetId`。Closed pool 仍保留穩定 `userId`。由於不同 pool 可以各自有一個 `VU-1`，完整 VU identity 是 `(workloadId, userId)`。Scheduler diagnostics 不屬於 expression Context。

舊有 expression path `EXEC.LOAD` 已不再公開；既有 template 應將業務輸入改用 `EXEC.INPUT`，並在 expression 之外檢視 retained scheduler evidence。

#### Workload models

**Closed VU** 使用正整數 `load.users`。每個 virtual user 只會重複執行自己 workload 的固定 target，等待一次 iteration 完成後才開始下一次。`execution.thinkTime` 作用於已完成 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`、正整數 `maxConcurrent` 及 `overloadPolicy: drop`。它沒有 persistent VU identity。因該 workload concurrency limit 已滿而無法開始的 arrival 會記為 `dropped`；不排隊，也不計作 SUT error。Arrival-rate workload 會拒絕 `execution.thinkTime`。

`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase。`DIAG.load.phase` evidence 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

#### Closed-VU think time 與 deterministic randomization

原有 fixed-duration 寫法保持相容：

```yaml
execution:
  thinkTime: 500ms
```

Closed VU 亦可使用 uniform range：

```yaml
execution:
  thinkTime:
    min: 500ms
    max: 2s
```

兩端都使用 ATT 的整數 duration syntax（`ms`、`s`、`m`、`h`），正規化為毫秒後兩端均包含，且要求 `max >= min`。每個 VU 在一次 iteration 完成後重新取樣。Sleep 會裁剪至 load envelope 剩餘時間，而且不計入 response latency。

Top-level `seed` 可明確提供 run seed；否則 ATT 由 `runId` 推導。v1.1 每個 closed VU 的 deterministic stream 由 run seed + workload identity + `USER_ID` 推導，因此不同 workload 不會共用 mutable RNG。Randomized think-time run 的 report-safe summary 會保留 effective seed；每次 sampled delay 不會逐筆持久化。

已提交的離線例子為 `examples/load/multi-arrival.yaml` 及 `examples/load/multi-closed.yaml`。

#### CLI overrides

明確 CLI 值繼續可覆蓋 v1.0 scenario 對應欄位：

`--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent`、`--overload-policy`。

若 v1.1 scenario 有**多於一個 workload**，上述沒有 workload scope 的 load-model override 會產生歧義，因此 ATT 會在執行前拒絕；每個 workload 以 YAML 為準。只有一個 workload 的 v1.1 scenario 仍可使用現有 override。`--think-time <duration>` 仍只代表 fixed duration；不使用含糊的 range 字串語法。

#### Metrics、thresholds 與 evidence

v1.1 report 同時包含 aggregate 與 per-workload metrics。Aggregate counter/rate 由所有 workload 的 raw event 合併。Aggregate latency percentile 直接由 aggregate latency collector 計算；ATT **不會**把各 workload 的 P95/P99 做平均來得到 overall percentile。

Workload threshold 放在各自 workload 內；top-level v1.1 threshold 作用於整個 aggregate run。任何 workload threshold 或 global threshold fail，都令 run 成為 `FAIL`／exit `1`；任一 workload 出現 runtime/infrastructure error，整體為 `ERROR`／exit `3`。

支援的 threshold 包括 `errorRate`、`p95`、`p99`、`minThroughput`、`droppedRate`、`achievedArrivalRate`，並受 workload model 相容性限制。

Evidence policy 仍是 run-scoped。v1.1 會按 workload 分區保存 retained artifact。每筆 sample/failure 記錄及其 summary link 都會記錄 workload、target type 和 ID、iteration ID，以及 closed users 的 user ID：

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/<workloadId>/...
├── failures/<workloadId>/...
└── performance.json   # 使用 --profile 時
```

DB/MQ resource diagnostics 仍屬 aggregate/run-scoped；成功 iteration workspace 預設不保留。

#### Outputs 與 exit codes

`--profile` 量度 ATT generator/runtime overhead，不是 target host 的 CPU/memory benchmark。Load exit code：`0` PASS、`1` threshold failure、`2` scenario/configuration/target 無效、`3` runtime/infrastructure error。

Load execution identity 與 evidence-only scheduler diagnostics 的中央定義見第 3 章；artifact schema/report 細節見第 11 章。

#### Evidence retention 使用指南

Evidence policy 是 run-scoped 設定，決定哪些已完成 iteration record 和 workspace 需要保留；它不會改變 scheduler、pacing、VU identity、`maxConcurrent`、overload/drop 行為、think time、threshold aggregate 或 measured latency。

| `mode` | 成功 iteration | 失敗 iteration | 常見用途 |
|---|---|---|---|
| `metrics` | 不保留 | 不保留 | 純 performance 量度，最低 evidence I/O |
| `failures` | 不保留 | 完整保留 | 一般 SIT/UAT load test；建議 default |
| `samples` | 取樣保留 | 完整保留 | Representative success 加上所有 failure |
| `all` | 完整保留 | 完整保留 | Troubleshooting 及短時間 controlled test |

Default 是 `failures`：保留可行動的 failure，同時避免每個成功 iteration 都建立 Case workspace 和 evidence file。`all` 是明確 opt-in；在高 throughput 或長時間測試中，它會明顯增加 generator 的 disk 與 I/O 使用量。

每一種 mode 都可以直接複製到 scenario：

```yaml
evidence:
  mode: metrics
```

```yaml
evidence:
  mode: failures
  maxSamples: 100
```

```yaml
evidence:
  mode: samples
  sampleRate: 0.02
  maxSamples: 500
```

```yaml
evidence:
  mode: all
```

完整保留亦可用顯式 policy 表示：

```yaml
# 這個 explicit form 只適用於 att-load/v1.1。
evidence:
  success: full
  failure: full
```

##### 欄位語義與 precedence

`mode` 提供 policy default；顯式 `success` 和 `failure` 會分別覆蓋各自的 mode-derived 值。完全省略 `evidence` 時，framework default 是 `mode: failures`。

| 欄位 | 值／default | 語義 |
|---|---|---|
| `mode` | `metrics`、`failures`、`samples`、`all`；default `failures` | 選擇上表的 success/failure policy。 |
| `success` | `none`、`sample`、`full`；省略時由 mode 決定 | 只有 `sample` 會受 `sampleRate` 影響；`full` 保留每個符合條件的 completed success。顯式 `full` 需要 `att-load/v1.1`；frozen v1.0 schema 接受 `mode: all`，但拒絕這個 field value。 |
| `failure` | `none`、`full`；省略時由 mode 決定 | `full` 獨立保留符合條件的 completed failure，不受 success sampling 影響。 |
| `sampleRate` | `0` 至 `1`；只有 `success: sample` 預設 `0.01` | deterministic success sampling 比例；`none` 與 `full` 會忽略並解析為 `0`。 |
| `maxSamples` | `>= 0` 整數；bounded policy default `1000` | 所有 retained completed success/failure 共用的一個總 cap；`0` 表示不保留。顯式值也適用於 `all`。 |

`mode: all` 及 `success: full` 預設沒有 implicit retention cap。因此 `all` 會保留每一個 completed success 和 failure，除非顯式設定 `maxSamples`。例如 `mode: all` 加 `maxSamples: 1000` 的意思是「最多保留 1000 筆符合條件的 record」，不是 unlimited retention。Dropped arrival 不是 completed iteration，不會建立 retained iteration evidence。

`maxSamples` 會在 retained record 與 in-flight success reservation 之間以 atomic 方式執行。若 success 是否符合 policy 可由 iteration ID 先決定，才會在執行前取得 slot；failure 則要等 completed status 確定後才 claim 剩餘 quota，再延遲 materialize log/context。這避免 in-flight success 或未取樣 iteration 搶走後續 eligible failure 的 quota，同時仍把 retained workspace/evidence overhead 限制在設定的 cap 內。只有 claim、policy 與已 materialize 的 evidence 都成立時，completion 才會被保留。

##### Retained artifact 與容量估算

Aggregate result 和 retained record 是兩種不同資料：

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/<workloadId>/...     # retained successful iterations
├── failures/<workloadId>/...    # retained failed iterations
└── iterations/...               # 需要時才建立的 temporary/retained workspace
```

每個完成的 run 都會有 summary、HTML report 和 bounded metrics。Retained evidence file 只是指向 iteration workspace 的 link，不是 aggregate latency/throughput metric。成功 workspace 一般採 lazy materialization，只有 policy 要保留時才建立；failure workspace 在 completed status 後才 claim 和 materialize，因此 `mode: failures` 不會讓成功的 in-flight work 搶走 failure slot，也不會在大量 failure 同時完成時超過可用 cap。v1.0 使用單 target 的 `samples/` 和 `failures/`；v1.1 再加上 `<workloadId>`，避免不同 target 的 record 混淆。

具體估算：`10 TPS × 5 分鐘` 約產生 `3,000` 個 scheduled iteration。使用 `success: sample` 與 `sampleRate: 0.02` 時，約有 `60` 個成功 record 在 cap 前符合取樣資格。Failure 由 `failure` policy 獨立處理，不會受 success sample rate 影響；但兩者共用顯式的 `maxSamples` 總 cap。

`metrics` 的 disk/IO overhead 最低；一般 load test 使用 `failures`；需要代表性成功 request context 時使用 `samples`。`all` 不會改變 scheduler 或 measured latency，但未設定顯式 cap 時可能為每個 completed iteration 寫出 workspace 和 evidence；長時間、高 TPS、production-like 測試應先評估 disk 和 generator overhead。

##### Closed-VU 與 arrival-rate 例子

同一套 policy 同時適用於兩種 scheduler：

```yaml
# Closed VU：每筆選中的 record 保留穩定 userId。
schemaVersion: att-load/v1.0
target: {type: template, id: PAYMENT}
load: {users: 20, duration: 5m}
evidence: {mode: samples, sampleRate: 0.02, maxSamples: 500}
```

```yaml
# Fixed arrival rate：dropped arrival 仍是 drop，不會建立 evidence。
schemaVersion: att-load/v1.0
target: {type: flow, id: PAYMENT_LOOKUP}
load: {arrivalRate: 10/s, duration: 5m, maxConcurrent: 50, overloadPolicy: drop}
evidence: {mode: all, maxSamples: 5000}
```

Evidence policy 在 run 開始時套用。它不會把 arrival-rate 變成 queue、不會新增 VU identity、不會改變 closed-VU think time 或 `maxConcurrent`，也不會把 generator drop 當成 SUT error。v1.1 的同一個 run-scoped policy 會套用到每個 workload，而 retained file 會按 workload ID 分區。

##### Troubleshooting

- **為什麼沒有成功 sample？** 可能是 `metrics`/`failures`、`success: none`、`sampleRate: 0`，或成功 sample 已達 `maxSamples`。要 bounded 地檢查成功 iteration，可使用非零 `sampleRate` 的 `mode: samples`。
- **為什麼只看到 failure？** 這是 `failures` default 的預期結果；需要成功 evidence 時選 `samples` 或 `all`。
- **為什麼 retention 在 N 筆後停止？** 顯式 `maxSamples` 是 success/failure 共用的總 cap。省略時 bounded policy default 是 `1000`；`all` 沒有 implicit cap。
- **`mode: all` 是否真的代表全部？** 是；所有 completed success/failure 都符合保留資格，除非顯式設定 `maxSamples`。Dropped arrival 不算 completed iteration。
- **`sampleRate` 會影響 failure 嗎？** 不會；它只在 `success: sample` 時使用，failure 由 `failure` policy 決定。
- **為什麼 `mode: failures` 的某個 failure 沒有 workspace？** Shared cap 可能已被 retained evidence 或已知符合條件的 success reservation 佔用。Failure 是在 completed 後才 claim quota，因此 success 的 in-flight work 不會永久搶走 failure slot；完成時 cap 已滿的 failure 不會保留。
- **Dropped arrival 會建立 retained evidence 嗎？** 不會；它只留在 scheduler metrics/events。
- **某一個 workload 的 evidence 在哪裡？** v1.0 查看 `samples/` 或 `failures/`；v1.1 查看 `samples/<workloadId>/` 或 `failures/<workloadId>/`，並以 summary/report 的 relative path 為準。

##### `mode: all` migration note

舊版 ATT 將 `mode: all` 當作 sampled successes 加上 full failures，並套用 default success `sampleRate`。修正後是 full successes 加上 full failures；除非顯式設定 `maxSamples`，否則沒有 implicit cap。若既有 scenario 依賴 `mode: all` 的低 success sampling，升級後 evidence volume 可能大幅增加；請重新評估 disk budget，或改用 `samples`。

##### 顯式 full success 的 schema migration

歷史 `att-load/v1.0` schema 是 frozen 的。它的 `evidence.success` enum 仍然只有 `none` 或 `sample`；即使 runtime policy 支援 full success retention，也會刻意拒絕 `success: full`。v1.0 scenario 若需要完整成功 evidence，可以繼續使用 `evidence: {mode: all}`；或升級至 v1.1：把 `schemaVersion` 改為 `att-load/v1.1`，將 `target`、`inputs`、`load` 移到一個 workload（例如 `workloads: [{id: default, ...}]`）下，再使用顯式 `success: full`。

#### Lazy Load workspace 下的 MQ payload

MQ `file` 可以使用 ATT package 內 regular file 的 absolute path。Load mode 會以 package root 驗證這類 path，因此即使目前 iteration workspace 尚未 materialize，也能正常使用；ATT 不會為了驗證 project payload 而建立空 workspace。

Relative MQ payload path 仍限制在 Case output：拒絕 `..` traversal、symlink payload 和 symlink escape，且 resolved file 必須是安全的 regular file。真正不存在的 payload 會直接報告 payload path 問題。這些是 MQ connect/open/put/get 之前的 local path validation，不會改變 queue configuration、response parsing、pacing 或 evidence retention。
