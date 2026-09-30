## 14 動作與型別化值

本章定義 ATT 3.6.0 現行 Action 契約。Template 使用 att-template/v3.3。每個完成的 Action 都會在 output.result 發布邏輯型別化值；Action 不使用共用的 result.format/path/overwrite 物件。資源配置請參閱 Tool、DBHelper、MQHelper、HTTPHelper 章節。

### Action 類型

| 類型 | 必填欄位 | 結果與行為 |
|---|---|---|
| render | payload | 將範本檔渲染為 DocumentValue；多來源時回傳以相對路徑為 key 的 DocumentValue map。不解析文件，也不寫入結果檔。 |
| tool | call | 呼叫已配置 Tool、built-in 或 helper，保留原生型別化結果。 |
| db | db 與 query/update 其中一個區塊 | 回傳 DB operation 的型別化值與 evidence。 |
| assert | assert | 評估布林條件並記錄 PASS 或 FAIL。expected、actual 是可選診斷值。 |
| log | message 或 value | 將型別化值格式化後寫入 Case 日誌。欄位為 level、message、value、format。 |
| assign | name、expression | 將 expression 的型別化結果發布至 EXEC.VARS。 |
| flow | use | 在巢狀 Action scope 執行已註冊 Flow，返回時還原 caller scope。 |

Actions 按 YAML 順序執行。依類型允許時，也可定義 id、description、onFailure、runWhen。Action ID 在 scope 內必須唯一。類型不支援的欄位會在 validation 失敗。共用 Action result、Log file 與 Log fields 不屬於現行契約。

### 區分邏輯值與表示方式

ATT 將 operation 的邏輯結果與人類可讀或 wire representation 分開：

| Boundary | 欄位/值 | 用途 |
|---|---|---|
| Command Tool stdout | stdoutFormat | 將外部 stdout 解析為型別化結果。 |
| HTTP/MQ response | responseFormat | 將外部 response bytes 解析為型別化結果。 |
| Render 輸出 | templateFormat | 標示範本所產生的 representation。 |
| 透過 HTTP/MQ 傳送抽象 Map/List | requestFormat | 在 outbound boundary 序列化該值。 |
| Log 或 resource evidence | format / evidence.output.format | 產生人類可讀表示。 |

DB result 本身已是型別化值。Tool、Action、Template、Flow 和 expression results 在 ATT 中傳遞時均保留型別。

### Render 與 DocumentValue

Render 回傳已表示的文件。DocumentValue 帶有 format 及完全一致的 rendered text：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
~~~

單一來源會令 output.result 成為 DocumentValue。多來源則回傳以 template root 相對來源路徑為 key 的有序 map。templateFormat 支援 auto、text、json、yaml、xml。auto 依副檔名選擇：.json 為 json、.yaml/.yml 為 yaml、.xml 為 xml，其他為 text。

DocumentValue.text 是權威表示。ATT 不會將它解析成可導航的 map/tree，也不會在傳輸前 pretty-print、normalize 或改寫。Render 不建立檔案，也不暴露 output.targetFiles。若要存取結構化資料，請使用原本的型別化 Context value，例如 EXEC.INPUT.amount 或前一 Action 的 output.result.amount。

Render 結果直接傳送至 HTTP/MQ：

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml

sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

MQ 請將 DocumentValue 傳給 payload。DocumentValue 不可搭配 requestFormat。Resource 使用其配置的 charset/CCSID 編碼原文。DocumentValue.format 不會設定 MQMD.Format，也不會覆蓋由 resource 管理的 HTTP Content-Type。

requestFormat 僅供 Map 或 List 等抽象結構化值使用。此類 body 必須明確指定格式，例如 requestFormat=json。DocumentValue 與 requestFormat 同時出現會失敗，確保已表示文件不會被靜默 parse/serialize。只有 resource 呼叫明確定義 file 參數時，raw file input 才仍可使用；Render 不會建立 handoff file。

### Tool、DB 與 Flow 結果

Command-backed Tool 在 Tool descriptor 宣告 stdoutFormat：

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat 是 ingress parser；stdout 只解析一次成為 output.result，並非輸出序列化設定。Call-backed Tool 及 DB/HTTP/MQ operation 保留 native implementation 回傳的型別。

DB action 使用 db 及 query 或 update 其中一個區塊。SQL、bind parameters、transaction controls 和 DB evidence 依 DB action 與 DBHelper 契約處理。

