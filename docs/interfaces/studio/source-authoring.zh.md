<!-- att-doc-status: current release=4.4.1 -->

# 在 Studio 編輯及發布來源

本指南說明如何在 ATT Studio 編輯 Package Resource 或共用 Runtime Config，並透過 Server 暫存候選版本、驗證、檢閱及發布。

## 開始前

- 登入擁有該 Package 或 Runtime Config 的 ATT Server。
- 編輯 Package source 時，使用 active `USER` account；login ID 必須與已登錄 Package ID 相同，或列於該 Package 的 trusted registry `editors`。`default` Package 不可編輯。
- 編輯共用 Runtime Config 時，使用 `ADMIN` 帳戶。Server 必須啟用 `server.centralConfigurationEditingEnabled: true`；變更此部署設定需要重啟 Server。
- 將相關 source 檔案放在所選 Package。Global Runtime Config 管理共用 Data Source connection 及 security policy；每個 Package 擁有自己的 Metric Sources、Groups 及 Profiles。
- 執行 Worker acceptance 時，使用支援的隔離環境：Linux 必須在 `PATH` 提供 `bwrap` 並啟用 user namespace，或 macOS 必須提供 `/usr/bin/sandbox-exec`。若無法建立隔離邊界，Worker execution 會 fail closed；詳見 [source change 與 Worker isolation contract](../../system-design/source-change-contracts.md)。

## 使用 source workflow

1. 在 **Explorer** 開啟 Package，再選擇 executable resource 或 supporting source。Supporting sources 包含 Package Manifest、Testdata、File catalog 及 Package SUT Metrics。
2. 開啟要修改的 source。安全檢視為唯讀。只有 Server 回傳完整、未遮蔽的 source bytes，並確認你有編輯權限時，才會顯示 authoring workspace。
3. 選擇 **Edit** 或 **Create**，然後修改內容。若有相關檔案，請先將每個操作加入同一候選版本，再進行暫存。
4. 選擇 **Stage candidate** 或 **Save Draft**。暫存會建立私人 Change Set，不會修改已發布檔案。
5. 選擇 **Validate** 驗證候選版本。這與 **Validate Package** 不同；後者檢查已發布的 Package，不會驗證或發布你的 draft。
6. 檢查所有變更檔案、反向關聯及自動產生檔案。編輯共用 Runtime Config 時，亦要檢查目前 Config Impact report 及受影響的 Package、Environment 和 Schedule。
7. 選擇 **Review changed files**，並只在候選版本與 Impact report 仍為最新時選擇 **Publish**。若系統要求 ADMIN acknowledgement，請綁定目前的 report 後再發布。
8. 檢查發布結果。Runtime Config 必須確認 Active revision 與 Published revision 相同，且 activation state 為 `ACTIVE`，才可開始新的工作。

Server 負責權限、validation、revision 檢查及 publication。`Published` 表示 Publish 已提交的 source revision；`Active` 表示 Runtime Config activation 已核實的 revision。`auditConfirmed` 只表示 publication audit 是否已持久記錄，不能證明已 activation。若編輯期間 source 已變更，Studio 會保留本機文字，但不允許以舊 revision 暫存。複製仍需保留的修改、重新載入最新 source，再手動套用。Studio 不會靜默 rebase 或合併整份檔案。

## 編輯 Package Manifest

1. 在 **Explorer** 開啟 **Supporting sources**，再選擇目前 Package 的 **Package manifest**。
2. 使用 **Effective** 檢查解析後的 Package 設定，使用 **Compare with Global** 比較 Package 與 Global 的值，並使用 **Declared** 檢視 Manifest 宣告值。選擇 **Source** 檢視或編輯 `package.yaml`；只有 Server 提供完整且已授權的 source，並允許此帳戶編輯 Package source 時，才可編輯。
3. 修改 YAML，再選擇 **Create Manifest draft**。這會暫存私人候選版本，不會修改已發布的 Package。
4. 依序選擇 **Validate Manifest draft** 及 **Review validated Manifest**。在檢閱面板比較已發布來源與候選版本，並檢查變更路徑及 Manifest 基底 revision。
5. 選擇 **Publish reviewed Manifest** 並確認。新 Manifest 會用於之後的 Package 設定解析。此流程發布 Package 擁有的設定；共用 Runtime Config 使用獨立的 ADMIN workflow。
6. 載入 Manifest history 以檢查歷史 revision。**Stage rollback** 會以保留的 revision 建立新 draft；必須驗證、檢閱並發布後才會成為目前版本。發布後，可在 **Published Manifest revision (advanced)** 檢視 revision digest。

若發布回傳 `409`，表示候選版本建立後 source 已變更。捨棄過期 draft、重新載入 Manifest，並將修改套用至新的候選版本。

例如 Alice 與 Bob 可同時以共享 Package 的同一 revision 暫存不同修改。若 Alice 先發布，Bob 的舊 draft 在發布時會回傳 `409`。Bob 應捨棄舊 draft，載入目前 source 及 history，將預期修改整合至最新 revision，再建立、驗證、檢閱及發布新候選版本。

