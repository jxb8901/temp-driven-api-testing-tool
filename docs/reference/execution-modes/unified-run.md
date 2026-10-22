# Unified RUN and schedules

ATT 4.2.2 represents functional one-shot execution as **RUN**. A RUN executes a Case, Template, Flow, Tool, or a selected batch of Cases. **Debug** is an optional diagnostic mode of RUN. **LOAD** retains its performance engine, pacing controls and specialized reports.

## Validate and execute a RUN

Use the same authenticated Server origin and context path as ATT Studio. Send this request to `POST /api/v1/drafts/run`:

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

| Existing interface | Mapping in 4.2.2 |
|---|---|
| CLI `validate`, `run`, `report` | Existing validation, Case RUN and report commands remain valid |
| CLI `--suite`, `--suite-dir`, `--case`, `--tag`, `--rerun-failed` | Existing selectors retain their behavior; Server batches use `selection` |
| Debug CLI, sidecars, `/jobs/debug`, `/drafts/debug` | Compatibility resource RUN with legacy diagnostic behavior |
| `/jobs/run` with legacy Case selection | Existing Case adapter remains valid |
| `/load-schedules`, legacy `jobType:load/debug` | Shared scheduler compatibility routes; existing definitions remain readable |
| `/jobs/...`, saved Studio job/result links | Existing shared history, cancellation, events and results routes |

No legacy endpoint is removed in 4.2.2. `GET /api/v1/version` advertises `unifiedRunVersion:att-execution/v1`. New Studio resource forms can fall back to legacy Debug drafts when an older Server lacks this capability. Unified Case/batch drafts require 4.2.2; use the existing Explorer Case action on older deployments. Deploy matching Server/Worker components together; older Workers do not implement resource RUN dispatch.

Schedule persistence adds JSON fields in the existing H2 tables, leaving legacy LOAD rows unchanged. Before downgrade, stop scheduling, export new definitions, remove RUN/one-time definitions and restore the pre-upgrade database backup. An older scheduler must not interpret a new RUN definition as LOAD. Retained job artifacts remain separate from database metadata.
