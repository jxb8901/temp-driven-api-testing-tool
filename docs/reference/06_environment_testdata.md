## 06 Environment and Test Data

Environment selection changes resource bindings, not Action logic.

### Environment profiles

`att-config/v2.10` defines the current `environment` default and `environments` map. `--config` selects the shared configuration file; `--env` selects one profile and overrides the configured default. Matching is case-insensitive. Unknown profiles fail before external execution.

Profiles are typed shallow bindings, not generic recursive YAML inheritance. The profile may replace each configured `dbhelpers`, `mqhelpers`, `sshhelpers`, or `httphelpers` list as a whole; an omitted list inherits the common root list.

```yaml
schemaVersion: att-config/v2.10
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    sshhelpers: [config/sshhelpers/sit/application.yaml]
    httphelpers: [config/httphelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    sshhelpers: [config/sshhelpers/uat/application.yaml]
    httphelpers: [config/httphelpers/uat/payment.yaml]
```

Each environment should expose the same stable logical IDs (`orders`, `payment`, `application`, etc.). Templates, Flows, Actions, and Tool-group bindings then remain unchanged across SIT/UAT/PREPROD. SSH Resource Helper calls publish only the logical helper ID and type as `META.SSHHELPER`; endpoint and credential details remain private. See the [SSHHelper chapter](05_resources/sshhelper.md) and [Runtime Context inventory](03_runtime_context.md).

### Topology and secrets

Topology may vary by descriptor and environment. Inject secrets through `${ENV:NAME}` where supported; never commit them or expose resolved values in META, reports or diagnostics. Missing required variables identify the field/name without printing the secret.

### Cross-mode consistency

Run, Validate, Debug, and Load resolve the selected environment through the same effective configuration. `--env` is not Action branching and does not create mode-specific helper IDs.

### Separate configuration files

Separate `--config config/environments/sit.yaml` and `uat.yaml` files remain useful when package roots, report policy, Tool topology, or other configuration intentionally differ. Use profiles when the package contract is shared and only resource bindings change.

### Test data extension point

Workbook/sidecar/snapshot remains the Testcase data contract. Environment-bound business inputs belong in `EXEC.INPUT`; environment selection belongs to configuration, not Action expressions.
