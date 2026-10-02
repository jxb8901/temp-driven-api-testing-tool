## 03 Actions and Typed Values

This chapter defines the active ATT action contract. Templates use att-template/v3.6. Each completed action publishes its logical typed value at output.result. Actions do not use a shared result.format/path/overwrite object. See the Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper chapters for resource configuration.

### Action types

| Type | Required fields | Result and behavior |
|---|---|---|
| tool | call | Invokes a configured Tool, built-in or helper call and preserves the native typed result. DB query/scalar/update calls are ordinary Tool calls. |
| assert | assert | Evaluates a boolean condition and records PASS or FAIL. expected and actual are optional diagnostic values. |
| log | message or value | Formats a typed value for the Case log. Its fields are level, message, value and format. |
| assign | name and expression | Publishes the expression's typed result below EXEC.VARS. |
| flow | use | Runs a registered Flow in a nested Action scope and restores the caller's scope on return. |

Actions run in YAML order. Where supported, an action may also define id, description, onFailure and runWhen. Action IDs are unique within their scope. Type-specific invalid fields fail validation. Action result, Log file and Log fields are not part of the current action contract.

### Separate logical values from representations

ATT keeps the logical operation result separate from human or wire representations:

| Boundary | Field/value | Purpose |
|---|---|---|
| Command Tool stdout | stdoutFormat | Parses external stdout into a typed result. |
| HTTP/MQ response | responseFormat | Parses external response bytes into a typed result. |
| Project-file expression | `String` | Reads one safe UTF-8 project file and preserves its exact characters after expression evaluation. |
| Abstract Map/List sent over HTTP/MQ | requestFormat | Serializes the value at the outbound boundary. |
| Log or resource evidence | format / evidence.output.format | Produces a human-readable representation. |

DB results are already typed values. Tool, Action, Template, Flow and expression results remain typed while they move through ATT.

DB query, scalar, and update operations use the first-class DBHelper call forms `db.<helper>.query(...)`, `db.<helper>.scalar(...)`, and `db.<helper>.update(...)` inside a normal `type: tool` Action. A DB call accepts one String `sql` argument plus either positional `params` or named `parameters`; `sql=&{project-relative-file.sql}` supplies package SQL content. The historical `type: db` Action is retained only by archived schema versions.

### Project-file expressions return String

The current replacement for the historical Render Action is the typed project-file value expression `&{path}`. It always returns one `String`; it never infers a document format, parses an extension, expands a glob, or creates an output file:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
~~~

`${...}` remains a Context reference and `#{...}` remains an expression/call. `&{...}` is a static, one-file locator; v1 has no glob or dynamic locator form. The locator is relative to the canonical ATT project root. A descriptor-relative `./` or `../` path is allowed only when its canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace and glob syntax fail validation.

Ordinary UTF-8 files are returned unchanged. If the file contains `${...}` or `#{...}`, ATT compiles those nodes once and evaluates them for each execution; the compiled plan is immutable and dynamic values are not reparsed as a second template. Run and Debug reuse the plan until the file fingerprint changes. Load validates and captures the selected file identity, content and compiled dependency closure before scheduling, so active iterations see a stable snapshot.

Use Assign when the String is reused by later Actions:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

For HTTP or MQ, pass the `String` as the body/payload. The resource encodes the exact text with its configured charset/CCSID. HTTP content type and MQ transport metadata remain resource-owned settings. `&{...}` is valid in Tool/Helper call arguments, Assign expressions, Log values and other typed value positions.

requestFormat is for abstract structured values such as Map or List. Such a body requires an explicit format, for example requestFormat=json. Combining requestFormat with a `String` fails; a project-file result is never silently parsed and serialized. A raw file input remains available only for resource calls that explicitly define a file argument.

### Tool, DB and Flow results

A command-backed Tool declares stdoutFormat in its Tool descriptor:

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat parses external stdout once into output.result; it is not output serialization. Call-backed Tools and DB/HTTP/MQ/SSH operations keep their native return types.

A DB action uses db and exactly one query or update block. SQL, bind parameters, transaction controls and DB evidence follow the DB action and DBHelper contracts.

A Flow action uses use with a canonical Flow ID. It runs in a fresh EXEC.ACTIONS scope and publishes its result/evidence to the caller when it returns. META.FLOW exists only while that invocation is active.

### Tool evidence collectors

