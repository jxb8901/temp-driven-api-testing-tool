<!-- att-doc-status: current release=4.2.8 -->

# Studio 管理

![ATT Studio Configuration 設定畫面](../../system-design/ui-shell-baseline/configuration-1440-light.png)

*設定靜態 UI shell 基線截圖，使用示例資料；圖中版本標籤屬於截圖基線。*

## 帳戶管理

Local mode 中，每位已登入的本地用戶均可在目前用戶選單使用 **Change password**。表單需要目前密碼、新密碼及確認。變更成功後會撤銷目前 session，並將用戶轉回登入頁面及顯示成功訊息。密碼只會留在目前表單，不會寫入 local storage、session storage、URL 或應用程式日誌。

Local `ADMIN` 可前往 **Settings → User Management**，以登入 ID、顯示名稱、角色及狀態搜尋或排序安全帳戶列表。管理員可建立 `ADMIN` 或 `USER`、啟用或停用帳戶，以及重設密碼。建立帳戶及密碼表單都要求確認欄位相符，並遵守 Server 的 12 至 1024 字元密碼政策。系統不會顯示現有密碼。停用用戶或重設密碼前需要確認，並會撤銷該用戶的 session。即使多個停用請求同時執行，Server 仍會阻止停用最後一個啟用中的 `ADMIN`。若所有管理員都無法登入，請使用 [Server 部署指南](../../operations/security.zh.md) 所述的離線復原程序；復原只會更新現有 `ADMIN`，不會重新開啟 bootstrap。

只有已驗證的 local `ADMIN` 可檢視帳戶列表及控制項。其他本地用戶看不到帳戶資料或管理功能。Container mode 的 Settings 會說明帳戶由 Tomcat 或設定的 SSO provider 管理；ATT 不會把外部帳戶顯示成由 ATT 管理。Settings 可使用英文或繁體中文、跟隨瀏覽器語言，或在此瀏覽器保存語言選擇；存取控制及密碼政策仍由 Server 執行。

## Monitoring

**Monitoring** 為啟用中的 local `USER`／`ADMIN`，以及獲授權的 container user／administrator 提供唯讀 `att-monitoring/v1` snapshot。頁面使用 allowlist endpoint `/api/v1/monitoring`，與只供 administrator 使用的 raw `/api/v1/metrics` 分開。Snapshot 涵蓋 process／JVM 使用量、Worker admission、HTTP／SSE 活動、journal compaction、storage、inspection／execution pool、schedule 及 Job 狀態總數。頁面會標明未支援量度、累計計數，並從有界 session history 計算區間速率及壓縮失敗增量。作用中 Job 的 compaction failure 仍以 active aggregate 提供；`compactionFailuresSinceStart` 是此 Server runtime 的累計值，因此 Job 離開記憶體後，interval alert 仍維持單調。目前不會量度 process RSS 或 SSE 重連／錯誤數；未能 aggregate 的 archive bytes 會標示為無資料。Per-Job journal 詳情只使用作用中或已快取資料；沒有量度時會提供通往已授權 Events 檢視的連結。Response 不會提供 secret、filesystem path、raw event 或 per-Job payload。

頁面預設每 15 秒更新，並可選擇 5、15、30 或 60 秒。瀏覽器分頁隱藏時會暫停輪詢，不會重疊請求；歷史只保存在目前頁面的 session 記憶體，最多 96 個 snapshot。頁面顯示資料新鮮度及錯誤，並提供 threshold 與近期變化警示。Per-Job journal 面板只有在使用者有權查看該 Job 時才會回傳有界的 journal summary；system administrator 可查看任何現存 Job 的 journal summary。
