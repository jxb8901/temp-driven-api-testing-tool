# MQHelper

MQHelper is a logical IBM MQ resource with one or more physical instances. Current descriptors use att-mqhelper/v1.3. Connection settings, credentials, queue defaults and pool limits belong to the resource and are not exposed through META.

~~~yaml
schemaVersion: att-mqhelper/v1.3
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
    maxResponseBytes: 10485760
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

A Tool Action calls mq.<id>.send, mq.<id>.receive or mq.<id>.request as its primary operation. MQ reply bytes are decoded using received CCSID metadata when available, then parsed by responseFormat (text/json/yaml/xml). The parsed typed value is output.result. responseFormat owns ingress parsing; Log.format and evidence.output.format only control presentation.

## Request queue routing

<!-- att-docs:historical -->
`att-mqhelper/v1.3` adds `message.requestQueues` and `message.requestQueueSelection.strategy`. Existing `att-mqhelper/v1.2` scalar descriptors remain accepted and keep their original behavior; the new keys require v1.3.
<!-- /att-docs:historical -->

~~~yaml
schemaVersion: att-mqhelper/v1.3
id: payment
name: Payment MQ
description: Payment request queue routing
defaults:
  connection: {queueManager: QM1, host: mq.example.internal, port: 1414, channel: APP.SVRCONN}
  message:
    requestQueues: [PAYMENT.REQUEST.01, PAYMENT.REQUEST.02, PAYMENT.REQUEST.03]
    requestQueueSelection: {strategy: roundRobin}
    replyQueue: PAYMENT.REPLY
instances:
  - id: primary
~~~

A list contains 1–256 unique, nonblank queue names, each at most 48 characters from `[A-Za-z0-9_.%/-]`. `single` (default) chooses the first entry; `roundRobin` rotates in list order; `random` chooses only configured entries. Scalar `requestQueue` and list `requestQueues` cannot coexist in one message object. An instance's scalar replaces an inherited list and its selection policy; an instance's list replaces an inherited scalar/list and resets selection to `single` unless it supplies its own policy. An instance that only overrides `requestQueueSelection` keeps the inherited list. Selection policy requires an effective list. Reply defaults continue to inherit separately and remain one optional scalar `replyQueue`.

Runtime first selects the physical instance using `instance=` or physical `selection.strategy`. Then `send(queue=...)` or `request(requestQueue=...)` overrides automatic queue routing. Blank/invalid explicit queues fail with `MQ_ARGUMENT` before MQ I/O; they never fall back to a configured destination. Without an explicit queue, the selected instance supplies its scalar or list. Missing required effective queues also fail before connecting. `receive` uses its explicit `queue` or the single reply default. Each request queue belongs to the selected instance's queue manager; this does not route across queue managers.

Queue round-robin counters are thread-safe and scoped to one `MqHelperExecutor`, logical helper, and physical instance. Run/Debug/Load calls sharing that executor share counters; separate executors and Worker JVMs start their own counters. There is no Server-wide or cross-worker fairness guarantee. Explicit overrides and receive calls do not advance queue counters. Automatic selection occurs once after argument/payload validation and before connecting; connection/open/PUT failures still advance it. A request never reselects or automatically fails over after PUT, including a correlated reply timeout. Explicit Action retry remains governed by the replay policy below.

Action evidence and output metadata include `requestQueue`, `queue`, `requestQueueSelectionStrategy` (`single`, `roundRobin`, `random`, or `explicit`), the selected physical instance and queue manager, and the effective `replyQueue` for requests. ReplyToQ/ReplyToQMgr and sent MsgId → reply CorrelId matching remain unchanged. These bounded routing fields accompany retained Run/Debug/Load evidence; credentials remain protected.

## Sending file-content strings and abstract values

A file-content expression produces a String and can be passed directly as payload:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT encodes the exact file text using the configured MQ charset/CCSID. It does not parse and reserialize the String. Do not supply requestFormat for a file-content String; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. String + requestFormat is rejected. MQ send/request calls do not accept local file paths; `file` is an unknown argument. A file-content expression does not create a file or targetFiles.

## Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. See [Migration Notes](../appendices/migrations.md) for schema migration.

MQ replies are capped at 10 MiB. The IBM MQ adapter configures the message receive limit before reading and reports `MQ_RESPONSE_TOO_LARGE` when a reply exceeds it. This is a transport-success size rejection and remains distinct from a missing reply or connection failure.

See [Actions and Typed Values](../actions.md) for the shared typed-result contract.

## Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing; `maxResponseBytes` defaults to 10485760 and accepts 1–1073741824 |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s. Optional `att-mqhelper/v1.3` lifecycle keys default off: `maxIdle`, `maxLifetime`, `staleIdleValidation`, `staleIdleThreshold` (60s), `validationTimeout` (200ms, 1–5000ms), `heartbeatInterval` (0) |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../../../schemas/att-mqhelper-v1.3.schema.json).

### Idle connections and teardown

A LOAD pool key is `logicalId::instanceId` (for example `fromeaimqhk::mq01`). Teardown diagnostics use a single colon, `fromeaimqhk:mq01`. Those strings name the same physical pool. A failure for `fromeaimqhk::mq01` is not a failure of `fromeaimqcb::mq01` or `toeaimq::hk02`.

