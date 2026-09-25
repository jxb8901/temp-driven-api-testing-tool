### 5.3 MQHelper

MQHelper is a first-class IBM MQ resource. Each descriptor uses `schemaVersion: att-mqhelper/v1.0`, a stable logical `id`, connection topology and optional credentials. Global `mqhelpers` references descriptor files; environment profiles may select a different descriptor for the same logical ID.

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

username and password map to MQConstants.USER_ID_PROPERTY and PASSWORD_PROPERTY before constructing MQQueueManager. Environment credentials are secret and never enter evidence, logs, reports, or generated docs.

message.charset is an integer IBM MQ CCSID for MQMessage.characterSet, not a Java charset name. ccsid remains a compatibility alias and must equal charset when both are present. encoding maps to MQMessage.encoding. Empty message.format is valid and remains empty; named values MQSTR, MQFMT_STRING, MQHRF2, MQFMT_NONE, and NONE remain supported. persistence accepts asQueue/0, persistent/1, and notPersistent/nonPersistent/2. expiry -1 means MQEI_UNLIMITED; positive values use IBM MQ tenths-of-a-second units, not milliseconds.

requestQueue and replyQueue are optional request defaults. Queue precedence is call argument > message default > validation error. send(queue=...) and receive(queue=...) do not use these defaults. Request payload files stay byte-preserving through MQMessage.write(byte[]). Request output uses bind-not-fixed; reply input uses shared input. ATT sets MQPMO_NEW_MSG_ID and correlates reply correlationId to the generated request MsgId with MQGMO_WAIT, MQMO_MATCH_CORREL_ID, and waitInterval from waitMs. ATT uses NO_SYNCPOINT for put/get and does not call legacy commit().

#### Common saveAs

MQ receive/request use the common Action saveAs object; no MQ-specific resultType/replyType exists.

~~~yaml
saveAs:
  format: raw
  path: response.bin
  overwrite: false
~~~

format defaults to raw and path is optional. raw gives the original byte[], text gives String, and json/yaml/xml give existing ATT typed values. No saveAs or saveAs: {} keeps the result in memory and creates no file. No .reply.bin is created unless path is explicit. raw plus a path writes exact bytes; overwrite/path safety follow the common Action rules.

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

output.result is the business payload; MQ metadata is directly under output. send publishes sent, queue, bytes, messageId, correlationId, and leaves result null/absent. receive/request publish received or replyReceived, queue names, waitMs, messageId, replyMessageId, replyCorrelationId, byte counts, completion/reason fields, and put the parsed payload only in result. A normal request satisfies output.messageId == output.replyCorrelationId. MQRC 2033 leaves result null and publishes received/replyReceived false plus reasonCode 2033 and MQRC_NO_MSG_AVAILABLE.

Public MsgId/CorrelId values are lowercase hex, two characters per byte, no separators, with leading zeroes; a 24-byte ID is 48 characters. Raw runtime values remain byte[]. Logs/reports display raw bytes using new String(rawBytes, Charset.defaultCharset()) semantics, not hex and not an implicit file. Validation rejects unknown fields, conflicting charset/ccsid, invalid encoding/expiry/queues, missing effective request/reply queues, unsupported saveAs formats, and unsafe paths.
