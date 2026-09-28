### 14.3 遷移說明

Current Reference 依產品概念描述 ATT，不再按 release chronology 組織。逐 release 變更仍保留在 `CHANGELOG.md` 與 `docs/history/`。

目前主要 migration：

- 新 authoring 優先使用 `EXEC` / `META`，而非 legacy Context alias；
- 使用 `output.result` / `EXEC.ACTIONS.<id>.output.result` 及 common evidence/attempt contract；
- 把 Tool、DBHelper、MQHelper、HTTPHelper 視為 peer resource；
- 若只改 DB/MQ/SSH/HTTPHelper binding，使用 environment profile；
- 需要連線池與型別化 HTTP metadata 時，以固定的 `http.<id>.<method>` Action 取代常見 curl 呼叫；既有 curl Tool 仍然有效；
- 舊版 descriptor 使用新欄位時，依驗證診斷更新 `schemaVersion` 與必要欄位後再驗證；歷史 schema 位於 `schemas/history/`；
- command Tool 將 `output: txt|json|yaml|xml` 遷移為必需的 Tool-level `result: {format: text|json|yaml|xml}`；Action-level `result.format` 現在只控制序列化，`raw` 不是共用結果格式；
- 使用 `att-config/v2.9`、`att-tool-group/v2.8`、`att-template/v3.2`／`att-flow/v3.2`；舊版已登記 schema 保留於 `schemas/history/`，package catalog 驗證會檢查所有資源；
- HTTPHelper 按 response `Content-Type` 解析原生結果；公開 header key 統一小寫，Action 序列化不會改變型別化結果；
- MQ 可在 `message.requestQueue` 設 send/request 預設，在 `message.replyQueue` 設 receive/request 預設；明確 call argument 優先於所選 instance 繼承設定；
- 非預期 internal exception 會在 Case log 記錄有界且遮蔽 secret 的 stack detail；預期 transport／validation error 維持精簡；
- 需要邏輯多實例路由時，以 `att-tool-group/v2.7` 的 `ssh: {helper: <id>}` 取代實體 group SSH；
- 把 Run、Debug、Load 視為 peer execution mode。

Pre-#42 monolithic manual 的可審核 disposition 記錄在 `docs/reference-migration-map.md`。
