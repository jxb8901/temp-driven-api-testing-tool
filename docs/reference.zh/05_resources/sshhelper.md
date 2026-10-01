### 7.6 SSHHelper：邏輯 SSH 目標

#### SSH Resource Helper operation

SSHHelper 也支援在一般 `type: tool` Action 中使用共用 Resource Helper 形式：`ssh.<helperId>.execute`、`ssh.<helperId>.upload` 及 `ssh.<helperId>.download`。Helper ID 是邏輯 ID；native Resource Helper call 使用 `single`、`random` 或 `roundRobin` 選取一個實體 instance。Native Resource Helper call 不支援 `selection.strategy: all`，會在 validation 時拒絕；`all` 只供 command-backed Tool fan-out 使用。這些呼叫會在未建立 SSH connection 前完成驗證，並共用 helper 的並發上限及 identity 遮蔽規則。

```yaml
actions:
  health:
    type: tool
    call: >-
      #{ssh.application.execute(
        command='systemctl is-active example.service',
        stdoutFormat='text',
        timeoutMs=5000
      )}
  uploadRequest:
    type: tool
    call: >-
      #{ssh.application.upload(
        remotePath='/srv/app/request.json',
        payload=${EXEC.ACTIONS.renderRequest.output.result},
        overwrite=true
      )}
  downloadResponse:
    type: tool
    call: >-
      #{ssh.application.download(
        remotePath='/srv/app/response.json',
        localPath='ssh/response.json',
        overwrite=true
      )}
```

`execute` 需要 `command`，可接受 `stdoutFormat: text|json|yaml|xml` 及 `timeoutMs`。`text` 原樣回傳 stdout String；structured format 會把 stdout 解析為原生 Map/List/scalar。Timeout、遠端 non-zero exit 或 parse failure 會回傳不同 category 的 operation error，並保留有界 stderr、exit code、byte count 及 transport evidence。Resource SSH 的 non-zero exit 會令 operation 失敗，與下文 legacy command-backed Tool 的 fan-out 契約不同。

`upload` 需要 `remotePath`，以及 `localPath`／`payload` 二選一。Local file 必須是 package 或目前 Case output 內的 regular non-symlink file。Payload 必須解析為 String 或 byte array；Map/List 會被拒絕，不會隱式序列化。Remote absolute path 可用；upload 的 `overwrite` 預設為 `true`。

`download` 需要 `remotePath` 及 Case-output-relative 的 `localPath`。ATT 先寫入 temporary file，再移入受控 Case output directory；不會解析下載 bytes。Download 的 `overwrite` 預設為 `false`，已有檔案必須明確使用 `overwrite: true`。Typed result 是包含 remote path、保留後 local path 及 byte count 的 transfer summary。

三種 operation 只接受 named arguments。Unknown operation/helper/argument、重複 argument、錯誤 format／timeout、缺少 required field、upload source 衝突及不安全 local path 都會在外部執行前驗證失敗。Runtime evidence 包含 logical helper、選定 instance、host/port、operation、transport、時間及 transfer/command 詳情；command input 和 represented payload 不會複製到 evidence。由 environment 提供的 identity path 仍會遮蔽。

Native Resource Helper call 使用一個 absolute Action deadline，涵蓋 concurrency pool wait、connection 與 channel setup，以及 command 或 SFTP transfer。Per-call `timeoutMs` 或 helper `timeouts.commandTimeoutMs` 只設定 operation limit，不能延長 enclosing Action deadline。`timeouts.connectTimeoutMs` 只在剩餘 deadline 內限制 connection establishment，不會額外增加 operation 時間。`execute`、`upload`、`download` 都遵守此契約。Native `execute` 的 `SSH_TIMEOUT` 與 `SSH_POOL_TIMEOUT` 可使用 Action `retryOn: [TIMEOUT]`；native `upload` 與 `download` 會拒絕 timeout retry，因為重播 transfer 可能重複副作用。SFTP Action 會在 deadline 到達時返回，但 concurrency lease 會由 cleanup worker 持有，直到 transfer worker 與 transport 終止。

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
timeouts: {connectTimeoutMs: 10000, commandTimeoutMs: 60000}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

在 `att-config/v2.10` 的全域或 `environments.<NAME>.sshhelpers` 列出 descriptor 路徑。目前 package 使用 config v2.10 與 Tool Group v2.9。選定環境的清單會整組取代全域清單；省略則繼承。Tool group 所綁定的相同邏輯 ID 必須在每個選定 profile 內存在。SIT 可綁定一臺，UAT 綁定兩臺，Tool／Action 不必修改：

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
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
selection: {strategy: all} # 只供 command-backed Tool fan-out；native ssh.application.* 會拒絕 all
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
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

