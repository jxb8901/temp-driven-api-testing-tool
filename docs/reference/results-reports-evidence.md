# Results, reports, and evidence

## ATT Server Job artifact publication

Worker files remain private until the Server's publication step approves them. Artifact producers apply their sensitive-data protections before writing files. The archive is not a redaction boundary: it validates paths, file types, sizes and UTF-8 text, then preserves captured producer bytes exactly. This boundary applies to RUN (including Debug mode, Case, Template, Flow, Tool and batch), LOAD, and retained legacy Jobs.

After execution ends, the Server asynchronously builds one `artifacts.zip`. Its status is independent of the Job result: `PENDING`, `ARCHIVING`, `READY`, or `FAILED`. A PASS/FAIL/ERROR/INVALID/CANCELLED Job remains terminal while publication runs. On successful verification and atomic publication, loose output is removed. The ZIP is the only persistent artifact payload; metadata, audit, events and archive-status records remain separate.

### Publication and sensitive-data policy

The archive accepts regular, non-symlink UTF-8 text files with these extensions: `txt`, `log`, `json`, `yaml`, `yml`, `xml`, `html`, `htm`, `css`, `csv`, and `md`. Binary data, malformed UTF-8, NUL bytes, nested archives and unsupported formats are excluded. JSON and YAML are treated as producer-authored text during archive publication; the archive does not parse, redact or reserialize them. Rejected filenames are not recorded in the public manifest. Studio displays omissions explicitly; an incomplete export does not claim to contain all output.

| Input | Publication behavior |
|---|---|
| Logs, text, XML, CSV and generated structured results | Producers apply the relevant secret, credential and path protections before writing files. The archive preserves the resulting bytes exactly. |
| Ordinary business values, including names and potentially regulated data | Producer rules preserve values unless they match an explicit sensitive-field or secret-text rule; arbitrary PII detection is not provided. |
| HTML | The archive preserves exact producer bytes, including markup, scripts and relative links. HTTP previews use a restrictive sandbox CSP; downloaded HTML is not rewritten to add one. |

Producer-side protections recognize names such as password/passphrase, token, secret, credentials, API/access/private keys, authorization and cookies. Recognized secret text in structured field names is protected too; producer-side collisions after secret/path projection reject the record rather than silently overwriting business fields. Put sensitive business data under an explicitly sensitive field or classify its enclosing structured record as `secret`. An unrelated field containing arbitrary business data is not automatically confidential. Authorization to operate Jobs does not authorize raw-secret export. There is no public raw-export capability or raw-file fallback; producers must protect content before it reaches the archive input tree.

Engine-generated artifact data applies path and secret projection before writing. The archive does not inspect semantic fields or rewrite known paths. Online HTML previews use a sandbox with an opaque origin, a restrictive CSP, no forms and no Studio-origin privileges. Relative links in the downloaded report continue to resolve against the archive's file layout when their targets are present.

### Manifest and resource limits

`export-manifest-v1.json` uses manifest version `1` and policy `att-export-approved-bytes-v1`; new entries use decision `preserved` (legacy `att-export-safe-text-v2` ZIPs remain readable). Approved producer bytes are preserved exactly; producers must mask sensitive values before emitting artifacts. Each entry records its logical path, media type, exact byte size, SHA-256, report/preview eligibility, classification and publication decision. `complete` and aggregate `omissions` describe excluded output without rejected filenames or values. ZIP, preview and extracted files use the same captured bytes; GET requests never independently redact them.

Inventory is independent of `server.maxArtifacts`: up to 50,000 filesystem candidates, depth 64, 10,000 published entries, 16 MiB per entry and 128 MiB total published bytes. The manifest is limited to 8 MiB. Crossing aggregate limits fails publication and preserves private source output. The default ZIP method is STORED; server configuration may select bounded per-entry DEFLATE or AUTO while respecting the 200:1 ratio limit. Readers reject unsafe paths, control characters, encoded traversal, duplicate/case-colliding names, oversized or high-ratio entries, unmanifested entries and integrity mismatches. Publication uses one writer with a bounded queue; at most eight artifact reads run concurrently.

