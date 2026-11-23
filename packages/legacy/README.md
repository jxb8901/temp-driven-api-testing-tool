# Legacy Package

[繁體中文](README.zh.md)

This package is a registerable home for example Templates and workbooks that are not Native Flow-bound Cases. `package.yaml` is the manifest. It does not by itself add the Package to a Server registry. Local CLI selects it with `ATT_PACKAGE_ROOT`. `config/att-server.example.yaml` lists `default` and `fpp-common` only. A deployment registers this Package by adding an entry whose id is `legacy` and whose path is this directory, inside `packages.allowedRoots` and `packages.entries`. Until that entry exists, Server and Studio do not list it. The Template IDs and workbook Case IDs are unchanged.

These resources are not translated to `att-testcase/v1`. A translation would replace Stage selection, `runWhen` / `onFailure`, or workbook Case IDs, and there is no golden that preserves that behavior. Run and Debug them here. Do not point Quick or Advanced Load at `PAYMENT_*`, the FPP Templates, or the local-payment Template unless the scenario supplies an explicit business TestCase ID. `examples/load/closed.yaml` and `examples/load/closed-minimal.yaml` keep target id `V3_FLOW_EXAMPLE` and run with this package as the working directory.

`templates/flows/common/` and `tools/` in this package are the package-local copies those Templates resolve. Tool and Flow lookup does not cross into `packages/default`. The canonical current copies of the common Flows and the current Tool catalog remain in `packages/default`.

Offline checks from the repository root. `./att.sh` changes into the package named by `ATT_PACKAGE_ROOT`:

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh run --suite testcase/payment_regression.xlsx --dry-run
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/load/closed-minimal.yaml --users 1 --duration 100ms
```

`payment_regression` dry-run selects 22 Cases and skips all 22. It does not call FPP, MQ, or DB. A live business run of these Templates is outside this package move.
