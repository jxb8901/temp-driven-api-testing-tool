# ATT V3.4.2 System Design

**Document Status:** Implemented

**Target Version:** ATT 3.4.2
**Last Updated:** 2026-09-19

## 1. Purpose

ATT V3.4.2 defines one execution-neutral expression Context for ordinary TestCase and standalone debug execution. `EXEC` and curated immutable `META` are canonical roots; `output` remains Action-local, while deterministic legacy aliases remain readable with migration warnings. Tool, DB, and MQ executors adapt their native outcomes to one operation-result boundary, then the Action runner owns status, retry, and publication into one stable result/evidence envelope. A Flow is called only from a Template, enters a fresh Action scope, and publishes only its aggregate invocation result plus explicit `EXEC.VARS` assignments; debug invokes the same runtime with a synthetic Case.

```text
Excel test case -> Stage -> Template -> Action / Flow -> Action -> Tool / DB / built-in
```

The V3.1 removal of the isolated-function model remains normative. A Flow does not declare inputs or outputs and does not create separate `input`, lowercase `actions`, `runtime`, or `flow` Context roots. Its Action namespace is nevertheless isolated from the caller and nested Flows; data that must cross that boundary is assigned explicitly to `EXEC.VARS`.

The existing Excel, Stage, V2 Template, Tool, DB, run, report, and CI contracts remain unchanged.

## 2. Goals and boundaries

V3.3 provides:

- one Expression Engine and Context contract for Template and Flow Actions;
- canonical `EXEC`/`META` roots, Action-local `output`, and narrowly scoped legacy views;
- static Flow composition and nesting to depth 3;
- validation of the complete expanded Action order before run output is created;
- explicit scope-local Action IDs for Template and nested Flow execution;
- Case-scoped `assign` behavior through `EXEC.VARS`; and
- nested evidence and qualified artifact paths without a Flow-specific expression API.

V3.3 additionally provides:

- typed `#{...}` expression blocks with arithmetic, `in`, lists, comparisons, boolean operators, Context operands, and calls;
- direct DB Action named parameters compiled safely to JDBC positional bindings;
- deterministic `prettyPrint` formatting for nested runtime values;
- raw multiline Log Action and process output in the Case log;
- `saveAs.path: console`; and
- no persistent `process-output` artifacts.

V3.4 additionally provides:

- Tool Action evidence collectors that run after the primary result and before assertion;
- per-attempt collector evidence, timeout, failure policy, and collector invocation identity; and
- invocation-scoped IBM MQ send, receive, and request/reply operations using exact file payload bytes;
- verbose human `run` output by default, with `--verbose` retained as a compatibility spelling and `--quiet` as the explicit suppression; and
- all-workbook `snapshot` generation by default when no selector is supplied, with `--all` retained as an explicit spelling.

V3.4.2 additionally provides:

- one executor-neutral operation-result boundary for generic Tools, DB operations, MQ operations, and future helpers;
- helper-native evidence under `output.evidence.<kind>.invocations[]` while the Action is active and under `EXEC.ACTIONS.<id>.output.evidence.<kind>.invocations[]` after publication;
- call-backed Tools as the preferred extension model for framework-native/reusable capabilities, with typed arguments preserved;
- command-backed Tools as a supported external-process extension mechanism, with deterministic argv, process evidence, SSH, and script use cases preserved; and
- DB transaction/connection state kept internal to the resource scope, with legacy `CASE.DB` finalization retained separately from operation evidence.

V3.3 does not add namespaces, implicit last-Action output, replacement input/output syntax, loops, parallel branches, dynamic dispatch, Flow timeout/retry, `runAlways`, warning impact, or inheritance.

## 3. Public configuration

### 3.1 Flow descriptor

Flow descriptors remain below `<templatesRoot>/flows/**/flow.yaml` and retain the `att-flow/v3.0` schema name. The schema is deliberately redefined and is not compatible with the isolated V3.0 contract.

```yaml
schemaVersion: att-flow/v3.0
id: payment.invoke.v1
name: Invoke Payment
description: Render and invoke a payment request.

actions:
  renderRequest:
    type: render
    payload: request.xml
    renderAs: file

  invokePayment:
    type: tool
    call: >-
      #{payment.invoke(
        request=${EXEC.ACTIONS.renderRequest.output.result},
        reference=${EXEC.INPUT.SrcRefNo}
      )}
```

