# Runtime and Context model

RUN, Debug, and LOAD share the canonical `EXEC` and `META` expression roots. `EXEC` holds identity, typed inputs, variables, and completed Actions. `META` describes the selected source, target, Flow, and active Resource invocation. These roots contain no secrets.

Standalone Debug maps `inputs` to `EXEC.INPUT`; optional `vars` seed `EXEC.VARS`. See [Standalone Debug](execution-modes/debug.md) for the input schema and protected roots.

## EXEC roots

| Path | Meaning and availability |
|---|---|
| `EXEC.ID` | Current Case execution, Debug execution, or LOAD iteration ID; available after initialization. |
| `EXEC.RUN_ID` | Enclosing ATT Run ID; shared by Cases and iterations in a run. |
| `EXEC.STARTED_AT` | Current execution start time as an ISO-8601 string. |
| `EXEC.RUN_STARTED_AT` | Enclosing run start time as an ISO-8601 string. |
| `EXEC.OUTPUT_DIR` | Execution workspace path; in LOAD it may be planned before the directory exists. |
| `EXEC.INPUT` | Typed input map for a Case, Debug target, or LOAD workload. |
| `EXEC.VARS` | Typed values created by `assign`, updated by `set`, or supplied as Debug bootstrap variables. |
| `EXEC.ACTIONS` | Completed Actions in the active Flow scope. A nested Flow receives a separate scope. |
| `EXEC.LOAD.*` | Workload and iteration identity available only during LOAD. `USER_ID` is absent for arrival-rate workloads. |

Scheduler counters, queue state, and timing diagnostics remain in evidence; they are not expression roots. A reusable Flow should receive business identifiers through its input contract, not infer them from `EXEC.ID`.

## META inventory

| Path | Meaning and availability |
|---|---|
| `META.SOURCE.type` | Source kind: `testcase`, `debug`, or `load`. |
| `META.SOURCE.path` | Logical Package-relative source path when the source is file-backed. |
| `META.SOURCE.caseId` | Qualified Case ID during a Case RUN; absent for Debug and LOAD. |
| `META.SOURCE.scenario` | LOAD scenario identity; absent outside LOAD. |
| `META.TARGET.type` | Resolved target kind: Case, Flow, or Tool. |
| `META.TARGET.id` | Resolved target identifier. |
| `META.FLOW.id` | Active Flow ID during a Flow invocation. |
| `META.FLOW.invocationId` | Calling Action ID during that invocation. |
| `META.FLOW.depth` | One-based nested Flow depth. |
| `META.TOOL.id`, `META.TOOL.type` | Active configured Tool or built-in invocation. |
| `META.DBHELPER.*`, `META.MQHELPER.*`, `META.HTTPHELPER.*`, `META.SSHHELPER.*` | Logical Resource identity during the corresponding invocation. |

META is descriptive and immutable. An inactive component's fields are absent. Resource endpoints, usernames, identity files, and credentials remain inside the executor and are never published as META values.

## Invocation and scope rules

Entering a Flow, Tool, or Resource invocation publishes its active metadata. Nested calls push a scope frame and restore the caller's metadata on return. With no active invocation, its branch is absent; do not depend on “last call” state.

`EXEC.INPUT` is the input map for the current execution. `EXEC.VARS` is available to later Actions in the same Flow invocation. `EXEC.ACTIONS` contains completed Action results in that Flow scope. During an Action, read its local `output`; after completion, the value is published at `EXEC.ACTIONS.<id>.output`.

## Optional lookup

`${path}` is a strict lookup. `${path?}` returns null only for an allowed missing map/list path; it does not make invalid syntax or disallowed scope access valid.

For existing Packages that rely on earlier Context names or metadata, use the [Legacy documentation](../legacy/README.md). The [Actions reference](actions.md) owns Action results; [Debug](execution-modes/debug.md) and [LOAD](execution-modes/load.md) own their input initialization rules.
