# ATT V2.3 System Design

Status: implementation contract
Target release: 2.3.4

## 1. Scope

V2.3 refactors template actions without changing the established workbook → testcase → ordered stage → template → ordered action → tool model. The release adds multi-file render actions, a single nested action-outcome contract, assertion-controlled action results, two-phase action text evaluation, and explicit Expected/Actual report values.

The V2.2 tool groups, argv, SSH transports, retry policy, Case IDs, and run lifecycle remain unchanged unless this document says otherwise. V2.3.1 extends only the ATT-owned built-in catalog and ships a reference FPP tool group; V2.3.2 adds the current Case output directory to Context and makes it the working directory of local tool processes; V2.3.3 adds optional `argName` expansion to declared tool arguments; V2.3.4 allows multiple delimited arguments and adds named-list expansion modes. These updates retain the V2.2 config and tool-group schema identifiers.

V2.3 introduces `att-template/v2.3`. V2.3 packages use that template schema. Configuration and tool-group schemas remain at their V2.2 versions because their contracts do not change.

## 2. Naming decision

The canonical runtime-result field is `actual`.

The requirement drafts used both `acture` and `actural`; these are treated as spelling mistakes, not compatibility aliases. The strict V2.3 schema rejects both spellings. User documentation, Context, logs, JSON, HTML, Excel, and JUnit consistently use `actual`.

## 3. V2.3 action model

An action remains an ordered entry whose map key is its Action ID. All action types support:

| Field | Required | Meaning |
|---|---:|---|
| `type` | yes | `render`, `tool`, `assert`, or `log` |
| `description` | no | Human-readable text; supports two-phase `${...}` evaluation |
| `onFailure` | no | `stop` by default; `continue` keeps running later actions |

Every non-assert action may additionally define `assert`. For an assert action, `assert` is its required primary expression. `expression` is removed.

An assert action may define:

| Field | Required | Evaluation phase |
|---|---:|---|
| `assert` | yes | runtime |
| `expected` | no | validation, preserving unresolved runtime placeholders |
| `actual` | no | runtime |

Example:

```yaml
schemaVersion: att-template/v2.3
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    description: Render request for ${CASE.caseId}; status=${output.status}
    payload: payload/*.xml
    renderAs: file
    assert: "${output.targetFiles[0]} != null"

  invokeApi:
    type: tool
    description: Invoke the payment API
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]})}"
    assert: "${output.exitCode} == 0 and ${output.result.Status} == 'SUCCESS'"

  verifyStatus:
    type: assert
    description: Payment status must match the testcase expectation
    assert: "${ACTIONS.invokeApi.output.result.Status} == '${CASE.expectedStatus}'"
    expected: "${CASE.expectedStatus}"
    actual: "${ACTIONS.invokeApi.output.result.Status}"
```

## 4. Two-phase expression evaluation

### 4.1 Validation phase

Validation evaluates `description` on every action and `expected` on assert actions against the selected testcase's static Context. Static values include workbook/case data, Case IDs, stage selection, template identity, and other values known before execution.

Evaluation is partial. A `${...}` placeholder whose value is unavailable until execution is preserved exactly, including its `${` and `}` delimiters. Validation must never replace an unresolved placeholder with an empty string.

For example, with `CASE.caseId=payment.payment.CT001`:

```text
input:  Execute ${CASE.caseId}; send status=${output.status}
result: Execute payment.payment.CT001; send status=${output.status}
```

Validation still checks the syntax of every `assert` expression. It checks `actual` syntax as a template value but does not evaluate it.

Package validation may validate template structure without a testcase. In that mode all case- and runtime-dependent placeholders remain unchanged. Selected/all testcase validation evaluates each referenced template with the corresponding testcase Context.

### 4.2 Runtime phase

Runtime starts from the validation-phase text and resolves the remaining placeholders when their values become available:

- action payload/call/message processing occurs first;
- ATT creates the current action's `output` object;
- `assert`, `actual`, and the remaining part of `description` are evaluated against that outcome;
- the final description and outcome are persisted and logged.

`${output...}` is the action-local scope for the current action's outcome. `${ACTIONS.<actionId>.output...}` accesses a completed action in the current template. The durable path remains `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS.<actionId>.output...}`.

Unknown runtime placeholders are errors for `assert` evaluation. For descriptive text and optional `actual`, unresolved placeholders are retained so the log shows the missing runtime reference rather than silently deleting it.

## 5. Render actions

### 5.1 Configuration

A render action requires `payload` and `renderAs`:

```yaml
renderRequests:
  type: render
  payload: payload/**/*.xml
  renderAs: file
```

`payload` is one template-directory-relative glob. Absolute paths, parent traversal, symbolic links, and matches outside the template directory are rejected. ATT matches regular files only, sorts matches by normalized template-relative path, and reports validation ERROR when the pattern matches no files.

