# Run 模式

Native RUN 會執行 Test Case Definition 中所選的 Resolved Case。Case 會解析至 Flow 和 Data Source；開始執行前，RUN 會驗證所選的 dependency closure。新工作請從[快速入門](../../quick-start.zh.md)開始。維護以較早模型編寫的 Package，請參閱[舊版文件](../../legacy/README.zh.md)。

## 選擇 Case

可用 qualified ID 選擇一個 Case，或用 implicit Suite 按 Definition、ID 或 tag 選取 Cases。不需要 `suite.yaml`。Excluded tag 優先於 included tag。若 selection 不存在或為空，RUN 會拒絕執行，不會改用其他 selection。

[Unified RUN Reference](unified-run.md) 說明 Server draft 與 schedule lifecycle。[CLI Reference](../cli.md) 定義 command 語法和 exit code。

## 驗證及執行

Case 開始前，ATT 會驗證 Definition、已解析的 Data Source、Flow 和被引用的 Resource contract。Server draft 會凍結所選 Case ID 和 Package source revision；source 改變後必須重新驗證。Preview 和 validation 不會提交 Job。

每個 Resolved Case 會產生一次 Case execution，各自有 execution ID、status 和 evidence。RUN 會把結果寫入唯一 run directory，並只在完成後更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。

## 結果和失敗狀態

Status aggregation 保留嚴重程度：ERROR > INVALID > FAIL > PASS > SKIPPED。Run 完成且沒有失敗時 process exit code 為 `0`；Case/assertion 失敗為 `1`；command/configuration/validation 無效為 `2`；runtime 或 infrastructure error 為 `3`。

在 Studio 中，**Configure & Rerun** 會依保留的 RUN definition 建立新的已驗證 draft。提交前請檢查 Package revision 和 selection。詳見[Unified RUN](unified-run.md)。
