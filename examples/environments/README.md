# ATT V3.5.2 environment profile examples

ATT V3.5.2 adds a first-class `--env` selector over one common `att-config/v2.6` file. The selected profile owns the physical DB/MQ descriptor lists; Templates, Flows, and Actions keep stable logical helper IDs.

> Actions reference stable logical resources; environment differences belong to the configuration/resource layer.

The legacy complete-config pattern remains supported. Use it when environments intentionally have different testcase/template roots, report policy, or package structure; use profiles when only infrastructure/resource bindings differ.

## Profile contract

The root `environment` is the default profile name. `--env` overrides it, and matching is case-insensitive while the declared profile spelling is retained in runtime metadata. `--config` selects the common file; it may be combined with `--env` in all four profile-aware modes.

Only `dbhelpers` and `mqhelpers` are profile-overridable. Each list is a shallow replacement of the common list; omitted lists inherit the common value. There is no recursive YAML merge or environment-specific override for templates, testcase roots, tools, report settings, or execution policy. Unknown profile names and unsupported profile fields fail before validation or external execution.

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
environment: SIT                 # default; --env UAT overrides it
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/sample.yaml
tools: {}
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

The snippet shows the profile layer. Retain the common `report`, `run`, `execution`, and global `tools` entries from the package's `config/config.yaml` when copying it into a real package; do not move those settings into profiles.

## Package layout

```text
config/
├── config.yaml                  # V3.5.2 common config + profiles
├── environments/{sit,uat}.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

All paths are package-relative. The files under `config/environments/` are optional legacy complete-config migration sources; keep any existing package `tools`, `toolGroups`, templates, testcase roots, and report settings in the common `config/config.yaml` when using profiles.

## Complete environment configs

The executable, checked-in configs are the actual files under `config/environments/`:

- `config/environments/sit.yaml`
- `config/environments/uat.yaml`

Use those files as the copyable examples; they are intentionally complete package configs, not abbreviated overlays.

Both files preserve the common registry from `config/config.yaml`:

```yaml
toolGroups:
  - config/tools/sample.yaml
  - config/tools/fpp.yaml
  - config/tools/orders-db.yaml
```

The global `tools` map is also copied unchanged in both files, including `invokePaymentApi`, `selectCtxn`, `grepFromAppLogs`, `getAppLogs`, `genEndToEndId`, `getAcDate`, and `getSeq`. This is required because `PAYMENT_INVOKE` calls `invokePaymentApi` and `examples/load/closed-smoke.yaml` calls `sample.getAcDate`. Only `environment` and the DB/MQ descriptor paths differ:

```yaml
# SIT
environment: SIT
dbhelpers: [config/dbhelpers/sit/orders.yaml]
mqhelpers: [config/mqhelpers/sit/payment.yaml]

# UAT
environment: UAT
dbhelpers: [config/dbhelpers/uat/orders.yaml]
mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Do not replace the shared `toolGroups` or `tools` definitions with `[]` or `{}`. Do not create environment-specific helper or Action IDs.

See the DB/MQ descriptor, identical Action, CLI, CI, and secrets examples below.

## DB and MQ descriptors

Both DB descriptors use `id: orders`; only physical connection details change:

```yaml
# config/dbhelpers/sit/orders.yaml
schemaVersion: att-dbhelper/v2.5
id: orders
name: Orders database
description: SIT order database
connection:
  url: jdbc:postgresql://sit-db.example.internal:5432/orders
  username: "${ENV:ORDERS_DB_USERNAME}"
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
  properties: {connectTimeout: "10"}
  readOnly: false
  isolation: readCommitted
statement: {timeoutSeconds: 30}
transaction: {scope: case, onEnd: rollback}
result: {maxRows: 1000, maxCellBytes: 1048576, maxBytes: 10485760}
evidence: {sql: full, parameters: masked}
```