A Tool Action may define first-class `evidence` collectors for diagnostics that must be gathered before the Action assertion. The lifecycle is:

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collectors are diagnostic operations, not replacement Actions. Each collector has its own typed result and does not replace or mutate the primary `output.result`:

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.VARS.requestText},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.result.replyReceived} == true
```

While the containing Action, including its assertion, is active, use these paths:

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

After publication, the same values are available below `EXEC.ACTIONS`:

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

The public shape keeps primary resource evidence and collector evidence separate:

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

When a Tool retries, collectors run for every primary attempt before that attempt's assertion. The top-level `output.evidence.collectors.<id>` is the final/winning attempt; `output.attempts[n].evidence.collectors.<id>` retains each attempt, including earlier failures. After publication, the corresponding history path is `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`.

`call` is required. `timeoutMs` is independent of the primary Tool timeout. `onFailure: continue` is the normal application-log pattern so a diagnostic collection failure does not hide the original business or assertion failure; `stop` makes the collector failure an Action error. Collector status and diagnostic remain observable, and collector failure never changes the primary logical result. Distinguish an evidence collector from an ordinary Tool/Log/Assign Action: use a collector for diagnostic data needed before the containing Tool assertion, and an ordinary Action when the collected value is normal business/test data for later assertions.

Collector results follow the normal typed-result rules. Evidence placement does not stringify a map, list or project-file `String`; helper `evidence.output` is an explicit presentation boundary. In Load, explicit collector execution is separate from helper `evidence.output` serialization. Resource-output formatting remains controlled by the Load evidence policy and is not silently substituted for or dropped in place of an author-requested collector.

### Log: typed value to Case log

Log is a presentation action and therefore has its own format field:

~~~yaml
logOrder:
  type: log
  level: INFO
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

level defaults to INFO and accepts TRACE, DEBUG, INFO, WARN or ERROR. At least one of message or value is required. message is rendered as text. value accepts any typed value, including nested maps/lists. Exact ${...} and #{...} expressions preserve their native types; map/list children are evaluated recursively without converting numbers, booleans, nulls or nested values to strings. format accepts text, json, yaml, xml or sqlplus and controls only the emitted Case-log string. When format is present, value is required.

When both message and value are supplied, Log emits the message, a newline, then the formatted value. output.result is that emitted string. A project-file String is emitted as-is when used as a value; Log does not infer or attach a document format. Log does not read a file and has no fields map. Put a typed map/list in value for structured log content.

### Expressions and variable scope

Action expressions use the regular ATT expression engine. A complete ${...} or #{...} expression keeps its result type; embedding an expression in surrounding text produces a String. See [Expressions and Built-ins](07_expressions.md).

assign publishes its typed value once below EXEC.VARS.<name>. The name must match [A-Za-z_][A-Za-z0-9_]* and be unique within the Case. Values assigned in one Stage are available to later Stages. Action-local output is available at output.* while an Action runs and at EXEC.ACTIONS.<id>.output.* after publication. Flow invocation creates a temporary Action namespace; publish values to EXEC.VARS when the caller needs them after the Flow returns.

### Resource output evidence

Resource evidence is separate from the logical result. A helper may configure an optional evidence.output presentation policy:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

This adds a bounded human-readable snapshot beside operation metadata; it does not change output.result or response parsing. In Load, evidence.resources.output accepts inherit (default) or none. none skips resource-output formatting and file materialization. Metrics-only iterations create no execution directory. When iteration evidence is retained, eligible resource output is formatted lazily into that workspace.

### Action output and evidence paths

| Path | Meaning and availability |
|---|---|
| `output.result` | Primary typed Action result while the Action is active, including its assertion. |
| `output.evidence.collectors.<id>.result` | Typed result of an active Tool evidence collector. |
| `output.evidence.collectors.<id>.status` | Collector `PASS`/`ERROR` status while the Action is active. |
| `output.evidence.collectors.<id>.error` | Bounded failure summary with a non-blank `message` when the collector fails. |
| `output.evidence.collectors.<id>.evidence` | Preserved bounded/redacted underlying Tool/resource evidence, including resource identity and native failure fields when supplied. |
| `EXEC.ACTIONS.<actionId>.output.result` | Published primary typed result after the Action completes. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | Published final/winning collector result. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | Published final/winning collector status. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and preserved operation evidence. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | Collector result/status for a specific retry attempt; earlier attempts remain after a later success. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | Failure summary and underlying evidence for that specific collector attempt. |

Strings, numbers, booleans, null, maps and lists remain typed across Action/Template/Flow boundaries.

### Common retry and Boolean conditions

Tool Actions, including retry-capable DB query/scalar calls, share the `retry` contract. `maxAttempts` (2–10), `intervalMs` (0–3600000) and a non-empty `retryOn` list (ASSERTION/TIMEOUT) remain required. `when` is an optional non-empty Boolean expression String. Existing restrictions on mutating DB updates and SSH transfers still apply.

~~~yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [TIMEOUT]
  when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

Each attempt executes its operation, publishes current result/evidence/diagnostic, evaluates its assertion where applicable, and selects a retry category. Only after `retryOn` matches, and while attempts remain, does ATT evaluate `when`. Omitting it preserves ordinary retry behavior. True permits the interval wait and another attempt; false preserves the current TIMEOUT/FAIL and stops. Successful attempts, category mismatches and exhausted attempts do not evaluate the gate.

The condition can inspect `output.status`, `output.result`, `output.evidence`, `output.diagnostic`, the one-based `output.attempt`, and EXEC/META paths valid in the current scope. Top-level output is cleared at the start of each attempt so it cannot expose stale result/evidence. History remains in `output.attempts[n]`. Each `retryDecision` records category, candidate, whenEvaluated, whenResult (if evaluated), allowed and a reason such as WHEN_FALSE or MAX_ATTEMPTS.

Conditions use normal `${...}`/`#{...}` typing and must return Boolean; numbers and strings such as 'false' are not coerced. Use `when: "#{false}"` to stop retry. Strict missing paths and expression failures produce normal diagnostics at retry.when and terminate retry. Pure deterministic built-ins are allowed; Tool/DB/MQ/HTTP/SSH calls, file/project-file operations, sequences, randomness and current-time operations are forbidden. Deterministic syntax/type errors fail validation; runtime result types and unavailable paths are checked when the gate runs.

TIMEOUT is the canonical Action outcome; suite/report aggregate operational failure remains ERROR. Authors must decide whether side-effecting operations such as MQ request or HTTP POST are safe to replay. Unconditional TIMEOUT retry can duplicate a business transaction; ATT does not silently suppress MQ retry. See the [MQHelper example](05_resources/mqhelper.md).
