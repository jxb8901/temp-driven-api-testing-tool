# Load mode

ATT accepts att-load/v1.9 scenarios. A scenario has one or more workloads; each workload uses either one fixed Template, Flow or Tool target, or a closed-user weighted mix of targets. Workloads own their inputs, testdata policy, bootstrap vars and pacing policy. Root defaults may be shared by all workloads, while workload-local fields override them. ATT validates and pre-resolves every configured target before a scheduler starts.

Descriptors using the previous workload schema remain compatible and are normalized to the current schema when loaded.

Run `./att.sh load` with no scenario to discover valid full Load descriptors under `load/`. Only YAML declaring `schemaVersion: att-load/*` is considered; unrelated YAML is ignored, while invalid declared descriptors are shown with their diagnostics. Discovery resolves and validates targets without starting a scheduler or making resource calls.

## Prepare referenced resources before LOAD starts

After target validation and Load plan preparation, ATT discovers only DB, MQ and HTTP helpers reachable from the selected Template, Flow, primary Tool calls, nested Tool calls, and frozen Render payload expressions. It prepares these resources before the scheduler start gate opens. Unused helpers in global configuration are not opened. A reference that cannot be resolved statically is reported as `SKIPPED_DYNAMIC` and remains lazy on first use.

For each referenced DB helper, startup creates distinct physical connections sufficient for `max(1, minIdle)` simultaneous ready sessions. The configured `minIdle` and `maxSize` remain unchanged; normal pool growth stays lazy. MQ readiness creates idle connections for the configured physical instances but does not open queues, send messages or issue business requests. HTTP readiness builds the client and TLS configuration only; it does not connect or send a request.

Preparation is bounded to 64 initial DB/MQ sessions per run and 256 per Worker JVM, with a deadline and cancellation handling. ATT Server admits at most four concurrently executing LOAD Workers per Server process, so Server-submitted LOAD jobs share an aggregate initial-session ceiling of 256. Cleanup keeps each reservation charged until its resource owners finish closing. This scheduler cap covers only LOAD jobs submitted to that Server process; RUN/Debug jobs, CLI or direct Engine processes, and other Server instances are outside its admission budget and can add sessions to a shared backend. Failure stops the run before any iteration is admitted. Live output and structured events report each helper's readiness; the run summary records preparation results. This barrier is LOAD-only and does not replace configured warm-up traffic.

## Independent evidence quotas

`att-load/v1.9` supports two quota models. Legacy scenarios keep their exact previous behavior: an explicit `maxSamples` is one combined run cap, and an explicit `maxFailureSamples` enables shared success/failure caps across the whole run. A scenario that omits both keys also keeps the existing mode defaults, including uncapped-by-default `all`.

The optional `evidence.quotaScope: perWorkload` selects the independent model. It is explicit so an existing scenario is never silently reinterpreted:

~~~yaml
evidence:
  quotaScope: perWorkload
  success: sample
  failure: full
  sampleRate: 0.1
  defaults:
    maxSuccessSamples: 10
    maxFailureSamples: 20
workloads:
  - id: payment-api
    target: {type: template, id: PAYMENT_INVOKE}
    load: {users: 10, duration: 2m}
  - id: mq-processing
    target: {type: tool, id: sample.getAcDate}
    load: {users: 5, duration: 2m}
    evidence:
      maxSuccessSamples: 5
      maxFailureSamples: 30
~~~

In this mode root `success`, `failure`, `sampleRate` and `resources` remain the shared capture policy. `evidence.defaults` supplies each workload's default `maxSuccessSamples` and `maxFailureSamples`; an omitted default is **1,000**. A `workloads[].evidence` object may override either cap, and it accepts only those two cap keys. A partial override inherits the other default. Caps are integers from 0 to 1,000,000; 0 reserves no evidence for that workload and category. In the example, `payment-api` receives 10 success / 20 failure and `mq-processing` receives 5 success / 30 failure.

Per-workload mode must not be mixed with root `maxSamples`, `maxSuccessSamples` or `maxFailureSamples`, and `evidence.defaults` or any workload cap override requires `quotaScope: perWorkload`. Before execution, ATT validates the sum of all effective success caps and the sum of all effective failure caps; either sum may not exceed the aggregate safety budget of 1,000,000. This is a preflight allocation bound, not a runtime global allocator.

