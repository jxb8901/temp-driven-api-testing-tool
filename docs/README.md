# ATT documentation

This is the documentation map for ATT's 4.6.0 development line. No 4.6.0 release or tag has been published. The map separates task guidance from normative Reference contracts.

The current branch includes Native Case resolution and RUN for inline and Excel sources, Studio editing and preview, implicit Suites, and reports. Native RUN acceptance under [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed. Report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)) and Default Package adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) remain open. Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) remain deferred.

## Start here

| Reader | Start with |
|---|---|
| New user | [Quick Start (English)](quick-start.md) · [繁體中文快速入門](quick-start.zh.md) |
| Test author | [Authoring model](concepts/authoring-model.md) · [Test authoring Reference](reference/test-authoring.md) |
| CLI user | [Current CLI Reference](reference/cli.md) |
| Load tester | [Load testing guide](guides/load-testing.md) · [Six-mode Load model](concepts/load-model.md) |
| Studio operator | [Studio navigation](interfaces/studio/navigation.md) |
| Server administrator | [Deployment](operations/server-deployment.md) · [Configuration](operations/server-configuration.md) · [Security](operations/security.md) · [Limits Reference](operations/limits-reference.md) · [繁體中文容量參考](operations/limits-reference.zh.md) |
| Integrator | [Server API v1](interfaces/server-api/README.md) |
| Contributor | [Documentation architecture](system-design/documentation-architecture.md) · [Reference source guide](reference/README.md) |

The Quick Start leads with the Native Case path. VALIDATE is read-only.

~~~sh
./att.sh validate --package
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
~~~

## Common tasks

| I want to... | Go to |
|---|---|
| Understand Package ownership and isolation | [Package model](concepts/package-model.md) · [Package Manifest Reference](reference/package-manifest.md) |
| Author a Case and Flow | [Authoring model](concepts/authoring-model.md) |
| Understand RUN, LOAD and VALIDATE | [Execution model](concepts/execution-model.md) |
| Configure shared defaults and environments | [Configuration model](concepts/configuration-model.md) · [Configuration Reference](reference/configuration.md) |
| Run functional tests | [RUN guide](guides/run-tests.md) · [Unified RUN Reference](reference/execution-modes/unified-run.md) |
| Walk through a multi-Flow Native Suite and its reports | [Native payments](guides/native-payments.md) · [繁體中文](guides/native-payments.zh.md) |
| Run a Flow or Tool with diagnostics | [Debug Reference](reference/execution-modes/debug.md) |
| Configure DB and MQ helpers | [DBHelper Reference](reference/resources/dbhelper.md) · [MQHelper Reference](reference/resources/mqhelper.md) |
| Validate configuration or a Package | [Validation guide](guides/validate.md) · [Validation Reference](reference/validation-diagnostics.md) |
| Edit and publish Package or shared Runtime Config source | [Studio source authoring guide](interfaces/studio/source-authoring.md) · [繁體中文指南](interfaces/studio/source-authoring.zh.md) |
| Use ATT expressions and Context | [Expressions Reference](reference/expressions.md) · [Runtime Context Reference](reference/runtime-context.md) |
| Choose one of the six LOAD modes | [Load model](concepts/load-model.md) · [Load Reference](reference/execution-modes/load.md) |
| Run a Load test | [Load testing guide](guides/load-testing.md) |
| See how a Local Run ID differs from a Server Job | [Run and Job lifecycle](concepts/jobs-and-runs.md) |
| Review future vNext CLI syntax | [Proposed vNext CLI](interfaces/cli/vnext-proposed.md) — planned, not released |
| Find REST, Jobs, events, or Schedule contracts | [Server API map](interfaces/server-api/README.md) |
| Operate Studio or schedules | [Studio interfaces](interfaces/studio/) · [Jobs and Runs](concepts/jobs-and-runs.md) |
| Review schemas and versioned contracts | [Generated Reference Manual](reference.md) · [繁體中文 Reference](reference.zh.md) |
| Build and package ATT | [CI and packaging operations](reference/ci-packaging-operations.md) |
| Understand Engine execution flow | [Runtime execution design](system-design/runtime-execution.md) |
| Diagnose capacity limits and truncation | [Limits Reference](operations/limits-reference.md) · [繁體中文容量參考](operations/limits-reference.zh.md) · [Internal limits design](system-design/internal-limits-diagnostics.md) |
| Design high-resolution LOAD history retention | [LOAD time-series retention follow-up for #299](system-design/load-time-series-retention.md) · [繁體中文設計](system-design/load-time-series-retention.zh.md) |

## Capability status

| Capability | Status |
|---|---|
| Inline and Excel Case RUN | Integrated; uses `att-testcase/v1` and `att-execution/v2` |
| Implicit Suite selection | Integrated; acceptance remains open under [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) |
| Native result reports | Integrated; acceptance remains open under [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) |
| Native LOAD Case targets | Deferred under [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418) |
| Testdata generators | Deferred under [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419) |
| Debug interoperability | Deferred under [#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420) |

## Documentation types

- **Getting started and Guides** show how to complete a task with the current release.
- **Concepts** explain the model and link to the normative owner.
- **Reference** defines public behavior and versioned contracts. The editable paired sources are in [reference/](reference/) and [reference.zh/](reference.zh/); the manuals are generated.
- **Legacy documentation** holds authoring and execution guidance for existing Packages. English: [Legacy documentation](legacy/README.md). 繁體中文：[舊版文件](legacy/README.zh.md)。
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
  legacy/                             bilingual guidance for existing Packages
  system-design/                     maintainer docs and inventory
  history/                           preserved superseded material
~~~

The API details have a versioned owner under [interfaces/server-api/](interfaces/server-api/).

For contributor checks, regenerate the manuals with python3 -B tools/build_reference_manual.py and run python3 -B tools/verify_documentation.py. The latter checks generated freshness, schemas, executable Current examples, status rules, links and language parity.
