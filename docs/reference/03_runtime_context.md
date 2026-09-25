## 03 Runtime and Context Model

ATT uses one public Context model for Run, Debug and each Load iteration.

### Canonical tree

```text
EXEC
├── ID
├── RUN_ID
├── STARTED_AT
├── RUN_STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
└── ACTIONS

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # current Action only
```

`EXEC.INPUT` contains execution inputs adapted from the workbook/stage, debug sidecar, or load scenario. `EXEC.VARS` is the mutable publication area for `assign` and other explicit shared values. `EXEC.ACTIONS` contains completed Actions in the **current** Stage/Template/Flow scope. `META` is curated, immutable and secret-safe. `output` is the active Action's local result and is not a persistent root.

### Scope and lifetime

A normal Testcase owns its Case runtime. `EXEC.VARS` can carry explicitly published values across its Stages/Templates. Each Stage/Template starts a fresh `EXEC.ACTIONS` scope. Invoking a Flow temporarily installs a fresh Action scope for the Flow; nested Actions can read earlier Flow Actions, and the caller's Action scope is restored when the Flow returns. Values needed after a Flow returns must be published through `EXEC.VARS`.

There is no public `EXEC.STAGES`, `EXEC.OUTPUT`, `EXEC.CALL`, or invocation-worker tree. Stage/Flow history belongs to result/report evidence, not reusable expression state. Resource connection/pool/process lifecycle state is internal.

### Action-local output and publication

Executable Actions publish a stable envelope. Fields are present where meaningful:

```text
output
├── status
├── success
├── durationMs
├── result
├── diagnostic
├── evidence
└── attempts[]
```

While an Action is active, use `${output...}`. After it completes in the current scope, use `${EXEC.ACTIONS.<id>.output...}`. `result` is the final/winning primary operation result. Retry history and per-attempt collectors remain under `attempts[n]`; they do not replace the top-level final result.

### Execution identity and diagnostics

The identity and timestamps have mode-specific scope:

| Mode | `EXEC.ID` | `EXEC.RUN_ID` | `EXEC.STARTED_AT` | `EXEC.RUN_STARTED_AT` |
|---|---|---|---|---|
| Testcase | Canonical `workbookId.groupId.rowCaseId`; one per Case | The enclosing Run ID, shared by all selected Cases | When this Case starts | When the enclosing Run starts; shared by its Cases |
| Debug | The debug ID; also the `EXEC.RUN_ID` | Same debug ID | When the one-shot Debug execution starts | Same timestamp as `EXEC.STARTED_AT` |
| Load | Unique `<runId>-execution-<n>` for each iteration across the run | The enclosing Load Run ID, shared by all workloads and iterations | When this iteration starts | When the enclosing Load Run starts; shared by its iterations |

For example, two Cases in one Run have different `EXEC.ID` values but the same `EXEC.RUN_ID`:

| Case | `EXEC.ID` | `EXEC.RUN_ID` |
|---|---|---|
| `payments.payment.TC001` | `payments.payment.TC001` | `RUN-42` |
| `payments.payment.TC002` | `payments.payment.TC002` | `RUN-42` |

Two Load iterations (including iterations from different workloads or VUs) share their `EXEC.RUN_ID` but have unique `EXEC.ID` values, such as `LOAD-7-execution-1` and `LOAD-7-execution-2`. A closed VU's stable identity is separate evidence (`DIAG.load.userId`); fixed-arrival iterations have no persistent VU identity.

These framework-owned fields cannot be overridden by input. Legacy `RUN.id` and `RUN.runId` remain deterministic aliases of `EXEC.RUN_ID`.

The framework records mode, timestamps, and load scheduler metadata in the `DIAG` evidence section. `DIAG` is deliberately absent from expression Context: `${EXEC.MODE}`, `${EXEC.LOAD...}`, and `${DIAG...}` are invalid. Normal Template/Flow authors must not depend on or author against the `DIAG` structure; ATT may add, remove, regroup, or rename its fields without Template/Flow compatibility guarantees. Use `EXEC.INPUT` for business variation and `META.TARGET` for curated target identity. Load evidence may contain:

```text
DIAG.load
├── runId
├── workloadId
├── model
├── userId
├── targetType
├── targetId
├── iterationId
├── iteration
└── phase
```

For `att-load/v1.1`, `WORKLOAD_ID` is the configured workload `id`. `TARGET_TYPE` and `TARGET_ID` identify the fixed target owned by that workload. Closed workloads provide a stable `USER_ID` for one virtual user; fixed-arrival-rate iterations have no persistent VU identity.

Different closed-VU workload pools may both contain a `VU-1`. The durable evidence identity is therefore the pair `(workloadId, userId)`. Each iteration still gets isolated `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, transient Tool/DB state and Action-local `output`.

#### Migration from mode-specific Context

| Previous expression/usage | Supported replacement |
|---|---|
| `${EXEC.LOAD.RUN_ID}` | `${EXEC.RUN_ID}` |
| `${EXEC.LOAD.TARGET_TYPE}` / `${EXEC.LOAD.TARGET_ID}` | `${META.TARGET.type}` / `${META.TARGET.id}` where a target is defined (for example, Debug/Load) |
| Branching business behavior on mode, workload, VU, or phase | Pass the intended business selector explicitly through adapter-provided `${EXEC.INPUT.<name>}` |
| Reading scheduler/diagnostic details in expressions | Inspect retained `DIAG` evidence outside expressions; do not reference `DIAG` from Template/Flow code |

### Optional lookup

`${path}` is strict. `${path?}` returns null for a missing map/list path where optional lookup is defined, but it does not make malformed syntax, ambiguity, invalid traversal, or illegal scope access valid.

### Compatibility aliases

Deterministic legacy views such as `CASE`, `RUN`, and `ACTIONS` remain readable where they map one-to-one to canonical data and may produce migration warnings. New documentation and new authoring use `EXEC`/`META`. Semantically incompatible historical paths such as stage-history-as-runtime-state are errors rather than aliases.
