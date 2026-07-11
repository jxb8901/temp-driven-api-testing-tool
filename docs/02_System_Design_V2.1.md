# ATT System Design V2.1

**Product:** ATT — Automated Testing Tool  
**Version:** 2.1  
**Status:** Proposed normative design  
**Design baseline:** V2.0  
**Audience:** product owners, framework developers, test architects, DevOps engineers, and SIT/UAT users

## 1. Purpose

ATT V2.1 is a hardening release of the template-driven API testing framework. It retains the V2 execution model while making validation, result classification, filesystem handling, release packaging, diagnostics, environment protection, evidence persistence, and CI integration production-grade.

The core model remains:

```text
test case --1:n ordered stages--> template --1:n ordered actions--> tool
```

V2.1 does not become a general workflow engine. Retry belongs to action/tool execution policy; it does not introduce arbitrary graph loops, stage jumps, or workflow-level repetition.

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are normative.

## 2. V2.1 goals

V2.1 MUST:

1. prevent run, case, report, template, action-output, and archive path traversal;
2. use one authoritative version across Java, Maven, scripts, archives, manifests, and reports;
3. aggregate PASS, FAIL, ERROR, SKIPPED, and INVALID consistently;
4. define one clean contract shared by code, tests, and documentation;
5. provide package-wide and selected-dependency validation modes;
6. reject unknown and structurally invalid configuration fields;
7. validate every action according to its type before execution;
8. support JSON output and lossless-enough XML mapping for repeated elements and attributes;
9. persist versioned, reproducible run evidence and input hashes;
10. write runs through an in-progress state and atomically publish completion;
11. produce structured diagnostics and CI-native output;
12. support bounded retry for eligible tool failures, excluding timeouts; and
13. require a fully green release verification gate.

## 3. Non-goals

V2.1 does not add:

- distributed execution;
- arbitrary DAG workflows;
- stage-level loops or jumps;
- a web UI;
- `doctor` and environment allowlist/confirmation policy;
- implicit configuration compatibility aliases;
- silent coercion of unknown fields; or
- timeout retry, stage-level retry, or assertion retry.

## 4. Architectural principles

### 4.1 Explicit ownership

- Global configuration owns runtime defaults, environment, template root, report defaults, and tool contracts.
- A workbook sidecar owns workbook parsing, ordered stages, and workbook-specific reporting settings.
- A stage selects one template and contributes stage-private data.
- A template owns ordered actions.
- An action performs render, tool, assert, or log behavior.
- A tool is a reusable external process contract.

### 4.2 Validate before mutation

ATT MUST resolve selection, load configuration, validate the required scope, and build an execution plan before it creates a run directory or invokes a tool.

### 4.3 Validated identifiers map directly to directories

Run IDs and full Case IDs are both user-visible identifiers and output-directory names. ATT MUST validate them against the V2.1 identifier rules before using them in a path. After validation, ATT uses the unchanged Run ID and full Case ID directly, so a user can identify a run or case without consulting a slug/hash mapping.

Validation is the primary rule; canonical `normalize` and root-containment checks remain mandatory defense in depth. Template names, action IDs, report selectors, and other untrusted values MUST also be validated before they affect a filesystem path or URL.

### 4.4 One source of truth

Status aggregation, diagnostic codes, configuration schemas, version metadata, and generated-output directory definitions MUST each have one authoritative implementation reused by CLI, reports, tests, and documentation.

### 4.5 Evidence is versioned and reproducible

Every completed run MUST record sufficient version and hash metadata to determine which framework, configuration, workbook, sidecar, template, and tool definitions produced it.

## 5. Implementation continuity

V2.1 retains the existing Java package and execution model: `FrameworkRunner`, `FrameworkEngine`, `PackageValidator`, `StageTemplateLoader`, `StageTemplateRunner`, `ToolInvoker`, report generators, and cleaners remain the primary implementation boundaries. This design does not require a new component model, workflow model, or a wholesale class reorganization.

The implementation MAY introduce focused helpers for shared concerns such as status aggregation, schema validation, diagnostics, manifest writing, and path checks. Such helpers MUST preserve the existing Case → Stage → Template → Action → Tool ownership model.

## 6. Authoritative version and release packaging

### 6.1 Version source

The Maven project version is the authoritative ATT version. Release builds MUST use a non-SNAPSHOT value such as `2.1.0`.

The build MUST generate a resource containing:

```properties
att.version=2.1.0
att.buildTime=2026-07-11T00:00:00Z
att.gitCommit=<commit-or-unknown>
```

The existing runtime startup path reads this generated resource. The following MUST use the same value:

- `att version` output;
- application JAR filename;
- release directory and archive names;
- `run.yaml` metadata;
- HTML and CI reports;
- package manifest; and
- generated documentation.

Build scripts MUST NOT independently hard-code `v2.1` or another version string.

### 6.2 Build script location

