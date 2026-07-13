# ATT V2.2 新手入門

本指南用一套中文 Excel 案例帶你完成 ATT V2.2 的工具、工具組、模板、案例、嚴格驗證、執行、報告、CI 輸出、文件及打包流程。V2.2 的關鍵原則是：先讓整個套件通過驗證，再執行；每個輸出目錄、結果狀態和證據檔都有清楚、可追溯的含義。

本指南面向案例作者。完整欄位契約、診斷 JSON、輸出資料結構及限制見 [ATT V2.2 Reference Manual](09_Reference_Manual_V2.md)。

## 1. 核心關係

```text
test case --1:n stage--> template --1:n action--> tool
```

Test case、template、tool 是核心概念。Stage 只定義一個案例調用模板的數量及順序；Action 只定義模板調用工具或執行 render/assert/log 的數量及順序。

## 2. 先理解 V2.2 的工作方式

一次正常 run 會依序完成：

```text
validate + plan
  → output/.in-progress/<RunID>-<nonce>/
  → 執行並保留每個案例／工具／重試證據
  → 原子發佈 output/<RunID>/
  → 原子更新 output/latest-run.yaml
```

只有完整完成的 run 才能用 `report`、`build` 或 `rerun-failed`。中途中斷的 run 留在 `.in-progress`，不會被誤當成完成結果。

V2.2 的狀態不可混淆：

| 狀態 | 意義 | 例子 |
|---|---|---|
| PASS | 框架及業務驗證都成功 | 回應狀態為 `SUCCESS` |
| FAIL | 框架成功執行，但業務斷言不成立 | 狀態為 `REJECTED`，但預期 `SUCCESS` |
| ERROR | 工具、超時、解析、I/O 或框架執行失敗 | script exit code 非 0、JSON 格式錯誤 |
| SKIPPED | 刻意未執行 | `--dry-run` 或不符合 `runWhen` |
| INVALID | 驗證不通過，不能排程 | 未知字段、缺少 template、非法 Case ID |

若同一 run 同時有 FAIL 和 ERROR，整體 exit code 是 `3`；只有 FAIL 而沒有 ERROR 時才是 `1`。

## 3. 目錄

```text
config/config.yaml
config/tools/payment.yaml
testcase/支付回歸.xlsx
testcase/支付回歸.yaml
templates/payment/local/CT001/template.yaml
templates/payment/local/CT001/request.tmp.xml
tools/invoke_payment_api.sh
```

全域 `testcase.root` 預設為 `testcase`。ATT 會遞歸掃描其任意子目錄；同一目錄內的 `basename.yaml` 與 `basename.xlsx` 配對後就是一個測試案例集。

只有直接包含 `template.yaml` 的目錄才是模板；上例模板完整路徑是 `payment/local/CT001`。

## 4. 建立嚴格的全域配置

```yaml
schemaVersion: att-config/v2.2
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog:
  yamlAnchors: false
testcase:
  root: testcase
templates:
  root: templates
run:
  id:
    default: timestamp
    timestampFormat: yyyyMMdd-HHmmss
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  junit:
    caseLogEmbedThresholdBytes: 10240
xml:
  namespaceMode: ignore
toolGroups:
  - config/tools/payment.yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: 調用付款 API
    command:
      - ./tools/invoke_payment_api.sh
      - "${requestFile}"
      - "${environment}"
    output: json
    arguments:
      requestFile: {name: Request File, description: 已渲染 XML, required: true}
      environment: {name: Environment, description: 執行環境, required: true}
```

`schemaVersion` 必填。V2.2 對 config、tool group、sidecar、template 和 action 採嚴格 schema：未定義字段會在 validate 報錯；若要保存工具自訂但 ATT 不解讀的資料，字段必須以 `x-` 開頭。既有 `tools` 仍是全局工具，可繼續使用未加前綴的調用名稱。

`caseLog.yamlAnchors` 預設為 `false`，相同 Map/List 會在 Case log 每處完整輸出。設為 `true` 時，YAML 可使用 `&id001`／`*id001` 形式的 anchor/alias 以減少重複內容；它們只是 YAML 引用，不是 ATT ID。

Case log 中 `ERROR`、`FAIL`、`INVALID` 區塊會以 `【!!!!!】` 開頭，例如 `【!!!!!】[ACTION invokeApi]`。可直接搜尋 `【!!!!!】` 快速定位異常日誌。

