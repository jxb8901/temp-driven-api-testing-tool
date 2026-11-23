# ATT 4.6.0 — API and integration testing (release target)

**Release status: not published.** This documentation describes the 4.6.0 formal release target; no 4.6.0 release or tag exists. The 4.3.1 version identifies an earlier CLI code line and is not a verified GitHub Release.

The 4.6.0 target stack contains Native Case implementation work for inline and Excel data, Studio editing and preview, implicit Suites, Native reports, and Default Package examples. Deployed acceptance remains open for Native RUN ([#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393)), report integration ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)), and Default Package adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)). Shared-editor deployment evidence is tracked separately in [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387). Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) are outside this release target.

ATT is a Package-oriented platform for functional API and integration testing and controlled Load testing. A Package groups Testcases, Templates, Flows, Tools, Testdata and other business resources behind one registered root. Shared Runtime Configuration and Environment profiles bind those resources to SIT, UAT or another environment.

ATT supports Local execution, an optional ATT Server control plane, a browser Studio, and a versioned REST API. The Engine runs one functional RUN model across Case, Template, Flow and Tool targets; Debug adds diagnostic detail to a RUN. LOAD uses separate workload pacing and evidence controls. VALIDATE checks configuration, Package resources and dependencies without business execution.

![ATT Studio dashboard showing configured Packages, recent Jobs, and Server status](docs/system-design/ui-shell-baseline/dashboard-1440-light.png)

*Illustrative Studio UI-shell baseline with sample Package and Job data; the version badge belongs to that screenshot baseline.*

## Quick Start: Native Case (4.6.0 release target)

Primary happy path on the release-target stack; release and deployed acceptance remain pending:

```text
Package → Flow → testcase.yaml → Preview/Resolve → Select Case(s) → Validate → RUN
```

~~~sh
./att.sh validate --package
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
~~~

Open [Quick Start](docs/quick-start.md) for the offline Native fixtures (single / inline-multi / Excel), Studio preview notes, and the labeled legacy Workbook appendix. The same guide is available in [繁體中文](docs/quick-start.zh.md). Migration decisions live in the [S12 matrix](docs/maintenance/native-case-default-package-migration-s12.md).

Legacy Workbook + Sidecar + Snapshot remains supported:

~~~sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
~~~

## What ATT provides

- Package isolation and reusable authoring with Native Test Case Definitions (`testcase.yaml`), Flows, Actions, Tools and Resources; legacy Stages/Templates/Workbooks remain supported.
- Functional RUN and separate LOAD execution, with read-only VALIDATE.
- Shared Runtime Configuration, Environment profiles, optional Package Manifest defaults and Package-owned Testdata.
- Local CLI, ATT Studio, REST/SSE integration and Server Jobs and Schedules.
- Bounded diagnostics, reports and evidence with secret protection.

## Documentation

- [Documentation map](docs/README.md): start pages for test authors, CLI users, load testers, Studio operators, Server administrators, integrators and contributors.
- [Concepts](docs/concepts/): Package, authoring, execution, configuration and Server lifecycle.
- [Guides](docs/guides/): task-oriented RUN, LOAD and validation procedures.
- [Current CLI Reference](docs/reference/cli.md) and [ATT Remote compatibility guide](docs/remote-client.md).
- [Proposed CLI vNext](docs/interfaces/cli/vnext-proposed.md) is a plan for a later release and is not part of the 4.6.0 target.
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
