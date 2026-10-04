# Run mode

Run is workbook-driven Testcase execution.

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT loads the effective configuration/environment, verifies canonical workbook snapshots, validates the selected dependency closure, reserves a unique Run ID, then executes selected Cases in Stage order. A Stage resolves its selector to a Template; Actions execute in YAML order subject to `runWhen` and `onFailure`.

Run evidence is written directly below `output/<RunID>/`. The completed run publishes `run.yaml`, Case directories/logs, result workbooks, HTML/CI outputs as configured, and only after completion updates `latest-run.yaml`. A pre-existing Run ID is rejected rather than overwritten. `run --update-snapshot` is the explicit opt-in snapshot refresh path before validation/execution.

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for test/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime/infrastructure error.

Use the [CLI Reference](../cli.md) for exact selectors and options, [Reliability](../reliability-execution-control.md) for execution control, and [Results, Reports, and Evidence](../results-reports-evidence.md) for artifact contracts.
