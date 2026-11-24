# 驗證 Configuration 和 Package

VALIDATE 會檢查 Native authored input，不會執行 business work。共用 CLI grammar 見 [CLI Reference](../reference.zh/cli.md)；此 release target 尚未發佈。

## 驗證 Package

~~~sh
./att.sh validate --package
~~~

指定 Runtime Configuration file 和 Environment：

~~~sh
./att.sh validate --config config/config.yaml --env SIT --package
~~~

使用 selected-validation option 檢查一個 Definition 或 Resource dependency closure。Diagnostic code、JSON 欄位、scope 和 exit code 見[Validation 和 Troubleshooting](../reference.zh/validation-diagnostics.md)。維護以較早模型編寫的 Package，請參閱[舊版文件](../legacy/README.zh.md)。

## VALIDATE 的行為

VALIDATE 會讀取所選 Configuration 和 Package source、檢查已登記 schema 及 semantic reference，並回報 diagnostic。它不會執行 Tool、呼叫 DB/MQ business operation、提交 Server Job 或啟動 LOAD scheduler，也不會建立或更新 authored input。
