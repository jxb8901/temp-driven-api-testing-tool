<!-- att-doc-status: current release=4.2.5 -->

# ATT Server 安全與身份

## 驗證與身份

`server.auth.mode` 選擇驗證提供者。為保留現有部署，預設為 `container`。設為 `local` 後，ATT 會管理本地帳戶。Local mode 必須同時設定 `authenticationRequired: true`；若與舊式匿名設定 `false` 一起使用，Server 會拒絕啟動。

在 `container` mode 中，Tomcat 驗證請求，ATT 使用 Servlet Principal。現有 BASIC、FORM 及 SSO 部署會保留由 container 管理的登入流程。Health 及 version 維持公開。舊式 `server.authenticationRequired: false` 只適用於 container mode，會讓 UI 及 API 共用匿名存取，只應用於隔離且可信的網絡。所有匿名 caller 共用 drafts、jobs 及 artifacts。啟用時 ATT 會在啟動日誌發出警告。

在 `local` mode 中，未登入的瀏覽器開啟 `/ui/` 時會轉至 ATT 登入頁；REST API 則回傳結構化 JSON `401`。首次管理員帳戶使用 `dataDir/bootstrap-admin.token` 內的隨機一次性 token 建立。請限制 `dataDir` 只供 Server 作業系統帳戶存取，在登入頁輸入 token 完成建立，之後移除對 token 檔案的存取。初始化時會先寫入權限受限的暫存檔，再以原子 rename 安裝 token；啟動時只有在尚無帳戶且 bootstrap 未被認領時才會修復無效 token。Bootstrap 一經認領便不會重新開啟。系統沒有預設帳戶或密碼。除 loopback 開發外，所有包含本地密碼的請求（登入、bootstrap、建立帳戶、重設及自行變更密碼）都必須使用 HTTPS。Tomcat 只會透過已設定的可信任 proxy 判斷 proxy 終止的 HTTPS；不可信任的 forwarded header 不會建立 HTTPS 狀態。本地密碼以每位用戶獨立 salt 的 PBKDF2-HMAC-SHA-256 雜湊保存。首個版本支援 `ADMIN` 及 `USER`；所有 active `USER` 共用一般 Server 操作，`ADMIN` 僅有系統及帳戶管理權限。

Local session 使用不透明的 server-side identifier，登入時會輪替，閒置 30 分鐘或累計 12 小時後失效。登出、密碼變更或重設、停用帳戶都會撤銷 session。Server 重啟後，未到期或未撤銷的 session 仍有效。Cookie 設為 HttpOnly、SameSite=Lax，Path 限於部署 context；Tomcat 判斷請求使用 HTTPS 時亦會設 Secure。啟用 TLS termination 前，請先設定可信任的反向代理，讓 Tomcat 識別外部 HTTPS scheme。所有使用 cookie 驗證的狀態變更請求，以及由瀏覽器發出的 Basic-authenticated 狀態變更請求，必須在 `X-ATT-CSRF` 提交 CSRF token；登入及一次性 bootstrap endpoint 則要求 same-or-trusted-origin JSON。設為 `security.checkCrossOrigin: false` 只會放寬 Origin 相同來源檢查，不會略過 cookie CSRF token，也不會略過登入/bootstrap 的 same-or-trusted-origin JSON 要求，亦不會啟用 wildcard CORS。同一 login ID 及 client address 在 10 分鐘內失敗 8 次，或同一 client address 以不同 login ID 失敗 32 次後，登入會被限速。Bootstrap 每個 client address 在 10 分鐘內最多可輸入錯誤 token 8 次。自行變更密碼的 current-password 驗證使用相同限速及有界 hash semaphore。這些記憶體限速只適用於單一 Server process；多個 instance 請在共用的 edge 層設定 rate limit。登入回應不會區分不存在、已停用或密碼錯誤的帳戶。登入成功及失敗都會寫入 audit，並不記錄憑證。密碼以 310,000 次 PBKDF2-HMAC-SHA-256 計算雜湊；成功登入時會升級計算次數較低的舊 hash。

`GET /api/v1/auth/me` 回傳安全的目前用戶資料及瀏覽器所需的 CSRF token。`POST /api/v1/auth/login` 及 `/api/v1/auth/logout` 管理 session。使用 local account 的 CLI 及 REST client 亦可透過 HTTPS Basic authentication 存取。管理員可使用 `GET`、`POST /api/v1/auth/users`、`POST /api/v1/auth/users/{loginId}/password`、`POST /api/v1/auth/users/{loginId}/enable` 及 `DELETE /api/v1/auth/users/{loginId}` 管理帳戶。`POST /api/v1/auth/password` 會先核對目前密碼，再變更自己的密碼。API 回應不會包含密碼雜湊、session identifier 或密碼。

