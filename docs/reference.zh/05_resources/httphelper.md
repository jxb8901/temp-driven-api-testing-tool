### 7.5 HTTPHelper

HTTPHelper 是依環境綁定的 HTTP resource。選定的 config profile 將穩定 logical helper ID 綁定至 base URL。Descriptor 使用 att-httphelper/v1.1。

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  maxResponseBytes: 10485760
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

HTTP connection pool 的預設值及限制如下：

| Pool 欄位 | 預設值 | 契約 |
|---|---:|---|
| `maxConnections` | 50 | 總 connection 數，1–10000 |
| `maxConnectionsPerRoute` | 20 | 每 route connection 數，1–10000 且不可大於 `maxConnections` |
| `connectionRequestTimeoutMs` | 5000 ms | 等待 lease connection 的最長時間，1–3600000 ms |
| `keepAliveMs` | 30000 ms | Keep-alive 時間，1–3600000 ms |
| `idleEvictMs` | 60000 ms | Connection 閒置達此時間後符合 eviction 條件；ATT 在 request 前檢查，1–3600000 ms |

請按預期 Load in-flight concurrency sizing。以下 tuning override 提高 per-route capacity 並縮短等待 lease 的時間；例中的值並非預設值：

~~~yaml
pool:
  maxConnections: 50
  maxConnectionsPerRoute: 40
  connectionRequestTimeoutMs: 2000
~~~

`connectionRequestTimeoutMs` 限制等待取得 pooled connection 的時間。若 service latency 穩定但 pool 等候/timeout 增加，可能表示 generator pool 飽和；請查看 Load output 中按 helper 區分的 active/idle/waiting/peak metrics。

以 type: tool Action 的 primary call 呼叫 http.<id>.get/post/request。Response bytes 由此 boundary 解析：使用 call responseFormat、helper default，或 auto 時依 Content-Type 判斷。支援 auto、text、json、yaml、xml。解析後的 native value 發布於 output.result。可選 evidence.output 是有長度上限的人類可讀 snapshot，不會改變該值。

#### Request body 與 project-file String

Project-file expression 回傳 exact UTF-8 file content String，可先 Assign，再直接傳入 body：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.VARS.requestText})}"
~~~

HTTP 在 charset encoding boundary 傳送完全相同的 String，不會 parse/serialize。String 不可搭配 requestFormat。

Map/List 是抽象結構化值，需明確指定 requestFormat，例如 body=${EXEC.INPUT.request}, requestFormat=json。requestFormat 支援 text、json、yaml、xml，且只用於 Map/List。String + requestFormat 會被拒絕。HTTP call 不接受 local file path；`file` 是 unknown argument。Project-file expression 不建立結果檔，也沒有 targetFiles。

Project-file String 不會覆蓋由 resource 管理的 HTTP Content-Type。需要特定 media type 時請配置 contentType/header。Request charset/header 與 response parsing 都由 HTTPHelper 管理，與 Action result/Log formatting 分開。

#### Failure 與 evidence

Transport/protocol、response-parse failures 屬 operational error。已收到的 4xx/5xx 是 completed response，可對 statusCode 做 assertion。HTTP evidence 可包含 helper ID、method、安全 URL、response status、content type、byte counts、response format 與 duration。Credentials/payload 不會隱式保存。Load 可用 evidence.resources.output: none 略過可選 resource output formatting，或將其延至 iteration evidence 保留時。

HTTP response 上限為 10 MiB。若宣告的 Content-Length 超過上限，會在讀取前拒絕；未知或錯誤長度則使用有界 stream，在超出上限的第一個 byte 停止。Operation 會回報 `HTTP_RESPONSE_TOO_LARGE`，並在 diagnostics 保留已收到的 status code。Load resource diagnostics 會依 helper 回報 HTTP connection pool 的 active、idle、waiting 與 peak 數量。

HTTP helper descriptor 的 `defaults.maxResponseBytes` 可設為 1 到 1073741824，預設為 10485760（10 MiB）。

共用 typed-result 契約見[Action 與型別化值](../14_actions.md)。

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。
