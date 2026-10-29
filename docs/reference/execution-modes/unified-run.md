# Unified RUN and schedules

<!-- att-doc-status: current release=4.2.7 -->

ATT 4.2.7 represents functional one-shot execution as **RUN**. A RUN executes a Case, Template, Flow, Tool, or a selected batch of Cases. **Debug** is an optional diagnostic mode of RUN. **LOAD** retains its performance engine, pacing controls and specialized reports.

The released Local CLI keeps its interface-specific compatibility boundary: its run verb selects workbook Cases, and standalone debug runs one Template, Flow or Tool. ATT Remote retains its legacy Server commands. This shared RUN model does not mean the proposed vNext CLI grammar is released; see the [Planned vNext page](../../interfaces/cli/vnext-proposed.md).

## Validate and execute a RUN

Use the same authenticated Server origin and context path as ATT Studio. Send this request to `POST /api/v1/drafts/run`:

<!-- att-doc-example: current id=unified-run-draft schema=att-execution/v1 format=json -->
```json
{
  "schemaVersion": "att-execution/v1",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {"type": "template", "id": "PAYMENT"},
  "input": {"schemaVersion": "att-debug/v1.2", "inputs": {"amount": 100}},
  "options": {"debug": false}
}
```

Replace `payments`, `SIT` and `PAYMENT` with configured package values. Review the safe preview, then send `{"packageId":"payments","draftId":"R..."}` to `POST /api/v1/jobs/run`. The accepted job uses the existing status, cancellation, events, results and artifact routes. Opening a page or changing a mode does not submit a job.

The request follows `schemas/att-execution-v1.schema.json`. Unknown fields and mixed target/selection requests fail validation. A draft expires after ten minutes, belongs to the validating identity, and binds the package revision. Package changes require validation again. Invalid input returns HTTP 400, unavailable drafts 404, changed revisions 409 and exhausted job/draft capacity 429. Inspection capacity/time/size limits remain in force.

For a single Case, use `target: {"type":"case","id":"payments.default.PAY001"}` and omit `input`. Case values continue to come from Excel/sidecar bindings. Template, Flow and Tool inputs retain Debug sidecar semantics, including omitted values, explicit null, literals, expressions, variables and Tool arguments. Do not translate Case parameters into resource input fields.

## Select a batch

Replace `target` and `input` with an explicit selection:

```json
{
  "schemaVersion": "att-execution/v1",
  "executionType": "run",
  "packageId": "payments",
  "environment": "UAT",
  "selection": {
    "suites": ["testcase/payments.xlsx"],
    "caseIds": ["payments.default.PAY001"],
    "tags": ["smoke"],
    "excludeTags": ["slow"]
  },
  "options": {"debug": true, "failFast": false}
}
```

`suites` and `suiteDirectory` are mutually exclusive. `suiteDirectory` selects a package-relative group directory. Case and tag filters retain CLI intersection rules; tags match any requested tag, and excluded tags remove matches. Use `all:true` for the package selection. Arrays must be nonempty, unique and contain at most 1000 values. Validation resolves and freezes at most 10000 selected Case IDs. An empty selection fails rather than falling back to all Cases.

`rerunFailed:true` resolves failures from the latest retained completed Case RUN owned by the caller in this package, within the latest 100 owned jobs. It intersects explicit Case/tag filters and freezes the resulting IDs. An unavailable manifest or no matching failures is a validation error. Existing CLI `--rerun-failed` continues to use its local output history.

## Enable Debug diagnostics

Set `options.debug:true` to retain additional Debug execution events. This option does not repeat execution, increase concurrency, grant a role or enable unsafe failure details. Central Server size limits, disclosure policy, secret filtering and artifact controls still apply. Existing functional and resource report representations remain available through the shared job routes.

## Schedule the validated definition

Canonical creation requests use `schemas/att-schedule-v2.schema.json`; legacy routes retain their existing optional fields.

Create a RUN schedule by sending this body to `POST /api/v1/schedules`:

<!-- att-doc-example: current id=run-schedule schema=att-schedule/v2 format=json -->
```json
{
  "schemaVersion": "att-schedule/v2",
  "name": "Nightly payment checks",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "draftId": "R...",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "maxConcurrentRuns": 1,
  "enabled": false
}
```

Use the draft ID returned by validation. RUN schedules store the validated input/selection and revision, not a mutable query. A changed package, target, environment or configuration requires a fresh schedule. Protected package defaults use path-bound keep markers. Submitted sensitive overrides cannot be persisted in a schedule.

LOAD schedules continue to accept package scenario paths and validated Load drafts. Their existing scenario validation and pacing limits remain authoritative. Legacy package-path LOAD schedules validate the current package scenario at each firing; the public `revisionPolicy` distinguishes them from revision-bound RUN snapshots.

Use either five-field `cron` with an IANA timezone, or a single `runAt` instant such as `2026-10-20T02:00:00+08:00`. Do not supply both. A schedule keeps one trigger: an update that supplies either field replaces the stored trigger, and an update that supplies neither keeps it. One-time triggers are claimed once. Cron skips nonexistent local times during spring-forward; repeated local times during fall-back can produce two instants subject to the configured minimum interval. Preview trigger times with `POST /api/v1/schedules/preview` before enabling.

The shared scheduler records an occurrence before admission. Per-schedule/global overlap limits skip an occurrence at capacity; ordinary Worker and Load admission limits also apply. Late/restart misfires are skipped without backfill. Failed submissions follow `server.scheduling.failurePolicy`; there are no automatic execution retries. Cancelling a job cancels that occurrence, while pausing a schedule prevents future firings. Job and occurrence retention keep their existing Server limits.

Every active local `USER` or authenticated container principal in `server.authorization.userRole` can manage the shared RUN and LOAD schedules. `ADMIN` accounts have system and account administration only. A schedule's creator is retained for audit, but does not restrict which active USER can view, edit, pause, trigger, or delete it. Under local authentication, the creator must remain an active `USER` for scheduled RUN occurrences to submit. Container roles are checked for each management request; the background scheduler cannot query the external realm, so an operator must pause schedules when their creator loses the configured USER role. Scheduled RUN jobs keep the creator identity in their audit trail.

## Migrate existing clients

| Existing interface | Mapping in 4.2.7 |
|---|---|
| CLI `validate`, `run`, `report` | Existing validation, Case RUN and report commands remain valid |
| CLI `--suite`, `--suite-dir`, `--case`, `--tag`, `--rerun-failed` | Existing selectors retain their behavior; Server batches use `selection` |
| Debug CLI, sidecars, `/jobs/debug`, `/drafts/debug` | Compatibility resource RUN with legacy diagnostic behavior |
| `/jobs/run` with legacy Case selection | Existing Case adapter remains valid |
| `/load-schedules`, legacy `jobType:load/debug` | Shared scheduler compatibility routes; existing definitions remain readable |
| `/jobs/...`, saved Studio job/result links | Existing shared history, cancellation, events and results routes |

No legacy endpoint is removed in 4.2.7. `GET /api/v1/version` advertises `unifiedRunVersion:att-execution/v1`. New Studio resource forms can fall back to legacy Debug drafts when an older Server lacks this capability. Unified Case/batch drafts require 4.2.6; use the existing Explorer Case action on older deployments. Deploy matching Server/Worker components together; older Workers do not implement resource RUN dispatch.

Schedule persistence adds JSON fields in the existing H2 tables, leaving legacy LOAD rows unchanged. Before downgrade, stop scheduling, export new definitions, remove RUN/one-time definitions and restore the pre-upgrade database backup. An older scheduler must not interpret a new RUN definition as LOAD. Retained job artifacts remain separate from database metadata.

## Replay and new execution from a Job

In **Jobs**, **Job Details**, or **LOAD History**, choose **Rerun with same settings** or **Configure & Rerun**. Both actions create one authorized Server draft and validate the current Package/Environment before execution. The standard RUN or LOAD editor receives only an opaque `draftRef`; it fetches the sanitized definition from the Server on entry. It does not place the definition in a URL or browser storage.

