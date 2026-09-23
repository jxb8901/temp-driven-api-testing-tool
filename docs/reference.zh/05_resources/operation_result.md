### 5.4 Common Operation Result 與 Evidence

Tool、DB、MQ executor 先收斂到同一 operation boundary，之後 Template runner 才套用 Action lifecycle、assertion、retry policy。

```text
operation
├── result
├── evidence
├── executionSuccess
├── diagnostic
└── timing
       |
       v
Action output
├── status / success
├── durationMs
├── result          # 最後／勝出的 primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` 是 business/operation data；`evidence` 是支援執行的證據；`diagnostic` 解釋 operational failure；`status` 則是在 operation outcome 與 assertion 處理後的 Action-level classification。這些概念刻意分開。

Retry 不會在 top-level 發布多個競爭結果：只有最後／勝出的 primary operation 位於 top-level。每個 attempt 的 evidence 與 collector result 保留在 `attempts[n]`。Connection pool、JDBC transaction object、MQ session、process handle 都是 internal lifecycle state，不屬於 Context。
