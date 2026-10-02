## Appendix A — Schema and Version Matrix

Active schemas (source of truth: `schemas/catalog.yaml`):

| Artifact | Active schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.6 |
| Flow | att-flow/v3.6 |
| Debug input | att-debug/v1.1 |
| Load scenario | att-load/v1.4 |
| Load summary | att-load-summary/v1.0 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |
| JUnit XML | att-junit/v2.1 |

For the changed resource/configuration schemas, older versions are historical definitions under schemas/history; they are not active execution contracts. Unsupported versions fail validation with migration guidance. The repository catalog at schemas/catalog.yaml is authoritative. Package validation checks the registered schema resources themselves; it does not enable runtime compatibility for archived versions.
