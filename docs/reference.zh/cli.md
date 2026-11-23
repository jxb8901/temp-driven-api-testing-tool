# CLI Reference

現行 CLI 可執行 Native Case、驗證 Package resource、診斷 Flow 或 Tool，以及執行 Flow 或 Tool 的 Load workload。既有 Package 格式請參閱[舊版 CLI 指南](../legacy/cli.zh.md)。

## 編譯 source checkout

Source checkout launcher 會執行已編譯的 class。若 class 缺失，請編譯 Java CLI、Engine、Worker 和 Server API module：

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

macOS/Linux 使用 `./att.sh`，Windows 使用 `att.bat`。Packaged release 內含已編譯的 application JAR，使用 Java 8 或更新版本；Server WAR 使用 Java 17 或更新版本。

## 常用命令

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

[測試撰寫](test-authoring.md)定義 Native selector 語法和 source ownership。Native Definition 綁定 Flow 與 Data Source；其 Case 可按 qualified ID、tag 或 Suite 條件選擇。

## 執行 Case 或 Suite

~~~sh
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
./att.sh --local -p default run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool
./att.sh --local -p default run native-suite --all --tag smoke --exclude-tag slow
~~~

`--dry-run` 只驗證並解析 selection，不執行 Actions。Native Suite 使用 `--rerun-failed` 會從最近符合資格且已完成的 Run 選取曾執行但失敗的 Case。`--fail-fast` 會在失敗後停止安排新的 execution。

## 驗證 Package

~~~sh
./att.sh validate --package
./att.sh validate --selected testcase/quick-start/single/testcase.yaml
~~~

Validation 會檢查所選 Definition、Flow、Tools、Testdata、Runtime Configuration、schemas 和 resource references。不會執行 Actions 或啟動 Load scheduler。

## 診斷 Flow 或 Tool

~~~sh
./att.sh debug flow common.payment.v1 --input debug/payment.yaml --env SIT
./att.sh debug tool sample.getAcDate --set arg.date=2026-11-23
~~~

`--input` 選擇 Debug input file。可重複使用 `--set input.path=value`、`--set vars.path=value` 或 `--set arg.name=value` 提供 typed value。Flow target 接受 input 和 vars；Tool target 接受 input、vars 及已聲明的 arguments。Debug discovery、受保護路徑、output 和 result 行為見 [Debug](execution-modes/debug.md)。

## 執行 Load workload

Direct Load workload 以 Flow 或 Tool 為 target。已聲明的 scenario 可包含通過驗證的 Flow 和 Tool workloads。

~~~sh
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load examples/load/closed-smoke.yaml --users 1 --duration 100ms
~~~

List 和 preview 不會啟動 traffic。[Load 模式](execution-modes/load.md)說明 pacing、Testdata、evidence、threshold 和 scenario composition。

## 執行 Server 命令

本機命令在目前機器執行。加入 `remote` 將支援的操作提交至 ATT Server：

~~~sh
./att.sh remote --server sit packages
./att.sh remote --server sit run payments --detach
./att.sh remote --server sit jobs
./att.sh remote --server sit watch J0123456789ABCDEF
~~~

Remote Package 名稱、Definition selection、configuration 和 scenario 路徑都是由 Server 解析的邏輯值；不會傳送用戶端絕對路徑或本機 output directory。[ATT Remote client](../remote-client.zh.md)說明連線 profile、authentication、streaming 和 troubleshooting。

## 常用 options

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

## Exit code

| Code | 意義 |
|---:|---|
| 0 | Command 成功，沒有 FAIL、ERROR 或 INVALID |
| 1 | 一個或多個 Case 或 assertion 失敗 |
| 2 | Command、configuration 或 validation 無效 |
| 3 | Runtime 或 infrastructure failure |

Windows 使用 `att.bat`，並接受相同的 Native Case、Flow、Tool 和 Load arguments。
