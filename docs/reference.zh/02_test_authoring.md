## 02 測試案例編寫

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 02 快速开始

这个示例会创建一个支付测试：渲染 JSON、调用工具并验证返回状态。

#### 第 1 步：配置 ATT

创建 `config/config.yaml`：

```yaml
schemaVersion: att-config/v2.6
outputDirectory: output
environment: SIT
timeoutMs: 10000
templates:
  root: templates
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
xml:
  namespaceMode: ignore
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Send a rendered payment request
    command: ["./tools/invoke_payment_api.sh", "${input.requestFile}", "${input.environment}"]
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered request filename
        required: true
      environment:
        name: Environment
        description: Target environment name
        required: true
```

argv 列表形式的 `command` 会保留每一项为一个独立进程参数。旧式的标量命令会被 token 化一次并进入相同的内部列表。新配置应使用 canonical `${input.requestFile}`；`${requestFile}` 只在唯一对应已声明参数时兼容，并由 validate 发出迁移 warning。

#### 第 2 步：创建工作簿与侧车

创建 `testcase/payment.xlsx`，包含一行表头：

| Case ID | Tags | Amount | Expected Status | Invoke Template |
|---|---|---:|---|---|
| TC001 | smoke,payment | 100 | SUCCESS | PAYMENT_INVOKE |

创建相邻的 `testcase/payment.yaml`：

```yaml
schemaVersion: att-sidecar/v2.2
id: payment
excel:
  sheet: payment=Payment Cases
  headerRows: 1
  caseId: Case ID
  tags: Tags
  dataColumns: amount=Amount, expectedStatus=Expected Status
stages:
  - key: invoke
    template: Invoke Template
    required: true
```

完整 Case ID 是 `payment.payment.TC001`：第一个 `payment` 是包内唯一的工作簿 `id`，第二个是工作簿 sheet 映射左侧的逻辑 `groupId`，`TC001` 来自该行。

#### 第 3 步：创建模板

创建 `templates/payment/template.yaml`：

```yaml
schemaVersion: att-template/v2.6
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    renderAs: file
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
  assertStatus:
    type: assert
    description: Payment API status matches the expected status
    assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
    expected: "${EXEC.INPUT.expectedStatus}"
    actual: "${EXEC.ACTIONS.callApi.output.result.status}"
```

创建 `templates/payment/request.tmp.json`：

```json
{
  "caseId": "${EXEC.INPUT.caseId}",
  "amount": "${EXEC.INPUT.amount}"
}
```

#### 第 4 步：创建模拟工具

创建 `tools/invoke_payment_api.sh`：

```sh
#!/usr/bin/env sh
set -eu

request_file="${1:?missing request file}"
environment="${2:?missing environment}"

[ -f "$request_file" ] || {
  echo "request file not found: $request_file" >&2
  exit 2
}

printf '{"status":"SUCCESS","environment":"%s"}\n' "$environment"
```

赋予执行权限：

```sh
chmod +x tools/invoke_payment_api.sh
```

#### 第 5 步：校验并运行

```sh
./att.sh validate --package
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001 --run-id SIT-001 --ci-output junit,json
```

在 Windows 上，用 `att.bat` 替换 `./att.sh`；命令名、选项、输出和退出码是相同的。这个快速开始示例是 POSIX shell 示例，因此 Windows 包需要在运行前配置等效的 `.bat`、`.cmd`、PowerShell 或原生可执行文件。

在开发阶段，使用选中范围校验会更快：

```sh
./att.sh validate --selected --suite testcase/payment.xlsx --case payment.payment.TC001
```

#### 第 6 步：查看结果

成功完成后，打开：

```text
output/SIT-001/report/index.html
```

其他有用文件包括：

- `output/SIT-001/run.yaml` — 版本化运行清单和输入哈希；
- `output/SIT-001/payment.payment.TC001/case.yaml` — 持久化运行时上下文；
- `output/SIT-001/ci/summary.json` — CI JSON 汇总；
- `output/SIT-001/ci/junit.xml` — JUnit XML；
- `output/SIT-001/report/junit.html` — 人类可读的 JUnit 视图。

你现在已经拥有完整的 ATT 开发闭环：编写、校验、运行、查看和优化。

### 03 用户指南

本章说明正常日常工作流中的数据流转顺序。

#### 3.1 工作簿

##### 工作簿、侧车和快照之间的关系

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

##### 映射数据列

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

##### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空单元格和仅包含空白字符的值都会归一化为空白。普通空白数据值会变为空字符串。空白 `(yaml)` 单元格则保持为空白，不进行解析。

必需阶段选择器会拒绝空白值。可选阶段如果选择器为空白，则跳过。

##### 公式、日期、百分比和科学记数法单元格

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

##### 多行表头

`headerRows: 2` 表示第 1–2 行是表头，数据从第 3 行开始。ATT 会扫描每个物理列从上到下，使用最后一个非空且已去除首尾空白的表头单元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不会拼接父子标签。表头匹配会移除空格、制表符、换行符、NBSP 以及其他 Unicode 空白字符；匹配其余部分仍区分大小写。例如，`案例 編號`、`案例\n編號`、`案例編號` 会被视为同一列。每个有效表头在归一化后必须唯一，因此仅因空白差异而不同的两个物理表头会被认为是重复表头错误。测试用例加载和结果工作簿写回使用相同的投影逻辑；结果列如果原本不存在，则会写入最终表头行。

##### 阶段与模板选择

每个侧车阶段都有一个不含点号的 `key`，以及一个命名物理 Excel 选择器列的 `template` 字段。选择器单元格可以包含符号模板名、完整相对模板路径，或 YAML 映射：

| 单元格值 | 含义 |
|---|---|
| `PAYMENT_INVOKE` | 符号名称简写 |
| `payment/local/CT001` | 相对 `templates.root` 的完整路径简写 |
| `name: PAYMENT_INVOKE` | 明确的符号名称映射 |
| `name: PAYMENT_INVOKE` 加其他键 | 模板选择 + 阶段私有行数据 |

ATT 会先将 `name` 作为全局唯一的符号名解析。只有在没有符号名匹配时，才会尝试完整相对模板路径。绝对路径、部分路径、以及逃逸出 `templates.root` 的路径都是非法的。

所有选择器映射键（包括 `name`）都会复制到阶段 Context 中。`stages[].dataColumns` 会增加更多阶段私有值。选择器映射与阶段数据列之间如果出现重复键，则报错。

##### 阶段执行控制

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

#### 3.2 模板

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

##### 动作类型

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

#### 3.3 工具

工具是一个外部能力，可以在全局 `config.yaml` 中配置，也可以位于独立的工具组文件中，并由模板动作通过命名参数调用。

##### Tool backend 选择与共同 Action envelope

对于新的 framework-native 或可重用能力，call-backed Tool 是首选／默认扩展模型。它在 ATT typed runtime 中执行，保留 String、Number、Boolean、null、List、Context value 及 nested built-in/helper call 的原生类型。Command-backed Tool 仍然完整支持，但应作为脚本、第三方 CLI、SSH、操作系统命令或其它外部 process 边界的特殊扩展机制；它没有被 deprecated。

