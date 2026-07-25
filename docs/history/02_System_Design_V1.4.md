# ATT System Design V1.4

**Version:** V1.4
**Status:** Deprecated
**Source:** V1.4 roadmap and user review comments
**Last Updated:** 2026-07-10

---

# 1. System Overview

ATT, short for Automated Testing Tool, provides a lightweight automation layer for API SIT/UAT testing.

V1.4 is a stabilization release on top of the V1.3 execution model.

The core runtime model remains:

- Excel workbooks drive case execution.
- Each workbook must have a sidecar YAML file beside it.
- The sidecar defines how the workbook is parsed and executed.
- Test cases are organized into ordered stages.
- Stages select reusable templates.
- Templates contain ordered actions.
- Actions invoke tools or render data.
- `${...}` references runtime context values.
- `#{...}` invokes tools.
- Tool execution remains external and shell-script based.

V1.4 tightens the configuration boundary and workbook parsing rules:

- `config.yaml` configures global behavior only, such as tools, environment, log defaults, and shared execution defaults.
- `config.yaml` does not define workbook-specific testcase columns, stages, or report column mappings.
- Workbook-specific testcase parsing, stage parsing, and report mapping live in the workbook sidecar.
- Every Excel workbook must have a sidecar config with an explicit Test Case Template binding.
- V1.4 supports Chinese and mixed-language workbook names, sheet names, and column headers.
- `testcase.columns` mapping must accept Chinese source headers and Chinese display names without assuming English field names.

Relationship model:

```text
testcase <1:n stage 1:m> template <1:n action m:1> tool
```

---

# 2. Design Goals

## 2.1 Make Workbook Configuration Self-Contained

Each workbook should be understandable by reading the workbook and its sidecar config.

The sidecar config owns:

- selected Test Case Template
- sheet name
- header row count
- testcase column mapping
- stage column mapping
- report column mapping
- suite-level execution defaults
- suite log level

## 2.2 Support Localized Workbook Naming

V1.4 must support Chinese or mixed-language workbook metadata.

Supported at the workbook boundary:

- workbook file name
- worksheet name
- header labels
- display names in sidecar mappings
- template names shown to users

The framework should keep stable logical keys internally while accepting localized display labels at the workbook boundary.

## 2.3 Reduce Authoring Mistakes

Excel files often contain spaces or formatting artifacts that humans do not notice.

V1.4 should normalize necessary whitespace for matching and identifiers, while preserving intentional business text.

## 2.4 Keep Template Cells Structured

Stage template cells are YAML values.

This supports:

- Chinese template names
- remarks
- comments
- extra metadata
- multi-line text

## 2.5 Clarify Context Composition

All case-related values are exposed through the runtime context.

Ordinary testcase columns, testcase YAML columns, stage YAML columns, template cell metadata, action outputs, and tool outputs are all context data sources.

Duplicate keys are allowed, but overwrite behavior must be explicit and should warn.

## 2.6 Preserve Compatibility Where It Helps

V1.4 keeps the `ACTIONS.<ActionID>` model and retains `TOOLS.<ActionID>` as a legacy alias.

V1.4 also keeps the V1.3 output layout so existing run history remains recognizable.

---

# 3. Configuration Boundary

## 3.1 Global Config

`config.yaml` should configure:

- tools
- environment information
- global log defaults
- common execution defaults
- template search paths

`config.yaml` should not configure:

- testcase columns
- stage lists
- report columns
- workbook-specific template selection

## 3.2 Workbook Sidecar

Every Excel workbook must have a sidecar YAML file beside it.

