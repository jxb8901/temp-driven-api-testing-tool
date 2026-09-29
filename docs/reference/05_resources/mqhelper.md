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

`connection.transport` is configurable in v1.1 using `MQSeries Client`, `MQSeries`, or `MQSeries Bindings`; v1.1 instance values override `defaults.connection.transport`. The historical v1.0 schema does not accept this field, but its runtime default remains `MQSeries Client`. ATT passes the selected IBM MQ client constant as-is with `MQConstants.TRANSPORT_PROPERTY`; if the installed client lacks that transport constant, ATT reports the required constant and client dependency instead of substituting a numeric value.

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

MQ reply bytes are decoded using the received CCSID when available, then parsed as the effective `responseFormat` (`text|json|yaml|xml`). v1.1 may configure `requestReply.responseFormat`; v1.0 does not accept this descriptor field and keeps the runtime default `text`. For `request`, precedence is call `responseFormat` > selected instance's inherited v1.1 `requestReply.responseFormat` > `text`; `receive` uses the same precedence. `send` rejects `responseFormat`. Action `result.format` remains serialization-only and never changes the resource response parser. A pathless `result` creates no file; `path: console` writes the selected serialization to the Case log and creates no `output.targetFiles` entry. No implicit `.reply.bin` is created. Overwrite/path safety follow the common Action rules.

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

#### Choosing an API

All calls use the stable logical helper ID and named arguments. `file` is read as exact bytes from the Case output directory or package. The APIs differ in whether they wait for a reply and who owns correlation:

| API | Primary use | Default queue(s) | Correlation |
|---|---|---|---|
| `send` | One-way producer; does not wait for a reply | `message.requestQueue` | None |
| `receive` | Standalone consumer | `message.replyQueue` | Optional caller-supplied `correlationId`; omitted means no filter |
| `request` | Request/reply in one operation | `message.requestQueue` + `message.replyQueue` | Automatic: reply `MQMD.CorrelId` matches request `MsgId` |

For v1.1, ATT first selects or resolves the physical instance, then uses that instance's inherited effective queues. An explicit operation argument overrides that selected instance's setting. No other instance's queue is borrowed. An Action `timeoutMs` is the outer execution deadline; call-level `waitMs` overrides `requestReply.waitMs` but is capped by the remaining Action deadline. Tool Action retry is explicit for each API; ATT does not infer idempotency.

#### `mq.<helper>.send(...)`

Use `send` to put a file-backed message on a request/producer queue when the caller does not need a synchronous reply. The call completes after the PUT succeeds; it does not open or wait on a reply queue.

| Argument | Type | Required? | Default / precedence | Description |
|---|---|---:|---|---|
| `file` | string/path | Yes | None | Payload file, read byte-for-byte from the Case output directory or package. |
| `queue` | string | No when configured | Call `queue` > selected instance's `message.requestQueue` > validation error | Destination queue. |
| `instance` | string | No | v1.0/single instance uses that instance; multi-instance uses configured selection strategy | Pin a v1.1 call to one physical instance. |

The helper must provide an effective `message.requestQueue` if `queue` is omitted. Missing both is an actionable argument/configuration error before connecting. Result/evidence records `sent`, the resolved `queue`, byte count, `messageId`, and `correlationId`; send has no business reply, leaves `output.result` null/absent, and does not accept Action result persistence. `timeoutMs` bounds the Action; if retry is explicitly enabled, a repeated PUT may enqueue a duplicate, so the package author owns replay safety.

Minimal send uses the helper default:

~~~yaml
message: {requestQueue: PAYMENT.REQUEST}
~~~

~~~text
mq.payment.send(file='request.xml')
~~~

An explicit queue overrides the configured default:

~~~text
mq.payment.send(queue='PAYMENT.REQUEST.ALT', file='request.xml')
~~~

The payload may be generated earlier in the same Action sequence; `targetFiles[0]` is the rendered file path, not a hard-coded package filename:

~~~yaml
- id: renderRequest
  type: render
  payload: payload/request.xml
  result: {format: text, path: generated/request.xml}
- id: sendRendered
  type: tool
  call: "#{mq.payment.send(file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
  assert: "${output.sent} == true"
~~~

For a v1.1 group, selecting an instance happens before that instance's queue fallback. In this descriptor fragment, the explicit `payment-b` call uses its own `PAYMENT.REQUEST.B`, not the group default:

~~~yaml
defaults:
  message: {requestQueue: PAYMENT.REQUEST}
instances:
  - id: payment-a
  - id: payment-b
    message: {requestQueue: PAYMENT.REQUEST.B}
selection: {strategy: roundRobin}
~~~

This call pins to `payment-b`; an explicit `queue` would override that instance value:

~~~text
mq.payment.send(file='request.xml', instance='payment-b')
~~~

#### `mq.<helper>.receive(...)`

Use `receive` as a standalone consumer for a reply or independently produced message. It performs one MQGET using the effective wait and returns whether a message arrived.

| Argument | Type | Required? | Default / precedence | Description |
|---|---|---:|---|---|
| `queue` | string | No when configured | Call `queue` > selected instance's `message.replyQueue` > validation error | Input/reply queue. |
| `correlationId` | hex string | No | No default; omitted means no `MQMD.CorrelId` filter | When supplied, match this value against the incoming message's `MQMD.CorrelId`. |
| `waitMs` | integer | No | Call value > `requestReply.waitMs`; capped by Action `timeoutMs` | Maximum MQGET wait in milliseconds. |
| `instance` | string | No | v1.0/single instance uses that instance; multi-instance uses configured selection strategy | Pin a v1.1 call to one physical instance. |

