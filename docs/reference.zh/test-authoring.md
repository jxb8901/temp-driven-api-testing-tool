# Test authoring

## 編寫並執行 Testcase

Testcase 是一個正規化 Workbook row；Run 會為每個選中的 row 建立一次 Case execution。按以下流程編寫、驗證和執行變更。

1. 編輯 Workbook，並在 Sidecar 中映射欄位。
2. 產生 Snapshot 並檢查差異。
3. 使用 ./att.sh validate --package 驗證 package。
4. 使用 [Debug](execution-modes/debug.md) 隔離 Template、Flow 或 Tool，再使用 [Run](execution-modes/run.md) 建立 Case execution。

以下各節定義 authoring contract。公式單元格、多行表頭和 XML 序列化詳情見[進階 Workbook 與 Snapshot 細節](#進階-workbook-與-snapshot-細節)。

## 區分 Testcase 與 Case execution

**Testcase** 是 Sidecar mapping 和 Snapshot normalization 後由作者編寫的 Workbook row。它的完整 Case ID 格式為 `workbookId.groupId.rowCaseId`。**Case execution** 是 Run 對該 Testcase 的一次 runtime 執行，並有自己的 status 和 evidence。兩者共用 identifier，但概念不同。本頁使用 *Testcase* 表示 Workbook 內容，使用 *Case execution* 表示執行工作。

## 編寫契約

本頁說明正常日常工作流中的數據流轉順序。

| 需求 | 使用 |
|---|---|
| Stage execution entry point | Template |
| 可重用 Template logic | Flow |
| 一個有序 operation | Action |
| 外部能力 | Resource |
| Testcase/Stage business input | EXEC.INPUT |
| 跨 Action mutable state | EXEC.VARS |

## Workbook

### Workbook、Sidecar 和 Snapshot 之間的關系

每個 `.xlsx` Workbook 都要求有一個 YAML Sidecar 和一個生成的 XML Snapshot，它們必須具有相同的基名且位於同一目錄：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

Sidecar 將 Excel 結構映射為 ATT 概念。它負責 sheet 映射、表頭、Testcase 數據、有序 Stage 及可選報告列標簽；timeout/retry 不屬於 Workbook 配置。

```yaml
schemaVersion: att-sidecar/v2.2
id: paymentRegression
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, options=執行參數(yaml)
    required: true
    runWhen: normal
    onFailure: stop
```

根 `id` 是必需的，並且必須在整個包中唯一。`excel.sheet` 可以接受一個 sheet 名稱，或以逗號分隔的 `groupId=sheetName` 條目。如果只給出一個 sheet 且沒有 group ID，ATT 會使用 `default`。完整 Case ID 的形式始終是 `workbookId.groupId.rowCaseId`，並且必須在整個包中唯一。

在修改 Excel 後，執行 `./att.sh snapshot --suite testcase/payment_regression.xlsx`。生成的 `payment_regression.xml` 使用模式 `att-testcases/v2.4`，並僅存儲歸一化後的 Sidecar 映射語義。它保留 group、Case、標簽、map/list 和 Stage 順序，使用顯式值類型，並排除樣式和無關 Workbook 內容。請審查並提交生成的 XML；不要手工修改。

普通 `run` 和每一種 `validate` 模式都會保持只讀，如果 XML 缺失、無效、非規範或過期，則會在輸出創建前失敗。`run --update-snapshot` 會顯式允許 ATT 在應用相同驗證與校驗規則前，僅為選中的完整 Workbook 刷新已更改的 Snapshot。它不會寫入部分 Case/標簽 Snapshot，不會在更新期間調用 Tool，拒絕 Snapshot 符號鏈接，並且當與 `--dry-run` 組合使用時仍會執行授權更新。字節內容完全相同的 Snapshot 不會被重寫。

### 映射數據列

`dataColumns` 可以接受：

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

普通列作為字符串進入 Context。`(yaml)` 列則會把顯示的單元格值解析為 YAML 標量、列表或映射。

雙引號可保護逗號、等號和括號：

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

最後的 `(yaml)` 是 ATT 的解析標記。在最後一個例子中，物理 Excel 表頭名是 `請求(yaml)`。

### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空單元格和僅包含空白字符的值都會歸一化為空白。普通空白數據值會變為空字符串。空白 `(yaml)` 單元格則保持為空白，不進行解析。

必需 Stage 選擇器會拒絕空白值。可選 Stage 如果選擇器為空白，則跳過。

### Workbook / Sidecar

| 對象 | 允許屬性 | 必填/約束 |
|---|---|---|
| 根對象 | `schemaVersion`、`id`、`excel`、`stages`、`report`、`x-*` | `schemaVersion`、包內唯一 `id`、`excel`、非空 `stages` 必需 |
| `excel` | `sheet`、`headerRows`、`caseId`、`tags`、`dataColumns` | `sheet`、`caseId`、`tags` 必需；`headerRows >= 1` |
| `stages[]` | `key`、`template`、`dataColumns`、`required`、`runWhen`、`onFailure` | `key`/`template` 必需；`key` 不能含點號 |
| `report` | `columns` | 值為字符串 |

只有 Sidecar 根對象允許 `x-*`；`excel`、stages 和 Sidecar `report` 拒絕擴展和其他未知字段。Sidecar 不能覆蓋 timeout、retry、Tool、Template 根、環境或輸出根。

## Stage

每個 Sidecar Stage 都有一個不含點號的 `key`，以及一個命名物理 Excel 選擇器列的 `template` 字段。選擇器單元格可以包含符號 Template 名、完整相對 Template 路徑，或 YAML 映射：

| 單元格值 | 含義 |
|---|---|
| `PAYMENT_INVOKE` | 符號名稱簡寫 |
| `payment/local/CT001` | 相對 `templates.root` 的完整路徑簡寫 |
| `name: PAYMENT_INVOKE` | 明確的符號名稱映射 |
| `name: PAYMENT_INVOKE` 加其他鍵 | Template 選擇 + Stage 私有行數據 |

ATT 會先將 `name` 作為全局唯一的符號名解析。只有在沒有符號名匹配時，才會嘗試完整相對 Template 路徑。絕對路徑、部分路徑、以及逃逸出 `templates.root` 的路徑都是非法的。

所有選擇器映射鍵（包括 `name`）都會復制到 Stage Context 中。`stages[].dataColumns` 會增加更多 Stage 私有值。選擇器映射與 Stage 數據列之間如果出現重復鍵，則報錯。


Stage 的 `required`、`runWhen` 與 `onFailure` 規則見 [Reliability](reliability-execution-control.md)。

## Template

只有直接包含 template.yaml 的目錄纔是可呼叫 Template。ATT 使用 att-template/v3.6。每個 Template 都需要非空且有序的 actions map，以及 description。

每個 Action 依類型使用不同契約。`&{templates/payment/request.xml}` 這類 file-content expression 會將 exact UTF-8 檔案內容作為 String 回傳，不會建立檔案。Tool/DB/HTTP/MQ/SSH action 發布原生型別化 operation result。Log 將 typed value 格式化為人類可讀內容。Assign 將值發布至 EXEC.VARS；Flow 在巢狀 Action scope 執行。

完整欄位、範例、typed result/evidence model、HTTP/MQ/SSH boundary 與 migration guidance，請參閱[Action 與型別化值](actions.md)。[Expressions and Built-ins](expressions.md) 定義共用 expression language；[Load](execution-modes/load.md) 定義 ID initialization scope。

## Flow

Flow 是可重用的 Template logic，使用 `att-flow/v3.6`，並由 `flow.yaml` 定義。必填欄位為 `schemaVersion`、versioned canonical `id`（例如 `common.payment.v1`）、`name`、`description` 及非空有序 `actions` map。Template 的 Flow Action 以 `use: common.payment.v1` 呼叫它。每次 invocation 建立新的 `EXEC.ACTIONS` scope；回傳後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。[Actions](actions.md) 定義 Flow result 與 Assign behavior；[Context](runtime-context.md) 定義 lifetime。

## Authoring lifecycle

更新 Workbook 後產生 Snapshot，檢視並提交差異；編輯 Sidecar、Template、Flow 或 Resource 後執行 `./att.sh validate --package`。用 [Debug](execution-modes/debug.md) 隔離檢查，再以 [Run](execution-modes/run.md) 從 Testcase 建立 Case execution。循序建立第一個 package 請使用 [Quick Start](../quick-start.zh.md)。

## Test data ownership

Workbook/Sidecar/Snapshot 定義 Testcase data；Testcase 與 Stage 的 business input 進入 `EXEC.INPUT`。[Context](runtime-context.md) 定義 scope 與 lifetime；Run 會把每個選中的 Testcase 轉為 Case execution。Environment selection 由 [Configuration](configuration.md) 定義。

## Testdata registry 與 input mapping

可用 `att-testdata/v1.0` descriptor 儲存可重用 records；只在 Case、Stage、Debug `inputs` 或 Load workload `inputs` mapping 中引用。完整 `@{id}` 會保留 record 的原生 map/list/scalar 型別；`@{id.path}` 可讀取巢狀值，也支援數字 list index。內嵌參照（例如 `"ORD-@{accounts.id}"`）會產生文字，因此所選 record 的欄位必須是 scalar。Mapping 中的 `${...}` 只能讀取該 mapping 解析前已初始化的 Context root。允許的 root 依 mapping 階段而異，並會在 execution 開始前驗證（Load 則在 scheduler 啟動前驗證）：

- Run Case/Stage mapping 可讀取 `EXEC.ID`、`EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、`EXEC.OUTPUT_DIR`，以及 `META.SOURCE` 或 `META.TARGET`。
- Debug `inputs` 可讀取相同的 execution root 與 metadata，另加 `META.TEMPLATE`。
- Load workload `inputs` 可讀取 `EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、已初始化的 `EXEC.LOAD` identity fields，以及 `META.SOURCE`、`META.TARGET` 或 `META.TEMPLATE`。`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化。Arrival-rate workload 不提供 `EXEC.LOAD.USER_ID`；若 mapping 要同時支援兩種模型，請使用 optional path `${EXEC.LOAD.USER_ID?}`。

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

Environment profile 的 `testdata` list 宣告共享 registry。Load scenario 可在頂層宣告本地 `testdata` imports；同 ID 會在該次 Load 完整取代 environment descriptor。單一 layer 內的重複 ID 會報錯。一般 Run/Debug 只延遲啟用實際引用的 ID；`validate --package` 會檢查全部配置 descriptors。Template、Flow 與 Tool 定義只能透過 `EXEC.INPUT` 取得已解析資料，不可直接寫 `@{...}` 或 `%{...}`。詳見[Environment 與 Test Data](configuration.md)、[Load](execution-modes/load.md) 及維護者的 [testdata design](../system-design/testdata.zh.md)。

## 進階 Workbook 與 Snapshot 細節

### 安全地正規化 XML 文字

包含 LF 或 XML 特殊字符 `&`、`<`、`>` 的字符串值會使用 CDATA；文字 `]]>` 會被拆分成相鄰 CDATA 段，並在解析時精確重建。LF 前的空格或製表符會使用 `&#32;`/`&#9;` 插入兩個 CDATA 段之間，從而保留值而不觸發 Git 行尾空白警告。請審查並提交該 XML；不要手工修改它。

