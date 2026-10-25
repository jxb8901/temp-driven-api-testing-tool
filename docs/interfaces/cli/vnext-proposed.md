<!-- att-doc-status: planned release=unreleased issue=287 -->
# Proposed CLI vNext

> **Planned, not released.** This grammar is not available in ATT 4.2.4. Use the current [CLI Reference](../../reference/cli.md) for released commands. Promote this contract to Current only with a released and verified #287 implementation.

## Proposed grammar and behavior


```text
att [--local | --server <profile|url>] [-p <packageId>] [-e <profile>] \
    [--config <path>] [--format human|json] <run|load|validate> <target-kind> [target]
```

`--local` and `--server` are mutually exclusive. With neither flag, vNext uses Local Engine. Local Package selection uses `ATT_PACKAGE_ROOT` or the current directory when `-p` is omitted; `-p <id>` resolves only to the immediate `ATT_HOME/packages/<id>` directory. Remote RUN/LOAD and Package or selected-target validation require `-p <id>` registered with the Server; Remote `validate config` may omit it to check only central configuration. Remote paths are Package-relative, and the Server owns its Runtime Config.

| Command | Example | Behavior |
|---|---|---|
| `run` | `att --local -p payments run case quickStart.default.QS001` | Run a Case, Suite workbook, all Cases, Template, Flow, or Tool; Local runs synchronously. |
| `load` | `att --server sit -p payments load flow PAYMENT.submit --arrival-rate 5/s --duration 2m` | Run a Flow, Template, Tool, or authored Load Scenario through the existing Load Engine. |
| `validate config` | `att --local -p payments -e SIT validate config` | Check shared Runtime Config and the selected Package's effective Config and Testdata references without business I/O. |
| `validate package` | `att --server sit -p payments validate package` | Check the full registered Package inventory and static dependencies. |
| selected `validate` | `att --local -p payments validate flow PAYMENT.submit` | Check the target and its dependency closure. |
| `validate --all` | `att --server sit validate --all --format json` | Aggregate shared Config, active Server Config, and all in-scope Packages. Server mode requires installation-wide administrator permission. |

`validate --all` cannot be combined with a target, `-p`, or `-e`. Local validation never creates or changes Server data directories. A missing optional active Server Config is reported as `SKIP`; an explicitly selected missing or invalid file is `FAIL`. Server validation checks only registered Packages and uses the Server-owned Runtime Config.

Validation is read-only: it does not execute Tools, send Load traffic, probe DB/MQ systems, or modify Config, Package files, or outputs. Human output has hierarchical `PASS`/`FAIL`/`WARN`/`SKIP` sections. `--format json` emits one `att-validation/v1` report on stdout. Exit codes are `0` for PASS, `1` for functional RUN/LOAD failure, `2` for invalid input or validation failure, `3` for execution error/cancellation, and `4` for Remote transport or untrusted-result failure. No Local Job store or `--detach` is part of this grammar.

Common vNext options are `-p, --package`, `-e, --env`, `--format`, `--quiet`, `--verbose`, `--help`, and `--version`. `--config <path>` is Local-only. RUN selection options include `--suite`, `--suite-dir`, `--case`, `--tag`, `--exclude-tag`, `--all`, `--rerun-failed`, `--dry-run`, `--fail-fast`, `--run-id`, and Local-only `--output-dir`. Resource RUN/LOAD accepts `--input` and repeatable `--set`; LOAD accepts `--users` or `--arrival-rate` plus duration, warmup, ramp, think-time, concurrency, and overload options. `--users` and `--arrival-rate` cannot be combined. Unsupported options and invalid combinations fail before submission or traffic.

### Migrate existing commands

| Existing syntax | vNext syntax |
|---|---|
| `att run --suite testcase/payment.xlsx` | `att --local run suite testcase/payment.xlsx` |
| `att debug flow PAYMENT.submit --input debug.yaml` | `att --local run flow PAYMENT.submit --input debug.yaml` |
| `att load load/payment.yaml --users 5 --duration 60s` | `att --local load scenario load/payment.yaml --users 5 --duration 60s` |
| `att remote run payments --suite testcase/payment.xlsx` | `att --server sit -p payments run suite testcase/payment.xlsx` |
| `att remote validate payments --package` | `att --server sit -p payments validate package` |

Legacy verbs remain on the compatibility path during migration. They are not additional vNext commands. Management commands and advanced Remote detach remain outside this minimal contract.

## Remote examples

These examples use the proposed grammar and are not runnable with ATT 4.2.4. The existing ATT Remote command and Job-management interface remains the current compatibility path.


The vNext syntax puts backend and Package selection before one of the three commands `run`, `load`, or `validate`. It uses the existing Server profile and authentication transport. Remote target paths stay Package-relative; `--config` and `--output-dir` are Local-only.

```sh
./att.sh --server sit -p payments run suite testcase/payment.xlsx
./att.sh --server sit -p payments -e SIT load flow PAYMENT.submit --arrival-rate 5/s --duration 2m
./att.sh --server sit validate config
./att.sh --server sit -p payments -e UAT validate config
./att.sh --server sit -p payments validate package
./att.sh --server sit validate --all --format json
```

`validate config` reads the Server-owned Runtime Config and returns source-aware diagnostics without executing business Tools or contacting DB/MQ systems. `validate --all` checks the shared Runtime Config, active Server Config, and registered Packages. The Server requires an administrator for this installation-wide validation; it never scans unregistered directories. A Remote client cannot submit a Runtime Config path. The result uses the versioned `att-validation/v1` report and validation failures exit with code `2`.

Use `--detach` to return the job ID without keeping a background process:

```sh
./att.sh remote --server ci run payments --all --detach
```
