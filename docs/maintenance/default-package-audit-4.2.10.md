<!-- att-doc-status: historical release=4.2.10 -->
<!-- att-docs:historical -->
# Default Package maintenance audit — ATT 4.2.10

This audit records the before/after evidence for issue #368. The baseline is commit `0ec52acfd2bbe6981d65d322da7b8d9ae5b5d2d8`, the source state before the #368 cleanup. Schema checks use the Engine's embedded registry and schemas under `schemas/`; no external `ATT_HOME/schemas` copy was used.

## Inventory and disposition

| Item | Before | Change or retained classification | Evidence |
|---|---|---|---|
| Global FPP Tool group | `config/tools/fpp.yaml` | Deleted as an equivalent duplicate; Package manifest and 8 Package-owned Tools retained under `packages/default/tools/fpp/`. | Member IDs, arguments, command/call, stdout format and backend contracts compared: 8/8 equivalent. |
| Global Orders Tool group | `config/tools/orders-db.yaml` | Deleted as an equivalent duplicate; Package manifest and 4 Package-owned Tools retained under `packages/default/tools/orders/`. | Same contract comparison: 4/4 equivalent. |
| Global Sample Tool group | `config/tools/sample.yaml` | Deleted as an equivalent duplicate; Package manifest and 2 Package-owned Tools retained under `packages/default/tools/sample/`. | Same contract comparison: 2/2 equivalent. |
| Tool ownership | `config/config.yaml` has no `tools` or `toolGroups`. | Package-local descriptors, manifests, dispatcher and executable scripts remain; central DB, MQ, HTTP and SSH Helpers remain configured. | Current Package validation resolves 21 Tools and the 3 groups with no diagnostics. |
| Migration fixtures | `tools/test_package_tools_migration.py` and isolated legacy test fixtures. | Retained for compatibility/migration coverage; they are not active default configuration. | Fixtures continue to exercise the explicit migration path. |
| Testcase assets | 3 `.xlsx` suites, 3 `.xml` snapshots and 3 `.yaml` sidecars. | Retained; checked through the real Package validator and documented entry-point tests. | 3 suites / 26 cases; no snapshot, workbook, sidecar or reference diagnostics. |

The 14 migrated Tool members remain available under the same IDs and argument/backend contracts. The checked-in Helper configuration is unchanged. Active docs, build checks and tests now point to Package-owned Tool paths; the generated document inventory no longer lists the three deleted global files.

## Current Package schema inventory

The deterministic `DocumentationExamplesTest.defaultPackageSchemaVersionsMatchTheCurrentEngineRegistry` gate walks each Package YAML file, resolves the schema from the current embedded Engine registry, validates its contents and compares the exact inventory below.

| Current schema | Count |
|---|---:|
| `att-package/v1` | 1 |
| `att-template/v3.6` | 10 |
| `att-flow/v3.6` | 3 |
| `att-sidecar/v2.2` | 3 |
| `att-tool-group/v3.0` | 3 |
| `att-tool/v1` | 21 |
| `att-debug/v1.2` | 1 |
| `att-testdata/v1.0` | 1 |

All 43 schema-declared YAML resources resolve and validate. Package validation additionally checks Template/Flow/Tool contracts, reference closure, source paths, declared environments and Testcase mappings against the checked-in workbook, XML and sidecar assets. No schema version bump was needed. The `V3_FLOW_EXAMPLE` description was corrected without changing its stable ID or path. The FPP sample's `sysdate` calls now use the documented canonical `date.sysdate` name with the same format argument.

## Expression and cross-mode audit

