## 09 配置參考

本章是作者编写配置时的权威阅读参考。下面提到的 [`schemas/`](../../schemas/) 仍是机器可读契约。模式校验会先于跨字段和文件系统校验执行。

### 配置层与优先级

| 层级 | 来源 | 所管辖内容 |
|---|---|---|
| 全局 | `config/config.yaml` | 输出目录/环境/运行时默认值、模板根、报告、XML 模式、全局工具、组路径、可选全局 SSH |
| DB helper | `dbhelpers` 引用的独立 YAML | 一个 JDBC 实例的连接、statement timeout、交易、result limit 与 evidence policy |
| MQ helper | `mqhelpers` 引用的独立 YAML | 一个 v1.0 IBM MQ TCP client 实例，或一个 v1.1 logical group 的 defaults、physical instances、selection 与 request/reply 默认值 |
| 工具组 | 配置的 YAML 路径 | 组身份、可选 script/SSH、分组工具 |
| 工作簿 | `<workbook>.yaml` | Excel 映射、阶段、工作簿标签 |
| 模板 | `template.yaml` | 模板身份和有序动作 |
| CLI | 命令选项 | 选择、Run ID、输出覆盖、展示、CI 格式 |

Action timeout 覆盖 Tool descriptor timeout，Tool timeout 覆盖全局 timeout。sidecar、stage、Template 不拥有 timeout/retry 默认。CLI 的 `--output-dir` 和 `--run-id` 会在一次命令中覆盖相应默认值。一个层级中合法的字段，若放在别的层级中也会被拒绝。

### V3.5.2 多环境 Profile 选择

ATT V3.5.2 使用一份 common `att-config/v2.6` 加上 `environments` map 选择环境；不通过修改 Action 或增加环境专用 Tool ID 来选择环境。SIT、UAT、PREPROD 及 production-like 环境之间，Action 只保留稳定的 logical ID：

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

SIT 与 UAT 的 DBHelper 都保持 `id: orders`，只改变 JDBC URL 等 physical connection details；MQHelper 都保持 `id: payment`，只改变 host、queue manager、port 和 channel。包含完整 descriptor、pool 和安全 evidence policy 的可复制例子见 [`examples/environments/README.md`](../../examples/environments/README.md)。

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

[`schemas/catalog.yaml`](../../schemas/catalog.yaml) 使用 `att-schema-catalog/v3.0`。当前主配置、Tool group、sidecar、Template 与 Flow 分别为 `att-config/v2.6`、`att-tool-group/v2.6`、`att-sidecar/v2.2`、`att-template/v3.0` 与 `att-flow/v3.0`。旧 schema 保持有限 read compatibility，但旧 `EXIT_CODE` retry 与 sidecar timeout 必须迁移。

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
| `mqhelpers` | `[]` | 唯一、安全、包相对的 `att-mqhelper/v1.0` 或 `att-mqhelper/v1.1` YAML 路径；normalized duplicate 会被拒绝 |
| `environments` | absent | 非空 profile 映射；每个 profile 只可包含 `dbhelpers` 和/或 `mqhelpers` typed list |
| `ssh` | absent | 内联全局工具的可选 SSH 目标 |
| `tools` | `{}` | 可复用工具契约映射 |

每个 `mqhelpers` path 都从 package root 解析，并包含一个 `att-mqhelper/v1.0` 或 `att-mqhelper/v1.1` object。v1.0 是 flat single-instance descriptor；v1.1 使用 `defaults`、非空 `instances[]`、group-level `evidence`，并在多 instance 时要求 `selection.strategy` 为 `random` 或 `roundRobin`。每个 physical instance 会在执行前取得 effective `connection`、`message`、`requestReply`、`pool`；详细 v1.1 model 与 invocation 例子维护在 MQHelper resource module。

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

`path` 可省略，`overwrite` 默认 false。省略 `path`（包括 `saveAs: {format: ...}`）只把 typed result 保留在 memory，不建立 artifact。只要提供 `saveAs`，即使没有 path，也会按目标专属的 format 默认值与限制校验；提供 `path` 时才按这些规则写入。`format` 只控制写入表示，不改变 `${output.result}` 的 typed value。`att-template/v2.6` 不允许 sibling `overwrite` 或 scalar `saveAs: file.name`。

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
  "attVersion": "3.5.2",
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
