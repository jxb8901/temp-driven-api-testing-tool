### 5.2 DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.5` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

A `type: db` Action selects one helper ID and exactly one `query` or `update` block. Read operations are also available through supported `#{db.<id>.query(...)}` / `scalar(...)` expression calls. Positional JDBC `?` bindings and direct-Action named `:name` parameters are supported by the documented contracts.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. The executor applies the shorter effective limit between the Action timeout and the DBHelper `statement.timeoutSeconds`; each retry attempt gets a fresh Action timeout and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION` and/or `TIMEOUT`. An explicit Action `timeoutMs` overrides the helper's statement-timeout default; without it, the helper default applies. JDBC query timeout is rounded up to whole seconds while ATT retains millisecond deadline cancellation. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; the top-level `output.result` / `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

```yaml
actions:
  waitForOrder:
    type: db
    db: orders
    timeoutMs: 1500
    query:
      sql: select status from orders where id = :id
      parameters:
        id: "${EXEC.INPUT.orderId}"
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

Direct `update` Actions support `timeoutMs` but deliberately reject `retry`. A timeout or database/transport failure cannot generally prove whether a mutation reached or committed at the server, so generic automatic replay could duplicate business state. Application-specific idempotent retry must be modeled explicitly instead.

DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Action-level timeout/retry extends the shared Action lifecycle without changing the DBHelper identity or Context model.
