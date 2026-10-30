# Validate configuration and Packages

The current ATT 4.2.8 VALIDATE command checks authored inputs without running business work.

## Validate the Package

~~~sh
./att.sh validate --package
~~~

To select a Runtime Configuration file and Environment:

~~~sh
./att.sh validate --config config/config.yaml --env SIT --package
~~~

Use the CLI's selected-validation options to check a chosen dependency closure. Select an explicit Case or resource target; do not rely on validation to discover a Snapshot or create missing authoring files.

## What VALIDATE does

VALIDATE reads the selected configuration and Package sources, checks registered schemas and semantic references, and reports diagnostics. It does not execute Tools, invoke DB/MQ business operations, submit a Server Job or start a Load scheduler. It does not create or update a workbook Snapshot.

For diagnostic status, JSON fields, scope and exit codes, use [Validation and Diagnostics](../reference/validation-diagnostics.md). The proposed vNext scopes are described separately as [Planned](../interfaces/cli/vnext-proposed.md).
