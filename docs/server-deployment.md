# ATT Server deployment and API

ATT Server 4.0.0 provides a single-node control plane for submitting ATT Run, Debug, Load, and Validate jobs over a versioned REST API. Deploy its WAR to an external Tomcat 10.1+ instance running Java 17 or later. The CLI, Engine, and Worker remain Java 8-compatible.

## Prepare the deployment

1. Create a server-owned data directory, for example `/var/lib/att-server`, writable only by the Tomcat service account. Keep it separate from ATT installation files and package roots.
2. Create package directories under an allowed root and ensure the Tomcat service account can read them.
3. Copy `config/att-server.example.yaml` to a protected location, update its absolute paths, and keep credentials out of the file and package content.
4. Set `-Datt.server.config=/etc/att/server.yaml` in Tomcat's Java options. The `ATT_SERVER_CONFIG` environment variable is also accepted when the system property is absent.
5. Deploy `att-server-4.0.0.war` to Tomcat 10.1+. Keep Tomcat's `unpackWARs` enabled so the Server can launch the Worker from `WEB-INF/lib`.

Tomcat owns listeners, TLS, access logs, and authentication. The WAR uses the container Realm for HTTP BASIC authentication on package and job APIs and metrics. Any authenticated Servlet Principal has the same API permissions in v1; no `ATT_USER` role assignment or ATT-specific RBAC is required. Health and version remain public. Terminate TLS before exposing BASIC credentials. The Server fails startup when its configuration, Java baseline, or package mappings are invalid.

## Configuration

`server.dataDir` stores H2 control-plane metadata and job output. Worker concurrency, queue size, Load admission, and graceful stop timeout are bounded by `workers`. Rejected submissions are discarded before they create durable job records. Terminal job metadata, journals, and artifacts are retained for `server.jobRetentionDays` (default 30, allowed range 1–3650); expired jobs are cleaned at startup and hourly, while active jobs are preserved. `workers.maxConcurrentLoad` bounds admitted Load jobs, including queued jobs, so waiting Loads do not occupy general Worker threads. Excess Load or overall-capacity submissions receive HTTP 429. The `packages` registry is read-only and maps stable package IDs to canonical roots beneath `allowedRoots`.

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
workers:
  maxConcurrent: 8
  queuedLimit: 100
  # Maximum admitted Load jobs, queued or executing.
  maxConcurrentLoad: 2
  gracefulStopMs: 10000
packages:
  allowedRoots:
    - /srv/att/packages
  entries:
    payments: /srv/att/packages/payments
```

`dataDir` must be absolute. Package roots and allowed roots must exist at startup. Package paths are canonicalized; symlinks that resolve outside an allowed root are rejected. Clients submit `packageId`; they cannot select a package path, output path, Worker executable, or classpath. Package registration and mutation endpoints are not available in v1.

Server state is stored in `dataDir/db/`. Each job uses `dataDir/jobs/<jobId>/` for its bounded event journal and execution output. A restarted Server marks old queued or active jobs `ERROR` with `ATT-SERVER-INTERRUPTED`; it does not rerun them.

## Authentication and identity

Tomcat authenticates requests. ATT Server reads `HttpServletRequest.getUserPrincipal()` and requires a Principal on package, job, result, event, and artifact endpoints. Health and version may be anonymous. The principal name is stored with job and audit metadata and is not sent to the Worker or exposed in ATT expression Context.

State-changing requests require `application/json`; requests carrying an `Origin` must match the request origin. All authenticated Servlet Principals have the same permissions in v1; ATT does not require a particular container role or provide ATT-specific RBAC. For an intentionally isolated network, set `server.authenticationRequired: false` to accept anonymous API requests; this bypasses authentication for every API operation and should not be used on an untrusted network. ATT Server does not implement passwords, JWT/OIDC validation, LDAP authentication, or login flows. Do not put credentials in API payloads or package files.

## REST API

All endpoints use `/api/v1`. Requests and responses use JSON unless the endpoint is an SSE stream or artifact download.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health`, `/version` | Health and build information |
| `GET` | `/metrics` | Bounded job and Worker counts |
| `GET` | `/packages`, `/packages/{packageId}` | Read the configured registry |
| `POST` | `/jobs/run`, `/jobs/debug`, `/jobs/load`, `/jobs/validate` | Submit one job |
| `GET` | `/jobs`, `/jobs/{jobId}` | List recent jobs or read job status |
| `GET` | `/jobs/{jobId}/result` | Read the canonical result and diagnostic |
| `GET` | `/jobs/{jobId}/events` | Observe retained and live job events |
| `GET` | `/jobs/{jobId}/artifacts` | List output files |
| `GET` | `/jobs/{jobId}/artifacts/{path}` | Download one output file |
| `DELETE` | `/jobs/{jobId}` | Cancel an active job; retain its history |

Submit a job with a stable package ID and command-specific ATT Worker fields:

```http
POST /api/v1/jobs/run
Content-Type: application/json

{"packageId":"payments","all":true}
```

The API returns `202 Accepted` and a job ID. Jobs move through `QUEUED`, `PREPARING`, and `RUNNING`, then finish as `PASS`, `FAIL`, `ERROR`, `INVALID`, or `CANCELLED`. Capacity overflow returns `429` with an `ATT-SERVER-CAPACITY-EXCEEDED` error. Errors use an `error` object with `code`, `summary`, `detail`, and `requestId`; server stack traces are not returned.

`GET /jobs` returns the latest 100 jobs. Submission payloads are not persisted; metadata stores only the command and package ID summary. Engine results and diagnostics are persisted as control-plane metadata. Larger reports, logs, and other evidence remain under the job output directory.

## Server-sent events

Connect to `/api/v1/jobs/{jobId}/events` with `Accept: text/event-stream`. Events are retained in `dataDir/jobs/<jobId>/events.jsonl`, assigned increasing numeric IDs per job, and named `status`, `progress`, `log`, `diagnostic`, or `result`.

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

Send `Last-Event-ID` after reconnecting to replay retained events with greater IDs. If older events have expired from the bounded journal, the stream resumes with events still retained. The stream sends `: keepalive` comments while idle and closes after it delivers the terminal result. A disconnected observer does not cancel the job. Each observer has its own one-entry notification queue, and the stream executor is also bounded. Client writes happen outside the Worker event reader.

## Cancellation and recovery

`DELETE /jobs/{jobId}` requests graceful Worker termination, waits up to `gracefulStopMs`, and then force-terminates only that Worker if needed. The job record remains available. A Server restart does not automatically rerun jobs that were not terminal.

Artifact paths are relative to the job output directory. Absolute paths, traversal, and symlink escapes are rejected. The Server does not expose general filesystem browsing or package mutation APIs.

## Distribution and compatibility

The 4.0.0 binary release includes `server/att-server-4.0.0.war`; the existing CLI remains available through `att.sh` and `att.bat`. Tomcat provides the HTTP listener and Servlet API. The Server requires Java 17; the CLI, Engine, and Worker continue to target Java 8.