Supported glob syntax follows Java `PathMatcher("glob:...")`; `/` is the portable path separator in package files. Literal paths remain valid patterns and therefore preserve the single-file use case.

`renderAs` is required and is one of `file`, `text`, `json`, `yaml`, or `xml`. Render actions no longer accept `saveAs`, `overwrite`, or the former configuration `output.mode`.

### 5.2 `renderAs: file`

Each matched source is rendered and written below the current Case output directory using the same template-relative path. For example:

```text
template payload: payload/payment/request.xml
case target:      output/<RunID>/<CaseID>/payload/payment/request.xml
```

Parent directories are created as needed. ATT never overwrites a target that was already produced in the same Case; a collision is an action ERROR. This prevents two actions or two patterns from silently replacing evidence. The ordered target paths are stored in `action.output.targetFiles`. `action.output.result` is the same ordered list of target paths so generic result consumers do not need a file-specific top-level field.

### 5.3 Structured and text results

For `text`, ATT stores each rendered UTF-8 string. For `json`, `yaml`, and `xml`, ATT parses each rendered document using the same safe parsers and V2 XML projection rules used for tool output. Parse failure is an action ERROR.

`action.output.result` has a deterministic shape:

- one matched file: the string or parsed object itself;
- multiple matched files: an insertion-ordered map from normalized template-relative path to the corresponding string or parsed object.

`action.output.targetFiles` is empty for `text`, `json`, `yaml`, and `xml` because these modes do not write rendered artifacts.

## 6. Canonical action outcome

Persisted and transient Action nodes use this shape:

```yaml
renderRequest:
  id: renderRequest
  type: render
  description: Render request for payment.payment.CT001; status=PASS
  output:
    status: PASS
    success: true
    durationMs: 18
    exception: null
    targetFiles:
      - /package/output/20260714-120000/payment.payment.CT001/payload/request.xml
    result:
      - /package/output/20260714-120000/payment.payment.CT001/payload/request.xml
    assertion:
      expression: "${output.targetFiles[0]} != null"
      rendered: ".../payload/request.xml != null"
      passed: true
```

Only action identity/configuration metadata (`id`, `type`, final `description`, and tool evidence namespace when applicable) remains outside `output`. All outcome data is nested under `output`:

| Field | Contract |
|---|---|
| `status` | `PASS`, `FAIL`, or `ERROR` |
| `success` | `true` only when status is `PASS` |
| `durationMs` | complete action duration including retry/assert/runtime rendering |
| `exception` | `null`, or `{type, message}` for an operational error |
| `targetFiles` | ordered list; empty when no action output file was written |
| `result` | typed action result |
| `assertion` | present when the action has `assert`; expression, rendered value, and boolean result |

The former action-level `status`, `durationMs`, `outputFile`, `rawOutput`, and scalar/object `output` fields are removed. Tool stdout/stderr/argv/exit-code evidence remains available but is nested under the action outcome and/or the canonical `TOOL` evidence node; expressions use `output.result`, `output.exitCode`, and related outcome fields.

When execution throws, ATT persists the same Action node with `output.status=ERROR`, `success=false`, exception details, measured duration, any targets/results already safely produced, and an ERROR-marked Case-log block. A failed action is therefore always addressable from later actions when `onFailure: continue` is used.

## 7. Assertion-controlled success

Operational completion and test success are separate concepts.

- A launch failure, timeout, unsafe path, render/parse failure, or expression-evaluation error produces `ERROR`. An assertion cannot convert an operational ERROR into PASS.
- When a non-assert action has `assert`, a boolean true produces PASS and false produces FAIL.
- When a non-assert action omits `assert`, successful operational completion produces PASS.
- A tool's non-zero exit code is result evidence, not by itself the action status. If `assert` is present, the assertion decides PASS/FAIL and may explicitly test `output.exitCode`. If no assertion is present, a completed process is PASS regardless of exit code.
- Existing `retry.retryOn: [EXIT_CODE]` is evaluated before the final assertion. ATT retains attempt evidence; only the final selected attempt enters the action outcome used by `assert`.

An assert action has no separate default-success rule: its required `assert` evaluates true to PASS and false to FAIL.

The stage, Case, run, CLI exit code, and report status continue to aggregate action PASS/FAIL/ERROR values according to existing stop/continue rules.

## 8. Expected and Actual results

Only actions with `type: assert` contribute to the Case-level Expected and Actual report values.

For each assert action, ATT builds an Expected block by appending the non-blank final `description` and non-blank validation-phase `expected`, in that order. It builds Actual from the non-blank runtime `actual`. Missing optional values contribute no blank line.

