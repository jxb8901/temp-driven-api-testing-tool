# ATT V2.6 Tool, Built-in Package, and Reporting System Design

Status: final design; implemented for V2.6.1
Target release: 2.6.1
Depends on: [V2.5 Database Helper System Design](02_System_Design_V2.5.md)

## 1. Decision

V2.6 adds `call` as a second Tool implementation form. A Tool descriptor has exactly one of:

- `command`: launch an external process, preserving the existing Tool contract; or
- `call`: invoke one ATT DB expression or pure built-in and return its typed Java value.

This gives users a small, business-named façade such as `#{orders.find(...)}` without moving dbhelper configuration back into `config.yaml`, hiding JDBC behind argv/stdout, or removing direct `type: db` and `#{db.<instance>.*}` access.

Call-backed Tools are optional reuse units. Direct DB Actions and read-only DB expressions remain first-class V2.5 contracts.

## 2. Goals and non-goals

Goals:

- keep ordinary Tool call syntax unchanged at Action and expression call sites;
- organize canonical built-ins and grouped Tools in one package-like dotted namespace;
- reuse existing Tool names, descriptions, arguments, documentation, and grouping;
- preserve typed query/scalar/update results without stdout parsing;
- allow reusable SQL, parameter binding, SQL files, pure built-ins, and cache policy;
- keep SQL timeout, connection, transaction, limits, read-only, and evidence policy owned by the dbhelper instance;
- maintain strict validation and V2.2/V2.5 configuration compatibility.
- pass multi-value process arguments as typed YAML arrays instead of descriptor-owned string delimiters;
- persist each rendered Action description as an independent report field.

V2.6 does not:

- make dbhelper a process Tool implementation;
- permit one configured Tool to call another configured Tool;
- add named SQL parameters, batch SQL, generated keys, stored procedures, or JDBC driver bundling;
- apply process argv, SSH, stdout parser, exit-code retry, or Action timeout settings to a call-backed Tool;
- automatically invalidate cache after DB update, commit, rollback, or reconnect.

## 3. Version ownership

| Artifact | Current schema | Compatibility |
|---|---|---|
| Product | `2.6.1` | V2.5 behavior remains readable |
| Main configuration | `att-config/v2.6` | reads `v2.5`, `v2.2`, and `v2.1` |
| Tool group | `att-tool-group/v2.6` | reads unchanged `v2.2` groups |
| Dbhelper | `att-dbhelper/v2.5` | unchanged |
| Template | `att-template/v2.5` | unchanged; the Action call syntax did not change |
| Schema catalog | `att-schema-catalog/v2.6` | includes current and legacy schemas |

`call` and `cache` are accepted only in V2.6 Tool descriptors. An older schema never silently changes meaning.

## 4. Configuration contract

### 4.1 Tool group

A Tool group is also its call package: group `orders` publishes `orders.find`, `orders.count`, and `orders.updateStatus`. The existing file structure and `id` field therefore remain compatible while the user-facing model is package-like rather than a flat command registry.

```yaml
schemaVersion: att-tool-group/v2.6
id: orders
name: Order database tools
description: Reusable order operations

tools:
  find:
    name: Find orders
    description: Find a customer's orders in one status
    call: "#{db.orders.query(sql='select order_id, status from orders where customer_id = ? and status = ?', params=[${input.customerId}, ${input.status}])}"
    cache:
      scope: case
    arguments:
      customerId:
        name: Customer ID
        description: Customer whose orders are returned
        required: true
      status:
        name: Status
        description: Exact order status
        required: true

  count:
    name: Count orders
    description: Return one scalar count
    call: "#{db.orders.scalar(sql='select count(*) from orders where customer_id = ?', params=[${input.customerId}])}"
    cache:
      scope: db
    arguments:
      customerId:
        name: Customer ID
        description: Customer whose orders are counted
        required: true

  updateStatus:
    name: Update order status
    description: Update one order
    call: "#{db.orders.update(sql='update orders set status = ? where order_id = ?', params=[${input.status}, ${input.orderId}])}"
    arguments:
      orderId: {name: Order ID, description: Order to update, required: true}
      status: {name: Status, description: New status, required: true}
```

The same descriptor may be placed under global `tools` in `att-config/v2.6`. A group without `script` or `ssh` may mix command-backed and call-backed Tools. A call-backed Tool cannot inherit group `script` or `ssh`.

