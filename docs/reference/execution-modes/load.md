# Load mode

ATT accepts att-load/v1.6 scenarios. A scenario has one or more workloads; each workload uses either one fixed Template, Flow or Tool target, or a closed-user weighted mix of targets. Workloads own their inputs, testdata policy, bootstrap vars and pacing policy. Root defaults may be shared by all workloads, while workload-local fields override them. ATT validates and pre-resolves every configured target before a scheduler starts.

Descriptors using the previous workload schema remain compatible and are normalized to the current schema when loaded.

Run `./att.sh load` with no scenario to discover valid full Load descriptors under `load/`. Only YAML declaring `schemaVersion: att-load/*` is considered; unrelated YAML is ignored, while invalid declared descriptors are shown with their diagnostics. Discovery resolves and validates targets without starting a scheduler or making resource calls.

## Define a Load scenario

~~~yaml
schemaVersion: att-load/v1.6
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

## Distribute closed-user work across targets

A workload can declare `mix` instead of `target` to distribute each closed user's next iteration across pre-resolved targets. Every entry has a unique `id`, a positive integer `weight`, and a Template, Flow or Tool `target`. The selector uses the run seed, workload ID, stable VU ID and that VU's iteration number; changing targets does not reset the VU or its think-time stream. Weights express selection probability, not a promise that a short run will match the exact ratio.

~~~yaml
schemaVersion: att-load/v1.6
seed: 73
workloads:
  - id: checkout
    inputs: {region: HK}
    mix:
      - id: browse
        weight: 60
        target: {type: template, id: BROWSE}
        inputs: {operation: browse}
      - id: purchase
        weight: 30
        target: {type: flow, id: PURCHASE}
        inputs: {operation: purchase}
      - id: report
        weight: 10
        target: {type: tool, id: REPORT, arguments: {format: csv}}
    load: {users: 20, duration: 1m}
~~~

Workload `inputs` and `vars` provide defaults; an entry's same-named top-level keys replace those defaults. Tool arguments stay under that entry's `target.arguments`, and Tool entries cannot declare `vars`. A mix is closed-model only. Each completed iteration waits its normal think time before the next target is selected. `EXEC.LOAD.MIX_ID`, `TARGET_TYPE` and `TARGET_ID` are available before execution ID expressions, testdata and bootstrap vars are evaluated. Testdata selection state remains scoped to the workload/VU when targets change.

The run summary and HTML report include configured weights, observed selection counts, and bounded per-entry metrics. Overall and workload percentiles are calculated from their own aggregate latency collectors; ATT does not average per-entry percentiles to produce them. Events and retained evidence include the selected `mixId` and target identity.

A target accepts template, flow or tool; Tool targets may provide named arguments but cannot declare bootstrap vars. Workload inputs become EXEC.INPUT for each iteration; Template/Flow workload vars become a fresh initial EXEC.VARS tree for every started iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

| Workload field | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input values; not bootstrap variables |
| `vars` | initial `EXEC.VARS` | Typed expression tree for Template/Flow; independently evaluated per execution |
| `target.arguments` | Tool arguments | Tool-only call arguments; separate from `EXEC.INPUT` and `EXEC.VARS` |

ATT snapshots each iteration's input map once as a deeply immutable tree. Request metadata copies reuse that snapshot, and the Load adapter passes its nested values through into the per-iteration `EXEC.INPUT` map without copying them again. A later change to the caller's source map cannot affect a started iteration, and separate iterations do not share their input snapshots.

## Map inputs and choose testdata

The environment profile contributes the shared `testdata` descriptor list. A scenario's optional top-level `testdata` list imports package-relative YAML files as a Load-only overlay. A matching local ID replaces the whole environment descriptor for that scenario; records and selection settings are not merged. Duplicate IDs within either layer fail validation.

