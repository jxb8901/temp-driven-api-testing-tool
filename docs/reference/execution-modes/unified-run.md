# Unified RUN and schedules

Native RUN executes selected Resolved Cases from a Test Case Definition. Each Case resolves to a Flow and Data Source; ATT validates the selected dependency closure before execution. New work should start with the [Quick Start](../../quick-start.md). Existing Packages authored with an earlier model are covered by the [Legacy documentation](../../legacy/README.md).

## Validate and execute a RUN

Use the authenticated Server origin and context path used by ATT Studio. Send a Native Case draft to `POST /api/v1/drafts/run`:

<!-- att-doc-example: current id=unified-run-draft schema=att-execution/v2 format=json -->
~~~json
{
  "schemaVersion": "att-execution/v2",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {
    "type": "nativeCase",
    "definitionId": "payments.authorize",
    "selection": {"resolvedCaseIds": ["accepted"]}
  },
  "options": {"debug": false}
}
~~~

Replace the package, environment, Definition, and local Case ID with registered values. Review the safe preview, then submit `{"packageId":"payments","draftId":"R..."}` to `POST /api/v1/jobs/run`. A draft is bound to the validating identity and Package revision and expires after ten minutes. Package changes require validation again. Preview and validation do not submit a Job.

A Native Suite draft uses `target.type: nativeSuite`, a non-empty `definitionIds` list, and one selection: `all:true`, `qualifiedCaseIds`, or tag filters. Included tags use any-match semantics; excluded tags take precedence. Validation resolves and freezes at most 10,000 Case IDs. Empty or unknown selections fail rather than expanding to all Cases.

Unknown fields and invalid combinations fail validation. Invalid input returns HTTP 400, unavailable drafts 404, changed revisions 409, and exhausted Job or draft capacity 429. Inspection size, time, and concurrency limits also apply.

Each selected Resolved Case produces one Case execution with its own execution ID, status, and evidence. A Server draft freezes selected Case IDs and the Package source revision; a source change requires validation again. The accepted Job uses the standard status, cancellation, event, result, and artifact routes.

## Rerun failed Cases

`rerunFailed:true` on a Native Suite selects only failed, executed Cases from the latest eligible completed Native Suite RUN for the same owner, Package, Definition IDs, and Package revision. The Server orders candidates by completion time and does not cap discovery at the latest 100 Jobs.

Local execution uses a Package/Definition-scoped Native Suite pointer with a verified manifest hash. Dry runs and unrelated RUNs do not replace it. A Server draft freezes the source Job/run, manifest digest, revision, source selection count, retry attempt number, and exact failed Qualified Case IDs. Submission revalidates that source and rejects a stale draft if a newer matching RUN completed after validation. Skipped source Cases are excluded. The new RUN reports its own selected, executed, and skipped counts; retry counts remain separate from source counts. An unavailable or inconsistent manifest, changed source, or empty failed selection is a validation error.

## Enable Debug diagnostics

Set `options.debug:true` to retain additional diagnostic execution events. This does not repeat execution, increase concurrency, grant a role, or enable unsafe failure details. Server size limits, disclosure policy, secret filtering, and artifact controls still apply.

## Schedule a validated definition

Canonical schedule creation uses `att-schedule/v2`. Create a RUN schedule with a validated draft:

<!-- att-doc-example: current id=run-schedule schema=att-schedule/v2 format=json -->
~~~json
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
~~~

RUN schedules preserve the validated inputs, Case selection, and source revision. A changed Package, target, environment, or configuration requires a fresh validated definition. Protected Package defaults use path-bound keep markers. Submitted sensitive overrides cannot be persisted in a schedule.

Use either five-field `cron` with an IANA timezone or a single `runAt` instant. Do not supply both. An update that supplies either trigger replaces the stored trigger; an update that supplies neither keeps it. One-time triggers are claimed once. Nonexistent local times during spring-forward are skipped; repeated local times during fall-back can produce two instants, subject to the configured minimum interval. Preview trigger times with `POST /api/v1/schedules/preview` before enabling.

The scheduler records an occurrence before admission. Per-schedule and global overlap limits skip an occurrence at capacity; ordinary Worker and Load admission limits also apply. Late or restart misfires are skipped without backfill. Failed submissions follow `server.scheduling.failurePolicy`; execution is not retried automatically. Cancelling a Job cancels that occurrence, while pausing a schedule prevents future firings. Job and occurrence retention use the configured Server limits.

Every active local `USER` or authenticated container principal in `server.authorization.userRole` can manage shared schedules. `ADMIN` accounts have system and account administration only. The creator is retained for audit but does not restrict which active USER can view, edit, pause, trigger, or delete a schedule. Under local authentication, the creator must remain an active USER for scheduled RUN occurrences to submit. Container roles are checked for each management request; an operator must pause schedules if the creator loses the configured role because the background scheduler cannot query the external realm.

## Replay or configure a new execution

From Jobs or Job Details, choose **Rerun with same settings** or **Configure & Rerun**. The Server creates an authorized draft and validates the current Package and Environment. The editor receives an opaque `draftRef` and fetches its sanitized definition from the Server; the definition is not put in the URL or browser storage.

Rerun preserves safely retained parameters and presents the source and current revisions before confirmation. Strict source revision selection rejects a mismatch and offers review against the current Package. Configure & Rerun opens the standard editor with supported safe settings, validates any changes, and creates an independent Job. Cancel or Back leaves the source Job and Package unchanged. Secrets, transport data, host roots, and unsafe paths are never reconstructed from logs or artifacts.

Draft references and confirmation intents are principal-bound and expire after ten minutes. They are held in Server memory, so a Server restart requires creating a new draft from the source Job. A stale Package, expired draft, removed Resource, invalid schema, or capacity rejection has a distinct response.

The [Run mode reference](run.md) covers local Native execution and output paths. The [CLI Reference](../cli.md) owns command syntax and exit codes.
