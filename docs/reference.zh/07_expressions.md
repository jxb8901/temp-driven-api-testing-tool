## 07 Expressions 與 Built-ins

### 統一 expression engine

ATT 的 runtime Template、Flow、Action、Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map、List 或 DocumentValue；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。Render 內容、Action description/assert、Log message/value、assign expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[動作與型別化值](14_actions.md)。

### Load execution ID initialization

ATT 不為 configuration 另設一套 non-runtime expression language。Load 的 execution.execIdFormat 使用同一套 ${...} / #{...} engine，並在 iteration initialization 求值一次。可用值受生命週期限制：EXEC.RUN_ID、timestamps、EXEC.INPUT、穩定 EXEC.LOAD identity，以及當時已初始化的 META branches。

EXEC.ID/EXEC.OUTPUT_DIR 尚不可用，因為 ID 會決定 workspace。EXEC.ACTIONS 和 invocation-scoped Flow/Tool/DB/MQ/HTTP metadata 尚不存在。Arrival-rate 沒有 EXEC.LOAD.USER_ID。只允許 deterministic、side-effect-free built-ins；external calls、seq.next()、random/clock/filesystem functions 都會被拒絕。

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 請省略 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

完整 META inventory、lifecycle 表格與 artifact navigation layout 見[Runtime 與 Context 模型](03_runtime_context.md)。ATT 3.6.0 沒有通用 configuration-expression model。

### `config.report.fileNamePattern`

该配置使用统一表达式引擎，但拥有独立的非 Case 作用域。它只支持一个大小写敏感的值引用：

| 占位符 | 值 |
|---|---|
| `${SUITE_NAME}` | 源工作簿 basename，去掉结尾的小写 `.xlsx` 后缀；例如 `testcase/payment_regression.xlsx` 变为 `payment_regression` |

配置字符串必须显式引用 `${SUITE_NAME}`，无论它用于文本插值还是内建函数参数。ATT 没有定义其他通用 non-runtime/configuration expression roots。call 内的裸 `SUITE_NAME` 会被拒绝。合法示例包括：

```yaml
report:
  fileNamePattern: "${SUITE_NAME}.result.xlsx"
```

以及：

```yaml
fileNamePattern: "result-${SUITE_NAME}.xlsx"
fileNamePattern: "ATT-${SUITE_NAME}-report.xlsx"
fileNamePattern: "${SUITE_NAME}-${SUITE_NAME}.xlsx"
fileNamePattern: "#{upper(${SUITE_NAME})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${SUITE_NAME})})}.xlsx"
```

但不支持如 `${RUN_ID}`、`${WORKBOOK_ID}`、`${ENVIRONMENT}`、`${EXEC.INPUT.caseId}` 等运行时值引用。

### Tool 定义中的 `command` 表达式

Tool 的 `command` 也拥有独立的受限上下文，只能引用该工具 `arguments` 映射中声明的键。canonical 文档及新配置应使用 `${input.<argument>}`：

| 形式 | 含义 |
|---|---|
| `${input.requestText}` | canonical 工具本地输入引用 |
| `${TOOL.input.requestText}` | legacy 完整别名；会产生 `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${requestText}` | deprecated shorthand；仅在唯一对应已声明参数时兼容，并产生迁移 warning |

`${TOOL.input.argument}` 与 `${argument}` 只有在名称恰好对应当前 Tool 一个已声明参数时才会接受，并产生 `CONTEXT_TOOL_INPUT_SHORTHAND`；`att validate` 会给出精确的 `${input.argument}` 替换。未声明或有歧义的 shorthand 会报错。command-backed 与 call-backed Tool 使用相同规则。

例如：

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
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
    stdoutFormat: yaml
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
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
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

内建函数通过 `#{...}` 调用。Canonical 名称使用 framework-owned `str.*`、`date.*`、`file.*`、`misc.*` 与 `seq.*` package；旧 flat 名称保留为兼容 alias。Tool group 同样以 `group.tool` 组成 package-like 调用名；配置 Tool 不得占用 built-in package root 或任何 canonical／legacy built-in 名称。

