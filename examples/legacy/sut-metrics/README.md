# Archived SUT Metrics catalog

These files are the former Settings catalog and v1 composite Profile examples. They are compatibility history, not a current configuration.

Do not register `source.json`, `profile-jvm.json`, `profile-jvm-prod.json`, `profile-app.json`, or `profile-platform.json` in **Settings → SUT Metrics administration**. Global Settings now owns Data Source connection descriptors only. Metric Sources, Groups, and Profiles belong to a Package inventory (`att-package/v2`).

| File | Schema | Why it is here |
|---|---|---|
| `source.json` | `att-sut-metrics-source/v1` | Old mutable catalog Data Source. The URL is `https://prometheus.example.invalid` and must not be queried |
| `profile-jvm.json`, `profile-jvm-prod.json`, `profile-app.json` | `att-sut-metrics-profile/v1` | Base profiles that were registered in Settings |
| `profile-platform.json` | `att-sut-metrics-profile/v1` composite | Includes the base profiles by ID |
| `scenario.yaml` | `att-load/v1.9` | Binds the catalog Profile `profile-platform` and the placeholder template `PAYMENTS_HEALTH` |

`scenario.yaml` is not an offline LOAD example. It names a template that is not in the Default Package and a catalog Profile that is no longer registered. Routine CI does not execute it.

The current path is [examples/package-local-sut-metrics](../../package-local-sut-metrics/README.md). Follow [the migration guide](../../../docs/guides/sut-metrics-config-migration.md). Schema-level v2 contract samples that tests still load stay at [examples/load/sut-metrics/v2](../../load/sut-metrics/v2/metric-source-db-wide-row.json).
