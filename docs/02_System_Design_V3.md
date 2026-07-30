# ATT V3.2.0 System Design

**Document Status:** Implemented

**Target Version:** ATT 3.2.0
**Last Updated:** 2026-07-30

## 1. Purpose

ATT V3.2.0 retains the reusable, shared-Context Flow model and improves expression, database, and Case-log authoring without adding another Context root or workflow construct. A Flow is called only from a Template and executes within that Template's existing Context.

```text
Excel test case -> Stage -> Template -> Action / Flow -> Action -> Tool / DB / built-in
```

The V3.1 removal of the isolated-function model remains normative. A Flow does not declare inputs or outputs and does not create separate `input`, lowercase `actions`, `runtime`, or `flow` Context roots. This keeps Action expressions unchanged when Actions are moved between a Template and a Flow.

The existing Excel, Stage, V2 Template, Tool, DB, run, report, and CI contracts remain unchanged.

## 2. Goals and boundaries

V3.2 provides:

- one Expression Engine and Context contract for Template and Flow Actions;
- direct access to `CASE`, `RUN`, prior `ACTIONS`, `TOOL`, `DB`, and current `output`;
- static Flow composition and nesting to depth 3;
- validation of the complete expanded Action order before run output is created;
- one Action-ID namespace for a Template and its nested Flow closure;
- Case-scoped `assign` behavior through `CASE.VARS`; and
- nested evidence and qualified artifact paths without a Flow-specific expression API.

V3.2 additionally provides:

- typed `#{...}` expression blocks with arithmetic, `in`, lists, comparisons, boolean operators, Context operands, and calls;
- direct DB Action named parameters compiled safely to JDBC positional bindings;
- deterministic `prettyPrint` formatting for nested runtime values;
- raw multiline Log Action and process output in the Case log;
- `saveAs.path: console`; and
- no persistent `process-output` artifacts.

V3.2 does not add namespaces, implicit last-Action output, replacement input/output syntax, loops, parallel branches, dynamic dispatch, Flow timeout/retry, `runAlways`, warning impact, or inheritance.

## 3. Public configuration

### 3.1 Flow descriptor

Flow descriptors remain below `<templatesRoot>/flows/**/flow.yaml` and retain the `att-flow/v3.0` schema name. The schema is deliberately redefined and is not compatible with the isolated V3.0 contract.

```yaml
schemaVersion: att-flow/v3.0
id: payment.invoke.v1
name: Invoke Payment
description: Render and invoke a payment request.

actions:
  renderRequest:
    type: render
    payload: request.xml
    renderAs: file

  invokePayment:
    type: tool
    call: >-
      #{payment.invoke(
        request=${ACTIONS.renderRequest.output.result},
        reference=${CASE.SrcRefNo}
      )}
```

The only top-level fields are `schemaVersion`, `id`, `name`, `description`, and `actions`. `inputs` and `outputs` are validation errors.

### 3.2 Template invocation

`att-template/v3.0` retains `type: flow` with one static canonical `use` value:

```yaml
actions:
  invokeFlow:
    type: flow
    use: payment.invoke.v1

  verify:
    type: assert
    assert: "${ACTIONS.invokePayment.output.result.status} == 'SUCCESS'"
```

`with` is invalid. A Flow Action exposes only the normal Action outcome fields such as `status`, `success`, `durationMs`, and `exception`. Business results are read from the internal Action that produced them; ATT does not create `output.outputs` or forward the last Action result.

## 4. Context and assignment semantics

Flow Actions use exactly the same readable logical Context as inline Template Actions:

| Root | Meaning |
|---|---|
| `CASE.*` | Current Case data, stages, DB finalization data, and `CASE.VARS` |
| `RUN.*` | Current Run metadata |
| `ACTIONS.*` | Earlier completed Actions in the expanded Template plan |
| `TOOL.*` | Current Tool invocation data where available |
| `DB.*` | Current DB invocation evidence where available |
| `output.*` | Current Action outcome during supported post-execution fields |

Existing unique-suffix shorthand remains unchanged. Removed Flow-only roots are invalid rather than treated as aliases.

Visibility follows execution order:

1. a Flow sees Actions completed before its invocation;
2. each internal Action sees all earlier completed Actions in the same expanded plan;
3. a nested Flow inherits the same visible Action sequence; and
4. Actions after the Flow may read its completed internal Actions directly.

`assign` always publishes to `CASE.VARS`, including inside a Flow. Assignments never overwrite an existing name and persist across later Actions, Templates, and Stages in the same Case.

## 5. Static expansion and identity

Every selected Template is validated as one statically expanded Action plan. Flow invocation Actions remain nodes in the plan, while their internal Actions execute before the invocation Action publishes its aggregate outcome.

All Action IDs in the expanded Template closure must be unique, including:

- inline Template Actions;
- Flow invocation Actions;
- internal Actions;
- nested Flow invocation Actions; and
- nested internal Actions.

Consequently, two used Flows cannot contribute the same Action ID, and the same Flow cannot be called twice in one Template. Different Templates or Stages may reuse the same IDs because `ACTIONS` is reset at each Stage Template execution.

