# Package 模型

ATT 將共用 runtime 資源與業務資源分開管理。

## Global Runtime Configuration

安裝環境擁有一份共用 Runtime Configuration，通常位於 ATT_HOME/config/config.yaml。此設定檔定義 runtime 預設值、resource registry、Tool group 及 Environment Profile。操作人員選擇 Environment，將穩定的 logical helper ID 對應到各環境的 DB、MQ、HTTP 或 SSH 資源。

## Package 所有權與隔離

Package 是業務資源的已註冊根目錄，包含 Testcase workbook 與 sidecar、Template、Flow、package-local script、SQL 及 Testdata。Server registry 將 logical Package ID 對應到目錄。Server request 使用該 ID 及 Package-relative resource 名稱；用戶端不能自行指定主機路徑。Package 不會擁有另一份 Runtime Configuration 或 schema catalog。

Package 隔離會阻止 Package 透過自行建立路徑來讀取其他 Package 的私有資源。共用 Runtime Configuration 仍屬全域資源，但不代表所有用戶都能查看每個 shared resource；仍須遵守 Server authorization、已註冊根目錄及檢視政策。

## 選用 Package Manifest

選用的 package.yaml Manifest 可宣告 Package 預設值及明確的 Testdata inventory。它可選擇預設 Environment 和受控的 typed override，但不會取代共用 Runtime Configuration，也不會授權任意欄位、特權設定或跨 Package 讀取。

[Package Manifest](../reference.zh/package-manifest.md) 是 Manifest 欄位、typed override 及 Testdata resolution 的規範來源。[Configuration and Environments](../reference.zh/configuration.md) 定義 shared configuration 與 precedence。

## 延伸閱讀

- [Authoring model](authoring-model.zh.md) 說明 Package 如何撰寫。
- [Configuration model](configuration-model.zh.md) 說明 shared 與 Package-owned 值如何組合。
- [Run and Job lifecycle](jobs-and-runs.zh.md) 區分 Local output 與 Server record。