| 函数 | 目的 | 示例 |
|---|---|---|
| `seq.next` | 返回 run-scoped `Long`；可选名称及宽度用于独立计数或精确宽度的零填充文字 | `#{seq.next('payment', 10)}` |
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
| `file.exists/directoryExists` | 测试常规文件／目录 | `#{file.exists(${EXEC.INPUT.requestText})}` |
| `file.size/mkdirs` | 返回文件大小／创建目录树 | `#{file.size(${EXEC.INPUT.requestText})}` |
| `file.copy/move/delete` | 复制、移动、删除文件 | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `misc.string/number/boolean` | 类型转换与归一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默认值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 从布尔值选择两个值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 从输入中随机选择 | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | 将稳定 typed DB result 格式化为 SQL*Plus 风格文字 | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `misc.prettyPrint` | 将 Map/List/array/tree 确定性格式化为缩进文字 | `#{misc.prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

#### `seq.next` run-scoped 序列

`seq.next` 支援以下四種位置參數 overload（同一組參數亦可使用 `name`、`width` 具名傳入；不可混用具名與位置參數）：

| 呼叫 | 計數器 | 返回值 |
|---|---|---|
| `#{seq.next()}` | 預設序列 | 遞增的 Java `Long` |
| `#{seq.next('payment')}` | 名為 `payment` 的獨立序列 | 遞增的 Java `Long` |
| `#{seq.next(10)}` | 預設序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |
| `#{seq.next('payment', 10)}` | 名為 `payment` 的序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |

預設序列與每個具名序列互相獨立，且各自從 1 開始。狀態由單次 ATT Run 擁有：Run 內所有 Testcase／suite 共用計數器；Debug 的單次 execution 使用新 service；Load 的所有 workload 與並行 iteration 共用計數器。每個序列計數器均為 thread-safe，在該 Run 內發出唯一且單調遞增的值；並行排程不保證哪個 VU 取得哪個值。新 Run、新 Debug execution 或新 Load run 都會重新從 1 開始。不公開 `EXEC.SEQUENCES` Context node，也沒有 reset/current API。

| 模式 | 範例用法 | Scope 說明 |
|---|---|---|
| Testcase | 在 `assign` Action 中：`expression: "#{seq.next('payment', 10)}"` | 同一 Run 的連續 Cases 共用 `payment` 計數器。 |
| Debug | 在 `assign` Action 中：`expression: "#{seq.next()}"` | 新的單次 Debug execution 從 1 開始。 |
| Load | 在 target Template/Flow 的 `assign` Action 中：`expression: "#{seq.next('load-order', 10)}"` | Iterations 共用計數器；並行呼叫唯一，但不保證每個 VU 的固定分配順序。 |

`width` 必須是 1 至 1000 的整數。序列名稱必須是非空白文字；只有一個位置參數時，數字代表 `width`，字串代表序列名稱。超過兩個參數、混合具名與位置參數、無效參數型別、空白名稱、小數／零／負數／超出範圍的 width 都會報錯；diagnostic 會指出 `seq.next` 及錯誤的參數數量、型別或範圍。若補零後的數值位數超過 `width`，或底層 `Long` 計數器溢位，求值會明確失敗；ATT 不會截斷序列值，也不會默默超出指定寬度。

`misc.dbText` 只接受一个位置参数或具名 `value`。参数必须是直接 DB Action、DB expression 或 DB-backed Tool 返回的稳定 query／update result。它与DB `output.result` 的 text presentation 共用同一个确定性 formatter，并且没有 JDBC、transaction、connection 或 cache side effect。

`misc.prettyPrint`（alias：`prettyPrint`、`format.pretty`）接受一个位置参数或具名 `value`，递归格式化 Map、List、Iterable、array、scalar 与 null。Linked Map 保留插入顺序，其他 Map 按 key 排序；输出使用两个空格缩进，并带有循环和深度保护。它不会修改输入值。
