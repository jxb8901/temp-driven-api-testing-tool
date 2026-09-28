### 5.1 Tool

A Tool is a named external or framework-native capability. A Tool declares exactly one backend: **command-backed** or **call-backed**. Current config and Tool Group schemas use `result.format`; the legacy Tool `output` field is rejected by the current schema.

#### Command-backed Tool

Command-backed Tools execute a configured argv contract locally or through configured SSH transport. Argv-list definitions preserve item boundaries; scalar command definitions are tokenized into the same internal argv model. ATT does not implicitly invoke a shell or expand wildcards for ordinary process-backed Tools. Stdout/stderr, exit code, timeout and process diagnostics are evidence; a non-zero process exit does not by itself define assertion PASS/FAIL unless the Action contract says so.

A command-backed Tool must declare `result.format: text|json|yaml|xml`. This Tool-level format selects how stdout is parsed into the typed primary `output.result` (for example, JSON stdout becomes a map); it is not a raw-byte mode. Bounded process preview and full streamed capture remain separate evidence.

```yaml
tools:
  queryTool:
    name: Query tool
    description: Parse JSON stdout as a typed result
    command: [./tools/query.sh]
    result: {format: json}
    arguments: {}
```

Use command-backed Tools for scripts, CLIs, SSH and third-party executables.

#### Call-backed Tool

Call-backed Tools execute typed framework-native calls without converting typed values into process strings. Supported calls include built-ins and primary DB, MQHelper, or HTTPHelper operations; call-backed MQ/HTTP operations must run as a `type: tool` Action's primary call. The call's native return type is preserved. Optional Tool-level `result.format` is only a preferred serialization format when Action output is written to a file or Case log; it never reparses or changes the native value. A call-backed Tool does not need `result.format` when no serialization default is needed.

```yaml
tools:
  requestPayment:
    name: Request payment
    description: Invoke the selected payment HTTP helper
    call: "#{http.paymentApi.post(path='/v1/payments', body=${input.request})}"
    result: {format: json}
    arguments:
      request:
        name: Request
        description: Typed request body
        required: true
```

Both backends publish the same public Action envelope. The primary value is `${output.result}` while active and `${EXEC.ACTIONS.<id>.output.result}` after publication. Final operation evidence is under `output.evidence`; retries preserve per-attempt evidence under `output.attempts[n].evidence`.

The Action's optional `result.format` supports only `text|json|yaml|xml` and controls file/console serialization, not the in-memory result. `result.path` is optional; omitting it creates no artifact. `path: console` writes the serialized value to the Case log. Post-operation evidence collectors execute after the primary operation and before that attempt's assertion; collector failure policy does not replace the primary `result`.
