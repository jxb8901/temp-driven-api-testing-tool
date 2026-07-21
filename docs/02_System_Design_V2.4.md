# ATT V2.4 System Design

Status: implemented
Target release: 2.4.2

## 1. Scope and source of truth

V2.4 adds testcase version control without changing the established workbook → Case → ordered stage → template → ordered action → tool model. V2.4.1 additionally makes failed Context lookup easier to diagnose, permits deterministic unambiguous Context-path suffixes, improves the layout of multiple human validation diagnostics, and adds an explicit run option for refreshing selected snapshots before validation. V2.4.2 unifies `${...}` references and `#{...}` calls behind one expression engine across all expression-bearing surfaces. The `.xlsx` workbook remains the only editable source. Every workbook has two adjacent companions:

```text
testcase/payment.xlsx   # editable source
testcase/payment.yaml   # workbook sidecar
testcase/payment.xml    # generated semantic snapshot
```

The XML is not an unpacked OOXML workbook and cannot be used to regenerate Excel. It records only values ATT consumes after sidecar mapping and normalization. Styles, widths, comments, filters, formulas outside configured columns, and unconfigured sheets/columns are excluded.

## 2. Snapshot contract

The root is `<testcases schemaVersion="att-testcases/v2.4" workbookId="...">`. Groups follow sidecar order and contain `id`, physical `sheet`, and Cases in workbook order. A Case is identified by `groupId.rowCaseId` and contains ordered tags, configured case data, and configured stages. Row numbers are not persisted.

Values use explicit `type="string|integer|decimal|boolean|null|map|list"`. Maps use ordered `<entry name="...">` children and lists use ordered `<item>` children. This preserves parsed `(yaml)` cell types without relying on XML text inference.

Canonical output is UTF-8 without BOM, LF-only, two-space indentation, fixed element/attribute ordering, and one final newline. String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA so the testcase text remains directly readable and unescaped; ordinary strings use normal escaped XML text. A literal `]]>` is losslessly split across adjacent CDATA sections in one deterministic form. Spaces or tabs immediately before an LF are emitted as `&#32;` or `&#9;` outside the adjacent CDATA so the parsed value remains exact without introducing Git trailing-whitespace errors. DTDs, external entities, XInclude, and external schema access are disabled. `schemas/att-testcases-v2.4.xsd` publishes the document shape; runtime strict parsing additionally enforces value-type/content rules and canonical bytes.

## 3. Generation and verification

```sh
./att.sh snapshot --suite testcase/payment.xlsx
./att.sh snapshot --all
```

Generation resolves the mandatory sidecar and fully loads every selected workbook before writing any snapshot. Each destination is written through a same-directory temporary file and atomically replaced when supported. A parse/build failure does not overwrite the existing XML.

`run`, `validate --selected`, and `validate --package` require every selected workbook snapshot to exist, parse securely, match the current schema, be canonical, and equal the current normalized testcase projection. Verification is read-only and never regenerates XML. A mismatch is `ATT-TC-001` and reports added/removed/reordered groups or Cases plus field paths such as `payment.TC001.data.amount` or `payment.TC001.stages.invoke.name`.

Framework planning verifies the snapshot before creating the output or `.in-progress` directory. Completed run manifests hash the workbook, sidecar, and testcase snapshot as separate inputs.

## 4. Excel boundaries and workflow

Formula cells in configured Case ID, tags, data, stage selector, or stage-data columns are invalid because a formula and its cached/displayed result can diverge. Authors must paste literal values and use Excel Text for identifiers or leading-zero values. Any merged region intersecting configured testcase columns below the header area is invalid; merged presentation headers within `excel.headerRows` remain supported.

Normal authoring flow is: edit xlsx → run snapshot → review the XML diff → run `validate --package` → commit xlsx, YAML when changed, and XML together. XML must not be manually edited. Binary workbook merge conflicts are reconciled in Excel and followed by fresh snapshot generation.

## 5. Release gates

- Deterministic output across repeated generation, locales, and LF/CRLF environments.
- Semantic changes produce minimal field-level diffs; style-only changes do not alter XML.
- Chinese names, multiline/CDATA strings, XML-special characters including literal `]]>`, empty strings, nested maps/lists, booleans, integers, decimals, and null values round-trip exactly.
- Missing, stale, malformed, non-canonical, XXE/DTD-bearing, formula-backed, and merged-data inputs fail before output mutation.
- `mvn test`, `./build.sh`, repository `validate --package`, and built-package `validate --package` pass with all sample snapshots present.

