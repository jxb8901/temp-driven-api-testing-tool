# SUT Metrics Runtime Config migration

ATT 4.3.1 reads Data Sources, Metric Sources, Groups, and Profiles from the selected server Runtime Config. The `sut-metrics-catalog.json` file in the Server data directory is no longer an active source. Keep it until migration is complete; the Server ignores it after switching to config-backed mode.

## Author definitions in YAML

Use `att-config/v2.14` and the bounded `sutMetrics` arrays. See [`examples/config/sut-metrics-v2.14.yaml`](../../examples/config/sut-metrics-v2.14.yaml). Do not add `createdAt`, Data Source revision, Metric Source connection revision, or flat Profile revision hashes. ATT derives revisions from the normalized definition and active Runtime Config. Store only secret references such as `ATT_SECRET_PROMETHEUS_TOKEN`; never store secret values or absolute paths.

During activation, every referenced Data Source credential and optional CA secret must resolve on the Server as `ATT_SECRET_<secretRef>`. HTTPS hosts must also appear in the exact-host `ATT_SUT_METRICS_ALLOWED_HOSTS` list; only loopback HTTP is exempt. A missing secret or disallowed host rejects the candidate and leaves the last-known-good catalog active.

Admin catalog reads and previews remain available. Admin API mutations return HTTP 409 `ATT-SUT-METRICS-CONFIG-READ-ONLY` until the central Runtime Config writer is available. Publish changes through the approved Runtime Config change process. Invalid candidate YAML is rejected while the last-known-good catalog remains active.

DBHelper Metric Sources resolve against the existing DBHelper descriptors in each Runtime Config Environment. A flat Profile's `allowedBindings.environments` must name declared environments, and each referenced DBHelper must exist in every environment allowed by that Profile. Package Tool connections remain package-scoped and are checked against the selected Package and Environment when a LOAD is admitted.

## Migrate the legacy JSON catalog

First create and review a separate validated candidate:

```sh
scripts/migrate-sut-metrics-catalog.sh \
  --config /absolute/path/config/config.yaml \
  --catalog /absolute/path/data/sut-metrics-catalog.json \
  --output /absolute/path/config/config.sut-metrics-candidate.yaml
```

Compare the candidate with the source config, verify secret references and endpoints, then validate it in the intended deployment environment. The migration rejects duplicate IDs and any different pre-existing `sutMetrics` definitions. It does not merge or overwrite a conflicting catalog.

After review, apply explicitly:

```sh
scripts/migrate-sut-metrics-catalog.sh \
  --config /absolute/path/config/config.yaml \
  --catalog /absolute/path/data/sut-metrics-catalog.json \
  --apply
```

Apply rechecks both input files, writes digest-named read-only backups for the Runtime Config and old catalog, and atomically publishes the validated `att-config/v2.14` candidate. It leaves the original catalog in place for rollback; the Server does not consult it. Repeating the same migration is a no-op. If the Runtime Config already contains matching definitions, the command reports that state; different definitions fail without merging.

The previous ATT config schema remains readable for configurations without `sutMetrics`. Migration upgrades the config version to v2.14 so the new strict block is accepted. Jobs already submitted retain their pinned SUT Metrics snapshots and revisions.
