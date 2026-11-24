# Legacy authoring

This guide describes the workbook-based authoring contract retained for existing Packages.

## Resource chain

```text
Workbook row -> Stage -> Template -> ordered Actions -> Tools and Helpers
                                         `-> Flow
```

A Testcase row supplies Case data. A YAML sidecar defines worksheet groups, headers, columns, and Stage selectors. Snapshot generation writes the canonical XML representation beside the workbook. A Stage selects a Template and contributes Stage-specific values. A Template declares ordered Actions and can call a Flow. Flows, Tools, DBHelpers, MQHelpers, HTTPHelpers, SSHHelpers, scripts, and Testdata remain Package-scoped resources.

## Workbook, sidecar, and Snapshot files

Workbook-based Packages use a same-basename `.xlsx`, `.yaml`, and `.xml` set in the configured testcase directory. The YAML file uses `att-sidecar/v2.2`; the generated XML file uses `att-testcases/v2.4`. Edit the workbook and sidecar, regenerate the XML Snapshot, review all three files, and validate the Package before running it.

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh snapshot --suite testcase/quick_start.xlsx
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
```

Do not edit generated XML by hand. VALIDATE reads existing files and does not create or refresh a Snapshot.

## Stages and Templates

A Stage selector identifies a Template. A Template descriptor is `template.yaml` under its Package resource root and uses `att-template/v3.6`. A Template declares a nonempty ordered `actions` map. Stage values can override same-named Case inputs for that Stage; the override ends when the Stage completes.

Case IDs preserve the workbook identity path: `workbookId.groupId.rowCaseId`. Keep the workbook ID, group ID, row IDs, and Stage keys stable when compatibility with existing reports or automation matters.

## Data columns and blank values

The sidecar `dataColumns` string supports comma-separated column declarations, aliases, `(yaml)` parsing, and quoted delimiters. A source column must be explicitly selected before its value is available to the Testcase. Blank markers such as empty text, `N/A`, `NA`, `NULL`, and `NONE` normalize to blank before mapping and Stage selection.

Excel formula cells, date/percentage/scientific notation cells, multi-row headers, XML entity escaping, and size limits are covered by the maintained [migration matrix](../maintenance/native-case-default-package-migration-s12.md) and the `att-testcases/v2.4` schema.

## Flow reuse

A Flow is an ordered sequence of Actions declared in `flow.yaml` using `att-flow/v3.6`. A Template can call a Flow with a static canonical `use` ID. Each invocation receives a fresh `EXEC.ACTIONS` scope; the caller scope is restored on return. `META.FLOW` exists only during that invocation.

## Related contracts

| Legacy schema | Active version |
|---|---|
| Workbook sidecar | `att-sidecar/v2.2` |
| Testcase Snapshot | `att-testcases/v2.4` |
| Template descriptor | `att-template/v3.6` |

The exact input, selector, and validation fields are defined by `schemas/att-sidecar-v2.2.schema.json`, `schemas/att-testcases-v2.4.schema.json`, and `schemas/att-template-v3.6.schema.json`. Package-local Resource lookup does not cross into another Package.
