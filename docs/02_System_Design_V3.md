# ATT V3.0.1 Executable Flow System Design

**Document Status:** Implemented
**Target Version:** ATT 3.0.1
**Last Updated:** 2026-07-25

---

## 1. Purpose

ATT V3.0.1 provides a first-class, reusable Flow between Template Actions and Tools. A Flow is a statically selected, typed, isolated, linear Action sequence that may invoke another Flow.

V3.0.1 tightens the V3.0.0 implementation in three places: an ordered `assign` makes `${CASE.VARS.<name>}` available without pretending its validation placeholder is a real null value; malformed Flow `use`/`with` shapes fail at validation/load time; and run evidence is written directly to `output/<RunID>` without `.in-progress` staging.

```text
Excel test case -> Stage -> Template -> Flow -> Action -> Tool / DB / built-in
```

The existing Excel -> Stage -> Template contract is unchanged. V2 Templates and all V2 config, sidecar, Tool, run, report, and CI schemas remain readable with their existing semantics.

V3.0.0 combines the original parsing and runtime phases. A schema-only Flow release is not useful because authors must be able to validate and execute the declared composition.

## 2. Goals and success criteria

V3.0.0 succeeds when:

1. only `att-template/v3.0` can invoke a Flow or use Action `runWhen`;
2. `att-flow/v3.0` provides strict typed input and output contracts;
3. Flow dependencies, cycles, depth, bindings, and local Context access are rejected before a run directory is created;
4. Template -> Flow -> nested Flow -> Tool executes without exposing Flow internals to the caller;
5. repeated Flow use cannot collide in Context or artifact paths;
6. V2.6 Templates run without modification; and
7. aggregate status remains `ERROR > INVALID > FAIL > PASS > SKIPPED`.

## 3. Scope

### 3.1 Included

- strict `att-flow/v3.0` and `att-template/v3.0` schemas;
- package Flow registry and selected dependency-closure loading;
- static canonical Flow IDs ending in `.vN`;
- static Flow calls through `type: flow`, `use`, and `with`;
- input types `string`, `integer`, `number`, `boolean`, `object`, and `array`;
- required, default, omitted optional input, and runtime type checks;
- recursive `${...}` resolution in maps and arrays;
- isolated `input`, `actions`, `runtime`, and `flow` Context roots;
- nested Flows up to depth 3;
- Action `runWhen`, existing `onFailure: stop|continue`, and deterministic aggregation;
- declared outputs exported only after a PASS Flow;
- hierarchical Case YAML/log evidence and qualified artifact paths; and
- package-generated documentation for Flows.

### 3.2 Deferred to V3.1 or later

- `runAlways` or finally-style cleanup;
- `failureImpact: warning` or a WARNING result;
- Flow deadlines, cancellation propagation, or whole-Flow retry;
- `att flow list/show/validate/usages/graph` and `att explain`;
- expanded hierarchical HTML reporting and dependency digests;
- parallel branches, loops, dynamic dispatch, inheritance, remote registries, or version ranges.

Flow Actions therefore do not accept `timeoutMs` or `retry`. Timeout and retry remain properties of eligible Tool Actions, including Tool Actions inside a Flow.

## 4. Public schemas

### 4.1 Flow descriptor

Each Flow is stored at `<templatesRoot>/flows/**/flow.yaml`. A directory must not contain both `flow.yaml` and `template.yaml`.

```yaml
schemaVersion: att-flow/v3.0
id: common.decorate.v1
name: Decorate Value
description: Append a stable suffix.
inputs:
  value:
    type: string
    required: true
  enabled:
    type: boolean
    default: true
actions:
  decorate:
    type: assign
    name: result
    expression: "${input.value}-done"
  audit:
    type: log
    message: "Decorated ${runtime.result}"
    runWhen: "${input.enabled} == true"
outputs:
  value:
    type: string
    from: "${runtime.result}"
```

The top-level fields are exactly `schemaVersion`, `id`, `name`, `description`, `inputs`, `actions`, and `outputs`. Unknown fields fail validation.

### 4.2 V3 Template invocation

```yaml
schemaVersion: att-template/v3.0
name: PAYMENT_FLOW
description: Compose a reusable payment Flow.
actions:
  prepare:
    type: flow
    use: payment.prepare.v1
    with:
      requestId: "${CASE.requestId}"
      options:
        trace: true
  verify:
    type: assert
    assert: "${ACTIONS.prepare.output.outputs.status} == 'READY'"
```

`use` is a literal canonical ID. It cannot contain `${...}`, `#{...}`, a version range, or a runtime selector. A Template has no top-level `inputs`; it binds Flow inputs from literals or existing Case/Action Context.

### 4.3 Action contract

V3 inherits the V2.6 Actions `render`, `tool`, `db`, `assert`, `log`, and `assign`, and adds `flow`. Every V3 Action may declare `runWhen` as a boolean expression evaluated immediately before execution.

If `runWhen` is false, the Action is recorded as SKIPPED and no child Tool, DB Action, or Flow is executed. This is new Action behavior; it is separate from the existing Stage-level `runWhen` contract.

## 5. Type and binding contract

Each Flow input declares one supported type. `required: true` and `default` are mutually exclusive. A default must match the declared type. An omitted optional input has the value `null`; ATT performs no implicit conversion.