## 6. V2.4.1 Context lookup, human diagnostics, and run snapshot update

### 6.1 Context lookup failure evidence

Every failed `${...}` Context lookup MUST retain the existing source location (file, field, Sheet, row, column, template, and action when available) and additionally report:

- `requestedPath`: the exact case-sensitive path written by the author;
- `currentNode`: the deepest node reached successfully while resolving that path, or `<root>` when no first segment was selected;
- `missingSegment`: the first segment that could not be resolved, for an unknown-path failure;
- the relevant canonical candidate paths, for an ambiguous shorthand failure.

`currentNode` describes Context traversal, not the currently executing Action. The existing diagnostic source location identifies the executing template, Action, and field. Diagnostics MUST NOT append the complete Context tree: large Cases and failed Tool results otherwise multiply the same structure across console, Case log, reports, and exception evidence. Runtime values and the full structured final state remain available in `case.yaml`.

Example:

```text
ATT-CTX-001: Unknown Context variable '${CASE.response.Status.code}'
  location: file=templates/VERIFY/template.yaml, field=actions.assertStatus.actual, template=VERIFY, action=assertStatus
  requestedPath: CASE.response.Status.code
  currentNode: CASE.response.Status
  missingSegment: code
```

Case-bound validation, package validation, and runtime use the same path grammar, traversal-boundary rules, nearest-path suggestion, and candidate-selection rules without printing runtime values or a whole-tree projection.

### 6.2 Unambiguous Context-path suffixes

A canonical full Context path remains valid and is resolved first. References beginning with an explicit public root (`CASE`, `RUN`, `ACTIONS`, `TOOL`, or action-local `output`) are traversed strictly so an invalid rooted path cannot fall through to another scope. A reference without one of those roots is treated as a case-sensitive path-segment suffix and searched against the logical Context readable at that evaluation point. Given one canonical path `a.b.c`, and no competing readable path with the same suffix, `${a.b.c}`, `${b.c}`, and `${c}` resolve to the same node.

Suffix matching follows these rules:

1. Matching is by parsed map/list path segment, not by raw character suffix. Quoted map keys and list indexes retain their normal exact semantics.
2. Only paths readable at the current validation or runtime position are candidates. A future Action or an unavailable action-local/tool scope cannot satisfy a shorthand.
3. Durable and convenience aliases of the same logical node count as one candidate and report one canonical path.
4. Exactly one candidate resolves successfully. Zero candidates produce `ATT-CTX-001` with the failed traversal evidence from Section 6.1.
5. More than one logical candidate produces `ATT-CTX-002` (`Ambiguous Context shorthand`). ATT lists every canonical candidate and requires a longer suffix or full path; it never selects by insertion order, tree order, or nearest scope.
6. Adding a new conflicting node may make an existing shorthand invalid at validation, but MUST NOT silently change the value to which that shorthand resolves.

For example:

```text
CASE.payment.response.status: string
```

`${payment.response.status}`, `${response.status}`, and `${status}` are equivalent while this is the only readable path ending in those segments. If `CASE.refund.response.status` also becomes readable, `${status}` and `${response.status}` are ambiguous and fail with both canonical paths; `${payment.response.status}` remains unique.

The shorthand applies only to `${...}` runtime Context references in template/action fields and render payloads. It does not change configured-tool names, tool command argument placeholders, YAML keys, file paths, XML navigation, or any other grammar. Documentation and generated examples continue to prefer canonical paths, especially for cross-stage references; shorthand is an authoring convenience, not a new persisted Context shape.

### 6.3 Human validation layout

Human `validate` and pre-run validation output group every diagnostic into one indented block. ATT prints one blank line after the validation summary and exactly one blank line between diagnostic blocks. The diagnostic header is indented by two spaces; continuation lines such as location, detail, Context evidence, and suggestion are indented by four spaces relative to the left margin. ERROR, WARNING, and INFO use the same layout and retain their existing deterministic order.

```text
Validation FAIL: 2 suites, 24 cases, 8 templates, 16 tools

  [ERROR] ATT-CTX-001: Unknown Context variable '${CASE.response.code}'
    location: file=templates/VERIFY/template.yaml, field=actions.verify.actual
    suggestion: Use '${CASE.response.Status}' if that is the intended Context variable.

  [ERROR] ATT-TPL-104: assert action requires a non-blank expression
    location: file=templates/VERIFY/template.yaml, field=actions.verify.assert
    suggestion: Add a non-blank assert expression.
```

