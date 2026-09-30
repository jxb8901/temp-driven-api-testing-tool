## 09 配置參考

本章是作者编写配置时的权威阅读参考。下面提到的 [`schemas/`](../../schemas/) 仍是机器可读契约。模式校验会先于跨字段和文件系统校验执行。

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

SIT 与 UAT 的 DBHelper 都保持 `id: orders`，只改变 JDBC URL 等 physical connection details；MQHelper 都保持 `id: payment`，只改变 host、queue manager、port 和 channel。包含完整 descriptor、pool 和安全 evidence policy 的可复制例子见 [`examples/environments/README.md`](../../examples/environments/README.md)。

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
| Load scenario | att-load/v1.2 |

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
  fileNamePattern: "${SUITE_NAME}.result.xlsx"
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
| `report.fileNamePattern` | `${SUITE_NAME}.result.xlsx` | 结果工作簿文件名模式 |
| `report.columns` | `{}` | 任意字符串键和字符串标签值 |
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

全局 `mqhelpers` 中的每个路径都从 package root 解析，并包含现行 `att-mqhelper/v1.2` object。它定义 logical group、defaults、physical `instances[]`、selection 与 evidence policy；每个 physical instance 会在执行前取得 effective `connection`、`message`、`requestReply`、`pool`。可选 `evidence.output` 只控制 human-readable snapshot，不改变 typed `output.result`。详见[MQHelper resource module](05_resources/mqhelper.md)。

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

共用 Action result.format/path/overwrite 已移除。Render 使用 templateFormat 标记 DocumentValue。HTTP/MQ responseFormat 负责 ingress parsing；requestFormat 只供抽象 Map/List payload。字段、范例、evidence 行为与迁移见[动作与型别化值](14_actions.md)。

### 工具契约

Tool descriptor 必须二选一配置 command 或 call。Command-backed Tool 必须使用 stdoutFormat: text|json|yaml|xml 将 stdout 解析为 output.result。Call-backed Tool 保留 native return type，不使用 stdoutFormat。Tool descriptor 和 Action 没有共用 result representation/persistence 字段。Process output 属 operational evidence；人类可读格式由 Log 或可选 resource evidence output 负责。

Tool group 使用 att-tool-group/v2.9。Command、call、argument 与 evidence 范例见[Tool](05_resources/tools.md)。

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
