## 02 Test Authoring

### 編寫契約

本章說明正常日常工作流中的數據流轉順序。

| 需求 | 使用 |
|---|---|
| Stage execution entry point | Template |
| 可重用 Template logic | Flow |
| 一個有序 operation | Action |
| 外部能力 | Resource |
| Case/Stage business input | EXEC.INPUT |
| 跨 Action mutable state | EXEC.VARS |

### 2.1 Workbook

#### Workbook、Sidecar 和 Snapshot 之間的關系

每個 `.xlsx` Workbook 都要求有一個 YAML Sidecar 和一個生成的 XML Snapshot，它們必须具有相同的基名且位于同一目錄：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

Sidecar 將 Excel 结構映射為 ATT 概念。它負责 sheet 映射、表頭、Testcase 數據、有序 Stage 及可選報告列標簽；timeout/retry 不屬于 Workbook 配置。

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

根 `id` 是必需的，並且必须在整個包中唯一。`excel.sheet` 可以接受一個 sheet 名称，或以逗號分隔的 `groupId=sheetName` 條目。如果只給出一個 sheet 且没有 group ID，ATT 會使用 `default`。完整 Case ID 的形式始終是 `workbookId.groupId.rowCaseId`，並且必须在整個包中唯一。

在修改 Excel 後，執行 `./att.sh snapshot --suite testcase/payment_regression.xlsx`。生成的 `payment_regression.xml` 使用模式 `att-testcases/v2.4`，並僅存儲歸一化後的 Sidecar 映射語義。它保留 group、Case、標簽、map/list 和 Stage 順序，使用顯式值類型，並排除样式和無關 Workbook 內容。包含 LF 或 XML 特殊字符 `&`、`<`、`>` 的字符串值會使用 CDATA；文字 `]]>` 會被拆分成相邻 CDATA 段，並在解析時精确重建。LF 前的空格或制表符會使用 `&#32;`/`&#9;` 插入两個 CDATA 段之間，從而保留值而不触發 Git 行尾空白警告。請审查並提交該 XML；不要手工修改它。

普通 `run` 和每一種 `validate` 模式都會保持只讀，如果 XML 缺失、無效、非规范或過期，則會在輸出创建前失敗。`run --update-snapshot` 會顯式允許 ATT 在應用相同驗證與校驗规則前，僅為選中的完整 Workbook 刷新已更改的 Snapshot。它不會寫入部分 Case/標簽 Snapshot，不會在更新期間調用 Tool，拒絕 Snapshot 符號链接，並且當與 `--dry-run` 组合使用時仍會執行授權更新。字節內容完全相同的 Snapshot 不會被重寫。

#### 映射數據列

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

最後的 `(yaml)` 是 ATT 的解析標记。在最後一個例子中，物理 Excel 表頭名是 `請求(yaml)`。

#### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空單元格和僅包含空白字符的值都會歸一化為空白。普通空白數據值會變為空字符串。空白 `(yaml)` 單元格則保持為空白，不進行解析。

必需 Stage 選擇器會拒絕空白值。可選 Stage 如果選擇器為空白，則跳過。

#### 公式、日期、百分比和科学记數法單元格

V2.4 會拒絕在配置的 Case ID、標簽、Case 數據、Stage 選擇器和 Stage 數據列中使用公式單元格。公式定義與缓存/顯示结果可能不一致，因此不能用于生成可信的語義 Snapshot。請在 Excel 中重新計算後將结果粘贴為字面值，或者在專门的 ATT 步骤中進行計算。

與配置 Testcase 列相交且位于 `excel.headerRows` 以下的合並區域也會被拒絕。完全位于配置表頭區域內的合並展示單元格則允許。

對于非公式單元格，ATT 導入顯示文本。其精确表示遵循 Workbook 單元格格式和运行時區域设置：

| Excel 值與格式 | Context 值 |
|---|---|
| `45292` 格式化為 `yyyy-mm-dd` | `2024-01-01` |
| `0.125` 格式化為 `0.0%` | `12.5%` |
| `123000` 格式化為 `0.00E+00` | `1.23E+05` |
| `000123` 以文本形式存儲/格式化 | `000123` |

普通列仍然是字符串。`(yaml)` 列可能將顯示文本轉換為其他 YAML 類型。對于日期、百分比、科学計數、账號或代碼這類文本，應該使用引號把 YAML 標量包起來，以便保持為字符串。

#### 多行表頭