`config/dbhelpers/uat/orders.yaml`:

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
name: Orders database
description: UAT order database
connection:
  url: jdbc:postgresql://uat-db.example.internal:5432/orders
  username: "${ENV:ORDERS_DB_USERNAME}"
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
  properties: {connectTimeout: "10"}
  readOnly: false
  isolation: readCommitted
statement: {timeoutSeconds: 30}
transaction: {scope: case, onEnd: rollback}
result: {maxRows: 1000, maxCellBytes: 1048576, maxBytes: 10485760}
evidence: {sql: full, parameters: masked}
```

Both MQ descriptors use `id: payment`; only host, queue manager, port, and channel change:

```yaml
# config/mqhelpers/sit/payment.yaml
schemaVersion: att-mqhelper/v1.0
id: payment
name: Payment MQ
description: SIT payment request and reply queues
connection:
  queueManager: SITQM1
  host: sit-mq.example.internal
  port: 1414
  channel: SIT.APP.SVRCONN
  username: "${ENV:PAYMENT_MQ_USERNAME}"
  password: "${ENV:PAYMENT_MQ_PASSWORD}"
message: {ccsid: 1208, format: MQSTR, persistence: asQueue}
requestReply: {waitMs: 10000}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
evidence: {payload: metadata}
```

`config/mqhelpers/uat/payment.yaml`:

```yaml
schemaVersion: att-mqhelper/v1.0
id: payment
name: Payment MQ
description: UAT payment request and reply queues
connection:
  queueManager: UATQM1
  host: uat-mq.example.internal
  port: 1415
  channel: UAT.APP.SVRCONN
  username: "${ENV:PAYMENT_MQ_USERNAME}"
  password: "${ENV:PAYMENT_MQ_PASSWORD}"
message: {ccsid: 1208, format: MQSTR, persistence: asQueue}
requestReply: {waitMs: 10000}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
evidence: {payload: metadata}
```

DBHelper resolves complete `${ENV:NAME}` values in `connection.url`, `username`, `password`, and string-valued `connection.properties`. MQHelper resolves `${ENV:NAME}` only for username/password; host, queue manager, channel, and numeric port are normally literal values in the selected descriptor. Keep credentials in the process environment or CI secret store, not in committed YAML. The PostgreSQL driver and optional IBM MQ client jar must be supplied by the package as usual.

## Identical Actions

The same Template/Flow Action definitions are used with SIT and UAT:

```yaml
actions:
  renderRequest:
    type: render
    payload: payment/request.json
    renderAs: file
  queryOrder:
    type: db
    db: orders
    query:
      sql: "select * from orders where order_id = ?"
      params: ["${EXEC.INPUT.orderId}"]
  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
        waitMs=5000
      )}
```

`db: orders` and `mq.payment...` are logical capability references, not physical endpoint names. Avoid environment-specific Action IDs or branches such as `orders_sit` or `if environment == UAT` solely to choose infrastructure.

## CLI and CI usage

The package includes `templates/PAYMENT_INVOKE/debug.yaml`, so the documented Template debug command is runnable without creating an additional sidecar. The DB/MQ environment variables shown above must be supplied by the local shell or CI secret store before loading either config.

```sh
# SIT (uses the declared default, but explicit selection is clearer in CI)
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env SIT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT

# UAT
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env UAT
```

CI should validate and run each environment explicitly:

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
```

For local developer SIT, keep credentials in local `${ENV:NAME}` variables. For shared SIT/UAT, run the same package with an explicit `--env` in CI. For PREPROD or production-like validation, inject credentials from CI secret storage and keep topology in the selected descriptor. `run`, `validate`, `debug`, and `load` all use the same selector and effective helper registry.

Migration from the 3.5.0 pattern is mechanical: keep the existing DB/MQ descriptor files and stable IDs, copy the common global settings into one `config/config.yaml`, move each environment's two descriptor lists under `environments.<NAME>`, and replace `--config config/environments/<name>.yaml` with `--config config/config.yaml --env <NAME>`. Template, Flow, and Action content does not change. Separate top-level configs remain preferable when non-resource behavior intentionally differs.