Flow action 使用 canonical Flow ID 的 use。Flow 在新的 EXEC.ACTIONS scope 執行，返回時將結果/evidence 發布給 caller。META.FLOW 只在該次 invocation 執行期間存在。

### Tool evidence collector

Tool Action 可定義第一級 `evidence` collector，用來在 Action assertion 前收集診斷資料。執行順序是：

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collector 是診斷 operation，不是替代 Action。每個 collector 有自己的型別化 result，不會取代或修改 primary `output.result`：

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.ACTIONS.renderRequest.output.result},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.replyReceived} == true
```

包含 assertion 在內，Action active 時可使用：

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

Action 發布後，對應值位於 `EXEC.ACTIONS`：

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

Public shape 會將 primary resource evidence 與 collector evidence 分開：

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

Tool retry 時，每個 primary attempt 都會在該 attempt assertion 前執行 collector。Top-level `output.evidence.collectors.<id>` 是最後／勝出的 attempt；`output.attempts[n].evidence.collectors.<id>` 保留每個 attempt，包括較早的 failure。發布後的歷史路徑是 `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`。

`call` 必填。`timeoutMs` 與 primary Tool timeout 獨立。應用程式 log 的一般診斷模式使用 `onFailure: continue`，避免收集 log 失敗掩蓋原本的 business 或 assertion failure；`stop` 則令 collector failure 成為 Action error。Collector 的 status 與 diagnostic 仍可觀察，且 collector failure 不會改變 primary logical result。若資料是後續 assertion 要使用的正常 business/test value，應使用普通 Tool/Log/Assign Action，而非 evidence collector。

Collector result 遵守一般 typed-result 規則。放在 evidence 下不代表會轉成 String；Map、List 和 `DocumentValue` 均保留型別，匹配格式時保留 `DocumentValue` 的權威原文。在 Load 中，明確要求的 collector execution 與 helper `evidence.output` serialization 是兩件事；resource-output 格式化仍由 Load evidence policy 控制，不會靜默取代或刪除 author-requested collector。

### Log：將型別化值轉成人類可讀日誌

Log 是 presentation Action，因此有自己的 format 欄位：

~~~yaml
logOrder:
  type: log
  level: INFO
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

level 預設 INFO，可設 TRACE、DEBUG、INFO、WARN、ERROR。message 或 value 至少要有一項。message 以文字求值。value 可接受任意型別化值，包括巢狀 map/list。完整的 ${...} 和 #{...} expression 保留原始型別；map/list 子節點會遞迴求值，不會將數字、布林、null 或巢狀值轉成字串。format 支援 text、json、yaml、xml、sqlplus，只控制寫入 Case 日誌的字串。指定 format 時必須提供 value。

同時提供 message 和 value 時，Log 輸出 message、換行，再輸出格式化 value。output.result 是最終字串。DocumentValue 未指定 format 或指定相同格式時會保留權威原文；衝突格式會失敗，不會轉換。Log 不讀取檔案，也沒有 fields map。需要結構化日誌時，將 typed map/list 放到 value。

### Expressions 與變數 scope

Action expression 使用一般 ATT expression engine。完整 ${...} 或 #{...} expression 保留結果型別；expression 放在一般字串內才會成為 String。請參閱[Expressions and Built-ins](07_expressions.md)。

assign 會將 typed value 發布至 EXEC.VARS.<name> 一次。name 必須符合 [A-Za-z_][A-Za-z0-9_]*，且在 Case 中唯一。一個 Stage 指派的值可供後續 Stage 使用。Action 執行期間可讀取 output.*，發布後可讀取 EXEC.ACTIONS.<id>.output.*。Flow 有暫時 Action namespace；若 caller 在 Flow 返回後仍需要該值，請發布到 EXEC.VARS。

### Resource output evidence

Resource evidence 與邏輯 result 分離。Helper 可設定可選的 evidence.output presentation policy：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

此設定會在 operation metadata 旁加入有長度上限的人類可讀快照，不會改變 output.result 或 response parsing。Load 的 evidence.resources.output 可設 inherit（預設）或 none。none 會略過 resource-output 格式化與檔案物化。Metrics-only iteration 不建立 execution 目錄。Iteration evidence 被保留後，符合條件的 resource output 才會延遲格式化至該 workspace。

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

[META Runtime and Context Model](03_runtime_context.md) 說明 META lifecycle；[Load Mode](04_execution_modes/load.md) 說明 execution identity 和 retained evidence 路徑。
