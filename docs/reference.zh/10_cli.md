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
