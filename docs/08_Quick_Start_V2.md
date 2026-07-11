# ATT V2 新手入門

這份入門用中文 Excel 完成工具、模板、案例、驗證、執行、報告、文件及打包流程。

## 1. 核心關係

```text
test case --1:n stage--> template --1:n action--> tool
```

Test case、template、tool 是核心概念。Stage 只定義一個案例調用模板的數量及順序；Action 只定義模板調用工具或執行 render/assert/log 的數量及順序。

## 2. 目錄

```text
config/config.yaml
testcase/支付回歸.xlsx
testcase/支付回歸.yaml
templates/payment/local/CT001/template.yaml
templates/payment/local/CT001/request.tmp.xml
tools/invoke_payment_api.sh
```

只有直接包含 `template.yaml` 的目錄才是模板；上例模板完整路徑是 `payment/local/CT001`。

## 3. 配置工具

```yaml
outputDirectory: output
environment: SIT
timeoutSeconds: 120
templates: {root: templates}
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: 調用付款 API
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestFile: {name: Request File, description: 已渲染 XML, required: true}
      environment: {name: Environment, description: 執行環境, required: true}
```

`arguments` 用於驗證及工具文件。每個參數只定義 `name`、`description`、`required`；只有最後一個參數可增加 `delimit`。

## 4. 建立模板

`templates/payment/local/CT001/template.yaml`：

```yaml
name: 本地付款
description: 產生付款 XML 並調用 API
actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: request.xml
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
  assertStatus:
    type: assert
    expression: "${ACTIONS.invokeApi.output.Response.Status} == '${CASE.expected.status}'"
```

`request.tmp.xml`：

```xml
<PaymentRequest>
  <CaseId>${CASE.caseId}</CaseId>
  <DebitAccount>${CASE.debitAccount}</DebitAccount>
  <Amount>${CASE.amount}</Amount>
</PaymentRequest>
```

## 5. 中文 Excel 和 sidecar

Excel 表頭：

| 案例編號 | 案例名稱 | 標籤 | 扣賬帳號 | 金額 | 預期結果 | 執行模板 |
|---|---|---|---|---:|---|---|
| TC001 | 本地付款成功 | smoke,付款 | 111111 | 100 | `status: SUCCESS` | `name: 本地付款` |

相鄰的 `testcase/支付回歸.yaml`：

```yaml
excel:
  sheet: 支付測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, debitAccount=扣賬帳號, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    required: true
    runWhen: normal
    onFailure: stop
report:
  columns:
    result: 測試結果
    reportLink: 詳細報告
```

