# ATT System Design V2.0

**Version:** V2.0
**Status:** Proposed Stable Design
**Design baseline:** V1.0–V1.3 only
**Compatibility policy:** V2 configuration only; preserve design and implementation continuity
**Last updated:** 2026-07-10

---

# 1. System Overview

ATT, short for Automated Testing Tool, is a template-driven API testing framework for SIT/UAT. It loads test cases from Excel, executes ordered templates through configured external tools, validates results, and produces test reports and execution packages.

V2 is a stable consolidation of the V1.0–V1.3 design. It keeps existing terms, Java package/class boundaries, and execution concepts whenever they remain suitable. V2 has one normative configuration format and does not load, translate, or validate V1 configuration.

The main V2 goals are:

- make testcase, stage, template, action, and tool relationships unambiguous;
- require suite-local Excel configuration and validate it before execution;
- support multiple testcase groups in one workbook;
- support Chinese workbook names, sheets, columns, mappings, template names, and paths;
- consolidate runtime context into one case-owned data tree;
- preserve the established tool execution model while formalizing V2 tool parameter metadata;
- provide package-level validation and actionable error messages;
- generate JavaDoc-like HTML documentation and test reports;
- package the latest completed run with reports and per-case logs;
- provide a stable, user-friendly `att.sh` interface.

# 2. Design Principles And Continuity

## 2.1 Development continuity

V2 does not support V1 configuration syntax. Development continuity means retaining proven concepts and implementation boundaries, not accepting two configuration schemas.

The following V1.3 concepts remain:

- `config/config.yaml` as the global runtime and tool configuration;
- Excel workbook plus adjacent sidecar YAML;
- `outputDirectory`, `environment`, `timeoutSeconds`, `run`, `templates`, `report`, and `tools` configuration areas;
- stage `key`, `required`, `onFailure`, and `runWhen` semantics;
- template directories containing `template.yaml`;
- ordered template actions;
- action types such as `render`, `tool`, `assert`, and `log`;
- external tool `command`, `output`, and `arguments` concepts;
- file-based action inputs and outputs;
- existing Java packages such as `config`, `core`, `excel`, `template`, `exec`, and `validation` unless implementation work proves a concrete need to change them.

The V2 loader accepts and validates only the V2 schema documented here. It has no V1 compatibility aliases, fallback resolution, migration parser, or legacy-specific diagnostics.

## 2.2 Normative language

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative.

- Violating a MUST rule makes the selected package or suite invalid.
- Independent validation errors SHOULD be accumulated and reported together.
- All ATT configuration and template text files MUST use UTF-8.
- Logical paths use `/` on every operating system.

# 3. Core Concept Model

## 3.1 Primary and subordinate concepts

V2 has three primary reusable concepts:

| Concept | Meaning | Physical representation |
|---|---|---|
| Test Case | One executable case with input data and runtime state | One Excel row interpreted through the workbook sidecar |
| Template | One reusable workflow | A directory containing `template.yaml` |
| Tool | One external capability callable by ATT | One entry under `tools` in `config.yaml` |

Stage and action are subordinate concepts:

```text
test case ----1:n <stage>----> template ----1:n <action>----> tool
```

More precisely:

```text
Test Case
  └─ stages[]                 ordered template references owned by the case model
       └─ template            exactly one template selected from the stage's Excel column
            └─ actions[]      ordered execution steps owned by the template
                 └─ tool      exactly one tool for a tool action
```

- A stage determines the number and order of templates invoked by a test case.
- A stage does not contain template implementation logic.
- An action determines the number and order of operations within a template.
- An action does not exist outside a template.
- A tool is independent of testcase, stage, and template business semantics.

## 3.2 Stable identifiers

| Identifier | Rule |
|---|---|
| Testcase group ID | Unique within one workbook; explicit or `default` |
| Row case ID | Non-empty and unique within its testcase group |
| Full Case ID | `<groupId>.<rowCaseId>` and unique within the workbook |
| Stage key | Unique in the sidecar `stages` list |
| Template path | Unique directory path relative to `templates.root` |
| Template name | Optional; if defined, globally unique under `templates.root` |
| Action ID | Unique within one template |
| Tool key | Unique under global `tools` configuration |

Examples:

```text
payment.TC001
batch.TC001
default.TC001
```

The group prefix prevents identical row IDs in different sheets from colliding.

# 4. Package Layout

```text
att-package/
├── att.sh
├── config/
│   └── config.yaml
├── testcase/
│   ├── 支付回歸.xlsx
│   └── 支付回歸.yaml
├── templates/
│   └── payment/
│       └── local/
│           └── CT001/
│               ├── template.yaml
│               ├── request.tmp.xml
│               └── README.md
├── tools/
│   ├── invoke_payment_api.sh
│   └── README.md
├── docs/
├── output/
├── classes/
└── lib/
```

This layout continues the V1 release package structure. `classes/` and `lib/` are used by packaged execution. `output/` MAY be created on first run.

# 5. Test Suite Sidecar

## 5.1 Mandatory pairing

Every selected Excel workbook MUST have a same-basename YAML sidecar in the same directory:

```text
testcase/支付回歸.xlsx
testcase/支付回歸.yaml
```

