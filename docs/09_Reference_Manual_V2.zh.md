# ATT V2.6.0 中文用户手册与参考手册

作者：Jeffrey + ChatGPT
版本：2.6.0
状态：规范性终端用户文档

本手册设计为两种阅读方式：

- 如果你是第一次使用 ATT，请按章节 1–4 顺序阅读。它会从第一个测试用例带你走到实用的开发模式。
- 如果你已经在使用 ATT，那么章节 5–9 是你日常使用时最常用的命令、配置、表达式、报表和排障参考。
- 第 10 章向维护者与需要诊断生命周期或集成问题的用户解释内部行为。

如果旧版 V2.0 的示例与本手册冲突，则以本手册为准。

## 目录

01. [简介](#01-简介)
02. [快速开始](#02-快速开始)
03. [用户指南](#03-用户指南)
   - [3.1 工作簿](#31-工作簿)
   - [3.2 模板](#32-模板)
   - [3.3 工具](#33-工具)
   - [3.4 运行测试](#34-运行测试)
   - [3.5 报告](#35-报告)
04. [Cookbook](#04-cookbook)
05. [CLI 参考](#05-cli-参考)
06. [配置参考](#06-配置参考)
07. [表达式参考](#07-表达式参考)
08. [报表参考](#08-报表参考)
09. [故障排查](#09-故障排查)
10. [维护者架构](#10-维护者架构)

## 01 简介

ATT 是一个离线的、基于模板驱动的 API 测试执行器。测试数据保存在 Excel 中，可复用的执行逻辑保存在模板目录中，外部能力则注册为工具。

```text
工作簿行 → 测试用例 → 有序阶段
阶段 → 模板选择列 → 当前行的选择器单元格 → 模板
模板 → 有序动作 → 配置好的工具（对于工具动作）
```

你首先需要掌握的四个概念是：

| 概念 | 其负责的内容 |
|---|---|
| 测试用例 | 一行工作簿数据、用例级数据、标签和有序阶段 |
| 阶段 | 模板选择、阶段私有数据、执行条件和失败处理 |
| 模板 | 一个可复用的有序动作列表 |
| 工具 | 一个具有命名输入的能力；可启动外部命令，也可通过 V2.6 `call` 包装 typed DB 操作或纯 built-in |
| Dbhelper | 一个独立配置的 JDBC 连接、SQL timeout、transaction、结果限制与 evidence 策略 |

一个动作可以渲染负载、调用工具、断言表达式、写入结构化日志，或分配 Case 作用域的运行时变量。ATT 会在执行外部工具前校验所选包，并将结果证据记录到一个已完成的运行目录下。

### V2.6 的保证

- 配置是严格的。未知字段、错误类型、无效枚举值、重复 YAML 键，以及无效动作形状都是错误。
- dbhelper 使用独立 `att-dbhelper/v2.5` 文件，并通过一級 `type: db` Action 或只读 `#{db.<instance>...}` 表达式调用；它不是 Tool 的特殊配置。
- `CASE.DB` 是固定、区分大小写、由框架拥有的 Case 交易收尾节点，不能由 Excel 或侧车数据覆盖。
- 每个工作簿都有一个同名的 YAML 侧车文件和生成的语义 XML 快照。
- 每个模板都是包含 `template.yaml` 的目录。
- `validate --package` 会检查整个包；`validate --selected` 只检查所选依赖闭包。
- Run ID 和 Case ID 会先被校验，然后直接用作输出目录名。
- 只有在运行完成后，最终运行目录才会发布。
- FAIL、ERROR、INVALID、SKIPPED、PASS 具有稳定的聚合与退出码含义。
- JSON、XML、JUnit XML、JUnit HTML 和 CI JSON 输出都有版本化契约。

### 包布局

```text
att-package/
├── att.sh
├── att.bat
├── config/config.yaml
├── config/dbhelpers/
│   └── orders.yaml
├── config/tools/
│   └── orders-db.yaml
├── testcase/
│   ├── payment.xlsx
│   ├── payment.yaml
│   └── payment.xml
├── templates/
│   └── payment/
│       ├── template.yaml
│       └── request.tmp.json
├── tools/
├── lib/                  # 用户提供的 JDBC driver 与其依赖
├── schemas/
└── output/
```

你通常会编辑 `config/config.yaml`、工作簿、侧车、模板、负载和工具脚本。同名 testcase XML 通常由 `snapshot` 生成，并作为源码控制证据进行审查；仅在运行时使用的 `--update-snapshot` 是显式的刷新工作流。ATT 拥有配置输出目录及其文档化构建位置下的生成内容。

全局 `testcase.root` 设置默认为 `testcase`。发现是递归进行的，且相邻的同名 XLSX/YAML/XML 三元组定义一个 testcase 集。

## 02 快速开始

这个示例会创建一个支付测试：渲染 JSON、调用工具并验证返回状态。

### 第 1 步：配置 ATT

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
    command: ["./tools/invoke_payment_api.sh", "${requestFile}", "${environment}"]
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

argv 列表形式的 `command` 会保留每一项为一个独立进程参数。旧式的标量命令会被 token 化一次并进入相同的内部列表。已声明的参数可直接引用，例如 `${requestFile}`。

### 第 2 步：创建工作簿与侧车

创建 `testcase/payment.xlsx`，包含一行表头：

| Case ID | Tags | Amount | Expected Status | Invoke Template |
|---|---|---:|---|---|
| TC001 | smoke,payment | 100 | SUCCESS | PAYMENT_INVOKE |

创建相邻的 `testcase/payment.yaml`：

```yaml
schemaVersion: att-sidecar/v2.1
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

### 第 3 步：创建模板

创建 `templates/payment/template.yaml`：

```yaml
schemaVersion: att-template/v2.5
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    renderAs: file
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]}, environment=${CASE.environment})}"
  assertStatus:
    type: assert
    description: Payment API status matches the expected status
    assert: "${ACTIONS.callApi.output.result.status} == ${CASE.expectedStatus}"
    expected: "${CASE.expectedStatus}"
    actual: "${ACTIONS.callApi.output.result.status}"
```

创建 `templates/payment/request.tmp.json`：

```json
{
  "caseId": "${CASE.caseId}",
  "amount": "${CASE.amount}"
}
```

### 第 4 步：创建模拟工具

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

### 第 5 步：校验并运行

```sh
./att.sh validate --package
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001 --run-id SIT-001 --ci-output junit,json
```

在 Windows 上，用 `att.bat` 替换 `./att.sh`；命令名、选项、输出和退出码是相同的。这个快速开始示例是 POSIX shell 示例，因此 Windows 包需要在运行前配置等效的 `.bat`、`.cmd`、PowerShell 或原生可执行文件。

在开发阶段，使用选中范围校验会更快：

```sh
./att.sh validate --selected --suite testcase/payment.xlsx --case payment.payment.TC001
```

### 第 6 步：查看结果

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

## 03 用户指南

本章说明正常日常工作流中的数据流转顺序。

### 3.1 工作簿

#### 工作簿、侧车和快照之间的关系

每个 `.xlsx` 工作簿都要求有一个 YAML 侧车和一个生成的 XML 快照，它们必须具有相同的基名且位于同一目录：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

侧车将 Excel 结构映射为 ATT 概念。它负责 sheet 映射、表头、用例数据、有序阶段、可选报告列标签，以及可选的工作簿超时。

```yaml
schemaVersion: att-sidecar/v2.1
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

### 3.2 模板

只有当目录直接包含 `template.yaml` 时，它才是可调用模板。类别目录可以包含其他模板目录，但自身不是可调用模板。

```yaml
schemaVersion: att-template/v2.5
name: PAYMENT_INVOKE
description: Render and invoke a payment request
actions:
  buildReference:
    type: assign
    name: paymentReference
    expression: "PAY-#{sysdate('yyyyMMdd')}-#{sample.getSeq(10)}"
  renderRequest:
    type: render
    description: "Render request for ${CASE.caseId}; status=${output.status}"
    payload: requests/*.xml
    renderAs: file
    assert: "${output.targetFiles[0]} != null"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]})}"
    saveAs:
      path: "${CASE.caseId}-response.json"
      format: json
      overwrite: false
    assert: "${output.result.status} == 'SUCCESS'"
  recordResult:
    type: log
    level: INFO
    message: "Payment ${CASE.caseId} completed"
    file: "${ACTIONS.callApi.output.targetFiles[0]}"
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
| `assign` | 求值文本并发布 Case 级变量 | `type`、`name`、`expression` | `${CASE.VARS.<name>}`、`output.name`、`output.result` |

动作按 YAML 顺序执行。动作 ID 在模板内唯一，且不能包含点号。每个动作都可以定义 `description` 和 `onFailure: stop|continue`。

动作校验按类型进行。render 动作要求安全且非空的 payload glob，以及 `renderAs: file|text|json|yaml|xml`；它不能包含 Tool、assert-action、log 或 DB 字段。重试和 Action 级 timeout 仅对 Tool 动作有效。Tool 与 DB Action 可使用共同的 object-shaped `saveAs`，其他类型不可使用。DB Action 必须指定已配置的 `db` ID，并在 `query` 与 `update` 中恰好选择一个；所选 block 又必须在 `sql` 与 `sqlFile` 中恰好选择一个。assert 动作要求 `assert`，并可包含 `expected` 和 `actual`；`expression`、`acture`、`actural` 都是非法字段。log 动作要求 `message`、`file` 或两者；并可使用 `level` 和 `fields`。assign 动作要求 `name` 和 `expression`。不支持的字段会报错，而不是被忽略。

每个动作都可以使用 `assert`，但 assert 动作本身把它作为必需主表达式。每个动作结果都嵌套在 `output` 下，包括 `status`、`success`、`durationMs`、`exception`、`targetFiles`、`result`，以及可选断言详情。操作错误保持 ERROR；否则显式断言决定 PASS/FAIL。一个已完成的工具进程即使返回非零退出码，也不会自动变成 ERROR：需要在 `assert` 中检查 `output.exitCode`。

每个动作都支持表达式型 `description`。验证时会检查 `${...}` 引用和 `#{...}` 调用而不执行它们，尽量解析可知的静态 Case 值，并保留运行时相关引用。执行成功后，ATT 会在当前动作局部 `${output...}` 作用域下对两种表达式形式进行求值，然后再持久化最终 description。

assign 动作使用常规 Context、内建函数、配置 Tool 与只读 DB expression 语法求值 `expression`。其 `name` 必须符合 `[A-Za-z_][A-Za-z0-9_]*`，大小写敏感，并且在当前 Case 的 `CASE.VARS` 下不能已存在。`CASE.VARS` 会在每个 Test Case 中创建一次，跨阶段和模板保持存在，并将运行时赋值与 Excel 及框架自有 Case 字段区分开。完整的类型化表达式（例如 `#{db.orders.query(...)}`）保留 Java object，不会转成字符串。成功赋值后，后续动作和阶段可通过 `${CASE.VARS.<name>}` 读取；同一值也保存在 `${ACTIONS.<assignActionId>.output.result}`。Assign 支持可选 `description`、`assert` 和 `onFailure`，但不支持 render、tool-action、log、report-only、retry、timeout 或 `saveAs`。断言 FAIL/ERROR 不会回滚已成功求值的变量；表达式失败则不会创建变量。

Render 负载路径必须保持在模板根目录下。glob 匹配会取模板相对路径排序后的普通非符号链接文件。`renderAs: file` 会把渲染结果写入 Case 输出目录中对应的相对路径；冲突会报 ERROR。其他渲染模式不会写文件，而是把一个类型化值，或多个匹配项对应的“相对路径→值”有序映射，写入 `output.result`。

log 动作可以输出渲染后的 `message`、一个 `file` 的完整内容，或两者同时输出：

```yaml
logResponse:
  type: log
  level: DEBUG
  message: "API response for ${CASE.caseId}:"
  file: "${ACTIONS.callApi.output.targetFiles[0]}"
  fields:
    action: callApi
```

`message` 与 `file` 都支持统一的 `${...}` / `#{...}` 表达式引擎，并在 log 动作发布自身输出前进行求值。相对 `file` 路径会解析到 `${CASE.outputDirectory}` 以下；绝对路径仅在其解析后的真实路径仍位于该目录下时才接受。源必须是存在的、普通非符号链接、UTF-8 文件。路径或符号链接逃逸、恶意 UTF-8、空白解析路径，以及尝试读取当前 Case 日志，都会报 ERROR。

`output.sourceFile` 记录规范化后的源路径。`output.result` 包含文件内容一次，CRLF/CR 会被归一化成 LF；当两种输入同时存在时，它包含消息、一个 LF，然后是文件内容。这个相同结果也只会在人工 Case 日志中输出一次，而不会把文件内容复制到多个证据字段。

### 3.3 工具

工具是一个外部能力，可以在全局 `config.yaml` 中配置，也可以位于独立的工具组文件中，并由模板动作通过命名参数调用。

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered request
    command: ["./tools/invoke_payment_api.sh", "${requestFile}", "${environment}"]
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
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]}, environment=${CASE.environment})}"
```

未知、重复或缺失必需参数都会在验证时失败。参数元数据用于记录和验证契约，不会自动注入值。如果且仅如果工具恰好声明一个参数，调用时可以省略参数名：`#{getAppLogs(${CASE.caseId})}` 等价于 `#{getAppLogs(caseId=${CASE.caseId})}`。零参数工具仍然使用 `#{tool()}`；多参数工具则拒绝位置参数。

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

调用方式为 `#{database.selectPayment(caseId=${CASE.caseId})}`。在 `script` 存在时，逻辑 argv 为 `/opt/att/database-tools selectPayment select-payment --case <caseId>`：脚本 argv、无前缀 tool key、然后是 tool command argv。没有 `script` 时，工具命令从可执行文件开始。持久化的分组证据可在 `TOOL.database.selectPayment` 下访问。

#### 数据库助手（DB helper）

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
  parameters: masked
```

`connection.url` 必填；username、password 和 properties 中的值可写成完整的 `${ENV:NAME}` 环境变量引用。`driverClass` 可选，优先使用 JDBC service discovery。`readOnly` 是 ATT 的 update 拒绝边界，也会传给 JDBC Connection；它不能防止 vendor side effect。`isolation` 可为 `driverDefault`、`readUncommitted`、`readCommitted`、`repeatableRead` 或 `serializable`。

`statement.timeoutSeconds` 是实例级 SQL timeout，默认 30，范围 1–3600。ATT 对该实例创建的每个 `PreparedStatement` 调用 `setQueryTimeout`；DB Action 不接受 Action 级 `timeoutMs`。

交易设置组合如下：

| `scope` | `onEnd` | 行为 |
|---|---|---|
| `statement` | `commit` | JDBC auto-commit |
| `statement` | `rollback` | 每次 DB 操作后 rollback |
| `case` | `commit` | Case 完成时 commit 每个已使用实例 |
| `case` | `rollback` | Case 完成时 rollback 每个已使用实例 |

默认值为 `scope: case`、`onEnd: rollback`。

##### DB Action

查询使用 `query` block，不另设沉重的 `operation` 字段：

```yaml
queryOrder:
  type: db
  db: orders
  query:
    sql: >-
      select id, status
      from orders
      where customer_id = ? and status = ?
    params:
      - "${CASE.customerId}"
      - OPEN
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
    params: [CLOSED, "${CASE.orderId}"]
  assert: "${output.result.affectedRows} == 1"
```

`query` 与 `update` 必须且只能出现一个。block 内的 `sql` 与 `sqlFile` 也必须且只能出现一个。`params` 可省略（默认为空 list），也可使用 YAML list，或一个精确 Context 引用，其类型化值必须是 Java `List`。ATT 按顺序调用 `PreparedStatement.setObject`，不会把值转成字符串后重新解析。V2.5 不支持 named parameters、batch、generated keys、callable statements 或多个 JDBC result。

`type: db` 不支持 `retry` 或 Action 级 `timeoutMs`。自动重试 update 并不安全。DB 执行成功后可使用普通 `description`、`assert` 和 `onFailure`；执行失败时不运行断言，状态保持 ERROR。

DB 的 `saveAs` 使用共同 object shape，但保存的是类型化 result，而非 stdout。`path` 与 `format: json|yaml|xml` 必填，`overwrite` 默认 false。路径必须保持在 Case artifact 目录内。序列化或写文件失败也是 ERROR。

##### 渲染 SQL 与 SQL 文件

inline SQL 在 JDBC prepare 前使用当前 Context 渲染。前置 render Action 也可提供完整 SQL：

```yaml
query:
  sql: "${ACTIONS.renderSql.output.result}"
  params: "${CASE.queryParams}"
```

也可引用静态、包内 UTF-8 文件：

```yaml
query:
  sqlFile: sql/orders/find-by-customer.sql
  params: ["${CASE.customerId}", OPEN]
```

validate 会确认 `sqlFile` 是包内普通非符号链接文件。ATT 对文件内容使用与 inline SQL 相同的渲染规则，并记录 source path 与 SQL hash／全文；路径本身不渲染。SQL source 只允许 Context 与 pure built-in，不允许配置 Tool 或巢状 DB 调用。外部准备应在前一 Action 完成，再引用其结果。

渲染只适合 SQL 结构；测试数据应留在 `params` 并使用 JDBC `?`。ATT 不解析、分类、改写 SQL，也不会把参数插入 vendor SQL 字串。

##### 表达式中的 DB 查询

只读查询可在所有 Case-runtime expression 位置调用：

```yaml
assert: >-
  #{db.orders.scalar(
      sql='select count(*) from orders where customer_id = ?',
      params=${CASE.customerParams}
  )} == 1
```

多参数可按 JDBC placeholder 次序 inline 编写：

```yaml
#{db.orders.query(
    sql='select id from orders where customer_id = ? and status = ? and amount >= ?',
    params=[${CASE.customerId}, 'OPEN', ${CASE.minimumAmount}]
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
        params=${CASE.customerParams}
    )}
```

后续可读取 `${CASE.VARS.customerOrders.rows[0].STATUS}`。非 scalar 的 query object 不能插入周围文字；请用 assign 或 DB Action。expression update、DDL、callable statement 与 generic execute 一律拒绝。查询失败会使所在 Action 与 Case 成为 ERROR；inline evidence 保存在 `ACTIONS.<action>.DB.<instance>.<callId>`。

##### 结果与连接生命周期

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

失败仍保留相同顶层形状：`success: false`、空 rows、零 rowCount、null affectedRows，以及经净化的 `error`（`type`、`message`、`sqlState`、`vendorCode`）。类型包括 `CONNECTION_ERROR`、`BIND_ERROR`、`SQL_ERROR`、`TIMEOUT`、`LIMIT_EXCEEDED`、`ROLLBACK_ONLY` 与 `FINALIZE_ERROR`。

Connection 的生命周期只与 dbhelper 实例和执行 thread 有关，与 Case 无关。ATT 为每个实例/thread 重用一个 Connection。Case 完成时对该 Case 已使用实例执行配置的 commit/rollback，但不关闭 Connection；thread 结束或 run 结束才关闭。

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

ATT 在 Case 初始化时创建空 `CASE.DB`，所有 Action 完成并执行交易收尾后，才为已使用实例加入 entry。因此同一 Case 的 Action 不能依赖 `${CASE.DB.orders.state}` 作执行决策。单次操作结果请读 `${ACTIONS.<actionId>.output.result}`；`CASE.DB` 用于持久化 Context、Case log、报告或其他 post-Case 处理。

ATT 不内置特定 JDBC driver。将 driver 与全部依赖放入包根 `lib/`，更改后重启 ATT。默认使用 JDBC service discovery；旧式 driver 可配置 `connection.driverClass`。V2.5 使用 flat shared classpath，不支持动态 reload 或 driver 依赖隔离。

#### Call-backed Tool（V2.6）

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
    call: "#{db.orders.query(sql='select order_id, status from orders where customer_id = ? and status = ?', params=[input.customerId, input.status])}"
    cache:
      scope: case
    arguments:
      customerId: {name: Customer ID, description: 要查询的客户, required: true}
      status: {name: Status, description: 精确订单状态, required: true}

  count:
    name: Count orders
    description: 返回一个 typed count
    call: "#{db.orders.scalar(sql='select count(*) from orders where customer_id = ?', params=[input.customerId])}"
    cache:
      scope: db
    arguments:
      customerId: {name: Customer ID, description: 要统计的客户, required: true}

  findByDate:
    name: Find orders by date
    description: 使用 package 内的 rendered SQL file
    call: "#{db.orders.query(sqlFile='sql/orders-by-date.sql', params=[input.customerId, input.fromDate])}"
    arguments:
      customerId: {name: Customer ID, description: 要查询的客户, required: true}
      fromDate: {name: From date, description: 包含此日期起的订单, required: true}

  updateStatus:
    name: Update order status
    description: 更新一个订单
    call: "#{db.orders.update(sql='update orders set status = ? where order_id = ?', params=[input.status, input.orderId])}"
    arguments:
      orderId: {name: Order ID, description: 要更新的订单, required: true}
      status: {name: Status, description: 新状态, required: true}
```

Tool 定义通过 typed `input.<argument>` 读取参数；也兼容 `${input.*}` 与 `TOOL.input.*`。定义内不能读取 `CASE`、`RUN`、`ACTIONS` 或 evidence root，Case 数据必须从外层调用传入。允许 pure built-in，例如 `params=[input.customerId, #{upper(input.status)}]`。不允许调用另一 configured Tool，因此不会形成 Tool dependency cycle。

`call` 必须是一个完整 `#{...}`，目标只能是 `db.<instance>.query|scalar|update` 或一个 pure built-in。Call-backed Tool 禁止 `output`、SSH、group `script` 及参数的 `delimit|argName|argNameMode`；这些 process-only 字段会报错，不会被静默忽略。

DB façade 的 `sqlFile` 必须是静态 package-relative 路径，例如 `sqlFile='sql/orders-by-date.sql'`；不能从 input 或表达式动态产生路径。ATT 会校验该文件，并以 `tool-sql` input 将其 SHA-256 写入 `run.yaml`，执行时再渲染文件内容。因此 package provenance 保持完整，而 SQL 内容仍可读取已声明 Tool input 与 pure built-in。

##### 场景：在表达式中取得 typed query result

READ façade 可用于 `assign`、`assert`、render payload、log field、description 与普通调用参数：

```yaml
loadOrders:
  type: assign
  name: customerOrders
  expression: >-
    #{orders.find(
        customerId=${CASE.customerId},
        status='OPEN'
    )}

checkOrders:
  type: assert
  assert: "${CASE.VARS.customerOrders.rowCount} > 0"
  expected: 至少一个 OPEN order
  actual: "${CASE.VARS.customerOrders.rowCount}"
```

精确调用直接返回与 `db.orders.query` 相同的 Java object，不经过文本序列化；后续可读取 `${CASE.VARS.customerOrders.rows[0].STATUS}`。

Scalar 也可直接放入断言：

```yaml
checkOrderCount:
  type: assert
  assert: "#{orders.count(customerId=${CASE.customerId})} >= 1"
```

`scalar` 仍要求正好一行一列，否则是 ERROR。

##### 场景：Tool Action、saveAs 与 update

READ façade 可作为主要 Tool Action，并保存 typed result：

```yaml
queryOrders:
  type: tool
  call: "#{orders.find(customerId=${CASE.customerId}, status='OPEN')}"
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
  call: "#{orders.updateStatus(orderId=${CASE.orderId}, status='CLOSED')}"
  assert: "${output.result.affectedRows} == 1"
```

若把 update façade 放入 `assign`、`assert`、payload、另一个 Tool 参数或 SQL，validate 与 runtime 都会拒绝。DB 操作异常令 Action/Case 为 ERROR，不是 FAIL。

##### 场景：包装 pure built-in

```yaml
tools:
  normalizeStatus:
    name: Normalize status
    description: trim 后转大写
    call: "#{upper(#{trim(input.value)})}"
    cache:
      scope: case
    arguments:
      value: {name: Value, description: 状态文字, required: true}
```

调用方式为 `#{normalizeStatus(value=${CASE.status})}`。Pure built-in façade 可用 Case cache，但不能用 DB cache。

##### Cache scope 与 stale-read 契约

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

##### Timeout、retry、lifecycle 与 evidence

Call-backed DB Tool 继续使用目标 dbhelper 的 `statement.timeoutSeconds`、read-only、transaction、result limits、每 instance/thread 一个 Connection、Case 前 rollback 与 reconnect 规则。Action `timeoutMs` 与 process EXIT_CODE `retry` 对它无意义并会被拒绝。

Action 保留正常 `TOOL` wrapper，含 `implementation: call`、input、typed output、status、duration 与 cache evidence；DB miss 也写入 Action `DB`。`command`、`argv`、`stdout`、`stderr`、`rawOutput`、`exitCode` 等 process-only 字段不存在。

选择建议：一次性 SQL 用 `type: db`；一次性表达式读取用直接 `db.*`；稳定、重复、有业务名称或需要 cache 的操作用 call-backed Tool；需要 executable、SSH、argv、stdout parser 或 exit-code retry 时继续用 command-backed Tool。

完整规范见 [V2.6 Call-backed Tool System Design](02_System_Design_V2.6.md)。

#### 命令处理

V2.2 会把每个命令归一化为 argv 模板列表。标量命令会用旧式 token 化器处理一次。YAML 列表已经是归一化形式：每一项恰好是一个 argv 值，不再被 token 化。普通声明参数因此保持原子化，不管值中包含空格、引号、反斜杠、前导短横线还是 shell 类字符。任何带 `delimit` 的已声明参数都可能被有意扩展为零个或多个 argv 值，且一个工具中多个参数可以独立如此扩展。解析后的值不会再次被 token 化。ATT 不会调用本地 shell。

V2.3.2 会以 `${CASE.outputDirectory}` 作为当前工作目录启动每个本地工具进程。以 `./` 或 `../` 开头的已配置可执行文件仍保持 package 相对路径：ATT 会在启动前把第一个 argv 值相对于包根解析。裸可执行文件名仍使用 `PATH`。其它相对路径会被工具从 Case 输出目录解释。这使得相对工具产物成为 Case 输出的一部分，而不要求每个动作都构造绝对路径。

ATT 会注入并持有这些本地进程环境变量：

| 变量 | 契约 |
|---|---|
| `ATT_ROOT_DIR` | 规范化绝对 ATT 包根目录 |
| `ATT_CASE_OUTPUT_DIR` | 规范化绝对当前 Case 输出目录；在进程启动时与 `${CASE.outputDirectory}` 相同 |

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
command: "./tools/send.sh '${requestFile}' --label 'Payment regression'"
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
  - "${requestFile}"
  - --label
  - Payment regression
```

参数可以定义可选的原子 `argName` 标记。其占位符决定插入位置，且在 `argName` 非空时必须占据一个完整命令 token：

```yaml
command:
  - ./tools/send.sh
  - "${requestFile}"
  - "${reference}"
arguments:
  requestFile: {name: Request File, description: Input file, required: true}
  reference: {name: Reference, description: Optional reference, required: false, argName: --reference}
```

当 `reference='REF 123'` 时，逻辑 argv 的最后一部分为 `--reference`、`REF 123`；该值仍是一个原子参数。如果可选值缺失或归一化为空白，则这两个 token 都不会输出。省略 `argName` 或将 `argName: ''` 设为空，会使该参数变成位置参数：一个完整 token 占位符只输出其值，或在可选值为空白时输出空。嵌入式占位符如 `--reference=${reference}` 仍然是一个普通渲染 token，不能使用非空 `argName`。对于 delimited 值，`argNameMode` 控制名称是 `once`（向后兼容默认值，出现在整个列表前）还是 `repeat`（每个值前都重复）；对位置参数没有输出影响。

优先使用最短的声明参数占位符，如 `${keywords}`；只有在需要明确命名空间时才使用 `${input.keywords}`。两者都是大小写敏感，且必须精确匹配参数名。`${TOOL.input.keywords}` 仍然支持，但不是首选写法。工具会把原始结果写到 stdout，把诊断写到 stderr；ATT 会在 Case 日志中记录输入/标准输出/标准错误。

全局工具命令只能引用其声明参数，不能引用 `${CASE...}`、`${ACTIONS...}` 或其它运行时 Context 作用域。需要把运行时数据显式传递到动作调用中，然后再在命令中引用对应声明参数。这使全局工具保持独立，并使依赖可静态校验。

#### SSH 执行

全局工具和每个工具组都可以在其自身根配置中定义一个执行目标：

```yaml
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519
```

根 `ssh` 仅适用于内联全局工具；工具组仅使用其自身 `ssh`，不会继承根配置。host/user 是必需的，port 默认为 22，key 是可选的。密码字段不受支持。ATT 优先使用本地 OpenSSH，带 `BatchMode=yes` 和 `StrictHostKeyChecking=yes`。如果 `PATH` 中不存在可执行 `ssh`，ATT 会警告将使用捆绑的 mwiede/jsch Java 库。后者使用严格的 `~/.ssh/known_hosts`，不会继承 OpenSSH agent 或 `~/.ssh/config`，通常需要 `identityFile`。两种传输都会把逻辑 argv 安全地单引号封装为一个 POSIX 远程命令字符串。远程连接性和可执行文件存在性无法通过包验证证明。SSH 的 stdout/stderr/status/timeout/retry/assert/saveAs 行为与本地工具一致，证据中会记录 `transport: openssh|mwiede/jsch`。

Case 输出工作目录以及两个环境变量规则仅适用于本地工具进程。ATT 不会为 SSH 远程命令预先 `cd`，也不会注入本地文件系统路径，因为 `${CASE.outputDirectory}` 和包根在远端没有定义映射。远程进程会使用 SSH 账户的默认目录。需要共享文件系统或远端目录时，须通过显式声明的工具参数传递。

#### 输入、输出、超时和状态

可用命令占位符为：

| 占位符 | 含义 |
|---|---|
| `${argument}` | 首选直接引用已声明参数键 |
| `${input.argument}` | 显式命名空间引用同一已声明参数 |
| `${TOOL.input.argument}` | 对同一参数的支持完整别名 |

ATT 按工具配置的 `output: txt|yaml|json|xml` 解析 stdout，默认 `txt`。V2.5 Tool Action 的 object `saveAs` 可保存精确 raw stdout，或把 typed `${output.result}` 编码为 text/json/yaml/xml；路径写入当前 Case artifact 目录。retry 共用一个渲染后的路径，最终保留最后一次可写 attempt 的内容。不使用 `saveAs` 时，ATT 不创建额外命名 artifact，但输入、argv、stdout、stderr、解析结果、退出码和 retry evidence 仍保存在 Case evidence 中。

超时、启动/进程 I/O 失败或结构化输出解析失败是 ERROR，不能被断言覆盖。否则工具动作的 `assert` 决定 PASS/FAIL。如果未配置断言，操作完成即算 PASS，即使进程退出码非零。退出码仍保留在 `action.output.exitCode` 中，因此要求零退出码的模板需显式写明。命令、输入、stdout、stderr、原始输出、持续时间、退出码、解析后的 `output.result` 和断言详情都会保留为证据。

超时优先级为：

```text
工具动作 timeoutMs → 工作簿侧车 timeoutMs → 全局 timeoutMs → 10000 ms
```

所有配置的超时都是 1 到 3600000 毫秒的整数。

### 3.4 运行测试

#### 先校验

```sh
./att.sh validate --package
```

包模式是默认模式，会检查全局配置、所有发现的工作簿/侧车、配置的 sheets 和行、所有模板（包括未被引用的模板）、表达式、工具、路径和包完整性。它不会调用外部工具，是发布前的必需门槛。

```sh
./att.sh validate --selected --case payment.payment.TC001
```

选中模式只检查显式选中的 Case 及其依赖闭包。它适合快速反馈，可报告未选中内容未被检查。`run` 总是会对其不可变执行计划执行选中范围校验。

#### 选择 Case

| 选项 | 含义 |
|---|---|
| `--all` | 递归发现 `testcase.root` 下所有工作簿/侧车对 |
| `--suite <xlsx>` | 选择一个工作簿；可重复 |
| `--suite-dir <dir>` | 在另一个目录下发现工作簿 |
| `--case <workbookId.groupId.rowCaseId>` | 选择一个完整 Case ID |
| `--tag <tag>` | 包含任何指定标签的 Case |
| `--exclude-tag <tag>` | 在包含过滤之后排除匹配标签 |

空选择是错误。`--rerun-failed` 本身也是一个有效选择，会从最新完成的持久化 run 中读取 FAIL/ERROR Case ID。额外的 `--case`、`--tag`、`--exclude-tag` 过滤器会进一步收缩该集合。缺失历史、先前无 FAIL/ERROR Case、或保存的 ID 无当前可发现 Case 匹配，都会导致命令错误。当前工作簿、侧车、testcase XML 快照、模板和工具定义都会被校验和执行；ATT 不会重放旧 run 的输入。

#### 执行

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

默认人类可读 run 只打印最终结果计数和报告路径。`--quiet` 会抑制正常输出。`--verbose` 会增加 run/suite/Case/stage/action 生命周期进度，并把每个完整 Case 日志块镜像到控制台，包括模板/工具输入、逻辑和执行 argv、stdout、stderr、负载和动作证据。Verbose 输出可能包含秘密或个人数据，因此只应在合适受保护的终端中启用。两个选项互斥。

#### 结果与退出码

| 最高结果 | 运行状态 | 退出码 |
|---|---|---:|
| ERROR | ERROR | 3 |
| 无 ERROR 时为 INVALID | INVALID | 2 |
| 无 ERROR/INVALID 时为 FAIL | FAIL | 1 |
| 至少一个 PASS 且仅有 PASS/SKIPPED | PASS | 0 |
| 全部选中 Case 都是 SKIPPED | SKIPPED | 0 |

断言为假是 FAIL。表达式求值、进程、超时、解析、I/O、配置和校验失败是 ERROR 或 INVALID，取决于阶段。包含 FAIL 和 ERROR 的 run 将返回 3。

### 3.5 报告

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

展开的 Case 包含完整 Case ID、名称、状态、持续时间、Expected 和 Actual 结果、每条记录动作结果的一行、详细执行日志，以及 `.log` 与 `case.yaml` 的显式链接。HTML 不会内联完整的持久化 Stage/Template/Action/Tool 树；需要结构化最终运行时状态时，请打开 `case.yaml`。只有 `type: assert` 动作会计入：Expected 会按动作顺序追加每个非空最终 description 和验证时的 `expected`；Actual 会按动作顺序追加每个非空运行时 `actual`。值按动作顺序使用一个 LF 分隔非空条目。HTML 会转义并预换行，Excel 保留 LF 并使用自动换行，JSON 使用转义的 `\n`，JUnit 保留相同的换行边界。

`--ci-output junit,json` 会生成 JUnit XML、JUnit HTML 和 JSON 汇总：

- `<run>/ci/junit.xml`
- `<run>/report/junit.html`
- `<run>/ci/summary.json`

JUnit HTML 是与 JUnit XML 相同已完成汇总的人类可读投影，不是第二次聚合。

可从持久化 run 证据重新生成报告：

```sh
./att.sh report --run-id SIT-001
```

## 04 Cookbook

本章从你想要完成的任务出发，展示对应的 ATT 模式。

### 在失败后执行回滚，并每次都清理

```yaml
stages:
  - {key: invoke, template: 執行模板, required: true}
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

回滚只在先前失败后运行。清理无论先前结果如何都会运行。回滚或清理失败仍会保留在最终聚合中。

### 覆盖结果工作簿列标签

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

### 解析 JSON 工具输出

配置 `output: json` 后，使用普通 map/list 导航即可：

```yaml
assertStatus:
  type: assert
  description: API status is successful
  assert: "${ACTIONS.callApi.output.result.status} == 'SUCCESS'"
  expected: SUCCESS
  actual: "${ACTIONS.callApi.output.result.status}"
```

JSON 重复对象键、格式错误，或声明为 JSON 但无法解析的输出，都会在进程退出码为 0 时仍然报 ERROR。

### 访问 XML 属性、文本和重复元素

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

### 把列表作为单独进程参数传递

多个已声明工具参数可使用 `delimit`。下面示例同时展示两种 `argNameMode`：

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords and log levels
    command:
      - ./tools/grep_from_app_logs.sh
      - "${logFile}"
      - "${keywords}"
      - "${levels}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Comma-delimited values, required: true, delimit: ",", argName: --keyword, argNameMode: repeat}
      levels: {name: Levels, description: Pipe-delimited levels, required: false, delimit: "|", argName: --levels, argNameMode: once}
```

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${ACTIONS.getLogs.output.targetFiles[0]}, keywords='PAYMENT,POSTED', levels='ERROR|WARN')}"
```

结果尾部逻辑 argv 为 `--keyword`、`PAYMENT`、`--keyword`、`POSTED`、`--levels`、`ERROR`、`WARN`。`repeat` 会对每个关键词重复 `--keyword`；`once` 是默认值，可以省略，它只在整个列表前发出一次 `--levels`。每个 delimited 参数都在各自的命令占位符位置独立扩展。

#### Linux Bash 解析示例

本节通过 Bash 示例说明 `argNameMode: repeat` 与 `once` 的行为。省略。

### 重试指定退出码

重试属于 tool 动作，而不是工作流或任意 stage：

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    retryOn: [EXIT_CODE]
    exitCodes: [1, 75]
```

`maxAttempts` 包含第一次尝试，默认 1。V2.3 只支持 `EXIT_CODE`；如果省略 `exitCodes`，则任何非零退出码都可重试。超时、输出解析、I/O、配置、断言、render、log 失败不会重试。V2.3 没有延迟/退避字段，因此符合条件的重试会立即执行。

每次尝试都直接记录在 Case 日志和动作记录中；不会创建 `attempt-001` 目录。后续成功尝试会使动作 PASS，同时保留先前证据；耗尽重试次数后则产出 ERROR。只有安全可重复的操作才应使用重试。

## 05 CLI 参考

### 命令

| 命令 | 目的 | 是否调用外部工具 |
|---|---|---:|
| `help` | 显示语法和选项；无命令时默认 | 否 |
| `version` | 输出 ATT 版本 | 否 |
| `validate` | 校验包或选中依赖闭包 | 否 |
| `snapshot` | 生成同名规范 testcase XML | 否 |
| `run` | 校验并执行已选 Case | 是，dry-run 除外 |
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
| `./att.sh run <selection> --quiet` | 抑制正常完成输出 |
| `./att.sh run <selection> --verbose` | 显示生命周期进度并镜像完整 Case 日志 |
| `./att.sh report --run-id <id>` | 重建 `report/index.html` 和 `report/junit.html` |
| `./att.sh docs` | 生成 `build/docs/index.html` |
| `./att.sh build` | 在 `build/` 中归档最新完成 run |
| `./att.sh clean` | 删除文档化生成输出 |

### 退出码

| 代码 | 含义 |
|---:|---|
| 0 | 命令/运行成功，且无 FAIL、ERROR、INVALID |
| 1 | 至少一个 FAIL，且无 ERROR/INVALID |
| 2 | CLI/配置/校验/INVALID 失败 |
| 3 | 至少一个 ERROR，或不可恢复运行时失败 |

## 06 配置参考

本章是作者编写配置时的权威阅读参考。下面提到的 [`schemas/`](../schemas/) 仍是机器可读契约。模式校验会先于跨字段和文件系统校验执行。

### 配置层与优先级

| 层级 | 来源 | 所管辖内容 |
|---|---|---|
| 全局 | `config/config.yaml` | 输出目录/环境/运行时默认值、模板根、报告、XML 模式、全局工具、组路径、可选全局 SSH |
| DB helper | `dbhelpers` 引用的独立 YAML | 一个 JDBC 实例的连接、statement timeout、交易、result limit 与 evidence policy |
| 工具组 | 配置的 YAML 路径 | 组身份、可选 script/SSH、分组工具 |
| 工作簿 | `<workbook>.yaml` | Excel 映射、阶段、工作簿标签、工作簿超时 |
| 模板 | `template.yaml` | 模板身份和有序动作 |
| CLI | 命令选项 | 选择、Run ID、输出覆盖、展示、CI 格式 |

动作超时覆盖侧车超时，侧车超时覆盖全局超时。CLI 的 `--output-dir` 和 `--run-id` 会在一次命令中覆盖相应默认值。一个层级中合法的字段，若放在别的层级中也会被拒绝。

### Schema catalog

[`schemas/catalog.yaml`](../schemas/catalog.yaml) 使用 `att-schema-catalog/v2.6`。当前主配置与 Tool group 分别为 `att-config/v2.6`、`att-tool-group/v2.6`；dbhelper 与 template 仍为 `att-dbhelper/v2.5`、`att-template/v2.5`。旧 `att-config/v2.1|v2.2|v2.5`、`att-tool-group/v2.2` 与 `att-template/v2.3` 保持可读，但只有 V2.6 Tool descriptor 可使用 `call`／`cache`。

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
tools: {}
```

| 路径 | 必填/默认值 | 约束 |
|---|---|---|
| `schemaVersion` | 必填 | 当前为 `att-config/v2.6`；旧 V2.1/V2.2/V2.5 仍可读取，但不能声明 call-backed Tool |
| `outputDirectory` | `output` | 非空包相对输出根 |
| `environment` | `SIT` | 非空值暴露为 `${CASE.environment}`；它不会单独选择端点 |
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
| `evidence.parameters` | `masked` | `masked`、`types` 或 `values`；使用 values 可能暴露敏感数据 |

validate、docs、snapshot 与 dry-run 都不会打开 DB Connection。dbhelper 文件路径、ID、字段、SQL 文件和 template call 会在执行前校验。

### 工作簿侧车

| 对象 | 允许属性 | 必填/约束 |
|---|---|---|
| 根对象 | `schemaVersion`、`id`、`excel`、`stages`、`report`、`timeoutMs`、`x-*` | `schemaVersion`、包内唯一 `id`、`excel`、非空 `stages` 必需 |
| `excel` | `sheet`、`headerRows`、`caseId`、`tags`、`dataColumns` | `sheet`、`caseId`、`tags` 必需；`headerRows >= 1` |
| `stages[]` | `key`、`template`、`dataColumns`、`required`、`runWhen`、`onFailure` | `key`/`template` 必需；`key` 不能含点号 |
| `report` | `columns` | 值为字符串 |

`timeoutMs` 为 1–3600000。只有侧车根对象允许 `x-*`；`excel`、stages 和侧车 `report` 拒绝扩展和其他未知字段。侧车不能覆盖工具、模板根、环境或输出根。

### 模板与动作

| 对象/类型 | 允许/必需契约 |
|---|---|
| 模板根对象 | `schemaVersion`、`name`、`description`、`actions`、`x-*`；`schemaVersion`、`description`、非空 `actions` 必需 |
| 动作 common | `type`、`description`、`onFailure`，以及其选定类型所属字段；动作 ID 不能含点号 |
| render | 需要 `payload`、`renderAs`；可选 `assert`; 不允许 saveAs/overwrite/output/call/expression/message/file/level/fields/timeout/retry |
| tool | 需要 `call`；可选 object `saveAs` 与 `assert`；只有主要目标为 command-backed Tool 时才支持 `timeoutMs`／`retry`；不允许 DB/render/assert-action/log-only 字段 |
| db | 需要 `db` 与恰好一个 `query`／`update`；block 内恰好一个 `sql`／`sqlFile`；可选 params、object `saveAs`、`assert`；不允许 retry 或 Action timeout |
| assert | 需要 `assert`；可选 `expected`、`actual`；不允许 expression/render/tool/log-only 字段、timeout 或 retry |
| log | 至少需要 `message` 或 `file`；可选 `level`、`fields`、`assert`；不允许 render/tool/assert-action-only 字段、timeout 或 retry |
| assign | 需要 `name`、`expression`；可选 `assert`；`name` 在整个 Case 的 `CASE.VARS` 下唯一；不允许 render/tool/assert-action/log-only 字段、timeout、retry、saveAs 或 overwrite |
| retry | `maxAttempts`、`retryOn`、`exitCodes`；`retryOn` 必填且仅包含 `EXIT_CODE` |

`renderAs` 允许 `file`、`text`、`json`、`yaml`、`xml`。`maxAttempts` 为 1–10；`exitCodes` 值为 1–255。日志级别为 `TRACE`、`DEBUG`、`INFO`、`WARN` 或 `ERROR`。模板根对象与动作都允许 `x-*`；`fields` 是无约束日志字段映射。`output` 是 V2.3 的运行时证据，绝不是动作配置字段。

#### Assign 变量唯一性与生命周期

assign 动作会在 `CASE.VARS` 下创建一个不可变、Case 作用域的条目。一个 Case 内每个变量名必须唯一。重复声明会导致校验失败。

#### Action `saveAs`

V2.5 的 Tool 与 DB Action 共用一个 object shape：

```yaml
saveAs:
  path: relative/path/result.yaml
  format: yaml
  overwrite: false
```

`path` 必填，`overwrite` 默认 false。`format` 只控制写入表示，不改变 `${output.result}` 的 typed value。`att-template/v2.5` 不允许 sibling `overwrite` 或 scalar `saveAs: file.name`。

| Action target | 允许格式 | 默认 | 保存内容 |
|---|---|---|---|
| 配置 process Tool | `raw`、`text`、`json`、`yaml`、`xml` | `raw` | raw 为精确 stdout bytes；其他格式序列化已解析 typed result |
| `type: tool` 的主要 Java built-in | `text`、`json`、`yaml`、`xml` | `text` | built-in typed result；text 使用 UTF-8 字符串表示 |
| 配置 call-backed Tool | `text`、`json`、`yaml`、`xml` | 无，必须明确指定 | typed result；不存在 stdout |
| `type: db` | `json`、`yaml`、`xml` | 无，必须明确指定 | 稳定 typed DB result object |

`raw` 仅适用于有原始 stdout 的 process Tool。configured Tool 的 `output: txt|json|yaml|xml` 继续决定 stdout 如何解析，因此也决定非 raw 格式的 typed source。

`saveAs.path` 使用 Action 前的正常 expression scope 渲染，必须得到非空安全相对路径并保持在当前 Case artifact 目录内。绝对路径、反斜线、空／`.`／`..` segment 与 containment escape 都非法。父目录按需创建。

写入发生在可选 Action assertion 之前。process Tool 的 raw 即使遇到 parse error、exit-code retry 或最终 Action 不成功，也保存已捕获 stdout；非 raw Tool artifact 要求 parse 成功，built-in artifact 要求调用成功，DB artifact 要求 JDBC 成功。codec、路径、collision 或写入失败都是 ERROR。retry 共用同一路径，后续 attempt 只能覆盖同一 Action 先前 attempt 写入的 artifact。最终路径加入 `output.targetFiles`。

`render`、`assert`、`log` 与 `assign` 不支持 `saveAs`；render file 继续使用 `renderAs: file`。

`att-template/v2.3` 保持读取兼容：旧式 Tool `saveAs: response.json` 加 sibling `overwrite` 会内部归一化并维持原行为；新模板必须使用 V2.5 object form。

### 工具契约

每个工具要求 `name`、`description`，以及恰好一个 `command` 或 `call`。Command 可以是非空标量或字符串列表，`output` 默认为 `txt` 并支持 `txt|yaml|json|xml`。Call 必须是一个精确表达式，目标为 DB query/scalar/update 或 pure built-in；可选 `cache` 只含 `scope: case|db`。Call-backed Tool 禁止 process-only `output`、SSH/script 与参数 `argName|argNameMode|delimit`。Update 不能缓存，`db` cache 只适用于 DB query/scalar。

每个参数都要求 `name`、`description` 与 YAML boolean `required`。Command-backed 参数可使用 argv 属性；call-backed 参数只描述与校验 typed input。

### 标识符和路径约束

Run ID 和完整 Case ID 会直接用作目录名，ATT 不会对合法标识做 slug 化或哈希处理。

Run ID 必须非空、最多 128 个 Unicode 码点，不能是 `.` 或 `..`，不得含前导/尾随空白或尾随 `.`，且不能包含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|`、NUL、控制字符。Windows 设备名（如 `CON`、`NUL`、`COM1`、`LPT1`）会按大小写不敏感方式拒绝。

`workbookId`、`groupId`、`rowCaseId` 同样遵循相同字符规则。`workbookId` 与 `groupId` 不能含点号，因为点号用于分隔三个组件；`rowCaseId` 可含点号。模板路径相对 `templates.root`；render glob 匹配必须保持在模板下，`renderAs: file` 与 Tool/DB Action `saveAs.path` 目标必须保持在 Case artifact 目录下。ATT 会规范化并检查根包含性。

### Validation JSON 合约

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.6.0",
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

每个诊断都包含 `code`、`severity`、`message`、`file`、`field`、`sheet`、`row`、`column`、`template`、`action` 和 `suggestion`。不适用的字段为 `null`。代码稳定；自动化不能解析人类消息。

### 生成输出模式摘要

| 产物 | 顶层必需契约 |
|---|---|
| `run.yaml` | `schemaVersion`、`att`、`runtime`、`run`、`validation`、`inputs`、`cases`、`summary`、`outputs` |
| Validation JSON | `schemaVersion`、`attVersion`、`valid`、`mode`、`summary`、`diagnostics` |
| CI summary JSON | `schemaVersion`、`attVersion`、`runId`、`environment`、`startedAt`、`endedAt`、`status`、`summary`、`durationStatistics`、`cases`、`diagnosticCounts`、`report`、`inputManifestHash` |
| JUnit XML | 一个 testsuite，含 test/failure/error/skipped 计数，以及每个 ATT 用例的 testcase |

## 07 表达式参考

### 统一表达式引擎

V2.4.2 使用同一个解析和渲染器来处理 `${...}` 值插值和 `#{...}` 表达式。所有接受 `${...}` 的用户可见位置也都接受 `#{...}`；校验使用相同语法，但不会执行内建函数或外部进程。

两种分隔符指示不同角色，而不是两套不同引擎：

- `${path}` 将一个 Context 值插入周围文本，例如 `Reference=${CASE.VARS.SrcRefNo}`。
- `#{name(arguments)}` 评估内建函数或已配置 Tool 调用。在其参数中，完整未引用的规范 Context 路径会被直接解析，例如 `#{length(CASE.VARS.SrcRefNo)}`。
- ASCII `'...'` 或 `"..."` 总是在调用内表示字面字符串，例如 `#{length('CASE.VARS.SrcRefNo')}`。

优先使用不嵌套的形式：

```yaml
assert: "#{length(value=CASE.VARS.SrcRefNo)} <= 35"
description: "Reference length: #{length(CASE.VARS.SrcRefNo)}"
```

旧式嵌套形式仍然兼容，结果相同：

```yaml
assert: "#{length(value=${CASE.VARS.SrcRefNo})} <= 35"
```

#### 调用参数解析规则

ATT 按以下顺序解析每个完整调用参数：

1. 精确 `${...}` 引用会被解析为其类型化 Context 值。
2. 未引用且以 `CASE`、`RUN`、`ACTIONS`、`TOOL`、`DB` 或动作局部 `output` 开头的规范 Case-runtime 路径会被解析为其类型化值。
3. 精确嵌套 `#{...}` 调用会被求值，并把其类型化结果传给外层调用。
4. ASCII 单/双引号包裹的文本是显式字符串字面量。数字和布尔字面量保留正常类型。
5. 其他未引用 token 保留旧式字面行为，以兼容旧写法。新作者应对字符串字面量加引号，以避免歧义。

只有完整参数会执行 bare-path 解析。`prefix-CASE.caseId` 是 literal；应写成 `prefix-${CASE.caseId}` 或 `#{concat('prefix-', CASE.caseId)}`。ASCII 引号才是语法，弯引号 `“...”`／`‘...’` 会保留为普通 Unicode 字符。

可用值与可调用能力取决于表达式所在位置。普通 Case-runtime 字段可使用 built-in、配置 Tool 与只读 DB query；`report.fileNamePattern`、Tool `command` 与 DB SQL source 是受限 scope，不允许隐藏或递归 external execution。Tool/DB `saveAs.path`、DB params 在主调用前求值；DB SQL 内容只允许 Context 和 pure built-in。

`type: tool` 的主 `call` 可指向配置 Tool 或 ATT built-in。主 built-in 在 JVM 内执行，结果在 `${output.result}`，记录 `type: builtin` attempt evidence，但没有 process `TOOL` 节点、argv、stdout 或 stderr。

### Runtime Context

ATT 的持久化运行时树有一个权威根：

```text
CASE
├── caseId, workbookId, groupId, rowCaseId, workbook, sheet, rowNumber, tags
├── outputDirectory
├── environment, status, startedAt, durationMs, error
├── <case data aliases>
├── VARS
│   └── <assignName> (typed runtime value shared by later stages/templates)
├── DB (fixed framework-owned Case-finalization map)
│   └── <dbhelperId> (transaction finalization state and sanitized error)
└── STAGES
    └── <stageKey>
        ├── name and stage-private data
        └── TEMPLATE
            └── ACTIONS
                └── <actionId>
                    ├── id, type, description
                    ├── output
                    ├── TOOL
                    │   └── <toolName>
                    └── DB
                        └── <dbhelperId>
                            └── <callId> (inline query evidence)
```

关键字是大写：`CASE`、`VARS`、`STAGES`、`TEMPLATE`、`ACTIONS`、`TOOL`、`DB`。元数据保留 camelCase。没有 `CASE.fields`、`CASE.data` 或 `TOOLS` 节点。

常见作用域包括：

| 作用域 | 示例 |
|---|---|
| CASE | `caseId`、`workbookId`、`groupId`、`rowCaseId`、`workbook`、`sheet`、`rowNumber`、`tags`、`environment`、保留 `outputDirectory`、Case 数据别名、运行时 `VARS`、固定交易收尾 map `DB` |
| STAGE | `key`、`name`、选择器映射数据、侧车阶段数据、状态、计时、错误 |
| TEMPLATE | `name`、`path`、`description`、状态、计时、错误 |
| ACTION | `id`、`type`、最终 `description`；嵌套 `output.status`、`success`、`durationMs`、`exception`、`targetFiles`、`result` 与断言/日志数据 |
| TOOL | 配置工具的限定名称、可选 group ID/tool key、`input`、逻辑/执行 argv、可选 SSH 目标元数据、`stdout`、`stderr`、`rawOutput`、解析 `output`、状态、退出码、持续时间、重试证据，以及在 `saveAs` 使用时的 `outputFile` |
| DB | dbhelper ID、call ID、按 evidence policy 保存的 SQL source/hash、masked/typed/value parameters、duration、typed result 与 sanitized error |

使用 `${output...}` 访问当前动作在求值 runtime assertion、actual 与最终 description 时的局部 scope。`${ACTIONS.<id>...}` 是当前模板已完成 Action 的便利视图；跨阶段使用规范 `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS...}`。根 `${TOOL...}` 与 `${DB...}` 保留给调用内部，不是 Case 级“最近一次调用”API。Tool 与 inline DB evidence 保存在所在 Action 并写入 Case log；Case 级 DB 收尾只在完成后通过 `${CASE.DB.<instance>}` 提供。开头的 `CASE` 区分 `${CASE.DB...}` 与 invocation-local `${DB...}`。

`${CASE.outputDirectory}` 是保留的标准化绝对路径。`CASE.VARS` 与 `CASE.DB` 也是固定 framework-owned map，因此 sidecar `excel.dataColumns` alias 或其他 Case-root alias 不能名为 `VARS`／`DB`。三者在第一个 stage 前已存在；`CASE.DB` 保持空值，直到 Case transaction finalization 发布已使用实例 outcome。同一 Case 的 Action 不可依赖该 post-Case state。

### `config.report.fileNamePattern`

该配置使用统一表达式引擎，但拥有独立的非 Case 作用域。它只支持一个大小写敏感的值引用：

| 占位符 | 值 |
|---|---|
| `${suiteName}` | 源工作簿 basename，去掉结尾的小写 `.xlsx` 后缀；例如 `testcase/payment_regression.xlsx` 变为 `payment_regression` |

配置字符串必须引用 `suiteName`，可以是 `${suiteName}` 文本插值，也可以是内建函数的完整裸参数。合法示例包括：

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

以及：

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(suiteName)}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(suiteName)})}.xlsx"
```

但不支持如 `${runId}`、`${workbookId}`、`${environment}`、`${CASE.caseId}` 等运行时值引用。

### Tool 定义中的 `command` 表达式

Tool 的 `command` 也拥有独立的受限上下文，只能引用该工具 `arguments` 映射中声明的键。每个声明键允许三种等价占位形式：

| 形式 | 含义 |
|---|---|
| `${requestFile}` | 首选直接参数引用 |
| `${input.requestFile}` | 显式工具输入命名空间 |
| `${TOOL.input.requestFile}` | 对同一工具输入的支持完整别名 |

例如：

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${requestFile}"
      - "${environment}"
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

### Tool 定义中的 `call` 表达式

V2.6 call-backed Tool 使用相同的声明参数理念，但保留 typed value，并只允许 pure built-in 与一个主要 DB query/scalar/update。`input.customerId` 来自外层 Tool call，不是 Case 全局变量；`CASE`／`ACTIONS` 等 root 在定义中不可见。Inline SQL 与 package-contained SQL file 内容都在此 scope render，测试数据仍应放在 `params` 并使用 JDBC `?`。

### 操作符

支持的断言操作符有：

- `==`
- `!=`
- `>`
- `>=`
- `<`
- `<=`
- `like`
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

内建函数通过 `#{...}` 调用，表格中说明了常见函数：

