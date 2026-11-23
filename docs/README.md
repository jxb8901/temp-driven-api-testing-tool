# ATT documentation

This is the canonical documentation map for the ATT 4.6.0 formal release target. The release and tag have not been published; 4.3.1 identifies the earlier CLI code line and is not a verified GitHub Release. The map separates task guidance from normative Reference contracts and identifies which interfaces are included, legacy-compatible or planned.

The current default branch contains Native Case implementation for inline and Excel sources, Studio editing and preview, implicit Suites, Native reports, and Default Package examples. That code is integrated on this branch. Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed, which accepts Native RUN for that issue and is not a published 4.6.0 release. Report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)) and S12 adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) remain open. Shared-editor deployment evidence remains separate ([#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387)). Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) stay deferred.

## Start here

| Reader | Start with |
|---|---|
| New user | [Quick Start (English)](quick-start.md) · [繁體中文快速入門](quick-start.zh.md) |
| Test author | [Authoring model](concepts/authoring-model.md) · [Test authoring Reference](reference/test-authoring.md) |
| CLI user | [Current CLI Reference](reference/cli.md) · [Legacy ATT Remote guide](remote-client.md) |
| Load tester | [Load testing guide](guides/load-testing.md) · [Six-mode Load model](concepts/load-model.md) |
| Studio operator | [Studio navigation](interfaces/studio/navigation.md) |
| Server administrator | [Deployment](operations/server-deployment.md) · [Configuration](operations/server-configuration.md) · [Security](operations/security.md) · [Limits Reference](operations/limits-reference.md) · [繁體中文容量參考](operations/limits-reference.zh.md) |
| Integrator | [Server API v1](interfaces/server-api/README.md) |
| Contributor | [Documentation architecture](system-design/documentation-architecture.md) · [Reference source guide](reference/README.md) |

The Quick Start leads with the Native Case happy path on the 4.6.0 release-target stack. VALIDATE is read-only. Legacy Workbook snapshot generation remains an explicit authoring step when you use that path.

~~~sh
./att.sh validate --package
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
~~~

## Common tasks

| I want to... | Go to |
|---|---|
| Understand Package ownership and isolation | [Package model](concepts/package-model.md) · [Package Manifest Reference](reference/package-manifest.md) |
| Choose Case, Template, Flow or Tool authoring | [Authoring model](concepts/authoring-model.md) |
| Understand RUN, LOAD and VALIDATE | [Execution model](concepts/execution-model.md) |
| Configure shared defaults and environments | [Configuration model](concepts/configuration-model.md) · [Configuration Reference](reference/configuration.md) |
| Run functional tests | [RUN guide](guides/run-tests.md) · [Unified RUN Reference](reference/execution-modes/unified-run.md) |
| Walk through a multi-Flow Native Suite and its reports | [Native payments](guides/native-payments.md) · [繁體中文](guides/native-payments.zh.md) |
| Run a Template, Flow or Tool with diagnostics | [Debug Reference](reference/execution-modes/debug.md) |
| Configure DB and MQ helpers | [DBHelper Reference](reference/resources/dbhelper.md) · [MQHelper Reference](reference/resources/mqhelper.md) |
| Validate configuration or a Package | [Validation guide](guides/validate.md) · [Validation Reference](reference/validation-diagnostics.md) |
| Edit and publish Package or shared Runtime Config source | [Studio source authoring guide](interfaces/studio/source-authoring.md) · [繁體中文指南](interfaces/studio/source-authoring.zh.md) |
| Use ATT expressions and Context | [Expressions Reference](reference/expressions.md) · [Runtime Context Reference](reference/runtime-context.md) |
| Choose one of the six LOAD modes | [Load model](concepts/load-model.md) · [Load Reference](reference/execution-modes/load.md) |
| Run a Load test | [Load testing guide](guides/load-testing.md) |
| See how a Local Run ID differs from a Server Job | [Run and Job lifecycle](concepts/jobs-and-runs.md) |
| Use the legacy CLI surface from the 4.3.1 code line | [CLI Reference](reference/cli.md) |
| Review future vNext CLI syntax | [Proposed vNext CLI](interfaces/cli/vnext-proposed.md) — planned, not released |
| Use ATT Server from the existing client | [Legacy ATT Remote guide](remote-client.md) |
| Find REST, Jobs, events, or Schedule contracts | [Server API map](interfaces/server-api/README.md) |
| Operate Studio or schedules | [Studio interfaces](interfaces/studio/) · [Jobs and Runs](concepts/jobs-and-runs.md) |
| Review schemas and versioned contracts | [Generated Reference Manual](reference.md) · [繁體中文 Reference](reference.zh.md) |
| Build and package ATT | [CI and packaging operations](reference/ci-packaging-operations.md) |
| Understand Engine execution flow | [Runtime execution design](system-design/runtime-execution.md) |
| Compare legacy Stages with Native Flows | [Template–Flow–Stage compatibility matrix](system-design/template-flow-stage-compatibility.md) · [繁體中文版](system-design/template-flow-stage-compatibility.zh.md) |
| Diagnose capacity limits and truncation | [Limits Reference](operations/limits-reference.md) · [繁體中文容量參考](operations/limits-reference.zh.md) · [Internal limits design](system-design/internal-limits-diagnostics.md) |
| Design high-resolution LOAD history retention | [LOAD time-series retention follow-up for #299](system-design/load-time-series-retention.md) · [繁體中文設計](system-design/load-time-series-retention.zh.md) |

## Product and interface status

| Capability | Legacy Local CLI (4.3.1 code line) | ATT Remote | Studio / REST | Availability note |
|---|---|---|---|---|
| Functional Case RUN | Current legacy run | Current legacy remote run | Current | Local CLI selection is workbook-based |
| Native Case, Excel source, Studio editor, Suites, reports and examples | Integrated on the current default branch (`att-testcase/v1`, `att-execution/v2`) | Integrated when the Server advertises `att-execution/v2` | Integrated in Studio and REST drafts | Implemented and integrated on master. #393 is closed for Native RUN acceptance. #394, #395, #396, #397 and #413 remain open. No 4.6.0 release or tag exists. #418, #419 and #420 stay deferred |
| Template, Flow and Tool execution | Current legacy debug | Current legacy remote debug | Current unified RUN | Standalone Debug remains a compatibility command |
| LOAD | Current legacy load | Current legacy remote load | Current | Six workload shapes are documented in the Load model |
| VALIDATE | Current local validate | Current remote validate | Current | Read-only checks; no business execution |
| CLI vNext run, load, validate | Planned | Planned contract | Not a Studio or REST removal | The vNext grammar remains planned and is not part of the ATT 4.6.0 release target |
| Jobs and Schedules | Not Local CLI resources | Current att remote Job management | Current Server lifecycle | Local Run IDs are not Server Job IDs |
| #294 management CLI resources | Not available | Not part of vNext proposal | Server/Studio capabilities are separate | Future P3 proposal |

## Documentation types

- **Getting started and Guides** show how to complete a task with the current release.
- **Concepts** explain the model and link to the normative owner.
- **Reference** defines public behavior and versioned contracts. The editable paired sources are in [reference/](reference/) and [reference.zh/](reference.zh/); the manuals are generated.
- **Interfaces** explain CLI, Studio and REST entry points without creating competing semantic definitions.
- **Operations** owns Server deployment, configuration, security and operational policy.
- **System Design** documents maintainer architecture and benchmarks.
- **History** preserves superseded and release-specific records in [history/README.md](history/README.md).

## Current tree

~~~text
docs/
  README.md                         audience and task navigation
  quick-start.md / quick-start.zh.md current runnable tutorial
  concepts/ and guides/             task guidance linked to Reference
  interfaces/                       CLI, Studio and versioned Server API
  operations/                        deployment, config, security, lifecycle, capacity
  reference/ and reference.zh/       paired normative source modules
  reference.md / reference.zh.md     generated manuals
  system-design/                     maintainer docs and inventory
  history/                           preserved superseded material
~~~

The Server deployment and Studio legacy paths remain as short compatibility pages. The API details now have a versioned owner under [interfaces/server-api/](interfaces/server-api/).

For contributor checks, regenerate the manuals with python3 -B tools/build_reference_manual.py and run python3 -B tools/verify_documentation.py. The latter checks generated freshness, schemas, executable Current examples, status rules, links and language parity.
