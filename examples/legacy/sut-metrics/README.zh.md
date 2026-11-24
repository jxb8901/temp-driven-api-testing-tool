# 已封存的 SUT Metrics catalog

這些檔案是舊的 Settings catalog 和 v1 composite Profile 範例。它們是相容性歷史，不是目前的設定方式。

不要在 **Settings → SUT Metrics administration** 註冊 `source.json`、`profile-jvm.json`、`profile-jvm-prod.json`、`profile-app.json` 或 `profile-platform.json`。Global Settings 現在只擁有 Data Source 連線描述檔。Metric Source、Group 和 Profile 屬於 Package 清單（`att-package/v2`）。

| 檔案 | Schema | 保留原因 |
|---|---|---|
| `source.json` | `att-sut-metrics-source/v1` | 舊的可變 catalog Data Source。URL 是 `https://prometheus.example.invalid`，不得查詢 |
| `profile-jvm.json`、`profile-jvm-prod.json`、`profile-app.json` | `att-sut-metrics-profile/v1` | 以前在 Settings 註冊的 base profile |
| `profile-platform.json` | `att-sut-metrics-profile/v1` composite | 以 ID include 那些 base profile |
| `scenario.yaml` | `att-load/v1.9` | 綁定 catalog Profile `profile-platform` 和佔位 Template `PAYMENTS_HEALTH` |

`scenario.yaml` 不是離線 LOAD 範例。它指向 Default Package 裡不存在的 Template，以及已不再註冊的 catalog Profile。例行 CI 不會執行它。

目前的路徑是 [examples/package-local-sut-metrics](../../package-local-sut-metrics/README.md)。請依照[遷移指南](../../../docs/guides/sut-metrics-config-migration.zh.md)。測試仍會載入的 v2 contract 範例留在 [examples/load/sut-metrics/v2](../../load/sut-metrics/v2/metric-source-db-wide-row.json)。
