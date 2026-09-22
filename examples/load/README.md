# ATT Load Scenario Examples

本目錄的 scenario 使用 `att-load/v1.0`。`att load` 會先完成 schema、語義、target 解析及依賴驗證，再啟動 3.5.0 的 closed-VU 或 fixed-arrival-rate scheduler。兩種 scheduler 共用同一個 `IterationExecutor` 和普通 run/debug 的 `EXEC`/`META` Context；只有 scheduler identity 放在 `EXEC.LOAD`。

## 1. 最小 closed workload

`closed` model 用固定數量的 Virtual Users。每個 user 可以連續產生多個 iteration，因此 scheduler 必須為同一 Virtual User 維持穩定的 `EXEC.LOAD.USER_ID`。

```yaml
schemaVersion: att-load/v1.0

target:
  type: template
  id: V3_FLOW_EXAMPLE

load:
  users: 20
  duration: 5m
```

執行：

```sh
./att.sh load examples/load/closed-minimal.yaml
```

`load.duration` 是唯一必填的 timing 欄位；`warmup`、`rampUp` 和 `rampDown` 可省略，省略時等同於零。實際可直接執行的完整例子見 [`closed.yaml`](closed.yaml)。

## 1.1 最短 smoke commands

以下兩個例子使用包內 deterministic `sample.getAcDate` Tool，約在數秒內完成，適合先確認 CLI、scheduler、summary 和 report 路徑：

```sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
```

`closed.yaml` 和 `arrival-rate.yaml` 保留 30 秒 warm-up、1 分鐘 ramp-up、5 分鐘 measured duration 和 30 秒 ramp-down，作為較接近實際 workload 的完整例子。

## 2. 完整 closed scenario

```yaml
schemaVersion: att-load/v1.0

target:
  type: template
  id: V3_FLOW_EXAMPLE

# inputs 會進入每個 iteration 的 EXEC.INPUT；舊 CASE alias 僅為相容 view。
inputs:
  paymentType: LOAD
  region: HK

load:
  users: 20
  warmup: 30s
  rampUp: 1m
  duration: 5m
  rampDown: 30s

execution:
  thinkTime: 500ms

thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"

evidence:
  mode: failures
  sampleRate: 0.1
  maxSamples: 1000
```

closed model 的規則：

- 必須提供正整數 `load.users`。
- 不可同時提供 `load.arrivalRate`、`load.maxConcurrent` 或 `load.overloadPolicy`。
- `execution.thinkTime` 只適用 closed model。
- `EXEC.LOAD.USER_ID` 對同一 Virtual User 保持穩定；`EXEC.LOAD.ITERATION_ID` 對每次 iteration 唯一，並且同時作為 `EXEC.ID`。

## 3. 完整 fixed arrival-rate scenario

`arrivalRate` model 用固定到達率建立 iteration，不建立長期 Virtual User identity。可直接執行的例子見 [`arrival-rate.yaml`](arrival-rate.yaml)。

```yaml
schemaVersion: att-load/v1.0

target:
  type: flow
  id: common.compose.v1

inputs:
  source: load-example
  region: HK

load:
  arrivalRate: 100/s
  warmup: 30s
  rampUp: 1m
  duration: 5m
  rampDown: 30s
  maxConcurrent: 500
  overloadPolicy: drop

thresholds:
  droppedRate: "== 0%"
  achievedArrivalRate: ">= 99%"

evidence:
  mode: metrics
```

arrival-rate model 的規則：

- `load.arrivalRate` 必須是正數加 `/s` 或 `/m`，例如 `100/s`、`6000/m`。
- 必須同時提供正整數 `maxConcurrent` 和 `overloadPolicy: drop`。
- 不可提供 `load.users` 或 `execution.thinkTime`。
- `EXEC.LOAD.USER_ID` 是 `null` 或 absent；不要把一次 arrival-rate iteration 當成長期 Virtual User。

## 4. 三種 target

`target.type` 只支持 `template`、`flow`、`tool`：

```yaml
# Template
target: {type: template, id: V3_FLOW_EXAMPLE}

# Flow；id 必須是已註冊的 canonical Flow ID
target: {type: flow, id: common.compose.v1}

# Tool；id 可以是全域 Tool key 或 group.localKey
target:
  type: tool
  id: fpp.invokeApi
  arguments:
    requestId: LOAD-001
    requestType: fromchannel
    requestFile: /tmp/load-request.xml
    apiLogPath: /tmp/load-api.log
```

