# ATT Server deployment and API

ATT Server 4.2.1 provides a single-node control plane for submitting ATT Run, Debug, Load, and Validate jobs over a versioned REST API. Deploy its WAR to an external Tomcat 10.1+ instance running Java 17 or later. The CLI, Engine, and Worker remain Java 8-compatible.

## Prepare the deployment

1. Extract the Local archive into the installation root and set `ATT_HOME` to that directory. When upgrading, remove the previous `lib/att-local-*.jar` before extracting the new archive so `ATT_HOME/lib` contains one runtime. Runtime configuration is shared from `$ATT_HOME/config/config.yaml`; trusted schemas are bundled with the ATT runtime. A Package contains business resources such as `testcase/`, `templates/`, and package-local scripts, with no required `config/` or `schemas/` directory. `ATT_HOME/config/att-server.example.yaml` remains separate Server deployment configuration.
2. Register package roots beneath an allowed root and ensure the Tomcat service account can read them. The extracted default package can be registered directly as `$ATT_HOME/packages/default`.
3. Create `server.dataDir` before startup and make it writable only by the Tomcat service account. The sample uses `$ATT_HOME/data`; it is supported when it does not equal, contain, or sit inside any registered package root. `/var/lib/att-server` is another option. Keep it out of package content.
4. Copy `$ATT_HOME/config/att-server.example.yaml` to `$ATT_HOME/config/att-server.yaml` or a protected external path, update its absolute paths, and keep credentials out of package content. Do not replace an existing active config during an upgrade.
5. Deploy `att-server-4.2.1.war` to Tomcat 10.1+. Tomcat may run the WAR packed; isolated Workers load the matching `att-local-4.2.1.jar` from `$ATT_HOME/lib`.

The Local archive's extracted directory is `ATT_HOME`, the shared installation and deployment root. It contains launchers and the shared Worker/Engine runtime at `lib/att-local-4.2.1.jar`; install the matching Local archive beside the Server WAR. Keep one versioned `att-local-<version>.jar` in each `ATT_HOME/lib`. Executable package content lives beneath `packages/<id>/`; Server state lives in the separately configured `server.dataDir`. The archive includes `config/att-server.example.yaml` as a template, but never includes an active `config/att-server.yaml` or a `data/` directory.

Server config discovery checks, in order, JVM property `-Datt.server.config`, environment variable `ATT_SERVER_CONFIG`, then `$ATT_HOME/config/att-server.yaml`. For the last candidate, the Server resolves `ATT_HOME` from `-Datt.home` first and then the `ATT_HOME` environment variable. A selected config that is missing, unreadable, or invalid stops startup with its path in the diagnostic; the Server does not fall back to another source. With no explicit source or usable default candidate, startup fails with setup guidance.

Tomcat owns listeners, TLS, access logs, and container authentication. Give its service account read access to ATT_HOME and package roots, and ownership or write access to `server.dataDir`. In container-auth mode, configure `server.authorization.userRole` for active operators and `server.authorization.adminRole` for account and system administration. Operators can use packages, Run, Load, Jobs, and Schedules; administrators use Settings and account management. An account with the admin role does not gain operational API access. Local-auth mode applies the equivalent policy to active `USER` and `ADMIN` accounts. Health and version remain public. Terminate TLS before exposing BASIC credentials. The Server fails startup when its configuration, Java baseline, or package mappings are invalid.

## ATT_HOME layout and migration

A shared install can use this layout:

```text
att/                              # ATT_HOME
  att.sh
  att.bat
  lib/
  config/
    att-server.example.yaml       # Server settings template only
    config.yaml                   # shared Engine/Worker config
    tools/                        # shared Tool definitions
    dbhelpers/                    # shared helper descriptors
    mqhelpers/
    httphelpers/
    sshhelpers/
  schemas/                        # source catalog; trusted copies are bundled at runtime
  data/                           # optional server.dataDir; Tomcat-owned
  packages/default/               # complete package root
    testcase/
    templates/
    testdata/                     # package-local descriptor and data
    tools/                        # package-local scripts
```

To migrate a flat Local installation, back it up first and extract the new archive to a staging directory. Keep one reviewed common `config/config.yaml` and move its shared Tool and helper descriptors into ATT_HOME `config/`. If former installations have different package configs, merge them deliberately; ATT does not choose or merge a package config at runtime. Move trusted schemas to ATT_HOME `schemas/`. Move `testcase/`, `templates/`, `load/`, top-level `tools/`, `sql/`, and Testdata descriptors into each registered Package. Preserve package-local scripts under `packages/<id>/tools/`; these are separate from shared Tool definitions under ATT_HOME `config/tools/`. Exclude `att-server.example.yaml` and `att-server.yaml` when migrating runtime configuration. Keep any active Server config at `ATT_HOME/config/att-server.yaml` or its protected external path. Retain the old flat tree until the migrated Package passes validation and execution. Update each Server registry entry to the resulting Package root and set `ATT_HOME` or `-Datt.home` for Tomcat. Keep Server state outside Package roots and preserve it when replacing or upgrading the install; the Local archive does not contain `data/` or an active Server config.

## Configuration

