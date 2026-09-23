## 07 Expressions and Built-ins

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 07 Expression Reference

#### Unified expression engine

V3.4 uses one engine with two deliberately separate roles:

- `${path}` reads one Context value and interpolates it into surrounding text, for example `Reference=${EXEC.VARS.SrcRefNo}`.
- `#{expression}` evaluates one typed expression block. The block may contain Context operands, calls, list literals, parentheses, unary operators, arithmetic, comparisons, `like`, `in`, null tests, and boolean logic.

Context references remain explicit inside a block; write `${EXEC.INPUT.amount}`, never bare `CASE.amount`. Append `?` to make the entire reference optional, for example `${EXEC.INPUT.response.body.missing?}`. If any map, list, root-owned Context value, or intermediate segment is missing, the result is the real `null`; an existing final `null` also remains `null`. `${path}` remains strict. Optional lookup does not suppress ambiguity, malformed syntax, or invalid traversal such as indexing a scalar, so those authoring errors still fail. Exact blocks preserve their Java result type, while a block embedded in surrounding text is converted to text.

```yaml
assert: "#{${EXEC.INPUT.response.body.missing?} is null}"
actual: "#{nvl(${EXEC.INPUT.response.body.missing?}, 'not supplied')}"
description: "status=${EXEC.INPUT.response.body.status?}; fallback=#{coalesce(${EXEC.INPUT.response.body.missing?}, 'N/A')}"
```

```yaml
assert: >-
  #{(${EXEC.INPUT.amount} * ${EXEC.INPUT.rate}) >= 100
    and ${EXEC.INPUT.status} in ['PENDING', 'POSTED']}
description: "Reference length: #{length(${EXEC.VARS.SrcRefNo})}"
expression: "#{${EXEC.ACTIONS.query.output.result.rowCount} + 1}"
```

Operator precedence from highest to lowest is:

1. parentheses, literals, `${...}`, lists, and calls;
2. unary `+`, unary `-`, and `not`;
3. `*` and `/`;
4. `+` and `-`;
5. `== != > >= < <=`, `like`, `in`, and `is [not] null`;
6. `and`;
7. `or`.

Arithmetic operands must be numeric and division by zero is an error. `in` requires a List, array, or Iterable right operand; a literal list such as `['A', 'B']` and a typed Context list such as `${EXEC.INPUT.allowedStatuses}` are valid. The legacy non-block assertion grammar also accepts literal-list `in`, but arithmetic and typed list membership should use `#{...}`.

Call arguments may themselves be any expression. Calls can be nested directly, for example `#{upper(trim(${EXEC.INPUT.name}))}`; the older nested-block spelling `#{upper(#{trim(${EXEC.INPUT.name})})}` remains accepted. Single/double ASCII quotes and paired typographic quotes delimit strings. Numeric, boolean, and null literals retain their types. Other unquoted tokens are literal strings unless they look like reserved Context paths or a visible scoped variable, in which case ATT requires `${...}`.

Context interpolation within surrounding text still uses `${...}`: write `prefix-${EXEC.INPUT.caseId}` or `#{concat('prefix-', ${EXEC.INPUT.caseId})}`. Unique-suffix lookup remains available only inside `${...}`, although canonical paths such as `${EXEC.VARS.SrcRefNo}` are preferred.

For backward compatibility, an unquoted Tool-call argument shaped like `${directory}/file.name` remains text interpolation rather than numeric division. New numeric division such as `${EXEC.INPUT.amount}/2` remains arithmetic; quote path-like values in new configuration when practical.

The available values and callable capabilities still depend on the location's scope:

| Expression-bearing location | `${...}` scope | Built-in `#{...}` | Configured Tool `#{...}` | DB `db.*` query | Evaluation point |
|---|---|---:|---:|---:|---|
| render payload content | Runtime Context | Yes | Yes | Yes | Before payload parsing/writing |
| action `description` | Runtime Context including current `output` | Yes | Yes | Yes | After normal action completion |
| action `assert` | Runtime Context including current `output` | Yes | Yes | Yes | After the action result is published locally |
| assert-action `expected` | Runtime Context before current output | Yes | Yes | Yes | Before the assert action |
| assert-action `actual` | Runtime Context including current `output` | Yes | Yes | Yes | After assertion evaluation |
| log-action `message`, `file`, and `fields` values | Runtime Context before current output | Yes | Yes | Yes | Before reading/emitting the optional file |
| assign-action `expression` | Runtime Context before current output | Yes | Yes | Yes, typed for an exact call | Before publishing `EXEC.VARS.<name>` |
| Tool-action `call` | Runtime Context before current output | Yes, including as the primary call | Yes | Yes inside arguments | As the action's primary invocation |
| Tool/DB-action `saveAs.path` | Runtime Context before current output | Yes | Yes | Yes | Before the primary Tool/JDBC invocation |
| DB-action `query/update.params` | Runtime Context before current output | Yes | Yes | Yes | Before primary JDBC binding |
| DB-action `query/update.sql` or `sqlFile` content | Runtime Context before current output | Pure built-ins only | No | No | Before JDBC prepare |
| `config.report.fileNamePattern` | `${suiteName}` | Yes | No | No | When writing the result workbook |
| Tool-definition `command` tokens | declared Tool-input `${...}` aliases | Yes | No | No | When constructing logical argv |
| Tool-definition `call` | declared typed `${input.*}` only | Pure built-ins | No configured Tool chaining | One primary DB query/scalar/update | When invoking the façade |