Tool target 的 `arguments` 會轉成正常 Tool call；它必須符合 `config/config.yaml` 的全域 Tool 或 `config/tools/*.yaml` 的 Tool group 契約。`fpp.invokeApi` 是包內的 reference Tool，實際整合前要替換 reference script 和 payload 路徑。

要先驗證一個不依賴外部服務的 Tool target，可直接使用 [`tool.yaml`](tool.yaml)：

```sh
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

這個例子使用 `sample.getAcDate`，每次 iteration 啟動包內的 deterministic shell Tool；真實整合時只需替換 `target.id` 和其 `arguments`，不需要改 load scheduler 或 Context contract。

## 5. 欄位說明

| 路徑 | 必填 | 說明 |
|---|---:|---|
| `schemaVersion` | 是 | 固定為 `att-load/v1.0`。 |
| `target.type` | 是 | `template`、`flow` 或 `tool`。 |
| `target.id` | 是 | 目標 Template 名稱、Flow canonical ID 或 Tool key。 |
| `target.arguments` | 否 | 只支持 Tool target 的 named arguments；Template/Flow target 会被拒绝。 |
| `inputs` | 否 | 傳入每個 iteration 的 `EXEC.INPUT.*`；`CASE.*` 只保留為相容 alias。 |
| `load.users` | closed 必填 | 正整數 Virtual User 數量。 |
| `load.arrivalRate` | arrival 必填 | 正數速率，格式為 `number/s` 或 `number/m`。 |
| `load.warmup` / `rampUp` / `duration` / `rampDown` | `duration` 必填 | 整數 duration，例如 `500ms`、`30s`、`5m`、`1h`；`duration` 必須大於零。 |
| `load.maxConcurrent` | arrival 必填 | 大於零的並發上限。 |
| `load.overloadPolicy` | arrival 必填 | V1 只支持 `drop`。 |
| `execution.thinkTime` | 否 | closed iteration 之間的 think time；arrival-rate 禁止。 |
| `thresholds.*` | 否 | `errorRate`/`droppedRate` 用 `%`，`achievedArrivalRate` 用 `%`、`/s` 或 `/m`，`p95`/`p99` 用 `ms`，`minThroughput` 用 `/s` 或 `/m`；`minThroughput` 对两种 workload 都适用。 |
| `evidence.mode` | 否 | `metrics`、`failures`、`samples` 或 `all`。 |
| `evidence.sampleRate` | 否 | `0` 到 `1` 之間的 sample fraction。 |
| `evidence.maxSamples` | 否 | 非負整數 sample 上限。 |

未知欄位會被拒絕；需要自訂 metadata 時只能使用根層 `x-*` 欄位。

## 6. CLI 覆蓋與優先級

scenario 是基礎配置，明確提供的 CLI workload option 會覆蓋同名 YAML 欄位；沒有提供的 option 保留 YAML 值：

```sh
./att.sh load examples/load/closed.yaml \
  --users 50 \
  --warmup 1m \
  --duration 2m \
  --think-time 250ms

./att.sh load examples/load/arrival-rate.yaml \
  --arrival-rate 200/s \
  --max-concurrent 800 \
  --duration 10m \
  --format json
