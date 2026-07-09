# ATT System Design V1.4

**Version:** V1.4
**Status:** Draft
**Source:** V1.4 roadmap and user review comments
**Last Updated:** 2026-07-09

---

# 1. System Overview

ATT, short for Automated Testing Tool, provides a lightweight automation layer for API SIT/UAT testing.

V1.4 is a stabilization release on top of the V1.3 execution model.

The core runtime model remains:

- Excel suites drive case execution.
- Suite sidecar config defines how one workbook is parsed and executed.
- Stages are executed in configured order.
- Stage templates contain ordered actions.
- `${...}` references runtime context values.
- `#{...}` invokes tools.
- Tool execution remains external shell-script based.

V1.4 tightens the configuration boundary:

- `config.yaml` configures tools and optional global defaults such as environment, global log level, and common execution defaults.
- Testcase, stage, and report mapping live in the suite sidecar config.
- Every Excel suite must have a sidecar config.
- The sidecar config must define the Test Case Template, stages, report column mapping, and other suite-specific report settings.

V1.4 also clarifies workbook parsing and authoring support:

- Multi-row Excel headers are supported through `testcase.headerRows`.
- Human-entered invisible spaces are trimmed where they can break matching.
- Template cells are YAML objects.
- Chinese and mixed-language template names are supported.
- Case author reference manuals can be generated from configured testcase, template, and tool metadata.

---

# 2. V1.4 Design Goals

## 2.1 Make Suite Configuration Self-Contained

Each workbook should be understandable by reading the workbook and its sidecar config.

The sidecar config owns:

- sheet name
- header rows
- testcase data columns
- stage template columns
- testcase and stage YAML columns
- report columns
- suite log level
- Test Case Template binding

Global `config.yaml` may define tools, environment information, global log level, and other common default behavior.

Global `config.yaml` should not define testcase columns, stages, or suite-specific report column mapping.

## 2.2 Reduce Authoring Mistakes

Excel files often contain spaces that humans do not notice.

V1.4 requires necessary whitespace normalization to reduce failures caused by invisible spaces.

The goal is not to alter intentional business text, but to trim values used for matching and identifiers.

## 2.3 Keep Template Cells Structured

Stage template cell values are YAML.

This naturally supports:

- Chinese template names
- remarks
- comments
- extra metadata
- multi-line text

## 2.4 Clarify Context Data Composition

Case data is made available through the runtime Context.

V1.4 treats all case-related values as Context data sources instead of introducing separate per-case or per-stage data objects.

Ordinary testcase columns, testcase YAML columns, stage YAML columns, template cell metadata, action outputs, and tool outputs are all Context data sources.

Expressions resolve data through a defined Context lookup order.

Duplicate keys are allowed but must produce warnings when overwritten.

## 2.5 Improve Tool Discoverability

`att.sh` should provide a command to generate a reference manual for case authors.

The manual should be generated from configured testcase fields, stage fields, template metadata, and tool metadata.

---

# 3. Changes From V1.3

| Area | V1.3 | V1.4 |
|------|------|------|
| Global config | May provide testcase/stage/report defaults | Tools, environment, global log level, and common defaults |
| Suite sidecar | Recommended | Required |
| Stage model | Test Case Template owns stage list | Suite sidecar config owns stage columns and required rules |
| Stage template cell | Template name string | YAML object with `name` |
| Stage data | Per-case data plus optional stage-specific data | Stage YAML columns become stage-scoped Context data sources |
| Testcase columns | Mapped into fixed values/context | Simplified list syntax; values become Context data |
| Template/data columns | Could be ordinary columns | Stage parsing settings; normally not repeated in `testcase.columns` |
| Report columns | Global or template defaults possible | Suite sidecar testcase config |
| Log level | General design concept | Global config, suite sidecar, stage/action config, or `att.sh` override |
| Tool arguments | Lightweight contract | Kept as lightweight open syntax |
| Retry | Tool default plus action override | Kept, scoped to tool execution |

---

# 4. Terminology

