### 4.3 Load Mode

Load repeatedly executes one Template, Flow or Tool target from an `att-load/v1.0` scenario.

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

`target.type` is `template`, `flow`, or `tool`. Only Tool targets accept `target.arguments`. Scenario `inputs` become each iteration's `EXEC.INPUT`.

#### Workload models

**Closed VU** uses positive `load.users`. One virtual user repeatedly runs iterations and keeps a stable `EXEC.LOAD.USER_ID`; `execution.thinkTime` applies between its iterations.

**Fixed arrival rate** uses `load.arrivalRate` plus positive `maxConcurrent` and V1 `overloadPolicy: drop`. It has no persistent VU identity. Arrivals that cannot start because the concurrency limit is full are recorded as `dropped`; they are not queued and are not counted as SUT errors.

The models are mutually exclusive. `duration` is required. Optional `warmup`, `rampUp`, and `rampDown` define phases; `EXEC.LOAD.PHASE` identifies `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. Warm-up traffic executes but is excluded from measured threshold aggregates.

#### CLI overrides

Explicit CLI values replace the corresponding scenario values and the effective scenario is validated again:

`--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, `--overload-policy`.

#### Metrics, thresholds and evidence

Load records bounded metrics including iteration success/failure, latency percentiles and throughput. Arrival-rate runs additionally track scheduled/started/completed/dropped and achieved arrival rate. Supported thresholds include `errorRate`, `p95`, `p99`, `minThroughput`, `droppedRate`, and `achievedArrivalRate`.

Evidence policy controls successful samples and failure retention. Success workspaces are not retained by default; failures/samples are materialized only as required by policy. DB/MQ pools and load-run resource ownership are shared at the load-run resource layer, while mutable `EXEC.VARS`, `EXEC.ACTIONS`, Tool transient state and per-iteration output remain isolated.

#### Outputs and exit codes

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/      # when retained
├── failures/     # when retained
└── performance.json   # with --profile
```

`--profile` measures ATT generator/runtime overhead; it is not a target-host CPU/memory benchmark. Load exit codes are `0` PASS, `1` threshold failure, `2` invalid scenario/configuration/target, and `3` runtime/infrastructure error.

`EXEC.LOAD` is defined centrally in Chapter 3; artifact schemas and report details are in Chapter 11.