两种 backend 的底层 invocation contract 有意不同，但 observable outcome 会在同一个 Action boundary 汇合：

| | call-backed | command-backed |
|---|---|---|
| Invocation | typed native/helper call | OS process、script、CLI 或 SSH |
| 首选角色 | 普通 framework-native/reusable Tool | 支持的 external-process escape hatch |
| 参数 | typed values 和 nested calls | deterministic argv；scalar 是一个 item，flat List 可展开 |
| `argName` / `argNameMode` | 不适用 | 只支持 process 参数塑形 |
| stdout/stderr 与 exit code | 没有 process contract | process evidence contract |
| cache | 在适用时支持 | 不提供 process cache |
| 发布 | 共同 Action result/evidence | 共同 Action result/evidence |

每个 primary Tool、直接 DB operation 和 MQ helper operation 都发布同一个
operation result，由 Action runner 消费并发布为 Action result/evidence envelope。Action 执行期间使用 `${output.result}` 和
`${output.evidence}`；发布后使用 `${EXEC.ACTIONS.<actionId>.output.result}`
和 `${EXEC.ACTIONS.<actionId>.output.evidence}`。按实际能力，envelope 可包含
`status`／`success`、`durationMs`、typed `result`、`diagnostic`、helper-native
`evidence` 以及 retry／collector `attempts`：

```text
output.evidence.tool.invocations[0] # 最终 Tool、command 或 built-in metadata
output.evidence.db.invocations[0]   # 最终 SQL、parameter、row/update 和 timing metadata
output.evidence.mq.invocations[0]   # 最终 queue、MsgId/CorrelId、reason 和 timing metadata
output.attempts[n].evidence # post-invoke collector evidence
```

每个 operation evidence kind 都固定使用 `<kind>.invocations[]`，即使只有
一次 invocation 也不改变形状；retry 的历史 evidence 只保留在
`${output.attempts[n].evidence}`，顶层只发布最终／winning operation。
JDBC connection/transaction、MQ connection/queue、process handle 和 cache
lease 都是 internal resource lifecycle state，不会成为 `EXEC.DB`、
`EXEC.MQ`、`EXEC.TOOL` 或其它 helper-specific canonical Context root。
现有 root `TOOL.*`／`DB.*` 及 Action-level 大写 helper node 只可保留在
internal 或 persisted historical/result compatibility view，不是受支持的
general expression API，也不会削弱 #29 的 validation/migration 规则；
完成 Case 后的 `${CASE.DB.<instance>}` 表示 transaction finalization state，
不是 DB operation result/evidence。

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered request
    command: ["./tools/invoke_payment_api.sh", "${input.requestFile}", "${input.environment}"]
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered request path
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

动作调用必须使用配置的键。多个声明参数的工具会使用命名参数：

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
```

未知、重复或缺失必需参数都会在验证时失败。参数元数据用于记录和验证契约，不会自动注入值。如果且仅如果工具恰好声明一个参数，调用时可以省略参数名：`#{getAppLogs(${EXEC.INPUT.caseId})}` 等价于 `#{getAppLogs(caseId=${EXEC.INPUT.caseId})}`。零参数工具仍然使用 `#{tool()}`；多参数工具则拒绝位置参数。

全局工具保留无前缀名称。V2.2 的 tool group 可通过全局配置中的 `toolGroups` 列出：

```yaml
toolGroups:
  - config/tools/database.yaml
```

引用文件声明 package 内唯一 ID 及其工具：

```yaml
schemaVersion: att-tool-group/v2.2
id: database
name: Database tools
description: Read-only queries
script: ["/opt/att/database-tools"]
tools:
  selectPayment:
    name: Select payment
    description: Query one payment
    command: ["select-payment", "--case", "${caseId}"]
    output: json
    arguments:
      caseId: {name: Case ID, description: Full Case ID, required: true}
```

调用方式为 `#{database.selectPayment(caseId=${EXEC.INPUT.caseId})}`。在 `script` 存在时，逻辑 argv 为 `/opt/att/database-tools selectPayment select-payment --case <caseId>`：脚本 argv、无前缀 tool key、然后是 tool command argv。没有 `script` 时，工具命令从可执行文件开始。规范的结果／证据路径是当前 Action 的 `output.result`／`output.evidence.tool.invocations[0]`；`TOOL.database.selectPayment` 只作为现有包的历史结果兼容 view，并不是受支持的 general expression API。

以下两个参考 helper 会明确提供 pathname expansion，但不会改变普通 process-backed Tool 的契约：

```yaml
runChecks:
  type: tool
  call: >-
    #{fpp.exehelper(
        command='wc',
        arguments=['-l', '${EXEC.OUTPUT_DIR}/requests/*.xml'],
        stdoutPath='${EXEC.OUTPUT_DIR}/request-counts.txt'
    )}

findTransactions:
  type: tool
  call: >-
    #{fpp.loghelper(
        maxTidFiles=10,
        minTidFiles=2,
        outputPrefix='${EXEC.OUTPUT_DIR}/transaction',
        logFiles=['/var/log/payment/app*.log', '/archive/payment/app-2026-07-2?.log'],
        keywords=[${EXEC.INPUT.caseId}, 'SUCCESS'],
        recentLogCount=0,
        sshOption='--ssh'
    )}
```

`fpp.exehelper.arguments` 是有序 typed array；`exehelper.sh` 对每个含 `*`、`?` 或 `[` 的参数按其 filesystem working directory 展开。匹配结果使用 C locale 的 pathname 顺序，且每个匹配仍是独立 argv；路径含空格也不会再被拆分。没有匹配的 pattern 会保持为一个字面参数。Executable 名称、stdout/stderr 路径不会展开；shell variable、command substitution、pipe 和 redirection 也不会执行。

`fpp.loghelper.logFiles` 与 `keywords` 是有序 typed array；每个 path／pathname pattern 会在被搜索的 host 上独立展开。只接受 regular file；相同 canonical path 只搜索一次；无匹配 pattern 会跳过并输出诊断。如果本地没有任何 pattern 匹配 regular file，调用失败。远程 host 收到的是原始 pattern，因此会按远程 filesystem 展开。

直接运行 `tools/loghelper.sh` 而不带参数时，会显示用法并成功退出；`--help` 与 `--usage` 行为相同。Help 写入 stderr，确保 stdout 继续只用于 ATT YAML result contract。

启用 `--ssh` 时，可在 `tools/loghelper.sh` 配置 `SSH_SERVERS`，或通过 `LOGHELPER_SSH_SERVERS` 提供多行记录：

```text
localhost||||
server1.example.com|appuser|22||/opt/att/tools/loghelper.sh
server2.example.com|appuser|2222|/secure/att_ed25519|/opt/att/tools/loghelper.sh
```

