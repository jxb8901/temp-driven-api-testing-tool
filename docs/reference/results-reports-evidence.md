# Results, reports, and evidence

## ATT Server Job artifact publication

Worker files remain private until the Server's publication step approves them. Artifact producers apply their sensitive-data protections before writing files. The archive is not a redaction boundary: it validates paths, file types, sizes and UTF-8 text, then preserves captured producer bytes exactly. This boundary applies to Native RUN (including Debug diagnostics), LOAD, and their Server Job artifacts.

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

`export-manifest-v1.json` uses manifest version `1` and policy `att-export-approved-bytes-v1`; entries use decision `preserved`. Approved producer bytes are preserved exactly; producers must mask sensitive values before emitting artifacts. Each entry records its logical path, media type, exact byte size, SHA-256, report/preview eligibility, classification and publication decision. `complete` and aggregate `omissions` describe excluded output without rejected filenames or values. ZIP, preview and extracted files use the same captured bytes; GET requests never independently redact them.

Inventory is independent of `server.maxArtifacts`: up to 50,000 filesystem candidates, depth 64, 10,000 published entries, 16 MiB per entry and 128 MiB total published bytes. The manifest is limited to 8 MiB. Crossing aggregate limits fails publication and preserves private source output. The default ZIP method is STORED; server configuration may select bounded per-entry DEFLATE or AUTO while respecting the 200:1 ratio limit. Readers reject unsafe paths, control characters, encoded traversal, duplicate/case-colliding names, oversized or high-ratio entries, unmanifested entries and integrity mismatches. Publication uses one writer with a bounded queue; at most eight artifact reads run concurrently.

Readers also check the ZIP central directory against local headers before trusting the manifest. Link, special-file and Windows reparse attributes, file/directory prefix collisions, filename-override extra fields, comments, hidden prefixes or records, overlapping entries, multipart archives and ZIP64 are rejected. The bounded export profile permits STORED or Deflate entries and timestamp-only extra metadata; STORED remains the default. Manifest parsing rejects duplicate keys, trailing documents, fractional versions/sizes, unknown fields and non-policy omission text. A structurally readable ZIP is not sufficient proof of an approved export.

ZIP downloads use a request-private copy in the JVM's OS temporary directory. The Server copies at most 140 MiB per download, verifies the complete copy and all entry digests before setting download headers, and streams from that same open file with its captured length. Each 64 KiB buffer is checked against its captured SHA-256 before writing, so later replacement or in-place modification of the published pathname cannot substitute response bytes. The existing eight-reader limit also bounds concurrent download copies; allow temporary disk space for these copies. Normal completion, disconnect and failed approval close/delete the copy. Abrupt process termination can leave temporary files for OS or operator cleanup. Entry previews retain their separate request-scoped verified ZIP handle and per-entry digest/CRC checks.

### Detach and restore a Job console

Studio can open a non-modal floating console from Live Output, Events, or the Job detail header. It remains mounted while the user navigates elsewhere in Studio, and one console is allowed per browser tab. Switching to another Job requires explicit confirmation. Minimize leaves a bottom chip and keeps one bounded per-tab SSE subscription without rendering hidden event rows; Close releases the subscription. Neither action cancels the Job. The console can be moved and resized with pointer or keyboard controls and becomes a full-screen sheet on small viewports.

**Open full Job Details in a new tab** uses the encoded same-origin route `#/jobs/{jobId}?standalone=1&tab=events` with `noopener noreferrer`. It opens Overview, Live Output, Events, Results, Artifacts and Metrics, preselecting the originating tab. Old `/console?view=live|events` bookmarks remain compatible. Switching tabs preserves the Job ID and shared SSE subscription. Refresh, direct links and non-root context paths remain supported. Unauthorized or missing Jobs use the existing sign-in or safe-unavailable message. Floating console behavior remains unchanged.

### View and download a Job archive

In Job Details, open **Artifacts**. Reports appear independently of listing pagination. Choose **View Report** or **Preview** for read-only text with search, copy and line numbers. Text rendering is capped at 200,000 characters. Use **Load more artifacts** for subsequent pages. **Download Artifacts (.zip)** is the sole download action. Extract the ZIP and open its HTML report for offline browsing; relative links preserve the same archive hierarchy.

The authenticated archive index returns `archiveSizeBytes` only for a READY, verified ZIP. Studio shows its human-readable size beside the ZIP download; it does not download the ZIP or make a HEAD request to calculate size.

