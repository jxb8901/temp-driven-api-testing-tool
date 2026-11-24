# Template, Flow and Stage compatibility (S10 / #398)

- **Status:** Compatibility analysis and focused golden fixture; not an accepted replacement contract.
- **Scope:** Existing workbook/Sidecar Stage execution versus Native Case execution through one Flow.
- **Release track:** ATT 4.6.0 release target; release and tag have not been published.
- **Runtime changes:** None in this slice. The golden fixture exercises the current public APIs and pins observed differences.
- **Acceptance status:** S10 remains an analysis baseline, not an accepted replacement contract. Native RUN acceptance ([#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393)) is closed and is not a published 4.6.0 release. Report acceptance ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)) and Default Package adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)) remain open. Shared-editor deployment evidence is separate ([#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387)).
- **Related:** [Issue #398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398), [ADR-0001](../adr/0001-flow-centric-testcase-resolution.md), [Traditional Chinese](template-flow-stage-compatibility.zh.md).

## Acceptance summary

The current evidence supports a phased compatibility effort, not a whole-engine replacement. The focused fixture demonstrates that both execution paths can preserve a failed Verify result while allowing a rollback action and final cleanup action to run. It also records differences that must remain visible: legacy Stage input overlays are temporary and restored at each Stage boundary; Native Case Flow inputs are case-wide; result trees and event granularity differ.

This fixture uses log actions as rollback and cleanup markers. It does not prove a database transaction was reversed, an external service was compensated, or every helper's teardown behavior is equivalent. Those require separate side-effect fixtures.

Representative Helper rollback and teardown effects are deferred to [#421](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/421). Final ADR approval and architecture closure follow the 4.6.0 target under [#422](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/422); this follow-up is not a target release blocker.

## Current execution paths

| Path | Selection and execution |
| --- | --- |
| Legacy Case | XLSX row + Sidecar + generated XML snapshot → ordered Stage selectors → row-selected Templates → Template Actions, with optional nested Flow calls. |
| Native Case | att-testcase/v1 Definition + resolved Case inputs → one entry Flow → ordered Flow Actions and nested Flow calls. The current RUN adapter wraps that Flow in a synthetic run Stage for evidence. |

The Stage path remains a supported execution contract. S10 does not remove workbook, Sidecar, snapshot, Template, or Stage behavior.

## Compatibility matrix

| Concern | Legacy Stage behavior | Native Flow behavior | Assessment |
| --- | --- | --- | --- |
| Order and selection | Sidecar fixes ordered Stage keys; each workbook row selects a Template for each required Stage. Missing required Stage data fails the Case. | A Definition binds one entry Flow; Flow Actions have fixed order. There is no row-selected Stage/Template list. | **Different source model.** Keep the Stage adapter; compare each migrated scenario explicitly. |
| Failure and continuation | Stage runWhen uses prior Stage failure and stop state. Stage onFailure controls whether later normal Stages are stopped. | Action runWhen is an expression; action onFailure controls continuation inside the Flow. | **Similar primitives, different boundaries.** The fixture proves one explicit mapping for failure → rollback → cleanup, not general equivalence. |
| Input overlays and restoration | Case inputs seed EXEC.INPUT. Current Stage values override them while that Stage runs, then the previous values are restored when it ends or the next Stage begins. | Resolved Definition inputs seed EXEC.INPUT for the Case. Flow scopes isolate action evidence; they do not create a Stage input overlay or restore input values at Flow boundaries. | **Known gap.** The fixture proves a Stage override is visible only during Invoke and that the final Case input returns to BASE; Flow stays at BASE throughout. |
| Case variables | EXEC.VARS values survive Stage boundaries. | EXEC.VARS values survive Actions and Flow calls in the Case. | **Observed common behavior.** Golden fixture pins one assignment/read across phases. |
| Result status | Case status aggregates executed Stage/action results. A later successful rollback or cleanup does not erase an earlier FAIL. | Flow status aggregates its Action results. A successful later action does not erase an earlier FAIL. | **Observed common behavior for fixture.** More action types and ERROR/INVALID paths still need cases. |
| Rollback and cleanup | Commonly modeled as a Stage selected with onFailure and a Stage selected with always. Resource cleanup is also owned by case finalization paths. | Can be modeled as ordered Actions with explicit runWhen/onFailure policy. It does not automatically invoke a compensation or transaction rollback. | **Partial mapping only.** Marker Actions are covered; DB/MQ/HTTP/SSH compensation and teardown require dedicated fixtures. |
| Event stream | Emits Stage start/finish events and per-action completion events, keyed by legacy Case and Stage IDs. | Native RUN emits Case/run events and an outer Flow invocation completion; this path does not emit one event per inner Flow Action. | **Different event contract.** Consumers must not infer a one-to-one event mapping. |
| Result tree and logs | Stage evidence is under STAGES.<stage>.TEMPLATE.ACTIONS. Each Case writes case.yaml and case.log below the legacy run/case layout. | The current synthetic run Stage stores the Flow invocation and nested actions under STAGES.run.TEMPLATE.ACTIONS.run.flow.actions. Native outputs are under a run's executions/<executionId> directory. | **Different evidence shape and path.** The fixture checks required files and representative nodes, not byte equality. |
| Nested Flow scope | A Template Action may invoke a Flow; Flow evidence is nested under that Stage action. | The entry Flow has its own action namespace and Flow metadata; nested calls keep nested evidence. | **Shared runner mechanics, different parent path.** This fixture does not compare nested call-chain evidence or META.FLOW scope. |
| Identity and reference checks | Case identity comes from Workbook/Sidecar selectors; Template and referenced Flow IDs are resolved by the legacy execution plan. | Definition, Qualified Case, source revision, and entry Flow are resolved and captured by the Native resolver. | **Different identity contracts.** Keep legacy IDs/replay stable; compare version, cycle, depth, and reference-failure cases separately. |
| Resource finalization | Case finalization owns cleanup for the resources opened by the legacy execution. | Native RUN has its own Case finalization path for selected Flow execution. | **Not proven equivalent.** This log-marker fixture does not exercise a DB transaction or external resource teardown. |
| Retries, timeouts, nested Flow errors | Existing Template Actions and nested Flow calls use the shared runner, while Stage progression is handled outside it. | Native Flow also uses the shared action/Flow runner, with native source identity and provenance. | **Shared machinery, incomplete paired proof.** Preserve existing tests and add golden cases for each migration boundary. |
| LOAD, Testdata/generators, Debug | Existing Stage/Template and direct LOAD/Debug behaviors remain current contracts. | Native Case LOAD targets, advanced Testdata/generators, and Debug interoperability are separate follow-ups. | **Deferred from 4.6.0.** S08 ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), S09 ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and S11 ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) remain out of scope; #388 stays open. |

## Golden fixture

The focused test is FrameworkEngineTest.legacyStageAndNativeFlowGoldenFixturePinsCompatibilityBoundary. It runs the same logical Case intent through:

1. Legacy prepare and invoke Stages, with an Invoke-only input overlay.
2. A deliberately failing Verify.
3. Rollback after failure and unconditional cleanup.
4. A Native Definition with one Flow using the closest current Action-level conditions.

Both runs must finish FAIL, preserve the failure in the Case result, record rollback and cleanup markers, and write case.yaml and case.log. The test also pins the Stage-only input overlay/restoration, the Native case-wide input, the different evidence paths, and the different event grain.

The fixture is a compatibility probe, not a promise that every legacy Stage graph can be translated to a Flow without an adapter.

## Remaining gaps

- Define and test how row-specific Stage/Template selection maps to a Definition and Flow without losing source-row identity.
- Decide whether Flow needs a versioned, explicitly scoped equivalent for Stage input overlays; do not mutate EXEC.INPUT implicitly to simulate one.
- Compare normal, onSuccess, onFailure, always, stop, and continue across multiple failure positions, including ERROR and INVALID outcomes.
- Add real compensation/rollback tests for database and external operations, then prove resource finalization and cleanup order.
- Evaluate existing Flow Action assertions (#34) and evidence collectors (#35) before proposing any new execution-control capability; this slice adds neither.
- Compare nested Flow call-chain evidence, META.FLOW scope, and ID/version/depth/cycle diagnostics across both entry paths.
- Compare retries, timeout/cancellation, nested Flow diagnostics, report identities, rerun selection, and evidence retention on the exact public outputs.
- Preserve LOAD, Testdata/generator, and Debug contracts while those follow-ups are designed and accepted.

## Safe phases

1. **S10 baseline (this slice):** publish the matrix and paired golden fixture; retain every existing execution path.
2. **Focused gap decisions:** for each mismatch, choose an adapter, an additive versioned Flow feature, or an intentional permanent difference. Record its impact on inputs, identity, events, artifacts, and replay before implementation.
3. **Small compatibility increments:** add one behavior and paired regressions per change; keep the legacy path executable throughout.
4. **Deferred interoperability:** plan S08 LOAD, S09 Testdata/generators, and S11 Debug independently with their own public-contract and acceptance fixtures.

Do not schedule an all-at-once FrameworkEngine rewrite or remove the legacy Stage path on the basis of this analysis.
