Warning: truncated output (original token count: 55505)
Total output lines: 2862

# ATT V3.7.1 Reference Manual

Author: Jeffrey + ChatGPT
Version: 3.7.1
Status: Normative end-user documentation; generated from modular sources

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->

**Contents**

- [01 Overview and Product Model](#01-overview-and-product-model)
  - [Product model](#product-model)
  - [Execution modes are peers](#execution-modes-are-peers)
  - [Resources are peers](#resources-are-peers)
  - [Package boundaries](#package-boundaries)
  - [How to use this manual](#how-to-use-this-manual)
- [02 Test Authoring](#02-test-authoring)
  - [Authoring contracts](#authoring-contracts)
  - [2.1 Workbook](#21-workbook)
  - [2.2 Stage](#22-stage)
  - [2.3 Template](#23-template)
  - [2.4 Flow](#24-flow)
  - [2.5 Authoring lifecycle](#25-authoring-lifecycle)
  - [Test data ownership](#test-data-ownership)
  - [Testdata registry and input mapping](#testdata-registry-and-input-mapping)
- [03 Actions and Typed Values](#03-actions-and-typed-values)
  - [Action types](#action-types)
  - [Separate logical values from representations](#separate-logical-values-from-representations)
  - [Project-file expressions return String](#project-file-expressions-return-string)
  - [Tool, DB and Flow results](#tool-db-and-flow-results)
  - [Tool evidence collectors](#tool-evidence-collectors)
  - [Log: typed value to Case log](#log-typed-value-to-case-log)
  - [Expressions and variable scope](#expressions-and-variable-scope)
  - [Resource output evidence](#resource-output-evidence)
  - [Action output and evidence paths](#action-output-and-evidence-paths)
  - [Common retry and Boolean conditions](#common-retry-and-boolean-conditions)
- [04 Runtime and Context Model](#04-runtime-and-context-model)
  - [Identity roots](#identity-roots)
  - [META field inventory and lifecycle](#meta-field-inventory-and-lifecycle)
  - [Invocation and scope rules](#invocation-and-scope-rules)
  - [Optional lookup and compatibility](#optional-lookup-and-compatibility)
  - [Lifecycle navigation](#lifecycle-navigation)
- [05 Expressions and Built-ins](#05-expressions-and-built-ins)
  - [Unified expression engine](#unified-expression-engine)
  - [Project-file String expressions](#project-file-string-expressions)
  - [Testdata input mapping syntax](#testdata-input-mapping-syntax)
  - [Operators](#operators)
  - [Built-in functions](#built-in-functions)
  - [Expression scope and errors](#expression-scope-and-errors)
  - [Retry-condition lifecycle](#retry-condition-lifecycle)
- [06 Execution Modes](#06-execution-modes)
  - [6.1 Run Mode](#61-run-mode)
  - [6.2 Standalone Debug](#62-standalone-debug)
  - [Debug troubleshooting and MQ payload paths](#debug-troubleshooting-and-mq-payload-paths)
  - [6.3 Load Mode](#63-load-mode)
  - [Load execution ID initialization](#load-execution-id-initialization)
- [07 Resources and Integrations](#07-resources-and-integrations)
  - [7.1 Operation Result and Evidence](#71-operation-result-and-evidence)
  - [7.2 Tool](#72-tool)
  - [Tool-definition `command` expressions](#tool-definition-command-expressions)
  - [Inline Tool descriptor fields](#inline-tool-descriptor-fields)
  - [7.3 DBHelper](#73-dbhelper)
  - [Dbhelper configuration](#dbhelper-configuration)
  - [7.4 MQHelper](#74-mqhelper)
  - [Descriptor configuration](#descriptor-configuration)
  - [7.5 HTTPHelper](#75-httphelper)
  - [7.6 SSHHelper: logical SSH targets](#76-sshhelper-logical-ssh-targets)
- [08 Reliability and Execution Control](#08-reliability-and-execution-control)
  - [Assertion and status](#assertion-and-status)
  - [`runWhen` and `onFailure`](#runwhen-and-onfailure)
  - [Timeout](#timeout)
  - [Retry and attempts](#retry-and-attempts)
  - [Evidence collectors](#evidence-collectors)
  - [Transaction/resource lifecycle](#transactionresource-lifecycle)
  - [Aggregation](#aggregation)
  - [Stage execution controls](#stage-execution-controls)
  - [Tool timeout precedence](#tool-timeout-precedence)
  - [Direct DB timeout and retry eligibility](#direct-db-timeout-and-retry-eligibility)
- [09 Configuration and Environments](#09-configuration-and-environments)
  - [Configuration layers and precedence](#configuration-layers-and-precedence)
  - [Ignore or disable ATT-owned configuration with `x-`](#ignore-or-disable-att-owned-configuration-with-x)
  - [Multi-environment profiles in current ATT](#multi-environment-profiles-in-current-att)
  - [Schema catalog](#schema-catalog)
  - [Global configuration](#global-configuration)
  - [Identifier and path constraints](#identifier-and-path-constraints)
  - [Topology and secrets](#topology-and-secrets)
  - [Cross-mode consistency](#cross-mode-consistency)
  - [Separate configuration files](#separate-configuration-files)
  - [`config.report.fileNamePattern`](#configreportfilenamepattern)
  - [Feature configuration owners](#feature-configuration-owners)
- [10 CLI Reference](#10-cli-reference)
  - [Commands](#commands)
  - [Command syntax](#command-syntax)
  - [Typed overrides and Quick Load](#typed-overrides-and-quick-load)
  - [Debug inputs and outputs](#debug-inputs-and-outputs)
  - [Exit codes](#exit-codes)
  - [Complete option matrix](#complete-option-matrix)
- [11 Results, Reports, and Evidence](#11-results-reports-and-evidence)
  - [Run directory](#run-directory)
  - [Human HTML report](#human-html-report)
  - [Tool evidence collector failures](#tool-evidence-collector-failures)
  - [Result workbook](#result-workbook)
  - [JUnit XML](#junit-xml)
  - [CI JSON summary](#ci-json-summary)
  - [Run manifest and reproducibility](#run-manifest-and-reproducibility)
  - [Documentation, archive, and clean](#documentation-archive-and-clean)
  - [Run, execution and evidence navigation](#run-execution-and-evidence-navigation)
  - [Generated-output schema summary](#generated-output-schema-summary)
  - [Reading case.log and case.yaml](#reading-caselog-and-caseyaml)
- [12 Validation and Troubleshooting](#12-validation-and-troubleshooting)
  - [Where to look first](#where-to-look-first)
  - [Start with validation](#start-with-validation)
  - [Common questions](#common-questions)
  - [Security reminders](#security-reminders)
  - [Validation JSON contract](#validation-json-contract)
- [13 CI, Packaging, and Operations](#13-ci-packaging-and-operations)
  - [Development/release gates](#developmentrelease-gates)
  - [Runtime dependencies](#runtime-dependencies)
  - [Documentation operations](#documentation-operations)
  - [CI and environment promotion](#ci-and-environment-promotion)
- [Appendix A — Schema and Version Matrix](#appendix-a-schema-and-version-matrix)
- [Appendix B — Compatibility and Deprecated Aliases](#appendix-b-compatibility-and-deprecated-aliases)
- [Appendix C — Migration Notes](#appendix-c-migration-notes)
  - [ATT 3.7.1 testdata migration](#att-371-testdata-migration)
  - [Historical schema migration](#historical-schema-migration)
  - [Debug schema migration](#debug-schema-migration)
  - [Global configuration migration](#global-configuration-migration)
  - [Environment profile migration](#environment-profile-migration)
- [Appendix D — Limits, Security Guarantees and Advanced Diagnostics](#appendix-d-limits-security-guarantees-and-advanced-diagnostics)
  - [Limits and defaults](#limits-and-defaults)
  - [Collector projection and redaction guarantees](#collector-projection-and-redaction-guarantees)
  - [Advanced diagnostics](#advanced-diagnostics)
## 01 Overview and Product Model

ATT separates test intent from integration mechanics. Test data is versioned in workbook/sidecar/snapshot form; Templates and Flows define reusable behavior; Resources connect that behavior to external systems.

### Product model

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- project-file expression / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A **Testcase** is one normalized workbook row. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper, MQHelper, HTTPHelper or SSHHelper used by Actions or permitted expression calls.

### Execution modes are peers

Run, Debug and Load adapt different inputs into the same execution-neutral Context and reusable components:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ/HTTP/SSH |
| Debug | `att-debug/v1.1` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.5` scenario | one or more Template, Flow or Tool workloads repeatedly |

Reusable Templates/Flows depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`. Execution mode and scheduler identity are framework diagnostics in retained evidence, not expression data.

### Resources are peers

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are independent resource types. They differ in configuration and lifecycle, while Actions publish native typed results through `output.result` and keep optional presentation evidence separate. Public expressions should consume Action results/evidence rather than resource-internal connection/process state. SSH resource operations use the common `ssh.<helperId>.<operation>` form inside a normal `type: tool` Action.

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

### Package boundaries

A normal package contains `config/`, `testcase/`, `templates/`, `tools/`, `schemas/` and generated `output/`. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

For a guided package build, use [Quick Start](quick-start.md). The rest of this manual is normative lookup documentation.

### How to use this manual

| Goal | Go to |
|---|---|
| Build the first ATT package | [Quick Start](quick-start.md) |
| Understand the core ATT model | Chapters 1–5 |
| Configure DB/MQ/HTTP/SSH | [Resources](reference/05_resources/index.md) |
| Find a CLI option | [CLI Reference](reference/10_cli.md) |
| Diagnose a failure | [Validation and Troubleshooting](reference/12_validation_diagnostics.md) |
| Upgrade an older package | [Appendix C](reference/appendices/migrations.md) |

Reference defines the public contract; README, Quick Start and examples explain that contract for narrower tasks. Each contract has one semantic owner; other chapters summarize and link to that owner.

## 02 Test Authoring

### Authoring contracts

This chapter explains the normal day-to-day workflow in the same order that data moves through ATT.

| Need | Use |
|---|---|
| Stage execution entry point | Template |
| Reusable Template logic | Flow |
| One ordered operation | Action |
| External capability | Resource |
| Case/stage business input | EXEC.INPUT |
| Cross-Action mutable state | EXEC.VARS |

### 2.1 Workbook

#### Workbook, sidecar, and snapshot relationship

Every `.xlsx` workbook requires a YAML sidecar and generated XML snapshot with the same basename in the same directory:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

The sidecar maps Excel structure into ATT concepts. It owns the sheet mapping, headers, case data, ordered stages, and optional report-column labels. Timeout and retry policy do not belong to the workbook.

```yaml
schemaVersion: att-sidecar/v2.2
id: paymentRegression
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, options=執行參數(yaml)
    required: true
    runWhen: normal
    onFailure: stop
```

The root `id` is mandatory and must be unique across the package. `excel.sheet` accepts one sheet name or comma-separated `groupId=sheetName` entries. If one sheet is given without a group ID, ATT uses `default`. Full Case IDs always have the form `workbookId.groupId.rowCaseId` and must be unique across the package.

After editing Excel, run `./att.sh snapshot --suite testcase/payment_regression.xlsx`. The generated `payment_regression.xml` uses schema `att-testcases/v2.4` and stores only normalized sidecar-mapped semantics. It preserves group, Case, tag, map/list, and stage order, uses explicit value types, and excludes styles and unrelated workbook content. String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA; literal `]]>` content is split across adjacent CDATA sections and reconstructs exactly when parsed. Spaces/tabs immediately before LF use `&#32;`/`&#9;` between CDATA sections, preserving the value without Git trailing-whitespace warnings. Review and commit the XML with the xlsx; do not edit it manually.

Ordinary `run` and every `validate` mode remain read-only and fail before output creation if the XML is missing, invalid, non-canonical, or stale. `run --update-snapshot` explicitly permits ATT to refresh only changed snapshots for the selected complete workbooks before applying the same verification and validation gates. It never writes partial Case/tag snapshots, does not invoke tools during update, rejects snapshot symlinks, and also performs the authorized update when combined with `--dry-run`. Byte-identical snapshots are not rewritten.

#### Mapping data columns

`dataColumns` accepts:

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

Ordinary columns enter Context as strings. A `(yaml)` column parses the displayed cell value into a YAML scalar, list, or map.

Double quotes protect commas, equals signs, and parentheses:

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

The final `(yaml)` is the ATT parsing marker. In the last example, the physical Excel header is `請求(yaml)`.

#### Blank values

`N/A`, `NA`, `NULL`, `NONE`, empty cells, and whitespace-only values normalize to blank. An ordinary blank data value becomes the empty string. A blank `(yaml)` cell remains blank rather than being parsed.

A required stage selector rejects a blank value. An optional stage with a blank selector is skipped.

#### Formula, date, percentage, and scientific notation cells

V2.4 rejects formula cells in configured Case ID, tags, case-data, stage-selector, and stage-data columns. Formula definitions and cached/displayed results can diverge and therefore cannot produce a trustworthy semantic snapshot. Recalculate in Excel and paste the result as a literal value, or calculate it in a dedicated ATT step.

Merged regions intersecting configured testcase columns below `excel.headerRows` are likewise rejected. Merged presentation cells wholly inside the configured header area remain allowed.

For non-formula cells, ATT imports the displayed text. The exact representation follows the workbook's cell format and the runtime locale:

| Excel value and format | Context value |
|---|---|
| `45292` formatted `yyyy-mm-dd` | `2024-01-01` |
| `0.125` formatted `0.0%` | `12.5%` |
| `123000` formatted `0.00E+00` | `1.23E+05` |
| `000123` stored/formatted as text | `000123` |

An ordinary column remains a string. A `(yaml)` column may convert displayed text into another YAML type. Quote a YAML scalar when text such as a date, percentage, scientific number, account number, or code must stay a string.

#### Multi-row headers

`headerRows: 2` means rows 1–2 are headers and data begins at row 3. ATT scans each physical column top-to-bottom and uses its last non-empty trimmed header cell:

```text
Row 1: Basic data |           | Execution |
Row 2: Case ID    | Case name | Template  | Parameters
Effective: Case ID, Case name, Template, Parameters
```

ATT does not concatenate parent and child labels. Header matching removes spaces, tabs, line breaks, non-breaking spaces, and other Unicode whitespace from both the effective Excel header and configured sidecar/report label; matching otherwise remains case-sensitive. For example, `案例 編號`, `案例\n編號`, and `案例編號` identify the same column. Every effective header must exist exactly once after this normalization, so two physical headers that differ only by whitespace are a duplicate-header error. Testcase loading and result-workbook writing use this same projection; result columns that do not already exist are written to the final header row.

#### Workbook sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override timeout, retry, tools, dbhelpers, template root, environment, or output root.

### 2.2 Stage

Each sidecar stage has a dot-free `key` and a `template` field naming the physical Excel selector column. The selector cell may contain a symbolic template name, full relative template path, or YAML map:

| Cell value | Meaning |
|---|---|
| `PAYMENT_INVOKE` | Symbolic-name shorthand |
| `payment/local/CT001` | Full-path shorthand relative to `templates.root` |
| `name: PAYMENT_INVOKE` | Explicit symbolic-name map |
| `name: PAYMENT_INVOKE` plus other keys | Template selection plus stage-private row data |

ATT first resolves `name` as a globally unique symbolic name. Only when no symbolic name matches does it try a complete relative template path. Absolute paths, partial paths, and paths escaping `templates.root` are invalid.

All selector-map keys, including `name`, are copied into the stage Context. `stages[].dataColumns` adds more stage-private values. A duplicate key between the selector map and stage data columns is an error.


Stage `required`, `runWhen` and `onFailure` behavior is defined in [Reliability](reference/08_reliability_execution_control.md).

### 2.3 Template

A directory is a callable Template only when it directly contains template.yaml. ATT uses att-template/v3.6. Each Template has a non-empty ordered actions map and a required description.

Each Action has a type-specific contract. A project-file expression such as `&{templates/payment/request.xml}` returns the exact UTF-8 file content as a String without creating a file. Tool/DB/HTTP/MQ/SSH actions publish the native typed operation result. Log formats typed values for human observation. Assign publishes values to EXEC.VARS, and Flow runs in a nested Action scope.

See [Actions and Typed Values](reference/14_actions.md) for the complete field list, examples, typed result/evidence model, HTTP/MQ/SSH boundaries and migration guidance. [Expressions and Built-ins](reference/07_expressions.md) covers the shared expression language; [Load](reference/04_execution_modes/load.md) owns ID initialization.

### 2.4 Flow

A Flow is reusable Template logic, declared in `flow.yaml` using `att-flow/v3.6`. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. A Template invokes it through a Flow Action with `use: common.payment.v1`. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](reference/14_actions.md) owns Flow results and Assign behavior; [Context](reference/03_runtime_context.md) owns scope lifetime.

### 2.5 Authoring lifecycle

After changing a Workbook, generate its Snapshot, review and commit the diff. After changing a Sidecar, Template, Flow or Resource, run `./att.sh validate --package`. Use [Debug](reference/04_execution_modes/debug.md) to isolate an authoring check and [Run](reference/04_execution_modes/run.md) to execute Testcases. Follow [Quick Start](quick-start.md) to build the first package.

### Test data ownership

Workbook/Sidecar/Snapshot defines Testcase data. Case and Stage business inputs enter `EXEC.INPUT`; [Context](reference/03_runtime_context.md) defines their scope and lifetime. Environment selection belongs to [Configuration](reference/09_configuration.md).

### Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them only from a Case, Stage, Debug `inputs`, or Load workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- Run Case/Stage mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.PROJECT`, `META.SOURCE`, or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus `META.TEMPLATE`.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.PROJECT`, `META.SOURCE`, `META.TARGET`, or `META.TEMPLATE`. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

Every mode rejects references to `EXEC.INPUT` (the value being built), `EXEC.VARS`, `EXEC.ACTIONS`, Action `output`, and invocation-scoped helper metadata. The V1 mapping grammar evaluates literals, selected-record `@{...}` references, and `${...}` Context references; built-in calls are not evaluated. `#{...}`, `&{...}`, and `%{...}` are not input-mapping expressions.

~~~yaml
schemaVersion: att-testdata/v1.0
id: accounts
records:
  - {id: "A-100", tier: gold}
  - {id: "A-200", tier: silver}
selection: {strategy: sequential, exhaustion: recycle}
~~~

Generated records are virtual and indexed; ATT materializes only the selected record. The inclusive integer range is capped at 1,000,000 records, and `%{seq}` is the only supported generated-record substitution:

~~~yaml
schemaVersion: att-testdata/v1.0
id: generatedAccounts
records:
  generate:
    seq: {from: 100, to: 999999, format: "%06d"}
  record: {id: "A-%{seq}", amount: 42}
selection: {strategy: roundRobin, exhaustion: stop}
~~~

An environment profile's `testdata` list declares the shared registry. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every configured descriptor. Templates, Flows, and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](reference/09_configuration.md), [Load](reference/04_execution_modes/load.md), and the maintainer [testdata design](system-design/testdata.md).

## 03 Actions and Typed Values

This chapter defines the active ATT action contract. Templates use att-template/v3.6. Each completed action publishes its logical typed value at output.result. Actions do not use a shared result.format/path/overwrite object. See the Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper chapters for resource configuration.

### Action types

| Type | Required fields | Result and behavior |
|---|---|---|
| tool | call | Invokes a configured Tool, built-in or helper call and preserves the native typed result. DB query/scalar/update calls are ordinary Tool calls. |
| assert | assert | Evaluates a boolean condition and records PASS or FAIL. expected and actual are optional diagnostic values. |
| log | message or value | Formats a typed value for the Case log. Its fields are message, value and format. |
| assign | name and expression | Publishes the expression's typed result below EXEC.VARS. |
| flow | use | Runs a registered Flow in a nested Action scope and restores the caller's scope on return. |

Actions run in YAML order. Where supported, an action may also define id, description, onFailure and runWhen. Action IDs are unique within their scope. Type-specific invalid fields fail validation. Action result, Log file and Log fields are not part of the current action contract.

For the lowercase `x-` convention that disables ATT-owned Action fields and keyed entries before validation or execution—and how it differs from `runWhen: false`—see [Configuration](reference/09_configuration.md#ignore-or-disable-att-owned-configuration-with-x).

### Separate logical values from representations

ATT keeps the logical operation result separate from human or wire representations:

| Boundary | Field/value | Purpose |
|---|---|---|
| Command Tool stdout | stdoutFormat | Parses external stdout into a typed result. |
| HTTP/MQ response | responseFormat | Parses external response bytes into a typed result. |
| Project-file expression | `String` | Reads one safe UTF-8 project file and preserves its exact characters after expression evaluation. |
| Abstract Map/List sent over HTTP/MQ | requestFormat | Serializes the value at the outbound boundary. |
| Log or resource evidence | format / evidence.output.format | Produces a human-readable representation. |

DB results are already typed values. Tool, Action, Template, Flow and expression results remain typed while they move through ATT.

DB query, scalar, and update operations use the first-class DBHelper call forms `db.<helper>.query(...)`, `db.<helper>.scalar(...)`, and `db.<helper>.update(...)` inside a normal `type: tool` Action. A DB call accepts one String `sql` argument plus either positional `params` or named `parameters`; `sql=&{project-relative-file.sql}` supplies package SQL content. The historical `type: db` Action is retained only by archived schema versions.

### Project-file expressions return String

The current replacement for the historical Render Action is the typed project-file value expression `&{path}`. It always returns one `String`; it never infers a document format, parses an extension, expands a glob, or creates an output file:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
~~~

`${...}` remains a Context reference and `#{...}` remains an expression/call. `&{...}` is a static, one-file locator; v1 has no glob or dynamic locator form. The locator is relative to the canonical ATT project root. A descriptor-relative `./` or `../` path is allowed only when its canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace and glob syntax fail validation.

Ordinary UTF-8 files are returned unchanged. If the file contains `${...}` or `#{...}`, ATT compiles those nodes once and evaluates them for each execution; the compiled plan is immutable and dynamic values are not reparsed as a second template. Run and Debug reuse the plan until the file fingerprint changes. Load validates and captures the selected file identity, content and compiled dependency closure before scheduling, so active iterations see a stable snapshot.

Use Assign when the String is reused by later Actions:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

For HTTP or MQ, pass the `String` as the body/payload. The resource encodes the exact text with its configured charset/CCSID. HTTP content type and MQ transport metadata remain resource-owned settings. `&{...}` is valid in Tool/Helper call arguments, Assign expressions, Log values and other typed value positions.

requestFormat is for abstract structured values such as Map or List. Such a body requires an explicit format, for example requestFormat=json. Combining requestFormat with a `String` fails; a project-file result is never silently parsed and serialized. A raw file input remains available only for resource calls that explicitly define a file argument.

### Tool, DB and Flow results

A command-backed Tool declares stdoutFormat in its Tool descriptor:

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat parses external stdout once into output.result; it is not output serialization. Call-backed Tools and DB/HTTP/MQ/SSH operations keep their native return types.

A DB action uses db and exactly one query or update block. SQL, bind parameters, transaction controls and DB evidence follow the DB action and DBHelper contracts.

A Flow action uses use with a canonical Flow ID. It runs in a fresh EXEC.ACTIONS scope and publishes its result/evidence to the caller when it returns. META.FLOW exists only while that invocation is active.

### Tool evidence collectors

A Tool Action may define first-class `evidence` collectors for diagnostics that must be gathered before the Action assertion. The lifecycle is:

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collectors are diagnostic operations, not replacement Actions. Each collector has its own typed result and does not replace or mutate the primary `output.result`:

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.VARS.requestText},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.result.replyReceived} == true
```

While the containing Action, including its assertion, is active, use these paths:

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

After publication, the same values are available below `EXEC.ACTIONS`:

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

The public shape keeps primary resource evidence and collector evidence separate:

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

When a Tool retries, collectors run for every primary attempt before that attempt's assertion. The top-level `output.evidence.collectors.<id>` is the final/winning attempt; `output.attempts[n].evidence.collectors.<id>` retains each attempt, including earlier failures. After publication, the corresponding history path is `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`.

`call` is required. `timeoutMs` is independent of the primary Tool timeout. `onFailure: continue` is the normal application-log pattern so a diagnostic collection failure does not hide the original business or assertion failure; `stop` makes the collector failure an Action error. Collector status and diagnostic remain observable, and collector failure never changes the primary logical result. Distinguish an evidence collector from an ordinary Tool/Log/Assign Action: use a collector for diagnostic data needed before the containing Tool assertion, and an ordinary Action when the collected value is normal business/test data for later assertions.

Collector results follow the normal typed-result rules. Evidence placement does not stringify a map, list or project-file `String`; helper `evidence.output` is an explicit presentation boundary. In Load, explicit collector execution is separate from helper `evidence.output` serialization. Resource-output formatting remains controlled by the Load evidence policy and is not silently substituted for or dropped in place of an author-requested collector.

### Log: typed value to Case log

Log is a presentation action and therefore has its own format field:

~~~yaml
logOrder:
  type: log
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

Log is a plain Case-log entry. The current v3.6 contract removes `level`; delete it when migrating. Historical v3.4/v3.5 Template and Flow descriptors still accept their schema-defined Log level for compatibility. Internal diagnostic severity remains separate. At least one of message or value is required. message is rendered as text. value accepts any typed value, including nested maps/lists. Exact ${...} and #{...} expressions preserve their native types; map/list children are evaluated recursively without converting numbers, booleans, nulls or nested values to strings. format accepts text, json, yaml, xml or sqlplus and controls only the emitted Case-log string. When format is present, value is required.

When both message and value are supplied, Log emits the message, a newline, then the formatted value. output.result is that emitted string. A project-file String is emitted as-is when used as a value; Log does not infer or attach a document format. Log does not read a file and has no fields map. Put a typed map/list in value for structured log content.

### Expressions and variable scope

Action expressions use the regular ATT expression engine. A complete ${...} or #{...} expression keeps its result type; embedding an expression in surrounding text produces a String. See [Expressions and Built-ins](reference/07_expressions.md).

assign publishes its typed value once below EXEC.VARS.<name>. The name must match [A-Za-z_][A-Za-z0-9_]* and be unique within the Case. Values assigned in one Stage are available to later Stages. Action-local output is available at output.* while an Action runs and at EXEC.ACTIONS.<id>.output.* after publication. Flow invocation creates a temporary Action namespace; publish values to EXEC.VARS when the caller needs them after the Flow returns.

### Resource output evidence

Resource evidence is separate from the logical result. A helper may configure an optional evidence.output presentation policy:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

This adds a bounded human-readable snapshot beside operation metadata; it does not change output.result or response parsing. In Load, evidence.resources.output accepts inherit (default) or none. none skips resource-output formatting and file materialization. Metrics-only iterations create no execution directory. When iteration evidence is retained, eligible resource output is formatted lazily into that workspace.

### Action output and evidence paths

| Path | Meaning and availability |
|---|---|
| `output.result` | Primary typed Action result while the Action is active, including its assertion. |
| `output.evidence.collectors.<id>.result` | Typed result of an active Tool evidence collector. |
| `output.evidence.collectors.<id>.status` | Collector `PASS`/`ERROR` status while the Action is active. |
| `output.evidence.collectors.<id>.error` | Bounded failure summary with a non-blank `message` when the collector fails. |
| `output.evidence.collectors.<id>.evidence` | Preserved bounded/redacted underlying Tool/resource evidence, including resource identity and native failure fields when supplied. |
| `EXEC.ACTIONS.<actionId>.output.result` | Published primary typed result after the Action completes. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | Published final/winning collector result. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | Published final/winning collector status. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and preserved operation evidence. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | Collector result/status for a specific retry attempt; earlier attempts remain after a later success. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | Failure summary and underlying evidence for that specific collector attempt. |

Strings, numbers, booleans, null, maps and lists remain typed across Action/Template/Flow boundaries.

### Common retry and Boolean conditions

Tool Actions, including retry-capable DB query/scalar calls, share the `retry` contract. `maxAttempts` (2–10), `intervalMs` (0–3600000) and a non-empty `retryOn` list (ASSERTION/TIMEOUT) remain required. `when` is an optional non-empty Boolean expression String. Existing restrictions on mutating DB updates and SSH transfers still apply.

~~~yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [TIMEOUT]
  when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

Each attempt executes its operation, publishes current result/evidence/diagnostic, evaluates its assertion where applicable, and selects a retry category. Only after `retryOn` matches, and while attempts remain, does ATT evaluate `when`. Omitting it preserves ordinary retry behavior. True permits the interval wait and another attempt; false preserves the current TIMEOUT/FAIL and stops. Successful attempts, category mismatches and exhausted attempts do not evaluate the gate.

The condition can inspect `output.status`, `output.result`, `output.evidence`, `output.diagnostic`, the one-based `output.attempt`, and EXEC/META paths valid in the current scope. Top-level output is cleared at the start of each attempt so it cannot expose stale result/evidence. History remains in `output.attempts[n]`. Each `retryDecision` records category, candidate, whenEvaluated, whenResult (if evaluated), allowed and a reason such as WHEN_FALSE or MAX_ATTEMPTS.

Conditions use normal `${...}`/`#{...}` typing and must return Boolean; numbers and strings such as 'false' are not coerced. Use `when: "#{false}"` to stop retry. Strict missing paths and expression failures produce normal diagnostics at retry.when and terminate retry. Pure deterministic built-ins are allowed; Tool/DB/MQ/HTTP/SSH calls, file/project-file operations, sequences, randomness and current-time operations are forbidden. Deterministic syntax/type errors fail validation; runtime result types and unavailable paths are checked when the gate runs.

TIMEOUT is the canonical Action outcome; suite/report aggregate operational failure remains ERROR. Authors must decide whether side-effecting operations such as MQ request or HTTP POST are safe to replay. Unconditional TIMEOUT retry can duplicate a business transaction; ATT does not silently suppress MQ retry. See the [MQHelper example](reference/05_resources/mqhelper.md).

## 04 Runtime and Context Model

Run, Debug and Load share one canonical EXEC/META expression model. EXEC changes through framework lifecycle and explicit input/variable/action publication. META is curated, immutable and secret-safe.

Standalone Debug bootstrap values are mapped into these canonical roots: `inputs` populates `EXEC.INPUT`, while Template/Flow `vars` seeds `EXEC.VARS` before the target starts. See [Standalone Debug](reference/04_execution_modes/debug.md) for the current schema, typed literal rules and protected framework roots.

### Identity roots

| Path | Meaning and type | Availability |
|---|---|---|
| EXEC.ID | Current Case, Debug execution or Load iteration ID; String. | After execution initialization; generated before Load business actions. |
| EXEC.RUN_ID | Enclosing ATT Run ID; String. | All modes; shared by Cases/iterations in a run. |
| EXEC.STARTED_AT | Current execution start time; ISO-8601 String. | All modes. |
| EXEC.RUN_STARTED_AT | Enclosing run start time; ISO-8601 String. | All modes. |
| EXEC.OUTPUT_DIR | Absolute execution workspace path. | After initialization; in Load it may be planned and not yet exist. |
| EXEC.INPUT | Case/stage, Debug sidecar or Load workload input map. | All modes. Stage inputs temporarily overlay Case inputs. |
| EXEC.VARS | Typed values published by assign. | All modes; empty before assignment. |
| EXEC.ACTIONS | Completed Actions in the active Template/Flow scope. | After an Action is published; Flow has a nested scope. |
| EXEC.LOAD.MODEL | closed or arrivalRate. | Load only; available before ID generation. |
| EXEC.LOAD.WORKLOAD_ID | Configured workload ID. | Load only; available before ID generation. |
| EXEC.LOAD.USER_ID | Stable virtual-user ID. | Closed Load only; absent for arrival-rate. |
| EXEC.LOAD.ITERATION | Numeric iteration sequence. | Load only; available before ID generation. |
| EXEC.LOAD.PHASE | WARMUP, RAMP_UP, STEADY or RAMP_DOWN. | Load only; available before ID generation. |

EXEC.LOAD exposes stable identity. Scheduler counters, queue state and timing diagnostics stay in evidence-only DIAG.load, which is not an expression root.

### META field inventory and lifecycle

The public META root contains only `PROJECT`, `SOURCE`, `TARGET`, `TEMPLATE`, `FLOW`, `TOOL`, `DBHELPER`, `MQHELPER`, `HTTPHELPER`, and `SSHHELPER` as listed below. META contains descriptive fields only. A path may be absent when its component is not active.

| Public path | Meaning, type and example | Modes and availability | Scope and when absent |
|---|---|---|---|
| META.PROJECT.id | Project directory name; String, e.g. `payment-att`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.PROJECT.root | Normalized project-root path; String, e.g. `/srv/att/payment`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.SOURCE.type | Source kind; String: `testcase`, `debug` or `load`. | Run, Debug, Load. | Execution-wide. |
| META.SOURCE.path | Normalized absolute source path; String, e.g. `/srv/att/payment/testcase/payment.xlsx`, `/srv/att/payment/debug.yaml` or `/srv/att/payment/load/payment.yaml`. | Run, Debug, Load when a source file exists. | Execution-wide; absent for an in-memory source. |
| META.SOURCE.caseId | Canonical TestCase or synthetic Debug Case ID; String, e.g. `payment.default.P001`. | Run, Debug. | Execution-wide; absent in Load. |
| META.SOURCE.workbookId | Workbook identifier; String, e.g. `payment`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.groupId | Workbook group identifier; String, e.g. `default`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.rowCaseId | Case ID from the workbook row; String, e.g. `P001`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.sheet | Workbook sheet name; String, e.g. `Payment`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.row | One-based workbook row number; Number, e.g. `12`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.workbook | Optional workbook label; String when present, e.g. `payment_regression.xlsx`. | Run when supplied by the adapter. | Execution-wide; optional and otherwise absent. |
| META.SOURCE.scenario | Load scenario basename without its suffix; String, e.g. `payment-smoke`. | Load. | Execution-wide; absent outside Load. |
| META.TARGET.type | Resolved target kind; String: `testcase`, `template`, `flow` or `tool`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TARGET.id | Resolved target identifier; String, e.g. `PAYMENT_INVOKE` or `payment.flow.v1`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TEMPLATE.id | Active Template or resolved Load execution-wrapper ID; String, e.g. `PAYMENT_INVOKE`. | Run/Debug while a Stage runs; Load after target resolution and while its wrapper runs. | Component scope; absent before target/template resolution, restored or removed after the scope. In Load Flow/Tool targets this is the resolved synthetic wrapper. |
| META.TEMPLATE.path | Normalized Template or execution-wrapper directory; String, e.g. `/srv/att/templates/PAYMENT_INVOKE`. | Same availability as META.TEMPLATE.id. | Component scope; absent before resolution, restored or removed after the scope. |
| META.FLOW.id | Active Flow ID; String, e.g. `payment.request.v1`. | Run, Debug, Load while that Flow invocation runs. | Invocation scope; push on entry, restore on return, absent when inactive. |
| META.FLOW.invocationId | Caller Action ID; String, e.g. `sendRequest`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.FLOW.depth | One-based nested Flow depth; Number, e.g. `1`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.TOOL.id | Active configured Tool or built-in name; String, e.g. `payment.queryOrder` or `upper`. | Run, Debug, Load during the invocation. | Invocation scope; never retained as the “last Tool” after return. |
| META.TOOL.type | Invocation kind; String, e.g. `tool` or `builtin`. | Same availability as META.TOOL.id. | Invocation scope; absent after return unless an outer invocation remains. |
| META.DBHELPER.id | Logical DBHelper ID; String, e.g. `orders`. | Run, Debug, Load during a DBHelper operation/DB Action. | Invocation scope; absent after return unless an outer scope remains. |
| META.DBHELPER.type | Resource kind; String, `dbhelper`. | Same availability as META.DBHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.MQHELPER.id | Logical MQHelper ID; String, e.g. `payment`. | Run, Debug, Load during an MQHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.MQHELPER.type | Resource kind; String, `mqhelper`. | Same availability as META.MQHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.HTTPHELPER.id | Logical HTTPHelper ID; String, e.g. `payment`. | Run, Debug, Load during an HTTPHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.HTTPHELPER.type | Resource kind; String, `httphelper`. | Same availability as META.HTTPHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.SSHHELPER.id | Logical SSHHelper ID; String, e.g. `application`. | Run, Debug, Load during an SSH Resource Helper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.SSHHELPER.type | Resource kind; String, `sshhelper`. | Same availability as META.SSHHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |

META.SSHHELPER exposes only the logical helper ID and resource type. SSH endpoint, user, identity file and credentials stay private to the executor and are not META fields.

ATT recursively filters credential-bearing keys such as password, secret, token, authorization/cookie, API key and private key. Expressions and adapters can read META but cannot mutate it.

### Invocation and scope rules

Entering a Template, Flow, Tool or helper invocation publishes metadata for that active scope. Nested calls push a frame; exit restores previous metadata. When no invocation is active, its branch is absent. Consumers must not depend on “last invoked” state.

EXEC.INPUT is the canonical input map. A Stage temporarily overlays Case inputs and restores them after completion. EXEC.VARS is shared across later Stages in a Case. EXEC.ACTIONS is scoped to the active Template or Flow. An Action reads local output while running and publishes its envelope at EXEC.ACTIONS.<id>.output.

### Optional lookup and compatibility

${path} is strict. ${path?} returns null for an allowed missing map/list path; it does not make malformed syntax or illegal scope access valid. Legacy CASE, RUN and ACTIONS aliases remain only where they map one-to-one to canonical data. New Templates should use EXEC and META.

### Lifecycle navigation

[Actions](reference/14_actions.md) owns Action-local `output` and publication at `EXEC.ACTIONS.<id>.output`. [Debug](reference/04_execution_modes/debug.md) and [Load](reference/04_execution_modes/load.md) own bootstrap variables, identity initialization and available scope. [Results](reference/11_results_reports_evidence.md) owns artifact navigation.

## 05 Expressions and Built-ins

### Unified expression engine

ATT uses one expression engine for runtime Templates, Flows, Actions and Tool calls:

- ${path} reads a Context value and interpolates it into surrounding text.
- #{expression} evaluates a typed expression. It supports Context operands, built-in calls, list literals, parentheses, unary operators, arithmetic, comparisons, like, in, null checks and boolean logic.

A complete expression preserves its value type. For example, an exact #{...} may return a number, boolean, map, list or String. Embedding an expression in surrounding text also produces a String. Use canonical EXEC and META paths; optional lookup uses a trailing question mark.

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

Use the expression form supported by each field. Project-file content, Action descriptions/assertions, Log message/value, assign expressions and Tool calls use the ordinary runtime model. A Log value can recursively contain typed expressions; see [Actions and Typed Values](reference/14_actions.md).

### Project-file String expressions

`&{path}` is a typed project-file expression. It resolves exactly one regular UTF-8 file and always returns a `String`; it never infers a document format, parses an extension, expands a glob or creates an output file. The path is relative to the canonical ATT project root. Descriptor-relative `./` and `../` paths are allowed only when their canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace, glob syntax and dynamic locators fail validation.

Use a YAML string when authoring a standalone value or embedding the locator in a larger expression:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

`${...}` and `#{...}` inside the file are compiled and evaluated when the file value is used. Run and Debug cache the compiled plan and invalidate it when the file fingerprint changes; Load freezes the validated file identity, content and compiled plan for the scenario. File output is not reparsed as a new expression source.

### Testdata input mapping syntax

Testdata references are resolved while Case/Stage, Debug, or Load input maps are prepared; they are not part of the general `${...}` / `#{...}` expression engine. Use `@{id}` to preserve a selected record's native type, `@{id.object.path}` or `@{id.items[0]}` to select a value, and scalar interpolation to compose text. One logical ID selects one record per mapping/lifetime, so every reference to that ID in the same mapping sees the same record. Mapping interpolation rejects nulls, maps and lists. `${...}` can read initialized Context values except `EXEC.INPUT`; input construction cannot depend on itself. Direct testdata markers are rejected in reusable Template, Flow, and Tool definitions so those components consume resolved `EXEC.INPUT` values only.

See [Testdata Registry and Input Mapping](reference/02_test_authoring.md) for descriptor and generation syntax.

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Only authored file-expression nodes are executable. Context values, Tool results and file output remain literal Strings even when they contain `&{...}`. Embedded Context paths and calls follow the enclosing Action's normal ordering, scope and resource validation rules. Nested `&{...}` inside project-file content, including inside `#{...}` arguments, is rejected in v1 in Run, Debug, validation and Load snapshot discovery.

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, `misc.*`, and `seq.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `seq.next` | Return a run-scoped `Long`; optional sequence name and width produce independent named counters or exact-width zero-padded text | `#{seq.next('payment', 10)}` |
| `str.upper` | Convert text to upper case | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.lower` | Convert text to lower case | `#{str.lower(value=${EXEC.INPUT.channel})}` |
| `str.trim` | Remove surrounding whitespace | `#{str.trim(value=${EXEC.INPUT.reference})}` |
| `str.ltrim` / `str.rtrim` | Remove leading/trailing whitespace | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | Return text length | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | Concatenate arguments in call order | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr` | Extract text from a zero-based start | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.indexOf` | Return zero-based position or `-1` | `#{str.indexOf(${EXEC.INPUT.reference}, '-')}` |
| `str.contains` | Test literal substring membership | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.startsWith` / `str.endsWith` | Test a literal prefix/suffix | `#{str.startsWith(${EXEC.INPUT.reference}, 'PAY')}` |
| `str.replace` | Replace every literal target | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad` / `str.rpad` | Pad to a minimum length | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | Repeat a value 0–10000 times | `#{str.repeat(3, '9')}` |
| `date.sysdate` | Return system-zone date, optionally formatted | `#{date.sysdate('yyyyMMdd')}` |
| `date.systimestamp` | Return system-zone timestamp, optionally formatted | `#{date.systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `date.format` | Format an ISO-8601 value | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | Add a calendar/time amount | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `misc.string` | Convert a value to text | `#{misc.string(value=${EXEC.INPUT.amount})}` |
| `misc.number` | Parse and normalize a number | `#{misc.number(value='12.50')}` |
| `misc.boolean` | Convert true/false, yes/no, or 1/0 | `#{misc.boolean(yes)}` |
| `misc.coalesce` | Return first non-blank value | `#{misc.coalesce(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.nvl` | Return a default for null/empty text | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | Select one of two values from a boolean | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | Return one of 1–1000 input values | `#{misc.randomChoice('A', 'B', 'C')}` |

#### `seq.next` run-scoped sequences

`seq.next` supports these four positional overloads (the same arguments may be supplied by the names `name` and `width`; do not mix named and positional styles):

| Call | Counter | Return value |
|---|---|---|
| `#{seq.next()}` | Default sequence | Incrementing Java `Long` |
| `#{seq.next('payment')}` | Independent sequence named `payment` | Incrementing Java `Long` |
| `#{seq.next(10)}` | Default sequence | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |
| `#{seq.next('payment', 10)}` | Sequence named `payment` | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |

The default and each named sequence have independent counters, each starting at 1. State is owned by one ATT Run: Run shares counters across its Testcases and suites; Debug has a fresh service for its one-shot execution; Load shares counters across its workloads and concurrent iterations. Each per-name counter is thread-safe and issues unique, monotonically increasing values within that Run; concurrent scheduling does not guarantee which VU receives which value. A new Run, Debug execution, or Load run starts the counters again at 1. No `EXEC.SEQUENCES` Context node or reset/current API is exposed.

| Mode | Example use | Scope note |
|---|---|---|
| Testcase | In an `assign` Action: `expression: "#{seq.next('payment', 10)}"` | Consecutive Cases in one Run share the `payment` counter. |
| Debug | In an `assign` Action: `expression: "#{seq.next()}"` | A fresh one-shot Debug execution starts at 1. |
| Load | In an `assign` Action in the target Template/Flow: `expression: "#{seq.next('load-order', 10)}"` | Iterations share the counter; concurrent calls are unique, but no stable VU allocation order is promised. |

`width` must be an integer from 1 through 1000. The name must be non-blank text; with one positional argument, a number means `width` and a string means sequence name. More than two arguments, mixed named/positional argument styles, invalid argument types, blank names, fractional/zero/negative/out-of-range widths are errors. Diagnostics identify `seq.next` and the invalid arity, argument, or range.…25505 tokens truncated… Non-empty package-relative template root |
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
| `execution` | `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |

See [Appendix C](reference/appendices/migrations.md) for removed configuration fields.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; project-file expressions use one canonical, regular UTF-8 file below the project root and reject absolute paths, globs, dynamic locators and symlink escapes. Resource file inputs and outputs must remain below their documented safe roots. ATT normalizes and checks root containment before reads and writes.

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
| Workbook / Sidecar / Snapshot | [Test Authoring](reference/02_test_authoring.md) |
| Template / Flow / Action | [Test Authoring](reference/02_test_authoring.md) / [Actions](reference/14_actions.md) |
| Tool command, call, arguments | [Tool](reference/05_resources/tools.md) |
| DB descriptor | [DBHelper](reference/05_resources/dbhelper.md) |
| MQ descriptor | [MQHelper](reference/05_resources/mqhelper.md) |
| HTTP descriptor | [HTTPHelper](reference/05_resources/httphelper.md) |
| SSH descriptor | [SSHHelper](reference/05_resources/sshhelper.md) |
| Timeout / Retry | [Reliability](reference/08_reliability_execution_control.md) |

## 10 CLI Reference

### Commands

| Command | Purpose | External tools? |
|---|---|---:|
| `help` | Show syntax and options; default with no command | No |
| `version` | Print ATT version | No |
| `validate` | Validate package or selected dependency closure | No |
| `snapshot` | Generate same-basename canonical testcase XML | No |
| `run` | Validate and execute selected cases | Yes, except dry-run |
| `debug` | Execute one Template, Flow, or Tool with a debug sidecar | Yes |
| `load` | Execute a declared scenario or promote a Debug sidecar into a Quick Load | Yes |
| `docs` | Generate searchable package documentation | No |
| `report` | Regenerate reports for a completed run | No |
| `build` | Archive the latest completed run | No |
| `clean` | Remove documented ATT-generated output | No |

### Command syntax

The tables use the Linux/macOS launcher `./att.sh`. On Windows, use `att.bat` with the same command and options. `att.bat snapshot`, `att.bat validate`, and `att.bat docs` do not invoke configured testcase tools. Windows validation checks `.sh` file existence and path safety, skips POSIX launch/executable compatibility, and emits one warning listing affected tools; a validation PASS does not prove those scripts can run on Windows. Provide and test Windows-native equivalents before `run`. Binary releases require Java 8+. Source-tree `att.bat` compiles with Maven when available and otherwise requires existing `target\classes`.

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh help` | Show help |
| `./att.sh version` | Print version |
| `./att.sh snapshot` | Generate snapshots recursively below `testcase.root`; equivalent to `--all` when no selector is supplied |
| `./att.sh snapshot --suite <xlsx>` | Generate one same-basename XML snapshot |
| `./att.sh snapshot --all` | Generate snapshots recursively below `testcase.root` |
| `./att.sh snapshot --suite-dir <dir>` | Generate snapshots recursively below a directory |
| `./att.sh validate --package` | Validate complete package; default scope |
| `./att.sh validate --selected <selection>` | Validate selected dependency closure |
| `./att.sh validate --package --format json` | Emit one validation JSON document to stdout |
| `./att.sh run --all` | Run all discovered cases |
| `./att.sh run --suite <xlsx>` | Run one workbook; repeatable |
| `./att.sh run --suite-dir <dir>` | Discover workbooks below a directory |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | Include one full Case ID |
| `./att.sh run <selection> --tag <tag>` | Include a tag |
| `./att.sh run <selection> --exclude-tag <tag>` | Exclude a tag |
| `./att.sh run <selection> --dry-run` | Validate/plan without tool execution |
| `./att.sh run <selection> --update-snapshot` | Explicitly refresh changed complete-workbook snapshots before validation |
| `./att.sh run <selection> --fail-fast` | Stop scheduling after first FAIL/ERROR |
| `./att.sh run <selection> --rerun-failed` | Select prior FAIL/ERROR cases |
| `./att.sh run <selection> --run-id <id>` | Set final run directory name |
| `./att.sh run <selection> --output-dir <dir>` | Override output root |
| `./att.sh run <selection> --ci-output junit,json` | Write CI XML/JSON plus JUnit HTML |
| `./att.sh run <selection> --profile` | Write per-run `performance.json` timings and counters |
| `./att.sh run <selection> --queue` | Wait for another ATT process using the same output root |
| `./att.sh run <selection> --allow-parallel-runs` | Allow concurrent ATT processes; does not parallelize Cases in this run |
| `./att.sh run <selection> --format json` | Emit machine-readable summary |
| `./att.sh run <selection> --quiet` | Suppress detailed live progress; keep the final summary and errors |
| `./att.sh run <selection> --verbose` | Accepted for compatibility; detailed live progress is already the default |
| `./att.sh debug` | Discover runnable Tools, Templates, and Flows; show only existing default sidecars |
| `./att.sh debug template <id>` | Execute one Template; auto-discover `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | Execute one canonical Flow; auto-discover `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | Execute one Tool; auto-discover `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | Override the target's auto-discovered debug input |
| `./att.sh debug <type> <id> --set input.path=<yaml-value>` | Override a typed `EXEC.INPUT` value; repeatable |
| `./att.sh debug tool <id> --set arg.name=<yaml-value>` | Override one Tool argument; repeatable |
| `./att.sh debug <type> <id> --set vars.path=<yaml-value>` | Override Template/Flow bootstrap `EXEC.VARS` before expression evaluation |
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | Emit a compact machine-readable console summary; full evidence remains in `result.yaml` |
| `./att.sh debug <type> <id> --quiet` | Suppress detailed live progress; keep the final summary and errors |
| `./att.sh load` | Discover valid `att-load/*` scenarios under `load/`; report invalid declared scenarios |
| `./att.sh load <scenario.yaml> --quiet` | Suppress periodic live progress; keep the final summary and errors |
| `./att.sh load <scenario.yaml> --verbose` | Accepted for compatibility; bounded live progress is already the default |
| `./att.sh load --debug <type> <id>` | Promote a Debug sidecar into a normal single-workload Load run using `load/load.yaml` policy |
| `./att.sh load <scenario.yaml> --set input.path=<yaml-value>` | Override one-workload `EXEC.INPUT`; repeatable, not valid for multi-workload scenarios |
| `./att.sh load <scenario.yaml> --set arg.name=<yaml-value>` | Override a Tool argument in a one-workload Tool scenario |
| `./att.sh load <scenario.yaml> --set vars.path=<yaml-value>` | Override one-workload Template/Flow bootstrap vars |
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

No-target `debug` and `load` are read-only discovery commands. Debug validates target contracts without invoking Tools or creating output. Load scans only declared `att-load/*` YAML, validates every target before listing the scenario, reports invalid declared descriptors, and ignores unrelated YAML. Both accept `--config`, `--env`, `--format`, `--quiet`, and `--verbose` in discovery mode.

### Typed overrides and Quick Load

`--set` is repeatable and accepts exactly one namespace: `input`, `arg`, or `vars`. Values use safe YAML parsing (for example `42`, `true`, `null`, `[a, b]`, or `{id: 7}`), and nested paths may use map keys and numeric list indexes such as `input.customer.ids[0]=42`. Duplicate assignments are applied in order, so the last value wins. ATT expressions are not evaluated while parsing an override; quote expression-looking values when a shell could expand them. `arg.*` is Tool-only; `vars.*` is Template/Flow-only. Unqualified overrides are rejected for multi-workload Load scenarios.

`load/load.yaml` is an optional, policy-only `att-load/v1.5` file. It may contain `load`, `execution`, `thresholds`, `evidence`, and `seed`, but no target or business inputs. `load --debug` promotes sidecar `inputs` to `EXEC.INPUT`, Template/Flow `vars` to bootstrap `EXEC.VARS`, or Tool `arguments` to the Tool call, then runs through the regular Load validator, scheduler, and evidence pipeline. Explicit CLI pacing fields override the policy. Without a policy, provide a complete policy on the command line; for example:

```yaml
schemaVersion: att-load/v1.5
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
```

```sh
./att.sh debug
./att.sh load
./att.sh load --debug template PAYMENT_INVOKE
./att.sh load --debug tool fpp.invokeApi --users 1 --duration 10s --set arg.requestId=42
./att.sh load --debug flow common.payment --users 4 --duration 5s --set input.customer.ids[0]=42
```

`run`, `debug`, and `load` default to interactive verbose behavior. Lifecycle, Case, Stage, Action, resource-attempt, retry, assertion, and error records are written as they occur and flushed promptly. The live Case-log mirror uses the same redacted append path as `case.log`; `case.log`, `case.yaml`/`result.yaml`, reports, and evidence remain the persistent source of truth. Concurrent Case-log chunks carry a Case ID prefix. `--quiet` suppresses detailed live progress but retains a final summary and errors. With `--format json`, machine-readable output remains on stdout and live progress is sent to stderr. Load progress prints bounded periodic counters/rates and throttled errors, never one console block per successful iteration.

### Debug inputs and outputs

This chapter defines target, `--input`, `--set` and `--env` syntax in the option matrix. [Debug](reference/04_execution_modes/debug.md) owns input discovery, bootstrap variables, protected roots and output lifecycle.

### Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command/run succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more FAIL results and no ERROR/INVALID |
| 2 | CLI/configuration/validation/INVALID failure |
| 3 | One or more ERROR results or unrecoverable runtime failure |

### Complete option matrix

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.11` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` or `load`.

Load uses the scenario as the base and explicit workload options override the corresponding fields before the effective scenario is validated again:

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

Repeatable `--set <input|arg|vars>.<path>=<yaml-value>` applies safe-YAML typed overrides before expression evaluation. The same option works for `debug`, single-workload Load scenarios, and `load --debug template|flow|tool <id>`. Quick Load uses `load/load.yaml` when present; otherwise provide a complete policy on the command line. For example:

```sh
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
```

The complete workload override set is `--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, and `--overload-policy`. `--think-time` is closed-VU only. Common selection/output options remain command-specific: `--suite`, `--suite-dir`, `--case`/`--case-id`, `--tag`, `--exclude-tag`, `--all`, `--run-id`, `--output-dir`, `--format`, `--quiet`, `--verbose`, `--ci-output`, `--dry-run`, `--fail-fast`, `--rerun-failed`, `--update-snapshot`, `--package`, `--selected`, `--input`, `--set`, `--queue`, `--parallel`, `--allow-parallel-runs`, `--profile`, `--config`, `--env`, and `--help` are accepted only where the command contract permits them.

## 11 Results, Reports, and Evidence

### Run directory

```text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
└── executions/<EXEC.ID>/...
```

Run and Case IDs appear unchanged after validation. A run is completed only when its `run.yaml` state is `COMPLETE`; interrupted work remains directly below its reserved Run ID for debugging.

### Human HTML report

`report/index.html` is the primary end-user report. It can be opened without a web server. Groups are summarized by `workbookId.groupId`; the interface labels `groupId` as Sheet because it maps to one physical sheet. Cases supports Workbook/Sheet/Status dropdowns, case-insensitive search over workbook/group/full Case ID/tags, and ascending/descending sorting from every column heading. Duration sorting is numeric.

An expanded case contains the full Case ID and name, status and duration, Expected and Actual results, one row per recorded action result, a bounded detailed execution-log preview, and explicit `.log`/`case.yaml` artifact links. Each Action Results row has independent Stage, Action, Description, Status, and Message columns; Description is the final rendered action description and is also persisted in `run.yaml` and CI JSON. `report.html.caseLogInlineLimitBytes` controls the head/tail preview; `0` keeps only the artifact link. For compatibility, Expected remains the ordered LF-joined non-blank assert descriptions and `expected` values; Actual is the ordered LF-joined non-blank runtime `actual` values. `case.yaml` holds the complete structured final Stage/Template/Action/Tool/DB state. Depending on what ran, these artifacts include selected templates, executed or skipped stages/actions, assertion messages, Tool argv/stdout/stderr/retry evidence, DB source/parameter/result/finalization evidence, diagnostics, and saved payload/Tool/DB-output paths. Workbook ID, group ID, and tags are persisted per case in `run.yaml`, so `report --run-id` regenerates equivalent controls and grouping.

`report/junit.html` is a human-readable JUnit projection. It displays counts and one row per testcase with status, duration, and embedded case-log content or a relative artifact link.

### Tool evidence collector failures

An evidence collector is post-operation observability, not the primary Tool result. With `onFailure: continue`, the primary Action may remain `PASS` while the collector is independently recorded as `ERROR`:

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>` (or the equivalent `ACTIONS` compatibility view). The record contains `status`, `success`, `invocationId`, `result`, `error`, and the bounded/redacted underlying operation `evidence`; when an operation supplies structured diagnostics, `operationDiagnostic` retains safe fields from the native operation diagnostic. `diagnostic` identifies the collector failure and its source file/field. `error.message` is populated from the underlying exception, operation status/exit code, or a safe fallback. Resource identity and fields such as SSH helper/instance, exit code, bounded stderr, MQ reason codes, HTTP status, and timeout details remain under `evidence` when provided by the executor. Failed collector evidence is bounded/redacted; raw input, payload, argv, output, resolved command text and the failed `result` are not published. See [Appendix D](reference/appendices/limits_defaults.md) for the exact projection, numeric budgets and security guarantees.

For retries, inspect `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`. Earlier failed collector records remain available after a later successful attempt; the top-level collector record follows the final/winning attempt. With `onFailure: stop`, the Action can fail, but its diagnostic still includes the collector's root-cause message and preserved evidence. The same structured record is written to the `EVIDENCE <action> attempt=<n> collector=<id>` block in `case.log`, so the basic resource, category, message, exit code, and bounded stderr can be diagnosed without opening internal exception traces. Existing capture limits and secret redaction continue to apply; collector wrapping does not enable unbounded raw output.

### Result workbook

ATT copies the source workbook and appends configured result columns using `report.mode: append-to-copy`. Set `report.mode: none` for CI or large runs that do not need a copied workbook. Global `report.fileNamePattern` controls the copy filename. Sidecar `report.columns` changes workbook labels only. Supported mappings include `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, and `runTime`; Expected/Actual cells retain LF characters and use wrapped text. Row matching reads the Case ID with the same Excel `DataFormatter` and whitespace normalization as testcase loading, so displayed formats such as numeric leading zeroes identify the same Case during execution and report writing.

### JUnit XML

Each ATT case maps to one `<testcase>`:

| ATT status | JUnit representation |
|---|---|
| PASS | no failure child |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

Text is XML-escaped. JUnit XML and HTML use `report.junit.caseLogEmbedThresholdBytes`. Logs at or below the threshold are embedded; larger logs use a relative link. `0` always links.

### CI JSON summary

`ci/summary.json` uses `schemaVersion: att-ci-summary/v2.1` and contains ATT/run IDs, environment, timing, aggregate status/counts, duration statistics, per-case records, diagnostic counts, report/artifact paths, and the input-manifest hash.

### Run manifest and reproducibility

`run.yaml` uses `schemaVersion: att-run/v2.1` and records ATT/build identity, Java/OS/locale/timezone, validation mode, environment, timestamps, status/summary, output paths, and SHA-256 inputs for effective configuration, tool-group files, call-backed Tool SQL files (`tool-sql`), workbook, sidecar, resolved templates/payloads, package-local tool files, and schema/catalog version.

### Documentation, archive, and clean

| Command | Output/behavior |
|---|---|
| `docs` | Generates searchable offline package documentation at `build/docs/index.html`; Testcases are grouped by workbook and Sheet |
| `report --run-id <id>` | Regenerates both HTML reports from completed evidence |
| `build` | Archives the latest completed run without executing tests |
| `clean` | Removes configured output directory, `build/docs`, and `build/att-*.tar.gz` |

The build archive contains reports, workbooks, case logs, referenced artifacts, redacted configuration/template snapshots, manifest, and hashes. Exact YAML keys named `password`, `token`, `secret`, or `authorization` are redacted case-insensitively; this is not a general log/result redactor.

The Testcases section renders one table per Sheet below each workbook heading. The Sheet column is omitted because the group heading supplies that context. Each table includes Expected Result, formed in stage/action order from every assert action's validation-time `description` and `expected`; unresolved runtime placeholders remain visible and line endings are normalized to LF.

Clean never removes testcase, template, tool, configuration, documentation, schema, or other source files. It canonicalizes paths, rejects source/package roots and external symlink targets, is idempotent, and reports what it removed.

### Run, execution and evidence navigation

| Identity | Meaning | Scope | Artifact role |
|---|---|---|---|
| EXEC.RUN_ID | Enclosing ATT run. | Run. | Run root, summary and report. |
| EXEC.ID | Current Case/Debug/Load execution. | Execution. | Key for logs/evidence when a workspace exists. |
| EXEC.OUTPUT_DIR | Workspace path associated with EXEC.ID. | Execution. | Physical Run/Debug workspace or planned lazy Load workspace. |

Normal Run stores functional Cases under output/<RUN_ID>/executions/<EXEC.ID>/. In Load, EXEC.OUTPUT_DIR and CASE.outputDirectory remain at output/load/<RUN_ID>/executions/<EXEC.ID>/ throughout the iteration. When retained, a copy of its artifacts is also stored under samples/<EXEC.ID>/ or failures/<EXEC.ID>/. Metrics-only iterations have EXEC.ID but no per-iteration directory after the scheduler releases their temporary workspace. Retained Load rows show EXEC.ID and link to case.log when present. Debug uses its debug ID as both EXEC.RUN_ID and EXEC.ID.

DIAG is evidence-only. Do not reference DIAG, EXEC.MODE or arbitrary scheduler counters in expressions; pass business variation through EXEC.INPUT.


### Generated-output schema summary

| Artifact | Required top-level contract |
|---|---|
| `run.yaml` | `schemaVersion`, `att`, `runtime`, `run`, `validation`, `inputs`, `cases`, `summary`, `outputs` |
| Validation JSON | `schemaVersion`, `attVersion`, `valid`, `mode`, `summary`, `diagnostics` |
| CI summary JSON | `schemaVersion`, `attVersion`, `runId`, `environment`, `startedAt`, `endedAt`, `status`, `summary`, `durationStatistics`, `cases`, `diagnosticCounts`, `report`, `inputManifestHash` |
| JUnit XML | one testsuite with test/failure/error/skipped counts and one testcase per ATT case |

Generated envelopes reject additional top-level fields according to their schemas. JUnit HTML is a human-readable output and not an XML/JSON schema artifact.

### Reading case.log and case.yaml

Case log structured entries use YAML. The human log records each normal Action and each Tool/DB invocation once; duplicated attempt fields and persisted TOOL/DB subtrees are omitted from this projection. Complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` fully expands shared Map/List objects; `true` permits YAML anchor markers, which carry no ATT identifier semantics.

ATT prefixes Case log blocks whose section or nested status is ERROR, FAIL or INVALID with `【!!!!!】`. Search for that marker to find abnormal blocks; PASS, SKIPPED and informational blocks remain unmarked.

## 12 Validation and Troubleshooting

### Where to look first

Run `validate --package` and fix the diagnostic's file/field first. For runtime failures, inspect the report status/message, then the execution's `case.log`, `case.yaml` and Action evidence. [Reliability](reference/08_reliability_execution_control.md) defines FAIL versus ERROR, continuation and retries; [Results](reference/11_results_reports_evidence.md) identifies collector failure paths. See [Appendix D](reference/appendices/limits_defaults.md) for Windows launchers, Java SSH negotiation and stack-trace policy.

### Start with validation

Run this after every workbook, sidecar, template, helper, or tool change:

```sh
./att.sh validate --package
```

For one environment, use `./att.sh validate --config config/config.yaml --env SIT --package`. ATT validates descriptors against the active schemas in [Appendix A](reference/appendices/schema_matrix.md). Superseded schema files under `schemas/history/` are historical references, not runtime compatibility contracts. Update the declared `schemaVersion` and migrate fields to the active contract before validation. Diagnostics retain the original violation, file and YAML field location and provide migration guidance; they never rewrite descriptors. For example, replace a historical Render action with an Assign using `&{path}` and pass the resulting String as described in [Actions and Typed Values](reference/14_actions.md). Unsupported versions fail before execution.

Current schemas are in [`schemas/`](../schemas); older definitions are under [`schemas/history/`](../schemas/history). `validate --package` checks every catalog-registered schema resource, even when the package does not use it. A missing, unreadable, unsafe, or duplicate registered schema is a hard `PACKAGE_INVALID` error. Validation never rewrites YAML. Review the migration guidance, update the file, then rerun package validation for each selected `--env`.

Then use the diagnostic code and structured location. Do not automate against message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing/stale snapshot, sidecar/sheet/header error, or duplicate Case ID | Check snapshot/basenames, sheet mapping, effective headers, and full IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Inspect requested/current/missing fields, nearest suggestion, or canonical candidates |
| `ATT-STG` | Blank required selector, invalid selector YAML, duplicate stage key | Check selector form, `name`, aliases, and required flag |
| `ATT-TPL` | Unknown/duplicate template, invalid action or payload | Check symbolic name/full path, descriptor, action type, and local files |
| `ATT-CFG` | Unknown field, duplicate key, wrong schema/type/enum | Compare with Chapter 6 and remove unsupported fields |
| `ATT-TOOL` | Unknown/missing argument, process or parse failure | Compare call contract; inspect exit code and bounded stdout/stderr capture evidence |
| `ATT-PATH` | Illegal ID or escaping path | Remove illegal characters and keep content below configured roots |
| `ATT-RUN` | Timeout, non-zero exit, render/runtime failure | Inspect case log and action/tool evidence |

### Common questions

#### Why is the Case ID rejected although Excel displays it correctly?

ATT imports displayed cell text, then applies strict ID safety checks. Check hidden leading/trailing whitespace, trailing `.`, path characters, controls, and Windows device names. Store identifiers as text to preserve leading zeroes.

#### Can two sheets both contain `TC001`?

Yes. Give sheets different group IDs, producing IDs such as `payment.payment.TC001` and `payment.batch.TC001`.

#### Why did `N/A` become empty?

ATT normalizes `N/A`, `NA`, `NULL`, `NONE`, empty, and whitespace-only values to blank before data mapping and stage selection.

#### Why does a Context variable fail?

ATT treats an absent path as an authoring/runtime error instead of silently rendering it as empty. Follow `ATT-CTX-001` and its `requestedPath`, `currentNode`, `missingSegment`, and nearest suggestion to check the case-sensitive scope, physical header/alias, stage key, action ID, and availability point. A suffix shorthand must identify exactly one readable logical path; `ATT-CTX-002` lists every conflicting candidate so you can lengthen it. The diagnostic intentionally omits the full Context tree. A declared optional field with a blank value is still valid and renders as the empty string.

#### Why did a FAIL become ERROR?

A false assertion is FAIL. Invalid expression syntax/navigation, tool failure, timeout, parse failure, I/O failure, or runtime exception is ERROR. Inspect the action evidence rather than only the final aggregate status.

#### Why did a tool run more than once?

Its action used retry and received an eligible non-zero exit code. Inspect the attempt list and final action record in the case log.

#### Can I use a shell pipeline in `command`?

No. ATT passes `|`, `>`, and `<` literally. Put shell behavior inside a reviewed tool script.

#### Why does a required array argument reject `[]`?

Required validation happens before argv expansion. An empty typed List is missing input; pass at least one scalar item or make the argument optional.

#### Should I use package or selected validation?

Use selected mode for fast local feedback. Use package mode before release, CI promotion, or sharing a package.

#### Can reports be opened without a server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

#### Does build execute tests again?

No. It archives one completed persisted run.

### Security reminders

Do not place passwords, tokens, private keys, or sensitive customer data in workbook cells, template descriptors, command strings, stdout, or stderr. Prefer approved secret injection inside tool scripts. Review reports and archives before sharing.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.7.1",
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

ATT may also include `summary`, `detail`, `source`, `context`, and `schemaViolations`. `source` holds physical YAML or payload `line`, `column`, `endLine`, and `endColumn`; the top-level `row` and `column` continue to identify an Excel cell. For single-line plain or directly quoted YAML scalars, an expression syntax error points to its character. Folded, multiline, or escaped scalars use the YAML scalar range when an exact mapping is unavailable. Every schema violation retains its own path, keyword, message, and physical source. `context` may contain the Case, Stage, Flow ID, and nested call chain. Expression syntax details identify the containing tool-call argument (for example, `logFiles`), the unexpected token, and a bounded caret excerpt when it is safe to show; source excerpts are omitted when the field or line may contain credentials or secrets.

Runtime Action failures preserve the same structure in Case YAML, `run.yaml`, regenerated reports, CI JSON, and JUnit failure detail. A nested Flow failure identifies the inner `flow.yaml` and Action while the call chain identifies how the Template reached it. Tool and DB evidence adds attempts, timeout, parse/capture, parameter binding, and cancellation details where available. File save failures include the configured path and allowed artifact root.

## 13 CI, Packaging, and Operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Reference Manual, builds the application jar and release/source archives, and verifies the packaged launcher. It requires Java/Maven plus Python 3 and Pandoc for Reference generation.

### Runtime dependencies

Java 8+ is the runtime baseline. ATT does not bundle JDBC drivers; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.

## Appendix A — Schema and Version Matrix

Active schemas (source of truth: `schemas/catalog.yaml`):

| Artifact | Active schema |
|---|---|
| Global configuration | att-config/v2.11 |
| Testdata descriptor | att-testdata/v1.0 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.1 |
| Load scenario | att-load/v1.5 |
| Load summary | att-load-summary/v1.0 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

For the changed resource/configuration schemas, older versions are historical definitions under schemas/history; they are not active execution contracts. Unsupported versions fail validation with migration guidance. The repository catalog at schemas/catalog.yaml is authoritative. Package validation checks the registered schema resources themselves; it does not enable runtime compatibility for archived versions.

## Appendix B — Compatibility and Deprecated Aliases

Compatibility exists to read established packages without creating a second current model. New authoring uses canonical `EXEC`, `META`, Action-local `output`, current schema versions, `--env`, and current Tool/DB/MQ contracts.

Deterministic legacy aliases may remain readable with migration warnings. Aliases are not created where old semantics conflict with scope isolation or the common result/evidence contract. Deprecated CLI/authoring forms remain documented in their owning chapter or CHANGELOG only when users still need a migration path.

## Appendix C — Migration Notes

### ATT 3.7.1 testdata migration

Change global configuration from `att-config/v2.10` to `att-config/v2.11` and Load scenarios from `att-load/v1.4` to `att-load/v1.5`. The previous schemas remain catalogued under `schemas/history/` for migration diagnostics. `att-testdata/v1.0` is new: add descriptor paths to the selected environment profile's `testdata` list, then use `@{id}` references in Case/Stage, Debug, or Load workload input maps. Load scenarios can add package-relative top-level `testdata` paths as a Load-only overlay. Repeated logical IDs across layers mean a whole descriptor replacement; duplicate IDs inside one layer are invalid. Add an explicit selection policy for every descriptor containing multiple records. Existing packages without testdata references need no new descriptor files.

Load workload `testdata.<id>` settings control `scope` and optionally replace the whole descriptor `selection` policy. Scope defaults to `iteration`; `user` is valid only for closed-VU workloads. Choose `error`, `recycle`, or `stop` exhaustion deliberately. Selection metadata is recorded without record values.

ATT 3.6.2 separates typed operation results, external parsing, project-file Strings, outbound transport and human-readable evidence.

| Previous field/model | 3.6.2 migration |
|---|---|
| `att-template/v3.4` or `att-flow/v3.4` with `type: render` | Change the descriptor to the active v3.5 schema and replace each Render Action with an Assign that uses a project-file expression. Historical v3.4 descriptors remain loadable only through the historical schema path. |
| `type: render` / `payload: path` | Use `type: assign`, a variable `name`, and `expression: "&{project-relative-file}"`; pass `${EXEC.VARS.<name>}` to the consumer. |
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Remove the old format/persistence fields. The project-file expression returns the exact UTF-8 String and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the project-file String directly as HTTP body or MQ payload, or use an explicit resource file argument. |
| requestFormat on a project-file String | Remove it. requestFormat is only for abstract Map/List values; String + requestFormat fails. |
| Dynamic or unsafe file locator | Replace it with one static project-relative file. Absolute paths, globs, dynamic locators, missing files, directories, non-UTF-8 bytes and symlink escapes are rejected. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Use the active schema from [Appendix A](reference/appendices/schema_matrix.md) and migrate the fields above. Historical schemas are not active contracts. |

A project-file String passed to HTTP:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

The file is read as strict UTF-8 text. `${...}` and `#{...}` inside the file remain runtime expressions and are compiled without invoking external resources during validation. Run/Debug cache the compiled plan and invalidate it when the file fingerprint changes; Load freezes the validated file identity, content and plan for the scenario. File output is not reparsed as a new expression source.

For an abstract value, use requestFormat explicitly:

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

For Load, migrate old single-target or v1.1 scenarios through the historical v1.2/v1.3 loaders, then change the schemaVersion to att-load/v1.5. Root defaults may be shared by multiple workloads; each workload's `inputs`, `vars`, load policy and execution settings override the corresponding root values. Top-level thresholds remain aggregate-only; workload thresholds are declared per workload and are not inherited from the root. `inputs` remains EXEC.INPUT; `vars` is evaluated after each execution's EXEC.ID and EXEC.OUTPUT_DIR are initialized and before the target starts. Exact references preserve native values, dependencies are order-independent, and cycles or external/stateful calls fail validation. The optional top-level execution.execIdFormat still uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it.

The historical `att-load-profile/v1.0` policy file is migration-only: rewrite it as the current policy-only `att-load/v1.5` descriptor before use. It is not a current `load/load.yaml` example.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](reference/14_actions.md), [Runtime and Context Model](reference/03_runtime_context.md), [Load Mode](reference/04_execution_modes/load.md) and [Schema Matrix](reference/appendices/schema_matrix.md).

### Historical schema migration

ATT 3.6.2 uses `att-template/v3.6` and `att-flow/v3.6` as the active schemas. The published `att-template/v3.5`, `att-flow/v3.5`, and older definitions remain under `schemas/history/`; their historical DB and Render Actions are compatibility-only and are not part of the active contract. When migrating those descriptors, change their schema versions to v3.6 and apply the field changes below.

| Historical configuration | 3.6.2 form |
|---|---|
| `att-template/v3.3` or `att-flow/v3.3` | Follow the historical release migration to v3.4, then change to v3.6 and migrate the Render/DB Actions. |
| Historical `type: db` with `query` or `update` | Use an ordinary `type: tool` Action with `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)`; query/scalar may retry, update must not use automatic retry. |
| Historical `sqlFile` | Use the single String argument `sql=&{project-relative-sql-file}`. `params` and `parameters` remain mutually exclusive. |
| Historical `type: render` | Replace it with an Assign whose expression is `"&{project-relative-file}"`; use `${EXEC.VARS.<name>}` in later Actions. |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | Remove the old persistence fields. The project-file expression returns the exact UTF-8 String and creates no implicit result file. |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ | Pass the project-file String directly as HTTP body or MQ payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

Project-file paths are relative to the canonical project root. `./` and `../` are allowed only when the canonical target remains inside that root. The v1 contract has no globs or dynamic locators; the target must be a regular strict-UTF-8 file.

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](reference/03_runtime_context.md) for META lifecycle and [Load Mode](reference/04_execution_modes/load.md) for execution identity and retained evidence paths.

### Debug schema migration

`att-debug/v1.0` is historical; upgrade to `att-debug/v1.1`. Template/Flow may define `vars` to seed `EXEC.VARS`; Tool targets do not support `vars`. See [Debug](reference/04_execution_modes/debug.md) for current input and argument rules.

### Global configuration migration

Old `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` fields are not part of the active global contract. See [Configuration](reference/09_configuration.md) for current fields.

### Environment profile migration

When migrating complete-config packages, preserve descriptors and Actions, move common settings to `config/config.yaml`, move descriptor lists to `environments.<NAME>`, and select with `--config config/config.yaml --env <NAME>`. See [Configuration](reference/09_configuration.md) for the current contract.

## Appendix D — Limits, Security Guarantees and Advanced Diagnostics

### Limits and defaults

Use the owning schema/configuration chapter for normative field defaults. Important architectural limits include:

- Load chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `DIAG` is framework-owned evidence and is not part of the expression tree;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.

### Collector projection and redaction guarantees

All failed collectors, including returned operation errors and thrown Tool exceptions, pass through the same public projection before publication or logging. The projection omits raw input, payload, argv, output, resolved command text, and the failed record's `result`, and does not guarantee `parserDiagnostic`. Native error/diagnostic maps retain only safe fields; each retained text field is limited to 1024 characters. `inputOmitted` and truncation flags identify omitted or bounded evidence. Free-form messages, stderr, per-instance errors, and cleanup warnings redact string, typed text, and array inputs within a fixed budget: 256 input nodes, 8192 token characters, and 1024 characters per token. Byte arrays are limited to 128 bytes (UTF-8, Base64, hexadecimal, and Java decimal renderings), other arrays to 64 elements, and char arrays to 1024 characters. Exceeding any budget, encountering a private token shorter than 4 characters, or encountering an unknown input type omits all free-form failure details with a safe marker, including upstream-truncated secret prefixes or head/tail echoes, and sets `inputRedactionLimited` and `failureDetailsOmitted`. Free-form fields longer than 1024 characters are also omitted and marked truncated; structured metadata remains available. Structured status, category, and resource identity are only length-bounded. SSH fan-out retains bounded metadata, errors, and stderr for up to 64 instances, prioritizing failures; `instanceCount` and `instancesTruncated` identify the total and omitted instances. When private tokens exist and an operation or instance record reports capture/detail truncation (such as `stderrTruncated` or `stderrArtifactTruncated`), that record's free-form failure details are also omitted to avoid leaking a split short secret's prefix/suffix. Without private tokens, bounded previews can remain available. Primitive arrays redact both the complete list rendering and individual elements within the same node/token budgets. Returned DB failures extract a safe summary from native `result.error` (`type`, bounded/redacted `message`, `sqlState`, `vendorCode`, and safe cancellation metadata), retain it as DB evidence `error`, and use it for the collector's `error`; rows, parameters, SQL text, and raw results are omitted. Failed command `stdout` can remain as separate diagnostic evidence under the same bounded/redacted/omission policy as `stderr`; it is not used as `error.message` or restored as the failed `result`. MQ resource nodes and error summaries retain `completionCode`, `reasonCode`, and bounded symbolic `reason`. Safe location metadata includes HTTP `method` and the `url` origin (scheme/host/port only), and MQ `queueManager`, `physicalInstance`, `host`, `port`, `channel`, and `transport`. HTTP evidence does not carry resolved request inputs, so failed collector URLs always omit path, query, fragment, and user info, with `urlPathOmitted` identifying omitted components; no raw input is added. URLs that cannot be safely parsed or exceed the budget are omitted with a safe marker.

### Advanced diagnostics

#### When does an unexpected exception get a stack trace?

Unexpected internal failures such as `NullPointerException`, `ClassCastException`, reflection lookup/access failures, other unexpected runtime exceptions, and non-domain `IllegalStateException` (including when nested in a wrapper cause) add a bounded `[ATT INTERNAL ERROR]` block to `case.log` with the execution phase and original cause chain. Validation `IllegalArgumentException`, recognized domain/transport failures, timeout/cancellation, assertion failures, and ordinary MQ no-message outcomes remain concise. A Throwable is written only once per Case log even when both a resource executor and its Action boundary see it. Resource-specific redactions (including environment-supplied SSH identity-file paths) are registered with that Case log and applied to subsequent log writes, so the outer Action diagnostic cannot expose text omitted from the sanitized stack. Stack output is capped at 180 lines/16 KB; configured secrets and sensitive key/value assignments are also redacted. Public Action evidence contains only the compact error type/phase, not the stack. The shared logging path covers Run, Debug, and reusable Tool/HTTP/MQ/DB execution.

#### Why does `att.bat` ask for Maven, or why does a `.sh` tool fail on Windows?

In a binary release, `att.bat` finds `lib\att-*.jar` and only requires Java 8+. In a source tree it compiles with Maven when Maven is on `PATH`; without Maven, previously compiled `target\classes` must exist. Use `att.bat version` to confirm the launcher before validating the package.

The launcher makes ATT itself cross-platform; it cannot translate external tool executables. Configure a Windows-compatible `.bat`, `.cmd`, PowerShell script (with an explicit `powershell`/`pwsh` argv), or native executable instead of a POSIX-only `.sh` command. PATH validation follows Windows `PATHEXT`, so names such as `pwsh` can resolve `pwsh.exe`. Keep argument contracts and stdout output formats identical when maintaining platform variants.

#### Why did ATT say it will use mwiede/jsch, or why did Java SSH algorithm negotiation fail?

ATT uses the local `ssh` command when it is executable on `PATH`. If it is absent, ATT prints `local ssh command not found; ATT will use Java SSH library mwiede/jsch` and opens a Java exec channel instead. This is an automatic fallback, not a remote connectivity test.

The fallback is deliberately minimal: ATT includes `com.github.mwiede:jsch:2.28.2` but does not bundle Bouncy Castle. It requires a readable non-symbolic-link `~/.ssh/known_hosts` for strict host verification. It does not read `~/.ssh/config` or automatically use the OpenSSH agent; configure an unencrypted or otherwise non-interactively readable `identityFile`. Password and interactive passphrase prompts remain unsupported.

Algorithm availability depends on the Java runtime:

| Algorithm | Java fallback limitation | Preferred solution |
|---|---|---|
| `ssh-ed25519`, `ssh-ed448` | Require Java 15+, or a Bouncy Castle provider | Prefer local OpenSSH or Java 15+; otherwise have an administrator add approved `bcprov-jdk18on` to the runtime classpath |
| `curve25519-sha256`, `curve448-sha512` | Require Java 11+, or Bouncy Castle | Prefer local OpenSSH or Java 11+; otherwise use an approved Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | Requires Bouncy Castle on every Java version | Prefer local OpenSSH, enable an AES-GCM/CTR cipher on the server, or add an approved Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` signatures | Disabled by default by mwiede/jsch | Update the server to RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) or another modern host/user-key algorithm; do not re-enable SHA-1 except as a reviewed temporary legacy measure |

When negotiation fails, first run the same connection with local `ssh -v` to identify the host-key, key-exchange, cipher, or user-key mismatch. Prefer upgrading Java or the server's algorithm set over weakening JSch defaults. The authoritative compatibility notes and configurable `jsch.kex`, `jsch.server_host_key`, `jsch.cipher`, and `jsch.mac` system properties are documented in the [mwiede/jsch README](https://github.com/mwiede/jsch). ATT does not change those secure defaults.
