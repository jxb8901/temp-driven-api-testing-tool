### 7.2 Tool

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

A call-backed Tool invokes a built-in or supported native DB/MQ/HTTP/SSH operation. Its native typed return value is output.result. Call-backed descriptors do not declare stdoutFormat.

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

### Tool-definition `command` expressions

#### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestText}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestText}` | Explicit Tool-input namespace |
| `${TOOL.input.requestText}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a project-file payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
        name: Request Body
        description: Request body String
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

The action call is the boundary between the general Runtime Context and this restricted Tool-input Context:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestText=${EXEC.VARS.requestText}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestText` and `environment`. The command then substitutes `${input.requestText}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestText}` / `${ENVIRONMENT}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases shown above, and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestText})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestText}` is canonical; `${TOOL.input.requestText}` and `${requestText}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestText` or `input.requestText` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestText}"
  - "--environment=${input.environment}"
```

Because this is a YAML argv list, each list item remains one atomic process argument even when its resolved value contains spaces or shell-like characters. ATT does not invoke a local shell.

#### Quotes, Context values, and atomic argv

Quotes inside a Tool call belong to the ATT expression grammar; they are not shell quotes. The outer `'...'` or `"..."` delimiters are removed before invocation, the opposite quote is literal, and a matching quote can be escaped with a backslash. A `${...}` reference embedded in a quoted value is interpolated, while an unquoted canonical Context path passes its typed value directly.

The following configured Tool keeps each declared input as one argv value:

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${input.message}", "${input.sourceFile}"]
    stdoutFormat: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

Use a YAML block scalar when a call contains several quote layers:

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}
```

The child process receives the three messages exactly as `Customer O'Reilly`, `status="READY"`, and, for example, `O'Reilly said "READY" for payment.payment.TC001`. A Context value that itself contains either quote needs no caller-side shell escaping and still occupies one argv item.

If a call is kept on one YAML line, YAML escaping is an additional and separate layer:

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

The first line escapes double quotes for the YAML double-quoted scalar. The second doubles the apostrophe for the YAML single-quoted scalar. The expression engine then evaluates the resulting `#{...}` text.

Ordinary process-backed Tools never ask a shell to reinterpret resolved inputs. Text such as `$HOME`, `$(date)`, `a*.xml`, `|`, `>`, and quotes carried by a Context value is passed literally. Use an explicitly reviewed wrapper when shell-like behavior is required; the shipped `fpp.exehelper` and `fpp.loghelper` provide only the narrowly documented pathname expansion above.

#### Illegal forms and token restrictions

Tool commands cannot directly read the general Runtime Context, use unique-suffix navigation, or navigate argument fields with bracket syntax. These `${...}` forms are rejected during configuration or package validation:

```text
${EXEC.INPUT.environment}
${EXEC.ID}
${EXEC.ID}
${STAGES.invoke.InstrAmt}
${input['requestText']}
${TOOL.input['requestText']}
${requestText.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestText})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a standalone global Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestText}"]
arguments:
  requestText:
    name: Request Body
    description: Project-file request body String
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestText}` or a transformed form such as `#{str.upper(${input.requestText})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

### Inline Tool descriptor fields

Global `tools` and Tool-group `tools` entries use the same Tool contract:

| Object | Allowed properties |
|---|---|
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `argName`, `argNameMode`, `delimit`, `x-*` |
