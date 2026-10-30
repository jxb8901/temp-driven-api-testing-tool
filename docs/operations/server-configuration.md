<!-- att-doc-status: current release=4.2.7 -->

# ATT Server configuration

## Configuration

The Server validates `min(workers.maxConcurrent, workers.maxConcurrentLoad) <= 4`. Each Worker can reserve up to 64 initial DB/MQ sessions, so one Server process admits at most four executing LOAD Workers and caps Server-submitted LOAD preparation at 256 sessions. The bound covers LOAD jobs admitted by this Server process only. RUN/Debug, CLI or direct Engine processes, and other Server instances are not coordinated by its admission semaphore; budget their sessions separately when they share a backend.

`server.dataDir` stores H2 control-plane metadata and job output. Worker concurrency, queue size, Load admission, graceful stop timeout, and optional per-Worker heap limits are bounded by `workers`. `workers.heapMaxMb` sets `-Xmx` for every Worker (64–65536 MiB); optional `heapInitialMb` sets `-Xms` (32–65536 MiB), requires `heapMaxMb`, and cannot exceed it. Plan the aggregate heap allowance against `maxConcurrent` plus the Server and container memory. Rejected submissions are discarded before they create durable job records. Terminal job metadata, journals, and artifacts are retained for `server.jobRetentionDays` (default 30, allowed range 1–3650); expired jobs are cleaned at startup and hourly, while active jobs are preserved. Age retention is independent of the optional size quota. `server.jobStorage` is disabled unless `enabled: true`. When enabled, `maxTotalSizeMb` and a strictly smaller `cleanupTargetSizeMb` are required (each 1–10485760 MiB). The Server measures only `dataDir/jobs`, without following symbolic links and without counting the H2 database. If that measurement cannot be completed, nothing is deleted. While usage is above the high watermark, the oldest terminal Jobs are removed until usage is at or below the cleanup target: `finishedAt` ascending, then `jobId` ascending. Running, queued, in-memory, quarantined, artifact-recovery, archive-reader, and Jobs newer than `minRetentionHours` (default 24) are kept. `minRetentionHours` applies only to size-based eviction. Age expiry still uses `jobRetentionDays` alone and can remove a Job that size eviction would keep. Each eviction removes the Job directory and its H2 row; a Job marked deleting is hidden from job lists, including the LOAD list, and from get APIs until deletion finishes. A restart that completes that deletion records one `JOB_STORAGE_EVICT` audit. `maxSingleJobSizeMb` is rejected because a single large Job is not deleted only for its size. The quota does not compact H2. A sweep that reaches its candidate budget while more deletable Jobs remain is reported as `partial` and is not `quota_unmet`. The next sweep resumes after the last candidate examined. That resume point is discarded when the anchor Job's finish time changes, when the candidate list has been passed, or when usage is back at or below the high watermark. A resumed sweep that reaches the end of a pass which already included the oldest candidate reports `quota_unmet` when usage is still above the target. Quarantined Jobs and Jobs that still need artifact recovery are omitted from the candidate pages, so a protected prefix cannot consume the budget. `quota_unmet` means the sweep finished the candidate list and usage is still above the target because only protected Jobs remain. Authenticated administrators can read the latest sweep from `GET /api/v1/metrics` under `jobStorage`. `workers.maxConcurrentLoad` bounds admitted Load jobs, including queued jobs, so waiting Loads do not occupy general Worker threads. Excess Load or overall-capacity submissions receive HTTP 429. `workers.libraryDirs` optionally lists absolute, existing, readable directories whose JARs are added to the Worker subprocess classpath for external JDBC, MQ, or other dependencies; configure only trusted server-owned directories. The `packages` registry is read-only and maps stable package IDs to canonical roots beneath `allowedRoots`. `server.inspection` separately bounds read-only resource discovery with a pool of long-lived Worker processes. The pool has at most `maxConcurrent` processes; each handles one request at a time and caches up to two package indexes. Package file events invalidate those indexes. Inspection has separate queue, timeout, heap, source, and response limits. With `sourceBrowsing.policy: safe-package-files`, eligible non-sensitive package-local text is available without per-file allowlists. This includes bounded Template and Flow YAML projections, Tool scripts, ordinary configuration and Testdata descriptor files, and eligible `&{...}` targets reached from an authorized source. The `config/` tree is the only directory listing. Every linked file uses a Server-issued opaque reference ID bound to its package and origin; path and origin alone cannot authorize navigation. Under `allowlist-only`, shared config source paths in `server.inspection.safeTextSources` are ATT_HOME-relative; Package source paths remain package-relative. External scripts require a canonical exact file path under `sourceBrowsing.externalTextSources.<packageId>` and a configured canonical `externalSourceRoots` directory; wildcards are not accepted, external files are not listed, and responses hide the filesystem path. Sensitive paths, denied files, binary files, package-root escapes, and oversized files remain unavailable; credential and personal-data values are protected in every mode. `redaction.enabled` defaults to `false`, so ordinary package values are shown in safe projections. Credentials, authentication material, private keys, and common personal-data fields remain protected even when masking is off or the caller is anonymous. Enabling redaction also masks configured paths, field patterns, and free-form defaults. The Server warns at startup when masking is off, and the UI reports the active setting. For deployments with shared or production data, enable masking and require authenticated access; use unmasked values only on an isolated SIT network.


