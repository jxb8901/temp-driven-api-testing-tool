# Runtime and Execution Internals

Status: Maintainer documentation

This document explains implementation ownership behind the supported contracts in `docs/reference/`. The Reference Manual is authoritative for user-visible behavior; this document must not redefine a conflicting public model.

## Execution-neutral runtime

The canonical runtime roots are `EXEC` and `META`; `output` is Action-local. Run, Debug and Load adapt their inputs into that same model before invoking reusable Templates, Flows and Resources.

```text
Run adapter -----\
Debug adapter ----+--> Execution Context --> Template / Flow / Tool
Load adapter -----/          |
                              +--> EXEC / META
                              `--> Action-local output
```

`EXEC.ACTIONS` is scope-local. A Stage/Template receives a fresh Action scope; a Flow invocation installs another fresh scope and restores the caller scope on return. `EXEC.VARS` is the explicit publication mechanism across those boundaries. `EXEC.LOAD` is attached only by the load adapter and does not fork the runtime model.

Compatibility aliases are views over canonical state where a deterministic mapping exists. Stage history, resource handles, scheduler workers and invocation frames are not promoted into the public Context tree.

## Configuration and environment resolution

CLI parsing selects a base config and optional `--env`. Environment selection is resolved once into an effective framework configuration before Run, Validate, Debug or Load dispatch. Typed DB/MQ bindings therefore enter downstream execution through the same resolved resource registry instead of mode-specific branching.

Secrets are resolved only where supported by the relevant descriptor. Curated `META` data and diagnostics must not publish resolved credentials.

## Validation pipeline

ATT applies schema validation before semantic/dependency validation. The semantic phase resolves configured roots, testcase mappings, snapshots, Template/Flow dependencies, payloads, expressions and resource contracts before external execution.

Package validation discovers the whole configured package. Selected validation and standalone execution modes validate only the dependency closure required by the selected target. Runtime uses validated/compiled descriptors rather than discovering a different contract during Action execution.

## Execution and aggregation

The execution engine preserves ordered Stage/Template/Action semantics. `runWhen` decides eligibility and `onFailure` controls continuation without suppressing failure severity. Parent results aggregate child outcomes with the public order:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Reports, manifests, CLI summaries and CI outputs must consume the same aggregate result rather than recomputing independent status rules.

## Action and operation boundary

Tool, DB and MQ executors return operation data through a common boundary before the Template runner applies Action status, assertion and retry semantics:

```text
operation result/evidence/diagnostic/timing
                  |
                  v
          Action lifecycle
                  |
                  v
     output.result / evidence / attempts
```

Only the final or winning primary operation is exposed at top level. Per-attempt evidence remains in `output.attempts[n]`. JDBC connections/transactions, MQ connections/sessions, process handles and load scheduler state are internal ownership objects, not alternate Context roots.

Tool Actions and direct DB query Actions use the same retry-decision semantics: a retry category must be explicitly configured and another attempt must remain. Direct DB query retry is deliberately limited to `ASSERTION` and `TIMEOUT`; generic SQL failures remain terminal. Direct DB updates may use Action `timeoutMs` but cannot opt into automatic retry because mutation outcome can be uncertain after timeout or transport/database failure.

DB Action timeout is passed into the DB executor for each attempt. The JDBC layer applies the shorter effective bound between the Action timeout and the DBHelper statement timeout; the Action runner owns attempt count and inter-attempt sleep, so `retry.intervalMs` is outside the attempt timeout. This keeps timeout mechanics in the resource executor and retry/status/evidence ownership in the Action runner.

## Run lifecycle and persistence

A normal run validates and plans before reserving `<outputDirectory>/<RunID>/`. Evidence is written under that reserved directory. Completion publishes the run manifest/reports and then updates the latest-completed-run pointer. A colliding Run ID is rejected rather than overwritten.

Debug writes an isolated debug result tree and does not participate in normal latest-run publication. Load owns one load-run output tree and creates per-iteration physical workspaces lazily according to failure/evidence policy.

## Load scheduling and resource ownership

Closed-VU and fixed-arrival-rate schedulers are mode adapters around the shared iteration executor. Closed VUs retain scheduler identity across iterations; arrival-rate execution schedules independent arrivals and records drops when its concurrency cap prevents a start.

Load-run DB/MQ resource pools may be shared by concurrent iterations according to the resource layer, while mutable execution Context (`EXEC.VARS`, `EXEC.ACTIONS`, Action-local output) remains iteration-isolated. Pool/session implementation details must not leak into public expression paths.

## Process and path safety

Process-backed Tools construct explicit argv rather than relying on an implicit shell. Output is bounded/streamed according to the Tool evidence contract. Framework writes normalize and contain paths beneath their intended roots; logical target/resource identifiers are not treated as arbitrary filesystem paths.

## Maintainer verification

Changes to runtime or documentation should preserve these invariants:

- Run/Debug/Load share the canonical Context and reusable component semantics;
- Tool/DB/MQ converge on one Action result/evidence model;
- retry policy remains Action-owned while resource executors own their operation-specific timeout/cancellation mechanisms;
- environment resolution occurs before mode execution;
- scope isolation/restoration is deterministic;
- validation precedes external execution for the validated target closure;
- reports and exit behavior derive from the same status model;
- generated documentation and schemas remain aligned with code/tests.
