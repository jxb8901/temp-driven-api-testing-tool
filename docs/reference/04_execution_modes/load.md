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

#### Live console progress

`load` streams a start/configuration line, then bounded periodic counters, active work, throughput, and mean latency. Failures, errors, timeouts, and dropped arrivals are reported immediately with rate limiting; successful iterations are never printed one by one. `--quiet` keeps only the final summary and errors. `--format json` keeps JSON on stdout and sends live progress to stderr. The final `load-summary` and report remain authoritative.

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

#### Evidence retention guide

Evidence policy is a run-scoped choice. It controls which completed iteration records and workspaces are retained; it does not change scheduling, pacing, VU identity, `maxConcurrent`, overload/drop behavior, think time, threshold aggregation, or measured latency.

| `mode` | Successful iterations | Failed iterations | Typical use |
|---|---|---|---|
| `metrics` | none | none | Pure performance measurement with the lowest evidence I/O |
| `failures` | none | full | Normal SIT/UAT load testing; the recommended default |
| `samples` | sampled | full | Representative successes plus every failure |
| `all` | full | full | Troubleshooting and short, controlled tests only |

The default is `failures`. It keeps actionable failures while avoiding a Case workspace and evidence file for every successful iteration. `all` is intentionally opt-in: it can materially increase generator disk and I/O usage.

Each mode can be copied directly into a scenario:

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

The explicit equivalent of full retention is:

```yaml
# This explicit form is available in att-load/v1.1.
evidence:
  success: full
  failure: full
```

##### Field semantics and precedence

`mode` supplies the default policy. Explicit `success` and `failure` fields override their corresponding mode-derived value independently; the framework default is `mode: failures` when `evidence` is omitted.

| Field | Values/default | Semantics |
|---|---|---|
| `mode` | `metrics`, `failures`, `samples`, `all`; default `failures` | Selects the success/failure policy shown above. |
| `success` | `none`, `sample`, `full`; omitted means mode-derived | `sample` is the only success policy affected by `sampleRate`; `full` retains every eligible completed success. Explicit `full` requires `att-load/v1.1`; the frozen v1.0 schema accepts `mode: all` but rejects this field value. |
| `failure` | `none`, `full`; omitted means mode-derived | `full` retains eligible completed failures independently of success sampling. |
| `sampleRate` | `0` to `1`; default `0.01` only for `success: sample` | Fraction used for deterministic success sampling. It is ignored, and resolved to `0`, for `none` and `full`. |
| `maxSamples` | Integer `>= 0`; default `1000` for bounded policies | One cap over all retained completed success and failure records. `0` retains none. An explicit value applies to `all` too. |

`mode: all` and `success: full` have no implicit retention cap. Therefore `all` retains every completed success and failure unless `maxSamples` is explicitly configured. For example, `mode: all` with `maxSamples: 1000` is “all eligible records up to the configured cap”, not unlimited retention. Dropped arrivals are scheduler events, not completed iterations, and never create retained iteration evidence.

`maxSamples` is enforced atomically across retained records and in-flight evidence reservations. An eligible success or failure must reserve a slot before its iteration can receive a retained workspace. A completion is retained only when its reservation, policy, and materialized evidence all agree; a candidate that loses eligibility or finishes after the cap is released and its temporary workspace is removed. This keeps retained records from pointing at missing evidence and bounds workspace/evidence overhead to the configured cap, including `mode: failures`.

##### Retained artifacts and sizing

