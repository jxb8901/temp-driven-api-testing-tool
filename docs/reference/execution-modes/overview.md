# Execution modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ/HTTP/SSH execution semantics.

| Mode | `EXEC.ID` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | one target invocation | `output/debug/<debugId>/` |
| Load | unique iteration execution ID | repeated target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` identifies the enclosing ATT run. Mode and scheduler-specific information are retained under evidence-only `DIAG`; neither `EXEC.MODE`, `EXEC.LOAD`, nor `DIAG` is available to expressions.

All three resolve the selected environment before execution, construct canonical Context, validate the target/dependency closure, and use the same component contracts. Mode-specific scheduling, selection and reporting do not create alternate Template or expression semantics.

| Mode | Typical use |
|---|---|
| Run | Normal functional SIT/UAT execution |
| Debug | Isolate one Template/Flow/Tool during authoring or diagnosis |
| Load | Repeated/concurrent performance execution |

All three share the execution model. Each mode owns its inputs, identity/bootstrap lifecycle, CLI behavior and output layout.
