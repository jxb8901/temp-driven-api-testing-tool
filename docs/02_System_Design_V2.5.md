# ATT V2.5 Database Helper System Design

Status: final design; implemented for V2.5.0
Target release: 2.5.0
Replaces: the earlier draft that modelled dbhelper as `tools.<name>.implementation`

## 1. Design conclusion

dbhelper is a first-class ATT database service, not a process Tool and not the first implementation of a generic Java Tool SPI.

The earlier design optimized for one internal dispatch path: dbhelper was declared below `tools`, wrapped in `implementation.type/name/config`, and called through a Tool expression. That made the runtime superficially uniform but made the package configuration harder to read and coupled database semantics to argv, exit-code, retry, stdout parsing, and Tool-group concepts that do not apply to JDBC.

V2.5 therefore separates configuration and execution by domain:

| Concern | Process Tool | Database helper |
|---|---|---|
| Configuration namespace | `tools` / Tool groups | referenced `att-dbhelper/v2.5` files |
| Action type | `type: tool` | `type: db`, or a namespaced read expression |
| Invocation | `call: "#{tool(...)}"` | explicit Action fields or `#{db.<instance>.query(...)}` |
| Input model | argv arguments | typed JDBC parameters |
| Success/failure | process completion plus optional assert | JDBC operation error is always ERROR |
| Output source | stdout decoded by configured format | Java object produced directly |
| `saveAs` source | raw stdout or typed result, selected by format | typed result only |
| Retry | Tool retry policy | no automatic DB retry in V2.5 |
| Lifecycle | one process per call | one Connection per instance and execution thread |
| Group/SSH | supported | not applicable |

The two domains still use the common ATT Action outcome, Context, expression, `onFailure`, assertion, `saveAs`, Case log, and report contracts. This is the useful level of consistency; their configuration and execution mechanics remain separate.

## 2. Goals and non-goals

### 2.1 Goals

- Make every database instance recognizable without understanding Tool internals.
- Keep connection, statement, transaction, result, and evidence settings in named blocks.
- Configure SQL timeout per dbhelper instance.
- Use typed JDBC parameters without SQL interpolation.
- Permit DB queries in expressions without routing them through Tool configuration.
- Return one stable Java object shape for every query cardinality.
- Reuse Connections by instance and execution thread while isolating adjacent Cases.
- Make every DB operation/finalization error a Case `ERROR`.
- Keep credentials out of generated documentation and runtime evidence.
- Load user-supplied JDBC drivers from package-root `lib/`.

### 2.2 Non-goals

- A general third-party Java Tool SPI. That remains a separate future design.
- A connection pool, distributed transaction manager, ORM, SQL parser, or migration tool.
- Named SQL parameters, batch operations, callable statements, generated keys, or multiple JDBC results.
- Automatic retry of SQL or transaction completion. Retrying updates can duplicate effects and must not be inherited from Tool retry semantics.
- Database updates hidden inside expressions. Updates require an explicit `type: db` Action.
- Dynamic loading or version isolation of jars after the ATT JVM has started.

## 3. Dedicated configuration model

### 3.1 Main configuration references

`config.yaml` does not contain JDBC, credential, timeout, transaction, limit, or evidence settings. Like `toolGroups`, it only contains an explicit package-relative list of independent configuration files:

```yaml
schemaVersion: att-config/v2.5

dbhelpers:
  - config/dbhelpers/orders.yaml
  - config/dbhelpers/audit.yaml
```

`tools` returns to the process Tool contract used by V2.1/V2.2; it contains no `implementation`, `builtin`, JDBC, or dbhelper fields. dbhelpers are not declared inside Tool groups.

Each path must be a non-empty, package-contained `.yaml` or `.yml` file. Duplicate normalized paths are invalid. ATT loads and schema-validates every referenced file during package validation, before any Case starts. Explicit references are used instead of directory auto-discovery so validation, packaging, and execution always resolve the same inputs.

`dbhelpers` is optional and defaults to an empty list. Its schema uses `uniqueItems: true`; runtime validation additionally compares normalized paths so spelling variants cannot load one file twice.

### 3.2 Independent dbhelper file

One file defines exactly one dbhelper instance:

```yaml
# config/dbhelpers/orders.yaml
schemaVersion: att-dbhelper/v2.5
id: orders
name: Orders database
description: Query and update the orders schema

connection:
  url: jdbc:postgresql://db.example/orders
  username: att_user
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
  properties:
    connectTimeout: "10"
  readOnly: false
  isolation: readCommitted

statement:
  timeoutSeconds: 30

transaction:
  scope: case
  onEnd: rollback

result:
  maxRows: 1000
  maxCellBytes: 1048576
  maxBytes: 10485760

evidence:
  sql: full
  parameters: masked
```

`id` is the stable package-global runtime identity used by `type: db` and `db.<id>.*` expressions. IDs must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be unique case-insensitively across every referenced dbhelper file. Tool names do not conflict because DB expressions use the reserved `db` namespace.

`name` and `description` are required human-readable metadata. There is no `type: builtin`, `name: dbhelper`, nested `implementation.config`, Tool `arguments`, or instance-level serialization `output`.

Required top-level file fields are `schemaVersion`, `id`, `name`, `description`, and `connection`. `connection.url` is required. `statement`, `transaction`, `result`, and `evidence` are optional blocks with the defaults specified below. Unknown fields are rejected except `x-*` extension fields.

The one-instance-per-file rule is intentional. Connection credentials, transaction policy, timeout, and evidence exposure form one operational unit. A multi-instance "dbhelper group" would add nesting and group identity without changing JDBC lifecycle. The loading experience is similar to Tool groups, while the file structure follows the database domain rather than copying Tool-group structure mechanically.

### 3.3 Connection block

| Field | Required/default | Meaning |
|---|---|---|
| `url` | required | JDBC URL; never copied into normal invocation evidence |
| `username` | optional | JDBC username |
| `password` | optional | literal or complete `${ENV:NAME}` reference |
| `driverClass` | optional | explicit legacy driver loading; JDBC service discovery is preferred |
| `properties` | `{}` | string-valued driver properties, including vendor connection timeouts |
| `readOnly` | `false` | rejects DB `update` Actions for this instance and is also passed to `Connection.setReadOnly` |
| `isolation` | `driverDefault` | `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable` |

Environment references must occupy the complete scalar. A missing environment variable is a configuration error. Passwords, JDBC URLs, and values of properties whose keys contain `password`, `secret`, or `token` are redacted from driver messages.

ATT does not expose a generic `connectionTimeoutSeconds`, because JDBC has no safe per-Connection portable setter for it. Such settings belong in driver-specific `properties`.

`readOnly: true` is enforced by ATT package validation for explicit DB Actions and reinforced through JDBC. It is still not a complete security boundary: query SQL and vendor functions may have side effects, so database account permissions remain authoritative.

### 3.4 Statement block

`statement.timeoutSeconds` is the instance-level SQL timeout. It defaults to 30, accepts 1–3600, and is applied with `PreparedStatement.setQueryTimeout` to every query and update. V2.5 does not permit an Action to override it; this keeps the operational limit controlled by the database instance owner rather than individual testcase authors.

### 3.5 Transaction block

Transaction behaviour uses two orthogonal fields instead of four implementation-oriented enum names:

| `scope` | `onEnd` | JDBC behaviour |
|---|---|---|
| `statement` | `commit` | JDBC auto-commit; equivalent to the old `autoCommit` |
| `statement` | `rollback` | manual rollback after every operation; equivalent to `rollbackEach` |
| `case` | `commit` | commit at Case end; equivalent to `commitAtCaseEnd` |
| `case` | `rollback` | rollback at Case end; equivalent to `rollbackAtCaseEnd` |

Defaults are `scope: case` and `onEnd: rollback`, because a testing tool should not persist updates unless the package owner opts in explicitly.

### 3.6 Result and evidence blocks

`result` contains hard safety limits. ATT returns an error rather than truncating data.

- `maxRows`: default 1000.
- `maxCellBytes`: default 1 MiB.
- `maxBytes`: default 10 MiB for the typed/encoded result.

`evidence` makes the data-exposure decision visible:

- `sql`: `full` (default) or `hash`.
- `parameters`: `masked` (default), `types`, or `values`.

Credentials are never eligible for evidence. `parameters: values` is an explicit package-owner opt-in because SQL parameters may contain tokens, personal data, or account identifiers.

## 4. Dedicated DB Action and expression API

### 4.1 Syntax

The syntax for a visible database workflow step is a dedicated Action:

```yaml
- id: queryOrder
  type: db
  db: orders
  query:
    sql: >-
      select id, status
      from orders
      where id = ?
    params:
      - "${CASE.orderId}"
  saveAs:
    path: order-result.json
    format: json
    overwrite: false
  assert: "${output.result.rowCount} == 1"
```