`server.authorization.adminRole` and `userRole` are distinct container roles. A `USER` operator role grants access to package browsing, execution, jobs, and schedules; the `ADMIN` role grants Settings and account/system administration only. Accounts with both roles retain the admin-only policy. In local-auth mode, ATT applies the equivalent rule to active `USER` and `ADMIN` accounts. `allowAnonymousOperations` is available only when `authenticationRequired: false`; it grants anonymous callers the same operational API access as a USER and should be enabled only on a trusted isolated network. Health and version endpoints remain public.

Every Package must be explicitly listed under `packages.entries`. A directory below an allowed root is never registered because its name matches a user account. Package IDs are ordinary labels; matching a login ID grants no ownership or extra permissions. Each Package resolves business resources only from its own root, while all packages use the same trusted runtime configuration and schema registry. To add or remove a Package, update `packages.entries` in the Server configuration and restart the Server.

```yaml
server:
  authorization:
    adminRole: ATT_ADMIN
    userRole: ATT_USER
    allowAnonymousOperations: false
  runtimeConfigPath: /opt/att/config/config.yaml
packages:
  allowedRoots:
    - /opt/att/packages
  entries:
    default: /opt/att/packages/default
    alice: /opt/att/packages/alice
```

On older releases, a YAML entry in `server.inspection.safeTextSources` could stop Tomcat with `Inspection source allowlist accepts text script files only`. To restore an older release, remove that entry before restarting. The default `safe-package-files` policy makes eligible shared config YAML available through the Configuration view. Keep exact ATT_HOME-relative entries only when using `allowlist-only`.

