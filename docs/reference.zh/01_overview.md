## 01 概覽與核心概念

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 01 简介

ATT 是一个离线的、基于模板驱动的 API 测试执行器。测试数据保存在 Excel 中，完整场景保存在 Template 中，可复用的实现序列保存在 Flow 中，外部能力则注册为 Tool。

```text
工作簿行 → 测试用例 → 有序阶段
阶段 → 模板选择列 → 当前行的选择器单元格 → 模板
模板 → 有序动作 → Flow 或配置好的 Tool
Flow → 在调用 Template Context 中执行的可复用有序动作 → Tool / DB / built-in / 嵌套 Flow
```

你首先需要掌握的四个概念是：

| 概念 | 其负责的内容 |
|---|---|
| 测试用例 | 一行工作簿数据、用例级数据、标签和有序阶段 |
| 阶段 | 模板选择、阶段私有数据、执行条件和失败处理 |
| 模板 | 由有序动作组成的完整测试场景 |
| Flow | 拥有独立 Action scope、通过 `EXEC.VARS` 显式发布值的可复用 Template Action 组 |
| 工具 | 一个具有命名输入的能力；可启动外部命令，也可通过 V2.6 `call` 包装 typed DB 操作或纯 built-in |
| Dbhelper | 一个独立配置的 JDBC 连接、SQL timeout、transaction、结果限制与 evidence 策略 |

一个动作可以渲染负载、调用工具、查询／更新数据库、断言表达式、写入结构化日志、分配作用域运行值，或调用 Flow。ATT 会在执行外部工具前校验所选包，并将结果证据记录到一个已完成的运行目录下。

#### V3.4 的保证

- 配置是严格的。未知字段、错误类型、无效枚举值、重复 YAML 键，以及无效动作形状都是错误。
- dbhelper 使用独立 `att-dbhelper/v2.5` 文件，并通过一級 `type: db` Action 或只读 `#{db.<instance>...}` 表达式调用；它不是 Tool 的特殊配置。
- `CASE.DB` 是固定、区分大小写、由框架拥有的 Case 交易收尾节点，不能由 Excel 或侧车数据覆盖。
- 每个工作簿都有一个同名的 YAML 侧车文件和生成的语义 XML 快照。
- 每个模板都是包含 `template.yaml` 的目录。
- 每个 Flow 位于 `templates/flows/**/flow.yaml`，使用静态 `.vN` ID、使用 canonical caller input/metadata roots 但拥有新的 Action scope，并且最大嵌套深度为 3。
- `validate --package` 会检查整个包；`validate --selected` 只检查所选依赖闭包。
- Run ID 和 Case ID 会先被校验，然后直接用作输出目录名。
- 运行在执行前直接保留最终 Run ID 目录，执行期间即可查看 Case 日志和证据；只有完成 manifest 与 `latest-run.yaml` 会在成功最终化后发布。
- Log Action 与 process stdout/stderr 会以原始物理行写入 Case 日志；普通 run 不创建持久 `process-output` artifact。
- `${...}` 继续负责 Context 引用和文字插值；`#{...}` 是支持调用、算术、比较、布尔逻辑、list 与 `in` 的 typed expression block。
- 直接 DB Action 支持位置 `params` 或具名 `parameters`；dbhelper parameter evidence 默认记录解析后的值，但不会记录 connection credentials。
- FAIL、ERROR、INVALID、SKIPPED、PASS 具有稳定的聚合与退出码含义。
- JSON、XML、JUnit XML、JUnit HTML 和 CI JSON 输出都有版本化契约。
- V2.6 Template 保持可读，但只有 `att-template/v3.0` 可使用 Flow Action 或 Action `runWhen`。
- Tool Action 可在主要结果之后、assertion 之前运行 `evidence` collectors；每次 retry 都会重新收集，并记录独立 timeout 与 `continue|stop` 失败策略。
- IBM MQ helper 使用 `att-mqhelper/v1.0`，提供主要 Tool Action 的 `send`、`receive` 和 `request`，保留精确文件 bytes 及 CorrelId/MsgId 证据。

#### 包布局

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
│   ├── payment/
│   │   ├── template.yaml
│   │   └── request.tmp.json
│   └── flows/common/prepare/
│       └── flow.yaml
├── tools/
├── lib/                  # 用户提供的 JDBC driver 与其依赖
├── schemas/
└── output/
```

你通常会编辑 `config/config.yaml`、工作簿、侧车、模板、负载和工具脚本。同名 testcase XML 通常由 `snapshot` 生成，并作为源码控制证据进行审查；仅在运行时使用的 `--update-snapshot` 是显式的刷新工作流。ATT 拥有配置输出目录及其文档化构建位置下的生成内容。

全局 `testcase.root` 设置默认为 `testcase`。发现是递归进行的，且相邻的同名 XLSX/YAML/XML 三元组定义一个 testcase 集。

#### V3 Flow 编写契约

Flow descriptor 的顶层字段只能是 `schemaVersion`、`id`、`name`、`description` 和 `actions`：

```yaml
schemaVersion: att-flow/v3.0
id: common.decorate.v1
name: Decorate
description: 添加固定后缀。
actions:
  decorate:
    type: assign
    name: decoratedResult
    expression: "${EXEC.INPUT.caseId}-done"
  audit:
    type: log
    message: "Decorated ${EXEC.VARS.decoratedResult}"
    runWhen: "${EXEC.INPUT.auditEnabled} == true"
```

V3 Template 使用固定 canonical ID 调用 Flow：

```yaml
schemaVersion: att-template/v3.0
name: PAYMENT_FLOW
description: 使用可复用 Flow 的完整测试场景。
actions:
  prepare:
    type: flow
    use: common.decorate.v1
  verify:
    type: assert
    assert: "${EXEC.VARS.decoratedResult} == '${META.SOURCE.caseId}-done'"
```

Flow 和内联 Template Action 使用相同的 expression engine 与 canonical `EXEC`／`META` roots，但每个 Stage／Template 及 Flow invocation 都拥有明确的 Action scope。`output` 是 Action-local。`CASE`、`RUN` 与 `ACTIONS` root 只在可确定映射时作为兼容 alias 保留。`${...}` 读取 Context；`#{...}` 在 Action 允许的位置调用 Tool、DB facade 或 built-in。Action 只能读取当前 scope 中已经完成的 Action。

Flow `inputs`、`outputs`、调用端 `with` 以及专用的 `input`、小写 `actions`、`runtime` 和 `flow` root 均为非法。Flow 内的 `assign` 与内联 assign 一样写入 `EXEC.VARS`。Flow 内部 Action 只可在该 Flow scope 内通过 `${EXEC.ACTIONS.<internalActionId>.output...}` 读取；返回后 parent scope 会恢复。调用端需要的值必须显式发布到 `${EXEC.VARS.<name>}`。Flow 调用 Action 本身只提供标准状态结果，不会创建 `output.outputs`。

Action ID 只须在同一个 Stage／Template／Flow scope 内唯一。不同或重复的 Flow invocation 拥有新的 scope，因此可以复用内部 ID；同一 scope 内的重复 ID 仍会验证失败。内部 Action 全部跳过的已调用 Flow 为 PASS；Flow Action 自身 `runWhen` 为 false 时才是 SKIPPED。

Flow `use` 不支持动态选择。`runAlways`、warning impact、Flow timeout/retry、loop、动态 dispatch 和并行分支都不是 V3.4.0 能力。聚合优先级保持 `ERROR > INVALID > FAIL > PASS > SKIPPED`。
