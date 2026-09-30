### 5.5 HTTPHelper

HTTPHelper is an environment-bound HTTP resource. The selected config profile binds a stable logical helper ID to its base URL. Descriptors use att-httphelper/v1.1.

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

Call http.<id>.get/post/request as the primary call of a type: tool Action. Response bytes are parsed at this boundary using call responseFormat, the helper default, or Content-Type when auto is selected. Supported response formats are auto, text, json, yaml and xml. The parsed native value is output.result. Optional evidence.output is a bounded human-readable snapshot and never changes that value.

#### Request bodies and DocumentValue

A Render Action returns a DocumentValue containing format and authoritative rendered text. Pass it directly as body:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

HTTP sends the exact DocumentValue text to its charset-encoding boundary. ATT does not parse and reserialize it. Do not combine a DocumentValue with requestFormat.

A Map/List is an abstract structured value and requires explicit requestFormat, such as body=${EXEC.INPUT.request}, requestFormat=json. requestFormat accepts text, json, yaml or xml and applies only to Map/List. DocumentValue + requestFormat and String + requestFormat are rejected. body and file are mutually exclusive; file is explicit raw file input supported by the HTTP call. Render creates no result file and has no targetFiles.

DocumentValue.format does not override resource-owned HTTP Content-Type. Configure contentType/header when a specific media type is required. Request charset/headers and response parsing remain HTTPHelper concerns, separate from Action result or Log formatting.

#### Failure and evidence

Transport/protocol and response-parse failures are operational errors. A received 4xx/5xx is a completed response and can be asserted through statusCode. HTTP evidence may include helper ID, method, safe URL, response status, content type, byte counts, response format and duration. Credentials and payloads are not implicitly stored. Load can set evidence.resources.output: none to skip optional resource-output formatting, or defer it until the iteration evidence is retained.

See [Actions and Typed Values](../14_actions.md) for the shared DocumentValue and typed-result contract.
