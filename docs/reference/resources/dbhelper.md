# DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.6` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: Orders JDBC resource
connection:
  driverClass: oracle.jdbc.OracleDriver
  url: ${ENV:ORDERS_DB_URL}
  username: ${ENV:ORDERS_DB_USERNAME}
  password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

In the current `att-template/v3.6` contract, DB operations run under ordinary `type: tool` Actions using `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)` calls. The call has exactly one String `sql` argument. Use a file-content expression such as `sql=&{sql/find-order.sql}` when the SQL is stored in the package; `sqlFile` is historical-only. Positional `params` and named `parameters` are mutually exclusive and use the same JDBC binding rules.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

[Reliability](../reliability-execution-control.md) owns Action timeout precedence, retry eligibility, attempt limits and replay cautions. DBHelper owns the descriptor's statement timeout and transaction lifecycle. Example of an eligible query:

```yaml
actions:
  waitForOrder:
    timeoutMs: 1500
    type: tool
    call: >-
      #{db.orders.query(
        sql='select status from orders where id = :id',
        parameters={id: ${EXEC.INPUT.orderId}}
      )}
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

An explicit update is also a Tool Action. It must not use automatic retry:

```yaml
actions:
  markOrder:
    type: tool
    call: "#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}"
```

The historical v3.5/v3.4 `type: db` Action and its `query`/`update` blocks remain available only through the archived schemas and compatibility loaders.



DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Action-level timeout/retry extends the shared Action lifecycle without changing the DBHelper identity or Context model.

## DBHelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.6` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `statement`, `transaction`, `result`, `evidence`, `pool`, `x-*` |
| `connection` | required | required `url`; optional `username`, `password`, `driverClass`, `properties`, `readOnly`, `isolation`, `x-*` |
| `statement` | defaults | `timeoutSeconds` defaults to 30, integer 1–3600 |
| `transaction` | defaults | `scope: case|statement`, `onEnd: commit|rollback`; defaults `case`/`rollback` |
| `result` | defaults | `maxRows` 1000, `maxCellBytes` 1048576, `maxBytes` 10485760; positive bounded integers |
| `evidence` | defaults | `sql: full\|hash` defaults full; `parameters: values\|types\|masked` defaults values; optional `output: {format: json\|yaml\|xml\|text\|sqlplus, maxChars: 10000}` |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `connectionTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize`, timeout is at least 250ms |

Query result byte limits are accounted one row at a time; CLOB UTF-8 bytes are counted while reading chunks. This keeps limit enforcement linear in the returned data size.

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [DBHelper](dbhelper.md) for Action, expression, result, security, and lifecycle behaviour.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

The `waitForOrder` query above needs no following Log Action. Configure its DBHelper like this to retain an automatic SQL*Plus-style row snapshot:

```yaml
evidence:
  sql: full
  parameters: masked
  output:
    format: sqlplus
    maxChars: 10000
```

`sql` controls SQL evidence; `parameters` accepts only `values`, `types`, or `masked` (there is no `no_mask` option), and defaults to `values`. Configured credentials remain redacted under every parameter mode. `output` affects presentation only; assertions and later Actions still read typed rows, and `db.<id>.query`/`scalar` use the same policy. `maxChars` defaults to 10000 and ranges from 1 to 1000000. A large `SELECT` may need a higher limit to retain its complete formatted snapshot.
