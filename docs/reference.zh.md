# ATT V3.6.0 使用手冊與參考

Author: Jeffrey + ChatGPT
Version: 3.6.0
Status: 規範性使用者文件；由模組化來源自動生成

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->
## 01 概覽與核心概念

ATT 把測試意圖與整合機制分離。測試數據以 workbook／sidecar／snapshot 版本化；Template 與 Flow 定義可重用行為；Resource 把這些行為連接到外部系統。

### 產品模型

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- render / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

**Testcase** 是一個標準化 workbook row；**Stage** 選擇 Template 並提供 stage-private data；**Template** 是可執行 scenario 邊界；**Flow** 是具有獨立 Action scope 的可重用 Template 邏輯；**Action** 是一個有序工作單元；**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper 或 MQHelper。

### 三種執行模式是同級概念

Run、Debug、Load 把不同輸入適配到同一 execution-neutral Context 和同一批 reusable components：

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | workbook Testcase 與 Stage selector | Template、Flow、Tool、DB/MQ |
| Debug | `att-debug/v1.1` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.3` scenario | 重複執行單一 Template、Flow 或 Tool target |

可重用 Template/Flow 應依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。執行模式與 scheduler identity 只保留在 framework evidence，不會成為 expression data。

### 三種 Resource 是同級概念

Tool、DBHelper、MQHelper 是獨立 Resource 類型。它們的配置與 lifecycle 不同，但 operation data 最終都收斂到同一 Action envelope。公開 expression 應讀取 Action result/evidence，而不是 resource 內部 connection/process state。

```text
Tool ----\
DBHelper --+--> operation result/evidence --> Action output
MQHelper -/
```

### Package 邊界

一般 package 包含 `config/`、`testcase/`、`templates/`、`tools/`、`schemas/` 和生成的 `output/`。ATT 在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

需要逐步建立一個可工作的 package，請使用 `docs/quick-start.md`；本 Reference 其餘內容是規範性查閱文件。

## 02 測試案例編寫

### 編寫契約

本章说明正常日常工作流中的数据流转顺序。

### 2.1 工作簿

#### 工作簿、侧车和快照之间的关系

每个 `.xlsx` 工作簿都要求有一个 YAML 侧车和一个生成的 XML 快照，它们必须具有相同的基名且位于同一目录：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

侧车将 Excel 结构映射为 ATT 概念。它负责 sheet 映射、表头、用例数据、有序阶段及可选报告列标签；timeout/retry 不属于工作簿配置。

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

根 `id` 是必需的，并且必须在整个包中唯一。`excel.sheet` 可以接受一个 sheet 名称，或以逗号分隔的 `groupId=sheetName` 条目。如果只给出一个 sheet 且没有 group ID，ATT 会使用 `default`。完整 Case ID 的形式始终是 `workbookId.groupId.rowCaseId`，并且必须在整个包中唯一。

在修改 Excel 后，执行 `./att.sh snapshot --suite testcase/payment_regression.xlsx`。生成的 `payment_regression.xml` 使用模式 `att-testcases/v2.4`，并仅存储归一化后的侧车映射语义。它保留 group、Case、标签、map/list 和阶段顺序，使用显式值类型，并排除样式和无关工作簿内容。包含 LF 或 XML 特殊字符 `&`、`<`、`>` 的字符串值会使用 CDATA；文字 `]]>` 会被拆分成相邻 CDATA 段，并在解析时精确重建。LF 前的空格或制表符会使用 `&#32;`/`&#9;` 插入两个 CDATA 段之间，从而保留值而不触发 Git 行尾空白警告。请审查并提交该 XML；不要手工修改它。

普通 `run` 和每一种 `validate` 模式都会保持只读，如果 XML 缺失、无效、非规范或过期，则会在输出创建前失败。`run --update-snapshot` 会显式允许 ATT 在应用相同验证与校验规则前，仅为选中的完整工作簿刷新已更改的快照。它不会写入部分 Case/标签快照，不会在更新期间调用工具，拒绝快照符号链接，并且当与 `--dry-run` 组合使用时仍会执行授权更新。字节内容完全相同的快照不会被重写。

#### 映射数据列

`dataColumns` 可以接受：

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

普通列作为字符串进入 Context。`(yaml)` 列则会把显示的单元格值解析为 YAML 标量、列表或映射。

双引号可保护逗号、等号和括号：

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

最后的 `(yaml)` 是 ATT 的解析标记。在最后一个例子中，物理 Excel 表头名是 `請求(yaml)`。

#### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空单元格和仅包含空白字符的值都会归一化为空白。普通空白数据值会变为空字符串。空白 `(yaml)` 单元格则保持为空白，不进行解析。

必需阶段选择器会拒绝空白值。可选阶段如果选择器为空白，则跳过。

#### 公式、日期、百分比和科学记数法单元格

V2.4 会拒绝在配置的 Case ID、标签、Case 数据、阶段选择器和阶段数据列中使用公式单元格。公式定义与缓存/显示结果可能不一致，因此不能用于生成可信的语义快照。请在 Excel 中重新计算后将结果粘贴为字面值，或者在专门的 ATT 步骤中进行计算。

与配置测试用例列相交且位于 `excel.headerRows` 以下的合并区域也会被拒绝。完全位于配置表头区域内的合并展示单元格则允许。

对于非公式单元格，ATT 导入显示文本。其精确表示遵循工作簿单元格格式和运行时区域设置：

| Excel 值与格式 | Context 值 |
|---|---|
| `45292` 格式化为 `yyyy-mm-dd` | `2024-01-01` |
| `0.125` 格式化为 `0.0%` | `12.5%` |
| `123000` 格式化为 `0.00E+00` | `1.23E+05` |
| `000123` 以文本形式存储/格式化 | `000123` |

普通列仍然是字符串。`(yaml)` 列可能将显示文本转换为其他 YAML 类型。对于日期、百分比、科学计数、账号或代码这类文本，应该使用引号把 YAML 标量包起来，以便保持为字符串。

#### 多行表头

`headerRows: 2` 表示第 1–2 行是表头，数据从第 3 行开始。ATT 会扫描每个物理列从上到下，使用最后一个非空且已去除首尾空白的表头单元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不会拼接父子标签。表头匹配会移除空格、制表符、换行符、NBSP 以及其他 Unicode 空白字符；匹配其余部分仍区分大小写。例如，`案例 編號`、`案例\n編號`、`案例編號` 会被视为同一列。每个有效表头在归一化后必须唯一，因此仅因空白差异而不同的两个物理表头会被认为是重复表头错误。测试用例加载和结果工作簿写回使用相同的投影逻辑；结果列如果原本不存在，则会写入最终表头行。

#### 阶段与模板选择

每个侧车阶段都有一个不含点号的 `key`，以及一个命名物理 Excel 选择器列的 `template` 字段。选择器单元格可以包含符号模板名、完整相对模板路径，或 YAML 映射：

| 单元格值 | 含义 |
|---|---|
| `PAYMENT_INVOKE` | 符号名称简写 |
| `payment/local/CT001` | 相对 `templates.root` 的完整路径简写 |
| `name: PAYMENT_INVOKE` | 明确的符号名称映射 |
| `name: PAYMENT_INVOKE` 加其他键 | 模板选择 + 阶段私有行数据 |

ATT 会先将 `name` 作为全局唯一的符号名解析。只有在没有符号名匹配时，才会尝试完整相对模板路径。绝对路径、部分路径、以及逃逸出 `templates.root` 的路径都是非法的。

所有选择器映射键（包括 `name`）都会复制到阶段 Context 中。`stages[].dataColumns` 会增加更多阶段私有值。选择器映射与阶段数据列之间如果出现重复键，则报错。

#### 阶段执行控制

| 设置 | 值/默认值 | 含义 |
|---|---|---|
| `required` | boolean/`false` | 空白选择器是否视作错误 |
| `runWhen` | `normal`/默认、`onSuccess`、`onFailure`、`always` | 阶段何时有资格运行 |
| `onFailure` | `stop`/默认、`continue` | 后续适格工作是否继续 |

`continue` 不会把 FAIL 或 ERROR 改成 PASS，只是允许后续适格工作继续运行。

| 先前结果 | 后续 `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | 运行 | 运行 | 跳过 | 运行 |
| FAIL/ERROR 且 `stop` | 跳过 | 跳过 | 运行 | 运行 |
| FAIL/ERROR 且 `continue` | 运行 | 跳过 | 运行 | 运行 |

使用 `onFailure` 做回滚/诊断，使用 `always` 做清理或最终证据收集。

### 2.2 Template

只有直接包含 template.yaml 的目錄才是可呼叫 Template。ATT 3.6.0 使用 att-template/v3.3。每個 Template 都需要非空且有序的 actions map，以及 description。

每個 Action 依類型使用不同契約。Render 回傳 DocumentValue，不寫入檔案。Tool/DB/HTTP/MQ action 發布原生型別化 operation result。Log 將 typed value 格式化為人類可讀內容。Assign 將值發布至 EXEC.VARS；Flow 在巢狀 Action scope 執行。

完整欄位、範例、typed result/evidence model、DocumentValue 行為、HTTP/MQ boundary 與 migration guidance，請參閱[動作與型別化值](reference.zh/14_actions.md)。[Expressions and Built-ins](reference.zh/07_expressions.md) 說明共用 expression engine 與 Load ID initialization scope。

## 03 Runtime 與 Context 模型

Run、Debug、Load 共用 canonical EXEC/META expression model。EXEC 透過 framework lifecycle 和明確的 input/variable/action publication 更新。META 是 curated、immutable、secret-safe 的描述資訊。

Standalone Debug bootstrap value 會映射到這些 canonical root：`inputs` 寫入 `EXEC.INPUT`，Template/Flow 的 `vars` 會在 target 開始前 seed `EXEC.VARS`。v1.1 schema、typed literal 規則及受保護的 framework roots 請參考 [Standalone Debug](reference.zh/04_execution_modes/debug.md)。

### Identity roots

| 路徑 | 意義與型別 | 可用時機 |
|---|---|---|
| EXEC.ID | 目前 Case、Debug execution 或 Load iteration ID；String。 | execution initialization 後；Load 在 business action 前產生。 |
| EXEC.RUN_ID | 外層 ATT Run ID；String。 | 三種模式均可用，同一 run 的 Cases/iterations 共用。 |
| EXEC.STARTED_AT | 目前 execution 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.RUN_STARTED_AT | 外層 run 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.OUTPUT_DIR | execution workspace 絕對路徑。 | 初始化後可用；Load 中可能先有 planned path，尚未實際建立。 |
| EXEC.INPUT | Case/stage、Debug sidecar 或 Load workload input map。 | 三種模式均可用；Stage input 暫時 overlay Case input。 |
| EXEC.VARS | assign 發布的型別化值。 | 三種模式均可用；指派前為空。 |
| EXEC.ACTIONS | active Template/Flow scope 已完成的 Actions。 | 發布 Action 後可用；Flow 有巢狀 scope。 |
| EXEC.LOAD.MODEL | closed 或 arrivalRate。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.WORKLOAD_ID | 配置的 workload ID。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.USER_ID | 穩定 virtual user ID。 | 僅 closed Load；arrival-rate 缺席。 |
| EXEC.LOAD.ITERATION | 數值 iteration 序號。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.PHASE | WARMUP、RAMP_UP、STEADY 或 RAMP_DOWN。 | 僅 Load；ID 產生前可用。 |

EXEC.LOAD 只公開穩定 identity。Scheduler counter、queue state 與 timing diagnostics 留在 evidence-only DIAG.load，不能作為 expression root。

### META 欄位清單與生命週期

公開 META root 只包含下表列出的 `PROJECT`、`SOURCE`、`TARGET`、`TEMPLATE`、`FLOW`、`TOOL`、`DBHELPER`、`MQHELPER` 和 `HTTPHELPER`。META 只包含描述欄位。元件在目前 mode/scope 尚未 active 時，相應路徑可能不存在。

| 公開路徑 | 意義、type 與範例 | Mode 與可用時機 | Scope 與缺席時機 |
|---|---|---|---|
| META.PROJECT.id | Project 目錄名稱；String，例如 `payment-att`。 | Run、Debug、Load；project 綁定後。 | Execution-wide。 |
| META.PROJECT.root | 正規化 project root 路徑；String，例如 `/srv/att/payment`。 | Run、Debug、Load；project 綁定後。 | Execution-wide。 |
| META.SOURCE.type | Source 類型；String：`testcase`、`debug` 或 `load`。 | Run、Debug、Load。 | Execution-wide。 |
| META.SOURCE.path | 正規化絕對 source path；String，例如 `/srv/att/payment/testcase/payment.xlsx`、`/srv/att/payment/debug.yaml` 或 `/srv/att/payment/load/payment.yaml`。 | Run、Debug、Load，source file 存在時。 | Execution-wide；memory source 可缺席。 |
| META.SOURCE.caseId | Canonical TestCase 或 synthetic Debug Case ID；String，例如 `payment.default.P001`。 | Run、Debug。 | Execution-wide；Load 缺席。 |
| META.SOURCE.workbookId | Workbook identifier；String，例如 `payment`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.groupId | Workbook group identifier；String，例如 `default`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.rowCaseId | Workbook row 的 Case ID；String，例如 `P001`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.sheet | Workbook sheet 名稱；String，例如 `Payment`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.row | Workbook 由 1 起始的 row number；Number，例如 `12`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.workbook | 可選 workbook label；有值時為 String，例如 `payment_regression.xlsx`。 | Run，adapter 有提供時。 | Execution-wide；否則缺席。 |
| META.SOURCE.scenario | 不含副檔名的 Load scenario 名稱；String，例如 `payment-smoke`。 | Load。 | Execution-wide；非 Load 時缺席。 |
| META.TARGET.type | 已解析 target 類型；String：`testcase`、`template`、`flow` 或 `tool`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TARGET.id | 已解析 target identifier；String，例如 `PAYMENT_INVOKE` 或 `payment.flow.v1`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TEMPLATE.id | Active Template 或已解析 Load execution-wrapper ID；String，例如 `PAYMENT_INVOKE`。 | Run/Debug 的 Stage 執行期間；Load 的 target resolution 後及 wrapper 執行期間。 | Component scope；解析前缺席，scope 結束後 restore/remove。Load Flow/Tool target 使用已解析的 synthetic wrapper。 |
| META.TEMPLATE.path | 正規化 Template 或 execution-wrapper 目錄；String，例如 `/srv/att/templates/PAYMENT_INVOKE`。 | 與 META.TEMPLATE.id 相同。 | Component scope；解析前缺席，scope 結束後 restore/remove。 |
| META.FLOW.id | Active Flow ID；String，例如 `payment.request.v1`。 | Run、Debug、Load 的 Flow invocation 期間。 | Invocation scope；進入 push、返回 restore、inactive 時缺席。 |
| META.FLOW.invocationId | Caller Action ID；String，例如 `sendRequest`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.FLOW.depth | 由 1 開始的巢狀 Flow 深度；Number，例如 `1`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.TOOL.id | Active configured Tool 或 built-in 名稱；String，例如 `payment.queryOrder` 或 `upper`。 | Run、Debug、Load invocation 期間。 | Invocation scope；返回後不保留最後一次 Tool。 |
| META.TOOL.type | Invocation 類型；String，例如 `tool` 或 `builtin`。 | 與 META.TOOL.id 相同。 | 外層 invocation 不存在時，返回後缺席。 |
| META.DBHELPER.id | Logical DBHelper ID；String，例如 `orders`。 | Run、Debug、Load 的 DBHelper operation/DB Action 期間。 | Invocation scope；外層 scope 不存在時返回後缺席。 |
| META.DBHELPER.type | Resource 類型；String，`dbhelper`。 | 與 META.DBHELPER.id 相同。 | 外層 scope 不存在時返回後缺席。 |
| META.MQHELPER.id | Logical MQHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 MQHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.MQHELPER.type | Resource 類型；String，`mqhelper`。 | 與 META.MQHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.id | Logical HTTPHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 HTTPHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.type | Resource 類型；String，`httphelper`。 | 與 META.HTTPHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |

META.SSHHELPER 不是公開欄位。SSH connection 與 credential settings 留在 Tool invocation 內部。META.TOOL 可識別 active Tool；SSH endpoint、user、identity file、credentials 不會公開在 META。

ATT 會遞迴過濾 password、secret、token、authorization/cookie、API key、private key 等 secret-bearing keys。Expressions 與 adapters 可讀 META，但不能修改。

### Invocation 與 scope 規則

進入 Template、Flow、Tool、helper invocation 時，公開該 active scope 的 metadata。巢狀呼叫會 push frame，離開時 restore 前一份 metadata。沒有 active invocation 時，其 branch 缺席。請勿依賴「最後一次呼叫」狀態。

EXEC.INPUT 是 canonical input map。Stage 暫時 overlay Case input，完成後還原。EXEC.VARS 可供同一 Case 後續 Stages 共用。EXEC.ACTIONS 屬於 active Template/Flow。Action 執行期間讀 local output，完成後發布到 EXEC.ACTIONS.<id>.output。

### Action output 與 evidence path

| Path | 意義與可用時機 |
|---|---|
| `output.result` | Action active（包括 assertion）期間的 primary typed result。 |
| `output.evidence.collectors.<id>.result` | Active Tool evidence collector 的 typed result。 |
| `output.evidence.collectors.<id>.status` | Collector 的 `PASS`／`ERROR` status。 |
| `EXEC.ACTIONS.<actionId>.output.result` | Action 完成後發布的 primary typed result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | 發布後最後／勝出的 collector result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | 發布後最後／勝出的 collector status。 |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | 指定 retry attempt 的 collector result/status；後續成功後仍保留較早 attempt。 |

String、Number、Boolean、null、Map、List、DocumentValue 等值跨越 Action/Template/Flow boundary 時都保留原型別。

### Execution bootstrap variables

Debug sidecar 與現行 Load workload 可為 Template/Flow 提供 canonical 初始 `EXEC.VARS` tree。初始化順序為：解析及驗證 target/definitions；建立 `EXEC.RUN_ID`、`EXEC.INPUT`、`EXEC.LOAD`、穩定 META 與 timestamps；產生並發布 `EXEC.ID`；設定 `EXEC.OUTPUT_DIR`；評估 vars；最後啟動 Template/Flow。Debug 亦使用相同規則及已初始化 identity/output path。

Values 使用 ATT 一般 expression parser。完整 `${...}` 保留 reference 原生型別（包括 null、number、list、map）；混合文字成為字串；`#{...}` 保留 typed result。Map/list 遞迴處理而 key 維持字面值。Vars dependency 不受宣告順序影響；missing bootstrap var 及直接／間接循環會報錯。每個 Load execution 都評估獨立複本，併發 user/workload 不共用 mutable values。第一次一般 `assign` 可取代初始值。

