Warning: truncated output (original token count: 70238)
Total output lines: 3412

# ATT v4.2.1 reference manual

Author: Jeffrey + ChatGPT
Version: 4.2.1
Status: Normative end-user documentation; generated from modular sources

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->

**Contents**

- [Overview and product model](#overview-and-product-model)
  - [Product model](#product-model)
  - [RUN and LOAD execution models](#run-and-load-execution-models)
  - [Resources are peers](#resources-are-peers)
  - [Package boundaries](#package-boundaries)
  - [How to use this manual](#how-to-use-this-manual)
- [Test authoring](#test-authoring)
  - [Author and run a Testcase](#author-and-run-a-testcase)
  - [Distinguish Testcases from Case executions](#distinguish-testcases-from-case-executions)
  - [Authoring contracts](#authoring-contracts)
  - [Workbook](#workbook)
  - [Stage](#stage)
  - [Template](#template)
  - [Flow](#flow)
  - [Authoring lifecycle](#authoring-lifecycle)
  - [Test data ownership](#test-data-ownership)
  - [Testdata registry and input mapping](#testdata-registry-and-input-mapping)
  - [Advanced workbook and Snapshot details](#advanced-workbook-and-snapshot-details)
- [Actions and typed values](#actions-and-typed-values)
  - [Action types](#action-types)
  - [Separate logical values from representations](#separate-logical-values-from-representations)
  - [File-content expressions return a `String`](#file-content-expressions-return-a-string)
  - [Tool, DB and Flow results](#tool-db-and-flow-results)
  - [Tool evidence collectors](#tool-evidence-collectors)
  - [Log: typed value to case log](#log-typed-value-to-case-log)
  - [Expressions and variable scope](#expressions-and-variable-scope)
  - [Resource output evidence](#resource-output-evidence)
  - [Action output and evidence paths](#action-output-and-evidence-paths)
  - [Common Retry and boolean conditions](#common-retry-and-boolean-conditions)
- [Runtime and Context model](#runtime-and-context-model)
  - [Identity roots](#identity-roots)
  - [META field inventory and lifecycle](#meta-field-inventory-and-lifecycle)
  - [Invocation and scope rules](#invocation-and-scope-rules)
  - [Optional lookup and compatibility](#optional-lookup-and-compatibility)
  - [Lifecycle navigation](#lifecycle-navigation)
- [Expressions and built-ins](#expressions-and-built-ins)
  - [Unified expression engine](#unified-expression-engine)
  - [File-content expressions](#file-content-expressions)
  - [Argument result typing](#argument-result-typing)
  - [Testdata input mapping syntax](#testdata-input-mapping-syntax)
  - [Operators](#operators)
  - [Built-in functions](#built-in-functions)
  - [Resource helper methods](#resource-helper-methods)
  - [Expression scope and errors](#expression-scope-and-errors)
  - [Retry-condition lifecycle](#retry-condition-lifecycle)
- [RUN and LOAD execution adapters](#run-and-load-execution-adapters)
  - [Unified RUN and schedules](#unified-run-and-schedules)
  - [Run mode](#run-mode)
  - [Standalone Debug](#standalone-debug)
  - [Load mode](#load-mode)
- [Resources and integrations](#resources-and-integrations)
  - [Operation result and Evidence](#operation-result-and-evidence)
  - [Tool](#tool)
  - [DBHelper](#dbhelper-1)
  - [MQHelper](#mqhelper-1)
  - [HTTPHelper](#httphelper-1)
  - [SSHHelper: logical SSH targets](#sshhelper-logical-ssh-targets)
- [Reliability and execution control](#reliability-and-execution-control)
  - [Assertion and status](#assertion-and-status)
  - [`runWhen` and `onFailure`](#runwhen-and-onfailure)
  - [Timeout](#timeout)
  - [Retry and attempts](#retry-and-attempts)
  - [Evidence collectors](#evidence-collectors)
  - [Transaction/Resource lifecycle](#transactionresource-lifecycle)
  - [Aggregation](#aggregation)
  - [Stage execution controls](#stage-execution-controls)
  - [Tool timeout precedence](#tool-timeout-precedence)
  - [Direct DB timeout and retry eligibility](#direct-db-timeout-and-retry-eligibility)
- [Configuration and environments](#configuration-and-environments)
  - [Find a configuration task](#find-a-configuration-task)
  - [Configuration layers and precedence](#configuration-layers-and-precedence)
  - [Ignore or disable ATT-owned configuration with `x-`](#ignore-or-disable-att-owned-configuration-with-x)
  - [Select an environment profile](#select-an-environment-profile)
  - [Schema catalog](#schema-catalog)
  - [Set package-wide options](#set-package-wide-options)
  - [Identifier and path constraints](#identifier-and-path-constraints)
  - [Topology and secrets](#topology-and-secrets)
  - [Cross-mode consistency](#cross-mode-consistency)
  - [Separate configuration files](#separate-configuration-files)
  - [`config.report.fileNamePattern`](#configreportfilenamepattern)
  - [Configuration owners](#configuration-owners)
- [CLI reference](#cli-reference)
  - [Choose a command](#choose-a-command)
  - [Build a source checkout](#build-a-source-checkout)
  - [Find syntax, options, and examples by task](#find-syntax-options-and-examples-by-task)
  - [Use ATT Server from the CLI](#use-att-server-from-the-cli)
  - [Path display](#path-display)
  - [Typed overrides and quick Load](#typed-overrides-and-quick-load)
  - [Debug inputs and outputs](#debug-inputs-and-outputs)
  - [Exit codes](#exit-codes)
  - [Complete option matrix](#complete-option-matrix)
- [Results, reports, and evidence](#results-reports-and-evidence)
  - [Find artifacts for a Run](#find-artifacts-for-a-run)
  - [Find Case execution results in the HTML report](#find-case-execution-results-in-the-html-report)
  - [Diagnose Tool evidence collector failures](#diagnose-tool-evidence-collector-failures)
  - [Add execution results to a Workbook](#add-execution-results-to-a-workbook)
  - [Export JUnit results](#export-junit-results)
  - [Read the CI JSON summary](#read-the-ci-json-summary)
  - [Reproduce a Run](#reproduce-a-run)
  - [Generate docs and manage package output](#generate-docs-and-manage-package-output)
  - [Trace execution identity to an artifact](#trace-execution-identity-to-an-artifact)
  - [Inspect generated-output schemas](#inspect-generated-output-schemas)
  - [Reading `case.log` and `case.yaml`](#reading-caselog-and-caseyaml)
- [Validation and troubleshooting](#validation-and-troubleshooting)
  - [Choose your first diagnostic step](#choose-your-first-diagnostic-step)
  - [Start with validation](#start-with-validation)
  - [Common questions](#common-questions)
  - [Security reminders](#security-reminders)
  - [Validation JSON contract](#validation-json-contract)
- [CI, packaging, and operations](#ci-packaging-and-operations)
  - [Development/release gates](#developmentrelease-gates)
  - [Runtime dependencies](#runtime-dependencies)
  - [Studio inspection caches](#studio-inspection-caches)
  - [Documentation operations](#documentation-operations)
  - [CI and environment promotion](#ci-and-environment-promotion)
- [Appendix A — schema and version matrix](#appendix-a-schema-and-version-matrix)
- [Appendix B — compatibility and deprecated aliases](#appendix-b-compatibility-and-deprecated-aliases)
- [Appendix C — migration notes](#appendix-c-migration-notes)
  - [Independent Load evidence quotas](#independent-load-evidence-quotas)
  - [MQ request queue routing](#mq-request-queue-routing)
  - [File arguments and case-log paths](#file-arguments-and-case-log-paths)
  - [Previous release Testdata migration](#previous-release-testdata-migration)
  - [Execution identity format migration](#execution-identity-format-migration)
  - [Historical schema migration](#historical-schema-migration)
  - [Debug schema migration](#debug-schema-migration)
  - [Global configuration migration](#global-configuration-migration)
  - [Environment profile migration](#environment-profile-migration)
- [Appendix D — limits, security guarantees and advanced diagnostics](#appendix-d-limits-security-guarantees-and-advanced-diagnostics)
  - [Limits and defaults](#limits-and-defaults)
  - [Collector projection and redaction guarantees](#collector-projection-and-redaction-guarantees)
  - [Advanced diagnostics](#advanced-diagnostics)
## Overview and product model

ATT separates test intent from integration mechanics. Test data is versioned in workbook/sidecar/snapshot form; Templates and Flows define reusable behavior; Resources connect that behavior to external systems.

### Product model

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A **Testcase** is one authored, normalized workbook row. A **Case execution** is one Run execution of that Testcase; its full Case ID identifies the Testcase and is reused in execution results and evidence. Use *Testcase* when discussing workbook data and authoring, and *Case execution* when discussing runtime status, logs, reports, and artifacts. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper, MQHelper, HTTPHelper or SSHHelper used by Actions or permitted expression calls.

### RUN and LOAD execution models

RUN executes a functional target or Case selection once. Debug is an optional diagnostic mode; LOAD retains its performance engine. See [Unified RUN and schedules](reference/execution-modes/unified-run.md). Legacy adapters retain their specialized inputs and reports:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ/HTTP/SSH |
| Debug | `att-debug/v1.2` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.7` scenario | one or more Template, Flow or Tool workloads repeatedly |

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

Each Package contains its business resources, such as `testcase/`, `templates/`, package-local `tools/`, `testdata/`, and generated `output/`. All Packages use the shared runtime configuration under `ATT_HOME/config/`; trusted schemas are bundled with ATT. A Package does not need its own `config/` or `schemas/` directory. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

For a guided package build, use [Quick Start](quick-start.md). The rest of this manual is normative lookup documentation.

### How to use this manual

| Goal | Go to |
|---|---|
| Build the first ATT package | [Quick Start](quick-start.md) |
| Understand the core ATT model | [Product model](reference/overview.md), [Test Authoring](reference/test-authoring.md), [Actions](reference/actions.md), [Runtime and Context](reference/runtime-context.md), and [Run mode](reference/execution-modes/run.md) |
| Configure DB/MQ/HTTP/SSH | [Resources](reference/resources/overview.md) |
| Find a CLI option | [CLI Reference](reference/cli.md) |
| Diagnose a failure | [Validation and Troubleshooting](reference/validation-diagnostics.md) |
| Upgrade an older package | [Migration Notes](reference/appendices/migrations.md) |

Reference defines the public contract; README, Quick Start and examples explain that contract for narrower tasks. Each contract has one semantic owner; other Reference pages summarize and link to that owner.

## Test authoring

### Author and run a Testcase

A Testcase is one normalized workbook row; Run creates one Case execution from each selected row. Use this workflow to author, validate, and execute a change.

1. Edit the workbook and map its columns in the Sidecar.
2. Generate the Snapshot and review its diff.
3. Validate the package with ./att.sh validate --package.
4. Use [Debug](reference/execution-modes/debug.md) to isolate a Template, Flow, or Tool, then use [Run](reference/execution-modes/run.md) to create Case executions.

The following sections define each authoring contract. See [Advanced workbook and Snapshot details](#advanced-workbook-and-snapshot-details) for formula cells, multi-row headers, and XML serialization.

### Distinguish Testcases from Case executions

A **Testcase** is the authored workbook row after sidecar mapping and snapshot normalization. Its full Case ID is `workbookId.groupId.rowCaseId`. A **Case execution** is one runtime execution of that Testcase by Run, with its own status and evidence. The identifier is shared; the concepts are not synonyms. This page uses *Testcase* for workbook content and *Case execution* for runtime work.

### Authoring contracts

This page explains the normal day-to-day workflow in the same order that data moves through ATT.

| Need | Use |
|---|---|
| Stage execution entry point | Template |
| Reusable Template logic | Flow |
| One ordered operation | Action |
| External capability | Resource |
| Testcase/Stage business input | EXEC.INPUT |
| Cross-Action mutable state | EXEC.VARS |

### Workbook

#### Workbook, Sidecar, and Snapshot relationship

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

After editing Excel, run `./att.sh snapshot --suite testcase/payment_regression.xlsx`. The generated `payment_regression.xml` uses schema `att-testcases/v2.4` and stores only normalized sidecar-mapped semantics. It preserves group, Case, tag, map/list, and stage order, uses explicit value types, and excludes styles and unrelated workbook content. Review and commit the XML with the xlsx; do not edit it manually.

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

#### Workbook Sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override timeout, retry, tools, dbhelpers, template root, environment, or output root.

### Stage

Each sidecar stage has a dot-free `key` and a `template` field naming the physical Excel selector column. The selector cell may contain a symbolic template name, full relative template path, or YAML map:

| Cell value | Meaning |
|---|---|
| `PAYMENT_INVOKE` | Symbolic-name shorthand |
| `payment/local/CT001` | Full-path shorthand relative to `templates.root` |
| `name: PAYMENT_INVOKE` | Explicit symbolic-name map |
| `name: PAYMENT_INVOKE` plus other keys | Template selection plus stage-private row data |

ATT first resolves `name` as a globally unique symbolic name. Only when no symbolic name matches does it try a complete relative template path. Absolute paths, partial paths, and paths escaping `templates.root` are invalid.

All selector-map keys, including `name`, are copied into the stage Context. `stages[].dataColumns` adds more stage-private values. A duplicate key between the selector map and stage data columns is an error.


Stage `required`, `runWhen` and `onFailure` behavior is defined in [Reliability](reference/reliability-execution-control.md).

### Template

A directory is a callable Template only when it directly contains template.yaml. ATT uses att-template/v3.6. Each Template has a non-empty ordered actions map and a required description.

Each Action has a type-specific contract. A file-content expression such as `&{templates/payment/request.xml}` returns the exact UTF-8 file content as a String without creating a file. Tool/DB/HTTP/MQ/SSH actions publish the native typed operation result. Log formats typed values for human observation. Assign publishes values to EXEC.VARS, and Flow runs in a nested Action scope.

See [Actions and Typed Values](reference/actions.md) for the complete field list, examples, typed result/evidence model, HTTP/MQ/SSH boundaries and migration guidance. [Expressions and Built-ins](reference/expressions.md) covers the shared expression language; [Load](reference/execution-modes/load.md) owns ID initialization.

### Flow

A Flow is reusable Template logic, declared in `flow.yaml` using `att-flow/v3.6`. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. A Template invokes it through a Flow Action with `use: common.payment.v1`. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](reference/actions.md) owns Flow results and Assign behavior; [Context](reference/runtime-context.md) owns scope lifetime.

### Authoring lifecycle

After changing a Workbook, generate its Snapshot, review and commit the diff. After changing a Sidecar, Template, Flow or Resource, run `./att.sh validate --package`. Use [Debug](reference/execution-modes/debug.md) to isolate an authoring check and [Run](reference/execution-modes/run.md) to create Case executions from Testcases. Follow [Quick Start](quick-start.md) to build the first package.

### Test data ownership

Workbook/Sidecar/Snapshot defines Testcase data. Testcase and Stage business inputs enter `EXEC.INPUT`; [Context](reference/runtime-context.md) defines their scope and lifetime. Run turns each selected Testcase into a Case execution. Environment selection belongs to [Configuration](reference/configuration.md).

### Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them only from a Case, Stage, Debug `inputs`, or Load workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- Run Case/Stage mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.SOURCE` or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus `META.TEMPLATE`.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.SOURCE`, `META.TARGET`, or `META.TEMPLATE`. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

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

An environment profile's `testdata` list declares the shared registry. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every configured descriptor. Templates, Flows, and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](reference/configuration.md), [Load](reference/execution-modes/load.md), and the maintainer [testdata design](system-design/testdata.md).

### Advanced workbook and Snapshot details

#### Normalize XML text safely

String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA; literal `]]>` content is split across adjacent CDATA sections and reconstructs exactly when parsed. Spaces/tabs immediately before LF use `&#32;`/`&#9;` between CDATA sections, preserving the value without Git trailing-whitespace warnings. Review and commit the XML with the xlsx; do not edit it manually.

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

## Actions and typed values

This page defines the active ATT action contract. Templates use att-template/v3.6. Each completed action publishes its logical typed value at output.result. Actions do not use a shared result.format/path/overwrite object. For resource configuration, start with [Resources](reference/resources/overview.md).

### Action types

| Type | Required fields | Result and behavior |
|---|---|---|
| tool | call | Invokes a configured Tool, built-in or helper call and preserves the native typed result. DB query/scalar/update calls are ordinary Tool calls. |
| assert | assert | Evaluates a boolean condition and records PASS or FAIL. expected and actual are optional diagnostic values. |
| log | message or value | Formats a typed value for the Case log. Its fields are message, value and format. |
| assign | name and expression | Publishes the expression's typed result below EXEC.VARS. |
| flow | use | Runs a registered Flow in a nested Action scope and restores the caller's scope on return. |

Actions run in YAML order. Where supported, an action may also define id, description, onFailure and runWhen. Action IDs are unique within their scope. Type-specific invalid fields fail validation. Action result, Log file and Log fields are not part of the current action contract.

For the lowercase `x-` convention that disables ATT-owned Action fields and keyed entries before validation or execution—and how it differs from `runWhen: false`—see [Configuration](reference/configuration.md#ignore-or-disable-att-owned-configuration-with-x).

### Separate logical values from representations

ATT keeps the logical operation result separate from human or wire representations:

| Boundary | Field/value | Purpose |
|---|---|---|
| Command Tool stdout | stdoutFormat | Parses external stdout into a typed result. |
| HTTP/MQ response | responseFormat | Parses external response bytes into a typed result. |
| File-content expression | `String` | Reads one safe UTF-8 package file and preserves its exact characters after expression evaluation. |
| Abstract Map/List sent over HTTP/MQ | requestFormat | Serializes the value at the outbound boundary. |
| Log or resource evidence | format / evidence.output.format | Produces a human-readable representation. |

DB results are already typed values. Tool, Action, Template, Flow and expression results remain typed while they move through ATT.

DB query, scalar, and update operations use the first-class DBHelper call forms `db.<helper>.query(...)`, `db.<helper>.scalar(...)`, and `db.<helper>.update(...)` inside a normal `type: tool` Action. A DB call accepts one String `sql` argument plus either positional `params` or named `parameters`; `sql=&{package-relative-file.sql}` supplies package SQL content. The historical `type: db` Action is retained only by archived schema versions.

### File-content expressions return a `String`

The current replacement for the historical Render Action is the typed file-content value expression `&{path}`. It always returns one `String`; it never infers a document format, parses an extension, expands a glob, or creates an output file:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
~~~

`${...}` remains a Context reference and `#{...}` remains an expression/call. `&{...}` is a static, one-file locator; v1 has no glob or dynamic locator form. The locator is relative to the canonical ATT package root. A descriptor-relative `./` or `../` path is allowed only when its canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace and glob syntax fail validation.

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

requestFormat is for abstract structured values such as Map or List. Such a body requires an explicit format, for example requestFormat=json. Combining requestFormat with a `String` fails; a file-content result is never silently parsed and serialized. HTTP, MQ, and SSH Resource calls consume file content as String values and do not resolve local file-path arguments.

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

Collector results follow the normal typed-result rules. Evidence placement does not stringify a map, list or file-content `String`; helper `evidence.output` is an explicit presentation boundary. In Load, explicit collector execution is separate from helper `evidence.output` serialization. Resource-output formatting remains controlled by the Load evidence policy and is not silently substituted for or dropped in place of an author-requested collector.

### Log: typed value to case log

Log is a presentation action and therefore has its own format field:

~~~yaml
logOrder:
  type: log
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

Log is a plain Case-log entry. The current v3.6 contract removes `level`; delete it when migrating. Historical v3.4/v3.5 Template and Flow descriptors still accept their schema-defined Log level for compatibility. Internal diagnostic severity remains separate. At least one of message or value is required. message is rendered as text. value accepts any typed value, including nested maps/lists. Exact ${...} and #{...} expressions preserve their native types; map/list children are evaluated recursively without converting numbers, booleans, nulls or nested values to strings. format accepts text, json, yaml, xml or sqlplus and controls only the emitted Case-log string. When format is present, value is required.

When both message and value are supplied, Log emits the message, a newline, then the formatted value. output.result is that emitted string. A file-content String is emitted as-is when used as a value; Log does not infer or attach a document format. Log does not read a file and has no fields map. Put a typed map/list in value for structured log content.

### Expressions and variable scope

Action expressions use the regular ATT expression engine. A complete ${...} or #{...} expression keeps its result type; embedding an expression in surrounding text produces a String. See [Expressions and Built-ins](reference/expressions.md).

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
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and pre…50238 tokens truncated…ate --package
```

For one environment, use `./att.sh validate --config config/config.yaml --env SIT --package`. ATT validates descriptors against schemas in the matching ATT Engine JAR's embedded registry, listed in the [Schema and Version Matrix](reference/appendices/schema-matrix.md). The repository `schemas/` tree is build input; it and any old `ATT_HOME/schemas/` or package-local directory are not runtime schema sources. Superseded schemas under the embedded registry's `schemas/history/` are historical references, not runtime compatibility contracts unless that Engine version explicitly supports them. Update the declared `schemaVersion` and migrate fields to the active contract before validation. Diagnostics retain the original violation, file, and YAML field location and provide migration guidance; they never rewrite descriptors. For example, replace a historical Render Action with an Assign using `&{path}` and pass the resulting String as described in [Actions and Typed Values](reference/actions.md). Unsupported versions fail before execution.

Current schema sources and their historical counterparts are in the repository's `schemas/` build-input tree, which is not installed under ATT_HOME. At runtime, `validate --package` checks every schema registered in the embedded Engine catalog, even when the Package does not use it. A missing, unreadable, unsafe, or duplicate embedded resource is a hard `PACKAGE_INVALID` error. The Engine parses the catalog once per JVM and validates JSON Schema and JUnit XML directly from embedded classpath bytes. Compiled JSON validators use a bounded in-memory cache keyed by Engine registry identity and schema resource. The private SHA-256 disk cache is retained only as a verified bridge for Path-based compatibility APIs; it does not supply validation rules. Validation never rewrites YAML. Review the migration guidance, update the file, then rerun package validation for each selected `--env`.

Package validation also discovers existing Template, Flow and grouped or ungrouped Tool `debug.yaml` sidecars using the same discovery and schema/input validation as Debug execution. Historical schema versions and invalid current-schema structure are reported before invoking `att debug`; optional missing sidecars remain valid.

Then use the diagnostic code and structured location. Do not automate against message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing/stale snapshot, sidecar/sheet/header error, or duplicate Case ID | Check snapshot/basenames, sheet mapping, effective headers, and full IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Inspect requested/current/missing fields, nearest suggestion, or canonical candidates |
| `ATT-STG` | Blank required selector, invalid selector YAML, duplicate stage key | Check selector form, `name`, aliases, and required flag |
| `ATT-TPL` | Unknown/duplicate template, invalid action or payload | Check symbolic name/full path, descriptor, action type, and local files |
| `ATT-CFG` | Unknown field, duplicate key, wrong schema/type/enum | Compare with [Configuration and Environments](reference/configuration.md) and remove unsupported fields |
| `ATT-TOOL` | Unknown/missing argument, process or parse failure | Compare call contract; inspect exit code and bounded stdout/stderr capture evidence |
| `ATT-PATH` | Illegal ID or escaping path | Remove illegal characters and keep content below configured roots |
| `ATT-RUN` | Timeout, non-zero exit, render/runtime failure | Inspect case log and action/tool evidence |

### Common questions

#### Why is the Testcase ID rejected although Excel displays it correctly?

ATT imports displayed cell text, then applies strict ID safety checks. Check hidden leading/trailing whitespace, trailing `.`, path characters, controls, and Windows device names. Store identifiers as text to preserve leading zeroes.

#### Can two sheets contain the same row ID?

Yes. Give the sheets different group IDs. The rows then define different Testcases, such as `payment.payment.TC001` and `payment.batch.TC001`, and Run can create a separate Case execution for each.

#### Why did `N/A` become empty?

ATT normalizes `N/A`, `NA`, `NULL`, `NONE`, empty, and whitespace-only values to blank before data mapping and stage selection.

#### Why does a Context variable fail?

ATT treats an absent path as an authoring/runtime error instead of silently rendering it as empty. Follow `ATT-CTX-001` and its `requestedPath`, `currentNode`, `missingSegment`, and nearest suggestion to check the case-sensitive scope, physical header/alias, stage key, action ID, and availability point. A suffix shorthand must identify exactly one readable logical path; `ATT-CTX-002` lists every conflicting candidate so you can lengthen it. The diagnostic intentionally omits the full Context tree. A declared optional field with a blank value is still valid and renders as the empty string.

#### Why did a fail become error?

A false assertion makes the Case execution FAIL. Invalid expression syntax/navigation, Tool failure, timeout, parse failure, I/O failure, or runtime exception makes it ERROR. Inspect the Action evidence rather than only the final Run status.

#### Why did a Tool run more than once?

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
  "attVersion": "4.2.1",
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

## CI, packaging, and operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Markdown Reference Manual, builds the application jar and release archives, and smoke-tests the extracted local package. It requires Java/Maven and Python 3. It outputs `att-4.2.1-local.tar.gz` for the CLI, `att-4.2.1-server.tar.gz` for the WAR and deployment guides, and `att-4.2.1-src.tar.gz` for source. Each binary archive must be smaller than 25 MB. The local package no longer includes the WAR or its duplicate runtime libraries.

### Runtime dependencies

The CLI, Engine, and Worker remain Java 8-compatible. The ATT Server WAR requires Java 17+ and external Tomcat 10.1+. ATT does not bundle JDBC drivers for CLI integrations; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default CLI build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Studio inspection caches

Studio keeps Resource pages, Detail, safe Source, and ordinary Configuration projections in session memory. The shared cache retains at most 128 responses and 8 MiB of encoded payload, with a 1 MiB per-response admission limit. Entries remain fresh for 15 seconds; returning to an older view shows its cached projection while a background read refreshes it. In-flight reads are deduplicated, and aborting one consumer does not cancel another consumer's request. Search, sorting, pagination and scroll state remain available on view return.

Every reusable read first checks the authenticated inspection context. Identity, session, role or inspection-policy changes discard previous responses. Package/environment selection, explicit refresh, logout, page suspension, a newly observed package revision, and stale-cursor or access errors invalidate the affected memory. Nothing stores Configuration or Source content in localStorage, IndexedDB, a service worker, or an HTTP cache. Auth/CSRF responses and `includeAllFields` administrator responses bypass the query cache. `Cache-Control: no-store` remains enforced on HTTP responses.

Each inspection Worker still handles one request at a time and retains two Resource inspectors. Dispatch prefers an idle Worker already holding the same package/config/environment and policy key, then falls back to another idle Worker. Parsed Configuration snapshots have a separate four-entry LRU and a 32 MiB weighted retention budget per JVM; the configured Worker heap limit remains the hard JVM bound. Root configuration, selected Helper/Tool Group/Testdata dependencies, and the schema tree undergo bounded content reconciliation on reuse. Renames, deletions, same-timestamp replacements and unknown dependency changes therefore force a fresh load. Cache failures fall back to ordinary uncached validation. Every request constructs a fresh permission-checked projection; an administrator projection cannot enter the ordinary cache.

Optional `server.inspection.warmup` defaults to `enabled: false`, `workers: 1`, `keys: []`. `workers` must be from 1 through `server.inspection.maxConcurrent`. Each key contains a `packageId` and optional Engine `environment`; configure no more than twice the warmup Worker count, with no duplicates. Omit `environment` to warm Explorer's package-default index. Keys are distributed among Workers with at most two per Worker. With no keys, each selected Worker warms the first registered package. Missing packages and preload failures do not block Tomcat startup. Warmup skips interactive demand, retries each key at most three times with backoff, reloads after a daemon replacement, and stops at shutdown. Interactive traffic can cancel a preload when every Worker is occupied.

`GET /api/v1/metrics` follows the configured Server authentication policy and includes `inspection.server` and the latest bounded `inspection.workers` snapshots. Counters and total nanoseconds cover HTTP handling, queueing, process launch, dispatch, processing, Configuration load/parse, source requests, workbook/YAML parsing and index builds. They also report cache hits/misses/evictions, watcher invalidation/overflow counts, watched directories, retained keys, instrumented read bytes, protocol response bytes, heap-pool peaks, GC and process CPU. Up to 16 opaque package/environment aggregates retain request count, processing time, index builds, Configuration loads and instrumented read bytes per Worker. Package keys are hashed; counters never contain paths, configuration values or file contents. Byte counters cover instrumented inspection reads rather than OS-level physical I/O. Process-launch timing excludes JVM readiness; dispatch includes readiness and protocol transport. Benchmarks record sample p50/p95 separately. If optional inspection telemetry cannot fit within the configured response-byte budget, the Worker omits that snapshot and the Server clears its routing hint; metrics never relax the response limit or make an otherwise valid response exceed it.

Display snapshots never authorize execution. Debug/Quick Load approval and Worker execution retain authoritative package revision checks, `ATT-SERVER-DRAFT-STALE`, principal-bound cursors and stale-cursor HTTP `409`. See [Server deployment](server-deployment.md) for the configuration example and [inspection benchmarks](system-design/web-ui-inspection-inline-jobs.md) for measurement procedures.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.

## Appendix A — schema and version matrix

Active schemas (the matching ATT Engine JAR's embedded registry, built from `schemas/catalog.yaml`):

| Artifact | Active schema |
|---|---|
| Schedule creation | att-schedule/v2 |
| RUN definition | att-execution/v1 |
| Global configuration | att-config/v2.12 |
| Testdata descriptor | att-testdata/v1.0 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.3 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.2 |
| Load scenario | att-load/v1.7 |
| Load summary | att-load-summary/v1.1 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

For the changed resource/configuration schemas, older versions are historical definitions under `schemas/history/`; they are not active execution contracts unless the embedded Engine registry registers and supports them. Unsupported versions fail validation with migration guidance. The repository catalog at `schemas/catalog.yaml` is build input; at runtime, the registry embedded in the matching ATT Engine JAR is authoritative. The Engine parses its Classpath catalog once per JVM and reads schema bytes directly from the JAR. Compiled JSON validators are held in a bounded, in-memory cache keyed by registry digest and schema resource. A verified, content-addressed cache in a private per-user temporary directory remains only as a transitional bridge for Path-based compatibility APIs. It retains the active registry and the immediately previous version for rollback; older entries are deleted only when an exclusive OS lock confirms no Worker is using them, and interrupted cleanup is retried at the next initialization. An incomplete or tampered cache is rebuilt only when no process holds its use lease; otherwise validation fails closed and retries after the active Worker exits. Validation revisions bind Engine and registry identity, Package files, shared config files and selected Environment; Workers verify the registry identity supplied by the Server before handling requests. `ATT_HOME/schemas/` and package-local `schemas/` are not runtime sources and cannot override that registry. Package validation checks the Engine's registered schema resources; it does not enable compatibility for unregistered archived versions.

<!-- att-docs:historical -->
Load v1.6 and MQHelper v1.2 are compatible historical schemas supported by their loaders; their existing scalar routing and legacy evidence policies remain unchanged. New routing/quota fields require the active schema.
<!-- /att-docs:historical -->

## Appendix B — compatibility and deprecated aliases

Compatibility exists to read established packages without creating a second current model. New authoring uses canonical `EXEC`, `META`, Action-local `output`, current schema versions, `--env`, and current Tool/DB/MQ contracts.

Deterministic legacy aliases may remain readable with migration warnings. Aliases are not created where old semantics conflict with scope isolation or the common result/evidence contract. Deprecated CLI/authoring forms remain documented on their owning page or in CHANGELOG only when users still need a migration path.

## Appendix C — migration notes

### Independent Load evidence quotas

Use `att-load/v1.7` for finite Load evidence quotas. Legacy scenarios with `maxSamples`, or with the shared `maxSuccessSamples` / `maxFailureSamples` pair, keep their existing semantics. Per-workload caps require the explicit `evidence.quotaScope: perWorkload` discriminator. In that mode configure `evidence.defaults` and optional cap-only `workloads[].evidence` overrides; do not add root `maxSamples`, `maxSuccessSamples` or `maxFailureSamples`. Each workload's caps are independent and no capacity is lent, borrowed, reclaimed or used to delete retained artifacts. See [Load](reference/execution-modes/load.md#independent-evidence-quotas) for limits, validation and reporting.


### MQ request queue routing

`att-mqhelper/v1.2` scalar descriptors remain supported. To use `message.requestQueues` or `requestQueueSelection`, change the descriptor to `att-mqhelper/v1.3` and replace the scalar `requestQueue` with a 1–256-item unique queue list. Keep one optional scalar `replyQueue`. Instance routing overrides replace inherited scalar/list keys and reset the queue strategy unless explicitly configured. See [MQHelper](reference/resources/mqhelper.md#request-queue-routing) for queue selection, counter scope, evidence and replay behavior.


### File arguments and case-log paths

HTTPHelper calls no longer accept `file`; pass `&{package-relative-file}` directly as `body`. MQHelper `send` and `request` no longer accept `file`; pass the expression as `payload`. SSHHelper `upload` now requires content in `payload` and rejects `localPath`; pass `&{...}` directly. SSHHelper no longer supports `download`, because it requires a local destination path. Use a deliberately configured command-backed Tool for workflows that must retrieve files from a host. These changes remove native arbitrary-binary local-file input from these Resource APIs; `&{...}` supplies UTF-8 text.

Case logs, CLI output, and emitted case evidence display paths under the canonical package root as `$ATT_HOME` or `$ATT_HOME/<relative-path>`, with `/` separators. `$ATT_HOME` is a presentation token, not an environment variable, Context root, or file-expression locator. Runtime resolution and filesystem access continue to use canonical absolute Paths. Absolute paths outside the package root use a bounded `$EXTERNAL/<basename>` presentation when logged as Path values or embedded in diagnostic messages. Explicit remote-path fields and URLs retain their values.

### Previous release Testdata migration

Change global configuration from `att-config/v2.10` to `att-config/v2.11` and Load scenarios from `att-load/v1.4` to `att-load/v1.5`. The previous schemas remain catalogued under `schemas/history/` for migration diagnostics. `att-testdata/v1.0` is new: add descriptor paths to the selected environment profile's `testdata` list, then use `@{id}` references in Case/Stage, Debug, or Load workload input maps. Load scenarios can add package-relative top-level `testdata` paths as a Load-only overlay. Repeated logical IDs across layers mean a whole descriptor replacement; duplicate IDs inside one layer are invalid. Add an explicit selection policy for every descriptor containing multiple records. Existing packages without testdata references need no new descriptor files.

### Execution identity format migration

`att-config/v2.12` adds execution identity formats; the immediately preceding configuration schema remains supported. To configure outer Run and Load IDs, add `execution.runIdFormat`. To configure standalone Debug directory IDs, add `execution.debugIdFormat`. Existing timestamp Run/Load IDs and `<type>-<targetId>` Debug IDs remain the defaults when these fields are absent. `execution.execIdFormat` remains the Load per-iteration `EXEC.ID` policy. Exact Run/Load IDs use `--run-id`; exact standalone Debug IDs use `--debug-id`.

ATT 3.7.2 introduces `att-load/v1.6`. Existing v1.5 descriptors remain compatible and are normalized at load time. To use a closed-user target mix, change the schema version to v1.6 and replace that workload's `target` with a `mix` list of uniquely named entries, each with a positive integer `weight` and its own target. Mix entries are prevalidated before scheduling; only closed workloads support mixes. Load summaries now use `att-load-summary/v1.1` and include per-mix selection and metric data when applicable. The previous Load and summary schemas remain available as historical definitions.

Load workload `testdata.<id>` settings control `scope` and optionally replace the whole descriptor `selection` policy. Scope defaults to `iteration`; `user` is valid only for closed-VU workloads. Choose `error`, `recycle`, or `stop` exhaustion deliberately. Selection metadata is recorded without record values.

ATT 3.6.2 separates typed operation results, external parsing, file-content Strings, outbound transport and human-readable evidence.

| Previous field/model | 3.6.2 migration |
|---|---|
| `att-template/v3.4` or `att-flow/v3.4` with `type: render` | Change the descriptor to the active v3.5 schema and replace each Render Action with an Assign that uses a file-content expression. Historical v3.4 descriptors remain loadable only through the historical schema path. |
| `type: render` / `payload: path` | Use `type: assign`, a variable `name`, and `expression: "&{package-relative-file}"`; pass `${EXEC.VARS.<name>}` to the consumer. |
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Remove the old format/persistence fields. The file-content expression returns the exact UTF-8 String and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the file-content String directly as HTTP body, MQ payload, or SSH upload payload. |
| requestFormat on a file-content String | Remove it. requestFormat is only for abstract Map/List values; String + requestFormat fails. |
| Dynamic or unsafe file locator | Replace it with one static package-relative file. Absolute paths, globs, dynamic locators, missing files, directories, non-UTF-8 bytes and symlink escapes are rejected. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Use the active schema from [Schema and Version Matrix](reference/appendices/schema-matrix.md) and migrate the listed fields. Historical schemas are not active contracts. |

A file-content String passed to HTTP:

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

The historical `att-load-profile/v1.0` policy file is migration-only: rewrite it as the current policy-only `att-load/v1.6` descriptor before use. It is not a current `load/load.yaml` example.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](reference/actions.md), [Runtime and Context Model](reference/runtime-context.md), [Load Mode](reference/execution-modes/load.md) and [Schema Matrix](reference/appendices/schema-matrix.md).

### Historical schema migration

ATT 3.6.2 uses `att-template/v3.6` and `att-flow/v3.6` as the active schemas. The published `att-template/v3.5`, `att-flow/v3.5`, and older definitions remain under `schemas/history/`; their historical DB and Render Actions are compatibility-only and are not part of the active contract. When migrating those descriptors, change their schema versions to v3.6 and apply the migration table's field changes.

| Historical configuration | 3.6.2 form |
|---|---|
| `att-template/v3.3` or `att-flow/v3.3` | Follow the historical release migration to v3.4, then change to v3.6 and migrate the Render/DB Actions. |
| Historical `type: db` with `query` or `update` | Use an ordinary `type: tool` Action with `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)`; query/scalar may retry, update must not use automatic retry. |
| Historical `sqlFile` | Use the single String argument `sql=&{package-relative-sql-file}`. `params` and `parameters` remain mutually exclusive. |
| Historical `type: render` | Replace it with an Assign whose expression is `"&{package-relative-file}"`; use `${EXEC.VARS.<name>}` in later Actions. |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | Remove the old persistence fields. The file-content expression returns the exact UTF-8 String and creates no implicit result file. |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ/SSH | Pass the file-content String directly as HTTP body, MQ payload, or SSH upload payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

File-content paths are relative to the canonical package root. `./` and `../` are allowed only when the canonical target remains inside that root. The v1 contract has no globs or dynamic locators; the target must be a regular strict-UTF-8 file.

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](reference/runtime-context.md) for META lifecycle and [Load Mode](reference/execution-modes/load.md) for execution identity and retained evidence paths.

### Debug schema migration

`att-debug/v1.0` and `att-debug/v1.1` are historical; upgrade to `att-debug/v1.2`. Template/Flow may define `vars` to seed `EXEC.VARS`; Tool targets do not support `vars`. The current schema also permits package-relative `testdata` imports scoped to a standalone Debug invocation. See [Debug](reference/execution-modes/debug.md) for current input and argument rules.

### Global configuration migration

Old `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` fields are not part of the active global contract. See [Configuration](reference/configuration.md) for current fields.

### Environment profile migration

When migrating complete-config packages, preserve descriptors and Actions, move common settings to `config/config.yaml`, move descriptor lists to `environments.<NAME>`, and select with `--config config/config.yaml --env <NAME>`. See [Configuration](reference/configuration.md) for the current contract.

## Appendix D — limits, security guarantees and advanced diagnostics

### Limits and defaults

Use the owning schema or configuration page for normative field defaults. Important architectural limits include:

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

#### Why does `att.bat` ask for Maven, or why does a `.sh` Tool fail on windows?

In a binary release, `att.bat` finds the versioned runtime JAR, such as `lib\att-local-4.2.1.jar`, and only requires Java 8+. Keep one `att-local-<version>.jar` in each `ATT_HOME/lib` so the launcher selects an unambiguous runtime. In a source tree it compiles with Maven when Maven is on `PATH`; without Maven, previously compiled `target\classes` must exist. Use `att.bat version` to confirm the launcher before validating the package.

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
