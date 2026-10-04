# CLI reference

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
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
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


Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

No-target `debug` and `load` are read-only discovery commands. Debug validates target contracts without invoking Tools or creating output. Load scans only declared `att-load/*` YAML, validates every target before listing the scenario, reports invalid declared descriptors, and ignores unrelated YAML. Both accept `--config`, `--env`, `--format`, `--quiet`, and `--verbose` in discovery mode.

## Typed overrides and quick Load

`--set` is repeatable and accepts exactly one namespace: `input`, `arg`, or `vars`. Values use safe YAML parsing (for example `42`, `true`, `null`, `[a, b]`, or `{id: 7}`), and nested paths may use map keys and numeric list indexes such as `input.customer.ids[0]=42`. Duplicate assignments are applied in order, so the last value wins. ATT expressions are not evaluated while parsing an override; quote expression-looking values when a shell could expand them. `arg.*` is Tool-only; `vars.*` is Template/Flow-only. Unqualified overrides are rejected for multi-workload Load scenarios.

`load/load.yaml` is an optional, policy-only `att-load/v1.6` file. It may contain `load`, `execution`, `thresholds`, `evidence`, and `seed`, but no target or business inputs. `load --debug` promotes sidecar `inputs` to `EXEC.INPUT`, Template/Flow `vars` to bootstrap `EXEC.VARS`, or Tool `arguments` to the Tool call, then runs through the regular Load validator, scheduler, and evidence pipeline. Explicit CLI pacing fields override the policy. Without a policy, provide a complete policy on the command line; for example:

```yaml
schemaVersion: att-load/v1.6
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

This chapter defines target, `--input`, `--set` and `--env` syntax in the option matrix. [Debug](execution-modes/debug.md) owns input discovery, bootstrap variables, protected roots and output lifecycle.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command/run succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more FAIL results and no ERROR/INVALID |
| 2 | CLI/configuration/validation/INVALID failure |
| 3 | One or more ERROR results or unrecoverable runtime failure |

## Complete option matrix

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.11` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` or `load`.

Load uses the scenario as the base and explicit workload options override the corresponding fields before the effective scenario is validated again:

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

Repeatable `--set <input|arg|vars>.<path>=<yaml-value>` applies safe-YAML typed overrides before expression evaluation. The same option works for `debug`, single-workload Load scenarios, and `load --debug template|flow|tool <id>`. Quick Load uses `load/load.yaml` when present; otherwise provide a complete policy on the command line. For example:

```sh
./att.sh debug template PAYMENT_INVOKE --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
```

The complete workload override set is `--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, and `--overload-policy`. `--think-time` is closed-VU only. Common selection/output options remain command-specific: `--suite`, `--suite-dir`, `--case`/`--case-id`, `--tag`, `--exclude-tag`, `--all`, `--run-id`, `--output-dir`, `--format`, `--quiet`, `--verbose`, `--ci-output`, `--dry-run`, `--fail-fast`, `--rerun-failed`, `--update-snapshot`, `--package`, `--selected`, `--input`, `--set`, `--queue`, `--parallel`, `--allow-parallel-runs`, `--profile`, `--config`, `--env`, and `--help` are accepted only where the command contract permits them.
