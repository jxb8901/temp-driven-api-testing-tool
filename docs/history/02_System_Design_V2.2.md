# ATT V2.2 System Design

Status: implementation contract for ATT 2.2.1
Scope: tool organization, argv commands, built-ins, SSH execution, and cross-platform launchers

## 1. Goals

V2.2 adds six capabilities without changing testcase, sidecar, template-action, report, or Case Context contracts:

1. External tools may be split into independently maintained tool-group files.
2. Existing tools declared directly in `config/config.yaml` remain global tools and keep their unqualified calls.
3. Built-ins remain global. V2.2.0 adds `nvl`, `iif`, and `nchar`; V2.2.1 adds common string/date functions and single-value shorthand.
4. Commands support an explicit argv-list form; legacy scalar commands are normalized to the same internal list.
5. Global tools and each tool group may execute through one configured SSH target.
6. `att.sh` and `att.bat` provide equivalent Unix/macOS and Windows CLI entrypoints.

V2.2 does not load user Java classes. A built-in provider boundary is reserved for V3; V2.3 also remains closed to custom Java providers.

## 2. Versioned artifacts

- Product version: `2.2.1`.
- New global configuration schema: `att-config/v2.2`.
- New group schema: `att-tool-group/v2.2`.
- Existing V2.1 sidecar, template, run, validation, CI, and JUnit contracts remain unchanged.
- An unchanged `att-config/v2.1` file remains readable. V2.2-only fields require `att-config/v2.2`.

The V2.2 schema catalog references both the V2.2 global/group schemas and the unchanged V2.1 schemas.

### 2.1 Cross-platform launcher contract

- Linux and macOS use `./att.sh`; Windows uses `att.bat`. Both forward all arguments to `att.FrameworkRunner` and preserve its process exit code.
- A binary release launcher finds `lib/att-*.jar` and starts the runner with every JAR below `lib/` on the platform-native classpath.
- Source-tree launchers compile with Maven when available. `att.bat` may use already compiled `target/classes` when Maven is absent; if neither compiled classes nor Maven exists, it exits with code 2 and an actionable diagnostic.
- Both binary and source release archives contain `att.sh` and `att.bat`. Package validation requires both launchers.
- Launcher portability does not make individual external tools portable. Tool authors must configure executables/scripts supported by the target host; for example, a `.sh` sample may need a `.bat`, `.cmd`, PowerShell, or native executable equivalent on Windows.
- Windows executable discovery honors `PATH` plus `PATHEXT`; Unix/macOS discovery continues to use exact executable names on `PATH`.

## 3. Global configuration

```yaml
schemaVersion: att-config/v2.2
outputDirectory: output
environment: SIT
timeoutMs: 10000

toolGroups:
  - config/tools/database.yaml
  - config/tools/application-log.yaml

# Optional default execution target for inline global tools only.
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519

# Existing syntax remains valid. These are global tools.
tools:
  getAcDate:
    name: Get account date
    description: Return the account date
    command: ["/opt/att/get-ac-date"]
    output: txt
    arguments: {}
```

Rules:

- `toolGroups` is an optional ordered list of unique, safe, package-relative YAML paths.
- A referenced file must exist, be a regular non-symbolic-link file, and remain below the package root.
- Inline `tools` retain their existing keys and are invoked as `#{getAcDate()}`.
- Inline tool keys must not contain `.`; built-in names are reserved for built-ins.
- Root `ssh` applies to every inline global tool. A group uses only its own `ssh` value and does not inherit root `ssh`.

## 4. Tool-group file

```yaml
schemaVersion: att-tool-group/v2.2
id: database
name: Database tools
description: Read-only database operations

# Optional group dispatcher. It is prepended to every group tool invocation.
script: ["/opt/att/database-tools"]

# Optional SSH target for this group.
ssh:
  host: db-tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519

tools:
  selectPayment:
    name: Select payment
    description: Query one payment
    command: ["select-payment", "--case", "${caseId}"]
    output: json
    arguments:
      caseId:
        name: Case ID
        description: Full ATT Case ID
        required: true
```

Rules:

- `id`, `name`, `description`, and a non-empty `tools` map are required.
- Group IDs are package-unique and match `[A-Za-z_][A-Za-z0-9_-]*`.
- Tool keys use the same pattern and are unique within the group.
- A grouped tool is invoked only by its qualified name: `#{database.selectPayment(caseId=${CASE.caseId})}`.
- A configured global or grouped tool that declares exactly one argument may omit its argument name, for example `#{database.selectPayment(${CASE.caseId})}`. Tools declaring zero or multiple arguments retain their existing call rules; multi-argument tools require named arguments.
- The qualified name is also the configuration lookup key. Persisted evidence uses `TOOL.database.selectPayment` nesting so Context paths remain navigable.
- Group IDs and tool keys cannot contain `.` because the dot is the namespace separator.
- A group may omit `script`. Without it, the tool command itself contains the executable argv.

## 5. Group script contract

When `script` exists, ATT constructs the logical tool argv in this exact order:

```text
<group script argv> <unqualified tool key> <expanded tool command argv>
```

For the example above:

```text
/opt/att/database-tools selectPayment select-payment --case workbook.payment.TC001
```

The group script therefore receives:

- argument 1: the unqualified tool key;
- argument 2 onward: the tool-specific command and resolved arguments.

`script` is normalized using the same scalar/list rules as `command`. Script argv cannot reference tool arguments.

## 6. Command and argv rules

Every tool `command`, and every group `script`, accepts either form:

```yaml
# Legacy scalar form
command: "./tools/query.sh --case ${caseId}"

# V2.2 argv-list form
command:
  - ./tools/query.sh
  - --case
  - "${caseId}"
```

Normalization:

- A scalar is parsed once with the existing V2 tokenizer and stored as an argv template list.
- A YAML list is already an argv template list; each list item is exactly one argv element and is never tokenized.
- Both forms use identical placeholder validation and runtime expansion after normalization.
- A declared final `delimit` argument may expand one exact-placeholder argv item into zero or more argv items.
- Unknown placeholders, an empty argv list, a blank executable, or a delimited placeholder embedded in surrounding text are validation errors.
- ATT never invokes a local shell.

The argv-list form is recommended because quoting is represented by YAML rather than by a command tokenizer.

## 7. Built-ins

Built-ins remain global and are called without a group prefix. A function accepting exactly one `value` accepts either `value=...` or one unnamed argument. Multi-argument functions accept either a complete positional list or their documented names; named and positional arguments must not be mixed.

V2.2.0 functions are `nvl(value, defaultValue)`, `iif(condition, trueValue, falseValue)`, and `nchar(count, value)`. Their null, eager-branch, boolean, and 0–10000 repetition contracts remain unchanged.

V2.2.1 adds:

| Family | Functions | Contract |
|---|---|---|
| trim | `ltrim(value)`, `rtrim(value)` | Remove leading or trailing whitespace; `trim` remains available for both sides |
| slice/search | `substr(value, start[, length])`, `indexOf(value, search[, fromIndex])` | Zero-based UTF-16 indexes; negative `substr` start counts from the end; `indexOf` returns `-1` when absent |
| predicates | `contains(value, search)`, `startsWith(value, prefix)`, `endsWith(value, suffix)` | Case-sensitive literal matching and `true`/`false` text output |
| replacement/padding | `replace(value, target, replacement)`, `padLeft(value, length[, pad])`, `padRight(value, length[, pad])` | Literal replacement; padding defaults to one space, never truncates, and is bounded to length 10000 |
| current time | `sysdate()`, `systimestamp()` | System-zone `yyyy-MM-dd` and ISO offset timestamp with milliseconds |
| date transform | `formatDate(value, pattern[, zoneId])`, `dateAdd(value, amount, unit)` | ISO-8601 input; locale-independent Java date patterns; optional IANA/offset zone; arithmetic units year/month/week/day/hour/minute/second/millisecond |

`substr` clamps an overlong requested length at the end but rejects a start outside the text. `formatDate` converts instants to the configured zone (or the JVM system zone); an explicit zone also converts offset/zoned timestamps and attaches a zone to local date-times. `dateAdd` preserves the input ISO shape and rejects units incompatible with that shape, such as adding hours to a date-only value.

Implementation reserves an internal `BuiltInProvider`/registry boundary. Only ATT's built-in provider is registered in V2.2.1; no class name, classpath, plugin directory, or user implementation field is accepted by configuration.