An update is explicit:

```yaml
- id: closeOrder
  type: db
  db: orders
  update:
    sql: "update orders set status = ? where id = ?"
    params: [CLOSED, "${CASE.orderId}"]
  assert: "${output.result.affectedRows} == 1"
```

Required fields are `id`, `type`, and `db`, plus exactly one of `query` or `update`. The selected block is the operation; there is no separate `operation: query|update` discriminator. ATT uses `executeQuery` for `query` and `executeUpdate` for `update`, so a mismatched statement fails before ATT interprets a result.

Each block contains exactly one SQL source (`sql` or `sqlFile`) and an optional ordered `params` list bound with `PreparedStatement.setObject`. Grouping source and parameters under `query`/`update` keeps the Action readable and permits additional source types without adding parallel Action-level fields such as `querySql`, `queryFile`, `updateSql`, and `updateFile`.

`params` may be an inline YAML list or one exact Context reference whose Java value is a `List`. Exact references preserve the typed List and its typed elements; ATT does not stringify and reparse them.

DB Action fields are:

| Field | Required/default | Contract |
|---|---|---|
| `id` | required | normal unique Action ID |
| `type` | required | exactly `db` |
| `db` | required | referenced dbhelper ID |
| `query` / `update` | exactly one | operation and SQL-source block |
| `description` | empty | normal rendered Action description |
| `assert` | optional | evaluated only after successful DB execution |
| `saveAs` | optional | common V2.5 artifact settings; DB permits typed structured formats only |
| `onFailure` | normal Action default | controls subsequent Action flow, not Case status aggregation |

`retry` and Action-level `timeoutMs` are invalid for `type: db`. SQL retry is unsafe for updates, and statement timeout belongs to the referenced dbhelper instance.

The explicit Action is required for updates and is preferred when a query needs its own Action outcome, assertion, `saveAs`, description, or `onFailure` behaviour.

V2.5 does not retain the ambiguous old `#{orders(...)}` form. Expression calls use the reserved `db` namespace described below, so they cannot be mistaken for a Tool or built-in function.

### 4.2 Query expressions

DB data may be fetched directly in expressions through a reserved, instance-qualified namespace:

```yaml
assert: >-
  #{db.orders.scalar(
      sql='select count(*) from orders where customer_id = ?',
      params=${CASE.customerParams}
  )} == 1
```

Two read operations are available:

- `#{db.<instance>.query(sql=..., params=...)}` returns the same stable query result object used by a `type: db` query Action.
- `#{db.<instance>.scalar(sql=..., params=...)}` requires exactly one row and one column and returns that typed cell value. Zero rows, multiple rows, or multiple columns are `ERROR`, not null/default coercion.

An exact `query(...)` call can be assigned as a typed value and accessed later:

```yaml
- id: loadOrders
  type: assign
  name: customerOrders
  expression: >-
    #{db.orders.query(
        sql='select id, status from orders where customer_id = ?',
        params=${CASE.customerParams}
    )}

- id: verifyOrders
  type: assert
  assert: "${CASE.VARS.customerOrders.rowCount} > 0"
```

This requires the expression engine to preserve the Java object when a DB call is the complete expression value. A non-scalar query result cannot be interpolated into surrounding text; authors must assign it or use a `type: db` Action. `scalar(...)` may be used as an ordinary expression operand.

Every expression call executes once at the point where its containing Action is evaluated and records child evidence at `ACTIONS.<action>.DB.<instance>.<callId>`. Validation parses and resolves the instance but never connects or executes SQL. A query error makes the containing Action and Case `ERROR`; it cannot be converted into PASS/FAIL by the surrounding expression.

Expression calls support queries only. This is an authoring safeguard, not a database security boundary: a vendor query may invoke functions with side effects, so database permissions and `connection.readOnly` remain important. `update`, DDL, callable statements, and generic `execute` are rejected in the expression namespace.

### 4.3 SQL source and rendering

The `query` or `update` block accepts exactly one source:

```yaml
query:
  sql: "${ACTIONS.renderSql.output.result}"
  params: "${CASE.queryParams}"
```

or with file-backed SQL:

```yaml
query:
  sqlFile: sql/orders/find-by-customer.sql
  params:
    - "${CASE.customerId}"
    - OPEN
```