The helper must provide `message.replyQueue` when `queue` is omitted. `output`/evidence records the resolved `queue`, effective `waitMs`, `received`, message identifiers and safe MQ reason/completion metadata. A received reply is decoded using its CCSID into the native `String` at `output.result`. At the end of the wait, MQRC 2033 (`MQRC_NO_MSG_AVAILABLE`) is a completed no-message result—not an ATT/runtime error: `received` is false and `output.result` is null. An expired Action deadline instead reports `MQ_TIMEOUT`. Explicit Action retries may consume a later message, so retry is not automatic.

Minimal receive uses the helper's reply queue; the wait can be tuned per call:

~~~yaml
message: {replyQueue: PAYMENT.REPLY}
~~~

~~~text
mq.payment.receive()
mq.payment.receive(waitMs=5000)
~~~

An explicit input queue overrides that default:

~~~text
mq.payment.receive(queue='PAYMENT.REPLY.ALT', waitMs=5000)
~~~

For v1.1, `instance` selects first and the selected instance's effective `message.replyQueue` is then used when `queue` is omitted:

~~~text
mq.payment.receive(waitMs=5000, instance='payment-b')
~~~

To wait for a particular request's reply, supply its correlation ID:

~~~text
mq.payment.receive(correlationId='414d5120...', waitMs=5000)
~~~

~~~text
correlationId supplied  -> match incoming MQMD.CorrelId
correlationId omitted   -> receive without correlation filtering
~~~

Uncorrelated receives are appropriate for independently addressed event, notification, or batch-result queues, and for a simple consumer that intentionally accepts the next message:

~~~text
mq.events.receive()                 # event queue
mq.notifications.receive()          # notification queue
mq.batchResults.receive(waitMs=1000) # bounded batch-result poll
mq.validation.receive(queue='QA.CONSUMER')
~~~

Warning: a bare `receive()` on a shared reply queue can consume another caller's reply. Prefer `request(...)` for request/reply, or use `receive(correlationId=...)` when a standalone consumer must select one caller's reply.

#### `mq.<helper>.request(...)`

Use `request` when one operation must send a request and wait for its correlated reply. ATT owns the request/reply correlation lifecycle; callers do not pass `correlationId`.

| Argument | Type | Required? | Default / precedence | Description |
|---|---|---:|---|---|
| `file` | string/path | Yes | None | Request payload file, read byte-for-byte. |
| `requestQueue` | string | No when configured | Call value > selected instance's `message.requestQueue` > validation error | Request output queue. |
| `replyQueue` | string | No when configured | Call value > selected instance's `message.replyQueue` > validation error | Reply input queue. |
| `waitMs` | integer | No | Call value > `requestReply.waitMs`; capped by Action `timeoutMs` | Maximum reply wait in milliseconds. |
| `instance` | string | No | v1.0/single instance uses that instance; multi-instance uses configured selection strategy | Pin both PUT and GET to one v1.1 physical instance. |

Both effective queues must exist before connecting. Explicit queue arguments override the selected instance's settings. The normal lifecycle is:

~~~text
PUT request
  -> obtain request MsgId
  -> GET from the effective reply queue
  -> match reply MQMD.CorrelId against that request MsgId
~~~

Result/evidence records the resolved queues, wait, request/reply identifiers, `replyReceived`, and safe MQ completion/reason information. For a received reply, `output.messageId == output.replyCorrelationId`; `output.result` is the CCSID-decoded native String. If no reply arrives before the effective wait, MQRC 2033 is a completed request with no reply (`replyReceived: false`, null result and reason 2033 / `MQRC_NO_MSG_AVAILABLE`), not an ATT/runtime error. If the Action deadline expires, ATT reports `MQ_TIMEOUT` instead. Action retry is opt-in; a retry sends another PUT with a new MsgId and can repeat the business operation.

Minimal request uses both helper defaults:

~~~yaml
message: {requestQueue: PAYMENT.REQUEST, replyQueue: PAYMENT.REPLY}
~~~

~~~text
mq.payment.request(file='request.xml')
~~~

Both queues can be overridden together:

~~~text
mq.payment.request(requestQueue='PAYMENT.REQUEST.ALT', replyQueue='PAYMENT.REPLY.ALT', file='request.xml')
~~~

A call-level wait overrides the helper's `requestReply.waitMs` (but not the Action deadline):

~~~text
mq.payment.request(file='request.xml', waitMs=10000)
~~~

To pin the full request/reply cycle to a v1.1 instance:

~~~text
mq.payment.request(file='request.xml', instance='payment-b')
~~~

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

#### Payload paths in Load mode

`file` accepts an absolute path only when its resolved regular file is inside the ATT package root. This is useful for a Flow or Template that uses a checked-in request payload, for example:

~~~text
#{mq.toeaimq.request(file='/fpp/att/templates/flows/mqtest/BOC060032.xml')}
~~~

Load iteration workspaces are intentionally lazy. An absolute package payload is validated against the package root and therefore does not require the current `output/load/<runId>/iterations/<iterationId>/` directory to exist. ATT does not create one empty iteration directory per successful iteration merely to validate this file. The payload is validated before MQ connect/open/put/get, so a failure at this point is a local path-safety error, not an IBM MQ transport, queue, or response-parse error.

Relative paths keep the Case-output contract: ATT resolves them below the current Case output directory, rejects `..` traversal, rejects payload symlinks and symlink escapes, and requires a safe regular file. Absolute files outside the package and genuinely missing files are rejected; the diagnostic names the payload problem rather than exposing an unrelated lazy-workspace `NoSuchFileException`.

For troubleshooting, first check whether the `file` value is absolute or relative, then check the resolved file and the relevant root. Do not pre-create every Load workspace as a workaround. Use `evidence: {mode: failures}` or `metrics` according to the evidence guide when the test should avoid retaining successful iteration artifacts.