`build.sh` is package-root relative and MUST determine the project root as its own directory:

```sh
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
```

The script MUST fail with a clear diagnostic if required paths such as `pom.xml`, `src/main/java`, `config`, or `att.sh` are missing. Optional directories MUST be copied only when present.

### 6.3 Release output

Release artifacts are written under:

```text
dist/releases/att-<version>.tar.gz
dist/releases/att-<version>-src.tar.gz
```

End-user run evidence archives created by `att.sh build` are written separately under:

```text
build/att-run-<runId>.tar.gz
```

This keeps end-user run archives under the `att.sh clean` ownership boundary. `dist/` remains exclusively owned by the development/release `build.sh` flow.

Release packaging MUST run the release gate in Section 24 before publishing an archive.

### 6.4 Release size policy

The binary release MUST package runtime dependencies only. V2.1 removes `log4j-core` because ATT does not use a Log4j implementation directly and Apache POI requires only `log4j-api`; this saves approximately 1.8 MiB before archive compression. The remaining dominant dependencies (`poi`, `poi-ooxml`, `poi-ooxml-lite`, `xmlbeans`, Commons IO/Compress/Collections/Math, and `curvesapi`) form the supported XLSX runtime and MUST NOT be replaced by partial in-house OOXML handling in V2.1. Jackson (approximately 2.2 MiB including core and annotations) could technically be replaced by an internal strict JSON parser, but doing so would duplicate mature Unicode, number, duplicate-key, and escaping behavior; this is not accepted for V2.1. SnakeYAML has the same maintainability and security concern. Future slimming MUST demonstrate a green release gate against representative XLSX/JSON/YAML fixtures and MUST measure both installed and compressed size.

## 7. Strict configuration schemas

### 7.1 General rules

V2.1 defines schemas for:

- global `config.yaml`;
- workbook sidecars;
- `template.yaml`;
- each action type;
- tool and tool-argument descriptors;
- persisted `run.yaml`; and
- CI JSON summary.

Schemas MUST be version-controlled in `schemas/`. Every schema-controlled mapping MUST reject unknown fields by default. Extension fields MAY be supported only through names beginning with `x-`; ATT preserves but does not interpret them.

Duplicate YAML keys, unsafe YAML tags, incorrect scalar types, unknown enum values, missing required fields, and unknown non-extension fields are validation errors.

Diagnostics MUST use the source field path, for example:

```text
tools.invokePaymentApi.arguments.requestFile.required
stages[2].runWhen
actions.assertStatus.expression
```

### 7.2 Global configuration schema

Supported top-level fields are:

```yaml
schemaVersion: att-config/v2.1
outputDirectory: output
environment: SIT
timeoutMs: 120000
templates:
  root: templates
run:
  id:
    default: timestamp
    timestampFormat: yyyyMMdd-HHmmss
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns:
    result: 測試結果
    reportLink: 詳細報告
  junit:
    caseLogEmbedThresholdBytes: 10240
xml:
  namespaceMode: ignore
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered request file
        required: true
```

`schemaVersion` is mandatory. The complete schema is:

| Field | Type | Required | Rule |
|---|---|---:|---|
| schemaVersion | string | Yes | exactly `att-config/v2.1` |
| outputDirectory | string | No | relative package path; default `output` |
| environment | string | No | non-blank; default `SIT` |
| timeoutMs | integer | No | 1–86400000; default 120000 |
| templates | map | No | only `root` is allowed |
| templates.root | string | No | relative package path; default `templates` |
| run | map | No | only `id` is allowed |
| run.id | map | No | only `default`, `timestampFormat` are allowed |
| run.id.default | string | No | exactly `timestamp`; default `timestamp` |
| run.id.timestampFormat | string | No | valid Java time pattern; default `yyyyMMdd-HHmmss` |
| report | map | No | only `mode`, `fileNamePattern`, `columns`, `junit` are allowed |
| report.mode | string | No | exactly `append-to-copy` |
| report.fileNamePattern | string | No | must contain `${suiteName}` |
| report.columns | map of string | No | recognized result-column keys only |
| report.junit | map | No | only `caseLogEmbedThresholdBytes` is allowed |
| report.junit.caseLogEmbedThresholdBytes | integer | No | 0–1048576 bytes; default 10240 |
| xml | map | No | only `namespaceMode` is allowed |
| xml.namespaceMode | string | No | `ignore` or `preserve`; default `ignore` |
| tools | map | No | tool key → tool schema below |

A tool descriptor permits only `name`, `description`, `command`, `output`, `arguments`, and `x-*`. `name`, `description`, and `command` are non-blank strings. `output` is `txt`, `yaml`, `json`, or `xml`, defaulting to `txt`. `arguments` is an ordered map; each argument permits only `name`, `description`, `required`, `delimit`, and `x-*`. `name`, `description`, and boolean `required` are mandatory; `delimit` is a non-blank string permitted only on the last declared argument.

`reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are outside V2.1 scope and are rejected as unknown fields.

### 7.3 Sidecar schema

Supported top-level fields are:

```yaml
schemaVersion: att-sidecar/v2.1
excel:
  sheet: payment=支付測試案例集
  headerRows: 1
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道
    required: true
    runWhen: normal
    onFailure: stop
report:
  columns:
    result: 測試結果
timeoutMs: 120000
```

The complete sidecar schema is:

| Field | Type | Required | Rule |
|---|---|---:|---|
| schemaVersion | string | Yes | exactly `att-sidecar/v2.1` |
| excel | map | Yes | fields defined below only |
| excel.sheet | string | Yes | one sheet or comma-separated `groupId=sheetName` entries |
| excel.headerRows | integer | No | at least 1; default 1 |
| excel.caseId | string | Yes | physical Excel header |
| excel.tags | string | Yes | physical Excel header |
| excel.dataColumns | string | No | V2 column-spec grammar |
| stages | ordered list | Yes | one or more stage maps |
| stages[].key | string | Yes | unique dot-free identifier |
| stages[].template | string | Yes | physical selector-column header |
| stages[].dataColumns | string | No | V2 column-spec grammar |
| stages[].required | boolean | No | default false |
| stages[].runWhen | string | No | `normal`, `onSuccess`, `onFailure`, `always`; default `normal` |
| stages[].onFailure | string | No | `stop` or `continue`; default `stop` |
| report | map | No | only `columns` is allowed |
| report.columns | map of string | No | recognized result-column keys only |
| timeoutMs | integer | No | 1–86400000; overrides global value |

The sidecar MUST NOT override global tools, template root, environment, or output roots. Unknown fields in `excel`, each stage, and `report` are errors.

### 7.4 Template schema

```yaml
schemaVersion: att-template/v2.1
name: PAYMENT_INVOKE
description: Invoke payment API
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    saveAs: request.json
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
```

The template mapping permits only `schemaVersion`, `name`, `description`, `actions`, and `x-*`. `schemaVersion`, `description`, and a non-empty ordered `actions` map are mandatory. `name` MAY be omitted only for path-only templates. Action keys are unique, non-blank, dot-free identifiers. Each action map must satisfy its type-specific schema in Section 8; unknown action fields are errors.

## 8. Type-specific action validation

Every action has common fields:

| Field | Required | Rule |
|---|---:|---|
| type | Yes | `render`, `tool`, `assert`, or `log` |
| onFailure | No | `stop` or `continue`; default `stop` |
| retry | No | Valid only where Section 18 permits it |
| description | No | Human-readable purpose |

Unknown fields are errors unless prefixed with `x-`.

### 8.1 Render action

Allowed fields:

```yaml
renderRequest:
  type: render
  payload: request.tmp.xml
  saveAs: request.xml
  output:
    mode: file
  onFailure: stop
```

Rules:

- `payload` is mandatory and MUST identify a regular file below the template directory.
- `saveAs` is required when `output.mode` is `file`.
- `saveAs` MUST be a safe relative filename/path below the action output directory.
- `retry` is not allowed because render is deterministic local processing.
- `call`, `expression`, `message`, `level`, and `fields` are forbidden.

### 8.2 Tool action

```yaml
invokeApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    retryOn: [EXIT_CODE]
```

Rules:

- `call` is mandatory and MUST contain exactly one configured external tool call.
- Built-in functions are not valid as the primary call of a tool action.
- Tool name and all argument names MUST resolve during validation.
- Required arguments MUST be present syntactically; non-blank runtime validation still occurs before invocation.
- `timeoutMs` is optional, is valid only for a tool action, and overrides the resolved global/sidecar `timeoutMs` for that action. Its range is 1–86400000.
- `payload`, `saveAs`, `expression`, `message`, `level`, and `fields` are forbidden.
- Retry follows Section 18; timeout is never retried.

### 8.3 Assert action

```yaml
assertStatus:
  type: assert
  expression: "${ACTIONS.invokeApi.output.status} == 'SUCCESS'"
```

Rules:

- `expression` is mandatory and non-blank.
- Expression syntax MUST be parsed during validation.
- Context references are syntax-checked; references depending on runtime data are not required to resolve during validation.
- `retry` is forbidden. Polling/eventual assertions require an explicit polling tool action.
- `payload`, `saveAs`, `call`, `message`, `level`, and `fields` are forbidden.

### 8.4 Log action

```yaml
recordEvidence:
  type: log
  level: INFO
  message: "Payment response recorded"
  fields:
    status: "${ACTIONS.invokeApi.output.status}"
