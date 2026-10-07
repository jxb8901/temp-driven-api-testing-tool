# Run mode

Run executes selected authored Testcases from a workbook. Each selected Testcase produces one Case execution with a status and evidence record. Use *Testcase* for the normalized workbook row and *Case execution* for that runtime result; the Case ID is the shared identifier.

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT loads the effective configuration/environment, verifies canonical workbook snapshots, validates the selected dependency closure, reserves a unique Run ID, then starts a Case execution for each selected Testcase. Stages run in order. Each Stage resolves its selector to a Template; Actions execute in YAML order subject to `runWhen` and `onFailure`.

When `--run-id <id>` is absent, `execution.runIdFormat` can generate the outer Run ID; otherwise the existing `run.id.timestampFormat` default applies. The format is evaluated once during identity initialization, before `EXEC.RUN_ID` or an output path is published. Its allowed values and built-ins are listed in [package-wide configuration](../configuration.md#set-package-wide-options). The exact CLI ID takes precedence and is treated as a literal.

Run evidence is written directly below `output/<RunID>/`. The completed run publishes `run.yaml`, Case directories/logs, result workbooks, HTML/CI outputs as configured, and only after completion updates `latest-run.yaml`. A pre-existing Run ID is rejected rather than overwritten. `run --update-snapshot` is the explicit opt-in snapshot refresh path before validation/execution.

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for test/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime/infrastructure error.

See the [CLI Reference](../cli.md) for selectors and options, [Reliability and Execution Control](../reliability-execution-control.md) for Stage and Action control, and [Results, Reports, and Evidence](../results-reports-evidence.md) for artifact contracts.