| Resource or field | Classification and result |
|---|---|
| `common.identity.v1` / Action `identityValue` | Reusable across Run, Debug, Quick Load and Advanced Load. Uses `#{misc.coalesce(${META.SOURCE.caseId?}, ${EXEC.ID})}`: Run/Debug preserve the source Case ID; Load uses its iteration ID. |
| `common.decorate.v1` and `common.compose.v1` | Reusable nested Flow chain. Decoration uses the same optional source-ID / execution-ID choice. |
| `V3_FLOW_EXAMPLE` | Assertions and expected values use the selected identity and canonical `str.concat`; its schema and stable name remain unchanged. |
| `QUICK_START` | Its log now uses the same identity rule, preserving Case IDs and showing the Load iteration ID when run in Load. |
| `PAYMENT_PREPARE`, `PAYMENT_INVOKE`, `PAYMENT_VERIFY`, `PAYMENT_ROLLBACK`, `PAYMENT_CLEANUP`, `FPP_CTO_RTI_USMF`, `FPP_D3I_BATCH_SIT`, local payment | Intentionally business/TestCase-specific Run/Debug examples. Active EN and Traditional Chinese Context docs state that they require an explicit business-ID input contract before use as Quick or Advanced Load targets. No integration Tool, DB, MQ, HTTP or shell action was executed for this audit. |
| `CASE.environment` / `CASE.status` | `CASE.environment` is initialized from the selected profile in Run, Debug and Load. `CASE.status` and `CASE.durationMs` are lifecycle/result values, not portable pre-action inputs; the rollback example is documented as Run/Debug only. |
| Other candidate syntax | Active default Package Context references were reviewed. Remaining `EXEC.INPUT`, `EXEC.VARS` and `EXEC.ACTIONS` references are covered by the current validators. There are no active `${CASE.*}` shorthand references beyond the documented environment/status fields, and no uses of removed `dbText`, `prettyPrint` or local `file.*` built-ins. |

Offline cross-mode regression coverage in `LoadCrossModeTest` copies the default Template and nested Flows into a temporary fixture and executes Run, Debug, Quick Load and Advanced Load without external services. Each mode passes; the Load tests assert the decorated execution ID and the return value at the nested `identityValue` Action. A separate Quick Load test confirms an unsupported business-ID-only `PAYMENT_ROLLBACK` call still fails at its source Action, `rollbackSummary`, with guidance that `META.SOURCE.caseId` is only available in Run/Debug and a business ID must be supplied explicitly. This preserves the restriction instead of inventing an identity fallback for business transactions.

## ATT `validate` diagnostics

Baseline was built from commit `0ec52acf` before the #368 edits; final commands use the candidate source tree. The JSON report schema is `att-validation/v2.1`.

| Validation command | Baseline | Final |
|---|---|---|
| `./att.sh validate --package --format json --quiet` | PASS; 0 errors, 0 warnings, `diagnostics: []`; 3 suites, 26 cases, 10 templates, 21 Tools. | PASS; same counts and zero diagnostics. |
| `./att.sh validate --config config/config.yaml --env SIT --package --format json --quiet` | PASS; 0 errors, 0 warnings, `diagnostics: []`; same counts. | PASS; same counts and zero diagnostics. |
| `./att.sh validate --config config/config.yaml --env UAT --package --format json --quiet` | PASS; 0 errors, 0 warnings, `diagnostics: []`; same counts. | PASS; same counts and zero diagnostics. |

Final report summary for each command: `attVersion=4.2.10`, `valid=true`, `errors=0`, `warnings=0`, `suites=3`, `cases=26`, `templates=10`, `tools=21`. The source-checkout launcher prints a Log4j2 “no logging implementation” status message to stderr; it is not present in the validation report and does not indicate a Package warning. Validation is read-only; the checks did not launch business resources or mutate the Package.

The original LOAD runtime finding was not visible to static `att validate`: Quick Load failed in `flows/common/identity/flow.yaml`, Action `identityValue`, while resolving `${META.SOURCE.caseId}`. The supported shared Flow path now passes offline in all four modes. Intentionally mode-specific business examples remain restricted and produce an actionable source-Action diagnostic when invoked from Load.

## Verification record

- `mvn -pl att-engine -am -Dtest=LoadCrossModeTest -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test` — passed; 17 tests, including nested Flow Run/Debug/Quick Load/Advanced Load and the mode-specific diagnostic.
- `mvn -pl att-cli -am -Dtest=DocumentationExamplesTest -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test` — passed; documentation examples, Package SIT/UAT checks and all Package YAML schema checks.
- `python3 tools/build_reference_manual.py`, `python3 -B tools/build_documentation_inventory.py`, `python3 tools/verify_documentation.py`, and `python3 tools/validate_reference_content.py` — passed.
- `./att.sh validate` for Package, SIT and UAT — passed as recorded above.
<!-- /att-docs:historical -->