Each workload owns its caps. Reaching one workload's success or failure cap never reserves capacity from another workload, and unused capacity stays unused. There is no lending, borrowing, repayment, reclaiming, revocation or quota-driven artifact deletion. A workload's first eligible success or failure is accepted whenever its own cap has room, even if every other workload is already full. Retained evidence is append-only for the run. Reservations are released on cancellation, rejection, PASS-to-FAIL reclassification, dropped/unstarted arrivals or missing evidence. Success and failure capacity remain independent, and exhaustion of one category never suppresses the other.

`sampleRate` applies only to sampled PASS iterations; FAIL/ERROR (including operational TIMEOUT) use the failure cap without sampling. `failure: full` is bounded by the effective failure cap and does not guarantee that every failure is retained. Failure log capture keeps the existing 65,536-character tail, mandatory secret protection and deferred resource formatting.

`load-summary.json` / YAML, the HTML report and Studio job results expose the resolved `evidence.policy`, aggregate retained counts, category exhaustion flags and skipped quota/missing-evidence counters. Per-workload coverage includes the workload and target IDs, effective `maxSuccessSamples` and `maxFailureSamples`, completed PASS iterations, PASS iterations excluded by the sampling hash, retained samples, quota skips and missing evidence. The report and Studio warn when one or more failure quotas are reached; aggregate metrics, thresholds and failure events continue. Retained links remain under `samples/` and `failures/` with EXEC.ID, case.log and case.yaml. Studio separates legacy/shared caps from the explicit per-workload scope, shows effective defaults and workload overrides, and keeps advanced JSON synchronized. See [the per-workload example](../../../examples/load/independent-evidence.yaml).


## Define a Load scenario

~~~yaml
schemaVersion: att-load/v1.9
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
~~~

## Distribute closed-user work across targets

A workload can declare `mix` instead of `target` to distribute each closed user's next iteration across pre-resolved targets. Every entry has a unique `id`, a positive integer `weight`, and a Template, Flow or Tool `target`. The selector uses the run seed, workload ID, stable VU ID and that VU's iteration number; changing targets does not reset the VU or its think-time stream. Weights express selection probability, not a promise that a short run will match the exact ratio.

~~~yaml
schemaVersion: att-load/v1.9
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

