## Appendix C — Migration Notes

ATT 3.6.2 separates typed operation results, external parsing, rendered strings, outbound transport and human-readable evidence.

| Previous field/model | 3.6.2 migration |
|---|---|
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Remove the old format/persistence fields. Render returns the exact String and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the Render String directly as HTTP body or MQ payload, or use an explicit resource file argument. |
| requestFormat on rendered output | Remove it. requestFormat is only for abstract Map/List values; String + requestFormat fails. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Use the active schema from [Appendix A](schema_matrix.md) and migrate the fields above. Historical schemas are not active contracts. |

A Render-to-HTTP example:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

For an abstract value, use requestFormat explicitly:

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

For Load, migrate old single-target or v1.1 scenarios through the historical v1.2/v1.3 loaders, then change the schemaVersion to att-load/v1.4. Root defaults may be shared by multiple workloads; each workload's `inputs`, `vars`, load policy, execution settings and thresholds override the corresponding root values. `inputs` remains EXEC.INPUT; `vars` is evaluated after each execution's EXEC.ID and EXEC.OUTPUT_DIR are initialized and before the target starts. Exact references preserve native values, dependencies are order-independent, and cycles or external/stateful calls fail validation. The optional top-level execution.execIdFormat still uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](../14_actions.md), [Runtime and Context Model](../03_runtime_context.md), [Load Mode](../04_execution_modes/load.md) and [Schema Matrix](schema_matrix.md).

### Removed fields and migration

ATT 3.6.0 accepts only the current schema for each resource. Historical versions are archived under schemas/history and are not active contracts.

| Old configuration | 3.6.0 form |
|---|---|
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | templateFormat; consume output.result as DocumentValue; no implicit file replacement |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ | Pass DocumentValue directly as HTTP body or MQ payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](../03_runtime_context.md) for META lifecycle and [Load Mode](../04_execution_modes/load.md) for execution identity and retained evidence paths.

### Debug schema migration

`att-debug/v1.0` is historical; upgrade to `att-debug/v1.1`. Template/Flow may define `vars` to seed `EXEC.VARS`; Tool targets do not support `vars`. See [Debug](../04_execution_modes/debug.md) for current input and argument rules.

### Global configuration migration

Old `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` fields are not part of the active global contract. See [Configuration](../09_configuration.md) for current fields.

### Environment profile migration

When migrating complete-config packages, preserve descriptors and Actions, move common settings to `config/config.yaml`, move descriptor lists to `environments.<NAME>`, and select with `--config config/config.yaml --env <NAME>`. See [Configuration](../09_configuration.md) for the current contract.