The sidecar is the source of truth for:

- testcase sheet groups;
- the Case ID column;
- case-level data columns;
- stage count and order;
- each stage's template column;
- each stage's private data columns;
- report columns and suite-level execution defaults.

Global `config.yaml` MUST NOT provide default Excel sheet, Case ID column, data column, or stage definitions. ATT MUST NOT guess a missing sidecar or workbook layout.

## 5.2 Sidecar example

`testcase/支付回歸.yaml`:

```yaml
testCaseTemplate:
  id: payment_transfer_cases
  name: Payment Transfer Cases

excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, debitAccount=扣賬帳號, creditAccount=入賬帳號, amount=金額, currency=幣別, 預期結果(yaml)

stages:
  - key: prepare
    template: 準備模板
    dataColumns: channel=渠道, 準備參數(yaml)
    required: false
    onFailure: stop
    runWhen: normal
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, 執行參數(yaml)
    required: true
    onFailure: stop
    runWhen: normal
  - key: verify
    template: 驗證模板
    dataColumns: 驗證參數(yaml)
    required: true
    onFailure: stop
    runWhen: onSuccess
  - key: cleanup
    template: 清理模板
    dataColumns: 清理參數(yaml)
    required: false
    onFailure: continue
    runWhen: always

actionDefaults:
  onFailure: stop
  logOutput: summary

report:
  columns:
    result: 測試結果
    durationMs: 耗時毫秒
    actualResult: 實際結果
    caseLog: 案例日誌
    failedStage: 失敗階段
    failedAction: 失敗動作
    runTime: 執行時間
```

The established `testCaseTemplate`, `actionDefaults`, and `report` concepts remain available. Their V2 definitions are the only accepted definitions.

# 6. Excel Testcase Groups

## 6.1 `excel.sheet` grammar

`excel.sheet` is a comma-separated configuration string.

Multiple groups:

```yaml
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
```

This defines:

| Group ID | Excel sheet name |
|---|---|
| `payment` | `支付測試案例集` |
| `batch` | `批量測試案例集` |

If the workbook has only one testcase group, the group ID MAY be omitted:

```yaml
excel:
  sheet: 支付測試案例集
```

ATT assigns the group ID `default`.

Rules:

- Each group ID MUST be non-empty and unique.
- An omitted group ID is permitted only when exactly one sheet is configured.
- The configured sheet name MUST exist exactly once in the workbook.
- Group IDs are case-sensitive and SHOULD use stable ASCII identifiers.
- Sheet names MAY contain Chinese or other Unicode text.
- CSV-style double quoting is used if a sheet name contains a comma, equals sign, or surrounding whitespace.

Example:

```yaml
excel:
  sheet: payment="支付,本地=測試案例集", batch=批量測試案例集
```

## 6.2 Case ID

The Case ID column is configured directly under `excel`:

```yaml
excel:
  caseId: 案例編號
```

`caseId` is not a data-column mapping. It is an ATT-required structural column.

For every non-empty testcase row:

```text
fullCaseId = groupId + "." + rowCaseId
```

Example:

```text
sheet group: payment
row value: TC001
full Case ID: payment.TC001
```

The row Case ID MUST be non-empty after trimming. Full Case IDs MUST be unique across the workbook. ATT preserves the original row value for display but uses the full Case ID for selection, logs, report links, output directories, and runtime context.

## 6.3 Tags

The tags column is configured directly under `excel` and is mandatory:

```yaml
excel:
  tags: 標籤
```

`tags` is not part of `dataColumns`. It is an ATT-required structural column used by `--tag`, `--exclude-tag`, reports, documentation, and the CASE metadata tree. Every configured testcase sheet MUST contain this column. A cell MAY be blank, meaning the case has no tags. Non-blank cells are parsed as a comma-separated tag list with surrounding whitespace removed.

## 6.4 Built-in required columns

V2 has no configurable `required` column list.

ATT validates these columns itself:

- `excel.caseId` MUST exist in every configured testcase sheet;
- `excel.tags` MUST be configured and MUST exist in every configured testcase sheet;
- every column named by `excel.dataColumns` MUST exist;
- every stage `template` column MUST exist;
- every column named by `stages[].dataColumns` MUST exist;
- a required stage MUST contain a non-empty template selector in every enabled testcase row;
- an optional stage with an empty template selector is skipped for that row.

These requirements are framework rules, not user-configurable column requirements.

# 7. Data Column Grammar

## 7.1 Case data columns

`excel.dataColumns` maps Excel columns into the case root:

```yaml
excel:
  tags: 標籤
  dataColumns: debitAccount=扣賬帳號, creditAccount=入賬帳號, amount=金額, currency=幣別, 預期結果(yaml)
```

Each item has one of four forms:

| Form | Meaning |
|---|---|
| `alias=ColumnName` | Store the cell under `CASE.<alias>` |
| `ColumnName` | Store the cell under `CASE.<ColumnName>` |
| `alias=ColumnName(yaml)` | YAML-parse the cell and store it under `CASE.<alias>` |
| `ColumnName(yaml)` | YAML-parse the cell and store it under `CASE.<ColumnName>` |

Examples:

```text
${CASE.debitAccount}
${CASE.currency}
${CASE.預期結果.status}
```