| 函数 | 目的 | 示例 |
|---|---|---|
| `upper` | 转大写 | `#{upper(value=${CASE.currency})}` |
| `lower` | 转小写 | `#{lower(value=${CASE.channel})}` |
| `trim` | 去除首尾空白 | `#{trim(value=${CASE.reference})}` |
| `ltrim` | 去除前导空白 | `#{ltrim(${CASE.reference})}` |
| `rtrim` | 去除尾随空白 | `#{rtrim(${CASE.reference})}` |
| `string` | 转文本 | `#{string(value=${CASE.amount})}` |
| `number` | 解析并归一化数字 | `#{number(value='12.50')}` |
| `boolean` | 转布尔值 | `#{boolean(yes)}` |
| `length` | 返回文本长度 | `#{length(value=${CASE.reference})}` |
| `concat` | 拼接参数 | `#{concat(a='PAY-', b=${CASE.caseId})}` |
| `coalesce` | 返回第一个非空值 | `#{coalesce(${CASE.optional}, 'N/A')}` |
| `nvl` | 对 null/empty 返回默认值 | `#{nvl(${CASE.optional}, 'N/A')}` |
| `iif` | 从布尔值选择两个值之一 | `#{iif(${CASE.enabled}, 'Y', 'N')}` |
| `nchar` | 重复值 | `#{nchar(3, '9')}` |
| `substr` | 截取子串 | `#{substr(${CASE.reference}, 0, 8)}` |
| `indexOf` | 返回位置 | `#{indexOf(${CASE.reference}, '-')}` |
| `contains` | 测试子串包含 | `#{contains(${CASE.message}, 'SUCCESS')}` |
| `startsWith` | 测试前缀 | `#{startsWith(${CASE.reference}, 'PAY')}` |
| `endsWith` | 测试后缀 | `#{endsWith(${CASE.fileName}, '.xml')}` |
| `replace` | 字面替换 | `#{replace(${CASE.reference}, '-', '')}` |
| `padLeft` | 左填充 | `#{padLeft(${CASE.sequence}, 8, '0')}` |
| `padRight` | 右填充 | `#{padRight(${CASE.code}, 5, '_')}` |
| `sysdate` | 返回系统日期 | `#{sysdate('yyyyMMdd')}` |
| `systimestamp` | 返回系统时间戳 | `#{systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `formatDate` | 格式化 ISO 日期 | `#{formatDate(${CASE.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `dateAdd` | 日期增减 | `#{dateAdd(${CASE.businessDate}, 1, 'day')}` |
| `fileExists` | 测试常规文件是否存在 | `#{fileExists(${CASE.requestFile})}` |
| `directoryExists` | 测试目录是否存在 | `#{directoryExists(${CASE.outputDirectory})}` |
| `fileSize` | 返回常规文件大小 | `#{fileSize(${CASE.requestFile})}` |
| `makeDirectories` | 创建目录树 | `#{makeDirectories(${CASE.archiveDirectory})}` |
| `copyFile` | 复制文件 | `#{copyFile(${CASE.requestFile}, ${CASE.backupFile}, true)}` |
| `moveFile` | 移动文件 | `#{moveFile(${CASE.sourceFile}, ${CASE.targetFile})}` |
| `deleteFile` | 删除文件 | `#{deleteFile(${CASE.temporaryFile}, true)}` |
| `randomChoice` | 从输入中随机选择 | `#{randomChoice('A', 'B', 'C')}` |

