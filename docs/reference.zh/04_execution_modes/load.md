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

**Closed VU** 使用正整數 `load.users`。同一 virtual user 連續執行 iteration 並保持穩定 `EXEC.LOAD.USER_ID`；`execution.thinkTime` 用於該 VU 已完成的兩次 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`，並必須同時提供正整數 `maxConcurrent` 和 V1 唯一的 `overloadPolicy: drop`。它沒有 persistent VU identity。因 concurrent limit 已滿而不能開始的 arrival 會記為 `dropped`，不排隊，也不計成 SUT error。Arrival-rate scenario 會拒絕 scalar 或 range 形式的 `execution.thinkTime`。

兩種 model 互斥。`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase，`EXEC.LOAD.PHASE` 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

#### Closed-VU think time 與 deterministic randomization

原有 fixed duration 寫法完全保持相容：

```yaml
execution:
  thinkTime: 500ms
```

Closed VU 亦可使用 uniform range：

```yaml
schemaVersion: att-load/v1.0
seed: 12345
load:
  users: 20
  duration: 5m
execution:
  thinkTime:
    min: 500ms
    max: 2s
```

`min`、`max` 都使用 ATT 既有的整數 duration syntax（`ms`、`s`、`m`、`h`），並正規化成整數毫秒。兩端都包含在取樣範圍內；`min` 可以是零，`max` 必須大於或等於 `min`。兩者相等時會正規化成與 scalar 寫法相同的 fixed policy。缺少 `min`/`max` 或加入未知 range 欄位都屬 invalid。

每個 VU 在一次 iteration 完成後、開始下一次 iteration 前重新取樣一次。Sleep 會裁剪至 load envelope 的剩餘時間，因此 think time 不會令 run 超過配置結束時間。Think time 只在 target completion timestamp 之後開始，所以不計入 target response latency 或 latency percentile。

Randomized load generation 使用單一 effective run seed。可用 top-level `seed` 明確指定；沒有指定時，ATT 會由 load `runId` 穩定推導 effective seed。每個 VU 都有由 run seed、workload identity 與 `USER_ID` 推導出的獨立 deterministic stream，不會在 VU threads 之間共用 mutable RNG。Report-safe load summary 會在 randomized think-time run 記錄 `effectiveSeed`，並把 policy 顯示為 `fixed: 500ms` 或 `uniform: 500ms..2s`；ATT 不會持久化每一次 sampled delay。

相同 seed 加相同 VU/workload identity 會重播相同 random sequence。這個 run-level facility 刻意獨立於 think-time YAML，讓日後 randomized workload selector 可以重用，而不是再建立另一套 RNG scheme。完整離線例子已提交在 `examples/load/closed-random-think.yaml`。

Sampled delay 屬 scheduler 行為，不會增加新的 public Context root 或 field。

#### CLI overrides

明確提供的 CLI 值會覆蓋 scenario 同名值，然後再次驗證 effective scenario：

`--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent`、`--overload-policy`。

`--think-time <duration>` 繼續只表示 fixed duration。V1 的 random range 只可在 YAML 設定；CLI 不會把 range 塞進含糊的字串語法。若 YAML 原本是 range，明確提供 `--think-time` 會把它覆蓋為該 fixed duration。

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