For a `type: tool` action, the outer `call` may name either a configured Tool or an ATT built-in. A primary built-in runs in a bounded daemon executor and publishes its value at `${output.result}`; it has `exitCode: 0`, supports timeout, Action assertion/retry, and optional `saveAs`, and records `type: builtin` attempt evidence without a `TOOL` process node, argv, stdout, or stderr. Built-ins, command-backed Tools, call-backed READ Tools, and direct read-only DB queries may be used inside ordinary Case-runtime expressions. A call-backed DB update is restricted to the primary call of a Tool Action. Configured Tool and DB calls remain unavailable in `fileNamePattern`, Tool `command`, and DB SQL-source rendering because those dedicated scopes cannot safely contain hidden or recursive external execution.

```yaml
normalizeReference:
  type: tool
  call: "#{upper(${EXEC.INPUT.reference})}"
  saveAs:
    path: "normalized-reference.txt"
    format: text
  assert: "${output.result} == 'PAY-001'"
```

`#{...}` is not restricted to text replacement. An exact block retains its typed result and is evaluated before the Action consumes it. Therefore both of these are valid:

```yaml
assert: "#{length(value=${EXEC.VARS.SrcRefNo})} <= 35"
assert: "#{${EXEC.INPUT.status} in ${EXEC.INPUT.allowedStatuses}}"
```

The first block returns a Boolean directly; ATT does not stringify and reparse it. A configured Tool or DB query called from a Case-runtime field is a real external invocation and produces evidence; do not use either merely for formatting when a built-in or existing Context value is sufficient.

#### Runtime Context

The execution-neutral Context has two canonical roots and one Action-local binding:

```text
EXEC
├── ID, MODE, STARTED_AT, OUTPUT_DIR
├── INPUT (TestCase data or debug sidecar input)
├── VARS (typed variables shared by later stages/templates)
└── ACTIONS (completed/published Action results)
META
├── PROJECT, SOURCE, TARGET
├── TEMPLATE, FLOW
└── TOOL, DBHELPER, MQHELPER (curated invocation metadata)
output
└── current Action/attempt-local result; unavailable outside that Action scope
```

`EXEC.MODE` is `testcase`, `debug`, or `load`. `EXEC.LOAD` exists only when `EXEC.MODE=load`; ordinary TestCase and debug execution do not materialize it. `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` are the same mutable runtime state used by all modes, not parallel copies. The TestCase adapter overlays current Stage caller/input values onto `EXEC.INPUT` for the active Stage; Stage values win over Case-level values on collision and the Case-level values are restored after the Stage. Framework-owned fields such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` cannot be overwritten by Case or sidecar input. There is intentionally no `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.OUTPUT`, `EXEC.CALL`, `EXEC.INVOCATION`, `EXEC.STAGE`, or `EXEC.STAGES`: helper/resource state remains internal, root-level `TOOL.*` / `DB.*` remain compatibility or transient views, and Action result/evidence is consumed through local `output` while active and `EXEC.ACTIONS` after publication. Stage/Template status, timing, and history remain in the execution result/evidence model and legacy `CASE.STAGES`. The `att-load/v1.0` adapter adds the load-only `EXEC.LOAD` namespace described below.

#### Load V1 Context (3.5.1)

Each load iteration uses the same `EXEC`/`META` tree and action-local `output` as normal execution. `EXEC.MODE` is `load`; `EXEC.ID` and `EXEC.LOAD.ITERATION_ID` are the same iteration identity; `EXEC.STARTED_AT` is the iteration start; and `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, and local `output` are isolated per iteration. The scheduler-owned fields are:

| Path | Meaning |
|---|---|
| `EXEC.LOAD.RUN_ID` | Enclosing load run identity shared by its iterations. |
| `EXEC.LOAD.MODEL` | `closed` or `arrivalRate`. |
| `EXEC.LOAD.USER_ID` | Stable closed-model Virtual User identity; `null` or absent for arrival-rate. |
| `EXEC.LOAD.ITERATION_ID` | Globally unique iteration identity within the load run. |
| `EXEC.LOAD.ITERATION` | Scheduler sequence number. |
| `EXEC.LOAD.PHASE` | `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. |
| `EXEC.LOAD.RUN_STARTED_AT` | Optional enclosing load-run start timestamp. |

Scenario `inputs` are copied only into `EXEC.INPUT.*`; reusable Templates, Flows, and Tools must use that canonical input tree, `EXEC.VARS.*`, `EXEC.ACTIONS.*`, and current `output.*`. `META.SOURCE` identifies the load scenario by type, scenario name, and path; iteration identity remains under `EXEC.ID` and `EXEC.LOAD.*`, and secrets are excluded. Root-level `LOAD.*`, `EXEC.OUTPUT`, `EXEC.CALL`, and `EXEC.INVOCATION` are not public load APIs. See [`examples/load/README.md`](../examples/load/README.md) for complete closed/arrival-rate configurations, CLI overrides, target forms, thresholds, evidence, and validation examples.

`att load` validates the scenario and target before starting one of two schedulers. Closed mode keeps a stable Virtual User identity and waits for target completion before think time and the next iteration. Arrival-rate mode uses absolute planned due times; when `maxConcurrent` is full, the arrival is recorded as generator `dropped` work rather than queued or counted as a SUT failure. Both schedulers publish compact events to bounded-memory metrics, and both write isolated `output/load/<runId>/load-summary.json`, `load-summary.yaml`, and `report/index.html`. Warm-up is real traffic but is excluded from measured threshold aggregates by default. Successful iterations retain metrics only unless evidence sampling is configured; a bounded sampled success gets a physical iteration workspace with `case.log` and `case.yaml`, while a failure creates that workspace lazily when its diagnostic is retained. Evidence links are written below the load run's `samples/` or `failures/` directories and never enter ordinary functional-run artifacts.

The shortest end-to-end smoke commands are:

```sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

`examples/load/README.md` is the maintained copyable reference for Template, Flow, Tool, DB/MQ pool sizing, thresholds, evidence, CLI overrides, and invalid configurations. All six examples are schema- and dependency-validated by `LoadAcceptanceTest`; that test also launches the real `att.FrameworkRunner load` CLI for short closed and arrival-rate scenarios and checks the persisted JSON, YAML, and offline HTML report.

#### Load summary and HTML report contract

`load-summary.json` and `load-summary.yaml` share the stable `att-load-summary/v1.0` contract. Root fields are `schemaVersion`, `status` (`PASS`, `FAIL`, or `ERROR`), `exitCode`, `runId`, `startedAt`, `endedAt`, `durationMs`, `scenario`, `timing`, `metrics`, `thresholds`, `resources`, optional `evidence`, and `report: report/index.html` relative to the run directory. The JSON schema is `schemas/att-load-summary-v1.0.schema.json`, registered in the schema catalog as `att-load-summary/v1.0`.

The persisted `scenario` is a dedicated report-safe projection. It retains target type/id, workload and execution timing, threshold configuration, and evidence policy, but omits arbitrary business `inputs` and Tool `target.arguments` from JSON, YAML, and the HTML `window.ATT_LOAD_SUMMARY`. CI and offline tooling can therefore consume the summary without durable password, token, request-body, or other oversized payload values.

`timing.phases` lists configured `WARMUP`, `RAMP_UP`, `STEADY`, and `RAMP_DOWN` start/end/duration windows. `metrics.phases` contains observed scheduled/started/completed/failure/drop counts, throughput, latency, scheduler lag, and concurrency aggregates per phase. Warm-up has `measured: false`: its traffic remains visible in the run history, but measured SLA aggregates exclude it. Other phases remain measured. A phase with no events still appears in `timing.phases`, so empty and edge runs have a stable machine-readable shape.

`resources.db` and `resources.mq` contain only bounded pool diagnostics such as pool size, active/idle, waiting, and timeout/acquisition counts; they never contain connections, queue handles, credentials, or other live objects. Pool saturation and acquisition timeouts are separate from SUT failures. `evidence.items[].path` points to retained evidence below `<runId>/samples/` or `<runId>/failures/`; the HTML report renders each path as a relative link.

