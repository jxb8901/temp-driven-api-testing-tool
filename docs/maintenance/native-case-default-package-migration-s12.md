<!-- att-doc-status: current release=4.6.0-dev issue=413 -->
# Default Package Native Case migration matrix (S12 / #413)

Baseline: branch stacked on S05 PR #409 (`codex/issue-395-studio`), which already includes S03 Native RUN and S04 Excel Data Source. This record is the before/after inventory for issue #413. It is **not** a claim that S06 implicit Suites or S07 source-independent native XLSX reports are shipped on this head.

## Classification legend

| Class | Meaning |
|---|---|
| **Native canonical example** | Checked-in `att-testcase/v1` Definition meant as the primary getting-started path |
| **Supported legacy compatibility fixture** | Older Workbook/Sidecar/Snapshot (or LOAD) path that remains runnable and labeled |
| **Active example awaiting migration** | Still useful; Native parallel only when semantics are proven |
| **Deferred** | Needs a later workstream (Suite, reports, Stage/Flow equivalence, LOAD Case targets, …) |

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
| Implicit Native Suite (`run native-suite`) | S06 / #396 | — | **Deferred** | Requires S06 PR; not on S05 base | Documented as deferred in Quick Start |
| Native standard XLSX without workbook (`workbooks/native-cases.result.xlsx`) | S07 / #397 | — | **Deferred** | Requires S07 PR; HTML/CI evidence from existing RUN path only on this head | Documented as deferred |
| Studio Native editor + Preview | S05 / #395 | Studio | **Shipped on this base** | Preview/Resolve for `testcase.yaml` | Reference test-authoring Studio section |

## Before / after (Default Package user-facing Case sources)

| Before (master without S12) | After (this PR) |
|---|---|
| Only legacy `quick_start` / `payment2` / `payment_regression` under `testcase/` | Same three legacy suites **plus** three Native Definitions under `testcase/quick-start/**/testcase.yaml` |
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


## Intentionally out of scope on this PR

- Forced conversion or deletion of `payment2` / `payment_regression` / committed Snapshots
- Implicit Suite descriptor or `native-suite` documentation as shipped behavior
- Claiming S07 native workbook reports or S08 LOAD Case targets
- Automatic Workbook→Flow conversion or Stage/Template retirement (#398)
