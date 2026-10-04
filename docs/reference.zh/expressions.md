# Expressions 與 built-ins

## 統一 Expression engine

ATT 的 runtime Template、Flow、Action、Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map 或 List；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。Project-file 內容、Action description/assert、Log message/value、assign expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[Action 與型別化值](actions.md)。

## Project-file string expression

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

## Testdata input mapping 語法

Testdata reference 會在準備 Case/Stage、Debug 或 Load input map 時解析，不屬於一般 `${...}` / `#{...}` expression engine。使用 `@{id}` 保留所選 record 的原生型別；`@{id.object.path}` 或 `@{id.items[0]}` 可讀取巢狀值；scalar interpolation 可組合文字。同一 mapping/lifetime 中，一個 logical ID 只選一次，因此該 mapping 內對同 ID 的引用會得到同一筆 record。Interpolation 不接受 null、map 或 list。`${...}` 可讀取已初始化 Context，但不能讀取 `EXEC.INPUT`，因為 input 建構不能依賴自身。Reusable Template、Flow 和 Tool definition 不可直接包含 testdata marker；它們只會使用已解析的 `EXEC.INPUT` 值。

Descriptor 和 generated record 語法見[Testdata Registry 與 Input Mapping](test-authoring.md)。

## 操作符

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

## 內建函數

只有作者直接撰寫的 file-expression node 才會求值。Context value、Tool result 和 file output 即使包含 `&{...}`，亦維持 literal String。檔案內的 Context path 和 call 依 enclosing Action 的一般 ordering、scope 和 resource validation 規則驗證。V1 在 Run、Debug、validation 和 Load snapshot discovery 都拒絕 project-file 內容中的巢狀 `&{...}`，包括 `#{...}` argument 內的 locator。

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
| `misc.string/number/boolean` | 類型轉換與歸一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默認值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 從布爾值選擇兩個值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 從輸入中隨機選擇 | `#{misc.randomChoice('A', 'B', 'C')}` |

### `Seq.next` Run-scoped 序列

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





## Resource helper methods

已配置的 `db.*`、`mq.*`、`ssh.*` 和 `http.*` 使用相同的 `#{...}` 語法，但它們是會存取外部系統並返回 typed result 的 resource operation，並非 pure built-in。MQ、SSH 和 HTTP call 必須是 `type: tool` Action 的主要 `call`。DB `query` 和 `scalar` 亦可用於支援的 expression 欄位；`db.update` 必須是 `type: tool` Action 的主要 call。這些操作不可用於 `retry.when`；該欄位只接受 deterministic pure built-in。以下參數均使用具名形式；省略 optional 參數時會採用 helper 配置的預設值。完整 resource/result 契約見各 helper 頁面。

### DBHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `db.<id>.query` / `db.<id>.scalar` | `sql: String`；optional `params: List` **或** `parameters: Map<String, value>`（兩者互斥；預設沒有 bind value）。`sql` 可為 inline SQL 或返回 String 的 project-file expression。 | `#{db.orders.query(sql='select status from orders where id = :id', parameters={id: ${EXEC.INPUT.orderId}})}` |
| `db.<id>.update` | 與 `query` 相同的參數和型別；只可作為 Tool Action 的主要 call。 | `#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}` |

`query` 返回 typed rows，`scalar` 返回 scalar result，`update` 返回更新結果。SQL binding、transaction 和 result 詳情見 [DBHelper](resources/dbhelper.md)。

### MQHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `mq.<id>.send` | `payload: String、byte[] 或 structured Map/List`（必填）；`queue: String` 在未配置 request queue 時必填。Optional `requestFormat: text\|json\|yaml\|xml`（Map/List 必填）、`instance: String`（選擇已配置的 physical instance）。 | `#{mq.payment.send(queue='PAYMENT.REQUEST', payload=${EXEC.VARS.requestText})}` |
| `mq.<id>.receive` | `queue: String` 在未配置 reply queue 時必填。Optional `waitMs: Integer`（預設 10,000 ms 或 helper setting）、`correlationId: String`、`responseFormat: text\|json\|yaml\|xml`（預設 `text` 或 helper setting）、`instance: String`。 | `#{mq.payment.receive(queue='PAYMENT.REPLY', waitMs=5000, responseFormat='json')}` |
| `mq.<id>.request` | `payload` 同上；有效的 `requestQueue` 和 `replyQueue` 必須存在（可由 helper defaults 提供）。Optional `requestFormat` 同上、`waitMs: Integer`（預設 10,000 ms 或 helper setting）、`responseFormat`（預設 `text` 或 helper setting）、`instance: String`。 | `#{mq.payment.request(requestQueue='PAYMENT.REQUEST', replyQueue='PAYMENT.REPLY', payload=${EXEC.VARS.requestText}, waitMs=5000, responseFormat='xml')}` |

`requestFormat` 只用於 structured payload；String 會原樣傳送，不可與它同時使用。`waitMs` 範圍為 0–3,600,000。已配置 queue defaults 和 typed reply 詳情見 [MQHelper](resources/mqhelper.md)。

### SSHHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `ssh.<id>.execute` | `command: String`；optional `stdoutFormat: text\|json\|yaml\|xml`（預設 `text`）、`timeoutMs: Integer`（預設 60,000 ms，且受 Action deadline 限制）。 | `#{ssh.application.execute(command='systemctl is-active example.service', stdoutFormat='text', timeoutMs=5000)}` |
| `ssh.<id>.upload` | `remotePath: String`、`payload: String 或 byte[]`；optional `overwrite: Boolean`（預設 `true`）、`timeoutMs: Integer`（預設 60,000 ms）。 | `#{ssh.application.upload(remotePath='/srv/app/request.json', payload=${EXEC.VARS.requestText}, overwrite=true)}` |
| `ssh.<id>.stat` / `ssh.<id>.mkdirs` / `ssh.<id>.delete` | `remotePath: String`；optional `timeoutMs: Integer`（預設 60,000 ms）；`delete` 另外接受 `missingOk: Boolean`（預設 `false`）。 | `#{ssh.application.stat(remotePath='/srv/app/result.json')}` |
| `ssh.<id>.move` | `sourcePath: String`、`targetPath: String`；optional `overwrite: Boolean`（預設 `false`）、`timeoutMs: Integer`（預設 60,000 ms）。 | `#{ssh.application.move(sourcePath='/srv/app/out.json', targetPath='/srv/app/archive/out.json', overwrite=false)}` |

SSH operation 只接受具名參數；path 是 remote path，upload 接受內容而非 local file path。Path 限制、返回值及 timeout/retry 行為見 [SSHHelper](resources/sshhelper.md)。

### HTTPHelper

`http.<id>.get(...)` 和 `http.<id>.post(...)` 由 method 名稱決定 HTTP method；`http.<id>.request(...)` 另需 `method: String`（`GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`HEAD` 或 `OPTIONS`）。三種 method 都接受以下 optional 具名參數：

| 參數 | 型別及預設值 | 說明 |
|---|---|---|
| `path` | `String`，預設 `''` | 相對於已配置的 base URL；不可提供 absolute URL、query string 或 fragment。 |
| `query` | `Map<String, value>`，optional | Iterable value 會成為重複的 query parameter。 |
| `headers` | `Map<String, value>`，optional | 與已配置 headers 合併；value 會轉成文字。 |
| `body` | `String`、`byte[]` 或 structured Map/List，optional | GET 和 HEAD 不接受 body。Map/List 必須提供 `requestFormat`。 |
| `requestFormat` | `text\|json\|yaml\|xml`，optional | 只在 Map/List body 時必填；String/byte[] 原樣傳送。 |
| `contentType` | `String`，optional | 覆蓋 request Content-Type header。 |
| `responseFormat` | `auto\|text\|json\|yaml\|xml`，預設 helper 設定（預設為 `auto`） | `auto` 會按 response Content-Type 決定格式。 |
| `connectTimeoutMs`、`readTimeoutMs`、`connectionRequestTimeoutMs` | `Integer` 1–3,600,000 ms，optional | 覆蓋相應 helper timeout；三者 descriptor default 依次為 5,000 ms、30,000 ms、5,000 ms。 |
| `followRedirects` | `Boolean`，預設 helper 設定（預設為 `false`） | 最多跟隨 runtime 限制數量的 redirect。 |

示例：`#{http.payment.post(path='/v1/payments', query={dryRun: true}, headers={Accept: 'application/json'}, body=${EXEC.INPUT.request}, requestFormat='json', responseFormat='json')}`。Timeout 範圍、body encoding 及 response parsing 見 [HTTPHelper](resources/httphelper.md)。

## Expression scope 與錯誤

Expression language 由本頁定義；可用 roots 與求值時機由欄位的 semantic owner 定義：[Tool command/call](resources/tools.md)、[Load execIdFormat 與 vars](execution-modes/load.md)、[Debug vars](execution-modes/debug.md)、[report filename](configuration.md)。`${path?}` 只允許缺少的 map/list path 回傳 null；語法錯誤與非法 scope 仍會失敗。Expression syntax 或缺少的必需 Context path 會提供結構化 diagnostic；見[Validation](validation-diagnostics.md)。

已移除 presentation-only `dbText`／`misc.dbText`、`prettyPrint`／`misc.prettyPrint`／`format.pretty` 和所有 local `file.*`／legacy file alias。DB result 保持 typed；顯示時改用 Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` 加 `format: sqlplus`，Map/List 則使用 `format: json` 或 `yaml`。Project content 使用 `&{...}`，並將 String 傳入 HTTP body、MQ payload 或 SSH upload payload。SSHHelper upload 只接受 content；native SSH download 已移除。ATT local output 由 framework 管理。移除的 API 會提供 migration diagnostic。

## Retry condition 的生命週期

`retry.when` 在當前 attempt 完成後、retryOn 符合且尚有 attempt 時才評估。`output.*` 綁定當前 result/evidence/diagnostic 及 `output.attempt`。Normal Boolean typing、strict/optional Context path 契約均適用。僅允許 deterministic pure built-in；external、file、sequence、random 及 current-time operation 禁止。詳見 [Actions retry](actions.md)。
