## 08 Reliability and Execution Control

This chapter owns cross-cutting public execution behavior.

### Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception/infrastructure problem is `ERROR`; invalid authoring/configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are therefore distinct.

### `runWhen` and `onFailure`

`runWhen` controls whether a statically known Action/Stage is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes the failed status into PASS. Cleanup/diagnostic work should use the documented conditional execution semantics rather than hiding failures.

### Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic/evidence. Timeout is an operational failure; it is not an assertion false result. Tool timeout behavior and resource-specific DB/MQ limits are documented in their resource contracts.

### Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final/winning operation becomes top-level `output.result` / `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

### Evidence collectors

Tool evidence collectors run after the primary operation has published its typed `output.result` and before that attempt's assertion. While the Action is active, `${output.evidence.collectors.<id>.result}` and `${output.evidence.collectors.<id>.status}` are available; after publication the canonical paths are `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` and `.status`. Collectors have independent `timeoutMs` and `onFailure: continue|stop`. Collector output belongs to the attempt's evidence and never replaces or mutates the primary operation result.

Collectors run once per primary attempt. The top-level collector node represents the final/winning attempt, while `output.attempts[n].evidence.collectors.<id>` retains each attempt. `continue` keeps the primary/assertion outcome visible when diagnostic collection fails; `stop` makes the collector failure an Action error. Use an ordinary Tool/Log/Assign Action when the collected value is business/test data rather than pre-assertion diagnostics.

### Transaction/resource lifecycle

DB transaction finalization and DB/MQ resource cleanup occur at the appropriate execution lifecycle boundary. These mechanisms can affect operation success/diagnostics but are internal resource state, not public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

### Stage execution controls

| Setting | Values/default | Meaning |
|---|---|---|
| `required` | boolean/`false` | Whether a blank selector is an error |
| `runWhen` | `normal`/default, `onSuccess`, `onFailure`, `always` | When the stage is eligible to run |
| `onFailure` | `stop`/default, `continue` | Whether later eligible work may continue |

`continue` never changes FAIL or ERROR into PASS. It only permits later eligible work to run.

| Earlier outcome | Later `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | Run | Run | Skip | Run |
| FAIL/ERROR with `stop` | Skip | Skip | Run | Run |
| FAIL/ERROR with `continue` | Run | Skip | Run | Run |

Use `onFailure` for rollback/diagnostics and `always` for cleanup or final evidence collection.


### Tool timeout precedence

Tool Action timeout overrides Tool descriptor timeout, which overrides global timeout. Sidecars, Stages and Templates do not own timeout/retry defaults. For call-backed DB Tools, the DBHelper statement timeout remains a backend ceiling. Each supported primary retry attempt runs its collectors before assertion; collector continuation behavior does not turn a failed primary operation into PASS.

### Direct DB timeout and retry eligibility

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. When present, `Action.timeoutMs` overrides `DBHelper.statement.timeoutSeconds`; otherwise the helper timeout is used. Each retry attempt gets a fresh Action timeout, and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION` and/or `TIMEOUT`. An explicit Action `timeoutMs` overrides the helper's statement-timeout default; without it, the helper default applies. JDBC query timeout is rounded up to whole seconds while ATT retains millisecond deadline cancellation. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; the top-level `output.result` / `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

Direct `update` Actions support `timeoutMs` but deliberately reject `retry`. A timeout or database/transport failure cannot generally prove whether a mutation reached or committed at the server, so generic automatic replay could duplicate business state. Application-specific idempotent retry must be modeled explicitly instead.
