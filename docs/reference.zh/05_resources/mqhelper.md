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

`connection.transport` 可在 v1.1 設定為 `MQSeries Client`、`MQSeries` 或 `MQSeries Bindings`；v1.1 instance 值會覆寫 `defaults.connection.transport`。歷史 v1.0 schema 不接受此欄位，但 runtime 預設仍為 `MQSeries Client`。ATT 會將所選 IBM MQ client constant 原型別連同 `MQConstants.TRANSPORT_PROPERTY` 傳入；若已安裝 client 缺少該 transport constant，會明確指出所需 constant/dependency，不會代換成數字。

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

MQ reply bytes 會先按收到的 CCSID 解碼，再依有效 `responseFormat`（`text|json|yaml|xml`）解析。v1.1 可設定 `requestReply.responseFormat`；v1.0 descriptor 不接受此欄位，runtime 預設為 `text`。`request`/`receive` 的優先序為 call `responseFormat` > 所選 instance 繼承後的 v1.1 `requestReply.responseFormat` > `text`；`send` 不接受 `responseFormat`。Action `result.format` 仍只負責序列化，不會改變 resource response parser。省略 path 不建立檔案；`path: console` 將選定序列化寫入 Case log，不會新增 `output.targetFiles`。不會隱式建立 `.reply.bin`。Overwrite/path safety 沿用 common Action rules。

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

#### API 選擇

所有呼叫都使用穩定的 logical helper ID 與 named arguments。`file` 會從 Case output directory 或 package 以原 bytes 讀取。三個 API 的差異在於是否等待 reply，以及由誰負責 correlation：

| API | 主要用途 | 預設 queue | Correlation |
|---|---|---|---|
| `send` | 單向 producer，不等待 reply | `message.requestQueue` | 無 |
| `receive` | 獨立 consumer | `message.replyQueue` | 可選 caller-supplied `correlationId`；省略時不過濾 |
| `request` | 單次操作完成 request/reply | `message.requestQueue` + `message.replyQueue` | 自動以 request `MsgId` 比對 reply `MQMD.CorrelId` |

v1.1 會先選擇／解析 physical instance，再使用該 instance 繼承後的有效 queue。明確的 operation argument 會覆蓋選中 instance 的設定，不會借用其他 instance 的 queue。Action `timeoutMs` 是外層 deadline；call-level `waitMs` 覆蓋 `requestReply.waitMs`，但不能超出剩餘 Action deadline。三個 API 的 Tool Action retry 都需明確設定；ATT 不推斷 idempotency。

#### `mq.<helper>.send(...)`

當呼叫端只需要把 file-based message 放到 request／producer queue、不需要同步 reply 時使用 `send`。PUT 成功後呼叫即完成，不會開啟或等待 reply queue。

| 參數 | 型別 | 必填？ | 預設／優先序 | 說明 |
|---|---|---:|---|---|
| `file` | string/path | 是 | 無 | Payload file，以原 bytes 從 Case output directory 或 package 讀取。 |
| `queue` | string | 有設定時可省略 | call `queue` > 選中 instance 的 `message.requestQueue` > validation error | 目的 queue。 |
| `instance` | string | 否 | v1.0／單一 instance 使用該 instance；多 instance 依設定 strategy 選擇 | v1.1 時將呼叫固定至指定 physical instance。 |

省略 `queue` 時，helper 必須提供有效的 `message.requestQueue`。兩者都缺少時，ATT 會在連線前回報可行動的參數／設定錯誤。Result/evidence 記錄 `sent`、解析後的 `queue`、bytes、`messageId` 與 `correlationId`；send 沒有 business reply，`output.result` 為 null／不存在，也不接受 Action result persistence。`timeoutMs` 限制整個 Action；若明確啟用 retry，重複 PUT 可能產生重複訊息，重放安全由 package author 負責。

使用 helper 預設 queue 的最簡 send：

~~~yaml
message: {requestQueue: PAYMENT.REQUEST}
~~~

~~~text
mq.payment.send(file='request.xml')
~~~

明確 queue 會覆蓋設定預設：

~~~text
mq.payment.send(queue='PAYMENT.REQUEST.ALT', file='request.xml')
~~~

