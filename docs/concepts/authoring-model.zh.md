# Authoring model

ATT 將業務資料與可重用的執行邏輯分開。

## 現行 Case 撰寫路徑

現行 Case 撰寫路徑是：

~~~text
Flow + testcase.yaml + Data Source
    -> Resolution
    -> Resolved Case
    -> Suite selection
    -> Case Execution
~~~

**Flow**（`att-flow/v3.6`）是可重用的有序 Actions。**Test Case Definition**（`att-testcase/v1`，通常是 `testcase.yaml`）綁定一個 Flow 和一個 Data Source。**Resolution** 把該 Definition 解析成 Resolved Cases。**Suite selection** 選擇要執行哪些 Case。不需要強制的 `suite.yaml`。**Case Execution** 是所選 Resolved Case 的一次 RUN，各自擁有 execution ID、狀態和 evidence。

這是新 Case 的主要模型。規範契約是 [Test Authoring Reference](../reference.zh/test-authoring.md)。實作已整合在目前的 default branch。Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) 已關閉，代表該 issue 的 Native RUN 驗收完成。ATT 4.6.0 尚未發佈 GitHub Release 或 tag，因此關閉 #393 並不是產品 release。Implicit Suite 驗收（[#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396)）、Native 報告驗收（[#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)）以及其餘 Default Package 採納（[#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)）仍然開啟。Native LOAD Case target（[#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)）、n 對 m generator（[#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)）和 Debug 互通（[#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)）維持延後。

### V1 是一筆來源紀錄對應一個 Case

`att-testcase/v1` 是精確的 1:1 來源紀錄契約：

- 只提供 typed `inputs` map 的 Definition 會解析成一個 Case，Resolved Case ID 固定為 `default`。
- inline 或 Excel Data Source 的每一筆非空白來源紀錄正好解析成一個 Resolved Case。Resolution 不會展開、合併或額外產生 Case。
- `id` 是 Package 範圍的 Definition ID。Resolved Case ID 是一個 path-safe 片段。Qualified ID 是 `definitionId.caseId`。
- `caseIdExpression` 只能用一次 `${DATA.key}` 查詢取代來源紀錄 ID。它不是 generator，也不能呼叫 Tool 或產生額外 Case。
- typed `inputs` 把來源欄位投影到 Flow。省略投影時使用同名 `DATA` 欄位。明確投影使用 `{from: DATA.key}` 或 `{value: <typed value>}`。
- Excel `dataColumns` 會選取及投影要提供給 `DATA` 的業務欄位。
- 報告與 Package 檔案互相隔離。RUN 會寫入新的 artifact，不會修改 Definition 或 Data Source。

提議中的 n 對 m generator、邊界展開，以及由 Testdata 產生 Case，屬於 [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)，不在 `att-testcase/v1` 之內。

## 資料邊界

Testdata descriptor 屬於 Package，並透過 Package Manifest 或支援的 invocation 選取。共用 Runtime Configuration 擁有可重用的外部 resource binding。請將 secret 放在受保護的 runtime input 或 secret store，不要把 credential 複製到 Definition 或文件範例。

## 規範來源

[Test Authoring Reference](../reference.zh/test-authoring.md) 定義 Native Definition 和 Flow 行為。[Actions Reference](../reference.zh/actions.md) 定義 Action execution。Resource contract 位於 [Resources Reference](../reference.zh/resources/overview.md)。維護以較早模型編寫的既有 Package，請使用[舊版文件](../legacy/README.zh.md)。
