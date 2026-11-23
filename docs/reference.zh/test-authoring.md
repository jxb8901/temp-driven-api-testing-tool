# Test authoring

## 撰寫 Native Case

新的 Case 使用這條路徑。維護以較早模型編寫的 Package，請使用[舊版文件](../legacy/README.zh.md)。

~~~text
Flow + testcase.yaml + Data Source -> Resolution -> Resolved Case -> Suite selection -> Case Execution
~~~

1. 撰寫或重用一個 Flow（`att-flow/v3.6`）。
2. 撰寫綁定該 Flow 和一個 Data Source 的 Test Case Definition（`att-testcase/v1`）。
3. Resolve 該 Definition。Preview 是唯讀的，不會建立 Job。
4. 選擇 Resolved Cases：單一 Case、明確的 qualified ID、tags，或 implicit Suite。不需要強制的 `suite.yaml`。
5. 驗證 Package，然後 RUN。每個被選中的 Resolved Case 會成為一次 Case Execution，各自擁有 execution ID、狀態和 evidence。

`att-testcase/v1` 是精確的 1:1 來源紀錄契約，由本頁和 schema `att-testcase/v1` 定義：

- 只有 typed `inputs` map、沒有 Data Source 時，會解析成一個 ID 為 `default` 的 Case。
- 每一筆非空白的 inline 或 Excel 紀錄正好解析成一個 Resolved Case。Resolution 不會把一筆紀錄展開成多個 Case。
- `caseIdExpression` 只是一次 `${DATA.key}` 查詢，不是 generator。
- Excel `dataColumns` 會明確選取來源欄，並支援別名、YAML 解析和引號分隔符。
- Definition `id` 屬於 Package。Resolved Case ID 是一個 path-safe 片段。Qualified ID 是 `definitionId.caseId`。
- typed input 投影、報告和 Package 檔案互相隔離。Resolution 和 RUN 不會修改 Definition 或來源 Workbook。

n 對 m generator 不屬於 `att-testcase/v1`，仍然延後。既有 Package 使用的 Data Source 格式請參閱[舊版文件](../legacy/README.zh.md)。

## 區分 Resolved Case 與 Case execution

**Resolved Case** 是 Resolution 產生的一個可選邏輯 Case。**Case execution** 是該 Resolved Case 的一次 runtime 執行。Qualified Case ID 在多次 RUN 之間保持穩定；execution ID、狀態和 evidence 屬於單次執行。

## 編寫契約

| 需求 | Native 契約 |
|---|---|
| 可重用的有序 Actions | Flow |
| 單一有序操作 | Action |
| 外部能力 | Resource |
| 業務輸入 | Definition `inputs` 或 `DATA` projection |
| 跨 Action 的可變狀態 | `EXEC.VARS` |
| Case 選取 | Resolved Case ID、tag 或 implicit Suite |

## Native Test Case Definition

現行契約是 `att-testcase/v1`。目前的 default branch 已包含 Native Case CLI，以及經驗證的 Server draft flow，支援 singleton、inline 和 Excel Definition 的 discovery、resolution 和 RUN。這些程式已整合在這個 branch。Native RUN 已在此實作。4.6.0 release 和 tag 尚未發佈。implicit Suite 驗收、Native 報告驗收，以及剩餘的 Default Package 採納仍然開啟。Native LOAD Case target、n 對 m generator 和 Debug interoperability 維持延後。現有直接 LOAD 和 Debug 路徑仍受支援。

Native Excel adapter 只讀取 Package 內 `.xlsx`，支援明確欄位映射、blank normalization 和安全 YAML cell parsing，並在每個 resolved Case 保留 workbook revision、sheet、row 及 column provenance。壓縮後的 workbook 上限為 32 MiB；POI 載入前會預檢 ZIP archive，最多 256 個 entries、每個解壓 entry 32 MiB、總解壓資料 64 MiB。選取的 worksheet 最多 100,000 個實體 rows（包括空白列）；header scan 最多 1,000,000 個 header cells；Resolution 最多接受 10,000 筆非空白 records。Preview 亦有上限。YAML cells 沿用共用 parser 限制：3,000,000 code points、50 個 collection aliases 及 50 層巢狀；解析後的 value graph 最多 64 層及 100,000 個值。系統拒絕非有限數字和遞迴 aliases。

