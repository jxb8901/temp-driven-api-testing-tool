### 14.1 Schema 與版本矩陣

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.9`（仍可讀取 v2.1–v2.8）|
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.1`（仍可讀取 v1.0） |
| HTTPHelper | `att-httphelper/v1.0` |
| SSHHelper | `att-sshhelper/v1.0` |
| Tool group | `att-tool-group/v2.8`（仍可讀取 v2.2、v2.6、v2.7）|
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.2`（舊 `renderAs`／`saveAs` 會被拒絕並提供遷移建議） |
| Flow | `att-flow/v3.2`（相容讀取：`att-flow/v3.0`–`v3.1`）|
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.1`（仍可讀取 v1.0） |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` 是 repository 的 authoritative catalog。現行 schema 位於 `schemas/`，保留的舊版位於 `schemas/history/`。`validate --package` 會檢查所有已註冊的現行及歷史資源；缺少或不安全的註冊項目會硬性失敗，不會略過或從 process CWD 載入。Compatibility 是 reader contract；新 authoring 應使用相應 feature 的 current schema。
