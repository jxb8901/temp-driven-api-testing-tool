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

`width` must be an integer from 1 through 1000. The name must be non-blank text; with one positional argument, a number means `width` and a string means sequence name. More than two arguments, mixed named/positional argument styles, invalid argument types, blank names, fractional/zero/negative/out-of-range widths are errors. Diagnostics identify `seq.next` and the invalid arity, argument, or range. If a padded value needs more digits than `width`, or the underlying `Long` counter overflows, evaluation fails explicitly; ATT never truncates a sequence or silently exceeds the requested width.

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.



`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.







Typical expressions:

```yaml
assert: "${EXEC.INPUT.amount} > 0"
assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
assert: "${EXEC.ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${EXEC.INPUT.channel} == 'MOBILE') and (${EXEC.INPUT.amount} <= 1000)"
```

### Expression scope and errors

This chapter defines the language. Each field's owner defines available roots and evaluation timing: [Tool command/call](reference/05_resources/tools.md), [Load execIdFormat and vars](reference/04_execution_modes/load.md), [Debug vars](reference/04_execution_modes/debug.md), and [report filenames](reference/09_configuration.md). `${path?}` permits an absent allowed map/list path to return null; malformed syntax and illegal scope access still fail. Expression syntax and missing required Context paths produce structured diagnostics; see [Validation](reference/12_validation_diagnostics.md).

Removed APIs: `dbText`/`misc.dbText`, `prettyPrint`/`misc.prettyPrint`/`format.pretty`, all local `file.*` built-ins and their legacy aliases. Keep DB results typed and migrate display calls to Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` with `format: sqlplus`; use `format: json` or `yaml` for Maps/Lists. Read project content with `&{...}` and inspect or change remote files with SSHHelper `stat`, `mkdirs`, `move`, and `delete`; use `upload`/`download` for transfers. ATT local output remains framework-owned. Removed calls fail with migration guidance.

### Retry-condition lifecycle

`retry.when` runs after the current attempt completes, only after retryOn matches and while another attempt is available. `output.*` binds current result/evidence/diagnostic and `output.attempt`. Normal Boolean typing and strict/optional Context paths apply. Only deterministic pure built-ins are permitted; external, file, sequence, random and current-time operations are rejected. See [Action retry](reference/14_actions.md).

## 06 Execution Modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ/HTTP/SSH execution semantics.

| Mode | `EXEC.ID` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | one target invocation | `output/debug/<debugId>/` |
| Load | unique iteration execution ID | repeated target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` identifies the enclosing ATT run. Mode and scheduler-specific information are retained under evidence-only `DIAG`; neither `EXEC.MODE`, `EXEC.LOAD`, nor `DIAG` is available to expressions.

All three resolve the selected environment before execution, construct canonical Context, validate the target/dependency closure, and use the same component contracts. Mode-specific scheduling, selection and reporting do not create alternate Template or expression semantics.

| Mode | Typical use |
|---|---|
| Run | Normal functional SIT/UAT execution |
| Debug | Isolate one Template/Flow/Tool during authoring or diagnosis |
| Load | Repeated/concurrent performance execution |

All three share the execution model. Each mode owns its inputs, identity/bootstrap lifecycle, CLI behavior and output layout.

### 6.1 Run Mode

Run is workbook-driven Testcase execution.

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT loads the effective configuration/environment, verifies canonical workbook snapshots, validates the selected dependency closure, reserves a unique Run ID, then executes selected Cases in Stage order. A Stage resolves its selector to a Template; Actions execute in YAML order subject to `runWhen` and `onFailure`.

Run evidence is written directly below `output/<RunID>/`. The completed run publishes `run.yaml`, Case directories/logs, result workbooks, HTML/CI outputs as configured, and only after completion updates `latest-run.yaml`. A pre-existing Run ID is rejected rather than overwritten. `run --update-snapshot` is the explicit opt-in snapshot refresh path before validation/execution.

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for test/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime/infrastructure error.

Use Chapter 10 for exact selectors/options, Chapter 8 for execution control, and Chapter 11 for artifact contracts.

### 6.2 Standalone Debug

Debug executes one Template, Flow or Tool without requiring a workbook Testcase.

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Run `./att.sh debug` with no target to list statically valid runnable Tools, Templates and Flows with copyable commands. A default sidecar path is displayed only when that regular non-symlink file exists. Discovery validates selected target dependencies but does not create Debug output or invoke Tools. Use `--format json` for machine-readable discovery output.

Debug input uses the current `schemaVersion: att-debug/v1.1`. Supported top-level data is `case`, optional `stage`, `inputs`, `vars`, `arguments`, and grouped `tools.<localKey>.arguments`. `inputs` is adapted into canonical `EXEC.INPUT`; Template/Flow `vars` is evaluated as a typed bootstrap tree and seeds canonical `EXEC.VARS` before a target starts. Tool Debug uses `arguments` and does not support `vars`. Framework-owned identity, output, Actions, resource metadata and compatibility views cannot be overwritten by user input. Schema migration is documented in [Appendix C](reference/appendices/migrations.md).

#### Standalone Debug bootstrap data

The three input contracts are intentionally separate:

| Debug field | Runtime destination | Use |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input consumed directly by a Flow/Template |
| `vars` | initial `EXEC.VARS` | Caller-prepared values expected by a reusable Flow/Template |
| `arguments` / `tools.<localKey>.arguments` | Tool argument contract | Explicit arguments for standalone Tool Debug |

A Flow that only consumes `EXEC.INPUT` needs no `vars`. A Flow that normally runs after a parent Flow publishes `EXEC.VARS.refNo` can be debugged directly with a scalar or typed structure:

Debug `inputs` use the same Testdata mapping syntax as Run: select a configured environment with `--env`, then use exact `@{id}` / `@{id.path}` references or scalar interpolation. ATT resolves these before publishing `EXEC.INPUT`; direct Testdata markers remain invalid inside the reusable Template, Flow or Tool definition. See [Testdata Registry and Input Mapping](reference/02_test_authoring.md).

```yaml
schemaVersion: att-debug/v1.1
inputs:
  amount: 100
vars:
  refNo: REF001
  txnSeq: 23
  tags: [SIT, PAYMENT]
  order:
    id: ORD001
    amount: 100
```

```sh
./att.sh debug flow common.payment --input common.payment.debug.yaml
```

`vars` values use the shared expression engine: an exact `${EXEC.INPUT.amount}` preserves its native type, interpolated text becomes a string, and `#{...}` preserves the expression result type. Maps and lists recurse; map keys remain literal. Vars may reference other vars regardless of declaration order; cycles, missing vars, unavailable roots, and side-effecting calls fail before the target starts. The first normal `assign` may replace a bootstrapped variable, after which normal duplicate-assignment rules apply. Final values appear through the normal `EXEC.VARS`/`CASE.VARS` context and result artifacts, subject to existing redaction rules; no second Debug-only namespace is created.

