# Native Quick Start examples (Default Package)

Offline-safe `att-testcase/v1` Definitions for the Native Case happy path. They bind Flow `quick.start.v1` and do not require DB, MQ, HTTP, or credentials.

| Definition ID | Path | Resolved Cases | Data Source |
|---|---|---|---|
| `quick.start.single` | `single/testcase.yaml` | `default` | Typed `inputs` |
| `quick.start.inline` | `inline-multi/testcase.yaml` | `basic`, `with-tool` | Inline records |
| `quick.start.excel` | `excel/testcase.yaml` | `QS001`, `QS002` | `testdata/quick-start-native.xlsx` |

Legacy Workbook Quick Start remains at `../quick_start.xlsx` (+ sidecar/XML). See [migration matrix](../../../../docs/maintenance/native-case-default-package-migration-s12.md).

Implicit Suite selection (`native-suite`) and source-independent native XLSX reporting land with S06/S07 and are not claimed here.