```

Rules:

- `message` is mandatory and non-blank.
- `level` is one of `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`.
- `fields` is a string-keyed mapping.
- `retry`, `payload`, `saveAs`, `call`, and `expression` are forbidden.

## 9. Validation architecture

### 9.1 Modes

The command is:

```sh
./att.sh validate [--package|--selected] <selection options>
```

`--package` is the default.

**Package mode** validates the complete ATT package regardless of testcase references:

- global configuration and environment policy;
- every workbook and mandatory sidecar under the selected package/suite roots;
- every configured sheet and row;
- every template below `templates.root`, including unreferenced templates;
- symbolic-name and path uniqueness;
- every action and expression;
- every tool descriptor, command executable reference, and argument contract;
- cross-references between cases, templates, actions, and tools;
- schemas, paths, permissions required for validation, and package integrity.

**Selected mode** validates only the dependency closure required by the selected cases. It is intended for fast local iteration and MUST report that unselected package content was not validated.

`run` performs selected-mode validation against its execution plan. A release build and CI release gate MUST perform package-mode validation.

The two flags are mutually exclusive. `--selected` requires a case/suite/tag selection. `validate --package` MAY be used without selection and discovers the configured package roots.

### 9.2 Validation phases

Validation proceeds without invoking external tools:

1. CLI and option validation;
2. package root and schema discovery;
3. global configuration schema validation;
4. package indexing;
5. sidecar and workbook structure validation;
6. case ID, tag, stage selector, and data validation;
7. template schema and path validation;
8. type-specific action validation;
9. tool schema and call-contract validation;
10. expression syntax validation;
11. environment policy validation; and
12. execution-plan consistency validation where applicable.

All safely discoverable diagnostics SHOULD be accumulated rather than stopping after the first error.

### 9.3 Structured diagnostics

Every diagnostic has this contract:

```json
{
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
}
```

Required keys are always present. Non-applicable location values are JSON `null`, not empty strings.

Severity is one of:

- `ERROR`: invalid package or selected plan;
- `WARNING`: execution is permitted but attention is required; or
- `INFO`: explanatory validation result.

Diagnostic codes are stable API values defined in a central catalog. They MUST NOT be inferred from free-form exception messages.

Suggested code families are:

| Family | Scope |
|---|---|
| ATT-CLI | Commands and options |
| ATT-CFG | Global configuration/schema |
| ATT-TC | Workbook, sheet, row, and case data |
| ATT-STG | Stage configuration and selector data |
| ATT-TPL | Template and action schema |
| ATT-TOOL | Tool contract and invocation configuration |
| ATT-ENV | Environment protection |
| ATT-PATH | Path and identifier safety |
| ATT-RUN | Runtime and run lifecycle |
| ATT-PKG | Package integrity/build/archive |

### 9.4 JSON validation output

`validate --format json` writes one JSON document to stdout:

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.1.0",
  "valid": false,
  "mode": "package",
  "summary": {
    "errors": 1,
    "warnings": 0,
    "suites": 1,
    "cases": 22,
    "templates": 7,
    "tools": 7
  },
  "diagnostics": []
}
```

stdout MUST contain JSON only. Progress information is sent to stderr and is suppressed in quiet mode. Diagnostics MUST be emitted in deterministic order by file, location, code, and message.

## 10. Identifier and path safety

### 10.1 Canonical root checks

For every generated or resolved path ATT MUST:

1. resolve it against the intended root;
2. normalize it;
3. resolve existing parent symlinks where applicable;
4. verify it remains below the canonical intended root; and
5. reject root equality when a child location is required.

String-prefix comparison is insufficient unless both values are normalized `Path` instances and symlink behavior has been addressed.

### 10.2 Run ID

Run IDs are both logical display values and physical output-directory names. They MAY contain Chinese/Unicode letters and numbers, ASCII letters and numbers, spaces, `.`, `_`, and `-`, subject to the rules below.

Run IDs MUST:

- be non-blank after trimming;
- be no longer than 128 Unicode code points;
- not equal `.` or `..`;
- not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, or `|`;
- not contain NUL, newline, carriage return, tab, or another Unicode control character;
- not begin or end with whitespace;
- not end with `.`;
- not be an absolute path or contain a path segment; and
- not equal a platform-reserved device name such as `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, or `LPT1`–`LPT9`, case-insensitively.

After validation, the completed run directory is exactly:

```text
<outputDirectory>/<runId>/
```

ATT MUST NOT rewrite, slugify, hash, or otherwise change the Run ID used as the directory name.

### 10.3 Case ID

The full Case ID remains `<groupId>.<rowCaseId>` for selection, display, reporting, runtime context, and its physical directory name. Group ID and row Case ID MUST each be non-blank and MUST satisfy the same illegal-character, control-character, reserved-name, leading/trailing-whitespace, trailing-dot, and length rules as Run ID. The combined full Case ID MUST be no longer than 255 Unicode code points.

After validation, a case output directory is exactly:

```text
<outputDirectory>/<runId>/<fullCaseId>/
```

ATT MUST NOT rewrite, slugify, hash, or otherwise change the full Case ID used as the directory name. `case.yaml`, `run.yaml`, workbooks, reports, and indexes use the same value. HTML anchors and URLs MUST still apply normal URL/HTML encoding without changing the displayed identifier.

Before directory creation, ATT resolves and normalizes `<runDirectory>/<fullCaseId>` and verifies that the result is a strict child of the validated run directory.

### 10.4 Report command

`report --run-id` validates the logical Run ID, resolves only below the configured output root, and reads the canonical run manifest. It MUST NOT accept an absolute path or traversal segment as a Run ID.

### 10.5 Template and action paths

- Full template references use `/`, contain no empty, `.` or `..` segments, and remain below `templates.root`.
- Render payloads remain below their template directory.
- `saveAs` remains below its action output directory.
- Archive entry names are generated from verified relative paths and MUST contain no absolute or parent segments.

## 11. Environment value

V2.1 retains V2.0's `environment` as a non-blank global configuration value. ATT exposes it through `${CASE.environment}`, records it in run evidence, and permits tools to consume it through normal template arguments.

Environment allowlists, protected-environment confirmation, and production-access policy are intentionally outside V2.1 scope. Deployments requiring such controls MUST enforce them in approved tool scripts, CI permissions, or surrounding release processes.

## 12. Execution planning and atomic run lifecycle

### 12.1 Pre-run plan

Before filesystem mutation, ATT creates an immutable execution plan containing:

- selected suites and cases;
- ordered stages and resolved templates;
- referenced tools;
- effective timeout and retry policies;
- active environment;
- output root and proposed Run ID; and
- hashes of validated inputs.

The plan is rejected if validation has any ERROR diagnostic.

### 12.2 In-progress directory

The run lifecycle is:

```text
validate and plan
  → create <output>/.in-progress/<runId>-<nonce>/
  → write state RUNNING
  → execute and persist case evidence
  → finalize manifests and reports
  → fsync/close files where supported
  → atomically move to <output>/<runId>/
  → atomically replace latest-run.yaml
```

The final directory MUST NOT be visible as a completed run before all required evidence is finalized.

If atomic directory move is unsupported across filesystems, the output root configuration is invalid; ATT MUST NOT silently degrade to a partially visible completion protocol.

### 12.3 Interrupted runs

An interrupted run remains below `.in-progress` with state `ABORTED` or `INTERRUPTED` when ATT can update it safely. It is not eligible for `report`, `rerun-failed`, or `build` as a completed run.

ATT MUST report stale in-progress runs when a user invokes `att.sh clean`; it MAY provide a dedicated cleanup option after displaying each run's age and path.

## 13. Result model and aggregation

### 13.1 Status meanings

| Status | Meaning |
|---|---|
| PASS | All executed validation behavior succeeded |
| FAIL | Framework executed correctly and one or more business assertions failed |
| ERROR | Framework, tool, parser, timeout, I/O, or runtime execution failed |
| SKIPPED | Intentionally not executed due to dry-run, selection, or run condition |
| INVALID | Could not be scheduled because testcase/package validation failed |

A non-zero tool exit, timeout, malformed configured output, expression parse/evaluation exception, render error, and unexpected framework exception are ERROR, never FAIL.

### 13.2 Aggregation priority

For a collection of child results, the aggregate is determined by:

```text
if any ERROR   → ERROR
else if any INVALID → INVALID
else if any FAIL    → FAIL
else if all SKIPPED → SKIPPED
else if any PASS    → PASS
else                → SKIPPED
```

`onFailure: continue` affects control flow only. It MUST NOT downgrade or erase FAIL or ERROR.

All Stage, Template, Case, RunSummary, workbook, HTML, JSON, JUnit, and exit-code generation MUST call the same `ResultAggregator` implementation.

### 13.3 Exit codes

| Exit | Meaning |
|---:|---|
| 0 | Command succeeded; run contains no FAIL, ERROR, or INVALID |
| 1 | Run completed with at least one FAIL and no ERROR/INVALID |
| 2 | CLI, configuration, validation, environment policy, or INVALID selection failure |
| 3 | Run contains at least one ERROR or an unrecoverable runtime error occurred |

If a run contains both FAIL and ERROR, exit code is `3`.

Dry-run returns `0` when validation succeeds, even though selected cases are recorded as SKIPPED.

## 14. JSON and XML tool outputs

Supported tool output types are:

```text
txt | yaml | json | xml
```

Malformed structured output from a successful tool process is an ERROR. ATT MUST preserve raw output and parser diagnostics.

### 14.1 JSON

JSON parsing preserves:

- object insertion order;
- arrays;
- strings;
- booleans;
- null; and
- numbers using arbitrary precision (`BigDecimal`/`BigInteger`) rather than binary floating point.

Duplicate JSON object keys are rejected.

### 14.2 XML

XML parsing remains XXE-safe. Namespace handling is configured globally under `xml.namespaceMode`:

```yaml
xml:
  namespaceMode: ignore   # ignore | preserve
