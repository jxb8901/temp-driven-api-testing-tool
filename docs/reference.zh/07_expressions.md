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

`EXEC.MODE` 在普通 run 中是 `testcase`，standalone debug 中是 `debug`，load iteration 中是 `load`。`EXEC.LOAD` 仅在 `EXEC.MODE=load` 时存在；普通 TestCase 和 debug execution 不会物化它。`EXEC.INPUT`、`EXEC.VARS` 与各 scope 内的 `EXEC.ACTIONS` 是所有 execution mode 共用的 runtime state，不是平行副本。TestCase adapter 会把当前 Stage 的 caller/input values 适配到 `EXEC.INPUT`；同名时 Stage value 在该 Stage 期间优先，Stage 结束后恢复 Case-level value。`EXEC.ID`、`EXEC.MODE`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.VARS` 和 `EXEC.ACTIONS` 等框架字段不能被 Case 或 sidecar input 覆盖。不存在 `EXEC.TOOL`、`EXEC.DB`、`EXEC.MQ`、`EXEC.OUTPUT`、`EXEC.CALL`、`EXEC.INVOCATION`、`EXEC.STAGE` 或 `EXEC.STAGES`：helper/resource state 保持 internal，根层 `TOOL.*`／`DB.*` 只可作为 compatibility 或 transient view；当前 Action 使用 local `output`，完成后只在其所属 scope 通过 `EXEC.ACTIONS` 发布。Flow 返回后 parent scope 会恢复，跨 scope 值必须写入 `EXEC.VARS`。Stage/template 的 status、timing 和 history 属于 execution result/evidence model，并由旧的 `CASE.STAGES` view 提供读取。严格的 `${EXEC.LOAD.<field>}` 在非 load mode 会 validation error，可选的 `${EXEC.LOAD.<field>?}` 会解析为空；3.5.2 的 `att-load/v1.0` adapter 会按下述 contract 增加 load-only 的 `EXEC.LOAD`。

### Load V1 Context（3.5.2）

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

Scenario `inputs` 只会复制到 `EXEC.INPUT.*`；可复用的 Template、Flow 和 Tool 必须使用 canonical input tree、`EXEC.VARS.*`、`EXEC.ACTIONS.*` 及当前 `output.*`。`META.SOURCE` 只标识 load scenario 的 type、名称和 path；iteration identity 保留在 `EXEC.ID` 与 `EXEC.LOAD.*`，并排除 secrets。根层 `LOAD.*`、`EXEC.OUTPUT`、`EXEC.CALL` 和 `EXEC.INVOCATION` 不是公开的 load API。完整的 closed／arrival-rate 配置、CLI override、target 形式、threshold、evidence 和 validation 例子见 [`examples/load/README.md`](../../examples/load/README.md)。

`att load` 会在 scheduler 启动前完成 scenario 和 target validation，再选择两个 scheduler 之一。closed mode 为 Virtual User 保持稳定 identity，等待 target 完成后才进入 think time 和下一次 iteration；arrival-rate mode 使用 absolute planned due time，`maxConcurrent` 已满时记录 generator `dropped`，不排队，也不算作 SUT failure。两个 scheduler 都只发布 compact events，由 bounded-memory metrics 汇总，并写入独立的 `output/load/<runId>/load-summary.json`、`load-summary.yaml` 和 `report/index.html`。warm-up 是真实 traffic，但默认不计入 measured threshold aggregates；成功 iteration 默认只保留 metrics，配置 sampling 后只为有界 sampled success 创建带 `case.log` 和 `case.yaml` 的 physical iteration workspace；失败则在保留 diagnostic 时 lazy 创建该 workspace。证据链接写入 load run 下的 `samples/` 或 `failures/`，不会污染普通 functional run artifacts。

最短的端到端 smoke 命令如下：

```sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

[`examples/load/README.md`](../../examples/load/README.md) 是维护中的可复制参考，涵盖 Template、Flow、Tool、DB/MQ pool sizing、threshold、evidence、CLI override 和非法配置。`LoadAcceptanceTest` 会先校验全部六个例子的 schema 与 dependencies，再启动真正的 `att.FrameworkRunner load` CLI 执行短版 closed 与 arrival-rate scenario，并检查持久化 JSON、YAML 和离线 HTML report。

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
