# SSHHelper system design

Status: Maintainer documentation. The [Reference Manual](../reference/05_resources/sshhelper.md) defines the public contract.

## Resolution and validation

The config loader verifies `att-config/v2.10`, applies one `environments.<name>` shallow list replacement, loads `att-sshhelper/v1.0` descriptors, and resolves current Tool group `ssh.helper` bindings against the selected registry. There is no Action-level routing state. The descriptor loader preserves list order in a `LinkedHashMap`, rejects unsafe/duplicate paths and case-insensitive duplicate IDs, and constructs immutable effective `SshConfig` instances by applying defaults followed by per-instance overrides. Schema and semantic checks reject unknown fields, missing effective host/user, invalid ports/strategy and missing logical bindings before external execution. Older v2.6/v2.2 direct SSH paths remain separate and unchanged.

```text
CLI --env -> effective config -> SSHHelper registry -> Tool group binding
                                                   -> command argv -> selection -> SSH transport
```

## Selection and execution

The Tool group strategy overrides the helper default. `single` selects the sole instance; `random` chooses one uniformly; `roundRobin` uses an atomic cyclic counter per helper, so concurrent invocations cannot duplicate one counter position. `all` constructs one task per instance in descriptor order and runs them through a fixed pool of at most `fanout.maxConcurrency` workers. Each task uses the already-expanded command argv and the effective Action/Tool/global timeout. It never retries on a different host. Completed outcomes are assembled by descriptor order, not by completion order. An individual I/O/parse/timeout failure stays in its host entry and does not erase peer outcomes. A completed non-zero process exit remains a successful execution with `exitCode` evidence for the Action assertion; it does not fail fan-out by itself. The aggregate succeeds if every entry completed without an operational failure. Parent interruption cancels pool tasks; `CommandRunner` forcibly destroys interrupted OpenSSH processes, and `JschSshClient` disconnects channel/session in `finally`.

OpenSSH and Java SSH receive the same effective `SshConfig` and safely quoted logical argv. An exact `${ENV:NAME}` identity-file path is resolved at descriptor load; a missing value fails without showing the secret. Transport selection is infrastructure-dependent, not a selection strategy. Both retain strict host-key checking. No `identityFile` contents, environment-supplied key paths, resolved credentials or session handles are copied into Context or evidence.

The evidence boundary redacts environment-supplied identity paths from transport stderr previews and streamed Case-log diagnostics as well as argv and exception messages. This applies in both single-target and fan-out execution; parsed business stdout remains unchanged, so remote commands must not print secrets.

## Result and evidence boundary

Single-target invocation preserves the existing parsed Tool result and adds logical ID, selected instance/host, strategy/source and transport metadata. For `all`, the operation value is `{instances: {<id>: <per-host result>}}`; each host records status, endpoint, transport, start/end/duration, exit code, bounded stdout/stderr, parsed output or error. The Tool operation evidence is subsequently wrapped by the common Action lifecycle. Assertions and later Action references may inspect the ordered per-instance map. On mixed outcome the aggregate failure is categorized while preserving that complete map in exception evidence. The result is not a distributed transaction or a failover result.

## Scope and verification

Validation should exercise SIT/UAT re-binding, descriptor inheritance/override, malformed schema and unknown binding, concurrent round-robin, bounded and mixed `all`, timeout/cancellation, both transports and legacy direct SSH. Run the focused tests, full Maven test/package gate, and regenerate/check both Reference Manuals. Future host discovery, per-call selection and cross-host orchestration require a separate contract.