`resources.mq` is a PRE_TEARDOWN sample taken before disconnect. `failures` counts creation failures only. Idle-validation problems are `validationFailures` and are not included in `failures`. `cleanupFailuresIncluded` is false: disconnect results stay in `disconnectFailureCount` and `failureClass`. `failures: 0` with `idle: 2` does not mean disconnect succeeded and does not mean validation ran. `resources.postTeardown` is the separate confirmation sample taken after disconnect attempts. Unconfirmed sessions stay quarantined: the slot remains charged, the connection is not borrowed again, and MQRC 2009 does not prove the queue manager released it. The job stays ERROR.

`PHYSICAL_DISCONNECT_FAILED` means disconnect returned or threw before the shutdown wait ended. `LOAD_RESOURCE_TEARDOWN_TIMEOUT` means disconnect or connect was still running when that wait ended (`max(borrowTimeout, 5s)`). That session is one frozen record, including a connect still inside the factory before a connection object exists. A later completion, including MQRC 2009 or a successful disconnect, is attached to the same record and is not counted again; the failure class stays `LOAD_RESOURCE_TEARDOWN_TIMEOUT`. A validation timeout is neither of those: that connection is not offered. `close()` interrupts the idle-validation thread. That worker clears the interrupt status before it calls disconnect, so the closer does not observe the validation interrupt. If the check is still running when shutdown ends and `disconnect()` was not attempted, the result is `MQ_IDLE_VALIDATION_INCOMPLETE` and it is not added to `failedCloseCount`. `isConnected()` returning false is `MQ_IDLE_NOT_CONNECTED` and does not invent MQRC 2009. Reason 2009 is recorded only when IBM returns it. A cancelled lease or other non-connection invalidation stays out of the idle pool with `exclusionCode=INVALIDATED` and is not marked known-dead. `knownDead` is reserved for a recognized connection failure or an explicit outage.

Reason 2009 at final disconnect does not say when the socket stopped working. Correlate queue-manager, channel, client and network logs at the failure time. For the 2026-10-30 run that finished 2026-11-01 10:52:35Z, that local time is 2026-11-01 18:52:35 HKT (UTC+8). Process exit does not prove a graceful MQ disconnect.

The default borrow path does not call MQ. Optional stale validation, when `staleIdleValidation` is true and the idle age is at least `staleIdleThreshold`, calls IBM `isConnected()` and, when the client exposes it, `inquire` of `MQIA_PLATFORM`. It does not put or get a business message. `staleIdleThreshold: 0` validates every idle borrow. A transport without those methods is counted as unsupported and is still offered. `maxIdle` and `maxLifetime` recycle an idle connection by closing it, with no validation call and no business message. The next borrow recycles an expired idle session before lending it. While either limit is greater than 0, a background sweep about every 30 seconds closes idle sessions that have already passed the limit, including a session that is never borrowed again, so it does not wait for final teardown. `0` disables that limit. With both at 0 the sweep does not start, and the default borrow path still does no MQ I/O. `close()` stops the sweep before it waits for disconnects, and a session is disconnected once. ATT does not retry the business call.

`pool.heartbeatInterval` is applied only when `com.ibm.mq.constants.MQConstants` actually exposes `HEARTBEAT_INTERVAL_PROPERTY` and the value is greater than 0. IBM MQ classes for Java 9.4 do not publish that connection property, so the setting does not override the SVRCONN channel HBINT on that client. `0` never sets a client property. Set the channel HBINT below the firewall idle timeout when idle sessions are being dropped. TCP keepalive is an operating-system and IBM client setting (`com.ibm.mq.cfg.TCP.KeepAlive`, channel KAINT). ATT does not enable it. Raise `borrowTimeout` only when a successful disconnect genuinely needs longer; a hung disconnect remains `LOAD_RESOURCE_TEARDOWN_TIMEOUT` and stays quarantined.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

### Request/reply timeout and replay policy

A correlated reply completes `mq.<id>.request(...)` with PASS. A successful PUT followed by correlated GET MQRC 2033 (`MQRC_NO_MSG_AVAILABLE`) is a standard TIMEOUT with `MQ_TIMEOUT` diagnostic, even if the outer Action deadline has time remaining. Native evidence retains `sent: true`, `replyReceived: false`, `completionCode: 2`, `reasonCode: 2033`, the reason name and effective `waitMs`. Other transport failures retain the stable MQ ERROR taxonomy; outer deadline and pool borrow timeout also follow the normal TIMEOUT path.

The canonical Action outcome is `output.status: TIMEOUT`; suite/report aggregate operational failure remains ERROR, with TIMEOUT and MQRC 2033 in the report message and Case log. Native metadata is available at `output.evidence.mq.invocations[0]` during the attempt and at `EXEC.ACTIONS.<actionId>.output.evidence.mq.invocations[0]` afterwards.

Without `retry.when`, `retryOn: [TIMEOUT]` can PUT the whole request again. For a side-effecting request, add a Boolean gate that excludes the already-sent/no-reply case:

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

The optional `?` path evaluates to null when another timeout has no MQ reason code. The condition permits ordinary timeout retry, but reasonCode 2033 suppresses a second PUT without changing the TIMEOUT outcome. ATT does not infer idempotency or deduplicate messages. Use `send` followed by correlated `receive` when repeated reply polling is required.

Standalone `receive` explicitly retains its non-error polling contract: MQRC 2033 returns PASS with `received: false` when the outer deadline has not expired, including a bounded wait that finds no message. An expired outer deadline is TIMEOUT. This operation-aware contract supersedes the earlier guidance that avoided request/2033 TIMEOUT to prevent replay. See [common retry semantics](../actions.md).
