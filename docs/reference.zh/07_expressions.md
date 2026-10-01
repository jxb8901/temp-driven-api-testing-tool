## 05 Expressions 與 Built-ins

### 統一 expression engine

ATT 的 runtime Template、Flow、Action、Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map 或 List；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。Project-file 內容、Action description/assert、Log message/value、assign expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[Action 與型別化值](14_actions.md)。

### Project-file String expression

`&{path}` 是 typed project-file expression。它只解析一個 regular UTF-8 檔案，並且一定回傳 `String`；不會推斷 document format、解析副檔名、展開 glob 或建立 output file。Path 相對於 canonical ATT project root。Descriptor-relative 的 `./` 與 `../` 只有在 canonical target 仍位於該 root 內時才允許。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白、glob syntax 及 dynamic locator 都會在 validation 失敗。

Standalone value 或嵌入較大 expression 時，請使用 YAML string：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

檔案內的 `${...}` 與 `#{...}` 會在 file value 使用時編譯及求值。Run 與 Debug 會 cache compiled plan，並在 file fingerprint 改變時失效；Load 會為 scenario freeze 已驗證的 file identity、content 及 compiled plan。File output 不會再被當作新的 expression source 解析。

### 操作符

支持的斷言操作符有：

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

`like` 是大小寫不敏感的操作符關鍵詞，但規範寫法使用小寫。它匹配完整值，並使用 SQL 風格通配符：

- `%` 匹配零個或多個字符
- `_` 匹配恰好一個字符
- 匹配本身是大小寫敏感的

### 內建函數

內建函數通過 `#{...}` 調用。Canonical 名稱使用 framework-owned `str.*`、`date.*`、`file.*`、`misc.*` 與 `seq.*` package；舊 flat 名稱保留為兼容 alias。Tool group 同樣以 `group.tool` 組成 package-like 調用名；配置 Tool 不得佔用 built-in package root 或任何 canonical／legacy built-in 名稱。

| 函數 | 目的 | 示例 |
|---|---|---|
| `seq.next` | 返回 run-scoped `Long`；可選名稱及寬度用於獨立計數或精確寬度的零填充文字 | `#{seq.next('payment', 10)}` |
| `str.upper/lower/trim` | 大小寫與首尾空白處理 | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.ltrim/rtrim` | 去除前導／尾隨空白 | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | 返回文本長度 | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | 拼接參數 | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr/indexOf` | 截取子串／返回位置 | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.contains/startsWith/endsWith` | 測試字面包含、前綴、後綴 | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.replace` | 字面替換 | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad/rpad` | 左／右填充 | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | 重復值 | `#{str.repeat(3, '9')}` |
| `date.sysdate/systimestamp` | 返回系統日期／時間戳 | `#{date.sysdate('yyyyMMdd')}` |
| `date.format` | 格式化 ISO 日期 | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | 日期增減 | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `file.exists/directoryExists` | 測試常規文件／目錄 | `#{file.exists(${EXEC.INPUT.requestText})}` |
| `file.size/mkdirs` | 返回文件大小／創建目錄樹 | `#{file.size(${EXEC.INPUT.requestText})}` |
| `file.copy/move/delete` | 復制、移動、刪除文件 | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `misc.string/number/boolean` | 類型轉換與歸一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默認值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 從布爾值選擇兩個值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 從輸入中隨機選擇 | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | 將穩定 typed DB result 格式化為 SQL*Plus 風格文字 | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `misc.prettyPrint` | 將 Map/List/array/tree 確定性格式化為縮進文字 | `#{misc.prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

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

`misc.dbText` 只接受一個位置參數或具名 `value`。參數必須是直接 DB Action、DB expression 或 DB-backed Tool 返回的穩定 query／update result。它與 DB `output.result` 的 text presentation 共用同一個確定性 formatter，並且沒有 JDBC、transaction、connection 或 cache side effect。

`misc.prettyPrint`（alias：`prettyPrint`、`format.pretty`）接受一個位置參數或具名 `value`，遞歸格式化 Map、List、Iterable、array、scalar 與 null。Linked Map 保留插入順序，其他 Map 按 key 排序；輸出使用兩個空格縮進，並帶有循環和深度保護。它不會修改輸入值。

### Expression scope 與錯誤

Expression language 由本章定義；可用 roots 與求值時機由欄位的 semantic owner 定義：[Tool command/call](05_resources/tools.md)、[Load execIdFormat 與 vars](04_execution_modes/load.md)、[Debug vars](04_execution_modes/debug.md)、[report filename](09_configuration.md)。`${path?}` 只允許缺少的 map/list path 回傳 null；語法錯誤與非法 scope 仍會失敗。Expression syntax 或缺少的必需 Context path 會提供結構化 diagnostic；見[Validation](12_validation_diagnostics.md)。
