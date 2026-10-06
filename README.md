# ATT 3.7.4 - Automated Testing Tool

ATT is an offline, template-driven API and integration test runner for SIT/UAT. Excel rows define Testcases; Stages select Templates; Templates execute ordered Actions; reusable Flows and configured Resources keep implementation logic out of test data.

```text
Testcase -> Stage -> Template -> Action -> Resource
                                  |         |-- Tool
                                  |         |-- DBHelper
                                  |         |-- MQHelper
                                  |         `-- SSHHelper (Tool routing + Resource Helper)
                                  `-- Flow
```

ATT has three peer execution modes over the same runtime model:

- **Run** — workbook-driven Testcase execution;
- **Debug** — standalone Template, Flow or Tool execution;
- **Load** — closed-VU or fixed-arrival-rate execution against a Template, Flow or Tool.

All modes use canonical `EXEC` / `META` Context roots and Action-local `output`. Actions publish native typed values through `output.result`; resource-specific evidence and transport parsing remain at their boundaries. Environment profiles select DB/MQ/SSHHelper bindings through stable logical IDs without changing Actions.

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

- Canonical documentation index: [`docs/README.md`](docs/README.md)
- English guided tutorial: [`docs/quick-start.md`](docs/quick-start.md)
- 中文快速入門: [`docs/quick-start.zh.md`](docs/quick-start.zh.md)
- Complete generated Reference Manual: [`docs/reference.html`](docs/reference.html)
- 中文 Reference Manual: [`docs/reference.zh.html`](docs/reference.zh.html)
- English Reference sources: [`docs/reference/`](docs/reference/)
- Chinese Reference sources: [`docs/reference.zh/`](docs/reference.zh/)
- Maintainer design: [`docs/system-design/`](docs/system-design/)
- Historical and superseded documentation: [`docs/history/`](docs/history/)
- Release chronology: [`CHANGELOG.md`](CHANGELOG.md)

The current Reference Manual is organized by product concepts rather than release history: authoring, Context, Run/Debug/Load, Tool/DBHelper/MQHelper/SSHHelper, environments, expressions, reliability, configuration, CLI, results, validation and operations.

DB operations run under ordinary `type: tool` Actions through `db.<helper>.<query|scalar|update>(...)` calls. Action-level `timeoutMs` overrides the DBHelper statement timeout. Read-only query/scalar calls may use bounded retry for `ASSERTION` and `TIMEOUT`; mutating update calls deliberately reject automatic retry because the mutation outcome can be uncertain after timeout or database/transport failure. See the [DBHelper Reference](docs/reference/resources/dbhelper.md).

## Repository modules

The repository is a minimal Maven reactor while the runtime/package root stays compatible with earlier ATT releases:

```text
pom.xml       parent / aggregator
att-cli/      current ATT application, all existing Java packages and tests
att-server/   empty placeholder reserved for future ATT Server work
att-dist/     binary/source release assembly
```

**ATT Server is not implemented yet.** The `att-server` module contains no server framework, API, worker, persistence, scheduler, authentication, or alternate entry point. Existing execution remains in `att-cli`, with `att.FrameworkRunner` as the application entry point. See [Multi-module build layout](docs/system-design/multi-module-build.md).

## Core package layout

```text
config/       global config, Tool groups, DBHelper/MQHelper/SSHHelper descriptors
testcase/     xlsx + yaml sidecar + generated xml snapshot
templates/    Template directories and reusable Flows
tools/        process-backed integration scripts/programs
schemas/      published ATT schemas
output/       run/debug/load evidence and reports
```

ATT 3.7.4 uses `att-config/v2.11`, `att-testdata/v1.0`, `att-tool-group/v2.9`, `att-dbhelper/v2.6`, `att-mqhelper/v1.2`, `att-httphelper/v1.1`, `att-template/v3.6`, `att-flow/v3.6`, and `att-load/v1.6`. Current schemas live under `schemas/`; previous and older definitions are historical references under `schemas/history/`, not current runtime contracts.

## Build and validation

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./build.sh
```

`mvn clean verify` runs the full reactor: it tests `att-cli`, builds the empty `att-server` placeholder, and assembles binary/source releases through `att-dist`. `build.sh` remains the compatibility/release entry point: it regenerates the Reference Manual, runs the reactor gate, and smoke-tests the assembled distribution. Release packaging consumes the Maven-built `att-cli` JAR rather than recompiling Java sources separately. Java 8+ remains the runtime baseline. JDBC drivers are supplied in `lib/`; IBM MQ remains an optional runtime integration.