Use `inputs` to map `@{id}`, `@{id.path}`, or scalar interpolation into `EXEC.INPUT`. Each ID is selected once for a mapping, and the configured `scope` controls how long that choice is reused: `workload`, `user`, or `iteration`. If omitted, Load uses `iteration`. `user` requires a closed-VU workload and is invalid for `arrivalRate`. The workload `testdata` map is policy only; it does not import descriptors. Its optional `selection` object replaces the descriptor's entire selection policy. Policies support `sequential`, `roundRobin`, or seeded `random`, with exhaustion behavior `error` (default), `recycle`, or `stop`. `stop` ends that workload cleanly after its records are consumed. A one-record descriptor needs no selection policy.

Selection evidence records only the testdata ID, source layer, record index, generated sequence where applicable, scope, strategy, and random seed. It never includes record contents. Run and Debug load only IDs used in mappings; Load validates referenced IDs and explicit workload policies before starting.

## Initialize Template and Flow variables

After the scheduler identity and unique EXEC.ID/EXEC.OUTPUT_DIR are ready, ATT evaluates each workload's `vars` tree before starting its Template or Flow. Exact `${...}` references preserve native types, mixed text becomes a string, `#{...}` uses the ordinary typed expression parser, and nested maps/lists are evaluated recursively. References between vars are declaration-order independent; missing vars and dependency cycles fail before the target starts. Each iteration owns its evaluated maps/lists, so concurrent users and workloads cannot share mutations. The first normal `assign` may replace a bootstrapped variable.

Bootstrap expressions may use initialized `EXEC.RUN_ID`, `EXEC.ID`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.LOAD`, other `EXEC.VARS.<name>` values, and stable project/source/target/template metadata. `EXEC.ACTIONS`, action-local `output`, invocation-scoped metadata, and Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are unavailable. Only safe pure built-ins are permitted. Tool arguments remain separate from `vars`.

## Choose a closed-user or arrival-rate workload

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

Closed workloads preserve the configured number of virtual users while iterations run synchronously. For scheduler and worker-pool implementation details, see [Load scheduler design](../../system-design/load-scheduler.md).

## Set pacing and size Resource pools

For a steady arrival rate, estimate average in-flight requests with Little's law:

```text
average concurrency ≈ arrival rate (requests/second) × mean response time (seconds)
```

For example, 20 HTTP requests/second at a mean 1.5-second response time needs about 30 concurrent connections to avoid the client pool becoming the limiting factor. HTTP defaults to `pool.maxConnections: 50` and `pool.maxConnectionsPerRoute: 20`; raise both as needed for the workload, keeping the per-route value no greater than the total. Add headroom for latency variation and other routes, then confirm with `resources.http` active/idle/waiting/peak observations. Pool capacity is a generator-side ceiling, not a recommendation to send that load to an unverified service.

For DB work, use the connection-lease time in the same estimate: DB operations/second × mean time a borrowed connection stays in use. For example, 40 operations/second with a 100 ms mean lease needs about four concurrent connections. Set `pool.maxSize` for each DB helper to that concurrency plus headroom, then check its `resources.db` active/idle/waiting, borrow-timeout and total-borrow-wait metrics. The default maximum is 20 per helper; it is a client-side limit, not a target database capacity recommendation.

For MQ request/reply, 10 requests/second with a mean 3-second reply time likewise needs about 30 leased connections across the workload. Size `pool.maxSize` for the expected in-flight requests **per physical MQ instance**, not per logical helper. With a single instance (or calls pinned to one instance), that is about 30 plus headroom. With two evenly selected instances, it is about 15 per instance on average; account for selection skew and verify the actual distribution. `resources.mq` reports pool metrics per physical instance, so inspect each pool's waiting and timeout metrics alongside response latency. `minIdle` is applied when that physical pool is first created on use; it does not create or warm the pool before Load starts. To move connection creation out of measured steady-state latency, combine `minIdle` with a Load warm-up/first-use warm-up phase. Recalculate from observed mean latency as load changes; tail latency is useful for headroom, but is not the mean used by the estimate.

duration is required. warmup, rampUp and rampDown default to zero. Warm-up sends real traffic but is excluded from measured threshold aggregates. Optional seed makes closed-VU think-time randomization deterministic.

## Identify iterations and find their artifacts

Each started iteration has a unique `EXEC.ID` across the Load run and shares `EXEC.RUN_ID`. If `execution.execIdFormat` is omitted, ATT uses its default run-scoped ID. Otherwise, ATT evaluates the configured format once during initialization with the ordinary `${...}` / `#{...}` engine. Bootstrap vars are evaluated after that identity is published, so they can use `EXEC.ID` and `EXEC.OUTPUT_DIR`. Closed workloads can use `EXEC.LOAD.USER_ID`; arrival-rate cannot. See [Check execution ID fields before use](#check-execution-id-fields-before-use) for field availability and function restrictions.

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

