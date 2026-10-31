<!-- att-doc-status: planned release=unreleased issue=287 -->
# Proposed CLI vNext

> **規劃中，尚未發行。** 此語法不適用於 ATT 4.2.8。已發行的 command 請使用目前的 [CLI Reference](../../reference.zh/cli.md)。只有在 #287 implementation 發行並完成核實後，才可將本 contract 標示為 Current。

## Proposed grammar and behavior


```text
att [--local | --server <profile|url>] [-p <packageId>] [-e <profile>] \
    [--config <path>] [--format human|json] <run|load|validate> <target-kind> [target]
```

`--local` 與 `--server` 不能同時使用。兩者皆未指定時，vNext 使用 Local Engine。若沒有 `-p`，Local Package 選擇使用 `ATT_PACKAGE_ROOT` 或目前目錄；`-p <id>` 只會解析 `ATT_HOME/packages/<id>` 下的直接子目錄。Remote RUN/LOAD 及 Package 或選中目標 validation 必須指定已在 Server 註冊的 `-p <id>`；Remote `validate config` 則可省略 `-p`，只檢查中央設定。Remote 路徑相對於 Package，Runtime Config 由 Server 管理。vNext 頂層 execution command 只有 `run`、`load` 和 `validate`。

| 命令 | 範例 | 行為 |
|---|---|---|
| `run` | `att --local -p payments run case quickStart.default.QS001` | 執行 Case、Suite workbook、所有 Case、Template、Flow 或 Tool；Local 會同步執行。 |
| `load` | `att --server sit -p payments load flow PAYMENT.submit --arrival-rate 5/s --duration 2m` | 透過現有 Load Engine 執行 Flow、Template、Tool 或已編寫的 Load Scenario。 |
| `validate config` | `att --local -p payments -e SIT validate config` | 檢查共享 Runtime Config，以及所選 Package 的有效 Config 和 Testdata reference，不執行業務 I/O。 |
| `validate package` | `att --server sit -p payments validate package` | 檢查完整的已註冊 Package inventory 和靜態依賴。 |
| 選中項目 `validate` | `att --local -p payments validate flow PAYMENT.submit` | 檢查目標及其依賴閉包。 |
| `validate --all` | `att --server sit validate --all --format json` | 彙總共享 Config、有效 Server Config 和範圍內的所有 Package。Server 模式需要安裝級管理員權限。 |

`validate --all` 不能與目標、`-p` 或 `-e` 同時使用。Local validation 不會建立或更改 Server data directory。若沒有可選的 active Server Config，結果會標記為 `SKIP`；明確指定的檔案若不存在或無效則標記為 `FAIL`。Server validation 只檢查已註冊的 Package，並使用 Server 管理的 Runtime Config。

Validation 只讀：不執行 Tool、不發送 Load traffic、不連線探測 DB/MQ，也不修改 Config、Package 檔案或輸出。Human 輸出使用階層式 `PASS`/`FAIL`/`WARN`/`SKIP` 區段；`--format json` 會向 stdout 輸出單一 `att-validation/v1` report。Exit code：PASS 為 `0`、RUN/LOAD 功能失敗為 `1`、輸入或 validation 無效為 `2`、執行錯誤/取消為 `3`、Remote transport 或不可信結果為 `4`。持續性 Local Job 管理及背景提交不屬於此提案語法。

常用 vNext option：`-p, --package`、`-e, --env`、`--format`、`--quiet`、`--verbose`、`--help` 和 `--version`。`--config <path>` 只適用 Local。RUN 選擇 option 包括 `--suite`、`--suite-dir`、`--case`（亦可用 `--case-id`）、`--tag`、`--exclude-tag`、`--all`、`--rerun-failed`、`--dry-run`（只適用於 Case/Suite RUN）、`--fail-fast`、`--run-id`，以及僅限 Local 的 `--output-dir`。Template、Flow 及 Tool RUN 會拒絕 `--dry-run`，並支援 `--input <path>`；Resource RUN 和 LOAD 可重複使用 `--set`。直接 LOAD 會拒絕 `--input`。LOAD 可使用 `--users` 或 `--arrival-rate`，並可設定 `--ramp-up`、`--warmup`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`。`--users` 與 `--arrival-rate` 不能同時使用。未支援的 option 和無效組合會在提交工作或發送 traffic 前報錯。

### 遷移既有命令

| 既有語法 | vNext 語法 |
|---|---|
| `att run --suite testcase/payment.xlsx` | `att --local run suite testcase/payment.xlsx` |
| `att debug flow PAYMENT.submit --input debug.yaml` | `att --local run flow PAYMENT.submit --input debug.yaml` |
| `att load load/payment.yaml --users 5 --duration 60s` | `att --local load scenario load/payment.yaml --users 5 --duration 60s` |
| `att remote run payments --suite testcase/payment.xlsx` | `att --server sit -p payments run suite testcase/payment.xlsx` |
| `att remote validate payments --package` | `att --server sit -p payments validate package` |

遷移期間會保留舊命令的相容路徑，但它們不是 vNext 命令。管理命令和背景提交不屬於這份精簡語法。
