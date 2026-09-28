### 5.4 SSHHelper: logical SSH targets

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
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

Bind descriptor paths globally or in `environments.<NAME>.sshhelpers` of `att-config/v2.7`. The selected environment's list replaces the global list; omission inherits it. A group binding must resolve to the same logical ID in each selected profile. SIT can bind one host and UAT two without changing the Tool or Action:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.7
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
selection: {strategy: all}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.7
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
    output: txt
```

The unchanged Action calls `app.status`. Set `APP_SSH_KEY` to a readable private-key **path** in the local/CI secret environment, then validate both profiles: `./att.sh validate --config config/config.yaml --env SIT --package` and the equivalent UAT command. An exact `${ENV:NAME}` identity-file reference is resolved at load time; a missing/empty variable is rejected without revealing its value. A Tool group uses either direct SSH (`host`, `user`, optional `port`/`identityFile`) or logical SSH (`helper`, optional `selection`), never both. Call-backed Tools cannot use SSH. Existing inline global SSH and v2.6/v2.2 group files remain readable; the logical form requires v2.7. There is no Action- or per-call strategy override.

Strategy precedence is group override then helper default. One instance works without a strategy (`single`); multiple instances require one. `random` selects one uniformly, `roundRobin` selects one via a thread-safe cyclic counter, and explicit `all` executes every listed instance once with bounded parallelism. **`all` has side effects on every host**: use only commands safe across the entire group. There is no implicit fan-out, cross-host retry, or failover. If an author configures an Action timeout retry, the whole `all` invocation is repeated, not just one host. Each host gets the Action/Tool/global timeout; interruption cancels active OpenSSH processes or Java SSH sessions. Both transports receive the same normalized host/user/port/key. OpenSSH is preferred; mwiede/jsch fallback retains strict host-key verification and the limitations in the SSH diagnostics chapter.

For a single selected host, parsed `output.result` remains the legacy scalar/object value. Evidence adds `sshHelper`, `instance`, `host`, `selectionStrategy`, `selectionSource` (`helper` or `toolGroup`), transport, start/end/duration, exit code, output and errors. For `all`, `output.result` contains `sshHelper`, effective `selectionStrategy`, `selectionSource`, and `instances` keyed in descriptor order. Every entry has `instance`, `host`, `port`, `transport`, `startedAt`, `endedAt`, `durationMs`, `status`, and when available `exitCode`, `stdout`, `stderr`, `rawOutput`, parsed `output`, or `error`. A completed command has `status: PASS` even with a non-zero `exitCode`; that code is evidence for the Action assertion, not an operational failure. The operation fails only on an execution, output-parse, cancellation, or timeout error; other hosts' evidence is retained. Assertions may inspect `${output.result.instances.app1.exitCode}`, `${output.result.instances.app1.status}`, or `${output.result.instances.app1.output}`. Credential contents and environment-supplied key paths are not recorded; keep private keys outside the package and do not put secrets in commands.

Migration: leave direct SSH unchanged if one physical target suffices. To migrate, move its host/user/port/key into a helper descriptor, bind that descriptor per environment, upgrade the group to v2.7, replace physical `ssh` with `ssh: {helper: application}`, and validate each environment. Actions stay unchanged. Inventory discovery, per-Action host override, distributed transactions, cross-host failover and orchestration are out of scope.