Payload 也可由前一個 Action 產生；`targetFiles[0]` 是 render 後的實際檔案路徑，不是寫死的 package filename：

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

v1.1 會先選 instance，再使用它的 queue fallback。以下 descriptor 片段中，明確指定 `payment-b` 時會使用它自己的 `PAYMENT.REQUEST.B`，而非 group default：

~~~yaml
defaults:
  message: {requestQueue: PAYMENT.REQUEST}
instances:
  - id: payment-a
  - id: payment-b
    message: {requestQueue: PAYMENT.REQUEST.B}
selection: {strategy: roundRobin}
~~~

此呼叫固定到 `payment-b`；若明確傳入 `queue`，則會覆蓋該 instance 設定：

~~~text
mq.payment.send(file='request.xml', instance='payment-b')
~~~

#### `mq.<helper>.receive(...)`

將 `receive` 用作 standalone consumer，以讀取 reply 或獨立 producer 發出的訊息。它依有效 wait 執行一次 MQGET，並回報是否收到訊息。

| 參數 | 型別 | 必填？ | 預設／優先序 | 說明 |
|---|---|---:|---|---|
| `queue` | string | 有設定時可省略 | call `queue` > 選中 instance 的 `message.replyQueue` > validation error | Input／reply queue。 |
| `correlationId` | hex string | 否 | 無預設；省略時不以 `MQMD.CorrelId` 過濾 | 提供時，以此值比對收到訊息的 `MQMD.CorrelId`。 |
| `waitMs` | integer | 否 | call value > `requestReply.waitMs`；並受 Action `timeoutMs` 限制 | MQGET 最長等待毫秒數。 |
| `instance` | string | 否 | v1.0／單一 instance 使用該 instance；多 instance 依設定 strategy 選擇 | v1.1 時將呼叫固定至指定 physical instance。 |

省略 `queue` 時，helper 必須提供 `message.replyQueue`。`output`／evidence 記錄解析後的 `queue`、有效 `waitMs`、`received`、message IDs 及安全的 MQ reason／completion metadata。收到訊息後，以訊息 CCSID 解碼成原生 `String` 放在 `output.result`。等待結束時收到 MQRC 2033（`MQRC_NO_MSG_AVAILABLE`）是已完成的 no-message result，而非 ATT/runtime error：`received` 為 false、`output.result` 為 null。Action deadline 到期則回報 `MQ_TIMEOUT`。明確設定 Action retry 可能再取走另一則訊息，因此不會自動 retry。

使用 helper 預設 reply queue 的最簡 receive；也可調整本次等待時間：

~~~yaml
message: {replyQueue: PAYMENT.REPLY}
~~~

~~~text
mq.payment.receive()
mq.payment.receive(waitMs=5000)
~~~

明確 input queue 會覆蓋預設：

~~~text
mq.payment.receive(queue='PAYMENT.REPLY.ALT', waitMs=5000)
~~~

v1.1 會先選擇 `instance`，省略 `queue` 時再使用該 instance 的有效 `message.replyQueue`：

~~~text
mq.payment.receive(waitMs=5000, instance='payment-b')
~~~

若要等待特定 request 的 reply，需提供 correlation ID：

~~~text
mq.payment.receive(correlationId='414d5120...', waitMs=5000)
~~~

~~~text
提供 correlationId  -> 比對收到訊息的 MQMD.CorrelId
省略 correlationId  -> receive 時不套用 correlation filter
~~~

若本來就不需要 correlation，可用於獨立 event、notification、batch-result queue，或刻意接收下一則訊息的簡單 consumer：

~~~text
mq.events.receive()                 # event queue
mq.notifications.receive()          # notification queue
mq.batchResults.receive(waitMs=1000) # 有限等待批次結果
mq.validation.receive(queue='QA.CONSUMER')
~~~

注意：在多人共用的 reply queue 上直接呼叫 `receive()`，可能取走其他 caller 的 reply。Request/reply 流程通常應使用 `request(...)`；standalone consumer 若需鎖定某個 caller 的 reply，應使用 `receive(correlationId=...)`。

#### `mq.<helper>.request(...)`

當同一個操作需先送 request，再等待其 correlated reply 時使用 `request`。Correlation lifecycle 由 ATT 管理，caller 不傳 `correlationId`。

