### 5.4 Common Operation Result and Evidence

Tool, DB and MQ executors converge at one operation boundary before the Template runner applies Action lifecycle, assertions and retry policy.

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
├── result          # final/winning primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` is business/operation data. `evidence` is supporting execution data. `diagnostic` explains an operational failure. `status` is the Action-level classification after operation outcome and assertion handling. These are intentionally different concepts.

Retries never publish multiple competing top-level results: only the final/winning primary operation is top-level. Each attempt retains its own evidence and collector results in `attempts[n]`. Connection pools, JDBC transaction objects, MQ sessions and process handles are internal lifecycle state and must not be treated as Context.