`server.dataDir` stores H2 control-plane metadata and job output. Worker concurrency, queue size, Load admission, graceful stop timeout, and optional per-Worker heap limits are bounded by `workers`. `workers.heapMaxMb` sets `-Xmx` for every Worker (64–65536 MiB); optional `heapInitialMb` sets `-Xms` (32–65536 MiB), requires `heapMaxMb`, and cannot exceed it. Plan the aggregate heap allowance against `maxConcurrent` plus the Server and container memory. Rejected submissions are discarded before they create durable job records. Terminal job metadata, journals, and artifacts are retained for `server.jobRetentionDays` (default 30, allowed range 1–3650); expired jobs are cleaned at startup and hourly, while active jobs are preserved. `workers.maxConcurrentLoad` bounds admitted Load jobs, including queued jobs, so waiting Loads do not occupy general Worker threads. Excess Load or overall-capacity submissions receive HTTP 429. `workers.libraryDirs` optionally lists absolute, existing, readable directories whose JARs are added to the Worker subprocess classpath for external JDBC, MQ, or other dependencies; configure only trusted server-owned directories. The `packages` registry is read-only and maps stable package IDs to canonical roots beneath `allowedRoots`. `server.inspection` separately bounds read-only resource discovery with a pool of long-lived Worker processes. The pool has at most `maxConcurrent` processes; each handles one request at a time and caches up to two package indexes. Package file events invalidate those indexes. Inspection has separate queue, timeout, heap, source, and response limits. With `sourceBrowsing.policy: safe-package-files`, eligible non-sensitive package-local text is available without per-file allowlists. This includes bounded Template and Flow YAML projections, Tool scripts, ordinary configuration and Testdata descriptor files, and eligible `&{...}` targets reached from an authorized source. The `config/` tree is the only directory listing. Every linked file uses a Server-issued opaque reference ID bound to its package and origin; path and origin alone cannot authorize navigation. Under `allowlist-only`, shared config source paths in `server.inspection.safeTextSources` are ATT_HOME-relative; Package source paths remain package-relative. External scripts require a canonical exact file path under `sourceBrowsing.externalTextSources.<packageId>` and a configured canonical `externalSourceRoots` directory; wildcards are not accepted, external files are not listed, and responses hide the filesystem path. Sensitive paths, denied files, binary files, package-root escapes, and oversized files remain unavailable; credential and personal-data values are protected in every mode. `redaction.enabled` defaults to `false`, so ordinary package values are shown in safe projections. Credentials, authentication material, private keys, and common personal-data fields remain protected even when masking is off or the caller is anonymous. Enabling redaction also masks configured paths, field patterns, and free-form defaults. The Server warns at startup when masking is off, and the UI reports the active setting. For deployments with shared or production data, enable masking and require authenticated access; use unmasked values only on an isolated SIT network.


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

