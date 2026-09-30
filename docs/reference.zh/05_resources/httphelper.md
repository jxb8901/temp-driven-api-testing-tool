### 5.5 HTTPHelper

HTTPHelper 是依環境綁定的 HTTP resource。選定的 config profile 將穩定 logical helper ID 綁定至 base URL。Descriptor 使用 att-httphelper/v1.1。

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

以 type: tool Action 的 primary call 呼叫 http.<id>.get/post/request。Response bytes 由此 boundary 解析：使用 call responseFormat、helper default，或 auto 時依 Content-Type 判斷。支援 auto、text、json、yaml、xml。解析後的 native value 發布於 output.result。可選 evidence.output 是有長度上限的人類可讀 snapshot，不會改變該值。

#### Request body 與 DocumentValue

Render Action 回傳包含 format 和權威渲染文字的 DocumentValue，可直接傳入 body：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

HTTP 在 charset encoding boundary 傳送完全相同的 DocumentValue text，不會 parse/serialize。DocumentValue 不可搭配 requestFormat。

Map/List 是抽象結構化值，需明確指定 requestFormat，例如 body=${EXEC.INPUT.request}, requestFormat=json。requestFormat 支援 text、json、yaml、xml，且只用於 Map/List。DocumentValue + requestFormat 及 String + requestFormat 會被拒絕。body 和 file 互斥；file 是 HTTP call 明確支援的 raw file input。Render 不建立結果檔，也沒有 targetFiles。

DocumentValue.format 不會覆蓋由 resource 管理的 HTTP Content-Type。需要特定 media type 時請配置 contentType/header。Request charset/header 與 response parsing 都由 HTTPHelper 管理，與 Action result/Log formatting 分開。

#### Failure 與 evidence

Transport/protocol、response-parse failures 屬 operational error。已收到的 4xx/5xx 是 completed response，可對 statusCode 做 assertion。HTTP evidence 可包含 helper ID、method、安全 URL、response status、content type、byte counts、response format 與 duration。Credentials/payload 不會隱式保存。Load 可用 evidence.resources.output: none 略過可選 resource output formatting，或將其延至 iteration evidence 保留時。

共用 DocumentValue 與 typed-result 契約見[動作與型別化值](../14_actions.md)。
