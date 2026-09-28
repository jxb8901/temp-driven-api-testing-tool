### 5.5 HTTPHelper

HTTPHelper is a first-class, environment-bound HTTP resource. A Template or Flow calls a stable logical ID; the selected `att-config/v2.9` profile supplies the physical endpoint. Unlike a command-backed curl Tool, HTTPHelper owns a bounded, reusable client, native response decoding and HTTP metadata.

```yaml
# config/config.yaml
schemaVersion: att-config/v2.9
environment: SIT
environments:
  SIT: {httphelpers: [config/httphelpers/sit/payment.yaml]}
  UAT: {httphelpers: [config/httphelpers/uat/payment.yaml]}
```

Both descriptor files use `id: paymentApi`; only environment-owned values such as `baseUrl`, credentials or trust material differ. A root `httphelpers` list is inherited when a profile omits its own list; a profile list replaces it in full. IDs and paths must be unique (IDs case-insensitively), safe and package-contained. Validate each selected environment before execution.

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

The [schema](../../schemas/att-httphelper-v1.0.schema.json) rejects unknown fields and unsafe values. `baseUrl` must be absolute HTTP/HTTPS without embedded credentials, query or fragment. Path resolution uses standard URI resolution: `/v1/orders` starts at the origin root, whereas `v1/orders` resolves against the configured base path. Absolute per-call URLs, protocol-relative paths, and paths with a literal query/fragment are rejected. Pass an encoded `query` map instead; query values are omitted from recorded URLs.

Use `#{http.<id>.request(method='POST', path='/v1/orders', ...)}` or convenience `get`, `post`, `put`, `patch`, `delete`, `head`, `options`. Calls must be the primary call of a `type: tool` Action. Arguments are named: `method` (only for `request`), `path`, `query`, `headers`, `body`, `file`, `contentType`, `connectTimeoutMs`, `readTimeoutMs`, `connectionRequestTimeoutMs`, and `followRedirects`. Header names compare case-insensitively; call headers override helper defaults. `file` reads exact bytes from a safe Case output or package path; relative file paths start at the Case output directory. `body` accepts bytes, text or a typed value serialized as UTF-8 JSON. `body` and `file` are exclusive; GET and HEAD reject both. Content type may be overridden per call. No implicit cookie session is shared across Cases.

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

HTTP response decoding is native and independent of Action `result.format`: JSON media types produce ATT typed JSON values; YAML media types produce typed YAML values; XML media types produce ATT's typed XML value; other textual media types decode using the declared charset or UTF-8. An `application/octet-stream` response fails as `HTTP_FORMAT` because arbitrary bytes are not a supported common Action result. Malformed structured bodies also fail explicitly. The common Action formats are only `text`, `json`, `yaml`, and `xml`; `result.format` controls serialization to `result.path` or `path: console` and never reparses or mutates `output.result`.

HTTP metadata is directly under `output`: `httpHelper`, `method`, safe `url` (without query), `statusCode`, `reasonPhrase`, `contentType`, `requestBytes`, `responseBytes`, and multi-valued `headers`. Public response-header keys are normalized to lowercase so case-sensitive Context paths are stable; repeated values remain lists. Only secret-bearing response headers and values matching configured credentials or values from secret-bearing request headers are redacted. Ordinary values that happen to match `Accept` or another non-secret request header remain visible. Request headers, query values, auth secrets and payloads are not recorded in HTTP evidence.

A received 4xx/5xx is a completed exchange, so assertions may deliberately expect 404 or 500. Transport/configuration/format failures make the Action `ERROR` with an HTTP-specific error type; an assertion mismatch is `FAIL`. Evidence contains helper ID, method, safe URL, byte counts, status when received, duration and any error/redirect count. The existing Action attempt list retains retries. HTTPHelper never retries statuses automatically. `retry.retryOn: [TIMEOUT]` can replay a request after an HTTP or pool-borrow timeout; `ASSERTION` can replay after a failed assertion. Authors must assess side effects for **every** method, including GET/PUT—POST/PATCH/DELETE may create duplicate work.

The Action timeout is the overall deadline; pool-borrow, connect and read timeouts use the smaller of call override, helper default and remaining Action time. Late responses are still timeouts. Each helper client has a thread-safe Apache HTTP connection pool bounded by `maxConnections` and `maxConnectionsPerRoute`; pool waits have an HTTP-specific timeout. Idle connections are evicted before reuse and the run/load resource owner closes the pool once. TLS certificate and hostname verification are on by default and cannot be disabled by the descriptor. Optional `tls.trustStore` is a safe package-relative Java trust store path, with optional `${ENV:...}` path/password; no mutual TLS in v1.0. `auth.type` is `none`, `basic` (`username`, `password`) or `bearer` (`token`); `${ENV:NAME}` resolves secrets without logging values. Redirects are off by default; if enabled, at most five redirects are followed and cross-origin redirects are rejected to prevent credential forwarding.

Existing command-backed curl/script Tools remain supported. To migrate a common curl call, move the endpoint and credentials to the selected HTTPHelper descriptor, keep a stable logical ID in the Template, replace curl argv with `http.<id>.<method>(...)`, and select the response representation via the common Action `result` field.