There is no `CASE.fields` or `CASE.data` node. Tags are framework metadata under `${CASE.tags}` and MUST NOT be repeated in `dataColumns`.

## 7.2 Stage-private data columns

Each stage MAY define `dataColumns` using the same grammar:

```yaml
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, 執行參數(yaml)
```

The values are stored directly below that stage:

```text
${CASE.STAGES.invoke.channel}
${CASE.STAGES.invoke.執行參數.timeout}
```

Stage-private values are not copied to the case root and are not visible as unqualified case data.

## 7.3 Parsing and escaping

The configuration string uses CSV-style double quoting:

```yaml
excel:
  dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

Rules:

- comma separates items only outside double quotes;
- the first unquoted equals sign separates alias and column name;
- a trailing unquoted `(yaml)` is the YAML marker;
- double quotes are escaped as `""` inside a quoted value;
- surrounding unquoted whitespace is ignored;
- an empty alias, empty column name, duplicate alias, or malformed quote is an error;
- aliases are case-sensitive;
- aliases SHOULD use stable identifiers, but a direct ColumnName key MAY contain Chinese;
- YAML cells MUST contain a mapping, list, scalar, or null supported by the safe YAML parser;
- duplicate YAML keys and unsafe YAML tags are rejected.

## 7.4 Blank value normalization

ATT treats the following trimmed, case-insensitive cell values as blank:

```text
N/A
NA
NULL
NONE
```

An empty cell and a whitespace-only string are also blank. Normalization occurs before template lookup, scalar storage, YAML parsing, expression resolution, and tool input generation.

- A blank required stage template value is a validation error.
- A blank optional stage template value skips that stage.
- A blank case or stage data value is stored as the empty string `""`.
- When passed to a tool script, a blank value is always transmitted as `""`, never as `N/A`, `NA`, `NULL`, or `NONE`.
- A YAML document containing one of these strings as an intentionally quoted business value still normalizes to blank; authors must use a different literal if the marker itself is significant.

The `payload="請求(yaml)"(yaml)` example distinguishes literal parentheses in the column name from the YAML marker.

# 8. Stage Model

## 8.1 Stage fields

| Field | Required | Meaning |
|---|---:|---|
| `key` | yes | Stable stage identifier and runtime tree key |
| `template` | yes | Physical Excel column containing the template reference |
| `dataColumns` | no | Stage-private Excel data mapping |
| `required` | yes | Whether every enabled row must select a template |
| `onFailure` | no | `stop` or `continue`; default `stop` |
| `runWhen` | no | `normal`, `onSuccess`, `onFailure`, or `always`; default `normal` |

V2 does not define a separate stage display `name` or `templateColumn`. The single `template` field is sufficient.

`template` always names an Excel column. A sidecar cannot define a fixed template for a stage. This preserves case-row control over template choice and keeps one resolution rule.

## 8.2 Template selector cells

Every non-blank stage template cell MUST be a YAML mapping. Scalar template cells are invalid in V2.

Symbolic-name example:

```yaml
name: 本地付款
retry: 2
```

Full-path example:

```yaml
name: payment/local/CT001
retry: 2
```

The `name` key is mandatory and selects the template:

1. ATT first matches an indexed template whose `template.yaml.name` equals the value.
2. If no symbolic name matches, ATT treats the value as a full template path relative to `templates.root`.
3. Zero matches or more than one match is a validation error.

All key-value pairs in the YAML cell, including `name`, are copied directly into the current stage data tree. For example:

```text
${CASE.STAGES.invoke.name}
${CASE.STAGES.invoke.retry}
```

If a key from the template cell duplicates a key loaded through `stages[].dataColumns`, validation fails; ATT does not silently choose one value.

A blank required-stage cell invalidates the testcase before execution. A blank optional-stage cell omits that stage instance.

# 9. Template Directory Model

## 9.1 Template detection and path

A template is a directory, not a single file. A directory is a template if and only if it directly contains `template.yaml`.

```text
templates/payment/local/CT001/template.yaml
```

The full template path is:

```text
payment/local/CT001
```

Classification directories are allowed:

```text
templates/payment/                 # category only
templates/payment/local/           # category only
templates/payment/local/CT001/     # template
```

ATT recursively indexes `templates.root`. A template path MUST remain below that root after normalization. Absolute paths, `.`/`..` segments, empty segments, and path traversal are rejected.

## 9.2 Template descriptor

```yaml
name: 本地付款
description: 建立本地付款請求並調用付款 API

config:
  actionDefaults:
    onFailure: stop

actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: request.xml

  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"

  assertStatus:
    type: assert
    expression: "${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'"