### 4.2 Exclusive implementation fields

| Field | command-backed | call-backed |
|---|---:|---:|
| `name`, `description`, `arguments` | yes | yes |
| `command` | required | forbidden |
| `call` | forbidden | required |
| `output` | optional | forbidden |
| argument `argName`, `argNameMode` | supported | forbidden |
| group/global SSH and group `script` | supported | forbidden |
| `cache` | forbidden | optional |

`call` must be one exact `#{...}` expression. Its primary target is limited to:

- `db.<instance>.query(...)`;
- `db.<instance>.scalar(...)`;
- `db.<instance>.update(...)`; or
- one ATT pure built-in.

Nested calls may be pure built-ins only. This restriction prevents Tool dependency cycles and leaves a clean future boundary for a Java Tool SPI.

### 4.3 Multi-value command arguments

`att-config/v2.6` and `att-tool-group/v2.6` do not define `delimit`. A caller passes an ordered typed list directly:

```yaml
call: "#{fpp.loghelper(logFiles=['/var/log/app.log', '/archive/app*.log'], keywords=['PAYMENT', ${CASE.caseId}], outputPrefix=${CASE.outputDirectory})}"
```

An exact command placeholder such as `${keywords}` expands the List into separate argv values. `argNameMode: once` emits one `argName` before the list; `repeat` emits it before every value. A List placeholder must occupy one complete command token, an empty required List is an error, and nested Lists/maps are not valid argv items. Scalar values remain one argv value. Legacy `att-config/v2.1|v2.2|v2.5` and `att-tool-group/v2.2` files keep their historical `delimit` read compatibility, but migrating a descriptor to V2.6 requires removing `delimit` and changing call sites to arrays.

### 4.4 Definition scope

The definition reads declared arguments through typed `input.*` paths:

```yaml
call: "#{db.orders.query(sql='select * from orders where customer_id = ? and status = ?', params=[${input.customerId}, #{upper(${input.status})}])}"
```

Tool definitions must use `${input.customerId}` or the explicit alias `${TOOL.input.customerId}`. Bare `input.customerId` and `TOOL.input.customerId` are rejected. `CASE`, `RUN`, `ACTIONS`, and invocation evidence roots are forbidden in the Tool definition. Case-specific values are supplied at the outer call site. This keeps the façade reusable and independently documentable.

## 5. SQL and result behavior

The DB target accepts named `sql|sqlFile` and optional `params`, with exactly one SQL source. `params` is an ordered inline list or one typed List input. Values are bound with `PreparedStatement.setObject`.

Inline SQL is rendered at invocation time in the Tool definition scope. `sqlFile` is a static package-relative path: the path itself cannot come from an input or expression. ATT validates and hashes the referenced file as a run input, then loads and renders its contents in the same definition scope. Only pure built-ins and declared input values are available while rendering SQL.

Results retain the V2.5 shapes:

- `query` returns the DB result object containing `success`, `operation`, `rows`, `rowCount`, and transaction metadata;
- `scalar` requires exactly one row and one column and returns that typed cell;
- `update` returns the DB result object containing `affectedRows` and transaction metadata.

No intermediate string serialization occurs.

## 6. Invocation rules

### 6.1 READ façades

Query, scalar, and pure-built-in façades may be used anywhere an ordinary Tool call is permitted, including expressions:

```yaml
- id: loadOrders
  type: assign
  name: orders
  expression: "#{orders.find(customerId=${CASE.customerId}, status='OPEN')}"

- id: checkCount
  type: assert
  assert: "#{orders.count(customerId=${CASE.customerId})} >= 1"
```

### 6.2 WRITE façades

A DB update façade is permitted only as the primary call of `type: tool`:

```yaml
- id: closeOrder
  type: tool
  call: "#{orders.updateStatus(orderId=${CASE.orderId}, status='CLOSED')}"
```

Using the same WRITE façade inside `assign`, `assert`, render content, SQL, another call argument, or interpolated text is a validation and runtime error. DB failures produce Case status `ERROR`, never assertion `FAIL`.

## 7. Cache

Cache is explicit and optional:

```yaml
cache:
  scope: case   # or db
```

Only successful call-backed results are stored. DB update façades cannot be cached. `db` scope additionally requires a DB query or scalar target; a pure built-in may use `case` only.

The cache key is SHA-256 over the qualified Tool name and a deterministic, type-sensitive representation of its resolved input. Argument order therefore does not change the key, while values with different Java types remain different entries.

