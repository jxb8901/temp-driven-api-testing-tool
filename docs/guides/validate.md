# Validate configuration and Packages

VALIDATE checks authored Native inputs without running business work. The shared CLI grammar is documented in the [CLI Reference](../reference/cli.md); this target remains unpublished.

## Validate the Package

~~~sh
./att.sh validate --package
~~~

To select a Runtime Configuration file and Environment:

~~~sh
./att.sh validate --config config/config.yaml --env SIT --package
~~~

Use a selected-validation option to check one Definition or resource dependency closure. See [Validation and Troubleshooting](../reference/validation-diagnostics.md) for diagnostic codes, JSON fields, scopes, and exit codes. For a Package authored with an earlier model, see the [Legacy documentation](../legacy/README.md).

## What VALIDATE does

VALIDATE reads the selected Configuration and Package sources, checks registered schemas and semantic references, and reports diagnostics. It does not execute Tools, invoke DB/MQ business operations, submit a Server Job, or start a LOAD scheduler. It does not create or update authored inputs.
