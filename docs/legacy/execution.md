# Legacy execution

Use these commands when running existing workbook-based Cases or direct compatibility targets.

## Run a Case

Set `ATT_PACKAGE_ROOT` to the Package root when it is not the default Package. Paths passed to `./att.sh` are then relative to that Package.

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
```

For the retained Quick Start fixture, select `quickStart.default.QS001` or `quickStart.default.QS002` from `testcase/quick_start.xlsx`. The first exercises `assign`, `log`, and `assert`; the second also calls the local sample Tool. Use a dry run for fixtures that require external business systems.

A workbook Testcase can pass through ordered Stage selectors. Each selected Stage chooses a Template, and its inputs are scoped to that Stage. The Case ID remains the workbook, group, and row ID tuple.

## Run Debug

Debug executes one direct component and writes its own result directory. For a Package-local target, select that Package explicitly:

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
```

You can supply `--input <path>` to select an explicit Debug input file. Keep credentials in environment variables or the configured secret store. The example target performs no real request unless its Tool and environment bindings are deliberately configured.

## Run LOAD

LOAD accepts an `att-load/v1.9` scenario and supports direct Template, Flow, and Tool targets. Scenario files under `examples/load/` are relative to the repository root when the selected Package is `packages/legacy`:

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
```

Native Case targets are not part of this LOAD contract. See the current [LOAD Reference](../reference/execution-modes/load.md) for scheduler, evidence, and report fields shared with the present runtime.

## Reports and reruns

RUN output is written beneath the selected output directory and run ID. Workbook-based runs retain their existing result-workbook layout and Case IDs. A same-directory `--rerun-failed` uses the latest eligible completed result for that Package; use a separate `--output-dir` when you need an independent run history.

The completed run updates `latest-run.yaml` only after all Cases finish. A failed or incomplete run does not publish a new pointer.

For the offline multi-Flow example and its standard report workbook, use the current [Native payments walkthrough](../guides/native-payments.md).