| Term | Meaning |
|------|---------|
| Test Suite | One Excel workbook containing test cases |
| Test Suite Config | Sidecar YAML file beside the Excel workbook |
| Test Case Template | Reusable suite model selected by the sidecar |
| Testcase Column | Ordinary workbook column exposed in Context |
| Template Column | Stage-specific workbook column containing YAML template cell data |
| YAML Column | Workbook column whose cell value is parsed as YAML and exposed in Context |
| Stage | Named execution step configured in the sidecar |
| Stage Template | Template package selected by stage template cell `name` |
| Template Cell | YAML object in a stage template column |
| Context | Runtime data tree used by `${...}` expressions |
| Action ID | Stable identifier for a template action and its runtime context record |
| Tool Argument Contract | Lightweight declaration of common supported named parameters |
| Tool Retry Policy | Retry count and retryable exit codes for one tool execution |

---

# 5. Test Suite And Sidecar Configuration

V1.4 centers testcase parsing and stage execution around the suite sidecar config.

Example files:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
```

The sidecar config is required.

## 5.1 Required Shape

The sidecar must configure:

- `testCaseTemplate`
- `testcase`
- `testcase.report`
- `stages`

Example:

```yaml
testCaseTemplate: payment_transfer_cases

testcase:
  sheet: 測試案例
  headerRows: 2
  yamlColumns: data=案例資料, 更多測試數據
  logLevel: INFO
  columns: 案例編號, 案例名稱, 標籤, 金額, 扣帳帳號, 幣種, creditAcNo=入帳帳號
  report:
    columns:
      result: 測試結果
      durationMs: 耗時(ms)
      actualResult: 實際結果
      caseLog: 案例日誌

stages:
  prepare:
    templateColumn: 準備模板
    yamlColumns: 準備資料
    required: false
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
  verify:
    templateColumn: 驗證模板
    yamlColumns: 驗證資料
    required: true
```

## 5.2 Config Ownership

`config.yaml` should configure tools and optional global defaults.

It may configure:

- tool definitions
- environment information
- global log level
- common execution defaults

It should not configure:

- testcase columns
- stages
- report columns
- suite-specific report mapping

Those settings belong to the suite sidecar.

## 5.3 Excel Workbook Model

The Excel workbook may use localized names.

Examples:

- workbook `付款回歸測試.xlsx`
- sheet `測試案例`
- column `案例編號`
- template name `中文支付調用模板`

Workbook display labels are matched exactly after necessary trimming.

## 5.4 Multi-Row Header Model

`testcase.headerRows` defines how many top rows are treated as headers.

Default:

```yaml
testcase:
  headerRows: 1
```

For stacked headers:

```yaml
testcase:
  headerRows: 2
```

The effective column label is resolved from the last non-empty cell within each header column.

Example:

| 基本資料 | 基本資料 | 模板資料 | 模板資料 |
|----------|----------|----------|----------|
| 案例編號 | 案例名稱 | 調用模板 | 驗證模板 |

Effective labels:

- `案例編號`
- `案例名稱`
- `調用模板`
- `驗證模板`

## 5.5 Whitespace Normalization

ATT should trim necessary whitespace during parsing to avoid failures caused by invisible user-entered spaces.

Trim should apply to matching-oriented values:

- header labels
- configured column labels
- stage keys
- template names
- tool names
- argument names
- action IDs
- required/optional markers in `arguments`

ATT should not blindly trim intentional business text inside YAML scalar values unless that value is used as an identifier or matching key.

## 5.6 Testcase Columns

`testcase.columns` defines ordinary testcase data columns.

It uses a compact comma-separated syntax.

Each item may be either:

- a workbook column label
- an alias mapping in the form `contextKey=workbookColumnLabel`

Example:

```yaml
testcase:
  columns: 案例編號, 金額, 扣帳帳號, 幣種, creditAcNo=入帳帳號
```

If the row contains:

| 案例編號 | 金額 | 扣帳帳號 | 幣種 | 入帳帳號 |
|----------|------|----------|------|----------|
| TC001 | 100 | 123456789 | HKD | 987654321 |

Context receives:

```yaml
案例編號: TC001
金額: "100"
扣帳帳號: "123456789"
幣種: HKD
入帳帳號: "987654321"
creditAcNo: "987654321"
```

The same cell may therefore be referenced by either the original column label or the configured alias:

```text
${入帳帳號}
${creditAcNo}
```

Both expressions resolve to the same value.

`templateColumn` and `yamlColumns` are parsing settings.

They do not need to be repeated in `testcase.columns`.

If a project intentionally also maps those columns under `testcase.columns`, they are treated as ordinary Context data columns and duplicate-key warning rules still apply.

## 5.7 Testcase YAML Columns

`testcase.yamlColumns` is optional.

It configures one or more workbook columns whose cell values are parsed as YAML.

It uses the same compact syntax as `testcase.columns`.

Each item may be either:

- a workbook column label
- an alias mapping in the form `contextKey=workbookColumnLabel`

Example:

```yaml
testcase:
  yamlColumns: data=其它測試數據, 更多測試數據