## 8. SSH execution

`ssh` has this strict shape:

```yaml
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519
```

- `host` and `user` are required non-blank strings without whitespace or control characters.
- `port` defaults to 22 and is limited to 1–65535.
- `identityFile` is optional. A relative value resolves from the package root; an absolute value remains host-local. It must resolve to a readable regular non-symbolic-link file. ATT supplies it to OpenSSH `-i` or the Java identity repository and does not copy or log key contents.
- Passwords and password fields are not supported. Local OpenSSH authentication uses an SSH agent or `identityFile`. The Java fallback does not inherit an OpenSSH agent or `~/.ssh/config`; configure `identityFile` unless the server accepts another non-interactive method.
- ATT always performs strict host-key checking. OpenSSH receives `BatchMode=yes` and `StrictHostKeyChecking=yes`; the Java fallback requires a readable non-symbolic-link `~/.ssh/known_hosts`.
- The action/global timeout still bounds the complete SSH process.

After logical argv construction, local execution passes the list directly to `ProcessBuilder`. SSH execution prefers the local `ssh` command and builds:

```text
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes [-p PORT] [-i KEY] -- USER@HOST <encoded remote command>
```

If no executable `ssh` is found on `PATH`, ATT emits a warning and runs the same encoded command through the bundled `com.github.mwiede:jsch` exec channel. The minimal ATT package does not bundle Bouncy Castle. Java/JSch algorithm limitations and remedies are documented in Reference Manual Chapter 09.

Both transports execute a remote command through the remote account's POSIX shell. ATT single-quotes every logical argv item and escapes embedded single quotes before joining it into the one remote command string. This preserves argument boundaries and prevents resolved values from becoming remote shell operators. SSH stdout, stderr, exit code, timeout, retry, parsing, assertion, `saveAs`, and Case log behavior are otherwise identical to local tools.

Package validation checks SSH field syntax and the local identity file when one is configured. When local `ssh` is unavailable it reports that mwiede/jsch will be used instead of rejecting the package. Validation cannot prove remote connectivity, host-key compatibility, authentication, algorithm negotiation, or remote executable existence. Local tool/script executable checks are skipped for SSH-targeted tools.

## 9. Lookup and validation

At configuration load time ATT builds one immutable lookup map:

- global tool: `toolKey`;
- grouped tool: `groupId.toolKey`.

Validation fails on duplicate group paths, duplicate group IDs, duplicate qualified names, invalid identifiers, missing files, unknown call names, invalid command placeholders, or malformed SSH settings. Built-in names are rejected as global external-tool keys. A group tool may reuse a built-in's unqualified name because its qualified name is distinct.

`validate --package` loads and validates every configured group, every global/group tool, and every template call. Tool totals include global and grouped external tools; built-ins are not included.

## 10. Evidence and documentation

Tool evidence records:

- qualified configured name;
- group ID and unqualified tool key when grouped;
- logical argv before SSH wrapping;
- executed argv (local/OpenSSH process argv, or logical remote argv for the Java transport);
- SSH destination and selected transport metadata without secret contents;
- existing input/output/stdout/stderr/status/retry fields.

Generated package documentation lists global tools first, then tool groups and their tools. Quick Start shows one inline tool and one group. The reference manual documents both command forms, dispatch ordering, built-ins, and SSH limitations.

## 11. Test and release gates

Required automated coverage:

- V2.1 global-tool configuration compatibility;
- V2.2 group loading, unique IDs, qualified lookup, and invalid paths;
- scalar-to-argv normalization and exact list preservation;
- group script ordering;
- V2.2.0 plus V2.2.1 built-in success/error cases, including fixed-clock date behavior;
- unnamed single-argument tool and single-value built-in calls, plus multi-argument rejection;
- Windows launcher/package presence and platform-native classpath construction;
- OpenSSH/JSch transport selection, fallback warning, safe remote quoting, and no real network dependency;
- local versus SSH executable validation;
- end-to-end template calls for global and grouped tools;
- package validation and generated documentation visibility.

Release gates remain `mvn clean test`, `./att.sh validate --package`, built-wrapper validation, and `./build.sh`. The release archive audit additionally verifies `att.bat` and both launchers in the source archive; Windows execution is validated on a Windows host or CI runner when available.