`sql` is a text template, not necessarily a literal statement. ATT renders it against the current Context before preparing the statement. An exact reference to a preceding render Action therefore works naturally, as do ordinary Context references and pure built-in functions inside inline SQL.

`sqlFile` is a static, package-relative, UTF-8 file path. Package validation requires the file to exist, remain inside the package, and be a regular file. ATT reads the file at execution time and renders its contents using the same rules as `sql`; the path itself is not rendered. Evidence records the source path, source hash, and rendered SQL according to the instance evidence policy.

V2.5 implements both `sql` and `sqlFile` for DB Actions, `query(...)`, and `scalar(...)`.

SQL-source rendering accepts Context references and pure ATT built-in functions. It does not execute configured Tools or nested `db.*` calls. External work needed to construct SQL must be performed by an earlier explicit Action and referenced through Context. This prevents hidden recursive database work and side effects while still allowing a render Action's result to supply the complete SQL string.

Rendering produces SQL structure; JDBC parameters remain separate. Test data should use `?` plus `params`, rather than `${...}` substitution into SQL values. ATT does not parse, normalize, classify, or rewrite the rendered vendor SQL.

The same source alternatives apply to expression queries:

```yaml
#{db.orders.query(
    sql=${ACTIONS.renderSql.output.result},
    params=${CASE.queryParams}
)}

#{db.orders.query(
    sqlFile='sql/orders/find-by-customer.sql',
    params=${CASE.queryParams}
)}
```

`query(...)` and `scalar(...)` require exactly one of `sql` or `sqlFile`.

### 4.4 Common `saveAs` contract

V2.5 gives `type: tool` and `type: db` the same visible `saveAs` structure:

```yaml
saveAs:
  path: relative/path/result.yaml
  format: yaml
  overwrite: false
```

`path` is required and `overwrite` defaults to `false`. `format` controls what is written; it does not change the Action's typed `${output.result}` value. The Action-level sibling field `overwrite` and scalar `saveAs: file.name` are not valid authoring forms in `att-template/v2.5`.

The shared shape does not imply identical data sources:

| Action target | Allowed `format` | Default | Saved content |
|---|---|---|---|
| configured process Tool | `raw`, `text`, `json`, `yaml`, `xml` | `raw` | exact stdout bytes for `raw`; otherwise the parsed typed `${output.result}` |
| primary Java built-in called by `type: tool` | `text`, `json`, `yaml`, `xml` | `text` | the built-in's typed `${output.result}`; `text` uses its UTF-8 text representation |
| `type: db` | `json`, `yaml`, `xml` | none; required | the stable typed DB result object |

`raw` exists only for a configured process Tool because only that target has original stdout bytes. `text` encodes `String.valueOf(output.result)` as UTF-8. Structured formats serialize the typed result through the corresponding codec; they never serialize process stdout directly. A configured Tool's declared `output: txt|json|yaml|xml` still controls stdout parsing and therefore the typed value available to non-`raw` formats.

`saveAs.path` is rendered in the normal pre-Action expression scope and must resolve to a non-blank safe relative path under the current Case artifact directory. Absolute paths, backslashes, empty/`.`/`..` segments, and containment escapes are invalid. The current Action's `${output...}` is not available while the path is rendered. Parent directories are created as needed.

Write timing follows the selected content source and always precedes the optional Action assertion. A process Tool with `raw` saves captured stdout even when output parsing, exit-code retry, or the final Action outcome is unsuccessful; a non-`raw` Tool artifact requires a successfully parsed typed result. A built-in artifact requires a successful built-in call, and a DB artifact requires successful JDBC execution. Serialization, invalid content for the selected codec, path resolution, collision, or file-write failure makes the Action `ERROR`. If the target already exists, `overwrite: false` is `ERROR`; Tool retries reuse the same resolved path and replace only the artifact written by an earlier attempt of that same Action so the last artifact-capable attempt remains visible. The saved path is included in `output.targetFiles`; DB serialization never replaces the typed Context result.

`render`, `assert`, `log`, and `assign` do not support `saveAs`. Rendered files continue to use `renderAs: file`; this is output production, not persistence of another Action's result.

## 5. Stable result contract

Query cardinality must not change the Java type. `rows` is always a list, including zero or one row:

```yaml
success: true
operation: query
rowCount: 1
rows:
  - ID: A100
    STATUS: READY
affectedRows: null
transaction:
  scope: case
  onEnd: rollback
  state: PENDING_ROLLBACK
error: null
```

