# Appendix C — migration notes

## Load 獨立 evidence 配額

有限 Load evidence 配額須使用 `att-load/v1.7`。使用 `maxSamples`，或使用共用 `maxSuccessSamples`／`maxFailureSamples` 組合的 legacy scenario，均保留既有語義。Per-workload cap 必須明確設定 `evidence.quotaScope: perWorkload`。此模式下請設定 `evidence.defaults` 及可選的 cap-only `workloads[].evidence` override；不要加入 root `maxSamples`、`maxSuccessSamples` 或 `maxFailureSamples`。每個 workload 的 cap 互相獨立，不會出借、借用、收回容量，也不會因配額刪除已保留 artifact。限制、驗證及 reporting 見 [Load](../execution-modes/load.md#獨立-evidence-配額)。


## MQ request queue 路由

`att-mqhelper/v1.2` scalar descriptor 仍受支援。使用 `message.requestQueues` 或 `requestQueueSelection` 時，須改為 `att-mqhelper/v1.3`，並以 1–256 項不重複 queue 清單取代 scalar `requestQueue`；`replyQueue` 維持單一可選 scalar。Instance 路由覆寫會取代繼承的 scalar／清單，未明確指定 strategy 時重設 queue strategy。Queue selection、counter 範圍、evidence 及 replay 行為見 [MQHelper](../resources/mqhelper.md#request-queue-路由)。


## File arguments 與 case log paths

HTTPHelper call 不再接受 `file`；請直接將 `&{package-relative-file}` 傳入 `body`。MQHelper `send` 和 `request` 不再接受 `file`；請將 expression 傳入 `payload`。SSHHelper `upload` 現在要求 `payload` content，並拒絕 `localPath`；請直接傳入 `&{...}`。SSHHelper 不再支援 `download`，因為它需要 local destination path。若工作必須從主機取回檔案，請明確配置 command-backed Tool。這些變更移除 Resource API 原生的任意 binary local-file input；`&{...}` 提供 UTF-8 text。

Case log、CLI output 和輸出的 case evidence 會將 canonical package root 下的 path 顯示為 `$ATT_HOME` 或 `$ATT_HOME/<relative-path>`，並統一使用 `/`。`$ATT_HOME` 只是在 presentation 中使用的 token，不是 environment variable、Context root 或 file-expression locator。Runtime resolution 和 filesystem access 仍使用 canonical absolute Path。package root 以外的 absolute path，不論是 Path value 或診斷訊息內的路徑，都會以受限的 `$EXTERNAL/<basename>` 顯示。明確的 remote-path 欄位及 URL 會保留原值。

## Previous release Testdata migration

將 global configuration 從 `att-config/v2.10` 升至 `att-config/v2.11`，並將 Load scenario 從 `att-load/v1.4` 升至 `att-load/v1.5`。舊 schema 仍登錄於 `schemas/history/`，供 migration diagnostics 使用。`att-testdata/v1.0` 是新增契約：在選定的 environment profile `testdata` list 加入 descriptor path，再於 Case/Stage、Debug 或 Load workload input map 使用 `@{id}`。Load scenario 可在頂層加入 package-relative `testdata` paths，形成僅適用於該次 Load 的 overlay。不同 layer 的同名 ID 會完整取代 descriptor；同一 layer 內的重複 ID 無效。多筆 records 的 descriptor 必須有明確 selection policy。沒有 testdata reference 的既有 package 不需要新增 descriptor。

## Execution identity format migration

`att-config/v2.12` 新增 execution identity formats；前一版 configuration schema 仍受支援。可用 `execution.runIdFormat` 設定 Run 和 Load 的外層 ID，並用 `execution.debugIdFormat` 設定 standalone Debug directory ID。未設定時仍使用既有 timestamp Run/Load ID 及 `<type>-<targetId>` Debug ID。Load 每次 iteration 的 `EXEC.ID` 現由 Engine 管理；請移除已停用的 `execution.execIdFormat`。Run/Load 的明確 ID 使用 `--run-id`；standalone Debug 的明確 ID 使用 `--debug-id`。

Load workload `testdata.<id>` 設定控制 `scope`，並可選擇整份覆蓋 descriptor 的 `selection` policy。Scope 預設為 `iteration`；`user` 只適用 closed-VU workload。請明確選擇 `error`、`recycle` 或 `stop` exhaustion。Selection metadata 會記錄，但不包含 record value。

ATT 3.6.2 將型別化 operation result、外部 parsing、file-content String、outbound transport 和人類可讀 evidence 分開。

| 舊欄位／模型 | 3.6.2 遷移方式 |
|---|---|
| `att-template/v3.4` 或 `att-flow/v3.4` 的 `type: render` | 將 descriptor 改為 active v3.5 schema，並以使用 file-content expression 的 Assign 取代每個 Render Action。Historical v3.4 descriptor 只可經由 historical schema path 載入。 |
| `type: render` / `payload: path` | 使用 `type: assign`、variable `name` 及 `expression: "&{package-relative-file}"`；將 `${EXEC.VARS.<name>}` 傳給 consumer。 |
| Command Tool result.format | 將 parsing 設定移至 Tool descriptor 的 stdoutFormat。 |
| 共用 Action result.format/path/overwrite | 移除。output.result 是 native logical typed value；沒有隱式檔案替代方案。 |
| Render result.format/path 或 renderAs/saveAs | 移除舊欄位。File-content expression 回傳 exact UTF-8 String，不建立結果檔或 targetFiles。 |
| 透過 targetFiles 傳遞 Render 檔案 | 直接將 file-content String 傳入 HTTP body、MQ payload 或 SSH upload payload。 |
| 在 file-content String 使用 requestFormat | 移除。requestFormat 僅供抽象 Map/List；String + requestFormat 會失敗。 |
| Dynamic 或不安全 file locator | 改為一個 static package-relative file。Absolute path、glob、dynamic locator、missing file、directory、非 UTF-8 bytes 及 symlink escape 都會被拒絕。 |
| Log file | 直接將 value 傳入 Log.value。 |
| Log fields | 將 typed map/list 放在 Log.value，並選擇 Log.format。 |
| HTTP/MQ 共用 result 格式設定 | 使用 responseFormat 做 ingress parsing；可選 evidence.output.format 只控制人類可讀表示。 |
| 舊 active resource/config schema | 使用 [Schema and Version Matrix](schema-matrix.md) 的 active schema，並遷移上述欄位。Historical schemas 不是 active contracts。 |

File-content String 傳入 HTTP 的例子：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

File 以 strict UTF-8 text 讀取。File 內的 `${...}` 與 `#{...}` 仍是 runtime expressions；validation 編譯時不會呼叫外部 resource。Run/Debug 會 cache compiled plan，並在 file fingerprint 改變時失效；Load 會為 scenario freeze 已驗證的 file identity、content 及 plan。File output 不會再被當作新的 expression source 解析。

抽象 typed value 必須明確使用 requestFormat：

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

Load scenario 請將舊 single-target/v1.1 格式經由歷史 v1.2/v1.3 loader 遷移，再把 schemaVersion 升至 att-load/v1.6。Root defaults 可供多個 workload 共用；每個 workload 的 `inputs`、`vars`、load policy 及 execution 設定會覆蓋相應 root 值。Top-level thresholds 只屬於 aggregate；workload thresholds 必須在各 workload 宣告，不會從 root 繼承。`inputs` 仍對應 EXEC.INPUT；`vars` 在每個 execution 的 EXEC.ID 與 EXEC.OUTPUT_DIR 初始化後、target 啟動前評估。完整 reference 保留 native type，dependency 不受宣告順序影響；循環及 external/stateful calls 會在執行前拒絕。Load iteration identity 現由 Engine 管理；移除舊 `execution.execIdFormat`。Closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。

歷史的 `att-load-profile/v1.0` policy file 僅供 migration 使用：使用前請改寫為現行 policy-only `att-load/v1.6` descriptor；它不是現行 `load/load.yaml` 範例。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[Action 與型別化值](../actions.md)、[Runtime 與 Context 模型](../runtime-context.md)、[Load 模式](../execution-modes/load.md)與[Schema 矩陣](schema-matrix.md)。

## Load mix migration

現有 `att-load/v1.5` 檔案仍相容。升級至 `att-load/v1.6` 後，可將 workload 的 `target` 改為 `mix`，設定 closed-user weighted target mix。每個 entry 需要唯一 `id`、正整數 `weight`，以及 Template、Flow 或 Tool target。現行 summary contract 為 `att-load-summary/v1.1`；v1.0 保留作歷史格式。

## Historical schema migration

ATT 3.6.2 使用 `att-template/v3.6` 與 `att-flow/v3.6` 作為 active schemas。已發布的 `att-template/v3.5`、`att-flow/v3.5` 及更舊定義保留於 `schemas/history/`；其中 historical DB 與 Render Action 只供 compatibility 使用，不是 active contract。遷移這些 descriptor 時，先將 schema version 改為 v3.6，再套用以下欄位變更。

| Historical configuration | 3.6.2 形式 |
|---|---|
| `att-template/v3.3` 或 `att-flow/v3.3` | 先按 historical release migration 遷移至 v3.4，再改為 v3.6 並遷移 Render/DB Action。 |
| Historical `type: db` 及 `query`/`update` | 改為普通 `type: tool` Action，使用 `#{db.<id>.query(...)}`、`scalar(...)` 或 `update(...)`；query/scalar 可 retry，update 不可 automatic retry。 |
| Historical `sqlFile` | 改用單一 String argument `sql=&{package-relative-sql-file}`；`params` 與 `parameters` 互斥。 |
| Historical `type: render` | 改為使用 `"&{package-relative-file}"` expression 的 Assign；後續 Action 使用 `${EXEC.VARS.<name>}`。 |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite 或 renderAs/saveAs | 移除舊 persistence 欄位。File-content expression 回傳 exact UTF-8 String，不會隱式建立結果檔。 |
| Log file | 將 typed value 直接傳入 Log.value |
| Log fields | 將 typed map/list 放入 Log.value，並指定 Log.format |
| Render targetFiles handoff 至 HTTP/MQ/SSH | 將 file-content String 直接作為 HTTP body、MQ payload 或 SSH upload payload |
| 在 Render result 使用 requestFormat | 移除；requestFormat 留給抽象 Map/List |

File-content path 相對於 canonical package root。`./` 與 `../` 只有在 canonical target 仍位於該 root 內時才允許。v1 沒有 glob 或 dynamic locator；target 必須是 regular strict-UTF-8 file。

Unsupported schemaVersion 會在 execution 前由 validation 拒絕並提供 migration guidance。ATT 不會靜默轉換舊欄位，也不會為產生 guidance 而呼叫 Tools/resources。

[META Runtime and Context Model](../runtime-context.md) 說明 META lifecycle；[Load Mode](../execution-modes/load.md) 說明 execution identity 和 retained evidence 路徑。

## Debug schema migration

`att-debug/v1.0` 及 `att-debug/v1.1` 為 historical schema；請升級至 `att-debug/v1.2`。Template/Flow 可配置 `vars` 以 seed `EXEC.VARS`；Tool 不支援 `vars`。現行 schema 亦允許 package-relative `testdata` imports，並限定在 standalone Debug invocation。Input 與 arguments 的現行規則見 [Debug](../execution-modes/debug.md)。

## Environment profile migration

從舊的 complete-config pattern 遷移時，保留 descriptor 與 Action，將 common settings 移至 `config/config.yaml`、各環境 descriptor lists 移至 `environments.<NAME>`，並以 `--config config/config.yaml --env <NAME>` 選擇環境。現行 contract 見 [Configuration](../configuration.md)。

## Load execution identity migration

ATT 為每個 started iteration 配置唯一的 engine-managed `EXEC.ID`：`<runId>-execution-<sequence>`。同一 Run 的所有 Workload、Target、Mix entry 及 phase 共用一個 thread-safe、單調遞增的 allocator。ID 在 bootstrap vars 與 Target action 前發布；`EXEC.RUN_ID` 保持為外層 Run identity。Collision tracking 不會建立 per-iteration 檔案或持續增長的 ID registry。

目前及歷史 Load Scenario、policy-only YAML、inline draft，以及已儲存的 Schedule／Worker snapshot，都拒絕 `execution.execIdFormat`，包括空值。請移除此欄位並遷移至 `att-load/v1.9`，檢查依賴 artifact path 的整合，改用獨立 input／variable 保存 business correlation。歷史 artifact 保留原路徑；歷史 schema 檔案仍供檢視。不含自訂 ID 的歷史資源會正規化至目前 contract。受影響的 immutable schedule 須透過一般 reviewed-draft 流程建立替代版本。

生成 ID 沿用安全 path segment grammar 及 240 字元上限；Run ID 必須預留 `-execution-` 與 signed-long sequence 空間。Sequence 耗盡後持續失敗，不會 wrap。每個 Run output namespace 只透過 `.execution-owner` 原子 claim 一次；第二個 owner 或 restart 必須選用新的外層 Run ID。Claim 由安全的 completed-Job artifact retention 隨 Run 目錄清理。舊 reservation 目錄只可由 completed-Job retention 清理，不能在舊 process 仍可能使用時刪除。Metrics-only evidence 保持 lazy；單一 Run metadata 檔不會建立 iteration workspace。

外層 `execution.runIdFormat`、`execution.debugIdFormat`、literal `--run-id` 與 `--debug-id` 行為不變。Diagnostic iteration／VU／Workload／Mix ID 與 scheduler event sequence 仍獨立於 `EXEC.ID`。分散式 Worker 必須使用互不重疊的 namespace 或共用 allocator；獨立 process-local counter 不能安全共用同一 Run namespace。

## Load v1.9 readiness 與 windowed metrics

`att-load/v1.8` Load scenario 仍可載入，並會正規化為 `att-load/v1.9`。目前 Load 啟動只會在 scheduler 放行前準備被引用的 DB／MQ／HTTP helper；RUN 和 Debug 維持 lazy resource 行為。`att-load-summary/v1.2` 新增 optional resource-preparation 及 `windowMetrics` data，並保留既有 summary 欄位。Report 預設使用 steady window；`thresholds.steady` 和 `thresholds.overall` 可選擇 window scope。Window 沒有足夠的完成 evidence 時回報 `NOT_EVALUATED`，不能當作 pass。Resource 限制及 metric 定義見 [Load 模式](../execution-modes/load.md)。