可用 root 包括初始化完成的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他命名的 `EXEC.VARS`，以及穩定 META project/source/target/template metadata。Actions、action-local `output`、invocation-scoped META 和 external/stateful calls 不可用。只允許安全 pure built-in，並沿用相同 expression syntax/type rules。`--set vars.path=value` 會在 evaluation 前修改原始 definition。

### Load execution ID initialization

Load 使用 att-load/v1.3。設定 execution.execIdFormat 時，ATT 在每個 iteration initialization 使用一般 ${...} / #{...} engine 求值一次；省略時維持預設 run-scoped ID。Bootstrap vars 會在生成 ID 及 output path 發布後評估。

可用值有 EXEC.RUN_ID、timestamps、EXEC.INPUT、EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE、closed-only EXEC.LOAD.USER_ID，以及已建立的 META.PROJECT/SOURCE/TARGET/TEMPLATE。EXEC.ID 和 EXEC.OUTPUT_DIR 尚未可用，因為生成的 ID 決定 workspace。還沒有 Action 執行，所以 EXEC.ACTIONS 與 Flow/Tool/helper invocation META 缺席。

只允許 deterministic、side-effect-free built-ins。External Tool/DB/MQ/HTTP/SSH calls 及 stateful、random、clock、filesystem functions 會被拒絕。seq.next() 不允許也不需要。請使用穩定 identity：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 沒有 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

ID 必須非空、安全且為單一路徑 segment，並在 Load run 內唯一。重複或不安全值會在 target 開始前失敗；ATT 不會附加隱藏 suffix。

### Run、execution 與 evidence 導覽

| Identity | 意義 | Scope | Artifact 用途 |
|---|---|---|---|
| EXEC.RUN_ID | 外層 ATT run。 | Run。 | Run root、summary、report。 |
| EXEC.ID | 目前 Case/Debug/Load execution。 | Execution。 | Workspace 建立時作為 log/evidence key。 |
| EXEC.OUTPUT_DIR | 與 EXEC.ID 關聯的 workspace。 | Execution。 | Run/Debug 實體 workspace 或 Load planned lazy workspace。 |

一般 Run 的功能性 Case 位於 output/<RUN_ID>/executions/<EXEC.ID>/。Load 執行期間，EXEC.OUTPUT_DIR 與 CASE.outputDirectory 固定指向 output/load/<RUN_ID>/executions/<EXEC.ID>/。Iteration 被保留時，artifact 也會複製到 samples/<EXEC.ID>/ 或 failures/<EXEC.ID>/。Metrics-only iteration 有 EXEC.ID；scheduler 清理暫存 workspace 後不保留 per-iteration 目錄。Load report 顯示 retained rows 的 EXEC.ID，有保留 case.log 時提供連結。Debug 使用同一 debug ID 作為 EXEC.RUN_ID 與 EXEC.ID。

DIAG 是 evidence-only。Expression 不可讀取 DIAG、EXEC.MODE 或任意 scheduler counter；業務差異請透過 EXEC.INPUT 傳入。

### Optional lookup 與相容性

${path} 是 strict lookup。${path?} 在允許的 map/list 缺失路徑回傳 null；不會讓錯誤語法或不合法 scope access 變有效。CASE、RUN、ACTIONS alias 只在能一對一對應 canonical data 時保留。新 Template 請使用 EXEC/META。

## 04 執行模式

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ 執行語義。

| 模式 | `EXEC.ID` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | 單一 target invocation | `output/debug/<debugId>/` |
| Load | run 內唯一的 iteration execution ID | 重複 target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` 表示外層 ATT run。模式與 scheduler 資料只保存在 evidence-only `DIAG`，`EXEC.MODE`、`EXEC.LOAD` 和 `DIAG` 均不能供 expression 使用。

三種模式都會先解析 environment、建立 canonical Context、驗證 target/dependency closure，再使用相同 component contract。Mode-specific scheduling、selection、reporting 不會建立另一套 Template 或 expression 語義。

### 4.1 Run 模式

Run 是 workbook-driven Testcase execution。

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT 先載入 effective configuration/environment、驗證 canonical workbook snapshot、驗證 selected dependency closure、保留唯一 Run ID，然後依 Stage 順序執行 selected Case。Stage 將 selector 解析成 Template；Action 依 YAML 順序並受 `runWhen` / `onFailure` 控制。

Run evidence 直接寫到 `output/<RunID>/`。完成後才發布 `run.yaml`、Case directories/logs、結果 workbook、HTML/CI output，並更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。`run --update-snapshot` 是執行前明確授權更新 snapshot 的唯一流程。

Status aggregation 的嚴重度為 ERROR > INVALID > FAIL > PASS > SKIPPED。Process exit code：`0` 表示沒有失敗狀態、`1` 表示測試/assertion failure、`2` 表示 command/configuration/validation 無效、`3` 表示 runtime/infrastructure error。

精確 selector/option 見第 10 章；執行控制見第 8 章；artifact contract 見第 11 章。

### 4.2 Standalone Debug

Debug 可在沒有 workbook Testcase 的情況下執行單一 Template、Flow 或 Tool。

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

不帶 target 執行 `./att.sh debug`，會列出 statically valid、可執行的 Tool、Template 和 Flow，附 copyable command。只會顯示實際存在的 regular non-symlink default sidecar。Discovery 會檢查 selected target dependencies，但不建立 Debug output，也不呼叫 Tool。可用 `--format json` 取得 machine-readable 結果。

Debug input 使用現行 `schemaVersion: att-debug/v1.1`。Top-level 支援 `case`、可選 `stage`、`inputs`、`vars`、`arguments`，以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；Template/Flow 的 `vars` 會以 typed bootstrap tree 評估，並在 target 開始前 seed canonical `EXEC.VARS`。Tool Debug 使用 `arguments`，不支援 `vars`。Framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。歷史 `att-debug/v1.0` 仍封存，必須遷移到 v1.1。

#### Standalone Debug bootstrap data

三種 input contract 有意分開：

| Debug 欄位 | Runtime destination | 用途 |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Flow/Template 直接消費的 business input |
| `vars` | initial `EXEC.VARS` | 可重用 Flow/Template 原本由 caller 準備的值 |
| `arguments` / `tools.<localKey>.arguments` | Tool argument contract | standalone Tool Debug 的明確參數 |

只消費 `EXEC.INPUT` 的 Flow 不需要 `vars`。若 Flow 正常由 parent Flow 先發布 `EXEC.VARS.refNo`，可用 scalar 或 typed structure 直接 debug：

```yaml
schemaVersion: att-debug/v1.1
inputs:
  amount: 100
vars:
  refNo: REF001
  txnSeq: 23
  tags: [SIT, PAYMENT]
  order:
    id: ORD001
    amount: 100
```

```sh
./att.sh debug flow common.payment --input common.payment.debug.yaml
```

`vars` 使用共用 expression engine：完整 `${EXEC.INPUT.amount}` 保留原生型別；混合文字會成為字串；`#{...}` 保留 expression result 型別。Map/list 會遞迴處理，map key 維持字面值。Vars 可按任意順序相依；循環、缺少 var、不可用 root 及 side-effecting call 會在 target 開始前失敗。第一次正常 `assign` 可以取代 bootstrap variable，之後仍遵守一般 duplicate-assignment rules。Final values 會使用既有 canonical `EXEC.VARS`/`CASE.VARS` context 及 result artifacts，並套用既有 redaction policy；不會建立第二個 Debug-only namespace。

Bootstrap value 可使用已初始化 execution identity、`EXEC.INPUT`、有提供時的 `EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata 不可用。Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 會被拒絕；安全純 built-in 使用 ATT 一般 parser。可重複使用 `--set input.path=value`、`--set vars.path=value`，以及僅限 Tool 的 `--set arg.name=value`。值以 safe YAML 解析；可用巢狀 map 及數字 list index。例：`--set 'vars.refNo=${EXEC.INPUT.refNo}'` 會在 evaluation 前修改原始定義。

未指定 `--input` 時，Template/Flow 會在旁邊尋找 `debug.yaml`；grouped Tool 會查找 `config/tools/<group>.debug.yaml`，ungrouped Tool 使用 `config/tools/<localKey>.debug.yaml`。沒有 default sidecar 時請使用 `--input`；明確的 `--input` 會取代 auto-discovery。`--env` 在 target validation 之前使用與 Run/Validate/Load 相同的 environment resolver。

Debug 執行 target-scoped validation：只驗證 selected Template/Flow dependency closure 或 Tool contract，不要求無關 workbook。Template/Flow debug 使用與 Run 相同的 Action/Flow scope rule；Tool debug 使用相同 configured Tool invocation contract。

每次 invocation 隔離於：

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug 不建立或更新普通 `latest-run.yaml`。Exit code：`0` PASS、`1` FAIL、`2` CLI/config/input/validation 無效、`3` runtime error。它在 selected reusable-component 邊界上與正常執行等價，但**不是** workbook Case：除非 debug input/artifact 明確提供，否則沒有 workbook selection、Stage history 或 result-workbook lifecycle。

### Debug 排錯與 MQ payload 路徑

當 debug target 無法解析時，先確認 target kind 及 identifier，再用 `--input <path>` 排除 sidecar discovery 因素。Template/Flow debug 會尋找 `<target directory>/debug.yaml`；grouped Tool debug 會尋找 `config/tools/<group>.debug.yaml`。只會驗證 selected target 的 dependency closure，因此不需要無關 workbook 或 Case 檔案。

MQ 的 `file` argument 在 Debug、Run、Load 使用相同的安全路徑規則：

- 絕對路徑必須解析為 ATT package root 內的 regular file。即使 Load 尚未建立 lazy iteration workspace，也會直接按 package root 驗證。
- 相對路徑會在目前 active Case output directory 下解析；`..` traversal、symlink payload、symlink escape、directory 及非 regular file 會在 MQ connect/open/put/get 前被拒絕。
- 遺失或不安全 payload 會直接指出 payload path。尚未建立 MQ connection，因此應先修正路徑，再檢查 broker credential 或 queue 狀態。

按 output directory 分辨排錯階段：

| 症狀 | 檢查 |
|---|---|
| `Debug input file does not exist` | 在 selected target 旁加入 sidecar，或明確傳入 `--input`。 |
| `Debug input uses a historical schemaVersion` | 將 `att-debug/v1.0` 升級至 `att-debug/v1.1`；只有 Flow/Template 需要 caller-prepared `EXEC.VARS` 時才加入 `vars`。 |
| `target` 或 dependency validation 失敗 | 確認 target type/id，並查看回報的 dependency field；不需要無關 workbook。 |
| MQ 回報 payload 遺失或不安全 | 核對 package 內的絕對路徑或 Case-output 內的相對路徑，移除 traversal 及 symlink。 |
| action 已執行但輸出不符預期 | 查看 `output/debug/<debugId>/` 下的 `case.log`、`result.yaml` 及 action artifacts，並對照 rendered inputs 與 selected environment。 |

Load 專用的 evidence retention（`metrics`、`failures`、`samples`、`all`）不適用於 standalone Debug invocation。Debug 會在自己的 debug directory 保留 invocation result 與 artifacts；同一 target 若由 load run 執行，請參考 Chapter 4 的 Load evidence retention 章節。

### 4.3 Load 模式

ATT 接受 att-load/v1.3 scenario。Scenario 有一個或多個 workload；每個 workload 固定一個 Template、Flow 或 Tool target，並配置自己的 inputs、bootstrap vars 與 pacing。Scheduler 啟動前會驗證 scenario 與所有 target。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

#### Scenario 結構

~~~yaml
schemaVersion: att-load/v1.3
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK, amount: 100}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Target 支援 template、flow 或 tool；Tool target 可有 named arguments，但不能宣告 bootstrap vars。Workload inputs 會成為每個 iteration 的 EXEC.INPUT；Template/Flow 的 workload vars 則在每個開始的 iteration 建立全新的初始 EXEC.VARS tree。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

| Workload 欄位 | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input；不作為 bootstrap variables |
| `vars` | initial `EXEC.VARS` | Template/Flow typed expression tree；每個 execution 獨立評估 |
| `target.arguments` | Tool arguments | 僅供 Tool call；與 `EXEC.INPUT`、`EXEC.VARS` 分開 |

#### 每次執行的 bootstrap vars

Scheduler identity 及唯一 EXEC.ID/EXEC.OUTPUT_DIR 初始化完成後，ATT 會在 Template 或 Flow 開始前評估 workload 的 vars tree。完整 `${...}` reference 保留原生型別，混合文字會轉成字串，`#{...}` 使用一般 typed expression parser，巢狀 map/list 會遞迴評估。Vars 之間的依賴不受宣告順序影響；缺少變數或循環會在 target 開始前失敗。每個 iteration 都有獨立 map/list，因此併發 user/workload 不會共用可變值。第一次一般 `assign` 可取代 bootstrap variable。

