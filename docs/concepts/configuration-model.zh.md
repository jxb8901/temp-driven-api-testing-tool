# Configuration model

ATT 會分層解析共用 runtime 設定及 Package-owned 預設值。

## 共用 Runtime Configuration

ATT_HOME/config/config.yaml 擁有 runtime 預設值、resource registry 及 Environment Profile。選擇 Environment 後，ATT 會使用該 profile 為 logical helper 指定的 binding，亦可覆寫該層 schema 允許的設定值。

## Package 預設值與 Testdata

選用的 Package Manifest 擁有 Package 預設值及已宣告的 Testdata inventory。受支援的 typed override 只能變更 Manifest contract 允許的欄位類型。它不能取代 global file、加入特權設定或指向另一個 Package。

## Effective configuration

Effective value 由共用設定、所選 Environment 及允許的 Package 預設值解析。Invocation-level option 可在單次執行中覆寫支援的值。某值在一層有效，不代表它在另一層也有效；每層 schema 及 semantic validator 均會檢查其 owner。

欄位 precedence、合法 override 及相容行為請以規範來源 [Configuration Reference](../reference.zh/configuration.md) 及 [Package Manifest Reference](../reference.zh/package-manifest.md) 為準。