工具 `output` 可為 `txt`、`yaml`、`json` 或 `xml`。`arguments` 用於驗證及工具文件；每個參數都需 `name`、`description`、`required`，只有最後一個參數可增加 `delimit`。

`command` 推薦使用多行 argv list：每一項就是一個 argv，不會再次分詞。原有單行字串仍支持，ATT 只在載入時拆分一次，再統一轉為 argv list。具名參數優先以 `${requestFile}` 直接引用，需要明確命名空間時使用 `${input.requestFile}`；名稱大小寫必須與 arguments key 完全一致。command 只能引用已聲明參數，不能直接引用 `${CASE...}` 或 `${ACTIONS...}`。工具將結果寫到 stdout、診斷寫到 stderr。只有 action 明確設定 `saveAs` 時才另存 raw stdout。

例如下列 `note` 可包含空格並保持為一個 argv：

```yaml
command: "./tools/invoke_payment_api.sh '${requestFile}' --label 'Payment regression'"
```

工具組是獨立配置文件。`config/tools/payment.yaml` 的最小例子：

```yaml
schemaVersion: att-tool-group/v2.2
id: payment
name: Payment tools
description: Payment integration commands
script: ["./tools/payment_dispatch.sh"]
tools:
  invoke:
    name: Invoke payment
    description: Invoke one payment request
    command: ["send", "${requestFile}", "${environment}"]
    output: json
    arguments:
      requestFile: {name: Request File, description: 已渲染 XML, required: true}
      environment: {name: Environment, description: 執行環境, required: true}
```

調用名稱是 `payment.invoke`。有 `script` 時，實際邏輯 argv 是 `./tools/payment_dispatch.sh invoke send <requestFile> <environment>`；沒有 `script` 時，tool command 的第一項就是 executable。

全局 config 或個別工具組都可配置一個 SSH 目標：

```yaml
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519
```

ATT 預設使用本地 `ssh` command；若 `PATH` 找不到 `ssh`，會提示並改用內置 mwiede/jsch Java library。OpenSSH 可使用 SSH agent 或 `identityFile`；Java fallback 不繼承 OpenSSH agent／`~/.ssh/config`，通常需設定 `identityFile`，並要求 `~/.ssh/known_hosts`。兩者均執行 strict host-key checking，不支持 password 欄位。工具輸出及 retry/assert/saveAs 行為與本地執行相同；Java/JSch 算法限制見 Reference Manual Chapter 09。

不要這樣將案例資料直接拼到 command：

```yaml
# 錯誤：全域 tool 不能隱式依賴 runtime Context
command: "./tools/invoke_payment_api.sh --note ${CASE.note}"
```

建立最小可執行 mock tool `tools/invoke_payment_api.sh`：

```sh
#!/usr/bin/env sh
set -eu

request_file="${1:?missing request file}"
environment="${2:?missing environment}"

[ -f "$request_file" ] || {
  echo "request file not found: $request_file" >&2
  exit 2
}

printf '{"status":"SUCCESS","environment":"%s"}\n' "$environment"
```

```sh
chmod +x tools/invoke_payment_api.sh
```

## 5. 建立 V2.2 模板與 JSON 工具輸出

`templates/payment/local/CT001/template.yaml`：

```yaml
schemaVersion: att-template/v2.1
name: 本地付款
description: 產生付款 XML 並調用 API
actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: "${CASE.caseId}-request.xml"
    assert: "${ACTIONS.renderRequest.output} != ''"
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
    saveAs: "${CASE.caseId}-response.json"
    overwrite: false
    assert: "${ACTIONS.invokeApi.output.status} == '${CASE.expected.status}'"
    # 可覆蓋 config/sidecar 的 timeoutMs；單位為毫秒
    timeoutMs: 30000
    retry:
      maxAttempts: 3
      retryOn: [EXIT_CODE]
```

`request.tmp.xml`：

```xml
<PaymentRequest>
  <CaseId>${CASE.caseId}</CaseId>
  <DebitAccount>${CASE.debitAccount}</DebitAccount>
  <Amount>${CASE.amount}</Amount>
</PaymentRequest>
```

Action type 決定可用字段：