Inspection caching, the optional `server.inspection.warmup` settings, lifecycle behavior and metrics are specified in the [operations Reference](reference/ci-packaging-operations.md#studio-inspection-caches).

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


Browser-created Quick and Advanced Load drafts have a separate opt-in: `server.inlineLoad.enabled` defaults to `false`. When enabled, the Server enforces the configured workload, target, total Virtual User, aggregate Arrival Rate, per-workload and aggregate concurrency, and total timing-envelope caps after Engine validation and again before queue admission. The [Server configuration example](#configuration) sets conservative defaults. The duration cap is the sum of warmup, ramp-up, measured duration, and ramp-down; target count includes each fixed target and each VU mix entry. A disabled feature returns `403 ATT-SERVER-INLINE-LOAD-DISABLED`. An over-limit draft returns `400 ATT-SERVER-INLINE-LOAD-LIMIT` with a summary naming the exceeded setting. These limits apply to browser-created drafts; existing path-based Load submissions retain their compatibility behavior and continue to use `workers.maxConcurrentLoad` admission.

Recurring package-backed Load schedules use a separate opt-in: `server.scheduling.enabled` defaults to `false`. Schedule APIs use the operational role from `server.authorization.userRole` (or an active local `USER` account); the admin role does not grant schedule access. When authentication is disabled, `server.authorization.allowAnonymousOperations` is the single setting that grants shared access to operational APIs, including schedules. The legacy scheduling `adminRole` and `allowAnonymousManagement` settings are accepted for config compatibility, but do not change authorization. The feature requires bounded package inspection and stores schedule definitions and execution history in H2 under `server.dataDir`; keep one Server process on that data directory. The latest 10,000 terminal occurrences per schedule are retained by default (`maxExecutionsPerSchedule` can be set from 100 to 100,000); active and in-progress occurrences are preserved. This scheduler does not claim multi-instance leader election.

Schedules can refer to a package-relative Load scenario file or snapshot a live, validated Quick/Advanced Load draft. Draft snapshot creation requires the same resolved principal, package, and environment as the draft; Debug drafts cannot be scheduled. The bounded Inspector validates and normalizes the full scenario and target closure before saving and again before every occurrence. The schedule stores the normalized scenario privately and returns only `inlineSnapshot: true`; schedule API views do not include the scenario contents. Inline snapshots cannot be replaced or edited in place, and cannot use separate Load overrides; create a new schedule from a new validated draft to change its workload. Server Load user, rate, duration, workload, and target caps apply even when browser-created Load drafts are disabled. Each submitted job follows the regular Worker and Load admission path, so Load concurrency and queue limits still apply. Clients cannot provide output paths, package roots, Worker settings, credentials, or a principal identity. Schedule-owned jobs use a Server-generated `load-schedule:<id>` principal. A failed submission is recorded and is not retried. A Load job accepted by the Server has its own standard job status and evidence, separate from its schedule occurrence.

Manual Run Now submissions execute under the active USER who triggered them. Recurring occurrences use the schedule's persisted execution identity. In local-auth mode, an unmigrated schedule with a missing or disabled creator fails closed, and its execution identity must remain an active USER. A legacy schedule created by an active ADMIN can be adopted the first time an active USER triggers it: the Server keeps the original `createdBy`, records the migration actor and time, and assigns that USER as the recurring execution identity. A missing or disabled legacy ADMIN cannot be adopted; after adoption, the assigned USER's active status governs future execution. Sensitive RUN/Debug values remain excluded from saved schedules by the existing draft validation rules.

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
| `maxDurationSeconds` | 3,600 | 1–86,400 | Sum of warmup, ramp-up, duration, and ramp-down |

`dataDir` must be absolute. Package roots, allowed roots, and configured Worker library directories must exist at startup. Package paths are canonicalized; symlinks that resolve outside an allowed root are rejected. Clients submit `packageId`; they cannot select a package path, output path, Worker executable, or classpath. Package registration and mutation endpoints are not available in v1.

Server state is stored in `dataDir/db/`. Each job uses `dataDir/jobs/<jobId>/` for its bounded event journal and execution output. A restarted Server marks old queued or active jobs `ERROR` with `ATT-SERVER-INTERRUPTED`; it does not rerun them.

## Authentication and identity

`server.auth.mode` selects the authentication provider. It defaults to `container` to preserve existing deployments. Set it to `local` to use ATT-managed accounts. Local mode requires `authenticationRequired: true`; startup rejects a configuration that combines local mode with the legacy anonymous setting.

In `container` mode, Tomcat authenticates requests and ATT uses the Servlet Principal. Existing BASIC, FORM, and SSO deployments keep their container-managed login flow. Health and version remain public. The legacy `server.authenticationRequired: false` option is available only in container mode. It enables shared anonymous access to the UI and API and should be limited to isolated trusted networks. All anonymous callers share drafts, jobs, and artifacts. ATT logs a warning when this mode is enabled.

In `local` mode, `/ui/` redirects unauthenticated browsers to ATT's login page. The REST API returns structured JSON `401` responses. The first administrator is created once using the random token in `dataDir/bootstrap-admin.token`; keep `dataDir` restricted to the Server operating-system account, enter the token on the login page, and remove access to the token file after bootstrap. Token initialization uses a private temporary file and atomic rename; on startup an invalid token is repaired only while no account exists and bootstrap has not been claimed. A claimed bootstrap is never reopened. No default account or password exists. Every request that carries a local password—including login, bootstrap, user creation, password reset, and self-service change—requires HTTPS except for loopback development. Tomcat recognizes proxy-terminated HTTPS only through its configured trusted proxy handling; forwarded headers from untrusted clients do not establish HTTPS. Local passwords are stored as salted PBKDF2-HMAC-SHA-256 hashes. The first release supports `ADMIN` and `USER`; active USER accounts can use operational APIs, while ADMIN accounts can use Settings and account administration only.

Local sessions use opaque server-side identifiers, rotate at login, expire after 30 minutes of inactivity or 12 hours, and are revoked at logout, password change/reset, or account disable. Sessions persist across Server restarts until expiry or revocation. Cookies are HttpOnly, SameSite=Lax, scoped to the deployed context, and marked Secure when Tomcat recognizes the request as HTTPS. Configure the trusted reverse proxy before enabling TLS termination so Tomcat sees the external HTTPS scheme. State-changing cookie-authenticated requests require the session CSRF token in `X-ATT-CSRF`; browser-originated Basic-authenticated mutations also require it. Setting `security.checkCrossOrigin: false` relaxes origin-equality rejection only: it does not bypass cookie CSRF checks or the same-or-trusted-origin JSON requirement for login/bootstrap, and it does not enable wildcard CORS. Eight failed attempts for the same normalized login and client address, or 32 failed attempts from one client address across usernames, are throttled within ten minutes. Bootstrap also allows eight failed token attempts per client address in ten minutes. Self-service current-password verification uses these same attempt limits and the bounded password-check semaphore. These in-memory limits are local to each Server process; use a shared edge rate limit when running multiple instances. Login responses do not distinguish unknown, disabled, and incorrectly authenticated accounts. Successful and failed login attempts are audited without credentials. Password hashes use 310,000 PBKDF2-HMAC-SHA-256 iterations and are upgraded at successful login when a stored hash uses fewer iterations.

`GET /api/v1/auth/me` returns the safe current-user profile and the CSRF token needed by browser clients. `POST /api/v1/auth/login` and `/api/v1/auth/logout` manage sessions. HTTPS Basic authentication is also supported for CLI and REST clients using local accounts. `GET` and `POST /api/v1/auth/users`, `POST /api/v1/auth/users/{loginId}/password`, `POST /api/v1/auth/users/{loginId}/enable`, and `DELETE /api/v1/auth/users/{loginId}` provide administrator account management. `POST /api/v1/auth/password` changes the current user's password after validating the current password. Password hashes, session identifiers, and passwords are never returned in API responses.

In Studio, local `ADMIN` users can manage accounts under **Settings → User Management**. The table exposes only login ID, display name, role, and active state. Create, enable/disable, and password-reset actions use explicit confirmation; disabling or resetting a user revokes that user's sessions. The Server rejects an attempt to disable the last active `ADMIN`, including concurrent requests from separate Server runtimes. Every local user can use **Change password** in the current-user menu. A successful change revokes the current session and returns to the login page. Account passwords are held only in the form while it is open and are not saved to browser storage or application logs. In container mode the Settings page explains that Tomcat or the configured SSO provider owns account management.

### Recover a locked-out local administrator

The one-time bootstrap is not a recovery mechanism and is never reopened. To recover an existing `ADMIN` who has lost the password or was disabled, use the offline recovery tool. This operation cannot create an account and will reject a missing account or a non-`ADMIN` account.

1. Stop every Tomcat process that uses this `server.dataDir` and wait for it to exit. Run the offline recovery command as the Server operating-system account, with write access to the existing `dataDir`.
2. Set `ATT_SERVER_WAR` to the deployed `att-server-4.1.4.war`, `CATALINA_HOME` to the Tomcat installation, and `JAVA_HOME` to Java 17 or later. Extract the WAR to a private temporary directory and launch the tool with its packaged libraries and Tomcat's Servlet API:

   ```sh
   umask 077
   recovery_dir="$(mktemp -d)"
   trap 'rm -rf "$recovery_dir"' EXIT
   (
     cd "$recovery_dir"
     "$JAVA_HOME/bin/jar" xf "$ATT_SERVER_WAR"
     "$JAVA_HOME/bin/java" -Datt.server.config=/absolute/path/to/att-server.yaml \
       -cp "WEB-INF/lib/*:$CATALINA_HOME/lib/*" att.server.AuthRecoveryTool
   )
   ```

3. At the interactive prompts, enter the existing ADMIN login ID, type `RECOVER ADMIN`, and enter the new password twice. Password input is not echoed and is not accepted as a command-line argument.
4. Start Tomcat and sign in with the new password.

The tool updates only the selected, existing `ADMIN` row, reactivates it if disabled, replaces its password hash and CSRF token, revokes all its sessions, and records an `ADMIN_RECOVERY` audit event. It does not modify the bootstrap claim or create a bootstrap token. If no existing database is found, the database cannot be opened, or the selected account is not an `ADMIN`, recovery fails without creating an account. Keep the backup, temporary extraction directory, and `server.dataDir` protected as server credentials.

Active local `USER` accounts share operational access: they can use eligible static and discovered packages, and can view or manage shared jobs and schedules. Draft IDs remain bound to their creating login. `ADMIN` accounts have Settings and account-management access only and receive no Package, Run, Load, Jobs, or Schedules API access. In container-auth mode, access is granted to the configured USER role and excluded for principals with the ADMIN role.

Anonymous job submissions may include an optional top-level `principal` label, for display and audit only. The Server trims and Unicode-normalizes the label, validates its length and characters, and stores it in the existing job/audit `principal` field with the suffix `(anonym)`, such as `Bob(anonym)`. Omitting it keeps the existing value `anonymous`; callers cannot supply the reserved suffix themselves. This label is self-declared and never changes authorization, draft access, job visibility, artifact access, cancellation, or scheduling. Authenticated mode always stores the Servlet Principal and ignores a submitted label. Attribution is not sent to the Worker or exposed in ATT expression Context; no additional persisted or response field is introduced.

State-changing requests require `application/json`; requests carrying an `Origin` must match the request origin. Behind a TLS-terminating proxy, configure Tomcat's `RemoteIpValve` to derive the Servlet scheme, host, and port from the proxy's forwarded headers. Set `internalProxies` to only the actual proxy addresses, and ensure the proxy removes client-supplied `Forwarded`/`X-Forwarded-*` headers before adding its own. For example, adapt these header names and trusted addresses to the proxy:

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

Tomcat's `RemoteIpValve` must trust only the actual proxy addresses. Ensure the proxy removes client-supplied forwarded headers before adding its own. Bind Tomcat to loopback or a private interface and protect the proxy with a VPN, firewall, or IP allowlist. Use HTTPS for traffic outside localhost. Drafts are in memory and are cleared on Server restart. Changing authentication mode requires a restart; existing drafts are not transferred between identities. Local mode does not read or modify JSPWiki account files and does not implement external LDAP, OIDC, SAML, or shared SSO.

The bundled browser console is served at `<context path>/ui/`. It uses the selected container or local authentication mode and provides account management and password-change controls in local mode. See [ATT Server Web UI](att-server-web-ui.md).

## REST API

All endpoints use `/api/v1`. Requests and responses use JSON unless the endpoint is an SSE stream or artifact download.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health`, `/version` | Health, build, and browser Load capability information |
| `GET`, `POST` | `/auth/bootstrap/status`, `/auth/bootstrap` | Check and perform one-time local administrator setup |
| `POST`, `GET` | `/auth/login`, `/auth/logout`, `/auth/me` | Create/revoke a local session and read the current profile |
| `GET`, `POST`, `DELETE` | `/auth/users`, `/auth/users/{loginId}` | List, create, disable, enable, or reset local accounts (administrator) |
| `POST` | `/auth/password` | Change the current local user's password |
| `GET` | `/metrics` | Bounded job, Worker, and enabled schedule counts |
| `GET` | `/packages`, `/packages/{packageId}` | Read the configured registry |
| `GET` | `/packages/{packageId}/resources?type=case&query=...&limit=50&cursor=...` | List safe Case, Template, Flow, and Tool projections |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}` | Read one safe resource definition and references |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source` | Read a sanitized YAML projection or eligible package-local Tool source and discover Server-authorized references |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source-file?referenceId=...` | Read one eligible referenced text file through its opaque resource-scoped reference |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/debug-form?environment=SIT` | Read safe target-scoped Debug defaults |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT` | Read safe defaults and one-workload Quick Load preview |
| `GET` | `/packages/{packageId}/load-policy?model=virtualUsers&environment=SIT` | Read safe model-specific Load policy defaults for Advanced Load |
| `POST` | `/drafts/debug` | Validate a fixed-target Debug input and create an in-memory draft |
| `POST` | `/drafts/quick-load` | Validate a fixed-target Quick Load and create an in-memory draft |
| `POST` | `/drafts/load` | Validate an `att-load/v1.7` scenario and create an in-memory draft |
| `GET` | `/drafts/{draftId}` | Read the safe preview for an owned Debug, Quick Load, or Advanced Load draft |
| `POST` | `/jobs/debug`, `/jobs/load` | Submit an owned Debug, Quick Load, or Advanced Load draft |
| `POST` | `/jobs/run`, `/jobs/load`, `/jobs/validate` | Submit one path-based job |
| `GET` | `/jobs`, `/jobs/{jobId}` | List recent jobs or read job status |
| `GET` | `/jobs/{jobId}/result` | Read the canonical result and diagnostic |
| `GET` | `/jobs/{jobId}/events` | Observe retained and live job events |
| `GET` | `/jobs/{jobId}/artifacts` | List output files |
| `GET` | `/jobs/{jobId}/artifacts/{path}` | Download one output file |
| `DELETE` | `/jobs/{jobId}` | Cancel an active job; retain its history |
| `GET`, `POST` | `/load-schedules` | List schedules or create one (active USER operational access) |
| `POST` | `/load-schedules/preview` | Validate timezone/cron and preview occurrence times only (active USER operational access) |
| `GET`, `PATCH`, `DELETE` | `/load-schedules/{scheduleId}` | Read, edit, or delete a schedule (active USER operational access) |
| `POST` | `/load-schedules/{scheduleId}/pause`, `/resume`, `/trigger` | Pause, resume, or manually trigger (active USER operational access) |
| `GET` | `/load-schedules/{scheduleId}/executions?limit=100` | Read retained occurrence history with links to accepted jobs |

Schedule routes are hidden with `404` while disabled. With scheduling enabled, authenticated callers need the configured `server.authorization.userRole` (default `ATT_USER`) or an active local `USER` account. `ADMIN` has no schedule operation access. The browser/API state-changing origin checks also apply. A schedule creation request is a JSON object such as:

```json
{
  "name": "Nightly checkout load",
  "packageId": "payments",
  "scenario": "load/checkout.yaml",
  "config": "config/config.yaml",
  "environment": "SIT",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "load": { "duration": "10m" },
  "maxConcurrentRuns": 1,
  "enabled": false
}
```

To schedule a validated UI draft instead, replace `scenario` with its `draftId` (the `A...` Advanced Load ID or `L...` Quick Load ID) and provide the same `environment` used during validation. This option is for schedule creation only; later edits cannot replace the stored snapshot.

The preview endpoint checks recurrence and calculates occurrence instants; it does not validate a package scenario, Testdata, or saved draft. `POST /load-schedules` validates the complete scenario and target closure before persisting a schedule. A validation rejection returns HTTP 400 with `error` and a `diagnostics` array. Each available diagnostic can include its code, summary, detail, field, source file and line/column, package ID, environment, resource/target IDs, and suggestion. The top-level `requestId` is also copied to each diagnostic. Server API path sanitization is independent of the local CLI display flags: package files retain a safe package-relative path such as `load/scenario.yaml` and source line/column, while absolute paths outside the package are shown as `$EXTERNAL/<basename>`. The UI presents these details beside the schedule editor and can focus a matching field or open the stored snapshot.

Schedules default to paused. Create, edit, and resume compute the next matching instant after the current time. Occurrence states are `scheduled`, `submitted`, `skipped`, and `failed-to-submit`; job execution results remain in the regular job API. Deleting a schedule removes its definition but retains its occurrence history and does not cancel accepted jobs. Record the schedule ID before deletion: administrators can continue reading retained rows at `/load-schedules/{scheduleId}/executions` while any remain, even though the deleted definition no longer appears in the UI or schedule list. History is capped by `maxExecutionsPerSchedule`; in-progress occurrences and occurrences linked to active jobs can temporarily exceed that cap, then pruning runs when the linked job becomes terminal, including after its schedule was deleted.

Submit a job with a stable package ID and command-specific ATT Worker fields:

```http
POST /api/v1/jobs/run
Content-Type: application/json

{"packageId":"payments","all":true,"principal":"Bob"}
```

The optional `principal` input is honored only when anonymous mode is enabled. The submitted value remains raw in the request; the Server applies `(anonym)` and persists the result in the existing `principal` property. The default remains `anonymous`.

The API returns `202 Accepted` and a job ID. Jobs move through `QUEUED`, `PREPARING`, and `RUNNING`, then finish as `PASS`, `FAIL`, `ERROR`, `INVALID`, or `CANCELLED`. Capacity overflow returns `429` with an `ATT-SERVER-CAPACITY-EXCEEDED` error. Errors use an `error` object with `code`, `summary`, `detail`, and `requestId`; server stack traces are not returned.

Browser Quick and Advanced Load endpoints return `403 ATT-SERVER-INLINE-LOAD-DISABLED` until `server.inlineLoad.enabled` is enabled. A scenario that exceeds an enabled cap returns `400 ATT-SERVER-INLINE-LOAD-LIMIT` with a summary naming the exceeded setting and is not queued.

Job records include an additive `performance` object when measurements are available. `performance.timings` uses monotonic elapsed time for admission, queue wait, Worker preparation/spawn, Worker-ready (first `STATUS`), execution-ready (first `PROGRESS` or `LOG`), Worker lifetime, and result-to-termination. `performance.worker` records heap, live-thread, GC, process CPU, and sampled peak RSS metrics from the isolated Worker. Sampling is event-triggered and limited to one sample per 100 ms; brief peaks can be missed. RSS is available on Linux `/proc` systems only. Worker resource metrics are included in the canonical job record and survive Server restart.

`GET /jobs` returns the latest 100 jobs. Add `?principal=<text>` to search case-insensitively within the existing `principal` property; matching is applied before the 100-row limit. Search uses substring matching, so `alice` also matches `alice(anonym)`. The Web UI and `att remote jobs --principal <text>` use this filter. Historical rows retain their saved value, including older `anonymous` rows. Submission payloads are not persisted; metadata stores only the command and package ID summary. Engine results and diagnostics are persisted as control-plane metadata. Larger reports, logs, and other evidence remain under the job output directory.

Resource discovery accepts `case`, `template`, `flow`, or `tool` as the optional `type`. List pages default to 50 items and allow up to 100; continuation cursors are encrypted, bound to the resolved request identity and query, and rejected with `409` if package content changes. In anonymous mode, all callers share the same cursor identity. The explorer returns logical resource IDs, safe projections, provenance, relationships, and stable diagnostics. YAML source responses are parsed and sanitized projections, not original file bytes. Under the default `safe-package-files` policy, eligible bounded package-local Tool text is available without a per-file allowlist; `allowlist-only` requires the exact package-relative entry in `server.inspection.safeTextSources`. External scripts require an exact canonical per-package allowlist beneath a configured external root, and their filesystem paths are never returned. Deny-glob entries match package-relative paths for package files and canonical paths relative to the most specific configured external root for external files. Safe `&{...}` references in Template/Flow and Configuration source can open eligible package-local text through a bounded source route; the Server canonicalizes each target, enforces extension, deny-path, encoding, size, and visibility rules, and requires a Server-issued opaque reference bound to the package and origin. The separate Configuration source route applies the same policy to eligible text under `config/`; it is not a general filesystem browser. Raw configuration, Testdata descriptors, and Debug sidecars are not exposed through the resource-source route; eligible non-sensitive configuration and Testdata text is available through the Configuration route. Credentials and personal-data values remain protected regardless of the display-masking setting. Discovery never executes Tools or exposes a filesystem browser. The inspection queue, Worker heap, timeout, and response bounds are independent of normal job execution.

### Package resource inspection

Use the public read-only endpoints with the identity resolved by the Server: a Servlet Principal in authenticated mode or the shared `anonymous` identity in anonymous mode. `kind` is `case`, `template`, `flow`, or `tool`; `resourceId` is the opaque package-scoped ID returned by the list endpoint. The list `query` matches logical IDs, names, descriptions, and tags. Omit `type`, `query`, or `limit` to use the defaults. A continuation cursor is valid only for the same package, identity, type, and query.

```http
GET /api/v1/packages/payments/resources?type=case&query=refund&limit=50
```

The response includes safe summaries, a total count, and an opaque `nextCursor` when another page exists:

```json
{
  "items": [{
    "resourceId": "case.<opaque>",
    "type": "case",
    "logicalId": "PAYMENT.REFUND01",
    "name": "Refund request",
    "state": "ready",
    "sourceAvailable": false,
    "provenance": {"suite": "testcase/payments.xlsx", "groupId": "PAYMENT", "sheet": "Cases", "rowNumber": 12},
    "references": [{"type": "template", "logicalId": "PAYMENT.refund", "resourceId": "template.<opaque>", "resolution": "resolved"}],
    "referencedBy": [],
    "diagnostics": []
  }],
  "total": 1,
  "nextCursor": null,
  "diagnostics": [],
  "requestId": "..."
}
```

Read one resource's parsed definition and relationships, then request its safe source projection separately:

```http
GET /api/v1/packages/payments/resources/template/{resourceId}
GET /api/v1/packages/payments/resources/template/{resourceId}/source
GET /api/v1/packages/payments/resources/template/{resourceId}/source-file?referenceId=<opaque>
```

The detail response contains `resource`, `definition`, `diagnostics`, and `requestId`. The Template/Flow YAML source response contains `resource`, `available`, `format`, `text`, `redacted`, `fileReferences`, `fileReferencesTruncated`, and `requestId`. Each eligible `fileReferences` item contains a package-relative `logicalPath` and opaque `referenceId`; pass that ID to `source-file` to read the bounded text after the Server repeats canonical containment and visibility checks. Source-file responses include `logicalPath`, sanitized `text`, and a `redacted` flag. The route uses the same configured identity and authorization mode as other inspection endpoints; anonymous mode remains subject to its independent inspection feature gate. Never construct a filesystem URL from a logical path. For unavailable source, `available` is `false`, `reason` is stable, and no partial text is returned. References with no matching resource have `resolution: "unresolved"` and a stable resource diagnostic.

Invalid list parameters return `400`; unknown packages/resources return the same `404` shape. Stale cursors return `409`, oversized responses `413`, a full inspection queue `503`, and an inspector timeout `504`. Error responses use the existing `error.code`, `error.summary`, and `requestId` envelope; they do not include physical paths or parser exception messages.

### Target-scoped Debug and drafts

Debug form, Quick Load form, Load policy, and draft endpoints use the same resolved identity as the rest of the API. In anonymous mode, drafts created by one caller are available to every other anonymous caller. Use a resource ID from the Package Resource Explorer; Cases cannot be Debug or Quick Load targets.

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/debug-form?environment=SIT
```

The response contains the selected resource, fixed logical target, safe `att-debug/v1.2` input defaults, a `redacted` flag, and `requestId`. A missing sidecar produces valid empty defaults. Sensitive fields are redacted before the response.

Validate the typed input to create a server-issued draft:

```http
POST /api/v1/drafts/debug
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}}}
```

The response includes an opaque `draftId`, safe YAML preview, validation diagnostics, and expiry. `GET /api/v1/drafts/{draftId}` returns the same safe preview only to its owning resolved identity. Drafts live in memory for up to 10 minutes, with a limit of 128 active drafts per Server and 16 per identity. In anonymous mode every caller shares that identity and draft limit. Restart invalidates drafts. The Server never returns the internal package revision digest.

Submit only the draft identity:

```http
POST /api/v1/jobs/debug
Content-Type: application/json