**Rerun with same settings** defaults to **Use current Package**: preserve the Job's safely retained execution parameters, resolve targets and configuration against the current Package, then show the original/current revisions and validation outcome before confirmation. It never silently replaces a retained scenario with the latest YAML defaults. **Require original revision (strict)** is the reproducibility choice. A mismatch returns `ATT-SERVER-REPLAY-REVISION-CONFLICT` and offers **Review with current Package**; stored metadata cannot restore missing historical source bytes. `server.scheduling.packageChangePolicy` and a Schedule-level `ignore` apply only to scheduled executions and do not change manual Rerun.

**Configure & Rerun** opens the ordinary editor with supported safe settings prefilled. Edit the RUN target/Case selection, options and safe inputs, or LOAD timing, workloads, model-specific pacing, concurrency, thresholds, evidence and safe testdata selection. **Validate** creates a new review intent with bounded typed overrides; **Review changes** shows before/after values, summed LOAD phase duration, current revision and the distinction between configured arrival rate and measured TPS. **Run** creates an independent Job with `rerunOfJobId`; Cancel/Back leaves the source Job, report, Schedule and package files unchanged. Package-file-backed LOAD uses its retained safe package-relative source route and does not inherit the inline-draft-only duration ceiling.

Schedules remain a separate workflow: **Edit existing schedule**, **Create new schedule**, or **Run now**. Existing schedule `strict`/`pause`/`ignore` behavior is unchanged.

New retained execution specs use `att-job-execution/v2`; the parser still accepts v1. Replay drafts use `att-job-replay-draft/v1`. Draft references and confirmation intents are principal-bound and expire after ten minutes; they are held in Server memory, so Server restart loses them and the user must reopen the source Job to create a new draft. A source Job without a safe retained definition, a missing sensitive input, an expired draft, a removed resource, an invalid schema and capacity rejection have separate error responses. Secrets, Worker transport data, host roots and unsafe paths are never reconstructed from logs or artifacts.

Historical Jobs without a retained definition show **Rerun unavailable for this historical job**. Validation-only Jobs, failed safe retention, and classified submitted inputs cannot be replayed exactly. Legacy direct submissions with raw overrides or unresolved `rerunFailed` history selection require a new validated draft before safe retention. Configure a new run and provide missing credentials through the normal protected input flow. Never reconstruct an execution from logs, artifacts or redacted previews. Safe definitions are limited to 256 KiB and exclude Worker transport fields and host roots.

The Job lifecycle API uses the following endpoints under `/api/v1`:

| Method and path | Result |
| --- | --- |
| `GET /jobs/{jobId}/replay` | Authorized safe definition and availability reason. |
| `POST /jobs/{jobId}/replay-draft` with `{"sourcePolicy":"current"}` or `{"sourcePolicy":"strict","overrides":{...}}` | `att-job-replay-draft/v1` validation, opaque `draftRef` and confirmation `intentId`; no Job or Schedule is created. Omitted `sourcePolicy` preserves legacy strict behavior. |
| `GET /jobs/{jobId}/replay-draft/{draftRef}` | Principal-authorized sanitized editor definition and current configuration provenance; no definition is carried in the route. |
| `POST /jobs/{jobId}/rerun` with `{"intentId":"...","confirm":true,"sourcePolicy":"current"}` | New independent Job, HTTP 202 and `Location`; retries with the same intent return the accepted Job. |

Schedule creation uses its normal validated draft contract. Debug remains `options.debug` on RUN. Replay access is restricted to the submitting identity or retained schedule execution owner. `ATT-SERVER-REPLAY-DRAFT-STALE` is returned when the Package changes after validation; `ATT-SERVER-REPLAY-DRAFT-EXPIRED` is returned for an expired or restart-lost draft; capacity returns HTTP 429. Repeating an accepted intent returns the same Job.