- `render` 必須有 `payload`；使用 `output.mode: file` 時必須有 `saveAs`；可用 `assert` 直接驗證 render 結果，但不能 retry。
- `tool` 必須有一個已配置的 `call`；可用 `assert` 直接驗證結果，也可用 `saveAs` 保存 raw stdout，並設定 `timeoutMs` 與 retry。
- `saveAs` 支援 `${...}` Context 表達式，文件位於 case log 所在目錄。模板開發者需確保名稱唯一；`overwrite` 預設為 `false`，同名文件已存在時會報錯。
- `assert` 必須有非空 `expression`；不允許 retry，輪詢請交給明確的 tool action。
- `log` 必須有非空 `message`，可有 `level` 和 `fields`；不允許 retry。

全域 `timeoutMs` 的單位是毫秒；上例為 10 秒。sidecar 可用同名 `timeoutMs` 覆蓋全域值；個別 tool action 再以 `timeoutMs` 覆蓋已解析的全域／sidecar 值。`timeoutMs` 只可用於 tool action，範圍為 1–3600000。

retry 只適用於 tool action 的 `EXIT_CODE`。超時、配置錯誤、參數錯誤、輸出解析錯誤和 assertion FAIL 都不會 retry；V2.2 沒有 retry delay 或 backoff 設定，符合條件的重試會立即進行。每次嘗試的證據直接寫入 case log/action record，不建立 `attempt-001/` 等目錄；有 `saveAs` 時，最後一次嘗試會覆蓋前一次嘗試的結果文件。

## 6. 中文 Excel 和 sidecar

Excel 表頭：

| 案例編號 | 案例名稱 | 標籤 | 扣賬帳號 | 金額 | 預期結果 | 執行模板 |
|---|---|---|---|---:|---|---|
| TC001 | 本地付款成功 | smoke,付款 | 111111 | 100 | `status: SUCCESS` | `name: 本地付款` |

相鄰的 `testcase/支付回歸.yaml`：

```yaml
schemaVersion: att-sidecar/v2.1
id: payment
excel:
  sheet: 支付測試案例集
  headerRows: 1
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
timeoutMs: 60000
```

`report.columns` 的 key 是 ATT 結果字段，value 是 Excel 實際表頭。若來源工作表已存在映射表頭（例如「測試結果」），ATT 會直接填充該欄；只有不存在的映射表頭才按配置順序追加到工作表末尾。

