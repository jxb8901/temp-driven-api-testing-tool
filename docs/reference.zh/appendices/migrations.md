## Appendix C — Migration Notes

ATT 3.6.0 將型別化 operation result、外部 parsing、渲染文件、outbound transport 和人類可讀 evidence 分開。

| 舊欄位／模型 | 3.6.0 遷移方式 |
|---|---|
| Command Tool result.format | 將 parsing 設定移至 Tool descriptor 的 stdoutFormat。 |
| 共用 Action result.format/path/overwrite | 移除。output.result 是 native logical typed value；沒有隱式檔案替代方案。 |
| Render result.format/path 或 renderAs/saveAs | 改用 templateFormat。Render 回傳含原文的 DocumentValue，不建立結果檔或 targetFiles。 |
| 透過 targetFiles 傳遞 Render 檔案 | 直接將 DocumentValue 傳入 HTTP body 或 MQ payload。 |
| 在 Render output 使用 requestFormat | 移除。requestFormat 僅供抽象 Map/List；DocumentValue + requestFormat 會失敗。 |
| Log file | 直接將 value 傳入 Log.value。 |
| Log fields | 將 typed map/list 放在 Log.value，並選擇 Log.format。 |
| HTTP/MQ 共用 result 格式設定 | 使用 responseFormat 做 ingress parsing；可選 evidence.output.format 只控制人類可讀表示。 |
| 舊 active resource/config schema | 使用 [Appendix A](schema_matrix.md) 的 active schema，並遷移上述欄位。Historical schemas 不是 active contracts。 |

Render 直接傳到 HTTP 的例子：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

抽象 typed value 必須明確使用 requestFormat：

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

Load scenario 請將舊 single-target/v1.1 格式遷移為 att-load/v1.2 workloads，再把 schemaVersion 升至 att-load/v1.3 以啟用 workload vars。`inputs` 仍對應 EXEC.INPUT；`vars` 在每個 execution 的 EXEC.ID 與 EXEC.OUTPUT_DIR 初始化後、target 啟動前評估。完整 reference 保留 native type，dependency 不受宣告順序影響；循環及 external/stateful calls 會在執行前拒絕。頂層 execution.execIdFormat 仍在 initialization 使用一般 expression engine 求值一次；closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[Action 與型別化值](../14_actions.md)、[Runtime 與 Context 模型](../03_runtime_context.md)、[Load 模式](../04_execution_modes/load.md)與[Schema 矩陣](schema_matrix.md)。

### 移除欄位與遷移

ATT 3.6.0 每種 resource 只接受現行 schema。歷史版本存放於 schemas/history，不是 active contract。

| 舊配置 | 3.6.0 形式 |
|---|---|
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite 或 renderAs/saveAs | 使用 templateFormat；將 output.result 當作 DocumentValue；沒有隱式檔案替代方案 |
| Log file | 將 typed value 直接傳入 Log.value |
| Log fields | 將 typed map/list 放入 Log.value，並指定 Log.format |
| Render targetFiles handoff 至 HTTP/MQ | 將 DocumentValue 直接作為 HTTP body 或 MQ payload |
| 在 Render result 使用 requestFormat | 移除；requestFormat 留給抽象 Map/List |

Unsupported schemaVersion 會在 execution 前由 validation 拒絕並提供 migration guidance。ATT 不會靜默轉換舊欄位，也不會為產生 guidance 而呼叫 Tools/resources。

[META Runtime and Context Model](../03_runtime_context.md) 說明 META lifecycle；[Load Mode](../04_execution_modes/load.md) 說明 execution identity 和 retained evidence 路徑。

### Debug schema migration

`att-debug/v1.0` 為 historical schema；請升級至 `att-debug/v1.1`。Template/Flow 可配置 `vars` 以 seed `EXEC.VARS`；Tool 不支援 `vars`。Input 與 arguments 的現行規則見 [Debug](../04_execution_modes/debug.md)。

### Environment profile migration

從舊的 complete-config pattern 遷移時，保留 descriptor 與 Action，將 common settings 移至 `config/config.yaml`、各環境 descriptor lists 移至 `environments.<NAME>`，並以 `--config config/config.yaml --env <NAME>` 選擇環境。現行 contract 見 [Configuration](../09_configuration.md)。