Bootstrap expression 可使用已初始化的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定的 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata，以及 Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 不可用。只允許安全的純 built-in。Tool arguments 與 vars 是不同 contract。

#### Workload 模型

Closed workload 使用正整數 load.users。每個穩定 virtual user 重複執行固定 target，並在下一次 iteration 前遵守 execution.thinkTime。thinkTime 可設 duration 或 {min, max} range。

Arrival-rate workload 使用 load.arrivalRate、正整數 load.maxConcurrent 與 overloadPolicy: drop。Scheduler 依絕對 due time 排程。超過 maxConcurrent 的 arrival 記為 generator drop；不排隊，也不算 SUT error。Arrival-rate 沒有持續 USER_ID，也不能配置 thinkTime。

duration 必填。warmup、rampUp、rampDown 預設為零。Warm-up 送出真實 traffic，但不計入 measured threshold aggregates。可選 seed 使 closed-VU think-time randomization 可重複。

#### Load identity 與輸出路徑

每個開始的 iteration 在 Load run 內有唯一 EXEC.ID，並共用 EXEC.RUN_ID。省略 execution.execIdFormat 時 ATT 使用預設 run-scoped ID；有設定時，在 initialization 使用一般 ${...} / #{...} engine 求值一次。Bootstrap vars 在 ID 發布後才評估，因此可讀 EXEC.ID 與 EXEC.OUTPUT_DIR。Closed workload 可讀 EXEC.LOAD.USER_ID；arrival-rate 沒有此欄位。欄位可用時機及 function 限制見[Runtime and Context Model](reference.zh/03_runtime_context.md)。

產生的 ID 必須非空且是安全的 path segment。重複 ID 會在 target 啟動前失敗；ATT 不會靜默附加 suffix。

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── 寫入 EXEC.OUTPUT_DIR 的 action outputs
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

Metrics-only iteration 雖有 EXEC.ID，但除非 operation 寫入 artifact 或 retention decision 要求 materialize evidence，否則不會建立 per-iteration execution directory。iteration 執行期間 EXEC.OUTPUT_DIR 維持 executions/<EXEC.ID> 的 logical planned path。保留的 failure 或 sampled success 會將 evidence 複製到 failures/<EXEC.ID>/ 或 samples/<EXEC.ID>/。Report/evidence summary 顯示 EXEC.ID；有保留 case.log 時提供連結。Helper resource-output formatting 會延遲至 retention；明確要求的 Tool evidence collector 仍會執行，因為它是 author-requested diagnostic operation。

#### Evidence 與 resource output

evidence.mode 支援 metrics、failures、samples、all；預設 failures。sampleRate 與 maxSamples 限制保留 evidence。Dropped arrival 不建立 iteration evidence。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、Render DocumentValue 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

#### Report、metrics 與 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

Root thresholds 套用於 aggregate run；workload thresholds 套用於個別 workload。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

#### CLI 與範例

單一 workload 可用 --users、--arrival-rate、--warmup、--ramp-up、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Template/Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用 `att-load-profile/v1.0`，只含 policy，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence` 和 `seed`。明確 CLI pacing 會覆蓋 profile。`load --debug template|flow|tool <id>` 會將 sidecar 的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 profile 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load-profile/v1.0
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

可複製範例與欄位說明維護於 [examples/load/README.md](../examples/load/README.md)。前一版 v1.2 schema 保留供 migration diagnostic；若要使用 workload vars，請將 schemaVersion 升至 v1.3。歷史 v1.0/v1.1 亦不能作為 active version。詳見[Migrations](reference.zh/appendices/migrations.md)。

## 05 資源與整合

Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper 是同級 integration/resource 類型。SSHHelper 為 command-backed Tool 提供路由；它們最終收斂到 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> Tool SSH routing --------/
```

Resource ID 是 Template/expression 或 Tool group 所引用的 logical contract。Environment profile 可把相同 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，無需修改 Action YAML。

### 5.1 Tool

Tool 是具名的 external 或 framework-native capability。Descriptor 必須二選一使用 command 或 call。

#### Command-backed Tool

Command Tool 使用 stdoutFormat 將外部 stdout 解析為型別化結果：

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat 支援 text、json、yaml、xml。ATT 只解析一次 stdout，再將 typed value 發布於 output.result。此欄位不控制人類可讀日誌或檔案輸出。Exit code、有界 stdout/stderr preview 和 process artifacts 都屬於 evidence。

#### Call-backed Tool

Call-backed Tool 呼叫 built-in 或支援的原生 DB/MQ/HTTP operation，其 native typed return value 發布於 output.result。Call-backed descriptor 不宣告 stdoutFormat。

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation 沒有 result.format/path/overwrite 契約。檔案持久化只由明確定義該 API 的 resource 負責；人類可讀表示屬於 Log 或配置的 evidence output。HTTP/MQ parsing 由 transport boundary 管理。

Action result 規則見[動作與型別化值](reference.zh/14_actions.md)；typed result/evidence 的區分見[Operation Result and Evidence](reference.zh/05_resources/operation_result.md)。

### 5.2 DBHelper

DBHelper 是獨立於 Tool 的一級 JDBC resource。每個 descriptor 使用 `schemaVersion: att-dbhelper/v2.6` 和穩定 logical `id`；global `dbhelpers` 只引用 descriptor file。

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credential 可從 environment variable 解析，但不能發布到 `META`、report 或 diagnostic。JDBC driver jar 由使用者放入 `lib/`；ATT 不內置 database driver。

`type: db` Action 選擇一個 helper ID，並且只能有一個 `query` 或 `update` block。Read operation 亦可透過支援的 `#{db.<id>.query(...)}` / `scalar(...)` expression call 使用。文件契約支援 positional JDBC `?` binding，以及 direct Action 的 named `:name` parameter。

Query 返回 typed row/scalar；update 返回規範的 update result。Operation、SQL/parameter evidence 進入 common Action envelope；secret credential 永遠不是 evidence。Parameter evidence 按 descriptor/Action 的 masking/type policy 處理。

Direct DB Action 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。明確的 Action timeout 會覆蓋 DBHelper `statement.timeoutSeconds` 預設值；未設定時才使用 helper timeout。JDBC statement timeout 以秒向上取整，ATT 仍保留毫秒級 deadline cancellation；每次 retry attempt 都重新取得完整 Action timeout，`retry.intervalMs` 的等待時間不計入該 attempt timeout。

Direct `query` Action 亦可使用標準 retry block：`maxAttempts` 2–10、`intervalMs` 0–3,600,000，`retryOn` 必須是非空且不重複的 `ASSERTION` / `TIMEOUT` 列表。使用 `ASSERTION` 時必須同時定義 Action `assert`。一般 SQL error 為 terminal，不會自動 retry。啟用 retry 後，每次 query attempt 會保留在 `output.attempts[n]`；top-level `output.result` / `output.evidence` 永遠代表 final 或 winning attempt，並以 `winningAttempt` 或 `finalAttempt` 記錄終止 attempt 編號。

