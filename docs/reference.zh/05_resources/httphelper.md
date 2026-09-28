### 5.5 HTTPHelper

HTTPHelper 是依環境綁定的一級 HTTP 資源。Template／Flow 呼叫固定的 logical ID；選定的 `att-config/v2.8` profile 提供實體 endpoint。與 command-backed curl Tool 不同，HTTPHelper 自行管理有界、可重用的 client、型別化回應及 HTTP metadata。

```yaml
# config/config.yaml
schemaVersion: att-config/v2.8
environment: SIT
environments:
  SIT: {httphelpers: [config/httphelpers/sit/payment.yaml]}
  UAT: {httphelpers: [config/httphelpers/uat/payment.yaml]}
```

兩份 descriptor 均使用 `id: paymentApi`，只有 `baseUrl`、憑證及信任材料等環境資訊不同。profile 未列出 `httphelpers` 時繼承 root 清單；列出時整組替換。ID 不分大小寫須唯一，路徑須安全且位於 package 內。執行前應逐一驗證所選環境。

```yaml
schemaVersion: att-httphelper/v1.0
id: paymentApi
name: Payment API
description: Payment service
baseUrl: https://sit-payments.example.internal
defaults:
  headers: {Accept: application/json, X-Channel: ATT}
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
pool:
  maxConnections: 50
  maxConnectionsPerRoute: 20
  connectionRequestTimeoutMs: 5000
  keepAliveMs: 30000
  idleEvictMs: 60000
auth:
  type: bearer
  token: ${ENV:PAYMENT_API_TOKEN}
tls:
  verifyHostname: true
```

[Schema](../../schemas/att-httphelper-v1.0.schema.json) 拒絕未知欄位及不安全數值。`baseUrl` 必須是沒有內嵌憑證、query 或 fragment 的絕對 HTTP/HTTPS URL。路徑依標準 URI 規則解析：`/v1/orders` 從 origin root 開始，`v1/orders` 則依 base path 解析。每次呼叫不得使用絕對 URL、`//` path 或內含 query/fragment 的 path；請用 `query` map，由 client 編碼，並從記錄的 URL 移除 query 值。

可使用 `#{http.<id>.request(method='POST', path='/v1/orders', ...)}`，或 `get`、`post`、`put`、`patch`、`delete`、`head`、`options`。HTTP 呼叫必須是 `type: tool` Action 的 primary call。具名參數為 `method`（僅 `request`）、`path`、`query`、`headers`、`body`、`file`、`contentType`、`connectTimeoutMs`、`readTimeoutMs`、`connectionRequestTimeoutMs`、`followRedirects`。Header 名稱不分大小寫，call 值覆蓋 helper 預設。`file` 從安全的 Case output 或 package 路徑逐 byte 讀取；相對路徑從 Case output 開始。`body` 可為 bytes、文字或序列化為 UTF-8 JSON 的型別值。`body` 與 `file` 不可同時設定；GET/HEAD 均不接受 body。`contentType` 可逐次覆蓋。Case 之間不會隱式共享 cookie session。

```yaml
actions:
  createPayment:
    type: tool
    call: >-
      #{http.paymentApi.post(path='/v1/payments',
        file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
        contentType='application/json')}
    result: {format: json, path: responses/payment.json}
    assert: "${output.statusCode} == 201"
```

`result.format` 支援 `raw`、`text`、`json`、`yaml`、`xml`；省略 `result` 時回應為 text。`raw` 在 `output.result` 保留精確 `byte[]`，`result.path` 也逐 byte 寫入；raw console 以 Base64 顯示。`text` 按 `Content-Type` charset 解碼，缺省為 UTF-8。結構化格式使用 ATT 既有 parser，格式錯誤會明確失敗。`result.path` 可省略，只控制持久化；`path: console` 寫入 Case log。`output` 直接提供 `httpHelper`、`method`、不含 query 的安全 `url`、`statusCode`、`reasonPhrase`、`contentType`、`requestBytes`、`responseBytes`、多值 `headers`。Header 名稱依 HTTP 規則不分大小寫。`Authorization`、cookie、API-key/token/password 類 response header 在 output/evidence 中遮蔽；request header、query 值、認證 secret 與 payload 不進入 HTTP evidence。

收到 4xx/5xx 仍屬完成的 HTTP exchange，可斷言預期 404 或 500。transport/config/format 失敗使 Action 為 `ERROR`；斷言不符則為 `FAIL`。Evidence 含 helper ID、method、安全 URL、bytes、收到的 status、duration、錯誤／redirect 數。既有 Action attempt list 保留 retry；HTTPHelper 不會自動重試 status。`retry.retryOn: [TIMEOUT]` 可於 HTTP 或連線池等待 timeout 後重送，`ASSERTION` 可在斷言失敗後重送。作者須評估**每一種** method 的副作用，POST/PATCH/DELETE 尤其可能重複執行。

Action `timeoutMs` 是總 deadline；pool borrow、connect、read 取 call override、helper 預設與剩餘 Action 時間的較小值。遲到的回應仍算 timeout。每個 helper 使用 thread-safe、有 `maxConnections`／`maxConnectionsPerRoute` 上限的連線池；pool wait 有專用 timeout，閒置連線在重用前清理，run/load owner 只關閉一次。TLS 憑證與 hostname 預設驗證且不能於 descriptor 關閉。可選的 `tls.trustStore` 是安全的 package-relative Java trust store 路徑，path/password 可使用 `${ENV:...}`；v1.0 不支援 mutual TLS。`auth.type` 為 `none`、`basic`（`username`、`password`）或 `bearer`（`token`）；`${ENV:NAME}` 解析後不記錄 secret 值。Redirect 預設關閉；開啟後最多跟隨五次，跨 origin redirect 會被拒絕以避免轉送憑證。

既有 command-backed curl/script Tool 保持相容。遷移常見 curl 呼叫時，將 endpoint／認證移入所選 HTTPHelper descriptor，Template 保留固定 logical ID，以 `http.<id>.<method>(...)` 取代 curl argv，並以共用 Action `result` 選擇回應格式。
