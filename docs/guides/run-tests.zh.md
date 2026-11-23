# 執行 functional test

本指南示範[快速入門](../quick-start.zh.md)所述的 Native Case 流程。從 repository root 執行 `./att.sh` 可使用 default Package；亦可用 `--package` 選取另一個已登記的 Package。

## 編譯 source checkout

```sh
./build.sh
```

Build 會建立 runtime distribution，並準備本地 CLI。

## 驗證 Native input

```sh
./att.sh validate --package
```

Validation 會檢查 Native Definition 及其所選的 Flow/Tool dependency，不會提交 Server Job 或啟動 LOAD scheduler。

## 執行所選 Case

使用 `run native-case` 選擇一個 Definition/Case，或使用 `run native-suite` 執行 implicit Suite。完整 command 和預期結果見[快速入門](../quick-start.zh.md)。RUN 會先驗證 selection，再把 evidence 寫入新的 run directory。

維護以較早模型編寫的 Package，請使用[舊版 execution guide](../legacy/execution.zh.md)。
