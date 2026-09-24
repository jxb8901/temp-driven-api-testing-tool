# ATT V3.5.1 使用手冊與參考

Author: Jeffrey + ChatGPT
Version: 3.5.1
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
| Debug | `att-debug/v1.0` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.0` scenario | 重複執行單一 Template、Flow 或 Tool target |

模式差異由 `EXEC.MODE` 表達；只有 load 會額外出現 `EXEC.LOAD`。可重用 Template/Flow 應主要依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`，而不是建立另一套 mode-specific runtime tree。

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

### 2.2 模板

只有当目录直接包含 `template.yaml` 时，它才是可调用模板。类别目录可以包含其他模板目录，但自身不是可调用模板。

```yaml
schemaVersion: att-template/v2.6
name: PAYMENT_INVOKE
description: Render and invoke a payment request
actions:
  buildReference:
    type: assign
    name: paymentReference
    expression: "PAY-#{sysdate('yyyyMMdd')}-#{sample.getSeq(10)}"
  renderRequest:
    type: render
    description: "Render request for ${EXEC.INPUT.caseId}; status=${output.status}"
    payload: requests/*.xml
    renderAs: file
    assert: "${output.targetFiles[0]} != null"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
    saveAs:
      path: "${EXEC.INPUT.caseId}-response.json"
      format: json
      overwrite: false
    assert: "${output.result.status} == 'SUCCESS'"
  recordResult:
    type: log
    level: INFO
    message: "Payment ${EXEC.INPUT.caseId} completed"
    file: "${EXEC.ACTIONS.callApi.output.targetFiles[0]}"
```

`schemaVersion`、`description` 和非空有序 `actions` 是必需的。`name` 在模板总是通过完整路径选择时可以省略；可复用模板应使用全局唯一的符号名。

#### 动作类型

| 类型 | 目的 | 必需字段 | 常见结果 |
|---|---|---|---|
| `render` | 渲染一个或多个 UTF-8 负载 | `type`、`payload`、`renderAs` | 嵌套 `output.result` 与 `output.targetFiles` |
| `tool` | 调用已配置的外部工具 | `type`、`call` | 嵌套类型化结果和进程证据 |
| `db` | 查询或更新已配置数据库 | `type`、`db`，以及恰好一个 `query`／`update` block | 稳定类型化 DB 结果与交易证据 |
| `assert` | 计算布尔表达式 | `type`、`assert` | PASS/FAIL 或求值 ERROR；可选 Expected/Actual |
| `log` | 写入渲染后的消息和/或 UTF-8 Case 输出文件 | `type`，至少包含 `message` 或 `file` | 合并内容、源路径和渲染字段 |
| `assign` | 求值文本并发布 Case 级变量 | `type`、`name`、`expression` | `${EXEC.VARS.<name>}`、`output.name`、`output.result` |

动作按 YAML 顺序执行。动作 ID 在模板内唯一，且不能包含点号。每个动作都可以定义 `description` 和 `onFailure: stop|continue`。

动作校验按类型进行。render 动作要求安全且非空的 payload glob，以及 `renderAs: file|text|json|yaml|xml`；它不能包含 Tool、assert-action、log 或 DB 字段。重试和 Action 级 timeout 仅对 Tool 动作有效。Tool 与 DB Action 可使用共同的 object-shaped `saveAs`，其他类型不可使用。DB Action 必须指定已配置的 `db` ID，并在 `query` 与 `update` 中恰好选择一个；所选 block 又必须在 `sql` 与 `sqlFile` 中恰好选择一个。assert 动作要求 `assert`，并可包含 `expected` 和 `actual`；`expression`、`acture`、`actural` 都是非法字段。log 动作要求 `message`、`file` 或两者；并可使用 `level` 和 `fields`。assign 动作要求 `name` 和 `expression`。不支持的字段会报错，而不是被忽略。

每个动作都可以使用 `assert`，但 assert 动作本身把它作为必需主表达式。每个动作结果都嵌套在 `output` 下，包括 `status`、`success`、`durationMs`、`exception`、`targetFiles`、`result`，以及可选断言详情。操作错误保持 ERROR；否则显式断言决定 PASS/FAIL。一个已完成的工具进程即使返回非零退出码，也不会自动变成 ERROR：需要在 `assert` 中检查 `output.exitCode`。

每个动作都支持表达式型 `description`。验证时会检查 `${...}` 引用和 `#{...}` 调用而不执行它们，尽量解析可知的静态 Case 值，并保留运行时相关引用。执行成功后，ATT 会在当前动作局部 `${output...}` 作用域下对两种表达式形式进行求值，然后再持久化最终 description。

assign 动作使用常规 Context、内建函数、配置 Tool 与只读 DB expression 语法求值 `expression`。其 `name` 必须符合 `[A-Za-z_][A-Za-z0-9_]*`，大小写敏感，并且在当前 Case 的 `EXEC.VARS` 下不能已存在。`EXEC.VARS` 会在每个 Test Case 中创建一次，跨阶段和模板保持存在，并将运行时赋值与 Excel 及框架自有 Case 字段区分开。完整的类型化表达式（例如 `#{db.orders.query(...)}`）保留 Java object，不会转成字符串。成功赋值后，后续动作和阶段可通过 `${EXEC.VARS.<name>}` 读取；同一值也保存在 `${EXEC.ACTIONS.<assignActionId>.output.result}`。Assign 支持可选 `description`、`assert` 和 `onFailure`，但不支持 render、tool-action、log、report-only、retry、timeout 或 `saveAs`。断言 FAIL/ERROR 不会回滚已成功求值的变量；表达式失败则不会创建变量。

Render 负载路径必须保持在模板根目录下。glob 匹配会取模板相对路径排序后的普通非符号链接文件。`renderAs: file` 会把渲染结果写入 Case 输出目录中对应的相对路径；冲突会报 ERROR。其他渲染模式不会写文件，而是把一个类型化值，或多个匹配项对应的“相对路径→值”有序映射，写入 `output.result`。

log 动作可以输出渲染后的 `message`、一个 `file` 的完整内容，或两者同时输出：

```yaml
logResponse:
  type: log
  level: DEBUG
  message: "API response for ${EXEC.INPUT.caseId}:"
  file: "${EXEC.ACTIONS.callApi.output.targetFiles[0]}"
  fields:
    action: callApi
```

`message` 与 `file` 都支持统一的 `${...}` / `#{...}` 表达式引擎，并在 log 动作发布自身输出前进行求值。两者合并后的内容会以原始文本写入 Case 日志，并将 CRLF/CR 统一为 LF，因此多行内容会保留为物理行，而不是显示为 YAML escape 后的 `\\n`。相对 `file` 路径会解析到 `${EXEC.OUTPUT_DIR}` 以下；绝对路径仅在其解析后的真实路径仍位于该目录下时才接受。源必须是存在的、普通非符号链接、UTF-8 文件。路径或符号链接逃逸、恶意 UTF-8、空白解析路径，以及尝试读取当前 Case 日志，都会报 ERROR。

若要用与 DB Action `saveAs.format: text` 相同的 SQL*Plus 风格输出打印类型化 DB 结果，可在 message 中使用纯内建函数 `dbText(...)`：

```yaml
printOrders:
  type: log
  message: "#{dbText(${EXEC.ACTIONS.queryOrders.output.result})}"
```

`dbText(...)` 只格式化传入值，不执行 JDBC、不改变交易，也不清除 cache。也可以传入巢状只读 DB 表达式，但引用前一个 DB Action 可避免重复查询。

`output.sourceFile` 记录规范化后的源路径。`output.result` 包含文件内容一次，CRLF/CR 会被归一化成 LF；当两种输入同时存在时，它包含消息、一个 LF，然后是文件内容。这个相同结果也只会在人工 Case 日志中输出一次，而不会把文件内容复制到多个证据字段。

## 03 Runtime 與 Context 模型

ATT 對 Run、Debug 以及每一個 Load iteration 使用同一套公開 Context 模型。

### Canonical tree

```text
EXEC
├── ID
├── MODE
├── STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
├── ACTIONS
└── LOAD          # 只在 load mode 存在

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # 只屬於目前 Action
```

