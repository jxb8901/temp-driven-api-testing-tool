# Offline Native payments Suite

[繁體中文](native-payments.zh.md) · [Quick Start](../quick-start.md) · [Test authoring](../reference/test-authoring.md)

This walkthrough is an offline Default Package example. It uses two reusable Flows, inline records, and one Excel Data Source. It does not call a bank, database, MQ, or network, and it does not replace `payment2` or `payment_regression`. Those legacy suites stay in place because Stage/Template equivalence is still open under [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398).

There is no `suite.yaml` and no Steps DSL. The Suite is the `run native-suite` selection.

## What the Cases do

| Qualified Case ID | Flow | Expected execution | Why |
|---|---|---|---|
| `payments.offline.authorize.auth-accepted` | `payments.offline.authorize.v1` | `PASS` | Amount 100 is within limit 1000, so the decision is `ACCEPTED` |
| `payments.offline.authorize.auth-rejected` | `payments.offline.authorize.v1` | `PASS` | Amount 5000 is above the limit. The expected business outcome is `REJECTED` |
| `payments.offline.authorize.auth-mismatch` | `payments.offline.authorize.v1` | `FAIL` | The decision is `REJECTED`, but the Case expects `ACCEPTED` |
| `payments.offline.settle.SETTLE-OK` | `payments.offline.settle.v1` | `PASS` | Amount 40 is within the authorized cap 100, so the decision is `SETTLED` |
| `payments.offline.settle.SETTLE-REJECTED` | `payments.offline.settle.v1` | `PASS` | Amount 0 is a business rejection, and the Case expects `REJECTED` |
| `payments.offline.settle.SETTLE-MISMATCH` | `payments.offline.settle.v1` | `FAIL` | The decision is `SETTLED`, but the Case expects `REJECTED` |

`att-testcase/v1` resolves one source record to one Case. These six IDs are the complete resolution of the two Definitions.

Files:

```text
packages/default/templates/flows/payments/offline-authorize/flow.yaml
packages/default/templates/flows/payments/offline-settle/flow.yaml
packages/default/testcase/payments/offline-authorize/testcase.yaml
packages/default/testcase/payments/offline-settle/testcase.yaml
packages/default/testdata/payments-offline-settle.xlsx
```

## CLI

From the repository root, after the Local CLI is built:

```sh
./att.sh validate --package
```

Select both passing Cases, one from each Flow:

```sh
./att.sh --local -p default --output-dir output --run-id payments-ids \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --qualified-case-id payments.offline.authorize.auth-accepted \
  --qualified-case-id payments.offline.settle.SETTLE-OK
```

Expect overall `PASS`, `total=2`, `passed=2`, `executionCount=2`, `skipped=0`. The summary lists `payments.offline.authorize.v1` and `payments.offline.settle.v1`.

Select the smoke Cases and drop the business-rejection tag:

```sh
./att.sh --local -p default --output-dir output --run-id payments-tags \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --tag smoke --exclude-tag business-rejection
```

Expect `payments.offline.authorize.auth-accepted` and `payments.offline.settle.SETTLE-OK` only.

Run every Case. The two deliberate assertion failures make the Suite `FAIL`:

```sh
./att.sh --local -p default --output-dir output --run-id payments-all \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --all
```

Expect `total=6`, `passed=4`, `failed=2`, `executionCount=6`, `skipped=0`. Definitions run in ID order, and each Definition keeps source-record order.

Stop after the first failure:

```sh
./att.sh --local -p default --output-dir output --run-id payments-failfast \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --all --fail-fast
```

`auth-accepted` and `auth-rejected` pass, `auth-mismatch` fails, and the three settlement Cases are `SKIPPED` because execution did not start. Expect `total=6`, `passed=2`, `failed=1`, `skipped=3`, `executionCount=3`. Skipped rows are not executions.

Retry only the failed executed Cases from that output directory. `--rerun-failed` is implemented on this branch for Local CLI and Studio. It cannot be combined with `--all`, IDs, or tags. It ignores skipped Cases:

```sh
./att.sh --local -p default --output-dir output --run-id payments-rerun \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --rerun-failed
```

Run this against the same `--output-dir` as `payments-all`, not the fail-fast run, when you want both assertion failures retried. The new RUN reports only its own selection. Its `run.yaml` `retryLineage` keeps the source selected count separate from this attempt's execution count. Issue [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) remains open for Suite acceptance. No 4.6.0 release tag exists.

## Reports

Every Native RUN writes:

```text
output/<run-id>/report/index.html
output/<run-id>/ci/summary.json
output/<run-id>/ci/junit.xml
output/<run-id>/workbooks/native-cases.result.xlsx
output/<run-id>/executions/<execId>/case.log
output/<run-id>/executions/<execId>/case.yaml
```

`report/index.html` links each Case to its execution evidence. The standard XLSX lists every selected Case and does not depend on the Excel layout.

The settlement Definition requests `report: {format: xlsx, template: sourceWorkbook}`. A RUN that selects only `payments.offline.settle` also writes `workbooks/native-cases.source-workbook.xlsx`. Compare the SHA-256 of `packages/default/testdata/payments-offline-settle.xlsx` before and after that RUN: the Package workbook must be unchanged.

A Suite that includes both Definitions cannot use one source-workbook layout. The standard XLSX still contains every selected Case, and `run.yaml` records `sourceWorkbookReport.diagnosticCode: MULTIPLE_DEFINITIONS`. That fallback is the supported behavior, not a missing result.

Report acceptance remains open under [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397).

## Studio

When the Server advertises `att-execution/v2`:

1. Open the Default Package in Explorer.
2. Open `testcase/payments/offline-authorize/testcase.yaml` or the settlement Definition and use **Preview / Resolve**. Preview does not create a Job and does not modify the workbook.
3. Choose **Run Test Cases…**, then **Native Suite**.
4. Enter both Definition IDs. Choose all Cases, the two qualified IDs above, or tag `smoke` with exclude tag `business-rejection`.
5. To retry, choose the failed-case selector (`rerunFailed: true`) by itself. Studio uses the same exclusive selector as CLI `--rerun-failed`.

Publish edits only through the existing Change Set path. This example is already in the Package, so a local checkout can RUN without a new publish.