```yaml
actions:
  waitForOrder:
    type: db
    db: orders
    timeoutMs: 1500
    query:
      sql: select status from orders where id = :id
      parameters:
        id: "${EXEC.INPUT.orderId}"
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

Direct `update` Action 支援 `timeoutMs`，但明確拒絕 `retry`。發生 timeout 或 database/transport failure 後，ATT 通常無法證明 mutation 是否已送達或 commit；自動重放可能造成重複業務變更。因此，需要 application-specific idempotent retry 時應由作者明確建模，而不是啟用通用 DB Action retry。

DBHelper 擁有 descriptor 定義的 connection/statement limit、query timeout、transaction behavior。Transaction finalization 綁定 Case/iteration lifecycle；commit/rollback/reconnect 是 resource operation，不是 public Context root。Action-level timeout/retry 只擴展共同 Action lifecycle，不改變 DBHelper identity 或 Context model。

### 5.3 MQHelper

MQHelper 是由多個 physical instances 組成的 IBM MQ logical resource。現行 descriptor 使用 att-mqhelper/v1.2。Connection settings、credentials、queue defaults 與 pool limits 屬於 resource，不會公開至 META。

~~~yaml
schemaVersion: att-mqhelper/v1.2
id: payment
name: Payment MQ
description: Payment request/reply queues
defaults:
  connection:
    queueManager: QM1
    host: mq.example.internal
    port: 1414
    channel: APP.SVRCONN
  message:
    requestQueue: PAYMENT.REQUEST
    replyQueue: PAYMENT.REPLY
  requestReply:
    waitMs: 5000
    responseFormat: xml
  pool:
    maxSize: 20
instances:
  - id: primary
evidence:
  payload: none
  output:
    format: text
    maxChars: 10000
~~~

Tool Action 以 primary operation 呼叫 mq.<id>.send、mq.<id>.receive 或 mq.<id>.request。MQ reply bytes 在收到 CCSID metadata 時優先按該編碼解碼，再依 responseFormat（text/json/yaml/xml）解析。Typed value 發布於 output.result。responseFormat 負責 ingress parsing；Log.format 和 evidence.output.format 只控制 presentation。

#### 傳送已表示或抽象值

Render output 是 DocumentValue，可直接傳入 payload：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

ATT 使用配置的 MQ charset/CCSID 編碼完全相同的渲染文字，不會 parse/serialize。DocumentValue 不應提供 requestFormat。Document format 不會設定 MQMD.Format；MQ transport metadata 仍由 resource 管理。

Map/List 是抽象結構化值，需指定 requestFormat（text/json/yaml/xml），例如 payload=${EXEC.INPUT.request}, requestFormat=json。DocumentValue + requestFormat 及 String + requestFormat 會被拒絕。payload 與 file 互斥。file 可用於明確的 raw file input；Render 不建立檔案或 targetFiles。

#### Evidence、response parsing 與 Load

MQ evidence 可包含有界 transport metadata，例如 helper/instance identity、operation、安全 queue names、message IDs、CCSID、byte counts、response format、duration 與 failure classification。Payload capture 由 evidence.payload 控制；人類可讀 snapshot 由 evidence.output 獨立控制。Load 可用 evidence.resources.output: none 關閉 resource snapshots；否則等 iteration 保留後才格式化。Typed result 與 response parsing 不變。

Call-level responseFormat 可覆蓋 receive/request 的 requestReply.responseFormat；send 不解析 reply。Instance selection 與 pool limits 屬於 descriptor。歷史 v1.0/v1.1 schema 已封存；validation 前請遷移至 v1.2。

共用 DocumentValue 與 typed-result 契約見[動作與型別化值](reference.zh/14_actions.md)。

### 5.5 HTTPHelper

HTTPHelper 是依環境綁定的 HTTP resource。選定的 config profile 將穩定 logical helper ID 綁定至 base URL。Descriptor 使用 att-httphelper/v1.1。

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

以 type: tool Action 的 primary call 呼叫 http.<id>.get/post/request。Response bytes 由此 boundary 解析：使用 call responseFormat、helper default，或 auto 時依 Content-Type 判斷。支援 auto、text、json、yaml、xml。解析後的 native value 發布於 output.result。可選 evidence.output 是有長度上限的人類可讀 snapshot，不會改變該值。

#### Request body 與 DocumentValue

Render Action 回傳包含 format 和權威渲染文字的 DocumentValue，可直接傳入 body：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

HTTP 在 charset encoding boundary 傳送完全相同的 DocumentValue text，不會 parse/serialize。DocumentValue 不可搭配 requestFormat。

Map/List 是抽象結構化值，需明確指定 requestFormat，例如 body=${EXEC.INPUT.request}, requestFormat=json。requestFormat 支援 text、json、yaml、xml，且只用於 Map/List。DocumentValue + requestFormat 及 String + requestFormat 會被拒絕。body 和 file 互斥；file 是 HTTP call 明確支援的 raw file input。Render 不建立結果檔，也沒有 targetFiles。

DocumentValue.format 不會覆蓋由 resource 管理的 HTTP Content-Type。需要特定 media type 時請配置 contentType/header。Request charset/header 與 response parsing 都由 HTTPHelper 管理，與 Action result/Log formatting 分開。

#### Failure 與 evidence

Transport/protocol、response-parse failures 屬 operational error。已收到的 4xx/5xx 是 completed response，可對 statusCode 做 assertion。HTTP evidence 可包含 helper ID、method、安全 URL、response status、content type、byte counts、response format 與 duration。Credentials/payload 不會隱式保存。Load 可用 evidence.resources.output: none 略過可選 resource output formatting，或將其延至 iteration evidence 保留時。

共用 DocumentValue 與 typed-result 契約見[動作與型別化值](reference.zh/14_actions.md)。

### 5.4 SSHHelper：邏輯 SSH 目標

SSHHelper 讓 command-backed Tool 使用穩定的邏輯應用伺服器 ID，而非在 Tool group 中寫入實體主機。`att-sshhelper/v1.0` YAML descriptor 含 `id`、可選 `name`／`description`、可選 `defaults`（`user`、`port`、`identityFile`）、非空有序 `instances`、可選 `selection.strategy` 和 `fanout.maxConcurrency`（預設 4、範圍 1–256）。每個 instance 需有 `id`／`host`，`user` 必須由 instance 或 defaults 提供。Instance 欄位覆蓋 defaults；port 預設 22，必須在 1–65535。Helper 和 instance ID 符合 `[A-Za-z_][A-Za-z0-9_-]*`，忽略大小寫後不可重複。無效 host/user、未知欄位、重複 ID、缺少 user、不安全路徑和無效 strategy 都會在 SSH 執行前失敗。

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

在 `att-config/v2.10` 的全域或 `environments.<NAME>.sshhelpers` 列出 descriptor 路徑。目前 package 使用 config v2.10 與 Tool Group v2.9。選定環境的清單會整組取代全域清單；省略則繼承。Tool group 所綁定的相同邏輯 ID 必須在每個選定 profile 內存在。SIT 可綁定一台，UAT 綁定兩台，Tool／Action 不必修改：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # 可選 group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

Action 仍呼叫 `app.status`。先在本機／CI secret environment 把 `APP_SSH_KEY` 設為可讀私鑰的**路徑**，再分別以 `./att.sh validate --config config/config.yaml --env SIT --package` 及 UAT 驗證。完整 `${ENV:NAME}` identityFile reference 在載入時解析；缺失／空值會報錯而不揭露值。Tool group 的 `ssh` 只能是直接目標（`host`、`user`、可選 `port`／`identityFile`）或邏輯目標（`helper`、可選 `selection`），不可混用。Call-backed Tool 不支援 SSH。現行 Tool Group schema 為 v2.9。Command-backed Tool 使用 `stdoutFormat`（`text|json|yaml|xml`）設定 stdout parsing；call-backed Tool 保留 native result type。舊 config/group schema 僅供 migration reference。SSH routing detail 不會發布為 `META.SSHHELPER`；公開 META 欄位及原因請見[Runtime Context](reference.zh/03_runtime_context.md)。Action／per-call 層沒有 strategy override。

Strategy 優先序：group override，再到 helper 預設。單 instance 不需 strategy（`single`）；多 instance 必須指定。`random` 均勻選一台，`roundRobin` 以 thread-safe 循環計數器選一台，明確的 `all` 在並發上限內對每台各執行一次。**`all` 會在每台主機產生副作用**；只用於整組執行均安全的命令。不會隱式 fan-out、跨主機重試或 failover。若作者設定 Action timeout retry，整個 `all` 呼叫會重做，並非只重試某台。每台依 Action／Tool／全域 timeout 執行；中斷會取消正在執行的 OpenSSH process 或 Java SSH session。兩種 transport 使用同一組標準化 host/user/port/key。優先 OpenSSH；mwiede/jsch fallback 仍嚴格驗證 host key，限制見 SSH 診斷章。

單主機時解析後的 `output.result` 仍是舊有 scalar／object。Evidence 新增 `sshHelper`、`instance`、`host`、`selectionStrategy`、`selectionSource`（`helper` 或 `toolGroup`）、transport、起訖／持續時間、exit code、輸出及錯誤。`all` 時 `output.result` 包含 `sshHelper`、有效 `selectionStrategy`、`selectionSource`，以及依 descriptor 順序以 ID 為 key 的 `instances`；每筆有 `instance`、`host`、`port`、`transport`、`startedAt`、`endedAt`、`durationMs`、`status`，在適用時另有 `exitCode`、`stdout`、`stderr`、`rawOutput`、解析後 `output` 或 `error`。命令正常完成時即使 `exitCode` 非零，仍是 `status: PASS`；exit code 是供 Action assertion 判斷的證據，不屬操作失敗。只有執行、輸出解析、取消或 timeout 錯誤才令 operation 失敗，其他主機證據仍會保留。Assertion 可查 `${output.result.instances.app1.exitCode}`、`${output.result.instances.app1.status}` 或 `${output.result.instances.app1.output}`。Evidence 不記錄認證內容或環境提供的私鑰路徑；私鑰應放在 package 外，命令中亦不要放秘密。

單主機及 `all` 執行都會在 argv、transport stderr（包括串流寫入的 Case log 診斷）及 exception evidence 遮蔽環境提供的私鑰路徑。上述不記錄保證適用於 ATT metadata 和 transport 診斷；解析後的業務 stdout 不變，因此命令不可輸出私鑰路徑。

遷移：若一個實體目標已足夠，直接 SSH 可維持原狀。否則把 host/user/port/key 搬到 helper descriptor，在每個環境綁定，將 group 升到 v2.7，以 `ssh: {helper: application}` 取代實體 `ssh`，逐一驗證環境。Action 不需重寫。Inventory discovery、Action 層指定主機、分散式交易、跨主機 failover 與 orchestration 均不在此 schema 範圍。

### 5.5 Operation Result 與 Evidence

ATT 將 operation 的邏輯結果與執行 evidence 分開：

~~~text
Operation
├── result       # native typed value
└── evidence     # 有界的 execution/transport metadata
~~~

Action 在 output.result 發布最後的 operation value。Action status、assertion detail、diagnostic、attempts 描述執行，不會取代 business result。Command stdout 使用 stdoutFormat 解析；HTTP/MQ response 使用 responseFormat；DB operation 回傳 native typed value。Render 回傳 DocumentValue，詳見[動作與型別化值](reference.zh/14_actions.md)。

Resource evidence 可包含低成本 metadata。Helper 也可選擇配置人類可讀 snapshot：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output 支援 json、yaml、xml、text、sqlplus。這只用於 presentation，不會修改或取代 output.result。含 secrets 的值會過濾或省略。

Load scenario 可將 evidence.resources.output 設為 inherit（預設）或 none。none 略過可選的 resource-output formatting/materialization；inherit 會等 success sample 或 failure 取得 retention slot 後才格式化。Metrics-only iteration 不序列化 resource output，也不建立 evidence workspace。Transport parsing 與 Render representation 不變。

## 06 環境與測試資料

Environment selection 改變 resource binding，不改變 Action logic。

### Environment profiles

`att-config/v2.10` 定義現行 `environment` default 與 `environments` map。`--config` 選擇共用 configuration；`--env` 選擇 profile 並覆蓋 default。名稱比對不區分大小寫。未知 profile 會在 external execution 前失敗。

Profile 是 typed shallow binding，不是 generic recursive YAML inheritance。Profile 可整組替換 `dbhelpers`、`mqhelpers`、`sshhelpers` 或 `httphelpers` list；未提供的 list 會繼承 common root list。

```yaml
schemaVersion: att-config/v2.10
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    sshhelpers: [config/sshhelpers/sit/application.yaml]
    httphelpers: [config/httphelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    sshhelpers: [config/sshhelpers/uat/application.yaml]
    httphelpers: [config/httphelpers/uat/payment.yaml]
```

各環境應提供相同的 stable logical ID（例如 `orders`、`payment`、`application`），Template、Flow、Action 和 Tool-group binding 才能在 SIT/UAT/PREPROD 之間保持不變。SSH endpoint detail 不會公開為 `META.SSHHELPER`；見[SSHHelper 章](reference.zh/05_resources/sshhelper.md)及[Runtime Context 欄位清單](reference.zh/03_runtime_context.md)。

### Topology 與 secrets

Topology 可隨 descriptor/environment 改變。在 descriptor 支援處使用 `${ENV:NAME}` 注入 secret；不可提交，也不可將解析後的值公開在 META、report 或 diagnostic。缺少 required variable 時會指出 field/name，但不列印 secret。

### Cross-mode consistency

Run、Validate、Debug、Load 透過同一 effective configuration 解析所選 environment。`--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 分開的 configuration files

若 package roots、report policy、Tool topology 或其他 config 刻意不同，可繼續使用 `--config config/environments/sit.yaml` 與 `uat.yaml`。若 package contract 相同而只改 resource binding，使用 profiles。

### Test data 擴充位置

Workbook/sidecar/snapshot 仍是 Testcase data contract。Environment-bound business input 放在 `EXEC.INPUT`；environment selection 屬於 configuration，不是 Action expression。

## 07 Expressions 與 Built-ins

### 統一 expression engine

ATT 的 runtime Template、Flow、Action、Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map、List 或 DocumentValue；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。Render 內容、Action description/assert、Log message/value、assign expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[動作與型別化值](reference.zh/14_actions.md)。

### Load execution ID initialization

ATT 不為 configuration 另設一套 non-runtime expression language。Load 的 execution.execIdFormat 使用同一套 ${...} / #{...} engine，並在 iteration initialization 求值一次。可用值受生命週期限制：EXEC.RUN_ID、timestamps、EXEC.INPUT、穩定 EXEC.LOAD identity，以及當時已初始化的 META branches。

EXEC.ID/EXEC.OUTPUT_DIR 尚不可用，因為 ID 會決定 workspace。EXEC.ACTIONS 和 invocation-scoped Flow/Tool/DB/MQ/HTTP metadata 尚不存在。Arrival-rate 沒有 EXEC.LOAD.USER_ID。只允許 deterministic、side-effect-free built-ins；external calls、seq.next()、random/clock/filesystem functions 都會被拒絕。

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 請省略 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

完整 META inventory、lifecycle 表格與 artifact navigation layout 見[Runtime 與 Context 模型](reference.zh/03_runtime_context.md)。ATT 3.6.0 沒有通用 configuration-expression model。

### `config.report.fileNamePattern`

该配置使用统一表达式引擎，但拥有独立的非 Case 作用域。它只支持一个大小写敏感的值引用：

| 占位符 | 值 |
|---|---|
| `${suiteName}` | 源工作簿 basename，去掉结尾的小写 `.xlsx` 后缀；例如 `testcase/payment_regression.xlsx` 变为 `payment_regression` |

配置字符串必须显式引用 `${suiteName}`，无论它用于文本插值还是内建函数参数。ATT 没有定义其他通用 non-runtime/configuration expression roots。call 内的裸 `suiteName` 会被拒绝。合法示例包括：

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

以及：

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(${suiteName})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${suiteName})})}.xlsx"
```

但不支持如 `${RUN_ID}`、`${WORKBOOK_ID}`、`${ENVIRONMENT}`、`${EXEC.INPUT.caseId}` 等运行时值引用。

### Tool 定义中的 `command` 表达式

Tool 的 `command` 也拥有独立的受限上下文，只能引用该工具 `arguments` 映射中声明的键。canonical 文档及新配置应使用 `${input.<argument>}`：

| 形式 | 含义 |
|---|---|
| `${input.requestText}` | canonical 工具本地输入引用 |
| `${TOOL.input.requestText}` | legacy 完整别名；会产生 `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${requestText}` | deprecated shorthand；仅在唯一对应已声明参数时兼容，并产生迁移 warning |

`${TOOL.input.argument}` 与 `${argument}` 只有在名称恰好对应当前 Tool 一个已声明参数时才会接受，并产生 `CONTEXT_TOOL_INPUT_SHORTHAND`；`att validate` 会给出精确的 `${input.argument}` 替换。未声明或有歧义的 shorthand 会报错。command-backed 与 call-backed Tool 使用相同规则。

例如：

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
        name: Request File
        description: Rendered XML request path
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

每个 YAML command list item 在 render 后仍是一个 atomic argv；值中含空格、引号或类似 shell 的字符也不会再次分词。ATT 不会启动本地 shell。

#### 引号、Context value 与 atomic argv

Tool call 内的引号属于 ATT expression grammar，并不是 shell quote。外层 `'...'` 或 `"..."` delimiter 在调用前会移除；另一种引号是普通字符；与 delimiter 相同的引号可用反斜线 escape。Quoted value 内嵌 `${...}` 会做 interpolation；未加引号的 canonical Context path 则直接传递 typed value。

以下 Tool 会把每个输入保持为一个 argv：

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${message}", "${sourceFile}"]
    stdoutFormat: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

当 call 同时包含多层引号时，建议使用 YAML block scalar：

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}
```

Child process 收到的三条 message 分别是 `Customer O'Reilly`、`status="READY"`，以及例如 `O'Reilly said "READY" for payment.payment.TC001`。Context value 自身包含任一种引号时，无需 caller 做 shell escaping，仍只占一个 argv。

如果坚持把 call 写成单行，还需额外处理独立的 YAML escaping 层：

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

第一行是为 YAML double-quoted scalar escape 双引号；第二行是为 YAML single-quoted scalar 把 apostrophe 写成两个。之后 expression engine 才会解析所得的 `#{...}`。

普通 process-backed Tool 不会让 shell 重新解释已解析输入。Context value 内的 `$HOME`、`$(date)`、`a*.xml`、`|`、`>` 与引号都按字面传递。需要 shell-like behavior 时应使用经过审查的 wrapper；随包提供的 `fpp.exehelper` 和 `fpp.loghelper` 只提供上文明确说明的 pathname expansion。

### Tool 定义中的 `call` 表达式

V2.6 call-backed Tool 使用相同的声明参数理念，但保留 typed value，并只允许 pure built-in 与一个主要 DB query/scalar/update。`${input.customerId}` 来自外层 Tool call，不是 Case 全局变量；`CASE`／`ACTIONS` 等 root 在定义中不可见。Inline SQL 与 package-contained SQL file 内容都在此 scope render，测试数据仍应放在 `params` 并使用 JDBC `?`。

### 操作符

支持的断言操作符有：

- `==`
- `!=`
- `>`
- `>=`
- `<`
- `<=`
- `like`
- `in`
- `is null`
- `is not null`
- `not`
- `and`
- `or`

`like` 是大小写不敏感的操作符关键词，但规范写法使用小写。它匹配完整值，并使用 SQL 风格通配符：

- `%` 匹配零个或多个字符
- `_` 匹配恰好一个字符
- 匹配本身是大小写敏感的

### 内建函数

内建函数通过 `#{...}` 调用。Canonical 名称使用 framework-owned `str.*`、`date.*`、`file.*`、`misc.*` 与 `seq.*` package；旧 flat 名称保留为兼容 alias。Tool group 同样以 `group.tool` 组成 package-like 调用名；配置 Tool 不得占用 built-in package root 或任何 canonical／legacy built-in 名称。

| 函数 | 目的 | 示例 |
|---|---|---|
| `seq.next` | 返回 run-scoped `Long`；可选名称及宽度用于独立计数或精确宽度的零填充文字 | `#{seq.next('payment', 10)}` |
| `str.upper/lower/trim` | 大小写与首尾空白处理 | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.ltrim/rtrim` | 去除前导／尾随空白 | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | 返回文本长度 | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | 拼接参数 | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr/indexOf` | 截取子串／返回位置 | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.contains/startsWith/endsWith` | 测试字面包含、前缀、后缀 | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.replace` | 字面替换 | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad/rpad` | 左／右填充 | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | 重复值 | `#{str.repeat(3, '9')}` |
| `date.sysdate/systimestamp` | 返回系统日期／时间戳 | `#{date.sysdate('yyyyMMdd')}` |
| `date.format` | 格式化 ISO 日期 | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | 日期增减 | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `file.exists/directoryExists` | 测试常规文件／目录 | `#{file.exists(${EXEC.INPUT.requestText})}` |
| `file.size/mkdirs` | 返回文件大小／创建目录树 | `#{file.size(${EXEC.INPUT.requestText})}` |
| `file.copy/move/delete` | 复制、移动、删除文件 | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `misc.string/number/boolean` | 类型转换与归一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默认值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 从布尔值选择两个值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 从输入中随机选择 | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | 将稳定 typed DB result 格式化为 SQL*Plus 风格文字 | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `misc.prettyPrint` | 将 Map/List/array/tree 确定性格式化为缩进文字 | `#{misc.prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

#### `seq.next` run-scoped 序列

`seq.next` 支援以下四種位置參數 overload（同一組參數亦可使用 `name`、`width` 具名傳入；不可混用具名與位置參數）：

| 呼叫 | 計數器 | 返回值 |
|---|---|---|
| `#{seq.next()}` | 預設序列 | 遞增的 Java `Long` |
| `#{seq.next('payment')}` | 名為 `payment` 的獨立序列 | 遞增的 Java `Long` |
| `#{seq.next(10)}` | 預設序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |
| `#{seq.next('payment', 10)}` | 名為 `payment` 的序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |

預設序列與每個具名序列互相獨立，且各自從 1 開始。狀態由單次 ATT Run 擁有：Run 內所有 Testcase／suite 共用計數器；Debug 的單次 execution 使用新 service；Load 的所有 workload 與並行 iteration 共用計數器。每個序列計數器均為 thread-safe，在該 Run 內發出唯一且單調遞增的值；並行排程不保證哪個 VU 取得哪個值。新 Run、新 Debug execution 或新 Load run 都會重新從 1 開始。不公開 `EXEC.SEQUENCES` Context node，也沒有 reset/current API。

| 模式 | 範例用法 | Scope 說明 |
|---|---|---|
| Testcase | 在 `assign` Action 中：`expression: "#{seq.next('payment', 10)}"` | 同一 Run 的連續 Cases 共用 `payment` 計數器。 |
| Debug | 在 `assign` Action 中：`expression: "#{seq.next()}"` | 新的單次 Debug execution 從 1 開始。 |
| Load | 在 target Template/Flow 的 `assign` Action 中：`expression: "#{seq.next('load-order', 10)}"` | Iterations 共用計數器；並行呼叫唯一，但不保證每個 VU 的固定分配順序。 |

