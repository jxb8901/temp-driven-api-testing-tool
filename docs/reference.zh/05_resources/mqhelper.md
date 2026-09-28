### 5.3 MQHelper

MQHelper 是一級 IBM MQ resource。每個 descriptor 使用 `schemaVersion: att-mqhelper/v1.0` 或 `att-mqhelper/v1.1`，具備穩定 logical `id`、connection topology 與可選 credential。Global `mqhelpers` 引用 descriptor file；environment profile 可讓相同 logical ID 在不同環境選擇不同 descriptor。v1.0 仍是相容的單一 instance 形式；v1.1 增加由多個 physical instance 組成的 logical group。

主要 call：

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payload 採 file-based contract，因此 request bytes 不需要複製到 Context/evidence。`request` 結合 send 與 correlated receive。Correlation identifier、queue/operation metadata、timing、diagnostic 屬 evidence；credential 與 payload bytes 不複製到 evidence。

Timeout 是 operation-specific failure，與 assertion failure 分開。Action `timeoutMs` 優先於 helper 的 `requestReply.waitMs` 預設值，並在每次 GET 前重新計算剩餘 Action deadline；call `waitMs` 優先於 helper 預設值，但不能延長 deadline。IBM MQ client call 是同步操作，ATT 無法強制中斷 connect/open/put/get；若呼叫在 deadline 後才返回，ATT 會立即記錄 `MQ_TIMEOUT` 並套用 retry policy。所有 MQ 操作（`send`、`receive`、`request`）的 Tool Action retry 都由作者明確控制；ATT 不推斷 idempotency，也不抑制可能改變業務狀態的重放。重試 `send` 可能排入重複訊息；重試 `request` 會再次 PUT、產生新的 MsgId 和新的 correlation cycle，並可能重放業務操作。重放／重複處理安全由 package author 負責。每次 attempt 的 timing/retry 結果都保留在 Action evidence，包含各 request attempt 的 message/correlation ID。MQ connection/pool lifecycle 是 framework-owned resource state，特別是在 Load mode，不會公開成 `EXEC.MQ` tree。

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

username/password 在建立 MQQueueManager 前分別對應 MQConstants.USER_ID_PROPERTY/PASSWORD_PROPERTY。`evidence.payload` 支援 `metadata` 或 `none`；`metadata` 只在 evidence 保留 policy marker，`none` 則省略。Environment credential 是 secret，不會進入 evidence、log、report 或 generated docs。

message.charset 是寫入 MQMessage.characterSet 的整數 IBM MQ CCSID，不是 Java charset name；ccsid 是 compatibility alias，兩者同時出現必須相等。encoding 寫入 MQMessage.encoding。空的 message.format 合法且保持 empty；MQSTR、MQFMT_STRING、MQHRF2、MQFMT_NONE、NONE 仍支援。persistence 支援 asQueue/0、persistent/1、notPersistent/nonPersistent/2。expiry -1 是 MQEI_UNLIMITED；正數使用 IBM MQ 十分之一秒，不是 milliseconds。

`message.requestQueue` 和 `message.replyQueue` 是各 matching operation 的 optional defaults：send/request 使用 `requestQueue`；receive/request 使用 `replyQueue`。v1.1 會先選擇或解析 physical instance，再 materialize 該 instance 繼承後的 `message` settings，最後套用 call argument。因此有效優先序為 explicit call argument > selected instance override > group default > runtime default（如有）> point-of-use validation error。ATT 不會借用其他 physical instance 的 queue default；若最後仍沒有有效 queue，會在 connect 前令呼叫失敗。Request file 經 MQMessage.write(byte[]) 保持 bytes。只有 request output queue 使用 `MQOO_BIND_NOT_FIXED`；send output 使用普通 `MQOO_OUTPUT`，reply input 使用 shared input。ATT 設定 MQPMO_NEW_MSG_ID，使用 MQGMO_WAIT、MQMO_MATCH_CORREL_ID 和 waitMs 的 waitInterval，以 request MsgId 對 reply correlationId。Put/get 使用 NO_SYNCPOINT，不呼叫 legacy commit()。Descriptor 只有在 `encoding` 是合法的 IBM MQ integer/decimal/float 組合時才會接受。

#### Common Action result

MQ receive/request 使用共同的 Action `result`，不增加 MQ-specific resultType/replyType：

~~~yaml
result:
  format: text
  path: response.txt
  overwrite: false
~~~

MQ reply bytes 會按收到的 CCSID 解碼為原生 `String`；Action `result.format` 不會解析或替換該值。共用格式為 `text|json|yaml|xml`，只選擇如何將 typed value 序列化到檔案或 Case log。沒有 public `raw` Action result。省略 path 不建立檔案；`path: console` 將選定序列化寫入 Case log，不會新增 `output.targetFiles`。不會隱式建立 `.reply.bin`。Overwrite/path safety 沿用 common Action rules。

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

#### Output 與 validation

`output.result` 是 business payload（MQ reply 會以 CCSID 解碼成 String）；MQ metadata 直接放在 output。每個 operation 都發布 `mqHelper`、選中的 physical `instance`、`queueManager` 與 `selectionStrategy`；v1.0 及單一 instance helper 的 `selectionStrategy` 為 `single`。send 發布 sent、queue、bytes、messageId、correlationId，result 為 null/absent。`send` 不產生 business payload，因此拒絕 Action `result`；`receive` 與 `request` 支援它。receive/request 發布 received/replyReceived、queue names、effective waitMs、messageId、replyMessageId、replyCorrelationId、byte counts、MQ 提供時的 reply CCSID/encoding/format、completion/reason fields；解碼 payload 只放在 `output.result`。正常 request 滿足 `output.messageId == output.replyCorrelationId`。MQRC 2033 時 result 為 null，received/replyReceived 為 false，並發布 reasonCode 2033、MQRC_NO_MSG_AVAILABLE 及 effective waitMs。

