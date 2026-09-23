## 03 Runtime and Context Model

ATT uses one public Context model for Run, Debug and each Load iteration.

### Canonical tree

```text
EXEC
├── ID
├── MODE
├── STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
├── ACTIONS
└── LOAD          # load mode only

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

### Load-only Context

`EXEC.LOAD` is conditional data added to the same Context model, not a second runtime. It may contain `RUN_ID`, `MODEL`, `USER_ID`, `ITERATION_ID`, `ITERATION`, `PHASE`, and `RUN_STARTED_AT`. Closed workloads provide a stable `USER_ID` for one virtual user; fixed-arrival-rate iterations have no persistent VU identity.

### Optional lookup

`${path}` is strict. `${path?}` returns null for a missing map/list path where optional lookup is defined, but it does not make malformed syntax, ambiguity, invalid traversal, or illegal scope access valid.

### Compatibility aliases

Deterministic legacy views such as `CASE`, `RUN`, and `ACTIONS` remain readable where they map one-to-one to canonical data and may produce migration warnings. New documentation and new authoring use `EXEC`/`META`. Semantically incompatible historical paths such as stage-history-as-runtime-state are errors rather than aliases.
