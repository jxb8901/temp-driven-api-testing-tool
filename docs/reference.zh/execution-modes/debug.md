# Standalone Debug

Debug 使用明確 inputs 執行一個 Flow 或 Tool，並寫入獨立結果目錄，不需要 Test Case Definition。維護以較早模型編寫的 Package，請參閱[舊版文件](../../legacy/README.zh.md)。

```sh
./att.sh debug flow common.payment.v1 --input common.payment.debug.yaml
./att.sh debug tool sample.getAcDate --input sample-tool.debug.yaml --env UAT
```

## Bootstrap inputs

使用 `att-debug/v1.2`。Flow Debug 接受 `inputs`、選用 `vars` 和選用 Testdata imports。Tool Debug 接受該 Tool 所宣告的 `arguments`。執行前會驗證這些值；framework-owned identity 和 output 欄位不能被覆蓋。

```yaml
schemaVersion: att-debug/v1.2
inputs:
  amount: 100
vars:
  reference: REF-001
```

讀取 `EXEC.INPUT` 的 Flow 需要 `inputs`；`vars` 會初始化可重用 Flow 的 `EXEC.VARS`。Testdata reference 使用與 RUN 和 LOAD 相同的 mapping rules。詳見[Testdata registry 和 input mapping](../test-authoring.md)。

## Discovery 和結果

不帶 target 執行 `./att.sh debug`，會列出靜態有效的 Flow 和 Tool。Discovery 會驗證所選 dependency closure，但不會執行 Resource。使用 `--format json` 可取得機器可讀輸出。

每次 invocation 會在 `output/debug/<debugId>/` 寫入 `case.log`、`result.yaml` 和保留的 Action artifact。Debug 不會更新一般 `latest-run.yaml` pointer。PASS 的 exit code 為 `0`；Action 失敗為 `1`；CLI/input/configuration 無效為 `2`；runtime error 為 `3`。

Resource call 在 Debug、RUN 和 LOAD 中使用相同的 Package-relative file-content expression。外部 I/O 前會解析被引用的檔案。若 Tool 可能連接外部系統，執行前請檢查 Environment 和 safe inputs。
