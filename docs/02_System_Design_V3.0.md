# ATT Unified Tool Execution Policy and Reusable Flow Design

**Document Status:** Draft  
**Target Version:** ATT 2.7
**Author:** Jeffrey / ChatGPT  
**Last Updated:** 2026-07-25

---

## 1. Executive Summary

ATT currently provides relatively strong reuse at the Tool layer because Tools are small, reusable capabilities such as invoking an API, querying a database, searching logs, or executing a process.

However, two architectural limitations remain:

1. Timeout and retry behavior differs between process-backed Tools and call-backed Tools.
2. Templates are linear Action sequences but do not provide a reusable composition mechanism.

The first limitation leaks Tool implementation details into Template authoring. A Template author must know whether a Tool is backed by an external process, database helper, built-in function, or another implementation before deciding whether timeout or retry is available.

The second limitation encourages duplicated Action sequences. As business scenarios increase, users may create many nearly identical Templates that differ in only one or two Actions, resulting in Template explosion.

This design introduces two related capabilities:

1. A unified **Tool Invocation Policy** that applies timeout and retry consistently to every Tool invocation.
2. A reusable **Flow** abstraction representing a typed, isolated, statically composed sequence of Actions.

The intended architecture is:

```text
Excel Test Case
      ↓
Template
      ↓
Flow
      ↓
Action
      ↓
Tool
      ↓
Tool Implementation Adapter
```

The responsibilities of each layer are:

```text
Excel       Business test data
Template    Complete business test scenario
Flow        Reusable linear Action sequence
Action      One execution or framework operation
Tool        Reusable capability
Adapter     Backend-specific implementation
```

The design deliberately avoids turning ATT into a general-purpose workflow programming language.

Flows support:

- linear execution;
- static composition;
- typed inputs and outputs;
- isolated scope;
- optional Actions through `runWhen`;
- bounded Flow nesting;
- timeout propagation;
- deterministic validation and reporting.

Flows do not support:

- arbitrary `if/else` blocks;
- loops;
- recursion;
- dynamic Tool or Flow selection;
- mutable global variables;
- user-defined functions;
- exception-handling syntax;
- runtime graph construction.

The central design principle is:

> ATT supports static execution graphs with runtime skipping, not runtime workflow programming.

---

## 2. Background and Problem Statement

### 2.1 Existing Tool execution inconsistency

ATT supports multiple Tool implementation mechanisms, including:

- process-backed Tools;
- SSH or remote-process Tools;
- call-backed Tools;
- database helper calls;
- built-in functions;
- potential future Java SPI implementations.

The current timeout and retry behavior is not consistently exposed across these implementations.

For example:

```text
Process-backed Tool
    Action-level timeout supported
    Exit-code retry supported

Call-backed or database Tool
    Action-level timeout may be rejected
    Database statement timeout configured separately
    Retry behavior unavailable or implementation-specific
```

This creates several problems:

- Template authors must understand Tool implementation details.
- Switching a Tool implementation can break Template configuration.
- Timeout semantics differ between wall-clock invocation timeout and backend operation timeout.
- Retry conditions are coupled to process exit codes.
- Database and future Tool types cannot use the same policy model.
- Reporting cannot present a consistent execution-policy view.

Timeout and retry are not process-specific concerns. They are general Tool invocation concerns.

---

### 2.2 Existing Template reuse limitation

A Template is effectively a linear sequence of Actions:

```text
prepare request
→ invoke API
→ extract response values
→ query database
→ search logs
→ validate result
```

Many Templates share most of these Actions but differ in one or two areas.

Without Template-level reuse, users may create combinations such as:

```text
PAYMENT_SUCCESS
PAYMENT_SUCCESS_WITH_DB
PAYMENT_SUCCESS_WITH_LOG
PAYMENT_SUCCESS_WITH_DB_AND_LOG
PAYMENT_REJECT
PAYMENT_REJECT_WITH_DB
PAYMENT_REJECT_WITH_LOG
PAYMENT_TIMEOUT
PAYMENT_REVERSAL
PAYMENT_REVERSAL_WITH_DB
```

This results in:

- duplicated YAML;
- inconsistent fixes;
- difficult maintenance;
- increased regression risk;
- unclear ownership;
- Template naming proliferation;
- inconsistent evidence and failure handling.

A simple YAML include mechanism would reduce text duplication but would not solve:

- input contracts;
- output contracts;
- variable collisions;
- isolated scope;
- dependency validation;
- versioning;
- reporting;
- execution-policy propagation.

A first-class reusable flow abstraction is therefore required.

---

## 3. Goals

### 3.1 Unified timeout and retry goals

The unified execution-policy design shall:

1. Apply timeout and retry to every configured Tool invocation.
2. Hide backend-specific enforcement details from Template authors.
3. Define timeout as a clear, consistent invocation deadline.
4. Support backend-specific safety limits without exposing them as the primary authoring model.
5. Normalize failures into implementation-independent categories.
6. prevent unsafe retries of non-idempotent operations.
7. support Tool-level defaults and Action-level overrides.
8. record effective timeout, retry, attempts, and cancellation evidence.
9. preserve deterministic execution and reporting.
10. support backward compatibility with existing configurations.

---

### 3.2 Flow goals

The Flow design shall:

1. Enable reuse of common linear Action sequences.
2. reduce copy-and-paste duplication between Templates.
3. keep Templates focused on complete business scenarios.
4. provide typed Flow inputs and outputs.
5. isolate Flow-internal Actions and variables.
6. support static Flow-to-Flow composition.
7. allow validation before execution.
8. preserve hierarchical evidence and reporting.
9. propagate timeout budgets correctly.
10. prevent ATT from evolving into a general-purpose programming language.

---

### 3.3 Product-level goals

Together, the changes shall produce the following authoring model:

```text
Tool
    Reusable capability

Flow
    Reusable implementation sequence

Template
    Complete scenario composition

Excel
    Test data and scenario selection
```

A typical Template should become thin and readable:

```yaml
actions:
  prepare:
    type: flow
    use: payment.prepare.v1
    with: ...

  invoke:
    type: flow
    use: payment.invoke.v1
    with: ...

  verify:
    type: flow
    use: payment.verify-success.v1
    with: ...

  collectEvidence:
    type: flow
    use: common.collect-evidence.v1
    with: ...
```

---

## 4. Non-Goals

This design does not attempt to turn ATT into a workflow engine or programming language.

The following capabilities are explicitly out of scope:

- arbitrary nested `if/else`;
- `switch` or pattern-matching blocks;
- loops or `forEach`;
- recursion;
- mutable global variables;
- callbacks or hooks;
- Action injection;
- overriding internal Flow Actions;
- dynamic Flow names;
- dynamic Tool names;
- dynamic Action creation;
- user-defined functions;
- try/catch/finally syntax;
- parallel branches;
- distributed workflow scheduling;
- compensation transactions;
- general state-machine modeling.

Complex algorithms or control flows should be implemented in an appropriate language and exposed as a Tool.

Examples include:

- Java;
- Python;
- Shell;
- database stored procedures;
- dedicated API services.

ATT remains responsible for:

- input resolution;
- invocation;
- timeout;
- retry;
- output capture;
- assertion;
- evidence;
- reporting.

---

## 5. Design Principles

### 5.1 One Tool abstraction

Templates and Flows shall interact with one Tool abstraction regardless of whether the Tool is implemented through:

- a local process;
- an SSH process;
- a database helper;
- an internal function;
- an HTTP client;
- a Java extension;
- another future adapter.

Implementation differences belong inside the Tool adapter.

---

### 5.2 Policy and mechanism separation

ATT owns policy semantics:

```text
timeout resolution
retry resolution
attempt scheduling
backoff
retry-safety validation
deadline propagation
evidence generation
```

The Tool adapter owns enforcement mechanisms:

```text
process termination
SSH channel cancellation
JDBC query timeout
JDBC statement cancellation
HTTP request cancellation
thread interruption
failure classification
```

---

### 5.3 Composition over inheritance

Templates and Flows shall reuse behavior through composition.

Allowed:

```text
Template → Flow → Flow → Action → Tool
```

Not supported:

```text
Template extends Template
Flow overrides Flow Action
Template overrides inherited Action
```

Composition keeps the final execution plan explicit and avoids inheritance chains.

---

### 5.4 Static graph, runtime skipping

The complete execution graph must be resolvable during validation.

Runtime expressions may decide whether a known Action is executed:

```yaml
runWhen: "#{input.verifyDatabase == true}"
```

Runtime expressions may not determine which Flow, Tool, or Action exists.

Allowed:

```yaml
verifyDatabase:
  type: flow
  use: payment.verify-db.v1
  runWhen: "#{input.verifyDatabase == true}"
```

Not allowed:

```yaml
verify:
  type: flow
  use: "#{input.verifyFlow}"
```

The runtime graph remains fixed. Runtime evaluation may only change a node from executable to `SKIPPED`.

---

### 5.5 Explicit contracts

Tools and Flows shall declare:

- inputs;
- outputs;
- types;
- required fields;
- defaults;
- execution policy;
- retry safety.

Implicit dependencies on global context should be minimized.

---

### 5.6 Isolated scope

Each Flow invocation shall have an isolated scope.

A Flow shall access external data only through declared inputs and shall expose data only through declared outputs.

This prevents:

- variable collisions;
- implicit dependencies;
- accidental context mutation;
- fragile internal Action references.

---

### 5.7 Deterministic and explainable execution

ATT must be able to explain the complete execution plan before running it.

For example:

```bash
att explain --template payment.success.v1
```

The output should show:

- resolved Template;
- resolved Flows;
- Tool dependencies;
- input bindings;
- output bindings;
- conditional Actions;
- timeout hierarchy;
- retry policies;
- dependency versions and digests.

---

## 6. Conceptual Model

### 6.1 Tool

A Tool represents one reusable capability.

Examples:

```text
invoke payment API
query transaction table
search application logs
render XML request
copy file
execute remote command
```

A Tool may have one of several implementations, but its public contract remains stable.

---

### 6.2 Action

An Action represents one operation inside a Template or Flow.

Examples:

- invoke a Tool;
- invoke a Flow;
- assign a value;
- render a request;
- assert a result;
- publish an output.

---

### 6.3 Flow

A Flow is a reusable, typed, isolated, linear sequence of Actions.

A Flow:

- cannot be selected directly by an Excel stage;
- declares inputs and outputs;
- may invoke Tools;
- may invoke other Flows;
- may use limited `runWhen`;
- produces hierarchical evidence;
- has a bounded execution deadline;
- cannot recursively invoke itself.

---

### 6.4 Template

A Template is a complete business scenario exposed to a test stage.

A Template:

- may contain Actions;
- may compose multiple Flows;
- may bind Excel or case data to Flow inputs;
- is selected by an Excel stage;
- defines the scenario-level execution result.

A Template should not normally be called by another Template or Flow.

---

### 6.5 Stage

A Stage remains a high-level testcase lifecycle node configured by the sidecar and selected through Excel data.

Examples:

```text
prepare
invoke
verify
rollback
cleanup
```

A Stage selects a Template, not a Flow.

---

## 7. Proposed Configuration Model

## 7.1 Unified Tool execution policy

A Tool may define a default execution policy:

```yaml
schemaVersion: att-config/v2.7

tools:
  findOrders:
    name: Find Orders
    description: Find orders belonging to a customer

    call: >-
      #{db.orders.query(
        sql='select * from orders where customer_id = ?',
        params=[input.customerId]
      )}

    arguments:
      customerId:
        type: string
        required: true

    execution:
      timeoutMs: 20000

      retry:
        maxAttempts: 3
        retryOn:
          - TRANSIENT_ERROR
          - TIMEOUT
        delayMs: 500
        backoff: exponential
        maxDelayMs: 5000

    semantics:
      sideEffects: none
      retrySafety: safe
```

---

## 7.2 Action-level execution override

A primary Tool Action may override Tool defaults:

```yaml
actions:
  loadOrders:
    type: tool

    call: >-
      #{orders.findOrders(
        customerId=input.customerId
      )}

    timeoutMs: 10000

    retry:
      maxAttempts: 2
      retryOn:
        - TRANSIENT_ERROR
```

This configuration is valid regardless of whether `findOrders` is backed by:

- a process;
- a database call;
- an internal function;
- a future Java extension.

---

## 7.3 Timeout configuration

The recommended explicit timeout fields are:

```yaml
execution:
  totalTimeoutMs: 30000
  attemptTimeoutMs: 10000
```

Definitions:

| Field | Meaning |
|---|---|
| `totalTimeoutMs` | Maximum wall-clock duration for the complete Tool invocation, including retries and delays |
| `attemptTimeoutMs` | Maximum duration of one attempt |
| backend safety timeout | Adapter-specific maximum configured outside the public invocation policy |

For compatibility, existing `timeoutMs` may remain supported as an alias.

Recommended migration:

```text
Existing schema:
timeoutMs = per-attempt timeout

New schema:
totalTimeoutMs = total Tool invocation deadline
attemptTimeoutMs = individual attempt limit
```

If only `timeoutMs` is provided in a new schema, ATT may map it to `totalTimeoutMs` and issue a migration warning.

---

## 7.4 Retry configuration

```yaml
retry:
  maxAttempts: 3

  retryOn:
    - TRANSIENT_ERROR
    - TIMEOUT

  delayMs: 500
  backoff: exponential
  multiplier: 2.0
  maxDelayMs: 5000
```

Suggested normalized failure categories:

| Category | Meaning |
|---|---|
| `TIMEOUT` | ATT deadline or backend timeout |
| `TRANSIENT_ERROR` | Temporary backend or availability failure |
| `EXIT_CODE` | Selected process exit code |
| `IO_ERROR` | Temporary local, network, SSH, or stream failure |
| `RATE_LIMIT` | Backend rate-limit response |
| `DB_ERROR` | Selected SQLState or database vendor error |
| `RESULT_ERROR` | Invalid or unparsable Tool output |
| `ASSERTION_FAILURE` | Business assertion failed after successful invocation |

The following should not be retried by default:

- configuration errors;
- validation errors;
- expression syntax errors;
- missing required arguments;
- output contract violations;
- permanent authentication failures;
- permanent database errors;
- assertion failures.

---

## 7.5 Backend-specific retry matchers

Adapters may expose optional matchers while retaining the generic retry model.

Example:

```yaml
retry:
  maxAttempts: 3

  retryOn:
    - EXIT_CODE
    - DB_ERROR

  exitCodes:
    - 1
    - 75

  sqlStates:
    - "40001"
    - "40P01"

  vendorCodes:
    - 60
```