| Scope | Ownership | Lifetime |
|---|---|---|
| `case` | `CaseRuntimeContext` | current Case only |
| `db` | dbhelper instance + execution thread | executor thread scope, normally the worker/suite lifetime |

Cache is deliberately independent of JDBC state. DB update, commit, rollback, transaction finalization, connection close, and automatic reconnect do not invalidate either cache scope. A cache hit does not open or check a JDBC connection.

This is a strong opt-in stale-read contract. `db` scope should be used only for stable/reference data or where the package owner accepts stale values. ATT does not provide TTL or automatic coherence in V2.6. Cache is in-memory, bounded only by the number and size of distinct calls made during its scope, and is not persisted to run artifacts.

Invocation evidence records:

```yaml
cache:
  scope: db
  key: <sha256>
  hit: true
```

A miss records the nested DB evidence. A hit records no new DB invocation because JDBC was not executed.

## 8. Timeout, transaction, and connection lifecycle

A call-backed DB Tool delegates to the same `DbHelperExecutor` as direct DB Actions and expressions. Therefore:

- `statement.timeoutSeconds` on the dbhelper instance is always applied;
- dbhelper read-only, result limits, transaction scope/onEnd, connection reuse, Case pre-rollback, and reconnect behavior are unchanged;
- Action `timeoutMs` and process EXIT_CODE retry are rejected for call-backed Tools;
- the connection remains thread-owned and independent of Case lifetime, as specified by V2.5.

Cache ownership does not alter those rules and JDBC lifecycle does not alter cached entries.

## 9. saveAs and evidence

V2.6 additionally permits `format: text` on a direct `type: db` Action. Query output is a deterministic SQL*Plus-style table in JDBC column-label order with terminal-display-width padding (including wide Unicode), numeric right alignment, explicit `NULL`, escaped control/non-printing characters, and a row-count footer. Empty queries produce `no rows selected.`; updates produce `<n> row(s) updated.`. Only the artifact representation changes: the Action Context retains the original typed DB result. `raw` remains invalid because DB execution has no process stdout.

The same formatter is exposed as the pure `dbText(value)` built-in. A log Action can therefore emit a previous typed DB result without an intermediate file:

```yaml
printOrders:
  type: log
  message: "#{dbText(${ACTIONS.queryOrders.output.result})}"
```

`dbText` has no JDBC, transaction, connection, or cache side effects. A nested read-only DB call remains possible through the normal expression grammar, but reusing a previous Action result avoids an additional query.

A primary call-backed Tool Action uses the common Action object:

```yaml
saveAs:
  path: db/orders.json
  format: json
  overwrite: false
```

`path`, `format`, and `overwrite` behave like other V2.5 Actions. `format` is required for a call-backed Tool and may be `text`, `json`, `yaml`, or `xml`; `raw` is invalid because there is no process stdout. Use structured formats for query/update result objects and `text` when a scalar is intentionally textual.

The outer Action contains a normal `TOOL` node with `implementation: call`, input, typed output, status, duration, and optional cache evidence. A DB miss also appears under the Action `DB` node and the invocation-local root `DB`. Call-backed runtime evidence does not copy the configured `call`, so `evidence.sql: hash` cannot be bypassed by the façade layer. It also has no `command`, `argv`, `stdout`, `stderr`, `rawOutput`, or `exitCode` fields.

## 10. Error and validation contract

Configuration/package validation rejects:

- missing or simultaneous `command`/`call`;
- `call` under a legacy config or Tool-group schema;
- process-only properties on a call-backed Tool;
- undeclared or non-`input.*` definition references;
- configured Tool chaining or invalid DB targets/arguments;
- unknown dbhelper instances or unsafe SQL files;
- cached updates or `db` cache on a non-DB target;
- a WRITE façade outside a primary Tool Action;
- call-backed Action timeout/retry, raw save, or missing save format.

Runtime performs the same safety checks so programmatic construction cannot bypass the contract. JDBC connection, bind, SQL, timeout, limit, conversion, scalar cardinality, and finalization errors remain `ERROR`.

## 11. Compatibility and migration

Existing `att-tool-group/v2.2` and `att-config/v2.1|v2.2|v2.5` command-backed Tools keep their argv, SSH, output parsing, retry, and evidence behavior. They require no edits.