```

V2 keeps the V1.3 action mapping and YAML execution order. The YAML action key is the Action ID unless an explicit `id` is configured. There is no need to change this established structure to a list.

`name` is optional. If present, it MUST be unique across indexed templates. Templates without `name` are referenced by full path.

## 9.3 Template files

A template MAY contain one or more request files:

```text
request.tmp.xml
request.tmp.json
request.tmp.yaml
```

It MAY also contain SQL, text, sample data, and README files. Each render action explicitly identifies its input file through the existing `payload` field. ATT does not infer which request file to render.

Chinese template names, directory segments, filenames, descriptions, and request content are supported as UTF-8.

# 10. Action Model

## 10.1 Ordered actions

Actions execute in YAML declaration order. Supported action types continue from V1.3:

| Type | Purpose |
|---|---|
| `render` | Render a payload and optionally save it as a file |
| `tool` | Invoke a configured external tool |
| `assert` | Evaluate an expression |
| `log` | Write structured information to the case log |

The default Action ID is the YAML action key. IDs MUST be unique within the template.

## 10.2 Failure behavior

- Default action failure behavior is `stop`.
- `onFailure: continue` records the action failure and continues the template.
- A failed assertion produces `FAIL`.
- Rendering, tool execution, parsing, timeout, or framework failures produce `ERROR`.
- Cleanup and rollback behavior remains controlled by stage `runWhen` and `onFailure`.

## 10.3 File outputs

Large output SHOULD be written beneath the current action directory:

```text
output/<RunID>/<FullCaseID>/<StageKey>/<ActionID>/
```

The case log records the path, byte count, hash, preview, duration, and status instead of duplicating the entire file.

# 11. Case Runtime Context

## 11.1 Single case-owned tree

Runtime context is a tree rooted at `CASE`. Case metadata, case data, and runtime results belong to this tree.

```text
CASE
├── caseId
├── groupId
├── rowCaseId
├── workbook
├── sheet
├── rowNumber
├── tags
├── environment
├── status
├── startedAt
├── durationMs
├── <case data columns>
└── STAGES
    └── <stageKey>
        ├── key
        ├── status
        ├── startedAt
        ├── durationMs
        ├── <stage data columns>
        └── TEMPLATE
            ├── name
            ├── path
            ├── status
            ├── startedAt
            ├── durationMs
            └── ACTIONS
                └── <actionId>
                    ├── id
                    ├── type
                    ├── tool
                    ├── status
                    ├── startedAt
                    ├── durationMs
                    ├── command
                    ├── input
                    ├── output
                    ├── outputFile
                    ├── stdout
                    ├── stderr
                    └── error
```

Only core concept keywords are uppercase: `CASE`, `STAGES`, `TEMPLATE`, `ACTIONS`, and the transient `TOOL` scope. Metadata/result properties keep the existing camelCase style, such as `caseId`, `groupId`, `startedAt`, `durationMs`, and `outputFile`. User-defined case aliases, stage keys, stage data keys, Action IDs, and tool names retain their configured spelling. Metadata and runtime values share the appropriate logical node; V2 does not add artificial `metadata`, `runtime`, `fields`, or `data` wrapper nodes. A tool action stores its tool metadata and result directly under the Action ID; it does not add another persisted `TOOL` node.

## 11.2 Normative references

```text
${CASE.caseId}
${CASE.groupId}
${CASE.rowCaseId}
${CASE.tags}
${CASE.amount}
${CASE.預期結果.status}
${CASE.STAGES.invoke.channel}
${CASE.STAGES.invoke.TEMPLATE.path}
${CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.status}
${CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.output}
```

`${CASE...}` is the normative form for case and execution data.

Map paths use dot-separated keys. YAML/tool list results additionally support zero-based `${CASE.items[0].status}` and `${CASE.items.0.status}` access. Exact Context values passed to tools retain their original string/number/boolean type; assertion evaluation treats Context values as typed operands rather than reparsing their text as operators.

## 11.3 Current action and tool convenience scopes

ATT exposes transient uppercase execution scopes while an action is running:

| Scope | Purpose |
|---|---|
| `ACTIONS.<ActionID>` | Convenient references to completed actions in the current template |
| `TOOL.input` | Current tool input |
| `TOOL.output` | Current tool parsed output |
| `TOOL.inputFile` | Current generated tool input file |
| `TOOL.outputFile` | Current allocated tool output file |

These are views over the current nodes in the `CASE` tree, not independent runtime stores. After execution, the authoritative persisted data is under `CASE.STAGES.<key>.TEMPLATE.ACTIONS...`. No lowercase alias for a core concept keyword and no `TOOLS` scope exists in V2.

## 11.4 Expression and invocation grammar

- `${path}` performs a read-only Context lookup and never invokes a tool.
- `#{name(...)}` invokes a configured tool or documented built-in function.
- Tool arguments may be named; configured external tools require named arguments matching their `arguments` contract.
- Literals are quoted strings (`'text'` or `"text"`), numbers, and `true`/`false`. Quoted commas remain within one argument.
- An exact `${path}` tool argument preserves the Context value's original type. A Context reference embedded inside a quoted literal becomes part of that string.
- Assertions support `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, parentheses, `not`, `and`, and `or`.
- Context values are bound as typed operands before expression parsing; their text cannot inject expression operators or alter precedence.

# 12. Tool Configuration

## 12.1 V2 execution fields

V2 tool configuration uses:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: 調用付款 API 並輸出 XML response
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile} ${TOOL.input.keywords}"
    output: xml
    arguments:
      requestFile:
        name: Request File
        description: 已渲染的 API request 檔案
        required: true
      environment:
        name: Environment
        description: 目標執行環境
        required: true
      keywords:
        name: Keywords
        description: 要傳遞給工具的零個或多個查詢關鍵字
        required: false
        delimit: ","
```