## 直接修改檔案與已發布狀態

Package 檔案與 central Runtime Config 目錄使用不同 policy。直接修改 Package 檔案可能會匯入成新的 Package source revision，並令現有 draft 過期。Central Config 初次登錄後，其設定目錄只作 deployment mirror：直接修改不會匯入成 revision，也不會 activation。Server 會繼續使用最後一個 immutable Config revision，並回報 `FAILED` 及 `ATT-CONFIG-EXTERNAL-SOURCE-DRIFT`；mirror 不一致時會暫停新 Job admission。已接納的 Job 仍使用原先 pinned source。ADMIN 仍可在 Studio 載入已核實的 source，並以該 revision 為基礎建立 draft。只有完成 Validate、Review 及最新 Impact approval 的 managed publication 才會將已檢閱 revision export 至 mirror 並清除 drift。不要把檔案複製結果標示為 Published 或 Active。

若要復原外部 Config drift，先保留部署檔案供調查，在 Studio 重新載入已核實 source、暫存預期結果，並完成 Validate → Review → Impact → Publish。Draft 以已核實 revision 為基礎；如需保留外部變更，必須明確納入並檢閱。Activation 失敗或 unavailable 不代表 Published revision 已 Active：檢查 `activationState`、`publishedRevision`、`activeRevision` 及 `activationFailureCode`，保持新 Job 暫停，並依 Server 管理員的 activation 復原程序處理，再以目前已核實 revision 重試。不要手動修改或刪除 Server 管理的 revision store。Source revision 及 publication 不要求使用 Git；若團隊有需要，可另行維持 Git workflow。

## 同時編輯 Template 或 Flow 及其參照 File

1. 在 Package 的 **File catalog** 以路徑、resource 或 Action ID 搜尋。目錄搜尋 Server 按 revision 建立的 Package index，不是瀏覽器的部分掃描結果。
2. 檢查 coverage 狀態。若為 `PARTIAL`，部分 reference 可能未列出；搜尋結果沒有某個檔案，不代表該檔案未被使用。
3. 開啟列出的 resource 或 Action call site，檢查安全 source。只有 Server 標示 File 可用並提供授權 source reference 時，才可開啟 File。
4. 將 Template 或 Flow 修改與 File 變更加入同一候選版本。使用目前 source 的操作，再加入相關檔案，最後一起暫存。
5. 驗證並檢查完整變更檔案清單及反向關聯，再發布單一候選版本。

File catalog 按 canonical Package-relative path 合併 references，並顯示可用狀態及 call site，不會為每個 Action 建立 File 副本。檢視期間不會評估 `${...}` 或 `#{...}` expression。

## 上傳 Testcase workbook 並產生 snapshot

1. 為所選 Package-relative XML 路徑開啟 Testcase authoring workspace。
2. 選擇 **Create new workbook** 或 **Replace existing workbook**，並選擇對應的 sidecar 操作。
3. 一起上傳 workbook 與 YAML sidecar。Studio 會將兩者暫存於同一私人 Change Set。
4. 選擇 **Generate Testcase snapshot**。XML snapshot 會產生至 draft；單純檢視或上傳不會自動產生 snapshot。
5. 驗證 draft、檢查 workbook、sidecar 及產生的 XML 變更，然後發布。

若 snapshot generation 或 validation 失敗，請先修正 source 再重試。Publish 成功前，已發布的 Package 不會改變。

## 建立 Testdata

1. 在所選 Package 及 Environment 開啟 **Testdata**，再選擇 **Create Testdata descriptor**。
2. 輸入唯一的 Testdata ID。選擇 literal JSON records 或 generated sequence，再選擇記錄選取及耗盡 policy。
3. 選擇 **Prepare atomic candidate**。Studio 會一併準備 `testdata/<ID>.yaml` 及 Package Manifest inventory 變更。
4. 在 source workspace 檢查產生的 descriptor 及 Manifest 操作，再一起 Stage、Validate、Review 及 Publish 兩個檔案。

引導式建立工具要求可編輯的 `att-package/v2` Manifest。若 Package 還需要其他 inventory 修改，請使用進階 Manifest editor。

## 編輯或刪除 Testdata

1. 在所選 Package 及 Environment 的 Testdata catalog 選擇描述檔。
2. 具備 Package source authoring 權限時，可在描述檔的 source workspace 編輯、移動或刪除檔案。編輯內容會透過共用 Change Set 工作流程暫存。
3. 移動或刪除檔案時，請在同一 candidate 加入對應的 Package Manifest inventory 更新及受影響的 LOAD Scenario 參照。使用 **Add a related file** 將這些 source 一起加入。
4. Publish 前驗證並檢查完整 candidate，並確保 Base 及 Environment inventory 與保留的檔案一致。

描述檔編輯器不會自動修改 Manifest 或 Scenario 參照。Publish 前請檢查所有受影響的 source。

## 編輯 SUT Metrics

