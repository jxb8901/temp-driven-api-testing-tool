# Execution mode 概覽

RUN 會執行所選 Case。Debug 會對單一 Flow 或 Tool execution 提供診斷。LOAD 透過 scheduler 重複執行 Flow 或 Tool workload。VALIDATE 會檢查所選 input，不會啟動 business execution。

| Mode | Input | Output 位置 | 是否啟動 business work |
|---|---|---|---|
| RUN | Definition 和所選 Resolved Case | `output/<runId>/` | 驗證後會 |
| Debug | Flow 或 Tool target 和明確 inputs | `output/debug/<debugId>/` | 會，限所選 target |
| LOAD | 已驗證的 Flow 或 Tool workload | `output/load/<runId>/` | Scheduler 啟動後會 |
| VALIDATE | Package 或所選 dependency closure | Validation report | 不會 |

各 mode 共用 Expression、Action 和 Resource contract。Selection 和 scheduler 會決定啟動哪些工作，不會另行定義 input binding 語義。較早撰寫模型請參閱[舊版文件](../../legacy/README.zh.md)。