Action 仍呼叫 `app.status`。先在本機／CI secret environment 把 `APP_SSH_KEY` 設為可讀私鑰的**路徑**，再分別以 `./att.sh validate --config config/config.yaml --env SIT --package` 及 UAT 驗證。完整 `${ENV:NAME}` identityFile reference 在載入時解析；缺失／空值會報錯而不揭露值。Tool group 的 `ssh` 只能是直接目標（`host`、`user`、可選 `port`／`identityFile`）或邏輯目標（`helper`、可選 `selection`），不可混用。Call-backed Tool 可在 primary `type: tool` operation 中呼叫 native SSH Resource Helper。現行 Tool Group schema 為 v2.9。Command-backed Tool 使用 `stdoutFormat`（`text|json|yaml|xml`）設定 stdout parsing；call-backed Tool 保留 native result type。舊 config/group schema 僅供 migration reference。SSH Resource Helper call 只發布 `META.SSHHELPER.id` 與 `.type`；endpoint、user、identity file、credentials 仍保持私有。Resource call 沒有 Action／per-call strategy override；selection 由 helper configuration 決定。

Strategy 優先序：group override，再到 helper 預設。Native Resource Helper call 中，單 instance 不需 strategy（`single`）；多 instance 只支援 `random` 或 `roundRobin`，`all` 會被拒絕，不會靜默選一臺。Command-backed Tool routing 才支援 `all`：`random` 均勻選一臺，`roundRobin` 以 thread-safe 循環計數器選一臺，明確的 `all` 在並發上限內對每臺各執行一次。**Command-backed `all` 會在每臺主機產生副作用**；只用於整組執行均安全的命令。不會隱式 fan-out、跨主機重試或 failover。若作者設定 Action timeout retry，整個 `all` 呼叫會重做，並非只重試某臺。每臺依 Action／Tool／全域 timeout 執行；中斷會取消正在執行的 OpenSSH process 或 Java SSH session。兩種 transport 使用同一組標準化 host/user/port/key。優先 OpenSSH；mwiede/jsch fallback 仍嚴格驗證 host key，限制見 SSH 診斷章。

單主機時解析後的 `output.result` 仍是舊有 scalar／object。Evidence 新增 `sshHelper`、`instance`、`host`、`selectionStrategy`、`selectionSource`（`helper` 或 `toolGroup`）、transport、起訖／持續時間、exit code、輸出及錯誤。只有 command-backed Tool fan-out 使用 `all`；此時 `output.result` 包含 `sshHelper`、有效 `selectionStrategy`、`selectionSource`，以及依 descriptor 順序以 ID 為 key 的 `instances`；每筆有 `instance`、`host`、`port`、`transport`、`startedAt`、`endedAt`、`durationMs`、`status`，在適用時另有 `exitCode`、`stdout`、`stderr`、`rawOutput`、解析後 `output` 或 `error`。命令正常完成時即使 `exitCode` 非零，仍是 `status: PASS`；exit code 是供 Action assertion 判斷的證據，不屬操作失敗。只有執行、輸出解析、取消或 timeout 錯誤才令 operation 失敗，其他主機證據仍會保留。Assertion 可查 `${output.result.instances.app1.exitCode}`、`${output.result.instances.app1.status}` 或 `${output.result.instances.app1.output}`。Evidence 不記錄認證內容或環境提供的私鑰路徑；私鑰應放在 package 外，命令中亦不要放祕密。

單主機及 `all` 執行都會在 argv、transport stderr（包括串流寫入的 Case log 診斷）及 exception evidence 遮蔽環境提供的私鑰路徑。上述不記錄保證適用於 ATT metadata 和 transport 診斷；解析後的業務 stdout 不變，因此命令不可輸出私鑰路徑。

遷移：若一個實體目標已足夠，直接 SSH 可維持原狀。否則把 host/user/port/key 搬到 helper descriptor，在每個環境綁定，將 group 升到 v2.9，以 `ssh: {helper: application}` 取代實體 `ssh`，逐一驗證環境。Action 不需重寫。Inventory discovery、Action 層指定主機、分散式交易、跨主機 failover 與 orchestration 均不在此 schema 範圍。
