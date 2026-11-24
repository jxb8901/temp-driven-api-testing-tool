# Execution mode overview

RUN executes selected Cases. Debug adds diagnostics to one Flow or Tool execution. LOAD repeats Flow or Tool workloads under a scheduler. VALIDATE checks the selected inputs without starting business execution.

| Mode | Input | Output location | Starts business work |
|---|---|---|---|
| RUN | Definition and selected Resolved Cases | `output/<runId>/` | Yes, after validation |
| Debug | Flow or Tool target and explicit inputs | `output/debug/<debugId>/` | Yes, for the selected target |
| LOAD | Validated Flow or Tool workloads | `output/load/<runId>/` | Yes, when the scheduler starts |
| VALIDATE | Package or selected dependency closure | Validation report | No |

Modes share the same Expression, Action, and Resource contracts. Selection and scheduling affect which work starts; they do not define alternate input-binding semantics. For an earlier authoring model, use the [Legacy documentation](../../legacy/README.md).
