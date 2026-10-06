# Multi-module build layout

Issue #162 changes ATT's repository/build structure without changing its execution architecture or CLI contract.

```text
template-driven-api-testing-tool/
|-- pom.xml        parent / Maven reactor
|-- att-cli/       current ATT application, tests, and att.FrameworkRunner
|-- att-server/    empty reserved module for future ATT Server work
`-- att-dist/      binary/source distribution assembly
```

`att-cli` owns all existing Run, Debug, Load, validation, reporting, resource-helper, and Template/Flow behavior. Java packages remain unchanged and `att.FrameworkRunner` remains the application entry point used by `att.sh` and `att.bat`.

`att-server` is intentionally empty. It reserves a module boundary only; ATT Server, REST endpoints, workers, persistence, scheduling, authentication, job protocols, and `ServerMain` are not implemented or advertised.

`att-dist` is packaging-only. Its Maven assembly consumes the JAR already built by `att-cli`, adds runtime dependencies plus the existing package assets, and produces both `att-<version>.tar.gz` and `att-<version>-src.tar.gz`. Release packaging no longer recompiles application sources through a separate raw `javac` path.

Root runtime assets remain unchanged: `config/`, `templates/`, `tools/`, `testcase/`, `sql/`, `schemas/`, `docs/`, and `examples/`. PACKAGE_ROOT semantics and CLI discovery therefore remain unchanged.

## Developer and release flow

Run `mvn clean verify` from the repository root. The reactor tests `att-cli`, packages the empty `att-server` JAR, and executes `att-dist` assembly. `build.sh` remains the supported release entry point; it regenerates documentation, runs the reactor release gate, validates the source-tree launcher, smoke-tests the assembled binary with `version`, `help`, `debug`, `load`, and `validate --package`, and copies binary/source archives to `dist/releases/`.

Source-tree launchers still execute from the repository root and now use `att-cli/target/classes`.
