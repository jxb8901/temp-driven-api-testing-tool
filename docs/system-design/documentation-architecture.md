<!-- att-doc-status: current release-target=4.6.1 status=unpublished legacy-code-line=4.3.1 -->

# Documentation architecture and truth matrix

This page records documentation owners, feature/interface availability and migration decisions against the ATT 4.6.1 release target. No formal 4.6.0 release or tag has been published; 4.3.1 identifies the prior CLI code line and is not a verified GitHub Release. Product claims distinguish implementation in the open target stack from deployed acceptance. Open or unverified acceptance remains Pending.

## Inventory and link impact

The generated [document inventory](document-inventory.csv) lists current and historical Markdown, generated manuals, reference source modules, documented fixtures, language pairs, destinations, release status, test coverage and inbound Markdown-link counts. Rebuild it with:

~~~sh
python3 -B tools/build_documentation_inventory.py
python3 -B tools/build_documentation_inventory.py --check
~~~

The documentation release gate checks local links and anchors from current entry points, paired source modules, interface and Operations pages, and documented examples. The compatibility pages retain old Server deployment and Studio heading anchors. Their link counts show which old paths still receive navigation.

## Feature, interface and availability matrix

| Capability | ATT 4.6.1 target Local CLI | ATT Remote compatibility CLI | Studio and REST | Engine or Server | Status |
|---|---|---|---|---|---|
| Legacy Workbook Case execution | `./att.sh run` selects workbook Cases | `att remote run` submits legacy Server work | Existing Case and batch routes remain available | Engine executes the legacy Case adapter | Compatibility behavior retained |
| Native Case and implicit Suite RUN | Target CLI exposes `run native-case` and `run native-suite` | `att remote` remains legacy; the target CLI's `--server` path requires validated Native drafts | Native authoring, preview and RUN draft flows are present in the target stack | Engine resolves/runs selected Cases; Server validates `att-execution/v2` drafts and owns Jobs | Implemented in target stack; deployed acceptance pending |
| Template, Flow and Tool execution | Current standalone debug command | Current remote debug command | Current unified RUN targets these resources; Debug is optional diagnostic mode | Shared execution and Action contracts | Current; local CLI verb is legacy |
| LOAD | Current load scenarios, Quick Load and Load Template CLI | Current remote load | Current six-mode Template catalog and validated builder | Current scheduler, workload mix and arrival-rate execution | Current |
| VALIDATE | Current package and selected dependency validation | Current remote validation | Current Studio and REST validation flows | Read-only validation; no business execution | Current |
| Shared Configuration and Package Manifest | Current config and Package validation | Server-owned configuration for remote work | Current inspectors and Package authoring flows | Global Runtime Configuration; optional Package Manifest and Package Testdata | Current |
| Jobs and Schedules | Local Run IDs and output only | Current Job listing, watch, result, cancel and artifact commands | Current Job, Schedule and history views | Server persists Jobs and Schedule definitions | Server capability; no Local Job store |
| Broader CLI vNext grammar and migration | Target parser covers Native Case/Suite; broader shared grammar remains proposed | Legacy ATT Remote remains supported | Existing Studio and REST capabilities remain | Native remote RUN uses validated drafts; raw legacy Case submission is not used | Native target implemented; broader proposal remains Planned |
| #294 CLI management resources | Not available | Not included in the proposed grammar | Server and Studio resources remain separately available | Future P3 proposal | Planned future work |
| Workbook Snapshot generation | Current explicit snapshot authoring command | Not applicable | Studio authoring tools are separate | Snapshot generation is not a VALIDATE side effect | Current |

The product feature, interface and release status are separate facts. A Server or Studio capability does not imply a command on the legacy CLI. The target CLI provides explicit Native Case/Suite commands while the broader grammar remains proposed. The 4.6.0 target stack is unpublished; implementation does not close deployed acceptance.

## Semantic ownership matrix

| Contract | Normative owner | Guides and interfaces should link to |
|---|---|---|
| Testcase, workbook, sidecar and Snapshot | [Test authoring](../reference/test-authoring.md) | [Authoring model](../concepts/authoring-model.md) |
| Stage, Template, Action and Flow execution | [Actions](../reference/actions.md) and [Unified RUN](../reference/execution-modes/unified-run.md) | [Execution model](../concepts/execution-model.md) |
| Legacy Local Case-run adapter | [Run mode](../reference/execution-modes/run.md) | [RUN guide](../guides/run-tests.md) |
| Debug inputs and diagnostics | [Debug](../reference/execution-modes/debug.md) | [Studio Explorer](../interfaces/studio/explorer-run.md) |
| LOAD schema, six modes, composition, pacing and evidence | [Load mode](../reference/execution-modes/load.md) | [Load model](../concepts/load-model.md) and [Load guide](../guides/load-testing.md) |
| Read-only validation and diagnostics | [Validation and diagnostics](../reference/validation-diagnostics.md) | [Validation guide](../guides/validate.md) |
| Shared Runtime Configuration and Environment precedence | [Configuration](../reference/configuration.md) | [Configuration model](../concepts/configuration-model.md) |
| Package defaults, typed overrides and Testdata | [Package Manifest](../reference/package-manifest.md) | [Package model](../concepts/package-model.md) |
| Context and Expressions | [Runtime Context](../reference/runtime-context.md) and [Expressions](../reference/expressions.md) | Concepts link to both owners |
| Tool and Helper contracts | [Resource Reference](../reference/resources/overview.md) and its resource modules | Authoring and task guides |
| Retry, timeout and execution control | [Reliability and execution control](../reference/reliability-execution-control.md) | Load and RUN guides |
| Results, reports and retained evidence | [Results, reports and evidence](../reference/results-reports-evidence.md) | CLI, Studio and Server API guides |
| Current CLI contract | [CLI Reference](../reference/cli.md) | [CLI interface map](../interfaces/cli/README.md) |
| Proposed CLI vNext contract | [Planned vNext page](../interfaces/cli/vnext-proposed.md) | Must remain visibly Planned before release |
| REST routes and payload behavior | [Server API v1 pages](../interfaces/server-api/README.md) | Studio and Server operator pages |
| Job and Schedule lifecycle | [Jobs and Events API](../interfaces/server-api/jobs-and-events.md), [Schedules API](../interfaces/server-api/schedules.md), and [operations](../operations/server-jobs-schedules.md) | [Run and Job lifecycle](../concepts/jobs-and-runs.md) |
| Deployment, security and operational policy | [Operations manuals](../operations/server-deployment.md) | API pages link here for deployment concerns |

