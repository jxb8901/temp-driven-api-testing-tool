# ADR-0001：以 Flow 為核心的 Test Case 定義與解析模型

- **狀態：** Proposed（提案中，尚未接受為已實作契約）
- **日期：** 2026-11-20
- **適用範圍：** ATT 下一代 Test Authoring；非 4.4.0 已發布功能
- **相關工作：** [Issue #388](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/388)、[Source Management #233](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/233)、[PR #386](https://github.com/jxb8901/temp-driven-api-testing-tool/pull/386)
- **英文主文件：** [ADR-0001](0001-flow-centric-testcase-resolution.md)

> **架構決策：** Test Case Definition 綁定 Flow、Data Source 及 Resolution Rules；Case Resolution 產生 **0..m 個 Resolved Test Cases**；RUN／LOAD 對選定的 Cases 執行。**產生案例與執行案例是不同的生命週期。**

## 背景與問題

現有 ATT 的測試案例撰寫流程是「XLSX Workbook + `att-sidecar/v2.2` + 產生的 XML Snapshot → Testcase → Stage → Template → Action」，其中 Action 也可呼叫 Flow。雖然功能成熟，但作者需要理解太多相互重疊的概念，且 Excel 與 Testcase Source、報告版面耦合。

目前 `att-flow/v3.6` 已具備有序 Actions 與巢狀 Flow；Debug 可利用 `att-debug/v1.2` 的 Input 獨立執行 Flow；LOAD 已能重複執行 Flow、Template、Tool Target。因此下一代模型應**重用 Flow**，而不是另外發明 Steps 執行語言。

本文記錄**未來的架構方向**；不能視為 `att-testcase/v1`、Case Resolver、動態 Generator 或 LOAD Case Target 已經實作。

## 決策

### 精簡領域模型

| 概念 | 職責 | 是否為獨立的持久資源 |
| --- | --- | --- |
| **Action** | 一個操作或巢狀 Flow 呼叫 | Flow 內部 |
| **Flow** | 可重用、有序的 Actions、Assertions、錯誤及清理邏輯 | 是：`flow.yaml` |
| **Test Case Definition** | Flow 引用、Metadata、Data Source、資料映射／生成／過濾與 Case ID 規則 | 是：`testcase.yaml` + 內嵌／引用資料 |
| **Data Source** | Inline YAML、Excel 靜態資料列、ATT Testdata 或未來的生成／轉換資料 | Definition 的一部分；不強制新增資源類型 |
| **Resolved Test Case**（Case Instance） | 已解析、具體、可識別和可選取的案例身份，以及 Flow／Input Binding | **不是**新的檔案或 Resource Type |
| **Test Suite** | 一個或多個 Definition 的 Resolved Cases 的選取或分組 | 預設可隱式形成，必要時才持久化 |
| **Case Execution** | Resolved Case 的一次實際執行及 Result／Evidence | Runtime Record／Artifacts |
| **LOAD Run／Iteration** | 整個負載 Job／選定 Target 的一次執行 | 既有 Scheduler／Iteration 模型 |
| **Debug Input** | 指定 Target 的臨時 Inputs／Vars | 現有 `debug.yaml`；不是自動發布的 Test Case |

不引入獨立的 **Steps／Step Group Resource 或 DSL**。邏輯步驟直接是 Flow 中的 Action 或巢狀 Flow 呼叫。新的 `testcase.yaml` **直接引用一個入口 Flow**，自身不再包含一套 Template／Stage／Actions 定義。Expected Values 作為 Input，由 Flow Assertion 驗證；預設不另建 Test Case Assertion 語法。

### Case Resolution 與 Execution 分離

```text
Flow
  + Test Case Definition (testcase.yaml)
  + Data Source (Inline | Excel | ATT Testdata | Generator)
  + Resolution Rules (Mapping / Expansion / Filter / Case ID)
                   |
                   v
            Case Resolution
                   |
          0..m Resolved Cases
                   |
          Suite / Target Selection
                   |
         RUN 或 LOAD Case Executions
```

`Resolve(Definition, ResolutionContext) → {ResolvedCase_1, ..., ResolvedCase_m}`

同一 Definition 可包含 `n` 筆來源資料／生成規則，但最後產生 `m` 個 Cases，**m 不一定等於 n**：

| Data Source | n | Resolution | m |
| --- | ---: | --- | ---: |
| Inline YAML 一組 Input | 1 | 不展開 | 1 |
| Excel 有 100 行 | 100 | 全部選取 | 100 |
| Excel 有 100 行 | 100 | 過濾剩 smoke | 20 |
| 一條 Boundary Rule | 1 | 生成最大／最小及相鄰值 | 6 |
| ATT Testdata 3 筆資料 | 3 | 每筆展開三個變體 | 9 |
| 任意來源 | n | 全部被過濾 | 0 |

`m = 0` 必須有明確的空選取或錯誤政策，**不得把沒有執行任何案例視為全部 PASS**。Resolution 不可呼叫業務 Tool 或執行 Flow Actions。

### 案例身份與重現性

必須區分：

- **Definition ID：** 例如 `fpp.transfer.boundary`，標識持久的案例定義。
- **Resolved Case ID：** 例如 `min_minus_1`，在 Definition 範圍內唯一。
- **Qualified Case ID：** 例如 `fpp.transfer.boundary.min_minus_1`，按照 Package／Definition Namespace 規則構成跨案例唯一身份。
- **Execution／Iteration ID：** 每次實際執行的唯一身份；同一 Case 可以有無數次執行。

支援透過 **Case ID Expression** 或穩定 Source Key，在 **Resolution 時**用受限、可預測的資料上下文產生 ID。應驗證空 ID、重複、非法及衝突 ID。Excel 的物理行號不宜作為預設長期 Case 身份。

相同 Case ID **不代表**每次執行都使用相同的動態 Input。例如 LOAD 的 Testdata 可依 Iteration 重新選取，但 Resolved Case ID 不變。精確重跑須保存已固定的來源／Flow／Data Revision、Generator 版本和 Seed、Resolved Binding，以及可保留的實際 Input 選取證據；並區分「按歷史固定資料重跑」與「以最新資料重新執行」。

### 多種 Data Source，共用 Resolution 模型

- **Inline YAML：** 一組輸入通常產生一個 Resolved Case。
- **Excel：** 預設一行資料產生一個 Resolved Case；Flow 仍由 Definition 引用，毋須在 Excel 儲存可執行邏輯。
- **ATT Testdata：** 可作為生成 Resolved Cases 的來源，也可在執行時依既有政策動態選取；**兩者必須區分**。
- **Generated／Transformed Data：** 未來支援邊界值、等價類、範圍、過濾及組合，使用同一 Resolution Contract。

V1 不必一次實作所有 Generator，也不必為每種 Data Source 引入新 Resource Type 或通用 Plugin Framework。

單案例示例（**建議語法，尚未實作**）：

```yaml
schemaVersion: att-testcase/v1
id: fpp.transfer.success
name: FPP Transfer Success
flow: payment.transfer.v1
tags: [fpp, regression]
inputs:
  amount: 100
  expectedStatus: SUCCESS
```

Excel Data-driven 示例（**建議語法，欄位及 ID Expression 契約待確定**）：

```yaml
schemaVersion: att-testcase/v1
id: fpp.transfer.regression
flow: payment.transfer.v1
dataSource:
  type: excel
  path: testdata/transfer-cases.xlsx
  sheet: Cases
  caseIdColumn: Case ID
  mapping:
    amount: Amount
    expectedStatus: Expected Status
```

單一 Definition 加上三行 Excel 通常形成三個 Resolved Cases，每個都執行同一 Flow。日後一條邊界值規則亦可產生 `min_minus_1`、`min`、`min_plus_1`、`max_minus_1`、`max`、`max_plus_1` 六個 Cases；具體 Generator 和 Expected Mapping 細節留待後續 Schema 設計。

### Test Suite 是選取集合

單一 Definition 產生的多個 Resolved Cases 可自然形成**隱式 Suite**，不需要強制建立 `suite.yaml`。有跨 Definition／Flow 組織及持久保存選取的需要時，再考慮明確 Suite Descriptor。

Resolved Case 可以被多個 Suites 選取，但不需要複製其 Definition。

### RUN 與 LOAD 的數量模型

**RUN：** 選取 `m` 個 Resolved Cases，通常每個執行一次。

**LOAD：** 選取 `k` 個 Resolved Case Targets，由 Scheduler 控制重複執行。**每次 Iteration 不會產生新的 Resolved Case。**

| 操作 | Resolved Cases 數 | 實際 Executions／Iterations |
| --- | ---: | ---: |
| RUN 選 m 個案例各一次 | m | m |
| LOAD 選 1 Case 執行 n 次 | **1** | **n** |
| LOAD 選 k Cases，各執行 n 次 | **k** | **n × k** |
| LOAD 選 k Cases，各執行次數不同 | **k** | **sum(n_i)** |

對 Weighted Mix、Arrival-rate、Dropped／Cancelled Iterations，實際每個 Target 的 `n_i` 取決於 Scheduler。報告應分開顯示 **Selected Case Count、Started／Completed Iterations、Success／Failure 和 Retained Evidence Samples**，不可把 Sample 當成執行數。

每次 LOAD Iteration 可以選取不同 Testdata（workload／user／iteration Scope），但只會產生新的 Execution ID，**不會改變 Resolved Case 身份**。

如果 Definition Resolution 產生很多 Cases，也**不代表 LOAD 自動執行全部 Cases**；需要由 Scenario／Workload 明確選取目標。

既有 `att-load/v1.9` 直接執行 **Template／Flow／Tool**、Weighted Mix 和 Pacing 的能力必須保留。支持 Resolved Case Target 屬於未來增量擴展。

### Debug 與正式 Test Case 的分別

`Flow + debug.yaml` 通常足夠執行一個測試場景，但 `att-debug/v1.2` 是臨時 Invocation Input，缺乏持久 Case ID、Suite Membership、Source Revision Binding 及正式 RUN 報告身份。

可共用適用的 Input Mapping／Bootstrap Vars 解析。未來如提供 **Save as Test Case** 或 **Debug this Case**，必須明確確認和驗證 Debug-only Fields，不得暗中把 `debug.yaml` 轉換成 Test Case。

### Excel 同時可作為 Data Source 和 Report Layout

Excel 有兩個**邏輯上分離的角色**：

1. **Data Source：** 提供 Case ID、Inputs 和 Expected Values。
2. **Report Layout：** 可選擇複製來源 Workbook 的格式，並把結果寫入**新的結果 Workbook**，不可更改原始 Excel。

沒有 Excel 來源的 Inline／Generated／Testdata Cases，仍應可輸出 HTML、CI／JUnit 和標準 XLSX 報告。報告由標準化 Case Execution Results 產生，不應依賴 Excel 作為唯一來源。

## Resolution 生命週期與安全約束

- **Discovery／Preview：** 檢查來源和 Metadata；預覽或估算 Cases 及 IDs；大型／動態來源須有上限，不執行業務 I/O。
- **Resolve／Prepare：** 資料映射、過濾、展開、Case ID 校驗、Flow Dependencies 驗證；固定所選 Cases、Source／Data Revision、Generator Seed 等。
- **Execute：** 使用已選的 Resolved Case Binding，依政策選取執行時 Testdata，記錄每次執行的 ID、Result 和 Evidence。

View／Validate／Preview 不可改動 Source、產生有副作用的 Legacy Snapshot 或呼叫業務 Tool。Job 接受後的來源必須固定，不能被後續編輯影響。所有 Preview／Logs／Artifacts／Excel Report 須遵守權限、脫敏、容量與保留上限。

將來即使採用決定性 Lazy Resolution，也不等於在每次 LOAD Iteration 重新生成不同 Case ID。

## 比較過的替代方案

| 方案 | 判斷 |
| --- | --- |
| 繼續以 Stage／Template／Excel 為唯一撰寫模型 | 不採作長期主模型：概念重疊、與 Workbook 耦合 |
| 另外引入 Steps／Step Group | 不採用：重複 Flow Actions／控制語義 |
| 把 `debug.yaml` 直接當正式 Test Case | 不自動等同：缺持久案例身份與報告契約 |
| Excel 每行產生一份 `testcase.yaml` | 不作預設：過度重複 |
| 在 Excel Cell 中編寫完整 Flow／Actions | 不採用：引入第二套不直觀 DSL |
| LOAD 每個 Iteration 生成一個 Resolved Case | 不採用：混淆 Case Identity 與 Execution |
| **Flow + Definition + Data Source → Resolution → Cases** | **建議方向** |

## 正面影響、成本與兼容策略

**好處：** 概念較少、Flow 複用、靜態與動態資料共用模型、可選取及精確追蹤案例、改善 Web 編輯及報告架構。

**成本：** 需要新 Definition Schema、Resolver、穩定 ID 規則、擴展／容量限制、來源溯源、跨 RUN／LOAD／Debug 的兼容處理。

現有 **XLSX + Sidecar + XML Snapshot**、多 Stage Case、Template Target、CLI、Job／Schedule、報告必須繼續可用。Stage 的 `required`、`runWhen`、`onFailure`、Scope／Input Overlay、Rollback／Cleanup 和 Nested Flow Scope **不能假設等同普通 Flow Action**。未驗證等價前應保留 Legacy Adapter／Execution Path。

必須重用 4.4.0 的 Package Source／Change Sets／Validate／Review／Publish／Revision／History／Restore 及不可變 Job Source Snapshot，不另建平行發布系統。

**本 ADR 不要求 PR #386／4.4.0 GA 完成 Native Cases、Generator、Template／Stage 合併或 LOAD Case Target。** 屬於後續逐步演進方向。

## 接受 ADR 前仍需處理的問題

1. `att-testcase/v1` 精確 Schema、Inputs／Defaults 的覆蓋優先級與 `EXEC.VARS` 初始化。
2. Case ID Expression 語法、命名空間、改名／移動時的身份穩定性，與 Legacy ID 的對應。
3. `m = 0`、無效資料、Generator Seed／數量上限、動態外部資料與重跑保證。
4. Resolution 何時進行、是否支援決定性的 Lazy／Bounded Enumeration、如何在 Job 接受前固定 Target。
5. 跨 Definition 的持久 Suite Selection 是否需要額外 Descriptor。
6. Resolved Case Targets 與既有 LOAD Workload／Mix 的映射及效率。
7. 舊 Stage 與新 Flow Action 的 Input／Failure／Cleanup 語義相容性。
8. Debug 轉為 Test Case、敏感 Input Evidence、Excel Report Layout 的精確契約。

狀態保持 **Proposed**，待架構評審及首階段實作契約確定後才轉為 **Accepted**。

## 必須驗證的代表案例

- Inline Definition → 1 Resolved Case → 1 次 RUN Execution。
- Excel 3 行 → 3 個可獨立選取的 Cases，個別 Result／Rerun、可選新 Workbook Report。
- 一條 Boundary Rule → 6 個穩定 IDs；資料過濾／展開的 n→m 和 m=0 政策。
- LOAD 選定 1 Case × 100 Iterations → **1 Case ID、100 Execution IDs**；每次 Testdata 不同亦不改 Case 身份。
- LOAD 選定 3 Cases，各執行 100 次 → **3 Cases、300 Executions**；Weighted Mix 報告實際 Target 分布。
- Legacy 五 Stage Case（Prepare、Invoke、Verify、Rollback、Cleanup）及 Job／Schedule／Snapshot／報告相容無回歸。