每行格式为 `host|user|port|identity-file|remote-loghelper-path`。共享 server list 可以包含 `localhost`、loopback address 或当前 hostname；loghelper 会识别这是已经搜索过的本机，不会 SSH 到自身。非本机记录仍必须提供 user、正整数 port、remote helper path，以及配置时存在的 identity file。

##### 数据库助手（DB helper）

dbhelper 不属于 `tools`，也不使用 Tool group schema。全局配置只引用独立文件：

```yaml
schemaVersion: att-config/v2.6
dbhelpers:
  - config/dbhelpers/orders.yaml
  - config/dbhelpers/audit.yaml
```

每个文件只声明一个实例；ID 在整个包内忽略大小写后必须唯一。下面是完整示例：

```yaml
# config/dbhelpers/orders.yaml
schemaVersion: att-dbhelper/v2.5
id: orders
name: Orders database
description: Order query and update connection
connection:
  url: jdbc:postgresql://db.example/orders
  username: att
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
  readOnly: false
  isolation: readCommitted
  properties:
    applicationName: att
statement:
  timeoutSeconds: 30
transaction:
  scope: case
  onEnd: rollback
result:
  maxRows: 1000
  maxCellBytes: 1048576
  maxBytes: 10485760
evidence:
  sql: full
  parameters: values
pool:
  maxSize: 20
  minIdle: 2
  connectionTimeout: 2s
```

`connection.url` 必填；username、password 和 properties 中的值可写成完整的 `${ENV:NAME}` 环境变量引用。`driverClass` 可选，优先使用 JDBC service discovery。`readOnly` 是 ATT 的 update 拒绝边界，也会传给 JDBC Connection；它不能防止 vendor side effect。`isolation` 可为 `driverDefault`、`readUncommitted`、`readCommitted`、`repeatableRead` 或 `serializable`。

`statement.timeoutSeconds` 是实例级 SQL timeout，默认 30，范围 1–3600。ATT 对该实例创建的每个 `PreparedStatement` 调用 `setQueryTimeout`；DB Action 不接受 Action 级 `timeoutMs`。

`evidence.parameters` 可为 `masked`、`types` 或 `values`，V3.3 默认是 `values`。`values` 会记录实际 SQL binding 值和 `null`，方便调试；connection URL、username、password 和 connection properties 不会进入 parameter evidence。若 SQL 参数本身含敏感业务数据，套件作者应明确改为 `masked` 或 `types`。

交易设置组合如下：

| `scope` | `onEnd` | 行为 |
|---|---|---|
| `statement` | `commit` | JDBC auto-commit |
| `statement` | `rollback` | 每次 DB 操作后 rollback |
| `case` | `commit` | Case 完成时 commit 每个已使用实例 |
| `case` | `rollback` | Case 完成时 rollback 每个已使用实例 |

默认值为 `scope: case`、`onEnd: rollback`。

##### IBM MQ helper

全局配置使用 `mqhelpers` 引用独立的 `att-mqhelper/v1.0` 文件：

```yaml
schemaVersion: att-config/v2.6
mqhelpers:
  - config/mqhelpers/orders.yaml
```

```yaml
schemaVersion: att-mqhelper/v1.0
id: orders
name: Orders MQ
description: Order request and reply queues
connection:
  queueManager: QM1
  host: mq.example.internal
  port: 1414
  channel: APP.SVRCONN
  username: att
  password: "${ENV:MQ_PASSWORD}"
message: {ccsid: 1208, format: MQSTR, persistence: asQueue}
requestReply: {waitMs: 10000}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
evidence: {payload: metadata}
```

MQ 仅可作为 `type: tool` Action 的主要 call：`mq.orders.send(queue=..., file=...)`、`mq.orders.receive(queue=..., waitMs=..., correlationId=...)` 或 `mq.orders.request(requestQueue=..., replyQueue=..., file=..., waitMs=...)`。Payload 按原始文件 bytes 读取；request 先 PUT 捕获 MsgId，再以它作为 GET CorrelId。reason 2033 是成功但没有消息；需要 reply 时用 Action assertion 判断 `replyReceived`。MQ queue handle 仍是 invocation-scoped，不使用 syncpoint；load 模式会通过有界 pool 复用 physical Connection，并独占借用 lease，只会让失败的 Connection 失效，无消息的健康 Connection 会归还。`MQ_POOL_TIMEOUT` 与 MQ operation error 分开报告。Pool metrics 提供 active、idle、total、waiting、wait duration、create/failure、replacement 和 timeout 计数，且不包含 credentials。reply 文件只写一次到 Case output，evidence 不保存完整 payload 或 credential。IBM client jar 通过 Maven `ibm-mq` profile 或 package `lib/` 提供，默认 ATT build 不内置 vendor client。

###### DB Action

查询使用 `query` block，不另设沉重的 `operation` 字段：

```yaml
queryOrder:
  type: db
  db: orders
  query:
    sql: >-
      select id, status
      from orders
      where customer_id = :customerId and status = :status
    parameters:
      customerId: "${EXEC.INPUT.customerId}"
      status: OPEN
  assert: "${output.result.rowCount} > 0"
  saveAs:
    path: order-result.json
    format: json
    overwrite: false
```

更新使用 `update` block：

```yaml
closeOrder:
  type: db
  db: orders
  update:
    sql: "update orders set status = ? where id = ?"
    params: [CLOSED, "${EXEC.INPUT.orderId}"]
  assert: "${output.result.affectedRows} == 1"
```

`query` 与 `update` 必须且只能出现一个。block 内的 `sql` 与 `sqlFile` 也必须且只能出现一个。位置参数使用 `params`：可省略（默认为空 list）、使用 YAML list，或使用一个精确 Context 引用，其类型化值必须是 Java `List`。具名参数使用 `parameters` map，SQL 中以 `:name` 引用；ATT 会按 SQL 出现顺序安全编译为 JDBC `?` binding，同名 placeholder 可重复使用。每个具名 placeholder 必须有值，且 map 中不能有未使用的 key。`params` 与 `parameters` 不能同时存在。两种形式的动态值都使用正常 `${...}`／`#{...}` 求值，最终都通过 `PreparedStatement.setObject` 绑定，不会拼接回 SQL。batch、generated keys、callable statements 和多个 JDBC result 仍不支持。

`type: db` 不支持 `retry` 或 Action 级 `timeoutMs`。自动重试 update 并不安全。DB 执行成功后可使用普通 `description`、`assert` 和 `onFailure`；执行失败时不运行断言，状态保持 ERROR。

DB 的 `saveAs` 使用共同 object shape，但保存的是类型化 result，而非 stdout。`path` 与 `format: text|json|yaml|xml` 必填，`overwrite` 默认 false，`raw` 非法。`text` 使用下述 SQL*Plus 风格。路径必须保持在 Case artifact 目录内；格式化、序列化或写文件失败都是 ERROR。