V1 契約定義兩種資料形態：含完整 typed `inputs` map、Resolved Case ID 固定為 `default` 的 singleton inline Case；或含穩定來源 ID 的多筆 inline／Excel Data Source。Definition ID 在 Package 範圍內唯一、保留大小寫並使用點號分段；Resolved Case ID 是單一 path-safe 段落。Excel Case ID 必須是符合 `[A-Za-z0-9_-]{1,128}` 的 literal text cell；公式、數字／日期／boolean／error cell、blank markers、前後空白、保留裝置名稱和 case-fold collision 都會被拒絕。`caseIdExpression` 只可用單一 `${DATA.key}` lookup 覆蓋 resolved ID。空白、重複、格式錯誤、缺少或衝突的 ID，以及空的 resolution 結果都屬錯誤，不會當作全部 PASS。

Data-driven Case 省略 `inputs` 時，會把 `DATA` 同名映射到 Flow inputs；提供 projection 時，該 projection 會完全取代 identity mapping，只接受 `{from: DATA.key}` 或 `{value: <typed value>}`。Excel `dataColumns` 會明確選取來源欄，支援 alias、YAML parsing 和引號分隔符。省略或空白的 `dataColumns` 不會把任何業務欄選入 `DATA`（Case ID／tags selector 也不會自動暴露）；需要 Excel 輸入時必須明確宣告 `dataColumns`。Excel `path` 必須是可移植的 Package 相對 `.xlsx` 路徑。

每次 Native RUN 都會在 `workbooks/native-cases.result.xlsx` 建立不依賴來源 Workbook 的結果報告，內容包括所選 Case 的結果。Excel-backed Definition 可額外設定 `report: {format: xlsx, template: sourceWorkbook}`，要求產生選用的來源 Workbook 版面 artifact：`workbooks/native-cases.source-workbook.xlsx`。RUN 開始前，Engine 會 pin 及驗證 workbook bytes、formula inventory 和所選實體來源列身份。結果只寫入獨立檔案中的所選列，保留來源公式，且不修改 Package 內原始 Workbook。

選用 artifact 限於單一 Excel Definition 和 worksheet、非空且不超過 1,000 個 Case 的 selection，以及不超過 16 MiB 的輸出。發佈前，ATT 會在每個 worksheet 遮蔽敏感來源欄，包括 header 或 mapping alias 被分類為敏感的欄，並在其他位置遮蔽已登記的 secret value。如果遮蔽可能留下敏感的 cached formula result，或無法安全驗證／發佈來源版面檔案，ATT 會省略此選用 artifact 並記錄 fallback diagnostic；標準結果 Workbook 和 Case 結果仍可用。如果 Workbook 在解析後改變，或已不符合 pinning 的資料列，ATT 會在執行前拒絕，不會改用過時來源資料而靜默 fallback。

來源版面報告包含已遮蔽敏感值的 Workbook 其他工作表和資料列。Server 只有在 Job 建立時的原始 principal 目前仍有該 Package 的 editor 權限時才會將可選報告放入 archive；讀取 artifact 清單、每個 entry 和整個 archive 時，也會重新檢查目前的 Package editor 權限。沒有該權限的使用者仍可按一般 artifact 權限讀取不依賴來源的標準結果 Workbook 和其他 Job artifacts。

完整契約和驗證界線見[active Schema](../../schemas/att-testcase-v1.schema.json)、[Singleton inline 範例](../adr/examples/att-testcase-v1/inline-single.yaml)、[多筆 inline 範例](../adr/examples/att-testcase-v1/inline-multi.yaml)、[Excel 範例](../adr/examples/att-testcase-v1/excel.yaml)、[省略 `dataColumns` 的 Excel 範例](../adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml)及[ADR-0001](../adr/0001-flow-centric-testcase-resolution.zh.md)。

Default Package 已附上離線 Native Quick Start Definitions（`packages/default/testcase/quick-start/**/testcase.yaml`），綁定 Flow `quick.start.v1`。請參閱[Default Package Native 快速入門指南](../quick-start.zh.md)與 [S12 遷移矩陣](../maintenance/native-case-default-package-migration-s12.md)。S07 亦提供上述可選的來源 Workbook 版面報告。

### 在 Studio 編寫

