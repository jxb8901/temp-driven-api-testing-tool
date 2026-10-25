<!-- att-doc-status: current release=4.2.4 -->

# ATT Server Job and Schedule operations

## Published Live Output limits (4.2.4)

Set `server.liveOutput.maxEntryChars` (integer 1–32000, default 4000) and `server.liveOutput.maxEntryLines` (integer 1–200, default 40). For example:

```yaml
server:
  liveOutput:
    maxEntryChars: 16000
    maxEntryLines: 100
```

Restart the Server to load changes. Each newly submitted RUN, Debug or LOAD Job receives a fixed trusted policy. Package YAML and REST request fields cannot increase it. CLI presentation remains 16000 characters / 80 lines; Case Logs and artifacts keep their own policies. Existing journals retain their stored text; larger settings cannot restore discarded text. Invalid, fractional, quoted numeric, out-of-range or unknown settings fail startup.

Worker projection redacts secrets before truncation and strips ANSI/control characters. A character or line limit produces `[truncated]`. The journal retains at most 65536 bytes per event payload after UTF-8 JSON encoding. For an oversized payload it keeps the structured allow-list fields `status`, `code`, `message`, `executionId`, `caseId`, `stage`, `action`, and `executionEventType` as strings bounded to 512 characters, plus numeric `exitCode` and `durationMs`; it records up to 32 omitted field names in `droppedFields`. The payload includes `truncated: true`, a `journalLimit` object, and a bounded summary. The configured console preview is retained when possible; if console alone exceeds the byte budget, it adds `consoleTruncated: true` and `[truncated by journal]`. The Worker protocol remains capped at 1 MiB. Multibyte characters and JSON escaping can exhaust the journal budget before the character limit.

The Studio Live Output history holds 500 recent events. Dropped browser history and expired journal history have separate notices. The Events tab initially previews 4000 characters and provides an explicit expansion of the retained payload up to 65536 displayed characters. Expansion cannot recover upstream truncation.

Larger entries increase redaction/JSON CPU, SSE/replay bandwidth and journal disk use proportionally. At 16000 ASCII characters, 10000 retained events can contain about 160 MB of text per Job, before metadata; 500 browser events can contain about 8 million characters. Unicode/escaping can increase serialized bytes. Size quota usage includes pinned Jobs; monitor storage and keep defaults for high-volume LOAD unless diagnostics require more detail.


Reproduce the bounded concurrent journal/replay check with `mvn -pl att-server -am -Dtest=JobEventsTest#concurrentExpandedEntriesKeepReplayBoundedAndReportMeasuredCost -Dsurefire.failIfNoSpecifiedTests=false test`. It writes 800 expanded entries across four concurrent Jobs, reopens each journal, verifies the retained 100-entry windows and exact text, and prints elapsed milliseconds plus retained journal bytes. The WAR integration test also checks an 8000-character diagnostic through Worker → journal → SSE replay under the 16000/100 policy, including an untrusted request that tries to override it.

## Pinned evidence and pending Job deletion (4.2.4)

USER operators can pin terminal RUN, Debug and LOAD Jobs. Pin/Unpin icons persist on the first click without a confirmation or reason prompt. Pins protect the whole Job from age and size retention, survive restart, and never expire automatically. The optional API `pinReason` remains bounded to 200 characters and secret-redacted. Protected bytes still count toward storage quota.

Jobs uses server-side filters, ordering and offset pagination. `GET /api/v1/jobs` accepts `offset`, `limit` (1–100, default 100), `principal`, `principalMode` (`all`, `text`, `mine`, `schedule`), `packageId`, `executionType`, `status`, `from`, `to`, `search`, `sort` (`newest`, `oldest`, `target-asc`, `target-desc`), `pinnedOnly`, `deletion` (`all`, `pending`, `unmarked`) and `groupBy=target`. Dates use ISO-8601 instants. RUN includes Debug. Mine uses the authenticated identity; Schedule matches canonical schedule principals or persisted schedule provenance. Free text is a literal substring. The response includes `items`, authorized `total`, `offset`, `limit`, `nextOffset`, bounded principal/package suggestions and page-relevant `groups` with complete filtered counts/statuses. The current operator policy permits USER operators to see shared Job history; ADMIN accounts cannot use operator endpoints. Suggestions follow that same policy and exclude schedule IDs.

Jobs displays target identity first, with Package/Environment and a shortened, copyable full Job ID. Target identities are immutable allowlisted projections captured at submission. Historical rows without provenance show Unknown target and remain openable. Group keys include Package, Environment and the complete target-binding signature; multi-target Jobs appear once. Pages contain individual Jobs; group counts cover all matching history while page actions include collapsed members on the page. Default ordering is newest first. The footer remembers 25/50/100 rows, default 100.