The index also reports full-archive entry counts and uncompressed bytes for summaries/manifests, metrics/time series, execution evidence, recovery checkpoints, and other files. Studio keeps the primary report visible, groups the remaining entries in collapsed categories, nests execution evidence under workload and execution groups when those path components are available, and searches the loaded pages by path or workload; load more pages to extend a search. Entry previews show compact filenames and keep the full approved archive path available in the row.

The Server validates report completeness independently from execution status, performance verdict and archive status. Routine recovery checkpoints are omitted only when the canonical report is verified complete and `executionStatus: PASS`; a `NOT_MET` performance verdict remains separate and does not change this policy. Cancelled or runtime-error runs retain up to the latest two integrity-verified checkpoint generations per scope even if they committed a complete diagnostic report. If the report is absent, incomplete, corrupt, interrupted, or unavailable, up to the latest two verified generations per scope are retained. An incomplete or corrupt report bundle is omitted as a bundle, while verified checkpoints remain previewable and the archive reports its omissions. Source checkpoints are not deleted before report-commit validation.

When identical large aggregate and single-workload series share detail files, the versioned manifest reports how many files were reused and the serialized bytes saved. Values that differ or belong to multi-workload runs keep their separately scoped files.

**Artifact archive metrics** appear under **Jobs and results → Metrics**, including terminal ERROR Jobs without Load results. Both Job detail and archive index expose the typed `archiveMetrics` object (version 1), persisted independently of Worker execution metrics. Attempts/retries survive restart; timings and resource measurements describe the latest writer attempt. Queue wait ends when the writer starts; `publicationWallMs` includes the writer's finalization and cleanup, not queue wait. Timing buckets cover scan, validation and byte capture, ZIP compression/write, verification/file-fsync, and private/staging cleanup. Regular source file count/bytes include rejected regular files; included/omitted counts describe the attempted publication. The ratio is approved uncompressed entry bytes divided by complete ZIP bytes (including the manifest), not percentage space saved. Source byte sums saturate at the signed 64-bit maximum.

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

After a failure, keep the private output and use **Retry archive** when available. Concurrent retries are coalesced; a retry accepted while the previous writer is finishing is retained for one follow-up publication instead of being silently dropped. Startup and five-minute reconciliation use a persisted recovery-needed flag and a cursor, scheduling at most 64 unfinished terminal Jobs per pass on maintenance isolated from retention. Successful ZIP verification plus loose-output/staging cleanup clears the flag; Retryable FAILED or interrupted work remains eligible; quarantined Jobs are excluded. Existing history is enrolled once during database migration and processed in these bounded batches, not rescanned after every successful publication. Recovery validates a ZIP committed before its status update and removes abandoned staging and leftover loose output. It never serves unarchived loose files. A missing private source cannot be reconstructed from a corrupt ZIP; the archive remains unavailable.

A terminal Worker RESULT is not an execution-cleanup fence. Publication waits until the in-memory execution has finished and its admission lease is released. The Server retains up to 1,024 observed process identities per Job, including children seen before terminal delivery and cancellation/error cleanup. Graceful stop and forced escalation use those same identities even after a parent exits; admission and publication remain fenced until all observed processes have exited. Enumeration/identity-persistence failure never authorizes publication. After every known process has independently been confirmed stopped, the Server persists a quarantine before releasing admission (including LOAD capacity) and removing the active Job fence. Bounded one-second reconciliation retries deferred release, including transient quarantine-store failures; until the quarantine is durable, admission remains held. The quarantine survives restart and blocks both publication and retention, with archive reason `worker_unverified`. Archive index/read routes honor the durable quarantine even if filesystem status says READY; ordinary archive retry is disabled. Retry cannot clear it: operators must independently establish complete process containment before any offline recovery; this release provides no API to clear quarantine or export raw output. On restart, publication and retention check both the Worker PID/start time and the persisted observed-process ledger; a matching live process, or a live PID with unknown start identity, prevents publication/deletion. Archive status `FAILED` with reason `worker_active` preserves the private output; retry after the Worker and recorded descendants have stopped. A different known start time identifies PID reuse, not the original Worker. This observed-process fencing does not yet prove containment of arbitrary detached descendants that escape before observation. Full OS-level containment and its cross-platform acceptance remain outstanding.

