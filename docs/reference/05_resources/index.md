## 05 Resources and Integrations

Tool, DBHelper and MQHelper are peer integration/resource types. They have different descriptors and lifecycle rules but converge on the common operation-result/evidence contract in 5.4.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource IDs are logical contracts referenced by Templates/expressions. Environment profiles may bind the same DB/MQ logical ID to different descriptors without changing Action YAML.
