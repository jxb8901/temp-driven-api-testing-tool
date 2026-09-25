### 4.3 Load Mode

ATT Load executes Template, Flow or Tool targets through a bounded load-run lifecycle. `att-load/v1.0` remains the single-target compatibility contract; `att-load/v1.1` adds multiple independently paced workloads in one run.

#### Single-target compatibility (`att-load/v1.0`)

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

`target.type` is `template`, `flow`, or `tool`. Only Tool targets accept `target.arguments`. Scenario `inputs` become each iteration's `EXEC.INPUT`. Existing v1.0 scenarios continue through the original single-workload path.

#### Multi-workload contract (`att-load/v1.1`)

A v1.1 scenario owns one or more named `workloads`. Each workload has a stable `id`, one fixed target, optional inputs/Tool arguments, its own load settings, and optional workload thresholds.

Independent arrival-rate example:

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

These are three independent arrival generators, not one 105/s generator randomly choosing targets. PAYMENT remains paced at 80/s, BALANCE at 20/s and CUSTOMER at 5/s; each workload owns its own `maxConcurrent` and drop behavior.

Closed-VU pool example:

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

Here 60 VUs always execute PAYMENT, 30 always execute BALANCE and 10 always execute ENQUIRY. This is **not** transaction mix: one VU does not switch targets during the run. Transaction-mix selection is a separate feature.

#### v1.1 lifecycle and validation

All workloads in one v1.1 run must use the same scheduler model: either all `arrivalRate` or all `users`. Mixed open/closed workload models are rejected. All workloads must also use the same `warmup`, `rampUp`, `duration` and `rampDown` envelope. This gives all child schedulers one common monotonic T0 and aligned phase windows.

Before scheduling starts, ATT resolves and validates **every** workload target and dependency. If any workload is invalid, no workload begins execution. Workloads share the same run-scoped DB/MQ resource layer so they contend realistically for configured pools, while mutable iteration Context and output remain isolated.

For v1.1, retained `DIAG.load` evidence additionally exposes `workloadId`, `targetType`, and `targetId`. Closed pools retain stable `userId`. Because separate pools may each contain `VU-1`, the durable virtual-user identity is `(workloadId, userId)`. Scheduler diagnostics are not expression Context.

The former expression path `EXEC.LOAD` is no longer public; existing templates that reference it must move business inputs to `EXEC.INPUT` and inspect retained scheduler evidence outside expressions.

#### Workload models

**Closed VU** uses positive `load.users`. One virtual user repeatedly runs its workload's fixed target and waits for completion before its next iteration. `execution.thinkTime` applies between completed iterations.

**Fixed arrival rate** uses `load.arrivalRate` plus positive `maxConcurrent` and `overloadPolicy: drop`. It has no persistent VU identity. Arrivals that cannot start because the workload's concurrency limit is full are recorded as `dropped`; they are not queued and are not counted as SUT errors. Arrival-rate workloads reject `execution.thinkTime`.

`duration` is required. Optional `warmup`, `rampUp`, and `rampDown` define phases; `DIAG.load.phase` evidence identifies `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. Warm-up traffic executes but is excluded from measured threshold aggregates.

#### Closed-VU think time and deterministic randomization

The fixed-duration form remains valid:

```yaml
execution:
  thinkTime: 500ms
```

Closed VUs may alternatively use a uniform range:

```yaml
execution:
  thinkTime:
    min: 500ms
    max: 2s
```

Both endpoints use ATT integer duration syntax (`ms`, `s`, `m`, `h`), are inclusive after millisecond normalization, and `max >= min` is required. A new delay is sampled after each completed iteration. Sleep is clipped to the remaining load envelope and excluded from response latency.

Optional top-level `seed` supplies the run seed; otherwise ATT derives one from `runId`. Each v1.1 closed VU uses a deterministic stream derived from run seed + workload identity + `USER_ID`, preventing cross-workload/shared-RNG coupling. The report-safe summary records the effective seed for randomized think-time runs; individual sampled delays are not durably retained.

Checked-in offline examples are `examples/load/multi-arrival.yaml` and `examples/load/multi-closed.yaml`.

#### CLI overrides

Explicit CLI values continue to replace corresponding v1.0 scenario values:

`--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, `--overload-policy`.

For a v1.1 scenario containing **more than one workload**, these unscoped load-model overrides are ambiguous and are rejected before execution; YAML is authoritative for each workload. A single-workload v1.1 scenario may still use them. `--think-time <duration>` remains fixed-only; range overrides are not encoded into an ad-hoc CLI string.

#### Metrics, thresholds and evidence

A v1.1 report contains both aggregate and per-workload metrics. Aggregate counters and rates combine raw workload events. Aggregate latency percentiles are calculated from the aggregate latency collector; ATT never computes overall P95/P99 by averaging workload percentiles.

Workload thresholds live inside each workload; top-level v1.1 thresholds apply to the aggregate run. Any workload-threshold or global-threshold failure makes the run `FAIL`/exit `1`. A runtime/infrastructure error in any workload makes the run `ERROR`/exit `3`.

Supported thresholds include `errorRate`, `p95`, `p99`, `minThroughput`, `droppedRate`, and `achievedArrivalRate`, subject to workload-model compatibility.

Evidence policy remains run-scoped. For v1.1, retained artifacts are partitioned by workload where applicable. Each retained sample/failure record and its summary link identify the workload, target type and ID, iteration ID, and (for closed users) user ID:

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/<workloadId>/...
├── failures/<workloadId>/...
└── performance.json   # with --profile
```

DB/MQ resource diagnostics remain aggregate/run-scoped. Successful iteration workspaces are not retained by default.

#### Outputs and exit codes

`--profile` measures ATT generator/runtime overhead; it is not a target-host CPU/memory benchmark. Load exit codes are `0` PASS, `1` threshold failure, `2` invalid scenario/configuration/target, and `3` runtime/infrastructure error.

Load execution identity and evidence-only scheduler diagnostics are defined centrally in Chapter 3; artifact schemas and report details are in Chapter 11.
