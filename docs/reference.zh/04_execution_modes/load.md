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

v1.1 在原有 `EXEC.LOAD` node 中增加 `WORKLOAD_ID`、`TARGET_TYPE`、`TARGET_ID`。Closed pool 仍保留穩定 `USER_ID`。由於不同 pool 可以各自有一個 `VU-1`，完整 VU identity 是 `(WORKLOAD_ID, USER_ID)`。

#### Workload models

**Closed VU** 使用正整數 `load.users`。每個 virtual user 只會重複執行自己 workload 的固定 target，等待一次 iteration 完成後才開始下一次。`execution.thinkTime` 作用於已完成 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`、正整數 `maxConcurrent` 及 `overloadPolicy: drop`。它沒有 persistent VU identity。因該 workload concurrency limit 已滿而無法開始的 arrival 會記為 `dropped`；不排隊，也不計作 SUT error。Arrival-rate workload 會拒絕 `execution.thinkTime`。

`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase。`EXEC.LOAD.PHASE` 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

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

`EXEC.LOAD` 的中央定義見第 3 章；artifact schema/report 細節見第 11 章。
