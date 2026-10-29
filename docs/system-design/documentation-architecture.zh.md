<!-- att-doc-status: current release=4.2.7 -->

# Documentation architecture and truth matrix

本頁記錄 ATT 4.2.7 的文件 owner、feature/interface availability 及 migration 決策。產品 capability 以已發行 source、schema、test 及已合併行為核實；open issue 或未合併 implementation 不會自動成為 Current。

## Inventory 與 link impact

產生的 [document inventory](document-inventory.csv) 列出目前與歷史 Markdown、generated manual、Reference source module、文件 fixture、語言配對、destination、release status、test coverage 及 inbound Markdown link 數量。使用以下指令重建：

~~~sh
python3 -B tools/build_documentation_inventory.py
python3 -B tools/build_documentation_inventory.py --check
~~~

Documentation release gate 會檢查目前 entry point、配對 source module、interface 與 Operations page，以及文件範例中的 local link 和 anchor。舊 Server deployment 及 Studio heading anchor 由 compatibility page 保留。Link count 可顯示仍有哪些導航指向舊路徑。

## Feature、interface 及 availability matrix

| Capability | ATT 4.2.7 Local CLI | ATT Remote 相容 CLI | Studio 及 REST | Engine 或 Server | Status |
|---|---|---|---|---|---|
| Functional Case execution | Current run command 選擇 workbook Case | Current remote run 提交 Server 工作 | Current unified RUN 支援 Case 及選取的 Case batch | Engine 執行 Case；Server 驗證 draft 並擁有 Job | Current |
| Template、Flow、Tool execution | Current standalone debug command | Current remote debug command | Current unified RUN 支援這些 resource；Debug 是選用診斷模式 | 共用 execution 及 Action contract | Current；local CLI command 是 legacy |
| LOAD | Current load scenario、Quick Load 及 Load Template CLI | Current remote load | Current 六種 Template catalog 及 validated builder | Current scheduler、workload mix 及 arrival-rate execution | Current |
| VALIDATE | Current Package 及 selected dependency validation | Current remote validation | Current Studio 及 REST validation flow | Read-only validation；不執行 business work | Current |
| Shared Configuration 及 Package Manifest | Current config 及 Package validation | Remote work 使用 Server-owned configuration | Current inspector 及 Package authoring flow | Global Runtime Configuration；選用 Package Manifest 與 Package Testdata | Current |
| Jobs 及 Schedules | 只有 Local Run ID 及 output | Current Job list、watch、result、cancel 及 artifact command | Current Job、Schedule 及 history view | Server 持久化 Job 與 Schedule definition | Server capability；沒有 Local Job store |
| CLI vNext 共用 run、load、validate grammar | 4.2.7 尚未發行 | Proposed Local/Server selection syntax | 此 proposal 不會移除 Studio 或 REST capability | Parser implementation 在 PR #296 未合併 branch | Planned；只可於 release 核實後升級 |
| #294 CLI management resource | 不提供 | 不包括在 proposed grammar | Server 及 Studio resource 分開提供 | 未來 P3 proposal | Planned future work |
| Workbook Snapshot generation | Current 明確的 snapshot authoring command | 不適用 | Studio authoring tool 分開處理 | Snapshot generation 不是 VALIDATE 副作用 | Current |