查询的 text 输出保持 JDBC column-label 顺序，按 terminal display width（包括中文等全形 Unicode 字符）扩展每列；所有非空值均为数值的列右对齐，`NULL` 明确显示。末尾为 `1 row selected.` 或 `<n> rows selected.`。表头／cell 内的反斜线、控制字符与不可见 format 字符会转义，确保一条 DB row 只占一个物理行。空查询输出 `no rows selected.`；update 输出 `1 row updated.` 或 `<n> rows updated.`。编码为 UTF-8，行尾为 LF。该文本只含表格／update 摘要；若需要完整 transaction metadata，应使用 JSON、YAML 或 XML。

```text
ID    STATUS  AMOUNT
----  ------  ------
A100  READY    12.50
A101  DONE         3

2 rows selected.
```

###### 渲染 SQL 与 SQL 文件

inline SQL 在 JDBC prepare 前使用当前 Context 渲染。前置 render Action 也可提供完整 SQL：

```yaml
query:
  sql: "${EXEC.ACTIONS.renderSql.output.result}"
  params: "${EXEC.INPUT.queryParams}"
```

也可引用静态、包内 UTF-8 文件：

```yaml
query:
  sqlFile: sql/orders/find-by-customer.sql
  params: ["${EXEC.INPUT.customerId}", OPEN]
```

validate 会确认 `sqlFile` 是包内普通非符号链接文件。ATT 对文件内容使用与 inline SQL 相同的渲染规则，并记录 source path 与 SQL hash／全文；路径本身不渲染。SQL source 只允许 Context 与 pure built-in，不允许配置 Tool 或巢状 DB 调用。外部准备应在前一 Action 完成，再引用其结果。

渲染只适合 SQL 结构；测试数据应留在 `params` 并使用 JDBC `?`。ATT 不解析、分类、改写 SQL，也不会把参数插入 vendor SQL 字串。

###### 表达式中的 DB 查询

只读查询可在所有 Case-runtime expression 位置调用：

```yaml
assert: >-
  #{db.orders.scalar(
      sql='select count(*) from orders where customer_id = ?',
      params=${EXEC.INPUT.customerParams}
  )} == 1
```

多参数可按 JDBC placeholder 次序 inline 编写：

```yaml
#{db.orders.query(
    sql='select id from orders where customer_id = ? and status = ? and amount >= ?',
    params=[${EXEC.INPUT.customerId}, 'OPEN', ${EXEC.INPUT.minimumAmount}]
)}
```

- `#{db.<instance>.query(sql|sqlFile, params?)}` 返回稳定查询结果 object。
- `#{db.<instance>.scalar(sql|sqlFile, params?)}` 要求恰好一行一列并返回该 typed cell；其他基数都是 ERROR。

完整 query call 可在 assign 中保留 Java object：

```yaml
loadOrders:
  type: assign
  name: customerOrders
  expression: >-
    #{db.orders.query(
        sql='select id, status from orders where customer_id = ?',
        params=${EXEC.INPUT.customerParams}
    )}
```

后续可读取 `${EXEC.VARS.customerOrders.rows[0].STATUS}`。非 scalar 的 query object 不能插入周围文字；请用 assign 或 DB Action。expression update、DDL、callable statement 与 generic execute 一律拒绝。查询失败会使所在 Action 与 Case 成为 ERROR；inline evidence 保存在 `EXEC.ACTIONS.<action>.DB.<instance>.<callId>`。

###### 结果与连接生命周期

查询的 `rows` 永远是 List，包括零行或一行：

```yaml
success: true
operation: query
rowCount: 1
rows:
  - ID: A100
    STATUS: READY
affectedRows: null
transaction: {scope: case, onEnd: rollback, state: PENDING_ROLLBACK}
error: null
```

update 使用 `rows: []`、`rowCount: 0` 与整数 `affectedRows`。重复 column label 是 ERROR，需在 SQL 加 alias。binary 使用 Base64，temporal 使用可移植字符串；LOB/cell/row/result 超限会失败而非截断。

失败仍保留相同顶层形状：`success: false`、空 rows、零 rowCount、null affectedRows，以及经净化的 `error`（`type`、`message`、`sqlState`、`vendorCode`）。类型包括 `CONNECTION_ERROR`、`DB_POOL_TIMEOUT`、`BIND_ERROR`、`SQL_ERROR`、`TIMEOUT`、`LIMIT_EXCEEDED`、`ROLLBACK_ONLY` 与 `FINALIZE_ERROR`。

Connection 的生命周期只与 dbhelper 实例和执行 thread 有关，与 Case 无关。ATT 为每个实例/thread 重用一个 Connection。Case 完成时对该 Case 已使用实例执行配置的 commit/rollback，但不关闭 Connection；thread 结束或 run 结束才关闭。load 模式下，每个 dbhelper 在 load-run owner 内拥有一个 HikariCP pool；Connection 按需借用，由一个 iteration thread 独占，在 Case finalize 或 abort 后归还。`DB_POOL_TIMEOUT` 与 SQL/SUT error 分开报告；pool metrics 提供 active、idle、total、waiting、borrow wait duration、borrow timeout 和 borrow failure，且不包含 credentials。

新 Case 开始前，ATT 对该 thread 上每个已打开的 non-auto-commit Connection 做一次 rollback，以隔离不同 Case。若 rollback 异常，ATT 丢弃旧 Connection 并立即尝试 reconnect；这不会改变新 Case 状态。数据库仍不可用时，第一个实际 DB Action 才成为 ERROR。

对 `scope: case`，任何 SQL/JDBC 错误都会把该实例标为 rollback-only；同一 Case 后续调用不发送 SQL并直接 ERROR，Case 收尾时 rollback。vendor DDL 可能按自身规则隐式 commit，ATT 无法把它强制变成交易式操作。

最终结果写入固定 `${CASE.DB.<instance>}`。`CASE.DB` 由框架拥有、区分大小写、不能改名或由 Case 数据提供：

```yaml
CASE:
  DB:
    orders:
      scope: case
      onEnd: rollback
      state: ROLLED_BACK
      success: true
      error: null
```

三个相似写法职责不同：

| 写法 | 含义 |
|---|---|
| `#{db.orders.query(...)}` | 小写可调用 namespace，在表达式求值点执行只读查询 |
| `${CASE.DB.orders.state}` | 固定 Case Context 路径，包含 Case 完成后的交易收尾结果 |
| `${DB...}` | 单次调用内部 evidence scope；不是 Case 级“最近一次 DB 调用”API |

ATT 在 Case 初始化时创建空 `CASE.DB`，所有 Action 完成并执行交易收尾后，才为已使用实例加入 entry。因此同一 Case 的 Action 不能依赖 `${CASE.DB.orders.state}` 作执行决策。单次操作结果请读 `${EXEC.ACTIONS.<actionId>.output.result}`；`CASE.DB` 用于持久化 Context、Case log、报告或其他 post-Case 处理。

ATT 不内置特定 JDBC driver。将 driver 与全部依赖放入包根 `lib/`，更改后重启 ATT。默认使用 JDBC service discovery；旧式 driver 可配置 `connection.driverClass`。V2.5 使用 flat shared classpath，不支持动态 reload 或 driver 依赖隔离。

##### Call-backed Tool（V2.6）

