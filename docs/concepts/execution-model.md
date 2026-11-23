# Execution model

ATT separates functional RUN, LOAD traffic generation, and read-only VALIDATE.

## Functional RUN

Native RUN resolves a Test Case Definition to selected Resolved Cases. Each Case uses its bound Flow, typed inputs, and declared Resources. Server and Studio submit a validated RUN draft; the draft freezes the Package revision and selection. Debug runs one Flow or Tool with explicit inputs.

## LOAD

LOAD runs Flow or Tool workloads through a scheduler with virtual-user or arrival-rate pacing, timing controls, thresholds, and evidence policy. Native Case LOAD targets remain deferred. See the [Load model](load-model.md) and [Load Reference](../reference/execution-modes/load.md).

## VALIDATE

VALIDATE inspects Configuration, Package resources, schemas, and selected dependencies. It does not execute Tools, send LOAD traffic, or perform DB/MQ business operations. Validation does not create or update authored inputs.

## Contract ownership

[RUN](../reference/execution-modes/run.md) owns Case selection and execution. [Debug](../reference/execution-modes/debug.md) owns diagnostic inputs and outputs. [LOAD](../reference/execution-modes/load.md) owns workload behavior. [Validation and Troubleshooting](../reference/validation-diagnostics.md) owns validation results and diagnostics. For a Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md).
