# ATT v4.4.1 快速入門

[English Quick Start](quick-start.md) · [Reference Manual](reference.zh.md) · [遷移矩陣](maintenance/native-case-default-package-migration-s12.md)

Native Case 主路徑對應 4.6.0 開發候選（正式 POM 版本在發佈前仍為 4.4.1）。本指南從乾淨的 repository checkout 開始，用最小離線例子跑通一次 **Native Case**。內容先教新的作者模型：

```text
Package → Flow → testcase.yaml（+ inline 或 Excel Data Source）
       → Preview / Resolve → 選擇 Case → Validate → RUN → Results
```

舊式 Excel + Sidecar + XML Snapshot + Stage/Template 仍然支援。若該路徑仍是正確選擇，請看[舊式 Workbook 附錄](#舊式-workbook-快速入門支援中)。

已提交的 Native 例子刻意離線：只使用 `assign`、`log`、`assert`，以及可選的 ATT 本地 sample Tool；不需要資料庫、MQ、API、credential 或網絡。

## 你將會執行甚麼

Default Package 內的主要檔案：

```text
packages/default/templates/flows/quick-start/flow.yaml
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

Actions 由 `templates/flows/quick-start/flow.yaml` 擁有；Test Case 不會再寫一套 Steps 或 assert DSL。

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
./att.sh --local -p default run native-case quick.start.single \
  --resolved-case-id default
```

預期狀態為 `PASS`，並在 run 輸出目錄留下 per-Case evidence。在此 S05 基底上，HTML/CI 類證據沿用現有 Native RUN 路徑。**不依賴來源 Workbook 的標準 native XLSX 報告屬於 S07，本文件不宣稱已交付。**

## RUN 多個 inline Case

```sh
./att.sh --local -p default run native-case quick.start.inline \
  --resolved-case-id basic --resolved-case-id with-tool
```

Qualified ID 分別為 `quick.start.inline.basic` 與 `quick.start.inline.with-tool`。

## RUN Excel Data Source Case

```sh
./att.sh --local -p default run native-case quick.start.excel \
  --resolved-case-id QS001 --resolved-case-id QS002
```

`testdata/quick-start-native.xlsx` 只作為唯讀 Data Source。Preview／RUN 不會修改該檔，也不會產生舊式 XML Snapshot。

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
| Implicit Suite（`run native-suite`、跨 Definition 標籤） | S06 / #396 — 不在 S05 基底 |
| 不依賴來源 Workbook 的 native XLSX 報告 | S07 / #397 — 不在 S05 基底 |
| Native LOAD Case targets | S08 — 延後 |
| 自動 Stage/Template → Flow 轉換 | #398 — 分析軌道；保留舊式 suites |

## 舊式 Workbook 快速入門（支援中）

新工作請優先使用 Native。需要 Stage/Template 語意、既有 Snapshot Case ID，或 workbook 報表版面時，繼續使用：

```text
packages/default/testcase/quick_start.xlsx
packages/default/testcase/quick_start.yaml
packages/default/testcase/quick_start.xml
packages/default/templates/QUICK_START/template.yaml
```

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS002
```

| 舊式 Case ID | 用途 |
|---|---|
| `quickStart.default.QS001` | 離線 assign/log/assert |
| `quickStart.default.QS002` | 同一路徑再加本地 sample Tool |

不要手工修改已提交的 `quick_start.xml`；改 workbook 後用 `snapshot` 重新產生。Stage/Template 細節見[測試撰寫](reference.zh/test-authoring.md)。

## 下一步

| 目標 | 文件 |
|---|---|
| Native Case 欄位與 Excel mapping | [測試撰寫](reference.zh/test-authoring.md) |
| S12 盤點／遷移決策 | [遷移矩陣](maintenance/native-case-default-package-migration-s12.md) |
| Debug Template／Flow | [Debug](reference.zh/execution-modes/debug.md) |
| LOAD（既有直接 targets） | [Load](reference.zh/execution-modes/load.md) |
| CLI 語法（含 `run native-case`） | [Proposed CLI vNext](interfaces/cli/vnext-proposed.md) |
