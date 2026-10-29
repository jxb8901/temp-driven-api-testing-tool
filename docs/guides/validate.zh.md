# 驗證 configuration 與 Package

目前 ATT 4.2.7 VALIDATE command 會檢查 authored input，但不會執行 business work。

## 驗證 Package

~~~sh
./att.sh validate --package
~~~

如需選擇 Runtime Configuration file 及 Environment：

~~~sh
./att.sh validate --config config/config.yaml --env SIT --package
~~~

使用 CLI 的 selected-validation option 檢查指定 dependency closure。請明確選擇 Case 或 resource target；不要假設 validation 會發現 Snapshot 或建立缺少的 authoring file。

## VALIDATE 的行為

VALIDATE 會讀取所選 configuration 和 Package source、檢查已註冊 schema 及 semantic reference，並回報 diagnostics。它不會執行 Tool、呼叫 DB/MQ business operation、提交 Server Job 或啟動 Load scheduler，也不會建立或更新 workbook Snapshot。

Diagnostic status、JSON 欄位、scope 及 exit code 請參閱 [Validation and Diagnostics](../reference.zh/validation-diagnostics.md)。Proposed vNext scope 另行標示為[規劃中](../interfaces/cli/vnext-proposed.zh.md)。
