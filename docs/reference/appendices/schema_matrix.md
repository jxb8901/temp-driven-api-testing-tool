### 14.1 Schema and Version Matrix

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.7` (v2.6 remains readable) |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0`, `att-mqhelper/v1.1` |
| SSHHelper | `att-sshhelper/v1.0` |
| Tool group | `att-tool-group/v2.7` (v2.6 remains readable) |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.1` (`renderAs`/`saveAs` are rejected with migration suggestions) |
| Flow | `att-flow/v3.1` (legacy read: `att-flow/v3.0`) |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` is the authoritative repository catalog. Compatibility is a reader contract; new authoring should use the current schema for the feature being authored.