## 08 报表参考

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

Run ID 和 Case ID 在校验后保持原样。最终 run 目录表示已完成发布；中断工作仍保留在 `.in-progress` 下。

### 人类可读 HTML 报告

`report/index.html` 是主要终端用户报表。可以直接从磁盘打开。组按 `workbookId.groupId` 汇总；界面把 `groupId` 标记为 Sheet。Case 支持 Workbook/Sheet/Status 下拉框、对 workbook/group/full Case ID/tag 的大小写不敏感搜索，以及每列标题的升序/降序排序。Duration 按数值排序。

展开的 Case 包含完整 Case ID、名称、状态、持续时间、Expected 和 Actual 结果、每条记录动作结果的一行、详细执行日志，以及 `.log`/`case.yaml` 的显式链接。Expected 是所有 assert 动作的非空最终 description 和验证时 `expected` 的有序 LF 联接；Actual 是所有非空运行时 `actual` 的有序 LF 联接。详细日志显示按时间顺序的执行证据；`case.yaml` 存储完整结构化最终 Stage/Template/Action/Tool 状态。

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

## 09 故障排查

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

ATT 会把缺失路径视作作者/运行时错误，而不是静默渲染成空字符串。遵循 `ATT-CTX-001` 的 `requestedPath`、`currentNode`、`missingSegment` 和最近建议，检查大小写敏感的作用域、物理表头/别名、阶段 key、动作 ID，以及可用性时间点。后缀简写必须唯一识别一个可读逻辑路径；`ATT-CTX-002` 会列出所有冲突候选，以便你加长后缀或使用规范路径。声明的可选字段即使值为空白，仍然是有效空字符串。

