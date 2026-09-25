## 09 Configuration Reference

This chapter is the authoritative reading reference for author-authored configuration. The files below [`schemas/`](../../schemas/) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

### Configuration layers and precedence

| Layer | Source | Owns |
|---|---|---|
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, global tools, group paths, MQ helper paths, optional global SSH |
| Tool group | configured YAML path | group identity, optional script/SSH, grouped tools |
| Dbhelper | configured `dbhelpers` YAML path | one database identity, connection, statement timeout, transaction, limits, and evidence policy |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

Tool Action timeout overrides Tool descriptor timeout, which overrides global timeout. Sidecars, stages, and Templates do not own timeout/retry defaults. For call-backed DB Tools the dbhelper statement timeout remains a backend ceiling. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Multi-environment profiles in V3.5.2

ATT V3.5.2 selects an environment through one common `att-config/v2.6` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

```text
Actions -> logical helper ID -> selected config -> physical descriptor -> endpoint
```

The supported package layout is:

```text
config/
├── config.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

The common config keeps the existing templates, testcase roots, run/execution/report settings, `toolGroups`, and global `tools` registry. The profile layer is deliberately limited to typed DB/MQ descriptor lists:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
environment: SIT                 # default profile; --env overrides it
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/sample.yaml
  - config/tools/fpp.yaml
  - config/tools/orders-db.yaml
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Use the executable complete configs in `config/environments/sit.yaml` and `config/environments/uat.yaml` as the migration source for the common registry, including `invokePaymentApi` and the `sample.getAcDate` tool used by `examples/load/closed-smoke.yaml`. Do not replace that shared registry with `tools: {}` or `toolGroups: []` in a real package.

The SIT and UAT DBHelper descriptors both use `id: orders`, while their JDBC URL and other physical connection details differ. The MQHelper descriptors both use `id: payment`, while host, queue manager, port, and channel differ. A complete descriptor pair, including pool settings and safe evidence policy, is in [`examples/environments/README.md`](../../examples/environments/README.md).

The Action definitions remain identical:

```yaml
actions:
  renderRequest:
    type: render
    payload: payment/request.json
    result: {format: text, path: rendered/{filename}}

  queryOrder:
    type: db
    db: orders
    query:
      sql: "select * from orders where order_id = ?"
      params:
        - "${EXEC.INPUT.orderId}"

  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
        waitMs=5000
      )}
```

`environment` is the default profile name. A case-insensitive `--env` selector overrides it. Each profile may replace `dbhelpers` and/or `mqhelpers` as a whole list; omitted lists inherit the common list. No generic recursive YAML merge is performed, and profile fields other than `dbhelpers` and `mqhelpers` are rejected. Unknown profile names fail before validation or external execution. Use the same package with every supported execution mode:

```sh
# SIT
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env SIT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT

# UAT
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env UAT
```

For CI, run the same validation and execution stages once per selected environment:

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
```

This design keeps Testcases, Templates, Flows, and Actions reusable and makes validation deterministic because the selected config defines the complete resource registry before execution. Logical IDs such as `orders` and `payment` represent capabilities, not physical endpoints; infrastructure topology belongs in configuration. Do not introduce `orders_sit`, `orders_uat`, or environment conditionals solely to choose endpoints. Separate top-level configs are appropriate when testcase/template roots, report policy, or package structure intentionally differ.

Keep non-secret topology in YAML: JDBC URL, MQ host/port, queue manager, channel, pool sizes, and timeouts. Keep DB/MQ usernames and passwords in `${ENV:NAME}` references backed by the local environment or CI secret store. DBHelper resolves complete `${ENV:NAME}` values for the URL, username, password, and string-valued connection properties. MQHelper resolves `${ENV:NAME}` only for username/password; host, queue manager, channel, and numeric port are normally literal values in the selected descriptor. Resolved secrets remain absent from profile metadata, diagnostics, reports, and generated documentation.

Use profiles when the same test package is promoted across environments and only infrastructure bindings change. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. Migration from the 3.5.0 complete-config pattern keeps every descriptor and Action unchanged: move the common settings into `config/config.yaml`, place each descriptor list under `environments.<NAME>`, and replace `--config config/environments/<env>.yaml` with `--config config/config.yaml --env <NAME>`.

### Schema catalog

V3.4 adds post-invocation Tool evidence and the independent MQ helper schema. V2.6.2 added `att-template/v2.6` and `att-sidecar/v2.2` for the unified Tool Action policy; `att-template/v3.1` is the current template schema and introduces the common Action `result` contract. The dbhelper schema remains V2.5.

