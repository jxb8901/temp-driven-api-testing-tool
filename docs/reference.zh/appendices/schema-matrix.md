# Appendix A — schema 與 version matrix

現行 active schemas（由版本相符 ATT Engine JAR 內嵌 registry 提供，repository `schemas/catalog.yaml` 為其 build input）：

| Artifact | 現行 schema |
|---|---|
| 排程建立 | att-schedule/v2 |
| RUN 定義 | att-execution/v1 |
| Global configuration | att-config/v2.13 |
| Package Manifest | att-package/v1 |
| Testdata descriptor | att-testdata/v1.0 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.3 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.2 |
| Load scenario | att-load/v1.9 |
| Load Template | `att-load-template/v1` | `schemas/att-load-template-v1.schema.json` |
| Load bindings | `att-load-bindings/v1` | `schemas/att-load-bindings-v1.schema.json` |
| Load summary | 直接 Engine／CLI：legacy summary contract (schema under `schemas/history/`)；封存報告：att-load-summary/v1.3 |
| LOAD report detail files | att-load-detail-manifest/v1、att-load-detail-reference/v1、att-load-detail-series/v1、att-load-workload-detail/v1 |
| Run manifest | att-run/v2.1 |
| 舊版 Package validation JSON | `att-validation/v2.1` |
| 精簡 CLI 階層式 validation report | `att-validation/v1` |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

本次調整的 resource/config schema 舊版本位於 `schemas/history/`，除非嵌入的 Engine registry 登錄且支援，否則僅供歷史參考，不是 active execution contract。Unsupported version 會在 validation 失敗並提供 migration guidance。Repository `schemas/catalog.yaml` 僅為 build input；runtime 唯一權威來源是版本相符 ATT Engine JAR 內嵌 registry。Runtime 會從 classpath 讀取 schema files，並可在 private per-user temporary directory 建立經驗證、以內容 SHA-256 定位的相容快取。快取會保留目前及緊接上一版 registry 供 rollback；只有取得排他 OS lock 並確認沒有 Worker 使用後，才會刪除較舊版本。若清理中斷，下一次初始化會重試。不完整或遭竄改的快取只會在確認沒有程序持有 use lease 後重建；若仍有程序使用，validation 會 fail closed，並於 Worker 結束後重試。Validation revision 會綁定 Engine/registry identity、Package files、shared config files 及選定 Environment；Worker 處理請求前會驗證 Server 傳入的 registry identity。`ATT_HOME/schemas/` 及 package-local `schemas/` 不是 runtime 來源，也不能覆寫 registry。Package validation 會驗證 Engine 登錄的 schema resources；封存 schema 不會自動成為可用的 runtime contract。

<!-- att-docs:historical -->
Load v1.6 及 MQHelper v1.2 是 loader 支援的相容歷史 schema；既有 scalar routing 及 legacy evidence policy 維持。新路由／配額欄位須使用 active schema。
<!-- /att-docs:historical -->