Public MsgId/CorrelId 是 lowercase hex，每 byte 兩字元、沒有 separators、保留 leading zero；24-byte ID 是 48 字元。Transport payload 會在 MQ client 內保持原 bytes；public typed reply value 是 `String`，有 MQMessage.characterSet/CCSID 時優先使用，透過 explicit IBM MQ CCSID-to-Java charset resolver 解碼；不支援的 CCSID 會清楚失敗，缺少 metadata 時才 fallback 到 configured charset。Log/report 序列化 typed result，不會建立 implicit file。Validation 拒絕 unknown fields、衝突 charset/ccsid、非法 encoding/expiry/queue、不支援的 result format 及 unsafe result.path；缺少 effective queue 會在 operation 使用點回報。

#### Send、receive、request API

所有呼叫都使用穩定的 logical helper ID 與 named arguments。v1.1 group 可用 `instance` 指定 physical ID；否則 ATT 先依 `random`／`roundRobin` strategy 選擇。

| Operation | 必填參數 | 可選參數 | 省略 queue 時的來源 |
|---|---|---|---|
| `send` | `file` | `queue`、`instance` | `message.requestQueue` |
| `receive` | 無 | `queue`、`correlationId`、`waitMs`、`instance` | `message.replyQueue` |
| `request` | `file` | `requestQueue`、`replyQueue`、`waitMs`、`instance` | `message.requestQueue` 與 `message.replyQueue` |

`file` 會從 Case output 或 package 以原 bytes 讀取。`send`／`receive` 使用 `queue`；request 特意使用 `requestQueue` 和 `replyQueue`。明確的非 null call argument 會覆蓋選中 instance 的有效 message default。若 call argument 與有效設定都沒有 queue，ATT 會在建立連線前回報精簡參數／設定錯誤。Queue default 只在 physical instance 選定後解析，因此 request PUT 與 correlated GET 固定使用同一 broker 及該 instance 的 queue settings。

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

`receive`／`request` 的 call-level `waitMs` 覆蓋 `requestReply.waitMs`。Action `timeoutMs` 是外層 deadline，會將有效 wait 限制在剩餘時間內；MQGET 前會重新計算 receive wait。允許的等待結束時遇到 `MQRC_NO_MSG_AVAILABLE`（2033）代表正常 no-message outcome：operation 仍成功，`received`／`replyReceived` 為 false，`output.result` 為 null。若 Action deadline 已過，則回報 `MQ_TIMEOUT`。

Tool Action retry 必須明確設定，且適用於 send、receive、request。ATT 不推斷 idempotency：重試 send 可能重複入列；重試 request 會以新 PUT／MsgId 再做一次業務操作。每次 attempt 都保留各自 IDs 與 evidence。Request/reply 由 ATT 設定 reply queue metadata，並以生成的 request MsgId 配合 `MQMO_MATCH_CORREL_ID` 等待；輸出需滿足 `output.messageId == output.replyCorrelationId`，不要手動替換 correlation。Standalone receive 可用 `correlationId` 指定目標 reply。Multi-instance 下明確指定的 instance 會固定於該次呼叫；retry 或 no-message 不會暗中改用其他 broker。

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
evidence: {payload: none}
~~~

每個 physical instance 在 invocation 前先 materialize 成 immutable effective configuration。每個 section 的 precedence 是 invocation override、instance override、group default、runtime default，最後才是 validation error。Effective `queueManager`、`host`、`port`、`channel` 必須存在；username/password 可選，而且永遠不會輸出到 evidence。

Public call 維持 logical id：

~~~text
#{mq.payment.send(queue='PAYMENT.REQUEST', file='request.bin')}
#{mq.payment.request(file='request.bin', instance='payment-b')}
#{mq.payment.receive(queue='PAYMENT.REPLY', instance='payment-a')}
~~~

單一 instance 的 v1.1 group 直接使用該 instance。多 instance group 必須宣告 `selection.strategy: random` 或 `roundRobin`；每次 MQ invocation 只選擇一次，並在 connect 前完成，因此同一個 `request` 的 PUT 與 correlated GET 一定使用同一個 physical instance。明確的 `instance` call argument 可選定 physical id；未知 id 會被拒絕。每個 physical instance 都有獨立 pool，pool identity 是 logical id 加 physical id。

Output 與 evidence 同時保留 logical helper id 並公開選中的 physical instance。Evidence 也會記錄適用 strategy、queue manager、operation、queue names、MsgId/CorrelId 及安全的 connection metadata。使用 `evidence.payload: none` 時會省略 payload policy marker；credential 與 payload bytes 永不包含其中。Validation 會拒絕重複 physical id、未知 inherited field、缺少 effective connection field、非法 strategy 或 override，以及無效的 effective message/requestReply/pool 值。

`output.selectionStrategy` 表示已設定的 group policy（`single`、`random` 或 `roundRobin`），而非單次 invocation 的選擇來源。若呼叫明確提供 `instance`，此 policy 值仍維持不變；`output.instance` 則表示實際選中的 physical instance。