`width` 必須是 1 至 1000 的整數。序列名稱必須是非空白文字；只有一個位置參數時，數字代表 `width`，字串代表序列名稱。超過兩個參數、混合具名與位置參數、無效參數型別、空白名稱、小數／零／負數／超出範圍的 width 都會報錯；diagnostic 會指出 `seq.next` 及錯誤的參數數量、型別或範圍。若補零後的數值位數超過 `width`，或底層 `Long` 計數器溢位，求值會明確失敗；ATT 不會截斷序列值，也不會默默超出指定寬度。

`misc.dbText` 只接受一个位置参数或具名 `value`。参数必须是直接 DB Action、DB expression 或 DB-backed Tool 返回的稳定 query／update result。它与DB `output.result` 的 text presentation 共用同一个确定性 formatter，并且没有 JDBC、transaction、connection 或 cache side effect。

`misc.prettyPrint`（alias：`prettyPrint`、`format.pretty`）接受一个位置参数或具名 `value`，递归格式化 Map、List、Iterable、array、scalar 与 null。Linked Map 保留插入顺序，其他 Map 按 key 排序；输出使用两个空格缩进，并带有循环和深度保护。它不会修改输入值。

## 08 可靠性與執行控制

本章集中定義 cross-cutting public execution behavior。

### Assertion 與 status

Assertion 在文件規定的 assertion point、primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception/infrastructure problem 是 `ERROR`；authoring/configuration 無效是 `INVALID`；條件未選中是 `SKIPPED`；成功工作是 `PASS`。因此 operation failure 與 assertion failure 是不同概念。

### `runWhen` 與 `onFailure`

`runWhen` 決定 statically known Action/Stage 是否 eligible；`onFailure: stop|continue` 決定 failure 後是否繼續。`continue` 不會把 failed status 改成 PASS。Cleanup/diagnostic 應使用規範的 conditional execution semantics，而不是隱藏 failure。

### Timeout

Timeout 依 backend 支援能力終止或放棄 operation，並記錄 diagnostic/evidence。Timeout 是 operational failure，不是 assertion false。Tool timeout 與 resource-specific DB/MQ limit 分別由其 resource contract 定義。

### Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可以擁有多個 attempt。Retry policy 決定哪些 operation failure 可重試。最後／勝出的 operation 成為 top-level `output.result` / `output.evidence`；每次 attempt 保留在 `output.attempts[n]`。後續成功不會抹掉較早 attempt evidence。

### Evidence collectors

Tool evidence collector 在 primary operation 發布 typed `output.result` 後、該 attempt assertion 前執行。Action active 時可使用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 是 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 和 `.status`。Collector 有獨立 `timeoutMs` 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代或修改 primary operation result。

每個 primary attempt 都會執行 collector。Top-level collector node 代表最後／勝出的 attempt，`output.attempts[n].evidence.collectors.<id>` 則保留各 attempt。`continue` 讓 primary/assertion outcome 在診斷收集失敗時仍可觀察；`stop` 令 collector failure 成為 Action error。若收集的值是 business/test data，而不是 pre-assertion 診斷資料，應使用普通 Tool/Log/Assign Action。

### Transaction/resource lifecycle

DB transaction finalization 與 DB/MQ resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

未來 fixture（#38）與 DB Action-level timeout/retry（#39）應延伸本章既有概念，而不是再建立一套 reliability model。

## 09 配置參考

本章是作者编写配置时的权威阅读参考。下面提到的 [`schemas/`](../schemas) 仍是机器可读契约。模式校验会先于跨字段和文件系统校验执行。

### 配置层与优先级

| 层级 | 来源 | 所管辖内容 |
|---|---|---|
| 全局 | `config/config.yaml` | 输出目录/环境/运行时默认值、模板根、报告、XML 模式、全局工具、组路径、可选全局 SSH |
| DB helper | `dbhelpers` 引用的独立 YAML | 一个 JDBC 实例的连接、statement timeout、交易、result limit 与 evidence policy |
| MQ helper | `mqhelpers` 引用的独立 YAML | v1.2 IBM MQ logical group、instances、transport、response parsing、pool 與 evidence policy |
| SSHHelper | `sshhelpers` 引用的獨立 YAML | 邏輯 SSH ID、實體 instances、defaults、selection 與 fan-out 上限 |
| HTTPHelper | `httphelpers` 引用的獨立 YAML | 邏輯 HTTP ID、base URL、預設值、連線池、認證與 TLS |
| 工具组 | 配置的 YAML 路径 | 组身份、可选 script/SSH、分组工具 |
| 工作簿 | `<workbook>.yaml` | Excel 映射、阶段、工作簿标签 |
| 模板 | `template.yaml` | 模板身份和有序动作 |
| CLI | 命令选项 | 选择、Run ID、输出覆盖、展示、CI 格式 |

Action timeout 覆盖 Tool descriptor timeout，Tool timeout 覆盖全局 timeout。sidecar、stage、Template 不拥有 timeout/retry 默认。CLI 的 `--output-dir` 和 `--run-id` 会在一次命令中覆盖相应默认值。一个层级中合法的字段，若放在别的层级中也会被拒绝。

### ATT 3.6.0 多环境 Profile 选择

`att-config/v2.10` 是現行 profile 契約。Profile 可整組替換已配置的 DBHelper、MQHelper、SSHHelper、HTTPHelper descriptor lists。各綁定方式見 resource chapters。

ATT 3.6.0 使用一份 common `att-config/v2.10` 加上 `environments` map 选择环境；不通过修改 Action 或增加环境专用 Tool ID 来选择环境。SIT、UAT、PREPROD 及 production-like 环境之间，Action 只保留稳定的 logical ID：

```text
Action -> logical helper ID -> selected config -> physical descriptor -> endpoint
```

推荐目录：

```text
config/
├── config.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

common config 保留现有 templates、testcase root、run/execution/report 设置、`toolGroups` 和 global `tools` registry。Profile 層可配置 typed DB/MQ/SSH/HTTP descriptor lists；以下以 DB/MQ 示範：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
environment: SIT                 # default；--env 会覆盖
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/sample.yaml
  - config/tools/fpp.yaml
  - config/tools/orders-db.yaml
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

可把 `config/environments/sit.yaml` 和 `config/environments/uat.yaml` 作为 common registry 的迁移来源，包括 `invokePaymentApi` 以及 `examples/load/closed-smoke.yaml` 使用的 `sample.getAcDate`。实际 package 不要把共用 registry 缩减成 `tools: {}` 或 `toolGroups: []`。

SIT 与 UAT 的 DBHelper 都保持 `id: orders`，只改变 JDBC URL 等 physical connection details；MQHelper 都保持 `id: payment`，只改变 host、queue manager、port 和 channel。包含完整 descriptor、pool 和安全 evidence policy 的可复制例子见 [`examples/environments/README.md`](../examples/environments/README.md)。

两种环境使用完全相同的 Action 定义：

```yaml
actions:
  renderRequest:
    type: render
    payload: payment/request.json
    templateFormat: json

  queryOrder:
    type: db
    db: orders
    query:
      sql: "select * from orders where order_id = ?"
      params:
        - "${EXEC.INPUT.orderId}"

  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        payload=${EXEC.ACTIONS.renderRequest.output.result},
        responseFormat='xml',
        waitMs=5000
      )}
```

根级 `environment` 是 default profile；大小写不敏感的 `--env` 会覆盖它。Profile 中的 `dbhelpers` 或 `mqhelpers` 各自是整组 shallow replacement，省略才会继承 common list；不支持 generic recursive merge，其他 profile 字段都会被拒绝。未知 profile 名称会在 validation 或 external execution 前失败。四种执行模式使用同一个 selector：

```sh
# SIT
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env SIT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT

# UAT
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env UAT
```

CI 对每个目标环境分别执行 `validate --package` 和 `run --all`：

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
```

这个设计使 Testcase、Template、Flow 和 Action 可以从 SIT promotion 到 UAT，不需要编辑；selected config 在 execution 前定义完整 resource registry，因此 validation 也是 deterministic 的。`orders`、`payment` 等 logical ID 表示能力，不表示 physical endpoint；topology 应属于配置层。不要仅为选择 endpoint 而创建 `orders_sit`、`orders_uat` 或在 Action 中加入环境条件。若 testcase/template root、report policy 或 package structure 确实不同，才使用不同 top-level config。

YAML 中可保留非 secret topology：JDBC URL、MQ host/port、queue manager、channel、pool size 和 timeout。DB/MQ username/password 应使用 `${ENV:NAME}`，由本地环境或 CI secret store 提供。DBHelper 对 URL、username、password 及 string-valued connection properties 支持完整 `${ENV:NAME}`；MQHelper 仅对 username/password 支持该解析，host、queue manager、channel 和 numeric port 通常直接写在 selected descriptor 中。resolved secret 不会进入 profile metadata、diagnostics、reports 或 generated docs。

当同一 package 只在基础设施绑定上不同，应使用 profiles；当 testcase/template root、report policy 或 package structure 有意不同，才使用不同 top-level config。从 3.5.0 的完整 config 迁移时，保留所有 descriptor 和 Action，只把 common settings 合并到 `config/config.yaml`，把各环境 descriptor list 放到 `environments.<NAME>`，并将 `--config config/environments/<env>.yaml` 改为 `--config config/config.yaml --env <NAME>`。

### Schema catalog

ATT 3.6.0 使用以下現行 resource/config schema。現行 JSON Schema 位於 schemas/；歷史定義封存於 schemas/history，不代表舊版本仍有 runtime compatibility。

| Artifact | 現行 schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Template | att-template/v3.3 |
| Flow | att-flow/v3.3 |
| Load scenario | att-load/v1.3 |

schemas/catalog.yaml 是 authoritative catalog。Package validation 會檢查 catalog registrations；這不會令封存 schema 成為可執行 contract。Unsupported active schema version 會失敗並提供 migration guidance。

### 全局配置

```yaml
schemaVersion: att-config/v2.10
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns: {}
  junit: {caseLogEmbedThresholdBytes: 10240}
xml: {namespaceMode: ignore}
toolGroups: [config/tools/database.yaml]
dbhelpers: [config/dbhelpers/orders.yaml]
mqhelpers: [config/mqhelpers/orders.yaml]
tools: {}
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

| 路径 | 必填/默认值 | 约束 |
|---|---|---|
| `schemaVersion` | 必填 | 現行為 `att-config/v2.10`；舊版 configuration 不屬於現行契約。上面的範例使用現行 schema。 |
| `outputDirectory` | `output` | 非空包相对输出根 |
| `environment` | `SIT` | 存在 `environments` 时是 default profile 名称；否则只是 exposed metadata |
| `timeoutMs` | `10000` | 整数 1–3600000 毫秒 |
| `caseLog.yamlAnchors` | `false` | 布尔值；false 会完全展开重复的 YAML 结构，true 允许锚点/别名 |
| `templates.root` | `templates` | 非空包相对模板根 |
| `testcase.root` | `testcase` | 非空包相对递归工作簿/侧车发现根 |
| `run.id.default` | `timestamp` | 仅支持 `timestamp` |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | 非空 Java 日期/时间格式 |
| `report.mode` | `append-to-copy` | 仅支持 `append-to-copy` |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | 结果工作簿文件名模式 |
| `report.columns` | `{}` | 支持键：`result`、`durationMs`、`expectedResult`、`actualResult`、`caseLog`、`reportLink`、`runTime`、`execId`；各值为字符串列标签 |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | 整数 0–1048576 UTF-8 字节；0 始终使用链接 |
| `xml.namespaceMode` | `ignore` | `ignore` 或 `preserve` |
| `toolGroups` | `[]` | 唯一安全且包相对的工具组 YAML 路径 |
| `dbhelpers` | `[]` | 唯一、安全、包相对的 `.yaml`／`.yml` 路径；每个文件声明一个实例 |
| `mqhelpers` | `[]` | 唯一、安全、包相对的 `att-mqhelper/v1.2` YAML 路径；normalized duplicate 会被拒绝 |
| `sshhelpers` | `[]` | 唯一、安全、package-relative 的 `att-sshhelper/v1.0` YAML 路徑 |
| `httphelpers` | `[]` | 唯一、安全、package-relative 的 `att-httphelper/v1.1` YAML 路徑 |
| `environments` | absent | 非空 profile 映射；profile 可包含已配置的 resource descriptor lists |
| `ssh` | absent | 内联全局工具的可选 SSH 目标 |
| `tools` | `{}` | 可复用工具契约映射 |

全局 `mqhelpers` 中的每个路径都从 package root 解析，并包含现行 `att-mqhelper/v1.2` object。它定义 logical group、defaults、physical `instances[]`、selection 与 evidence policy；每个 physical instance 会在执行前取得 effective `connection`、`message`、`requestReply`、`pool`。可选 `evidence.output` 只控制 human-readable snapshot，不改变 typed `output.result`。详见[MQHelper resource module](reference.zh/05_resources/mqhelper.md)。

### Dbhelper 配置

| 路径 | 必填/默认值 | 约束 |
|---|---|---|
| `schemaVersion` | 必填 | `att-dbhelper/v2.6` |
| `id` | 必填 | `[A-Za-z_][A-Za-z0-9_-]*`；全包忽略大小写后唯一 |
| `name`、`description` | 必填 | 非空显示文字 |
| `connection.url` | 必填 | 非空 JDBC URL |
| `connection.username/password` | `""` | 字符串；可用完整 `${ENV:NAME}` |
| `connection.driverClass` | `""` | 可选显式 class；默认 JDBC discovery |
| `connection.properties` | `{}` | 字符串键和值；敏感键在错误中净化 |
| `connection.readOnly` | `false` | 布尔值；update Action 在 prepare 前拒绝 |
| `connection.isolation` | `driverDefault` | `driverDefault`／`readUncommitted`／`readCommitted`／`repeatableRead`／`serializable` |
| `statement.timeoutSeconds` | `30` | 每个 statement 使用的整数 1–3600 秒 |
| `transaction.scope` | `case` | `case` 或 `statement` |
| `transaction.onEnd` | `rollback` | `commit` 或 `rollback` |
| `result.maxRows` | `1000` | 整数 1–1000000 |
| `result.maxCellBytes` | `1048576` | 整数 1–1073741824 |
| `result.maxBytes` | `10485760` | 整数 1–1073741824，且不小于 maxCellBytes |
| `evidence.sql` | `full` | `full` 或 `hash` |
| `evidence.parameters` | `values` | `masked`、`types` 或 `values`；使用 values 可能暴露敏感业务数据 |
| `pool` | 默认值 | `maxSize` 默认 20、`minIdle` 默认 0、`connectionTimeout` 默认 2s；`maxSize` 为 1–10000，`minIdle` 不可大于 `maxSize`，timeout 至少 250ms |

validate、docs、snapshot 与 dry-run 都不会打开 DB Connection。dbhelper 文件路径、ID、字段、SQL 文件和 template call 会在执行前校验。

### 工作簿侧车

| 对象 | 允许属性 | 必填/约束 |
|---|---|---|
| 根对象 | `schemaVersion`、`id`、`excel`、`stages`、`report`、`x-*` | `schemaVersion`、包内唯一 `id`、`excel`、非空 `stages` 必需 |
| `excel` | `sheet`、`headerRows`、`caseId`、`tags`、`dataColumns` | `sheet`、`caseId`、`tags` 必需；`headerRows >= 1` |
| `stages[]` | `key`、`template`、`dataColumns`、`required`、`runWhen`、`onFailure` | `key`/`template` 必需；`key` 不能含点号 |
| `report` | `columns` | 值为字符串 |

只有侧车根对象允许 `x-*`；`excel`、stages 和侧车 `report` 拒绝扩展和其他未知字段。侧车不能覆盖 timeout、retry、工具、模板根、环境或输出根。

### 模板与动作

只有直接包含 template.yaml 的目录才是 callable Template，并使用 att-template/v3.3。Template 必须提供 description 与非空、有序的 actions map。ATT 按现行 type-specific contract 验证每个 Action。

| Action | 必填字段 | Typed-result contract |
|---|---|---|
| render | payload | 返回 DocumentValue；没有结果文件或 targetFiles。 |
| tool | call | 发布 Tool/helper 的 native result。Command stdout parsing 使用 stdoutFormat。 |
| db | db 及 query/update 其中一个区块 | 发布 native typed DB result。 |
| assert | assert | 按条件记录 PASS/FAIL。 |
| log | message 或 value | 支持 level/message/value/format；不支持 file 或 fields。 |
| assign | name/expression | 将 typed value 发布至 EXEC.VARS。 |
| flow | use | 在嵌套 Action scope 执行 Flow。 |

共用 Action result.format/path/overwrite 已移除。Render 使用 templateFormat 标记 DocumentValue。HTTP/MQ responseFormat 负责 ingress parsing；requestFormat 只供抽象 Map/List payload。字段、范例、evidence 行为与迁移见[动作与型别化值](reference.zh/14_actions.md)。

### 工具契约

Tool descriptor 必须二选一配置 command 或 call。Command-backed Tool 必须使用 stdoutFormat: text|json|yaml|xml 将 stdout 解析为 output.result。Call-backed Tool 保留 native return type，不使用 stdoutFormat。Tool descriptor 和 Action 没有共用 result representation/persistence 字段。Process output 属 operational evidence；人类可读格式由 Log 或可选 resource evidence output 负责。

Tool group 使用 att-tool-group/v2.9。Command、call、argument 与 evidence 范例见[Tool](reference.zh/05_resources/tools.md)。

### 标识符和路径约束

Run ID 和完整 Case ID 会直接用作目录名，ATT 不会对合法标识做 slug 化或哈希处理。

Run ID 必须非空、最多 128 个 Unicode 码点，不能是 `.` 或 `..`，不得含前导/尾随空白或尾随 `.`，且不能包含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|`、NUL、控制字符。Windows 设备名（如 `CON`、`NUL`、`COM1`、`LPT1`）会按大小写不敏感方式拒绝。

