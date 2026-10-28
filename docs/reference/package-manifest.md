# Package Manifest

Use `<PACKAGE_ROOT>/package.yaml` to give one Package its own defaults, business roots, and Testdata inventory. The optional file uses `att-package/v1`, independently of Global Configuration. The matching Engine JAR supplies the authoritative schema.

A Package ID and its filesystem root come from trusted registration or the supported Local package selection. A Manifest never registers a Package, grants access, or supplies a privileged runtime configuration. Shared profiles, Tools, and DB/MQ/HTTP/SSH Helpers remain owned by Global Configuration.

## Declare Package defaults

This example selects an existing central profile and declares two Package-owned Testdata files. Create those descriptors before validating the Package.

```yaml
schemaVersion: att-package/v1
metadata:
  name: Payments
  description: Payment regression resources
defaultEnvironment: SIT
resources:
  templatesRoot: templates
  testcaseRoot: testcase
testdata:
  descriptors: [testdata/accounts.yaml]
  environments:
    UAT: [testdata/uat-accounts.yaml]
    PROD: []
defaults:
  timeoutMs: 15000
  report:
    columns: {result: Result, durationMs: Duration}
  xml: {namespaceMode: preserve}
```

`SIT`, `UAT`, and `PROD` must exist in the central `environments` mapping. Omitting a profile's Testdata entry inherits `descriptors`. A present list replaces that inventory completely; `[]` clears it.

Paths use forward slashes and stay relative to the selected Package. Absolute paths, `..`, unsafe symlinks, and escapes are rejected. The canonical file is limited to 262144 UTF-8 bytes. Unknown fields, duplicate YAML keys, aliases, anchors, and `x-` extensions are rejected.

Without a Manifest, the Package uses conventional `templates` and `testcase` roots and an empty Package Testdata inventory. Global `templates.root` and `testcase.root` no longer choose business roots.

The conventional `templates/`, `testcase/`, and `load/` directories are optional for inspection. If a directory is absent, Explorer, LOAD Catalog, and Quick Load return an empty inventory for that root. If a configured or conventional root exists but is not a readable directory, or resolves outside the canonical Package, inspection fails with a sanitized `ATT-RESOURCE-ROOT-INVALID` diagnostic; it never follows an unsafe path or treats an invalid present root as empty.

## Resolve an Environment

An explicit request or supported CLI `--env` selects the profile first. Otherwise, `defaultEnvironment` selects an existing central profile; the central default applies when the Manifest omits it. Profile names resolve to their canonical central spelling. The Effective view shows the selected profile and its origin.

Server policy applies after selection. A Package default cannot bypass the allowed list or explicit-selection requirement. New Server execution drafts, direct submissions, and schedules retain the resolved Environment. An omitted Environment on schedule creation inherits the validated draft's selection. A later default edit cannot redirect that snapshot; source revision checks may require a new review.

Registry adapters can use `FrameworkConfigLoader.loadContext` with an explicit `PackageContext`. Its Environment precedence is request → Context → Package → Global. Only the adapter-supplied registered Package ID enters provenance; a folder name or login does not supply an ID. The future CLI Context persistence commands remain a separate change.

Execution results retain a value-free `summary.configuration` snapshot: canonical Environment, original selection layer, Global and Manifest digests, effective configuration identity, Engine version/revision, Schema Registry digest, and field origins with logical authored paths. RUN, Debug, and Load write `configuration.json` alongside their managed artifacts. Server drafts, queued Jobs, and Schedule definitions retain the approved snapshot, including the registered Package ID and validated Package revision. Job history and results preserve it across Server restarts. Callers cannot supply these internal approval fields.

A Worker checks the approved effective configuration identity before execution. A Schedule using the `ignore` Package-change policy revalidates an execution copy against current sources; the stored approval remains unchanged, and the execution evidence retains its approved configuration identity for comparison. No configuration values, descriptor records, credentials, or host paths are included in this evidence.

## Apply typed defaults

The Manifest permits only these execution and presentation defaults. Unspecified fields inherit their global values.

| Manifest path | Behavior |
|---|---|
| `defaults.timeoutMs` | Positive integer, bounded by central `packagePolicy.maxTimeoutMs`; default cap 300000 ms |
| `defaults.run.id.default` | `timestamp` only |
| `defaults.run.id.timestampFormat` | Existing execution timestamp format validation |
| `defaults.execution.runIdFormat` | Existing Run/Load identity expression contract |
| `defaults.execution.debugIdFormat` | Existing Debug identity expression contract |
| `defaults.report.fileNamePattern` | Existing report filename contract |
| `defaults.report.columns` | Replaces the complete column mapping, including explicit `{}` |
| `defaults.report.html.caseLogInlineLimitBytes` | Integer 0–1048576 |
| `defaults.report.junit.caseLogEmbedThresholdBytes` | Integer 0–1048576 |
| `defaults.caseLog.yamlAnchors` | Boolean |
| `defaults.xml.namespaceMode` | `ignore` or `preserve` |

Output directories, process output limits, scheduling limits, authentication, credentials, helper definitions, worker settings, and schema locations cannot be overridden here. The Manifest schema in the embedded Engine registry defines the complete field whitelist.

## Resolve Testdata for one invocation

Base inventories belong to the selected Package and Environment. Another Package can use the same descriptor ID without sharing records or importing its neighbor's files.

