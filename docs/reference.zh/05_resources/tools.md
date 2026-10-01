### 7.2 Tool

Tool 是具名的 external 或 framework-native capability。Descriptor 必須二選一使用 command 或 call。

#### Command-backed Tool

Command Tool 使用 stdoutFormat 將外部 stdout 解析為型別化結果：

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat 支援 text、json、yaml、xml。ATT 只解析一次 stdout，再將 typed value 發布於 output.result。此欄位不控制人類可讀日誌或檔案輸出。Exit code、有界 stdout/stderr preview 和 process artifacts 都屬於 evidence。

#### Call-backed Tool

Call-backed Tool 呼叫 built-in 或支援的原生 DB/MQ/HTTP operation，其 native typed return value 發布於 output.result。Call-backed descriptor 不宣告 stdoutFormat。

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation 沒有 result.format/path/overwrite 契約。檔案持久化只由明確定義該 API 的 resource 負責；人類可讀表示屬於 Log 或配置的 evidence output。HTTP/MQ/SSH parsing 或 transfer 由各自 transport boundary 管理。

Action result 規則見[Action 與型別化值](../14_actions.md)；typed result/evidence 的區分見[Operation Result and Evidence](operation_result.md)。

### Tool 定義中的 `command` 表達式

Tool 的 `command` 也擁有獨立的受限 Context，只能引用該 Tool `arguments` 映射中聲明的鍵。canonical 文檔及新配置應使用 `${input.<argument>}`：

| 形式 | 含義 |
|---|---|
| `${input.requestText}` | canonical Tool 本地輸入引用 |
| `${TOOL.input.requestText}` | legacy 完整別名；會產生 `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${requestText}` | deprecated shorthand；僅在唯一對應已聲明參數時兼容，並產生遷移 warning |

`${TOOL.input.argument}` 與 `${argument}` 只有在名稱恰好對應當前 Tool 一個已聲明參數時才會接受，並產生 `CONTEXT_TOOL_INPUT_SHORTHAND`；`att validate` 會給出精確的 `${input.argument}` 替換。未聲明或有歧義的 shorthand 會報錯。command-backed 與 call-backed Tool 使用相同規則。

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

每個 YAML command list item 在 render 後仍是一個 atomic argv；值中含空格、引號或類似 shell 的字符也不會再次分詞。ATT 不會啟動本地 shell。

#### 引號、Context value 與 atomic argv

Tool call 內的引號屬於 ATT expression grammar，並不是 shell quote。外層 `'...'` 或 `"..."` delimiter 在調用前會移除；另一種引號是普通字符；與 delimiter 相同的引號可用反斜線 escape。Quoted value 內嵌 `${...}` 會做 interpolation；未加引號的 canonical Context path 則直接傳遞 typed value。

以下 Tool 會把每個輸入保持為一個 argv：

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${input.message}", "${input.sourceFile}"]
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

當 call 同時包含多層引號時，建議使用 YAML block scalar：

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

Child process 收到的三條 message 分別是 `Customer O'Reilly`、`status="READY"`，以及例如 `O'Reilly said "READY" for payment.payment.TC001`。Context value 自身包含任一種引號時，無需 caller 做 shell escaping，仍只佔一個 argv。

如果堅持把 call 寫成單行，還需額外處理獨立的 YAML escaping 層：

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

第一行是為 YAML double-quoted scalar escape 雙引號；第二行是為 YAML single-quoted scalar 把 apostrophe 寫成兩個。之後 expression engine 才會解析所得的 `#{...}`。

普通 process-backed Tool 不會讓 shell 重新解釋已解析輸入。Context value 內的 `$HOME`、`$(date)`、`a*.xml`、`|`、`>` 與引號都按字面傳遞。需要 shell-like behavior 時應使用經過審查的 wrapper；隨包提供的 `fpp.exehelper` 和 `fpp.loghelper` 只提供上文明確說明的 pathname expansion。

### Tool 定義中的 `call` 表達式

Call-backed Tool 使用相同的聲明參數理念，但保留 typed value，並只允許 pure built-in 與一個主要 DB query/scalar/update。`${input.customerId}` 來自外層 Tool call，不是 Case 全局變量；`CASE`／`ACTIONS` 等 root 在定義中不可見。Inline SQL 與 package-contained SQL file 內容都在此 scope render，測試數據仍應放在 `params` 並使用 JDBC `?`。

### Inline Tool descriptor fields

Global `tools` and Tool-group `tools` entries use the same Tool contract:

| Object | Allowed properties |
|---|---|
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `argName`, `argNameMode`, `delimit`, `x-*` |