Blocks follow template/action execution order. Lines and blocks are joined with exactly one line-feed (`\n`); ATT normalizes CRLF and CR input to LF and removes no meaningful internal line breaks. It does not use semicolons or HTML `<br>` as the stored value.

Example stored values:

```text
Expected:
Payment status must match the testcase expectation
SUCCESS
Ledger row must exist
1

Actual:
SUCCESS
1
```

Rendering rules:

- Case logs use YAML block scalars or escaped line feeds without losing line boundaries.
- HTML uses escaped text inside `<pre>`/pre-wrapped cells; content is never injected as markup.
- Excel enables wrapped text and preserves LF characters in the cell.
- CI JSON uses standard JSON escaped `\n` strings.
- JUnit includes the same Expected/Actual values in failure/error detail without flattening them to semicolon-separated text.

The configurable Excel report-column mapping adds `expectedResult`; existing `actualResult` remains. The default report includes both columns. HTML case tables/details and regenerated reports expose both values.

## 9. Validation and schema rules

The V2.3 template schema is strict:

| Type | Required | Optional type-specific fields | Forbidden legacy fields |
|---|---|---|---|
| render | `type`, `payload`, `renderAs` | `description`, `assert`, `onFailure` | `saveAs`, `overwrite`, `output`, `expression`, tool/log fields |
| tool | `type`, `call` | `description`, `assert`, `saveAs`, `overwrite`, `timeoutMs`, `retry`, `onFailure` | `payload`, `renderAs`, `expression`, assert-only/report fields, log fields |
| assert | `type`, `assert` | `description`, `expected`, `actual`, `onFailure` | `expression`, render/tool/log fields |
| log | `type`, `message` | `description`, `level`, `fields`, `assert`, `onFailure` | render/tool/assert-action fields |

Tool `saveAs`/`overwrite` remain unchanged in V2.3. Their removal applies to render actions, where `renderAs: file` and source-relative target names replace explicit filenames. This avoids inventing a new naming contract for persisted tool stdout.

Validation additionally checks glob safety and non-empty matches, parsing required by each `renderAs` mode where all static values are resolvable, expression syntax, two-phase fields, unique output targets within a Case plan when determinable, and report-column keys.

## 10. Compatibility and migration

V2.3 is an explicit template-schema migration. A V2.1 template is updated as follows:

```yaml
# Before
renderRequest:
  type: render
  payload: request.xml
  saveAs: request.xml
  overwrite: false

checkStatus:
  type: assert
  expression: "${ACTIONS.callApi.output.Status} == 'SUCCESS'"
```

```yaml
# V2.3
renderRequest:
  type: render
  payload: request.xml
  renderAs: file

checkStatus:
  type: assert
  description: API status is successful
  assert: "${ACTIONS.callApi.output.result.Status} == 'SUCCESS'"
  expected: SUCCESS
  actual: "${ACTIONS.callApi.output.result.Status}"
```

All references to prior Action result fields migrate below `.output`; a typed result uses `.output.result`, file targets use `.output.targetFiles`, and status uses `.output.status`.

## 11. Implementation boundaries

Implementation is divided into the following components:

1. template schema/loader model for `renderAs`, `expected`, `actual`, and canonical `assert`;
2. safe deterministic glob resolver and multi-document render/parser pipeline;
3. partial placeholder evaluator that preserves unresolved values;
4. uniform Action outcome builder/timer/error recorder;
5. action-local `output` Context and assertion-controlled result evaluation;
6. Expected/Actual collector and LF-normalizing report writers;
7. migration of shipped templates, Quick Start, Reference Manual, README, CHANGELOG, generated docs, and schema catalog;
8. focused unit tests plus package-level validation/build verification.

V2.3.1 adds ATT-owned local filesystem helpers (`fileExists`, `directoryExists`, `fileSize`, `makeDirectories`, `copyFile`, `moveFile`, and `deleteFile`) plus `randomChoice`. Mutating filesystem helpers use collision-safe defaults, reject unsafe final symlink copy/move targets, and return normalized absolute paths. These helpers produce no external TOOL evidence. `randomChoice` accepts 1–1000 consistently positional or named values and preserves the chosen value's type.

The shipped `fpp` group contains POSIX reference scripts for an API adapter skeleton, SQLPlus pipe-delimited output conversion, and child-script execution with captured stdout/stderr plus YAML status. The SQLPlus converter emits safe column names as direct XML elements and falls back to a `Column name="..."` element only when a header cannot safely form an XML name. The API skeleton reports `NOT_IMPLEMENTED` until its explicit integration block is replaced. The script runner preserves the child exit code inside parsed YAML while the wrapper exits successfully so ATT can parse and assert the result. Platform-specific implementations may replace these commands without changing the `fpp.*` call contract.

