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
| `packages/default/flows/quick-start/flow.yaml` (`quick.start.v1`) | `att-flow/v3.6` | Bound by the three Definitions above | **Native canonical example** | Offline assign/log/assert (+ optional `sample.getAcDate`) | Package validate + Native RUN |
| `packages/legacy/testcase/quick_start.xlsx` + `.yaml` + `.xml` | Sidecar `att-sidecar/v2.2` + Snapshot `att-testcases/v2.4` + Template `QUICK_START` | Legacy Quick Start guide, `QuickStartDocumentationTest`, packaging | **Relocated legacy** | Retain original Case IDs and Stage/Template path; do not rewrite | `ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001` |
| `packages/legacy/templates/QUICK_START/template.yaml` | `att-template/v3.6` | Legacy Quick Start Stage | **Relocated legacy** | Stage/Template entry remains valid; Native path uses Flow instead | Legacy suite RUN |
| `packages/legacy/testcase/payment2.xlsx` (+ sidecar/XML) | Legacy single-Stage payment demo | Docs/debug examples, `DocumentationExamplesTest` | **Relocated legacy** | Business Template stages and external-style inputs; not offline-safe as a Native Quick Start. Case IDs unchanged | Dry-run from `packages/legacy` remains green |
| `packages/legacy/testcase/payment_regression.xlsx` (+ sidecar/XML) | Legacy multi-Stage prepare/invoke/verify/rollback/cleanup | Payment regression coverage | **Relocated legacy** | Per-Stage Template selection, `runWhen`/`onFailure`, and Case ID mapping are not 1:1 with a single Flow binding | Offline dry-run from `packages/legacy`: `./att.sh run --suite testcase/payment_regression.xlsx --dry-run` → `total=22`, `skipped=22` (no FPP/MQ/DB I/O). Full business RUN against live systems is not part of this move |
| `packages/default/flows/common/*` | Nested identity/decorate/compose Flows | Cross-mode examples | **Unchanged** | Already Flow-native helpers | Existing LoadCrossMode / Package validate |
| `packages/default/tools/**` | Package-owned Tools | Legacy + Native Quick Start sample Tool | **Unchanged** | #352 ownership preserved | Package validate |
| `examples/load/**` | `att-load/v1.9` direct targets | LOAD guides | **Supported legacy / current LOAD path** | Native LOAD Case targets are S08 (deferred) | Existing load smoke examples |
| Implicit Native Suite (`run native-suite`, including rerun-failed) | S06 / #396, `att-execution/v2` | CLI, Studio Run editor, Engine | **Implemented and integrated on master; acceptance open** | Not an open stacked PR. Studio Explorer, CLI, and Reference agree that rerun-failed retries only failed executed Cases. #396 remains open. No 4.6.0 release tag | [S06 #396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) |
| Native standard XLSX (`workbooks/native-cases.result.xlsx`) and optional source-workbook XLSX | S07 / #397, `att-testcase/v1` report | Native RUN | **Implemented and integrated on master; acceptance open** | Not an open stacked PR. Multi-Definition Suites fall back to the standard workbook. #397 remains open. No 4.6.0 release tag | [S07 #397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) |
| Studio Native editor + Preview | S05 / #395 | Studio | **Implemented and integrated on master; acceptance open** | Preview/Resolve for `testcase.yaml`. #395 remains open. No 4.6.0 release tag | [S05 #395](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/395) |

## Before / after (Default Package user-facing Case sources)

| Before (master without S12) | After (this PR) |
|---|---|
| Legacy `quick_start` / `payment2` / `payment_regression` examples alongside Native examples in `packages/default/testcase/` | The `quick_start` workbook and the payment examples live in `packages/legacy`. Native Quick Start Definitions and the offline payments Suite stay under `packages/default/testcase/` |
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

# Legacy Quick Start retained in the Legacy Package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001

# Relocated multi-Stage fixture — launcher selects packages/legacy (no live FPP/MQ/DB)
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run --format json --quiet

# Package closure
./att.sh validate --package --format json --quiet
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package --format json --quiet
```

## Relocated legacy resources

[#426](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/426) moves the former blocked Templates and workbooks into the registerable `packages/legacy` package. `package.yaml` is the manifest. `config/att-server.example.yaml` does not list `legacy`; a Server deployment registers that id separately. Local CLI selects the directory with `ATT_PACKAGE_ROOT`. Template IDs and workbook Case IDs are unchanged. Run, Debug, and the closed LOAD scenarios that select `V3_FLOW_EXAMPLE` use `packages/legacy` as the working directory. Tool scenarios such as `closed-smoke.yaml` stay on `packages/default`. The common Flows and Tools copied into `packages/legacy` exist so that package resolves its own dependencies; the canonical copies stay in `packages/default`.

These resources are not rewritten as `att-testcase/v1`. That rewrite would change Stage selection or Case IDs. `examples/legacy/load/closed.yaml` still selects Template id `V3_FLOW_EXAMPLE`. The per-file table is [example-migration-matrix.md](example-migration-matrix.md).

The former SUT Metrics Settings catalog is under `examples/legacy/sut-metrics/`. The v2 schema samples stay at `examples/load/sut-metrics/v2/` because server contract tests load them there. The current ownership example stays at `examples/package-local-sut-metrics/`.

The machine-readable inventory is [../example-inventory.json](../example-inventory.json).

## Current-branch boundary and pending acceptance

- Rewriting `payment2` / `payment_regression` / committed Snapshots into Native Cases
- S06 and S07 acceptance owned by their own issues; this S12 matrix does not close [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) or [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)
- Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)), and Helper rollback/teardown verification ([#421](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/421)) are follow-ups outside the 4.6.0 target. ADR approval/architecture closure is tracked separately under [#422](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/422) and is not a target release blocker.
- Automatic Workbook→Flow conversion or Stage/Template retirement (#398)
