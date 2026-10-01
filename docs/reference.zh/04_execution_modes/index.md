## 06 Execution Modes

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ/HTTP/SSH 執行語義。

| 模式 | `EXEC.ID` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | 單一 target invocation | `output/debug/<debugId>/` |
| Load | run 內唯一的 iteration execution ID | 重複 target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` 表示外層 ATT run。模式與 scheduler 資料只保存在 evidence-only `DIAG`，`EXEC.MODE`、`EXEC.LOAD` 和 `DIAG` 均不能供 expression 使用。

三種模式都會先解析 environment、建立 canonical Context、驗證 target/dependency closure，再使用相同 component contract。Mode-specific scheduling、selection、reporting 不會建立另一套 Template 或 expression 語義。

| Mode | 典型用途 |
|---|---|
| Run | 一般功能 SIT/UAT execution |
| Debug | 在 authoring 或 diagnosis 階段隔離一個 Template/Flow/Tool |
| Load | 重複／併發 performance execution |

三者共用 execution model；各模式分別定義 input、identity/bootstrap lifecycle、CLI behavior 與 output layout。
