# Configuration and environments

Shared Runtime Configuration selects the Environment, output behavior, and global Resource bindings. Native Package roots and Package-owned resources are defined by the [Package Manifest](package-manifest.md). Existing packages authored with earlier configuration fields can use the [Legacy configuration guide](../legacy/configuration.md).

## Configuration layers

| Layer | Source | Owns |
|---|---|---|
| Shared Runtime Configuration | `ATT_HOME/config/config.yaml` | Environment selection, execution defaults, reporting limits, and shared DB/MQ/HTTP/SSH Resources |
| Environment profile | `environments.<name>` in shared configuration | Environment-specific Resource bindings |
| Package Manifest | `<PACKAGE_ROOT>/package.yaml` | Package identity, Flows, Test Case Definitions, Tools, Testdata, and Package-owned files |
| CLI options | Current command | One-command selection, Environment, identity, and output overrides |

The Package Manifest controls Package resource roots and Testdata. A Native Package does not need a separate runtime configuration, schema tree, or helper registry.

## Select an Environment

Use one shared configuration file and select the Environment with `--env`. The top-level `environment` value is the default.

~~~yaml
schemaVersion: att-config/v2.15
environment: SIT
outputDirectory: output
timeoutMs: 30000
dbhelpers:
  - config/dbhelpers/orders.yaml
mqhelpers:
  - config/mqhelpers/payment.yaml
environments:
  SIT:
    dbhelpers:
      - config/dbhelpers/sit/orders.yaml
    mqhelpers:
      - config/mqhelpers/sit/payment.yaml
  UAT:
    dbhelpers:
      - config/dbhelpers/uat/orders.yaml
    mqhelpers:
      - config/mqhelpers/uat/payment.yaml
~~~

~~~sh
./att.sh validate --package --config config/config.yaml --env SIT
./att.sh validate --package --config config/config.yaml --env UAT
./att.sh --local -p default run native-case payments.offline.authorize --resolved-case-id auth-accepted --config config/config.yaml --env SIT
~~~

Profiles replace the configured Resource lists as a whole. Keep logical Resource IDs stable across Environments; use profile bindings to select endpoints and credentials.

## Configure execution identity and output

| Field | Default | Meaning |
|---|---|---|
| `outputDirectory` | `output` | Package-relative root for local output |
| `timeoutMs` | `10000` | Default execution timeout, from 1 to 3,600,000 milliseconds |
| `run.id.default` | `timestamp` | The only supported Run ID default |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Java date/time format for generated IDs |
| `execution.runIdFormat` | unset | Optional expression for the outer RUN or LOAD identity |
| `execution.debugIdFormat` | unset | Optional expression for standalone Flow or Tool Debug |
| `execution.processOutput.memoryLimitBytes` | `65536` | Bounded stdout/stderr preview retained in memory |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Maximum bytes streamed to each process artifact |
| `report.html.caseLogInlineLimitBytes` | `32768` | Inline case-log limit in HTML output |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | JUnit case-log embedding threshold |

Explicit `--run-id` and `--debug-id` values override generated identities. Generated values must be safe single path segments and unique within the selected output root. Identity expressions run before execution; they cannot read per-Case or per-iteration values.

The Native RUN result workbook is written as `workbooks/native-cases.result.xlsx`. Earlier source-workbook report naming is described in the [Legacy configuration guide](../legacy/configuration.md).

## Ignore or disable ATT-owned configuration with x

The lowercase `x-` prefix can disable ATT-owned fields and keyed entries at schema-supported extension locations before validation or execution. It does not remove user data such as HTTP header names or DB parameters. `runWhen: false` is a runtime condition and does not disable configuration fields.

## Resource bindings and credentials

The shared configuration owns DB, MQ, HTTP, and SSH Resource descriptors. Paths must remain within their configured runtime root. Environment profiles can replace each Resource list.

Use the approved secret-injection syntax where supported. Never commit credentials or place resolved secret values in logs, report fields, or diagnostic output. Missing-variable messages identify only the required name.

## Validate changes

~~~sh
./att.sh validate --package --format json --quiet
./att.sh validate --package --config config/config.yaml --env UAT
~~~

Validation checks configuration schemas, profile references, Resource descriptors, Package resources, and dependency paths. It is read-only. It does not submit a Job, execute a Tool, or start a Load scheduler.

For Native Package roots and Manifest fields, see [Package Manifest](package-manifest.md). For shared Resource contracts, see [Resources](resources/overview.md).
