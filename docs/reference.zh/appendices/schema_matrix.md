### 14.1 Schema 與版本矩陣

ATT 3.6.0 現行 schema：

| Artifact | 現行 schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.3 |
| Flow | att-flow/v3.3 |
| Debug input | att-debug/v1.0 |
| Load scenario | att-load/v1.2 |
| Load summary | att-load-summary/v1.0 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |

本次調整的 resource/config schema 舊版本已移至 schemas/history，僅供歷史參考，不是 active execution contract。Unsupported version 會在 validation 失敗並提供 migration guidance。schemas/catalog.yaml 是 repository authoritative catalog。Package validation 會驗證已註冊的 schema resource 本身；封存不代表舊版本仍有 runtime compatibility。
