# 舊式 Package 遷移

Native Case model 可與既有 Package 執行並存，逐步遷移各個 Package。

## 使用隔離的 Package

舊有 payment、FPP 和 `V3_FLOW_EXAMPLE` asset 已集中到 `packages/legacy`。Descriptor、Workbook Case ID、Tool 和本地 dependency 仍放在一起。本地 CLI 請明確選取 Package：

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
```

選取 `V3_FLOW_EXAMPLE` 的 LOAD 範例會繼續使用該 ID。搬移 Package 不會轉換檔案，也不會改動 Case ID。

## 選擇轉換邊界

若自動轉換會改變選取內容、Action 順序、Case ID 或報告版面，請勿自動轉換 Stage 或 Template。等價分析由 [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398) 追蹤。Native LOAD Case target 由 [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418) 追蹤。每個 consumer 都有經驗證的替代方案前，請保留現有 runtime 契約。

[遷移矩陣](../maintenance/example-migration-matrix.md)記錄 migration status、runtime support、offline CI coverage 和各檔案的已知 consumer。`legacy` 表示資源所有權和相容支援狀態，不代表資源不能執行。

## 使用 Native Case 開始新工作

新工作可使用 Test Case Definition、Flow 及 inline 或 Excel Data Source。[Native 快速入門](../quick-start.zh.md)可離線執行，不需先轉換既有 Package。
