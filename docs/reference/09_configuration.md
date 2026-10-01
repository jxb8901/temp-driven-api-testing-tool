## 09 Configuration and Environments

This chapter is the authoritative reading reference for author-authored configuration. The files below [`schemas/`](../../schemas/) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

### Configuration layers and precedence

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

[Reliability](08_reliability_execution_control.md) defines timeout/retry precedence and eligibility. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Multi-environment profiles in current ATT

`att-config/v2.10` is the active profile contract. Profiles can replace configured DBHelper, MQHelper, SSHHelper and HTTPHelper descriptor lists as a whole. See the resource chapters for each binding.

ATT selects an environment through one common `att-config/v2.10` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

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

The common config keeps the existing templates, testcase roots, run/execution/report settings, `toolGroups`, and global `tools` registry. The profile layer contains typed DB/MQ/SSH/HTTP descriptor lists; the example below shows DB/MQ bindings:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
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
        payload=${EXEC.ACTIONS.renderRequest.output.result},
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

Use profiles when the same test package is promoted across environments and only infrastructure bindings change. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. See [Appendix C](appendices/migrations.md) for complete-config migration.

### Schema catalog

[`schemas/catalog.yaml`](../../schemas/catalog.yaml) is authoritative for active schema registrations. Package validation checks registrations; archived schemas do not become active runtime contracts. See the complete matrix in [Appendix A](appendices/schema_matrix.md).

### Global configuration

```yaml
schemaVersion: att-config/v2.10
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
| `schemaVersion` | required | Current: `att-config/v2.10`; older configuration versions are not active contracts. The example uses the active schema. |
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
| `report.columns` | `{}` | Supported keys: `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, `runTime`, `execId`; each value is a string column label |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.6` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.2` YAML paths; normalized duplicates are rejected |
| `sshhelpers` | `[]` | Unique package-contained `att-sshhelper/v1.0` YAML paths |
| `httphelpers` | `[]` | Unique package-contained `att-httphelper/v1.1` YAML paths |
| `environments` | absent | Non-empty map of profile names; profiles may contain configured resource descriptor lists |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `ssh`, `tools`, `environments`, `x-*` |
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

See [Appendix C](appendices/migrations.md) for removed configuration fields.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render glob matches remain below the template and resource file inputs and outputs must remain below their documented safe roots. ATT normalizes and checks root containment before reads and writes.

### Topology and secrets

Topology may vary by descriptor and environment. Inject secrets through `${ENV:NAME}` where supported; never commit them or expose resolved values in META, reports or diagnostics. Missing required variables identify the field/name without printing the secret.

### Cross-mode consistency

Run, Validate, Debug, and Load resolve the selected environment through the same effective configuration. `--env` is not Action branching and does not create mode-specific helper IDs.

### Separate configuration files

Separate `--config config/environments/sit.yaml` and `uat.yaml` files remain useful when package roots, report policy, Tool topology, or other configuration intentionally differ. Use profiles when the package contract is shared and only resource bindings change.


### `config.report.fileNamePattern`

#### Context and legal forms

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

#### Illegal or unsupported forms

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


### Feature configuration owners

| Contract | Semantic owner |
|---|---|
| Workbook / Sidecar / Snapshot | [Test Authoring](02_test_authoring.md) |
| Template / Flow / Action | [Test Authoring](02_test_authoring.md) / [Actions](14_actions.md) |
| Tool command, call, arguments | [Tool](05_resources/tools.md) |
| DB descriptor | [DBHelper](05_resources/dbhelper.md) |
| MQ descriptor | [MQHelper](05_resources/mqhelper.md) |
| HTTP descriptor | [HTTPHelper](05_resources/httphelper.md) |
| SSH descriptor | [SSHHelper](05_resources/sshhelper.md) |
| Timeout / Retry | [Reliability](08_reliability_execution_control.md) |