```

The default is `ignore`. In ignore mode, element and attribute keys use local names; namespace declarations and URI identity are not exposed in the parsed result. In preserve mode, keys use Clark notation (`{namespace-uri}localName`). The mapped form below shows preserve mode:

```yaml
name: "{namespace-uri}localName"
attributes:
  "{}id": "1"
text: "A"
children:
  item:
    - name: "{}item"
      attributes: {"{}id": "1"}
      text: "A"
      children: {}
    - name: "{}item"
      attributes: {"{}id": "2"}
      text: "B"
      children: {}
```

Rules:

- Repeated sibling elements MUST be preserved in document order as arrays.
- Element attributes MUST be preserved.
- `namespaceMode: ignore` is the default and uses local-name keys.
- `namespaceMode: preserve` retains namespace identity using Clark notation.
- Text and child elements MAY coexist.
- Comments and processing instructions are ignored unless a future schema version specifies otherwise.
- Entity expansion, DTD loading, and external resources remain disabled.

Convenience lookup MAY expose non-repeated children directly, but canonical persisted XML output MUST use one unambiguous mapping.

## 15. Run manifest and reproducibility

Every completed run contains `run.yaml` with at least:

```yaml
schemaVersion: att-run/v2.1
att:
  version: 2.1.0
  buildTime: 2026-07-11T00:00:00Z
  gitCommit: abcdef0
runtime:
  javaVersion: "21.0.7"
  javaVendor: Example
  osName: macOS
  osVersion: "15"
  osArchitecture: aarch64
  locale: en_HK
  timezone: Asia/Hong_Kong
run:
  id: SIT-001
  state: COMPLETE
  environment: SIT
  startedAt: 2026-07-11T01:00:00Z
  endedAt: 2026-07-11T01:05:00Z
validation:
  mode: selected
  diagnostics: []
inputs:
  - kind: global-config
    path: config/config.yaml
    sha256: "..."
  - kind: workbook
    path: testcase/payment_regression.xlsx
    sha256: "..."
cases: []
summary: {}
outputs: {}
```

Input hashes MUST include:

- effective global configuration;
- selected workbook files;
- all corresponding sidecars;
- every resolved template descriptor and render payload;
- referenced tool scripts or executable files when they are package-local regular files; and
- schema/catalog version used for validation.

Package-mode validation MAY record hashes for the entire package index. Paths stored in manifests are portable package-relative paths where possible. Secrets MUST be redacted before persistence, but hashing SHOULD be performed over the actual validated input bytes so modifications can be detected. Hashes are not secret redaction.

`latest-run.yaml` contains a small pointer with `schemaVersion`, logical Run ID, final run directory, completion time, status, and manifest hash. It is replaced atomically only after the final run directory is published.

## 16. Reports and CI outputs

### 16.1 Human report

The HTML report and result workbook consume the completed run manifest and case evidence. They MUST NOT independently reclassify results.

`report/index.html` MUST begin with a visible index linking to Overview, Groups, Cases, Case details, and the JUnit HTML view. Section targets MUST remain stable so large reports are directly navigable.

### 16.2 JSON summary

Each completed run writes:

```text
<run>/ci/summary.json
```

The document contains:

- `schemaVersion: att-ci-summary/v2.1`;
- ATT and Run IDs;
- environment and timestamps;
- aggregate counts and status;
- duration statistics;
- one record per selected case;
- diagnostic counts;
- report and artifact paths; and
- input manifest hash.

Numbers are JSON numbers, missing optional values are `null`, and output order is deterministic.

### 16.3 JUnit XML and HTML

Each completed run writes:

```text
<run>/ci/junit.xml
<run>/report/junit.html
```

`report/junit.html` is a human-readable projection of the same `RunSummary` used by `ci/junit.xml`; it does not introduce a second aggregation model. It is stored beside `report/index.html` and contains counts, one row per testcase, status, duration, and either embedded case-log content or an external relative link using the same configured threshold as XML.

Mapping:

| ATT status | JUnit representation |
|---|---|
| PASS | successful `<testcase>` |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

One ATT case maps to one JUnit testcase. Stage/action details and diagnostic codes are included in failure/error text with XML escaping.

JUnit case-log delivery is configured in global report configuration:

```yaml
report:
  junit:
    caseLogEmbedThresholdBytes: 10240
