### 5.1 Tool

A Tool is a named external or framework-native capability. A Tool declares exactly one backend: **command-backed** or **call-backed**.

#### Command-backed Tool

Command-backed Tools execute a configured argv contract locally or through configured SSH transport. Argv-list definitions preserve item boundaries; scalar command definitions are tokenized into the same internal argv model. ATT does not implicitly invoke a shell or expand wildcards for ordinary process-backed Tools. Stdout/stderr, exit code, timeout and process diagnostics are evidence; a non-zero process exit does not by itself define assertion PASS/FAIL unless the Action contract says so.

Use command-backed Tools for scripts, CLIs, SSH and third-party executables.

#### Call-backed Tool

Call-backed Tools execute typed framework-native calls, such as supported DB read/update façades or pure built-ins, without converting typed values into process strings. Use them when the capability is naturally represented by a typed ATT call contract rather than an external process.

Both backends publish the same public Action envelope. The primary value is `${output.result}` while active and `${EXEC.ACTIONS.<id>.output.result}` after publication. Final operation evidence is under `output.evidence`; retries preserve per-attempt evidence under `output.attempts[n].evidence`.

A Tool Action may use the common `result` object and post-operation evidence collectors where permitted. `result.format` selects the in-memory representation, while optional `result.path` persists it. Collectors execute after the primary operation and before that attempt's assertion; collector failure policy does not replace the primary `result`.