The adapter converts the backend result into a normalized failure.

Example:

```text
Process exit code 75
    → EXIT_CODE
    → retry matcher succeeds

SQLState 40001
    → DB_ERROR
    → retry matcher succeeds
```

---

## 7.6 Retry safety

Each Tool that may be retried shall declare retry safety:

```yaml
semantics:
  sideEffects: none
  retrySafety: safe
```

Supported values:

| Value | Meaning |
|---|---|
| `safe` | The operation has no externally visible side effects |
| `idempotent` | Repeating the operation produces the same effective result |
| `requiresKey` | Retry is allowed only when an idempotency key is supplied |
| `unsafe` | Retrying may duplicate or corrupt business effects |
| `unknown` | Safety has not been declared |

Example with an idempotency key:

```yaml
semantics:
  sideEffects: write
  retrySafety: requiresKey
  idempotencyKeyArgument: requestId
```

Validation rules:

```text
maxAttempts = 1
    Always valid

maxAttempts > 1 and retrySafety = safe
    Valid

maxAttempts > 1 and retrySafety = idempotent
    Valid

maxAttempts > 1 and retrySafety = requiresKey
    Valid only when the key is bound

maxAttempts > 1 and retrySafety = unsafe or unknown
    Validation error
```

---

## 7.7 Database timeout configuration

Database helpers may retain a native safety limit:

```yaml
dbhelpers:
  orders:
    statement:
      timeoutSeconds: 60
```

This field is not the public Tool invocation timeout. It is a database safety limit.

The effective database statement timeout is:

```text
minimum of:
- remaining Tool attempt deadline;
- remaining Tool total deadline;
- database helper statement safety limit.
```

Example:

```text
Tool total timeout:           30 seconds
Tool attempt timeout:         10 seconds
DB helper statement timeout:  60 seconds

Effective JDBC timeout:       10 seconds
```

Another example:

```text
Tool total timeout:           90 seconds
Tool attempt timeout:         90 seconds
DB helper statement timeout:  60 seconds

Effective JDBC timeout:       60 seconds
```

The outer ATT deadline still applies to:

- argument resolution;
- connection preparation;
- statement execution;
- result extraction;
- output conversion;
- evidence writing.

---

## 8. Execution Policy Resolution

### 8.1 Precedence

The recommended timeout precedence is:

```text
Action override
    ↓
Tool default
    ↓
Template or sidecar default
    ↓
Global ATT default
    ↓
Framework fallback
```

Example:

```text
action.totalTimeoutMs
→ tool.execution.totalTimeoutMs
→ sidecar.execution.totalTimeoutMs
→ config.execution.totalTimeoutMs
→ framework default
```

Retry precedence should be more conservative:

```text
Action retry override
→ Tool retry default
→ no retry
```

A global retry default should not be introduced initially because it may retry unsafe operations unexpectedly.

---

### 8.2 Deadline propagation

ATT shall calculate an absolute deadline for each execution scope.

```text
Stage deadline
    └── Template deadline
          └── Flow deadline
                └── Action deadline
                      └── Tool attempt deadline
```

The effective deadline is always the earliest applicable deadline.

```text
effective deadline =
minimum of:
- Action deadline;
- Flow remaining deadline;
- Template remaining deadline;
- Stage remaining deadline.
```

No child operation may outlive its parent scope.

---

### 8.3 Retry and total budget

Suppose:

```yaml
totalTimeoutMs: 30000
attemptTimeoutMs: 10000

retry:
  maxAttempts: 3
  delayMs: 1000
  backoff: exponential
```

A possible execution is:

```text
Attempt 1: 10 seconds
Delay:      1 second
Attempt 2: 10 seconds
Delay:      2 seconds
Attempt 3: maximum 7 seconds remaining
```

ATT must not blindly allocate three complete ten-second attempts after consuming retry delays.

ATT should not start another attempt when:

- the total deadline has expired;
- cancellation has been requested;
- the failure is not retryable;
- retry safety validation fails;
- no meaningful time remains for another attempt.

---

## 9. Tool Runtime Architecture

### 9.1 Common policy model

Suggested Java model:

```java
public final class ToolInvocationPolicy {
    private final long totalTimeoutMs;
    private final Long attemptTimeoutMs;
    private final RetryPolicy retryPolicy;
}

public final class RetryPolicy {
    private final int maxAttempts;
    private final Set<FailureKind> retryOn;
    private final long delayMs;
    private final BackoffType backoff;
    private final double multiplier;
    private final long maxDelayMs;
}
```

---

### 9.2 Adapter interface

```java
public interface ToolImplementationAdapter {

    ToolAttemptResult invoke(
        ResolvedToolCall call,
        AttemptContext context
    );

    CancellationResult cancel(
        CancellationContext context
    );

    FailureClassification classifyFailure(
        Throwable error,
        ToolAttemptResult result
    );
}
```

The common `ToolExecutor` owns:

- policy resolution;
- deadline calculation;
- attempt scheduling;
- retry loop;
- backoff;
- retry-safety checks;
- attempt evidence;
- final result aggregation.

The adapter owns:

- backend invocation;
- backend cancellation;
- native timeout configuration;
- backend error parsing;
- normalized failure classification.

---

### 9.3 Adapter-specific behavior

#### Process adapter

The process adapter shall:

- start the process;
- collect stdout and stderr;
- wait until the attempt deadline;
- terminate gracefully when possible;
- force termination when required;
- classify configured exit codes;
- record process ID and termination evidence.

#### SSH adapter

The SSH adapter shall:

- apply remaining attempt timeout;
- close or cancel the remote channel;
- distinguish local timeout from remote command failure;
- record whether remote termination was confirmed.

#### Database adapter

The database adapter shall:

- set JDBC query timeout;
- schedule statement cancellation;
- apply the database helper safety limit;
- classify SQLState and vendor codes;
- avoid retrying unknown write outcomes unless declared safe.

#### Built-in adapter

The built-in adapter shall:

- execute under the ATT deadline;
- cooperate with cancellation where possible;
- classify deterministic validation errors as non-retryable;
- classify temporary external dependencies as transient only when explicitly supported.

---

## 10. Flow Configuration

### 10.1 Flow document structure

A Flow is stored in a dedicated Flow definition file.

Recommended schema:

```yaml
schemaVersion: att-flow/v1

id: payment.invoke-and-capture.v1
name: Invoke Payment and Capture Response
description: Invoke a payment request and capture common response values

inputs:
  requestFile:
    type: string
    required: true

  environment:
    type: string
    required: true

  expectedHttpStatus:
    type: integer
    default: 200

actions:
  invoke:
    type: tool

    call: >-
      #{paymentApi.invoke(
        requestFile=input.requestFile,
        environment=input.environment
      )}

  verifyHttpStatus:
    type: assert
    actual: "#{actions.invoke.httpStatus}"
    expected: "#{input.expectedHttpStatus}"

outputs:
  transactionReference:
    type: string
    from: "#{actions.invoke.body.transactionReference}"

  responseStatus:
    type: string
    from: "#{actions.invoke.body.status}"

  responseBody:
    type: object
    from: "#{actions.invoke.body}"
```

---

### 10.2 Flow directory structure

Recommended project structure:

```text
templates/
  payment/
    success/
      template.yaml

    reject/
      template.yaml

flows/
  common/
    render-request/
      flow.yaml

    collect-evidence/
      flow.yaml

  payment/
    prepare/
      flow.yaml

    invoke/
      flow.yaml

    verify-success/
      flow.yaml

    verify-reject/
      flow.yaml
```

A directory is considered a Flow only when it contains `flow.yaml`.

A directory is considered a Template only when it contains `template.yaml`.

---

### 10.3 Flow identity

Every Flow shall have a canonical immutable ID:

```yaml
id: payment.invoke.v1
```

Recommended identity fields:

```yaml
id: payment.invoke.v1
name: Invoke Payment
version: 1
```

The identity shall not depend solely on the file path.

The file path is a storage location, not the public contract.

---

### 10.4 Invoking a Flow

A Flow invocation is represented as an Action:

```yaml
actions:
  invokePayment:
    type: flow
    use: payment.invoke.v1

    with:
      requestFile: "#{actions.prepare.outputs.renderedFile}"
      environment: "#{input.environment}"
      expectedHttpStatus: 200
```

This preserves the existing linear Action model.

---

### 10.5 Flow input contract

Flows shall access external values only through `input.*`.

Allowed:

```yaml
call: "#{paymentApi.invoke(file=input.requestFile)}"
```

Not allowed:

```yaml
call: "#{paymentApi.invoke(file=CASE.requestFile)}"
```

The Template is responsible for binding case data:

```yaml
actions:
  invoke:
    type: flow
    use: payment.invoke.v1

    with:
      requestFile: "${CASE.requestFile}"
      environment: "${CASE.environment}"
```

This makes all Flow dependencies visible and validates them before execution.

---

### 10.6 Flow output contract

Flows shall expose only declared outputs.

```yaml
outputs:
  transactionReference:
    type: string
    from: "#{actions.invoke.body.transactionReference}"

  responseStatus:
    type: string
    from: "#{actions.invoke.body.status}"
```

A caller accesses outputs through the Flow Action:

```text
actions.invokePayment.outputs.transactionReference
actions.invokePayment.outputs.responseStatus
```

A caller shall not depend on internal Flow Actions:

```text
actions.invokePayment.actions.invoke.body.status
```

Internal Action evidence may be visible for debugging, but it is not part of the stable Flow API.

---

### 10.7 Scope isolation

Every Flow invocation shall create a local scope containing:

```text
input.*
actions.*
outputs.*
runtime.*
flow.*
```

A Flow shall not automatically access:

```text
CASE.*
STAGE.*
parent Flow internals
sibling Flow internals
global mutable variables
```

The parent receives only:

```text
Flow status
Flow outputs
Flow evidence reference
```

This prevents namespace collisions and hidden coupling.

---

### 10.8 Nested Flows

A Flow may invoke another Flow:

```yaml
actions:
  pollUntilComplete:
    type: flow
    use: common.poll-transaction.v1

    with:
      transactionReference: "#{actions.invoke.outputs.transactionReference}"
```

Restrictions:

- Flow references must be static.
- Recursive calls are forbidden.
- Dependency cycles are forbidden.
- Maximum nesting depth is configurable.
- The recommended default maximum depth is three.
- The hard maximum should remain small, such as five.

Invalid dependency:

```text
payment.full.v1
→ payment.invoke.v1
→ common.transport.v1
→ payment.full.v1
```

Validation error example:

```text
ATT-FLOW-004:
Flow dependency cycle detected:

payment.full.v1
→ payment.invoke.v1
→ common.transport.v1
→ payment.full.v1
```

---

## 11. Flow Execution Semantics

### 11.1 Linear execution

Flow Actions execute in declaration order.

```text
Action 1
→ Action 2
→ Action 3
```

No arbitrary graph edges are introduced in the first version.

---

### 11.2 Conditional execution

Flows may use the existing limited `runWhen` capability:

```yaml
actions:
  verifyDatabase:
    type: flow
    use: payment.verify-db.v1

    runWhen: "#{input.verifyDatabase == true}"
```

This means:

> A statically known Action may be skipped at runtime.

ATT shall not introduce general conditional blocks.

Not supported:

```yaml
if: "#{input.channel == 'FPS'}"
then:
  - ...
else:
  - ...
```

Instead, use static conditional Actions:

```yaml
actions:
  verifyFps:
    type: flow
    use: payment.verify-fps.v1
    runWhen: "#{input.channel == 'FPS'}"

  verifyRtg:
    type: flow
    use: payment.verify-rtg.v1
    runWhen: "#{input.channel == 'RTG'}"
```

---

### 11.3 Failure behavior

The existing `onFailure` behavior may be reused:

```yaml
onFailure: stop
```

or:

```yaml
onFailure: continue
```

`continue` controls execution continuation. It does not automatically remove the failure from the final result.

A separate field should control result impact when needed:

```yaml
failureImpact: warning
```

This avoids mixing:

```text
control-flow behavior
and
result-severity behavior
```

---

### 11.4 Flow result aggregation

Recommended result priority:

```text
INVALID
    >
ERROR
    >
FAIL
    >
PASS
    >
SKIPPED
```

Suggested rules:

| Internal result | Flow result |
|---|---|
| All Actions are `PASS` or `SKIPPED` | `PASS` |
| At least one assertion is `FAIL` | `FAIL` |
| At least one execution error occurs | `ERROR` |
| Contract or expression validation fails | `INVALID` |
| Flow is not executed because `runWhen` is false | `SKIPPED` |

An Action configured with `failureImpact: warning` contributes warning evidence but does not necessarily fail the Flow.

---

### 11.5 Always-run cleanup

ATT may introduce a limited capability:

```yaml
runAlways: true
```

Example:

```yaml
actions:
  cleanup:
    type: flow
    use: common.cleanup.v1
    runAlways: true
```

Meaning:

> Execute this known Action even if a previous Action stopped normal execution.

This provides a limited cleanup mechanism without introducing general `try/catch/finally`.

---

## 12. Flow Timeout and Retry

### 12.1 Flow timeout

A Flow Action may define a total Flow deadline:

```yaml
actions:
  invokeAndVerify:
    type: flow
    use: payment.invoke-and-verify.v1
    totalTimeoutMs: 60000
```

The Flow deadline includes:

- nested Flow calls;
- Tool calls;
- assertions;
- retry delays;
- output evaluation;
- evidence generation.

Every child operation receives the smaller of:

- its configured timeout;
- the remaining Flow deadline;
- the remaining parent deadline.

---

### 12.2 Flow retry

Retrying a complete Flow is potentially dangerous.

For example:

```text
create transaction
→ query transaction
→ verify transaction
```

If the query fails after the transaction was created, retrying the entire Flow may create a duplicate transaction.

Therefore:

```yaml
retry:
  maxAttempts: 1
```

shall be the default for Flow Actions.

Whole-Flow retry should be allowed only when the Flow declares retry safety:

```yaml
semantics:
  retrySafety: idempotent
```

In most scenarios, retries should occur at individual Tool Actions inside the Flow rather than at the Flow level.

---

## 13. Template Composition Example

### 13.1 Common preparation Flow

```yaml
schemaVersion: att-flow/v1

id: common.render-request.v1
name: Render Request

inputs:
  templateFile:
    type: string
    required: true

  data:
    type: object
    required: true

actions:
  render:
    type: tool

    call: >-
      #{requestRenderer.render(
        templateFile=input.templateFile,
        data=input.data
      )}

outputs:
  renderedFile:
    type: string
    from: "#{actions.render.outputFile}"
```