The only top-level fields are `schemaVersion`, `id`, `name`, `description`, and `actions`. `inputs` and `outputs` are validation errors.

### 3.2 Template invocation

`att-template/v3.0` retains `type: flow` with one static canonical `use` value:

```yaml
actions:
  invokeFlow:
    type: flow
    use: payment.invoke.v1

  verify:
    type: assert
    assert: "${EXEC.ACTIONS.invokePayment.output.result.status} == 'SUCCESS'"
```

`with` is invalid. A Flow Action exposes only the normal Action outcome fields such as `status`, `success`, `durationMs`, and `exception`. Business results are read from the internal Action that produced them; ATT does not create `output.outputs` or forward the last Action result.

### 3.3 Standalone debug command

`debug template <id>`, `debug flow <id>`, and `debug tool <id>` construct one synthetic Case and pass it through the same `StageTemplateRunner`, `UnifiedTemplateEngine`, Tool invoker, DB lifecycle, Flow registry, Context validation, and Case-log writer used by ordinary execution. Debug does not iterate over Excel and does not create a normal Run manifest or `latest-run.yaml`.

Debug inputs use `att-debug/v1.0`:

```yaml
schemaVersion: att-debug/v1.0
case: {RefNo: REF001, Amount: 1000}
stage: {key: DEBUG, values: {SrcRefNo: SRC001}}
inputs: {SrcRefNo: SRC001}
arguments: {requestId: REF001}
```

The sidecar is discovered beside the selected Template or Flow, or as `config/tools/<group>.debug.yaml` for a grouped Tool. `--input` overrides discovery. Validation is target-scoped: only the selected Template/Flow closure or Tool contract is loaded, so unrelated workbook artifacts and unrelated malformed Template descriptors do not block a debug session. Framework-owned Case, Run, Action, Tool, DB, stage, and output fields are written after user Case data and therefore cannot be replaced.

Each debug run writes `output/debug/<debugId>/case.log`, `result.yaml`, and `artifacts/`. `result.yaml` records target, synthetic Case ID, input source, status, duration, action results, diagnostics, and artifact locations. PASS, FAIL, invalid input/validation, and runtime error map to exit codes 0, 1, 2, and 3 respectively.

## 4. Context and assignment semantics

Flow Actions use the same canonical roots and expression engine as inline Template Actions, but each Stage/Template and Flow invocation owns an explicit Action scope:

The canonical execution contract is:

```text
EXEC
├── ID, MODE, STARTED_AT, OUTPUT_DIR
├── INPUT   (TestCase data plus the current Stage caller/input values)
├── VARS    (mutable assigned variables)
└── ACTIONS (current Stage's completed/published Action results)
META
├── PROJECT, SOURCE, TARGET, TEMPLATE, FLOW, TOOL
└── DBHELPER, MQHELPER (curated safe component metadata)
output
└── current Action/attempt-local result
```

`EXEC` deliberately has no `TOOL`, `DB`, `MQ`, `OUTPUT`, `LOAD`, `CALL`,
`INVOCATION`, `STAGE`, or `STAGES` child. Helper/resource state remains
internal; existing root-level `TOOL.*` and `DB.*` structures may remain in
internal or persisted historical/result compatibility views only and never
define supported expression APIs or canonical storage. Helper identity may be
exposed through curated `META.TOOL`, `META.DBHELPER`, and `META.MQHELPER`
metadata where there is a concrete expression use case.

`EXEC.MODE` is `testcase` or `debug`. A TestCase adapter prepares the current
Stage's `EXEC.INPUT` from Case-level input and current-Stage values; when the
same key exists at both levels, the current Stage value wins only while that
Stage is active. There is intentionally no canonical `EXEC.STAGES` or
`EXEC.STAGE`. Stage/Template status, timing, and execution history remain in
the execution result/evidence model; `CASE.STAGES.*` is a persisted result view,
not an expression namespace. `EXEC.ACTIONS` is the current Action scope and is
cleared when the next Stage starts; the legacy `ACTIONS.*` alias is only the
compatibility spelling for that same current scope.

Lifecycle/result fields such as status, duration, error, diagnostic,
environment, and debug input remain in the execution result and legacy Case
adapter. They are not promoted to new `EXEC` fields; the public `EXEC` tree
stays limited to the seven nodes shown above.