`headerRows: 2` 表示第 1–2 行是表頭，數據從第 3 行開始。ATT 會扫描每個物理列從上到下，使用最後一個非空且已去除首尾空白的表頭單元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不會拼接父子標簽。表頭匹配會移除空格、制表符、換行符、NBSP 以及其他 Unicode 空白字符；匹配其余部分仍區分大小寫。例如，`案例 編號`、`案例\n編號`、`案例編號` 會被视為同一列。每個有效表頭在歸一化後必须唯一，因此僅因空白差異而不同的两個物理表頭會被認為是重復表頭錯誤。Testcase 加載和结果 Workbook 寫回使用相同的投影逻辑；结果列如果原本不存在，則會寫入最終表頭行。

#### WorkbookSidecar

| 對象 | 允許屬性 | 必填/約束 |
|---|---|---|
| 根對象 | `schemaVersion`、`id`、`excel`、`stages`、`report`、`x-*` | `schemaVersion`、包內唯一 `id`、`excel`、非空 `stages` 必需 |
| `excel` | `sheet`、`headerRows`、`caseId`、`tags`、`dataColumns` | `sheet`、`caseId`、`tags` 必需；`headerRows >= 1` |
| `stages[]` | `key`、`template`、`dataColumns`、`required`、`runWhen`、`onFailure` | `key`/`template` 必需；`key` 不能含点號 |
| `report` | `columns` | 值為字符串 |

只有 Sidecar 根對象允許 `x-*`；`excel`、stages 和 Sidecar `report` 拒絕擴展和其他未知字段。Sidecar 不能覆盖 timeout、retry、Tool、Template 根、环境或輸出根。

### 2.2 Stage

每個 SidecarStage 都有一個不含点號的 `key`，以及一個命名物理 Excel 選擇器列的 `template` 字段。選擇器單元格可以包含符號 Template 名、完整相對 Template 路径，或 YAML 映射：

| 單元格值 | 含義 |
|---|---|
| `PAYMENT_INVOKE` | 符號名称簡寫 |
| `payment/local/CT001` | 相對 `templates.root` 的完整路径簡寫 |
| `name: PAYMENT_INVOKE` | 明确的符號名称映射 |
| `name: PAYMENT_INVOKE` 加其他键 | Template 選擇 + Stage 私有行數據 |

ATT 會先將 `name` 作為全局唯一的符號名解析。只有在没有符號名匹配時，才會尝试完整相對 Template 路径。絕對路径、部分路径、以及逃逸出 `templates.root` 的路径都是非法的。

所有選擇器映射键（包括 `name`）都會復制到 Stage Context 中。`stages[].dataColumns` 會增加更多 Stage 私有值。選擇器映射與 Stage 數據列之間如果出现重復键，則報錯。


Stage 的 `required`、`runWhen` 與 `onFailure` 規則見 [Reliability](08_reliability_execution_control.md)。

### 2.3 Template

只有直接包含 template.yaml 的目錄才是可呼叫 Template。ATT 使用 att-template/v3.3。每個 Template 都需要非空且有序的 actions map，以及 description。

每個 Action 依類型使用不同契約。Render 回傳 DocumentValue，不寫入檔案。Tool/DB/HTTP/MQ action 發布原生型別化 operation result。Log 將 typed value 格式化為人類可讀內容。Assign 將值發布至 EXEC.VARS；Flow 在巢狀 Action scope 執行。

完整欄位、範例、typed result/evidence model、DocumentValue 行為、HTTP/MQ boundary 與 migration guidance，請參閱[Action 與型別化值](14_actions.md)。[Expressions and Built-ins](07_expressions.md) 說明共用 expression engine 與 Load ID initialization scope。

### 2.4 Flow

Flow 是可重用的 Template logic，使用 `att-flow/v3.3`，並由 `flow.yaml` 定義。必填欄位為 `schemaVersion`、versioned canonical `id`（例如 `common.payment.v1`）、`name`、`description` 及非空有序 `actions` map。Template 的 Flow Action 以 `use: common.payment.v1` 呼叫它。每次 invocation 建立新的 `EXEC.ACTIONS` scope；回傳後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。[Actions](14_actions.md) 定義 Flow result 與 Assign behavior；[Context](03_runtime_context.md) 定義 lifetime。

### 2.5 Authoring lifecycle

更新 Workbook 後產生 Snapshot，檢視並提交差異；編輯 Sidecar、Template、Flow 或 Resource 後執行 `./att.sh validate --package`。用 [Debug](04_execution_modes/debug.md) 隔離檢查，再以 [Run](04_execution_modes/run.md) 執行 Testcase。循序建立第一個 package 請使用 [Quick Start](../quick-start.zh.md)。

### Test data ownership

Workbook/Sidecar/Snapshot 定義 Testcase data；Case 與 Stage 的 business input 進入 `EXEC.INPUT`。[Context](03_runtime_context.md) 定義 scope 與 lifetime；environment selection 由 [Configuration](09_configuration.md) 定義。