`workbookId`、`groupId`、`rowCaseId` 同样遵循相同字符规则。`workbookId` 与 `groupId` 不能含点号，因为点号用于分隔三个组件；`rowCaseId` 可含点号。模板路径相对 `templates.root`；render glob 匹配必须保持在模板下。明确声明的 resource file input 和 evidence output 路径必须保持在各自配置根目录内；ATT 会规范化并检查包含性。

### Validation JSON 合约

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.6.0",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

每个诊断都包含 `code`、`severity`、`message`、`file`、`field`、`sheet`、`row`、`column`、`template`、`action` 和 `suggestion`。不适用的字段为 `null`。当 package 和 case 验证发现同一个根本错误时，ATT 输出一条诊断，并在适用时附带 `occurrences` 和 `affectedCases`；`summary.errors` 统计唯一诊断，`summary.errorOccurrences` 保留原始出现次数。代码稳定；自动化不能解析人类消息。

ATT 3.3.0 可另外提供 `summary`、`detail`、`source`、`context` 和 `schemaViolations`。`source` 中的 `line`、`column`、`endLine`、`endColumn` 是 YAML 或 payload 文件的物理位置；顶层 `row` 和 `column` 仍表示 Excel 单元格。单行纯文本及可直接对应的引号字符串，表达式语法错误会指向具体字符；折叠、多行或经过转义的 YAML 字符串若无法精确映射，则报告整个 scalar 范围。每项 Schema 错误保留自己的路径、关键字、消息及物理位置。`context` 可包含 Case、Stage、Flow ID 和嵌套调用链。表达式语法详情在安全时会指出所在工具调用参数（例如 `logFiles`）、意外 token 及带 caret 的有限邻近片段；可能含有凭据或敏感值的字段及整行不会显示原文摘要。

运行时 Action 错误的结构化诊断会传入 Case YAML、`run.yaml`、重新生成的报表、CI JSON 和 JUnit 错误详情。嵌套 Flow 错误会指出内部 `flow.yaml` 及 Action，调用链说明 Template 如何到达该位置。Tool 与 DB evidence 在适用时记录尝试次数、超时、解析／采集状态、参数绑定及取消操作；文件保存错误包含配置路径和允许的产物根目录。

### 生成输出模式摘要

| 产物 | 顶层必需契约 |
|---|---|
| `run.yaml` | `schemaVersion`、`att`、`runtime`、`run`、`validation`、`inputs`、`cases`、`summary`、`outputs` |
| Validation JSON | `schemaVersion`、`attVersion`、`valid`、`mode`、`summary`、`diagnostics` |
| CI summary JSON | `schemaVersion`、`attVersion`、`runId`、`environment`、`startedAt`、`endedAt`、`status`、`summary`、`durationStatistics`、`cases`、`diagnosticCounts`、`report`、`inputManifestHash` |
| JUnit XML | 一个 testsuite，含 test/failure/error/skipped 计数，以及每个 ATT 用例的 testcase |

## 10 CLI 參考

### 命令

| 命令 | 目的 | 是否调用外部工具 |
|---|---|---:|
| `help` | 显示语法和选项；无命令时默认 | 否 |
| `version` | 输出 ATT 版本 | 否 |
| `validate` | 校验包或选中依赖闭包 | 否 |
| `snapshot` | 生成同名规范 testcase XML | 否 |
| `run` | 校验并执行已选 Case | 是，dry-run 除外 |
| `debug` | 使用 debug sidecar 执行一个 Template、Flow 或 Tool | 是 |
| `load` | 执行已声明的 scenario，或将 Debug sidecar promotion 为 Quick Load | 是 |
| `docs` | 生成可搜索的包文档 | 否 |
| `report` | 为已完成 run 重新生成报表 | 否 |
| `build` | 归档最新已完成 run | 否 |
| `clean` | 删除文档化的 ATT 生成输出 | 否 |

### 命令语法

表格中使用 Linux/macOS 启动器 `./att.sh`。Windows 上使用 `att.bat`，命令与选项相同。`att.bat snapshot`、`att.bat validate` 和 `att.bat docs` 不会触发配置的 testcase 工具。Windows 校验会检查 `.sh` 文件是否存在并路径是否安全，跳过 POSIX 启动/可执行兼容性，并输出一条警告列出受影响工具；一次校验 PASS 并不证明这些脚本能在 Windows 上运行。运行前请提供并测试 Windows 原生等价物。二进制发布要求 Java 8+；源码树 `att.bat` 会在可用时使用 Maven，否则要求存在 `target\classes`。

| 语法 | 说明 |
|---|---|
| `./att.sh` 或 `./att.sh help` | 显示帮助 |
| `./att.sh version` | 输出版本 |
| `./att.sh snapshot` | 未指定 selector 时递归生成 `testcase.root` 下所有快照；等同于 `--all` |
| `./att.sh snapshot --suite <xlsx>` | 生成一个同名 XML 快照 |
| `./att.sh snapshot --all` | 递归生成 `testcase.root` 下所有快照 |
| `./att.sh snapshot --suite-dir <dir>` | 在某目录下递归生成快照 |
| `./att.sh validate --package` | 校验整个包；默认范围 |
| `./att.sh validate --selected <selection>` | 校验选中依赖闭包 |
| `./att.sh validate --package --format json` | 向 stdout 输出单个校验 JSON 文档 |
| `./att.sh run --all` | 运行所有发现的 Case |
| `./att.sh run --suite <xlsx>` | 运行一个工作簿；可重复 |
| `./att.sh run --suite-dir <dir>` | 在目录下发现工作簿 |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | 包含一个完整 Case ID |
| `./att.sh run <selection> --tag <tag>` | 包含一个标签 |
| `./att.sh run <selection> --exclude-tag <tag>` | 排除一个标签 |
| `./att.sh run <selection> --dry-run` | 仅校验/规划，不执行工具 |
| `./att.sh run <selection> --update-snapshot` | 在校验前显式刷新已更改的完整工作簿快照 |
| `./att.sh run <selection> --fail-fast` | 在首次 FAIL/ERROR 后停止调度 |
| `./att.sh run <selection> --rerun-failed` | 重新选择先前 FAIL/ERROR 的 Case |
| `./att.sh run <selection> --run-id <id>` | 设置最终 run 目录名 |
| `./att.sh run <selection> --output-dir <dir>` | 覆盖输出根目录 |
| `./att.sh run <selection> --ci-output junit,json` | 写出 CI XML/JSON 与 JUnit HTML |
| `./att.sh run <selection> --format json` | 输出机器可读摘要 |
| `./att.sh run <selection> --quiet` | 抑制详细实时进度；保留最终摘要和错误 |
| `./att.sh run <selection> --verbose` | 为兼容性保留；详细实时进度已是默认行为 |
| `./att.sh debug` | 发现可运行的 Tool、Template 和 Flow；只显示实际存在的默认 sidecar |
| `./att.sh debug template <id>` | 执行一个 Template；自动发现 `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | 执行一个规范 Flow；自动发现 `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | 执行一个 Tool；自动发现 `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | 覆盖目标自动发现的 debug 输入 |
| `./att.sh debug <type> <id> --set input.path=<yaml-value>` | 覆盖 typed `EXEC.INPUT` 值；可重复使用 |
| `./att.sh debug tool <id> --set arg.name=<yaml-value>` | 覆盖一个 Tool argument；可重复使用 |
| `./att.sh debug <type> <id> --set vars.path=<yaml-value>` | 在 expression evaluation 前覆盖 Template/Flow bootstrap `EXEC.VARS` |
| `./att.sh debug <type> <id> --output-dir <dir>` | 将 debug 输出隔离到 `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | 输出紧凑机器可读摘要；完整证据仍在 `result.yaml` |
| `./att.sh debug <type> <id> --quiet` | 抑制详细实时进度；保留最终摘要和错误 |
| `./att.sh load` | 发现 `load/` 下有效的 `att-load/*` scenario；报告无效的已声明 scenario |
| `./att.sh load <scenario.yaml> --quiet` | 抑制定期实时进度；保留最终摘要和错误 |
| `./att.sh load <scenario.yaml> --verbose` | 为兼容性保留；有界实时进度已是默认行为 |
| `./att.sh load --debug <type> <id>` | 使用 `load/load.yaml` policy，将 Debug sidecar promotion 为普通单 workload Load run |
| `./att.sh load <scenario.yaml> --set input.path=<yaml-value>` | 覆盖单 workload `EXEC.INPUT`；多 workload scenario 不支持未限定覆盖 |
| `./att.sh load <scenario.yaml> --set arg.name=<yaml-value>` | 覆盖单 workload Tool scenario 的 argument |
| `./att.sh load <scenario.yaml> --set vars.path=<yaml-value>` | 覆盖单 workload Template/Flow bootstrap vars |
| `./att.sh report --run-id <id>` | 重建 `report/index.html` 和 `report/junit.html` |
| `./att.sh docs` | 生成 `build/docs/index.html` |
| `./att.sh build` | 在 `build/` 中归档最新完成 run |
| `./att.sh clean` | 删除文档化生成输出 |

### Standalone debug 配置例子

`run`、`debug` 和 `load` 默认采用交互式 verbose 行为。Lifecycle、Case、Stage、Action、资源 attempt、retry、assertion 和错误事件会即时写出并及时 flush。实时 Case-log 镜像复用与 `case.log` 相同的脱敏 append 路径；`case.log`、`case.yaml`/`result.yaml`、report 和 evidence 仍是持久化事实来源。并发 Case-log 区块会带有 Case ID 前缀。`--quiet` 抑制详细实时进度，但保留最终摘要和错误。使用 `--format json` 时，机器可读内容仍写入 stdout，实时进度写入 stderr。Load 只定期输出有界计数/速率并节流错误，不会为每个成功 iteration 输出一大段内容。

以下每个文件都是完整的 `att-debug/v1.1` 文档，展示 Template、Flow、分组 Tool、未分组 Tool 和临时覆盖值的不同写法。

Template sidecar（`templates/PAYMENT_INVOKE/debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: PAYMENT debug
  amount: 100
  environment: SIT
stage:
  key: invoke
  values:
    channel: WEB
    sourceRef: SRC-001
```

执行：

```sh
./att.sh debug template PAYMENT_INVOKE
```

Template 表达式应优先读取 `${EXEC.INPUT.amount}`、`${EXEC.INPUT.environment}` 和当前 Stage 的 `${EXEC.INPUT.channel}`；当前 Stage 的 `values` 会在该 Stage 期间覆盖同名 Case-level input，Stage 结束后恢复。对应的 `CASE.*` 路径仍是兼容 aliases，`CASE.STAGES.*` 只保留为旧的执行／证据视图。

Flow sidecar（`templates/flows/common/compose/debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: Compose debug
  traceId: TRACE-001
stage:
  key: DEBUG
  values:
    mode: SIT
inputs:
  source: payment
  suffix: -debug
```

执行：

```sh
./att.sh debug flow common.compose.v1
```

Flow 可用 `${EXEC.INPUT.source}` 读取 `inputs`；如果没有名为 `inputs` 的业务字段，旧定义仍可用只读兼容视图 `${CASE.inputs.source}`，但不会把整棵 `inputs` 子树重复写入 `EXEC.INPUT`。

分组 Tool sidecar（`fpp.invokeApi` 对应 `config/tools/fpp.debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  RefNo: REF001
tools:
  invokeApi:
    arguments:
      requestId: REF001
      requestType: PAYMENT
      requestFile: /tmp/payment-request.xml
      apiLogPath: /tmp/payment-api.log
```

执行：

```sh
./att.sh debug tool fpp.invokeApi
```

`invokeApi` 是工具组内的 local key；参数值必须是 Tool descriptor 接受的 scalar 或 list。Standalone Tool adapter 不接受用 map literal 表示普通 Tool 参数。

未分组 Tool sidecar（`config/tools/invokePaymentApi.debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
arguments:
  requestFile: /tmp/payment-request.xml
  environment: SIT
```

执行：

```sh
./att.sh debug tool invokePaymentApi
```

未分组 Tool 使用根 `arguments`；不需要再包一层 `tools.invokePaymentApi.arguments`。

临时覆盖自动发现的 sidecar：

```sh
./att.sh debug template PAYMENT_INVOKE \
  --input /tmp/payment-debug.yaml \
  --output-dir /tmp/att-debug --format json
```

明确指定的 `--input` 优先于目标旁边的 `debug.yaml`。缺少文件、schema 错误、未知或缺少 Tool 参数等输入／配置错误会返回 exit code `2`，并在诊断中标出 `Debug input: ...`。

保护字段例子：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseId: pretend-id
  outputDirectory: /tmp/pretend-output
  VARS: {shouldNotReplace: true}
  STAGES: {shouldNotReplace: true}