```

Cell `其它測試數據`:

```yaml
payment:
  channel: ATM
  expected:
    status: SUCCESS
```

Cell `更多測試數據`:

```yaml
risk:
  level: LOW
```

An aliased YAML column is exposed under `Case.<alias>`.

An unaliased YAML column is exposed by its original column label.

Therefore, the YAML data above may be referenced as:

```text
${Case.data.payment.channel}
${Case.data.payment.expected.status}
${更多測試數據.risk.level}
```

YAML comments and Chinese keys/values are supported.

## 5.8 Duplicate Context Keys

When multiple sources write the same key, later values override earlier values and ATT must emit a warning.

Typical sources:

- `testcase.columns`
- `testcase.yamlColumns`
- `stages.<stage>.yamlColumns`
- stage template cell metadata
- action output data

Warnings should be visible in diagnostics and, depending on log level, in the case log.

## 5.9 Stage Column Model

`stages.<stage>` defines where a stage reads its template and optional data.

Each stage must configure:

- `templateColumn`

Each stage may configure:

- `yamlColumns`
- `required`
- `logLevel`

Example:

```yaml
stages:
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
    logLevel: DEBUG
```

`templateColumn` must be configured.

`yamlColumns` may be omitted.

`required` controls whether the stage template cell must be non-empty.

Values such as `NA`, `N/A`, `n/a`, and blank strings are treated as empty template cells rather than template names.

If the stage is required, these empty values should fail the case with a clear message.

If the stage is not required, these empty values may skip the stage.

## 5.10 Stage YAML Columns

`stages.<stage>.yamlColumns` is optional.

If configured, each referenced cell value is parsed as YAML and exposed as stage-scoped Context data.

It supports the same compact syntax:

```yaml
stages:
  invoke:
    templateColumn: 調用模板
    yamlColumns: invokeData=調用資料, 更多調用資料
```

Example:

```yaml
timeoutProfile: long
expected:
  status: SUCCESS
```

The resulting data participates in normal Context lookup while the stage is executing.

## 5.11 Report Configuration

Report configuration is defined in the testcase sidecar config.

At minimum, the sidecar should define report column mapping.

It may also define other suite-specific report settings such as report file name pattern, output style, or trace fields.

Example:

```yaml
testcase:
  report:
    fileNamePattern: "${suiteName}.result.xlsx"
    columns:
      result: 測試結果
      durationMs: 耗時(ms)
      actualResult: 實際結果
      caseLog: 案例日誌
```

Suite-specific report configuration should not be defined in `config.yaml`.

---

# 6. Template Cell Model

Stage template cells are YAML.

The minimum shape is:

```yaml
name: PAYMENT_PRECHECK
```

With Chinese name and remark:

```yaml
name: 中文支付調用模板
remark: |
  第一行備註
  第二行備註
```

Because the template cell is YAML, it naturally supports:

- Chinese template names
- Chinese remarks
- YAML comments
- multi-line remarks
- extra metadata

`name` is the actual template name.

`name` must be trimmed before lookup.

`remark` is human-facing metadata.

All parsed template cell data may be injected into context and may be emitted into the case log.

Recommended context shape:

```text
STAGES.<stageKey>.template.name
STAGES.<stageKey>.template.remark
STAGES.<stageKey>.template.<metadataKey>
```

---

# 7. Case Runtime Context

The runtime context contains:

- testcase column values
- testcase YAML column values
- current stage YAML column values
- stage template cell data
- current stage metadata
- action outputs
- tool inputs and outputs
- run paths
- case log path

Preferred action references remain:

```text
${ACTIONS.<ActionID>.output}
${ACTIONS.<ActionID>.outputFile}
${ACTIONS.<ActionID>.rawOutput}
```

`TOOLS.<ActionID>` may remain as a legacy alias.

---

# 8. Context Data Lookup

## 8.1 Data Sources

V1.4 treats all case-related values as Context data sources instead of defining separate per-case or per-stage data objects.

All case-related data is available through Context.

Typical Context data sources are:

- ordinary cells configured by `testcase.columns`
- YAML cells configured by `testcase.yamlColumns`
- YAML cells configured by `stages.<stage>.yamlColumns`
- parsed stage template cell metadata
- action outputs
- tool inputs and outputs
- run and case metadata

Example:

```yaml
testcase:
  columns: 案例編號, 金額, creditAcNo=入帳帳號
  yamlColumns: data=其它測試數據, 更多測試數據
