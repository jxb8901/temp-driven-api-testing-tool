## Appendix C — Migration Notes

ATT 3.6.2 separates typed operation results, external parsing, project-file Strings, outbound transport and human-readable evidence.

| Previous field/model | 3.6.2 migration |
|---|---|
| `att-template/v3.4` or `att-flow/v3.4` with `type: render` | Change the descriptor to the active v3.5 schema and replace each Render Action with an Assign that uses a project-file expression. Historical v3.4 descriptors remain loadable only through the historical schema path. |
| `type: render` / `payload: path` | Use `type: assign`, a variable `name`, and `expression: "&{project-relative-file}"`; pass `${EXEC.VARS.<name>}` to the consumer. |
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Remove the old format/persistence fields. The project-file expression returns the exact UTF-8 String and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the project-file String directly as HTTP body or MQ payload, or use an explicit resource file argument. |
| requestFormat on a project-file String | Remove it. requestFormat is only for abstract Map/List values; String + requestFormat fails. |
| Dynamic or unsafe file locator | Replace it with one static project-relative file. Absolute paths, globs, dynamic locators, missing files, directories, non-UTF-8 bytes and symlink escapes are rejected. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Use the active schema from [Appendix A](schema_matrix.md) and migrate the fields above. Historical schemas are not active contracts. |

A project-file String passed to HTTP:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

The file is read as strict UTF-8 text. `${...}` and `#{...}` inside the file remain runtime expressions and are compiled without invoking external resources during validation. Run/Debug cache the compiled plan and invalidate it when the file fingerprint changes; Load freezes the validated file identity, content and plan for the scenario. File output is not reparsed as a new expression source.

For an abstract value, use requestFormat explicitly:

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

For Load, migrate old single-target or v1.1 scenarios through the historical v1.2/v1.3 loaders, then change the schemaVersion to att-load/v1.4. Root defaults may be shared by multiple workloads; each workload's `inputs`, `vars`, load policy and execution settings override the corresponding root values. Top-level thresholds remain aggregate-only; workload thresholds are declared per workload and are not inherited from the root. `inputs` remains EXEC.INPUT; `vars` is evaluated after each execution's EXEC.ID and EXEC.OUTPUT_DIR are initialized and before the target starts. Exact references preserve native values, dependencies are order-independent, and cycles or external/stateful calls fail validation. The optional top-level execution.execIdFormat still uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it.

The historical `att-load-profile/v1.0` policy file is migration-only: rewrite it as the current policy-only `att-load/v1.4` descriptor before use. It is not a current `load/load.yaml` example.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](../14_actions.md), [Runtime and Context Model](../03_runtime_context.md), [Load Mode](../04_execution_modes/load.md) and [Schema Matrix](schema_matrix.md).

### Historical schema migration

ATT 3.6.2 uses `att-template/v3.5` and `att-flow/v3.5` as the active schemas. The published `att-template/v3.4` and `att-flow/v3.4` definitions remain under `schemas/history/`; their historical Render Action is compatibility-only and is not part of the active contract. When migrating those descriptors, change their schema versions to v3.5 and apply the field changes below.

| Historical configuration | 3.6.2 form |
|---|---|
| `att-template/v3.3` or `att-flow/v3.3` | Follow the historical release migration to v3.4, then change to v3.5 and migrate the Render Action. |
| Historical `type: render` | Replace it with an Assign whose expression is `"&{project-relative-file}"`; use `${EXEC.VARS.<name>}` in later Actions. |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | Remove the old persistence fields. The project-file expression returns the exact UTF-8 String and creates no implicit result file. |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ | Pass the project-file String directly as HTTP body or MQ payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

Project-file paths are relative to the canonical project root. `./` and `../` are allowed only when the canonical target remains inside that root. The v1 contract has no globs or dynamic locators; the target must be a regular strict-UTF-8 file.

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](../03_runtime_context.md) for META lifecycle and [Load Mode](../04_execution_modes/load.md) for execution identity and retained evidence paths.

### Debug schema migration

`att-debug/v1.0` is historical; upgrade to `att-debug/v1.1`. Template/Flow may define `vars` to seed `EXEC.VARS`; Tool targets do not support `vars`. See [Debug](../04_execution_modes/debug.md) for current input and argument rules.

### Global configuration migration

Old `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` fields are not part of the active global contract. See [Configuration](../09_configuration.md) for current fields.

### Environment profile migration

When migrating complete-config packages, preserve descriptors and Actions, move common settings to `config/config.yaml`, move descriptor lists to `environments.<NAME>`, and select with `--config config/config.yaml --env <NAME>`. See [Configuration](../09_configuration.md) for the current contract.
