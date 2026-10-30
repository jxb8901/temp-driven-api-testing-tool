# CLI reference

<!-- att-doc-status: current release=4.2.7 -->


## Choose a command

| Command | Purpose | External tools? |
|---|---|---:|
| `help` | Show syntax and options; default with no command | No |
| `version` | Print ATT version | No |
| `validate` | Validate package or selected dependency closure | No |
| `snapshot` | Generate same-basename canonical testcase XML | No |
| `run` | Validate and execute selected cases | Yes, except dry-run |
| `debug` | Execute one Template, Flow, or Tool with a debug sidecar | Yes |
| `load` | Execute a declared scenario or promote a Debug sidecar into a Quick Load | Yes |
| `docs` | Generate searchable package documentation | No |
| `report` | Regenerate reports for a completed run | No |
| `build` | Archive the latest completed run | No |
| `clean` | Remove documented ATT-generated output | No |
| `remote` | Submit and manage jobs through ATT Server's REST/SSE API | ATT Server |

The commands in this table are the ATT 4.2.7 CLI. The separate vNext proposal is not part of this release.

## Availability in ATT 4.2.7

This Reference describes the CLI shipped with ATT 4.2.7. The vNext grammar is a separate [Planned proposal](../interfaces/cli/vnext-proposed.md); it is not a current command interface. Legacy commands remain supported in this release.

## Build a source checkout

The source checkout launchers run prebuilt classes and never compile code. Build the Java 8-compatible CLI, Engine, Remote, and Server API modules explicitly with a JDK and Maven:

```sh
mvn -DskipTests -pl att-cli -am compile
```

Then run `./att.sh` on macOS/Linux or `att.bat` on Windows. If required classes are missing, the launcher exits with the build command and does not try to run it. Packaged releases contain prebuilt application JARs and require a Java 8 or later runtime, not Maven. Place optional JDBC or IBM MQ driver JARs in `lib/` when those integrations are used. The Server WAR requires Java 17 or later.

## Find syntax, options, and examples by task

