# Legacy Package migration

The Native Case model and existing Package execution can coexist while a Package is migrated.

## Use the isolated Package

The former payment, FPP, and `V3_FLOW_EXAMPLE` assets are grouped in `packages/legacy`. Their descriptors, workbook Case IDs, Tools, and local dependencies remain together. Select the Package explicitly for Local CLI work:

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
```

The LOAD examples that select `V3_FLOW_EXAMPLE` continue to use that identifier. A Package move does not translate those files or change their Case IDs.

## Choose a conversion boundary

Do not translate a Stage or Template automatically when the conversion would change selected work, Action ordering, Case IDs, or report layout. The equivalence analysis remains tracked by [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398). Native LOAD Case targets remain tracked by [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418). Keep the existing runtime contract available until each consumer has a verified replacement.

The [migration matrix](../maintenance/example-migration-matrix.md) records migration status, runtime support, offline-CI coverage, and each file's known consumers. `legacy` describes ownership and support; it does not mean a resource cannot run.

## Start new work with Native Cases

New work can use a Test Case Definition, a Flow, and an inline or Excel Data Source. The [Native Quick Start](../quick-start.md) runs offline and does not require converting an existing Package first.