Bootstrap values may use initialized execution identity, `EXEC.INPUT`, `EXEC.LOAD` when present, other `EXEC.VARS.<name>` entries, and stable project/source/target/template metadata. `EXEC.ACTIONS`, action-local `output`, and invocation-scoped metadata are not available. Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are blocked; safe pure built-ins use the normal ATT parser. Repeatable `--set` namespaces are `input.path=value`, `vars.path=value`, and Tool-only `arg.name=value`. Values are parsed as safe YAML; nested maps and numeric list indexes are supported where the destination accepts them. For example, `--set 'vars.refNo=${EXEC.INPUT.refNo}'` changes the raw definition before evaluation.

Without `--input`, ATT looks for `debug.yaml` beside a selected Template or Flow and for `config/tools/<group>.debug.yaml` for a grouped Tool (`config/tools/<localKey>.debug.yaml` for an ungrouped Tool). When no default sidecar exists, supply `--input`; an explicit `--input` replaces auto-discovery. `--env` uses the same environment resolver as Run/Validate/Load before target validation.

Debug performs target-scoped validation: it validates the selected Template/Flow dependency closure or Tool contract, rather than requiring unrelated workbooks. Template and Flow debug use the same Action/Flow scope rules as Run. Tool debug constructs the same configured Tool invocation contract.

Each invocation is isolated under:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug does not create or update normal `latest-run.yaml`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input/validation, and `3` runtime error. It is execution-equivalent at the selected reusable-component boundary, but it is **not** a workbook Case: there is no workbook selection, Stage history or result-workbook lifecycle unless explicitly represented by debug inputs/artifacts.

### Debug troubleshooting and MQ payload paths

When a debug target cannot be resolved, check the target kind and identifier first, then use `--input <path>` to remove sidecar discovery from the diagnosis. Template and Flow debug discover `<target directory>/debug.yaml`; grouped Tool debug discovers `config/tools/<group>.debug.yaml`. The selected target's dependency closure is validated, so an unrelated workbook or Case file is not a prerequisite.

MQ `file` arguments follow the same safe path rules in Debug, Run and Load:

- An absolute path must resolve to a regular file inside the ATT package root. It is validated against the package root even when Load has not materialized a lazy iteration workspace yet.
- A relative path is resolved under the active Case output directory. `..` traversal, symlink payloads, symlink escapes, directories and non-regular files are rejected before MQ connect/open/put/get.
- A missing or unsafe payload reports the payload path directly. No MQ connection is attempted, so a path error should be fixed before investigating broker credentials or queue state.

Use the output directory to separate diagnosis stages:

| Symptom | Check |
|---|---|
| `Debug input file does not exist` | Add the sidecar beside the selected target or pass `--input` explicitly. |
| `Debug input uses a historical schemaVersion` | Use the active Debug schema; see [Appendix C](reference/appendices/migrations.md); add `vars` only when a Flow/Template needs caller-prepared `EXEC.VARS`. |
| `target` or dependency validation fails | Confirm the target type/id and inspect the reported dependency field; unrelated workbook files are not required. |
| MQ reports a missing/unsafe payload | Verify the absolute package path or the relative Case-output path; remove traversal and symlinks. |
| The action runs but output is unexpected | Read `case.log`, `result.yaml` and the action artifacts under `output/debug/<debugId>/`; compare rendered inputs with the selected environment. |

Load-specific evidence retention (`metrics`, `failures`, `samples`, `all`) does not apply to a standalone Debug invocation. Debug always keeps its invocation result and artifacts under its own debug directory; see the Load evidence retention section in Chapter 4 when the same target is exercised by a load run.

#### Configuration examples

The following examples show the supported placement of debug values. Every file is a complete `att-debug/v1.1` document.

