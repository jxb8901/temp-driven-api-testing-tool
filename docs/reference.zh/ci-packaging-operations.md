# CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

## Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 和 release archive，並 smoke-test 解壓後的 local package。Reference generation 另外需要 Python 3 與 Pandoc。它會輸出供 CLI 使用的 `att-4.2.3-local.tar.gz`、包含 WAR 和部署指南的 `att-4.2.3-server.tar.gz`，以及 source archive `att-4.2.3-src.tar.gz`。每個 binary archive 均須小於 25 MB。Local package 不再包含 WAR 或其中重複的 runtime library。

## Runtime dependencies

CLI、Engine 及 Worker 仍相容 Java 8。ATT Server WAR 需要 Java 17+ 及外部 Tomcat 10.1+。ATT 不內置 CLI integration 所需的 JDBC driver；請將 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default CLI build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

## Studio inspection cache

Resource metadata discovery 會延後處理 DB/MQ/SSH/HTTP Helper 支援的 `${ENV:NAME}`，不讀取 process secret。Resource summary 將 `metadataState` 與 `executionReadiness` 分開：`not-validated` 不代表允許 execution；`dependency-unresolved` 會為可探索的 transitive Helper reference 提供不含值的 `ATT-RESOURCE-ENV-REQUIRED` diagnostic。這些 diagnostic 只列出 requirement，並非已確認缺少 variable 或 network failure。Explorer 顯示有效 environment 與 remediation，同時保留安全 metadata。原有 Resource `state: ready` 只描述 metadata，不代表 execution readiness。

Discovery 仍執行 root YAML/schema/profile 檢查及 shared-config path boundary。確認 boundary 後，DB/MQ/SSH/HTTP Helper descriptor 格式錯誤或缺失會獨立處理，保留正常 Helper 及不相關 resource metadata，並在 Explorer 提供不含值的 `ATT-RESOURCE-HELPER-INVALID` / `ATT-RESOURCE-HELPER-UNAVAILABLE` warning。失敗 descriptor 不會建立可用 Helper binding。Explorer 最多回傳四個個別 Helper failure diagnostic，另加其餘失敗數量。Discovery 每個 Helper type 最多 256 個 descriptor、每個 descriptor 3,000,000 bytes、每個 type 16 MiB；unsafe/symlink path、重複 path/Helper ID、trusted-schema failure 及超出限額仍會失敗。SSH Helper descriptor 缺失或無效時，Tool group 會保留 logical binding 供 discovery 使用。Explorer 將 SSH binding 和 ENV requirement 經 command-backed Tool 傳遞至相依 target；unresolved binding 不代表允許 execution。Tool-group 結構／schema failure 仍會失敗。Configuration Effective 和 Compare（包括 parsed snapshot cache）現在使用相同的 value-free discovery policy。Helper 缺失或格式無效時，仍保留有效 metadata 並顯示 structured dependency warning；`state: degraded` 和 `executionReadiness: not-validated` 均不允許 execution。ENV 名稱代表 requirement，不表示 variable 已確認缺失：這些 view 不會讀取 process secret value。Root configuration、profile、unsafe path、trusted-schema failure 和 inspection limit 仍 fail-closed。缺失的 Helper descriptor 亦參與 content reconciliation，新增或修復後會使 cached metadata 失效，毋須重啟。僅載入所選 target dependency 的 strict loading，以及統一 automatic/manual reindex operation 尚未實作。明確 validation 和 execution 仍使用 strict configuration loading，因此不相關的 active Helper 缺少 variable 時仍可能阻塞。Metadata-only configuration 不可執行。請在 Tomcat/Worker process environment 設定所需 variable，然後重新 validate；不要加入 production placeholder credential 只為了讓 browsing 成功。

Studio 將 Resource 分頁、Detail、安全 Source 及一般 Configuration projection 保存在 session memory。共用 cache 最多保留 128 個 response 及 8 MiB encoded payload；單一 response 超過 1 MiB 不會加入 cache。資料在 15 秒內視為 fresh；重新開啟較舊 view 時先顯示 cached projection，再由 background read 更新。相同 in-flight read 會合併；取消一個 consumer 不會取消其他 consumer 的 request。返回 view 時保留搜尋、排序、分頁及捲動狀態。

