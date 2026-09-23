## 13 CI、打包與運維

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 10 维护者架构

本章解释用户通常不需要在编写 Case 时了解，但维护者在修改校验、执行、持久化或报表时需要了解的行为。

#### 所有权模型

```text
case 拥有有序阶段
stage 定义模板选择列并拥有阶段私有数据
当前行的选择器单元格命名要解析的模板
template 拥有有序动作
tool action 通过声明参数调用一个独立全局工具契约
```

持久化运行时树的权威根只有一个 `CASE`。如 `ACTIONS` 和 `TOOL` 这样的便利作用域不会创建替代持久化根。

#### 校验流水线

ATT 会先使用 Draft 2020-12 schema，然后做语义校验。随后解析工作簿映射、选择器、模板、负载、表达式、工具、参数契约、标识符、路径和包完整性。

包模式会发现配置根目录下所有内容。选中模式只校验执行所需的不可变依赖闭包。校验在外部工具或最终 run 发布前完成。

#### 执行与聚合

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

#### 运行生命周期

验证和计划完成后，ATT 会原子保留：

```text
<outputDirectory>/<RunID>/
```

证据直接写入其中，Action 执行期间即可检查。所有必需输出最终化后，ATT 写入 `COMPLETE` manifest，并原子替换 `latest-run.yaml`。中断运行保留在该 Run ID 目录中，但没有完成 manifest，因此不适用于 `report`、`build`、`rerun-failed` 或 latest-run 选择。已存在的 Run ID 会在执行前被拒绝；使用同一 ID 重试前，应先移动或清理未完成目录。

#### 进程安全

ATT 直接构造 argv，不使用隐式 shell。stdout 与 stderr 会并发读取，在内存中只保留有界 head/tail preview，并通过有界临时 spool 写入 Case 日志。临时 spool 会在日志写入或显式 `saveAs` 后删除；普通 run 不创建 `process-output` 文件或目录。证据仍记录原始 byte count 与 truncation flag。超时终止必须依据平台支持停止受管进程并保留相同的有界证据。结构化解析器会拒绝格式错误/歧义输入以及 XML 外部资源特性。

工作簿导入使用 Apache POI `DataFormatter` 处理普通单元格，刻意不创建 `FormulaEvaluator`；公式表达式而不是缓存结果进入 Context。

#### CI 与并行执行

| 并发操作 | 契约 |
|---|---|
| 两个 run 使用相同 Run ID | 原子目录保留只允许其中一个开始；另一个失败且不会覆盖证据。 |
| 多个 run 更新 `latest-run.yaml` | 每个 run 先写入完成 manifest；最后完成者赢得原子指针更新。完成顺序而非启动顺序决定 latest。 |
| `build` 与 `run` 同时执行 | Build 会固定一个已完成 latest-run/manifest 对，并忽略没有 `COMPLETE` manifest 的运行。 |
| `report` 与 `clean` 同时执行 | 此破坏性竞态不受支持。Report 会失败而不是产生部分结果；共享一个输出根时应串行化 report/archive/clean 作业。 |

并行作业若需要独立运行历史、清理或 latest-run 行为，应使用不同 `--output-dir`。

#### 路径与标识符安全

已校验的 Run ID 和 Case ID 会直接映射到目录名。每次写入都要针对预期根目录进行解析、规范化、解析相关现有符号链接，并验证严格包含。逻辑 CLI 标识符从不作为任意文件系统路径接受。

#### 可复现性与版本化输出

完成的清单会捕获运行时身份、有效输入、哈希、选中 Case、摘要和输出路径。校验 JSON、运行清单和 CI 汇总都具有显式 `schemaVersion`。JUnit XML 受 XSD 约束。使用者应验证声明版本，而非推断结构。

#### 维护者发布清单

- 运行完整自动化测试套件，并要求全部通过。
- 对代表性包运行 `validate --package`。
- 验证 CLI 与所有报表中的 FAIL/ERROR/INVALID 聚合与退出码。
- 验证 JSON/XML 解析、重复 XML 子节点、属性和命名空间。
- 验证超时和重试证据，包括耗尽重试和后续成功的情况。
- 验证 Run ID 冲突、原子完成、latest-run 更新和中断运行。
- 验证报表/build/clean 边界与并发命令行为。
- 验证 schema、示例、生成文档和本手册保持一致。
