### 14.1 Schema and Version Matrix

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0`, `att-mqhelper/v1.1` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0` (older supported forms remain readable where compatible) |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` is the authoritative repository catalog. Compatibility is a reader contract; new authoring should use the current schema for the feature being authored.
