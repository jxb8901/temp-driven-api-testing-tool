<!-- att-doc-status: current release=4.2.10 -->

# Studio Read-only validation

Studio uses bounded, read-only Server validation and displays the `att-validation/v1` report. Contextual actions in Configuration, Package Explorer, and LOAD Scenarios create durable asynchronous records. The advanced **Validate** workspace at `#/validate` retains the synchronous validation form. Neither flow edits Config or Package files or creates a RUN, LOAD, Debug draft, Job, Schedule, or saved snapshot.

## Choose a scope

Select a scope, then select a registered Package when required. The Package selector lists only Packages returned by the authenticated Server. A Package selection made here is route context; it does not change the global Studio Workspace.

| Scope | Target and context |
|---|---|
| Shared / Effective Config | With no Package, validate Server Runtime Config. With a Package, validate its effective Config. |
| Package | Validate a registered Package and its static dependency/resource checks. |
| Case | Select an authorized Case by logical Case ID. |
| Suite | Select a Package-relative Suite path. Explorer offers this action from a Case that reports its Suite. |
| Template, Flow, Tool | Select one authorized resource using the Server-issued canonical resource ID. |
| LOAD Scenario | Select an executable scenario path from the authorized LOAD catalog. Policy-only templates are not targets. |

Package-scoped validation uses an active Environment Profile. Choose one explicitly, or leave **Package default** selected. The request omits an Environment in the latter case, allowing Server to resolve its actual effective/default Environment. The page shows the resolved name when the Server returns it. An unavailable or unauthorized profile cannot be treated as validated.

Contextual actions open an inline record from Shared Configuration, Package Explorer, or an executable LOAD Scenario. Shared Configuration provides separate actions for global Runtime Config and that Package's effective Config. The selected source file does not change the validation scope. Package Explorer uses the opened Package and its Environment, with a separate **Read-only validate Package** action and selected Case, Suite, Template, Flow, or Tool actions. LOAD Scenario validation uses the opened scenario, Package, and Environment. These actions never scan the full Package unless you choose **Read-only validate Package**.

## Run and review

Contextual validation submits to `POST /api/v1/validations` and receives a durable record ID after the Server accepts the work. Studio reads `GET /api/v1/validations/{validationId}` every 2.5 seconds while the view is visible. It shows `QUEUED` or `RUNNING`, lifecycle times, queue and execution duration, and any available phase. It does not invent a percentage or an estimated completion time. The shell links to the newest active or recent owner-scoped record. **Open full record** opens a bookmarkable `#/validate?record=V…` route that can be reloaded later.

The synchronous advanced workspace sends one `POST /api/v1/validation` request after you select **Run Validation**. Loading the page, changing scope, choosing a Package/Environment/target, or searching the catalog does not submit validation. Studio checks `GET /api/v1/version.validationApiVersion`; the action stays disabled until the Server advertises `att-validation/v1`. Contextual actions also require `validationRecordsApiVersion: "att-validation-records/v1"`. The Server authorizes every request using the current principal, Package and Environment policy, and Origin/CSRF controls.

The report shows hierarchical checks, report completeness, counts, Server request ID, and safe returned context. Use severity, code, source, resource, and text filters to narrow findings. **Copy safe report** copies only the supported report fields and allowlisted context values; credential-shaped values and absolute paths are redacted. **Open matching resource** is available when a finding identifies the selected Explorer resource.

Studio reports `PASS` only when the Server returns `overallStatus: PASS`, `complete: true`, and `exitCode: 0`. `INVALID` reports remain invalid; incomplete or otherwise non-passing results are shown as `PARTIAL`. HTTP `200` alone is not a pass.

The report distinguishes transport state from validation outcome. `COMPLETED` can contain `PASS`, `INVALID`, or `PARTIAL`; only `overallStatus: PASS`, `complete: true`, and `exitCode: 0` represent a pass. Historical records show `UNVERIFIED` source applicability, so an old pass is not treated as current. **Cancel validation** calls `POST /api/v1/validations/{validationId}/cancel`. Closing the panel, navigating away, or stopping polling does not cancel Server work. The record and report remain owner-scoped and durable until retention or storage cleanup removes them. A Server restart marks active records `INTERRUPTED`.

## Access and presentation

Operational users can validate only scopes the Server authorizes. Installation-wide validation is not exposed by the Studio workspace. The Server stores records for seven days by default, with configurable count and byte limits. A user can open only their own records; the Server rechecks Package and Environment access when records are read. Unsupported Server builds do not advertise the asynchronous capability. The synchronous workspace remains available when `att-validation/v1` is advertised.

Controls use labeled native inputs, visible focus, keyboard operation, and live status messages. The layout adapts to narrow screens and follows Studio light/dark theme tokens. English and Traditional Chinese labels follow the Studio language preference. Static assets and API calls resolve relative to the deployed Tomcat context path, including non-root paths.

See the [Server Validation API](../server-api/validation.md) for the request, report, authorization, and error contract.