| Field | Purpose |
|---|---|
| `name` | Human-readable tool name |
| `description` | Tool documentation |
| `command` | V2 command template |
| `output` | Output parser type such as `xml`, `yaml`, `json`, or `txt` |
| `arguments` | Parameter contract used for validation and documentation |

`argv` is not a V2 tool field.

## 12.2 Tool argument contract

Every `arguments.<argumentKey>` descriptor contains only:

| Field | Required | Meaning |
|---|---:|---|
| `name` | yes | Display name used in diagnostics and tool reference pages |
| `description` | yes | Human-readable purpose and expected value |
| `required` | yes | Whether every invocation must provide the argument |
| `delimit` | no | Only allowed on the final declared argument; splits a multi-value input into an array |

Except for the final argument's optional `delimit`, `arguments` does not provide a value, default, expression, injection mapping, or command fragment.

The tool action remains responsible for providing invocation values through its existing call expression:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment}, keywords=${CASE.keywords})}"
```

Validation rules:

- an argument supplied by the action MUST be declared by the tool;
- every declared `required: true` argument MUST be supplied;
- omitted optional arguments are not written to the tool input;
- duplicate arguments are errors;
- `delimit` MAY appear only on the final argument descriptor and MUST contain a non-empty delimiter;
- ATT splits the final argument's normalized string value by `delimit`, trims each element, converts blank markers to `""`, and passes the result to the tool script as an array;
- array elements replace the final argument position in the process argument list in their original order; zero non-blank elements produce an empty array;
- argument metadata is used to generate tool reference documentation;
- argument metadata never injects or transforms runtime values.

## 12.3 Execution safety

File-based input remains recommended for large XML, JSON, YAML, SQL, and log content. ATT executes command parameters as a process argument array rather than constructing an unquoted shell string. A final `delimit` argument expands into the final zero or more array elements. Commands, inputs, stdout, stderr, exit status, parser result, and duration are recorded with configured secret redaction.

# 13. Global Configuration

V2 keeps the V1.3 global configuration structure:

```yaml
outputDirectory: output
environment: SIT
timeoutSeconds: 120

run:
  id:
    default: timestamp
    timestampFormat: yyyyMMdd-HHmmss

templates:
  root: templates

report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
    runTime: Run Time

tools:
  # tool definitions
```

Global config contains runtime defaults, template root, report defaults, run ID behavior, and external tool definitions. Workbook parsing and stage definitions belong to the sidecar and MUST NOT be defaulted globally.

# 14. Execution Lifecycle

```text
Load global config
  -> discover selected workbook-sidecar pairs
  -> parse testcase groups and data-column grammar
  -> index template directories
  -> validate package, suites, templates, actions, and tools
  -> select full Case IDs
  -> create run directory and run manifest
  -> for each selected case
       -> create CASE tree
       -> for each stage in sidecar order
            -> read template selector from stage template column
            -> load exactly one template directory
            -> populate stage-private data
            -> execute template actions in YAML order
            -> persist results into the CASE tree
       -> calculate case result and write case log
  -> generate result workbooks and HTML report
  -> write completed run history and latest-run pointer
```

No selected testcase begins until package-level validation succeeds.

Stage `runWhen` values retain V1 semantics:

| Value | Meaning |
|---|---|
| `normal` | Run while no prior blocking failure exists |
| `onSuccess` | Run only when prior normal stages passed |
| `onFailure` | Run only after a prior failure or error |
| `always` | Run regardless of prior status |

Case statuses are `PASS`, `FAIL`, `ERROR`, `SKIPPED`, and `INVALID`.

# 15. Validation And Diagnostics

## 15.1 Validation phases

`./att.sh validate` and the pre-run validator execute these phases:

1. Package structure and global config.
2. Workbook-sidecar pairing.
3. `excel.sheet`, mandatory `caseId`/`tags`, and data-column grammar.
4. Sheet existence, required built-in columns, blank normalization, YAML cells, and full Case ID uniqueness.
5. Stage keys, template columns, YAML template mappings, stage-private columns, and required-stage values.
6. Recursive template directory index and template-name uniqueness.
7. Template/action schema, payload files, Action IDs, and tool references.
8. Tool argument contracts, missing required arguments, and unknown action arguments.
9. Runtime/output prerequisites.

## 15.2 Diagnostic format

```text
ERROR ATT-TC-021 testcase/支付回歸.xlsx[支付測試案例集!J5]
stage 'invoke' requires template column '執行模板', but the cell is empty
for case 'payment.TC001'.
Hint: enter a template name/path, or set required: false if the stage is optional.
```

Each diagnostic contains:

- stable severity and code;
- file and YAML line/column or Excel sheet/cell where available;
- full Case ID when a case row is involved;
- concise cause and offending value;
- actionable hint;
- related locations for conflicts.

Diagnostic families:

| Prefix | Area |
|---|---|
| `ATT-PKG` | Package and filesystem |
| `ATT-CFG` | Global configuration |
| `ATT-TC` | Workbook, sidecar, group, Case ID, and data columns |
| `ATT-STG` | Stage definitions and template cells |
| `ATT-TPL` | Template directories and descriptors |
| `ATT-ACT` | Actions, payloads, and expressions |
| `ATT-TOOL` | Tool contract, execution, and parsing |
| `ATT-RPT` | Report generation |
| `ATT-DOC` | Documentation generation |
| `ATT-BLD` | Latest-run packaging |

# 16. Unicode And Chinese Support

ATT MUST support Chinese and Unicode in:

- workbook filenames;
- sheet names and testcase group display sources;
- Case ID column headers and data-column names;
- aliases and direct Case Data keys;
- cell values and YAML cell content;
- template directory paths and symbolic names;
- template descriptions, payloads, logs, and reports;
- tool names, parameter names, and descriptions.

String comparison uses Unicode NFC normalization while reports preserve original display text. Case IDs, group IDs, aliases, stage keys, template names, and paths remain case-sensitive.

Required automated fixtures include:

- a Chinese single-sheet suite using the `default` group;
- a Chinese multi-sheet suite using `payment` and `batch` groups;
- identical row Case IDs in different groups producing distinct full IDs;
- Chinese direct data keys and aliased data keys;
- Chinese template names and nested template paths;
- YAML-parsed Chinese cells;
- quoted sheet/column names containing comma, equals sign, and parentheses.

# 17. Output And Logging

```text
output/<RunID>/
├── run.yaml
├── events.jsonl
├── report/
│   ├── index.html
│   ├── cases/payment.TC001.html
│   └── assets/
├── workbooks/
│   └── 支付回歸.result.xlsx
└── payment.TC001/
    ├── case.yaml
    ├── payment.TC001.<date>.<time>.<seq>.log
    └── invoke/
        └── callApi/
            ├── input.yaml
            └── response.xml
