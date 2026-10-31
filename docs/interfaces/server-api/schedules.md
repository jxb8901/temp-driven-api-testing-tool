<!-- att-doc-status: current release=4.2.8 -->

# Schedule API

Schedules are Server-owned definitions. They can submit Jobs later, but they are not Local Run IDs and do not create a Job until an occurrence is accepted.

The current REST route table, request shape, authorization and occurrence-history paths are defined in [REST API v1](v1.md#rest-api). The browser Schedule screens are described in [Studio Schedules](../studio/schedules.md).

Use the Server operational guide for package revision policy, retained history, overlap limits, shutdown and recovery behavior: [Jobs and Schedules operations](../../operations/server-jobs-schedules.md).

The legacy path name /load-schedules remains supported for the current 4.2.8 REST API. The interface does not expose future CLI management commands from #294.

## Review and replace execution settings

`POST /api/v1/schedules/{id}/execution-draft` validates an execution change without submitting a Job. Send `expectedDefinitionVersion` and either `overrides` for a RUN or inline LOAD snapshot, or `packageEdit` for a package-path LOAD schedule. The response contains a short-lived `draftId`, safe before-and-after previews, the package revision, policy, and next run. The review is bound to the authenticated operator who created it. Operators with existing Schedule permissions may review shared schedules; their previews contain only the safe Schedule summary, and the underlying execution draft is not exposed. The execution identity is rechecked when applying changes. A workload override may use `mixEdits` to update existing mix-entry weights or targets by ID. Unchanged entries retain their inputs; changing a target clears that entry’s inputs and variables.

Confirm the review with `PATCH /api/v1/schedules/{id}/execution` and a JSON body containing `draftId`, `expectedDefinitionVersion`, and `confirm: true`. A successful response keeps the Schedule ID and trigger, advances `snapshotVersion`, and applies the new execution settings to future claims. Already claimed occurrences and accepted Jobs retain their previous inputs. A stale definition, draft, or package revision requires a new review. The Schedule retains at most 20 safe snapshot history entries, including previous and new versions, actor, timestamp, revision and a generic change summary. Older entries may lack the new fields. Jobs retain their own execution evidence according to Job retention policy.

Package-path edits retain package-path execution. Package-catalog snapshots are revalidated with package-backed limits and remain snapshots, including when browser-created inline loads are disabled. Legacy schedules without a retained package revision cannot use this replacement route.
