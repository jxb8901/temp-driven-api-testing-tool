# ATT Load Scenario Examples

本目錄的範例均使用 ATT 3.6.0 現行 schema att-load/v1.2。每個 scenario 以 workloads 清單配置 target。Schema、語義、所有 target 與依賴會在 scheduler 啟動前驗證。

## 範例索引

| 檔案 | 模型與用途 |
|---|---|
| closed-minimal.yaml | 最小 closed VU。 |
| closed.yaml | 完整 closed workload、inputs、thinkTime、threshold 與 evidence。 |
| closed-random-think.yaml | Seeded closed-VU think-time range。 |
| closed-smoke.yaml | 短時間 closed smoke。 |
| arrival-rate.yaml | 完整 fixed arrival-rate 範例。 |
| arrival-smoke.yaml | 短時間 arrival-rate smoke。 |
| multi-closed.yaml | 多個獨立 closed VU workload。 |
| multi-arrival.yaml | 多個獨立 arrival-rate workload。 |
| tool.yaml | 呼叫包內 deterministic Tool 的短範例。 |

## 最小 closed workload

~~~yaml
schemaVersion: att-load/v1.2
workloads:
  - id: default
    target: {type: template, id: V3_FLOW_EXAMPLE}
    load:
      users: 20
      duration: 5m
~~~

每個 Virtual User 重複執行固定 target。Think time 可設一個 duration，也可設定 min/max range。

## 自訂 Load execution ID

execution.execIdFormat 使用一般 ATT ${...} / #{...} expression engine，在每個 iteration 初始化時求值一次：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

此時 EXEC.ID、EXEC.OUTPUT_DIR 尚未建立，也沒有 Action result 或 invocation-scoped META。Closed 可讀穩定 USER_ID；arrival-rate 沒有 USER_ID，需使用：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

只允許 deterministic、side-effect-free built-ins。不允許 seq.next()、clock/random/filesystem functions 或 Tool/DB/MQ/HTTP/SSH calls。ID 必須安全且在同一 run 唯一。

## Evidence 與路徑

evidence.mode 支援 metrics、failures、samples、all；預設 failures。Metrics-only iteration 不建立目錄。保留的成功 sample 位於 samples/<EXEC.ID>/，failure 位於 failures/<EXEC.ID>/；workspace 含 case.log 與 case.yaml。Report 顯示 EXEC.ID，有保留 log 時提供連結。evidence.resources.output 可設 none，略過 resource output 格式化與檔案寫入；typed runtime result 與 transport parsing 不受影響。

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── failures/<EXEC.ID>/
└── samples/<EXEC.ID>/
~~~

## Workload 模型與執行

Closed workload 使用 load.users 及必填 duration，可配置 execution.thinkTime。Arrival-rate 使用 load.arrivalRate、maxConcurrent、overloadPolicy: drop；超額 arrival 計為 generator drop，不排隊，也不是 SUT error。Arrival-rate 不支援 thinkTime，且沒有 USER_ID。

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
~~~

同一 scenario 的 workloads 必須使用相同 model 與 phase timing envelope。Multi-workload 是多個固定 target 各自 pacing，不會在 target 間隨機切換。CLI load-model overrides 僅適用單一 workload；多 workload 時請修改 YAML。完整欄位、threshold、CLI option 與報告契約見[Load Mode](../../docs/reference/04_execution_modes/load.md)。
