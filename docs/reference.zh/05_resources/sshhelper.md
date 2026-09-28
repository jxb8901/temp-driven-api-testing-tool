### 5.4 SSHHelper：邏輯 SSH 目標

SSHHelper 讓 command-backed Tool 使用穩定的邏輯應用伺服器 ID，而非在 Tool group 中寫入實體主機。`att-sshhelper/v1.0` YAML descriptor 含 `id`、可選 `name`／`description`、可選 `defaults`（`user`、`port`、`identityFile`）、非空有序 `instances`、可選 `selection.strategy` 和 `fanout.maxConcurrency`（預設 4、範圍 1–256）。每個 instance 需有 `id`／`host`，`user` 必須由 instance 或 defaults 提供。Instance 欄位覆蓋 defaults；port 預設 22，必須在 1–65535。Helper 和 instance ID 符合 `[A-Za-z_][A-Za-z0-9_-]*`，忽略大小寫後不可重複。無效 host/user、未知欄位、重複 ID、缺少 user、不安全路徑和無效 strategy 都會在 SSH 執行前失敗。

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

在 `att-config/v2.9` 的全域或 `environments.<NAME>.sshhelpers` 列出 descriptor 路徑。邏輯 SSH binding 由 config/group v2.7 引入；目前 package 使用 config v2.9 與 Tool Group v2.8。選定環境的清單會整組取代全域清單；省略則繼承。Tool group 所綁定的相同邏輯 ID 必須在每個選定 profile 內存在。SIT 可綁定一台，UAT 綁定兩台，Tool／Action 不必修改：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.9
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.8
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # 可選 group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

Action 仍呼叫 `app.status`。先在本機／CI secret environment 把 `APP_SSH_KEY` 設為可讀私鑰的**路徑**，再分別以 `./att.sh validate --config config/config.yaml --env SIT --package` 及 UAT 驗證。完整 `${ENV:NAME}` identityFile reference 在載入時解析；缺失／空值會報錯而不揭露值。Tool group 的 `ssh` 只能是直接目標（`host`、`user`、可選 `port`／`identityFile`）或邏輯目標（`helper`、可選 `selection`），不可混用。Call-backed Tool 不支援 SSH。既有 inline global SSH 和 v2.6／v2.2 group 仍可讀；邏輯綁定需要 v2.7 或更新版本。目前 Tool Group 使用 v2.8，command-backed Tool 必須設定 `result.format`（`text|json|yaml|xml`）；舊 `output: txt` 遷移為 `result: {format: text}`。Action／per-call 層沒有 strategy override。

Strategy 優先序：group override，再到 helper 預設。單 instance 不需 strategy（`single`）；多 instance 必須指定。`random` 均勻選一台，`roundRobin` 以 thread-safe 循環計數器選一台，明確的 `all` 在並發上限內對每台各執行一次。**`all` 會在每台主機產生副作用**；只用於整組執行均安全的命令。不會隱式 fan-out、跨主機重試或 failover。若作者設定 Action timeout retry，整個 `all` 呼叫會重做，並非只重試某台。每台依 Action／Tool／全域 timeout 執行；中斷會取消正在執行的 OpenSSH process 或 Java SSH session。兩種 transport 使用同一組標準化 host/user/port/key。優先 OpenSSH；mwiede/jsch fallback 仍嚴格驗證 host key，限制見 SSH 診斷章。

單主機時解析後的 `output.result` 仍是舊有 scalar／object。Evidence 新增 `sshHelper`、`instance`、`host`、`selectionStrategy`、`selectionSource`（`helper` 或 `toolGroup`）、transport、起訖／持續時間、exit code、輸出及錯誤。`all` 時 `output.result` 包含 `sshHelper`、有效 `selectionStrategy`、`selectionSource`，以及依 descriptor 順序以 ID 為 key 的 `instances`；每筆有 `instance`、`host`、`port`、`transport`、`startedAt`、`endedAt`、`durationMs`、`status`，在適用時另有 `exitCode`、`stdout`、`stderr`、`rawOutput`、解析後 `output` 或 `error`。命令正常完成時即使 `exitCode` 非零，仍是 `status: PASS`；exit code 是供 Action assertion 判斷的證據，不屬操作失敗。只有執行、輸出解析、取消或 timeout 錯誤才令 operation 失敗，其他主機證據仍會保留。Assertion 可查 `${output.result.instances.app1.exitCode}`、`${output.result.instances.app1.status}` 或 `${output.result.instances.app1.output}`。Evidence 不記錄認證內容或環境提供的私鑰路徑；私鑰應放在 package 外，命令中亦不要放秘密。

單主機及 `all` 執行都會在 argv、transport stderr（包括串流寫入的 Case log 診斷）及 exception evidence 遮蔽環境提供的私鑰路徑。上述不記錄保證適用於 ATT metadata 和 transport 診斷；解析後的業務 stdout 不變，因此命令不可輸出私鑰路徑。

遷移：若一個實體目標已足夠，直接 SSH 可維持原狀。否則把 host/user/port/key 搬到 helper descriptor，在每個環境綁定，將 group 升到 v2.7，以 `ssh: {helper: application}` 取代實體 `ssh`，逐一驗證環境。Action 不需重寫。Inventory discovery、Action 層指定主機、分散式交易、跨主機 failover 與 orchestration 均不在此 schema 範圍。
