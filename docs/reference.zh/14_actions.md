## 03 Actions 與 Typed Values

本章定義 ATT 現行 Action 契約。Template 使用 att-template/v3.5。每個完成的 Action 都會在 output.result 發布邏輯型別化值；Action 不使用共用的 result.format/path/overwrite 物件。Resource 配置請參閱 Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper 章節。

### Action 類型

| 類型 | 必填欄位 | 結果與行為 |
|---|---|---|
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
| Project-file expression | `String` | 讀取安全的 UTF-8 project file，並在 expression evaluation 後保留其字元。 |
| 透過 HTTP/MQ 傳送抽象 Map/List | requestFormat | 在 outbound boundary 序列化該值。 |
| Log 或 resource evidence | format / evidence.output.format | 產生人類可讀表示。 |

DB result 本身已是型別化值。Tool、Action、Template、Flow 和 expression results 在 ATT 中傳遞時均保留型別。

### Project-file expression 回傳 String

歷史 Render Action 的現行替代方式是 typed project-file value expression `&{path}`。它一定回傳一個 `String`，不會推斷 document format、parse 副檔名、展開 glob 或建立輸出檔：

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
~~~

`${...}` 仍然是 Context reference，`#{...}` 仍然是 expression/call，`&{...}` 是 static、one-file locator；v1 沒有 glob 或 dynamic locator。Locator 相對 canonical ATT project root。`./` 或 `../` descriptor-relative path 只有在 canonical target 仍在該 root 內才允許。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白和 glob syntax 都會在 validation 失敗。

普通 UTF-8 file 會原樣回傳。如果 file 包含 `${...}` 或 `#{...}`，ATT 只 compile 一次這些 node，並在每次 execution 評估；compiled plan immutable，dynamic value 不會被當成第二份 template 重新 parse。Run 和 Debug 會重用 plan，直到 file fingerprint 改變。Load 會在 scheduling 前 validation 並 capture selected file identity、content 和 compiled dependency closure，因此 active iteration 看到穩定 snapshot。

若要由後續 Action 重用 String，使用 Assign：

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"

sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

HTTP/MQ 請將 String 直接傳給 body/payload。Resource 使用其配置的 charset/CCSID 編碼原文；Content-Type 與 MQ transport metadata 仍由 resource 管理。`&{...}` 可用於 Tool/Helper call argument、Assign expression、Log value 和其他 typed value 位置。

requestFormat 僅供 Map 或 List 等抽象結構化值使用。此類 body 必須明確指定格式，例如 requestFormat=json。String 與 requestFormat 同時出現會失敗，確保 project-file result 不會被靜默 parse/serialize。只有 resource 呼叫明確定義 file 參數時，raw file input 才仍可使用。

### Tool、DB 與 Flow 結果

Command-backed Tool 在 Tool descriptor 宣告 stdoutFormat：

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat 是 ingress parser；stdout 只解析一次成為 output.result，並非輸出序列化設定。Call-backed Tool 及 DB/HTTP/MQ/SSH operation 保留 native implementation 回傳的型別。

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
      payload=${EXEC.VARS.requestText},
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
    ${output.result.replyReceived} == true
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

Collector result 遵守一般 typed-result 規則。放在 evidence 下不代表會轉成 String；Map、List 和 project-file `String` 均保留型別。在 Load 中，明確要求的 collector execution 與 helper `evidence.output` serialization 是兩件事；resource-output 格式化仍由 Load evidence policy 控制，不會靜默取代或刪除 author-requested collector。

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

同時提供 message 和 value 時，Log 輸出 message、換行，再輸出格式化 value。output.result 是最終字串。Project-file String 會原樣輸出；Log 不會推斷或附加 document format，也不會讀取檔案或使用 fields map。需要結構化日誌時，將 typed map/list 放到 value。

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

