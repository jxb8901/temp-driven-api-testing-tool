# Appendix A — schema and version matrix

Active schemas are supplied by the matching Engine JAR's embedded registry, built from `schemas/catalog.yaml`.

| Artifact | Active schema |
|---|---|
| Schedule creation | `att-schedule/v2` |
| Native RUN definition | `att-execution/v2` |
| Native Test Case Definition | `att-testcase/v1` |
| Global configuration | `att-config/v2.15` |
| Package Manifest | `att-package/v2` |
| Testdata descriptor | `att-testdata/v1.0` |
| DBHelper | `att-dbhelper/v2.6` |
| MQHelper | `att-mqhelper/v1.3` |
| HTTPHelper | `att-httphelper/v1.1` |
| SSHHelper | `att-sshhelper/v1.0` |
| Package Tool | `att-tool/v1` |
| Package Tool Group | `att-tool-group/v3.0` |
| Flow | `att-flow/v3.6` |
| Debug input | `att-debug/v1.2` |
| Load scenario | `att-load/v1.9` |
| LOAD observability binding | `att-load-observability/v1` |
| SUT Metrics Data Source | `att-sut-metrics-source/v1` |
| SUT Metric Source acquisition and output mapping | `att-sut-metrics-metric-source/v1` |
| SUT logical Metric Group | `att-sut-metrics-group/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v2` |
| SUT Metrics execution binding | `att-sut-metrics-snapshot/v1`, `att-sut-metrics-snapshot/v2` |
| Server-private SUT Metrics execution payload | `att-sut-metrics-execution-payload/v2` |
| SUT Metrics collection manifest | `att-sut-metrics-manifest/v1` |
| SUT Metrics manifest integrity history | `att-sut-metrics-manifest-integrity/v1` |
| SUT Metrics normalized observation | `att-sut-metrics-observation/v1`, `att-sut-metrics-observation/v2` |
| SUT Metrics series data | `att-sut-metrics-series/v1` |
| SUT Metrics threshold evaluation record | `att-sut-threshold-evaluation/v1` |
| Load preset | `att-load-template/v1` |
| Load bindings | `att-load-bindings/v1` |
| Load summary | Engine/CLI result contract; archived report uses `att-load-summary/v1.3` |
| LOAD report detail files | `att-load-detail-manifest/v1`, `att-load-detail-reference/v1`, `att-load-detail-series/v1`, `att-load-detail-series/v2`, `att-load-workload-detail/v1` |
| Run manifest | `att-run/v2.1` |
| Minimal CLI hierarchical validation report | `att-validation/v1` |
| Package validation JSON | `att-validation/v2.1` |
| CI summary | `att-ci-summary/v2.1` |
| JUnit XML | `att-junit/v2.1` |

The Engine reads schema bytes from its classpath registry. Compiled validators are held in a bounded in-memory cache keyed by registry digest and schema resource. The embedded registry is authoritative at runtime; `ATT_HOME/schemas/` and Package-local `schemas/` cannot override it. Validation revisions bind Engine and registry identity, Package files, shared configuration files, and selected Environment. Workers verify the registry identity supplied by the Server before handling requests.

The SUT Metrics Data Source schema defines the registered connection. The Metric Source schema describes one acquisition and its declared output mapping; it does not register another Data Source, DBHelper, or Package Tool. The Server-private execution payload binds executable fetch/call data to the Job's approved execution definition and is never returned by an API or report. Offline report metadata and externally supplied verdict records use standalone Server contracts under `schemas/api/`, outside the Engine execution registry. See [Offline SUT metrics report](../results-reports-evidence.md).
