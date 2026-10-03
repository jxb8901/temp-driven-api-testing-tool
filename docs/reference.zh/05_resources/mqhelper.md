### 7.4 MQHelper

MQHelper 是由多個 physical instances 組成的 IBM MQ logical resource。現行 descriptor 使用 att-mqhelper/v1.2。Connection settings、credentials、queue defaults 與 pool limits 屬於 resource，不會公開至 META。

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

Tool Action 以 primary operation 呼叫 mq.<id>.send、mq.<id>.receive 或 mq.<id>.request。MQ reply bytes 在收到 CCSID metadata 時優先按該編碼解碼，再依 responseFormat（text/json/yaml/xml）解析。Typed value 發布於 output.result。responseFormat 負責 ingress parsing；Log.format 和 evidence.output.format 只控制 presentation。

#### 傳送 project-file String 或抽象值

Project-file expression output 是 String，可直接傳入 payload：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT 使用配置的 MQ charset/CCSID 編碼完全相同的 file text，不會 parse/serialize。String 不應提供 requestFormat。Project-file expression 不會設定 MQMD.Format；MQ transport metadata 仍由 resource 管理。

Map/List 是抽象結構化值，需指定 requestFormat（text/json/yaml/xml），例如 payload=${EXEC.INPUT.request}, requestFormat=json。String + requestFormat 會被拒絕。payload 與 file 互斥。file 可用於明確的 raw file input；project-file expression 不建立檔案或 targetFiles。

#### Evidence、response parsing 與 Load

MQ evidence 可包含有界 transport metadata，例如 helper/instance identity、operation、安全 queue names、message IDs、CCSID、byte counts、response format、duration 與 failure classification。Payload capture 由 evidence.payload 控制；人類可讀 snapshot 由 evidence.output 獨立控制。Load 可用 evidence.resources.output: none 關閉 resource snapshots；否則等 iteration 保留後才格式化。Typed result 與 response parsing 不變。

Call-level responseFormat 可覆蓋 receive/request 的 requestReply.responseFormat；send 不解析 reply。Instance selection 與 pool limits 屬於 descriptor。Schema migration 見 [Appendix C](../appendices/migrations.md)。

MQ reply 上限為 10 MiB。IBM MQ adapter 會在讀取前設定 message receive limit，超限時回報 `MQ_RESPONSE_TOO_LARGE`。這是 transport 成功後的 size rejection，與 reply 遺失或 connection failure 分開處理。

共用 typed-result 契約見[Action 與型別化值](../14_actions.md)。

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

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

#### Request/reply timeout 與重播策略

`mq.<id>.request(...)` 收到 correlated reply 時為 PASS。PUT 成功後，correlated GET 回傳 MQRC 2033（`MQRC_NO_MSG_AVAILABLE`）時，使用標準 TIMEOUT 與 `MQ_TIMEOUT` diagnostic，即使 outer Action deadline 尚未到期。原生 evidence 保留 `sent: true`、`replyReceived: false`、`completionCode: 2`、`reasonCode: 2033`、reason 名稱及有效 `waitMs`。其他 transport failure 維持既有 MQ ERROR 分類；outer deadline 與 pool borrow timeout 也走標準 TIMEOUT 路徑。

Canonical Action outcome 為 `output.status: TIMEOUT`；suite/report 的 operation failure aggregate 仍為 ERROR，report message 與 Case log 顯示 TIMEOUT 及 MQRC 2033。當前 attempt 的原生 metadata 位於 `output.evidence.mq.invocations[0]`；完成後位於 `EXEC.ACTIONS.<actionId>.output.evidence.mq.invocations[0]`。

未配置 `retry.when` 時，`retryOn: [TIMEOUT]` 可重新 PUT 整個 request。對有副作用的 request，使用 Boolean gate 排除已送出但無 reply 的情況：

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

Optional `?` path 在其他 timeout 沒有 MQ reason code 時回傳 null，因此允許一般 timeout retry；reasonCode 為 2033 時阻止第二次 PUT，最終 Action 仍是 TIMEOUT。ATT 不會推斷 idempotency 或去除重複訊息。需要反覆輪詢 reply 時，可使用 `send` 後執行 correlated `receive`。

獨立 `receive` 明確保留非錯誤的 polling 契約：outer deadline 未到期時，即使 bounded wait 找不到訊息，2033 仍回傳 PASS 與 `received: false`；outer deadline 到期則為 TIMEOUT。此契約取代 先前為避免重播而不將 request/2033 分類為 TIMEOUT 的舊指引。請參閱[共用 retry 語義](../14_actions.md)。
