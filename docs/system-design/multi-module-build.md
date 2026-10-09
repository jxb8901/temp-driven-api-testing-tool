# Multi-module build layout

Issue #162 established ATT's Maven reactor. Issue #164 separates reusable execution semantics from adapters and adds a one-job Worker process.

```text
template-driven-api-testing-tool/
|-- pom.xml        parent / Maven reactor
|-- att-engine/   reusable execution/domain implementation and typed API
|-- att-server-api/ public Java 8 REST/SSE wire contract
|-- att-remote/   Java 8 REST/SSE client adapter
|-- att-cli/       argv parsing, terminal presentation, exit-code adapter
|-- att-worker/    structured one-job process adapter over att-engine
|-- att-server/    Java 17+ Jakarta Servlet control plane WAR
`-- att-dist/      binary/source distribution assembly
```

`att-engine` owns Run, Debug, Load, validation, snapshots, reports, resource helpers, Template/Flow execution, and the command-neutral `att.api.AttService` request/result contracts. Existing Java package names remain unchanged. The engine depends on neither adapter nor on `att-server`, and its execution entry points do not call `System.exit()`.

`att-cli` contains `att.FrameworkRunner` and `CliDiscovery`; it parses argv, builds typed Run, Debug, Load, Validate, and Snapshot requests, renders terminal output, and maps engine outcomes to the existing process exit codes. Run, Load, and Validate execution use the same `AttService` path as Worker requests. Optional live Load progress is supplied by the CLI as an observer; the reusable service remains quiet by default. `att.sh` and `att.bat` continue to start `att.FrameworkRunner`, preserving the installed CLI interface.

`att-server-api` contains only public REST/SSE DTOs, status values, and endpoint/version constants. It compiles for Java 8 and is shared by `att-server` and `att-remote`. `att-remote` is a Java 8 client for public Server endpoints; it owns HTTP/authentication, profiles, SSE reconnect, rendering, and artifact downloads. It depends on `att-server-api`, not on `att-engine` or `att-server`. `FrameworkRunner` dispatches explicit `remote` commands before local `CliOptions` parsing.

`att-worker` accepts one JSON request using protocol `att-worker/v1`, calls `AttService` directly, emits JSON Lines events, and exits after that single result. It does not start the CLI or parse terminal text. The protocol reserves `STATUS`, `LOG`, `PROGRESS`, `DIAGNOSTIC`, and `RESULT` event names. Stdout is exclusively the protocol stream; failures after request intake are returned as structured diagnostic and result events. External process termination remains the Worker cancellation boundary. See [worker-protocol.md](worker-protocol.md) for the internal request/event contract and launch form.

`att-server` is the single-node HTTP control plane. It reads a container-authenticated Servlet Principal, maps configured package IDs to validated canonical roots, persists job metadata in H2 under `SERVER_DATA_DIR`, and starts one `att-worker` process per job. Rejected submissions leave no durable job record; terminal job rows, event journals, and output artifacts are retained for a configurable period and then removed. Admission semaphores bound accepted work before persistence, and Load submissions are admitted before entering the general Worker pool so they cannot occupy threads while waiting for Load capacity. It exposes the `/api/v1` REST and SSE surfaces. Tomcat owns HTTP/TLS and its Realm validates Basic credentials through the Servlet API; all authenticated principals have the same v1 permissions. `server.authenticationRequired: false` explicitly permits anonymous use on isolated networks. ATT does not implement password storage, token validation, or ATT-specific RBAC. `att-server` compiles for Java 17, while the CLI, Engine, and Worker retain their Java 8 compiler targets. Deployment details are in [ATT Server deployment and API](../server-deployment.md).

`att-dist` is packaging-only. Its Maven assemblies produce `att-<version>-local.tar.gz` with the CLI, runtime dependencies, and existing package assets; `att-<version>-server.tar.gz` with the Server WAR and deployment guides; and `att-<version>-src.tar.gz` with source. The two binary archives are checked to remain below 25 MB. Keeping the WAR out of the local archive avoids shipping a second copy of the runtime libraries bundled inside it. Release packaging no longer recompiles application sources through a separate raw `javac` path.

## Java compatibility is module-specific

The current CLI remains Java 8-compatible. The parent defines the CLI-specific `att.cli.java=8` value, and `att-cli/pom.xml` explicitly applies it to the compiler. The parent does not define repository-wide `maven.compiler.source` or `maven.compiler.target` values.

The Server declares its Java 17 minimum independently. This does not raise the CLI, Engine, or Worker baseline.

Root runtime assets remain unchanged: `config/`, `templates/`, `tools/`, `testcase/`, `sql/`, `schemas/`, `docs/`, and `examples/`. PACKAGE_ROOT semantics and CLI discovery therefore remain unchanged.

## Developer and release flow

Run `mvn clean verify` from the repository root. The reactor tests `att-engine`, `att-server-api`, `att-remote`, `att-cli`, `att-worker`, and `att-server`, packages the Server WAR, and executes both binary assemblies plus the source assembly. `build.sh` regenerates documentation, runs the reactor gate, validates the source-tree launcher, smoke-tests the extracted local package with `version`, `help`, `debug`, `load`, `validate --package`, and `remote help`, checks that both binary archives are below 25 MB, and copies all three release archives to `dist/releases/`.

Source-tree launchers still execute from the repository root and include the CLI, Engine, Remote, and Server API module outputs on the classpath.
