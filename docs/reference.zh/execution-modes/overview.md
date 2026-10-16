# RUN 與 LOAD 執行 adapter

RUN 是 Case、選定 Cases 或單一 Template/Flow/Tool 的功能執行模型；Debug 是選用的診斷選項。LOAD 保留效能引擎與併發語義。驗證及遷移範例請參閱[統一 RUN 與排程](unified-run.md)。

現有 Engine adapter 與輸出格式保持相容：

| RUN/LOAD adapter | `EXEC.ID` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| RUN：Case runner | canonical Case ID | selected Testcase | `output/<RunID>/` |
| RUN：Resource runner（舊 Debug adapter） | debug ID | 單一 target invocation | `output/debug/<debugId>/` |
| LOAD | run 內唯一的 iteration execution ID | 重複 target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` 表示外層 ATT run。模式與 scheduler 資料只保存在 evidence-only `DIAG`；`EXEC.MODE`、`EXEC.LOAD` 與 `DIAG` 均不能供 expression 使用。

所有 adapter 都先解析 environment、建立 canonical Context、驗證 dependency closure，再使用相同的 Template/Flow/Tool/DB/MQ/HTTP/SSH contract。Selection、scheduling 與 reporting 不會建立另一套 expression 或參數綁定語義。啟用 Debug 診斷只影響診斷事件，不會改變呼叫次數或權限。