{"packageId":"payments","draftId":"D0123456789ABCDEF0123456789ABCDEF"}
```

The Server rechecks the package revision at submission and before Worker start. A change detected during submission returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID` with that diagnostic. Validation failures return a top-level `diagnostics` array with safe `code`, `summary`, `field`, and logical `resourceId` values; source paths and parser snippets are omitted. Worker execution receives the same immutable typed values validated for the preview; inline values remain in memory and do not create files under the package root. Existing path-based Debug requests remain supported for compatible clients.

### Target-scoped Quick Load

Quick Load is available from a selected Template, Flow, or Tool when `server.inlineLoad.enabled` is true. It uses one fixed target and creates one Workload. The model is `virtualUsers` or `arrivalRate`; the Server composes the matching current `att-load/v1.7` policy and validates the target through the existing Load pipeline. When disabled, both Quick Load form/policy reads and draft creation return `403 ATT-SERVER-INLINE-LOAD-DISABLED`.

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
```

The form response includes safe `inputs` and `vars` defaults for Template/Flow targets, or `inputs` and `arguments` for a Tool, plus a redacted one-workload preview. It reads only the business fields from the optional target `debug.yaml`; Debug-only `case`, `stage`, and local `testdata` are not copied. When a sidecar has Debug-local Testdata, the form reports that those imports were omitted. Supply any needed package-relative descriptors in the separate Load-level `testdata` array; these paths are validated as part of the effective Load scenario.

The Server reads `load/load.visualuser.yaml` for `virtualUsers` and `load/load.arrivalrate.yaml` for `arrivalRate`. These are policy-only `att-load/v1.7` descriptors; the selected policy must match the chosen model. If a model-specific file is absent, a bundled low-intensity fallback is used: one Virtual User for 10 seconds, or 1 arrival per second for 10 seconds with `maxConcurrent: 1` and `overloadPolicy: drop`. Existing CLI `load/load.yaml` behavior is unchanged.

Validate business values and pacing overrides to create an owned draft:

```http
POST /api/v1/drafts/quick-load
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"model":"virtualUsers","input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}},"load":{"users":4,"duration":"30s"},"execution":{"thinkTime":"100ms"},"testdata":["testdata/load-accounts.yaml"]}
```

`load` accepts only pacing fields supported by the selected model. `execution` accepts the optional closed-workload `thinkTime`. `testdata` is an optional array of additional package-relative Load descriptor paths; it is kept separate from Debug-local imports and the model policy's existing descriptors. The response returns an identity-bound opaque `draftId` beginning with `L`, the safe effective YAML preview, redaction state, and expiry. Debug and Quick Load drafts share the 128-active-per-Server, 16-per-identity, 10-minute in-memory limits.

After reviewing the preview, submit only the draft identity:

```http
POST /api/v1/jobs/load
Content-Type: application/json