Readers also check the ZIP central directory against local headers before trusting the manifest. Link, special-file and Windows reparse attributes, file/directory prefix collisions, filename-override extra fields, comments, hidden prefixes or records, overlapping entries, multipart archives and ZIP64 are rejected. The bounded export profile permits STORED or Deflate entries and timestamp-only extra metadata; STORED remains the default. Manifest parsing rejects duplicate keys, trailing documents, fractional versions/sizes, unknown fields and non-policy omission text. A structurally readable ZIP is not sufficient proof of an approved export.

ZIP downloads use a request-private snapshot in the JVM's OS temporary directory. The Server copies at most 140 MiB per download, verifies the complete copied ZIP and all entry digests before setting download headers, and streams the same open snapshot with its captured length. Each 64 KiB buffer is checked against its captured SHA-256 before writing, so later replacement or in-place modification of the published pathname cannot substitute response bytes. The existing eight-reader limit also bounds concurrent download snapshots; allow temporary disk space for these copies. Normal completion, disconnect and failed approval close/delete the copy. Abrupt process termination can leave temporary files for OS or operator cleanup. Entry previews retain their separate request-scoped verified ZIP handle and per-entry digest/CRC checks.

### Detach and restore a Job console

Studio can open a non-modal floating console from Live Output, Events, or the Job detail header. It remains mounted while the user navigates elsewhere in Studio, and one console is allowed per browser tab. Switching to another Job requires explicit confirmation. Minimize leaves a bottom chip and keeps one bounded per-tab SSE subscription without rendering hidden event rows; Close releases the subscription. Neither action cancels the Job. The console can be moved and resized with pointer or keyboard controls and becomes a full-screen sheet on small viewports.

**Open full Job Details in a new tab** uses the encoded same-origin route `#/jobs/{jobId}?standalone=1&tab=events` with `noopener noreferrer`. It opens Overview, Live Output, Events, Results, Artifacts and Metrics, preselecting the originating tab. Old `/console?view=live|events` bookmarks remain compatible. Switching tabs preserves the Job ID and shared SSE subscription. Refresh, direct links and non-root context paths remain supported. Unauthorized or missing Jobs use the existing sign-in or safe-unavailable message. Floating console behavior remains unchanged.

### View and download a Job archive

In Job Details, open **Artifacts**. Reports appear independently of listing pagination. Choose **View Report** or **Preview** for read-only text with search, copy and line numbers. Text rendering is capped at 200,000 characters. Use **Load more artifacts** for subsequent pages. **Download Artifacts (.zip)** is the sole download action. Extract the ZIP and open its HTML report for offline browsing; relative links preserve the same archive hierarchy.

The authenticated archive index returns `archiveSizeBytes` only for a READY, verified ZIP. Studio shows its human-readable size beside the ZIP download; it does not download the ZIP or make a HEAD request to calculate size.

The index also reports full-archive entry counts and uncompressed bytes for summaries/manifests, metrics/time series, execution evidence, recovery checkpoints, and other files. Studio keeps the primary report visible, groups the remaining entries in collapsed categories, nests execution evidence under workload and execution groups when those path components are available, and searches the loaded pages by path or workload; load more pages to extend a search. Entry previews show compact filenames and keep the full approved archive path available in the row.

The Server validates report completeness independently from execution status, performance verdict and archive status. Routine recovery checkpoints are omitted only when the canonical report is verified complete and `executionStatus: PASS`; a `NOT_MET` performance verdict remains separate and does not change this policy. Cancelled or runtime-error runs retain up to the latest two integrity-verified checkpoint generations per scope even if they committed a complete diagnostic report. If the report is absent, incomplete, corrupt, interrupted, or unavailable, up to the latest two verified generations per scope are retained. An incomplete or corrupt report bundle is omitted as a bundle, while verified checkpoints remain previewable and the archive reports its omissions. Source checkpoints are not deleted before report-commit validation.