每次重用前先檢查 authenticated inspection context。Identity、session、role 或 inspection policy 改變會清除舊 response。Package/environment selection、明確 refresh、logout、頁面暫停、新觀察到的 package revision，以及 stale cursor 或存取錯誤，會使相關 memory 失效。Configuration 和 Source content 不會寫入 localStorage、IndexedDB、service worker 或 HTTP cache。Auth/CSRF response 及 `includeAllFields` administrator response 不會加入 query cache。HTTP response 仍遵守 `Cache-Control: no-store`。

每個 inspection Worker 仍逐一處理 request，並保留兩個 Resource inspector。Dispatch 優先選擇已保留相同 package/config/environment 及 policy key 的 idle Worker，再使用其他 idle Worker。Parsed Configuration snapshot 另有每 JVM 四個 entry 的 LRU，以及 32 MiB weighted retention budget；Worker heap 設定仍是 JVM 的硬性上限。重用時會對 root configuration、所選 Helper/Tool Group/Testdata dependency 及 schema tree 作 bounded content reconciliation，因此 rename、delete、相同 timestamp 的內容替換及未知 dependency 改動均會重新載入。Cache 錯誤會回退至原有 uncached validation。每個 request 均重新建立 permission-checked projection；administrator projection 不會進入一般 cache。

Optional `server.inspection.warmup` 預設為 `enabled: false`、`workers: 1`、`keys: []`。`workers` 範圍為 1 至 `server.inspection.maxConcurrent`。每個 key 包含 `packageId` 及 optional Engine `environment`；key 數量不得超過 warmup Worker 數量的兩倍，亦不可重複。不設定 `environment` 可預熱 Explorer 使用的 package-default index。Keys 分配至各 Worker，每個最多兩個；沒有 keys 時，所選 Worker 會預熱第一個 registered package。Package 缺失或 preload 失敗不會阻塞 Tomcat startup。Warmup 會避開 interactive demand，每個 key 最多 retry 三次並加入 backoff；daemon 更換後會重新預熱，shutdown 時停止。所有 Worker 均忙碌時，interactive traffic 可以取消 preload。

`GET /api/v1/metrics` 遵循既有 Server authentication policy，包含 `inspection.server` 及最新的 bounded `inspection.workers` snapshot。Counter 與 total nanoseconds 涵蓋 HTTP handling、queue、process launch、dispatch、processing、Configuration load/parse、Source request、workbook/YAML parsing 及 index build，亦提供 cache hit/miss/eviction、watcher invalidation/overflow、watched directory、retained key、instrumented read bytes、protocol response bytes、heap-pool peak、GC 及 process CPU。每個 Worker 最多保留 16 個 opaque package/environment aggregate，記錄 request count、processing time、index build、Configuration load 及 instrumented read bytes。Package key 經 hash；counter 不包含 path、configuration value 或 file content。Byte counter 代表已 instrument 的 inspection read，不等於 OS physical I/O。Process-launch timing 不包含 JVM readiness；dispatch 包含 readiness 及 protocol transport。Benchmark 另行記錄 sample p50/p95。若 optional inspection telemetry 無法放入設定的 response-byte budget，Worker 會省略該 snapshot，Server 同時清除 routing hint；metrics 不會放寬 response limit，亦不會令原本有效的 response 超限。

Display snapshot 不會授權 execution。Debug/Quick Load approval 及 Worker execution 仍保留 authoritative package revision check、`ATT-SERVER-DRAFT-STALE`、綁定 principal 的 cursor，以及 stale cursor HTTP `409`。設定例子見 [Server 部署](../server-deployment.zh.md)；量測程序見 [inspection benchmark](../system-design/web-ui-inspection-inline-jobs.md)。

## Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

## CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定 DB/MQ logical ID 讓相同 Template 在 SIT/UAT/PREPROD 間移動而不用改 Action。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。
