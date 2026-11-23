# 遷移至 Package 所有的 SUT Metrics 定義

目前的設定契約將共用連線與指標定義分開。Global `att-config/v2.15` 保留 Data Source 連線描述檔；所選 Package 透過明確的 `att-package/v2` 清單管理自己的 Metric Source、Group 和 Profile。LOAD Scenario 必須選擇該 Package 的 Profile，才會收集指標。

完整檔案結構請參閱 [Package-local SUT Metrics 範例](../../examples/package-local-sut-metrics/README.md)。範例包含 Global SIT/UAT 連線描述檔，以及一個 Package 的指標定義。

## 遷移後的所有權

| 項目 | 所有者與位置 | 如何選用 |
| --- | --- | --- |
| Data Source 端點、驗證、存取權與限制 | Global config 的 `config/sut-metrics/` 描述檔 | 生效環境中的 `sutMetrics.dataSources` |
| Metric Source 定義 | 所屬 Package 的 `load/sut-metrics/sources/` | `package.yaml` 明確列出路徑 |
| Group 定義 | 所屬 Package 的 `load/sut-metrics/groups/` | `package.yaml` 明確列出路徑 |
| Profile 定義 | 所屬 Package 的 `load/sut-metrics/profiles/` | `package.yaml` 明確列出路徑，再由 LOAD Scenario 選用 |

Global Settings 繼續作為連線設定的唯一來源，不再擁有 Package 的 Metric Source、Group 或 Profile。每個 Package 有自己的 ID 範圍：不同 Package 可以使用相同的定義 ID，但 Package 不能匯入其他 Package 的定義。

## 遷移步驟

### 盤點現有定義及使用者

<!-- att-docs:historical -->
從現有 `att-config/v2.14` 的內嵌 `sutMetrics` 區塊開始。[v2.14 內嵌範例](../../examples/history/config/sut-metrics-v2.14.yaml)展示遷移前的檔案結構；請只把它當作遷移來源，不是 4.3.1 的目標格式。記錄每個 Data Source、Metric Source、Group 和 Profile ID、選用各 Profile 的 LOAD Scenario，以及 Scenario 所屬的 Package。
<!-- /att-docs:historical -->

若多個 Package 使用同一個中央定義，請將它複製到每個需要它的 Package，並在各 Package 內處理 Profile 綁定和參照。不要保留共用的 Global Metric Source，也不要依賴跨 Package 匯入。

v2 Package 若不使用 SUT Metrics，請讓其 manifest 不包含 `observability` 清單。ATT 不會掃描目錄自動發現指標檔案，也不會退回使用 Global config 或其他 Package 的定義。遷移期間，pre-v2 Package 保留既有 global binding resolution；Studio picker 不會提供這些 Profile 建立新 binding。

### 將 Data Source 保留在 Global，改用描述檔

在 Global config 的 `config/sut-metrics/` 目錄下，每個 Data Source 使用一個版本化描述檔。在 `att-config/v2.15` 中，`sutMetrics.dataSources` 列出描述檔路徑，不再內嵌 Data Source 物件：

```yaml
schemaVersion: att-config/v2.15
environment: SIT
sutMetrics:
  dataSources:
    - config/sut-metrics/prometheus-sit.yaml
environments:
  UAT:
    sutMetrics:
      dataSources:
        - config/sut-metrics/prometheus-uat.yaml
```

範例中的 SIT 與 UAT 描述檔都使用 `id: prom-main`，但端點和 secret reference 按環境區分。UAT profile 會整份取代描述檔清單；穩定的邏輯 ID 讓 Package 定義在兩個環境都使用 `prom-main`。SIT 和 UAT 的 Package 檔案保持相同。憑證只存於 Server secret store，描述檔只放 secret reference，並只允許必要的精確 HTTPS host。Server 會將 `PROMETHEUS_SIT_TOKEN` 這類 reference 解析為 `ATT_SECRET_PROMETHEUS_SIT_TOKEN`；非 loopback 的 HTTPS host 必須列在 `ATT_SUT_METRICS_ALLOWED_HOSTS`。

Global `sutMetrics` 現在只包含 Data Source 描述檔參照。將舊的內嵌 `metricSources`、`groups` 和 `profiles` 清單移走後，請從 Global config 刪除。不要保留第二份定義當作 fallback。既有 DBHelper 資源仍依原本 Global 設定管理；本次 Package Metric Source 使用 `kind: DATA_SOURCE` 和 Global Data Source ID。

### 在每個 Package manifest 加入明確清單

將 Package manifest 升級至 `att-package/v2`。在 `observability` 中逐一列出指標定義檔案，且每種定義只能放在對應目錄：

```yaml
schemaVersion: att-package/v2
metadata:
  name: Payments LOAD Package
defaultEnvironment: SIT
observability:
  metricSources:
    - load/sut-metrics/sources/app-runtime.yaml
  groups:
    - load/sut-metrics/groups/app-servers.yaml
  profiles:
    - load/sut-metrics/profiles/basic.yaml
```

