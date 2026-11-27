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

This is the primary model for new Cases. The [Test Authoring Reference](../reference/test-authoring.md) is the normative contract. The implementation is integrated on the current default branch. Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed, which accepts Native RUN for that issue. No ATT 4.6.1 GitHub Release or tag has been published, so closure of #393 is not a product release. Implicit Suite acceptance ([#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396)), Native report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)), and remaining Default Package adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) stay open. Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), n-to-m generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) stay deferred.

### V1 resolves one source record to one Case

`att-testcase/v1` is an exact 1:1 source-record contract:

- A Definition that supplies only a typed `inputs` map resolves to one Case. Its Resolved Case ID is `default`.
- An inline or Excel Data Source resolves each nonblank source record to exactly one Resolved Case. Resolution does not expand, join, or invent extra Cases.
- `id` is the Package-scoped Definition ID. A Resolved Case ID is one path-safe segment. The qualified ID is `definitionId.caseId`.
- `caseIdExpression` may replace the source record ID with one `${DATA.key}` lookup. It is not a generator and cannot call Tools or create additional Cases.
- Typed `inputs` project source fields into the Flow. Omitted projections use same-name `DATA` fields. An authored projection uses `{from: DATA.key}` or `{value: <typed value>}`.
- Excel `dataColumns` selects and projects the business columns exposed to `DATA`.
- Reports and Package files stay isolated. RUN writes new artifacts and does not modify the Definition or Data Source.

Proposed n-to-m generators, boundary expansion, and Testdata-driven Case production belong to [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419). They are not part of `att-testcase/v1`.

## Data boundaries

Testdata descriptors belong to the Package and are selected through the Package Manifest or a supported invocation. Shared Runtime Configuration owns reusable external-resource bindings. Keep secrets in protected runtime inputs or secret stores; do not copy credentials into Definitions or documentation examples.

## Normative owners

The [Test Authoring Reference](../reference/test-authoring.md) owns Native Definitions and Flow behavior. The [Actions Reference](../reference/actions.md) owns Action execution. Resource contracts live in the [Resources Reference](../reference/resources/overview.md). For an existing Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md).
