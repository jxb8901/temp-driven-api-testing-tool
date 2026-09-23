## 05 資源與整合

Tool、DBHelper、MQHelper 是同級 integration/resource 類型。它們的 descriptor 與 lifecycle rule 不同，但最後都收斂到 5.4 的 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource ID 是 Template/expression 所引用的 logical contract。Environment profile 可以把相同 DB/MQ logical ID 綁定到不同 descriptor，而不需要修改 Action YAML。