There is no leading blank line before the summary and no extra blank line after the final diagnostic. `--quiet` suppression and stdout/stderr selection do not change. `--format json` remains a single machine-readable document and is not given human indentation or blank-line formatting.

### 6.4 Opt-in snapshot update during run

Normal `run` remains read-only with respect to testcase sources. Without an update option, ATT loads each workbook and sidecar, derives its current semantic projection, and verifies the adjacent snapshot. A missing, stale, malformed, non-canonical, unsupported-schema, or symbolic-link snapshot produces `ATT-TC-001` and exits before creating the output directory or invoking any template/tool. A modified Git working-tree status by itself is not an ATT error: this comparison is between canonical snapshot bytes and the current workbook/sidecar projection and does not invoke or depend on Git.

V2.4.1 adds this run-only option:

```sh
./att.sh run --suite testcase/payment.xlsx --update-snapshot
```

`--update-snapshot` explicitly authorizes ATT to create or replace the adjacent XML snapshots for the workbooks selected by that run before normal validation. It is valid only for `run`; `validate` remains read-only, and the standalone `snapshot` command remains the normal authoring/review workflow.

The update phase follows these rules:

1. ATT parses the command and configuration, checks the proposed Run ID, resolves the run's workbook set, and completely loads every selected workbook/sidecar before replacing any snapshot. An invalid workbook, sidecar, configured formula/merge boundary, duplicate ID, or serialization failure prevents all selected snapshot writes.
2. Workbook selection follows the normal run discovery rules. `--suite` limits the update to that workbook; `--suite-dir` limits it to that recursive directory; `--all`, `--case`, `--tag`, and `--rerun-failed` without an explicit suite use the workbooks discovered below the configured testcase root. Case/tag/rerun filters do not create partial snapshots: every updated XML always represents its complete workbook.
3. ATT builds canonical bytes with the same extractor and serializer used by the standalone `snapshot` command and by verification. If an existing regular snapshot already has identical canonical bytes, ATT leaves the file untouched. Missing, stale, malformed, non-canonical, and unsupported-schema regular files are eligible for replacement. A symbolic-link snapshot is rejected rather than followed or replaced.
4. Each changed snapshot is written through a same-directory temporary file and atomically replaced when supported. A failure before replacement preserves that file's previous bytes. Multi-workbook preparation is all-or-nothing, while physical replacement remains atomic per file; a later filesystem failure may leave earlier successfully replaced workbooks updated and MUST identify every completed and failed path.
5. After the update phase, ATT performs the ordinary read-only snapshot verification and complete selected-scope validation again. Execution starts only if that validation passes. If another validation problem fails the run, successfully updated snapshots remain on disk because the option explicitly requested that source-side update; ATT does not roll them back.
6. Snapshot generation and validation complete before any run output or `.in-progress` directory is created. No template action, inline tool, or configured tool may execute during the update phase.
7. `--dry-run --update-snapshot` is allowed: `--dry-run` suppresses testcase tool execution, while the explicit update option still authorizes snapshot writes. Queue/parallel execution MUST serialize the snapshot update phase for a package so concurrent ATT processes cannot replace the same snapshot simultaneously.

For non-quiet human output, ATT prints one package-relative `Snapshot updated: <path>` line for each replaced or created file and prints nothing for byte-identical snapshots. `--quiet` suppresses successful update notices. JSON mode keeps stdout machine-clean; update notices, when enabled by its console mode, use stderr and never precede or follow the JSON document on stdout. Any update failure uses the normal typed diagnostic and exit code 2.

The default error suggestion for a missing or mismatched snapshot continues to provide the review-first command:

```sh
./att.sh snapshot --suite testcase/payment.xlsx
```

It may additionally mention `run --update-snapshot`, but MUST describe that option as an explicit overwrite workflow rather than silently enabling it.

### 6.5 V2.4.1 acceptance criteria

