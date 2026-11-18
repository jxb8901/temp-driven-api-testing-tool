# Package-local SUT Metrics example

This example shows the 4.3.1 ownership split:

- `config/config.yaml` references global Data Source descriptors. The UAT profile replaces the descriptor path while retaining the logical ID `prom-main`.
- `packages/payments/package.yaml` explicitly inventories one Metric Source, Group, and Profile under the matching `load/sut-metrics/` directories.
- The three Package definitions do not contain environment endpoints or credentials. They are the same in SIT and UAT.

The `PAYMENTS_HEALTH` target in the sample Profile binding is illustrative. Replace it with a target that exists in the Package using the example. Configure the referenced secrets in the ATT Server secret store and allow only the exact Prometheus hosts required by each environment. The `.example.invalid` URLs are placeholders and cannot be queried.

Each definition file contains one versioned document. The example does not configure SQL/DBHelper or Package Tool collection, live previews, or Long Rows. No SUT Metrics are enabled by copying these files alone; the Package manifest inventory and a LOAD scenario Profile binding are both required.
