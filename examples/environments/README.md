# ATT 3.5.x multi-environment configuration examples

ATT 3.5.x selects an environment by selecting a complete global configuration with `--config`. The selected configuration owns the physical DB/MQ descriptors; Templates, Flows, and Actions keep stable logical helper IDs.

> Actions reference stable logical resources; environment differences belong to the configuration/resource layer.

This is the supported 3.5.x pattern. It does not add an `--env` selector, config inheritance, or overlay semantics. Those future capabilities are tracked in issue #36.

## Package layout

```text
config/
├── environments/{sit,uat}.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

All paths are package-relative. Keep any existing package `tools`, `toolGroups`, templates, testcase roots, and report settings identical in both selected configs.

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

DBHelper resolves complete `${ENV:NAME}` values in `connection.url`, `username`, `password`, and string-valued `connection.properties`. MQHelper 3.5.x resolves `${ENV:NAME}` only for username/password; host, queue manager, channel, and numeric port are normally literal values in the selected descriptor. Keep credentials in the process environment or CI secret store, not in committed YAML. The PostgreSQL driver and optional IBM MQ client jar must be supplied by the package as usual.

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
# SIT
./att.sh validate --config config/environments/sit.yaml --package
./att.sh run --config config/environments/sit.yaml --all
./att.sh debug template PAYMENT_INVOKE --config config/environments/sit.yaml
./att.sh load examples/load/closed-smoke.yaml --config config/environments/sit.yaml

# UAT
./att.sh validate --config config/environments/uat.yaml --package
./att.sh run --config config/environments/uat.yaml --all
./att.sh debug template PAYMENT_INVOKE --config config/environments/uat.yaml
./att.sh load examples/load/closed-smoke.yaml --config config/environments/uat.yaml
```

CI should validate and run each environment explicitly:

```sh
./att.sh validate --config config/environments/sit.yaml --package
./att.sh run --config config/environments/sit.yaml --all
./att.sh validate --config config/environments/uat.yaml --package
./att.sh run --config config/environments/uat.yaml --all
```

The same guidance applies to local/SIT, shared SIT/UAT, and PREPROD or production-like validation. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. The current supported selector is `--config config/environments/<env>.yaml`; issue #36 may introduce a future `--env` profile mechanism, but no such runtime option exists in 3.5.x.