```

Full Case IDs are safe logical identifiers. When converted to physical paths or URLs, ATT MUST validate and encode unsafe filesystem/URL characters without changing the identifier stored in reports and runtime context.

The case log records data in execution order and includes group, sheet, row, template, action, tool, input, command, stdout, stderr, parsed output, status, duration, and artifact references.

# 18. JavaDoc-like Package Documentation

```sh
./att.sh docs
```

ATT generates offline HTML under `build/docs/`:

```text
build/docs/
├── index.html
├── testcases/index.html
├── templates/index.html
├── tools/index.html
├── search-index.json
├── single-page.html
└── assets/
```

Documentation covers:

- workbooks, testcase groups, sheets, full Case IDs, mappings, stages, and report fields;
- template paths, symbolic names, descriptions, request files, and ordered actions;
- tool command/output metadata and each argument's name, description, and required flag;
- cross-links from case → stage → template → action → tool;
- inbound references from tools/templates back to their callers;
- Unicode-aware search for Chinese names, paths, IDs, tags, and descriptions.

`att.sh docs --single-page` selects the self-contained `single-page.html` entry point; the standard command retains the multi-page JavaDoc-like layout.

# 19. JavaDoc-like HTML Test Report

Every completed run generates an offline HTML report containing:

- an index of all selected full Case IDs;
- group ID, row Case ID, sheet, row, description, and case data;
- run/environment/version metadata;
- a run summary showing total selected cases, executed cases, passed, failed, errored, skipped, and invalid counts;
- run start/end time and total elapsed duration;
- aggregate case duration, minimum/maximum/average case duration, and the slowest cases;
- pass rate calculated as `PASS / (PASS + FAIL + ERROR)`, with the denominator displayed;
- per-workbook and per-testcase-group result counts and elapsed durations;
- filters by workbook, group, result, tag, stage, and text;
- the ordered stage/template/action/tool tree;
- assertions with expected and actual values;
- redacted tool inputs, command, exit status, stdout/stderr previews, and artifacts;
- links to complete case logs and output files;
- errors with code, source location, cause, and hint.

The report MUST work when opened directly from disk and MUST escape workbook, template, and tool content.

The summary is generated from the same completed run records used by the case index. Counts on the summary page MUST reconcile exactly with the indexed cases; a mismatch is a report-generation error.

# 20. ATT Command Line

Running `./att.sh` without arguments displays help and exits successfully. `./att.sh --help` provides the same output.

```text
Usage: ./att.sh <command> [options]

Commands:
  run              Validate and execute selected test cases
  validate         Validate package and selected suites
  docs             Generate testcase/template/tool HTML documentation
  report           Regenerate HTML report for an existing run
  build            Archive the latest completed run
  version          Print ATT version
  help             Show help
