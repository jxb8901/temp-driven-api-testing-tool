### 4.3 Load 模式

Load 由 `att-load/v1.0` scenario 重複執行一個 Template、Flow 或 Tool target。

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

`target.type` 只可以是 `template`、`flow`、`tool`；只有 Tool target 可使用 `target.arguments`。Scenario `inputs` 會成為每個 iteration 的 `EXEC.INPUT`。

#### Workload models

**Closed VU** 使用正整數 `load.users`。同一 virtual user 連續執行 iteration 並保持穩定 `EXEC.LOAD.USER_ID`；`execution.thinkTime` 用於該 VU 兩次 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`，並必須同時提供正整數 `maxConcurrent` 和 V1 唯一的 `overloadPolicy: drop`。它沒有 persistent VU identity。因 concurrent limit 已滿而不能開始的 arrival 會記為 `dropped`，不排隊，也不計成 SUT error。

兩種 model 互斥。`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase，`EXEC.LOAD.PHASE` 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

#### CLI overrides

明確提供的 CLI 值會覆蓋 scenario 同名值，然後再次驗證 effective scenario：

`--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent`、`--overload-policy`。

#### Metrics、thresholds 與 evidence

Load 以 bounded memory 記錄 iteration success/failure、latency percentile、throughput；arrival-rate 另外記錄 scheduled/started/completed/dropped 與 achieved arrival rate。Threshold 支援 `errorRate`、`p95`、`p99`、`minThroughput`、`droppedRate`、`achievedArrivalRate`。

Evidence policy 控制成功 sample 與 failure retention。成功 workspace 預設不保留；failure/sample 只在 policy 需要時 materialize。DB/MQ pool 與 load-run resource ownership 屬 load-run resource layer；mutable `EXEC.VARS`、`EXEC.ACTIONS`、Tool transient state 與 per-iteration output 仍彼此隔離。

#### Outputs 與 exit codes

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/      # 有保留時
├── failures/     # 有保留時
└── performance.json   # 使用 --profile 時
```

`--profile` 量度 ATT generator/runtime overhead，不是 target host 的 CPU/memory benchmark。Load exit code：`0` PASS、`1` threshold failure、`2` scenario/configuration/target 無效、`3` runtime/infrastructure error。

`EXEC.LOAD` 的中央定義見第 3 章；artifact schema/report 細節見第 11 章。