The only list bulk controls are two footer menus: Pin all / Unpin all and Delete all / Undelete all. They apply to the explicit current-page ID snapshot, never unseen pages. Per-ID responses distinguish updated, already in state, rejected/skipped and failed outcomes. No checkboxes or selection dialogs are required.

Use these bounded state-setting endpoints (at most 100 IDs, deduplicated; action bodies at most 16384 bytes or the configured request limit):

- `POST /api/v1/jobs/{jobId}/pin` with `{"pinned":true}` or `false`.
- `POST /api/v1/jobs/pin` with `{"pinned":true,"jobIds":["J0123456789ABCDEF"]}`. Repeating the same bulk state returns `ALREADY_IN_STATE`.
- `POST /api/v1/jobs/{jobId}/delete` with `{"requested":true}` to mark or `{"requested":false}` to unmark. Legacy `{"confirm":true}` requests remain accepted as marks.
- `POST /api/v1/jobs/delete` with `{"requested":true,"jobIds":["J0123456789ABCDEF"]}`; use `false` for unmark.

Marking returns `MARKED` and persists `DELETE_REQUESTED`; it never recursively deletes files in the HTTP request. Unmark returns `UNMARKED` only before cleanup claim. Identical state returns `ALREADY_IN_STATE`; claimed records return `ALREADY_CLAIMED`, pinned records `PINNED_UNPIN_FIRST`, active records `NOT_TERMINAL` or `JOB_BUSY`, and recovery/quarantine fences `NOT_ELIGIBLE`. Single rejected requests return HTTP 409 with an outcome map; bulk requests return per-ID outcomes. Pending Jobs remain listable and accessible. After claim, endpoints hide the Job; unmark cannot restore artifacts. Missing records return `ALREADY_UNAVAILABLE` without implying restoration.

A bounded background task runs every five seconds, with a minimum 30-second pending grace period. Claim conditionally transitions `DELETE_REQUESTED` to `DELETING`, rechecking pins, terminal state, Worker identities/descendants, recovery, quarantine and archive lifecycle locks. Startup and periodic reconciliation finish interrupted `DELETING` cleanup; pending intent survives restart. Filesystem deletion remains idempotent and audited with the requesting actor. `DELETE /api/v1/jobs/{jobId}` retains cancellation semantics. Schedule history and package sources remain. Deletion does not immediately compact H2; independent backups remain necessary for disaster recovery.

## Schedule package changes and runtime shutdown (4.2.4)

Set `server.scheduling.packageChangePolicy` to `strict` (default), `pause`, or `ignore`. Unknown values prevent startup. Each schedule can override `packageChangePolicy`; `null` or an omitted value inherits the Server default, including older persisted schedules. Studio labels these choices **Default (Server)**, **Strict**, **Auto-pause**, and **Revalidate latest**.

- **Strict** rejects a changed retained revision with occurrence reason `package-revision-changed` and leaves the definition enabled and blocked. It preserves the original inputs and digest.
- **Auto-pause** records the same reason and atomically pauses only the affected definition, records its reason/time, and writes one `SCHEDULE_AUTO_PAUSE` audit entry. A concurrent definition edit prevents an outdated pause decision from changing that definition.
- **Revalidate latest** bypasses only equality with the retained package digest. The current registered package root, execution identity, typed target, exact Case IDs, schemas, environment, protected input classification, and limits remain validated. The Worker receives a fresh digest and checks it again before execution. The stored definition remains immutable. Audit action `SCHEDULE_SOURCE_REVALIDATED` records both revisions and whether the policy came from the Server or the schedule. Records predating the private package-registration identity pin require a reviewed replacement before accepting a changed source under this policy.

Policy is checked before recurring and manual submissions and when enabling/resuming. New schedules always require current validated inputs. Name, description, tags, recurrence, timezone, and policy can be saved on a stale schedule without replacing its source or clearing its stale/paused state. RUN inputs, target, package and environment remain immutable; validate a new RUN and create a replacement schedule to change them. Package-wide digests include shared configuration and Engine/schema identity. A change to an unrelated package file can therefore trigger the policy; target dependency digests remain a later optimization. Legacy file-backed LOAD definitions without a retained digest continue to validate their current package scenario on every firing.

The Server owns the bounded event-compaction executor used by new, recovered, and subsequently read journals. Shutdown rejects new Jobs, stops scheduling and retention producers, terminates Worker/inspection/SSE activity, drains artifact writers and queued compactions, and then closes database ownership. Every executor wait is bounded; interrupted compaction leaves the append journal available for recovery. Repeated close calls are safe. H2 registrations are reference-counted across runtimes sharing the ATT classloader; the last close deregisters only H2 drivers owned by that classloader. Container-owned JDBC drivers remain registered. Tests exercise three deployments in one Tomcat JVM, including compaction and retained schedule/journal reads after redeploy; CI also runs the redeploy case on Tomcat 11.
