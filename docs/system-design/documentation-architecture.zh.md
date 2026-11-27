<!-- att-doc-status: current release-target=4.6.1 status=unpublished legacy-code-line=4.3.1 -->

# Documentation architecture and truth matrix

本頁記錄 ATT 4.6.1 release target 的文件 owner、feature/interface availability 及 migration 決策。尚未發佈正式 4.6.0 release 或 tag；4.3.1 只用來標識較早的 CLI code line，並非已核實的 GitHub Release。產品 capability 會區分開啟中的目標 stack implementation 與部署驗收；未完成或未核實的 acceptance 仍標示為 Pending。

## Inventory 與 link impact

產生的 [document inventory](document-inventory.csv) 列出目前與歷史 Markdown、generated manual、Reference source module、文件 fixture、語言配對、destination、release status、test coverage 及 inbound Markdown link 數量。使用以下指令重建：

~~~sh
python3 -B tools/build_documentation_inventory.py
python3 -B tools/build_documentation_inventory.py --check
~~~

Documentation release gate 會檢查目前 entry point、配對 source module、interface 與 Operations page，以及文件範例中的 local link 和 anchor。舊 Server deployment 及 Studio heading anchor 由 compatibility page 保留。Link count 可顯示仍有哪些導航指向舊路徑。

## Feature、interface 及 availability matrix

| Capability | ATT 4.6.1 目標 Local CLI | ATT Remote 相容 CLI | Studio 及 REST | Engine 或 Server | Status |
|---|---|---|---|---|---|
| Legacy Workbook Case execution | `./att.sh run` 選擇 workbook Case | `att remote run` 提交舊版 Server 工作 | 現有 Case 和 batch route 繼續可用 | Engine 執行 legacy Case adapter | 保留相容行為 |
| Native Case 和 implicit Suite RUN | 目標 CLI 提供 `run native-case` 和 `run native-suite` | `att remote` 保留舊版行為；目標 CLI 的 `--server` 路徑使用已驗證 Native draft | 目標 stack 已有 Native authoring、preview 和 RUN draft flow | Engine 解析及執行選取的 Case；Server 驗證 `att-execution/v2` draft 並擁有 Job | 已在目標 stack 實作；部署驗收待完成 |
| Template、Flow、Tool execution | Current standalone debug command | Current remote debug command | Current unified RUN 支援這些 resource；Debug 是選用診斷模式 | 共用 execution 及 Action contract | Current；local CLI command 是 legacy |
| LOAD | Current load scenario、Quick Load 及 Load Template CLI | Current remote load | Current 六種 Template catalog 及 validated builder | Current scheduler、workload mix 及 arrival-rate execution | Current |
| VALIDATE | Current Package 及 selected dependency validation | Current remote validation | Current Studio 及 REST validation flow | Read-only validation；不執行 business work | Current |
| Shared Configuration 及 Package Manifest | Current config 及 Package validation | Remote work 使用 Server-owned configuration | Current inspector 及 Package authoring flow | Global Runtime Configuration；選用 Package Manifest 與 Package Testdata | Current |
| Jobs 及 Schedules | 只有 Local Run ID 及 output | Current Job list、watch、result、cancel 及 artifact command | Current Job、Schedule 及 history view | Server 持久化 Job 與 Schedule definition | Server capability；沒有 Local Job store |
| 更廣泛的 CLI vNext grammar 及 migration | 目標 parser 已涵蓋 Native Case/Suite；更廣泛的共用 grammar 仍屬 proposal | 舊版 ATT Remote 繼續支援 | 現有 Studio 和 REST capability 保留 | Native remote RUN 使用已驗證 draft；不使用舊版 raw Case submission | Native target 已實作；更廣泛 proposal 仍屬 Planned |
| #294 CLI management resource | 不提供 | 不包括在 proposed grammar | Server 及 Studio resource 分開提供 | 未來 P3 proposal | Planned future work |
| Workbook Snapshot generation | Current 明確的 snapshot authoring command | 不適用 | Studio authoring tool 分開處理 | Snapshot generation 不是 VALIDATE 副作用 | Current |

產品 feature、interface 及 release status 是不同事實。Server 或 Studio capability 不代表舊版 CLI 有對應 command。目標 CLI 提供明確 Native Case/Suite command，而更廣泛的 grammar 仍屬 proposal。4.6.0 target stack 尚未發佈；有 implementation 不代表部署驗收已完成。

