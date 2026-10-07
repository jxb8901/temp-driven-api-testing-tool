# Multi-module build layout

Issue #162 established ATT's Maven reactor. Issue #164 separates reusable execution semantics from adapters and adds a one-job Worker process.

```text
template-driven-api-testing-tool/
|-- pom.xml        parent / Maven reactor
|-- att-engine/   reusable execution/domain implementation and typed API
|-- att-cli/       argv parsing, terminal presentation, exit-code adapter
|-- att-worker/    structured one-job process adapter over att-engine
|-- att-server/    empty reserved module for future ATT Server work
`-- att-dist/      binary/source distribution assembly
```

`att-engine` owns Run, Debug, Load, validation, snapshots, reports, resource helpers, Template/Flow execution, and the command-neutral `att.api.AttService` request/result contracts. Existing Java package names remain unchanged. The engine depends on neither adapter nor on `att-server`, and its execution entry points do not call `System.exit()`.

`att-cli` contains `att.FrameworkRunner` and `CliDiscovery`; it parses argv, builds typed Run, Debug, Load, Validate, and Snapshot requests, renders terminal output, and maps engine outcomes to the existing process exit codes. Run, Load, and Validate execution use the same `AttService` path as Worker requests. Optional live Load progress is supplied by the CLI as an observer; the reusable service remains quiet by default. `att.sh` and `att.bat` continue to start `att.FrameworkRunner`, preserving the installed CLI interface.

`att-worker` accepts one JSON request using protocol `att-worker/v1`, calls `AttService` directly, emits JSON Lines events, and exits after that single result. It does not start the CLI or parse terminal text. The protocol reserves `STATUS`, `LOG`, `PROGRESS`, `DIAGNOSTIC`, and `RESULT` event names. Stdout is exclusively the protocol stream; failures after request intake are returned as structured diagnostic and result events. External process termination remains the Worker cancellation boundary. See [worker-protocol.md](worker-protocol.md) for the internal request/event contract and launch form.

`att-server` is intentionally empty. It reserves a module boundary only; ATT Server, REST endpoints, persistence, scheduling, authentication, and job management are not implemented or advertised.

`att-dist` is packaging-only. Its Maven assembly consumes the JAR already built by `att-cli`, adds runtime dependencies plus the existing package assets, and produces both `att-<version>.tar.gz` and `att-<version>-src.tar.gz`. Release packaging no longer recompiles application sources through a separate raw `javac` path.

## Java compatibility is module-specific

The current CLI remains Java 8-compatible. The parent defines the CLI-specific `att.cli.java=8` value, and `att-cli/pom.xml` explicitly applies it to the compiler. The parent does not define repository-wide `maven.compiler.source` or `maven.compiler.target` values.

The empty `att-server` module intentionally declares no compiler baseline in #162. A future Server issue can choose Java 17, Java 21, or another justified runtime independently without requiring the current CLI to move off Java 8. #162 reserves that option; it does not choose or implement the Server Java version.

Root runtime assets remain unchanged: `config/`, `templates/`, `tools/`, `testcase/`, `sql/`, `schemas/`, `docs/`, and `examples/`. PACKAGE_ROOT semantics and CLI discovery therefore remain unchanged.

## Developer and release flow

Run `mvn clean verify` from the repository root. The reactor tests `att-engine`, `att-cli`, and `att-worker`, packages the empty `att-server` JAR, and executes `att-dist` assembly. The binary contains the Worker JAR alongside its engine/runtime dependencies; no user-facing Worker launcher is added. `build.sh` remains the supported release entry point; it regenerates documentation, runs the reactor release gate, validates the source-tree launcher, smoke-tests the assembled binary with `version`, `help`, `debug`, `load`, and `validate --package`, and copies binary/source archives to `dist/releases/`.

Source-tree launchers still execute from the repository root and use both `att-cli/target/classes` and `att-engine/target/classes`.