| Root | Meaning |
|---|---|
| `EXEC.*` | Canonical execution identity, input, variables, and completed Actions |
| `META.*` | Curated immutable project/source/target/component metadata |
| `CASE.*` | Legacy Case/input/lifecycle aliases; `CASE.STAGES` is result/evidence data, not a supported expression root |
| `RUN.*` | Current Run metadata |
| `ACTIONS.*` | Current-scope completed Actions, compatibility spelling of `EXEC.ACTIONS` |
| `TOOL.*` | Internal/historical compatibility view where required; not a supported expression API |
| `DB.*` | Internal/historical compatibility view where required; not a supported expression API |
| `output.*` | Current Action outcome during supported post-execution fields |

Existing unique-suffix shorthand remains unchanged. Removed Flow-only roots are invalid rather than treated as aliases.

### 4.1 Common Action result and evidence lifecycle

Every observable operation converges at the Action boundary without forcing its
executor to share a low-level implementation or parameter contract:

```text
generic Tool / DB helper / MQ helper
              |
   operation result (result, evidence,
   executionSuccess, diagnostic, timing)
              |
   StageTemplateRunner owns Action
   status, retry attempts, and duration
              |
   local output while active
              |
   EXEC.ACTIONS.<actionId> after publication
```

The operation result is intentionally separate from the Action lifecycle.
`result` contains the typed business/operation value; `evidence` contains safe
helper or process metadata; `diagnostic` is present for typed failures; and
`executionSuccess` describes only the operation, not a final assertion. The
Action runner adds `status`, `success`, `durationMs`, and `attempts`. Every
operation evidence kind has a stable `invocations[]` list, even when there is
only one invocation: DB SQL/parameter metadata is stored as
`output.evidence.db.invocations[0]`, MQ metadata as
`output.evidence.mq.invocations[0]`, and Tool/process metadata as
`output.evidence.tool.invocations[0]`. Post-invoke collectors remain under
the final Action evidence and each attempt's evidence. A DB connection, transaction,
Hikari lease, MQ connection/queue handle, process handle, or retry frame is
resource state, not Action evidence and is never a canonical Context root.

The two Tool backends converge only at this boundary:

| | call-backed | command-backed |
|---|---|---|
| Invocation | typed native/helper call | OS process, script, CLI, or SSH |
| Preferred role | normal framework-native/reusable Tool | supported special-case external-process extension |
| Arguments | String, Number, Boolean, null, List, Context value, nested call | deterministic argv items; flat List may expand |
| `argName` / `argNameMode` | not applicable | supported for process shaping |
| stdout/stderr and exit code | no process contract | process evidence contract |
| cache | supported where valid | no process cache |
| Action publication | operation result consumed by the Action runner | operation result consumed by the Action runner |

New framework-native capabilities should use `call`. Existing and new
command-backed Tools remain supported when an external process is the natural
boundary; they are not deprecated and do not need migration.

Visibility follows explicit scope boundaries:

1. a Stage/Template starts with a fresh `EXEC.ACTIONS` scope;
2. each internal Action sees only earlier completed Actions in that same scope;
3. a Flow enters a fresh scope, and a nested Flow enters another fresh scope; and
4. returning from a Flow restores the parent scope, so parent Actions cannot read the Flow's internal Action IDs directly.

Use a unique Case-scoped `EXEC.VARS` assignment when a value must cross a Flow
boundary. The Flow invocation itself remains visible in the parent scope with
its standard status outcome, and its detailed hierarchy is retained as result
evidence rather than promoted to the parent's Action namespace.

`assign` always publishes to `EXEC.VARS`, including inside a Flow. Assignments never overwrite an existing name and persist across later Actions, Templates, and Stages in the same Case.

## 5. Static expansion and identity

Every selected Template is validated as one statically expanded Action plan. Flow invocation Actions remain nodes in the plan, while their internal Actions execute before the invocation Action publishes its aggregate outcome.

Action IDs must be unique within each Action scope, including:

- inline Template Actions;
- Flow invocation Actions;
- internal Actions;
- nested Flow invocation Actions; and
- nested internal Actions.

Separate Flow invocations and separate nested scopes may reuse internal IDs.
Duplicate IDs in one Template, one Flow body, or one other single scope remain
validation errors. This permits repeated/nested Flow calls without collisions;
`EXEC.VARS` assignment names remain Case-scoped and must still be unique.

Flow IDs remain path-independent canonical IDs ending in `.vN`. References are static. Direct and indirect cycles are invalid, and maximum nesting depth remains 3.

