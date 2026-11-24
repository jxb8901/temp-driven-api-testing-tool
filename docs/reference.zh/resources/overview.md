# Resources 與 integrations

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是同級 Resource 類型。SSHHelper 可路由 command-backed Tool，並透過共通的 operation-result/evidence contract 提供 `ssh.<helperId>.<operation>` 操作。

```text
Tool       -> process/call operation --\
DBHelper   -> JDBC operation ----------+--> Action output
MQHelper   -> MQ operation ------------/
HTTPHelper -> HTTP operation ----------/
SSHHelper  -> SSH operation -----------/
```

Resource ID 是 Flow、Action 和 expression 引用的 logical contract。Environment profile 可將相同的 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，而不需修改 Action YAML。

小寫 `x-` prefix 可停用 ATT-owned Resource configuration 中的欄位和 keyed entries，但不會移除 HTTP header name 或 DB parameter 等 user data；詳見[Configuration](../configuration.md)。
