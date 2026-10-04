# Resources 與 integrations

Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper 是同級 integration/resource 類型。SSHHelper 為 command-backed Tool 提供路由，也提供 `ssh.<helperId>.execute|upload` 及 remote filesystem operation；最後收斂到 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> SSH routing 或 Resource Helper /
```

Resource ID 是 Template/expression 或 Tool group 所引用的 logical contract。Environment profile 可把相同 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，無需修改 Action YAML。

小寫 `x-` 可停用 ATT 擁有的 resource 配置字段及 keyed entries；但不會移除 HTTP header 名稱或 DB parameters 等 user data。詳情見[Configuration](../configuration.md#使用-x-忽略或停用-att-配置項)。
