# Expressions and built-ins

## Unified expression engine

ATT uses one expression engine for runtime Templates, Flows, Actions and Tool calls:

- ${path} reads a Context value and interpolates it into surrounding text.
- #{expression} evaluates a typed expression. It supports Context operands, built-in calls, list literals, parentheses, unary operators, arithmetic, comparisons, like, in, null checks and boolean logic.

A complete expression preserves its value type. For example, an exact #{...} may return a number, boolean, map, list or String. Embedding an expression in surrounding text also produces a String. Use canonical EXEC and META paths; optional lookup uses a trailing question mark.

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

Use the expression form supported by each field. File-content content, Action descriptions/assertions, Log message/value, assign expressions and Tool calls use the ordinary runtime model. A Log value can recursively contain typed expressions; see [Actions and Typed Values](actions.md).

## File-content expressions

`&{path}` is a file-content expression. It reads exactly one statically addressed regular UTF-8 package resource and returns its String content. Bare names resolve from the package root; explicit `./` and `../` locators resolve from the referring descriptor directory. Resolution rejects absolute names, package escapes and symlink targets outside the package. It never infers or parses a document format, expands a glob or creates an output file. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace, glob syntax and dynamic locators fail validation. File content is not implicitly parsed as JSON, YAML or XML.

## Argument result typing

An argument expression is evaluated recursively before its receiving call runs. The call's argument contract applies to the final resolved value only: an inner `&{...}` passed to `str.substr` first produces file text, `str.substr` returns its String result, and only then does the outer target argument apply its type and range rules. Thus a numeric helper argument may accept `waitMs=#{str.substr(&{params/wait.txt}, 0, 4)}` when that result is an integral value, while a String payload keeps every original character. Scalar String coercion trims only for numeric or Boolean parsing; it does not globally trim String/payload values. Map/List values remain typed and are never inferred from file extensions or parsed file content.

Package validation resolves literal values, static file content and explicitly pure built-ins when their inputs are static, then applies the same target argument contract used at runtime. It checks the structure and declared types of runtime-dependent expressions and defers only their final value/range checks. Tool, resource and external calls are never run to discover values during validation.

Use a YAML string when authoring a standalone value or embedding the locator in a larger expression:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

`${...}` and `#{...}` inside the file are compiled and evaluated when the file value is used. Run and Debug cache the compiled plan and invalidate it when the file fingerprint changes; Load freezes the validated file identity, content and compiled plan for the scenario. File output is not reparsed as a new expression source.

## Testdata input mapping syntax

Testdata references are resolved while Case/Stage, Debug, or Load input maps are prepared; they are not part of the general `${...}` / `#{...}` expression engine. Use `@{id}` to preserve a selected record's native type, `@{id.object.path}` or `@{id.items[0]}` to select a value, and scalar interpolation to compose text. One logical ID selects one record per mapping/lifetime, so every reference to that ID in the same mapping sees the same record. Mapping interpolation rejects nulls, maps and lists. `${...}` can read initialized Context values except `EXEC.INPUT`; input construction cannot depend on itself. Direct testdata markers are rejected in reusable Template, Flow, and Tool definitions so those components consume resolved `EXEC.INPUT` values only.

See [Testdata Registry and Input Mapping](test-authoring.md) for descriptor and generation syntax.

## Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

## Built-in functions

Only authored file-expression nodes are executable. Context values, Tool results and file output remain literal Strings even when they contain `&{...}`. Embedded Context paths and calls follow the enclosing Action's normal ordering, scope and resource validation rules. Nested `&{...}` inside file content, including inside `#{...}` arguments, is rejected in v1 in Run, Debug, validation and Load snapshot discovery.

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, `misc.*`, and `seq.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `seq.next` | Return a run-scoped `Long`; optional sequence name and width produce independent named counters or exact-width zero-padded text | `#{seq.next('payment', 10)}` |
| `str.upper` | Convert text to upper case | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.lower` | Convert text to lower case | `#{str.lower(value=${EXEC.INPUT.channel})}` |
| `str.trim` | Remove surrounding whitespace | `#{str.trim(value=${EXEC.INPUT.reference})}` |
| `str.ltrim` / `str.rtrim` | Remove leading/trailing whitespace | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | Return text length | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | Concatenate arguments in call order | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr` | Extract text from a zero-based start | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.indexOf` | Return zero-based position or `-1` | `#{str.indexOf(${EXEC.INPUT.reference}, '-')}` |
| `str.contains` | Test literal substring membership | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.startsWith` / `str.endsWith` | Test a literal prefix/suffix | `#{str.startsWith(${EXEC.INPUT.reference}, 'PAY')}` |
| `str.replace` | Replace every literal target | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad` / `str.rpad` | Pad to a minimum length | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | Repeat a value 0–10000 times | `#{str.repeat(3, '9')}` |
| `date.sysdate` | Return system-zone date, optionally formatted | `#{date.sysdate('yyyyMMdd')}` |
| `date.systimestamp` | Return system-zone timestamp, optionally formatted | `#{date.systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `date.format` | Format an ISO-8601 value | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | Add a calendar/time amount | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `misc.string` | Convert a value to text | `#{misc.string(value=${EXEC.INPUT.amount})}` |
| `misc.number` | Parse and normalize a number | `#{misc.number(value='12.50')}` |
| `misc.boolean` | Convert true/false, yes/no, or 1/0 | `#{misc.boolean(yes)}` |
| `misc.coalesce` | Return first non-blank value | `#{misc.coalesce(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.nvl` | Return a default for null/empty text | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | Select one of two values from a boolean | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | Return one of 1–1000 input values | `#{misc.randomChoice('A', 'B', 'C')}` |

