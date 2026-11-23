# 離線 Native 付款 Suite

[English](native-payments.md) · [快速入門](../quick-start.zh.md) · [測試撰寫](../reference.zh/test-authoring.md)

這是 Default Package 的離線範例。它使用兩個可重用 Flow、inline 紀錄和一個 Excel Data Source。它不會呼叫銀行、資料庫、MQ 或網路。

沒有 `suite.yaml`，也沒有 Steps DSL。Suite 就是 `run native-suite` 的選取結果。

## Case 做什麼

| Qualified Case ID | Flow | 預期執行結果 | 原因 |
|---|---|---|---|
| `payments.offline.authorize.auth-accepted` | `payments.offline.authorize.v1` | `PASS` | 金額 100 未超過上限 1000，決策為 `ACCEPTED` |
| `payments.offline.authorize.auth-rejected` | `payments.offline.authorize.v1` | `PASS` | 金額 5000 超過上限。預期的業務結果是 `REJECTED` |
| `payments.offline.authorize.auth-mismatch` | `payments.offline.authorize.v1` | `FAIL` | 決策是 `REJECTED`，但 Case 預期 `ACCEPTED` |
| `payments.offline.settle.SETTLE-OK` | `payments.offline.settle.v1` | `PASS` | 金額 40 未超過授權上限 100，決策為 `SETTLED` |
| `payments.offline.settle.SETTLE-REJECTED` | `payments.offline.settle.v1` | `PASS` | 金額 0 是業務拒絕，Case 也預期 `REJECTED` |
| `payments.offline.settle.SETTLE-MISMATCH` | `payments.offline.settle.v1` | `FAIL` | 決策是 `SETTLED`，但 Case 預期 `REJECTED` |

`att-testcase/v1` 把一筆來源紀錄解析成一個 Case。這六個 ID 就是兩個 Definition 的完整解析結果。

檔案：

```text
packages/default/templates/flows/payments/offline-authorize/flow.yaml
packages/default/templates/flows/payments/offline-settle/flow.yaml
packages/default/testcase/payments/offline-authorize/testcase.yaml
packages/default/testcase/payments/offline-settle/testcase.yaml
packages/default/testdata/payments-offline-settle.xlsx
```

## CLI

在 repository root、Local CLI 已建置後：

```sh
./att.sh validate --package
```

從每個 Flow 各選一個會通過的 Case：

```sh
./att.sh --local -p default --output-dir output --run-id payments-ids \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --qualified-case-id payments.offline.authorize.auth-accepted \
  --qualified-case-id payments.offline.settle.SETTLE-OK
```

預期整體 `PASS`，`total=2`、`passed=2`、`executionCount=2`、`skipped=0`。摘要會列出 `payments.offline.authorize.v1` 和 `payments.offline.settle.v1`。

選取 smoke Case，並排除 business-rejection tag：

```sh
./att.sh --local -p default --output-dir output --run-id payments-tags \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --tag smoke --exclude-tag business-rejection
```

預期只出現 `payments.offline.authorize.auth-accepted` 和 `payments.offline.settle.SETTLE-OK`。

執行全部 Case。兩個刻意的 assertion 失敗會讓 Suite 成為 `FAIL`：

```sh
./att.sh --local -p default --output-dir output --run-id payments-all \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --all
```

預期 `total=6`、`passed=4`、`failed=2`、`executionCount=6`、`skipped=0`。Definition 依 ID 排序，每個 Definition 保留來源紀錄順序。

立刻在同一個 `--output-dir` 重跑。`--rerun-failed` 讀取該目錄裡最新的 Native Suite pointer。同一個目錄裡稍後的非 dry-run 會取代這個 pointer。此 command 不能和 `--all`、ID 或 tag 併用，也會忽略 Skipped Case：

```sh
./att.sh --local -p default --output-dir output --run-id payments-rerun \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --rerun-failed
```

預期重跑 `payments-all` 的兩個已執行失敗：`payments.offline.authorize.auth-mismatch` 和 `payments.offline.settle.SETTLE-MISMATCH`。`total=2`、`failed=2`、`executionCount=2`、`skipped=0`。`run.yaml` 的 `retryLineage.sourceSelectedCaseCount` 仍是 6，和這次嘗試分開。Issue [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) 的 Suite 驗收仍然開啟。沒有 4.6.0 release tag。

在另一個輸出目錄於第一個失敗後停止，才不會取代上面使用的 pointer：

```sh
./att.sh --local -p default --output-dir output-failfast --run-id payments-failfast \
  run native-suite \
  --definition payments.offline.authorize \
  --definition payments.offline.settle \
  --all --fail-fast
```

`auth-accepted` 和 `auth-rejected` 通過，`auth-mismatch` 失敗，三個結算 Case 是 `SKIPPED`，因為執行沒有開始。預期 `total=6`、`passed=2`、`failed=1`、`skipped=3`、`executionCount=3`。被略過的列不是 execution。在 `output-failfast` 執行 `--rerun-failed` 只會選到 `auth-mismatch`。

## 報告

每次 Native RUN 都會寫入：

```text
output/<run-id>/report/index.html
output/<run-id>/ci/summary.json
output/<run-id>/ci/junit.xml
output/<run-id>/workbooks/native-cases.result.xlsx
output/<run-id>/executions/<execId>/case.log
output/<run-id>/executions/<execId>/case.yaml
```

`report/index.html` 會把每個 Case 連到它的 execution evidence。標準 XLSX 列出每個被選中的 Case，不依賴 Excel 版面。

結算 Definition 要求 `report: {format: xlsx, template: sourceWorkbook}`。只選取 `payments.offline.settle` 的 RUN 還會寫入 `workbooks/native-cases.source-workbook.xlsx`。比較 RUN 前後 `packages/default/testdata/payments-offline-settle.xlsx` 的 SHA-256：Package 內的 Workbook 必須不變。

同時包含兩個 Definition 的 Suite 不能使用單一來源 Workbook 版面。標準 XLSX 仍包含每個被選中的 Case，而 `run.yaml` 會記錄 `sourceWorkbookReport.diagnosticCode: MULTIPLE_DEFINITIONS`。這個 fallback 是受支援的行為，不是遺失結果。

報告驗收仍由 [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) 跟進。

## Studio

當 Server 宣告支援 `att-execution/v2`：

1. 在 Explorer 開啟 Default Package。
2. 開啟 `testcase/payments/offline-authorize/testcase.yaml` 或結算 Definition，使用 **Preview / Resolve**。Preview 不會建立 Job，也不會修改 Workbook。
3. 選擇 **Run Test Cases…**，再選擇 **Native Suite**。
4. 輸入兩個 Definition ID。可以選擇全部 Case、上面兩個 qualified ID，或 tag `smoke` 並排除 `business-rejection`。
5. 若要重跑，單獨選擇失敗 Case selector（`rerunFailed: true`）。Studio 使用和 CLI `--rerun-failed` 相同的互斥 selector。

修改只能透過既有 Change Set 路徑發佈。這個範例已經在 Package 裡，本機 checkout 不需要新的發佈就可以 RUN。