The aggregate result and retained records are separate concerns:

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/<workloadId>/...     # retained successful iterations
├── failures/<workloadId>/...    # retained failed iterations
└── iterations/...               # temporary/retained Case workspaces when needed
```

`load-summary.json`, `load-summary.yaml`, the HTML report, and bounded metrics exist for every completed run. A retained evidence file is a link to an iteration workspace; it is not the aggregate latency/throughput metric. Successful workspaces are normally lazy and are materialized only when the evidence policy retains them. Failure workspaces are also reservation-backed, so `mode: failures` cannot materialize more than the available cap even when many failures finish concurrently. v1.0 uses the single-target `samples/` and `failures/` directories; v1.1 adds `<workloadId>` so records from separate targets cannot be confused.

For a concrete estimate, `10 TPS × 5 minutes` produces about `3,000` scheduled iterations. With `success: sample` and `sampleRate: 0.02`, roughly `60` successful records are eligible before the retention cap. Failure records are evaluated independently by `failure`; they do not consume the success sample rate, although both kinds share the explicit total `maxSamples` cap.

Use `metrics` for the lowest disk/IO overhead, `failures` for normal load tests, and `samples` when representative successful request context is needed. `all` does not alter scheduler semantics or measured latency, but without an explicit cap it can write one workspace and evidence record per completed iteration; avoid it for long, high-TPS, production-like tests unless the resulting disk and generator overhead are acceptable.

##### Closed-VU and arrival-rate examples

The same policy applies to both scheduler models:

```yaml
# Closed VU: stable userId is retained in each selected record.
schemaVersion: att-load/v1.0
target: {type: template, id: PAYMENT}
load: {users: 20, duration: 5m}
evidence: {mode: samples, sampleRate: 0.02, maxSamples: 500}
```

```yaml
# Fixed arrival rate: dropped arrivals remain drops and create no evidence record.
schemaVersion: att-load/v1.0
target: {type: flow, id: PAYMENT_LOOKUP}
load: {arrivalRate: 10/s, duration: 5m, maxConcurrent: 50, overloadPolicy: drop}
evidence: {mode: all, maxSamples: 5000}
```

Evidence policy is evaluated once for the run. It does not turn arrival-rate work into a queue, add a VU identity, change closed-VU think time, change `maxConcurrent`, or turn a generator drop into a SUT error. In v1.1, the same run-scoped policy is applied to every workload and retained files are partitioned by workload ID.

##### Troubleshooting

- **Why are there no successful samples?** The policy may be `metrics`/`failures`, `success` may be `none`, `sampleRate` may be `0`, or the sampled successes may have reached `maxSamples`. Use `mode: samples` with a non-zero rate for bounded inspection.
- **Why do I only see failures?** That is the intended `failures` default. Select `samples` or `all` when successful iteration evidence is required.
- **Why did retained evidence stop after N records?** An explicit `maxSamples` is a total cap across retained success and failure records. An omitted cap defaults to `1000` for bounded policies; `all` has no implicit cap.
- **Does `mode: all` really mean all?** Yes: all completed successful and failed iterations are eligible, unless an explicit `maxSamples` cap is configured. Dropped arrivals are not completed iterations.
- **Does `sampleRate` affect failures?** No. It is consulted only for `success: sample`; failure retention follows `failure`.
- **Why did a failure not get a workspace under `mode: failures`?** The shared cap may already be held by retained or in-flight evidence. Only iterations with a reservation can materialize retained evidence.
- **Do dropped arrivals create retained evidence?** No. They remain scheduler metrics/events and are excluded from retained iteration evidence.
- **Where is one workload's evidence?** v1.0 uses `samples/` or `failures/`; v1.1 uses `samples/<workloadId>/` or `failures/<workloadId>/`. Follow the relative path in `load-summary.json` or the report.

##### Migration note for `mode: all`

Previous ATT behavior treated `mode: all` like sampled successes plus full failures, using the default success `sampleRate`. The corrected behavior is full successes plus full failures, with no implicit cap unless `maxSamples` is explicitly configured. Existing scenarios that used `mode: all` while relying on low success sampling may therefore create substantially more evidence; review disk budget and use `samples` when representative successes are sufficient.

##### Schema migration for explicit full success

The historical `att-load/v1.0` schema is frozen. Its `evidence.success` enum remains `none` or `sample`; `success: full` is intentionally rejected even though the runtime policy supports full success retention. A v1.0 scenario that needs full success evidence should keep `evidence: {mode: all}` or migrate to v1.1 by changing `schemaVersion` to `att-load/v1.1`, moving `target`, `inputs`, and `load` under one workload such as `workloads: [{id: default, ...}]`, and then using the explicit `success: full` field.

#### MQ payload files in lazy Load workspaces

An MQ `file` argument may use an absolute path to a regular file inside the ATT package. In Load mode this file is validated against the package root, so it works even when the current iteration workspace has not been materialized. ATT does not create an empty workspace merely to validate that project payload.

Relative MQ payload paths remain Case-output scoped: `..` traversal is rejected, symlink payloads and symlink escapes are rejected, and the resolved file must be a safe regular file. A missing payload reports the payload path problem directly. These rules are local path validation and occur before MQ connect/open/put/get; they do not change queue configuration, response parsing, pacing, or evidence retention.