```

The threshold defaults to `10240` bytes (10 KiB). ATT embeds the complete case log in the JUnit testcase `system-out` when the UTF-8 log size is at or below the threshold. If it is larger, ATT writes a compact summary and a package-relative external artifact reference in `system-out`; the full log remains at its normal case-log path. A value of `0` always uses an external reference. ATT MUST XML-escape embedded content and URLs.

CLI MAY support:

```sh
./att.sh run --all --ci-output junit,json
```

The default completed-run output SHOULD include both formats because generation is local and deterministic.

## 17. Clean contract

V2.1 defines generated content explicitly:

| Location | Owner | Removed by default clean? |
|---|---|---:|
| configured `outputDirectory` | end-user `att.sh run` output | Yes, by `att.sh clean` |
| `build/docs` | end-user `att.sh docs` output | Yes, by `att.sh clean` |
| `build/att-*.tar.gz` | end-user `att.sh build` output | Yes, by `att.sh clean` |
| `target` | development compilation/test output | Yes, by `build.sh clean` |
| `target/release` | development release staging | Yes, by `build.sh clean` |
| `dist` | development release archives | Yes, by `build.sh clean` |

Command behavior:

```sh
./att.sh clean
./build.sh clean
```

`att.sh clean` removes only artifacts generated by end users invoking `att.sh`: configured `outputDirectory`, `build/docs`, and `build/att-*.tar.gz`. It MUST NOT remove `target`, `target/release`, or `dist`.

`build.sh clean` removes only development/build artifacts: `target`, `target/release`, and `dist`. It MUST NOT remove test run output or end-user report/archive output. Build implementation MUST accept the `clean` command before its normal package-build flow.

Clean MUST:

- canonicalize every target below the package root;
- reject the package root and source/configuration roots;
- reject symlinks that resolve outside the package root;
- deduplicate parent/child targets before deletion;
- be idempotent; and
- report exactly which roots were removed.

It MUST never remove `config`, `testcase`, `templates`, `tools`, `docs`, `schemas`, `src`, `lib`, `.git`, or the package root.

This section is authoritative for implementation, tests, Quick Start, Reference Manual, and CLI help.

## 18. Retry policy

Retry is an execution policy for tool actions. It is not a stage control-flow feature.

### 18.1 Schema

```yaml
retry:
  maxAttempts: 3
  retryOn: [EXIT_CODE]
  exitCodes: [1, 75]
```

Rules:

- `maxAttempts` includes the first attempt and is between 1 and the V2.1 fixed safety maximum of 10. V2.1 has no global retry-maximum override; introducing one requires a future configuration schema version.
- Default `maxAttempts` is 1.
- `retryOn` supports only `EXIT_CODE`.
- `exitCodes` is used only with `EXIT_CODE`; if omitted, any non-zero code qualifies.
- V2.1 has no retry delay or backoff fields. Attempts proceed immediately; delay/backoff policy is outside V2.1 scope.
- Timeout is never retried: a tool exceeding the action `timeoutMs`, or the resolved configuration `timeoutMs` when no action override exists, is immediately classified as ERROR.
- Configuration/argument validation errors and assertion FAIL are never retried.

### 18.2 Evidence

Every attempt has its own directory and record:

```text
invokeApi/attempt-001/
invokeApi/attempt-002/
```

The action node records all attempts, final status, total duration, and winning attempt. ERROR is returned after exhaustion. A later successful attempt makes the action PASS while retaining earlier errors as attempt evidence and warnings.

## 19. Tool process safety

Tool commands SHOULD be represented as an explicit argv list in V2.1:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - --input
  - ${TOOL.inputFile}
  - --output
  - ${TOOL.outputFile}
```

String command form MAY be retained only as a deprecated compatibility form with documented ATT tokenization; it is not evaluated by a shell. Pipes, redirects, substitutions, globbing, and environment-variable expansion are not implied.

Processes MUST have timeouts. On timeout ATT MUST terminate the process tree where supported, wait for bounded cleanup, preserve captured output, and classify the attempt as ERROR/TIMEOUT.

## 20. CLI reference changes

V2.1 command set:

```text
run        Validate selected dependencies and execute cases
validate   Validate the package or selected dependency closure
docs       Generate package documentation
report     Regenerate a completed run report
build      Build a run evidence archive
clean      Remove explicitly defined generated content
version    Print authoritative version/build information
help       Show command help
```

Relevant examples:

```sh
./att.sh validate --package
./att.sh validate --selected --case payment.TC001
./att.sh validate --package --format json
./att.sh run --all --ci-output junit,json
```

CLI parsing MUST reject options not valid for the selected command. `--package` and `--selected` are validation-scope options, not testcase filters.

## 21. Compatibility and migration

V2.1 is conceptually compatible with the V2 Case/Stage/Template/Action/Tool model, but its strict schemas intentionally expose ambiguous V2.0 configuration.

Migration requirements:

- add `schemaVersion` to global config, sidecars, and templates;
- remove unknown/reserved fields;
- update tool output enum where JSON is used;
- convert deprecated string commands to argv lists where practical;
- update scripts to use the authoritative version; and
- regenerate runs because V2.0 manifests do not satisfy `att-run/v2.1`.

