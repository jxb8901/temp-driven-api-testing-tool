# MQHelper

MQHelper is a logical IBM MQ resource with one or more physical instances. Current descriptors use att-mqhelper/v1.2. Connection settings, credentials, queue defaults and pool limits belong to the resource and are not exposed through META.

~~~yaml
schemaVersion: att-mqhelper/v1.2
id: payment
name: Payment MQ
description: Payment request/reply queues
defaults:
  connection:
    queueManager: QM1
    host: mq.example.internal
    port: 1414
    channel: APP.SVRCONN
  message:
    requestQueue: PAYMENT.REQUEST
    replyQueue: PAYMENT.REPLY
  requestReply:
    waitMs: 5000
    responseFormat: xml
    maxResponseBytes: 10485760
  pool:
    maxSize: 20
instances:
  - id: primary
evidence:
  payload: none
  output:
    format: text
    maxChars: 10000
~~~

A Tool Action calls mq.<id>.send, mq.<id>.receive or mq.<id>.request as its primary operation. MQ reply bytes are decoded using received CCSID metadata when available, then parsed by responseFormat (text/json/yaml/xml). The parsed typed value is output.result. responseFormat owns ingress parsing; Log.format and evidence.output.format only control presentation.

## Sending project-file strings and abstract values

A project-file expression produces a String and can be passed directly as payload:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT encodes the exact file text using the configured MQ charset/CCSID. It does not parse and reserialize the String. Do not supply requestFormat for a project-file String; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. String + requestFormat is rejected. MQ send/request calls do not accept local file paths; `file` is an unknown argument. A project-file expression does not create a file or targetFiles.

## Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. See [Appendix C](../appendices/migrations.md) for schema migration.

MQ replies are capped at 10 MiB. The IBM MQ adapter configures the message receive limit before reading and reports `MQ_RESPONSE_TOO_LARGE` when a reply exceeds it. This is a transport-success size rejection and remains distinct from a missing reply or connection failure.

See [Actions and Typed Values](../actions.md) for the shared typed-result contract.

## Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing; `maxResponseBytes` defaults to 10485760 and accepts 1–1073741824 |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../../../schemas/att-mqhelper-v1.2.schema.json).

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

### Request/reply Timeout and replay policy

A correlated reply completes `mq.<id>.request(...)` with PASS. A successful PUT followed by correlated GET MQRC 2033 (`MQRC_NO_MSG_AVAILABLE`) is a standard TIMEOUT with `MQ_TIMEOUT` diagnostic, even if the outer Action deadline has time remaining. Native evidence retains `sent: true`, `replyReceived: false`, `completionCode: 2`, `reasonCode: 2033`, the reason name and effective `waitMs`. Other transport failures retain the stable MQ ERROR taxonomy; outer deadline and pool borrow timeout also follow the normal TIMEOUT path.

The canonical Action outcome is `output.status: TIMEOUT`; suite/report aggregate operational failure remains ERROR, with TIMEOUT and MQRC 2033 in the report message and Case log. Native metadata is available at `output.evidence.mq.invocations[0]` during the attempt and at `EXEC.ACTIONS.<actionId>.output.evidence.mq.invocations[0]` afterwards.

Without `retry.when`, `retryOn: [TIMEOUT]` can PUT the whole request again. For a side-effecting request, add a Boolean gate that excludes the already-sent/no-reply case:

~~~yaml
invokePayment:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.INPUT.requestText})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    intervalMs: 1000
    retryOn: [TIMEOUT]
    when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

The optional `?` path evaluates to null when another timeout has no MQ reason code. The condition permits ordinary timeout retry, but reasonCode 2033 suppresses a second PUT without changing the TIMEOUT outcome. ATT does not infer idempotency or deduplicate messages. Use `send` followed by correlated `receive` when repeated reply polling is required.

Standalone `receive` explicitly retains its non-error polling contract: MQRC 2033 returns PASS with `received: false` when the outer deadline has not expired, including a bounded wait that finds no message. An expired outer deadline is TIMEOUT. This operation-aware contract supersedes the earlier guidance that avoided request/2033 TIMEOUT to prevent replay. See [common retry semantics](../actions.md).