`report/index.html` is self-contained and can be opened offline. It shows run identity/status, closed or arrival-rate semantics, phase and warm-up separation, aggregate metrics, threshold diagnostics, resource diagnostics, retained evidence links, and bounded one-second time-series buckets. Arrival-rate reports explicitly distinguish configured arrival rate, achieved scheduling rate, completed TPS, and generator drops; drops are not SUT errors. The report links to the adjacent JSON/YAML summaries but does not embed raw per-iteration samples or secrets; `window.ATT_LOAD_SUMMARY` exposes the same bounded summary for offline tooling.

`att load --profile` keeps the existing profiling contract and writes `performance.json` beside the load summary. It records load execution/report phases, bounded load counters, and the shared schema/Template/payload/process counters, so the documented self-overhead gate is reproducible without turning ATT into a target CPU or memory benchmark.

The machine-readable `metrics` object reports configured load (`configuredUsers`, `configuredArrivalRatePerSecond`, `configuredMaxConcurrent`), iteration/scheduling counts (`iterations`, `scheduled`, `measuredScheduled`, `started`, `measuredStarted`, `completed`, `success`, `failure`, `runtimeError`, `dropped`, `measuredDropped`), concurrency (`activeVus`, `maxActiveVus`, `currentInFlight`, `maxInFlight`), measured-phase results (`warmupCompleted`, `measuredCompleted`, `sutErrorRate`, `runtimeErrorRate`, `droppedRate`, `completedThroughput`), latency percentiles (`p50Ms`, `p95Ms`, `p99Ms`), scheduler lag, and grouped `errorClassifications`. Percentiles use a bounded reservoir; `latencyMinMs`, `latencyMeanMs`, `latencyMaxMs`, and `latencyObservationCount` remain exact across all measured observations. Runtime errors are separate from SUT failures, and generator drops never increase `sutErrorRate`. The `buckets` map is sorted by one-second epoch-millisecond key; each bucket includes `model`, `phase`, configured rate/concurrency, completed TPS, p95/p99, SUT/drop rates, active/in-flight counts, scheduler lag, and error classifications. Latency storage is capped at 4096 global samples and 256 samples per bucket; time-series storage is capped at 4096 buckets and evicts the oldest bucket, so memory does not grow linearly with run duration or raw latency values.

Load thresholds use the common `errorRate` (`%`), `p95`/`p99` (`ms`), and `minThroughput` (`/s` or `/m`) fields for both workload models. Arrival-rate scenarios additionally support `droppedRate` (`%`) and `achievedArrivalRate` (`%`, `/s`, or `/m`). For the percentage form, achieved arrival rate is measured `measuredStarted / measuredScheduled`; warm-up is excluded, while ramp-up, steady, and ramp-down remain part of the integrated measured schedule. The rate forms compare the actual average started rate over the full phase window; `/m` thresholds are normalized to per-second before comparison. Each threshold is reported independently with expected expression, formatted actual value, PASS/FAIL status, and failure diagnostic. The load result then uses exit code `0` for PASS, `1` for a completed run with failed SLA thresholds, `2` for validation/configuration failure, and `3` for load runtime/infrastructure error. `target.arguments` is valid only for Tool targets; Template and Flow targets reject it with a field-specific diagnostic.

The release gate is deliberately reproducible rather than a SUT microbenchmark:

```sh
mvn -q -Dtest=LoadAcceptanceTest,LoadCrossModeTest,ClosedVuSchedulerTest,FixedArrivalRateSchedulerTest,LoadRuntimeTest,LoadScenarioTest,LoadReportTest,LoadDbPoolingTest,LoadMqPoolingTest,PooledMqHelperExecutorTest,PooledMqTransportFactoryTest test
```

It checks the CLI-to-report path for both schedulers, including deterministic arrival-rate cap/drop and configured-versus-achieved-versus-completed metrics; Context deep-copy and iteration isolation; lazy success/failure workspaces; bounded evidence and metric reservoirs; scheduler lag accounting; process/file artifact behavior; DB/MQ reuse, timeout, pool diagnostics, and cleanup; threshold PASS/FAIL; summary schema; report rendering; and compatibility of the existing run/debug/validation test suite. Load V1 does not claim distributed execution, Poisson/random pacing, weighted multi-scenario, rendezvous, adaptive pools, MQ handle pooling, XA/affinity, or target CPU/memory benchmarking.

Common properties include:

| Scope | Examples |
|---|---|
| EXEC.INPUT | TestCase columns, debug `case`/`inputs`, and stage input aliases |
| EXEC.VARS | `assign` values; `CASE.VARS` remains a compatibility alias |
| EXEC.ACTIONS | current Stage's completed/published Action results; cleared when the next Stage starts |
| CASE.STAGES | persisted Stage/Template status, timing, and nested Action evidence; not a supported expression namespace |
| META | safe project/source/target/component identity; never a config dump or credential store |
| output | current Action result, assertion actual value, and final description inputs |
| CASE / RUN / ACTIONS | generated legacy views of the canonical state; `ACTIONS` is current-scope only |
| CASE.DB / TOOL / DB | existing finalization or transient framework scopes, kept separate from `EXEC` |

Prefer canonical paths such as `${EXEC.INPUT.amount}`, `${EXEC.INPUT.channel}`, `${EXEC.VARS.txnSeq}`, `${EXEC.ACTIONS.callApi.output.result}`, and `${META.TARGET.id}`. Use `${output...}` only for the current Action and `${EXEC.ACTIONS.<id>...}` only for a completed Action in the current scope. Stage/Template/Flow history, including `${CASE.STAGES...}`, is persisted result/evidence data and is not a supported reusable expression path; direct reads produce `CONTEXT_CROSS_SCOPE`. Root `${TOOL...}` and `${DB...}` may remain only as internal or persisted historical/result compatibility views, not case-wide “latest invocation” APIs; general expressions using them produce `CONTEXT_LEGACY_PATH`. Tool and inline DB evidence is persisted below the containing Action with stable `<kind>.invocations[]` cardinality; Case-level DB finalization remains available through `${CASE.DB.<instance>}` after Case completion.

The following aliases are required for existing packages. New authoring should use the right-hand canonical/local path; the left-hand forms belong in migration or compatibility material only:

| Legacy path | Canonical/local path |
|---|---|
| `${CASE.<businessField>}` | `${EXEC.INPUT.<businessField>}` |
| `${CASE.caseId}` / `${CASE.workbookId}` / `${CASE.groupId}` / `${CASE.rowCaseId}` | `${META.SOURCE.caseId}` / `${META.SOURCE.workbookId}` / `${META.SOURCE.groupId}` / `${META.SOURCE.rowCaseId}` |
| `${CASE.VARS}` | `${EXEC.VARS}` |
| `${ACTIONS}` | `${EXEC.ACTIONS}` |
| `${RUN.id}` / `${RUN.runId}` | `${EXEC.ID}` |
| `${CASE.outputDirectory}` | `${EXEC.OUTPUT_DIR}` |
| `${CASE.status}` / `${CASE.durationMs}` / `${CASE.environment}` | Legacy lifecycle/result aliases; there is no corresponding canonical `EXEC` field |
| `${CASE.STAGES.<stage>...}` | Legacy execution/evidence data only; direct expression use is rejected with `CONTEXT_CROSS_SCOPE` |
| `${output.*}` | current Action-local `output.*` |

For compatibility, a framework adapter may still write `${CASE.<businessField>}`; that write is applied to the same `EXEC.INPUT` map and does not create a second input store. New expressions should read the canonical path; only compatibility adapters should use the legacy write spelling. Framework-owned identity, lifecycle, `VARS`, `DB`, and Stage evidence fields remain protected.

`META` is read-only to expressions and contains only curated safe metadata. Optional references such as `${EXEC.INPUT.maybeMissing?}` and `${output.response?}` use the same canonical/local resolver and return null only for missing values; malformed, ambiguous, or invalid traversal remains an error.

V2.4.1 also accepts a case-sensitive path-segment suffix when it identifies exactly one currently readable logical Context path. For example, if `EXEC.INPUT.payment.response.resultCode` is the only readable path ending with those segments, `${payment.response.resultCode}`, `${response.resultCode}`, and `${resultCode}` resolve to the same value. Matching uses parsed map keys/list indexes, not a raw character suffix. Canonical and convenience aliases of the same logical node count once. If multiple logical paths match, ATT raises `ATT-CTX-002`, lists every canonical candidate in deterministic order, and requires a longer suffix or full path. Adding a conflicting node therefore makes an existing shorthand invalid rather than silently changing its target. Documentation continues to prefer canonical paths.