Example:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
```

The sidecar is required.

The sidecar must explicitly declare:

- `testCaseTemplate`
- `testcase`
- `stages`
- `report`

Optional sidecar settings may include:

- `headerRows`
- suite log level
- execution defaults
- stage-specific parsing settings

## 3.3 Sidecar Ownership Rules

The sidecar owns workbook-specific concerns:

- testcase column mapping
- stage column mapping
- report column mapping
- workbook-level template binding
- suite-level log level
- suite-level execution defaults

This avoids a hidden dependency on global defaults and makes workbook behavior easier to reason about.

---

# 4. Workbook Parsing Model

## 4.1 Workbook Identity

The workbook file name identifies the suite.

The sidecar file name must match the workbook base name.

Example:

- `payment_regression.xlsx`
- `payment_regression.yaml`

## 4.2 Header Resolution

V1.4 supports one-row and multi-row headers.

`testcase.headerRows` controls how many top rows are treated as header rows.

Header resolution rules:

- empty cells are ignored
- invisible surrounding spaces are trimmed
- the effective column name is resolved from the last non-empty cell in the header column
- the resolved header text is matched against configured mappings exactly after normalization

## 4.3 Column Mapping Model

`testcase.columns` maps workbook headers into stable logical field keys.

The mapping must support:

- English headers
- Chinese headers
- mixed-language headers
- explicit aliases such as `creditAcNo=入帳帳號`

Examples:

```yaml
testcase:
  columns:
    caseId: 案例編號
    caseName: 案例名稱
    tags: 標籤
    amount: 金額
    creditAccount: creditAcNo=入帳帳號
```

Rules:

- the right-hand side is the workbook header label
- the left-hand side is the internal logical field key
- aliases are optional
- mapping is exact after whitespace normalization
- untranslated assumptions are not allowed

## 4.4 YAML Columns

Some workbook columns contain YAML text.

These should be configured separately from ordinary columns when they need to be parsed as structured data.

Example:

```yaml
testcase:
  yamlColumns:
    data: 案例資料
  stages:
    invoke:
      yamlColumns:
        request: 調用資料
```

Rules:

- YAML column parsing is explicit
- YAML columns are still workbook-bound, not global defaults
- parsed YAML values become context data

---

# 5. Test Case Template Model

## 5.1 Workbook-Level Template Binding

The sidecar selects one Test Case Template for the workbook.

That template defines the shared stage flow for all cases in the workbook.

V1.4 does not support selecting different Test Case Templates per case row by default.

## 5.2 Template Purpose

A Test Case Template is a reusable workbook model.

It defines:

- default testcase column semantics
- stage list
- stage-to-template binding
- report defaults
- execution defaults

## 5.3 Template Resolution

By default, a template resolves from the configured template root:

```text
templates/testcase/<templateId>/template.yaml
```

Stage templates resolve from the configured stage template root:

```text
templates/<template>/template.yaml
```

Legacy fallback paths may be retained only for compatibility and must be documented explicitly.

## 5.4 Template Naming

Template display names may be Chinese or mixed-language.

Template identifiers should remain stable and machine-friendly.

Use display names for humans, and use stable IDs for resolution.

---

# 6. Stage Model

## 6.1 Stage Ownership

Stages belong to the workbook-selected Test Case Template.

All cases in the workbook share the same stage sequence unless the template explicitly defines a different template layout.

## 6.2 Stage Definition

A stage definition should include:

- key
- display name
- template name or template path
- required flag
- failure behavior
- optional execution condition

Example:

```yaml
stages:
  - key: prepare
    name: 準備
    template: PAYMENT_PREPARE
    required: false
    onFailure: stop
  - key: invoke
    name: 調用
    template: PAYMENT_INVOKE
    required: true
    onFailure: stop
  - key: verify
    name: 驗證
    template: PAYMENT_VERIFY
    required: true
    onFailure: stop