模板單元格可寫成含 `name` 的 YAML map，也可直接寫一行 YAML scalar shorthand。`name` 或 scalar 值有兩種寫法：填寫模板 `template.yaml` 中定義的 symbolic name（例如 `本地付款`），或填寫相對於 `templates.root` 的完整模板目錄路徑（例如 `payment/local/CT001`）；兩者都用來唯一選定要執行的模板。例如 `PAYMENT_INVOKE` 等價於 `name: PAYMENT_INVOKE`。scalar 會由 ATT 正規化為 `name` stage data；map 的所有 key-value 都會加入 stage data。`N/A`、`NA`、`NULL`、`NONE` 和空白會正規化為 blank。詳細規則見 [Reference Manual V2：Workbook sidecar](09_Reference_Manual_V2.md#4-workbook-sidecar)。

多 sheet 使用：

```yaml
sheet: payment=支付測試案例集, batch=批量測試案例集
```

完整 Case ID 分別是 `payment.TC001`、`batch.TC001`。

### 多行表頭

如果 Excel 前兩行是表頭，在 sidecar 的 `excel` 下設定 `headerRows: 2`。ATT 會逐欄由上到下尋找最後一個非空表頭 cell 作為實際欄名，不會把多行文字拼接：

```text
第 1 行：基本資料 |        | 執行資訊 |        |
第 2 行：案例編號 | 案例名稱 | 執行模板 | 執行參數 |
有效欄名：案例編號、案例名稱、執行模板、執行參數
```

`headerRows` 預設為 `1`；資料從表頭列之後開始。有效欄名重複、找不到必填欄位或 `headerRows < 1` 都會在 validate 階段報錯。詳細規則見 [Reference Manual V2：Workbook sidecar](09_Reference_Manual_V2.md#4-workbook-sidecar)。

## 6. 表達式

- `${CASE.amount}`：讀取 Context。
- `${CASE.STAGES.invoke.TEMPLATE.ACTIONS.invokeApi.TOOL.invokePaymentApi.output}`：跨 stage 完整路徑。
- `${ACTIONS.invokeApi.output}`：目前 template 的便捷路徑。
- `#{tool(name='中文,字串', count=2, enabled=true)}`：調用工具，支援字串、數字、布爾 literal。

核心節點 `CASE`、`STAGES`、`TEMPLATE`、`ACTIONS`、`TOOL` 使用大寫；`caseId`、`outputFile` 等 metadata 使用 camelCase。

`CASE`、`STAGE`、`TEMPLATE`、`ACTION`、`TOOL` 的所有內建屬性、適用時機及完整路徑，見 [Reference Manual V2：Runtime Context](09_Reference_Manual_V2.md#7-runtime-context)。

ATT 內置函數包括：

- `upper(value=...)`：轉換為大寫；
- `lower(value=...)`：轉換為小寫；
- `trim(value=...)`：移除前後空白；
- `string(value=...)`、`number(value=...)`、`boolean(value=...)`：執行型別轉換；
- `length(value=...)`：取得字串長度；
- `concat(...)`：按參數順序串接文字；
- `coalesce(...)`：返回第一個非 blank 值。

例如：

```text
#{upper(value=${CASE.currency})}
#{coalesce(${CASE.optionalReference}, 'NO-REFERENCE')}
#{boolean(yes)}
```

完整函數清單、參數規則及字面量語法見 [Reference Manual V2：Expressions and built-in functions](09_Reference_Manual_V2.md#8-expressions-and-built-in-functions)。

## 7. 驗證及執行

```sh
./att.sh validate --suite testcase/支付回歸.xlsx
./att.sh run --suite testcase/支付回歸.xlsx
./att.sh run --all
./att.sh run --all --case payment.TC001
./att.sh run --all --tag smoke
```

不帶參數或使用 `--help` 顯示完整用法。

驗證錯誤代碼、選擇規則及 stage 執行語義見 [Reference Manual V2：Validation, reports, documentation, and packaging](09_Reference_Manual_V2.md#11-validation-reports-documentation-and-packaging)。

## 8. 報告、文件和打包

```sh
./att.sh report --run-id <RunID>
./att.sh docs
./att.sh build
./att.sh clean
```

- 單頁測試報告：`output/<RunID>/report/index.html`
- 單頁套件文件：`build/docs/index.html`
- 結果工作簿：`output/<RunID>/workbooks/`
- 最近完成 run 的 archive：`build/att-<RunID>.tar.gz`

`./att.sh clean` 清除 ATT 生成的 output/report/log、`build/docs`、`build` 和 `target`，不會清除案例、模板、工具、設定或文件。它拒絕清除專案根目錄或專案外路徑。

報告欄位、單頁 HTML 內容及 archive 內容詳見 [Reference Manual V2：Validation, reports, documentation, and packaging](09_Reference_Manual_V2.md#11-validation-reports-documentation-and-packaging)。

## 9. 常見問題

- 找不到模板：檢查目錄中的 `template.yaml`、symbolic name 或完整路徑。
- Context 是空值：檢查大寫核心節點及 ID；stage/action ID 不可包含 `.`。
- Tool 驗證失敗：檢查 unknown、missing required 或 duplicate argument。
- YAML cell 失敗：確認內容是 map，模板 selector 包含 `name`。
- 中文連結：V2 使用 Unicode-safe anchor，可支援中文 Case ID、模板名及路徑。

更多配置錯誤診斷及常見問題可參考 [Reference Manual V2](09_Reference_Manual_V2.md)。

## 10. 案例開發參考

### 10.1 新增案例行

1. Excel 表頭必須與 sidecar 完全一致。
2. 每個 sheet 的 row Case ID 必須有效；ATT 會自動組成 `<groupId>.<rowCaseId>`。
3. 只有 sidecar 以 `(yaml)` 標識的欄位才會解析 YAML，Excel 實際表頭不包含 `(yaml)`。
4. required stage 的模板欄必須是包含 `name` 的 YAML map。
5. 優先執行 `./att.sh validate` 再執行案例。

案例欄位示例：

| 欄位 | 示例 |
|---|---|
| 案例編號 | `TC012` |
| 案例名稱 | `退款金額小數測試` |
| 標籤 | `refund,edge,decimal` |
| 預期結果 | `status: SUCCESS\nrejectCode: '0000'` |
| 執行模板 | `name: PAYMENT_INVOKE` |
| 驗證模板 | `name: PAYMENT_VERIFY` |

### 10.2 Stage、Template、Action 設計

每個 stage 未配置時，ATT 預設 `runWhen: normal`、`onFailure: stop`：主流程失敗後，後續 normal stage 不再執行。`runWhen: onSuccess` 適合驗證；`runWhen: onFailure` 適合補償或診斷；`runWhen: always` 適合清理。`onFailure: continue` 只應用於不阻斷後續流程的證據收集，且不會將案例結果改為 PASS。

例如 invoke 使用預設 normal/stop，verify 使用 onSuccess，rollback 使用 onFailure，cleanup 使用 always：

```yaml
- {key: invoke, template: 執行模板, required: true}
- {key: verify, template: 驗證模板, required: true, runWhen: onSuccess}
- {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
- {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

完整的判斷表與非阻斷診斷場景見 [Reference Manual V2：Stage execution semantics](09_Reference_Manual_V2.md#55-stage-execution-semantics)。

模板目錄必須直接包含 `template.yaml`。大型 XML、JSON、YAML 或文字內容應放在模板目錄的 request 文件中，由 `render` action 產生輸出。

action 的 `onFailure` 與 stage 的設定獨立：每個 action 只可設為 `stop` 或 `continue`，未設定即為 `stop`。`stop` 停止同一模板後續 action；`continue` 僅容許後續 action 執行，仍會保留失敗結果。詳見 [Reference Manual V2：Action field reference](09_Reference_Manual_V2.md#62-action-field-reference)。

目前模板的結果可用 `${ACTIONS.<actionId>.output}` 讀取；跨 stage 的工具結果使用 `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS.<actionId>.TOOL.<toolName>.output}`。

### 10.3 表達式與工具呼叫

字串 literal 必須加引號；數字及布爾值可保留原生型別：

```yaml
expression: "${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'"
expression: "${ACTIONS.selectTxn.output.effectRows} >= 1 and true"
```

內置函數可處理簡單轉換：

```text
#{upper(value=${CASE.currency})}
#{coalesce(${CASE.optionalReference}, 'NO-REFERENCE')}
#{boolean(yes)}
```

外部工具只接受已在 `config/config.yaml` 宣告的命名參數。最後一個參數可使用 `delimit`，例如把 `PAYMENT,POSTED` 解析為有順序的多個命令參數。

### 10.4 開發檢查表

- Workbook 與 sidecar 檔名相同且位於同一目錄。
- Case ID、標籤及模板名稱清晰且唯一。
- YAML cell 可解析，且不與 stage data key 重複。
- 工具參數沒有 unknown、missing required 或 duplicate。
- 範例及 log 不包含敏感資料。
- `./att.sh validate --suite ...` 通過後再執行選定案例。

完整配置、Context、報告、打包及診斷內容見 [ATT V2 Reference Manual](09_Reference_Manual_V2.md)。
