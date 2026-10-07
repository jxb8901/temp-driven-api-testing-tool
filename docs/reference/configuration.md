# Configuration and environments

This page is the authoritative reading reference for author-authored configuration. Files in [`schemas/`](../../schemas/) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

## Find a configuration task

| Task | Start here |
|---|---|
| Choose SIT, UAT, or another resource profile | [Select an environment profile](#select-an-environment-profile) |
| Set output, Run IDs, or report defaults | [Set package-wide options](#set-package-wide-options) |
| Find the owner of a Tool or helper contract | [Configuration owners](#configuration-owners) |
| Migrate or validate a schema | [Schema catalog](#schema-catalog) and [Migration notes](appendices/migrations.md) |

## Configuration layers and precedence

| Layer | Source | Owns |
|---|---|---|
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, global tools, group paths, DB/MQ/HTTP/SSHHelper paths, optional legacy inline SSH |
| Tool group | configured YAML path | group identity, optional script/SSH, grouped tools |
| Dbhelper | configured `dbhelpers` YAML path | one database identity, connection, statement timeout, transaction, limits, and evidence policy |
| SSHHelper | configured `sshhelpers` YAML path | logical SSH ID, physical instances, defaults, selection and fan-out cap |
| HTTPHelper | configured `httphelpers` YAML path | logical HTTP ID, base URL, defaults, pool, auth and TLS |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

[Reliability](reliability-execution-control.md) defines timeout/retry precedence and eligibility. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

## Ignore or disable ATT-owned configuration with `x-`

Prefix an optional ATT-owned field or an entry in an ATT-owned keyed collection with the exact lowercase `x-` to make it behave as absent. This applies to current config objects and keyed collections such as `tools`, environment profiles, Tool `arguments` declarations, `actions`, Action `evidence` collectors, report columns, and Debug Tool overrides. The YAML must still parse, but ATT does not schema-check, resolve, discover, evaluate, instantiate, execute, or publish a disabled entry. For a keyed collection, use this on the key. This does not disable or rename argument values supplied when invoking a Tool:

```yaml
schemaVersion: att-config/v2.12
x-debug-note: "#{missing.tool()}"      # ignored config field
tools:
  x-temporary: not-a-tool               # ignored Tool entry
  smoke:
    name: Smoke check
    description: Check the local setup
    call: "#{upper('ok')}"
    x-retry: 0                           # ignored optional Tool field
```

The same rule applies to Actions and evidence collectors. A disabled Action is absent from validation and runtime; a disabled collector is not called or included in evidence. Nested `x-` fields in ATT-owned blocks are also ignored:

```yaml
actions:
  x-preview:
    type: tool
    call: "#{missing.tool()}"
  verify:
    type: tool
    call: "#{upper('ok')}"
    retry: {maxAttempts: 2, intervalMs: 0, retryOn: [TIMEOUT], x-note: ignored}
    evidence:
      x-snapshot: not-a-collector
```

Disabled entries do not supply required fields: `x-schemaVersion` cannot replace `schemaVersion`, and `x-type` cannot replace an Action's required `type`. An `X-` uppercase prefix is not special and is validated as an ordinary key. A live reference to a disabled Action is unresolved, because that Action is absent. `runWhen: "#{false}"` is different: it is a valid, present Action that evaluates to `false` and produces the normal `SKIPPED` result.

Do not use this prefix to remove keys from user data. Keys in HTTP headers, `EXEC.INPUT`, `EXEC.VARS`, arbitrary maps, DB `params`/`parameters`, and Tool invocation argument values remain data and retain their names. For example, `x-correlation-id` is still an HTTP header or input key unless it is itself the key of an ATT-owned configuration collection.

## Select an environment profile

`att-config/v2.12` is the active profile contract. Profiles can replace configured DBHelper, MQHelper, SSHHelper, HTTPHelper and testdata descriptor lists as a whole. See the resource pages and [Testdata registry and input mapping](test-authoring.md) for each binding.

ATT selects an environment through one common `att-config/v2.12` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

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

The common config keeps the existing templates, testcase roots, run/execution/report settings, `toolGroups`, and global `tools` registry. The profile layer contains typed DB/MQ/SSH/HTTP descriptor lists. This profile example shows DB/MQ bindings:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.12
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
    testdata: [config/testdata/accounts.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Use the executable complete configs in `config/environments/sit.yaml` and `config/environments/uat.yaml` as the migration source for the common registry, including `invokePaymentApi` and the `sample.getAcDate` tool used by `examples/load/closed-smoke.yaml`. Do not replace that shared registry with `tools: {}` or `toolGroups: []` in a real package.

The SIT and UAT DBHelper descriptors both use `id: orders`, while their JDBC URL and other physical connection details differ. The MQHelper descriptors both use `id: payment`, while host, queue manager, port, and channel differ. A complete descriptor pair, including pool settings and safe evidence policy, is in [`examples/environments/README.md`](../../examples/environments/README.md).

The Action definitions remain identical:

```yaml
actions:
  prepareRequest:
    type: assign
    name: requestText
    expression: "&{templates/payment/request.json}"

  queryOrder:
    type: tool
    call: >-
      #{db.orders.query(
        sql='select * from orders where order_id = ?',
        params=[${EXEC.INPUT.orderId}]
      )}

  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        payload=${EXEC.VARS.requestText},
        responseFormat='xml',
        waitMs=5000
      )}
```

`environment` is the default profile name. A case-insensitive `--env` selector overrides it. Each profile may replace configured resource descriptor lists as a whole; omitted lists inherit the common list. No generic recursive YAML merge is performed. Unknown profile names fail before validation or external execution. Use the same package with every supported execution mode:

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

Use profiles when the same test package is promoted across environments and only infrastructure bindings change. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. See [Migration Notes](appendices/migrations.md) for complete-config migration.

## Schema catalog

[`schemas/catalog.yaml`](../../schemas/catalog.yaml) is authoritative for active schema registrations. Package validation checks registrations; archived schemas do not become active runtime contracts. See the complete matrix in [Schema and Version Matrix](appendices/schema-matrix.md).

## Set package-wide options

```yaml
schemaVersion: att-config/v2.12
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
execution:
  runIdFormat: "run-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
  debugIdFormat: "debug-${META.TARGET.type}-${META.TARGET.id}-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
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
    testdata: [config/testdata/accounts.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    testdata: [config/testdata/accounts.yaml]
```

### Set runtime and package paths

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | Current: `att-config/v2.12`; the previous schema remains compatible. The example uses the active schema. |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty default profile name when `environments` is present; otherwise exposed metadata only |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `execution.runIdFormat` | unset | Optional expression for the outer Run and Load ID; exact `--run-id` takes precedence |
| `execution.debugIdFormat` | unset | Optional expression for standalone Debug ID; exact `--debug-id` takes precedence |
| `execution.processOutput.memoryLimitBytes` | `65536` | Integer 1024–1048576; in-memory head/tail preview per stdout/stderr stream |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Integer from `memoryLimitBytes` through 1073741824; maximum bytes streamed to each process artifact |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |

`runIdFormat` generates the outer Run ID and, when Load has no exact `--run-id`, the outer Load ID. It does not affect Load's per-iteration `execution.execIdFormat` (`EXEC.ID`). `debugIdFormat` generates the standalone Debug directory identity. When no format is configured, the existing timestamp Run/Load default and `<type>-<targetId>` Debug default remain in effect.

Formats are evaluated once before their identity or output directory is published. Run and Load formats can read `META.SOURCE.type/path` and `EXEC.RUN_STARTED_AT`; they cannot read `EXEC.STARTED_AT`, which is captured separately for each Case or Load iteration. ATT uses the same enclosing start timestamp in the generated Run/Load ID and each runtime Context's `EXEC.RUN_STARTED_AT`. Debug formats can read `META.TARGET.type/id`, `EXEC.STARTED_AT`, and `EXEC.RUN_STARTED_AT`; both timestamps represent the standalone Debug start. Pure identity-format built-ins and `date.sysdate` / `date.systimestamp` are available; external calls, random/sequence functions, filesystem calls, and invocation state are not. The identity being created is unavailable: references such as `${EXEC.RUN_ID}`, `${EXEC.ID}`, `${EXEC.OUTPUT_DIR}`, `${EXEC.VARS}`, or `${EXEC.ACTIONS}` are rejected. Generated values must already be valid single path segments; ATT does not sanitize them. Run/Load and configured or explicit Debug IDs fail on collision. The legacy default Debug ID keeps its timestamp collision suffix.

For example, a package can use timestamped identities with target-specific standalone Debug folders:

```yaml
execution:
  runIdFormat: "run-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
  debugIdFormat: "debug-${META.TARGET.type}-${META.TARGET.id}-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
```

Run and Load accept `--run-id <id>` as a literal override. Standalone Debug accepts `--debug-id <id>` as a literal override; it is not evaluated as an expression.

### Configure report output

| Path | Required/default | Constraints |
|---|---|---|
| `report.mode` | `append-to-copy` | `append-to-copy` or `none`; `none` skips result-workbook creation |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Supported keys: `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, `runTime`, `execId`; each value is a string column label |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |

### Configure Resource and environment registries

| Path | Required/default | Constraints |
|---|---|---|
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.6` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.2` YAML paths; normalized duplicates are rejected |
| `sshhelpers` | `[]` | Unique package-contained `att-sshhelper/v1.0` YAML paths |
| `httphelpers` | `[]` | Unique package-contained `att-httphelper/v1.1` YAML paths |
| `environments` | absent | Non-empty map of profile names; profiles may contain configured resource and testdata descriptor lists |
| `environments.<profile>.testdata` | `[]` | Unique package-relative YAML paths available to that selected environment |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |


Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `ssh`, `tools`, `environments`, `x-*` |
| `environments.<profile>` | `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `testdata`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `execution` | `runIdFormat`, `debugIdFormat`, `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |

See [Migration Notes](appendices/migrations.md) for removed configuration fields.

## Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; file-content expressions use one canonical, regular UTF-8 file within the package root and reject absolute paths, globs, dynamic locators and symlink escapes. Resource file inputs and outputs must remain within their documented safe roots. ATT normalizes and checks root containment before reads and writes.

## Topology and secrets

Topology may vary by descriptor and environment. Inject secrets through `${ENV:NAME}` where supported; never commit them or expose resolved values in META, reports or diagnostics. Missing required variables identify the field/name without printing the secret.

## Cross-mode consistency

Run, Validate, Debug, and Load resolve the selected environment through the same effective configuration. `--env` is not Action branching and does not create mode-specific helper IDs.

## Separate configuration files

Separate `--config config/environments/sit.yaml` and `uat.yaml` files remain useful when package roots, report policy, Tool topology, or other configuration intentionally differ. Use profiles when the package contract is shared and only resource bindings change.


## `config.report.fileNamePattern`

### Context and legal forms

`report.fileNamePattern` uses the unified expression engine with a dedicated non-Case scope. It has a dedicated configuration-local root, separate from EXEC:

| Placeholder | Value |
|---|---|
| `${suiteName}` | Source workbook basename with its final lowercase `.xlsx` suffix removed; for example, `testcase/payment_regression.xlsx` becomes `payment_regression` |

The configured string must reference `${suiteName}` explicitly, whether used as text interpolation or as a built-in argument. No other general non-runtime/configuration expression roots are defined. Bare `suiteName` inside a call is rejected. Legal examples include:

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(${suiteName})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${suiteName})})}.xlsx"
```

For `testcase/payment.xlsx`, the first example writes `output/<RunID>/workbooks/payment.result.xlsx`. `${suiteName}` is the physical workbook basename, not the sidecar `id`, Sheet/group ID, Case ID, or Run ID. Authors should keep the value a safe filename ending in `.xlsx`; avoid `/`, `\`, absolute paths, `..`, and platform-reserved names. Workbooks in different recursive directories that share the same basename resolve to the same default result filename, so package authors must avoid that collision.

### Illegal or unsupported forms

These values fail configuration loading because they do not reference `${suiteName}`:

```yaml
fileNamePattern: "result.xlsx"
fileNamePattern: "${RUN_ID}.result.xlsx"
fileNamePattern: "${WORKBOOK_ID}.result.xlsx"
```

No other configuration root or Runtime Context path is supported. Configured Tool calls are also unavailable in this scope. These forms are invalid:

```text
${RUN_ID}
${WORKBOOK_ID}
${ENVIRONMENT}
${EXEC.INPUT.caseId}
${EXEC.ID}
#{configuredTool()}
#{upper(${RUN_ID})}
```

A pattern such as `${suiteName}-${RUN_ID}.xlsx` is rejected; unknown references are never retained as literal output text. All documented built-ins are parsed by the same engine, including nested calls. Because the resulting text becomes a filename, prefer deterministic string transformations and avoid side-effecting filesystem built-ins, random values, path separators, absolute paths, `..`, and platform-reserved names.


## Configuration owners

| Contract | Semantic owner |
|---|---|
| Workbook / Sidecar / Snapshot | [Test Authoring](test-authoring.md) |
| Template / Flow / Action | [Test Authoring](test-authoring.md) / [Actions](actions.md) |
| Tool command, call, arguments | [Tool](resources/tools.md) |
| DB descriptor | [DBHelper](resources/dbhelper.md) |
| MQ descriptor | [MQHelper](resources/mqhelper.md) |
| HTTP descriptor | [HTTPHelper](resources/httphelper.md) |
| SSH descriptor | [SSHHelper](resources/sshhelper.md) |
| Timeout / Retry | [Reliability](reliability-execution-control.md) |
