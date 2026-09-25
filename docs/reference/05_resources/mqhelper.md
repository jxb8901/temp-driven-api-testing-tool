### 5.3 MQHelper

MQHelper is a first-class IBM MQ resource. Each descriptor uses `schemaVersion: att-mqhelper/v1.0` or `att-mqhelper/v1.1`, with a stable logical `id`, connection topology and optional credentials. Global `mqhelpers` references descriptor files; environment profiles may select a different descriptor for the same logical ID. v1.0 remains the compatible single-instance form; v1.1 adds logical groups of physical instances.

Primary calls are:

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payloads are file-based so request bytes do not have to be duplicated into Context/evidence. `request` combines send and correlated receive behavior. Correlation identifiers, queue/operation metadata, timing and diagnostic information are evidence; credentials and payload bytes are not copied into evidence.

Timeout behavior is operation-specific and remains distinct from assertion failure. MQ connection/pool lifecycle is framework-owned resource state, especially in Load mode; it is not exposed as a public `EXEC.MQ` tree.

ATT's default build does not require IBM MQ client classes. Runtime MQ use requires the IBM MQ client jar/profile documented by the package/release instructions. MQ operations feed the same Action result/evidence envelope as Tool and DB operations.


#### Issue #59 configuration and public contract

A complete descriptor can contain connection, message, requestReply, evidence, and pool fields:

~~~yaml
schemaVersion: att-mqhelper/v1.0
id: ordersMq
name: Orders MQ
description: IBM MQ connection used by SIT/UAT order tests
connection:
  queueManager: QM1
  host: 10.12.13.14
  port: 1414
  channel: CHANNEL
  username: ${ENV:MQ_USERNAME}
  password: ${ENV:MQ_PASSWORD}
message:
  charset: 1208
  encoding: 273
  format: ""
  persistence: asQueue
  expiry: -1
  requestQueue: requestQ
  replyQueue: replyQ
requestReply: {waitMs: 40000}
evidence: {payload: metadata}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
~~~

username and password map to MQConstants.USER_ID_PROPERTY and PASSWORD_PROPERTY before constructing MQQueueManager. `evidence.payload` accepts `metadata` or `none`; `metadata` keeps only the policy marker in evidence, while `none` omits it. Environment credentials are secret and never enter evidence, logs, reports, or generated docs.

message.charset is an integer IBM MQ CCSID for MQMessage.characterSet, not a Java charset name. ccsid remains a compatibility alias and must equal charset when both are present. encoding maps to MQMessage.encoding. Empty message.format is valid and remains empty; named values MQSTR, MQFMT_STRING, MQHRF2, MQFMT_NONE, and NONE remain supported. persistence accepts asQueue/0, persistent/1, and notPersistent/nonPersistent/2. expiry -1 means MQEI_UNLIMITED; positive values use IBM MQ tenths-of-a-second units, not milliseconds.

requestQueue and replyQueue are optional request defaults. Queue precedence is call argument > message default > validation error. send(queue=...) and receive(queue=...) do not use these defaults. Request payload files stay byte-preserving through MQMessage.write(byte[]). Only the request output queue uses `MQOO_BIND_NOT_FIXED`; send output uses ordinary `MQOO_OUTPUT`, and reply input uses shared input. ATT sets MQPMO_NEW_MSG_ID and correlates reply correlationId to the generated request MsgId with MQGMO_WAIT, MQMO_MATCH_CORREL_ID, and waitInterval from waitMs. ATT uses NO_SYNCPOINT for put/get and does not call legacy commit(). `encoding` is validated as a legal IBM MQ integer/decimal/float encoding combination before the descriptor is accepted.

#### Common saveAs

MQ receive/request use the common Action saveAs object; no MQ-specific resultType/replyType exists.

~~~yaml
saveAs:
  format: raw
  path: response.bin
  overwrite: false
~~~

format defaults to raw and path is optional. raw gives the original byte[], text gives String, and json/yaml/xml give existing ATT typed values. No saveAs or saveAs: {} keeps the result in memory and creates no file. `path: console` writes the selected representation to the Case log and creates no `output.targetFiles` entry or file. No .reply.bin is created unless a real path is explicit. raw plus a real path writes exact bytes; overwrite/path safety follow the common Action rules.

~~~yaml
- id: requestXml
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  saveAs: {format: xml}
  assert: "${output.result.Response.Status} == 'SUCCESS'"

- id: requestXmlSaved
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  saveAs: {format: xml, path: responses/payment.xml, overwrite: false}