`EXEC.INPUT` 保存由 workbook/stage、debug sidecar 或 load scenario 適配而來的執行輸入；`EXEC.VARS` 是 `assign` 等顯式共享值的 mutable publication area；`EXEC.ACTIONS` 只保存**目前** Stage/Template/Flow scope 已完成的 Action；`META` 是經過篩選、immutable、secret-safe 的 metadata；`output` 只是 active Action 的局部結果，並不是持久 root。

### Scope 與 lifetime

一般 Testcase 擁有自己的 Case runtime。`EXEC.VARS` 可以在該 Case 的 Stage/Template 之間保存明確發布的值。每個 Stage/Template 都建立新的 `EXEC.ACTIONS` scope。呼叫 Flow 時會暫時換成該 Flow 的新 Action scope；Flow 內可讀取較早完成的內部 Action，返回 caller 後原來 scope 會恢復。Flow 返回後仍需使用的值必須透過 `EXEC.VARS` 發布。

公開模型沒有 `EXEC.STAGES`、`EXEC.OUTPUT`、`EXEC.CALL` 或 invocation-worker tree。Stage/Flow 歷史屬於 result/report evidence，而不是可重用 expression state。Resource connection/pool/process lifecycle state 亦屬內部資料。

### Action-local output 與 publication

可執行 Action 以穩定 envelope 發布結果；各欄位在有意義時存在：

```text
output
├── status
├── success
├── durationMs
├── result
├── diagnostic
├── evidence
└── attempts[]
```

Action 執行中使用 `${output...}`；在目前 scope 完成後使用 `${EXEC.ACTIONS.<id>.output...}`。`result` 是最後／勝出的 primary operation result；retry history 與每次 attempt 的 collector 保留在 `attempts[n]`，不會取代 top-level final result。

### Load-only Context

`EXEC.LOAD` 只是加在同一 Context 上的 conditional data，不是第二套 runtime。它可包含 `RUN_ID`、`MODEL`、`USER_ID`、`ITERATION_ID`、`ITERATION`、`PHASE`、`RUN_STARTED_AT`。Closed workload 對同一 virtual user 提供穩定 `USER_ID`；fixed-arrival-rate iteration 沒有 persistent VU identity。

### Optional lookup

`${path}` 是 strict lookup。`${path?}` 在定義允許的 missing map/list path 上返回 null，但不會把 malformed syntax、ambiguity、invalid traversal 或越權 scope access 變成合法。

### Compatibility aliases

`CASE`、`RUN`、`ACTIONS` 等 deterministic legacy view 在能與 canonical data 一對一映射時仍可讀，並可能產生 migration warning。新文件與新 authoring 使用 `EXEC`/`META`；不能保持相同語義的歷史 path 不會偽裝成 alias。

## 04 執行模式

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ 執行語義。

| 模式 | Context `EXEC.MODE` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | 單一 target invocation | `output/debug/<debugId>/` |
| Load | `load` | 重複 target iterations | `output/load/<runId>/` |

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

Debug input 使用 `schemaVersion: att-debug/v1.0`。Top-level 支援 `case`、可選 `stage`、`inputs`、`arguments`、以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。

未指定 `--input` 時，Template/Flow 會在旁邊尋找 `debug.yaml`；grouped Tool 會查找 `config/tools/<group>.debug.yaml`。沒有可發現 default sidecar 時請使用 `--input`。`--env` 在 target validation 之前使用與 Run/Validate/Load 相同的 environment resolver。

Debug 執行 target-scoped validation：只驗證 selected Template/Flow dependency closure 或 Tool contract，不要求無關 workbook。Template/Flow debug 使用與 Run 相同的 Action/Flow scope rule；Tool debug 使用相同 configured Tool invocation contract。

每次 invocation 隔離於：

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug 不建立或更新普通 `latest-run.yaml`。Exit code：`0` PASS、`1` FAIL、`2` CLI/config/input/validation 無效、`3` runtime error。它在 selected reusable-component 邊界上與正常執行等價，但**不是** workbook Case：除非 debug input/artifact 明確提供，否則沒有 workbook selection、Stage history 或 result-workbook lifecycle。

### 4.3 Load 模式

Load 由 `att-load/v1.0` scenario 重複執行一個 Template、Flow 或 Tool target。

```yaml
schemaVersion: att-load/v1.0
target: {type: template, id: V3_FLOW_EXAMPLE}
inputs: {region: HK}
load:
  users: 20
  duration: 5m
execution:
  thinkTime: 500ms
thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"
```

`target.type` 只可以是 `template`、`flow`、`tool`；只有 Tool target 可使用 `target.arguments`。Scenario `inputs` 會成為每個 iteration 的 `EXEC.INPUT`。

#### Workload models

**Closed VU** 使用正整數 `load.users`。同一 virtual user 連續執行 iteration 並保持穩定 `EXEC.LOAD.USER_ID`；`execution.thinkTime` 用於該 VU 兩次 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`，並必須同時提供正整數 `maxConcurrent` 和 V1 唯一的 `overloadPolicy: drop`。它沒有 persistent VU identity。因 concurrent limit 已滿而不能開始的 arrival 會記為 `dropped`，不排隊，也不計成 SUT error。

兩種 model 互斥。`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase，`EXEC.LOAD.PHASE` 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

#### CLI overrides

明確提供的 CLI 值會覆蓋 scenario 同名值，然後再次驗證 effective scenario：

`--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent`、`--overload-policy`。

#### Metrics、thresholds 與 evidence

Load 以 bounded memory 記錄 iteration success/failure、latency percentile、throughput；arrival-rate 另外記錄 scheduled/started/completed/dropped 與 achieved arrival rate。Threshold 支援 `errorRate`、`p95`、`p99`、`minThroughput`、`droppedRate`、`achievedArrivalRate`。

Evidence policy 控制成功 sample 與 failure retention。成功 workspace 預設不保留；failure/sample 只在 policy 需要時 materialize。DB/MQ pool 與 load-run resource ownership 屬 load-run resource layer；mutable `EXEC.VARS`、`EXEC.ACTIONS`、Tool transient state 與 per-iteration output 仍彼此隔離。

#### Outputs 與 exit codes

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/      # 有保留時
├── failures/     # 有保留時
└── performance.json   # 使用 --profile 時
```

`--profile` 量度 ATT generator/runtime overhead，不是 target host 的 CPU/memory benchmark。Load exit code：`0` PASS、`1` threshold failure、`2` scenario/configuration/target 無效、`3` runtime/infrastructure error。

`EXEC.LOAD` 的中央定義見第 3 章；artifact schema/report 細節見第 11 章。

## 05 資源與整合

Tool、DBHelper、MQHelper 是同級 integration/resource 類型。它們的 descriptor 與 lifecycle rule 不同，但最後都收斂到 5.4 的 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource ID 是 Template/expression 所引用的 logical contract。Environment profile 可以把相同 DB/MQ logical ID 綁定到不同 descriptor，而不需要修改 Action YAML。

### 5.1 Tool

Tool 是具名的 external 或 framework-native capability。每個 Tool 必須二選一：**command-backed** 或 **call-backed**。

#### Command-backed Tool

Command-backed Tool 依 configured argv contract 在本機或已配置 SSH transport 執行。Argv list 會保留每個 item 的 argument boundary；scalar command 只會被 tokenize 成相同 internal argv model。一般 process-backed Tool 不會隱式啟動 shell，也不會自動 wildcard expansion。Stdout/stderr、exit code、timeout 和 process diagnostic 屬 evidence；非零 process exit 本身不等於 assertion FAIL，除非 Action contract 明確這樣判定。

Script、CLI、SSH、third-party executable 適合 command-backed Tool。

#### Call-backed Tool

Call-backed Tool 執行 typed framework-native call，例如支援的 DB read/update facade 或 pure built-in，不需要把 typed value 轉成 process string。若 capability 本身就是 typed ATT call contract，優先使用 call-backed。

兩種 backend 都發布相同 public Action envelope。Active Action 使用 `${output.result}`，完成後使用 `${EXEC.ACTIONS.<id>.output.result}`。Final operation evidence 位於 `output.evidence`；retry 的 per-attempt evidence 保留在 `output.attempts[n].evidence`。

Tool Action 在支援位置可以使用 object `saveAs` 和 post-operation evidence collector。Collector 在 primary operation 後、該 attempt assertion 前執行；collector failure policy 不會取代 primary `result`。

### 5.2 DBHelper

DBHelper 是獨立於 Tool 的一級 JDBC resource。每個 descriptor 使用 `schemaVersion: att-dbhelper/v2.5` 和穩定 logical `id`；global `dbhelpers` 只引用 descriptor file。

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credential 可從 environment variable 解析，但不能發布到 `META`、report 或 diagnostic。JDBC driver jar 由使用者放入 `lib/`；ATT 不內置 database driver。

`type: db` Action 選擇一個 helper ID，並且只能有一個 `query` 或 `update` block。Read operation 亦可透過支援的 `#{db.<id>.query(...)}` / `scalar(...)` expression call 使用。文件契約支援 positional JDBC `?` binding，以及 direct Action 的 named `:name` parameter。

