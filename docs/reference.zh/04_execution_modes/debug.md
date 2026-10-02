### 6.2 Standalone Debug

Debug 可在沒有 workbook Testcase 的情況下執行單一 Template、Flow 或 Tool。

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

不帶 target 執行 `./att.sh debug`，會列出 statically valid、可執行的 Tool、Template 和 Flow，附 copyable command。只會顯示實際存在的 regular non-symlink default sidecar。Discovery 會檢查 selected target dependencies，但不建立 Debug output，也不呼叫 Tool。可用 `--format json` 取得 machine-readable 結果。

Debug input 使用現行 `schemaVersion: att-debug/v1.1`。Top-level 支援 `case`、可選 `stage`、`inputs`、`vars`、`arguments`，以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；Template/Flow 的 `vars` 會以 typed bootstrap tree 評估，並在 target 開始前 seed canonical `EXEC.VARS`。Tool Debug 使用 `arguments`，不支援 `vars`。Framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。Schema migration 見 [Appendix C](../appendices/migrations.md)。

#### Standalone Debug bootstrap data

三種 input contract 有意分開：

| Debug 欄位 | Runtime destination | 用途 |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Flow/Template 直接消費的 business input |
| `vars` | initial `EXEC.VARS` | 可重用 Flow/Template 原本由 caller 準備的值 |
| `arguments` / `tools.<localKey>.arguments` | Tool argument contract | standalone Tool Debug 的明確參數 |

只消費 `EXEC.INPUT` 的 Flow 不需要 `vars`。若 Flow 正常由 parent Flow 先發布 `EXEC.VARS.refNo`，可用 scalar 或 typed structure 直接 debug：

Debug `inputs` 與 Run 使用相同 Testdata mapping 語法：以 `--env` 選擇已配置的 environment，再使用完整 `@{id}`／`@{id.path}` reference 或 scalar interpolation。ATT 會先解析再發布到 `EXEC.INPUT`；Reusable Template、Flow 或 Tool definition 內仍不可直接使用 Testdata marker。詳見[Testdata Registry 與 Input Mapping](../02_test_authoring.md)。

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

### Debug 排錯與 MQ payload 路徑

當 debug target 無法解析時，先確認 target kind 及 identifier，再用 `--input <path>` 排除 sidecar discovery 因素。Template/Flow debug 會尋找 `<target directory>/debug.yaml`；grouped Tool debug 會尋找 `config/tools/<group>.debug.yaml`。只會驗證 selected target 的 dependency closure，因此不需要無關 workbook 或 Case 檔案。

MQ 的 `file` argument 在 Debug、Run、Load 使用相同的安全路徑規則：

- 絕對路徑必須解析為 ATT package root 內的 regular file。即使 Load 尚未建立 lazy iteration workspace，也會直接按 package root 驗證。
- 相對路徑會在目前 active Case output directory 下解析；`..` traversal、symlink payload、symlink escape、directory 及非 regular file 會在 MQ connect/open/put/get 前被拒絕。
- 遺失或不安全 payload 會直接指出 payload path。尚未建立 MQ connection，因此應先修正路徑，再檢查 broker credential 或 queue 狀態。

按 output directory 分辨排錯階段：

| 症狀 | 檢查 |
|---|---|
| `Debug input file does not exist` | 在 selected target 旁加入 sidecar，或明確傳入 `--input`。 |
| `Debug input uses a historical schemaVersion` | 使用 active Debug schema；見 [Appendix C](../appendices/migrations.md)；只有 Flow/Template 需要 caller-prepared `EXEC.VARS` 時才加入 `vars`。 |
| `target` 或 dependency validation 失敗 | 確認 target type/id，並查看回報的 dependency field；不需要無關 workbook。 |
| MQ 回報 payload 遺失或不安全 | 核對 package 內的絕對路徑或 Case-output 內的相對路徑，移除 traversal 及 symlink。 |
| action 已執行但輸出不符預期 | 查看 `output/debug/<debugId>/` 下的 `case.log`、`result.yaml` 及 action artifacts，並對照 rendered inputs 與 selected environment。 |

Load 專用的 evidence retention（`metrics`、`failures`、`samples`、`all`）不適用於 standalone Debug invocation。Debug 會在自己的 debug directory 保留 invocation result 與 artifacts；同一 target 若由 load run 執行，請參考 Chapter 4 的 Load evidence retention 章節。

#### CLI configuration examples

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
