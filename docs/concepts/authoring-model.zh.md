# Authoring model

ATT 將業務資料與可重用的執行邏輯分開。

## Functional resource

Testcase 由 workbook row 和選用 YAML sidecar 撰寫。Stage 選擇 Template；Template 宣告有序 Actions。Action 可呼叫 Tool 或 Helper，亦可重用 Flow。Flow 是可重用的執行邏輯，並有自己的有序 Actions。Resource 包括 shared Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper，以及 Package-local script 與 Testdata。

~~~text
Testcase -> Stage -> Template -> Action -> Resource
                         |          \-> Flow
                         \------------> Flow
~~~

Workbook 是撰寫 Case 的其中一種方式，並非完整 ATT resource model。Studio 及 Server 亦可直接使用 Template、Flow 和 Tool target。

## Snapshot 是明確的 authoring 操作

對 workbook-based Case，Snapshot generation 會為所選 workbook 建立 canonical XML representation。這是明確的 authoring 操作。VALIDATE 會讀取現有 authored input 並檢查，但不會以副作用建立或更新 Snapshot。

目前 4.2.5 Quick Start 保留明確步驟：產生 Snapshot、驗證 Package，然後執行 Case。請參閱 [Quick Start](../quick-start.zh.md) 及 [Test Authoring Reference](../reference.zh/test-authoring.md)。

## 資料邊界

Testdata descriptor 屬於 Package，並透過 Package Manifest 或支援的 invocation 選取。共用 Runtime Configuration 擁有可重用的外部 resource binding。請將 secret 放在受保護的 runtime input 或 secret store，不要把 credential 複製到 workbook row、Template 或文件範例。

## 規範來源

[Test Authoring Reference](../reference.zh/test-authoring.md) 定義 workbook、sidecar 及 Snapshot 行為。[Actions Reference](../reference.zh/actions.md) 定義 Action execution。Resource contract 位於 [Resources Reference](../reference.zh/resources/overview.md)。
