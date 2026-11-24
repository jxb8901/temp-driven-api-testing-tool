# CLI Reference

The current CLI runs Native Cases, validates Package resources, diagnoses Flow or Tool targets, and runs Flow or Tool Load workloads. For earlier Package formats, see the [Legacy CLI guide](../legacy/cli.md).

## Build a source checkout

The source checkout launchers run prebuilt classes. Build the Java CLI, Engine, Worker, and Server API modules when classes are missing:

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

Use `./att.sh` on macOS/Linux or `att.bat` on Windows. Packaged releases contain prebuilt application JARs and require Java 8 or later; the Server WAR requires Java 17 or later.

## Start with a command

| Task | Command |
|---|---|
| Show help | `./att.sh` or `./att.sh help` |
| Print the version | `./att.sh version` |
| Validate the Package | `./att.sh validate --package` |
| Validate a selected dependency closure | `./att.sh validate --selected <selection>` |
| Run one Native Definition | `./att.sh --local -p default run native-case quick.start.single --resolved-case-id default` |
| Run a Native Suite | `./att.sh --local -p default run native-suite --all` |
| Diagnose a Flow | `./att.sh debug flow <id>` |
| Diagnose a Tool | `./att.sh debug tool <id>` |
| Run a Flow or Tool workload | `./att.sh load --debug flow <id> --load-template builtin:single-vu --preview` |
| Run a declared Load scenario | `./att.sh load examples/load/closed-smoke.yaml` |
| Regenerate reports for a completed Run | `./att.sh report --run-id <id>` |
| Generate local documentation | `./att.sh docs` |
| Archive the latest completed Run | `./att.sh build` |
| Remove documented generated output | `./att.sh clean` |

Native selector syntax and source ownership are defined in [Test Authoring](test-authoring.md). A native Definition binds a Flow and a Data Source; its Cases are selected by qualified ID, tag, or Suite criteria.

## RUN a Case or Suite

~~~sh
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
./att.sh --local -p default run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool
./att.sh --local -p default run native-suite --all --tag smoke --exclude-tag slow
~~~

Use `--dry-run` to validate and resolve the selection without running Actions. Use `--rerun-failed` with a Native Suite to select the failed executed Cases from its latest eligible completed Run. Use `--fail-fast` to stop scheduling after a failure.

## Validate a Package

~~~sh
./att.sh validate --package
./att.sh validate --selected testcase/quick-start/single/testcase.yaml
~~~

Validation checks the selected Definition, Flow, Tools, Testdata, Runtime Configuration, schemas, and resource references. It does not execute Actions or start a Load scheduler.

## Debug a Flow or Tool

~~~sh
./att.sh debug flow common.payment.v1 --input debug/payment.yaml --env SIT
./att.sh debug tool sample.getAcDate --set arg.date=2026-11-23
~~~

`--input` selects a Debug input file. Repeat `--set input.path=value`, `--set vars.path=value`, or `--set arg.name=value` for typed values. Flow targets accept input and vars; Tool targets accept input, vars, and declared arguments. See [Debug](execution-modes/debug.md) for discovery, protected paths, output, and result behavior.

## Run a Load workload

A direct Load workload targets a Flow or Tool. A declared scenario can contain validated Flow and Tool workloads.

~~~sh
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load examples/load/closed-smoke.yaml --users 1 --duration 100ms
~~~

List and preview operations do not start traffic. See [Load mode](execution-modes/load.md) for pacing, Testdata, evidence, thresholds, and scenario composition.

## Run a Server command

Local commands execute on the current machine. Add `remote` to submit supported operations to ATT Server:

~~~sh
./att.sh remote --server sit packages
./att.sh remote --server sit run payments --detach
./att.sh remote --server sit jobs
./att.sh remote --server sit watch J0123456789ABCDEF
~~~

Remote Package names, Definition selections, configuration, and scenario paths are logical values resolved by Server. Client absolute paths and local output directories are not sent. See [ATT Remote client](../remote-client.md) for connection profiles, authentication, streaming, and troubleshooting.

## Common options

Options apply only to commands that accept them.

| Option | Purpose |
|---|---|
| `--config <file>`, `--env <name>` | Select shared Runtime Configuration and Environment |
| `--run-id <id>`, `--debug-id <id>` | Set the Run or Debug output identity |
| `--output-dir <dir>` | Select an output root for supported local commands |
| `--format human` or `--format json` | Select terminal output |
| `--quiet`, `--verbose` | Control progress detail |
| `--profile` | Write supported phase timings and counters |
| `--queue`, `--allow-parallel-runs` | Set output-root process concurrency behavior |
| `--substitute-package-root`, `--substitute-external-basename` | Hide absolute paths in displayed diagnostics |
| `--help` | Print command-specific help |

`--resolved-case-id` may be repeated for a Native Definition. Suite selection uses `--all`, `--tag`, and `--exclude-tag`. Options are validated per command; unsupported combinations return an error.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more Case or assertion failures |
| 2 | Invalid command, configuration, or validation |
| 3 | Runtime or infrastructure failure |

Windows uses `att.bat` with the same supported Native Case, Flow, Tool, and Load arguments.