### `Seq.next` Run-scoped sequences

`seq.next` supports these four positional overloads (the same arguments may be supplied by the names `name` and `width`; do not mix named and positional styles):

| Call | Counter | Return value |
|---|---|---|
| `#{seq.next()}` | Default sequence | Incrementing Java `Long` |
| `#{seq.next('payment')}` | Independent sequence named `payment` | Incrementing Java `Long` |
| `#{seq.next(10)}` | Default sequence | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |
| `#{seq.next('payment', 10)}` | Sequence named `payment` | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |

The default and each named sequence have independent counters, each starting at 1. State is owned by one ATT Run: Run shares counters across its Testcases and suites; Debug has a fresh service for its one-shot execution; Load shares counters across its workloads and concurrent iterations. Each per-name counter is thread-safe and issues unique, monotonically increasing values within that Run; concurrent scheduling does not guarantee which VU receives which value. A new Run, Debug execution, or Load run starts the counters again at 1. No `EXEC.SEQUENCES` Context node or reset/current API is exposed.

| Mode | Example use | Scope note |
|---|---|---|
| Testcase | In an `assign` Action: `expression: "#{seq.next('payment', 10)}"` | Consecutive Cases in one Run share the `payment` counter. |
| Debug | In an `assign` Action: `expression: "#{seq.next()}"` | A fresh one-shot Debug execution starts at 1. |
| Load | In an `assign` Action in the target Template/Flow: `expression: "#{seq.next('load-order', 10)}"` | Iterations share the counter; concurrent calls are unique, but no stable VU allocation order is promised. |

`width` must be an integer from 1 through 1000. The name must be non-blank text; with one positional argument, a number means `width` and a string means sequence name. More than two arguments, mixed named/positional argument styles, invalid argument types, blank names, fractional/zero/negative/out-of-range widths are errors. Diagnostics identify `seq.next` and the invalid arity, argument, or range. If a padded value needs more digits than `width`, or the underlying `Long` counter overflows, evaluation fails explicitly; ATT never truncates a sequence or silently exceeds the requested width.

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.



`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.







Typical expressions:

```yaml
assert: "${EXEC.INPUT.amount} > 0"
assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
assert: "${EXEC.ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${EXEC.INPUT.channel} == 'MOBILE') and (${EXEC.INPUT.amount} <= 1000)"
```

## Resource helper methods

Configured `db.*`, `mq.*`, `ssh.*`, and `http.*` calls use the same `#{...}` syntax, but they are resource operations rather than pure built-ins: they access external systems and return typed results. MQ, SSH, and HTTP calls must be the primary `call` of a `type: tool` Action. DB `query` and `scalar` calls are also available to supported expression fields; `db.update` must be the primary call of a `type: tool` Action. They are not permitted in `retry.when`, which accepts only deterministic pure built-ins. All arguments below are named; omit optional arguments to use the configured helper default. See each linked helper page for its full resource and result contract.

### DBHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `db.<id>.query` / `db.<id>.scalar` | `sql: String`; optional `params: List` **or** `parameters: Map<String, value>` (mutually exclusive; default: no bind values). `sql` may be an inline SQL string or a file-content expression returning a String. | `#{db.orders.query(sql='select status from orders where id = :id', parameters={id: ${EXEC.INPUT.orderId}})}` |
| `db.<id>.update` | Same arguments and types as `query`; primary Tool Action only. | `#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}` |

`query` returns typed rows, `scalar` returns a scalar result, and `update` returns the update result. See [DBHelper](resources/dbhelper.md) for SQL binding, transaction, and result details.

### MQHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `mq.<id>.send` | `payload: String, byte[] or structured Map/List` (required); `queue: String` is required unless a request queue is configured. Optional `requestFormat: text\|json\|yaml\|xml` (required for Map/List only), `instance: String` (selects a configured physical instance). | `#{mq.payment.send(queue='PAYMENT.REQUEST', payload=${EXEC.VARS.requestText})}` |
| `mq.<id>.receive` | `queue: String` is required unless a reply queue is configured. Optional `waitMs: Integer` (default 10,000 ms or helper setting), `correlationId: String`, `responseFormat: text\|json\|yaml\|xml` (default `text` or helper setting), `instance: String`. | `#{mq.payment.receive(queue='PAYMENT.REPLY', waitMs=5000, responseFormat='json')}` |
| `mq.<id>.request` | `payload` as above; effective `requestQueue` and `replyQueue` are required (each may come from the helper defaults). Optional `requestFormat` as above, `waitMs: Integer` (default 10,000 ms or helper setting), `responseFormat` (default `text` or helper setting), `instance: String`. | `#{mq.payment.request(requestQueue='PAYMENT.REQUEST', replyQueue='PAYMENT.REPLY', payload=${EXEC.VARS.requestText}, waitMs=5000, responseFormat='xml')}` |

