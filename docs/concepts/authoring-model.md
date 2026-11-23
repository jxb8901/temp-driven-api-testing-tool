# Authoring model

ATT keeps business data separate from reusable execution logic.

## Current Case authoring

The current Case authoring path is:

~~~text
Flow + testcase.yaml + Data Source
    -> Resolution
    -> Resolved Case
    -> Suite selection
    -> Case Execution
~~~

A **Flow** (`att-flow/v3.6`) is reusable ordered Actions. A **Test Case Definition** (`att-testcase/v1`, usually `testcase.yaml`) binds one Flow and one Data Source. **Resolution** turns that Definition into Resolved Cases. **Suite selection** chooses which of those Cases to run. There is no mandatory `suite.yaml`. **Case Execution** is one RUN of one selected Resolved Case, with its own execution ID, status, and evidence.

This is the primary model for new Cases. The [Test Authoring Reference](../reference/test-authoring.md) is the normative contract. The implementation is integrated on the current default branch. Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed, which accepts Native RUN for that issue. No ATT 4.6.0 GitHub Release or tag has been published, so closure of #393 is not a product release. Implicit Suite acceptance ([#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396)), Native report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)), and remaining Default Package adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) stay open. Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), n-to-m generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) stay deferred.

### V1 resolves one source record to one Case

`att-testcase/v1` is an exact 1:1 source-record contract:

- A Definition that supplies only a typed `inputs` map resolves to one Case. Its Resolved Case ID is `default`.
- An inline or Excel Data Source resolves each nonblank source record to exactly one Resolved Case. Resolution does not expand, join, or invent extra Cases.
- `id` is the Package-scoped Definition ID. A Resolved Case ID is one path-safe segment. The qualified ID is `definitionId.caseId`.
- `caseIdExpression` may replace the source record ID with one `${DATA.key}` lookup. It is not a generator and cannot call Tools or create additional Cases.
- Typed `inputs` project source fields into the Flow. Omitted projections use same-name `DATA` fields. An authored projection uses `{from: DATA.key}` or `{value: <typed value>}`.
- Excel `dataColumns` keeps the `att-sidecar/v2.2` comma-separated syntax, including aliases, `(yaml)` parsing, and quoted delimiters. Omitted or blank `dataColumns` copies no business columns into `DATA`.
- Reports and Package files stay isolated. RUN writes new artifacts. It does not modify the Definition, the Excel Data Source, or a legacy Snapshot.

Proposed n-to-m generators, boundary expansion, and Testdata-driven Case production belong to [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419). They are not part of `att-testcase/v1`.

## Supported legacy authoring

Workbook, Stage, and Template remain a supported runtime. They are not the only Case authoring model, and nothing on this page retires them.

~~~text
Workbook row -> Stage -> Template -> Action -> Resource
                                |          -> Flow
                                ------------> Flow
~~~

A legacy Testcase is authored in a workbook row and an optional YAML sidecar. A Stage selects a Template. A Template declares ordered Actions. Actions can call Tools and Helpers or reuse a Flow. A Flow is reusable execution logic with its own ordered Actions. Resources include shared Tools, DBHelpers, MQHelpers, HTTPHelpers and SSHHelpers, plus Package-local scripts and Testdata.

The workbook is one supported way to author Cases. Studio and the Server also run direct Template, Flow, and Tool targets, and they run the Native Definition path above.

### Snapshots are explicit authoring

For workbook-based Cases, Snapshot generation creates the canonical XML representation of the selected workbook. It is an explicit authoring operation. VALIDATE reads the available authored inputs and checks them; it never creates or refreshes a Snapshot as a side effect.

The legacy Quick Start keeps that explicit sequence: generate the Snapshot, validate the Package, then execute a Case. See the legacy appendix in [Quick Start](../quick-start.md) and the [Test Authoring Reference](../reference/test-authoring.md).

## Data boundaries

Testdata descriptors belong to the Package and are selected through the Package Manifest or a supported invocation. Shared Runtime Configuration owns reusable external-resource bindings. Keep secrets in protected runtime inputs or secret stores; do not copy credentials into workbook rows, Definitions, Templates, or documentation examples.

## Normative owners

The [Test Authoring Reference](../reference/test-authoring.md) owns Native Definitions and legacy workbook, sidecar, and Snapshot behavior. The [Actions Reference](../reference/actions.md) owns Action execution. Resource contracts live in the [Resources Reference](../reference/resources/overview.md).