---

### 13.2 Payment invocation Flow

```yaml
schemaVersion: att-flow/v1

id: payment.invoke.v1
name: Invoke Payment

inputs:
  requestFile:
    type: string
    required: true

  environment:
    type: string
    required: true

actions:
  invoke:
    type: tool

    call: >-
      #{paymentApi.invoke(
        requestFile=input.requestFile,
        environment=input.environment
      )}

    totalTimeoutMs: 30000

    attemptTimeoutMs: 10000

    retry:
      maxAttempts: 3
      retryOn:
        - TRANSIENT_ERROR

outputs:
  response:
    type: object
    from: "#{actions.invoke.body}"

  transactionReference:
    type: string
    from: "#{actions.invoke.body.transactionReference}"
```

---

### 13.3 Success verification Flow

```yaml
schemaVersion: att-flow/v1

id: payment.verify-success.v1
name: Verify Successful Payment

inputs:
  response:
    type: object
    required: true

  expectedStatus:
    type: string
    required: true

actions:
  verifyStatus:
    type: assert
    actual: "#{input.response.status}"
    expected: "#{input.expectedStatus}"

outputs:
  verifiedStatus:
    type: string
    from: "#{input.response.status}"
```

---

### 13.4 Complete Template

```yaml
schemaVersion: att-template/v2.7

id: payment.success.v1
name: Successful Payment

inputs:
  requestTemplate:
    type: string
    required: true

  requestData:
    type: object
    required: true

  environment:
    type: string
    required: true

  expectedStatus:
    type: string
    required: true

actions:
  prepare:
    type: flow
    use: common.render-request.v1

    with:
      templateFile: "#{input.requestTemplate}"
      data: "#{input.requestData}"

  invoke:
    type: flow
    use: payment.invoke.v1

    with:
      requestFile: "#{actions.prepare.outputs.renderedFile}"
      environment: "#{input.environment}"

  verify:
    type: flow
    use: payment.verify-success.v1

    with:
      response: "#{actions.invoke.outputs.response}"
      expectedStatus: "#{input.expectedStatus}"

  collectEvidence:
    type: flow
    use: common.collect-evidence.v1

    with:
      transactionReference: >-
        #{actions.invoke.outputs.transactionReference}

    runWhen: >-
      #{actions.invoke.outputs.transactionReference != null}

    onFailure: continue
    failureImpact: warning
```

---

## 14. Validation Rules

### 14.1 Tool execution-policy validation

ATT shall validate:

- timeout values are positive;
- `attemptTimeoutMs` does not exceed `totalTimeoutMs` unless intentionally capped at runtime;
- `maxAttempts` is at least one;
- retry categories are supported;
- backend-specific matchers match the Tool implementation;
- retry safety permits multiple attempts;
- required idempotency keys are bound;
- action-level policy fields are valid for every Tool implementation;
- deprecated timeout fields produce migration warnings.

---

### 14.2 Flow validation

ATT shall validate:

- Flow schema version;
- canonical Flow ID;
- duplicate Flow IDs;
- required input definitions;
- output definitions;
- input binding completeness;
- type compatibility;
- output expression validity;
- static Flow references;
- unresolved dependencies;
- dependency cycles;
- nesting depth;
- Action ID uniqueness;
- unsupported control-flow fields;
- direct access to forbidden context;
- timeout compatibility;
- unsafe whole-Flow retry;
- output references to missing internal Actions.

---

### 14.3 Complexity linting

ATT should issue warnings when Flow or Template complexity exceeds recommended limits.

Suggested rules:

```text
Template Actions: recommended maximum 15
Flow Actions: recommended maximum 10
Flow nesting depth: recommended maximum 3
Conditional Actions per Flow: recommended maximum 5
Expression complexity: configurable threshold
```

Example warning:

```text
ATT-FLOW-021:

Flow payment.process.v1 contains 9 conditional Actions.
Consider splitting it into scenario-specific Templates or smaller Flows.
```

These are lint warnings rather than hard validation failures, except for nesting depth and forbidden features.

---

## 15. Compilation and Runtime Planning

### 15.1 Compilation stages

ATT should compile Templates and Flows before execution:

```text
Parse Template and Flow files
        ↓
Resolve canonical dependencies
        ↓
Validate contracts
        ↓
Detect cycles
        ↓
Build scoped execution tree
        ↓
Resolve static policies
        ↓
Create compiled execution plan
```

The compiled plan should preserve hierarchy.

Example:

```yaml
template:
  id: payment.success.v1

actions:
  invoke:
    type: flow
    flowId: payment.invoke.v1

    actions:
      invoke:
        type: tool
        toolId: paymentApi.invoke

      verifyHttpStatus:
        type: assert
```

The implementation may use qualified internal IDs:

```text
invoke.invoke
invoke.verifyHttpStatus
```

However, reports and evidence should retain the parent-child structure.

---

### 15.2 No runtime dependency resolution

The runtime shall not dynamically discover Flow files.

All dependencies shall be resolved during validation or compilation.

Benefits:

- deterministic execution;
- early failure;
- faster runtime;
- stable snapshots;
- clear dependency manifests;
- reproducible evidence.

---

## 16. Evidence and Reporting

### 16.1 Unified Tool evidence

Every Tool Action should record:

```yaml
executionPolicy:
  totalTimeoutMs: 30000
  attemptTimeoutMs: 10000
  timeoutSource: action

  retry:
    maxAttempts: 3
    retryOn:
      - TRANSIENT_ERROR

attempts:
  - number: 1
    durationMs: 1024
    status: ERROR
    failureKind: TRANSIENT_ERROR

  - number: 2
    durationMs: 310
    status: PASS
```

Cancellation evidence:

```yaml
cancellation:
  requested: true
  mechanism: JDBC_STATEMENT_CANCEL
  confirmed: false
```

---

### 16.2 Hierarchical Flow reporting

Summary view:

| Action | Type | Result | Duration |
|---|---|---:|---:|
| prepare | Flow | PASS | 120 ms |
| invoke | Flow | PASS | 842 ms |
| verify | Flow | PASS | 310 ms |
| collectEvidence | Flow | WARNING | 105 ms |

Expanded view:

```text
Template: payment.success.v1

PASS prepare
  PASS render

PASS invoke
  PASS invoke
  PASS verifyHttpStatus

PASS verify
  PASS verifyStatus

WARNING collectEvidence
  ERROR searchApplicationLog
```

---

### 16.3 Evidence directory layout

Recommended layout:

```text
run/
  cases/
    PAYMENT.001/
      stages/
        invoke/
          template.yaml
          compiled-plan.yaml

          actions/
            prepare/
              flow.yaml
              inputs.json
              outputs.json

              actions/
                render/
                  action.json
                  tool-evidence.json

            invoke/
              flow.yaml
              inputs.json
              outputs.json

              actions/
                invoke/
                  attempts/
                    1/
                    2/
```

Each resolved dependency should record:

```yaml
dependency:
  id: payment.invoke.v1
  resolvedPath: flows/payment/invoke/flow.yaml
  digest: sha256:...
```

---

## 17. CLI Changes

Recommended commands:

