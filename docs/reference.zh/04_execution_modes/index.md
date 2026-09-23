## 04 執行模式

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ 執行語義。

| 模式 | Context `EXEC.MODE` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | 單一 target invocation | `output/debug/<debugId>/` |
| Load | `load` | 重複 target iterations | `output/load/<runId>/` |

三種模式都會先解析 environment、建立 canonical Context、驗證 target/dependency closure，再使用相同 component contract。Mode-specific scheduling、selection、reporting 不會建立另一套 Template 或 expression 語義。