Query 返回 typed row/scalar；update 返回規範的 update result。Operation、SQL/parameter evidence 進入 common Action envelope；secret credential 永遠不是 evidence。Parameter evidence 按 descriptor/Action 的 masking/type policy 處理。

Direct DB Action 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。Executor 會在 Action timeout 與 DBHelper `statement.timeoutSeconds` 之間採用較短者；每次 retry attempt 都重新取得完整 Action timeout，`retry.intervalMs` 的等待時間不計入該 attempt timeout。

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

MQHelper 是一級 IBM MQ resource。每個 descriptor 使用 `schemaVersion: att-mqhelper/v1.0`、穩定 logical `id`、connection topology 與可選 credential。Global `mqhelpers` 引用 descriptor file；environment profile 可讓相同 logical ID 在不同環境選擇不同 descriptor。

主要 call：

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payload 採 file-based contract，因此 request bytes 不需要複製到 Context/evidence。`request` 結合 send 與 correlated receive。Correlation identifier、queue/operation metadata、timing、diagnostic 屬 evidence；credential 與 payload bytes 不複製到 evidence。

Timeout 是 operation-specific failure，與 assertion failure 分開。MQ connection/pool lifecycle 是 framework-owned resource state，特別是在 Load mode，不會公開成 `EXEC.MQ` tree。

ATT default build 不要求 IBM MQ client class；真正執行 MQ 需要 package/release 文件所述 IBM MQ client jar/profile。MQ operation 與 Tool、DB 一樣進入同一 Action result/evidence envelope。

### 5.4 Common Operation Result 與 Evidence

Tool、DB、MQ executor 先收斂到同一 operation boundary，之後 Template runner 才套用 Action lifecycle、assertion、retry policy。

```text
operation
├── result
├── evidence
├── executionSuccess
├── diagnostic
└── timing
       |
       v
Action output
├── status / success
├── durationMs
├── result          # 最後／勝出的 primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` 是 business/operation data；`evidence` 是支援執行的證據；`diagnostic` 解釋 operational failure；`status` 則是在 operation outcome 與 assertion 處理後的 Action-level classification。這些概念刻意分開。

Retry 不會在 top-level 發布多個競爭結果：只有最後／勝出的 primary operation 位於 top-level。每個 attempt 的 evidence 與 collector result 保留在 `attempts[n]`。Connection pool、JDBC transaction object、MQ session、process handle 都是 internal lifecycle state，不屬於 Context。

## 06 環境與測試數據

Environment selection 改變 resource binding，不改變 Action logic。

### Environment profiles

`att-config/v2.6` 可以定義 `environment` default 與 `environments` map。`--config` 選擇 base configuration file；`--env` 在該 configuration 內選擇 named binding。明確 `--env` 優先於 configured default；未知 environment 在任何 external execution 前失敗。

Profile 是 typed shallow binding，不是 generic recursive YAML inheritance。目前 profile 可擁有 `dbhelpers`、`mqhelpers` list：profile 明確提供某 list 時會取代 root 的該 resource list；沒有提供的 list 則繼承 common root list。

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

不同 environment 的 descriptor 應暴露相同 stable logical ID（例如 `orders`、`payment`），因此 Template/Flow/Action 在 SIT/UAT/PREPROD 之間不需要修改。

### Topology 與 secrets

Topology 可以隨 descriptor/environment 改變。Secret 應在 descriptor 支援的位置使用 `${ENV:NAME}` 注入，不應提交到 repo，也不應出現在 effective metadata/diagnostic。缺少 required environment variable 屬 validation/configuration error；錯誤會指出 field/variable name，但不列印 resolved secret。

### Cross-mode consistency

Run、Validate、Debug、Load 在 mode-specific 工作前都經過相同 effective-config environment resolution。因此 `--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 從獨立 config 遷移

原有 `--config config/environments/sit.yaml` / `uat.yaml` 工作方式仍可使用。若 package contract 相同、只改 typed DB/MQ binding，profile 更簡潔；若整體 policy、root、Tool topology 或 configuration ownership 有重大差異，仍應使用 separate config。

### Test data 擴展位置

Workbook/sidecar/snapshot 仍是目前 Testcase data contract。未來 logical environment-bound fixture（#38）應擴展本章，並沿用 stable logical-name 原則，而不是把 environment branch 放入 Action。

## 07 表達式與 Built-ins

### 统一表达式引擎

V3.4 使用一个表达式引擎，但保留两种刻意分开的角色：

- `${path}` 读取一个 Context 值并插入周围文字，例如 `Reference=${EXEC.VARS.SrcRefNo}`。
- `#{expression}` 计算一个 typed expression block。block 可包含 Context operand、调用、list literal、括号、unary operator、算术、比较、`like`、`in`、null 判断与布尔逻辑。

Context 引用在 block 内仍必须明确使用 `${...}`；应写 `${EXEC.INPUT.amount}`，不可写裸 `CASE.amount`。可在整条引用路径末尾加 `?`，例如 `${EXEC.INPUT.response.body.missing?}`。只要任一 map、list、root-owned Context 值或中间 segment 不存在，结果就是真正的 `null`；路径存在但最后值本身为 `null` 时也保持 `null`。`${path}` 仍然 strict。Optional lookup 不会抑制歧义、错误语法或在 scalar 上索引等 invalid traversal，因此这些 authoring 错误仍会失败。精确 block 保留 Java 结果类型；嵌入周围文字的 block 才会转换为文字。

```yaml
assert: "#{${EXEC.INPUT.response.body.missing?} is null}"
actual: "#{nvl(${EXEC.INPUT.response.body.missing?}, 'not supplied')}"
description: "status=${EXEC.INPUT.response.body.status?}; fallback=#{coalesce(${EXEC.INPUT.response.body.missing?}, 'N/A')}"
```

```yaml
assert: >-
  #{(${EXEC.INPUT.amount} * ${EXEC.INPUT.rate}) >= 100
    and ${EXEC.INPUT.status} in ['PENDING', 'POSTED']}
description: "Reference length: #{length(${EXEC.VARS.SrcRefNo})}"
expression: "#{${EXEC.ACTIONS.query.output.result.rowCount} + 1}"
```

运算优先级由高至低：

1. 括号、literal、`${...}`、list 和调用；
2. unary `+`、unary `-` 与 `not`；
3. `*` 与 `/`；
4. `+` 与 `-`；
5. `== != > >= < <=`、`like`、`in` 与 `is [not] null`；
6. `and`；
7. `or`。

算术 operand 必须为数值，除以零是错误。`in` 的右 operand 必须是 List、array 或 Iterable；`['A', 'B']` 这样的 literal list 与 `${EXEC.INPUT.allowedStatuses}` 这样的 typed Context list 都合法。旧的非 block assertion grammar 也接受 literal-list `in`，但算术与 typed list membership 应使用 `#{...}`。

