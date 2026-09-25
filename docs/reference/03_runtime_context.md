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

`EXEC.ID` identifies the current execution unit; `EXEC.RUN_ID` identifies its enclosing ATT run. Testcase IDs are canonical Case IDs, debug uses the debug ID for both, and each load iteration receives a unique execution ID across the run. Legacy `RUN.id` and `RUN.runId` remain deterministic aliases of `EXEC.RUN_ID`. These framework-owned fields cannot be overridden by input.

The framework records mode, timestamps, and load scheduler metadata in the `DIAG` evidence section. `DIAG` is deliberately absent from expression Context: `${EXEC.MODE}`, `${EXEC.LOAD...}`, and `${DIAG...}` are invalid. Use `EXEC.INPUT` for business variation. Load evidence may contain:

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

### Optional lookup

`${path}` is strict. `${path?}` returns null for a missing map/list path where optional lookup is defined, but it does not make malformed syntax, ambiguity, invalid traversal, or illegal scope access valid.

### Compatibility aliases

Deterministic legacy views such as `CASE`, `RUN`, and `ACTIONS` remain readable where they map one-to-one to canonical data and may produce migration warnings. New documentation and new authoring use `EXEC`/`META`. Semantically incompatible historical paths such as stage-history-as-runtime-state are errors rather than aliases.
