# Run functional tests

This guide runs the Native Case workflow described in the [Quick Start](../quick-start.md). Use `./att.sh` from the repository root to work with the default Package, or select another registered Package with `--package`.

## Build the source checkout

```sh
./build.sh
```

The build creates the runtime distribution and prepares the local CLI.

## Validate Native inputs

```sh
./att.sh validate --package
```

Validation checks Native Definitions and their selected Flow/Tool dependencies. It does not submit a Server Job or start a LOAD scheduler.

## Run selected Cases

Use `run native-case` for one Definition/Case selection or `run native-suite` for an implicit Suite. Follow the [Quick Start](../quick-start.md) for complete commands and expected results. RUN validates the selection before execution and writes evidence under a new run directory.

To maintain a Package authored with an earlier model, use the [Legacy execution guide](../legacy/execution.md).
