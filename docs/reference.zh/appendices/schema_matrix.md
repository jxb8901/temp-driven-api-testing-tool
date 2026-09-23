### 14.1 Schema 與版本矩陣

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0`（相容的舊格式仍可讀） |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` 是 repository 的 authoritative catalog。Compatibility 是 reader contract；新 authoring 應使用相應 feature 的 current schema。
