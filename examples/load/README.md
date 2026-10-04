# ATT Load Scenario Examples

本目錄的範例均使用 ATT 現行 schema att-load/v1.6。每個 scenario 以 workloads 清單配置固定 target 或 closed-user weighted mix。Schema、語義、所有 target 與依賴會在 scheduler 啟動前驗證。

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
| mixed-closed.yaml | 單一 closed workload 在兩個預先驗證 Tool targets 間按權重選擇。 |
| multi-arrival.yaml | 多個獨立 arrival-rate workload。 |
| tool.yaml | 呼叫包內 deterministic Tool 的短範例。 |
| testdata-generated.yaml | 從 Load-local generated testdata 依 iteration 順序載入 QUICK_START Template；records 用盡時停止 workload。 |
| quick-profile.yaml | 僅含 Load policy 的 Quick Load profile；複製到 `load/load.yaml` 後使用。 |

## 最小 closed workload

~~~yaml
schemaVersion: att-load/v1.6
workloads:
  - id: default
    target: {type: template, id: V3_FLOW_EXAMPLE}
    load:
      users: 20
      duration: 5m
~~~

每個 Virtual User 重複執行固定 target。Think time 可設一個 duration，也可設定 min/max range。

`mixed-closed.yaml` 展示每次 iteration 依 workload seed、穩定 VU identity 和 iteration number 選擇 target；輸出會列出每個 mix entry 的選擇次數與 metrics。

## Generated Testdata

`testdata-generated.yaml` imports a four-record virtual sequence from `examples/testdata/generated-account.yaml`, maps the typed `amount` value into the offline `QUICK_START` Template, and uses `scope: iteration`. Its `exhaustion: stop` policy ends that workload cleanly after the records are consumed:

~~~sh
./att.sh load examples/load/testdata-generated.yaml
~~~

## Quick Load policy profile

`quick-profile.yaml` 使用現行 `att-load/v1.6` policy-only descriptor，不包含 target 或 business data。複製到專案的 `load/load.yaml` 後，執行 `./att.sh load --debug template <id>`、`flow <id>` 或 `tool <id>`，即可將 sidecar inputs/vars/arguments 與此 policy 合併，再進入正常 Load runtime。CLI intensity options 會覆蓋 policy；若沒有 policy，命令列需提供完整 pacing policy。

~~~yaml
schemaVersion: att-load/v1.6
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

## 每次 execution 的 typed vars

Template/Flow workload 可設定 bootstrap `vars`。完整 reference 保留原生型別；expression 在每次 Load execution 的 EXEC.ID/EXEC.OUTPUT_DIR 初始化後評估：

~~~yaml
schemaVersion: att-load/v1.6
workloads:
  - id: payments
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {amount: 100}
    vars:
      amount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      requestId: "REQ-${EXEC.ID}"
    load: {users: 2, duration: 10s}
~~~

各 execution 的 map/list 相互獨立。Vars 可以引用其他 vars（不受宣告順序影響）；循環、缺少變數或外部/stateful calls 會在 target action 執行前失敗。第一次一般 `assign` 可取代 bootstrap variable。CLI 的 `--set vars.path=value` 會先修改 definition，再評估 expression。

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

同一 scenario 的 workloads 必須使用相同 model 與 phase timing envelope。Multi-workload 是多個固定 target 各自 pacing，不會在 target 間隨機切換。CLI load-model overrides 僅適用單一 workload；多 workload 時請修改 YAML。完整欄位、threshold、CLI option 與報告契約見[Load Mode](../../docs/reference/execution-modes/load.md)。
