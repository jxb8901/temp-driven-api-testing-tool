# Run 模式

Run 會執行 Workbook 中經選擇的 authored Testcase。每個選中的 Testcase 會產生一次 Case execution，並有自己的狀態和 evidence record。討論正規化 Workbook row 時使用 *Testcase*；討論 runtime 結果時使用 *Case execution*；兩者共用同一個 Case ID。

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT 先載入 effective configuration/environment、驗證 canonical workbook snapshot 和 selected dependency closure、保留唯一 Run ID，然後為每個選中的 Testcase 啟動一次 Case execution。Stages 依序執行。每個 Stage selector 都會解析為 Template；Action 按 YAML 順序執行，並受 `runWhen` / `onFailure` 控制。

Run evidence 直接寫到 `output/<RunID>/`。完成後才發布 `run.yaml`、Case directories/logs、結果 workbook、HTML/CI output，並更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。`run --update-snapshot` 是執行前明確授權更新 snapshot 的唯一流程。

Status aggregation 的嚴重度為 ERROR > INVALID > FAIL > PASS > SKIPPED。Process exit code：`0` 表示沒有失敗狀態、`1` 表示測試/assertion failure、`2` 表示 command/configuration/validation 無效、`3` 表示 runtime/infrastructure error。

selector 和 option 請看 [CLI Reference](../cli.md)，Stage 和 Action control 請看 [Reliability and Execution Control](../reliability-execution-control.md)，artifact contract 請看 [Results, Reports, and Evidence](../results-reports-evidence.md)。