#### 为什么 FAIL 变成 ERROR？

假断言是 FAIL。无效表达式语法/导航、工具失败、超时、解析失败、I/O 失败或运行时异常，都是 ERROR。应查看动作证据，而不只看最终聚合状态。

#### 为什么工具跑了不止一次？

它的动作启用了重试，并收到了符合条件的非零退出码。查看 Case 日志中的尝试列表和最终动作记录。

#### 我能在 `command` 中使用 shell 管道吗？

不能。ATT 会把 `|`、`>`、`<` 按字面值传递。把 shell 行为放到审查过的工具脚本中。

#### 为什么必需的 delimited 参数会拒绝 `N/A`？

必需项验证发生在数组扩展之前。`N/A` 会归一化为空白，所以必需输入被视为缺失。

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

## 10 维护者架构

本章解释用户通常不需要在编写 Case 时了解，但维护者在修改校验、执行、持久化或报表时需要了解的行为。

### 所有权模型

```text
case 拥有有序阶段
stage 定义模板选择列并拥有阶段私有数据
当前行的选择器单元格命名要解析的模板
template 拥有有序动作
tool action 通过声明参数调用一个独立全局工具契约
```

持久化运行时树的权威根只有一个 `CASE`。如 `ACTIONS` 和 `TOOL` 这样的便利作用域不会创建替代持久化根。