调用参数本身可以是任何 expression。可直接嵌套调用，例如 `#{upper(trim(${EXEC.INPUT.name}))}`；旧写法 `#{upper(#{trim(${EXEC.INPUT.name})})}` 继续兼容。ASCII 单／双引号及成对弯引号可界定字符串；数字、布尔和 null literal 保留其类型。其他无引号 token 是 literal string，除非它看起来像保留 Context path 或当前可见变量，此时 ATT 会要求使用 `${...}`。

周围文字中的 Context interpolation 仍使用 `${...}`，例如 `prefix-${EXEC.INPUT.caseId}` 或 `#{concat('prefix-', ${EXEC.INPUT.caseId})}`。唯一后缀查找只在 `${...}` 中使用，建议优先写 canonical path，例如 `${EXEC.VARS.SrcRefNo}`。

为保持兼容，`${directory}/file.name` 这种无引号 Tool-call 参数继续按文字插值处理，不会误判为数字除法；`${EXEC.INPUT.amount}/2` 仍是算术。新配置中的路径值建议在可行时明确加引号。

可用值与可调用能力取决于表达式所在位置。普通 Case-runtime 字段可使用 built-in、配置 Tool 与只读 DB query；`report.fileNamePattern`、Tool `command` 与 DB SQL source 是受限 scope，不允许隐藏或递归 external execution。Tool/DB `saveAs.path`、DB `params`／`parameters` 在主调用前求值；DB SQL 内容只允许 Context 和 pure built-in。

`type: tool` 的主 `call` 可指向配置 Tool 或 ATT built-in。主 built-in 在 JVM 内执行，结果在 `${output.result}`，记录 `type: builtin` attempt evidence，但没有 process `TOOL` 节点、argv、stdout 或 stderr。

### Runtime Context

执行中立的 Context 有两个规范根和一个 Action 局部 binding：

```text
EXEC
├── ID、MODE、STARTED_AT、OUTPUT_DIR
├── INPUT（TestCase 数据或 debug sidecar input）
├── VARS（跨阶段／模板共享的 typed variables）
└── ACTIONS（已完成／已发布的 Action results）
META
├── PROJECT、SOURCE、TARGET
├── TEMPLATE、FLOW
└── TOOL、DBHELPER、MQHELPER（curated invocation metadata）
output
└── 当前 Action／attempt 的局部结果；离开该 Action 后不可见
```

`EXEC.MODE` 在普通 run 中是 `testcase`，standalone debug 中是 `debug`，load iteration 中是 `load`。`EXEC.LOAD` 仅在 `EXEC.MODE=load` 时存在；普通 TestCase 和 debug execution 不会物化它。`EXEC.INPUT`、`EXEC.VARS` 与各 scope 内的 `EXEC.ACTIONS` 是所有 execution mode 共用的 runtime state，不是平行副本。TestCase adapter 会把当前 Stage 的 caller/input values 适配到 `EXEC.INPUT`；同名时 Stage value 在该 Stage 期间优先，Stage 结束后恢复 Case-level value。`EXEC.ID`、`EXEC.MODE`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.VARS` 和 `EXEC.ACTIONS` 等框架字段不能被 Case 或 sidecar input 覆盖。不存在 `EXEC.TOOL`、`EXEC.DB`、`EXEC.MQ`、`EXEC.OUTPUT`、`EXEC.CALL`、`EXEC.INVOCATION`、`EXEC.STAGE` 或 `EXEC.STAGES`：helper/resource state 保持 internal，根层 `TOOL.*`／`DB.*` 只可作为 compatibility 或 transient view；当前 Action 使用 local `output`，完成后只在其所属 scope 通过 `EXEC.ACTIONS` 发布。Flow 返回后 parent scope 会恢复，跨 scope 值必须写入 `EXEC.VARS`。Stage/template 的 status、timing 和 history 属于 execution result/evidence model，并由旧的 `CASE.STAGES` view 提供读取。严格的 `${EXEC.LOAD.<field>}` 在非 load mode 会 validation error，可选的 `${EXEC.LOAD.<field>?}` 会解析为空；3.5.1 的 `att-load/v1.0` adapter 会按下述 contract 增加 load-only 的 `EXEC.LOAD`。

### Load V1 Context（3.5.1）

每个 load iteration 使用与普通执行相同的 `EXEC`／`META` tree 和 Action 局部 `output`。`EXEC.MODE` 是 `load`；`EXEC.ID` 与 `EXEC.LOAD.ITERATION_ID` 相同；`EXEC.STARTED_AT` 是本 iteration 的开始时间；`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS` 和 local `output` 均按 iteration 隔离。scheduler-owned fields 如下：

| 路径 | 含义 |
|---|---|
| `EXEC.LOAD.RUN_ID` | enclosing load run identity，同一 load run 的 iterations 共用。 |
| `EXEC.LOAD.MODEL` | `closed` 或 `arrivalRate`。 |
| `EXEC.LOAD.USER_ID` | closed model 的稳定 Virtual User identity；arrival-rate 为 `null` 或 absent。 |
| `EXEC.LOAD.ITERATION_ID` | load run 内全局唯一的 iteration identity。 |
| `EXEC.LOAD.ITERATION` | scheduler sequence number。 |
| `EXEC.LOAD.PHASE` | `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。 |
| `EXEC.LOAD.RUN_STARTED_AT` | 可选的 enclosing load-run start timestamp。 |

Scenario `inputs` 只会复制到 `EXEC.INPUT.*`；可复用的 Template、Flow 和 Tool 必须使用 canonical input tree、`EXEC.VARS.*`、`EXEC.ACTIONS.*` 及当前 `output.*`。`META.SOURCE` 只标识 load scenario 的 type、名称和 path；iteration identity 保留在 `EXEC.ID` 与 `EXEC.LOAD.*`，并排除 secrets。根层 `LOAD.*`、`EXEC.OUTPUT`、`EXEC.CALL` 和 `EXEC.INVOCATION` 不是公开的 load API。完整的 closed／arrival-rate 配置、CLI override、target 形式、threshold、evidence 和 validation 例子见 [`examples/load/README.md`](../examples/load/README.md)。

`att load` 会在 scheduler 启动前完成 scenario 和 target validation，再选择两个 scheduler 之一。closed mode 为 Virtual User 保持稳定 identity，等待 target 完成后才进入 think time 和下一次 iteration；arrival-rate mode 使用 absolute planned due time，`maxConcurrent` 已满时记录 generator `dropped`，不排队，也不算作 SUT failure。两个 scheduler 都只发布 compact events，由 bounded-memory metrics 汇总，并写入独立的 `output/load/<runId>/load-summary.json`、`load-summary.yaml` 和 `report/index.html`。warm-up 是真实 traffic，但默认不计入 measured threshold aggregates；成功 iteration 默认只保留 metrics，配置 sampling 后只为有界 sampled success 创建带 `case.log` 和 `case.yaml` 的 physical iteration workspace；失败则在保留 diagnostic 时 lazy 创建该 workspace。证据链接写入 load run 下的 `samples/` 或 `failures/`，不会污染普通 functional run artifacts。

最短的端到端 smoke 命令如下：

```sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

[`examples/load/README.md`](../examples/load/README.md) 是维护中的可复制参考，涵盖 Template、Flow、Tool、DB/MQ pool sizing、threshold、evidence、CLI override 和非法配置。`LoadAcceptanceTest` 会先校验全部六个例子的 schema 与 dependencies，再启动真正的 `att.FrameworkRunner load` CLI 执行短版 closed 与 arrival-rate scenario，并检查持久化 JSON、YAML 和离线 HTML report。

### Load summary 与 HTML report contract

`load-summary.json` 和 `load-summary.yaml` 共用稳定的 `att-load-summary/v1.0` contract。root-level 字段包括 `schemaVersion`、`status`（`PASS`、`FAIL` 或 `ERROR`）、`exitCode`、`runId`、`startedAt`、`endedAt`、`durationMs`、`scenario`、`timing`、`metrics`、`thresholds`、`resources`、可选的 `evidence`，以及相对于 run directory 的 `report: report/index.html`。JSON schema 位于 `schemas/att-load-summary-v1.0.schema.json`，schema catalog 以 `att-load-summary/v1.0` 注册。

持久化的 `scenario` 是专用的 report-safe projection，只保留 target type/id、workload 和 execution timing、threshold configuration 与 evidence policy；任意业务 `inputs` 及 Tool `target.arguments` 不会写入 JSON、YAML 或 HTML 的 `window.ATT_LOAD_SUMMARY`。因此 CI 和离线工具可以消费 summary，而不会把 password、token、request body 或其他过大的 payload 写入 durable report artifacts。

`timing.phases` 按 `WARMUP`、`RAMP_UP`、`STEADY`、`RAMP_DOWN` 顺序列出 configured start/end/duration window；`metrics.phases` 则提供实际 observed 的 scheduled/started/completed/failure/drop、throughput、latency、scheduler lag 和 concurrency aggregates。`WARMUP` 的 `measured` 是 `false`：traffic 仍保留在 run history，但 measured SLA aggregates 不包含它；其他 phase 仍属于 measured。没有事件的 phase 也会出现在 `timing.phases`，使 empty/edge run 具有稳定的 machine-readable shape。

`resources.db` 和 `resources.mq` 只包含 bounded pool diagnostics，例如 pool size、active/idle、waiting 和 timeout/acquisition counts；不会包含 connection、queue handle、credential 或其他 live object。pool saturation 和 acquisition timeout 必须与 SUT failure 分开解读。`evidence.items[].path` 指向 `<runId>/samples/` 或 `<runId>/failures/` 下的 retained evidence，HTML report 会把每个 path 渲染为相对链接。

`report/index.html` 是 self-contained、可离线打开的 performance report，显示 run identity/status、closed 或 arrival-rate semantics、phase/warm-up 分隔、aggregate metrics、threshold diagnostics、resource diagnostics、retained evidence links 和 bounded 一秒 time-series buckets。arrival-rate report 会明确区分 configured arrival rate、achieved scheduling rate、completed TPS 和 generator drops；drop 不属于 SUT error。报告链接到旁边的 JSON/YAML summary，但不嵌入 raw per-iteration samples 或 secrets；`window.ATT_LOAD_SUMMARY` 为离线工具提供同一份 bounded summary。

`att load --profile` 沿用既有 profile 诊断契约，并在 load summary 同目录写出 `performance.json`。它记录 load execution/report phases、bounded load counters，以及共用的 schema、Template、payload 和 process counters，使 self-overhead gate 可重复执行；它不是 target CPU 或 memory benchmark。

machine-readable 的 `metrics` 会输出配置负载（`configuredUsers`、`configuredArrivalRatePerSecond`、`configuredMaxConcurrent`）、iteration/scheduling 计数（`iterations`、`scheduled`、`measuredScheduled`、`started`、`measuredStarted`、`completed`、`success`、`failure`、`runtimeError`、`dropped`、`measuredDropped`）、并发（`activeVus`、`maxActiveVus`、`currentInFlight`、`maxInFlight`）、measured 结果（`warmupCompleted`、`measuredCompleted`、`sutErrorRate`、`runtimeErrorRate`、`droppedRate`、`completedThroughput`）、latency percentiles（`p50Ms`、`p95Ms`、`p99Ms`）、scheduler lag 及 grouped `errorClassifications`。percentiles 来自 bounded reservoir；`latencyMinMs`、`latencyMeanMs`、`latencyMaxMs` 和 `latencyObservationCount` 始终覆盖全部 measured observations 并保持 exact。runtime error 与 SUT failure 分开；generator drop 不会增加 `sutErrorRate`。`buckets` 以一秒 epoch-millisecond key 排序，每个 bucket 包含 `model`、`phase`、配置的 rate/concurrency、completed TPS、p95/p99、SUT/drop rate、active/in-flight、scheduler lag 和 error classifications。全局 latency 最多保留 4096 个 sample，每个 bucket 最多 256 个；time-series 最多保留 4096 个 bucket，超过后淘汰最旧 bucket，因此 memory 不会随 run 时长或 raw latency values 线性增长。

Load threshold 中，`errorRate` 使用 `%`，`p95`／`p99` 使用 `ms`，`minThroughput` 使用 `/s` 或 `/m`，这些 common thresholds 对两种 workload 都适用。arrival-rate 另外支持 `droppedRate`（`%`）和 `achievedArrivalRate`（`%`、`/s` 或 `/m`）。`achievedArrivalRate` 使用 `%` 时表示 measured phase 的 `measuredStarted / measuredScheduled`；warm-up 不计入，ramp-up、steady 和 ramp-down 仍纳入 integrated measured schedule。使用 `/s` 或 `/m` 时表示整个 phase window 内实际 started 的平均速率，`/m` threshold 会先换算成每秒再比较。每个 threshold 都独立输出 expected expression、格式化 actual、PASS/FAIL status 和 failure diagnostic。load result 的 exit code 为：PASS `0`、已完成但 SLA threshold 失败 `1`、validation/configuration failure `2`、load runtime/infrastructure error `3`。`target.arguments` 只适用于 Tool target；Template 和 Flow target 会以带准确 field path 的 diagnostic 拒绝。

Release gate 是可重复的整合检查，而不是 SUT microbenchmark：

```sh
mvn -q -Dtest=LoadAcceptanceTest,LoadCrossModeTest,ClosedVuSchedulerTest,FixedArrivalRateSchedulerTest,LoadRuntimeTest,LoadScenarioTest,LoadReportTest,LoadDbPoolingTest,LoadMqPoolingTest,PooledMqHelperExecutorTest,PooledMqTransportFactoryTest test
```

它检查两个 scheduler 的 CLI-to-report 路径，包括确定性的 arrival-rate cap/drop 以及 configured/achieved/completed metrics；Context deep-copy 与 iteration isolation、lazy success/failure workspace、有界 evidence 与 metrics reservoir、scheduler lag、process/file artifact、DB/MQ reuse、timeout、pool diagnostics 与 cleanup、threshold PASS/FAIL、summary schema、report rendering，以及既有 run/debug/validation compatibility test suite。Load V1 不承诺 distributed、Poisson/random pacing、weighted multi-scenario、rendezvous、adaptive pool、MQ handle pooling、XA/affinity 或 target CPU/memory benchmarking。

常见作用域包括：

| 作用域 | 示例 |
|---|---|
| EXEC.INPUT | TestCase columns、debug `case`/`inputs` 及 stage input aliases |
| EXEC.VARS | `assign` values；`CASE.VARS` 保持兼容 alias |
| EXEC.ACTIONS | 当前 Stage 已完成／已发布的 Action results；下一个 Stage 开始时清空 |
| CASE.STAGES | 持久化的 Stage/template status、timing 与嵌套 Action evidence；不是可用的 expression namespace |
| META | 安全的 project/source/target/component identity；不是 config dump 或 credential store |
| output | 当前 Action result、assertion actual value 与最终 description 输入 |
| CASE / RUN / ACTIONS | canonical state 的生成式 legacy views；`ACTIONS` 只表示当前 scope |
| CASE.DB / TOOL / DB | 既有 finalization 或 transient framework scope，与 `EXEC` 分开 |

建议使用 `${EXEC.INPUT.amount}`、`${EXEC.INPUT.channel}`、`${EXEC.VARS.txnSeq}`、`${EXEC.ACTIONS.callApi.output.result}` 和 `${META.TARGET.id}` 等 canonical paths。`${output...}` 只用于当前 Action，`${EXEC.ACTIONS.<id>...}` 只用于当前 scope 已完成的 Action。Stage／Template／Flow history（包括 `${CASE.STAGES...}`）属于持久化 result/evidence，不是可重用的 expression path；直接读取会产生 `CONTEXT_CROSS_SCOPE`。根 `${TOOL...}` 与 `${DB...}` 只可存在于 internal 或 persisted historical/result compatibility view，不是 Case 级“最近一次调用”API；普通 expression 读取会产生 `CONTEXT_LEGACY_PATH`。Tool 与 inline DB evidence 保存在所在 Action，并固定为 `<kind>.invocations[]`；Case 级 DB 收尾在完成后仍通过 `${CASE.DB.<instance>}` 提供。

现有 package 必须继续支持以下 aliases。新配置应使用右侧 canonical/local path；左侧只用于迁移或兼容说明：

| Legacy path | Canonical/local path |
|---|---|
| `${CASE.<businessField>}` | `${EXEC.INPUT.<businessField>}` |
| `${CASE.caseId}` / `${CASE.workbookId}` / `${CASE.groupId}` / `${CASE.rowCaseId}` | `${META.SOURCE.caseId}` / `${META.SOURCE.workbookId}` / `${META.SOURCE.groupId}` / `${META.SOURCE.rowCaseId}` |
| `${CASE.VARS}` | `${EXEC.VARS}` |
| `${ACTIONS}` | `${EXEC.ACTIONS}` |
| `${RUN.id}` / `${RUN.runId}` | `${EXEC.ID}` |
| `${CASE.outputDirectory}` | `${EXEC.OUTPUT_DIR}` |
| `${CASE.status}` / `${CASE.durationMs}` / `${CASE.environment}` | legacy lifecycle/result alias；没有对应的 canonical `EXEC` 字段 |
| `${CASE.STAGES.<stage>...}` | 旧 execution/evidence data；runtime expression 读取会以 `CONTEXT_CROSS_SCOPE` 拒绝 |
| `${output.*}` | 当前 Action-local `output.*` |

为保持兼容，framework adapter 仍可写入 `${CASE.<businessField>}`；该写入会作用于同一份 `EXEC.INPUT` map，不会创建第二份 input store。新 expression 应读取 canonical path；只有 compatibility adapter 才应使用旧的写入形式。framework-owned identity、lifecycle、`VARS`、`DB` 与 Stage evidence 字段仍受保护。

`META` 对 expression 是只读的，只包含 curated safe metadata，不包含 credential 或任意 config。Optional references 如 `${EXEC.INPUT.maybeMissing?}` 和 `${output.response?}` 使用同一 canonical/local resolver；缺失值返回 null，但 malformed、ambiguous 或 invalid traversal 仍然是错误。

`${EXEC.OUTPUT_DIR}` 是保留的标准化绝对路径。`EXEC.VARS` 与 `CASE.DB` 也是固定 framework-owned map，因此 sidecar `excel.dataColumns` alias 或其他 Case-root alias 不能名为 `VARS`／`DB`。三者在第一个 stage 前已存在；`CASE.DB` 保持空值，直到 Case transaction finalization 发布已使用实例 outcome。同一 Case 的 Action 不可依赖该 post-Case state。`EXEC` 不会新增 `TOOL`／`DB`／`MQ`／`OUTPUT`／`STAGE(S)` 等 helper 或 orchestration 节点；可表达式读取的 helper identity 只在有明确用途时通过 curated `META.TOOL`、`META.DBHELPER`、`META.MQHELPER` 提供。

### `config.report.fileNamePattern`

该配置使用统一表达式引擎，但拥有独立的非 Case 作用域。它只支持一个大小写敏感的值引用：

| 占位符 | 值 |
|---|---|
| `${suiteName}` | 源工作簿 basename，去掉结尾的小写 `.xlsx` 后缀；例如 `testcase/payment_regression.xlsx` 变为 `payment_regression` |

配置字符串必须显式引用 `${suiteName}`，无论它用于文本插值还是内建函数参数。call 内的裸 `suiteName` 会被拒绝。合法示例包括：

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

但不支持如 `${runId}`、`${workbookId}`、`${environment}`、`${EXEC.INPUT.caseId}` 等运行时值引用。

### Tool 定义中的 `command` 表达式

Tool 的 `command` 也拥有独立的受限上下文，只能引用该工具 `arguments` 映射中声明的键。canonical 文档及新配置应使用 `${input.<argument>}`：

| 形式 | 含义 |
|---|---|
| `${input.requestFile}` | canonical 工具本地输入引用 |
| `${TOOL.input.requestFile}` | legacy 完整别名；会产生 `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${requestFile}` | deprecated shorthand；仅在唯一对应已声明参数时兼容，并产生迁移 warning |

`${TOOL.input.argument}` 与 `${argument}` 只有在名称恰好对应当前 Tool 一个已声明参数时才会接受，并产生 `CONTEXT_TOOL_INPUT_SHORTHAND`；`att validate` 会给出精确的 `${input.argument}` 替换。未声明或有歧义的 shorthand 会报错。command-backed 与 call-backed Tool 使用相同规则。

例如：

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestFile}"
      - "${input.environment}"
    output: json
    arguments:
      requestFile:
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
    output: yaml
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
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
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

内建函数通过 `#{...}` 调用。Canonical 名称使用 framework-owned `str.*`、`date.*`、`file.*` 与 `misc.*` package；旧 flat 名称保留为兼容 alias。Tool group 同样以 `group.tool` 组成 package-like 调用名；配置 Tool 不得占用 built-in package root 或任何 canonical／legacy built-in 名称。

