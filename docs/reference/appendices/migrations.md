### 14.3 Migration Notes

The current Reference describes ATT by product concept rather than release chronology. Release-by-release changes remain in `CHANGELOG.md` and `docs/history/`.

Key current migrations are:

- prefer `EXEC` / `META` over legacy Context aliases;
- use `output.result` / `EXEC.ACTIONS.<id>.output.result` and the common evidence/attempt contract;
- treat Tool, DBHelper, MQHelper and HTTPHelper as peer resources;
- use environment profiles when DB/MQ/SSH/HTTPHelper bindings vary;
- move common curl invocations to a logical `http.<id>.<method>` Action when pooled transport and typed response metadata are useful; existing curl Tools remain valid;
- when an older descriptor uses newer fields, follow the validation diagnostic, migrate `schemaVersion` and any named legacy fields, then validate again; historical schema definitions live in `schemas/history/`;
- migrate command Tool `output: txt|json|yaml|xml` to required Tool-level `result: {format: text|json|yaml|xml}`; Action-level `result.format` now controls serialization only, and `raw` is not a common result format;
- use `att-config/v2.9`, `att-tool-group/v2.8`, and `att-template/v3.2` / `att-flow/v3.2`; prior registered schema resources remain in `schemas/history/` and are validated from the package catalog;
- configure HTTPHelper results from response `Content-Type`; public header keys are lowercase, and Action serialization no longer changes the native typed result;
- set optional MQ defaults in `message.requestQueue` for send/request and `message.replyQueue` for receive/request; explicit call arguments override the selected instance's inherited settings;
- unexpected internal exceptions now include bounded, secret-sanitized stack detail in Case logs; expected transport and validation errors remain concise;
- migrate physical group SSH to `ssh: {helper: <id>}` with `att-tool-group/v2.7` when logical multi-instance routing is needed;
- treat Run, Debug and Load as peer execution modes.

The auditable disposition of the pre-#42 monolithic manual is recorded in `docs/reference-migration-map.md`.
