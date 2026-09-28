## 05 資源與整合

Tool、DBHelper、MQHelper、SSHHelper 是同級 integration/resource 類型。SSHHelper 為 command-backed Tool 提供路由；它們最終收斂到 5.5 的 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
SSHHelper -> Tool SSH routing --------/
```

Resource ID 是 Template/expression 或 Tool group 所引用的 logical contract。Environment profile 可把相同 DB/MQ/SSH logical ID 綁定到不同 descriptor，無需修改 Action YAML。