- id: receiveReply
  type: tool
  call: "#{mq.ordersMq.receive(queue='replyQ', correlationId=${EXEC.ACTIONS.sendRequest.output.messageId}, waitMs=40000)}"
  saveAs: {format: json}
~~~

#### Output and validation

output.result is the business payload; MQ metadata is directly under output. Every operation publishes `mqHelper`, selected physical `instance`, `queueManager`, and `selectionStrategy`; v1.0 and single-instance helpers report `selectionStrategy: single`. send publishes sent, queue, bytes, messageId, correlationId, and leaves result null/absent. `send` does not produce a business payload and rejects `saveAs`; `receive` and `request` support it. receive/request publish received or replyReceived, queue names, effective waitMs, messageId, replyMessageId, replyCorrelationId, byte counts, reply CCSID/encoding/format when supplied by MQ, completion/reason fields, and put the parsed payload only in result. A normal request satisfies output.messageId == output.replyCorrelationId. MQRC 2033 leaves result null and publishes received/replyReceived false plus reasonCode 2033, MQRC_NO_MSG_AVAILABLE, and the effective waitMs.

Public MsgId/CorrelId values are lowercase hex, two characters per byte, no separators, with leading zeroes; a 24-byte ID is 48 characters. Raw runtime values remain byte[]. Typed reply decoding uses the received MQMessage.characterSet/CCSID when available, with an explicit IBM MQ CCSID-to-Java charset resolver and the configured charset as fallback; unsupported CCSIDs fail clearly. Logs/reports display raw bytes using new String(rawBytes, Charset.defaultCharset()) semantics, not hex and not an implicit file. Validation rejects unknown fields, conflicting charset/ccsid, invalid encoding/expiry/queues, missing effective request/reply queues, unsupported saveAs formats, and unsafe paths.

#### Issue #60 v1.1 logical groups and physical instances

`att-mqhelper/v1.1` keeps one public logical helper id while declaring one or more physical connection instances. A v1.0 descriptor remains valid without changes. The v1.1 descriptor has group defaults and per-instance overrides for `connection`, `message`, `requestReply`, and `pool`:

~~~yaml
schemaVersion: att-mqhelper/v1.1
id: payment
name: Payment MQ
description: Payment MQ endpoints
defaults:
  connection:
    queueManager: QM1
    host: mq.default.example
    port: 1414
    channel: APP.SVRCONN
    username: ${ENV:MQ_USERNAME}
    password: ${ENV:MQ_PASSWORD}
  message: {charset: 1208, requestQueue: PAYMENT.REQUEST, replyQueue: PAYMENT.REPLY}
  requestReply: {waitMs: 40000}
  pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
instances:
  - id: payment-a
    connection: {host: mq-a.example}
  - id: payment-b
    connection: {host: mq-b.example}
    message: {replyQueue: PAYMENT.REPLY.B}
selection: {strategy: roundRobin}
evidence: {payload: none}
~~~

Each physical instance is materialized into an immutable effective configuration before an invocation. For every section the precedence is invocation override, instance override, group default, runtime default, then validation error. Effective `queueManager`, `host`, `port`, and `channel` are required; username/password are optional and are never emitted as evidence.

The public call remains logical:

~~~text
#{mq.payment.send(queue='PAYMENT.REQUEST', file='request.bin')}
#{mq.payment.request(file='request.bin', instance='payment-b')}
#{mq.payment.receive(queue='PAYMENT.REPLY', instance='payment-a')}
~~~

A single-instance v1.1 group uses that instance directly. A group with multiple instances must declare `selection.strategy: random` or `roundRobin`; selection occurs once per MQ invocation, before connecting, so a `request` PUT and correlated GET always use the same physical instance. An explicit `instance` call argument selects that physical id and is rejected when it is unknown. Each physical instance has an isolated pool; pool identity is logical id plus physical id.

Output and evidence retain the logical helper id and expose the selected physical instance. Evidence also records the applicable strategy, queue manager, operation, queue names, MsgId/CorrelId, and safe connection metadata. With `evidence.payload: none`, the payload policy marker is omitted; credentials and payload bytes are never included. Validation rejects duplicate physical ids, unknown inherited fields, missing effective connection fields, invalid strategies or overrides, and invalid effective message/requestReply/pool values.

`output.selectionStrategy` identifies the configured group policy (`single`, `random`, or `roundRobin`), not the selection source for an individual invocation. When a call explicitly supplies `instance`, that policy value remains unchanged and `output.instance` identifies the physical instance actually selected.
