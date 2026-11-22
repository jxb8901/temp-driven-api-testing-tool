# Execution model

ATT separates functional execution, Load generation and read-only validation.

## Functional RUN

The execution model supports a Case, Template, Flow or Tool target, including a selected batch of Cases. ATT Studio and Server expose these through unified RUN drafts and Jobs. Debug is an optional RUN diagnostic mode for supported Server targets.

The legacy Local CLI's `./att.sh run` command executes workbook Cases, while standalone `debug` runs one Template, Flow or Tool. The 4.6.0 release-target CLI adds explicit `run native-case` and `run native-suite` commands backed by Native resolution; the release and tag remain unpublished and deployed acceptance is pending. The legacy ATT Remote client keeps its Server commands. Command names do not define separate Engine semantics.

## LOAD

LOAD runs workload definitions through the Load scheduler with VU or arrival-rate pacing, timing controls, thresholds and evidence policy. It is a separate performance execution model. The six authoring shapes are summarized in the [Load model](load-model.md); the [Load Reference](../reference/execution-modes/load.md) owns their schema and execution contract.

## VALIDATE

VALIDATE inspects configuration, Package resources, schemas and selected dependencies. It is read-only with respect to business execution: it does not execute Tools, send Load traffic or perform DB/MQ business operations. Snapshot generation remains an explicit authoring command.

## Contract ownership

[Unified RUN](../reference/execution-modes/unified-run.md) owns RUN semantics. [Debug](../reference/execution-modes/debug.md) owns diagnostic inputs and outputs. [LOAD](../reference/execution-modes/load.md) owns workload behavior. [Validation and Diagnostics](../reference/validation-diagnostics.md) owns validation results and diagnostics.
