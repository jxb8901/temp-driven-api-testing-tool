### 14.3 Migration Notes

ATT 3.6.0 separates typed operation results, external parsing, rendered documents, outbound transport and human-readable evidence.

| Previous field/model | 3.6.0 migration |
|---|---|
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Use templateFormat. Render returns DocumentValue with exact text and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the DocumentValue directly as HTTP body or MQ payload. |
| requestFormat on rendered output | Remove it. requestFormat is only for abstract Map/List values; DocumentValue + requestFormat fails. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Update schemaVersion to the ATT 3.6.0 active schema and migrate the fields listed above. Archived schemas under schemas/history are not active runtime contracts. |

A Render-to-HTTP example:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
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

For Load, migrate old single-target or v1.1 scenarios to att-load/v1.2 workloads form, then change the schemaVersion to att-load/v1.3 to enable workload-level vars. `inputs` remains EXEC.INPUT; `vars` is evaluated after each execution's EXEC.ID and EXEC.OUTPUT_DIR are initialized and before the target starts. Exact references preserve native values, dependencies are order-independent, and cycles or external/stateful calls fail validation. The optional top-level execution.execIdFormat still uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](../14_actions.md), [Runtime and Context Model](../03_runtime_context.md), [Load Mode](../04_execution_modes/load.md) and [Schema Matrix](schema_matrix.md).
