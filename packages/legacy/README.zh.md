# Legacy Package

[English](README.md)

這個 Package 是尚未成為 Native Flow Case 的範例 Template 與 workbook 的註冊位置。它與 `packages/default` 並列，使用本目錄作為 package root 即可選用。Template ID 與 workbook Case ID 維持不變。

這些資源沒有改寫成 `att-testcase/v1`。改寫會替換 Stage 選擇、`runWhen`／`onFailure` 或 workbook Case ID，而且沒有保留該行為的 golden。請在這裡執行 Run 與 Debug。除非 scenario 明確提供 business TestCase ID，否則不要把 `PAYMENT_*`、FPP Template 或本地付款 Template 當成 Quick 或 Advanced Load target。`examples/load/closed.yaml` 與 `examples/load/closed-minimal.yaml` 仍使用 target id `V3_FLOW_EXAMPLE`，並以這個 Package 為工作目錄執行。

本 Package 的 `templates/flows/common/` 與 `tools/` 是這些 Template 在 package 內解析到的複本。Tool 與 Flow 查找不會跨到 `packages/default`。common Flow 與目前 Tool catalog 的現行正本仍在 `packages/default`。

從 repository root 執行的離線檢查。`./att.sh` 會進入 `ATT_PACKAGE_ROOT` 指定的 Package：

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/load/closed-minimal.yaml --users 1 --duration 100ms
```

`payment_regression` dry-run 選取 22 個 Case 並略過全部 22 個。它不會呼叫 FPP、MQ 或 DB。這些 Template 的實際業務執行不屬於這次搬移。
