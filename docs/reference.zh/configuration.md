# Configuration 與 Environment

共用 Runtime Configuration 選擇 Environment、output 行為和全域 Resource bindings。Native Package roots 和 Package-owned resources 由[Package Manifest](package-manifest.md)定義。使用較早 configuration 欄位的既有 Package，請參閱[舊版 Configuration 指南](../legacy/configuration.zh.md)。

## Configuration layers

| Layer | Source | 管理範圍 |
|---|---|---|
| 共用 Runtime Configuration | `ATT_HOME/config/config.yaml` | Environment 選擇、execution defaults、report limits 和共用 DB/MQ/HTTP/SSH Resources |
| Environment profile | 共用 configuration 的 `environments.<name>` | Environment 專屬 Resource bindings |
| Package Manifest | `<PACKAGE_ROOT>/package.yaml` | Package identity、Flows、Test Case Definitions、Tools、Testdata 和 Package-owned files |
| CLI options | 目前的 command | 單次命令的 selection、Environment、identity 和 output overrides |

Package Manifest 管理 Package resource roots 和 Testdata。Native Package 不需要獨立 runtime configuration、schema tree 或 helper registry。

## 選擇 Environment

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

## 設定 execution identity 和 output

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

Native RUN result workbook 會寫入 `workbooks/native-cases.result.xlsx`。較早的來源 workbook report naming 見[舊版 Configuration 指南](../legacy/configuration.zh.md)。

## 使用 `x-` 忽略或停用 ATT 配置項

在 schema 支援的 extension location，以小寫 `x-` prefix 可於 validation 或 execution 前停用 ATT-owned fields 和 keyed entries。這不會移除 HTTP header 名稱或 DB parameters 等 user data。`runWhen: false` 是 runtime condition，不會停用 configuration fields。

## Resource bindings 和 credentials

共用 configuration 管理 DB、MQ、HTTP 和 SSH Resource descriptors。路徑必須位於各自配置的 runtime root 內。Environment profile 可整組替換各 Resource list。

在支援的位置使用核准的 secret-injection syntax。不可提交 credential，也不要把解析後的 secret 放入 log、report field 或 diagnostic output。缺少變數的訊息只會指出必需的變數名稱。

## 驗證變更

~~~sh
./att.sh validate --package --format json --quiet
./att.sh validate --package --config config/config.yaml --env UAT
~~~

Validation 會檢查 configuration schemas、profile references、Resource descriptors、Package resources 和 dependency paths。它是唯讀操作，不會提交 Job、執行 Tool 或啟動 Load scheduler。

Native Package roots 和 Manifest 欄位見 [Package Manifest](package-manifest.md)。共用 Resource contract 見 [Resources](resources/overview.md)。