## 6. Validation

The registry performs package-level structural checks:

- strict Flow and Template schemas;
- canonical and duplicate Flow IDs;
- missing dependencies;
- cycles and nesting depth;
- `template.yaml`/`flow.yaml` conflicts; and
- symlink and package-root escapes.

The execution-plan validator recursively validates each Flow at its actual Template call site. It creates a fresh completed-Action set for every Flow scope while retaining the Case-scoped `EXEC.VARS` assignment set, and applies the ordinary Action validation rules to Flow render payloads, Tool calls, DB blocks, assertions, logs, assignments, descriptions, and `runWhen` expressions.

An unused Flow receives structural validation. Context references that depend on a caller or a concrete Case are validated for every actual invocation. Invalid plans are rejected before a Run directory is created.

## 7. Runtime and evidence

The runtime pushes an Action-scope frame when entering a Flow. Completed internal
Actions are published only to that Flow's `EXEC.ACTIONS` view while it runs.
When the Flow returns, the parent `EXEC.ACTIONS` view is restored; internal IDs
are retained under the Flow invocation's result/evidence tree and are not
published as parent-scope Actions.

A Flow frame is retained only for:

- Flow ID, invocation ID, and nesting depth evidence;
- hierarchical Case YAML/log evidence;
- qualified Action IDs used internally; and
- collision-free artifact directories.

Artifacts retain paths such as:

```text
<case>/<stage>/flows/<invocation>/actions/<action>/...
```

Nested Flow evidence remains below the calling Flow Action. Evidence paths such as `CASE.STAGES.<stage>.TEMPLATE.ACTIONS.<flowCall>.flow.actions...` are diagnostic data and are not a supported expression contract. Use `${EXEC.ACTIONS.<internalActionId>...}` only inside the scope that owns that Action; publish cross-scope values explicitly through `${EXEC.VARS.<name>}`.

## 8. Control and result semantics

Action `runWhen` and `onFailure: stop|continue` behave identically inside and outside a Flow. A Flow Action whose own `runWhen` is false is SKIPPED and does not execute its children. An invoked Flow whose executed children are all PASS or SKIPPED is PASS.

Flow aggregation retains the common priority:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Existing Excel, HTML, JUnit, JSON, and CI consumers continue to consume the top-level Flow Action result. Internal hierarchy remains available in Case evidence.

## 9. Migration

Earlier isolated V3 Flow packages must be migrated:

- remove Flow `inputs` and `outputs`;
- remove invocation `with`;
- replace `${input.x}` with the canonical `${EXEC.INPUT.x}` source;
- replace `${actions.x...}` with `${EXEC.ACTIONS.x...}`;
- replace `${runtime.x}` with `${EXEC.VARS.x}`; and
- replace `${ACTIONS.<flowCall>.output.outputs.x}` with an explicit `assign` to `EXEC.VARS` when the value must cross a Flow boundary;
- replace CASE business aliases, `CASE.VARS`, `ACTIONS`, `RUN`, and `CASE.outputDirectory` with their canonical paths. `att validate` reports these as `CONTEXT_LEGACY_PATH` warnings with exact replacements;
- remove direct `CASE.STAGES` expression reads and cross-scope `EXEC.ACTIONS` reads. These are errors (`CONTEXT_CROSS_SCOPE`) because Stage/Flow history is evidence, not reusable input;
- replace Tool-local `${argument}` shorthand with `${input.argument}`. The shorthand remains compatible only for a uniquely declared Tool argument and produces `CONTEXT_TOOL_INPUT_SHORTHAND`.
- treat rootless `${...}` references as deprecated convenience syntax. `att validate` supplies an exact replacement only when the current scope proves one unique canonical path; otherwise it emits a warning without guessing `EXEC.INPUT`.

This is intentionally a breaking reinterpretation of `att-flow/v3.0` and `att-template/v3.0`. No compatibility mode is provided. Non-Flow V2 Templates remain compatible.

## 10. Expression, DB, logging, and integration extensions

`${...}` remains the only Context-reference and text-interpolation syntax. `#{...}` is a complete typed expression block. It supports nested calls, list literals, parentheses, unary `+`, unary `-`, `not`, arithmetic `+ - * /`, comparisons, `like`, `in`, `is [not] null`, `and`, and `or`. Arithmetic is numeric, division by zero is an error, and `in` requires a list, array, or Iterable right operand. Append `?` to a complete reference, as in `${EXEC.INPUT.response.body.missing?}`, to return a real null when any map, list entry, root-owned value, or intermediate segment is missing; an existing final null remains null. Strict `${path}` lookup is unchanged, and optional lookup still rejects ambiguity, malformed syntax, and invalid traversal. Bare Context-looking identifiers remain invalid.

