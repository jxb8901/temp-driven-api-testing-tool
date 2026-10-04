# CLI 參考

## 命令

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

## 命令語法

表格中使用 Linux/macOS 啟動器 `./att.sh`。Windows 上使用 `att.bat`，命令與選項相同。`att.bat snapshot`、`att.bat validate` 和 `att.bat docs` 不會觸發配置的 testcase Tool。Windows 校驗會檢查 `.sh` 文件是否存在並路徑是否安全，跳過 POSIX 啟動/可執行兼容性，並輸出一條警告列出受影響 Tool；一次校驗 PASS 並不證明這些腳本能在 Windows 上運行。運行前請提供並測試 Windows 原生等價物。二進制發布要求 Java 8+；源碼樹 `att.bat` 會在可用時使用 Maven，否則要求存在 `target\classes`。

| 語法 | 說明 |
|---|---|
| `./att.sh` 或 `./att.sh help` | 顯示幫助 |
| `./att.sh version` | 輸出版本 |
| `./att.sh snapshot` | 未指定 selector 時遞歸生成 `testcase.root` 下所有 Snapshot；等同於 `--all` |
| `./att.sh snapshot --suite <xlsx>` | 生成一個同名 XML Snapshot |
| `./att.sh snapshot --all` | 遞歸生成 `testcase.root` 下所有 Snapshot |
| `./att.sh snapshot --suite-dir <dir>` | 在某目錄下遞歸生成 Snapshot |
| `./att.sh validate --package` | 校驗整個包；默認範圍 |
| `./att.sh validate --selected <selection>` | 校驗選中依賴閉包 |
| `./att.sh validate --package --format json` | 向 stdout 輸出單個校驗 JSON 文檔 |
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
| `./att.sh load` | 發現 `load/` 下有效的 `att-load/*` scenario；報告無效的已聲明 scenario |
| `./att.sh load <scenario.yaml> --quiet` | 抑制定期實時進度；保留最終摘要和錯誤 |
| `./att.sh load <scenario.yaml> --verbose` | 為兼容性保留；有界實時進度已是默認行為 |
| `./att.sh load --debug <type> <id>` | 使用 `load/load.yaml` policy，將 Debug sidecar promotion 為普通單 workload Load run |
| `./att.sh load <scenario.yaml> --set input.path=<yaml-value>` | 覆蓋單 workload `EXEC.INPUT`；多 workload scenario 不支持未限定覆蓋 |
| `./att.sh load <scenario.yaml> --set arg.name=<yaml-value>` | 覆蓋單 workload Tool scenario 的 argument |
| `./att.sh load <scenario.yaml> --set vars.path=<yaml-value>` | 覆蓋單 workload Template/Flow bootstrap vars |
| `./att.sh report --run-id <id>` | 重建 `report/index.html` 和 `report/junit.html` |
| `./att.sh docs` | 生成 `build/docs/index.html` |
| `./att.sh build` | 在 `build/` 中歸檔最新完成 run |
| `./att.sh clean` | 刪除文檔化生成輸出 |

## Typed overrides and quick Load

`--set` 可重複使用，並且只接受一個 namespace：`input`、`arg` 或 `vars`。值使用安全 YAML 解析，例如 `42`、`true`、`null`、`[a, b]` 或 `{id: 7}`；nested path 可使用 map key 和數字 list index，例如 `input.customer.ids[0]=42`。重複賦值按順序套用，最後一個值生效。解析 override 時不會評估 ATT expression；若 shell 可能展開類似 expression 的值，請加上引號。`arg.*` 僅適用於 Tool，`vars.*` 僅適用於 Template/Flow。多 workload Load scenario 不接受未限定的 override。

`load/load.yaml` 是可選的 policy-only `att-load/v1.6` 檔案，可包含 `load`、`execution`、`thresholds`、`evidence` 和 `seed`，但不包含 target 或 business inputs。`load --debug` 會將 sidecar `inputs` 提升為 `EXEC.INPUT`、Template/Flow `vars` 提升為 bootstrap `EXEC.VARS`，或將 Tool `arguments` 傳入 Tool call，然後使用一般 Load validator、scheduler 和 evidence pipeline 執行。明確的 CLI pacing fields 會覆蓋 policy；沒有 policy 時，請直接在 CLI 指定完整 policy。

## Debug input 與 output

CLI 的 target、`--input`、`--set` 與 `--env` 語法見本章 option matrix。Input discovery、bootstrap vars、保護 roots 與 output lifecycle 見 [Debug](execution-modes/debug.md)。

## 退出碼

| 代碼 | 含義 |
|---:|---|
| 0 | 命令/運行成功，且無 FAIL、ERROR、INVALID |
| 1 | 至少一個 FAIL，且無 ERROR/INVALID |
| 2 | CLI/配置/校驗/INVALID 失敗 |
| 3 | 至少一個 ERROR，或不可恢復運行時失敗 |

## 完整 CLI option matrix

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
