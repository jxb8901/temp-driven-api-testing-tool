# Run functional tests

This guide shows the legacy workbook/Sidecar/Snapshot compatibility path and runs a checked-in offline Case with `./att.sh run`. For Native Case commands in the unpublished 4.6.0 release-target stack, see the [Quick Start](../quick-start.md) and [current CLI Reference](../reference/cli.md).

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

For target and selection semantics, see [Unified RUN](../reference/execution-modes/unified-run.md). Local CLI vNext syntax is [proposed for a later release](../interfaces/cli/vnext-proposed.md).