Update result:

```yaml
success: true
operation: update
rowCount: 0
rows: []
affectedRows: 1
transaction:
  scope: case
  onEnd: rollback
  state: PENDING_ROLLBACK
error: null
```

Column maps preserve JDBC column order and use `ResultSetMetaData.getColumnLabel`. Duplicate labels are errors and require explicit SQL aliases. Portable cell values are null, booleans, numbers, strings, temporal strings, Base64 binary strings, and bounded CLOB/BLOB values. Unknown vendor values become strings.

A failed operation keeps the same top-level shape with `success: false` and a sanitized `error` containing `type`, `message`, `sqlState`, and `vendorCode`. It is retained as evidence but the Action and Case are always `ERROR`; an assertion cannot downgrade an operational DB error to PASS or FAIL.

`error.type` is one of `CONNECTION_ERROR`, `BIND_ERROR`, `SQL_ERROR`, `TIMEOUT`, `LIMIT_EXCEEDED`, `ROLLBACK_ONLY`, or `FINALIZE_ERROR`. Configuration/template validation failures occur before invocation and therefore do not create a DB result object. On failure, `rows` is empty, `rowCount` is zero, and `affectedRows` is null.

Transaction states in invocation results are `AUTO_COMMITTED`, `ROLLED_BACK`, `PENDING_COMMIT`, `PENDING_ROLLBACK`, or `ROLLBACK_ONLY`. Case finalization replaces a pending state with `COMMITTED`, `ROLLED_BACK`, or `FINALIZE_ERROR` in finalization evidence.

## 6. Connection and transaction lifecycle

Connections belong to a dbhelper instance and execution thread, not to a Case. The run-scoped `DbConnectionManager` lazily opens one Connection per used instance/thread and reuses it across Cases assigned to that thread. Case completion applies the configured transaction action to every instance used by that Case but does not close the Connection. Connections are closed when the worker thread is shut down or the ATT run ends.

Before a later Case starts, ATT rolls back every open non-auto-commit Connection on that thread and clears rollback-only/per-Case state. Auto-commit Connections have no pending transaction to roll back. If isolation rollback throws, ATT closes the old Connection and immediately attempts to reconnect. This recovery attempt does not affect the new Case status; if the database is still unavailable, the first actual DB Action becomes `ERROR`.

For `scope: case`, any SQL/JDBC error marks that instance rollback-only for the current Case. Later Actions using the same instance return `ERROR` without sending SQL, and Case completion rolls back. Commit/rollback finalization errors are lifecycle `ERROR`s and replace the unusable Connection.

Expression queries participate in the same per-thread Connection and current-Case transaction as explicit DB Actions. Database DDL executed through an `update` block may commit implicitly according to vendor rules; ATT cannot make vendor implicit commits transactional.

`CASE.DB` is a fixed, case-sensitive, framework-owned Context map. ATT creates the empty map when the Case Context is initialized; package data and Case-root aliases cannot define or overwrite `DB`. Case completion publishes every used instance's finalization outcome at `CASE.DB.<instance>`, including configured `scope`/`onEnd`, final `state`, `success`, and sanitized `error`. An unused instance has no entry and is not committed or rolled back at Case end; it is still included in the next Case's pre-Case isolation rollback if that thread has an open non-auto-commit Connection.

`CASE.DB.<instance>` contains Case-level transaction finalization only. Per-operation typed results remain at `ACTIONS.<action>.output.result`, and invocation evidence remains below the containing Action. The separate root `DB` scope is reserved for the currently evaluated invocation's internal evidence: `${CASE.DB.orders.state}` and `${DB...}` are therefore different canonical paths. The lowercase `#{db.<instance>.query/scalar(...)}` form is a callable read-expression namespace, not a Context node. Because instance entries are published after the Case's Actions have finished, Actions in that same Case must not depend on `${CASE.DB.<instance>...}`; it is intended for persisted Context, Case logs, reports, and post-Case consumers.

Validation, documentation generation, snapshot operations, and dry-run never open database connections.

## 7. Error, assertion, and flow control