在 Studio 中，local `ADMIN` 可於 **Settings → User Management** 管理帳戶。列表只顯示登入 ID、顯示名稱、角色及啟用狀態。建立、啟用／停用及重設密碼都需要明確確認；停用或重設密碼會撤銷該用戶的 session。即使來自不同 Server runtime 的請求同時執行，Server 仍會拒絕停用最後一個啟用中的 `ADMIN`。所有 local 用戶均可在目前用戶選單使用 **Change password**。密碼變更成功後會撤銷目前 session，並返回登入頁面。密碼只會在表單開啟時暫存，不會寫入瀏覽器儲存空間或應用程式日誌。Container mode 的 Settings 會說明帳戶由 Tomcat 或設定的 SSO provider 管理。

### 復原失去存取權的本地管理員

一次性 bootstrap 不會重新開啟，也不是帳戶復原方式。若現有 `ADMIN` 忘記密碼或已被停用，請使用離線復原工具。此操作不能建立帳戶；若帳戶不存在或不是 `ADMIN`，工具會拒絕執行。

1. 停止所有使用此 `server.dataDir` 的 Tomcat process，並等待完全結束。以下命令須以 Server 作業系統帳戶執行，並可寫入現有的 `dataDir`。
2. 設定 `ATT_SERVER_WAR` 為部署中的 `att-server-4.1.4.war`、`CATALINA_HOME` 為 Tomcat 安裝目錄，以及 `JAVA_HOME` 為 Java 17 或更新版本。將 WAR 解壓至權限受限的暫存目錄，再以套件內 library 及 Tomcat Servlet API 啟動工具：

   ```sh
   umask 077
   recovery_dir="$(mktemp -d)"
   trap 'rm -rf "$recovery_dir"' EXIT
   (
     cd "$recovery_dir"
     "$JAVA_HOME/bin/jar" xf "$ATT_SERVER_WAR"
     "$JAVA_HOME/bin/java" -Datt.server.config=/absolute/path/to/att-server.yaml \
       -cp "WEB-INF/lib/*:$CATALINA_HOME/lib/*" att.server.AuthRecoveryTool
   )
   ```

3. 按互動提示輸入現有 ADMIN 登入 ID、輸入 `RECOVER ADMIN` 確認，然後輸入新密碼兩次。密碼輸入時不會顯示，也不接受作為命令列參數。
4. 重新啟動 Tomcat，並使用新密碼登入。

工具只會更新所選的現有 `ADMIN` row；若帳戶已停用，會重新啟用，並更新密碼雜湊及 CSRF token、撤銷該帳戶所有 session，以及記錄 `ADMIN_RECOVERY` audit event。工具不會修改 bootstrap claim 或建立 bootstrap token。若找不到現有資料庫、資料庫無法開啟，或指定帳戶不是 `ADMIN`，復原會失敗且不會建立帳戶。請保護備份、暫存解壓目錄及 `server.dataDir`，並視為 Server 憑證處理。

Local mode 的所有 active USER 均可存取同一操作範圍內的 package、工作狀態、結果、SSE、已核准 artifacts 及取消操作；工作建立者只作 audit 記錄，不是 owner-only 存取限制。Draft 仍只供建立者使用。ADMIN-only 身分不會自動取得 USER 操作權限；container mode 同樣按操作角色授權。

匿名 job submission 可在最外層 JSON 加入選填的 `principal` label，只供顯示及 audit 使用。Server 會 trim 並以 Unicode 正規化 label、驗證長度及字元，然後在既有 job/audit `principal` 欄位加上 `(anonym)` 後綴，例如 `Bob(anonym)`。省略時維持既有值 `anonymous`；caller 不可自行提交保留後綴。此 label 由 caller 自行聲稱，不會改變授權、draft 存取、job 可見範圍、artifact 存取、取消或排程。驗證模式永遠使用 Servlet Principal，並忽略提交的 label。此歸屬資料不會傳給 Worker，也不會放進 ATT expression Context；不會新增持久化欄位或回應欄位。

改變狀態的請求必須使用 `application/json`；如請求帶有 `Origin`，必須與請求來源相同。若 TLS 在反向代理終止，請設定 Tomcat `RemoteIpValve`，由代理的 forwarded headers 還原 Servlet scheme、host 及 port。`internalProxies` 只可列出實際代理位址，並確保代理會先移除用戶提交的 `Forwarded`/`X-Forwarded-*` headers，再加入自身的值。請依代理實際使用的 header 名稱及受信任位址調整設定：

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

`RemoteIpValve` 只應信任實際 proxy 位址。Proxy 加入 forwarded headers 前，必須先移除 client 自行提交的值。請將 Tomcat 綁定 loopback 或 private interface，並用 VPN、防火牆或 IP allowlist 保護 proxy。localhost 以外的流量必須使用 HTTPS。Draft 存於記憶體，Server 重啟後會清除。切換驗證 mode 需要重啟；現有 draft 不會轉移到其他身份。Local mode 不會讀取或修改 JSPWiki 帳戶檔案，也不提供外部 LDAP、OIDC、SAML 或共用 SSO。

內建瀏覽器主控台位於 `<context path>/ui/`，使用所選的 container 或 local 驗證 mode。Local mode 會在主控台提供帳戶管理及密碼變更功能。詳見 [ATT Server Web UI](../interfaces/studio/administration.zh.md)。