| 函数 | 目的 | 示例 |
|---|---|---|
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
| `file.exists/directoryExists` | 测试常规文件／目录 | `#{file.exists(${EXEC.INPUT.requestFile})}` |
| `file.size/mkdirs` | 返回文件大小／创建目录树 | `#{file.size(${EXEC.INPUT.requestFile})}` |
| `file.copy/move/delete` | 复制、移动、删除文件 | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `misc.string/number/boolean` | 类型转换与归一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默认值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 从布尔值选择两个值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 从输入中随机选择 | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | 将稳定 typed DB result 格式化为 SQL*Plus 风格文字 | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `misc.prettyPrint` | 将 Map/List/array/tree 确定性格式化为缩进文字 | `#{misc.prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

`misc.dbText` 只接受一个位置参数或具名 `value`。参数必须是直接 DB Action、DB expression 或 DB-backed Tool 返回的稳定 query／update result。它与直接 DB Action 的 `saveAs.format: text` 共用同一个确定性 formatter，并且没有 JDBC、transaction、connection 或 cache side effect。

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

Tool evidence collector 在 primary operation 後、該 attempt assertion 前執行。Collector 有獨立 timeout 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代 primary operation result。

### Transaction/resource lifecycle

DB transaction finalization 與 DB/MQ resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

未來 fixture（#38）與 DB Action-level timeout/retry（#39）應延伸本章既有概念，而不是再建立一套 reliability model。

## 09 配置參考

本章是作者编写配置时的权威阅读参考。下面提到的 [`schemas/`](../schemas/) 仍是机器可读契约。模式校验会先于跨字段和文件系统校验执行。

### 配置层与优先级

| 层级 | 来源 | 所管辖内容 |
|---|---|---|
| 全局 | `config/config.yaml` | 输出目录/环境/运行时默认值、模板根、报告、XML 模式、全局工具、组路径、可选全局 SSH |
| DB helper | `dbhelpers` 引用的独立 YAML | 一个 JDBC 实例的连接、statement timeout、交易、result limit 与 evidence policy |
| MQ helper | `mqhelpers` 引用的独立 YAML | 一个 IBM MQ TCP client 实例的队列管理器、连接、消息及 request/reply 默认值 |
| 工具组 | 配置的 YAML 路径 | 组身份、可选 script/SSH、分组工具 |
| 工作簿 | `<workbook>.yaml` | Excel 映射、阶段、工作簿标签 |
| 模板 | `template.yaml` | 模板身份和有序动作 |
| CLI | 命令选项 | 选择、Run ID、输出覆盖、展示、CI 格式 |

Action timeout 覆盖 Tool descriptor timeout，Tool timeout 覆盖全局 timeout。sidecar、stage、Template 不拥有 timeout/retry 默认。CLI 的 `--output-dir` 和 `--run-id` 会在一次命令中覆盖相应默认值。一个层级中合法的字段，若放在别的层级中也会被拒绝。

### V3.5.1 多环境 Profile 选择

ATT V3.5.1 使用一份 common `att-config/v2.6` 加上 `environments` map 选择环境；不通过修改 Action 或增加环境专用 Tool ID 来选择环境。SIT、UAT、PREPROD 及 production-like 环境之间，Action 只保留稳定的 logical ID：

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

common config 保留现有 templates、testcase root、run/execution/report 设置、`toolGroups` 和 global `tools` registry。Profile 层只允许 typed 的 DB/MQ descriptor list：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
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
    renderAs: file

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
        file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
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

[`schemas/catalog.yaml`](../schemas/catalog.yaml) 使用 `att-schema-catalog/v3.0`。当前主配置、Tool group、sidecar、Template 与 Flow 分别为 `att-config/v2.6`、`att-tool-group/v2.6`、`att-sidecar/v2.2`、`att-template/v3.0` 与 `att-flow/v3.0`。旧 schema 保持有限 read compatibility，但旧 `EXIT_CODE` retry 与 sidecar timeout 必须迁移。

### 全局配置

```yaml
schemaVersion: att-config/v2.6
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
| `schemaVersion` | 必填 | 当前为 `att-config/v2.6`；旧 V2.1/V2.2/V2.5 仍可读取，但不能声明 call-backed Tool |
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
| `report.columns` | `{}` | 任意字符串键和字符串标签值 |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | 整数 0–1048576 UTF-8 字节；0 始终使用链接 |
| `xml.namespaceMode` | `ignore` | `ignore` 或 `preserve` |
| `toolGroups` | `[]` | 唯一安全且包相对的工具组 YAML 路径 |
| `dbhelpers` | `[]` | 唯一、安全、包相对的 `.yaml`／`.yml` 路径；每个文件声明一个实例 |
| `mqhelpers` | `[]` | 唯一、安全、包相对的 `att-mqhelper/v1.0` YAML 路径；每个文件声明一个实例 |
| `environments` | absent | 非空 profile 映射；每个 profile 只可包含 `dbhelpers` 和/或 `mqhelpers` typed list |
| `ssh` | absent | 内联全局工具的可选 SSH 目标 |
| `tools` | `{}` | 可复用工具契约映射 |

