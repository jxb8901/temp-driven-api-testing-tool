# Package model

ATT 將共用 runtime ownership 與 business-resource ownership 分開。

## Global Runtime Configuration

Installation 使用一份共用 Runtime Configuration，通常位於 `ATT_HOME/config/config.yaml`。它定義 runtime defaults、helper registry 和 Environment Profile。Operator 選擇 Environment，將穩定的 logical helper ID 綁定到環境專屬 DB、MQ、HTTP 或 SSH resource。Tool 和 Tool group 屬於所選 Package，放在 `tools/`。

## Package ownership 和隔離

Package 是 Test Case Definition、Flow、Tool、Tool group、package-local script、SQL 和 Testdata 的已登記 root。Server registry 將 logical Package ID 對應到 root。Server request 使用該 ID 和 Package-relative resource name；client 不能指定 host path。Package 不會再擁有另一份 Runtime Configuration 或 schema catalog。

Package 隔離會阻止某個 Package 透過自造路徑存取另一 Package 的 private resource。共用 Runtime Configuration 仍屬全域。Server authorization、已登記 root 和 inspection policy 會限制各使用者可以存取的 resource。

## Optional Package Manifest

選用的 `package.yaml` Manifest 宣告 Package defaults 和明確的 Testdata inventory。它可以選擇 default Environment 及受控 typed override，但不會取代共用 Runtime Configuration，也不授權任意欄位、privileged configuration 或跨 Package 讀取。

[Package Manifest](../reference.zh/package-manifest.md) 是 Manifest 欄位、typed override 和 Testdata resolution 的規範來源。[Configuration and Environments](../reference.zh/configuration.md) 定義共用 configuration 和 precedence。

## 後續文件

- [Authoring model](authoring-model.zh.md) 說明 Package 編寫方式。
- [Configuration model](configuration-model.zh.md) 說明共用與 Package-owned value 如何組合。
- [Run and Job lifecycle](jobs-and-runs.zh.md) 區分 Local output 和 Server record。
- 維護以較早模型編寫的 Package，請使用[舊版文件](../legacy/README.zh.md)。