Call-backed Tool 为重复的 DB 操作提供简短、业务化的名称，同时保留一級 dbhelper 的 typed result、SQL timeout、transaction 与 evidence 行为。它是 façade，不取代 `type: db` 或直接 `#{db.<instance>.*}`。

主配置使用 `att-config/v2.6`，并引用独立的 V2.6 Tool group 与 V2.5 dbhelper：

```yaml
schemaVersion: att-config/v2.6
toolGroups:
  - config/tools/orders-db.yaml
dbhelpers:
  - config/dbhelpers/orders.yaml
```

一个 Tool 必须且只能有 `command` 或 `call`。下面配置覆盖多参数 query、scalar、SQL file、update 与两种 cache scope：

```yaml
# config/tools/orders-db.yaml
schemaVersion: att-tool-group/v2.6
id: orders
name: Order database tools
description: 可复用的 typed order 操作

tools:
  find:
    name: Find orders
    description: 按客户与状态查询订单
    call: "#{db.orders.query(sql='select order_id, status from orders where customer_id = ? and status = ?', params=[${input.customerId}, ${input.status}])}"
    cache:
      scope: case
    arguments:
      customerId: {name: Customer ID, description: 要查询的客户, required: true}
      status: {name: Status, description: 精确订单状态, required: true}

  count:
    name: Count orders
    description: 返回一个 typed count
    call: "#{db.orders.scalar(sql='select count(*) from orders where customer_id = ?', params=[${input.customerId}])}"
    cache:
      scope: db
    arguments:
      customerId: {name: Customer ID, description: 要统计的客户, required: true}

  findByDate:
    name: Find orders by date
    description: 使用 package 内的 rendered SQL file
    call: "#{db.orders.query(sqlFile='sql/orders-by-date.sql', params=[${input.customerId}, ${input.fromDate}])}"
    arguments:
      customerId: {name: Customer ID, description: 要查询的客户, required: true}
      fromDate: {name: From date, description: 包含此日期起的订单, required: true}

  updateStatus:
    name: Update order status
    description: 更新一个订单
    call: "#{db.orders.update(sql='update orders set status = ? where order_id = ?', params=[${input.status}, ${input.orderId}])}"
    arguments:
      orderId: {name: Order ID, description: 要更新的订单, required: true}
      status: {name: Status, description: 新状态, required: true}
```

Tool 定义应通过 canonical `${input.<argument>}` 读取 typed 参数；`${TOOL.input.<argument>}` 是 legacy 兼容 alias，并产生 `CONTEXT_TOOL_INPUT_SHORTHAND`。裸 `input.*`／`TOOL.input.*` 会被拒绝。定义内不能读取 `CASE`、`RUN`、`ACTIONS` 或 evidence root，Case 数据必须从外层调用传入。允许 pure built-in，例如 `params=[${input.customerId}, #{upper(${input.status})}]`。不允许调用另一 configured Tool，因此不会形成 Tool dependency cycle。

`call` 必须是一个完整 `#{...}`，目标只能是 `db.<instance>.query|scalar|update` 或一个 pure built-in。Call-backed Tool 禁止 `output`、SSH、group `script` 及参数的 `argName|argNameMode`；这些 process-only 字段会报错，不会被静默忽略。

DB façade 的 `sqlFile` 必须是静态 package-relative 路径，例如 `sqlFile='sql/orders-by-date.sql'`；不能从 input 或表达式动态产生路径。ATT 会校验该文件，并以 `tool-sql` input 将其 SHA-256 写入 `run.yaml`，执行时再渲染文件内容。因此 package provenance 保持完整，而 SQL 内容仍可读取已声明 Tool input 与 pure built-in。

###### 场景：在表达式中取得 typed query result

READ façade 可用于 `assign`、`assert`、render payload、log field、description 与普通调用参数：

```yaml
loadOrders:
  type: assign
  name: customerOrders
  expression: >-
    #{orders.find(
        customerId=${EXEC.INPUT.customerId},
        status='OPEN'
    )}

checkOrders:
  type: assert
  assert: "${EXEC.VARS.customerOrders.rowCount} > 0"
  expected: 至少一个 OPEN order
  actual: "${EXEC.VARS.customerOrders.rowCount}"
```

精确调用直接返回与 `db.orders.query` 相同的 Java object，不经过文本序列化；后续可读取 `${EXEC.VARS.customerOrders.rows[0].STATUS}`。

Scalar 也可直接放入断言：

```yaml
checkOrderCount:
  type: assert
  assert: "#{orders.count(customerId=${EXEC.INPUT.customerId})} >= 1"
```

`scalar` 仍要求正好一行一列，否则是 ERROR。

###### 场景：Tool Action、saveAs 与 update

READ façade 可作为主要 Tool Action，并保存 typed result：

```yaml
queryOrders:
  type: tool
  call: "#{orders.find(customerId=${EXEC.INPUT.customerId}, status='OPEN')}"
  saveAs:
    path: db/open-orders.json
    format: json
    overwrite: false
```

Call-backed `saveAs` 与其它 Action 一样使用 `path`、`format`、`overwrite`。`format` 必填，可为 `text|json|yaml|xml`；因为没有 process stdout，`raw` 无效。Query/update object 建议用 JSON/YAML/XML，scalar 才通常使用 text。

WRITE façade 只能是 `type: tool` 的主要调用：

```yaml
closeOrder:
  type: tool
  call: "#{orders.updateStatus(orderId=${EXEC.INPUT.orderId}, status='CLOSED')}"
  assert: "${output.result.affectedRows} == 1"
```

若把 update façade 放入 `assign`、`assert`、payload、另一个 Tool 参数或 SQL，validate 与 runtime 都会拒绝。DB 操作异常令 Action/Case 为 ERROR，不是 FAIL。

###### 场景：包装 pure built-in

```yaml
tools:
  normalizeStatus:
    name: Normalize status
    description: trim 后转大写
    call: "#{upper(#{trim(${input.value})})}"
    cache:
      scope: case
    arguments:
      value: {name: Value, description: 状态文字, required: true}
```

调用方式为 `#{normalizeStatus(value=${EXEC.INPUT.status})}`。Pure built-in façade 可用 Case cache，但不能用 DB cache。

###### Cache scope 与 stale-read 契约

```yaml
cache:
  scope: case   # 或 db
```

只缓存成功的 query、scalar 或 pure-built-in result；update 不能配置 cache。Key 是「qualified Tool 名称 + typed input 的确定性表示」的 SHA-256；named arguments 的书写顺序不会改变 key，不同 Java 类型则是不同 entry。

| scope | 所有者与生命周期 | 典型用途 |
|---|---|---|
| `case` | 当前 `CaseRuntimeContext`；Case 结束即消失 | 同一 Case 内重复 lookup |
| `db` | dbhelper instance + executor thread；通常为 worker/suite 生命周期 | 跨 Case 重用稳定 reference data |