Every started iteration receives an engine-managed `EXEC.ID` before bootstrap vars and target execution; `EXEC.RUN_ID` remains the enclosing Run. See [ID initialization](#check-execution-id-fields-before-use).

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

`evidence.mode` accepts `metrics`, `failures`, `samples` or `all`; the default is `failures`. These modes set the default effective success/failure policies to `none/none`, `none/full`, `sample/full` and `full/full`, respectively. Explicit `evidence.success` and `evidence.failure` values override those defaults independently. `sampleRate` and the selected legacy/shared or per-workload caps bound retained evidence. Dropped arrivals do not create iteration evidence.

Case-log capture follows the effective success/failure policies and remaining retention capacity. With failure policy `full` and a `maxSamples` slot available, eligible failures—including unselected successes under `samples`—retain a redacted rolling log tail of at most 65,536 characters. ATT materializes the failure log only when the failure claims a retention slot. If the tail is truncated, a marker identifies the omitted earlier events, and the latest action and runtime failure details remain at the end. Failure logs are not retained after capacity is exhausted. Sampled successes and full-success policies retain full deferred logs. For example, `mode: metrics, failure: full` enables bounded failure capture while capacity remains, while `mode: failures, failure: none` disables it. See [Load scheduler design](../../system-design/load-scheduler.md) for implementation and storage details.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, exact file-content String output and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

## Read Load results and apply thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Direct Engine/CLI `OperationResult` output keeps its legacy full-summary detail shape; its schema definition remains under `schemas/history/`. The archived report emitted by `LoadReportWriter` uses additive `att-load-summary/v1.3` and moves large aggregate/per-workload metric collections into relative `details/v1/` sidecars referenced by `details/v1/manifest.json`. Inline collections have at most 32 preview items, each sidecar chunk is at most 4 MiB, the sidecar set is capped at 100 MiB/9,000 files, and complete report output is capped at 120 MiB; each main report file remains at or below 16 MiB. Aggregate counters and thresholds remain exact. Server-mediated Job Results use `att-load-terminal-result/v1` in `result.summary`: execution outcome, performance verdict, aggregate counters, bounded workload/threshold previews, primary diagnostic and detail/archive state. The compact summary is capped at 32 KiB; measured terminal journal and SSE envelopes stay below 48 KiB, below the unchanged 64 KiB journal event-data limit. The 1 MiB Worker frame limit is also unchanged. Existing LOAD scheduling, virtual-user and arrival-rate semantics do not change.

Full buckets, failure samples, workload details, threshold details and evidence remain in `load-summary.json`, `load-summary.yaml`, `report/index.html` and the versioned sidecars. The Server exposes those through the existing authorized artifact archive routes. Studio shows the compact result first, loads evidence/workload previews when Results is opened, and loads metric-series sidecars when Metrics is opened. No artifact request is made on the initial Job Details load. Archive availability is independent from execution outcome; `PENDING`, `ARCHIVING`, `READY`, `FAILED` or omitted details do not change terminal status or exit code. ERROR and INVALID keep their real runtime/preparation/validation cause ahead of Worker/Journal truncation and archive diagnostics.

Load v1.9 adds event-time windows named `all`, `measured` and `steady`. `measured` starts after warm-up and includes ramp-up, steady and ramp-down; `steady` covers only the configured steady duration. The HTML report opens on `steady` and lets readers select another window. Scheduled, started and completed rates use the timestamp of each event and the configured window duration. `startedRate` therefore remains event-time. `admissionRate` instead uses the same scheduled cohort: arrivals scheduled in the window that start by run finish divided by all arrivals scheduled in that window; arrivals still unstarted at finish count as not admitted. Latency percentiles and outcome counts use the scheduled cohort, so a drained iteration remains attributed to the phase where it was scheduled. Existing top-level metric names and their legacy meanings remain available.

Nested `thresholds.steady` and `thresholds.overall` evaluate against the steady and all-execution windows. Existing top-level thresholds retain their previous measured-run semantics. An incomplete or unevaluable window returns `NOT_EVALUATED`; it is not a performance pass. Reports show execution status separately from the performance verdict and disclose the percentile sample and observation counts so sparse estimates are visible. Direct Engine/CLI summaries use legacy summary contract (schema under `schemas/history/`); archived reports use the additive `att-load-summary/v1.3` schema with optional detail-manifest and workload-sidecar references.

In the summary's `thresholds` object, `status` and `performanceVerdict` are authoritative and use `MET`, `NOT_MET` or `NOT_EVALUATED`. `legacyPassed` is an optional compatibility boolean that retains the previous `passed()` semantics: it is false when configured thresholds are unevaluable and true when no thresholds are configured. Consumers should use the tri-state fields to distinguish incomplete evidence from a missed threshold.

The summary separates generator observations from SUT outcomes. `metrics.generator` includes sampled heap used/committed/maximum, observed peak live threads, GC count/time, and process CPU when the JVM exposes it. Sampling is limited to one observation per 100 ms, so brief peaks may be missed. `schedulerWakeups`, `submitLag*`, and `workerQueueDepth*` describe scheduler pressure; arrival drops remain separate from SUT errors. `resources.http` reports active/idle/waiting and observed peak connections per HTTP helper alongside DB, MQ, and Render pool/plan diagnostics; `resources.resourceMetricSamples` reports the number of rate-limited resource observations. `resources.executionIds` reports custom execution-ID reservation counts. Testdata mapping and selection counts are under `resources.generator.testdata`; interpretation and implementation limits are documented in [Load Generator Telemetry](../../system-design/load-telemetry.md).

`latencySampleCapacity`, `latencySampleCount`, `latencyObservationCount`, and `latencySampleRate` describe the run-level percentile estimate; exact latency aggregates remain exact. The run-level reservoir keeps up to 4,096 samples. Legacy `metrics.phases.*` percentiles keep up to 256 samples per phase, while each configured window's `metrics.windowMetrics.windows.*.cohort.latency` uses a separate 4,096-sample reservoir. Each chart-bin latency estimate under `metrics.windowMetrics.timeSeries.points` keeps up to 128 samples.

The full-execution series at `metrics.windowMetrics.timeSeries.points` stores up to 2,048 populated time bins, initially 1,000 ms wide. When the populated-bin count exceeds that capacity, ATT merges adjacent bins and doubles the bucket width using power-of-two coarsening. It does not evict historical bins, so longer runs retain their history at lower time resolution. The measured and steady series at `metrics.windowMetrics.timeSeries.windowSeries.measured.points` and `metrics.windowMetrics.timeSeries.windowSeries.steady.points` have separate 2,048-bin bounds. They align bins to their configured window start, start with the smaller of 1,000 ms or the window duration, and clip a final partial bucket at the window boundary; they use the same coarsening and no-eviction policy. The compatibility `metrics.buckets` series is separate: it keeps the newest 4,096 one-second buckets in a circular buffer and evicts older entries. ATT does not average per-workload percentiles to calculate the overall percentile.

The Load telemetry fields remain optional in the direct legacy summary contract (schema under `schemas/history/`); archived reports use `att-load-summary/v1.3` and sidecar references. Server Job Results use the separate, versioned `att-load-terminal-result/v1` contract. Consumers that need full metrics should follow `detailManifestPath` and its relative chunk references after the archive reports READY.

For sampling limits and maintainer verification, see [Load Generator Telemetry](../../system-design/load-telemetry.md) and [Compact LOAD terminal result](../../system-design/load-terminal-result.md). The deterministic short-versus-synthetic-long transfer benchmark runs with `mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest#benchmarkShortAndSyntheticLongMultiWorkloadTransferSizes -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test`; it constructs a 30–60-minute-equivalent multi-workload result and does not run a long LOAD.

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

`load/load.yaml` is an optional current `att-load/v1.9` policy-only descriptor with no target, inputs or Tool arguments. It contains the default `load` policy and may also declare `execution`, `thresholds`, `evidence`, `seed` and Load-local `testdata` imports. Explicit CLI pacing values override the policy. `load --debug template|flow|tool <id>` promotes the selected sidecar's `inputs`, `vars` or Tool `arguments` into a transient single-workload scenario and then uses the regular Load validation, scheduler and evidence pipeline; Debug execution is not run first. With no policy, provide a complete CLI policy such as `--users 2 --duration 10s` (arrival-rate also requires `--max-concurrent` and `--overload-policy`).

Example policy descriptor (copy to `load/load.yaml`):

~~~yaml
schemaVersion: att-load/v1.9
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml
./att.sh load ../examples/load/arrival-smoke.yaml --format json
./att.sh load ../examples/load/multi-closed.yaml
./att.sh load ../examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../../../examples/load/README.md). Schema migration is documented in [Migration Notes](../appendices/migrations.md).