```bash
att flow list

att flow show payment.invoke.v1

att flow validate payment.invoke.v1

att flow usages payment.invoke.v1

att flow graph payment.success.v1

att explain --template payment.success.v1

att validate --package

att validate --selected
```

Suggested `att explain` output:

```text
Template: payment.success.v1

1. prepare
   Type: Flow
   Flow: common.render-request.v1

   1.1 render
       Type: Tool
       Tool: requestRenderer.render

2. invoke
   Type: Flow
   Flow: payment.invoke.v1
   Total timeout: 30000 ms

   2.1 invoke
       Type: Tool
       Tool: paymentApi.invoke
       Attempts: 3
       Retry on: TRANSIENT_ERROR

3. verify
   Type: Flow
   Flow: payment.verify-success.v1
```

---

## 18. Schema Changes

### 18.1 New Flow schema

Add:

```text
schemas/att-flow-v1.schema.json
```

Primary fields:

```yaml
schemaVersion:
id:
name:
description:
inputs:
actions:
outputs:
execution:
semantics:
```

---

### 18.2 Updated Tool schema

Add or extend:

```yaml
execution:
  totalTimeoutMs:
  attemptTimeoutMs:

  retry:
    maxAttempts:
    retryOn:
    delayMs:
    backoff:
    multiplier:
    maxDelayMs:
    exitCodes:
    sqlStates:
    vendorCodes:

semantics:
  sideEffects:
  retrySafety:
  idempotencyKeyArgument:
```

---

### 18.3 Updated Action schema

A Tool Action supports:

```yaml
type: tool
call:
totalTimeoutMs:
attemptTimeoutMs:
retry:
runWhen:
runAlways:
onFailure:
failureImpact:
```

A Flow Action supports:

```yaml
type: flow
use:
with:
totalTimeoutMs:
retry:
runWhen:
runAlways:
onFailure:
failureImpact:
```

---

### 18.4 Updated Template schema

The Template Action union shall include:

```text
Tool Action
Flow Action
Assign Action
Assert Action
Render Action
Existing supported framework Actions
```

---

## 19. Backward Compatibility and Migration

### 19.1 Existing process-backed Tool behavior

Existing process Tool configuration shall continue to work.

Example:

```yaml
timeoutMs: 10000

retry:
  maxAttempts: 3
  retryOn:
    - EXIT_CODE
  exitCodes:
    - 75
```

ATT should translate the configuration into the new internal policy model.

---

### 19.2 Existing call-backed Tools

Existing call-backed Tools without timeout or retry shall continue to work.

Previously invalid Action-level timeout or retry fields may become valid under the new schema.

---

### 19.3 Existing database timeout

Existing:

```yaml
statement:
  timeoutSeconds: 60
```

shall remain supported as a native database safety limit.

Documentation must clarify that this is no longer the complete Tool invocation timeout.

---

### 19.4 Existing Templates

Existing Templates without Flow Actions remain valid.

Flow is an additive mechanism.

No existing Template must be converted immediately.

---

### 19.5 Migration warnings

Recommended warnings:

```text
ATT-MIGRATION-001:
timeoutMs uses legacy per-attempt semantics.
Consider replacing it with totalTimeoutMs and attemptTimeoutMs.
```

```text
ATT-MIGRATION-002:
Database statement.timeoutSeconds is a backend safety limit.
Configure Tool totalTimeoutMs for an end-to-end invocation deadline.
```

---

## 20. Implementation Recommendations

### 20.1 Implementation phases

#### Phase 1: Internal execution-policy unification

Implement:

- `ToolInvocationPolicy`;
- `RetryPolicy`;
- normalized failure categories;
- common attempt loop;
- deadline model;
- process adapter integration;
- database adapter integration;
- common evidence.

Do not expose Flow yet.

---

#### Phase 2: Tool configuration and compatibility

Implement:

- new Tool schema;
- Action-level overrides;
- legacy configuration translation;
- retry-safety validation;
- migration warnings;
- tests for all existing Tool types.

---

#### Phase 3: Flow parsing and validation

Implement:

- Flow schema;
- Flow registry;
- canonical ID resolution;
- input/output contracts;
- static dependency graph;
- cycle detection;
- maximum nesting depth;
- context isolation.

---

#### Phase 4: Flow runtime

Implement:

- Flow Action executor;
- local Flow context;
- input binding;
- output export;
- failure aggregation;
- deadline propagation;
- nested Flow execution;
- whole-Flow retry restrictions.

---

#### Phase 5: Reporting and CLI

Implement:

- hierarchical reports;
- nested evidence;
- `att flow` commands;
- `att explain`;
- dependency digests;
- Flow usage lookup;
- complexity linting.

---

### 20.2 Suggested packages

Example Java package structure:

```text
com.att.execution.policy
  ToolInvocationPolicy
  RetryPolicy
  Deadline
  BackoffStrategy
  FailureKind
  FailureClassification

com.att.execution.tool
  ToolExecutor
  ToolImplementationAdapter
  ProcessToolAdapter
  DatabaseToolAdapter
  BuiltinToolAdapter

com.att.flow.model
  FlowDefinition
  FlowInputDefinition
  FlowOutputDefinition
  FlowActionDefinition

com.att.flow.validation
  FlowValidator
  FlowDependencyResolver
  FlowCycleDetector
  FlowContractValidator

com.att.flow.execution
  FlowActionExecutor
  FlowContext
  FlowResultAggregator

com.att.plan
  ExecutionPlanCompiler
  CompiledTemplatePlan
  CompiledFlowPlan
```

---

### 20.3 Shared execution scopes

Suggested runtime abstraction:

```java
public interface ExecutionScope {
    Deadline deadline();
    ExecutionContext context();
    EvidenceWriter evidenceWriter();
}
```

Implementations:

```text
StageExecutionScope
TemplateExecutionScope
FlowExecutionScope
ActionExecutionScope
ToolAttemptExecutionScope
```

This simplifies deadline propagation and hierarchical evidence.

---

### 20.4 Failure classification

Each adapter should return a structured classification:

```java
public final class FailureClassification {
    private final FailureKind kind;
    private final boolean retryable;
    private final String backendCode;
    private final String message;
    private final Throwable cause;
}
```

The adapter classifies the failure, but the common Tool executor decides whether retry is allowed based on:

- configured retry categories;
- attempt count;
- remaining deadline;
- retry safety;
- cancellation state.

---

## 21. Test Strategy

Testing shall include:

- unit tests;
- schema validation tests;
- policy resolution tests;
- adapter contract tests;
- Flow validation tests;
- Flow runtime tests;
- integration tests;
- backward-compatibility tests;
- report and evidence tests;
- performance tests.

---

## 22. Timeout and Retry Test Cases

### TR-001: Process Tool succeeds on first attempt

**Given**

```yaml
totalTimeoutMs: 10000
retry:
  maxAttempts: 3
```

**When**

The process exits successfully.

**Then**

- one attempt is recorded;
- result is `PASS`;
- no retry delay occurs;
- effective policy is included in evidence.

---

### TR-002: Process Tool retries configured exit code

**Given**

```yaml
retry:
  maxAttempts: 3
  retryOn:
    - EXIT_CODE
  exitCodes:
    - 75
```

**When**

- attempt 1 exits with code 75;
- attempt 2 succeeds.

**Then**

- two attempts are recorded;
- failure category for attempt 1 is `EXIT_CODE`;
- final result is `PASS`.

