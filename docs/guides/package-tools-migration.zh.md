# Package 專屬 Tool（ATT 4.2.10）

Tool 定義由**所選已註冊 Package** 擁有，不再屬於全域 Runtime Configuration。每個 Tool 以獨立的 `<PACKAGE_ROOT>/tools/*.yaml` 檔案表示，`schemaVersion: att-tool/v1`。此功能無須等待 #233 線上編輯功能。

```yaml
schemaVersion: att-tool/v1
id: queryTransaction
name: Query Transaction
description: 使用全域 DB Helper 查詢狀態
call: "#{db.orders.scalar(sql='select count(*) from orders where id = ?', params=[${input.transactionId}])}"
arguments:
  transactionId:
    name: Transaction ID
    required: true
```

Tool 的識別為 `(registeredPackageId, toolId)`，不同 Package 可使用相同 ID，不會互相搜尋或回退至全域 Tool。DB/MQ/HTTP/SSH Helper 及其連線、憑證、端點仍由全域 Environment 管理。Command Tool 沿用既有 Worker OS 身份、腳本執行及 Package 路徑規則，不加入沙箱或腳本審批機制。

### 由 4.2.9 升級

舊版 `config/config.yaml` 的 `tools:` 和 `toolGroups:` 不會在 4.2.10 自動生效，必須明確遷移。

1. 備份原始 Config 與各 Package。
2. 使用 `python3 tools/migrate_package_tools.py --config config/config.yaml --package packages/default` 預覽遷移（預設 dry run，需 PyYAML）。
3. 檢查 ID 衝突、Helper 依賴與 Script 相對 Package 路徑。被多個 Package 使用的 Tool 必須明確複製到各自 Package。
4. 確認後加入 `--write` 寫入；使用獨立環境配置檔時亦需要另外遷移。工具對不支援的舊式 Tool Group SSH 設定會拒絕自動轉換。
5. 重新執行 Package validation、RUN/DEBUG/LOAD 驗證，並重新驗證先前已接受的 Job/Schedule revision。

為降低模板破壞性，舊有點號式呼叫名稱（例如 `fpp.invokeApi`）可保留為 **Package 本地 ID**；不代表跨 Package 共用。

### Studio

Package Explorer 的 Tools 清單以所選 Package 為準；Source 頁面可依既有授權及遮罩策略檢視 `att-tool/v1` 原始 YAML。Shared Configuration 僅保留全域 Runtime/Environment/DB/MQ/HTTP/SSH Helper 等資訊，不再顯示 Tool 與 Tool Group。線上 Source Editing 往後再與 #233 整合。

參考 [Package Model](../concepts/package-model.zh.md)。
