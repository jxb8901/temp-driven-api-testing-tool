### 5.1 Tool

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

Tool invocation 沒有 result.format/path/overwrite 契約。檔案持久化只由明確定義該 API 的 resource 負責；人類可讀表示屬於 Log 或配置的 evidence output。HTTP/MQ parsing 由 transport boundary 管理。

Action result 規則見[動作與型別化值](../14_actions.md)；typed result/evidence 的區分見[Operation Result and Evidence](operation_result.md)。