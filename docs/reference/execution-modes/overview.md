# RUN and LOAD execution adapters

RUN is the functional execution model for a Case, selected cases, or one Template/Flow/Tool. Debug is an opt-in diagnostic option. LOAD retains its performance engine and concurrency semantics. See [Unified RUN and schedules](unified-run.md) for validation and migration examples.

The existing Engine adapters and output formats remain stable:

| RUN/LOAD adapter | `EXEC.ID` | Execution unit | Main output |
|---|---|---|---|
| RUN: Case runner | canonical Case ID | selected Testcase | `output/<RunID>/` |
| RUN: resource runner (legacy Debug adapter) | debug ID | one target invocation | `output/debug/<debugId>/` |
| LOAD | unique iteration execution ID within a run | repeated target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` identifies the outer ATT run. Mode and scheduler metadata remains evidence-only `DIAG`; `EXEC.MODE`, `EXEC.LOAD` and `DIAG` are unavailable to expressions.

All adapters resolve the environment, construct canonical Context, validate the dependency closure, and use the same Template/Flow/Tool/DB/MQ/HTTP/SSH contracts. Selection, scheduling and reporting do not introduce alternate expression or parameter-binding semantics. Enabling Debug diagnostics changes diagnostic events, not invocation count or privileges.
