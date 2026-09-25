### 5.3 MQHelper

MQHelper 是一級 IBM MQ resource。每個 descriptor 使用 `schemaVersion: att-mqhelper/v1.0` 或 `att-mqhelper/v1.1`，具備穩定 logical `id`、connection topology 與可選 credential。Global `mqhelpers` 引用 descriptor file；environment profile 可讓相同 logical ID 在不同環境選擇不同 descriptor。v1.0 仍是相容的單一 instance 形式；v1.1 增加由多個 physical instance 組成的 logical group。

主要 call：

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payload 採 file-based contract，因此 request bytes 不需要複製到 Context/evidence。`request` 結合 send 與 correlated receive。Correlation identifier、queue/operation metadata、timing、diagnostic 屬 evidence；credential 與 payload bytes 不複製到 evidence。

Timeout 是 operation-specific failure，與 assertion failure 分開。MQ connection/pool lifecycle 是 framework-owned resource state，特別是在 Load mode，不會公開成 `EXEC.MQ` tree。

ATT default build 不要求 IBM MQ client class；真正執行 MQ 需要 package/release 文件所述 IBM MQ client jar/profile。MQ operation 與 Tool、DB 一樣進入同一 Action result/evidence envelope。


#### Issue #59 配置與 public contract

完整 descriptor 可包含 connection、message、requestReply、evidence、pool：

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

username/password 在建立 MQQueueManager 前分別對應 MQConstants.USER_ID_PROPERTY/PASSWORD_PROPERTY。Environment credential 是 secret，不會進入 evidence、log、report 或 generated docs。

message.charset 是寫入 MQMessage.characterSet 的整數 IBM MQ CCSID，不是 Java charset name；ccsid 是 compatibility alias，兩者同時出現必須相等。encoding 寫入 MQMessage.encoding。空的 message.format 合法且保持 empty；MQSTR、MQFMT_STRING、MQHRF2、MQFMT_NONE、NONE 仍支援。persistence 支援 asQueue/0、persistent/1、notPersistent/nonPersistent/2。expiry -1 是 MQEI_UNLIMITED；正數使用 IBM MQ 十分之一秒，不是 milliseconds。

requestQueue/replyQueue 是 optional request defaults。Queue precedence 是 call argument > message default > validation error。send(queue=...) 和 receive(queue=...) 不會套用這些 defaults。Request file 經 MQMessage.write(byte[]) 保持 bytes。只有 request output queue 使用 `MQOO_BIND_NOT_FIXED`；send output 使用普通 `MQOO_OUTPUT`，reply input 使用 shared input。ATT 設定 MQPMO_NEW_MSG_ID，使用 MQGMO_WAIT、MQMO_MATCH_CORREL_ID 和 waitMs 的 waitInterval，以 request MsgId 對 reply correlationId。Put/get 使用 NO_SYNCPOINT，不呼叫 legacy commit()。Descriptor 只有在 `encoding` 是合法的 IBM MQ integer/decimal/float 組合時才會接受。

#### Common saveAs

MQ receive/request 使用 common Action saveAs，不增加 MQ-specific resultType/replyType：

~~~yaml
saveAs:
  format: raw
  path: response.bin
  overwrite: false
~~~

format 預設 raw，path 可省略。raw 是原始 byte[]，text 是 String，json/yaml/xml 是現有 ATT typed value。沒有 saveAs 或 saveAs: {} 只保留 memory result，不建立 file。`path: console` 會把所選表示寫入 Case log，不加入 `output.targetFiles`，也不建立 file。沒有真正的 path 不會建立 .reply.bin。raw 加真正的 path 逐 byte 寫入，overwrite/path safety 沿用 common Action rules。

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

#### Output 與 validation

output.result 是 business payload；MQ metadata 直接放在 output。send 發布 sent、queue、bytes、messageId、correlationId，result 為 null/absent。receive/request 發布 received/replyReceived、queue names、effective waitMs、messageId、replyMessageId、replyCorrelationId、byte counts、MQ 提供時的 reply CCSID/encoding/format、completion/reason fields，parsed payload 只在 result。正常 request 滿足 output.messageId == output.replyCorrelationId。MQRC 2033 時 result 為 null，received/replyReceived 為 false，並發布 reasonCode 2033、MQRC_NO_MSG_AVAILABLE 及 effective waitMs。

Public MsgId/CorrelId 是 lowercase hex，每 byte 兩字元、沒有 separators、保留 leading zero；24-byte ID 是 48 字元。Raw runtime value 保持 byte[]。Typed reply 會優先使用收到的 MQMessage.characterSet/CCSID 解碼，沒有 metadata 才 fallback 到 configured charset。Log/report 以 new String(rawBytes, Charset.defaultCharset()) 顯示 raw，不轉 hex，也不建立 implicit file。Validation 拒絕 unknown fields、衝突 charset/ccsid、非法 encoding/expiry/queue、缺少 effective request/reply queue、不支援 saveAs format 及 unsafe path。

#### Issue #60 v1.1 logical group 與 physical instance

`att-mqhelper/v1.1` 保留一個 public logical helper id，同時宣告一個或多個 physical connection instance。v1.0 descriptor 不需修改仍然有效。v1.1 descriptor 為 `connection`、`message`、`requestReply`、`pool` 提供 group defaults 及 per-instance override：

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
evidence: {payload: metadata}
~~~

每個 physical instance 在 invocation 前先 materialize 成 immutable effective configuration。每個 section 的 precedence 是 invocation override、instance override、group default、runtime default，最後才是 validation error。Effective `queueManager`、`host`、`port`、`channel` 必須存在；username/password 可選，而且永遠不會輸出到 evidence。

Public call 維持 logical id：

~~~text
#{mq.payment.send(queue='PAYMENT.REQUEST', file='request.bin')}
#{mq.payment.request(file='request.bin', instance='payment-b')}
#{mq.payment.receive(queue='PAYMENT.REPLY', instance='payment-a')}
~~~

單一 instance 的 v1.1 group 直接使用該 instance。多 instance group 必須宣告 `selection.strategy: random` 或 `roundRobin`；每次 MQ invocation 只選擇一次，並在 connect 前完成，因此同一個 `request` 的 PUT 與 correlated GET 一定使用同一個 physical instance。明確的 `instance` call argument 可選定 physical id；未知 id 會被拒絕。每個 physical instance 都有獨立 pool，pool identity 是 logical id 加 physical id。

Output 與 evidence 同時保留 logical helper id 並公開選中的 physical instance。Evidence 也會記錄適用 strategy、queue manager、operation、queue names、MsgId/CorrelId 及安全的 connection metadata；credential 與 payload bytes 永不包含其中。Validation 會拒絕重複 physical id、未知 inherited field、缺少 effective connection field、非法 strategy 或 override，以及無效的 effective message/requestReply/pool 值。