```

支持的 workload overrides 是 `--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`。覆蓋後仍會重新執行完整 schema 和語義驗證；override 不會繞過 closed/arrival-rate 的互斥規則。

## 7. EXEC/META Context

scheduler 呼叫共用 `IterationExecutor` 時，每個 iteration 都建立獨立 runtime：

| Context | 意義 |
|---|---|
| `EXEC.ID` | 當前 iteration identity，與 `EXEC.LOAD.ITERATION_ID` 相同。 |
| `EXEC.MODE` | 固定為 `load`。 |
| `EXEC.STARTED_AT` | 當前 iteration 的 ISO-8601 start timestamp。 |
| `EXEC.OUTPUT_DIR` | 當前 iteration 隔離的 output directory。 |
| `EXEC.INPUT.*` | scenario `inputs` 與 scheduler 傳入的 iteration input。 |
| `EXEC.VARS.*` / `EXEC.ACTIONS.*` | 每個 iteration 內獨立的 mutable variables 和 current-scope Action 結果。 |
| `EXEC.LOAD.RUN_ID` | enclosing load run identity；同一 load run 的 iterations 共用。 |
| `EXEC.LOAD.MODEL` | `closed` 或 `arrivalRate`。 |
| `EXEC.LOAD.USER_ID` | closed model 的穩定 VU identity；arrival-rate 為 `null` 或 absent。 |
| `EXEC.LOAD.ITERATION_ID` | load run 內唯一的 iteration identity，亦是 `EXEC.ID`。 |
| `EXEC.LOAD.ITERATION` | scheduler 提供的 iteration sequence。 |
| `EXEC.LOAD.PHASE` | `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。 |
| `EXEC.LOAD.RUN_STARTED_AT` | enclosing load run start timestamp（若 scheduler 提供）。 |
| `META.SOURCE` / `META.TARGET` | secret-safe 的 load scenario、target type/id 及來源 metadata；不暴露整份 config 或 secrets。 |

Template、Flow 和 Tool 的 reusable component 仍使用 `EXEC.INPUT.*`、`EXEC.VARS.*`、`EXEC.ACTIONS.*` 與 action-local `output.*`；不使用 root-level `LOAD.*`、`EXEC.OUTPUT` 或 public `CALL`/`INVOCATION` worker fields。

`CASE.VARS`、Action/Flow scope、DB state、Tool transient state 和 MQ state 不會在 concurrent iterations 之間共享。成功 iteration 預設不留下完整 Case artifact；需要輸出時由 scheduler 傳入 output directory。

## 8. 驗證與診斷

```sh
./att.sh load examples/load/closed.yaml
./att.sh load examples/load/arrival-rate.yaml --format json
```

成功執行時，human output 使用固定的三行摘要：

```text
LOAD PASS | model=closed | runId=<id>
Report: <absolute path>/output/load/<id>/report/index.html
Metrics: { ... }
```

`--format json` 的 stdout 是完整的 load result/summary JSON，包括 `status`、`exitCode`、`runId`、report-safe `scenario`、`timing`、bounded `metrics`、`thresholds`、resource diagnostics 和可選 evidence；它不是另一個獨立的 resolved-target object。錯誤則返回 exit code `2`，並保留 scenario file、field/path 和建議。

以下配置會在 scheduler 前失敗：

```yaml
load:
  users: 20
  arrivalRate: 100/s   # 與 users 互斥
  duration: 5m
```

```yaml
load:
  arrivalRate: 100/s
  duration: 5m
  maxConcurrent: 500
  overloadPolicy: drop
execution:
  thinkTime: 500ms     # arrival-rate 不允許
```

修改 scenario 後可直接執行 `att load`。結果會寫到 `output/load/<runId>/load-summary.json`、`load-summary.yaml` 和 `report/index.html`；成功 iteration 預設只保留 metrics，不建立 physical workspace，失敗 iteration 只在需要保留 diagnostic 時 lazy 建立 `case.log`/`case.yaml`，明確設定的 success sample 才建立有界的 physical workspace 並寫入 evidence link。所有 load evidence 都留在 `output/load/<runId>/samples` 或 `failures`，`att run` 的普通 Excel Case lifecycle 不會因 load scenario 而改變。加上 `--profile` 時，load run 也會在同一目錄寫出 `performance.json`，包含 load execution/report phases、bounded load counters，以及既有 schema、Template、payload 和 process counters；它是 ATT generator self-overhead 的可重複診斷證據，不是 target CPU/memory benchmark。

## 9. Runtime、metrics 和 report

closed workload 會為每個 Virtual User 維持穩定的 `EXEC.LOAD.USER_ID`，完成一個 iteration 後才進入 think time；arrival-rate workload 按絕對 planned due time 送出 arrival，超過 `maxConcurrent` 時記錄 `dropped`，不排隊，也不把 generator saturation 算成 SUT error。warm-up traffic 會執行，但預設不納入 thresholds 的 measured aggregates。

