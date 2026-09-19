# ATT Load Scenario Examples

本目錄的 scenario 使用 `att-load/v1.0`。在 `3.5.0-alpha.1` 中，`att load` 會完成 schema、語義、target 解析及依賴驗證；它會在 scheduler 啟動前停止，尚未執行實際 load scheduler。真正執行 iteration 時，load adapter 使用與普通 run/debug 相同的 `EXEC`/`META` Context，只有 scheduler state 放在 `EXEC.LOAD`。

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
  achievedArrivalRate: ">= 99/s"

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

## 5. 欄位說明

| 路徑 | 必填 | 說明 |
|---|---:|---|
| `schemaVersion` | 是 | 固定為 `att-load/v1.0`。 |
| `target.type` | 是 | `template`、`flow` 或 `tool`。 |
| `target.id` | 是 | 目標 Template 名稱、Flow canonical ID 或 Tool key。 |
| `target.arguments` | 否 | Tool target 的 named arguments；Template/Flow 通常不需要。 |
| `inputs` | 否 | 傳入每個 iteration 的 `EXEC.INPUT.*`；`CASE.*` 只保留為相容 alias。 |
| `load.users` | closed 必填 | 正整數 Virtual User 數量。 |
| `load.arrivalRate` | arrival 必填 | 正數速率，格式為 `number/s` 或 `number/m`。 |
| `load.warmup` / `rampUp` / `duration` / `rampDown` | `duration` 必填 | 整數 duration，例如 `500ms`、`30s`、`5m`、`1h`；`duration` 必須大於零。 |
| `load.maxConcurrent` | arrival 必填 | 大於零的並發上限。 |
| `load.overloadPolicy` | arrival 必填 | V1 只支持 `drop`。 |
| `execution.thinkTime` | 否 | closed iteration 之間的 think time；arrival-rate 禁止。 |
| `thresholds.*` | 否 | `errorRate`/`droppedRate` 用 `%`，`p95`/`p99` 用 `ms`，吞吐率用 `/s` 或 `/m`。 |
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

未來 scheduler 呼叫共用 `IterationExecutor` 時，每個 iteration 都建立獨立 runtime：

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

Human output 顯示 validation pass、model、target 和 scenario path。`--format json` 的 stdout 是一個 JSON document，包含 effective `scenario` 和 resolved `target`；錯誤則返回 exit code `2`，並保留 scenario file、field/path 和建議。

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

修改 scenario 後，先執行 `att load`，再交給後續 scheduler；`att run` 的普通 Excel Case lifecycle 不會因 load scenario 而改變。
