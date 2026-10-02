## 05 Expressions and Built-ins

### Unified expression engine

ATT uses one expression engine for runtime Templates, Flows, Actions and Tool calls:

- ${path} reads a Context value and interpolates it into surrounding text.
- #{expression} evaluates a typed expression. It supports Context operands, built-in calls, list literals, parentheses, unary operators, arithmetic, comparisons, like, in, null checks and boolean logic.

A complete expression preserves its value type. For example, an exact #{...} may return a number, boolean, map, list or String. Embedding an expression in surrounding text also produces a String. Use canonical EXEC and META paths; optional lookup uses a trailing question mark.

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

Use the expression form supported by each field. Project-file content, Action descriptions/assertions, Log message/value, assign expressions and Tool calls use the ordinary runtime model. A Log value can recursively contain typed expressions; see [Actions and Typed Values](14_actions.md).

### Project-file String expressions

`&{path}` is a typed project-file expression. It resolves exactly one regular UTF-8 file and always returns a `String`; it never infers a document format, parses an extension, expands a glob or creates an output file. The path is relative to the canonical ATT project root. Descriptor-relative `./` and `../` paths are allowed only when their canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace, glob syntax and dynamic locators fail validation.

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

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Only authored file-expression nodes are executable. Context values, Tool results and file output remain literal Strings even when they contain `&{...}`. Embedded Context paths and calls follow the enclosing Action's normal ordering, scope and resource validation rules. Nested `&{...}` inside project-file content, including inside `#{...}` arguments, is rejected in v1 in Run, Debug, validation and Load snapshot discovery.

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

#### `seq.next` run-scoped sequences

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

### Expression scope and errors

This chapter defines the language. Each field's owner defines available roots and evaluation timing: [Tool command/call](05_resources/tools.md), [Load execIdFormat and vars](04_execution_modes/load.md), [Debug vars](04_execution_modes/debug.md), and [report filenames](09_configuration.md). `${path?}` permits an absent allowed map/list path to return null; malformed syntax and illegal scope access still fail. Expression syntax and missing required Context paths produce structured diagnostics; see [Validation](12_validation_diagnostics.md).

Removed APIs: `dbText`/`misc.dbText`, `prettyPrint`/`misc.prettyPrint`/`format.pretty`, all local `file.*` built-ins and their legacy aliases. Keep DB results typed and migrate display calls to Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` with `format: sqlplus`; use `format: json` or `yaml` for Maps/Lists. Read project content with `&{...}` and inspect or change remote files with SSHHelper `stat`, `mkdirs`, `move`, and `delete`; use `upload`/`download` for transfers. ATT local output remains framework-owned. Removed calls fail with migration guidance.

### Retry-condition lifecycle

`retry.when` runs after the current attempt completes, only after retryOn matches and while another attempt is available. `output.*` binds current result/evidence/diagnostic and `output.attempt`. Normal Boolean typing and strict/optional Context paths apply. Only deterministic pure built-ins are permitted; external, file, sequence, random and current-time operations are rejected. See [Action retry](14_actions.md).
