# ATT 3.6.0 environment profile examples

ATT 3.6.0 uses `att-config/v2.10` profiles to select resource descriptor lists while keeping Template, Flow, Action and Tool IDs stable across environments. The active DBHelper, MQHelper, HTTPHelper, SSHHelper and Tool Group schemas are listed in [the configuration reference](../../docs/reference/09_configuration.md). Older schemas are historical references under `schemas/history/`, not runtime compatibility contracts.

## Shared configuration and profile bindings

The package keeps common behavior in one `config/config.yaml`. Each profile may replace configured resource lists (`dbhelpers`, `mqhelpers`, `httphelpers`, `sshhelpers`) as a whole; omitted lists inherit the common list.

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
environment: SIT
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/orders.yaml
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Keep the package's normal `report`, `run`, `execution`, global `tools` and `toolGroups` entries in the common config. Only move environment-specific resource descriptor paths into profiles. HTTP and SSH bindings use the same `httphelpers` and `sshhelpers` list pattern.

## Stable logical helper IDs

The SIT and UAT descriptors below both use `id: orders`; only connection details change:

```yaml
# config/dbhelpers/sit/orders.yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: SIT order database
connection:
  url: jdbc:postgresql://sit-db.example.internal:5432/orders
  username: "${ENV:ORDERS_DB_USERNAME}"
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
statement: {timeoutSeconds: 30}
transaction: {scope: case, onEnd: rollback}
result: {maxRows: 1000, maxCellBytes: 1048576, maxBytes: 10485760}
evidence: {sql: full, parameters: masked}
```

```yaml
# config/mqhelpers/sit/payment.yaml
schemaVersion: att-mqhelper/v1.2
id: payment
name: Payment MQ
description: SIT payment queues
defaults:
  message: {ccsid: 1208, format: MQSTR, persistence: asQueue}
  requestReply: {waitMs: 10000, responseFormat: xml}
  pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
instances:
  - id: sit
    connection:
      queueManager: SITQM1
      host: sit-mq.example.internal
      port: 1414
      channel: SIT.APP.SVRCONN
      username: "${ENV:PAYMENT_MQ_USERNAME}"
      password: "${ENV:PAYMENT_MQ_PASSWORD}"
evidence:
  payload: metadata
  output: {format: text, maxChars: 10000}
```

The UAT descriptors keep the same IDs and replace only physical endpoint settings. DBHelper and MQHelper credentials stay in `${ENV:NAME}` references and are not published to META, reports, or diagnostics. Supply the values through the local process environment or CI secret store.

## Typed Action flow

Actions use the same logical IDs in every profile. Render returns a `DocumentValue`; HTTP/MQ accepts it directly without a `requestFormat` or temporary result file:

```yaml
actions:
  renderRequest:
    type: render
    payload: payment/request.xml
    templateFormat: xml
  sendPayment:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        payload=${EXEC.ACTIONS.renderRequest.output.result},
        responseFormat='xml'
      )}
```

For an abstract Map/List request, supply `requestFormat` explicitly. Do not combine `requestFormat` with a `DocumentValue`. HTTP/MQ `responseFormat` parses received bytes; `evidence.output.format` controls only an optional human-readable snapshot. See [Actions and Typed Values](../../docs/reference/14_actions.md), [DBHelper](../../docs/reference/05_resources/dbhelper.md), [MQHelper](../../docs/reference/05_resources/mqhelper.md), and [HTTPHelper](../../docs/reference/05_resources/httphelper.md).

## Validate each profile

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh validate --config config/config.yaml --env UAT --package
```

`run`, `debug`, and `load` use the same environment selector. Keep separate top-level config files only when roots, reporting policy, Tool topology, or other package behavior intentionally differs.