{"packageId":"payments","draftId":"L0123456789ABCDEF0123456789ABCDEF"}
```

The Server checks the package revision at draft submission, before Worker start, and again in the Worker. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`, or marks an already accepted job `INVALID` with that diagnostic. The Worker receives the exact immutable scenario validated for preview; it stays in memory and is not written into the package. Existing path-based Load requests remain supported.

### Advanced Load builder

Advanced Load creates a full `att-load/v1.7` scenario with one or more workloads when `server.inlineLoad.enabled` is true. The selected `model` must be `virtualUsers` or `arrivalRate` for every workload. Virtual Users workloads can use one fixed target or a weighted `mix`; Arrival Rate workloads use one fixed target each. The Engine validates the shared timing envelope, workload intensity, thresholds, Testdata policies, and every target before the Server creates a draft. The Server then enforces its configured inline Load caps before returning the preview. Exceeding a cap returns `400 ATT-SERVER-INLINE-LOAD-LIMIT` with a safe summary; the Server does not schedule the scenario.

Read safe model policy defaults with:

```http
GET /api/v1/packages/payments/load-policy?model=virtualUsers&environment=SIT
```

The response contains `model`, a redacted `policy`, its safe `previewYaml`, a `redacted` flag, and `requestId`. This endpoint is read-only and does not create a draft.

