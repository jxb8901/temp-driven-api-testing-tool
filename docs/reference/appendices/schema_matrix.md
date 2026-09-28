### 14.1 Schema and Version Matrix

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.9` (v2.1–v2.8 remain readable) |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.1` (`v1.0` remains readable) |
| HTTPHelper | `att-httphelper/v1.0` |
| SSHHelper | `att-sshhelper/v1.0` |
| Tool group | `att-tool-group/v2.8` (v2.2, v2.6, and v2.7 remain readable) |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.2` (`renderAs`/`saveAs` are rejected with migration suggestions) |
| Flow | `att-flow/v3.2` (legacy read: `att-flow/v3.0`–`v3.1`) |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.1` (`v1.0` remains readable) |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` is the authoritative repository catalog. Current schemas live in `schemas/`, retained older schemas in `schemas/history/`. `validate --package` checks every registered current/history resource; a missing or unsafe registration is a hard error and is never skipped or loaded from the process CWD. Compatibility is a reader contract; new authoring should use the current schema for the feature being authored.
