<!-- att-doc-status: historical release=4.1.0 issue=176 -->

# Issue #176 rollout checklist

## Issue #176 rollout checklist

1. 備份 Server `dataDir`，並保留目前 WAR 及部署設定以便回復。
2. 保持 Tomcat 驗證啟用，並以 HTTPS 接收公開流量。確認 package root 與 Server data directory 分離，且只有服務帳戶可寫入。
3. 請將 `ATT_HOME` 或 `-Datt.home` 設為版本相符的 Local installation，讓 Worker process 使用 `$ATT_HOME/lib/*`。Tomcat 可直接部署 packed WAR；`workers.libraryDirs` 仍可額外加入外部 JDBC、MQ 或其他 library。透過實際 Tomcat context path 檢查 `/api/v1/health` 及 `/api/v1/version`。
4. 初始維持 `server.inlineLoad.enabled: false`。先用非正式環境 package 檢視 Package 與 Configuration Explorer。只在核准的環境啟用瀏覽器 Load，並設定 workload 數、target、users 或 rate、並行數及完整時間窗口上限。
5. 監察 Worker 與 Load 佇列容量、工作結果、取消及保留的 artifacts。Package 維持唯讀；回復時停用 inline Load，必要時還原前一版 WAR 及設定。

此清單說明部署步驟，不能取代 P6 的安全性、瀏覽器、相容性、WAR 封裝及 hosted CI 驗收證據。