```

即使输入包含这些字段，`EXEC.ID`、`EXEC.RUN_ID`、`EXEC.OUTPUT_DIR`、`EXEC.VARS`、`EXEC.ACTIONS` 以及对应的 `CASE.*`、`RUN.*`、`ACTIONS.*`、`TOOL.*` 和 `DB.*` aliases 仍由框架生成。模式及 scheduler 诊断不会暴露给 expressions。`EXEC.STAGES` 不是 canonical Context 节点；Stage 历史仍由旧的 `CASE.STAGES` 证据视图保存。诊断时查看 `output/debug/<debugId>/case.log`、`result.yaml` 和 `artifacts/case.yaml`。

### 退出码

| 代码 | 含义 |
|---:|---|
| 0 | 命令/运行成功，且无 FAIL、ERROR、INVALID |
| 1 | 至少一个 FAIL，且无 ERROR/INVALID |
| 2 | CLI/配置/校验/INVALID 失败 |
| 3 | 至少一个 ERROR，或不可恢复运行时失败 |

### 完整選項矩陣（3.6.0）

`--config <file>` 選擇 base configuration；`--env <name>` 從 `att-config/v2.10` 選擇 environment profile，適用於 `run`、`validate`、`debug` 和 `load`。`--help` 顯示說明。`--case-id` 是 `--case` 的相容別名。`--parallel` 是已棄用的 `--allow-parallel-runs` 相容拼法，應優先使用後者。`--queue` 與 `--allow-parallel-runs` 控制共用 output root 的 process-level concurrency，不會在單一 run 內增加 Case worker。`--profile` 為 `run` 或 `load` 寫入 performance diagnostics。

Load 以 scenario 為基礎；明確提供的 workload option 會先覆蓋對應欄位，再重新驗證 effective scenario：

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

無 target 的 `debug` 和 `load` 是唯讀 discovery。Debug 會驗證可執行 target，但不呼叫 Tool，也不建立 output。Load 只掃描宣告 `att-load/*` 的 YAML、驗證 target，並回報無效的已宣告 descriptor；其他 YAML 會忽略。Discovery 模式支援 `--config`、`--env`、`--format`、`--quiet` 和 `--verbose`。

`--set` 可重複使用，namespace 只能是 `input`、`arg` 或 `vars`。值使用 safe YAML 解析並保留型別，例如 `42`、`true`、`null`、`[a, b]` 或 `{id: 7}`；nested path 可用 map key 及數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效。解析時不會執行 ATT expression；shell 可能展開的值要加引號。`arg.*` 僅適用 Tool，`vars.*` 僅適用 Template/Flow。多 workload Load scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用 `att-load-profile/v1.0`，只放 policy，不能包含 target 或 business inputs。它可設定 `load`，以及可選的 `execution`、`thresholds`、`evidence` 和 `seed`。`load --debug` 會將 sidecar `inputs` promotion 到 `EXEC.INPUT`、Template/Flow `vars` promotion 到 bootstrap `EXEC.VARS`，或將 Tool `arguments` 傳入 Tool call，之後使用正常 Load validator、scheduler 和 evidence pipeline；不會先執行 Debug。明確的 CLI pacing 會覆蓋 profile。沒有 profile 時，請在命令列提供完整 policy：

```yaml
schemaVersion: att-load-profile/v1.0
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
```

```sh
./att.sh debug
./att.sh load
./att.sh load --debug template PAYMENT_INVOKE
./att.sh load --debug tool fpp.invokeApi --users 1 --duration 10s --set arg.requestId=42
./att.sh load --debug flow common.payment --users 4 --duration 5s --set input.customer.ids[0]=42
```

重複的 `--set <input|arg|vars>.<path>=<yaml-value>` 會在 expression evaluation 前以安全 YAML 型別覆寫 definition。`debug`、單 workload Load scenario，以及 `load --debug template|flow|tool <id>` 都支援。Quick Load 會使用可選的 `load/load.yaml`；若沒有 profile，請在 command line 提供完整 policy。例如：

```sh
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
```

完整 workload override 為 `--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`；`--think-time` 只適用 closed-VU。其餘 selection/output 選項仍受各 command 約束：`--suite`、`--suite-dir`、`--case`/`--case-id`、`--tag`、`--exclude-tag`、`--all`、`--run-id`、`--output-dir`、`--format`、`--quiet`、`--verbose`、`--ci-output`、`--dry-run`、`--fail-fast`、`--rerun-failed`、`--update-snapshot`、`--package`、`--selected`、`--input`、`--set`、`--queue`、`--parallel`、`--allow-parallel-runs`、`--profile`、`--config`、`--env` 和 `--help` 只在對應 command contract 允許時有效。

## 11 結果、報告與 Evidence

### 运行目录

```text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
└── <CaseID>/...
```

Run ID 和 Case ID 在校验后保持原样。只有 `run.yaml` 状态为 `COMPLETE` 才表示运行完成；中断工作会直接保留在已保留的 Run ID 目录中供调试。

### 人类可读 HTML 报告

`report/index.html` 是主要终端用户报表。可以直接从磁盘打开。组按 `workbookId.groupId` 汇总；界面把 `groupId` 标记为 Sheet。Case 支持 Workbook/Sheet/Status 下拉框、对 workbook/group/full Case ID/tag 的大小写不敏感搜索，以及每列标题的升序/降序排序。Duration 按数值排序。

展开的 Case 包含完整 Case ID、名称、状态、持续时间、Expected 和 Actual 结果、每条记录动作结果的一行、详细执行日志，以及 `.log`/`case.yaml` 的显式链接。Action Results 每行独立显示最终渲染的 Description，并写入 `run.yaml` 与 CI JSON。为兼容既有报表，Expected 仍是所有 assert 动作非空最终 description 与 `expected` 的有序 LF 联接；Actual 是所有非空运行时 `actual` 的有序 LF 联接。

### 结果工作簿

ATT 会复制源工作簿，并使用 `report.mode: append-to-copy` 追加配置的结果列。全局 `report.fileNamePattern` 控制文件名。侧车 `report.columns` 只修改工作簿标签。支持的映射包括 `result`、`durationMs`、`expectedResult`、`actualResult`、`caseLog`、`reportLink`、`runTime`；Expected/Actual 单元格保留 LF 字符并以换行文本显示。结果回填使用与 testcase loader 相同的 Excel 显示格式和空白规范化规则读取 Case ID，因此带前导零等数字格式的 ID 在执行与报表写入时会匹配同一 Case。

### JUnit XML

每个 ATT Case 对应一个 `<testcase>`：

| ATT 状态 | JUnit 表示 |
|---|---|
| PASS | 无 failure 子节点 |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

文本会被 XML 转义。JUnit XML 与 HTML 使用 `report.junit.caseLogEmbedThresholdBytes`。低于或等于阈值的日志会被嵌入；更大的日志使用相对链接。`0` 始终使用链接。

### CI JSON 汇总

`ci/summary.json` 使用 `schemaVersion: att-ci-summary/v2.1`，包含 ATT/Run ID、环境、时间、聚合状态/统计、持续时间统计、每个 Case 记录、诊断计数、报表/产物路径以及输入清单哈希。

### 运行清单与可复现性

`run.yaml` 使用 `schemaVersion: att-run/v2.1`，记录 ATT/构建身份、Java/OS/locale/timezone、校验模式、环境、时间戳、状态/摘要、输出路径，以及有效配置、工具组文件、call-backed Tool SQL 文件（`tool-sql`）、工作簿、侧车、解析模板/负载、包内工具文件和 schema/catalog 版本的 SHA-256 hash。

### 文档、归档和清理

| 命令 | 输出/行为 |
|---|---|
| `docs` | 在 `build/docs/index.html` 生成可搜索离线包文档；Testcases 按工作簿和 Sheet 分组 |
| `report --run-id <id>` | 从完成证据重建两个 HTML 报告 |
| `build` | 归档最新完成 run，不执行测试 |
| `clean` | 删除配置输出目录、`build/docs` 与 `build/att-*.tar.gz` |

## 12 驗證與診斷

### 先從 validation 開始

每次修改 workbook、sidecar、template、helper 或 tool 後執行：

```sh
./att.sh validate --package
```

針對單一環境可執行 `./att.sh validate --config config/config.yaml --env SIT --package`。ATT 3.6.0 對本次調整的 descriptor family 僅接受現行 schema：config v2.10、DBHelper v2.6、MQHelper v1.2、HTTPHelper v1.1、Tool Group v2.9、Template/Flow v3.3 及 Load v1.2。`schemas/history/` 中的舊 schema 僅供歷史參考，不是 runtime compatibility contract。請先更新 `schemaVersion` 並將欄位遷移至現行契約，再執行 validation。診斷會保留原始違規、檔案及 YAML 欄位位置，並提供 migration guidance；ATT 不會改寫 descriptor。例如，移除舊 Render `result.path`，並依[動作與型別化值](reference.zh/14_actions.md)將 typed `output.result` 傳至下一個 Action。Unsupported version 會在執行前失敗。

現行 schema 位於 [`schemas/`](../schemas)，較舊定義位於 [`schemas/history/`](../schemas/history)。`validate --package` 會檢查 catalog 登錄的每一份 schema，即使 package 沒有使用。缺少、無法讀取、不安全或重複的註冊 schema 會硬性回報 `PACKAGE_INVALID`。Validation 不會改寫 YAML。請檢視 migration guidance、更新檔案，再針對每個選定的 `--env` 重跑 package validation。

然后根据诊断代码和结构化位置排查。不要针对人类可读消息做自动化判断。

| 类别 | 典型原因 | 修正措施 |
|---|---|---|
| `ATT-TC` | 缺失/过期快照、侧车/Sheet/表头错误、重复 Case ID | 检查快照/基名、sheet 映射、有效表头和完整 ID |
| `ATT-CTX` | 未知或歧义 Context 路径 | 检查请求/当前/缺失字段、最近建议或规范候选 |
| `ATT-STG` | 必需选择器为空白、选择器 YAML 无效、阶段键重复 | 检查选择器形式、`name`、别名和 required 标志 |
| `ATT-TPL` | 未知/重复模板、动作或负载无效 | 检查符号名/完整路径、描述符、动作类型和本地文件 |
| `ATT-CFG` | 未知字段、重复键、schema 类型/枚举错误 | 与第 6 章对照并移除不支持字段 |
| `ATT-TOOL` | 未知/缺失参数、进程或解析失败 | 对比调用契约，检查退出码和有界 stdout/stderr capture evidence |
| `ATT-PATH` | 非法 ID 或路径逃逸 | 移除非法字符，并保持内容在配置根目录下 |
| `ATT-RUN` | 超时、非零退出、渲染/运行时失败 | 检查 Case 日志和动作/工具证据 |

### 常见问题

#### 为什么 Excel 看起来没问题，但 Case ID 被拒绝？

ATT 导入的是显示单元格文本，然后应用严格的 ID 安全检查。检查隐藏的首尾空白、尾随 `.`、路径字符、控制字符以及 Windows 设备名。以文本形式保存标识符，以保留前导零。

#### 两张 sheet 能同时包含 `TC001` 吗？

可以。给 sheet 不同的 group ID，即可生成例如 `payment.payment.TC001` 和 `payment.batch.TC001` 这样的 ID。

#### 为什么 `N/A` 变成空了？

ATT 会在数据映射和阶段选择前，把 `N/A`、`NA`、`NULL`、`NONE`、空和仅空白值归一化为 blank。

#### 为什么 Context 变量失败？

ATT 会把缺失路径视作作者/运行时错误，而不是静默渲染成空字符串。遵循 `ATT-CTX-001` 的 `requestedPath`、`currentNode`、`missingSegment` 和最近建议，检查大小写敏感的作用域、物理表头/别名、阶段 key、动作 ID，以及可用性时间点。后缀简写必须唯一识别一个可读逻辑路径；当 validation 能识别 canonical current-scope replacement 时，会以 `CONTEXT_LEGACY_PATH` 发出迁移 warning。`ATT-CTX-002` 会列出所有冲突候选，以便你加长后缀或使用规范路径。声明的可选字段即使值为空白，仍然是有效空字符串。

#### 为什么 FAIL 变成 ERROR？

假断言是 FAIL。无效表达式语法/导航、工具失败、超时、解析失败、I/O 失败或运行时异常，都是 ERROR。应查看动作证据，而不只看最终聚合状态。

#### 哪些意外异常会附带 stack trace？

意外内部故障（例如 `NullPointerException`、`ClassCastException`、反射查找／存取失败、其他非預期 runtime exception，或非 domain `IllegalStateException`，包括包在 wrapper cause 內的情況）會在 `case.log` 寫入有界的 `[ATT INTERNAL ERROR]` 區塊、執行 phase 及原始 cause chain。Validation `IllegalArgumentException`、已識別的 domain／transport failure、timeout／cancellation、assertion failure 與一般 MQ no-message outcome 仍保持精簡。同一 Throwable 即使同時被 resource executor 和 Action boundary 看見，每個 Case log 也只會寫一次。Resource-specific redaction（包括由 environment 提供的 SSH identity-file path）會註冊到該 Case log，並套用至後續 log write，避免外層 Action diagnostic 洩漏未出現在 sanitized stack 的內容。Stack 最多 180 行／16 KB；configured secrets 與敏感 key/value assignment 也會遮蔽。Public Action evidence 只保留簡短錯誤類型／phase，不加入 stack。Run、Debug 及 reusable Tool/HTTP/MQ/DB 共用這條 logging path。

#### 为什么工具跑了不止一次？

它的动作启用了重试，并收到了符合条件的非零退出码。查看 Case 日志中的尝试列表和最终动作记录。

#### 我能在 `command` 中使用 shell 管道吗？

不能。ATT 会把 `|`、`>`、`<` 按字面值传递。把 shell 行为放到审查过的工具脚本中。

#### 为什么必需的 array 参数会拒绝 `[]`？

必需项验证发生在 argv 扩展之前。空 typed List 被视为缺失；请至少传入一个标量 item，或将参数设为 optional。

#### 我应该使用包校验还是选中校验？

本地快速反馈请用 selected 模式。发布前、CI 推进、或共享包时请用 package 模式。

#### 报告能否不依赖服务器打开？

可以。保持生成的 run 目录完整即可，相关相对链接仍可工作。

#### build 会不会再次执行测试？

不会。它只是归档一个已完成的持久化 run。

#### 为什么 `att.bat` 会要求 Maven，或者为什么 `.sh` 工具在 Windows 上失败？

在二进制发布中，`att.bat` 会找到 `lib\att-*.jar`，只需要 Java 8+。源码树中，`att.bat` 会在 Maven 在 `PATH` 上时使用 Maven；没有 Maven 时，需要已有的 `target\classes`。先用 `att.bat version` 确认启动器后再校验包。

启动器让 ATT 自身跨平台；它无法翻译外部工具可执行文件。请为 Windows 配置 `.bat`、`.cmd`、PowerShell 脚本（需要显式 `powershell`/`pwsh` argv）或原生可执行文件，而不是 POSIX-only `.sh`。PATH 校验遵循 Windows `PATHEXT`，因此如 `pwsh` 这类名称可解析为 `pwsh.exe`。维护多平台版本时，请保持参数契约和 stdout 输出格式一致。

#### 为什么 ATT 说会使用 mwiede/jsch，或者 Java SSH 协商失败？

当 `PATH` 中存在可执行 `ssh` 时，ATT 会优先使用本地 `ssh`。如果不存在，ATT 会打印 `local ssh command not found; ATT will use Java SSH library mwiede/jsch`，并改用 Java exec channel。这是自动回退，不是远程连通性测试。

回退实现非常保守：ATT 包含 `com.github.mwiede:jsch:2.28.2`，但不捆绑 Bouncy Castle。它要求一个可读、非符号链接的 `~/.ssh/known_hosts` 用作严格主机验证。它不会读取 `~/.ssh/config`，也不会自动使用 OpenSSH agent；需配置一个非交互可读的 `identityFile`。密码和交互式口令提示不支持。

算法可用性取决于 Java 运行时：

| 算法 | Java 回退限制 | 首选方案 |
|---|---|---|
| `ssh-ed25519`、`ssh-ed448` | 需要 Java 15+ 或 Bouncy Castle provider | 优先使用本地 OpenSSH 或 Java 15+；否则让管理员把批准的 `bcprov-jdk18on` 加入运行时 classpath |
| `curve25519-sha256`、`curve448-sha512` | 需要 Java 11+ 或 Bouncy Castle | 优先本地 OpenSSH 或 Java 11+；否则使用批准的 Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | 在所有 Java 版本上都需要 Bouncy Castle | 优先本地 OpenSSH，或在服务端启用 AES-GCM/CTR cipher，并添加 Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` 签名 | 默认被 mwiede/jsch 禁用 | 更新服务端到 RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) 或其他现代 host/user-key 算法；不要在未经审查的情况下重启 SHA-1 |

协商失败时，先用本地 `ssh -v` 复现连接，定位 host-key、key-exchange、cipher 或 user-key 不匹配。优先升级 Java 或服务端算法集合，而不是弱化 JSch 默认值。

### 安全提醒

不要把密码、token、私钥或敏感客户数据放进工作簿单元格、模板描述符、命令字符串、stdout 或 stderr。优先使用工具脚本中经批准的秘密注入方式。在共享报表和归档前进行审查。

## 13 CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 與 release/source archive，並驗證 packaged launcher。Reference generation 另外需要 Python 3 與 Pandoc。

### Runtime dependencies

Java 8+ 是 runtime baseline。ATT 不內置 JDBC driver；需要的 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

### Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

### CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定 DB/MQ logical ID 讓相同 Template 在 SIT/UAT/PREPROD 間移動而不用改 Action。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。

## 14 動作與型別化值

本章定義 ATT 3.6.0 現行 Action 契約。Template 使用 att-template/v3.3。每個完成的 Action 都會在 output.result 發布邏輯型別化值；Action 不使用共用的 result.format/path/overwrite 物件。資源配置請參閱 Tool、DBHelper、MQHelper、HTTPHelper 章節。

### Action 類型

| 類型 | 必填欄位 | 結果與行為 |
|---|---|---|
| render | payload | 將範本檔渲染為 DocumentValue；多來源時回傳以相對路徑為 key 的 DocumentValue map。不解析文件，也不寫入結果檔。 |
| tool | call | 呼叫已配置 Tool、built-in 或 helper，保留原生型別化結果。 |
| db | db 與 query/update 其中一個區塊 | 回傳 DB operation 的型別化值與 evidence。 |
| assert | assert | 評估布林條件並記錄 PASS 或 FAIL。expected、actual 是可選診斷值。 |
| log | message 或 value | 將型別化值格式化後寫入 Case 日誌。欄位為 level、message、value、format。 |
| assign | name、expression | 將 expression 的型別化結果發布至 EXEC.VARS。 |
| flow | use | 在巢狀 Action scope 執行已註冊 Flow，返回時還原 caller scope。 |

Actions 按 YAML 順序執行。依類型允許時，也可定義 id、description、onFailure、runWhen。Action ID 在 scope 內必須唯一。類型不支援的欄位會在 validation 失敗。共用 Action result、Log file 與 Log fields 不屬於現行契約。

### 區分邏輯值與表示方式

ATT 將 operation 的邏輯結果與人類可讀或 wire representation 分開：

| Boundary | 欄位/值 | 用途 |
|---|---|---|
| Command Tool stdout | stdoutFormat | 將外部 stdout 解析為型別化結果。 |
| HTTP/MQ response | responseFormat | 將外部 response bytes 解析為型別化結果。 |
| Render 輸出 | templateFormat | 標示範本所產生的 representation。 |
| 透過 HTTP/MQ 傳送抽象 Map/List | requestFormat | 在 outbound boundary 序列化該值。 |
| Log 或 resource evidence | format / evidence.output.format | 產生人類可讀表示。 |

DB result 本身已是型別化值。Tool、Action、Template、Flow 和 expression results 在 ATT 中傳遞時均保留型別。

### Render 與 DocumentValue

Render 回傳已表示的文件。DocumentValue 帶有 format 及完全一致的 rendered text：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
~~~

單一來源會令 output.result 成為 DocumentValue。多來源則回傳以 template root 相對來源路徑為 key 的有序 map。templateFormat 支援 auto、text、json、yaml、xml。auto 依副檔名選擇：.json 為 json、.yaml/.yml 為 yaml、.xml 為 xml，其他為 text。

DocumentValue.text 是權威表示。ATT 不會將它解析成可導航的 map/tree，也不會在傳輸前 pretty-print、normalize 或改寫。Render 不建立檔案，也不暴露 output.targetFiles。若要存取結構化資料，請使用原本的型別化 Context value，例如 EXEC.INPUT.amount 或前一 Action 的 output.result.amount。

Render 結果直接傳送至 HTTP/MQ：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml

sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

MQ 請將 DocumentValue 傳給 payload。DocumentValue 不可搭配 requestFormat。Resource 使用其配置的 charset/CCSID 編碼原文。DocumentValue.format 不會設定 MQMD.Format，也不會覆蓋由 resource 管理的 HTTP Content-Type。

requestFormat 僅供 Map 或 List 等抽象結構化值使用。此類 body 必須明確指定格式，例如 requestFormat=json。DocumentValue 與 requestFormat 同時出現會失敗，確保已表示文件不會被靜默 parse/serialize。只有 resource 呼叫明確定義 file 參數時，raw file input 才仍可使用；Render 不會建立 handoff file。

### Tool、DB 與 Flow 結果

Command-backed Tool 在 Tool descriptor 宣告 stdoutFormat：

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat 是 ingress parser；stdout 只解析一次成為 output.result，並非輸出序列化設定。Call-backed Tool 及 DB/HTTP/MQ operation 保留 native implementation 回傳的型別。

DB action 使用 db 及 query 或 update 其中一個區塊。SQL、bind parameters、transaction controls 和 DB evidence 依 DB action 與 DBHelper 契約處理。

Flow action 使用 canonical Flow ID 的 use。Flow 在新的 EXEC.ACTIONS scope 執行，返回時將結果/evidence 發布給 caller。META.FLOW 只在該次 invocation 執行期間存在。

### Tool evidence collector

Tool Action 可定義第一級 `evidence` collector，用來在 Action assertion 前收集診斷資料。執行順序是：

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collector 是診斷 operation，不是替代 Action。每個 collector 有自己的型別化 result，不會取代或修改 primary `output.result`：

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.ACTIONS.renderRequest.output.result},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.replyReceived} == true
```

包含 assertion 在內，Action active 時可使用：

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

Action 發布後，對應值位於 `EXEC.ACTIONS`：

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

Public shape 會將 primary resource evidence 與 collector evidence 分開：

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

Tool retry 時，每個 primary attempt 都會在該 attempt assertion 前執行 collector。Top-level `output.evidence.collectors.<id>` 是最後／勝出的 attempt；`output.attempts[n].evidence.collectors.<id>` 保留每個 attempt，包括較早的 failure。發布後的歷史路徑是 `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`。

`call` 必填。`timeoutMs` 與 primary Tool timeout 獨立。應用程式 log 的一般診斷模式使用 `onFailure: continue`，避免收集 log 失敗掩蓋原本的 business 或 assertion failure；`stop` 則令 collector failure 成為 Action error。Collector 的 status 與 diagnostic 仍可觀察，且 collector failure 不會改變 primary logical result。若資料是後續 assertion 要使用的正常 business/test value，應使用普通 Tool/Log/Assign Action，而非 evidence collector。

Collector result 遵守一般 typed-result 規則。放在 evidence 下不代表會轉成 String；Map、List 和 `DocumentValue` 均保留型別，匹配格式時保留 `DocumentValue` 的權威原文。在 Load 中，明確要求的 collector execution 與 helper `evidence.output` serialization 是兩件事；resource-output 格式化仍由 Load evidence policy 控制，不會靜默取代或刪除 author-requested collector。

### Log：將型別化值轉成人類可讀日誌

Log 是 presentation Action，因此有自己的 format 欄位：

~~~yaml
logOrder:
  type: log
  level: INFO
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

level 預設 INFO，可設 TRACE、DEBUG、INFO、WARN、ERROR。message 或 value 至少要有一項。message 以文字求值。value 可接受任意型別化值，包括巢狀 map/list。完整的 ${...} 和 #{...} expression 保留原始型別；map/list 子節點會遞迴求值，不會將數字、布林、null 或巢狀值轉成字串。format 支援 text、json、yaml、xml、sqlplus，只控制寫入 Case 日誌的字串。指定 format 時必須提供 value。

同時提供 message 和 value 時，Log 輸出 message、換行，再輸出格式化 value。output.result 是最終字串。DocumentValue 未指定 format 或指定相同格式時會保留權威原文；衝突格式會失敗，不會轉換。Log 不讀取檔案，也沒有 fields map。需要結構化日誌時，將 typed map/list 放到 value。

### Expressions 與變數 scope

Action expression 使用一般 ATT expression engine。完整 ${...} 或 #{...} expression 保留結果型別；expression 放在一般字串內才會成為 String。請參閱[Expressions and Built-ins](reference.zh/07_expressions.md)。

assign 會將 typed value 發布至 EXEC.VARS.<name> 一次。name 必須符合 [A-Za-z_][A-Za-z0-9_]*，且在 Case 中唯一。一個 Stage 指派的值可供後續 Stage 使用。Action 執行期間可讀取 output.*，發布後可讀取 EXEC.ACTIONS.<id>.output.*。Flow 有暫時 Action namespace；若 caller 在 Flow 返回後仍需要該值，請發布到 EXEC.VARS。

### Resource output evidence

Resource evidence 與邏輯 result 分離。Helper 可設定可選的 evidence.output presentation policy：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

此設定會在 operation metadata 旁加入有長度上限的人類可讀快照，不會改變 output.result 或 response parsing。Load 的 evidence.resources.output 可設 inherit（預設）或 none。none 會略過 resource-output 格式化與檔案物化。Metrics-only iteration 不建立 execution 目錄。Iteration evidence 被保留後，符合條件的 resource output 才會延遲格式化至該 workspace。

### 移除欄位與遷移

ATT 3.6.0 每種 resource 只接受現行 schema。歷史版本存放於 schemas/history，不是 active contract。

| 舊配置 | 3.6.0 形式 |
|---|---|
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite 或 renderAs/saveAs | 使用 templateFormat；將 output.result 當作 DocumentValue；沒有隱式檔案替代方案 |
| Log file | 將 typed value 直接傳入 Log.value |
| Log fields | 將 typed map/list 放入 Log.value，並指定 Log.format |
| Render targetFiles handoff 至 HTTP/MQ | 將 DocumentValue 直接作為 HTTP body 或 MQ payload |
| 在 Render result 使用 requestFormat | 移除；requestFormat 留給抽象 Map/List |

Unsupported schemaVersion 會在 execution 前由 validation 拒絕並提供 migration guidance。ATT 不會靜默轉換舊欄位，也不會為產生 guidance 而呼叫 Tools/resources。

[META Runtime and Context Model](reference.zh/03_runtime_context.md) 說明 META lifecycle；[Load Mode](reference.zh/04_execution_modes/load.md) 說明 execution identity 和 retained evidence 路徑。

## 14 附錄

附錄集中保存不應主導主要產品敘事的穩定查閱資料：schema/version matrix、compatibility/deprecation、migration note、limit/default。

### 14.1 Schema 與版本矩陣

ATT 3.6.0 現行 schema：

| Artifact | 現行 schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.3 |
| Flow | att-flow/v3.3 |
| Debug input | att-debug/v1.1 |
| Load scenario | att-load/v1.3 |
| Load summary | att-load-summary/v1.0 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |

本次調整的 resource/config schema 舊版本已移至 schemas/history，僅供歷史參考，不是 active execution contract。Unsupported version 會在 validation 失敗並提供 migration guidance。schemas/catalog.yaml 是 repository authoritative catalog。Package validation 會驗證已註冊的 schema resource 本身；封存不代表舊版本仍有 runtime compatibility。

### 14.2 相容性與已棄用 Alias

Compatibility 的目的，是讓既有 package 可讀，而不是維持第二套 current model。新 authoring 使用 canonical `EXEC`、`META`、Action-local `output`、current schema、`--env` 與目前 Tool/DB/MQ contract。

Deterministic legacy alias 在可一對一映射時可以保留並產生 migration warning；若舊語義與 scope isolation 或 common result/evidence contract 衝突，就不建立 alias。Deprecated CLI/authoring form 只有在使用者仍需要 migration path 時才保留在其 owner chapter 或 CHANGELOG。

### 14.3 遷移說明

ATT 3.6.0 將型別化 operation result、外部 parsing、渲染文件、outbound transport 和人類可讀 evidence 分開。

| 舊欄位／模型 | 3.6.0 遷移方式 |
|---|---|
| Command Tool result.format | 將 parsing 設定移至 Tool descriptor 的 stdoutFormat。 |
| 共用 Action result.format/path/overwrite | 移除。output.result 是 native logical typed value；沒有隱式檔案替代方案。 |
| Render result.format/path 或 renderAs/saveAs | 改用 templateFormat。Render 回傳含原文的 DocumentValue，不建立結果檔或 targetFiles。 |
| 透過 targetFiles 傳遞 Render 檔案 | 直接將 DocumentValue 傳入 HTTP body 或 MQ payload。 |
| 在 Render output 使用 requestFormat | 移除。requestFormat 僅供抽象 Map/List；DocumentValue + requestFormat 會失敗。 |
| Log file | 直接將 value 傳入 Log.value。 |
| Log fields | 將 typed map/list 放在 Log.value，並選擇 Log.format。 |
| HTTP/MQ 共用 result 格式設定 | 使用 responseFormat 做 ingress parsing；可選 evidence.output.format 只控制人類可讀表示。 |
| 舊 active resource/config schema | 將 schemaVersion 升至 ATT 3.6.0 現行版本，並遷移上述欄位。schemas/history 中的 schema 不是 active runtime contract。 |

Render 直接傳到 HTTP 的例子：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

抽象 typed value 必須明確使用 requestFormat：

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

Load scenario 請將舊 single-target/v1.1 格式遷移為 att-load/v1.2 workloads，再把 schemaVersion 升至 att-load/v1.3 以啟用 workload vars。`inputs` 仍對應 EXEC.INPUT；`vars` 在每個 execution 的 EXEC.ID 與 EXEC.OUTPUT_DIR 初始化後、target 啟動前評估。完整 reference 保留 native type，dependency 不受宣告順序影響；循環及 external/stateful calls 會在執行前拒絕。頂層 execution.execIdFormat 仍在 initialization 使用一般 expression engine 求值一次；closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[動作與型別化值](reference.zh/14_actions.md)、[Runtime 與 Context 模型](reference.zh/03_runtime_context.md)、[Load 模式](reference.zh/04_execution_modes/load.md)與[Schema 矩陣](reference.zh/appendices/schema_matrix.md)。

### 14.4 限制與預設值

Normative field default 以其 owner schema/configuration chapter 為準。重要 architecture limit 包括：

- load V1 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `DIAG` 是 framework-owned evidence，不屬於 expression tree；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。
