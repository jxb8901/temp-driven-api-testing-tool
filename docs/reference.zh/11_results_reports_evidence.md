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

### Tool evidence collector 失败

Evidence collector 是 operation 完成后的 observability，不是 primary Tool result。使用 `onFailure: continue` 时，primary Action 可以维持 `PASS`，而 collector 会独立记录为 `ERROR`：

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

请查看 `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`（或等价的 `ACTIONS` compatibility view）。Record 包含 `status`、`success`、`invocationId`、`result`、`error`，以及 bounded/redacted 的 underlying operation `evidence`；operation 有提供 structured diagnostics 时会在 `operationDiagnostic` 保留 native operation diagnostic。`diagnostic` 则记录 collector failure 及其 source file/field。`error.message` 会从 underlying exception、operation status/exit code 或安全 fallback 填入。若 executor 有提供，SSH helper/instance、command、exit code、bounded stderr、MQ reason code、HTTP status、parser diagnostic 和 timeout detail 等 resource identity/field 会留在 `evidence`。 Tool exception 的 public projection 会省略 raw input、payload、argv 和 output field，并从保留文字中 redact input value；每个保留的 text field 限制为 1024 字元。`inputOmitted` 和 truncation flag 表示省略或截断的 evidence。 Free-form message、stderr、per-instance error 和 cleanup warning 会 redact string、DocumentValue text 和 array input；byte array 会按 UTF-8、Base64、hex 和 Java decimal array rendering 处理。Structured status、category 和 resource identity 只做长度限制。SSH fan-out 会保留最多 64 个 instance 的 bounded metadata、error 和 stderr，优先保留失败 instance；`instanceCount` 和 `instancesTruncated` 表示总数和省略的 instance。

有 retry 时，请查看 `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`。即使后一个 attempt 成功，较早的 failed collector record 仍会保留；top-level collector record 代表最后／胜出的 attempt。使用 `onFailure: stop` 时，Action 可以失败，但其 diagnostic 仍会包含 collector root-cause message 和保留的 evidence。同一 structured record 也会写入 `case.log` 的 `EVIDENCE <action> attempt=<n> collector=<id>` block，因此不必打开 internal exception trace，便可看到基本 resource、category、message、exit code 和 bounded stderr。既有 capture limit 与 secret redaction 仍然有效；collector wrapper 不会开放无上限 raw output。

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