## Check execution ID fields before use

ATT allocates one engine-managed `EXEC.ID` per started iteration: `<runId>-execution-<sequence>`. One run-scoped, thread-safe monotonic allocator serves every workload, target, mix entry and phase. The ID is published before bootstrap variables and target actions. `EXEC.RUN_ID` remains the enclosing Run identity. Collision tracking uses no per-iteration files or growing identity registry.

`execution.execIdFormat` is rejected in current and historical Load Scenarios, policy-only files, inline drafts and saved Schedule/Worker snapshots, including an empty value. Remove the field and migrate to `att-load/v1.9`; review artifact-path consumers and store business correlation in a separate input/variable. Existing artifacts keep their original paths. Historical schema files remain available for inspection; historical resources without customization normalize to the current contract. Replace affected immutable schedules through the normal reviewed-draft workflow.

Generated identities retain the existing safe-segment grammar and 240-character limit. The existing Run ID contract (including Unicode and interior spaces) is unchanged. Run IDs outside the ASCII execution-segment grammar use `run-<SHA-256 of UTF-8 Run ID>` as their execution prefix; ASCII-safe Run IDs keep their literal prefix. The enclosing Run directory and `EXEC.RUN_ID` retain the original value. Encoding is computed once per run; every prefix leaves room for `-execution-` plus a signed-long sequence. Exhaustion fails permanently without wrapping. Every Run output namespace is claimed atomically once using `.execution-owner`. A second owner or restart must choose a fresh outer Run ID. Claims remain until safe completed-Job artifact retention removes the Run directory. Old reservation directories must only be removed by safe completed-Job retention; never delete markers while an old process can still use them. Metrics-only evidence remains lazy: this single Run metadata file does not materialize per-iteration workspaces.