A metrics-only iteration still has `EXEC.ID` but does not create a per-iteration execution directory unless an operation writes an artifact or retention materializes evidence. `EXEC.OUTPUT_DIR` remains the logical planned path at `executions/<EXEC.ID>` while the iteration runs. Retained failures and sampled successes receive an evidence copy under `failures/<EXEC.ID>/` or `samples/<EXEC.ID>/`. The report and evidence summary show `EXEC.ID` and link to `case.log` when it exists. Optional Resource output formatting is deferred until an iteration is retained; explicit Tool evidence collectors still run because they are part of the requested scenario.

## Choose which iteration evidence to retain

`evidence.mode` accepts `metrics`, `failures`, `samples` or `all`; the default is `failures`. These modes set the default effective success/failure policies to `none/none`, `none/full`, `sample/full` and `full/full`, respectively. Explicit `evidence.success` and `evidence.failure` values override those defaults independently. `sampleRate` and `maxSamples` bound retained evidence. Dropped arrivals do not create iteration evidence.

Case-log capture follows the effective success/failure policies and remaining retention capacity. With failure policy `full` and a `maxSamples` slot available, eligible failures—including unselected successes under `samples`—retain a redacted rolling log tail of at most 65,536 characters. ATT materializes the failure log only when the failure claims a retention slot. If the tail is truncated, a marker identifies the omitted earlier events, and the latest action and runtime failure details remain at the end. Failure logs are not retained after capacity is exhausted. Sampled successes and full-success policies retain full deferred logs. For example, `mode: metrics, failure: full` enables bounded failure capture while capacity remains, while `mode: failures, failure: none` disables it. See [Load scheduler design](../../system-design/load-scheduler.md) for implementation and storage details.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, exact file-content String output and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

## Read Load results and apply thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

The summary separates generator observations from SUT outcomes. `metrics.generator` includes sampled heap used/committed/maximum, observed peak live threads, GC count/time, and process CPU when the JVM exposes it. Sampling is limited to one observation per 100 ms, so brief peaks may be missed. `schedulerWakeups`, `submitLag*`, and `workerQueueDepth*` describe scheduler pressure; arrival drops remain separate from SUT errors. `resources.http` reports active/idle/waiting and observed peak connections per HTTP helper alongside DB, MQ, and Render pool/plan diagnostics; `resources.resourceMetricSamples` reports the number of rate-limited resource observations. `resources.executionIds` reports custom execution-ID reservation counts. Testdata mapping and selection counts are under `resources.generator.testdata`; interpretation and implementation limits are documented in [Load Generator Telemetry](../../system-design/load-telemetry.md).

`latencySampleCapacity`, `latencySampleCount`, `latencyObservationCount`, and `latencySampleRate` describe the run-level percentile estimate; exact latency aggregates remain exact. Time-series output retains the newest 4,096 one-second buckets. ATT does not average per-workload percentiles to calculate the overall percentile.

The new Load summary telemetry fields are optional under `att-load-summary/v1.1`; current writers emit them, and summaries produced before this telemetry was added remain valid.

For sampling limits and maintainer verification, see [Load Generator Telemetry](../../system-design/load-telemetry.md).

Top-level thresholds apply only to the aggregate run; workload thresholds apply only to their individual workload. Root thresholds are not inherited into workload thresholds. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

## Cancel a Load run

Cancellation stops new admissions and interrupts admitted iterations. ATT drains completion events for admitted work and accounts for those outcomes before finalizing workload results and metrics.

## Understand payload changes during a Load run

