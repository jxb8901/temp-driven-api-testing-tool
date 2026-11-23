# Validation and troubleshooting

## Choose the first diagnostic step

Run `./att.sh validate --package` and start with the reported file and field. A **Resolved Case** is one selectable Case from a Test Case Definition. A **Case execution** is one run of a selected Resolved Case. For runtime failures, inspect the execution status and message, then `case.log`, `case.yaml`, and Action evidence. For an existing Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md).

## Validate Native inputs

Package validation checks Definitions, Flows, Tools, Testdata, Runtime Configuration, registered schemas, and semantic references. It does not execute Tools, submit a Server Job, or start a LOAD scheduler. It is read-only and never creates authoring inputs.

Use a selected-validation option for fast feedback on one Definition or resource target. Before sharing a Package or promoting a build, validate the whole Package and each selected Environment. The [Schema and Version Matrix](appendices/schema-matrix.md) lists the contracts used by this release. Diagnostics retain the source file, field, and structured location; automation should use stable diagnostic codes rather than message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing Definition, invalid Data Source, or duplicate Case ID | Check Definition ID, source records, and resolved Case IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Check the requested path, current scope, and suggested canonical path |
| `ATT-FLOW` | Unknown Flow, invalid Action, or unresolved dependency | Check the Flow ID, ordered Actions, and referenced resources/files |
| `ATT-CFG` | Unknown field, duplicate key, or wrong type | Compare the value with the active Configuration schema |
| `ATT-TOOL` | Unknown argument, process failure, or parse failure | Check the Tool contract, exit code, and bounded output evidence |
| `ATT-PATH` | Illegal identifier or escaping path | Keep identifiers and files beneath configured Package roots |
| `ATT-RUN` | Timeout, non-zero exit, or runtime exception | Inspect the Case log and Action evidence |

## Common questions

### Why was a Case ID rejected?

Resolved Case IDs must be unique and path-safe. For Excel sources, store IDs as text and check for blank values, leading or trailing whitespace, reserved device names, and case-fold collisions.

### Why does a Context variable fail?

ATT reports an absent path as an authoring or runtime error. Check the exact scope, source-field mapping, Action ID, and when the value becomes available. A declared optional blank value is valid and renders as an empty string.

### Why did a Case fail or error?

A false assertion makes the Case execution FAIL. Invalid expression syntax, Tool failure, timeout, parse failure, I/O failure, or runtime exception makes it ERROR. Inspect the Action evidence for the underlying cause.

### Can a report be opened without a Server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

## Security and structured diagnostics

Do not place passwords, tokens, private keys, or sensitive customer data in Definition inputs, Tool arguments, logs, or documentation examples. Use approved secret injection and review reports before sharing them.

Machine-readable validation uses `att-validation/v2.1`. Diagnostics include stable codes, severity, file and field, source location when available, and a safe suggestion. Codes are the supported automation interface; message wording can change.
