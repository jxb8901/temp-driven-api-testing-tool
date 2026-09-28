### 5.1 Tool

Tool 是具名的 external 或 framework-native capability。每個 Tool 必須二選一：**command-backed** 或 **call-backed**。現行 config 與 Tool Group schema 使用 `result.format`；現行 schema 不再接受舊 Tool `output` 欄位。

#### Command-backed Tool

Command-backed Tool 依 configured argv contract 在本機或已配置 SSH transport 執行。Argv list 會保留每個 item 的 argument boundary；scalar command 只會被 tokenize 成相同 internal argv model。一般 process-backed Tool 不會隱式啟動 shell，也不會自動 wildcard expansion。Stdout/stderr、exit code、timeout 和 process diagnostic 屬 evidence；非零 process exit 本身不等於 assertion FAIL，除非 Action contract 明確這樣判定。

Command-backed Tool 必須宣告 `result.format: text|json|yaml|xml`。此 Tool-level format 決定如何把 stdout 解析成型別化主結果 `output.result`（例如 JSON stdout 解析成 map），不提供 raw bytes 模式。有界 process preview 與完整串流 capture 仍是獨立 evidence。

```yaml
tools:
  queryTool:
    name: Query tool
    description: Parse JSON stdout as a typed result
    command: [./tools/query.sh]
    result: {format: json}
    arguments: {}
```

Script、CLI、SSH、third-party executable 適合 command-backed Tool。

#### Call-backed Tool

Call-backed Tool 執行 typed framework-native call，不會把 typed value 轉成 process string。支援 built-in，以及 primary DB、MQHelper 或 HTTPHelper operation；call-backed MQ/HTTP 必須是 `type: tool` Action 的 primary call。呼叫原生返回型別會被保留。可選 Tool-level `result.format` 只是在 Action 將結果寫入檔案或 Case log 時採用的預設序列化格式，不會重新解析或改變原生值。若不需要序列化預設，call-backed Tool 可不宣告 `result.format`。

```yaml
tools:
  requestPayment:
    name: Request payment
    description: Invoke the selected payment HTTP helper
    call: "#{http.paymentApi.post(path='/v1/payments', body=${input.request})}"
    result: {format: json}
    arguments:
      request:
        name: Request
        description: Typed request body
        required: true
```

兩種 backend 都發布相同 public Action envelope。Active Action 使用 `${output.result}`，完成後使用 `${EXEC.ACTIONS.<id>.output.result}`。Final operation evidence 位於 `output.evidence`；retry 的 per-attempt evidence 保留在 `output.attempts[n].evidence`。

Action 可選的 `result.format` 僅支援 `text|json|yaml|xml`，控制檔案／console 序列化，不改變內存結果。`result.path` 可省略；省略時不建立 artifact。`path: console` 會把序列化值寫入 Case log。Post-operation evidence collector 在 primary operation 後、該次 assertion 前執行；collector failure policy 不會取代 primary `result`。
