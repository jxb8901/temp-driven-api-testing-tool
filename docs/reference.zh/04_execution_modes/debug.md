### 4.2 Standalone Debug

Debug 可在沒有 workbook Testcase 的情況下執行單一 Template、Flow 或 Tool。

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Debug input 使用 `schemaVersion: att-debug/v1.0`。Top-level 支援 `case`、可選 `stage`、`inputs`、`arguments`、以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。

未指定 `--input` 時，Template/Flow 會在旁邊尋找 `debug.yaml`；grouped Tool 會查找 `config/tools/<group>.debug.yaml`。沒有可發現 default sidecar 時請使用 `--input`。`--env` 在 target validation 之前使用與 Run/Validate/Load 相同的 environment resolver。

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
| `target` 或 dependency validation 失敗 | 確認 target type/id，並查看回報的 dependency field；不需要無關 workbook。 |
| MQ 回報 payload 遺失或不安全 | 核對 package 內的絕對路徑或 Case-output 內的相對路徑，移除 traversal 及 symlink。 |
| action 已執行但輸出不符預期 | 查看 `output/debug/<debugId>/` 下的 `case.log`、`result.yaml` 及 action artifacts，並對照 rendered inputs 與 selected environment。 |

Load 專用的 evidence retention（`metrics`、`failures`、`samples`、`all`）不適用於 standalone Debug invocation。Debug 會在自己的 debug directory 保留 invocation result 與 artifacts；同一 target 若由 load run 執行，請參考 Chapter 4 的 Load evidence retention 章節。