### Dbhelper 配置

| 路径 | 必填/默认值 | 约束 |
|---|---|---|
| `schemaVersion` | 必填 | `att-dbhelper/v2.5` |
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

| 对象/类型 | 允许/必需契约 |
|---|---|
| 模板根对象 | `schemaVersion`、`name`、`description`、`actions`、`x-*`；`schemaVersion`、`description`、非空 `actions` 必需 |
| 动作 common | `type`、`description`、`onFailure`，以及其选定类型所属字段；动作 ID 不能含点号 |
| render | 需要 `payload`、`renderAs`；可选 `assert`; 不允许 saveAs/overwrite/output/call/expression/message/file/level/fields/timeout/retry |
| tool | 需要 `call`；可选 object `saveAs`、`assert`、`expected`、`actual`、`timeoutMs`、Action-only `retry` 与 `evidence`；command/call-backed 共用契约 |
| db | 需要 `db` 与恰好一个 `query`／`update`；block 内恰好一个 `sql`／`sqlFile`；可选位置 `params` 或具名 `parameters`、object `saveAs`、`assert`；不允许同时使用两种 parameter 形式，也不允许 retry 或 Action timeout |
| assert | 需要 `assert`；可选 `expected`、`actual`；不允许 expression/render/tool/log-only 字段、timeout 或 retry |
| log | 至少需要 `message` 或 `file`；可选 `level`、`fields`、`assert`；不允许 render/tool/assert-action-only 字段、timeout 或 retry |
| assign | 需要 `name`、`expression`；可选 `assert`；`name` 在整个 Case 的 `EXEC.VARS` 下唯一；不允许 render/tool/assert-action/log-only 字段、timeout、retry、saveAs 或 overwrite |
| retry | 必填 `maxAttempts`、`intervalMs`、`retryOn`；category 仅 `ASSERTION`、`TIMEOUT` |

`renderAs` 允许 `file`、`text`、`json`、`yaml`、`xml`。retry `maxAttempts` 为 2–10，`intervalMs` 为 0–3600000；`ASSERTION` 要求 Tool Action 有非空 `assert`。日志级别为 `TRACE`、`DEBUG`、`INFO`、`WARN` 或 `ERROR`。模板根对象与动作都允许 `x-*`；`fields` 是无约束日志字段映射。`output` 是运行时证据，绝不是动作配置字段。

#### Assign 变量唯一性与生命周期

assign 动作会在 `EXEC.VARS` 下创建一个不可变、Case 作用域的条目。一个 Case 内每个变量名必须唯一。重复声明会导致校验失败。

#### Action `saveAs`

V2.6 的 Tool 与 DB Action 共用一个 object shape：

```yaml
saveAs:
  path: relative/path/result.yaml
  format: yaml
  overwrite: false
```

`path` 必填，`overwrite` 默认 false。`format` 只控制写入表示，不改变 `${output.result}` 的 typed value。`att-template/v2.6` 不允许 sibling `overwrite` 或 scalar `saveAs: file.name`。

| Action target | 允许格式 | 默认 | 保存内容 |
|---|---|---|---|
| 配置 process Tool | `raw`、`text`、`json`、`yaml`、`xml` | `raw` | raw 为精确 stdout bytes；其他格式序列化已解析 typed result |
| `type: tool` 的主要 Java built-in | `text`、`json`、`yaml`、`xml` | `text` | built-in typed result；text 使用 UTF-8 字符串表示 |
| 配置 call-backed Tool | `text`、`json`、`yaml`、`xml` | 无，必须明确指定 | typed result；不存在 stdout |
| `type: db` | `text`、`json`、`yaml`、`xml` | 无，必须明确指定 | text 为 SQL*Plus 风格 rows／update 行数；其他格式为稳定 typed DB result object |

`raw` 仅适用于有原始 stdout 的 process Tool。configured Tool 的 `output: txt|json|yaml|xml` 继续决定 stdout 如何解析，因此也决定非 raw 格式的 typed source。

Tool／built-in 的 `text` 使用 `String.valueOf(output.result)`；直接 DB Action 的 `text` 使用上述 SQL*Plus 风格 formatter。任何写入表示都不会替换 Context 中的 typed `${output.result}`。

`saveAs.path` 可以是大小写不敏感的保留值 `console`。此时 ATT 把所选表示写入 Case 日志，不添加 `output.targetFiles`，也不创建文件。其他 path 使用 Action 前的正常 expression scope 渲染，必须得到非空安全相对路径并保持在当前 Case artifact 目录内。绝对路径、反斜线、空／`.`／`..` segment 与 containment escape 都非法。父目录按需创建。

写入发生在可选 Action assertion 之前。process Tool 的 raw 即使遇到 parse error、exit-code retry 或最终 Action 不成功，也保存已捕获 stdout；非 raw Tool artifact 要求 parse 成功，built-in artifact 要求调用成功，DB artifact 要求 JDBC 成功。codec、路径、collision 或写入失败都是 ERROR。retry 共用同一路径，后续 attempt 只能覆盖同一 Action 先前 attempt 写入的 artifact。最终路径加入 `output.targetFiles`。

`render`、`assert`、`log` 与 `assign` 不支持 `saveAs`；render file 继续使用 `renderAs: file`。

`att-template/v2.3` 保持读取兼容：旧式 Tool `saveAs: response.json` 加 sibling `overwrite` 会内部归一化并维持原行为；新模板必须使用 V2.5 object form。

### 工具契约

每个工具要求 `name`、`description`，以及恰好一个 `command` 或 `call`；可选 descriptor `timeoutMs` 提供 Tool 默认值。Command 可以是非空标量或字符串列表，`output` 默认为 `txt` 并支持 `txt|yaml|json|xml`。Call 必须是一个精确表达式，目标为 DB query/scalar/update 或 pure built-in；可选 `cache` 只含 `scope: case|db`。Call-backed Tool 禁止 process-only `output`、SSH/script 与参数 `argName|argNameMode`。V2.6 不定义 `delimit`；多值直接在调用中传 typed array。Update 不能缓存，`db` cache 只适用于 DB query/scalar。

