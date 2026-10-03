# Testdata Registry and Input Mapping

This page describes the runtime boundary for issue #63. The public descriptor contract is `att-testdata/v1.0`; environment selection belongs to `att-config/v2.11`, and Load policy belongs to `att-load/v1.5`.

## Registry layers and activation

The selected environment profile supplies package-relative testdata descriptor paths. Each descriptor has a logical ID and either a non-empty literal `records` list or a generated record declaration. A Load scenario can add package-relative paths at its top-level `testdata` property. Those descriptors form a scenario-local overlay: a matching Load-local ID replaces the complete environment descriptor for that scenario. No record or selection fields are merged. Repeated IDs within a layer and repeated canonical paths in that layer are invalid.

The registry indexes descriptor IDs on demand. Run and Debug load only descriptors referenced by input mappings. Load validates the IDs used by workload inputs and explicit workload policies before scheduler startup. Explicit package validation loads and schema-validates every descriptor in the selected environment, including unused ones. Imports must resolve to regular YAML files inside the canonical package root and may not be symlinks.

## Literal and generated records

Literal records preserve YAML value types. An exact input reference can therefore produce a scalar, map, or list. ATT freezes descriptor data after validation so callers cannot mutate a record shared by another mapping.

Generated records declare one inclusive integer range, `from` through `to`, and one `record` template. The range must be ascending and contain at most 1,000,000 values. Optional integer formatting is limited to `%d` or `%0Nd`. `record` may contain nested maps and lists; `%{seq}` is replaced recursively with the formatted integer. No other variable or expression syntax is allowed in generated records. Only the requested index is materialized, so a large sequence does not allocate a list of every record.

## Mapping and selection

Input mapping is performed before the resolved values are published as `EXEC.INPUT`:

- `@{id}` returns the selected record with its native type.
- `@{id.path}` and numeric list indexes traverse the selected record.
- `@{id}` may be embedded in text only when the selected value is a non-null scalar; the result is a string.
- `${...}` may read only Context initialized before that mapping phase. Run Case/Stage mappings accept `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.PROJECT/SOURCE/TARGET`. Debug `inputs` additionally accept `META.TEMPLATE`. Load workload `inputs` accept `EXEC.RUN_ID`, both timestamps, available `EXEC.LOAD` identity fields, and `META.PROJECT/SOURCE/TARGET/TEMPLATE`; `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is closed-workload-only and can be written as the optional path `${EXEC.LOAD.USER_ID?}` when supporting arrival-rate too.
- All modes reject `EXEC.INPUT` while it is being created, `EXEC.VARS`, `EXEC.ACTIONS`, Action `output`, and invocation-scoped helper metadata. Mapping validation runs before execution, and before scheduler startup in Load. The V1 mapping grammar evaluates literals, selected-record `@{...}` references, and `${...}` Context references; built-in calls are not evaluated.
- `#{...}`, `&{...}`, and `%{...}` are not evaluated in mappings.

All references to one ID in a mapping reuse one selected record. Across Run/Debug Case and Stage mappings, the resolver also retains that execution's ID choice. A descriptor with multiple records must declare `selection.strategy`: `sequential`, `roundRobin`, or `random`. Optional `exhaustion` defaults to `error`; `recycle` starts again at the beginning, while `stop` raises a scheduler stop signal for the active Load workload. A `seed` makes random selection reproducible.

In Load, each referenced ID can set a scope of `workload`, `user`, or `iteration`; the default is `iteration`. A scope keeps the selected record stable for its lifetime. `user` requires a closed-VU workload because arrival-rate execution has no stable user identity. A workload `selection` policy replaces the descriptor's whole policy. It is a selection override, not an import declaration. A one-record descriptor is selected directly and does not need a selection policy.

The resolver retains only workload- and user-scoped choices in its run-level selection map. An iteration choice is held in the current input mapping's local memo table, so repeated references to one ID in that mapping stay consistent while completed iterations leave no selection entry behind. Resolver telemetry reports mapping evaluations, selection requests/evaluations/cache hits, and cache sizes grouped by scope.

## Direct-reference boundary and evidence

Testdata markers are accepted only in input mappings. Templates, Flows, Tool definitions and Tool invocation arguments cannot use `@{...}` or `%{...}` directly, and cannot read `TESTDATA.*`, `EXEC.TESTDATA`, `EXEC.DATA`, or `EXEC.FIXTURE`; they receive resolved values through `EXEC.INPUT`. This keeps selection policy with the execution boundary and lets reusable components consume ordinary typed input.

Selection evidence contains the logical ID, source layer, zero-based record index, generated sequence when present, scope, strategy and effective random seed. It never copies raw record contents. Load stores this bounded metadata with retained iteration results. The resolver keeps selection decisions local to the current execution/workload and makes generated values by index rather than expanding the source range.

Descriptor loading rejects credential-like record field names such as `password`, `token`, `authorization`, and `api_key`; this is a field-name guard, not a scan of arbitrary values. Selection metadata does not contain input values. Use synthetic or minimized personal data in records, and use the existing Load evidence policy to control which execution evidence is retained.