在 Explorer 選擇 **New native Test Case**，或開啟索引中的 `testcase.yaml`。引導式編輯器可設定 Package 範圍的 Definition ID、名稱、標籤、canonical Flow、typed inputs，以及單一 Case 或 inline records。進階 YAML 可編輯引導式表單以外的欄位。新檔案必須位於 Package 已配置的 `resources.testcaseRoot` 之下（預設為 `testcase/`）。

**Preview / Resolve** 會把目前編輯器中的 candidate 及 Package-relative `testcase.yaml` 路徑提交至已驗證身份的 Server。Server 會檢查精確 USER/editor 權限和 Package source revisions，再透過有界的 Test Case resolver 及 S04 Excel adapter 解析 candidate。預覽最多回傳 1,000 個 Case，並提供 Package source revision、Definition revision，以及適用時的 workbook revision。Excel row 錯誤會標示 sheet、row 和 column。Typed inputs 依照 Server 設定的 redaction policy 處理。預覽不會建立 Job 或 RunDraft、不會寫入 Package source，也不會修改 Workbook；RUN validation 會再次解析並凍結已選的 Case ID。

使用 **Add to Change Set**，透過現有 Package source 工作流暫存新增或替換：Draft → Validate → Review → Publish。發布前目前 Package source 仍保持啟用。發布後請預覽最新 source，再選擇 Case ID 進行 RUN 驗證和提交。只有在 Server 宣告支援 `att-execution/v2` 時，Native Case RUN 才可用；Server 會為 Job 驗證並凍結 source 和已選的 ID。

Excel-backed Definition 使用與 native resolution 相同的唯讀 S04 adapter，並在 Studio 顯示 row 和 column diagnostics。維護以較早模型編寫的 Package，請使用[舊版文件](../legacy/README.zh.md)。

### Run an implicit Native Case Suite

4.6.0 發佈目標可在已解析的 Case 上建立 Suite selection，毋須作者建立 `suite.yaml`。先指定一個或多個 Definition ID，再選取全部 Case、指定完整 Qualified Case ID，或按一個或多個 tags 選取。被排除的 tags 優先於包含的 tags。

以下本地命令會從兩個 Definition 選取所有 `smoke` Case，但排除帶有 `destructive` tags 的 Case：

```sh
att --local --package payments run native-suite --definition fpp.transfer --definition fpp.refund --tag smoke --exclude-tag destructive --fail-fast
```

若要選取指定 Case，為每個 ID 使用 `--qualified-case-id`，例如 `fpp.transfer.customerA`。每個 ID 必須屬於已指定的 Definition。Server backend 會透過已驗證的 `att-execution/v2` RUN draft flow 使用相同選取器。

ATT 會按 ID 排列 Definition，並保留每個 Definition 的來源記錄順序。重複 Definition ID、重複 Qualified Case ID、不存在的 ID，以及空選取都會在驗證時失敗。Worker 執行前，Server draft 會凍結選取 ID 和 Definition source revision。每個 Case 的結果沿用現有 RUN evidence 和報告流程。

Implicit Suite 是 runtime selection，並非可重用的 authored Definition。這項功能不會新增 LOAD target 或進階 Testdata/generator binding；Debug interoperability 仍屬後續工作。

Studio Run editor 亦提供這項選取功能：輸入一個或多個 Definition ID，再選擇全部 Case、指定 Qualified Case ID，或按 tags 選取 Case。可用 Include Tags 和 Exclude Tags 進一步篩選；重播時會保留保存定義傳回的 tag selector。Native Suite `--rerun-failed` 只會重跑同一 Package 和 Definition 集合中最近一個合資格已完成 RUN 內曾執行但失敗的 Case。Local RUN 會驗證 latest-run pointer 和 manifest hash；Server draft 會凍結來源 Job/run、manifest digest、Package revision 和精確 Qualified Case ID。過期 draft 或來源變更會被拒絕，Skipped Case 不會重跑，每次嘗試的執行／略過數會與來源及 retry 次數分開記錄。Schedule 會保留已驗證的 retry source。同一個 selector 可在 Studio Run editor 和 CLI 使用。它已在目前的 default branch 實作並整合。implicit Suite 驗收仍然開啟，且尚未發佈 4.6.0 release tag。

## Flow