每個 YAML 檔只包含一個既有版本的定義，不放多筆陣列，也不合併成總目錄。Package Metric Source 透過邏輯 ID 連接 Global 描述檔：

```yaml
schemaVersion: att-sut-metrics-metric-source/v1
id: app-runtime
name: Application CPU utilization
connection:
  kind: DATA_SOURCE
  id: prom-main
```

搬移時保留既有 fetch、mapping、Group 和 Profile 語義。不要在 Package Metric Source 加入 Data Source revision、環境專屬 revision 或 connection revision。Package Metric Source 可以使用所選 Environment 中既有的 Global DBHelper，或所選 Package 所有的 Package Tool；不得使用其他 Package 的 Tool。本範例樹只展示 `DATA_SOURCE` 連線。保留有界 fetch 和 label projection；Prometheus mapping 維持 wide-row series，不使用 Long Rows 表示法。

完整 manifest 和定義檔案請參閱：

- [Package manifest](../../examples/package-local-sut-metrics/packages/payments/package.yaml)
- [Metric Source](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/sources/app-runtime.yaml)
- [Group](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/groups/app-servers.yaml)
- [Profile](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/profiles/basic.yaml)

### 在每個 LOAD Scenario 選擇 Package 內的 Profile

先選擇擁有該 Scenario 的 Package，再綁定該 Package 清單中的 Profile ID。例如，在 Package 既有 LOAD Scenario 加入或更新以下片段：

```yaml
observability:
  schemaVersion: att-load-observability/v1
  collections:
    - profile: basic
  collection:
    mode: post-run
    before: 5m
    after: 2m
    step: 30s
  livePreview:
    enabled: false
```

請使用 Profile ID（`basic`），不要使用檔名。Profile 的 `allowedBindings` 必須涵蓋 Scenario 的環境、target、tenant 和 parameters。範例 Profile 開放 SIT 與 UAT，並以 `PAYMENTS_HEALTH` 作為示範 template target；請改成使用 Package 中實際存在的 target。

定義檔存在不代表已啟用收集。Package 沒有列出 Profile，或 Scenario 沒有綁定 Profile，就不會收集 SUT Metrics。Package 的 `observability` 區塊不存在或為空，代表沒有指標清單，也不會自動退回 Global 定義。

### 發佈前預覽並驗證所選 Package

在 Studio 一併檢視所選 Package manifest、清單中的定義檔，以及 Scenario 的 Profile 綁定。定義預覽應顯示解析後的 Package 定義和綁定計畫，不執行查詢。端點檢查或即時指標預覽是另外明確觸發的操作，並受 Global Data Source 權限和 host allowlist 管理。不要透過預覽執行 SQL 或 Package Tool。

發佈前請修正所有問題，包括 Package 內重複 ID、重複清單路徑、路徑不在相符的 `load/sut-metrics/` 目錄、缺少參照、找不到 Data Source ID、不支援的 schema 版本、未授權綁定，以及超出預算的 fetch。錯誤資訊應指出 Package 和來源檔案，讓維護者在所屬位置修正定義。

### 明確處理舊設定衝突

<!-- att-docs:historical -->
切換 Global config 至 v2.15 前，請刪除 v2.14 中已退役的內嵌 Metric Source、Group 和 Profile 清單。如果舊 Global 定義與 Package 內定義宣稱相同所有權或 ID，請先停止並明確選定所屬 Package。不要合併兩份定義、靜默擇一或退回舊副本。不同 Package 可使用相同 ID，因為定義以 Package ID 隔離；同一 Package 內的重複 ID 則屬衝突。

舊 Server 端的 `sut-metrics-catalog.json` 不再是 Package 定義的有效來源。若其中有未出現在 v2.14 內嵌 config 的紀錄，請先由其擁有者明確協調，再停用舊 catalog。本指南不定義遷移命令。
<!-- /att-docs:historical -->

### 保留執行來源資訊

已提交的 LOAD Job 必須保留所選 Global config digest 和環境、Package 身分與 manifest/resource digests、生效定義 revisions，以及遮蔽敏感資訊的 fetch plan。這些資料讓指標及報告能追溯到 Job 使用的確切 Package 檔案和 Global 連線設定。之後發佈的修改只供新驗證的 Job 使用，不得改寫既有 Job 的來源資訊或已封存產物。

## 最後檢查清單

- Global v2.15 只在 `config/sut-metrics/` 下參照 Data Source 描述檔。
- SIT 和 UAT 描述檔保留相同 Data Source ID；secret 和端點仍由 Global 管理。
- 每個 Package 明確列出自己的 sources、groups、profiles，路徑位於各自相符的目錄。
- 每個清單檔只含一個版本化定義，不含環境專屬 connection revision。
- Package Metric Source 使用 `DATA_SOURCE` 和穩定的邏輯 Data Source ID。
- LOAD Scenario 選用所選 Package 的 Profile，且綁定允許目前環境和 target。
- 沒有 Package 清單或 Scenario 綁定就沒有指標；不需掃描目錄、跨 Package 匯入、合併或舊設定 fallback。
- 先檢視預覽，再明確執行任何即時端點檢查；Job 產物保留 Package 來源資訊。
