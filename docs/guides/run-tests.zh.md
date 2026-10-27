# 執行 functional test

本指南使用目前 ATT 4.2.5 Local CLI，明確保留 Snapshot 建立步驟，並執行已提交的 offline Case。

## 1. 編譯 source checkout

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

Packaged installation 已包含 application JAR。

## 2. 產生並驗證 authored input

從 repository root 執行時，launcher 會選擇 default Package。

~~~sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
~~~

Snapshot generation 會寫入 workbook 的 canonical representation。Validation 檢查目前 configuration 和 Package input，不會呼叫 business Tool 或建立 Snapshot。

## 3. 執行 Case

~~~sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
~~~

請依據結果摘要中的 Local Run ID 和 output directory 尋找執行 evidence。Template、Flow 或 Tool diagnostics 使用目前相容 command standalone Debug：

~~~sh
./att.sh debug template PAYMENT_INVOKE
~~~

Target 與 selection semantics 請參閱 [Unified RUN](../reference.zh/execution-modes/unified-run.md)。Local CLI vNext 語法[預計於未來版本推出](../interfaces/cli/vnext-proposed.zh.md)。