`with` accepts scalar literals, maps, arrays, and recursively embedded `${...}` references. Binding never executes `#{...}` calls.

Validation is split by what can be known safely:

- literals are type-checked while compiling the invocation;
- an exact declared Flow input or Flow output reference is checked statically;
- Case values and other runtime-dependent values are checked immediately before entering the Flow.

`integer` may bind to `number`; other declared types must match exactly.

Each output declares `type` and one exact `from: "${...}"`. ATT evaluates and type-checks outputs only when all effective Flow Actions aggregate to PASS. FAIL, ERROR, or INVALID Flows export no declared output.

## 6. Context isolation

Inside a Flow, only these local roots are readable:

| Root | Meaning |
|---|---|
| `input.*` | The invocation's validated inputs |
| `actions.*` | Outputs from prior local Actions |
| `runtime.*` | Values assigned locally by `assign` |
| `flow.*` | Current Flow ID, invocation ID, and depth |

`${...}` reads Context values. `#{...}` invokes a Tool, DB facade, or built-in where that Action contract permits calls.

A Flow cannot read `CASE`, `RUN`, a parent Flow, a sibling Flow, or a future Action. It may read a prior local Action only through `${actions.<id>.output...}`. A local `assign` writes `runtime.<name>` and never changes `CASE.VARS`.

The caller sees only declared outputs:

- Template caller: `${ACTIONS.<flowAction>.output.outputs.<name>}`
- nested Flow caller: `${actions.<flowAction>.output.outputs.<name>}`

Internal Actions remain in evidence but are not part of the caller's expression view.

## 7. Registry and static compilation

The package registry scans Flow descriptors once and indexes them by canonical ID. IDs are independent of directory paths, allowing internal directory reorganization without changing callers.

`validate --package` parses and validates every Flow. Selected validation indexes descriptor headers but parses only the dependency closure reached from selected V3 Templates. An unrelated invalid Flow therefore does not invalidate a selected-only operation.

Before output mutation, compilation checks:

- Flow and V3 Template schemas;
- duplicate IDs and unresolved references;
- required/unknown bindings and statically known type mismatches;
- direct and indirect cycles;
- maximum nesting depth 3;
- illegal or forward Context references;
- output source and type contracts;
- `template.yaml`/`flow.yaml` directory conflicts; and
- symlink and package-root escapes.

The compiled registry is carried by the execution plan. Runtime does not rescan the filesystem or dynamically choose a Flow.

## 8. Runtime semantics

For a Flow Action, the runner:

1. evaluates the caller Action's `runWhen`;
2. recursively resolves and type-checks inputs;
3. pushes an isolated Flow frame;
4. executes local Actions in declaration order;
5. applies each local `runWhen` and existing `onFailure` policy;
6. aggregates local results;
7. exports declared outputs only on PASS; and
8. records and pops the Flow frame.

An invoked Flow whose internal Actions are all SKIPPED is PASS. A Flow Action skipped by its caller is SKIPPED.

Each internal invocation receives a qualified ID. Artifacts use hierarchical paths such as:

```text
<case>/<stage>/flows/<invocation>/actions/<action>/...
```

Nested invocations add another `flows/<invocation>/actions` segment. Tool/DB executors, caches, logs, connection lifecycle, and sequence counters remain Case-level shared resources.

Case YAML and logs preserve nested Flow evidence. Existing workbook, HTML, JUnit, JSON, and CI consumers continue to use the top-level Flow Action result in V3.0.0.

## 9. Result aggregation

ATT uses one status ordering everywhere:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

For Flow aggregation, any ERROR wins, then INVALID, then FAIL. Otherwise the Flow is PASS, including the all-internal-SKIPPED case. `onFailure: stop` stops remaining sibling Actions after a non-PASS result; `continue` records the result and continues.

## 10. Delivery plan and acceptance

### 10.1 Design and schemas

- publish the V3 schemas and schema catalog entries;
- provide three complete Flows and one composition Template; and
- compile both schemas as external Draft 2020-12 documents.

### 10.2 Static Flow compiler

- load the package registry or selected dependency closure;
- validate contracts, graphs, Context access, paths, and depth before output creation; and
- store resolved definitions in the execution plan.

### 10.3 Isolated runtime

- execute nested Flow Actions with isolated frames;
- preserve existing Tool/DB resources and failure semantics; and
- write qualified hierarchical evidence and artifacts.

### 10.4 Release acceptance

Required coverage includes:

- schema unknown fields, invalid types, required/default conflicts, dynamic `use`, and illegal Context;
- duplicate IDs, missing references, direct/indirect cycles, depth four, and path/symlink rejection;
- basic and nested calls, cross-Flow output, repeated reuse, same-named internal Actions, local assign, `runWhen`, stop/continue, and failed-output suppression;
- nested evidence, artifact qualification, caller isolation, and unchanged top-level consumers;
- unchanged V2.6 Template execution and V2 rejection of V3 fields; and
- 200 Flows with 2,000 references compiling within 10 seconds, with each descriptor parsed at most once per package build.

The release gate is:

```text
mvn test
./att.sh validate --package
./att.sh docs
./build.sh
unpack the V3 binary package and run ./att.sh validate --package
git diff --check
```