- Invalid dbhelper configuration is a validation error; execution does not start.
- Invalid DB Action fields, selecting both/neither `query` and `update`, or selecting both/neither `sql` and `sqlFile` are template validation errors.
- Invalid DB expression syntax, unknown instances, or non-query expression operations are template validation errors.
- Connection, prepare, bind, timeout, execute, result conversion, limit, rollback-only, and transaction-finalization problems are `ERROR`.
- A successful Action or expression query followed by an assertion evaluating false is `FAIL`; assertions are not evaluated after DB execution errors.
- `onFailure: continue` may allow later Actions to run, but aggregation keeps the Case `ERROR`.
- V2.5 performs no automatic SQL retry. Connection replacement at a Case boundary is lifecycle recovery, not operation retry.

## 8. JDBC driver loading

Source and release launchers add startup-time package-root `lib/*.jar` to the JVM classpath. ATT bundles no database-specific driver. JDBC service discovery is used by default; `connection.driverClass` supports legacy drivers. Users must supply the driver and all transitive jars and restart ATT after changing `lib`.

V2.5 uses a flat shared classpath. Dynamic reload and per-driver dependency isolation are future concerns.

## 9. Runtime architecture

The database implementation is independent of Tool execution:

- `DbHelperRegistry`: loads referenced `att-dbhelper/v2.5` files, enforces package-global ID uniqueness, and resolves instances.
- `DbActionExecutor`: selects the `query`/`update` block, resolves and renders its SQL source, binds parameters, executes JDBC, and creates the stable result object.
- `DbExpressionProvider`: resolves `db.<instance>.query/scalar`, preserves exact-call typed values, and attaches child evidence to the containing Action.
- `DbConnectionManager`: owns thread-local Connections and Case-boundary transaction hooks.
- `ActionResultArtifactWriter`: validates the common `saveAs` object, contains paths, selects raw/text/structured content, and writes Tool or DB artifacts.
- `DbResultCodec`: exposes DB typed results to the shared structured codecs; it has no DB-specific path contract.
- `StageTemplateRunner`: dispatches `type: db` and converts DB operational errors to `ERROR`.

`ToolConfig`, Tool groups, `ToolInvoker`, argv expansion, SSH, stdout parsing, process retries, and exit-code behaviour remain unchanged by dbhelper. The common V2.5 `saveAs` object is an independent template/artifact-writer change, not a reason to route JDBC through Tool execution. The expression engine delegates the reserved `db.*` namespace directly to `DbExpressionProvider`, not `ToolInvoker`. A future Java Tool SPI can be designed around actual extension requirements instead of inheriting JDBC-specific decisions.

## 10. Schema and migration

Because V2.5 has not been released, the earlier `tools.<name>.implementation.name: dbhelper` draft is removed rather than supported as legacy syntax. Version ownership is:

| Artifact | Schema | V2.5 change |
|---|---|---|
| Main configuration | `att-config/v2.5` | adds the dbhelper file-reference list only |
| Dbhelper instance | `att-dbhelper/v2.5` | new dedicated schema containing one instance |
| Template | `att-template/v2.5` | adds `type: db`, common structured `saveAs` for Tool/DB, and typed DB expression validation |
| Tool group | `att-tool-group/v2.2` | unchanged; remains process-Tool-only |
| Schema catalog | `att-schema-catalog/v2.5` | registers the new/current schemas and legacy read-compatible schemas |

ATT continues reading `att-config/v2.1`, `att-config/v2.2`, and `att-template/v2.3` without semantic changes. Those legacy schemas cannot reference dbhelpers or declare DB Actions. A legacy Tool Action such as `saveAs: response.json` plus sibling `overwrite: false` remains valid only under its legacy template schema and is normalized internally to `{path: response.json, format: raw, overwrite: false}`. Newly authored `att-template/v2.5` files must use the object form. There is no `att-tool-group/v2.5`, because V2.5 makes no Tool-group contract change.

The superseded working-tree draft was migrated as follows:

1. Create one `att-dbhelper/v2.5` file for each JDBC definition, add its path to the main `dbhelpers` reference list, and rename fields by block.
2. Replace each update call and each query requiring an explicit outcome with `type: db`; replace read-only inline calls with `#{db.<instance>.query/scalar(...)}`.
3. Replace cardinality-dependent `result.data` expressions with stable `result.rows` expressions.
4. Move serialization format from the instance's `output` to the Action's `saveAs.format`; migrate V2.5 Tool Actions from scalar `saveAs` plus sibling `overwrite` to the common object.
5. Remove dbhelper branches from `ToolConfig`, `ToolInvoker`, Tool-group schemas, and Tool documentation generation; restore `att-tool-group/v2.2` as the current Tool-group schema.