```

Key run options:

| Option | Meaning |
|---|---|
| `--suite <xlsx>` | Select one workbook; repeatable |
| `--all` | Select every enabled case in every valid workbook-sidecar pair |
| `--case <fullCaseId>` | Select a full Case ID such as `payment.TC001` |
| `--tag <tag>` | Include cases by tag |
| `--exclude-tag <tag>` | Exclude cases by tag |
| `--dry-run` | Validate and display the resolved plan without running tools |
| `--fail-fast` | Stop scheduling cases after the first failure/error |
| `--run-id <id>` | Override the timestamp run ID |
| `--output-dir <dir>` | Override output directory |
| `--format human|json` | Select feedback format |
| `--verbose` | Show action-level feedback |
| `--quiet` | Show warnings, errors, and final summary only |

`run` never implicitly means all cases. Users must provide `--all`, a suite, a full Case ID, or a filter resolving to an explicit selection.

Progress example:

```text
[1/5] Validating package ........................ PASS (2 sheets, 24 cases, 12 templates, 8 tools)
[2/5] Selecting cases ........................... PASS (24 selected)
[3/5] Running payment.TC001 本地付款成功 ......... PASS (1.42s)
[3/5] Running batch.TC001 批量付款成功 ........... PASS (2.31s)
[4/5] Generating HTML and Excel reports .......... PASS
[5/5] Run complete: PASS 24, FAIL 0, ERROR 0, SKIPPED 0
Report: output/20260710-153000/report/index.html
```

# 21. Latest Completed Run Build

```sh
./att.sh build
```

ATT reads the atomically updated `output/latest-run.yaml` and archives the latest run whose `run.yaml` status is `COMPLETE`.

Default output:

```text
dist/att-<RunID>.tar.gz
```

The archive includes:

- HTML report;
- result workbooks;
- run metadata and structured events;
- every selected full Case ID's complete execution log;
- artifacts referenced by the report;
- effective global config, sidecars, and template descriptors with secrets redacted;
- `MANIFEST.yaml` containing version, run ID, summary, file list, and SHA-256 hashes;
- instructions for opening the report and verifying the archive.

Build fails if the latest pointer is missing, the run is incomplete, an expected case log/artifact is missing, or hash verification fails.

# 22. Stability And Security

- YAML parsing MUST reject duplicate keys and unsafe object tags.
- XML parsing MUST disable DTD and external entities.
- Excel/ZIP loading MUST enforce resource limits.
- Template and output paths MUST remain under configured roots.
- Tool processes MUST have timeouts and controlled termination.
- Reports/logs MUST redact configured secrets and escape untrusted text.
- Writes to run metadata and latest-run pointers MUST be atomic.
- Every completed run records ATT version, Java version, OS, locale, timezone, effective configuration hashes, workbook hashes, and template hashes.

# 23. Implementation Continuity

V2 implementation SHOULD evolve the existing packages rather than introduce parallel replacements:

| Existing package/class area | V2 responsibility |
|---|---|
| `config.FrameworkConfig`, `StageConfig`, `ToolConfig` | extend sidecar/group/data-column and tool argument metadata models |
| `excel.ExcelTestSuiteLoader` | load multiple sheet groups, build full Case IDs, and parse case/stage data columns |
| `core.TestCase`, `CaseRuntimeContext`, `Contexts` | represent and resolve the authoritative CASE tree |
| `template.StageTemplateLoader`, `StageTemplateRunner`, `TemplateAction` | resolve template directories and execute retained ordered action mappings |
| `exec.ToolInvoker`, `CommandRunner` | validate parameter contracts, normalize blanks, and expand the final delimited argument |
| `validation` | package, workbook, template, stage, action, and tool diagnostics |
| `excel.ExcelReportWriter` | write full Case IDs and HTML report links into result workbooks |

Class or package renaming is out of scope unless a later implementation plan demonstrates a concrete conflict.

# 24. Testing Strategy

| Layer | Required coverage |
|---|---|
| Unit | sheet/data-column grammar, quoting, blank-marker normalization, full Case ID construction, uppercase CASE path resolution, argument validation |
| Excel fixtures | mandatory tags, single/multi-sheet Chinese workbooks, duplicate IDs, missing built-in columns, YAML template/data cells |
| Template fixtures | nested/Chinese paths, symbolic-name/full-path resolution through cell `name`, multiple request files, missing `template.yaml`, duplicate names |
| Tool contract | required/optional/unknown parameters, final `delimit` argument, blank conversion, timeout, exit code, parsers |
| Integration | validate → select → execute → CASE tree → workbook/HTML report → build archive |
| Golden tests | help, progress, diagnostics, documentation, report summaries/case pages, manifest |
| Security | YAML tags, XXE, path traversal, HTML injection, secret leakage, command argument handling |
| Recovery | interrupted runs, atomic latest pointer, missing build artifact |

The stable V2 release gate requires:

- Chinese single-sheet and multi-sheet end-to-end fixtures pass;
- identical row IDs in different groups remain distinct;
- all built-in column and required-stage failures have location-aware diagnostics;
- every tool reference page includes argument name, description, and required state;
- final delimited tool arguments preserve array order and convert blank elements to `""`;
- report and documentation links pass offline link checking;
- HTML summary totals and durations reconcile with the complete case index and run records;
- archives reproduce their manifest file list and hashes;
- every V2 configuration field is covered by positive and negative schema tests;
- no undocumented fallback configuration changes the selected sheet, column, stage, template, action, or tool.

# 25. Complete Example

## 25.1 Workbook

Workbook:

```text
testcase/支付回歸.xlsx
```

Sheet `支付測試案例集`:

| 案例編號 | 案例名稱 | 標籤 | 扣賬帳號 | 入賬帳號 | 金額 | 幣別 | 預期結果 | 執行模板 | 渠道 | 執行參數 | 驗證模板 | 驗證參數 |
|---|---|---|---|---|---:|---|---|---|---|---|---|---|
| TC001 | 本地付款成功 | smoke,付款 | 111111 | 222222 | 100 | HKD | `status: SUCCESS` | `name: 本地付款` | MOBILE | `timeout: 30` | `name: payment/common/驗證成功` | `checkDb: true` |

Sheet `批量測試案例集` MAY also contain a row whose row Case ID is `TC001`. The resulting IDs are different:

```text
payment.TC001
batch.TC001
```

## 25.2 Sidecar

```yaml
testCaseTemplate:
  id: payment_transfer_cases
  name: Payment Transfer Cases

excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, debitAccount=扣賬帳號, creditAccount=入賬帳號, amount=金額, currency=幣別, 預期結果(yaml)

stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, 執行參數(yaml)
    required: true
    onFailure: stop
    runWhen: normal
  - key: verify
    template: 驗證模板
    dataColumns: 驗證參數(yaml)
    required: true
    onFailure: stop
    runWhen: onSuccess

report:
  columns:
    result: 測試結果
    durationMs: 耗時毫秒
    caseLog: 案例日誌
```

## 25.3 Template

`templates/payment/local/CT001/template.yaml`:

```yaml
name: 本地付款
description: 產生本地付款 request 並調用 API

actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: request.xml

  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"

  assertStatus:
    type: assert
    expression: "${ACTIONS.callApi.output.Response.Status} == '${CASE.預期結果.status}'"
```

`templates/payment/local/CT001/request.tmp.xml`:

```xml
<PaymentRequest>
  <CaseId>${CASE.caseId}</CaseId>
  <DebitAccount>${CASE.debitAccount}</DebitAccount>
  <CreditAccount>${CASE.creditAccount}</CreditAccount>
  <Amount>${CASE.amount}</Amount>
  <Currency>${CASE.currency}</Currency>
  <Channel>${CASE.STAGES.invoke.channel}</Channel>
</PaymentRequest>
```

## 25.4 Tool

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: 調用付款 API 並返回 XML
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestFile:
        name: Request File
        description: 已渲染的付款 request XML
        required: true
      environment:
        name: Environment
        description: 目標 ATT 執行環境
        required: true
```

## 25.5 Runtime path

After `callApi`, the authoritative result is available at:

```text
${CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.output}
```

Within the same template, the retained convenience form is:

```text
${ACTIONS.callApi.output}
```

# 26. Acceptance Criteria

The V2 System Design is complete when:

- V2 has one configuration schema and contains no V1 configuration loader, validator, alias, fallback, or migration behavior;
- established terms, action execution model, tool command/output concepts, and Java package/class names are retained where they fit V2;
- testcase, template, and tool are primary concepts, while stage and action remain subordinate ordered concepts;
- every workbook requires an adjacent sidecar and global Excel defaults are forbidden;
- `excel.sheet` supports multiple `groupId=sheetName` entries and a single implicit `default` group;
- full Case IDs use `<groupId>.<rowCaseId>` and are unique across the workbook;
- `excel.caseId` is an ATT-required structural column outside data mappings;
- `excel.tags` is mandatory and is outside `dataColumns`;
- configurable required-column lists are removed;
- case and stage `dataColumns` use the documented alias/direct/YAML/CSV-quoted grammar;
- `stages[].template` is the only stage template-column field and fixed sidecar templates are unsupported;
- every non-blank stage template cell is a YAML mapping with mandatory `name`, and every key-value pair is copied into stage data;
- `N/A`, `NA`, `NULL`, `NONE`, empty, and whitespace-only values normalize to blank and are passed to tool scripts as `""`;
- stage-private data is stored beneath its stage node;
- the authoritative runtime context is the documented CASE tree without `fields`, `data`, `metadata`, or `runtime` wrapper nodes;
- core concept Context keywords are uppercase (`CASE`, `STAGES`, `TEMPLATE`, `ACTIONS`, and transient `TOOL`), while metadata/result properties retain camelCase;
- tool action metadata and results are stored directly below the Action ID without a persisted `TOOL` wrapper node;
- normative paths include `${CASE.caseId}`, `${CASE.amount}`, `${CASE.STAGES.invoke.channel}`, and the complete action result path;
- retained `ACTIONS` and `TOOL` scopes are views over the CASE tree rather than separate persisted stores;
- tool argument metadata contains `name`, `description`, `required`, plus optional `delimit` only for the final argument;
- `argv` is not supported, and the final delimited argument is expanded into a process argument array;
- unknown tool arguments and missing required tool arguments fail validation;
- templates are directories containing `template.yaml` and support nested/Chinese paths and symbolic names;
- package validation reports testcase, stage, template, action, and tool errors before execution;
- Chinese workbooks, sheets, mappings, keys, templates, logs, reports, and documentation are covered by tests;
- `att.sh` provides default help, progress feedback, explicit `--all`, validation, docs, report, and latest-run build commands;
- JavaDoc-like package documentation covers testcases, templates, and tools;
- JavaDoc-like reports include every case, description, result, detailed execution tree, logs, and artifacts;
- HTML report summaries include total, pass/fail/error/skip/invalid counts, pass rate, start/end time, and elapsed/aggregate/min/max/average durations;
- latest completed run archives include reports and every case log with a verifiable manifest.