`requestFormat` applies only to structured payloads; a String is sent as-is and must not be paired with it. `waitMs` is 0–3,600,000. See [MQHelper](resources/mqhelper.md) for configured queue defaults and typed reply behavior.

### SSHHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `ssh.<id>.execute` | `command: String`; optional `stdoutFormat: text\|json\|yaml\|xml` (default `text`), `timeoutMs: Integer` (default 60,000 ms, capped by the Action deadline). | `#{ssh.application.execute(command='systemctl is-active example.service', stdoutFormat='text', timeoutMs=5000)}` |
| `ssh.<id>.upload` | `remotePath: String`, `payload: String or byte[]`; optional `overwrite: Boolean` (default `true`), `timeoutMs: Integer` (default 60,000 ms). | `#{ssh.application.upload(remotePath='/srv/app/request.json', payload=${EXEC.VARS.requestText}, overwrite=true)}` |
| `ssh.<id>.stat` / `ssh.<id>.mkdirs` / `ssh.<id>.delete` | `remotePath: String`; optional `timeoutMs: Integer` (default 60,000 ms); `delete` additionally accepts `missingOk: Boolean` (default `false`). | `#{ssh.application.stat(remotePath='/srv/app/result.json')}` |
| `ssh.<id>.move` | `sourcePath: String`, `targetPath: String`; optional `overwrite: Boolean` (default `false`), `timeoutMs: Integer` (default 60,000 ms). | `#{ssh.application.move(sourcePath='/srv/app/out.json', targetPath='/srv/app/archive/out.json', overwrite=false)}` |

SSH operations accept named arguments only; paths are remote paths, and upload takes content rather than a local file path. See [SSHHelper](resources/sshhelper.md) for path restrictions, return values, and timeout/retry behavior.

### HTTPHelper

`http.<id>.get(...)` and `http.<id>.post(...)` select the method by name; `http.<id>.request(...)` additionally requires `method: String` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, or `OPTIONS`). All three accept these optional named arguments:

| Argument | Type and default | Notes |
|---|---|---|
| `path` | `String`, default `''` | Relative to the configured base URL; no absolute URL, query string, or fragment. |
| `query` | `Map<String, value>`, optional | Iterable values become repeated query parameters. |
| `headers` | `Map<String, value>`, optional | Merged with configured headers; values are rendered as strings. |
| `body` | `String`, `byte[]`, or structured Map/List, optional | GET and HEAD do not accept a body. Map/List requires `requestFormat`. |
| `requestFormat` | `text\|json\|yaml\|xml`, optional | Required for Map/List body only; String/byte[] content is passed through. |
| `contentType` | `String`, optional | Overrides the request Content-Type header. |
| `responseFormat` | `auto\|text\|json\|yaml\|xml`, default helper setting (`auto` by default) | `auto` resolves from response Content-Type. |
| `connectTimeoutMs`, `readTimeoutMs`, `connectionRequestTimeoutMs` | Integer 1–3,600,000 ms, optional | Override corresponding helper timeout; descriptor defaults are 5,000 ms, 30,000 ms, and 5,000 ms respectively. |
| `followRedirects` | `Boolean`, default helper setting (`false` by default) | Redirects are followed up to the runtime limit. |

Example: `#{http.payment.post(path='/v1/payments', query={dryRun: true}, headers={Accept: 'application/json'}, body=${EXEC.INPUT.request}, requestFormat='json', responseFormat='json')}`. See [HTTPHelper](resources/httphelper.md) for timeout ranges, body encoding, and response parsing.

## Expression scope and errors

This page defines the language. Each field's owner defines available roots and evaluation timing: [Tool command/call](resources/tools.md), [Load execIdFormat and vars](execution-modes/load.md), [Debug vars](execution-modes/debug.md), and [report filenames](configuration.md). `${path?}` permits an absent allowed map/list path to return null; malformed syntax and illegal scope access still fail. Expression syntax and missing required Context paths produce structured diagnostics; see [Validation](validation-diagnostics.md).

Removed APIs: `dbText`/`misc.dbText`, `prettyPrint`/`misc.prettyPrint`/`format.pretty`, all local `file.*` built-ins and their legacy aliases. Keep DB results typed and migrate display calls to Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` with `format: sqlplus`; use `format: json` or `yaml` for Maps/Lists. Read package content with `&{...}` and pass its String to HTTP body, MQ payload, or SSH upload payload. SSHHelper upload accepts content only; native SSH download was removed. ATT local output remains framework-owned. Removed calls fail with migration guidance.

## Retry-condition lifecycle

`retry.when` runs after the current attempt completes, only after retryOn matches and while another attempt is available. `output.*` binds current result/evidence/diagnostic and `output.attempt`. Normal Boolean typing and strict/optional Context paths apply. Only deterministic pure built-ins are permitted; external, file, sequence, random and current-time operations are rejected. See [Action retry](actions.md).
