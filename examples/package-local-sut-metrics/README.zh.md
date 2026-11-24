# Package-local SUT Metrics 範例

[English](README.md)

這個範例展示 4.3.1 的所有權拆分：

- `config/config.yaml` 引用 Global Data Source 描述檔。UAT profile 會替換描述檔路徑，並保留邏輯 ID `prom-main`。
- `packages/payments/package.yaml` 在對應的 `load/sut-metrics/` 目錄明確列出一個 Metric Source、Group 和 Profile。
- 這三個 Package 定義不含環境端點或憑證。SIT 和 UAT 使用同一份定義。

範例 Profile binding 裡的 `PAYMENTS_HEALTH` target 只是示意。請改成你的 Package 裡實際存在的 target。在 ATT Server secret store 設定所引用的 secret，並只允許各環境需要的精確 Prometheus host。`.example.invalid` URL 是佔位，不能查詢。

每個定義檔只含一份有版本的文件。這個範例沒有設定 SQL／DBHelper 或 Package Tool 收集、即時預覽或 Long Rows。只複製這些檔案不會啟用 SUT Metrics；必須同時有 Package manifest 清單和 LOAD scenario 的 Profile binding。
