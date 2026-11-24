# Run mode

A Native RUN executes selected Resolved Cases from a Test Case Definition. A Case resolves to a Flow and Data Source; RUN validates the selected dependency closure before execution. New work should start with the [Quick Start](../../quick-start.md). For an existing Package authored with an earlier model, see the [Legacy documentation](../../legacy/README.md).

## Select Cases

Select one Case by qualified ID or use an implicit Suite to select Cases by Definition, ID, or tag. There is no required `suite.yaml`. Excluded tags take precedence over included tags. RUN rejects an unknown or empty selection rather than executing a different set.

The [Unified RUN reference](unified-run.md) describes the Server draft and schedule lifecycle. The [CLI Reference](../cli.md) owns command syntax and exit codes.

## Validate and execute

Before a Case starts, ATT validates the Definition, resolved Data Source, Flow, and referenced Resource contracts. A Server draft freezes the selected Case IDs and Package source revision; a source change requires validation again. Preview and validation do not submit a Job.

Each selected Resolved Case produces one Case execution with its own execution ID, status, and evidence. RUN writes results under a unique run directory and updates `latest-run.yaml` only after completion. A pre-existing Run ID is rejected rather than overwritten.

## Results and failure status

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for a Case/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime or infrastructure error.

In Studio, **Configure & Rerun** creates a new validated draft from a retained RUN definition. Review the Package revision and selection before submitting. See [Job replay and new execution](unified-run.md#replay-or-configure-a-new-execution).
