# ATT v3.7.4 使用手冊與參考

Author: Jeffrey + ChatGPT
Version: 3.7.4
Status: 規範性使用者文件；由模組化來源自動生成

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->

**目錄**

- [概覽與 Product model](#概覽與-product-model)
  - [產品模型](#產品模型)
  - [三種執行模式是同級概念](#三種執行模式是同級概念)
  - [五種 Resource 是同級概念](#五種-resource-是同級概念)
  - [Package 邊界](#package-邊界)
  - [如何使用本手冊](#如何使用本手冊)
- [Test authoring](#test-authoring)
  - [編寫並執行 Testcase](#編寫並執行-testcase)
  - [區分 Testcase 與 Case execution](#區分-testcase-與-case-execution)
  - [編寫契約](#編寫契約)
  - [Workbook](#workbook)
  - [Stage](#stage)
  - [Template](#template)
  - [Flow](#flow)
  - [Authoring lifecycle](#authoring-lifecycle)
  - [Test data ownership](#test-data-ownership)
  - [Testdata registry 與 input mapping](#testdata-registry-與-input-mapping)
  - [進階 Workbook 與 Snapshot 細節](#進階-workbook-與-snapshot-細節)
- [Actions 與 typed values](#actions-與-typed-values)
  - [Action 類型](#action-類型)
  - [區分邏輯值與表示方式](#區分邏輯值與表示方式)
  - [File-content expression 回傳 string](#file-content-expression-回傳-string)
  - [Tool、DB 與 Flow 結果](#tooldb-與-flow-結果)
  - [Tool Evidence collector](#tool-evidence-collector)
  - [Log：將型別化值轉成人類可讀日誌](#log將型別化值轉成人類可讀日誌)
  - [Expressions 與變數 scope](#expressions-與變數-scope)
  - [Resource output Evidence](#resource-output-evidence)
  - [Action output 與 Evidence path](#action-output-與-evidence-path)
  - [共用 Retry 與 boolean condition](#共用-retry-與-boolean-condition)
- [Runtime 與 Context model](#runtime-與-context-model)
  - [Identity roots](#identity-roots)
  - [META 欄位清單與生命週期](#meta-欄位清單與生命週期)
  - [Invocation 與 scope 規則](#invocation-與-scope-規則)
  - [Optional lookup 與相容性](#optional-lookup-與相容性)
  - [Lifecycle 導覽](#lifecycle-導覽)
- [Expressions 與 built-ins](#expressions-與-built-ins)
  - [統一 Expression engine](#統一-expression-engine)
  - [File-content expression](#file-content-expression)
  - [Argument 結果型別](#argument-結果型別)
  - [Testdata input mapping 語法](#testdata-input-mapping-語法)
  - [操作符](#操作符)
  - [內建函數](#內建函數)
  - [Resource helper methods](#resource-helper-methods)
  - [Expression scope 與錯誤](#expression-scope-與錯誤)
  - [Retry condition 的生命週期](#retry-condition-的生命週期)
- [Execution modes](#execution-modes)
  - [Run 模式](#run-模式)
  - [Standalone Debug](#standalone-debug)
  - [Load 模式](#load-模式)
- [Resources 與 integrations](#resources-與-integrations)
  - [Operation result 與 Evidence](#operation-result-與-evidence)
  - [Tool](#tool)
  - [DBHelper](#dbhelper-1)
  - [MQHelper](#mqhelper-1)
  - [HTTPHelper](#httphelper-1)
  - [SSHHelper：邏輯 SSH 目標](#sshhelper邏輯-ssh-目標)
- [可靠性與執行控制](#可靠性與執行控制)
  - [Assertion 與 status](#assertion-與-status)
  - [`runWhen` 與 `onFailure`](#runwhen-與-onfailure)
  - [Timeout](#timeout)
  - [Retry 與 attempts](#retry-與-attempts)
  - [Evidence collectors](#evidence-collectors)
  - [Transaction/Resource lifecycle](#transactionresource-lifecycle)
  - [Aggregation](#aggregation)
  - [Stage execution controls](#stage-execution-controls)
  - [Tool Timeout precedence](#tool-timeout-precedence)
  - [Direct DB timeout 與 retry eligibility](#direct-db-timeout-與-retry-eligibility)
- [Configuration 與 environments](#configuration-與-environments)
  - [按任務查找配置](#按任務查找配置)
  - [配置層與優先級](#配置層與優先級)
  - [使用 `X-` 忽略或停用 ATT 配置項](#使用-x-忽略或停用-att-配置項)
  - [選擇 environment profile](#選擇-environment-profile)
  - [Schema catalog](#schema-catalog)
  - [設定 package-wide options](#設定-package-wide-options)
  - [標識符和路徑約束](#標識符和路徑約束)
  - [Topology 與 secrets](#topology-與-secrets)
  - [Cross-mode consistency](#cross-mode-consistency)
  - [分開的 Configuration files](#分開的-configuration-files)
  - [`config.report.fileNamePattern`](#configreportfilenamepattern)
  - [Configuration owners](#configuration-owners)
- [CLI 參考](#cli-參考)
  - [選擇命令](#選擇命令)
  - [按任務查閱語法、option 和範例](#按任務查閱語法option-和範例)
  - [Typed overrides and quick Load](#typed-overrides-and-quick-load)
  - [Debug input 與 output](#debug-input-與-output)
  - [退出碼](#退出碼)
  - [完整 CLI option matrix](#完整-cli-option-matrix)
- [結果、報告與 evidence](#結果報告與-evidence)
  - [查找 Run artifacts](#查找-run-artifacts)
  - [在 HTML 報表中查看 Case execution 結果](#在-html-報表中查看-case-execution-結果)
  - [診斷 Tool evidence collector 失敗](#診斷-tool-evidence-collector-失敗)
  - [將 execution 結果寫入 Workbook](#將-execution-結果寫入-workbook)
  - [匯出 JUnit 結果](#匯出-junit-結果)
  - [查看 CI JSON 彙總](#查看-ci-json-彙總)
  - [重現 Run](#重現-run)
  - [生成文檔並管理 package 輸出](#生成文檔並管理-package-輸出)
  - [從 execution identity 追蹤 artifact](#從-execution-identity-追蹤-artifact)
  - [查閱 generated-output schemas](#查閱-generated-output-schemas)
  - [Reading `case.log` and `case.yaml`](#reading-caselog-and-caseyaml)
- [Validation 與 troubleshooting](#validation-與-troubleshooting)
  - [選擇第一個診斷步驟](#選擇第一個診斷步驟)
  - [先從 Validation 開始](#先從-validation-開始)
  - [常見問題](#常見問題)
  - [安全提醒](#安全提醒)
  - [Validation JSON 合約](#validation-json-合約)
- [CI、打包與運維](#ci打包與運維)
  - [Development/release gates](#developmentrelease-gates)
  - [Runtime dependencies](#runtime-dependencies)
  - [Documentation operations](#documentation-operations)
  - [CI 與 environment promotion](#ci-與-environment-promotion)
- [Appendix A — schema 與 version matrix](#appendix-a-schema-與-version-matrix)
- [Appendix B — compatibility 與 deprecated aliases](#appendix-b-compatibility-與-deprecated-aliases)
- [Appendix C — migration notes](#appendix-c-migration-notes)
  - [File arguments 與 case log paths](#file-arguments-與-case-log-paths)
  - [Previous release Testdata migration](#previous-release-testdata-migration)
  - [Load mix migration](#load-mix-migration)
  - [Historical schema migration](#historical-schema-migration)
  - [Debug schema migration](#debug-schema-migration)
  - [Environment profile migration](#environment-profile-migration)
- [Appendix D — limits、security guarantees 與 advanced diagnostics](#appendix-d-limitssecurity-guarantees-與-advanced-diagnostics)
  - [Limits 與預設值](#limits-與預設值)
  - [Collector projection 與 redaction guarantees](#collector-projection-與-redaction-guarantees)
  - [Advanced diagnostics](#advanced-diagnostics)
## 概覽與 Product model

ATT 把測試意圖與整合機制分離。測試數據以 workbook／sidecar／snapshot 版本化；Template 與 Flow 定義可重用行為；Resource 把這些行為連接到外部系統。

### 產品模型

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

**Testcase** 是作者編寫並正規化的 Workbook row。**Case execution** 是 Run 對該 Testcase 的一次執行；其完整 Case ID 標識 Testcase，並沿用於執行結果和 evidence。討論 Workbook data 和 authoring 時使用 *Testcase*；討論 runtime status、日誌、報表和 artifacts 時使用 *Case execution*。**Stage** 選擇 Template 並提供 stage-private data；**Template** 是可執行 scenario 邊界；**Flow** 是具有獨立 Action scope 的可重用 Template 邏輯；**Action** 是一個有序工作單元；**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper、MQHelper、HTTPHelper 或 SSHHelper。

### 三種執行模式是同級概念

Run、Debug、Load 把不同輸入適配到同一 execution-neutral Context 和同一批 reusable components：

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | workbook Testcase 與 Stage selector | Template、Flow、Tool、DB/MQ/HTTP/SSH |
| Debug | `att-debug/v1.1` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.6` scenario | 重複執行一個或多個 Template、Flow 或 Tool workload |

可重用 Template/Flow 應依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。執行模式與 scheduler identity 只保留在 framework evidence，不會成為 expression data。

### 五種 Resource 是同級概念

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是獨立 Resource 類型。它們的配置與 lifecycle 不同，但 Action 會透過 `output.result` 發布原生 typed result，並將可選的 presentation evidence 分開保存。公開 expression 應讀取 Action result/evidence，而不是 resource 內部 connection/process state。

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

### Package 邊界

一般 package 包含 `config/`、`testcase/`、`templates/`、`tools/`、`schemas/` 和生成的 `output/`。ATT 在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

需要逐步建立一個可工作的 package，請使用 `docs/quick-start.md`；本 Reference 其餘內容是規範性查閱文件。

### 如何使用本手冊

| 目標 | 文件 |
|---|---|
| 建立第一個 ATT package | [Quick Start](quick-start.zh.md) |
| 理解核心 ATT model | [Product model](reference.zh/overview.md)、[Test Authoring](reference.zh/test-authoring.md)、[Actions](reference.zh/actions.md)、[Runtime and Context](reference.zh/runtime-context.md) 和 [Run mode](reference.zh/execution-modes/run.md) |
| 配置 DB/MQ/HTTP/SSH | [Resources](reference.zh/resources/overview.md) |
| 查閱 CLI option | [CLI Reference](reference.zh/cli.md) |
| 診斷失敗 | [Validation and Troubleshooting](reference.zh/validation-diagnostics.md) |
| 升級舊 package | [Migration Notes](reference.zh/appendices/migrations.md) |

Reference 定義 public contract；README、Quick Start 與 examples 按特定任務說明這份 contract。每項 contract 由一個 semantic owner 定義，其他 Reference 頁面提供摘要並連結至 owner。

## Test authoring

### 編寫並執行 Testcase

Testcase 是一個正規化 Workbook row；Run 會為每個選中的 row 建立一次 Case execution。按以下流程編寫、驗證和執行變更。

1. 編輯 Workbook，並在 Sidecar 中映射欄位。
2. 產生 Snapshot 並檢查差異。
3. 使用 ./att.sh validate --package 驗證 package。
4. 使用 [Debug](reference.zh/execution-modes/debug.md) 隔離 Template、Flow 或 Tool，再使用 [Run](reference.zh/execution-modes/run.md) 建立 Case execution。

以下各節定義 authoring contract。公式單元格、多行表頭和 XML 序列化詳情見[進階 Workbook 與 Snapshot 細節](#進階-workbook-與-snapshot-細節)。

### 區分 Testcase 與 Case execution

**Testcase** 是 Sidecar mapping 和 Snapshot normalization 後由作者編寫的 Workbook row。它的完整 Case ID 格式為 `workbookId.groupId.rowCaseId`。**Case execution** 是 Run 對該 Testcase 的一次 runtime 執行，並有自己的 status 和 evidence。兩者共用 identifier，但概念不同。本頁使用 *Testcase* 表示 Workbook 內容，使用 *Case execution* 表示執行工作。

### 編寫契約

本頁說明正常日常工作流中的數據流轉順序。

| 需求 | 使用 |
|---|---|
| Stage execution entry point | Template |
| 可重用 Template logic | Flow |
| 一個有序 operation | Action |
| 外部能力 | Resource |
| Testcase/Stage business input | EXEC.INPUT |
| 跨 Action mutable state | EXEC.VARS |

### Workbook

#### Workbook、Sidecar 和 Snapshot 之間的關系

每個 `.xlsx` Workbook 都要求有一個 YAML Sidecar 和一個生成的 XML Snapshot，它們必須具有相同的基名且位於同一目錄：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

Sidecar 將 Excel 結構映射為 ATT 概念。它負責 sheet 映射、表頭、Testcase 數據、有序 Stage 及可選報告列標簽；timeout/retry 不屬於 Workbook 配置。

```yaml
schemaVersion: att-sidecar/v2.2
id: paymentRegression
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, options=執行參數(yaml)
    required: true
    runWhen: normal
    onFailure: stop
```

根 `id` 是必需的，並且必須在整個包中唯一。`excel.sheet` 可以接受一個 sheet 名稱，或以逗號分隔的 `groupId=sheetName` 條目。如果只給出一個 sheet 且沒有 group ID，ATT 會使用 `default`。完整 Case ID 的形式始終是 `workbookId.groupId.rowCaseId`，並且必須在整個包中唯一。

在修改 Excel 後，執行 `./att.sh snapshot --suite testcase/payment_regression.xlsx`。生成的 `payment_regression.xml` 使用模式 `att-testcases/v2.4`，並僅存儲歸一化後的 Sidecar 映射語義。它保留 group、Case、標簽、map/list 和 Stage 順序，使用顯式值類型，並排除樣式和無關 Workbook 內容。請審查並提交生成的 XML；不要手工修改。

普通 `run` 和每一種 `validate` 模式都會保持只讀，如果 XML 缺失、無效、非規範或過期，則會在輸出創建前失敗。`run --update-snapshot` 會顯式允許 ATT 在應用相同驗證與校驗規則前，僅為選中的完整 Workbook 刷新已更改的 Snapshot。它不會寫入部分 Case/標簽 Snapshot，不會在更新期間調用 Tool，拒絕 Snapshot 符號鏈接，並且當與 `--dry-run` 組合使用時仍會執行授權更新。字節內容完全相同的 Snapshot 不會被重寫。

#### 映射數據列

`dataColumns` 可以接受：

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

普通列作為字符串進入 Context。`(yaml)` 列則會把顯示的單元格值解析為 YAML 標量、列表或映射。

雙引號可保護逗號、等號和括號：

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

最後的 `(yaml)` 是 ATT 的解析標記。在最後一個例子中，物理 Excel 表頭名是 `請求(yaml)`。

#### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空單元格和僅包含空白字符的值都會歸一化為空白。普通空白數據值會變為空字符串。空白 `(yaml)` 單元格則保持為空白，不進行解析。

必需 Stage 選擇器會拒絕空白值。可選 Stage 如果選擇器為空白，則跳過。

#### Workbook / Sidecar

| 對象 | 允許屬性 | 必填/約束 |
|---|---|---|
| 根對象 | `schemaVersion`、`id`、`excel`、`stages`、`report`、`x-*` | `schemaVersion`、包內唯一 `id`、`excel`、非空 `stages` 必需 |
| `excel` | `sheet`、`headerRows`、`caseId`、`tags`、`dataColumns` | `sheet`、`caseId`、`tags` 必需；`headerRows >= 1` |
| `stages[]` | `key`、`template`、`dataColumns`、`required`、`runWhen`、`onFailure` | `key`/`template` 必需；`key` 不能含點號 |
| `report` | `columns` | 值為字符串 |

只有 Sidecar 根對象允許 `x-*`；`excel`、stages 和 Sidecar `report` 拒絕擴展和其他未知字段。Sidecar 不能覆蓋 timeout、retry、Tool、Template 根、環境或輸出根。

### Stage

每個 Sidecar Stage 都有一個不含點號的 `key`，以及一個命名物理 Excel 選擇器列的 `template` 字段。選擇器單元格可以包含符號 Template 名、完整相對 Template 路徑，或 YAML 映射：

| 單元格值 | 含義 |
|---|---|
| `PAYMENT_INVOKE` | 符號名稱簡寫 |
| `payment/local/CT001` | 相對 `templates.root` 的完整路徑簡寫 |
| `name: PAYMENT_INVOKE` | 明確的符號名稱映射 |
| `name: PAYMENT_INVOKE` 加其他鍵 | Template 選擇 + Stage 私有行數據 |

ATT 會先將 `name` 作為全局唯一的符號名解析。只有在沒有符號名匹配時，才會嘗試完整相對 Template 路徑。絕對路徑、部分路徑、以及逃逸出 `templates.root` 的路徑都是非法的。

所有選擇器映射鍵（包括 `name`）都會復制到 Stage Context 中。`stages[].dataColumns` 會增加更多 Stage 私有值。選擇器映射與 Stage 數據列之間如果出現重復鍵，則報錯。


Stage 的 `required`、`runWhen` 與 `onFailure` 規則見 [Reliability](reference.zh/reliability-execution-control.md)。

### Template

只有直接包含 template.yaml 的目錄纔是可呼叫 Template。ATT 使用 att-template/v3.6。每個 Template 都需要非空且有序的 actions map，以及 description。

每個 Action 依類型使用不同契約。`&{templates/payment/request.xml}` 這類 file-content expression 會將 exact UTF-8 檔案內容作為 String 回傳，不會建立檔案。Tool/DB/HTTP/MQ/SSH action 發布原生型別化 operation result。Log 將 typed value 格式化為人類可讀內容。Assign 將值發布至 EXEC.VARS；Flow 在巢狀 Action scope 執行。

完整欄位、範例、typed result/evidence model、HTTP/MQ/SSH boundary 與 migration guidance，請參閱[Action 與型別化值](reference.zh/actions.md)。[Expressions and Built-ins](reference.zh/expressions.md) 定義共用 expression language；[Load](reference.zh/execution-modes/load.md) 定義 ID initialization scope。

### Flow

Flow 是可重用的 Template logic，使用 `att-flow/v3.6`，並由 `flow.yaml` 定義。必填欄位為 `schemaVersion`、versioned canonical `id`（例如 `common.payment.v1`）、`name`、`description` 及非空有序 `actions` map。Template 的 Flow Action 以 `use: common.payment.v1` 呼叫它。每次 invocation 建立新的 `EXEC.ACTIONS` scope；回傳後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。[Actions](reference.zh/actions.md) 定義 Flow result 與 Assign behavior；[Context](reference.zh/runtime-context.md) 定義 lifetime。

### Authoring lifecycle

更新 Workbook 後產生 Snapshot，檢視並提交差異；編輯 Sidecar、Template、Flow 或 Resource 後執行 `./att.sh validate --package`。用 [Debug](reference.zh/execution-modes/debug.md) 隔離檢查，再以 [Run](reference.zh/execution-modes/run.md) 從 Testcase 建立 Case execution。循序建立第一個 package 請使用 [Quick Start](quick-start.zh.md)。

### Test data ownership

Workbook/Sidecar/Snapshot 定義 Testcase data；Testcase 與 Stage 的 business input 進入 `EXEC.INPUT`。[Context](reference.zh/runtime-context.md) 定義 scope 與 lifetime；Run 會把每個選中的 Testcase 轉為 Case execution。Environment selection 由 [Configuration](reference.zh/configuration.md) 定義。

### Testdata registry 與 input mapping

可用 `att-testdata/v1.0` descriptor 儲存可重用 records；只在 Case、Stage、Debug `inputs` 或 Load workload `inputs` mapping 中引用。完整 `@{id}` 會保留 record 的原生 map/list/scalar 型別；`@{id.path}` 可讀取巢狀值，也支援數字 list index。內嵌參照（例如 `"ORD-@{accounts.id}"`）會產生文字，因此所選 record 的欄位必須是 scalar。Mapping 中的 `${...}` 只能讀取該 mapping 解析前已初始化的 Context root。允許的 root 依 mapping 階段而異，並會在 execution 開始前驗證（Load 則在 scheduler 啟動前驗證）：

- Run Case/Stage mapping 可讀取 `EXEC.ID`、`EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、`EXEC.OUTPUT_DIR`，以及 `META.PACKAGE_ROOT`、`META.SOURCE` 或 `META.TARGET`。
- Debug `inputs` 可讀取相同的 execution root 與 metadata，另加 `META.TEMPLATE`。
- Load workload `inputs` 可讀取 `EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、已初始化的 `EXEC.LOAD` identity fields，以及 `META.PACKAGE_ROOT`、`META.SOURCE`、`META.TARGET` 或 `META.TEMPLATE`。`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化。Arrival-rate workload 不提供 `EXEC.LOAD.USER_ID`；若 mapping 要同時支援兩種模型，請使用 optional path `${EXEC.LOAD.USER_ID?}`。

所有 mode 都拒絕引用 `EXEC.INPUT`（正在建立的值）、`EXEC.VARS`、`EXEC.ACTIONS`、Action `output` 及 invocation-scoped helper metadata。V1 mapping grammar 會評估 literal、selected-record `@{...}` reference 及 `${...}` Context reference；不會評估 built-in call。`#{...}`、`&{...}` 和 `%{...}` 都不是 input-mapping expression。

~~~yaml
schemaVersion: att-testdata/v1.0
id: accounts
records:
  - {id: "A-100", tier: gold}
  - {id: "A-200", tier: silver}
selection: {strategy: sequential, exhaustion: recycle}
~~~

Generated records 為 virtual 並可按 index 存取；ATT 只物化被選中的 record。Inclusive integer range 上限為 1,000,000 筆，且 `%{seq}` 是唯一支援的 generated-record substitution：

~~~yaml
schemaVersion: att-testdata/v1.0
id: generatedAccounts
records:
  generate:
    seq: {from: 100, to: 999999, format: "%06d"}
  record: {id: "A-%{seq}", amount: 42}
selection: {strategy: roundRobin, exhaustion: stop}
~~~

Environment profile 的 `testdata` list 宣告共享 registry。Load scenario 可在頂層宣告本地 `testdata` imports；同 ID 會在該次 Load 完整取代 environment descriptor。單一 layer 內的重複 ID 會報錯。一般 Run/Debug 只延遲啟用實際引用的 ID；`validate --package` 會檢查全部配置 descriptors。Template、Flow 與 Tool 定義只能透過 `EXEC.INPUT` 取得已解析資料，不可直接寫 `@{...}` 或 `%{...}`。詳見[Environment 與 Test Data](reference.zh/configuration.md)、[Load](reference.zh/execution-modes/load.md) 及維護者的 [testdata design](system-design/testdata.zh.md)。

### 進階 Workbook 與 Snapshot 細節

#### 安全地正規化 XML 文字

包含 LF 或 XML 特殊字符 `&`、`<`、`>` 的字符串值會使用 CDATA；文字 `]]>` 會被拆分成相鄰 CDATA 段，並在解析時精確重建。LF 前的空格或製表符會使用 `&#32;`/`&#9;` 插入兩個 CDATA 段之間，從而保留值而不觸發 Git 行尾空白警告。請審查並提交該 XML；不要手工修改它。

#### 公式、日期、百分比和科學記數法單元格

V2.4 會拒絕在配置的 Case ID、標簽、Case 數據、Stage 選擇器和 Stage 數據列中使用公式單元格。公式定義與緩存/顯示結果可能不一致，因此不能用於生成可信的語義 Snapshot。請在 Excel 中重新計算後將結果粘貼為字面值，或者在專門的 ATT 步驟中進行計算。

與配置 Testcase 列相交且位於 `excel.headerRows` 以下的合並區域也會被拒絕。完全位於配置表頭區域內的合並展示單元格則允許。

對於非公式單元格，ATT 導入顯示文本。其精確表示遵循 Workbook 單元格格式和運行時區域設置：

| Excel 值與格式 | Context 值 |
|---|---|
| `45292` 格式化為 `yyyy-mm-dd` | `2024-01-01` |
| `0.125` 格式化為 `0.0%` | `12.5%` |
| `123000` 格式化為 `0.00E+00` | `1.23E+05` |
| `000123` 以文本形式存儲/格式化 | `000123` |

普通列仍然是字符串。`(yaml)` 列可能將顯示文本轉換為其他 YAML 類型。對於日期、百分比、科學計數、賬號或代碼這類文本，應該使用引號把 YAML 標量包起來，以便保持為字符串。

#### 多行表頭

`headerRows: 2` 表示第 1–2 行是表頭，數據從第 3 行開始。ATT 會掃描每個物理列從上到下，使用最後一個非空且已去除首尾空白的表頭單元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不會拼接父子標簽。表頭匹配會移除空格、製表符、換行符、NBSP 以及其他 Unicode 空白字符；匹配其餘部分仍區分大小寫。例如，`案例 編號`、`案例\n編號`、`案例編號` 會被視為同一列。每個有效表頭在歸一化後必須唯一，因此僅因空白差異而不同的兩個物理表頭會被認為是重復表頭錯誤。Testcase 加載和結果 Workbook 寫回使用相同的投影邏輯；結果列如果原本不存在，則會寫入最終表頭行。

## Actions 與 typed values

本頁定義 ATT 現行 Action 契約。Template 使用 att-template/v3.6。每個完成的 Action 都會在 output.result 發布邏輯型別化值；Action 不使用共用的 result.format/path/overwrite 物件。Resource 配置請從[Resources](reference.zh/resources/overview.md)開始查閱。

### Action 類型

| 類型 | 必填欄位 | 結果與行為 |
|---|---|---|
| tool | call | 呼叫已配置 Tool、built-in 或 helper，保留原生型別化結果；DB query/scalar/update 也是普通 Tool call。 |
| assert | assert | 評估布林條件並記錄 PASS 或 FAIL。expected、actual 是可選診斷值。 |
| log | message 或 value | 將型別化值格式化後寫入 Case 日誌。欄位為 message、value、format。 |
| assign | name、expression | 將 expression 的型別化結果發布至 EXEC.VARS。 |
| flow | use | 在巢狀 Action scope 執行已註冊 Flow，返回時還原 caller scope。 |

Actions 按 YAML 順序執行。依類型允許時，也可定義 id、description、onFailure、runWhen。Action ID 在 scope 內必須唯一。類型不支援的欄位會在 validation 失敗。共用 Action result、Log file 與 Log fields 不屬於現行契約。

關於以小寫 `x-` 在 validation 或 execution 前停用 ATT 擁有的 Action 字段/keyed entries，以及它與 `runWhen: false` 的差別，請見[Configuration](reference.zh/configuration.md#使用-x-忽略或停用-att-配置項)。

### 區分邏輯值與表示方式

ATT 將 operation 的邏輯結果與人類可讀或 wire representation 分開：

| Boundary | 欄位/值 | 用途 |
|---|---|---|
| Command Tool stdout | stdoutFormat | 將外部 stdout 解析為型別化結果。 |
| HTTP/MQ response | responseFormat | 將外部 response bytes 解析為型別化結果。 |
| File-content expression | `String` | 讀取安全的 UTF-8 package file，並在 expression evaluation 後保留其字元。 |
| 透過 HTTP/MQ 傳送抽象 Map/List | requestFormat | 在 outbound boundary 序列化該值。 |
| Log 或 resource evidence | format / evidence.output.format | 產生人類可讀表示。 |

DB result 本身已是型別化值。Tool、Action、Template、Flow 和 expression results 在 ATT 中傳遞時均保留型別。

DB query、scalar、update operation 在普通 `type: tool` Action 內使用 `db.<helper>.query(...)`、`db.<helper>.scalar(...)`、`db.<helper>.update(...)`。DB call 接受一個 String `sql`，以及 `params` 或 `parameters` 其中一種；`sql=&{package-relative-file.sql}` 可提供 package SQL 內容。歷史 `type: db` Action 只由 archived schema 保留。

### File-content expression 回傳 string

歷史 Render Action 的現行替代方式是 typed file-content value expression `&{path}`。它一定回傳一個 `String`，不會推斷 document format、parse 副檔名、展開 glob 或建立輸出檔：

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
~~~

`${...}` 仍然是 Context reference，`#{...}` 仍然是 expression/call，`&{...}` 是 static、one-file locator；v1 沒有 glob 或 dynamic locator。Locator 相對 canonical ATT package root。`./` 或 `../` descriptor-relative path 只有在 canonical target 仍在該 root 內才允許。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白和 glob syntax 都會在 validation 失敗。

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

requestFormat 僅供 Map 或 List 等抽象結構化值使用。此類 body 必須明確指定格式，例如 requestFormat=json。String 與 requestFormat 同時出現會失敗，確保 file-content result 不會被靜默 parse/serialize。HTTP、MQ、SSH Resource call 會將 file-content content 作為 String value 消費，不會解析 local file-path argument。

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

### Tool Evidence collector

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

Collector result 遵守一般 typed-result 規則。放在 evidence 下不代表會轉成 String；Map、List 和 file-content `String` 均保留型別。在 Load 中，明確要求的 collector execution 與 helper `evidence.output` serialization 是兩件事；resource-output 格式化仍由 Load evidence policy 控制，不會靜默取代或刪除 author-requested collector。

### Log：將型別化值轉成人類可讀日誌

Log 是 presentation Action，因此有自己的 format 欄位：

~~~yaml
logOrder:
  type: log
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

Log 是一般 Case-log entry，沒有 user-authored severity；現行 v3.6 契約移除 `level`，migration 時請刪除該欄位。歷史 v3.4/v3.5 Template／Flow descriptor 為 compatibility 仍接受其 schema 定義的 Log level。Internal diagnostic severity 維持獨立。message 或 value 至少要有一項。message 以文字求值。value 可接受任意型別化值，包括巢狀 map/list。完整的 ${...} 和 #{...} expression 保留原始型別；map/list 子節點會遞迴求值，不會將數字、布林、null 或巢狀值轉成字串。format 支援 text、json、yaml、xml、sqlplus，只控制寫入 Case 日誌的字串。指定 format 時必須提供 value。

同時提供 message 和 value 時，Log 輸出 message、換行，再輸出格式化 value。output.result 是最終字串。File-content String 會原樣輸出；Log 不會推斷或附加 document format，也不會讀取檔案或使用 fields map。需要結構化日誌時，將 typed map/list 放到 value。

### Expressions 與變數 scope

Action expression 使用一般 ATT expression engine。完整 ${...} 或 #{...} expression 保留結果型別；expression 放在一般字串內才會成為 String。請參閱[Expressions and Built-ins](reference.zh/expressions.md)。

assign 會將 typed value 發布至 EXEC.VARS.<name> 一次。name 必須符合 [A-Za-z_][A-Za-z0-9_]*，且在 Case 中唯一。一個 Stage 指派的值可供後續 Stage 使用。Action 執行期間可讀取 output.*，發布後可讀取 EXEC.ACTIONS.<id>.output.*。Flow 有暫時 Action namespace；若 caller 在 Flow 返回後仍需要該值，請發布到 EXEC.VARS。

### Resource output Evidence

Resource evidence 與邏輯 result 分離。Helper 可設定可選的 evidence.output presentation policy：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

此設定會在 operation metadata 旁加入有長度上限的人類可讀 Snapshot，不會改變 output.result 或 response parsing。Load 的 evidence.resources.output 可設 inherit（預設）或 none。none 會略過 resource-output 格式化與檔案物化。Metrics-only iteration 不建立 execution 目錄。Iteration evidence 被保留後，符合條件的 resource output 才會延遲格式化至該 workspace。

### Action output 與 Evidence path

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

### 共用 Retry 與 boolean condition

Tool Action（包括可重試的 DB query/scalar call）共用 `retry` 契約。`maxAttempts`（2–10）、`intervalMs`（0–3600000）及非空 `retryOn`（ASSERTION/TIMEOUT）仍為必填；`when` 是可選的非空 Boolean expression String。Mutating DB update 及 SSH transfer 的既有 retry 限制維持不變。

~~~yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [TIMEOUT]
  when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

每個 attempt 先執行 operation、發布當前 result/evidence/diagnostic、評估可用 assertion，然後分類 retry category。只有 `retryOn` 符合且尚有 attempt 可執行時，才評估 `when`。未配置時維持一般 retry 行為；true 才等待 interval 並重試，false 保留當前 TIMEOUT/FAIL 且不重試。沒有符合 category、成功或達到 maxAttempts 時均不評估 gate。

`when` 可讀取 `output.status`、`output.result`、`output.evidence`、`output.diagnostic`、從 1 開始的 `output.attempt`，以及當前 scope 允許的 EXEC/META path。Top-level output 在每次 attempt 開始時清除，不會讀到前次的 result/evidence。歷史紀錄保留於 `output.attempts[n]`；`retryDecision` 記錄 category、candidate、whenEvaluated、whenResult（有評估時）、allowed 與 reason（例如 WHEN_FALSE、MAX_ATTEMPTS）。

Condition 使用正常 `${...}`/`#{...}` 型別規則，必須回傳 Boolean；字串 'false' 或數字不會轉成 Boolean。`when: "#{false}"` 可停止 retry。Strict missing path 與 expression error 使用一般 diagnostic，定位至 retry.when 並停止重試。Pure deterministic built-in 可使用；Tool/DB/MQ/HTTP/SSH、file/file-content、sequence、random 與 current-time operation 均禁止。可確定的 syntax/type error 在 validation 時拒絕；runtime result 的型別與 missing path 在 gate 評估時檢查。

TIMEOUT 是 canonical Action outcome；suite/report aggregate 的 operation failure 仍為 ERROR。對 MQ request、HTTP POST 等非冪等操作，作者必須決定是否可重播。未加 when 的 TIMEOUT retry 可能重複 business transaction；ATT 不會默默抑制 MQ retry。請參閱 [MQHelper 範例](reference.zh/resources/mqhelper.md)。

## Runtime 與 Context model

Run、Debug、Load 共用 canonical EXEC/META expression model。EXEC 透過 framework lifecycle 和明確的 input/variable/action publication 更新。META 是 curated、immutable、secret-safe 的描述資訊。

Standalone Debug bootstrap value 會映射到這些 canonical root：`inputs` 寫入 `EXEC.INPUT`，Template/Flow 的 `vars` 會在 target 開始前 seed `EXEC.VARS`。v1.1 schema、typed literal 規則及受保護的 framework roots 請參考 [Standalone Debug](reference.zh/execution-modes/debug.md)。

### Identity roots

| 路徑 | 意義與型別 | 可用時機 |
|---|---|---|
| EXEC.ID | 目前 Case、Debug execution 或 Load iteration ID；String。 | execution initialization 後；Load 在 business action 前產生。 |
| EXEC.RUN_ID | 外層 ATT Run ID；String。 | 三種模式均可用，同一 run 的 Cases/iterations 共用。 |
| EXEC.STARTED_AT | 目前 execution 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.RUN_STARTED_AT | 外層 run 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.OUTPUT_DIR | execution workspace 絕對路徑。 | 初始化後可用；Load 中可能先有 planned path，尚未實際建立。 |
| EXEC.INPUT | Case/stage、Debug sidecar 或 Load workload input map。 | 三種模式均可用；Stage input 暫時 overlay Case input。 |
| EXEC.VARS | assign 發布的型別化值。 | 三種模式均可用；指派前為空。 |
| EXEC.ACTIONS | active Template/Flow scope 已完成的 Actions。 | 發布 Action 後可用；Flow 有巢狀 scope。 |
| EXEC.LOAD.MODEL | closed 或 arrivalRate。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.WORKLOAD_ID | 配置的 workload ID。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.USER_ID | 穩定 virtual user ID。 | 僅 closed Load；arrival-rate 缺席。 |
| EXEC.LOAD.ITERATION | 數值 iteration 序號。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.PHASE | WARMUP、RAMP_UP、STEADY 或 RAMP_DOWN。 | 僅 Load；ID 產生前可用。 |

EXEC.LOAD 只公開穩定 identity。Scheduler counter、queue state 與 timing diagnostics 留在 evidence-only DIAG.load，不能作為 expression root。

### META 欄位清單與生命週期

公開 META root 包含 `PACKAGE_ROOT`、`SOURCE`、`TARGET`、`TEMPLATE`、`FLOW`、`TOOL`、`DBHELPER`、`MQHELPER`、`HTTPHELPER` 和 `SSHHELPER`。`META.PACKAGE_ROOT` 是 active ATT package root 的正規化絕對路徑，在 Run、Debug 和 Load package binding 後全程可用。舊有 `META.PROJECT.id` 與 `META.PROJECT.root` 已移除，不提供相容 alias。ATT installation scope (`ATT_HOME`)、package scope (`META.PACKAGE_ROOT`) 和 execution output scope (`EXEC.OUTPUT_DIR`) 各自獨立。META 只包含描述欄位；元件在目前 mode/scope 尚未 active 時，相應路徑可能不存在。

| 公開路徑 | 意義、type 與範例 | Mode 與可用時機 | Scope 與缺席時機 |
|---|---|---|---|
| META.PACKAGE_ROOT | 正規化絕對 package root 路徑；String，例如 `/srv/att/payment`。 | Run、Debug、Load；package binding 後。 | Execution-wide。 |
| META.SOURCE.type | Source 類型；String：`testcase`、`debug` 或 `load`。 | Run、Debug、Load。 | Execution-wide。 |
| META.SOURCE.path | 正規化絕對 source path；String，例如 `/srv/att/payment/testcase/payment.xlsx`、`/srv/att/payment/debug.yaml` 或 `/srv/att/payment/load/payment.yaml`。 | Run、Debug、Load，source file 存在時。 | Execution-wide；memory source 可缺席。 |
| META.SOURCE.caseId | Canonical TestCase 或 synthetic Debug Case ID；String，例如 `payment.default.P001`。 | Run、Debug。 | Execution-wide；Load 缺席。 |
| META.SOURCE.workbookId | Workbook identifier；String，例如 `payment`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.groupId | Workbook group identifier；String，例如 `default`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.rowCaseId | Workbook row 的 Case ID；String，例如 `P001`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.sheet | Workbook sheet 名稱；String，例如 `Payment`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.row | Workbook 由 1 起始的 row number；Number，例如 `12`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.workbook | 可選 workbook label；有值時為 String，例如 `payment_regression.xlsx`。 | Run，adapter 有提供時。 | Execution-wide；否則缺席。 |
| META.SOURCE.scenario | 不含副檔名的 Load scenario 名稱；String，例如 `payment-smoke`。 | Load。 | Execution-wide；非 Load 時缺席。 |
| META.TARGET.type | 已解析 target 類型；String：`testcase`、`template`、`flow` 或 `tool`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TARGET.id | 已解析 target identifier；String，例如 `PAYMENT_INVOKE` 或 `payment.flow.v1`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TEMPLATE.id | Active Template 或已解析 Load execution-wrapper ID；String，例如 `PAYMENT_INVOKE`。 | Run/Debug 的 Stage 執行期間；Load 的 target resolution 後及 wrapper 執行期間。 | Component scope；解析前缺席，scope 結束後 restore/remove。Load Flow/Tool target 使用已解析的 synthetic wrapper。 |
| META.TEMPLATE.path | 正規化 Template 或 execution-wrapper 目錄；String，例如 `/srv/att/templates/PAYMENT_INVOKE`。 | 與 META.TEMPLATE.id 相同。 | Component scope；解析前缺席，scope 結束後 restore/remove。 |
| META.FLOW.id | Active Flow ID；String，例如 `payment.request.v1`。 | Run、Debug、Load 的 Flow invocation 期間。 | Invocation scope；進入 push、返回 restore、inactive 時缺席。 |
| META.FLOW.invocationId | Caller Action ID；String，例如 `sendRequest`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.FLOW.depth | 由 1 開始的巢狀 Flow 深度；Number，例如 `1`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.TOOL.id | Active configured Tool 或 built-in 名稱；String，例如 `payment.queryOrder` 或 `upper`。 | Run、Debug、Load invocation 期間。 | Invocation scope；返回後不保留最後一次 Tool。 |
| META.TOOL.type | Invocation 類型；String，例如 `tool` 或 `builtin`。 | 與 META.TOOL.id 相同。 | 外層 invocation 不存在時，返回後缺席。 |
| META.DBHELPER.id | Logical DBHelper ID；String，例如 `orders`。 | Run、Debug、Load 的 DBHelper operation/DB Action 期間。 | Invocation scope；外層 scope 不存在時返回後缺席。 |
| META.DBHELPER.type | Resource 類型；String，`dbhelper`。 | 與 META.DBHELPER.id 相同。 | 外層 scope 不存在時返回後缺席。 |
| META.MQHELPER.id | Logical MQHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 MQHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.MQHELPER.type | Resource 類型；String，`mqhelper`。 | 與 META.MQHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.id | Logical HTTPHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 HTTPHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.type | Resource 類型；String，`httphelper`。 | 與 META.HTTPHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |
| META.SSHHELPER.id | Logical SSHHelper ID；String，例如 `application`。 | Run、Debug、Load 的 SSH Resource Helper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.SSHHELPER.type | Resource 類型；String，`sshhelper`。 | 與 META.SSHHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |

META.SSHHELPER 只公開 logical helper ID 和 resource type。SSH endpoint、user、identity file、credentials 留在 executor 內部，不會公開在 META。

ATT 會遞迴過濾 password、secret、token、authorization/cookie、API key、private key 等 secret-bearing keys。Expressions 與 adapters 可讀 META，但不能修改。

### Invocation 與 scope 規則

進入 Template、Flow、Tool、helper invocation 時，公開該 active scope 的 metadata。巢狀呼叫會 push frame，離開時 restore 前一份 metadata。沒有 active invocation 時，其 branch 缺席。請勿依賴「最後一次呼叫」狀態。

EXEC.INPUT 是 canonical input map。Stage 暫時 overlay Case input，完成後還原。EXEC.VARS 可供同一 Case 後續 Stages 共用。EXEC.ACTIONS 屬於 active Template/Flow。Action 執行期間讀 local output，完成後發布到 EXEC.ACTIONS.<id>.output。

### Optional lookup 與相容性

${path} 是 strict lookup。${path?} 在允許的 map/list 缺失路徑回傳 null；不會讓錯誤語法或不合法 scope access 變有效。CASE、RUN、ACTIONS alias 只在能一對一對應 canonical data 時保留。新 Template 請使用 EXEC/META。

### Lifecycle 導覽

Action-local `output` 與發布後的 `EXEC.ACTIONS.<id>.output` contract 見[Actions](reference.zh/actions.md)。[Debug](reference.zh/execution-modes/debug.md) 與 [Load](reference.zh/execution-modes/load.md) 分別定義 bootstrap vars、identity initialization 與可用 scope；[Results](reference.zh/results-reports-evidence.md) 定義 artifact navigation。

## Expressions 與 built-ins

### 統一 Expression engine

ATT 的 runtime Template、Flow、Action、Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map 或 List；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。File-content 內容、Action description/assert、Log message/value、assign expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[Action 與型別化值](reference.zh/actions.md)。

### File-content expression

`&{path}` 是 file-content expression：它讀取 active ATT package root 內一個靜態指定的 regular UTF-8 檔案，並回傳原始 String content。它不會推斷或解析文件格式、展開 glob 或建立 output file。Descriptor-relative 的 `./` 與 `../` 只有在 canonical target 仍位於 `META.PACKAGE_ROOT` 內時才允許。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白、glob syntax 及 dynamic locator 都會在 validation 失敗。File content 不會自動解析為 JSON、YAML 或 XML。

### Argument 結果型別

Argument expression 會先遞迴求值，再呼叫接收它的 function。Target argument contract 只套用在最終結果：例如 `str.substr` 先接收 `&{...}` 產生的 file String，再回傳 String，最後 outer target argument 才執行型別與 range 檢查。因此 `waitMs=#{str.substr(&{params/wait.txt}, 0, 4)}` 在結果為 integral value 時可供 numeric helper 使用；String payload 則保留所有原始字元。Scalar String 只會在數值或 Boolean parsing 時 trim，不會全域修剪 String/payload。Map/List 會保留型別；不會依副檔名或檔案內容推斷並解析結構。

Package validation 會在 inputs 靜態可知時解析 literal、static file content 及明確 pure built-in，再套用與 runtime 相同的 target argument contract。Runtime-dependent expression 會檢查結構及已宣告型別，並將最終值/range 檢查留待執行；validation 絕不執行 Tool、resource 或 external call 來取得值。

Standalone value 或嵌入較大 expression 時，請使用 YAML string：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

檔案內的 `${...}` 與 `#{...}` 會在 file value 使用時編譯及求值。Run 與 Debug 會 cache compiled plan，並在 file fingerprint 改變時失效；Load 會為 scenario freeze 已驗證的 file identity、content 及 compiled plan。File output 不會再被當作新的 expression source 解析。

### Testdata input mapping 語法

Testdata reference 會在準備 Case/Stage、Debug 或 Load input map 時解析，不屬於一般 `${...}` / `#{...}` expression engine。使用 `@{id}` 保留所選 record 的原生型別；`@{id.object.path}` 或 `@{id.items[0]}` 可讀取巢狀值；scalar interpolation 可組合文字。同一 mapping/lifetime 中，一個 logical ID 只選一次，因此該 mapping 內對同 ID 的引用會得到同一筆 record。Interpolation 不接受 null、map 或 list。`${...}` 可讀取已初始化 Context，但不能讀取 `EXEC.INPUT`，因為 input 建構不能依賴自身。Reusable Template、Flow 和 Tool definition 不可直接包含 testdata marker；它們只會使用已解析的 `EXEC.INPUT` 值。

Descriptor 和 generated record 語法見[Testdata Registry 與 Input Mapping](reference.zh/test-authoring.md)。

### 操作符

支持的斷言操作符有：

- `==`
- `!=`
- `>`
- `>=`
- `<`
- `<=`
- `like`
- `in`
- `is null`
- `is not null`
- `not`
- `and`
- `or`

`like` 是大小寫不敏感的操作符關鍵詞，但規範寫法使用小寫。它匹配完整值，並使用 SQL 風格通配符：

- `%` 匹配零個或多個字符
- `_` 匹配恰好一個字符
- 匹配本身是大小寫敏感的

### 內建函數

只有作者直接撰寫的 file-expression node 才會求值。Context value、Tool result 和 file output 即使包含 `&{...}`，亦維持 literal String。檔案內的 Context path 和 call 依 enclosing Action 的一般 ordering、scope 和 resource validation 規則驗證。V1 在 Run、Debug、validation 和 Load snapshot discovery 都拒絕 file-content 內容中的巢狀 `&{...}`，包括 `#{...}` argument 內的 locator。

內建函數通過 `#{...}` 調用。Canonical 名稱使用 framework-owned `str.*`、`date.*`、`file.*`、`misc.*` 與 `seq.*` package；舊 flat 名稱保留為兼容 alias。Tool group 同樣以 `group.tool` 組成 package-like 調用名；配置 Tool 不得佔用 built-in package root 或任何 canonical／legacy built-in 名稱。

| 函數 | 目的 | 示例 |
|---|---|---|
| `seq.next` | 返回 run-scoped `Long`；可選名稱及寬度用於獨立計數或精確寬度的零填充文字 | `#{seq.next('payment', 10)}` |
| `str.upper/lower/trim` | 大小寫與首尾空白處理 | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.ltrim/rtrim` | 去除前導／尾隨空白 | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | 返回文本長度 | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | 拼接參數 | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr/indexOf` | 截取子串／返回位置 | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.contains/startsWith/endsWith` | 測試字面包含、前綴、後綴 | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.replace` | 字面替換 | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad/rpad` | 左／右填充 | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | 重復值 | `#{str.repeat(3, '9')}` |
| `date.sysdate/systimestamp` | 返回系統日期／時間戳 | `#{date.sysdate('yyyyMMdd')}` |
| `date.format` | 格式化 ISO 日期 | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | 日期增減 | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `misc.string/number/boolean` | 類型轉換與歸一化 | `#{misc.number(value='12.50')}` |
| `misc.coalesce/nvl` | 返回非空值或默認值 | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | 從布爾值選擇兩個值之一 | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | 從輸入中隨機選擇 | `#{misc.randomChoice('A', 'B', 'C')}` |

#### `Seq.next` Run-scoped 序列

`seq.next` 支援以下四種位置參數 overload（同一組參數亦可使用 `name`、`width` 具名傳入；不可混用具名與位置參數）：

| 呼叫 | 計數器 | 返回值 |
|---|---|---|
| `#{seq.next()}` | 預設序列 | 遞增的 Java `Long` |
| `#{seq.next('payment')}` | 名為 `payment` 的獨立序列 | 遞增的 Java `Long` |
| `#{seq.next(10)}` | 預設序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |
| `#{seq.next('payment', 10)}` | 名為 `payment` 的序列 | Java `String`，十進位值左側補零至恰好 10 個字元 |

預設序列與每個具名序列互相獨立，且各自從 1 開始。狀態由單次 ATT Run 擁有：Run 內所有 Testcase／suite 共用計數器；Debug 的單次 execution 使用新 service；Load 的所有 workload 與並行 iteration 共用計數器。每個序列計數器均為 thread-safe，在該 Run 內發出唯一且單調遞增的值；並行排程不保證哪個 VU 取得哪個值。新 Run、新 Debug execution 或新 Load run 都會重新從 1 開始。不公開 `EXEC.SEQUENCES` Context node，也沒有 reset/current API。

| 模式 | 範例用法 | Scope 說明 |
|---|---|---|
| Testcase | 在 `assign` Action 中：`expression: "#{seq.next('payment', 10)}"` | 同一 Run 的連續 Cases 共用 `payment` 計數器。 |
| Debug | 在 `assign` Action 中：`expression: "#{seq.next()}"` | 新的單次 Debug execution 從 1 開始。 |
| Load | 在 target Template/Flow 的 `assign` Action 中：`expression: "#{seq.next('load-order', 10)}"` | Iterations 共用計數器；並行呼叫唯一，但不保證每個 VU 的固定分配順序。 |

`width` 必須是 1 至 1000 的整數。序列名稱必須是非空白文字；只有一個位置參數時，數字代表 `width`，字串代表序列名稱。超過兩個參數、混合具名與位置參數、無效參數型別、空白名稱、小數／零／負數／超出範圍的 width 都會報錯；diagnostic 會指出 `seq.next` 及錯誤的參數數量、型別或範圍。若補零後的數值位數超過 `width`，或底層 `Long` 計數器溢位，求值會明確失敗；ATT 不會截斷序列值，也不會默默超出指定寬度。





### Resource helper methods

已配置的 `db.*`、`mq.*`、`ssh.*` 和 `http.*` 使用相同的 `#{...}` 語法，但它們是會存取外部系統並返回 typed result 的 resource operation，並非 pure built-in。MQ、SSH 和 HTTP call 必須是 `type: tool` Action 的主要 `call`。DB `query` 和 `scalar` 亦可用於支援的 expression 欄位；`db.update` 必須是 `type: tool` Action 的主要 call。這些操作不可用於 `retry.when`；該欄位只接受 deterministic pure built-in。以下參數均使用具名形式；省略 optional 參數時會採用 helper 配置的預設值。完整 resource/result 契約見各 helper 頁面。

#### DBHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `db.<id>.query` / `db.<id>.scalar` | `sql: String`；optional `params: List` **或** `parameters: Map<String, value>`（兩者互斥；預設沒有 bind value）。`sql` 可為 inline SQL 或返回 String 的 file-content expression。 | `#{db.orders.query(sql='select status from orders where id = :id', parameters={id: ${EXEC.INPUT.orderId}})}` |
| `db.<id>.update` | 與 `query` 相同的參數和型別；只可作為 Tool Action 的主要 call。 | `#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}` |

`query` 返回 typed rows，`scalar` 返回 scalar result，`update` 返回更新結果。SQL binding、transaction 和 result 詳情見 [DBHelper](reference.zh/resources/dbhelper.md)。

#### MQHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `mq.<id>.send` | `payload: String、byte[] 或 structured Map/List`（必填）；`queue: String` 在未配置 request queue 時必填。Optional `requestFormat: text\|json\|yaml\|xml`（Map/List 必填）、`instance: String`（選擇已配置的 physical instance）。 | `#{mq.payment.send(queue='PAYMENT.REQUEST', payload=${EXEC.VARS.requestText})}` |
| `mq.<id>.receive` | `queue: String` 在未配置 reply queue 時必填。Optional `waitMs: Integer`（預設 10,000 ms 或 helper setting）、`correlationId: String`、`responseFormat: text\|json\|yaml\|xml`（預設 `text` 或 helper setting）、`instance: String`。 | `#{mq.payment.receive(queue='PAYMENT.REPLY', waitMs=5000, responseFormat='json')}` |
| `mq.<id>.request` | `payload` 同上；有效的 `requestQueue` 和 `replyQueue` 必須存在（可由 helper defaults 提供）。Optional `requestFormat` 同上、`waitMs: Integer`（預設 10,000 ms 或 helper setting）、`responseFormat`（預設 `text` 或 helper setting）、`instance: String`。 | `#{mq.payment.request(requestQueue='PAYMENT.REQUEST', replyQueue='PAYMENT.REPLY', payload=${EXEC.VARS.requestText}, waitMs=5000, responseFormat='xml')}` |

`requestFormat` 只用於 structured payload；String 會原樣傳送，不可與它同時使用。`waitMs` 範圍為 0–3,600,000。已配置 queue defaults 和 typed reply 詳情見 [MQHelper](reference.zh/resources/mqhelper.md)。

#### SSHHelper

| Method | 參數（未標 optional 即必填） | 示例 |
|---|---|---|
| `ssh.<id>.execute` | `command: String`；optional `stdoutFormat: text\|json\|yaml\|xml`（預設 `text`）、`timeoutMs: Integer`（預設 60,000 ms，且受 Action deadline 限制）。 | `#{ssh.application.execute(command='systemctl is-active example.service', stdoutFormat='text', timeoutMs=5000)}` |
| `ssh.<id>.upload` | `remotePath: String`、`payload: String 或 byte[]`；optional `overwrite: Boolean`（預設 `true`）、`timeoutMs: Integer`（預設 60,000 ms）。 | `#{ssh.application.upload(remotePath='/srv/app/request.json', payload=${EXEC.VARS.requestText}, overwrite=true)}` |
| `ssh.<id>.stat` / `ssh.<id>.mkdirs` / `ssh.<id>.delete` | `remotePath: String`；optional `timeoutMs: Integer`（預設 60,000 ms）；`delete` 另外接受 `missingOk: Boolean`（預設 `false`）。 | `#{ssh.application.stat(remotePath='/srv/app/result.json')}` |
| `ssh.<id>.move` | `sourcePath: String`、`targetPath: String`；optional `overwrite: Boolean`（預設 `false`）、`timeoutMs: Integer`（預設 60,000 ms）。 | `#{ssh.application.move(sourcePath='/srv/app/out.json', targetPath='/srv/app/archive/out.json', overwrite=false)}` |

SSH operation 只接受具名參數；path 是 remote path，upload 接受內容而非 local file path。Path 限制、返回值及 timeout/retry 行為見 [SSHHelper](reference.zh/resources/sshhelper.md)。

#### HTTPHelper

`http.<id>.get(...)` 和 `http.<id>.post(...)` 由 method 名稱決定 HTTP method；`http.<id>.request(...)` 另需 `method: String`（`GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`HEAD` 或 `OPTIONS`）。三種 method 都接受以下 optional 具名參數：

| 參數 | 型別及預設值 | 說明 |
|---|---|---|
| `path` | `String`，預設 `''` | 相對於已配置的 base URL；不可提供 absolute URL、query string 或 fragment。 |
| `query` | `Map<String, value>`，optional | Iterable value 會成為重複的 query parameter。 |
| `headers` | `Map<String, value>`，optional | 與已配置 headers 合併；value 會轉成文字。 |
| `body` | `String`、`byte[]` 或 structured Map/List，optional | GET 和 HEAD 不接受 body。Map/List 必須提供 `requestFormat`。 |
| `requestFormat` | `text\|json\|yaml\|xml`，optional | 只在 Map/List body 時必填；String/byte[] 原樣傳送。 |
| `contentType` | `String`，optional | 覆蓋 request Content-Type header。 |
| `responseFormat` | `auto\|text\|json\|yaml\|xml`，預設 helper 設定（預設為 `auto`） | `auto` 會按 response Content-Type 決定格式。 |
| `connectTimeoutMs`、`readTimeoutMs`、`connectionRequestTimeoutMs` | `Integer` 1–3,600,000 ms，optional | 覆蓋相應 helper timeout；三者 descriptor default 依次為 5,000 ms、30,000 ms、5,000 ms。 |
| `followRedirects` | `Boolean`，預設 helper 設定（預設為 `false`） | 最多跟隨 runtime 限制數量的 redirect。 |

示例：`#{http.payment.post(path='/v1/payments', query={dryRun: true}, headers={Accept: 'application/json'}, body=${EXEC.INPUT.request}, requestFormat='json', responseFormat='json')}`。Timeout 範圍、body encoding 及 response parsing 見 [HTTPHelper](reference.zh/resources/httphelper.md)。

### Expression scope 與錯誤

Expression language 由本頁定義；可用 roots 與求值時機由欄位的 semantic owner 定義：[Tool command/call](reference.zh/resources/tools.md)、[Load execIdFormat 與 vars](reference.zh/execution-modes/load.md)、[Debug vars](reference.zh/execution-modes/debug.md)、[report filename](reference.zh/configuration.md)。`${path?}` 只允許缺少的 map/list path 回傳 null；語法錯誤與非法 scope 仍會失敗。Expression syntax 或缺少的必需 Context path 會提供結構化 diagnostic；見[Validation](reference.zh/validation-diagnostics.md)。

已移除 presentation-only `dbText`／`misc.dbText`、`prettyPrint`／`misc.prettyPrint`／`format.pretty` 和所有 local `file.*`／legacy file alias。DB result 保持 typed；顯示時改用 Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` 加 `format: sqlplus`，Map/List 則使用 `format: json` 或 `yaml`。Package content 使用 `&{...}`，並將 String 傳入 HTTP body、MQ payload 或 SSH upload payload。SSHHelper upload 只接受 content；native SSH download 已移除。ATT local output 由 framework 管理。移除的 API 會提供 migration diagnostic。

### Retry condition 的生命週期

`retry.when` 在當前 attempt 完成後、retryOn 符合且尚有 attempt 時才評估。`output.*` 綁定當前 result/evidence/diagnostic 及 `output.attempt`。Normal Boolean typing、strict/optional Context path 契約均適用。僅允許 deterministic pure built-in；external、file、sequence、random 及 current-time operation 禁止。詳見 [Actions retry](reference.zh/actions.md)。

## Execution modes

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ/HTTP/SSH 執行語義。

| 模式 | `EXEC.ID` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | 單一 target invocation | `output/debug/<debugId>/` |
| Load | run 內唯一的 iteration execution ID | 重複 target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` 表示外層 ATT run。模式與 scheduler 資料只保存在 evidence-only `DIAG`，`EXEC.MODE`、`EXEC.LOAD` 和 `DIAG` 均不能供 expression 使用。

三種模式都會先解析 environment、建立 canonical Context、驗證 target/dependency closure，再使用相同 component contract。Mode-specific scheduling、selection、reporting 不會建立另一套 Template 或 expression 語義。

| Mode | 典型用途 |
|---|---|
| Run | 一般功能 SIT/UAT execution |
| Debug | 在 authoring 或 diagnosis 階段隔離一個 Template/Flow/Tool |
| Load | 重複／併發 performance execution |

三者共用 execution model；各模式分別定義 input、identity/bootstrap lifecycle、CLI behavior 與 output layout。

### Run 模式

Run 會執行 Workbook 中經選擇的 authored Testcase。每個選中的 Testcase 會產生一次 Case execution，並有自己的狀態和 evidence record。討論正規化 Workbook row 時使用 *Testcase*；討論 runtime 結果時使用 *Case execution*；兩者共用同一個 Case ID。

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT 先載入 effective configuration/environment、驗證 canonical workbook snapshot 和 selected dependency closure、保留唯一 Run ID，然後為每個選中的 Testcase 啟動一次 Case execution。Stages 依序執行。每個 Stage selector 都會解析為 Template；Action 按 YAML 順序執行，並受 `runWhen` / `onFailure` 控制。

Run evidence 直接寫到 `output/<RunID>/`。完成後才發布 `run.yaml`、Case directories/logs、結果 workbook、HTML/CI output，並更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。`run --update-snapshot` 是執行前明確授權更新 snapshot 的唯一流程。

Status aggregation 的嚴重度為 ERROR > INVALID > FAIL > PASS > SKIPPED。Process exit code：`0` 表示沒有失敗狀態、`1` 表示測試/assertion failure、`2` 表示 command/configuration/validation 無效、`3` 表示 runtime/infrastructure error。

selector 和 option 請看 [CLI Reference](reference.zh/cli.md)，Stage 和 Action control 請看 [Reliability and Execution Control](reference.zh/reliability-execution-control.md)，artifact contract 請看 [Results, Reports, and Evidence](reference.zh/results-reports-evidence.md)。

### Standalone Debug

Debug 可在沒有 workbook Testcase 的情況下執行單一 Template、Flow 或 Tool。

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

不帶 target 執行 `./att.sh debug`，會列出 statically valid、可執行的 Tool、Template 和 Flow，附 copyable command。只會顯示實際存在的 regular non-symlink default sidecar。Discovery 會檢查 selected target dependencies，但不建立 Debug output，也不呼叫 Tool。可用 `--format json` 取得 machine-readable 結果。

Debug input 使用現行 `schemaVersion: att-debug/v1.1`。Top-level 支援 `case`、可選 `stage`、`inputs`、`vars`、`arguments`，以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；Template/Flow 的 `vars` 會以 typed bootstrap tree 評估，並在 target 開始前 seed canonical `EXEC.VARS`。Tool Debug 使用 `arguments`，不支援 `vars`。Framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。Schema migration 見 [Migration Notes](reference.zh/appendices/migrations.md)。

#### Standalone Debug bootstrap data

三種 input contract 有意分開：

| Debug 欄位 | Runtime destination | 用途 |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Flow/Template 直接消費的 business input |
| `vars` | initial `EXEC.VARS` | 可重用 Flow/Template 原本由 caller 準備的值 |
| `arguments` / `tools.<localKey>.arguments` | Tool argument contract | standalone Tool Debug 的明確參數 |

只消費 `EXEC.INPUT` 的 Flow 不需要 `vars`。若 Flow 正常由 parent Flow 先發布 `EXEC.VARS.refNo`，可用 scalar 或 typed structure 直接 debug：

Debug `inputs` 與 Run 使用相同 Testdata mapping 語法：以 `--env` 選擇已配置的 environment，再使用完整 `@{id}`／`@{id.path}` reference 或 scalar interpolation。ATT 會先解析再發布到 `EXEC.INPUT`；Reusable Template、Flow 或 Tool definition 內仍不可直接使用 Testdata marker。詳見[Testdata Registry 與 Input Mapping](reference.zh/test-authoring.md)。

```yaml
schemaVersion: att-debug/v1.1
inputs:
  amount: 100
vars:
  refNo: REF001
  txnSeq: 23
  tags: [SIT, PAYMENT]
  order:
    id: ORD001
    amount: 100
```

```sh
./att.sh debug flow common.payment --input common.payment.debug.yaml
```

`vars` 使用共用 expression engine：完整 `${EXEC.INPUT.amount}` 保留原生型別；混合文字會成為字串；`#{...}` 保留 expression result 型別。Map/list 會遞迴處理，map key 維持字面值。Vars 可按任意順序相依；循環、缺少 var、不可用 root 及 side-effecting call 會在 target 開始前失敗。第一次正常 `assign` 可以取代 bootstrap variable，之後仍遵守一般 duplicate-assignment rules。Final values 會使用既有 canonical `EXEC.VARS`/`CASE.VARS` context 及 result artifacts，並套用既有 redaction policy；不會建立第二個 Debug-only namespace。

Bootstrap value 可使用已初始化 execution identity、`EXEC.INPUT`、有提供時的 `EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata 不可用。Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 會被拒絕；安全純 built-in 使用 ATT 一般 parser。可重複使用 `--set input.path=value`、`--set vars.path=value`，以及僅限 Tool 的 `--set arg.name=value`。值以 safe YAML 解析；可用巢狀 map 及數字 list index。例：`--set 'vars.refNo=${EXEC.INPUT.refNo}'` 會在 evaluation 前修改原始定義。

未指定 `--input` 時，Template/Flow 會在旁邊尋找 `debug.yaml`；grouped Tool 會查找 `config/tools/<group>.debug.yaml`，ungrouped Tool 使用 `config/tools/<localKey>.debug.yaml`。沒有 default sidecar 時請使用 `--input`；明確的 `--input` 會取代 auto-discovery。`--env` 在 target validation 之前使用與 Run/Validate/Load 相同的 environment resolver。

Debug 執行 target-scoped validation：只驗證 selected Template/Flow dependency closure 或 Tool contract，不要求無關 workbook。Template/Flow debug 使用與 Run 相同的 Action/Flow scope rule；Tool debug 使用相同 configured Tool invocation contract。

每次 invocation 隔離於：

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug 不建立或更新普通 `latest-run.yaml`。Exit code：`0` PASS、`1` FAIL、`2` CLI/config/input/validation 無效、`3` runtime error。它在 selected reusable-component 邊界上與正常執行等價，但**不是** workbook Case：除非 debug input/artifact 明確提供，否則沒有 workbook selection、Stage history 或 result-workbook lifecycle。

#### Debug 排錯與 MQ payload 路徑

當 debug target 無法解析時，先確認 target kind 及 identifier，再用 `--input <path>` 排除 sidecar discovery 因素。Template/Flow debug 會尋找 `<target directory>/debug.yaml`；grouped Tool debug 會尋找 `config/tools/<group>.debug.yaml`。只會驗證 selected target 的 dependency closure，因此不需要無關 workbook 或 Case 檔案。

Debug、Run、Load 都使用相同的 `&{package-relative-file}` expression，再將 UTF-8 String 傳入 HTTP `body`、MQ `payload` 或 SSH upload `payload`。這些 Resource Helper 不會解析各自的 local path。Validation 會在外部 I/O 前解析 package file；舊 HTTP/MQ `file`、SSH upload `localPath` 及 SSH `download` call 均會被拒絕。

按 output directory 分辨排錯階段：

| 症狀 | 檢查 |
|---|---|
| `Debug input file does not exist` | 在 selected target 旁加入 sidecar，或明確傳入 `--input`。 |
| `Debug input uses a historical schemaVersion` | 使用 active Debug schema；見 [Migration Notes](reference.zh/appendices/migrations.md)；只有 Flow/Template 需要 caller-prepared `EXEC.VARS` 時才加入 `vars`。 |
| `target` 或 dependency validation 失敗 | 確認 target type/id，並查看回報的 dependency field；不需要無關 workbook。 |
| MQ 回報 payload 遺失或不安全 | 核對 package 內的絕對路徑或 Case-output 內的相對路徑，移除 traversal 及 symlink。 |
| action 已執行但輸出不符預期 | 查看 `output/debug/<debugId>/` 下的 `case.log`、`result.yaml` 及 action artifacts，並對照 rendered inputs 與 selected environment。 |

Load 專用的 evidence retention（`metrics`、`failures`、`samples`、`all`）不適用於 standalone Debug invocation。Debug 會在自己的 debug directory 保留 invocation result 與 artifacts；同一 target 若由 Load run 執行，請參考 [Load evidence retention](reference.zh/execution-modes/load.md#選擇要保留的-iteration-evidence)。

##### CLI configuration examples

`run`、`debug` 和 `load` 默認採用交互式 verbose 行為。Lifecycle、Case、Stage、Action、Resource attempt、retry、assertion 和錯誤事件會即時寫出並及時 flush。實時 Case-log 鏡像復用與 `case.log` 相同的脫敏 append 路徑；`case.log`、`case.yaml`/`result.yaml`、report 和 evidence 仍是持久化事實來源。並發 Case-log 區塊會帶有 Case ID 前綴。`--quiet` 抑制詳細實時進度，但保留最終摘要和錯誤。使用 `--format json` 時，機器可讀內容仍寫入 stdout，實時進度寫入 stderr。Load 只定期輸出有界計數/速率並節流錯誤，不會為每個成功 iteration 輸出一大段內容。

以下每個文件都是完整的 `att-debug/v1.1` 文檔，展示 Template、Flow、分組 Tool、未分組 Tool 和臨時覆蓋值的不同寫法。

Template sidecar（`templates/PAYMENT_INVOKE/debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: PAYMENT debug
  amount: 100
  environment: SIT
stage:
  key: invoke
  values:
    channel: WEB
    sourceRef: SRC-001
```

執行：

```sh
./att.sh debug template PAYMENT_INVOKE
```

Template 表達式應優先讀取 `${EXEC.INPUT.amount}`、`${EXEC.INPUT.environment}` 和當前 Stage 的 `${EXEC.INPUT.channel}`；當前 Stage 的 `values` 會在該 Stage 期間覆蓋同名 Case-level input，Stage 結束後恢復。對應的 `CASE.*` 路徑仍是兼容 aliases，`CASE.STAGES.*` 只保留為舊的執行／證據視圖。

Flow sidecar（`templates/flows/common/compose/debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseName: Compose debug
  traceId: TRACE-001
stage:
  key: DEBUG
  values:
    mode: SIT
inputs:
  source: payment
  suffix: -debug
```

執行：

```sh
./att.sh debug flow common.compose.v1
```

Flow 可用 `${EXEC.INPUT.source}` 讀取 `inputs`；如果沒有名為 `inputs` 的業務字段，舊定義仍可用只讀兼容視圖 `${CASE.inputs.source}`，但不會把整棵 `inputs` 子樹重復寫入 `EXEC.INPUT`。

分組 Tool sidecar（`fpp.invokeApi` 對應 `config/tools/fpp.debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
case:
  RefNo: REF001
tools:
  invokeApi:
    arguments:
      requestId: REF001
      requestType: PAYMENT
      requestFile: /tmp/payment-request.xml
      apiLogPath: /tmp/payment-api.log
```

執行：

```sh
./att.sh debug tool fpp.invokeApi
```

`invokeApi` 是 Tool group 內的 local key；參數值必須是 Tool descriptor 接受的 scalar 或 list。Standalone Tool adapter 不接受用 map literal 表示普通 Tool 參數。

未分組 Tool sidecar（`config/tools/invokePaymentApi.debug.yaml`）：

```yaml
schemaVersion: att-debug/v1.1
arguments:
  requestFile: /tmp/payment-request.xml
  environment: SIT
```

執行：

```sh
./att.sh debug tool invokePaymentApi
```

未分組 Tool 使用根 `arguments`；不需要再包一層 `tools.invokePaymentApi.arguments`。

臨時覆蓋自動發現的 sidecar：

```sh
./att.sh debug template PAYMENT_INVOKE \
  --input /tmp/payment-debug.yaml \
  --output-dir /tmp/att-debug --format json
```

明確指定的 `--input` 優先於目標旁邊的 `debug.yaml`。缺少文件、schema 錯誤、未知或缺少 Tool 參數等輸入／配置錯誤會返回 exit code `2`，並在診斷中標出 `Debug input: ...`。

保護字段例子：

```yaml
schemaVersion: att-debug/v1.1
case:
  caseId: pretend-id
  outputDirectory: /tmp/pretend-output
  VARS: {shouldNotReplace: true}
  STAGES: {shouldNotReplace: true}
```

即使輸入包含這些字段，`EXEC.ID`、`EXEC.RUN_ID`、`EXEC.OUTPUT_DIR`、`EXEC.VARS`、`EXEC.ACTIONS` 以及對應的 `CASE.*`、`RUN.*`、`ACTIONS.*`、`TOOL.*` 和 `DB.*` aliases 仍由框架生成。模式及 scheduler 診斷不會暴露給 expressions。`EXEC.STAGES` 不是 canonical Context 節點；Stage 歷史仍由舊的 `CASE.STAGES` 證據視圖保存。診斷時查看 `output/debug/<debugId>/case.log`、`result.yaml` 和 `artifacts/case.yaml`。

### Load 模式

ATT 接受 att-load/v1.6 scenario。Scenario 有一個或多個 workload；每個 workload 使用單一固定 Template、Flow 或 Tool target，或使用 closed-user weighted target mix。Workload 配置 inputs、testdata policy、bootstrap vars 與 pacing。Root defaults 可供多個 workload 共用，workload-local 欄位會覆蓋它們。Scheduler 啟動前會驗證並預先 resolve 所有 target。

使用上一版 workload schema 的 descriptor 仍相容，載入時會 normalize 至現行 schema。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

#### 定義 Load scenario

~~~yaml
schemaVersion: att-load/v1.6
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK, account: "@{generatedAccounts}", accountId: "@{generatedAccounts.id}"}
    testdata:
      generatedAccounts:
        scope: iteration
        selection: {strategy: sequential, exhaustion: recycle}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

#### 將 closed-user work 分配到多個 target

Workload 可用 `mix` 代替 `target`，讓每位 closed user 的下一次 iteration 在預先 resolve 的 targets 之間選擇。每個 entry 包含唯一 `id`、正整數 `weight`，以及 Template、Flow 或 Tool `target`。Selector 由 run seed、workload ID、穩定 VU ID 和該 VU 的 iteration number 決定；切換 target 不會重設 VU 或 think-time random stream。Weight 表示選擇機率，短時間 run 不保證精確符合比例。

~~~yaml
schemaVersion: att-load/v1.6
seed: 73
workloads:
  - id: checkout
    inputs: {region: HK}
    mix:
      - id: browse
        weight: 60
        target: {type: template, id: BROWSE}
        inputs: {operation: browse}
      - id: purchase
        weight: 30
        target: {type: flow, id: PURCHASE}
        inputs: {operation: purchase}
      - id: report
        weight: 10
        target: {type: tool, id: REPORT, arguments: {format: csv}}
    load: {users: 20, duration: 1m}
~~~

Workload `inputs` 和 `vars` 提供預設值；entry 中相同名稱的 top-level key 會取代預設值。Tool arguments 必須放在 entry 的 `target.arguments`，Tool entry 不可宣告 `vars`。Mix 僅支援 closed model。每次 iteration 完成並經正常 think time 後才選下一個 target。`EXEC.LOAD.MIX_ID`、`TARGET_TYPE` 和 `TARGET_ID` 會在 execution ID expressions、testdata 及 bootstrap vars 評估前發布。切換 target 時，testdata selection state 仍按 workload/VU scope 共用。

Run summary 和 HTML report 會列出設定 weight、實際選擇次數及有界 per-entry metrics。Overall 和 workload percentiles 各自使用 aggregate latency collector 計算；ATT 不會平均各 entry percentile。Event 和 retained evidence 會包含所選的 `mixId` 及 target identity。

Target 支援 template、flow 或 tool；Tool target 可有 named arguments，但不能宣告 bootstrap vars。Workload inputs 會成為每個 iteration 的 EXEC.INPUT；Template/Flow 的 workload vars 則在每個開始的 iteration 建立全新的初始 EXEC.VARS tree。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

| Workload 欄位 | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input；不作為 bootstrap variables |
| `vars` | initial `EXEC.VARS` | Template/Flow typed expression tree；每個 execution 獨立評估 |
| `target.arguments` | Tool arguments | 僅供 Tool call；與 `EXEC.INPUT`、`EXEC.VARS` 分開 |

ATT 會在每個 iteration boundary 將 inputs map snapshot 一次，形成深層 immutable tree。複製 request metadata 時會重用這個 snapshot；Load adapter 會將巢狀 value 直接傳入該 iteration 的 `EXEC.INPUT` map，不再複製巢狀內容。開始 iteration 後，caller 修改來源 map 不會影響該 iteration；不同 iteration 也不會共用 input snapshot。

#### 映射 inputs 並選擇 testdata

Environment profile 提供共享的 `testdata` descriptor list。Scenario 可選擇在頂層宣告 package-relative YAML `testdata` imports，形成僅供該次 Load 使用的 overlay。同 ID 的 Load-local descriptor 會完整取代 environment descriptor，不會合併 records 或 selection 設定。任一 layer 內的重複 ID 都會使 validation 失敗。

使用 `inputs` 將 `@{id}`、`@{id.path}` 或 scalar interpolation 映射到 `EXEC.INPUT`。同一 mapping 內每個 ID 只選一次 record；`scope` 決定何時重新選擇：`workload`、`user` 或 `iteration`。Load 預設為 `iteration`。`user` 只適用 closed-VU workload，在 `arrivalRate` 無效。Workload `testdata` map 只存 policy，不負責匯入 descriptor。其 `selection` 若有設定，會整份取代 descriptor selection policy。Policy 支援 `sequential`、`roundRobin`、有 seed 的 `random`，以及 exhaustion `error`（預設）、`recycle` 或 `stop`。耗盡後 `stop` 會平順停止該 workload。單筆 descriptor 不需要 selection policy。

Selection evidence 只記錄 testdata ID、來源 layer、record index、適用時的 generated sequence、scope、strategy 與 random seed，不包含 record 內容。Run/Debug 只載入 mapping 有引用的 ID；Load 會在 scheduler 啟動前驗證被引用的 ID 及 workload policy。

#### 初始化 Template 和 Flow variables

Scheduler identity 及唯一 EXEC.ID/EXEC.OUTPUT_DIR 初始化完成後，ATT 會在 Template 或 Flow 開始前評估 workload 的 vars tree。完整 `${...}` reference 保留原生型別，混合文字會轉成字串，`#{...}` 使用一般 typed expression parser，巢狀 map/list 會遞迴評估。Vars 之間的依賴不受宣告順序影響；缺少變數或循環會在 target 開始前失敗。每個 iteration 都有獨立 map/list，因此併發 user/workload 不會共用可變值。第一次一般 `assign` 可取代 bootstrap variable。

Bootstrap expression 可使用已初始化的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定的 project/source/target/template metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata，以及 Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 不可用。只允許安全的純 built-in。Tool arguments 與 vars 是不同 contract。

#### 選擇 closed-user 或 arrival-rate workload

Closed workload 使用正整數 load.users。每個穩定 virtual user 重複執行固定 target，並在下一次 iteration 前遵守 execution.thinkTime。thinkTime 可設 duration 或 {min, max} range。

Arrival-rate workload 使用 `load.arrivalRate`、正整數 `load.maxConcurrent` 與 `overloadPolicy: drop`。Scheduler 依絕對 due time 排程。超過 maxConcurrent 的 arrival 記為 generator drop；不排隊，也不算 SUT error。Arrival-rate 沒有持續 USER_ID，也不能配置 `thinkTime`。

Closed workload 會在 iteration 同步執行時保留配置的 virtual user 數量。Scheduler 和 worker-pool implementation 請看 [Load scheduler design](system-design/load-scheduler.zh.md)。

#### 設定 pacing 並估算 Resource pool 容量

穩定 arrival rate 下，可使用 Little's law 估算平均同時處理中的 request 數：

```text
平均 concurrency ≈ arrival rate（requests/second）× 平均 response time（seconds）
```

例如 HTTP 每秒 20 個 request、平均 response time 為 1.5 秒，約需 30 條 concurrent connection，才不會先受 client pool 限制。HTTP 預設 `pool.maxConnections: 50`、`pool.maxConnectionsPerRoute: 20`；請按 workload 需要調整兩者，並確保 per-route 值不大於總數。另為 latency 變化及其他 route 留出 headroom，再查看 `resources.http` 的 active/idle/waiting/peak observations。Pool capacity 是 generator-side 上限，不代表應向未確認承載能力的 service 發送該流量。

MQ request/reply 若每秒 10 個 request、平均 reply time 為 3 秒，整個 workload 約需 30 條 leased connection。`pool.maxSize` 應按每個**實體 MQ instance** 的預期 in-flight request 數 sizing，而非按 logical helper。單一 instance（或 request 固定送往一個 instance）約需 30 條再加 headroom。兩個平均分配的 instance 平均各需約 15 條；亦要考慮 selection skew 並確認實際分佈。`resources.mq` 按實體 instance 顯示 pool metrics，請逐一查看 waiting、timeout 及 response latency。`minIdle` 會在該實體 pool 首次被使用並建立時套用，不會在 Load 開始前建立或預熱 pool。若要避免 connection creation 影響 measured steady state，請配合 Load warm-up / first-use warm-up phase 使用 `minIdle`。負載變化時，請依觀察到的平均 latency 重新估算；tail latency 可用於 headroom 規劃，但不是公式中的平均值。

duration 必填。warmup、rampUp、rampDown 預設為零。Warm-up 送出真實 traffic，但不計入 measured threshold aggregates。可選 seed 使 closed-VU think-time randomization 可重複。

#### 識別 iterations 並查看其 artifacts

每個開始的 iteration 在 Load run 內有唯一 `EXEC.ID`，並共用 `EXEC.RUN_ID`。省略 `execution.execIdFormat` 時 ATT 使用預設 run-scoped ID；有設定時，使用一般 `${...}` / `#{...}` engine 求值一次。Bootstrap vars 在 ID 發布後才評估，因此可讀取 `EXEC.ID` 與 `EXEC.OUTPUT_DIR`。Closed workload 可讀取 `EXEC.LOAD.USER_ID`；arrival-rate 沒有此欄位。欄位初始化時機與 function 限制見 [Check execution ID fields before use](#檢查-execution-id-可用欄位)。

產生的 ID 必須非空且是安全的 path segment。重複 ID 會在 target 啟動前失敗；ATT 不會靜默附加 suffix。

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── 寫入 EXEC.OUTPUT_DIR 的 action outputs
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

Metrics-only iteration 雖有 `EXEC.ID`，但除非 operation 寫入 artifact 或 retention materialize evidence，否則不會建立 per-iteration execution directory。Iteration 執行期間 `EXEC.OUTPUT_DIR` 維持 `executions/<EXEC.ID>` 的 logical planned path。保留的 failure 或 sampled success 會將 evidence 複製到 `failures/<EXEC.ID>/` 或 `samples/<EXEC.ID>/`。Report/evidence summary 顯示 `EXEC.ID`；有保留 `case.log` 時提供連結。Optional Resource output formatting 會延遲至 iteration 被保留後；明確要求的 Tool evidence collector 仍會執行。

#### 選擇要保留的 iteration evidence

`evidence.mode` 支援 `metrics`、`failures`、`samples`、`all`；預設為 `failures`。這些 mode 分別將 effective success/failure policy 預設為 `none/none`、`none/full`、`sample/full`、`full/full`。`evidence.success` 與 `evidence.failure` 可各自覆寫預設。`sampleRate` 與 `maxSamples` 限制保留 evidence。Dropped arrival 不建立 iteration evidence。

Case-log capture 依 effective success/failure policy 及剩餘 retention capacity 決定。Failure policy 為 `full` 且仍有 `maxSamples` slot 時，符合條件的 failure（包括 `samples` 中未被抽中的 success）會保留經 redaction 的 rolling log tail，上限為 65,536 個字符。ATT 只會在 failure 取得 retention slot 後 materialize failure log。Tail 被截斷時會加上 marker 標示較早事件已省略，並在末尾保留最新的 action 和 runtime failure details。容量耗盡後不再保留 failure log。Sampled success 和 full-success policy 會保留完整的 deferred log。例如 `mode: metrics, failure: full` 會在容量允許時進行有界 failure capture；`mode: failures, failure: none` 則會停用此功能。Implementation 和 storage 細節見 [Load scheduler design](system-design/load-scheduler.zh.md)。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、exact file-content String 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

#### 查看 Load 結果並設定 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

Summary 會將 generator observation 與 SUT outcome 分開。`metrics.generator` 包含 sampled heap used/committed/maximum、觀察到的 peak live threads、GC count/time，以及 JVM 支援時的 process CPU。Sampling 限制為每 100 ms 至多一次，較短暫的 peak 可能錯過。`schedulerWakeups`、`submitLag*` 與 `workerQueueDepth*` 描述 scheduler pressure；arrival drops 與 SUT error 分開。`resources.http` 會按 HTTP helper 報告 active/idle/waiting 與觀察到的 peak connections，並與 DB、MQ、Render pool/plan diagnostics 並列；`resources.resourceMetricSamples` 顯示 rate-limited resource observations 的數量。`resources.executionIds` 顯示 custom execution-ID reservation 數量。Testdata mapping/selection counts 位於 `resources.generator.testdata`；解讀方式和 implementation limits 見 [Load Generator Telemetry](system-design/load-telemetry.zh.md)。

`latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate` 描述 run-level percentile estimate；精確 latency aggregates 仍保持精確。Time-series output 保留最新 4,096 個一秒 bucket。ATT 不會平均 workload percentile 來計算整體 percentile。

新增的 Load summary telemetry 欄位在 `att-load-summary/v1.1` 下屬 optional；目前 writer 會輸出這些欄位，加入 telemetry 前產生的 summary 仍然有效。

Sampling limits、metrics 解讀方式和 maintainer verification 請看 [Load Generator Telemetry](system-design/load-telemetry.zh.md)。

Root thresholds 只套用於 aggregate run；workload thresholds 只套用於個別 workload，不會從 root 繼承。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

#### 取消 Load run

取消時，ATT 會停止新的 admission，並 interrupt 已 admission 的 iterations。已 admission 工作的 completion events 會先 drain 並計入 outcomes，之後才 finalize workload results 和 metrics。

#### 了解 Load 執行期間的 payload 變化

Load workload 啟動前，ATT 會為每個可達的 Render payload glob 解析一次，並凍結該 run 匹配到的 UTF-8 source content。Run 進行中對 payload 的編輯、替換或新增 glob match 不影響該 run 的 iterations；下一次 Load run 會重新解析 package。一般 Run 與 Debug 每次 execution 使用新 plan，因此下一次 execution 會讀取編輯後的內容。

每個 iteration 都會以自己的 Context 評估 Context reference、built-in call 及 external call。`seq.next()`、clock/random function 及 external call 等 stateful call 會在每次 iteration 執行。Render 在記憶體回傳 String，因此將 `ACTIONS.<id>.output.result` 傳給下游 action 不會建立中間 Render file。只有 operation 明確需要檔案時才使用 `EXEC.OUTPUT_DIR`。

使用 `--profile` 時，`performance.json` 會記錄 `renderPlansCompiled`、`renderPlanCacheHits`、`renderPayloadResolutions`、`renderPayloadResolutionCacheHits`、`renderEvaluations`、`renderArtifactWrites` 與 `renderSourceBytes`。Load 如何準備 plans 並在 iterations 間重用 immutable source data，請看 [Load scheduler design](system-design/load-scheduler.zh.md)。

使用 `--profile` 時，`performance.json` 的 `resources.execution` 會包含 `executionPlansCompiled`、`actionPlansCompiled` 與 `actionEvaluations`。Plan 和 resolver reuse 的 implementation 見 [Load scheduler design](system-design/load-scheduler.zh.md)。

#### 從 CLI 覆蓋 workload 設定

單一 workload 可用 --users、--arrival-rate、--warmup、--ramp-up、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Template/Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 `att-load/v1.6` policy-only descriptor，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence`、`seed` 及 Load-local `testdata` imports。明確 CLI pacing 會覆蓋 policy。`load --debug template|flow|tool <id>` 會將 sidecar 的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 policy 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy descriptor 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load/v1.6
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

可複製範例與欄位說明見 [examples/load/README.md](../examples/load/README.md)；schema migration 見 [Migration Notes](reference.zh/appendices/migrations.md)。

#### 檢查 execution ID 可用欄位

Load 使用 att-load/v1.6。設定 execution.execIdFormat 時，ATT 在每個 iteration initialization 使用一般 ${...} / #{...} engine 求值一次；省略時維持預設 run-scoped ID。Bootstrap vars 會在生成 ID 及 output path 發布後評估。

可用值有 EXEC.RUN_ID、timestamps、EXEC.INPUT、EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE、closed-only EXEC.LOAD.USER_ID，以及已建立的 META.PACKAGE_ROOT/SOURCE/TARGET/TEMPLATE。EXEC.ID 和 EXEC.OUTPUT_DIR 尚未可用，因為生成的 ID 決定 workspace。還沒有 Action 執行，所以 EXEC.ACTIONS 與 Flow/Tool/helper invocation META 缺席。

只允許 deterministic、side-effect-free built-ins。External Tool/DB/MQ/HTTP/SSH calls 及 stateful、random、clock、filesystem functions 會被拒絕。seq.next() 不允許也不需要。請使用穩定 identity：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 沒有 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

ID 必須非空、安全且為單一路徑 segment，並在 Load run 內唯一。重複或不安全值會在 target 開始前失敗；ATT 不會附加隱藏 suffix。


execIdFormat 只允許 deterministic、side-effect-free built-ins；external calls、seq.next()、random、clock 與 filesystem functions 都會被拒絕。Schema migration 見 [Migration Notes](reference.zh/appendices/migrations.md)。

## Resources 與 integrations

Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper 是同級 integration/resource 類型。SSHHelper 為 command-backed Tool 提供路由，也提供 `ssh.<helperId>.execute|upload` 及 remote filesystem operation；最後收斂到 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> SSH routing 或 Resource Helper /
```

Resource ID 是 Template/expression 或 Tool group 所引用的 logical contract。Environment profile 可把相同 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，無需修改 Action YAML。

小寫 `x-` 可停用 ATT 擁有的 resource 配置字段及 keyed entries；但不會移除 HTTP header 名稱或 DB parameters 等 user data。詳情見[Configuration](reference.zh/configuration.md#使用-x-忽略或停用-att-配置項)。

### Operation result 與 Evidence

ATT 將 operation 的邏輯結果與執行 evidence 分開：

~~~text
Operation
├── result       # native typed value
└── evidence     # 有界的 execution/transport metadata
~~~

Action 在 output.result 發布最後的 operation value。Action status、assertion detail、diagnostic、attempts 描述執行，不會取代 business result。Command stdout 使用 stdoutFormat 解析；HTTP/MQ/SSH response 或 stdout 使用各自的 responseFormat；DB operation 回傳 native typed value。File-content expression 回傳原樣 file text String，詳見[Action 與型別化值](reference.zh/actions.md)。

Resource evidence 可包含低成本 metadata。Helper 也可選擇配置人類可讀 snapshot：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output 支援 json、yaml、xml、text、sqlplus。這只用於 presentation，不會修改或取代 output.result。含 secrets 的值會過濾或省略。

Load scenario 可將 evidence.resources.output 設為 inherit（預設）或 none。none 略過可選的 resource-output formatting/materialization；inherit 會等 success sample 或 failure 取得 retention slot 後才格式化。Metrics-only iteration 不序列化 resource output，也不建立 evidence workspace。Transport parsing 與 file-content String representation 不變。

### Tool

Tool 是具名的 external 或 framework-native capability。Descriptor 必須二選一使用 command 或 call。

#### Command-backed Tool

Command Tool 使用 stdoutFormat 將外部 stdout 解析為型別化結果：

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat 支援 text、json、yaml、xml。ATT 只解析一次 stdout，再將 typed value 發布於 output.result。此欄位不控制人類可讀日誌或檔案輸出。Exit code、有界 stdout/stderr preview 和 process artifacts 都屬於 evidence。

#### Call-backed Tool

Call-backed Tool 呼叫 built-in 或支援的原生 DB/MQ/HTTP/SSH operation，其 native typed return value 發布於 output.result。Call-backed descriptor 不宣告 stdoutFormat。

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation 沒有 result.format/path/overwrite 契約。檔案持久化只由明確定義該 API 的 resource 負責；人類可讀表示屬於 Log 或配置的 evidence output。HTTP/MQ/SSH parsing 或 transfer 由各自 transport boundary 管理。

Action result 規則見[Action 與型別化值](reference.zh/actions.md)；typed result/evidence 的區分見[Operation Result and Evidence](reference.zh/resources/operation-result.md)。

#### Tool 定義中的 `command` 表達式

##### Context and legal forms

Tool 的 `command` 也擁有獨立的受限 Context，只能引用該 Tool `arguments` 映射中聲明的鍵。canonical 文檔及新配置應使用 `${input.<argument>}`：

| 形式 | 含義 |
|---|---|
| `${input.requestText}` | canonical Tool 本地輸入引用 |
| `${TOOL.input.requestText}` | legacy 完整別名；會產生 `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${requestText}` | deprecated shorthand；僅在唯一對應已聲明參數時兼容，並產生遷移 warning |

`${TOOL.input.argument}` 與 `${argument}` 只有在名稱恰好對應當前 Tool 一個已聲明參數時才會接受，並產生 `CONTEXT_TOOL_INPUT_SHORTHAND`；`att validate` 會給出精確的 `${input.argument}` 替換。未聲明或有歧義的 shorthand 會報錯。command-backed 與 call-backed Tool 使用相同規則。

例如：

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a file-content payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
        name: Request Body
        description: Request body String
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

每個 YAML command list item 在 interpolation 後仍是一個 atomic argv；值中含空格、引號或類似 shell 的字符也不會再次分詞。ATT 不會啟動本地 shell。

##### 引號、Context value 與 atomic argv

Tool call 內的引號屬於 ATT expression grammar，並不是 shell quote。外層 `'...'` 或 `"..."` delimiter 在調用前會移除；另一種引號是普通字符；與 delimiter 相同的引號可用反斜線 escape。Quoted value 內嵌 `${...}` 會做 interpolation；未加引號的 canonical Context path 則直接傳遞 typed value。

以下 Tool 會把每個輸入保持為一個 argv：

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${input.message}", "${input.sourceFile}"]
    stdoutFormat: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

當 call 同時包含多層引號時，建議使用 YAML block scalar：

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}
```

Child process 收到的三條 message 分別是 `Customer O'Reilly`、`status="READY"`，以及例如 `O'Reilly said "READY" for payment.payment.TC001`。Context value 自身包含任一種引號時，無需 caller 做 shell escaping，仍只佔一個 argv。

如果堅持把 call 寫成單行，還需額外處理獨立的 YAML escaping 層：

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

第一行是為 YAML double-quoted scalar escape 雙引號；第二行是為 YAML single-quoted scalar 把 apostrophe 寫成兩個。之後 expression engine 才會解析所得的 `#{...}`。

普通 process-backed Tool 不會讓 shell 重新解釋已解析輸入。Context value 內的 `$HOME`、`$(date)`、`a*.xml`、`|`、`>` 與引號都按字面傳遞。需要 shell-like behavior 時應使用經過審查的 wrapper；隨包提供的 `fpp.exehelper` 和 `fpp.loghelper` 只提供上文明確說明的 pathname expansion。

##### Illegal forms and token restrictions

未聲明或有歧義的 shorthand 會報錯。Process-backed Tool 不會對已解析的輸入執行 shell expansion 或重新分詞；需要 shell-like behavior 時，應使用經過審查的 wrapper。

#### Tool 定義中的 `call` 表達式

Call-backed Tool 使用相同的聲明參數理念，但保留 typed value，並只允許 pure built-in 與一個主要 DB query/scalar/update。`${input.customerId}` 來自外層 Tool call，不是 Case 全局變量；`CASE`／`ACTIONS` 等 root 在定義中不可見。Inline SQL 與 package-contained SQL file 內容都在此 scope render，測試數據仍應放在 `params` 並使用 JDBC `?`。

#### Inline Tool descriptor fields

Global `tools` and Tool-group `tools` entries use the same Tool contract:

| Object | Allowed properties |
|---|---|
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `type`, `enumValues`（僅 `type: enum` 時必須），`argName`、`argNameMode`、`delimit`、`x-*` |

`type` 宣告 configured Tool 完整解析後的值 contract，可用 `any`、`string`、`integer`、`long`、`decimal`、`boolean`、`enum`、`bytes`、`map` 或 `list`。未填 `type` 時仍按 `any` 處理，維持舊 descriptor 行為。數值和 Boolean scalar 接受原生值或去除前後空白的數字／`true`／`false` 字串；enum 必須符合其中一個 `enumValues`；`bytes` 接受 byte array 或 UTF-8 String。`string`、`map` 和 `list` 要求原生值型別吻合。這些檢查在巢狀 expression 完整解析後執行；靜態安全可求值的內容會在 validation 檢查，其餘在 runtime 檢查。

### DBHelper

DBHelper 是獨立於 Tool 的一級 JDBC resource。每個 descriptor 使用 `schemaVersion: att-dbhelper/v2.6` 和穩定 logical `id`；global `dbhelpers` 只引用 descriptor file。

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: Orders JDBC resource
connection:
  driverClass: oracle.jdbc.OracleDriver
  url: ${ENV:ORDERS_DB_URL}
  username: ${ENV:ORDERS_DB_USERNAME}
  password: ${ENV:ORDERS_DB_PASSWORD}
```

Credential 可從 environment variable 解析，但不能發布到 `META`、report 或 diagnostic。JDBC driver jar 由使用者放入 `lib/`；ATT 不內置 database driver。

在現行 `att-template/v3.6` 契約中，DB operation 在普通 `type: tool` Action 內使用 `#{db.<id>.query(...)}`、`scalar(...)` 或 `update(...)` call。Call 必須有一個 String `sql` argument。SQL 若存放於 package，可使用 `sql=&{sql/find-order.sql}`；`sqlFile` 只供 historical compatibility。Positional `params` 與 named `parameters` 互斥，並使用相同 JDBC binding 規則。

Query 返回 typed row/scalar；update 返回規範的 update result。Operation、SQL/parameter evidence 進入 common Action envelope；secret credential 永遠不是 evidence。Parameter evidence 按 descriptor/Action 的 masking/type policy 處理。

[Reliability](reference.zh/reliability-execution-control.md) 定義 Action timeout precedence、Retry eligibility、attempt limits 與 replay caution；DBHelper 定義 statement timeout 與 transaction lifecycle。Eligible query 範例：

```yaml
actions:
  waitForOrder:
    timeoutMs: 1500
    type: tool
    call: >-
      #{db.orders.query(
        sql='select status from orders where id = :id',
        parameters={id: ${EXEC.INPUT.orderId}}
      )}
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

明確的 update 亦使用 Tool Action，而且不可使用 automatic retry：

```yaml
actions:
  markOrder:
    type: tool
    call: "#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}"
```

歷史 v3.5/v3.4 的 `type: db` Action 及其 `query`/`update` block 只會由 archived schema 與 compatibility loader 支援。

DBHelper 擁有 descriptor 定義的 connection/statement limit、query timeout、transaction behavior。Transaction finalization 綁定 Case/iteration lifecycle；commit/rollback/reconnect 是 resource operation，不是 public Context root。Action-level timeout/retry 只擴展共同 Action lifecycle，不改變 DBHelper identity 或 Context model。

#### DBHelper 配置

| 路徑 | 必填/默認值 | 約束 |
|---|---|---|
| `schemaVersion` | 必填 | `att-dbhelper/v2.6` |
| `id` | 必填 | `[A-Za-z_][A-Za-z0-9_-]*`；全包忽略大小寫後唯一 |
| `name`、`description` | 必填 | 非空顯示文字 |
| `connection.url` | 必填 | 非空 JDBC URL |
| `connection.username/password` | `""` | 字符串；可用完整 `${ENV:NAME}` |
| `connection.driverClass` | `""` | 可選顯式 class；默認 JDBC discovery |
| `connection.properties` | `{}` | 字符串鍵和值；敏感鍵在錯誤中淨化 |
| `connection.readOnly` | `false` | 布爾值；update Action 在 prepare 前拒絕 |
| `connection.isolation` | `driverDefault` | `driverDefault`／`readUncommitted`／`readCommitted`／`repeatableRead`／`serializable` |
| `statement.timeoutSeconds` | `30` | 每個 statement 使用的整數 1–3600 秒 |
| `transaction.scope` | `case` | `case` 或 `statement` |
| `transaction.onEnd` | `rollback` | `commit` 或 `rollback` |
| `result.maxRows` | `1000` | 整數 1–1000000 |
| `result.maxCellBytes` | `1048576` | 整數 1–1073741824 |
| `result.maxBytes` | `10485760` | 整數 1–1073741824，且不小於 maxCellBytes |
| `evidence.sql` | `full` | `full` 或 `hash` |
| `evidence.parameters` | `values` | `masked`、`types` 或 `values`；使用 values 可能暴露敏感業務數據 |
| `evidence.output` | 不啟用 | `format: json\|yaml\|xml\|text\|sqlplus`；`maxChars` 預設 10000（1–1000000） |
| `pool` | 默認值 | `maxSize` 默認 20、`minIdle` 默認 0、`connectionTimeout` 默認 2s；`maxSize` 為 1–10000，`minIdle` 不可大於 `maxSize`，timeout 至少 250ms |

Query result byte limit 逐 row 累加；CLOB UTF-8 bytes 在讀取 chunks 時計算，使限制檢查隨結果大小線性成長。

validate、docs、snapshot 與 dry-run 都不會打開 DB Connection。dbhelper 文件路徑、ID、字段、SQL 文件和 template call 會在執行前校驗。

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

以上 `waitForOrder` query 無需後續 Log Action。DBHelper 設定 `evidence: {sql: full, parameters: masked, output: {format: sqlplus, maxChars: 10000}}` 即會自動保留 SQL*Plus-style row snapshot；`sql` 決定 SQL evidence，`parameters` 決定 parameter representation，`output` 只控制人類可讀顯示。Assertion 和後續 Action 仍直接讀取 typed rows；`db.<id>.query`／`scalar` 亦遵循相同 policy。

### MQHelper

MQHelper 是由多個 physical instances 組成的 IBM MQ logical resource。現行 descriptor 使用 att-mqhelper/v1.2。Connection settings、credentials、queue defaults 與 pool limits 屬於 resource，不會公開至 META。

~~~yaml
schemaVersion: att-mqhelper/v1.2
id: payment
name: Payment MQ
description: Payment request/reply queues
defaults:
  connection:
    queueManager: QM1
    host: mq.example.internal
    port: 1414
    channel: APP.SVRCONN
  message:
    requestQueue: PAYMENT.REQUEST
    replyQueue: PAYMENT.REPLY
  requestReply:
    waitMs: 5000
    responseFormat: xml
    maxResponseBytes: 10485760
  pool:
    maxSize: 20
instances:
  - id: primary
evidence:
  payload: none
  output:
    format: text
    maxChars: 10000
~~~

Tool Action 以 primary operation 呼叫 mq.<id>.send、mq.<id>.receive 或 mq.<id>.request。MQ reply bytes 在收到 CCSID metadata 時優先按該編碼解碼，再依 responseFormat（text/json/yaml/xml）解析。Typed value 發布於 output.result。responseFormat 負責 ingress parsing；Log.format 和 evidence.output.format 只控制 presentation。

#### 傳送 File-content string 或抽象值

File-content expression output 是 String，可直接傳入 payload：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT 使用配置的 MQ charset/CCSID 編碼完全相同的 file text，不會 parse/serialize。String 不應提供 requestFormat。File-content expression 不會設定 MQMD.Format；MQ transport metadata 仍由 resource 管理。

Map/List 是抽象結構化值，需指定 requestFormat（text/json/yaml/xml），例如 payload=${EXEC.INPUT.request}, requestFormat=json。String + requestFormat 會被拒絕。MQ send/request call 不接受 local file path；`file` 是 unknown argument。File-content expression 不建立檔案或 targetFiles。

#### Evidence、response parsing 與 Load

MQ evidence 可包含有界 transport metadata，例如 helper/instance identity、operation、安全 queue names、message IDs、CCSID、byte counts、response format、duration 與 failure classification。Payload capture 由 evidence.payload 控制；人類可讀 snapshot 由 evidence.output 獨立控制。Load 可用 evidence.resources.output: none 關閉 resource snapshots；否則等 iteration 保留後才格式化。Typed result 與 response parsing 不變。

Call-level responseFormat 可覆蓋 receive/request 的 requestReply.responseFormat；send 不解析 reply。Instance selection 與 pool limits 屬於 descriptor。Schema migration 見 [Migration Notes](reference.zh/appendices/migrations.md)。

MQ reply 上限為 10 MiB。IBM MQ adapter 會在讀取前設定 message receive limit，超限時回報 `MQ_RESPONSE_TOO_LARGE`。這是 transport 成功後的 size rejection，與 reply 遺失或 connection failure 分開處理。

共用 typed-result 契約見[Action 與型別化值](reference.zh/actions.md)。

#### Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing; `maxResponseBytes` defaults to 10485760 and accepts 1–1073741824 |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../schemas/att-mqhelper-v1.2.schema.json).

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

##### Request/reply Timeout 與重播策略

`mq.<id>.request(...)` 收到 correlated reply 時為 PASS。PUT 成功後，correlated GET 回傳 MQRC 2033（`MQRC_NO_MSG_AVAILABLE`）時，使用標準 TIMEOUT 與 `MQ_TIMEOUT` diagnostic，即使 outer Action deadline 尚未到期。原生 evidence 保留 `sent: true`、`replyReceived: false`、`completionCode: 2`、`reasonCode: 2033`、reason 名稱及有效 `waitMs`。其他 transport failure 維持既有 MQ ERROR 分類；outer deadline 與 pool borrow timeout 也走標準 TIMEOUT 路徑。

Canonical Action outcome 為 `output.status: TIMEOUT`；suite/report 的 operation failure aggregate 仍為 ERROR，report message 與 Case log 顯示 TIMEOUT 及 MQRC 2033。當前 attempt 的原生 metadata 位於 `output.evidence.mq.invocations[0]`；完成後位於 `EXEC.ACTIONS.<actionId>.output.evidence.mq.invocations[0]`。

未配置 `retry.when` 時，`retryOn: [TIMEOUT]` 可重新 PUT 整個 request。對有副作用的 request，使用 Boolean gate 排除已送出但無 reply 的情況：

~~~yaml
invokePayment:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.INPUT.requestText})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    intervalMs: 1000
    retryOn: [TIMEOUT]
    when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

Optional `?` path 在其他 timeout 沒有 MQ reason code 時回傳 null，因此允許一般 timeout retry；reasonCode 為 2033 時阻止第二次 PUT，最終 Action 仍是 TIMEOUT。ATT 不會推斷 idempotency 或去除重複訊息。需要反覆輪詢 reply 時，可使用 `send` 後執行 correlated `receive`。

獨立 `receive` 明確保留非錯誤的 polling 契約：outer deadline 未到期時，即使 bounded wait 找不到訊息，2033 仍回傳 PASS 與 `received: false`；outer deadline 到期則為 TIMEOUT。此契約取代 先前為避免重播而不將 request/2033 分類為 TIMEOUT 的舊指引。請參閱[共用 retry 語義](reference.zh/actions.md)。

### HTTPHelper

HTTPHelper 是依環境綁定的 HTTP resource。選定的 config profile 將穩定 logical helper ID 綁定至 base URL。Descriptor 使用 att-httphelper/v1.1。

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  maxResponseBytes: 10485760
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

HTTP connection pool 的預設值及限制如下：

| Pool 欄位 | 預設值 | 契約 |
|---|---:|---|
| `maxConnections` | 50 | 總 connection 數，1–10000 |
| `maxConnectionsPerRoute` | 20 | 每 route connection 數，1–10000 且不可大於 `maxConnections` |
| `connectionRequestTimeoutMs` | 5000 ms | 等待 lease connection 的最長時間，1–3600000 ms |
| `keepAliveMs` | 30000 ms | Keep-alive 時間，1–3600000 ms |
| `idleEvictMs` | 60000 ms | Connection 閒置達此時間後符合 eviction 條件；ATT 在 request 前檢查，1–3600000 ms |

請按預期 Load in-flight concurrency sizing。以下 tuning override 提高 per-route capacity 並縮短等待 lease 的時間；例中的值並非預設值：

~~~yaml
pool:
  maxConnections: 50
  maxConnectionsPerRoute: 40
  connectionRequestTimeoutMs: 2000
~~~

`connectionRequestTimeoutMs` 限制等待取得 pooled connection 的時間。若 service latency 穩定但 pool 等候/timeout 增加，可能表示 generator pool 飽和；請查看 Load output 中按 helper 區分的 active/idle/waiting/peak metrics。

以 type: tool Action 的 primary call 呼叫 http.<id>.get/post/request。Response bytes 由此 boundary 解析：使用 call responseFormat、helper default，或 auto 時依 Content-Type 判斷。支援 auto、text、json、yaml、xml。解析後的 native value 發布於 output.result。可選 evidence.output 是有長度上限的人類可讀 snapshot，不會改變該值。

#### Request body 與 file-content string

File-content expression 回傳 exact UTF-8 file content String，可先 Assign，再直接傳入 body：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.VARS.requestText})}"
~~~

HTTP 在 charset encoding boundary 傳送完全相同的 String，不會 parse/serialize。String 不可搭配 requestFormat。

Map/List 是抽象結構化值，需明確指定 requestFormat，例如 body=${EXEC.INPUT.request}, requestFormat=json。requestFormat 支援 text、json、yaml、xml，且只用於 Map/List。String + requestFormat 會被拒絕。HTTP call 不接受 local file path；`file` 是 unknown argument。File-content expression 不建立結果檔，也沒有 targetFiles。

File-content String 不會覆蓋由 resource 管理的 HTTP Content-Type。需要特定 media type 時請配置 contentType/header。Request charset/header 與 response parsing 都由 HTTPHelper 管理，與 Action result/Log formatting 分開。

#### Failure 與 Evidence

Transport/protocol、response-parse failures 屬 operational error。已收到的 4xx/5xx 是 completed response，可對 statusCode 做 assertion。HTTP evidence 可包含 helper ID、method、安全 URL、response status、content type、byte counts、response format 與 duration。Credentials/payload 不會隱式保存。Load 可用 evidence.resources.output: none 略過可選 resource output formatting，或將其延至 iteration evidence 保留時。

HTTP response 上限為 10 MiB。若宣告的 Content-Length 超過上限，會在讀取前拒絕；未知或錯誤長度則使用有界 stream，在超出上限的第一個 byte 停止。Operation 會回報 `HTTP_RESPONSE_TOO_LARGE`，並在 diagnostics 保留已收到的 status code。Load resource diagnostics 會依 helper 回報 HTTP connection pool 的 active、idle、waiting 與 peak 數量。

HTTP helper descriptor 的 `defaults.maxResponseBytes` 可設為 1 到 1073741824，預設為 10485760（10 MiB）。

共用 typed-result 契約見[Action 與型別化值](reference.zh/actions.md)。

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

### SSHHelper：邏輯 SSH 目標

#### SSH Resource helper operation

SSHHelper 也支援在一般 `type: tool` Action 中使用共用 Resource Helper 形式：`ssh.<helperId>.execute`、`ssh.<helperId>.upload`、`ssh.<helperId>.stat`、`ssh.<helperId>.mkdirs`、`ssh.<helperId>.move` 及 `ssh.<helperId>.delete`。Helper ID 是邏輯 ID；native Resource Helper call 使用 `single`、`random` 或 `roundRobin` 選取一個實體 instance。Native Resource Helper call 不支援 `selection.strategy: all`，會在 validation 時拒絕；`all` 只供 command-backed Tool fan-out 使用。這些呼叫會在未建立 SSH connection 前完成驗證，並共用 helper 的並發上限及 identity 遮蔽規則。

```yaml
actions:
  health:
    type: tool
    call: >-
      #{ssh.application.execute(
        command='systemctl is-active example.service',
        stdoutFormat='text',
        timeoutMs=5000
      )}
  uploadRequest:
    type: tool
    call: >-
      #{ssh.application.upload(
        remotePath='/srv/app/request.json',
        payload=${EXEC.VARS.requestText},
        overwrite=true
      )}
```

`execute` 需要 `command`，可接受 `stdoutFormat: text|json|yaml|xml` 及 `timeoutMs`。`text` 原樣回傳 stdout String；structured format 會把 stdout 解析為原生 Map/List/scalar。Timeout、遠端 non-zero exit 或 parse failure 會回傳不同 category 的 operation error，並保留有界 stderr、exit code、byte count 及 transport evidence。Resource SSH 的 non-zero exit 會令 operation 失敗，與下文 legacy command-backed Tool 的 fan-out 契約不同。

`upload` 需要 `remotePath` 及 String 或 byte array `payload`。上傳 package file 時，直接傳入 `&{...}` 回傳的 UTF-8 String；SSHHelper 不接受或解析 local filesystem path。Map/List 會被拒絕，不會隱式序列化。Remote absolute path 可用；upload 的 `overwrite` 預設為 `true`。

SSHHelper 不提供 `download` operation，因下載必須定義 local destination path。若工作確實要從主機取回檔案，請使用明確配置的 command-backed Tool。

所有 operation 只接受 named arguments。Unknown operation/helper/argument、重複 argument、錯誤 format／timeout、缺少 required field、upload source 衝突及不安全 local path 都會在外部執行前驗證失敗。Runtime evidence 包含 logical helper、選定 instance、host/port、operation、transport、時間及 transfer/command 詳情；command input 和 represented payload 不會複製到 evidence。由 environment 提供的 identity path 仍會遮蔽。

Native SSH failure 在 invocation `error.category` 和 `SSH.error.category` 發布穩定分類：

| Category | 意義 |
|---|---|
| `SSH_CONNECTION_ERROR` | 非 timeout 的 connection、host verification 或 channel setup 失敗；backend 無法可靠識別 authentication 時亦使用此類別。 |
| `SSH_AUTH_ERROR` | Java backend 識別到 authentication rejection/cancellation，或無法初始化配置的 identity。 |
| `SSH_TRANSPORT_ERROR` | OpenSSH 回傳 255；可能是 connection/authentication failure，亦可能是 remote command 自己 exit 255。ATT 保留 stderr 及 exit code，不依本地化訊息猜測。 |
| `SSH_TIMEOUT` / `SSH_POOL_TIMEOUT` | Operation/connect deadline 或 concurrency wait 到期。 |
| `SSH_REMOTE_EXIT` | Remote command 完成但 exit 非零（OpenSSH 255 使用上述類別）。 |
| `SSH_RESULT_PARSE_ERROR` | stdout parsing 失敗。 |
| `SSH_UPLOAD_ERROR` | Connection setup 後 upload 失敗，包括 remote permission、missing path 或 protocol error。 |
| `SSH_ARGUMENT` | Argument validation 失敗。 |
| `SSH_INTERRUPTED` | Caller 中斷 operation。 |

Transfer connection/channel failure 保留 `phase: connect|channel`；timeout evidence 亦保留相應 timeout budget。所有 failure 都保留選定 operation 與實際 transport。Transfer error 仍不可自動 timeout replay。

Native Resource Helper call 使用一個 absolute Action deadline，涵蓋 concurrency pool wait、connection 與 channel setup，以及 command 或 SFTP operation。Per-call `timeoutMs` 或 helper `timeouts.commandTimeoutMs` 只設定 operation limit，不能延長 enclosing Action deadline。`timeouts.connectTimeoutMs` 只在剩餘 deadline 內限制 connection establishment，不會額外增加 operation 時間。execute/upload/stat/mkdirs/move/delete 均遵守此契約。Native `execute`、read-only `stat` 與 idempotent `mkdirs` 的 `SSH_TIMEOUT`／`SSH_POOL_TIMEOUT` 可使用 Action `retryOn: [TIMEOUT]`，call-backed Tool 亦相同，並沿用共用 `retry.when` 控制重播。Native `upload`、`move`、`delete` 拒絕 timeout retry，因為 timeout 後的 mutation outcome 可能不確定。Validation 與 runtime 共用同一 operation policy。SFTP Action 會在 deadline 到達時返回，但 concurrency lease 會由 cleanup worker 持有，直到 transfer worker 與 transport 終止。

SSHHelper 讓 command-backed Tool 使用穩定的邏輯應用伺服器 ID，而非在 Tool group 中寫入實體主機。`att-sshhelper/v1.0` YAML descriptor 含 `id`、可選 `name`／`description`、可選 `defaults`（`user`、`port`、`identityFile`）、非空有序 `instances`、可選 `selection.strategy` 和 `fanout.maxConcurrency`（預設 4、範圍 1–256）。每個 instance 需有 `id`／`host`，`user` 必須由 instance 或 defaults 提供。Instance 欄位覆蓋 defaults；port 預設 22，必須在 1–65535。Helper 和 instance ID 符合 `[A-Za-z_][A-Za-z0-9_-]*`，忽略大小寫後不可重複。無效 host/user、未知欄位、重複 ID、缺少 user、不安全路徑和無效 strategy 都會在 SSH 執行前失敗。

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
timeouts: {connectTimeoutMs: 10000, commandTimeoutMs: 60000}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

在 `att-config/v2.11` 的全域或 `environments.<NAME>.sshhelpers` 列出 descriptor 路徑。目前 package 使用 config v2.11 與 Tool Group v2.9。選定環境的清單會整組取代全域清單；省略則繼承。Tool group 所綁定的相同邏輯 ID 必須在每個選定 profile 內存在。SIT 可綁定一臺，UAT 綁定兩臺，Tool／Action 不必修改：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.11
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all} # 只供 command-backed Tool fan-out；native ssh.application.* 會拒絕 all
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # 可選 group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

Action 仍呼叫 `app.status`。先在本機／CI secret environment 把 `APP_SSH_KEY` 設為可讀私鑰的**路徑**，再分別以 `./att.sh validate --config config/config.yaml --env SIT --package` 及 UAT 驗證。完整 `${ENV:NAME}` identityFile reference 在載入時解析；缺失／空值會報錯而不揭露值。Tool group 的 `ssh` 只能是直接目標（`host`、`user`、可選 `port`／`identityFile`）或邏輯目標（`helper`、可選 `selection`），不可混用。Call-backed Tool 可在 primary `type: tool` operation 中呼叫 native SSH Resource Helper。現行 Tool Group schema 為 v2.9。Command-backed Tool 使用 `stdoutFormat`（`text|json|yaml|xml`）設定 stdout parsing；call-backed Tool 保留 native result type。舊 config/group schema 僅供 migration reference。SSH Resource Helper call 只發布 `META.SSHHELPER.id` 與 `.type`；endpoint、user、identity file、credentials 仍保持私有。Resource call 沒有 Action／per-call strategy override；selection 由 helper configuration 決定。

Strategy 優先序：group override，再到 helper 預設。Native Resource Helper call 中，單 instance 不需 strategy（`single`）；多 instance 只支援 `random` 或 `roundRobin`，`all` 會被拒絕，不會靜默選一臺。Command-backed Tool routing 才支援 `all`：`random` 均勻選一臺，`roundRobin` 以 thread-safe 循環計數器選一臺，明確的 `all` 在並發上限內對每臺各執行一次。**Command-backed `all` 會在每臺主機產生副作用**；只用於整組執行均安全的命令。不會隱式 fan-out、跨主機重試或 failover。若作者設定 Action timeout retry，整個 `all` 呼叫會重做，並非只重試某臺。每臺依 Action／Tool／全域 timeout 執行；中斷會取消正在執行的 OpenSSH process 或 Java SSH session。兩種 transport 使用同一組標準化 host/user/port/key。優先 OpenSSH；mwiede/jsch fallback 仍嚴格驗證 host key，限制見 SSH 診斷章。

單主機時解析後的 `output.result` 仍是舊有 scalar／object。Evidence 新增 `sshHelper`、`instance`、`host`、`selectionStrategy`、`selectionSource`（`helper` 或 `toolGroup`）、transport、起訖／持續時間、exit code、輸出及錯誤。只有 command-backed Tool fan-out 使用 `all`；此時 `output.result` 包含 `sshHelper`、有效 `selectionStrategy`、`selectionSource`，以及依 descriptor 順序以 ID 為 key 的 `instances`；每筆有 `instance`、`host`、`port`、`transport`、`startedAt`、`endedAt`、`durationMs`、`status`，在適用時另有 `exitCode`、`stdout`、`stderr`、`rawOutput`、解析後 `output` 或 `error`。命令正常完成時即使 `exitCode` 非零，仍是 `status: PASS`；exit code 是供 Action assertion 判斷的證據，不屬操作失敗。只有執行、輸出解析、取消或 timeout 錯誤才令 operation 失敗，其他主機證據仍會保留。Assertion 可查 `${output.result.instances.app1.exitCode}`、`${output.result.instances.app1.status}` 或 `${output.result.instances.app1.output}`。Evidence 不記錄認證內容或環境提供的私鑰路徑；私鑰應放在 package 外，命令中亦不要放祕密。

單主機及 `all` 執行都會在 argv、transport stderr（包括串流寫入的 Case log 診斷）及 exception evidence 遮蔽環境提供的私鑰路徑。上述不記錄保證適用於 ATT metadata 和 transport 診斷；解析後的業務 stdout 不變，因此命令不可輸出私鑰路徑。

遷移：若一個實體目標已足夠，直接 SSH 可維持原狀。否則把 host/user/port/key 搬到 helper descriptor，在每個環境綁定，將 group 升到 v2.9，以 `ssh: {helper: application}` 取代實體 `ssh`，逐一驗證環境。Action 不需重寫。Inventory discovery、Action 層指定主機、分散式交易、跨主機 failover 與 orchestration 均不在此 schema 範圍。

`stat(remotePath='/srv/app/result.xml')` 透過 SFTP lstat 回傳 typed `{path, exists}`，存在時包含 `type: file|directory|other`、file `size` 與可用的 ISO `modifiedAt`；missing path 是正常 `exists: false`，permission/auth/transport error 仍失敗。`mkdirs(remotePath='/srv/app/archive')` 建立 parents，已有 directory 時冪等，遇到 non-directory 時失敗。`move(sourcePath='/srv/app/out.xml', targetPath='/srv/app/archive/out.xml', overwrite=false)` 在同一選定 host 上 rename，source 必須存在，已有 target 需明確允許 overwrite；不下載／上傳。明確 overwrite 會先移除已有且型別相同的 regular file 或 empty directory，再執行 SFTP rename，因此不依賴 server 的 rename-overwrite extension。Non-empty directory、special file 或型別不符會拒絕。Replacement 不是 atomic：移除失敗時保留兩個 path；之後 rename 失敗則 source 仍在，target 可能不存在。`delete(remotePath='/srv/app/tmp.xml', missingOk=false)` 支援 regular file 和 empty directory，missingOk 必須明確開啟；non-empty directory、recursive 參數、wildcard／backslash path 都拒絕。所有 operation 只接受具名參數及可選 `timeoutMs`，沿用 selection／concurrency／deadline／host verification／redacted identity／Run-Debug-Load execution。Filesystem operation error 使用 `SSH_STAT_ERROR`／`SSH_MKDIRS_ERROR`／`SSH_MOVE_ERROR`／`SSH_DELETE_ERROR`。沒有 remote copy API；需要 copy 時明確使用 `execute(command='cp /srv/app/a /srv/app/b')`。File-content expression 使用 `&{...}`，local output 由 ATT 管理。

## 可靠性與執行控制

本頁集中定義 cross-cutting public execution behavior。

### Assertion 與 status

Assertion 在文件規定的 assertion point、primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception/infrastructure problem 是 `ERROR`；authoring/configuration 無效是 `INVALID`；條件未選中是 `SKIPPED`；成功工作是 `PASS`。因此 operation failure 與 assertion failure 是不同概念。

### `runWhen` 與 `onFailure`

`runWhen` 決定 statically known Action/Stage 是否 eligible；`onFailure: stop|continue` 決定 failure 後是否繼續。`continue` 不會把 failed status 改成 PASS。Cleanup/diagnostic 應使用規範的 conditional execution semantics，而不是隱藏 failure。

### Timeout

Timeout 依 backend 支援能力終止或放棄 operation，並記錄 diagnostic/evidence。Timeout 是 operational failure，不是 assertion false。Tool timeout 與 resource-specific DB/MQ/HTTP/SSH limit 分別由其 resource contract 定義。

### Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可以擁有多個 attempt。Retry policy 決定哪些 operation failure 可重試。最後／勝出的 operation 成為 top-level `output.result` / `output.evidence`；每次 attempt 保留在 `output.attempts[n]`。後續成功不會抹掉較早 attempt evidence。

### Evidence collectors

Tool evidence collector 在 primary operation 發布 typed `output.result` 後、該 attempt assertion 前執行。Action active 時可使用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 是 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 和 `.status`。Collector 有獨立 `timeoutMs` 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代或修改 primary operation result。

每個 primary attempt 都會執行 collector。Top-level collector node 代表最後／勝出的 attempt，`output.attempts[n].evidence.collectors.<id>` 則保留各 attempt。`continue` 讓 primary/assertion outcome 在診斷收集失敗時仍可觀察；`stop` 令 collector failure 成為 Action error。若收集的值是 business/test data，而不是 pre-assertion 診斷資料，應使用普通 Tool/Log/Assign Action。

### Transaction/Resource lifecycle

DB transaction finalization 與 DB/MQ/HTTP/SSH resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

### Stage execution controls

| Setting | Values/default | 說明 |
|---|---|---|
| `required` | boolean / `false` | Blank selector 是否為 error |
| `runWhen` | `normal`（default）、`onSuccess`、`onFailure`、`always` | Stage eligibility |
| `onFailure` | `stop`（default）、`continue` | Failure 後是否允許後續 eligible work |

`continue` 不會把 FAIL 或 ERROR 改成 PASS。

| Earlier outcome | Later `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | Run | Run | Skip | Run |
| FAIL/ERROR with `stop` | Skip | Skip | Run | Run |
| FAIL/ERROR with `continue` | Run | Skip | Run | Run |

Rollback/diagnostics 使用 `onFailure`；cleanup 或 final Evidence 使用 `always`。

### Tool Timeout precedence

Tool Action timeout 優先於 Tool descriptor timeout，再優先於 global timeout。Sidecar、Stage 與 Template 不定義 timeout/retry defaults。Call-backed DB Tool 的 DBHelper statement timeout 仍是 backend ceiling。每次 supported primary retry attempt 都會在 assertion 前執行 collectors。

### Direct DB timeout 與 retry eligibility

Direct DB Action 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。明確的 Action timeout 會覆蓋 DBHelper `statement.timeoutSeconds` 預設值；未設定時才使用 helper timeout。JDBC statement timeout 以秒向上取整，ATT 仍保留毫秒級 deadline cancellation；每次 retry attempt 都重新取得完整 Action timeout，`retry.intervalMs` 的等待時間不計入該 attempt timeout。

Direct `query` Action 亦可使用標準 retry block：`maxAttempts` 2–10、`intervalMs` 0–3,600,000，`retryOn` 必須是非空且不重複的 `ASSERTION` / `TIMEOUT` 列表。使用 `ASSERTION` 時必須同時定義 Action `assert`。一般 SQL error 為 terminal，不會自動 retry。啟用 retry 後，每次 query attempt 會保留在 `output.attempts[n]`；top-level `output.result` / `output.evidence` 永遠代表 final 或 winning attempt，並以 `winningAttempt` 或 `finalAttempt` 記錄終止 attempt 編號。

Direct `update` Action 支援 `timeoutMs`，但明確拒絕 `retry`。發生 timeout 或 database/transport failure 後，ATT 通常無法證明 mutation 是否已送達或 commit；自動重放可能造成重複業務變更。因此，需要 application-specific idempotent retry 時應由作者明確建模，而不是啟用通用 DB Action retry。

## Configuration 與 environments

本頁是作者編寫配置時的權威閱讀參考。[`schemas/`](../schemas) 目錄中的文件仍是機器可讀契約。模式校驗會先於跨字段和文件系統校驗執行。

### 按任務查找配置

| 任務 | 從這裡開始 |
|---|---|
| 選擇 SIT、UAT 或其他 Resource profile | [選擇 environment profile](#選擇-environment-profile) |
| 設定輸出、Run ID 或報表默認值 | [設定 package-wide options](#設定-package-wide-options) |
| 查找 Tool 或 Helper contract 的 owner | [Configuration owners](#configuration-owners) |
| 遷移或校驗 schema | [Schema catalog](#schema-catalog) 和 [Migration notes](reference.zh/appendices/migrations.md) |

### 配置層與優先級

| 層級 | 來源 | 所管轄內容 |
|---|---|---|
| 全局 | `config/config.yaml` | 輸出目錄/環境/運行時默認值、Template 根、報告、XML 模式、全局 Tool、組路徑、可選全局 SSH |
| DB helper | `dbhelpers` 引用的獨立 YAML | 一個 JDBC 實例的連接、statement timeout、交易、result limit 與 evidence policy |
| MQ helper | `mqhelpers` 引用的獨立 YAML | v1.2 IBM MQ logical group、instances、transport、response parsing、pool 與 evidence policy |
| SSHHelper | `sshhelpers` 引用的獨立 YAML | 邏輯 SSH ID、實體 instances、defaults、selection 與 fan-out 上限 |
| HTTPHelper | `httphelpers` 引用的獨立 YAML | 邏輯 HTTP ID、base URL、預設值、連線池、認證與 TLS |
| Tool group | 配置的 YAML 路徑 | 組身份、可選 script/SSH、分組 Tool |
| Workbook | `<workbook>.yaml` | Excel 映射、Stage、Workbook 標簽 |
| Template | `template.yaml` | Template 身份和有序 Action |
| CLI | 命令選項 | 選擇、Run ID、輸出覆蓋、展示、CI 格式 |

Timeout/Retry precedence 與 eligibility 見 [Reliability](reference.zh/reliability-execution-control.md)。CLI 的 `--output-dir` 和 `--run-id` 會在一次命令中覆蓋相應默認值。一個層級中合法的字段，若放在別的層級中也會被拒絕。

### 使用 `X-` 忽略或停用 ATT 配置項

在可選的 ATT 配置字段，或 ATT 擁有的 keyed collection 條目名稱前加上完全小寫的 `x-`，該項便會視為不存在。適用於現行配置物件和 keyed collection，例如 `tools`、environment profiles、Tool `arguments` declarations、`actions`、Action `evidence` collectors、report columns 及 Debug Tool overrides。YAML 本身仍須能解析；但 ATT 不會對停用項進行模式校驗、解析引用、探索依賴、求值、建立物件、執行或發布。對 keyed collection，請加在 key 上。此規則不會停用或改名 Tool 呼叫時傳入的 argument values：

```yaml
schemaVersion: att-config/v2.11
x-debug-note: "#{missing.tool()}"      # 忽略的配置字段
tools:
  x-temporary: not-a-tool               # 忽略的 Tool 條目
  smoke:
    name: Smoke check
    description: Check the local setup
    call: "#{upper('ok')}"
    x-retry: 0                           # 忽略的可選 Tool 字段
```

Action 和 evidence collector 也使用相同規則。停用的 Action 不會進入校驗或 runtime；停用的 collector 不會被呼叫，也不會加入 evidence。ATT 擁有的巢狀配置區塊中的 `x-` 字段同樣會被忽略：

```yaml
actions:
  x-preview:
    type: tool
    call: "#{missing.tool()}"
  verify:
    type: tool
    call: "#{upper('ok')}"
    retry: {maxAttempts: 2, intervalMs: 0, retryOn: [TIMEOUT], x-note: ignored}
    evidence:
      x-snapshot: not-a-collector
```

停用項不能代替必填字段：`x-schemaVersion` 不能代替 `schemaVersion`，`x-type` 不能代替 Action 必填的 `type`。大寫 `X-` 沒有特殊含義，會按一般 key 校驗。若仍有啟用中的引用指向已停用 Action，該引用會因 Action 不存在而無法解析。`runWhen: "#{false}"` 則不同：Action 仍然存在，條件求值為 `false` 後會產生一般的 `SKIPPED` 結果。

不要用此規則移除 user data 中的 key。HTTP headers、`EXEC.INPUT`、`EXEC.VARS`、任意 maps、DB `params`/`parameters` 和 Tool invocation argument values 中的 key 都是資料，名稱會原樣保留。例如，除非 `x-correlation-id` 本身是 ATT 擁有的配置 collection key，否則它仍是一般 HTTP header 或 input key。

### 選擇 environment profile

`att-config/v2.11` 是現行 profile 契約。Profile 可整組替換已配置的 DBHelper、MQHelper、SSHHelper、HTTPHelper 與 testdata descriptor lists。各 Helper 與 testdata descriptor 的設定方式及 [Testdata Registry 與 Input Mapping](reference.zh/test-authoring.md) 見對應頁面。

ATT 使用一份 common `att-config/v2.11` 加上 `environments` map 選擇環境；不通過修改 Action 或增加環境專用 Tool ID 來選擇環境。SIT、UAT、PREPROD 及 production-like 環境之間，Action 只保留穩定的 logical ID：

```text
Action -> logical helper ID -> selected config -> physical descriptor -> endpoint
```

推薦目錄：

```text
config/
├── config.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

common config 保留現有 templates、testcase root、run/execution/report 設置、`toolGroups` 和 global `tools` registry。Profile 層可配置 typed DB/MQ/SSH/HTTP descriptor lists；以下以 DB/MQ 示範：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.11
environment: SIT                 # default；--env 会覆盖
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/sample.yaml
  - config/tools/fpp.yaml
  - config/tools/orders-db.yaml
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    testdata: [config/testdata/accounts.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    testdata: [config/testdata/accounts.yaml]
```

可把 `config/environments/sit.yaml` 和 `config/environments/uat.yaml` 作為 common registry 的遷移來源，包括 `invokePaymentApi` 以及 `examples/load/closed-smoke.yaml` 使用的 `sample.getAcDate`。實際 package 不要把共用 registry 縮減成 `tools: {}` 或 `toolGroups: []`。

SIT 與 UAT 的 DBHelper 都保持 `id: orders`，只改變 JDBC URL 等 physical connection details；MQHelper 都保持 `id: payment`，只改變 host、queue manager、port 和 channel。包含完整 descriptor、pool 和安全 evidence policy 的可復制例子見 [`examples/environments/README.md`](../examples/environments/README.md)。

兩種環境使用完全相同的 Action 定義：

```yaml
actions:
  prepareRequest:
    type: assign
    name: requestText
    expression: "&{templates/payment/request.json}"

  queryOrder:
    type: tool
    call: >-
      #{db.orders.query(
        sql='select * from orders where order_id = ?',
        params=[${EXEC.INPUT.orderId}]
      )}

  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        payload=${EXEC.VARS.requestText},
        responseFormat='xml',
        waitMs=5000
      )}
```

根級 `environment` 是 default profile；大小寫不敏感的 `--env` 會覆蓋它。Profile 中的 `dbhelpers`、`mqhelpers`、`sshhelpers` 與 `httphelpers` 各自是整組 shallow replacement，省略才會繼承 common list；不支持 generic recursive merge，其他 profile 字段都會被拒絕。未知 profile 名稱會在 validation 或 external execution 前失敗。四種執行模式使用同一個 selector：

```sh
# SIT
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env SIT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT

# UAT
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env UAT
```

CI 對每個目標環境分別執行 `validate --package` 和 `run --all`：

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
```

這個設計使 Testcase、Template、Flow 和 Action 可以從 SIT promotion 到 UAT，不需要編輯；selected config 在 execution 前定義完整 resource registry，因此 validation 也是 deterministic 的。`orders`、`payment` 等 logical ID 表示能力，不表示 physical endpoint；topology 應屬於配置層。不要僅為選擇 endpoint 而創建 `orders_sit`、`orders_uat` 或在 Action 中加入環境條件。若 testcase/template root、report policy 或 package structure 確實不同，才使用不同 top-level config。

YAML 中可保留非 secret topology：JDBC URL、MQ host/port、queue manager、channel、pool size 和 timeout。DB/MQ username/password 應使用 `${ENV:NAME}`，由本地環境或 CI secret store 提供。DBHelper 對 URL、username、password 及 string-valued connection properties 支持完整 `${ENV:NAME}`；MQHelper 僅對 username/password 支持該解析，host、queue manager、channel 和 numeric port 通常直接寫在 selected descriptor 中。resolved secret 不會進入 profile metadata、diagnostics、reports 或 generated docs。

當同一 package 只在基礎設施綁定上不同，應使用 profiles；當 testcase/template root、report policy 或 package structure 有意不同，才使用不同 top-level config。完整 config migration 見 [Migration Notes](reference.zh/appendices/migrations.md)。

### Schema catalog

[`schemas/catalog.yaml`](../schemas/catalog.yaml) 是 active schema 的 source of truth。Package validation 檢查 registrations；封存 schema 不會成為 active runtime contract。完整矩陣見 [Schema and Version Matrix](reference.zh/appendices/schema-matrix.md)。

### 設定 package-wide options

以下 configuration example 與 field table 和英文版共用相同 contract；欄位名與 literal values 保留英文。

```yaml
schemaVersion: att-config/v2.11
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
execution:
  processOutput: {memoryLimitBytes: 65536, artifactLimitBytes: 104857600}
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns: {}
  html: {caseLogInlineLimitBytes: 32768}
  junit: {caseLogEmbedThresholdBytes: 10240}
xml: {namespaceMode: ignore}
toolGroups: [config/tools/database.yaml]
dbhelpers: [config/dbhelpers/orders.yaml]
mqhelpers: [config/mqhelpers/orders.yaml]
tools: {}
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

#### 設定 runtime 和 package 路徑

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | 現行版本：`att-config/v2.11`；上一版 schema 仍受支援。本例採用現行 schema。 |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty default profile name when `environments` is present; otherwise exposed metadata only |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `execution.processOutput.memoryLimitBytes` | `65536` | Integer 1024–1048576; in-memory head/tail preview per stdout/stderr stream |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Integer from `memoryLimitBytes` through 1073741824; maximum bytes streamed to each process artifact |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |

#### 配置報表輸出

| Path | Required/default | Constraints |
|---|---|---|
| `report.mode` | `append-to-copy` | `append-to-copy` or `none`; `none` skips result-workbook creation |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Supported keys: `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, `runTime`, `execId`; each value is a string column label |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |

#### 配置 Resource 和 environment registries

| Path | Required/default | Constraints |
|---|---|---|
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.6` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.2` YAML paths; normalized duplicates are rejected |
| `sshhelpers` | `[]` | Unique package-contained `att-sshhelper/v1.0` YAML paths |
| `httphelpers` | `[]` | Unique package-contained `att-httphelper/v1.1` YAML paths |
| `environments` | absent | Non-empty map of profile names; profiles may contain configured resource and testdata descriptor lists |
| `environments.<profile>.testdata` | `[]` | Unique package-relative YAML paths available to that selected environment |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |


Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `ssh`, `tools`, `environments`, `x-*` |
| `environments.<profile>` | `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `testdata`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `execution` | `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |

See [Migration Notes](reference.zh/appendices/migrations.md) for removed configuration fields.

### 標識符和路徑約束

Run ID 和完整 Case ID 會直接用作目錄名，ATT 不會對合法標識做 slug 化或哈希處理。

Run ID 必須非空、最多 128 個 Unicode 碼點，不能是 `.` 或 `..`，不得含前導/尾隨空白或尾隨 `.`，且不能包含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|`、NUL、控制字符。Windows 設備名（如 `CON`、`NUL`、`COM1`、`LPT1`）會按大小寫不敏感方式拒絕。

`workbookId`、`groupId`、`rowCaseId` 同樣遵循相同字符規則。`workbookId` 與 `groupId` 不能含點號，因為點號用於分隔三個組件；`rowCaseId` 可含點號。Template 路徑相對 `templates.root`；file-content expression 只可讀取 package root 內一個 canonical、regular、UTF-8 file，並拒絕 absolute path、glob、dynamic locator 及 symlink escape。明確聲明的 resource file input 和 evidence output 路徑必須保持在各自配置根目錄內；ATT 會規範化並檢查包含性。

### Topology 與 secrets

Topology 可隨 descriptor/environment 改變。在 descriptor 支援處使用 `${ENV:NAME}` 注入 secret；不可提交，也不可將解析後的值公開在 META、report 或 diagnostic。缺少 required variable 時會指出 field/name，但不列印 secret。

### Cross-mode consistency

Run、Validate、Debug、Load 透過同一 effective configuration 解析所選 environment。`--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 分開的 Configuration files

若 package roots、report policy、Tool topology 或其他 config 刻意不同，可繼續使用 `--config config/environments/sit.yaml` 與 `uat.yaml`。若 package contract 相同而只改 resource binding，使用 profiles。


### `config.report.fileNamePattern`

#### Context and legal forms

該配置使用統一表達式引擎，但擁有獨立的非 Case 作用域。它只支持一個大小寫敏感的值引用：

| 佔位符 | 值 |
|---|---|
| `${suiteName}` | 源 Workbook basename，去掉結尾的小寫 `.xlsx` 後綴；例如 `testcase/payment_regression.xlsx` 變為 `payment_regression` |

配置字符串必須顯式引用 `${suiteName}`，無論它用於文本插值還是內建函數參數。ATT 沒有定義其他通用 non-runtime/configuration expression roots。call 內的裸 `suiteName` 會被拒絕。合法示例包括：

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

以及：

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(${suiteName})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${suiteName})})}.xlsx"
```

#### Illegal or unsupported forms

但不支持如 `${RUN_ID}`、`${WORKBOOK_ID}`、`${ENVIRONMENT}`、`${EXEC.INPUT.caseId}` 等運行時值引用。


### Configuration owners

| Contract | Semantic owner |
|---|---|
| Workbook / Sidecar / Snapshot | [Test Authoring](reference.zh/test-authoring.md) |
| Template / Flow / Action | [Test Authoring](reference.zh/test-authoring.md) / [Actions](reference.zh/actions.md) |
| Tool command、call、arguments | [Tool](reference.zh/resources/tools.md) |
| DB descriptor | [DBHelper](reference.zh/resources/dbhelper.md) |
| MQ descriptor | [MQHelper](reference.zh/resources/mqhelper.md) |
| HTTP descriptor | [HTTPHelper](reference.zh/resources/httphelper.md) |
| SSH descriptor | [SSHHelper](reference.zh/resources/sshhelper.md) |
| Timeout / Retry | [Reliability](reference.zh/reliability-execution-control.md) |

## CLI 參考

### 選擇命令

| 命令 | 目的 | 是否調用外部 Tool |
|---|---|---:|
| `help` | 顯示語法和選項；無命令時默認 | 否 |
| `version` | 輸出 ATT 版本 | 否 |
| `validate` | 校驗包或選中依賴閉包 | 否 |
| `snapshot` | 生成同名規範 testcase XML | 否 |
| `run` | 校驗並執行已選 Case | 是，dry-run 除外 |
| `debug` | 使用 debug sidecar 執行一個 Template、Flow 或 Tool | 是 |
| `load` | 執行已聲明的 scenario，或將 Debug sidecar promotion 為 Quick Load | 是 |
| `docs` | 生成可搜索的包文檔 | 否 |
| `report` | 為已完成 run 重新生成報表 | 否 |
| `build` | 歸檔最新已完成 run | 否 |
| `clean` | 刪除文檔化的 ATT 生成輸出 | 否 |

### 按任務查閱語法、option 和範例

#### 查看 help 和版本

| 語法 | 說明 |
|---|---|
| `./att.sh` 或 `./att.sh help` | 顯示幫助 |
| `./att.sh version` | 輸出版本 |

#### 產生 Workbook snapshot

| 語法 | 說明 |
|---|---|
| `./att.sh snapshot` | 未指定 selector 時遞歸生成 `testcase.root` 下所有 Snapshot；等同於 `--all` |
| `./att.sh snapshot --suite <xlsx>` | 生成一個同名 XML Snapshot |
| `./att.sh snapshot --all` | 遞歸生成 `testcase.root` 下所有 Snapshot |
| `./att.sh snapshot --suite-dir <dir>` | 在某目錄下遞歸生成 Snapshot |

#### 驗證 package

| 語法 | 說明 |
|---|---|
| `./att.sh validate --package` | 校驗整個包；默認範圍 |
| `./att.sh validate --selected <selection>` | 校驗選中依賴閉包 |
| `./att.sh validate --package --format json` | 向 stdout 輸出單個校驗 JSON 文檔 |

在 Windows 上，`att.bat snapshot`、`att.bat validate` 和 `att.bat docs` 不會調用已配置的 Testcase Tool。Validation 會檢查 `.sh` path 和文件是否存在，但不檢查 POSIX launch/executable compatibility；warning 會列出受影響的 Tool。Validation PASS 只確認這些腳本的配置，不能證明它們可在 Windows 執行。執行 `run` 前，請提供並測試 Windows-native equivalent。啟動器和 Tool 相容性見 [Windows launcher and Tool compatibility](reference.zh/appendices/limits-defaults.md)。

#### 執行 Testcase

| 語法 | 說明 |
|---|---|
| `./att.sh run --all` | 運行所有發現的 Case |
| `./att.sh run --suite <xlsx>` | 運行一個 Workbook；可重復 |
| `./att.sh run --suite-dir <dir>` | 在目錄下發現 Workbook |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | 包含一個完整 Case ID |
| `./att.sh run <selection> --tag <tag>` | 包含一個標簽 |
| `./att.sh run <selection> --exclude-tag <tag>` | 排除一個標簽 |
| `./att.sh run <selection> --dry-run` | 僅校驗/規劃，不執行 Tool |
| `./att.sh run <selection> --update-snapshot` | 在校驗前顯式刷新已更改的完整 WorkbookSnapshot |
| `./att.sh run <selection> --fail-fast` | 在首次 FAIL/ERROR 後停止調度 |
| `./att.sh run <selection> --rerun-failed` | 重新選擇先前 FAIL/ERROR 的 Case |
| `./att.sh run <selection> --run-id <id>` | 設置最終 run 目錄名 |
| `./att.sh run <selection> --output-dir <dir>` | 覆蓋輸出根目錄 |
| `./att.sh run <selection> --ci-output junit,json` | 寫出 CI XML/JSON 與 JUnit HTML |
| `./att.sh run <selection> --format json` | 輸出機器可讀摘要 |
| `./att.sh run <selection> --quiet` | 抑制詳細實時進度；保留最終摘要和錯誤 |
| `./att.sh run <selection> --verbose` | 為兼容性保留；詳細實時進度已是默認行為 |

#### Debug component

| 語法 | 說明 |
|---|---|
| `./att.sh debug` | 發現可運行的 Tool、Template 和 Flow；只顯示實際存在的默認 sidecar |
| `./att.sh debug template <id>` | 執行一個 Template；自動發現 `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | 執行一個規範 Flow；自動發現 `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | 執行一個 Tool；自動發現 `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | 覆蓋目標自動發現的 debug 輸入 |
| `./att.sh debug <type> <id> --set input.path=<yaml-value>` | 覆蓋 typed `EXEC.INPUT` 值；可重復使用 |
| `./att.sh debug tool <id> --set arg.name=<yaml-value>` | 覆蓋一個 Tool argument；可重復使用 |
| `./att.sh debug <type> <id> --set vars.path=<yaml-value>` | 在 expression evaluation 前覆蓋 Template/Flow bootstrap `EXEC.VARS` |
| `./att.sh debug <type> <id> --output-dir <dir>` | 將 debug 輸出隔離到 `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | 輸出緊湊機器可讀摘要；完整證據仍在 `result.yaml` |
| `./att.sh debug <type> <id> --quiet` | 抑制詳細實時進度；保留最終摘要和錯誤 |

#### 執行 Load scenario

| 語法 | 說明 |
|---|---|
| `./att.sh load` | 發現 `load/` 下有效的 `att-load/*` scenario；報告無效的已聲明 scenario |
| `./att.sh load <scenario.yaml> --quiet` | 抑制定期實時進度；保留最終摘要和錯誤 |
| `./att.sh load <scenario.yaml> --verbose` | 為兼容性保留；有界實時進度已是默認行為 |
| `./att.sh load --debug <type> <id>` | 使用 `load/load.yaml` policy，將 Debug sidecar promotion 為普通單 workload Load run |
| `./att.sh load <scenario.yaml> --set input.path=<yaml-value>` | 覆蓋單 workload `EXEC.INPUT`；多 workload scenario 不支持未限定覆蓋 |
| `./att.sh load <scenario.yaml> --set arg.name=<yaml-value>` | 覆蓋單 workload Tool scenario 的 argument |
| `./att.sh load <scenario.yaml> --set vars.path=<yaml-value>` | 覆蓋單 workload Template/Flow bootstrap vars |

#### 管理報表和 package 輸出

| 語法 | 說明 |
|---|---|
| `./att.sh report --run-id <id>` | 重建 `report/index.html` 和 `report/junit.html` |
| `./att.sh docs` | 生成 `build/docs/index.html` |
| `./att.sh build` | 在 `build/` 中歸檔最新完成 run |
| `./att.sh clean` | 刪除文檔化生成輸出 |


### Typed overrides and quick Load

`--set` 可重複使用，並且只接受一個 namespace：`input`、`arg` 或 `vars`。值使用安全 YAML 解析，例如 `42`、`true`、`null`、`[a, b]` 或 `{id: 7}`；nested path 可使用 map key 和數字 list index，例如 `input.customer.ids[0]=42`。重複賦值按順序套用，最後一個值生效。解析 override 時不會評估 ATT expression；若 shell 可能展開類似 expression 的值，請加上引號。`arg.*` 僅適用於 Tool，`vars.*` 僅適用於 Template/Flow。多 workload Load scenario 不接受未限定的 override。

`load/load.yaml` 是可選的 policy-only `att-load/v1.6` 檔案，可包含 `load`、`execution`、`thresholds`、`evidence` 和 `seed`，但不包含 target 或 business inputs。`load --debug` 會將 sidecar `inputs` 提升為 `EXEC.INPUT`、Template/Flow `vars` 提升為 bootstrap `EXEC.VARS`，或將 Tool `arguments` 傳入 Tool call，然後使用一般 Load validator、scheduler 和 evidence pipeline 執行。明確的 CLI pacing fields 會覆蓋 policy；沒有 policy 時，請直接在 CLI 指定完整 policy。

### Debug input 與 output

CLI 的 target、`--input`、`--set` 與 `--env` 語法見本頁 option matrix。Input discovery、bootstrap vars、保護 roots 與 output lifecycle 見 [Debug](reference.zh/execution-modes/debug.md)。

### 退出碼

| 代碼 | 含義 |
|---:|---|
| 0 | 命令/運行成功，且無 FAIL、ERROR、INVALID |
| 1 | 至少一個 FAIL，且無 ERROR/INVALID |
| 2 | CLI/配置/校驗/INVALID 失敗 |
| 3 | 至少一個 ERROR，或不可恢復運行時失敗 |

### 完整 CLI option matrix

`--config <file>` 選擇 base configuration；`--env <name>` 從 `att-config/v2.11` 選擇 environment profile，適用於 `run`、`validate`、`debug` 和 `load`。`--help` 顯示說明。`--case-id` 是 `--case` 的相容別名。`--parallel` 是已棄用的 `--allow-parallel-runs` 相容拼法，應優先使用後者。`--queue` 與 `--allow-parallel-runs` 控制共用 output root 的 process-level concurrency，不會在單一 run 內增加 Case worker。`--profile` 為 `run` 或 `load` 寫入 performance diagnostics。

Load 以 scenario 為基礎；明確提供的 workload option 會先覆蓋對應欄位，再重新驗證 effective scenario：

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

無 target 的 `debug` 和 `load` 是唯讀 discovery。Debug 會驗證可執行 target，但不呼叫 Tool，也不建立 output。Load 只掃描宣告 `att-load/*` 的 YAML、驗證 target，並回報無效的已宣告 descriptor；其他 YAML 會忽略。Discovery 模式支援 `--config`、`--env`、`--format`、`--quiet` 和 `--verbose`。

`--set` 可重複使用，namespace 只能是 `input`、`arg` 或 `vars`。值使用 safe YAML 解析並保留型別，例如 `42`、`true`、`null`、`[a, b]` 或 `{id: 7}`；nested path 可用 map key 及數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效。解析時不會執行 ATT expression；shell 可能展開的值要加引號。`arg.*` 僅適用 Tool，`vars.*` 僅適用 Template/Flow。多 workload Load scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 policy-only `att-load/v1.6`，不能包含 target 或 business inputs。它可設定 `load`，以及可選的 `execution`、`thresholds`、`evidence` 和 `seed`。`load --debug` 會將 sidecar `inputs` promotion 到 `EXEC.INPUT`、Template/Flow `vars` promotion 到 bootstrap `EXEC.VARS`，或將 Tool `arguments` 傳入 Tool call，之後使用正常 Load validator、scheduler 和 evidence pipeline；不會先執行 Debug。明確的 CLI pacing 會覆蓋 policy。沒有 policy 時，請在命令列提供完整 policy：

```yaml
schemaVersion: att-load/v1.6
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
```

```sh
./att.sh debug
./att.sh load
./att.sh load --debug template PAYMENT_INVOKE
./att.sh load --debug tool fpp.invokeApi --users 1 --duration 10s --set arg.requestId=42
./att.sh load --debug flow common.payment --users 4 --duration 5s --set input.customer.ids[0]=42
```

重複的 `--set <input|arg|vars>.<path>=<yaml-value>` 會在 expression evaluation 前以安全 YAML 型別覆寫 definition。`debug`、單 workload Load scenario，以及 `load --debug template|flow|tool <id>` 都支援。Quick Load 會使用可選的 `load/load.yaml`；若沒有 profile，請在 command line 提供完整 policy。例如：

```sh
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
```

完整 workload override 為 `--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`；`--think-time` 只適用 closed-VU。其餘 selection/output 選項仍受各 command 約束：`--suite`、`--suite-dir`、`--case`/`--case-id`、`--tag`、`--exclude-tag`、`--all`、`--run-id`、`--output-dir`、`--format`、`--quiet`、`--verbose`、`--ci-output`、`--dry-run`、`--fail-fast`、`--rerun-failed`、`--update-snapshot`、`--package`、`--selected`、`--input`、`--set`、`--queue`、`--parallel`、`--allow-parallel-runs`、`--profile`、`--config`、`--env` 和 `--help` 只在對應 command contract 允許時有效。

## 結果、報告與 evidence

Run 會執行 authored Testcase，並為每個選中的 row 記錄一次 Case execution。Testcase 是 Workbook data；Case execution 是使用相同完整 Case ID 標識的 runtime result。閱讀 status、日誌、報表和 evidence 時使用 Case execution。

| 任務 | 前往 |
|---|---|
| 查找 Run files 和 execution artifacts | [查找 Run artifacts](#查找-run-artifacts) |
| 查看狀態或檢查失敗的 Action | [在 HTML 報表中查看 Case execution 結果](#在-html-報表中查看-case-execution-結果) |
| 匯出 JUnit 或 CI 結果 | [匯出 JUnit 結果](#匯出-junit-結果)或[查看 CI JSON 彙總](#查看-ci-json-彙總) |
| 重現已完成的 Run | [重現 Run](#重現-run) |

### 查找 Run artifacts

```text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
└── executions/<EXEC.ID>/...
```

Run ID 和 Case ID 在校驗後保持原樣。只有 `run.yaml` 狀態為 `COMPLETE` 才表示運行完成；中斷工作會直接保留在已保留的 Run ID 目錄中供調試。

### 在 HTML 報表中查看 Case execution 結果

`report/index.html` 是主要終端用戶報表，可以直接從磁盤打開。組按 `workbookId.groupId` 彙總；界面把 `groupId` 標記為 Sheet。Cases view 支持 Workbook/Sheet/Status 下拉框、對 workbook/group/full Case ID/tag 的大小寫不敏感搜索，以及每列標題的升序/降序排序。Duration 按數值排序。

展開的 Case 包含完整 Case ID、名稱、狀態、持續時間、Expected 和 Actual 結果、每條記錄 Action 結果的一行、詳細執行日誌，以及 `.log`/`case.yaml` 的顯式鏈接。Action Results 每行獨立顯示最終渲染的 Description，並寫入 `run.yaml` 與 CI JSON。為兼容既有報表，Expected 仍是所有 assert Action 非空最終 description 與 `expected` 的有序 LF 聯接；Actual 是所有非空運行時 `actual` 的有序 LF 聯接。

### 診斷 Tool evidence collector 失敗

Evidence collector 是 operation 完成後的 observability，不是 primary Tool result。使用 `onFailure: continue` 時，primary Action 可以維持 `PASS`，而 collector 會獨立記錄為 `ERROR`：

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

請查看 `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`（或等價的 `ACTIONS` compatibility view）。Record 包含 `status`、`success`、`invocationId`、`result`、`error`，以及 bounded/redacted 的 underlying operation `evidence`；operation 有提供 structured diagnostics 時會在 `operationDiagnostic` 保留 native operation diagnostic 的安全 field。`diagnostic` 則記錄 collector failure 及其 source file/field。`error.message` 會從 underlying exception、operation status/exit code 或安全 fallback 填入。若 executor 有提供，SSH helper/instance、exit code、bounded stderr、MQ reason code、HTTP status 和 timeout detail 等 resource identity/field 會留在 `evidence`。Failed collector evidence 會被 bounded/redacted；raw input、payload、argv、output、resolved command text 與 failed `result` 不會發布。完整 projection、numeric budgets 與 security guarantees 見 [Limits, Security Guarantees, and Advanced Diagnostics](reference.zh/appendices/limits-defaults.md)。

有 retry 時，請查看 `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`。即使後一個 attempt 成功，較早的 failed collector record 仍會保留；top-level collector record 代表最後／勝出的 attempt。使用 `onFailure: stop` 時，Action 可以失敗，但其 diagnostic 仍會包含 collector root-cause message 和保留的 evidence。同一 structured record 也會寫入 `case.log` 的 `EVIDENCE <action> attempt=<n> collector=<id>` block，因此不必打開 internal exception trace，便可看到基本 resource、category、message、exit code 和 bounded stderr。既有 capture limit 與 secret redaction 仍然有效；collector wrapper 不會開放無上限 raw output。

### 將 execution 結果寫入 Workbook

ATT 會復制源 Workbook，並使用 `report.mode: append-to-copy` 追加配置的結果列。`report.mode: none` 跳過 result Workbook，適合不需要 copy 的 CI 或大型 run。Global `report.fileNamePattern` 控制檔名。Sidecar `report.columns` 只修改 Workbook 標簽。支持的映射包括 `result`、`durationMs`、`expectedResult`、`actualResult`、`caseLog`、`reportLink`、`runTime`；Expected/Actual 單元格保留 LF 字符並以換行文本顯示。結果回填使用與 testcase loader 相同的 Excel 顯示格式和空白規範化規則讀取 Case ID，因此帶前導零等數字格式的 ID 在執行與報表寫入時會匹配同一 Case。

### 匯出 JUnit 結果

每個 Case execution 對應一個 JUnit `<testcase>`：

| ATT 狀態 | JUnit 表示 |
|---|---|
| PASS | 無 failure 子節點 |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

文本會被 XML 轉義。JUnit XML 與 HTML 使用 `report.junit.caseLogEmbedThresholdBytes`。低於或等於閾值的日誌會被嵌入；更大的日誌使用相對鏈接。`0` 始終使用鏈接。

### 查看 CI JSON 彙總

`ci/summary.json` 使用 `schemaVersion: att-ci-summary/v2.1`，包含 ATT/Run ID、環境、時間、聚合狀態/統計、持續時間統計、每個 Case 記錄、診斷計數、報表/產物路徑以及輸入清單哈希。

### 重現 Run

`run.yaml` 使用 `schemaVersion: att-run/v2.1`，記錄 ATT/構建身份、Java/OS/locale/timezone、校驗模式、環境、時間戳、狀態/摘要、輸出路徑，以及有效配置、Tool group 文件、call-backed Tool SQL 文件（`tool-sql`）、Workbook、Sidecar、解析 Template/負載、包內 Tool 文件和 schema/catalog 版本的 SHA-256 hash。

### 生成文檔並管理 package 輸出

| 命令 | 輸出/行為 |
|---|---|
| `docs` | 在 `build/docs/index.html` 生成可搜索離線包文檔；Testcases 按 Workbook 和 Sheet 分組 |
| `report --run-id <id>` | 從完成證據重建兩個 HTML 報告 |
| `build` | 歸檔最新完成 run，不執行測試 |
| `clean` | 刪除配置輸出目錄、`build/docs` 與 `build/att-*.tar.gz` |

### 從 execution identity 追蹤 artifact

| Identity | 意義 | Scope | Artifact 用途 |
|---|---|---|---|
| EXEC.RUN_ID | 外層 ATT run。 | Run。 | Run root、summary、report。 |
| EXEC.ID | 目前 Case/Debug/Load execution。 | Execution。 | Workspace 建立時作為 log/evidence key。 |
| EXEC.OUTPUT_DIR | 與 EXEC.ID 關聯的 workspace。 | Execution。 | Run/Debug 實體 workspace 或 Load planned lazy workspace。 |

一般 Run 的功能性 Case 位於 output/<RUN_ID>/executions/<EXEC.ID>/。Load 執行期間，EXEC.OUTPUT_DIR 與 CASE.outputDirectory 固定指向 output/load/<RUN_ID>/executions/<EXEC.ID>/。Iteration 被保留時，artifact 也會複製到 samples/<EXEC.ID>/ 或 failures/<EXEC.ID>/。Metrics-only iteration 有 EXEC.ID；scheduler 清理暫存 workspace 後不保留 per-iteration 目錄。Load report 顯示 retained rows 的 EXEC.ID，有保留 case.log 時提供連結。Debug 使用同一 debug ID 作為 EXEC.RUN_ID 與 EXEC.ID。

DIAG 是 evidence-only。Expression 不可讀取 DIAG、EXEC.MODE 或任意 scheduler counter；業務差異請透過 EXEC.INPUT 傳入。


### 查閱 generated-output schemas

| 產物 | 頂層必需契約 |
|---|---|
| `run.yaml` | `schemaVersion`、`att`、`runtime`、`run`、`validation`、`inputs`、`cases`、`summary`、`outputs` |
| Validation JSON | `schemaVersion`、`attVersion`、`valid`、`mode`、`summary`、`diagnostics` |
| CI summary JSON | `schemaVersion`、`attVersion`、`runId`、`environment`、`startedAt`、`endedAt`、`status`、`summary`、`durationStatistics`、`cases`、`diagnosticCounts`、`report`、`inputManifestHash` |
| JUnit XML | 一個 testsuite，含 test/failure/error/skipped 計數，以及每個 Case execution 的 testcase |

### Reading `case.log` and `case.yaml`

Case log structured entries use YAML. The human log records each normal Action and each Tool/DB invocation once; duplicated attempt fields and persisted TOOL/DB subtrees are omitted from this projection. Complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` fully expands shared Map/List objects; `true` permits YAML anchor markers, which carry no ATT identifier semantics.

巢狀 structured entry 中的 multiline String 會以易讀的 YAML block content 顯示。ATT 保留 LF、CRLF 或單獨 CR 分隔符，不會解析、修剪或重排 business text。Raw process 與 user content 也保留原始換行。Live console mirror 與已寫入的 `case.log` 使用相同的 render text。

ATT prefixes Case log blocks whose section or nested status is ERROR, FAIL or INVALID with `【!!!!!】`. Search for that marker to find abnormal blocks; PASS, SKIPPED and informational blocks remain unmarked.

## Validation 與 troubleshooting

### 選擇第一個診斷步驟

先執行 `validate --package` 並修正 diagnostic 的 file/field。**Testcase** 是作者編寫並正規化的 Workbook row；**Case execution** 是 Run 對該 row 的一次執行，兩者使用相同 Case ID。Runtime 失敗先看 Case execution 的 report status/message，再看其 `case.log`、`case.yaml` 與 Action evidence。`FAIL` 與 `ERROR` 的區分、continuation 與 Retry 見 [Reliability and Execution Control](reference.zh/reliability-execution-control.md)；collector failure path 見 [Results, Reports, and Evidence](reference.zh/results-reports-evidence.md)。[Limits, Security Guarantees, and Advanced Diagnostics](reference.zh/appendices/limits-defaults.md) 說明 Windows launcher、Java SSH negotiation 與 stack-trace policy。

### 先從 Validation 開始

每次修改 workbook、sidecar、template、helper 或 tool 後執行：

```sh
./att.sh validate --package
```

針對單一環境可執行 `./att.sh validate --config config/config.yaml --env SIT --package`。ATT 僅接受 [Schema and Version Matrix](reference.zh/appendices/schema-matrix.md) 列出的 active schemas。`schemas/history/` 中的舊 schema 僅供歷史參考，不是 runtime compatibility contract。請先更新 `schemaVersion` 並將欄位遷移至現行契約，再執行 validation。診斷會保留原始違規、檔案及 YAML 欄位位置，並提供 migration guidance；ATT 不會改寫 descriptor。例如，將 historical Render Action 改為使用 `&{path}` 的 Assign，再依[Actions and Typed Values](reference.zh/actions.md)傳遞 resulting String。Unsupported version 會在執行前失敗。

現行 schema 位於 [`schemas/`](../schemas)，較舊定義位於 [`schemas/history/`](../schemas/history)。`validate --package` 會檢查 catalog 登錄的每一份 schema，即使 package 沒有使用。缺少、無法讀取、不安全或重複的註冊 schema 會硬性回報 `PACKAGE_INVALID`。Validation 不會改寫 YAML。請檢視 migration guidance、更新檔案，再針對每個選定的 `--env` 重跑 package validation。

Package validation 會依照 Debug execution 相同的 discovery 與 schema/input validation，檢查現有 Template、Flow、分組及未分組 Tool 的 `debug.yaml` sidecar。歷史 schema version 與不符合現行 schema 的結構會在執行 `att debug` 前回報；可選 sidecar 缺少時仍屬有效。

然後根據診斷代碼和結構化位置排查。不要針對人類可讀消息做自動化判斷。

| 類別 | 典型原因 | 修正措施 |
|---|---|---|
| `ATT-TC` | 缺失/過期 Snapshot、Sidecar/Sheet/表頭錯誤、重復 Case ID | 檢查 Snapshot/基名、sheet 映射、有效表頭和完整 ID |
| `ATT-CTX` | 未知或歧義 Context 路徑 | 檢查請求/當前/缺失字段、最近建議或規範候選 |
| `ATT-STG` | 必需選擇器為空白、選擇器 YAML 無效、Stage 鍵重復 | 檢查選擇器形式、`name`、別名和 required 標志 |
| `ATT-TPL` | 未知/重復 Template、Action 或負載無效 | 檢查符號名/完整路徑、描述符、Action 類型和本地文件 |
| `ATT-CFG` | 未知字段、重復鍵、schema 類型/枚舉錯誤 | 對照 [Configuration and Environments](reference.zh/configuration.md) 並移除不支持字段 |
| `ATT-TOOL` | 未知/缺失參數、進程或解析失敗 | 對比調用契約，檢查退出碼和有界 stdout/stderr capture evidence |
| `ATT-PATH` | 非法 ID 或路徑逃逸 | 移除非法字符，並保持內容在配置根目錄下 |
| `ATT-RUN` | 超時、非零退出、渲染/運行時失敗 | 檢查 Case 日誌和 Action/Tool 證據 |

### 常見問題

#### 為什麼 Testcase ID 被拒絕，Excel 卻顯示正確？

ATT 導入的是顯示單元格文本，然後應用嚴格的 ID 安全檢查。檢查隱藏的首尾空白、尾隨 `.`、路徑字符、控制字符以及 Windows 設備名。以文本形式保存標識符，以保留前導零。

#### 兩張 Sheet 可以使用相同的 row ID 嗎？

可以。給兩張 sheet 不同的 group ID，讓兩行形成不同 Testcase，例如 `payment.payment.TC001` 和 `payment.batch.TC001`；Run 會為它們各自建立 Case execution。

#### 為什麼 `N/A` 變成空了？

ATT 會在數據映射和 Stage 選擇前，把 `N/A`、`NA`、`NULL`、`NONE`、空和僅空白值歸一化為 blank。

#### 為什麼 Context 變量失敗？

ATT 會把缺失路徑視作作者/運行時錯誤，而不是靜默渲染成空字符串。遵循 `ATT-CTX-001` 的 `requestedPath`、`currentNode`、`missingSegment` 和最近建議，檢查大小寫敏感的作用域、物理表頭/別名、Stage key、Action ID，以及可用性時間點。後綴簡寫必須唯一識別一個可讀邏輯路徑；當 validation 能識別 canonical current-scope replacement 時，會以 `CONTEXT_LEGACY_PATH` 發出遷移 warning。`ATT-CTX-002` 會列出所有衝突候選，以便你加長後綴或使用規範路徑。聲明的可選字段即使值為空白，仍然是有效空字符串。

#### 為什麼 Fail 變成 error？

Assertion 為 false 會令 Case execution 變成 FAIL。無效表達式語法/導航、Tool 失敗、超時、解析失敗、I/O 失敗或運行時異常會令它變成 ERROR。應查看 Action 證據，而不只看最終 Run 狀態。

#### 為什麼 Tool 跑了不止一次？

它的 Action 啟用了重試，並收到了符合條件的非零退出碼。查看 Case 日誌中的嘗試列表和最終 Action 記錄。

#### 我能在 `Command` 中使用 shell 管道嗎？

不能。ATT 會把 `|`、`>`、`<` 按字面值傳遞。把 shell 行為放到審查過的 Tool 腳本中。

#### 為什麼必需的 Array 參數會拒絕 `[]`？

必需項驗證發生在 argv 擴展之前。空 typed List 被視為缺失；請至少傳入一個標量 item，或將參數設為 optional。

#### 我應該使用包校驗還是選中校驗？

本地快速反饋請用 selected 模式。發布前、CI 推進、或共享包時請用 package 模式。

#### 報告能否不依賴服務器打開？

可以。保持生成的 run 目錄完整即可，相關相對鏈接仍可工作。

#### Build 會不會再次執行測試？

不會。它只是歸檔一個已完成的持久化 run。

### 安全提醒

不要把密碼、token、私鑰或敏感客戶數據放進 Workbook 單元格、Template 描述符、命令字符串、stdout 或 stderr。優先使用 Tool 腳本中經批准的祕密注入方式。在共享報表和歸檔前進行審查。

### Validation JSON 合約

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.7.4",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

每個診斷都包含 `code`、`severity`、`message`、`file`、`field`、`sheet`、`row`、`column`、`template`、`action` 和 `suggestion`。不適用的字段為 `null`。當 package 和 case 驗證發現同一個根本錯誤時，ATT 輸出一條診斷，並在適用時附帶 `occurrences` 和 `affectedCases`；`summary.errors` 統計唯一診斷，`summary.errorOccurrences` 保留原始出現次數。代碼穩定；自動化不能解析人類消息。

ATT 可另外提供 `summary`、`detail`、`source`、`context` 和 `schemaViolations`。`source` 中的 `line`、`column`、`endLine`、`endColumn` 是 YAML 或 payload 文件的物理位置；頂層 `row` 和 `column` 仍表示 Excel 單元格。單行純文本及可直接對應的引號字符串，表達式語法錯誤會指向具體字符；摺疊、多行或經過轉義的 YAML 字符串若無法精確映射，則報告整個 scalar 範圍。每項 Schema 錯誤保留自己的路徑、關鍵字、消息及物理位置。`context` 可包含 Case、Stage、Flow ID 和嵌套調用鏈。表達式語法詳情在安全時會指出所在 Tool 調用參數（例如 `logFiles`）、意外 token 及帶 caret 的有限鄰近片段；可能含有憑據或敏感值的字段及整行不會顯示原文摘要。

運行時 Action 錯誤的結構化診斷會傳入 Case YAML、`run.yaml`、重新生成的報表、CI JSON 和 JUnit 錯誤詳情。嵌套 Flow 錯誤會指出內部 `flow.yaml` 及 Action，調用鏈說明 Template 如何到達該位置。Tool 與 DB evidence 在適用時記錄嘗試次數、超時、解析／採集狀態、參數綁定及取消操作；文件保存錯誤包含配置路徑和允許的產物根目錄。

## CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 與 release/source archive，並驗證 packaged launcher。Reference generation 另外需要 Python 3 與 Pandoc。

### Runtime dependencies

Java 8+ 是 runtime baseline。ATT 不內置 JDBC driver；需要的 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

### Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

### CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定 DB/MQ logical ID 讓相同 Template 在 SIT/UAT/PREPROD 間移動而不用改 Action。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。

## Appendix A — schema 與 version matrix

現行 active schemas（source of truth：`schemas/catalog.yaml`）：

| Artifact | 現行 schema |
|---|---|
| Global configuration | att-config/v2.11 |
| Testdata descriptor | att-testdata/v1.0 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.1 |
| Load scenario | att-load/v1.6 |
| Load summary | att-load-summary/v1.1 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

本次調整的 resource/config schema 舊版本已移至 schemas/history，僅供歷史參考，不是 active execution contract。Unsupported version 會在 validation 失敗並提供 migration guidance。schemas/catalog.yaml 是 repository authoritative catalog。Package validation 會驗證已註冊的 schema resource 本身；封存不代表舊版本仍有 runtime compatibility。

## Appendix B — compatibility 與 deprecated aliases

Compatibility 的目的，是讓既有 package 可讀，而不是維持第二套 current model。新 authoring 使用 canonical `EXEC`、`META`、Action-local `output`、current schema、`--env` 與目前 Tool/DB/MQ contract。

Deterministic legacy alias 在可一對一映射時可以保留並產生 migration warning；若舊語義與 scope isolation 或 common result/evidence contract 衝突，就不建立 alias。Deprecated CLI/authoring form 只有在使用者仍需要 migration path 時才保留在其 owner page 或 CHANGELOG。

## Appendix C — migration notes

### File arguments 與 case log paths

HTTPHelper call 不再接受 `file`；請直接將 `&{package-relative-file}` 傳入 `body`。MQHelper `send` 和 `request` 不再接受 `file`；請將 expression 傳入 `payload`。SSHHelper `upload` 現在要求 `payload` content，並拒絕 `localPath`；請直接傳入 `&{...}`。SSHHelper 不再支援 `download`，因為它需要 local destination path。若工作必須從主機取回檔案，請明確配置 command-backed Tool。這些變更移除 Resource API 原生的任意 binary local-file input；`&{...}` 提供 UTF-8 text。

Case log、CLI output 和輸出的 case evidence 會將 canonical package root 下的 path 顯示為 `$ATT_HOME` 或 `$ATT_HOME/<relative-path>`，並統一使用 `/`。`$ATT_HOME` 只是在 presentation 中使用的 token，不是 environment variable、Context root 或 file-expression locator。Runtime resolution 和 filesystem access 仍使用 canonical absolute Path。package root 以外的 absolute path，不論是 Path value 或診斷訊息內的路徑，都會以受限的 `$EXTERNAL/<basename>` 顯示。明確的 remote-path 欄位及 URL 會保留原值。

### Previous release Testdata migration

將 global configuration 從 `att-config/v2.10` 升至 `att-config/v2.11`，並將 Load scenario 從 `att-load/v1.4` 升至 `att-load/v1.5`。舊 schema 仍登錄於 `schemas/history/`，供 migration diagnostics 使用。`att-testdata/v1.0` 是新增契約：在選定的 environment profile `testdata` list 加入 descriptor path，再於 Case/Stage、Debug 或 Load workload input map 使用 `@{id}`。Load scenario 可在頂層加入 package-relative `testdata` paths，形成僅適用於該次 Load 的 overlay。不同 layer 的同名 ID 會完整取代 descriptor；同一 layer 內的重複 ID 無效。多筆 records 的 descriptor 必須有明確 selection policy。沒有 testdata reference 的既有 package 不需要新增 descriptor。

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
| 舊 active resource/config schema | 使用 [Schema and Version Matrix](reference.zh/appendices/schema-matrix.md) 的 active schema，並遷移上述欄位。Historical schemas 不是 active contracts。 |

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

Load scenario 請將舊 single-target/v1.1 格式經由歷史 v1.2/v1.3 loader 遷移，再把 schemaVersion 升至 att-load/v1.6。Root defaults 可供多個 workload 共用；每個 workload 的 `inputs`、`vars`、load policy 及 execution 設定會覆蓋相應 root 值。Top-level thresholds 只屬於 aggregate；workload thresholds 必須在各 workload 宣告，不會從 root 繼承。`inputs` 仍對應 EXEC.INPUT；`vars` 在每個 execution 的 EXEC.ID 與 EXEC.OUTPUT_DIR 初始化後、target 啟動前評估。完整 reference 保留 native type，dependency 不受宣告順序影響；循環及 external/stateful calls 會在執行前拒絕。頂層 execution.execIdFormat 仍在 initialization 使用一般 expression engine 求值一次；closed workload 可用 EXEC.LOAD.USER_ID，arrival-rate 沒有此欄位。

歷史的 `att-load-profile/v1.0` policy file 僅供 migration 使用：使用前請改寫為現行 policy-only `att-load/v1.6` descriptor；它不是現行 `load/load.yaml` 範例。

Unsupported schema version 會在 execution 前失敗並提供 migration guidance。ATT 不會自動改寫 package，也不會為產生診斷而呼叫外部 resource。詳見[Action 與型別化值](reference.zh/actions.md)、[Runtime 與 Context 模型](reference.zh/runtime-context.md)、[Load 模式](reference.zh/execution-modes/load.md)與[Schema 矩陣](reference.zh/appendices/schema-matrix.md)。

### Load mix migration

現有 `att-load/v1.5` 檔案仍相容。升級至 `att-load/v1.6` 後，可將 workload 的 `target` 改為 `mix`，設定 closed-user weighted target mix。每個 entry 需要唯一 `id`、正整數 `weight`，以及 Template、Flow 或 Tool target。現行 summary contract 為 `att-load-summary/v1.1`；v1.0 保留作歷史格式。

### Historical schema migration

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

[META Runtime and Context Model](reference.zh/runtime-context.md) 說明 META lifecycle；[Load Mode](reference.zh/execution-modes/load.md) 說明 execution identity 和 retained evidence 路徑。

### Debug schema migration

`att-debug/v1.0` 為 historical schema；請升級至 `att-debug/v1.1`。Template/Flow 可配置 `vars` 以 seed `EXEC.VARS`；Tool 不支援 `vars`。Input 與 arguments 的現行規則見 [Debug](reference.zh/execution-modes/debug.md)。

### Environment profile migration

從舊的 complete-config pattern 遷移時，保留 descriptor 與 Action，將 common settings 移至 `config/config.yaml`、各環境 descriptor lists 移至 `environments.<NAME>`，並以 `--config config/config.yaml --env <NAME>` 選擇環境。現行 contract 見 [Configuration](reference.zh/configuration.md)。

## Appendix D — limits、security guarantees 與 advanced diagnostics

### Limits 與預設值

Normative field default 以其 owner schema 或 configuration page 為準。重要 architecture limit 包括：

- Load 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `DIAG` 是 framework-owned evidence，不屬於 expression tree；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。

### Collector projection 與 redaction guarantees

所有失敗 collector（包括 returned operation error 和 thrown Tool exception）都會先經過同一 public projection 再發布或寫 log。Projection 省略 raw input、payload、argv、output、resolved command text 和失敗 record 的 `result`，且不保證保留 `parserDiagnostic`。Native error/diagnostic 只保留安全 field；每個保留的 text field 限制為 1024 字元。`inputOmitted` 和 truncation flag 表示省略或截斷的 evidence。 Free-form message、stderr、per-instance error 和 cleanup warning 會在固定 budget 內 redact string、typed text 和 array input；最多檢查 256 個 input node、8192 個 token 字元，每個 token 最多 1024 字元。Byte array 最多處理 128 byte（UTF-8、Base64、hex 和 Java decimal rendering），其他 array 最多 64 個 element；char array 最多 1024 字元。超出任一 budget、private token 少於 4 字元或遇到未知 input type 時，會用安全 marker 省略所有 free-form failure detail（包括 upstream-truncated secret prefix/head-tail echo），並設置 `inputRedactionLimited` 和 `failureDetailsOmitted`。超過 1024 字元的 free-form field 也會被省略並標記 truncated；structured metadata 繼續保留。Structured status、category 和 resource identity 只做長度限制。SSH fan-out 會保留最多 64 個 instance 的 bounded metadata、error 和 stderr，優先保留失敗 instance；`instanceCount` 和 `instancesTruncated` 表示總數和省略的 instance。 若有 private token，且 operation 或 instance record 標記了 capture/detail truncation（如 `stderrTruncated` 或 `stderrArtifactTruncated`），該 record 的 free-form failure detail 也會被省略，以避免短 secret 被切斷後泄漏 prefix/suffix。沒有 private token 時可保留 bounded preview。Primitive array 的完整 list rendering 和單獨 element 都會在相同 node/token budget 內 redact。 DB returned failure 會從 native `result.error` 提取安全 summary（`type`、bounded/redacted `message`、`sqlState`、`vendorCode` 和安全 cancellation metadata），保留於 DB evidence 的 `error` 並用於 collector 的 `error`；不會發布 rows、parameters、SQL text 或 raw result。失敗 command 的 `stdout` 可作為獨立 diagnostic evidence，按與 `stderr` 相同的 bounded/redacted/omission policy 處理；不會作為 `error.message` 或恢復失敗 `result`。 MQ evidence 的 root 和 error summary 會保留 `completionCode`、`reasonCode` 和 bounded symbolic `reason`。安全 location metadata 包括 HTTP `method` 和僅含 scheme/host/port 的 `url` origin，以及 MQ `queueManager`、`physicalInstance`、`host`、`port`、`channel` 和 `transport`。HTTP evidence 沒有 resolved request input，因此失敗 collector 的 URL 一律省略 path、query、fragment 和 user info，並設置 `urlPathOmitted`；不添加 raw input。無法安全解析或超過 budget 的 URL 會以安全 marker 省略。

### Advanced diagnostics

#### 哪些意外異常會附帶 Stack trace？

意外內部故障（例如 `NullPointerException`、`ClassCastException`、反射查找／存取失敗、其他非預期 runtime exception，或非 domain `IllegalStateException`，包括包在 wrapper cause 內的情況）會在 `case.log` 寫入有界的 `[ATT INTERNAL ERROR]` 區塊、執行 phase 及原始 cause chain。Validation `IllegalArgumentException`、已識別的 domain／transport failure、timeout／cancellation、assertion failure 與一般 MQ no-message outcome 仍保持精簡。同一 Throwable 即使同時被 resource executor 和 Action boundary 看見，每個 Case log 也只會寫一次。Resource-specific redaction（包括由 environment 提供的 SSH identity-file path）會註冊到該 Case log，並套用至後續 log write，避免外層 Action diagnostic 洩漏未出現在 sanitized stack 的內容。Stack 最多 180 行／16 KB；configured secrets 與敏感 key/value assignment 也會遮蔽。Public Action evidence 只保留簡短錯誤類型／phase，不加入 stack。Run、Debug 及 reusable Tool/HTTP/MQ/DB 共用這條 logging path。

#### 為什麼 `Att.bat` 會要求 Maven，或者為什麼 `.sh` Tool 在 windows 上失敗？

在二進制發布中，`att.bat` 會找到 `lib\att-*.jar`，只需要 Java 8+。源碼樹中，`att.bat` 會在 Maven 在 `PATH` 上時使用 Maven；沒有 Maven 時，需要已有的 `target\classes`。先用 `att.bat version` 確認啟動器後再校驗包。

啟動器讓 ATT 自身跨平臺；它無法翻譯外部 Tool 可執行文件。請為 Windows 配置 `.bat`、`.cmd`、PowerShell 腳本（需要顯式 `powershell`/`pwsh` argv）或原生可執行文件，而不是 POSIX-only `.sh`。PATH 校驗遵循 Windows `PATHEXT`，因此如 `pwsh` 這類名稱可解析為 `pwsh.exe`。維護多平臺版本時，請保持參數契約和 stdout 輸出格式一致。

#### 為什麼 ATT 說會使用 mwiede/jsch，或者 Java SSH 協商失敗？

當 `PATH` 中存在可執行 `ssh` 時，ATT 會優先使用本地 `ssh`。如果不存在，ATT 會打印 `local ssh command not found; ATT will use Java SSH library mwiede/jsch`，並改用 Java exec channel。這是自動回退，不是遠程連通性測試。

回退實現非常保守：ATT 包含 `com.github.mwiede:jsch:2.28.2`，但不捆綁 Bouncy Castle。它要求一個可讀、非符號鏈接的 `~/.ssh/known_hosts` 用作嚴格主機驗證。它不會讀取 `~/.ssh/config`，也不會自動使用 OpenSSH agent；需配置一個非交互可讀的 `identityFile`。密碼和交互式口令提示不支持。

算法可用性取決於 Java 運行時：

| 算法 | Java 回退限制 | 首選方案 |
|---|---|---|
| `ssh-ed25519`、`ssh-ed448` | 需要 Java 15+ 或 Bouncy Castle provider | 優先使用本地 OpenSSH 或 Java 15+；否則讓管理員把批准的 `bcprov-jdk18on` 加入運行時 classpath |
| `curve25519-sha256`、`curve448-sha512` | 需要 Java 11+ 或 Bouncy Castle | 優先本地 OpenSSH 或 Java 11+；否則使用批准的 Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | 在所有 Java 版本上都需要 Bouncy Castle | 優先本地 OpenSSH，或在服務端啟用 AES-GCM/CTR cipher，並添加 Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` 簽名 | 默認被 mwiede/jsch 禁用 | 更新服務端到 RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) 或其他現代 host/user-key 算法；不要在未經審查的情況下重啟 SHA-1 |

協商失敗時，先用本地 `ssh -v` 復現連接，定位 host-key、key-exchange、cipher 或 user-key 不匹配。優先升級 Java 或服務端算法集合，而不是弱化 JSch 默認值。