Inspection caching, the optional `server.inspection.warmup` settings, lifecycle behavior and metrics are specified in the [operations Reference](../reference/ci-packaging-operations.md#studio-inspection-caches).

The optional `server.inspection.configurationAdminRole` (default `ATT_ADMIN`) controls `includeAllFields=true` on Effective and Compare configuration views. In local-auth mode only a local `ADMIN` can request these views. The option adds non-secret DB properties, evidence SQL/parameters, and otherwise-masked DB operational settings; it is not a full-disclosure switch. Usernames, passwords, tokens, personal data, HTTP header maps, MQ evidence payloads, SSH connection details and identity-file paths, classified fields, and configured-sensitive values remain protected. Source remains a safe-source projection, while Declared reports declaration status and counts. Protected values stay hidden across the views. These responses are marked `Cache-Control: no-store` and audited.

`server.security.checkCrossOrigin` defaults to `false` and controls the Origin-equality check on state-changing API requests. Setting it to `true` rejects mismatched Origins with `ATT-SERVER-ORIGIN-MISMATCH`. Disabling this check does not disable CSRF validation for local cookie-authenticated mutations, relax local login/bootstrap origin checks, enable CORS, or add wildcard headers. Behind a reverse proxy, configure Tomcat `RemoteIpValve` with trusted proxy addresses so the Servlet request exposes the external scheme, host, and port; the application does not trust arbitrary forwarded headers. The Server warns at startup when origin checking is disabled.

`server.security.trustedOrigins` is an optional exact allowlist for known browser gateways. It accepts at most 100 origins; an empty or omitted list preserves existing behavior. Configure trusted Tomcat proxy addresses and the external HTTPS scheme/host/port first. The allowlist handles a deliberate difference between the browser Origin and the Servlet origin; it cannot make an HTTP request secure.

```yaml
server:
  security:
    checkCrossOrigin: true
    trustedOrigins:
      - https://att.example.com
      - https://att-gateway.example.com:8443
```

Origins compare by scheme, case-insensitive hostname/IP, and effective port (`https` defaults to 443). Configuration may have a trailing `/`; request Origins must have no path. Startup rejects paths, queries, fragments, userinfo, wildcards, malformed ports, `null`, duplicate equivalent origins, and public HTTP origins. HTTP is allowed only for `localhost`, `127.0.0.1`, or IPv6 loopback development. No DNS lookup, prefix matching, suffix matching, or automatic forwarded-header trust occurs.

| Browser Origin | Ordinary mutation, check enabled | Ordinary mutation, check disabled | Login/bootstrap, either setting |
| --- | --- | --- | --- |
| Same Servlet origin or exact trusted entry | Passes Origin policy | Passes Origin policy | Passes Origin policy |
| Different unlisted origin | Rejected | Origin check skipped | Rejected |
| `null`, malformed, or multiple Origins | Rejected | Origin check skipped | Rejected |
| Missing Origin (REST/CLI compatibility) | Passes Origin policy | Passes Origin policy | Passes Origin policy |

The shared policy applies to local and container authentication wherever Origin equality is checked. Rejection uses `ATT-SERVER-ORIGIN-MISMATCH` and an `ORIGIN_REJECT` audit record without raw Origin or credentials. Passing Origin policy grants no permissions: local cookie and Basic mutations still require `X-ATT-CSRF`; authentication, roles, HTTPS credential transport, rate limits, Secure/HttpOnly/SameSite cookies, and draft ownership remain enforced. Active USER accounts share eligible packages, runs, jobs, results, artifacts, and schedules. This does not configure CORS, send `Access-Control-Allow-Origin`, or enable cross-site cookies. True cross-site browser API access requires a separate CORS design.


Browser-created Quick and Advanced Load drafts have a separate opt-in: `server.inlineLoad.enabled` defaults to `false`. When enabled, the Server enforces the configured workload, target, total Virtual User, aggregate Arrival Rate, per-workload and aggregate concurrency, and total timing-envelope caps after Engine validation and again before queue admission. The [Server configuration example](#configuration) sets conservative defaults. The duration cap is the sum of ramp-up, warmup, measured duration, and ramp-down; target count includes each fixed target and each VU mix entry. A disabled feature returns `403 ATT-SERVER-INLINE-LOAD-DISABLED`. An over-limit draft returns `400 ATT-SERVER-INLINE-LOAD-LIMIT` with a summary naming the exceeded setting. These limits apply to browser-created drafts; existing path-based Load submissions retain their compatibility behavior and continue to use `workers.maxConcurrentLoad` admission.

Recurring package-backed Load schedules use a separate opt-in: `server.scheduling.enabled` defaults to `false`. Schedule APIs use the operational role from `server.authorization.userRole` (or an active local `USER` account); the admin role does not grant schedule access. When authentication is disabled, `server.authorization.allowAnonymousOperations` is the single setting that grants shared access to operational APIs, including schedules. The legacy scheduling `adminRole` and `allowAnonymousManagement` settings are accepted for config compatibility, but do not change authorization. The feature requires bounded package inspection and stores schedule definitions and execution history in H2 under `server.dataDir`; keep one Server process on that data directory. The latest 10,000 terminal occurrences per schedule are retained by default (`maxExecutionsPerSchedule` can be set from 100 to 100,000); active and in-progress occurrences are preserved. This scheduler does not claim multi-instance leader election.

Schedules can refer to a package-relative Load scenario file or snapshot a live, validated Quick/Advanced Load draft. Draft snapshot creation requires the same resolved principal, package, and environment as the draft; Debug drafts cannot be scheduled. The bounded Inspector validates and normalizes the full scenario and target closure before saving and again before every occurrence. The schedule stores the normalized scenario privately and returns only `inlineSnapshot: true`; schedule API views do not include the scenario contents. Inline snapshots cannot be replaced or edited in place, and cannot use separate Load overrides; create a new schedule from a new validated draft to change its workload. Server Load user, rate, duration, workload, and target caps apply even when browser-created Load drafts are disabled. Each submitted job follows the regular Worker and Load admission path, so Load concurrency and queue limits still apply. Clients cannot provide output paths, package roots, Worker settings, credentials, or a principal identity. Schedule-owned jobs use a Server-generated `load-schedule:<id>` principal. A failed submission is recorded and is not retried. A Load job accepted by the Server has its own standard job status and evidence, separate from its schedule occurrence.

Manual Run Now uses the active caller as its execution identity; recurring occurrences use the schedule's persisted execution identity. Each accepted occurrence audits that identity. Schedule-owned Load and Debug jobs keep the Server-generated `load-schedule:<id>` principal so shared schedule history remains available. In local-auth mode, an unmigrated schedule with a missing or disabled creator fails closed, and its execution identity must remain an active USER. A legacy schedule created by an active ADMIN can be adopted the first time an active USER triggers it: the Server keeps the original `createdBy`, records the migration actor and time, and assigns that USER as the recurring execution identity. A missing or disabled legacy ADMIN cannot be adopted; after adoption, the assigned USER's active status governs future execution. Sensitive RUN/Debug values remain excluded from saved schedules by the existing draft validation rules.

The UI offers daily and weekly local-time choices, plus five-field cron (`minute hour day-of-month month day-of-week`) with `*`, lists, ranges, and `/step`. Names and extended cron syntax are rejected. When both day-of-month and day-of-week are restricted, either may match. The timezone must be an IANA zone ID. A nonexistent local minute during a spring-forward gap is skipped; during a fall-back overlap, a matching local minute runs once for each real instant. Occurrences must be at least `minimumIntervalMinutes` apart (default 15; allowed 1–10080). Missed firings after restart or a delayed poll are advanced directly to the next future instant and coalesced into one `skipped` history/audit row per schedule and polling tick with reason `misfire-no-backfill`; missed occurrences are not replayed as Load jobs. A polling tick submits at most one scheduled occurrence per schedule. Each schedule's `maxConcurrentRuns` defaults to 1 and cannot exceed the global `server.scheduling.maxConcurrentRuns`. The default per-schedule behavior skips a run while its active-run cap is reached. The global `maxConcurrentRuns` cap (default 1, and never above `workers.maxConcurrentLoad`) limits scheduler-submitted jobs. Capacity and package validation rejections become `failed-to-submit` and are never retried. `failurePolicy: continue` (default) leaves the schedule enabled; `pauseAfterSubmitFailure` pauses it after the first failed submission. This policy concerns submission errors; a submitted job's later result remains an ordinary job outcome. Pausing, editing, or deleting a schedule does not cancel a job already accepted.

The built-in Scheduled Load page supports create, edit, pause/resume, manual trigger, delete, timezone-aware next-run preview, and occurrence history linked to standard jobs. Manual triggers require browser confirmation, obey the per-schedule/global active-run and capacity limits, and are rate-limited to the configured minimum interval.

The enabled `/metrics` response includes the next due time, active scheduled Loads, and retained occurrence counts by status. Occurrence history and the audit table record submission, skip, capacity, and misfire outcomes; mutation and manual-trigger actions include the resolved caller. The scheduler uses the Server host's wall clock and the configured IANA timezone; keep the host clock synchronized with NTP. A forward clock adjustment is handled like a misfire and is recorded without backfill; a backward adjustment delays due work until wall time reaches its stored next instant. For a WAR upgrade or Tomcat restart, use the normal single-instance deployment process: the scheduler stops with the runtime, accepted jobs follow standard shutdown recovery, and missed occurrences are skipped after restart. Do not overlap old and new Server processes against the same `server.dataDir` or use rolling multi-instance deployment for this single-node scheduler.

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
  inspection:
    enabled: true
    configurationAdminRole: ATT_ADMIN
    maxConcurrent: 2
    queuedLimit: 16
    timeoutMs: 30000
    heapMaxMb: 512
    maxResponseBytes: 262144
    maxSourceBytes: 65536
    # Optional asynchronous preload. Keep at most two keys per warmup Worker.
    warmup:
      enabled: false
      workers: 1
      keys: []
      # keys:
      #   - packageId: payments
      #     environment: SIT
    sourceBrowsing:
      enabled: true
      policy: safe-package-files
      denyGlobs: []
      # Optional canonical external roots and exact per-package files.
      # externalSourceRoots:
      #   - /srv/att/external-scripts
      # externalTextSources:
      #   payments:
      #     - /srv/att/external-scripts/mq/check-queue.sh
    redaction:
      enabled: false
      sensitivePaths: []
      sensitiveFieldPatterns: []
    # Legacy exact package-relative allowlist; used only with policy: allowlist-only.
    # Entries are never reinterpreted as external filesystem paths.
    # safeTextSources:
    #   payments:
    #     - tools/payment-check.sh
  security:
    checkCrossOrigin: false
  inlineLoad:
    enabled: false
    maxWorkloads: 10
    maxTargets: 20
    maxTotalUsers: 100
    maxAggregateArrivalRatePerSecond: 100
    maxConcurrentPerWorkload: 100
    maxDurationSeconds: 3600
  scheduling:
    enabled: false
    allowAnonymousManagement: false
    adminRole: ATT_ADMIN
    maxSchedules: 100
    minimumIntervalMinutes: 15
    pollIntervalSeconds: 10
    maxConcurrentRuns: 1
    failurePolicy: continue
    packageChangePolicy: strict
    maxExecutionsPerSchedule: 10000
workers:
  maxConcurrent: 8
  queuedLimit: 100
  # Maximum admitted Load jobs, queued or executing.
  maxConcurrentLoad: 2
  gracefulStopMs: 10000
  # Optional bounds applied separately to each Worker subprocess.
  # heapInitialMb: 256
  # heapMaxMb: 1024
  # Optional external Worker dependency JAR directories.
  libraryDirs:
    - /opt/att/worker-libs
packages:
  allowedRoots:
    - /srv/att/packages
  entries:
    payments: /srv/att/packages/payments
```

Inline Load settings use these defaults and hard ranges:

| Setting | Default | Allowed range | Enforcement |
| --- | ---: | ---: | --- |
| `maxWorkloads` | 10 | 1–128 | Workloads per draft |
| `maxTargets` | 20 | 1–256 | Fixed targets plus VU mix entries |
| `maxTotalUsers` | 100 | 1–100,000 | Sum of Virtual Users across workloads |
| `maxAggregateArrivalRatePerSecond` | 100 | greater than 0 to 1,000,000 | Sum of Arrival Rate workloads, normalized to requests per second |
| `maxConcurrentPerWorkload` | 100 | 1–1,000,000 | Each Arrival Rate workload |
| `maxTotalConcurrent` | 1,000 | 1–1,000,000 | Sum of `maxConcurrent` across Arrival Rate workloads |
| `maxDurationSeconds` | 3,600 | 1–864,000 | Maximum effective LOAD envelope: ramp-up + warmup + duration + ramp-down, up to 10 days |

`dataDir` must be absolute. Package roots, allowed roots, and configured Worker library directories must exist at startup. Package paths are canonicalized; symlinks that resolve outside an allowed root are rejected. Clients submit `packageId`; they cannot select a package path, output path, Worker executable, or classpath. Package registration and mutation endpoints are not available in v1.

Server state is stored in `dataDir/db/`. Each job uses `dataDir/jobs/<jobId>/` for its bounded event journal and execution output. A restarted Server marks old queued or active jobs `ERROR` with `ATT-SERVER-INTERRUPTED`; it does not rerun them.

### Event journal limits and disk-full recovery

`server.maxEventsPerJob` limits retained records (default 10,000; 100–1,000,000). `server.maxEventJournalBytes` independently caps a new or healthy on-disk journal (default 64 MiB; 1–256 MiB). Each event payload remains capped at 65,536 JSON-encoded UTF-8 bytes, each input line is capped at 131,072 bytes, and each in-memory Job journal accounts for at most 16 MiB including per-record overhead. Oversized payload measurement stops at the byte limit without allocating the complete JSON representation. The runtime divides a 128 MiB accounted memory allowance across `workers.maxConcurrent + workers.queuedLimit` (minimum 1 KiB per Job). Each journal keeps its newest event even if it exceeds that share; the excess is bounded by one line and reported in `eventJournal.activeMemoryOverBudgetBytes`. Background compaction starts when obsolete bytes reach at least the larger of 4 KiB and the retained-window size capped at one quarter of the journal byte budget; the byte ceiling still triggers synchronous recovery. It writes a bounded temporary file, checks its SHA-256, and replaces the original only with an atomic same-filesystem move. The compactor never builds the whole snapshot as one string.

The Server rehydrates at most the last configured journal-byte window and skips lines over the line limit. Terminal journals are reused through an LRU cache bounded to 16 Jobs and 128 MiB of accounted retained memory. `GET /api/v1/metrics` includes active-job journal bytes, retained records/memory, compaction attempts/failures, degraded-job count, and terminal-cache size. The Job event stream emits a `diagnostic` event when persistence is degraded or the journal approaches 80% of its byte cap.

An existing journal already larger than its configured cap is not rewritten at startup: only its bounded tail is exposed, the original file is preserved, and that Job's journal persistence is marked `DEGRADED`. A malformed final line, oversized record, or unverified sequence also produces a Job-local history diagnostic. When storage headroom falls below 8 MiB plus the next record, the Server stops writing that journal, keeps bounded in-memory delivery available, and retries with increasing delays up to five minutes. This is a headroom check, not a filesystem reservation; concurrent writers can still exhaust storage. The job's execution status/result remains separate from the journal warning.

For disk exhaustion, first identify the exact large file and filesystem before attributing growth to `events.jsonl`; the reported 6.3 GB incident path has not been confirmed by this source change. Preserve or copy the original file to separate storage before repairing it, restore free space, then inspect `journalPersistence`, `journalBytes`, and compaction diagnostics in the Job stream/result and `GET /api/v1/metrics`. Capture the journal path, size and growth interval, free bytes/inodes, `server.maxEventsPerJob`, `server.maxEventJournalBytes`, JVM heap settings, GC/OOM logs, and related Worker/archive errors. Do not delete a Job directory or journal before confirming a separate verified backup.

### Artifact archive compression

`server.artifacts.compression.mode` selects `store` (default), `deflate`, or `auto`; `level` accepts 1–9 and defaults to 3. STORED keeps the earlier archive format. DEFLATE and AUTO compress each approved entry once into a bounded private staging file. AUTO leaves files under 256 bytes stored and uses DEFLATE only when it saves at least 5%. Any entry whose compressed expansion ratio would exceed the verifier's 200:1 limit falls back to STORED, including in explicit DEFLATE mode. The existing 16 MiB per-entry, 128 MiB total, count, CRC/SHA, path, and atomic publication checks remain in force. Archive metrics record the configured/effective modes, level, payload sizes, savings, duration, entry methods, and bounded fallback reasons.
