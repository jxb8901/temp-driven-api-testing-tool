<!-- att-doc-status: current release=4.2.10 -->

# Studio Read-only validation

The **Validate** workspace runs bounded, synchronous Server validation and displays the `att-validation/v1` report. It is available from the sidebar, the command palette, and `#/validate`. The workspace never edits Config or Package files and never creates a RUN, LOAD, Debug draft, Job, Schedule, or saved snapshot.

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

Context actions open this workspace from Shared Configuration, an Explorer resource, or an executable LOAD Scenario. Shared Configuration offers separate shortcuts for global Runtime Config and, when a Package context is selected, that Package's effective Config. Explorer and LOAD shortcuts retain their originating Package, Environment, and selected target so they can be refreshed or shared within the authenticated Studio context.

## Run and review

Select **Run Validation** to send one `POST /api/v1/validation` request. Loading the page, changing scope, choosing a Package/Environment/target, and searching the catalog do not submit validation. Studio checks `GET /api/v1/version.validationApiVersion`; the action stays disabled until the Server advertises `att-validation/v1`. The request is still authorized by Server role, Package and Environment policy, and same-origin controls.

The report shows hierarchical checks, report completeness, counts, Server request ID, and safe returned context. Use severity, code, source, resource, and text filters to narrow findings. **Copy safe report** copies only the supported report fields and allowlisted context values; credential-shaped values and absolute paths are redacted. **Open matching resource** is available when a finding identifies the selected Explorer resource.

Studio reports `PASS` only when the Server returns `overallStatus: PASS`, `complete: true`, and `exitCode: 0`. `INVALID` reports remain invalid; incomplete or otherwise non-passing results are shown as `PARTIAL`. HTTP `200` alone is not a pass.

The page maps authentication, access, missing-resource, stale-source, request-size, media-type, Worker-capacity, and timeout errors to actionable messages. For `404` or `409`, refresh the target catalog and rerun. Cancel aborts the browser request, clears the pending result, and ignores any late response; Server-side time and resource bounds remain in effect.

## Access and presentation

Operational users can validate only scopes the Server authorizes. Installation-wide validation is not exposed by the Studio workspace. An unsupported Server version shows the required capability instead of submitting a request. Validation reports stay in memory for the current route and auth context; changing scope, Package, Environment, target, principal context, or inspection session clears the prior report and aborts an active browser request.

Controls use labeled native inputs, visible focus, keyboard operation, and live status messages. The layout adapts to narrow screens and follows Studio light/dark theme tokens. English and Traditional Chinese labels follow the Studio language preference. Static assets and API calls resolve relative to the deployed Tomcat context path, including non-root paths.

See the [Server Validation API](../server-api/validation.md) for the request, report, authorization, and error contract.