`${EXEC.OUTPUT_DIR}` is a reserved, normalized absolute path and Case data cannot override it. `EXEC.VARS` and `CASE.DB` are likewise fixed framework-owned maps, so a sidecar `excel.dataColumns` alias or any other Case-root alias cannot be named `VARS` or `DB`. All three nodes exist before the first stage; `CASE.DB` remains empty until Case transaction finalization publishes used-instance outcomes. During a Stage, its caller/input values are adapted into `EXEC.INPUT` and do not create an `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.STAGE`, or `EXEC.STAGES` node. Stage status, timing, and historical selector values remain below legacy `CASE.STAGES.<stage>` evidence. During execution `outputDirectory` is already the final `<outputDirectory>/<RunID>/<CaseID>` directory, so live evidence and persisted paths are identical. Validation preserves the output-directory placeholder because no runtime Run directory exists yet.

Map properties use dot navigation and lists use zero-based brackets:

```text
${EXEC.INPUT.amount}
${EXEC.INPUT.channel}
${EXEC.ACTIONS.callApi.output.result.items[0].status}
```

Dot notation navigates simple map keys. Lists accept bracket or numeric-dot indexes, so `${EXEC.INPUT.items[0].status}` and `${EXEC.INPUT.items.0.status}` are equivalent. Indexes are zero-based. Map keys containing dots, spaces, braces, or colons use quoted brackets, for example `${EXEC.INPUT.response['{urn:payment}Status'].text}`.

##### Example: reference stage-selector data from an XML payload

Suppose the Excel selector cell for stage `invoke` contains this YAML flow map:

```yaml
{name: templateName, debitAccount: "012123456", InstrAmt: "100.00"}
```

Flow-map entries use commas, not semicolons. Every selector-map key is adapted into the current Stage's `EXEC.INPUT`, so the canonical XML payload reference is:

```xml
<InstrAmt>${EXEC.INPUT.InstrAmt}</InstrAmt>
```

The old `CASE.STAGES` path remains available in persisted execution evidence and migration material only; it is not readable from a Template/Flow expression. When no other currently readable logical path creates a suffix conflict, the following forms resolve to the same current Stage input value:

| Expression | Meaning | Stability |
|---|---|---|
| `${EXEC.INPUT.InstrAmt}` | Canonical current-Stage input | Preferred; explicit and stable |
| `${EXEC.INPUT.InstrAmt}` | Canonical current-Stage input | Preferred; explicit and stable |
| `${invoke.InstrAmt}` | Unique current-input suffix | Valid only while unique; migrate to canonical form |
| `${InstrAmt}` | One-segment suffix | May be ambiguous because the value is also in `EXEC.INPUT` |

If another readable path also ends in `InstrAmt`, the shortest form raises `ATT-CTX-002` instead of choosing one silently. Lengthen the suffix or use the canonical path. Bracket notation for `CASE.STAGES` remains a report/evidence selector spelling only and is rejected when used as a runtime Context expression.

`InstrAmt` is current Stage input at `${EXEC.INPUT.InstrAmt}`; it is not a direct child of `TEMPLATE`. `${TEMPLATE.InstrAmt}` is invalid unless an independently mapped value actually exists at the requested path. Quote XML lexical values such as account numbers and fixed-scale amounts in the selector YAML. This preserves the leading zero in `"012123456"` and the authored decimal representation `"100.00"`; unquoted YAML numeric values are typed numbers and do not promise to retain their original text formatting.

Validation resolves available static values and preserves only values that are legitimately runtime-dependent. Runtime resolves every remaining reference at its defined execution point. Canonical `EXEC`/`META` roots and supported legacy aliases are traversed strictly; `CASE.STAGES` and cross-scope Action reads are rejected as incompatible scope references. References without an explicit root use the unique-suffix rule above; when validation can identify the canonical current-scope replacement, it emits `CONTEXT_LEGACY_PATH` and should be migrated. Tool definitions have a separate rule described below. An unknown Context path is never converted silently to empty text: `ATT-CTX-001` reports the exact `requestedPath`, deepest successfully reached `currentNode`, first `missingSegment`, and source location. `ATT-CTX-002` reports the requested shorthand and all candidate paths. Neither diagnostic dumps the complete Context tree, preventing large failed Action/Tool/DB structures from being copied repeatedly into logs and reports. A declared optional Case field whose actual value is blank remains a valid empty string. An Action may read only Case data, its local `output` where supported, and Action outputs that exist in its current scope; validation rejects current/future or cross-scope Action references.

#### `config.report.fileNamePattern`

##### Context and legal forms

`report.fileNamePattern` uses the unified expression engine with a dedicated non-Case scope. It has one case-sensitive value reference:

| Placeholder | Value |
|---|---|
| `${suiteName}` | Source workbook basename with its final lowercase `.xlsx` suffix removed; for example, `testcase/payment_regression.xlsx` becomes `payment_regression` |