Validate a complete scenario to create a server-issued draft:

```http
POST /api/v1/drafts/load
Content-Type: application/json

{
  "packageId": "payments",
  "environment": "SIT",
  "scenario": {
    "schemaVersion": "att-load/v1.7",
    "load": {"users": 1, "duration": "30s"},
    "workloads": [
      {"id": "browse", "target": {"type": "flow", "id": "PAYMENT.browse"}, "load": {"users": 4}},
      {"id": "submit", "target": {"type": "template", "id": "PAYMENT.submit"}, "load": {"users": 2}}
    ]
  }
}
```

The Server validates the full scenario and resolves every target without scheduling traffic. It restores redacted values only from each selected target's package-local `debug.yaml` business fields; Debug `case`, `stage`, and local Testdata are excluded. The response contains an opaque draft ID beginning with `A`, a safe effective YAML preview, the selected model, a redaction flag, and expiry. Advanced Load shares the 128-active-draft Server limit, 16-draft per-identity limit, and 10-minute expiry with Debug and Quick Load.

Submit only the package and draft ID to `POST /api/v1/jobs/load`. The Server checks the package revision at submission and before Worker start, then passes the same immutable normalized scenario validated for preview through the existing Worker, Engine, scheduler, event, and evidence paths. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID`. The browser disables YAML copy and export when any preview field is redacted. Existing path-based Load requests remain supported.

### Package configuration inspection

Configuration inspection uses the same bounded inspection Worker as package resources. These read-only endpoints use the resolved authenticated or anonymous identity:

```http
GET /api/v1/packages/payments/configuration?view=declared
GET /api/v1/packages/payments/configuration/effective?environment=SIT
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&offset=0&limit=50
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeInherited=true&offset=0&limit=50
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/source?path=templates%2Fpayment%2Fpayload%2Frequest.xml&origin=flows%2Fpayment.yaml
GET /api/v1/packages/payments/configuration/testdata/accounts/records?environment=SIT&offset=0&limit=50
GET /api/v1/packages/payments/configuration/testdata/generatedAccounts/records?environment=SIT&offset=0&limit=50&previewGenerated=true
```

The declared view returns the schema version, safe global fields, configured profiles, and for each configuration section its declared or absent state, entry count, and profile inheritance or replacement state. It does not return helper field values. Effective views use `FrameworkConfigLoader` to select the environment and return safe helper, Tool, and Testdata descriptor metadata. Omit `environment` to inspect a root-only package or use the configured default. The first effective response returns section counts; request a section by its ID (`dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `tools`, or `testdata`) to read its entries. Effective section and comparison field responses accept `offset` and `limit` (1–100; default 50) and return `total` and `nextOffset` for paging. Inspection never connects to DB, MQ, HTTP, or SSH services and never returns unrestricted configuration YAML. Testdata records are available only through the bounded, configured-descriptor preview endpoint below.

