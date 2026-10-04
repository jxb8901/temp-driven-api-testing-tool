# Resources and integrations

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are peer integration/resource types. SSHHelper routes command-backed Tools and exposes `ssh.<helperId>.execute|upload` plus remote filesystem operations. It converges on the common operation-result/evidence contract.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> SSH routing or Resource Helper /
```

Resource IDs are logical contracts referenced by Templates/expressions or Tool groups. Environment profiles may bind the same DB/MQ/HTTP/SSH logical ID to different descriptors without changing Action YAML.

The lowercase `x-` prefix can disable fields and keyed entries in ATT-owned resource configuration. It does not strip user data such as HTTP header names or DB parameters; see [Configuration](../configuration.md#ignore-or-disable-att-owned-configuration-with-x).