| Artifact | Schema identifier | Formal definition |
|---|---|---|
| Debug input | `att-debug/v1.0` | [att-debug-v1.0.schema.json](../../schemas/att-debug-v1.0.schema.json) |
| Global configuration | `att-config/v2.6` | [att-config-v2.6.schema.json](../../schemas/att-config-v2.6.schema.json) |
| Legacy global configuration (read compatibility) | `att-config/v2.1`, `att-config/v2.2`, `att-config/v2.5` | [att-config-v2.5.schema.json](../../schemas/att-config-v2.5.schema.json) |
| Dbhelper instance | `att-dbhelper/v2.5` | [att-dbhelper-v2.5.schema.json](../../schemas/att-dbhelper-v2.5.schema.json) |
| MQ helper descriptor | `att-mqhelper/v1.0`, `att-mqhelper/v1.1` | [att-mqhelper-v1.0.schema.json](../../schemas/att-mqhelper-v1.0.schema.json), [att-mqhelper-v1.1.schema.json](../../schemas/att-mqhelper-v1.1.schema.json) |
| Tool group | `att-tool-group/v2.6` | [att-tool-group-v2.6.schema.json](../../schemas/att-tool-group-v2.6.schema.json) |
| Legacy Tool group (read compatibility) | `att-tool-group/v2.2` | [att-tool-group-v2.2.schema.json](../../schemas/att-tool-group-v2.2.schema.json) |
| Workbook sidecar | `att-sidecar/v2.2` | [att-sidecar-v2.2.schema.json](../../schemas/att-sidecar-v2.2.schema.json) |
| Legacy workbook sidecar (without timeout) | `att-sidecar/v2.1` | [att-sidecar-v2.1.schema.json](../../schemas/att-sidecar-v2.1.schema.json) |
| Template descriptor | `att-template/v3.1` | [att-template-v3.1.schema.json](../../schemas/att-template-v3.1.schema.json) |
| Previous template descriptor (legacy result fields rejected) | `att-template/v3.0` | [att-template-v3.0.schema.json](../../schemas/att-template-v3.0.schema.json) |
| Legacy template descriptors (recognized for validation/migration) | `att-template/v2.6`, `att-template/v2.5`, `att-template/v2.3` | [att-template-v2.6.schema.json](../../schemas/att-template-v2.6.schema.json), [att-template-v2.5.schema.json](../../schemas/att-template-v2.5.schema.json) |
| Run manifest | `att-run/v2.1` | [att-run-v2.1.schema.json](../../schemas/att-run-v2.1.schema.json) |
| Validation JSON | `att-validation/v2.1` | [att-validation-v2.1.schema.json](../../schemas/att-validation-v2.1.schema.json) |
| CI summary | `att-ci-summary/v2.1` | [att-ci-summary-v2.1.schema.json](../../schemas/att-ci-summary-v2.1.schema.json) |
| JUnit XML | XSD | [att-junit-v2.1.xsd](../../schemas/att-junit-v2.1.xsd) |
| Diagnostic codes | `att-diagnostic-catalog/v2.1` | [diagnostic-codes.yaml](../../schemas/diagnostic-codes.yaml) |

All JSON Schema files use Draft 2020-12. Schema-controlled objects reject unknown properties unless the schema explicitly permits `x-*`. Extensions are preserved metadata and have no execution meaning. Duplicate YAML keys, unsafe tags, wrong types, missing fields, invalid enums, and unsupported properties are errors.

### Global configuration

```yaml
schemaVersion: att-config/v2.6
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
execution:
  processOutput: {memoryLimitBytes: 65536, artifactLimitBytes: 104857600}
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns: {}
  html: {caseLogInlineLimitBytes: 32768}
  junit: {caseLogEmbedThresholdBytes: 10240}
xml: {namespaceMode: ignore}
toolGroups: [config/tools/database.yaml]
dbhelpers: [config/dbhelpers/orders.yaml]
mqhelpers: [config/mqhelpers/orders.yaml]
tools: {}
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | `att-config/v2.6`; V2.1/V2.2/V2.5 remain readable, but only V2.6 Tool descriptors accept `call`/`cache` |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty default profile name when `environments` is present; otherwise exposed metadata only |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `execution.processOutput.memoryLimitBytes` | `65536` | Integer 1024–1048576; in-memory head/tail preview per stdout/stderr stream |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Integer from `memoryLimitBytes` through 1073741824; maximum bytes streamed to each process artifact |
| `report.mode` | `append-to-copy` | `append-to-copy` or `none`; `none` skips result-workbook creation |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Arbitrary string keys and string label values |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.5` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.0` or `att-mqhelper/v1.1` YAML paths; normalized duplicates are rejected |
| `environments` | absent | Non-empty map of profile names; each profile may contain only `dbhelpers` and/or `mqhelpers` typed lists |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `ssh`, `tools`, `environments`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `execution` | `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; process Tools may use `output`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, `argName`, `argNameMode`, `x-*` |