此設定會在 operation metadata 旁加入有長度上限的人類可讀 Snapshot，不會改變 output.result 或 response parsing。Load 的 evidence.resources.output 可設 inherit（預設）或 none。none 會略過 resource-output 格式化與檔案物化。Metrics-only iteration 不建立 execution 目錄。Iteration evidence 被保留後，符合條件的 resource output 才會延遲格式化至該 workspace。

### Action output 與 evidence path

| Path | 意義與可用時機 |
|---|---|
| `output.result` | Action active（包括 assertion）期間的 primary typed result。 |
| `output.evidence.collectors.<id>.result` | Active Tool evidence collector 的 typed result。 |
| `output.evidence.collectors.<id>.status` | Collector 的 `PASS`／`ERROR` status。 |
| `output.evidence.collectors.<id>.error` | Collector 失敗時的 bounded failure summary；有可用訊息時包含非空 `message`。 |
| `output.evidence.collectors.<id>.evidence` | 保留 bounded/redacted 的 underlying Tool/resource evidence，包括 executor 提供的 resource identity 與 native failure fields。 |
| `EXEC.ACTIONS.<actionId>.output.result` | Action 完成後發布的 primary typed result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | 發布後最後／勝出的 collector result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | 發布後最後／勝出的 collector status。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | 發布後的 collector failure summary 與保留的 operation evidence。 |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | 指定 retry attempt 的 collector result/status；後續成功後仍保留較早 attempt。 |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | 該 collector attempt 的 failure summary 與 underlying evidence。 |

String、Number、Boolean、null、Map、List 等值跨越 Action/Template/Flow boundary 時都保留原型別。

### 共用 retry 與 Boolean condition

Tool Action 與可重試的 direct DB query 共用 `retry` 契約。`maxAttempts`（2–10）、`intervalMs`（0–3600000）及非空 `retryOn`（ASSERTION/TIMEOUT）仍為必填；`when` 是可選的非空 Boolean expression String。Mutating DB update 及 SSH transfer 的既有 retry 限制維持不變。

~~~yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [TIMEOUT]
  when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

每個 attempt 先執行 operation、發布當前 result/evidence/diagnostic、評估可用 assertion，然後分類 retry category。只有 `retryOn` 符合且尚有 attempt 可執行時，才評估 `when`。未配置時維持一般 retry 行為；true 才等待 interval 並重試，false 保留當前 TIMEOUT/FAIL 且不重試。沒有符合 category、成功或達到 maxAttempts 時均不評估 gate。

`when` 可讀取 `output.status`、`output.result`、`output.evidence`、`output.diagnostic`、從 1 開始的 `output.attempt`，以及當前 scope 允許的 EXEC/META path。Top-level output 在每次 attempt 開始時清除，不會讀到前次的 result/evidence。歷史紀錄保留於 `output.attempts[n]`；`retryDecision` 記錄 category、candidate、whenEvaluated、whenResult（有評估時）、allowed 與 reason（例如 WHEN_FALSE、MAX_ATTEMPTS）。

Condition 使用正常 `${...}`/`#{...}` 型別規則，必須回傳 Boolean；字串 'false' 或數字不會轉成 Boolean。`when: "#{false}"` 可停止 retry。Strict missing path 與 expression error 使用一般 diagnostic，定位至 retry.when 並停止重試。Pure deterministic built-in 可使用；Tool/DB/MQ/HTTP/SSH、file/project-file、sequence、random 與 current-time operation 均禁止。可確定的 syntax/type error 在 validation 時拒絕；runtime result 的型別與 missing path 在 gate 評估時檢查。

TIMEOUT 是 canonical Action outcome；suite/report aggregate 的 operation failure 仍為 ERROR。對 MQ request、HTTP POST 等非冪等操作，作者必須決定是否可重播。未加 when 的 TIMEOUT retry 可能重複 business transaction；ATT 不會默默抑制 MQ retry。請參閱 [MQHelper 範例](05_resources/mqhelper.md)。