| 參數 | 型別 | 必填？ | 預設／優先序 | 說明 |
|---|---|---:|---|---|
| `file` | string/path | 是 | 無 | Request payload file，以原 bytes 讀取。 |
| `requestQueue` | string | 有設定時可省略 | call value > 選中 instance 的 `message.requestQueue` > validation error | Request output queue。 |
| `replyQueue` | string | 有設定時可省略 | call value > 選中 instance 的 `message.replyQueue` > validation error | Reply input queue。 |
| `waitMs` | integer | 否 | call value > `requestReply.waitMs`；並受 Action `timeoutMs` 限制 | 最長 reply 等待毫秒數。 |
| `instance` | string | 否 | v1.0／單一 instance 使用該 instance；多 instance 依設定 strategy 選擇 | v1.1 時將 PUT 與 GET 固定在同一 physical instance。 |

連線前必須有兩個有效 queue；明確參數會覆蓋選中 instance 的設定。正常流程如下：

~~~text
PUT request
  -> 取得 request MsgId
  -> 從有效 reply queue 執行 GET
  -> 比對 reply MQMD.CorrelId 與 request MsgId
~~~

Result/evidence 記錄解析後的 queues、wait、request/reply IDs、`replyReceived` 及安全的 MQ completion/reason 資訊。收到 reply 時 `output.messageId == output.replyCorrelationId`；`output.result` 是按 CCSID 解碼的原生 String。若有效 wait 結束仍無 reply，MQRC 2033 代表 request 已完成但沒有 reply（`replyReceived: false`、null result、reason 2033／`MQRC_NO_MSG_AVAILABLE`），不是 ATT/runtime error。若 Action deadline 到期則回報 `MQ_TIMEOUT`。Action retry 是 opt-in；再次 PUT 會產生新 MsgId，也可能重做業務操作。

最簡 request 使用 helper 的兩個預設 queue：

~~~yaml
message: {requestQueue: PAYMENT.REQUEST, replyQueue: PAYMENT.REPLY}
~~~

~~~text
mq.payment.request(file='request.xml')
~~~

可同時明確覆蓋 request 與 reply queue：

~~~text
mq.payment.request(requestQueue='PAYMENT.REQUEST.ALT', replyQueue='PAYMENT.REPLY.ALT', file='request.xml')
~~~

Call-level wait 覆蓋 helper 的 `requestReply.waitMs`，但仍受 Action deadline 限制：

~~~text
mq.payment.request(file='request.xml', waitMs=10000)
~~~

要將整個 request/reply cycle 固定到 v1.1 instance：

~~~text
mq.payment.request(file='request.xml', instance='payment-b')
~~~

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

#### Load 模式的 payload path

`file` 的 absolute path 只有在 resolved regular file 位於 ATT package root 之內時才接受。這適合 Flow 或 Template 使用 package 內的 checked-in request payload，例如：

~~~text
#{mq.toeaimq.request(file='/fpp/att/templates/flows/mqtest/BOC060032.xml')}
~~~

Load iteration workspace 採 lazy 設計。Absolute package payload 只需對 package root 做 validation，因此即使目前的 `output/load/<runId>/iterations/<iterationId>/` 尚未存在，也不需要先建立。ATT 不會為了驗證檔案而替每個成功 iteration 建立空 directory。此 payload check 發生在 MQ connect/open/put/get 之前，所以這類 failure 是 local path-safety error，不是 IBM MQ transport、queue 或 response parse error。

Relative path 保持 Case-output contract：會在目前 Case output 下 resolve、拒絕 `..` traversal、拒絕 payload symlink 和 symlink escape，並要求是安全的 regular file。Package 外的 absolute file 和真正不存在的 file 都會被拒絕；diagnostic 會指出 payload 問題，不會顯示 unrelated lazy-workspace `NoSuchFileException`。

Troubleshooting 時先判斷 `file` 是 absolute 還是 relative，再檢查 resolved file 與適用 root。不要以預先建立所有 Load workspace 作為 workaround；如需避免成功 iteration artifact，可按 evidence guide 使用 `evidence: {mode: failures}` 或 `metrics`。
