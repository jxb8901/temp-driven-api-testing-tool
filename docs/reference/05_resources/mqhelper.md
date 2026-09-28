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

An Action `timeoutMs` takes precedence over the helper's default `requestReply.waitMs` and caps receive/request waits to the remaining Action deadline, recalculated immediately before each GET. A call-level `waitMs` takes precedence over the helper default but cannot extend that deadline. IBM MQ client calls are synchronous and cannot be forcibly interrupted by ATT; if connect/open/put/get returns after the deadline, ATT immediately records `MQ_TIMEOUT` and applies the configured retry policy. Tool Action retry is author-controlled for every MQ operation (`send`, `receive`, and `request`): ATT does not infer idempotency or suppress potentially mutating replays. Retrying `send` may enqueue duplicate messages; retrying `request` performs another PUT with a new MsgId and a new correlation cycle, and may replay the business operation. The package author owns replay/duplicate safety. Per-attempt timing and retry outcomes remain in Action evidence, including each request attempt's message/correlation IDs.

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

`message.requestQueue` and `message.replyQueue` are optional defaults for all matching operations: send/request use `requestQueue`; receive/request use `replyQueue`. For v1.1, ATT selects or resolves the requested physical instance first, materializes that instance's inherited `message` settings, then applies the call argument as the final override. Thus the effective order is explicit call argument > selected instance override > group default > runtime default (if defined) > point-of-use validation error. ATT never borrows a queue default from a different physical instance. If the effective queue is still absent, the call fails before connecting. Request payload files stay byte-preserving through MQMessage.write(byte[]). Only the request output queue uses `MQOO_BIND_NOT_FIXED`; send output uses ordinary `MQOO_OUTPUT`, and reply input uses shared input. ATT sets MQPMO_NEW_MSG_ID and correlates reply correlationId to the generated request MsgId with MQGMO_WAIT, MQMO_MATCH_CORREL_ID, and waitInterval from waitMs. ATT uses NO_SYNCPOINT for put/get and does not call legacy commit(). `encoding` is validated as a legal IBM MQ integer/decimal/float encoding combination before the descriptor is accepted.

#### Common Action result

MQ receive/request use the common Action `result` object; no MQ-specific resultType/replyType exists.

~~~yaml
result:
  format: text
  path: response.txt
  overwrite: false
~~~

MQ reply bytes are decoded to a native `String` using the received CCSID when available; Action `result.format` does not parse or replace that value. The common formats are `text|json|yaml|xml` and only choose how the typed value is serialized to a file or Case log. There is no public `raw` Action result. A pathless `result` creates no file; `path: console` writes the selected serialization to the Case log and creates no `output.targetFiles` entry. No implicit `.reply.bin` is created. Overwrite/path safety follow the common Action rules.

~~~yaml
- id: requestXml
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  result: {format: text}
  assert: "${output.replyReceived} == true"

- id: requestXmlSaved
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  result: {format: text, path: responses/payment.txt, overwrite: false}

- id: receiveReply
  type: tool
  call: "#{mq.ordersMq.receive(queue='replyQ', correlationId=${EXEC.ACTIONS.sendRequest.output.messageId}, waitMs=40000)}"
  result: {format: json}
~~~

#### Output and validation

`output.result` is the business payload (a charset-decoded String for MQ replies); MQ metadata is directly under `output`. Every operation publishes `mqHelper`, selected physical `instance`, `queueManager`, and `selectionStrategy`; v1.0 and single-instance helpers report `selectionStrategy: single`. send publishes sent, queue, bytes, messageId, correlationId, and leaves result null/absent. `send` does not produce a business payload and rejects Action `result`; `receive` and `request` support it. receive/request publish received or replyReceived, queue names, effective waitMs, messageId, replyMessageId, replyCorrelationId, byte counts, reply CCSID/encoding/format when supplied by MQ, completion/reason fields, and place the decoded payload only in `output.result`. A normal request satisfies `output.messageId == output.replyCorrelationId`. MQRC 2033 leaves result null and publishes received/replyReceived false plus reasonCode 2033, MQRC_NO_MSG_AVAILABLE, and the effective waitMs.

Public MsgId/CorrelId values are lowercase hex, two characters per byte, no separators, with leading zeroes; a 24-byte ID is 48 characters. Transport payloads remain byte-preserving through the MQ client; the public typed reply value is a `String` decoded using the received MQMessage.characterSet/CCSID when available, with an explicit IBM MQ CCSID-to-Java charset resolver and configured charset as fallback. Unsupported CCSIDs fail clearly. Logs/reports serialize the typed result and never create an implicit file. Validation rejects unknown fields, conflicting charset/ccsid, invalid encoding/expiry/queues, unsupported result formats, and unsafe paths; a missing effective queue is rejected at the operation's point of use.

#### Send, receive, and request APIs

All calls use the stable logical helper ID and named arguments. For a v1.1 group, an optional `instance` selects a physical ID; otherwise ATT applies the configured `random` or `roundRobin` strategy before resolving queue defaults.

| Operation | Required arguments | Optional arguments | Queue source when omitted |
|---|---|---|---|
| `send` | `file` | `queue`, `instance` | `message.requestQueue` |
| `receive` | none | `queue`, `correlationId`, `waitMs`, `instance` | `message.replyQueue` |
| `request` | `file` | `requestQueue`, `replyQueue`, `waitMs`, `instance` | `message.requestQueue` and `message.replyQueue` |

`file` is read as exact bytes from the Case output directory or package. `queue` is the send/receive argument; request deliberately uses the distinct `requestQueue` and `replyQueue` names. Explicit non-null call arguments override the selected instance's effective message defaults. If a queue has neither a call argument nor a configured effective default, ATT returns a concise argument/configuration error before opening a connection. The default is evaluated only after the physical instance has been selected, so a request PUT and its correlated GET always use the same broker and that instance's queue settings.

```yaml
sendPayment:
  type: tool
  call: "#{mq.payment.send(file='request.bin')}"
  assert: "${output.sent} == true"

waitForPayment:
  type: tool
  call: "#{mq.payment.receive(correlationId=${EXEC.ACTIONS.sendPayment.output.messageId}, waitMs=30000)}"
  result: {format: text, path: replies/payment.txt}
  assert: "${output.received} == true"

requestPayment:
  type: tool
  call: "#{mq.payment.request(file='request.xml', waitMs=40000)}"
  result: {format: text}
  assert: "${output.replyReceived} == true"
```

For `receive` and `request`, call-level `waitMs` overrides `requestReply.waitMs`. An Action `timeoutMs` is the outer deadline and caps the effective wait to the remaining time; the receive wait is recalculated immediately before MQGET. `MQRC_NO_MSG_AVAILABLE` (2033) at the end of the permitted wait is a completed no-message outcome: the operation remains successful, `received`/`replyReceived` is false, and `output.result` is null. If the Action deadline has expired, the outcome is instead `MQ_TIMEOUT`.

Tool Action retry is explicit and applies to send, receive, and request. ATT does not infer idempotency: a retried send may enqueue duplicates, while retried request issues a new PUT/MsgId and can repeat the business operation. Each attempt keeps its own IDs and evidence. For request/reply, ATT sets the reply queue metadata, waits with `MQMO_MATCH_CORREL_ID` for the generated request MsgId, and publishes `output.messageId == output.replyCorrelationId`; do not manually substitute a different correlation value. For a standalone receive, use `correlationId` when a particular reply is required. In multi-instance mode, an explicit instance is pinned for that call; no retry or no-message path silently changes brokers.

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