### 校验流水线

ATT 会先使用 Draft 2020-12 schema，然后做语义校验。随后解析工作簿映射、选择器、模板、负载、表达式、工具、参数契约、标识符、路径和包完整性。

包模式会发现配置根目录下所有内容。选中模式只校验执行所需的不可变依赖闭包。校验在外部工具或最终 run 发布前完成。

### 执行与聚合

Runner 会按确定顺序规划选中的 Case，并执行阶段/模板/动作顺序。`onFailure` 控制继续，但不抑制结果严重度。聚合规则共享给所有消费者：

```text
if any ERROR exists: ERROR
else if any INVALID exists: INVALID
else if any FAIL exists: FAIL
else if any PASS exists: PASS
else: SKIPPED
```

因此 PASS + SKIPPED 是 PASS，全部是 SKIPPED 时是 SKIPPED，且解析为零 Case 的选择是命令错误，而不是 SKIPPED run。

报表、清单、CLI 汇总、CI JSON、JUnit XML、JUnit HTML 和进程退出码必须来自同一聚合模型。

### 原子运行生命周期

执行前，ATT 会创建：

```text
<outputDirectory>/.in-progress/<RunID>-<nonce>/
```

证据写入其中。所有必需输出最终化后，ATT 会原子发布为：

```text
<outputDirectory>/<RunID>/
```

