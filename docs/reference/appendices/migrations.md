### 14.3 Migration Notes

The current Reference describes ATT by product concept rather than release chronology. Release-by-release changes remain in `CHANGELOG.md` and `docs/history/`.

Key current migrations are:

- prefer `EXEC` / `META` over legacy Context aliases;
- use `output.result` / `EXEC.ACTIONS.<id>.output.result` and the common evidence/attempt contract;
- treat Tool, DBHelper and MQHelper as peer resources;
- use environment profiles when DB/MQ/SSHHelper bindings vary;
- migrate physical group SSH to `ssh: {helper: <id>}` with `att-tool-group/v2.7` when logical multi-instance routing is needed;
- treat Run, Debug and Load as peer execution modes.

The auditable disposition of the pre-#42 monolithic manual is recorded in `docs/reference-migration-map.md`.
