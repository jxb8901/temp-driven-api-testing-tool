### 5.3 MQHelper

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

#### Sending represented and abstract values

Render output is a DocumentValue and can be passed directly as payload:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

ATT encodes the exact rendered text using the configured MQ charset/CCSID. It does not parse and reserialize the document. Do not supply requestFormat for DocumentValue. The document format does not set MQMD.Format; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. DocumentValue + requestFormat and String + requestFormat are rejected. payload and file are mutually exclusive. file remains available for explicit raw file input; Render does not create a file or targetFiles.

#### Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. Historical v1.0/v1.1 schemas are archived; migrate descriptors to v1.2 before validation.

#### Request/reply no-message semantics

`mq.<id>.request(...)` is complete only when the correlated reply is received. A successful PUT followed by MQRC 2033 (`MQRC_NO_MSG_AVAILABLE`) therefore publishes `sent: true`, `replyReceived: false`, the native completion/reason metadata, and an `MQ_NO_REPLY` error with status `ERROR`. It is not a generic `TIMEOUT`, so `retryOn: TIMEOUT` does not PUT the business request again. Use `mq.<id>.send(...)` followed by `mq.<id>.receive(correlationId=...)` when repeated reply polling is required.

Standalone `mq.<id>.receive(...)` retains its polling contract: a 2033 no-message result may remain a completed PASS with `received: false`, subject to the configured receive semantics. An actual outer Action deadline remains `MQ_TIMEOUT` and follows the normal timeout/retry policy.

See [Actions and Typed Values](../14_actions.md) for the shared DocumentValue and typed-result contract.
