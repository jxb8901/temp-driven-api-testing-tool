# Results, reports, and evidence

Run executes authored Testcases and records one Case execution for each selected row. A Testcase is workbook data; a Case execution is the runtime result identified by the same full Case ID. Use the runtime term when reading statuses, logs, reports, and evidence.

| Task | Go to |
|---|---|
| Find run files and execution artifacts | [Find artifacts for a Run](#find-artifacts-for-a-run) |
| Read a status or inspect a failed Action | [Find Case execution results](#find-case-execution-results-in-the-html-report) |
| Export JUnit or CI results | [Export JUnit results](#export-junit-results) or [Read the CI JSON summary](#read-the-ci-json-summary) |
| Reproduce a completed run | [Reproduce a Run](#reproduce-a-run) |

## Find artifacts for a Run

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

## Find Case execution results in the HTML report

`report/index.html` is the primary end-user report. It can be opened without a web server. Groups are summarized by `workbookId.groupId`; the interface labels `groupId` as Sheet because it maps to one physical sheet. The Cases view supports Workbook/Sheet/Status dropdowns, case-insensitive search over workbook/group/full Case ID/tags, and ascending/descending sorting from every column heading. Duration sorting is numeric.

An expanded case contains the full Case ID and name, status and duration, Expected and Actual results, one row per recorded action result, a bounded detailed execution-log preview, and explicit `.log`/`case.yaml` artifact links. Each Action Results row has independent Stage, Action, Description, Status, and Message columns; Description is the final rendered action description and is also persisted in `run.yaml` and CI JSON. `report.html.caseLogInlineLimitBytes` controls the head/tail preview; `0` keeps only the artifact link. For compatibility, Expected remains the ordered LF-joined non-blank assert descriptions and `expected` values; Actual is the ordered LF-joined non-blank runtime `actual` values. `case.yaml` holds the complete structured final Stage/Template/Action/Tool/DB state. Depending on what ran, these artifacts include selected templates, executed or skipped stages/actions, assertion messages, Tool argv/stdout/stderr/retry evidence, DB source/parameter/result/finalization evidence, diagnostics, and saved payload/Tool/DB-output paths. Workbook ID, group ID, and tags are persisted per case in `run.yaml`, so `report --run-id` regenerates equivalent controls and grouping.

`report/junit.html` is a human-readable JUnit projection. It displays counts and one row per testcase with status, duration, and embedded case-log content or a relative artifact link.

## Diagnose Tool evidence collector failures

An evidence collector is post-operation observability, not the primary Tool result. With `onFailure: continue`, the primary Action may remain `PASS` while the collector is independently recorded as `ERROR`:

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>` (or the equivalent `ACTIONS` compatibility view). The record contains `status`, `success`, `invocationId`, `result`, `error`, and the bounded/redacted underlying operation `evidence`; when an operation supplies structured diagnostics, `operationDiagnostic` retains safe fields from the native operation diagnostic. `diagnostic` identifies the collector failure and its source file/field. `error.message` is populated from the underlying exception, operation status/exit code, or a safe fallback. Resource identity and fields such as SSH helper/instance, exit code, bounded stderr, MQ reason codes, HTTP status, and timeout details remain under `evidence` when provided by the executor. Failed collector evidence is bounded/redacted; raw input, payload, argv, output, resolved command text and the failed `result` are not published. See [Limits, Security Guarantees, and Advanced Diagnostics](appendices/limits-defaults.md) for the exact projection, numeric budgets and security guarantees.

For retries, inspect `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`. Earlier failed collector records remain available after a later successful attempt; the top-level collector record follows the final/winning attempt. With `onFailure: stop`, the Action can fail, but its diagnostic still includes the collector's root-cause message and preserved evidence. The same structured record is written to the `EVIDENCE <action> attempt=<n> collector=<id>` block in `case.log`, so the basic resource, category, message, exit code, and bounded stderr can be diagnosed without opening internal exception traces. Existing capture limits and secret redaction continue to apply; collector wrapping does not enable unbounded raw output.

## Add execution results to a Workbook

ATT copies the source workbook and appends configured result columns using `report.mode: append-to-copy`. Set `report.mode: none` for CI or large runs that do not need a copied workbook. Global `report.fileNamePattern` controls the copy filename. Sidecar `report.columns` changes workbook labels only. Supported mappings include `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, and `runTime`; Expected/Actual cells retain LF characters and use wrapped text. Row matching reads the Testcase's Case ID with the same Excel `DataFormatter` and whitespace normalization as Testcase loading, so displayed formats such as numeric leading zeroes identify the same Testcase in execution results.

## Export JUnit results

Each Case execution maps to one JUnit `<testcase>`:

| ATT status | JUnit representation |
|---|---|
| PASS | no failure child |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

Text is XML-escaped. JUnit XML and HTML use `report.junit.caseLogEmbedThresholdBytes`. Logs at or below the threshold are embedded; larger logs use a relative link. `0` always links.

## Read the CI JSON summary

`ci/summary.json` uses `schemaVersion: att-ci-summary/v2.1` and contains ATT/run IDs, environment, timing, aggregate status/counts, duration statistics, per-case records, diagnostic counts, report/artifact paths, and the input-manifest hash.

## Reproduce a Run

`run.yaml` uses `schemaVersion: att-run/v2.1` and records ATT/build identity, Java/OS/locale/timezone, validation mode, environment, timestamps, status/summary, output paths, and SHA-256 inputs for effective configuration, tool-group files, call-backed Tool SQL files (`tool-sql`), workbook, sidecar, resolved templates/payloads, package-local tool files, and schema/catalog version.

## Generate docs and manage package output

| Command | Output/behavior |
|---|---|
| `docs` | Generates searchable offline package documentation at `build/docs/index.html`; Testcases are grouped by workbook and Sheet |
| `report --run-id <id>` | Regenerates both HTML reports from completed evidence |
| `build` | Archives the latest completed run without executing tests |
| `clean` | Removes configured output directory, `build/docs`, and `build/att-*.tar.gz` |

The build archive contains reports, workbooks, case logs, referenced artifacts, redacted configuration/template snapshots, manifest, and hashes. Exact YAML keys named `password`, `token`, `secret`, or `authorization` are redacted case-insensitively; this is not a general log/result redactor.

The Testcases section renders one table per Sheet below each workbook heading. The Sheet column is omitted because the group heading supplies that context. Each table includes Expected Result, formed in stage/action order from every assert action's validation-time `description` and `expected`; unresolved runtime placeholders remain visible and line endings are normalized to LF.

Clean never removes testcase, template, tool, configuration, documentation, schema, or other source files. It canonicalizes paths, rejects source/package roots and external symlink targets, is idempotent, and reports what it removed.

## Trace execution identity to an artifact

| Identity | Meaning | Scope | Artifact role |
|---|---|---|---|
| EXEC.RUN_ID | Enclosing ATT run. | Run. | Run root, summary and report. |
| EXEC.ID | Current Case/Debug/Load execution. | Execution. | Key for logs/evidence when a workspace exists. |
| EXEC.OUTPUT_DIR | Workspace path associated with EXEC.ID. | Execution. | Physical Run/Debug workspace or planned lazy Load workspace. |

Normal Run stores functional Cases under output/<RUN_ID>/executions/<EXEC.ID>/. In Load, EXEC.OUTPUT_DIR and CASE.outputDirectory remain at output/load/<RUN_ID>/executions/<EXEC.ID>/ throughout the iteration. When retained, a copy of its artifacts is also stored under samples/<EXEC.ID>/ or failures/<EXEC.ID>/. Metrics-only iterations have EXEC.ID but no per-iteration directory after the scheduler releases their temporary workspace. Retained Load rows show EXEC.ID and link to case.log when present. Debug uses its debug ID as both EXEC.RUN_ID and EXEC.ID.

DIAG is evidence-only. Do not reference DIAG, EXEC.MODE or arbitrary scheduler counters in expressions; pass business variation through EXEC.INPUT.


## Inspect generated-output schemas

| Artifact | Required top-level contract |
|---|---|
| `run.yaml` | `schemaVersion`, `att`, `runtime`, `run`, `validation`, `inputs`, `cases`, `summary`, `outputs` |
| Validation JSON | `schemaVersion`, `attVersion`, `valid`, `mode`, `summary`, `diagnostics` |
| CI summary JSON | `schemaVersion`, `attVersion`, `runId`, `environment`, `startedAt`, `endedAt`, `status`, `summary`, `durationStatistics`, `cases`, `diagnosticCounts`, `report`, `inputManifestHash` |
| JUnit XML | one testsuite with test/failure/error/skipped counts and one testcase per ATT case |

Generated envelopes reject additional top-level fields according to their schemas. JUnit HTML is a human-readable output and not an XML/JSON schema artifact.

## Reading `case.log` and `case.yaml`

Case log structured entries use YAML. The human log records each normal Action and each Tool/DB invocation once; duplicated attempt fields and persisted TOOL/DB subtrees are omitted from this projection. Complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` fully expands shared Map/List objects; `true` permits YAML anchor markers, which carry no ATT identifier semantics.

Multiline String values nested in structured entries are shown as readable YAML block content. ATT preserves their LF, CRLF or lone-CR separators and does not parse, trim or reformat the business text. Raw process and user content also keeps its original line endings. The live console mirror uses the same rendered log text as the persisted `case.log`.

ATT prefixes Case log blocks whose section or nested status is ERROR, FAIL or INVALID with `【!!!!!】`. Search for that marker to find abnormal blocks; PASS, SKIPPED and informational blocks remain unmarked.