模板單元格可寫成含 `name` 的 YAML map，也可直接寫一行 YAML scalar shorthand。`name` 或 scalar 值有兩種寫法：填寫模板 `template.yaml` 中定義的 symbolic name（例如 `本地付款`），或填寫相對於 `templates.root` 的完整模板目錄路徑（例如 `payment/local/CT001`）；兩者都用來唯一選定要執行的模板。例如 `PAYMENT_INVOKE` 等價於 `name: PAYMENT_INVOKE`。scalar 會由 ATT 正規化為 `name` stage data；map 的所有 key-value 都會加入 stage data。`N/A`、`NA`、`NULL`、`NONE` 和空白會正規化為 blank。未知 sidecar 字段、未知 stage 字段、錯誤資料型別和重複 YAML key 都是 validation ERROR。詳細規則見 [Reference Manual V2.2：Workbook sidecar](09_Reference_Manual_V2.md#workbook-sidecar)。

多 sheet 使用：

```yaml
sheet: payment=支付測試案例集, batch=批量測試案例集
```

完整 Case ID 分別是 `payment.payment.TC001`、`payment.batch.TC001`。第一段來自 sidecar `id`，第二段來自 `excel.sheet` 的 group ID，第三段來自 Excel 案例編號。

Case ID 同時是輸出目錄名，完整路徑為 `output/<RunID>/<workbookId>.<groupId>.<rowCaseId>/`。請使用可讀、穩定的值，例如 `payment.payment.TC001`。sidecar `id` 在 package 內必須唯一；三個 ID component 都不能是空白、`.`、`..`，不能含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|` 或控制字符，不能以空白或 `.` 結尾，也不能使用 `CON`、`NUL`、`COM1` 等保留名稱。ATT 不會 slugify 或 hash Case ID；合法 ID 原樣成為目錄名。

HTML report 的 Groups 會按 `workbookId.groupId` 統計。Cases 可用 Workbook、Sheet、Status 下拉列表過濾；搜尋框匹配 workbook、sheet、完整 Case ID 和 tags；點擊任一列頭可切換升序／降序。

### 多行表頭

如果 Excel 前兩行是表頭，在 sidecar 的 `excel` 下設定 `headerRows: 2`。ATT 會逐欄由上到下尋找最後一個非空表頭 cell 作為實際欄名，不會把多行文字拼接：

```text
第 1 行：基本資料 |        | 執行資訊 |        |
第 2 行：案例編號 | 案例名稱 | 執行模板 | 執行參數 |
有效欄名：案例編號、案例名稱、執行模板、執行參數
```

`headerRows` 預設為 `1`；資料從表頭列之後開始。有效欄名重複、找不到必填欄位或 `headerRows < 1` 都會在 validate 階段報錯。詳細規則見 [Reference Manual V2.2：Workbook sidecar](09_Reference_Manual_V2.md#workbook-sidecar)。

## 7. 表達式

- `${CASE.amount}`：讀取 Context。
- `${CASE.STAGES.invoke.TEMPLATE.ACTIONS.invokeApi.TOOL.invokePaymentApi.output}`：跨 stage 完整路徑。
- `${ACTIONS.invokeApi.output}`：目前 template 的便捷路徑。
- `#{tool(name='中文,字串', count=2, enabled=true)}`：調用工具，支援字串、數字、布爾 literal。

核心節點 `CASE`、`STAGES`、`TEMPLATE`、`ACTIONS`、`TOOL` 使用大寫；`caseId`、`outputFile` 等 metadata 使用 camelCase。

`CASE`、`STAGE`、`TEMPLATE`、`ACTION`、`TOOL` 的所有內建屬性、適用時機及完整路徑，見 [Reference Manual V2.2：Runtime Context](09_Reference_Manual_V2.md#runtime-context)。

ATT 內置函數包括：

- `upper(value=...)`：轉換為大寫；
- `lower(value=...)`：轉換為小寫；
- `trim(value=...)`：移除前後空白；
- `string(value=...)`、`number(value=...)`、`boolean(value=...)`：執行型別轉換；
- `length(value=...)`：取得字串長度；
- `concat(...)`：按參數順序串接文字；
- `coalesce(...)`：返回第一個非 blank 值。
- `nvl(value, defaultValue)`：value 為 null/空字串時返回預設值；
- `iif(condition, trueValue, falseValue)`：按布爾條件選值；
- `nchar(count, value)`：重複文字，例如 `nchar(3, '9')` 返回 `999`。

例如：

```text
#{upper(value=${CASE.currency})}
#{coalesce(${CASE.optionalReference}, 'NO-REFERENCE')}
#{boolean(yes)}
#{nvl(${CASE.optionalReference}, 'NO-REFERENCE')}
#{iif(${CASE.enabled}, 'Y', 'N')}
#{nchar(3, '9')}
```

完整函數清單、參數規則及字面量語法見 [Reference Manual V2.2：Expressions and built-in functions](09_Reference_Manual_V2.md#built-in-functions)。

## 8. 先驗證，再執行

```sh
# 預設 --package：檢查整個套件，包括未被案例引用的 template/tool
./att.sh validate --package

# 快速迭代：只驗證選中案例及其依賴閉包
./att.sh validate --selected --suite testcase/支付回歸.xlsx --case payment.payment.TC001

# CI：stdout 只有一份可機讀 JSON；進度與診斷走 stderr
./att.sh validate --package --format json

./att.sh run --suite testcase/支付回歸.xlsx
./att.sh run --all
./att.sh run --all --case payment.payment.TC001
./att.sh run --all --tag smoke
./att.sh run --all --ci-output junit,json
```

同一個 output 目錄同時收到多個 `run` 時，可選擇：

```bash
./att.sh run --all             # 默認：已有 run 時立即拒絕
./att.sh run --all --queue     # 顯示排隊提示，等前一個 run 完成
./att.sh run --all --parallel  # 真正並行，各自寫入獨立 in-progress 目錄
```

ATT 會在 validation/progress 輸出前預檢 Run ID，並在 planning／取得排隊鎖後再次檢查。若已存在，會直接輸出包含重複 ID 與路徑的 `ATT-RUN-001`，不會顯示「Executing cases」或調用工具。若只在完成發布時才發生競態碰撞，ATT 會保留既有 run，並使用第一個可用的 `-2`、`-3` 等序號發布本次結果。請以完成訊息輸出的最終 Run ID 和 report 路徑為準。

`validate --package` 是預設模式；它可不帶 testcase filter，並會找出未被引用但壞掉的 template 或 tool。`--selected` 僅驗證選定案例的依賴，速度較快，但輸出會明示未驗證其餘內容。

範例 validation JSON：

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.2.0",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null, "row": null, "column": null,
    "template": "PAYMENT_VERIFY", "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

每條 diagnostic 都包含穩定 code、severity、檔案、字段，並在適用時提供 sheet、row、column、template、action 和修正建議。

不帶參數或使用 `--help` 顯示完整用法。

驗證錯誤代碼、選擇規則及 stage 執行語義見 [Reference Manual V2.2：Validation JSON contract](09_Reference_Manual_V2.md#validation-json-contract)。

## 9. 報告、CI、文件、打包與清理

```sh
./att.sh report --run-id <RunID>
./att.sh docs
./att.sh build
./att.sh clean
```

- 單頁測試報告：`output/<RunID>/report/index.html`
- 單頁套件文件：`build/docs/index.html`
- 結果工作簿：`output/<RunID>/workbooks/`
- CI JSON：`output/<RunID>/ci/summary.json`
- CI JUnit XML：`output/<RunID>/ci/junit.xml`
- JUnit HTML 報告：`output/<RunID>/report/junit.html`（可直接開啟閱讀）
- 最近完成 run 的 archive：`build/att-<RunID>.tar.gz`

Run ID 也直接是 `output/<RunID>/` 的目錄名，遵循與 Case ID 相同的非法字符及保留名稱限制。`report --run-id` 只接受合法 Run ID，不接受檔案路徑。

`report --run-id` 會同時重建 `report/index.html` 和 `report/junit.html`；run 目錄或 manifest 若是跳出 output root 的 symlink，命令會拒絕處理。

清理只針對 ATT 產生的最終用戶輸出：

- `./att.sh clean`：只清除設定的 `outputDirectory`、`build/docs` 及 `build/att-*.tar.gz`；不會清除案例、模板、工具、設定或文件。

`clean` 拒絕清除專案根目錄、專案外目錄、source/configuration directory 或會跳出專案的 symlink。

報告欄位、CI 輸出、單頁 HTML 內容及 archive 內容詳見 [Reference Manual V2.2：Report Reference](09_Reference_Manual_V2.md#08-report-reference)。

## 10. 常見問題與安全提醒

- 找不到模板：檢查目錄中的 `template.yaml`、symbolic name 或完整路徑。
- Context 是空值：檢查大寫核心節點及 ID；stage/action ID 不可包含 `.`。
- Tool 驗證失敗：檢查 unknown、missing required 或 duplicate argument。
- YAML cell 失敗：確認內容是 map 或 scalar shorthand；檢查重複 key 和未知字段。
- 中文連結：V2.2 使用 Unicode-safe anchor，可支援中文 Case ID、模板名及路徑。
- JSON／XML output ERROR：檢查 raw output 和 parser diagnostic；JSON duplicate key、非合法 JSON，以及 XML DTD/外部 entity 都會被拒絕。
- ERROR 與 FAIL：ERROR 表示執行可靠性問題，優先查看 tool attempt、stdout、stderr、raw output 和 case log；FAIL 表示 assertion 的預期與實際不一致。

更多配置錯誤診斷及常見問題可參考 [Reference Manual V2.2](09_Reference_Manual_V2.md)。

## 11. 案例開發參考

### 11.1 新增案例行

1. Excel 表頭必須與 sidecar 完全一致。
2. sidecar `id` 必須在 package 內唯一；每個 sheet 的 row Case ID 必須有效，ATT 會自動組成 `<workbookId>.<groupId>.<rowCaseId>`。
3. 只有 sidecar 以 `(yaml)` 標識的欄位才會解析 YAML，Excel 實際表頭不包含 `(yaml)`。
4. required stage 的模板欄可為包含 `name` 的 YAML map，或直接使用 scalar shorthand。
5. Case ID 必須通過非法字符檢查，因為它會原樣成為輸出目錄名。
6. 優先執行 `./att.sh validate --package`；只做局部開發時使用 `--selected`。

案例欄位示例：

| 欄位 | 示例 |
|---|---|
| 案例編號 | `TC012` |
| 案例名稱 | `退款金額小數測試` |
| 標籤 | `refund,edge,decimal` |
| 預期結果 | `status: SUCCESS\nrejectCode: '0000'` |
| 執行模板 | `name: PAYMENT_INVOKE` |
| 驗證模板 | `name: PAYMENT_VERIFY` |

### 11.2 Stage、Template、Action 設計

每個 stage 未配置時，ATT 預設 `runWhen: normal`、`onFailure: stop`：主流程失敗後，後續 normal stage 不再執行。`runWhen: onSuccess` 適合驗證；`runWhen: onFailure` 適合補償或診斷；`runWhen: always` 適合清理。`onFailure: continue` 只應用於不阻斷後續流程的證據收集，且不會將案例結果改為 PASS。

例如 invoke 使用預設 normal/stop，verify 使用 onSuccess，rollback 使用 onFailure，cleanup 使用 always：

```yaml
- {key: invoke, template: 執行模板, required: true}
- {key: verify, template: 驗證模板, required: true, runWhen: onSuccess}
- {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
- {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

完整的判斷表與非阻斷診斷場景見 [Reference Manual V2.2：Stage execution controls](09_Reference_Manual_V2.md#stage-execution-controls)。

模板目錄必須直接包含 `template.yaml`。大型 XML、JSON、YAML 或文字內容應放在模板目錄的 request 文件中，由 `render` action 產生輸出。

action 的 `onFailure` 與 stage 的設定獨立：每個 action 只可設為 `stop` 或 `continue`，未設定即為 `stop`。`stop` 停止同一模板後續 action；`continue` 僅容許後續 action 執行，仍會保留失敗結果。詳見 [Reference Manual V2.2：Template and action](09_Reference_Manual_V2.md#template-and-action)。

目前模板的結果可用 `${ACTIONS.<actionId>.output}` 讀取；跨 stage 的工具結果使用 `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS.<actionId>.TOOL.<toolName>.output}`。

### 11.3 表達式與工具呼叫

字串 literal 必須加引號；數字及布爾值可保留原生型別：

```yaml
expression: "${ACTIONS.callApi.output.status} == 'SUCCESS'"
expression: "${ACTIONS.selectTxn.output.effectRows} >= 1 and true"
```

內置函數可處理簡單轉換：

```text
#{upper(value=${CASE.currency})}
#{coalesce(${CASE.optionalReference}, 'NO-REFERENCE')}
#{boolean(yes)}
```

外部工具只接受已在 `config/config.yaml` 或其 `toolGroups` 文件宣告的命名參數。全局工具使用 `tool(...)`，組內工具使用 `group.tool(...)`。最後一個參數可使用 `delimit`，例如把 `PAYMENT,POSTED` 解析為有順序的多個命令參數。

### 11.4 V2.2 開發檢查表

- config、sidecar 和 template 都有正確的 `schemaVersion`。
- Workbook 與 sidecar 檔名相同且位於同一目錄。
- Case ID、標籤及模板名稱清晰且唯一。
- YAML cell 可解析，沒有重複 key、未知字段或錯誤資料型別，且不與 stage data key 重複。
- 工具參數沒有 unknown、missing required 或 duplicate。
- 工具組 ID 唯一，使用 `group.tool` 調用；argv list 每項都是預期的一個 process argument。
- SSH 工具已在執行主機準備 key/agent 與 known-host 記錄，且 package validation 無法替代連線測試。
- 每個 action 都符合其 type 專屬字段要求；只為可安全重試的 tool action 設定 retry。
- JSON/XML tool output 選擇正確，並以解析後結構撰寫 assertion。
- 範例及 log 不包含敏感資料。
- `./att.sh validate --package` 通過後再執行選定案例。
- CI 使用 `--ci-output junit,json`，並保留 `ci/summary.json`、`ci/junit.xml`、`report/junit.html` 和 run manifest。

完整配置、Context、報告、打包及診斷內容見 [ATT V2.2 Reference Manual](09_Reference_Manual_V2.md)。