---

### TR-003: Process Tool does not retry unconfigured exit code

**When**

The process exits with code 2.

**Then**

- no retry occurs;
- final result is `ERROR`;
- evidence records the unmatched exit code.

---

### TR-004: Process Tool attempt timeout

**Given**

```yaml
totalTimeoutMs: 30000
attemptTimeoutMs: 5000
```

**When**

The process exceeds five seconds.

**Then**

- ATT requests process termination;
- attempt result is `TIMEOUT`;
- retry occurs only when `TIMEOUT` is configured;
- termination evidence is recorded.

---

### TR-005: Total timeout prevents further retry

**Given**

```yaml
totalTimeoutMs: 12000
attemptTimeoutMs: 10000

retry:
  maxAttempts: 3
  delayMs: 3000
```

**When**

The first attempt consumes ten seconds.

**Then**

- ATT does not wait three seconds and start a second full attempt;
- final result is `TIMEOUT`;
- evidence explains that the total deadline was exhausted.

---

### TR-006: Database Tool uses minimum timeout

**Given**

```text
Tool attempt timeout: 10 seconds
DB helper timeout:    60 seconds
```

**Then**

The JDBC timeout is configured as ten seconds.

---

### TR-007: Database helper limit is lower

**Given**

```text
Tool attempt timeout: 90 seconds
DB helper timeout:    60 seconds
```

**Then**

The JDBC timeout is configured as sixty seconds.

---

### TR-008: Transient database failure retries

**Given**

```yaml
retry:
  maxAttempts: 3
  retryOn:
    - DB_ERROR
  sqlStates:
    - "40001"
```

**When**

- attempt 1 returns SQLState `40001`;
- attempt 2 succeeds.

**Then**

- the Tool is retried;
- final result is `PASS`;
- both attempts are recorded.

---

### TR-009: Permanent database failure does not retry

**When**

A syntax error or invalid table error occurs.

**Then**

- the failure is classified as permanent;
- no retry occurs;
- final result is `ERROR`.

---

### TR-010: Unsafe Tool retry rejected

**Given**

```yaml
retry:
  maxAttempts: 2

semantics:
  retrySafety: unsafe
```

**Then**

Validation fails before execution.

---

### TR-011: Required idempotency key missing

**Given**

```yaml
semantics:
  retrySafety: requiresKey
  idempotencyKeyArgument: requestId
```

**When**

`requestId` is not bound.

**Then**

Validation fails.

---

### TR-012: Required idempotency key supplied

**When**

`requestId` is supplied.

**Then**

Multiple attempts are allowed.

---

### TR-013: Call-backed Tool accepts Action timeout

**Given**

A Tool implemented through `call`.

**When**

An Action specifies `totalTimeoutMs`.

**Then**

Validation succeeds and the timeout is enforced.

---

### TR-014: Expression Tool call uses Tool defaults

**Given**

A Tool is invoked inside an assertion expression.

**Then**

- Tool-level execution defaults apply;
- the enclosing Action deadline remains the outer limit;
- the expression does not inherit a whole-Action retry loop automatically.

---

### TR-015: Cancellation evidence

**When**

ATT requests cancellation but the backend cannot confirm cancellation.

**Then**

Evidence records:

```yaml
requested: true
confirmed: false
```

The result remains `TIMEOUT` or `ERROR` as appropriate.

---

## 23. Flow Validation Test Cases

### FL-V-001: Valid Flow contract

**Given**

A Flow has valid inputs, Actions, and outputs.

**Then**

Validation succeeds.

---

### FL-V-002: Missing required input binding

**Given**

A Flow requires `requestFile`.

**When**

The caller does not bind it.

**Then**

Validation fails before execution.

---

### FL-V-003: Input type mismatch

**Given**

A Flow expects an integer.

**When**

The caller binds an object.

**Then**

Validation fails.

---

### FL-V-004: Missing output source

**When**

A Flow output references a nonexistent internal Action.

**Then**

Validation fails.

---

### FL-V-005: Duplicate Flow ID

**When**

Two Flow files declare the same canonical ID.

**Then**

Package validation fails.

---

### FL-V-006: Unresolved Flow reference

**When**

A Template references a missing Flow.

**Then**

Validation fails.

---

### FL-V-007: Direct recursion

**Given**

Flow A calls Flow A.

**Then**

Validation fails.

---

### FL-V-008: Indirect cycle

**Given**

```text
A → B → C → A
```

**Then**

Validation fails and reports the complete cycle.

---

### FL-V-009: Maximum nesting depth exceeded

**When**

The resolved Flow tree exceeds the configured depth.

**Then**

Validation fails.

---

### FL-V-010: Dynamic Flow reference rejected

**Given**

```yaml
use: "#{input.flowName}"
```

**Then**

Validation fails.

---

### FL-V-011: Forbidden case-context access

**When**

A Flow directly references `CASE.amount`.

**Then**

Validation fails or produces a strict-mode error.

---

### FL-V-012: Unsupported `if/else`

**When**

A Flow contains an `if` block.

**Then**

Schema validation fails.

---

### FL-V-013: Unsupported loop

**When**

A Flow contains `forEach`.

**Then**

Schema validation fails.

---

### FL-V-014: Unsafe whole-Flow retry

**Given**

A Flow has retry safety `unsafe`.

**When**

The caller configures more than one attempt.

**Then**

Validation fails.

---

## 24. Flow Runtime Test Cases

### FL-R-001: Basic Flow invocation

**When**

A Template invokes a Flow with valid inputs.

**Then**

- Actions execute in declaration order;
- outputs are returned;
- internal scope is isolated;
- hierarchical evidence is generated.

---

### FL-R-002: Flow output consumed by next Flow

**When**

Flow A exports a transaction reference and Flow B consumes it.

**Then**

The value is transferred through the declared output contract.

---

### FL-R-003: Internal Action names do not collide

**Given**

The Template and Flow both contain an Action named `invoke`.

**Then**

No namespace collision occurs.

---

### FL-R-004: `runWhen` skips known Flow Action

**When**

`runWhen` evaluates to false.

**Then**

- the Flow Action result is `SKIPPED`;
- its internal Actions do not run;
- the execution graph remains unchanged.

---

### FL-R-005: Internal failure with `onFailure: stop`

**When**

An internal Action fails.

**Then**

- remaining normal Actions are not executed;
- always-run Actions still execute;
- Flow result is aggregated correctly.

---

### FL-R-006: Internal failure with `onFailure: continue`

**Then**

- later Actions execute;
- the failure remains visible;
- final Flow result remains failed unless downgraded.

---

### FL-R-007: Warning-impact Action

**When**

An evidence-collection Action fails with:

```yaml
failureImpact: warning
```

**Then**

- warning evidence is recorded;
- the Flow may still complete successfully;
- the warning appears in the report.

---

### FL-R-008: Always-run cleanup

**When**

A prior Action stops normal execution.

**Then**

A cleanup Action with `runAlways: true` still executes.

---

### FL-R-009: Flow timeout

**When**

The Flow total deadline expires.

**Then**

- the active child operation is cancelled where possible;
- remaining Actions are not started;
- Flow result is `ERROR` or `TIMEOUT`;
- timeout evidence is recorded.

---

### FL-R-010: Parent deadline is shorter

**Given**

```text
Template remaining deadline: 20 seconds
Flow timeout:                60 seconds
```