Before a Load workload starts, ATT resolves each reachable Render payload glob once and freezes the matched UTF-8 source content for that run. A payload edit, replacement, or new glob match made while the run is active does not affect its iterations; the next Load run resolves the package again. Normal Run and Debug use a fresh plan for each execution, so edits are picked up by the next execution.

Each iteration evaluates Context references, built-in calls, and external calls against its own Context. Stateful calls such as `seq.next()`, clock/random functions, and external calls run for each iteration. Render returns its String in memory, so passing `ACTIONS.<id>.output.result` to a downstream action does not create an intermediate Render file. Use `EXEC.OUTPUT_DIR` only when an operation explicitly needs a file.

With `--profile`, `performance.json` records `renderPlansCompiled`, `renderPlanCacheHits`, `renderPayloadResolutions`, `renderPayloadResolutionCacheHits`, `renderEvaluations`, `renderArtifactWrites`, and `renderSourceBytes`. See [Load scheduler design](../../system-design/load-scheduler.md) for how Load prepares plans and reuses immutable source data across iterations.

Load startup also compiles the selected Template/Flow action sequence, primary Tool calls and argument expressions, `runWhen`, assertions, and `retry.when`. Each iteration evaluates that immutable plan against its own Context. Testdata input mappings are likewise compiled during target validation; descriptors and effective workload policies are prepared once, while record selection and Context values remain iteration-specific. The run summary's `resources.execution` contains `executionPlansCompiled`, `actionPlansCompiled`, and `actionEvaluations`.

## Override workload settings from the CLI

For one workload, options such as --users, --arrival-rate, --warmup, --ramp-up, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

Repeatable `--set` accepts `input.path=value`, Tool-only `arg.name=value`, or Template/Flow-only `vars.path=value`. Values use safe YAML parsing and remain typed; nested maps and numeric list indexes are supported where practical, for example `input.customer.ids[0]=42`. Duplicate assignments apply in order (last wins). ATT expressions are not evaluated during option parsing. Unqualified overrides are rejected for multi-workload scenarios.

`load/load.yaml` is an optional current `att-load/v1.6` policy-only descriptor with no target, inputs or Tool arguments. It contains the default `load` policy and may also declare `execution`, `thresholds`, `evidence`, `seed` and Load-local `testdata` imports. Explicit CLI pacing values override the policy. `load --debug template|flow|tool <id>` promotes the selected sidecar's `inputs`, `vars` or Tool `arguments` into a transient single-workload scenario and then uses the regular Load validation, scheduler and evidence pipeline; Debug execution is not run first. With no policy, provide a complete CLI policy such as `--users 2 --duration 10s` (arrival-rate also requires `--max-concurrent` and `--overload-policy`).

Example policy descriptor (copy to `load/load.yaml`):

~~~yaml
schemaVersion: att-load/v1.6
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

Copyable examples and field descriptions are maintained in [examples/load/README.md](../../../examples/load/README.md). Schema migration is documented in [Migration Notes](../appendices/migrations.md).

## Check execution ID fields before use

The outer Load directory uses the same `execution.runIdFormat` policy as Run when `--run-id` is absent. `--run-id` remains an exact literal override for the outer Load identity. This policy is separate from `execution.execIdFormat`, which continues to generate each started iteration's `EXEC.ID`.

Load uses schema att-load/v1.6. If execution.execIdFormat is present, ATT evaluates it once per started iteration with the normal ${...} / #{...} engine during initialization; otherwise the default run-scoped ID remains in effect. Bootstrap vars are evaluated after the generated ID and output path are published.

Available values include EXEC.RUN_ID, timestamps, EXEC.INPUT, EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE, closed-only EXEC.LOAD.USER_ID and the already curated META.SOURCE/TARGET/TEMPLATE. EXEC.ID and EXEC.OUTPUT_DIR are unavailable because the generated ID determines the workspace. No Action has run, so EXEC.ACTIONS and invocation-scoped Flow/Tool/helper META are absent.

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


execIdFormat permits deterministic, side-effect-free built-ins only; external calls, seq.next(), random, clock and filesystem functions are rejected. See [Migration Notes](../appendices/migrations.md) for schema migration.
