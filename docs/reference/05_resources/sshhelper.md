### 7.6 SSHHelper: logical SSH targets

#### SSH Resource Helper operations

SSHHelper also exposes the common Resource Helper form inside a normal `type: tool` Action: `ssh.<helperId>.execute`, `ssh.<helperId>.upload`, and `ssh.<helperId>.download`. The helper ID is logical; native Resource Helper calls select one physical instance using `single`, `random`, or `roundRobin`. `selection.strategy: all` is rejected for native Resource Helper calls; it is reserved for command-backed Tool fan-out. These calls are validated without opening an SSH connection, and they share the helper's concurrency bound and redacted identity handling.

```yaml
actions:
  health:
    type: tool
    call: >-
      #{ssh.application.execute(
        command='systemctl is-active example.service',
        stdoutFormat='text',
        timeoutMs=5000
      )}
  uploadRequest:
    type: tool
    call: >-
      #{ssh.application.upload(
        remotePath='/srv/app/request.json',
        payload=${EXEC.VARS.requestText},
        overwrite=true
      )}
  downloadResponse:
    type: tool
    call: >-
      #{ssh.application.download(
        remotePath='/srv/app/response.json',
        localPath='ssh/response.json',
        overwrite=true
      )}
```

`execute` requires `command` and accepts `stdoutFormat: text|json|yaml|xml` plus `timeoutMs`. Text returns the exact stdout String; the structured formats parse stdout into the native Map/List/scalar result. A timeout, non-zero remote exit, or parse failure returns an operation error with a distinct category and retains bounded stderr, exit code, byte counts and transport evidence. SSH resource execution treats a non-zero exit as an operation failure; this is separate from the legacy command-backed Tool fan-out contract described below.

`upload` requires `remotePath` and exactly one of `localPath` or `payload`. A local file must be a regular non-symlink file under the package or current Case output. A represented payload must resolve to a String or byte array; Map/List values are rejected rather than implicitly serialized. Absolute remote paths are allowed. Upload overwrite defaults to `true`.

`download` requires an absolute or relative `remotePath` and a Case-output-relative `localPath`. ATT writes through a temporary file and moves it into the controlled Case output directory; it does not parse the downloaded bytes. Download overwrite defaults to `false`, and an existing destination must be explicitly replaced with `overwrite: true`. The typed result is a transfer summary containing remote path, retained local path and byte count.

All three operations accept only named arguments. Unknown operations, helper IDs, arguments, duplicate arguments, invalid formats, invalid timeout values, missing required fields, upload source conflicts and unsafe local paths fail validation before external execution. Runtime evidence contains the logical helper, selected instance, host/port, operation, transport, timing and transfer/command details; command input and represented payload content are not copied into evidence. Environment-supplied identity paths remain redacted.

Native SSH failures expose stable categories in both invocation `error.category` and `SSH.error.category`:

| Category | Meaning |
|---|---|
| `SSH_CONNECTION_ERROR` | Connection, host verification, or channel setup failed without a timeout; also used when the backend cannot identify authentication reliably. |
| `SSH_AUTH_ERROR` | The Java backend identifies authentication rejection/cancellation or cannot initialize the configured identity. |
| `SSH_TRANSPORT_ERROR` | OpenSSH returned 255. This may indicate connection/authentication failure or a remote command that itself exited 255; ATT retains stderr and exit code without guessing from localized diagnostics. |
| `SSH_TIMEOUT` / `SSH_POOL_TIMEOUT` | Operation/connect deadline or concurrency wait expired. |
| `SSH_REMOTE_EXIT` | A remote command completed with a non-zero exit (OpenSSH 255 uses the category above). |
| `SSH_RESULT_PARSE_ERROR` | stdout parsing failed. |
| `SSH_UPLOAD_ERROR` / `SSH_DOWNLOAD_ERROR` | The corresponding transfer failed after connection setup, including remote permission/missing-path/protocol errors. |
| `SSH_PATH` / `SSH_ARGUMENT` | Local containment/security checks or argument validation failed. |
| `SSH_INTERRUPTED` | The caller interrupted the operation. |

Transfer connection/channel failures retain `phase: connect|channel`; timeout evidence also retains the applicable timeout budgets. All failures keep their selected operation and actual transport. Transfer errors remain ineligible for automatic timeout replay.

Native Resource Helper calls use one absolute Action deadline covering concurrency-pool wait, connection and channel setup, and command or SFTP transfer. A per-call `timeoutMs` or helper `timeouts.commandTimeoutMs` sets the operation limit but cannot extend the enclosing Action deadline. `timeouts.connectTimeoutMs` caps connection establishment within the remaining deadline; it does not add time to the operation. The timeout applies to `execute`, `upload`, and `download`. Native `execute` `SSH_TIMEOUT` and `SSH_POOL_TIMEOUT` failures may use Action `retryOn: [TIMEOUT]`; native `upload` and `download` reject timeout retry because replaying a transfer can duplicate a side effect. A timed-out SFTP Action returns at its deadline while its concurrency lease remains held by the cleanup worker until the transfer worker and transport terminate.