Cache 有意与 JDBC lifecycle 解耦。DB update、commit、rollback、Case transaction 收尾、Connection close 与自动 reconnect 都**不会**清除任一 cache。DB-scope hit 也不会打开或检查 Connection。因此 `db` scope 明确可能返回 stale data，只应在 package owner 接受此行为时启用。V2.6 没有 TTL、size limit、持久化或自动一致性。

Evidence 记录 `cache.scope`、SHA-256 `cache.key` 与 `cache.hit`。Cache miss 有正常 DB evidence；hit 因未执行 JDBC，不产生新 DB invocation。

###### Timeout、retry、lifecycle 与 evidence

Call-backed DB Tool 保留目标 dbhelper 的 read-only、transaction、result limits、Connection 与 Case lifecycle 规则，并与 command-backed Tool 共用 Action `timeoutMs` 和 ASSERTION/TIMEOUT retry。JDBC query timeout 取 Tool attempt timeout 与 `statement.timeoutSeconds` 中较短者；配置 retry 的 Action 会绕过 call-backed cache，避免轮询旧值。

Action 通过共同 envelope 保存 `output.result`、`output.evidence.tool.invocations[0]` 和适用的 `output.evidence.db.invocations[0]`；`TOOL`／`DB` wrapper 只作历史结果兼容 view。`command`、`argv`、`stdout`、`stderr`、`rawOutput`、`exitCode` 等 process-only 字段不存在于 call-backed Tool 的 typed contract。

选择建议：一次性 SQL 用 `type: db`；一次性表达式读取用直接 `db.*`；稳定、重复、有业务名称或需要 cache 的操作用 call-backed Tool；需要 executable、SSH、argv、stdout parser 或 exit-code retry 时继续用 command-backed Tool。

完整规范见 [V2.6 Call-backed Tool System Design](02_System_Design_V2.6.md)。

##### 命令处理

ATT 会把每个命令归一化为 argv 模板列表。标量命令会用旧式 token 化器处理一次。YAML 列表已经是归一化形式：每一项恰好是一个 argv 值，不再被 token 化。普通声明标量参数因此保持原子化，不管值中包含空格、引号、反斜杠、前导短横线还是 shell 类字符。Tool call 传入的 typed List 只会在完整 token 占位符位置扩展为零个或多个 argv 值。解析后的值不会再次被 token 化。ATT 不会调用本地 shell。

V2.3.2 会以 `${EXEC.OUTPUT_DIR}` 作为当前工作目录启动每个本地工具进程。以 `./` 或 `../` 开头的已配置可执行文件仍保持 package 相对路径：ATT 会在启动前把第一个 argv 值相对于包根解析。裸可执行文件名仍使用 `PATH`。其它相对路径会被工具从 Case 输出目录解释。这使得相对工具产物成为 Case 输出的一部分，而不要求每个动作都构造绝对路径。

ATT 会注入并持有这些本地进程环境变量：

| 变量 | 契约 |
|---|---|
| `ATT_ROOT_DIR` | 规范化绝对 ATT 包根目录 |
| `ATT_CASE_OUTPUT_DIR` | 规范化绝对当前 Case 输出目录；在进程启动时与 `${EXEC.OUTPUT_DIR}` 相同 |

具有这些名称的继承值会被替换。POSIX 脚本可使用 `$ATT_CASE_OUTPUT_DIR`；Windows batch 脚本使用 `%ATT_CASE_OUTPUT_DIR%`。

命令文本的处理规则为：

| 命令文本 | 效果 |
|---|---|
| 引号外的空白 | 分隔 argv 元素 |
| `'...'` 或 `"..."` | 将固定文本分组为一个 argv 元素；引号被移除 |
| 单引号外的反斜杠 | 为 ATT 分词转义下一个字符 |
| `|`, `>`, `<`, `;`, `&`, `$`, `(`, `)`, `*`, `?` | 字面字符，不是 shell 操作符 |
| 未配对引号或末尾转义 | 报错 |

这份配置：

```yaml
command: "./tools/send.sh '${input.requestFile}' --label 'Payment regression'"
```

会产生这些逻辑参数：

```text
./tools/send.sh
<request-file-value>
--label
Payment regression
```

文本 `status|PENDING` 仍然是一个字面参数，`>result` 不会重定向输出。静态模板组模板文本中的引号只是文本内容的一部分，并不是解析后值的转义要求。

更推荐等价写法为：

```yaml
command:
  - ./tools/send.sh
  - "${input.requestFile}"
  - --label
  - Payment regression
```

参数可以定义可选的原子 `argName` 标记。其占位符决定插入位置，且在 `argName` 非空时必须占据一个完整命令 token：

```yaml
command:
  - ./tools/send.sh
  - "${input.requestFile}"
  - "${input.reference}"
arguments:
  requestFile: {name: Request File, description: Input file, required: true}
  reference: {name: Reference, description: Optional reference, required: false, argName: --reference}
```

当 `reference='REF 123'` 时，逻辑 argv 的最后一部分为 `--reference`、`REF 123`；该值仍是一个原子参数。如果可选值缺失或归一化为空白，则这两个 token 都不会输出。省略 `argName` 或将 `argName: ''` 设为空，会使该参数变成位置参数：一个完整 token 占位符只输出其值，或在可选值为空白时输出空。嵌入式占位符如 `--reference=${reference}` 仍然是一个普通渲染 token，不能接收 List。对于 typed List，`argNameMode` 控制名称是 `once`（默认值，出现在整个列表前）还是 `repeat`（每个值前都重复）；对位置参数没有输出影响。

优先使用 canonical 声明参数占位符 `${input.keywords}`。`${TOOL.input.keywords}` 是 legacy 兼容 alias，`${keywords}` 是 deprecated shorthand；两者只在唯一对应已声明参数时兼容，并产生 `CONTEXT_TOOL_INPUT_SHORTHAND`。工具会把原始结果写到 stdout，把诊断写到 stderr；ATT 会在 Case 日志中记录输入/标准输出/标准错误。

全局工具命令只能引用其声明参数，不能引用 `${EXEC.INPUT...}`、`${EXEC.ACTIONS...}` 或其它运行时 Context 作用域。需要把运行时数据显式传递到动作调用中，然后再在命令中引用对应声明参数。这使全局工具保持独立，并使依赖可静态校验。

##### SSH 执行

全局工具和每个工具组都可以在其自身根配置中定义一个执行目标：

```yaml
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519
```

根 `ssh` 仅适用于内联全局工具；工具组仅使用其自身 `ssh`，不会继承根配置。host/user 是必需的，port 默认为 22，key 是可选的。密码字段不受支持。ATT 优先使用本地 OpenSSH，带 `BatchMode=yes` 和 `StrictHostKeyChecking=yes`。如果 `PATH` 中不存在可执行 `ssh`，ATT 会警告将使用捆绑的 mwiede/jsch Java 库。后者使用严格的 `~/.ssh/known_hosts`，不会继承 OpenSSH agent 或 `~/.ssh/config`，通常需要 `identityFile`。两种传输都会把逻辑 argv 安全地单引号封装为一个 POSIX 远程命令字符串。远程连接性和可执行文件存在性无法通过包验证证明。SSH 的 stdout/stderr/status/timeout/retry/assert/saveAs 行为与本地工具一致，证据中会记录 `transport: openssh|mwiede/jsch`。

