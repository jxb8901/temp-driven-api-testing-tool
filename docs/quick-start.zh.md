# ATT v4.6.0 快速入門

[English Quick Start](quick-start.md) · [Reference Manual](reference.zh.md) · [遷移矩陣](maintenance/native-case-default-package-migration-s12.md)

維護以較早模型編寫的 Package？請使用[舊版文件](legacy/README.zh.md)。

本指南示範目前 default branch 上的 Native Case 流程。4.6.0 尚未發佈或建立 tag。inline 和 Excel Native RUN 已整合在此。Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) 的 Native RUN 驗收已關閉；關閉該 issue 並不是產品 release。Default Package 採納驗收由 [#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413) 跟進。報告驗收由 [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) 跟進。共享編輯器部署驗收由獨立的 [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387) 跟進。

Native LOAD Case target（[#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)）、進階 Testdata／generator（[#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)）及 Debug interoperability（[#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)）不屬於此發佈目標；現有直接 LOAD 和 Debug 路徑仍受支援。

本指南從乾淨的 repository checkout 開始，用最小離線例子跑通一次 **Native Case**。內容先教新的作者模型：

```text
Package → Flow → testcase.yaml（+ inline 或 Excel Data Source）
       → Preview / Resolve → 選擇 Case → Validate → RUN → Results
```

已提交的 Native 例子刻意離線：只使用 `assign`、`log`、`assert`，以及可選的 ATT 本地 sample Tool；不需要資料庫、MQ、API、credential 或網絡。

## 你將會執行甚麼

Default Package 內包含以下 Native Definition 和唯讀 Excel Data Source：

```text
packages/default/testcase/quick-start/single/testcase.yaml
packages/default/testcase/quick-start/inline-multi/testcase.yaml
packages/default/testcase/quick-start/excel/testcase.yaml
packages/default/testdata/quick-start-native.xlsx
config/config.yaml
```

| Definition ID | Resolved Case ID | 用途 |
|---|---|---|
| `quick.start.single` | `default` | 單一 Definition + typed `inputs` → 一個 Case |
| `quick.start.inline` | `basic`、`with-tool` | 單一 Definition + inline records → 多個 Case |
| `quick.start.excel` | `QS001`、`QS002` | 單一 Definition + 唯讀 Excel Data Source |

三者皆綁定 Flow `quick.start.v1`。Resolved Case 是可選擇的邏輯 Case；每次 RUN 產生獨立 execution。

## 前置條件

1. 已安裝 JDK 與 Maven（需要時可編譯 source checkout）。
2. 若缺少 class，於 repository root 編譯 Local CLI：

```sh
mvn -DskipTests -pl att-cli -am compile
```

3. Package validation 可能用到的占位環境變數（與本例子無關的 Helper）：

```sh
export ORDERS_DB_USERNAME=docs-example-user
export ORDERS_DB_PASSWORD=docs-example-password
export PAYMENT_MQ_USERNAME=docs-example-user
export PAYMENT_MQ_PASSWORD=docs-example-password
```

## 查看 Native Definition

打開 `testcase/quick-start/single/testcase.yaml`：

```yaml
schemaVersion: att-testcase/v1
id: quick.start.single
name: Native Quick Start (single Case)
flow: quick.start.v1
tags: [quick-start, smoke, native]
inputs:
  amount: 100
  useTool: false
  expected: 100
```

Actions 由 `flows/quick-start/flow.yaml` 擁有；Test Case 不會再寫一套 Steps 或 assert DSL。

## 驗證 Package

```sh
./att.sh validate --package
```

驗證是唯讀的，會一併檢查 Native Definition、Flow/Tool 依賴閉包，以及仍保留的舊式 suites。

## 只做 Resolve（dry-run）

```sh
./att.sh --local -p default run native-case quick.start.single \
  --resolved-case-id default --dry-run --format json
```

Dry-run 會解析並驗證選中的 Case ID，不會執行 Flow Actions。

## RUN 單一 Case

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-single \
  run native-case quick.start.single --resolved-case-id default
```

預期整體狀態為 `PASS`。請使用專屬 `--run-id`（可選 `--output-dir`），避免第二次執行撞上既有 run 目錄。

## 查看結果

Native RUN 會把 evidence 寫到 `<output-dir>/<run-id>/`（省略 `--output-dir` 時，Default Package 預設為 `packages/default/output/`）：

```text
output/native-qs-single/
  run.yaml
  report/index.html
  report/junit.html
  ci/summary.json
  ci/junit.xml
  executions/<execId>/case.log
  executions/<execId>/case.yaml
```

用 `report/index.html` 看 HTML 總覽，用 `ci/summary.json` 看機器可讀彙總。每個選中的 Case 都有獨立的 `executions/<execId>/case.log`。

| 狀態 | 在本指南中的意思 |
|---|---|
| `PASS` | 選中的 Case 完成且 assertion 成功 |
| `FAIL` | 業務 assertion 失敗 |
| `ERROR` | 執行／整合失敗 |
| `INVALID` | 驗證阻止執行 |
| `SKIPPED` | Dry-run 只做 Resolve，未執行 Flow |

這次執行也會在 HTML、CI 和 JUnit artifact 旁寫入不依賴來源的 `workbooks/native-cases.result.xlsx`。該報告已在這個 branch 以 `att-testcase/v1` 實作。Issue [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) 的報告驗收仍然開啟。[離線付款導覽](guides/native-payments.zh.md)說明多 Flow Suite、每個 Case 的 PASS／FAIL evidence、標準 XLSX，以及選用的來源 Workbook 報告。

## RUN 多個 inline Case

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-inline \
  run native-case quick.start.inline \
  --resolved-case-id basic --resolved-case-id with-tool
```

Qualified ID 分別為 `quick.start.inline.basic` 與 `quick.start.inline.with-tool`。兩者都應在 `ci/summary.json` 顯示 `PASS`（`total=2`、`passed=2`）。

## RUN Excel Data Source Case

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-excel \
  run native-case quick.start.excel \
  --resolved-case-id QS001 --resolved-case-id QS002
```

Excel 檔案 `testdata/quick-start-native.xlsx` 只作為唯讀 Data Source。Preview／RUN 不會修改該檔。預期 run summary 同時包含 `quick.start.excel.QS001` 與 `quick.start.excel.QS002`。

## Studio Preview（當 Server／Studio 可用時）

在本開發候選版的 ATT Server 與 Studio：

1. 於 Explorer 開啟 Default Package。
2. 開啟或建立 `testcase/` 下的 `testcase.yaml`。
3. 使用 **Preview / Resolve** 列出 qualified Case ID 與 typed inputs（唯讀；不會建立 Job）。
4. 走既有 Change Set Draft → Validate → Review → Publish，並在 Server 宣告 `att-execution/v2` 後再 RUN。

詳見[測試撰寫 — Native Case](reference.zh/test-authoring.md)。

## 本基底刻意未涵蓋的能力

| 能力 | 狀態 |
|---|---|
| Implicit Suite（`run native-suite`、tags、excludes、fail-fast、rerun-failed） | 已在這個 branch 實作並整合（`att-execution/v2`）。[#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) 驗收仍然開啟。這不是 4.6.0 release |
| 標準 native XLSX 和選用的來源 Workbook 報告 | 已在這個 branch 實作並整合（`att-testcase/v1` 報告契約）。[#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) 驗收仍然開啟 |
| Native LOAD Case targets | 由 [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418) 延後 |
| n 對 m generator | 由 [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419) 延後 |
| Debug 互通 | 由 [#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420) 延後 |
| 既有 Package 行為的轉換 | 由 [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398) 獨立追蹤 |

## 下一步

| 目標 | 文件 |
|---|---|
| Native Case 欄位與 Excel mapping | [測試撰寫](reference.zh/test-authoring.md) |
| S12 盤點／遷移決策 | [遷移矩陣](maintenance/native-case-default-package-migration-s12.md) |
| Debug Flow 或 Tool | [Debug](reference.zh/execution-modes/debug.md) |
| 在 LOAD 中執行 Flow 或 Tool | [Load](reference.zh/execution-modes/load.md) |
| 多 Flow 離線付款 Suite、報告和 rerun-failed | [Native 付款導覽](guides/native-payments.zh.md) |
| CLI 語法（含 `run native-case` 和 `run native-suite`） | [目前 CLI Reference](reference.zh/cli.md) |
| 維護既有 Package | [舊版文件](legacy/README.zh.md) |
