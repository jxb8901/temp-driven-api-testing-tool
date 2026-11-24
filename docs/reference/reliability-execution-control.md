# Reliability and execution control

This page defines cross-cutting behavior for Native Flow Actions and their Resource calls.

## Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception or infrastructure problem is `ERROR`; invalid authoring or configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are distinct.

## `runWhen` and `onFailure`

`runWhen` controls whether an Action is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes a failed status into PASS. Use conditional execution for cleanup and diagnostics without hiding a failed primary operation.

## Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic evidence. Timeout is an operational failure, not a false assertion. Tool timeout behavior and DB/MQ/HTTP/SSH limits are defined by their Resource contracts.

## Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final or winning operation becomes top-level `output.result` and `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

## Evidence collectors

Tool evidence collectors run after the primary operation publishes its typed `output.result` and before that attempt's assertion. While the Action is active, `${output.evidence.collectors.<id>.result}` and `${output.evidence.collectors.<id>.status}` are available; after publication, the canonical paths are `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` and `.status`. Collectors have independent `timeoutMs` and `onFailure: continue|stop`. Their output belongs to attempt evidence and never replaces the primary operation result.

Collectors run once per primary attempt. The top-level collector node represents the final or winning attempt, while `output.attempts[n].evidence.collectors.<id>` retains each attempt. `continue` keeps the primary outcome visible when diagnostic collection fails; `stop` makes collector failure an Action error. Use an ordinary Tool or Log Action when the collected value is business or test data rather than pre-assertion diagnostics.

## Transaction and Resource lifecycle

DB transaction finalization and DB/MQ/HTTP/SSH cleanup occur at the appropriate execution boundary. These mechanisms can affect operation success and diagnostics, but they are internal Resource state rather than public Context namespaces.

## Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

~~~text
ERROR > INVALID > FAIL > PASS > SKIPPED
~~~

## Tool timeout precedence

Tool Action timeout overrides the Tool descriptor timeout, which overrides the global timeout. For call-backed DB Tools, the DBHelper statement timeout remains a backend ceiling. Each supported primary retry attempt runs its collectors before assertion.

## Direct DB timeout and retry eligibility

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. When present, `Action.timeoutMs` overrides `DBHelper.statement.timeoutSeconds`; otherwise the helper timeout is used. Each retry attempt gets a fresh Action timeout, and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION`, `TIMEOUT`, or both. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; top-level `output.result` and `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

Direct `update` Actions support `timeoutMs` but reject `retry`. After a timeout or database/transport failure, ATT generally cannot prove whether a mutation reached or committed at the server. Automatic replay could duplicate business state, so any application-specific idempotent retry must be modeled explicitly.