SSHHelper routes a command-backed Tool to a stable logical application-server ID instead of embedding a physical host in the Tool group. The `att-sshhelper/v1.0` YAML descriptor contains `id`, optional `name`/`description`, optional `defaults` (`user`, `port`, `identityFile`), a non-empty ordered `instances` list, optional `selection.strategy`, and optional `fanout.maxConcurrency` (default 4, range 1–256). Each instance needs `id` and `host`; `user` must come from the instance or defaults. Instance fields override defaults; port defaults to 22 and must be 1–65535. Helper and instance IDs match `[A-Za-z_][A-Za-z0-9_-]*` and are unique ignoring case. Invalid hosts/users, unknown properties, duplicates, missing users, unsafe paths, and unsupported strategies fail before SSH execution.

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
timeouts: {connectTimeoutMs: 10000, commandTimeoutMs: 60000}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

Bind descriptor paths globally or in `environments.<NAME>.sshhelpers` of `att-config/v2.10`. Current packages use config v2.10 and Tool Group v2.9. The selected environment's list replaces the global list; omission inherits it. A group binding must resolve to the same logical ID in each selected profile. SIT can bind one host and UAT two without changing the Tool, Action, or Resource Helper call:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all} # command-backed Tool fan-out only; native ssh.application.* rejects all
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # optional group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

The unchanged Action calls `app.status`. Set `APP_SSH_KEY` to a readable private-key **path** in the local/CI secret environment, then validate both profiles: `./att.sh validate --config config/config.yaml --env SIT --package` and the equivalent UAT command. An exact `${ENV:NAME}` identity-file reference is resolved at load time; a missing/empty variable is rejected without revealing its value. A Tool group uses either direct SSH (`host`, `user`, optional `port`/`identityFile`) or logical SSH (`helper`, optional `selection`), never both. Call-backed Tools may target the native SSH Resource Helper call when it is the primary `type: tool` operation. The active Tool Group schema is v2.9. Command-backed Tools declare stdout parsing with `stdoutFormat` (`text|json|yaml|xml`); call-backed Tools preserve their native result type. Superseded config and group schemas are migration references only. SSH Resource Helper calls publish only `META.SSHHELPER.id` and `.type`; endpoint, user, identity file and credentials remain private. Resource calls have no Action- or per-call strategy override; selection remains helper configuration.

Strategy precedence is group override then helper default. For native Resource Helper calls, one instance works without a strategy (`single`), multiple instances require `random` or `roundRobin`, and `all` is rejected rather than silently selecting one host. For command-backed Tool routing, `random` selects one uniformly, `roundRobin` selects one via a thread-safe cyclic counter, and explicit `all` executes every listed instance once with bounded parallelism. **Command-backed `all` has side effects on every host**: use only commands safe across the entire group. There is no implicit fan-out, cross-host retry, or failover. If an author configures an Action timeout retry, the whole `all` invocation is repeated, not just one host. Each host gets the Action/Tool/global timeout; interruption cancels active OpenSSH processes or Java SSH sessions. Both transports receive the same normalized host/user/port/key. OpenSSH is preferred; mwiede/jsch fallback retains strict host-key verification and the limitations in the SSH diagnostics chapter.

For a single selected host, parsed `output.result` remains the legacy scalar/object value. Evidence adds `sshHelper`, `instance`, `host`, `selectionStrategy`, `selectionSource` (`helper` or `toolGroup`), transport, start/end/duration, exit code, output and errors. Only command-backed Tool fan-out uses `all`; its `output.result` contains `sshHelper`, effective `selectionStrategy`, `selectionSource`, and `instances` keyed in descriptor order. Every entry has `instance`, `host`, `port`, `transport`, `startedAt`, `endedAt`, `durationMs`, `status`, and when available `exitCode`, `stdout`, `stderr`, `rawOutput`, parsed `output`, or `error`. A completed command has `status: PASS` even with a non-zero `exitCode`; that code is evidence for the Action assertion, not an operational failure. The operation fails only on an execution, output-parse, cancellation, or timeout error; other hosts' evidence is retained. Assertions may inspect `${output.result.instances.app1.exitCode}`, `${output.result.instances.app1.status}`, or `${output.result.instances.app1.output}`. Credential contents and environment-supplied key paths are not recorded; keep private keys outside the package and do not put secrets in commands.

Environment-supplied identity paths are redacted from argv, transport stderr (including streamed Case-log diagnostics), and exception evidence for both single-host and `all` execution. This no-recording guarantee applies to ATT metadata and transport diagnostics; parsed business stdout remains unchanged, so commands must not print secret paths.

Migration: leave direct SSH unchanged if one physical target suffices. To migrate, move its host/user/port/key into a helper descriptor, bind that descriptor per environment, upgrade the group to v2.9, replace physical `ssh` with `ssh: {helper: application}`, and validate each environment. Actions stay unchanged. Inventory discovery, per-Action host override, distributed transactions, cross-host failover and orchestration are out of scope.
