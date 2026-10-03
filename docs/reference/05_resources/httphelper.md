### 7.5 HTTPHelper

HTTPHelper is an environment-bound HTTP resource. The selected config profile binds a stable logical helper ID to its base URL. Descriptors use att-httphelper/v1.1.

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

The HTTP connection pool has these defaults and limits:

| Pool field | Default | Contract |
|---|---:|---|
| `maxConnections` | 50 | Total connections, 1–10000 |
| `maxConnectionsPerRoute` | 20 | Per-route connections, 1–10000 and no greater than `maxConnections` |
| `connectionRequestTimeoutMs` | 5000 ms | Maximum wait to lease a connection, 1–3600000 ms |
| `keepAliveMs` | 30000 ms | Keep-alive duration, 1–3600000 ms |
| `idleEvictMs` | 60000 ms | Idle age after which a connection is eligible for eviction; checked before requests, 1–3600000 ms |

Size the pool for the expected in-flight Load concurrency. For example, the following tuning overrides raise the per-route capacity and shorten the connection-lease wait; values shown here are not defaults:

~~~yaml
pool:
  maxConnections: 50
  maxConnectionsPerRoute: 40
  connectionRequestTimeoutMs: 2000
~~~

`connectionRequestTimeoutMs` bounds how long an operation waits to lease a pooled connection. A short wait/timeout while service latency remains stable can indicate generator pool saturation; inspect the per-helper active/idle/waiting/peak metrics in Load output.

Call http.<id>.get/post/request as the primary call of a type: tool Action. Response bytes are parsed at this boundary using call responseFormat, the helper default, or Content-Type when auto is selected. Supported response formats are auto, text, json, yaml and xml. The parsed native value is output.result. Optional evidence.output is a bounded human-readable snapshot and never changes that value.

#### Request bodies and project-file Strings

A project-file expression returns the exact UTF-8 file content as a String. Assign it once and pass it directly as the body:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.VARS.requestText})}"
~~~

HTTP sends the exact String to its charset-encoding boundary. ATT does not parse and reserialize it. Do not combine a project-file String with requestFormat.

A Map/List is an abstract structured value and requires explicit requestFormat, such as body=${EXEC.INPUT.request}, requestFormat=json. requestFormat accepts text, json, yaml or xml and applies only to Map/List. String + requestFormat is rejected. HTTP calls do not accept local file paths; `file` is an unknown argument. A project-file expression creates no result file and has no targetFiles.

The project-file String has no format metadata and does not set HTTP Content-Type. Configure contentType/header when a specific media type is required. Request charset/headers and response parsing remain HTTPHelper concerns, separate from Action result or Log formatting.

#### Failure and evidence

Transport/protocol and response-parse failures are operational errors. A received 4xx/5xx is a completed response and can be asserted through statusCode. HTTP evidence may include helper ID, method, safe URL, response status, content type, byte counts, response format and duration. Credentials and payloads are not implicitly stored. Load can set evidence.resources.output: none to skip optional resource-output formatting, or defer it until the iteration evidence is retained.

HTTP responses are capped at 10 MiB. A larger declared Content-Length is rejected before reading; unknown or incorrect lengths are read through a bounded stream that stops at the first byte beyond the cap. The operation reports `HTTP_RESPONSE_TOO_LARGE` while retaining the received status code in diagnostics. In Load resource diagnostics, HTTP connection pools report active, idle, waiting, and peak counts by helper.

Set `defaults.maxResponseBytes` from 1 through 1073741824 in the HTTP helper descriptor; it defaults to 10485760 (10 MiB).

See [Actions and Typed Values](../14_actions.md) for the shared project-file String and typed-result contract.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.
