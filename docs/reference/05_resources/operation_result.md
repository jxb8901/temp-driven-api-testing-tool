### 7.1 Operation Result and Evidence

ATT keeps an operation's logical result separate from execution evidence:

~~~text
Operation
├── result       # native typed value
└── evidence     # bounded execution/transport metadata
~~~

The Action publishes the final operation value at output.result. Action status, assertion detail, diagnostic and attempts describe execution; they do not replace the business result. Command stdout is parsed through stdoutFormat. HTTP/MQ responses use responseFormat. DB operations return native typed values. Render returns DocumentValue as described in [Actions and Typed Values](../14_actions.md).

Resource evidence can include low-cost metadata. A helper may also configure an optional human-readable snapshot:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output supports json, yaml, xml, text and sqlplus. It is presentation only; it does not mutate or replace output.result. Secret-bearing values are filtered or omitted.

Load scenarios may set evidence.resources.output to inherit (default) or none. none skips optional resource-output formatting/materialization. inherit defers formatting until a success sample or failure receives a retention slot. Metrics-only iterations do not serialize resource output or create an evidence workspace. Transport parsing and Render representation are unchanged.