Outer `execution.runIdFormat`, `execution.debugIdFormat`, exact `--run-id` and `--debug-id` behavior is unchanged. Diagnostic iteration/VU/Workload/mix IDs and scheduler event sequences remain separate from `EXEC.ID`. Independent distributed Workers must use disjoint namespaces or a shared allocator; separate process-local counters cannot safely share a Run namespace.

## Error rates and failure diagnosis (4.2.4)

Measured completions are divided into PASS, SUT failures, and runtime ERROR/INVALID. Warm-up is excluded from measured rates; it can still make execution ERROR. Generator drops remain separate.

| Metric | Numerator / denominator |
| --- | --- |
| `sutErrorRate` / existing `errorRate` threshold | `measuredFailure / measuredCompleted` |
| `runtimeErrorRate` | `measuredRuntimeError / measuredCompleted` |
| `totalErrorRate` | `(measuredFailure + measuredRuntimeError) / measuredCompleted` |
| `completedThroughput` | all measured completions / measured seconds |
| `successfulThroughput` | measured PASS completions / measured seconds |

Optional `runtimeErrorRate` and `totalErrorRate` thresholds use the same percentage comparisons as `errorRate`, for example `{errorRate: '< 1%', totalErrorRate: '< 1%'}`. `errorRate` remains SUT FAIL-only and excludes runtime errors and drops. Thresholds PASS and Execution ERROR can coexist; neither thresholds nor completed TPS imply successful execution. Rates in JSON/YAML remain numeric zero when no measured completions exist, for compatibility; the HTML report shows N/A. The report summary renders `Thresholds: none configured` when no thresholds exist, or `Thresholds: N passed / M failed` when they do.

The Runtime failures / Failure diagnosis section shows total/measured runtime counters and at most 100 sanitized lightweight failure samples per aggregate, workload or mix collector. Each sample identifies iteration, sequence, phase, timestamp, workload/mix, outcome, classification, diagnostic source/code/summary and evidence state. Counts stay complete after sample limits are reached. When no samples are available, the report says `No lightweight failure samples were recorded.` Scheduler exceptions are distinguished from returned executor failures; public samples omit stack traces, classified secrets and host paths. Retained Case Logs are linked in the Evidence section. `NO_CASE_LOG`, `WORKSPACE_MISSING`, `EVIDENCE_IO_ERROR`, `EVIDENCE_MATERIALIZATION_ERROR` and `NOT_RETAINED_BY_POLICY` explain missing evidence independently of the iteration outcome. An evidence failure is not another failed iteration.

## Target-agnostic Load Templates

Load testing opens **Templates** by default. All six modes use `att-load-template/v1`: `single-vu`, `single-tps`, `single-workload-mix`, `multi-vu`, `multi-tps`, and `multi-workload-mix`. Single VU/TPS reuse Quick Load. Mixed modes use independent weighted iteration selection within each workload; VU pools are never divided by mix weights. Multiple TPS workloads request arrivals independently and report drops and achieved throughput separately. All workloads share the same phase timing envelope.

The **Scenarios** tab retains executable YAML and **Legacy Quick Load Policy** profiles. It excludes `load/templates/` and `load/bindings/`. **View LOAD Jobs** opens Jobs and results with an authorized Server-side LOAD filter and pagination; the filter survives details and browser navigation.

Built-ins use `builtin:<mode>`. Put package templates in `load/templates/` and bindings in `load/bindings/`. Files must be package-relative, schema-valid, at most 256 KiB, and within the bounded catalog scan. Templates contain structural/pacing defaults only, never target IDs or business values. A template cannot execute until every slot is bound. For example:

Catalog inspection pages contain at most 100 items and also honor the configured serialized response budget (256 KiB by default). Follow `nextOffset` until it is null: each page retains complete definitions, so the same query and offset retrieve the corresponding definitions without a separate detail call. A definition that cannot fit even alone is listed as `response-too-large` without its definition; reduce its size for Studio, or compose it directly by file reference in the CLI. CLI `load --list-templates` follows every page automatically and retains the scan-limit `truncated` indicator.

<!-- att-doc-example: current id=multi-workload-load-template schema=att-load-template/v1 format=yaml -->
```yaml
schemaVersion: att-load-template/v1
name: Independent mixed pools
mode: multi-workload-mix
policy:
  load: {users: 10, duration: 10s}
  evidence: {mode: metrics}
workloads:
  - id: workload-a
    mix: [{id: browse, weight: 70}, {id: purchase, weight: 30}]
  - id: workload-b
    load: {users: 20}
    mix: [{id: purchase, weight: 40}, {id: report, weight: 60}]
```

<!-- att-doc-example: current id=multi-workload-load-bindings schema=att-load-bindings/v1 format=yaml -->
```yaml
schemaVersion: att-load-bindings/v1
bindings:
  workload-a:
    mix:
      browse: {type: template, id: BROWSE}
      purchase: {type: flow, id: PURCHASE}
  workload-b:
    mix:
      purchase: {type: flow, id: PURCHASE}
      report: {type: tool, id: REPORT}
```

This creates two workloads, four bindings, three unique targets and 30 VU. Workload and mix slot IDs remain stable when targets repeat or rows move. Fixed modes use `bindings.<workloadId>.target`. Extra workload/mix bindings require an explicit `prototype` naming an existing slot in that scope; they copy its policy, not its target. Missing required slots, unknown prototypes, duplicate IDs and incompatible models fail before traffic. The descriptor limit is 128 workloads/entries; Server aggregate limits may be lower.

The builder summarizes bindings, users/rates and weights. Detailed workload, mix, target and common settings use Save/Cancel dialogs; Cancel or Escape discards tentative changes. Target inputs/vars/Tool arguments retain safe typed sidecar defaults independently for each target. Precedence is template policy, then safe target sidecar business defaults, then target binding values, then workload binding inputs/vars; applicable explicit CLI overrides win. Business maps merge by key, preserving distinct keys and letting the later layer win conflicts. Studio preserves workload-specific timing, ramps and execution settings, including ranged think time, through validation and edits. Debug-local testdata imports are rejected: declare supported Load testdata sources explicitly. Bindings may specify workload inputs, vars and testdata selection as well as target-specific values.

Validate creates a full `att-load/v1.9` immutable draft through the shared Engine composer. Review shows redacted Server-normalized YAML. Any saved edit or environment change invalidates validation; source/revision changes are checked again at submission. Run requires traffic confirmation; Schedule uses the same validated draft and normal permissions, limits, expiry and revision policy. No browser storage or package source writes are used.

**Configure & Rerun** from a LOAD Job uses this standard editor with a server-issued `draftRef`. It prefills the safe retained scenario, environment, workload IDs/mix, model, pacing, timing phases, thresholds, evidence and eligible inputs. Edit the common timing envelope and model-specific fields, then validate against the current Package before reviewing the delta. Current-package rerun does not silently reload latest YAML defaults or convert Arrival Rate to Virtual Users. File-backed LOAD replay remains on its package-relative source route and does not inherit inline-draft-only duration caps; see [Job replay](unified-run.md#replay-and-new-execution-from-a-job).

```bash
./att.sh load --list-templates --format json
./att.sh load --debug template BROWSE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview --format json
# Remove --preview only after reviewing the composed scenario.
```

Use the same arguments with `att.bat` on Windows. List and preview are read-only and start no traffic. `--preview` requires a template; JSON output remains machine-readable. Multi-target mode requires a complete binding descriptor and rejects unscoped `--input`, `--set`, `--users`, `--arrival-rate`, `--think-time`, `--max-concurrent` and `--overload-policy`. Common duration/warmup/ramp timing overrides apply to the shared envelope. Existing no-template `load --debug <type> <id>` and no-argument scenario discovery retain their behavior. Validation errors exit nonzero with field context.