## Source-to-target migration map

| Existing source | Decision | Current destination |
|---|---|---|
| Root README | Rewrite around the Package-oriented platform and a runnable current workflow | [README](../../README.md) |
| docs/README.md | Replace single workflow navigation with audiences, tasks and availability | [Documentation map](../README.md) |
| docs/reference/cli.md and Chinese pair | Keep legacy verbs and target-stack Native Case/Suite commands Current; label unpublished target behavior | [CLI Reference](../reference/cli.md) |
| vNext portion of CLI Reference and proposed examples | Move and label Planned until a release is verified | [Proposed CLI vNext](../interfaces/cli/vnext-proposed.md) |
| docs/remote-client.md and Chinese pair | Keep as the legacy ATT Remote profile, Job and artifact guide | [ATT Remote guide](../remote-client.md) |
| docs/server-deployment.md and Chinese pair | Keep old paths and heading anchors as compatibility stubs | [Deployment](../operations/server-deployment.md), [configuration](../operations/server-configuration.md), [security](../operations/security.md), and [API v1](../interfaces/server-api/README.md) |
| docs/att-server-web-ui.md and Chinese pair | Keep old paths and heading anchors as compatibility stubs | [Studio interface pages](../interfaces/studio/README.md) |
| REST API and SSE sections | Split by route concern without changing request contract content | [Versioned Server API](../interfaces/server-api/README.md) |
| Server release-specific operational sections | Keep release-specific policies under Operations | [Jobs and Schedules operations](../operations/server-jobs-schedules.md) |
| Issue #176 rollout checklist | Preserve as a historical record | [Archived checklist](../history/issue-176-server-rollout.md) |
| Load mode introduction and task navigation | Add a task guide and six-mode concept page; keep schema/execution ownership in Reference | [Load guide](../guides/load-testing.md), [Load model](../concepts/load-model.md), [Load Reference](../reference/execution-modes/load.md) |
| docs/reference/ and docs/reference.zh/ | Preserve paired editable normative modules and their manifest | [Reference sources](../reference/README.md) and [繁體中文 sources](../reference.zh/README.md) |
| docs/reference.md and docs/reference.zh.md | Preserve as generated outputs and rebuild after source changes | [Generated English](../reference.md) and [繁體中文](../reference.zh.md) |
| docs/history/ | Preserve chronology and superseded material | [History index](../history/README.md) |

## Current and Planned CLI migration

The previous Local CLI baseline retains explicit workbook Snapshot authoring, Package validation and legacy Case execution. The 4.6.0 target Quick Start uses explicit `run native-case` commands documented in the [current CLI Reference](../reference/cli.md). VALIDATE reads and checks authored files; it never generates or refreshes a Snapshot. The target remains unpublished.

| Interface path | Current target or proposed syntax | Status |
|---|---|---|
| `./att.sh run --suite ...` and legacy Case selection | Workbook/Sidecar Case adapter | Retained compatibility behavior |
| `run native-case` and `run native-suite` | Explicit Native Case and implicit Suite selectors | Implemented in the 4.6.0 target stack; release and deployed acceptance pending |
| standalone Debug, direct LOAD, Snapshot, docs, report, build and clean | Existing command paths | Retained compatibility behavior |
| `att remote` Jobs, events, artifacts and management | Existing Remote profile and REST/SSE client | Retained compatibility behavior |
| Broader shared Local/Server run, load and validate grammar | See the separate [Proposed CLI vNext](../interfaces/cli/vnext-proposed.md) | Planned beyond the Native target commands |
| #294 CLI management resources | Not part of the minimal proposal | Future work |

This map describes current target-stack coverage, not complete migration or semantic parity. The 4.6.0 release/tag does not exist yet; deployed acceptance for Native RUN, reports, shared editors and Default Package adoption remains separately tracked. Broader migration requires its own implementation and release verification.

## Documentation-as-code contract

The release gate preserves the generator, manifest, history archive and Traditional Chinese source pairing, and checks behavior as well as structure:

- Current CLI examples are checked against the shipped legacy parser and help contract. Planned examples are tagged, remain outside the current execution suite and are compared with the proposed parser only.
- Checked-in Current examples are run against offline fixtures. Schema-bearing YAML/JSON fixtures are validated by the embedded Engine registry and the semantic validators used by the CLI tests.
- Server API route tables are reconciled with the Servlet routes and Server contract tests. API pages retain request, authorization, error and result semantics from the released source.
- Current, Legacy, Planned and Historical markers constrain which command and version claims each page can make.
- Local Markdown paths and anchors, EN/ZH source pairing, generated Reference freshness, Snapshot statements and Local Run versus Server Job claims are verified.
- The release command is python3 -B tools/verify_documentation.py. It includes manual freshness, contract, unit-test and semantic Reference gates.
