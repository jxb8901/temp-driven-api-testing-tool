<!-- att-doc-status: current release=4.4.1 -->

# Package-scoped shared editors 驗收

請在已包含 Issue [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387) shared-editors 建置的 staging Server 上執行此矩陣。Fixture-backed browser smoke 與一般 hosted CI **不能** 視為通過此 gate。

## 準備部署

1. 記錄確切 WAR/建置 head SHA 與 staging 基底 URL。
2. 啟用 `server.packageConfiguration.sourceEditingEnabled: true`，並維持部署既定的 `server.centralConfigurationEditingEnabled`。
3. 登錄例如 `alice`（僅 owner）與 `fpp-common`（`editors: [alice, bob]`）。不要把 editor grant 寫進 Package 擁有的 `package.yaml`。
4. 建立 Alice、Bob、Carol 的 active USER，以及 ADMIN-only 身分。證據中請遮罩密碼與 token。

## 驗收矩陣

| 檢查 | 預期結果 | 應附證據 |
| --- | --- | --- |
| Private Package 仍僅 owner 可編 | Alice 可編 `alice`；Bob、Carol 對 privileged source/mutation API 得到 403 | Package ID、遮罩後身分、HTTP 狀態碼 |
| Shared Package Online Edit | Alice 與 Bob 在 `fpp-common` 完成 Stage → Validate → Review → Publish | Package ID、Change Set ID、已發布 revision |
| Shared Package ZIP Import | Alice 或 Bob 以相同 Change Set 閘道匯入 Package ZIP 並成功發布 | ZIP Change Set ID、已發布 revision |
| 非 editor 檢視 | Carol 仍可安全檢視並使用 RUN/Debug/LOAD/Jobs/Schedules；privileged authoring API 回 403 | 路由與狀態碼清單 |
| 多 Package grant | 被指派多個 Package 的 USER 可編輯各已授權 Package，且不會取得其他 Package 所有權 | Package capability 回應 |
| 不提升權限 | Editor grant 不會加上 `SYSTEM_SETTINGS_WRITE`、Central Runtime Config authoring 或 ADMIN；ADMIN-only 仍被拒於 Package authoring 與一般 RUN/Jobs/Schedules | `/auth/capabilities`、被拒的 Central/Package 路由 |
| 撤銷 | 從 `editors` 移除 Bob 後，privileged UI/source 狀態清除、publish 被拒，且已發布資料保持不變 | 撤銷前後 registry revision、被拒 Change Set ID |
| 並行 editor | 兩位 editor 以同一 revision 暫存；先發布者成功，後者得到 409 | 兩個 Change Set ID 與 409 內容 |
| Studio Package 綁定 | Authoring UI 顯示綁定的 Package ID，並對非 editor 隱藏變更控制項 | UI 說明或截圖路徑 |
| 已接受 Jobs 與 Schedules | 當 shared Package 已有 accepted/queued/running Job 與 pinned Schedule 時，trusted editor 發布不會改變其 pinned Package root、source revision 與 Schedule `expectedRevisionDigest`；之後新 Job 才看到新 revision | Job ID、前後 revision digest、Schedule ID |

## 自動化覆蓋仍不能取代本 gate

- Hosted CI 與 `smoke:source-authoring` 覆蓋 fixture-backed shared-editor 流程、並行 409 與撤銷清理。
- Server 整合測試覆蓋 trusted editor 授權，以及 shared-editor 發布下的 Job/Schedule pin 行為。
- 關閉 #387 前仍須完成此 operator 矩陣：在 issue 附上 staging head SHA、Package ID、遮罩後身分/角色、API/UI 結果，以及 Job/Schedule ID。

## 通過條件

僅當上表每一列都有同一建置 head 的具體 staging 證據時，才可視為通過。不可只憑 fixture smoke 或 CI 關閉 #387。