Case 输出工作目录以及两个环境变量规则仅适用于本地工具进程。ATT 不会为 SSH 远程命令预先 `cd`，也不会注入本地文件系统路径，因为 `${EXEC.OUTPUT_DIR}` 和包根在远端没有定义映射。远程进程会使用 SSH 账户的默认目录。需要共享文件系统或远端目录时，须通过显式声明的工具参数传递。

##### 输入、输出、超时和状态

可用命令占位符为：

| 占位符 | 含义 |
|---|---|
| `${argument}` | deprecated shorthand；唯一匹配已声明参数时兼容并产生 warning |
| `${input.argument}` | 显式命名空间引用同一已声明参数 |
| `${TOOL.input.argument}` | legacy 兼容别名；`att validate` 会产生 `CONTEXT_TOOL_INPUT_SHORTHAND` |

ATT 按工具配置的 `output: txt|yaml|json|xml` 解析 stdout，默认 `txt`。V2.6 Tool Action 的 object `saveAs` 可保存精确 raw stdout，或把 typed `${output.result}` 编码为 text/json/yaml/xml；路径写入当前 Case artifact 目录。retry 共用一个渲染后的路径，最终保留最后一次可写 attempt 的内容。不使用 `saveAs` 时，ATT 不创建额外命名 artifact，但输入、argv、stdout、stderr、解析结果、退出码和 retry evidence 仍保存在 Case evidence 中。

超时、启动/进程 I/O 失败或结构化输出解析失败是 ERROR，不能被断言覆盖。否则工具动作的 `assert` 决定 PASS/FAIL。如果未配置断言，操作完成即算 PASS，即使进程退出码非零。退出码仍保留在 `action.output.exitCode` 中，因此要求零退出码的模板需显式写明。命令、输入、stdout、stderr、原始输出、持续时间、退出码、解析后的 `output.result` 和断言详情都会保留为证据。

超时优先级为：

```text
工具动作 timeoutMs → Tool descriptor timeoutMs → 全局 timeoutMs → 10000 ms
```

所有配置的超时都是 1 到 3600000 毫秒的整数。

retry 只属于 `type: tool` Action，不存在全局、Tool、Template、stage 或 sidecar 默认：

```yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [ASSERTION, TIMEOUT]
```

`maxAttempts` 包含首次 attempt，范围 2–10；`intervalMs` 范围 0–3600000。`ASSERTION` 要求同一 Tool Action 配置 `assert`，每次正常返回后立即判断，false 才重试；`TIMEOUT` 独立控制单次超时是否重试。exit code 只作为 `${output.exitCode}` 证据，由 assert 判断。配置、参数、I/O、解析、非 timeout DB 错误及 assertion 求值错误均不重试。

##### 调用后的 Evidence Collector

Tool Action 可声明以 collector ID 为 key 的 `evidence` map。每个 collector 必须有 `call`，并可配置独立 `timeoutMs` 及 `onFailure: continue|stop`：

```yaml
invokeApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.INPUT.requestFile})}"
  evidence:
    queueState:
      call: "#{readQueueState(queue=${EXEC.INPUT.queue})}"
      timeoutMs: 3000
      onFailure: continue
  assert: "${output.result.status} == 'SUCCESS'"
```

ATT 先完成主要 Tool attempt，再按声明顺序运行 collectors，最后求值主要 assertion。Collector 期间 `${output.result}` 仍是主要结果；结果写入 `output.attempts[n].evidence.<collectorId>`，不会替换主要结果。主要 assertion retry 会重新执行主要 call 和所有 collectors；`continue` 只记录 collector 错误，`stop` 会让 Action ERROR 并跳过 assertion。Collector 可调用 built-in、配置 Tool 或只读 call-backed Tool façade；直接 DB/MQ helper call 仍限于主要 Action。

#### 3.4 运行测试

##### 先校验

```sh
./att.sh validate --package
```

包模式是默认模式，会检查全局配置、所有发现的工作簿/侧车、配置的 sheets 和行、所有模板（包括未被引用的模板）、表达式、工具、路径和包完整性。它不会调用外部工具，是发布前的必需门槛。

```sh
./att.sh validate --selected --case payment.payment.TC001
```

选中模式只检查显式选中的 Case 及其依赖闭包。它适合快速反馈，可报告未选中内容未被检查。`run` 总是会对其不可变执行计划执行选中范围校验。

##### 选择 Case

| 选项 | 含义 |
|---|---|
| `--all` | 递归发现 `testcase.root` 下所有工作簿/侧车对 |
| `--suite <xlsx>` | 选择一个工作簿；可重复 |
| `--suite-dir <dir>` | 在另一个目录下发现工作簿 |
| `--case <workbookId.groupId.rowCaseId>` | 选择一个完整 Case ID |
| `--tag <tag>` | 包含任何指定标签的 Case |
| `--exclude-tag <tag>` | 在包含过滤之后排除匹配标签 |

空选择是错误。`--rerun-failed` 本身也是一个有效选择，会从最新完成的持久化 run 中读取 FAIL/ERROR Case ID。额外的 `--case`、`--tag`、`--exclude-tag` 过滤器会进一步收缩该集合。缺失历史、先前无 FAIL/ERROR Case、或保存的 ID 无当前可发现 Case 匹配，都会导致命令错误。当前工作簿、侧车、testcase XML 快照、模板和工具定义都会被校验和执行；ATT 不会重放旧 run 的输入。

##### 执行

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --tag smoke
./att.sh run --all --case payment.payment.TC001 --run-id SIT-001
./att.sh run --rerun-failed
./att.sh run --rerun-failed --tag payment
./att.sh run --all --dry-run
./att.sh run --suite testcase/payment.xlsx --update-snapshot
```

`--dry-run` 会校验/规划并把所选 Case 记录为 SKIPPED，而不调用工具。`--fail-fast` 会在第一次 FAIL 或 ERROR 后停止调度后续 Case。`--output-dir` 会为一次命令覆盖输出根目录。

`--update-snapshot` 仅对 `run` 有效。它会在验证和输出目录创建前，显式创建或原子替换所选工作簿的已更改规范 XML 快照；字节内容完全相同的文件保留原有字节和修改时间。工作簿准备完成后再替换任何选中的 XML。快照符号链接会被拒绝。后续单文件 I/O 失败会报告先前已完成的更新，并且相同包的并发快照生成/更新阶段会被串行化。结合 `--format json` 时，成功更新通知会写入 stderr，从而保持 stdout 为单个 JSON 文档；`--quiet` 会抑制它们。与 `--dry-run` 组合时，仍然允许 XML 更新，但测试用例工具保持禁用。

人类可读 `run` 默认显示 run/suite/Case/stage/action 生命周期进度，并把每个完整 Case 日志块镜像到控制台，包括模板/工具输入、逻辑和执行 argv、stdout、stderr、负载和动作证据。`--verbose` 仍然接受，但只是兼容性选项；`--quiet` 会抑制这个默认输出，明确同时指定 `--verbose --quiet` 仍然无效。非 quiet 输出可能包含秘密或个人数据，只应在合适受保护的终端中使用。

##### 结果与退出码

| 最高结果 | 运行状态 | 退出码 |
|---|---|---:|
| ERROR | ERROR | 3 |
| 无 ERROR 时为 INVALID | INVALID | 2 |
| 无 ERROR/INVALID 时为 FAIL | FAIL | 1 |
| 至少一个 PASS 且仅有 PASS/SKIPPED | PASS | 0 |
| 全部选中 Case 都是 SKIPPED | SKIPPED | 0 |

断言为假是 FAIL。表达式求值、进程、超时、解析、I/O、配置和校验失败是 ERROR 或 INVALID，取决于阶段。包含 FAIL 和 ERROR 的 run 将返回 3。

#### 3.5 报告

已完成的 run 会发布到 `<outputDirectory>/<RunID>/`：

```text
output/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/
│   ├── summary.json
│   └── junit.xml
├── report/
│   ├── index.html
│   └── junit.html
└── <workbookId>.<groupId>.<rowCaseId>/
    ├── case.yaml
    ├── <case>.log
    └── <stage>/<action>/
