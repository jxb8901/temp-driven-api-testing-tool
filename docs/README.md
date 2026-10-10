# ATT Documentation

This is the canonical entry point for the current ATT documentation. Current user and maintainer documentation lives directly under `docs/` or in the current source directories below. Superseded, version-specific, migration, and issue-specific material lives under [`history/`](history/).

## Start here

| I am... | Start with |
|---|---|
| New to ATT | [Quick Start (English)](quick-start.md) |
| 使用中文 | [快速入門（繁體中文）](quick-start.zh.md) |
| Looking up a supported field, CLI option, or schema | [Reference Manual (English)](reference.html) · [modular sources](reference/README.md) |
| 查閱欄位、CLI 選項或 schema | [Reference Manual（繁體中文）](reference.zh.html) · [模組化來源](reference.zh/README.md) |

Minimal offline workflow:

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

## Common tasks

| I want to... | Go to |
|---|---|
| Create my first test | [Quick Start](quick-start.md) |
| Understand workbook, sidecar, or snapshot | [Test Authoring](reference/test-authoring.md) |
| Write a Template or Flow | [Test Authoring](reference/test-authoring.md) |
| Call an external script or program | [Tool](reference/resources/tools.md) |
| Query or update a database | [DBHelper](reference/resources/dbhelper.md) |
| Send, receive, or request MQ messages | [MQHelper](reference/resources/mqhelper.md) |
| Route SSH Tools across SIT/UAT or clustered hosts | [SSHHelper](reference/resources/sshhelper.md) · [中文](reference.zh/resources/sshhelper.md) |
| Debug one Template, Flow, or Tool | [Debug](reference/execution-modes/debug.md) |
| Run a load test | [Load](reference/execution-modes/load.md) |
| Switch SIT/UAT resources | [Configuration and Environments](reference/configuration.md) |
| Understand `${...}` and `#{...}` | [Expressions](reference/expressions.md) |
| Diagnose FAIL, ERROR, or INVALID | [Validation and Troubleshooting](reference/validation-diagnostics.md) |
| Integrate ATT with CI or package a release | [CI, Packaging, and Operations](reference/ci-packaging-operations.md) |
| Deploy the API control plane | [ATT Server deployment and API](server-deployment.md) · [中文](server-deployment.zh.md) |
| Use the ATT Server browser console | [ATT Server Web UI](att-server-web-ui.md) · [中文](att-server-web-ui.zh.md) |
| Run jobs on ATT Server | [ATT Remote client](remote-client.md) · [中文](remote-client.zh.md) |

## Documentation types and language

- **Quick Start** is the tutorial for a first successful run.
- **Reference** is the supported public contract. Use the generated [English](reference.html) or [繁體中文](reference.zh.html) manual, or the matching [English](reference/) and [繁體中文](reference.zh/) source modules.
- **System Design** explains maintainer-facing implementation architecture: [runtime execution](system-design/runtime-execution.md), [Run and Load benchmark](system-design/run-load-benchmark.md) / [Run 與 Load 效能基準](system-design/run-load-benchmark.zh.md), [Server Worker and SSE benchmark](system-design/server-worker-sse-benchmark.md) / [Server Worker 與 SSE 效能基準](system-design/server-worker-sse-benchmark.zh.md), [Server event-journal benchmark](system-design/server-event-journal-benchmark.md) / [Server 事件日誌基準](system-design/server-event-journal-benchmark.zh.md), [Load scheduler](system-design/load-scheduler.md) / [Load scheduler 中文](system-design/load-scheduler.zh.md), [Load telemetry](system-design/load-telemetry.md) / [Load telemetry 中文](system-design/load-telemetry.zh.md), and SSHHelper [English](system-design/sshhelper.md) / [中文](system-design/sshhelper.zh.md).
- **CHANGELOG** records release chronology: [CHANGELOG.md](../CHANGELOG.md).
- **History** preserves superseded, version-specific, migration, compatibility, and issue-specific material: [history/README.md](history/README.md).

The generated Reference manuals are assembled from the modular source directories. This page routes users to the correct owner and intentionally does not duplicate field-level normative content.

## Current tree

```text
docs/
├── README.md                 # canonical documentation landing page
├── quick-start.md            # current English tutorial
├── quick-start.zh.md         # current 繁體中文 tutorial
├── server-deployment.md      # ATT Server API and Tomcat deployment
├── server-deployment.zh.md   # ATT Server API 與 Tomcat 部署
├── att-server-web-ui.md      # ATT Server browser console
├── remote-client.md         # ATT Remote client guide
├── remote-client.zh.md      # ATT Remote 用戶端指南
├── reference.md              # generated combined English Reference
├── reference.html            # generated combined English Reference
├── reference.zh.md           # generated combined 繁體中文 Reference
├── reference.zh.html         # generated combined 繁體中文 Reference
├── reference/                # current English Reference sources
├── reference.zh/             # current 繁體中文 Reference sources
├── system-design/            # current maintainer design
├── reference-manifest.txt    # Reference generation order/build metadata
└── history/                  # all superseded/version-specific material
```

For Reference generation and validation commands, see the source-module [README](reference/README.md). Historical documentation architecture and migration notes are retained under [`history/`](history/) instead of the current documentation path.