V2.1 MUST NOT silently rewrite source files. A future migration command MAY generate a proposed patch/report.

## 22. Security requirements

- Safe YAML parsing MUST reject duplicate keys and arbitrary object construction.
- JSON parsing MUST reject duplicate object keys.
- XML parsing MUST disable DTDs, external entities, XInclude, and external schema retrieval.
- Excel/ZIP loading MUST enforce documented resource limits.
- All generated paths MUST follow Section 10.
- HTML, XML, JSON, YAML, CSV, and JUnit writers MUST escape untrusted values appropriately.
- Commands, inputs, stdout, stderr, workbooks, and reports may contain secrets; structured redaction policy MUST run before display/archive persistence where configured.
- Archive creation MUST verify entry paths, required evidence, and hashes.
- Completed manifests and latest-run pointers MUST use atomic publication.

## 23. Test strategy

Required automated suites include:

### 23.1 Schema and validation

- positive and negative tests for every allowed field;
- unknown-field and incorrect-type rejection;
- package versus selected validation closure;
- unused broken templates and tools in package mode;
- deterministic structured diagnostics and locations;
- JSON stdout purity; and
- expression/action type validation.

### 23.2 Paths and security

- Run ID, Case ID, report ID, template path, saveAs, and archive traversal;
- absolute paths and platform-specific separators;
- Unicode, reserved device names, control characters, and long identifiers;
- symlinks escaping allowed roots;
- XXE, YAML tags, duplicate YAML/JSON keys, ZIP bombs, and HTML injection.

### 23.3 Status semantics

- all combinations of PASS/FAIL/ERROR/SKIPPED/INVALID;
- mixed FAIL+ERROR returns ERROR and exit code 3;
- onFailure continue preserves failure/error;
- dry-run status and exit behavior; and
- identical aggregation across HTML, workbook, JSON, JUnit, and CLI.

### 23.4 Parsers

- JSON arrays, null, arbitrary precision, and duplicate keys;
- XML repeated elements, attributes, namespaces, mixed content, and XXE;
- malformed structured output preserves raw evidence and returns ERROR.

### 23.5 Run lifecycle

- no run directory before plan validation;
- in-progress creation;
- atomic completion and latest pointer replacement;
- interruption and stale run discovery;
- manifest schema/version/runtime/input hashes; and
- report/build refusal for incomplete runs.

### 23.6 Retry

- retryable versus non-retryable failure categories, including timeout as non-retryable;
- delay/backoff bounds;
- per-attempt evidence; and
- successful recovery versus exhaustion.

### 23.7 Release and clean

- build script resolves its actual root;
- all version surfaces match;
- optional directories do not break builds;
- clean removes only the authoritative default targets;
- `att.sh clean` and `build.sh clean` remove only their respective authoritative targets; and
- clean documentation, tests, and implementation use the same policy.

## 24. Release gate

V2.1 MUST NOT be marked stable or packaged as a release unless all of the following pass:

```text
1. clean checkout
2. compile with every supported JDK
3. all unit tests green: zero failures, zero errors
4. all integration tests green
5. all security/path regression tests green
6. ./att.sh validate --package passes for bundled examples
7. bundled sample run passes
8. JSON and JUnit outputs validate against their schemas
9. report regeneration succeeds
10. run evidence archive builds and verifies all hashes
11. release archive installs and runs from a new temporary directory
12. version values match across CLI, JAR, manifest, report, and archive
13. every documented command example is exercised by documentation tests
```

Warnings approved for release MUST be documented. Tests MUST NOT be skipped merely to make the release gate green. A known failing test blocks release.

## 25. Acceptance criteria traceability

| V2.1 requirement | Normative section |
|---|---|
| Run/Case/report traversal prevention | 10 |
| build.sh and unified version | 6 |
| ERROR/FAIL aggregation and exits | 13 |
| Separate `att.sh clean` / `build.sh clean` behavior | 17 |
| `--selected`/`--package` validation | 9 |
| Strict schemas/unknown fields | 7 |
| Type-specific actions | 8 |
| JSON and corrected XML | 14 |
| Manifest metadata and hashes | 15 |
| In-progress/atomic completion | 12 |
| Green release gate | 24 |
| Structured validation JSON | 9.3–9.4 |
| Diagnostic locations | 9.3 |
| Tool/action retry excluding timeout | 18 |
| JUnit and JSON CI output | 16 |

## 26. Final design position

V2.1 preserves the usability of Excel-driven, template-based API testing while making the result trustworthy. Its defining improvement is not feature quantity: it is the establishment of enforceable contracts around validation, paths, status, evidence, releases, and CI consumption.

A V2.1 PASS means that the framework executed successfully and the business assertions passed. A V2.1 ERROR means execution reliability was compromised. A completed V2.1 run is atomically published, versioned, hash-traceable, and consumable by both people and CI systems.
