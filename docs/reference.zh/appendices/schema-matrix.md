# Appendix A — schema 與 version matrix

現行 schemas 由版本相符 ATT Engine JAR 內嵌 registry 提供；repository 的 `schemas/catalog.yaml` 是其 build input。

| Artifact | 現行 schema |
|---|---|
| 排程建立 | `att-schedule/v2` |
| Native RUN 定義 | `att-execution/v2` |
| Native Test Case Definition | `att-testcase/v1` |
| Global configuration | `att-config/v2.15` |
| Package Manifest | `att-package/v2` |
| Testdata descriptor | `att-testdata/v1.0` |
| DBHelper | `att-dbhelper/v2.6` |
| MQHelper | `att-mqhelper/v1.3` |
| HTTPHelper | `att-httphelper/v1.1` |
| SSHHelper | `att-sshhelper/v1.0` |
| Package Tool | `att-tool/v1` |
| Package Tool Group | `att-tool-group/v3.0` |
| Flow | `att-flow/v3.6` |
| Debug input | `att-debug/v1.2` |
| Load scenario | `att-load/v1.9` |
| LOAD observability binding | `att-load-observability/v1` |
| SUT Metrics Data Source | `att-sut-metrics-source/v1` |
| SUT Metric Source acquisition and output mapping | `att-sut-metrics-metric-source/v1` |
| SUT logical Metric Group | `att-sut-metrics-group/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v2` |
| SUT Metrics execution binding | `att-sut-metrics-snapshot/v1`、`att-sut-metrics-snapshot/v2` |
| Server-private SUT Metrics execution payload | `att-sut-metrics-execution-payload/v2` |
| SUT Metrics collection manifest | `att-sut-metrics-manifest/v1` |
| SUT Metrics manifest integrity history | `att-sut-metrics-manifest-integrity/v1` |
| SUT Metrics normalized observation | `att-sut-metrics-observation/v1`、`att-sut-metrics-observation/v2` |
| SUT Metrics series data | `att-sut-metrics-series/v1` |
| SUT Metrics threshold evaluation record | `att-sut-threshold-evaluation/v1` |
| Load preset | `att-load-template/v1` |
| Load bindings | `att-load-bindings/v1` |
| Load summary | Engine/CLI result contract；封存報告使用 `att-load-summary/v1.3` |
| LOAD report detail files | `att-load-detail-manifest/v1`、`att-load-detail-reference/v1`、`att-load-detail-series/v1`、`att-load-detail-series/v2`、`att-load-workload-detail/v1` |
| Run manifest | `att-run/v2.1` |
| 精簡 CLI 階層式 validation report | `att-validation/v1` |
| Package validation JSON | `att-validation/v2.1` |
| CI summary | `att-ci-summary/v2.1` |
| JUnit XML | `att-junit/v2.1` |

Engine 會從 classpath registry 讀取 schema bytes。Compiled validators 以 registry digest 和 schema resource 為 key，保存在有界記憶體 cache。Runtime 唯一權威來源是內嵌 registry；`ATT_HOME/schemas/` 及 Package-local `schemas/` 不能覆寫它。Validation revision 會綁定 Engine/registry identity、Package files、shared configuration files 和選定 Environment。Worker 處理請求前會驗證 Server 傳入的 registry identity。

SUT Metrics Data Source schema 定義已登錄的 connection。Metric Source schema 描述一次 acquisition 及其 declared output mapping，不會登錄另一個 Data Source、DBHelper 或 Package Tool。Server 私有 execution payload 會把 executable fetch/call data 綁定至已批准的 Job execution definition，且不會經 API 或 report 回傳。離線報告 metadata 及外部 verdict records 使用 `schemas/api/` 下獨立的 Server contracts，不屬於 Engine execution registry。詳見[離線 SUT Metrics 報告](../results-reports-evidence.md)。
