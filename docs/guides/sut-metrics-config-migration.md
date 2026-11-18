# Migrate SUT Metrics to Package-owned definitions

ATT 4.4.0 separates shared connections from metric definitions. Global `att-config/v2.15` keeps Data Source connection descriptors. The selected Package owns its Metric Sources, Groups, and Profiles through an explicit `att-package/v2` inventory. A LOAD scenario must select a Profile from that Package before metrics are collected.

The complete file layout is in the [Package-local SUT Metrics example](../../examples/package-local-sut-metrics/README.md). It includes the global SIT/UAT connection descriptors and one Package's definitions.

## Ownership after migration

| Item | Owner and location | Selection |
| --- | --- | --- |
| Data Source endpoint, authentication, access, and limits | Global config descriptors under `config/sut-metrics/` | `sutMetrics.dataSources` in the active global config environment |
| Metric Source definition | The owning Package, under `load/sut-metrics/sources/` | Explicit path in `package.yaml` |
| Group definition | The owning Package, under `load/sut-metrics/groups/` | Explicit path in `package.yaml` |
| Profile definition | The owning Package, under `load/sut-metrics/profiles/` | Explicit path in `package.yaml`, then selected in a LOAD scenario |

Global Settings remains the connection authority. It does not own Package Metric Sources, Groups, or Profiles. Package IDs form separate namespaces: two Packages may use the same definition ID, but a Package cannot import definitions from another Package.

## Migration steps

### Inventory existing definitions and their consumers

<!-- att-docs:historical -->
Start with the inline `sutMetrics` block in the existing `att-config/v2.14` file. The [v2.14 inline example](../../examples/history/config/sut-metrics-v2.14.yaml) shows the source layout; use it as a migration input, not as the 4.3.1 destination. Record each Data Source, Metric Source, Group, and Profile ID, the LOAD scenarios that select each Profile, and the Package that owns each scenario.
<!-- /att-docs:historical -->

If one central definition is used by several Packages, copy that definition into every Package that needs it. Resolve each Package's bindings and references locally. Do not create a shared global Metric Source or rely on cross-Package imports.

For a v2 Package with no SUT Metrics, leave its manifest without an `observability` inventory. ATT must not discover metric files by scanning directories or fall back to definitions in the global config or another Package. Pre-v2 Packages retain their existing global binding resolution while migrating; the Studio picker does not offer those Profiles for new bindings.

### Keep Data Sources global and move them into descriptor files

Create one versioned Data Source descriptor per file under the global config's `config/sut-metrics/` directory. In `att-config/v2.15`, `sutMetrics.dataSources` contains descriptor paths rather than inline Data Source objects:

```yaml
schemaVersion: att-config/v2.15
environment: SIT
sutMetrics:
  dataSources:
    - config/sut-metrics/prometheus-sit.yaml
environments:
  UAT:
    sutMetrics:
      dataSources:
        - config/sut-metrics/prometheus-uat.yaml
```

The example SIT and UAT descriptors both use `id: prom-main`; their endpoint and secret reference differ by environment. The UAT profile replaces the descriptor list as a whole, while the stable logical ID lets Package definitions keep referring to `prom-main` in both environments. Keep the Package files identical between SIT and UAT. Store credentials only in the server secret store, keep secret references in the descriptor, and allow only the required exact HTTPS hosts. The Server resolves a reference such as `PROMETHEUS_SIT_TOKEN` from `ATT_SECRET_PROMETHEUS_SIT_TOKEN`; every non-loopback HTTPS host must be listed in `ATT_SUT_METRICS_ALLOWED_HOSTS`.

Global `sutMetrics` now contains Data Source descriptor references only. Remove the old inline `metricSources`, `groups`, and `profiles` arrays after their definitions have been moved. Do not leave a second definition behind as a fallback. Existing DBHelper resources remain governed by their normal global configuration; this migration's Package Metric Source connections use `kind: DATA_SOURCE` and a global Data Source ID.

### Add explicit inventories to each Package manifest

Upgrade the Package manifest to `att-package/v2`. List every metric definition file explicitly under `observability`, using only the matching directory for each kind:

```yaml
schemaVersion: att-package/v2
metadata:
  name: Payments LOAD Package
defaultEnvironment: SIT
observability:
  metricSources:
    - load/sut-metrics/sources/app-runtime.yaml
  groups:
    - load/sut-metrics/groups/app-servers.yaml
  profiles:
    - load/sut-metrics/profiles/basic.yaml
```

Each listed YAML file contains exactly one existing versioned definition, not an array or a combined catalog. The Package Metric Source connects to the global descriptor by logical ID:

```yaml
schemaVersion: att-sut-metrics-metric-source/v1
id: app-runtime
name: Application CPU utilization
connection:
  kind: DATA_SOURCE
  id: prom-main
```

Keep the established fetch, mapping, Group, and Profile semantics while moving the definitions. Do not add a Data Source revision, environment-specific revision, or connection revision to the Package Metric Source. A Package Metric Source may use an existing global DBHelper in the selected Environment, or a Package Tool owned by the same selected Package; it cannot use a Tool from another Package. The example tree demonstrates `DATA_SOURCE` connections only. Preserve bounded fetches and label projection; Prometheus mappings remain wide-row series, with no Long Rows representation.

The example files show the complete manifest and definition documents:

- [Package manifest](../../examples/package-local-sut-metrics/packages/payments/package.yaml)
- [Metric Source](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/sources/app-runtime.yaml)
- [Group](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/groups/app-servers.yaml)
- [Profile](../../examples/package-local-sut-metrics/packages/payments/load/sut-metrics/profiles/basic.yaml)

### Select a Package-local Profile in each LOAD scenario

Select the Package that owns the scenario, then bind a Profile ID from that Package's inventory. For example, add or update this fragment in the Package's existing LOAD scenario:

```yaml
observability:
  schemaVersion: att-load-observability/v1
  collections:
    - profile: basic
  collection:
    mode: post-run
    before: 5m
    after: 2m
    step: 30s
  livePreview:
    enabled: false
```

Use the Profile ID (`basic`), not its filename. Keep the Profile's `allowedBindings` aligned with the scenario's environment, target, tenant, and parameters. The sample Profile allows SIT and UAT and uses `PAYMENTS_HEALTH` as an illustrative template target; replace that binding with a target in the consuming Package.

A definition's presence does not enable collection. A Package without an inventoried Profile, or a scenario with no Profile binding, collects no SUT Metrics. In particular, an empty or absent Package `observability` block means no metric inventory and has no implicit global fallback.

### Preview and validate the selected Package before publishing

In Studio, inspect the selected Package manifest, its inventoried files, and the scenario's Profile binding together. A definition preview should show the resolved Package-owned definitions and binding plan without executing a query. Treat endpoint checks or live metric previews as separate, explicit actions subject to the global Data Source permissions and host allowlist. Do not use a preview to execute SQL or Package Tools.

Resolve every issue before publishing: duplicate IDs within a Package, duplicate inventory paths, a path outside its matching `load/sut-metrics/` directory, missing references, an unavailable Data Source ID, an unsupported schema version, unauthorized bindings, or a fetch that exceeds configured budgets. Diagnostics should identify the Package and source file so the definition can be corrected at its owner.

### Resolve legacy conflicts explicitly

<!-- att-docs:historical -->
Before switching the global config to v2.15, remove the retired inline Metric Source, Group, and Profile arrays from v2.14. If a legacy global definition and a Package-local definition claim the same ownership or ID, stop and choose the intended Package owner. Do not merge the two definitions, silently prefer one, or fall back to the legacy copy. Duplicate IDs in different Packages are valid because Package IDs scope their definitions; duplicate IDs within one Package are conflicts.

The old server-side `sut-metrics-catalog.json` is not an active source for Package-owned definitions. If it contains records that were not represented by the v2.14 inline config, reconcile those records explicitly with their owners before retiring the old catalog. This guide does not define a migration command.
<!-- /att-docs:historical -->

### Preserve execution provenance

A submitted LOAD job must retain the selected global config digest and environment, Package identity and manifest/resource digests, effective definition revisions, and a redacted fetch plan. This ties collected metrics and their reports to the exact Package files and global connection configuration used for that job. Publishing later edits makes them available to newly validated jobs; it must not rewrite the provenance or sealed artifacts of existing jobs.

## Final review checklist

- Global v2.15 references Data Source descriptor files under `config/sut-metrics/` only.
- SIT and UAT descriptors retain the same Data Source IDs; their secrets and endpoint details remain global.
- Every Package explicitly inventories its own sources, groups, and profiles using paths in the matching directories.
- Every inventoried file contains one versioned definition, with no environment-specific connection revision.
- Package Metric Sources use `DATA_SOURCE` and a stable logical Data Source ID.
- LOAD scenarios select a Profile from the selected Package, with bindings allowed for the active environment and target.
- No Package or scenario means no metrics; no directory scan, cross-Package import, merge, or legacy fallback is needed.
- Previews are reviewed before any explicit live endpoint check, and job artifacts preserve Package provenance.
