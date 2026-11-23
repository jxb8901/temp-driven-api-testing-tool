<!-- att-doc-status: planned release=4.6.0 issue=413 acceptance=pending -->
# Default Package Native Case migration matrix (S12 / #413)

This record tracks Default Package migration on the current default branch. No 4.6.0 release or tag has been published. Native RUN, Excel Data Source, Studio editing, implicit Suites, and Native reports are implemented and integrated on master. They are not open stacked PRs. Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed for Native RUN acceptance. Suite acceptance ([#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396)), report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)), and S12 adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) remain open. Shared-editor deployment evidence is separate ([#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387)).

## Classification legend

| Class | Meaning |
|---|---|
| **Native canonical example** | Checked-in `att-testcase/v1` Definition meant as the primary getting-started path |
| **Supported legacy compatibility fixture** | Older Workbook/Sidecar/Snapshot (or LOAD) path that remains runnable and labeled |
| **Active example awaiting migration** | Still useful; Native parallel only when semantics are proven |
| **Deferred** | Needs a later workstream (Suite, reports, Stage/Flow equivalence, LOAD Case targets, …) |
| **Blocked tracked exception** | Stays at its current path. A copy under `packages/legacy` without a cutover is not a migration. The reason and dependency are recorded |
| **Relocated legacy** | Physically moved under `examples/legacy/` with a README that says it is not the current path |

## Resource inventory and decisions

| Resource | Schema / mode | Consumers | Decision | Reason | Evidence |
|---|---|---|---|---|---|
| `packages/default/testcase/quick-start/single/testcase.yaml` | `att-testcase/v1` inline singleton → Case `default` | Quick Start EN/ZH, README, CLI Native RUN | **Native canonical example** | Offline Flow-bound Definition; no workbook | `./att.sh --local -p default run native-case quick.start.single --resolved-case-id default` |
| `packages/default/testcase/quick-start/inline-multi/testcase.yaml` | `att-testcase/v1` inline multi → `basic`, `with-tool` | Quick Start multi-Case demo | **Native canonical example** | Stable Qualified IDs from `caseIdExpression` | `… run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool` |
| `packages/default/testcase/quick-start/excel/testcase.yaml` + `testdata/quick-start-native.xlsx` | `att-testcase/v1` Excel DS → `QS001`, `QS002` | Quick Start Excel Data Source demo | **Native canonical example** | Read-only workbook provenance; no Snapshot | `… run native-case quick.start.excel --resolved-case-id QS001` |
| `packages/default/templates/flows/quick-start/flow.yaml` (`quick.start.v1`) | `att-flow/v3.6` | Bound by the three Definitions above | **Native canonical example** | Offline assign/log/assert (+ optional `sample.getAcDate`) | Package validate + Native RUN |
| `packages/default/testcase/quick_start.xlsx` + `.yaml` + `.xml` | Sidecar `att-sidecar/v2.2` + Snapshot `att-testcases/v2.4` + Template `QUICK_START` | Legacy Quick Start appendix, `QuickStartDocumentationTest`, packaging | **Supported legacy compatibility fixture** | Retain original Case IDs and Stage/Template path; do not rewrite | `./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001` |
| `packages/default/templates/QUICK_START/template.yaml` | `att-template/v3.6` | Legacy Quick Start Stage | **Supported legacy compatibility fixture** | Stage/Template entry remains valid; Native path uses Flow instead | Legacy suite RUN |
| `packages/default/testcase/payment2.xlsx` (+ sidecar/XML) | Legacy single-Stage payment demo | Docs/debug examples, `DocumentationExamplesTest` | **Retained legacy** (no parallel Native yet) | Business Template stages and external-style inputs; not offline-safe as a Native Quick Start | Dry-run suite remains green; Native conversion deferred pending #398 semantics review |
| `packages/default/testcase/payment_regression.xlsx` (+ sidecar/XML) | Legacy multi-Stage prepare/invoke/verify/rollback/cleanup | Payment regression coverage | **Retained legacy** (no parallel Native yet) | Per-Stage Template selection, `runWhen`/`onFailure`, and Case ID mapping are not 1:1 with a single Flow binding (#398) | Offline dry-run: `./att.sh run --suite testcase/payment_regression.xlsx --dry-run` → `total=22`, `skipped=22` (no FPP/MQ/DB I/O). Package validate counts the suite in `suites=3` / `cases=26`. Full business RUN against live systems is integration-only and owned by #398; not exercised here. |
| `packages/default/templates/flows/common/*` | Nested identity/decorate/compose Flows | Cross-mode examples | **Unchanged** | Already Flow-native helpers | Existing LoadCrossMode / Package validate |
| `packages/default/tools/**` | Package-owned Tools | Legacy + Native Quick Start sample Tool | **Unchanged** | #352 ownership preserved | Package validate |
| `examples/load/**` | `att-load/v1.9` direct targets | LOAD guides | **Supported legacy / current LOAD path** | Native LOAD Case targets are S08 (deferred) | Existing load smoke examples |
| Implicit Native Suite (`run native-suite`, including rerun-failed) | S06 / #396, `att-execution/v2` | CLI, Studio Run editor, Engine | **Implemented and integrated on master; acceptance open** | Not an open stacked PR. Studio Explorer, CLI, and Reference agree that rerun-failed retries only failed executed Cases. #396 remains open. No 4.6.0 release tag | [S06 #396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) |
| Native standard XLSX (`workbooks/native-cases.result.xlsx`) and optional source-workbook XLSX | S07 / #397, `att-testcase/v1` report | Native RUN | **Implemented and integrated on master; acceptance open** | Not an open stacked PR. Multi-Definition Suites fall back to the standard workbook. #397 remains open. No 4.6.0 release tag | [S07 #397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) |
| Studio Native editor + Preview | S05 / #395 | Studio | **Implemented and integrated on master; acceptance open** | Preview/Resolve for `testcase.yaml`. #395 remains open. No 4.6.0 release tag | [S05 #395](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/395) |

## Before / after (Default Package user-facing Case sources)

| Before (master without S12) | After (this PR) |
|---|---|
| Only legacy `quick_start` / `payment2` / `payment_regression` under `testcase/` | Same three legacy suites, the Quick Start Native Definitions, and the offline payments Suite under `testcase/payments/` |
| Quick Start docs lead with Excel → Stage → Template | Quick Start docs lead with Package → Flow → `testcase.yaml` → Preview/Resolve → Select → RUN |
| No Default Package `att-testcase/v1` example | Offline Native single / inline-multi / Excel examples with Flow `quick.start.v1` |

## Smoke commands (Local, offline)

```sh
# Native — resolve only
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default --dry-run

# Native — execute
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
./att.sh --local -p default run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool
./att.sh --local -p default run native-case quick.start.excel --resolved-case-id QS001 --resolved-case-id QS002

# Legacy Quick Start retained
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001

# Retained multi-Stage fixture — offline dry-run only (no live FPP/MQ/DB)
./att.sh run --suite testcase/payment_regression.xlsx --dry-run --format json --quiet

# Package closure (legacy + native together)
./att.sh validate --package --format json --quiet
```


## Tracked exceptions (#426)

`payment2`, `payment_regression`, the payment Stage templates, the FPP templates, `V3_FLOW_EXAMPLE`, and `付款/本地付款` stay in `packages/default`. Issue [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398) has not produced the golden evidence required before a Flow translation. Issue [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418) keeps direct LOAD targets on the legacy path. These files are also the verified compatibility route used by package validation, the legacy Quick Start, and the payment regression dry-run. Moving them into `packages/legacy` without changing every consumer would be a duplicate without a cutover, which this migration does not do. `packages/legacy` is therefore not created.

The physical relocation in this change is the former SUT Metrics Settings catalog, now under `examples/legacy/sut-metrics/`. The v2 schema samples stay at `examples/load/sut-metrics/v2/` because server contract tests load them there. The current ownership example stays at `examples/package-local-sut-metrics/`.

The per-file table is [example-migration-matrix.md](example-migration-matrix.md). The machine-readable inventory is [../example-inventory.json](../example-inventory.json).

## Current-branch boundary and pending acceptance

- Forced conversion or deletion of `payment2` / `payment_regression` / committed Snapshots
- S06 and S07 acceptance owned by their own issues; this S12 matrix does not close [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) or [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)
- Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)), and Helper rollback/teardown verification ([#421](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/421)) are follow-ups outside the 4.6.0 target. ADR approval/architecture closure is tracked separately under [#422](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/422) and is not a target release blocker.
- Automatic Workbook→Flow conversion or Stage/Template retirement (#398)
