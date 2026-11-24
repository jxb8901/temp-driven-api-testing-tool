# 舊式 Execution

執行既有 Workbook-based Case 或直接相容 target 時，請使用以下指令。

## 執行 Case

若 Package 不是預設 Package，請將 `ATT_PACKAGE_ROOT` 設為 Package root。傳入 `./att.sh` 的路徑會相對於該 Package。

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
```

保留的 Quick Start fixture 可從 `testcase/quick_start.xlsx` 選擇 `quickStart.default.QS001` 或 `quickStart.default.QS002`。前者執行 `assign`、`log` 和 `assert`；後者亦會呼叫本地 sample Tool。需要外部業務系統的 fixture 請使用 dry run。

Workbook Testcase 可以依序通過多個 Stage selector。每個選取的 Stage 會選擇一個 Template，而其 input 只在該 Stage 範圍內有效。Case ID 仍由 Workbook、group 和 row ID 組成。

## 執行 Debug

Debug 會執行一個直接 component，並寫入專屬 result directory。選取 Package-local target 時，請明確指定 Package：

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
```

可以用 `--input <path>` 指定 Debug input file。Credential 請放入環境變數或已配置的 secret store。除非刻意配置 Tool 和 environment binding，否則範例 target 不會發出真正 request。

## 執行 LOAD

LOAD 接受 `att-load/v1.9` scenario，並支援直接的 Template、Flow 和 Tool target。選取 `packages/legacy` 時，`examples/load/` 下的 scenario 路徑相對於 repository root：

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
```

Native Case target 不屬於此 LOAD 契約。與目前 runtime 共用的 scheduler、evidence 和 report 欄位請見[目前的 LOAD Reference](../reference/execution-modes/load.md)。

## 報告和重跑

RUN output 寫在所選 output directory 和 run ID 之下。Workbook-based run 保留既有結果 Workbook 版面和 Case ID。同一目錄的 `--rerun-failed` 會使用該 Package 最近一個符合資格且已完成的結果；若需要獨立的 run history，請指定另一個 `--output-dir`。

所有 Case 完成後，run 才會更新 `latest-run.yaml`。失敗或未完成的 run 不會發布新的 pointer。

離線 multi-Flow 範例和標準報告 Workbook 請使用目前的 [Native payments walkthrough](../guides/native-payments.zh.md)。
