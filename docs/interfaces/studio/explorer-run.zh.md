<!-- att-doc-status: current release=4.2.7 -->

# Explorer 與 functional execution

![ATT Studio Explorer 與 Debug 工作區，顯示已選取的 Resource](../../system-design/ui-shell-baseline/explorer-debug-1440-light.png)

*Explorer 靜態 UI shell 基線截圖，使用示例資料；圖中版本標籤屬於截圖基線。*

Package Resource Explorer 會按 package-scoped 邏輯資源 ID 列出已設定的 Case、Template、Flow 及 Tool，並支援類型與文字篩選、跨所有符合項目排序後再分頁、加密 cursor、資源詳細資料及安全關聯。在預設 `safe-package-files` policy 下，已授權 source 所連結的合資格 package-local Tool scripts 及安全 `&{...}` targets 無須逐檔設定即可開啟；Template/Flow YAML 仍以淨化投影回傳。`allowlist-only` 使用完全相同的 package-relative `server.inspection.safeTextSources` 路徑。External scripts 必須位於已設定的 external root 並列入每 package canonical 精確 allowlist，回應會隱藏 filesystem path。Case 詳細資料會顯示身份及 Stage/Template 中繼資料，並隱藏 workbook 業務值。反向關聯最多內嵌 100 筆，並同時回傳準確數量及 `referencedByHasMore`。Explorer 不會瀏覽任意檔案、不會執行 Tool，也不會修改 package 內容。若分頁期間 package 或排序/篩選條件有變更，Server 會拒絕過期 cursor，UI 可重新整理清單。

Debug 只能從已選取的 Template、Flow 或 Tool 詳細資料開啟。表單會載入該目標選填 `debug.yaml` 的安全預設值；若檔案不存在，Server 會提供有效的空白 `att-debug/v1.2` 預設值。Package 撰寫的值預設會隱藏。保留 `$attDebugKeepDefault` 標記即可沿用隱藏值；若要覆寫，請改成明確值。可以用 JSON object 編輯巢狀值、還原已載入的預設值、驗證，並在開始 Debug 前檢視已遮蔽的 YAML 預覽。所有目標都使用 `inputs`；Template 與 Flow 亦使用 `vars`，Tool 則使用 `arguments` 並不接受 `vars`。Server 會將記憶體中的 draft 綁定至已驗證 Principal 及目前 package revision。Draft 於 10 分鐘後過期，Server 重啟後失效。編輯或提交都不會寫入 package 檔案。現有以路徑提交的 Debug API 仍供相容用戶端使用。

## Package Explorer 與 Debug

React Explorer 以三欄工作區呈現套件資源、所選定義及資源操作。選擇 Case、Template、Flow 或 Tool 即可開啟詳細資料。`#/packages/{packageId}/resources/{type}/{resourceId}` hash route 會在重新載入時還原所選資源，並支援瀏覽器導覽。Definition、Source 及 Dependencies 分頁可使用方向鍵、Home 及 End 鍵盤操作。

Source 分頁只載入 Server 提供的安全 source projection。畫面最多顯示前 200,000 個字元及 12,000 行，並只渲染視窗內的行；source 內容會以純文字呈現。Package 更新後可重新整理。**Copy displayed source** 只會複製上述上限內已顯示的文字。空白或不可用的 projection 會明確顯示；驗證及權限錯誤不會包含原始回應細節。

可從資源 Inspector 執行 Debug 或 Case。Sidecar 提供的 scalar 欄位會使用文字、數字、布林值及 null 等型別控制項。巢狀值及完整 business input 可在 **Advanced JSON** 編輯。提交驗證前，請修正無效 JSON 或 scalar 值。**Reset defaults** 會還原從 sidecar 載入的值。若 Server 將 sidecar 值標示為 redacted，表單會顯示 keep-default 標記，不會揭露隱藏值。保留標記即可沿用 package 值；如需覆寫，請選擇型別並輸入明確值。開始 Debug 前請先驗證並檢視安全預覽。Server 仍是驗證及存取檢查的權威來源。

當 Debug 編輯尚未儲存時，切換 package、resource 或 environment 會先詢問是否捨棄變更。同一保護也涵蓋 hash route 及瀏覽器上一頁／下一頁。選填 job label 會隨執行請求作為 Principal label 傳送；它不能取代 Tomcat 驗證。若 session 過期，請重新登入後重試。在 Server 允許請求時，匿名部署亦可使用此 label。
