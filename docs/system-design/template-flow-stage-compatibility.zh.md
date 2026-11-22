# Template、Flow 與 Stage 相容性（S10 / #398）

- **狀態：** 相容性分析及聚焦 golden fixture；尚未成為已接受的替代契約。
- **範圍：** 現有 Workbook／Sidecar Stage 執行與透過單一 Flow 執行 Native Case 的比較。
- **版本線：** 下一個 ATT development candidate；驗收仍待完成。
- **Runtime 變更：** 本階段沒有。Golden fixture 使用現有公開 API，記錄已觀察到的差異。
- **相關文件：** [Issue #398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398)、[ADR-0001](../adr/0001-flow-centric-testcase-resolution.md)、[English](template-flow-stage-compatibility.md)。

## 驗收摘要

目前證據支持分階段處理相容性，不支持一次過替換整個 Engine。聚焦 fixture 證明兩條執行路徑都能保留 Verify 的失敗結果，同時執行 rollback action 及最後的 cleanup action。fixture 亦記錄必須清楚保留的差異：legacy Stage input overlay 只在該 Stage 期間有效，並在每個 Stage 邊界還原；Native Case Flow inputs 作用於整個 Case；結果樹及事件粒度亦不相同。

Fixture 以 log action 作 rollback 和 cleanup 標記。它沒有證明資料庫交易已回滾、外部服務已補償，或所有 helper teardown 行為都相同。這些情況需要另外建立副作用測試。

## 現有執行路徑

| 路徑 | 選取與執行 |
| --- | --- |
| Legacy Case | XLSX 列 + Sidecar + 產生的 XML snapshot → 有序 Stage selectors → 由資料列選取的 Templates → Template Actions，可呼叫巢狀 Flow。 |
| Native Case | att-testcase/v1 Definition + 已解析的 Case inputs → 一個入口 Flow → 有序 Flow Actions 及巢狀 Flow。目前 RUN adapter 會以一個合成的 run Stage 包裝該 Flow 作為 evidence。 |

Stage 路徑仍是受支援的執行契約。S10 不會移除 Workbook、Sidecar、snapshot、Template 或 Stage 行為。

## 相容性矩陣

| 項目 | Legacy Stage 行為 | Native Flow 行為 | 評估 |
| --- | --- | --- | --- |
| 順序與選取 | Sidecar 定義有序 Stage keys；每個 Workbook 資料列為每個必需 Stage 選取 Template。缺少必需的 Stage data 會令 Case 失敗。 | Definition 綁定一個入口 Flow；Flow Actions 有固定順序。沒有按資料列選取 Stage／Template 清單。 | **來源模型不同。** 保留 Stage adapter；每個遷移情境需逐一比較。 |
| 失敗與繼續 | Stage runWhen 按較早 Stage 的失敗狀態及 stop 狀態決定執行；Stage onFailure 控制後續一般 Stage 是否停止。 | Action runWhen 是 expression；action onFailure 控制 Flow 內是否繼續。 | **原語相似、邊界不同。** Fixture 證明一個「失敗 → rollback → cleanup」對應方式，未證明普遍等價。 |
| Input overlay 與還原 | Case inputs 初始化 EXEC.INPUT。當前 Stage values 會在 Stage 執行期間覆蓋原值；Stage 結束或下一個 Stage 開始時還原舊值。 | Resolved Definition inputs 在 Case 開始時初始化 EXEC.INPUT。Flow scope 只隔離 action evidence，不會建立 Stage input overlay，也不會在 Flow 邊界還原 inputs。 | **已知差距。** Fixture 證明 Stage override 只在 Invoke 可見，Case 最終 input 還原為 BASE；Flow 全程維持 BASE。 |
| Case variables | EXEC.VARS 跨 Stage 保留。 | EXEC.VARS 在同一 Case 的 Actions 及 Flow calls 之間保留。 | **觀察到共同行為。** Golden fixture 覆蓋一次跨階段 assignment/read。 |
| 結果狀態 | Case status 會彙總已執行的 Stage／action 結果。後續 rollback 或 cleanup 成功不會清除較早的 FAIL。 | Flow status 會彙總 Action 結果。後續 Action 成功不會清除較早的 FAIL。 | **Fixture 已觀察共同結果。** 其他 action 類型及 ERROR／INVALID 路徑仍需補案例。 |
| Rollback 與 cleanup | 常見模型是以 onFailure 選取 rollback Stage，以 always 選取 cleanup Stage。資源清理亦由 Case finalization 路徑負責。 | 可用有序 Actions 加上明確 runWhen／onFailure policy 建模；不會自動執行補償或交易回滾。 | **只完成部分對應。** 已覆蓋 marker Action；資料庫、MQ、HTTP、SSH 補償及 teardown 仍需獨立 fixture。 |
| 事件串流 | 發出 Stage start／finish 事件及逐個 action completion event，使用 legacy Case 和 Stage IDs。 | Native RUN 發出 Case/run 事件及最外層 Flow invocation completion；此路徑不會為 Flow 內每個 Action 發出事件。 | **事件契約不同。** 消費者不可假設事件一對一對應。 |
| 結果樹及 logs | Stage evidence 位於 STAGES.<stage>.TEMPLATE.ACTIONS。每個 Case 在 legacy run/case 路徑下寫入 case.yaml 和 case.log。 | 合成的 run Stage 會把 Flow invocation 及巢狀 actions 存於 STAGES.run.TEMPLATE.ACTIONS.run.flow.actions。Native outputs 位於 run 的 executions/<executionId> 目錄。 | **Evidence 結構及路徑不同。** Fixture 檢查必要檔案和代表性節點，不要求 bytes 相同。 |
| 巢狀 Flow scope | Template Action 可呼叫 Flow；Flow evidence 巢狀於該 Stage action 之下。 | 入口 Flow 有自己的 action namespace 和 Flow metadata；巢狀呼叫保留巢狀 evidence。 | **共用 runner 機制，父層路徑不同。** 本 fixture 沒有比較巢狀 call-chain evidence 或 META.FLOW scope。 |
| Identity 及 reference checks | Case identity 來自 Workbook／Sidecar selectors；Template 和所引用的 Flow IDs 由 legacy execution plan 解析。 | Native resolver 解析並保存 Definition、Qualified Case、source revision 及入口 Flow。 | **Identity 契約不同。** 保留 legacy IDs／replay；version、cycle、depth 和 reference-failure 案例須分別比較。 |
| Resource finalization | Case finalization 負責清理 legacy execution 開啟的資源。 | Native RUN 有自己的 Case finalization 路徑，處理所選 Flow execution。 | **尚未證明等價。** 本 log-marker fixture 沒有執行 DB transaction 或外部 resource teardown。 |
| Retry、timeout、巢狀 Flow 錯誤 | 現有 Template Actions 及巢狀 Flow calls 使用共用 runner；Stage progression 在 runner 之外處理。 | Native Flow 亦使用共用 action／Flow runner，並保留 Native source identity 和 provenance。 | **共用部分機制，配對證據未完整。** 保留現有測試，為每個遷移邊界加入 golden case。 |
| LOAD、Testdata/generators、Debug | 現有 Stage／Template 及直接 LOAD／Debug 行為仍是目前契約。 | Native Test Case 作為 LOAD target、Testdata／generator bindings 及 Debug interoperability 是獨立後續工作。 | **延後處理。** S08 LOAD、S09 Testdata/generators、S11 Debug 不屬本階段；#388 維持開啟。 |

## Golden fixture

聚焦測試為 FrameworkEngineTest.legacyStageAndNativeFlowGoldenFixturePinsCompatibilityBoundary。它以以下方式執行相同的邏輯 Case：

1. Legacy prepare 和 invoke Stages，其中 Invoke 有專屬 input overlay。
2. 刻意失敗的 Verify。
3. 失敗後執行 rollback，並無條件執行 cleanup。
4. 使用最接近現有 action-level conditions 的 Native Definition 和單一 Flow。

兩次執行都必須以 FAIL 結束、在 Case 結果保留失敗、記錄 rollback 和 cleanup 標記，並寫出 case.yaml 和 case.log。測試同時固定 Stage-only input overlay／還原、Native case-wide input、不同的 evidence path，以及事件粒度差異。

這個 fixture 是相容性探測，不表示所有 legacy Stage graph 都可在沒有 adapter 的情況下轉換成 Flow。

## 尚待處理的差距

- 定義每列專屬的 Stage／Template selection 如何映射至 Definition 和 Flow，同時保留來源列 identity。
- 決定 Flow 是否需要有版本的明確 scoped input overlay；不可隱式修改 EXEC.INPUT 來模擬 overlay。
- 比較 normal、onSuccess、onFailure、always、stop 和 continue 在多個失敗位置的行為，並涵蓋 ERROR 及 INVALID。
- 加入資料庫及外部操作的真實 compensation／rollback 測試，再驗證資源 finalization 和 cleanup 順序。
- 提議新的 execution-control capability 前，先評估現有 Flow Action assertions（#34）及 evidence collectors（#35）；本階段不會新增兩者。
- 比較兩條入口路徑的巢狀 Flow call-chain evidence、META.FLOW scope 及 ID/version/depth/cycle diagnostics。
- 在公開輸出上比較 retries、timeout／cancellation、巢狀 Flow diagnostics、報告 identity、rerun selection 及 evidence retention。
- 保留 LOAD、Testdata/generator 和 Debug 契約，並在各 follow-up 設計和驗收時覆蓋。

## 安全分階段計劃

1. **S10 基線（本階段）：** 發佈矩陣和配對 golden fixture，保留所有既有執行路徑。
2. **聚焦差距決策：** 每項差異逐一選擇 adapter、有版本的 Flow 增量功能，或明確保留差異。實作前記錄對 inputs、identity、events、artifacts 及 replay 的影響。
3. **小步相容性增量：** 每次只加入一種行為及其配對 regression，並持續保持 legacy 路徑可執行。
4. **延後的互通工作：** S08 LOAD、S09 Testdata/generators 和 S11 Debug 各自規劃公開契約及驗收 fixture。

本分析不支持安排一次過重寫 FrameworkEngine，亦不支持移除 legacy Stage 路徑。