### Check help and version

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh help` | Show help |
| `./att.sh version` | Print version |

### Generate workbook snapshots

| Syntax | Notes |
|---|---|
| `./att.sh snapshot` | Generate snapshots recursively below `testcase.root`; equivalent to `--all` when no selector is supplied |
| `./att.sh snapshot --suite <xlsx>` | Generate one same-basename XML snapshot |
| `./att.sh snapshot --all` | Generate snapshots recursively below `testcase.root` |
| `./att.sh snapshot --suite-dir <dir>` | Generate snapshots recursively below a directory |

### Validate a package

| Syntax | Notes |
|---|---|
| `./att.sh validate --package` | Validate complete package; default scope |
| `./att.sh validate --selected <selection>` | Validate selected dependency closure |
| `./att.sh validate --package --format json` | Emit one validation JSON document to stdout |

On Windows, `att.bat snapshot`, `att.bat validate`, and `att.bat docs` do not invoke configured Testcase Tools. Validation checks `.sh` paths and file existence but skips POSIX launch and executable compatibility; it warns with the affected Tools. A validation PASS confirms configuration for those scripts, not that they can run on Windows. Provide and test Windows-native equivalents before `run`. See [Windows launcher and Tool compatibility](appendices/limits-defaults.md).

### Run Testcases

| Syntax | Notes |
|---|---|
| `./att.sh run --all` | Run all discovered cases |
| `./att.sh run --suite <xlsx>` | Run one workbook; repeatable |
| `./att.sh run --suite-dir <dir>` | Discover workbooks below a directory |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | Include one full Case ID |
| `./att.sh run <selection> --tag <tag>` | Include a tag |
| `./att.sh run <selection> --exclude-tag <tag>` | Exclude a tag |
| `./att.sh run <selection> --dry-run` | Validate/plan without tool execution |
| `./att.sh run <selection> --update-snapshot` | Explicitly refresh changed complete-workbook snapshots before validation |
| `./att.sh run <selection> --fail-fast` | Stop scheduling after first FAIL/ERROR |
| `./att.sh run <selection> --rerun-failed` | Select prior FAIL/ERROR cases |
| `./att.sh run <selection> --run-id <id>` | Set final run directory name |
| `./att.sh run <selection> --output-dir <dir>` | Override output root |
| `./att.sh run <selection> --ci-output junit,json` | Write CI XML/JSON plus JUnit HTML |
| `./att.sh run <selection> --profile` | Write per-run `performance.json` timings and counters |
| `./att.sh run <selection> --queue` | Wait for another ATT process using the same output root |
| `./att.sh run <selection> --allow-parallel-runs` | Allow concurrent ATT processes; does not parallelize Cases in this run |
| `./att.sh run <selection> --format json` | Emit machine-readable summary |
| `./att.sh run <selection> --quiet` | Suppress detailed live progress; keep the final summary and errors |
| `./att.sh run <selection> --verbose` | Accepted for compatibility; detailed live progress is already the default |

### Debug a component

| Syntax | Notes |
|---|---|
| `./att.sh debug` | Discover runnable Tools, Templates, and Flows; show only existing default sidecars |
| `./att.sh debug template <id>` | Execute one Template; auto-discover `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | Execute one canonical Flow; auto-discover `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | Execute one Tool; auto-discover `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | Override the target's auto-discovered debug input |
| `./att.sh debug <type> <id> --set input.path=<yaml-value>` | Override a typed `EXEC.INPUT` value; repeatable |
| `./att.sh debug tool <id> --set arg.name=<yaml-value>` | Override one Tool argument; repeatable |
| `./att.sh debug <type> <id> --set vars.path=<yaml-value>` | Override Template/Flow bootstrap `EXEC.VARS` before expression evaluation |
| `./att.sh debug <type> <id> --debug-id <id>` | Set the exact standalone Debug directory identity |
| `./att.sh debug <type> <id> --unsafe-failure-details` | Opt into expanded collector failure diagnostics for this standalone local Debug run; prints a warning and keeps secret redaction |
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --profile` | Write CLI startup and Debug phase timings to `performance.json` |
| `./att.sh debug <type> <id> --format json` | Emit a compact machine-readable console summary; full evidence remains in `result.yaml` |
| `./att.sh debug <type> <id> --quiet` | Suppress detailed live progress; keep the final summary and errors |

### Run a Load scenario

| Syntax | Notes |
|---|---|
| `./att.sh load` | Discover valid `att-load/*` scenarios under `load/`; report invalid declared scenarios |
| `./att.sh load <scenario.yaml> --quiet` | Suppress periodic live progress; keep the final summary and errors |
| `./att.sh load <scenario.yaml> --verbose` | Accepted for compatibility; bounded live progress is already the default |
| `./att.sh load --debug <type> <id>` | Promote a Debug sidecar into a normal single-workload Load run using `load/load.yaml` policy |
| `./att.sh load <scenario.yaml> --set input.path=<yaml-value>` | Override one-workload `EXEC.INPUT`; repeatable, not valid for multi-workload scenarios |
| `./att.sh load <scenario.yaml> --set arg.name=<yaml-value>` | Override a Tool argument in a one-workload Tool scenario |
| `./att.sh load <scenario.yaml> --set vars.path=<yaml-value>` | Override one-workload Template/Flow bootstrap vars |

### Manage reports and package output

| Syntax | Notes |
|---|---|
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

## Use ATT Server from the CLI

Remote commands use the public ATT Server `/api/v1` contract. Existing commands such as `run`, `debug`, `load`, and `validate` remain local unless you explicitly add `remote`.

```sh
./att.sh remote --server sit packages
./att.sh remote --server sit --no-auth packages
./att.sh remote --server https://att-sit.example.com/att packages
ATT_SERVER=sit ./att.sh remote packages
./att.sh remote --server sit run payments --suite testcase/smoke.xlsx --env SIT --tag smoke
./att.sh remote --server sit --no-auth run payments --all --principal Bob
./att.sh remote --server sit jobs --principal 'Bob(anonym)'
./att.sh remote --server ci run payments --all --detach
./att.sh remote --server sit watch J0123456789ABCDEF
./att.sh remote --server sit load payments --scenario load/payment.yaml --users 2 --duration 30s
./att.sh remote --server sit artifacts J0123456789ABCDEF --download ./results
```

The `--server` value selects an entry from `~/.att/servers.yaml`; set `ATT_REMOTE_CONFIG` to select another profile file. `ATT_SERVER_CONFIG` is reserved for Server deployment YAML. `ATT_SERVER` overrides the default profile URL; an explicit `--server` profile takes precedence. Remote package, suite, config, scenario, and Debug input values are logical names relative to the Server package. Absolute client paths and `--output-dir` are not sent.

The profile can store a URL, Basic-auth username, and the name of a CI secret environment variable. It cannot store a password. If `passwordEnv` is absent, the CLI prompts without echo when a terminal is available. Basic authentication requires HTTPS; normal TLS certificate and hostname checks stay enabled.

Attached Run, Debug, Load, and Validate commands submit once, follow SSE, reconnect with `Last-Event-ID`, then retrieve the canonical result. `--detach` prints the accepted job ID and exits. Use `jobs`, `job`, `watch`, `result`, `artifacts`, `artifact`, or `cancel` to work with retained jobs. Artifacts download under the selected local directory and never overwrite an existing file.

Remote exit codes are `0` for PASS/success, `1` for ATT FAIL, `2` for INVALID, `3` for ATT ERROR/CANCELLED, and `4` when the client cannot establish a trustworthy result because of connection, authentication, or protocol failure. See [ATT Remote client](../remote-client.md) for profiles, command options, reconnect behavior, and troubleshooting.

Remote-only options include `--server <profile|url>`, `--no-auth`, `--principal <name>`, `--detach`, `--download <directory>`, and `--scenario <package-relative-path>`. `--no-auth` bypasses profile credentials only when the Server explicitly permits anonymous access. In anonymous mode, `--principal` supplies a self-declared label that the Server stores in the existing `principal` field as `<name>(anonym)`; it does not affect access or authorization. Authenticated mode ignores it. `jobs --principal <text>` searches that field by case-insensitive substring, so searching for `alice` also matches `alice(anonym)`. `--format human|json` and the documented ATT selection, environment, Debug, and Load options are accepted where they map to the remote job contract. See the linked guide for the remote command list.


Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

No-target `debug` and `load` are read-only discovery commands. Debug validates target contracts without invoking Tools or creating output. Load scans only declared `att-load/*` YAML, validates every target before listing the scenario, reports invalid declared descriptors, and ignores unrelated YAML. Both accept `--config`, `--env`, `--format`, `--quiet`, and `--verbose` in discovery mode.

## Path display

Local CLI output shows absolute paths by default. Add `--substitute-package-root` to display package-contained paths as `$ATT_PACKAGE_ROOT` or `$ATT_PACKAGE_ROOT/<relative-path>`, and `--substitute-external-basename` to display other absolute paths as `$EXTERNAL/<basename>`. These options are independent and apply to human-readable diagnostics, structured results, and displayed report paths for `run`, `validate`, `snapshot`, `debug`, and `load`. They change presentation only; filesystem access and saved package data are unchanged. These CLI switches apply to the local CLI process only; Server Workers force both substitutions for API and Studio output regardless of CLI flags, to avoid exposing host paths. Add the flags to restore the previous local CLI presentation when upgrading to 4.2.4.

```sh
./att.sh validate --package --substitute-package-root
./att.sh debug template PAYMENT_INVOKE --substitute-external-basename
```

## Typed overrides and quick Load

`--set` is repeatable and accepts exactly one namespace: `input`, `arg`, or `vars`. Values use safe YAML parsing (for example `42`, `true`, `null`, `[a, b]`, or `{id: 7}`), and nested paths may use map keys and numeric list indexes such as `input.customer.ids[0]=42`. Duplicate assignments are applied in order, so the last value wins. ATT expressions are not evaluated while parsing an override; quote expression-looking values when a shell could expand them. `arg.*` is Tool-only; `vars.*` is Template/Flow-only. Unqualified overrides are rejected for multi-workload Load scenarios.

`load/load.yaml` is an optional, policy-only `att-load/v1.9` file. It may contain `load`, `execution`, `thresholds`, `evidence`, and `seed`, but no target or business inputs. `load --debug` promotes sidecar `inputs` to `EXEC.INPUT`, Template/Flow `vars` to bootstrap `EXEC.VARS`, or Tool `arguments` to the Tool call, then runs through the regular Load validator, scheduler, and evidence pipeline. It rejects sidecars with Debug-local `testdata` imports; declare those imports on a Load scenario. Explicit CLI pacing fields override the policy. Without a policy, provide a complete policy on the command line; for example:

```yaml
schemaVersion: att-load/v1.9
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
```

```sh
./att.sh debug
./att.sh load
./att.sh load --debug template PAYMENT_INVOKE
./att.sh load --debug tool fpp.invokeApi --users 1 --duration 10s --set arg.requestId=42
./att.sh load --debug flow common.payment --users 4 --duration 5s --set input.customer.ids[0]=42
```

`run`, `debug`, and `load` default to interactive verbose behavior. Lifecycle, Case, Stage, Action, resource-attempt, retry, assertion, and error records are written as they occur and flushed promptly. The live Case-log mirror uses the same redacted append path as `case.log`; `case.log`, `case.yaml`/`result.yaml`, reports, and evidence remain the persistent source of truth. Concurrent Case-log chunks carry a Case ID prefix. `--quiet` suppresses detailed live progress but retains a final summary and errors. With `--format json`, machine-readable output remains on stdout and live progress is sent to stderr. Load progress prints bounded periodic counters/rates and throttled errors, never one console block per successful iteration.

## Debug inputs and outputs

This page defines target, `--input`, `--set` and `--env` syntax in the option matrix. [Debug](execution-modes/debug.md) owns input discovery, bootstrap variables, protected roots and output lifecycle.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command/run succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more FAIL results and no ERROR/INVALID |
| 2 | CLI/configuration/validation/INVALID failure |
| 3 | One or more ERROR results or unrecoverable runtime failure |

## Complete option matrix

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.13` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` and `load`, and writes startup plus execution phase timings for a selected `debug` target.

Load uses the scenario as the base and explicit workload options override the corresponding fields before the effective scenario is validated again:

```sh
./att.sh load ../examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load ../examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --ramp-up 1s --warmup 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

Repeatable `--set <input|arg|vars>.<path>=<yaml-value>` applies safe-YAML typed overrides before expression evaluation. The same option works for `debug`, single-workload Load scenarios, and `load --debug template|flow|tool <id>`. Quick Load uses `load/load.yaml` when present; otherwise provide a complete policy on the command line. For example:

```sh
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
```

The complete workload override set is `--users`, `--arrival-rate`, `--ramp-up`, `--warmup`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, and `--overload-policy`. `--think-time` is closed-VU only. Common selection/output options remain command-specific: `--suite`, `--suite-dir`, `--case`/`--case-id`, `--tag`, `--exclude-tag`, `--all`, `--run-id` (outer Run/Load identity), `--debug-id` (standalone Debug identity), `--output-dir`, `--format`, `--quiet`, `--verbose`, `--ci-output`, `--dry-run`, `--fail-fast`, `--rerun-failed`, `--update-snapshot`, `--package`, `--selected`, `--input`, `--set`, `--queue`, `--parallel`, `--allow-parallel-runs`, `--profile`, `--config`, `--env`, and `--help` are accepted only where the command contract permits them. Both identity options are literal overrides.

### Diagnostic trust boundaries and secret protection

`att-security` supplies the mandatory Secret Protection policy shared by Engine, Worker,
Server and the inspectors. Password/passphrase, secret/token/credential, authorization,
cookie, API/access/private key and identity-file fields are reserved; a sensitive field
protects its whole value, including an unknown nested object. Bearer/Basic credentials,
URL credentials, PEM private keys, `!secret` values, secret CSV columns and XML/JSON/YAML
secret values are protected in text. Resource adapters also register concrete secret
spellings with the Case Log; those spellings remain protected wherever they occur.
There is no client flag or privileged `includeAllFields` bypass for these classes.
Unknown objects and cyclic/deep sensitive structures fail closed.

| Boundary | Path policy | Secret policy |
| --- | --- | --- |
| CLI console | Both aliases off by default; each alias flag works independently | Mandatory protection always applies |
| CLI `case.log` | Typed local path fields and diagnostic absolute path tokens follow selected aliases; authored commands, relative expressions, URLs and remote paths retain their text | Mandatory protection and registered resource secrets |
| Server Worker `case.log` | Both aliases off; trusted local diagnostic evidence retains original paths | Same mandatory protection |
| Run/Debug/Load `case.yaml` and retained failure evidence | Local typed presentation follows the process policy; authored relative expressions remain intact | Structured secret fields and registered Case Log secrets are protected |
| Worker JSON Lines | Internal trusted process channel; original locations remain available to the Server | Shared mandatory protection on every event/result |
| Server API, SSE and Job Result | Server applies deterministic `package:` / `artifact:` locators and external basename labels; clients cannot disable this policy | Shared mandatory protection on every public projection |
| Source/Configuration inspectors | Package-relative locations; external host paths are hidden at the Server boundary | Mandatory protection plus independently configured optional field/PII redaction |
| Public artifact download | UTF-8 text projection, up to 16 MiB; original on-disk file remains unchanged. Binary, invalid UTF-8 and oversized artifacts require trusted filesystem access | Mandatory secret projection before download |

Diagnostic excerpts preserve `&{./Pacs008.xml}`, `&{../request.xml}`, `${...}`, `#{...}`,
XML closing tags, Unicode, URLs and SSH remote paths. Structured `file`, `line` and
`column`/`sourceColumn` fields provide navigation separately from authored excerpts.
Public projection is idempotent. An API download is therefore a safe view of an
artifact, rather than a byte-for-byte export of trusted local evidence.

With `server.inspection.redaction.enabled: false`, ordinary host/port/user, client ID,
queue/channel, Content-Type and non-secret DB properties/evidence settings are visible
to authorized users. Optional PII fields such as email/address are also visible.
`includeAllFields` additionally exposes ordinary fields normally hidden by display
policy and remains authorized server-side through the configured configuration-admin
role. Secret values remain protected in both modes. Field records use `state: hidden`,
`value: '[REDACTED]'`, `reasonCode: MANDATORY_SECRET` for protected values; unavailable
fields use `[HIDDEN]` and `UNAVAILABLE_BY_POLICY` or `OPTIONAL_FIELD_POLICY`.
Source traversal, symlink, deny-glob, sensitive-file and size restrictions remain active.

Load Template CLI: `load --list-templates [--format json]`, `load --debug <type> <id> --load-template <id> [--preview]`, or `load --debug --load-template <id> --bindings <file> [--preview]`. See [Load Templates](execution-modes/load.md) for the versioned contract, precedence, migration and safe preview. Windows `att.bat` accepts the same arguments.