Execution validates the base descriptors needed by the referenced Testdata closure. An unrelated missing or malformed base descriptor does not block a Quick Load that never references it. Invocation-local Debug/Load imports remain strict: explicitly imported missing or malformed files fail validation. An invocation descriptor replaces the entire base descriptor with the same ID; fields and records are not merged. Duplicate IDs within one layer remain errors.

Full Package validation checks every declared Manifest descriptor layer, including Environment-specific lists. For the bundled default Package, run:

```bash
./att.sh validate --package --format json
```

Inspect diagnostics for Package ownership, logical path, layer, and descriptor ID. Resolve full-validation failures before publishing or distributing a Package.

## Inspect and publish in Studio

With a registered Package selected, open **Configuration**, then **Package Configuration**. **Source**, **Declared**, **Effective**, and **Compare with Global** inspect this Manifest separately from **Shared Configuration**. The All Packages shared inspector remains central-only.

Source reading uses the existing source browsing policy and structured masking. Masked projections are read only. Editing requires enabled Server policy and an authenticated active USER whose exact login equals the explicitly registered Package ID. Other USERs retain ordinary operations. An ADMIN-only account has no implicit source-edit permission. Matching a login never registers a directory.

1. Open **Source** and edit the visible Manifest, or create a new optional Manifest.
2. Select **Create Manifest draft** to stage a private, expiring candidate against the displayed digest.
3. Select **Validate Manifest draft** to validate the candidate through the Worker without changing the live file.
4. Review the safe declaration, effective values, Environment, and origins; select **Review validated Manifest**.
5. Select **Publish reviewed Manifest**. The Server rechecks ownership, live digest, global/Package dependencies, and schema revision, then atomically replaces `package.yaml`.

Manifest publication fingerprints the central Runtime Config, explicitly listed Tool/Helper descriptors across its declared profiles, every candidate Testdata descriptor layer, business root bindings, and the embedded Schema Registry. It checks the fingerprint before and after validation and again at publication. It does not scan unrelated business files, archives, or unreferenced central files. The validation dependency set is bounded to 10000 distinct descriptor paths and 256 MiB of file content; these are dependency limits, not a total Package size limit. Execution and Job revisions retain their separate business dependency checks.

Edited source and pending drafts participate in Studio navigation protection. Refresh and view switching stay disabled until the source is staged or explicitly discarded. A conflict requires a fresh draft. Publication records an audit intent before replacement and a completion record afterward. History stays in private Server data; the API returns revision identifiers rather than raw historical source, ordered by stored modification time newest first with an ascending digest tie-breaker. **Stage rollback** creates another candidate that must pass the same validation and review gates. Restoring pre-Manifest defaults produces a canonical empty `att-package/v1` file, as shown in review.

The trusted deployment policy is optional and defaults to editing disabled:

```yaml
server:
  packageConfiguration:
    sourceEditingEnabled: false
    maxDrafts: 128
    maxDraftsPerUser: 8
    draftTtlSeconds: 900
    maxHistory: 50
    allowedEnvironments: [SIT, UAT]
    requireExplicitEnvironments: [UAT]
```

`allowedEnvironments: []` permits any centrally defined profile. `requireExplicitEnvironments` rejects implicit selection of its listed profiles before execution validation. Draft counts, expiry, history, and source size remain bounded. Source editing also requires inspection and the existing authenticated USER authorization path.

## Migrate legacy Package inventories

Global `testdata` and profile-level `testdata` lists are deprecated and ignored by default. The temporary central `packagePolicy.legacyTestdata: true` switch restores those inventories only for Packages without a Manifest. A Manifest takes ownership completely, including explicit empty inventories. Migration warnings identify the deprecated ownership; this compatibility switch is intended for a bounded migration window.

The migration helper produces a versioned read-only plan from an explicit trusted Server registry. Run it from the repository with a Python environment containing `tools/requirements-migration.txt`:

```bash
python tools/migrate_package_config.py --config config/config.yaml --server-config /ABSOLUTE/PATH/att-server.yaml
```

Replace `/ABSOLUTE/PATH/att-server.yaml` with your deployment registry. The helper inventories each registered Package's business text and bounded XLSX text. It proposes only locally owned, referenced descriptors; preserves explicit Environment empties; skips existing Manifests; and reports missing, unused, or unsafe imports. It lists inherited default field names without printing their values or descriptor records. Duplicate mapping keys and YAML aliases fail inventory. It does not publish files or infer registration.

Review each proposed Manifest, supply any required descriptors, and run full Package validation. Publish through the source workflow or your controlled deployment process. Then remove legacy global inventories and disable `legacyTestdata`.

## API paths

All paths are relative to `/api/v1`. Apply the existing authenticated USER, origin, CSRF, source policy, and request-size rules.

| Method and path | Result |
|---|---|
| `GET /packages/{id}/package-configuration/{source\|declared\|effective\|compare}` | Safe projection; optional `environment` query for effective selection |
| `POST /packages/{id}/package-configuration/drafts` | Stage `{source, expectedManifestDigest}` |
| `POST /packages/{id}/package-configuration/drafts/{draftId}/{validate\|review\|publish\|discard}` | Advance the private draft using `{}` |
| `GET /packages/{id}/package-configuration/history` | Owner-authorized bounded revision list |
| `POST /packages/{id}/package-configuration/rollback` | Stage `{revision, expectedManifestDigest}` for review |

Source revision conflicts return `409`; denied source access or Environment policy returns `403`; expired or unavailable drafts return `404`; draft capacity returns `429`. Publication never runs a test or changes central profile definitions.