```http
GET /api/v1/packages/payments/configuration/testdata/{descriptorId}/records?environment=SIT&offset=0&limit=50
```

The descriptor must be configured for the selected environment. Records are paged (1–100 per response); credentials, authentication material, and classified personal-data fields are always masked, including in anonymous mode. Ordinary values remain visible by default. When `redaction.enabled` is true, the configured sensitive paths and field patterns are masked as well. The masking switch cannot authorize disclosure of protected values. Unknown or unconfigured descriptors return `404`.

Comparison requires a section ID returned by the declared or effective view. The Server verifies that ID, filters both profiles to that section, and only then paginates. By default, it compares profile-sourced values; `includeInherited=true` also includes inherited and global values with their origins. `includeAllFields=true` adds otherwise-omitted non-secret DB properties and evidence fields, and requires the configured configuration administrator role (or local `ADMIN` in local-auth mode). These responses are not cached and are audited. This prevents unrelated sections from leaking into later pages. A sensitive field is returned as `state: "hidden"` with `change: "hidden"`; the response does not reveal whether its value is equal or different. Absolute local paths, credentials, server-owned secrets, and unrestricted descriptor content are not returned. Non-credential URLs and MQ host/port values can be visible in Effective/Compare when masking is disabled; HTTP header maps and SSH host/user/port/identity-file values remain protected in every view. Enabling masking also hides configured operational paths and fields.