The configured string must reference `${suiteName}` explicitly, whether used as text interpolation or as a built-in argument. Bare `suiteName` inside a call is rejected. Legal examples include:

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(${suiteName})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${suiteName})})}.xlsx"
```

For `testcase/payment.xlsx`, the first example writes `output/<RunID>/workbooks/payment.result.xlsx`. `${suiteName}` is the physical workbook basename, not the sidecar `id`, Sheet/group ID, Case ID, or Run ID. Authors should keep the value a safe filename ending in `.xlsx`; avoid `/`, `\`, absolute paths, `..`, and platform-reserved names. Workbooks in different recursive directories that share the same basename resolve to the same default result filename, so package authors must avoid that collision.

##### Illegal or unsupported forms

These values fail configuration loading because they do not reference `suiteName`:

```yaml
fileNamePattern: "result.xlsx"
fileNamePattern: "${runId}.result.xlsx"
fileNamePattern: "${workbookId}.result.xlsx"
```

No other value reference or Runtime Context path is supported. Configured Tool calls are also unavailable in this scope. These forms are invalid:

```text
${runId}
${workbookId}
${environment}
${EXEC.INPUT.caseId}
${EXEC.ID}
#{configuredTool()}
#{upper(${runId})}
```

A pattern such as `${suiteName}-${runId}.xlsx` is rejected; unknown references are never retained as literal output text. All documented built-ins are parsed by the same engine, including nested calls. Because the resulting text becomes a filename, prefer deterministic string transformations and avoid side-effecting filesystem built-ins, random values, path separators, absolute paths, `..`, and platform-reserved names.

#### Tool-definition `command` expressions

##### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestFile}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestFile}` | Explicit Tool-input namespace |
| `${TOOL.input.requestFile}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestFile}"
      - "${input.environment}"
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered XML request path
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

The action call is the boundary between the general Runtime Context and this restricted Tool-input Context:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestFile` and `environment`. The command then substitutes `${input.requestFile}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestFile}` / `${environment}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases shown above, and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestFile})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestFile}` is canonical; `${TOOL.input.requestFile}` and `${requestFile}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestFile` or `input.requestFile` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestFile}"
  - "--environment=${input.environment}"
```

Because this is a YAML argv list, each list item remains one atomic process argument even when its resolved value contains spaces or shell-like characters. ATT does not invoke a local shell.

##### Quotes, Context values, and atomic argv

Quotes inside a Tool call belong to the ATT expression grammar; they are not shell quotes. The outer `'...'` or `"..."` delimiters are removed before invocation, the opposite quote is literal, and a matching quote can be escaped with a backslash. A `${...}` reference embedded in a quoted value is interpolated, while an unquoted canonical Context path passes its typed value directly.

The following configured Tool keeps each declared input as one argv value:

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${message}", "${sourceFile}"]
    output: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

Use a YAML block scalar when a call contains several quote layers:

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}
```

The child process receives the three messages exactly as `Customer O'Reilly`, `status="READY"`, and, for example, `O'Reilly said "READY" for payment.payment.TC001`. A Context value that itself contains either quote needs no caller-side shell escaping and still occupies one argv item.

If a call is kept on one YAML line, YAML escaping is an additional and separate layer:

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

The first line escapes double quotes for the YAML double-quoted scalar. The second doubles the apostrophe for the YAML single-quoted scalar. The expression engine then evaluates the resulting `#{...}` text.

Ordinary process-backed Tools never ask a shell to reinterpret resolved inputs. Text such as `$HOME`, `$(date)`, `a*.xml`, `|`, `>`, and quotes carried by a Context value is passed literally. Use an explicitly reviewed wrapper when shell-like behavior is required; the shipped `fpp.exehelper` and `fpp.loghelper` provide only the narrowly documented pathname expansion above.

##### Illegal forms and token restrictions

Tool commands cannot directly read the general Runtime Context, use unique-suffix navigation, or navigate argument fields with bracket syntax. These `${...}` forms are rejected during configuration or package validation:

```text
${EXEC.INPUT.environment}
${EXEC.ID}
${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
${STAGES.invoke.InstrAmt}
${input['requestFile']}
${TOOL.input['requestFile']}
${requestFile.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestFile})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a standalone global Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestFile}"]
arguments:
  requestFile:
    name: Request File
    description: Rendered XML request path
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestFile}` or a transformed form such as `#{str.upper(${input.requestFile})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

#### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

#### Built-in functions

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, and `misc.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `str.upper` | Convert text to upper case | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.lower` | Convert text to lower case | `#{str.lower(value=${EXEC.INPUT.channel})}` |
| `str.trim` | Remove surrounding whitespace | `#{str.trim(value=${EXEC.INPUT.reference})}` |
| `str.ltrim` / `str.rtrim` | Remove leading/trailing whitespace | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | Return text length | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | Concatenate arguments in call order | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr` | Extract text from a zero-based start | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.indexOf` | Return zero-based position or `-1` | `#{str.indexOf(${EXEC.INPUT.reference}, '-')}` |
| `str.contains` | Test literal substring membership | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.startsWith` / `str.endsWith` | Test a literal prefix/suffix | `#{str.startsWith(${EXEC.INPUT.reference}, 'PAY')}` |
| `str.replace` | Replace every literal target | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad` / `str.rpad` | Pad to a minimum length | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | Repeat a value 0–10000 times | `#{str.repeat(3, '9')}` |
| `date.sysdate` | Return system-zone date, optionally formatted | `#{date.sysdate('yyyyMMdd')}` |
| `date.systimestamp` | Return system-zone timestamp, optionally formatted | `#{date.systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `date.format` | Format an ISO-8601 value | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | Add a calendar/time amount | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `file.exists` | Test whether a regular file exists | `#{file.exists(${EXEC.INPUT.requestFile})}` |
| `file.directoryExists` | Test whether a directory exists | `#{file.directoryExists(${EXEC.OUTPUT_DIR})}` |
| `file.size` | Return regular-file size in bytes | `#{file.size(${EXEC.INPUT.requestFile})}` |
| `file.mkdirs` | Create a directory tree and return its absolute path | `#{file.mkdirs(${EXEC.INPUT.archiveDirectory})}` |
| `file.copy` | Copy a regular file and return the target path | `#{file.copy(${EXEC.INPUT.requestFile}, ${EXEC.INPUT.backupFile}, true)}` |
| `file.move` | Move a regular file and return the target path | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `file.delete` | Delete a non-directory file | `#{file.delete(${EXEC.INPUT.temporaryFile}, true)}` |
| `misc.string` | Convert a value to text | `#{misc.string(value=${EXEC.INPUT.amount})}` |
| `misc.number` | Parse and normalize a number | `#{misc.number(value='12.50')}` |
| `misc.boolean` | Convert true/false, yes/no, or 1/0 | `#{misc.boolean(yes)}` |
| `misc.coalesce` | Return first non-blank value | `#{misc.coalesce(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.nvl` | Return a default for null/empty text | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | Select one of two values from a boolean | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | Return one of 1–1000 input values | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | Format one stable typed DB result as SQL*Plus-style text | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `prettyPrint` / `format.pretty` | Deterministically format a Map/List/array tree | `#{prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.

Filesystem built-ins resolve relative paths against the ATT JVM working directory and return normalized absolute paths from create/copy/move operations. Existence and size functions accept only their documented regular-file or directory type and do not follow the final symbolic link. Copy and move reject symbolic-link sources/targets, create missing target parents, and default `overwrite` to `false`; an existing target is an error unless `overwrite=true`. `deleteFile` rejects directories, may delete a file or symbolic link itself, and defaults `missingOk` to `false`. Filesystem errors produce action ERROR and these in-process operations create no TOOL process artifacts.

`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.

`dbText` accepts exactly one positional argument or named `value`. The value must be a stable query/update result returned by a direct DB Action, DB expression, or DB-backed Tool. It uses exactly the same deterministic formatter as direct DB Action `saveAs.format: text` and has no JDBC, transaction, connection, or cache side effects.

`prettyPrint` accepts exactly one positional argument or named `value`. It formats Maps, Lists, Iterables, arrays, scalars, and null with two-space indentation. Linked and sorted Maps retain their iteration order; other Map keys are sorted by text. Strings are quoted and escaped, cycles and excessive depth are marked, output is bounded, and the source object is not modified.

Use built-ins for in-process transformations, time values, DB-result formatting, and simple local file operations; use tools when filesystem work needs process evidence or for network, database, system integration, or complex reusable logic. Built-ins occupy reserved framework packages. V2.6 retains an internal provider boundary for a future release, but configuration cannot load custom Java classes. Invalid arguments produce action ERROR.

Typical expressions:

```yaml
assert: "${EXEC.INPUT.amount} > 0"
assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
assert: "${EXEC.ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${EXEC.INPUT.channel} == 'MOBILE') and (${EXEC.INPUT.amount} <= 1000)"
```
