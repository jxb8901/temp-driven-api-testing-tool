# Authoring model

ATT keeps business data separate from reusable execution logic.

## Functional resources

A Testcase is authored in a workbook row and optional YAML sidecar. A Stage selects a Template. A Template declares ordered Actions. Actions can call Tools and Helpers or reuse a Flow. A Flow is reusable execution logic with its own ordered Actions. Resources include shared Tools, DBHelpers, MQHelpers, HTTPHelpers and SSHHelpers, plus Package-local scripts and Testdata.

The common relationship is:

~~~text
Testcase -> Stage -> Template -> Action -> Resource
                         |          -> Flow
                         ------------> Flow
~~~

The workbook is one supported way to author Cases. It is not the complete ATT resource model: Studio and the Server also work with direct Template, Flow and Tool targets.

## Snapshots are explicit authoring

For workbook-based Cases, Snapshot generation creates the canonical XML representation of the selected workbook. It is an explicit authoring operation. VALIDATE reads the available authored inputs and checks them; it never creates or refreshes a Snapshot as a side effect.

The current 4.2.7 Quick Start keeps the explicit sequence: generate the Snapshot, validate the Package, then execute a Case. See [Quick Start](../quick-start.md) and the [Test Authoring Reference](../reference/test-authoring.md).

## Data boundaries

Testdata descriptors belong to the Package and are selected through the Package Manifest or a supported invocation. Shared Runtime Configuration owns reusable external-resource bindings. Keep secrets in protected runtime inputs or secret stores; do not copy credentials into workbook rows, Templates or documentation examples.

## Normative owners

The [Test Authoring Reference](../reference/test-authoring.md) owns workbook, sidecar and Snapshot behavior. The [Actions Reference](../reference/actions.md) owns Action execution. Resource contracts live in the [Resources Reference](../reference/resources/overview.md).
