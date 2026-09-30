### 5.1 Tool

A Tool is a named external or framework-native capability. A descriptor selects exactly one backend: command or call.

#### Command-backed Tool

A command Tool declares stdoutFormat to parse external stdout into the typed result:

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat accepts text, json, yaml or xml. It is ingress parsing: ATT parses stdout once and publishes the typed value at output.result. It does not control human-readable logging or file output. Exit code, bounded stdout/stderr preview and streamed process artifacts remain evidence.

#### Call-backed Tool

A call-backed Tool invokes a built-in or supported native DB/MQ/HTTP operation. Its native typed return value is output.result. Call-backed descriptors do not declare stdoutFormat.

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation has no result.format/path/overwrite contract. File persistence is explicit to an API that defines it; human-readable presentation belongs to Log or configured evidence output. HTTP/MQ parsing is owned by those transport boundaries.

See [Actions and Typed Values](../14_actions.md) for Action result handling and [Operation Result and Evidence](operation_result.md) for typed results versus evidence.