Canonical built-ins use package-qualified names: `str.*`, `date.*`, `file.*`, and `misc.*`, for example `#{str.lpad('7', 4, '0')}`, `#{file.move(source, target)}`, and `#{misc.nvl(value, fallback)}`. Existing flat names remain callable aliases for source compatibility. Built-in package roots are framework-owned: a configured Tool/group may not claim `str`, `date`, `file`, or `misc`, nor any canonical or legacy built-in name.

Every executed Action stores its final rendered `description` separately from `expected`, `actual`, status, and message. `run.yaml`, CI JSON, JUnit diagnostic text, and the HTML Action Results table retain that field; the HTML table displays a dedicated Description column. For backward compatibility, Case Expected aggregation continues to contain each assert description followed by its `expected` value.

To add a façade:

1. keep the dbhelper in its independent `att-dbhelper/v2.5` file;
2. change the main file to `att-config/v2.6`;
3. add an `att-tool-group/v2.6` file or a global V2.6 Tool;
4. declare arguments and one exact `call`;
5. choose cache only when its stale-read semantics are acceptable;
6. use the qualified Tool name from Actions or expressions.

Direct `type: db` and `#{db.orders.query|scalar(...)}` remain valid and are preferable for one-off SQL. A Tool façade is preferable when an operation has a stable business name, repeated SQL/binding rules, documentation value, or an intentional cache policy.

## 12. V2.6.1 reference-helper hardening

V2.6.1 keeps the core Tool execution contract unchanged and tightens only the shipped FPP wrapper behavior:

- `loghelper.sh` distinguishes an intentional reverse-scan early stop from a real reverse-reader failure. The awk consumer writes an invocation-local marker immediately before its planned exit. Producer status `1` or SIGPIPE-style `141` is accepted only when that marker exists; any other non-zero producer status remains an error.
- Each `loghelper` log path may be a pathname pattern. Patterns are expanded independently on the local or remote host, accept regular files only, use C-locale ordering, deduplicate canonical paths, and diagnose unmatched patterns. Original patterns—not local matches—are forwarded to remote helpers.
- Each `exehelper` child argument containing `*`, `?`, or `[` is pathname-expanded after ATT has already formed atomic argv. Matching paths become separate atomic arguments in C-locale order; spaces are preserved and an unmatched pattern remains literal. The executable and capture paths are not expanded.
- `loghelper` recognizes `localhost`, loopback addresses, or the current hostname in a shared SSH server list as the local host already covered by the local pass. It skips SSH for that entry without requiring remote-only fields. Other entries retain strict SSH validation.
- These helpers do not introduce a shell. Variable expansion, command substitution, pipes, redirection, and other shell syntax remain literal. Ordinary process-backed Tools do not inherit the helpers' pathname expansion.

The reverse-scan marker is temporary, private to one invocation, and removed by the existing cleanup trap. It must not be inferred from a status code alone, because both a planned closed pipe and an actual reader failure can surface as status `1` on supported platforms.

## 13. Acceptance criteria

V2.6.1 is complete when tests prove:

- current and legacy schemas load with the documented boundaries;
- V2.6 rejects `delimit`, inline/typed Lists expand atomically, and legacy delimiter schemas retain read compatibility;
- canonical built-in packages work, legacy aliases remain compatible, and Tool/built-in qualified-name collisions are rejected;
- command-backed Tool behavior is unchanged;
- query/scalar façades return typed values in Actions and expressions;
- update façades work only as primary Tool Actions;
- dbhelper timeout and transaction behavior still apply;
- `case` and `db` cache hits, typed keys, evidence, and lifetimes are correct;
- DB update, commit/rollback, and reconnect do not clear cache;
- direct DB `saveAs.format: text` and `dbText(...)` produce the same deterministic SQL*Plus-style representation without changing typed Context values;
- errors become `ERROR` and process-only fields never appear;
- structured `saveAs`, package validation, generated docs, and distribution gates pass.
- reverse-reader status `1` is accepted only after an intentional early stop, while an unmarked status `1` still fails;
- loghelper expands local/remote log patterns, exehelper expands child-argument patterns, and paths containing spaces stay atomic;
- a local-host entry in the loghelper SSH list is skipped without SSH while ordinary remote validation remains enforced;
- documented Tool-call examples preserve apostrophes, double quotes, Context interpolation, and literal shell-like text.
- HTML and machine-readable Action results expose rendered descriptions independently from expected values.
