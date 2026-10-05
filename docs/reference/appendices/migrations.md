# Appendix C — migration notes

## File arguments and case-log paths

HTTPHelper calls no longer accept `file`; pass `&{project-relative-file}` directly as `body`. MQHelper `send` and `request` no longer accept `file`; pass the expression as `payload`. SSHHelper `upload` now requires content in `payload` and rejects `localPath`; pass `&{...}` directly. SSHHelper no longer supports `download`, because it requires a local destination path. Use a deliberately configured command-backed Tool for workflows that must retrieve files from a host. These changes remove native arbitrary-binary local-file input from these Resource APIs; `&{...}` supplies UTF-8 text.

Case logs, CLI output, and emitted case evidence display paths under the canonical package root as `$ATT_HOME` or `$ATT_HOME/<relative-path>`, with `/` separators. `$ATT_HOME` is a presentation token, not an environment variable, Context root, or file-expression locator. Runtime resolution and filesystem access continue to use canonical absolute Paths. Absolute paths outside the package root use a bounded `$EXTERNAL/<basename>` presentation when logged as Path values or embedded in diagnostic messages. Explicit remote-path fields and URLs retain their values.

## Previous release Testdata migration

Change global configuration from `att-config/v2.10` to `att-config/v2.11` and Load scenarios from `att-load/v1.4` to `att-load/v1.5`. The previous schemas remain catalogued under `schemas/history/` for migration diagnostics. `att-testdata/v1.0` is new: add descriptor paths to the selected environment profile's `testdata` list, then use `@{id}` references in Case/Stage, Debug, or Load workload input maps. Load scenarios can add package-relative top-level `testdata` paths as a Load-only overlay. Repeated logical IDs across layers mean a whole descriptor replacement; duplicate IDs inside one layer are invalid. Add an explicit selection policy for every descriptor containing multiple records. Existing packages without testdata references need no new descriptor files.

ATT 3.7.2 introduces `att-load/v1.6`. Existing v1.5 descriptors remain compatible and are normalized at load time. To use a closed-user target mix, change the schema version to v1.6 and replace that workload's `target` with a `mix` list of uniquely named entries, each with a positive integer `weight` and its own target. Mix entries are prevalidated before scheduling; only closed workloads support mixes. Load summaries now use `att-load-summary/v1.1` and include per-mix selection and metric data when applicable. The previous Load and summary schemas remain available as historical definitions.

Load workload `testdata.<id>` settings control `scope` and optionally replace the whole descriptor `selection` policy. Scope defaults to `iteration`; `user` is valid only for closed-VU workloads. Choose `error`, `recycle`, or `stop` exhaustion deliberately. Selection metadata is recorded without record values.

ATT 3.6.2 separates typed operation results, external parsing, file-content Strings, outbound transport and human-readable evidence.

| Previous field/model | 3.6.2 migration |
|---|---|
| `att-template/v3.4` or `att-flow/v3.4` with `type: render` | Change the descriptor to the active v3.5 schema and replace each Render Action with an Assign that uses a file-content expression. Historical v3.4 descriptors remain loadable only through the historical schema path. |
| `type: render` / `payload: path` | Use `type: assign`, a variable `name`, and `expression: "&{project-relative-file}"`; pass `${EXEC.VARS.<name>}` to the consumer. |
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Remove the old format/persistence fields. The file-content expression returns the exact UTF-8 String and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the file-content String directly as HTTP body, MQ payload, or SSH upload payload. |
| requestFormat on a file-content String | Remove it. requestFormat is only for abstract Map/List values; String + requestFormat fails. |
| Dynamic or unsafe file locator | Replace it with one static project-relative file. Absolute paths, globs, dynamic locators, missing files, directories, non-UTF-8 bytes and symlink escapes are rejected. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Use the active schema from [Schema and Version Matrix](schema-matrix.md) and migrate the listed fields. Historical schemas are not active contracts. |

A file-content String passed to HTTP:

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

For Load, migrate old single-target or v1.1 scenarios through the historical v1.2/v1.3 loaders, then change the schemaVersion to att-load/v1.5. Root defaults may be shared by multiple workloads; each workload's `inputs`, `vars`, load policy and execution settings override the corresponding root values. Top-level thresholds remain aggregate-only; workload thresholds are declared per workload and are not inherited from the root. `inputs` remains EXEC.INPUT; `vars` is evaluated after each execution's EXEC.ID and EXEC.OUTPUT_DIR are initialized and before the target starts. Exact references preserve native values, dependencies are order-independent, and cycles or external/stateful calls fail validation. The optional top-level execution.execIdFormat still uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it.

The historical `att-load-profile/v1.0` policy file is migration-only: rewrite it as the current policy-only `att-load/v1.6` descriptor before use. It is not a current `load/load.yaml` example.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](../actions.md), [Runtime and Context Model](../runtime-context.md), [Load Mode](../execution-modes/load.md) and [Schema Matrix](schema-matrix.md).

## Historical schema migration

ATT 3.6.2 uses `att-template/v3.6` and `att-flow/v3.6` as the active schemas. The published `att-template/v3.5`, `att-flow/v3.5`, and older definitions remain under `schemas/history/`; their historical DB and Render Actions are compatibility-only and are not part of the active contract. When migrating those descriptors, change their schema versions to v3.6 and apply the migration table's field changes.

| Historical configuration | 3.6.2 form |
|---|---|
| `att-template/v3.3` or `att-flow/v3.3` | Follow the historical release migration to v3.4, then change to v3.6 and migrate the Render/DB Actions. |
| Historical `type: db` with `query` or `update` | Use an ordinary `type: tool` Action with `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)`; query/scalar may retry, update must not use automatic retry. |
| Historical `sqlFile` | Use the single String argument `sql=&{project-relative-sql-file}`. `params` and `parameters` remain mutually exclusive. |
| Historical `type: render` | Replace it with an Assign whose expression is `"&{project-relative-file}"`; use `${EXEC.VARS.<name>}` in later Actions. |
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | Remove the old persistence fields. The file-content expression returns the exact UTF-8 String and creates no implicit result file. |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ/SSH | Pass the file-content String directly as HTTP body, MQ payload, or SSH upload payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

File-content paths are relative to the canonical package root. `./` and `../` are allowed only when the canonical target remains inside that root. The v1 contract has no globs or dynamic locators; the target must be a regular strict-UTF-8 file.

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](../runtime-context.md) for META lifecycle and [Load Mode](../execution-modes/load.md) for execution identity and retained evidence paths.

## Debug schema migration

`att-debug/v1.0` is historical; upgrade to `att-debug/v1.1`. Template/Flow may define `vars` to seed `EXEC.VARS`; Tool targets do not support `vars`. See [Debug](../execution-modes/debug.md) for current input and argument rules.

## Global configuration migration

Old `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` fields are not part of the active global contract. See [Configuration](../configuration.md) for current fields.

## Environment profile migration

When migrating complete-config packages, preserve descriptors and Actions, move common settings to `config/config.yaml`, move descriptor lists to `environments.<NAME>`, and select with `--config config/config.yaml --env <NAME>`. See [Configuration](../configuration.md) for the current contract.