### 公式、日期、百分比和科學記數法單元格

V2.4 會拒絕在配置的 Case ID、標簽、Case 數據、Stage 選擇器和 Stage 數據列中使用公式單元格。公式定義與緩存/顯示結果可能不一致，因此不能用於生成可信的語義 Snapshot。請在 Excel 中重新計算後將結果粘貼為字面值，或者在專門的 ATT 步驟中進行計算。

與配置 Testcase 列相交且位於 `excel.headerRows` 以下的合並區域也會被拒絕。完全位於配置表頭區域內的合並展示單元格則允許。

對於非公式單元格，ATT 導入顯示文本。其精確表示遵循 Workbook 單元格格式和運行時區域設置：

| Excel 值與格式 | Context 值 |
|---|---|
| `45292` 格式化為 `yyyy-mm-dd` | `2024-01-01` |
| `0.125` 格式化為 `0.0%` | `12.5%` |
| `123000` 格式化為 `0.00E+00` | `1.23E+05` |
| `000123` 以文本形式存儲/格式化 | `000123` |

普通列仍然是字符串。`(yaml)` 列可能將顯示文本轉換為其他 YAML 類型。對於日期、百分比、科學計數、賬號或代碼這類文本，應該使用引號把 YAML 標量包起來，以便保持為字符串。

### 多行表頭

`headerRows: 2` 表示第 1–2 行是表頭，數據從第 3 行開始。ATT 會掃描每個物理列從上到下，使用最後一個非空且已去除首尾空白的表頭單元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不會拼接父子標簽。表頭匹配會移除空格、製表符、換行符、NBSP 以及其他 Unicode 空白字符；匹配其餘部分仍區分大小寫。例如，`案例 編號`、`案例\n編號`、`案例編號` 會被視為同一列。每個有效表頭在歸一化後必須唯一，因此僅因空白差異而不同的兩個物理表頭會被認為是重復表頭錯誤。Testcase 加載和結果 Workbook 寫回使用相同的投影邏輯；結果列如果原本不存在，則會寫入最終表頭行。