Flow 是一等的可重用有序 Actions，使用 `att-flow/v3.6`，並由 `flow.yaml` 定義。Native Test Case Definition 直接綁定該 Flow。必填欄位為 `schemaVersion`、versioned canonical `id`（例如 `common.payment.v1`）、`name`、`description` 及非空有序 `actions` map。每次 invocation 建立新的 `EXEC.ACTIONS` scope；回傳後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。[Actions](actions.md) 定義 Flow result 與 Assign behavior；[Context](runtime-context.md) 定義 lifetime。

## Authoring lifecycle

修改 Flow 或 Test Case Definition 後執行 `./att.sh validate --package`，再 RUN 所選的 Resolved Case。用 [Debug](execution-modes/debug.md) 隔離單一 Flow 或 Tool，再以 [Run](execution-modes/run.md) 建立 Case execution。循序建立第一個 package 請使用 [Quick Start](../quick-start.zh.md)。

## Test data ownership

Native Test Case Definition 擁有自己的 Data Source 與 input projection。Resolution 讀取該來源，不會修改它。[Context](runtime-context.md) 定義 scope 與 lifetime。RUN 會把每個選中的 Resolved Case 轉為 Case execution。Environment selection 由 [Configuration](configuration.md) 定義。

## Testdata registry 與 input mapping

可用 `att-testdata/v1.0` descriptor 儲存可重用 records；只在 Case、Debug `inputs` 或 Load workload `inputs` mapping 中引用。完整 `@{id}` 會保留 record 的原生 map/list/scalar 型別；`@{id.path}` 可讀取巢狀值，也支援數字 list index。內嵌參照（例如 `"ORD-@{accounts.id}"`）會產生文字，因此所選 record 的欄位必須是 scalar。Mapping 中的 `${...}` 只能讀取該 mapping 解析前已初始化的 Context root。允許的 root 依 mapping 階段而異，並會在 execution 開始前驗證（Load 則在 scheduler 啟動前驗證）：

- RUN mapping 可讀取 `EXEC.ID`、`EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、`EXEC.OUTPUT_DIR`，以及 `META.SOURCE` 或 `META.TARGET`。
- Debug `inputs` 可讀取相同的 execution root 與 metadata，另加 target metadata。
- Load workload `inputs` 可讀取 `EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、已初始化的 `EXEC.LOAD` identity fields，以及 `META.SOURCE`、`META.TARGET` 或 target metadata。`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化。Arrival-rate workload 不提供 `EXEC.LOAD.USER_ID`；若 mapping 要同時支援兩種模型，請使用 optional path `${EXEC.LOAD.USER_ID?}`。

所有 mode 都拒絕引用 `EXEC.INPUT`（正在建立的值）、`EXEC.VARS`、`EXEC.ACTIONS`、Action `output` 及 invocation-scoped helper metadata。V1 mapping grammar 會評估 literal、selected-record `@{...}` reference 及 `${...}` Context reference；不會評估 built-in call。`#{...}`、`&{...}` 和 `%{...}` 都不是 input-mapping expression。

~~~yaml
schemaVersion: att-testdata/v1.0
id: accounts
records:
  - {id: "A-100", tier: gold}
  - {id: "A-200", tier: silver}
selection: {strategy: sequential, exhaustion: recycle}
~~~

Generated records 為 virtual 並可按 index 存取；ATT 只物化被選中的 record。Inclusive integer range 上限為 1,000,000 筆，且 `%{seq}` 是唯一支援的 generated-record substitution：

~~~yaml
schemaVersion: att-testdata/v1.0
id: generatedAccounts
records:
  generate:
    seq: {from: 100, to: 999999, format: "%06d"}
  record: {id: "A-%{seq}", amount: 42}
selection: {strategy: roundRobin, exhaustion: stop}
~~~

[Package Manifest](package-manifest.md) 宣告 Package Testdata registry，以及可選的 Environment replacement lists。Load scenario 可在頂層宣告本地 `testdata` imports；同 ID 會在該次 Load 完整取代 environment descriptor。單一 layer 內的重複 ID 會報錯。一般 Run/Debug 只延遲啟用實際引用的 ID；`validate --package` 會檢查全部 Manifest descriptor layers。Flow 與 Tool 定義只能透過 `EXEC.INPUT` 取得已解析資料，不可直接寫 `@{...}` 或 `%{...}`。詳見[Environment 與 Test Data](configuration.md)、[Load](execution-modes/load.md) 及維護者的 [testdata design](../system-design/testdata.zh.md)。