每次 load run 會產生 bounded-memory metrics：iterations、success/failure、completed throughput、SUT error rate、p50/p95/p99 latency，以及 arrival-rate 的 configured/achieved rate、current/max in-flight、scheduled/started/completed/dropped。arrival-rate 的 `achievedArrivalRate` 若以 `%` 作 threshold，表示 measured phase 的 `measuredStarted / measuredScheduled`；warmup 不計入這兩個 measured counters，ramp-up、steady 和 ramp-down 仍按 integrated planned arrivals 保持可比較。若以 `/s` 或 `/m` 作 threshold，則比較整個 phase window 的實際平均 started rate；`/m` 會先換算成每秒。threshold failure 會以 exit code `1` 結束；load runtime/infrastructure error 以 `3` 結束；validation/configuration failure 以 `2` 結束；只有 PASS 返回 `0`。JSON 與 load summary 會同時輸出 `status`、`exitCode` 及每個 threshold 的 expected/actual/status/diagnostic。

### 9.1 Summary 與 HTML report contract

`load-summary.json` 和 `load-summary.yaml` 共用穩定的 `att-load-summary/v1.0` contract。root-level 欄位包括 `schemaVersion`、`status`（`PASS`、`FAIL` 或 `ERROR`）、`exitCode`、`runId`、`startedAt`、`endedAt`、`durationMs`、`scenario`、`timing`、`metrics`、`thresholds`、`resources`、可選的 `evidence`，以及相對於 run directory 的 `report: report/index.html`。JSON schema 位於 `schemas/att-load-summary-v1.0.schema.json`，schema catalog 會以 `att-load-summary/v1.0` 對應它。

報告中的 `scenario` 是專用的 report-safe projection，只保留 target type/id、load/execution timing、threshold 和 evidence policy；任意業務 `inputs` 及 Tool `target.arguments` 不會寫入 JSON、YAML 或 HTML `window.ATT_LOAD_SUMMARY`。因此可把 summary 交給 CI 或離線工具，而不會把 password、token、request body 或其他大 payload 帶入 durable report artifacts。

`timing.phases` 以 `WARMUP`、`RAMP_UP`、`STEADY`、`RAMP_DOWN` 順序列出 configured start/end/duration；`metrics.phases` 則提供實際 observed 的 scheduled/started/completed/failure/drop、throughput、latency、scheduler lag 和 concurrency aggregates。`WARMUP` 的 `measured` 是 `false`，其 traffic 仍保留在 run history；其他 phase 的 measured aggregates 才用於 SLA 判斷。沒有事件的 phase 仍會在 `timing.phases` 出現，方便 empty/edge run 被機器穩定解析。

`resources.db` 和 `resources.mq` 只包含 pool 的 bounded diagnostics（例如 pool size、active/idle、waiting、timeout/acquisition counts）；不包含 connection、queue handle、credential 或其他 live object。DB/MQ pool saturation、acquisition timeout 和 SUT failure 必須分開解讀。`evidence.items[].path` 是指向 `<runId>/samples/` 或 `<runId>/failures/` 的 retained evidence path，HTML report 會把它渲染成可點擊的相對連結。

`report/index.html` 是 self-contained、可離線打開的 performance report：它顯示 run identity/status、closed 或 arrival-rate 的 model semantics、phase/warm-up 分隔、aggregate metrics、threshold diagnostics、resource diagnostics、retained evidence links，以及按一秒 bounded buckets 渲染的 time series。arrival-rate 會明確分開 configured arrival rate、achieved scheduling rate、completed TPS 和 generator drops；drop 不會被當成 SUT error。報告同時連結旁邊的 JSON/YAML summary，但不嵌入 raw per-iteration samples 或 secrets；`window.ATT_LOAD_SUMMARY` 只提供同一份 bounded summary 給離線工具使用。

`load-summary.json` 的 `metrics` 是 machine-readable contract。全局 metrics 包含 `configuredUsers`、`configuredArrivalRatePerSecond`、`configuredMaxConcurrent`、`iterations`、`scheduled`、`measuredScheduled`、`started`、`measuredStarted`、`completed`、`success`、`failure`、`runtimeError`、`dropped`、`measuredDropped`、`activeVus`/`maxActiveVus`、`currentInFlight`/`maxInFlight`、`warmupCompleted`、`measuredCompleted`、`sutErrorRate`、`runtimeErrorRate`、`droppedRate`、`completedThroughput`、`achievedArrivalRate`、`schedulerLagMeanMs`/`schedulerLagMaxMs`、`errorClassifications` 及 bounded latency sample/percentile fields。`p50Ms`/`p95Ms`/`p99Ms` 来自 bounded reservoir；`latencyMinMs`、`latencyMeanMs`、`latencyMaxMs` 和 `latencyObservationCount` 则跨全部 measured observations exact。`runtimeError` 不計入 `sutErrorRate`；`dropped` 是 generator saturation，不是 SUT failure。成功 iteration 不會把完整 Context 或 Case evidence 放入 metrics。

