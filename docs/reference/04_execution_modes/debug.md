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

### Debug troubleshooting and MQ payload paths

When a debug target cannot be resolved, check the target kind and identifier first, then use `--input <path>` to remove sidecar discovery from the diagnosis. Template and Flow debug discover `<target directory>/debug.yaml`; grouped Tool debug discovers `config/tools/<group>.debug.yaml`. The selected target's dependency closure is validated, so an unrelated workbook or Case file is not a prerequisite.

MQ `file` arguments follow the same safe path rules in Debug, Run and Load:

- An absolute path must resolve to a regular file inside the ATT package root. It is validated against the package root even when Load has not materialized a lazy iteration workspace yet.
- A relative path is resolved under the active Case output directory. `..` traversal, symlink payloads, symlink escapes, directories and non-regular files are rejected before MQ connect/open/put/get.
- A missing or unsafe payload reports the payload path directly. No MQ connection is attempted, so a path error should be fixed before investigating broker credentials or queue state.

Use the output directory to separate diagnosis stages:

| Symptom | Check |
|---|---|
| `Debug input file does not exist` | Add the sidecar beside the selected target or pass `--input` explicitly. |
| `target` or dependency validation fails | Confirm the target type/id and inspect the reported dependency field; unrelated workbook files are not required. |
| MQ reports a missing/unsafe payload | Verify the absolute package path or the relative Case-output path; remove traversal and symlinks. |
| The action runs but output is unexpected | Read `case.log`, `result.yaml` and the action artifacts under `output/debug/<debugId>/`; compare rendered inputs with the selected environment. |

Load-specific evidence retention (`metrics`, `failures`, `samples`, `all`) does not apply to a standalone Debug invocation. Debug always keeps its invocation result and artifacts under its own debug directory; see the Load evidence retention section in Chapter 4 when the same target is exercised by a load run.