產品 feature、interface 及 release status 是不同事實。Server 或 Studio capability 不代表 Local CLI 也有對應 command。Proposed vNext command 只有 run、load 及 validate；它不會移除目前相容 verb 或 Server resource。

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
| Root README | 以 Package-oriented platform 及可執行的 4.2.7 workflow 重寫 | [README](../../README.md) |
| docs/README.md | 以讀者、任務及 availability 導航取代單一 workflow | [Documentation map](../README.md) |
| docs/reference/cli.md 及中文配對 | 保留已發行的 4.2.7 verb 為 Current；從 current Reference 移除 proposal | [CLI Reference](../reference.zh/cli.md) |
| CLI Reference 的 vNext 部分及 proposed example | 移至獨立 Planned 文件，直到核實 release | [Proposed CLI vNext](../interfaces/cli/vnext-proposed.zh.md) |
| docs/remote-client.md 及中文配對 | 保留為 legacy ATT Remote profile、Job 及 artifact 指南 | [ATT Remote guide](../remote-client.zh.md) |
| docs/server-deployment.md 及中文配對 | 保留舊路徑與 heading anchor 作為 compatibility stub | [Deployment](../operations/server-deployment.zh.md)、[Configuration](../operations/server-configuration.zh.md)、[Security](../operations/security.zh.md) 及 [API v1](../interfaces/server-api/README.zh.md) |
| docs/att-server-web-ui.md 及中文配對 | 保留舊路徑與 heading anchor 作為 compatibility stub | [Studio interface pages](../interfaces/studio/navigation.zh.md) |
| REST API 及 SSE section | 按 route concern 拆分，不更改 request contract | [Versioned Server API](../interfaces/server-api/README.zh.md) |
| Server release-specific operational section | 保留目前 4.2.7 policy，移至 Operations | [Jobs and Schedules operations](../operations/server-jobs-schedules.zh.md) |
| Issue #176 rollout checklist | 保留為 historical record | [Archived checklist](../history/issue-176-server-rollout.zh.md) |
| Load mode introduction 與 task navigation | 增加 task guide 及六種 mode concept page；schema/execution owner 留在 Reference | [Load guide](../guides/load-testing.zh.md)、[Load model](../concepts/load-model.zh.md)、[Load Reference](../reference.zh/execution-modes/load.md) |
| docs/reference/ 及 docs/reference.zh/ | 保留配對 editable normative module 及 manifest | [Reference sources](../reference/README.md) 及 [繁體中文 sources](../reference.zh/README.md) |
| docs/reference.md 及 docs/reference.zh.md | 保留 generated output；source 變更後重新建置 | [Generated English](../reference.md) 及 [繁體中文](../reference.zh.md) |
| docs/history/ | 保留 chronology 及 superseded material | [History index](../history/README.md) |

## Current 與 Planned CLI migration

ATT 4.2.7 保留明確的 workbook Snapshot authoring、Package validation 及 legacy Case execution。Current Quick Start 仍可在此 interface 執行。VALIDATE 會讀取並檢查 authored file，絕不產生或更新 Snapshot。

| 4.2.7 相容語法 | Proposed vNext 語法 | 狀態 |
|---|---|---|
| 使用 workbook suite 與 Case selection 執行 run | 用明確 Local selection 執行 run suite 或 case | Planned |
| 對 Template、Flow 或 Tool 使用 standalone debug | 以 typed target 執行 run，並可選 Debug diagnostics | Planned |
| 載入 authored scenario 或 Quick Load target | 使用共用 Local/Server routing 載入 scenario 或 typed target | Planned |
| 透過 att remote 提交 remote run、load 或 validate | 在 run、load 或 validate 前選擇 Server profile | Planned |
| snapshot、docs、report、build、clean 及 remote Job management | 三個 command proposal 沒有定義等價 command | 保留目前 compatibility 行為；未來變更須另作 release 決策 |
| remote jobs、watch、artifacts、cancel 及 detach | 不包括在精簡 run/load/validate grammar | 保留在目前 legacy Remote guide；#294 management resource 仍屬未來工作 |

本表說明預期 migration path，不代表完全行為等價。只有 #287 發行且 parser、help、Local/Server 行為、diagnostics、output 及 compatibility test 通過後，才更新 current entry point。

## Documentation-as-code contract

Release gate 保留 generator、manifest、history archive 及 Traditional Chinese source pairing，並檢查行為與結構：

- Current CLI example 依已發行 legacy parser 及 help contract 核實。Planned example 有狀態標記，不會進入 Current execution suite，只會與 proposed parser 對照。
- 已提交的 Current example 使用 offline fixture 執行。含 schema 的 YAML/JSON fixture 透過 embedded Engine registry 及 CLI test 使用的 semantic validator 核實。
- Server API route table 與 Servlet route 及 Server contract test 對照。API page 保留已發行 source 中的 request、authorization、error 及 result semantics。
- Current、Legacy、Planned 及 Historical marker 限制各頁能宣稱的 command 與 version。
- 核實 local Markdown path/anchor、EN/ZH source pairing、generated Reference freshness、Snapshot 說明，以及 Local Run 和 Server Job 的差異。
- Release 指令為 python3 -B tools/verify_documentation.py，會執行 manual freshness、contract、unit-test 及 semantic Reference gate。