```

直接从磁盘打开 `report/index.html` 即可。组按 `workbookId.groupId` 聚合；界面将 `groupId` 标记为 Sheet，因为它映射到一个物理工作簿 sheet。Case 页面中，结合 Workbook、Sheet、Status 下拉框，以及对 workbook ID、group ID、完整 Case ID 和标签的全文搜索。点击任何 Cases 表头可切换升序/降序；Duration 按数值排序。

展开的 Case 包含完整 Case ID、名称、状态、持续时间、Expected 和 Actual 结果、每条记录动作结果的一行、详细执行日志，以及 `.log` 与 `case.yaml` 的显式链接。Action Results 的每行独立显示 Stage、Action、Description、Status 与 Message；Description 是动作完成后的最终渲染值，并写入 `run.yaml` 与 CI JSON。HTML 不会内联完整的持久化 Stage/Template/Action/Tool 树；需要结构化最终运行时状态时，请打开 `case.yaml`。为兼容既有报表，Expected 仍按动作顺序追加 assert 的非空最终 description 与 `expected`；Actual 会按动作顺序追加每个非空运行时 `actual`。

`--ci-output junit,json` 会生成 JUnit XML、JUnit HTML 和 JSON 汇总：

- `<run>/ci/junit.xml`
- `<run>/report/junit.html`
- `<run>/ci/summary.json`

JUnit HTML 是与 JUnit XML 相同已完成汇总的人类可读投影，不是第二次聚合。

可从持久化 run 证据重新生成报告：

```sh
./att.sh report --run-id SIT-001
```

### 04 Cookbook

本章从你想要完成的任务出发，展示对应的 ATT 模式。

#### 在失败后执行回滚，并每次都清理

```yaml
stages:
  - {key: invoke, template: 執行模板, required: true}
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

回滚只在先前失败后运行。清理无论先前结果如何都会运行。回滚或清理失败仍会保留在最终聚合中。

#### 覆盖结果工作簿列标签

```yaml
report:
  columns:
    result: 測試結果
    durationMs: 耗時毫秒
    expectedResult: 預期結果
    actualResult: 實際結果
    caseLog: 案例日誌
    reportLink: 詳細報告
    runTime: 執行時間
```

这只会修改复制出来的工作簿标签，不会重命名 Context 键、HTML 字段或 CI schema 属性。

#### 解析 JSON 工具输出

配置 `output: json` 后，使用普通 map/list 导航即可：

```yaml
assertStatus:
  type: assert
  description: API status is successful
  assert: "${EXEC.ACTIONS.callApi.output.result.status} == 'SUCCESS'"
  expected: SUCCESS
  actual: "${EXEC.ACTIONS.callApi.output.result.status}"
```

JSON 重复对象键、格式错误，或声明为 JSON 但无法解析的输出，都会在进程退出码为 0 时仍然报 ERROR。

#### 访问 XML 属性、文本和重复元素

ATT 会把 XML 转换为稳定的 map/list 树：

```xml
<Response requestId="R-100">
  <Status code="00">SUCCESS</Status>
  <Messages>
    <Message severity="INFO">accepted</Message>
    <Message severity="WARN">review later</Message>
  </Messages>
</Response>
```

```yaml
name: Response
attributes:
  requestId: R-100
Status:
  attributes: {code: "00"}
  text: SUCCESS
Messages:
  Message:
    - attributes: {severity: INFO}
      text: accepted
    - attributes: {severity: WARN}
      text: review later
```

只有重复兄弟节点才会变成数组，因此索引只对重复节点有意义。

#### 把列表作为单独进程参数传递

V2.6 调用直接传入 typed YAML array。下面示例在没有分隔符配置的情况下展示两种 `argNameMode`：

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords and log levels
    command:
      - ./tools/grep_from_app_logs.sh
      - "${logFile}"
      - "${input.keywords}"
      - "${input.levels}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Ordered keyword array, required: true, argName: --keyword, argNameMode: repeat}
      levels: {name: Levels, description: Ordered level array, required: false, argName: --levels, argNameMode: once}
```

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${EXEC.ACTIONS.getLogs.output.targetFiles[0]}, keywords=['PAYMENT', 'POSTED'], levels=['ERROR', 'WARN'])}"
```

结果尾部逻辑 argv 为 `--keyword`、`PAYMENT`、`--keyword`、`POSTED`、`--levels`、`ERROR`、`WARN`。`repeat` 会对每个关键词重复 `--keyword`；`once` 是默认值，可以省略，它只在整个列表前发出一次 `--levels`。每个 typed List 都在各自的完整命令占位符位置独立扩展。V2.6 schema 拒绝 `delimit`；旧 schema 只保留历史读取兼容性。

##### Linux Bash 解析示例

本节通过 Bash 示例说明 `argNameMode: repeat` 与 `once` 的行为。省略。

#### 重试 assertion 轮询或 timeout

重试属于 tool 动作，而不是工作流或任意 stage：

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
  timeoutMs: 30000
  assert: "${output.result.status} == 'COMPLETED'"
  retry:
    maxAttempts: 3
    intervalMs: 1000
    retryOn: [ASSERTION, TIMEOUT]
```

`maxAttempts` 包含第一次尝试。ATT 每次取得正常结果后立即执行 Tool Action assertion；`ASSERTION` 重试 false，`TIMEOUT` 则独立允许超时重试。已移除的 `EXIT_CODE`／`exitCodes` 在 2.6.2 中属于 validation error。

每次尝试都直接记录在 Case 日志和动作记录中；不会创建 `attempt-001` 目录。后续成功尝试会使动作 PASS，同时保留先前证据；耗尽重试次数后则产出 ERROR。只有安全可重复的操作才应使用重试。
