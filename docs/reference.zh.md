# ATT v4.6.0 使用手冊與參考

Author: Jeffrey + ChatGPT
Version: 4.6.0
Status: 規範性使用者文件；由模組化來源自動生成

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->

**目錄**

- [概覽與 Product model](#概覽與-product-model)
  - [產品模型](#產品模型)
  - [RUN 與 LOAD](#run-與-load)
  - [Resource 是同級概念](#resource-是同級概念)
  - [Package 邊界](#package-邊界)
  - [如何使用本手冊](#如何使用本手冊)
- [Test authoring](#test-authoring)
  - [撰寫 Native Case](#撰寫-native-case)
  - [區分 Resolved Case 與 Case execution](#區分-resolved-case-與-case-execution)
  - [編寫契約](#編寫契約)
  - [Native Test Case Definition](#native-test-case-definition)
  - [Flow](#flow)
  - [Authoring lifecycle](#authoring-lifecycle)
  - [Test data ownership](#test-data-ownership)
  - [Testdata registry 與 input mapping](#testdata-registry-與-input-mapping)
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
- [Runtime 和 Context model](#runtime-和-context-model)
  - [EXEC roots](#exec-roots)
  - [META 欄位](#meta-欄位)
  - [Invocation 和 scope 規則](#invocation-和-scope-規則)
  - [Optional lookup](#optional-lookup)
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
- [Execution mode 概覽](#execution-mode-概覽)
  - [統一 RUN 與排程](#統一-run-與排程)
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
  - [Transaction 與 Resource lifecycle](#transaction-與-resource-lifecycle)
  - [Aggregation](#aggregation)
  - [Tool timeout precedence](#tool-timeout-precedence)
  - [Direct DB timeout 與 retry eligibility](#direct-db-timeout-與-retry-eligibility)
- [Configuration 與 Environment](#configuration-與-environment)
  - [Configuration layers](#configuration-layers)
  - [選擇 Environment](#選擇-environment)
  - [設定 execution identity 和 output](#設定-execution-identity-和-output)
  - [使用 `x-` 忽略或停用 ATT 配置項](#使用-x-忽略或停用-att-配置項)
  - [Resource bindings 和 credentials](#resource-bindings-和-credentials)
  - [驗證變更](#驗證變更)
- [Package Manifest](#package-manifest)
  - [宣告 Package 預設值](#宣告-package-預設值)
  - [解析 Environment](#解析-environment)
  - [套用 typed defaults](#套用-typed-defaults)
  - [解析 invocation Testdata](#解析-invocation-testdata)
  - [在 Studio 檢視及發布](#在-studio-檢視及發布)
  - [遷移舊 Package inventory](#遷移舊-package-inventory)
  - [API paths](#api-paths)
- [CLI Reference](#cli-reference)
  - [編譯 source checkout](#編譯-source-checkout)
  - [常用命令](#常用命令)
  - [執行 Case 或 Suite](#執行-case-或-suite)
  - [驗證 Package](#驗證-package)
  - [診斷 Flow 或 Tool](#診斷-flow-或-tool)
  - [執行 Load workload](#執行-load-workload)
  - [執行 Server 命令](#執行-server-命令)
  - [常用 options](#常用-options)
  - [Exit code](#exit-code)
- [結果、報告與 evidence](#結果報告與-evidence)
  - [ATT Server Job artifact 發佈](#att-server-job-artifact-發佈)
  - [查找 RUN artifacts](#查找-run-artifacts)
  - [閱讀 Case 結果](#閱讀-case-結果)
  - [查看 CI summary](#查看-ci-summary)
  - [診斷 Tool evidence collectors](#診斷-tool-evidence-collectors)
  - [重現 RUN](#重現-run)
  - [閱讀 Case logs](#閱讀-case-logs)
  - [離線 SUT Metrics 報告](#離線-sut-metrics-報告)
- [Validation 和 troubleshooting](#validation-和-troubleshooting)
  - [選擇第一個 diagnostic step](#選擇第一個-diagnostic-step)
  - [驗證 Native input](#驗證-native-input)
  - [常見問題](#常見問題)
  - [安全與結構化 diagnostics](#安全與結構化-diagnostics)
- [CI、打包與運維](#ci打包與運維)
  - [Development/release gates](#developmentrelease-gates)
  - [Runtime dependencies](#runtime-dependencies)
  - [Studio inspection cache](#studio-inspection-cache)
  - [Documentation operations](#documentation-operations)
  - [CI 與 environment promotion](#ci-與-environment-promotion)
- [Appendix A — schema 與 version matrix](#appendix-a-schema-與-version-matrix)
- [Appendix D — limits、security guarantees 與 advanced diagnostics](#appendix-d-limitssecurity-guarantees-與-advanced-diagnostics)
  - [Limits 與預設值](#limits-與預設值)
  - [Collector projection 與 redaction guarantees](#collector-projection-與-redaction-guarantees)
  - [Advanced diagnostics](#advanced-diagnostics)
## 概覽與 Product model

ATT 把可重用的執行邏輯與業務資料分開。現行 Case 路徑是：Test Case Definition 綁定 Flow 與 Data Source，解析成所選的 Resolved Case，再以 Flow 的有序 Actions 和 Resource 執行每一個 Case。

### 產品模型

```text
Test Case Definition (att-testcase/v1)
  |-- Flow (att-flow/v3.6)
  `-- Data Source
        -> Resolution
        -> Resolved Case
        -> Suite selection
        -> Case Execution
              `-- Flow
                    `-- ordered Actions
                          |-- file-content expression / assert / log / assign / set
                          `-- Resource
                                |-- Tool
                                |-- DBHelper
                                |-- MQHelper
                                |-- HTTPHelper
                                `-- SSHHelper
```

**Test Case Definition**（`att-testcase/v1`，通常是 `testcase.yaml`）綁定一個 Flow 和一個 Data Source。**Flow** 是可重用的有序 Actions。**Resolution** 把 Definition 解析成 Resolved Cases。V1 把每一筆非空白來源紀錄正好解析成一個 **Resolved Case**；它不會把一筆紀錄展開、合併或額外產生多個 Case。**Suite selection** 選擇要執行哪些 Resolved Case。不需要強制的 `suite.yaml`。**Case execution** 是所選 Resolved Case 的一次 RUN。**Action** 是該 Flow 內的一個有序工作單元。**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper、MQHelper、HTTPHelper 或 SSHHelper。

### RUN 與 LOAD

RUN 以 qualified ID、tag 或 implicit Suite 選擇 Resolved Case。Debug 直接診斷 Flow 或 Tool。LOAD 重複執行 Flow 或 Tool workload；Native Case LOAD target 仍在延後範圍。詳見[統一 RUN 與排程](reference.zh/execution-modes/unified-run.md)。

| 模式 | 輸入 | 結果 |
|---|---|---|
| RUN | Definition 與所選 Resolved Case | 每個所選 Case 執行一次 |
| Debug | Flow 或 Tool target 與明確 inputs | 一次獨立診斷執行 |
| LOAD | 已驗證且包含 Flow 或 Tool workload 的 scenario | 重複 iteration 和 workload evidence |

可重用 Flow 使用 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。Execution mode 和 scheduler identity 是保留 evidence 中的 framework diagnostics，不是 expression data。

### Resource 是同級概念

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是獨立 Resource 類型。其 configuration 與 lifecycle 各有不同，但 Actions 會透過 `output.result` 發布 typed operation result，並將選用的 presentation evidence 分開保存。Public expression 應讀取 Action result/evidence，不應依賴 Resource 內部 connection/process state。SSH Resource 操作在一般 `type: tool` Action 中使用共通的 `ssh.<helperId>.<operation>` 格式。

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

### Package 邊界

每個 Package 擁有 Flows、Test Case Definitions、Package-local Tools、Testdata 和生成的 output。所有 Package 共用 `ATT_HOME/config/` 下的 runtime configuration；受信任 schema 由 ATT 內嵌。Package 不需要自己的 `config/` 或 `schemas/` 目錄。執行前會驗證路徑與 identifier。Credential 應放在受保護的 runtime input 或 secret store。

逐步建立範例請使用[快速入門](quick-start.zh.md)。維護以較早模型編寫的 Package，請參閱[舊版文件](legacy/README.zh.md)。本手冊其餘部分是規範性查閱文件。

### 如何使用本手冊

| 目標 | 文件 |
|---|---|
| 建立第一個 ATT Package | [Quick Start](quick-start.zh.md) |
| 理解 ATT 核心 model | [Product model](reference.zh/overview.md)、[Test Authoring](reference.zh/test-authoring.md)、[Actions](reference.zh/actions.md)、[Runtime and Context](reference.zh/runtime-context.md) 和 [Run mode](reference.zh/execution-modes/run.md) |
| 配置 DB/MQ/HTTP/SSH | [Resources](reference.zh/resources/overview.md) |
| 查閱 CLI option | [CLI Reference](reference.zh/cli.md) |
| 診斷失敗 | [Validation and Troubleshooting](reference.zh/validation-diagnostics.md) |
| 維護既有 Package | [舊版文件](legacy/README.zh.md) |

Reference 定義 public contract；README、Quick Start 與 examples 按特定任務說明這份 contract。每項 contract 由一個 semantic owner 定義，其他 Reference 頁面提供摘要並連結至 owner。

## Test authoring

### 撰寫 Native Case

新的 Case 使用這條路徑。維護以較早模型編寫的 Package，請使用[舊版文件](legacy/README.zh.md)。

~~~text
Flow + testcase.yaml + Data Source -> Resolution -> Resolved Case -> Suite selection -> Case Execution
~~~

1. 撰寫或重用一個 Flow（`att-flow/v3.6`）。
2. 撰寫綁定該 Flow 和一個 Data Source 的 Test Case Definition（`att-testcase/v1`）。
3. Resolve 該 Definition。Preview 是唯讀的，不會建立 Job。
4. 選擇 Resolved Cases：單一 Case、明確的 qualified ID、tags，或 implicit Suite。不需要強制的 `suite.yaml`。
5. 驗證 Package，然後 RUN。每個被選中的 Resolved Case 會成為一次 Case Execution，各自擁有 execution ID、狀態和 evidence。

`att-testcase/v1` 是精確的 1:1 來源紀錄契約，由本頁和 schema `att-testcase/v1` 定義：

- 只有 typed `inputs` map、沒有 Data Source 時，會解析成一個 ID 為 `default` 的 Case。
- 每一筆非空白的 inline 或 Excel 紀錄正好解析成一個 Resolved Case。Resolution 不會把一筆紀錄展開成多個 Case。
- `caseIdExpression` 只是一次 `${DATA.key}` 查詢，不是 generator。
- Excel `dataColumns` 會明確選取來源欄，並支援別名、YAML 解析和引號分隔符。
- Definition `id` 屬於 Package。Resolved Case ID 是一個 path-safe 片段。Qualified ID 是 `definitionId.caseId`。
- typed input 投影、報告和 Package 檔案互相隔離。Resolution 和 RUN 不會修改 Definition 或來源 Workbook。

n 對 m generator 不屬於 `att-testcase/v1`，仍然延後。既有 Package 使用的 Data Source 格式請參閱[舊版文件](legacy/README.zh.md)。

### 區分 Resolved Case 與 Case execution

**Resolved Case** 是 Resolution 產生的一個可選邏輯 Case。**Case execution** 是該 Resolved Case 的一次 runtime 執行。Qualified Case ID 在多次 RUN 之間保持穩定；execution ID、狀態和 evidence 屬於單次執行。

### 編寫契約

| 需求 | Native 契約 |
|---|---|
| 可重用的有序 Actions | Flow |
| 單一有序操作 | Action |
| 外部能力 | Resource |
| 業務輸入 | Definition `inputs` 或 `DATA` projection |
| 跨 Action 的可變狀態 | `EXEC.VARS` |
| Case 選取 | Resolved Case ID、tag 或 implicit Suite |

### Native Test Case Definition

現行契約是 `att-testcase/v1`。目前的 default branch 已包含 Native Case CLI，以及經驗證的 Server draft flow，支援 singleton、inline 和 Excel Definition 的 discovery、resolution 和 RUN。這些程式已整合在這個 branch。Native RUN 已在此實作。4.6.0 release 和 tag 尚未發佈。implicit Suite 驗收、Native 報告驗收，以及剩餘的 Default Package 採納仍然開啟。Native LOAD Case target、n 對 m generator 和 Debug interoperability 維持延後。現有直接 LOAD 和 Debug 路徑仍受支援。

Native Excel adapter 只讀取 Package 內 `.xlsx`，支援明確欄位映射、blank normalization 和安全 YAML cell parsing，並在每個 resolved Case 保留 workbook revision、sheet、row 及 column provenance。壓縮後的 workbook 上限為 32 MiB；POI 載入前會預檢 ZIP archive，最多 256 個 entries、每個解壓 entry 32 MiB、總解壓資料 64 MiB。選取的 worksheet 最多 100,000 個實體 rows（包括空白列）；header scan 最多 1,000,000 個 header cells；Resolution 最多接受 10,000 筆非空白 records。Preview 亦有上限。YAML cells 沿用共用 parser 限制：3,000,000 code points、50 個 collection aliases 及 50 層巢狀；解析後的 value graph 最多 64 層及 100,000 個值。系統拒絕非有限數字和遞迴 aliases。

V1 契約定義兩種資料形態：含完整 typed `inputs` map、Resolved Case ID 固定為 `default` 的 singleton inline Case；或含穩定來源 ID 的多筆 inline／Excel Data Source。Definition ID 在 Package 範圍內唯一、保留大小寫並使用點號分段；Resolved Case ID 是單一 path-safe 段落。Excel Case ID 必須是符合 `[A-Za-z0-9_-]{1,128}` 的 literal text cell；公式、數字／日期／boolean／error cell、blank markers、前後空白、保留裝置名稱和 case-fold collision 都會被拒絕。`caseIdExpression` 只可用單一 `${DATA.key}` lookup 覆蓋 resolved ID。空白、重複、格式錯誤、缺少或衝突的 ID，以及空的 resolution 結果都屬錯誤，不會當作全部 PASS。

Data-driven Case 省略 `inputs` 時，會把 `DATA` 同名映射到 Flow inputs；提供 projection 時，該 projection 會完全取代 identity mapping，只接受 `{from: DATA.key}` 或 `{value: <typed value>}`。Excel `dataColumns` 會明確選取來源欄，支援 alias、YAML parsing 和引號分隔符。省略或空白的 `dataColumns` 不會把任何業務欄選入 `DATA`（Case ID／tags selector 也不會自動暴露）；需要 Excel 輸入時必須明確宣告 `dataColumns`。Excel `path` 必須是可移植的 Package 相對 `.xlsx` 路徑。

每次 Native RUN 都會在 `workbooks/native-cases.result.xlsx` 建立不依賴來源 Workbook 的結果報告，內容包括所選 Case 的結果。Excel-backed Definition 可額外設定 `report: {format: xlsx, template: sourceWorkbook}`，要求產生選用的來源 Workbook 版面 artifact：`workbooks/native-cases.source-workbook.xlsx`。RUN 開始前，Engine 會 pin 及驗證 workbook bytes、formula inventory 和所選實體來源列身份。結果只寫入獨立檔案中的所選列，保留來源公式，且不修改 Package 內原始 Workbook。

選用 artifact 限於單一 Excel Definition 和 worksheet、非空且不超過 1,000 個 Case 的 selection，以及不超過 16 MiB 的輸出。發佈前，ATT 會在每個 worksheet 遮蔽敏感來源欄，包括 header 或 mapping alias 被分類為敏感的欄，並在其他位置遮蔽已登記的 secret value。如果遮蔽可能留下敏感的 cached formula result，或無法安全驗證／發佈來源版面檔案，ATT 會省略此選用 artifact 並記錄 fallback diagnostic；標準結果 Workbook 和 Case 結果仍可用。如果 Workbook 在解析後改變，或已不符合 pinning 的資料列，ATT 會在執行前拒絕，不會改用過時來源資料而靜默 fallback。

來源版面報告包含已遮蔽敏感值的 Workbook 其他工作表和資料列。Server 只有在 Job 建立時的原始 principal 目前仍有該 Package 的 editor 權限時才會將可選報告放入 archive；讀取 artifact 清單、每個 entry 和整個 archive 時，也會重新檢查目前的 Package editor 權限。沒有該權限的使用者仍可按一般 artifact 權限讀取不依賴來源的標準結果 Workbook 和其他 Job artifacts。

完整契約和驗證界線見[active Schema](../schemas/att-testcase-v1.schema.json)、[Singleton inline 範例](adr/examples/att-testcase-v1/inline-single.yaml)、[多筆 inline 範例](adr/examples/att-testcase-v1/inline-multi.yaml)、[Excel 範例](adr/examples/att-testcase-v1/excel.yaml)、[省略 `dataColumns` 的 Excel 範例](adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml)及[ADR-0001](adr/0001-flow-centric-testcase-resolution.zh.md)。

Default Package 已附上離線 Native Quick Start Definitions（`packages/default/testcase/quick-start/**/testcase.yaml`），綁定 Flow `quick.start.v1`。請參閱[Default Package Native 快速入門指南](quick-start.zh.md)與 [S12 遷移矩陣](maintenance/native-case-default-package-migration-s12.md)。S07 亦提供上述可選的來源 Workbook 版面報告。

#### 在 Studio 編寫

在 Explorer 選擇 **New native Test Case**，或開啟索引中的 `testcase.yaml`。引導式編輯器可設定 Package 範圍的 Definition ID、名稱、標籤、canonical Flow、typed inputs，以及單一 Case 或 inline records。進階 YAML 可編輯引導式表單以外的欄位。新檔案必須位於 Package 已配置的 `resources.testcaseRoot` 之下（預設為 `testcase/`）。

**Preview / Resolve** 會把目前編輯器中的 candidate 及 Package-relative `testcase.yaml` 路徑提交至已驗證身份的 Server。Server 會檢查精確 USER/editor 權限和 Package source revisions，再透過有界的 Test Case resolver 及 S04 Excel adapter 解析 candidate。預覽最多回傳 1,000 個 Case，並提供 Package source revision、Definition revision，以及適用時的 workbook revision。Excel row 錯誤會標示 sheet、row 和 column。Typed inputs 依照 Server 設定的 redaction policy 處理。預覽不會建立 Job 或 RunDraft、不會寫入 Package source，也不會修改 Workbook；RUN validation 會再次解析並凍結已選的 Case ID。

使用 **Add to Change Set**，透過現有 Package source 工作流暫存新增或替換：Draft → Validate → Review → Publish。發布前目前 Package source 仍保持啟用。發布後請預覽最新 source，再選擇 Case ID 進行 RUN 驗證和提交。只有在 Server 宣告支援 `att-execution/v2` 時，Native Case RUN 才可用；Server 會為 Job 驗證並凍結 source 和已選的 ID。

Excel-backed Definition 使用與 native resolution 相同的唯讀 S04 adapter，並在 Studio 顯示 row 和 column diagnostics。維護以較早模型編寫的 Package，請使用[舊版文件](legacy/README.zh.md)。

#### Run an implicit Native Case Suite

4.6.0 發佈目標可在已解析的 Case 上建立 Suite selection，毋須作者建立 `suite.yaml`。先指定一個或多個 Definition ID，再選取全部 Case、指定完整 Qualified Case ID，或按一個或多個 tags 選取。被排除的 tags 優先於包含的 tags。

以下本地命令會從兩個 Definition 選取所有 `smoke` Case，但排除帶有 `destructive` tags 的 Case：

```sh
att --local --package payments run native-suite --definition fpp.transfer --definition fpp.refund --tag smoke --exclude-tag destructive --fail-fast
```

若要選取指定 Case，為每個 ID 使用 `--qualified-case-id`，例如 `fpp.transfer.customerA`。每個 ID 必須屬於已指定的 Definition。Server backend 會透過已驗證的 `att-execution/v2` RUN draft flow 使用相同選取器。

ATT 會按 ID 排列 Definition，並保留每個 Definition 的來源記錄順序。重複 Definition ID、重複 Qualified Case ID、不存在的 ID，以及空選取都會在驗證時失敗。Worker 執行前，Server draft 會凍結選取 ID 和 Definition source revision。每個 Case 的結果沿用現有 RUN evidence 和報告流程。

Implicit Suite 是 runtime selection，並非可重用的 authored Definition。這項功能不會新增 LOAD target 或進階 Testdata/generator binding；Debug interoperability 仍屬後續工作。

Studio Run editor 亦提供這項選取功能：輸入一個或多個 Definition ID，再選擇全部 Case、指定 Qualified Case ID，或按 tags 選取 Case。可用 Include Tags 和 Exclude Tags 進一步篩選；重播時會保留保存定義傳回的 tag selector。Native Suite `--rerun-failed` 只會重跑同一 Package 和 Definition 集合中最近一個合資格已完成 RUN 內曾執行但失敗的 Case。Local RUN 會驗證 latest-run pointer 和 manifest hash；Server draft 會凍結來源 Job/run、manifest digest、Package revision 和精確 Qualified Case ID。過期 draft 或來源變更會被拒絕，Skipped Case 不會重跑，每次嘗試的執行／略過數會與來源及 retry 次數分開記錄。Schedule 會保留已驗證的 retry source。同一個 selector 可在 Studio Run editor 和 CLI 使用。它已在目前的 default branch 實作並整合。implicit Suite 驗收仍然開啟，且尚未發佈 4.6.0 release tag。

### Flow

Flow 是一等的可重用有序 Actions，使用 `att-flow/v3.6`，並由 `flow.yaml` 定義。Native Test Case Definition 直接綁定該 Flow。必填欄位為 `schemaVersion`、versioned canonical `id`（例如 `common.payment.v1`）、`name`、`description` 及非空有序 `actions` map。每次 invocation 建立新的 `EXEC.ACTIONS` scope；回傳後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。[Actions](reference.zh/actions.md) 定義 Flow result 與 Assign behavior；[Context](reference.zh/runtime-context.md) 定義 lifetime。

### Authoring lifecycle

修改 Flow 或 Test Case Definition 後執行 `./att.sh validate --package`，再 RUN 所選的 Resolved Case。用 [Debug](reference.zh/execution-modes/debug.md) 隔離單一 Flow 或 Tool，再以 [Run](reference.zh/execution-modes/run.md) 建立 Case execution。循序建立第一個 package 請使用 [Quick Start](quick-start.zh.md)。

### Test data ownership

Native Test Case Definition 擁有自己的 Data Source 與 input projection。Resolution 讀取該來源，不會修改它。[Context](reference.zh/runtime-context.md) 定義 scope 與 lifetime。RUN 會把每個選中的 Resolved Case 轉為 Case execution。Environment selection 由 [Configuration](reference.zh/configuration.md) 定義。

### Testdata registry 與 input mapping

可用 `att-testdata/v1.0` descriptor 儲存可重用 records；只在 Case、Debug `inputs` 或 Load workload `inputs` mapping 中引用。完整 `@{id}` 會保留 record 的原生 map/list/scalar 型別；`@{id.path}` 可讀取巢狀值，也支援數字 list index。內嵌參照（例如 `"ORD-@{accounts.id}"`）會產生文字，因此所選 record 的欄位必須是 scalar。Mapping 中的 `${...}` 只能讀取該 mapping 解析前已初始化的 Context root。允許的 root 依 mapping 階段而異，並會在 execution 開始前驗證（Load 則在 scheduler 啟動前驗證）：

- RUN mapping 可讀取 `EXEC.ID`、`EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、`EXEC.OUTPUT_DIR`，以及 `META.SOURCE` 或 `META.TARGET`。
- Debug `inputs` 可讀取相同的 execution root 與 metadata，另加 target metadata。
- Load workload `inputs` 可讀取 `EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、已初始化的 `EXEC.LOAD` identity fields，以及 `META.SOURCE`、`META.TARGET` 或 target metadata。`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化。Arrival-rate workload 不提供 `EXEC.LOAD.USER_ID`；若 mapping 要同時支援兩種模型，請使用 optional path `${EXEC.LOAD.USER_ID?}`。

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

[Package Manifest](reference.zh/package-manifest.md) 宣告 Package Testdata registry，以及可選的 Environment replacement lists。Load scenario 可在頂層宣告本地 `testdata` imports；同 ID 會在該次 Load 完整取代 environment descriptor。單一 layer 內的重複 ID 會報錯。一般 Run/Debug 只延遲啟用實際引用的 ID；`validate --package` 會檢查全部 Manifest descriptor layers。Flow 與 Tool 定義只能透過 `EXEC.INPUT` 取得已解析資料，不可直接寫 `@{...}` 或 `%{...}`。詳見[Environment 與 Test Data](reference.zh/configuration.md)、[Load](reference.zh/execution-modes/load.md) 及維護者的 [testdata design](system-design/testdata.zh.md)。

## Actions 與 typed values

本頁定義 ATT 現行 Action 契約。Native Flow Actions 使用 att-flow/v3.6。每個完成的 Action 都會在 output.result 發布邏輯型別化值；Action 不使用共用的 result.format/path/overwrite 物件。Resource 配置請從[Resources](reference.zh/resources/overview.md)開始查閱。

### Action 類型

| 類型 | 必填欄位 | 結果與行為 |
|---|---|---|
| tool | call | 呼叫已配置 Tool、built-in 或 helper，保留原生型別化結果；DB query/scalar/update 也是普通 Tool call。 |
| assert | assert | 評估布林條件並記錄 PASS 或 FAIL。expected、actual 是可選診斷值。 |
| log | message 或 value | 將型別化值格式化後寫入 Case 日誌。欄位為 message、value、format。 |
| assign | name、expression | 將 expression 的型別化結果發布至 EXEC.VARS 一次。 |
| set | name、expression | 以 expression 的型別化結果取代既有的頂層 EXEC.VARS 值。 |
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

DB result 本身已是型別化值。Tool、Action、Flow 和 expression results 在 ATT 中傳遞時均保留型別。

DB query、scalar、update operation 在普通 `type: tool` Action 內使用 `db.<helper>.query(...)`、`db.<helper>.scalar(...)`、`db.<helper>.update(...)`。DB call 接受一個 String `sql`，以及 `params` 或 `parameters` 其中一種；`sql=&{package-relative-file.sql}` 可提供 package SQL 內容。歷史 `type: db` Action 只由 archived schema 保留。

### File-content expression 回傳 string

歷史 Render Action 的現行替代方式是 typed file-content value expression `&{path}`。它一定回傳一個 `String`，不會推斷 document format、parse 副檔名、展開 glob 或建立輸出檔：

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"
~~~

`${...}` 仍然是 Context reference，`#{...}` 仍然是 expression/call，`&{...}` 是 static、one-file locator；v1 沒有 glob 或 dynamic locator。Locator 相對 canonical ATT package root。`./` 或 `../` descriptor-relative path 只有在 canonical target 仍在該 root 內才允許。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白和 glob syntax 都會在 validation 失敗。

普通 UTF-8 file 會原樣回傳。如果 file 包含 `${...}` 或 `#{...}`，ATT 只 compile 一次這些 node，並在每次 execution 評估；compiled plan immutable，dynamic value 不會再次解析成新的 expression source。Run 和 Debug 會重用 plan，直到 file fingerprint 改變。Load 會在 scheduling 前 validation 並 capture selected file identity、content 和 compiled dependency closure，因此 active iteration 看到immutable dependency closure。

若要由後續 Action 重用 String，使用 Assign：

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"

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

Log 是一般 Case-log entry，沒有 user-authored severity；現行 v3.6 契約移除 `level`，migration 時請刪除該欄位。較早 Log-level 行為見[舊版 authoring guide](legacy/authoring.zh.md)。Internal diagnostic severity 維持獨立。message 或 value 至少要有一項。message 以文字求值。value 可接受任意型別化值，包括巢狀 map/list。完整的 ${...} 和 #{...} expression 保留原始型別；map/list 子節點會遞迴求值，不會將數字、布林、null 或巢狀值轉成字串。format 支援 text、json、yaml、xml、sqlplus，只控制寫入 Case 日誌的字串。指定 format 時必須提供 value。

同時提供 message 和 value 時，Log 輸出 message、換行，再輸出格式化 value。output.result 是最終字串。File-content String 會原樣輸出；Log 不會推斷或附加 document format，也不會讀取檔案或使用 fields map。需要結構化日誌時，將 typed map/list 放到 value。

### Expressions 與變數 scope

Action expression 使用一般 ATT expression engine。完整 ${...} 或 #{...} expression 保留結果型別；expression 放在一般字串內才會成為 String。請參閱[Expressions and Built-ins](reference.zh/expressions.md)。

assign 會將 typed value 發布至 EXEC.VARS.<name> 一次。name 必須符合 [A-Za-z_][A-Za-z0-9_]*，且在 Case 中唯一。使用相同的 name 和 expression 欄位可透過 set 更新既有的頂層 EXEC.VARS 值；set 不會宣告不存在的變數。Expression 會讀取更新前的 Context 值，並只在評估及 assertion 成功後發布，保留原生型別。set 可使用共用 Action retry block：每次 attempt 都以尚未更新的變數評估；失敗 attempt 保留原值，只有 winning attempt 會發布一次。Attempt 狀態及 retry decision 保留在 output.attempts。兩種 Action 的 EXEC.VARS 都會在順序執行的 Actions 和巢狀 Flow 間共用。set 更新 Debug/Load bootstrap 變數時會消耗首次 assign 的許可；之後的 assign 不可覆寫該更新。Action 執行期間可讀取 output.*，發布後可讀取 EXEC.ACTIONS.<id>.output.*。Flow 有暫時 Action namespace；EXEC.VARS 更新仍會保留給 caller。

~~~yaml
initializeCounter:
  type: assign
  name: counter
  expression: "#{0}"
incrementCounter:
  type: set
  name: counter
  expression: "#{${EXEC.VARS.counter} + 1}"
~~~

此範例會讓 `EXEC.VARS.counter` 保持型別化數字。set 若遭略過或評估失敗，原有值會維持不變。

### Resource output Evidence

Resource evidence 與邏輯 result 分離。Helper 可設定可選的 evidence.output presentation policy：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

此設定會在 operation metadata 旁加入有長度上限的有界的人類可讀 rendering，不會改變 output.result 或 response parsing。Load 的 evidence.resources.output 可設 inherit（預設）或 none。none 會略過 resource-output 格式化與檔案物化。Metrics-only iteration 不建立 execution 目錄。Iteration evidence 被保留後，符合條件的 resource output 才會延遲格式化至該 workspace。

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

String、Number、Boolean、null、Map、List 等值跨越 Action/Flow boundary 時都保留原型別。

### 共用 Retry 與 boolean condition

Tool Action、可重試的 DB query/scalar call 及 set Action 共用 `retry` 契約。`maxAttempts`（2–10）、`intervalMs`（0–3600000）及非空 `retryOn`（ASSERTION/TIMEOUT）仍為必填；`when` 是可選的非空 Boolean expression String。set 使用 `ASSERTION` retry 時必須有 Action `assert`；失敗 attempt 不會提交候選值至 EXEC.VARS。Mutating DB update 及 SSH transfer 的既有 retry 限制維持不變。

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

## Runtime 和 Context model

RUN、Debug 和 LOAD 共用 canonical `EXEC` 與 `META` expression root。`EXEC` 保存 identity、typed input、variable 和已完成 Action。`META` 描述所選 source、target、Flow 和 active Resource invocation。這些 root 不包含 secret。

Standalone Debug 會將 `inputs` 映射到 `EXEC.INPUT`；選用的 `vars` 會初始化 `EXEC.VARS`。Input schema 和受保護的 root 見 [Standalone Debug](reference.zh/execution-modes/debug.md)。

### EXEC roots

| Path | 意義與可用時機 |
|---|---|
| `EXEC.ID` | 目前 Case execution、Debug execution 或 LOAD iteration ID；初始化後可用。 |
| `EXEC.RUN_ID` | Enclosing ATT Run ID；同一 run 的 Cases 和 iteration 共用。 |
| `EXEC.STARTED_AT` | 目前 execution 的 ISO-8601 起始時間。 |
| `EXEC.RUN_STARTED_AT` | Enclosing run 的 ISO-8601 起始時間。 |
| `EXEC.OUTPUT_DIR` | Execution workspace path；LOAD 中可能先規劃、之後才建立目錄。 |
| `EXEC.INPUT` | Case、Debug target 或 LOAD workload 的 typed input map。 |
| `EXEC.VARS` | 由 `assign` 建立、由 `set` 更新，或由 Debug bootstrap 提供的 typed value。 |
| `EXEC.ACTIONS` | Active Flow scope 中已完成的 Action。巢狀 Flow 使用獨立 scope。 |
| `EXEC.LOAD.*` | 只在 LOAD 中提供 workload 和 iteration identity。Arrival-rate workload 不提供 `USER_ID`。 |

Scheduler counter、queue state 和 timing diagnostic 保留於 evidence，不屬於 expression root。可重用 Flow 應透過 input contract 取得 business identifier，不要依賴 `EXEC.ID`。

### META 欄位

| Path | 意義與可用時機 |
|---|---|
| `META.SOURCE.type` | Source 類型：`testcase`、`debug` 或 `load`。 |
| `META.SOURCE.path` | Source 有檔案時的 Package-relative logical path。 |
| `META.SOURCE.caseId` | Case RUN 中的 qualified Case ID；Debug 和 LOAD 時缺席。 |
| `META.SOURCE.scenario` | LOAD scenario identity；非 LOAD 時缺席。 |
| `META.TARGET.type` | 已解析 target 類型：Case、Flow 或 Tool。 |
| `META.TARGET.id` | 已解析的 target identifier。 |
| `META.FLOW.id` | Flow invocation 期間的 active Flow ID。 |
| `META.FLOW.invocationId` | Invocation 期間呼叫該 Flow 的 Action ID。 |
| `META.FLOW.depth` | 由 1 起始的巢狀 Flow depth。 |
| `META.TOOL.id`、`META.TOOL.type` | Active configured Tool 或 built-in invocation。 |
| `META.DBHELPER.*`、`META.MQHELPER.*`、`META.HTTPHELPER.*`、`META.SSHHELPER.*` | 對應 invocation 期間的 logical Resource identity。 |

META 是不可變的描述資料。Component 未啟用時，相應欄位會缺席。Resource endpoint、username、identity file 和 credential 會留在 executor 內，不會發布為 META value。

### Invocation 和 scope 規則

進入 Flow、Tool 或 Resource invocation 時會發布其 active metadata。巢狀呼叫會 push scope frame，返回時恢復 caller metadata。沒有 active invocation 時，相應 branch 缺席；不要依賴「最後一次呼叫」狀態。

`EXEC.INPUT` 是目前 execution 的 input map。`EXEC.VARS` 可供同一 Flow invocation 內後續 Action 使用。`EXEC.ACTIONS` 保存該 Flow scope 中已完成的 Action result。Action 執行時讀取 local `output`；完成後 value 會發布至 `EXEC.ACTIONS.<id>.output`。

### Optional lookup

`${path}` 是 strict lookup。`${path?}` 只會對允許的缺失 map/list path 回傳 null，不會讓無效語法或不允許的 scope access 變成有效。

既有 Package 若依賴較早的 Context name 或 metadata，請使用[舊版文件](legacy/README.zh.md)。[Actions Reference](reference.zh/actions.md) 定義 Action result；[Debug](reference.zh/execution-modes/debug.md) 和 [LOAD](reference.zh/execution-modes/load.md) 定義各自的 input initialization rules。

## Expressions 與 built-ins

### 統一 Expression engine

ATT 的 Native Flow、Action 和 Tool call 共用一個 expression engine：

- ${path} 讀取 Context 值，並可插入一般文字。
- #{expression} 評估型別化 expression，支援 Context operands、built-in calls、list literals、括號、一元運算、算術、比較、like、in、null 檢查及布林邏輯。

完整 expression 會保留回傳型別，例如 Number、Boolean、Map 或 List；expression 放在一般文字中會產生 String。請使用 canonical EXEC/META paths；optional lookup 在路徑尾端加問號。

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

依各欄位支援的形式使用 expression。File-content 內容、Action description/assert、Log message/value、assign/set expression 和 Tool call 使用一般 runtime model。Log value 可遞迴包含 typed expressions，詳見[Action 與型別化值](reference.zh/actions.md)。

### File-content expression

`&{path}` 是 file-content expression：它讀取 package resource 中一個靜態指定的 regular UTF-8 檔案，並回傳原始 String content。Bare name 從 package root 解析；明確 `./` 和 `../` locator 從引用 descriptor 的目錄解析。Resolver 會拒絕 absolute name、package escape，以及指向 package 外的 symlink。它不會推斷或解析文件格式、展開 glob 或建立 output file。Absolute path、missing file、directory、symlink escape、非 UTF-8 bytes、前後空白、glob syntax 及 dynamic locator 都會在 validation 失敗。File content 不會自動解析為 JSON、YAML 或 XML。

### Argument 結果型別

Argument expression 會先遞迴求值，再呼叫接收它的 function。Target argument contract 只套用在最終結果：例如 `str.substr` 先接收 `&{...}` 產生的 file String，再回傳 String，最後 outer target argument 才執行型別與 range 檢查。因此 `waitMs=#{str.substr(&{params/wait.txt}, 0, 4)}` 在結果為 integral value 時可供 numeric helper 使用；String payload 則保留所有原始字元。Scalar String 只會在數值或 Boolean parsing 時 trim，不會全域修剪 String/payload。Map/List 會保留型別；不會依副檔名或檔案內容推斷並解析結構。

Package validation 會在 inputs 靜態可知時解析 literal、static file content 及明確 pure built-in，再套用與 runtime 相同的 target argument contract。Runtime-dependent expression 會檢查結構及已宣告型別，並將最終值/range 檢查留待執行；validation 絕不執行 Tool、resource 或 external call 來取得值。

Standalone value 或嵌入較大 expression 時，請使用 YAML string：

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

檔案內的 `${...}` 與 `#{...}` 會在 file value 使用時編譯及求值。Run 與 Debug 會 cache compiled plan，並在 file fingerprint 改變時失效；Load 會為 scenario freeze 已驗證的 file identity、content 及 compiled plan。File output 不會再被當作新的 expression source 解析。

### Testdata input mapping 語法

Testdata reference 會在準備 Native Case、Debug 或 Load input map 時解析，不屬於一般 `${...}` / `#{...}` expression engine。使用 `@{id}` 保留所選 record 的原生型別；`@{id.object.path}` 或 `@{id.items[0]}` 可讀取巢狀值；scalar interpolation 可組合文字。同一 mapping/lifetime 中，一個 logical ID 只選一次，因此該 mapping 內對同 ID 的引用會得到同一筆 record。Interpolation 不接受 null、map 或 list。`${...}` 可讀取已初始化 Context，但不能讀取 `EXEC.INPUT`，因為 input 建構不能依賴自身。Reusable Flow 和 Tool definition 不可直接包含 testdata marker；它們只會使用已解析的 `EXEC.INPUT` 值。

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

只有作者直接撰寫的 file-expression node 才會求值。Context value、Tool result 和 file output 即使包含 `&{...}`，亦維持 literal String。檔案內的 Context path 和 call 依 enclosing Action 的一般 ordering、scope 和 resource validation 規則驗證。V1 在 Run、Debug、validation 和 Load dependency discovery 都拒絕 file-content 內容中的巢狀 `&{...}`，包括 `#{...}` argument 內的 locator。

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
| Load | 在 target Flow 的 `assign` Action 中：`expression: "#{seq.next('load-order', 10)}"` | Iterations 共用計數器；並行呼叫唯一，但不保證每個 VU 的固定分配順序。 |

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

Expression language 由本頁定義；可用 roots 與求值時機由欄位的 semantic owner 定義：[Tool command/call](reference.zh/resources/tools.md)、[Load identity 與 vars](reference.zh/execution-modes/load.md)、[Debug vars](reference.zh/execution-modes/debug.md)、[report filename](reference.zh/configuration.md)。`${path?}` 只允許缺少的 map/list path 回傳 null；語法錯誤與非法 scope 仍會失敗。Expression syntax 或缺少的必需 Context path 會提供結構化 diagnostic；見[Validation](reference.zh/validation-diagnostics.md)。

已移除 presentation-only `dbText`／`misc.dbText`、`prettyPrint`／`misc.prettyPrint`／`format.pretty` 和所有 local `file.*`／legacy file alias。DB result 保持 typed；顯示時改用 Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` 加 `format: sqlplus`，Map/List 則使用 `format: json` 或 `yaml`。Package content 使用 `&{...}`，並將 String 傳入 HTTP body、MQ payload 或 SSH upload payload。SSHHelper upload 只接受 content；native SSH download 已移除。ATT local output 由 framework 管理。移除的 API 會提供 migration diagnostic。

### Retry condition 的生命週期

`retry.when` 在當前 attempt 完成後、retryOn 符合且尚有 attempt 時才評估。`output.*` 綁定當前 result/evidence/diagnostic 及 `output.attempt`。Normal Boolean typing、strict/optional Context path 契約均適用。僅允許 deterministic pure built-in；external、file、sequence、random 及 current-time operation 禁止。詳見 [Actions retry](reference.zh/actions.md)。

## Execution mode 概覽

RUN 會執行所選 Case。Debug 會對單一 Flow 或 Tool execution 提供診斷。LOAD 透過 scheduler 重複執行 Flow 或 Tool workload。VALIDATE 會檢查所選 input，不會啟動 business execution。

| Mode | Input | Output 位置 | 是否啟動 business work |
|---|---|---|---|
| RUN | Definition 和所選 Resolved Case | `output/<runId>/` | 驗證後會 |
| Debug | Flow 或 Tool target 和明確 inputs | `output/debug/<debugId>/` | 會，限所選 target |
| LOAD | 已驗證的 Flow 或 Tool workload | `output/load/<runId>/` | Scheduler 啟動後會 |
| VALIDATE | Package 或所選 dependency closure | Validation report | 不會 |

各 mode 共用 Expression、Action 和 Resource contract。Selection 和 scheduler 會決定啟動哪些工作，不會另行定義 input binding 語義。較早撰寫模型請參閱[舊版文件](legacy/README.zh.md)。

### 統一 RUN 與排程

Native RUN 會執行 Test Case Definition 中所選的 Resolved Cases。每個 Case 會解析至 Flow 和 Data Source；開始執行前，ATT 會驗證所選的 dependency closure。新工作請從[快速入門](quick-start.zh.md)開始。以較早模型編寫的 Package 請參閱[舊版文件](legacy/README.zh.md)。

#### 驗證及執行 RUN

使用 ATT Studio 相同的已驗證 Server origin 和 context path。向 `POST /api/v1/drafts/run` 提交 Native Case draft：

~~~json
{
  "schemaVersion": "att-execution/v2",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {
    "type": "nativeCase",
    "definitionId": "payments.authorize",
    "selection": {"resolvedCaseIds": ["accepted"]}
  },
  "options": {"debug": false}
}
~~~

請將 package、environment、Definition 和 local Case ID 換成已登錄的值。檢閱安全預覽後，向 `POST /api/v1/jobs/run` 提交 `{"packageId":"payments","draftId":"R..."}`。Draft 綁定驗證身分及 Package revision，十分鐘後到期。Package 改變後必須重新驗證。Preview 和 validation 不會提交 Job。

Native Suite draft 使用 `target.type: nativeSuite`、非空 `definitionIds` 清單，以及一種 selection：`all:true`、`qualifiedCaseIds` 或 tag filters。Included tags 使用 any-match semantics；excluded tags 優先。Validation 最多解析並固定 10,000 個 Case ID。空 selection 或未知值會驗證失敗，不會擴大成全部 Cases。

未知欄位或不合法組合會驗證失敗。輸入無效回傳 HTTP 400、不可用 draft 回傳 404、revision 改變回傳 409、Job 或 draft 容量已滿回傳 429。Inspection 大小、時間及並行上限亦適用。

每個 Resolved Case 會產生一次 Case execution，各自有 execution ID、status 和 evidence。Server draft 會凍結所選 Case ID 和 Package source revision；source 改變後必須重新驗證。已接受的 Job 使用標準 status、取消、event、result 和 artifact routes。

#### 重跑失敗 Cases

Native Suite 的 `rerunFailed:true` 只會從同一 owner、Package、Definition IDs 和 Package revision 下，最近一個符合資格且已完成的 Native Suite RUN，選取已執行但失敗的 Cases。Server 按完成時間排列候選，不會只搜尋最近 100 個 Job。

本機執行會使用按 Package/Definition 區分並驗證 manifest hash 的 Native Suite pointer。Dry run 和不相關的 RUN 不會取代它。Server draft 會凍結來源 Job/run、manifest digest、revision、來源選取數、retry attempt number 和精確的失敗 Qualified Case ID。提交時會重新驗證來源；若驗證後有更新的相符 RUN 完成，便拒絕過期 draft。來源中的 Skipped Cases 不會重跑。新 RUN 只報告本次選取、執行和略過數；retry 次數與來源統計分開保存。Manifest 不可用或不一致、來源變更，或沒有已執行的失敗 Case 都會驗證失敗。

#### 啟用 Debug diagnostics

設定 `options.debug:true` 會保留額外診斷事件。這不會重複執行、增加並行度或權限，也不會啟用不安全的 failure details。Server 大小限制、披露政策、secret filtering 和 artifact controls 仍然適用。

#### 排程已驗證的定義

標準排程建立使用 `att-schedule/v2`。使用已驗證的 draft 建立 RUN schedule：

~~~json
{
  "schemaVersion": "att-schedule/v2",
  "name": "Nightly payment checks",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "draftId": "R...",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "maxConcurrentRuns": 1,
  "enabled": false
}
~~~

RUN schedule 會保留已驗證的 inputs、Case selection 和 source revision。Package、target、environment 或 configuration 改變後，必須重新驗證並建立新定義。受保護的 Package defaults 會使用綁定路徑的 keep marker。提交的敏感 overrides 不可保存至 schedule。

可使用五欄 `cron` 加 IANA timezone，或單次 `runAt` instant；不可同時提供。更新若提供任一 trigger，會取代已保存的 trigger；兩者皆未提供則保留現值。單次 trigger 只會被領取一次。春季 DST 不存在的本地時間會跳過；秋季重複時間可產生兩個 instants，但仍受最短間隔限制。啟用前可用 `POST /api/v1/schedules/preview` 預覽時間。

Scheduler 會先記錄 occurrence，再提交執行。每 schedule 及全域 overlap 上限已滿時會跳過該次；一般 Worker 和 Load admission 上限仍生效。延遲或重啟期間錯過的觸發不會補跑。提交失敗遵循 `server.scheduling.failurePolicy`，不會自動重試 execution。取消 Job 只會取消該 occurrence；暫停 schedule 會阻止未來觸發。Job 和 occurrence retention 使用已設定的 Server limits。

所有 active local `USER` 或符合 `server.authorization.userRole` 的 authenticated container principal 均可管理共用 schedule。`ADMIN` 僅有 system 和 account administration 權限。建立者會保留作為 audit，但不限制其他 active USER 查看、編輯、暫停、觸發或刪除 schedule。使用 local authentication 時，scheduled RUN 提交時建立者必須仍是 active USER。Container roles 會在每個管理請求檢查；建立者失去指定 role 時，operator 必須暫停 schedule，因背景 scheduler 無法查詢外部 realm。

#### Replay 或設定新執行

在 Jobs 或 Job Details 選擇 **Rerun with same settings** 或 **Configure & Rerun**。Server 會建立獲授權的 draft，並以目前 Package 和 Environment 驗證。Editor 只收到不透明的 `draftRef`，再向 Server 取得 sanitized definition；定義不會放入 URL 或 browser storage。

Rerun 會保留安全保存的參數，並在確認前顯示來源和目前 revisions。Strict source revision 選項在 revision 不符時拒絕執行，並讓使用者檢閱目前 Package。Configure & Rerun 會在一般 editor 預填支援且安全的設定，驗證變更後建立獨立 Job。Cancel 或 Back 不會修改來源 Job 或 Package。系統不會從 logs 或 artifacts 重建 secrets、transport data、host roots 或不安全路徑。

Draft reference 和 confirmation intent 綁定 principal，十分鐘後到期，且保存在 Server 記憶體中。Server restart 後必須從來源 Job 建立新 draft。Package 過期、draft 到期、Resource 移除、schema 無效或容量已滿均有不同回應。

[Run mode Reference](reference.zh/execution-modes/run.md) 說明本機 Native execution 和 output paths。[CLI Reference](reference.zh/cli.md) 定義 command syntax 和 exit code。

### Run 模式

Native RUN 會執行 Test Case Definition 中所選的 Resolved Case。Case 會解析至 Flow 和 Data Source；開始執行前，RUN 會驗證所選的 dependency closure。新工作請從[快速入門](quick-start.zh.md)開始。維護以較早模型編寫的 Package，請參閱[舊版文件](legacy/README.zh.md)。

#### 選擇 Case

可用 qualified ID 選擇一個 Case，或用 implicit Suite 按 Definition、ID 或 tag 選取 Cases。不需要 `suite.yaml`。Excluded tag 優先於 included tag。若 selection 不存在或為空，RUN 會拒絕執行，不會改用其他 selection。

[Unified RUN Reference](reference.zh/execution-modes/unified-run.md) 說明 Server draft 與 schedule lifecycle。[CLI Reference](reference.zh/cli.md) 定義 command 語法和 exit code。

#### 驗證及執行

Case 開始前，ATT 會驗證 Definition、已解析的 Data Source、Flow 和被引用的 Resource contract。Server draft 會凍結所選 Case ID 和 Package source revision；source 改變後必須重新驗證。Preview 和 validation 不會提交 Job。

每個 Resolved Case 會產生一次 Case execution，各自有 execution ID、status 和 evidence。RUN 會把結果寫入唯一 run directory，並只在完成後更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。

#### 結果和失敗狀態

Status aggregation 保留嚴重程度：ERROR > INVALID > FAIL > PASS > SKIPPED。Run 完成且沒有失敗時 process exit code 為 `0`；Case/assertion 失敗為 `1`；command/configuration/validation 無效為 `2`；runtime 或 infrastructure error 為 `3`。

在 Studio 中，**Configure & Rerun** 會依保留的 RUN definition 建立新的已驗證 draft。提交前請檢查 Package revision 和 selection。詳見[Unified RUN](reference.zh/execution-modes/unified-run.md)。

### Standalone Debug

Debug 使用明確 inputs 執行一個 Flow 或 Tool，並寫入獨立結果目錄，不需要 Test Case Definition。維護以較早模型編寫的 Package，請參閱[舊版文件](legacy/README.zh.md)。

```sh
./att.sh debug flow common.payment.v1 --input common.payment.debug.yaml
./att.sh debug tool sample.getAcDate --input sample-tool.debug.yaml --env UAT
```

#### Bootstrap inputs

使用 `att-debug/v1.2`。Flow Debug 接受 `inputs`、選用 `vars` 和選用 Testdata imports。Tool Debug 接受該 Tool 所宣告的 `arguments`。執行前會驗證這些值；framework-owned identity 和 output 欄位不能被覆蓋。

```yaml
schemaVersion: att-debug/v1.2
inputs:
  amount: 100
vars:
  reference: REF-001
```

讀取 `EXEC.INPUT` 的 Flow 需要 `inputs`；`vars` 會初始化可重用 Flow 的 `EXEC.VARS`。Testdata reference 使用與 RUN 和 LOAD 相同的 mapping rules。詳見[Testdata registry 和 input mapping](reference.zh/test-authoring.md)。

#### Discovery 和結果

不帶 target 執行 `./att.sh debug`，會列出靜態有效的 Flow 和 Tool。Discovery 會驗證所選 dependency closure，但不會執行 Resource。使用 `--format json` 可取得機器可讀輸出。

每次 invocation 會在 `output/debug/<debugId>/` 寫入 `case.log`、`result.yaml` 和保留的 Action artifact。Debug 不會更新一般 `latest-run.yaml` pointer。PASS 的 exit code 為 `0`；Action 失敗為 `1`；CLI/input/configuration 無效為 `2`；runtime error 為 `3`。

Resource call 在 Debug、RUN 和 LOAD 中使用相同的 Package-relative file-content expression。外部 I/O 前會解析被引用的檔案。若 Tool 可能連接外部系統，執行前請檢查 Environment 和 safe inputs。

### Load 模式

ATT 接受 `att-load/v1.9` scenario。每個 workload 使用一個 Flow 或 Tool target，或在 closed-user workload 中加權選取這兩類 target。維護以較早模型編寫的 Package，請參閱[舊版文件](legacy/README.zh.md)。Workload 設定 inputs、testdata policy、bootstrap vars 和 pacing。Root defaults 可供多個 workload 共用，workload-local 欄位則會覆寫 defaults。Scheduler 啟動前，ATT 會驗證並預先解析每個 target。

使用上一版 workload schema 的 descriptor 仍相容，載入時會 normalize 至現行 schema。

不帶 scenario 執行 `./att.sh load`，會發現 `load/` 下有效的完整 Load descriptor。只考慮宣告 `schemaVersion: att-load/*` 的 YAML；其他 YAML 會忽略，無效的已宣告 descriptor 則附 diagnostic 顯示。Discovery 會 resolve 並驗證 target，但不啟動 scheduler 或呼叫 resource。

#### LOAD 啟動前準備被引用的 resource

Target 驗證及 Load plan preparation 完成後，ATT 只會發現所選 Flow、主要及巢狀 Tool call，以及已凍結 Render payload expression 可到達的 DB、MQ、HTTP helper，並在 scheduler start gate 開啟前準備它們。Global configuration 中未使用的 helper 不會被啟動。無法靜態 resolve 的 reference 會標記為 `SKIPPED_DYNAMIC`，並在首次使用時維持 lazy preparation。

每個被引用 DB helper 會建立足夠的不同實體 connection，至少同時有 `max(1, minIdle)` 個可用 session。原有 `minIdle`、`maxSize` 設定不變，pool 後續正常擴充仍採 lazy 模式。MQ readiness 會為配置的實體 instance 建立 idle connection，但不會開啟 queue、傳送 message 或執行 business request。HTTP readiness 只建立 client 及 TLS 設定，不會連線或發出 request。

Preparation 每個 run 的 DB/MQ initial session 上限為 64 個，每個 Worker JVM 上限為 256 個，並受 deadline 及 cancellation 控制。ATT Server 每個 Server process 最多同時執行四個 LOAD Worker，因此由該 Server 提交的 LOAD 工作共用 256 個 initial session 的 aggregate 上限。Resource owner 完成 close 前，cleanup 會繼續計算其 reservation。此 scheduler 上限只涵蓋該 Server process 提交的 LOAD；RUN/Debug、CLI 或直接 Engine process，以及其他 Server instance 不在 admission budget 內，仍可能增加共用 backend 的 session。失敗會在任何 iteration admitted 前停止 run。Live output 和 structured event 會顯示各 helper readiness；run summary 會記錄準備結果。此 barrier 只適用 LOAD，也不會取代已配置的 warm-up traffic。

#### 獨立 evidence 配額

`att-load/v1.9` 支援共用 run-level quota，以及選用的 per-workload quota。有限 evidence 會按固定 run-time window 保留，因此實際保留集合由配置上限和各 window 共同決定。

可選的 `evidence.quotaScope: perWorkload` 會啟用獨立模型。此為明確 opt-in，既有 scenario 不會被靜默重新解釋：

~~~yaml
evidence:
  quotaScope: perWorkload
  success: sample
  failure: full
  sampleRate: 0.1
  defaults:
    maxSuccessSamples: 10
    maxFailureSamples: 20
workloads:
  - id: payment-api
    target: {type: flow, id: common.payment.v1}
    load: {users: 10, duration: 2m}
  - id: mq-processing
    target: {type: tool, id: sample.getAcDate}
    load: {users: 5, duration: 2m}
    evidence:
      maxSuccessSamples: 5
      maxFailureSamples: 30
~~~

在此模式下，root `success`、`failure`、`sampleRate` 與 `resources` 仍是共用的 capture policy。`evidence.defaults` 提供每個 workload 預設的 `maxSuccessSamples` 與 `maxFailureSamples`；未設定的 default 為 **1,000**。`workloads[].evidence` 可覆寫任一 cap，且只接受這兩個 cap 欄位；只覆寫一項時，另一項繼承 default。Cap 為 0–1,000,000 的整數；0 表示該 workload 的該類別不保留 evidence。上例中，`payment-api` 為 10 success／20 failure，`mq-processing` 為 5 success／30 failure。

Per-workload 模式不可與 root `maxSamples`、`maxSuccessSamples` 或 `maxFailureSamples` 混用；`evidence.defaults` 或任何 workload cap override 都必須配合 `quotaScope: perWorkload`。執行前，ATT 會驗證所有 effective success caps 的總和及所有 effective failure caps 的總和；任一總和不得超過 aggregate safety budget 1,000,000。這是 preflight allocation 上限，不是 runtime global allocator。

每個 workload 各自擁有 cap。任一 workload 達到 success 或 failure cap，都不會預留其他 workload 的容量；未使用容量就是維持未使用。沒有 lending、borrowing、repayment、reclaiming、revocation 或由配額驅動的 artifact 刪除。只要 workload 自己的 cap 仍有空間，其第一個合格 success 或 failure 就會被接受，即使其他 workload 已全部額滿。保留的 evidence 在整個 run 內只增不減。取消、拒絕、PASS-to-FAIL 重新分類、dropped／未開始 arrival 或 evidence 缺失時，會釋放預留。Success 與 failure 容量互相獨立，一類用盡不會抑制另一類。

`sampleRate` 只用於抽樣 PASS；FAIL/ERROR（包括操作層 TIMEOUT）直接使用 failure cap，不經抽樣。`failure: full` 受 effective failure cap 限制，不保證每個失敗都會保留。Failure log 維持 65,536 字元 tail、強制 secret protection 及延後 resource formatting。

抽樣 PASS 的資格由 MurmurHash3 x86 32-bit（seed 0）計算，輸入是以 UTF-8 編碼的 iteration ID。ATT 將結果視為 unsigned 32-bit 整數，低於 `floor(sampleRate × 2^32)` 才合格；resolved policy 會記錄 `murmur3-32-v1`。Hash 合格不代表一定保留：仍須有可用配額名額及 evidence artifact。

有上限的 evidence 名額會按 planned run duration 分配到最多十個等長 window；各 outcome cap 和 workload cap 分別分配。名額少於十個時，每個名額使用一個 window。名額只會新增，不會替換、撤回、借用空 window 容量或刪除已保留 artifact。每個 window 內仍採先到先得，因此 cap 較小、某個 window 沒有合格候選，或候選速率不均時，代表性仍可能受限。無上限保留及沒有 scheduler timeline 的直接 store 呼叫維持原有行為。

`load-summary.json`／YAML、HTML report 及 Studio job result 會顯示 resolved sampling hash 和 retention strategy、完成的 success、hash 合格數、sampling-ineligible success、已保留 evidence、success/failure 配額拒絕、無法判定 outcome 的 reservation，以及缺失 artifact。抽樣模式下，completed success = hash 合格 + sampling-ineligible。若 hash 合格的 success 之後變成 failure，quota denial 會按最後 outcome 只計一次；missing artifact 只計已預留但完成後缺少 artifact 的 outcome。這些欄位位於不同階段，不能當成可相加的 retention funnel。Workload row 亦會顯示 target ID 及有效 per-workload cap。Report 會顯示各 time window 和 phase 的保留情況；combined cap 或類別配額拒絕候選時會發出警告，aggregate metrics、thresholds 和 failure events 繼續完整統計。保留連結仍使用 `samples/`、`failures/`，並保留 EXEC.ID、case.log、case.yaml。Studio 會分開顯示 shared caps 與明確 per-workload scope、effective defaults 及 workload overrides，並與 advanced JSON 同步。見[per-workload 範例](../examples/load/independent-evidence.yaml)。


#### 定義 Load scenario

~~~yaml
schemaVersion: att-load/v1.9
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: flow, id: common.payment.v1}
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
      rampUp: 5s
      warmup: 10s
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
~~~

#### 將 closed-user work 分配到多個 target

Workload 可用 `mix` 代替 `target`，讓每位 closed user 的下一次 iteration 在預先 resolve 的 targets 之間選擇。每個 entry 包含唯一 `id`、正整數 `weight`，以及 Flow 或 Tool `target`。Selector 由 run seed、workload ID、穩定 VU ID 和該 VU 的 iteration number 決定；切換 target 不會重設 VU 或 think-time random stream。Weight 表示選擇機率，短時間 run 不保證精確符合比例。

~~~yaml
schemaVersion: att-load/v1.9
seed: 73
workloads:
  - id: checkout
    inputs: {region: HK}
    mix:
      - id: browse
        weight: 60
        target: {type: flow, id: common.payment.v1}
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

Target 支援 flow 或 tool；Tool target 可有 named arguments，但不能宣告 bootstrap vars。Workload inputs 會成為每個 iteration 的 EXEC.INPUT；Flow 的 workload vars 則在每個開始的 iteration 建立全新的初始 EXEC.VARS tree。同一 scenario 的 workloads 必須使用相同 model（closed users 或 arrivalRate）與相同 warmup/rampUp/duration/rampDown 時間窗口。它們是獨立 pacing 的固定 target，不是 transaction mix。

| Workload 欄位 | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input；不作為 bootstrap variables |
| `vars` | initial `EXEC.VARS` | Flow typed expression tree；每個 execution 獨立評估 |
| `target.arguments` | Tool arguments | 僅供 Tool call；與 `EXEC.INPUT`、`EXEC.VARS` 分開 |

ATT 會在每個 iteration boundary 將 inputs map snapshot 一次，形成深層 immutable tree。複製 request metadata 時會重用這個 snapshot；Load adapter 會將巢狀 value 直接傳入該 iteration 的 `EXEC.INPUT` map，不再複製巢狀內容。開始 iteration 後，caller 修改來源 map 不會影響該 iteration；不同 iteration 也不會共用 input snapshot。

#### 映射 inputs 並選擇 testdata

Environment profile 提供共享的 `testdata` descriptor list。Scenario 可選擇在頂層宣告 package-relative YAML `testdata` imports，形成僅供該次 Load 使用的 overlay。同 ID 的 Load-local descriptor 會完整取代 environment descriptor，不會合併 records 或 selection 設定。任一 layer 內的重複 ID 都會使 validation 失敗。

使用 `inputs` 將 `@{id}`、`@{id.path}` 或 scalar interpolation 映射到 `EXEC.INPUT`。同一 mapping 內每個 ID 只選一次 record；`scope` 決定何時重新選擇：`workload`、`user` 或 `iteration`。Load 預設為 `iteration`。`user` 只適用 closed-VU workload，在 `arrivalRate` 無效。Workload `testdata` map 只存 policy，不負責匯入 descriptor。其 `selection` 若有設定，會整份取代 descriptor selection policy。Policy 支援 `sequential`、`roundRobin`、有 seed 的 `random`，以及 exhaustion `error`（預設）、`recycle` 或 `stop`。耗盡後 `stop` 會平順停止該 workload。單筆 descriptor 不需要 selection policy。

Selection evidence 只記錄 testdata ID、來源 layer、record index、適用時的 generated sequence、scope、strategy 與 random seed，不包含 record 內容。Run/Debug 只載入 mapping 有引用的 ID；Load 會在 scheduler 啟動前驗證被引用的 ID 及 workload policy。

#### 初始化 Flow variables

Scheduler identity 及唯一 EXEC.ID/EXEC.OUTPUT_DIR 初始化完成後，ATT 會在 Flow 開始前評估 workload 的 vars tree。完整 `${...}` reference 保留原生型別，混合文字會轉成字串，`#{...}` 使用一般 typed expression parser，巢狀 map/list 會遞迴評估。Vars 之間的依賴不受宣告順序影響；缺少變數或循環會在 target 開始前失敗。每個 iteration 都有獨立 map/list，因此併發 user/workload 不會共用可變值。第一次一般 `assign` 可取代 bootstrap variable；成功的 `set` 會更新該值並消耗首次 assign 許可。各 iteration 的 set 不會影響其他 iteration。

Bootstrap expression 可使用已初始化的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他 `EXEC.VARS.<name>`，以及穩定的 project/source/target metadata。`EXEC.ACTIONS`、action-local `output`、invocation-scoped metadata，以及 Tool/DB/MQ/HTTP/SSH/process/filesystem 或 stateful calls 不可用。只允許安全的純 built-in。Tool arguments 與 vars 是不同 contract。

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

DB workload 可用相同估算，將 DB operations/second 乘以 connection lease 平均時間（connection 借出後仍被使用的時間）。例如每秒 40 次 operation、平均 lease 100 ms，約需四條 concurrent connection。每個 DB helper 的 `pool.maxSize` 可按此 concurrency 加上 headroom 設定，再查看其 `resources.db` active/idle/waiting、borrow timeout 及 total borrow wait metrics。每個 helper 預設 maximum 為 20；這是 client-side 上限，並非建議的目標 database 容量。

MQ request/reply 若每秒 10 個 request、平均 reply time 為 3 秒，整個 workload 約需 30 條 leased connection。`pool.maxSize` 應按每個**實體 MQ instance** 的預期 in-flight request 數 sizing，而非按 logical helper。單一 instance（或 request 固定送往一個 instance）約需 30 條再加 headroom。兩個平均分配的 instance 平均各需約 15 條；亦要考慮 selection skew 並確認實際分佈。`resources.mq` 按實體 instance（`logicalId::instanceId`）顯示 PRE_TEARDOWN pool metrics，請逐一查看 waiting、timeout 及 response latency。Disconnect 確認是另一份 `resources.postTeardown` 樣本；見 [閒置 MQ 連線與 teardown](reference.zh/resources/mqhelper.md#閒置-mq-連線與-teardown)。`minIdle` 會在該實體 pool 首次被使用並建立時套用，不會在 Load 開始前建立或預熱 pool。若要避免 connection creation 影響 measured steady state，請配合 Load warm-up / first-use warm-up phase 使用 `minIdle`。負載變化時，請依觀察到的平均 latency 重新估算；tail latency 可用於 headroom 規劃，但不是公式中的平均值。

duration 必填。rampUp、warmup、rampDown 預設為零。執行順序為 Ramp-up → Warm-up → Steady → Ramp-down，然後進入 DRAIN，等待已 admitted work 完成而不再排程新 arrival：Ramp-up 由零增加至設定目標，Warm-up 維持完整目標，Steady 結束後 Ramp-down 逐步降低流量。Ramp-up 與 Warm-up 都會送出真實 traffic，但不納入 measured metrics 及 threshold denominator；measured load 為 Steady + Ramp-down。例如 100/s 目標、Ramp-up 60s、Warm-up 60s、Steady 180s、Ramp-down 60s，分別排定 3,000 + 6,000 + 18,000 + 3,000 = 30,000 次 arrival。既有儲存描述會保留原值與歷史；重新執行時採用新順序，不會重現舊 phase timing。可選 seed 使 closed-VU think-time randomization 可重複。

#### 識別 iterations 並查看其 artifacts

每個 started iteration 會在 bootstrap vars 與 Target 執行前取得 engine-managed `EXEC.ID`；`EXEC.RUN_ID` 維持外層 Run。較早的 ID 初始化說明見[舊版文件](legacy/README.zh.md)。

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

`evidence.mode` 支援 `metrics`、`failures`、`samples`、`all`；預設為 `failures`。這些 mode 分別將 effective success/failure policy 預設為 `none/none`、`none/full`、`sample/full`、`full/full`。`evidence.success` 與 `evidence.failure` 可各自覆寫預設。`sampleRate` 決定 hash 資格；配額 window 另行決定實際保留項目。Dropped arrival 不建立 iteration evidence。有上限的選取使用最多十個 append-only time window，並在每個 window 內先到先得；抽樣和代表性限制見[獨立 evidence 配額](#獨立-evidence-配額)。

Case-log capture 依 effective success/failure policy 及剩餘 retention capacity 決定。Failure policy 為 `full` 且仍有 `maxSamples` slot 時，符合條件的 failure（包括 `samples` 中未被抽中的 success）會保留經 redaction 的 rolling log tail，上限為 65,536 個字符。ATT 只會在 failure 取得 retention slot 後 materialize failure log。Tail 被截斷時會加上 marker 標示較早事件已省略，並在末尾保留最新的 action 和 runtime failure details。容量耗盡後不再保留 failure log。Sampled success 和 full-success policy 會保留完整的 deferred log。例如 `mode: metrics, failure: full` 會在容量允許時進行有界 failure capture；`mode: failures, failure: none` 則會停用此功能。Implementation 和 storage 細節見 [Load scheduler design](system-design/load-scheduler.zh.md)。

evidence.resources.output 支援 inherit（預設）或 none。none 停用可選的人類可讀 resource-output 格式化與物化，但保留 typed result、stdoutFormat/responseFormat parsing、exact file-content String 與 requestFormat 行為。Load 將 resource output 延至 iteration 被保留後才處理；metrics-only iteration 不做 business-output formatting 或 evidence file I/O。

#### 查看 Load 結果並設定 thresholds

ATT 在 run root 寫入有界 load-summary.json/yaml 與 self-contained report/index.html。Report 對 retained execution 顯示 EXEC.ID、workload/target identity、status、timing；有保留 case.log 時提供 link。Aggregate latency percentile 由 aggregate latency collector 計算，不會平均 workload percentile。

直接 Engine／CLI `OperationResult` 輸出仍保留舊版完整 summary 詳細格式；其 schema 定義仍存於 `schemas/history/`。`LoadReportWriter` 輸出的 archived report 使用新增欄位的 `att-load-summary/v1.3`，並將大型 aggregate／per-workload metric collection 移至相對路徑 `details/v1/` detail file，由 `details/v1/manifest.json` 引用。Inline collection 最多顯示 32 項 preview；每個 detail file chunk 最多 4 MiB，detail file 集合上限為 100 MiB／9,000 個檔案，完整 report output 上限為 120 MiB，每個主要 report 檔案最多 16 MiB。Aggregate counters 和 thresholds 維持精確。Server 代理 Job Results 則在 `result.summary` 使用 `att-load-terminal-result/v1`：執行結果、performance verdict、有界 aggregate counters、workload／threshold 預覽、主要診斷及 detail/archive 狀態。精簡 summary 上限為 32 KiB；量度終端 journal 和 SSE envelope 後均低於 48 KiB，並低於不變的 64 KiB journal event-data 上限。Worker frame 的 1 MiB 上限也維持不變。LOAD scheduling、virtual-user 和 arrival-rate 語義不變。

長時間 scenario 會由 Engine 執行 read-only preflight 並在 summary 記錄 `resources.preflight`；此提示不會只因 duration 過長而拒絕 run。Browser inline draft 及 package-backed scenario 均受 Server 設定的完整 envelope 上限 (`server.inlineLoad.maxDurationSeconds`，最多 10 日) 限制。選擇性 CLI Local 手動 benchmark 見 [25 小時 LOAD soak acceptance 指南](operations/load-soak-acceptance.zh.md)。

完整 buckets、failure samples、workload／threshold 細節及 evidence 仍保存在 `load-summary.json`、`load-summary.yaml`、`report/index.html` 及版本化 detail file，Server 只透過現有授權 artifact archive routes 提供。Studio 先顯示精簡結果；開啟 Results 才載入 evidence/workload preview，開啟 Metrics 才載入 metric-series detail file。初始 Job Details 不會請求 artifact。Archive availability 與執行結果獨立；`PENDING`、`ARCHIVING`、`READY`、`FAILED` 或詳細資料 omitted 均不會改變終端狀態或 exit code。ERROR 和 INVALID 會優先保留真實 runtime/preparation/validation 原因，而非 Worker／Journal truncation 或 archive 診斷。

Load v1.9 新增 `all`、`measured`、`steady` 三個 event-time window。`measured` 從 ramp-up 及 warm-up 結束後開始，只包含 steady 及 ramp-down；`steady` 只涵蓋配置的穩態 duration。HTML report 預設顯示 `steady`，並可切換其他 window。Scheduled、started、completed rate 依各 event timestamp 及配置 window duration 計算，因此 `startedRate` 仍按 event time 計算。`admissionRate` 則使用相同的 scheduled cohort：分子是該 window 內排程且在 run 結束前已開始的 arrival，分母是該 window 內全部排程 arrival；run 結束仍未開始的 arrival 視為未准入。Latency percentile 和 outcome count 依 scheduled cohort 計算，因此 drain 完成的 iteration 仍屬於其 scheduled phase。既有 top-level metric 名稱及 legacy 語義仍保留。

巢狀 `thresholds.steady` 和 `thresholds.overall` 分別對 steady 與 all-execution window 評估。Top-level measured threshold 使用修正後的 measured interval，即 `STEADY + RAMP_DOWN`；ramp-up 和 warm-up 不計入。Window 不完整或無法評估時回報 `NOT_EVALUATED`，不視為 performance pass。Report 會把 execution status 與 performance verdict 分開，並顯示 percentile sample／observation count，讓稀疏估算可被辨識。直接 Engine／CLI summary 使用 existing Engine/CLI summary contract；archived report 使用新增 `detailManifestPath` 和 workload detail file references 的 `att-load-summary/v1.3`。

##### SUT threshold acceptance

LOAD scenario 可針對已登記的 SUT Metrics profile 設定 `observability.thresholds`。目前規則支援在實際 UTC `STEADY` interval 內計算 sampled `max <= value`，並套用至所有已綁定 target。每個 source target 都會獨立評估。數值等於上限時為 `MET`；任何有效 sample 超標時，即使 coverage 不完整仍為 `NOT_MET`。缺少、過舊、部分或覆蓋不足的 samples 會回報 `NOT_EVALUATED`，除非有效超標已能證明 `NOT_MET`。最低 coverage 預設為 95%。

Server 會將正規化規則固定在 Job 的 SUT snapshot，並把 post-run 結果寫入獨立版本化 `att-sut-threshold-evaluation/v1` record。`GET /api/v1/jobs/{id}/result` 可在授權後加入 `performanceAcceptance` object，包含 `executionStatus`、`nativeLoadThresholds`、`sutThresholds`、`externalEvaluationStatus` 及 combined `performanceVerdict`。每個 target 的 evidence 會列出實際 UTC STEADY window、sampled peak 和時間、resolution、coverage，以及固定的 source/profile/target revision。Worker terminal result 和 native thresholds 保持不變。External evaluation pending 時，combined verdict 會保持 pending；已知的 native 或 SUT threshold failure 則維持 `NOT_MET`。

Scenario authoring、完整 rule 欄位、coverage 條件及 Studio evidence 見 [Studio Load testing](interfaces/studio/load-testing.zh.md#sut-metrics)。

##### 單次呼叫業務指標

Base profile 可使用已配置的 `call` 定義業務指標，代替 PromQL。LOAD drain 後，ATT 會透過所選 package 現有的 DBHelper 或 Package Tool configuration，對每個 call group 執行一次。預設為 Job scope；單一 Workload binding 必須指定完全相符的 `workloadId`；以列對照的輸出則使用 LOAD scenario 中不可變的 Workload 和 mix identity。ATT 不會 fan-out 呼叫、輪詢、重試，或從 target ID 推斷 identity。DBHelper call 使用既有 helper configuration、bind parameters 和 connection limits。由於 ATT 無法判斷 Package Tool code 是否有 side effects，使用 Package Tool 必須設定 `sideEffectsAcknowledged: true`。

~~~json
{
  "schemaVersion": "att-sut-metrics-profile/v1",
  "id": "active-sessions",
  "kind": "base",
  "category": "db",
  "metrics": [
    {
      "id": "active",
      "title": "Active sessions",
      "unit": "count",
      "kind": "gauge",
      "call": "#{db.reporting.scalar(sql='select count(*) from active_sessions')}",
      "sampling": "afterDrain"
    }
  ]
}
~~~

如一次 query 或 Package Tool invocation 回傳多個數值欄位或資料列，請使用 `outputs`。每個 output 分別設定 metric ID、title、result column、unit 和 kind。以下設定以一次 DBHelper query，將兩個固定欄位對應至不同 counter metric：

~~~json
{
  "schemaVersion": "att-sut-metrics-profile/v1",
  "id": "transaction-summary",
  "kind": "base",
  "category": "db",
  "metrics": [{
    "id": "transaction-query",
    "call": "#{db.reporting.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}",
    "sampling": "afterDrain",
    "mapping": {
      "rows": "rows",
      "workloadColumn": "workload_id",
      "mixColumn": "mix_id"
    },
    "outputs": [
      {"id": "successful_transactions", "title": "Successful transactions", "column": "success", "unit": "transactions", "kind": "counter"},
      {"id": "failed_transactions", "title": "Failed transactions", "column": "failure", "unit": "transactions", "kind": "counter"}
    ]
  }]
}
~~~

Result path 預設為 `rows`，也可指定 `payload.rows` 等有界 object/list path。若沒有 `workloadColumn`，Job-wide binding 必須恰好回傳一列。設定 `workloadColumn` 但不設定 `mixColumn` 時，每列都會對應至 Workload scope，mixed Workload 亦然。若 query 回傳跨 mix slot 的 aggregate，必須由 query 或 Tool 明確計算；ATT 會保留 Workload scope，不會將數值複製或歸屬至個別 slot。每個 Workload 最多只能有一列。設定 `mixColumn` 可用 `workloadId` 和 `mixId` 配對 mix slot，並且必須同時設定 `workloadColumn`。缺少或未知 mix ID 的 row 會保持未歸屬並記錄於 mapping diagnostics；其他已明確對應的 slot 仍可用。ATT 不會以 target ID、動態 pivot、模糊 join 或隱式 aggregation 對照資料列。欄位名稱會區分大小寫，mapping 必須與 query 或 Tool 回傳的欄名完全一致。

每個 call group 最多可有 64 個 output 及 4,096 列 result。每個 Job 最多執行 16 次實際呼叫，每次最多 60 秒，所有呼叫合計最多 120 秒。回傳列只保存在記憶體；detail file 和報告會保存各個數值 output 及 mapping diagnostics，不保存 raw rows。無效欄位只影響該 metric 和 subject。Call 失敗或 timeout 會令同一 group 的所有 outputs unavailable。重複、未知、缺少或有歧義的 rows 會保留為 diagnostics；每個預期的 Workload/mix output 仍會出現在結果中。Partial 或 unavailable value 不會替換為零。

設定 `sampling: windowQuery` 和 `window: steady`，即可在 drain 後執行一次 call，並透過 `EXEC.OBSERVATION.WINDOW_START` 和 `EXEC.OBSERVATION.WINDOW_END` 讀取實際 STEADY bounds。Aggregation 由 query author 定義；成功回傳數值不代表已完整覆蓋該 interval。單一輸出的 map 或 list result 必須使用明確 `valuePath`，例如 `rows[0].active`；解析後必須是單一有限數字。Server 會在 Job admission 時固定 Package 和 Runtime Config revision，但不會保留 executable source 供延遲執行；若任一 revision 在 collection 前改變，detail file 會記為 `NOT_EVALUATED`，Job diagnostics 會回報 `BUSINESS_CALL_PACKAGE_CHANGED` 或 `BUSINESS_CALL_CONFIG_CHANGED`。ATT 不會靜默使用已改動的檔案執行 call。Calls 在有界的 Server worker pool 中執行，不會佔用 LOAD worker。After-drain value 只有在 LOAD 完成後兩分鐘內收集才會視為 fresh；此上限不受 Prometheus after buffer 影響，較遲的 value 會標示 stale。Mixed Job 的 after-drain call 會在 LOAD 完成後儘快執行。已驗證的 business detail file 會在 combined manifest 仍為 `PENDING` 時，以 provisional 唯讀證據提供，並標示 `business: COMPLETE`、`prometheus: PENDING` 及 Prometheus `eligibleAt`。Threshold 和 combined performance verdict 會等到完整 collection 終結；buffer 期間的 polling 會依 deadline 逐步退避。Server 重啟後，會恢復尚未完成的 Prometheus phase，不會重複執行已完成的 Business phase。若重啟時 Business call 尚未完成，系統會以 `SERVER_RESTART_INTERRUPTED` fail closed，不會重試可能有 side effect 的 call。Call 失敗、timeout、過期或結果非數字會評估為 `NOT_EVALUATED`；不會代入零值，也不會改變 Worker result。Binding validation 和 live preview 都不會執行 business call。

Business metric（包括 numeric counter）支援 `max <= value` threshold。`afterDrain` metric 使用 `window: afterDrain`；`windowQuery` metric 使用 `window: steady`。這些單值規則不使用 `minimumCoverage`。省略 `subjects` 或設定 `all` 會評估所有預期 subject。若要指定 Workload 或 mix，請將 `subjects` 設為明確清單；mix selector 必須同時包含不可變 ID：

~~~yaml
thresholds:
  - id: mobile-success-limit
    profile: transaction-summary
    metric: successful_transactions
    max: 1000
    unit: transactions
    subjects:
      - scope: mix
        workloadId: checkout
        mixId: mobile
~~~

每個 selector 必須恰好解析至一個已固定的 Job、Workload 或 Workload/mix metric identity。同一 metric ID 在多個 Workload 中會保留為不同 threshold result。缺少、過期、partial 或有歧義的 mapping 會得到 `NOT_EVALUATED`，除非另一個有效的已選 subject 證明 `NOT_MET`。

Summary 的 `thresholds` object 中，`status` 和 `performanceVerdict` 是 authoritative tri-state 欄位，值為 `MET`、`NOT_MET` 或 `NOT_EVALUATED`。Optional `legacyPassed` boolean 維持舊有 `passed()` 語義：已配置但無法評估的 threshold 為 false，未配置 threshold 時為 true。Consumer 應使用 tri-state 欄位，區分 evidence 不完整與 threshold 未達標。

Summary 會將 generator observation 與 SUT outcome 分開。`metrics.generator` 包含 sampled heap used/committed/maximum、觀察到的 peak live threads、GC count/time，以及 JVM 支援時的 process CPU。Sampling 限制為每 100 ms 至多一次，較短暫的 peak 可能錯過。`schedulerWakeups`、`submitLag*` 與 `workerQueueDepth*` 描述 scheduler pressure；arrival drops 與 SUT error 分開。`resources.http` 會按 HTTP helper 報告 active/idle/waiting 與觀察到的 peak connections，並與 DB、MQ、Render pool/plan diagnostics 並列；`resources.resourceMetricSamples` 顯示 rate-limited resource observations 的數量。`resources.executionIds` 顯示 custom execution-ID reservation 數量。Testdata mapping/selection counts 位於 `resources.generator.testdata`；解讀方式和 implementation limits 見 [Load Generator Telemetry](system-design/load-telemetry.zh.md)。

`latencySampleCapacity`、`latencySampleCount`、`latencyObservationCount` 及 `latencySampleRate` 描述 run-level percentile estimate；精確 latency aggregates 仍保持精確。Run-level reservoir 最多保留 4,096 個 sample。Legacy `metrics.phases.*` 每個 phase 最多保留 256 個 percentile sample；每個 configured window 的 `metrics.windowMetrics.windows.*.cohort.latency` 則使用獨立的 4,096-sample reservoir。`metrics.windowMetrics.timeSeries.points` 的每個 chart bin 最多保留 128 個 latency sample。

`metrics.windowMetrics.timeSeries.points` 全執行序列最多保留 2,048 個有資料的 time bin，初始寬度為 1,000 ms。資料 bin 超過容量時，ATT 會合併相鄰 bin，並以 power-of-two coarsening 將 bucket 寬度加倍。系統不會淘汰歷史 bin，因此較長的 run 會以較低的時間解析度保留歷史資料。`metrics.windowMetrics.timeSeries.windowSeries.measured.points` 和 `metrics.windowMetrics.timeSeries.windowSeries.steady.points` 是各自獨立的序列，各自最多 2,048 個 bin。它們按 configured window 起點對齊，初始寬度為 1,000 ms 與 window duration 兩者中較小者，並會在 window boundary 截短最後一個 partial bucket；它們使用相同的 coarsening 和不淘汰歷史資料政策。Compatibility `metrics.buckets` 是獨立的 legacy series：使用 circular buffer 保留最新 4,096 個一秒 bucket，並淘汰較舊項目。ATT 不會平均 workload percentile 來計算整體 percentile。

Load telemetry 欄位在舊版直接 summary contract（schema under `schemas/history/`）下仍屬 optional；archived report 使用 `att-load-summary/v1.3` 及 detail file references。Server Job Results 使用另一個版本化契約 `att-load-terminal-result/v1`；需要完整 metrics 的 consumer 應在 archive 顯示 READY 後跟隨 `detailManifestPath` 及相對 chunk references。

Sampling limits、metrics 解讀方式和 maintainer verification 請看 [Load Generator Telemetry](system-design/load-telemetry.zh.md) 及[精簡 LOAD 終端結果](system-design/load-terminal-result.zh.md)。執行確定性的短時間對照與模擬長時間 transfer benchmark：`mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest#benchmarkShortAndSyntheticLongMultiWorkloadTransferSizes -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test`。該測試會建構等同 30–60 分鐘的多 workload 結果，不會真的執行長時間 LOAD。

Root thresholds 只套用於 aggregate run；workload thresholds 只套用於個別 workload，不會從 root 繼承。Threshold 失敗回傳 FAIL/exit 1。設定或 target 無效回傳 exit 2；runtime/infrastructure error 回傳 ERROR/exit 3。Generator drop 不屬於 SUT error。

#### 取消 Load run

取消時，ATT 會停止新的 admission，並 interrupt 已 admission 的 iterations。已 admission 工作的 completion events 會先 drain 並計入 outcomes，之後才 finalize workload results 和 metrics。

#### 了解 Load 執行期間的 payload 變化

Load workload 啟動前，ATT 會為每個可達的 Render payload glob 解析一次，並凍結該 run 匹配到的 UTF-8 source content。Run 進行中對 payload 的編輯、替換或新增 glob match 不影響該 run 的 iterations；下一次 Load run 會重新解析 package。一般 Run 與 Debug 每次 execution 使用新 plan，因此下一次 execution 會讀取編輯後的內容。

每個 iteration 都會以自己的 Context 評估 Context reference、built-in call 及 external call。`seq.next()`、clock/random function 及 external call 等 stateful call 會在每次 iteration 執行。Render 在記憶體回傳 String，因此將 `ACTIONS.<id>.output.result` 傳給下游 action 不會建立中間 Render file。只有 operation 明確需要檔案時才使用 `EXEC.OUTPUT_DIR`。

使用 `--profile` 時，`performance.json` 會記錄 `renderPlansCompiled`、`renderPlanCacheHits`、`renderPayloadResolutions`、`renderPayloadResolutionCacheHits`、`renderEvaluations`、`renderArtifactWrites` 與 `renderSourceBytes`。Load 如何準備 plans 並在 iterations 間重用 immutable source data，請看 [Load scheduler design](system-design/load-scheduler.zh.md)。

使用 `--profile` 時，`performance.json` 的 `resources.execution` 會包含 `executionPlansCompiled`、`actionPlansCompiled` 與 `actionEvaluations`。Plan 和 resolver reuse 的 implementation 見 [Load scheduler design](system-design/load-scheduler.zh.md)。

#### 從 CLI 覆蓋 workload 設定

單一 workload 可用 --users、--arrival-rate、--ramp-up、--warmup、--duration、--ramp-down、--think-time、--max-concurrent 等 option 覆蓋對應 YAML。多 workload 使用未指定 workload 的 load-model override 會失敗。

重複的 `--set` 可用 `input.path=value`、僅限 Tool 的 `arg.name=value`，或僅限 Flow 的 `vars.path=value`。值使用 safe YAML 解析並保留型別；實用時支援巢狀 map 與數字 list index，例如 `input.customer.ids[0]=42`。重複賦值依序套用，最後一個值生效；解析 override 時不會評估 ATT expression。多 workload scenario 會拒絕未限定的 override。

可選的 `load/load.yaml` 使用現行 `att-load/v1.9` policy-only descriptor，不含 target、inputs 或 Tool arguments。它提供預設 `load` policy，並可選擇包含 `execution`、`thresholds`、`evidence`、`seed` 及 Load-local `testdata` imports。明確 CLI pacing 會覆蓋 policy。`load --debug flow|tool <id>` 會將目標 input 設定中的 `inputs`、`vars` 或 Tool `arguments` promotion 成暫時的單一 workload scenario，然後使用正常 Load validation、scheduler 和 evidence pipeline；不會先執行 Debug。沒有 policy 時，請在 CLI 提供完整 policy，例如 `--users 2 --duration 10s`（arrival-rate 還需要 `--max-concurrent` 和 `--overload-policy`）。

Policy descriptor 範例（複製到 `load/load.yaml`）：

~~~yaml
schemaVersion: att-load/v1.9
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load ../../examples/load/closed-smoke.yaml
./att.sh load ../../examples/load/arrival-smoke.yaml --format json
./att.sh load ../../examples/load/multi-closed.yaml
./att.sh load ../../examples/load/multi-arrival.yaml
./att.sh debug flow common.payment.v1 --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

可複製範例與欄位說明見 [examples/load/README.md](../examples/load/README.md)；migration details 見[Legacy migration reference](legacy/migration-reference.zh.md)。

較早 Load 欄位的遷移說明見[舊版文件](legacy/README.zh.md)。

現行 `att-load/v1.9` contract 拒絕 `execution.execIdFormat`；較早配置的完整遷移說明見[Legacy migration reference](legacy/migration-reference.zh.md)。

#### 錯誤率與失敗診斷（4.2.4）

測量階段完成項目分為 PASS、SUT 失敗及執行期 ERROR/INVALID。Ramp-up 與 Warm-up 都不計入 measured rates，但任一階段仍可使執行結果成為 ERROR。產生器丟棄獨立計算。

| 指標 | 分子 / 分母 |
| --- | --- |
| `sutErrorRate`／原有 `errorRate` 門檻 | `measuredFailure / measuredCompleted` |
| `runtimeErrorRate` | `measuredRuntimeError / measuredCompleted` |
| `totalErrorRate` | `(measuredFailure + measuredRuntimeError) / measuredCompleted` |
| `completedThroughput` | 全部測量完成數 / 測量秒數 |
| `successfulThroughput` | 測量 PASS 數 / 測量秒數 |

可選的 `runtimeErrorRate`、`totalErrorRate` 門檻採用百分比比較，例如 `{errorRate: '< 1%', totalErrorRate: '< 1%'}`。`errorRate` 保留僅計 SUT FAIL 的含義，不包含執行期錯誤或丟棄。Thresholds PASS 可與 Execution ERROR 同時出現；門檻通過或完成 TPS 均不代表執行成功。沒有測量完成項目時，JSON/YAML 的錯誤率維持數值零以相容舊消費者，HTML 顯示 N/A。報告摘要沒有門檻時顯示 `Thresholds: none configured`；有門檻時顯示 `Thresholds: N passed / M failed`。

Runtime failures / Failure diagnosis 顯示全部／測量執行期錯誤數，並於每個彙總、workload 或 mix 收集器保留最多 100 個已遮罩的輕量失敗樣本。樣本包含 iteration、序號、階段、時間、workload/mix、結果、分類、診斷來源／代碼／摘要及證據狀態；達到樣本上限後仍完整計數。沒有樣本時，報告顯示 `No lightweight failure samples were recorded.`。排程器例外與執行器回傳失敗分開顯示；公開資料不含 stack trace、已分類秘密或主機路徑。保留的 Case Log 可從 Evidence 區段連結開啟。`NO_CASE_LOG`、`WORKSPACE_MISSING`、`EVIDENCE_IO_ERROR`、`EVIDENCE_MATERIALIZATION_ERROR`、`NOT_RETAINED_BY_POLICY` 分別說明證據缺失原因，不改變 iteration 結果，也不額外計為一次失敗。

#### Load presets

Load testing 預設使用 preset resources。六種模式共同使用 `att-load-template/v1`：`single-vu`、`single-tps`、`single-workload-mix`、`multi-vu`、`multi-tps`、`multi-workload-mix`。Single VU/TPS 沿用 Quick Load；mix weights 是每個 workload 內的 iteration 選擇機率，不會分配固定 VU。各 workload 獨立持有 users 或 requested arrivals；所有 workload 共用 phase timing。實際 throughput 和 generator drops 另行報告。

**Scenarios** 保留 executable YAML 與 **Saved Quick Load policy**，排除 `load/templates/`、`load/bindings/`。**View LOAD Jobs** 開啟 Jobs and results，使用 Server 授權後的 LOAD 篩選及分頁，detail/back 繼續保留 URL filter。

內建 ID 為 `builtin:<mode>`。Package preset 放於 `load/templates/`，binding 放於 `load/bindings/`；只允許 package-relative、安全且通過 schema 的檔案，每檔最多 256 KiB，catalog 掃描有上限。Preset 只保存結構與 policy，不保存 Target ID 或 business values。完整 YAML 範例見英文同名章節；preset 欄位為 `schemaVersion`、`name`、`mode`、`policy`、`workloads`。Workload 宣告穩定 `id`、可選 `load` override、mix slot 的 `id/weight`。Binding descriptor 使用 `att-load-bindings/v1`，`bindings.<workloadId>.target` 或 `bindings.<workloadId>.mix.<slotId>` 指向 `{type,id}`。

Catalog inspection 每頁最多 100 項，亦受序列化 response 大小限制（預設 256 KiB）。須持續跟隨 `nextOffset`，直到其為 null；每頁保留完整 definition，相同 query 與 offset 可取得對應 definition，毋須額外 detail request。若單一 definition 已超出大小限制，catalog 仍列出 metadata，但標示 `response-too-large` 並省略 definition；可縮小檔案供 Studio 使用，或在 CLI 直接以檔案 reference compose。CLI `load --list-templates` 自動讀取所有分頁，並保留掃描上限的 `truncated` 標示。

例如 workload-a 使用 10 VU、A/B 70/30，workload-b 使用 20 VU、B/C 40/60，即 2 workloads、4 bindings、3 unique targets、30 VU；重複 Target 不合併 workload。額外 binding 必須有 `prototype`，明確指向同 scope 的原有 slot，複製 policy 而不複製 Target；不可省略必要 slot。Descriptor 最多 128 workloads/entries，Server 可設定更低 aggregate limits。

Builder 以精簡表格顯示 users/rates、bindings、weights。Workload、mix、Target、common settings 使用 Save/Cancel modal；Cancel/Escape 捨棄暫存修改。每個 Target 的 typed inputs/vars/Tool arguments 獨立載入；優先次序為 scenario preset policy、safe target defaults、explicit binding values、適用的 CLI overrides。Debug-local testdata imports 不會自動提升，須明確宣告 Load testdata。Binding 亦可指定 workload inputs/vars/testdata selection。Business inputs/vars 按 key 合併，優先順序為 safe target defaults、Target binding、workload binding；後者覆蓋同名 key，其餘 key 保留。Studio validation 及編輯會保留各 workload 的 timing、ramps、execution settings 與 ranged think time。

Validate 經由共享 Engine composer 建立完整 `att-load/v1.9` immutable draft；Review 顯示 Server-normalized redacted YAML。儲存修改或更改 Environment 會使驗證失效；提交時再次檢查 package/source revision。Run 前確認 traffic；Schedule 沿用相同 draft、權限、limits、expiry 和 revision policy。不使用 browser storage 或寫入 package source。

LOAD Job 的 **Configure & Rerun** 使用此標準 editor 及 Server 發出的 `draftRef`。它會預填安全保留的 scenario、environment、workload IDs/mix、model、pacing、timing phases、thresholds、evidence 及可安全保留的 inputs。修改共用 timing envelope 與模型專屬欄位後，需依目前 Package 驗證並檢查差異。Current-package rerun 不會無提示重載最新 YAML defaults，也不會把 Arrival Rate 轉成 Virtual Users。File-backed LOAD replay 會沿用 package-relative source route，不套用只供 inline draft 使用的 duration 上限；詳見 [Job replay](reference.zh/execution-modes/unified-run.md)。

```bash
./att.sh load --list-templates --format json
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview --format json
```

Windows `att.bat` 使用相同參數。List/preview 不啟動 traffic；確認 composed scenario 後才移除 `--preview`。Multi-target 必須提供完整 bindings，拒絕不明確的 `--input`、`--set`、`--users`、`--arrival-rate`、`--think-time`、`--max-concurrent`、`--overload-policy`；duration/warmup/ramp timing 可共同覆蓋。既有不帶 preset 的 single-target grammar 和無參數 scenario discovery 不變；validation error 回傳非零 exit code 與欄位位置。

## Resources 與 integrations

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是同級 Resource 類型。SSHHelper 可路由 command-backed Tool，並透過共通的 operation-result/evidence contract 提供 `ssh.<helperId>.<operation>` 操作。

```text
Tool       -> process/call operation --\
DBHelper   -> JDBC operation ----------+--> Action output
MQHelper   -> MQ operation ------------/
HTTPHelper -> HTTP operation ----------/
SSHHelper  -> SSH operation -----------/
```

Resource ID 是 Flow、Action 和 expression 引用的 logical contract。Environment profile 可將相同的 DB/MQ/HTTP/SSH logical ID 綁定到不同 descriptor，而不需修改 Action YAML。

小寫 `x-` prefix 可停用 ATT-owned Resource configuration 中的欄位和 keyed entries，但不會移除 HTTP header name 或 DB parameter 等 user data；詳見[Configuration](reference.zh/configuration.md)。

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

Tool 是所選 Package 專屬的具名 external 或 framework-native capability。每個 `tools/*.yaml` 檔案包含一份 `att-tool/v1` descriptor，且必須二選一使用 command 或 call。全局 DB/MQ/HTTP/SSH Helpers 仍由 Runtime Config 管理。

#### Command-backed Tool

Command Tool 使用 stdoutFormat 將外部 stdout 解析為型別化結果：

~~~yaml
schemaVersion: att-tool/v1
id: queryOrder
name: queryOrder
description: Package Tool queryOrder
command: [./tools/query-order.sh]
stdoutFormat: json
arguments: {}
~~~

stdoutFormat 支援 text、json、yaml、xml。ATT 只解析一次 stdout，再將 typed value 發布於 output.result。此欄位不控制人類可讀日誌或檔案輸出。Exit code、有界 stdout/stderr preview 和 process artifacts 都屬於 evidence。

#### Call-backed Tool

Call-backed Tool 呼叫 built-in 或支援的原生 DB/MQ/HTTP/SSH operation，其 native typed return value 發布於 output.result。Call-backed descriptor 不宣告 stdoutFormat。

~~~yaml
schemaVersion: att-tool/v1
id: queryOrder
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
schemaVersion: att-tool/v1
id: invokePaymentApi
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
schemaVersion: att-tool/v1
id: writeAudit
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

每份 `att-tool/v1` 只定義一個 Package Tool，不使用 Global `tools` 或 `toolGroups`:

| Object | Allowed properties |
|---|---|
| `att-tool/v1` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `att-tool/v1.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `type`, `enumValues`（僅 `type: enum` 時必須），`argName`、`argNameMode`、`delimit`、`x-*` |

`type` 宣告 configured Tool 完整解析後的值 contract，可用 `any`、`string`、`integer`、`long`、`decimal`、`boolean`、`enum`、`bytes`、`map` 或 `list`。未填 `type` 時仍按 `any` 處理，維持舊 descriptor 行為。數值和 Boolean scalar 接受原生值或去除前後空白的數字／`true`／`false` 字串；enum 必須符合其中一個 `enumValues`；`bytes` 接受 byte array 或 UTF-8 String。`string`、`map` 和 `list` 要求原生值型別吻合。這些檢查在巢狀 expression 完整解析後執行；靜態安全可求值的內容會在 validation 檢查，其餘在 runtime 檢查。

#### Package-local Tool Group

Standalone Tool 使用 `tools/<id>.yaml` 與 `att-tool/v1`；Grouped Tools 則由固定名稱 `tools/<folder>/tool-group.yaml`（`att-tool-group/v3.0`）明確列出每個 `att-tool/v1` 成員，不遞迴掃描。

```yaml
# packages/fpp/tools/fpp/tool-group.yaml
schemaVersion: att-tool-group/v3.0
id: fpp
name: FPP Tools
description: FPP integration commands
tools: [invokeApi.yaml, loghelper.yaml]
```

`tools/fpp/invokeApi.yaml` 的 `id: invokeApi` 對外仍是 `#{fpp.invokeApi(...)}`。公開前綴來自 Manifest 的 `id`，不是目錄名稱。可選 `script` 保留 Group Dispatcher + 成員 ID + 成員命令 argv 順序。可選 `ssh: {helper: application}` 綁定 Global SSHHelper，不容許在 Manifest 定義主機或憑證。缺失／重複成員和歧義 qualified ID 會阻止驗證。Tool 屬所選 Package；Helper 繼續屬於全域配置。

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

在 Native `att-flow/v3.6` 契約中，DB operation 在普通 `type: tool` Action 內使用 `#{db.<id>.query(...)}`、`scalar(...)` 或 `update(...)` call。Call 必須有一個 String `sql` argument。SQL 若存放於 package，可使用 `sql=&{sql/find-order.sql}`。Positional `params` 與 named `parameters` 互斥，並使用相同 JDBC binding 規則。

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

LOAD 的 run-scoped connection provider 在 `scope: statement` 的每次 statement transaction、結果 materialization、statement close 及 cancellation cleanup 完成後歸還 connection；後續 DB Action 重新借用。`statement/commit` 使用 JDBC auto-commit；`statement/rollback` 在歸還前 rollback。Query/update failure 亦在 cleanup 後歸還，不會自動重試 SQL。

`scope: case` 保留 shared connection 至 Case finalization，維持 read-your-writes 與 rollback-only 行為；預設仍為 `case/rollback`。直接 RUN/DEBUG connection 的生命週期維持原狀。只有各 Action 獨立時才明確選用 statement scope；由 Case scope 改為 statement scope 會改變 transaction 語意。

LOAD `resources.db` 按 helper 增加 aggregate `totalConnectionHoldMs`、`releasedConnections`、`totalStatementMs`、`statementCount`、`activePeak` 及 sampled `observedWaitingPeak`，並保留原有 borrow count/wait/timeout metrics。Hold time 從借用成功至 close 完成；statement time 不含 pool acquisition，但包含結果與 resource cleanup。Sampled waiting peak 可能未捕捉短暫等待；所有 counter 都不包含 SQL、參數、URL 或 credential。`DB_POOL_TIMEOUT` 仍表示 SQL 執行前 acquisition 失敗；SQL timeout 與 Tool Action timeout 是獨立預算。

Validate、docs 與 dry-run 都不會打開 DB Connection。dbhelper 文件路徑、ID、字段、SQL 文件和 Flow call 會在執行前校驗。

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

以上 `waitForOrder` query 無需後續 Log Action。DBHelper 可作以下設定，自動保留 SQL*Plus-style row capture：

```yaml
evidence:
  sql: full
  parameters: masked
  output:
    format: sqlplus
    maxChars: 10000
```

`sql` 決定 SQL evidence；`parameters` 僅接受 `values`、`types` 或 `masked`（沒有 `no_mask` 選項），預設為 `values`。任何 parameter mode 都會繼續遮蔽 configured credentials。`output` 只控制 presentation；Assertion 和後續 Action 仍讀取 typed rows，`db.<id>.query`／`scalar` 遵循相同 policy。`maxChars` 預設 10000，範圍為 1 至 1000000。大型 `SELECT` 可能需要提高上限，才能保留完整 formatted rendering。

### MQHelper

MQHelper 是由多個 physical instances 組成的 IBM MQ logical resource。現行 descriptor 使用 att-mqhelper/v1.3。Connection settings、credentials、queue defaults 與 pool limits 屬於 resource，不會公開至 META。

~~~yaml
schemaVersion: att-mqhelper/v1.3
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

#### Request queue 路由

<!-- att-docs:historical -->
`att-mqhelper/v1.3` 新增 `message.requestQueues` 及 `message.requestQueueSelection.strategy`。既有 `att-mqhelper/v1.2` scalar descriptor 仍可使用，並保留原有行為；新欄位須使用 v1.3。
<!-- /att-docs:historical -->

~~~yaml
schemaVersion: att-mqhelper/v1.3
id: payment
name: Payment MQ
description: Payment request queue routing
defaults:
  connection: {queueManager: QM1, host: mq.example.internal, port: 1414, channel: APP.SVRCONN}
  message:
    requestQueues: [PAYMENT.REQUEST.01, PAYMENT.REQUEST.02, PAYMENT.REQUEST.03]
    requestQueueSelection: {strategy: roundRobin}
    replyQueue: PAYMENT.REPLY
instances:
  - id: primary
~~~

清單須有 1–256 個不重複、非空白的 queue name，每項最多 48 個 `[A-Za-z0-9_.%/-]` 字元。`single`（預設）選第一項；`roundRobin` 依清單順序輪替；`random` 只會選已設定項目。同一 message object 不可同時宣告 scalar `requestQueue` 及清單 `requestQueues`。Instance 的 scalar 會取代繼承的清單及其 selection policy；instance 的清單會取代繼承的 scalar／清單，並在沒有自行宣告 policy 時重設為 `single`。只覆寫 `requestQueueSelection` 會保留繼承的清單；selection policy 必須有有效清單。Reply default 獨立繼承，仍是單一可選的 scalar `replyQueue`。

Runtime 先以 `instance=` 或 physical `selection.strategy` 選擇 physical instance，再處理 request queue。`send(queue=...)` 或 `request(requestQueue=...)` 優先於自動路由。明確傳入空白／無效 queue 會在 MQ I/O 前回報 `MQ_ARGUMENT`，不會改用預設目的地。沒有明確 queue 時，使用已選 instance 的 scalar 或清單；缺少必要有效 queue 亦會在連線前失敗。`receive` 繼續使用明確 `queue` 或單一 reply default。每個 request queue 都屬於已選 instance 的 Queue Manager，不會跨 Queue Manager 路由。

Queue round-robin counter 支援並行存取，範圍是單一 `MqHelperExecutor`、logical helper 及 physical instance。共用 executor 的 Run/Debug/Load 呼叫會共用 counter；不同 executor 或 Worker JVM 各自從頭開始，不保證 Server-wide 或跨 Worker 公平性。明確 queue override 及 receive 不會推進 counter。自動選擇只在 argument／payload 驗證後、連線前執行一次；其後 connection/open/PUT 失敗仍會推進 counter。成功 PUT 後（包括 correlated reply timeout）不會重新選擇或自動 failover；明確 Action retry 仍按下述 replay policy 處理。

Action evidence 及 output metadata 包含 `requestQueue`、`queue`、`requestQueueSelectionStrategy`（`single`、`roundRobin`、`random` 或 `explicit`）、已選 physical instance、Queue Manager，以及 request 的有效 `replyQueue`。ReplyToQ/ReplyToQMgr 及已送 MsgId → reply CorrelId matching 維持。這些有界路由欄位會隨保留的 Run/Debug/Load evidence 提供，憑證仍受保護。

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

Call-level responseFormat 可覆蓋 receive/request 的 requestReply.responseFormat；send 不解析 reply。Instance selection 與 pool limits 屬於 descriptor。Migration details 見[Legacy migration reference](legacy/migration-reference.zh.md)。

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
| `pool` | defaults | maxSize 20（1–10000），minIdle 0（不可大於 maxSize），borrowTimeout 2s。`att-mqhelper/v1.3` 可選生命週期欄位預設關閉：`maxIdle`、`maxLifetime`、`staleIdleValidation`、`staleIdleThreshold`（60s）、`validationTimeout`（200ms，1–5000ms）、`heartbeatInterval`（0） |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../schemas/att-mqhelper-v1.3.schema.json).

##### 閒置 MQ 連線與 teardown

LOAD pool key 是 `logicalId::instanceId`（例如 `fromeaimqhk::mq01`）。Teardown 診斷使用單冒號 `fromeaimqhk:mq01`。兩者指同一個實體 pool。`fromeaimqhk::mq01` 的失敗不會歸到 `fromeaimqcb::mq01` 或 `toeaimq::hk02`。

`resources.mq` 是 disconnect 之前的 PRE_TEARDOWN 樣本。`failures` 只計算建立連線失敗。Idle validation 的問題只記在 `validationFailures`，不計入 `failures`。`cleanupFailuresIncluded` 為 false：disconnect 結果留在 `disconnectFailureCount` 與 `failureClass`。`failures: 0` 且 `idle: 2` 不代表 disconnect 已成功，也不代表做過 validation。`resources.postTeardown` 是 disconnect 嘗試之後的另一份確認樣本。未確認的 session 會隔離：容量繼續佔用、連線不會再被借出，MQRC 2009 也不能證明 queue manager 已釋放該 session。Job 維持 ERROR。

`PHYSICAL_DISCONNECT_FAILED` 表示 shutdown 等待結束前 disconnect 已返回或拋出錯誤。`LOAD_RESOURCE_TEARDOWN_TIMEOUT` 表示該等待結束時（`max(borrowTimeout, 5s)`）connect 或 disconnect 仍在執行。該 session 只凍結成一筆紀錄，包含 deadline 時仍在 factory 裡、連線物件尚未存在的 connect。之後才完成的結果，包括 MQRC 2009 或成功的 disconnect，會附在同一筆紀錄上，不會再計一次；failure class 維持 `LOAD_RESOURCE_TEARDOWN_TIMEOUT`。Validation timeout 不是這兩個分類：該連線不會再被提供。`close()` 會中斷 idle-validation 執行緒。該執行緒在呼叫 disconnect 之前會清掉 interrupt 狀態，closer 不會看到 validation 的 interrupt。若關閉結束時檢查仍在執行，且尚未呼叫 `disconnect()`，結果是 `MQ_IDLE_VALIDATION_INCOMPLETE`，不計入 `failedCloseCount`。`isConnected()` 回傳 false 是 `MQ_IDLE_NOT_CONNECTED`，不會假造 MQRC 2009。只有 IBM 實際回傳時才記錄 reason 2009。取消的 lease 或其他非連線失效會離開 idle pool，並以 `exclusionCode=INVALIDATED` 表示，不會標成 known-dead。`knownDead` 只用於已識別的連線失敗或明確的 outage。

最終 disconnect 的 reason 2009 不能說明 socket 何時失效。請用失敗時間對照 queue manager、channel、client 與 network 日誌。2026-10-30 開始、於 2026-11-01 10:52:35Z 結束的那次執行，當地時間是 2026-11-01 18:52:35 HKT（UTC+8）。行程結束不能證明 MQ 已正常斷線。

預設 borrow 路徑不會呼叫 MQ。只有在 `staleIdleValidation` 為 true，且 idle 時間達到 `staleIdleThreshold` 時，才會呼叫 IBM `isConnected()`；若 client 提供 `inquire`，接著查詢 `MQIA_PLATFORM`。這不是業務 put/get。`staleIdleThreshold: 0` 會在每次借用 idle 連線時都做檢查。Client 沒有這些方法時記為 unsupported，連線仍會被提供。`maxIdle` 與 `maxLifetime` 會關閉並回收 idle 連線，不會做 validation，也不會送業務訊息。下次 borrow 會先回收已過期的 idle session，再借出其他連線。任一限制大於 0 時，背景掃描大約每 30 秒關閉已超過限制的 idle session，包含沒有再被借用的 session，因此不必等到最終 teardown。一般的 borrow 與歸還通知不會提早執行這次掃描。`0` 表示停用該限制。兩者都是 0 時不會啟動掃描，預設 borrow 路徑仍然不會做 MQ I/O。`close()` 會立刻停止掃描，再等待 disconnect，同一 session 只會 disconnect 一次。連線失敗（包括 MQRC 2009）會把實體 disconnect 排入佇列，並立刻釋放 borrow permit，不會讓 LOAD worker 等待 IBM `disconnect()`。實體 slot 會維持佔用，直到 disconnect 被確認，因此該連線不會再被借出。每個 pool 最多同時執行 4 個 disconnect，執行緒名稱是 `att-load-resource-disconnect`。其餘 close 在該 pool 的佇列中等候，並保留容量。`close()` 會把已排隊與執行中的 disconnect 一併計算。shutdown 等待結束時仍未完成的，維持一筆凍結的 `LOAD_RESOURCE_TEARDOWN_TIMEOUT`。disconnect 回傳 MQRC 2009 時 Job 仍是 ERROR。ATT 不會自動重試業務呼叫。

`pool.heartbeatInterval` 只有在 `com.ibm.mq.constants.MQConstants` 真的提供 `HEARTBEAT_INTERVAL_PROPERTY` 且值大於 0 時才會套用。IBM MQ classes for Java 9.4 沒有這個連線屬性，因此該設定不會覆寫該 client 的 SVRCONN channel HBINT。`0` 不會設定任何 client 屬性。若 idle session 被防火牆切斷，請把 channel HBINT 設得短於防火牆的 idle timeout。TCP keepalive 屬於作業系統與 IBM client（`com.ibm.mq.cfg.TCP.KeepAlive`、channel KAINT），ATT 不會啟用它。只有在成功的 disconnect 確實需要更長時間時才提高 `borrowTimeout`；卡住的 disconnect 仍是 `LOAD_RESOURCE_TEARDOWN_TIMEOUT`，並維持隔離。

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

在 `att-config/v2.15` 的全域或 `environments.<NAME>.sshhelpers` 列出 descriptor 路徑。目前 package 使用 config v2.15 與 Tool Group v3.0。選定環境的清單會整組取代全域清單；省略則繼承。Tool group 所綁定的相同邏輯 ID 必須在每個選定 profile 內存在。SIT 可綁定一臺，UAT 綁定兩臺，Tool／Action 不必修改：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.15
environment: SIT
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
# packages/<id>/tools/app/tool-group.yaml
schemaVersion: att-tool-group/v3.0
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # 可選 group override
tools: [status.yaml]

# packages/<id>/tools/app/status.yaml（獨立檔案）
schemaVersion: att-tool/v1
id: status
name: Status
description: Print service status
command: [systemctl, is-active, example.service]
stdoutFormat: text
```

Action 仍呼叫 `app.status`。先在本機／CI secret environment 把 `APP_SSH_KEY` 設為可讀私鑰的**路徑**，再分別以 `./att.sh validate --config config/config.yaml --env SIT --package` 及 UAT 驗證。完整 `${ENV:NAME}` identityFile reference 在載入時解析；缺失／空值會報錯而不揭露值。Package-local Tool Group 的 `ssh` 僅能引用全域 SSHHelper（`helper`、可選 `selection`）；舊式直接 host/user/key 設定必須搬至全域 SSHHelper。Call-backed Tool 可在 primary `type: tool` operation 中呼叫 native SSH Resource Helper。現行 Tool Group schema 為 v3.0。Command-backed Tool 使用 `stdoutFormat`（`text|json|yaml|xml`）設定 stdout parsing；call-backed Tool 保留 native result type。舊 config/group schema 僅供 migration reference。SSH Resource Helper call 只發布 `META.SSHHELPER.id` 與 `.type`；endpoint、user、identity file、credentials 仍保持私有。Resource call 沒有 Action／per-call strategy override；selection 由 helper configuration 決定。

Strategy 優先序：group override，再到 helper 預設。Native Resource Helper call 中，單 instance 不需 strategy（`single`）；多 instance 只支援 `random` 或 `roundRobin`，`all` 會被拒絕，不會靜默選一臺。Command-backed Tool routing 才支援 `all`：`random` 均勻選一臺，`roundRobin` 以 thread-safe 循環計數器選一臺，明確的 `all` 在並發上限內對每臺各執行一次。**Command-backed `all` 會在每臺主機產生副作用**；只用於整組執行均安全的命令。不會隱式 fan-out、跨主機重試或 failover。若作者設定 Action timeout retry，整個 `all` 呼叫會重做，並非只重試某臺。每臺依 Action／Tool／全域 timeout 執行；中斷會取消正在執行的 OpenSSH process 或 Java SSH session。兩種 transport 使用同一組標準化 host/user/port/key。優先 OpenSSH；mwiede/jsch fallback 仍嚴格驗證 host key，限制見 SSH 診斷章。

單主機時解析後的 `output.result` 仍是舊有 scalar／object。Evidence 新增 `sshHelper`、`instance`、`host`、`selectionStrategy`、`selectionSource`（`helper` 或 `toolGroup`）、transport、起訖／持續時間、exit code、輸出及錯誤。只有 command-backed Tool fan-out 使用 `all`；此時 `output.result` 包含 `sshHelper`、有效 `selectionStrategy`、`selectionSource`，以及依 descriptor 順序以 ID 為 key 的 `instances`；每筆有 `instance`、`host`、`port`、`transport`、`startedAt`、`endedAt`、`durationMs`、`status`，在適用時另有 `exitCode`、`stdout`、`stderr`、`rawOutput`、解析後 `output` 或 `error`。命令正常完成時即使 `exitCode` 非零，仍是 `status: PASS`；exit code 是供 Action assertion 判斷的證據，不屬操作失敗。只有執行、輸出解析、取消或 timeout 錯誤才令 operation 失敗，其他主機證據仍會保留。Assertion 可查 `${output.result.instances.app1.exitCode}`、`${output.result.instances.app1.status}` 或 `${output.result.instances.app1.output}`。Evidence 不記錄認證內容或環境提供的私鑰路徑；私鑰應放在 package 外，命令中亦不要放祕密。

單主機及 `all` 執行都會在 argv、transport stderr（包括串流寫入的 Case log 診斷）及 exception evidence 遮蔽環境提供的私鑰路徑。上述不記錄保證適用於 ATT metadata 和 transport 診斷；解析後的業務 stdout 不變，因此命令不可輸出私鑰路徑。

遷移：把舊 Tool Group 的 host/user/port/key 搬到全域 SSHHelper，在各環境綁定，再建立 Package `tools/app/tool-group.yaml`（`att-tool-group/v3.0`）與 `att-tool/v1` 成員，使用 `ssh: {helper: application}`，逐一驗證環境。Action 不需重寫。Inventory discovery、Action 層指定主機、分散式交易、跨主機 failover 與 orchestration 均不在此 schema 範圍。

`stat(remotePath='/srv/app/result.xml')` 透過 SFTP lstat 回傳 typed `{path, exists}`，存在時包含 `type: file|directory|other`、file `size` 與可用的 ISO `modifiedAt`；missing path 是正常 `exists: false`，permission/auth/transport error 仍失敗。`mkdirs(remotePath='/srv/app/archive')` 建立 parents，已有 directory 時冪等，遇到 non-directory 時失敗。`move(sourcePath='/srv/app/out.xml', targetPath='/srv/app/archive/out.xml', overwrite=false)` 在同一選定 host 上 rename，source 必須存在，已有 target 需明確允許 overwrite；不下載／上傳。明確 overwrite 會先移除已有且型別相同的 regular file 或 empty directory，再執行 SFTP rename，因此不依賴 server 的 rename-overwrite extension。Non-empty directory、special file 或型別不符會拒絕。Replacement 不是 atomic：移除失敗時保留兩個 path；之後 rename 失敗則 source 仍在，target 可能不存在。`delete(remotePath='/srv/app/tmp.xml', missingOk=false)` 支援 regular file 和 empty directory，missingOk 必須明確開啟；non-empty directory、recursive 參數、wildcard／backslash path 都拒絕。所有 operation 只接受具名參數及可選 `timeoutMs`，沿用 selection／concurrency／deadline／host verification／redacted identity／Run-Debug-Load execution。Filesystem operation error 使用 `SSH_STAT_ERROR`／`SSH_MKDIRS_ERROR`／`SSH_MOVE_ERROR`／`SSH_DELETE_ERROR`。沒有 remote copy API；需要 copy 時明確使用 `execute(command='cp /srv/app/a /srv/app/b')`。File-content expression 使用 `&{...}`，local output 由 ATT 管理。

## 可靠性與執行控制

本頁定義 Native Flow Actions 及其 Resource calls 的共通行為。

### Assertion 與 status

Assertion 會在文件指定的 assertion point、Action primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception 或 infrastructure problem 是 `ERROR`；authoring 或 configuration 無效是 `INVALID`；未選中的條件是 `SKIPPED`；成功工作是 `PASS`。Operation failure 與 assertion failure 是不同概念。

### `runWhen` 與 `onFailure`

`runWhen` 決定 Action 是否 eligible to execute。`onFailure: stop|continue` 決定 failure 後是否繼續；`continue` 不會把 failed status 改為 PASS。Cleanup 和 diagnostics 應使用 conditional execution，而不是隱藏 primary operation failure。

### Timeout

Timeout 會依 backend 支援能力終止或放棄 operation，並記錄 diagnostic evidence。Timeout 是 operational failure，不是 false assertion。Tool timeout 行為及 DB/MQ/HTTP/SSH limits 由各 Resource contract 定義。

### Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可有多個 attempts。Retry policy 決定哪些 operation failure 可重試。最後或勝出的 operation 會成為 top-level `output.result` 與 `output.evidence`；每次 attempt 均保留在 `output.attempts[n]`。後續成功不會抹去較早的 attempt evidence。

### Evidence collectors

Tool evidence collectors 會在 primary operation 發布 typed `output.result` 後、該次 attempt assertion 前執行。Action 執行期間可用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 為 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 與 `.status`。Collector 有獨立的 `timeoutMs` 和 `onFailure: continue|stop`。Collector output 屬於 attempt evidence，不會取代 primary operation result。

每次 primary attempt 都會執行 collector。Top-level collector node 代表最後或勝出的 attempt；`output.attempts[n].evidence.collectors.<id>` 則保留各次 attempt。`continue` 會在 diagnostic collection 失敗時保留 primary outcome；`stop` 會令 collector failure 成為 Action error。若收集值是 business/test data，而不是 assertion 前的診斷資料，請使用一般 Tool 或 Log Action。

### Transaction 與 Resource lifecycle

DB transaction finalization 及 DB/MQ/HTTP/SSH cleanup 會在適當的 execution boundary 執行。這些機制可能影響 operation success 和 diagnostics，但屬於 internal Resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時會保留嚴重程度：

~~~text
ERROR > INVALID > FAIL > PASS > SKIPPED
~~~

### Tool timeout precedence

Tool Action timeout 優先於 Tool descriptor timeout，再優先於 global timeout。Call-backed DB Tool 的 DBHelper statement timeout 仍是 backend ceiling。每次支援的 primary retry attempt 都會在 assertion 前執行 collectors。

### Direct DB timeout 與 retry eligibility

Direct DB Actions 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。設定時，`Action.timeoutMs` 會覆蓋 `DBHelper.statement.timeoutSeconds`；否則使用 helper timeout。每次 retry attempt 都會重新取得完整 Action timeout，retry interval 不計入該 timeout。

Direct `query` Action 亦可使用標準 retry block：`maxAttempts` 2–10、`intervalMs` 0–3,600,000，`retryOn` 是非空且不重複的 `ASSERTION`、`TIMEOUT` 清單。使用 `ASSERTION` 必須同時定義 Action `assert`。一般 SQL errors 為 terminal。啟用 retry 後，每次 query attempt 會保留在 `output.attempts[n]`；top-level `output.result` 與 `output.evidence` 代表最後或勝出的 attempt，並以 `winningAttempt` 或 `finalAttempt` 記錄終止 attempt 編號。

Direct `update` Actions 支援 `timeoutMs`，但拒絕 `retry`。Timeout 或 database/transport failure 發生後，ATT 通常無法判定 mutation 是否已送達或 commit；自動重放可能重複修改業務資料。因此，application-specific idempotent retry 必須明確建模。

## Configuration 與 Environment

共用 Runtime Configuration 選擇 Environment、output 行為和全域 Resource bindings。Native Package roots 和 Package-owned resources 由[Package Manifest](reference.zh/package-manifest.md)定義。使用較早 configuration 欄位的既有 Package，請參閱[舊版 Configuration 指南](legacy/configuration.zh.md)。

### Configuration layers

| Layer | Source | 管理範圍 |
|---|---|---|
| 共用 Runtime Configuration | `ATT_HOME/config/config.yaml` | Environment 選擇、execution defaults、report limits 和共用 DB/MQ/HTTP/SSH Resources |
| Environment profile | 共用 configuration 的 `environments.<name>` | Environment 專屬 Resource bindings |
| Package Manifest | `<PACKAGE_ROOT>/package.yaml` | Package identity、Flows、Test Case Definitions、Tools、Testdata 和 Package-owned files |
| CLI options | 目前的 command | 單次命令的 selection、Environment、identity 和 output overrides |

Package Manifest 管理 Package resource roots 和 Testdata。Native Package 不需要獨立 runtime configuration、schema tree 或 helper registry。

### 選擇 Environment

使用單一 shared configuration file，再用 `--env` 選擇 Environment。最上層的 `environment` 是預設值。

~~~yaml
schemaVersion: att-config/v2.15
environment: SIT
outputDirectory: output
timeoutMs: 30000
dbhelpers:
  - config/dbhelpers/orders.yaml
mqhelpers:
  - config/mqhelpers/payment.yaml
environments:
  SIT:
    dbhelpers:
      - config/dbhelpers/sit/orders.yaml
    mqhelpers:
      - config/mqhelpers/sit/payment.yaml
  UAT:
    dbhelpers:
      - config/dbhelpers/uat/orders.yaml
    mqhelpers:
      - config/mqhelpers/uat/payment.yaml
~~~

~~~sh
./att.sh validate --package --config config/config.yaml --env SIT
./att.sh validate --package --config config/config.yaml --env UAT
./att.sh --local -p default run native-case payments.offline.authorize --resolved-case-id auth-accepted --config config/config.yaml --env SIT
~~~

Profile 會整組替換已設定的 Resource lists。請在不同 Environment 維持相同的 logical Resource ID，透過 profile bindings 選擇 endpoint 和 credentials。

### 設定 execution identity 和 output

| 欄位 | 預設值 | 說明 |
|---|---|---|
| `outputDirectory` | `output` | 本機 output 的 Package-relative root |
| `timeoutMs` | `10000` | 預設 execution timeout，範圍 1 至 3,600,000 毫秒 |
| `run.id.default` | `timestamp` | 唯一支援的 Run ID 預設方式 |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Java date/time format |
| `execution.runIdFormat` | 未設定 | 外層 RUN 或 LOAD identity 的選用 expression |
| `execution.debugIdFormat` | 未設定 | 單次 Flow 或 Tool Debug 的選用 expression |
| `execution.processOutput.memoryLimitBytes` | `65536` | 記憶體保留 stdout/stderr preview 的上限 |
| `execution.processOutput.artifactLimitBytes` | `104857600` | 每個 process artifact 的最大串流 bytes |
| `report.html.caseLogInlineLimitBytes` | `32768` | HTML output 中的 case-log inline 上限 |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | JUnit case-log embed threshold |

明確的 `--run-id` 和 `--debug-id` 會覆蓋生成的 identity。生成值必須是安全的單一路徑段，且在選定 output root 中唯一。Identity expression 在 execution 前執行，無法讀取每個 Case 或 iteration 的資料。

Native RUN result workbook 會寫入 `workbooks/native-cases.result.xlsx`。較早的來源 workbook report naming 見[舊版 Configuration 指南](legacy/configuration.zh.md)。

### 使用 `x-` 忽略或停用 ATT 配置項

在 schema 支援的 extension location，以小寫 `x-` prefix 可於 validation 或 execution 前停用 ATT-owned fields 和 keyed entries。這不會移除 HTTP header 名稱或 DB parameters 等 user data。`runWhen: false` 是 runtime condition，不會停用 configuration fields。

### Resource bindings 和 credentials

共用 configuration 管理 DB、MQ、HTTP 和 SSH Resource descriptors。路徑必須位於各自配置的 runtime root 內。Environment profile 可整組替換各 Resource list。

在支援的位置使用核准的 secret-injection syntax。不可提交 credential，也不要把解析後的 secret 放入 log、report field 或 diagnostic output。缺少變數的訊息只會指出必需的變數名稱。

### 驗證變更

~~~sh
./att.sh validate --package --format json --quiet
./att.sh validate --package --config config/config.yaml --env UAT
~~~

Validation 會檢查 configuration schemas、profile references、Resource descriptors、Package resources 和 dependency paths。它是唯讀操作，不會提交 Job、執行 Tool 或啟動 Load scheduler。

Native Package roots 和 Manifest 欄位見 [Package Manifest](reference.zh/package-manifest.md)。共用 Resource contract 見 [Resources](reference.zh/resources/overview.md)。

## Package Manifest

使用 `<PACKAGE_ROOT>/package.yaml` 為單一 Package 宣告預設值、業務Resource根目錄、Testdata inventory 及 SUT Metrics inventory。此檔案可省略，現行 schema 為獨立的 `att-package/v2`；權威定義來自相符 Engine JAR 內嵌的 registry。

Package ID 與實體根目錄來自可信註冊或既有 Local Package 選取。Manifest 不會註冊 Package、授權使用者或提供具特權的 runtime configuration。共用 Environment profiles、Tools 與 DB/MQ/HTTP/SSH Helpers 仍由 Global Configuration 管理。

### 宣告 Package 預設值

現行 runtime 在 `<PACKAGE_ROOT>/<templatesRoot>/flows/` 發現 Flow 檔案（預設 `templates/flows/`）。獨立的 `flows/` 目錄尚未支援。現行 `att-package/v2` schema 有 `resources.templatesRoot`，沒有 `resources.flowRoot`。

此範例選取中央既有 profile，並宣告 Package 自己的 Testdata。執行完整驗證前，先建立引用的 descriptor。

```yaml
schemaVersion: att-package/v2
metadata:
  name: Payments
  description: Payment regression resources
defaultEnvironment: SIT
resources:
  templatesRoot: templates
  testcaseRoot: testcase
testdata:
  descriptors: [testdata/accounts.yaml]
  environments:
    UAT: [testdata/uat-accounts.yaml]
    PROD: []
observability:
  metricSources: []
  groups: []
  profiles: []
defaults:
  timeoutMs: 15000
  report:
    columns: {result: Result, durationMs: Duration}
  xml: {namespaceMode: preserve}
```

`observability` paths 為可選欄位，用來列出 Package 自有、位於 `load/sut-metrics/{sources,groups,profiles}/` 的 Metric Sources、Groups 及 Profiles。清單為空代表 Package 沒有 SUT Metrics，不會退回全域定義。完整 descriptor 與共用 Data Source connection 範例請參閱 [SUT Metrics migration guide](guides/sut-metrics-config-migration.zh.md)。

<!-- att-docs:historical -->
既有 `att-package/v1` Manifest 仍採相容模式，保留舊有全域 SUT Metrics 行為。新建或編輯的 Manifest 使用 v2。
<!-- /att-docs:historical -->

中央 `environments` 必須定義 `SIT`、`UAT` 及 `PROD`。某個 Environment 未出現在 `testdata.environments` 時，繼承 `descriptors`；存在的 list 會整份取代 inventory，`[]` 代表清空。

所有路徑使用 `/`，相對於已選取 Package。絕對路徑、`..`、不安全 symlink 及越界路徑均會被拒絕。檔案上限為 262144 UTF-8 bytes。未知欄位、重複 YAML keys、aliases、anchors 及 `x-` extensions 均不允許。

沒有 Manifest 時，Package 在 `templates/flows/` 發現 Flow，Test Case Definition 使用 `testcase/`，Package Testdata inventory 為空。獨立的 `flows/` Package 尚未支援。

現行 Flow 目錄（`templates/flows/`）、Test Case Definition 及 `load/` roots 可在 inspection 時省略。目錄不存在時，Explorer、LOAD Catalog 及 Quick Load 會為該根目錄回傳空 inventory。若已設定或慣例根目錄存在，但不是可讀目錄或解析後超出 canonical Package，檢查會回傳已淨化的 `ATT-RESOURCE-ROOT-INVALID` 診斷；不會跟隨不安全路徑，也不會把存在但無效的目錄當成空目錄。

### 解析 Environment

明確 request 或既有 CLI `--env` 優先；未指定時使用 Package 的 `defaultEnvironment`，再回退至中央預設值。名稱會解析成中央 profile 的正式拼寫。Effective 檢視顯示實際 Environment 及來源。

Server 在解析後套用 policy。Package 預設值不能繞過允許清單或明確選取要求。新建 execution drafts、直接提交及 schedules 會保存已解析 Environment。從已驗證 draft 建立 schedule 而未提供 Environment 時，繼承 draft 的選取。日後修改預設值不能重新導向該 snapshot；來源 revision 檢查可能要求重新審閱。

Registry adapter 可透過 `FrameworkConfigLoader.loadContext` 與明確的 `PackageContext` 提供選取內容；Environment 優先序為 request → Context → Package → Global。來源證據只採用 adapter 提供的已註冊 Package ID，不會由資料夾名稱或登入身分推導。CLI Context 的持久化指令仍屬另一項變更。

執行結果在 `summary.configuration` 保留不含設定值的來源snapshot：標準 Environment、原始選取層、Global 與 Manifest 摘要、有效設定識別、Engine 版本與 revision、Schema Registry 摘要，以及欄位來源與邏輯撰寫路徑。RUN、Debug、Load 亦在受管理產物目錄寫入 `configuration.json`。Server Draft、排隊 Job、Schedule 定義保留核准snapshot，包含已註冊 Package ID 與驗證時的 Package revision；Job 歷史與結果在 Server 重啟後仍保留此資料。呼叫端不能提供這些內部核准欄位。

Worker 在執行前核對已核准的有效設定識別。採用 `ignore` Package 變更政策的 Schedule 會以目前來源重新驗證執行副本；已儲存的核准內容保持不變，執行證據另保留原核准設定識別以供比較。此證據不包含設定值、descriptor 紀錄、憑證或主機路徑。

### 套用 typed defaults

只允許以下執行與呈現預設值；未提供的欄位繼承 Global Configuration。

| Manifest path | 行為 |
|---|---|
| `defaults.timeoutMs` | 正整數，不得超過中央 `packagePolicy.maxTimeoutMs`；預設上限 300000 ms |
| `defaults.run.id.default` | 只接受 `timestamp` |
| `defaults.run.id.timestampFormat` | 使用既有 timestamp format 驗證 |
| `defaults.execution.runIdFormat` | 使用既有 Run/Load ID expression 契約 |
| `defaults.execution.debugIdFormat` | 使用既有 Debug ID expression 契約 |
| `defaults.report.fileNamePattern` | 使用既有報表檔名契約 |
| `defaults.report.columns` | 整份取代 column mapping；包括明確 `{}` |
| `defaults.report.html.caseLogInlineLimitBytes` | 整數 0–1048576 |
| `defaults.report.junit.caseLogEmbedThresholdBytes` | 整數 0–1048576 |
| `defaults.caseLog.yamlAnchors` | Boolean |
| `defaults.xml.namespaceMode` | `ignore` 或 `preserve` |

Manifest 不可覆寫 output directories、process output limits、scheduling limits、authentication、credentials、helper definitions、worker settings 或 schema locations。完整欄位白名單由 Engine 內嵌 Manifest schema 定義。

### 解析 invocation Testdata

Base inventory 屬於已選取 Package 及 Environment。不同 Package 可使用相同 descriptor ID，但不共用 records，也不匯入鄰近 Package 的檔案。

執行只驗證引用所需的 base Testdata dependency closure。未引用的缺失或無效 base descriptor 不會阻止 Quick Load。明確 invocation-local Debug/Load imports 仍採嚴格驗證：明確匯入的缺失或無效檔案會失敗。Invocation descriptor 以相同 ID 整份取代 base descriptor，不合併 fields 或 records；同一 layer 的重複 ID 仍屬錯誤。

完整 Package validation 檢查 Manifest 宣告的所有 descriptor layers，包括各 Environment lists。內建 default Package 可執行：

```bash
./att.sh validate --package --format json
```

按 diagnostics 中的 Package ownership、logical path、layer 與 descriptor ID 修正問題，再發布或分發 Package。

### 在 Studio 檢視及發布

選取已註冊 Package 後，開啟 **Configuration**，再選 **Package Configuration**。**Source**、**Declared**、**Effective**、**Compare with Global** 分別檢視 Manifest；**Shared Configuration** 保留中央共用設定。All Packages 共用 inspector 仍只使用中央來源。

Source reading 沿用既有 source browsing policy 及結構化遮蔽。已遮蔽 projection 只供閱讀。編輯需要 Server 明確啟用，以及已驗證、有效且 login 與明確註冊 Package ID 完全相同的 USER。其他 USER 保留一般操作；只有 ADMIN 的帳戶沒有隱含編輯權限。Login 相同不會自動註冊目錄。

1. 在 **Source** 編輯可見 Manifest，或建立新的可選 Manifest。
2. 選 **Create Manifest draft**，按目前 digest 暫存私有且會到期的 candidate。
3. 選 **Validate Manifest draft**，由 Worker 驗證 candidate，期間不修改 live file。
4. 審閱安全的 declaration、effective values、Environment 與 origins，再選 **Review validated Manifest**。
5. 選 **Publish reviewed Manifest**。Server 重新檢查 ownership、live digest、Global/Package dependencies 與 schema revision，再以 atomic replacement 寫入 `package.yaml`。

Manifest 發布的 fingerprint 涵蓋中央 Runtime Config、其所有已宣告 profiles 明列的 Tool/Helper descriptors、candidate 的每個 Testdata descriptor layer、business root bindings，以及內嵌 Schema Registry。系統在驗證前後及發布時檢查 fingerprint；不掃描無關的 business files、archives 或未被引用的中央檔案。驗證依賴集合最多包含 10000 個不同 descriptor paths，檔案內容總計最多 256 MiB；這是依賴集合限制，不是整個 Package 的大小上限。Execution 與 Job revisions 仍各自檢查 business dependencies。

Studio 會保護已修改的 Manifest source 與尚未發佈的 Draft；建立 Draft 或明確捨棄 source 變更前，不能重新整理或切換檢視。離開頁面亦使用現有的未儲存變更確認流程。

發生衝突時需建立新 draft。發布前保存 audit intent，發布後記錄完成狀態。History 位於私有 Server data，API 只回傳 revisions，不回傳歷史原始 source；按保存檔案的修改時間由新至舊排序，同時刻以 digest 升序排列。**Stage rollback** 只建立 candidate，仍需驗證與審閱。回復至沒有 Manifest 前的預設行為時，使用 canonical empty `att-package/v2` 檔案，審閱畫面會明確標示。

以下可信部署 policy 可省略；預設不允許編輯：

```yaml
server:
  packageConfiguration:
    sourceEditingEnabled: false
    maxDrafts: 128
    maxDraftsPerUser: 8
    draftTtlSeconds: 900
    maxHistory: 50
    allowedEnvironments: [SIT, UAT]
    requireExplicitEnvironments: [UAT]
```

`allowedEnvironments: []` 允許中央定義的任何 profile。`requireExplicitEnvironments` 在 execution validation 前拒絕隱含選取其列出的 profile。Draft 數量、到期時間、history 與 source size 均設上限。Source editing 亦要求 inspection 及既有已驗證 USER 授權流程。

### 遷移舊 Package inventory

Global `testdata` 及 profile `testdata` lists 已棄用，預設不使用。中央可暫時設定 `packagePolicy.legacyTestdata: true`，只為沒有 Manifest 的 Package 恢復舊 inventory。存在 Manifest 時完全採用 Package ownership，包括明確空 inventory。Migration warnings 會指出舊 ownership；相容 switch 應只在有期限的遷移期間使用。

Migration helper 依可信 Server 明確 registry 產生有版本的唯讀計畫。從 repository 執行，Python environment 需先安裝 `tools/requirements-migration.txt`：

```bash
python tools/migrate_package_config.py --config config/config.yaml --server-config /ABSOLUTE/PATH/att-server.yaml
```

將 `/ABSOLUTE/PATH/att-server.yaml` 換成部署 registry。Helper 掃描各已註冊 Package 的業務文字與受限 XLSX text，只建議屬於本 Package 且被引用的 descriptors；保留 Environment 明確空 lists，略過既有 Manifest，並報告缺失、未使用或不安全 imports。它只列出繼承預設值的欄位名稱，不輸出其值或 descriptor records。重複 mapping keys 或 YAML aliases 會令 inventory 失敗。工具不發布檔案，也不推斷註冊。

審閱各 proposed Manifest，補齊所需 descriptors，再執行完整 Package validation。透過 source workflow 或受控部署流程發布後，移除舊 Global inventories 並停用 `legacyTestdata`。

### API paths

以下路徑相對於 `/api/v1`，沿用已驗證 USER、origin、CSRF、source policy 與 request size 限制。

| Method 與 path | 結果 |
|---|---|
| `GET /packages/{id}/package-configuration/{source\|declared\|effective\|compare}` | 安全 projection；effective selection 可提供 `environment` query |
| `POST /packages/{id}/package-configuration/drafts` | 暫存 `{source, expectedManifestDigest}` |
| `POST /packages/{id}/package-configuration/drafts/{draftId}/{validate\|review\|publish\|discard}` | 以 `{}` 推進私有 draft |
| `GET /packages/{id}/package-configuration/history` | 只供 owner 存取的有上限 revision list |
| `POST /packages/{id}/package-configuration/rollback` | 暫存 `{revision, expectedManifestDigest}`，等待審閱 |

來源 revision 衝突回傳 `409`；source 存取或 Environment policy 拒絕回傳 `403`；draft 到期或不可用回傳 `404`；draft 容量不足回傳 `429`。發布不會執行測試或修改中央 profile definitions。

## CLI Reference

現行 CLI 可執行 Native Case、驗證 Package resource、診斷 Flow 或 Tool，以及執行 Flow 或 Tool 的 Load workload。既有 Package 格式請參閱[舊版 CLI 指南](legacy/cli.zh.md)。

### 編譯 source checkout

Source checkout launcher 會執行已編譯的 class。若 class 缺失，請編譯 Java CLI、Engine、Worker 和 Server API module：

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

macOS/Linux 使用 `./att.sh`，Windows 使用 `att.bat`。Packaged release 內含已編譯的 application JAR，使用 Java 8 或更新版本；Server WAR 使用 Java 17 或更新版本。

### 常用命令

| 工作 | 命令 |
|---|---|
| 顯示 help | `./att.sh` 或 `./att.sh help` |
| 顯示版本 | `./att.sh version` |
| 驗證 Package | `./att.sh validate --package` |
| 驗證指定 dependency closure | `./att.sh validate --selected <selection>` |
| 執行一個 Native Definition | `./att.sh --local -p default run native-case quick.start.single --resolved-case-id default` |
| 執行 Native Suite | `./att.sh --local -p default run native-suite --all` |
| 診斷 Flow | `./att.sh debug flow <id>` |
| 診斷 Tool | `./att.sh debug tool <id>` |
| 執行 Flow 或 Tool workload | `./att.sh load --debug flow <id> --load-template builtin:single-vu --preview` |
| 執行已聲明的 Load scenario | `./att.sh load examples/load/closed-smoke.yaml` |
| 重建已完成 Run 的報表 | `./att.sh report --run-id <id>` |
| 產生本地文件 | `./att.sh docs` |
| 封存最近完成的 Run | `./att.sh build` |
| 移除 ATT 說明的生成檔案 | `./att.sh clean` |

[測試撰寫](reference.zh/test-authoring.md)定義 Native selector 語法和 source ownership。Native Definition 綁定 Flow 與 Data Source；其 Case 可按 qualified ID、tag 或 Suite 條件選擇。

### 執行 Case 或 Suite

~~~sh
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
./att.sh --local -p default run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool
./att.sh --local -p default run native-suite --all --tag smoke --exclude-tag slow
~~~

`--dry-run` 只驗證並解析 selection，不執行 Actions。Native Suite 使用 `--rerun-failed` 會從最近符合資格且已完成的 Run 選取曾執行但失敗的 Case。`--fail-fast` 會在失敗後停止安排新的 execution。

### 驗證 Package

~~~sh
./att.sh validate --package
./att.sh validate --selected testcase/quick-start/single/testcase.yaml
~~~

Validation 會檢查所選 Definition、Flow、Tools、Testdata、Runtime Configuration、schemas 和 resource references。不會執行 Actions 或啟動 Load scheduler。

### 診斷 Flow 或 Tool

~~~sh
./att.sh debug flow common.payment.v1 --input debug/payment.yaml --env SIT
./att.sh debug tool sample.getAcDate --set arg.date=2026-11-23
~~~

`--input` 選擇 Debug input file。可重複使用 `--set input.path=value`、`--set vars.path=value` 或 `--set arg.name=value` 提供 typed value。Flow target 接受 input 和 vars；Tool target 接受 input、vars 及已聲明的 arguments。Debug discovery、受保護路徑、output 和 result 行為見 [Debug](reference.zh/execution-modes/debug.md)。

### 執行 Load workload

Direct Load workload 以 Flow 或 Tool 為 target。已聲明的 scenario 可包含通過驗證的 Flow 和 Tool workloads。

~~~sh
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load examples/load/closed-smoke.yaml --users 1 --duration 100ms
~~~

List 和 preview 不會啟動 traffic。[Load 模式](reference.zh/execution-modes/load.md)說明 pacing、Testdata、evidence、threshold 和 scenario composition。

### 執行 Server 命令

本機命令在目前機器執行。加入 `remote` 將支援的操作提交至 ATT Server：

~~~sh
./att.sh remote --server sit packages
./att.sh remote --server sit run payments --detach
./att.sh remote --server sit jobs
./att.sh remote --server sit watch J0123456789ABCDEF
~~~

Remote Package 名稱、Definition selection、configuration 和 scenario 路徑都是由 Server 解析的邏輯值；不會傳送用戶端絕對路徑或本機 output directory。[ATT Remote client](remote-client.zh.md)說明連線 profile、authentication、streaming 和 troubleshooting。

### 常用 options

Options 只適用於支援該 option 的 command。

| Option | 用途 |
|---|---|
| `--config <file>`、`--env <name>` | 選擇共用 Runtime Configuration 和 Environment |
| `--run-id <id>`、`--debug-id <id>` | 設定 Run 或 Debug output identity |
| `--output-dir <dir>` | 為支援的本機命令選擇 output root |
| `--format human` 或 `--format json` | 選擇 terminal output |
| `--quiet`、`--verbose` | 控制 progress detail |
| `--profile` | 寫入支援的 phase timing 和 counters |
| `--queue`、`--allow-parallel-runs` | 設定 output-root process concurrency 行為 |
| `--substitute-package-root`、`--substitute-external-basename` | 隱藏顯示診斷中的絕對路徑 |
| `--help` | 顯示 command-specific help |

Native Definition 可重複指定 `--resolved-case-id`。Suite selection 使用 `--all`、`--tag` 和 `--exclude-tag`。各 command 會驗證 options；不支援的組合會回傳錯誤。

### Exit code

| Code | 意義 |
|---:|---|
| 0 | Command 成功，沒有 FAIL、ERROR 或 INVALID |
| 1 | 一個或多個 Case 或 assertion 失敗 |
| 2 | Command、configuration 或 validation 無效 |
| 3 | Runtime 或 infrastructure failure |

Windows 使用 `att.bat`，並接受相同的 Native Case、Flow、Tool 和 Load arguments。

## 結果、報告與 evidence

### ATT Server Job artifact 發佈

Worker 檔案在 Server 核准前會留在私有目錄。Artifact producer 在寫檔前套用敏感資料保護。Archive 不是遮罩邊界：它驗證路徑、檔案類型、大小及 UTF-8 文字，然後原樣保留擷取到的 producer bytes。此邊界適用於 Native RUN（含 Debug diagnostics）、LOAD 及其 Server Job artifacts。

執行結束後，Server 非同步建立唯一的 `artifacts.zip`。Archive 狀態為 `PENDING`、`ARCHIVING`、`READY` 或 `FAILED`，與 Job result 分開；PASS/FAIL/ERROR/INVALID/CANCELLED 不會因發佈未完成而改變。驗證及原子發佈成功後刪除 loose output。ZIP 是唯一持久 artifact payload；metadata、audit、events 及 archive-status 記錄另行保留。

#### 發佈及敏感資料政策

Archive 接納一般、非 symlink 的 UTF-8 文字檔，副檔名包括 `txt`、`log`、`json`、`yaml`、`yml`、`xml`、`html`、`htm`、`css`、`csv`、`md`。Binary、無效 UTF-8、NUL bytes、巢狀 archive 及不支援格式會被排除；公開 manifest 不會列出被拒絕的檔名。Archive 將 JSON/YAML 視為 producer 寫入的文字，不會在發佈時解析、遮罩或重新序列化。Studio 會顯示 omissions，不會把不完整匯出稱為完整 output。

Logs、文字、XML、CSV 及生成的 structured results 由 producer 在寫檔前套用相關 secret、credential 及 path 保護；Archive 原樣保留結果 bytes。一般 business values（包括姓名或可能受規管的資料）若不符合 producer 的明確敏感規則便會保留；不提供任意 PII 自動偵測。操作 Job 的授權不等於 raw-secret 匯出權限，也沒有公開 raw-export 或 raw-file fallback。

Producer 端保護的敏感名稱包括 password/passphrase、token、secret、credentials、API/access/private key、authorization、cookies。Structured field name 內符合明確 secret text 規則的內容亦會受保護；secret/path projection 後名稱衝突會由 producer 拒絕，不會靜默覆寫 business field。敏感 business data 應放在明確敏感的欄位，或把其所屬 structured record 分類為 `secret`。Producer 必須在資料進入 archive input tree 前完成保護。

Engine 生成的 artifact data 會在寫檔前套用 path 及 secret projection。Archive 不檢查 semantic fields，也不改寫已知 paths。HTTP HTML preview 使用 opaque-origin sandbox、restrictive CSP、禁止 forms，且不授予 Studio-origin 權限。下載報告的 relative links 會依 archive 內檔案配置解析，前提是目標檔案存在。

#### Manifest 及 Resource limits

`export-manifest-v1.json` 使用 manifest version `1`、policy `att-export-approved-bytes-v1`；新 entry 的 decision 為 `preserved`（ZIP 使用 `att-export-approved-bytes-v1` publication policy）。ZIP 完整保留 producer 輸出的核准 bytes；敏感值必須在 artifact 生成前完成遮罩。每個 entry 記錄 logical path、media type、精確 byte size、SHA-256、report/preview eligibility、classification 及 publication decision。`complete` 和彙總 `omissions` 不包含被拒絕的檔名或值。ZIP、preview 及解壓檔案使用相同的已擷取 bytes；GET 不會再次獨立遮罩。

Inventory 不受 `server.maxArtifacts` 限制：最多 50,000 filesystem candidates、深度 64、10,000 已發佈 entries、每項 16 MiB、總 bytes 128 MiB、manifest 8 MiB。超出總量上限會使發佈失敗並保留私有來源。ZIP 預設使用 STORED；Server 設定可選有界的逐 entry DEFLATE 或 AUTO，並維持 200:1 ratio 上限。Reader 拒絕不安全路徑、control characters、encoded traversal、重複或大小寫衝突名稱、過大或高壓縮比 entries、未列入 manifest 的 entries 及 integrity mismatch。Writer 為單一有界佇列，同時最多八個 artifact reads。

Reader 在信任 manifest 前，亦會比較 ZIP central directory 與 local header。Link、special-file 或 Windows reparse attribute、file/directory prefix collision、覆寫 filename 的 extra field、comment、隱藏 prefix／record、重疊 entry、multipart archive 及 ZIP64 均會被拒絕。受限 export profile 只接受 STORED／Deflate entry 及 timestamp-only extra metadata；預設仍為 STORED。Manifest parsing 拒絕 duplicate key、尾隨 document、fractional version／size、未知 field，以及不符合 policy 的 omission 文字。ZIP 結構可讀並不代表已核准匯出。

#### 分離及還原 Job 主控台

Studio 可從 Live Output、Events 或 Job 詳情標題開啟非 modal floating console。使用者切換 Studio 其他頁面時它仍會保留；每個瀏覽器分頁只允許一個 console，切換至另一個 Job 必須明確確認。Minimize 會留下底部 chip，保留一條有界的分頁 SSE 訂閱，但不會渲染隱藏的事件列；Close 會釋放訂閱。兩者都不會取消 Job。主控台可用滑鼠或鍵盤移動及調整大小，小螢幕會改為全螢幕 sheet。

**Open full Job Details in a new tab** 使用已編碼的同源 route `#/jobs/{jobId}?standalone=1&tab=events` 及 `noopener noreferrer`，包含 Overview、Live Output、Events、Results、Artifacts、Metrics，預選來源 tab。舊 `/console?view=live|events` 書籤仍相容；切換 tab 保持同一 Job ID 及共用 SSE 訂閱。支援重新整理、直接連結及非根 context path；未授權或缺少 Job 使用既有登入或安全 unavailable 訊息。Floating console 行為不變。

#### 檢視及下載 Job archive

在 Job Details 開啟 **Artifacts**。報告清單獨立於分頁；使用 **View Report** 或 **Preview** 開啟內容。文字 viewer 提供唯讀、search、copy、line numbers，顯示最多 200,000 characters；**Load more artifacts** 載入後續頁面。**Download Artifacts (.zip)** 是唯一下載操作。解壓後可直接開啟 HTML，relative links 保持相同 archive hierarchy。

Index 亦會回報整份 archive 中 summaries/manifests、metrics/time series、execution evidence、recovery checkpoints 及其他檔案的 entry count 和 uncompressed bytes。Studio 會先顯示 primary report，再把其他項目放在預設收合的 categories；若安全 path 有 workload 與 execution 元件，execution evidence 會依 workload 和執行分組。搜尋會比對已載入頁面的 path 或 workload，載入更多頁面可擴大搜尋範圍。Artifact row 顯示精簡 filename，並可查看完整核准 archive path。

Server 會分別驗證 report 完整性、execution status、performance verdict 及 archive status。只有 canonical report 通過完整性驗證而且 `executionStatus: PASS` 時，LOAD ZIP 才會排除一般 recovery checkpoint；`NOT_MET` performance verdict 會獨立呈現，不會改變此規則。Cancelled 或 runtime-error 的 run 即使已提交完整診斷 report，仍會保留每個 scope 最新兩代 integrity-verified checkpoints。Report 缺失、不完整、損壞、中斷或無法使用時，每個 scope 最多保留最新兩代 verified checkpoints。不完整或損壞的 report bundle 會整組省略，並保留可預覽的 verified checkpoints，同時如實回報 archive omissions。Report commit 驗證完成前不會刪除 source checkpoints。

相同的大型 aggregate 與單一 workload series 共用 detail files 時，版本化 manifest 會記錄重用檔案數和精確省下的序列化 bytes。不同值或多 workload 執行仍保留各自 scoped files。

Authenticated archive index 僅在 ZIP 已驗證且為 READY 時回傳 `archiveSizeBytes`。Studio 在 ZIP download 旁顯示 human-readable size，不會為計算大小下載 ZIP 或發送 HEAD request。

ZIP download 在 JVM 的 OS temporary directory 建立 request-private copy，每份最多 140 MiB。Server 先驗證完整副本及所有 entry digest，再設定 download headers；response length 取自該副本，並從相同 open file 串流。每個 64 KiB buffer 在寫出前比對已保存的 SHA-256，因此 published pathname 後續被替換或原地修改，都不能以其他 bytes 取代 response。既有八個 concurrent reader 上限亦限制同時存在的 download copies，部署時須預留 temporary disk space。正常完成、disconnect 或 approval 失敗會關閉及刪除副本；process 突然終止可能留下 temporary files，須由 OS 或 operator 清理。Entry preview 仍使用獨立 request-scoped verified ZIP handle 及逐 entry digest/CRC 檢查。

**Jobs and results → Metrics** 的 **Artifact archive** 顯示獨立 Server 發佈指標，即使 terminal ERROR Job 沒有 Load result 亦可查看。Job detail 和 archive index 都提供 typed `archiveMetrics`（version 1），獨立持久化，不會混入 Worker execution metrics。Attempts/retries 跨重啟保留；時間和 resource 用量描述最新 writer attempt。Queue wait 至 writer 開始時結束；`publicationWallMs` 包括 writer finalization 和 cleanup，但不含 queue wait。Timing buckets 分別量度 scan、validation 和 byte capture、ZIP compression/write、verification/file-fsync 和 private/staging cleanup。Source file count/bytes 計入被拒絕的 regular file；included/omitted count 描述該次發佈。Ratio 為 approved uncompressed entry bytes 除以完整 ZIP bytes（包含 manifest），並非節省空間百分比。Source byte sum 最多為 signed 64-bit 上限。

`writerCpuMs` 僅量度 Server writer thread，且須 JVM 已支援並啟用 thread CPU timing。`writerAllocatedBytes` 是該 thread 的 allocated-byte delta，不是 retained memory、JVM heap 或 peak memory；不會量度 Worker CPU 或主動啟用 JVM instrumentation。不支援／未啟用的量度或未執行的 stage（例如重用既有 verified ZIP）回傳 `null` 並顯示 Unavailable，不會填入假零。真正 empty input 的 file/entry count 為零，但 ZIP 仍有 manifest。Outcome 和 failure phase/reason 僅使用固定安全 code，不包含 filename、data 或 exception message。RUNNING 代表 attempt 尚未完成；cleanup 失敗時為 CLEANUP_PENDING，但已驗證 ZIP 仍可下載。Metrics store 失敗會記錄安全 warning，不會改變 publication safety 或回退至 raw output。

解壓後的 `file://` 報告由瀏覽器決定如何開啟 linked file types；例如 Chromium 可能下載 linked YAML，而非直接顯示。Link 仍指向同一份已核准的解壓 bytes；Studio 會在 viewer 中以 inline text 提供這些 entries。

| 方法 | 相對於 `/api/v1` 的路徑 | 結果 |
|---|---|---|
| GET | `/jobs/{jobId}/artifacts?offset=0&limit=100` | Archive 狀態、分頁 entries、完整 primary reports 及安全 omissions |
| GET | `/jobs/{jobId}/artifacts/archive.zip` | 已驗證的最終 ZIP attachment |
| GET | `/jobs/{jobId}/artifacts/view/{entryPath}` | Manifest 核准的 HTML/CSS 或 inline text，保留相對目錄 |
| POST | `/jobs/{jobId}/artifacts/retry` | 私有來源仍存在時，安排 terminal Job 重試發佈 |

所有路徑均要求共用 OPERATE capability。Active USER 可存取其他 USER 建立的已核准 Job archives；creator 僅作 audit metadata。ADMIN-only 沒有隱含操作權限。Authentication、disabled-account 檢查、local CSRF 及設定的 Origin 政策均適用。回應使用 private/no-store；loose-file download 回傳 404。未完成或不可用 archive 回傳 `ATT-SERVER-ARCHIVE-UNAVAILABLE`（409）；read capacity 已滿則回傳 `ATT-SERVER-ARTIFACT-CAPACITY`（503）。

#### Recovery 及 expiry

失敗時保留私有 output，並在可用時選擇 **Retry archive**。同時提出的 retry 會合併；若上一個 writer 正在結束，已接受的 retry 會保留為下一次 publication，不會被靜默丟棄。Startup 及每五分鐘的 reconciliation 使用持久化 recovery-needed flag 和 cursor，每次最多安排 64 個未完成的 terminal Jobs，並使用與 retention 分離的 maintenance executor。ZIP 驗證及 loose-output/staging cleanup 全部成功後才清除 flag；可重試的 FAILED 或中斷工作仍會納入；quarantined Jobs 不會自動納入。Database migration 只將既有 history 納入一次，再以相同有界批次處理，不會每次重掃已成功發佈的全部 history。Recovery 會驗證已原子提交但狀態未更新的 ZIP，並清理 staging 及剩餘 loose output，不會直接提供未封存檔案。若來源已不存在，損壞 ZIP 無法重建，archive 會維持不可用。

Worker 的 terminal RESULT 不代表 execution cleanup 已完成。Publication 會等到記憶體中的 execution 結束並釋放 admission lease。Server 每個 Job 最多保留 1,024 個已觀察的 process identity，包括 terminal delivery 前及 cancellation/error cleanup 時發現的 child。Graceful stop 和 forced escalation 使用相同 identity，即使 parent 已退出亦然；所有已觀察的 process 退出前，admission 和 publication 仍受 fence 保護。Enumeration 或 identity persistence 失敗不會授權 publication。獨立確認所有已知 process 均停止後，Server 先持久化 quarantine，才釋放 admission（包括 LOAD capacity）及移除 active Job fence。每秒執行的有界 reconciliation 會重試延後的 release，包括暫時的 quarantine-store failure；quarantine 成功保存前仍保留 admission。Quarantine 跨 restart 保留，阻止 publication 和 retention，archive reason 為 `worker_unverified`。即使 filesystem status 為 READY，archive index/read routes 仍會遵守持久化 quarantine，並停用一般 archive retry。Retry 不會清除 quarantine：operator 必須獨立確認完整 process containment，才可進行 offline recovery；本版沒有清除 quarantine 或匯出 raw output 的 API。Restart 後，publication 和 retention 會檢查 Worker PID/start time 及已保存的 observed-process ledger；相符的 live process，或 start identity 未知的 live PID，會阻止 publication/deletion。Archive status `FAILED`、reason `worker_active` 會保留 private output；請在 Worker 及已記錄的 descendant 停止後 retry。已知但不同的 start time 表示 PID reuse，而非原本的 Worker。Observed-process fence 尚不能證明在 observation 前已逃離的任意 detached descendant 已被限制及停止；完整 OS-level containment 及跨平台 acceptance 仍待完成。

選用的 `server.jobStorage` 配額可在 `dataDir/jobs` 超過高水位時刪除完整 Job。它預設關閉，只測量該目錄，不跟隨符號連結，測量未完成時不會清理。回收依最舊的已結束 Job 開始，並同時刪除 Job 目錄與 H2 列。`partial` 清掃會從上次檢查的最後一個候選之後繼續；隔離中與 artifact recovery 中的 Job 不會進入候選頁，因此受保護的前綴不能佔滿預算。已包含最舊候選的一輪若走到結尾且用量仍高於目標，結果為 `quota_unmet`。執行中、佇列中、隔離、復原中、使用中，以及仍在 `minRetentionHours` 內的 Job 會保留。配額不會壓縮 H2，也不會只因單一 Job 過大而刪除它。既有 `server.jobRetentionDays` 會連同 ZIP 一併到期刪除完整 Job；選取有界 expiry page 前會先排除 quarantined Jobs，避免其長期保留阻止其他已過期 Job 清理；active readers/writers 存在時會延後該次 sweep，不會阻塞 cleanup thread 或刪除使用中的 archive。Classification 及發佈結果寫入不含 raw values 的 audit。失敗的發佈不會刪除唯一未封存的來源。

### 查找 RUN artifacts

已完成的 Native RUN 會將 manifest、event stream、reports 和 Case execution evidence 寫入唯一 Run directory：

~~~text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
├── workbooks/native-cases.result.xlsx
└── executions/<EXEC.ID>/...
~~~

標準 XLSX 是生成的結果檔，與測試輸入格式無關。Server Job 會將它列為 binary download，並停用文字預覽。HTML report 和 CI outputs 會保留 Definition ID、qualified Case ID、Flow ID、source revisions、execution ID 及可用的 source-record provenance。

### 閱讀 Case 結果

HTML report 顯示 Case identity、status、duration、Expected 和 Actual values、Action results 及有界 execution-log previews。每個 Action result 均包含最後 render 的 description 和 status。`report.html.caseLogInlineLimitBytes` 控制 inline preview；設定為 `0` 時只保留 artifact link。`report/junit.html` 提供人類可讀的 JUnit view。

每次 Case execution 對應一個 JUnit `<testcase>`。PASS 沒有 failure 子節點；FAIL 對應 `<failure>`；ERROR 和 INVALID 對應 `<error>`；SKIPPED 對應 `<skipped>`。文字會做 XML escaping。`report.junit.caseLogEmbedThresholdBytes` 控制 inline log；設定為 `0` 時一律使用相對連結。

### 查看 CI summary

`ci/summary.json` 使用 `att-ci-summary/v2.1`，記錄 Run identity、environment、時間、aggregate status/count、duration statistics、Case records、diagnostic counts、report paths 和 input-manifest hash。Native Case entries 亦記錄 Definition/Flow identity 及 source revisions。Generated Run manifest 使用 `att-run/v2.1`。

### 診斷 Tool evidence collectors

Evidence collector 是 operation 後的 observability，不是 primary Tool result。使用 `onFailure: continue` 時，primary Action 可維持 `PASS`，而 collector 獨立記錄為 `ERROR`。

~~~yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
~~~

請查看 `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`。Record 包含 status、success、invocation ID、result、error，以及有界和已遮罩的 operation evidence。Failed collector evidence 不會發布 raw input、payload、argv、output、resolved command text 或 failed result。Projection 規則和數值上限見[Limits, Security Guarantees, and Advanced Diagnostics](reference.zh/appendices/limits-defaults.md)。

重試時請查看 `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`。後續 attempt 成功後，較早失敗的 collector records 仍會保留；top-level record 代表最後或勝出的 attempt。

### 重現 RUN

`run.yaml` 記錄 ATT/build identity、runtime 資訊、Environment、時間、status、outputs，以及有效 configuration、Package files、Tools、SQL resources、Testdata 和 schema registry 的 SHA-256 inputs。使用保留的 revision 和 manifest identity 比較 runs。`report --run-id <id>` 可從已完成 evidence 重建 HTML reports，不會執行 Package。

### 閱讀 Case logs

Structured Case log entries 使用 YAML。ATT 會保留內嵌文字的 line endings，不會解析或重排 business text。Live console 和保存的 `case.log` 使用相同的 rendered text。ERROR、FAIL 或 INVALID block 會使用 `【!!!!!】` marker；PASS、SKIPPED 和 informational blocks 不會加上 marker。

### 離線 SUT Metrics 報告

完成的 LOAD 若有 SUT collection evidence，可發佈版本化的離線報告 archive。Publisher 會驗證 manifest 和每個 normalized observation file，逐 byte 保留已封存的 source archive，並在驗證通過後發佈報告。`att-load-report-extension/v2` manifest 引用 `att-load-offline-observations/v1`。Observation records 包含 source、metric、profile、target、UTC window、phase、單位、quality 和 checksums。Time series 提供有界 chart previews；business metrics 提供數值、scope、Workload identity、mapping quality 及 unmatched-row count。

若 Job 的 approved execution definition 包含 Metric Groups，報告會發布安全的 group metadata 和 assignments，不含 raw selectors。未分組和有歧義的 observations 仍會連同 diagnostics 列出。報告可離線使用並套用 restrictive Content Security Policy；它不會評估 performance thresholds。如有 `att-load-verdict/v1` record，報告只顯示該 record 的 state 和 evidence reference，不會重算 verdict。驗證發佈成功後，可透過 authenticated Job download route 取得 archive。

## Validation 和 troubleshooting

### 選擇第一個 diagnostic step

執行 `./att.sh validate --package`，先查看回報的檔案和欄位。**Resolved Case** 是 Test Case Definition 中可選取的一個 Case。**Case execution** 是所選 Resolved Case 的一次執行。Runtime failure 請檢查 execution status 和 message，再查看 `case.log`、`case.yaml` 和 Action evidence。維護以較早模型編寫的 Package，請使用[舊版文件](legacy/README.zh.md)。

### 驗證 Native input

Package validation 會檢查 Definition、Flow、Tool、Testdata、Runtime Configuration、已登記 schema 和 semantic reference。它不會執行 Tool、提交 Server Job 或啟動 LOAD scheduler。Validation 為唯讀，也不會建立 authoring input。

使用 selected-validation option 可快速檢查一個 Definition 或 Resource target。分享 Package 或升級 build 前，請驗證整個 Package 和每個所選 Environment。[Schema 和 Version Matrix](reference.zh/appendices/schema-matrix.md) 列出此 release 使用的契約。Diagnostic 會保留 source file、field 和結構化位置；automation 應使用穩定 diagnostic code，而非 message text。

| 類別 | 常見原因 | 修正方式 |
|---|---|---|
| `ATT-TC` | Definition 缺失、Data Source 無效或 Case ID 重複 | 檢查 Definition ID、來源紀錄和 resolved Case ID |
| `ATT-CTX` | Context path 不存在或有歧義 | 檢查 requested path、目前 scope 和建議的 canonical path |
| `ATT-FLOW` | Flow 不存在、Action 無效或 dependency 未解析 | 檢查 Flow ID、有序 Actions 和被引用的 Resource/檔案 |
| `ATT-CFG` | 未知欄位、重複 key 或型別錯誤 | 對照 active Configuration schema |
| `ATT-TOOL` | Argument 不存在、process failure 或 parse failure | 檢查 Tool contract、exit code 和有界 output evidence |
| `ATT-PATH` | Identifier 不合法或 path 越界 | 讓 identifier 和檔案留在配置的 Package root 之下 |
| `ATT-RUN` | Timeout、非零 exit code 或 runtime exception | 查看 Case log 和 Action evidence |

### 常見問題

#### 為何 Case ID 被拒絕？

Resolved Case ID 必須唯一且符合 path-safe 規則。Excel source 請將 ID 存為文字，並檢查空白、前後空格、保留裝置名稱和大小寫摺疊後的重複 ID。

#### 為何 Context variable 失敗？

ATT 會將不存在的 path 回報為 authoring 或 runtime error。請檢查確切 scope、source-field mapping、Action ID 及該值何時可用。已宣告但空白的 optional value 合法，並會渲染成空字串。

#### 為何 Case 失敗或出錯？

Assertion 為 false 時，Case execution 狀態為 FAIL。Expression syntax、Tool、timeout、parse、I/O 或 runtime exception 會回報 ERROR。請查看 Action evidence 找出原因。

#### 沒有 Server 可以開啟 report 嗎？

可以。請保留整個生成的 run directory，讓相對 artifact link 仍可用。

### 安全與結構化 diagnostics

不要把密碼、token、private key 或敏感客戶資料放在 Definition input、Tool argument、log 或文件範例中。使用核准的 secret injection，並在分享前檢查 report。

Machine-readable validation 使用 `att-validation/v2.1`。Diagnostic 包含穩定 code、severity、file 和 field，有提供時亦包含 source location 和安全建議。Code 才是支援的 automation interface；message wording 可以調整。

## CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 和 release archive，並 smoke-test 解壓後的 local package。Reference generation 另外需要 Python 3 與 Pandoc。它會輸出供 CLI 使用的 `att-4.3.1-local.tar.gz`、包含 WAR 和部署指南的 `att-4.3.1-server.tar.gz`，以及 source archive `att-4.3.1-src.tar.gz`。每個 binary archive 均須小於 25 MB。Local package 不再包含 WAR 或其中重複的 runtime library。

### Runtime dependencies

CLI、Engine 及 Worker 仍相容 Java 8。ATT Server WAR 需要 Java 17+ 及外部 Tomcat 10.1+。ATT 不內置 CLI integration 所需的 JDBC driver；請將 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default CLI build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

### Studio inspection cache

Resource metadata discovery 會延後處理 DB/MQ/SSH/HTTP Helper 支援的 `${ENV:NAME}`，不讀取 process secret。Resource summary 將 `metadataState` 與 `executionReadiness` 分開：`not-validated` 不代表允許 execution；`dependency-unresolved` 會為可探索的 transitive Helper reference 提供不含值的 `ATT-RESOURCE-ENV-REQUIRED` diagnostic。這些 diagnostic 只列出 requirement，並非已確認缺少 variable 或 network failure。Explorer 顯示有效 environment 與 remediation，同時保留安全 metadata。原有 Resource `state: ready` 只描述 metadata，不代表 execution readiness。

Discovery 仍執行 root YAML/schema/profile 檢查及 shared-config path boundary。確認 boundary 後，DB/MQ/SSH/HTTP Helper descriptor 格式錯誤或缺失會獨立處理，保留正常 Helper 及不相關 resource metadata，並在 Explorer 提供不含值的 `ATT-RESOURCE-HELPER-INVALID` / `ATT-RESOURCE-HELPER-UNAVAILABLE` warning。失敗 descriptor 不會建立可用 Helper binding。Explorer 最多回傳四個個別 Helper failure diagnostic，另加其餘失敗數量。Discovery 每個 Helper type 最多 256 個 descriptor、每個 descriptor 3,000,000 bytes、每個 type 16 MiB；unsafe/symlink path、重複 path/Helper ID、trusted-schema failure 及超出限額仍會失敗。SSH Helper descriptor 缺失或無效時，Tool group 會保留 logical binding 供 discovery 使用。Explorer 將 SSH binding 和 ENV requirement 經 command-backed Tool 傳遞至相依 target；unresolved binding 不代表允許 execution。Tool-group 結構／schema failure 仍會失敗。Configuration Effective 和 Compare（包括 parsed snapshot cache）現在使用相同的 value-free discovery policy。Helper 缺失或格式無效時，仍保留有效 metadata 並顯示 structured dependency warning；`state: degraded` 和 `executionReadiness: not-validated` 均不允許 execution。ENV 名稱代表 requirement，不表示 variable 已確認缺失：這些 view 不會讀取 process secret value。Root configuration、profile、unsafe path、trusted-schema failure 和 inspection limit 仍 fail-closed。缺失的 Helper descriptor 亦參與 content reconciliation，新增或修復後會使 cached metadata 失效，毋須重啟。僅載入所選 target dependency 的 strict loading，以及統一 automatic/manual reindex operation 尚未實作。明確 validation 和 execution 仍使用 strict configuration loading，因此不相關的 active Helper 缺少 variable 時仍可能阻塞。Metadata-only configuration 不可執行。請在 Tomcat/Worker process environment 設定所需 variable，然後重新 validate；不要加入 production placeholder credential 只為了讓 browsing 成功。

Studio 將 Resource 分頁、Detail、安全 Source 及一般 Configuration projection 保存在 session memory。共用 cache 最多保留 128 個 response 及 8 MiB encoded payload；單一 response 超過 1 MiB 不會加入 cache。資料在 15 秒內視為 fresh；重新開啟較舊 view 時先顯示 cached projection，再由 background read 更新。相同 in-flight read 會合併；取消一個 consumer 不會取消其他 consumer 的 request。返回 view 時保留搜尋、排序、分頁及捲動狀態。

每次重用前先檢查 authenticated inspection context。Identity、session、role 或 inspection policy 改變會清除舊 response。Package/environment selection、明確 refresh、logout、頁面暫停、新觀察到的 package revision，以及 stale cursor 或存取錯誤，會使相關 memory 失效。Configuration 和 Source content 不會寫入 localStorage、IndexedDB、service worker 或 HTTP cache。Auth/CSRF response 及 `includeAllFields` administrator response 不會加入 query cache。HTTP response 仍遵守 `Cache-Control: no-store`。

每個 inspection Worker 仍逐一處理 request，並保留兩個 Resource inspector。Dispatch 優先選擇已保留相同 package/config/environment 及 policy key 的 idle Worker，再使用其他 idle Worker。Parsed Configuration snapshot 另有每 JVM 四個 entry 的 LRU，以及 32 MiB weighted retention budget；Worker heap 設定仍是 JVM 的硬性上限。重用時會對 root configuration、所選 Helper/Tool Group/Testdata dependency 及 schema tree 作 bounded content reconciliation，因此 rename、delete、相同 timestamp 的內容替換及未知 dependency 改動均會重新載入。Cache 錯誤會回退至原有 uncached validation。每個 request 均重新建立 permission-checked projection；administrator projection 不會進入一般 cache。

Optional `server.inspection.warmup` 預設為 `enabled: false`、`workers: 1`、`keys: []`。`workers` 範圍為 1 至 `server.inspection.maxConcurrent`。每個 key 包含 `packageId` 及 optional Engine `environment`；key 數量不得超過 warmup Worker 數量的兩倍，亦不可重複。不設定 `environment` 可預熱 Explorer 使用的 package-default index。Keys 分配至各 Worker，每個最多兩個；沒有 keys 時，所選 Worker 會預熱第一個 registered package。Package 缺失或 preload 失敗不會阻塞 Tomcat startup。Warmup 會避開 interactive demand，每個 key 最多 retry 三次並加入 backoff；daemon 更換後會重新預熱，shutdown 時停止。所有 Worker 均忙碌時，interactive traffic 可以取消 preload。

`GET /api/v1/metrics` 遵循既有 Server authentication policy，包含 `inspection.server` 及最新的 bounded `inspection.workers` snapshot。Counter 與 total nanoseconds 涵蓋 HTTP handling、queue、process launch、dispatch、processing、Configuration load/parse、Source request、Definition/YAML parsing 及 index build，亦提供 cache hit/miss/eviction、watcher invalidation/overflow、watched directory、retained key、instrumented read bytes、protocol response bytes、heap-pool peak、GC 及 process CPU。每個 Worker 最多保留 16 個 opaque package/environment aggregate，記錄 request count、processing time、index build、Configuration load 及 instrumented read bytes。Package key 經 hash；counter 不包含 path、configuration value 或 file content。Byte counter 代表已 instrument 的 inspection read，不等於 OS physical I/O。Process-launch timing 不包含 JVM readiness；dispatch 包含 readiness 及 protocol transport。Benchmark 另行記錄 sample p50/p95。若 optional inspection telemetry 無法放入設定的 response-byte budget，Worker 會省略該 snapshot，Server 同時清除 routing hint；metrics 不會放寬 response limit，亦不會令原本有效的 response 超限。

Display snapshot 不會授權 execution。Debug/Quick Load approval 及 Worker execution 仍保留 authoritative package revision check、`ATT-SERVER-DRAFT-STALE`、綁定 principal 的 cursor，以及 stale cursor HTTP `409`。設定例子見 [Server 部署](server-deployment.zh.md)；量測程序見 [inspection benchmark](system-design/web-ui-inspection-inline-jobs.md)。

### Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

### CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定的 DB/MQ Logical ID 讓同一個 Flow 可以在 SIT、UAT、PREPROD 間重用，而無須修改 Action 定義。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。

## Appendix A — schema 與 version matrix

現行 schemas 由版本相符 ATT Engine JAR 內嵌 registry 提供；repository 的 `schemas/catalog.yaml` 是其 build input。

| Artifact | 現行 schema |
|---|---|
| 排程建立 | `att-schedule/v2` |
| Native RUN 定義 | `att-execution/v2` |
| Native Test Case Definition | `att-testcase/v1` |
| Global configuration | `att-config/v2.15` |
| Package Manifest | `att-package/v2` |
| Testdata descriptor | `att-testdata/v1.0` |
| DBHelper | `att-dbhelper/v2.6` |
| MQHelper | `att-mqhelper/v1.3` |
| HTTPHelper | `att-httphelper/v1.1` |
| SSHHelper | `att-sshhelper/v1.0` |
| Package Tool | `att-tool/v1` |
| Package Tool Group | `att-tool-group/v3.0` |
| Flow | `att-flow/v3.6` |
| Debug input | `att-debug/v1.2` |
| Load scenario | `att-load/v1.9` |
| LOAD observability binding | `att-load-observability/v1` |
| SUT Metrics Data Source | `att-sut-metrics-source/v1` |
| SUT Metric Source acquisition and output mapping | `att-sut-metrics-metric-source/v1` |
| SUT logical Metric Group | `att-sut-metrics-group/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v2` |
| SUT Metrics execution binding | `att-sut-metrics-snapshot/v1`、`att-sut-metrics-snapshot/v2` |
| Server-private SUT Metrics execution payload | `att-sut-metrics-execution-payload/v2` |
| SUT Metrics collection manifest | `att-sut-metrics-manifest/v1` |
| SUT Metrics manifest integrity history | `att-sut-metrics-manifest-integrity/v1` |
| SUT Metrics normalized observation | `att-sut-metrics-observation/v1`、`att-sut-metrics-observation/v2` |
| SUT Metrics series data | `att-sut-metrics-series/v1` |
| SUT Metrics threshold evaluation record | `att-sut-threshold-evaluation/v1` |
| Load preset | `att-load-template/v1` |
| Load bindings | `att-load-bindings/v1` |
| Load summary | Engine/CLI result contract；封存報告使用 `att-load-summary/v1.3` |
| LOAD report detail files | `att-load-detail-manifest/v1`、`att-load-detail-reference/v1`、`att-load-detail-series/v1`、`att-load-detail-series/v2`、`att-load-workload-detail/v1` |
| Run manifest | `att-run/v2.1` |
| 精簡 CLI 階層式 validation report | `att-validation/v1` |
| Package validation JSON | `att-validation/v2.1` |
| CI summary | `att-ci-summary/v2.1` |
| JUnit XML | `att-junit/v2.1` |

Engine 會從 classpath registry 讀取 schema bytes。Compiled validators 以 registry digest 和 schema resource 為 key，保存在有界記憶體 cache。Runtime 唯一權威來源是內嵌 registry；`ATT_HOME/schemas/` 及 Package-local `schemas/` 不能覆寫它。Validation revision 會綁定 Engine/registry identity、Package files、shared configuration files 和選定 Environment。Worker 處理請求前會驗證 Server 傳入的 registry identity。

SUT Metrics Data Source schema 定義已登錄的 connection。Metric Source schema 描述一次 acquisition 及其 declared output mapping，不會登錄另一個 Data Source、DBHelper 或 Package Tool。Server 私有 execution payload 會把 executable fetch/call data 綁定至已批准的 Job execution definition，且不會經 API 或 report 回傳。離線報告 metadata 及外部 verdict records 使用 `schemas/api/` 下獨立的 Server contracts，不屬於 Engine execution registry。詳見[離線 SUT Metrics 報告](reference.zh/results-reports-evidence.md)。

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

在二進制發布中，`att.bat` 會找到有版本號的 runtime JAR，例如 `lib\att-local-4.3.1.jar`，只需要 Java 8+。每個 `ATT_HOME/lib` 只保留一個 `att-local-<version>.jar`，讓啟動器能明確選取 runtime。源碼樹中，`att.bat` 會在 Maven 位於 `PATH` 時使用 Maven；沒有 Maven 時，需要已有的 `target\classes`。先用 `att.bat version` 確認啟動器後再校驗套件。

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
