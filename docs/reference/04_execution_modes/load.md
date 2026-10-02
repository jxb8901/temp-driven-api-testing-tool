### 6.3 Load Mode

ATT accepts att-load/v1.5 scenarios. A scenario has one or more workloads; each workload owns a fixed Template, Flow or Tool target, its inputs, testdata policy, bootstrap vars and pacing policy. Root defaults may be shared by all workloads, while workload-local fields override them. ATT validates the scenario and all targets before a scheduler starts.

Run `./att.sh load` with no scenario to discover valid full Load descriptors under `load/`. Only YAML declaring `schemaVersion: att-load/*` is considered; unrelated YAML is ignored, while invalid declared descriptors are shown with their diagnostics. Discovery resolves and validates targets without starting a scheduler or making resource calls.

#### Scenario shape

~~~yaml
schemaVersion: att-load/v1.5
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK, account: "@{generatedAccounts}", accountId: "@{generatedAccounts.id}"}
    testdata:
      generatedAccounts:
        scope: iteration
        selection: {strategy: sequential, exhaustion: recycle}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
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

A target accepts template, flow or tool; Tool targets may provide named arguments but cannot declare bootstrap vars. Workload inputs become EXEC.INPUT for each iteration; Template/Flow workload vars become a fresh initial EXEC.VARS tree for every started iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

| Workload field | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input values; not bootstrap variables |
| `vars` | initial `EXEC.VARS` | Typed expression tree for Template/Flow; independently evaluated per execution |
| `target.arguments` | Tool arguments | Tool-only call arguments; separate from `EXEC.INPUT` and `EXEC.VARS` |

#### Testdata imports and workload scopes

The environment profile contributes the shared `testdata` descriptor list. A scenario's optional top-level `testdata` list imports package-relative YAML files as a Load-only overlay. A matching local ID replaces the whole environment descriptor for that scenario; records and selection settings are not merged. Duplicate IDs within either layer fail validation.

Use `inputs` to map `@{id}`, `@{id.path}`, or scalar interpolation into `EXEC.INPUT`. Each ID is selected once for a mapping, and the configured `scope` controls how long that choice is reused: `workload`, `user`, or `iteration`. If omitted, Load uses `iteration`. `user` requires a closed-VU workload and is invalid for `arrivalRate`. The workload `testdata` map is policy only; it does not import descriptors. Its optional `selection` object replaces the descriptor's entire selection policy. Policies support `sequential`, `roundRobin`, or seeded `random`, with exhaustion behavior `error` (default), `recycle`, or `stop`. `stop` ends that workload cleanly after its records are consumed. A one-record descriptor needs no selection policy.

Selection evidence records only the testdata ID, source layer, record index, generated sequence where applicable, scope, strategy, and random seed. It never includes the record contents. Run and Debug load only IDs used in mappings; Load validates referenced IDs and explicit workload policies before starting the scheduler.

#### Per-execution bootstrap vars

After the scheduler identity and unique EXEC.ID/EXEC.OUTPUT_DIR are ready, ATT evaluates each workload's `vars` tree before starting its Template or Flow. Exact `${...}` references preserve native types, mixed text becomes a string, `#{...}` uses the ordinary typed expression parser, and nested maps/lists are evaluated recursively. References between vars are declaration-order independent; missing vars and dependency cycles fail before the target starts. Each iteration owns its evaluated maps/lists, so concurrent users and workloads cannot share mutations. The first normal `assign` may replace a bootstrapped variable.