## Semantic ownership matrix

| Contract | 規範 owner | Guide 與 interface 應連結至 |
|---|---|---|
| Testcase、workbook、sidecar 及 Snapshot | [Test authoring](../reference.zh/test-authoring.md) | [Authoring model](../concepts/authoring-model.zh.md) |
| Stage、Template、Action 及 Flow execution | [Actions](../reference.zh/actions.md) 及 [Unified RUN](../reference.zh/execution-modes/unified-run.md) | [Execution model](../concepts/execution-model.zh.md) |
| Legacy Local Case-run adapter | [Run mode](../reference.zh/execution-modes/run.md) | [RUN guide](../guides/run-tests.zh.md) |
| Debug input 與 diagnostics | [Debug](../reference.zh/execution-modes/debug.md) | [Studio Explorer](../interfaces/studio/explorer-run.zh.md) |
| LOAD schema、六種 mode、composition、pacing 及 evidence | [Load mode](../reference.zh/execution-modes/load.md) | [Load model](../concepts/load-model.zh.md) 及 [Load guide](../guides/load-testing.zh.md) |
| Read-only validation 及 diagnostics | [Validation and diagnostics](../reference.zh/validation-diagnostics.md) | [Validation guide](../guides/validate.zh.md) |
| Shared Runtime Configuration 及 Environment precedence | [Configuration](../reference.zh/configuration.md) | [Configuration model](../concepts/configuration-model.zh.md) |
| Package defaults、typed override 及 Testdata | [Package Manifest](../reference.zh/package-manifest.md) | [Package model](../concepts/package-model.zh.md) |
| Context 及 Expressions | [Runtime Context](../reference.zh/runtime-context.md) 及 [Expressions](../reference.zh/expressions.md) | Concept page link 至兩個 owner |
| Tool 與 Helper contract | [Resource Reference](../reference.zh/resources/overview.md) 及相關 module | Authoring 與 task guide |
| Retry、timeout 及 execution control | [Reliability and execution control](../reference.zh/reliability-execution-control.md) | Load 及 RUN guide |
| Result、report 及 retained evidence | [Results, reports and evidence](../reference.zh/results-reports-evidence.md) | CLI、Studio 及 Server API guide |
| Current CLI contract | [CLI Reference](../reference.zh/cli.md) | [CLI interface map](../interfaces/cli/README.zh.md) |
| Proposed CLI vNext contract | [Planned vNext page](../interfaces/cli/vnext-proposed.zh.md) | Release 前必須清楚標記為 Planned |
| REST route 及 payload 行為 | [Server API v1 pages](../interfaces/server-api/README.zh.md) | Studio 與 Server operator page |
| Job 及 Schedule lifecycle | [Jobs and Events API](../interfaces/server-api/jobs-and-events.zh.md)、[Schedules API](../interfaces/server-api/schedules.zh.md) 及 [Operations](../operations/server-jobs-schedules.zh.md) | [Run and Job lifecycle](../concepts/jobs-and-runs.zh.md) |
| Deployment、安全及 operational policy | [Operations manuals](../operations/server-deployment.zh.md) | API page 連結至相關 deployment concern |

## Source-to-target migration map

