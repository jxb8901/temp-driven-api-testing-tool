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
└── <CaseID>/...
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

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>` (or the equivalent `ACTIONS` compatibility view). The record contains `status`, `success`, `invocationId`, `result`, `error`, and the bounded/redacted underlying operation `evidence`; when an operation supplies structured diagnostics, `operationDiagnostic` retains safe fields from the native operation diagnostic. `diagnostic` identifies the collector failure and its source file/field. `error.message` is populated from the underlying exception, operation status/exit code, or a safe fallback. Resource identity and fields such as SSH helper/instance, exit code, bounded stderr, MQ reason codes, HTTP status, and timeout details remain under `evidence` when provided by the executor. All failed collectors, including returned operation errors and thrown Tool exceptions, pass through the same public projection before publication or logging. The projection omits raw input, payload, argv, output, resolved command text, and the failed record's `result`, and does not guarantee `parserDiagnostic`. Native error/diagnostic maps retain only safe fields; each retained text field is limited to 1024 characters. `inputOmitted` and truncation flags identify omitted or bounded evidence. Free-form messages, stderr, per-instance errors, and cleanup warnings redact String text and array inputs within a fixed budget: 256 input nodes, 8192 token characters, and 1024 characters per token. Byte arrays are limited to 128 bytes (UTF-8, Base64, hexadecimal, and Java decimal renderings), other arrays to 64 elements, and char arrays to 1024 characters. Exceeding any budget, encountering a private token shorter than 4 characters, or encountering an unknown input type omits all free-form failure details with a safe marker, including upstream-truncated secret prefixes or head/tail echoes, and sets `inputRedactionLimited` and `failureDetailsOmitted`. Free-form fields longer than 1024 characters are also omitted and marked truncated; structured metadata remains available. Structured status, category, and resource identity are only length-bounded. SSH fan-out retains bounded metadata, errors, and stderr for up to 64 instances, prioritizing failures; `instanceCount` and `instancesTruncated` identify the total and omitted instances. When private tokens exist and an operation or instance record reports capture/detail truncation (such as `stderrTruncated` or `stderrArtifactTruncated`), that record's free-form failure details are also omitted to avoid leaking a split short secret's prefix/suffix. Without private tokens, bounded previews can remain available. Primitive arrays redact both the complete list rendering and individual elements within the same node/token budgets. Returned DB failures extract a safe summary from native `result.error` (`type`, bounded/redacted `message`, `sqlState`, `vendorCode`, and safe cancellation metadata), retain it as DB evidence `error`, and use it for the collector's `error`; rows, parameters, SQL text, and raw results are omitted. Failed command `stdout` can remain as separate diagnostic evidence under the same bounded/redacted/omission policy as `stderr`; it is not used as `error.message` or restored as the failed `result`. MQ resource nodes and error summaries retain `completionCode`, `reasonCode`, and bounded symbolic `reason`. Safe location metadata includes HTTP `method` and the `url` origin (scheme/host/port only), and MQ `queueManager`, `physicalInstance`, `host`, `port`, `channel`, and `transport`. HTTP evidence does not carry resolved request inputs, so failed collector URLs always omit path, query, fragment, and user info, with `urlPathOmitted` identifying omitted components; no raw input is added. URLs that cannot be safely parsed or exceed the budget are omitted with a safe marker.

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