No V2.3 extension point for custom Java action types or user-supplied built-ins is introduced.

## 12. Test and release gates

V2.3 is complete only when automated coverage includes:

- literal and glob payloads, deterministic ordering, zero matches, traversal, symlink, and output collision rejection;
- all five `renderAs` modes, one-file and multi-file result shapes, and malformed structured content;
- nested outcome fields for PASS, FAIL, ERROR, retry, timeout, and continued execution;
- tool non-zero exit with assertion true/false and without assertion;
- validation partial evaluation and runtime completion of `description`, `expected`, and `actual`;
- canonical `assert` migration and rejection of `expression`, `acture`, and `actural`;
- Expected/Actual ordering, missing optionals, multiline normalization, escaping, HTML, Excel, JSON, JUnit, and report regeneration;
- migrated sample package validation and execution;
- filesystem collision, missing-file, symlink boundary, and random-choice argument coverage;
- executable FPP script tests for XML/YAML escaping, multiple SQLPlus rows, child exit propagation, and stdout/stderr capture;
- reserved `${CASE.outputDirectory}` initialization, validation-time preservation, published-path rewriting, and local-tool working-directory coverage;
- package-relative executable resolution from the package root after the process working directory changes to the Case output directory;
- `argName` named/positional expansion, `once`/`repeat` named-list modes, multiple delimited arguments, blank optional omission, atomic values, grouped dispatch, SSH logical argv, and malformed-placeholder rejection;
- `mvn test`, `./build.sh`, built-package `validate --package`, docs generation, and `git diff --check`.

## 13. V2.3.2 Case output directory contract

`${CASE.outputDirectory}` is a reserved runtime Context property. It is the normalized absolute path of the current Case's physical output directory and is available before the first stage starts. Case data cannot replace this value.

During execution the path points below `<outputDirectory>/.in-progress/<RunID>-<nonce>/<CaseID>/`. After successful atomic publication, ATT rewrites persisted text evidence to the completed `<outputDirectory>/<RunID>/<CaseID>/` path. Validation cannot know the runtime Run directory and therefore preserves `${CASE.outputDirectory}` rather than substituting a package-root placeholder.

Every local configured tool process starts with `${CASE.outputDirectory}` as its current working directory. Relative files created or read by the tool therefore belong to that Case by default and move with the atomic Run publication. To preserve existing packages, a local executable configured with a leading `./` or `../` is resolved against the package root before process launch and recorded as the executed absolute argv value; bare executable names still use the operating-system `PATH`. Other relative argv values are intentionally interpreted by the tool from the Case working directory.

ATT overrides two environment variables for every local tool process:

| Variable | Value |
|---|---|
| `ATT_ROOT_DIR` | normalized absolute package root |
| `ATT_CASE_OUTPUT_DIR` | the same runtime path as `${CASE.outputDirectory}` |

These values are framework-owned and replace inherited variables with the same names. They let one script locate stable package resources and Case-local artifacts without depending on cwd. `ATT_CASE_OUTPUT_DIR` points to the physical in-progress Case directory while the process runs.

For an SSH tool, `${CASE.outputDirectory}` remains available as local Context, but ATT does not issue a remote `cd` or inject the two local-path environment variables: local package and Case paths have no defined remote equivalents. The remote process continues to use the SSH account's default working directory. A tool that uses a shared filesystem or a dedicated remote directory must receive that path as an explicitly declared argument.

## 14. V2.3.3 optional tool argument names

A declared tool argument may set `argName` to one static whitespace-free argv token such as `--reference`. The argument placeholder supplies the deterministic insertion point and, for a non-empty `argName`, must be the only placeholder in exactly one complete command token. A provided non-blank scalar expands to `[argName, value]`; a delimited value expands to `[argName, value1, value2, ...]`. Values remain atomic and are never shell-tokenized.

If an optional value is absent or normalizes to blank, its complete-token placeholder emits zero argv, including no `argName`. An omitted or explicitly empty `argName` denotes a positional process argument and emits only the non-blank value. Required blank values remain validation errors. Expansion occurs before group-script dispatch and SSH transport wrapping, so local, grouped, OpenSSH, and mwiede/jsch executions share the same logical argv contract.

## 15. V2.3.4 multiple delimited arguments and argName modes

Multiple arguments may independently declare `delimit`; each exact-token placeholder expands its own normalized ordered list at that position.

For a delimited argument with a non-empty `argName`, `argNameMode` accepts `once` or `repeat` and defaults to `once` to preserve V2.3.3 behavior. `once` expands to `[argName, value1, value2, ...]`; `repeat` expands to `[argName, value1, argName, value2, ...]`. The mode does not affect a scalar value and has no output effect when `argName` is omitted or empty.
