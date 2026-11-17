# ATT environment profile examples

ATT uses `att-config/v2.14` profiles to select shared Helper and Testdata descriptor lists while keeping Template, Flow, Action and Tool IDs stable across environments. Business Tools are owned by their Package under `packages/<id>/tools/`; they are not registered through global `tools` or `toolGroups` entries. The active DBHelper, MQHelper, HTTPHelper, SSHHelper, Package Tool Group and Testdata schemas are listed in [the active schema matrix](../../docs/reference/appendices/schema-matrix.md). Older schemas are historical references under `schemas/history/`, not runtime compatibility contracts.

## Shared configuration and profile bindings

The package keeps common behavior in one `config/config.yaml`. Each profile may replace configured resource lists (`dbhelpers`, `mqhelpers`, `httphelpers`, `sshhelpers`) as a whole; omitted lists inherit the common list. A profile's `testdata` list selects the reusable records available in that environment.

```yaml
# config/config.yaml
schemaVersion: att-config/v2.14
environment: SIT
templates: {root: templates}
testcase: {root: testcase}
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    testdata: [testdata/accounts.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Keep the package's normal `report`, `run`, and `execution` settings in the common config. Only move environment-specific Helper and Testdata descriptor paths into profiles. HTTP and SSH bindings use the same `httphelpers` and `sshhelpers` list pattern. Testdata descriptors contain package-local records, not environment secrets. For example, `packages/default/tools/orders/tool-group.yaml` owns the Package's order Tools; only the shared `orders` DBHelper binding changes by environment.

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
schemaVersion: att-mqhelper/v1.3
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

Actions use the same logical IDs in every profile. A file-content expression returns an exact UTF-8 `String`; HTTP/MQ accepts it directly without a `requestFormat` or temporary result file:

```yaml
actions:
  prepareRequest:
    type: assign
    name: requestText
    expression: "&{templates/payment/request.xml}"
  sendPayment:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        payload=${EXEC.VARS.requestText},
        responseFormat='xml'
      )}
```

For an abstract Map/List request, supply `requestFormat` explicitly. Do not combine `requestFormat` with a String. HTTP/MQ `responseFormat` parses received bytes; `evidence.output.format` controls only an optional human-readable snapshot. See [Actions and Typed Values](../../docs/reference/actions.md), [DBHelper](../../docs/reference/resources/dbhelper.md), [MQHelper](../../docs/reference/resources/mqhelper.md), and [HTTPHelper](../../docs/reference/resources/httphelper.md).

## Validate each profile

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh validate --config config/config.yaml --env UAT --package
```

`run`, `debug`, and `load` use the same environment selector. Keep separate top-level config files only when roots, reporting policy, Tool topology, or other package behavior intentionally differs.
