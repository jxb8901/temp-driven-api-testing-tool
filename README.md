# ATT 3.5.1 - Automated Testing Tool

ATT is an offline, template-driven API and integration test runner for SIT/UAT. Excel rows define Testcases; Stages select Templates; Templates execute ordered Actions; reusable Flows and configured Resources keep implementation logic out of test data.

```text
Testcase -> Stage -> Template -> Action -> Resource
                                  |         |-- Tool
                                  |         |-- DBHelper
                                  |         `-- MQHelper
                                  `-- Flow
```

ATT has three peer execution modes over the same runtime model:

- **Run** — workbook-driven Testcase execution;
- **Debug** — standalone Template, Flow or Tool execution;
- **Load** — closed-VU or fixed-arrival-rate execution against a Template, Flow or Tool.

All modes use canonical `EXEC` / `META` Context roots and Action-local `output`. Tool, DB and MQ operations converge on the same Action result/evidence model. Environment profiles select DB/MQ bindings through stable logical IDs without changing Actions.

## Start here

New to ATT? Follow the [English Quick Start](docs/quick-start.md) or [中文快速入門](docs/quick-start.zh.md). Both guides use the same checked-in offline example and walk through snapshot, validation, execution, logs, assertions, and one local Tool call.

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

Useful peer-mode commands after the basic Run workflow is familiar:

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
```

Windows uses the same commands through `att.bat`.

## Documentation

- English guided tutorial: [`docs/quick-start.md`](docs/quick-start.md)
- 中文快速入門: [`docs/quick-start.zh.md`](docs/quick-start.zh.md)
- Complete generated Reference Manual: [`docs/generated/reference.html`](docs/generated/reference.html)
- 中文 Reference Manual: [`docs/generated/reference.zh.html`](docs/generated/reference.zh.html)
- English Reference sources: [`docs/reference/`](docs/reference/)
- Chinese Reference sources: [`docs/reference.zh/`](docs/reference.zh/)
- Maintainer design: [`docs/system-design/`](docs/system-design/)
- Documentation architecture: [`docs/documentation-architecture.md`](docs/documentation-architecture.md)
- Release chronology: [`CHANGELOG.md`](CHANGELOG.md)

The current Reference Manual is organized by product concepts rather than release history: authoring, Context, Run/Debug/Load, Tool/DB/MQ, environments, expressions, reliability, configuration, CLI, results, validation and operations.

## Core package layout

```text
config/       global config, Tool groups, DBHelper/MQHelper descriptors
testcase/     xlsx + yaml sidecar + generated xml snapshot
templates/    Template directories and reusable Flows
tools/        process-backed integration scripts/programs
schemas/      published ATT schemas
output/       run/debug/load evidence and reports
```

The current global configuration schema is `att-config/v2.6`. Templates/Flows, DBHelper, MQHelper, debug and load scenarios have their own versioned schemas under `schemas/`.

## Build and validation

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./build.sh
```

`build.sh` runs the release gate and regenerates the modular Reference Manual before packaging. Java 8+ remains the runtime baseline. JDBC drivers are supplied in `lib/`; IBM MQ remains an optional runtime integration.