The `source` endpoint resolves eligible package-local text references against their origin under `safe-package-files`; `allowlist-only` requires the existing exact package-relative `server.inspection.safeTextSources` entries. A linked request must include the opaque Server-issued `referenceId` returned by its authorized parent source; caller-supplied path and origin are insufficient. External scripts are available only through their exact canonical per-package allowlist under a configured external root, and their absolute paths are never returned. This remains a bounded source endpoint, not an arbitrary file browser. Testdata record previews require an effective descriptor ID. Literal records are paged with `limit` from 1 to 100 (the UI requests 50 at a time). Generated descriptors return `state: "preview-required"` and no records unless `previewGenerated=true` is explicitly requested. The same inspection identity, redaction policy, worker, source-size, response-size, queue, and timeout limits apply.

Configuration responses include `view`, `state`, `schemaVersion` when available, and `diagnostics`. Paged responses also include `section`, `offset`, `limit`, `total`, and `nextOffset`. An invalid configuration returns HTTP 200 with `state: "invalid"` and a stable diagnostic; malformed profile parameters return `400`. Unknown packages return `404`, oversized responses return `413`, a full inspection queue returns `503`, and an inspector timeout returns `504`. The same response-size, queue, and timeout limits used by resource inspection apply.

## Server-sent events

Connect to `/api/v1/jobs/{jobId}/events` with `Accept: text/event-stream`. Events are retained in `dataDir/jobs/<jobId>/events.jsonl`, assigned increasing numeric IDs per job, and named `status`, `progress`, `log`, `diagnostic`, or `result`.

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

Send `Last-Event-ID` after reconnecting to replay retained events with greater IDs. If older events have expired from the bounded journal, the stream resumes with events still retained. The stream sends `: keepalive` comments while idle and closes after it delivers the terminal result. A disconnected observer does not cancel the job. Up to 32 observers receive dedicated stream threads through direct handoff; excess observers are rejected with HTTP 503 before a stream starts. Each observer has its own one-entry notification queue. Client writes happen outside the Worker event reader.

## Cancellation and recovery

`DELETE /jobs/{jobId}` requests graceful Worker termination, waits up to `gracefulStopMs`, and then force-terminates only that Worker if needed. The job record remains available. A Server restart does not automatically rerun jobs that were not terminal.

Artifact paths are relative to the job output directory. Absolute paths, traversal, and symlink escapes are rejected. The Server does not expose general filesystem browsing or package mutation APIs.

## Distribution and compatibility

The Server binary is distributed separately as `att-4.2.1-server.tar.gz`, containing `server/att-server-4.2.1.war` (with ATT application code combined in `WEB-INF/lib/att-server-4.2.1.jar`) and the deployment guides. The local CLI is in `att-4.2.1-local.tar.gz` with the shared Worker/Engine runtime at `lib/att-local-4.2.1.jar`; the Server uses that same version-matched library. Tomcat provides the HTTP listener and Servlet API. The Server requires Java 17; the CLI, Engine, and Worker continue to target Java 8.

### Issue #176 rollout checklist

1. Back up the Server `dataDir` and retain the current WAR and deployment configuration for rollback.
2. Keep Tomcat authentication enabled and terminate public traffic with HTTPS. Confirm the package roots and Server data directory are separate and writable only by the service account.
3. Set `ATT_HOME` or `-Datt.home` to the matching Local installation so Worker processes can use `$ATT_HOME/lib/*`. Tomcat can deploy the WAR packed; `workers.libraryDirs` remains additive for external JDBC, MQ, and other libraries. Confirm `/api/v1/health` and `/api/v1/version` through the deployed Tomcat context path.
4. Begin with `server.inlineLoad.enabled: false`. Verify the Package and Configuration Explorers against a non-production package. Enable browser Load only in an approved environment and set caps for expected workload count, targets, users or rate, concurrency, and total envelope duration.
5. Monitor Worker and Load queue capacity, job outcomes, cancellation, and retained artifacts. The package remains read-only; rollback consists of disabling inline Load and restoring the prior WAR/config if needed.

This checklist describes deployment steps; it does not substitute for the P6 security, browser, compatibility, packaged-WAR, and hosted-CI acceptance evidence.