`metrics.buckets` 以一秒的 epoch-millisecond key 排序，每個 bucket 包含 `bucketStart`、`model`、`phase`、configured rate/concurrency、scheduled/started/completed/success/failure/runtimeError/dropped、`completedThroughput`/`completedTps`、`p95Ms`/`p99Ms`、`sutErrorRate`、`droppedRate`、active/in-flight、scheduler lag 和 error classifications。latency reservoir 最多保留 4096 個全局值、每 bucket 256 個值；time-series 最多保留 4096 個 bucket，超出的最舊 bucket 會被淘汰。

示例：

```json
{
  "metrics": {
    "configuredArrivalRatePerSecond": 100.0,
    "scheduled": 1000,
    "started": 980,
    "completed": 970,
    "dropped": 20,
    "completedThroughput": 96.5,
    "p95Ms": 420,
    "p99Ms": 730,
    "sutErrorRate": 0.002,
    "buckets": {
      "1700000000000": {
        "model": "arrivalRate",
        "phase": "STEADY",
        "completedTps": 97,
        "p95Ms": 415,
        "p99Ms": 710,
        "dropped": 2
      }
    }
  }
}
```

DB pool 的 `maxSize`/`connectionTimeout` 與 VU 或 `maxConcurrent` 無關；MQ pool 的 `maxSize`/`borrowTimeout` 同樣獨立。DB/MQ physical resources 由 load-run owner 管理，queue handles 仍然是 invocation-scoped，pool timeout 會分別標示為 `DB_POOL_TIMEOUT` / `MQ_POOL_TIMEOUT`，不會冒充 SQL、MQRC 2033 或 SUT failure。

## 10. Release-gate checks

Issue #25 的整合檢查由 Maven 測試自動執行：

```sh
mvn -q -Dtest=LoadAcceptanceTest,LoadCrossModeTest,ClosedVuSchedulerTest,FixedArrivalRateSchedulerTest,LoadRuntimeTest,LoadScenarioTest,LoadReportTest,LoadDbPoolingTest,LoadMqPoolingTest,PooledMqHelperExecutorTest,PooledMqTransportFactoryTest test
```

`LoadAcceptanceTest` 會先解析並驗證本目錄全部六個例子，再以真正的 CLI entry point 執行 closed、普通 arrival-rate 及 cap/drop saturation workload，確認 `load-summary.json`、`load-summary.yaml` 和離線 `report/index.html` 都被寫出，並檢查 configured arrival、achieved scheduling、completed TPS、scheduled/started/dropped 會一路保留到最終 report。`LoadCrossModeTest`、`ClosedVuSchedulerTest` 和 `FixedArrivalRateSchedulerTest` 覆蓋相同 component 的跨模式及兩種 scheduler lifecycle；`LoadRuntimeTest` 的 bounded-memory checks 會將 latency reservoir 和一秒 time-series 限制在固定容量；`LoadScenarioTest` 覆蓋 Context deep-copy、iteration workspace、process/file artifact 和 cancellation；`LoadReportTest` 驗證 schema、threshold、secret-safe projection、DB/MQ resource diagnostics 和 HTML；DB/MQ pooling suites 覆蓋 reuse、timeout、exclusive lease、cancellation cleanup 和 deterministic shutdown。

這是可重複的 ATT self-overhead gate，不是 SUT microbenchmark：它檢查每成功 iteration 不產生無界 Case/log churn、Context 不跨 iteration 共享、scheduler lag/metrics 保持有界、pool/resource cleanup 及 report/evidence retention 受策略控制。V1 不承諾 distributed/Poisson/weighted multi-scenario、rendezvous、adaptive pool 或 target CPU/memory benchmarking。