```

Supported references:

```text
${案例編號}
${金額}
${入帳帳號}
${creditAcNo}
${Case.data.a.b.c}
${更多測試數據.d.e.f}
```

## 8.2 Lookup Order

Expressions follow a consistent Context lookup order.

Recommended lookup order for unqualified names:

- current stage YAML column data
- current stage template cell metadata
- testcase YAML column data
- testcase ordinary column data
- run-level metadata
- global defaults

Fully qualified references are preferred when ambiguity is possible.

Examples:

```text
${Case.data.payment.expected.status}
${STAGES.invoke.template.remark}
${ACTIONS.callApi.output.Response.Status}
```

When multiple sources expose the same key in the same scope, the later source may overwrite the earlier value and ATT must emit a warning.

## 8.3 YAML Cell Rules

YAML columns support:

- comments
- Chinese keys and values
- nested objects
- lists
- multi-line scalar values

Example YAML cell:

```yaml
payment:
  channel: ATM
  expected:
    status: SUCCESS
```

---

# 9. Stage And Template Execution Model

The suite sidecar config defines the stage keys and stage columns.

The Excel row decides which stage template to use through the stage `templateColumn`.

Execution flow:

```text
Load suite sidecar
  -> Resolve workbook sheet and headers
  -> Load testcase columns into Context
  -> Parse testcase yamlColumns into Context
  -> For each configured stage
       -> Read templateColumn
       -> Parse template cell YAML
       -> Parse optional stage yamlColumns into Context
       -> Resolve stage template by template.name
       -> Execute ordered actions
```

If a required stage has an empty template cell, the case should fail with a clear error.

If a non-required stage has an empty template cell, the stage may be skipped.

---

# 10. Template Action Model

V1.4 keeps ordered template actions.

Example:

```yaml
actions:
  renderRequest:
    type: render
    payload: request.xml
    saveAs: request.xml

  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"

  checkStatus:
    type: assert
    expression: "${ACTIONS.callApi.output.Response.Status} == '${expected.status}'"
```

Action IDs remain stable context keys.

---

# 11. Tool Configuration Model

`config.yaml` should configure tools and optional global defaults.

It may configure environment information, global log level, and common execution defaults.

It should not configure testcase parsing, stage parsing, or suite-specific report column mapping.

Example:

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments: "requestFile*, environment='SIT', traceId"
    retry:
      count: 2
      exitCode: [1, 2]
```

## 11.1 Tool Arguments

`tools.<toolName>.arguments` is a lightweight, open parameter contract.

It is not a typed schema.

It is not a closed whitelist.

Recommended syntax:

```yaml
arguments: "requestFile*, environment='SIT', traceId"
```

Meaning:

| Item | Meaning |
|------|---------|
| `requestFile*` | Required known parameter |
| `environment='SIT'` | Optional known parameter with default value |
| `traceId` | Optional known parameter |

Undeclared parameters may still appear in template calls.

## 11.2 Tool Retry

Tool retry policy applies only to tool execution attempts.

Retry may be triggered by specific exit codes defined by the runtime.

Minimum fields:

- `count`
- `exitCode`

Tool definitions may provide defaults.

Action calls may override the retry policy.

Example:

```yaml
actions:
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
    retry:
      count: 4
      exitCode: [1, 2]
```

Retry does not rerun the whole stage or whole case.

---

# 12. Log Level Model

V1.4 log levels use a detail-filter model.

There is still one case log path:

```text
output/<RunID>/<CaseID>/<CaseID>.001.log
```

`RunID` belongs in the directory layer.

The file name uses `CaseID` plus a sequence number so multiple logs from the same case are easy to order without repeating `RunID`.

Log level controls how much detail is written.

Recommended levels:

| Level | Detail |
|-------|--------|
| `ERROR` | Failures, timeout, retry exhaustion, assertion failure |
| `INFO` | Case/stage/action summary, status, duration, output file paths |
| `DEBUG` | Full command, input, stdout, stderr, parsed output, context details |

Log level may be configured globally in `config.yaml`.

Log level may also be configured in the suite sidecar at testcase or stage scope.

Action-level log level may be defined in template action config.

`att.sh` may specify log level for one run.

More specific scopes override broader scopes.

Recommended priority:

```text
att.sh command line
  > action template config
  > stage config
  > testcase sidecar config
  > global config.yaml
```

---

# 13. Case Execution Output And Report

V1.4 keeps the V1.3 output layout.

Case output:

```text
output/<RunID>/<CaseID>/
```

Action output:

```text
output/<RunID>/<CaseID>/<ActionID>/
```

Run-level result workbook:

```text
output/<RunID>/<suiteName>.result.xlsx
```

Run history:

```text
output/<RunID>/run.yaml
output/latest-run.yaml
```

---

# 14. Author Reference Manual Generation

`att.sh` should provide a command to generate a reference manual for case authors.

The manual should be generated from configured testcase, template, and tool metadata.

Recommended command shape:

```sh
./att.sh manual
```

The manual command should generate the full reference manual in one pass.

It should not require selecting specific suites, templates, or tools.

The generated manual should include:

- workbook sheet name
- header row count
- testcase columns
- testcase YAML columns
- configured stages
- stage template columns
- stage YAML columns
- required stage rules
- report columns
- log level defaults
- available template names
- tool names
- tool `arguments`
- tool retry settings

The manual is a documentation aid for case authors.

It should not be required for runtime execution.

---

# 15. Migration From V1.3

Recommended migration steps:

1. Add a sidecar YAML file beside every Excel workbook.
2. Move testcase columns, stage columns, and report mappings into the sidecar.
3. Remove testcase, stage, and suite-specific report examples from `config.yaml`.
4. Keep tool definitions and optional global defaults in `config.yaml`.
5. Replace stage template string cells with YAML cells containing `name`.
6. Move human notes into template cell `remark`.
7. Ensure `templateColumn` is configured for every stage.
8. Rename testcase YAML data settings from `dataColumn` to `yamlColumns`.
9. Convert `testcase.columns` mapping definitions to compact list syntax, using `alias=columnLabel` only when an alias is needed.
10. Avoid repeating `templateColumn` and `yamlColumns` in `testcase.columns` unless the project intentionally wants those raw cells exposed as ordinary Context data.
11. Review duplicate Context keys and warnings.
12. Generate an author reference manual for migrated suites.

---

# 16. Acceptance Criteria

- `config.yaml` configures tools and optional global defaults such as environment and global log level.
- Every Excel suite has a sidecar config.
- Every sidecar declares `testCaseTemplate`.
- Every sidecar defines `stages`.
- Every stage defines `templateColumn`.
- Stage `yamlColumns` is optional.
- Testcase `yamlColumns` is optional and may configure multiple YAML columns.
- `testcase.columns` uses compact list syntax and supports `alias=columnLabel`.
- One cell value may be referenced by both original column label and configured alias.
- `templateColumn` and `yamlColumns` are parsing settings and do not need to appear in `testcase.columns`.
- All configured testcase values are available through Context.
- Duplicate Context keys produce warnings when overwritten.
- Necessary whitespace is trimmed for matching-oriented values.
- `testcase.headerRows` defaults to `1`.
- Multi-row header resolution uses the last non-empty header cell per column.
- Template cells are YAML and use `name` as the actual template name.
- Template cell YAML supports Chinese name, remark, comments, and metadata.
- Stage template cell data is available in context and can be written to case log.
- Context lookup order defines how testcase data, stage YAML data, template metadata, action output, and tool output are resolved.
- Report columns are configured in the suite sidecar.
- Log level may be configured globally, in suite sidecar, in template action config, or through `att.sh`.
- Tool `arguments` uses lightweight open syntax and does not require types.
- Undeclared tool parameters may appear in runtime calls.
- Tool retry supports tool default and action override.
- Retry is scoped to tool execution attempts only.
- `att.sh` can generate an author reference manual from configured testcase/template/tools metadata.
- V1.4 design does not claim runtime implementation is already complete.
