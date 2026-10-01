## Appendix C — Migration Notes

ATT 3.6.2 將型別化 operation result、外部 parsing、project-file String、outbound transport 和人類可讀 evidence 分開。

| 舊欄位／模型 | 3.6.2 遷移方式 |
|---|---|
| `att-template/v3.4` 或 `att-flow/v3.4` 的 `type: render` | 將 descriptor 改為 active v3.5 schema，並以使用 project-file expression 的 Assign 取代每個 Render Action。Historical v3.4 descriptor 只可經由 historical schema path 載入。 |
| `type: render` / `payload: path` | 使用 `type: assign`、variable `name` 及 `expression: "&{project-relative-file}"`；將 `${EXEC.VARS.<name>}` 傳給 consumer。 |
| Command Tool result.format | 將 parsing 設定移至 Tool descriptor 的 stdoutFormat。 |
| 共用 Action result.format/path/overwrite | 移除。output.result 是 native logical typed value；沒有隱式檔案替代方案。 |
| Render result.format/path 或 renderAs/saveAs | 移除舊欄位。Project-file expression 回傳 exact UTF-8 String，不建立結果檔或 targetFiles。 |
| 透過 targetFiles 傳遞 Render 檔案 | 直接將 project-file String 傳入 HTTP body 或 MQ payload。 |
| 在 project-file String 使用 requestFormat | 移除。requestFormat 僅供抽象 Map/List；String + requestFormat 會失敗。 |
| Dynamic 或不安全 file locator | 改為一個 static project-relative file。Absolute path、glob、dynamic locator、missing file、directory、非 UTF-8 bytes 及 symlink escape 都會被拒絕。 |
| Log file | 直接將 value 傳入 Log.value。 |
| Log fields | 將 typed map/list 放在 Log.value，並選擇 Log.format。 |
| HTTP/MQ 共用 result 格式設定 | 使用 responseFormat 做 ingress parsing；可選 evidence.output.format 只控制人類可讀表示。 |
| 舊 active resource/config schema | 使用 [Appendix A](schema_matrix.md) 的 active schema，並遷移上述欄位。Historical schemas 不是 active contracts。 |

Project-file String 傳入 HTTP 的例子：

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

Load scenario 請將舊 single-target/v1.1 格式經由歷史 v1.2/v1.3 loader 遷移，再把 schemaVersion 升至 att-load/v1.4。Root defaults 可供多個 workload 共用；每個 workload 的 `inputs`、`vars`、load policy 及 execution 設定會覆蓋相應 root 值。Top-level thresholds 只屬於 aggregate；workload thresholds 必須在各 workload 宣告，不會從 root 繼承。`inputs` 仍對應 EXEC.INPUT；`vars` 在每個 execution 的 EXEC.ID 與 EXEC.OUTPUT_DIR 初始化後、target 啟動前評估。完整 reference 保留 native type，dependency 不受宣告順序影響；循環及 external/stateful calls 會在執行前拒絕。頂層 execution.execIdFormat 仍在 initialization 使用一般 expression engine 求值一次；closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。

歷史的 `att-load-profile/v1.0` policy file 僅供 migration 使用：使用前請改寫為現行 policy-only `att-load/v1.4` descriptor；它不是現行 `load/load.yaml` 範例。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[Action 與型別化值](../14_actions.md)、[Runtime 與 Context 模型](../03_runtime_context.md)、[Load 模式](../04_execution_modes/load.md)與[Schema 矩陣](schema_matrix.md)。

### Historical schema migration

ATT 3.6.2 使用 `att-template/v3.5` 與 `att-flow/v3.5` 作為 active schemas。已發布的 `att-template/v3.4` 與 `att-flow/v3.4` 定義保留於 `schemas/history/`；其中 historical Render Action 只供 compatibility 使用，不是 active contract。遷移這些 descriptor 時，先將 schema version 改為 v3.5，再套用以下欄位變更。

| Historical configuration | 3.6.2 形式 |
|---|---|
| `att-template/v3.3` 或 `att-flow/v3.3` | 先按 historical release migration 遷移至 v3.4，再改為 v3.5 並遷移 Render Action。 |
| Historical `type: render` | 改為使用 `"&{project-relative-file}"` expression 的 Assign；後續 Action 使用 `${EXEC.VARS.<name>}`。 |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite 或 renderAs/saveAs | 移除舊 persistence 欄位。Project-file expression 回傳 exact UTF-8 String，不會隱式建立結果檔。 |
| Log file | 將 typed value 直接傳入 Log.value |
| Log fields | 將 typed map/list 放入 Log.value，並指定 Log.format |
| Render targetFiles handoff 至 HTTP/MQ | 將 project-file String 直接作為 HTTP body 或 MQ payload |
| 在 Render result 使用 requestFormat | 移除；requestFormat 留給抽象 Map/List |

Project-file path 相對於 canonical project root。`./` 與 `../` 只有在 canonical target 仍位於該 root 內時才允許。v1 沒有 glob 或 dynamic locator；target 必須是 regular strict-UTF-8 file。

Unsupported schemaVersion 會在 execution 前由 validation 拒絕並提供 migration guidance。ATT 不會靜默轉換舊欄位，也不會為產生 guidance 而呼叫 Tools/resources。

[META Runtime and Context Model](../03_runtime_context.md) 說明 META lifecycle；[Load Mode](../04_execution_modes/load.md) 說明 execution identity 和 retained evidence 路徑。

### Debug schema migration

`att-debug/v1.0` 為 historical schema；請升級至 `att-debug/v1.1`。Template/Flow 可配置 `vars` 以 seed `EXEC.VARS`；Tool 不支援 `vars`。Input 與 arguments 的現行規則見 [Debug](../04_execution_modes/debug.md)。

### Environment profile migration

從舊的 complete-config pattern 遷移時，保留 descriptor 與 Action，將 common settings 移至 `config/config.yaml`、各環境 descriptor lists 移至 `environments.<NAME>`，並以 `--config config/config.yaml --env <NAME>` 選擇環境。現行 contract 見 [Configuration](../09_configuration.md)。