When identical large aggregate and single-workload series are shared, the versioned detail manifest reports the exact number of reused sidecar files and serialized bytes saved. Values that differ or belong to multi-workload runs keep their separately scoped files.

**Artifact archive metrics** appear under **Jobs and results → Metrics**, including terminal ERROR Jobs without Load results. Both Job detail and archive index expose the typed `archiveMetrics` object (version 1), persisted independently of Worker execution metrics. Attempts/retries survive restart; timings and resource measurements describe the latest writer attempt. Queue wait ends when the writer starts; `publicationWallMs` includes the writer's finalization and cleanup, not queue wait. Stage milliseconds cover scan, validation and byte capture, ZIP compression/write, verification/file-fsync, and private/staging cleanup. Regular source file count/bytes include rejected regular files; included/omitted counts describe the attempted publication. The ratio is approved uncompressed entry bytes divided by complete ZIP bytes (including the manifest), not percentage space saved. Source byte sums saturate at the signed 64-bit maximum.

`writerCpuMs` measures only the Server writer thread when JVM thread CPU timing is already supported/enabled. `writerAllocatedBytes` is that thread's allocated-byte delta, not retained memory, JVM heap use, or peak memory. Telemetry does not enable JVM instrumentation or measure Worker CPU. Unsupported/disabled measurements and stages not executed (for example, reusing an existing verified ZIP) are `null` and displayed as Unavailable, never fabricated zeros. Real empty input has zero file/entry counts but a nonempty manifest ZIP. Outcome and failure phase/reason use fixed safe codes, never filenames, data or exception messages. RUNNING metrics identify an unfinished attempt; cleanup failure is CLEANUP_PENDING while an already verified ZIP stays downloadable. A metrics-store failure is logged without changing publication safety or falling back to raw output.

`server.artifacts.compression.mode` defaults to `store`; `deflate` and `auto` are opt-in, with levels 1–9 (default 3). AUTO leaves entries under 256 bytes stored and deflates only with at least 5% savings. Every entry remains subject to the 200:1 expansion-ratio verifier; highly repetitive entries fall back to STORED even in explicit DEFLATE mode. Metrics report configured/effective mode, level, compression duration, stored/deflated counts, compressed payload and ZIP overhead bytes, savings, and bounded fallback reasons.

For extracted `file://` reports, the browser controls how linked file types open. For example, Chromium may download a linked YAML file instead of displaying it. The link still targets the same approved extracted bytes; Studio serves those entries as inline text in its viewer.

| Method | Path relative to `/api/v1` | Result |
|---|---|---|
| GET | `/jobs/{jobId}/artifacts?offset=0&limit=100` | Archive state, paginated entries, primary reports and safe omissions |
| GET | `/jobs/{jobId}/artifacts/archive.zip` | Verified finalized ZIP attachment |
| GET | `/jobs/{jobId}/artifacts/view/{entryPath}` | Manifest-approved HTML/CSS or inline text; directory-preserving relative links |
| POST | `/jobs/{jobId}/artifacts/retry` | Schedule a terminal Job's retry while private source remains available |

Every route requires the shared OPERATE capability. Active USER accounts can access other USERs' approved Job archives; creator identity remains audit metadata. ADMIN-only accounts have no implicit operational access. Authentication, disabled-account checks, local CSRF and configured Origin policy apply. Responses use private/no-store caching; loose-file download routes return 404. Pending or unavailable archive reads return `ATT-SERVER-ARCHIVE-UNAVAILABLE` (409); exhausted read capacity returns `ATT-SERVER-ARTIFACT-CAPACITY` (503).

### Recover and expire archives

