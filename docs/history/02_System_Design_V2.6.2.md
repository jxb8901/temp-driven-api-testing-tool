# ATT V2.6.2 Timeout and Retry System Design

Status: Implemented  
Product version: 2.6.2  
Template schema: `att-template/v2.6`  
Sidecar schema: `att-sidecar/v2.2`

## 1. Purpose

ATT has command-backed Tools, call-backed DB/built-in Tools, and primary built-in Tool Actions. Before V2.6.2 their timeout and retry behavior differed: command Tools accepted Action timeout and EXIT_CODE retry, while call-backed Tools rejected both.

V2.6.2 defines one author-facing Tool Action contract:

- timeout prevents one Tool attempt from waiting indefinitely;
- timeout does not imply retry;
- retry belongs only to the Tool Action that performs the call;
- a returned result is asserted after every attempt;
- assertion failure and timeout are independent, explicit retry reasons.

The design intentionally has no total, Flow, stage, Template, or workbook deadline; no global or Tool retry policy; no EXIT_CODE matcher; and no retry-safety metadata.

## 2. Configuration

### 2.1 Timeout

Global fallback:

```yaml
schemaVersion: att-config/v2.6
timeoutMs: 10000
```

Optional Tool default:

```yaml
tools:
  queryStatus:
    name: Query status
    description: Query asynchronous API status
    timeoutMs: 5000
    command: [./query-status.sh, "${requestId}"]
    output: yaml
    arguments:
      requestId:
        name: Request ID
        description: Correlation ID
        required: true
```

Optional Action override:

```yaml
waitForStatus:
  type: tool
  call: "#{queryStatus(requestId=${CASE.requestId})}"
  timeoutMs: 3000
```

Resolution is:

```text
Action timeoutMs
→ Tool descriptor timeoutMs
→ global timeoutMs
→ 10000 ms
```

Every value is an integer from 1 through 3,600,000 milliseconds and applies independently to one attempt. Workbook sidecars, stages, and Templates cannot define timeout defaults.

For call-backed DB Tools, the JDBC query timeout is the shorter of the resolved Tool timeout and the dbhelper `statement.timeoutSeconds`.

### 2.2 Retry

Retry is valid only on a `type: tool` Action:

```yaml
waitForStatus:
  type: tool
  call: "#{queryStatus(requestId=${CASE.requestId})}"
  assert: "${output.result.status} == 'COMPLETED'"
  expected: COMPLETED
  actual: "${output.result.status}"
  retry:
    maxAttempts: 10
    intervalMs: 2000
    retryOn: [ASSERTION, TIMEOUT]
```

Fields:

| Field | Contract |
|---|---|
| `maxAttempts` | Required integer 2–10, including the first attempt |
| `intervalMs` | Required integer 0–3,600,000, waited only between attempts |
| `retryOn` | Required non-empty unique list containing `ASSERTION`, `TIMEOUT`, or both |

`ASSERTION` requires a non-empty Action `assert`. There is no retry default outside the Action.

`EXIT_CODE` and `exitCodes` are removed. A command exit code remains available at `${output.exitCode}` and may be part of the Action assertion.

## 3. Execution State Machine

For each Tool Action:

1. Resolve the Tool and effective timeout.
2. Resolve Action input and `saveAs` once according to the normal Action contract.
3. Invoke one attempt.
4. On timeout:
   - record the attempt as TIMEOUT;
   - retry only when `TIMEOUT` is selected and attempts remain;
   - otherwise finish the Action as ERROR.
5. On a normal result:
   - publish the current result at `${output...}`;
   - evaluate the Action assertion immediately;
   - finish PASS when it is true or absent;
   - retry only when it is false, `ASSERTION` is selected, and attempts remain;
   - otherwise finish FAIL.
6. Configuration, argument, I/O, output parsing, non-timeout DB, and assertion-evaluation errors finish ERROR without retry.

A non-zero command exit code is not by itself an operational error. The Action assertion decides whether that result passes, fails, or is retried.

## 4. Backend Enforcement

- Local and SSH command Tools use the existing bounded process runner and process-tree/channel termination.
- Call-backed DB Tools apply the resolved timeout to JDBC while retaining dbhelper transaction and connection ownership.
- Primary and call-backed built-ins run in a bounded daemon executor; timeout cancels the Future and returns control to the Case runner.
- A retry-enabled call-backed Tool bypasses case/db cache on every attempt, so polling observes fresh state.
- Direct `type: db` Actions and Tool/DB calls embedded outside a primary Tool Action do not gain an Action retry loop.

## 5. Evidence and Artifacts

The Action output contains:

- ordered `attempts`;
- each attempt number, duration, effective timeout, result/evidence, assertion result, and retry reason;
- `winningAttempt` for PASS or `finalAttempt` for exhausted FAIL;
- the final attempt result at the normal Action output paths.

When `saveAs` is configured, attempts use the same resolved Action-owned path. Later attempts may replace only the artifact produced by an earlier attempt of that Action. The final file therefore represents the last attempt. No `attempt-001` directories are created.

Tool Actions with `assert` may also declare `expected` and `actual`; they contribute the same report fields as a standalone assert Action.

## 6. Migration

- New polling templates use `att-template/v2.6`.
- New workbook sidecars use `att-sidecar/v2.2` and cannot contain `timeoutMs`.
- Existing `retryOn: [EXIT_CODE]` and `exitCodes` must be replaced by an Action assertion plus `retryOn: [ASSERTION]`; add `TIMEOUT` only when timeout retry is intended.
- A previous Tool Action followed by a standalone business assert must move that assertion into the Tool Action when the result is intended to drive polling.
- `att-template/v2.5`, `att-template/v2.3`, and `att-sidecar/v2.1` remain readable only when they do not use removed timeout/retry fields.

## 7. Acceptance Criteria

The release is complete when:

- command-backed and call-backed Tool Actions accept the same public timeout/retry fields;
- timeout precedence is Action → Tool → global → framework fallback;
- assertion false and timeout retry only when explicitly selected;
- EXIT_CODE retry and sidecar timeout fail validation;
- cached call-backed polling performs fresh calls;
- attempt evidence and final report values remain deterministic;
- package validation, Maven tests, build, built-package validation, and diff checks pass.
