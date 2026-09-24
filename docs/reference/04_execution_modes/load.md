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

**Closed VU** uses positive `load.users`. One virtual user repeatedly runs iterations and keeps a stable `EXEC.LOAD.USER_ID`; `execution.thinkTime` applies between its completed iterations.

**Fixed arrival rate** uses `load.arrivalRate` plus positive `maxConcurrent` and V1 `overloadPolicy: drop`. It has no persistent VU identity. Arrivals that cannot start because the concurrency limit is full are recorded as `dropped`; they are not queued and are not counted as SUT errors. Arrival-rate scenarios reject `execution.thinkTime` in either scalar or range form.

The models are mutually exclusive. `duration` is required. Optional `warmup`, `rampUp`, and `rampDown` define phases; `EXEC.LOAD.PHASE` identifies `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. Warm-up traffic executes but is excluded from measured threshold aggregates.

#### Closed-VU think time and deterministic randomization

The original fixed-duration form remains unchanged:

```yaml
execution:
  thinkTime: 500ms
```

Closed VUs may alternatively use a uniform range:

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

Both `min` and `max` use the normal ATT integer duration syntax (`ms`, `s`, `m`, `h`). Values are normalized to integer milliseconds; both endpoints are inclusive, `min` may be zero, and `max` must be greater than or equal to `min`. Equal bounds normalize to the same fixed policy as the scalar form. Unknown range fields and missing bounds are invalid.

A range is sampled once after each completed iteration before that VU starts its next iteration. Sleep is clipped to the remaining load envelope, so think time never extends the run beyond its configured end. Think time starts only after the target completion timestamp and is therefore excluded from target response latency and latency percentiles.

Randomized load generation uses one effective run seed. Optional top-level `seed` supplies it explicitly; otherwise ATT derives a stable effective seed from the load `runId`. Each VU gets its own deterministic stream derived from the run seed, workload identity, and `USER_ID`; there is no shared mutable RNG across VU threads. The report-safe load summary records `effectiveSeed` for a randomized think-time run and reports the normalized policy as `fixed: 500ms` or `uniform: 500ms..2s`. ATT does not persist every sampled delay.

The same seed and same VU/workload identity replay the same random sequence. This run-level facility is intentionally separate from think-time YAML so later randomized workload selectors can reuse it instead of creating another RNG scheme. A complete offline example is [`examples/load/closed-random-think.yaml`](../../../examples/load/closed-random-think.yaml).

The sampled delay is scheduler behavior and does not add a public Context field or root.

#### CLI overrides

Explicit CLI values replace the corresponding scenario values and the effective scenario is validated again:

`--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, `--overload-policy`.

`--think-time <duration>` continues to mean a fixed duration. Random ranges are YAML-only in V1; the CLI flag is deliberately not overloaded with an ad-hoc range string. Applying `--think-time` to a YAML range replaces it with the fixed CLI duration.

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
