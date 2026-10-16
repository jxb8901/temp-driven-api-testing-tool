# Unified RUN orchestration

Public contracts and upgrade examples live in [Unified RUN and schedules](../reference/execution-modes/unified-run.md). This page records adapter ownership for maintainers.

| Entry point | Validation / binding | Execution adapter | Result representation |
|---|---|---|---|
| `/drafts/run`, Case or batch | `RunDefinition` → Worker `run-input` → `PackageValidator`; freeze selected IDs and revision | `DefaultAttService.run` | Existing Run manifest, Case logs and functional report |
| `/drafts/run`, Template/Flow/Tool | `RunDefinition` → existing Debug draft inspection; preserve typed sidecar values and keep markers | `DefaultAttService.debug` through the RUN Worker command | Existing resource execution report and artifacts |
| Legacy `/jobs/run`, CLI run/validate/report | Existing Case selection and Excel/sidecar contracts | Existing functional runner | Unchanged |
| Legacy `/drafts/debug`, `/jobs/debug`, CLI debug | Existing Debug input schema and safe inspection | Existing Debug adapter | Unchanged |
| LOAD draft / legacy scenario | Existing Load scenario normalization and policy limits | Load engine and workload coordinator | Existing Load summary, telemetry and evidence |
| `/schedules` and `/load-schedules` alias | Typed stored snapshot and trigger | Shared occurrence claim → typed submit adapter | Shared jobs plus specialized result |

`WorkerRequest.diagnosticMode` is nullable: absent preserves legacy event behavior; canonical RUN always sets it. False suppresses DEBUG events at the Worker boundary, while both values retain standard progress, diagnostics and result events. Neither option changes the runner's invocation count or Server disclosure policy.

`ServerRuntime` owns identity-scoped, expiring RUN drafts. The public request never supplies a Worker inline payload or revision assertion. Validation produces those internal fields. Submission verifies revision again and the Worker rechecks immediately before execution. Resource schedules persist safe keep markers and reject submitted sensitive overrides. Case schedules persist the validated resolved IDs rather than reevaluating selectors or historical failures.

`LoadScheduleScheduler` remains the single scheduler. Its existing H2 occurrence transaction claims cron or once occurrences before submitting a job. A once claim advances `nextRunAt` to null, so restart cannot replay it. The existing process, queue, overlap, retention, audit and cancellation paths handle both types. Existing JSON schedules default to LOAD; new typed fields are additive and unknown job types fail closed. No destructive database migration is introduced. Backups must precede upgrades; downgrade requires restoring the old database or deleting new RUN/once schedules before starting older code.

The Server rechecks local account activity and administrator status before scheduled submission. Container roles are externally managed: schedule-management requests use the configured container role, and administrators must pause delegated schedules when an external account loses access. Background jobs cannot query an external container realm through the Servlet API.

Verification is split between strict contract tests, actual Worker SIT/UAT target and selection tests, scheduler claim/restart tests, authenticated non-root WAR API tests, and Studio component/browser acceptance. Existing Engine and Server suites retain the specialized report, cancellation/SSE, DST, misfire and overlap gates.
