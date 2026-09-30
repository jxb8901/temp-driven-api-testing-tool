### 14.3 遷移說明

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
| 舊 active resource/config schema | 將 schemaVersion 升至 ATT 3.6.0 現行版本，並遷移上述欄位。schemas/history 中的 schema 不是 active runtime contract。 |

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

Load scenario 請從舊 single-target/v1.1 格式遷移至 att-load/v1.2 workloads。Pacing 移入各 workload；需要自訂 EXEC.ID 時可設定頂層 execution.execIdFormat。它在初始化時使用一般 expression engine 求值一次；closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。不要在格式中使用 seq.next() 或 external/stateful functions。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[動作與型別化值](../14_actions.md)、[Runtime 與 Context 模型](../03_runtime_context.md)、[Load 模式](../04_execution_modes/load.md)與[Schema 矩陣](schema_matrix.md)。
