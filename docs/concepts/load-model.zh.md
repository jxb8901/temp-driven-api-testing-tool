# Load model

ATT LOAD 將 workload shape、target binding 及 pacing 分開處理。Mix weight 用來選擇下一次 iteration 的 target，不會分割已設定的 VU pool。

## 六種 workload shape

目前 Load Template catalog 有六種項目。Single VU 及 Single TPS 使用 Quick Load 編輯流程；其餘四種使用 multi-workload builder。

| Mode | Workload 數量 | Target binding | Pacing |
|---|---:|---|---|
| Single VU | 1 | 一個固定 Target | Closed VU |
| Single TPS | 1 | 一個固定 Target | Arrival rate |
| Single Workload Mix | 1 | Target weighted mix | Closed VU |
| Multi-workload VU | 多個 | 每個 workload 一個固定 Target | 獨立 closed VU pool |
| Multi-workload TPS | 多個 | 每個 workload 一個固定 Target | 獨立 arrival rate |
| Multi-workload Mix | 多個 | 每個 workload 獨立 weighted mix | 獨立 closed VU pool |

Multi-workload Mix 中，Workload A 和 B 可獨立綁定同一 Target，並使用不同 weight。包含兩個 workload、四個 mix entry 及三個不同 Target 的 scenario 仍只有兩個 workload pool。TPS 值是設定的 arrival target；實際 throughput 及 drops 會分開報告。

Engine 要求 scenario 內所有 workload 使用共同 phase timing envelope。Mixed workload 使用 closed VU pacing。UI catalog 及 Load Template CLI 會先組合成一般的 target-bound scenario，再進行 validation。

## Contract 所在位置

[Load Reference](../reference.zh/execution-modes/load.md) 定義 schema、composition、scheduler、telemetry、threshold 及 evidence。[Load guide](../guides/load-testing.zh.md) 說明目前 4.2.6 workflow。本頁是 model 概覽，不會重複建立 schema specification。
