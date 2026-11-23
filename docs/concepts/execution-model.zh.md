# Execution model

ATT 將 functional RUN、LOAD traffic generation 和唯讀 VALIDATE 分開處理。

## Functional RUN

Native RUN 會把 Test Case Definition 解析成所選 Resolved Case。每個 Case 使用其綁定的 Flow、typed input 和已宣告 Resource。Server 和 Studio 會提交已驗證的 RUN draft；draft 會凍結 Package revision 和 selection。Debug 會用明確 inputs 執行一個 Flow 或 Tool。

## LOAD

LOAD 透過 scheduler 執行 Flow 或 Tool workload，支援 virtual-user 或 arrival-rate pacing、timing control、threshold 和 evidence policy。Native Case LOAD target 仍在延後範圍。詳見[Load model](load-model.zh.md)和 [Load Reference](../reference.zh/execution-modes/load.md)。

## VALIDATE

VALIDATE 會檢查 Configuration、Package resource、schema 和所選 dependency。它不會執行 Tool、發送 LOAD traffic 或進行 DB/MQ business operation，也不會建立或更新 authored input。

## 契約來源

[RUN](../reference.zh/execution-modes/run.md) 定義 Case selection 和執行。[Debug](../reference.zh/execution-modes/debug.md) 定義診斷 input 和 output。[LOAD](../reference.zh/execution-modes/load.md) 定義 workload 行為。[Validation 和 Troubleshooting](../reference.zh/validation-diagnostics.md) 定義驗證結果和 diagnostic。維護以較早模型編寫的 Package，請使用[舊版文件](../legacy/README.zh.md)。
