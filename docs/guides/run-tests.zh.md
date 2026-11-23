# 執行 functional test

目前的 Case 路徑是 Flow + `testcase.yaml` + Data Source，說明見[快速入門](../quick-start.zh.md)及[目前 CLI Reference](../reference.zh/cli.md)。這些 Native command 已整合在目前的 default branch。本頁保留仍可執行已提交 offline Case 的 workbook／Sidecar／Snapshot command。這條路徑是受支援的相容做法，不是唯一的撰寫模型，且尚未發佈 4.6.0 release 或 tag。

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

Target 與 selection semantics 請參閱 [Unified RUN](../reference.zh/execution-modes/unified-run.md)。Native `run native-case` 和 `run native-suite` 是目前的 command。更廣泛的共用 vNext grammar 仍是[規劃中的 proposal](../interfaces/cli/vnext-proposed.zh.md)。