**Then**

The Flow receives a maximum effective deadline of twenty seconds.

---

### FL-R-011: Nested Flow reporting

**When**

A Flow invokes another Flow.

**Then**

The report displays both levels without flattening them into unrelated Actions.

---

### FL-R-012: Internal implementation refactoring

**Given**

A Flow keeps the same input and output contract but changes internal Actions.

**Then**

Existing callers continue to work.

---

## 25. Backward-Compatibility Test Cases

### BC-001: Existing process Tool Template

An existing Template using process timeout and exit-code retry runs without configuration changes.

---

### BC-002: Existing call-backed Tool

An existing call-backed Tool without execution policy runs unchanged.

---

### BC-003: Existing database helper timeout

The database helper safety timeout remains effective.

---

### BC-004: Existing Template without Flow

A Template containing only existing Action types remains valid.

---

### BC-005: Existing reports

Existing report consumers continue to read top-level Action results.

New nested Flow data is additive.

---

### BC-006: Legacy schema behavior

Legacy schema versions retain their original timeout interpretation unless explicitly migrated.

---

## 26. Performance Test Cases

### PF-001: Policy overhead

The unified execution-policy layer shall add negligible overhead to successful Tool calls.

Recommended target:

```text
Less than 5 ms framework overhead per invocation,
excluding Tool execution and evidence I/O.
```

---

### PF-002: Large Flow dependency graph

Validate a package containing:

- 500 Templates;
- 200 Flows;
- 2,000 Flow references.

Validation and compilation shall complete within an agreed project target.

A suggested initial target is ten seconds on a standard developer machine.

---

### PF-003: Repeated Flow reuse

A Flow reused by many Templates shall be parsed and validated once per package build where possible.

---

### PF-004: Evidence size

Hierarchical Flow evidence shall not duplicate complete Flow definitions for every nested node unnecessarily.

Digest references may be used where appropriate.

---

## 27. Acceptance Criteria

The design is accepted when all of the following conditions are satisfied.

### 27.1 Unified timeout and retry

- Every primary Tool Action supports a common execution policy.
- Process-backed and call-backed Tools use the same public timeout fields.
- Database calls respect both ATT deadlines and database safety limits.
- Retry behavior is driven by normalized failure categories.
- Unsafe retry configurations fail validation.
- Total timeout includes retries and delays.
- Attempt-level evidence is available.
- Cancellation evidence is available.
- Legacy configurations remain supported.

---

### 27.2 Flow reuse

- A Flow can declare typed inputs and outputs.
- A Template can invoke a Flow using a static canonical ID.
- A Flow can invoke another Flow within the nesting limit.
- Flow scope is isolated.
- Flow outputs are the only stable data exposed to callers.
- Missing inputs and invalid output references fail before execution.
- Dependency cycles are detected before execution.
- Hierarchical reporting is available.
- Flow total timeout propagates to child operations.
- Whole-Flow retry is rejected unless declared safe.
- Existing Templates continue to work without Flow conversion.

---

### 27.3 Complexity boundaries

- Arbitrary `if/else` is unsupported.
- Loops are unsupported.
- Dynamic Flow and Tool references are unsupported.
- Recursion is unsupported.
- Mutable global Flow variables are unsupported.
- Runtime graph construction is unsupported.
- ATT can explain the complete static execution tree before execution.
- `runWhen` only skips statically known Actions.
- Complexity linting warns about oversized or highly conditional Flows.

---

### 27.4 Documentation and tooling

- The reference manual documents policy precedence.
- The reference manual distinguishes invocation timeout from backend safety timeout.
- The reference manual documents retry safety.
- The Flow schema is documented.
- At least three complete Flow examples are included.
- `att flow validate` is available.
- `att explain` displays resolved Flow structure and execution policies.
- Migration warnings are documented.
- Error messages include actionable paths and identifiers.

---

## 28. Recommended Minimum Viable Scope

The first release should remain deliberately limited.

### Included

- unified Tool timeout;
- unified Tool retry;
- normalized failure categories;
- process adapter support;
- database adapter support;
- retry-safety validation;
- Flow schema;
- typed Flow inputs and outputs;
- Flow Action;
- isolated Flow scope;
- nested static Flows;
- cycle detection;
- maximum nesting depth;
- `runWhen`;
- `runAlways`;
- Flow timeout;
- hierarchical evidence;
- `att explain`.

### Deferred

- advanced jitter algorithms;
- adapter plugin-defined failure categories;
- whole-Flow retry beyond safe/idempotent Flows;
- parallel Flow branches;
- Action hooks;
- Flow slots;
- Flow inheritance;
- dynamic dispatch;
- loops;
- general exception handling;
- remote Flow registries;
- semantic version ranges.

---

## 29. Risks and Mitigations

### Risk 1: ATT becomes a programming language

**Mitigation**

- prohibit arbitrary branching;
- prohibit loops;
- prohibit dynamic graph construction;
- keep expressions side-effect free;
- enforce static Flow references;
- publish explicit non-goals.

---

### Risk 2: Excessive Flow fragmentation

Users may create a Flow for every trivial Action.

**Mitigation**

Recommend extracting a Flow when one of the following is true:

- the sequence is reused at least three times;
- the sequence contains at least three related Actions;
- the sequence represents a stable business capability;
- the sequence normalizes inputs or outputs;
- the sequence encapsulates a standard verification contract.

---

### Risk 3: Hidden retry side effects

**Mitigation**

- require retry-safety declarations;
- default to one attempt;
- require idempotency keys where applicable;
- make retry attempts visible in evidence;
- avoid global retry defaults.

---

### Risk 4: Timeout semantic migration errors

**Mitigation**

- introduce explicit `totalTimeoutMs` and `attemptTimeoutMs`;
- preserve legacy schema behavior;
- provide migration warnings;
- record effective timeout sources in evidence.

---

### Risk 5: Deep nesting reduces readability

**Mitigation**

- default maximum depth of three;
- hard maximum of five;
- provide `att explain`;
- provide complexity warnings;
- show hierarchical reports.

---

### Risk 6: Flow internal changes break callers

**Mitigation**

- expose only declared outputs;
- prohibit callers from depending on internal Actions;
- use canonical versioned Flow IDs;
- record dependency digests;
- apply compatibility rules to Flow contracts.

---

## 30. Final Recommendation

ATT should introduce both capabilities as part of one architectural direction:

```text
Unified execution policy
+
typed static Flow composition
```

The unified execution policy removes backend-specific timeout and retry behavior from Template authoring.

The Flow abstraction removes duplicated Action sequences without introducing Template inheritance or general workflow programming.

The final responsibility model should be:

```text
Excel
    supplies business testcase data

Template
    defines a complete business scenario

Flow
    provides reusable linear implementation sequences

Action
    represents one operation

Tool
    provides a reusable capability

Adapter
    implements backend-specific execution and cancellation
```

The design boundary should be stated clearly:

> Tool implementations handle complex programming logic.  
> Flows handle reusable linear composition.  
> Templates describe business scenarios.  
> Excel provides test data.

The key governance rule is:

> ATT allows static composition and conditional skipping, but does not allow dynamic workflow programming.

This balance addresses both current architectural problems while preserving ATT's core qualities:

- offline execution;
- deterministic behavior;
- early validation;
- readable configuration;
- reproducible evidence;
- maintainable SIT/UAT test assets.