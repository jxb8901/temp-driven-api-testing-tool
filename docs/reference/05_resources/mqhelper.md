### 7.4 MQHelper

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

#### Sending project-file Strings and abstract values

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

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. String + requestFormat is rejected. payload and file are mutually exclusive. file remains available for explicit raw file input; a project-file expression does not create a file or targetFiles.

#### Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. See [Appendix C](../appendices/migrations.md) for schema migration.

See [Actions and Typed Values](../14_actions.md) for the shared typed-result contract.

### Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../../../schemas/att-mqhelper-v1.2.schema.json).
