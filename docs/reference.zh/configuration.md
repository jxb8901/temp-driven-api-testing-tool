# Configuration 與 environments

本頁是作者編寫配置時的權威閱讀參考。[`schemas/`](../../schemas/) 目錄中的文件仍是機器可讀契約。模式校驗會先於跨字段和文件系統校驗執行。

## 按任務查找配置

| 任務 | 從這裡開始 |
|---|---|
| 選擇 SIT、UAT 或其他 Resource profile | [選擇 environment profile](#選擇-environment-profile) |
| 設定輸出、Run ID 或報表默認值 | [設定 package-wide options](#設定-package-wide-options) |
| 查找 Tool 或 Helper contract 的 owner | [Configuration owners](#configuration-owners) |
| 遷移或校驗 schema | [Schema catalog](#schema-catalog) 和 [Migration notes](appendices/migrations.md) |

## 配置層與優先級

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

Timeout/Retry precedence 與 eligibility 見 [Reliability](reliability-execution-control.md)。CLI 的 `--output-dir` 和 `--run-id` 會在一次命令中覆蓋相應默認值。一個層級中合法的字段，若放在別的層級中也會被拒絕。

## 使用 `X-` 忽略或停用 ATT 配置項

在可選的 ATT 配置字段，或 ATT 擁有的 keyed collection 條目名稱前加上完全小寫的 `x-`，該項便會視為不存在。適用於現行配置物件和 keyed collection，例如 `tools`、environment profiles、Tool `arguments` declarations、`actions`、Action `evidence` collectors、report columns 及 Debug Tool overrides。YAML 本身仍須能解析；但 ATT 不會對停用項進行模式校驗、解析引用、探索依賴、求值、建立物件、執行或發布。對 keyed collection，請加在 key 上。此規則不會停用或改名 Tool 呼叫時傳入的 argument values：

```yaml
schemaVersion: att-config/v2.12
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

## 選擇 environment profile

`att-config/v2.12` 是現行 profile 契約。Profile 可整組替換已配置的 DBHelper、MQHelper、SSHHelper、HTTPHelper 與 testdata descriptor lists。各 Helper 與 testdata descriptor 的設定方式及 [Testdata Registry 與 Input Mapping](test-authoring.md) 見對應頁面。

ATT 使用一份 common `att-config/v2.12` 加上 `environments` map 選擇環境；不通過修改 Action 或增加環境專用 Tool ID 來選擇環境。SIT、UAT、PREPROD 及 production-like 環境之間，Action 只保留穩定的 logical ID：

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
schemaVersion: att-config/v2.12
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

SIT 與 UAT 的 DBHelper 都保持 `id: orders`，只改變 JDBC URL 等 physical connection details；MQHelper 都保持 `id: payment`，只改變 host、queue manager、port 和 channel。包含完整 descriptor、pool 和安全 evidence policy 的可復制例子見 [`examples/environments/README.md`](../../examples/environments/README.md)。

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

當同一 package 只在基礎設施綁定上不同，應使用 profiles；當 testcase/template root、report policy 或 package structure 有意不同，才使用不同 top-level config。完整 config migration 見 [Migration Notes](appendices/migrations.md)。

## Schema catalog

[`schemas/catalog.yaml`](../../schemas/catalog.yaml) 是 active schema 的 source of truth。Package validation 檢查 registrations；封存 schema 不會成為 active runtime contract。完整矩陣見 [Schema and Version Matrix](appendices/schema-matrix.md)。

## 設定 package-wide options

以下 configuration example 與 field table 和英文版共用相同 contract；欄位名與 literal values 保留英文。

```yaml
schemaVersion: att-config/v2.12
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
execution:
  runIdFormat: "run-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
  debugIdFormat: "debug-${META.TARGET.type}-${META.TARGET.id}-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
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

### 設定 runtime 和 package 路徑

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | 現行版本：`att-config/v2.12`；上一版 schema 仍受支援。本例採用現行 schema。 |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty default profile name when `environments` is present; otherwise exposed metadata only |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `execution.runIdFormat` | unset | Optional outer Run/Load ID expression; exact `--run-id` wins |
| `execution.debugIdFormat` | unset | Optional standalone Debug ID expression; exact `--debug-id` wins |
| `execution.processOutput.memoryLimitBytes` | `65536` | Integer 1024–1048576; in-memory head/tail preview per stdout/stderr stream |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Integer from `memoryLimitBytes` through 1073741824; maximum bytes streamed to each process artifact |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |

`runIdFormat` 會產生外層 Run ID；Load 沒有指定 `--run-id` 時也會使用此 policy。它不會改變 Load 每個 iteration 的 `execution.execIdFormat`（`EXEC.ID`）。`debugIdFormat` 只產生 standalone Debug 目錄 identity。沒有設定時，Run/Load 維持原有 timestamp default，Debug 維持 `<type>-<targetId>` default。

Format 在 identity 發布或建立 output directory 前只求值一次。Run/Load 可讀取 `META.PACKAGE_ROOT`、`META.SOURCE.type/path`、`EXEC.STARTED_AT` 和 `EXEC.RUN_STARTED_AT`；Debug 另可讀取 `META.TARGET.type/id`。可使用純 identity-format built-ins，以及 `date.sysdate` / `date.systimestamp`；不可呼叫外部服務、random/sequence、filesystem 或 invocation state。正在生成的 identity 不可被讀取，例如 `${EXEC.RUN_ID}`、`${EXEC.ID}`、`${EXEC.OUTPUT_DIR}`、`${EXEC.VARS}` 和 `${EXEC.ACTIONS}` 會被拒絕。生成值必須本身是有效的單一路徑段，ATT 不會替它 sanitize。Run/Load 以及明確或配置的 Debug ID 發生 collision 時會 fail；legacy Debug default 仍會加 timestamp suffix。

```yaml
execution:
  runIdFormat: "run-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
  debugIdFormat: "debug-${META.TARGET.type}-${META.TARGET.id}-#{date.systimestamp(format='yyyyMMdd-HHmmss')}"
```

Run 和 Load 的 `--run-id <id>`，以及 standalone Debug 的 `--debug-id <id>` 都是 literal override，不會再當成 expression 求值。

### 配置報表輸出

| Path | Required/default | Constraints |
|---|---|---|
| `report.mode` | `append-to-copy` | `append-to-copy` or `none`; `none` skips result-workbook creation |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Supported keys: `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, `runTime`, `execId`; each value is a string column label |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |

### 配置 Resource 和 environment registries

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
| `execution` | `runIdFormat`, `debugIdFormat`, `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |

See [Migration Notes](appendices/migrations.md) for removed configuration fields.

## 標識符和路徑約束

Run ID 和完整 Case ID 會直接用作目錄名，ATT 不會對合法標識做 slug 化或哈希處理。

Run ID 必須非空、最多 128 個 Unicode 碼點，不能是 `.` 或 `..`，不得含前導/尾隨空白或尾隨 `.`，且不能包含 `/`、`\`、`:`、`*`、`?`、`"`、`<`、`>`、`|`、NUL、控制字符。Windows 設備名（如 `CON`、`NUL`、`COM1`、`LPT1`）會按大小寫不敏感方式拒絕。

`workbookId`、`groupId`、`rowCaseId` 同樣遵循相同字符規則。`workbookId` 與 `groupId` 不能含點號，因為點號用於分隔三個組件；`rowCaseId` 可含點號。Template 路徑相對 `templates.root`；file-content expression 只可讀取 package root 內一個 canonical、regular、UTF-8 file，並拒絕 absolute path、glob、dynamic locator 及 symlink escape。明確聲明的 resource file input 和 evidence output 路徑必須保持在各自配置根目錄內；ATT 會規範化並檢查包含性。

## Topology 與 secrets

Topology 可隨 descriptor/environment 改變。在 descriptor 支援處使用 `${ENV:NAME}` 注入 secret；不可提交，也不可將解析後的值公開在 META、report 或 diagnostic。缺少 required variable 時會指出 field/name，但不列印 secret。

## Cross-mode consistency

Run、Validate、Debug、Load 透過同一 effective configuration 解析所選 environment。`--env` 不是 Action branching，也不會產生 mode-specific helper ID。

## 分開的 Configuration files

若 package roots、report policy、Tool topology 或其他 config 刻意不同，可繼續使用 `--config config/environments/sit.yaml` 與 `uat.yaml`。若 package contract 相同而只改 resource binding，使用 profiles。


## `config.report.fileNamePattern`

### Context and legal forms

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

### Illegal or unsupported forms

但不支持如 `${RUN_ID}`、`${WORKBOOK_ID}`、`${ENVIRONMENT}`、`${EXEC.INPUT.caseId}` 等運行時值引用。


## Configuration owners

| Contract | Semantic owner |
|---|---|
| Workbook / Sidecar / Snapshot | [Test Authoring](test-authoring.md) |
| Template / Flow / Action | [Test Authoring](test-authoring.md) / [Actions](actions.md) |
| Tool command、call、arguments | [Tool](resources/tools.md) |
| DB descriptor | [DBHelper](resources/dbhelper.md) |
| MQ descriptor | [MQHelper](resources/mqhelper.md) |
| HTTP descriptor | [HTTPHelper](resources/httphelper.md) |
| SSH descriptor | [SSHHelper](resources/sshhelper.md) |
| Timeout / Retry | [Reliability](reliability-execution-control.md) |