V2.0 fields such as `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.2 fields.

### Dbhelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.5` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `statement`, `transaction`, `result`, `evidence`, `pool`, `x-*` |
| `connection` | required | required `url`; optional `username`, `password`, `driverClass`, `properties`, `readOnly`, `isolation`, `x-*` |
| `statement` | defaults | `timeoutSeconds` defaults to 30, integer 1–3600 |
| `transaction` | defaults | `scope: case|statement`, `onEnd: commit|rollback`; defaults `case`/`rollback` |
| `result` | defaults | `maxRows` 1000, `maxCellBytes` 1048576, `maxBytes` 10485760; positive bounded integers |
| `evidence` | defaults | `sql: full|hash` defaults full; `parameters: values|types|masked` defaults values |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `connectionTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize`, timeout is at least 250ms |

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [Database helpers](#52-dbhelper) for Action, expression, result, security, and lifecycle behaviour.

### MQ helper configuration

Each path in global `mqhelpers` resolves from the package root and contains one `att-mqhelper/v1.0` or `att-mqhelper/v1.1` object. v1.0 is a flat single-instance descriptor. v1.1 has `defaults`, a non-empty `instances[]` list, optional `selection.strategy` (`random` or `roundRobin` for multiple instances), and group-level `evidence`; each physical instance receives effective `connection`, `message`, `requestReply`, and `pool` values before execution. The detailed v1.1 model and invocation examples are maintained in the MQHelper resource module.

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `message`, `requestReply`, `evidence`, `pool`, `x-*` |
| `connection` | required | `queueManager`, `host`, `port`, and `channel` required; optional `username`, `password`; port 1–65535 |
| `message` | defaults | `ccsid` defaults to 1208; `format` is `MQSTR`, `MQHRF2`, `MQFMT_STRING`, `MQFMT_NONE`, or `NONE`; `persistence` is `asQueue`, `persistent`, `notPersistent`, or `nonPersistent` |
| `requestReply` | defaults | `waitMs` defaults to 10000 and is 0–3600000 milliseconds |
| `evidence` | defaults | `payload: none|metadata`; `none` omits payload evidence and `metadata` records only the policy marker; full payload bytes are never placed in structured evidence |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `borrowTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize` |

Connection credentials may be complete `${ENV:NAME}` references. The loader resolves them without putting the secret or the environment variable value in diagnostics, metadata, or Case evidence. Queue names supplied in calls are non-blank, at most 48 characters, and restricted to IBM MQ queue-name characters. A helper instance is selected case-insensitively by its `id`; configured paths and IDs must be unique.

Case log structured entries use YAML. The human log records each normal action and each Tool/DB invocation once; duplicated attempt fields and persisted `TOOL`/`DB` subtrees are omitted from this projection. The complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` is the default for remaining shared Map/List objects; `true` permits SnakeYAML `&id001` / `*id001` anchor markers, which carry no ATT identifier semantics.

ATT prefixes every Case log block whose section or nested `status` is `ERROR`, `FAIL`, or `INVALID` with `【!!!!!】`. Search for that exact marker to locate abnormal blocks; PASS, SKIPPED, and informational blocks remain unmarked.

### Workbook sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override timeout, retry, tools, dbhelpers, template root, environment, or output root.

### Template and action

| Object/type | Allowed/required contract |
|---|---|
| template root | `schemaVersion`, `name`, `description`, `actions`, `x-*`; schemaVersion, description, non-empty actions required |
| action common | `type`, `description`, `onFailure`, plus only fields belonging to its selected type; action ID has no dot |
| render | requires `payload` and `result.format`; optional `result.path`, `assert`; no `call`/expression/message/file/level/fields/timeout/retry/DB fields |
| tool | requires `call`; optional object-shaped `result`, `assert`, `expected`, `actual`, `timeoutMs`, Action-only `retry`, and `evidence`; command/call-backed Tools share this contract |
| db | requires `db` and exactly one `query`/`update`; selected block requires exactly one `sql`/`sqlFile` and either typed-list `params` or named `parameters`; optional `assert` and `result`; no `call`, retry, or Action timeout |
| assert | requires `assert`; optional `expected`, `actual`; no expression/render/tool/log-only fields, timeout, or retry |
| log | requires at least one of `message` or `file`; optional `level`, `fields`, `assert`; no render/tool/assert-action-only fields, timeout, or retry |
| assign | requires `name`, `expression`; optional `assert`; exact typed calls retain their Java value; name is unique below `EXEC.VARS` for the entire Case; no render/tool/DB/assert-action/log-only fields, timeout, retry, or result |
| `result` | required for Render; optional for supported Tool/DB Actions; `format` is Action-specific; `path` is optional; `overwrite` defaults false |
| retry | required `maxAttempts`, `intervalMs`, `retryOn`; categories are `ASSERTION`, `TIMEOUT` |

Template schema `att-template/v3.1` replaces `renderAs` and `saveAs` with `result`; legacy fields are rejected with migration suggestions. `result.format` selects `output.result`; `result.path` only controls optional persistence. A pathless result creates no artifact and `path: console` logs without creating a file. Retry `maxAttempts` is 2–10 and `intervalMs` is 0–3600000. `ASSERTION` requires a non-empty Tool Action `assert`. Log level is `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`. The template root and action permit `x-*`; `fields` is an unconstrained log-field map. `output` is runtime evidence and is never an action configuration field.

#### Assign variable uniqueness and lifetime

An `assign` action creates one immutable, Case-scoped entry below `EXEC.VARS`; it is not a mutable-variable update operation. Every `name` must be unique for the entire Test Case, including across stages and templates:

```yaml
firstAssign:
  type: assign
  name: txnSeq
  expression: "FIRST"

secondAssign:
  type: assign
  name: txnSeq
  expression: "SECOND"  # invalid: txnSeq was already declared for this Case
```

Validation reports `ATT-CTX-001` for the duplicate `${EXEC.VARS.txnSeq}` assignment and blocks execution. The second action never replaces the first value. `onFailure: continue` changes only subsequent action scheduling; it does not authorize an overwrite.

Use a new name for every transformation step:

```yaml
captureInitialAmount:
  type: assign
  name: initialAmount
  expression: "${EXEC.INPUT.amount}"

normalizeAmount:
  type: assign
  name: normalizedAmount
  expression: "#{number(${EXEC.VARS.initialAmount})}"
```

Later actions and stages read the transformed value as `${EXEC.VARS.normalizedAmount}`. The same assign name may be used by different Test Cases because each Case owns an isolated `EXEC.VARS`; uniqueness applies within one Case execution, not across the package globally.

If an assign expression fails, ATT does not create its variable. This does not relax the authoring rule: a later assign in the same Case plan still cannot reuse that declared name. If the expression succeeds and the assign action's optional assertion subsequently returns FAIL or ERROR, the variable remains available; assertions do not roll back successful assignment. `EXEC.VARS` survives stage/template transitions and is discarded only when that Test Case ends.

#### Common Action `result`

```yaml
renderRequest:
  type: render
  payload: requests/*.xml
  result:
    format: text
    path: rendered/{name}-out.{ext}
    overwrite: false

callApi:
  type: tool
  call: "#{invokePaymentApi(...)}"
  result: {format: json, path: responses/payment.json}
```

`result.format` selects `output.result`; `result.path` optionally persists that same representation. Supported formats remain Action-specific: Render accepts `raw|text|json|yaml|xml`; process Tools and MQ accept `raw|text|json|yaml|xml`; built-in/call-backed Tools accept `text|json|yaml|xml`; DB accepts `text|json|yaml|xml`. DB `text` uses its stable SQL*Plus-style formatter. Omitting `path` never creates an artifact. `path: console` writes the representation to the Case log without creating a file or `output.targetFiles` entry. All real paths remain safe, relative to the Case artifact directory, and are containment-checked.

Legacy `att-template/v2.6`, `v2.5`, and `v2.3` descriptors are recognized for validation and migration; legacy `renderAs`/`saveAs` result fields must be migrated to `att-template/v3.1` and are not accepted for execution. Do not interpret schema recognition as runtime compatibility for those fields.

Render evaluates ordinary ATT `${...}` / `#{...}` expressions in `result.path` first, then expands these source-set path tokens: `{filename}` (including extension), `{name}` (without final extension), `{ext}` (without dot), `{index}` (one-based deterministic order), and `{relativePath}` (relative to the matched source root). Token values are not recursively expression-evaluated. Multi-source paths must expand uniquely even when `overwrite: true`; every expanded path is safety-checked before writing. `output.result` remains a typed value for one source and an ordered source-keyed map for multiple sources; `output.targetFiles` lists only persisted paths.

Migration for `att-template/v3.1`: `renderAs` becomes `result.format`; `saveAs.format` becomes `result.format`; `saveAs.path` becomes `result.path`; `saveAs.overwrite` becomes `result.overwrite`. `renderAs: file` is ambiguous because it mixed output representation and persistence: choose a real format and a path explicitly. `att validate` rejects legacy fields and emits a concrete replacement suggestion (including the required representation choice for `file`) in human and JSON diagnostics; it never rewrites files.

### Tool contract

Each Tool requires `name`, `description`, and exactly one of `command` or `call`. Optional descriptor `timeoutMs` supplies the Tool-level default. A command is a non-blank scalar or non-empty string list; its `output` defaults to `txt` and accepts `txt|yaml|json|xml`. A call is one exact expression targeting DB query/scalar/update or a pure built-in; it forbids process-only `output`, SSH/script, and argument argv fields. Optional call-backed `cache` contains exactly `scope: case|db`; updates cannot be cached and `db` scope requires a DB query/scalar target.

Every argument requires `name`, `description`, and a YAML boolean `required`. For command-backed Tools, `argName` is optional and must be empty or one whitespace-free argv token. A non-empty `argName` requires exactly one complete-token placeholder. `argNameMode` accepts `once|repeat` and defaults to `once`; it controls a typed List supplied at the call site. These two argv properties are invalid for call-backed arguments. V2.6 does not define `delimit`.

Tool/argument keys are case-sensitive and argument keys use identifier syntax. The argument descriptor `name` is display text and may contain spaces, Chinese, and punctuation. External tool calls use named arguments. Positional arguments are reserved for ATT built-ins.

A tool-group root requires `schemaVersion`, package-unique `id`, `name`, `description`, and non-empty `tools`. It optionally accepts `script` in scalar/list command form and `ssh`. The group ID is the Tool package, so group calls use `group.tool`; inline global calls remain unqualified. Group/tool IDs match `[A-Za-z_][A-Za-z0-9_-]*` and contain no dot. Neither global nor qualified Tools may collide case-insensitively with canonical or legacy built-in names.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render glob matches remain below the template and all Action `result.path` targets remain below the Case artifact directory. ATT normalizes and checks root containment before reads and writes.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.5.2",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

Every diagnostic always contains `code`, `severity`, `message`, `file`, `field`, `sheet`, `row`, `column`, `template`, `action`, and `suggestion`. Inapplicable fields are `null`. When package and case validation discover the same root failure, ATT emits one diagnostic with `occurrences` and, when applicable, an `affectedCases` list; `summary.errors` counts unique diagnostics while `summary.errorOccurrences` preserves the raw occurrence count. Codes are stable; automation must not parse human messages.

ATT 3.3.0 may also include `summary`, `detail`, `source`, `context`, and `schemaViolations`. `source` holds physical YAML or payload `line`, `column`, `endLine`, and `endColumn`; the top-level `row` and `column` continue to identify an Excel cell. For single-line plain or directly quoted YAML scalars, an expression syntax error points to its character. Folded, multiline, or escaped scalars use the YAML scalar range when an exact mapping is unavailable. Every schema violation retains its own path, keyword, message, and physical source. `context` may contain the Case, Stage, Flow ID, and nested call chain. Expression syntax details identify the containing tool-call argument (for example, `logFiles`), the unexpected token, and a bounded caret excerpt when it is safe to show; source excerpts are omitted when the field or line may contain credentials or secrets.

Runtime Action failures preserve the same structure in Case YAML, `run.yaml`, regenerated reports, CI JSON, and JUnit failure detail. A nested Flow failure identifies the inner `flow.yaml` and Action while the call chain identifies how the Template reached it. Tool and DB evidence adds attempts, timeout, parse/capture, parameter binding, and cancellation details where available. File save failures include the configured path and allowed artifact root.

### Generated-output schema summary

| Artifact | Required top-level contract |
|---|---|
| `run.yaml` | `schemaVersion`, `att`, `runtime`, `run`, `validation`, `inputs`, `cases`, `summary`, `outputs` |
| Validation JSON | `schemaVersion`, `attVersion`, `valid`, `mode`, `summary`, `diagnostics` |
| CI summary JSON | `schemaVersion`, `attVersion`, `runId`, `environment`, `startedAt`, `endedAt`, `status`, `summary`, `durationStatistics`, `cases`, `diagnosticCounts`, `report`, `inputManifestHash` |
| JUnit XML | one testsuite with test/failure/error/skipped counts and one testcase per ATT case |

Generated envelopes reject additional top-level fields according to their schemas. JUnit HTML is a human-readable output and not an XML/JSON schema artifact.