After a failure, keep the private output and use **Retry archive** when available. Concurrent retries are coalesced; a retry accepted while the previous writer is finishing is retained for one follow-up publication instead of being silently dropped. Startup and five-minute reconciliation use a persisted recovery-needed flag and a cursor, scheduling at most 64 unfinished terminal Jobs per pass on maintenance isolated from retention. Successful ZIP verification plus loose-output/staging cleanup clears the flag; Retryable FAILED or interrupted work remains eligible; quarantined Jobs are excluded. Existing history is enrolled once during database migration and processed in these bounded batches, not rescanned after every successful publication. Legacy loose-output Jobs use the same publication contract. Recovery validates a ZIP committed before its status update and removes abandoned staging and leftover loose output. It never serves legacy raw files directly. A missing private source cannot be reconstructed from a corrupt ZIP; the archive remains unavailable.

A terminal Worker RESULT is not an execution-cleanup fence. Publication waits until the in-memory execution has finished and its admission lease is released. The Server retains up to 1,024 observed process identities per Job, including children seen before terminal delivery and cancellation/error cleanup. Graceful stop and forced escalation use those same identities even after a parent exits; admission and publication remain fenced until all observed processes have exited. Enumeration/identity-persistence failure never authorizes publication. After every known process has independently been confirmed stopped, the Server persists a quarantine before releasing admission (including LOAD capacity) and removing the active Job fence. Bounded one-second reconciliation retries deferred release, including transient quarantine-store failures; until the quarantine is durable, admission remains held. The quarantine survives restart and blocks both publication and retention, with archive reason `worker_unverified`. Archive index/read routes honor the durable quarantine even if filesystem status says READY; ordinary archive retry is disabled. Retry cannot clear it: operators must independently establish complete process containment before any offline recovery; this release provides no API to clear quarantine or export raw output. On restart, publication and retention check both the Worker PID/start time and the persisted observed-process ledger; a matching live process, or a live PID with unknown start identity, prevents publication/deletion. Archive status `FAILED` with reason `worker_active` preserves the private output; retry after the Worker and recorded descendants have stopped. A different known start time identifies PID reuse, not the original Worker. This observed-process fencing does not yet prove containment of arbitrary detached descendants that escape before observation. Full OS-level containment and its cross-platform acceptance remain outstanding.

The optional `server.jobStorage` quota can also remove complete Jobs when `dataDir/jobs` exceeds its high watermark. It is disabled by default, measures only that directory, never follows symbolic links, and skips a sweep when measurement is incomplete. Eviction is oldest-terminal-first and deletes the Job directory together with its H2 row. A partial sweep resumes after the last candidate it examined, and quarantined or artifact-recovery Jobs are omitted from those pages so a protected prefix cannot fill the budget. Finishing a pass that already included the oldest candidate reports `quota_unmet` when usage is still above the target. Running, queued, quarantined, recovering, in-use, and Jobs inside `minRetentionHours` stay. The quota does not compact H2 or delete a Job only because that one Job is large. The existing `server.jobRetentionDays` policy expires the complete Job, including its ZIP. Quarantined Jobs are excluded before selecting the bounded expiry page, so their indefinite preservation cannot starve ordinary expired Jobs. Retention defers its sweep while archive readers/writers are active; it does not block the cleanup thread or delete a file in use. Classification and publication outcomes are audited without raw values. Publication failures never delete the only unarchived source.

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

Normal Run stores functional Cases under output/<RUN_ID>/executions/<EXEC.ID>/. In Load, EXEC.OUTPUT_DIR remains at output/load/<RUN_ID>/executions/<EXEC.ID>/ throughout the iteration. When retained, a copy of its artifacts is also stored under samples/<EXEC.ID>/ or failures/<EXEC.ID>/. Metrics-only iterations have EXEC.ID but no per-iteration directory after the scheduler releases their temporary workspace. Retained Load rows show EXEC.ID and link to case.log when present. Debug uses its debug ID as both EXEC.RUN_ID and EXEC.ID.

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
