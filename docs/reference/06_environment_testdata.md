## 06 Environment and Test Data

Environment selection changes resource binding, not Action logic.

### Environment profiles

`att-config/v2.6` may declare an `environment` default and an `environments` map. `--config` selects the base configuration file; `--env` selects one named binding inside that configuration. Explicit `--env` wins over the configured default. Unknown environments fail before external execution.

Profiles are typed shallow bindings, not generic recursive YAML inheritance. Current profile-owned lists are `dbhelpers` and `mqhelpers`: when a profile supplies one of those lists it replaces that resource list; an omitted list inherits the common root list.

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

The descriptor in every environment should expose the same stable logical IDs (`orders`, `payment`, etc.). Template/Flow/Action references therefore remain unchanged across SIT/UAT/PREPROD.

### Topology and secrets

Topology may vary by descriptor/environment. Secrets should be injected through `${ENV:NAME}` where the descriptor supports it and must not be committed or surfaced in effective metadata/diagnostics. Missing required environment variables are validation/configuration errors that identify the field/variable name without printing a resolved secret.

### Cross-mode consistency

Run, Validate, Debug and Load resolve the environment through the same effective-config step before their mode-specific work. `--env` therefore cannot be used as Action branching and does not create mode-specific helper IDs.

### Migration from separate configs

Existing separate `--config config/environments/sit.yaml` / `uat.yaml` workflows remain useful when whole configurations genuinely differ. Profiles are preferable when the package contract is common and only typed DB/MQ bindings vary. Separate configs remain preferable for materially different package policy, roots, Tool topology or configuration ownership.

### Test data extension point

Workbook/sidecar/snapshot remains the current Testcase data contract. Future logical environment-bound fixtures (#38) belong in this chapter and should follow the same stable logical-name principle rather than introducing environment branches into Actions.
