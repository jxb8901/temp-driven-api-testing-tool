<!-- att-doc-status: current release=4.3.1 -->

# Studio Read-only validation

Studio uses bounded, read-only Server validation and displays the `att-validation/v1` report. Contextual actions in Configuration, Package Explorer, and LOAD Scenarios create durable asynchronous records. The advanced **Validate** workspace remains available at `#/validate` for its synchronous form; it is no longer a top-level sidebar destination, and existing direct links continue to work. Neither flow edits Config or Package files or creates a RUN, LOAD, Debug draft, Job, Schedule, or saved snapshot. Read-only validation does not require the immutable Package revision or its configured relative `outputDirectory` to be writable; execution readiness for actual Job/CLI output destinations remains separate.

## Choose a scope

Select a scope, then select a registered Package when required. The Package selector lists only Packages returned by the authenticated Server. A Package selection made here is route context; it does not change the global Studio Workspace.

| Scope | Target and context |
|---|---|
| Shared / Effective Config | With no Package, validate Server Runtime Config. With a Package, validate its effective Config. |
| Package | Validate a registered Package and its static dependency/resource checks. |
| Case | Select an authorized Case by its Server-issued resource ID. |
| Suite | Select a Package-relative Suite path. Explorer offers this action from a Case that reports its Suite. |
| Template, Flow, Tool | Select one authorized resource using the Server-issued canonical resource ID. |
| LOAD Scenario | Select an executable scenario path from the authorized LOAD catalog. Policy-only templates are not targets. |

Package-scoped validation uses an active Environment Profile. Choose one explicitly, or leave **Package default** selected. The request omits an Environment in the latter case, allowing Server to resolve its actual effective/default Environment. The page shows the resolved name when the Server returns it. An unavailable or unauthorized profile cannot be treated as validated.

Contextual actions open an inline record from Shared Configuration, Package Explorer, or an executable LOAD Scenario. Shared Configuration offers **Validate Shared Config** and **Validate Effective Config**. The selected source file does not change the validation scope. Package Explorer uses the opened Package and its Environment, with **Validate Package** and selected Case, Suite, Template, Flow, or Tool actions such as **Validate Case** and **Validate Suite**. The LOAD Catalog uses **Validate** for an executable scenario. These actions never scan the full Package unless you choose **Validate Package**. Helper text explains that these actions inspect without running tests or creating a Job.

## Run and review

Contextual validation opens a compact inline record panel. It looks up the newest record for the exact Package, Environment, scope, and target before admitting new work; an existing record is restored with its report. An explicit **Validate** or **Revalidate** action submits work when needed. Studio reads `GET /api/v1/validations/{validationId}` every 2.5 seconds while the record is active and the page is visible. The panel shows state, scope, Package, Environment, target, ID, outcome, applicability, and any available phase. Timing and the full report start collapsed. It does not invent a percentage or an estimated completion time. Closing or hiding the panel does not cancel or discard the record. **Open full record** opens a bookmarkable `#/validate?record=V…` route that can be reloaded later.

The **Validations** tab under **Jobs and results** is available at `#/jobs?tab=validations`. It shows owner-scoped, paginated metadata and filters by lifecycle status, Package, Environment, and scope. The list does not fetch report bodies; selecting **View details** opens only that record's detail. List applicability is `UNVERIFIED` because the metadata request does not fingerprint current sources. The detail view can refresh, cancel active work, revalidate a finished record, and open its matching Configuration, Explorer, or LOAD context. Polling pauses while the page is hidden. Servers without asynchronous validation records show an explicit unsupported state.

The synchronous advanced workspace sends one `POST /api/v1/validation` request after you select **Run Validation**. Loading the page, changing scope, choosing a Package/Environment/target, or searching the catalog does not submit validation. Studio checks `GET /api/v1/version.validationApiVersion`; the action stays disabled until the Server advertises `att-validation/v1`. Contextual actions also require `validationRecordsApiVersion: "att-validation-records/v1"`. The Server authorizes every request using the current principal, Package and Environment policy, and Origin/CSRF controls.

The report shows hierarchical checks, report completeness, counts, Server request ID, and safe returned context. Use severity, code, source, resource, and text filters to narrow findings. **Copy safe report** copies only the supported report fields and allowlisted context values; credential-shaped values and absolute paths are redacted. **Open matching resource** is available when a finding identifies the selected Explorer resource.

Studio reports `PASS` only when the Server returns `overallStatus: PASS`, `complete: true`, and `exitCode: 0`. `INVALID` reports remain invalid; incomplete or otherwise non-passing results are shown as `PARTIAL`. HTTP `200` alone is not a pass.

The report distinguishes transport state from validation outcome. `COMPLETED` can contain `PASS`, `INVALID`, or `PARTIAL`; only `overallStatus: PASS`, `complete: true`, and `exitCode: 0` represent a pass. Source applicability is `CURRENT` when the Server can verify the captured revision is unchanged, `STALE` when the authorized source has changed, and `UNVERIFIED` when a stable comparison is unavailable. **Cancel validation** calls `POST /api/v1/validations/{validationId}/cancel`. Closing the panel, navigating away, or stopping polling does not cancel Server work. Returning to the contextual source restores its exact matching record; changing the Studio locale does not resubmit validation. The record and report remain owner-scoped and durable until retention or storage cleanup removes them. A Server restart marks active records `INTERRUPTED`.

## Access and presentation

Operational users can validate only scopes the Server authorizes. Installation-wide validation is not exposed by the Studio workspace. The Server stores records for seven days by default, with configurable count and byte limits. A user can open only their own records; the Server rechecks Package and Environment access when records are read. Unsupported Server builds do not advertise the asynchronous capability. The synchronous workspace remains available when `att-validation/v1` is advertised.

Controls use labeled native inputs, visible focus, keyboard operation, and live status messages. The layout adapts to narrow screens and follows Studio light/dark theme tokens. English and Traditional Chinese labels follow the Studio language preference. Static assets and API calls resolve relative to the deployed Tomcat context path, including non-root paths.

See the [Server Validation API](../server-api/validation.md) for the request, report, authorization, and error contract.
