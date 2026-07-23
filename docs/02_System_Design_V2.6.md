# ATT V2.6 Call-backed Tool System Design

Status: final design; implemented for V2.6.0
Target release: 2.6.0
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
- reuse existing Tool names, descriptions, arguments, documentation, and grouping;
- preserve typed query/scalar/update results without stdout parsing;
- allow reusable SQL, parameter binding, SQL files, pure built-ins, and cache policy;
- keep SQL timeout, connection, transaction, limits, read-only, and evidence policy owned by the dbhelper instance;
- maintain strict validation and V2.2/V2.5 configuration compatibility.

V2.6 does not:

- make dbhelper a process Tool implementation;
- permit one configured Tool to call another configured Tool;
- add named SQL parameters, batch SQL, generated keys, stored procedures, or JDBC driver bundling;
- apply process argv, SSH, stdout parser, exit-code retry, or Action timeout settings to a call-backed Tool;
- automatically invalidate cache after DB update, commit, rollback, or reconnect.

## 3. Version ownership

| Artifact | Current schema | Compatibility |
|---|---|---|
| Product | `2.6.0` | V2.5 behavior remains readable |
| Main configuration | `att-config/v2.6` | reads `v2.5`, `v2.2`, and `v2.1` |
| Tool group | `att-tool-group/v2.6` | reads unchanged `v2.2` groups |
| Dbhelper | `att-dbhelper/v2.5` | unchanged |
| Template | `att-template/v2.5` | unchanged; the Action call syntax did not change |
| Schema catalog | `att-schema-catalog/v2.6` | includes current and legacy schemas |

`call` and `cache` are accepted only in V2.6 Tool descriptors. An older schema never silently changes meaning.

## 4. Configuration contract

### 4.1 Tool group

```yaml
schemaVersion: att-tool-group/v2.6
id: orders
name: Order database tools
description: Reusable order operations

tools:
  find:
    name: Find orders
    description: Find a customer's orders in one status
    call: "#{db.orders.query(sql='select order_id, status from orders where customer_id = ? and status = ?', params=[input.customerId, input.status])}"
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
    call: "#{db.orders.scalar(sql='select count(*) from orders where customer_id = ?', params=[input.customerId])}"
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
    call: "#{db.orders.update(sql='update orders set status = ? where order_id = ?', params=[input.status, input.orderId])}"
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
| argument `delimit`, `argName`, `argNameMode` | supported | forbidden |
| group/global SSH and group `script` | supported | forbidden |
| `cache` | forbidden | optional |

`call` must be one exact `#{...}` expression. Its primary target is limited to:

- `db.<instance>.query(...)`;
- `db.<instance>.scalar(...)`;
- `db.<instance>.update(...)`; or
- one ATT pure built-in.

Nested calls may be pure built-ins only. This restriction prevents Tool dependency cycles and leaves a clean future boundary for a Java Tool SPI.

### 4.3 Definition scope

The definition reads declared arguments through typed `input.*` paths:

```yaml
call: "#{db.orders.query(sql='select * from orders where customer_id = ? and status = ?', params=[input.customerId, #{upper(input.status)}])}"
```

`${input.customerId}` and legacy-compatible `TOOL.input.*` forms are also accepted. `CASE`, `RUN`, `ACTIONS`, and invocation evidence roots are forbidden in the Tool definition. Case-specific values are supplied at the outer call site. This keeps the façade reusable and independently documentable.

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

To add a façade:

1. keep the dbhelper in its independent `att-dbhelper/v2.5` file;
2. change the main file to `att-config/v2.6`;
3. add an `att-tool-group/v2.6` file or a global V2.6 Tool;
4. declare arguments and one exact `call`;
5. choose cache only when its stale-read semantics are acceptable;
6. use the qualified Tool name from Actions or expressions.

Direct `type: db` and `#{db.orders.query|scalar(...)}` remain valid and are preferable for one-off SQL. A Tool façade is preferable when an operation has a stable business name, repeated SQL/binding rules, documentation value, or an intentional cache policy.

## 12. Acceptance criteria

V2.6 is complete when tests prove:

- current and legacy schemas load with the documented boundaries;
- command-backed Tool behavior is unchanged;
- query/scalar façades return typed values in Actions and expressions;
- update façades work only as primary Tool Actions;
- dbhelper timeout and transaction behavior still apply;
- `case` and `db` cache hits, typed keys, evidence, and lifetimes are correct;
- DB update, commit/rollback, and reconnect do not clear cache;
- errors become `ERROR` and process-only fields never appear;
- structured `saveAs`, package validation, generated docs, and distribution gates pass.