在所選 Package 的 supporting sources 開啟 **SUT Metrics**，編輯該 Package 擁有的 Metric Sources、Groups 及 Profiles。Package Manifest 會在 `load/sut-metrics/` 下登錄這些檔案。

共用 Runtime Config 管理 Data Source connection 及其 security policy。Package author 不會發布到全域 Runtime Config。憑證應保存在 Server 設定的 secret provider；不要貼入 Package 的 metric 定義。

## 發布共用 Runtime Config

1. 開啟 **Shared Configuration**，選擇 **Source**。只有具備授權的 `ADMIN`，且部署編輯開關已啟用，才能讀取或編輯完整 source。
2. 暫存 Runtime Config 變更，並完成 **Validate** 及 **Review changed files / Impact**。
3. 確認 Impact report 為 `AVAILABLE`、coverage 為 `COMPLETE`，且 decision 不是 `BLOCKED`。檢查每一頁受影響的 Package、Environment 及 Schedule。
4. 若 Studio 要求 acknowledgement，請確認目前 report 後再選擇 **Publish**。
5. 確認 response 的 `activationState` 為 `ACTIVE`，且 `publishedRevision` 等於 `activeRevision`。`auditConfirmed` 不代表 activation 成功。若 `activationState` 為 `FAILED`、`PENDING` 或 `UNAVAILABLE`，依 `activationFailureCode` 復原；恢復已核實狀態前不要開始新工作。

Server 會拒絕不適用於線上發布的欄位。Deployment、bootstrap、security 及其他 Server 設定仍由部署設定管理，並可能需要重啟。

## 檢查歷史並暫存還原

1. 開啟具備授權的 source，選擇 **History / Restore**。
2. 選取保留的 transaction，檢查變更檔案。Server 可能隱藏受保護的 source 內容，但仍提供安全的路徑及變更中繼資料。
3. 選擇 **Stage restore as draft**。Studio 會以保留的 snapshot 建立新候選版本，不會直接回退 active source。
4. 檢查完整候選版本、執行 validation，然後完成一般 review 及 publish。Central Config restore 也必須取得最新 Impact report 及 activation approval。Diff 檔案會分頁載入；選擇 **Load more changed files** 直到看到完整清單。若 restore 超過 Server 的 32 個檔案操作 Change Set 上限，request 會被拒絕且不會部分發布；請將所需檔案分成較小的 draft 暫存。

## Source snapshot 儲存及保留

Server 會在每個已登錄的 Package 或 central Config root 旁建立私人 sibling directory，儲存不可變 source revision。名稱格式為 `.<source-root-name>.att-source-revisions-<identity>/`，內含 active pointer、保留的 `revisions/` 及 `executions/` 執行副本。此目錄應留在 source root 所屬的管理 volume，納入容量監測及備份；不要手動複製、修改或刪除其中項目。

每個 source scope 最多保留最新 500 筆已提交 transaction。超出歷史保留期而且沒有引用的 revision 及 execution copy 會被清除；active revision 及 durable Job 或 Schedule source lease 引用的 revision 即使歷史紀錄過期仍會保留。500 筆是紀錄數量上限，不是位元組 quota。Package snapshot 的 source tree 上限為 2 GiB、50,000 個檔案；已核准 central Config closure 上限為 512 MiB、20,000 個檔案；revision capture 單一檔案上限為 64 MiB。新增線上修改的限制較低：每個檔案 4 MiB、每個 Change Set 8 MiB。

目前沒有針對所有保留或 pinned snapshot 的總磁碟 quota。請監測 source volume 可用空間及 ADMIN `/metrics` 的 source-history retention 狀態。清理失敗累計增加、清理超過預期週期未成功，或可用空間接近部署預留值時應發出告警。磁碟已滿時，revision capture 或 publication 會 fail closed；先恢復容量並等待 Server recovery/cleanup，再重試。不可刪除 pinned revision 以騰出空間。

## 從錯誤中復原

| 狀態 | 處理方式 |
|---|---|
| `401` | 重新登入，再重新載入 source。 |
| `403` | 請 Package owner 或 assigned editor 確認 registry assignment，或請 Server 管理員確認目前角色及 source-editing policy。 |
| `409` | 載入最新 source，比較保留的 draft 或本機文字，再暫存新的候選版本。 |
| `413` | 將 source 或 upload 縮減至 Server 設定的大小限制內。 |
| `422` | 閱讀 validation diagnostics，修正指定 source 後再次驗證。 |
| Impact 無法使用、coverage 不完整或 decision 被阻擋 | 不要發布 central Config。請 Server 管理員恢復完整 Impact coverage 或處理阻擋原因。 |

資源檢視及執行方式請參閱 [Explorer 與 functional execution](explorer-run.zh.md)。部署開關及 source ownership 規則請參閱 [Server configuration](../../operations/server-configuration.zh.md)。Shared Package editors 的已部署多角色 operator 矩陣見 [Package-scoped shared editors 驗收](../../operations/shared-editors-acceptance.zh.md)。