每个参数都要求 `name`、`description` 与 YAML boolean `required`。Command-backed 参数可使用 argv 属性；call-backed 参数只描述与校验 typed input。

### 标识符和路径约束

Run ID 和完整 Case ID 会直接用作目录名，ATT 不会对合法标识做 slug 化或哈希处理。

Run ID 必须非空、最多 128 个 Unicode 码点，不能是 `.` 或 `..`，不得含前导/尾随空白或尾随 `.`，且不能包含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|`、NUL、控制字符。Windows 设备名（如 `CON`、`NUL`、`COM1`、`LPT1`）会按大小写不敏感方式拒绝。

`workbookId`、`groupId`、`rowCaseId` 同样遵循相同字符规则。`workbookId` 与 `groupId` 不能含点号，因为点号用于分隔三个组件；`rowCaseId` 可含点号。模板路径相对 `templates.root`；render glob 匹配必须保持在模板下，`renderAs: file` 与 Tool/DB Action `saveAs.path` 目标必须保持在 Case artifact 目录下。ATT 会规范化并检查根包含性。

### Validation JSON 合约

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.5.1",
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
| `./att.sh run <selection> --quiet` | 抑制默认生命周期和完整 Case 日志输出 |
| `./att.sh run <selection> --verbose` | 明确保留默认生命周期进度和完整 Case 日志镜像；为兼容性保留 |
| `./att.sh debug template <id>` | 执行一个 Template；自动发现 `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | 执行一个规范 Flow；自动发现 `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | 执行一个 Tool；自动发现 `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | 覆盖目标自动发现的 debug 输入 |
| `./att.sh debug <type> <id> --output-dir <dir>` | 将 debug 输出隔离到 `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | 输出紧凑机器可读摘要；完整证据仍在 `result.yaml` |
| `./att.sh report --run-id <id>` | 重建 `report/index.html` 和 `report/junit.html` |
| `./att.sh docs` | 生成 `build/docs/index.html` |
| `./att.sh build` | 在 `build/` 中归档最新完成 run |
| `./att.sh clean` | 删除文档化生成输出 |

### Standalone debug 配置例子

以下每个文件都是完整的 `att-debug/v1.0` 文档，展示 Template、Flow、分组 Tool、未分组 Tool 和临时覆盖值的不同写法。

Template sidecar（`templates/PAYMENT_INVOKE/debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.0
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
schemaVersion: att-debug/v1.0
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
schemaVersion: att-debug/v1.0
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
schemaVersion: att-debug/v1.0
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
schemaVersion: att-debug/v1.0
case:
  caseId: pretend-id
  outputDirectory: /tmp/pretend-output
  VARS: {shouldNotReplace: true}
  STAGES: {shouldNotReplace: true}
```

即使输入包含这些字段，`EXEC.ID`、`EXEC.MODE`、`EXEC.OUTPUT_DIR`、`EXEC.VARS`、`EXEC.ACTIONS` 以及对应的 `CASE.*`、`RUN.*`、`ACTIONS.*`、`TOOL.*` 和 `DB.*` aliases 仍由框架生成。`EXEC.STAGES` 不是 canonical Context 节点；Stage 历史仍由旧的 `CASE.STAGES` 证据视图保存。诊断时查看 `output/debug/<debugId>/case.log`、`result.yaml` 和 `artifacts/case.yaml`。

### 退出码

| 代码 | 含义 |
|---:|---|
| 0 | 命令/运行成功，且无 FAIL、ERROR、INVALID |
| 1 | 至少一个 FAIL，且无 ERROR/INVALID |
| 2 | CLI/配置/校验/INVALID 失败 |
| 3 | 至少一个 ERROR，或不可恢复运行时失败 |

### 完整選項矩陣（3.5.1）

`--config <file>` 選擇 base configuration；`--env <name>` 從 `att-config/v2.6` 選擇 environment profile，適用於 `run`、`validate`、`debug` 和 `load`。`--help` 顯示說明。`--case-id` 是 `--case` 的相容別名。`--parallel` 是已棄用的 `--allow-parallel-runs` 相容拼法，應優先使用後者。`--queue` 與 `--allow-parallel-runs` 控制共用 output root 的 process-level concurrency，不會在單一 run 內增加 Case worker。`--profile` 為 `run` 或 `load` 寫入 performance diagnostics。

Load 以 scenario 為基礎；明確提供的 workload option 會先覆蓋對應欄位，再重新驗證 effective scenario：

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

完整 workload override 為 `--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`；`--think-time` 只適用 closed-VU。其餘 selection/output 選項仍受各 command 約束：`--suite`、`--suite-dir`、`--case`/`--case-id`、`--tag`、`--exclude-tag`、`--all`、`--run-id`、`--output-dir`、`--format`、`--quiet`、`--verbose`、`--ci-output`、`--dry-run`、`--fail-fast`、`--rerun-failed`、`--update-snapshot`、`--package`、`--selected`、`--input`、`--queue`、`--parallel`、`--allow-parallel-runs`、`--profile`、`--config`、`--env` 和 `--help` 只在對應 command contract 允許時有效。

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

### 先从校验开始

在每次工作簿、侧车、模板或工具变更后执行：

```sh
./att.sh validate --package
```

然后根据诊断代码和结构化位置排查。不要针对人类可读消息做自动化判断。

| 类别 | 典型原因 | 修正措施 |
|---|---|---|
| `ATT-TC` | 缺失/过期快照、侧车/Sheet/表头错误、重复 Case ID | 检查快照/基名、sheet 映射、有效表头和完整 ID |
| `ATT-CTX` | 未知或歧义 Context 路径 | 检查请求/当前/缺失字段、最近建议或规范候选 |
| `ATT-STG` | 必需选择器为空白、选择器 YAML 无效、阶段键重复 | 检查选择器形式、`name`、别名和 required 标志 |
| `ATT-TPL` | 未知/重复模板、动作或负载无效 | 检查符号名/完整路径、描述符、动作类型和本地文件 |
| `ATT-CFG` | 未知字段、重复键、schema 类型/枚举错误 | 与第 6 章对照并移除不支持字段 |
| `ATT-TOOL` | 未知/缺失参数、进程或解析失败 | 对比调用契约，检查退出码/stdout/stderr/raw output |
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

## 14 附錄

附錄集中保存不應主導主要產品敘事的穩定查閱資料：schema/version matrix、compatibility/deprecation、migration note、limit/default。

### 14.1 Schema 與版本矩陣

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0`（相容的舊格式仍可讀） |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` 是 repository 的 authoritative catalog。Compatibility 是 reader contract；新 authoring 應使用相應 feature 的 current schema。

### 14.2 相容性與已棄用 Alias

Compatibility 的目的，是讓既有 package 可讀，而不是維持第二套 current model。新 authoring 使用 canonical `EXEC`、`META`、Action-local `output`、current schema、`--env` 與目前 Tool/DB/MQ contract。

Deterministic legacy alias 在可一對一映射時可以保留並產生 migration warning；若舊語義與 scope isolation 或 common result/evidence contract 衝突，就不建立 alias。Deprecated CLI/authoring form 只有在使用者仍需要 migration path 時才保留在其 owner chapter 或 CHANGELOG。

### 14.3 遷移說明

Current Reference 依產品概念描述 ATT，不再按 release chronology 組織。逐 release 變更仍保留在 `CHANGELOG.md` 與 `docs/history/`。

目前主要 migration：

- 新 authoring 優先使用 `EXEC` / `META`，而非 legacy Context alias；
- 使用 `output.result` / `EXEC.ACTIONS.<id>.output.result` 及 common evidence/attempt contract；
- 把 Tool、DBHelper、MQHelper 視為 peer resource；
- 若只改 typed DB/MQ binding，使用 environment profile；
- 把 Run、Debug、Load 視為 peer execution mode。

Pre-#42 monolithic manual 的可審核 disposition 記錄在 `docs/reference-migration-map.md`。

### 14.4 限制與預設值

Normative field default 以其 owner schema/configuration chapter 為準。重要 architecture limit 包括：

- load V1 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `EXEC.LOAD` 只在 Load iteration 存在；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。
