## 07 Resources 與 Integrations

Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper 是同級 integration/resource 類型。SSHHelper 為 command-backed Tool 提供路由，也提供 `ssh.<helperId>.execute|upload|download` Resource Helper operation；它們最終收斂到 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> SSH routing 或 Resource Helper /
```

Resource ID 是 Template/expression 或 Tool group 所引用的 logical contract。Environment profile 可把相同 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，無需修改 Action YAML。