```

## 6.3 Stage Parsing Settings

Stage-specific YAML columns and template references are workbook-side concerns.

They should not be repeated as generic global config values.

If a stage uses a custom template path, that path takes precedence over template-name resolution.

---

# 7. Template And Action Model

## 7.1 Template Structure

A template directory contains `template.yaml` and optional payload or helper files.

The directory must be recognized only when `template.yaml` exists.

## 7.2 Ordered Actions

Templates contain ordered actions.

V1.4 keeps the ordered-action model from V1.3.

Each action may:

- render payload content
- call a tool
- assert a condition
- log values

## 7.3 Action ID

Each action must have a stable Action ID.

Preferred context paths:

```text
${ACTIONS.<ActionID>.output}
${ACTIONS.<ActionID>.outputFile}
${ACTIONS.<ActionID>.rawOutput}
```

`TOOLS.<ActionID>` may remain as a compatibility alias.

## 7.4 File-Based Outputs

Large action outputs may be stored as files under:

```text
output/<RunID>/<CaseID>/<ActionID>/
```

This is the preferred way to handle long XML, JSON, SQL, and log outputs.

---

# 8. Context Model

## 8.1 Context Sources

The runtime context is the source of truth for template rendering and tool invocation.

Context may include:

- testcase column values
- testcase YAML column values
- stage YAML column values
- template cell metadata
- action outputs
- tool outputs
- run metadata

## 8.2 Lookup Rule

Expressions should resolve data through a defined context lookup order.

The lookup order must be stable and documented.

Where duplicate keys appear, the framework should either:

- preserve the first value and warn on overwrite, or
- preserve the latest value and warn on overwrite

The chosen rule should be consistent across all context sources.

## 8.3 Encoding And Trimming

V1.4 should tolerate Unicode text and whitespace around workbook values.

Normalization should be conservative:

- trim values used for matching
- preserve meaningful business text
- do not silently alter payload content

---

# 9. Report And Output Model

## 9.1 Result Workbook

The result workbook remains run-level.

It should be copied from the source workbook and appended with configured result columns.

## 9.2 Run History

Run history remains machine-readable under the run directory.

Typical artifacts:

- `run.yaml`
- `output/latest-run.yaml`
- per-case case logs
- copied result workbook

## 9.3 Report Columns

Report columns are configured in the workbook sidecar.

The report mapping can be localized, just like workbook headers.

Examples:

```yaml
report:
  columns:
    result: 測試結果
    durationMs: 耗時(ms)
    actualResult: 實際結果
    caseLog: 案例日誌
    runTime: 執行時間
```

---

# 10. Execution Flow

## 10.1 High-Level Flow

```text
Load workbook
  -> Load matching sidecar YAML
  -> Resolve workbook headers and testcase mappings
  -> Resolve Test Case Template
  -> Resolve stages and stage templates
  -> Build runtime context
  -> Execute ordered actions
  -> Record outputs and logs
  -> Write result workbook and run history
```

## 10.2 Failure Handling

If a required sidecar file is missing, execution should fail early.

If a required template cannot be resolved, execution should fail with a clear error.

If a required stage or action is invalid, the framework should stop before running partial business logic.

---

# 11. Migration From V1.3

V1.4 is intended to be a small step from V1.3, not a redesign.

Migration priorities:

1. Add a sidecar YAML file beside every Excel workbook.
2. Move workbook-specific testcase columns, stage mappings, and report mappings into the sidecar.
3. Declare the Test Case Template explicitly in the sidecar.
4. Use `ACTIONS.<ActionID>` for new templates.
5. Keep `TOOLS.<ActionID>` only as a migration alias.
6. Support Chinese workbook labels and column names through explicit mappings.
7. Keep the existing output layout and run history structure.

---

# 12. V1.4 Completion Criteria

V1.4 is considered complete when the following are true:

- Every Excel workbook has a sidecar config.
- Every sidecar explicitly declares a Test Case Template.
- Workbook-specific testcase and report mapping no longer depends on `config.yaml`.
- Chinese and mixed-language workbook labels are supported at the workbook boundary.
- `testcase.columns` supports explicit header mapping with Chinese labels.
- `ACTIONS.<ActionID>` is the preferred context reference.
- `TOOLS.<ActionID>` remains documented only as a compatibility alias.
- Output layout and run history remain stable.
