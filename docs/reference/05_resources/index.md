## 07 Resources and Integrations

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are peer integration/resource types. SSHHelper routes command-backed Tools. They converge on the common operation-result/evidence contract.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> Tool SSH routing --------/
```

Resource IDs are logical contracts referenced by Templates/expressions or Tool groups. Environment profiles may bind the same DB/MQ/HTTP/SSH logical ID to different descriptors without changing Action YAML.