The optional `server.jobStorage` quota can also remove complete Jobs when `dataDir/jobs` exceeds its high watermark. It is disabled by default, measures only that directory, never follows symbolic links, and skips a sweep when measurement is incomplete. Eviction is oldest-terminal-first and deletes the Job directory together with its H2 row. A partial sweep resumes after the last candidate it examined, and quarantined or artifact-recovery Jobs are omitted from those pages so a protected prefix cannot fill the budget. Finishing a pass that already included the oldest candidate reports `quota_unmet` when usage is still above the target. Running, queued, quarantined, recovering, in-use, and Jobs inside `minRetentionHours` stay. The quota does not compact H2 or delete a Job only because that one Job is large. The existing `server.jobRetentionDays` policy expires the complete Job, including its ZIP. Quarantined Jobs are excluded before selecting the bounded expiry page, so their indefinite preservation cannot starve ordinary expired Jobs. Retention defers its sweep while archive readers/writers are active; it does not block the cleanup thread or delete a file in use. Classification and publication outcomes are audited without raw values. Publication failures never delete the only unarchived source.

## Find artifacts for a RUN

A completed Native RUN writes its manifest, event stream, reports, and Case execution evidence under a unique Run directory:

~~~text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
├── workbooks/native-cases.result.xlsx
└── executions/<EXEC.ID>/...
~~~

The standard XLSX is generated output and is independent of test inputs. A Server Job indexes it as a binary download; text preview is disabled. The HTML report and CI outputs retain Definition ID, qualified Case ID, Flow ID, source revisions, execution ID, and available source-record provenance.

## Read a Case result

The HTML report shows Case identity, status, duration, Expected and Actual values, Action results, and bounded execution-log previews. Each Action result includes its final rendered description and status. The setting `report.html.caseLogInlineLimitBytes` controls the preview; `0` keeps only the artifact link. `report/junit.html` provides a human-readable JUnit view.

Every Case execution maps to one JUnit `<testcase>`. PASS has no failure child; FAIL maps to `<failure>`, ERROR and INVALID to `<error>`, and SKIPPED to `<skipped>`. Text is XML-escaped. `report.junit.caseLogEmbedThresholdBytes` controls inline log content; `0` always uses a relative link.

## Read the CI summary

`ci/summary.json` uses `att-ci-summary/v2.1` and records Run identity, environment, timing, aggregate status and counts, duration statistics, Case records, diagnostic counts, report paths, and the input-manifest hash. Native Case entries also record Definition/Flow identity and source revisions. The generated Run manifest uses `att-run/v2.1`.

## Diagnose Tool evidence collectors

An evidence collector is post-operation observability, not the primary Tool result. With `onFailure: continue`, the primary Action may remain `PASS` while the collector is independently recorded as `ERROR`.

~~~yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
~~~

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`. The record contains status, success, invocation ID, result, error, and bounded/redacted operation evidence. Failed collector evidence does not publish raw input, payload, argv, output, resolved command text, or the failed result. See [Limits, Security Guarantees, and Advanced Diagnostics](appendices/limits-defaults.md) for projection rules and numeric budgets.

For retries, inspect `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`. Earlier failed collector records remain available after a later successful attempt; the top-level record follows the final or winning attempt.

## Reproduce a RUN

`run.yaml` records ATT/build identity, runtime information, Environment, timestamps, status, outputs, and SHA-256 inputs for effective configuration, Package files, Tools, SQL resources, Testdata, and schema registry. Use the retained revision and manifest identity to compare runs. `report --run-id <id>` regenerates HTML reports from completed evidence without executing the Package.

## Read Case logs

Structured Case log entries use YAML. ATT preserves embedded text line endings and does not parse or reformat business text. The live console and persisted `case.log` use the same rendered text. Blocks with ERROR, FAIL, or INVALID status use the `【!!!!!】` marker; PASS, SKIPPED, and informational blocks are unmarked.

## Offline SUT Metrics report

A completed LOAD with SUT collection evidence can publish a versioned offline report archive. The publisher verifies its manifest and each normalized observation file, preserves the sealed source archive byte-for-byte, and publishes the report only after verification. The `att-load-report-extension/v2` manifest references `att-load-offline-observations/v1`. Observation records include source, metric, profile, target, UTC window, phase, units, quality, and checksums. Time series include bounded chart previews; business metrics include their value, scope, Workload identity, mapping quality, and unmatched-row count.

When the Job's approved execution definition includes Metric Groups, the report publishes safe group metadata and assignments without raw selectors. Unassigned and ambiguous observations remain listed with diagnostics. The report is usable offline and applies a restrictive Content Security Policy. It does not evaluate performance thresholds. If an `att-load-verdict/v1` record is supplied, the report displays its recorded state and evidence reference without recalculating the verdict. The archive is available through the authenticated Job download route after verified publication.