Bootstrap expressions may use initialized `EXEC.RUN_ID`, `EXEC.ID`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.LOAD`, other `EXEC.VARS.<name>` values, and stable project/source/target/template metadata. `EXEC.ACTIONS`, action-local `output`, invocation-scoped metadata, and Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are unavailable. Only safe pure built-ins are permitted. Tool arguments remain separate from `vars`.

#### Workload models

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

duration is required. warmup, rampUp and rampDown default to zero. Warm-up sends real traffic but is excluded from measured threshold aggregates. Optional seed makes closed-VU think-time randomization deterministic.

#### Load identity and output layout

Each started iteration has a unique EXEC.ID across the Load run and shares EXEC.RUN_ID. If execution.execIdFormat is omitted, ATT uses its default run-scoped ID. Otherwise, ATT evaluates it once during initialization with the ordinary ${...} / #{...} engine. Bootstrap vars are evaluated after that identity is published, so they can use EXEC.ID and EXEC.OUTPUT_DIR. Closed workloads can use EXEC.LOAD.USER_ID; arrival-rate cannot. See the execution ID initialization section below for field availability and function restrictions.

Generated IDs must be non-empty, path-safe segments. Duplicate IDs fail before the target starts; ATT does not silently append a suffix.

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── action outputs written under EXEC.OUTPUT_DIR
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

A metrics-only iteration still has EXEC.ID but does not create a per-iteration execution directory unless an operation writes an artifact or a retention decision materializes evidence. EXEC.OUTPUT_DIR remains the logical planned path at executions/<EXEC.ID> while the iteration runs. Retained failures and sampled successes receive an evidence copy under failures/<EXEC.ID>/ or samples/<EXEC.ID>/. The report and evidence summary show EXEC.ID and link to case.log when it exists. Helper resource-output formatting is deferred until retention; explicit Tool evidence collectors still execute because they are author-requested diagnostic operations.

#### Evidence and resource output

`evidence.mode` accepts `metrics`, `failures`, `samples` or `all`; the default is `failures`. These modes set the default effective success/failure policies to `none/none`, `none/full`, `sample/full` and `full/full`, respectively. Explicit `evidence.success` and `evidence.failure` values override those defaults independently. `sampleRate` and `maxSamples` bound retained evidence. Dropped arrivals do not create iteration evidence.

Case-log capture follows the effective success/failure policies and remaining retention capacity before each iteration starts. If the effective failure policy is `full` and a `maxSamples` slot remains available, failures (including unselected successes under `samples`) keep a redacted rolling in-memory tail of at most 65,536 characters; ATT materializes it only when a failure claims a retention slot. Once no failure can be retained because `maxSamples` is zero or exhausted, per-action serialization and buffering are skipped. A slot reserved by an in-flight iteration may conservatively make the scheduler skip capture for other iterations. The latest action and runtime failure details remain at the end of a retained log, after a truncation marker. Selected sampled successes and retained full-success evidence use full deferred case logs. For example, `mode: metrics, failure: full` enables bounded failure capture when capacity remains, while `mode: failures, failure: none` skips failure capture.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, exact project-file String output and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

#### Reports, metrics and thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Top-level thresholds apply only to the aggregate run; workload thresholds apply only to their individual workload. Root thresholds are not inherited into workload thresholds. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

#### CLI and examples

For one workload, options such as --users, --arrival-rate, --warmup, --ramp-up, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

Repeatable `--set` accepts `input.path=value`, Tool-only `arg.name=value`, or Template/Flow-only `vars.path=value`. Values use safe YAML parsing and remain typed; nested maps and numeric list indexes are supported where practical, for example `input.customer.ids[0]=42`. Duplicate assignments apply in order (last wins). ATT expressions are not evaluated during option parsing. Unqualified overrides are rejected for multi-workload scenarios.

`load/load.yaml` is an optional current `att-load/v1.5` policy-only descriptor with no target, inputs or Tool arguments. It contains the default `load` policy and may also declare `execution`, `thresholds`, `evidence`, `seed` and Load-local `testdata` imports. Explicit CLI pacing values override the policy. `load --debug template|flow|tool <id>` promotes the selected sidecar's `inputs`, `vars` or Tool `arguments` into a transient single-workload scenario and then uses the regular Load validation, scheduler and evidence pipeline; Debug execution is not run first. With no policy, provide a complete CLI policy such as `--users 2 --duration 10s` (arrival-rate also requires `--max-concurrent` and `--overload-policy`).

Example policy descriptor (copy to `load/load.yaml`):

~~~yaml
schemaVersion: att-load/v1.5
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../../../examples/load/README.md). Schema migration is documented in [Appendix C](../appendices/migrations.md).

### Load execution ID initialization

Load uses schema att-load/v1.5. If execution.execIdFormat is present, ATT evaluates it once per started iteration with the normal ${...} / #{...} engine during initialization; otherwise the default run-scoped ID remains in effect. Bootstrap vars are evaluated after the generated ID and output path are published.

Available values include EXEC.RUN_ID, timestamps, EXEC.INPUT, EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE, closed-only EXEC.LOAD.USER_ID and the already curated META.PROJECT/SOURCE/TARGET/TEMPLATE. EXEC.ID and EXEC.OUTPUT_DIR are unavailable because the generated ID determines the workspace. No Action has run, so EXEC.ACTIONS and invocation-scoped Flow/Tool/helper META are absent.

Only deterministic, side-effect-free built-ins are allowed. External Tool/DB/MQ/HTTP/SSH calls and stateful, random, clock or filesystem functions are rejected. seq.next() is neither allowed nor required. Use stable identity components:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate has no USER_ID:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

IDs must be non-empty, path-safe single segments and unique within the Load run. Duplicate or unsafe values fail before the target starts; ATT does not append a hidden suffix.


execIdFormat permits deterministic, side-effect-free built-ins only; external calls, seq.next(), random, clock and filesystem functions are rejected. See [Appendix C](../appendices/migrations.md) for schema migration.