Direct DB Actions may use either positional `params` with JDBC `?` placeholders or named `parameters` with `:name` placeholders, never both. Named placeholders are replaced by `?` before preparing the statement; values are never interpolated into SQL. The scanner ignores placeholders in quoted strings and comments and preserves PostgreSQL-style `::` casts. Missing and unused names fail validation. Parameter evidence defaults to resolved values; connection credentials are never included, and package owners may explicitly select `masked` or `types`.

Log Action messages and process stdout/stderr are written as raw UTF-8 Case-log text with CRLF/CR normalized to LF. Structured `case.yaml` evidence remains available, but the human log does not repeat escaped multiline result content. Process capture uses bounded temporary spools that are removed after logging or explicit artifact creation. A Run does not create `process-output`. Tool and DB `saveAs.path: console` writes the requested representation to the Case log and produces no target file.

`prettyPrint(value)` and `format.pretty(value)` format maps, lists, arrays, scalars, and null deterministically with two-space indentation, cycle/depth protection, and the existing built-in output bound. They do not mutate the supplied value.

### 10.1 Tool evidence collectors

A `type: tool` Action may declare an `evidence` map keyed by collector ID. Each collector has a `call`, optional independent `timeoutMs`, and `onFailure: continue|stop`. The primary Tool attempt completes first; collectors then execute in declaration order while `${output.result}` points to that primary result. Collector results are stored at `output.attempts[n].evidence.<collectorId>` and never replace the primary result. Assertion evaluation follows the collectors. A primary `ASSERTION` retry reruns the primary and all collectors, while a collector failure with `continue` is recorded and does not change the primary assertion outcome. `stop` makes the Action ERROR and skips assertion evaluation.

```yaml
invoke:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.INPUT.requestFile})}"
  evidence:
    queueState:
      call: "#{readQueueState(queue=${EXEC.INPUT.queue})}"
      timeoutMs: 3000
      onFailure: continue
  assert: "${output.result.status} == 'SUCCESS'"
```

### 10.2 IBM MQ helper

MQ helper files use `att-mqhelper/v1.0`, are listed under global `mqhelpers`, and are loaded before package validation. The Java adapter uses IBM MQ client classes in TCP mode; the default ATT build keeps those classes optional and resolves them from the runtime `lib/` directory. Calls are restricted to primary Tool Actions:

```text
#{mq.<instance>.send(queue='QUEUE.IN', file='request.bin')}
#{mq.<instance>.receive(queue='QUEUE.OUT', waitMs=5000)}
#{mq.<instance>.request(requestQueue='QUEUE.IN', replyQueue='QUEUE.OUT', file='request.bin')}
```

Send and request read file bytes without text conversion. Request captures the PUT MsgId and uses it as the GET CorrelId; receive/request with reason 2033 is a successful no-message result. Connections and queues are invocation-scoped, syncpoint is disabled, reply bytes are written once below the Case output directory, and MQ evidence contains metadata and reason/status fields but not credentials or full payload bytes.

## 11. Acceptance criteria

V3.4 is complete when:

- schema validation rejects `inputs`, `outputs`, and `with`;
- the same Action configuration runs inline or in a Flow without expression changes;
- Flow render payloads read Case data directly;
- caller, internal, nested, and following Actions share one ordered `ACTIONS` view;
- all expanded ID and `EXEC.VARS` collisions fail before execution;
- Flow Actions expose no `output.outputs`;
- nested evidence and qualified artifacts remain intact;
- all V2 compatibility tests pass; and
- multiline Excel and Action values remain physical Case-log lines;
- no default process-output artifact exists and console `saveAs` produces no file;
- arithmetic, `in`, named DB binding, visible parameter evidence, and `prettyPrint` pass validation and runtime tests; and
- evidence collectors run after primary output, repeat with assertion retries, and honor independent failure policy; and
- MQ configuration, validation, exact-byte send/receive/request behavior, correlation matching, no-message semantics, and safe evidence pass tests; and
- source, package, documentation, build, and unpacked-package validation gates pass.