Template sidecar (`templates/PAYMENT_INVOKE/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: PAYMENT debug
  amount: 100
  environment: SIT
stage:
  key: invoke
  values:
    channel: WEB
    sourceRef: SRC-001
```

Run it with `./att.sh debug template PAYMENT_INVOKE`. Template expressions should prefer `${EXEC.INPUT.amount}`, `${EXEC.INPUT.environment}`, and the current Stage value `${EXEC.INPUT.channel}`; the current Stage's `values` overlay Case-level input for that Stage, with the Stage value winning on collisions. The corresponding `CASE.*` paths remain compatibility aliases, while `CASE.STAGES.*` is retained only as the legacy execution/evidence view.

Flow sidecar (`templates/flows/common/compose/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: Compose debug
  traceId: TRACE-001
stage:
  key: DEBUG
  values:
    mode: SIT
inputs:
  source: payment
  suffix: -debug
```

Run it with `./att.sh debug flow common.compose.v1`. Flow inputs are available as `${EXEC.INPUT.source}` and, when there is no same-named Case value, as the compatibility alias `${EXEC.INPUT.source}`.

Grouped Tool sidecar (`config/tools/fpp.debug.yaml` for `fpp.invokeApi`):

```yaml
schemaVersion: att-debug/v1.1
case:
  RefNo: REF001
tools:
  invokeApi:
    arguments:
      requestId: REF001
      requestType: PAYMENT
      requestFile: /tmp/payment-request.xml
      apiLogPath: /tmp/payment-api.log
```

Run it with `./att.sh debug tool fpp.invokeApi`. The `invokeApi` key is the group-local Tool key. Values must be scalar or list values accepted by the Tool descriptor; map literals are not supported by the standalone Tool adapter.

Ungrouped Tool sidecar (`config/tools/invokePaymentApi.debug.yaml`):

```yaml
schemaVersion: att-debug/v1.1
arguments:
  requestFile: /tmp/payment-request.xml
  environment: SIT
```

Run it with `./att.sh debug tool invokePaymentApi`. For an ungrouped Tool, root `arguments` is passed directly; it is not wrapped under `tools`.

An explicit file overrides sidecar discovery, which is useful for temporary values in CI or local diagnosis:

```sh
./att.sh debug template PAYMENT_INVOKE --input /tmp/payment-debug.yaml \
  --output-dir /tmp/att-debug --format json
```

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. Mode/scheduler diagnostics are not expression-visible. `EXEC.STAGES` is not a canonical Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

Each invocation writes:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
    └── case.yaml
```

`result.yaml` contains the target, status, exit code, duration, input path, Case ID, action results, diagnostic (when present), and evidence locations. Synthetic framework-owned fields always win over same-named values in `case`; debug inputs cannot replace `CASE.caseId`, `CASE.workbookId`, `CASE.groupId`, `CASE.rowCaseId`, `CASE.outputDirectory`, the legacy `CASE.STAGES` evidence view, `CASE.DB`, `CASE.VARS`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, or `DB.*`. Debug output is independent of ordinary `output/latest-run.yaml` and report lifecycle.

For `validate --format json`, stdout contains exactly one JSON document; progress and human diagnostics go to stderr.

### 6.3 Load Mode

ATT accepts att-load/v1.5 scenarios. A scenario has one or more workloads; each workload owns a fixed Template, Flow or Tool target, its inputs, testdata policy, bootstrap vars and pacing policy. Root defaults may be shared by all workloads, while workload-local fields override them. ATT validates the scenario and all targets before a scheduler starts.

Run `./att.sh load` with no scenario to discover valid full Load descriptors under `load/`. Only YAML declaring `schemaVersion: att-load/*` is considered; unrelated YAML is ignored, while invalid declared descriptors are shown with their diagnostics. Discovery resolves and validates targets without starting a scheduler or making resource calls.

#### Scenario shape

~~~yaml
schemaVersion: att-load/v1.5
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK, account: "@{generatedAccounts}", accountId: "@{generatedAccounts.id}"}
    testdata:
      generatedAccounts:
        scope: iteration
        selection: {strategy: sequential, exhaustion: recycle}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

A target accepts template, flow or tool; Tool targets may provide named arguments but cannot declare bootstrap vars. Workload inputs become EXEC.INPUT for each iteration; Template/Flow workload vars become a fresh initial EXEC.VARS tree for every started iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

| Workload field | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input values; not bootstrap variables |
| `vars` | initial `EXEC.VARS` | Typed expression tree for Template/Flow; independently evaluated per execution |
| `target.arguments` | Tool arguments | Tool-only call arguments; separate from `EXEC.INPUT` and `EXEC.VARS` |

ATT snapshots each iteration's input map once as a deeply immutable tree. Request metadata copies reuse that snapshot, and the Load adapter passes its nested values through into the per-iteration `EXEC.INPUT` map without copying them again. A later change to the caller's source map cannot affect a started iteration, and separate iterations do not share their input snapshots.

#### Testdata imports and workload scopes

The environment profile contributes the shared `testdata` descriptor list. A scenario's optional top-level `testdata` list imports package-relative YAML files as a Load-only overlay. A matching local ID replaces the whole environment descriptor for that scenario; records and selection settings are not merged. Duplicate IDs within either layer fail validation.

Use `inputs` to map `@{id}`, `@{id.path}`, or scalar interpolation into `EXEC.INPUT`. Each ID is selected once for a mapping, and the configured `scope` controls how long that choice is reused: `workload`, `user`, or `iteration`. If omitted, Load uses `iteration`. `user` requires a closed-VU workload and is invalid for `arrivalRate`. The workload `testdata` map is policy only; it does not import descriptors. Its optional `selection` object replaces the descriptor's entire selection policy. Policies support `sequential`, `roundRobin`, or seeded `random`, with exhaustion behavior `error` (default), `recycle`, or `stop`. `stop` ends that workload cleanly after its records are consumed. A one-record descriptor needs no selection policy.

Selection evidence records only the testdata ID, source layer, record index, generated sequence where applicable, scope, strategy, and random seed. It never includes the record contents. Run and Debug load only IDs used in mappings; Load validates referenced IDs and explicit workload policies before starting the scheduler.

#### Per-execution bootstrap vars

After the scheduler identity and unique EXEC.ID/EXEC.OUTPUT_DIR are ready, ATT evaluates each workload's `vars` tree before starting its Template or Flow. Exact `${...}` references preserve native types, mixed text becomes a string, `#{...}` uses the ordinary typed expression parser, and nested maps/lists are evaluated recursively. References between vars are declaration-order independent; missing vars and dependency cycles fail before the target starts. Each iteration owns its evaluated maps/lists, so concurrent users and workloads cannot share mutations. The first normal `assign` may replace a bootstrapped variable.

Bootstrap expressions may use initialized `EXEC.RUN_ID`, `EXEC.ID`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.LOAD`, other `EXEC.VARS.<name>` values, and stable project/source/target/template metadata. `EXEC.ACTIONS`, action-local `output`, invocation-scoped metadata, and Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are unavailable. Only safe pure built-ins are permitted. Tool arguments remain separate from `vars`.

#### Workload models

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

duration is required. warmup, rampUp and rampDown default to zero. Warm-up sends real traffic but is excluded from measured threshold aggregates. Optional seed makes closed-VU think-time randomization deterministic.

#### Load identity and output layout

Each started iteration has a unique EXEC.ID across the Load run and shares EXEC.RUN_ID. If execution.execIdFormat is omitted, ATT uses its default run-scoped ID. Otherwise, ATT evaluates it once during initialization with the ordinary ${...} / #{...} engine. Bootstrap vars are evaluated after that identity is published, so they can use EXEC.ID and EXEC.OUTPUT_DIR. Closed workloads can use EXEC.LOAD.USER_ID; arrival-rate cannot. See the execution ID initialization section below for field availability and function restrictions.

Generated IDs must be non-empty, path-safe segments. Duplicate IDs fail before the target starts; ATT does not silently append a suffix.

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── action outputs written under EXEC.OUTPUT_DIR
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

A metrics-only iteration still has EXEC.ID but does not create a per-iteration execution directory unless an operation writes an artifact or a retention decision materializes evidence. EXEC.OUTPUT_DIR remains the logical planned path at executions/<EXEC.ID> while the iteration runs. Retained failures and sampled successes receive an evidence copy under failures/<EXEC.ID>/ or samples/<EXEC.ID>/. The report and evidence summary show EXEC.ID and link to case.log when it exists. Helper resource-output formatting is deferred until retention; explicit Tool evidence collectors still execute because they are author-requested diagnostic operations.

#### Evidence and resource output

`evidence.mode` accepts `metrics`, `failures`, `samples` or `all`; the default is `failures`. These modes set the default effective success/failure policies to `none/none`, `none/full`, `sample/full` and `full/full`, respectively. Explicit `evidence.success` and `evidence.failure` values override those defaults independently. `sampleRate` and `maxSamples` bound retained evidence. Dropped arrivals do not create iteration evidence.

Case-log capture follows the effective success/failure policies and remaining retention capacity before each iteration starts. If the effective failure policy is `full` and a `maxSamples` slot remains available, failures (including unselected successes under `samples`) keep a redacted rolling in-memory tail of at most 65,536 characters; ATT materializes it only when a failure claims a retention slot. Once no failure can be retained because `maxSamples` is zero or exhausted, per-action serialization and buffering are skipped. A slot reserved by an in-flight iteration may conservatively make the scheduler skip capture for other iterations. The latest action and runtime failure details remain at the end of a retained log, after a truncation marker. Selected sampled successes and retained full-success evidence use full deferred case logs; if a success-reserved iteration fails, its full deferred log remains available for failure evidence using that same reserved slot. For example, `mode: metrics, failure: full` enables bounded failure capture when capacity remains, while `mode: failures, failure: none` skips failure capture.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, exact project-file String output and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

#### Reports, metrics and thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Top-level thresholds apply only to the aggregate run; workload thresholds apply only to their individual workload. Root thresholds are not inherited into workload thresholds. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

#### Render plans and payload snapshots

Before a Load workload scheduler starts, ATT resolves each reachable Render payload glob once and freezes the matched UTF-8 source content and compiled reference/expression structure for that run. A payload edit, replacement, or new glob match made while the run is active does not affect its iterations; the next Load run resolves the package again. Normal Run and Debug use a fresh plan for each execution, so edits are picked up by the next execution.

Each iteration evaluates Context references, built-in calls, and external calls against its own Context. ATT reuses the parsed structure and source text, never a dynamic rendered result; stateful calls such as `seq.next()`, clock/random functions, and external calls still execute for each iteration. Render returns its String in memory, so passing `ACTIONS.<id>.output.result` to a downstream action does not create an intermediate Render file. Use `EXEC.OUTPUT_DIR` only when an operation explicitly needs a file.

With `--profile`, `performance.json` records `renderPlansCompiled`, `renderPlanCacheHits`, `renderPayloadResolutions`, `renderPayloadResolutionCacheHits`, `renderEvaluations`, `renderArtifactWrites`, and `renderSourceBytes`. These bounded run totals show source-plan reuse separately from per-iteration evaluation; `renderArtifactWrites` is zero because Render itself returns a String without writing an artifact.

#### CLI and examples

For one workload, options such as --users, --arrival-rate, --warmup, --ramp-up, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

Repeatable `--set` accepts `input.path=value`, Tool-only `arg.name=value`, or Template/Flow-only `vars.path=value`. Values use safe YAML parsing and remain typed; nested maps and numeric list indexes are supported where practical, for example `input.customer.ids[0]=42`. Duplicate assignments apply in order (last wins). ATT expressions are not evaluated during option parsing. Unqualified overrides are rejected for multi-workload scenarios.

`load/load.yaml` is an optional current `att-load/v1.5` policy-only descriptor with no target, inputs or Tool arguments. It contains the default `load` policy and may also declare `execution`, `thresholds`, `evidence`, `seed` and Load-local `testdata` imports. Explicit CLI pacing values override the policy. `load --debug template|flow|tool <id>` promotes the selected sidecar's `inputs`, `vars` or Tool `arguments` into a transient single-workload scenario and then uses the regular Load validation, scheduler and evidence pipeline; Debug execution is not run first. With no policy, provide a complete CLI policy such as `--users 2 --duration 10s` (arrival-rate also requires `--max-concurrent` and `--overload-policy`).

Example policy descriptor (copy to `load/load.yaml`):

~~~yaml
schemaVersion: att-load/v1.5
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../examples/load/README.md). Schema migration is documented in [Appendix C](reference/appendices/migrations.md).

### Load execution ID initialization

Load uses schema att-load/v1.5. If execution.execIdFormat is present, ATT evaluates it once per started iteration with the normal ${...} / #{...} engine during initialization; otherwise the default run-scoped ID remains in effect. Bootstrap vars are evaluated after the generated ID and output path are published.

Available values include EXEC.RUN_ID, timestamps, EXEC.INPUT, EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE, closed-only EXEC.LOAD.USER_ID and the already curated META.PROJECT/SOURCE/TARGET/TEMPLATE. EXEC.ID and EXEC.OUTPUT_DIR are unavailable because the generated ID determines the workspace. No Action has run, so EXEC.ACTIONS and invocation-scoped Flow/Tool/helper META are absent.

Only deterministic, side-effect-free built-ins are allowed. External Tool/DB/MQ/HTTP/SSH calls and stateful, random, clock or filesystem functions are rejected. seq.next() is neither allowed nor required. Use stable identity components:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate has no USER_ID:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

IDs must be non-empty, path-safe single segments and unique within the Load run. Duplicate or unsafe values fail before the target starts; ATT does not append a hidden suffix.


execIdFormat permits deterministic, side-effect-free built-ins only; external calls, seq.next(), random, clock and filesystem functions are rejected. See [Appendix C](reference/appendices/migrations.md) for schema migration.

## 07 Resources and Integrations

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are peer integration/resource types. SSHHelper routes command-backed Tools and exposes `ssh.<helperId>.execute|upload|download` Resource Helper operations. They converge on the common operation-result/evidence contract.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> SSH routing or Resource Helper /
```

Resource IDs are logical contracts referenced by Templates/expressions or Tool groups. Environment profiles may bind the same DB/MQ/HTTP/SSH logical ID to different descriptors without changing Action YAML.

The lowercase `x-` prefix can disable fields and keyed entries in ATT-owned resource configuration. It does not strip user data such as HTTP header names or DB parameters; see [Configuration](reference/09_configuration.md#ignore-or-disable-att-owned-configuration-with-x).

### 7.1 Operation Result and Evidence

ATT keeps an operation's logical result separate from execution evidence:

~~~text
Operation
├── result       # native typed value
└── evidence     # bounded execution/transport metadata
~~~

The Action publishes the final operation value at output.result. Action status, assertion detail, diagnostic and attempts describe execution; they do not replace the business result. Command stdout is parsed through stdoutFormat. HTTP/MQ responses use responseFormat. DB operations return native typed values. A project-file expression returns exact file text as a String, as described in [Actions and Typed Values](reference/14_actions.md).

Resource evidence can include low-cost metadata. A helper may also configure an optional human-readable snapshot:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output supports json, yaml, xml, text and sqlplus. It is presentation only; it does not mutate or replace output.result. Secret-bearing values are filtered or omitted.

Load scenarios may set evidence.resources.output to inherit (default) or none. none skips optional resource-output formatting/materialization. inherit defers formatting until a success sample or failure receives a retention slot. Metrics-only iterations do not serialize resource output or create an evidence workspace. Transport parsing and project-file String representation are unchanged.

### 7.2 Tool

A Tool is a named external or framework-native capability. A descriptor selects exactly one backend: command or call.

#### Command-backed Tool

A command Tool declares stdoutFormat to parse external stdout into the typed result:

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat accepts text, json, yaml or xml. It is ingress parsing: ATT parses stdout once and publishes the typed value at output.result. It does not control human-readable logging or file output. Exit code, bounded stdout/stderr preview and streamed process artifacts remain evidence.

#### Call-backed Tool

A call-backed Tool invokes a built-in or supported native DB/MQ/HTTP/SSH operation. Its native typed return value is output.result. Call-backed descriptors do not declare stdoutFormat.

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation has no result.format/path/overwrite contract. File persistence is explicit to an API that defines it; human-readable presentation belongs to Log or configured evidence output. HTTP/MQ parsing is owned by those transport boundaries.

See [Actions and Typed Values](reference/14_actions.md) for Action result handling and [Operation Result and Evidence](reference/05_resources/operation_result.md) for typed results versus evidence.

### Tool-definition `command` expressions

#### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestText}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestText}` | Explicit Tool-input namespace |
| `${TOOL.input.requestText}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a project-file payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
        name: Request Body
        description: Request body String
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

The action call is the boundary between the general Runtime Context and this restricted Tool-input Context:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestText=${EXEC.VARS.requestText}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestText` and `environment`. The command then substitutes `${input.requestText}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestText}` / `${ENVIRONMENT}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases shown above, and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestText})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestText}` is canonical; `${TOOL.input.requestText}` and `${requestText}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestText` or `input.requestText` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestText}"
  - "--environment=${input.environment}"
```

Because this is a YAML argv list, each list item remains one atomic process argument even when its resolved value contains spaces or shell-like characters. ATT does not invoke a local shell.

#### Quotes, Context values, and atomic argv

Quotes inside a Tool call belong to the ATT expression grammar; they are not shell quotes. The outer `'...'` or `"..."` delimiters are removed before invocation, the opposite quote is literal, and a matching quote can be escaped with a backslash. A `${...}` reference embedded in a quoted value is interpolated, while an unquoted canonical Context path passes its typed value directly.

The following configured Tool keeps each declared input as one argv value:

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${input.message}", "${input.sourceFile}"]
    stdoutFormat: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

Use a YAML block scalar when a call contains several quote layers:

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}
```

The child process receives the three messages exactly as `Customer O'Reilly`, `status="READY"`, and, for example, `O'Reilly said "READY" for payment.payment.TC001`. A Context value that itself contains either quote needs no caller-side shell escaping and still occupies one argv item.

If a call is kept on one YAML line, YAML escaping is an additional and separate layer:

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

The first line escapes double quotes for the YAML double-quoted scalar. The second doubles the apostrophe for the YAML single-quoted scalar. The expression engine then evaluates the resulting `#{...}` text.

Ordinary process-backed Tools never ask a shell to reinterpret resolved inputs. Text such as `$HOME`, `$(date)`, `a*.xml`, `|`, `>`, and quotes carried by a Context value is passed literally. Use an explicitly reviewed wrapper when shell-like behavior is required; the shipped `fpp.exehelper` and `fpp.loghelper` provide only the narrowly documented pathname expansion above.

#### Illegal forms and token restrictions

Tool commands cannot directly read the general Runtime Context, use unique-suffix navigation, or navigate argument fields with bracket syntax. These `${...}` forms are rejected during configuration or package validation:

```text
${EXEC.INPUT.environment}
${EXEC.ID}
${EXEC.ID}
${STAGES.invoke.InstrAmt}
${input['requestText']}
${TOOL.input['requestText']}
${requestText.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestText})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a standalone global Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestText}"]
arguments:
  requestText:
    name: Request Body
    description: Project-file request body String
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestText}` or a transformed form such as `#{str.upper(${input.requestText})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

### Inline Tool descriptor fields

Global `tools` and Tool-group `tools` entries use the same Tool contract:

| Object | Allowed properties |
|---|---|
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `argName`, `argNameMode`, `delimit`, `x-*` |

### 7.3 DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.6` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: Orders JDBC resource
connection:
  driverClass: oracle.jdbc.OracleDriver
  url: ${ENV:ORDERS_DB_URL}
  username: ${ENV:ORDERS_DB_USERNAME}
  password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

In the current `att-template/v3.6` contract, DB operations run under ordinary `type: tool` Actions using `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)` calls. The call has exactly one String `sql` argument. Use a project-file expression such as `sql=&{sql/find-order.sql}` when the SQL is stored in the package; `sqlFile` is historical-only. Positional `params` and named `parameters` are mutually exclusive and use the same JDBC binding rules.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

[Reliability](reference/08_reliability_execution_control.md) owns Action timeout precedence, retry eligibility, attempt limits and replay cautions. DBHelper owns the descriptor's statement timeout and transaction lifecycle. Example of an eligible query:

```yaml
actions:
  waitForOrder:
    timeoutMs: 1500
    type: tool
    call: >-
      #{db.orders.query(
        sql='select status from orders where id = :id',
        parameters={id: ${EXEC.INPUT.orderId}}
      )}
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

An explicit update is also a Tool Action. It must not use automatic retry:

```yaml
actions:
  markOrder:
    type: tool
    call: "#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}"
```

The historical v3.5/v3.4 `type: db` Action and its `query`/`update` blocks remain available only through the archived schemas and compatibility loaders.



DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Action-level timeout/retry extends the shared Action lifecycle without changing the DBHelper identity or Context model.

### Dbhelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.6` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `statement`, `transaction`, `result`, `evidence`, `pool`, `x-*` |
| `connection` | required | required `url`; optional `username`, `password`, `driverClass`, `properties`, `readOnly`, `isolation`, `x-*` |
| `statement` | defaults | `timeoutSeconds` defaults to 30, integer 1–3600 |
| `transaction` | defaults | `scope: case|statement`, `onEnd: commit|rollback`; defaults `case`/`rollback` |
| `result` | defaults | `maxRows` 1000, `maxCellBytes` 1048576, `maxBytes` 10485760; positive bounded integers |
| `evidence` | defaults | `sql: full\|hash` defaults full; `parameters: values\|types\|masked` defaults values; optional `output: {format: json\|yaml\|xml\|text\|sqlplus, maxChars: 10000}` |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `connectionTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize`, timeout is at least 250ms |

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [DBHelper](reference/05_resources/dbhelper.md) for Action, expression, result, security, and lifecycle behaviour.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

The `waitForOrder` query above needs no following Log Action: configure its DBHelper with `evidence: {sql: full, parameters: masked, output: {format: sqlplus, maxChars: 10000}}` to retain an automatic SQL*Plus-style row snapshot. `sql` controls SQL evidence, `parameters` controls parameter representation, and `output` controls human-readable presentation only. Assertions and later Actions still read typed rows; `db.<id>.query`/`scalar` use the same policy.

### 7.4 MQHelper

MQHelper is a logical IBM MQ resource with one or more physical instances. Current descriptors use att-mqhelper/v1.2. Connection settings, credentials, queue defaults and pool limits belong to the resource and are not exposed through META.

~~~yaml
schemaVersion: att-mqhelper/v1.2
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

#### Sending project-file Strings and abstract values

A project-file expression produces a String and can be passed directly as payload:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT encodes the exact file text using the configured MQ charset/CCSID. It does not parse and reserialize the String. Do not supply requestFormat for a project-file String; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. String + requestFormat is rejected. payload and file are mutually exclusive. file remains available for explicit raw file input; a project-file expression does not create a file or targetFiles.

#### Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. See [Appendix C](reference/appendices/migrations.md) for schema migration.

See [Actions and Typed Values](reference/14_actions.md) for the shared typed-result contract.

### Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../schemas/att-mqhelper-v1.2.schema.json).

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

#### Request/reply timeout and replay policy

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

Standalone `receive` explicitly retains its non-error polling contract: MQRC 2033 returns PASS with `received: false` when the outer deadline has not expired, including a bounded wait that finds no message. An expired outer deadline is TIMEOUT. This operation-aware contract supersedes the earlier guidance that avoided request/2033 TIMEOUT to prevent replay. See [common retry semantics](reference/14_actions.md).

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
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

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

A Map/List is an abstract structured value and requires explicit requestFormat, such as body=${EXEC.INPUT.request}, requestFormat=json. requestFormat accepts text, json, yaml or xml and applies only to Map/List. String + requestFormat is rejected. body and file are mutually exclusive; file is explicit raw file input supported by the HTTP call. A project-file expression creates no result file and has no targetFiles.

The project-file String has no format metadata and does not set HTTP Content-Type. Configure contentType/header when a specific media type is required. Request charset/headers and response parsing remain HTTPHelper concerns, separate from Action result or Log formatting.

#### Failure and evidence

Transport/protocol and response-parse failures are operational errors. A received 4xx/5xx is a completed response and can be asserted through statusCode. HTTP evidence may include helper ID, method, safe URL, response status, content type, byte counts, response format and duration. Credentials and payloads are not implicitly stored. Load can set evidence.resources.output: none to skip optional resource-output formatting, or defer it until the iteration evidence is retained.

See [Actions and Typed Values](reference/14_actions.md) for the shared project-file String and typed-result contract.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

### 7.6 SSHHelper: logical SSH targets

#### SSH Resource Helper operations

SSHHelper also exposes the common Resource Helper form inside a normal `type: tool` Action: `ssh.<helperId>.execute`, `ssh.<helperId>.upload`, `ssh.<helperId>.download`, `ssh.<helperId>.stat`, `ssh.<helperId>.mkdirs`, `ssh.<helperId>.move`, and `ssh.<helperId>.delete`. The helper ID is logical; native Resource Helper calls select one physical instance using `single`, `random`, or `roundRobin`. `selection.strategy: all` is rejected for native Resource Helper calls; it is reserved for command-backed Tool fan-out. These calls are validated without opening an SSH connection, and they share the helper's concurrency bound and redacted identity handling.

```yaml
actions:
  health:
    type: tool
    call: >-
      #{ssh.application.execute(
        command='systemctl is-active example.service',
        stdoutFormat='text',
        timeoutMs=5000
      )}
  uploadRequest:
    type: tool
    call: >-
      #{ssh.application.upload(
        remotePath='/srv/app/request.json',
        payload=${EXEC.VARS.requestText},
        overwrite=true
      )}
  downloadResponse:
    type: tool
    call: >-
      #{ssh.application.download(
        remotePath='/srv/app/response.json',
        localPath='ssh/response.json',
        overwrite=true
      )}
```

`execute` requires `command` and accepts `stdoutFormat: text|json|yaml|xml` plus `timeoutMs`. Text returns the exact stdout String; the structured formats parse stdout into the native Map/List/scalar result. A timeout, non-zero remote exit, or parse failure returns an operation error with a distinct category and retains bounded stderr, exit code, byte counts and transport evidence. SSH resource execution treats a non-zero exit as an operation failure; this is separate from the legacy command-backed Tool fan-out contract described below.

`upload` requires `remotePath` and exactly one of `localPath` or `payload`. A local file must be a regular non-symlink file under the package or current Case output. A represented payload must resolve to a String or byte array; Map/List values are rejected rather than implicitly serialized. Absolute remote paths are allowed. Upload overwrite defaults to `true`.

`download` requires an absolute or relative `remotePath` and a Case-output-relative `localPath`. ATT writes through a temporary file and moves it into the controlled Case output directory; it does not parse the downloaded bytes. Download overwrite defaults to `false`, and an existing destination must be explicitly replaced with `overwrite: true`. The typed result is a transfer summary containing remote path, retained local path and byte count.

All operations accept only named arguments. Unknown operations, helper IDs, arguments, duplicate arguments, invalid formats, invalid timeout values, missing required fields, upload source conflicts and unsafe local paths fail validation before external execution. Runtime evidence contains the logical helper, selected instance, host/port, operation, transport, timing and transfer/command details; command input and represented payload content are not copied into evidence. Environment-supplied identity paths remain redacted.

Native SSH failures expose stable categories in both invocation `error.category` and `SSH.error.category`:

| Category | Meaning |
|---|---|
| `SSH_CONNECTION_ERROR` | Connection, host verification, or channel setup failed without a timeout; also used when the backend cannot identify authentication reliably. |
| `SSH_AUTH_ERROR` | The Java backend identifies authentication rejection/cancellation or cannot initialize the configured identity. |
| `SSH_TRANSPORT_ERROR` | OpenSSH returned 255. This may indicate connection/authentication failure or a remote command that itself exited 255; ATT retains stderr and exit code without guessing from localized diagnostics. |
| `SSH_TIMEOUT` / `SSH_POOL_TIMEOUT` | Operation/connect deadline or concurrency wait expired. |
| `SSH_REMOTE_EXIT` | A remote command completed with a non-zero exit (OpenSSH 255 uses the category above). |
| `SSH_RESULT_PARSE_ERROR` | stdout parsing failed. |
| `SSH_UPLOAD_ERROR` / `SSH_DOWNLOAD_ERROR` | The corresponding transfer failed after connection setup, including remote permission/missing-path/protocol errors. |
| `SSH_PATH` / `SSH_ARGUMENT` | Local containment/security checks or argument validation failed. |
| `SSH_INTERRUPTED` | The caller interrupted the operation. |

Transfer connection/channel failures retain `phase: connect|channel`; timeout evidence also retains the applicable timeout budgets. All failures keep their selected operation and actual transport. Transfer errors remain ineligible for automatic timeout replay.

Native Resource Helper calls use one absolute Action deadline covering concurrency-pool wait, connection and channel setup, and command or SFTP operation. A per-call `timeoutMs` or helper `timeouts.commandTimeoutMs` sets the operation limit but cannot extend the enclosing Action deadline. `timeouts.connectTimeoutMs` caps connection establishment within the remaining deadline; it does not add time to the operation. The timeout applies to execute/upload/download/stat/mkdirs/move/delete. Native `execute`, read-only `stat`, and idempotent `mkdirs` `SSH_TIMEOUT` and `SSH_POOL_TIMEOUT` failures may use Action `retryOn: [TIMEOUT]`, including through call-backed Tools; common `retry.when` controls replay. Native `upload`, `download`, `move`, and `delete` reject timeout retry because their mutation outcome may be uncertain after timeout. Validation and runtime use the same operation policy. A timed-out SFTP Action returns at its deadline while its concurrency lease remains held by the cleanup worker until the transfer worker and transport terminate.

SSHHelper routes a command-backed Tool to a stable logical application-server ID instead of embedding a physical host in the Tool group. The `att-sshhelper/v1.0` YAML descriptor contains `id`, optional `name`/`description`, optional `defaults` (`user`, `port`, `identityFile`), a non-empty ordered `instances` list, optional `selection.strategy`, and optional `fanout.maxConcurrency` (default 4, range 1–256). Each instance needs `id` and `host`; `user` must come from the instance or defaults. Instance fields override defaults; port defaults to 22 and must be 1–65535. Helper and instance IDs match `[A-Za-z_][A-Za-z0-9_-]*` and are unique ignoring case. Invalid hosts/users, unknown properties, duplicates, missing users, unsafe paths, and unsupported strategies fail before SSH execution.

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
timeouts: {connectTimeoutMs: 10000, commandTimeoutMs: 60000}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

Bind descriptor paths globally or in `environments.<NAME>.sshhelpers` of `att-config/v2.11`. Current packages use config v2.11 and Tool Group v2.9. The selected environment's list replaces the global list; omission inherits it. A group binding must resolve to the same logical ID in each selected profile. SIT can bind one host and UAT two without changing the Tool, Action, or Resource Helper call:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.11
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all} # command-backed Tool fan-out only; native ssh.application.* rejects all
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # optional group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

The unchanged Action calls `app.status`. Set `APP_SSH_KEY` to a readable private-key **path** in the local/CI secret environment, then validate both profiles: `./att.sh validate --config config/config.yaml --env SIT --package` and the equivalent UAT command. An exact `${ENV:NAME}` identity-file reference is resolved at load time; a missing/empty variable is rejected without revealing its value. A Tool group uses either direct SSH (`host`, `user`, optional `port`/`identityFile`) or logical SSH (`helper`, optional `selection`), never both. Call-backed Tools may target the native SSH Resource Helper call when it is the primary `type: tool` operation. The active Tool Group schema is v2.9. Command-backed Tools declare stdout parsing with `stdoutFormat` (`text|json|yaml|xml`); call-backed Tools preserve their native result type. Superseded config and group schemas are migration references only. SSH Resource Helper calls publish only `META.SSHHELPER.id` and `.type`; endpoint, user, identity file and credentials remain private. Resource calls have no Action- or per-call strategy override; selection remains helper configuration.

Strategy precedence is group override then helper default. For native Resource Helper calls, one instance works without a strategy (`single`), multiple instances require `random` or `roundRobin`, and `all` is rejected rather than silently selecting one host. For command-backed Tool routing, `random` selects one uniformly, `roundRobin` selects one via a thread-safe cyclic counter, and explicit `all` executes every listed instance once with bounded parallelism. **Command-backed `all` has side effects on every host**: use only commands safe across the entire group. There is no implicit fan-out, cross-host retry, or failover. If an author configures an Action timeout retry, the whole `all` invocation is repeated, not just one host. Each host gets the Action/Tool/global timeout; interruption cancels active OpenSSH processes or Java SSH sessions. Both transports receive the same normalized host/user/port/key. OpenSSH is preferred; mwiede/jsch fallback retains strict host-key verification and the limitations in the SSH diagnostics chapter.

For a single selected host, parsed `output.result` remains the legacy scalar/object value. Evidence adds `sshHelper`, `instance`, `host`, `selectionStrategy`, `selectionSource` (`helper` or `toolGroup`), transport, start/end/duration, exit code, output and errors. Only command-backed Tool fan-out uses `all`; its `output.result` contains `sshHelper`, effective `selectionStrategy`, `selectionSource`, and `instances` keyed in descriptor order. Every entry has `instance`, `host`, `port`, `transport`, `startedAt`, `endedAt`, `durationMs`, `status`, and when available `exitCode`, `stdout`, `stderr`, `rawOutput`, parsed `output`, or `error`. A completed command has `status: PASS` even with a non-zero `exitCode`; that code is evidence for the Action assertion, not an operational failure. The operation fails only on an execution, output-parse, cancellation, or timeout error; other hosts' evidence is retained. Assertions may inspect `${output.result.instances.app1.exitCode}`, `${output.result.instances.app1.status}`, or `${output.result.instances.app1.output}`. Credential contents and environment-supplied key paths are not recorded; keep private keys outside the package and do not put secrets in commands.

Environment-supplied identity paths are redacted from argv, transport stderr (including streamed Case-log diagnostics), and exception evidence for both single-host and `all` execution. This no-recording guarantee applies to ATT metadata and transport diagnostics; parsed business stdout remains unchanged, so commands must not print secret paths.

Migration: leave direct SSH unchanged if one physical target suffices. To migrate, move its host/user/port/key into a helper descriptor, bind that descriptor per environment, upgrade the group to v2.9, replace physical `ssh` with `ssh: {helper: application}`, and validate each environment. Actions stay unchanged. Inventory discovery, per-Action host override, distributed transactions, cross-host failover and orchestration are out of scope.

`stat(remotePath='/srv/app/result.xml')` uses SFTP lstat and returns typed `{path, exists}` plus `type: file|directory|other`, file `size`, and available ISO `modifiedAt`. A missing path is normal `exists: false`; permission/auth/transport errors still fail. `mkdirs(remotePath='/srv/app/archive')` creates parents, succeeds for an existing directory, and rejects a non-directory. `move(sourcePath='/srv/app/out.xml', targetPath='/srv/app/archive/out.xml', overwrite=false)` renames on the same selected host; source must exist and replacing a target requires explicit overwrite. It does not stage bytes locally. Explicit overwrite removes an existing matching regular file or empty directory before SFTP rename, so it also works on servers without rename-overwrite support. Non-empty directories, special files, and mismatched types are rejected. Replacement is not atomic: a removal failure preserves both paths; a later rename failure leaves the source in place and may leave the target absent. `delete(remotePath='/srv/app/tmp.xml', missingOk=false)` supports regular files and empty directories; missingOk is explicit, and non-empty directories, recursive arguments, and wildcard/backslash paths are rejected. All operations accept named arguments and optional `timeoutMs`, sharing selection, concurrency, deadlines, host verification, redacted identity, and Run/Debug/Load execution. Filesystem errors use `SSH_STAT_ERROR`, `SSH_MKDIRS_ERROR`, `SSH_MOVE_ERROR`, or `SSH_DELETE_ERROR`. There is no remote copy API; use explicit `execute(command='cp /srv/app/a /srv/app/b')` when needed. Project files use `&{...}`; ATT manages local output.

## 08 Reliability and Execution Control

This chapter owns cross-cutting public execution behavior.

### Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception/infrastructure problem is `ERROR`; invalid authoring/configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are therefore distinct.

### `runWhen` and `onFailure`

`runWhen` controls whether a statically known Action/Stage is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes the failed status into PASS. Cleanup/diagnostic work should use the documented conditional execution semantics rather than hiding failures.

### Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic/evidence. Timeout is an operational failure; it is not an assertion false result. Tool timeout behavior and resource-specific DB/MQ/HTTP/SSH limits are documented in their resource contracts.

### Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final/winning operation becomes top-level `output.result` / `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

### Evidence collectors

Tool evidence collectors run after the primary operation has published its typed `output.result` and before that attempt's assertion. While the Action is active, `${output.evidence.collectors.<id>.result}` and `${output.evidence.collectors.<id>.status}` are available; after publication the canonical paths are `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` and `.status`. Collectors have independent `timeoutMs` and `onFailure: continue|stop`. Collector output belongs to the attempt's evidence and never replaces or mutates the primary operation result.

Collectors run once per primary attempt. The top-level collector node represents the final/winning attempt, while `output.attempts[n].evidence.collectors.<id>` retains each attempt. `continue` keeps the primary/assertion outcome visible when diagnostic collection fails; `stop` makes the collector failure an Action error. Use an ordinary Tool/Log/Assign Action when the collected value is business/test data rather than pre-assertion diagnostics.

### Transaction/resource lifecycle

DB transaction finalization and DB/MQ/HTTP/SSH resource cleanup occur at the appropriate execution lifecycle boundary. These mechanisms can affect operation success/diagnostics but are internal resource state, not public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

### Stage execution controls

| Setting | Values/default | Meaning |
|---|---|---|
| `required` | boolean/`false` | Whether a blank selector is an error |
| `runWhen` | `normal`/default, `onSuccess`, `onFailure`, `always` | When the stage is eligible to run |
| `onFailure` | `stop`/default, `continue` | Whether later eligible work may continue |

`continue` never changes FAIL or ERROR into PASS. It only permits later eligible work to run.

| Earlier outcome | Later `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | Run | Run | Skip | Run |
| FAIL/ERROR with `stop` | Skip | Skip | Run | Run |
| FAIL/ERROR with `continue` | Run | Skip | Run | Run |

Use `onFailure` for rollback/diagnostics and `always` for cleanup or final evidence collection.


### Tool timeout precedence

Tool Action timeout overrides Tool descriptor timeout, which overrides global timeout. Sidecars, Stages and Templates do not own timeout/retry defaults. For call-backed DB Tools, the DBHelper statement timeout remains a backend ceiling. Each supported primary retry attempt runs its collectors before assertion; collector continuation behavior does not turn a failed primary operation into PASS.

### Direct DB timeout and retry eligibility

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. When present, `Action.timeoutMs` overrides `DBHelper.statement.timeoutSeconds`; otherwise the helper timeout is used. Each retry attempt gets a fresh Action timeout, and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION` and/or `TIMEOUT`. An explicit Action `timeoutMs` overrides the helper's statement-timeout default; without it, the helper default applies. JDBC query timeout is rounded up to whole seconds while ATT retains millisecond deadline cancellation. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; the top-level `output.result` / `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

Direct `update` Actions support `timeoutMs` but deliberately reject `retry`. A timeout or database/transport failure cannot generally prove whether a mutation reached or committed at the server, so generic automatic replay could duplicate business state. Application-specific idempotent retry must be modeled explicitly instead.

## 09 Configuration and Environments

This chapter is the authoritative reading reference for author-authored configuration. The files below [`schemas/`](../schemas) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

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

[Reliability](reference/08_reliability_execution_control.md) defines timeout/retry precedence and eligibility. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Ignore or disable ATT-owned configuration with `x-`

Prefix an optional ATT-owned field or an entry in an ATT-owned keyed collection with the exact lowercase `x-` to make it behave as absent. This applies to current config objects and keyed collections such as `tools`, environment profiles, Tool `arguments` declarations, `actions`, Action `evidence` collectors, report columns, and Debug Tool overrides. The YAML must still parse, but ATT does not schema-check, resolve, discover, evaluate, instantiate, execute, or publish a disabled entry. For a keyed collection, use this on the key. This does not disable or rename argument values supplied when invoking a Tool:

```yaml
schemaVersion: att-config/v2.11
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

### Multi-environment profiles in current ATT

`att-config/v2.11` is the active profile contract. Profiles can replace configured DBHelper, MQHelper, SSHHelper, HTTPHelper and testdata descriptor lists as a whole. See the resource chapters and [Testdata Registry and Input Mapping](reference/02_test_authoring.md) for each binding.

ATT selects an environment through one common `att-config/v2.11` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

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
schemaVersion: att-config/v2.11
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

The SIT and UAT DBHelper descriptors both use `id: orders`, while their JDBC URL and other physical connection details differ. The MQHelper descriptors both use `id: payment`, while host, queue manager, port, and channel differ. A complete descriptor pair, including pool settings and safe evidence policy, is in [`examples/environments/README.md`](../examples/environments/README.md).

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

Use profiles when the same test package is promoted across environments and only infrastructure bindings change. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. See [Appendix C](reference/appendices/migrations.md) for complete-config migration.

### Schema catalog

[`schemas/catalog.yaml`](../schemas/catalog.yaml) is authoritative for active schema registrations. Package validation checks registrations; archived schemas do not become active runtime contracts. See the complete matrix in [Appendix A](reference/appendices/schema_matrix.md).

### Global configuration

```yaml
schemaVersion: att-config/v2.11
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
    testdata: [config/testdata/accounts.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    testdata: [config/testdata/accounts.yaml]
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | Current: `att-config/v2.11`; the previous schema remains compatible. The example uses the active schema. |
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