| Existing source | 決策 | 目前 destination |
|---|---|---|
| Root README | 以 Package-oriented platform 及可執行的 current workflow 重寫 | [README](../../README.md) |
| docs/README.md | 以讀者、任務及 availability 導航取代單一 workflow | [Documentation map](../README.md) |
| docs/reference/cli.md 及中文配對 | 保留 legacy verb 和目標 stack Native Case/Suite command 為 Current；清楚標示尚未發佈的 target behavior | [CLI Reference](../reference.zh/cli.md) |
| CLI Reference 的 vNext 部分及 proposed example | 移至獨立 Planned 文件，直到核實 release | [Proposed CLI vNext](../interfaces/cli/vnext-proposed.zh.md) |
| docs/remote-client.md 及中文配對 | 保留為 legacy ATT Remote profile、Job 及 artifact 指南 | [ATT Remote guide](../remote-client.zh.md) |
| docs/server-deployment.md 及中文配對 | 保留舊路徑與 heading anchor 作為 compatibility stub | [Deployment](../operations/server-deployment.zh.md)、[Configuration](../operations/server-configuration.zh.md)、[Security](../operations/security.zh.md) 及 [API v1](../interfaces/server-api/README.zh.md) |
| docs/att-server-web-ui.md 及中文配對 | 保留舊路徑與 heading anchor 作為 compatibility stub | [Studio interface pages](../interfaces/studio/navigation.zh.md) |
| REST API 及 SSE section | 按 route concern 拆分，不更改 request contract | [Versioned Server API](../interfaces/server-api/README.zh.md) |
| Server release-specific operational section | 保留 release-specific policy 於 Operations | [Jobs and Schedules operations](../operations/server-jobs-schedules.zh.md) |
| Issue #176 rollout checklist | 保留為 historical record | [Archived checklist](../history/issue-176-server-rollout.zh.md) |
| Load mode introduction 與 task navigation | 增加 task guide 及六種 mode concept page；schema/execution owner 留在 Reference | [Load guide](../guides/load-testing.zh.md)、[Load model](../concepts/load-model.zh.md)、[Load Reference](../reference.zh/execution-modes/load.md) |
| docs/reference/ 及 docs/reference.zh/ | 保留配對 editable normative module 及 manifest | [Reference sources](../reference/README.md) 及 [繁體中文 sources](../reference.zh/README.md) |
| docs/reference.md 及 docs/reference.zh.md | 保留 generated output；source 變更後重新建置 | [Generated English](../reference.md) 及 [繁體中文](../reference.zh.md) |
| docs/history/ | 保留 chronology 及 superseded material | [History index](../history/README.md) |

## Current 與 Planned CLI migration

上一個 Local CLI 基線保留明確的 workbook Snapshot authoring、Package validation 及 legacy Case execution。4.6.0 target Quick Start 使用明確的 `run native-case` command，見[目前 CLI Reference](../reference.zh/cli.md)。VALIDATE 會讀取並檢查 authored file，絕不產生或更新 Snapshot。此目標尚未發佈。

| Interface 路徑 | 目前目標或規劃語法 | 狀態 |
|---|---|---|
| `./att.sh run --suite ...` 及 legacy Case selection | Workbook/Sidecar Case adapter | 保留相容行為 |
| `run native-case` 和 `run native-suite` | 明確 Native Case 及 implicit Suite selector | 已在 4.6.0 目標 stack 實作；release 和部署驗收待完成 |
| standalone Debug、直接 LOAD、Snapshot、docs、report、build 和 clean | 現有 command 路徑 | 保留相容行為 |
| `att remote` Job、event、artifact 及管理 | 現有 Remote profile 和 REST/SSE client | 保留相容行為 |
| 更廣泛的共用 Local/Server run、load 和 validate grammar | 見獨立的[Proposed CLI vNext](../interfaces/cli/vnext-proposed.zh.md) | Native target command 以外仍屬規劃中 |
| #294 CLI management resource | 不屬於精簡 proposal | 未來工作 |

本表說明目前 target-stack coverage，不代表已完成 migration 或語義等價。4.6.0 release/tag 尚不存在；Native RUN、report、shared-editor 和 Default Package adoption 的部署驗收分別跟進。更廣泛的 migration 需要獨立 implementation 及 release 核實。

## Documentation-as-code contract

Release gate 保留 generator、manifest、history archive 及 Traditional Chinese source pairing，並檢查行為與結構：

- Current CLI example 依已發行 legacy parser 及 help contract 核實。Planned example 有狀態標記，不會進入 Current execution suite，只會與 proposed parser 對照。
- 已提交的 Current example 使用 offline fixture 執行。含 schema 的 YAML/JSON fixture 透過 embedded Engine registry 及 CLI test 使用的 semantic validator 核實。
- Server API route table 與 Servlet route 及 Server contract test 對照。API page 保留已發行 source 中的 request、authorization、error 及 result semantics。
- Current、Legacy、Planned 及 Historical marker 限制各頁能宣稱的 command 與 version。
- 核實 local Markdown path/anchor、EN/ZH source pairing、generated Reference freshness、Snapshot 說明，以及 Local Run 和 Server Job 的差異。
- Release 指令為 python3 -B tools/verify_documentation.py，會執行 manual freshness、contract、unit-test 及 semantic Reference gate。
