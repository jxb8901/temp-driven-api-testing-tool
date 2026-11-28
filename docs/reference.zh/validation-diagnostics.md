# Validation 和 troubleshooting

## 選擇第一個 diagnostic step

執行 `./att.sh validate --package`，先查看回報的檔案和欄位。**Resolved Case** 是 Test Case Definition 中可選取的一個 Case。**Case execution** 是所選 Resolved Case 的一次執行。Runtime failure 請檢查 execution status 和 message，再查看 `case.log`、`case.yaml` 和 Action evidence。維護以較早模型編寫的 Package，請使用[舊版文件](../legacy/README.zh.md)。

## 驗證 Native input

Package validation 會檢查 Definition、Flow、Tool、Testdata、Runtime Configuration、已登記 schema 和 semantic reference。它不會執行 Tool、提交 Server Job 或啟動 LOAD scheduler。Validation 為唯讀，也不會建立 authoring input。

Studio 及其他 immutable source inspection 路徑會驗證 Package 設定語意，不要求 pinned revision 或其相對 `outputDirectory` 可寫。本機 CLI `validate`/`run` 與 Server Job 執行仍會檢查實際 output 目的地是否有可寫 ancestor；Server Job 使用獨立配置的 Job output directory，不會寫入 immutable source tree。

使用 selected-validation option 可快速檢查一個 Definition 或 Resource target。分享 Package 或升級 build 前，請驗證整個 Package 和每個所選 Environment。[Schema 和 Version Matrix](appendices/schema-matrix.md) 列出此 release 使用的契約。Diagnostic 會保留 source file、field 和結構化位置；automation 應使用穩定 diagnostic code，而非 message text。

| 類別 | 常見原因 | 修正方式 |
|---|---|---|
| `ATT-TC` | Definition 缺失、Data Source 無效或 Case ID 重複 | 檢查 Definition ID、來源紀錄和 resolved Case ID |
| `ATT-CTX` | Context path 不存在或有歧義 | 檢查 requested path、目前 scope 和建議的 canonical path |
| `ATT-FLOW` | Flow 不存在、Action 無效或 dependency 未解析 | 檢查 Flow ID、有序 Actions 和被引用的 Resource/檔案 |
| `ATT-CFG` | 未知欄位、重複 key 或型別錯誤 | 對照 active Configuration schema |
| `ATT-TOOL` | Argument 不存在、process failure 或 parse failure | 檢查 Tool contract、exit code 和有界 output evidence |
| `ATT-PATH` | Identifier 不合法或 path 越界 | 讓 identifier 和檔案留在配置的 Package root 之下 |
| `ATT-RUN` | Timeout、非零 exit code 或 runtime exception | 查看 Case log 和 Action evidence |

## 常見問題

### 為何 Case ID 被拒絕？

Resolved Case ID 必須唯一且符合 path-safe 規則。Excel source 請將 ID 存為文字，並檢查空白、前後空格、保留裝置名稱和大小寫摺疊後的重複 ID。

### 為何 Context variable 失敗？

ATT 會將不存在的 path 回報為 authoring 或 runtime error。請檢查確切 scope、source-field mapping、Action ID 及該值何時可用。已宣告但空白的 optional value 合法，並會渲染成空字串。

### 為何 Case 失敗或出錯？

Assertion 為 false 時，Case execution 狀態為 FAIL。Expression syntax、Tool、timeout、parse、I/O 或 runtime exception 會回報 ERROR。請查看 Action evidence 找出原因。

### 沒有 Server 可以開啟 report 嗎？

可以。請保留整個生成的 run directory，讓相對 artifact link 仍可用。

## 安全與結構化 diagnostics

不要把密碼、token、private key 或敏感客戶資料放在 Definition input、Tool argument、log 或文件範例中。使用核准的 secret injection，並在分享前檢查 report。

Machine-readable validation 使用 `att-validation/v2.1`。Diagnostic 包含穩定 code、severity、file 和 field，有提供時亦包含 source location 和安全建議。Code 才是支援的 automation interface；message wording 可以調整。
