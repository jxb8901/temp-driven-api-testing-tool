# Run functional tests

The current Case path is Flow + `testcase.yaml` + Data Source, documented in the [Quick Start](../quick-start.md) and [current CLI Reference](../reference/cli.md). Those Native commands are integrated on the current default branch. This page keeps the workbook/Sidecar/Snapshot commands that still run a checked-in offline Case. That path is supported compatibility, not the only authoring model, and no 4.6.0 release or tag has been published.

## Build the source checkout

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

Packaged installations already contain application JARs.

## Generate and validate authored inputs

From the repository root, the launcher selects the default Package.

~~~sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
~~~

Snapshot generation writes the canonical workbook representation. Validation checks current configuration and Package inputs without invoking business Tools or creating Snapshots.

## Run a Case

~~~sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
~~~

Use the Local Run ID and output directory in the result summary to find the execution evidence. For template, flow or tool diagnostics, the current compatibility command is standalone Debug:

~~~sh
./att.sh debug template PAYMENT_INVOKE
~~~

For target and selection semantics, see [Unified RUN](../reference/execution-modes/unified-run.md). Native `run native-case` and `run native-suite` are the current commands. The broader shared vNext grammar remains a [planned proposal](../interfaces/cli/vnext-proposed.md).