Flow IDs remain path-independent canonical IDs ending in `.vN`. References are static. Direct and indirect cycles are invalid, and maximum nesting depth remains 3.

## 6. Validation

The registry performs package-level structural checks:

- strict Flow and Template schemas;
- canonical and duplicate Flow IDs;
- missing dependencies;
- cycles and nesting depth;
- `template.yaml`/`flow.yaml` conflicts; and
- symlink and package-root escapes.

The execution-plan validator recursively validates each Flow at its actual Template call site. It carries the same completed-Action set and `CASE.VARS` assignment set through the complete closure, and applies the ordinary Action validation rules to Flow render payloads, Tool calls, DB blocks, assertions, logs, assignments, descriptions, and `runWhen` expressions.

An unused Flow receives structural validation. Context references that depend on a caller or a concrete Case are validated for every actual invocation. Invalid plans are rejected before a Run directory is created.

## 7. Runtime and evidence

The runtime does not replace the logical Context when entering a Flow. Each completed internal Action is published to the shared `ACTIONS` view and remains readable after the Flow returns.

A Flow frame is retained only for:

- Flow ID, invocation ID, and nesting depth evidence;
- hierarchical Case YAML/log evidence;
- qualified Action IDs used internally; and
- collision-free artifact directories.

Artifacts retain paths such as:

```text
<case>/<stage>/flows/<invocation>/actions/<action>/...
```

Nested Flow evidence remains below the calling Flow Action. Evidence paths such as `ACTIONS.<flowCall>.flow.actions...` are diagnostic data and are not a supported expression contract; expressions use the directly published `ACTIONS.<internalActionId>` path.

## 8. Control and result semantics

Action `runWhen` and `onFailure: stop|continue` behave identically inside and outside a Flow. A Flow Action whose own `runWhen` is false is SKIPPED and does not execute its children. An invoked Flow whose executed children are all PASS or SKIPPED is PASS.

Flow aggregation retains the common priority:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Existing Excel, HTML, JUnit, JSON, and CI consumers continue to consume the top-level Flow Action result. Internal hierarchy remains available in Case evidence.

## 9. Migration

Earlier isolated V3 Flow packages must be migrated:

- remove Flow `inputs` and `outputs`;
- remove invocation `with`;
- replace `${input.x}` with the existing `${CASE...}` or `${ACTIONS...}` source;
- replace `${actions.x...}` with `${ACTIONS.x...}`;
- replace `${runtime.x}` with `${CASE.VARS.x}`; and
- replace `${ACTIONS.<flowCall>.output.outputs.x}` with the internal Action that produces the value.

This is intentionally a breaking reinterpretation of `att-flow/v3.0` and `att-template/v3.0`. No compatibility mode is provided. Non-Flow V2 Templates remain compatible.

## 10. Expression, DB, and logging extensions

`${...}` remains the only Context-reference and text-interpolation syntax. `#{...}` is a complete typed expression block. It supports nested calls, list literals, parentheses, unary `+`, unary `-`, `not`, arithmetic `+ - * /`, comparisons, `like`, `in`, `is [not] null`, `and`, and `or`. Arithmetic is numeric, division by zero is an error, and `in` requires a list, array, or Iterable right operand. Bare Context-looking identifiers remain invalid.

Direct DB Actions may use either positional `params` with JDBC `?` placeholders or named `parameters` with `:name` placeholders, never both. Named placeholders are replaced by `?` before preparing the statement; values are never interpolated into SQL. The scanner ignores placeholders in quoted strings and comments and preserves PostgreSQL-style `::` casts. Missing and unused names fail validation. Parameter evidence defaults to resolved values; connection credentials are never included, and package owners may explicitly select `masked` or `types`.

Log Action messages and process stdout/stderr are written as raw UTF-8 Case-log text with CRLF/CR normalized to LF. Structured `case.yaml` evidence remains available, but the human log does not repeat escaped multiline result content. Process capture uses bounded temporary spools that are removed after logging or explicit artifact creation. A Run does not create `process-output`. Tool and DB `saveAs.path: console` writes the requested representation to the Case log and produces no target file.

`prettyPrint(value)` and `format.pretty(value)` format maps, lists, arrays, scalars, and null deterministically with two-space indentation, cycle/depth protection, and the existing built-in output bound. They do not mutate the supplied value.

## 11. Acceptance criteria

V3.2 is complete when:

- schema validation rejects `inputs`, `outputs`, and `with`;
- the same Action configuration runs inline or in a Flow without expression changes;
- Flow render payloads read Case data directly;
- caller, internal, nested, and following Actions share one ordered `ACTIONS` view;
- all expanded ID and `CASE.VARS` collisions fail before execution;
- Flow Actions expose no `output.outputs`;
- nested evidence and qualified artifacts remain intact;
- all V2 compatibility tests pass; and
- multiline Excel and Action values remain physical Case-log lines;
- no default process-output artifact exists and console `saveAs` produces no file;
- arithmetic, `in`, named DB binding, visible parameter evidence, and `prettyPrint` pass validation and runtime tests; and
- source, package, documentation, build, and unpacked-package validation gates pass.