中断运行保留在 `.in-progress` 中，且不适用于 `report`、`build`、`rerun-failed` 或 latest-run 选择。若最终 Run ID 已存在，则发布失败。`latest-run.yaml` 只有在最终发布后才原子替换。

### 进程安全

ATT 直接构造 argv，不使用隐式 shell。超时终止必须依据平台支持停止受管进程并保留进程证据。结构化解析器会拒绝格式错误/歧义输入以及 XML 外部资源特性。

工作簿导入使用 Apache POI `DataFormatter` 处理普通单元格，刻意不创建 `FormulaEvaluator`；公式表达式而不是缓存结果进入 Context。

### CI 与并行执行

| 并发操作 | 契约 |
|---|---|
| 两个 run 使用相同 Run ID | 两者都可准备各自 `.in-progress` 目录，但只有一个可发布最终 run。后完成的发布者会失败且不会覆盖。 |
| 多个 run 更新 `latest-run.yaml` | 每个 run 先发布其最终目录；最后完成者赢得原子指针更新。完成顺序而非启动顺序决定 latest。 |
| `build` 与 `run` 同时执行 | Build 会固定一个已完成 latest-run/manifest 对，不会归档 `.in-progress` 内容。 |
| `report` 与 `clean` 同时执行 | 此破坏性竞态不受支持。Report 会失败而不是产生部分结果；共享一个输出根时应串行化 report/archive/clean 作业。 |

并行作业若需要独立运行历史、清理或 latest-run 行为，应使用不同 `--output-dir`。

### 路径与标识符安全

已校验的 Run ID 和 Case ID 会直接映射到目录名。每次写入都要针对预期根目录进行解析、规范化、解析相关现有符号链接，并验证严格包含。逻辑 CLI 标识符从不作为任意文件系统路径接受。

### 可复现性与版本化输出

完成的清单会捕获运行时身份、有效输入、哈希、选中 Case、摘要和输出路径。校验 JSON、运行清单和 CI 汇总都具有显式 `schemaVersion`。JUnit XML 受 XSD 约束。使用者应验证声明版本，而非推断结构。

### 维护者发布清单

- 运行完整自动化测试套件，并要求全部通过。
- 对代表性包运行 `validate --package`。
- 验证 CLI 与所有报表中的 FAIL/ERROR/INVALID 聚合与退出码。
- 验证 JSON/XML 解析、重复 XML 子节点、属性和命名空间。
- 验证超时和重试证据，包括耗尽重试和后续成功的情况。
- 验证 Run ID 冲突、原子完成、latest-run 更新和中断运行。
- 验证报表/build/clean 边界与并发命令行为。
- 验证 schema、示例、生成文档和本手册保持一致。
