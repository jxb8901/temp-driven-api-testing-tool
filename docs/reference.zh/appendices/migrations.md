### 14.3 遷移說明

Current Reference 依產品概念描述 ATT，不再按 release chronology 組織。逐 release 變更仍保留在 `CHANGELOG.md` 與 `docs/history/`。

目前主要 migration：

- 新 authoring 優先使用 `EXEC` / `META`，而非 legacy Context alias；
- 使用 `output.result` / `EXEC.ACTIONS.<id>.output.result` 及 common evidence/attempt contract；
- 把 Tool、DBHelper、MQHelper 視為 peer resource；
- 若只改 typed DB/MQ binding，使用 environment profile；
- 把 Run、Debug、Load 視為 peer execution mode。

Pre-#42 monolithic manual 的可審核 disposition 記錄在 `docs/reference-migration-map.md`。
