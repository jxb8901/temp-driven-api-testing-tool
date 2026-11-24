# Legacy LOAD scenarios

These examples keep Template targets and are separate from the current Flow and Tool scenarios in `examples/load/`. Their target resources are in `packages/legacy`; the package must be selected as the working directory.

| Scenario | Purpose |
|---|---|
| `closed-minimal.yaml` | Minimal closed VU run of `V3_FLOW_EXAMPLE`. |
| `closed.yaml` | Full Template target workload with inputs, think time, thresholds, and evidence. |
| `testdata-generated.yaml` | Iteration-scoped generated Testdata mapped into the offline `QUICK_START` Template. |

Run the examples from the repository root:

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed.yaml --users 1 --duration 100ms
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/testdata-generated.yaml
```

The generated Testdata scenario consumes four records from `examples/testdata/generated-account.yaml` and stops when the records are exhausted. These scenarios exercise the retained Template-target LOAD path; see the [Legacy documentation](../../../docs/legacy/README.md).
