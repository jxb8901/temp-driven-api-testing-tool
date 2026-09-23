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

DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Future DB Action-level timeout/retry belongs to Chapter 8 without changing the DBHelper identity model.
