# ATT V2.1 Reference Manual

Author: Jeffrey + ChatGPT
Version: 2.1
Status: Normative end-user reference

This is the complete English reference for ATT V2.1. It combines the user manual and configuration reference and describes the V2.1 contract for authoring, validation, execution, evidence, reports, CI outputs, documentation, and packaging. Where an older V2.0 example conflicts with this manual, this manual wins.

ATT is an offline, template-driven API test runner. A workbook row becomes a test case; ordered stages select templates; ordered actions render files, invoke tools, assert results, or write structured logs.

    test case --1:n stages--> template --1:n actions--> tool

Test cases, templates, and tools are the primary concepts. A stage belongs to a test case and controls template selection and order. An action belongs to a template and controls operations and tool invocation order.

## Table of contents

1. [Design principles](#1-design-principles)
2. [Release layout and CLI](#2-release-layout-and-cli)
3. [Global configuration](#3-global-configuration)
4. [Workbook sidecar](#4-workbook-sidecar)
5. [Stages and template selection](#5-stages-and-template-selection)
6. [Templates and actions](#6-templates-and-actions)
7. [Runtime Context](#7-runtime-context)
8. [Expressions and built-in functions](#8-expressions-and-built-in-functions)
9. [Tool configuration and execution](#9-tool-configuration-and-execution)
10. [End-to-end development example](#10-end-to-end-development-example)
11. [Validation, diagnostics, reports, CI, and packaging](#11-validation-diagnostics-reports-ci-and-packaging)
12. [Best practices and troubleshooting](#12-best-practices-and-troubleshooting)
13. [Unicode, paths, security, and run lifecycle](#13-unicode-paths-security-and-run-lifecycle)
14. [Complete command reference](#14-complete-command-reference)
15. [Frequently asked questions](#15-frequently-asked-questions)

## 1. Design principles

V2.1 continues the established ATT terms, command/output tool model, and ordered stage/template/action execution model. It deliberately avoids compatibility aliases and unrelated workflow-engine abstractions. V2.1 hardens the V2 model with strict schemas, package-wide validation, atomic run publication, stable diagnostics, trustworthy result aggregation, JSON/expanded XML parsing, retry evidence, and CI-native outputs.

The primary ownership rule is:

- testcase owns ordered stages;
- stage selects a template and contributes stage-private data;
- template owns ordered actions;
- action invokes a tool or performs render, assertion, or logging;
- tool is a reusable external capability configured once globally.

Configuration is explicit and strict. Every workbook has a mandatory sidecar; templates are directories identified by template.yaml; all schema-controlled mappings reject unknown non-`x-*` fields; validation reports errors before tools execute; and the persisted runtime tree has one CASE root.

Related sections: [Workbook sidecar](#4-workbook-sidecar), [Stages and template selection](#5-stages-and-template-selection), [Templates and actions](#6-templates-and-actions), and [Runtime Context](#7-runtime-context).

## 2. Release layout and CLI

An ATT package contains `att.sh`, `config/config.yaml`, `testcase/`, `templates/`, `tools/`, `schemas/`, and `output/`. This manual focuses on the user-visible files and commands needed to author and execute API tests.

    ./att.sh help
    ./att.sh validate --package
    ./att.sh validate --selected --case payment.TC001
    ./att.sh validate --package --format json
    ./att.sh run --all
    ./att.sh run --suite testcase/payment_regression.xlsx --tag smoke
    ./att.sh run --all --case payment.TC001 --run-id SIT-001
    ./att.sh run --all --dry-run
    ./att.sh report --run-id SIT-001
    ./att.sh docs
    ./att.sh build
    ./att.sh clean

Selection options include --suite, --suite-dir, --all, --case, --tag, and --exclude-tag. Validation scope options are --package and --selected. Execution options include --run-id, --output-dir, --dry-run, --fail-fast, --rerun-failed, and --ci-output junit,json. Output options include --format human|json, --quiet, and --verbose.

Exit codes are 0 for a successful command/run without FAIL, ERROR, or INVALID; 1 for one or more FAIL results and no ERROR/INVALID; 2 for CLI/configuration/validation/INVALID failures; and 3 for one or more ERROR results or an unrecoverable runtime failure. A run containing both FAIL and ERROR exits 3.

### 2.1 Commands

| Command | Purpose | Runs external tools? |
|---|---|---:|
| help | Show syntax and options; also the default when no command is supplied | No |
| version | Print the ATT version | No |
| validate | Validate selected workbooks, sidecars, templates, actions, and tools | No |
| run | Validate and execute selected cases | Yes, except with --dry-run |
| docs | Generate testcase/template/tool HTML documentation | No |
| report | Regenerate HTML for a persisted run | No |
| build | Archive the latest completed run | No |
| clean | Remove ATT-generated output | No |

### 2.2 Selection behavior

- `--all` discovers every `.xlsx` file under `testcase/` and requires its adjacent sidecar.
- `--suite <xlsx>` selects one workbook and may be repeated.
- `--suite-dir <dir>` discovers workbooks in another directory.
- `--case <group.caseId>` selects a full Case ID; the unqualified row ID is not sufficient.
- `--tag <tag>` includes cases having at least one requested tag.
- `--exclude-tag <tag>` removes matching cases after inclusion filters.
- If filters select no cases, ATT reports an error rather than silently producing an empty PASS run.
- `--rerun-failed` reads the latest persisted run and selects its FAIL/ERROR cases; it requires existing run history.

### 2.3 Validation, execution, and presentation behavior

`validate --package` is the default and validates the whole package, including unreferenced templates and tools. `validate --selected` validates only the dependency closure of the explicit selection and reports that the rest of the package was not checked. `run` always uses selected-scope validation for its immutable execution plan.

`--dry-run` validates and records selected cases as SKIPPED without invoking tools. `--fail-fast` stops scheduling further cases after the first FAIL or ERROR. `--run-id` overrides the timestamp ID, must pass the ID safety rules in Section 13, and directly becomes the final run-directory name. `--output-dir` overrides the global output root for that command. `--ci-output junit,json` writes the requested CI files below the completed run. `--format json` emits machine-readable command output. Normal human output reports all four phases: validation, selection, execution, and completion. `--quiet` suppresses these progress and completion messages. `--verbose` additionally reports safe run, suite, case, stage, and action lifecycle metadata including IDs, status, and duration; it never prints payloads, parsed output, commands, arguments, environment variables, stdout, or stderr. `--quiet` and `--verbose` cannot be combined.

For `validate --format json`, stdout contains exactly one JSON document; progress and human diagnostics are written to stderr. All command options are command-specific: an option that is unknown or not valid for the selected command is an error.

For pre-execution checks and generated outputs, see [Validation, diagnostics, reports, CI, and packaging](#11-validation-diagnostics-reports-ci-and-packaging).

## 3. Global configuration

config/config.yaml owns runtime defaults, template root, report defaults, run ID behavior, XML namespace mode, and tool contracts. It must not define workbook sheets, case ID columns, data columns, or stages.

`schemaVersion: att-config/v2.1` is mandatory. Supported top-level fields are only `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `templates`, `run`, `report`, `xml`, `tools`, and extension fields prefixed with `x-`. Unknown non-extension fields are validation errors. In particular, V2.0's `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.1 fields.

    schemaVersion: att-config/v2.1
    outputDirectory: output
    environment: SIT
    timeoutMs: 120000
    templates:
      root: templates
    report:
      mode: append-to-copy
      fileNamePattern: "${suiteName}.result.xlsx"
      junit:
        caseLogEmbedThresholdBytes: 10240
    xml:
      namespaceMode: ignore
    tools:
      invokePaymentApi:
        name: Invoke Payment API
        description: Invoke a rendered payment request
        command: "./tools/invoke_payment_api.sh --input '${TOOL.inputFile}' --output '${TOOL.outputFile}'"
        output: json
        arguments:
          requestFile:
            name: Request File
            description: Rendered request file
            required: true
          environment:
            name: Environment
            description: Target environment
            required: true

Tool arguments are metadata for validation and generated tool reference pages. Each argument requires name, description, and required; only the final argument may define delimit. `command` is tokenized by ATT and is not evaluated by a shell. For the complete tool contract, command placeholders, argument validation, output parsing, delimited argument behavior, special-character behavior, and invocation examples, see [Tool configuration and execution](#9-tool-configuration-and-execution).

### 3.1 Global field reference

| Field | Required | Default | User-visible effect |
|---|---:|---|---|
| schemaVersion | Yes | — | Exactly `att-config/v2.1` |
| outputDirectory | No | output | Relative package path; root for runs, `.in-progress`, and latest-run history |
| environment | No | SIT | Available as `${CASE.environment}` and commonly passed to tools |
| timeoutMs | No | 120000 | Maximum duration for each external tool process, in milliseconds; range 1–86400000 |
| run.id.default | No | timestamp | Must be `timestamp` |
| run.id.timestampFormat | No | yyyyMMdd-HHmmss | Java date/time format used for timestamp run IDs |
| templates.root | No | templates | Root searched recursively for template directories |
| report.mode | No | append-to-copy | Result-workbook write mode |
| report.fileNamePattern | No | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| report.columns | No | Standard English labels | Default result-workbook column labels |
| report.junit.caseLogEmbedThresholdBytes | No | 10240 | Maximum UTF-8 case-log bytes embedded in JUnit `system-out`; 0 always links externally |
| xml.namespaceMode | No | ignore | `ignore` uses local XML names; `preserve` uses Clark notation |
| tools | No | Empty map | Global external tool contracts available to template actions |

Global paths are package-relative. Keep outputDirectory outside templates and testcase so generated output is not rediscovered as source content. All controlled mappings reject unknown fields, wrong scalar types, duplicate YAML keys, unsafe YAML tags, and invalid enum values. Only `x-*` fields may be preserved without ATT interpretation.

### 3.2 Precedence and ownership

Global configuration provides runtime and report defaults. The workbook sidecar owns `excel`, ordered `stages`, and workbook-specific report-column overrides. Command-line `--output-dir`, `--run-id`, and selection options override their applicable runtime defaults. ATT never invents a missing sheet, case ID column, tags column, data mapping, or stage list.

## 4. Workbook sidecar

Every workbook requires an adjacent same-basename sidecar. For payment_regression.xlsx, ATT requires payment_regression.yaml in the same directory.

    excel:
      sheet: payment=支付測試案例集, batch=批量測試案例集
      headerRows: 2
      caseId: 案例編號
      tags: 標籤
      dataColumns: caseName=案例名稱, amount=金額, 預期結果(yaml)

    stages:
      - key: invoke
        template: 執行模板
        dataColumns: channel=渠道, 執行參數(yaml)
        required: true
        onFailure: stop
        runWhen: normal

In V2.1 the first field is mandatory:

```yaml
schemaVersion: att-sidecar/v2.1
```

excel.sheet accepts comma-separated groupId=sheetName entries. With one sheet, the group ID may be omitted and ATT uses default. The full Case ID is always groupId.rowCaseId and must be unique across the workbook.

excel.caseId and excel.tags are mandatory structural columns. tags is outside dataColumns; blank tags are valid. V2 has no configurable required-column list.

dataColumns supports alias=ColumnName, ColumnName, alias=ColumnName(yaml), and ColumnName(yaml). Double quotes protect commas, equals signs, and parentheses. YAML cells retain maps, lists, numbers, booleans, and strings.

### 4.1 Sidecar field reference

| Field | Required | Meaning |
|---|---:|---|
| schemaVersion | Yes | Exactly `att-sidecar/v2.1` |
| excel.sheet | Yes | One sheet name or comma-separated groupId=sheetName entries |
| excel.headerRows | No | Number of top rows treated as headers; default 1 |
| excel.caseId | Yes | Physical Excel header holding the row Case ID |
| excel.tags | Yes | Physical Excel header holding comma-separated tags |
| excel.dataColumns | No | Case-level data mapping parsed into CASE |
| stages | Yes | Non-empty ordered list of stage definitions |
| stages[].key | Yes | Unique, dot-free runtime stage identifier |
| stages[].template | Yes | Physical Excel header containing the row template selector |
| stages[].dataColumns | No | Data mapping visible only below that stage |
| stages[].required | No | Whether a blank selector is an error; default false |
| stages[].runWhen | No | normal, onSuccess, onFailure, or always |
| stages[].onFailure | No | stop or continue |
| report.columns | No | Workbook-specific result column label overrides |
| timeoutMs | No | Workbook-specific tool timeout override in milliseconds, 1–86400000 |

Only the fields in this table are allowed. The sidecar top level permits `schemaVersion`, `excel`, `stages`, `report`, `timeoutMs`, and `x-*`; `excel` permits `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns`, and `x-*`; each stage permits `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure`, and `x-*`; `report` permits `columns` and `x-*`. Unknown non-extension fields are errors. The sidecar cannot override global tools, template root, environment, or output roots.

### 4.2 CSV-style quoting examples

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

The `(yaml)` marker belongs to the sidecar specification, not the physical Excel header. In the last example the physical header is `請求(yaml)`, while the final marker instructs ATT to parse its cell value as YAML. Double a quote inside a quoted item using CSV rules.

### 4.3 Cell conversion and blank markers

Excel display values are trimmed before use. `N/A`, `NA`, `NULL`, `NONE`, an empty cell, and a whitespace-only value normalize to blank. A blank case/stage data value is stored as the empty string. A blank `(yaml)` data cell remains blank rather than being parsed. Non-blank YAML may be a scalar, list, or map for ordinary data columns; template selector cells follow the stricter map-or-string rules in Section 5.

Excel numeric formatting matters: identifiers that must retain leading zeroes should be stored/formatted as text. Case IDs, group IDs, stage keys, aliases, template names, and paths are case-sensitive.

### 4.4 Header and row validation

Every configured header must exist exactly once in every configured sheet. Duplicate headers are errors. Rows whose configured cells are all blank are ignored. A non-blank row must contain a Case ID; complete Case IDs must remain unique across all configured groups. Required stage selector cells must be non-blank after normalization.

Because ATT uses the full Case ID directly as the final case output-directory name, group IDs and row Case IDs must be non-blank, have no leading/trailing whitespace, not end in `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or any control character. They cannot be `.` or `..`, cannot be Windows device names such as `CON`, `NUL`, `COM1`, or `LPT1` (case-insensitive), and the combined full ID must not exceed 255 Unicode code points. Chinese and other Unicode letters/numbers are valid. ATT preserves a valid `groupId.rowCaseId` unchanged in output, reports, and manifests.

### 4.5 Multi-row headers

Set `excel.headerRows` when a sheet has more than one header row. For example, `headerRows: 2` means rows 1 and 2 are headers and data starts at row 3. ATT resolves each physical column independently by scanning those rows from top to bottom and taking the last non-empty trimmed cell:

```text
Row 1: Basic data |            | Execution |            |
Row 2: Case ID    | Case name  | Template  | Parameters |
Effective names: Case ID, Case name, Template, Parameters
```

ATT does not concatenate parent and child labels. An empty parent cell is permitted; an empty final cell causes the last non-empty parent label to become the effective name. Duplicate effective names, missing configured effective names, and `headerRows` less than 1 fail validation. Result workbook columns are written onto the final header row, while data rows remain after all header rows.

For stage selector cells and stage-private data, continue with [Stages and template selection](#5-stages-and-template-selection).

## 5. Stages and template selection

Each stage requires key and template. The template field names the physical Excel column that selects the template for each row; it is not a fixed template name in the sidecar.

### 5.1 Create a reusable template name

A template directory contains template.yaml. Its top-level name is the template's symbolic name, for example:

```yaml
# templates/payment/local/CT001/template.yaml
name: LOCAL_PAYMENT
description: Local payment workflow
actions:
  # ...
```

Symbolic names are global across templates.root. Do not reuse the same symbolic name in two template directories; ATT rejects duplicate names during validation. The full template path for this example is payment/local/CT001 and is always relative to templates.root.

### 5.2 Select a template in an Excel cell

The cell may use either an explicit YAML map or a one-line YAML scalar shorthand.

| Cell form | Example | When to use it |
|---|---|---|
| Symbolic-name map | name: LOCAL_PAYMENT | Select a reusable template by its global symbolic name |
| Full-path map | name: payment/local/CT001 | Select one categorized template by its complete relative path |
| Scalar shorthand | LOCAL_PAYMENT | Short form of name: LOCAL_PAYMENT |
| Scalar path shorthand | payment/local/CT001 | Short form of name: payment/local/CT001 |

An explicit map can also add data used only by that stage:

```yaml
name: LOCAL_PAYMENT
retry: 2
channelOverride: MOBILE
```

The scalar shorthand is normalized internally to a mapping containing name: <scalar>. Therefore `${CASE.STAGES.invoke.name}` is available for both forms. A scalar cannot add other stage values; use the map form when the row needs values such as retry, channelOverride, or request options.

### 5.3 Resolution order and ambiguity

ATT resolves the value of name, including a normalized scalar, in this exact order:

1. Look for a template whose template.yaml name equals the value.
2. Only when no symbolic name matches, look for a template directory with that complete path relative to templates.root.
3. If neither match exists, validation fails with an unknown template name/path diagnostic.

This means a symbolic name wins if it happens to equal a template path. Use a distinctive symbolic name, or choose a path that cannot collide with a symbolic name, when the distinction matters. Do not use an absolute filesystem path, a directory without template.yaml, or a path outside templates.root.

### 5.4 Stage data, blank values, and controls

All key-value pairs in an explicit selector map, including name, are copied directly into the stage data tree. stages[].dataColumns adds stage-private Excel data. A duplicate key between the selector map and stage dataColumns is a validation error.

required: true makes a blank selector an error. A blank selector on an optional stage skips that stage. N/A, NA, NULL, NONE, empty cells, and whitespace-only cells normalize to blank before template lookup. runWhen supports normal, onSuccess, onFailure, and always; onFailure supports stop and continue. V2 does not define name, templateColumn, or a fixed sidecar template selector.

### 5.5 Stage execution semantics

| Setting | Meaning | Typical use |
|---|---|---|
| runWhen: normal | Run while normal flow has not been stopped | Prepare and invoke stages |
| runWhen: onSuccess | Run only when earlier normal stages have not failed | Verification stages |
| runWhen: onFailure | Run only after an earlier stage fails | Rollback and diagnostics |
| runWhen: always | Run regardless of earlier stage results | Cleanup and evidence collection |
| onFailure: stop | Record the failure and stop subsequent normal stages | Default business flow behavior |
| onFailure: continue | Record the failure and continue where runWhen permits | Non-critical evidence collection |

When a stage omits both fields, ATT uses `runWhen: normal` and `onFailure: stop`. These are built-in per-stage defaults; V2 does not define a separate `stageDefaults` configuration block.

`onFailure: continue` never turns a failed assertion or tool error into PASS; it only allows later work to proceed. The distinction between `normal` and `onSuccess` after a continued failure is intentional:

| Earlier stage outcome | Later normal stage | Later onSuccess stage | Later onFailure stage | Later always stage |
|---|---|---|---|---|
| PASS | Runs | Runs | Skips | Runs |
| FAIL/ERROR with onFailure: stop | Skips | Skips | Runs | Runs |
| FAIL/ERROR with onFailure: continue | Runs | Skips | Runs | Runs |

This permits a diagnostic or evidence-collection stage to fail without stopping a later normal cleanup-preparation stage, while still preventing success-only verification from running after any failure.

### 5.6 Recommended scenarios

**Ordinary business flow.** Omit both settings for the main stages. ATT uses normal/stop, so an invoke failure stops later normal work; verification should explicitly use onSuccess.

```yaml
stages:
  - {key: prepare, template: 準備模板, required: false}
  - {key: invoke, template: 執行模板, required: true}
  - {key: verify, template: 驗證模板, required: true, runWhen: onSuccess}
```

**Rollback after a failed invocation.** Configure rollback as optional because not every row needs it, and use onFailure so it runs only after an earlier failure. Use continue when rollback evidence is useful but a rollback failure must not prevent final cleanup.

```yaml
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
```

**Always cleanup.** Cleanup should normally be optional and always-run. Its onFailure should usually be continue so it records a problem without changing the fact that the original business failure already occurred.

```yaml
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

**Non-blocking diagnostics.** A log collection stage may use normal/continue. A failure still makes the final case result fail, but later normal stages can continue. Do not use continue merely to hide a business-critical failure.

### 5.7 Result workbook column overrides

The sidecar may override the headings ATT writes to the copied result workbook:

```yaml
report:
  columns:
    result: 測試結果
    durationMs: 耗時毫秒
    actualResult: 實際結果
    caseLog: 案例日誌
    reportLink: 詳細報告
    runTime: 執行時間
```

This changes result-workbook labels only. It does not change the HTML report structure, runtime Context keys, or the global report file-name pattern.

For the selected template's implementation, see [Templates and actions](#6-templates-and-actions); for the resulting data tree, see [Runtime Context](#7-runtime-context).

## 6. Templates and actions

A template is a directory if and only if it directly contains template.yaml. Nested and Unicode/Chinese paths are supported. The descriptor contains an ordered YAML actions map.

    schemaVersion: att-template/v2.1
    name: PAYMENT_INVOKE
    description: Render and invoke a payment request
    actions:
      renderRequest:
        type: render
        payload: request.tmp.xml
        saveAs: request.xml
      callApi:
        type: tool
        call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
      checkStatus:
        type: assert
        expression: "${ACTIONS.callApi.output.status} == 'SUCCESS'"

Supported action types are render, tool, assert, and log. Action IDs are unique within a template and cannot contain a dot. Optional request files request.tmp.xml, request.tmp.json, and request.tmp.yaml live beside template.yaml and are rendered as UTF-8.

### 6.1 Template descriptor fields

`schemaVersion`, `description`, and `actions` are mandatory top-level descriptor fields. `schemaVersion` must be exactly `att-template/v2.1`. `name` is optional only when callers always select the template by full path; give reusable templates a globally unique symbolic name. `description` is shown in generated documentation. `actions` must be a non-empty ordered YAML map. Only `schemaVersion`, `name`, `description`, `actions`, and `x-*` are allowed at top level.

V2.1 does not support `actionDefaults`. Each action independently owns its optional `onFailure` value, so its behavior is visible at the point where the action is declared.

### 6.2 Action field reference

| Action type | Required fields | Optional fields | Result available to later actions |
|---|---|---|---|
| render | type, payload | saveAs, output, onFailure, description | `${ACTIONS.<id>.output}` or `${ACTIONS.<id>.outputFile}` |
| tool | type, call | timeoutMs, retry, onFailure, description | `${ACTIONS.<id>.output}` and canonical CASE.TOOL result |
| assert | type, expression | onFailure, description | status, expected, actual, error metadata |
| log | type, message | level, fields, onFailure, description | message and rendered fields metadata |

For every action type, `onFailure` accepts exactly `stop` or `continue`; if omitted or blank, ATT uses `stop`. `stop` stops later actions in the same template after a FAIL or ERROR. `continue` records the failure and lets later actions run, but never changes the stage or case result to PASS. Values such as `ignore`, `warn`, or a non-string YAML value are rejected when ATT loads the template.

Type-specific validation is strict. A render action requires a regular-file `payload`; `saveAs` is required with `output.mode: file`, and render cannot define `retry` or `timeoutMs`. A tool action requires exactly one configured external `call` and is the only action type allowed to define `retry` or `timeoutMs`. `timeoutMs`, when present, is an integer from 1 to 86400000 and overrides the resolved global/sidecar timeout for that action. An assert action requires a non-blank expression that parses at validation time and cannot retry. A log action requires a non-blank message; its level is TRACE, DEBUG, INFO, WARN, or ERROR. Fields intended for another action type are validation errors, not ignored configuration.

`render.payload` and `render.saveAs` must remain inside the template/action output directory. Prefer `saveAs` for XML, JSON, YAML, SQL, and large text that will be passed to a tool. A tool action calls one configured global tool through `#{toolName(namedArgument=value)}`. An assert action records FAIL when its expression is false and ERROR when expression evaluation itself fails. A log action renders message and fields but does not perform an assertion.

### 6.3 Template and file authoring rules

Keep every payload file below its template directory. A directory that lacks template.yaml is only a category directory, not a callable template. Template names, paths, descriptions, payload filenames, and payload content may use Chinese/Unicode UTF-8 text. Names and paths are case-sensitive.

Tool calls use the syntax described in [Expressions and built-in functions](#8-expressions-and-built-in-functions) and must satisfy the global contract in [Tool configuration and execution](#9-tool-configuration-and-execution).

## 7. Runtime Context

The authoritative persisted tree is rooted at CASE:

    CASE
    ├── caseId, groupId, rowCaseId, workbook, sheet, rowNumber, tags
    ├── status, startedAt, durationMs, error
    ├── <case data columns>
    └── STAGES
        └── <stageKey>
            ├── <stage data and template-cell YAML keys>
            └── TEMPLATE
                └── ACTIONS
                    └── <actionId>
                        ├── action metadata
                        └── TOOL
                            └── <toolName>
                                └── input/output/status/log/artifact data

Core keywords are uppercase: CASE, STAGES, TEMPLATE, ACTIONS, and TOOL. Metadata properties retain camelCase, such as caseId, groupId, startedAt, durationMs, and outputFile. There are no CASE.fields, CASE.data, or TOOLS nodes.

Normative references include ${CASE.caseId}, ${CASE.amount}, ${CASE.STAGES.invoke.channel}, and ${CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.TOOL.invokePaymentApi.output}. ${ACTIONS.<actionId>...} is a current-template convenience view. ${TOOL.input}, ${TOOL.output}, ${TOOL.inputFile}, and ${TOOL.outputFile} are transient views while a tool is running.

### 7.1 Built-in CASE properties

| Property | Availability and meaning |
|---|---|
| caseId | Full group-qualified Case ID |
| groupId | Sheet group ID from excel.sheet |
| rowCaseId | Original row-level Case ID |
| workbook | Workbook filename |
| sheet | Physical sheet name |
| rowNumber | One-based Excel row number |
| tags | Parsed tag list |
| environment | Effective execution environment |
| status | RUNNING, PASS, FAIL, ERROR, SKIPPED, or INVALID as applicable; ERROR always takes precedence over FAIL in aggregation |
| startedAt | Case start timestamp |
| durationMs | Measured case duration |
| error | Runtime error message when present |
| STAGES | Map of started stage nodes, keyed by configured stage key |

Configured case data keys are stored directly beside these properties. Reserved built-in names cannot be reused as data aliases.

### 7.2 Built-in STAGE properties

Every stage that is selected and started creates `CASE.STAGES.<stageKey>`. A blank optional selector creates no stage node. In addition to the configured `stages[].dataColumns` and every key from the template-selector cell YAML, ATT provides the following properties:

| Property | Availability and meaning |
|---|---|
| key | Configured `stages[].key` |
| status | `RUNNING` while executing; final aggregation is PASS, FAIL, ERROR, or SKIPPED |
| startedAt | ISO-8601 timestamp captured when the stage starts |
| durationMs | Elapsed stage duration after completion |
| TEMPLATE | Child template node described in the next section |

For example, `${CASE.STAGES.invoke.status}` reads the final status of an earlier `invoke` stage, and `${CASE.STAGES.invoke.channel}` reads that stage's row-specific data. Stage data is private to that stage; it is not copied to CASE.

### 7.3 Built-in TEMPLATE properties

Each started stage has exactly one `CASE.STAGES.<stageKey>.TEMPLATE` node. It identifies the resolved template rather than merely repeating the selector value.

| Property | Availability and meaning |
|---|---|
| name | Resolved template symbolic `name`; if the descriptor lacks one, the selected reference |
| path | Normalized filesystem path of the resolved template directory |
| status | `RUNNING` while actions execute; final aggregation is PASS, FAIL, ERROR, or SKIPPED |
| startedAt | ISO-8601 timestamp captured when template execution starts |
| durationMs | Elapsed template duration after completion |
| ACTIONS | Ordered child map of completed action nodes |

The selector's `name` key, if supplied in the Excel YAML cell, remains stage data at `${CASE.STAGES.<stageKey>.name}`. Do not confuse it with `${CASE.STAGES.<stageKey>.TEMPLATE.name}`, which is the resolved descriptor name.

### 7.4 Built-in ACTION properties

Every successfully recorded action creates `CASE.STAGES.<stageKey>.TEMPLATE.ACTIONS.<actionId>`. The common properties are:

| Property | Availability and meaning |
|---|---|
| id | Effective Action ID |
| type | `render`, `tool`, `assert`, or `log` |
| status | Assertions are PASS/FAIL; successful render/log are PASS; tool/parser/I/O failures are ERROR, with TIMEOUT recorded as tool detail. ERROR takes precedence over FAIL for stage/case/run aggregation. |
| durationMs | Action duration in milliseconds; tool actions use measured process duration, while local actions currently record `0` |
| output | Rendered result for in-memory render/assert/log actions; not present on a tool action itself |
| rawOutput | Unstructured rendered result for in-memory render/assert/log actions |
| outputFile | Render output path when `saveAs` or file output is used |
| outputBytes | UTF-8 byte length for a file render |
| outputSha256 | SHA-256 for a file render |
| outputPreview | Short single-line preview for a file render |
| level, message, fields | Present only for a `log` action |
| TOOL | Present only for a tool action; child map keyed by invoked tool name |

An action that fails before ATT can create its action record may have no node; consult the case log and case `error` in that situation. Within the currently running template, `${ACTIONS.<actionId>...}` is a convenience view of this node. For a tool action it also exposes the first invoked tool's fields, so `${ACTIONS.callApi.output}` is equivalent to the canonical tool output path.

### 7.5 Built-in TOOL properties

A tool action stores the durable record at `CASE.STAGES.<stageKey>.TEMPLATE.ACTIONS.<actionId>.TOOL.<toolKey>`. `<toolKey>` is the key under global `tools`, not the tool's display name. The record contains:

| Property | Availability and meaning |
|---|---|
| name | Invoked tool key |
| input | Fully resolved named input map after blank normalization and any final-argument `delimit` expansion |
| inputFile | YAML file ATT generated for the tool input |
| output | Parsed output: string for `txt`; structured YAML, JSON, or XML value for `yaml`, `json`, or `xml` |
| outputFile | Tool output artifact path |
| rawOutput | Raw UTF-8 tool output before parsing |
| stdout | Captured process standard output |
| stderr | Captured process standard error |
| command | Fully rendered command passed to the process runner |
| status | `PASS`, `ERROR`, or `TIMEOUT` |
| durationMs | Measured process duration |
| exitCode | Process exit code |

While a tool is executing and after its latest invocation, ATT also exposes the transient root `TOOL` scope: `${TOOL.input}`, `${TOOL.output}`, `${TOOL.inputFile}`, and `${TOOL.outputFile}`. These are shortcuts only; use the canonical CASE path for a durable cross-action or cross-stage reference.

### 7.6 Map and list navigation

Dot notation navigates maps. Lists support both bracket and numeric-dot indexes:

```text
${CASE.expected.status}
${CASE.items[0].status}
${CASE.items.0.status}
```

Indexes are zero-based. An unresolved path renders as the empty string in normal template text. In assertions, a missing value behaves as null, so `${CASE.missing} is null` is true. Use validation and explicit assertions to catch misspelled optional paths rather than relying on blank rendering.

### 7.7 Scope and timing

An action may read case data, its stage data, and outputs from earlier actions. It cannot read a later action that has not executed. The `ACTIONS` convenience view contains completed actions in the current template only; use the canonical CASE path to read a prior stage. TOOL scope is populated for the current/latest tool invocation and should not be used as long-term cross-stage storage.

For lookup syntax, literals, assertions, and built-ins, see [Expressions and built-in functions](#8-expressions-and-built-in-functions).

## 8. Expressions and built-in functions

${path} performs a read-only Context lookup. #{name(...)} invokes a configured external tool or built-in function. Exact Context arguments preserve their original string, number, or boolean type. Supported literals are quoted strings, numbers, and true/false.

Assertions support ==, !=, >, >=, <, <=, like, is null, is not null, parentheses, not, and, and or.

### LIKE wildcard semantics

LIKE matches the entire value and supports SQL-style wildcards:

| Pattern token | Meaning | Example |
|---|---|---|
| % | Zero or more arbitrary characters | 'PAYMENT_POSTED' like 'PAYMENT%' is true |
| _ | Exactly one arbitrary character | 'ABC' like 'A_C' is true |

For example, `${ACTIONS.getLogs.output} like '%PAYMENT%POSTED%'` confirms that the two fragments occur in order. The current implementation translates % to the Java regular-expression wildcard .* and _ to . before matching. Consequently, other regular-expression metacharacters in a LIKE pattern, such as ., +, *, [, ], (, ), ?, ^, $, |, and backslash, retain their regular-expression meaning instead of being automatically escaped as literal text. Use LIKE with ordinary literal text plus % and _ only; use == for exact text comparison. A future strict SQL-LIKE escaping rule may remove this limitation while retaining the % and _ contract.

Built-ins:

| Function | Purpose | Example |
|---|---|---|
| upper | Convert text to upper case | #{upper(value=${CASE.currency})} |
| lower | Convert text to lower case | #{lower(value=${CASE.channel})} |
| trim | Remove surrounding whitespace | #{trim(value=${CASE.reference})} |
| string | Convert a value to text | #{string(value=${CASE.amount})} |
| number | Parse and normalize a number | #{number(value='12.50')} |
| boolean | Convert true/false, yes/no, or 1/0 | #{boolean(yes)} |
| length | Return text length | #{length(value=${CASE.reference})} |
| concat | Concatenate arguments in order | #{concat(a='PAY', b=${CASE.caseId})} |
| coalesce | Return the first non-blank value | #{coalesce(${CASE.optional}, 'N/A')} |

### Built-in function rules

- `upper`, `lower`, `trim`, `string`, `number`, `boolean`, and `length` accept `value=` or the first positional argument.
- `upper` and `lower` use locale-independent case conversion.
- `number` rejects non-numeric text and returns normalized decimal text without unnecessary trailing zeroes.
- `boolean` accepts true/false, yes/no, and 1/0 (case-insensitive where textual).
- `length` measures the Java string length of the converted value.
- `concat` appends every supplied argument in call order; null values contribute an empty string.
- `coalesce` returns the first value that is neither null nor a whitespace-only string; it returns an empty string if none qualifies.
- Built-ins run inside ATT and do not create TOOL process artifacts. External tools are required for filesystem, database, network, or system operations.

Invalid function input produces an action ERROR, subject to the action/template onFailure behavior.

### Expression examples

```text
# Exact text and typed numeric comparison
${CASE.currency} == 'HKD'
${CASE.amount} >= 100 and ${CASE.enabled} == true

# Context value inside a quoted text literal
'payment-${CASE.caseId}' == 'payment-payment.TC001'

# Null and LIKE checks
${CASE.optionalReference} is null
${ACTIONS.getLogs.output} like '%PAYMENT%POSTED%'
'ABC' like 'A_C'

# Built-in values embedded into rendered content
Reference=#{concat(a='PAY-', b=${CASE.caseId})}
Channel=#{lower(value=${CASE.STAGES.invoke.channel})}
```

The first three lines are assertion expressions. The final two lines are examples of rendered text containing built-in function results.

For external invocation contracts and parsed outputs used by these expressions, see [Tool configuration and execution](#9-tool-configuration-and-execution).

## 9. Tool configuration and execution

A tool action supplies named arguments that must match the configured contract. Unknown, duplicate, or missing required arguments fail validation. Argument descriptions do not inject execution values.

The final argument may declare delimit. ATT normalizes the value, splits it into an ordered array, and passes each element as a separate process argument. N/A, NA, NULL, NONE, empty cells, and whitespace-only values are blank; blank values passed to scripts become the empty string.

Tool output types are txt, yaml, json, and xml. YAML, JSON, and XML output become structured values; text output remains a string. Tool input, command, stdout, stderr, exit code, status, duration, parsed output, attempt records, and artifact paths are recorded under TOOL.toolName.

### Tool and argument field reference

| Field | Required | Meaning |
|---|---:|---|
| tools.<key> | Yes | Unique key used by `#{<key>(...)}` |
| name | Yes | Human-readable name shown in generated documentation |
| description | Yes | Purpose and operational notes shown to users |
| command | Yes | Process command template rendered then tokenized by ATT; it is not shell syntax |
| output | No | txt, yaml, json, or xml parser selection; default txt |
| arguments | No | Ordered map of accepted invocation inputs; may be empty |
| arguments.<key>.name | Yes | Display name for the argument |
| arguments.<key>.description | Yes | Usage and expected-content description |
| arguments.<key>.required | Yes | Whether a non-blank value must be supplied |
| arguments.<key>.delimit | No | Split delimiter; valid only on the final argument |

Tool keys and argument keys are case-sensitive. A template call must use named arguments for configured external tools. Positional arguments are intended for ATT built-ins, not external tool contracts.

ATT validates configuration values before execution: `output` must be exactly `txt`, `yaml`, `json`, or `xml`; every argument's `required` must be the YAML boolean `true` or `false`; `delimit` may occur only on the final declared argument; global/sidecar `timeoutMs` and tool-action `timeoutMs` must be 1–86400000. Invalid values stop validation with a structured diagnostic instead of being silently coerced.

### 9.1 Command placeholders and input/output files

ATT writes each invocation input to `${TOOL.inputFile}` as YAML and allocates `${TOOL.outputFile}` for the script result. The following command placeholders are available:

| Placeholder | Meaning |
|---|---|
| `${TOOL.inputFile}` | Generated YAML input file for the invocation |
| `${TOOL.outputFile}` | File the script should create with its raw result |
| `${TOOL.input.<argument>}` | A declared scalar input value; the final delimited value expands to process arguments |

Use input/output files for large request or response data. A script may write its result to outputFile; if it does not, ATT records stdout as the raw output. Exit code 0 is PASS. A non-zero exit code, timeout, or structured-output parse failure produces ERROR, preserves command/stdout/stderr/raw-output artifacts, and follows the action's onFailure rule. A timeout is never retried.

Timeout resolution is explicit: a tool action's `timeoutMs` wins; otherwise ATT uses the selected workbook sidecar's `timeoutMs` when present; otherwise it uses global `config.yaml` `timeoutMs` (default `120000`). The value is milliseconds, so `30000` means 30 seconds and `120000` means 2 minutes.

#### Command tokenization, spaces, and special characters

ATT renders `${...}` placeholders first, then tokenizes the resulting `command` into the process argv. It does **not** invoke `/bin/sh`, `cmd.exe`, PowerShell, or another shell. The following rules are therefore important:

| Command text | ATT process argument effect |
|---|---|
| whitespace outside quotes | Separates argv elements |
| `'...'` or `"..."` | Groups enclosed text as one argv element; quote characters are not passed |
| `\` outside single quotes | Escapes the next character for ATT tokenization |
| `|`, `>`, `<`, `;`, `&`, `$`, `(`, `)`, `*`, `?` | Ordinary characters; they do not pipe, redirect, execute a substitution, expand a variable, or expand a glob |
| an unmatched quote or final escape | Configuration/runtime ERROR; ATT cannot form argv |

For example, this command produces five argv elements after rendering:

```yaml
command: "./tools/send.sh --input '${TOOL.inputFile}' --label 'Payment regression'"
```

```text
./tools/send.sh
--input
<generated-input.yaml>
--label
Payment regression
```

The following does **not** create a pipe or redirection. The characters are delivered to the program as part of ordinary arguments:

```yaml
command: "./tools/inspect.sh --filter status|PENDING --literal >result"
```

```text
./tools/inspect.sh
--filter
status|PENDING
--literal
>result
```

Do not interpolate untrusted or free-text testcase values directly into `command`. A value such as `A B`, `O'Reilly`, `a"b`, or `one\ two` can change ATT tokenization if it is rendered into a quoted/unquoted command string. Instead, pass it as a named tool argument and have the script read the generated YAML `${TOOL.inputFile}`:

```yaml
tools:
  submitNote:
    name: Submit note
    description: Submit a free-text note from the invocation input file
    command: "./tools/submit_note.sh --input '${TOOL.inputFile}' --output '${TOOL.outputFile}'"
    output: json
    arguments:
      note: {name: Note, description: Free text; read from input YAML, required: true}
```

```yaml
submit:
  type: tool
  call: "#{submitNote(note=${CASE.note})}"
```

The YAML input preserves spaces and special characters as data. The script should parse its input file rather than expecting `${CASE.note}` to be appended to its command line. Use `${TOOL.outputFile}` similarly for large or structured results.

If a script genuinely needs a fixed argument with spaces, quote only that fixed command fragment, as in `--label 'Payment regression'`. Do not put a workbook value inside those quotes. If shell pipeline/redirection logic is required, implement it inside a reviewed tool script; keep the ATT command as the script invocation.

### 9.2 JSON and XML output

JSON preserves object order, arrays, strings, booleans, null, and arbitrary-precision numbers. Duplicate JSON object keys are rejected. For a JSON output:

```json
{"status":"SUCCESS","transaction":{"id":"TXN-001"},"warnings":[]}
```

the following paths are available:

```text
${ACTIONS.callApi.output.status}
${ACTIONS.callApi.output.transaction.id}
${ACTIONS.callApi.output.warnings[0]}
```

XML remains XXE-safe. Repeated sibling elements are retained as arrays, attributes are retained, and text can coexist with children. `xml.namespaceMode: ignore` (default) uses local element/attribute names; `preserve` uses Clark notation such as `{urn:payment}Status`. In preserve mode, the canonical shape is:

```yaml
name: "{urn:payment}Items"
attributes: {}
text: ""
children:
  Item:
    - name: "{urn:payment}Item"
      attributes: {"{}id": "1"}
      text: "A"
      children: {}
    - name: "{urn:payment}Item"
      attributes: {"{}id": "2"}
      text: "B"
      children: {}
```

For a concrete `namespaceMode: ignore` example, a tool returning:

```xml
<PaymentResponse requestId="REQ-9">
  <Status code="00">SUCCESS</Status>
  <Messages>
    <Message severity="INFO">accepted</Message>
    <Message severity="WARN">review later</Message>
  </Messages>
</PaymentResponse>
```

is exposed through the canonical XML node. Assuming the tool action ID is `callApi`, expressions can access the root attribute, child text, child attribute, and same-name nodes as follows:

```yaml
assertRequestId:
  type: assert
  expression: "${ACTIONS.callApi.output.attributes.requestId} == 'REQ-9'"
assertStatusText:
  type: assert
  expression: "${ACTIONS.callApi.output.children.Status[0].text} == 'SUCCESS'"
assertStatusCode:
  type: assert
  expression: "${ACTIONS.callApi.output.children.Status[0].attributes.code} == '00'"
assertSecondMessage:
  type: assert
  expression: "${ACTIONS.callApi.output.children.Messages[0].children.Message[1].text} == 'review later'"
assertSecondSeverity:
  type: assert
  expression: "${ACTIONS.callApi.output.children.Messages[0].children.Message[1].attributes.severity == 'WARN'"
```

Indexes are zero-based. Even a child that appears once is represented as a list below `children`, so `Status[0]` is deliberate. With `namespaceMode: preserve`, replace keys such as `Status` and `requestId` with the emitted Clark-notation keys; inspect `case.yaml` to see the exact parsed structure.

Malformed JSON/XML after a zero-exit tool is still an ERROR. Inspect the persisted raw output and parser diagnostic rather than treating it as a business assertion FAIL.

### 9.3 Delimited array arguments: grep application logs

Use `delimit` when one logical source value must become multiple process arguments. It is allowed only on the final declared tool argument. ATT splits that final value after normalizing blank markers, preserves the order, and inserts each resulting item at the location of `${TOOL.input.<argument>}` in command.

For example, configure `grepFromAppLogs` as follows:

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords
    command: "./tools/grep_from_app_logs.sh --input '${TOOL.inputFile}' --output '${TOOL.outputFile}' ${TOOL.input.keywords}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Comma-delimited values, required: true, delimit: ","}
```

The declaration order is significant: `keywords` is the final argument, so it may declare `delimit: ","`. `logFile` is not eligible because it is not final.

Call the tool from a template action:

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${ACTIONS.getLogs.outputFile}, keywords='PAYMENT,POSTED')}"
```

For the value `PAYMENT,POSTED`, ATT invokes the command as the following logical argument array:

```text
./tools/grep_from_app_logs.sh
--input <generated-input.yaml>
--output <generated-output.yaml>
PAYMENT
POSTED
```

The application log script can consume those trailing values as independent keywords:

```sh
#!/usr/bin/env sh
set -eu

output=""
keywords=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input) shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do
        keywords="${keywords}${keywords:+,}$1"
        shift
      done
      ;;
    -*) shift ;;
    *)
      keywords="${keywords}${keywords:+,}$1"
      shift
      ;;
  esac
done

[ -n "$output" ] || { echo "missing --output" >&2; exit 2; }
printf 'matched: true\nkeywords: [%s]\n' "$keywords" > "$output"
```

#### Delimiter rules and edge cases

| Input value for keywords | Script receives after `--output` | Notes |
|---|---|---|
| `PAYMENT,POSTED` | `PAYMENT`, `POSTED` | Normal multi-value case |
| `PAYMENT, POSTED` | `PAYMENT`, `POSTED` | Surrounding whitespace is trimmed |
| `PAYMENT,,POSTED` | `PAYMENT`, ``, `POSTED` | Empty middle element is preserved as an empty argument |
| `N/A`, `NA`, `NULL`, `NONE`, or blank | No trailing keyword arguments | Normalizes to an empty array |
| `PAYMENT,POSTED,ERROR` | Three trailing arguments in the same order | Any number of values is supported |

`required: true` is checked before the delimited expansion. Therefore a blank/N/A required delimited argument is rejected as missing, while the same value is allowed for an optional delimited argument and becomes an empty array.

The expanded items are appended as distinct ATT argv elements. Thus an item such as `payment review` remains one argument, and `A|B`, `>result`, or `x<y` remains literal data rather than shell syntax. Do not quote `${TOOL.input.keywords}` in the command: quotes would make ATT treat all expanded items as one argv element. Do not use `delimit` when one value may itself contain that delimiter and must remain atomic. In that case use a different delimiter that is not present in the data, or pass the values through `${TOOL.inputFile}` instead.

When the tool output is yaml, `${ACTIONS.grepLogs.output.matched}` and `${ACTIONS.grepLogs.output.keywords}` read the parsed result. For JSON output, use normal map/list navigation such as `${ACTIONS.callApi.output.status}`. For XML, use the canonical `name`/`attributes`/`text`/`children` representation described in Section 9.2; repeated children are lists and namespace key names depend on `xml.namespaceMode`.

### 9.4 Retry an eligible tool action

Retry is an action/tool execution policy, not a stage loop. Only a `tool` action may define it:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    retryOn: [EXIT_CODE]
    exitCodes: [1, 75]
```

`maxAttempts` includes the first attempt and defaults to 1. V2.1 supports only `retryOn: [EXIT_CODE]`; `exitCodes` applies to that policy, and omitting it retries any non-zero exit code. Configuration/argument errors, output parse errors, I/O errors, assertion FAIL, render/log actions, and timeout are never retried. V2.1 has no retry delay or backoff fields: eligible retries run immediately. Use retry only for operations that are safe to repeat; ATT does not infer API idempotency.

Every attempt has separate evidence, for example `callApi/attempt-001/` and `callApi/attempt-002/`. The final action record preserves all attempts, durations, and the winning attempt. A later successful attempt gives the action PASS but retains earlier attempt errors as evidence; exhaustion gives ERROR.

For a complete case/template combination that uses these concepts, see [End-to-end development example](#10-end-to-end-development-example).

## 10. End-to-end development example

A typical workbook row contains TC001, tags smoke,payment, amount 100, an expected YAML value such as status: SUCCESS, and stage template cells name: PAYMENT_INVOKE and name: PAYMENT_VERIFY. Its global config, sidecar, and templates begin with their required `att-config/v2.1`, `att-sidecar/v2.1`, and `att-template/v2.1` schema versions.

A matching sidecar declares excel.sheet, excel.caseId, excel.tags, case dataColumns, and ordered invoke and verify stages. The invoke template renders request.tmp.xml and calls invokePaymentApi with `output: json`; the verify template reads the canonical CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.TOOL.invokePaymentApi.output.status path and asserts the expected status.

A request file can be:

    <PaymentRequest>
      <CaseId>${CASE.caseId}</CaseId>
      <Amount>${CASE.amount}</Amount>
    </PaymentRequest>

Run `./att.sh validate --package` first, execute the selected Case ID, inspect case.yaml, ci/summary.json, ci/junit.xml, report/junit.html, and the HTML report, then package the latest completed run with ./att.sh build.

### Failure compensation and cleanup example

```yaml
stages:
  - {key: invoke, template: 執行模板, required: true, runWhen: normal, onFailure: stop}
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

`rollback` runs only after a prior stage fails. `cleanup` is independent of the preceding result and is the appropriate place to release temporary data or evidence files.

For validation, report inspection, generated package documentation, CI output, and archiving, see [Validation, diagnostics, reports, CI, and packaging](#11-validation-diagnostics-reports-ci-and-packaging).

## 11. Validation, diagnostics, reports, CI, and packaging

### 11.1 Package and selected validation

`./att.sh validate --package` is the default. It checks the global configuration/schema, every discovered workbook and mandatory sidecar, every configured sheet and row, all templates below `templates.root` including unreferenced ones, symbolic-name/path uniqueness, every action and expression, every tool contract, paths, and package integrity. It never invokes external tools.

`./att.sh validate --selected` checks only the selected cases and their dependency closure. It is intended for fast authoring feedback and must state that unselected content was not checked. `run` uses selected validation after it resolves its execution plan. A release gate must use package validation.

When JSON is requested, stdout is exactly one deterministic JSON document:

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.1.0",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104", "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null, "row": null, "column": null,
    "template": "PAYMENT_VERIFY", "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

Each diagnostic always has `code`, `severity`, `message`, `file`, `field`, `sheet`, `row`, `column`, `template`, `action`, and `suggestion`. Inapplicable location fields are `null`, not empty strings. Codes are stable; do not parse a human message to automate remediation. Errors prevent execution; warnings do not.

### 11.2 Navigate a completed run

ATT validates and plans before creating output. It creates a temporary `output/.in-progress/<RunID>-<nonce>/` directory, writes evidence there, then atomically publishes it as `output/<RunID>/`. A final run directory is therefore complete; an interrupted run stays in `.in-progress` and cannot be reported, built, or selected by `rerun-failed`.

```text
output/<RunID>/
├── report/index.html       Single-page offline HTML report
├── run.yaml                Versioned manifest, runtime metadata, input hashes, summary
├── events.jsonl            One event record per completed case
├── workbooks/              Result workbook copies
├── ci/
│   ├── summary.json         CI JSON summary
│   └── junit.xml            CI JUnit XML
├── report/
│   ├── index.html           Main human report
│   └── junit.html           Human-readable JUnit result view
└── <groupId>.<rowCaseId>/
    ├── case.yaml           Persisted CASE runtime tree
    ├── <case>.log          Ordered case execution log
    └── <stage>/<action>/   Render/tool artifacts and attempt-001/, attempt-002/ evidence
```

Run and Case IDs are used directly as these directory names after passing the Section 13 safety rules; ATT does not slugify or hash them. `run.yaml` has `schemaVersion: att-run/v2.1`, ATT version/build information, Java/OS/locale/timezone metadata, validation mode, environment, timestamps, case summary, output paths, and SHA-256 input records for the effective config, workbook, sidecar, resolved template/payload, package-local tool files, and schema/catalog version.

Open report/index.html directly from disk. Use the case table to filter by status and text, then expand a case to inspect assertion results, the CASE tree, detailed log, and artifact links. Use case.yaml when diagnosing a Context path or confirming the exact template/action/tool output that an assertion used.

### 11.3 CI outputs

`--ci-output junit,json` writes the requested files under `<run>/ci/`. JSON summary uses `schemaVersion: att-ci-summary/v2.1` and includes run/ATT IDs, environment, timing, aggregate counts/status, per-case result records, diagnostic counts, report/artifact paths, and input-manifest hash.

JUnit produces `<run>/ci/junit.xml` and `<run>/report/junit.html`. The HTML file is stored with the other human reports and is a human-readable projection of the same run summary as the XML; it does not perform a second result aggregation. It shows counts plus one row per testcase with status, duration, and embedded case-log content or an external relative artifact link.

JUnit XML maps one ATT case to one `<testcase>`: PASS has no child, FAIL writes `<failure>`, ERROR writes `<error>`, SKIPPED writes `<skipped>`, and INVALID writes `<error type="ATTValidationError">`. Action/stage diagnostics are XML-escaped. Both JUnit formats use `report.junit.caseLogEmbedThresholdBytes` (default 10240 UTF-8 bytes): at or below the threshold the case log is embedded; larger logs are summarized and linked by package-relative artifact path. Set the threshold to 0 to always link externally.

### 11.4 Documentation, archive, and clean choices

| Command | Output | Use it when |
|---|---|---|
| `./att.sh docs` | build/docs/index.html | Browsing or sharing one searchable offline testcase/template/tool reference |
| `./att.sh report --run-id <id>` | Regenerated completed-run report | Refreshing report HTML from persisted run data |
| `./att.sh build` | Latest completed run archive in build/ | Handing off report, evidence, configuration snapshot, and manifest |
| `./att.sh clean` | End-user generated ATT artifacts only | Resetting run/docs/archive output |

The build archive contains the completed report, workbooks, per-case logs, referenced artifacts, effective configuration/sidecars/template descriptors with configured secrets redacted, manifest, and hash information. It does not execute cases again.

`att.sh clean` removes the configured `outputDirectory`, `build/docs`, and `build/att-*.tar.gz`. It never removes testcase, template, tool, configuration, documentation, schema, or other source files. It canonicalizes paths, rejects package/source roots and external symlink targets, is idempotent, and reports exactly what it removed.

### 11.5 Common diagnostics and corrective actions

| Diagnostic category | Typical cause | Corrective action |
|---|---|---|
| ATT-TC | Missing sidecar, sheet, structural column, or duplicate full Case ID | Check the workbook basename, sheet mapping, headers, and group-qualified IDs |
| ATT-STG | Blank required selector, invalid selector YAML, or duplicate stage data key | Enter a map/scalar selector, ensure map form includes name, and remove duplicate keys |
| ATT-TPL | Unknown template, duplicate symbolic name, missing template.yaml, invalid action/payload | Check the symbolic name/path, descriptor, action ID, and payload location |
| ATT-CFG | Unknown field, duplicate YAML key, wrong schema version/type, invalid enum | Add the required schemaVersion and remove or rename unsupported fields; use `x-*` only for preserved extensions |
| ATT-TOOL | Unknown tool, unknown argument, missing required argument, command/parse failure | Compare the call with config.yaml tools and arguments; check output type and script result |
| ATT-PATH | Illegal Run ID, Case ID, selector path, payload path, or output path | Remove path separators/control characters/reserved names and keep paths beneath the configured root |
| ATT-RUN | Tool timeout, non-zero exit code, rendering/runtime failure | Inspect case log, TOOL command/stdout/stderr, input.yaml, and output file |

Run `./att.sh validate --package` after every workbook, sidecar, template, or tool change. It is the fastest way to find package-wide issues before contacting an external system.

## 12. Best practices and troubleshooting

- Validate before every run and keep each workbook and sidecar together.
- Use stable, dot-free stage and action IDs.
- Use symbolic template names for reusable business templates and full paths for unambiguous categorized templates.
- Keep large request bodies in template directories.
- Use onFailure stages for compensation/diagnostics and runWhen: always for cleanup.
- Keep tool outputs deterministic and redact secrets from logs and reports.
- Use tags for repeatable smoke, regression, and batch selections.
- Use canonical CASE paths for persisted cross-stage references; use ACTIONS and TOOL for local convenience.

For a missing sidecar, check workbook and YAML basenames and directory. For a missing sheet or column, compare physical headers with excel.sheet, caseId, tags, and dataColumns. For an unknown template, check template.yaml.name or the relative template directory path. For an empty Context value, check uppercase keywords and dot-free IDs. For a rejected tool argument, compare the action call with the tool arguments contract. For offline links, keep the generated run/docs directory together and regenerate if needed.

## 13. Unicode, paths, security, and run lifecycle

ATT configuration, sidecars, template descriptors, payloads, and generated text use UTF-8. Chinese/Unicode is supported in workbook filenames, sheet names, headers, aliases, data values, template symbolic names, template directory segments, descriptions, logs, reports, and generated documentation.

Identifiers and references remain case-sensitive. `LOCAL_PAYMENT`, `Local_Payment`, and `local_payment` are different symbolic names. Unicode display text is preserved in reports. Avoid visually indistinguishable names and normalize naming conventions within a package.

### 13.1 Path safety

- Template full paths are relative to templates.root and use `/` in portable documentation/configuration.
- Absolute paths and paths escaping templates.root are not valid template references.
- render payloads must remain below their template directory.
- action output files must remain below the action output directory.
- Run ID and full Case ID directly become output directory names: `output/<RunID>/<groupId>.<rowCaseId>/`. ATT does not slugify or hash a valid ID.
- Run ID must be non-blank, at most 128 Unicode code points, not `.`/`..`, not leading/trailing whitespace or trailing `.`, and must not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or a control character. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.
- Group ID and row Case ID follow the same character rules; the combined full Case ID is at most 255 Unicode code points.
- `report --run-id` accepts a logical ID only, never a filesystem path.
- Before every path write, ATT resolves against its intended root, normalizes, resolves relevant existing symlinks, and verifies strict root containment.
- Do not place generated output below testcase or templates source trees.

### 13.2 Untrusted content and secrets

ATT disables unsafe YAML object construction, duplicate YAML keys, duplicate JSON object keys, XML DTD/external entity/XInclude loading, and external XML resources. HTML output escapes workbook, template, tool, log, and result text. These protections do not make it safe to place passwords, tokens, account secrets, or private keys in workbook cells or template descriptors.

Prefer environment-specific secret injection inside approved tool scripts. Avoid embedding secrets in command strings because commands, stdout, stderr, inputs, and outputs may be recorded as evidence. During build, configuration snapshot lines whose YAML key is exactly password, token, secret, or authorization (case-insensitive) are replaced with `***REDACTED***`; this is not a general-purpose log/result redactor. Use other key names or embedded secret values only if they are safe to archive, and always review generated reports and archives before sharing them.

### 13.3 Process safety

Tool commands run as local processes with the permissions of the ATT user. Validate packages before running, review every configured command/script, use file-based transfer for large or sensitive data, set realistic `timeoutMs` values, and run destructive tools only against the intended SIT/UAT environment. ATT records commands, inputs, stdout, stderr, raw outputs, and attempts as evidence; configure and review redaction before sharing artifacts.

### 13.4 Atomic completion and stale runs

ATT first validates and produces an execution plan, then writes only beneath `output/.in-progress/<RunID>-<nonce>/`. On successful finalization it atomically moves the directory to `output/<RunID>/`, then atomically replaces `latest-run.yaml`. A run that is not atomically published is incomplete and cannot be used by report/build/rerun-failed. `att.sh clean` reports stale in-progress directories; do not manually move one into the completed output root.

## 14. Complete command reference

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh --help` | Show help without executing anything |
| `./att.sh version` | Print version |
| `./att.sh validate --package` | Validate the complete package; default validation scope |
| `./att.sh validate --selected --case <group.caseId>` | Validate only one selected case and its dependency closure |
| `./att.sh validate --package --format json` | Emit one structured validation JSON document to stdout |
| `./att.sh run --all` | Execute every selected case |
| `./att.sh run --suite <file>` | Execute one workbook |
| `./att.sh run --all --case <group.caseId>` | Execute one full Case ID |
| `./att.sh run --all --tag <tag>` | Include cases matching a tag |
| `./att.sh run --all --exclude-tag <tag>` | Exclude matching cases |
| `./att.sh run --all --dry-run` | Validate/plan without external tool execution |
| `./att.sh run --all --fail-fast` | Stop after first failed/error case |
| `./att.sh run --all --rerun-failed` | Rerun failures from latest history |
| `./att.sh run --all --run-id <id>` | Use an explicit new run ID |
| `./att.sh run --all --output-dir <dir>` | Override output root |
| `./att.sh run --all --format json` | Emit machine-readable summary |
| `./att.sh run --all --ci-output junit,json` | Write JUnit XML, JUnit HTML, and JSON files below the completed run |
| `./att.sh run --all --quiet` | Suppress normal progress output |
| `./att.sh run --all --verbose` | Add safe suite/case/stage/action lifecycle progress without payload or command content |
| `./att.sh docs` | Generate the single-page package reference |
| `./att.sh report --run-id <id>` | Regenerate a persisted report |
| `./att.sh build` | Archive the latest completed run |
| `./att.sh clean` | Remove generated ATT output |

Options requiring a value fail when the value is missing. Unknown commands/options fail rather than being ignored. `validate --package` needs no testcase selection; `validate --selected` and `run` require an explicit selection such as --all, --suite, --case, or --tag. `--package` and `--selected` are mutually exclusive.

## 15. Frequently asked questions

### Can two sheets both contain TC001?

Yes. Give the sheets different group IDs; the full IDs become, for example, payment.TC001 and batch.TC001.

### Why was my Case ID rejected even though it is displayed correctly in Excel?

The full Case ID is used directly as an output directory name. Remove leading/trailing whitespace, a trailing `.`, path separators, `: * ? " < > |`, control characters, `.`/`..`, and reserved device names. Unicode names such as `付款.TC001` are valid when they meet these rules.

### Should I use validate --package or validate --selected?

Use `--package` before sharing, merging, building a release, or trusting a full suite: it checks unused templates/tools too. Use `--selected` only for fast local work on a known case/suite/tag selection. A run validates its selected execution plan automatically.

### Why did my FAIL become ERROR?

An assertion that evaluates false is FAIL. Tool non-zero exit, timeout, render/I/O problem, malformed JSON/XML/YAML output, or expression evaluation error is ERROR because ATT could not reliably complete the requested execution. ERROR takes precedence over FAIL for the run exit code.

### How do I consume ATT in CI?

Run `./att.sh run --all --ci-output junit,json`. Read `<run>/ci/junit.xml` for standard CI ingestion, open `<run>/report/junit.html` for a human-readable JUnit view, and use `<run>/ci/summary.json` for structured ATT metadata. Use `./att.sh validate --package --format json` for pre-run validation diagnostics.

### Why did a tool run more than once?

Only a tool action with an explicit `retry` block can run again, and each attempt is preserved under that action's `attempt-001/`, `attempt-002/`, and later directories. Timeout, assertion failures, configuration failures, render actions, and log actions are never retried.

### Can I use a shell pipeline in command?

No. Legacy string commands are tokenized by ATT, not evaluated by a shell. Put complex shell behavior in a reviewed package-local script, pass data through input/output files, and call the script as the tool command.

### Can I omit the template selector name key?

Yes, only with scalar shorthand. `PAYMENT_INVOKE` is normalized to `name: PAYMENT_INVOKE`. Use the map form when the stage needs extra values.

### Does name select a symbolic name or a path?

ATT tries global symbolic name first, then a complete path relative to templates.root. If a symbolic name equals a path, the symbolic name wins.

### Why did N/A become empty?

N/A, NA, NULL, NONE, empty, and whitespace-only values are deliberate blank markers. Do not use those exact strings as business data that must remain literal.

### Why is my optional Context value empty?

The source cell may be blank, the path may be misspelled, or the referenced action may not have executed. Inspect case.yaml and use an explicit `is null` assertion when absence is meaningful.

### When should I use ACTIONS instead of CASE?

Use ACTIONS for concise references to earlier actions in the current template. Use the canonical CASE.STAGES...TEMPLATE.ACTIONS... path for persisted cross-stage references.

### When should I use a built-in instead of a tool?

Use built-ins for pure string/type transformations. Use a tool for filesystem, process, network, database, or external-system work.

### Why does a required delimited argument reject N/A?

Required validation happens before expansion. N/A normalizes to blank and therefore fails required validation. Make the argument optional if an empty array is valid.

### Can I open reports and documentation without a server?

Yes. Generated HTML is designed for direct offline opening. Keep linked run/docs directories together so artifact links remain valid.

### Does build execute tests again?

No. It packages the latest completed persisted run and its evidence.

For the concise authoring workflow, see [ATT V2.1 Quick Start](08_Quick_Start_V2.md). The System Design is intended for framework developers; this Reference Manual is the authoritative end-user guide.
