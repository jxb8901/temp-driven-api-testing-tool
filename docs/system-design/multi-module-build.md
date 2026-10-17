# Multi-module build layout

Issue #162 established ATT's Maven reactor. Issue #164 separates reusable execution semantics from adapters and adds a one-job Worker process.

```text
template-driven-api-testing-tool/
|-- pom.xml        parent / Maven reactor
|-- att-security/ shared Java 8 mandatory Secret Protection, without runtime dependencies
|-- att-engine/   reusable execution/domain implementation and typed API
|-- att-server-api/ public Java 8 REST/SSE wire contract
|-- att-remote/   Java 8 REST/SSE client adapter
|-- att-cli/       argv parsing, terminal presentation, exit-code adapter
|-- att-worker/    structured one-job process adapter over att-engine
|-- att-server/    Java 17+ Jakarta Servlet control plane WAR
`-- att-dist/      binary/source distribution assembly
```

`att-security` is the common mandatory secret policy used by Engine and Server API. It has no dependency on execution or Servlet APIs; both Local and Server application JARs include it.

`att-engine` owns Run, Debug, Load, validation, snapshots, reports, resource helpers, Template/Flow execution, and the command-neutral `att.api.AttService` request/result contracts. Existing Java package names remain unchanged. The engine depends on neither adapter nor on `att-server`, and its execution entry points do not call `System.exit()`.

`att-cli` contains `att.FrameworkRunner` and `CliDiscovery`; it parses argv, builds typed Run, Debug, Load, Validate, and Snapshot requests, renders terminal output, and maps engine outcomes to the existing process exit codes. Run, Load, and Validate execution use the same `AttService` path as Worker requests. Optional live Load progress is supplied by the CLI as an observer; the reusable service remains quiet by default. `att.sh` and `att.bat` continue to start `att.FrameworkRunner`, preserving the installed CLI interface.

`att-server-api` contains only public REST/SSE DTOs, status values, and endpoint/version constants. It compiles for Java 8 and is shared by `att-server` and `att-remote`. `att-remote` is a Java 8 client for public Server endpoints; it owns HTTP/authentication, profiles, SSE reconnect, rendering, and artifact downloads. It depends on `att-server-api`, not on `att-engine` or `att-server`. `FrameworkRunner` dispatches explicit `remote` commands before local `CliOptions` parsing.

`att-worker` accepts one JSON request using protocol `att-worker/v1`, calls `AttService` directly, emits JSON Lines events, and exits after that single result. It does not start the CLI or parse terminal text. The protocol reserves `STATUS`, `LOG`, `PROGRESS`, `DIAGNOSTIC`, and `RESULT` event names. Stdout is exclusively the protocol stream; failures after request intake are returned as structured diagnostic and result events. External process termination remains the Worker cancellation boundary. See [worker-protocol.md](worker-protocol.md) for the internal request/event contract and launch form.

`att-server` is the single-node HTTP control plane. It resolves a container-authenticated Servlet Principal, or the shared `anonymous` identity when `server.authenticationRequired: false`, maps configured package IDs to validated canonical roots, persists job metadata in H2 under `SERVER_DATA_DIR`, and starts one `att-worker` process per job. Rejected submissions leave no durable job record; terminal job rows, event journals, and output artifacts are retained for a configurable period and then removed. Admission semaphores bound accepted work before persistence, and Load submissions are admitted before entering the general Worker pool so they cannot occupy threads while waiting for Load capacity. It exposes the `/api/v1` REST and SSE surfaces. Tomcat owns HTTP/TLS and its Realm validates Basic credentials through the Servlet API; all authenticated principals have the same v1 permissions. Anonymous mode permits all callers to share one identity and is intended only for isolated networks. ATT does not implement password storage, token validation, or ATT-specific RBAC. `att-server` compiles for Java 17, while the CLI, Engine, and Worker retain their Java 8 compiler targets. Deployment details are in [ATT Server deployment and API](../server-deployment.md).

`att-dist` is packaging-only. Its Maven assemblies produce `att-<version>-local.tar.gz` with the CLI, runtime dependencies, and existing package assets; `att-<version>-server.tar.gz` with the Server WAR and deployment guides; and `att-<version>-src.tar.gz` with source. The local binary excludes the development-only `tools/ui-acceptance/` harness, its visual baselines, and the UI shell screenshot guide/baselines under `docs/system-design/`; the source archive retains those references but omits generated captures. The two binary archives are checked to remain below 25 MB. Keeping the WAR out of the local archive avoids shipping a second copy of the runtime libraries bundled inside it. Release packaging no longer recompiles application sources through a separate raw `javac` path.

## Java compatibility is module-specific

The current CLI remains Java 8-compatible. The parent defines the CLI-specific `att.cli.java=8` value, and `att-cli/pom.xml` explicitly applies it to the compiler. The parent does not define repository-wide `maven.compiler.source` or `maven.compiler.target` values.

The Server declares its Java 17 minimum independently. This does not raise the CLI, Engine, or Worker baseline.

Root runtime assets remain unchanged: `config/`, `templates/`, `tools/`, `testcase/`, `sql/`, `schemas/`, `docs/`, and `examples/`. PACKAGE_ROOT semantics and CLI discovery therefore remain unchanged.

## Developer and release flow

Run `mvn clean verify` from the repository root. The reactor tests `att-security`, `att-engine`, `att-server-api`, `att-remote`, `att-cli`, `att-worker`, and `att-server`, packages the Server WAR, and executes both binary assemblies plus the source assembly. `build.sh` regenerates documentation, runs the reactor gate, validates the source-tree launcher, smoke-tests the extracted local package with `version`, `help`, `debug`, `load`, `validate --package`, and `remote help`, checks that both binary archives are below 25 MB, and copies all three release archives to `dist/releases/`.

Source-tree launchers still execute from the repository root and include the CLI, Engine, Remote, and Server API module outputs on the classpath.


## CI build-time gates

The pull-request CI splits the Java 17 Server unit tests from the WAR/release
build to reduce the critical path without dropping tests. The independent
`Java 17 / Server unit and authentication tests` job runs the complete
Server Surefire suite, including authentication, scheduler, and runtime tests.
Its Surefire process uses two separate JVM forks with `reuseForks=false`:
test classes have isolated system properties, Tomcat state, and H2 data,
while tests within a class remain sequential.

The separate release job runs `ATT_CI_SKIP_COVERED_TESTS=true ./build.sh`
and skips only the suites already executed by the Java 8, frontend, and
Server-unit jobs. It **still runs** the packaged WAR Failsafe tests
(`ServerWarUiIT`), launcher checks, package validation, and archive smoke
tests. All jobs must pass before merge.

A normal local `./build.sh` still runs **all** tests. Both local and CI
builds print `[ATT BUILD TIME]` wall-clock seconds to stderr for preflight,
reference generation, Maven, launcher verification, package validation,
archive/smoke checks, publication, and total runtime (including failed
builds). Compare the resulting CI timings instead of assuming the previous
7m30s Maven baseline applies to new commits.

### Resource-light local release builds

Maven parallelism is controlled by `ATT_MAVEN_THREADS`, which defaults to the
previous `1C` behavior and accepts a positive integer or a core multiple:

```sh
MAVEN_OPTS="-Xmx1024m" ATT_MAVEN_THREADS=1 ATT_CI_SKIP_COVERED_TESTS=true ./build.sh
```

The lower thread count reduces concurrent Maven and Node processes at the cost
of a longer critical path. `MAVEN_OPTS` bounds the Maven JVM only; it is not a
whole-build memory cap because npm, Vitest, and Surefire forks use separate
processes. `ATT_CI_SKIP_COVERED_TESTS` skips selected test suites already
covered by the Java 8, frontend, and Server-unit CI jobs. It does not skip
compilation, TypeScript checking, the Vite production build, the bundle budget,
WAR/archive assembly, schema/package validation, launcher checks, or packaged
CLI smoke tests. Release confidence still requires a healthy full hosted CI run.

`build.sh` reports the failed phase and prints focused rerun commands. For an
`att-web` failure, run the module alone or invoke Vitest directly:

```sh
mvn -B -ntp -T 1 -pl att-web -am clean verify
cd att-web/frontend && npm ci && npm test -- --reporter=verbose
```

Maven's final `npm test failed` line is its reactor summary. The first Vitest
`FAIL`, timeout, or worker error earlier in the log is the actionable failure.
`att-web/frontend/src/test/setup.ts` shims the `Range` geometry methods that
jsdom omits; CodeMirror measures the Load YAML preview through them, and the
missing methods otherwise surface as intermittent Vitest timeouts rather than a
clear failure.

### Full browser matrix on a pull-request head

PR CI intentionally keeps the fast Chromium smoke suites. To run the complete
Scheduled Load matrix before merge, either add the `full-browser-suite` label to
the pull request or dispatch **CI** on the PR branch with `full_browser_suite`
enabled. The job summary states which scope ran, and a failure publishes the
browser, viewport, theme, changed-pixel ratio, and artifact links. A manual
dispatch with `update_visual_baselines=true` uploads replacement captures for
review instead of committing them. The Linux baseline must be refreshed from
that artifact on the Linux runner, not copied from macOS.
