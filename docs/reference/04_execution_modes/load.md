### 4.3 Load Mode

ATT 3.6.0 accepts att-load/v1.2 scenarios. A scenario has one or more workloads; each workload owns a fixed Template, Flow or Tool target and its pacing policy. ATT validates the scenario and all targets before a scheduler starts.

#### Scenario shape

~~~yaml
schemaVersion: att-load/v1.2
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK}
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

A target accepts template, flow or tool; Tool targets may provide named arguments. Workload inputs become EXEC.INPUT for each iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

#### Workload models

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

duration is required. warmup, rampUp and rampDown default to zero. Warm-up sends real traffic but is excluded from measured threshold aggregates. Optional seed makes closed-VU think-time randomization deterministic.

#### Load identity and output layout

Each started iteration has a unique EXEC.ID across the Load run and shares EXEC.RUN_ID. If execution.execIdFormat is omitted, ATT uses its default run-scoped ID. Otherwise, ATT evaluates it once during initialization with the ordinary ${...} / #{...} engine. Closed workloads can use EXEC.LOAD.USER_ID; arrival-rate cannot. See [Runtime and Context Model](../03_runtime_context.md) for field availability and function restrictions.

Generated IDs must be non-empty, path-safe segments. Duplicate IDs fail before the target starts; ATT does not silently append a suffix.

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

A metrics-only iteration still has EXEC.ID but creates no per-execution directory. Workspaces are materialized only for retained failures or sampled successes. The report and evidence summary show EXEC.ID and link to case.log when it exists. Process/API output stays in temporary staging until a retention slot is granted.

#### Evidence and resource output

evidence.mode accepts metrics, failures, samples or all; the default is failures. sampleRate and maxSamples bound retained evidence. Dropped arrivals do not create iteration evidence.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, Render DocumentValue and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

#### Reports, metrics and thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Top-level thresholds apply to the aggregate run; workload thresholds apply to one workload. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

#### CLI and examples

For one workload, options such as --users, --arrival-rate, --warmup, --ramp-up, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../../../examples/load/README.md). Historical v1.0/v1.1 schemas are archived and are not accepted as active versions. Migrate to v1.2 workloads syntax; see [Migrations](../appendices/migrations.md).