Runtime, schemas, validation, tests, launchers, package documentation, and the English/Chinese user manuals implement this contract. The superseded Tool-style draft is intentionally not accepted as compatibility syntax because it was never released.

## 11. Acceptance criteria

- `config.yaml` contains only explicit dbhelper file references; all JDBC and operational settings live in independent `att-dbhelper/v2.5` files.
- Each dbhelper file defines exactly one instance, and validation rejects missing files, escaping paths, duplicate paths, invalid schemas, and duplicate IDs.
- Every instance independently controls connection properties, SQL timeout, transaction policy, limits, and evidence exposure.
- Every DB Action contains exactly one `query` or `update` block; each block contains exactly one rendered `sql` or package-contained `sqlFile` source.
- DB Actions use typed fields, while read-only DB queries remain available through the explicit `db.<instance>.query/scalar` expression namespace.
- Query `rows` is always a list; zero, one, and many rows have one Java type.
- Exact query expressions preserve the typed result object; scalar expressions enforce exactly one row and column.
- SQL parameters remain typed JDBC values and are masked in evidence by default.
- DB operational errors and transaction-finalization errors always produce Case `ERROR`; assertion failures after successful DB operations produce `FAIL`.
- Connections are reused per instance/thread, isolated before adjacent Cases, reconnected after isolation rollback failure, and not closed at Case end.
- `CASE.DB` is a fixed framework-owned map; Case-root data cannot override it, used-instance finalization entries appear only at Case completion, and root `DB` invocation evidence remains a separate scope.
- Existing process Tool configuration and execution behaviour remain unchanged apart from the V2.5 template-level `saveAs` shape and added result-serialization formats.
- Source and packaged Unix/Windows launchers load user JDBC jars from `lib`.
- Unit, schema, engine, package-validation, build, and packaged-runtime tests cover the new dedicated path.

Required automated coverage includes:

- external file path containment, duplicate paths/IDs, missing environment variables, defaults, unknown fields, and every enum/range boundary;
- zero/one/many-row queries, duplicate labels, nulls, temporal/binary/LOB values, multi-parameter binding, result limits, and query/update mismatch;
- rendered inline SQL, exact render-Action SQL results, rendered `sqlFile`, missing/escaping files, source hashing, and prohibition of nested Tool/DB calls during SQL rendering;
- `query(...)` typed assignment, `scalar(...)` success/cardinality errors, inline-call evidence, and DB error propagation from every expression surface;
- both transaction scopes with commit/rollback, rollback-only, Case-boundary isolation, rollback-triggered reconnect, finalization failure, thread isolation, and run-end close;
- fixed `CASE.DB` creation, reserved-name collisions, used/unused instances, finalization publication timing, and separation from root `DB` invocation evidence;
- distinct per-instance `statement.timeoutSeconds`, credential/parameter redaction, read-only update rejection, all codecs, common Tool/DB `saveAs` defaults, containment, overwrite, retry, and legacy-normalization behaviour;
- unchanged V2.1/V2.2 process Tool and V2.3 template compatibility.

## 12. Final decisions

The following decisions are normative for V2.5:

1. Use `dbhelpers` in `config.yaml` only as a file-reference list, one `att-dbhelper/v2.5` file per instance, and `type: db` as the Action name.
2. Do not retain the old ambiguous `#{dbhelper(...)}` shorthand; support `#{db.<instance>.query/scalar(...)}` instead.
3. Use mutually exclusive `query`/`update` blocks instead of an `operation` discriminator; do not auto-detect author intent from SQL text.
4. Return `rows: []` consistently instead of the old zero-map/one-map/many-list contract.
5. Default transactions to Case-scope rollback and evidence parameters to masked.
6. Permit expression queries but require updates to use `type: db`.
7. Keep dbhelper IDs package-global; do not add an artificial dbhelper-group namespace.
8. Support both rendered inline `sql` and rendered package-contained `sqlFile` in V2.5.
9. Allow Context references and pure built-ins during SQL rendering; prohibit Tool calls and nested DB calls in SQL-source rendering.
10. Use one object-shaped `saveAs` contract for Tool and DB Actions, while keeping target-specific format rules; retain scalar Tool `saveAs` only when reading legacy template schemas.
11. Reserve fixed `CASE.DB.<instance>` for Case-level DB finalization outcomes; do not make the node configurable or reuse root `DB` invocation evidence as Case state.
