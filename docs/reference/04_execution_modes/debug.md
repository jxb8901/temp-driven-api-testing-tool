### 4.2 Standalone Debug

Debug executes one Template, Flow or Tool without requiring a workbook Testcase.

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Debug input uses `schemaVersion: att-debug/v1.0`. Supported top-level data is `case`, optional `stage`, `inputs`, `arguments`, and grouped `tools.<localKey>.arguments`. `inputs` is adapted into canonical `EXEC.INPUT`; framework-owned identity, output, Actions, resource metadata and compatibility views cannot be overwritten by user input.

Without `--input`, ATT looks for `debug.yaml` beside a selected Template or Flow and for `config/tools/<group>.debug.yaml` for a grouped Tool. When no default sidecar exists, supply `--input`. `--env` uses the same environment resolver as Run/Validate/Load before target validation.

Debug performs target-scoped validation: it validates the selected Template/Flow dependency closure or Tool contract, rather than requiring unrelated workbooks. Template and Flow debug use the same Action/Flow scope rules as Run. Tool debug constructs the same configured Tool invocation contract.

Each invocation is isolated under:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug does not create or update normal `latest-run.yaml`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input/validation, and `3` runtime error. It is execution-equivalent at the selected reusable-component boundary, but it is **not** a workbook Case: there is no workbook selection, Stage history or result-workbook lifecycle unless explicitly represented by debug inputs/artifacts.
