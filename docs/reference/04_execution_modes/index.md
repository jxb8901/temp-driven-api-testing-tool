## 04 Execution Modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ execution semantics.

| Mode | Context `EXEC.MODE` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | one target invocation | `output/debug/<debugId>/` |
| Load | `load` | repeated target iterations | `output/load/<runId>/` |

All three resolve the selected environment before execution, construct canonical Context, validate the target/dependency closure, and use the same component contracts. Mode-specific scheduling, selection and reporting do not create alternate Template or expression semantics.
