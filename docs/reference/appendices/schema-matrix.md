# Appendix A — schema and version matrix

Active schemas (the matching ATT Engine JAR's embedded registry, built from `schemas/catalog.yaml`):

| Artifact | Active schema |
|---|---|
| Schedule creation | att-schedule/v2 |
| RUN definition | att-execution/v1 |
| Global configuration | att-config/v2.13 |
| Package Manifest | att-package/v1 |
| Testdata descriptor | att-testdata/v1.0 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.3 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.2 |
| Load scenario | att-load/v1.9 |
| Load Template | `att-load-template/v1` | `schemas/att-load-template-v1.schema.json` |
| Load bindings | `att-load-bindings/v1` | `schemas/att-load-bindings-v1.schema.json` |
| Load summary | att-load-summary/v1.2 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

For the changed resource/configuration schemas, older versions are historical definitions under `schemas/history/`; they are not active execution contracts unless the embedded Engine registry registers and supports them. Unsupported versions fail validation with migration guidance. The repository catalog at `schemas/catalog.yaml` is build input; at runtime, the registry embedded in the matching ATT Engine JAR is authoritative. The Engine parses its Classpath catalog once per JVM and reads schema bytes directly from the JAR. Compiled JSON validators are held in a bounded, in-memory cache keyed by registry digest and schema resource. A verified, content-addressed cache in a private per-user temporary directory remains only as a transitional bridge for Path-based compatibility APIs. It retains the active registry and the immediately previous version for rollback; older entries are deleted only when an exclusive OS lock confirms no Worker is using them, and interrupted cleanup is retried at the next initialization. An incomplete or tampered cache is rebuilt only when no process holds its use lease; otherwise validation fails closed and retries after the active Worker exits. Validation revisions bind Engine and registry identity, Package files, shared config files and selected Environment; Workers verify the registry identity supplied by the Server before handling requests. `ATT_HOME/schemas/` and package-local `schemas/` are not runtime sources and cannot override that registry. Package validation checks the Engine's registered schema resources; it does not enable compatibility for unregistered archived versions.

<!-- att-docs:historical -->
Load v1.6 and MQHelper v1.2 are compatible historical schemas supported by their loaders; their existing scalar routing and legacy evidence policies remain unchanged. New routing/quota fields require the active schema.
<!-- /att-docs:historical -->
