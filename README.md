# ATT 4.2.6 — API and integration testing

ATT is a Package-oriented platform for functional API and integration testing and controlled Load testing. A Package groups Testcases, Templates, Flows, Tools, Testdata and other business resources behind one registered root. Shared Runtime Configuration and Environment profiles bind those resources to SIT, UAT or another environment.

ATT supports Local execution, an optional ATT Server control plane, a browser Studio, and a versioned REST API. The Engine runs one functional RUN model across Case, Template, Flow and Tool targets; Debug adds diagnostic detail to a RUN. LOAD uses separate workload pacing and evidence controls. VALIDATE checks configuration, Package resources and dependencies without business execution.

![ATT Studio dashboard showing configured Packages, recent Jobs, and Server status](docs/system-design/ui-shell-baseline/dashboard-1440-light.png)

*Illustrative Studio UI-shell baseline with sample Package and Job data; the version badge belongs to that screenshot baseline.*

## Quick Start: current 4.2.6 CLI

This source-checkout walkthrough uses the shipped legacy CLI. Snapshot generation is an explicit authoring step; validation does not create or update snapshots.

~~~sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
~~~

Open [Quick Start](docs/quick-start.md) for the full offline fixture, validation, execution, logs and assertions. The same guide is available in [繁體中文](docs/quick-start.zh.md).

## What ATT provides

- Package isolation and reusable authoring with Testcases, Stages, Templates, Flows, Actions, Tools and Resources.
- Functional RUN and separate LOAD execution, with read-only VALIDATE.
- Shared Runtime Configuration, Environment profiles, optional Package Manifest defaults and Package-owned Testdata.
- Local CLI, ATT Studio, REST/SSE integration and Server Jobs and Schedules.
- Bounded diagnostics, reports and evidence with secret protection.

## Documentation

- [Documentation map](docs/README.md): start pages for test authors, CLI users, load testers, Studio operators, Server administrators, integrators and contributors.
- [Concepts](docs/concepts/): Package, authoring, execution, configuration and Server lifecycle.
- [Guides](docs/guides/): task-oriented RUN, LOAD and validation procedures.
- [Current CLI Reference](docs/reference/cli.md) and [ATT Remote compatibility guide](docs/remote-client.md).
- [Proposed CLI vNext](docs/interfaces/cli/vnext-proposed.md) is a plan for a later release. It is not available in ATT 4.2.6.
- [Generated Reference Manual](docs/reference.md) and [繁體中文 Reference](docs/reference.zh.md).
- [Server deployment](docs/operations/server-deployment.md), [Studio interfaces](docs/interfaces/studio/), and [Server API v1](docs/interfaces/server-api/).
- [Documentation architecture and inventory](docs/system-design/documentation-architecture.md); [history archive](docs/history/README.md).

## Build and documentation checks

Use a JDK and Maven to build the project. The source launcher runs prebuilt classes and does not compile code.

~~~sh
mvn clean verify
python3 -B tools/build_reference_manual.py --check
python3 -B tools/verify_documentation.py
./build.sh
~~~

The Server WAR requires Java 17 or later and external Tomcat 10.1+. The Local CLI, Engine, Worker, Remote client and Server API retain the published Java 8 baseline. See the [multi-module build guide](docs/system-design/multi-module-build.md) for maintainer details.