- Missing first, middle, final, quoted-key, and list-index path segments report the exact `requestedPath`, deepest `currentNode`, first `missingSegment`, and source location without a complete Context-tree dump.
- Case-bound validation, unreferenced-template validation, and runtime failures expose only the structures available at their respective evaluation points and never invent future Action results.
- Full paths and every uniquely identifying suffix resolve to the same value in validation and runtime; segment matching remains case-sensitive.
- Zero-candidate and multi-candidate references produce `ATT-CTX-001` and `ATT-CTX-002` respectively. Ambiguous diagnostics list all canonical candidates in deterministic order and never select one implicitly.
- Duplicate durable/convenience views of one logical node do not create false ambiguity; genuinely distinct nodes with equal values remain ambiguous.
- Human output uses one indented block per diagnostic and one blank line between blocks for ERROR, WARNING, and INFO; JSON bytes and schema remain unaffected by presentation-only whitespace rules.
- Context diagnostics do not copy the complete Context structure, actual Excel values, assigned values, tool input/output, stdout/stderr, argv, raw payloads, or credentials into default console output.
- Default `run` rejects missing, stale, malformed, non-canonical, unsupported-schema, and symbolic-link snapshots without modifying source files or creating run output.
- `run --update-snapshot` creates/replaces only the selected workbook snapshots whose canonical bytes differ, then passes through the same verification and validation gates as an ordinary run.
- Case/tag/rerun selection never produces a partial-workbook snapshot; explicit `--suite` and `--suite-dir` constrain the workbook update boundary.
- A generation failure before replacement preserves every selected snapshot; replacement is atomic per file, byte-identical files retain their bytes/mtime, and multi-file I/O failures report partial completion precisely.
- `--update-snapshot` rejects non-run commands and snapshot symlinks, performs no tool execution, precedes output-directory creation, remains explicit under `--dry-run`, and is serialized across concurrent ATT update phases.
- Human, quiet, and JSON console modes report or suppress successful snapshot updates according to Section 6.4 without contaminating machine-readable stdout.

## 7. V2.4.2 unified expression processing

V2.4.2 removes the split between value-only renderers and call-aware renderers. `UnifiedTemplateEngine` is the single author-facing grammar implementation for `${...}` references, `#{...}` built-ins, configured Tool calls, nested call arguments, validation parsing, and scoped string rendering.

Case-runtime surfaces use the complete engine and a `CaseRuntimeContext`: render payload content, action description/assert/expected/actual, log message/field values, assign expression, Tool-call arguments, and Tool-action `saveAs`. `${path}` remains text interpolation. Inside `#{...}`, a complete unquoted canonical path rooted at `CASE`, `RUN`, `ACTIONS`, `TOOL`, or `output` resolves directly, while ASCII-quoted values remain literals. Assertions execute calls first; for example, `#{length(CASE.value)} <= 35` becomes `12 <= 35` before comparison parsing. The older `#{length(${CASE.value})}` spelling remains compatible. Context paths and nested exact-call arguments retain typed results instead of being forced through text.

Two non-Case scopes use the same parser/renderer with explicit capabilities:

- `report.fileNamePattern` exposes `${suiteName}` interpolation or bare `suiteName` inside a built-in call;
- Tool-definition `command` exposes each declared argument as `${argument}`, `${input.argument}`, and `${TOOL.input.argument}`, plus the corresponding bare paths inside built-in calls.

Configured Tool calls are rejected in these two scopes. Report writing has no Case execution log, while Tool command expansion occurs inside an existing Tool invocation; permitting configured Tools there would create untracked or recursive process execution. The primary `type: tool` action call must still resolve to a configured Tool, although nested argument calls use the normal Case-runtime capability.

Validation parses every expression-bearing field and nested call using the runtime grammar, validates call names/signatures and Context timing, and never invokes built-ins or external Tools. Tool command and report configuration are rejected at load time for unknown scope values, configured Tool calls, malformed expressions, or dynamic executable tokens.

### 7.1 V2.4.2 acceptance criteria

- Every public location that accepts `${...}` accepts syntactically valid `#{...}` under its documented scope.
- Runtime and validation share call-boundary, nesting, argument, and value-reference parsing.
- Canonical Runtime Context paths resolve directly as complete unquoted call arguments; quoted equivalents remain literals, unique-suffix shorthand remains `${...}`, and legacy nested references remain compatible.
- Built-ins work in descriptions, assertions, expected/actual, logs, fields, assign, render payloads, Tool-call arguments, `saveAs`, report filename patterns, and Tool command tokens.
- Configured Tool calls work on every Case-runtime text surface and emit normal evidence, but are rejected in report-filename and Tool-command scopes.
- Assertion built-ins and configured Tools are evaluated before typed comparison parsing; the original and rendered assertion remain in action evidence.
- Unknown calls, malformed nested calls, illegal scope references, and unsupported Tool-action primary built-ins fail validation rather than becoming literal text.
